package com.github.auties00.smalt.property;

import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Output does not depend on the thread count. Every video encoder and decoder with a thread option produces the same
 * packets and frames for 1, 2, 3 and 8 threads; every codec and picture operation produces the same output while
 * other operations keep the shared helper threads busy (which changes how much speculative and pipelined work runs);
 * and a JVM with one shared thread prints the same digests ({@link ThreadDigest}) as one with eight.
 */
@DisplayName("Thread-count invariance")
class ThreadCountInvarianceTest {
    private static final int[] THREADS = {1, 2, 3, 8};

    /** The digests of this JVM without concurrent load, computed once. */
    private static Map<String, String> quiet;

    private static synchronized Map<String, String> quiet() {
        if (quiet == null) {
            quiet = ThreadDigest.digests();
        }
        return quiet;
    }

    static Stream<Codec> encoders() {
        return Stream.of(Codec.H264, Codec.HEVC, Codec.VP8, Codec.VP9, Codec.AV1, Codec.MJPEG, Codec.JPEG2000,
                Codec.PRORES, Codec.DNXHD, Codec.FFV1);
    }

    static Stream<Codec> decoders() {
        return Stream.of(Codec.H264, Codec.VP8, Codec.VP9, Codec.AV1, Codec.MJPEG, Codec.JPEG2000, Codec.PRORES,
                Codec.DNXHD, Codec.HEVC, Codec.MPEG4_PART2, Codec.MPEG2_VIDEO, Codec.FFV1);
    }

    @Nested
    @DisplayName("a video encoder")
    class Encoders {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.property.ThreadCountInvarianceTest#encoders")
        @DisplayName("writes the same packets for every thread count, in real-time and file modes")
        void packets(Codec codec) {
            for (var realtime : new boolean[]{true, false}) {
                String reference = null;
                for (var threads : THREADS) {
                    var stream = Streams.video(codec, 640, 360, 10, builder -> builder.realtime(realtime).threads(threads));
                    var digest = ThreadDigest.sha256Packets(stream);
                    if (reference == null) {
                        reference = digest;
                    }
                    assertEquals(reference, digest, codec + (realtime ? " real-time" : " file") + " with " + threads
                            + " threads");
                }
            }
        }
    }

    @Nested
    @DisplayName("a video decoder")
    class Decoders {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.property.ThreadCountInvarianceTest#decoders")
        @DisplayName("returns the same frames for every thread count")
        void frames(Codec codec) {
            var stream = codec == Codec.HEVC || codec == Codec.MPEG4_PART2 || codec == Codec.MPEG2_VIDEO
                    ? Catalog.stream(codec)
                    : Streams.video(codec, 640, 360, 10, builder -> builder.realtime(codec != Codec.H264));
            String reference = null;
            for (var threads : THREADS) {
                var digest = decode(stream, VideoDecoderOptions.builder().threads(threads).build());
                if (reference == null) {
                    reference = digest;
                }
                assertEquals(reference, digest, codec + " with " + threads + " threads");
            }
        }
    }

    @Nested
    @DisplayName("every shared-pool workload")
    class Load {
        @Test
        @DisplayName("gives the same output while three other operations keep the helper threads busy")
        void busy() throws Exception {
            var quiet = quiet();
            ExecutorService load = Executors.newFixedThreadPool(3);
            var stop = new java.util.concurrent.atomic.AtomicBoolean();
            var running = new ArrayList<Future<?>>();
            try {
                for (var index = 0; index < 3; index++) {
                    running.add(load.submit(() -> {
                        var picture = ThreadDigest.picture(1280, 720);
                        while (!stop.get()) {
                            ImageOps.resize(picture, 911, 517, com.github.auties00.smalt.image.ops.ResizeFilter.LANCZOS3);
                        }
                    }));
                }
                var busy = ThreadDigest.digests();
                assertDigests("under load", quiet, busy);
            } finally {
                stop.set(true);
                load.shutdown();
                assertTrue(load.awaitTermination(1, TimeUnit.MINUTES), "load threads stopped");
                for (var future : running) {
                    future.get();
                }
            }
        }

        @Test
        @DisplayName("gives the same output in a JVM with one shared thread and in one with eight")
        void forkedJvms() throws Exception {
            var one = fork(1);
            var eight = fork(8);
            assertDigests("smalt.threads=1 against smalt.threads=8", one, eight);
            assertDigests("smalt.threads=1 against this JVM", quiet(), one);
        }
    }

    static void assertDigests(String what, Map<String, String> expected, Map<String, String> actual) {
        var differing = new ArrayList<String>();
        expected.forEach((name, digest) -> {
            if (!digest.equals(actual.get(name))) {
                differing.add(name);
            }
        });
        assertEquals(List.of(), differing, what + ": workloads whose output changed");
    }

    static String decode(com.github.auties00.smalt.property.Streams.Encoded stream, VideoDecoderOptions options) {
        var digest = new StringBuilder();
        try (var decoder = VideoDecoders.create((VideoTrack) stream.track(), options)) {
            java.util.function.Consumer<VideoFrame> sink = frame -> digest.append(frame.pts()).append(':')
                    .append(ThreadDigest.sha256(ImageOps.toRgba(frame.picture()).pixels())).append(' ');
            for (Packet packet : stream.packets()) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return digest.toString();
    }

    private static Map<String, String> fork(int threads) throws Exception {
        var output = Files.createTempFile(Path.of("target"), "thread-digest-" + threads + "-", ".txt");
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-modules", "jdk.incubator.vector", "-Xmx1g", "-Dsmalt.threads=" + threads,
                "-Dsmalt.simd=" + System.getProperty("smalt.simd", "true"),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ThreadDigest.class.getName());
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(Duration.ofMinutes(10).toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("the child JVM with " + threads + " threads did not finish");
        }
        var lines = Files.readAllLines(output);
        assertEquals(0, process.exitValue(), "child JVM with " + threads + " threads: " + lines);
        var digests = new LinkedHashMap<String, String>();
        for (var line : lines) {
            var fields = line.split(" ");
            if (fields.length == 2 && fields[1].length() == 64) {
                digests.put(fields[0], fields[1]);
            }
        }
        return digests;
    }
}
