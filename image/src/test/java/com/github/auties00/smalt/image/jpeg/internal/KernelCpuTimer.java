package com.github.auties00.smalt.image.jpeg.internal;

import java.time.Duration;
import java.util.Random;

/**
 * Measures the process CPU time per block of the transform kernels, scalar against Vector API,
 * for use on a machine whose other processes keep the cores busy.
 */
public final class KernelCpuTimer {
    private KernelCpuTimer() {
    }

    public static void main(String[] args) {
        var blocks = 1024;
        var random = new Random(42);
        var coefficients = new int[blocks][64];
        for (var b = 0; b < blocks; b++) {
            for (var k = 0; k < 20; k++) {
                coefficients[b][JpegTables.ZIGZAG[k]] = (random.nextInt(81) - 40) * (k == 0 ? 8 : 3);
            }
        }
        var block = new int[64];
        var samples = new byte[blocks * 64];
        random.nextBytes(samples);
        var output = new byte[blocks * 64];
        var divisors = new QuantDivisors(JpegWriter.scaledTable(JpegTables.STD_LUMINANCE_QUANT, 92));
        ScalarIdctKernel[] idcts = {new ScalarIdctKernel()};
        BatchFdctKernel[] fdcts = {new ScalarBatchFdctKernel(), VectorKernels.vectorBatchFdct()};
        var batch = new int[BatchFdctKernel.BATCH_LENGTH];
        var masks = new long[BatchFdctKernel.LANES];
        var scratch = new byte[BatchFdctKernel.SCRATCH_LENGTH];
        var rounds = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        for (var repeat = 0; repeat < 3; repeat++) {
            for (var kernel : idcts) {
                var start = cpu();
                for (var r = 0; r < rounds; r++) {
                    for (var b = 0; b < blocks; b++) {
                        System.arraycopy(coefficients[b], 0, block, 0, 64);
                        kernel.idct8(block, output, b * 8, blocks * 8);
                    }
                }
                System.out.printf("idct %-20s %6.1f ns/block%n", kernel.getClass().getSimpleName(),
                        (cpu() - start) / (double) rounds / blocks);
            }
            for (var kernel : fdcts) {
                var start = cpu();
                var mask = 0L;
                for (var r = 0; r < rounds; r++) {
                    for (var b = 0; b + BatchFdctKernel.LANES <= blocks; b += BatchFdctKernel.LANES) {
                        kernel.transform(samples, b * 8, blocks * 8, divisors, batch, masks, scratch);
                        mask ^= masks[0];
                    }
                }
                System.out.printf("fdct %-20s %6.1f ns/block (%d)%n", kernel.getClass().getSimpleName(),
                        (cpu() - start) / (double) rounds / blocks, mask & 1);
            }
        }
    }

    private static long cpu() {
        return ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toNanos();
    }
}
