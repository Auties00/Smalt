/* Ports opencore-amr 0.1.6 (cbsearch.cpp, c2_9pf.cpp, c2_11pf.cpp, c3_14pf.cpp, c4_17pf.cpp). Copyright PacketVideo;
   Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.add;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMsu;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shl;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_CODE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_SUBFR;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.STEP;

/**
 * Searches the algebraic (fixed) codebook of the AMR-NB encoder ({@code cbsearch}): the 2-pulse
 * 9-bit (4.75 and 5.15 kbit/s) and 11-bit (5.9 kbit/s) codebooks, the 3-pulse 14-bit
 * (6.7 kbit/s) and 4-pulse 17-bit (7.4 and 7.95 kbit/s) codebooks here, and the 8-pulse 31-bit
 * and 10-pulse 35-bit codebooks through {@link FixedCodebookHigh}.
 *
 * <p>The impulse response passed in is the encoder's {@code h1}, which has 40 zeros before it:
 * the filtered codevector is built from it at negative offsets. The pitch sharpening is applied
 * to the impulse response before the search and to the codevector after it.
 */
final class FixedCodebook {
    /**
     * The first pulse track of every subframe of the 9-bit codebook, from {@code c2_9pf.cpp}.
     */
    private static final short[] TRACK_TABLE = {0, 1, 0, 1, -1, 0, -1, 1, 0, 1, 0, 1, 0, -1, 1, 0, 1, -1, 0, 1};

    /**
     * The start positions of the first pulse of the 11-bit codebook, from {@code c2_11pf.cpp}.
     */
    private static final short[] START_POS1 = {1, 3};

    /**
     * The start positions of the second pulse of the 11-bit codebook, from {@code c2_11pf.cpp}.
     */
    private static final short[] START_POS2 = {0, 1, 2, 4};

    /**
     * The correlation between the target and the impulse response.
     */
    private final short[] dn = new short[L_CODE];

    /**
     * The position preselection.
     */
    private final short[] dn2 = new short[L_CODE];

    /**
     * The sign of every position.
     */
    private final short[] dnSign = new short[L_CODE];

    /**
     * The signed impulse response autocorrelation matrix, 40 x 40.
     */
    private final short[] rr = new short[L_CODE * L_CODE];

    /**
     * The scaled impulse response.
     */
    private final short[] h2 = new short[L_CODE];

    /**
     * The unscaled correlations.
     */
    private final int[] y32 = new int[L_CODE];

    /**
     * The chosen pulse positions.
     */
    private final short[] codvec = new short[4];

    /**
     * The signs of the chosen pulses.
     */
    private final short[] pulseSign = new short[4];

    /**
     * The search positions of the pulses.
     */
    private final short[] ipos = new short[4];

    /**
     * The 8- and 10-pulse searches.
     */
    private final FixedCodebookHigh high;

    /**
     * Creates the search with its scratch buffers.
     */
    FixedCodebook() {
        this.high = new FixedCodebookHigh(dn, rr, h2, y32);
    }

