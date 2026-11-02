package com.github.auties00.smalt.io;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.dash.DashDemuxer;
import com.github.auties00.smalt.container.hls.HlsDemuxer;
import com.github.auties00.smalt.container.packaging.Packager;
import com.github.auties00.smalt.container.packaging.PackagingOptions;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.LocalSource;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the HTTP source against a local server of fixtures ({@link FixtureServer}): probing, demuxing and seeking
 * give what the same file gives through a path, with a bounded number of requests and no byte fetched twice while
 * the cache holds the file; a server without range support or without a length falls back to one front-to-back
 * response; transient statuses and truncated bodies are retried, and a resource that changes fails.
 */
@DisplayName("HTTP media source")
class HttpMediaSourceTest {
    private static final HttpSourceOptions FAST = HttpSourceOptions.builder().retryDelay(Duration.ZERO).build();

    private static FixtureServer server;

    @BeforeAll
    static void start() throws IOException {
        server = new FixtureServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @BeforeEach
    void reset() {
        server.reset();
    }

    private static byte[] random(int length, long seed) {
        var data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    static List<String> packets(MediaSource source) {
        return StreamSourceDemuxTest.packets(source);
    }

    static Stream<Arguments> containers() {
        return StreamSourceDemuxTest.containers().filter(arguments -> List.of("mp4 fast start", "mp4 moov after mdat",
                "fragmented mp4", "mkv h264 + opus", "ts h264 + aac", "mp3", "flac", "avi h264 + mp3")
                .contains((String) arguments.get()[0]));
    }

    private static byte[] fixture(Object[] arguments) throws IOException {
        @SuppressWarnings("unchecked")
        var options = (List<String>) arguments[1];
        return Files.readAllBytes(StreamSourceDemuxTest.fixture((String) arguments[2], options, (double) arguments[3]));
    }

    @Nested
    @DisplayName("with range requests")
    class Ranges {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.io.HttpMediaSourceTest#containers")
        @DisplayName("demuxes and probes as the file does, fetching each byte at most once")
        void demux(String name, List<String> options, String extension, double seconds, int boundKiB)
                throws IOException {
            var data = fixture(new Object[]{name, options, extension, seconds});
            var path = "/demux/" + name.replace(' ', '_') + "." + extension;
            server.put(path, data).etag = "\"" + Arrays.hashCode(data) + "\"";
            var expected = packets(MediaSource.of(data));
            var http = FAST.toBuilder().cacheSize(64L << 20).build();
            try (var source = MediaSource.of(server.uri(path), http)) {
                assertInstanceOf(HttpMediaSource.class, source);
                assertEquals(data.length, source.size());
                assertFalse(LocalSource.isLocal(source));
                assertEquals(expected, packets(source));
                assertEquals(Media.probe(MediaSource.of(data)).toString(), Media.probe(source).toString());
            }
            var blocks = (data.length + http.blockSize() - 1) / http.blockSize();
            assertTrue(server.requests() <= blocks + 1, server.requests() + " requests for " + blocks + " blocks");
            assertTrue(server.bodyBytes() <= data.length, server.bodyBytes() + " bytes sent for " + data.length);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.io.HttpMediaSourceTest#containers")
        @DisplayName("seeks as the file does, with a cache far smaller than the file")
        void seek(String name, List<String> options, String extension, double seconds, int boundKiB)
                throws IOException {
            var data = fixture(new Object[]{name, options, extension, seconds});
            var path = "/seek/" + name.replace(' ', '_') + "." + extension;
            server.put(path, data);
            var small = FAST.toBuilder().blockSize(16 << 10).cacheSize(128 << 10).readAhead(32 << 10).build();
            try (var file = MediaSource.of(data); var expected = Containers.openDemuxer(file);
                 var source = MediaSource.of(server.uri(path), small); var actual = Containers.openDemuxer(source)) {
                assertEquals(expected.isSeekable(), actual.isSeekable());
                if (!expected.isSeekable()) {
                    return;
                }
                var duration = expected.durationMicros();
                for (var fraction : new double[]{0.7, 0.2, 0.9, 0.0, 0.5}) {
                    var micros = (long) (duration * fraction);
                    expected.seek(micros);
                    actual.seek(micros);
                    for (var count = 0; count < 20; count++) {
                        var want = expected.read();
                        var got = actual.read();
                        assertEquals(describe(want), describe(got), name + " after seeking to " + micros);
                        if (want == null) {
                            break;
                        }
                    }
                }
            }
        }

        private static String describe(Packet packet) {
            return packet == null ? "end" : packet.trackId() + " " + packet.pts() + " " + packet.dts() + " "
                    + packet.flags() + " " + Arrays.hashCode(packet.data());
        }

        @Test
        @DisplayName("reads front to back in a growing window: few requests with read-ahead, one per block without")
        void readAhead() {
            var data = random(3 << 20, 1);
            server.put("/sequential.bin", data);
            var chunk = new byte[4096];
            try (var source = MediaSource.of(server.uri("/sequential.bin"), FAST)) {
                for (var at = 0; at < data.length; at += chunk.length) {
                    source.readFully(at, chunk, 0, chunk.length);
                    assertArrayEquals(Arrays.copyOfRange(data, at, at + chunk.length), chunk);
                }
            }
            assertTrue(server.requests() <= 10, server.requests() + " requests");
            assertEquals(data.length, server.bodyBytes());
            server.reset();
            try (var source = MediaSource.of(server.uri("/sequential.bin"), FAST.toBuilder().readAhead(0).build())) {
                for (var at = 0; at < data.length; at += chunk.length) {
                    source.readFully(at, chunk, 0, chunk.length);
                }
            }
            assertEquals(data.length / HttpSourceOptions.DEFAULT_BLOCK_SIZE, server.requests());
        }

        @Test
        @DisplayName("serves one large read with few requests, each at most half the cache")
        void largeRead() {
            var data = random(5 << 20, 2);
            server.put("/large.bin", data);
            var options = FAST.toBuilder().cacheSize(1 << 20).build();
            var read = new byte[data.length];
            try (var source = MediaSource.of(server.uri("/large.bin"), options)) {
                source.readFully(0, read, 0, read.length);
            }
            assertArrayEquals(data, read);
            assertTrue(server.requests() <= 12, server.requests() + " requests");
            for (var range : server.ranges()) {
                var spec = range.substring(range.indexOf('=') + 1).split("-");
                assertTrue(Long.parseLong(spec[1]) - Long.parseLong(spec[0]) + 1 <= options.cacheSize() / 2, range);
            }
        }

        @Test
        @DisplayName("reads random ranges from many threads with a cache of four blocks")
        void concurrent() throws Exception {
            var data = random(1 << 20, 3);
            server.put("/random.bin", data);
            var options = FAST.toBuilder().blockSize(4096).cacheSize(4 * 4096).readAhead(8192).build();
            try (var source = MediaSource.of(server.uri("/random.bin"), options);
                 var executor = Executors.newFixedThreadPool(8)) {
                var tasks = new ArrayList<Callable<Void>>();
                for (var thread = 0; thread < 8; thread++) {
                    var seed = thread;
                    tasks.add(() -> {
                        var random = new Random(seed);
                        for (var index = 0; index < 150; index++) {
                            var position = random.nextInt(data.length);
                            var length = 1 + random.nextInt(20_000);
                            var buffer = new byte[length];
                            var read = source.read(position, buffer, 0, length);
                            assertTrue(read > 0);
                            assertArrayEquals(Arrays.copyOfRange(data, position, position + read),
                                    Arrays.copyOf(buffer, read));
                        }
                        return null;
                    });
                }
                for (var future : executor.invokeAll(tasks)) {
                    future.get();
                }
            }
        }

        @Test
        @DisplayName("follows the source contract at the edges and after close")
        void contract() {
            var data = random(10_000, 4);
            server.put("/edges.bin", data);
            var source = MediaSource.of(server.uri("/edges.bin"), FAST);
            var buffer = new byte[100];
            assertEquals(0, source.read(5, buffer, 0, 0));
            assertEquals(-1, source.read(data.length, buffer, 0, 10));
            assertEquals(-1, source.read(data.length + 5, buffer, 0, 10));
            assertEquals(10, source.read(data.length - 10, buffer, 0, 100));
            assertArrayEquals(Arrays.copyOfRange(data, data.length - 10, data.length), Arrays.copyOf(buffer, 10));
            assertThrows(IndexOutOfBoundsException.class, () -> source.read(-1, buffer, 0, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> source.read(0, buffer, 90, 20));
            assertThrows(MediaException.Malformed.class, () -> source.readFully(data.length - 5, buffer, 0, 10));
            assertTrue(LocalSource.isLocal(source, 0, data.length), "the probe holds the whole file");
            source.close();
            source.close();
            assertThrows(MediaException.Io.class, () -> source.read(0, buffer, 0, 1));
            assertEquals(1, server.requests());
        }

        @Test
        @DisplayName("serves reads of cached blocks without allocating")
        void cachedReadsAllocateNothing() throws ReflectiveOperationException {
            var data = random(1 << 20, 24);
            server.put("/hits.bin", data);
            // the management modules are not read by the patched test module at run time, hence reflection
            var threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean")
                    .invoke(null);
            var allocatedBytes = Class.forName("com.sun.management.ThreadMXBean")
                    .getMethod("getCurrentThreadAllocatedBytes");
            var buffer = new byte[64];
            // small blocks, so that most block indices lie beyond the boxed values the JDK caches
            var options = FAST.toBuilder().blockSize(4096).cacheSize(4L << 20).build();
            try (var source = MediaSource.of(server.uri("/hits.bin"), options)) {
                source.readFully(0, new byte[data.length], 0, data.length);
                var requests = server.requests();
                var checksum = 0L;
                for (var pass = 0; pass < 2; pass++) {
                    var before = (long) allocatedBytes.invoke(threads);
                    for (var index = 0; index < 20_000; index++) {
                        var position = (index * 7919L * 64) % (data.length - buffer.length);
                        checksum += source.read(position, buffer, 0, buffer.length);
                    }
                    var allocated = (long) allocatedBytes.invoke(threads) - before;
                    if (pass == 1) {
                        assertTrue(allocated < 4096, allocated + " bytes allocated by 20000 cached reads");
                    }
                }
                assertEquals(2 * 20_000L * buffer.length, checksum);
                assertEquals(requests, server.requests());
            }
        }

        @Test
        @DisplayName("reports the cached ranges as local and the others as not")
        void local() {
            var data = random(200_000, 5);
            server.put("/local.bin", data);
            try (var source = MediaSource.of(server.uri("/local.bin"), FAST.toBuilder().blockSize(4096).build())) {
                assertTrue(LocalSource.isLocal(source, 0, 4096));
                assertFalse(LocalSource.isLocal(source, 0, 4097));
                assertFalse(LocalSource.isLocal(source, 100_000, 100_001));
                source.readFully(100_000, new byte[1], 0, 1);
                assertTrue(LocalSource.isLocal(source, 98_304, 102_400));
                assertTrue(LocalSource.isLocal(source, 7, 7));
                assertFalse(LocalSource.isLocal(source, 199_000, 200_001));
            }
        }

        @Test
        @DisplayName("opens an empty resource as an empty source")
        void empty() {
            server.put("/empty.bin", new byte[0]);
            try (var source = MediaSource.of(server.uri("/empty.bin"), FAST)) {
                assertEquals(0, source.size());
                assertEquals(-1, source.read(0, new byte[1], 0, 1));
            }
        }

        @Test
        @DisplayName("sends later requests to the URI the first one was redirected to")
        void redirect() {
            var data = random(300_000, 6);
            server.put("/final.bin", data);
            server.put("/moved.bin", new byte[0]).redirect = "/final.bin";
            var read = new byte[data.length];
            try (var source = MediaSource.of(server.uri("/moved.bin"), FAST.toBuilder().readAhead(0).build())) {
                source.readFully(0, read, 0, read.length);
            }
            assertArrayEquals(data, read);
            assertEquals(1, server.hits("/moved.bin"));
            assertTrue(server.hits("/final.bin") > 1);
        }

        @Test
        @DisplayName("adds the headers of the options to every request")
        void headers() {
            var data = random(200_000, 7);
            var resource = server.put("/private.bin", data);
            resource.requiredHeader = "Authorization";
            resource.requiredValue = "Bearer secret";
            var failure = assertThrows(MediaException.Io.class, () -> MediaSource.of(server.uri("/private.bin"), FAST));
            assertTrue(failure.getMessage().contains("401"), failure.getMessage());
            var options = FAST.toBuilder().header("Authorization", "Bearer secret").build();
            var read = new byte[data.length];
            try (var source = MediaSource.of(server.uri("/private.bin"), options)) {
                source.readFully(0, read, 0, read.length);
            }
            assertArrayEquals(data, read);
        }

        @Test
        @DisplayName("uses the client of the options")
        void client() {
            var data = random(70_000, 8);
            server.put("/client.bin", data);
            try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
                 var source = MediaSource.of(server.uri("/client.bin"), FAST.toBuilder().httpClient(client).build())) {
                var read = new byte[data.length];
                source.readFully(0, read, 0, read.length);
                assertArrayEquals(data, read);
            }
        }
    }

    @Nested
    @DisplayName("without range requests")
    class Sequential {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.io.HttpMediaSourceTest#containers")
        @DisplayName("falls back to one front-to-back response when the server ignores the range")
        void ignored(String name, List<String> options, String extension, double seconds, int boundKiB)
                throws IOException {
            var data = fixture(new Object[]{name, options, extension, seconds});
            var path = "/whole/" + name.replace(' ', '_') + "." + extension;
            server.put(path, data).ranges = false;
            var expected = packets(MediaSource.of(data));
            try (var source = MediaSource.of(server.uri(path), FAST)) {
                assertTrue(ForwardOnlySource.isForwardOnly(source));
                assertEquals(data.length, source.size());
                assertEquals(expected, packets(source));
            }
            assertEquals(1, server.requests());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.io.HttpMediaSourceTest#containers")
        @DisplayName("reads a chunked response of unknown length front to back")
        void chunked(String name, List<String> options, String extension, double seconds, int boundKiB)
                throws IOException {
            var data = fixture(new Object[]{name, options, extension, seconds});
            var path = "/chunked/" + name.replace(' ', '_') + "." + extension;
            var resource = server.put(path, data);
            resource.ranges = false;
            resource.chunked = true;
            var expected = packets(MediaSource.of(data));
            try (var source = MediaSource.of(server.uri(path), FAST)) {
                assertTrue(ForwardOnlySource.isForwardOnly(source));
                assertEquals(-1, source.size());
                assertEquals(expected, packets(source));
            }
            assertEquals(1, server.requests());
        }

        @Test
        @DisplayName("decodes a gzip body and rejects a content coding it cannot decode")
        void contentCoding() {
            var data = random(200_000, 17);
            var gzip = server.put("/gzip.bin", data);
            gzip.ranges = false;
            gzip.encoding = "gzip";
            var read = new byte[data.length];
            try (var source = MediaSource.of(server.uri("/gzip.bin"), FAST)) {
                assertEquals(-1, source.size());
                source.readFully(0, read, 0, read.length);
                assertEquals(-1, source.read(data.length, new byte[1], 0, 1));
            }
            assertArrayEquals(data, read);
            var deflate = server.put("/deflate.bin", data);
            deflate.ranges = false;
            deflate.encoding = "deflate";
            var inflated = new byte[data.length];
            try (var source = MediaSource.of(server.uri("/deflate.bin"), FAST)) {
                assertTrue(ForwardOnlySource.isForwardOnly(source));
                source.readFully(0, inflated, 0, inflated.length);
                assertEquals(-1, source.read(data.length, new byte[1], 0, 1));
            }
            assertArrayEquals(data, inflated);
            var brotli = server.put("/br.bin", data);
            brotli.ranges = false;
            brotli.encoding = "br";
            assertThrows(MediaException.Io.class, () -> MediaSource.of(server.uri("/br.bin"), FAST));
        }

        @Test
        @DisplayName("reads the whole resource front to back when a part states no total length")
        void unknownTotal() {
            var data = random(150_000, 18);
            server.put("/star.bin", data).unknownTotal = true;
            var read = new byte[data.length];
            try (var source = MediaSource.of(server.uri("/star.bin"), FAST)) {
                assertTrue(ForwardOnlySource.isForwardOnly(source));
                assertEquals(data.length, source.size());
                source.readFully(0, read, 0, read.length);
            }
            assertArrayEquals(data, read);
            assertEquals(List.of("/star.bin bytes=0-65535", "/star.bin null"), server.ranges());
        }

        @Test
        @DisplayName("keeps the stream options of the fallback")
        void streamOptions() {
            var data = random(1 << 20, 9);
            server.put("/budget.bin", data).ranges = false;
            var options = FAST.toBuilder().streamOptions(StreamSourceOptions.memoryOnly().toBuilder()
                    .memoryBudget(64 << 10).build()).build();
            try (var source = MediaSource.of(server.uri("/budget.bin"), options)) {
                assertThrows(MediaException.LimitExceeded.class, () -> source.readFully(0, new byte[1 << 20], 0,
                        1 << 20));
            }
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {
        @Test
        @DisplayName("retries transient statuses, then fails once the retries are spent")
        void transientStatuses() {
            var data = random(100_000, 10);
            server.put("/flaky.bin", data).fail(503, 502);
            try (var source = MediaSource.of(server.uri("/flaky.bin"), FAST)) {
                assertEquals(data.length, source.size());
            }
            assertEquals(3, server.requests());
            server.reset();
            var resource = server.put("/flaky2.bin", data);
            try (var source = MediaSource.of(server.uri("/flaky2.bin"), FAST.toBuilder().blockSize(4096).build())) {
                resource.fail(500, 504, 408);
                var read = new byte[data.length];
                source.readFully(0, read, 0, read.length);
                assertArrayEquals(data, read);
            }
            server.reset();
            server.put("/down.bin", data).fail(503, 503, 503);
            var failure = assertThrows(MediaException.Io.class,
                    () -> MediaSource.of(server.uri("/down.bin"), FAST.toBuilder().retries(2).build()));
            assertTrue(failure.getMessage().contains("503"), failure.getMessage());
            assertEquals(3, server.requests());
        }

        @Test
        @DisplayName("waits for a short Retry-After and fails at once on a long one")
        void retryAfter() {
            var data = random(10_000, 11);
            var resource = server.put("/later.bin", data).fail(503);
            resource.retryAfter = "1";
            var start = System.nanoTime();
            try (var source = MediaSource.of(server.uri("/later.bin"), FAST)) {
                assertEquals(data.length, source.size());
            }
            assertTrue(System.nanoTime() - start >= 900_000_000L, "the Retry-After of one second is honored");
            server.reset();
            var closed = server.put("/closed.bin", data).fail(503);
            closed.retryAfter = "3600";
            assertThrows(MediaException.Io.class, () -> MediaSource.of(server.uri("/closed.bin"), FAST));
            assertEquals(1, server.requests());
        }

        @Test
        @DisplayName("fails at once on a final status")
        void finalStatus() {
            assertThrows(MediaException.Io.class, () -> MediaSource.of(server.uri("/missing.bin"), FAST));
            assertEquals(1, server.requests());
        }

        @Test
        @DisplayName("resumes a response that ends early")
        void truncated() {
            var data = random(400_000, 12);
            var resource = server.put("/cut.bin", data);
            resource.truncations.set(3);
            var read = new byte[data.length];
            try (var source = MediaSource.of(server.uri("/cut.bin"), FAST.toBuilder().blockSize(4096).build())) {
                source.readFully(0, read, 0, read.length);
            }
            assertArrayEquals(data, read);
            server.reset();
            var always = server.put("/broken.bin", data);
            try (var source = MediaSource.of(server.uri("/broken.bin"), FAST.toBuilder().blockSize(4096).retries(2)
                    .readAhead(0).build())) {
                always.truncations.set(Integer.MAX_VALUE);
                assertThrows(MediaException.Io.class, () -> source.readFully(100_000, new byte[10], 0, 10));
            }
        }

        @Test
        @DisplayName("continues a response that ends inside a block from where it stopped")
        void resumedInsideBlock() {
            var data = random(200_000, 21);
            var resource = server.put("/half.bin", data);
            var options = FAST.toBuilder().blockSize(4096).readAhead(0).build();
            try (var source = MediaSource.of(server.uri("/half.bin"), options)) {
                resource.truncations.set(1);
                var read = new byte[10];
                source.readFully(100_000, read, 0, read.length);
                assertArrayEquals(Arrays.copyOfRange(data, 100_000, 100_010), read);
            }
            // block 24 starts at 98304; the cut response delivers half of it, the next request asks for the rest
            assertEquals(List.of("/half.bin bytes=0-4095", "/half.bin bytes=98304-102399",
                    "/half.bin bytes=100352-102399"), server.ranges());
        }

        @Test
        @DisplayName("reads a server that sends shorter parts than a block")
        void cappedParts() {
            var data = random(150_000, 22);
            server.put("/capped.bin", data).maxPart = 1000;
            var read = new byte[data.length];
            try (var source = MediaSource.of(server.uri("/capped.bin"), FAST.toBuilder().blockSize(4096).build())) {
                assertEquals(data.length, source.size());
                source.readFully(0, read, 0, read.length);
            }
            assertArrayEquals(data, read);
            assertTrue(server.bodyBytes() <= data.length + 1000, server.bodyBytes() + " bytes sent");
        }

        @Test
        @DisplayName("times out a stalled body and resumes it, or fails a forward-only read")
        void stalled() {
            var data = random(200_000, 23);
            var resource = server.put("/stall.bin", data);
            var options = FAST.toBuilder().blockSize(4096).readAhead(0).requestTimeout(Duration.ofMillis(300)).build();
            var start = System.nanoTime();
            try (var source = MediaSource.of(server.uri("/stall.bin"), options)) {
                resource.stalls.set(1);
                var read = new byte[10];
                source.readFully(100_000, read, 0, read.length);
                assertArrayEquals(Arrays.copyOfRange(data, 100_000, 100_010), read);
            }
            // the server holds each stalled response for three seconds
            assertTrue(System.nanoTime() - start < 2_500_000_000L, "the stalled body was not timed out");
            assertEquals(List.of("/stall.bin bytes=0-4095", "/stall.bin bytes=98304-102399",
                    "/stall.bin bytes=100352-102399"), server.ranges());
            var whole = server.put("/stall-whole.bin", data);
            whole.ranges = false;
            whole.stalls.set(1);
            var forward = System.nanoTime();
            try (var source = MediaSource.of(server.uri("/stall-whole.bin"), options)) {
                assertThrows(MediaException.Io.class, () -> source.readFully(0, new byte[data.length], 0,
                        data.length));
            }
            assertTrue(System.nanoTime() - forward < 2_500_000_000L, "the stalled body was not timed out");
        }

        @Test
        @DisplayName("fails a read of a resource that changed, by entity tag, by date and by length")
        void changed() {
            var data = random(300_000, 13);
            var tagged = server.put("/tagged.bin", data);
            tagged.etag = "\"v1\"";
            try (var source = MediaSource.of(server.uri("/tagged.bin"), FAST)) {
                tagged.data = random(300_000, 14);
                tagged.etag = "\"v2\"";
                var failure = assertThrows(MediaException.Io.class, () -> source.readFully(200_000, new byte[10], 0,
                        10));
                assertTrue(failure.getMessage().contains("changed"), failure.getMessage());
            }
            assertTrue(server.ranges().stream().noneMatch(range -> range.contains("null")));
            var dated = server.put("/dated.bin", data);
            dated.lastModified = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)
                    .minusDays(1));
            dated.etag = "W/\"weak\"";
            try (var source = MediaSource.of(server.uri("/dated.bin"), FAST)) {
                dated.lastModified = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));
                assertThrows(MediaException.Io.class, () -> source.readFully(200_000, new byte[10], 0, 10));
            }
            var plain = server.put("/plain.bin", data);
            try (var source = MediaSource.of(server.uri("/plain.bin"), FAST)) {
                plain.data = random(250_000, 15);
                assertThrows(MediaException.Io.class, () -> source.readFully(200_000, new byte[10], 0, 10));
            }
            var stopped = server.put("/stopped.bin", data);
            try (var source = MediaSource.of(server.uri("/stopped.bin"), FAST)) {
                stopped.ranges = false;
                assertThrows(MediaException.Io.class, () -> source.readFully(200_000, new byte[10], 0, 10));
            }
        }

        @Test
        @DisplayName("sends the strong entity tag, or a strong date without an entity tag, as If-Range")
        void ifRange() {
            var data = random(200_000, 19);
            var dayAgo = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).minusDays(1));
            var strong = server.put("/strong.bin", data);
            strong.etag = "\"s\"";
            strong.lastModified = dayAgo;
            var weak = server.put("/weak.bin", data);
            weak.etag = "W/\"w\"";
            weak.lastModified = dayAgo;
            server.put("/old.bin", data).lastModified = dayAgo;
            // modified after the Date of its response: a weak date, never sent as a validator
            server.put("/fresh.bin", data).lastModified = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    ZonedDateTime.now(ZoneOffset.UTC).plusDays(1));
            for (var path : List.of("/strong.bin", "/weak.bin", "/old.bin", "/fresh.bin")) {
                try (var source = MediaSource.of(server.uri(path), FAST)) {
                    source.readFully(150_000, new byte[10], 0, 10);
                }
            }
            assertEquals(List.of("/strong.bin null", "/strong.bin \"s\"", "/weak.bin null", "/weak.bin null",
                    "/old.bin null", "/old.bin " + dayAgo, "/fresh.bin null", "/fresh.bin null"), server.ifRanges());
        }

