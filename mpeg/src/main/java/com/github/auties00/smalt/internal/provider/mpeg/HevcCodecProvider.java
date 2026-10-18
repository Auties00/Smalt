package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.hevc.HevcDecoder;
import com.github.auties00.smalt.video.hevc.HevcDecoderOptions;

/**
 * Provides {@link HevcDecoder} to {@link VideoDecoders} for HEVC tracks.
 *
 * @implNote This implementation maps the codec-independent options onto
 *           {@link HevcDecoderOptions}: the limits, the thread count and the high bit depth
 *           request carry over unchanged.
 */
public final class HevcCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public HevcCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.HEVC;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        var hevcOptions = HevcDecoderOptions.builder()
                .limits(options.limits())
                .threads(options.threads())
                .highBitDepth(options.highBitDepth())
                .build();
        return new HevcDecoder(track, hevcOptions);
    }
}
