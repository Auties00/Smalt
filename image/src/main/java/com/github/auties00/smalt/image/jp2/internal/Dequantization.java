package com.github.auties00.smalt.image.jp2.internal;

/**
 * Dequantizes the code-blocks of the irreversible quantizers of ISO/IEC 15444-2: variable deadzone scalar
 * quantization (annex C) and trellis coded quantization (annex D).
 *
 * <p>The decoded coefficients come with one extra fractional bit, as the block decoders produce them: a coefficient
 * whose quantization index {@code q} was decoded down to its {@code N_b}-th bit-plane holds
 * {@code 2 q + 2^(M_b - N_b)}, with the sign of {@code q}, which is twice {@code q + r 2^(M_b - N_b)} of ISO/IEC
 * 15444-1 equation E-6 for {@code r = 1/2}.
 */
final class Dequantization {
    /**
     * The reconstruction offset of the full trellis coded dequantization, in step sizes, applied to indices of
     * magnitude at most {@link #TRELLIS_SMALL_INDEX} (the setting ISO/IEC 15444-2 D.4.1 suggests).
     */
    static final float TRELLIS_OFFSET = 0.125f;

    /**
     * The largest index magnitude {@link #TRELLIS_OFFSET} applies to.
     */
    static final int TRELLIS_SMALL_INDEX = 2;

    /**
     * The trellis state that follows each state for an even and an odd index, two entries per state (Table D.5).
     */
    private static final int[] NEXT_STATE = {0, 4, 4, 0, 1, 5, 5, 1, 6, 2, 2, 6, 7, 3, 3, 7};

    /**
     * The bit of each trellis state set when the state uses the union quantizer {@code A1} rather than {@code A0}
     * (Table D.4).
     */
    private static final int A1_STATES = 0b1100_1100;

    private Dequantization() {
    }

    /**
     * Dequantizes a code-block coded with a variable deadzone (ISO/IEC 15444-2 equation C-3).
     *
     * @implNote This implementation reconstructs negative indices as the negation of positive ones,
     * {@code (q - r 2^(M_b - N_b) + nz_b) Delta_b}: the quantizer of equation C-4 is symmetric, so its interval for a
     * negative index is the mirror of the positive one, whereas equation C-3 as printed subtracts {@code nz_b} on both
     * sides.
     * @param decoded the decoded coefficients, row-major, with one extra fractional bit
     * @param width the code-block width
     * @param height the code-block height
     * @param deadzone the deadzone adjustment {@code nz_b}
     * @param stepSize the step size {@code Delta_b}
     * @param target the tile-component buffer
     * @param offset the index in {@code target} of the code-block's first coefficient
     * @param stride the row stride of {@code target}
     */
    static void deadzone(long[] decoded, int width, int height, float deadzone, float stepSize, float[] target,
                         int offset, int stride) {
        for (var j = 0; j < height; j++) {
            var row = offset + j * stride;
            var source = j * width;
            for (var i = 0; i < width; i++) {
                var value = decoded[source + i];
                if (value == 0) {
                    target[row + i] = 0;
                    continue;
                }
                var magnitude = ((float) Math.abs(value) * 0.5f - deadzone) * stepSize;
                target[row + i] = value < 0 ? -magnitude : magnitude;
            }
        }
    }

    /**
     * Dequantizes a trellis coded code-block (ISO/IEC 15444-2 D.4): with the full dequantization of D.4.1 when every
     * index was decoded whole, and with the approximate one of D.4.2 otherwise.
     *
     * @param decoded the decoded coefficients, row-major, with one extra fractional bit
     * @param width the code-block width
     * @param height the code-block height
     * @param complete whether the cleanup pass of the least significant bit-plane was decoded
     * @param stepSize the step size of equation D-2, twice that of equation D-1
     * @param target the tile-component buffer
     * @param offset the index in {@code target} of the code-block's first coefficient
     * @param stride the row stride of {@code target}
     */
    static void trellis(long[] decoded, int width, int height, boolean complete, float stepSize, float[] target,
                        int offset, int stride) {
        if (complete) {
            fullTrellis(decoded, width, height, 0.5f * stepSize, target, offset, stride);
        } else {
            approximateTrellis(decoded, width, height, stepSize, target, offset, stride);
        }
    }

    /**
     * Reconstructs the indices of a code-block along the trellis, in the scan order of ISO/IEC 15444-1 D.1: stripes
     * of four rows from the top, each scanned column by column from the left and each column from the top (Tables D.3
     * to D.5).
     *
     * @param decoded the decoded coefficients, row-major, with one extra fractional bit
     * @param width the code-block width
     * @param height the code-block height
     * @param step the step size of equation D-1
     * @param target the tile-component buffer
     * @param offset the index in {@code target} of the code-block's first coefficient
     * @param stride the row stride of {@code target}
     */
    private static void fullTrellis(long[] decoded, int width, int height, float step, float[] target, int offset,
                                    int stride) {
        var state = 0;
        for (var stripe = 0; stripe < height; stripe += 4) {
            var rows = Math.min(4, height - stripe);
            for (var i = 0; i < width; i++) {
                for (var j = stripe; j < stripe + rows; j++) {
                    var value = decoded[j * width + i];
                    var index = value < 0 ? -(-value >> 1) : value >> 1;
                    var union1 = (A1_STATES >> state & 1) != 0;
                    if (union1 && (index == 1 || index == -1)) {
                        index = -index;
                    }
                    long level;
                    if (index == 0) {
                        level = 0;
                    } else if (union1) {
                        level = index > 0 ? 2 * index : 2 * index + 1;
                    } else {
                        level = index > 0 ? 2 * index - 1 : 2 * index;
                    }
                    var reconstruction = (float) level;
                    if (level != 0 && Math.abs(index) <= TRELLIS_SMALL_INDEX) {
                        reconstruction += level > 0 ? -TRELLIS_OFFSET : TRELLIS_OFFSET;
                    }
                    target[offset + j * stride + i] = reconstruction * step;
                    state = NEXT_STATE[2 * state + (int) (index & 1)];
                }
            }
        }
    }

    /**
     * Reconstructs the indices of a code-block as scalar indices with their least significant bit cleared (Table
     * D.6).
     *
     * @param decoded the decoded coefficients, row-major, with one extra fractional bit
     * @param width the code-block width
     * @param height the code-block height
     * @param stepSize the step size of equation D-2
     * @param target the tile-component buffer
     * @param offset the index in {@code target} of the code-block's first coefficient
     * @param stride the row stride of {@code target}
     */
    private static void approximateTrellis(long[] decoded, int width, int height, float stepSize, float[] target,
                                           int offset, int stride) {
        var step = 0.5f * stepSize;
        for (var j = 0; j < height; j++) {
            var row = offset + j * stride;
            var source = j * width;
            for (var i = 0; i < width; i++) {
                var value = decoded[source + i];
                var magnitude = Math.abs(value);
                if ((magnitude & 1) != 0) {
                    magnitude = magnitude < 4 ? 0 : magnitude & ~2L;
                }
                var reconstruction = (float) magnitude * step;
                target[row + i] = value < 0 ? -reconstruction : reconstruction;
            }
        }
    }
}
