package com.github.auties00.smalt.internal.provider.audio;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.opus.OpusApplication;
import com.github.auties00.smalt.audio.opus.OpusDecoder;
import com.github.auties00.smalt.audio.opus.OpusDecoderOptions;
import com.github.auties00.smalt.audio.opus.OpusEncoder;
import com.github.auties00.smalt.audio.opus.OpusEncoderOptions;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link OpusDecoder} to {@link AudioDecoders} for Opus tracks, decoding natively at 8,
 * 12, 16, 24 or 48 kHz when the caller prefers one of those rates, and {@link OpusEncoder} to
 * {@link AudioEncoders}.
 *
 * @implNote This implementation maps the codec-independent options onto the Opus options as
 *           follows:
 *           <table class="striped">
 *           <caption>Option mapping</caption>
 *           <thead><tr><th scope="col">Codec-independent option</th><th scope="col">Opus option</th></tr></thead>
 *           <tbody>
 *           <tr><th scope="row">decoder limits</th><td>{@link OpusDecoderOptions#limits()}</td></tr>
 *           <tr><th scope="row">preferred sample rate</th><td>{@link OpusDecoderOptions#sampleRate()} when it is 8, 12,
 *           16, 24 or 48 kHz, otherwise 48 kHz</td></tr>
 *           <tr><th scope="row">encoder sample rate and layout</th><td>{@link OpusEncoderOptions.Builder#format(int,
 *           com.github.auties00.smalt.audio.ChannelLayout)}</td></tr>
 *           <tr><th scope="row">encoder bitrate</th><td>{@link OpusEncoderOptions.Builder#bitrate(int)}, or
 *           {@value #DEFAULT_CHANNEL_BITRATE} bits per second per channel when the options leave it to the
 *           codec</td></tr>
 *           <tr><th scope="row">real time</th><td>{@link OpusApplication#VOIP} when set, otherwise
 *           {@link OpusApplication#AUDIO}</td></tr>
 *           <tr><th scope="row">everything else</th><td>{@link OpusEncoderOptions#defaults()}: unconstrained variable
 *           bitrate, complexity 10, 20 ms packets, automatic signal type, no bandwidth restriction</td></tr>
 *           </tbody>
 *           </table>
 */
public final class OpusCodecProvider implements AudioCodecProvider {
    /**
     * The bitrate per channel used when the options leave the bitrate to the codec.
     */
    private static final int DEFAULT_CHANNEL_BITRATE = 32_000;

    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public OpusCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.OPUS;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        var builder = OpusDecoderOptions.builder().limits(options.limits());
        var rate = options.preferredSampleRate().orElse(0);
        if (rate == 8_000 || rate == 12_000 || rate == 16_000 || rate == 24_000 || rate == 48_000) {
            builder.sampleRate(rate);
        }
        return new OpusDecoder(track, builder.build());
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.OPUS;
    }

    @Override
    public AudioEncoder createEncoder(Codec codec, AudioEncoderOptions options) {
        var channels = options.layout().channels();
        var bitrate = options.bitrate().orElse(DEFAULT_CHANNEL_BITRATE * channels);
        var application = options.realtime() ? OpusApplication.VOIP : OpusApplication.AUDIO;
        return new OpusEncoder(OpusEncoderOptions.builder()
                .format(options.sampleRate(), options.layout())
                .application(application)
                .bitrate(bitrate)
                .build());
    }
}
