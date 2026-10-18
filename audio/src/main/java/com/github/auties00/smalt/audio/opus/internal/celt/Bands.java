/* Ports libopus 1.5.2 (celt/bands.c). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Normalizes, quantizes and reconstructs the CELT band shapes: band energies, the recursive
 * band splitting with theta coding, stereo coupling, spectral folding, spreading decisions and
 * anti-collapse (RFC 6716 sections 4.3.4 to 4.3.7).
 *
 * <p>The static helpers are pure functions; an instance holds the scratch buffers of
 * {@link #quantAllBands}, so each encoder or decoder owns one and never shares it between
 * threads.
 *
 * @implNote This implementation follows the current folding rules (without {@code DISABLE_UPDATE_DRAFT}) and holds the
 * stack arrays of the reference in this object; pointers become an array and an offset.
 */
public final class Bands {
    /**
     * The ordering of Hadamard blocks for strides 2, 4, 8 and 16 ({@code ordery_table}).
     */
    private static final int[] ORDERY_TABLE = {
            1, 0,
            3, 0, 2, 1,
            7, 0, 4, 3, 6, 1, 5, 2,
            15, 0, 8, 7, 12, 3, 11, 4, 14, 1, 9, 6, 13, 2, 10, 5
    };

    /**
     * The fractional part of {@code 2^(i/8)} in Q14 ({@code exp2_table8}).
     */
    private static final short[] EXP2_TABLE8 = {16384, 17866, 19483, 21247, 23170, 25267, 27554, 30048};

    /**
     * The fill-mask compaction applied per recombination step ({@code bit_interleave_table}).
     */
    private static final short[] BIT_INTERLEAVE_TABLE = {0, 1, 1, 1, 2, 3, 3, 3, 2, 3, 3, 3, 2, 3, 3, 3};

    /**
     * The collapse-mask expansion undoing a recombination step ({@code bit_deinterleave_table}).
     */
    private static final short[] BIT_DEINTERLEAVE_TABLE = {
            0x00, 0x03, 0x0C, 0x0F, 0x30, 0x33, 0x3C, 0x3F,
            0xC0, 0xC3, 0xCC, 0xCF, 0xF0, 0xF3, 0xFC, 0xFF
    };

    /**
     * The folding memory of both channels ({@code _norm}).
     */
    private final float[] norm;

    /**
     * The encoder's folding scratch when resynthesizing ({@code _lowband_scratch}).
     */
    private final float[] lowbandScratchBuf;

    /**
     * The saved first channel of a theta rate-distortion trial ({@code X_save}).
     */
    private final float[] xSave;

    /**
     * The saved second channel of a theta rate-distortion trial ({@code Y_save}).
     */
    private final float[] ySave;

    /**
     * The first channel after the rounded-down theta trial ({@code X_save2}).
     */
    private final float[] xSave2;

    /**
     * The second channel after the rounded-down theta trial ({@code Y_save2}).
     */
    private final float[] ySave2;

    /**
     * The folding memory after the rounded-down theta trial ({@code norm_save2}).
     */
    private final float[] normSave2;

    /**
     * The scratch of the Hadamard (de)interleaving.
     */
    private final float[] hadamardTmp;

    /**
     * The pulse vector scratch of the PVQ search.
     */
    private final int[] iy;

    /**
     * The doubled pulse magnitudes scratch of the PVQ search.
     */
    private final float[] pvqY;

    /**
     * The sign scratch of the PVQ search.
     */
    private final int[] signx;

    /**
     * The saved range coder bytes of a theta rate-distortion trial.
     */
    private final byte[] bytesSave = new byte[1275];

    /**
     * The active band context.
     */
    private final BandCtx ctx = new BandCtx();

    /**
     * The band context saved before a theta trial.
     */
    private final BandCtx ctxSave = new BandCtx();

    /**
     * The band context after the rounded-down theta trial.
     */
    private final BandCtx ctxSave2 = new BandCtx();

    /**
     * The split parameters computed by {@link #computeTheta}.
     */
    private final SplitCtx sctx = new SplitCtx();

    /**
     * The range coder saved before a theta trial.
     */
    private final RangeEncoder ecSave = new RangeEncoder();

    /**
     * The range coder after the rounded-down theta trial.
     */
    private final RangeEncoder ecSave2 = new RangeEncoder();

    /**
     * The output of {@link Pitch#dualInnerProd}.
     */
    private final float[] dual = new float[2];

    /**
     * The channel weights of a theta trial.
     */
    private final float[] weights = new float[2];

    /**
     * Creates the scratch space for a mode.
     *
     * @param m the mode
     */
    public Bands(CeltMode m) {
        var M = 1 << m.maxLM;
        var eBands = m.eBands;
        norm = new float[2 * M * eBands[m.nbEBands - 1]];
        var last = M * (eBands[m.nbEBands] - eBands[m.nbEBands - 1]);
        lowbandScratchBuf = new float[last];
        xSave = new float[last];
        ySave = new float[last];
        xSave2 = new float[last];
        ySave2 = new float[last];
        normSave2 = new float[last];
        hadamardTmp = new float[last];
        iy = new int[last + 3];
        pvqY = new float[last + 3];
        signx = new int[last + 3];
    }

    /**
     * Returns the index of the threshold interval containing {@code val}, with hysteresis around
     * the previous decision ({@code hysteresis_decision}).
     *
     * @param val        the value
     * @param thresholds the increasing thresholds
     * @param hysteresis the hysteresis of each threshold
     * @param N          the number of thresholds
     * @param prev       the previous decision
     * @return the decision
     */
    public static int hysteresisDecision(float val, float[] thresholds, float[] hysteresis, int N, int prev) {
        int i;
        for (i = 0; i < N; i++) {
            if (val < thresholds[i]) {
                break;
            }
        }
        if (i > prev && val < thresholds[prev] + hysteresis[prev]) {
            i = prev;
        }
        if (i < prev && val > thresholds[prev - 1] - hysteresis[prev - 1]) {
            i = prev;
        }
        return i;
    }

    /**
     * Advances the linear congruential generator used for noise filling ({@code celt_lcg_rand}).
     *
     * @param seed the unsigned seed
     * @return the next unsigned seed
     */
    public static int celtLcgRand(int seed) {
        return 1664525 * seed + 1013904223;
    }

    /**
     * Multiplies two Q15 fractions with the reference's 16-bit truncation ({@code FRAC_MUL16}).
     *
     * @param a the first factor, truncated to 16 bits
     * @param b the second factor, truncated to 16 bits
     * @return the rounded product
     */
    private static int fracMul16(int a, int b) {
        return (16384 + (short) a * (short) b) >> 15;
    }

    /**
     * Returns a bit-exact approximation of {@code cos(pi / 2 * x / 16384)} in Q15
     * ({@code bitexact_cos}).
     *
     * @param x the angle, 0 to 16384
     * @return the cosine, 1 to 32767
     */
    public static int bitexactCos(int x) {
        int tmp;
        short x2;
        tmp = (4096 + (short) x * (short) x) >> 13;
        x2 = (short) tmp;
        x2 = (short) ((32767 - x2) + fracMul16(x2, (-7651 + fracMul16(x2, (8277 + fracMul16(-626, x2))))));
        return (short) (1 + x2);
    }

    /**
     * Returns a bit-exact approximation of {@code 2048 * log2(isin / icos)}
     * ({@code bitexact_log2tan}).
     *
     * @param isin the sine, positive
     * @param icos the cosine, positive
     * @return the scaled logarithm of the tangent
     */
    public static int bitexactLog2tan(int isin, int icos) {
        var lc = EntropyCoder.ilog(icos);
        var ls = EntropyCoder.ilog(isin);
        icos <<= 15 - lc;
        isin <<= 15 - ls;
        return (ls - lc) * (1 << 11)
                + fracMul16(isin, fracMul16(isin, -2597) + 7932)
                - fracMul16(icos, fracMul16(icos, -2597) + 7932);
    }

