package com.github.auties00.smalt.internal.provider.audio;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.musepack.MusepackDecoder;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link MusepackDecoder} to {@link AudioDecoders} for Musepack tracks of stream versions 7 and 8.
 */
public final class MusepackCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public MusepackCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.MUSEPACK7 || codec == Codec.MUSEPACK8;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new MusepackDecoder(track, options);
    }
}
