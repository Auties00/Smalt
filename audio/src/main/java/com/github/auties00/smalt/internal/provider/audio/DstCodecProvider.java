package com.github.auties00.smalt.internal.provider.audio;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.dst.DstDecoder;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link DstDecoder} to {@link AudioDecoders} for DST tracks, as the DSDIFF and MP4 demuxers deliver them.
 */
public final class DstCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public DstCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.DST;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new DstDecoder(track, options);
    }
}
