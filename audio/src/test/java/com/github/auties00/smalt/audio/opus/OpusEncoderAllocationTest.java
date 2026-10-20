package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.opus.internal.OpusDefines;
import com.github.auties00.smalt.audio.opus.internal.OpusMultistreamEncoder;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks that the multistream encoder, and the single-stream encoder under it, allocate nothing per
 * packet once warm: in the paths that pad constant-bitrate packets, assemble multi-frame packets,
 * filter VoIP input and code SILK frames. The packet space is one byte short of a maximal packet
 * per stream, so every stream is coded on the calling thread, whose allocations the counter sees.
 *
 * <p>The test runs on the scalar kernels (the scalar leg, {@code -Dsmalt.test.simd=false}): a vector
 * kernel allocates until the JIT compiles it, at a time the test cannot control.
 */
class OpusEncoderAllocationTest {
    @ParameterizedTest(name = "{0}ch family {1} {2} {3} {4} us")
    @CsvSource({
            "6, 1, audio, vbr, 20000",
            "6, 1, audio, cbr, 20000",
            "6, 1, audio, vbr, 60000",
            "6, 1, audio, cbr, 60000",
            "6, 1, voip, vbr, 60000",
            "8, 1, voip, cbr, 40000",
            "4, 2, audio, cbr, 20000",
            "3, 255, voip, cbr, 120000",
            "2, 1, voip, vbr, 10000",
            "2, 0, audio, cbr, 60000",
            "1, 0, voip, vbr, 40000",
            "1, 0, voip, cbr, 20000"
    })
    @DisplayName("allocates nothing per packet in steady state")
    void steadyState(int channels, int family, String application, String mode, int micros) throws Exception {
        assumeTrue(!Simd.ENABLED, "vector kernels allocate until compiled; run with -Dsmalt.test.simd=false");
        Object threads;
        Method allocated;
        try {
            threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null);
            allocated = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            assumeTrue(false, "thread allocation counter unavailable: " + exception);
            return;
        }
        var encoder = new OpusMultistreamEncoder(48000, channels, family, application.equals("voip")
                ? OpusDefines.OPUS_APPLICATION_VOIP : OpusDefines.OPUS_APPLICATION_AUDIO);
        encoder.setBitrate(48000 * channels);
        encoder.setVbr(mode.equals("vbr"));
        var frame = 48 * micros / 1000;
        var random = new Random(channels * 31L + micros);
        var pcm = new float[frame * channels * 8];
        for (var i = 0; i < pcm.length; i++) {
            pcm[i] = (float) (0.2 * Math.sin(i * 0.013 / channels) + 0.05 * random.nextGaussian());
        }
        var packet = new byte[encoder.streamCount() * (6 * 1275 + 12 - 1)];
        for (var k = 0; k < 16; k++) {
            assertTrue(encoder.encodeFloat(pcm, (k % 8) * frame * channels, frame, packet, 0, packet.length) > 0);
        }
        // The reflective counter boxes its result: measure that overhead with nothing in between
        var overhead = Long.MAX_VALUE;
        for (var i = 0; i < 10; i++) {
            var before = (long) allocated.invoke(threads);
            overhead = Math.min(overhead, (long) allocated.invoke(threads) - before);
        }
        long bytes = 0;
        for (var pass = 0; pass < 40; pass++) {
            var before = (long) allocated.invoke(threads);
            for (var k = 0; k < 8; k++) {
                encoder.encodeFloat(pcm, k * frame * channels, frame, packet, 0, packet.length);
            }
            bytes = (long) allocated.invoke(threads) - before - overhead;
            if (pass >= 2 && bytes == 0) {
                break;
            }
        }
        assertEquals(0, bytes, "bytes allocated over 8 packets");
    }
}
