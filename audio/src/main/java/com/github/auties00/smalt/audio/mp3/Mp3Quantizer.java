package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;

import java.util.Arrays;

/**
 * Quantizes the spectrum of one granule of one channel so that the noise of every coding band
 * stays under a target, and counts the bits of the result.
 *
 * <p>{@link #prepare} takes a granule's transform lines, band energies and masking thresholds.
 * {@link #quantize} then codes the granule for a noise offset {@code lambda} in decibels: every
 * band's target is its masking threshold raised by {@code lambda}. A band whose energy is under
 * its target is set to zero. Every other band gets the quantizer step whose estimated noise meets
 * its target; the coarsest of these steps becomes the global gain, and the scalefactors (with
 * {@code scalefac_scale}, {@code preflag} and, for short blocks, the subblock gains) refine the
 * others. The noise of each band is then measured and its scalefactor corrected up to two times.
 * Raising {@code lambda} lowers the bit count, which the frame encoder's rate control uses to fit
 * its budget.
 *
 * @implNote This implementation estimates a band's step from the noise of the power-law
 * quantizer, {@code N = 4/27 Q^(3/2) sum sqrt|x|}, the estimate 3GPP TS 26.403 5.6.2.1 uses for
 * AAC, whose quantizer is the same; the Layer III rounding offset of 0.4054 follows
 * ISO/IEC 11172-3 C.1.5.4.4.1.
 */
final class Mp3Quantizer {
    /**
     * The offset of {@link #SCALES} and {@link #STEPS}: the lowest effective gain they cover is
     * {@code -OFFSET}.
     */
    private static final int OFFSET = 256;

    /**
     * The quantizer scale {@code 2^(-3 (g - 210) / 16)} of every effective gain {@code g} from
     * {@code -256} to {@code 255}.
     */
    private static final float[] SCALES = new float[512];

    /**
     * The quantizer step {@code 2^((g - 210) / 4)} of every effective gain {@code g} from
     * {@code -256} to {@code 255}.
     */
    private static final float[] STEPS = new float[512];

    /**
     * The noise ratio over which a band's scalefactor is raised.
     */
    private static final float NOISE_HIGH = 1.4f;

    /**
     * The noise ratio under which a band's scalefactor is lowered.
     */
    private static final float NOISE_LOW = 0.35f;

    /**
     * The largest scalefactor of the long bands with 4-bit scalefactors (0 to 10) and the short
     * bands 0 to 5.
     */
    private static final int MAX_SF_LOW = 15;

    /**
     * The largest scalefactor of the long bands 11 to 20 and the short bands 6 to 11.
     */
    private static final int MAX_SF_HIGH = 7;

    /**
     * The change of a band's desired gain, in quarter steps, per decibel of noise offset:
     * {@code 8/3 log2(10) / 10}.
     */
    private static final float GAIN_PER_DECIBEL = (float) (8.0 / 3 * Math.log(10) / Math.log(2) / 10);

    static {
        for (var i = 0; i < 512; i++) {
            var g = i - OFFSET;
            SCALES[i] = (float) Math.pow(2, -3.0 * (g - 210) / 16);
            STEPS[i] = (float) Math.pow(2, (g - 210) / 4.0);
        }
    }

    /**
     * The DSP kernel.
     */
    private final Mp3EncoderKernel kernel;

    /**
     * Whether the stream is MPEG-1 (two granules, 4-bit {@code scalefac_compress}).
     */
    private final boolean mpeg1;

    /**
     * The Huffman coder.
     */
    private final Mp3Huffman huffman = new Mp3Huffman();

    /**
     * The magnitudes of the transform lines.
     */
    private final float[] magnitude = new float[576];

    /**
     * The magnitudes raised to the power 3/4.
     */
    private final float[] pow34 = new float[576];

    /**
     * Whether each line is negative.
     */
    private final boolean[] negative = new boolean[576];

    /**
     * The quantized magnitudes, in transform layout.
     */
    private final int[] quantized = new int[576];

    /**
     * The band layout of the prepared granule.
     */
    private Mp3BandLayout layout;

    /**
     * The block type of the prepared granule.
     */
    private int blockType;

    /**
     * The energy of each band.
     */
    private final float[] energy = new float[39];

    /**
     * The masking threshold of each band.
     */
    private final float[] threshold = new float[39];

    /**
     * The largest magnitude raised to the power 3/4 of each band.
     */
    private final float[] maxPow34 = new float[39];

    /**
     * The noise target of each band in the current pass.
     */
    private final float[] target = new float[39];

