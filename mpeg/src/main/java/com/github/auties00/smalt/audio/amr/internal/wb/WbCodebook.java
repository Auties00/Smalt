/* Ports opencore-amr 0.1.6 (dec_acelp_2p_in_64.cpp, dec_acelp_4p_in_64.cpp, dec_alg_codebook.cpp,
   dec_gain2_amr_wb.cpp, lagconceal.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wb;

import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbCodecTables;
import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbPitchOps;

import static com.github.auties00.smalt.audio.amr.internal.wb.WbMath.*;

/**
 * Decodes the excitation parameters of the AMR-WB decoder: the algebraic codebook pulses, the
 * quantized pitch and code gains with their bad-frame concealment, and the pitch lag
 * concealment.
 */
final class WbCodebook {
    /**
     * The number of positions of a track of the 4-track codebooks.
     */
    private static final int NB_POS = 16;

    /**
     * The length of the gain and lag histories.
     */
    private static final int L_LTPHIST = 5;

    /**
     * The pitch gain attenuation of unusable frames per error state.
     */
    private static final short[] PDOWN_UNUSABLE = {32767, 31130, 29491, 24576, 7537, 1638, 328};

    /**
     * The code gain attenuation of unusable frames per error state.
     */
    private static final short[] CDOWN_UNUSABLE = {32767, 16384, 8192, 8192, 8192, 4915, 3277};

    /**
     * The pitch gain attenuation of bad but usable frames per error state.
     */
    private static final short[] PDOWN_USABLE = {32767, 32113, 31457, 24576, 7537, 1638, 328};

    /**
     * The code gain attenuation of bad but usable frames per error state.
     */
    private static final short[] CDOWN_USABLE = {32767, 32113, 32113, 32113, 32113, 32113, 22938};

    /**
     * Prevents instantiation.
     */
    private WbCodebook() {
    }

    /**
     * Decodes the 2-pulse, 12-bit innovation of the 6.60 kbit/s mode.
     *
     * @param index the 12-bit index
     * @param code the 64-sample innovation
     * @param off the offset of the innovation
     */
    static void decAcelp2p(int index, short[] code, int off) {
        java.util.Arrays.fill(code, off, off + 64, (short) 0);
        var i = (index >> 5) & 0x003E;
        code[off + i] = (short) (((index >> 6) & 32) == 0 ? 512 : -512);
        i = ((index & 0x001F) << 1) + 1;
        code[off + i] = (short) ((index & 32) == 0 ? 512 : -512);
    }

    /**
     * Decodes the 4-track innovation of the 8.85 to 23.85 kbit/s modes.
     *
     * @param index the track indices
     * @param nbBits the number of bits of the codebook: 20, 36, 44, 52, 64, 72 or 88
     * @param code the 64-sample innovation
     * @param off the offset of the innovation
     * @param pos a scratch array of at least 6 positions
     */
    static void decAcelp4p(short[] index, int nbBits, short[] code, int off, short[] pos) {
        java.util.Arrays.fill(code, off, off + 64, (short) 0);
        switch (nbBits) {
            case 20 -> {
                for (var k = 0; k < 4; k++) {
                    dec1pN1(index[k], 4, 0, pos, 0);
                    addPulses(pos, 1, k, code, off);
                }
            }
            case 36 -> {
                for (var k = 0; k < 4; k++) {
                    dec2p2N1(index[k], 4, 0, pos, 0);
                    addPulses(pos, 2, k, code, off);
                }
            }
            case 44 -> {
                for (var k = 0; k < 2; k++) {
                    dec3p3N1(index[k], 4, 0, pos, 0);
                    addPulses(pos, 3, k, code, off);
                }
                for (var k = 2; k < 4; k++) {
                    dec2p2N1(index[k], 4, 0, pos, 0);
                    addPulses(pos, 2, k, code, off);
                }
            }
            case 52 -> {
                for (var k = 0; k < 4; k++) {
                    dec3p3N1(index[k], 4, 0, pos, 0);
                    addPulses(pos, 3, k, code, off);
                }
            }
            case 64 -> {
                for (var k = 0; k < 4; k++) {
                    var lIndex = (index[k] << 14) + index[k + 4];
                    dec4p4N(lIndex, 4, 0, pos, 0);
                    addPulses(pos, 4, k, code, off);
                }
            }
            case 72 -> {
                for (var k = 0; k < 2; k++) {
                    var lIndex = (index[k] << 10) + index[k + 4];
                    dec5p5N(lIndex, 4, 0, pos, 0);
                    addPulses(pos, 5, k, code, off);
                }
                for (var k = 2; k < 4; k++) {
                    var lIndex = (index[k] << 14) + index[k + 4];
                    dec4p4N(lIndex, 4, 0, pos, 0);
                    addPulses(pos, 4, k, code, off);
                }
            }
            case 88 -> {
                for (var k = 0; k < 4; k++) {
                    var lIndex = (index[k] << 11) + index[k + 4];
                    dec6p6N2(lIndex, 4, 0, pos, 0);
                    addPulses(pos, 6, k, code, off);
                }
            }
            default -> {
            }
        }
    }