    /**
     * Searches the codebook of a mode and writes its parameters ({@code cbsearch}).
     *
     * @param x the target, updated by the 10.2 and 12.2 kbit/s searches
     * @param h the impulse response, sharpened in place
     * @param hOffset the first coefficient; 40 zeros precede it
     * @param t0 the integer pitch lag
     * @param pitchSharp the pitch sharpening gain, Q14
     * @param gainPit the quantized pitch gain, Q14
     * @param res2 the long-term prediction residual
     * @param code receives the codevector, Q13 (Q12 at 12.2 kbit/s)
     * @param y receives the filtered codevector
     * @param ana receives the parameters
     * @param anaOffset the first parameter
     * @param mode the codec mode
     * @param subNr the subframe number
     * @param overflow the saturation flag
     * @return the number of parameters written
     */
    int search(short[] x, short[] h, int hOffset, short t0, short pitchSharp, short gainPit, short[] res2, short[] code,
               short[] y, short[] ana, int anaOffset, int mode, int subNr, Flag overflow) {
        switch (mode) {
            case Mode.MR475, Mode.MR515 -> {
                code2i40_9bits(subNr, x, h, hOffset, t0, pitchSharp, code, y, ana, anaOffset, overflow);
                return 2;
            }
            case Mode.MR59 -> {
                code2i40_11bits(x, h, hOffset, t0, pitchSharp, code, y, ana, anaOffset, overflow);
                return 2;
            }
            case Mode.MR67 -> {
                code3i40_14bits(x, h, hOffset, t0, pitchSharp, code, y, ana, anaOffset, overflow);
                return 2;
            }
            case Mode.MR74, Mode.MR795 -> {
                code4i40_17bits(x, h, hOffset, t0, pitchSharp, code, y, ana, anaOffset, overflow);
                return 2;
            }
            case Mode.MR102 -> {
                var pitSharpTmp = shl(pitchSharp, (short) 1);
                for (var i = t0; i < L_SUBFR; i++) {
                    var temp = mult(h[hOffset + i - t0], pitSharpTmp, overflow);
                    h[hOffset + i] = add(h[hOffset + i], temp, overflow);
                }
                high.code8i40_31bits(x, res2, h, hOffset, code, y, ana, anaOffset, overflow);
                for (var i = t0; i < L_SUBFR; i++) {
                    var temp = mult(code[i - t0], pitSharpTmp, overflow);
                    code[i] = add(code[i], temp, overflow);
                }
                return 7;
            }
            default -> {
                var pitSharpTmp = shl(gainPit, (short) 1);
                for (var i = t0; i < L_SUBFR; i++) {
                    var temp = (short) ((h[hOffset + i - t0] * pitSharpTmp) >> 15);
                    h[hOffset + i] = add(h[hOffset + i], temp, overflow);
                }
                high.code10i40_35bits(x, res2, h, hOffset, code, y, ana, anaOffset, overflow);
                for (var i = t0; i < L_SUBFR; i++) {
                    var temp = mult(code[i - t0], pitSharpTmp, overflow);
                    code[i] = add(code[i], temp, overflow);
                }
                return 10;
            }
        }
    }

    /**
     * Sharpens the impulse response or the codevector with the pitch lag.
     *
     * @param v the vector
     * @param vOffset the first element
     * @param t0 the integer pitch lag
     * @param sharp the sharpening gain
     * @param overflow the saturation flag
     */
    private static void sharpen(short[] v, int vOffset, short t0, short sharp, Flag overflow) {
        if (t0 < L_CODE) {
            for (var i = t0; i < L_CODE; i++) {
                var temp = mult(v[vOffset + i - t0], sharp, overflow);
                v[vOffset + i] = add(v[vOffset + i], temp, overflow);
            }
        }
    }

    /**
     * Searches the 2-pulse 9-bit codebook ({@code code_2i40_9bits}).
     *
     * @param subNr the subframe number
     * @param x the target
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param t0 the integer pitch lag
     * @param pitchSharp the pitch sharpening gain
     * @param code receives the codevector
     * @param y receives the filtered codevector
     * @param ana receives the position index and the signs
     * @param anaOffset the first parameter
     * @param overflow the saturation flag
     */
    private void code2i40_9bits(int subNr, short[] x, short[] h, int hOffset, short t0, short pitchSharp, short[] code,
                                short[] y, short[] ana, int anaOffset, Flag overflow) {
        var lTemp = pitchSharp << 1;
        short sharp;
        if (lTemp != (short) lTemp) {
            overflow.value = true;
            sharp = pitchSharp > 0 ? BasicOps.MAX_16 : BasicOps.MIN_16;
        } else {
            sharp = (short) lTemp;
        }
        sharpen(h, hOffset, t0, sharp, overflow);
        CodebookCorrelation.corHX(h, hOffset, x, 0, dn, 1, y32);
        CodebookCorrelation.setSign(dn, dnSign, dn2, 8);
        CodebookCorrelation.corH(h, hOffset, dnSign, rr, h2);
        search2i40_9(subNr);
        ana[anaOffset] = build2i40_9(subNr, code, h, hOffset, y, ana, anaOffset + 1, overflow);
        sharpen(code, 0, t0, sharp, overflow);
    }

