package com.github.auties00.smalt.internal.provider.audio;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.wavpack.WavPackDecoder;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link WavPackDecoder} to {@link AudioDecoders} for WavPack tracks, as the WavPack and Matroska demuxers
 * deliver them.
 */
public final class WavPackCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public WavPackCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.WAVPACK;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new WavPackDecoder(track, options);
    }
}
