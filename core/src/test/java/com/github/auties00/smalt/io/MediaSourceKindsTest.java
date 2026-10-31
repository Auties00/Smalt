package com.github.auties00.smalt.io;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ArrayBacked;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.LocalSource;
import com.github.auties00.smalt.internal.io.ReadAheadCache;
import com.github.auties00.smalt.internal.io.SegmentBacked;
import com.github.auties00.smalt.internal.io.SourceReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The byte-buffer, memory-segment and input-stream sources: their reads against the same bytes in
 * an array, the in-memory markers the parsers rely on, and the retained store of the stream
 * sources (spilling, its protection and encryption, the watermark, memory-only mode, ownership of
 * the stream, truncation and concurrency).
 */
@DisplayName("MediaSource kinds")
class MediaSourceKindsTest {
    static byte[] random(long seed, int length) {
        var data = new byte[length];
        var random = new SplittableRandom(seed);
        for (var i = 0; i < length; i++) {
            data[i] = (byte) random.nextInt(256);
        }
        return data;
    }

    /** A stream that delivers at most {@code chunk} bytes per read and counts reads, bytes and closes. */
    static final class CountingStream extends FilterInputStream {
        private final int chunk;
        final AtomicInteger closes = new AtomicInteger();
        long delivered;
        int reads;
        IOException failAfter;
        long failPosition = Long.MAX_VALUE;

        CountingStream(byte[] data, int chunk) {
            super(new ByteArrayInputStream(data));
            this.chunk = chunk;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (delivered >= failPosition) {
                throw failAfter;
            }
            reads++;
            var read = super.read(buffer, offset, Math.min(length, chunk));
            if (read > 0) {
                delivered += read;
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            closes.incrementAndGet();
            super.close();
        }
    }

    static StreamSourceOptions budget(long bytes, Path directory) {
        return StreamSourceOptions.builder().memoryBudget(bytes).spillDirectory(directory).build();
    }

    static Stream<Arguments> streams() {
        return Stream.of(
                Arguments.of("in memory", 16L << 20, 65_536, true),
                Arguments.of("spilling", 16L << 10, 65_536, true),
                Arguments.of("spilling, short reads", 16L << 10, 777, true),
                Arguments.of("unknown length, spilling", 16L << 10, 4096, false));
    }

    @Nested
    @DisplayName("over an input stream")
    class Streams {
        @TempDir
        Path directory;

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.io.MediaSourceKindsTest#streams")
        @DisplayName("answers random reads exactly as the array does, reading the stream once")
        void randomReads(String name, long budget, int chunk, boolean known) {
            var data = random(1, 300_000);
            var stream = new CountingStream(data, chunk);
            try (var source = MediaSource.of(stream, known ? data.length : -1, budget(budget, directory))) {
                assertEquals(known ? data.length : -1, source.size());
                var reference = MediaSource.of(data);
                var random = new SplittableRandom(7);
                for (var step = 0; step < 3000; step++) {
                    var position = random.nextLong(data.length + 100);
                    var length = random.nextInt(20_000);
                    var expected = new byte[length];
                    var actual = new byte[length];
                    var expectedCount = reference.read(position, expected, 0, length);
                    var actualCount = source.read(position, actual, 0, length);
                    if (expectedCount < 0) {
                        assertEquals(-1, actualCount);
                    } else {
                        assertTrue(actualCount > 0 && actualCount <= expectedCount, "short read " + actualCount);
                        assertArrayEquals(Arrays.copyOf(expected, actualCount), Arrays.copyOf(actual, actualCount));
                    }
                }
                assertEquals(data.length, stream.delivered);
                assertEquals(0, stream.closes.get());
            }
            assertEquals(0, stream.closes.get(), "the caller's stream is never closed");
            assertEmpty(directory);
        }

        @Test
        @DisplayName("leaves the caller's stream open and closes the supplier's stream once, opening it lazily")
        void ownership() {
            var data = random(2, 10_000);
            var given = new CountingStream(data, 1000);
            var source = MediaSource.of(given, data.length);
            source.readFully(0, new byte[100], 0, 100);
            source.close();
            source.close();
            assertEquals(0, given.closes.get());

            var opened = new AtomicInteger();
            var supplied = new ArrayList<CountingStream>();
            var lazy = MediaSource.of(() -> {
                opened.incrementAndGet();
                var stream = new CountingStream(data, 1000);
                supplied.add(stream);
                return stream;
            }, data.length);
            assertEquals(0, opened.get(), "the supplier is not called before the first read");
            var bytes = new byte[data.length];
            lazy.readFully(0, bytes, 0, bytes.length);
            lazy.readFully(10, bytes, 10, 100);
            assertArrayEquals(data, bytes);
            lazy.close();
            lazy.close();
            assertEquals(1, opened.get());
            assertEquals(1, supplied.getFirst().closes.get());
        }

