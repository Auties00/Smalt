package com.github.auties00.smalt.audio.ac3.internal;

import java.util.Arrays;

/**
 * Runs the parametric bit allocation of ATSC A/52 section 7.2.2, shared by the decoder and the
 * encoder so both derive the same bit allocation pointers from the same side information.
 *
 * <p>The routine is defined in exact integer arithmetic: the exponents of one exponent set map to
 * a power spectral density, which is integrated over the 50 bit allocation bands, spread into an
 * excitation function by the prototype masking curve, compared with the hearing threshold, shifted
 * by the delta bit allocation and the SNR offset, and finally turned into one bit allocation
 * pointer per mantissa. An instance holds the scratch arrays of one run and is reused. Instances
 * are not thread-safe.
 */
public final class BitAllocation {
    /**
     * The integrated power spectral density of each band, zero beyond the bands of the set.
     */
    final int[] bandPsd = new int[51];

    /**
     * The excitation function of each band.
     */
    private final int[] excite = new int[51];

    /**
     * The masking curve of each band.
     */
    private final int[] mask = new int[51];

    /**
     * The first band of the last {@link #spread(int[], int, int, Parameters, int, int, int)}.
     */
    private int spreadStart;

    /**
     * One past the last band of the last {@link #spread(int[], int, int, Parameters, int, int, int)},
     * equal to {@link #spreadStart} when the set was empty.
     */
    private int spreadEnd;

    /**
     * Creates the scratch state of a run.
     */
    public BitAllocation() {
    }

    /**
     * Computes the bit allocation pointers of one exponent set.
     *
     * <p>The delta bit allocation, when {@code deltaSegments} is positive, raises or lowers the
     * masking curve of the bands it names by multiples of 6 dB; segments reaching beyond band 49
     * are clipped. Every pointer outside {@code [start, end)} is left untouched.
     *
     * @param exponents the exponents, indexed by mantissa
     * @param start the first mantissa of the set
     * @param end one past the last mantissa of the set
     * @param parameters the bit allocation parameters of the block
     * @param fastGain the fast gain {@code fgain}
     * @param snrOffset the combined SNR offset {@code snroffset}
     * @param fastLeak the initial fast leak of the coupling channel, ignored when
     * {@code start} is zero
     * @param slowLeak the initial slow leak of the coupling channel, ignored when
     * {@code start} is zero
     * @param deltaSegments the number of delta bit allocation segments, {@code 0} for none
     * @param deltaOffsets the band offset of each segment
     * @param deltaLengths the band count of each segment
     * @param deltaValues the 3-bit delta code of each segment
     * @param table the pointer table, {@link Ac3Tables#BAP} or {@link Ac3Tables#HEBAP}
     * @param pointers receives the pointers, indexed by mantissa
     */
    public void allocate(int[] exponents, int start, int end, Parameters parameters, int fastGain, int snrOffset,
                         int fastLeak, int slowLeak, int deltaSegments, int[] deltaOffsets,
                         int[] deltaLengths, int[] deltaValues, int[] table, int[] pointers) {
        if (end <= start) {
            return;
        }
        mask(exponents, start, end, parameters, fastGain, fastLeak, slowLeak, mask);
        if (deltaSegments > 0) {
            var band = 0;
            for (var segment = 0; segment < deltaSegments; segment++) {
                band += deltaOffsets[segment];
                var code = deltaValues[segment];
                var delta = code >= 4 ? (code - 3) << 7 : (code - 4) << 7;
                for (var k = 0; k < deltaLengths[segment] && band < 50; k++) {
                    mask[band] += delta;
                    band++;
                }
            }
        }
        pointers(exponents, start, end, mask, snrOffset, parameters.floor, table, pointers);
    }

    /**
     * Computes the masking curve of one exponent set without delta bit allocation: the steps of
     * A/52 sections 7.2.2.2 to 7.2.2.5 up to and including the comparison with the hearing
     * threshold.
     *
     * <p>The curve does not depend on the SNR offset, so an encoder searching the offset computes
     * it once per exponent set and derives the pointers of every candidate offset with
     * {@link #pointers(int[], int, int, int[], int, int, int[], int[])}.
     *
     * @param exponents the exponents, indexed by mantissa
     * @param start the first mantissa of the set
     * @param end one past the last mantissa of the set
     * @param parameters the bit allocation parameters of the block
     * @param fastGain the fast gain {@code fgain}
     * @param fastLeak the initial fast leak of the coupling channel, ignored when {@code start}
     * is zero
     * @param slowLeak the initial slow leak of the coupling channel, ignored when {@code start}
     * is zero
     * @param out receives the masking curve, indexed by band; bands outside the set are
     * left untouched
     */
    public void mask(int[] exponents, int start, int end, Parameters parameters, int fastGain, int fastLeak,
                     int slowLeak, int[] out) {
        spread(exponents, start, end, parameters, fastGain, fastLeak, slowLeak);
        finish(parameters, out);
    }