        @Test
        @DisplayName("rejects a compressed part and a part that starts elsewhere, and reads a part longer than asked")
        void malformedParts() {
            var data = random(300_000, 20);
            server.put("/coded.bin", data).encodeRanges = true;
            var coded = assertThrows(MediaException.Io.class, () -> MediaSource.of(server.uri("/coded.bin"), FAST));
            assertTrue(coded.getMessage().contains("gzip"), coded.getMessage());
            server.put("/shifted.bin", data).rangeShift = 1;
            assertThrows(MediaException.Io.class, () -> MediaSource.of(server.uri("/shifted.bin"), FAST));
            var later = server.put("/later-shift.bin", data);
            try (var source = MediaSource.of(server.uri("/later-shift.bin"), FAST)) {
                later.rangeShift = 1;
                assertThrows(MediaException.Io.class, () -> source.readFully(200_000, new byte[10], 0, 10));
            }
            var laterCoded = server.put("/later-coded.bin", data);
            try (var source = MediaSource.of(server.uri("/later-coded.bin"), FAST)) {
                laterCoded.encodeRanges = true;
                assertThrows(MediaException.Io.class, () -> source.readFully(200_000, new byte[10], 0, 10));
            }
            server.reset();
            server.put("/over.bin", data).overDeliver = true;
            var read = new byte[data.length];
            try (var source = MediaSource.of(server.uri("/over.bin"), FAST.toBuilder().blockSize(4096).cacheSize(8192)
                    .readAhead(0).build())) {
                source.readFully(0, read, 0, read.length);
                source.readFully(70_000, read, 0, 10);
                assertArrayEquals(Arrays.copyOfRange(data, 70_000, 70_010), Arrays.copyOf(read, 10));
                source.readFully(0, read, 0, read.length);
            }
            assertArrayEquals(data, read);
        }

