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
 * of its buffer is decoded, in the order the corrections started; several corrections of a channel
 * may be pending at once. The processor keeps the last {@link #HISTORY} output samples of each
 * channel, from which a buffer completed in a later frame draws.
 *
 * <p>A correction starts at most {@code transproclen + 256}, so at most 511, samples before the end
 * of its buffer. Without holdback, a correction reaches only the samples of the frame that completes
 * its buffer and of later frames, so the samples before that frame stay as decoded. With holdback,
 * the output of each frame is held back {@link #HOLDBACK} samples by the caller, and a correction
 * also reaches the {@link #HOLDBACK} samples before the frame that completes its buffer: their
 * corrected values are reported through {@link #lateStart(int)} and {@link #late(int)}, which every
 * sample a correction covers then reaches. Instances are not thread-safe.
 *
 * @implNote This implementation follows Dolby's decoder (the Dolby Digital Plus decoder of Windows
 * Media Foundation, measured as a black box with forged streams), where the specification leaves
 * room: the 256-sample offset of the location, the range of locations applied, and the cross-fade
 * windows, which the specification leaves to the implementation. The first cross-fade weighs the
 * decoded samples with {@code w(n) = (1 + cos(pi * (n + 1) / 257)) / 2} and the buffer with
 * {@code 1 - w(n)}; the last weighs the buffer with {@code w(2k + 1)} and the decoded samples with
 * {@code 1 - w(2k)}, the first windows taken at every other sample, which is not of constant
 * amplitude. Dolby's decoder holds a whole frame back; the shorter holdback reaches the same samples.
 */
final class TransientPreNoise {
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
     * The number of samples before a frame that a correction completed in it may reach, which an
     * output held back by as many samples lets every correction reach: more than the 511 samples a
     * correction starts at most before the end of its buffer.
     */
    static final int HOLDBACK = 512;

    /**
     * The most samples of a frame: six blocks.
     */
    private static final int FRAME = 1536;

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
     * Whether corrections reach the {@link #HOLDBACK} samples before the frame being processed.
     */
    private final boolean holdback;

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
     * The sample, counted from the channel's first, up to which each slot's correction is applied.
     */
    private final long[][] appliedUntil;

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
     * The samples before the frame and the samples of the frame of the channel being processed, with
     * holdback: the samples the corrections may change.
     */
    private final float[] work;

    /**
     * The corrected values of the {@link #HOLDBACK} samples before the frame processed last, by
     * channel.
     */
    private final float[][] late;

    /**
     * The index in {@link #late} of the first sample a correction changed in the frame processed
     * last, by channel, or {@link #HOLDBACK} when none changed.
     */
    private final int[] lateStart;

    /**
     * The lowest sample, counted from the channel's first, that the corrections of the frame being
     * processed wrote.
     */
    private long lowest;

    /**
     * Creates the processor of a substream.
     *
     * @param channels the number of full-bandwidth channels, at most 5
     * @param holdback whether corrections also reach the {@link #HOLDBACK} samples before each frame
     */
    TransientPreNoise(int channels, boolean holdback) {
        this.holdback = holdback;
        this.history = new float[channels][HISTORY];
        this.position = new long[channels];
        this.active = new boolean[channels][SLOTS];
        this.captured = new boolean[channels][SLOTS];
        this.transients = new long[channels][SLOTS];
        this.appliedUntil = new long[channels][SLOTS];
        this.preNoise = new int[channels][SLOTS];
        this.length = new int[channels][SLOTS];
        this.head = new int[channels];
        this.held = new int[channels];
        this.buffers = new float[channels][SLOTS][LONGEST];
        this.work = holdback ? new float[HOLDBACK + FRAME] : null;
        this.late = holdback ? new float[channels][HOLDBACK] : null;
        this.lateStart = new int[channels];
        Arrays.fill(lateStart, HOLDBACK);
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
     * {@return whether corrections reach the samples before each frame}
     */
    boolean holdback() {
        return holdback;
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
            lateStart[ch] = HOLDBACK;
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
     * Returns the first of the {@link #HOLDBACK} samples before the frame of a channel processed last
     * that its corrections changed.
     *
     * @param ch the channel
     * @return the index in {@link #late(int)} of the first changed sample, or {@link #HOLDBACK} when
     * none changed, always without holdback
     */
    int lateStart(int ch) {
        return lateStart[ch];
    }

    /**
     * Returns the corrected values of the {@link #HOLDBACK} samples before the frame of a channel
     * processed last, the sample at index {@code i} lying {@code HOLDBACK - i} samples before the
     * frame; only the values from {@link #lateStart(int)} on are set.
     *
     * @param ch the channel
     * @return the values, owned by the processor and valid until the next call to
     * {@link #process(int, float[], int, int, int, int)} for the channel
     */
    float[] late(int ch) {
        return late[ch];
    }

    /**
     * Processes the decoded samples of one frame of a channel in place and records them as past
     * output.
     *
     * <p>The correction of this frame, when {@code location} is applied, joins the pending ones;
     * then every pending correction whose buffer is complete is applied to the frame's samples, and
     * with holdback to the samples before the frame, in the order the corrections started.
     *
     * @param ch the channel
     * @param pcm the decoded samples, corrected in place
     * @param offset the index of the frame's first sample
     * @param samples the samples of the frame, at most 1536
     * @param location the transient location {@code 4 * transprocloc} relative to the first input
     * sample of the frame, or {@code -1} without transient pre-noise data
     * @param processing the processing length {@code transproclen}, from 0 to 255
     */
    void process(int ch, float[] pcm, int offset, int samples, int location, int processing) {
        var start = position[ch];
        lateStart[ch] = HOLDBACK;
        if (applied(location)) {
            start(ch, start + location + DELAY, processing);
        }
        var end = start + samples;
        if (held[ch] > 0) {
            correct(ch, pcm, offset, samples, start, end);
        }
        remember(ch, pcm, offset, samples);
        position[ch] = end;
    }

    /**
     * Applies the pending corrections of a channel whose buffers are complete to a frame and, with
     * holdback, to the samples before it.
     *
     * @param ch the channel
     * @param pcm the decoded samples, corrected in place
     * @param offset the index of the frame's first sample
     * @param samples the samples of the frame
     * @param start the frame's first sample, counted from the channel's first
     * @param end the end of the frame, counted from the channel's first sample
     */
    private void correct(int ch, float[] pcm, int offset, int samples, long start, long end) {
        var window = holdback ? (int) Math.min(HOLDBACK, start) : 0;
        var past = history[ch];
        float[] data;
        int base;
        if (window > 0) {
            System.arraycopy(past, HISTORY - window, work, 0, window);
            System.arraycopy(pcm, offset, work, window, samples);
            data = work;
            base = 0;
        } else {
            data = pcm;
            base = offset;
        }
        var windowStart = start - window;
        lowest = Long.MAX_VALUE;
        var slots = active[ch];
        for (var i = 0; i < held[ch]; i++) {
            var slot = (head[ch] + i) % SLOTS;
            if (!slots[slot]) {
                continue;
            }
            var t = transients[ch][slot];
            if (!captured[ch][slot]) {
                var pn = preNoise[ch][slot];
                var bufferStart = t - 2 * TC1 - 2L * pn;
                var bufferLength = 2 * TC1 + pn;
                if (bufferStart + bufferLength > end) {
                    continue;
                }
                capture(ch, slot, data, base, windowStart, start, bufferStart, bufferLength);
            }
            apply(ch, slot, data, base, windowStart, end);
            if (t <= end) {
                slots[slot] = false;
            }
        }
        while (held[ch] > 0 && !slots[head[ch]]) {
            head[ch] = (head[ch] + 1) % SLOTS;
            held[ch]--;
        }
        if (window > 0) {
            if (lowest < start) {
                var first = (int) (lowest - windowStart);
                var index = HOLDBACK - window + first;
                System.arraycopy(work, first, late[ch], index, window - first);
                System.arraycopy(work, first, past, HISTORY - window + first, window - first);
                lateStart[ch] = index;
            }
            System.arraycopy(work, window, pcm, offset, samples);
        }
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
        appliedUntil[ch][slot] = Long.MIN_VALUE;
        preNoise[ch][slot] = (int) (t - blockStart);
        length[ch][slot] = processing;
    }

    /**
     * Copies the synthesis buffer of a slot from the samples the corrections may change and the past
     * output before them.
     *
     * @param ch the channel
     * @param slot the slot of the correction
     * @param data the samples the corrections may change
     * @param base the index in {@code data} of the sample at {@code windowStart}
     * @param windowStart the first sample the corrections may change, counted from the channel's
     * first
     * @param frameStart the frame's first sample, counted from the channel's first
     * @param bufferStart the buffer's first sample, counted from the channel's first
     * @param bufferLength the buffer length
     */
    private void capture(int ch, int slot, float[] data, int base, long windowStart, long frameStart,
                         long bufferStart, int bufferLength) {
        var past = history[ch];
        var buffer = buffers[ch][slot];
        for (var n = 0; n < bufferLength; n++) {
            var sample = bufferStart + n;
            if (sample >= windowStart) {
                buffer[n] = data[base + (int) (sample - windowStart)];
            } else {
                var index = HISTORY - (frameStart - sample);
                buffer[n] = index >= 0 ? past[(int) index] : 0;
            }
        }
        captured[ch][slot] = true;
    }

    /**
     * Overwrites the samples of a slot's correction that the frame may change and that it has not
     * yet overwritten with its buffer, cross-faded at both ends.
     *
     * @param ch the channel
     * @param slot the slot of the correction
     * @param data the samples the corrections may change, corrected in place
     * @param base the index in {@code data} of the sample at {@code windowStart}
     * @param windowStart the first sample the frame may change, counted from the channel's first
     * @param frameEnd the end of the frame, counted from the channel's first sample
     */
    private void apply(int ch, int slot, float[] data, int base, long windowStart, long frameEnd) {
        var t = transients[ch][slot];
        var total = preNoise[ch][slot] + length[ch][slot] + TC1;
        var start = t - total;
        var tail = total - TC2;
        var buffer = buffers[ch][slot];
        var first = Math.max(windowStart, appliedUntil[ch][slot]);
        var from = (int) Math.max(0, first - start);
        var to = (int) Math.min(total, frameEnd - start);
        appliedUntil[ch][slot] = frameEnd;
        if (from >= to) {
            return;
        }
        lowest = Math.min(lowest, start + from);
        for (var k = from; k < to; k++) {
            var index = base + (int) (start + k - windowStart);
            float value;
            if (k < TC1) {
                value = data[index] * FADE_OUT[k] + buffer[k] * FADE_IN[k];
            } else if (k < tail) {
                value = buffer[k];
            } else {
                value = data[index] * TAIL_DECODED[k - tail] + buffer[k] * TAIL_BUFFER[k - tail];
            }
            data[index] = value;
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