        @Test
        @DisplayName("maps a supplier failure to MediaException.Io")
        void supplierFailure() {
            try (var source = MediaSource.of(() -> {
                throw new UncheckedIOException(new IOException("no network"));
            }, 10)) {
                var failure = assertThrows(MediaException.Io.class, () -> source.read(0, new byte[4], 0, 4));
                assertEquals("no network", failure.getCause().getMessage());
            }
        }

        @Test
        @DisplayName("reports a stream shorter than its declared length as MediaException.Io caused by EOFException")
        void truncated() {
            var data = random(3, 5000);
            try (var source = MediaSource.of(new CountingStream(data, 512), 8000)) {
                var head = new byte[5000];
                source.readFully(0, head, 0, head.length);
                assertArrayEquals(data, head);
                var failure = assertThrows(MediaException.Io.class, () -> source.readFully(4000, new byte[2000], 0, 2000));
                assertInstanceOf(EOFException.class, failure.getCause());
                assertTrue(failure.getMessage().contains("5000") && failure.getMessage().contains("8000"), failure.getMessage());
                assertThrows(MediaException.Io.class, () -> source.read(6000, new byte[10], 0, 10));
                assertEquals(10, source.read(10, new byte[10], 0, 10), "kept bytes stay readable");
            }
        }

        @Test
        @DisplayName("never reads past the declared length")
        void declaredLength() {
            var data = random(4, 10_000);
            var stream = new CountingStream(data, 4096);
            try (var source = MediaSource.of(stream, 6000)) {
                assertEquals(-1, source.read(6000, new byte[10], 0, 10));
                assertEquals(100, source.read(5900, new byte[200], 0, 200));
                assertEquals(6000, stream.delivered);
            }
        }

        @Test
        @DisplayName("ends a stream of unknown length where the stream ends")
        void unknownLength() {
            var data = random(5, 7000);
            try (var source = MediaSource.of(new CountingStream(data, 1000), -1)) {
                assertEquals(-1, source.size());
                var all = new byte[8000];
                var read = ForwardOnlySource.readOnce(source, 0, all, 0, all.length);
                assertEquals(7000, read);
                assertArrayEquals(data, Arrays.copyOf(all, read));
                assertEquals(-1, source.read(7000, new byte[1], 0, 1));
            }
        }

        @Test
        @DisplayName("maps a failing stream to MediaException.Io and keeps what it read")
        void failingStream() {
            var data = random(6, 50_000);
            var stream = new CountingStream(data, 1000);
            stream.failAfter = new IOException("connection reset");
            stream.failPosition = 30_000;
            try (var source = MediaSource.of(stream, data.length, budget(8 << 10, directory))) {
                var failure = assertThrows(MediaException.Io.class, () -> source.readFully(0, new byte[40_000], 0, 40_000));
                assertEquals("connection reset", failure.getCause().getMessage());
                var head = new byte[30_000];
                source.readFully(0, head, 0, head.length);
                assertArrayEquals(Arrays.copyOf(data, 30_000), head);
            }
            assertEmpty(directory);
        }

        @Test
        @DisplayName("serves concurrent reads of kept bytes and of bytes past the frontier")
        void concurrentReads() throws InterruptedException {
            var data = random(8, 400_000);
            try (var source = MediaSource.of(new CountingStream(data, 3000), data.length, budget(64 << 10, directory))) {
                var threads = new ArrayList<Thread>();
                var failures = new ArrayList<Throwable>();
                var start = new CountDownLatch(1);
                for (var t = 0; t < 8; t++) {
                    var seed = t;
                    threads.add(Thread.ofPlatform().start(() -> {
                        try {
                            start.await();
                            var random = new SplittableRandom(seed);
                            for (var step = 0; step < 400; step++) {
                                var position = random.nextLong(data.length);
                                var length = (int) Math.min(random.nextInt(9000), data.length - position);
                                var bytes = new byte[length];
                                source.readFully(position, bytes, 0, length);
                                if (!Arrays.equals(data, (int) position, (int) position + length, bytes, 0, length)) {
                                    throw new AssertionError("mismatch at " + position);
                                }
                            }
                        } catch (Throwable failure) {
                            synchronized (failures) {
                                failures.add(failure);
                            }
                        }
                    }));
                }
                start.countDown();
                for (var thread : threads) {
                    thread.join();
                }
                assertTrue(failures.isEmpty(), () -> failures.toString());
            }
            assertEmpty(directory);
        }