    /**
     * Finds the best pair of pulses of the 9-bit codebook.
     *
     * @param subNr the subframe number
     */
    private void search2i40_9(int subNr) {
        short psk = -1;
        short alpk = 1;
        codvec[0] = 0;
        codvec[1] = 1;
        for (var track1 = 0; track1 < 2; track1++) {
            var i = (subNr << 1) + (track1 << 3);
            ipos[0] = CommonTables.START_POS[i];
            ipos[1] = CommonTables.START_POS[i + 1];
            for (int i0 = ipos[0]; i0 < L_CODE; i0 += STEP) {
                var ps0 = dn[i0];
                var alp0 = rr[i0 * L_CODE + i0] << 14;
                short sq = -1;
                short alp = 1;
                var ix = ipos[1];
                for (int i1 = ipos[1]; i1 < L_CODE; i1 += STEP) {
                    var ps1 = (short) (ps0 + dn[i1]);
                    var alp1 = alp0 + (rr[i1 * L_CODE + i1] << 14);
                    alp1 += rr[i0 * L_CODE + i1] << 15;
                    var sq1 = (short) ((ps1 * ps1) >> 15);
                    var alp16 = (short) ((alp1 + 0x00008000) >> 16);
                    var s = ((alp * sq1) << 1) - ((sq * alp16) << 1);
                    if (s > 0) {
                        sq = sq1;
                        alp = alp16;
                        ix = (short) i1;
                    }
                }
                var s = ((alpk * sq) << 1) - ((psk * alp) << 1);
                if (s > 0) {
                    psk = sq;
                    alpk = alp;
                    codvec[0] = (short) i0;
                    codvec[1] = ix;
                }
            }
        }
    }

    /**
     * Builds the codevector, its filtered version and the indices of the 9-bit codebook.
     *
     * @param subNr the subframe number
     * @param cod receives the codevector
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param y receives the filtered codevector
     * @param ana receives the signs
     * @param signOffset the index of the signs
     * @param overflow the saturation flag
     * @return the position index
     */
    private short build2i40_9(int subNr, short[] cod, short[] h, int hOffset, short[] y, short[] ana, int signOffset,
                              Flag overflow) {
        var pt = subNr + (subNr << 2);
        java.util.Arrays.fill(cod, 0, L_CODE, (short) 0);
        short indx = 0;
        short rsign = 0;
        for (var k = 0; k < 2; k++) {
            var i = codvec[k];
            var j = dnSign[i];
            var index = (short) ((i * 6554) >> 15);
            var track = (short) (i - 5 * index);
            var first = TRACK_TABLE[pt + track];
            if (k == 0) {
                track = 0;
                if (first != 0) {
                    index += 64;
                }
            } else {
                track = 1;
                index <<= 3;
            }
            if (j > 0) {
                cod[i] = 8191;
                pulseSign[k] = 32767;
                rsign += (short) (1 << track);
            } else {
                cod[i] = -8192;
                pulseSign[k] = (short) -32768;
            }
            indx += index;
        }
        ana[signOffset] = rsign;
        var p0 = hOffset - codvec[0];
        var p1 = hOffset - codvec[1];
        for (var i = 0; i < L_CODE; i++) {
            var s = lMult(h[p0++], pulseSign[0], overflow);
            s = lMac(s, h[p1++], pulseSign[1], overflow);
            y[i] = round(s, overflow);
        }
        return indx;
    }

    /**
     * Searches the 2-pulse 11-bit codebook ({@code code_2i40_11bits}).
     *
     * @param x the target
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param t0 the integer pitch lag
     * @param pitchSharp the pitch sharpening gain
     * @param code receives the codevector
     * @param y receives the filtered codevector
     * @param ana receives the position index and the signs
     * @param anaOffset the first parameter
     * @param overflow the saturation flag
     */
    private void code2i40_11bits(short[] x, short[] h, int hOffset, short t0, short pitchSharp, short[] code, short[] y,
                                 short[] ana, int anaOffset, Flag overflow) {
        var sharp = (short) (pitchSharp << 1);
        sharpen(h, hOffset, t0, sharp, overflow);
        CodebookCorrelation.corHX(h, hOffset, x, 0, dn, 1, y32);
        CodebookCorrelation.setSign(dn, dnSign, dn2, 8);
        CodebookCorrelation.corH(h, hOffset, dnSign, rr, h2);
        search2i40_11(overflow);
        ana[anaOffset] = build2i40_11(code, h, hOffset, y, ana, anaOffset + 1, overflow);
        sharpen(code, 0, t0, sharp, overflow);
    }