    /**
     * Runs the part of {@link #mask(int[], int, int, Parameters, int, int, int, int[])} that does
     * not depend on the dB per bit code: the integration of the power spectral density over the
     * bands and the excitation function (A/52 sections 7.2.2.2 to 7.2.2.4), kept in this instance
     * for {@link #finish(Parameters, int[])}.
     *
     * <p>The spread reads only the decay and gain parameters; {@link #finish(Parameters, int[])}
     * reads the rate and the dB per bit code, so an encoder comparing dB per bit codes spreads an
     * exponent set once and finishes it once per code.
     *
     * @param exponents the exponents, indexed by mantissa
     * @param start the first mantissa of the set
     * @param end one past the last mantissa of the set
     * @param parameters the bit allocation parameters of the block
     * @param fastGain the fast gain {@code fgain}
     * @param fastLeak the initial fast leak of the coupling channel, ignored when {@code start}
     * is zero
     * @param slowLeak the initial slow leak of the coupling channel, ignored when {@code start}
     * is zero
     */
    public void spread(int[] exponents, int start, int end, Parameters parameters, int fastGain, int fastLeak,
                       int slowLeak) {
        if (end <= start) {
            spreadStart = 0;
            spreadEnd = 0;
            return;
        }
        integrate(exponents, start, end);
        var bandStart = Ac3Tables.MASK[start];
        var bandEnd = Ac3Tables.MASK[end - 1] + 1;
        excitation(bandStart, bandEnd, parameters, fastGain, fastLeak, slowLeak);
        spreadStart = bandStart;
        spreadEnd = bandEnd;
    }

    /**
     * Completes the masking curve of the exponent set spread last by
     * {@link #spread(int[], int, int, Parameters, int, int, int)}: applies the dB per bit knee and
     * the hearing threshold (A/52 section 7.2.2.5) of a set of parameters.
     *
     * <p>The spread state is not changed, so one spread may be finished under several parameter
     * sets that differ only in the dB per bit code.
     *
     * @param parameters the bit allocation parameters, of which the rate and the dB per bit code
     * are read
     * @param out receives the masking curve, indexed by band; bands outside the set are
     * left untouched
     */
    public void finish(Parameters parameters, int[] out) {
        var threshold = Ac3Tables.HEARING_THRESHOLD[parameters.rateIndex];
        var knee = parameters.dbKnee;
        for (var band = spreadStart; band < spreadEnd; band++) {
            var level = bandPsd[band];
            var excitation = excite[band];
            if (level < knee) {
                excitation += (knee - level) >> 2;
            }
            out[band] = Math.max(excitation, threshold[band]);
        }
    }

    /**
     * Derives the bit allocation pointers of one exponent set from its masking curve and an SNR
     * offset (A/52 section 7.2.2.7).
     *
     * @param exponents the exponents, indexed by mantissa
     * @param start the first mantissa of the set
     * @param end one past the last mantissa of the set
     * @param mask the masking curve, indexed by band
     * @param snrOffset the combined SNR offset {@code snroffset}
     * @param floor the masking floor {@code floor}
     * @param table the pointer table, {@link Ac3Tables#BAP} or {@link Ac3Tables#HEBAP}
     * @param pointers receives the pointers, indexed by mantissa
     */
    public static void pointers(int[] exponents, int start, int end, int[] mask, int snrOffset, int floor, int[] table,
                                int[] pointers) {
        if (end <= start) {
            return;
        }
        var bin = start;
        var band = Ac3Tables.MASK[start];
        int last;
        do {
            last = Math.min(Ac3Tables.BAND_START[band + 1], end);
            var level = mask[band] - snrOffset - floor;
            if (level < 0) {
                level = 0;
            }
            level = (level & 0x1fe0) + floor;
            for (; bin < last; bin++) {
                var address = (3072 - (exponents[bin] << 7) - level) >> 5;
                pointers[bin] = table[Math.min(63, Math.max(0, address))];
            }
            band++;
        } while (end > last);
    }

