package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.tiff.TiffVideoDecoder;
import com.github.auties00.smalt.image.tiff.TiffVideoEncoder;
import com.github.auties00.smalt.image.tiff.TiffVideoEncoderOptions;
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
 * Provides {@link TiffVideoDecoder} to {@link VideoDecoders} for TIFF video tracks and {@link TiffVideoEncoder} to
 * {@link VideoEncoders}.
 *
 * @implNote This implementation encodes every frame with the {@code TiffEncoderOptions} defaults at the requested
 * frame rate; the other settings do not apply to a lossless intra-only codec that keeps the picture's own depth and
 * alpha.
 */
public final class TiffVideoCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public TiffVideoCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.TIFF;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new TiffVideoDecoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.TIFF;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        return new TiffVideoEncoder(options.width(), options.height(),
                TiffVideoEncoderOptions.builder().frameRate(options.frameRate()).build());
    }
}
