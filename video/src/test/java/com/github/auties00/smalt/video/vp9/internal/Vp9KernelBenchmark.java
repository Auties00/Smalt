package com.github.auties00.smalt.video.vp9.internal;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Times the VP9 pixel kernels, scalar and vector, on random input: two-dimensional sub-pixel
 * motion compensation of 8x8, 16x16 and 64x64 blocks, 16-tap loop filtering of a horizontal and
 * a vertical edge of 16 lines, and 8x8 and 32x32 inverse DCTs with every coefficient coded.
 * Each operation processes 64 blocks or edges spread over a plane, so the score divided by 64 is
 * the time of one.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx1g"})
public class Vp9KernelBenchmark {
    private static final int STRIDE = 1024;

    @Param({"scalar", "vector"})
    public String kernels;

    private Vp9Dsp dsp;
    private byte[] reference;
    private byte[] plane;
    private int[][] coefficients8;
    private int[][] coefficients32;
    private final byte[] convolveScratch = new byte[Vp9Dsp.CONVOLVE_SCRATCH];
    private final byte[] filterScratch = new byte[Vp9Dsp.LOOP_FILTER_SCRATCH];
    private final int[] transformScratch = new int[Vp9Dsp.TRANSFORM_SCRATCH];

    @Setup(Level.Trial)
    public void setUp() {
        dsp = kernels.equals("scalar") ? new ScalarVp9Dsp() : VectorVp9Dsp.create();
        var random = new Random(9);
        reference = new byte[STRIDE * 600];
        random.nextBytes(reference);
        plane = new byte[STRIDE * 600];
        for (var i = 0; i < plane.length; i++) {
            plane[i] = (byte) (120 + (i % STRIDE) / 64 * 3 + random.nextInt(3));
        }
        coefficients8 = new int[64][];
        coefficients32 = new int[64][];
        for (var b = 0; b < 64; b++) {
            coefficients8[b] = new int[64];
            coefficients32[b] = new int[1024];
            for (var i = 0; i < 64; i++) {
                coefficients8[b][i] = random.nextInt(31) - 15;
            }
            for (var i = 0; i < 1024; i++) {
                coefficients32[b][i] = random.nextInt(7) - 3;
            }
        }
    }

    private int convolve(int size) {
        for (var b = 0; b < 64; b++) {
            var x = 16 + (b % 8) * 72;
            var y = 16 + (b / 8) * 72;
            dsp.convolve(reference, y * STRIDE + x, STRIDE, plane, y * STRIDE + x, STRIDE, Vp9Constants.FILTER_KERNELS,
                    0, 1 + (b & 7), 16, 1 + (b >> 3), 16, size, size, false, convolveScratch);
        }
        return plane[17];
    }

    @Benchmark
    public int convolve8x8() {
        return convolve(8);
    }

    @Benchmark
    public int convolve16x16() {
        return convolve(16);
    }

    @Benchmark
    public int convolve64x64() {
        return convolve(64);
    }

    @Benchmark
    public int loopFilter16Horizontal() {
        for (var b = 0; b < 64; b++) {
            var off = (16 + (b / 8) * 64) * STRIDE + 16 + (b % 8) * 64;
            dsp.lpfHorizontal16(plane, off, STRIDE, 40, 10, 2, 2, filterScratch);
        }
        return plane[17];
    }

    @Benchmark
    public int loopFilter16Vertical() {
        for (var b = 0; b < 64; b++) {
            var off = (16 + (b / 8) * 64) * STRIDE + 16 + (b % 8) * 64;
            dsp.lpfVertical16(plane, off, STRIDE, 40, 10, 2, 16, filterScratch);
        }
        return plane[17];
    }

    @Benchmark
    public int inverseDct8x8() {
        for (var b = 0; b < 64; b++) {
            var off = (16 + (b / 8) * 64) * STRIDE + 16 + (b % 8) * 64;
            dsp.inverseTransformAdd(Vp9Constants.TX_8X8, Vp9Constants.DCT_DCT, 64, coefficients8[b], 0, plane, off,
                    STRIDE, transformScratch);
        }
        return plane[17];
    }

    @Benchmark
    public int inverseDct32x32() {
        for (var b = 0; b < 64; b++) {
            var off = (16 + (b / 8) * 64) * STRIDE + 16 + (b % 8) * 64;
            dsp.inverseTransformAdd(Vp9Constants.TX_32X32, Vp9Constants.DCT_DCT, 1024, coefficients32[b], 0, plane,
                    off, STRIDE, transformScratch);
        }
        return plane[17];
    }
}
