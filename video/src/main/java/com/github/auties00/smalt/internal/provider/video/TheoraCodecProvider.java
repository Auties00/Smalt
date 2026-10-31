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
import com.github.auties00.smalt.video.theora.TheoraDecoder;
import com.github.auties00.smalt.video.theora.TheoraEncoder;
import com.github.auties00.smalt.video.theora.TheoraEncoderOptions;

/**
 * Provides {@link TheoraDecoder} to {@link VideoDecoders} for Theora tracks and {@link TheoraEncoder} to
 * {@link VideoEncoders}.
 *
 * @implNote This implementation passes the decoder options on unchanged. It encodes 4:2:0 intra frames at the
 *           {@link TheoraEncoderOptions#defaults()} quality, with the requested frame rate and color description;
 *           the bitrate, the key frame interval and the real-time request do not apply, every frame being a key
 *           frame of one quality.
 */
public final class TheoraCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public TheoraCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.THEORA;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new TheoraDecoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.THEORA;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        var builder = TheoraEncoderOptions.builder().frameRate(options.frameRate());
        options.color().ifPresent(builder::color);
        return new TheoraEncoder(options.width(), options.height(), builder.build());
    }
}
