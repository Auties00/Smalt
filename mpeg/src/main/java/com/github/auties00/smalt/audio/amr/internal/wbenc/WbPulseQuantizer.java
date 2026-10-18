/* Ports vo-amrwbenc 0.1.3 (q_pulse.c). Copyright VisualOn; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wbenc;

import static com.github.auties00.smalt.audio.amr.internal.wbenc.WbBasicOps.*;

/**
 * Codes the positions and signs of the pulses of one track of the AMR-WB algebraic codebook into
 * an index (TS 26.190 section 5.8.2).
 *
 * <p>A position is a track-relative value whose bit {@code 2^N} (or 16 for the 4-bit tracks)
 * carries the sign. The scratch vectors of the 4-, 5- and 6-pulse coders are instance fields, so
 * coding allocates nothing; an instance is not thread-safe.
 */
final class WbPulseQuantizer {
    /**
     * The sign bit of a position on a 16-position track.
     */
    private static final int NB_POS = 16;

    /**
     * The positions of the 4-pulse coder without the sign split.
     */
    private final short[] posA4 = new short[4];

    /**
     * The positions of the 4-pulse coder with the sign split.
     */
    private final short[] posB4 = new short[4];

    /**
     * The positions of the 5-pulse coder without the sign split.
     */
    private final short[] posA5 = new short[5];

    /**
     * The positions of the 5-pulse coder with the sign split.
     */
    private final short[] posB5 = new short[5];

    /**
     * The positions of the 6-pulse coder without the sign split.
     */
    private final short[] posA6 = new short[6];

    /**
     * The positions of the 6-pulse coder with the sign split.
     */
    private final short[] posB6 = new short[6];

    /**
     * Creates the quantizer with its scratch vectors.
     */
    WbPulseQuantizer() {
    }

    /**
     * Codes 1 pulse with {@code N + 1} bits ({@code quant_1p_N1}).
     *
     * @param pos the position
     * @param n the number of position bits
     * @return the index
     */
    static int quant1pN1(int pos, int n) {
        var mask = (short) ((1 << n) - 1);
        var index = (int) (short) (pos & mask);
        if ((pos & NB_POS) != 0) {
            index = index + (1 << n);
        }
        return index;
    }

    /**
     * Codes 2 pulses with {@code 2N + 1} bits ({@code quant_2p_2N1}).
     *
     * @param pos1 the first position
     * @param pos2 the second position
     * @param n the number of position bits
     * @return the index
     */
    static int quant2p2N1(int pos1, int pos2, int n) {
        var mask = (short) ((1 << n) - 1);
        int index;
        if (((pos2 ^ pos1) & NB_POS) == 0) {
            if (pos1 <= pos2) {
                index = ((short) (pos1 & mask) << n) + (short) (pos2 & mask);
            } else {
                index = ((short) (pos2 & mask) << n) + (short) (pos1 & mask);
            }
            if ((pos1 & NB_POS) != 0) {
                index = index + (1 << (short) (n << 1));
            }
        } else {
            if ((short) (pos1 & mask) - (short) (pos2 & mask) <= 0) {
                index = ((short) (pos2 & mask) << n) + (short) (pos1 & mask);
                if ((pos2 & NB_POS) != 0) {
                    index = index + (1 << (short) (n << 1));
                }
            } else {
                index = ((short) (pos1 & mask) << n) + (short) (pos2 & mask);
                if ((pos1 & NB_POS) != 0) {
                    index = index + (1 << (short) (n << 1));
                }
            }
        }
        return index;
    }

