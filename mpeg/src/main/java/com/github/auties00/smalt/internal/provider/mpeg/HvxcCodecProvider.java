package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.hvxc.HvxcDecoder;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link HvxcDecoder} to {@link AudioDecoders} for MPEG-4 HVXC tracks.
 *
 * @implNote This implementation maps the codec-independent options onto the HVXC options as follows:
 * <table class="striped">
 * <caption>Option mapping</caption>
 * <thead><tr><th scope="col">Codec-independent option</th><th scope="col">HVXC option</th></tr></thead>
 * <tbody>
 * <tr><th scope="row">decoder limits</th><td>{@link AudioDecoderOptions#limits()}, unchanged</td></tr>
 * <tr><th scope="row">preferred sample rate</th><td>ignored: the decoder outputs 8 kHz</td></tr>
 * <tr><th scope="row">everything else</th><td>every layer the track holds at the original speed and pitch, in the
 * normal delay mode, with the postfilter</td></tr>
 * </tbody>
 * </table>
 * A track carries one elementary stream, so an object whose enhancement layer sits in other tracks decodes its base
 * layer, and a base layer with {@code epConfig} 1, whose error sensitivity categories sit in other tracks, is rejected
 * as malformed; {@link HvxcDecoder#HvxcDecoder(java.util.List,
 * com.github.auties00.smalt.audio.hvxc.HvxcDecoderOptions)} decodes them together.
 */
public final class HvxcCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public HvxcCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.HVXC;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new HvxcDecoder(track, options);
    }
}
