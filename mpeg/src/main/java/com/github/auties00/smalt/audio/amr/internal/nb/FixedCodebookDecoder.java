/* Ports opencore-amr 0.1.6 (d2_9pf.cpp, d2_11pf.cpp, d3_14pf.cpp, d4_17pf.cpp, d8_31pf.cpp, d1035pf.cpp). Copyright
   PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.add;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.negate;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shl;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_CODE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_SUBFR;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.NB_TRACK;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.NB_TRACK_MR102;

/**
 * Builds the algebraic (fixed) codebook vector of one AMR-NB subframe from its received pulse
 * positions and signs, for every mode.
 *
 * <p>Every position a received index can produce lies inside the 40-sample subframe, so no input
 * can address outside the vector.
 */
final class FixedCodebookDecoder {
    /**
     * The pulse amplitude of the 10.2 kbit/s codebook.
     */
    private static final short POS_CODE = 8191;

    /**
     * Prevents instantiation.
     */
    private FixedCodebookDecoder() {
    }

    /**
     * Decodes the 2 pulses of a 9-bit codebook, {@code MR475} and {@code MR515}
     * ({@code decode_2i40_9bits}).
     *
     * @param subNr the subframe number, 0 to 3
     * @param sign the 2 sign bits
     * @param index the position index
     * @param cod receives the 40 samples
     * @param overflow the flag raised on saturation
     */
    static void decode2i40_9bits(short subNr, short sign, short index, short[] cod, Flag overflow) {
        var j = (short) (index & 64);
        j >>= 3;
        var i = (short) (index & 7);
        var k = shl(subNr, (short) 1);
        k += j;
        var pos0 = (short) (i * 5 + CommonTables.START_POS[k++]);
        index >>= 3;
        i = (short) (index & 7);
        var pos1 = (short) (i * 5 + CommonTables.START_POS[k]);
        for (var n = L_SUBFR - 1; n >= 0; n--) {
            cod[n] = 0;
        }
        cod[pos0] = (short) ((sign & 1) * 16383 - 8192);
        sign >>= 1;
        cod[pos1] = (short) ((sign & 1) * 16383 - 8192);
    }

    /**
     * Decodes the 2 pulses of an 11-bit codebook, {@code MR59} ({@code decode_2i40_11bits}).
     *
     * @param sign the 2 sign bits
     * @param index the position index
     * @param cod receives the 40 samples
     */
    static void decode2i40_11bits(short sign, short index, short[] cod) {
        var j = (short) (index & 0x1);
        index >>= 1;
        var i = (short) (index & 0x7);
        var pos0 = (short) (i * 5 + j * 2 + 1);
        index >>= 3;
        j = (short) (index & 0x3);
        index >>= 2;
        i = (short) (index & 0x7);
        var pos1 = j == 3 ? (short) (i * 5 + 4) : (short) (i * 5 + j);
        for (var n = 0; n < L_SUBFR; n++) {
            cod[n] = 0;
        }
        cod[pos0] = (short) ((sign & 1) * 16383 - 8192);
        sign >>= 1;
        cod[pos1] = (short) ((sign & 1) * 16383 - 8192);
    }

    /**
     * Decodes the 3 pulses of a 14-bit codebook, {@code MR67} ({@code decode_3i40_14bits}).
     *
     * @param sign the 3 sign bits
     * @param index the position index
     * @param cod receives the 40 samples
     */
    static void decode3i40_14bits(short sign, short index, short[] cod) {
        var i = (short) (index & 0x7);
        var pos0 = (short) (i * 5);
        index >>= 3;
        var j = (short) (index & 0x1);
        index >>= 1;
        i = (short) (index & 0x7);
        var pos1 = (short) (i * 5 + j * 2 + 1);
        index >>= 3;
        j = (short) (index & 0x1);
        index >>= 1;
        i = (short) (index & 0x7);
        var pos2 = (short) (i * 5 + j * 2 + 2);
        for (var n = 0; n < L_SUBFR; n++) {
            cod[n] = 0;
        }
        cod[pos0] = (short) ((sign & 1) * 16383 - 8192);
        sign >>= 1;
        cod[pos1] = (short) ((sign & 1) * 16383 - 8192);
        sign >>= 1;
        cod[pos2] = (short) ((sign & 1) * 16383 - 8192);
    }

    /**
     * Decodes the 4 pulses of a 17-bit codebook, {@code MR74} and {@code MR795}
     * ({@code decode_4i40_17bits}).
     *
     * @param sign the 4 sign bits
     * @param index the position index
     * @param cod receives the 40 samples
     */
    static void decode4i40_17bits(short sign, short index, short[] cod) {
        var dgray = CommonTables.DGRAY;
        var i = dgray[index & 0x7];
        var pos0 = (short) (i * 5);
        index >>= 3;
        i = dgray[index & 0x7];
        var pos1 = (short) (i * 5 + 1);
        index >>= 3;
        i = dgray[index & 0x7];
        var pos2 = (short) (i * 5 + 2);
        index >>= 3;
        var j = (short) (index & 0x1);
        index >>= 1;
        i = dgray[index & 0x7];
        var pos3 = (short) (i * 5 + 3 + j);
        for (var n = 0; n < L_SUBFR; n++) {
            cod[n] = 0;
        }
        cod[pos0] = (short) ((sign & 1) * 16383 - 8192);
        sign >>= 1;
        cod[pos1] = (short) ((sign & 1) * 16383 - 8192);
        sign >>= 1;
        cod[pos2] = (short) ((sign & 1) * 16383 - 8192);
        sign >>= 1;
        cod[pos3] = (short) ((sign & 1) * 16383 - 8192);
    }

