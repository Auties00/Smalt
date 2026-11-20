package com.github.auties00.smalt.video.dv.internal;

/**
 * Computes the DCTs of DV video: the 8-8 mode, the orthonormal 8 by 8 DCT of SMPTE ST 314 clause 5.2.1 and SMPTE
 * ST 370 clause 4.2, and the 2-4-8 mode of SMPTE ST 314, an 8-point horizontal DCT followed by 4-point vertical
 * DCTs of the sums and the differences of the two fields.
 *
 * <p>Blocks are 64 values in raster order, coefficient {@code v * 8 + h} at vertical frequency {@code v} and
 * horizontal frequency {@code h}. The transforms are computed in single precision with fixed operation order, so
 * their results do not depend on the platform. Arguments are not checked; values outside the documented ranges
 * give unspecified results. This class is thread-safe.
 */
public final class DvTransform {
    /**
     * The 8-point basis: {@code BASIS_8[k * 8 + n] = c(k) cos(pi k (2n + 1) / 16)}, {@code c(0) = 0.5 / sqrt(2)},
     * {@code c(k) = 0.5} otherwise.
     */
    private static final float[] BASIS_8 = new float[64];

    /**
     * The 4-point basis of the 2-4-8 mode: {@code BASIS_4[u * 4 + z] = c(u) cos(pi u (2z + 1) / 8)}.
     */
    private static final float[] BASIS_4 = new float[16];

    static {
        for (var k = 0; k < 8; k++) {
            var scale = k == 0 ? 0.5 / StrictMath.sqrt(2) : 0.5;
            for (var n = 0; n < 8; n++) {
                BASIS_8[k * 8 + n] = (float) (scale * StrictMath.cos(StrictMath.PI * k * (2 * n + 1) / 16));
            }
        }
        for (var u = 0; u < 4; u++) {
            var scale = u == 0 ? 0.5 / StrictMath.sqrt(2) : 0.5;
            for (var z = 0; z < 4; z++) {
                BASIS_4[u * 4 + z] = (float) (scale * StrictMath.cos(StrictMath.PI * u * (2 * z + 1) / 8));
            }
        }
    }

    /**
     * Prevents instantiation.
     */
    private DvTransform() {
        throw new AssertionError();
    }

    /**
     * Replaces the coefficients of a block with its inverse transform.
     *
     * @param block the coefficients on entry, the samples on return; 64 values from {@code offset}
     * @param offset the index of the first value
     * @param fields whether the block is in the 2-4-8 mode
     * @param scratch a work array of at least 64 values
     */
    public static void inverse(float[] block, int offset, boolean fields, float[] scratch) {
        for (var v = 0; v < 8; v++) {
            var row = offset + v * 8;
            for (var x = 0; x < 8; x++) {
                var sum = 0.0f;
                for (var h = 0; h < 8; h++) {
                    sum += block[row + h] * BASIS_8[h * 8 + x];
                }
                scratch[v * 8 + x] = sum;
            }
        }
        if (!fields) {
            for (var y = 0; y < 8; y++) {
                for (var x = 0; x < 8; x++) {
                    var sum = 0.0f;
                    for (var v = 0; v < 8; v++) {
                        sum += scratch[v * 8 + x] * BASIS_8[v * 8 + y];
                    }
                    block[offset + y * 8 + x] = sum;
                }
            }
            return;
        }
        for (var z = 0; z < 4; z++) {
            for (var x = 0; x < 8; x++) {
                var sum = 0.0f;
                var difference = 0.0f;
                for (var u = 0; u < 4; u++) {
                    var basis = BASIS_4[u * 4 + z];
                    sum += scratch[u * 8 + x] * basis;
                    difference += scratch[(u + 4) * 8 + x] * basis;
                }
                block[offset + 2 * z * 8 + x] = sum + difference;
                block[offset + (2 * z + 1) * 8 + x] = sum - difference;
            }
        }
    }

    /**
     * Replaces the samples of a block with its forward transform.
     *
     * @param block the samples on entry, the coefficients on return; 64 values from {@code offset}
     * @param offset the index of the first value
     * @param fields whether to transform in the 2-4-8 mode
     * @param scratch a work array of at least 64 values
     */
    public static void forward(float[] block, int offset, boolean fields, float[] scratch) {
        for (var y = 0; y < 8; y++) {
            var row = offset + y * 8;
            for (var h = 0; h < 8; h++) {
                var sum = 0.0f;
                for (var x = 0; x < 8; x++) {
                    sum += block[row + x] * BASIS_8[h * 8 + x];
                }
                scratch[y * 8 + h] = sum;
            }
        }
        if (!fields) {
            for (var v = 0; v < 8; v++) {
                for (var h = 0; h < 8; h++) {
                    var sum = 0.0f;
                    for (var y = 0; y < 8; y++) {
                        sum += scratch[y * 8 + h] * BASIS_8[v * 8 + y];
                    }
                    block[offset + v * 8 + h] = sum;
                }
            }
            return;
        }
        for (var u = 0; u < 4; u++) {
            for (var h = 0; h < 8; h++) {
                var sum = 0.0f;
                var difference = 0.0f;
                for (var z = 0; z < 4; z++) {
                    var basis = BASIS_4[u * 4 + z];
                    var even = scratch[2 * z * 8 + h];
                    var odd = scratch[(2 * z + 1) * 8 + h];
                    sum += (even + odd) * basis;
                    difference += (even - odd) * basis;
                }
                block[offset + u * 8 + h] = sum;
                block[offset + (u + 4) * 8 + h] = difference;
            }
        }
    }
}
