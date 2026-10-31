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
import com.github.auties00.smalt.video.dv.DvDecoder;
import com.github.auties00.smalt.video.dv.DvEncoder;
import com.github.auties00.smalt.video.dv.DvEncoderOptions;
import com.github.auties00.smalt.video.dv.DvProfile;

/**
 * Provides {@link DvDecoder} to {@link VideoDecoders} for DV tracks and {@link DvEncoder} to {@link VideoEncoders}.
 *
 * @implNote This implementation passes the thread count of the decoder options on. It encodes the profile of the
 * requested picture size: {@link DvProfile#DV_525} for 720x480, {@link DvProfile#DV_625} for 720x576, the DVCPRO HD
 * profiles for 1280x1080, 1440x1080 and 960x720 (720/50p below 55 frames per second, 720/60p otherwise), at the
 * profile's own frame rate and size; the bitrate, the color description, the bit depth and the key frame settings
 * do not apply, since every DV frame is a key frame of fixed size in a fixed color space.
 */
public final class DvCodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public DvCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.DV;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new DvDecoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.DV;
    }

    @Override
    public VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        var width = options.width();
        var height = options.height();
        DvProfile profile;
        if (width == 720 && height == 480) {
            profile = DvProfile.DV_525;
        } else if (width == 720 && height == 576) {
            profile = DvProfile.DV_625;
        } else if (width == 1280 && height == 1080) {
            profile = DvProfile.DVCPRO_HD_1080I60;
        } else if (width == 1440 && height == 1080) {
            profile = DvProfile.DVCPRO_HD_1080I50;
        } else if (width == 960 && height == 720) {
            profile = options.frameRate().toDouble() < 55 ? DvProfile.DVCPRO_HD_720P50 : DvProfile.DVCPRO_HD_720P60;
        } else {
            throw new IllegalArgumentException("DV codes 720x480, 720x576, 1280x1080, 1440x1080 and 960x720 "
                    + "pictures, not " + width + "x" + height);
        }
        return new DvEncoder(DvEncoderOptions.of(profile).toBuilder().threads(options.threads()).build());
    }
}
