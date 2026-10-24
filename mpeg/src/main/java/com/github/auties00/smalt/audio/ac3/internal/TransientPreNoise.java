package com.github.auties00.smalt.audio.ac3.internal;

import java.util.Arrays;

/**
 * Applies the transient pre-noise processing of E-AC-3 (ATSC A/52 Annex E section 3.7, ETSI TS
 * 102 366 clause E.2.7) to the decoded PCM of the full-bandwidth channels of one substream.
 *
 * <p>For a channel whose frame carries transient pre-noise data, the decoded samples before the
 * transient are overwritten with a time scaling synthesis buffer copied from earlier audio, so that
 * the quantization noise a long transform spreads ahead of the transient disappears. The transient
 * lies {@code T = 4 * transprocloc + 256} decoded samples after the first decoded sample of the
 * frame: the location counts from the frame's first input sample, which the decoder outputs 256
 * samples later. With the block before the one holding the transient starting at
 * {@code blockStart = (T / 256 - 1) * 256} and {@code pnlen = T - blockStart}, the buffer is the
 * {@code 2 * TC1 + pnlen} samples that start {@code 2 * TC1 + 2 * pnlen} samples before the
 * transient; it replaces the {@code pnlen + transproclen + TC1} samples before the transient,
 * entering with a cross-fade of {@code TC1 = 256} samples and leaving with one of {@code TC2 = 128}
 * samples.
 *
 * <p>Data whose {@code 4 * transprocloc} lies outside {@code [1024, 2560)} is ignored. Inside that
 * range, the buffer and the corrected samples never precede the frame carrying the data, and a
 * correction ends at most 2816 samples after its start. A correction is applied once every sample
 * of its buffer is decoded, to the samples of that frame and of later frames; several corrections
 * of a channel may be pending at once. The processor keeps the last {@link #HISTORY} output samples
 * of each channel, from which a buffer completed in a later frame draws. Instances are not
 * thread-safe.
 *
 * @implNote This implementation follows Dolby's decoder (the Dolby Digital Plus decoder of Windows
 * Media Foundation, measured as a black box with forged streams), where the specification leaves
 * room: the 256-sample offset of the location, the range of locations applied, and the cross-fade
 * windows, which the specification leaves to the implementation. The first cross-fade weighs the
 * decoded samples with {@code w(n) = (1 + cos(pi * (n + 1) / 257)) / 2} and the buffer with
 * {@code 1 - w(n)}; the last weighs the buffer with {@code w(2k + 1)} and the decoded samples with
 * {@code 1 - w(2k)}, the first windows taken at every other sample, which is not of constant
 * amplitude.
 */
final class TransientPreNoise {
    // TODO: Dolby's decoder holds its output back until a correction's buffer is complete, so it
    //  also corrects the samples of the frame carrying the data when the buffer reaches into the
    //  next frame (a transient less than pnlen + 256 samples before that frame's end); this
    //  processor outputs every frame as it is decoded, so those samples stay uncorrected and only
    //  the samples of later frames are.

    /**
     * The length of the time scaling synthesis cross-fade into the buffer, {@code TC1}.
     */
    static final int TC1 = 256;

    /**
     * The length of the cross-fade out of the buffer before the transient, {@code TC2}.
     */
    static final int TC2 = 128;

    /**
     * The offset of the decoded output from the input the transient location counts in.
     */
    static final int DELAY = 256;

    /**
     * The smallest transient location {@code 4 * transprocloc} applied.
     */
    static final int FIRST_LOCATION = 1024;

    /**
     * The end of the transient locations {@code 4 * transprocloc} applied, exclusive.
     */
    static final int LOCATION_LIMIT = 2560;

    /**
     * The number of past output samples kept per channel: more than the longest buffer, which a
     * correction completed in a later frame copies partly from earlier frames.
     */
    static final int HISTORY = 2048;

    /**
     * The longest synthesis buffer: {@code 2 * TC1} plus the longest pre-noise of 511 samples.
     */
    private static final int LONGEST = 2 * TC1 + 511;

    /**
     * The most corrections of a channel pending at once: one per frame, over the at most 2816
     * samples a correction spans, in frames of one block.
     */
    private static final int SLOTS = 12;

    /**
     * The weight {@code w(n)} of the decoded samples over the first cross-fade, from one to zero.
     */
    static final float[] FADE_OUT = new float[TC1];

