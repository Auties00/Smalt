package com.github.auties00.smalt.audio.ac3.internal;

import java.util.Arrays;

/**
 * Chooses the transient pre-noise processing data of the full-bandwidth channels of an E-AC-3
 * frame (ETSI TS 102 366 clauses E.1.3.2.21 to E.1.3.2.23 and E.2.7): for each channel at most one
 * transient location and time scaling length, which make the decoder overwrite the quantization
 * noise the transform spreads ahead of a transient with earlier audio.
 *
 * <p>The analysis keeps the input of every channel and the time-domain quantization error of the
 * frames coded so far, both in the time base of the decoded output, which lags the input by 256
 * samples. A transient is an onset in the high-pass filtered input: a 16-sample segment whose energy
 * exceeds {@link #ONSET_RATIO} times the largest energy of the eight segments before it. For the
 * strongest onset of a channel in the frame, the decoded output is estimated as the input plus the
 * frame's quantization error, the processing of clause E.2.7.2 is applied to the estimate for a few
 * time scaling lengths, and the length that removes the most error energy ahead of the transient is
 * kept when it removes at least half of it. Corrections that would start
 * before the frame's first decoded sample or reach past its last are not attempted: their samples
 * belong to frames already output or not coded yet. Instances are not thread-safe.
 *
 * @implNote This implementation estimates the decoder with the raised-cosine cross-fades of
 * {@link TransientPreNoise}, which the specification leaves to the implementation, takes the earlier
 * audio the decoder copies from the input itself and treats the coefficients spectral extension
 * synthesizes as error-free; these approximations affect only the choice, never the validity of the
 * data.
 */
final class PreNoiseAnalysis {
    /**
     * The length of the cross-fade into the time scaling synthesis buffer, {@code TC1}.
     */
    private static final int TC1 = TransientPreNoise.TC1;

    /**
     * The length of the cross-fade out of the buffer before the transient, {@code TC2}.
     */
    private static final int TC2 = TransientPreNoise.TC2;

    /**
     * The number of past input samples kept per channel: enough for a buffer that starts
     * {@code 2 * TC1 + 2 * 511} samples before a transient.
     */
    private static final int HISTORY = 2048;

    /**
     * The energy ratio between a segment and the largest of the eight before it that marks an
     * onset: 32, 15 dB.
     */
    private static final double ONSET_RATIO = 32;

    /**
     * The smallest mean power of an onset segment, about -66 dBFS.
     */
    private static final double ONSET_FLOOR = 2.5e-7;

    /**
     * The time scaling lengths {@code transproclen} the analysis tries.
     */
    private static final int[] LENGTHS = {0, 64, 128, 192, 255};

    /**
     * The fraction of the error energy ahead of the transient the processing must leave at most.
     */
    private static final double REQUIRED_GAIN = 0.5;

    /**
     * The weight of the decoded samples over the first cross-fade, from one to zero.
     */
    private static final float[] FADE_OUT_1 = fadeOut(TC1);

    /**
     * The weight of the decoded samples over the last cross-fade, from one to zero.
     */
    private static final float[] FADE_OUT_2 = fadeOut(TC2);

    /**
     * The samples per channel of a frame.
     */
    private final int frameSize;

    /**
     * The input of each channel: {@link #HISTORY} past samples, then the frame being coded.
     */
    private final float[][] input;

    /**
     * The quantization error of each channel over the decoded samples of the frame being coded.
     */
    private final float[][] error;

    /**
     * The second half of the windowed error of the last block of each channel, which overlaps the
     * next block.
     */
    private final float[][] tail;

    /**
     * The overlap of every channel saved by {@link #saveTails()}.
     */
    private final float[][] savedTail;

    /**
     * The high-pass filtered input of one channel over the onset candidates of the frame and the
     * eight segments before them, scratch.
     */
    private final float[] filtered;

    /**
     * The windowed error of one block, scratch.
     */
    private final float[] windowed = new float[512];

    /**
     * The transient location {@code 4 * transprocloc} of each channel, or {@code -1} without
     * processing.
     */
    final int[] location;

    /**
     * The time scaling length {@code transproclen} of each channel.
     */
    final int[] length;

    /**
     * Creates the analysis of a substream.
     *
     * @param channels the number of full-bandwidth channels
     * @param frameSize the samples per channel of a frame
     */
    PreNoiseAnalysis(int channels, int frameSize) {
        this.frameSize = frameSize;
        this.input = new float[channels][HISTORY + frameSize];
        this.error = new float[channels][frameSize];
        this.tail = new float[channels][256];
        this.savedTail = new float[channels][256];
        this.filtered = new float[frameSize];
        this.location = new int[channels];
        this.length = new int[channels];
        Arrays.fill(location, -1);
    }

    /**
     * Builds a raised-cosine fade from one to zero.
     *
     * @param length the number of samples
     * @return the weights
     */
    private static float[] fadeOut(int length) {
        var weights = new float[length];
        for (var n = 0; n < length; n++) {
            weights[n] = (float) (0.5 * (1 + StrictMath.cos(Math.PI * (n + 0.5) / length)));
        }
        return weights;
    }

    /**
     * Appends the input of a channel's frame to its history.
     *
     * @param ch the channel
     * @param plane the samples
     * @param offset the index of the frame's first sample
     */
    void push(int ch, float[] plane, int offset) {
        var buffer = input[ch];
        System.arraycopy(buffer, frameSize, buffer, 0, HISTORY);
        System.arraycopy(plane, offset, buffer, HISTORY, frameSize);
    }

    /**
     * Saves the overlap of every channel before the error of a frame is measured.
     */
    void saveTails() {
        for (var ch = 0; ch < tail.length; ch++) {
            System.arraycopy(tail[ch], 0, savedTail[ch], 0, 256);
        }
    }

