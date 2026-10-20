package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;

import java.util.Arrays;
import java.util.Objects;

/**
 * Measures loudness as ITU-R BS.1770-4 and EBU R128 define it, streaming over blocks of samples.
 *
 * <p>Samples go through the K-weighting filter (a high-shelf pre-filter and the RLB high-pass) of
 * each channel; the mean square of every channel over a window, multiplied by the channel's
 * weight and summed over the channels, is the window's energy, and its loudness is
 * {@code -0.691 + 10 log10(energy)} LUFS. From that the meter reports:
 * <ul>
 *   <li>the momentary loudness (the last 400 ms) and the short-term loudness (the last 3 s), as
 *       of the end of the last complete 10 ms step, and their maxima over the program, taken
 *       every 10 ms;</li>
 *   <li>the integrated loudness: the 400 ms blocks every 100 ms (75% overlap) above the absolute
 *       gate of -70 LUFS, then above a relative gate 10 LU below their mean, averaged in the
 *       energy domain;</li>
 *   <li>the loudness range (EBU Tech 3342): the short-term blocks every 100 ms above -70 LUFS
 *       and above a relative gate 20 LU below their mean, from their 10th to their 95th
 *       percentile;</li>
 *   <li>the sample peak and the true peak (BS.1770-4 Annex 2: the signal oversampled four times
 *       below 96 kHz, twice below 192 kHz) of each channel.</li>
 * </ul>
 *
 * <p>Channel weights follow BS.1770-4 from the {@link ChannelLayout}: 1.0 for the front channels
 * (left, right, center, the front-of-center and wide pairs), 1.41 for the side and surround direct
 * channels and for the back pair of a layout without side channels (the surrounds of 5.1 and 4.0
 * layouts are either), 1.0 for the other back, height and bottom channels, and 0 for the
 * low-frequency effects channels, which are left out. {@link LoudnessMeterOptions} can replace
 * them, or weight mono as dual mono.
 *
 * <p>Memory is bounded whatever the duration: the gated blocks are kept as histograms of 1000
 * bins of 0.1 LU from -70 to +30 LUFS, each bin holding its block count and the sum of the
 * blocks' energies, so means are exact and only the blocks that share a bin with a relative
 * gate (within 0.1 LU of it) are classified by their bin.
 *
 * <p>An instance is not thread-safe.
 *
 * @implNote This implementation ports the measurement algorithms of libebur128 (MIT): the
 * K-weighting coefficients for any sampling rate ({@code ebur128_init_filter}), the 100 ms steps of
 * {@code samples_in_100ms}, the gating of {@code ebur128_gated_loudness}, the loudness range of
 * {@code ebur128_loudness_range_multiple} with its nearest-rank percentiles, and the true-peak
 * interpolator. Three choices differ: libebur128's histogram mode keeps counts only and uses the
 * bin centers, while this implementation also sums the energies of each bin, which makes the
 * results match libebur128's exact mode except at the gate boundaries; the loudness range takes
 * a short-term block every 100 ms, the 10 Hz rate EBU Tech 3342 requires, where libebur128 takes
 * one a second; and the maxima of the momentary and short-term loudness are taken every 10 ms, so
 * that a 400 ms burst that starts between two 100 ms steps (EBU Tech 3341 test cases 10 and 13)
 * is measured whole. The filter runs in double precision with denormals flushed at the end of
 * each 100 ms step, as libebur128 does through the flush-to-zero mode of the processor.
 */
public final class LoudnessMeter {
    /**
     * The absolute gate in LUFS.
     */
    private static final double ABSOLUTE_GATE = -70;

    /**
     * The energy of the absolute gate.
     */
    private static final double ABSOLUTE_GATE_ENERGY = energyOf(ABSOLUTE_GATE);

    /**
     * The ratio of the integrated loudness relative gate (-10 LU) in the energy domain.
     */
    private static final double RELATIVE_GATE_FACTOR = Math.pow(10, -10.0 / 10);

    /**
     * The ratio of the loudness range relative gate (-20 LU) in the energy domain.
     */
    private static final double RANGE_GATE_FACTOR = Math.pow(10, -20.0 / 10);

    /**
     * The number of histogram bins.
     */
    private static final int BINS = 1000;

    /**
     * The energy of the center of each histogram bin.
     */
    private static final double[] BIN_CENTERS = binCenters();

