package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.mpeg4.Mpeg4Decoder;

/**
 * Provides {@link Mpeg4Decoder} to {@link VideoDecoders} for MPEG-4 Part 2 Visual tracks.
 *
 * @implNote This implementation hands the codec-independent options to the decoder: the limits
 *           and the thread count apply unchanged; the streams are 8-bit, so the high bit depth
 *           request changes nothing.
 */
public final class Mpeg4CodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public Mpeg4CodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.MPEG4_PART2;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new Mpeg4Decoder(track, options);
    }
}
