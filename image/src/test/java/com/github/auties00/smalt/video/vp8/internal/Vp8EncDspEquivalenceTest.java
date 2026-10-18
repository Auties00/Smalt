package com.github.auties00.smalt.video.vp8.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the Vector API encoder kernels produce exactly the results of the scalar reference
 * on randomized work buffers, including saturated pixels and coefficients at the 16-bit limits.
 */
@DisplayName("VP8 encoder kernels: vector equals scalar")
class Vp8EncDspEquivalenceTest {
    static final int BPS = Vp8EncDsp.BPS;
    static final int TRIALS = 20_000;
    static final ScalarVp8EncDsp SCALAR = new ScalarVp8EncDsp();
    static final Vp8EncDsp VECTOR = VectorVp8EncDsp.create();

    static byte[] pixels(Random random, int size) {
        var buffer = new byte[size];
        switch (random.nextInt(4)) {
            case 0 -> random.nextBytes(buffer);
            case 1 -> {
                for (var i = 0; i < size; i++) {
                    buffer[i] = (byte) (random.nextBoolean() ? 0 : 255);
                }
            }
            case 2 -> {
                var base = random.nextInt(256);
                for (var i = 0; i < size; i++) {
                    buffer[i] = (byte) Math.clamp(base + random.nextInt(9) - 4, 0, 255);
                }
            }
            default -> Arrays.fill(buffer, (byte) random.nextInt(256));
        }
        return buffer;
    }

    static short[] coefficients(Random random) {
        var coefficients = new short[20];
        for (var i = 0; i < coefficients.length; i++) {
            coefficients[i] = switch (random.nextInt(4)) {
                case 0 -> (short) random.nextInt(65536);
                case 1 -> (short) (random.nextBoolean() ? Short.MIN_VALUE : Short.MAX_VALUE);
                case 2 -> 0;
                default -> (short) (random.nextInt(4096) - 2048);
            };
        }
        return coefficients;
    }

    @Test
    @DisplayName("forward transform")
    void forwardTransform() {
        var random = new Random(11);
        for (var trial = 0; trial < TRIALS; trial++) {
            var src = pixels(random, 8 * BPS);
            var ref = pixels(random, 8 * BPS);
            var srcOff = random.nextInt(4) * BPS + random.nextInt(28);
            var refOff = random.nextInt(4) * BPS + random.nextInt(28);
            var expected = new short[24];
            var actual = new short[24];
            var outOff = random.nextInt(8);
            SCALAR.fTransform(src, srcOff, ref, refOff, expected, outOff);
            VECTOR.fTransform(src, srcOff, ref, refOff, actual, outOff);
            assertArrayEquals(expected, actual, "trial " + trial);
        }
    }

    @Test
    @DisplayName("inverse transform with reconstruction, including 16-bit extreme coefficients")
    void inverseTransform() {
        var random = new Random(12);
        for (var trial = 0; trial < TRIALS; trial++) {
            var ref = pixels(random, 8 * BPS);
            var coefficients = coefficients(random);
            var refOff = random.nextInt(4) * BPS + random.nextInt(28);
            var dstOff = random.nextInt(4) * BPS + random.nextInt(28);
            var inOff = random.nextInt(4);
            var expected = pixels(random, 8 * BPS);
            var actual = expected.clone();
            SCALAR.iTransform(ref, refOff, coefficients, inOff, expected, dstOff);
            VECTOR.iTransform(ref, refOff, coefficients, inOff, actual, dstOff);
            assertArrayEquals(expected, actual, "trial " + trial);
        }
    }