        @Test
        @DisplayName("reports as kept exactly the taken ranges that a read below the watermark can still serve")
        void keeps() {
            var data = random(11, 120_000);
            try (var source = MediaSource.of(new CountingStream(data, 5000), data.length,
                    StreamSourceOptions.memoryOnly().toBuilder().memoryBudget(64 << 10).build())) {
                var bytes = new byte[1000];
                for (var position = 0; position < 100_000; position += 1000) {
                    source.readFully(position, bytes, 0, 1000);
                    ForwardOnlySource.release(source, Math.min(position, 50_000));
                }
                assertTrue(ForwardOnlySource.keeps(source, 7, 7));
                assertTrue(ForwardOnlySource.keeps(source, 60_000, 100_000));
                assertFalse(ForwardOnlySource.keeps(source, 0, 1000));
                assertFalse(ForwardOnlySource.keeps(source, 99_000, data.length + 1));
                // the chunk holding the watermark is kept although part of it lies below it
                assertTrue(ForwardOnlySource.keeps(source, 49_999, 50_000));
                for (var position = 0; position < 50_000; position += 500) {
                    var from = position;
                    if (ForwardOnlySource.keeps(source, from, from + 500)) {
                        source.readFully(from, bytes, 0, 500);
                        assertTrue(Arrays.equals(data, from, from + 500, bytes, 0, 500));
                    } else {
                        assertThrows(MediaException.Unsupported.class, () -> source.readFully(from, bytes, 0, 500));
                    }
                }
            }
            assertTrue(ForwardOnlySource.keeps(MediaSource.of(new byte[10]), 0, 10));
        }

        @Test
        @DisplayName("drops released bytes, fails a read below the watermark and reports kept ranges as local")
        void watermark() {
            var data = random(9, 200_000);
            try (var source = MediaSource.of(new CountingStream(data, 5000), data.length,
                    StreamSourceOptions.memoryOnly().toBuilder().memoryBudget(32 << 10).build())) {
                var local = (LocalSource) source;
                assertFalse(local.isLocal());
                var bytes = new byte[1000];
                for (var position = 0; position + 1000 <= data.length; position += 1000) {
                    source.readFully(position, bytes, 0, 1000);
                    assertTrue(Arrays.equals(data, position, position + 1000, bytes, 0, 1000));
                    assertTrue(local.isLocal(position, position + 1000));
                    ForwardOnlySource.release(source, position + 1000);
                }
                assertFalse(local.isLocal(0, 10));
                assertThrows(MediaException.Unsupported.class, () -> source.read(0, new byte[10], 0, 10));
            }
        }

        @Test
        @DisplayName("reads a whole stream once through a memory-only budget far below its length")
        void readOnce() {
            var data = random(10, 1_000_000);
            try (var source = MediaSource.of(new CountingStream(data, 8192), data.length,
                    StreamSourceOptions.memoryOnly().toBuilder().memoryBudget(16 << 10).build())) {
                var head = new byte[100];
                source.readFully(0, head, 0, head.length);
                var all = new byte[data.length];
                assertEquals(data.length, ForwardOnlySource.readOnce(source, 0, all, 0, all.length));
                assertArrayEquals(data, all);
            }
        }

        @Test
        @DisplayName("fails a memory-only source at its budget with MediaException.LimitExceeded")
        void memoryOnly() {
            var data = random(11, 200_000);
            try (var source = MediaSource.of(new CountingStream(data, 8192), data.length,
                    StreamSourceOptions.memoryOnly().toBuilder().memoryBudget(64 << 10).build())) {
                source.readFully(0, new byte[60_000], 0, 60_000);
                var failure = assertThrows(MediaException.LimitExceeded.class,
                        () -> source.readFully(0, new byte[150_000], 0, 150_000));
                assertTrue(failure.getMessage().contains("memory budget"), failure.getMessage());
            }
            assertEmpty(directory);
        }
    }

    @Nested
    @DisplayName("spill file")
    class Spill {
        @TempDir
        Path directory;