    /**
     * Adds signed unit pulses of one track to the innovation.
     *
     * @param pos the pulse positions, with the sign in bit 4
     * @param nbPulse the number of pulses
     * @param track the track number, 0 to 3
     * @param code the innovation
     * @param off the offset of the innovation
     */
    private static void addPulses(short[] pos, int nbPulse, int track, short[] code, int off) {
        for (var k = 0; k < nbPulse; k++) {
            var i = off + ((pos[k] & (NB_POS - 1)) << 2) + track;
            if ((pos[k] & NB_POS) == 0) {
                code[i] += 512;
            } else {
                code[i] -= 512;
            }
        }
    }

    /**
     * Decodes one pulse coded on {@code N + 1} bits.
     *
     * @param index the coded pulse index
     * @param n the number of position bits
     * @param offset the position offset
     * @param pos the positions
     * @param p the index of the decoded position
     */
    private static void dec1pN1(int index, int n, int offset, short[] pos, int p) {
        var mask = (1 << n) - 1;
        var pos1 = (short) ((index & mask) + offset);
        if (((index >> n) & 1) == 1) {
            pos1 += NB_POS;
        }
        pos[p] = pos1;
    }

    /**
     * Decodes two pulses coded on {@code 2N + 1} bits.
     *
     * @param index the coded pulse index
     * @param n the number of position bits per pulse
     * @param offset the position offset
     * @param pos the positions
     * @param p the index of the first decoded position
     */
    private static void dec2p2N1(int index, int n, int offset, short[] pos, int p) {
        var mask = (int) sub16(shl16((short) 1, n), 1);
        var pos1 = (short) add32(shr32(index, n) & mask, (short) offset);
        var tmp = shl16((short) n, 1);
        var i = (index >> tmp) & 1;
        var pos2 = add16((short) (index & mask), offset);
        if (pos2 < pos1) {
            if (i == 1) {
                pos1 += NB_POS;
            } else {
                pos2 += NB_POS;
            }
        } else if (i == 1) {
            pos1 += NB_POS;
            pos2 += NB_POS;
        }
        pos[p] = pos1;
        pos[p + 1] = pos2;
    }

    /**
     * Decodes three pulses coded on {@code 3N + 1} bits.
     *
     * @param index the coded pulse index
     * @param n the number of position bits per pulse
     * @param offset the position offset
     * @param pos the positions
     * @param p the index of the first decoded position
     */
    private static void dec3p3N1(int index, int n, int offset, short[] pos, int p) {
        var mask = (1 << ((2 * n) - 1)) - 1;
        var idx = index & mask;
        var j = (short) offset;
        var tmp = (n << 1) - 1;
        if (((index >> tmp) & 1) != 0) {
            j += (short) (1 << (n - 1));
        }
        dec2p2N1(idx, (short) (n - 1), j, pos, p);
        mask = (1 << (n + 1)) - 1;
        tmp = n << 1;
        idx = (index >> tmp) & mask;
        dec1pN1(idx, n, offset, pos, p + 2);
    }