    @Test
    @DisplayName("the key-frame encoder's inverse transform reconstructs as the decoder's")
    void inverseTransformMatchesDecoder() {
        // The two transforms are ports of different upstreams; they agree while the first pass
        // fits in 16 bits, which holds for dequantized coefficients of at most 2048 in magnitude.
        var random = new Random(16);
        var decoder = new ScalarVp8Dsp();
        for (var trial = 0; trial < TRIALS; trial++) {
            var coefficients = new short[16];
            for (var i = 0; i < 16; i++) {
                coefficients[i] = random.nextInt(3) == 0 ? 0 : (short) (random.nextInt(4097) - 2048);
            }
            var pred = pixels(random, 4 * BPS);
            var encoded = new byte[4 * BPS];
            SCALAR.iTransform(pred, 0, coefficients, 0, encoded, 0);
            var decoded = pred.clone();
            decoder.idctAdd(coefficients.clone(), 0, decoded, 0, BPS);
            for (var row = 0; row < 4; row++) {
                assertArrayEquals(Arrays.copyOfRange(decoded, row * BPS, row * BPS + 4),
                        Arrays.copyOfRange(encoded, row * BPS, row * BPS + 4), "trial " + trial + ", row " + row);
            }
        }
    }

    @ParameterizedTest(name = "{0}x{1}")
    @CsvSource({"16,16", "16,8", "8,8", "4,4", "4,2", "3,5"})
    @DisplayName("sum of squared differences")
    void sse(int width, int height) {
        var random = new Random(13L * width + height);
        for (var trial = 0; trial < TRIALS / 4; trial++) {
            var a = pixels(random, 20 * BPS);
            var b = pixels(random, 20 * BPS);
            var aOff = random.nextInt(4) * BPS + random.nextInt(BPS - width);
            var bOff = random.nextInt(4) * BPS + random.nextInt(BPS - width);
            assertEquals(SCALAR.sse(a, aOff, b, bOff, width, height), VECTOR.sse(a, aOff, b, bOff, width, height),
                    "trial " + trial);
        }
    }

    @Test
    @DisplayName("spectral distortion of 4x4 and 16x16 blocks")
    void spectralDistortion() {
        var random = new Random(14);
        for (var trial = 0; trial < TRIALS; trial++) {
            var a = pixels(random, 20 * BPS);
            var b = pixels(random, 20 * BPS);
            var weights = new int[16];
            for (var i = 0; i < 16; i++) {
                weights[i] = random.nextInt(64);
            }
            var aOff = random.nextInt(4) * BPS + random.nextInt(16);
            var bOff = random.nextInt(4) * BPS + random.nextInt(16);
            assertEquals(SCALAR.tDisto4x4(a, aOff, b, bOff, weights), VECTOR.tDisto4x4(a, aOff, b, bOff, weights),
                    "4x4 trial " + trial);
            if (trial % 16 == 0) {
                assertEquals(SCALAR.tDisto16x16(a, aOff, b, bOff, weights), VECTOR.tDisto16x16(a, aOff, b, bOff, weights),
                        "16x16 trial " + trial);
            }
        }
    }

    static QuantMatrix randomMatrix(Random random) {
        var matrix = new QuantMatrix();
        for (var j = 0; j < 16; j++) {
            var q = 1 + random.nextInt(random.nextBoolean() ? 157 : 2048);
            matrix.q[j] = q;
            matrix.iq[j] = (1 << QuantMatrix.QFIX) / q;
            matrix.bias[j] = random.nextInt(1 << QuantMatrix.QFIX);
            matrix.zthresh[j] = random.nextInt(q + 1);
            matrix.sharpen[j] = random.nextInt(8) == 0 ? random.nextInt(20) : 0;
        }
        return matrix;
    }

    @Test
    @DisplayName("spectral sums of 4x4 blocks")
    void spectralSums() {
        var random = new Random(16);
        for (var trial = 0; trial < TRIALS; trial++) {
            var a = pixels(random, 20 * BPS);
            var weights = new int[16];
            for (var i = 0; i < 16; i++) {
                weights[i] = random.nextInt(64);
            }
            var aOff = random.nextInt(4) * BPS + random.nextInt(16);
            assertEquals(SCALAR.spectralSum(a, aOff, weights), VECTOR.spectralSum(a, aOff, weights), "trial " + trial);
        }
    }