    /**
     * Finds the best pair of pulses of the 11-bit codebook.
     *
     * @param overflow the saturation flag
     */
    private void search2i40_11(Flag overflow) {
        short psk = -1;
        short alpk = 1;
        codvec[0] = 0;
        codvec[1] = 1;
        for (var track1 = 0; track1 < 2; track1++) {
            for (var track2 = 0; track2 < 4; track2++) {
                ipos[0] = START_POS1[track1];
                ipos[1] = START_POS2[track2];
                for (int i0 = ipos[0]; i0 < L_CODE; i0 += STEP) {
                    var ps0 = dn[i0];
                    var alp0 = rr[i0 * L_CODE + i0] << 14;
                    short sq = -1;
                    short alp = 1;
                    var ix = ipos[1];
                    for (int i1 = ipos[1]; i1 < L_CODE; i1 += STEP) {
                        var ps1 = add(ps0, dn[i1], overflow);
                        var alp1 = alp0 + (rr[i1 * L_CODE + i1] << 14);
                        alp1 += rr[i0 * L_CODE + i1] << 15;
                        var sq1 = (short) ((ps1 * ps1) >> 15);
                        var alp16 = (short) ((alp1 + 0x00008000) >> 16);
                        var s = (alp * sq1) << 1;
                        s -= (sq * alp16) << 1;
                        if (s > 0) {
                            sq = sq1;
                            alp = alp16;
                            ix = (short) i1;
                        }
                    }
                    var s = (alpk * sq) << 1;
                    s -= (psk * alp) << 1;
                    if (s > 0) {
                        psk = sq;
                        alpk = alp;
                        codvec[0] = (short) i0;
                        codvec[1] = ix;
                    }
                }
            }
        }
    }

    /**
     * Builds the codevector, its filtered version and the indices of the 11-bit codebook.
     *
     * @param cod receives the codevector
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param y receives the filtered codevector
     * @param ana receives the signs
     * @param signOffset the index of the signs
     * @param overflow the saturation flag
     * @return the position index
     */
    private short build2i40_11(short[] cod, short[] h, int hOffset, short[] y, short[] ana, int signOffset,
                               Flag overflow) {
        java.util.Arrays.fill(cod, 0, L_CODE, (short) 0);
        short indx = 0;
        short rsign = 0;
        for (var k = 0; k < 2; k++) {
            var i = codvec[k];
            var j = dnSign[i];
            var index = (short) ((i * 6554) >> 15);
            var tempWord = (short) ((index << 3) + (index << 1));
            tempWord >>= 1;
            var track = (short) (i - tempWord);
            if (track == 0) {
                track = 1;
                index <<= 6;
            } else if (track == 1) {
                if (k == 0) {
                    track = 0;
                    index <<= 1;
                } else {
                    track = 1;
                    index = (short) ((index << 6) + 16);
                }
            } else if (track == 2) {
                track = 1;
                index = (short) ((index << 6) + 32);
            } else if (track == 3) {
                track = 0;
                index = (short) ((index << 1) + 1);
            } else if (track == 4) {
                track = 1;
                index = (short) ((index << 6) + 48);
            }
            if (j > 0) {
                cod[i] = 8191;
                pulseSign[k] = 32767;
                rsign = add(rsign, shl((short) 1, track), overflow);
            } else {
                cod[i] = -8192;
                pulseSign[k] = (short) -32768;
            }
            indx = add(indx, index, overflow);
        }
        ana[signOffset] = rsign;
        var p0 = hOffset - codvec[0];
        var p1 = hOffset - codvec[1];
        for (var i = 0; i < L_CODE; i++) {
            var s = lMac(0, h[p0++], pulseSign[0], overflow);
            s = lMac(s, h[p1++], pulseSign[1], overflow);
            y[i] = round(s, overflow);
        }
        return indx;
    }

