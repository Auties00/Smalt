package com.github.auties00.smalt.internal.provider;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.UncompressedVideoDecoder;
import com.github.auties00.smalt.video.UncompressedVideoEncoder;
import com.github.auties00.smalt.video.UncompressedVideoFormat;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;

/**
 * Provides {@link UncompressedVideoDecoder} to {@link VideoDecoders} and {@link UncompressedVideoEncoder} to
 * {@link VideoEncoders} for {@link Codec#UNCOMPRESSED_23001_17}.
 */
public final class UncompressedVideoCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public UncompressedVideoCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.UNCOMPRESSED_23001_17;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new UncompressedVideoDecoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.UNCOMPRESSED_23001_17;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The encoder writes component-interleaved Y'CbCr 4:2:0 frames at the options' bit depth, in the options'
     * color description or BT.601 limited range; the bitrate, tuning, thread count and key frame interval of the
     * options are ignored, since every frame is a key frame of a fixed size.
     */
    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        return new UncompressedVideoEncoder(UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, options.bitDepth()),
                options.width(), options.height(), options.frameRate(), options.color().orElse(null));
    }
}