    /**
     * Decodes the 10 pulses of the 35-bit codebook, {@code MR122} ({@code dec_10i40_35bits}).
     *
     * @param index the 10 indices
     * @param indexOffset the index of the first index
     * @param cod receives the 40 samples
     */
    static void dec10i40_35bits(short[] index, int indexOffset, short[] cod) {
        var dgray = CommonTables.DGRAY;
        for (var n = 0; n < L_CODE; n++) {
            cod[n] = 0;
        }
        for (var j = 0; j < NB_TRACK; j++) {
            var tmp = index[indexOffset + j];
            var i = dgray[tmp & 7];
            i = (short) (i * 5);
            var pos1 = (short) (i + j);
            short sign = ((tmp >> 3) & 1) == 0 ? (short) 4096 : (short) -4096;
            cod[pos1] = sign;
            i = dgray[index[indexOffset + j + 5] & 7];
            i = (short) (i * 5);
            var pos2 = (short) (i + j);
            if (pos2 < pos1) {
                sign = negate(sign);
            }
            cod[pos2] = (short) (cod[pos2] + sign);
        }
    }

    /**
     * Decodes the 8 pulses of the 31-bit codebook, {@code MR102} ({@code dec_8i40_31bits}).
     *
     * @param index the 7 indices: 4 signs and 3 compressed position words
     * @param indexOffset the index of the first index
     * @param cod receives the 40 samples
     * @param positions scratch for the 8 decompressed positions
     * @param overflow the flag raised on saturation
     */
    static void dec8i40_31bits(short[] index, int indexOffset, short[] cod, short[] positions, Flag overflow) {
        for (var n = 0; n < L_CODE; n++) {
            cod[n] = 0;
        }
        var msbs = (short) (index[indexOffset + NB_TRACK_MR102] >> 3);
        var lsbs = (short) (index[indexOffset + NB_TRACK_MR102] & 0x7);
        decompress10(msbs, lsbs, 0, 4, 1, positions, overflow);
        msbs = (short) (index[indexOffset + NB_TRACK_MR102 + 1] >> 3);
        lsbs = (short) (index[indexOffset + NB_TRACK_MR102 + 1] & 0x7);
        decompress10(msbs, lsbs, 2, 6, 5, positions, overflow);
        msbs = (short) (index[indexOffset + NB_TRACK_MR102 + 2] >> 2);
        lsbs = (short) (index[indexOffset + NB_TRACK_MR102 + 2] & 0x3);
        var temp = lMult(msbs, (short) 25, overflow);
        var ia = (short) lShr(temp, (short) 1);
        ia += 12;
        var msbs024 = (short) (ia >> 5);
        ia = mult(msbs024, (short) 6554, overflow);
        ia &= 1;
        var ib = mult(msbs024, (short) 6554, overflow);
        temp = lMult(ib, (short) 5, overflow);
        ib = (short) (msbs024 - (short) (temp >> 1));
        if (ia == 1) {
            ib = (short) (4 - ib);
        }
        ib = shl(ib, (short) 1);
        ia = (short) (lsbs & 0x1);
        positions[3] = add(ib, ia, overflow);
        ia = mult(msbs024, (short) 6554, overflow);
        ia = shl(ia, (short) 1);
        positions[7] = (short) (ia + (lsbs >> 1));
        for (var j = 0; j < NB_TRACK_MR102; j++) {
            var pos1 = (short) ((positions[j] << 2) + j);
            var sign = index[indexOffset + j] == 0 ? POS_CODE : (short) -POS_CODE;
            if (pos1 < L_SUBFR) {
                cod[pos1] = sign;
            }
            var pos2 = (short) ((positions[j + 4] << 2) + j);
            if (pos2 < pos1) {
                sign = negate(sign);
            }
            if (pos2 < L_SUBFR) {
                cod[pos2] = (short) (cod[pos2] + sign);
            }
        }
    }

    /**
     * Decompresses 3 pulse positions from a 10-bit word ({@code decompress10}).
     *
     * @param msbs the 7 most significant bits
     * @param lsbs the 3 least significant bits
     * @param index1 the position slot of the first pulse
     * @param index2 the position slot of the second pulse
     * @param index3 the position slot of the third pulse
     * @param positions receives the positions
     * @param overflow the flag raised on saturation
     */
    private static void decompress10(short msbs, short lsbs, int index1, int index2, int index3, short[] positions,
                                     Flag overflow) {
        if (msbs > 124) {
            msbs = 124;
        }
        var ia = mult(msbs, (short) 1311, overflow);
        var temp = lMult(ia, (short) 25, overflow);
        ia = (short) (msbs - (temp >> 1));
        var ib = mult(ia, (short) 6554, overflow);
        temp = lMult(ib, (short) 5, overflow);
        ib = (short) (ia - (short) (temp >> 1));
        ib = shl(ib, (short) 1);
        var ic = (short) (lsbs - ((lsbs >> 2) << 2));
        positions[index1] = (short) (ib + (ic & 1));
        ib = mult(ia, (short) 6554, overflow);
        ib = shl(ib, (short) 1);
        positions[index2] = (short) (ib + (ic >> 1));
        ib = (short) (lsbs >> 2);
        ic = mult(msbs, (short) 1311, overflow);
        ic = shl(ic, (short) 1);
        positions[index3] = add(ib, ic, overflow);
    }
}
