package com.github.auties00.smalt.internal.provider.video;

import com.github.auties00.smalt.internal.codec.SampleAspects;
import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.theora.TheoraDecoder;
import com.github.auties00.smalt.video.theora.TheoraEncoder;
import com.github.auties00.smalt.video.theora.TheoraEncoderOptions;

/**
 * Provides {@link TheoraDecoder} to {@link VideoDecoders} for Theora tracks and {@link TheoraEncoder} to
 * {@link VideoEncoders}.
 *
 * @implNote This implementation passes the decoder options on unchanged. It encodes 4:2:0 at the
 *           {@link TheoraEncoderOptions#defaults()} quality, with the requested frame rate, color description,
 *           sample aspect ratio (in lowest terms, or the closest ratio of 24-bit terms) and key frame interval
 *           ({@link TheoraEncoderOptions#DEFAULT_KEYFRAME_INTERVAL} when none is requested, and key frames only at
 *           the start, on request and at scene cuts when periodic key frames are off); the bitrate and the
 *           real-time request do not apply, every frame being coded at one quality.
 */
public final class TheoraCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public TheoraCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.THEORA;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new TheoraDecoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.THEORA;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        var builder = TheoraEncoderOptions.builder().frameRate(options.frameRate());
        options.color().ifPresent(builder::color);
        options.sampleAspectRatio().ifPresent(sar -> builder.sampleAspectRatio(SampleAspects.fit(sar, 0xFFFFFF)));
        if (!options.periodicKeyframes()) {
            builder.noPeriodicKeyframes();
        } else {
            options.keyframeInterval().ifPresent(builder::keyframeInterval);
        }
        return new TheoraEncoder(options.width(), options.height(), builder.build());
    }
}
