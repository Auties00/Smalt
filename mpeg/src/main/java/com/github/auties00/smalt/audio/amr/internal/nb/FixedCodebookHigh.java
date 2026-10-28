/* Ports opencore-amr 0.1.6 (c8_31pf.cpp, c1035pf.cpp, s10_8pf.cpp). Copyright PacketVideo; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import com.github.auties00.smalt.audio.amr.internal.AmrKernel;
import com.github.auties00.smalt.audio.amr.internal.AmrKernels;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_CODE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.NB_TRACK;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.NB_TRACK_MR102;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.STEP;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.STEP_MR102;

/**
 * Searches the 8-pulse 31-bit (10.2 kbit/s) and 10-pulse 35-bit (12.2 kbit/s) algebraic
 * codebooks of the AMR-NB encoder with the depth-first pulse-pair search of
 * {@code search_10and8i40}.
 */
final class FixedCodebookHigh {
    /**
     * The correlation between the target and the impulse response, shared with
     * {@link FixedCodebook}.
     */
    private final short[] dn;

    /**
     * The signed impulse response autocorrelation matrix, shared with {@link FixedCodebook}.
     */
    private final short[] rr;

    /**
     * Scratch for the scaled impulse response, shared with {@link FixedCodebook}.
     */
    private final short[] h2;

    /**
     * Scratch for the unscaled correlations, shared with {@link FixedCodebook}.
     */
    private final int[] y32;

    /**
     * The sign of every position.
     */
    private final short[] sign = new short[L_CODE];

    /**
     * The sign-weighted correlation magnitudes.
     */
    private final short[] en = new short[L_CODE];

    /**
     * The strongest position of every track.
     */
    private final short[] posMax = new short[NB_TRACK];

    /**
     * The track order of the search.
     */
    private final short[] ipos = new short[10];

    /**
     * The chosen pulse positions.
     */
    private final short[] codvec = new short[10];

    /**
     * The positions of the current candidate.
     */
    private final short[] index = new short[10];

    /**
     * The partial sums of the inner search loops, two values per position.
     */
    private final short[] temp1 = new short[2 * L_CODE];

    /**
     * The kernel that scores the candidate pairs of the 12.2 kbit/s search.
     */
    private static final AmrKernel KERNEL = AmrKernels.INSTANCE;

    /**
     * The track order of the wide search.
     */
    private final short[] wideTracks = new short[10];

    /**
     * The positions of the current candidate in the wide search.
     */
    private final short[] widePositions = new short[10];

    /**
     * The best positions of the wide search.
     */
    private final short[] wideBest = new short[10];

    /**
     * The correlations of the candidates of a stage, one row per position of the first track.
     */
    private final short[] stageBlock = new short[64];

    /**
     * The energy of each row of a stage.
     */
    private final int[] stageEnergy = new int[8];

    /**
     * The correlation of the first pulse of each row of a stage.
     */
    private final short[] stageRow = new short[8];

    /**
     * The correlation of the second pulse of each column of a stage.
     */
    private final short[] stageColumn = new short[8];

    /**
     * The energy term of each column of a stage.
     */
    private final short[] stageTerm = new short[8];

    /**
     * The result of the scoring of a stage.
     */
    private final int[] stageResult = new int[32];

    /**
     * The correlation of the chosen pulses of the stage that just ran.
     */
    private short stagePs;

    /**
     * The best normalized correlation of the stage that just ran.
     */
    private short stageSq;

    /**
     * The best energy of the stage that just ran.
     */
    private short stageAlp;

    /**
     * The best first and second positions of the stage that just ran.
     */
    private int stageFirst;

    /**
     * The best second position of the stage that just ran.
     */
    private int stageSecond;

    /**
     * The signs of the chosen pulses.
     */
    private final short[] pulseSign = new short[10];

    /**
     * The sign index of every track of the 31-bit codebook.
     */
    private final short[] linearSigns = new short[NB_TRACK_MR102];