        @Test
        @DisplayName("rejects URIs it cannot fetch")
        void invalidUri() {
            assertThrows(IllegalArgumentException.class, () -> MediaSource.of(URI.create("file:///tmp/a.mp4")));
            assertThrows(IllegalArgumentException.class, () -> MediaSource.of(URI.create("relative/a.mp4")));
            assertThrows(IllegalArgumentException.class, () -> MediaSource.of(URI.create("http:/no-host")));
            assertThrows(NullPointerException.class, () -> MediaSource.of((URI) null));
        }

        @Test
        @DisplayName("fails with an I/O error when nothing listens")
        void refused() throws IOException {
            int port;
            try (var socket = new java.net.ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            var failure = assertThrows(MediaException.Io.class,
                    () -> MediaSource.of(URI.create("http://127.0.0.1:" + port + "/x"), FAST.toBuilder().retries(1)
                            .build()));
            assertInstanceOf(IOException.class, failure.getCause());
        }
    }

    @Nested
    @DisplayName("headers")
    class Headers {
        @Test
        @DisplayName("parses Content-Range values and rejects malformed ones")
        void contentRange() {
            assertEquals(new HttpMediaSource.ContentRange(0, 99, 1000), HttpMediaSource.ContentRange.parse(
                    "bytes 0-99/1000"));
            assertEquals(new HttpMediaSource.ContentRange(5, 5, -1), HttpMediaSource.ContentRange.parse(
                    " BYTES 5-5/* "));
            assertEquals(new HttpMediaSource.ContentRange(-1, -1, 42), HttpMediaSource.ContentRange.parse(
                    "bytes */42"));
            for (var invalid : new String[]{null, "", "bytes", "bytes ", "bytes 0-99", "bytes 0-99/", "bytes 9-1/20",
                    "bytes 0-20/20", "bytes -1-5/20", "bytes 0-5/x", "items 0-5/20", "bytes0-5/20", "bytes */*",
                    "bytes 0-99999999999999999999/1", "bytes +1-5/20", "bytes 1--5/20", "bytes 1-5/20/30"}) {
                assertNull(HttpMediaSource.ContentRange.parse(invalid), invalid);
            }
        }