    /**
     * Integrates the power spectral density {@code 3072 - (exponent << 7)} of every mantissa over
     * the bands with log-addition (A/52 sections 7.2.2.2 and 7.2.2.3).
     *
     * @implNote This implementation computes the density of each mantissa as it adds it and
     * spells the log-addition of A/52 section 7.2.2.3 as the larger power plus the table entry of
     * the difference, which selects the same operand as the branch of the specification without a
     * data-dependent branch: the sign of the difference is random on real spectra. The chain of
     * dependent table lookups of one band is the latency of the routine, so four consecutive bands
     * of equal width, as the bands of 3, 6, 12 and 24 mantissas come in runs, advance together in
     * one loop and the processor overlaps their chains; each band still folds its mantissas in
     * ascending order.
     *
     * @param exponents the exponents, indexed by mantissa
     * @param start the first mantissa
     * @param end one past the last mantissa
     */
    void integrate(int[] exponents, int start, int end) {
        Arrays.fill(bandPsd, 0);
        var table = Ac3Tables.LOG_ADD;
        var starts = Ac3Tables.BAND_START;
        var bin = start;
        var band = Ac3Tables.MASK[start];
        int last;
        do {
            last = Math.min(starts[band + 1], end);
            var width = last - bin;
            if (width >= 3 && band + 4 < starts.length && starts[band + 4] <= end && bin == starts[band]
                    && starts[band + 1] - bin == width && starts[band + 2] - starts[band + 1] == width
                    && starts[band + 3] - starts[band + 2] == width && starts[band + 4] - starts[band + 3] == width) {
                var b1 = bin + width;
                var b2 = b1 + width;
                var b3 = b2 + width;
                var sum0 = 3072 - (exponents[bin] << 7);
                var sum1 = 3072 - (exponents[b1] << 7);
                var sum2 = 3072 - (exponents[b2] << 7);
                var sum3 = 3072 - (exponents[b3] << 7);
                for (var k = 1; k < width; k++) {
                    var power0 = 3072 - (exponents[bin + k] << 7);
                    var power1 = 3072 - (exponents[b1 + k] << 7);
                    var power2 = 3072 - (exponents[b2 + k] << 7);
                    var power3 = 3072 - (exponents[b3 + k] << 7);
                    sum0 = Math.max(sum0, power0) + table[Math.min(Math.abs(sum0 - power0) >> 1, 255)];
                    sum1 = Math.max(sum1, power1) + table[Math.min(Math.abs(sum1 - power1) >> 1, 255)];
                    sum2 = Math.max(sum2, power2) + table[Math.min(Math.abs(sum2 - power2) >> 1, 255)];
                    sum3 = Math.max(sum3, power3) + table[Math.min(Math.abs(sum3 - power3) >> 1, 255)];
                }
                bandPsd[band] = sum0;
                bandPsd[band + 1] = sum1;
                bandPsd[band + 2] = sum2;
                bandPsd[band + 3] = sum3;
                band += 4;
                bin = starts[band];
                last = bin;
                continue;
            }
            var sum = 3072 - (exponents[bin++] << 7);
            for (; bin < last; bin++) {
                var power = 3072 - (exponents[bin] << 7);
                var address = Math.min(Math.abs(sum - power) >> 1, 255);
                sum = Math.max(sum, power) + table[address];
            }
            bandPsd[band] = sum;
            band++;
        } while (end > last);
    }

