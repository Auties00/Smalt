package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.twinvq.TwinVqDecoder;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link TwinVqDecoder} to {@link AudioDecoders} for MPEG-4 TwinVQ tracks.
 *
 * @implNote This implementation maps the codec-independent options onto the TwinVQ options as follows:
 * <table class="striped">
 * <caption>Option mapping</caption>
 * <thead><tr><th scope="col">Codec-independent option</th><th scope="col">TwinVQ option</th></tr></thead>
 * <tbody>
 * <tr><th scope="row">decoder limits</th><td>{@link AudioDecoderOptions#limits()}, unchanged</td></tr>
 * <tr><th scope="row">preferred sample rate</th><td>ignored: the decoder outputs the rate of the stream</td></tr>
 * <tr><th scope="row">everything else</th><td>every layer the track holds</td></tr>
 * </tbody>
 * </table>
 * A track carries one elementary stream, so an object whose scalable layers sit in other tracks decodes its base
 * layer, and a base layer with {@code epConfig} 1, whose second error sensitivity category sits in another track, is
 * rejected as malformed; {@link TwinVqDecoder#TwinVqDecoder(java.util.List,
 * com.github.auties00.smalt.audio.twinvq.TwinVqDecoderOptions)} decodes them together.
 */
public final class TwinVqCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public TwinVqCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.TWINVQ;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new TwinVqDecoder(track, options);
    }
}
