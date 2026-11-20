/* Ports JM, the ITU-T / ISO/IEC H.264 reference software (app/lencod/lambda.c: the rate-distortion multipliers).
   Copyright ITU-T and ISO/IEC; royalty-free license with notice. Modified for Smalt. */
package com.github.auties00.smalt.video.h264.encoder.internal;

/**
 * Holds the Lagrange multipliers that weigh bits against distortion in every decision.
 *
 * <p>For decisions measured with the sum of squared differences the multiplier is
 * {@code 0.85 * 2^((qp - 12) / 3)}; for decisions measured with SAD or SATD it is the square root
 * of that. Both are stored multiplied by 256.
 *
 * @implNote This implementation uses the multipliers of the ITU-T JM reference encoder, derived in
 * Sullivan and Wiegand, "Rate-distortion optimization for video compression", IEEE Signal
 * Processing Magazine, 1998.
 */
final class Lambda {
    /**
     * The SAD and SATD multiplier by quantizer, times 256.
     */
    static final int[] SAD = new int[52];

    /**
     * The squared-error multiplier by quantizer, times 256.
     */
    static final int[] SSD = new int[52];

    static {
        for (var qp = 0; qp < 52; qp++) {
            var ssd = 0.85 * StrictMath.pow(2, (qp - 12) / 3.0);
            SSD[qp] = (int) Math.round(256 * ssd);
            SAD[qp] = Math.max(1, (int) Math.round(256 * Math.sqrt(ssd)));
        }
    }

    private Lambda() {
        throw new AssertionError();
    }
}
