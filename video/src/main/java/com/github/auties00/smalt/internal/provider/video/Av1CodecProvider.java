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
import com.github.auties00.smalt.video.av1.Av1Decoder;
import com.github.auties00.smalt.video.av1.Av1DecoderOptions;
import com.github.auties00.smalt.video.av1.Av1Encoder;
import com.github.auties00.smalt.video.av1.Av1EncoderOptions;
import com.github.auties00.smalt.video.av1.Av1EncoderSpeed;

/**
 * Provides {@link Av1Decoder} to {@link VideoDecoders} for AV1 tracks and {@link Av1Encoder} to
 * {@link VideoEncoders}.
 *
 * @implNote This implementation passes the limits, the thread count and the high bit depth choice
 *           of the codec-independent decoder options to {@link Av1DecoderOptions}. It maps the
 *           codec-independent encoder options as follows:
 *           <ul>
 *             <li>real-time: {@link Av1EncoderOptions#forRealtime()}, the one-pass low-delay
 *                 engine, with the frame rate and bitrate;</li>
 *             <li>otherwise, for a file or an on-demand stream:
 *                 {@link Av1EncoderOptions#ofBitrate(long)} at the bitrate, with the frame rate and
 *                 the {@link Av1EncoderSpeed#FAST} speed, libaom's good-quality usage at
 *                 {@code cpu-used} 6. The lookahead holds frames back, which
 *                 {@link VideoEncoder#flush} drains; on 720p video this spends about 60% fewer
 *                 bits than the real-time engine for the same PSNR and SSIM, at about nine times
 *                 its encoding time, and the slower good-quality speeds save a further 1 to 2% for
 *                 a third more time;</li>
 *             <li>a thread count above one: {@link Av1EncoderOptions#threads()}, which caps it
 *                 at 64; {@code 0} and {@code 1} keep the single thread of the presets.</li>
 *           </ul>
 */
public final class Av1CodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public Av1CodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.AV1;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new Av1Decoder(track, Av1DecoderOptions.builder()
                .limits(options.limits())
                .threads(options.threads())
                .highBitDepth(options.highBitDepth())
                .build());
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.AV1;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        var builder = (options.realtime() ? Av1EncoderOptions.forRealtime().toBuilder()
                : Av1EncoderOptions.ofBitrate(options.bitrate()).toBuilder().speed(Av1EncoderSpeed.FAST))
                .frameRate(options.frameRate())
                .bitrate(options.bitrate());
        if (options.threads() > 1) {
            builder.threads(options.threads());
        }
        return new Av1Encoder(options.width(), options.height(), builder.build());
    }
}