        @Test
        @DisplayName("parses Retry-After as seconds or a date")
        void retryAfter() {
            assertEquals(Duration.ofSeconds(7), HttpMediaSource.retryAfter(headers("7")));
            assertNull(HttpMediaSource.retryAfter(headers("soon")));
            assertNull(HttpMediaSource.retryAfter(HttpHeaders.of(Map.of(), (name, value) -> true)));
            assertEquals(Duration.ZERO, HttpMediaSource.retryAfter(headers("Sun, 06 Nov 1994 08:49:37 GMT")));
            var later = HttpMediaSource.retryAfter(headers(DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(120))));
            assertTrue(later.toSeconds() > 100 && later.toSeconds() <= 120, later.toString());
        }

        @Test
        @DisplayName("chooses the If-Range validator RFC 9110 allows")
        void ifRangeValidator() {
            var date = "Sun, 06 Nov 1994 08:49:37 GMT";
            assertEquals("\"a\"", HttpMediaSource.ifRange("\"a\"", "Sun, 06 Nov 1994 08:00:00 GMT", date));
            assertNull(HttpMediaSource.ifRange("W/\"a\"", "Sun, 06 Nov 1994 08:00:00 GMT", date));
            assertNull(HttpMediaSource.ifRange("\"a\"û", "Sun, 06 Nov 1994 08:00:00 GMT", date));
            assertNull(HttpMediaSource.ifRange("\"a\u0001\"", null, null));
            assertNull(HttpMediaSource.ifRange("\"a\"b\"", null, null));
            assertNull(HttpMediaSource.ifRange("a", null, null));
            assertEquals("Sun, 06 Nov 1994 08:49:36 GMT", HttpMediaSource.ifRange(null,
                    " Sun, 06 Nov 1994 08:49:36 GMT\t", date));
            assertEquals("Sun, 06 Nov 1994 08:49:36 GMT", HttpMediaSource.ifRange(null,
                    "Sun, 06 Nov 1994 08:49:36 GMT", date));
            assertNull(HttpMediaSource.ifRange(null, date, date));
            assertNull(HttpMediaSource.ifRange(null, "Sun, 06 Nov 1994 08:00:00 GMT", null));
            assertNull(HttpMediaSource.ifRange(null, "yesterday", date));
            assertNull(HttpMediaSource.ifRange(null, null, date));
        }