    /**
     * Decodes four pulses coded on {@code 4N + 1} bits.
     *
     * @param index the coded pulse index
     * @param n the number of position bits per pulse
     * @param offset the position offset
     * @param pos the positions
     * @param p the index of the first decoded position
     */
    private static void dec4p4N1(int index, int n, int offset, short[] pos, int p) {
        var tmp = (n << 1) - 1;
        var mask = (1 << tmp) - 1;
        var idx = index & mask;
        var j = (short) offset;
        if (((index >> tmp) & 1) != 0) {
            j += (short) (1 << (n - 1));
        }
        dec2p2N1(idx, (short) (n - 1), j, pos, p);
        tmp = (n << 1) + 1;
        mask = (1 << tmp) - 1;
        idx = (index >> (n << 1)) & mask;
        dec2p2N1(idx, n, offset, pos, p + 2);
    }

    /**
     * Decodes four pulses coded on {@code 4N} bits.
     *
     * @param index the coded pulse index
     * @param n the number of position bits per pulse
     * @param offset the position offset
     * @param pos the positions
     * @param p the index of the first decoded position
     */
    private static void dec4p4N(int index, int n, int offset, short[] pos, int p) {
        var n1 = (short) (n - 1);
        var j = (short) (offset + (1 << n1));
        var tmp = (n << 2) - 2;
        switch ((index >> tmp) & 3) {
            case 0 -> {
                tmp = (n1 << 2) + 1;
                if (((index >> tmp) & 1) != 0) {
                    dec4p4N1(index, n1, j, pos, p);
                } else {
                    dec4p4N1(index, n1, offset, pos, p);
                }
            }
            case 1 -> {
                tmp = (3 * n1) + 1;
                dec1pN1(index >> tmp, n1, offset, pos, p);
                dec3p3N1(index, n1, j, pos, p + 1);
            }
            case 2 -> {
                tmp = (n1 << 1) + 1;
                dec2p2N1(index >> tmp, n1, offset, pos, p);
                dec2p2N1(index, n1, j, pos, p + 2);
            }
            default -> {
                tmp = n1 + 1;
                dec3p3N1(index >> tmp, n1, offset, pos, p);
                dec1pN1(index, n1, j, pos, p + 3);
            }
        }
    }

    /**
     * Decodes five pulses coded on {@code 5N} bits.
     *
     * @param index the coded pulse index
     * @param n the number of position bits per pulse
     * @param offset the position offset
     * @param pos the positions
     * @param p the index of the first decoded position
     */
    private static void dec5p5N(int index, int n, int offset, short[] pos, int p) {
        var n1 = (short) (n - 1);
        var j = add16(offset, shl16((short) 1, n1));
        var tmp = (n << 1) + 1;
        var idx = index >> tmp;
        tmp = (5 * n) - 1;
        if (((index >> tmp) & 1) != 0) {
            dec3p3N1(idx, n1, j, pos, p);
        } else {
            dec3p3N1(idx, n1, offset, pos, p);
        }
        dec2p2N1(index, n, offset, pos, p + 3);
    }

