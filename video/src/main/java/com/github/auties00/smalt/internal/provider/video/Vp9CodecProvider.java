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
import com.github.auties00.smalt.video.vp9.Vp9Decoder;
import com.github.auties00.smalt.video.vp9.Vp9Encoder;
import com.github.auties00.smalt.video.vp9.Vp9EncoderOptions;
import com.github.auties00.smalt.video.vp9.Vp9EncoderSpeed;
import com.github.auties00.smalt.video.vp9.Vp9Subsampling;

/**
 * Provides {@link Vp9Decoder} to {@link VideoDecoders} for VP9 tracks and {@link Vp9Encoder} to
 * {@link VideoEncoders}.
 *
 * @implNote This implementation passes the codec-independent decoder options to the decoder
 * unchanged. It maps the codec-independent encoder options as follows:
 * <ul>
 *   <li>real-time: {@link Vp9EncoderOptions#forRealtime()}, the one-pass constant bitrate engine,
 *       with the frame rate and bitrate;</li>
 *   <li>otherwise, for a file or an on-demand stream: {@link Vp9EncoderOptions#ofBitrate(long)} at
 *       the bitrate, with the frame rate and the {@link Vp9EncoderSpeed#SPEED_4} speed, libvpx's
 *       good-quality usage at {@code cpu-used} 4; the lookahead holds frames back, which
 *       {@link VideoEncoder#flush} drains;</li>
 *   <li>a key frame interval or the absence of periodic key frames in place of the preset's;</li>
 *   <li>a thread count above one: {@link Vp9EncoderOptions#threads()}, which caps it at 64;
 *       {@code 0} and {@code 1} keep the single thread of the presets;</li>
 *   <li>a color description: {@link Vp9EncoderOptions.Builder#color}, and a bit depth: 4:2:0 at 8, 10
 *       or 12 bits, the depth rounded up ({@link Vp9EncoderOptions.Builder#pictureFormat}).</li>
 * </ul>
 */
public final class Vp9CodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public Vp9CodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.VP9;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new Vp9Decoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.VP9;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        var builder = (options.realtime() ? Vp9EncoderOptions.forRealtime().toBuilder()
                : Vp9EncoderOptions.ofBitrate(options.bitrate()).toBuilder().speed(Vp9EncoderSpeed.SPEED_4))
                .frameRate(options.frameRate())
                .bitrate(options.bitrate());
        if (!options.periodicKeyframes()) {
            builder.noPeriodicKeyframes();
        } else {
            options.keyframeInterval().ifPresent(builder::keyframeInterval);
        }
        if (options.threads() > 1) {
            builder.threads(options.threads());
        }
        options.color().ifPresent(builder::color);
        builder.pictureFormat(Vp9Subsampling.YUV420, options.bitDepth() <= 8 ? 8 : options.bitDepth() <= 10 ? 10 : 12);
        return new Vp9Encoder(options.width(), options.height(), builder.build());
    }
}
