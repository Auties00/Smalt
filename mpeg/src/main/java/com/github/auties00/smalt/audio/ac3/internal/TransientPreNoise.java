package com.github.auties00.smalt.audio.ac3.internal;

import java.util.Arrays;

/**
 * Applies the transient pre-noise processing of E-AC-3 (ATSC A/52 Annex E section 3.7) to the
 * decoded PCM of the full-bandwidth channels of one substream.
 *
 * <p>For a channel whose frame carries transient pre-noise data, the decoded samples before the
 * transient are overwritten with a time scaling synthesis buffer copied from earlier audio, so that
 * the quantization noise a long transform spreads ahead of the transient disappears. With the
 * transient at {@code transloc} samples from the start of the frame, the block before the one
 * holding it starting at {@code blockStart}, and {@code pnlen = transloc - blockStart}, the buffer
 * is the {@code 2 * TC1 + pnlen} samples that start {@code 2 * TC1 + 2 * pnlen} samples before the
 * transient; it replaces the {@code pnlen + transproclen + TC1} samples before the transient,
 * entering with a cross-fade of {@code TC1 = 256} samples and leaving with one of
 * {@code TC2 = 128} samples.
 *
 * <p>The processor keeps the last {@link #HISTORY} output samples of each channel, from which the
 * buffer may draw. A correction whose transient lies in the next frame is completed when that frame
 * is processed, with the buffer taken when its samples became available. Instances are not
 * thread-safe.
 *
 * @implNote This implementation cross-fades with the complementary raised-cosine (Hann) windows
 * the specification names as giving good results, since its pseudo-code leaves the windows open. It
 * corrects only the samples of the frame being decoded and of later frames, because earlier frames
 * were already output.
 */
final class TransientPreNoise {
    // TODO: the output differs from Dolby's decoder (the Windows Media Foundation decoder used as a
    //  black box) in every frame with pre-noise data and the frame after it, about 11 dB of SNR
    //  below the extension band against the 53 dB of every other frame, in the Audition encodes of
    //  ffmpeg ticket 10958; the cross-fade windows, the buffer placement and the handling of a zero
    //  transproclen need checking against that decoder.

    /**
     * The length of the time scaling synthesis cross-fade into the buffer, {@code TC1}.
     */
    static final int TC1 = 256;

    /**
     * The length of the cross-fade out of the buffer before the transient, {@code TC2}.
     */
    static final int TC2 = 128;

    /**
     * The number of past output samples kept per channel: enough for a buffer reaching
     * {@code 2 * TC1 + 2 * 511} samples before a transient at the start of a frame.
     */
    static final int HISTORY = 2048;

    /**
     * The longest synthesis buffer: {@code 2 * TC1} plus the longest pre-noise of 511 samples.
     */
    private static final int LONGEST = 2 * TC1 + 511;

    /**
     * The weight of the decoded samples over the first cross-fade, from one to zero.
     */
    private static final float[] FADE_OUT_1 = fadeOut(TC1);

    /**
     * The weight of the decoded samples over the last cross-fade, from one to zero; the buffer
     * fades out with it, the decoded samples fade in with its complement.
     */
    private static final float[] FADE_OUT_2 = fadeOut(TC2);

    /**
     * The past output samples of each channel, the most recent last.
     */
    private final float[][] history;

    /**
     * Whether each channel has a correction pending from the previous frame.
     */
    private final boolean[] pending;

    /**
     * Whether the pending correction of each channel still needs its synthesis buffer.
     */
    private final boolean[] pendingDeferred;

    /**
     * The transient location of the pending correction of each channel, relative to the start of
     * the next frame.
     */
    private final int[] pendingLocation;

    /**
     * The processing length {@code transproclen} of the pending correction of each channel.
     */
    private final int[] pendingLength;

    /**
     * The synthesis buffer of the pending correction of each channel.
     */
    private final float[][] pendingBuffer;

    /**
     * The synthesis buffer of the correction being applied.
     */
    private final float[] buffer = new float[LONGEST];

