package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.vp8.Vp8Decoder;
import com.github.auties00.smalt.video.vp8.Vp8Encoder;
import com.github.auties00.smalt.video.vp8.Vp8EncoderOptions;
import com.github.auties00.smalt.video.vp8.Vp8EncoderSpeed;

/**
 * Provides {@link Vp8Decoder} to {@link VideoDecoders} for VP8 tracks and {@link Vp8Encoder} to
 * {@link VideoEncoders}.
 *
 * @implNote This implementation passes the codec-independent decoder options to
 *           {@link Vp8Decoder#Vp8Decoder(VideoTrack, VideoDecoderOptions)}. It maps the
 *           codec-independent encoder options as follows:
 *           <ul>
 *             <li>real-time: {@link Vp8EncoderOptions#forRealtime()} with the frame rate, the
 *                 bitrate and WebRTC's key frame target limit for the frame rate,
 *                 {@code 600 * frameRate / 20} percent;</li>
 *             <li>otherwise, for a file or an on-demand stream:
 *                 {@link Vp8EncoderOptions#ofBitrate(long)} at the bitrate, with the frame rate and the
 *                 {@link Vp8EncoderSpeed#SPEED_4} speed, libvpx's good-quality usage at
 *                 {@code cpu-used} 4; the lookahead holds frames back, which
 *                 {@link VideoEncoder#flush} drains;</li>
 *             <li>a key frame interval: {@link Vp8EncoderOptions.Builder#keyframeInterval(int)}, and
 *                 no periodic key frames: {@link Vp8EncoderOptions.Builder#noPeriodicKeyframes()},
 *                 in place of the preset's;</li>
 *             <li>a thread count above one: {@link Vp8EncoderOptions.Builder#threads(int)}, which
 *                 caps it at 64; {@code 0} and {@code 1} keep the single thread of the presets;</li>
 *             <li>alpha: {@link Vp8EncoderOptions.Builder#alpha(boolean)}, at the default alpha
 *                 quantizer.</li>
 *           </ul>
 */
public final class Vp8CodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public Vp8CodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.VP8;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new Vp8Decoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.VP8;
    }

    @Override
    public boolean encodesAlpha(Codec codec) {
        return codec == Codec.VP8;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        var frameRate = options.frameRate();
        var builder = options.realtime()
                ? Vp8EncoderOptions.forRealtime().toBuilder().maxKeyframeBitratePercent(
                        Math.clamp(Math.round(600 * frameRate.toDouble() / 20), 100, Integer.MAX_VALUE))
                : Vp8EncoderOptions.ofBitrate(options.bitrate()).toBuilder().speed(Vp8EncoderSpeed.SPEED_4);
        builder.frameRate(frameRate).bitrate(options.bitrate());
        if (!options.periodicKeyframes()) {
            builder.noPeriodicKeyframes();
        } else {
            options.keyframeInterval().ifPresent(builder::keyframeInterval);
        }
        if (options.threads() > 1) {
            builder.threads(options.threads());
        }
        builder.alpha(options.alpha());
        return new Vp8Encoder(options.width(), options.height(), builder.build());
    }
}
