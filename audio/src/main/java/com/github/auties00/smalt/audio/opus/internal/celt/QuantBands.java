/* Ports libopus 1.5.2 (celt/quant_bands.c). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Quantizes and reconstructs the CELT band energies: coarse energy with inter-frame prediction
 * and a Laplace model, fine energy with raw bits, and the final leftover bits (RFC 6716 section
 * 4.3.2).
 *
 * <p>Energies are base-2 logarithms of the band amplitudes relative to {@link #E_MEANS}.
 */
public final class QuantBands {
    /**
     * The mean log-energy of each band ({@code eMeans}).
     */
    public static final float[] E_MEANS = {
            6.437500f, 6.250000f, 5.750000f, 5.312500f, 5.062500f,
            4.812500f, 4.500000f, 4.375000f, 4.875000f, 4.687500f,
            4.562500f, 4.437500f, 4.875000f, 4.625000f, 4.312500f,
            4.500000f, 4.375000f, 4.625000f, 4.750000f, 4.437500f,
            3.750000f, 3.750000f, 3.750000f, 3.750000f, 3.750000f
    };

    /**
     * The inter-frame prediction coefficient of each LM ({@code pred_coef}).
     */
    private static final float[] PRED_COEF = {(float) (29440 / 32768.), (float) (26112 / 32768.),
            (float) (21248 / 32768.), (float) (16384 / 32768.)};

    /**
     * The intra-frame prediction coefficient of each LM ({@code beta_coef}).
     */
    private static final float[] BETA_COEF = {(float) (30147 / 32768.), (float) (22282 / 32768.),
            (float) (12124 / 32768.), (float) (6554 / 32768.)};

    /**
     * The intra-frame prediction coefficient of intra frames ({@code beta_intra}).
     */
    private static final float BETA_INTRA = (float) (4915 / 32768.);

    /**
     * The Laplace model parameters (probability of zero and decay, Q8) of each LM, prediction
     * type (inter, intra) and band ({@code e_prob_model}).
     */
    private static final short[][][] E_PROB_MODEL = {
            {
                    {72, 127, 65, 129, 66, 128, 65, 128, 64, 128, 62, 128, 64, 128,
                            64, 128, 92, 78, 92, 79, 92, 78, 90, 79, 116, 41, 115, 40,
                            114, 40, 132, 26, 132, 26, 145, 17, 161, 12, 176, 10, 177, 11},
                    {24, 179, 48, 138, 54, 135, 54, 132, 53, 134, 56, 133, 55, 132,
                            55, 132, 61, 114, 70, 96, 74, 88, 75, 88, 87, 74, 89, 66,
                            91, 67, 100, 59, 108, 50, 120, 40, 122, 37, 97, 43, 78, 50}
            },
            {
                    {83, 78, 84, 81, 88, 75, 86, 74, 87, 71, 90, 73, 93, 74,
                            93, 74, 109, 40, 114, 36, 117, 34, 117, 34, 143, 17, 145, 18,
                            146, 19, 162, 12, 165, 10, 178, 7, 189, 6, 190, 8, 177, 9},
                    {23, 178, 54, 115, 63, 102, 66, 98, 69, 99, 74, 89, 71, 91,
                            73, 91, 78, 89, 86, 80, 92, 66, 93, 64, 102, 59, 103, 60,
                            104, 60, 117, 52, 123, 44, 138, 35, 133, 31, 97, 38, 77, 45}
            },
            {
                    {61, 90, 93, 60, 105, 42, 107, 41, 110, 45, 116, 38, 113, 38,
                            112, 38, 124, 26, 132, 27, 136, 19, 140, 20, 155, 14, 159, 16,
                            158, 18, 170, 13, 177, 10, 187, 8, 192, 6, 175, 9, 159, 10},
                    {21, 178, 59, 110, 71, 86, 75, 85, 84, 83, 91, 66, 88, 73,
                            87, 72, 92, 75, 98, 72, 105, 58, 107, 54, 115, 52, 114, 55,
                            112, 56, 129, 51, 132, 40, 150, 33, 140, 29, 98, 35, 77, 42}
            },
            {
                    {42, 121, 96, 66, 108, 43, 111, 40, 117, 44, 123, 32, 120, 36,
                            119, 33, 127, 33, 134, 34, 139, 21, 147, 23, 152, 20, 158, 25,
                            154, 26, 166, 21, 173, 16, 184, 13, 184, 10, 150, 13, 139, 15},
                    {22, 178, 63, 114, 74, 82, 84, 83, 92, 82, 103, 62, 96, 72,
                            96, 67, 101, 73, 107, 72, 113, 55, 118, 52, 125, 52, 118, 52,
                            117, 55, 135, 49, 137, 39, 157, 32, 145, 29, 97, 33, 77, 40}
            }
    };

