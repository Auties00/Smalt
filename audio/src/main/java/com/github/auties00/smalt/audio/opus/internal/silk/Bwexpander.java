/* Ports libopus 1.5.2 (silk/bwexpander.c, silk/bwexpander_32.c). Copyright Skype Limited; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rshiftRound;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulww;

/**
 * Applies bandwidth expansion (chirping) to the coefficients of an AR filter.
 */
public final class Bwexpander {
    private Bwexpander() {
        throw new AssertionError();
    }

    /**
     * Chirps 16-bit AR filter coefficients in place ({@code silk_bwexpander}).
     *
     * <p>Rounded multiplications are used instead of {@code silk_SMULWB}, whose bias could make
     * the filter unstable.
     *
     * @param ar        the coefficients, without the leading one
     * @param d         the number of coefficients
     * @param chirp_Q16 the chirp factor in Q16, typically below one
     */
    public static void bwexpander(short[] ar, int d, int chirp_Q16) {
        bwexpander(ar, 0, d, chirp_Q16);
    }

    /**
     * Chirps 16-bit AR filter coefficients starting at an offset in place
     * ({@code silk_bwexpander}).
     *
     * @param ar        the array holding the coefficients
     * @param off       the index of the first coefficient
     * @param d         the number of coefficients
     * @param chirp_Q16 the chirp factor in Q16
     */
    public static void bwexpander(short[] ar, int off, int d, int chirp_Q16) {
        var chirp_minus_one_Q16 = chirp_Q16 - 65536;
        for (var i = 0; i < d - 1; i++) {
            ar[off + i] = (short) rshiftRound(chirp_Q16 * ar[off + i], 16);
            chirp_Q16 += rshiftRound(chirp_Q16 * chirp_minus_one_Q16, 16);
        }
        ar[off + d - 1] = (short) rshiftRound(chirp_Q16 * ar[off + d - 1], 16);
    }

    /**
     * Chirps 32-bit AR filter coefficients in place ({@code silk_bwexpander_32}).
     *
     * @param ar        the coefficients, without the leading one
     * @param d         the number of coefficients
     * @param chirp_Q16 the chirp factor in Q16
     */
    public static void bwexpander32(int[] ar, int d, int chirp_Q16) {
        var chirp_minus_one_Q16 = chirp_Q16 - 65536;
        for (var i = 0; i < d - 1; i++) {
            ar[i] = smulww(chirp_Q16, ar[i]);
            chirp_Q16 += rshiftRound(chirp_Q16 * chirp_minus_one_Q16, 16);
        }
        ar[d - 1] = smulww(chirp_Q16, ar[d - 1]);
    }
}
