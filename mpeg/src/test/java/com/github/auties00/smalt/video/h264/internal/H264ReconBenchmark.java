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
 * Benchmarks the residual reconstruction kernels: a 4x4 inverse transform, a 4x4 DC-only add, an
 * 8x8 inverse transform, and the DC-only reconstruction of a whole 8x8 chroma block. Each
 * invocation reconstructs 64 blocks of sparse random coefficients (about one in five non-zero,
 * within the conformant range) into a random plane; the coefficients are restored before every
 * block because the kernels clear them.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class H264ReconBenchmark {
    private static final int STRIDE = 1344;
    private static final int BLOCKS = 64;

    private final H264Dsp dsp = new VectorH264Dsp();
    private byte[] plane;
    private int[][] pristine;
    private int[] work;
    private int[] offsets;

    @Setup
    public void setup() {
        var random = new Random(5);
        plane = new byte[STRIDE * 64];
        random.nextBytes(plane);
        pristine = new int[BLOCKS][64];
        offsets = new int[BLOCKS];
        for (var i = 0; i < BLOCKS; i++) {
            for (var k = 0; k < 64; k++) {
                pristine[i][k] = random.nextInt(5) == 0 ? random.nextInt(2048) - 1024 : 0;
            }
            pristine[i][0] = random.nextInt(4096) - 2048;
            offsets[i] = (8 + random.nextInt(40)) * STRIDE + 16 + random.nextInt(1300);
        }
        work = new int[64];
    }

    @Benchmark
    @OperationsPerInvocation(BLOCKS)
    public void idct4x4() {
        for (var i = 0; i < BLOCKS; i++) {
            System.arraycopy(pristine[i], 0, work, 0, 16);
            dsp.idct4x4Add(work, 0, plane, offsets[i], STRIDE);
        }
    }

    @Benchmark
    @OperationsPerInvocation(BLOCKS)
    public void idct4x4Dc() {
        for (var i = 0; i < BLOCKS; i++) {
            work[0] = pristine[i][0];
            dsp.idct4x4DcAdd(work, 0, plane, offsets[i], STRIDE);
        }
    }

    @Benchmark
    @OperationsPerInvocation(BLOCKS)
    public void idct8x8() {
        for (var i = 0; i < BLOCKS; i++) {
            System.arraycopy(pristine[i], 0, work, 0, 64);
            dsp.idct8x8Add(work, 0, plane, offsets[i], STRIDE);
        }
    }

    @Benchmark
    @OperationsPerInvocation(BLOCKS)
    public void chromaDc8x8() {
        for (var i = 0; i < BLOCKS; i++) {
            var source = pristine[i];
            work[0] = source[0];
            work[16] = source[1];
            work[32] = source[2];
            work[48] = source[3];
            var offset = offsets[i];
            dsp.chromaDcAdd(work, plane, offset, STRIDE);
        }
    }
}