    /**
     * The inverse CDF of the low-rate energy residual ({@code small_energy_icdf}).
     */
    private static final short[] SMALL_ENERGY_ICDF = {2, 1, 0};

    private QuantBands() {
        throw new AssertionError();
    }

    /**
     * Returns the squared distance between the new and old energies, capped at 200
     * ({@code loss_distortion}).
     *
     * @param eBands    the new energies
     * @param oldEBands the old energies
     * @param start     the first band
     * @param end       the end band
     * @param len       the number of bands per channel
     * @param C         the number of channels
     * @return the distortion
     */
    private static float lossDistortion(float[] eBands, float[] oldEBands, int start, int end, int len, int C) {
        var dist = 0f;
        var c = 0;
        do {
            for (var i = start; i < end; i++) {
                var d = eBands[i + c * len] - oldEBands[i + c * len];
                dist = dist + d * d;
            }
        } while (++c < C);
        return 200 < dist ? 200 : dist;
    }

    /**
     * Encodes the coarse energies with one prediction type and returns how much the budget
     * forced the quantization away from its ideal ({@code quant_coarse_energy_impl}).
     *
     * @param m         the mode
     * @param start     the first band
     * @param end       the end band
     * @param eBands    the energies to code
     * @param oldEBands the previous energies; receives the quantized energies
     * @param budget    the total bit budget
     * @param tell      the bits used so far
     * @param probModel the Laplace model of this prediction type
     * @param error     receives the quantization error of each band
     * @param enc       the range encoder
     * @param C         the number of channels
     * @param LM        the frame size shift
     * @param intra     nonzero for intra prediction
     * @param maxDecay  the largest allowed energy decrease
     * @param lfe       nonzero for an LFE channel
     * @param prev      scratch of two floats
     * @return the badness, zero for LFE
     */
    private static int quantCoarseEnergyImpl(CeltMode m, int start, int end, float[] eBands, float[] oldEBands,
                                             int budget, int tell, short[] probModel, float[] error,
                                             RangeEncoder enc, int C, int LM, int intra, float maxDecay, int lfe,
                                             float[] prev) {
        var badness = 0;
        prev[0] = 0;
        prev[1] = 0;
        float coef;
        float beta;
        if (tell + 3 <= budget) {
            enc.encBitLogp(intra, 3);
        }
        if (intra != 0) {
            coef = 0;
            beta = BETA_INTRA;
        } else {
            beta = BETA_COEF[LM];
            coef = PRED_COEF[LM];
        }
        for (var i = start; i < end; i++) {
            var c = 0;
            do {
                int bitsLeft;
                int qi, qi0;
                float q;
                float x;
                float f, tmp;
                float oldE;
                float decayBound;
                x = eBands[i + c * m.nbEBands];
                oldE = MathOps.max(-9.f, oldEBands[i + c * m.nbEBands]);
                f = x - coef * oldE - prev[c];
                qi = (int) Math.floor(.5f + f);
                decayBound = MathOps.max(-28.f, oldEBands[i + c * m.nbEBands]) - maxDecay;
                if (qi < 0 && x < decayBound) {
                    qi += (int) (decayBound - x);
                    if (qi > 0) {
                        qi = 0;
                    }
                }
                qi0 = qi;
                tell = enc.tell();
                bitsLeft = budget - tell - 3 * C * (end - i);
                if (i != start && bitsLeft < 30) {
                    if (bitsLeft < 24) {
                        qi = Math.min(1, qi);
                    }
                    if (bitsLeft < 16) {
                        qi = Math.max(-1, qi);
                    }
                }
                if (lfe != 0 && i >= 2) {
                    qi = Math.min(qi, 0);
                }
                if (budget - tell >= 15) {
                    var pi = 2 * Math.min(i, 20);
                    qi = Laplace.laplaceEncode(enc, qi, probModel[pi] << 7, probModel[pi + 1] << 6);
                } else if (budget - tell >= 2) {
                    qi = Math.max(-1, Math.min(qi, 1));
                    enc.encIcdf(2 * qi ^ -(qi < 0 ? 1 : 0), SMALL_ENERGY_ICDF, 2);
                } else if (budget - tell >= 1) {
                    qi = Math.min(0, qi);
                    enc.encBitLogp(-qi, 1);
                } else {
                    qi = -1;
                }
                error[i + c * m.nbEBands] = f - qi;
                badness += Math.abs(qi0 - qi);
                q = (float) qi;
                tmp = coef * oldE + prev[c] + q;
                oldEBands[i + c * m.nbEBands] = tmp;
                prev[c] = prev[c] + q - beta * q;
            } while (++c < C);
        }
        return lfe != 0 ? 0 : badness;
    }