    /**
     * Decodes six pulses coded on {@code 6N - 2} bits.
     *
     * @param index the coded pulse index
     * @param n the number of position bits per pulse
     * @param offset the position offset
     * @param pos the positions
     * @param p the index of the first decoded position
     */
    private static void dec6p6N2(int index, int n, int offset, short[] pos, int p) {
        var n1 = (short) (n - 1);
        var j = (short) (offset + (1 << n1));
        var offsetA = j;
        var offsetB = j;
        if (((index >> (6 * n - 5)) & 1) == 0) {
            offsetA = (short) offset;
        } else {
            offsetB = (short) offset;
        }
        switch ((index >> (6 * n - 4)) & 3) {
            case 0 -> {
                dec5p5N(index >> n, n1, offsetA, pos, p);
                dec1pN1(index, n1, offsetA, pos, p + 5);
            }
            case 1 -> {
                dec5p5N(index >> n, n1, offsetA, pos, p);
                dec1pN1(index, n1, offsetB, pos, p + 5);
            }
            case 2 -> {
                dec4p4N(index >> (2 * n1 + 1), n1, offsetA, pos, p);
                dec2p2N1(index, n1, offsetB, pos, p + 4);
            }
            default -> {
                dec3p3N1(index >> (3 * n1 + 1), n1, offset, pos, p);
                dec3p3N1(index, n1, j, pos, p + 3);
            }
        }
    }

    /**
     * Initializes the gain decoder memory.
     *
     * @param mem the 23-value gain memory
     */
    static void initGain(short[] mem) {
        mem[0] = -14336;
        mem[1] = -14336;
        mem[2] = -14336;
        mem[3] = -14336;
        java.util.Arrays.fill(mem, 4, 22, (short) 0);
        mem[22] = 21845;
    }


