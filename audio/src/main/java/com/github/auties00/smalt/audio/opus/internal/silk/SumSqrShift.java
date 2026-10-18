/* Ports libopus 1.5.2 (silk/sum_sqr_shift.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;


/**
 * Computes the energy of a 16-bit vector together with the right shift that keeps it within a
 * signed 32-bit integer with two bits of headroom.
 */
public final class SumSqrShift {
    private SumSqrShift() {
        throw new AssertionError();
    }

    /**
     * Computes the shifted energy of a vector ({@code silk_sum_sqr_shift}).
     *
     * <p>The two outputs are returned packed: the energy in the low 32 bits and the shift in the
     * high 32 bits; use {@link #energy(long)} and {@link #shift(long)} to unpack them.
     *
     * @param x   the vector
     * @param off the index of the first sample
     * @param len the number of samples
     * @return the packed energy and shift
     */
    public static long sumSqrShift(short[] x, int off, int len) {
        var shft = 31 - Integer.numberOfLeadingZeros(len);
        var nrg = len;
        int i;
        for (i = 0; i < len - 1; i += 2) {
            var nrg_tmp = x[off + i] * x[off + i];
            nrg_tmp = nrg_tmp + x[off + i + 1] * x[off + i + 1];
            nrg = nrg + (nrg_tmp >>> shft);
        }
        if (i < len) {
            var nrg_tmp = x[off + i] * x[off + i];
            nrg = nrg + (nrg_tmp >>> shft);
        }
        shft = Math.max(0, shft + 3 - Integer.numberOfLeadingZeros(nrg));
        nrg = 0;
        for (i = 0; i < len - 1; i += 2) {
            var nrg_tmp = x[off + i] * x[off + i];
            nrg_tmp = nrg_tmp + x[off + i + 1] * x[off + i + 1];
            nrg = nrg + (nrg_tmp >>> shft);
        }
        if (i < len) {
            var nrg_tmp = x[off + i] * x[off + i];
            nrg = nrg + (nrg_tmp >>> shft);
        }
        return ((long) shft << 32) | (nrg & 0xFFFFFFFFL);
    }

    /**
     * Extracts the energy from the result of {@link #sumSqrShift(short[], int, int)}.
     *
     * @param packed the packed result
     * @return the shifted energy
     */
    public static int energy(long packed) {
        return (int) packed;
    }

    /**
     * Extracts the shift from the result of {@link #sumSqrShift(short[], int, int)}.
     *
     * @param packed the packed result
     * @return the right shift applied to the energy
     */
    public static int shift(long packed) {
        return (int) (packed >>> 32);
    }
}
