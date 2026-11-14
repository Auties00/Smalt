package com.github.auties00.smalt.audio.mace;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.codec.WaveformConcealer;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.pcm16.Pcm16Output;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes Apple's Macintosh Audio Compression/Expansion ({@link Codec#MACE3} and {@link Codec#MACE6}) packets into
 * 16-bit samples.
 *
 * <p>A packet holds whole frames in the layouts the codec constants describe, and the state of every channel
 * continues from one packet to the next. A packet that does not hold whole frames raises
 * {@link MediaException.Malformed}. Any bytes are a valid stream: there is no header and no reserved code.
 *
 * <p>For one and two channels, the output equals QuickTime 7.7.9's sample for sample, on crafted streams that reach
 * every table entry and on streams its own encoder wrote. QuickTime takes no more channels; more are decoded in the
 * same layout, each independently. The format decodes to 8-bit samples, and a 16-bit sample repeats the 8-bit one in
 * both of its bytes as QuickTime's 16-bit output does, from {@code 0x8080} to {@code 0x7F7F}.
 *
 * <p>The float and double outputs scale the 16-bit samples by {@code 2^-15}. A lost span
 * ({@link #conceal(int, Consumer)}) repeats the last pitch period of the float output, fading to silence, as
 * ITU-T G.711 Appendix I conceals G.711 frames; the decoder state continues unchanged after it.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation follows the structure of US patent 5,083,310: the 3-2-3 bit fields, the adaptive
 * step, the fixed predictor of the 3:1 path, the sign-driven adaptive predictor of the 6:1 path and its pairs of
 * interpolated samples. The patent names none of the integer arithmetic. The tables, the adaptation constants, the
 * rounding and the limits come from measuring QuickTime as a black box.
 */
public final class MaceDecoder implements AudioDecoder {
    /**
     * The limit of the predicted sample and of the 6:1 predictor coefficient; both stop short of {@code -32768}.
     */
    private static final int LIMIT = 32767;

    /**
     * The mask of a row number; the adaptation state itself does not wrap, so a state past the last row reads the
     * first rows again.
     */
    private static final int ROW_MASK = MaceTables.ROWS - 1;

    /**
     * What the 6:1 predictor coefficient, in units of {@code 2^-15}, gains when a step has the sign of the sample
     * before it.
     */
    private static final int COEFFICIENT_RISE = 506;

    /**
     * What the 6:1 predictor coefficient, in units of {@code 2^-15}, loses when a step has the opposite sign.
     */
    private static final int COEFFICIENT_FALL = 314;

    private final boolean sixToOne;
    private final ChannelLayout layout;
    private final int sampleRate;
    private final MediaLimits limits;
    private final Pcm16Output output;

    /**
     * The adaptation state of each channel.
     */
    private final int[] adaptation;

    /**
     * The newest reconstructed sample of each channel.
     */
    private final int[] newest;

    /**
     * The reconstructed sample before {@link #newest}, of each channel; 6:1 only.
     */
    private final int[] previous;

    /**
     * The predictor coefficient of each channel, in units of {@code 2^-15}; 6:1 only.
     */
    private final int[] coefficient;

    /**
     * Constructs a decoder for a MACE track.
     *
     * @param track the track, with its sampling rate and channel layout
     * @throws IllegalArgumentException if the track's codec is not {@link Codec#MACE3} or {@link Codec#MACE6}
     * @throws MediaException.Malformed if the track lacks its sampling rate or layout
     */
    public MaceDecoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a MACE track under the limits of codec-independent options.
     *
     * <p>Only {@link AudioDecoderOptions#limits()} applies: the decoder always outputs the track's own rate.
     *
     * @param track the track, with its sampling rate and channel layout
     * @param options the codec-independent options, of which only the limits apply
     * @throws IllegalArgumentException if the track's codec is not {@link Codec#MACE3} or {@link Codec#MACE6}
     * @throws MediaException.Malformed if the track lacks its sampling rate or layout
     * @throws MediaException.LimitExceeded if the track's channels or the decoder's state exceed the limits
     */
    public MaceDecoder(AudioTrack track, AudioDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.MACE3 && track.codec() != Codec.MACE6) {
            throw new IllegalArgumentException("not a MACE track: " + track.codec());
        }
        this.sixToOne = track.codec() == Codec.MACE6;
        this.layout = track.layout().orElseThrow(() -> new MediaException.Malformed(track.codec().displayName()
                + " track without a channel layout"));
        if (track.sampleRate() <= 0) {
            throw new MediaException.Malformed(track.codec().displayName() + " track without a sampling rate");
        }
        this.sampleRate = track.sampleRate();
        this.limits = options.limits();
        var channels = layout.channels();
        LimitChecks.checkChannels(limits, channels);
        LimitChecks.checkAllocation(limits, WaveformConcealer.footprint(channels, sampleRate));
        this.adaptation = new int[channels];
        this.newest = new int[channels];
        this.previous = new int[channels];
        this.coefficient = new int[channels];
        this.output = new Pcm16Output(layout, sampleRate);
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed if the packet does not hold whole frames
     * @throws MediaException.LimitExceeded if the packet's samples exceed the decoder's allocation limit
     */
    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        this.output.floats(decodePacket(packet), output);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: the decoder buffers no samples between packets.
     */
    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsIntegerOutput() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return 16
     */
    @Override
    public int bitsPerSample() {
        return 16;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed if the packet does not hold whole frames
     * @throws MediaException.LimitExceeded if the packet's samples exceed the decoder's allocation limit
     */
    @Override
    public void decodeIntegers(Packet packet, Consumer<IntegerAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        this.output.integers(decodePacket(packet), output);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: the decoder buffers no samples between packets.
     */
    @Override
    public void flushIntegers(Consumer<IntegerAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsDoubleOutput() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed if the packet does not hold whole frames
     * @throws MediaException.LimitExceeded if the packet's samples exceed the decoder's allocation limit
     */
    @Override
    public void decodeDoubles(Packet packet, Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        this.output.doubles(decodePacket(packet), output);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: the decoder buffers no samples between packets.
     */
    @Override
    public void flushDoubles(Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsConcealment() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code samples}, the span being produced exactly
     * @throws IllegalArgumentException if {@code samples} is not positive
     */
    @Override
    public int conceal(int samples, Consumer<AudioBuffer> output) {
        Checks.positive("samples", samples);
        Objects.requireNonNull(output, "output");
        return this.output.conceal(samples, output);
    }

    /**
     * Decodes a packet into the output's integer planes.
     *
     * @param packet the coded bytes and their timing
     * @return the samples per channel decoded
     */
    private int decodePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        var data = packet.data();
        var channels = layout.channels();
        var unit = sixToOne ? 1 : 2;
        if (data.length % (unit * channels) != 0) {
            throw new MediaException.Malformed("MACE packet of " + data.length + " bytes is not whole frames of "
                    + unit * channels + " bytes");
        }
        var frames = data.length / (unit * channels);
        var samples = frames * 6L;
        LimitChecks.checkAllocation(limits, samples * channels * (Integer.BYTES + Double.BYTES));
        var planes = output.planes(Math.max((int) samples, 1));
        for (var channel = 0; channel < channels; channel++) {
            if (sixToOne) {
                decodeSixToOne(data, channel, channels, frames, channel, planes[channel]);
            } else {
                decodeThreeToOne(data, channel * unit, channels * unit, frames, channel, planes[channel]);
            }
        }
        return (int) samples;
    }

    /**
     * Decodes one channel of a 3:1 packet, whose bytes hold the fields low, middle, high in time order.
     *
     * @param data the packet
     * @param start the offset of the channel's first byte
     * @param stride the distance from one frame of the channel to the next
     * @param frames the number of frames
     * @param channel the index of the channel, for its state
     * @param plane receives six samples per frame
     */
    private void decodeThreeToOne(byte[] data, int start, int stride, int frames, int channel, int[] plane) {
        var state = adaptation[channel];
        var sample = newest[channel];
        var position = 0;
        for (var frame = 0; frame < frames; frame++) {
            var offset = start + frame * stride;
            for (var part = 0; part < 2; part++) {
                var value = data[offset + part] & 0xFF;
                var code = value & 7;
                sample = predictFixed(sample, MaceTables.STEPS_3[(state >> 4 & ROW_MASK) << 3 | code]);
                state = adapt(state, MaceTables.ADAPT_3[code]);
                plane[position++] = widen(sample >> 8);
                code = value >> 3 & 3;
                sample = predictFixed(sample, MaceTables.STEPS_2[(state >> 4 & ROW_MASK) << 2 | code]);
                state = adapt(state, MaceTables.ADAPT_2[code]);
                plane[position++] = widen(sample >> 8);
                code = value >> 5;
                sample = predictFixed(sample, MaceTables.STEPS_3[(state >> 4 & ROW_MASK) << 3 | code]);
                state = adapt(state, MaceTables.ADAPT_3[code]);
                plane[position++] = widen(sample >> 8);
            }
        }
        adaptation[channel] = state;
        newest[channel] = sample;
    }

    /**
     * Decodes one channel of a 6:1 packet, whose bytes hold the fields high, middle, low in time order, each field
     * giving two samples.
     *
     * @param data the packet
     * @param start the offset of the channel's first byte
     * @param stride the distance from one frame of the channel to the next
     * @param frames the number of frames
     * @param channel the index of the channel, for its state
     * @param plane receives six samples per frame
     */
    private void decodeSixToOne(byte[] data, int start, int stride, int frames, int channel, int[] plane) {
        var position = 0;
        for (var frame = 0; frame < frames; frame++) {
            var value = data[start + frame * stride] & 0xFF;
            var code = value >> 5;
            position = interpolate(channel, MaceTables.STEPS_3[(adaptation[channel] >> 4 & ROW_MASK) << 3 | code],
                    MaceTables.ADAPT_3[code], plane, position);
            code = value >> 3 & 3;
            position = interpolate(channel, MaceTables.STEPS_2[(adaptation[channel] >> 4 & ROW_MASK) << 2 | code],
                    MaceTables.ADAPT_2[code], plane, position);
            code = value & 7;
            position = interpolate(channel, MaceTables.STEPS_3[(adaptation[channel] >> 4 & ROW_MASK) << 3 | code],
                    MaceTables.ADAPT_3[code], plane, position);
        }
    }

    /**
     * Reconstructs the next sample of a 6:1 channel and writes the two interpolated output samples it yields.
     *
     * <p>The two samples weigh the newest three reconstructed samples by 1:4:3 and 3:4:1, in eighths, each term
     * truncated on its own as QuickTime does. The coefficient predicts from the sample before this one and is then
     * updated for the next sample, by the signs of that same earlier sample and of this step.
     *
     * @param channel the index of the channel, for its state
     * @param step the step of the code
     * @param adapt the adaptation of the code
     * @param plane receives the two samples
     * @param position where to write them
     * @return the position after them
     */
    private int interpolate(int channel, int step, int adapt, int[] plane, int position) {
        adaptation[channel] = adapt(adaptation[channel], adapt);
        var last = newest[channel];
        var factor = coefficient[channel];
        var sample = clamp((factor * last >> 15) + step);
        coefficient[channel] = clamp(factor + ((last < 0) == (step < 0) ? COEFFICIENT_RISE : -COEFFICIENT_FALL));
        var before = previous[channel];
        previous[channel] = last;
        newest[channel] = sample;
        plane[position] = widen(((sample >> 3) + (last >> 1) + (3 * before >> 3)) >> 8);
        plane[position + 1] = widen(((3 * sample >> 3) + (last >> 1) + (before >> 3)) >> 8);
        return position + 2;
    }

    /**
     * Applies the fixed 7/8 predictor of the 3:1 path.
     *
     * @param sample the previous reconstructed sample
     * @param step the step of the code
     * @return the next reconstructed sample
     */
    private static int predictFixed(int sample, int step) {
        return clamp(sample - (sample >> 3) + step);
    }

    /**
     * Applies a code's adaptation, with the leak of 1/32 of the state.
     *
     * @param state the adaptation state
     * @param adapt the adaptation of the code
     * @return the next state, never negative
     */
    private static int adapt(int state, int adapt) {
        return Math.max(0, state + adapt - (state >> 5));
    }

    /**
     * Clamps a value to the range of a reconstructed sample and of the coefficient.
     *
     * @param value the sample or coefficient
     * @return the value, limited to {@code -32767..32767}
     */
    private static int clamp(int value) {
        return Math.max(-LIMIT, Math.min(LIMIT, value));
    }

    /**
     * Expands a signed 8-bit sample to 16 bits by repeating its byte, as QuickTime's 16-bit output does.
     *
     * @param sample the sample, {@code -128..127}
     * @return the 16-bit sample
     */
    private static int widen(int sample) {
        return sample << 8 | sample & 0xFF;
    }
}
