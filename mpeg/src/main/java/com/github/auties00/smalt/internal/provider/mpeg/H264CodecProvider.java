package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import com.github.auties00.smalt.video.h264.encoder.H264Encoder;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;


/**
 * Provides {@link H264Decoder} to {@link VideoDecoders} for H.264 tracks and {@link H264Encoder}
 * to {@link VideoEncoders}.
 *
 * @implNote This implementation decodes up to {@link VideoDecoderOptions#threads()} pictures at
 *           once when the thread count is above one, and one picture at a time otherwise, and
 *           passes {@link VideoDecoderOptions#highBitDepth()} on. It maps the codec-independent
 *           encoder options as follows:
 *           <ul>
 *             <li>real-time: {@link H264EncoderOptions#forRealtime()} with the frame rate and
 *                 bitrate;</li>
 *             <li>otherwise: {@link H264EncoderOptions#forFile()} with the frame rate and
 *                 bitrate, an IDR picture every two seconds of the frame rate, and the time base
 *                 {@code 1/frameRate}, so a frame's timestamp is its index;</li>
 *             <li>a key frame interval: {@link H264EncoderOptions.Builder#keyframeInterval(int)}, and
 *                 no periodic key frames: {@link H264EncoderOptions.Builder#noPeriodicKeyframes()},
 *                 in place of the preset's;</li>
 *             <li>a thread count above one: {@link H264EncoderOptions.Builder#threads(int)} capped at
 *                 64; {@code 0} and {@code 1} keep the single thread of the presets;</li>
 *             <li>a color description: {@link H264EncoderOptions.Builder#color};</li>
 *             <li>a bit depth above 8: {@link H264EncoderOptions.Builder#bitDepth(int)}, with the High 10
 *                 profile up to 10 bits and the High 4:4:4 Predictive profile up to 14 bits, the
 *                 deeper depths coded at 14 bits.</li>
 *           </ul>
 */
public final class H264CodecProvider implements VideoCodecProvider {
    /**
     * The most threads the encoder options accept.
     */
    private static final int MAX_ENCODER_THREADS = 64;

    /**
     * The deepest samples the encoder codes, in bits.
     */
    private static final int MAX_BIT_DEPTH = 14;

    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public H264CodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.H264;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new H264Decoder(track, H264DecoderOptions.builder()
                .limits(options.limits())
                .threads(options.threads())
                .highBitDepth(options.highBitDepth())
                .build());
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.H264;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        var frameRate = options.frameRate();
        var builder = options.realtime()
                ? H264EncoderOptions.forRealtime().toBuilder()
                : H264EncoderOptions.forFile().toBuilder()
                        .timeBase(frameRate.invert())
                        .keyframeInterval((int) Math.max(1,
                                Math.min(Integer.MAX_VALUE, Math.round(2 * frameRate.toDouble()))));
        builder.frameRate(frameRate).bitrate(options.bitrate());
        if (!options.periodicKeyframes()) {
            builder.noPeriodicKeyframes();
        } else {
            options.keyframeInterval().ifPresent(builder::keyframeInterval);
        }
        if (options.threads() > 1) {
            builder.threads(Math.min(MAX_ENCODER_THREADS, options.threads()));
        }
        options.color().ifPresent(builder::color);
        if (options.bitDepth() > 8) {
            var depth = Math.min(MAX_BIT_DEPTH, options.bitDepth());
            builder.profile(depth <= 10 ? H264Profile.HIGH_10 : H264Profile.HIGH_444_PREDICTIVE).bitDepth(depth);
        }
        return new H264Encoder(options.width(), options.height(), builder.build());
    }
}