    /**
     * The position indices of the 31-bit codebook, two per track.
     */
    private final short[] linearCodewords = new short[8];

    /**
     * Creates the search over the scratch buffers of {@link FixedCodebook}.
     *
     * @param dn the correlation buffer
     * @param rr the matrix buffer
     * @param h2 the scaled impulse response buffer
     * @param y32 the unscaled correlation buffer
     */
    FixedCodebookHigh(short[] dn, short[] rr, short[] h2, int[] y32) {
        this.dn = dn;
        this.rr = rr;
        this.h2 = h2;
        this.y32 = y32;
    }

    /**
     * Searches the 8-pulse 31-bit codebook ({@code code_8i40_31bits}).
     *
     * @param x the target
     * @param cn the long-term prediction residual
     * @param h the impulse response
     * @param hOffset the first coefficient; 40 zeros precede it
     * @param cod receives the codevector
     * @param y receives the filtered codevector
     * @param indx receives the 7 indices
     * @param indxOffset the first index
     * @param overflow the saturation flag
     */
    void code8i40_31bits(short[] x, short[] cn, short[] h, int hOffset, short[] cod, short[] y, short[] indx,
                         int indxOffset, Flag overflow) {
        CodebookCorrelation.corHX2(h, hOffset, x, 0, dn, 2, NB_TRACK_MR102, STEP_MR102, y32, overflow);
        CodebookCorrelation.setSign12k2(dn, cn, sign, posMax, NB_TRACK_MR102, ipos, STEP_MR102, en, overflow);
        CodebookCorrelation.corH(h, hOffset, sign, rr, h2, overflow);
        search10and8i40(8, STEP_MR102, NB_TRACK_MR102);
        build8i40(cod, h, hOffset, y, overflow);
        compressCode(indx, indxOffset);
    }

    /**
     * Searches the 10-pulse 35-bit codebook ({@code code_10i40_35bits}).
     *
     * @param x the target
     * @param cn the long-term prediction residual
     * @param h the impulse response
     * @param hOffset the first coefficient; 40 zeros precede it
     * @param cod receives the codevector
     * @param y receives the filtered codevector
     * @param indx receives the 10 indices
     * @param indxOffset the first index
     * @param overflow the saturation flag
     */
    void code10i40_35bits(short[] x, short[] cn, short[] h, int hOffset, short[] cod, short[] y, short[] indx,
                          int indxOffset, Flag overflow) {
        CodebookCorrelation.corHX(h, hOffset, x, 0, dn, 2, y32, overflow);
        CodebookCorrelation.setSign12k2(dn, cn, sign, posMax, NB_TRACK, ipos, STEP, en, overflow);
        CodebookCorrelation.corH(h, hOffset, sign, rr, h2, overflow);
        if (!search10Wide()) {
            search10and8i40(10, STEP, NB_TRACK);
        }
        build10i40(cod, h, hOffset, y, indx, indxOffset);
        for (var i = 0; i < 10; i++) {
            var tmp = indx[indxOffset + i];
            if (i < 5) {
                indx[indxOffset + i] = (short) ((tmp & 0x8) | CommonTables.GRAY[tmp & 0x7]);
            } else {
                indx[indxOffset + i] = CommonTables.GRAY[tmp & 0x7];
            }
        }
    }

    /**
     * Returns an element of the autocorrelation matrix.
     *
     * @param row the row index, 0 to 39
     * @param column the column index, 0 to 39
     * @return the element
     */
    private int rr(int row, int column) {
        return rr[row * L_CODE + column];
    }

