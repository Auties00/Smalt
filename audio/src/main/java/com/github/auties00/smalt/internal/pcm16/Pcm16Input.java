package com.github.auties00.smalt.internal.pcm16;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;

import java.util.Objects;

/**
 * Quantizes the input of a speech or ADPCM encoder to 16-bit samples and gathers it into frames of a fixed number
 * of samples per channel.
 *
 * <p>A float or double sample is clipped to {@code [-1, 1]}, multiplied by 32768, rounded to the nearest integer
 * with ties toward positive infinity and clamped to {@code [-32768, 32767]}; NaN becomes zero. An integer sample
 * narrower than 16 bits is shifted left to 16 bits and a wider one is rounded to 16 bits, to nearest with ties
 * toward positive infinity, and clamped. Instances are not thread-safe.
 */
public final class Pcm16Input {
    /**
     * Receives every completed frame.
     */
    @FunctionalInterface
    public interface FrameSink {
        /**
         * Codes one frame.
         *
         * @param samples one plane per channel, valid only during the call
         * @param count the number of samples per channel, the frame size except for a final partial frame
         */
        void frame(int[][] samples, int count);
    }

    /**
     * The layout every input block must have.
     */
    private final ChannelLayout layout;

    /**
     * The sampling rate every input block must have.
     */
    private final int sampleRate;

    /**
     * The samples per channel of a frame.
     */
    private final int frameSize;

    /**
     * The frame being gathered, one plane per channel.
     */
    private final int[][] frame;

    /**
     * The number of samples per channel already in {@link #frame}.
     */
    private int pending;

    /**
     * Constructs the input stage of an encoder.
     *
     * @param layout the layout of the input
     * @param sampleRate the sampling rate of the input, in hertz
     * @param frameSize the samples per channel of a frame, positive
     */
    public Pcm16Input(ChannelLayout layout, int sampleRate, int frameSize) {
        this.layout = layout;
        this.sampleRate = sampleRate;
        this.frameSize = frameSize;
        this.frame = new int[layout.channels()][frameSize];
    }

    /**
     * {@return the number of samples per channel gathered for the next frame}
     */
    public int pending() {
        return pending;
    }

    /**
     * Checks that a block has the encoder's format.
     *
     * @param blockLayout the layout of the block
     * @param blockRate the sampling rate of the block
     * @throws IllegalArgumentException if either differs from the encoder's
     */
    private void check(ChannelLayout blockLayout, int blockRate) {
        if (blockRate != sampleRate || !blockLayout.equals(layout)) {
            throw new IllegalArgumentException("input " + blockRate + " Hz " + blockLayout
                    + " differs from the encoder's " + sampleRate + " Hz " + layout);
        }
    }

    /**
     * Quantizes a float block and codes every frame it completes.
     *
     * @param input the block
     * @param sink codes the completed frames
     * @throws IllegalArgumentException if the block's format differs from the encoder's
     */
    public void add(AudioBuffer input, FrameSink sink) {
        Objects.requireNonNull(input, "input");
        check(input.layout(), input.sampleRate());
        var planes = input.planes();
        var total = input.frames();
        for (var consumed = 0; consumed < total; ) {
            var count = Math.min(total - consumed, frameSize - pending);
            for (var c = 0; c < frame.length; c++) {
                var from = planes[c];
                var to = frame[c];
                for (var i = 0; i < count; i++) {
                    to[pending + i] = quantize(from[consumed + i]);
                }
            }
            consumed += count;
            advance(count, sink);
        }
    }

    /**
     * Quantizes a double block and codes every frame it completes.
     *
     * @param input the block
     * @param sink codes the completed frames
     * @throws IllegalArgumentException if the block's format differs from the encoder's
     */
    public void add(DoubleAudioBuffer input, FrameSink sink) {
        Objects.requireNonNull(input, "input");
        check(input.layout(), input.sampleRate());
        var planes = input.planes();
        var total = input.frames();
        for (var consumed = 0; consumed < total; ) {
            var count = Math.min(total - consumed, frameSize - pending);
            for (var c = 0; c < frame.length; c++) {
                var from = planes[c];
                var to = frame[c];
                for (var i = 0; i < count; i++) {
                    to[pending + i] = quantize(from[consumed + i]);
                }
            }
            consumed += count;
            advance(count, sink);
        }
    }

    /**
     * Converts an integer block to 16 bits and codes every frame it completes.
     *
     * @param input the block
     * @param sink codes the completed frames
     * @throws IllegalArgumentException if the block's format differs from the encoder's
     */
    public void add(IntegerAudioBuffer input, FrameSink sink) {
        Objects.requireNonNull(input, "input");
        check(input.layout(), input.sampleRate());
        var planes = input.planes();
        var total = input.frames();
        var bits = input.bitsPerSample();
        for (var consumed = 0; consumed < total; ) {
            var count = Math.min(total - consumed, frameSize - pending);
            for (var c = 0; c < frame.length; c++) {
                var from = planes[c];
                var to = frame[c];
                for (var i = 0; i < count; i++) {
                    to[pending + i] = narrow(from[consumed + i], bits);
                }
            }
            consumed += count;
            advance(count, sink);
        }
    }

    /**
     * Codes the gathered samples as a final, partial frame, if there are any.
     *
     * @param sink codes the frame
     */
    public void flush(FrameSink sink) {
        if (pending > 0) {
            var count = pending;
            pending = 0;
            sink.frame(frame, count);
        }
    }

    /**
     * Accounts for samples added to the frame and codes it once it is complete.
     *
     * @param count the number of samples per channel added
     * @param sink codes the frame
     */
    private void advance(int count, FrameSink sink) {
        pending += count;
        if (pending == frameSize) {
            pending = 0;
            sink.frame(frame, frameSize);
        }
    }

    /**
     * Quantizes a float sample to 16 bits.
     *
     * @param value the sample
     * @return the 16-bit sample
     */
    public static int quantize(double value) {
        if (!(value > -1)) {
            return value != value ? 0 : -32768;
        }
        if (value >= 1) {
            return 32767;
        }
        return (int) Math.min(32767, Math.floor(value * 32768 + 0.5));
    }

    /**
     * Converts an integer sample to 16 bits.
     *
     * @param value the sample
     * @param bits the sample size, from 1 to 32
     * @return the 16-bit sample
     */
    public static int narrow(int value, int bits) {
        if (bits <= 16) {
            return value << (16 - bits);
        }
        var shift = bits - 16;
        var rounded = ((long) value + (1L << (shift - 1))) >> shift;
        return (int) Math.max(-32768, Math.min(32767, rounded));
    }
}