    /**
     * Computes the amplitude (square root of the energy) of each band ({@code compute_band_energies}).
     *
     * @param m     the mode
     * @param X     the MDCT coefficients of all channels
     * @param bandE receives the amplitudes, {@code nbEBands} per channel
     * @param end   the end band
     * @param C     the number of channels
     * @param LM    the frame size shift
     */
    public static void computeBandEnergies(CeltMode m, float[] X, float[] bandE, int end, int C, int LM) {
        var eBands = m.eBands;
        var N = m.shortMdctSize << LM;
        if (C == 2) {
            // Two independent sequential sums per band, interleaved for instruction-level parallelism.
            for (var i = 0; i < end; i++) {
                var off0 = eBands[i] << LM;
                var off1 = N + off0;
                var len = (eBands[i + 1] - eBands[i]) << LM;
                var s0 = 0f;
                var s1 = 0f;
                for (var j = 0; j < len; j++) {
                    s0 = s0 + X[off0 + j] * X[off0 + j];
                    s1 = s1 + X[off1 + j] * X[off1 + j];
                }
                bandE[i] = MathOps.celtSqrt(1e-27f + s0);
                bandE[i + m.nbEBands] = MathOps.celtSqrt(1e-27f + s1);
            }
            return;
        }
        for (var i = 0; i < end; i++) {
            var off = eBands[i] << LM;
            var sum = 1e-27f + Pitch.celtInnerProd(X, off, X, off, (eBands[i + 1] - eBands[i]) << LM);
            bandE[i] = MathOps.celtSqrt(sum);
        }
    }

    /**
     * Normalizes each band to unit energy ({@code normalise_bands}).
     *
     * @param m     the mode
     * @param freq  the MDCT coefficients
     * @param X     receives the normalized coefficients
     * @param bandE the band amplitudes
     * @param end   the end band
     * @param C     the number of channels
     * @param M     the number of short MDCTs
     */
    public static void normalizeBands(CeltMode m, float[] freq, float[] X, float[] bandE, int end, int C, int M) {
        var eBands = m.eBands;
        var N = M * m.shortMdctSize;
        var c = 0;
        do {
            for (var i = 0; i < end; i++) {
                var g = 1.f / (1e-27f + bandE[i + c * m.nbEBands]);
                for (var j = M * eBands[i]; j < M * eBands[i + 1]; j++) {
                    X[j + c * N] = freq[j + c * N] * g;
                }
            }
        } while (++c < C);
    }

    /**
     * Scales the unit-energy bands back to their decoded energies ({@code denormalise_bands}).
     *
     * @param m          the mode
     * @param X          the normalized coefficients
     * @param xOff       the index of the first normalized coefficient
     * @param freq       receives the MDCT coefficients
     * @param freqOff    the index of the first MDCT coefficient
     * @param bandLogE   the decoded log-energies
     * @param bandLogOff the index of the first log-energy
     * @param start      the first band
     * @param end        the end band
     * @param M          the number of short MDCTs
     * @param downsample the output decimation factor
     * @param silence    nonzero for a silent frame
     */
    public static void denormalizeBands(CeltMode m, float[] X, int xOff, float[] freq, int freqOff, float[] bandLogE,
                                        int bandLogOff, int start, int end, int M, int downsample, int silence) {
        var eBands = m.eBands;
        var N = M * m.shortMdctSize;
        var bound = M * eBands[end];
        if (downsample != 1) {
            bound = Math.min(bound, N / downsample);
        }
        if (silence != 0) {
            bound = 0;
            start = end = 0;
        }
        var f = freqOff;
        var x = xOff + M * eBands[start];
        for (var i = 0; i < M * eBands[start]; i++) {
            freq[f++] = 0;
        }
        for (var i = start; i < end; i++) {
            var j = M * eBands[i];
            var bandEnd = M * eBands[i + 1];
            var lg = bandLogE[bandLogOff + i] + QuantBands.E_MEANS[i];
            var g = MathOps.celtExp2(MathOps.min(32.f, lg));
            do {
                freq[f++] = X[x++] * g;
            } while (++j < bandEnd);
        }
        java.util.Arrays.fill(freq, freqOff + bound, freqOff + N, 0);
    }

    /**
     * Fills the short blocks that received no pulses with noise at a level derived from the
     * previous energies, preventing energy collapse on transients ({@code anti_collapse}).
     *
     * @param m             the mode
     * @param X_            the normalized coefficients of all channels
     * @param collapseMasks the collapse mask of each band and channel
     * @param LM            the frame size shift
     * @param C             the number of channels
     * @param size          the number of coefficients per channel
     * @param start         the first band
     * @param end           the end band
     * @param logE          the current log-energies
     * @param prev1logE     the log-energies of the previous frame
     * @param prev2logE     the log-energies of the frame before
     * @param pulses        the PVQ allocation of each band
     * @param seed          the unsigned noise seed
     */
    public static void antiCollapse(CeltMode m, float[] X_, int[] collapseMasks, int LM, int C, int size, int start,
                                    int end, float[] logE, float[] prev1logE, float[] prev2logE, int[] pulses,
                                    int seed) {
        for (var i = start; i < end; i++) {
            int N0;
            float thresh, sqrt1;
            int depth;
            N0 = m.eBands[i + 1] - m.eBands[i];
            depth = Integer.divideUnsigned(1 + pulses[i], m.eBands[i + 1] - m.eBands[i]) >>> LM;
            thresh = .5f * MathOps.celtExp2(-.125f * depth);
            sqrt1 = MathOps.celtRsqrt(N0 << LM);
            var c = 0;
            do {
                float prev1;
                float prev2;
                float Ediff;
                float r;
                var renormalize = 0;
                prev1 = prev1logE[c * m.nbEBands + i];
                prev2 = prev2logE[c * m.nbEBands + i];
                if (C == 1) {
                    prev1 = MathOps.max(prev1, prev1logE[m.nbEBands + i]);
                    prev2 = MathOps.max(prev2, prev2logE[m.nbEBands + i]);
                }
                Ediff = logE[c * m.nbEBands + i] - MathOps.min(prev1, prev2);
                Ediff = MathOps.max(0, Ediff);
                r = 2.f * MathOps.celtExp2(-Ediff);
                if (LM == 3) {
                    r *= 1.41421356f;
                }
                r = MathOps.min(thresh, r);
                r = r * sqrt1;
                var x = c * size + (m.eBands[i] << LM);
                for (var k = 0; k < 1 << LM; k++) {
                    if ((collapseMasks[i * C + c] & 1 << k) == 0) {
                        for (var j = 0; j < N0; j++) {
                            seed = celtLcgRand(seed);
                            X_[x + (j << LM) + k] = (seed & 0x8000) != 0 ? r : -r;
                        }
                        renormalize = 1;
                    }
                }
                if (renormalize != 0) {
                    Vq.renormalizeVector(X_, x, N0 << LM, 1.0f);
                }
            } while (++c < C);
        }
    }

    /**
     * Computes the per-channel weights of the stereo distortion of a theta trial
     * ({@code compute_channel_weights}).
     *
     * @param Ex the amplitude of the first channel
     * @param Ey the amplitude of the second channel
     * @param w  receives the two weights
     */
    private static void computeChannelWeights(float Ex, float Ey, float[] w) {
        var minE = MathOps.min(Ex, Ey);
        Ex = Ex + minE / 3;
        Ey = Ey + minE / 3;
        w[0] = Ex;
        w[1] = Ey;
    }