    /**
     * The desired effective gain of each band, {@link Integer#MIN_VALUE} for bands set to zero.
     */
    private final int[] desired = new int[39];

    /**
     * The desired gain of each band at a zero noise offset:
     * {@code 210 + 8/3 log2(6.75 T / sum sqrt|x|)}.
     */
    private final float[] baseGain = new float[39];

    /**
     * The coarsest gain at which each band keeps a non-zero value, less one quarter step.
     */
    private final float[] zeroGain = new float[39];

    /**
     * The smallest effective gain that keeps each band's values codable.
     */
    private final int[] minimumGain = new int[39];

    /**
     * Creates a quantizer.
     *
     * @param kernel the DSP kernel
     * @param mpeg1 whether the stream is MPEG-1
     */
    Mp3Quantizer(Mp3EncoderKernel kernel, boolean mpeg1) {
        this.kernel = kernel;
        this.mpeg1 = mpeg1;
    }

    /**
     * Takes the spectrum of a granule.
     *
     * @param lines the transform lines: frequency order, or window-major for short blocks
     * @param blockType the block type of {@link Mp3CodedGranule}
     * @param layout the band layout matching the block type
     * @param bandEnergy the energy of each band
     * @param bandThreshold the masking threshold of each band
     */
    void prepare(float[] lines, int blockType, Mp3BandLayout layout, float[] bandEnergy, float[] bandThreshold) {
        this.layout = layout;
        this.blockType = blockType;
        for (var i = 0; i < 576; i++) {
            var x = lines[i];
            var a = Math.abs(x);
            magnitude[i] = a;
            negative[i] = x < 0;
            pow34[i] = (float) Math.sqrt(a * Math.sqrt(a));
        }
        for (var b = 0; b < layout.count; b++) {
            var sum = 0.0f;
            var max = 0.0f;
            for (var i = layout.start[b]; i < layout.end[b]; i++) {
                sum += (float) Math.sqrt(magnitude[i]);
                max = Math.max(max, pow34[i]);
            }
            maxPow34[b] = max;
            energy[b] = bandEnergy[b];
            threshold[b] = bandThreshold[b];
            minimumGain[b] = max == 0 ? -OFFSET : (int) Math.ceil(210 + 16.0 / 3 * log2(max / 8206.0 * 1.001));
            if (max > 0 && sum > 0 && threshold[b] > 0) {
                baseGain[b] = (float) (210 + 8.0 / 3 * log2(6.75 * threshold[b] / sum));
                zeroGain[b] = (float) (210 + 16.0 / 3 * log2(max / 0.5946) - 1);
            }
        }
    }

    /**
     * Returns the energy of a band of the prepared granule.
     *
     * @param band the coding band
     * @return the energy
     */
    float energy(int band) {
        return energy[band];
    }