    /**
     * Encodes the coarse energies, choosing between intra and inter prediction
     * ({@code quant_coarse_energy}).
     *
     * @param m                 the mode
     * @param start             the first band
     * @param end               the end band
     * @param effEnd            the end of the bands holding signal
     * @param eBands            the energies to code
     * @param oldEBands         the previous energies; receives the quantized energies
     * @param budget            the total bit budget
     * @param error             receives the quantization error of each band
     * @param enc               the range encoder
     * @param C                 the number of channels
     * @param LM                the frame size shift
     * @param nbAvailableBytes  the bytes available for the frame
     * @param forceIntra        nonzero to force intra prediction
     * @param delayedIntra      holds the delayed intra distortion at index 0
     * @param twoPass           nonzero to try both prediction types
     * @param lossRate          the expected packet loss percentage
     * @param lfe               nonzero for an LFE channel
     * @param scratch           the encoder's scratch space
     */
    public static void quantCoarseEnergy(CeltMode m, int start, int end, int effEnd, float[] eBands, float[] oldEBands,
                                         int budget, float[] error, RangeEncoder enc, int C, int LM,
                                         int nbAvailableBytes, int forceIntra, float[] delayedIntra, int twoPass,
                                         int lossRate, int lfe, CoarseScratch scratch) {
        int intra;
        float maxDecay;
        var oldEBandsIntra = scratch.oldEBandsIntra;
        var errorIntra = scratch.errorIntra;
        var encStartState = scratch.encStartState;
        int tell;
        var badness1 = 0;
        int intraBias;
        float newDistortion;
        intra = forceIntra != 0 || (twoPass == 0 && delayedIntra[0] > 2 * C * (end - start)
                && nbAvailableBytes > (end - start) * C) ? 1 : 0;
        intraBias = (int) (((budget & 0xFFFFFFFFL) * delayedIntra[0] * lossRate) / (C * 512));
        newDistortion = lossDistortion(eBands, oldEBands, start, effEnd, m.nbEBands, C);
        tell = enc.tell();
        if (Integer.compareUnsigned(tell + 3, budget) > 0) {
            twoPass = intra = 0;
        }
        maxDecay = 16.f;
        if (end - start > 10) {
            maxDecay = MathOps.min(maxDecay, .125f * nbAvailableBytes);
        }
        if (lfe != 0) {
            maxDecay = 3.f;
        }
        encStartState.copyFrom(enc);
        System.arraycopy(oldEBands, 0, oldEBandsIntra, 0, C * m.nbEBands);
        if (twoPass != 0 || intra != 0) {
            badness1 = quantCoarseEnergyImpl(m, start, end, eBands, oldEBandsIntra, budget, tell,
                    E_PROB_MODEL[LM][1], errorIntra, enc, C, LM, 1, maxDecay, lfe, scratch.prev);
        }
        if (intra == 0) {
            var encIntraState = scratch.encIntraState;
            int tellIntra;
            int nstartBytes;
            int nintraBytes;
            int saveBytes;
            int badness2;
            tellIntra = enc.tellFrac();
            encIntraState.copyFrom(enc);
            nstartBytes = encStartState.rangeBytes();
            nintraBytes = encIntraState.rangeBytes();
            var intraBuf = encIntraState.base + nstartBytes;
            saveBytes = nintraBytes - nstartBytes;
            var intraBits = scratch.intraBits(saveBytes);
            System.arraycopy(encIntraState.buf, intraBuf, intraBits, 0, saveBytes);
            enc.copyFrom(encStartState);
            badness2 = quantCoarseEnergyImpl(m, start, end, eBands, oldEBands, budget, tell,
                    E_PROB_MODEL[LM][intra], error, enc, C, LM, 0, maxDecay, lfe, scratch.prev);
            if (twoPass != 0 && (badness1 < badness2
                    || (badness1 == badness2 && enc.tellFrac() + intraBias > tellIntra))) {
                enc.copyFrom(encIntraState);
                System.arraycopy(intraBits, 0, encIntraState.buf, intraBuf, saveBytes);
                System.arraycopy(oldEBandsIntra, 0, oldEBands, 0, C * m.nbEBands);
                System.arraycopy(errorIntra, 0, error, 0, C * m.nbEBands);
                intra = 1;
            }
        } else {
            System.arraycopy(oldEBandsIntra, 0, oldEBands, 0, C * m.nbEBands);
            System.arraycopy(errorIntra, 0, error, 0, C * m.nbEBands);
        }
        if (intra != 0) {
            delayedIntra[0] = newDistortion;
        } else {
            delayedIntra[0] = (PRED_COEF[LM] * PRED_COEF[LM]) * delayedIntra[0] + newDistortion;
        }
    }