    /**
     * Restores the overlap {@link #saveTails()} saved, before the error of the frame is measured
     * again.
     */
    void restoreTails() {
        for (var ch = 0; ch < tail.length; ch++) {
            System.arraycopy(savedTail[ch], 0, tail[ch], 0, 256);
        }
    }

    /**
     * Adds the quantization error of one block of a channel, in the transform domain, to the
     * channel's time-domain error.
     *
     * @param ch the channel
     * @param b the block
     * @param difference the original minus the reconstructed coefficients of the block
     * @param shortBlocks whether the block uses two short transforms
     * @param transform the transforms
     */
    void addBlock(int ch, int b, float[] difference, boolean shortBlocks, Transform transform) {
        transform.inverse(difference, shortBlocks, 2, windowed);
        var target = error[ch];
        var overlap = tail[ch];
        var at = 256 * b;
        for (var n = 0; n < 256; n++) {
            target[at + n] = windowed[n] + overlap[n];
            overlap[n] = windowed[256 + n];
        }
    }

    /**
     * Chooses the processing data of a channel from its input and error over the frame: at the
     * strongest onset, rounded down to the four-sample resolution of the location, the time scaling
     * length of {@link #LENGTHS} that removes the most error energy among those that remove at least
     * half of it, or none.
     *
     * @param ch the channel
     * @param filter the biquad coefficients of the high-pass filter: b0, b1, b2, a1, a2
     */
    void choose(int ch, float[] filter) {
        location[ch] = -1;
        length[ch] = 0;
        var onset = onset(ch, filter);
        if (onset < 0) {
            return;
        }
        var position = onset & ~3;
        var preNoise = position - (position / 256 - 1) * 256;
        var errors = error[ch];
        var bestReduction = 0.0;
        for (var candidate : LENGTHS) {
            var start = position - preNoise - candidate - TC1;
            if (start < 0) {
                break;
            }
            var plain = 0.0;
            for (var k = start; k < position; k++) {
                plain += (double) errors[k] * errors[k];
            }
            var processed = processedError(ch, position, preNoise, candidate);
            if (processed < plain * REQUIRED_GAIN && plain - processed > bestReduction) {
                bestReduction = plain - processed;
                location[ch] = position;
                length[ch] = candidate;
            }
        }
    }

    /**
     * Finds the strongest onset of a channel among the decoded samples of the frame from which a
     * correction stays within the frame.
     *
     * @param ch the channel
     * @param filter the high-pass filter coefficients
     * @return the onset in decoded samples from the frame's first decoded sample, or {@code -1}
     */
    private int onset(int ch, float[] filter) {
        var buffer = input[ch];
        // filtered[0] is the input sample decoded at 384, eight segments before the first candidate
        var first = HISTORY + 128;
        var count = frameSize - 384;
        if (count <= 128) {
            return -1;
        }
        var x1 = 0.0f;
        var x2 = 0.0f;
        var y1 = 0.0f;
        var y2 = 0.0f;
        for (var n = first - 64; n < first + count; n++) {
            var x = buffer[n];
            var y = filter[0] * x + filter[1] * x1 + filter[2] * x2 - filter[3] * y1 - filter[4] * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            if (n >= first) {
                filtered[n - first] = y;
            }
        }
        var best = -1;
        var bestRatio = ONSET_RATIO;
        for (var s = 8; s < count / 16; s++) {
            var energy = segmentEnergy(s);
            if (energy < ONSET_FLOOR * 16) {
                continue;
            }
            var previous = 0.0;
            for (var t = s - 8; t < s; t++) {
                previous = Math.max(previous, segmentEnergy(t));
            }
            var ratio = energy / Math.max(previous, 1e-30);
            if (ratio > bestRatio) {
                bestRatio = ratio;
                best = 384 + 16 * s;
            }
        }
        return best;
    }

    /**
     * Returns the energy of a 16-sample segment of {@link #filtered}.
     *
     * @param segment the segment index
     * @return the energy
     */
    private double segmentEnergy(int segment) {
        var energy = 0.0;
        for (var n = 16 * segment; n < 16 * segment + 16; n++) {
            energy += (double) filtered[n] * filtered[n];
        }
        return energy;
    }

    /**
     * Returns the error energy ahead of a transient after the decoder's processing of the
     * estimated decoded output, as {@link TransientPreNoise} applies it.
     *
     * @param ch the channel
     * @param position the transient location in decoded samples
     * @param preNoise the pre-noise length {@code pnlen}
     * @param scaling the time scaling length
     * @return the error energy over the corrected samples
     */
    private double processedError(int ch, int position, int preNoise, int scaling) {
        var buffer = input[ch];
        var errors = error[ch];
        var total = preNoise + scaling + TC1;
        var start = position - total;
        var bufferStart = position - 2 * TC1 - 2 * preNoise;
        var tailStart = total - TC2;
        var energy = 0.0;
        for (var k = 0; k < total; k++) {
            var index = start + k;
            var original = buffer[HISTORY + index - 256];
            var decoded = original + errors[index];
            var source = bufferStart + k;
            var copied = buffer[HISTORY + source - 256] + (source >= 0 ? errors[source] : 0);
            float value;
            if (k < TC1) {
                var weight = FADE_OUT_1[k];
                value = decoded * weight + copied * (1 - weight);
            } else if (k < tailStart) {
                value = copied;
            } else {
                var weight = FADE_OUT_2[k - tailStart];
                value = decoded * (1 - weight) + copied * weight;
            }
            var difference = value - original;
            energy += (double) difference * difference;
        }
        return energy;
    }
}
