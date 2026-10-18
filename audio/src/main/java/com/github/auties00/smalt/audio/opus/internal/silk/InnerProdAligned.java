/* Ports libopus 1.5.2 (silk/inner_prod_aligned.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt.
   */
package com.github.auties00.smalt.audio.opus.internal.silk;

/**
 * Computes inner products of 16-bit vectors with a per-term right shift.
 */
public final class InnerProdAligned {
    private InnerProdAligned() {
        throw new AssertionError();
    }

    /**
     * Returns the inner product of two vectors with each product shifted right before
     * accumulation ({@code silk_inner_prod_aligned_scale}).
     *
     * @param inVec1    the first vector
     * @param inVec1Off the index of the first element of {@code inVec1}
     * @param inVec2    the second vector
     * @param inVec2Off the index of the first element of {@code inVec2}
     * @param scale     the right shift applied to each product
     * @param len       the number of elements
     * @return the scaled inner product, wrapped to 32 bits
     */
    public static int innerProdAlignedScale(short[] inVec1, int inVec1Off, short[] inVec2, int inVec2Off, int scale,
                                            int len) {
        var sum = 0;
        for (var i = 0; i < len; i++) {
            sum = sum + ((inVec1[inVec1Off + i] * inVec2[inVec2Off + i]) >> scale);
        }
        return sum;
    }
}
