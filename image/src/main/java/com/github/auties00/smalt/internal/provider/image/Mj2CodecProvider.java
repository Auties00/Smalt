package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jp2.Mj2Decoder;
import com.github.auties00.smalt.image.jp2.Mj2Encoder;
import com.github.auties00.smalt.image.jp2.Mj2EncoderOptions;
import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;

/**
 * Provides {@link Mj2Decoder} to {@link VideoDecoders} for Motion JPEG 2000 tracks and {@link Mj2Encoder} to
 * {@link VideoEncoders}.
 *
 * @implNote This implementation encodes the {@link Mj2EncoderOptions#defaults()} layout (4:2:0 Y'CbCr, every frame a
 * JP2 file) at the requested bit depth with the irreversible wavelet at the requested frame rate, each frame capped at
 * its share of the requested bitrate; real-time tuning, the key frame settings and the thread count do not apply to an
 * intra-only codec coded on the shared pool, and the color description does not apply to a stream that declares its
 * Y'CbCr samples sYCC.
 */
public final class Mj2CodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public Mj2CodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.JPEG2000;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new Mj2Decoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.JPEG2000;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        return new Mj2Encoder(options.width(), options.height(), Mj2EncoderOptions.defaults().toBuilder()
                .coding(Jp2EncoderOptions.defaults().toBuilder().reversible(false).build())
                .bitDepth(options.bitDepth())
                .frameRate(options.frameRate())
                .bitrate(options.bitrate())
                .build());
    }
}
