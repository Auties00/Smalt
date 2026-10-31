package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.hap.HapDecoder;
import com.github.auties00.smalt.image.hap.HapEncoder;
import com.github.auties00.smalt.image.hap.HapEncoderOptions;
import com.github.auties00.smalt.image.hap.HapFormat;
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
 * Provides {@link HapDecoder} to {@link VideoDecoders} for HAP tracks and {@link HapEncoder} to {@link VideoEncoders}.
 *
 * @implNote This implementation encodes HAP (DXT1), Snappy-compressed in one chunk at the requested frame rate; the
 * bitrate, bit depth, color description, key frame settings, real-time tuning and thread count do not apply to a
 * fixed-rate intra-only texture codec coded on the shared pool, and alpha as a second stream is not offered (the
 * formats with alpha are reached through {@code HapEncoder} directly).
 */
public final class HapCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public HapCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.HAP;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new HapDecoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.HAP;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        return new HapEncoder(options.width(), options.height(), HapEncoderOptions.builder()
                .format(HapFormat.HAP)
                .frameRate(options.frameRate())
                .build());
    }
}