    /**
     * Encodes the fine energy refinement of each band ({@code quant_fine_energy}).
     *
     * @param m         the mode
     * @param start     the first band
     * @param end       the end band
     * @param oldEBands the quantized energies; refined in place
     * @param error     the remaining quantization error; updated in place
     * @param fineQuant the number of fine bits of each band
     * @param enc       the range encoder
     * @param C         the number of channels
     */
    public static void quantFineEnergy(CeltMode m, int start, int end, float[] oldEBands, float[] error,
                                       int[] fineQuant, RangeEncoder enc, int C) {
        for (var i = start; i < end; i++) {
            var frac = (short) (1 << fineQuant[i]);
            if (fineQuant[i] <= 0) {
                continue;
            }
            var c = 0;
            do {
                int q2;
                float offset;
                q2 = (int) Math.floor((error[i + c * m.nbEBands] + .5f) * frac);
                if (q2 > frac - 1) {
                    q2 = frac - 1;
                }
                if (q2 < 0) {
                    q2 = 0;
                }
                enc.encBits(q2, fineQuant[i]);
                offset = (q2 + .5f) * (1 << (14 - fineQuant[i])) * (1.f / 16384) - .5f;
                oldEBands[i + c * m.nbEBands] += offset;
                error[i + c * m.nbEBands] -= offset;
            } while (++c < C);
        }
    }

