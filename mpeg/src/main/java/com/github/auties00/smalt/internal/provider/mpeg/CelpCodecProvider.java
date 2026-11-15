package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.celp.CelpDecoder;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link CelpDecoder} to {@link AudioDecoders} for MPEG-4 CELP tracks.
 *
 * @implNote This implementation maps the codec-independent options onto the CELP options as follows:
 * <table class="striped">
 * <caption>Option mapping</caption>
 * <thead><tr><th scope="col">Codec-independent option</th><th scope="col">CELP option</th></tr></thead>
 * <tbody>
 * <tr><th scope="row">decoder limits</th><td>{@link AudioDecoderOptions#limits()}, unchanged</td></tr>
 * <tr><th scope="row">preferred sample rate</th><td>ignored: the decoder outputs the rate of the stream, 8 or
 * 16 kHz</td></tr>
 * <tr><th scope="row">everything else</th><td>every layer the track holds, with the post-processor</td></tr>
 * </tbody>
 * </table>
 * A track carries one elementary stream, so an object whose enhancement layers sit in other tracks decodes its base
 * layer, and a base layer with {@code epConfig} 1, whose error sensitivity categories sit in other tracks, is rejected
 * as malformed; {@link CelpDecoder#CelpDecoder(java.util.List,
 * com.github.auties00.smalt.audio.celp.CelpDecoderOptions)} decodes them together.
 */
public final class CelpCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public CelpCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.CELP;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new CelpDecoder(track, options);
    }
}