    /**
     * Replaces the first channel by the energy-weighted mix of both, for intensity stereo
     * ({@code intensity_stereo}).
     *
     * @param m      the mode
     * @param X      the first channel
     * @param xOff   the index of its first sample
     * @param Y      the second channel
     * @param yOff   the index of its first sample
     * @param bandE  the band amplitudes
     * @param bandID the band
     * @param N      the band size
     */
    private static void intensityStereo(CeltMode m, float[] X, int xOff, float[] Y, int yOff, float[] bandE,
                                        int bandID, int N) {
        var left = bandE[bandID];
        var right = bandE[bandID + m.nbEBands];
        var norm = MathOps.EPSILON + MathOps.celtSqrt(MathOps.EPSILON + left * left + right * right);
        var a1 = left / norm;
        var a2 = right / norm;
        for (var j = 0; j < N; j++) {
            var l = X[xOff + j];
            var r = Y[yOff + j];
            X[xOff + j] = a1 * l + a2 * r;
        }
    }

    /**
     * Converts left and right to mid and side ({@code stereo_split}).
     *
     * @param X    the first channel; receives the mid
     * @param xOff the index of its first sample
     * @param Y    the second channel; receives the side
     * @param yOff the index of its first sample
     * @param N    the band size
     */
    private static void stereoSplit(float[] X, int xOff, float[] Y, int yOff, int N) {
        for (var j = 0; j < N; j++) {
            var l = .70710678f * X[xOff + j];
            var r = .70710678f * Y[yOff + j];
            X[xOff + j] = l + r;
            Y[yOff + j] = r - l;
        }
    }

    /**
     * Converts the decoded mid and side back to left and right with energy compensation
     * ({@code stereo_merge}).
     *
     * @param X    the mid; receives the left channel
     * @param xOff the index of its first sample
     * @param Y    the side; receives the right channel
     * @param yOff the index of its first sample
     * @param mid  the mid gain
     * @param N    the band size
     */
    private void stereoMerge(float[] X, int xOff, float[] Y, int yOff, float mid, int N) {
        Pitch.dualInnerProd(Y, yOff, X, xOff, Y, yOff, N, dual);
        var xp = dual[0];
        var side = dual[1];
        xp = mid * xp;
        var mid2 = mid;
        var El = mid2 * mid2 + side - 2 * xp;
        var Er = mid2 * mid2 + side + 2 * xp;
        if (Er < 6e-4f || El < 6e-4f) {
            System.arraycopy(X, xOff, Y, yOff, N);
            return;
        }
        var lgain = MathOps.celtRsqrt(El);
        var rgain = MathOps.celtRsqrt(Er);
        for (var j = 0; j < N; j++) {
            var l = mid * X[xOff + j];
            var r = Y[yOff + j];
            X[xOff + j] = lgain * (l - r);
            Y[yOff + j] = rgain * (l + r);
        }
    }

    /**
     * Decides how much to spread the pulses of this frame from the distribution of the
     * normalized coefficients, and updates the pre-filter tapset ({@code spreading_decision}).
     *
     * @param m              the mode
     * @param X              the normalized coefficients
     * @param average        holds the recursive average at index 0
     * @param lastDecision   the previous spreading decision
     * @param hfAverage      holds the high-frequency average at index 0
     * @param tapsetDecision holds the tapset decision at index 0
     * @param updateHf       nonzero to update the tapset decision
     * @param end            the end band
     * @param C              the number of channels
     * @param M              the number of short MDCTs
     * @param spreadWeight   the weight of each band
     * @return the spreading decision
     */
    public static int spreadingDecision(CeltMode m, float[] X, int[] average, int lastDecision, int[] hfAverage,
                                        int[] tapsetDecision, int updateHf, int end, int C, int M,
                                        int[] spreadWeight) {
        int i, c, N0;
        int sum = 0, nbBands = 0;
        var eBands = m.eBands;
        int decision;
        var hfSum = 0;
        N0 = M * m.shortMdctSize;
        if (M * (eBands[end] - eBands[end - 1]) <= 8) {
            return Celt.SPREAD_NONE;
        }
        c = 0;
        do {
            for (i = 0; i < end; i++) {
                int j, N, tmp;
                int t0 = 0, t1 = 0, t2 = 0;
                var x = M * eBands[i] + c * N0;
                N = M * (eBands[i + 1] - eBands[i]);
                if (N <= 8) {
                    continue;
                }
                for (j = 0; j < N; j++) {
                    var x2N = (X[x + j] * X[x + j]) * N;
                    if (x2N < 0.25f) {
                        t0++;
                    }
                    if (x2N < 0.0625f) {
                        t1++;
                    }
                    if (x2N < 0.015625f) {
                        t2++;
                    }
                }
                if (i > m.nbEBands - 4) {
                    hfSum += Integer.divideUnsigned(32 * (t1 + t0), N);
                }
                tmp = (2 * t2 >= N ? 1 : 0) + (2 * t1 >= N ? 1 : 0) + (2 * t0 >= N ? 1 : 0);
                sum += tmp * spreadWeight[i];
                nbBands += spreadWeight[i];
            }
        } while (++c < C);
        if (updateHf != 0) {
            if (hfSum != 0) {
                hfSum = Integer.divideUnsigned(hfSum, C * (4 - m.nbEBands + end));
            }
            hfAverage[0] = (hfAverage[0] + hfSum) >> 1;
            hfSum = hfAverage[0];
            if (tapsetDecision[0] == 2) {
                hfSum += 4;
            } else if (tapsetDecision[0] == 0) {
                hfSum -= 4;
            }
            if (hfSum > 22) {
                tapsetDecision[0] = 2;
            } else if (hfSum > 18) {
                tapsetDecision[0] = 1;
            } else {
                tapsetDecision[0] = 0;
            }
        }
        sum = Integer.divideUnsigned(sum << 8, nbBands);
        sum = (sum + average[0]) >> 1;
        average[0] = sum;
        sum = (3 * sum + (((3 - lastDecision) << 7) + 64) + 2) >> 2;
        if (sum < 80) {
            decision = Celt.SPREAD_AGGRESSIVE;
        } else if (sum < 256) {
            decision = Celt.SPREAD_NORMAL;
        } else if (sum < 384) {
            decision = Celt.SPREAD_LIGHT;
        } else {
            decision = Celt.SPREAD_NONE;
        }
        return decision;
    }

    /**
     * Reorders interleaved short blocks into consecutive blocks ({@code deinterleave_hadamard}).
     *
     * @param X        the band
     * @param xOff     the index of its first sample
     * @param N0       the samples per block
     * @param stride   the number of blocks
     * @param hadamard nonzero to use the Hadamard block order
     */
    private void deinterleaveHadamard(float[] X, int xOff, int N0, int stride, int hadamard) {
        var tmp = hadamardTmp;
        var N = N0 * stride;
        if (hadamard != 0) {
            var ordery = stride - 2;
            for (var i = 0; i < stride; i++) {
                for (var j = 0; j < N0; j++) {
                    tmp[ORDERY_TABLE[ordery + i] * N0 + j] = X[xOff + j * stride + i];
                }
            }
        } else {
            for (var i = 0; i < stride; i++) {
                for (var j = 0; j < N0; j++) {
                    tmp[i * N0 + j] = X[xOff + j * stride + i];
                }
            }
        }
        System.arraycopy(tmp, 0, X, xOff, N);
    }

    /**
     * Reorders consecutive blocks back into interleaved short blocks ({@code interleave_hadamard}).
     *
     * @param X        the band
     * @param xOff     the index of its first sample
     * @param N0       the samples per block
     * @param stride   the number of blocks
     * @param hadamard nonzero to use the Hadamard block order
     */
    private void interleaveHadamard(float[] X, int xOff, int N0, int stride, int hadamard) {
        var tmp = hadamardTmp;
        var N = N0 * stride;
        if (hadamard != 0) {
            var ordery = stride - 2;
            for (var i = 0; i < stride; i++) {
                for (var j = 0; j < N0; j++) {
                    tmp[j * stride + i] = X[xOff + ORDERY_TABLE[ordery + i] * N0 + j];
                }
            }
        } else {
            for (var i = 0; i < stride; i++) {
                for (var j = 0; j < N0; j++) {
                    tmp[j * stride + i] = X[xOff + i * N0 + j];
                }
            }
        }
        System.arraycopy(tmp, 0, X, xOff, N);
    }