        private static HttpHeaders headers(String retryAfter) {
            return HttpHeaders.of(Map.of("Retry-After", List.of(retryAfter)), (name, value) -> true);
        }

        @Test
        @DisplayName("treats exactly 408, 425, 429, 500, 502, 503 and 504 as transient")
        void transientStatuses() {
            for (var status = 100; status < 600; status++) {
                assertEquals(List.of(408, 425, 429, 500, 502, 503, 504).contains(status),
                        HttpMediaSource.isTransient(status), "status " + status);
            }
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("hold their defaults, round-trip through toBuilder and validate in build")
        void builder() {
            var defaults = HttpSourceOptions.defaults();
            assertEquals(HttpSourceOptions.DEFAULT_BLOCK_SIZE, defaults.blockSize());
            assertEquals(HttpSourceOptions.DEFAULT_READ_AHEAD, defaults.readAhead());
            assertEquals(HttpSourceOptions.DEFAULT_CACHE_SIZE, defaults.cacheSize());
            assertEquals(HttpSourceOptions.DEFAULT_RETRIES, defaults.retries());
            assertEquals(HttpSourceOptions.DEFAULT_RETRY_DELAY, defaults.retryDelay());
            assertEquals(HttpSourceOptions.DEFAULT_REQUEST_TIMEOUT, defaults.requestTimeout());
            assertEquals(StreamSourceOptions.defaults(), defaults.streamOptions());
            assertTrue(defaults.httpClient().isEmpty());
            assertEquals(Map.of(), defaults.headers());
            assertEquals(defaults, HttpSourceOptions.builder().build());
            var custom = defaults.toBuilder().blockSize(8192).readAhead(0).cacheSize(8192).retries(0)
                    .retryDelay(Duration.ZERO).requestTimeout(Duration.ofSeconds(1)).header("X-A", "1")
                    .header("x-a", "2").header("X-B", "3").build();
            assertEquals(Map.of("X-A", List.of("1", "2"), "X-B", List.of("3")), custom.headers());
            assertEquals(List.of("X-A", "X-B"), List.copyOf(custom.headers().keySet()));
            assertEquals(custom, custom.toBuilder().build());
            assertEquals(custom.hashCode(), custom.toBuilder().build().hashCode());
            assertNotEquals(defaults, custom);
            assertEquals(Map.of(), custom.toBuilder().noHeaders().build().headers());
            assertThrows(UnsupportedOperationException.class, () -> custom.headers().put("X-C", List.of()));
            try (var client = HttpClient.newHttpClient()) {
                var withClient = defaults.toBuilder().httpClient(client).build();
                assertEquals(client, withClient.httpClient().orElseThrow());
                assertEquals(defaults, withClient.toBuilder().automaticHttpClient().build());
            }
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().blockSize(4095).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().blockSize((16 << 20) + 1).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().readAhead(-1).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().cacheSize(65535).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().retries(-1).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().retryDelay(Duration.ofMillis(-1))
                    .build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().requestTimeout(Duration.ZERO)
                    .build());
            for (var reserved : new String[]{"Range", "if-range", "Accept-Encoding", "Host", "Connection",
                    "bad name", "Content-Length"}) {
                assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().header(reserved, "x"),
                        reserved);
            }
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().httpClient(null));
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().streamOptions(null));
        }
    }

    @Nested
    @DisplayName("data references over HTTP")
    class Resolver {
        @TempDir
        Path directory;

        private void serve(String prefix, Path root) throws IOException {
            try (var files = Files.walk(root)) {
                for (var file : files.filter(Files::isRegularFile).toList()) {
                    var relative = root.relativize(file).toString().replace('\\', '/');
                    server.put(prefix + relative, Files.readAllBytes(file));
                }
            }
        }

        @Test
        @DisplayName("reads an HLS presentation over HTTP as from disk")
        void hls() throws IOException {
            var input = StreamSourceDemuxTest.video("mp4", 6.0, List.of("-c:v", "libx264", "-preset", "ultrafast",
                    "-g", "25", "-c:a", "aac"));
            var result = Packager.pack(input, directory, PackagingOptions.hls());
            var master = result.masterPlaylist().orElseThrow();
            serve("/hls/", directory);
            List<String> expected;
            try (var demuxer = HlsDemuxer.open(directory.resolve(master))) {
                expected = packets(demuxer);
            }
            var base = server.uri("/hls/" + master);
            var options = DemuxerOptions.builder().dataReferences(DataReferenceResolver.http(base, FAST)).build();
            try (var playlist = MediaSource.of(base, FAST); var demuxer = new HlsDemuxer(playlist, options)) {
                assertEquals(expected, packets(demuxer));
            }
        }

        @Test
        @DisplayName("reads a DASH presentation over HTTP as from disk")
        void dash() throws IOException {
            var input = StreamSourceDemuxTest.video("mp4", 6.0, List.of("-c:v", "libx264", "-preset", "ultrafast",
                    "-g", "25", "-c:a", "aac"));
            var result = Packager.pack(input, directory, PackagingOptions.dash());
            var manifest = result.manifest().orElseThrow();
            serve("/dash/", directory);
            List<String> expected;
            try (var demuxer = DashDemuxer.open(directory.resolve(manifest))) {
                expected = packets(demuxer);
            }
            var base = server.uri("/dash/" + manifest);
            var options = DemuxerOptions.builder().dataReferences(DataReferenceResolver.http(base, FAST)).build();
            try (var playlist = MediaSource.of(base, FAST); var demuxer = new DashDemuxer(playlist, options)) {
                assertEquals(expected, packets(demuxer));
            }
        }

        private static List<String> packets(com.github.auties00.smalt.container.Demuxer demuxer) {
            var lines = new ArrayList<String>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                lines.add(packet.trackId() + " " + packet.pts() + " " + packet.dts() + " " + packet.flags() + " "
                        + Arrays.hashCode(packet.data()));
            }
            return lines;
        }

        @Test
        @DisplayName("resolves relative and same-origin locations only, unless a filter accepts the URI")
        void origin() {
            var data = random(5000, 16);
            server.put("/media/a.bin", data);
            server.put("/b.bin", data);
            var resolver = DataReferenceResolver.http(server.uri("/media/index.m3u8"), FAST);
            try (var source = resolver.resolve(new DataReference(DataReference.Kind.URL, "a.bin#frag", ""))
                    .orElseThrow()) {
                assertEquals(data.length, source.size());
            }
            try (var source = resolver.resolve(new DataReference(DataReference.Kind.URN, "../b.bin", "name"))
                    .orElseThrow()) {
                assertEquals(data.length, source.size());
            }
            server.reset();
            var foreign = new DataReference(DataReference.Kind.URL, server.loopbackUri("/b.bin").toString(), "");
            assertTrue(resolver.resolve(foreign).isEmpty(), "another host is another origin");
            assertTrue(resolver.resolve(new DataReference(DataReference.Kind.URL, "file:///etc/passwd", "")).isEmpty());
            assertTrue(resolver.resolve(new DataReference(DataReference.Kind.URL, "https://localhost:"
                    + server.uri("/").getPort() + "/b.bin", "")).isEmpty(), "another scheme is another origin");
            assertTrue(resolver.resolve(new DataReference(DataReference.Kind.URL, "", "")).isEmpty());
            assertTrue(resolver.resolve(new DataReference(DataReference.Kind.URL, "a b%zz", "")).isEmpty());
            assertTrue(resolver.resolve(new DataReference(DataReference.Kind.ALIAS, "a.bin", "a.bin")).isEmpty());
            assertTrue(resolver.resolve(new DataReference(DataReference.Kind.MATROSKA_SEGMENT,
                    "00112233445566778899aabbccddeeff", "a.bin")).isEmpty());
            assertEquals(0, server.requests());
            var filtered = DataReferenceResolver.http(server.uri("/media/index.m3u8"), FAST,
                    uri -> uri.getHost().equals("127.0.0.1"));
            try (var source = filtered.resolve(foreign).orElseThrow()) {
                assertEquals(data.length, source.size());
            }
            assertTrue(filtered.resolve(new DataReference(DataReference.Kind.URL, "a.bin", "")).isEmpty(),
                    "the filter replaces the origin rule");
            assertThrows(MediaException.Io.class, () -> resolver.resolve(new DataReference(DataReference.Kind.URL,
                    "missing.bin", "")));
            var noPath = DataReferenceResolver.http(URI.create("http://localhost:" + server.uri("/").getPort()), FAST);
            try (var source = noPath.resolve(new DataReference(DataReference.Kind.URL, "b.bin", "")).orElseThrow()) {
                assertEquals(data.length, source.size());
            }
            assertEquals(resolver, DataReferenceResolver.http(server.uri("/media/index.m3u8"), FAST));
            assertNotEquals(resolver, DataReferenceResolver.http(server.uri("/other/index.m3u8"), FAST));
            assertThrows(IllegalArgumentException.class, () -> DataReferenceResolver.http(URI.create("a/b"), FAST));
            assertThrows(IllegalArgumentException.class,
                    () -> DataReferenceResolver.http(URI.create("ftp://host/a"), FAST));
        }
    }
}
