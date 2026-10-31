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
import com.github.auties00.smalt.video.h263.H263Decoder;
import com.github.auties00.smalt.video.h263.H263Encoder;
import com.github.auties00.smalt.video.h263.H263EncoderOptions;

/**
 * Provides {@link H263Decoder} to {@link VideoDecoders} for H.263 and Sorenson Spark tracks and {@link H263Encoder} to
 * {@link VideoEncoders}.
 *
 * @implNote This implementation hands the codec-independent options to the decoder: the limits
 *           and the thread count apply unchanged; the streams are 8-bit, so the high bit depth
 *           request changes nothing. It maps the codec-independent encoder options as follows:
 *           <ul>
 *             <li>real-time: {@link H263EncoderOptions#forRealtime()} with the frame rate and bitrate;</li>
 *             <li>otherwise: {@link H263EncoderOptions#defaults()} with the frame rate and bitrate, an
 *                 I-picture every two seconds of the frame rate, and the time base {@code 1/frameRate}, so a
 *                 frame's timestamp is its index;</li>
 *             <li>a key frame interval and no periodic key frames in place of the preset's;</li>
 *             <li>the thread count unchanged; the color description is ignored, since H.263 signals none,
 *                 and a bit depth above 8 is coded at 8 bits.</li>
 *           </ul>
 *           The baseline syntax is kept for the standard source formats up to 30 frames per second; any
 *           other size the custom formats code, and a faster frame rate, gets the extended picture type.
 */
public final class H263CodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public H263CodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.H263 || codec == Codec.FLV1;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new H263Decoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.H263;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        var frameRate = options.frameRate().reduce();
        var builder = options.realtime() ? H263EncoderOptions.forRealtime().toBuilder()
                : H263EncoderOptions.defaults().toBuilder().timeBase(frameRate.invert()).keyframeInterval((int)
                        Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(2 * frameRate.toDouble()))));
        builder.frameRate(frameRate).bitrate(options.bitrate()).threads(options.threads());
        if (!options.periodicKeyframes()) {
            builder.noPeriodicKeyframes();
        } else {
            options.keyframeInterval().ifPresent(builder::keyframeInterval);
        }
        return new H263Encoder(options.width(), options.height(), builder.build());
    }
}