    /**
     * Searches the pulse positions of the 12.2 kbit/s codebook ({@code search_10and8i40} with ten
     * pulses) by scoring the candidate pairs of each stage with {@link AmrKernel#pairSearch}.
     *
     * <p>The result is that of {@link #search10and8i40(int, int, int)} whenever every candidate
     * has a positive energy; otherwise the search is left untouched for that method to run.
     *
     * @return whether the search ran and left the pulse positions in {@link #codvec}
     */
    private boolean search10Wide() {
        var tracks = wideTracks;
        System.arraycopy(ipos, 0, tracks, 0, 10);
        var positions = widePositions;
        var best = wideBest;
        int i0 = posMax[tracks[0]];
        positions[0] = (short) i0;
        for (var i = 0; i < 10; i++) {
            best[i] = (short) i;
        }
        short psk = -1;
        short alpk = 1;
        for (var iteration = 1; iteration < NB_TRACK; iteration++) {
            int i1 = posMax[tracks[1]];
            positions[1] = (short) i1;
            var ps = (short) (dn[i0] + dn[i1]);
            var energy = rr(i0, i0) << 12;
            energy += rr(i1, i1) << 12;
            energy += rr(i0, i1) << 13;
            energy += 0x00008000;
            if (!wideStage(0, tracks[2], tracks[3], 2, ps, energy)) {
                return false;
            }
            positions[2] = (short) stageFirst;
            positions[3] = (short) stageSecond;
            energy = (stageAlp << 15) + 0x00008000;
            if (!wideStage(1, tracks[4], tracks[5], 4, stagePs, energy)) {
                return false;
            }
            positions[4] = (short) stageFirst;
            positions[5] = (short) stageSecond;
            energy = (stageAlp << 15) + 0x00008000;
            if (!wideStage(2, tracks[6], tracks[7], 6, stagePs, energy)) {
                return false;
            }
            positions[6] = (short) stageFirst;
            positions[7] = (short) stageSecond;
            energy = (stageAlp << 15) + 0x00008000;
            if (!wideStage(3, tracks[8], tracks[9], 8, stagePs, energy)) {
                return false;
            }
            positions[8] = (short) stageFirst;
            positions[9] = (short) stageSecond;
            if (alpk * stageSq > psk * stageAlp) {
                psk = stageSq;
                alpk = stageAlp;
                System.arraycopy(positions, 0, best, 0, 2 * NB_TRACK);
            }
            var pos = tracks[1];
            for (int j = 1, k = 2; k < 10; j++, k++) {
                tracks[j] = tracks[k];
            }
            tracks[9] = pos;
        }
        System.arraycopy(best, 0, codvec, 0, 10);
        return true;
    }

    /**
     * Runs one stage of the wide search: finds the best pair of positions on two tracks given the
     * pulses already chosen, leaving the pair and its scores in the {@code stage} fields.
     *
     * @param kind the stage kind, 0 to 3
     * @param trackA the track of the first pulse of the pair
     * @param trackB the track of the second pulse of the pair
     * @param chosen the number of pulses already chosen, in {@link #widePositions}
     * @param ps the correlation of the chosen pulses
     * @param energy the scaled energy of the chosen pulses
     * @return whether the scoring was valid
     */
    private boolean wideStage(int kind, int trackA, int trackB, int chosen, short ps, int energy) {
        var positions = widePositions;
        var s0 = energy >> 12;
        for (var t = 0; t < 8; t++) {
            int column = trackB + STEP * t;
            var sum = rr(column, column) >> 1;
            for (var c = 0; c < chosen; c++) {
                sum += kind < 2 ? rr(column, positions[c]) : rr(positions[c], column);
            }
            stageColumn[t] = (short) (ps + dn[column]);
            stageTerm[t] = (short) ((sum + (kind == 0 || kind == 1 ? 2 : 4)) >> (kind == 0 || kind == 1 ? 2 : 3));
        }
        for (var r = 0; r < 8; r++) {
            int row = trackA + STEP * r;
            int alp1;
            if (kind == 0) {
                alp1 = (s0 + rr(row, row)) >> 1;
                for (var c = 0; c < chosen; c++) {
                    alp1 += rr(row, positions[c]);
                }
            } else {
                var diagonalShift = kind == 1 ? 11 : kind == 2 ? 10 : 9;
                alp1 = energy + (rr(row, row) << diagonalShift);
                var pairShift = diagonalShift + 1;
                for (var c = 0; c < chosen; c++) {
                    alp1 += (kind < 2 ? rr(row, positions[c]) : rr(positions[c], row)) << pairShift;
                }
            }
            stageEnergy[r] = alp1;
            stageRow[r] = dn[row];
            for (var t = 0; t < 8; t++) {
                stageBlock[8 * r + t] = (short) rr(row, trackB + STEP * t);
            }
        }
        KERNEL.pairSearch(kind, stageBlock, stageEnergy, stageRow, stageColumn, stageTerm, stageResult);
        var invalid = 0;
        for (var t = 0; t < 8; t++) {
            invalid |= stageResult[24 + t];
        }
        if (invalid != 0) {
            return false;
        }
        var bestLane = 0;
        for (var t = 1; t < 8; t++) {
            var left = stageResult[t] * stageResult[8 + bestLane];
            var right = stageResult[bestLane] * stageResult[8 + t];
            if (left > right || (left == right && stageResult[16 + t] < stageResult[16 + bestLane])) {
                bestLane = t;
            }
        }
        var row = stageResult[16 + bestLane];
        stageSq = (short) stageResult[bestLane];
        stageAlp = (short) stageResult[8 + bestLane];
        stagePs = (short) (stageRow[row] + stageColumn[bestLane]);
        stageFirst = trackA + STEP * row;
        stageSecond = trackB + STEP * bestLane;
        return true;
    }

