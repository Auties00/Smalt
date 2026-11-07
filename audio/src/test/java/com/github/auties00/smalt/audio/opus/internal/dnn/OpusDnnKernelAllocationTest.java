package com.github.auties00.smalt.audio.opus.internal.dnn;

import com.github.auties00.smalt.internal.simd.Simd;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that each neural building block the decoder runs per frame allocates nothing once warm: the deep PLC's
 * history update and concealment, and the LACE and NoLACE enhancers. Scalar leg only, like the other allocation
 * tests.
 */
class OpusDnnKernelAllocationTest {
    private static long measure(Runnable work) throws Exception {
        Skip.unless(!Simd.ENABLED, "simd:enabled", "vector kernels allocate until compiled; run with -Dsmalt.test.simd=false");
        Object threads;
        Method allocated;
        try {
            threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null);
            allocated = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw Skip.now("env:allocation-counter", "thread allocation counter unavailable: " + exception);
        }
        for (var k = 0; k < 20; k++) {
            work.run();
        }
        var overhead = Long.MAX_VALUE;
        for (var i = 0; i < 10; i++) {
            var before = (long) allocated.invoke(threads);
            overhead = Math.min(overhead, (long) allocated.invoke(threads) - before);
        }
        long bytes = 0;
        for (var pass = 0; pass < 40; pass++) {
            var before = (long) allocated.invoke(threads);
            work.run();
            bytes = (long) allocated.invoke(threads) - before - overhead;
            if (pass >= 2 && bytes == 0) {
                break;
            }
        }
        return bytes;
    }

    private static short[] speechLike(int samples) {
        var out = new short[samples];
        var seed = 12345;
        for (var i = 0; i < samples; i++) {
            seed = seed * 1664525 + 1013904223;
            var noise = ((seed >>> 8) & 0xFFFF) - 32768;
            out[i] = (short) (8000 * Math.sin(i * 0.05) * Math.sin(i * 0.0021) + noise / 16);
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"update", "conceal"})
    @DisplayName("the deep PLC allocates nothing per 10 ms frame")
    void plc(String operation) throws Exception {
        var plc = new LpcnetPlc();
        var signal = speechLike(160 * 64);
        var frame = new short[160];
        var position = new int[1];
        var conceal = operation.equals("conceal");
        assertEquals(0, measure(() -> {
            for (var k = 0; k < 8; k++) {
                var at = (position[0]++ % 64) * 160;
                if (conceal && k % 4 == 3) {
                    plc.conceal(frame, 0);
                } else {
                    System.arraycopy(signal, at, frame, 0, 160);
                    plc.update(frame, 0);
                }
            }
        }), "bytes allocated over 8 frames");
    }

    @ParameterizedTest(name = "method {0}")
    @ValueSource(ints = {Osce.METHOD_LACE, Osce.METHOD_NOLACE})
    @DisplayName("the speech enhancers allocate nothing per 20 ms frame")
    void enhancer(int method) throws Exception {
        var osce = new Osce();
        osce.reset(method);
        var signal = speechLike(320 * 32);
        var frame = new short[320];
        var predCoef = new short[2][16];
        for (var h = 0; h < 2; h++) {
            for (var i = 0; i < 16; i++) {
                predCoef[h][i] = (short) (3000 / (i + 1));
            }
        }
        var pitchL = new int[]{80, 82, 84, 86};
        var gains = new int[]{65536 * 60, 65536 * 61, 65536 * 62, 65536 * 63};
        var ltp = new short[20];
        for (var i = 0; i < 20; i++) {
            ltp[i] = (short) (1000 + 100 * i);
        }
        var position = new int[1];
        assertEquals(0, measure(() -> {
            for (var k = 0; k < 4; k++) {
                System.arraycopy(signal, (position[0]++ % 32) * 320, frame, 0, 320);
                osce.enhance(frame, 0, 16, 4, 16, k % 3 == 2 ? 1 : 2, predCoef, pitchL, ltp, gains, 400);
            }
        }), "bytes allocated over 4 frames");
    }
}