    /**
     * Quantizes the prepared granule for a noise offset and counts its bits.
     *
     * @param lambda the offset of every band's noise target over its masking threshold, in
     * decibels
     * @param granule receives the coding; its block type is set by this method
     * @param previous the first granule of the frame when this is the second granule of an
     * MPEG-1 frame of the same channel, to share scalefactors, or {@code null}
     * @param search whether to search the Huffman region split exhaustively
     * @return the number of main data bits, {@code part2_3_length}
     */
    int quantize(float lambda, Mp3CodedGranule granule, Mp3CodedGranule previous, boolean search) {
        var layout = this.layout;
        var count = layout.count;
        var factor = (float) Math.pow(10, lambda / 10);
        var anyActive = false;
        for (var b = 0; b < count; b++) {
            var t = threshold[b] * factor;
            target[b] = t;
            if (energy[b] <= t || maxPow34[b] == 0) {
                desired[b] = Integer.MIN_VALUE;
                continue;
            }
            anyActive = true;
            var gain = Math.min(baseGain[b] + GAIN_PER_DECIBEL * lambda, zeroGain[b]);
            desired[b] = Math.max((int) Math.floor(gain), minimumGain[b]);
        }
        granule.clear();
        granule.blockType = blockType;
        if (!anyActive) {
            granule.globalGain = 0;
            granule.scalefacCompress = 0;
            granule.part2Length = 0;
            huffman.code(granule, layout.widths, false);
            return granule.part23Length();
        }
        var shortBlock = layout.shortBlocks;
        var scale = 0;
        var globalGain = chooseGlobalGain(0);
        if (globalGain < coarsestGain()) {
            var wider = chooseGlobalGain(1);
            if (wider > globalGain) {
                scale = 1;
                globalGain = wider;
            }
        }
        globalGain = Math.clamp(globalGain, 0, 255);
        granule.globalGain = globalGain;
        granule.scalefacScale = scale;
        var unit = scale == 0 ? 2 : 4;
        var sf = granule.scalefactors;
        if (shortBlock) {
            for (var w = 0; w < 3; w++) {
                var room = Integer.MAX_VALUE;
                for (var b = w; b < count; b += 3) {
                    if (desired[b] != Integer.MIN_VALUE) {
                        room = Math.min(room, globalGain - desired[b]);
                    }
                }
                granule.subblockGain[w] = room == Integer.MAX_VALUE ? 0 : Math.clamp(room / 8, 0, 7);
            }
        }
        var preflag = false;
        for (var b = 0; b < count; b++) {
            var index = layout.scalefactor[b];
            if (index < 0 || desired[b] == Integer.MIN_VALUE) {
                continue;
            }
            var refine = globalGain - 8 * granule.subblockGain[layout.window[b]] - desired[b];
            sf[index] = Math.clamp(Math.round((float) refine / unit), 0, maxScalefactor(layout.band[b]));
        }
        if (!shortBlock && mpeg1) {
            preflag = usePreemphasis(sf, globalGain, unit);
        }
        granule.preflag = preflag;
        for (var pass = 0; pass < 3; pass++) {
            var changed = false;
            for (var b = 0; b < count; b++) {
                if (desired[b] == Integer.MIN_VALUE) {
                    zeroBand(b);
                    continue;
                }
                var gain = effectiveGain(granule, b);
                var noise = quantizeBand(b, gain);
                var index = layout.scalefactor[b];
                if (pass == 2 || index < 0) {
                    continue;
                }
                var limit = maxScalefactor(layout.band[b]);
                if (noise > target[b] * NOISE_HIGH && sf[index] < limit && gain - unit >= minimumGain[b]) {
                    sf[index]++;
                    changed = true;
                } else if (noise < target[b] * NOISE_LOW && sf[index] > 0) {
                    sf[index]--;
                    var coarser = quantizeBand(b, effectiveGain(granule, b));
                    if (coarser > target[b] * NOISE_HIGH) {
                        sf[index]++;
                        quantizeBand(b, effectiveGain(granule, b));
                    } else {
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        var values = granule.values;
        var order = layout.order;
        for (var i = 0; i < 576; i++) {
            var line = order[i];
            var q = quantized[line];
            values[i] = negative[line] ? -q : q;
        }
        granule.part2Length = scalefactorBits(granule, previous);
        huffman.code(granule, layout.widths, search);
        return granule.part23Length();
    }

    /**
     * Writes the Huffman-coded spectrum of a granule this quantizer coded.
     *
     * @param granule the coded granule
     * @param out the destination
     */
    void writeSpectrum(Mp3CodedGranule granule, MsbBitWriter out) {
        huffman.write(granule, layout.widths, out);
    }

    /**
     * Returns the global gain that lets every active band reach its desired gain with
     * scalefactors of a given step, or the coarsest desired gain when that is reachable.
     *
     * @param scale the {@code scalefac_scale} value
     * @return the global gain
     */
    private int chooseGlobalGain(int scale) {
        var unit = scale == 0 ? 2 : 4;
        var coarsest = Integer.MIN_VALUE;
        var limit = Integer.MAX_VALUE;
        for (var b = 0; b < layout.count; b++) {
            if (desired[b] == Integer.MIN_VALUE) {
                continue;
            }
            coarsest = Math.max(coarsest, desired[b]);
            limit = Math.min(limit, desired[b] + maxRefinement(b, unit));
        }
        return Math.min(coarsest, limit);
    }

    /**
     * Returns the coarsest desired gain of the active bands.
     *
     * @return the gain
     */
    private int coarsestGain() {
        var coarsest = Integer.MIN_VALUE;
        for (var b = 0; b < layout.count; b++) {
            coarsest = Math.max(coarsest, desired[b]);
        }
        return coarsest;
    }

    /**
     * Returns how many quarter steps finer than the global gain a band can be quantized.
     *
     * @param band the coding band
     * @param unit the quarter steps of one scalefactor step
     * @return the largest refinement
     */
    private int maxRefinement(int band, int unit) {
        var subblock = layout.shortBlocks ? 8 * 7 : 0;
        if (layout.scalefactor[band] < 0) {
            return subblock;
        }
        return subblock + unit * maxScalefactor(layout.band[band]);
    }

    /**
     * Returns the largest scalefactor a band's scalefactor length allows.
     *
     * @param band the scalefactor band
     * @return the largest scalefactor
     */
    private int maxScalefactor(int band) {
        if (layout.shortBlocks) {
            return band < 6 ? MAX_SF_LOW : MAX_SF_HIGH;
        }
        return band < 11 ? MAX_SF_LOW : MAX_SF_HIGH;
    }

    /**
     * Decides whether the preemphasis of the high long bands pays and, when it does, subtracts
     * it from their scalefactors.
     *
     * @param sf the scalefactors
     * @param globalGain the global gain
     * @param unit the quarter steps of one scalefactor step
     * @return whether {@code preflag} is set
     */
    private boolean usePreemphasis(int[] sf, int globalGain, int unit) {
        var pretab = Mp3EncoderTables.PRETAB;
        for (var b = 11; b < 21; b++) {
            if (desired[b] == Integer.MIN_VALUE) {
                continue;
            }
            var refine = Math.round((float) (globalGain - desired[b]) / unit);
            if (refine < pretab[b]) {
                return false;
            }
        }
        for (var b = 11; b < 21; b++) {
            if (desired[b] == Integer.MIN_VALUE) {
                sf[b] = 0;
                continue;
            }
            var refine = Math.round((float) (globalGain - desired[b]) / unit);
            sf[b] = Math.clamp(refine - pretab[b], 0, MAX_SF_HIGH);
        }
        return true;
    }

    /**
     * Returns the effective gain of a band under the granule's current scalefactors.
     *
     * @param granule the coded granule
     * @param band the coding band
     * @return the gain in quarter steps
     */
    private int effectiveGain(Mp3CodedGranule granule, int band) {
        var unit = granule.scalefacScale == 0 ? 2 : 4;
        var gain = granule.globalGain;
        if (layout.shortBlocks) {
            gain -= 8 * granule.subblockGain[layout.window[band]];
        }
        var index = layout.scalefactor[band];
        if (index >= 0) {
            var sf = granule.scalefactors[index];
            if (granule.preflag) {
                sf += Mp3EncoderTables.PRETAB[layout.band[band]];
            }
            gain -= unit * sf;
        }
        return gain;
    }

    /**
     * Quantizes one band at a gain and returns its noise energy.
     *
     * @param band the coding band
     * @param gain the effective gain
     * @return the energy of the quantization error
     */
    private float quantizeBand(int band, int gain) {
        var start = layout.start[band];
        var end = layout.end[band];
        gain = Math.max(gain, minimumGain[band]);
        var index = Math.clamp(gain + OFFSET, 0, 511);
        var max = kernel.quantize(pow34, start, end, SCALES[index], quantized);
        if (max > Mp3EncoderTables.MAX_QUANTIZED) {
            for (var i = start; i < end; i++) {
                quantized[i] = Math.min(quantized[i], Mp3EncoderTables.MAX_QUANTIZED);
            }
        }
        var step = STEPS[index];
        var pow43 = Mp3EncoderTables.POW43;
        var noise = 0.0f;
        for (var i = start; i < end; i++) {
            var error = magnitude[i] - pow43[quantized[i]] * step;
            noise += error * error;
        }
        return noise;
    }

    /**
     * Sets a band's quantized values to zero.
     *
     * @param band the coding band
     */
    private void zeroBand(int band) {
        Arrays.fill(quantized, layout.start[band], layout.end[band], 0);
    }

    /**
     * Chooses the {@code scalefac_compress} value (and, for the second MPEG-1 granule, the
     * scalefactor sharing) of a granule and returns the scalefactor bits.
     *
     * @param granule the granule, whose scalefactors are set
     * @param previous the first granule of the frame for MPEG-1 sharing, or {@code null}
     * @return {@code part2_length}
     */
    private int scalefactorBits(Mp3CodedGranule granule, Mp3CodedGranule previous) {
        var sf = granule.scalefactors;
        if (mpeg1) {
            if (granule.blockType == Mp3CodedGranule.SHORT) {
                var max1 = 0;
                var max2 = 0;
                for (var i = 0; i < 18; i++) {
                    max1 = Math.max(max1, sf[i]);
                }
                for (var i = 18; i < 36; i++) {
                    max2 = Math.max(max2, sf[i]);
                }
                var best = bestCompress(max1, max2);
                granule.scalefacCompress = best;
                return 18 * Mp3EncoderTables.SLEN1[best] + 18 * Mp3EncoderTables.SLEN2[best];
            }
            var shared = previous != null && previous.blockType != Mp3CodedGranule.SHORT;
            var groups = Mp3EncoderTables.SCFSI_BANDS;
            var max1 = 0;
            var max2 = 0;
            for (var g = 0; g < 4; g++) {
                var same = shared;
                for (var b = groups[g]; b < groups[g + 1] && same; b++) {
                    same = sf[b] == previous.scalefactors[b];
                }
                granule.scfsi[g] = same;
                if (same) {
                    continue;
                }
                for (var b = groups[g]; b < groups[g + 1]; b++) {
                    if (b < 11) {
                        max1 = Math.max(max1, sf[b]);
                    } else {
                        max2 = Math.max(max2, sf[b]);
                    }
                }
            }
            var best = bestCompress(max1, max2);
            granule.scalefacCompress = best;
            var bits = 0;
            for (var g = 0; g < 4; g++) {
                if (!granule.scfsi[g]) {
                    var slen = g < 2 ? Mp3EncoderTables.SLEN1[best] : Mp3EncoderTables.SLEN2[best];
                    bits += (groups[g + 1] - groups[g]) * slen;
                }
            }
            return bits;
        }
        return lsfScalefactorBits(granule);
    }

    /**
     * Returns the MPEG-1 {@code scalefac_compress} value with the fewest bits whose lengths hold
     * two maxima.
     *
     * @param max1 the largest scalefactor coded with {@code slen1}
     * @param max2 the largest scalefactor coded with {@code slen2}
     * @return the value, from 0 to 15
     */
    private static int bestCompress(int max1, int max2) {
        var best = 15;
        var bestBits = Integer.MAX_VALUE;
        for (var c = 0; c < 16; c++) {
            var s1 = Mp3EncoderTables.SLEN1[c];
            var s2 = Mp3EncoderTables.SLEN2[c];
            if (max1 >= 1 << s1 || max2 >= 1 << s2) {
                continue;
            }
            var bits = 11 * s1 + 10 * s2;
            if (bits < bestBits) {
                bestBits = bits;
                best = c;
            }
        }
        return best;
    }

    /**
     * Chooses the MPEG-2 {@code scalefac_compress} value of a granule, switching to the
     * preemphasis table when it holds the scalefactors, and returns the scalefactor bits.
     *
     * @param granule the coded granule
     * @return {@code part2_length}
     */
    private int lsfScalefactorBits(Mp3CodedGranule granule) {
        var sf = granule.scalefactors;
        var shortBlock = granule.blockType == Mp3CodedGranule.SHORT;
        var entries = shortBlock ? 36 : 21;
        var bestBits = Integer.MAX_VALUE;
        var bestCompress = 0;
        for (var table = 0; table < 2; table++) {
            var partitions = Mp3EncoderTables.LSF_PARTITIONS[table][shortBlock ? 1 : 0];
            var slen = new int[4];
            var position = 0;
            for (var p = 0; p < 4; p++) {
                var max = 0;
                for (var k = 0; k < partitions[p]; k++, position++) {
                    max = Math.max(max, sf[position]);
                }
                slen[p] = 32 - Integer.numberOfLeadingZeros(max);
            }
            var limits = table == 0 ? new int[]{4, 4, 3, 3} : new int[]{4, 4, 3, 0};
            var fits = true;
            for (var p = 0; p < 4; p++) {
                fits &= slen[p] <= limits[p];
            }
            if (!fits || position < entries) {
                continue;
            }
            var bits = 0;
            for (var p = 0; p < 4; p++) {
                bits += partitions[p] * slen[p];
            }
            if (bits < bestBits) {
                bestBits = bits;
                bestCompress = table == 0
                        ? ((slen[0] * 5 + slen[1]) << 4) + (slen[2] << 2) + slen[3]
                        : 400 + ((slen[0] * 5 + slen[1]) << 2) + slen[2];
            }
        }
        if (bestBits == Integer.MAX_VALUE) {
            for (var i = 0; i < entries; i++) {
                sf[i] = Math.min(sf[i], i < entries * 0.5 ? 15 : 7);
            }
            return lsfScalefactorBits(granule);
        }
        granule.scalefacCompress = bestCompress;
        return bestBits;
    }

    /**
     * Returns the base-2 logarithm.
     *
     * @param value the value, positive
     * @return {@code log2(value)}
     */
    private static double log2(double value) {
        return Math.log(value) * 1.4426950408889634;
    }
}
