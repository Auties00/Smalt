package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.mpeg2.Mpeg2Decoder;

/**
 * Provides {@link Mpeg2Decoder} to {@link VideoDecoders} for MPEG-1 and MPEG-2 video tracks.
 *
 * @implNote This implementation hands the codec-independent options to the decoder: the limits and the thread count
 * apply unchanged; the streams are 8-bit, so the high bit depth request changes nothing.
 */
public final class Mpeg2CodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public Mpeg2CodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.MPEG1_VIDEO || codec == Codec.MPEG2_VIDEO;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new Mpeg2Decoder(track, options);
    }
}
