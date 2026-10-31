package com.github.auties00.smalt.internal.provider.audio;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.adpcm.AdpcmDecoder;
import com.github.auties00.smalt.audio.adpcm.AdpcmEncoder;
import com.github.auties00.smalt.audio.adpcm.AdpcmEncoderOptions;
import com.github.auties00.smalt.audio.g722.G722Decoder;
import com.github.auties00.smalt.audio.g722.G722Encoder;
import com.github.auties00.smalt.audio.g726.G726Decoder;
import com.github.auties00.smalt.audio.g726.G726Encoder;
import com.github.auties00.smalt.audio.g726.G726EncoderOptions;
import com.github.auties00.smalt.audio.gsm.GsmDecoder;
import com.github.auties00.smalt.audio.gsm.GsmEncoder;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides the ADPCM and telephony codecs to {@link AudioDecoders} and {@link AudioEncoders}: IMA ADPCM in its
 * WAVE and {@code ima4} layouts and Microsoft ADPCM ({@link AdpcmDecoder}, {@link AdpcmEncoder}), G.722
 * ({@link G722Decoder}, {@link G722Encoder}), G.726 ({@link G726Decoder}, {@link G726Encoder}) and GSM 06.10 in
 * both layouts ({@link GsmDecoder}, {@link GsmEncoder}).
 *
 * @implNote This implementation maps the codec-independent options onto the codecs' own as follows:
 * <table class="striped">
 * <caption>Option mapping</caption>
 * <thead><tr><th scope="col">Codec-independent option</th><th scope="col">Mapping</th></tr></thead>
 * <tbody>
 * <tr><th scope="row">decoder limits</th><td>passed on unchanged</td></tr>
 * <tr><th scope="row">preferred sample rate</th><td>ignored: the decoders output the stream's own rate</td></tr>
 * <tr><th scope="row">encoder sample rate and layout</th><td>{@link AdpcmEncoderOptions.Builder#format(int,
 * ChannelLayout)}, {@link G726EncoderOptions.Builder#sampleRate(int)} and the GSM 06.10 encoder's rate; G.722,
 * G.726 and GSM 06.10 accept only mono, and G.722 only 16 kHz</td></tr>
 * <tr><th scope="row">encoder bitrate</th><td>G.726: the code size nearest to the bitrate at the sampling rate,
 * 4 bits without one; ignored by the other codecs, whose bitrate the format fixes</td></tr>
 * <tr><th scope="row">real time</th><td>ignored: every encoder is real time</td></tr>
 * </tbody>
 * </table>
 */
public final class AdpcmCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public AdpcmCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return switch (codec) {
            case ADPCM_IMA_WAV, ADPCM_IMA_QT, ADPCM_MS, ADPCM_G722, ADPCM_G726, GSM, GSM_MS -> true;
            default -> false;
        };
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return switch (track.codec()) {
            case ADPCM_G722 -> new G722Decoder(track, options);
            case ADPCM_G726 -> new G726Decoder(track, options);
            case GSM, GSM_MS -> new GsmDecoder(track, options);
            default -> new AdpcmDecoder(track, options);
        };
    }

    @Override
    public boolean encodes(Codec codec) {
        return decodes(codec);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the codec takes neither the layout nor the sampling rate
     */
    @Override
    public AudioEncoder createEncoder(Codec codec, AudioEncoderOptions options) {
        var channels = options.layout().channels();
        if (codec != Codec.ADPCM_IMA_WAV && codec != Codec.ADPCM_IMA_QT && codec != Codec.ADPCM_MS && channels != 1) {
            throw new IllegalArgumentException(codec.displayName() + " encodes mono only, not " + channels
                    + " channels");
        }
        return switch (codec) {
            case ADPCM_G722 -> {
                if (options.sampleRate() != 16_000) {
                    throw new IllegalArgumentException("G.722 encodes 16 kHz only, not " + options.sampleRate()
                            + " Hz");
                }
                yield new G722Encoder();
            }
            case ADPCM_G726 -> {
                var codeSize = 4;
                if (options.bitrate().isPresent()) {
                    var bits = (double) options.bitrate().getAsInt() / options.sampleRate();
                    codeSize = (int) Math.max(2, Math.min(5, Math.round(bits)));
                }
                yield new G726Encoder(G726EncoderOptions.builder()
                        .sampleRate(options.sampleRate())
                        .codeSize(codeSize)
                        .build());
            }
            case GSM, GSM_MS -> new GsmEncoder(codec, options.sampleRate());
            default -> new AdpcmEncoder(AdpcmEncoderOptions.of(codec, options.sampleRate(), options.layout()));
        };
    }
}
