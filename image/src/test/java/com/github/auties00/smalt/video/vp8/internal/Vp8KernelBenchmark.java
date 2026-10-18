package com.github.auties00.smalt.video.vp8.internal;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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
 * Measures the VP8 decoder and encoder kernels, scalar against vector, on random data.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class Vp8KernelBenchmark {
    @Param({"scalar", "vector"})
    public String impl;

    private Vp8Dsp dsp;
    private Vp8EncDsp enc;
    private final byte[] plane = new byte[128 * 128];
    private final byte[] dst = new byte[64 * 64];
    private final byte[] scratch = new byte[Vp8Dsp.SCRATCH_SIZE];
    private final byte[] work = new byte[32 * 16];
    private final byte[] ref = new byte[32 * 16];
    private final short[] coeffs = new short[16];
    private final short[] levels = new short[16];
    private final int[] weights = {38, 32, 20, 9, 32, 28, 17, 7, 20, 17, 10, 4, 9, 7, 4, 2};
    private final QuantMatrix matrix = new QuantMatrix();

    @Setup
    public void setup() {
        dsp = impl.equals("scalar") ? new ScalarVp8Dsp() : VectorVp8Dsp.create();
        enc = impl.equals("scalar") ? new ScalarVp8EncDsp() : VectorVp8EncDsp.create();
        var random = new Random(1);
        for (var i = 0; i < plane.length; i++) {
            plane[i] = (byte) (100 + random.nextInt(24));
        }
        random.nextBytes(work);
        random.nextBytes(ref);
        for (var i = 0; i < 16; i++) {
            matrix.q[i] = 20;
            matrix.iq[i] = (1 << 17) / 20;
            matrix.bias[i] = 110 << 9;
            matrix.zthresh[i] = ((1 << 17) - 1 - matrix.bias[i]) / matrix.iq[i];
        }
    }

    @Benchmark
    public void filterNormal() {
        dsp.filterNormal(plane, 20 * 128 + 20, 128, plane, plane, 60 * 128 + 60, 128, 60, 50, 10, 2,
                Vp8Dsp.EDGE_LEFT | Vp8Dsp.EDGE_TOP | Vp8Dsp.EDGE_INNER, scratch);
    }

    @Benchmark
    public void filterSimple() {
        dsp.filterSimple(plane, 20 * 128 + 20, 128, 60, 50, Vp8Dsp.EDGE_LEFT | Vp8Dsp.EDGE_TOP | Vp8Dsp.EDGE_INNER, scratch);
    }

    @Benchmark
    public void sixtap16() {
        dsp.sixtap(plane, 20 * 128 + 20, 128, dst, 0, 64, 16, 16, 3, 5, scratch);
    }

    @Benchmark
    public void sixtap8() {
        dsp.sixtap(plane, 20 * 128 + 20, 128, dst, 0, 64, 8, 8, 2, 6, scratch);
    }

    @Benchmark
    public void bilinear16() {
        dsp.bilinear(plane, 20 * 128 + 20, 128, dst, 0, 64, 16, 16, 3, 5, scratch);
    }

    @Benchmark
    public void idct() {
        for (var i = 0; i < 16; i++) {
            coeffs[i] = (short) (i * 7 - 50);
        }
        dsp.idctAdd(coeffs, 0, plane, 30 * 128 + 30, 128);
    }

    @Benchmark
    public void encFTransform() {
        enc.fTransform(work, 0, ref, 0, coeffs, 0);
    }

    @Benchmark
    public void encITransform() {
        enc.iTransform(ref, 0, coeffs, 0, work, 4);
    }

    @Benchmark
    public int encSse16x16() {
        return enc.sse(work, 0, ref, 0, 16, 16);
    }

    @Benchmark
    public int encTDisto4x4() {
        return enc.tDisto4x4(work, 0, ref, 0, weights);
    }

    @Benchmark
    public int encQuantize() {
        enc.fTransform(work, 0, ref, 0, coeffs, 0);
        return enc.quantizeBlock(coeffs, 0, levels, 0, matrix);
    }

    private final byte[] recon = new byte[32 * 16];

    @Benchmark
    public long encI4Separate() {
        enc.fTransform(work, 0, ref, 0, coeffs, 0);
        var nz = enc.quantizeBlock(coeffs, 0, levels, 0, matrix);
        enc.iTransform(ref, 0, coeffs, 0, recon, 0);
        return nz + enc.sse(work, 0, recon, 0, 4, 4) + enc.tDisto4x4(work, 0, recon, 0, weights);
    }

    @Benchmark
    public long encI4Fused() {
        return enc.evaluateBlock(work, 0, ref, 0, matrix, levels, 0, recon, 0, weights, coeffs);
    }
}