    /**
     * Creates the processor of a substream.
     *
     * @param channels the number of full-bandwidth channels, at most 5
     */
    TransientPreNoise(int channels) {
        this.history = new float[channels][HISTORY];
        this.pending = new boolean[channels];
        this.pendingDeferred = new boolean[channels];
        this.pendingLocation = new int[channels];
        this.pendingLength = new int[channels];
        this.pendingBuffer = new float[channels][LONGEST];
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
     * Returns the number of channels the processor holds state for.
     *
     * @return the channel count
     */
    int channels() {
        return history.length;
    }

    /**
     * Forgets the past output and every pending correction, as after a seek.
     */
    void reset() {
        for (var line : history) {
            Arrays.fill(line, 0);
        }
        Arrays.fill(pending, false);
    }

    /**
     * Processes the decoded samples of one channel in place and records them as past output.
     *
     * <p>The correction pending from the previous frame is applied first; then the correction of
     * this frame, when {@code location} is not negative.
     *
     * @param ch the channel
     * @param pcm the decoded samples, corrected in place
     * @param offset the index of the frame's first sample
     * @param samples the samples of the frame
     * @param location the transient location {@code 4 * transprocloc} relative to the first sample
     * of the frame, or {@code -1} without transient pre-noise data
     * @param length the processing length {@code transproclen}, from 0 to 255
     */
    void process(int ch, float[] pcm, int offset, int samples, int location, int length) {
        if (pending[ch]) {
            pending[ch] = false;
            if (pendingDeferred[ch]) {
                correct(ch, pcm, offset, samples, pendingLocation[ch], pendingLength[ch]);
            } else {
                System.arraycopy(pendingBuffer[ch], 0, buffer, 0, LONGEST);
                apply(ch, pcm, offset, samples, pendingLocation[ch], pendingLength[ch]);
            }
        }
        if (location >= 0) {
            correct(ch, pcm, offset, samples, location, length);
        }
        remember(ch, pcm, offset, samples);
    }

    /**
     * Records the samples of a frame of a channel as past output without processing them.
     *
     * @param ch the channel
     * @param pcm the samples
     * @param offset the index of the frame's first sample
     * @param samples the samples of the frame
     */
    void remember(int ch, float[] pcm, int offset, int samples) {
        var past = history[ch];
        if (samples >= HISTORY) {
            System.arraycopy(pcm, offset + samples - HISTORY, past, 0, HISTORY);
            return;
        }
        System.arraycopy(past, samples, past, 0, HISTORY - samples);
        System.arraycopy(pcm, offset, past, HISTORY - samples, samples);
    }

    /**
     * Starts a correction: takes its synthesis buffer when every sample of it is decoded and
     * applies it, or defers it to the next frame.
     *
     * @param ch the channel
     * @param pcm the decoded samples
     * @param offset the index of the frame's first sample
     * @param samples the samples of the frame
     * @param location the transient location relative to the first sample of the frame
     * @param length the processing length
     */
    private void correct(int ch, float[] pcm, int offset, int samples, int location, int length) {
        var blockStart = (location / 256 - 1) * 256;
        var preNoise = location - blockStart;
        var bufferLength = 2 * TC1 + preNoise;
        var bufferStart = location - 2 * TC1 - 2 * preNoise;
        if (bufferStart + bufferLength > samples) {
            pending[ch] = true;
            pendingDeferred[ch] = true;
            pendingLocation[ch] = location - samples;
            pendingLength[ch] = length;
            return;
        }
        var past = history[ch];
        for (var n = 0; n < bufferLength; n++) {
            var index = bufferStart + n;
            buffer[n] = index >= 0 ? pcm[offset + index] : index >= -HISTORY ? past[HISTORY + index] : 0;
        }
        apply(ch, pcm, offset, samples, location, length);
    }

    /**
     * Overwrites the samples of a correction that fall in the frame with the synthesis buffer,
     * cross-faded at both ends, and keeps the correction pending when it continues into the next
     * frame.
     *
     * @param ch the channel
     * @param pcm the decoded samples, corrected in place
     * @param offset the index of the frame's first sample
     * @param samples the samples of the frame
     * @param location the transient location relative to the first sample of the frame
     * @param length the processing length
     */
    private void apply(int ch, float[] pcm, int offset, int samples, int location, int length) {
        var blockStart = (location / 256 - 1) * 256;
        var preNoise = location - blockStart;
        var total = preNoise + length + TC1;
        var start = location - total;
        var tail = total - TC2;
        var from = Math.max(0, -start);
        var to = Math.min(total, samples - start);
        for (var k = from; k < to; k++) {
            var index = offset + start + k;
            float value;
            if (k < TC1) {
                var weight = FADE_OUT_1[k];
                value = pcm[index] * weight + buffer[k] * (1 - weight);
            } else if (k < tail) {
                value = buffer[k];
            } else {
                var weight = FADE_OUT_2[k - tail];
                value = pcm[index] * (1 - weight) + buffer[k] * weight;
            }
            pcm[index] = value;
        }
        if (to < total) {
            pending[ch] = true;
            pendingDeferred[ch] = false;
            pendingLocation[ch] = location - samples;
            pendingLength[ch] = length;
            System.arraycopy(buffer, 0, pendingBuffer[ch], 0, LONGEST);
        }
    }
}
