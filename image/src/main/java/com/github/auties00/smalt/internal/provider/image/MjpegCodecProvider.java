package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.jpeg.MjpegDecoder;
import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;

/**
 * Provides {@link MjpegDecoder} to {@link VideoDecoders} for Motion-JPEG tracks.
 */
public final class MjpegCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public MjpegCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.MJPEG;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new MjpegDecoder(track, options);
    }
}
