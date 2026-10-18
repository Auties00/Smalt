package com.github.auties00.smalt.video.h264.internal;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks the deblocking kernels, which transpose vertical edges in registers and filter both
 * chroma planes at once. One operation filters one macroblock's worth of an edge kind: a 16-line
 * luma edge, or both chroma planes of one chroma edge (two edges for {@code chromaVertical}). Each
 * invocation cycles over 64 positions of a smooth, noisy plane where most lines pass the filter
 * conditions.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class H264DeblockKernelBenchmark {
    private static final int STRIDE = 1344;
    private static final int PACKED_TC0 = 2 | (2 << 8) | (3 << 16) | (1 << 24);

    private final H264Dsp dsp = new VectorH264Dsp();
    private final int[] chromaParams = new int[8];
    private final byte[] scratch = new byte[H264Dsp.EDGE_SCRATCH];
    private byte[] luma;
    private byte[] cb;
    private byte[] cr;

    @Setup
    public void setup() {
        var random = new Random(1);
        luma = plane(random);
        cb = plane(random);
        cr = plane(random);
        for (var at = 0; at < 8; at += 4) {
            chromaParams[at] = 60 | (18 << 8);
            chromaParams[at + 1] = 60 | (18 << 8);
            chromaParams[at + 2] = PACKED_TC0;
            chromaParams[at + 3] = PACKED_TC0;
        }
    }

    private static byte[] plane(Random random) {
        var plane = new byte[STRIDE * 160];
        for (var y = 0; y < 160; y++) {
            for (var x = 0; x < STRIDE; x++) {
                plane[y * STRIDE + x] = (byte) (100 + ((x >> 4) & 1) * 9 + ((y >> 4) & 1) * 7 + random.nextInt(3));
            }
        }
        return plane;
    }

    private static int at(int i) {
        return (32 + (i >> 3) * 16) * STRIDE + 32 + (i & 7) * 16;
    }

    @Benchmark
    @OperationsPerInvocation(64)
    public void lumaVertical() {
        for (var i = 0; i < 64; i++) {
            dsp.lumaEdgeVertical(luma, at(i), STRIDE, 60, 18, PACKED_TC0, scratch);
        }
    }

    @Benchmark
    @OperationsPerInvocation(64)
    public void lumaVerticalStrong() {
        for (var i = 0; i < 64; i++) {
            dsp.lumaEdgeVerticalStrong(luma, at(i), STRIDE, 60, 18, scratch);
        }
    }

    @Benchmark
    @OperationsPerInvocation(64)
    public void lumaHorizontal() {
        for (var i = 0; i < 64; i++) {
            dsp.lumaEdgeHorizontal(luma, at(i), STRIDE, 60, 18, PACKED_TC0);
        }
    }

    @Benchmark
    @OperationsPerInvocation(64)
    public void chromaVertical() {
        for (var i = 0; i < 64; i++) {
            var offset = at(i);
            dsp.chromaEdgesVertical(cb, cr, offset, STRIDE, 3, chromaParams, scratch);
        }
    }

    @Benchmark
    @OperationsPerInvocation(64)
    public void chromaHorizontal() {
        for (var i = 0; i < 64; i++) {
            var offset = at(i);
            dsp.chromaEdgeHorizontal(cb, cr, offset, STRIDE, chromaParams, 0);
        }
    }
}