    /**
     * Applies one level of the Haar transform between neighboring blocks ({@code haar1}).
     *
     * @param X      the band
     * @param xOff   the index of its first sample
     * @param N0     the band size
     * @param stride the block interleaving stride
     */
    public static void haar1(float[] X, int xOff, int N0, int stride) {
        CeltKernels.INSTANCE.haar1(X, xOff, N0, stride);
    }

    /**
     * Returns the number of quantization steps of theta for a split ({@code compute_qn}).
     *
     * @param N         the half-band size
     * @param b         the bits of the band
     * @param offset    the theta allocation offset
     * @param pulseCap  the pulse cap of the band
     * @param stereo    nonzero for a stereo split
     * @return the number of steps, even, at most 256
     */
    private static int computeQn(int N, int b, int offset, int pulseCap, int stereo) {
        int qn, qb;
        var N2 = 2 * N - 1;
        if (stereo != 0 && N == 2) {
            N2--;
        }
        qb = (b + N2 * offset) / N2;
        qb = Math.min(b - pulseCap - (4 << EntropyCoder.BITRES), qb);
        qb = Math.min(8 << EntropyCoder.BITRES, qb);
        if (qb < (1 << EntropyCoder.BITRES >> 1)) {
            qn = 1;
        } else {
            qn = EXP2_TABLE8[qb & 0x7] >> (14 - (qb >> EntropyCoder.BITRES));
            qn = (qn + 1) >> 1 << 1;
        }
        return qn;
    }

    /**
     * Computes, codes and applies the split angle theta of a band ({@code compute_theta}).
     *
     * <p>Reads and updates {@link SplitCtx#b} and {@link SplitCtx#fill} of {@link #sctx}.
     *
     * @param X      the first half or channel
     * @param xOff   the index of its first sample
     * @param Y      the second half or channel
     * @param yOff   the index of its first sample
     * @param N      the size of each half
     * @param B      the number of blocks
     * @param B0     the number of blocks before the split
     * @param LM     the frame size shift of the halves
     * @param stereo nonzero for a stereo split
     */
    private void computeTheta(float[] X, int xOff, float[] Y, int yOff, int N, int B, int B0, int LM, int stereo) {
        int qn;
        var itheta = 0;
        int delta;
        int imid, iside;
        int qalloc;
        int pulseCap;
        int offset;
        int tell;
        var inv = 0;
        var encode = ctx.encode;
        var m = ctx.m;
        var i = ctx.i;
        var intensity = ctx.intensity;
        var ec = ctx.ec;
        var bandE = ctx.bandE;
        pulseCap = m.logN[i] + LM * (1 << EntropyCoder.BITRES);
        offset = (pulseCap >> 1) - (stereo != 0 && N == 2 ? Rate.QTHETA_OFFSET_TWOPHASE : Rate.QTHETA_OFFSET);
        qn = computeQn(N, sctx.b, offset, pulseCap, stereo);
        if (stereo != 0 && i >= intensity) {
            qn = 1;
        }
        if (encode != 0) {
            itheta = Vq.stereoItheta(X, xOff, Y, yOff, stereo, N);
        }
        tell = ec.tellFrac();
        if (qn != 1) {
            if (encode != 0) {
                if (stereo == 0 || ctx.thetaRound == 0) {
                    itheta = (itheta * qn + 8192) >> 14;
                    if (stereo == 0 && ctx.avoidSplitNoise != 0 && itheta > 0 && itheta < qn) {
                        var unquantized = Integer.divideUnsigned(itheta * 16384, qn);
                        imid = bitexactCos((short) unquantized);
                        iside = bitexactCos((short) (16384 - unquantized));
                        delta = fracMul16((N - 1) << 7, bitexactLog2tan(iside, imid));
                        if (delta > sctx.b) {
                            itheta = qn;
                        } else if (delta < -sctx.b) {
                            itheta = 0;
                        }
                    }
                } else {
                    int down;
                    var bias = itheta > 8192 ? 32767 / qn : -32767 / qn;
                    down = Math.min(qn - 1, Math.max(0, (itheta * qn + bias) >> 14));
                    if (ctx.thetaRound < 0) {
                        itheta = down;
                    } else {
                        itheta = down + 1;
                    }
                }
            }
            if (stereo != 0 && N > 2) {
                var p0 = 3;
                var x = itheta;
                var x0 = qn / 2;
                var ft = p0 * (x0 + 1) + x0;
                if (encode != 0) {
                    ((RangeEncoder) ec).encode(x <= x0 ? p0 * x : (x - 1 - x0) + (x0 + 1) * p0,
                            x <= x0 ? p0 * (x + 1) : (x - x0) + (x0 + 1) * p0, ft);
                } else {
                    var dec = (RangeDecoder) ec;
                    var fs = dec.decode(ft);
                    if (fs < (x0 + 1) * p0) {
                        x = fs / p0;
                    } else {
                        x = x0 + 1 + (fs - (x0 + 1) * p0);
                    }
                    dec.decUpdate(x <= x0 ? p0 * x : (x - 1 - x0) + (x0 + 1) * p0,
                            x <= x0 ? p0 * (x + 1) : (x - x0) + (x0 + 1) * p0, ft);
                    itheta = x;
                }
            } else if (B0 > 1 || stereo != 0) {
                if (encode != 0) {
                    ((RangeEncoder) ec).encUint(itheta, qn + 1);
                } else {
                    itheta = ((RangeDecoder) ec).decUint(qn + 1);
                }
            } else {
                var fs = 1;
                int ft;
                ft = ((qn >> 1) + 1) * ((qn >> 1) + 1);
                if (encode != 0) {
                    int fl;
                    fs = itheta <= (qn >> 1) ? itheta + 1 : qn + 1 - itheta;
                    fl = itheta <= (qn >> 1) ? itheta * (itheta + 1) >> 1
                            : ft - ((qn + 1 - itheta) * (qn + 2 - itheta) >> 1);
                    ((RangeEncoder) ec).encode(fl, fl + fs, ft);
                } else {
                    var dec = (RangeDecoder) ec;
                    var fl = 0;
                    int fm;
                    fm = dec.decode(ft);
                    if (fm < ((qn >> 1) * ((qn >> 1) + 1) >> 1)) {
                        itheta = (MathOps.isqrt32(8 * fm + 1) - 1) >>> 1;
                        fs = itheta + 1;
                        fl = itheta * (itheta + 1) >> 1;
                    } else {
                        itheta = (2 * (qn + 1) - MathOps.isqrt32(8 * (ft - fm - 1) + 1)) >>> 1;
                        fs = qn + 1 - itheta;
                        fl = ft - ((qn + 1 - itheta) * (qn + 2 - itheta) >> 1);
                    }
                    dec.decUpdate(fl, fl + fs, ft);
                }
            }
            itheta = Integer.divideUnsigned(itheta * 16384, qn);
            if (encode != 0 && stereo != 0) {
                if (itheta == 0) {
                    intensityStereo(m, X, xOff, Y, yOff, bandE, i, N);
                } else {
                    stereoSplit(X, xOff, Y, yOff, N);
                }
            }
        } else if (stereo != 0) {
            if (encode != 0) {
                inv = itheta > 8192 && ctx.disableInv == 0 ? 1 : 0;
                if (inv != 0) {
                    for (var j = 0; j < N; j++) {
                        Y[yOff + j] = -Y[yOff + j];
                    }
                }
                intensityStereo(m, X, xOff, Y, yOff, bandE, i, N);
            }
            if (sctx.b > 2 << EntropyCoder.BITRES && ctx.remainingBits > 2 << EntropyCoder.BITRES) {
                if (encode != 0) {
                    ((RangeEncoder) ec).encBitLogp(inv, 2);
                } else {
                    inv = ((RangeDecoder) ec).decBitLogp(2);
                }
            } else {
                inv = 0;
            }
            if (ctx.disableInv != 0) {
                inv = 0;
            }
            itheta = 0;
        }
        qalloc = ec.tellFrac() - tell;
        sctx.b -= qalloc;
        if (itheta == 0) {
            imid = 32767;
            iside = 0;
            sctx.fill &= (1 << B) - 1;
            delta = -16384;
        } else if (itheta == 16384) {
            imid = 0;
            iside = 32767;
            sctx.fill &= ((1 << B) - 1) << B;
            delta = 16384;
        } else {
            imid = bitexactCos((short) itheta);
            iside = bitexactCos((short) (16384 - itheta));
            delta = fracMul16((N - 1) << 7, bitexactLog2tan(iside, imid));
        }
        sctx.inv = inv;
        sctx.imid = imid;
        sctx.iside = iside;
        sctx.delta = delta;
        sctx.itheta = itheta;
        sctx.qalloc = qalloc;
    }

