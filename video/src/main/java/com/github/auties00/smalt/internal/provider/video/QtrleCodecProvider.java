package com.github.auties00.smalt.internal.provider.video;

import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.qtrle.QtrleDecoder;
import com.github.auties00.smalt.video.qtrle.QtrleEncoder;
import com.github.auties00.smalt.video.qtrle.QtrleEncoderOptions;

/**
 * Provides {@link QtrleDecoder} to {@link VideoDecoders} for QuickTime Animation tracks and {@link QtrleEncoder} to
 * {@link VideoEncoders}.
 *
 * @implNote This implementation encodes 32-bit ARGB, which keeps the pictures' alpha in the stream itself, at the
 * requested frame rate, with a key frame at the requested interval when periodic key frames are requested and every
 * 12 frames otherwise; the bitrate, bit depth, color description, real-time tuning and thread count do not apply to a
 * lossless single-threaded 8-bit codec, and alpha as a second stream is not offered.
 */
public final class QtrleCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public QtrleCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.QTRLE;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new QtrleDecoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.QTRLE;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        var builder = QtrleEncoderOptions.builder()
                .depth(32)
                .frameRate(options.frameRate());
        if (options.periodicKeyframes()) {
            options.keyframeInterval().ifPresent(builder::keyframeInterval);
        }
        return new QtrleEncoder(options.width(), options.height(), builder.build());
    }
}
