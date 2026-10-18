package com.github.auties00.smalt.internal.provider;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.pcm.PcmDecoder;
import com.github.auties00.smalt.audio.pcm.PcmEncoder;
import com.github.auties00.smalt.audio.pcm.PcmEncoderOptions;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link PcmDecoder} to {@link AudioDecoders} and {@link PcmEncoder} to
 * {@link AudioEncoders} for every uncompressed PCM codec.
 */
public final class PcmCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public PcmCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec.isPcm();
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new PcmDecoder(track);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec.isPcm();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The bitrate and real-time tuning of the options are ignored: the bitrate of PCM follows
     * from its sample format, rate and channel count.
     */
    @Override
    public AudioEncoder createEncoder(Codec codec, AudioEncoderOptions options) {
        return new PcmEncoder(PcmEncoderOptions.of(codec, options.sampleRate(), options.layout()));
    }
}