    /**
     * Codes a one-sample band: just a sign per channel ({@code quant_band_n1}).
     *
     * @param X         the first channel
     * @param xOff      the index of its sample
     * @param Y         the second channel, or {@code null}
     * @param yOff      the index of its sample
     * @param lowOut    the folding output, or {@code null}
     * @param lowOutOff the index of the folding output
     * @return the collapse mask, always 1
     */
    private int quantBandN1(float[] X, int xOff, float[] Y, int yOff, float[] lowOut, int lowOutOff) {
        var stereo = Y != null ? 1 : 0;
        var x = X;
        var xo = xOff;
        var encode = ctx.encode;
        var ec = ctx.ec;
        var c = 0;
        do {
            var sign = 0;
            if (ctx.remainingBits >= 1 << EntropyCoder.BITRES) {
                if (encode != 0) {
                    sign = x[xo] < 0 ? 1 : 0;
                    ((RangeEncoder) ec).encBits(sign, 1);
                } else {
                    sign = ((RangeDecoder) ec).decBits(1);
                }
                ctx.remainingBits -= 1 << EntropyCoder.BITRES;
            }
            if (ctx.resynth != 0) {
                x[xo] = sign != 0 ? -1.f : 1.f;
            }
            x = Y;
            xo = yOff;
        } while (++c < 1 + stereo);
        if (lowOut != null) {
            lowOut[lowOutOff] = X[xOff];
        }
        return 1;
    }

    /**
     * Codes a mono partition, splitting it recursively while it needs more bits than one PVQ
     * codeword can use ({@code quant_partition}).
     *
     * @param X        the partition
     * @param xOff     the index of its first sample
     * @param N        the partition size
     * @param b        the bits of the partition
     * @param B        the number of blocks
     * @param lowband  the folding source, or {@code null}
     * @param lowOff   the index of the folding source
     * @param LM       the frame size shift of the partition
     * @param gain     the reconstruction norm
     * @param fill     the fill mask of the blocks
     * @return the collapse mask
     */
    private int quantPartition(float[] X, int xOff, int N, int b, int B, float[] lowband, int lowOff, int LM,
                               float gain, int fill) {
        int q;
        int currBits;
        int imid, iside;
        var B0 = B;
        float mid, side;
        var cm = 0;
        var encode = ctx.encode;
        var m = ctx.m;
        var i = ctx.i;
        var spread = ctx.spread;
        var ec = ctx.ec;
        var cacheIndex = m.cache.index[(LM + 1) * m.nbEBands + i];
        var cache = m.cache.bits;
        if (LM != -1 && b > cache[cacheIndex + cache[cacheIndex]] + 12 && N > 2) {
            int mbits, sbits, delta;
            int itheta;
            int qalloc;
            float[] nextLowband2 = null;
            var nextLowband2Off = 0;
            int rebalance;
            N >>= 1;
            var yOff = xOff + N;
            LM -= 1;
            if (B == 1) {
                fill = (fill & 1) | (fill << 1);
            }
            B = (B + 1) >> 1;
            sctx.b = b;
            sctx.fill = fill;
            computeTheta(X, xOff, X, yOff, N, B, B0, LM, 0);
            b = sctx.b;
            fill = sctx.fill;
            imid = sctx.imid;
            iside = sctx.iside;
            delta = sctx.delta;
            itheta = sctx.itheta;
            qalloc = sctx.qalloc;
            mid = (1.f / 32768) * imid;
            side = (1.f / 32768) * iside;
            if (B0 > 1 && (itheta & 0x3fff) != 0) {
                if (itheta > 8192) {
                    delta -= delta >> (4 - LM);
                } else {
                    delta = Math.min(0, delta + (N << EntropyCoder.BITRES >> (5 - LM)));
                }
            }
            mbits = Math.max(0, Math.min(b, (b - delta) / 2));
            sbits = b - mbits;
            ctx.remainingBits -= qalloc;
            if (lowband != null) {
                nextLowband2 = lowband;
                nextLowband2Off = lowOff + N;
            }
            rebalance = ctx.remainingBits;
            if (mbits >= sbits) {
                cm = quantPartition(X, xOff, N, mbits, B, lowband, lowOff, LM, gain * mid, fill);
                rebalance = mbits - (rebalance - ctx.remainingBits);
                if (rebalance > 3 << EntropyCoder.BITRES && itheta != 0) {
                    sbits += rebalance - (3 << EntropyCoder.BITRES);
                }
                cm |= quantPartition(X, yOff, N, sbits, B, nextLowband2, nextLowband2Off, LM, gain * side,
                        fill >> B) << (B0 >> 1);
            } else {
                cm = quantPartition(X, yOff, N, sbits, B, nextLowband2, nextLowband2Off, LM, gain * side,
                        fill >> B) << (B0 >> 1);
                rebalance = sbits - (rebalance - ctx.remainingBits);
                if (rebalance > 3 << EntropyCoder.BITRES && itheta != 16384) {
                    mbits += rebalance - (3 << EntropyCoder.BITRES);
                }
                cm |= quantPartition(X, xOff, N, mbits, B, lowband, lowOff, LM, gain * mid, fill);
            }
        } else {
            q = Rate.bits2pulses(m, i, LM, b);
            currBits = Rate.pulses2bits(m, i, LM, q);
            ctx.remainingBits -= currBits;
            while (ctx.remainingBits < 0 && q > 0) {
                ctx.remainingBits += currBits;
                q--;
                currBits = Rate.pulses2bits(m, i, LM, q);
                ctx.remainingBits -= currBits;
            }
            if (q != 0) {
                var K = Rate.getPulses(q);
                if (encode != 0) {
                    cm = Vq.algQuant(X, xOff, N, K, spread, B, (RangeEncoder) ec, gain, ctx.resynth, iy, pvqY, signx);
                } else {
                    cm = Vq.algUnquant(X, xOff, N, K, spread, B, (RangeDecoder) ec, gain, iy);
                }
            } else {
                if (ctx.resynth != 0) {
                    var cmMask = (int) ((1L << B) - 1);
                    fill &= cmMask;
                    if (fill == 0) {
                        java.util.Arrays.fill(X, xOff, xOff + N, 0);
                    } else {
                        if (lowband == null) {
                            for (var j = 0; j < N; j++) {
                                ctx.seed = celtLcgRand(ctx.seed);
                                X[xOff + j] = (float) (ctx.seed >> 20);
                            }
                            cm = cmMask;
                        } else {
                            for (var j = 0; j < N; j++) {
                                float tmp;
                                ctx.seed = celtLcgRand(ctx.seed);
                                tmp = 1.0f / 256;
                                tmp = (ctx.seed & 0x8000) != 0 ? tmp : -tmp;
                                X[xOff + j] = lowband[lowOff + j] + tmp;
                            }
                            cm = fill;
                        }
                        Vq.renormalizeVector(X, xOff, N, gain);
                    }
                }
            }
        }
        return cm;
    }

