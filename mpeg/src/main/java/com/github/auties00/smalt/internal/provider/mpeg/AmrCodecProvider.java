package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.amr.AmrDecoder;
import com.github.auties00.smalt.audio.amr.AmrEncoder;
import com.github.auties00.smalt.audio.amr.AmrEncoderOptions;
import com.github.auties00.smalt.audio.amr.AmrMode;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link AmrDecoder} to {@link AudioDecoders} for AMR-NB and AMR-WB tracks and
 * {@link AmrEncoder} to {@link AudioEncoders}.
 *
 * @implNote This implementation maps the codec-independent options onto the AMR options as
 * follows:
 * <table class="striped">
 * <caption>Option mapping</caption>
 * <thead><tr><th scope="col">Codec-independent option</th><th scope="col">AMR option</th></tr></thead>
 * <tbody>
 * <tr><th scope="row">decoder limits</th><td>{@link AudioDecoderOptions#limits()}, unchanged</td></tr>
 * <tr><th scope="row">preferred sample rate</th><td>ignored: AMR-NB decodes at 8 kHz, AMR-WB at 16 kHz
 * </td></tr>
 * <tr><th scope="row">encoder sample rate and layout</th><td>ignored: the encoder codes 8 kHz (AMR-NB) or
 * 16 kHz (AMR-WB) mono and reports that format through {@link AudioEncoder#sampleRate()} and
 * {@link AudioEncoder#layout()}, which callers resample and downmix to (the transcode API does)</td></tr>
 * <tr><th scope="row">encoder bitrate</th><td>the mode closest to it ({@link AmrMode#closest(Codec, int)}),
 * or the highest mode (12.2 kbit/s, 23.85 kbit/s) when the options leave it to the codec</td></tr>
 * <tr><th scope="row">real time</th><td>ignored: AMR always codes with 25 milliseconds of delay</td></tr>
 * <tr><th scope="row">everything else</th><td>discontinuous transmission off</td></tr>
 * </tbody>
 * </table>
 */
public final class AmrCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public AmrCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.AMR_NB || codec == Codec.AMR_WB;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new AmrDecoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.AMR_NB || codec == Codec.AMR_WB;
    }

    @Override
    public AudioEncoder createEncoder(Codec codec, AudioEncoderOptions options) {
        var bitrate = options.bitrate();
        var mode = bitrate.isPresent() ? AmrMode.closest(codec, bitrate.getAsInt()) : AmrMode.highest(codec);
        return new AmrEncoder(AmrEncoderOptions.of(mode));
    }
}
