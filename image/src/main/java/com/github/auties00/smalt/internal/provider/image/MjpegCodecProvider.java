package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.jpeg.MjpegDecoder;
import com.github.auties00.smalt.image.jpeg.MjpegEncoder;
import com.github.auties00.smalt.image.jpeg.MjpegEncoderOptions;
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
 * Provides {@link MjpegDecoder} to {@link VideoDecoders} for Motion-JPEG tracks and
 * {@link MjpegEncoder} to {@link VideoEncoders}.
 *
 * @implNote This implementation encodes the {@link MjpegEncoderOptions#defaults()} at the
 * requested frame rate, bitrate (through the encoder's rate control) and thread count; real-time
 * tuning and the key frame settings do not apply to an intra-only codec, whose every frame is a
 * key frame.
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

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.MJPEG;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        return new MjpegEncoder(options.width(), options.height(), MjpegEncoderOptions.defaults().toBuilder()
                .frameRate(options.frameRate())
                .bitrate(options.bitrate())
                .threads(options.threads())
                .build());
    }
}
