package com.github.auties00.smalt.image.jpeg.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Equivalence of the batched inverse DCTs (scalar and Vector API) with the per-block transforms
 * they replace, on randomized batches: dense and sparse blocks, flat rows and columns, values
 * that overflow the 32-bit intermediate arithmetic, and partial batches.
 */
class BatchIdctKernelTest {
    private static final int LANES = BatchIdctKernel.LANES;

    private final BatchIdctKernel scalar = new ScalarBatchIdctKernel();
    private final BatchIdctKernel vector = VectorKernels.vectorBatchIdct();
    private final ScalarIdctKernel reference = new ScalarIdctKernel();

    static Stream<Arguments> cases() {
        return Stream.of(0, 1, 2).flatMap(shift -> Stream.of(64, 1024, 8192, 65536, Integer.MAX_VALUE)
                .map(range -> Arguments.of(shift, range)));
    }

    private static int[][] randomBlocks(Random random, int range) {
        var blocks = new int[LANES][64];
        for (var block : blocks) {
            switch (random.nextInt(5)) {
                case 0 -> block[0] = random.nextInt(4096) - 2048;
                case 1 -> {
                    var column = random.nextInt(8);
                    var row = random.nextInt(8);
                    block[0] = random.nextInt(4096) - 2048;
                    for (var k = 0; k < 8; k++) {
                        if (random.nextBoolean()) {
                            block[k * 8 + column] = random.nextInt(512) - 256;
                        }
                        if (random.nextBoolean()) {
                            block[row * 8 + k] = random.nextInt(512) - 256;
                        }
                    }
                }
                default -> {
                    var density = random.nextInt(65);
                    for (var k = 0; k < density; k++) {
                        var index = random.nextInt(4) == 0 ? random.nextInt(64) : JpegTables.ZIGZAG[random.nextInt(Math.max(1, density))];
                        block[index] = range == Integer.MAX_VALUE ? random.nextInt() : random.nextInt(2 * range + 1) - range;
                    }
                }
            }
        }
        return blocks;
    }

    private static int[] interleave(int[][] blocks) {
        var batch = new int[BatchIdctKernel.BATCH_LENGTH];
        for (var b = 0; b < LANES; b++) {
            for (var k = 0; k < 64; k++) {
                batch[k * LANES + b] = blocks[b][k];
            }
        }
        return batch;
    }

    private void transform(BatchIdctKernel kernel, int shift, int[] batch, int count, byte[] output, int offset, int stride) {
        switch (shift) {
            case 0 -> kernel.idct8(batch, count, output, offset, stride);
            case 1 -> kernel.idct4(batch, count, output, offset, stride);
            default -> kernel.idct2(batch, count, output, offset, stride);
        }
    }

    private void transformBlock(int shift, int[] block, byte[] output, int offset, int stride) {
        switch (shift) {
            case 0 -> reference.idct8(block, output, offset, stride);
            case 1 -> ReducedIdct.idct4(block, output, offset, stride);
            default -> ReducedIdct.idct2(block, output, offset, stride);
        }
    }

    @ParameterizedTest(name = "scale 1/{0} range {1}")
    @MethodSource("cases")
    @DisplayName("scalar and vector batches equal the per-block transforms and clear the batch")
    void equivalence(int shift, int range) {
        var random = new Random(31L * shift + range);
        var size = 8 >> shift;
        var stride = LANES * size + 5;
        var offset = 3;
        for (var i = 0; i < 4_000; i++) {
            var blocks = randomBlocks(random, range);
            var count = random.nextInt(4) == 0 ? 1 + random.nextInt(LANES) : LANES;
            var expected = new byte[offset + size * stride];
            Arrays.fill(expected, (byte) 0x5A);
            for (var b = 0; b < count; b++) {
                transformBlock(shift, blocks[b].clone(), expected, offset + b * size, stride);
            }
            for (var kernel : new BatchIdctKernel[]{scalar, vector}) {
                var batch = interleave(blocks);
                var actual = new byte[expected.length];
                Arrays.fill(actual, (byte) 0x5A);
                transform(kernel, shift, batch, count, actual, offset, stride);
                var name = kernel.getClass().getSimpleName();
                assertArrayEquals(expected, actual, () -> name + " differs for " + Arrays.deepToString(blocks));
                assertArrayEquals(new int[BatchIdctKernel.BATCH_LENGTH], batch, () -> name + " left coefficients");
            }
        }
    }
}
