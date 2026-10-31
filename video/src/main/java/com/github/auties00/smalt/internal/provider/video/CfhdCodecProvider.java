package com.github.auties00.smalt.internal.provider.video;

import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.cfhd.CfhdDecoder;

/**
 * Provides {@link CfhdDecoder} to {@link VideoDecoders} for CineForm tracks.
 */
public final class CfhdCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public CfhdCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.CFHD;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new CfhdDecoder(track, options);
    }
}
