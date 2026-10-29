package com.github.auties00.smalt.internal.provider;

import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.RawPixelFormat;
import com.github.auties00.smalt.video.RawVideoDecoder;
import com.github.auties00.smalt.video.RawVideoEncoder;
import com.github.auties00.smalt.video.RawVideoFormat;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;

/**
 * Provides {@link RawVideoDecoder} to {@link VideoDecoders} and {@link RawVideoEncoder} to {@link VideoEncoders} for
 * {@link Codec#RAW_VIDEO}.
 */
public final class RawVideoCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public RawVideoCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.RAW_VIDEO;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new RawVideoDecoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.RAW_VIDEO;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The encoder writes 8-bit {@link RawPixelFormat#YUV420P} frames in BT.601 limited range; the bitrate,
     * tuning, thread count and key frame interval of the options are ignored, since every frame is a key frame of
     * a fixed size.
     */
    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        return new RawVideoEncoder(RawVideoFormat.of(RawPixelFormat.YUV420P), options.width(), options.height(),
                options.frameRate(), null);
    }
}