    /**
     * Decodes the pitch and code gains of a subframe, or conceals them for a bad frame.
     *
     * <p>The memory holds the past quantized energies ({@code mem[0..3]}), the past pitch and
     * code gains ({@code mem[4]}, {@code mem[5]}), the previous good code gain ({@code mem[6]})
     * and the pitch, code and second pitch gain histories ({@code mem[7..11]},
     * {@code mem[12..16]}, {@code mem[17..21]}).
     *
     * @implNote This implementation shifts the three gain histories by one entry per good
     * subframe and updates the predictor of a bad frame with the mean of the four past quantized
     * energies, shifting all four, as TS 26.173 specifies; opencore's history update moves only
     * the oldest entry, so the medians of its concealment see stale gains, and it uses half
     * that mean and drops an energy instead of shifting it.
     *
     * @param index the gain index
     * @param nbits the number of bits of the index, 6 or 7
     * @param code the innovation
     * @param codeOff the offset of the innovation
     * @param lSubfr the subframe length
     * @param bfi whether the frame is bad
     * @param prevBfi whether the previous frame was bad
     * @param state the error state, 0 to 6
     * @param unusableFrame whether the frame carries no usable data
     * @param vadHist the number of consecutive frames without voice activity
     * @param mem the 23-value gain memory
     * @return the packed pair: the code gain (Q16) in the low half and the pitch gain (Q14) as
     * the exponent, see {@link WbMath#pack(int, int)}
     */
    static long decGain(int index, int nbits, short[] code, int codeOff, int lSubfr, boolean bfi, int prevBfi, int state,
                        boolean unusableFrame, int vadHist, short[] mem) {
        var packed = dotProduct12(code, codeOff, code, codeOff, lSubfr);
        var exp = exponent(packed) - 24;
        packed = oneOvSqrtNorm(value(packed), exp);
        var gcodeInov = (short) (shl32(value(packed), exponent(packed) - 3) >> 16);
        short gainPit;
        int gainCod;
        if (bfi) {
            var tmp = WbPitchOps.median5(mem, 7 + 2);
            mem[4] = tmp;
            if (mem[4] > 15565) {
                mem[4] = 15565;
            }
            gainPit = unusableFrame ? mult(PDOWN_UNUSABLE[state], mem[4]) : mult(PDOWN_USABLE[state], mem[4]);
            tmp = WbPitchOps.median5(mem, 12 + 2);
            if (vadHist > 2) {
                mem[5] = tmp;
            } else {
                mem[5] = unusableFrame ? mult(CDOWN_UNUSABLE[state], tmp) : mult(CDOWN_USABLE[state], tmp);
            }
            var sum = mul(mem[0], 8192);
            sum = mac(sum, mem[1], 8192);
            sum = mac(sum, mem[2], 8192);
            sum = mac(sum, mem[3], 8192);
            var quaEner = sub16(sum >> 16, 3072);
            if (quaEner < -14336) {
                quaEner = -14336;
            }
            mem[3] = mem[2];
            mem[2] = mem[1];
            mem[1] = mem[0];
            mem[0] = quaEner;
            for (var i = 1; i < 5; i++) {
                mem[12 + i - 1] = mem[12 + i];
                mem[7 + i - 1] = mem[7 + i];
            }
            mem[12 + 4] = mem[5];
            mem[7 + 4] = mem[4];
            gainCod = mul(mem[5], gcodeInov);
            return pack(gainCod, gainPit);
        }
        var sum = 30 << 16;
        sum = shl32(sum, 8);
        sum = mac(sum, WbCodecTables.PRED[0], mem[0]);
        sum = mac(sum, WbCodecTables.PRED[1], mem[1]);
        sum = mac(sum, WbCodecTables.PRED[2], mem[2]);
        sum = mac(sum, WbCodecTables.PRED[3], mem[3]);
        var gcode0 = (short) (sum >> 16);
        sum = (gcode0 * 5443) >> 7;
        var dpf = toDpf(sum);
        var expGcode0 = (short) (dpf >> 16);
        var frac = (short) dpf;
        gcode0 = (short) powerOf2(14, frac);
        expGcode0 -= 14;
        var table = nbits == 6 ? WbCodecTables.GAIN_6B : WbCodecTables.GAIN_7B;
        gainPit = table[index << 1];
        var gCode = table[(index << 1) + 1];
        sum = mul(gCode, gcode0);
        sum = shl32(sum, expGcode0 + 4);
        gainCod = sum;
        if (prevBfi == 1) {
            sum = mul(mem[6], 5120);
            if (gainCod > sum && gainCod > 6553600) {
                gainCod = sum;
            }
        }
        mem[5] = round(shl32(gainCod, 3));
        mem[4] = gainPit;
        mem[6] = mem[5];
        for (var i = 1; i < 5; i++) {
            mem[12 + i - 1] = mem[12 + i];
            mem[7 + i - 1] = mem[7 + i];
            mem[17 + i - 1] = mem[17 + i];
        }
        mem[12 + 4] = mem[5];
        mem[7 + 4] = mem[4];
        mem[17 + 4] = mem[4];
        dpf = toDpf(gainCod);
        sum = mul32By16((short) (dpf >> 16), (short) dpf, gcodeInov);
        gainCod = shl32(sum, 3);
        mem[3] = mem[2];
        mem[2] = mem[1];
        mem[1] = mem[0];
        var log = log2(gCode);
        var e = (short) ((log >> 16) - 11);
        sum = mul32By16(e, (short) log, 24660);
        mem[0] = (short) (sum >> 3);
        return pack(gainCod, gainPit);
    }

