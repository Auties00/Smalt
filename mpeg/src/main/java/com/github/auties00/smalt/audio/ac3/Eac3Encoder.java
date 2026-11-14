package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.ac3.internal.ChannelMap;
import com.github.auties00.smalt.audio.ac3.internal.EncoderSession;
import com.github.auties00.smalt.audio.ac3.internal.EncoderTools;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes planar float PCM to Dolby Digital Plus (E-AC-3) frames, as ATSC A/52 Annex E and ETSI
 * TS 102 366 specify it: one independent substream for programs of up to 5.1 channels, and for a
 * program beyond 5.1 an independent substream with its 5.1 core followed by dependent substreams
 * that carry the other channels.
 *
 * <p>Every packet is one access unit of {@link Eac3EncoderOptions#blocksPerFrame()} blocks of 256
 * samples per channel, at the constant bit rate of the options: frame sizes alternate between two
 * whole numbers of words so that the average is exact. The frames code the AC-3 tools (block
 * switching, channel coupling with phase flags, rematrixing, cost-based exponent strategies with
 * the frame-based strategy syntax of six-block frames when it is cheaper) in the E-AC-3 syntax,
 * with spectral extension, the adaptive hybrid transform, transient pre-noise processing and enhanced
 * coupling as the options allow them, the metadata of {@link Eac3EncoderOptions#metadata()}, and
 * carry no out-of-band configuration: MP4 derives its {@code dec3} box from them, and Matroska,
 * MPEG-TS and raw {@code .ec3} files store them as they are. Every access unit is independently
 * decodable.
 *
 * <p>A program beyond 5.1 codes in its core the front pair, the center, the side pair (or the back
 * pair) and the LFE, and every other channel with a custom channel map location in dependent
 * substreams, whose custom channel maps ({@code chanmap}) name the channels they carry (ETSI TS 102
 * 366 clause E.2.8.2 and table E.1.4; see {@link Eac3EncoderOptions#layout()}). An extra channel is
 * mixed into the core channels nearest it, so that a decoder that renders 5.1 plays it too, as long
 * as one dependent substream can still carry the extra channels and the unmixed core channels that
 * received a mix, which replace the mixed ones in a decoder that renders more: for 7.1 and 7.0 the
 * surround channels of the core mix each side channel with the back channel of its side at -3 dB,
 * and the dependent substream carries the side and back pairs. A program whose extra channels need
 * more than one dependent substream mixes all of them into the core. The bit rate is divided among
 * the substreams in proportion to their full-bandwidth channels, five to four for 7.1.
 *
 * <p>With transient pre-noise processing, the encoder holds each packet until it has coded the frames
 * whose decoded samples the packet's data may correct, one frame with six blocks per frame and three
 * with three, so {@link #encode(AudioBuffer, Consumer)} emits packets that many frames after their
 * input and {@link #flush(Consumer)} emits the held ones; the timestamps are those of the input.
 *
 * <p>The decoder output lags the input by the {@link #delay()} of 256 samples;
 * {@link #flush(Consumer)} pads the input with silence until every input sample is covered. The
 * input is clipped to {@code [-1, 1]}. Instances are not thread-safe.
 *
 * @see Ac3Encoder
 */
public final class Eac3Encoder implements AudioEncoder {

    /**
     * The options the encoder was created with.
     */
    private final Eac3EncoderOptions options;

    /**
     * The frame buffering and coding.
     */
    private final EncoderSession session;

    /**
     * Constructs an encoder under {@link Eac3EncoderOptions#defaults()}.
     */
    public Eac3Encoder() {
        this(Eac3EncoderOptions.defaults());
    }

    /**
     * Constructs an encoder under explicit options.
     *
     * @param options the format, bit rate and coding tools
     */
    public Eac3Encoder(Eac3EncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.session = new EncoderSession(options.sampleRate(), options.layout(), options.effectiveBitrate(),
                options.dialogueLevel(), options.blockSwitching(), options.channelCoupling(), true,
                options.blocksPerFrame(), tools(options), false, options.metadata());
    }

    /**
     * Returns the E-AC-3 tools the encoder uses for its options.
     *
     * @param options the encoder options
     * @return the tools of every substream
     */
    private static EncoderTools tools(Eac3EncoderOptions options) {
        var fullBandwidth = EncoderSession.plan(options.layout(), true).fullBandwidthChannels();
        var tools = options.spectralExtension()
                ? EncoderTools.defaults(options.sampleRate(), options.effectiveBitrate(), fullBandwidth) : EncoderTools.NONE;
        return tools.withHybridTransform(options.hybridTransform()).withPreNoise(options.preNoiseProcessing())
                .withEnhancedCoupling(options.enhancedCoupling());
    }

    /**
     * {@return the options the encoder was created with}
     */
    public Eac3EncoderOptions options() {
        return options;
    }

    @Override
    public Codec codec() {
        return Codec.EAC3;
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
     * @return {@code 256} times the blocks per frame
     */
    @Override
    public int frameSize() {
        return options.frameSize();
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
     * @return empty: E-AC-3 frames describe themselves
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
