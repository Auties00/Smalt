package com.github.auties00.smalt.internal.provider.audio;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.atrac9.Atrac9Decoder;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link Atrac9Decoder} to {@link AudioDecoders} for ATRAC9 tracks.
 */
public final class Atrac9CodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public Atrac9CodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.ATRAC9;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new Atrac9Decoder(track, options);
    }
}