    /**
     * The weight {@code 1 - w(n)} of the buffer over the first cross-fade, from zero to one.
     */
    static final float[] FADE_IN = new float[TC1];

    /**
     * The weight {@code w(2k + 1)} of the buffer over the last cross-fade.
     */
    static final float[] TAIL_BUFFER = new float[TC2];

    /**
     * The weight {@code 1 - w(2k)} of the decoded samples over the last cross-fade.
     */
    static final float[] TAIL_DECODED = new float[TC2];

    static {
        for (var n = 0; n < TC1; n++) {
            var weight = 0.5 * (1 + StrictMath.cos(Math.PI * (n + 1) / (TC1 + 1)));
            FADE_OUT[n] = (float) weight;
            FADE_IN[n] = (float) (1 - weight);
        }
        for (var k = 0; k < TC2; k++) {
            TAIL_BUFFER[k] = FADE_OUT[2 * k + 1];
            TAIL_DECODED[k] = FADE_IN[2 * k];
        }
    }

    /**
     * The past output samples of each channel, the most recent last.
     */
    private final float[][] history;

    /**
     * The number of samples of each channel processed so far.
     */
    private final long[] position;

    /**
     * Whether each correction slot of each channel is pending.
     */
    private final boolean[][] active;

    /**
     * Whether the synthesis buffer of each correction slot has been taken.
     */
    private final boolean[][] captured;

    /**
     * The sample, counted from the channel's first, at which the transient of each slot lies.
     */
    private final long[][] transients;

    /**
     * The pre-noise length {@code pnlen} of each slot.
     */
    private final int[][] preNoise;

    /**
     * The processing length {@code transproclen} of each slot.
     */
    private final int[][] length;

    /**
     * The slot of the oldest correction of each channel still held: the slots form a ring in the
     * order the corrections started.
     */
    private final int[] head;

    /**
     * The number of slots of each channel held from {@link #head}, pending or finished.
     */
    private final int[] held;

    /**
     * The synthesis buffer of each slot.
     */
    private final float[][][] buffers;

    /**
     * Creates the processor of a substream.
     *
     * @param channels the number of full-bandwidth channels, at most 5
     */
    TransientPreNoise(int channels) {
        this.history = new float[channels][HISTORY];
        this.position = new long[channels];
        this.active = new boolean[channels][SLOTS];
        this.captured = new boolean[channels][SLOTS];
        this.transients = new long[channels][SLOTS];
        this.preNoise = new int[channels][SLOTS];
        this.length = new int[channels][SLOTS];
        this.head = new int[channels];
        this.held = new int[channels];
        this.buffers = new float[channels][SLOTS][LONGEST];
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
        for (var ch = 0; ch < history.length; ch++) {
            Arrays.fill(history[ch], 0);
            Arrays.fill(active[ch], false);
            position[ch] = 0;
            held[ch] = 0;
        }
    }

    /**
     * Returns whether a transient location is one the processor applies.
     *
     * @param location the transient location {@code 4 * transprocloc}
     * @return whether data with it corrects the output
     */
    static boolean applied(int location) {
        return location >= FIRST_LOCATION && location < LOCATION_LIMIT;
    }

    /**
     * Processes the decoded samples of one frame of a channel in place and records them as past
     * output.
     *
     * <p>The correction of this frame, when {@code location} is applied, joins the pending ones;
     * then every pending correction whose buffer is complete is applied to the frame's samples, in
     * the order the corrections started.
     *
     * @param ch the channel
     * @param pcm the decoded samples, corrected in place
     * @param offset the index of the frame's first sample
     * @param samples the samples of the frame
     * @param location the transient location {@code 4 * transprocloc} relative to the first input
     * sample of the frame, or {@code -1} without transient pre-noise data
     * @param processing the processing length {@code transproclen}, from 0 to 255
     */
    void process(int ch, float[] pcm, int offset, int samples, int location, int processing) {
        var start = position[ch];
        if (applied(location)) {
            start(ch, start + location + DELAY, processing);
        }
        var end = start + samples;
        var slots = active[ch];
        for (var i = 0; i < held[ch]; i++) {
            var slot = (head[ch] + i) % SLOTS;
            if (!slots[slot]) {
                continue;
            }
            var t = transients[ch][slot];
            var pn = preNoise[ch][slot];
            if (!captured[ch][slot]) {
                var bufferStart = t - 2 * TC1 - 2L * pn;
                var bufferLength = 2 * TC1 + pn;
                if (bufferStart + bufferLength > end) {
                    continue;
                }
                capture(ch, slot, pcm, offset, start, bufferStart, bufferLength);
            }
            apply(ch, slot, pcm, offset, start, end);
            if (t <= end) {
                slots[slot] = false;
            }
        }
        while (held[ch] > 0 && !slots[head[ch]]) {
            head[ch] = (head[ch] + 1) % SLOTS;
            held[ch]--;
        }
        remember(ch, pcm, offset, samples);
        position[ch] = end;
    }