    /**
     * Searches the 3-pulse 14-bit codebook ({@code code_3i40_14bits}).
     *
     * @param x the target
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param t0 the integer pitch lag
     * @param pitchSharp the pitch sharpening gain
     * @param code receives the codevector
     * @param y receives the filtered codevector
     * @param ana receives the position index and the signs
     * @param anaOffset the first parameter
     * @param overflow the saturation flag
     */
    private void code3i40_14bits(short[] x, short[] h, int hOffset, short t0, short pitchSharp, short[] code, short[] y,
                                 short[] ana, int anaOffset, Flag overflow) {
        var sharp = (short) (pitchSharp << 1);
        sharpen(h, hOffset, t0, sharp, overflow);
        CodebookCorrelation.corHX(h, hOffset, x, 0, dn, 1, y32);
        CodebookCorrelation.setSign(dn, dnSign, dn2, 6);
        CodebookCorrelation.corH(h, hOffset, dnSign, rr, h2);
        search3i40(overflow);
        ana[anaOffset] = build3i40(code, h, hOffset, y, ana, anaOffset + 1, overflow);
        sharpen(code, 0, t0, sharp, overflow);
    }

    /**
     * Finds the best three pulses of the 14-bit codebook.
     *
     * @param overflow the saturation flag
     */
    private void search3i40(Flag overflow) {
        short psk = -1;
        short alpk = 1;
        for (var i = 0; i < 3; i++) {
            codvec[i] = (short) i;
        }
        for (var track1 = 1; track1 < 4; track1 += 2) {
            for (var track2 = 2; track2 < 5; track2 += 2) {
                ipos[0] = 0;
                ipos[1] = (short) track1;
                ipos[2] = (short) track2;
                for (var i = 0; i < 3; i++) {
                    for (int i0 = ipos[0]; i0 < L_CODE; i0 += STEP) {
                        if (dn2[i0] < 0) {
                            continue;
                        }
                        var ps0 = dn[i0];
                        var alp0 = rr[i0 * L_CODE + i0] << 14;
                        short sq = -1;
                        short alp = 1;
                        short ps = 0;
                        var ix = ipos[1];
                        for (int i1 = ipos[1]; i1 < L_CODE; i1 += STEP) {
                            var ps1 = (short) (ps0 + dn[i1]);
                            var alp1 = alp0 + (rr[i1 * L_CODE + i1] << 14);
                            alp1 += rr[i0 * L_CODE + i1] << 15;
                            var sq1 = (short) ((ps1 * ps1) >> 15);
                            var alp16 = (short) ((alp1 + 0x00008000) >> 16);
                            var s = (alp * sq1) << 1;
                            s -= (sq * alp16) << 1;
                            if (s > 0) {
                                sq = sq1;
                                ps = ps1;
                                alp = alp16;
                                ix = (short) i1;
                            }
                        }
                        int i1 = ix;
                        ps0 = ps;
                        alp0 = alp << 14;
                        sq = -1;
                        alp = 1;
                        ps = 0;
                        ix = ipos[2];
                        for (int i2 = ipos[2]; i2 < L_CODE; i2 += STEP) {
                            var ps1 = (short) (ps0 + dn[i2]);
                            var alp1 = alp0 + (rr[i2 * L_CODE + i2] << 12);
                            alp1 += rr[i1 * L_CODE + i2] << 13;
                            alp1 += rr[i0 * L_CODE + i2] << 13;
                            var sq1 = (short) ((ps1 * ps1) >> 15);
                            var alp16 = (short) ((alp1 + 0x00008000) >> 16);
                            var s = (alp * sq1) << 1;
                            s -= (sq * alp16) << 1;
                            if (s > 0) {
                                sq = sq1;
                                ps = ps1;
                                alp = alp16;
                                ix = (short) i2;
                            }
                        }
                        int i2 = ix;
                        var s = lMult(alpk, sq, overflow);
                        s = lMsu(s, psk, alp, overflow);
                        if (s > 0) {
                            psk = sq;
                            alpk = alp;
                            codvec[0] = (short) i0;
                            codvec[1] = (short) i1;
                            codvec[2] = (short) i2;
                        }
                    }
                    var pos = ipos[2];
                    ipos[2] = ipos[1];
                    ipos[1] = ipos[0];
                    ipos[0] = pos;
                }
            }
        }
    }

