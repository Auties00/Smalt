package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.ac3.internal.EncoderSession;
import com.github.auties00.smalt.audio.ac3.internal.EncoderTools;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes planar float PCM to Dolby Digital (AC-3) sync frames, as ATSC A/52 specifies them.
 *
 * <p>Every packet is one sync frame of 1536 samples per channel, at the constant bit rate of the
 * options; at 44.1 kHz the frame size alternates between the two sizes of the rate so that the
 * average is exact. The frames carry the dialogue level and the metadata of
 * {@link Ac3EncoderOptions#metadata()}, in the alternate bit stream syntax of ATSC A/52 Annex D
 * when it needs it, and no out-of-band configuration: MP4 derives its {@code dac3} box from them,
 * and Matroska, MPEG-TS and raw {@code .ac3} files store them as they are. The
 * decoder output lags the input by the {@link #delay()} of 256 samples; {@link #flush(Consumer)}
 * pads the input with silence until every input sample is covered.
 *
 * <p>The input is clipped to {@code [-1, 1]}. Instances are not thread-safe.
 *
 * @see Eac3Encoder
 */
public final class Ac3Encoder implements AudioEncoder {
    /**
     * The samples per channel of one frame.
     */
    private static final int FRAME = 1536;

    /**
     * The options the encoder was created with.
     */
    private final Ac3EncoderOptions options;

    /**
     * The frame buffering and coding.
     */
    private final EncoderSession session;

    /**
     * Constructs an encoder under {@link Ac3EncoderOptions#defaults()}.
     */
    public Ac3Encoder() {
        this(Ac3EncoderOptions.defaults());
    }

    /**
     * Constructs an encoder under explicit options.
     *
     * @param options the format, bit rate and coding tools
     */
    public Ac3Encoder(Ac3EncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.session = new EncoderSession(options.sampleRate(), options.layout(), options.effectiveBitrate(),
                options.dialogueLevel(), options.blockSwitching(), options.channelCoupling(), false, 6,
                EncoderTools.NONE, options.fastSearch(), options.metadata());
    }

    /**
     * {@return the options the encoder was created with}
     */
    public Ac3EncoderOptions options() {
        return options;
    }

    @Override
    public Codec codec() {
        return Codec.AC3;
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
     * @return 1536
     */
    @Override
    public int frameSize() {
        return FRAME;
    }

    /**
     * {@inheritDoc}
     *
     * @return 256, the overlap of the filter bank
     */
    @Override
    public int delay() {
        return EncoderSession.DELAY;
    }

    /**
     * {@inheritDoc}
     *
     * @return empty: AC-3 frames describe themselves
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
        session.encode(input, output);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Silence is appended until the frames cover every input sample plus the delay; a second
     * call emits nothing.
     */
    @Override
    public void flush(Consumer<Packet> output) {
        session.flush(output);
    }
}
