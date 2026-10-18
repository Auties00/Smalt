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

/**
 * Provides {@link Vp9Decoder} to {@link VideoDecoders} for VP9 tracks and {@link Vp9Encoder} to
 * {@link VideoEncoders}.
 *
 * @implNote This implementation passes the codec-independent decoder options to the decoder
 * unchanged. It maps the codec-independent encoder options onto
 * {@link Vp9EncoderOptions#forRealtime()} when they are real-time and onto
 * {@link Vp9EncoderOptions#defaults()} otherwise, with their frame rate and bitrate, and a thread
 * count above one onto {@link Vp9EncoderOptions#threads()}, which caps it at 64; {@code 0} and
 * {@code 1} keep the single thread of the presets.
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
        var builder = (options.realtime() ? Vp9EncoderOptions.forRealtime() : Vp9EncoderOptions.defaults())
                .toBuilder()
                .frameRate(options.frameRate())
                .bitrate(options.bitrate());
        if (options.threads() > 1) {
            builder.threads(options.threads());
        }
        return new Vp9Encoder(options.width(), options.height(), builder.build());
    }
}
