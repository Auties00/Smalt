package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.vc1.Vc1Decoder;

/**
 * Provides {@link Vc1Decoder} to {@link VideoDecoders} for VC-1 tracks.
 *
 * @implNote This implementation hands the codec-independent options to the decoder: the limits apply unchanged; the
 * streams are 8-bit and the decoder is single-threaded, so the high bit depth request and the thread count change
 * nothing.
 */
public final class Vc1CodecProvider implements VideoCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public Vc1CodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.VC1;
    }

    @Override
    public VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        return new Vc1Decoder(track, options);
    }
}