    /**
     * Searches the pulse positions pair by pair ({@code search_10and8i40}).
     *
     * @param nbPulse the number of pulses, 8 or 10
     * @param step the track step
     * @param nbTracks the number of tracks
     */
    private void search10and8i40(int nbPulse, int step, int nbTracks) {
        var gsmefr = nbPulse == 10;
        int i0 = posMax[ipos[0]];
        index[0] = (short) i0;
        short psk = -1;
        short alpk = 1;
        for (var i = 0; i < nbPulse; i++) {
            codvec[i] = (short) i;
        }
        for (var i = 1; i < nbTracks; i++) {
            int i1 = posMax[ipos[1]];
            index[1] = (short) i1;
            var ps0 = (short) (dn[i0] + dn[i1]);
            var alp0 = rr(i0, i0) << 12;
            alp0 += rr(i1, i1) << 12;
            alp0 += rr(i0, i1) << 13;
            alp0 += 0x00008000;
            var p = 0;
            for (int i3 = ipos[3]; i3 < L_CODE; i3 += step) {
                var s = rr(i3, i3) >> 1;
                s += rr(i3, i0);
                s += rr(i3, i1);
                temp1[p++] = (short) (ps0 + dn[i3]);
                temp1[p++] = (short) ((s + 2) >> 2);
            }
            short sq = -1;
            short alp = 1;
            short ps = 0;
            var ia = ipos[2];
            var ib = ipos[3];
            var s0 = alp0 >> 12;
            for (int j = ipos[2]; j < L_CODE; j += step) {
                var alp1 = (s0 + rr(j, j)) >> 1;
                alp1 += rr(j, i0);
                alp1 += rr(j, i1);
                p = 0;
                var ps1 = dn[j];
                for (int i3 = ipos[3]; i3 < L_CODE; i3 += step) {
                    var ps2 = (short) (ps1 + temp1[p++]);
                    var sq2 = (short) ((ps2 * ps2) >> 15);
                    var alp2 = (alp1 + rr(j, i3)) >> 2;
                    alp2 = (alp2 + temp1[p++]) >> 1;
                    if (sq2 * alp > sq * alp2) {
                        sq = sq2;
                        ps = ps2;
                        alp = (short) alp2;
                        ia = (short) j;
                        ib = (short) i3;
                    }
                }
            }
            int i2 = ia;
            int i3 = ib;
            index[2] = ia;
            index[3] = ib;
            alp0 = (alp << 15) + 0x00008000;
            p = 0;
            for (int i5 = ipos[5]; i5 < L_CODE; i5 += step) {
                var s = rr(i5, i5) >> 1;
                s += rr(i5, i0);
                s += rr(i5, i1);
                s += rr(i5, i2);
                s += rr(i5, i3);
                temp1[p++] = (short) (ps + dn[i5]);
                temp1[p++] = (short) ((s + 2) >> 2);
            }
            sq = -1;
            alp = 1;
            ps = 0;
            ia = ipos[4];
            ib = ipos[5];
            for (int j = ipos[4]; j < L_CODE; j += step) {
                var alp1 = alp0 + (rr(j, j) << 11);
                alp1 += rr(j, i0) << 12;
                alp1 += rr(j, i1) << 12;
                alp1 += rr(j, i2) << 12;
                alp1 += rr(j, i3) << 12;
                p = 0;
                var ps1 = dn[j];
                for (int i5 = ipos[5]; i5 < L_CODE; i5 += step) {
                    var ps2 = (short) (ps1 + temp1[p++]);
                    var alp2 = alp1 + (rr(j, i5) << 12);
                    var alp16 = (short) ((alp2 + (temp1[p++] << 14)) >> 16);
                    var sq2 = (short) ((ps2 * ps2) >> 15);
                    if (sq2 * alp > sq * alp16) {
                        sq = sq2;
                        ps = ps2;
                        alp = alp16;
                        ia = (short) j;
                        ib = (short) i5;
                    }
                }
            }
            int i4 = ia;
            int i5 = ib;
            index[4] = ia;
            index[5] = ib;
            alp0 = (alp << 15) + 0x00008000;
            p = 0;
            for (int i7 = ipos[7]; i7 < L_CODE; i7 += step) {
                var s = rr(i7, i7) >> 1;
                s += rr(i0, i7);
                s += rr(i1, i7);
                s += rr(i2, i7);
                s += rr(i3, i7);
                s += rr(i4, i7);
                s += rr(i5, i7);
                temp1[p++] = (short) (ps + dn[i7]);
                temp1[p++] = (short) ((s + 4) >> 3);
            }
            sq = -1;
            alp = 1;
            ps = 0;
            ia = ipos[6];
            ib = ipos[7];
            for (int j = ipos[6]; j < L_CODE; j += step) {
                var alp1 = alp0 + (rr(j, j) << 10);
                alp1 += rr(j, i0) << 11;
                alp1 += rr(j, i1) << 11;
                alp1 += rr(j, i2) << 11;
                alp1 += rr(j, i3) << 11;
                alp1 += rr(j, i4) << 11;
                alp1 += rr(j, i5) << 11;
                p = 0;
                var ps1 = dn[j];
                for (int i7 = ipos[7]; i7 < L_CODE; i7 += step) {
                    var ps2 = (short) (ps1 + temp1[p++]);
                    var alp2 = alp1 + (rr(j, i7) << 11);
                    var alp16 = (short) ((alp2 + (temp1[p++] << 14)) >> 16);
                    var sq2 = (short) ((ps2 * ps2) >> 15);
                    if (sq2 * alp > sq * alp16) {
                        sq = sq2;
                        ps = ps2;
                        alp = alp16;
                        ia = (short) j;
                        ib = (short) i7;
                    }
                }
            }
            int i6 = ia;
            int i7 = ib;
            index[6] = ia;
            index[7] = ib;
            if (gsmefr) {
                alp0 = (alp << 15) + 0x00008000;
                p = 0;
                for (int i9 = ipos[9]; i9 < L_CODE; i9 += step) {
                    var s = rr(i9, i9) >> 1;
                    s += rr(i0, i9);
                    s += rr(i1, i9);
                    s += rr(i2, i9);
                    s += rr(i3, i9);
                    s += rr(i4, i9);
                    s += rr(i5, i9);
                    s += rr(i6, i9);
                    s += rr(i7, i9);
                    temp1[p++] = (short) (ps + dn[i9]);
                    temp1[p++] = (short) ((s + 4) >> 3);
                }
                sq = -1;
                alp = 1;
                ps = 0;
                ia = ipos[8];
                ib = ipos[9];
                for (int j = ipos[8]; j < L_CODE; j += step) {
                    var alp1 = alp0 + (rr(j, j) << 9);
                    alp1 += rr(i0, j) << 10;
                    alp1 += rr(i1, j) << 10;
                    alp1 += rr(i2, j) << 10;
                    alp1 += rr(i3, j) << 10;
                    alp1 += rr(i4, j) << 10;
                    alp1 += rr(i5, j) << 10;
                    alp1 += rr(i6, j) << 10;
                    alp1 += rr(i7, j) << 10;
                    p = 0;
                    var ps1 = dn[j];
                    for (int i9 = ipos[9]; i9 < L_CODE; i9 += step) {
                        var ps2 = (short) (ps1 + temp1[p++]);
                        var sq2 = (short) ((ps2 * ps2) >> 15);
                        var alp2 = alp1 + (rr(j, i9) << 10);
                        var alp16 = (short) ((alp2 + (temp1[p++] << 13)) >> 16);
                        if (sq2 * alp > sq * alp16) {
                            sq = sq2;
                            ps = ps2;
                            alp = alp16;
                            ia = (short) j;
                            ib = (short) i9;
                        }
                    }
                }
                index[8] = ia;
                index[9] = ib;
            }
            if (alpk * sq > psk * alp) {
                psk = sq;
                alpk = alp;
                System.arraycopy(index, 0, codvec, 0, gsmefr ? 2 * NB_TRACK : 2 * NB_TRACK_MR102);
            }
            var pos = ipos[1];
            for (int j = 1, k = 2; k < nbPulse; j++, k++) {
                ipos[j] = ipos[k];
            }
            ipos[nbPulse - 1] = pos;
        }
    }