        @Test
        @DisplayName("is created with owner-only permissions, holds no plaintext and is gone after close")
        void protectedAndEncrypted() throws IOException {
            var data = random(12, 600_000);
            var source = MediaSource.of(new CountingStream(data, 65_536), data.length, budget(16 << 10, directory));
            source.readFully(data.length - 10, new byte[10], 0, 10);
            var files = list(directory);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                assertEquals(List.of(), files, "the spill file is unlinked as soon as it is opened");
            } else {
                assertEquals(1, files.size());
                var file = files.getFirst();
                assertTrue(file.getFileName().toString().matches("smalt-[0-9a-f]{32}\\.spill"), file.toString());
                var acl = Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl();
                assertEquals(1, acl.size(), acl.toString());
                assertEquals(AclEntryType.ALLOW, acl.getFirst().type());
                assertEquals(Files.getOwner(file), acl.getFirst().principal());
                assertNoPlaintext(Files.readAllBytes(file), data);
            }
            var check = new byte[data.length];
            source.readFully(0, check, 0, check.length);
            assertArrayEquals(data, check);
            source.close();
            assertEmpty(directory);
        }

        @Test
        @DisplayName("is created rw------- on POSIX and encrypts every chunk it holds")
        void posixAndCipher() throws IOException {
            var spill = SpillFile.create(directory, 4096, false);
            try {
                var plain = random(13, 4096);
                spill.write(3, 41, plain.clone(), plain.length);
                spill.write(0, 42, plain.clone(), plain.length);
                if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(spill.path())));
                }
                var back = new byte[1000];
                spill.read(3, 41, 123, back, 0, back.length);
                assertTrue(Arrays.equals(plain, 123, 1123, back, 0, 1000));
                spill.read(0, 42, 4000, back, 0, 96);
                assertTrue(Arrays.equals(plain, 4000, 4096, back, 0, 96));
                var bytes = Files.readAllBytes(spill.path());
                assertNoPlaintext(bytes, plain);
                assertFalse(Arrays.equals(bytes, 0, 4096, bytes, 3 * 4096, 4 * 4096),
                        "the same chunk at two stream positions encrypts differently");
            } finally {
                spill.close();
            }
            assertEmpty(directory);
        }

        @Test
        @DisplayName("is gone after the stream fails mid-spill")
        void goneAfterFailure() {
            var data = random(14, 400_000);
            var stream = new CountingStream(data, 65_536);
            stream.failAfter = new IOException("broken pipe");
            stream.failPosition = 300_000;
            var source = MediaSource.of(stream, data.length, budget(16 << 10, directory));
            assertThrows(MediaException.Io.class, () -> source.readFully(0, new byte[data.length], 0, data.length));
            source.close();
            assertEmpty(directory);
        }

        @Test
        @DisplayName("reports a spill directory that cannot hold a file as MediaException.Io")
        void unusableDirectory() {
            var data = random(15, 200_000);
            var missing = directory.resolve("missing");
            try (var source = MediaSource.of(new CountingStream(data, 65_536), data.length, budget(16 << 10, missing))) {
                assertThrows(MediaException.Io.class, () -> source.readFully(0, new byte[data.length], 0, data.length));
            }
            assertEmpty(directory);
        }
    }

    @Nested
    @DisplayName("over a buffer or a segment")
    class Memory {
        @Test
        @DisplayName("reads a heap buffer in place over its array, from position to limit, leaving the buffer unchanged")
        void heapBuffer() {
            var data = random(20, 1000);
            var buffer = ByteBuffer.wrap(data).slice(100, 500).position(10).limit(310);
            var source = MediaSource.of(buffer);
            assertInstanceOf(ArrayBacked.class, source);
            assertEquals(300, source.size());
            var bytes = new byte[300];
            source.readFully(0, bytes, 0, 300);
            assertTrue(Arrays.equals(data, 110, 410, bytes, 0, 300));
            assertEquals(10, buffer.position());
            assertEquals(310, buffer.limit());
        }

        @Test
        @DisplayName("reads direct and read-only buffers through their segments")
        void directBuffers() {
            var data = random(21, 1000);
            var direct = ByteBuffer.allocateDirect(1000).put(data).position(200).limit(900);
            var readOnly = ByteBuffer.wrap(data).asReadOnlyBuffer().position(200).limit(900);
            for (var buffer : List.of(direct, readOnly)) {
                var source = MediaSource.of(buffer);
                assertInstanceOf(SegmentBacked.class, source);
                assertEquals(700, source.size());
                var bytes = new byte[700];
                source.readFully(0, bytes, 0, 700);
                assertTrue(Arrays.equals(data, 200, 900, bytes, 0, 700));
                assertEquals(200, buffer.position());
                assertEquals(-1, source.read(700, bytes, 0, 1));
            }
        }

        @Test
        @DisplayName("reads a heap segment over a byte array in place and a native segment through bulk copies")
        void segments() {
            var data = random(22, 5000);
            var heap = MediaSource.of(MemorySegment.ofArray(data).asSlice(1000, 2000));
            assertInstanceOf(ArrayBacked.class, heap);
            assertEquals(1000, ((ArrayBacked) heap).arrayOffset());
            try (var arena = Arena.ofShared()) {
                var segment = arena.allocate(data.length);
                MemorySegment.copy(data, 0, segment, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, data.length);
                var native_ = MediaSource.of(segment.asSlice(1000, 2000));
                assertInstanceOf(SegmentBacked.class, native_);
                assertTrue(LocalSource.isLocal(native_));
                for (var source : List.of(heap, native_)) {
                    var bytes = new byte[2000];
                    source.readFully(0, bytes, 0, 2000);
                    assertTrue(Arrays.equals(data, 1000, 3000, bytes, 0, 2000));
                }
            }
        }

        @Test
        @DisplayName("rejects a confined segment with the arenas to use instead, and a closed one")
        void confined() {
            try (var arena = Arena.ofConfined()) {
                var segment = arena.allocate(100);
                var failure = assertThrows(IllegalArgumentException.class, () -> MediaSource.of(segment));
                assertTrue(failure.getMessage().contains("Arena.ofShared()"), failure.getMessage());
                assertThrows(IllegalArgumentException.class, () -> MediaSource.of(segment.asByteBuffer()));
            }
            var arena = Arena.ofShared();
            var segment = arena.allocate(100);
            arena.close();
            assertThrows(IllegalStateException.class, () -> MediaSource.of(segment));
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(longs = {1, 2, 3, 4, 5})
        @DisplayName("reads through SourceReader and ReadAheadCache exactly as the array does")
        void parsers(long seed) {
            var data = random(seed, 200_000);
            try (var arena = Arena.ofShared()) {
                var segment = arena.allocate(data.length);
                MemorySegment.copy(data, 0, segment, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, data.length);
                var expected = replay(MediaSource.of(data), seed);
                assertEquals(expected, replay(MediaSource.of(segment), seed));
                assertEquals(expected, replay(MediaSource.of(new ByteArrayInputStream(data), data.length), seed));
            }
        }

        static List<String> replay(MediaSource source, long seed) {
            var random = new SplittableRandom(seed);
            var reader = new SourceReader(source);
            var cache = new ReadAheadCache(source, 8192, 1024);
            var log = new ArrayList<String>();
            for (var step = 0; step < 2000; step++) {
                try {
                    switch (random.nextInt(6)) {
                        case 0 -> log.add("u32 " + reader.u32be());
                        case 1 -> log.add("u8 " + reader.u8());
                        case 2 -> {
                            var bytes = new byte[random.nextInt(30_000)];
                            reader.readFully(bytes, 0, bytes.length);
                            log.add("bytes " + Arrays.hashCode(bytes));
                        }
                        case 3 -> {
                            reader.seek(random.nextLong(210_000));
                            log.add("seek " + reader.position());
                        }
                        case 4 -> {
                            var bytes = new byte[random.nextInt(3000)];
                            var read = cache.readUpTo(random.nextLong(205_000), bytes, 0, bytes.length);
                            log.add("cache " + read + " " + Arrays.hashCode(bytes));
                        }
                        default -> log.add("pos " + reader.position() + " end " + reader.atEnd());
                    }
                } catch (MediaException.Malformed failure) {
                    log.add("malformed");
                    reader.seek(0);
                }
            }
            return log;
        }
    }

    static List<Path> list(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.toList();
        }
    }

    static void assertEmpty(Path directory) {
        try {
            assertEquals(List.of(), list(directory), "no spill file is left behind");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Asserts that no 32-byte run of the plaintext, sampled every 997 bytes, occurs in a file. */
    static void assertNoPlaintext(byte[] file, byte[] plain) {
        var text = new String(file, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (var at = 0; at + 32 <= plain.length; at += 997) {
            var run = new String(plain, at, 32, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertEquals(-1, text.indexOf(run), "plaintext at " + at + " found in the spill file");
        }
    }
}