    /**
     * Codes a mono band, applying the TF resolution change and the Hadamard reordering around
     * {@link #quantPartition} ({@code quant_band}).
     *
     * @param X          the band
     * @param xOff       the index of its first sample
     * @param N          the band size
     * @param b          the bits of the band
     * @param B          the number of blocks
     * @param lowband    the folding source, or {@code null}
     * @param lowOff     the index of the folding source
     * @param LM         the frame size shift
     * @param lowOut     the folding output, or {@code null}
     * @param lowOutOff  the index of the folding output
     * @param gain       the reconstruction norm
     * @param scratch    the folding scratch, or {@code null}
     * @param scratchOff the index of the folding scratch
     * @param fill       the fill mask of the blocks
     * @return the collapse mask
     */
    private int quantBand(float[] X, int xOff, int N, int b, int B, float[] lowband, int lowOff, int LM,
                          float[] lowOut, int lowOutOff, float gain, float[] scratch, int scratchOff, int fill) {
        var N0 = N;
        var N_B = N;
        int N_B0;
        var B0 = B;
        var timeDivide = 0;
        var recombine = 0;
        int longBlocks;
        int cm;
        int k;
        var encode = ctx.encode;
        var tfChange = ctx.tfChange;
        longBlocks = B0 == 1 ? 1 : 0;
        N_B = Integer.divideUnsigned(N_B, B);
        if (N == 1) {
            return quantBandN1(X, xOff, null, 0, lowOut, lowOutOff);
        }
        if (tfChange > 0) {
            recombine = tfChange;
        }
        if (scratch != null && lowband != null && (recombine != 0 || ((N_B & 1) == 0 && tfChange < 0) || B0 > 1)) {
            System.arraycopy(lowband, lowOff, scratch, scratchOff, N);
            lowband = scratch;
            lowOff = scratchOff;
        }
        for (k = 0; k < recombine; k++) {
            if (encode != 0) {
                haar1(X, xOff, N >> k, 1 << k);
            }
            if (lowband != null) {
                haar1(lowband, lowOff, N >> k, 1 << k);
            }
            fill = BIT_INTERLEAVE_TABLE[fill & 0xF] | BIT_INTERLEAVE_TABLE[fill >> 4] << 2;
        }
        B >>= recombine;
        N_B <<= recombine;
        while ((N_B & 1) == 0 && tfChange < 0) {
            if (encode != 0) {
                haar1(X, xOff, N_B, B);
            }
            if (lowband != null) {
                haar1(lowband, lowOff, N_B, B);
            }
            fill |= fill << B;
            B <<= 1;
            N_B >>= 1;
            timeDivide++;
            tfChange++;
        }
        B0 = B;
        N_B0 = N_B;
        if (B0 > 1) {
            if (encode != 0) {
                deinterleaveHadamard(X, xOff, N_B >> recombine, B0 << recombine, longBlocks);
            }
            if (lowband != null) {
                deinterleaveHadamard(lowband, lowOff, N_B >> recombine, B0 << recombine, longBlocks);
            }
        }
        cm = quantPartition(X, xOff, N, b, B, lowband, lowOff, LM, gain, fill);
        if (ctx.resynth != 0) {
            if (B0 > 1) {
                interleaveHadamard(X, xOff, N_B >> recombine, B0 << recombine, longBlocks);
            }
            N_B = N_B0;
            B = B0;
            for (k = 0; k < timeDivide; k++) {
                B >>= 1;
                N_B <<= 1;
                cm |= cm >>> B;
                haar1(X, xOff, N_B, B);
            }
            for (k = 0; k < recombine; k++) {
                cm = BIT_DEINTERLEAVE_TABLE[cm];
                haar1(X, xOff, N0 >> k, 1 << k);
            }
            B <<= recombine;
            if (lowOut != null) {
                var n = MathOps.celtSqrt(N0);
                for (var j = 0; j < N0; j++) {
                    lowOut[lowOutOff + j] = n * X[xOff + j];
                }
            }
            cm &= (1 << B) - 1;
        }
        return cm;
    }

    /**
     * Codes a stereo band as a mid/side split with an angle ({@code quant_band_stereo}).
     *
     * @param X          the first channel
     * @param xOff       the index of its first sample
     * @param Y          the second channel
     * @param yOff       the index of its first sample
     * @param N          the band size
     * @param b          the bits of the band
     * @param B          the number of blocks
     * @param lowband    the folding source, or {@code null}
     * @param lowOff     the index of the folding source
     * @param LM         the frame size shift
     * @param lowOut     the folding output, or {@code null}
     * @param lowOutOff  the index of the folding output
     * @param scratch    the folding scratch, or {@code null}
     * @param scratchOff the index of the folding scratch
     * @param fill       the fill mask of the blocks
     * @return the collapse mask
     */
    private int quantBandStereo(float[] X, int xOff, float[] Y, int yOff, int N, int b, int B, float[] lowband,
                                int lowOff, int LM, float[] lowOut, int lowOutOff, float[] scratch, int scratchOff,
                                int fill) {
        int imid, iside;
        int inv;
        float mid, side;
        int cm;
        int mbits, sbits, delta;
        int itheta;
        int qalloc;
        int origFill;
        var encode = ctx.encode;
        var ec = ctx.ec;
        if (N == 1) {
            return quantBandN1(X, xOff, Y, yOff, lowOut, lowOutOff);
        }
        origFill = fill;
        sctx.b = b;
        sctx.fill = fill;
        computeTheta(X, xOff, Y, yOff, N, B, B, LM, 1);
        b = sctx.b;
        fill = sctx.fill;
        inv = sctx.inv;
        imid = sctx.imid;
        iside = sctx.iside;
        delta = sctx.delta;
        itheta = sctx.itheta;
        qalloc = sctx.qalloc;
        mid = (1.f / 32768) * imid;
        side = (1.f / 32768) * iside;
        if (N == 2) {
            int c;
            var sign = 0;
            float[] x2, y2;
            int x2Off, y2Off;
            mbits = b;
            sbits = 0;
            if (itheta != 0 && itheta != 16384) {
                sbits = 1 << EntropyCoder.BITRES;
            }
            mbits -= sbits;
            c = itheta > 8192 ? 1 : 0;
            ctx.remainingBits -= qalloc + sbits;
            x2 = c != 0 ? Y : X;
            x2Off = c != 0 ? yOff : xOff;
            y2 = c != 0 ? X : Y;
            y2Off = c != 0 ? xOff : yOff;
            if (sbits != 0) {
                if (encode != 0) {
                    sign = x2[x2Off] * y2[y2Off + 1] - x2[x2Off + 1] * y2[y2Off] < 0 ? 1 : 0;
                    ((RangeEncoder) ec).encBits(sign, 1);
                } else {
                    sign = ((RangeDecoder) ec).decBits(1);
                }
            }
            sign = 1 - 2 * sign;
            cm = quantBand(x2, x2Off, N, mbits, B, lowband, lowOff, LM, lowOut, lowOutOff, 1.0f, scratch, scratchOff,
                    origFill);
            y2[y2Off] = -sign * x2[x2Off + 1];
            y2[y2Off + 1] = sign * x2[x2Off];
            if (ctx.resynth != 0) {
                float tmp;
                X[xOff] = mid * X[xOff];
                X[xOff + 1] = mid * X[xOff + 1];
                Y[yOff] = side * Y[yOff];
                Y[yOff + 1] = side * Y[yOff + 1];
                tmp = X[xOff];
                X[xOff] = tmp - Y[yOff];
                Y[yOff] = tmp + Y[yOff];
                tmp = X[xOff + 1];
                X[xOff + 1] = tmp - Y[yOff + 1];
                Y[yOff + 1] = tmp + Y[yOff + 1];
            }
        } else {
            int rebalance;
            mbits = Math.max(0, Math.min(b, (b - delta) / 2));
            sbits = b - mbits;
            ctx.remainingBits -= qalloc;
            rebalance = ctx.remainingBits;
            if (mbits >= sbits) {
                cm = quantBand(X, xOff, N, mbits, B, lowband, lowOff, LM, lowOut, lowOutOff, 1.0f, scratch,
                        scratchOff, fill);
                rebalance = mbits - (rebalance - ctx.remainingBits);
                if (rebalance > 3 << EntropyCoder.BITRES && itheta != 0) {
                    sbits += rebalance - (3 << EntropyCoder.BITRES);
                }
                cm |= quantBand(Y, yOff, N, sbits, B, null, 0, LM, null, 0, side, null, 0, fill >> B);
            } else {
                cm = quantBand(Y, yOff, N, sbits, B, null, 0, LM, null, 0, side, null, 0, fill >> B);
                rebalance = sbits - (rebalance - ctx.remainingBits);
                if (rebalance > 3 << EntropyCoder.BITRES && itheta != 16384) {
                    mbits += rebalance - (3 << EntropyCoder.BITRES);
                }
                cm |= quantBand(X, xOff, N, mbits, B, lowband, lowOff, LM, lowOut, lowOutOff, 1.0f, scratch,
                        scratchOff, fill);
            }
        }
        if (ctx.resynth != 0) {
            if (N != 2) {
                stereoMerge(X, xOff, Y, yOff, mid, N);
            }
            if (inv != 0) {
                for (var j = 0; j < N; j++) {
                    Y[yOff + j] = -Y[yOff + j];
                }
            }
        }
        return cm;
    }

