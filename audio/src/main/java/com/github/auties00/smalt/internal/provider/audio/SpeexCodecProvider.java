package com.github.auties00.smalt.internal.provider.audio;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.speex.SpeexDecoder;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link SpeexDecoder} to {@link AudioDecoders} for Speex tracks, as the Ogg demuxer delivers them, and for
 * headerless (FLV) Speex tracks.
 */
public final class SpeexCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public SpeexCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.SPEEX;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new SpeexDecoder(track, options);
    }
}