    /**
     * Starts a correction in the next slot of a channel's ring, dropping the oldest correction when
     * every slot is held.
     *
     * @param ch the channel
     * @param t the transient, counted from the channel's first sample
     * @param processing the processing length
     */
    private void start(int ch, long t, int processing) {
        if (held[ch] == SLOTS) {
            head[ch] = (head[ch] + 1) % SLOTS;
            held[ch]--;
        }
        var slot = (head[ch] + held[ch]) % SLOTS;
        held[ch]++;
        var blockStart = (t / 256 - 1) * 256;
        active[ch][slot] = true;
        captured[ch][slot] = false;
        transients[ch][slot] = t;
        preNoise[ch][slot] = (int) (t - blockStart);
        length[ch][slot] = processing;
    }

    /**
     * Copies the synthesis buffer of a slot from the frame and the past output.
     *
     * @param ch the channel
     * @param slot the slot of the correction
     * @param pcm the decoded samples of the frame
     * @param offset the index of the frame's first sample
     * @param frameStart the frame's first sample, counted from the channel's first
     * @param bufferStart the buffer's first sample, counted from the channel's first
     * @param bufferLength the buffer length
     */
    private void capture(int ch, int slot, float[] pcm, int offset, long frameStart, long bufferStart,
                         int bufferLength) {
        var past = history[ch];
        var buffer = buffers[ch][slot];
        for (var n = 0; n < bufferLength; n++) {
            var index = (int) (bufferStart + n - frameStart);
            buffer[n] = index >= 0 ? pcm[offset + index] : index >= -HISTORY ? past[HISTORY + index] : 0;
        }
        captured[ch][slot] = true;
    }

    /**
     * Overwrites the samples of a slot's correction that fall in the frame with its buffer,
     * cross-faded at both ends.
     *
     * @param ch the channel
     * @param slot the slot of the correction
     * @param pcm the decoded samples, corrected in place
     * @param offset the index of the frame's first sample
     * @param frameStart the frame's first sample, counted from the channel's first
     * @param frameEnd the end of the frame, counted from the channel's first sample
     */
    private void apply(int ch, int slot, float[] pcm, int offset, long frameStart, long frameEnd) {
        var t = transients[ch][slot];
        var total = preNoise[ch][slot] + length[ch][slot] + TC1;
        var start = t - total;
        var tail = total - TC2;
        var buffer = buffers[ch][slot];
        var from = (int) Math.max(0, frameStart - start);
        var to = (int) Math.min(total, frameEnd - start);
        for (var k = from; k < to; k++) {
            var index = offset + (int) (start + k - frameStart);
            float value;
            if (k < TC1) {
                value = pcm[index] * FADE_OUT[k] + buffer[k] * FADE_IN[k];
            } else if (k < tail) {
                value = buffer[k];
            } else {
                value = pcm[index] * TAIL_DECODED[k - tail] + buffer[k] * TAIL_BUFFER[k - tail];
            }
            pcm[index] = value;
        }
    }

    /**
     * Records the samples of a frame of a channel as past output.
     *
     * @param ch the channel
     * @param pcm the samples
     * @param offset the index of the frame's first sample
     * @param samples the samples of the frame
     */
    private void remember(int ch, float[] pcm, int offset, int samples) {
        var past = history[ch];
        if (samples >= HISTORY) {
            System.arraycopy(pcm, offset + samples - HISTORY, past, 0, HISTORY);
            return;
        }
        System.arraycopy(past, samples, past, 0, HISTORY - samples);
        System.arraycopy(pcm, offset, past, HISTORY - samples, samples);
    }
}
