package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.vvc.VvcDecoder;
import com.github.auties00.smalt.video.vvc.VvcDecoderOptions;

/**
 * Provides {@link VvcDecoder} to {@link VideoDecoders} for VVC tracks.
 *
 * @implNote This implementation maps the codec-independent decoder options onto {@link VvcDecoderOptions}: the
 *           limits, the thread count and the high bit depth request carry over unchanged.
 */
public final class VvcCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public VvcCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.VVC;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        var vvcOptions = VvcDecoderOptions.builder()
                .limits(options.limits())
                .threads(options.threads())
                .highBitDepth(options.highBitDepth())
                .build();
        return new VvcDecoder(track, vvcOptions);
    }
}
