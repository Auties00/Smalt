package com.github.auties00.smalt.video.h264.internal;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks the motion-compensation kernels on 64 block positions of a random reference plane per
 * invocation. Chroma operations interpolate the co-located blocks of both planes in one call.
 * {@code fraction} is {@code fracY * 8 + fracX} for chroma and {@code fracY * 4 + fracX} (low four
 * bits) for luma.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class H264McBenchmark {
    private static final int STRIDE = 1344;

    @Param({"0", "3", "24", "27"})
    public int fraction;

    private final H264Dsp dsp = new VectorH264Dsp();
    private byte[] cb;
    private byte[] cr;
    private byte[] luma;
    private final byte[] dstCb = new byte[16 * 16];
    private final byte[] dstCr = new byte[16 * 16];
    private final byte[] dst = new byte[16 * 16];
    private final short[] sums = new short[H264Dsp.LUMA_SCRATCH_SHORTS];
    private final byte[] scratch = new byte[H264Dsp.LUMA_SCRATCH_BYTES];
    private final int[] offsets = new int[64];

    @Setup
    public void setup() {
        var random = new Random(1);
        cb = new byte[STRIDE * 128];
        cr = new byte[STRIDE * 128];
        luma = new byte[STRIDE * 128];
        random.nextBytes(cb);
        random.nextBytes(cr);
        random.nextBytes(luma);
        for (var i = 0; i < offsets.length; i++) {
            offsets[i] = (8 + random.nextInt(100)) * STRIDE + 8 + random.nextInt(1300);
        }
    }

    private void chroma(int width, int height) {
        var fx = fraction & 7;
        var fy = fraction >> 3;
        for (var offset : offsets) {
            dsp.chromaMc(cb, cr, offset, STRIDE, dstCb, dstCr, 0, 16, width, height, fx, fy);
        }
    }

    private void luma(int width, int height) {
        var fx = fraction & 3;
        var fy = (fraction >> 2) & 3;
        for (var offset : offsets) {
            dsp.lumaMc(luma, offset, STRIDE, dst, 0, 16, width, height, fx, fy, sums, scratch);
        }
    }

    @Benchmark
    @OperationsPerInvocation(64)
    public void chroma8x8() {
        chroma(8, 8);
    }

    @Benchmark
    @OperationsPerInvocation(64)
    public void chroma4x4() {
        chroma(4, 4);
    }

    @Benchmark
    @OperationsPerInvocation(64)
    public void luma16x16() {
        luma(16, 16);
    }

    @Benchmark
    @OperationsPerInvocation(64)
    public void luma8x8() {
        luma(8, 8);
    }
}