    /**
     * Builds the codevector, its filtered version and the indices of the 14-bit codebook.
     *
     * @param cod receives the codevector
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param y receives the filtered codevector
     * @param ana receives the signs
     * @param signOffset the index of the signs
     * @param overflow the saturation flag
     * @return the position index
     */
    private short build3i40(short[] cod, short[] h, int hOffset, short[] y, short[] ana, int signOffset,
                            Flag overflow) {
        java.util.Arrays.fill(cod, 0, L_CODE, (short) 0);
        short indx = 0;
        short rsign = 0;
        for (var k = 0; k < 3; k++) {
            var i = codvec[k];
            var j = dnSign[i];
            var index = (short) ((i * 6554) >> 15);
            var s = (index * 5) << 1;
            s >>= 1;
            var track = (short) (i - (short) s);
            if (track == 1) {
                index <<= 4;
            } else if (track == 2) {
                index <<= 8;
            } else if (track == 3) {
                track = 1;
                index <<= 4;
                index += 8;
            } else if (track == 4) {
                track = 2;
                index <<= 8;
                index += 128;
            }
            if (j > 0) {
                cod[i] = 8191;
                pulseSign[k] = 32767;
                rsign += (short) (1 << track);
            } else {
                cod[i] = -8192;
                pulseSign[k] = (short) -32768;
            }
            indx += index;
        }
        ana[signOffset] = rsign;
        var p0 = hOffset - codvec[0];
        var p1 = hOffset - codvec[1];
        var p2 = hOffset - codvec[2];
        for (var i = 0; i < L_CODE; i++) {
            var s = lMac(0, h[p0++], pulseSign[0], overflow);
            s = lMac(s, h[p1++], pulseSign[1], overflow);
            s = lMac(s, h[p2++], pulseSign[2], overflow);
            y[i] = round(s, overflow);
        }
        return indx;
    }

    /**
     * Searches the 4-pulse 17-bit codebook ({@code code_4i40_17bits}).
     *
     * @param x the target
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param t0 the integer pitch lag
     * @param pitchSharp the pitch sharpening gain
     * @param code receives the codevector
     * @param y receives the filtered codevector
     * @param ana receives the position index and the signs
     * @param anaOffset the first parameter
     * @param overflow the saturation flag
     */
    private void code4i40_17bits(short[] x, short[] h, int hOffset, short t0, short pitchSharp, short[] code, short[] y,
                                 short[] ana, int anaOffset, Flag overflow) {
        var sharp = (short) (pitchSharp << 1);
        sharpen(h, hOffset, t0, sharp, overflow);
        CodebookCorrelation.corHX(h, hOffset, x, 0, dn, 1, y32);
        CodebookCorrelation.setSign(dn, dnSign, dn2, 4);
        CodebookCorrelation.corH(h, hOffset, dnSign, rr, h2);
        search4i40();
        ana[anaOffset] = build4i40(code, h, hOffset, y, ana, anaOffset + 1, overflow);
        sharpen(code, 0, t0, sharp, overflow);
    }

