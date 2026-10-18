package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.h263.H263Decoder;

/**
 * Provides {@link H263Decoder} to {@link VideoDecoders} for H.263 tracks.
 *
 * @implNote This implementation hands the codec-independent options to the decoder: the limits
 *           and the thread count apply unchanged; the streams are 8-bit, so the high bit depth
 *           request changes nothing.
 */
public final class H263CodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public H263CodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.H263;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new H263Decoder(track, options);
    }
}
