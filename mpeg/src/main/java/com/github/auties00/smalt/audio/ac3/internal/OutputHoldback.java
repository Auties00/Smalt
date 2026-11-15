package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Holds the decoded output of an AC-3 or E-AC-3 program back by {@link #SAMPLES} samples, so that
 * the transient pre-noise processing of a frame can still correct the samples before it.
 *
 * <p>Each frame is kept with its own layout and sampling rate and with the planes it is output
 * from: the output channels, or the coded channels and the coefficients that downmix them, mixed
 * only when the samples are released. A frame records which of its planes each coded channel of
 * each substream feeds, so that the corrected value of a held sample replaces the value that
 * channel decoded. Released samples keep the layout of the frame they belong to: one buffer is
 * emitted per run of samples of one layout and rate. Instances are not thread-safe.
 */
public final class OutputHoldback {
    /**
     * The number of samples held back.
     */
    public static final int SAMPLES = TransientPreNoise.HOLDBACK;

    /**
     * The number of substream slots of a frame's map: the independent substream, then the
     * dependent substreams by identifier plus one.
     */
    public static final int SUBSTREAMS = 9;

    /**
     * The most coded channels of a substream, LFE included.
     */
    public static final int CHANNELS = 6;

    /**
     * The most frames held at once: the held samples and the frame just pushed, in frames of one
     * block.
     */
    private static final int SLOTS = 4;

    /**
     * The most samples of a frame.
     */
    private static final int FRAME = 1536;

    /**
     * The DSP kernels that mix downmixed frames.
     */
    private final Ac3Kernel kernel;

    /**
     * The held frames, a ring in output order from {@link #head}.
     */
    private final Frame[] frames = new Frame[SLOTS];

    /**
     * The slot of the oldest held frame.
     */
    private int head;

    /**
     * The number of frames held.
     */
    private int count;

    /**
     * The number of samples held, released or not, of the frames from the head on, minus the
     * released samples of the head frame.
     */
    private int held;

    /**
     * The buffers samples are released in, by layout.
     */
    private final Map<ChannelLayout, AudioBuffer> buffers = new HashMap<>();

    /**
     * Constructs an empty holdback.
     *
     * @param kernel the DSP kernels
     */
    public OutputHoldback(Ac3Kernel kernel) {
        this.kernel = kernel;
        for (var i = 0; i < SLOTS; i++) {
            frames[i] = new Frame();
        }
    }

    /**
     * A held frame.
     */
    private static final class Frame {
        /**
         * The layout of the frame's output.
         */
        ChannelLayout layout;

        /**
         * The sampling rate.
         */
        int rate;

        /**
         * The number of samples per channel.
         */
        int length;

        /**
         * The number of samples per channel already released.
         */
        int released;

        /**
         * The planes the frame is output from, at least {@link #sources} of them.
         */
        float[][] planes = new float[0][];

        /**
         * The number of planes in use.
         */
        int sources;

        /**
         * Whether the planes are coded channels downmixed by {@link #mix}, rather than the output
         * channels.
         */
        boolean mixed;

        /**
         * The number of output channels of a downmixed frame.
         */
        int outputs;

        /**
         * The coefficient of each coded channel in each output channel of a downmixed frame.
         */
        final float[][] mix = new float[2][CHANNELS];

        /**
         * The plane each coded channel of each substream slot feeds, or {@code -1}.
         */
        final int[][] map = new int[SUBSTREAMS][CHANNELS];

        /**
         * Makes room for planes of a frame.
         *
         * @param planes the number of planes
         */
        void prepare(int planes) {
            if (this.planes.length < planes) {
                var grown = Arrays.copyOf(this.planes, planes);
                for (var i = this.planes.length; i < planes; i++) {
                    grown[i] = new float[FRAME];
                }
                this.planes = grown;
            }
            sources = planes;
            released = 0;
            for (var row : map) {
                Arrays.fill(row, -1);
            }
        }
    }

    /**
     * Returns an empty map of a frame's coded channels to planes, for {@link #push(AudioBuffer, int[][])}.
     *
     * @return a new map with every entry {@code -1}
     */
    public static int[][] newMap() {
        var map = new int[SUBSTREAMS][CHANNELS];
        for (var row : map) {
            Arrays.fill(row, -1);
        }
        return map;
    }

    /**
     * Holds a frame output in its own channels.
     *
     * @param frame the frame's output, whose frame count is set; copied
     * @param map the output channel each coded channel of each substream slot feeds, or {@code -1}
     */
    public void push(AudioBuffer frame, int[][] map) {
        var slot = next();
        var channels = frame.layout().channels();
        slot.prepare(channels);
        slot.layout = frame.layout();
        slot.rate = frame.sampleRate();
        slot.length = frame.frames();
        slot.mixed = false;
        for (var ch = 0; ch < channels; ch++) {
            System.arraycopy(frame.plane(ch), 0, slot.planes[ch], 0, slot.length);
        }
        for (var s = 0; s < SUBSTREAMS; s++) {
            System.arraycopy(map[s], 0, slot.map[s], 0, CHANNELS);
        }
        held += slot.length;
    }

    /**
     * Holds a frame of the independent substream that is output downmixed.
     *
     * @param coded the samples of each coded channel; copied
     * @param channels the number of coded channels
     * @param mix the coefficient of each coded channel in each output channel; copied
     * @param outputs the number of output channels, 1 or 2
     * @param layout the output layout
     * @param rate the sampling rate
     * @param samples the samples per channel
     */
    public void pushMixed(float[][] coded, int channels, float[][] mix, int outputs, ChannelLayout layout, int rate,
                          int samples) {
        var slot = next();
        slot.prepare(channels);
        slot.layout = layout;
        slot.rate = rate;
        slot.length = samples;
        slot.mixed = true;
        slot.outputs = outputs;
        for (var ch = 0; ch < channels; ch++) {
            System.arraycopy(coded[ch], 0, slot.planes[ch], 0, samples);
            slot.map[0][ch] = ch;
        }
        for (var out = 0; out < outputs; out++) {
            System.arraycopy(mix[out], 0, slot.mix[out], 0, channels);
        }
        held += samples;
    }

    /**
     * Returns the slot the next frame is pushed into.
     *
     * @return the slot
     */
    private Frame next() {
        if (count == SLOTS) {
            throw new IllegalStateException("the holdback holds " + SLOTS + " frames");
        }
        var slot = frames[(head + count) % SLOTS];
        count++;
        return slot;
    }

    /**
     * Replaces held samples of a coded channel with their corrected values.
     *
     * <p>The values are those of the {@link #SAMPLES} samples before the frame about to be pushed:
     * the value at index {@code i} is that of the sample {@code SAMPLES - i} samples before it.
     * Samples already released, and samples of frames in which the channel fed no plane, are left
     * as they are.
     *
     * @param substream the substream slot: 0 for the independent substream, the identifier plus one
     * for a dependent one
     * @param ch the coded channel
     * @param values the corrected values
     * @param from the index of the first corrected value
     */
    public void correct(int substream, int ch, float[] values, int from) {
        var index = SAMPLES - held;
        for (var i = 0; i < count; i++) {
            var frame = frames[(head + i) % SLOTS];
            var available = frame.length - frame.released;
            var first = Math.max(from, index);
            var last = index + available;
            var plane = frame.map[substream][ch];
            if (plane >= 0 && first < last) {
                System.arraycopy(values, first, frame.planes[plane], frame.released + first - index, last - first);
            }
            index = last;
        }
    }

    /**
     * Releases every sample but the last {@link #SAMPLES}.
     *
     * @param output receives the released samples, one buffer per run of one layout and rate, each
     * valid only during the call
     */
    public void release(Consumer<AudioBuffer> output) {
        emit(Math.max(0, held - SAMPLES), output);
    }

    /**
     * Releases every held sample and empties the holdback.
     *
     * @param output receives the released samples, one buffer per run of one layout and rate, each
     * valid only during the call
     */
    public void flush(Consumer<AudioBuffer> output) {
        emit(held, output);
        clear();
    }

    /**
     * Drops every held sample.
     */
    public void clear() {
        head = 0;
        count = 0;
        held = 0;
    }

    /**
     * Returns the number of samples per channel held.
     *
     * @return the count, at most {@link #SAMPLES} between pushes
     */
    public int held() {
        return held;
    }

    /**
     * Releases the oldest held samples.
     *
     * @param samples the number of samples per channel to release
     * @param output receives the released samples
     */
    private void emit(int samples, Consumer<AudioBuffer> output) {
        AudioBuffer run = null;
        var filled = 0;
        while (samples > 0) {
            var frame = frames[head];
            if (run != null && (run.layout() != frame.layout && !run.layout().equals(frame.layout)
                    || run.sampleRate() != frame.rate)) {
                run.setFrames(filled);
                output.accept(run);
                run = null;
            }
            if (run == null) {
                run = buffer(frame.layout, frame.rate, samples);
                filled = 0;
            }
            var n = Math.min(samples, frame.length - frame.released);
            write(frame, run, filled, n);
            frame.released += n;
            filled += n;
            samples -= n;
            held -= n;
            if (frame.released == frame.length) {
                head = (head + 1) % SLOTS;
                count--;
            }
        }
        if (run != null) {
            run.setFrames(filled);
            output.accept(run);
        }
    }

    /**
     * Writes released samples of a frame into a buffer.
     *
     * @param frame the held frame the samples are released from
     * @param run the buffer
     * @param at the index of the first written sample in each plane of the buffer
     * @param n the number of samples per channel
     */
    private void write(Frame frame, AudioBuffer run, int at, int n) {
        var from = frame.released;
        if (!frame.mixed) {
            for (var ch = 0; ch < frame.sources; ch++) {
                System.arraycopy(frame.planes[ch], from, run.plane(ch), at, n);
            }
            return;
        }
        for (var out = 0; out < frame.outputs; out++) {
            var plane = run.plane(out);
            kernel.scale(frame.planes[0], from, frame.mix[out][0], plane, at, n);
            for (var ch = 1; ch < frame.sources; ch++) {
                if (frame.mix[out][ch] != 0) {
                    kernel.accumulate(frame.planes[ch], from, frame.mix[out][ch], plane, at, n);
                }
            }
        }
    }

    /**
     * Returns the release buffer of a layout, creating it on first use.
     *
     * @param layout the channel layout
     * @param rate the sampling rate
     * @param samples the samples per channel needed
     * @return the buffer
     */
    private AudioBuffer buffer(ChannelLayout layout, int rate, int samples) {
        var buffer = buffers.get(layout);
        if (buffer == null || buffer.sampleRate() != rate || buffer.capacity() < samples) {
            buffer = new AudioBuffer(layout, rate, Math.max(samples, FRAME));
            buffers.put(layout, buffer);
        }
        return buffer;
    }
}