    /**
     * Computes the excitation function (A/52 section 7.2.2.4).
     *
     * <p>The low-frequency compensation of a band compares its power with the next band's, so the
     * last band of a set that ends below band 22 keeps the compensation of the band before it: the
     * rule A/52 states for the 7-band set of the LFE channel, which E-AC-3 also meets in a channel
     * that enhanced coupling cuts off at mantissa 13. A coupling channel that starts below band 22,
     * which only enhanced coupling allows, applies the compensation from its first band on, starting
     * from zero, as a full-bandwidth channel does above band 7.
     *
     * @implNote This implementation fills a gap of A/52 and ETSI TS 102 366, whose excitation
     * pseudo-code compares with a band beyond the set and applies the compensation to
     * full-bandwidth and LFE channels only; both rules match the output of a reference decoder used
     * as a black box on streams coupled from mantissa 13, which fail to parse without them.
     *
     * @param bandStart the first band
     * @param bandEnd one past the last band
     * @param parameters the bit allocation parameters
     * @param fastGain the fast gain
     * @param fastLeak the initial fast leak of the coupling channel
     * @param slowLeak the initial slow leak of the coupling channel
     */
    private void excitation(int bandStart, int bandEnd, Parameters parameters, int fastGain, int fastLeak,
                            int slowLeak) {
        var slowGain = parameters.slowGain;
        var fastDecay = parameters.fastDecay;
        var slowDecay = parameters.slowDecay;
        int begin;
        if (bandStart == 0) {
            var lastBand = bandEnd - 1;
            var lowComp = lowCompensation(0, bandPsd[0], bandPsd[1], 0);
            excite[0] = bandPsd[0] - fastGain - lowComp;
            lowComp = lowCompensation(lowComp, bandPsd[1], bandPsd[2], 1);
            excite[1] = bandPsd[1] - fastGain - lowComp;
            begin = 7;
            for (var band = 2; band < 7; band++) {
                var last = band == lastBand;
                if (!last) {
                    lowComp = lowCompensation(lowComp, bandPsd[band], bandPsd[band + 1], band);
                }
                fastLeak = bandPsd[band] - fastGain;
                slowLeak = bandPsd[band] - slowGain;
                excite[band] = fastLeak - lowComp;
                if (!last && bandPsd[band] <= bandPsd[band + 1]) {
                    begin = band + 1;
                    break;
                }
            }
            var limit = Math.min(bandEnd, 22);
            for (var band = begin; band < limit; band++) {
                if (band != lastBand) {
                    lowComp = lowCompensation(lowComp, bandPsd[band], bandPsd[band + 1], band);
                }
                fastLeak -= fastDecay;
                fastLeak = Math.max(fastLeak, bandPsd[band] - fastGain);
                slowLeak -= slowDecay;
                slowLeak = Math.max(slowLeak, bandPsd[band] - slowGain);
                excite[band] = Math.max(fastLeak - lowComp, slowLeak);
            }
            begin = 22;
        } else if (bandStart < 22) {
            var lastBand = bandEnd - 1;
            var lowComp = 0;
            var limit = Math.min(bandEnd, 22);
            for (var band = bandStart; band < limit; band++) {
                if (band != lastBand) {
                    lowComp = lowCompensation(lowComp, bandPsd[band], bandPsd[band + 1], band);
                }
                fastLeak -= fastDecay;
                fastLeak = Math.max(fastLeak, bandPsd[band] - fastGain);
                slowLeak -= slowDecay;
                slowLeak = Math.max(slowLeak, bandPsd[band] - slowGain);
                excite[band] = Math.max(fastLeak - lowComp, slowLeak);
            }
            begin = limit;
        } else {
            begin = bandStart;
        }
        for (var band = begin; band < bandEnd; band++) {
            fastLeak -= fastDecay;
            fastLeak = Math.max(fastLeak, bandPsd[band] - fastGain);
            slowLeak -= slowDecay;
            slowLeak = Math.max(slowLeak, bandPsd[band] - slowGain);
            excite[band] = Math.max(fastLeak, slowLeak);
        }
    }

    /**
     * Updates the low-frequency compensation term {@code calc_lowcomp} of A/52 section 7.2.2.4.
     *
     * @param a the current compensation
     * @param b0 the power of the band
     * @param b1 the power of the next band
     * @param band the band index of {@code b0}
     * @return the new compensation
     */
    private static int lowCompensation(int a, int b0, int b1, int band) {
        if (band < 7) {
            if (b0 + 256 == b1) {
                return 384;
            }
            if (b0 > b1) {
                return Math.max(0, a - 64);
            }
            return a;
        }
        if (band < 20) {
            if (b0 + 256 == b1) {
                return 320;
            }
            if (b0 > b1) {
                return Math.max(0, a - 64);
            }
            return a;
        }
        return Math.max(0, a - 128);
    }

    /**
     * Holds the bit allocation parameters a block shares among its channels.
     */
    public static final class Parameters {
        /**
         * The row of the hearing threshold table: the sampling rate code.
         */
        int rateIndex;

        /**
         * The slow decay {@code sdecay}.
         */
        int slowDecay;

        /**
         * The fast decay {@code fdecay}.
         */
        int fastDecay;

        /**
         * The slow gain {@code sgain}.
         */
        int slowGain;

        /**
         * The knee {@code dbknee}.
         */
        int dbKnee;

        /**
         * The masking floor {@code floor}.
         */
        int floor;

        /**
         * Creates parameters set to the codes {@code 0}.
         */
        public Parameters() {
            set(0, 0, 0, 0, 0, 0);
        }

        /**
         * Sets the parameters from their codes.
         *
         * @param rateIndex the hearing threshold row, from 0 to 2
         * @param slowDecayCode {@code sdcycod}
         * @param fastDecayCode {@code fdcycod}
         * @param slowGainCode {@code sgaincod}
         * @param dbPerBitCode {@code dbpbcod}
         * @param floorCode {@code floorcod}
         */
        public void set(int rateIndex, int slowDecayCode, int fastDecayCode, int slowGainCode, int dbPerBitCode,
                        int floorCode) {
            this.rateIndex = rateIndex;
            this.slowDecay = Ac3Tables.SLOW_DECAY[slowDecayCode];
            this.fastDecay = Ac3Tables.FAST_DECAY[fastDecayCode];
            this.slowGain = Ac3Tables.SLOW_GAIN[slowGainCode];
            this.dbKnee = Ac3Tables.DB_PER_BIT[dbPerBitCode];
            this.floor = Ac3Tables.FLOOR[floorCode];
        }
    }
}