    /**
     * Builds the codevector, its filtered version, and the sign and position indices of the
     * 31-bit codebook.
     *
     * @param cod receives the codevector
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param y receives the filtered codevector
     * @param overflow the saturation flag
     */
    private void build8i40(short[] cod, short[] h, int hOffset, short[] y, Flag overflow) {
        java.util.Arrays.fill(cod, 0, L_CODE, (short) 0);
        for (var i = 0; i < NB_TRACK_MR102; i++) {
            linearCodewords[i] = -1;
            linearSigns[i] = -1;
        }
        for (var k = 0; k < 8; k++) {
            var i = codvec[k];
            var j = sign[i];
            var posIndex = (short) (i >> 2);
            var track = i & 3;
            short signIndex;
            if (j > 0) {
                cod[i] = (short) (cod[i] + 8191);
                pulseSign[k] = 32767;
                signIndex = 0;
            } else {
                cod[i] = (short) (cod[i] - 8191);
                pulseSign[k] = (short) -32768;
                signIndex = 1;
            }
            if (linearCodewords[track] < 0) {
                linearCodewords[track] = posIndex;
                linearSigns[track] = signIndex;
            } else if (((signIndex ^ linearSigns[track]) & 1) == 0) {
                if (linearCodewords[track] <= posIndex) {
                    linearCodewords[track + NB_TRACK_MR102] = posIndex;
                } else {
                    linearCodewords[track + NB_TRACK_MR102] = linearCodewords[track];
                    linearCodewords[track] = posIndex;
                    linearSigns[track] = signIndex;
                }
            } else if (linearCodewords[track] <= posIndex) {
                linearCodewords[track + NB_TRACK_MR102] = linearCodewords[track];
                linearCodewords[track] = posIndex;
                linearSigns[track] = signIndex;
            } else {
                linearCodewords[track + NB_TRACK_MR102] = posIndex;
            }
        }
        for (var i = 0; i < L_CODE; i++) {
            var s = 0;
            for (var k = 0; k < 8; k++) {
                s = lMac(s, h[hOffset - codvec[k] + i], pulseSign[k], overflow);
            }
            y[i] = round(s, overflow);
        }
    }

