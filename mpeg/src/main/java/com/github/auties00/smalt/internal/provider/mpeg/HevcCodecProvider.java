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
import com.github.auties00.smalt.video.hevc.HevcDecoder;
import com.github.auties00.smalt.video.hevc.HevcDecoderOptions;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoder;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoderOptions;
import com.github.auties00.smalt.video.hevc.encoder.HevcProfile;

/**
 * Provides {@link HevcDecoder} to {@link VideoDecoders} for HEVC tracks and {@link HevcEncoder} to
 * {@link VideoEncoders}.
 *
 * @implNote This implementation maps the codec-independent decoder options onto
 *           {@link HevcDecoderOptions}: the limits, the thread count and the high bit depth request carry
 *           over unchanged. It maps the codec-independent encoder options as follows:
 *           <ul>
 *             <li>real-time: {@link HevcEncoderOptions#forRealtime()} with the frame rate and bitrate;</li>
 *             <li>otherwise: {@link HevcEncoderOptions#forFile()} with the frame rate and bitrate, an IDR
 *                 picture every two seconds of the frame rate, and the time base {@code 1/frameRate}, so a
 *                 frame's timestamp is its index;</li>
 *             <li>a key frame interval: {@link HevcEncoderOptions.Builder#keyframeInterval(int)}, and no
 *                 periodic key frames: {@link HevcEncoderOptions.Builder#noPeriodicKeyframes()}, in place of the
 *                 preset's;</li>
 *             <li>a thread count above one: {@link HevcEncoderOptions.Builder#threads(int)} capped at 64;
 *                 {@code 0} and {@code 1} keep the single thread of the presets;</li>
 *             <li>a color description: {@link HevcEncoderOptions.Builder#color};</li>
 *             <li>a bit depth above 8: the {@link HevcProfile#MAIN_10} profile at 10 bits.</li>
 *           </ul>
 */
public final class HevcCodecProvider implements VideoCodecProvider {
    /**
     * The most threads the encoder options accept.
     */
    private static final int MAX_ENCODER_THREADS = 64;

    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public HevcCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.HEVC;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        var hevcOptions = HevcDecoderOptions.builder()
                .limits(options.limits())
                .threads(options.threads())
                .highBitDepth(options.highBitDepth())
                .dolbyVisionBaseLayer(options.dolbyVisionBaseLayer())
                .build();
        return new HevcDecoder(track, hevcOptions);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.HEVC;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        var frameRate = options.frameRate();
        var builder = options.realtime()
                ? HevcEncoderOptions.forRealtime().toBuilder()
                : HevcEncoderOptions.forFile().toBuilder()
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
            builder.profile(HevcProfile.MAIN_10).bitDepth(10);
        }
        return new HevcEncoder(options.width(), options.height(), builder.build());
    }
}
