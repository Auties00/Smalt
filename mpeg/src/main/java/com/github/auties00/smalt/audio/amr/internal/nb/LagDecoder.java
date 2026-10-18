/* Ports opencore-amr 0.1.6 (dec_lag3.cpp, dec_lag6.cpp). Copyright PacketVideo; Apache License 2.0. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.sub;

/**
 * Decodes the adaptive codebook (pitch) lags of AMR-NB from their indices.
 *
 * <p>Both decoders return the integer lag in the upper 16 bits and the fractional part in the
 * lower 16 bits of an {@code int}.
 */
final class LagDecoder {
    /**
     * Prevents instantiation.
     */
    private LagDecoder() {
    }

    /**
     * Decodes a lag of 1/3 resolution ({@code Dec_lag3}).
     *
     * @param index the received index
     * @param t0Min the lower bound of the relative search range
     * @param t0Max the upper bound of the relative search range
     * @param iSubfr 0 for an absolutely coded lag, nonzero for a relative one
     * @param t0Prev the integer lag of the previous subframe
     * @param flag4 whether the relative lag uses 4 bits (the low rate modes)
     * @param overflow the flag raised on saturation
     * @return the integer lag (upper 16 bits) and the fraction (lower 16 bits)
     */
    static int decLag3(short index, short t0Min, short t0Max, int iSubfr, short t0Prev, boolean flag4, Flag overflow) {
        short i;
        short tmpLag;
        short t0;
        short frac;
        if (iSubfr == 0) {
            if (index < 197) {
                tmpLag = (short) (index + 2);
                tmpLag = mult(tmpLag, (short) 10923, overflow);
                i = (short) (tmpLag + 19);
                t0 = i;
                i = (short) (i << 1);
                i = (short) (i + t0);
                tmpLag = (short) (index - i);
                frac = (short) (tmpLag + 58);
            } else {
                t0 = (short) (index - 112);
                frac = 0;
            }
        } else if (!flag4) {
            i = (short) (index + 2);
            i = (short) ((i * 10923) >> 15);
            i -= 1;
            t0 = (short) (i + t0Min);
            i = (short) (i + (i << 1));
            tmpLag = (short) (index - 2);
            frac = (short) (tmpLag - i);
        } else {
            tmpLag = t0Prev;
            i = sub(tmpLag, t0Min, overflow);
            if (i > 5) {
                tmpLag = (short) (t0Min + 5);
            }
            i = (short) (t0Max - tmpLag);
            if (i > 4) {
                tmpLag = (short) (t0Max - 4);
            }
            if (index < 4) {
                i = (short) (tmpLag - 5);
                t0 = (short) (i + index);
                frac = 0;
            } else if (index < 12) {
                i = (short) (index - 5);
                i = (short) ((i * 10923) >> 15);
                i--;
                t0 = (short) (i + tmpLag);
                i = (short) (i + (i << 1));
                tmpLag = (short) (index - 9);
                frac = (short) (tmpLag - i);
            } else {
                i = (short) (index - 12);
                i = (short) (i + tmpLag);
                t0 = (short) (i + 1);
                frac = 0;
            }
        }
        return (t0 << 16) | (frac & 0xffff);
    }

    /**
     * Decodes a lag of 1/6 resolution, the {@code MR122} lag ({@code Dec_lag6}).
     *
     * @param index the received index
     * @param pitMin the smallest lag
     * @param pitMax the largest lag
     * @param iSubfr 0 for an absolutely coded lag, nonzero for a relative one
     * @param t0 the integer lag of the previous subframe, the center of a relative range
     * @return the integer lag (upper 16 bits) and the fraction (lower 16 bits)
     */
    static int decLag6(short index, short pitMin, short pitMax, int iSubfr, short t0) {
        short i;
        short frac;
        if (iSubfr == 0) {
            if (index < 463) {
                i = (short) (index + 5);
                i = (short) ((i * 5462) >> 15);
                i += 17;
                t0 = i;
                i = (short) (i << 1);
                i = (short) (i + t0);
                i = (short) (i << 1);
                i = (short) (index - i);
                frac = (short) (i + 105);
            } else {
                t0 = (short) (index - 368);
                frac = 0;
            }
        } else {
            var t0Min = (short) (t0 - 5);
            if (t0Min < pitMin) {
                t0Min = pitMin;
            }
            var t0Max = (short) (t0Min + 9);
            if (t0Max > pitMax) {
                t0Max = pitMax;
                t0Min = (short) (t0Max - 9);
            }
            i = (short) (index + 5);
            i = (short) ((i * 5462) >> 15);
            i -= 1;
            t0 = (short) (i + t0Min);
            i = (short) (i + (i << 1));
            i = (short) (i << 1);
            var k = (short) (index - 3);
            frac = (short) (k - i);
        }
        return (t0 << 16) | (frac & 0xffff);
    }
}