    /**
     * The number of 100 ms steps of a momentary window.
     */
    private static final int MOMENTARY_STEPS = 4;

    /**
     * The number of 100 ms steps of a short-term window.
     */
    private static final int SHORT_TERM_STEPS = 30;

    /**
     * The number of parts of a 100 ms step: the maxima and the current values are updated at
     * every part, so every 10 ms.
     */
    private static final int PARTS = 10;

    /**
     * The number of parts of a momentary window.
     */
    private static final int MOMENTARY_PARTS = MOMENTARY_STEPS * PARTS;

    /**
     * The number of parts of a short-term window.
     */
    private static final int SHORT_TERM_PARTS = SHORT_TERM_STEPS * PARTS;

    /**
     * The lowest sampling rate accepted.
     */
    private static final int MIN_RATE = 8000;

    /**
     * The highest sampling rate accepted, the largest libebur128 accepts.
     */
    private static final int MAX_RATE = 2_822_400;

    /**
     * The channel layout.
     */
    private final ChannelLayout layout;

    /**
     * The sampling rate in hertz.
     */
    private final int sampleRate;

    /**
     * The options.
     */
    private final LoudnessMeterOptions options;

    /**
     * The indexes of the channels with a positive weight.
     */
    private final int[] measured;

    /**
     * The weight of each channel.
     */
    private final double[] weights;

    /**
     * The numerator coefficients of the K-weighting filter.
     */
    private final double b0;

    /**
     * The numerator coefficient of the first delayed sample.
     */
    private final double b1;

    /**
     * The numerator coefficient of the second delayed sample.
     */
    private final double b2;

    /**
     * The numerator coefficient of the third delayed sample.
     */
    private final double b3;

    /**
     * The numerator coefficient of the fourth delayed sample.
     */
    private final double b4;

    /**
     * The denominator coefficient of the first delayed state.
     */
    private final double a1;

    /**
     * The denominator coefficient of the second delayed state.
     */
    private final double a2;

    /**
     * The denominator coefficient of the third delayed state.
     */
    private final double a3;

    /**
     * The denominator coefficient of the fourth delayed state.
     */
    private final double a4;

    /**
     * The four filter states of each channel, channel-major.
     */
    private final double[] states;

    /**
     * The sum of the squared filtered samples of each channel in the current step.
     */
    private final double[] stepSums;

    /**
     * The number of samples in a 100 ms step, rounded as libebur128 rounds it.
     */
    private final int stepFrames;

    /**
     * The offset within a step where each part ends.
     */
    private final int[] partEnds;

    /**
     * The weighted energy sums of the last {@value #SHORT_TERM_PARTS} parts, a ring.
     */
    private final double[] parts;

    /**
     * The number of samples of the current step already filtered.
     */
    private int stepFill;

    /**
     * The index of the current part within the step.
     */
    private int part;

    /**
     * The number of complete parts.
     */
    private long partCount;

    /**
     * The number of complete steps.
     */
    private long stepCount;

    /**
     * The block counts of the integrated loudness histogram.
     */
    private final long[] blockCounts;

    /**
     * The block energy sums of the integrated loudness histogram.
     */
    private final double[] blockEnergies;

    /**
     * The block counts of the loudness range histogram.
     */
    private final long[] rangeCounts;

    /**
     * The block energy sums of the loudness range histogram.
     */
    private final double[] rangeEnergies;

    /**
     * The largest momentary energy.
     */
    private double maxMomentary;

    /**
     * The largest short-term energy.
     */
    private double maxShortTerm;

    /**
     * The sample peak of each channel.
     */
    private final double[] samplePeaks;

    /**
     * The oversampled peak of each channel.
     */
    private final double[] truePeaks;

    /**
     * The true-peak meter, or {@code null} when the true peak is not measured.
     */
    private final TruePeakMeter truePeak;

    /**
     * The number of samples per channel measured.
     */
    private long frames;

    /**
     * Constructs a meter with the default options.
     *
     * @param layout     the channel layout of the samples
     * @param sampleRate the sampling rate in hertz, from 8000 to 2822400
     * @throws NullPointerException     if {@code layout} is {@code null}
     * @throws IllegalArgumentException if the sampling rate is out of range
     */
    public LoudnessMeter(ChannelLayout layout, int sampleRate) {
        this(layout, sampleRate, LoudnessMeterOptions.defaults());
    }