    /**
     * Spends the bits left at the end of the frame on one more fine energy bit per band, by
     * priority ({@code quant_energy_finalise}).
     *
     * @param m            the mode
     * @param start        the first band
     * @param end          the end band
     * @param oldEBands    the quantized energies; refined in place
     * @param error        the remaining quantization error; updated in place
     * @param fineQuant    the number of fine bits of each band
     * @param finePriority the fine energy priority of each band
     * @param bitsLeft     the number of bits left
     * @param enc          the range encoder
     * @param C            the number of channels
     */
    public static void quantEnergyFinalize(CeltMode m, int start, int end, float[] oldEBands, float[] error,
                                           int[] fineQuant, int[] finePriority, int bitsLeft, RangeEncoder enc,
                                           int C) {
        for (var prio = 0; prio < 2; prio++) {
            for (var i = start; i < end && bitsLeft >= C; i++) {
                if (fineQuant[i] >= Rate.MAX_FINE_BITS || finePriority[i] != prio) {
                    continue;
                }
                var c = 0;
                do {
                    int q2;
                    float offset;
                    q2 = error[i + c * m.nbEBands] < 0 ? 0 : 1;
                    enc.encBits(q2, 1);
                    offset = (q2 - .5f) * (1 << (14 - fineQuant[i] - 1)) * (1.f / 16384);
                    oldEBands[i + c * m.nbEBands] += offset;
                    error[i + c * m.nbEBands] -= offset;
                    bitsLeft--;
                } while (++c < C);
            }
        }
    }

    /**
     * Decodes the coarse energies ({@code unquant_coarse_energy}).
     *
     * @param m         the mode
     * @param start     the first band
     * @param end       the end band
     * @param oldEBands the previous energies; receives the decoded energies
     * @param intra     nonzero for intra prediction
     * @param dec       the range decoder
     * @param C         the number of channels
     * @param LM        the frame size shift
     */
    public static void unquantCoarseEnergy(CeltMode m, int start, int end, float[] oldEBands, int intra,
                                           RangeDecoder dec, int C, int LM) {
        var probModel = E_PROB_MODEL[LM][intra];
        var prev0 = 0f;
        var prev1 = 0f;
        float coef;
        float beta;
        int budget;
        int tell;
        if (intra != 0) {
            coef = 0;
            beta = BETA_INTRA;
        } else {
            beta = BETA_COEF[LM];
            coef = PRED_COEF[LM];
        }
        budget = dec.storage * 8;
        for (var i = start; i < end; i++) {
            var c = 0;
            do {
                int qi;
                float q;
                float tmp;
                tell = dec.tell();
                if (budget - tell >= 15) {
                    var pi = 2 * Math.min(i, 20);
                    qi = Laplace.laplaceDecode(dec, probModel[pi] << 7, probModel[pi + 1] << 6);
                } else if (budget - tell >= 2) {
                    qi = dec.decIcdf(SMALL_ENERGY_ICDF, 2);
                    qi = (qi >> 1) ^ -(qi & 1);
                } else if (budget - tell >= 1) {
                    qi = -dec.decBitLogp(1);
                } else {
                    qi = -1;
                }
                q = (float) qi;
                var idx = i + c * m.nbEBands;
                oldEBands[idx] = MathOps.max(-9.f, oldEBands[idx]);
                var prev = c == 0 ? prev0 : prev1;
                tmp = coef * oldEBands[idx] + prev + q;
                oldEBands[idx] = tmp;
                prev = prev + q - beta * q;
                if (c == 0) {
                    prev0 = prev;
                } else {
                    prev1 = prev;
                }
            } while (++c < C);
        }
    }

    /**
     * Decodes the fine energy refinement of each band ({@code unquant_fine_energy}).
     *
     * @param m         the mode
     * @param start     the first band
     * @param end       the end band
     * @param oldEBands the decoded energies; refined in place
     * @param fineQuant the number of fine bits of each band
     * @param dec       the range decoder
     * @param C         the number of channels
     */
    public static void unquantFineEnergy(CeltMode m, int start, int end, float[] oldEBands, int[] fineQuant,
                                         RangeDecoder dec, int C) {
        for (var i = start; i < end; i++) {
            if (fineQuant[i] <= 0) {
                continue;
            }
            var c = 0;
            do {
                var q2 = dec.decBits(fineQuant[i]);
                var offset = (q2 + .5f) * (1 << (14 - fineQuant[i])) * (1.f / 16384) - .5f;
                oldEBands[i + c * m.nbEBands] += offset;
            } while (++c < C);
        }
    }

