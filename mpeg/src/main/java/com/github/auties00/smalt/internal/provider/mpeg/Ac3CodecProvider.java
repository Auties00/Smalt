package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ac3.Ac3Decoder;
import com.github.auties00.smalt.audio.ac3.Ac3DecoderOptions;
import com.github.auties00.smalt.audio.ac3.Ac3Encoder;
import com.github.auties00.smalt.audio.ac3.Ac3EncoderOptions;
import com.github.auties00.smalt.audio.ac3.Eac3Encoder;
import com.github.auties00.smalt.audio.ac3.Eac3EncoderOptions;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link Ac3Decoder} to {@link AudioDecoders} for AC-3 and E-AC-3 tracks, and
 * {@link Ac3Encoder} and {@link Eac3Encoder} to {@link AudioEncoders} for AC-3 and E-AC-3.
 *
 * @implNote This implementation maps the codec-independent options onto the AC-3 options as
 * follows:
 * <table class="striped">
 * <caption>Option mapping</caption>
 * <thead><tr><th scope="col">Codec-independent option</th><th scope="col">AC-3 option</th></tr></thead>
 * <tbody>
 * <tr><th scope="row">decoder limits</th><td>{@link Ac3DecoderOptions#limits()}</td></tr>
 * <tr><th scope="row">preferred sample rate</th><td>ignored: the stream's rate is output</td></tr>
 * <tr><th scope="row">everything else of the decoder</th><td>{@link Ac3DecoderOptions#defaults()}:
 * every coded channel, light dynamic range compression</td></tr>
 * <tr><th scope="row">encoder sample rate and layout</th><td>{@link Ac3EncoderOptions#of(int,
 * com.github.auties00.smalt.audio.ChannelLayout)}; a rate other than 32, 44.1 or 48 kHz codes at
 * 48 kHz and a layout AC-3 cannot carry codes the closest one, both reported through
 * {@link AudioEncoder#sampleRate()} and {@link AudioEncoder#layout()} for callers to convert to; for E-AC-3
 * {@link Eac3EncoderOptions#of(int, com.github.auties00.smalt.audio.ChannelLayout)}, which also keeps
 * the reduced rates and codes 7.1 and 7.0 with a dependent substream</td></tr>
 * <tr><th scope="row">encoder bitrate</th><td>the closest AC-3 bitrate, or for E-AC-3 the requested
 * bitrate brought into {@link Eac3EncoderOptions#minimumBitrate()} and
 * {@link Eac3EncoderOptions#maximumBitrate()}; the default bitrate of the channel count when the
 * options leave it to the codec</td></tr>
 * <tr><th scope="row">real time</th><td>AC-3: the faster search of {@link Ac3EncoderOptions#forRealtime()};
 * E-AC-3: one block per frame ({@link Eac3EncoderOptions#forRealtime()}) at 32, 44.1 and 48 kHz</td></tr>
 * </tbody>
 * </table>
 */
public final class Ac3CodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public Ac3CodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.AC3 || codec == Codec.EAC3;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new Ac3Decoder(track, Ac3DecoderOptions.builder().limits(options.limits()).build());
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.AC3 || codec == Codec.EAC3;
    }

    @Override
    public AudioEncoder createEncoder(Codec codec, AudioEncoderOptions options) {
        if (codec == Codec.EAC3) {
            var enhancedOptions = Eac3EncoderOptions.of(options.sampleRate(), options.layout());
            if (options.realtime() && enhancedOptions.sampleRate() >= 32_000) {
                enhancedOptions = enhancedOptions.toBuilder().blocksPerFrame(1).build();
            }
            var requested = options.bitrate();
            if (requested.isPresent()) {
                enhancedOptions = enhancedOptions.toBuilder().bitrate(Math.max(enhancedOptions.minimumBitrate(),
                        Math.min(enhancedOptions.maximumBitrate(), requested.getAsInt()))).build();
            }
            return new Eac3Encoder(enhancedOptions);
        }
        var encoderOptions = Ac3EncoderOptions.of(options.sampleRate(), options.layout()).toBuilder()
                .fastSearch(options.realtime());
        var bitrate = options.bitrate();
        if (bitrate.isPresent()) {
            encoderOptions.bitrate(Ac3EncoderOptions.closestBitrate(bitrate.getAsInt()));
        }
        return new Ac3Encoder(encoderOptions.build());
    }
}
