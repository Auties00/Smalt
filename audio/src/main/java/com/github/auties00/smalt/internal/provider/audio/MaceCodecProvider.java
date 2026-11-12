package com.github.auties00.smalt.internal.provider.audio;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.mace.MaceDecoder;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides the MACE decoder to {@link AudioDecoders}: {@link Codec#MACE3} and {@link Codec#MACE6}
 * ({@link MaceDecoder}). The decoder takes the options' limits and ignores their preferred sample rate.
 */
public final class MaceCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public MaceCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.MACE3 || codec == Codec.MACE6;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new MaceDecoder(track, options);
    }
}
