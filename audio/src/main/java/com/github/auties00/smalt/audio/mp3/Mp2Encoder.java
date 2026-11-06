package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes planar float samples into MPEG audio Layer II (MP2) or Layer I (MP1) frames, the audio of
 * the Video CD, DVB and DAB broadcasts and DVD-Video.
 *
 * <p>Each packet is one complete frame, header included, so writing the packets back to back gives an
 * {@code .mp2} elementary stream; the track codec is {@link Codec#MP2} for both layers. Frames at 32,
 * 44.1 and 48 kHz are MPEG-1 (ISO/IEC 11172-3), at 16, 22.05 and 24 kHz MPEG-2 lower sampling frequency
 * frames (ISO/IEC 13818-3); a Layer II frame holds 1152 samples per channel and a Layer I frame 384.
 * Every frame decodes on its own: the bitrate is constant and there is no bit reservoir. The format
 * needs no out-of-band configuration, so {@link #codecConfig()} is empty. Packet timestamps count
 * samples from the first input sample; a decoder outputs {@link #delay()} samples before the first
 * input sample, which the container signals.
 *
 * <p>Coding tools: the polyphase filterbank of the standard, a scalefactor per subband and block of 12
 * samples (Layer II sends one, two or three per frame), the psychoacoustic model 1 of ISO/IEC 11172-3
 * Annex D, bit allocation by the smallest mask-to-noise ratio, and in joint stereo mode intensity
 * stereo above a bound chosen per frame. Frames are bit-exact functions of the input and options:
 * encoding the same samples twice produces the same bytes.
 *
 * <p>The encoder buffers input of any length and emits each frame as soon as its samples are in;
 * {@link #flush(Consumer)} pads the input with silence until every input sample is covered and emits
 * the remaining frames. Input samples outside {@code [-1, 1]} are clipped; NaN samples are coded as
 * silence.
 *
 * <p>Instances are not thread-safe.
 */
public final class Mp2Encoder implements AudioEncoder {
    /**
     * The number of samples between the first input sample and the first sample a decoder outputs
     * for it: the delay of the polyphase analysis and synthesis filterbanks.
     */
    static final int DELAY = 481;

    /**
     * The options the encoder was created with.
     */
    private final Mp2EncoderOptions options;

    /**
     * The frame encoder.
     */
    private final Mp2FrameEncoder engine;

    /**
     * The number of samples per channel of one frame.
     */
    private final int frameSize;

    /**
     * The frame being filled, one plane per channel.
     */
    private final float[][] frame;

    /**
     * The number of samples per channel in {@link #frame}.
     */
    private int filled;

    /**
     * The number of input samples per channel received.
     */
    private long received;

    /**
     * The number of packets emitted.
     */
    private long emitted;

    /**
     * Whether {@link #flush(Consumer)} ran.
     */
    private boolean flushed;

    /**
     * Constructs an encoder for 48 kHz stereo input at 192 kbit/s Layer II, the settings of
     * {@link Mp2EncoderOptions#defaults()}.
     */
    public Mp2Encoder() {
        this(Mp2EncoderOptions.defaults());
    }

    /**
     * Constructs an encoder.
     *
     * @param options the settings
     */
    public Mp2Encoder(Mp2EncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.engine = new Mp2FrameEncoder(options);
        this.frameSize = engine.frameSamples();
        this.frame = new float[options.layout().channels()][frameSize];
    }

    /**
     * Returns the options the encoder was created with.
     *
     * @return the options
     */
    public Mp2EncoderOptions options() {
        return options;
    }

    @Override
    public Codec codec() {
        return Codec.MP2;
    }

    @Override
    public int sampleRate() {
        return options.sampleRate();
    }

    @Override
    public ChannelLayout layout() {
        return options.layout();
    }

    /**
     * {@inheritDoc}
     *
     * @return 1152 for Layer II, 384 for Layer I
     */
    @Override
    public int frameSize() {
        return frameSize;
    }

    /**
     * {@inheritDoc}
     *
     * @return 481: the delay of the polyphase analysis and synthesis filterbanks
     */
    @Override
    public int delay() {
        return DELAY;
    }

    /**
     * {@inheritDoc}
     *
     * @return empty: MPEG audio frames carry their configuration in their headers
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the encoder was flushed
     */
    @Override
    public void encode(AudioBuffer input, Consumer<Packet> output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (flushed) {
            throw new IllegalStateException("the encoder was flushed");
        }
        if (input.sampleRate() != options.sampleRate() || !input.layout().equals(options.layout())) {
            throw new IllegalArgumentException("input " + input.sampleRate() + " Hz " + input.layout().speakers()
                    + " does not match the encoder's " + options.sampleRate() + " Hz " + options.layout().speakers());
        }
        var planes = input.planes();
        var count = input.frames();
        received += count;
        var offset = 0;
        while (offset < count) {
            var take = Math.min(count - offset, frameSize - filled);
            for (var ch = 0; ch < frame.length; ch++) {
                var source = planes[ch];
                var target = frame[ch];
                for (var i = 0; i < take; i++) {
                    var x = source[offset + i];
                    target[filled + i] = x != x ? 0 : Math.clamp(x, -1.0f, 1.0f);
                }
            }
            filled += take;
            offset += take;
            if (filled == frameSize) {
                emit(output);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>After a flush the encoder accepts no further input.
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        if (flushed) {
            return;
        }
        flushed = true;
        if (received == 0) {
            return;
        }
        var needed = (received + DELAY + frameSize - 1) / frameSize;
        while (emitted < needed) {
            for (var plane : frame) {
                Arrays.fill(plane, filled, frameSize, 0);
            }
            emit(output);
        }
    }

    /**
     * Codes the filled frame and emits its packet.
     *
     * @param output receives the packet
     */
    private void emit(Consumer<Packet> output) {
        var data = engine.encode(frame);
        filled = 0;
        var timestamp = emitted * frameSize;
        emitted++;
        output.accept(new Packet(0, data, timestamp, timestamp, frameSize, true));
    }
}
