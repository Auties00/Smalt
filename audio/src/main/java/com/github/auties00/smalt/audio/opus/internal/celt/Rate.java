/* Ports libopus 1.5.2 (celt/rate.h, celt/rate.c). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Computes the CELT bit allocation: how many bits each band receives for its PVQ shape and its
 * fine energy (RFC 6716 section 4.3.3).
 *
 * <p>The allocation is part of the bitstream: the encoder and the decoder run the same
 * computation, and the few decisions that are signaled (band skipping, intensity, dual stereo)
 * are coded inside it.
 *
 * @implNote This implementation omits the custom-mode cache construction.
 */
public final class Rate {
    /**
     * The number of pseudo-pulse levels of the pulse cache.
     */
    public static final int MAX_PSEUDO = 40;

    /**
     * The base-2 logarithm of {@link #MAX_PSEUDO}, rounded up.
     */
    public static final int LOG_MAX_PSEUDO = 6;

    /**
     * The largest number of fine energy bits per band and channel.
     */
    public static final int MAX_FINE_BITS = 8;

    /**
     * The offset of the fine energy allocation, in 1/8 bits.
     */
    public static final int FINE_OFFSET = 21;

    /**
     * The offset of the theta allocation, in 1/8 bits.
     */
    public static final int QTHETA_OFFSET = 4;

    /**
     * The offset of the theta allocation for two-phase stereo, in 1/8 bits.
     */
    public static final int QTHETA_OFFSET_TWOPHASE = 16;

    /**
     * The number of bisection steps of the allocation interpolation.
     */
    private static final int ALLOC_STEPS = 6;

    /**
     * The cost in 1/8 bits of coding the intensity band for each number of bands
     * ({@code LOG2_FRAC_TABLE}).
     */
    private static final short[] LOG2_FRAC_TABLE = {
            0,
            8, 13,
            16, 19, 21, 23,
            24, 26, 27, 28, 29, 30, 31, 32,
            32, 33, 34, 34, 35, 36, 36, 37, 37
    };

    private Rate() {
        throw new AssertionError();
    }

    /**
     * Returns the number of pulses of a pseudo-pulse level ({@code get_pulses}).
     *
     * @param i the pseudo-pulse level
     * @return the number of pulses
     */
    public static int getPulses(int i) {
        return i < 8 ? i : (8 + (i & 7)) << ((i >> 3) - 1);
    }

