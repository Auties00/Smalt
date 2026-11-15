package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.image.jxs.JpegXsVideoDecoder;
import com.github.auties00.smalt.image.jxs.JpegXsVideoEncoder;
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
 * Provides {@link JpegXsVideoDecoder} to {@link VideoDecoders} for JPEG XS tracks and {@link JpegXsVideoEncoder} to
 * {@link VideoEncoders}.
 */
public final class JpegXsVideoCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public JpegXsVideoCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.JPEG_XS;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new JpegXsVideoDecoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.JPEG_XS;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        return new JpegXsVideoEncoder(options);
    }
}