    /**
     * Duplicates enough of the first coded band's folding data to fold the second band in hybrid
     * mode ({@code special_hybrid_folding}).
     *
     * @param m          the mode
     * @param norm       the folding memory
     * @param normOff    the index of the first channel's folding memory
     * @param norm2Off   the index of the second channel's folding memory
     * @param start      the first coded band
     * @param M          the number of short MDCTs
     * @param dualStereo nonzero when both channels fold separately
     */
    private static void specialHybridFolding(CeltMode m, float[] norm, int normOff, int norm2Off, int start, int M,
                                             int dualStereo) {
        var eBands = m.eBands;
        var n1 = M * (eBands[start + 1] - eBands[start]);
        var n2 = M * (eBands[start + 2] - eBands[start + 1]);
        if (n2 - n1 > 0) {
            System.arraycopy(norm, normOff + 2 * n1 - n2, norm, normOff + n1, n2 - n1);
            if (dualStereo != 0) {
                System.arraycopy(norm, norm2Off + 2 * n1 - n2, norm, norm2Off + n1, n2 - n1);
            }
        }
    }

    /**
     * Quantizes (encoder) or decodes (decoder) the shapes of all bands ({@code quant_all_bands}).
     *
     * @param encode        nonzero in the encoder
     * @param m             the mode
     * @param start         the first coded band
     * @param end           the end band
     * @param X_            the normalized coefficients: the first channel at index 0 and, in
     *                      stereo, the second channel at {@code yOff}
     * @param yOff          the index of the second channel, or {@code -1} for mono
     * @param collapseMasks receives the collapse mask of each band and channel
     * @param bandE         the band amplitudes (encoder only; {@code null} in the decoder)
     * @param pulses        the PVQ allocation of each band
     * @param shortBlocks   nonzero for a transient frame
     * @param spread        the spreading decision
     * @param dualStereo    nonzero for dual stereo
     * @param intensity     the first intensity-coded band
     * @param tfRes         the TF resolution change of each band
     * @param totalBits     the frame budget in 1/8 bits
     * @param balance       the allocation balance carried between bands
     * @param ec            the range coder
     * @param LM            the frame size shift
     * @param codedBands    the number of coded bands
     * @param seed          the unsigned noise seed
     * @param complexity    the encoder complexity
     * @param disableInv    nonzero to disable stereo phase inversion
     * @return the updated seed
     */
    public int quantAllBands(int encode, CeltMode m, int start, int end, float[] X_, int yOff, int[] collapseMasks,
                             float[] bandE, int[] pulses, int shortBlocks, int spread, int dualStereo, int intensity,
                             int[] tfRes, int totalBits, int balance, EntropyCoder ec, int LM, int codedBands,
                             int seed, int complexity, int disableInv) {
        int i;
        int remainingBits;
        var eBands = m.eBands;
        var hasY = yOff >= 0;
        float[] lowbandScratch;
        int lowbandScratchOff;
        int B;
        int M;
        int lowbandOffset;
        var updateLowband = 1;
        var C = hasY ? 2 : 1;
        int normOffset;
        var thetaRdo = encode != 0 && hasY && dualStereo == 0 && complexity >= 8 ? 1 : 0;
        var resynth = encode == 0 || thetaRdo != 0 ? 1 : 0;
        M = 1 << LM;
        B = shortBlocks != 0 ? M : 1;
        normOffset = M * eBands[start];
        var norm = this.norm;
        var norm2Off = M * eBands[m.nbEBands - 1] - normOffset;
        if (encode != 0 && resynth != 0) {
            lowbandScratch = lowbandScratchBuf;
            lowbandScratchOff = 0;
        } else {
            lowbandScratch = X_;
            lowbandScratchOff = M * eBands[m.effEBands - 1];
        }
        lowbandOffset = 0;
        ctx.bandE = bandE;
        ctx.ec = ec;
        ctx.encode = encode;
        ctx.intensity = intensity;
        ctx.m = m;
        ctx.seed = seed;
        ctx.spread = spread;
        ctx.disableInv = disableInv;
        ctx.resynth = resynth;
        ctx.thetaRound = 0;
        ctx.avoidSplitNoise = B > 1 ? 1 : 0;
        for (i = start; i < end; i++) {
            int tell;
            int b;
            int N;
            int currBalance;
            var effectiveLowband = -1;
            float[] X, Y;
            int xOff, yo;
            int tfChange;
            int xCm;
            int yCm;
            int last;
            ctx.i = i;
            last = i == end - 1 ? 1 : 0;
            X = X_;
            xOff = M * eBands[i];
            if (hasY) {
                Y = X_;
                yo = yOff + M * eBands[i];
            } else {
                Y = null;
                yo = 0;
            }
            N = M * eBands[i + 1] - M * eBands[i];
            tell = ec.tellFrac();
            if (i != start) {
                balance -= tell;
            }
            remainingBits = totalBits - tell - 1;
            ctx.remainingBits = remainingBits;
            if (i <= codedBands - 1) {
                currBalance = balance / Math.min(3, codedBands - i);
                b = Math.max(0, Math.min(16383, Math.min(remainingBits + 1, pulses[i] + currBalance)));
            } else {
                b = 0;
            }
            if (resynth != 0 && (M * eBands[i] - N >= M * eBands[start] || i == start + 1)
                    && (updateLowband != 0 || lowbandOffset == 0)) {
                lowbandOffset = i;
            }
            if (i == start + 1) {
                specialHybridFolding(m, norm, 0, norm2Off, start, M, dualStereo);
            }
            tfChange = tfRes[i];
            ctx.tfChange = tfChange;
            var curScratch = lowbandScratch;
            var curScratchOff = lowbandScratchOff;
            if (i >= m.effEBands) {
                X = norm;
                xOff = 0;
                if (hasY) {
                    Y = norm;
                    yo = 0;
                }
                lowbandScratch = null;
                curScratch = null;
            }
            if (last != 0 && thetaRdo == 0) {
                curScratch = null;
            }
            if (lowbandOffset != 0 && (spread != Celt.SPREAD_AGGRESSIVE || B > 1 || tfChange < 0)) {
                int foldStart;
                int foldEnd;
                int foldI;
                effectiveLowband = Math.max(0, M * eBands[lowbandOffset] - normOffset - N);
                foldStart = lowbandOffset;
                do {
                    foldStart--;
                } while (M * eBands[foldStart] > effectiveLowband + normOffset);
                foldEnd = lowbandOffset - 1;
                do {
                    foldEnd++;
                } while (foldEnd < i && M * eBands[foldEnd] < effectiveLowband + normOffset + N);
                xCm = yCm = 0;
                foldI = foldStart;
                do {
                    xCm |= collapseMasks[foldI * C];
                    yCm |= collapseMasks[foldI * C + C - 1];
                } while (++foldI < foldEnd);
            } else {
                xCm = yCm = (1 << B) - 1;
            }
            if (dualStereo != 0 && i == intensity) {
                dualStereo = 0;
                if (resynth != 0) {
                    for (var j = 0; j < M * eBands[i] - normOffset; j++) {
                        norm[j] = .5f * (norm[j] + norm[norm2Off + j]);
                    }
                }
            }
            var lowOutOff = M * eBands[i] - normOffset;
            if (dualStereo != 0) {
                xCm = quantBand(X, xOff, N, b / 2, B, effectiveLowband != -1 ? norm : null, effectiveLowband, LM,
                        last != 0 ? null : norm, lowOutOff, 1.0f, curScratch, curScratchOff, xCm);
                yCm = quantBand(Y, yo, N, b / 2, B, effectiveLowband != -1 ? norm : null, norm2Off + effectiveLowband,
                        LM, last != 0 ? null : norm, norm2Off + lowOutOff, 1.0f, curScratch, curScratchOff, yCm);
            } else {
                if (Y != null) {
                    if (thetaRdo != 0 && i < intensity) {
                        float dist0, dist1;
                        int cm, cm2;
                        int nstartBytes, nendBytes, saveBytes;
                        int bytesBuf;
                        var enc = (RangeEncoder) ec;
                        var w = weights;
                        computeChannelWeights(bandE[i], bandE[i + m.nbEBands], w);
                        cm = xCm | yCm;
                        ecSave.copyFrom(enc);
                        ctxSave.copyFrom(ctx);
                        System.arraycopy(X, xOff, xSave, 0, N);
                        System.arraycopy(Y, yo, ySave, 0, N);
                        ctx.thetaRound = -1;
                        xCm = quantBandStereo(X, xOff, Y, yo, N, b, B, effectiveLowband != -1 ? norm : null,
                                effectiveLowband, LM, last != 0 ? null : norm, lowOutOff, curScratch, curScratchOff, cm);
                        dist0 = w[0] * Pitch.celtInnerProd(xSave, 0, X, xOff, N)
                                + w[1] * Pitch.celtInnerProd(ySave, 0, Y, yo, N);
                        cm2 = xCm;
                        ecSave2.copyFrom(enc);
                        ctxSave2.copyFrom(ctx);
                        System.arraycopy(X, xOff, xSave2, 0, N);
                        System.arraycopy(Y, yo, ySave2, 0, N);
                        if (last == 0) {
                            System.arraycopy(norm, lowOutOff, normSave2, 0, N);
                        }
                        nstartBytes = ecSave.offs;
                        nendBytes = ecSave.storage;
                        bytesBuf = ecSave.base + nstartBytes;
                        saveBytes = nendBytes - nstartBytes;
                        System.arraycopy(ecSave.buf, bytesBuf, bytesSave, 0, saveBytes);
                        enc.copyFrom(ecSave);
                        ctx.copyFrom(ctxSave);
                        System.arraycopy(xSave, 0, X, xOff, N);
                        System.arraycopy(ySave, 0, Y, yo, N);
                        if (i == start + 1) {
                            specialHybridFolding(m, norm, 0, norm2Off, start, M, dualStereo);
                        }
                        ctx.thetaRound = 1;
                        xCm = quantBandStereo(X, xOff, Y, yo, N, b, B, effectiveLowband != -1 ? norm : null,
                                effectiveLowband, LM, last != 0 ? null : norm, lowOutOff, curScratch, curScratchOff, cm);
                        dist1 = w[0] * Pitch.celtInnerProd(xSave, 0, X, xOff, N)
                                + w[1] * Pitch.celtInnerProd(ySave, 0, Y, yo, N);
                        if (dist0 >= dist1) {
                            xCm = cm2;
                            enc.copyFrom(ecSave2);
                            ctx.copyFrom(ctxSave2);
                            System.arraycopy(xSave2, 0, X, xOff, N);
                            System.arraycopy(ySave2, 0, Y, yo, N);
                            if (last == 0) {
                                System.arraycopy(normSave2, 0, norm, lowOutOff, N);
                            }
                            System.arraycopy(bytesSave, 0, ecSave.buf, bytesBuf, saveBytes);
                        }
                    } else {
                        ctx.thetaRound = 0;
                        xCm = quantBandStereo(X, xOff, Y, yo, N, b, B, effectiveLowband != -1 ? norm : null,
                                effectiveLowband, LM, last != 0 ? null : norm, lowOutOff, curScratch, curScratchOff,
                                xCm | yCm);
                    }
                } else {
                    xCm = quantBand(X, xOff, N, b, B, effectiveLowband != -1 ? norm : null, effectiveLowband, LM,
                            last != 0 ? null : norm, lowOutOff, 1.0f, curScratch, curScratchOff, xCm | yCm);
                }
                yCm = xCm;
            }
            collapseMasks[i * C] = xCm & 0xFF;
            collapseMasks[i * C + C - 1] = yCm & 0xFF;
            balance += pulses[i] + tell;
            updateLowband = b > (N << EntropyCoder.BITRES) ? 1 : 0;
            ctx.avoidSplitNoise = 0;
        }
        return ctx.seed;
    }

