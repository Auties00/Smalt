package com.github.auties00.smalt.audio.amr.internal;

/**
 * Holds the hot integer loops of the AMR-NB and AMR-WB codecs that are plain wrapping 32-bit sums of 16-bit
 * products, whose result does not depend on the order of the additions.
 *
 * <p>Implementations must compute every output with two's complement 32-bit arithmetic and no saturation, so that
 * every implementation returns the same values. Arguments are not checked; values outside the documented ranges
 * give unspecified results.
 */
public interface AmrKernel {
    /**
     * Computes a finite impulse response with the taps running forward through the signal:
     * {@code out[outOffset + n] = sum over i of x[xOffset + n + i] * c[i]} for {@code n} below
     * {@code count} and {@code i} below {@code taps}.
     *
     * @param x the signal; it must hold {@code count + taps - 1} samples from {@code xOffset}
     * @param xOffset the index of the first sample
     * @param c the coefficients
     * @param taps the number of coefficients
     * @param count the number of outputs
     * @param out receives the outputs
     * @param outOffset the index of the first output
     */
    void fir(short[] x, int xOffset, int[] c, int taps, int count, int[] out, int outOffset);

    /**
     * Computes a finite impulse response with the taps running backward through the signal:
     * {@code out[outOffset + n] = sum over i of x[xOffset + n - i] * c[i]} for {@code n} below
     * {@code count} and {@code i} below {@code taps}.
     *
     * @param x the signal; it must hold {@code taps - 1} samples before {@code xOffset}
     * and {@code count} from it
     * @param xOffset the index of the newest sample of the first output
     * @param c the coefficients
     * @param taps the number of coefficients
     * @param count the number of outputs
     * @param out receives the outputs
     * @param outOffset the index of the first output
     */
    void firReverse(short[] x, int xOffset, int[] c, int taps, int count, int[] out, int outOffset);

    /**
     * Scores the candidate position pairs of one stage of the AMR-NB 12.2 kbit/s algebraic
     * codebook search and keeps, for each of the eight positions of the second track, the best
     * candidate over the eight positions of the first track.
     *
     * <p>With {@code j} the position of the first track and {@code t} that of the second, a
     * candidate has {@code ps2 = (short) (psj[j] + ta[t])} and
     * {@code sq = (short) ((ps2 * ps2) >> 15)}. Its energy is, for {@code kind} 0,
     * {@code alp = (((alp1[j] + rr) >> 2) + tb[t]) >> 1} with {@code rr = block[8 j + t]}; for
     * {@code kind} 1, 2 and 3 it is {@code (short) ((alp1[j] + (rr << r) + (tb[t] << s)) >> 16)} with
     * {@code (r, s)} equal to {@code (12, 14)}, {@code (11, 14)} and {@code (10, 13)}. A candidate
     * beats the best one so far, which starts at {@code sq = -1, alp = 1}, when
     * {@code sq * bestAlp > bestSq * alp}, and the first of equal candidates stays.
     *
     * <p>The result is written to {@code out}: the best {@code sq} of each lane at
     * {@code out[t]}, its {@code alp} at {@code out[8 + t]}, its {@code j} at {@code out[16 + t]},
     * and at {@code out[24 + t]} a nonzero value when some candidate of the lane has an energy that is
     * not positive (or, for {@code kind} 0, above 32767), in which case the lanes are not the best
     * candidates of a sequential search and must not be used.
     *
     * @param kind the stage kind, 0 to 3
     * @param block the correlations {@code rr} of the 64 candidates, row {@code j} of eight
     * @param alp1 the energy of the pulses chosen so far plus the first pulse of each row, 8 values
     * @param psj the correlation of the first pulse of each row, 8 values
     * @param ta the correlation of the second pulse of each column with the pulses so far, 8 values
     * @param tb the energy term of the second pulse of each column, 8 values
     * @param out receives 32 values
     */
    void pairSearch(int kind, short[] block, int[] alp1, short[] psj, short[] ta, short[] tb, int[] out);
}
