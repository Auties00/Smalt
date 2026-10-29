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
import com.github.auties00.smalt.video.mpeg4.Mpeg4Decoder;
import com.github.auties00.smalt.video.mpeg4.Mpeg4Encoder;
import com.github.auties00.smalt.video.mpeg4.Mpeg4EncoderOptions;

/**
 * Provides {@link Mpeg4Decoder} to {@link VideoDecoders} for MPEG-4 Part 2 Visual tracks and {@link Mpeg4Encoder}
 * to {@link VideoEncoders}.
 *
 * @implNote This implementation hands the codec-independent options to the decoder: the limits
 *           and the thread count apply unchanged; the streams are 8-bit, so the high bit depth
 *           request changes nothing. It maps the codec-independent encoder options as follows:
 *           <ul>
 *             <li>real-time: {@link Mpeg4EncoderOptions#forRealtime()} with the frame rate and bitrate;</li>
 *             <li>otherwise: {@link Mpeg4EncoderOptions#forFile()} with the frame rate and bitrate, an I-VOP
 *                 every two seconds of the frame rate, and the time base {@code 1/frameRate}, so a frame's
 *                 timestamp is its index;</li>
 *             <li>a key frame interval and no periodic key frames in place of the preset's;</li>
 *             <li>the thread count and the color description unchanged;</li>
 *             <li>a bit depth above 8 is coded at 8 bits, the only depth of the two profiles.</li>
 *           </ul>
 */
public final class Mpeg4CodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public Mpeg4CodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.MPEG4_PART2;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new Mpeg4Decoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.MPEG4_PART2;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        var frameRate = options.frameRate().reduce();
        var builder = options.realtime() ? Mpeg4EncoderOptions.forRealtime().toBuilder()
                : Mpeg4EncoderOptions.forFile().toBuilder().timeBase(frameRate.invert()).keyframeInterval((int)
                        Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(2 * frameRate.toDouble()))));
        builder.frameRate(frameRate).bitrate(options.bitrate()).threads(options.threads());
        if (!options.periodicKeyframes()) {
            builder.noPeriodicKeyframes();
        } else {
            options.keyframeInterval().ifPresent(builder::keyframeInterval);
        }
        options.color().ifPresent(builder::color);
        return new Mpeg4Encoder(options.width(), options.height(), builder.build());
    }
}
