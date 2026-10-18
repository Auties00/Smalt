package com.github.auties00.smalt.internal.provider.audio;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.flac.FlacDecoder;
import com.github.auties00.smalt.audio.flac.FlacEncoder;
import com.github.auties00.smalt.audio.flac.FlacEncoderOptions;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link FlacDecoder} to {@link AudioDecoders} and {@link FlacEncoder} to
 * {@link AudioEncoders} for FLAC tracks.
 *
 * @implNote This implementation maps the codec-independent options onto the FLAC options as
 *           follows:
 *           <table class="striped">
 *           <caption>Option mapping</caption>
 *           <thead><tr><th scope="col">Codec-independent option</th><th scope="col">FLAC option</th></tr></thead>
 *           <tbody>
 *           <tr><th scope="row">decoder limits</th><td>{@link AudioDecoderOptions#limits()}, passed on
 *           unchanged</td></tr>
 *           <tr><th scope="row">preferred sample rate</th><td>ignored: the decoder outputs the stream's own
 *           rate</td></tr>
 *           <tr><th scope="row">encoder sample rate and layout</th><td>{@link FlacEncoderOptions.Builder#format(int,
 *           com.github.auties00.smalt.audio.ChannelLayout)}</td></tr>
 *           <tr><th scope="row">encoder bitrate</th><td>ignored: FLAC is lossless</td></tr>
 *           <tr><th scope="row">lossless sample size</th><td>{@link FlacEncoderOptions.Builder#bitsPerSample(int)},
 *           clamped to 4 to 24 bits (the registry's encoders take float samples, which hold 24 bits), or 16 bits
 *           when the options leave it to the codec</td></tr>
 *           <tr><th scope="row">real time</th><td>{@link FlacEncoderOptions#forSpeed()} (compression level 0) when set,
 *           otherwise {@link FlacEncoderOptions#defaults()} (level 5)</td></tr>
 *           <tr><th scope="row">everything else</th><td>a seek point every ten seconds and 8192 bytes of
 *           padding</td></tr>
 *           </tbody>
 *           </table>
 */
public final class FlacCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public FlacCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.FLAC;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new FlacDecoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.FLAC;
    }

    @Override
    public AudioEncoder createEncoder(Codec codec, AudioEncoderOptions options) {
        var preset = options.realtime() ? FlacEncoderOptions.forSpeed() : FlacEncoderOptions.defaults();
        var bits = Math.clamp(options.bitsPerSample().orElse(16), 4, 24);
        return new FlacEncoder(preset.toBuilder()
                .format(options.sampleRate(), options.layout())
                .bitsPerSample(bits)
                .build());
    }
}