    /**
     * Codes 3 pulses with {@code 3N + 1} bits ({@code quant_3p_3N1}).
     *
     * @param pos1 the first position
     * @param pos2 the second position
     * @param pos3 the third position
     * @param n the number of position bits
     * @return the index
     */
    static int quant3p3N1(int pos1, int pos2, int pos3, int n) {
        var nbPos = (short) (1 << (n - 1));
        int index;
        if (((pos1 ^ pos2) & nbPos) == 0) {
            index = quant2p2N1(pos1, pos2, n - 1);
            index = index + ((short) (pos1 & nbPos) << n);
            index = index + (quant1pN1(pos3, n) << (n << 1));
        } else if (((pos1 ^ pos3) & nbPos) == 0) {
            index = quant2p2N1(pos1, pos3, n - 1);
            index = index + ((short) (pos1 & nbPos) << n);
            index = index + (quant1pN1(pos2, n) << (n << 1));
        } else {
            index = quant2p2N1(pos2, pos3, n - 1);
            index = index + ((short) (pos2 & nbPos) << n);
            index = index + (quant1pN1(pos1, n) << (n << 1));
        }
        return index;
    }

    /**
     * Codes 4 pulses with {@code 4N + 1} bits ({@code quant_4p_4N1}).
     *
     * @param pos1 the first position
     * @param pos2 the second position
     * @param pos3 the third position
     * @param pos4 the fourth position
     * @param n the number of position bits
     * @return the index
     */
    static int quant4p4N1(int pos1, int pos2, int pos3, int pos4, int n) {
        var nbPos = (short) (1 << (n - 1));
        int index;
        if (((pos1 ^ pos2) & nbPos) == 0) {
            index = quant2p2N1(pos1, pos2, n - 1);
            index = index + ((short) (pos1 & nbPos) << n);
            index = index + (quant2p2N1(pos3, pos4, n) << (n << 1));
        } else if (((pos1 ^ pos3) & nbPos) == 0) {
            index = quant2p2N1(pos1, pos3, n - 1);
            index = index + ((short) (pos1 & nbPos) << n);
            index = index + (quant2p2N1(pos2, pos4, n) << (n << 1));
        } else {
            index = quant2p2N1(pos2, pos3, n - 1);
            index = index + ((short) (pos2 & nbPos) << n);
            index = index + (quant2p2N1(pos1, pos4, n) << (n << 1));
        }
        return index;
    }

    /**
     * Codes 4 pulses with {@code 4N} bits ({@code quant_4p_4N}).
     *
     * @param pos the array holding the 4 positions
     * @param posOff the offset of the positions
     * @param n the number of position bits
     * @return the index
     */
    int quant4p4N(short[] pos, int posOff, int n) {
        var n1 = (short) (n - 1);
        var nbPos = (short) (1 << n1);
        var posA = posA4;
        var posB = posB4;
        var i = 0;
        var j = 0;
        for (var k = 0; k < 4; k++) {
            if ((pos[posOff + k] & nbPos) == 0) {
                posA[i++] = pos[posOff + k];
            } else {
                posB[j++] = pos[posOff + k];
            }
        }
        int index;
        switch (i) {
            case 0 -> {
                var tmp = (short) ((n << 2) - 3);
                index = 1 << tmp;
                index = index + quant4p4N1(posB[0], posB[1], posB[2], posB[3], n1);
            }
            case 1 -> {
                var tmp = (short) ((short) (((3 * n1) << 1) >> 1) + 1);
                index = lShl(quant1pN1(posA[0], n1), tmp);
                index = index + quant3p3N1(posB[0], posB[1], posB[2], n1);
            }
            case 2 -> {
                var tmp = (short) ((n1 << 1) + 1);
                index = lShl(quant2p2N1(posA[0], posA[1], n1), tmp);
                index = index + quant2p2N1(posB[0], posB[1], n1);
            }
            case 3 -> {
                index = lShl(quant3p3N1(posA[0], posA[1], posA[2], n1), n);
                index = index + quant1pN1(posB[0], n1);
            }
            default -> index = quant4p4N1(posA[0], posA[1], posA[2], posA[3], n1);
        }
        var tmp = (short) ((n << 2) - 2);
        return index + lShl(i & 3, tmp);
    }

