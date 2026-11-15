package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.internal.codec.SampleAspects;
import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
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
 *                 and a bit depth above 8 is coded at 8 bits;</li>
 *             <li>a sample aspect ratio other than 1:1: {@link H263EncoderOptions.Builder#sampleAspectRatio},
 *                 in lowest terms, or the closest ratio of 8-bit terms when the terms do not fit the extended
 *                 PAR; square samples, like no ratio, keep the ratio the picture format implies.</li>
 *           </ul>
 *           The baseline syntax is kept for the standard source formats up to 30 frames per second, unless the
 *           sample aspect ratio is neither square nor the 12:11 they imply; any other size the custom formats
 *           code, and a faster frame rate, gets the extended picture type. Square samples are not signaled in a
 *           custom format of a standard size because many H.263 decoders, those of the 3GPP baseline profile among
 *           them, read only the baseline picture header, and a transcode hands on the square samples many
 *           sources declare.
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
        options.sampleAspectRatio().map(Rational::reduce).filter(sar -> sar.numerator() != sar.denominator())
                .ifPresent(sar -> builder.sampleAspectRatio(SampleAspects.fit(sar, 255)));
        return new H263Encoder(options.width(), options.height(), builder.build());
    }
}