    /**
     * Packs three position indices into 10 bits ({@code compress10}).
     *
     * @param a the first index
     * @param b the second index
     * @param c the third index
     * @return the packed index
     */
    private static short compress10(short a, short b, short c) {
        var ia = (short) (a >> 1);
        var ib = (short) (((b >> 1) * 5 << 1) >> 1);
        var ic = (short) (((c >> 1) * 25 << 1) >> 1);
        ib += ic;
        ib += ia;
        var indx = (short) (ib << 3);
        ia = (short) (a & 1);
        ib = (short) ((short) (b & 1) << 1);
        ic = (short) ((short) (c & 1) << 2);
        ib += ic;
        ib += ia;
        indx += ib;
        return indx;
    }

    /**
     * Writes the 7 indices of the 31-bit codebook ({@code compress_code}).
     *
     * @param indx receives the indices
     * @param indxOffset the first index
     */
    private void compressCode(short[] indx, int indxOffset) {
        System.arraycopy(linearSigns, 0, indx, indxOffset, NB_TRACK_MR102);
        indx[indxOffset + NB_TRACK_MR102] = compress10(linearCodewords[0], linearCodewords[4], linearCodewords[1]);
        indx[indxOffset + NB_TRACK_MR102 + 1] = compress10(linearCodewords[2], linearCodewords[6], linearCodewords[5]);
        var ib = (short) (linearCodewords[7] >> 1);
        ib &= 1;
        var ia = (short) (linearCodewords[3] >> 1);
        if (ib == 1) {
            ia = (short) (4 - ia);
        }
        ib = (short) (linearCodewords[7] >> 1);
        ib = (short) (((ib * 5) << 1) >> 1);
        ib += ia;
        ib <<= 5;
        ib += 12;
        var ic = (short) ((ib * 1311) >> 15);
        ic <<= 2;
        ia = (short) (linearCodewords[3] & 1);
        ib = (short) ((short) (linearCodewords[7] & 1) << 1);
        ib += ic;
        ib += ia;
        indx[indxOffset + NB_TRACK_MR102 + 2] = ib;
    }

