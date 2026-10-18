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
import com.github.auties00.smalt.video.dnxhd.DnxhdDecoder;
import com.github.auties00.smalt.video.dnxhd.DnxhdDecoderOptions;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoder;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions;
import com.github.auties00.smalt.video.dnxhd.DnxhdProfile;

/**
 * Provides {@link DnxhdDecoder} to {@link VideoDecoders} for DNxHD tracks and {@link DnxhdEncoder}
 * to {@link VideoEncoders}.
 *
 * @implNote This implementation passes the limits, the thread count and the high bit depth choice
 *           of the codec-independent decoder options to {@link DnxhdDecoderOptions}. It encodes
 *           {@link DnxhdProfile#DNXHR_HQ} at the requested frame rate and thread count; the
 *           bitrate does not apply, since every DNxHR frame has the size its compression ID
 *           assigns.
 */
public final class DnxhdCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public DnxhdCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.DNXHD;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new DnxhdDecoder(track, DnxhdDecoderOptions.builder()
                .limits(options.limits())
                .threads(options.threads())
                .highBitDepth(options.highBitDepth())
                .build());
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.DNXHD;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        return new DnxhdEncoder(options.width(), options.height(), DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ)
                .toBuilder()
                .frameRate(options.frameRate())
                .threads(options.threads())
                .build());
    }
}
