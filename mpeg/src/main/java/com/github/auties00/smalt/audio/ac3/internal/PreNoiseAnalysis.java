package com.github.auties00.smalt.audio.ac3.internal;

import java.util.Arrays;

/**
 * Chooses the transient pre-noise processing data of the full-bandwidth channels of an E-AC-3
 * frame (ETSI TS 102 366 clauses E.1.3.2.21 to E.1.3.2.23 and E.2.7): for each channel at most one
 * transient location and time scaling length, which make the decoder overwrite the quantization
 * noise the transform spreads ahead of a transient with earlier audio.
 *
 * <p>A decoder applies only locations {@code 4 * transprocloc} from 1024 to 2559, the transients at
 * decoded samples 1280 to 2815 of the frame carrying the data (see {@link TransientPreNoise}), so the
 * data of a frame mostly concerns the frames after it: the analysis chooses the data of a frame once
 * the {@link #lookahead()} frames after it are coded. It keeps the input of every channel and the
 * time-domain quantization error of the frame and of those later frames, both in the time base of
 * the decoded output, which lags the input by 256 samples.
 *
 * <p>A transient is an onset in the high-pass filtered input: a 16-sample segment whose energy
 * exceeds {@link #ONSET_RATIO} times the largest energy of the eight segments before it. For the
 * strongest onset of a channel among the decoded samples a decoder corrects, the decoded output is
 * estimated as the input plus the quantization error, the processing of clause E.2.7.2 is applied to
 * the estimate for a few time scaling lengths, and the length that removes the most error energy
 * ahead of the transient is kept when it removes at least half of it. A correction is attempted only
 * when its synthesis buffer ends within the frame carrying the data or the corrected samples begin
 * after it, so that a decoder that outputs each frame as it decodes it applies all of it, and when its
 * buffer and corrected samples begin after the transient of the correction chosen before, so that
 * each transient is corrected once and corrections never overlap. Instances are not thread-safe.
 *
 * @implNote This implementation estimates the decoder with the cross-fades of
 * {@link TransientPreNoise}, takes the earlier audio the decoder copies from the input itself and
 * treats the coefficients spectral extension synthesizes as error-free; these approximations affect
 * only the choice, never the validity of the data.
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
     * The first decoded sample, from the start of the frame carrying the data, at which a transient
     * may lie.
     */
    static final int FIRST_TRANSIENT = TransientPreNoise.FIRST_LOCATION + TransientPreNoise.DELAY;

    /**
     * The end of the decoded samples at which a transient may lie, exclusive.
     */
    static final int TRANSIENT_LIMIT = TransientPreNoise.LOCATION_LIMIT + TransientPreNoise.DELAY;

    /**
     * The number of input samples kept ahead of the frame being chosen for: the decoded output lags
     * the input by 256 samples, and the onset filter and the eight segments before the first
     * candidate reach 192 samples further back.
     */
    private static final int HISTORY = 512;

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
     * The samples per channel of a frame.
     */
    private final int frameSize;

    /**
     * The number of frames coded after a frame before its data is chosen.
     */
    private final int lookahead;

    /**
     * The decoded samples the analysis covers: the frame being chosen for and its lookahead.
     */
    private final int span;

    /**
     * The input of each channel: {@link #HISTORY} samples before the frame being chosen for, then
     * that frame and the frames coded after it.
     */
    private final float[][] input;

    /**
     * The quantization error of each channel over the decoded samples of the frame being chosen for
     * and the frames coded after it.
     */
    private final float[][] error;

    /**
     * The second half of the windowed error of the last block of each channel, which overlaps the
     * next block.
     */
    private final float[][] tail;

    /**
     * The high-pass filtered input of one channel over the onset candidates and the eight segments
     * before them, scratch.
     */
    private final float[] filtered;

    /**
     * The windowed error of one block, scratch.
     */
    private final float[] windowed = new float[512];

    /**
     * The number of frames pushed, at most {@code lookahead + 1}: the frames the buffers hold.
     */
    private int held;

    /**
     * The first decoded sample of the oldest frame the buffers hold, counted from the first.
     */
    private long origin;

    /**
     * The decoded sample, counted from the first, after the transient of the last correction
     * chosen for each channel.
     */
    private final long[] lastTransient;

    /**
     * The transient location {@code 4 * transprocloc} of each channel chosen last, or {@code -1}
     * without processing.
     */
    final int[] location;

    /**
     * The time scaling length {@code transproclen} of each channel chosen last.
     */
    final int[] length;

    /**
     * Creates the analysis of a substream.
     *
     * @param channels the number of full-bandwidth channels
     * @param frameSize the samples per channel of a frame, at least 768
     */
    PreNoiseAnalysis(int channels, int frameSize) {
        this.frameSize = frameSize;
        this.lookahead = lookahead(frameSize);
        this.span = (lookahead + 1) * frameSize;
        this.input = new float[channels][HISTORY + span];
        this.error = new float[channels][span];
        this.tail = new float[channels][256];
        this.filtered = new float[TRANSIENT_LIMIT];
        this.location = new int[channels];
        this.length = new int[channels];
        this.lastTransient = new long[channels];
        Arrays.fill(location, -1);
    }

    /**
     * Returns the number of frames that follow a frame whose decoded samples may hold a transient
     * of its data.
     *
     * @param frameSize the samples per channel of a frame
     * @return the frames after the frame carrying the data up to the last possible transient
     */
    static int lookahead(int frameSize) {
        return (TRANSIENT_LIMIT - 1) / frameSize;
    }

    /**
     * {@return the number of frames coded after a frame before its data is chosen}
     */
    int lookahead() {
        return lookahead;
    }

    /**
     * Appends the input of a channel's frame, which is about to be coded, dropping the oldest frame
     * when the buffers are full; the channels are pushed in order.
     *
     * @param ch the channel
     * @param plane the samples
     * @param offset the index of the frame's first sample
     */
    void push(int ch, float[] plane, int offset) {
        if (ch == 0 && held == lookahead + 1) {
            drop();
        }
        System.arraycopy(plane, offset, input[ch], HISTORY + held * frameSize, frameSize);
        Arrays.fill(error[ch], held * frameSize, (held + 1) * frameSize, 0);
        if (ch == input.length - 1) {
            held++;
        }
    }

    /**
     * Forgets the oldest frame the buffers hold, once its data is chosen.
     */
    void drop() {
        for (var ch = 0; ch < input.length; ch++) {
            System.arraycopy(input[ch], frameSize, input[ch], 0, HISTORY + span - frameSize);
            System.arraycopy(error[ch], frameSize, error[ch], 0, span - frameSize);
        }
        held--;
        origin += frameSize;
    }

    /**
     * {@return the number of frames the buffers hold, the frame being chosen for first}
     */
    int held() {
        return held;
    }

    /**
     * Adds the quantization error of one block of a channel of the newest frame, in the transform
     * domain, to the channel's time-domain error.
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
        var at = (held - 1) * frameSize + 256 * b;
        for (var n = 0; n < 256; n++) {
            target[at + n] = windowed[n] + overlap[n];
            overlap[n] = windowed[256 + n];
        }
    }

    /**
     * Chooses the processing data of a channel for the oldest frame the buffers hold, from the
     * input and the error of that frame and of the frames coded after it: at the strongest onset,
     * rounded down to the four-sample resolution of the location, the time scaling length of
     * {@link #LENGTHS} that removes the most error energy among those that remove at least half of
     * it, or none.
     *
     * @param ch the channel
     * @param filter the biquad coefficients of the high-pass filter: b0, b1, b2, a1, a2
     */
    void choose(int ch, float[] filter) {
        location[ch] = -1;
        length[ch] = 0;
        var available = held * frameSize;
        // a buffer starts at least 1024 samples before its transient
        var from = (int) Math.max(FIRST_TRANSIENT, Math.min(TRANSIENT_LIMIT, lastTransient[ch] - origin + 1024));
        var onset = onset(ch, filter, from, Math.min(TRANSIENT_LIMIT, available));
        if (onset < 0) {
            return;
        }
        var position = onset & ~3;
        var blockStart = (position / 256 - 1) * 256;
        var preNoise = position - blockStart;
        var errors = error[ch];
        var bestReduction = 0.0;
        for (var candidate : LENGTHS) {
            var start = position - preNoise - candidate - TC1;
            // a decoder applies all of the correction when its buffer is complete at the end of the
            // frame carrying the data, or when the correction starts after that frame
            if (blockStart > frameSize && start < frameSize) {
                continue;
            }
            // the buffer and the corrected samples stay clear of the correction chosen before
            if (origin + position - 2 * TC1 - 2L * preNoise < lastTransient[ch]) {
                continue;
            }
            var plain = 0.0;
            for (var k = start; k < position; k++) {
                plain += (double) errors[k] * errors[k];
            }
            var processed = processedError(ch, position, preNoise, candidate);
            if (processed < plain * REQUIRED_GAIN && plain - processed > bestReduction) {
                bestReduction = plain - processed;
                location[ch] = position - TransientPreNoise.DELAY;
                length[ch] = candidate;
            }
        }
        if (location[ch] >= 0) {
            lastTransient[ch] = origin + position;
        }
    }

    /**
     * Finds the strongest onset of a channel among the decoded samples from {@code from} to
     * {@code end}.
     *
     * @param ch the channel
     * @param filter the high-pass filter coefficients
     * @param from the first candidate, at least {@link #FIRST_TRANSIENT}, in decoded samples from the
     * start of the oldest frame
     * @param end the end of the candidates, exclusive
     * @return the onset in decoded samples from the start of the oldest frame, or {@code -1}
     */
    private int onset(int ch, float[] filter, int from, int end) {
        var buffer = input[ch];
        // filtered[0] is the input sample decoded at FIRST_TRANSIENT - 128, eight segments before
        // the first candidate
        var base = FIRST_TRANSIENT - 128;
        var first = HISTORY + base - TransientPreNoise.DELAY;
        var count = end - base;
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
        for (var s = Math.max(8, (from - base + 15) / 16); s < count / 16; s++) {
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
                best = base + 16 * s;
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
     * @param position the transient in decoded samples from the start of the oldest frame
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
            var original = buffer[HISTORY + index - TransientPreNoise.DELAY];
            var decoded = original + errors[index];
            var source = bufferStart + k;
            var copied = buffer[HISTORY + source - TransientPreNoise.DELAY] + errors[source];
            float value;
            if (k < TC1) {
                value = decoded * TransientPreNoise.FADE_OUT[k] + copied * TransientPreNoise.FADE_IN[k];
            } else if (k < tailStart) {
                value = copied;
            } else {
                value = decoded * TransientPreNoise.TAIL_DECODED[k - tailStart]
                        + copied * TransientPreNoise.TAIL_BUFFER[k - tailStart];
            }
            var difference = value - original;
            energy += (double) difference * difference;
        }
        return energy;
    }
}
