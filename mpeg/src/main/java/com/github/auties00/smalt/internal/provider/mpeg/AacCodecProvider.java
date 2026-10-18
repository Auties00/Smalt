package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.aac.AacDecoder;
import com.github.auties00.smalt.audio.aac.AacDecoderOptions;
import com.github.auties00.smalt.audio.aac.AacEncoder;
import com.github.auties00.smalt.audio.aac.AacEncoderOptions;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link AacDecoder} to {@link AudioDecoders} for AAC tracks and {@link AacEncoder} to
 * {@link AudioEncoders}.
 *
 * @implNote This implementation maps the codec-independent options onto the AAC options as
 *           follows:
 *           <table class="striped">
 *           <caption>Option mapping</caption>
 *           <thead><tr><th scope="col">Codec-independent option</th><th scope="col">AAC option</th></tr></thead>
 *           <tbody>
 *           <tr><th scope="row">decoder limits</th><td>{@link AacDecoderOptions#limits()}</td></tr>
 *           <tr><th scope="row">preferred sample rate</th><td>ignored: the decoder outputs the core rate</td></tr>
 *           <tr><th scope="row">encoder sample rate and layout</th><td>{@link AacEncoderOptions.Builder#format(int,
 *           com.github.auties00.smalt.audio.ChannelLayout)}</td></tr>
 *           <tr><th scope="row">encoder bitrate</th><td>{@link AacEncoderOptions.Builder#bitrate(int)}, or
 *           {@value #DEFAULT_CHANNEL_BITRATE} bits per second per channel when the options leave it to the
 *           codec</td></tr>
 *           <tr><th scope="row">real time</th><td>ignored: AAC-LC has no low-delay mode</td></tr>
 *           <tr><th scope="row">everything else</th><td>{@link AacEncoderOptions#defaults()}: constant bitrate,
 *           automatic bandwidth</td></tr>
 *           </tbody>
 *           </table>
 */
public final class AacCodecProvider implements AudioCodecProvider {
    /**
     * The bitrate per channel used when the options leave the bitrate to the codec.
     */
    private static final int DEFAULT_CHANNEL_BITRATE = 64_000;

    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public AacCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.AAC;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new AacDecoder(track, AacDecoderOptions.builder().limits(options.limits()).build());
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.AAC;
    }

    @Override
    public AudioEncoder createEncoder(Codec codec, AudioEncoderOptions options) {
        var bitrate = options.bitrate().orElse(DEFAULT_CHANNEL_BITRATE * options.layout().channels());
        return new AacEncoder(AacEncoderOptions.of(options.sampleRate(), options.layout(), bitrate));
    }
}
