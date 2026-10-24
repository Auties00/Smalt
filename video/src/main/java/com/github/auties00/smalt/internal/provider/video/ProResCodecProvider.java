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
import com.github.auties00.smalt.video.prores.ProResDecoder;
import com.github.auties00.smalt.video.prores.ProResDecoderOptions;
import com.github.auties00.smalt.video.prores.ProResEncoder;
import com.github.auties00.smalt.video.prores.ProResEncoderOptions;
import com.github.auties00.smalt.video.prores.ProResProfile;

/**
 * Provides {@link ProResDecoder} to {@link VideoDecoders} for ProRes tracks and
 * {@link ProResEncoder} to {@link VideoEncoders}.
 *
 * @implNote This implementation passes the limits, the thread count and the high bit depth choice
 *           of the codec-independent decoder options to {@link ProResDecoderOptions}, so the
 *           registry decodes to 8-bit pictures unless asked otherwise. It encodes
 *           {@link ProResProfile#HQ} at the requested frame rate, bitrate (when positive) and
 *           thread count, with the color description when one is given (ProRes codes at least 10
 *           bits whatever the bit depth); real-time tuning and the key frame settings do not apply to an intra-only
 *           codec, whose every frame is a key frame.
 */
public final class ProResCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public ProResCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.PRORES;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new ProResDecoder(track, ProResDecoderOptions.builder()
                .limits(options.limits())
                .threads(options.threads())
                .highBitDepth(options.highBitDepth())
                .build());
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.PRORES;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        var builder = ProResEncoderOptions.of(ProResProfile.HQ).toBuilder()
                .frameRate(options.frameRate())
                .threads(options.threads());
        if (options.bitrate() > 0) {
            builder.bitrate(options.bitrate());
        }
        options.color().ifPresent(builder::color);
        return new ProResEncoder(options.width(), options.height(), builder.build());
    }
}