    /**
     * Returns the pseudo-pulse level whose cost is closest to a bit budget ({@code bits2pulses}).
     *
     * @param m the mode
     * @param band the band index
     * @param LM the frame size shift, possibly -1 for a split band
     * @param bits the budget in 1/8 bits
     * @return the pseudo-pulse level
     */
    public static int bits2pulses(CeltMode m, int band, int LM, int bits) {
        LM++;
        var cache = m.cache.bits;
        var c = m.cache.index[LM * m.nbEBands + band];
        var lo = 0;
        int hi = cache[c];
        bits--;
        for (var i = 0; i < LOG_MAX_PSEUDO; i++) {
            var mid = (lo + hi + 1) >> 1;
            if (cache[c + mid] >= bits) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        if (bits - (lo == 0 ? -1 : cache[c + lo]) <= cache[c + hi] - bits) {
            return lo;
        } else {
            return hi;
        }
    }

    /**
     * Returns the cost in 1/8 bits of a pseudo-pulse level ({@code pulses2bits}).
     *
     * @param m the mode
     * @param band the band index
     * @param LM the frame size shift, possibly -1 for a split band
     * @param pulses the pseudo-pulse level
     * @return the cost
     */
    public static int pulses2bits(CeltMode m, int band, int LM, int pulses) {
        LM++;
        var c = m.cache.index[LM * m.nbEBands + band];
        return pulses == 0 ? 0 : m.cache.bits[c + pulses] + 1;
    }

    /**
     * Interpolates between two allocation vectors, decides band skipping, intensity and dual
     * stereo, and splits the bits of each band between PVQ and fine energy
     * ({@code interp_bits2pulses}).
     *
     * @param m               the mode
     * @param start           the first coded band
     * @param end             the end band
     * @param skipStart       the first band that may be skipped
     * @param bits1           the lower allocation vector
     * @param bits2           the increment towards the upper allocation vector
     * @param thresh          the minimum useful allocation of each band
     * @param cap             the maximum useful allocation of each band
     * @param total           the total budget in 1/8 bits
     * @param balanceOut      receives the remaining balance at index 0
     * @param skipRsv         the bits reserved for the skip flag
     * @param intensity       holds the intensity band at index 0, read by the encoder and
     *                        written by both
     * @param intensityRsv    the bits reserved for the intensity band
     * @param dualStereo      holds the dual stereo flag at index 0, read by the encoder and
     *                        written by both
     * @param dualStereoRsv   the bits reserved for the dual stereo flag
     * @param bits            receives the PVQ allocation of each band
     * @param ebits           receives the fine energy bits of each band
     * @param finePriority    receives the fine energy priority of each band
     * @param C               the number of channels
     * @param LM              the frame size shift
     * @param ec              the range coder
     * @param encode          nonzero in the encoder
     * @param prev            the number of bands coded in the previous frame
     * @param signalBandwidth the band holding the signal bandwidth, for the skip decision
     * @return the number of coded bands
     */
    private static int interpBits2pulses(CeltMode m, int start, int end, int skipStart, int[] bits1, int[] bits2,
                                         int[] thresh, int[] cap, int total, int[] balanceOut, int skipRsv,
                                         int[] intensity, int intensityRsv, int[] dualStereo, int dualStereoRsv,
                                         int[] bits, int[] ebits, int[] finePriority, int C, int LM,
                                         EntropyCoder ec, int encode, int prev, int signalBandwidth) {
        int psum;
        int lo, hi;
        int i, j;
        int logM;
        int stereo;
        int codedBands;
        int allocFloor;
        int left, percoeff;
        int done;
        int balance;
        var eBands = m.eBands;
        allocFloor = C << EntropyCoder.BITRES;
        stereo = C > 1 ? 1 : 0;
        logM = LM << EntropyCoder.BITRES;
        lo = 0;
        hi = 1 << ALLOC_STEPS;
        for (i = 0; i < ALLOC_STEPS; i++) {
            var mid = (lo + hi) >> 1;
            psum = 0;
            done = 0;
            for (j = end; j-- > start; ) {
                var tmp = bits1[j] + (mid * bits2[j] >> ALLOC_STEPS);
                if (tmp >= thresh[j] || done != 0) {
                    done = 1;
                    psum += Math.min(tmp, cap[j]);
                } else {
                    if (tmp >= allocFloor) {
                        psum += allocFloor;
                    }
                }
            }
            if (psum > total) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        psum = 0;
        done = 0;
        for (j = end; j-- > start; ) {
            var tmp = bits1[j] + (lo * bits2[j] >> ALLOC_STEPS);
            if (tmp < thresh[j] && done == 0) {
                if (tmp >= allocFloor) {
                    tmp = allocFloor;
                } else {
                    tmp = 0;
                }
            } else {
                done = 1;
            }
            tmp = Math.min(tmp, cap[j]);
            bits[j] = tmp;
            psum += tmp;
        }
        for (codedBands = end; ; codedBands--) {
            int bandWidth;
            int bandBits;
            int rem;
            j = codedBands - 1;
            if (j <= skipStart) {
                total += skipRsv;
                break;
            }
            left = total - psum;
            percoeff = Integer.divideUnsigned(left, eBands[codedBands] - eBands[start]);
            left -= (eBands[codedBands] - eBands[start]) * percoeff;
            rem = Math.max(left - (eBands[j] - eBands[start]), 0);
            bandWidth = eBands[codedBands] - eBands[j];
            bandBits = bits[j] + percoeff * bandWidth + rem;
            if (bandBits >= Math.max(thresh[j], allocFloor + (1 << EntropyCoder.BITRES))) {
                if (encode != 0) {
                    int depthThreshold;
                    if (codedBands > 17) {
                        depthThreshold = j < prev ? 7 : 9;
                    } else {
                        depthThreshold = 0;
                    }
                    var enc = (RangeEncoder) ec;
                    if (codedBands <= start + 2 || (bandBits > (depthThreshold * bandWidth << LM << EntropyCoder.BITRES) >> 4
                            && j <= signalBandwidth)) {
                        enc.encBitLogp(1, 1);
                        break;
                    }
                    enc.encBitLogp(0, 1);
                } else if (((RangeDecoder) ec).decBitLogp(1) != 0) {
                    break;
                }
                psum += 1 << EntropyCoder.BITRES;
                bandBits -= 1 << EntropyCoder.BITRES;
            }
            psum -= bits[j] + intensityRsv;
            if (intensityRsv > 0) {
                intensityRsv = LOG2_FRAC_TABLE[j - start];
            }
            psum += intensityRsv;
            if (bandBits >= allocFloor) {
                psum += allocFloor;
                bits[j] = allocFloor;
            } else {
                bits[j] = 0;
            }
        }
        if (intensityRsv > 0) {
            if (encode != 0) {
                intensity[0] = Math.min(intensity[0], codedBands);
                ((RangeEncoder) ec).encUint(intensity[0] - start, codedBands + 1 - start);
            } else {
                intensity[0] = start + ((RangeDecoder) ec).decUint(codedBands + 1 - start);
            }
        } else {
            intensity[0] = 0;
        }
        if (intensity[0] <= start) {
            total += dualStereoRsv;
            dualStereoRsv = 0;
        }
        if (dualStereoRsv > 0) {
            if (encode != 0) {
                ((RangeEncoder) ec).encBitLogp(dualStereo[0], 1);
            } else {
                dualStereo[0] = ((RangeDecoder) ec).decBitLogp(1);
            }
        } else {
            dualStereo[0] = 0;
        }
        left = total - psum;
        percoeff = Integer.divideUnsigned(left, eBands[codedBands] - eBands[start]);
        left -= (eBands[codedBands] - eBands[start]) * percoeff;
        for (j = start; j < codedBands; j++) {
            bits[j] += percoeff * (eBands[j + 1] - eBands[j]);
        }
        for (j = start; j < codedBands; j++) {
            var tmp = Math.min(left, eBands[j + 1] - eBands[j]);
            bits[j] += tmp;
            left -= tmp;
        }
        balance = 0;
        for (j = start; j < codedBands; j++) {
            int N0, N, den;
            int offset;
            int NClogN;
            int excess, bit;
            N0 = eBands[j + 1] - eBands[j];
            N = N0 << LM;
            bit = bits[j] + balance;
            if (N > 1) {
                excess = Math.max(bit - cap[j], 0);
                bits[j] = bit - excess;
                den = C * N + ((C == 2 && N > 2 && dualStereo[0] == 0 && j < intensity[0]) ? 1 : 0);
                NClogN = den * (m.logN[j] + logM);
                offset = (NClogN >> 1) - den * FINE_OFFSET;
                if (N == 2) {
                    offset += den << EntropyCoder.BITRES >> 2;
                }
                if (bits[j] + offset < den * 2 << EntropyCoder.BITRES) {
                    offset += NClogN >> 2;
                } else if (bits[j] + offset < den * 3 << EntropyCoder.BITRES) {
                    offset += NClogN >> 3;
                }
                ebits[j] = Math.max(0, bits[j] + offset + (den << (EntropyCoder.BITRES - 1)));
                ebits[j] = Integer.divideUnsigned(ebits[j], den) >>> EntropyCoder.BITRES;
                if (C * ebits[j] > (bits[j] >> EntropyCoder.BITRES)) {
                    ebits[j] = bits[j] >> stereo >> EntropyCoder.BITRES;
                }
                ebits[j] = Math.min(ebits[j], MAX_FINE_BITS);
                finePriority[j] = ebits[j] * (den << EntropyCoder.BITRES) >= bits[j] + offset ? 1 : 0;
                bits[j] -= C * ebits[j] << EntropyCoder.BITRES;
            } else {
                excess = Math.max(0, bit - (C << EntropyCoder.BITRES));
                bits[j] = bit - excess;
                ebits[j] = 0;
                finePriority[j] = 1;
            }
            if (excess > 0) {
                int extraFine;
                int extraBits;
                extraFine = Math.min(excess >> (stereo + EntropyCoder.BITRES), MAX_FINE_BITS - ebits[j]);
                ebits[j] += extraFine;
                extraBits = extraFine * C << EntropyCoder.BITRES;
                finePriority[j] = extraBits >= excess - balance ? 1 : 0;
                excess -= extraBits;
            }
            balance = excess;
        }
        balanceOut[0] = balance;
        for (; j < end; j++) {
            ebits[j] = bits[j] >> stereo >> EntropyCoder.BITRES;
            bits[j] = 0;
            finePriority[j] = ebits[j] < 1 ? 1 : 0;
        }
        return codedBands;
    }

    /**
     * Computes the bit allocation of a frame ({@code clt_compute_allocation}).
     *
     * @param m               the mode
     * @param start           the first coded band
     * @param end             the end band
     * @param offsets         the dynamic allocation boost of each band, in 1/8 bits
     * @param cap             the maximum useful allocation of each band
     * @param allocTrim       the allocation tilt, 0 to 10
     * @param intensity       holds the intensity band at index 0
     * @param dualStereo      holds the dual stereo flag at index 0
     * @param total           the total budget in 1/8 bits
     * @param balance         receives the remaining balance at index 0
     * @param pulses          receives the PVQ allocation of each band in 1/8 bits
     * @param ebits           receives the fine energy bits of each band
     * @param finePriority    receives the fine energy priority of each band
     * @param C               the number of channels
     * @param LM              the frame size shift
     * @param ec              the range coder
     * @param encode          nonzero in the encoder
     * @param prev            the number of bands coded in the previous frame
     * @param signalBandwidth the band holding the signal bandwidth
     * @param scratch         four scratch arrays of at least {@code nbEBands} ints
     * @return the number of coded bands
     */
    public static int cltComputeAllocation(CeltMode m, int start, int end, int[] offsets, int[] cap, int allocTrim,
                                           int[] intensity, int[] dualStereo, int total, int[] balance, int[] pulses,
                                           int[] ebits, int[] finePriority, int C, int LM, EntropyCoder ec,
                                           int encode, int prev, int signalBandwidth, int[][] scratch) {
        int lo, hi, len, j;
        int codedBands;
        int skipStart;
        int skipRsv;
        int intensityRsv;
        int dualStereoRsv;
        var bits1 = scratch[0];
        var bits2 = scratch[1];
        var thresh = scratch[2];
        var trimOffset = scratch[3];
        var eBands = m.eBands;
        total = Math.max(total, 0);
        len = m.nbEBands;
        skipStart = start;
        skipRsv = total >= 1 << EntropyCoder.BITRES ? 1 << EntropyCoder.BITRES : 0;
        total -= skipRsv;
        intensityRsv = dualStereoRsv = 0;
        if (C == 2) {
            intensityRsv = LOG2_FRAC_TABLE[end - start];
            if (intensityRsv > total) {
                intensityRsv = 0;
            } else {
                total -= intensityRsv;
                dualStereoRsv = total >= 1 << EntropyCoder.BITRES ? 1 << EntropyCoder.BITRES : 0;
                total -= dualStereoRsv;
            }
        }
        for (j = start; j < end; j++) {
            thresh[j] = Math.max(C << EntropyCoder.BITRES,
                    (3 * (eBands[j + 1] - eBands[j]) << LM << EntropyCoder.BITRES) >> 4);
            trimOffset[j] = C * (eBands[j + 1] - eBands[j]) * (allocTrim - 5 - LM) * (end - j - 1)
                    * (1 << (LM + EntropyCoder.BITRES)) >> 6;
            if ((eBands[j + 1] - eBands[j]) << LM == 1) {
                trimOffset[j] -= C << EntropyCoder.BITRES;
            }
        }
        lo = 1;
        hi = m.nbAllocVectors - 1;
        do {
            var done = 0;
            var psum = 0;
            var mid = (lo + hi) >> 1;
            for (j = end; j-- > start; ) {
                int bitsj;
                var N = eBands[j + 1] - eBands[j];
                bitsj = C * N * m.allocVectors[mid * len + j] << LM >> 2;
                if (bitsj > 0) {
                    bitsj = Math.max(0, bitsj + trimOffset[j]);
                }
                bitsj += offsets[j];
                if (bitsj >= thresh[j] || done != 0) {
                    done = 1;
                    psum += Math.min(bitsj, cap[j]);
                } else {
                    if (bitsj >= C << EntropyCoder.BITRES) {
                        psum += C << EntropyCoder.BITRES;
                    }
                }
            }
            if (psum > total) {
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        } while (lo <= hi);
        hi = lo--;
        for (j = start; j < end; j++) {
            int bits1j, bits2j;
            var N = eBands[j + 1] - eBands[j];
            bits1j = C * N * m.allocVectors[lo * len + j] << LM >> 2;
            bits2j = hi >= m.nbAllocVectors ? cap[j] : C * N * m.allocVectors[hi * len + j] << LM >> 2;
            if (bits1j > 0) {
                bits1j = Math.max(0, bits1j + trimOffset[j]);
            }
            if (bits2j > 0) {
                bits2j = Math.max(0, bits2j + trimOffset[j]);
            }
            if (lo > 0) {
                bits1j += offsets[j];
            }
            bits2j += offsets[j];
            if (offsets[j] > 0) {
                skipStart = j;
            }
            bits2j = Math.max(0, bits2j - bits1j);
            bits1[j] = bits1j;
            bits2[j] = bits2j;
        }
        codedBands = interpBits2pulses(m, start, end, skipStart, bits1, bits2, thresh, cap, total, balance, skipRsv,
                intensity, intensityRsv, dualStereo, dualStereoRsv, pulses, ebits, finePriority, C, LM, ec, encode,
                prev, signalBandwidth);
        return codedBands;
    }
}
