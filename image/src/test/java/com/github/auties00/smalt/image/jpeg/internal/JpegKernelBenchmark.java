package com.github.auties00.smalt.image.jpeg.internal;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Measures the transform kernels on 1024 realistic blocks (a few low-frequency coefficients, as
 * q92 photos have), scalar against Vector API.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class JpegKernelBenchmark {
    private static final int BLOCKS = 1024;

    private final ScalarIdctKernel scalarIdct = new ScalarIdctKernel();
    private final ScalarFdctKernel scalarFdct = new ScalarFdctKernel();
    private final BatchFdctKernel scalarBatchFdct = new ScalarBatchFdctKernel();
    private final BatchFdctKernel vectorBatchFdct = VectorKernels.vectorBatchFdct();
    private final int[] fdctBatch = new int[BatchFdctKernel.BATCH_LENGTH];
    private final long[] fdctMasks = new long[BatchFdctKernel.LANES];
    private final byte[] fdctScratch = new byte[BatchFdctKernel.SCRATCH_LENGTH];
    private final BatchIdctKernel scalarBatch = new ScalarBatchIdctKernel();
    private final BatchIdctKernel vectorBatch = VectorKernels.vectorBatchIdct();
    private int[][] templates;
    private int[] work;
    private final ColorKernel scalarColor = new ScalarColorKernel();
    private final ColorKernel vectorColor = VectorKernels.vectorColor();
    private int[][] coefficients;
    private int[] block;
    private byte[] samples;
    private byte[] output;
    private QuantDivisors divisors;
    private byte[] rgba;
    private byte[] y;
    private byte[] cb;
    private byte[] cr;

    @Setup
    public void setup() {
        var random = new Random(42);
        coefficients = new int[BLOCKS][64];
        for (var b = 0; b < BLOCKS; b++) {
            for (var k = 0; k < 20; k++) {
                coefficients[b][JpegTables.ZIGZAG[k]] = (random.nextInt(81) - 40) * (k == 0 ? 8 : 3);
            }
        }
        block = new int[64];
        templates = new int[BLOCKS / 8][BatchIdctKernel.BATCH_LENGTH];
        work = new int[BatchIdctKernel.BATCH_LENGTH];
        for (var g = 0; g < BLOCKS / 8; g++) {
            for (var lane = 0; lane < 8; lane++) {
                for (var k = 0; k < 64; k++) {
                    templates[g][k * 8 + lane] = coefficients[g * 8 + lane][k];
                }
            }
        }
        samples = new byte[BLOCKS * 64];
        random.nextBytes(samples);
        output = new byte[BLOCKS * 64];
        divisors = new QuantDivisors(JpegWriter.scaledTable(JpegTables.STD_LUMINANCE_QUANT, 92));
        rgba = new byte[4032 * 4];
        random.nextBytes(rgba);
        y = new byte[4032];
        cb = new byte[4032];
        cr = new byte[4032];
    }

    @Benchmark
    @OperationsPerInvocation(BLOCKS)
    public byte[] idctScalar() {
        return idct(scalarIdct);
    }

    private byte[] idct(ScalarIdctKernel kernel) {
        for (var b = 0; b < BLOCKS; b++) {
            System.arraycopy(coefficients[b], 0, block, 0, 64);
            kernel.idct8(block, output, b * 8, BLOCKS * 8);
        }
        return output;
    }

    @Benchmark
    @OperationsPerInvocation(BLOCKS)
    public byte[] idct4PerBlock() {
        for (var b = 0; b < BLOCKS; b++) {
            System.arraycopy(coefficients[b], 0, block, 0, 64);
            ReducedIdct.idct4(block, output, b * 4, BLOCKS * 4);
        }
        return output;
    }

    @Benchmark
    @OperationsPerInvocation(BLOCKS)
    public byte[] batchIdct8Scalar() {
        return batch(scalarBatch, 0);
    }

    @Benchmark
    @OperationsPerInvocation(BLOCKS)
    public byte[] batchIdct8Vector() {
        return batch(vectorBatch, 0);
    }

    @Benchmark
    @OperationsPerInvocation(BLOCKS)
    public byte[] batchIdct4Scalar() {
        return batch(scalarBatch, 1);
    }

    @Benchmark
    @OperationsPerInvocation(BLOCKS)
    public byte[] batchIdct4Vector() {
        return batch(vectorBatch, 1);
    }

    private byte[] batch(BatchIdctKernel kernel, int shift) {
        for (var g = 0; g < BLOCKS / 8; g++) {
            System.arraycopy(templates[g], 0, work, 0, BatchIdctKernel.BATCH_LENGTH);
            if (shift == 0) {
                kernel.idct8(work, 8, output, g * 64, BLOCKS * 8);
            } else {
                kernel.idct4(work, 8, output, g * 32, BLOCKS * 4);
            }
        }
        return output;
    }


    @Benchmark
    @OperationsPerInvocation(BLOCKS)
    public long fdctScalar() {
        return fdct(scalarFdct);
    }

    @Benchmark
    @OperationsPerInvocation(BLOCKS)
    public long batchFdctScalar() {
        return batchFdct(scalarBatchFdct);
    }

    @Benchmark
    @OperationsPerInvocation(BLOCKS)
    public long batchFdctVector() {
        return batchFdct(vectorBatchFdct);
    }

    private long batchFdct(BatchFdctKernel kernel) {
        var mask = 0L;
        for (var b = 0; b < BLOCKS; b += BatchFdctKernel.LANES) {
            kernel.transform(samples, b * 8, BLOCKS * 8, divisors, fdctBatch, fdctMasks, fdctScratch);
            mask ^= fdctMasks[3];
        }
        return mask;
    }

    private long fdct(ScalarFdctKernel kernel) {
        var mask = 0L;
        for (var b = 0; b < BLOCKS; b++) {
            mask ^= kernel.transform(samples, b * 8, BLOCKS * 8, divisors, block);
        }
        return mask;
    }

    @Benchmark
    public byte[] colorScalar() {
        scalarColor.toYcc(rgba, 0, 4032, y, cb, cr);
        return y;
    }

    @Benchmark
    public byte[] colorVector() {
        vectorColor.toYcc(rgba, 0, 4032, y, cb, cr);
        return y;
    }
}
