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
import com.github.auties00.smalt.video.ffv1.Ffv1Decoder;
import com.github.auties00.smalt.video.ffv1.Ffv1Encoder;
import com.github.auties00.smalt.video.ffv1.Ffv1EncoderOptions;

/**
 * Provides {@link Ffv1Decoder} to {@link VideoDecoders} for FFV1 tracks and {@link Ffv1Encoder} to
 * {@link VideoEncoders}.
 *
 * @implNote This implementation passes the decoder options on unchanged. It encodes version 3 4:2:0 Y'CbCr at
 *           the requested depth, frame rate and thread count, with {@link Ffv1EncoderOptions#forSpeed()} for
 *           real-time tuning and {@link Ffv1EncoderOptions#defaults()} otherwise; the requested color description
 *           converts RGB pictures, a key frame interval applies when periodic key frames are requested, and the
 *           bitrate does not apply, since FFV1 is lossless.
 */
public final class Ffv1CodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public Ffv1CodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.FFV1;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new Ffv1Decoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.FFV1;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        var builder = (options.realtime() ? Ffv1EncoderOptions.forSpeed() : Ffv1EncoderOptions.defaults()).toBuilder()
                .bitDepth(options.bitDepth())
                .frameRate(options.frameRate())
                .threads(options.threads());
        options.color().ifPresent(builder::color);
        if (options.periodicKeyframes()) {
            options.keyframeInterval().ifPresent(builder::keyframeInterval);
        }
        return new Ffv1Encoder(options.width(), options.height(), builder.build());
    }
}
