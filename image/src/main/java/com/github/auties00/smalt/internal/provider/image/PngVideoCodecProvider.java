package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.png.PngVideoDecoder;
import com.github.auties00.smalt.image.png.PngVideoEncoder;
import com.github.auties00.smalt.image.png.PngVideoEncoderOptions;
import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;

/**
 * Provides {@link PngVideoDecoder} to {@link VideoDecoders} for PNG video tracks and {@link PngVideoEncoder} to
 * {@link VideoEncoders}.
 *
 * @implNote This implementation encodes every frame with the {@code PngEncoderOptions} defaults at the requested
 * frame rate; the other settings do not apply to a lossless intra-only codec that keeps the picture's own depth and
 * alpha.
 */
public final class PngVideoCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public PngVideoCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.PNG;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new PngVideoDecoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.PNG;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        return new PngVideoEncoder(options.width(), options.height(),
                PngVideoEncoderOptions.builder().frameRate(options.frameRate()).build());
    }
}
