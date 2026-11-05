package com.github.auties00.smalt.internal.provider.audio;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.alac.AlacDecoder;
import com.github.auties00.smalt.audio.alac.AlacEncoder;
import com.github.auties00.smalt.audio.alac.AlacEncoderOptions;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link AlacDecoder} to {@link AudioDecoders} for ALAC tracks, as the MP4 and Matroska
 * demuxers deliver them, and {@link AlacEncoder} to {@link AudioEncoders}.
 *
 * @implNote This implementation maps the codec-independent options onto the ALAC options as
 *           follows:
 *           <table class="striped">
 *           <caption>Option mapping</caption>
 *           <thead><tr><th scope="col">Codec-independent option</th><th scope="col">ALAC option</th></tr></thead>
 *           <tbody>
 *           <tr><th scope="row">decoder limits</th><td>{@link AudioDecoderOptions#limits()}, passed on
 *           unchanged</td></tr>
 *           <tr><th scope="row">preferred sample rate</th><td>ignored: the decoder outputs the stream's own
 *           rate</td></tr>
 *           <tr><th scope="row">encoder sample rate and layout</th><td>{@link AlacEncoderOptions.Builder#format(int,
 *           com.github.auties00.smalt.audio.ChannelLayout)}</td></tr>
 *           <tr><th scope="row">encoder bitrate</th><td>ignored: ALAC is lossless</td></tr>
 *           <tr><th scope="row">lossless sample size</th><td>{@link AlacEncoderOptions.Builder#bitsPerSample(int)},
 *           the smallest of 16, 20, 24 and 32 bits that holds the requested size, or 16 bits when the options
 *           leave it to the codec</td></tr>
 *           <tr><th scope="row">real time</th><td>{@link AlacEncoderOptions#forSpeed()} when set, otherwise
 *           {@link AlacEncoderOptions#defaults()}</td></tr>
 *           </tbody>
 *           </table>
 */
public final class AlacCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public AlacCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.ALAC;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new AlacDecoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.ALAC;
    }

    @Override
    public AudioEncoder createEncoder(Codec codec, AudioEncoderOptions options) {
        var preset = options.realtime() ? AlacEncoderOptions.forSpeed() : AlacEncoderOptions.defaults();
        var requested = options.bitsPerSample().orElse(16);
        var bits = requested <= 16 ? 16 : requested <= 20 ? 20 : requested <= 24 ? 24 : 32;
        return new AlacEncoder(preset.toBuilder()
                .format(options.sampleRate(), options.layout())
                .bitsPerSample(bits)
                .build());
    }
}
