package com.github.auties00.smalt.video.mpeg4.internal;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Times the MPEG-4 Part 2 kernels: the quarter sample interpolation of a 16x16 block at all 16
 * fractional positions and the inverse DCT of 64 blocks (store and add), for the first
 * quarter sample kernel ({@code reference}), the scalar kernels and the Vector API kernels.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class Mpeg4KernelBenchmark {
    private static final int STRIDE = 64;

    @Param({"reference", "scalar", "vector"})
    public String kernel;

    private Mpeg4Dsp dsp;
    private byte[] src;
    private byte[] dst;
    private int[] scratch;
    private short[][] blocks;
    private short[] block;
    private int[] work;

    @Setup(Level.Trial)
    public void setUp() {
        dsp = kernel.equals("vector") ? VectorMpeg4Dsp.create() : new ScalarMpeg4Dsp();
        var random = new Random(7);
        src = new byte[STRIDE * 48];
        random.nextBytes(src);
        dst = new byte[STRIDE * 24];
        scratch = new int[Mpeg4Dsp.SCRATCH_INTS];
        blocks = new short[64][64];
        for (var b = 0; b < 64; b++) {
            for (var i = 0; i < 8; i++) {
                blocks[b][random.nextInt(64)] = (short) (random.nextInt(400) - 200);
            }
        }
        block = new short[64];
        work = new int[64];
    }

    @Benchmark
    public byte quarterPel() {
        for (var fraction = 1; fraction < 16; fraction++) {
            if (kernel.equals("reference")) {
                ReferenceQuarterPel.quarterPel(src, 8 * STRIDE + 8 + fraction, STRIDE, dst, 0, STRIDE, 16, 16,
                        fraction & 3, fraction >> 2, fraction & 1, scratch);
            } else {
                dsp.quarterPel(src, 8 * STRIDE + 8 + fraction, STRIDE, dst, 0, STRIDE, 16, 16, fraction & 3,
                        fraction >> 2, fraction & 1, scratch);
            }
        }
        return dst[17];
    }

    @Benchmark
    public byte idct() {
        for (var b = 0; b < 64; b++) {
            System.arraycopy(blocks[b], 0, block, 0, 64);
            if ((b & 1) == 0) {
                dsp.idctPut(block, 0, dst, (b & 7) * 8, STRIDE, work);
            } else {
                dsp.idctAdd(block, 0, dst, (b & 7) * 8, STRIDE, work);
            }
        }
        return dst[9];
    }
}
