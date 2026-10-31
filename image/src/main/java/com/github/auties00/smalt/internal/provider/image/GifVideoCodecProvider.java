package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.gif.GifVideoDecoder;
import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;

/**
 * Provides {@link GifVideoDecoder} to {@link VideoDecoders} for GIF video tracks.
 */
public final class GifVideoCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public GifVideoCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.GIF;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new GifVideoDecoder(track, options);
    }
}