    /**
     * Decodes the final fine energy bits ({@code unquant_energy_finalise}).
     *
     * @param m            the mode
     * @param start        the first band
     * @param end          the end band
     * @param oldEBands    the decoded energies; refined in place
     * @param fineQuant    the number of fine bits of each band
     * @param finePriority the fine energy priority of each band
     * @param bitsLeft     the number of bits left
     * @param dec          the range decoder
     * @param C            the number of channels
     */
    public static void unquantEnergyFinalize(CeltMode m, int start, int end, float[] oldEBands, int[] fineQuant,
                                             int[] finePriority, int bitsLeft, RangeDecoder dec, int C) {
        for (var prio = 0; prio < 2; prio++) {
            for (var i = start; i < end && bitsLeft >= C; i++) {
                if (fineQuant[i] >= Rate.MAX_FINE_BITS || finePriority[i] != prio) {
                    continue;
                }
                var c = 0;
                do {
                    var q2 = dec.decBits(1);
                    var offset = (q2 - .5f) * (1 << (14 - fineQuant[i] - 1)) * (1.f / 16384);
                    oldEBands[i + c * m.nbEBands] += offset;
                    bitsLeft--;
                } while (++c < C);
            }
        }
    }

    /**
     * Converts band amplitudes to log-energies relative to {@link #E_MEANS} ({@code amp2Log2}).
     *
     * @param m        the mode
     * @param effEnd   the end of the bands holding signal
     * @param end      the end band
     * @param bandE    the band amplitudes
     * @param bandLogE receives the log-energies
     * @param C        the number of channels
     */
    public static void amp2Log2(CeltMode m, int effEnd, int end, float[] bandE, float[] bandLogE, int C) {
        var c = 0;
        do {
            for (var i = 0; i < effEnd; i++) {
                bandLogE[i + c * m.nbEBands] = MathOps.celtLog2(bandE[i + c * m.nbEBands]) - E_MEANS[i];
            }
            for (var i = effEnd; i < end; i++) {
                bandLogE[c * m.nbEBands + i] = -14.f;
            }
        } while (++c < C);
    }

    /**
     * Holds the scratch space of {@link #quantCoarseEnergy}.
     */
    public static final class CoarseScratch {
        /**
         * The energies of the intra trial.
         */
        final float[] oldEBandsIntra;

        /**
         * The quantization error of the intra trial.
         */
        final float[] errorIntra;

        /**
         * The range coder state before the first trial.
         */
        final RangeEncoder encStartState = new RangeEncoder();

        /**
         * The range coder state after the intra trial.
         */
        final RangeEncoder encIntraState = new RangeEncoder();

        /**
         * The prediction memory of each channel.
         */
        final float[] prev = new float[2];

        /**
         * The bytes written by the intra trial, grown on demand.
         */
        private byte[] intraBits = new byte[64];

        /**
         * Creates the scratch space for a mode and channel count.
         *
         * @param nbEBands the number of bands
         * @param channels the number of channels
         */
        public CoarseScratch(int nbEBands, int channels) {
            this.oldEBandsIntra = new float[nbEBands * channels];
            this.errorIntra = new float[nbEBands * channels];
        }

        /**
         * Returns a byte buffer of at least {@code size} bytes.
         *
         * @param size the required size
         * @return the buffer
         */
        byte[] intraBits(int size) {
            if (intraBits.length < size) {
                intraBits = new byte[Math.max(size, 2 * intraBits.length)];
            }
            return intraBits;
        }
    }
}