    /**
     * Holds the per-band coding context ({@code struct band_ctx}).
     */
    private static final class BandCtx {
        /**
         * Creates an empty context.
         */
        BandCtx() {
        }

        /**
         * Nonzero in the encoder.
         */
        int encode;

        /**
         * Nonzero to reconstruct the quantized signal.
         */
        int resynth;

        /**
         * The mode.
         */
        CeltMode m;

        /**
         * The current band.
         */
        int i;

        /**
         * The first intensity-coded band.
         */
        int intensity;

        /**
         * The spreading decision.
         */
        int spread;

        /**
         * The TF resolution change of the current band.
         */
        int tfChange;

        /**
         * The range coder.
         */
        EntropyCoder ec;

        /**
         * The bits left in the frame, in 1/8 bits.
         */
        int remainingBits;

        /**
         * The band amplitudes, or {@code null} in the decoder.
         */
        float[] bandE;

        /**
         * The unsigned noise seed.
         */
        int seed;

        /**
         * The theta rounding of the current trial: 0 to round, -1 down, 1 up.
         */
        int thetaRound;

        /**
         * Nonzero to disable stereo phase inversion.
         */
        int disableInv;

        /**
         * Nonzero to avoid injecting noise when splitting the first band.
         */
        int avoidSplitNoise;

        /**
         * Copies another context into this one.
         *
         * @param o the context to copy
         */
        void copyFrom(BandCtx o) {
            encode = o.encode;
            resynth = o.resynth;
            m = o.m;
            i = o.i;
            intensity = o.intensity;
            spread = o.spread;
            tfChange = o.tfChange;
            ec = o.ec;
            remainingBits = o.remainingBits;
            bandE = o.bandE;
            seed = o.seed;
            thetaRound = o.thetaRound;
            disableInv = o.disableInv;
            avoidSplitNoise = o.avoidSplitNoise;
        }
    }

    /**
     * Holds the parameters of a band split ({@code struct split_ctx}), plus the bits and fill
     * mask that {@link #computeTheta} reads and updates.
     */
    private static final class SplitCtx {
        /**
         * Creates an empty context.
         */
        SplitCtx() {
        }

        /**
         * Nonzero when the second channel is phase inverted.
         */
        int inv;

        /**
         * The mid gain in Q15.
         */
        int imid;

        /**
         * The side gain in Q15.
         */
        int iside;

        /**
         * The bit allocation shift between the halves.
         */
        int delta;

        /**
         * The quantized angle, 0 to 16384.
         */
        int itheta;

        /**
         * The bits spent coding the angle.
         */
        int qalloc;

        /**
         * The bits of the band, in and out.
         */
        int b;

        /**
         * The fill mask of the band, in and out.
         */
        int fill;
    }
}
