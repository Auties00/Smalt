package com.github.auties00.smalt.video.h263.internal;

import java.util.Arrays;

/**
 * Computes the inverse DCT in double precision with the separable row and column passes of the reference transform
 * of IEEE 1180 that the ITU-T H.263 test models decode with, rounding to the nearest integer and clipping to -256 to
 * 255 (6.2.4).
 *
 * <p>Decoding with it reproduces the pictures of those reference decoders and encoders sample for sample, which tests
 * rely on; decoding otherwise uses the integer transform of the macroblock engine, whose output differs from it
 * within the accuracy of Annex A.
 */
final class ReferenceTransform {
    /**
     * The basis functions, indexed by frequency and then by sample.
     */
    private static final double[][] BASIS = new double[8][8];

    static {
        for (var frequency = 0; frequency < 8; frequency++) {
            var scale = frequency == 0 ? StrictMath.sqrt(0.125) : 0.5;
            for (var time = 0; time < 8; time++) {
                BASIS[frequency][time] = scale * StrictMath.cos((StrictMath.PI / 8.0) * frequency * (time + 0.5));
            }
        }
    }

    /**
     * Prevents instantiation.
     */
    private ReferenceTransform() {
    }

    /**
     * Transforms a coefficient block into the residual samples.
     *
     * @param coefficients the coefficients in column-major order (coefficient {@code column} of row {@code row} at
     * {@code offset + column * 8 + row}); cleared on return
     * @param offset the index of the block's first coefficient
     * @param out receives the 64 samples in raster order
     */
    static void inverse(short[] coefficients, int offset, int[] out) {
        var rows = new double[64];
        for (var i = 0; i < 8; i++) {
            for (var j = 0; j < 8; j++) {
                var partial = 0.0;
                for (var k = 0; k < 8; k++) {
                    partial += BASIS[k][j] * coefficients[offset + k * 8 + i];
                }
                rows[8 * i + j] = partial;
            }
        }
        for (var j = 0; j < 8; j++) {
            for (var i = 0; i < 8; i++) {
                var partial = 0.0;
                for (var k = 0; k < 8; k++) {
                    partial += BASIS[k][i] * rows[8 * k + j];
                }
                var value = (int) StrictMath.floor(partial + 0.5);
                out[8 * i + j] = value < -256 ? -256 : Math.min(value, 255);
            }
        }
        Arrays.fill(coefficients, offset, offset + 64, (short) 0);
    }
}
