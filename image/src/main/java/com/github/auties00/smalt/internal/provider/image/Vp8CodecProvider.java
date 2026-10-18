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
 *             <li>otherwise: {@link Vp8EncoderOptions#defaults()} with the frame rate and
 *                 bitrate;</li>
 *             <li>a thread count above one: {@link Vp8EncoderOptions.Builder#threads(int)}, which
 *                 caps it at 64; {@code 0} and {@code 1} keep the single thread of the presets.</li>
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
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        var frameRate = options.frameRate();
        var builder = options.realtime()
                ? Vp8EncoderOptions.forRealtime().toBuilder().maxKeyframeBitratePercent(
                        Math.clamp(Math.round(600 * frameRate.toDouble() / 20), 100, Integer.MAX_VALUE))
                : Vp8EncoderOptions.defaults().toBuilder();
        builder.frameRate(frameRate).bitrate(options.bitrate());
        if (options.threads() > 1) {
            builder.threads(options.threads());
        }
        return new Vp8Encoder(options.width(), options.height(), builder.build());
    }
}