    /**
     * Builds the codevector, its filtered version and the position indices of the 35-bit
     * codebook.
     *
     * @param cod receives the codevector
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param y receives the filtered codevector
     * @param indx receives the 10 position indices
     * @param indxOffset the first index
     */
    private void build10i40(short[] cod, short[] h, int hOffset, short[] y, short[] indx, int indxOffset) {
        java.util.Arrays.fill(cod, 0, L_CODE, (short) 0);
        for (var i = 0; i < NB_TRACK; i++) {
            indx[indxOffset + i] = -1;
        }
        for (var k = 0; k < 10; k++) {
            var i = codvec[k];
            var index = (short) ((i * 6554) >> 15);
            var track = i - (index + (index << 2));
            if (sign[i] > 0) {
                cod[i] += 4096;
                pulseSign[k] = 8192;
            } else {
                cod[i] -= 4096;
                pulseSign[k] = -8192;
                index += 8;
            }
            var p1 = indxOffset + track;
            var temp = indx[p1];
            if (temp < 0) {
                indx[p1] = index;
            } else if (((index ^ temp) & 8) == 0) {
                if (temp <= index) {
                    indx[p1 + 5] = index;
                } else {
                    indx[p1 + 5] = temp;
                    indx[p1] = index;
                }
            } else if ((temp & 7) <= (index & 7)) {
                indx[p1 + 5] = temp;
                indx[p1] = index;
            } else {
                indx[p1 + 5] = index;
            }
        }
        for (var i = 0; i < L_CODE; i++) {
            var s = 0;
            for (var k = 0; k < 10; k++) {
                s += (h[hOffset - codvec[k] + i] * pulseSign[k]) >> 7;
            }
            y[i] = (short) ((s + 0x080) >> 8);
        }
    }
}