    @Test
    @DisplayName("single-pass block coding equals transform, quantization and reconstruction")
    void evaluateBlock() {
        var random = new Random(17);
        for (var trial = 0; trial < TRIALS; trial++) {
            var src = pixels(random, 20 * BPS);
            var pred = random.nextInt(4) == 0 ? src.clone() : pixels(random, 20 * BPS);
            var matrix = randomMatrix(random);
            var weights = new int[16];
            for (var i = 0; i < 16; i++) {
                weights[i] = random.nextInt(64);
            }
            var srcOff = random.nextInt(4) * BPS + random.nextInt(16);
            var predOff = random.nextInt(4) * BPS + random.nextInt(16);
            var reconOff = random.nextInt(4) * BPS + random.nextInt(16);
            var levelsOff = random.nextInt(4);
            var expectedLevels = new short[20];
            var actualLevels = new short[20];
            var expectedRecon = new byte[20 * BPS];
            var actualRecon = new byte[20 * BPS];
            var expected = SCALAR.evaluateBlock(src, srcOff, pred, predOff, matrix, expectedLevels, levelsOff, expectedRecon,
                    reconOff, weights, new short[16]);
            var actual = VECTOR.evaluateBlock(src, srcOff, pred, predOff, matrix, actualLevels, levelsOff, actualRecon,
                    reconOff, weights, new short[16]);
            assertEquals(expected, actual, "result, trial " + trial);
            assertArrayEquals(expectedLevels, actualLevels, "levels, trial " + trial);
            assertArrayEquals(expectedRecon, actualRecon, "reconstruction, trial " + trial);
            var coefficients = new short[16];
            var levels = new short[20];
            var recon = new byte[20 * BPS];
            SCALAR.fTransform(src, srcOff, pred, predOff, coefficients, 0);
            var nonZero = SCALAR.quantizeBlock(coefficients, 0, levels, levelsOff, matrix);
            SCALAR.iTransform(pred, predOff, coefficients, 0, recon, reconOff);
            assertEquals(nonZero, (int) (expected & 1), "nonzero, trial " + trial);
            assertEquals(SCALAR.sse(src, srcOff, recon, reconOff, 4, 4), (int) ((expected >>> 1) & 0x7FFFFFFF), "sse, trial " + trial);
            assertEquals(SCALAR.tDisto4x4(src, srcOff, recon, reconOff, weights),
                    Math.abs((int) (expected >>> 32) - SCALAR.spectralSum(src, srcOff, weights)) >> 5, "disto, trial " + trial);
            assertArrayEquals(levels, expectedLevels, "composed levels, trial " + trial);
            assertArrayEquals(recon, expectedRecon, "composed reconstruction, trial " + trial);
        }
    }

    @Test
    @DisplayName("quantization with random matrices, sharpening and dead zones")
    void quantization() {
        var random = new Random(15);
        for (var trial = 0; trial < TRIALS; trial++) {
            var matrix = new QuantMatrix();
            for (var j = 0; j < 16; j++) {
                var q = 1 + random.nextInt(random.nextBoolean() ? 157 : 2048);
                matrix.q[j] = q;
                matrix.iq[j] = (1 << QuantMatrix.QFIX) / q;
                matrix.bias[j] = random.nextInt(1 << QuantMatrix.QFIX);
                matrix.zthresh[j] = random.nextInt(q + 1);
                matrix.sharpen[j] = random.nextInt(8) == 0 ? random.nextInt(20) : 0;
            }
            var coefficients = coefficients(random);
            var inOff = random.nextInt(4);
            var expectedIn = coefficients.clone();
            var actualIn = coefficients.clone();
            var expectedOut = new short[20];
            var actualOut = new short[20];
            var outOff = random.nextInt(4);
            var expected = SCALAR.quantizeBlock(expectedIn, inOff, expectedOut, outOff, matrix);
            var actual = VECTOR.quantizeBlock(actualIn, inOff, actualOut, outOff, matrix);
            assertEquals(expected, actual, "result, trial " + trial);
            assertArrayEquals(expectedIn, actualIn, "dequantized, trial " + trial);
            assertArrayEquals(expectedOut, actualOut, "levels, trial " + trial);
        }
    }
}
