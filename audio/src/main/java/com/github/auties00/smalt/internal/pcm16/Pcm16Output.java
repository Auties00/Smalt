package com.github.auties00.smalt.internal.pcm16;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.internal.codec.WaveformConcealer;

import java.util.function.Consumer;

/**
 * Hands the 16-bit samples a speech or ADPCM decoder produced to its caller as floats, integers or doubles, and
 * conceals lost spans of the float output.
 *
 * <p>The decoder writes a block into {@link #planes(int)} and passes it on with {@link #floats}, {@link #integers}
 * or {@link #doubles}; the buffers handed out are reused by the next call. Floats and doubles are the integers
 * scaled by {@code 2^-15}. Instances are not thread-safe.
 */
public final class Pcm16Output {
    /**
     * The scale of a 16-bit sample.
     */
    private static final float SCALE = 0x1p-15f;

    /**
     * The layout of the output.
     */
    private final ChannelLayout layout;

    /**
     * The sampling rate of the output.
     */
    private final int sampleRate;

    /**
     * The concealment of lost spans, fed with every float block.
     */
    private final WaveformConcealer concealer;

    /**
     * The integer planes the decoder writes to; doubles as the integer output's planes.
     */
    private IntegerAudioBuffer integers;

    /**
     * The float buffer handed to consumers, or {@code null} before the first float block.
     */
    private AudioBuffer floats;

    /**
     * The double buffer handed to consumers, or {@code null} before the first double block.
     */
    private DoubleAudioBuffer doubles;

    /**
     * Constructs the output of a decoder.
     *
     * @param layout the channel layout of the output
     * @param sampleRate the sampling rate of the output, in hertz
     */
    public Pcm16Output(ChannelLayout layout, int sampleRate) {
        this.layout = layout;
        this.sampleRate = sampleRate;
        this.concealer = new WaveformConcealer(layout.channels(), sampleRate);
        this.integers = new IntegerAudioBuffer(layout, sampleRate, 16, 0);
    }

    /**
     * Returns the integer planes for a block, valid until the next call.
     *
     * @param frames the number of samples per channel the block will hold
     * @return one plane per channel, each at least {@code frames} long
     */
    public int[][] planes(int frames) {
        if (integers.capacity() < frames) {
            integers = new IntegerAudioBuffer(layout, sampleRate, 16, frames);
        }
        return integers.planes();
    }

    /**
     * Hands the block written into {@link #planes(int)} to a consumer as floats.
     *
     * @param frames the number of samples per channel of the block, 0 for none
     * @param output receives the block
     */
    public void floats(int frames, Consumer<AudioBuffer> output) {
        if (frames == 0) {
            return;
        }
        if (floats == null || floats.capacity() < frames) {
            floats = new AudioBuffer(layout, sampleRate, Math.max(frames, floats == null ? 0 : floats.capacity()));
        }
        var source = integers.planes();
        var target = floats.planes();
        for (var c = 0; c < target.length; c++) {
            var from = source[c];
            var to = target[c];
            for (var i = 0; i < frames; i++) {
                to[i] = from[i] * SCALE;
            }
        }
        concealer.recover(target, frames);
        concealer.record(target, frames);
        floats.setFrames(frames);
        output.accept(floats);
    }

    /**
     * Hands the block written into {@link #planes(int)} to a consumer as 16-bit integers.
     *
     * @param frames the number of samples per channel of the block, 0 for none
     * @param output receives the block
     */
    public void integers(int frames, Consumer<IntegerAudioBuffer> output) {
        if (frames == 0) {
            return;
        }
        integers.setFrames(frames);
        output.accept(integers);
    }

    /**
     * Hands the block written into {@link #planes(int)} to a consumer as doubles.
     *
     * @param frames the number of samples per channel of the block, 0 for none
     * @param output receives the block
     */
    public void doubles(int frames, Consumer<DoubleAudioBuffer> output) {
        if (frames == 0) {
            return;
        }
        if (doubles == null || doubles.capacity() < frames) {
            doubles = new DoubleAudioBuffer(layout, sampleRate,
                    Math.max(frames, doubles == null ? 0 : doubles.capacity()));
        }
        var source = integers.planes();
        var target = doubles.planes();
        for (var c = 0; c < target.length; c++) {
            var from = source[c];
            var to = target[c];
            for (var i = 0; i < frames; i++) {
                to[i] = from[i] * 0x1p-15;
            }
        }
        doubles.setFrames(frames);
        output.accept(doubles);
    }

    /**
     * Synthesizes a lost span from the float blocks handed out before it.
     *
     * @param samples the samples per channel of the span, positive
     * @param output receives one or more blocks
     * @return {@code samples}
     */
    public int conceal(int samples, Consumer<AudioBuffer> output) {
        var chunk = Math.max(1, sampleRate / 50);
        if (floats == null || floats.capacity() < chunk) {
            floats = new AudioBuffer(layout, sampleRate, Math.max(chunk, floats == null ? 0 : floats.capacity()));
        }
        chunk = floats.capacity();
        var planes = floats.planes();
        for (var remaining = samples; remaining > 0; ) {
            var frames = Math.min(remaining, chunk);
            concealer.synthesize(planes, 0, frames);
            floats.setFrames(frames);
            output.accept(floats);
            remaining -= frames;
        }
        return samples;
    }
}