    /**
     * Finds the best four pulses of the 17-bit codebook.
     */
    private void search4i40() {
        short psk = -1;
        short alpk = 1;
        for (var i = 0; i < 4; i++) {
            codvec[i] = (short) i;
        }
        for (var track = 3; track < 5; track++) {
            ipos[0] = 0;
            ipos[1] = 1;
            ipos[2] = 2;
            ipos[3] = (short) track;
            for (var i = 0; i < 4; i++) {
                for (int i0 = ipos[0]; i0 < L_CODE; i0 += STEP) {
                    if (dn2[i0] < 0) {
                        continue;
                    }
                    var ps0 = dn[i0];
                    var alp0 = rr[i0 * L_CODE + i0] << 14;
                    short sq = -1;
                    short alp = 1;
                    short ps = 0;
                    var ix = ipos[1];
                    for (int i1 = ipos[1]; i1 < L_CODE; i1 += STEP) {
                        var ps1 = (short) (ps0 + dn[i1]);
                        var alp1 = alp0 + (rr[i1 * L_CODE + i1] << 14);
                        alp1 += rr[i0 * L_CODE + i1] << 15;
                        var sq1 = (short) ((ps1 * ps1) >> 15);
                        var alp16 = (short) ((alp1 + 0x00008000) >> 16);
                        var s = (alp * sq1) << 1;
                        s -= (sq * alp16) << 1;
                        if (s > 0) {
                            sq = sq1;
                            ps = ps1;
                            alp = alp16;
                            ix = (short) i1;
                        }
                    }
                    int i1 = ix;
                    ps0 = ps;
                    alp0 = alp << 14;
                    sq = -1;
                    alp = 1;
                    ps = 0;
                    ix = ipos[2];
                    for (int i2 = ipos[2]; i2 < L_CODE; i2 += STEP) {
                        var ps1 = (short) (ps0 + dn[i2]);
                        var alp1 = alp0 + (rr[i2 * L_CODE + i2] << 12);
                        alp1 += rr[i1 * L_CODE + i2] << 13;
                        alp1 += rr[i0 * L_CODE + i2] << 13;
                        var sq1 = (short) ((ps1 * ps1) >> 15);
                        var alp16 = (short) ((alp1 + 0x00008000) >> 16);
                        var s = (alp * sq1) << 1;
                        s -= (sq * alp16) << 1;
                        if (s > 0) {
                            sq = sq1;
                            ps = ps1;
                            alp = alp16;
                            ix = (short) i2;
                        }
                    }
                    int i2 = ix;
                    ps0 = ps;
                    alp0 = alp << 16;
                    sq = -1;
                    alp = 1;
                    ps = 0;
                    ix = ipos[3];
                    for (int i3 = ipos[3]; i3 < L_CODE; i3 += STEP) {
                        var ps1 = (short) (ps0 + dn[i3]);
                        var alp1 = alp0 + (rr[i3 * L_CODE + i3] << 12);
                        alp1 += rr[i2 * L_CODE + i3] << 13;
                        alp1 += rr[i1 * L_CODE + i3] << 13;
                        alp1 += rr[i0 * L_CODE + i3] << 13;
                        var sq1 = (short) ((ps1 * ps1) >> 15);
                        var alp16 = (short) ((alp1 + 0x00008000) >> 16);
                        var s = (alp * sq1) << 1;
                        s -= (sq * alp16) << 1;
                        if (s > 0) {
                            sq = sq1;
                            ps = ps1;
                            alp = alp16;
                            ix = (short) i3;
                        }
                    }
                    var s = (alpk * sq) << 1;
                    s -= (psk * alp) << 1;
                    if (s > 0) {
                        psk = sq;
                        alpk = alp;
                        codvec[0] = (short) i0;
                        codvec[1] = (short) i1;
                        codvec[2] = (short) i2;
                        codvec[3] = ix;
                    }
                }
                var pos = ipos[3];
                ipos[3] = ipos[2];
                ipos[2] = ipos[1];
                ipos[1] = ipos[0];
                ipos[0] = pos;
            }
        }
    }

    /**
     * Builds the codevector, its filtered version and the indices of the 17-bit codebook.
     *
     * @param cod receives the codevector
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param y receives the filtered codevector
     * @param ana receives the signs
     * @param signOffset the index of the signs
     * @param overflow the saturation flag
     * @return the position index
     */
    private short build4i40(short[] cod, short[] h, int hOffset, short[] y, short[] ana, int signOffset,
                            Flag overflow) {
        java.util.Arrays.fill(cod, 0, L_CODE, (short) 0);
        short indx = 0;
        short rsign = 0;
        for (var k = 0; k < 4; k++) {
            var i = codvec[k];
            var j = dnSign[i];
            var index = (short) ((i * 6554) >> 15);
            var s = (index * 5) << 1;
            s >>= 1;
            var track = (short) (i - (short) s);
            index = CommonTables.GRAY[index];
            if (track == 1) {
                index <<= 3;
            } else if (track == 2) {
                index <<= 6;
            } else if (track == 3) {
                index <<= 10;
            } else if (track == 4) {
                track = 3;
                index <<= 10;
                index += 512;
            }
            if (j > 0) {
                cod[i] = 8191;
                pulseSign[k] = 32767;
                rsign += (short) (1 << track);
            } else {
                cod[i] = -8192;
                pulseSign[k] = (short) -32768;
            }
            indx += index;
        }
        ana[signOffset] = rsign;
        var p0 = hOffset - codvec[0];
        var p1 = hOffset - codvec[1];
        var p2 = hOffset - codvec[2];
        var p3 = hOffset - codvec[3];
        for (var i = 0; i < L_CODE; i++) {
            var s = lMac(0, h[p0++], pulseSign[0], overflow);
            s = lMac(s, h[p1++], pulseSign[1], overflow);
            s = lMac(s, h[p2++], pulseSign[2], overflow);
            s = lMac(s, h[p3++], pulseSign[3], overflow);
            y[i] = round(s, overflow);
        }
        return indx;
    }
}