    /**
     * Conceals or validates the pitch lag of a bad frame from the lag and gain histories.
     *
     * @param gainHist the pitch gain history of 5 values
     * @param gainOff the offset of the pitch gain history
     * @param lagHist the lag history of 5 values, newest first
     * @param t0 the received or default lag
     * @param oldT0 the previous lag
     * @param seed the holder of the random seed
     * @param seedIndex the index of the seed in its holder
     * @param unusableFrame whether the frame carries no usable data
     * @param lagHist2 a scratch array of 5 values
     * @return the concealed lag
     */
    static short lagConceal(short[] gainHist, int gainOff, short[] lagHist, short t0, short oldT0, short[] seed,
                            int seedIndex, boolean unusableFrame, short[] lagHist2) {
        var lastGain = gainHist[gainOff + 4];
        var secLastGain = gainHist[gainOff + 3];
        var lastLag = lagHist[0];
        var minLag = lagHist[0];
        var maxLag = lagHist[0];
        for (var i = 1; i < L_LTPHIST; i++) {
            if (lagHist[i] < minLag) {
                minLag = lagHist[i];
            }
            if (lagHist[i] > maxLag) {
                maxLag = lagHist[i];
            }
        }
        var minGain = gainHist[gainOff];
        for (var i = 1; i < L_LTPHIST; i++) {
            if (gainHist[gainOff + i] < minGain) {
                minGain = gainHist[gainOff + i];
            }
        }
        var lagDif = sub16(maxLag, minLag);
        if (unusableFrame) {
            if (minGain > 8192 && lagDif < 10) {
                t0 = oldT0;
            } else if (lastGain > 8192 && secLastGain > 8192) {
                t0 = lagHist[0];
            } else {
                t0 = randomLag(lagHist, seed, seedIndex, lagHist2);
            }
            return clampLag(t0, minLag, maxLag);
        }
        short meanLag = 0;
        for (var i = 0; i < L_LTPHIST; i++) {
            meanLag = add16(meanLag, lagHist[i]);
        }
        meanLag = mult(meanLag, 6554);
        var tmp = (short) (t0 - maxLag);
        var tmp2 = (short) (t0 - lastLag);
        if (lagDif < 10 && t0 > minLag - 5 && tmp < 5) {
            return t0;
        }
        if (lastGain > 8192 && secLastGain > 8192 && tmp2 + 10 > 0 && tmp2 < 10) {
            return t0;
        }
        if (minGain < 6554 && lastGain == minGain && t0 > minLag && t0 < maxLag) {
            return t0;
        }
        if (lagDif < 70 && t0 > minLag && t0 < maxLag) {
            return t0;
        }
        if (t0 > meanLag && t0 < maxLag) {
            return t0;
        }
        if (minGain > 8192 && lagDif < 10) {
            t0 = lagHist[0];
        } else if (lastGain > 8192 && secLastGain > 8192) {
            t0 = lagHist[0];
        } else {
            t0 = randomLag(lagHist, seed, seedIndex, lagHist2);
        }
        return clampLag(t0, minLag, maxLag);
    }

    /**
     * Draws a lag around the mean of the three largest lags of the history.
     *
     * @param lagHist the lag history
     * @param seed the holder of the random seed
     * @param seedIndex the index of the seed in its holder
     * @param lagHist2 a scratch array of 5 values
     * @return the lag
     */
    private static short randomLag(short[] lagHist, short[] seed, int seedIndex, short[] lagHist2) {
        System.arraycopy(lagHist, 0, lagHist2, 0, L_LTPHIST);
        for (var i = 0; i < L_LTPHIST; i++) {
            var x = lagHist2[i];
            var k = i - 1;
            for (; k >= 0; k--) {
                if (x < lagHist2[k]) {
                    lagHist2[k + 1] = lagHist2[k];
                } else {
                    break;
                }
            }
            lagHist2[k + 1] = x;
        }
        var lagDif = sub16(lagHist2[4], lagHist2[2]);
        if (lagDif > 40) {
            lagDif = 40;
        }
        var d = noise(seed, seedIndex);
        var d2 = mult((short) (lagDif >> 1), d);
        var tmp = add16(add16(lagHist2[2], lagHist2[3]), lagHist2[4]);
        return add16(mult(tmp, 10923), d2);
    }

    /**
     * Clamps a lag to the range of the history.
     *
     * @param t0 the lag
     * @param minLag the smallest lag of the history
     * @param maxLag the largest lag of the history
     * @return the clamped lag
     */
    private static short clampLag(short t0, short minLag, short maxLag) {
        if (t0 > maxLag) {
            t0 = maxLag;
        }
        if (t0 < minLag) {
            t0 = minLag;
        }
        return t0;
    }
}
