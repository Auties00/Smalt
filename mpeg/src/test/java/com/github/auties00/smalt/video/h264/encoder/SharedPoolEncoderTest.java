package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the encoder on the module's shared pool: the stream does not depend on which threads
 * decide the rows, a picture completes on the calling thread when no helper runs its
 * participants or the module runs on one thread, and concurrent encoders keep the pool within its
 * bound. The pictures are 13x9 macroblocks, so four workers wrap the six-row ring.
 */
@DisplayName("encoder on the shared worker pool")
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class SharedPoolEncoderTest {
    private static final Rational FPS = new Rational(30, 1);
    private static final int WIDTH = 208;
    private static final int HEIGHT = 144;
    private static final String WORKER_PREFIX = "smalt-worker-";

    static H264Setup config(H264EncoderSpeed speed, int threads) {
        var profile = speed == H264EncoderSpeed.FASTEST ? H264Profile.CONSTRAINED_BASELINE : H264Profile.HIGH;
        return H264Setup.of(WIDTH, HEIGHT, FPS, FPS.invert(), profile, 0, H264RateControl.AVERAGE_BITRATE,
                250_000, 0, 0, 26, 30, speed, ColorInfo.BT709_LIMITED, threads);
    }

    static List<YuvPicture> pictures() {
        return EncoderHarness.sequence(WIDTH, HEIGHT, 8, 17, 2.0);
    }

    static byte[][] encode(H264Setup config, List<YuvPicture> pictures, int forcedHelpers) {
        var packets = new ArrayList<byte[]>();
        try (var encoder = config.encoder()) {
            encoder.core().setForcedHelpers(forcedHelpers);
            for (var i = 0; i < pictures.size(); i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i, 1, false), packet -> packets.add(packet.data()));
            }
        }
        return packets.toArray(byte[][]::new);
    }

    static byte[] hash(byte[][] packets) throws NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance("SHA-256");
        for (var packet : packets) {
            digest.update(packet);
        }
        return digest.digest();
    }

    private static void assertSameStream(byte[][] expected, byte[][] actual, String label) {
        assertEquals(expected.length, actual.length, label);
        for (var i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], actual[i], label + " packet " + i);
        }
    }

    private static int liveWorkers() {
        var count = 0;
        for (var thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith(WORKER_PREFIX) && thread.isAlive()) {
                count++;
            }
        }
        return count;
    }

    @ParameterizedTest(name = "{0}, {1} helpers")
    @CsvSource({"BALANCED, 1", "BALANCED, 2", "BALANCED, 4", "BALANCED, 9", "FASTEST, 1", "FASTEST, 3"})
    @DisplayName("the stream is the same whichever participants decide the rows")
    void participantsIndependence(H264EncoderSpeed speed, int helpers) {
        var pictures = pictures();
        var expected = encode(config(speed, 1), pictures, -1);
        assertSameStream(expected, encode(config(speed, 4), pictures, helpers), helpers + " helpers");
        assertSameStream(expected, encode(config(speed, 9), pictures, helpers), helpers + " helpers, 9 workers");
    }

    @ParameterizedTest(name = "helpers blocked in declared waits: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a picture completes on the calling thread while every helper is busy with another operation")
    void saturatedPool(boolean declared) throws Exception {
        var pictures = pictures();
        var expected = encode(config(H264EncoderSpeed.BALANCED, 1), pictures, -1);
        var release = new CountDownLatch(1);
        var entered = new AtomicInteger();
        var bands = ParallelBands.THREADS;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var blocker = executor.submit(() -> ParallelBands.run(bands, band -> {
                if (declared) {
                    ParallelBands.beginBlocking();
                }
                try {
                    entered.incrementAndGet();
                    assertTrue(release.await(120, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                } finally {
                    if (declared) {
                        ParallelBands.endBlocking();
                    }
                }
            }));
            try {
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (entered.get() < bands && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertEquals(bands, entered.get(), "every helper should be occupied");
                assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                    assertSameStream(expected, encode(config(H264EncoderSpeed.BALANCED, 4), pictures, -1), "pool sized");
                    assertSameStream(expected, encode(config(H264EncoderSpeed.BALANCED, 4), pictures, 4), "forced participants");
                    assertSameStream(expected, encode(config(H264EncoderSpeed.BALANCED, 8), pictures, 8), "forced, 8 workers");
                });
            } finally {
                release.countDown();
            }
            blocker.get();
        }
    }

    @Test
    @DisplayName("a lone caller with smalt.threads=1 gets the stream of one thread and starts no helper")
    void loneCaller() throws Exception {
        var expected = HexFormat.of().formatHex(hash(encode(config(H264EncoderSpeed.BALANCED, 1), pictures(), -1)));
        var classpath = System.getProperty("surefire.test.class.path", Path.of("target", "test-classes").toAbsolutePath() + File.pathSeparator
                + Path.of("target", "classes").toAbsolutePath());
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-modules", "jdk.incubator.vector", "-Xmx256m", "-XX:+UseSerialGC", "-Dsmalt.threads=1",
                "-cp", classpath, LoneCallerEncode.class.getName());
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the child did not finish: " + output);
        assertEquals(0, process.exitValue(), output);
        var result = output.lines().filter(line -> line.startsWith("THREADS=")).findFirst().orElse(output);
        assertEquals("THREADS=1 HASH=" + expected + " WORKERS=0", result);
    }

    @Test
    @DisplayName("concurrent encoders on virtual threads get the stream of a lone encoder, on a bounded pool")
    void concurrentEncoders() throws Exception {
        var pictures = pictures();
        var expected = encode(config(H264EncoderSpeed.BALANCED, 1), pictures, -1);
        var peak = new AtomicInteger();
        var stop = new AtomicBoolean();
        var ownThreads = new AtomicBoolean();
        var sampler = Thread.ofPlatform().daemon().start(() -> {
            while (!stop.get()) {
                var alive = 0;
                for (var thread : Thread.getAllStackTraces().keySet()) {
                    if (thread.getName().startsWith(WORKER_PREFIX)) {
                        alive++;
                    } else if (thread.getName().startsWith("h264-encoder-worker")) {
                        ownThreads.set(true);
                    }
                }
                peak.accumulateAndGet(alive, Math::max);
                try {
                    Thread.sleep(2);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        });
        var results = new ArrayList<byte[][]>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<byte[][]>>();
            for (var i = 0; i < 12; i++) {
                var threads = 2 + i % 4 * 2;
                futures.add(executor.submit((Callable<byte[][]>) () -> encode(config(H264EncoderSpeed.BALANCED, threads), pictures, -1)));
            }
            for (var future : futures) {
                results.add(future.get());
            }
        } finally {
            stop.set(true);
            sampler.join();
        }
        for (var i = 0; i < results.size(); i++) {
            assertSameStream(expected, results.get(i), "encoder " + i);
        }
        assertTrue(!ownThreads.get(), "an encoder started threads of its own");
        assertTrue(peak.get() <= 2 * ParallelBands.THREADS - 1, peak.get() + " helpers alive at once");
        for (var entry : Thread.getAllStackTraces().entrySet()) {
            if (!entry.getKey().getName().startsWith(WORKER_PREFIX)) {
                continue;
            }
            for (var frame : entry.getValue()) {
                assertTrue(!frame.getClassName().contains(".video.h264.encoder."),
                        entry.getKey().getName() + " still runs " + frame);
            }
        }
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (liveWorkers() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, liveWorkers(), "idle helpers exit");
    }
}