    /**
     * Constructs a meter.
     *
     * @param layout the channel layout of the samples
     * @param sampleRate the sampling rate in hertz, from 8000 to 2822400
     * @param options the channel weights, gating and dual mono settings of the measurement
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the sampling rate is out of range, or the options have
     * explicit weights for another number of channels
     */
    public LoudnessMeter(ChannelLayout layout, int sampleRate, LoudnessMeterOptions options) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.options = Objects.requireNonNull(options, "options");
        if (sampleRate < MIN_RATE || sampleRate > MAX_RATE) {
            throw new IllegalArgumentException("sampling rate " + sampleRate + " is outside " + MIN_RATE + ".." + MAX_RATE);
        }
        this.sampleRate = sampleRate;
        var channels = layout.channels();
        this.weights = weights(layout, options);
        var count = 0;
        for (var weight : weights) {
            if (weight > 0) {
                count++;
            }
        }
        this.measured = new int[count];
        count = 0;
        for (var channel = 0; channel < channels; channel++) {
            if (weights[channel] > 0) {
                measured[count++] = channel;
            }
        }
        var k = Math.tan(Math.PI * 1681.974450955533 / sampleRate);
        var q = 0.7071752369554196;
        var vh = Math.pow(10.0, 3.999843853973347 / 20.0);
        var vb = Math.pow(vh, 0.4996667741545416);
        var a0 = 1.0 + k / q + k * k;
        var pb0 = (vh + vb * k / q + k * k) / a0;
        var pb1 = 2.0 * (k * k - vh) / a0;
        var pb2 = (vh - vb * k / q + k * k) / a0;
        var pa1 = 2.0 * (k * k - 1.0) / a0;
        var pa2 = (1.0 - k / q + k * k) / a0;
        k = Math.tan(Math.PI * 38.13547087602444 / sampleRate);
        q = 0.5003270373238773;
        var ra1 = 2.0 * (k * k - 1.0) / (1.0 + k / q + k * k);
        var ra2 = (1.0 - k / q + k * k) / (1.0 + k / q + k * k);
        this.b0 = pb0;
        this.b1 = pb0 * -2.0 + pb1;
        this.b2 = pb0 + pb1 * -2.0 + pb2;
        this.b3 = pb1 + pb2 * -2.0;
        this.b4 = pb2;
        this.a1 = ra1 + pa1;
        this.a2 = ra2 + pa1 * ra1 + pa2;
        this.a3 = pa1 * ra2 + pa2 * ra1;
        this.a4 = pa2 * ra2;
        this.states = new double[channels * 4];
        this.stepSums = new double[channels];
        this.stepFrames = (sampleRate + 5) / 10;
        this.partEnds = new int[PARTS];
        for (var index = 0; index < PARTS; index++) {
            partEnds[index] = (index + 1) * stepFrames / PARTS;
        }
        this.parts = new double[SHORT_TERM_PARTS];
        this.blockCounts = new long[BINS];
        this.blockEnergies = new double[BINS];
        this.rangeCounts = new long[BINS];
        this.rangeEnergies = new double[BINS];
        this.samplePeaks = new double[channels];
        this.truePeaks = new double[channels];
        this.truePeak = options.truePeak() ? new TruePeakMeter(channels, sampleRate) : null;
    }

    /**
     * Returns the weight of every channel.
     *
     * @param layout the speakers the weights follow when the options give none
     * @param options the settings that may give explicit weights
     * @return the weights
     * @throws IllegalArgumentException if explicit weights do not match the channel count
     */
    private static double[] weights(ChannelLayout layout, LoudnessMeterOptions options) {
        var explicit = options.weights();
        var channels = layout.channels();
        if (explicit != null) {
            if (explicit.length != channels) {
                throw new IllegalArgumentException(explicit.length + " channel weights for " + channels + " channels");
            }
            return explicit.clone();
        }
        var speakers = layout.speakers();
        var sides = speakers.contains(Speaker.SIDE_LEFT) || speakers.contains(Speaker.SIDE_RIGHT);
        var result = new double[channels];
        for (var channel = 0; channel < channels; channel++) {
            result[channel] = switch (speakers.get(channel)) {
                case LOW_FREQUENCY, LOW_FREQUENCY_2 -> 0;
                case SIDE_LEFT, SIDE_RIGHT, SURROUND_DIRECT_LEFT, SURROUND_DIRECT_RIGHT -> 1.41;
                case BACK_LEFT, BACK_RIGHT -> sides ? 1.0 : 1.41;
                default -> 1.0;
            };
        }
        if (channels == 1 && options.dualMono()) {
            result[0] = 2.0;
        }
        return result;
    }

    /**
     * Returns the energy of each histogram bin center, as libebur128 computes it.
     *
     * @return the energies
     */
    private static double[] binCenters() {
        var result = new double[BINS];
        for (var bin = 0; bin < BINS; bin++) {
            result[bin] = Math.pow(10.0, (bin / 10.0 - 69.95 + 0.691) / 10.0);
        }
        return result;
    }

    /**
     * Returns the channel layout measured.
     *
     * @return the layout
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * Returns the sampling rate measured.
     *
     * @return the rate in hertz
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the settings this meter measures with}
     */
    public LoudnessMeterOptions options() {
        return options;
    }

    /**
     * Measures the valid samples of a buffer.
     *
     * @param buffer the samples; its rate and channel count must be the meter's
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if the buffer's rate or channel count differs
     */
    public void accept(AudioBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.sampleRate() != sampleRate) {
            throw new IllegalArgumentException("buffer at " + buffer.sampleRate() + " Hz for a meter at " + sampleRate + " Hz");
        }
        accept(buffer.planes(), 0, buffer.frames());
    }

    /**
     * Measures a range of planar samples.
     *
     * @param planes one array per channel
     * @param offset the index of the first sample in each plane
     * @param count  the number of samples per channel
     * @throws NullPointerException      if {@code planes} or a plane is {@code null}
     * @throws IllegalArgumentException  if the plane count differs from the channel count
     * @throws IndexOutOfBoundsException if the range exceeds a plane
     */
    public void accept(float[][] planes, int offset, int count) {
        Objects.requireNonNull(planes, "planes");
        if (planes.length != stepSums.length) {
            throw new IllegalArgumentException(planes.length + " planes for a " + stepSums.length + "-channel meter");
        }
        for (var plane : planes) {
            Objects.checkFromIndexSize(offset, count, Objects.requireNonNull(plane, "plane").length);
        }
        if (count == 0) {
            return;
        }
        for (var channel = 0; channel < planes.length; channel++) {
            var plane = planes[channel];
            var peak = 0f;
            for (var index = offset; index < offset + count; index++) {
                peak = Math.max(peak, Math.abs(plane[index]));
            }
            samplePeaks[channel] = Math.max(samplePeaks[channel], peak);
            if (truePeak != null) {
                truePeaks[channel] = Math.max(truePeaks[channel], truePeak.process(channel, plane, offset, count));
            }
        }
        var position = offset;
        var remaining = count;
        while (remaining > 0) {
            var length = Math.min(remaining, partEnds[part] - stepFill);
            filter(planes, position, length);
            position += length;
            remaining -= length;
            stepFill += length;
            if (stepFill == partEnds[part]) {
                completePart();
            }
        }
        frames += count;
    }

    /**
     * Runs the K-weighting filter of every measured channel over a range and accumulates the
     * squares of its output, two channels per pass so that their recursions overlap.
     *
     * @param planes the samples, one array per channel
     * @param offset the index of the first sample
     * @param count the number of samples
     */
    private void filter(float[][] planes, int offset, int count) {
        var index = 0;
        for (; index + 1 < measured.length; index += 2) {
            filterPair(planes[measured[index]], measured[index], planes[measured[index + 1]], measured[index + 1], offset, count);
        }
        if (index < measured.length) {
            filterOne(planes[measured[index]], measured[index], offset, count);
        }
    }

    /**
     * Filters one channel.
     *
     * @param plane   the channel's samples
     * @param channel the channel index
     * @param offset  the index of the first sample
     * @param count   the number of samples
     */
    private void filterOne(float[] plane, int channel, int offset, int count) {
        var base = channel * 4;
        var v1 = states[base];
        var v2 = states[base + 1];
        var v3 = states[base + 2];
        var v4 = states[base + 3];
        var sum = 0.0;
        for (var index = offset; index < offset + count; index++) {
            var v0 = plane[index] - a1 * v1 - a2 * v2 - a3 * v3 - a4 * v4;
            var y = b0 * v0 + b1 * v1 + b2 * v2 + b3 * v3 + b4 * v4;
            sum += y * y;
            v4 = v3;
            v3 = v2;
            v2 = v1;
            v1 = v0;
        }
        states[base] = v1;
        states[base + 1] = v2;
        states[base + 2] = v3;
        states[base + 3] = v4;
        stepSums[channel] += sum;
    }

    /**
     * Filters two channels in one pass.
     *
     * @param first         the first channel's samples
     * @param firstChannel  the first channel index
     * @param second        the second channel's samples
     * @param secondChannel the second channel index
     * @param offset        the index of the first sample
     * @param count         the number of samples
     */
    private void filterPair(float[] first, int firstChannel, float[] second, int secondChannel, int offset, int count) {
        var base = firstChannel * 4;
        var other = secondChannel * 4;
        var v1 = states[base];
        var v2 = states[base + 1];
        var v3 = states[base + 2];
        var v4 = states[base + 3];
        var w1 = states[other];
        var w2 = states[other + 1];
        var w3 = states[other + 2];
        var w4 = states[other + 3];
        var sum = 0.0;
        var otherSum = 0.0;
        for (var index = offset; index < offset + count; index++) {
            var v0 = first[index] - a1 * v1 - a2 * v2 - a3 * v3 - a4 * v4;
            var w0 = second[index] - a1 * w1 - a2 * w2 - a3 * w3 - a4 * w4;
            var y = b0 * v0 + b1 * v1 + b2 * v2 + b3 * v3 + b4 * v4;
            var z = b0 * w0 + b1 * w1 + b2 * w2 + b3 * w3 + b4 * w4;
            sum += y * y;
            otherSum += z * z;
            v4 = v3;
            v3 = v2;
            v2 = v1;
            v1 = v0;
            w4 = w3;
            w3 = w2;
            w2 = w1;
            w1 = w0;
        }
        states[base] = v1;
        states[base + 1] = v2;
        states[base + 2] = v3;
        states[base + 3] = v4;
        states[other] = w1;
        states[other + 1] = w2;
        states[other + 2] = w3;
        states[other + 3] = w4;
        stepSums[firstChannel] += sum;
        stepSums[secondChannel] += otherSum;
    }

    /**
     * Closes a 10 ms part: records its weighted energy and updates the maxima, and at the end of
     * a 100 ms step the gating histograms.
     */
    private void completePart() {
        var energy = 0.0;
        for (var channel : measured) {
            energy += weights[channel] * stepSums[channel];
            stepSums[channel] = 0;
        }
        parts[(int) (partCount % SHORT_TERM_PARTS)] = energy;
        partCount++;
        var momentary = partCount >= MOMENTARY_PARTS ? windowEnergy(MOMENTARY_PARTS) : 0;
        var shortTerm = partCount >= SHORT_TERM_PARTS ? windowEnergy(SHORT_TERM_PARTS) : 0;
        maxMomentary = Math.max(maxMomentary, momentary);
        maxShortTerm = Math.max(maxShortTerm, shortTerm);
        if (++part < PARTS) {
            return;
        }
        part = 0;
        stepFill = 0;
        stepCount++;
        for (var index = 0; index < states.length; index++) {
            if (Math.abs(states[index]) < Double.MIN_NORMAL) {
                states[index] = 0;
            }
        }
        if (stepCount >= MOMENTARY_STEPS && momentary >= ABSOLUTE_GATE_ENERGY) {
            var bin = bin(momentary);
            blockCounts[bin]++;
            blockEnergies[bin] += momentary;
        }
        if (stepCount >= SHORT_TERM_STEPS && shortTerm >= ABSOLUTE_GATE_ENERGY) {
            var bin = bin(shortTerm);
            rangeCounts[bin]++;
            rangeEnergies[bin] += shortTerm;
        }
    }

    /**
     * Returns the energy of the window made of the last parts; parts before the first count as
     * silence.
     *
     * @param count the number of parts, a multiple of {@value #PARTS}
     * @return the mean weighted energy
     */
    private double windowEnergy(int count) {
        var sum = 0.0;
        for (var back = 1; back <= count; back++) {
            sum += parts[Math.floorMod(partCount - back, SHORT_TERM_PARTS)];
        }
        return sum / ((double) (count / PARTS) * stepFrames);
    }

    /**
     * Returns the histogram bin of an energy at or above the absolute gate.
     *
     * @param energy the weighted sum of the channels' mean squares, at or above the absolute gate
     * @return the bin, the last one for loudness of +30 LUFS and above
     */
    private static int bin(double energy) {
        var index = (int) Math.floor((loudnessOf(energy) - ABSOLUTE_GATE) * 10);
        return Math.clamp(index, 0, BINS - 1);
    }

    /**
     * Converts an energy to loudness.
     *
     * @param energy the weighted sum of the channels' mean squares, at least 0
     * @return the loudness in LUFS, negative infinity for zero
     */
    static double loudnessOf(double energy) {
        return 10 * Math.log10(energy) - 0.691;
    }

    /**
     * Converts loudness to an energy.
     *
     * @param loudness the loudness in LUFS
     * @return the energy
     */
    private static double energyOf(double loudness) {
        return Math.pow(10, (loudness + 0.691) / 10);
    }

    /**
     * Returns the first histogram bin above a relative gate, by libebur128's rule: the bin of the
     * gate, or the next one when the gate is above the bin center.
     *
     * @param gate the gate energy
     * @return the first bin kept
     */
    private static int firstBinAbove(double gate) {
        if (gate < ABSOLUTE_GATE_ENERGY) {
            return 0;
        }
        var bin = bin(gate);
        return gate > BIN_CENTERS[bin] ? bin + 1 : bin;
    }

    /**
     * Returns the momentary loudness: the last 400 ms as of the last complete 10 ms step, with the
     * time before the first sample counting as silence.
     *
     * @return the loudness in LUFS, negative infinity before the first step or for silence
     */
    public double momentary() {
        return partCount == 0 ? Double.NEGATIVE_INFINITY : loudnessOf(windowEnergy(MOMENTARY_PARTS));
    }

    /**
     * Returns the short-term loudness: the last 3 s as of the last complete 10 ms step, with the
     * time before the first sample counting as silence.
     *
     * @return the loudness in LUFS, negative infinity before the first step or for silence
     */
    public double shortTerm() {
        return partCount == 0 ? Double.NEGATIVE_INFINITY : loudnessOf(windowEnergy(SHORT_TERM_PARTS));
    }

    /**
     * Returns the integrated loudness of everything measured so far.
     *
     * @return the loudness in LUFS, negative infinity when no 400 ms block passes the gates
     */
    public double integrated() {
        var count = 0L;
        var sum = 0.0;
        for (var bin = 0; bin < BINS; bin++) {
            count += blockCounts[bin];
            sum += blockEnergies[bin];
        }
        if (count == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        var first = firstBinAbove(sum / count * RELATIVE_GATE_FACTOR);
        count = 0;
        sum = 0;
        for (var bin = first; bin < BINS; bin++) {
            count += blockCounts[bin];
            sum += blockEnergies[bin];
        }
        return count == 0 ? Double.NEGATIVE_INFINITY : loudnessOf(sum / count);
    }

    /**
     * Returns the relative gate of the integrated loudness.
     *
     * @return the gate in LUFS, {@code -70} when no block passes the absolute gate
     */
    public double relativeThreshold() {
        var count = 0L;
        var sum = 0.0;
        for (var bin = 0; bin < BINS; bin++) {
            count += blockCounts[bin];
            sum += blockEnergies[bin];
        }
        return count == 0 ? ABSOLUTE_GATE : loudnessOf(sum / count * RELATIVE_GATE_FACTOR);
    }

    /**
     * Returns the loudness range of everything measured so far.
     *
     * @return the range in LU, {@code 0} when no short-term block passes the gates
     */
    public double loudnessRange() {
        var bounds = rangeBounds();
        return bounds == null ? 0 : bounds[1] - bounds[0];
    }

    /**
     * Returns the 10th and 95th percentiles of the gated short-term loudness.
     *
     * @return the low and high bounds in LUFS, or {@code null} when no block passes the gates
     */
    private double[] rangeBounds() {
        var count = 0L;
        var sum = 0.0;
        for (var bin = 0; bin < BINS; bin++) {
            count += rangeCounts[bin];
            sum += rangeEnergies[bin];
        }
        if (count == 0) {
            return null;
        }
        var first = firstBinAbove(sum / count * RANGE_GATE_FACTOR);
        var kept = 0L;
        for (var bin = first; bin < BINS; bin++) {
            kept += rangeCounts[bin];
        }
        if (kept == 0) {
            return null;
        }
        var low = (long) ((kept - 1) * 0.1 + 0.5);
        var high = (long) ((kept - 1) * 0.95 + 0.5);
        return new double[]{percentile(first, low), percentile(first, high)};
    }

    /**
     * Returns the loudness of the block at a rank of the short-term histogram: the mean energy of
     * the bin that holds it.
     *
     * @param first the first bin kept
     * @param rank  the zero-based rank among the kept blocks
     * @return the loudness in LUFS
     */
    private double percentile(int first, long rank) {
        var seen = 0L;
        for (var bin = first; bin < BINS; bin++) {
            seen += rangeCounts[bin];
            if (seen > rank) {
                return loudnessOf(rangeEnergies[bin] / rangeCounts[bin]);
            }
        }
        return Double.NEGATIVE_INFINITY;
    }

    /**
     * Returns the largest sample magnitude of a channel.
     *
     * @param channel the channel index
     * @return the peak, 1.0 being full scale
     * @throws IndexOutOfBoundsException if {@code channel} is out of range
     */
    public double samplePeak(int channel) {
        return samplePeaks[Objects.checkIndex(channel, samplePeaks.length)];
    }

    /**
     * Returns the largest sample magnitude over every channel.
     *
     * @return the peak, 1.0 being full scale
     */
    public double samplePeak() {
        var peak = 0.0;
        for (var value : samplePeaks) {
            peak = Math.max(peak, value);
        }
        return peak;
    }

    /**
     * Returns the true peak of a channel: the largest magnitude of the oversampled signal, never
     * below the sample peak.
     *
     * @param channel the channel index
     * @return the peak, 1.0 being full scale, or {@code NaN} when the options leave the true peak
     *         out
     * @throws IndexOutOfBoundsException if {@code channel} is out of range
     */
    public double truePeak(int channel) {
        Objects.checkIndex(channel, truePeaks.length);
        return truePeak == null ? Double.NaN : Math.max(truePeaks[channel], samplePeaks[channel]);
    }

    /**
     * Returns the true peak over every channel.
     *
     * @return the peak, 1.0 being full scale, or {@code NaN} when the options leave the true peak
     *         out
     */
    public double truePeak() {
        if (truePeak == null) {
            return Double.NaN;
        }
        var peak = 0.0;
        for (var channel = 0; channel < truePeaks.length; channel++) {
            peak = Math.max(peak, Math.max(truePeaks[channel], samplePeaks[channel]));
        }
        return peak;
    }

    /**
     * Returns the number of samples per channel measured.
     *
     * @return the frame count
     */
    public long frames() {
        return frames;
    }

    /**
     * Returns every measurement of the program so far.
     *
     * @return the measurements
     */
    public LoudnessStats stats() {
        var bounds = rangeBounds();
        return new LoudnessStats(integrated(), bounds == null ? 0 : bounds[1] - bounds[0],
                bounds == null ? Double.NEGATIVE_INFINITY : bounds[0], bounds == null ? Double.NEGATIVE_INFINITY : bounds[1],
                relativeThreshold(), partCount >= MOMENTARY_PARTS ? loudnessOf(maxMomentary) : Double.NEGATIVE_INFINITY,
                partCount >= SHORT_TERM_PARTS ? loudnessOf(maxShortTerm) : Double.NEGATIVE_INFINITY, samplePeak(), truePeak(),
                frames * 1_000_000L / sampleRate);
    }

    /**
     * Forgets everything measured, as if the meter were new.
     */
    public void reset() {
        Arrays.fill(states, 0);
        Arrays.fill(stepSums, 0);
        Arrays.fill(parts, 0);
        Arrays.fill(blockCounts, 0);
        Arrays.fill(blockEnergies, 0);
        Arrays.fill(rangeCounts, 0);
        Arrays.fill(rangeEnergies, 0);
        Arrays.fill(samplePeaks, 0);
        Arrays.fill(truePeaks, 0);
        if (truePeak != null) {
            truePeak.reset();
        }
        stepFill = 0;
        part = 0;
        partCount = 0;
        stepCount = 0;
        maxMomentary = 0;
        maxShortTerm = 0;
        frames = 0;
    }
}