    /**
     * Codes 5 pulses with {@code 5N} bits ({@code quant_5p_5N}).
     *
     * @param pos the array holding the 5 positions
     * @param posOff the offset of the positions
     * @param n the number of position bits
     * @return the index
     */
    int quant5p5N(short[] pos, int posOff, int n) {
        var n1 = (short) (n - 1);
        var nbPos = (short) (1 << n1);
        var posA = posA5;
        var posB = posB5;
        var i = 0;
        var j = 0;
        for (var k = 0; k < 5; k++) {
            if ((pos[posOff + k] & nbPos) == 0) {
                posA[i++] = pos[posOff + k];
            } else {
                posB[j++] = pos[posOff + k];
            }
        }
        int index;
        switch (i) {
            case 0, 1, 2 -> {
                var tmp = (short) ((short) (((5 * n) << 1) >> 1) - 1);
                index = lShl(1, tmp);
                tmp = (short) ((n << 1) + 1);
                var tmp2 = lShl(quant3p3N1(posB[0], posB[1], posB[2], n1), tmp);
                index = index + tmp2;
                if (i == 0) {
                    index = index + quant2p2N1(posB[3], posB[4], n);
                } else if (i == 1) {
                    index = index + quant2p2N1(posB[3], posA[0], n);
                } else {
                    index = index + quant2p2N1(posA[0], posA[1], n);
                }
            }
            default -> {
                var tmp = (short) ((n << 1) + 1);
                index = lShl(quant3p3N1(posA[0], posA[1], posA[2], n1), tmp);
                if (i == 3) {
                    index = index + quant2p2N1(posB[0], posB[1], n);
                } else if (i == 4) {
                    index = index + quant2p2N1(posA[3], posB[0], n);
                } else {
                    index = index + quant2p2N1(posA[3], posA[4], n);
                }
            }
        }
        return index;
    }

    /**
     * Codes 6 pulses with {@code 6N - 2} bits ({@code quant_6p_6N_2}).
     *
     * @param pos the array holding the 6 positions
     * @param posOff the offset of the positions
     * @param n the number of position bits
     * @return the index
     */
    int quant6p6N2(short[] pos, int posOff, int n) {
        var n1 = (short) (n - 1);
        var nbPos = (short) (1 << n1);
        var posA = posA6;
        var posB = posB6;
        var i = 0;
        var j = 0;
        for (var k = 0; k < 6; k++) {
            if ((pos[posOff + k] & nbPos) == 0) {
                posA[i++] = pos[posOff + k];
            } else {
                posB[j++] = pos[posOff + k];
            }
        }
        int index;
        switch (i) {
            case 0 -> {
                index = 1 << (short) (6 * n - 5);
                index = index + (quant5p5N(posB, 0, n1) << n);
                index = index + quant1pN1(posB[5], n1);
            }
            case 1 -> {
                index = 1 << (short) (6 * n - 5);
                index = index + (quant5p5N(posB, 0, n1) << n);
                index = index + quant1pN1(posA[0], n1);
            }
            case 2 -> {
                index = 1 << (short) (6 * n - 5);
                index = index + (quant4p4N(posB, 0, n1) << (short) (2 * n1 + 1));
                index = index + quant2p2N1(posA[0], posA[1], n1);
            }
            case 3 -> {
                index = quant3p3N1(posA[0], posA[1], posA[2], n1) << (short) (3 * n1 + 1);
                index = index + quant3p3N1(posB[0], posB[1], posB[2], n1);
            }
            case 4 -> {
                i = 2;
                index = quant4p4N(posA, 0, n1) << (short) (2 * n1 + 1);
                index = index + quant2p2N1(posB[0], posB[1], n1);
            }
            case 5 -> {
                i = 1;
                index = quant5p5N(posA, 0, n1) << n;
                index = index + quant1pN1(posB[0], n1);
            }
            default -> {
                i = 0;
                index = quant5p5N(posA, 0, n1) << n;
                index = index + quant1pN1(posA[5], n1);
            }
        }
        return index + ((i & 3) << (short) (6 * n - 4));
    }
}
