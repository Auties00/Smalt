package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Wraps the output of an audio encoder that writes a whole stream: holds back its last packet so
 * that the packet can be trimmed to the real end of the input once the stream ends.
 *
 * <p>Packets pass through one behind, with their track identifier replaced; {@link #finish(long)}
 * writes the held packet with its duration cut to the samples the input really had. The static
 * methods open the encoder with the usual fallbacks and declare its track. Instances are not
 * thread-safe.
 */
public final class EncoderTail implements Consumer<Packet> {
    /**
     * The sampling rate tried when the requested one is not supported.
     */
    public static final int FALLBACK_RATE = 48_000;

    /**
     * The identifier of the output track.
     */
    private final int trackId;

    /**
     * The receiver of the packets.
     */
    private final Consumer<Packet> output;

    /**
     * The last packet, not yet written, or {@code null}.
     */
    private Packet held;

    /**
     * Constructs the tail of a stream.
     *
     * @param trackId the identifier the written packets carry
     * @param output receives the packets, one behind the encoder
     */
    public EncoderTail(int trackId, Consumer<Packet> output) {
        this.trackId = trackId;
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * Opens an encoder, falling back to other settings the encoder may support.
     *
     * <p>The candidates are tried in this order: the requested rate and layout; 48 kHz unless
     * {@code fixedRate}; and, unless {@code fixedLayout} and when the layout has more than two
     * channels, stereo at the requested rate and then stereo at 48 kHz (unless {@code fixedRate}).
     * A candidate the registry rejects with an {@link IllegalArgumentException} is skipped.
     *
     * @param codec the codec to encode
     * @param rate the requested sampling rate in hertz
     * @param layout the requested layout
     * @param fixedRate whether the caller asked for {@code rate} explicitly, so no other rate is
     * tried
     * @param fixedLayout whether the caller asked for {@code layout} explicitly, so no other
     * layout is tried
     * @param options builds the encoder options of one candidate rate and layout
     * @return the encoder of the first accepted candidate
     * @throws IllegalArgumentException the failure of the first candidate, with the later
     * failures suppressed, when the registry rejects every candidate
     */
    public static AudioEncoder open(Codec codec, int rate, ChannelLayout layout, boolean fixedRate, boolean fixedLayout,
                                    BiFunction<Integer, ChannelLayout, AudioEncoderOptions> options) {
        var candidates = new ArrayList<AudioEncoderOptions>();
        candidates.add(options.apply(rate, layout));
        if (!fixedRate && rate != FALLBACK_RATE) {
            candidates.add(options.apply(FALLBACK_RATE, layout));
        }
        if (!fixedLayout && layout.channels() > 2) {
            candidates.add(options.apply(rate, ChannelLayout.STEREO));
            if (!fixedRate && rate != FALLBACK_RATE) {
                candidates.add(options.apply(FALLBACK_RATE, ChannelLayout.STEREO));
            }
        }
        IllegalArgumentException failure = null;
        for (var candidate : candidates) {
            try {
                return AudioEncoders.create(codec, candidate);
            } catch (IllegalArgumentException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        throw failure;
    }

    /**
     * Returns a track builder describing an encoder's output: its codec, a time base of one
     * sample, its configuration, rate and layout, and its delay as the samples to skip, except for
     * an Opus stream, whose {@code OpusHead} pre-skip carries the delay in band.
     *
     * @param encoder the encoder whose output the track carries
     * @param trackId the track identifier
     * @return the builder, for the caller to complete and build
     */
    public static AudioTrack.Builder track(AudioEncoder encoder, int trackId) {
        return AudioTrack.builder()
                .id(trackId)
                .codec(encoder.codec())
                .timeBase(Rational.timeBaseOf(encoder.sampleRate()))
                .codecConfig(encoder.codecConfig().orElse(null))
                .sampleRate(encoder.sampleRate())
                .layout(encoder.layout())
                .skipSamples(encoder.codec() == Codec.OPUS && encoder.codecConfig().isPresent()
                        ? 0 : encoder.delay());
    }

    /**
     * Holds an encoded packet and writes the one held before it.
     *
     * @param packet the packet the encoder produced
     */
    @Override
    public void accept(Packet packet) {
        if (held != null) {
            output.accept(held);
        }
        held = packet.withTrackId(trackId);
    }

    /**
     * Writes the held packet, trimmed to the end of the input.
     *
     * <p>The duration of the last packet becomes {@code end - pts}, kept between 1 and its coded
     * duration. Nothing is written when no packet is held.
     *
     * @param end the end of the input in samples of the output rate, counting the encoder's delay:
     * the number of input samples plus {@link AudioEncoder#delay()}
     */
    public void finish(long end) {
        if (held == null) {
            return;
        }
        var duration = Math.clamp(end - held.pts(), 1, Math.max(held.duration(), 1));
        output.accept(held.withTiming(held.pts(), held.dts(), duration));
        held = null;
    }
}
