package com.github.auties00.smalt.internal.provider.audio;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.mp3.Mp2Encoder;
import com.github.auties00.smalt.audio.mp3.Mp2EncoderOptions;
import com.github.auties00.smalt.audio.mp3.Mp3BitrateMode;
import com.github.auties00.smalt.audio.mp3.Mp3Decoder;
import com.github.auties00.smalt.audio.mp3.Mp3Encoder;
import com.github.auties00.smalt.audio.mp3.Mp3EncoderOptions;
import com.github.auties00.smalt.audio.mp3.Mp3StereoMode;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link Mp3Decoder} to {@link AudioDecoders} for MP3 and MP2 tracks, and
 * {@link Mp3Encoder} and {@link Mp2Encoder} to {@link AudioEncoders} for MP3 and MP2 (Layer II).
 *
 * @implNote This implementation maps the codec-independent options onto the MP3 options as
 *           follows:
 *           <table class="striped">
 *           <caption>Option mapping</caption>
 *           <thead><tr><th scope="col">Codec-independent option</th><th scope="col">MP3 option</th></tr></thead>
 *           <tbody>
 *           <tr><th scope="row">decoder limits</th><td>{@link AudioDecoderOptions#limits()}, passed on
 *           unchanged</td></tr>
 *           <tr><th scope="row">preferred sample rate</th><td>ignored: the decoder outputs the stream's own
 *           rate</td></tr>
 *           <tr><th scope="row">encoder sample rate</th><td>the nearest of the nine Layer III rates (8 to
 *           48 kHz); the encoder reports it, so the caller resamples when it differs</td></tr>
 *           <tr><th scope="row">encoder layout</th><td>mono or a two-channel layout; more channels are
 *           rejected</td></tr>
 *           <tr><th scope="row">encoder bitrate</th><td>constant bitrate at the nearest bitrate of the
 *           rate's MPEG version; without one, 64 kbit/s per channel at 32 kHz and above, 32 kbit/s per
 *           channel at 16 to 24 kHz and 16 kbit/s per channel below</td></tr>
 *           <tr><th scope="row">real time</th><td>{@link Mp3EncoderOptions#forRealtime()}: no bit
 *           reservoir, so every frame decodes on its own</td></tr>
 *           <tr><th scope="row">MP2</th><td>Layer II at the nearest of the six Layer II rates (16 to
 *           48 kHz) and the nearest bitrate of the rate's MPEG version allowed for the channel count, or
 *           without one the {@link Mp2EncoderOptions#effectiveBitrate() automatic} bitrate; two channels at
 *           128 kbit/s or less in joint stereo, otherwise stereo; every frame decodes on its own, so real
 *           time changes nothing</td></tr>
 *           <tr><th scope="row">lossless sample size</th><td>ignored</td></tr>
 *           </tbody>
 *           </table>
 */
public final class Mp3CodecProvider implements AudioCodecProvider {
    /**
     * The Layer III sampling rates, in ascending order.
     */
    private static final int[] RATES = {8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000};

    /**
     * The Layer II sampling rates, in ascending order.
     */
    private static final int[] MP2_RATES = {16000, 22050, 24000, 32000, 44100, 48000};

    /**
     * The MPEG-1 Layer III bitrates in kilobits per second.
     */
    private static final int[] MPEG1_BITRATES = {32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};

    /**
     * The MPEG-2 and MPEG-2.5 Layer III bitrates in kilobits per second.
     */
    private static final int[] MPEG2_BITRATES = {8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};

    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public Mp3CodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.MP3 || codec == Codec.MP2;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new Mp3Decoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.MP3 || codec == Codec.MP2;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the layout has more than two channels
     */
    @Override
    public AudioEncoder createEncoder(Codec codec, AudioEncoderOptions options) {
        var channels = options.layout().channels();
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException(codec.displayName() + " codes mono or stereo, not " + channels
                    + " channels");
        }
        if (codec == Codec.MP2) {
            return createMp2Encoder(options);
        }
        var rate = nearestRate(options.sampleRate());
        var table = rate >= 32000 ? MPEG1_BITRATES : MPEG2_BITRATES;
        var requested = options.bitrate().isPresent() ? options.bitrate().getAsInt() / 1000.0
                : channels * (rate >= 32000 ? 64 : rate >= 16000 ? 32 : 16);
        var kbps = table[0];
        for (var candidate : table) {
            if (Math.abs(candidate - requested) < Math.abs(kbps - requested)) {
                kbps = candidate;
            }
        }
        return new Mp3Encoder(Mp3EncoderOptions.builder()
                .format(rate, options.layout())
                .bitrateMode(Mp3BitrateMode.CBR)
                .bitrate(kbps * 1000)
                .bitReservoir(!options.realtime())
                .build());
    }

    /**
     * Creates a Layer II encoder for codec-independent options.
     *
     * @param options the codec-independent options
     * @return the encoder
     */
    private static AudioEncoder createMp2Encoder(AudioEncoderOptions options) {
        var rate = MP2_RATES[0];
        for (var candidate : MP2_RATES) {
            if (Math.abs(candidate - options.sampleRate()) < Math.abs(rate - options.sampleRate())) {
                rate = candidate;
            }
        }
        var builder = Mp2EncoderOptions.builder().format(rate, options.layout());
        if (options.bitrate().isPresent()) {
            var requested = options.bitrate().getAsInt();
            var best = 0;
            for (var kbps : rate >= 32000 ? new int[]{32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384}
                    : new int[]{8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}) {
                var allowed = rate < 32000 || (options.layout().channels() == 1 ? kbps < 224
                        : kbps != 32 && kbps != 48 && kbps != 56 && kbps != 80);
                if (allowed && (best == 0 || Math.abs(kbps * 1000 - requested) < Math.abs(best * 1000 - requested))) {
                    best = kbps;
                }
            }
            builder.bitrate(best * 1000);
        }
        var built = builder.build();
        if (options.layout().channels() == 2 && built.effectiveBitrate() <= 128_000) {
            built = built.toBuilder().stereoMode(Mp3StereoMode.JOINT_STEREO).build();
        }
        return new Mp2Encoder(built);
    }

    /**
     * Returns the Layer III sampling rate nearest a requested one.
     *
     * @param rate the requested rate in hertz
     * @return the rate, from 8000 to 48000
     */
    private static int nearestRate(int rate) {
        var best = RATES[0];
        for (var candidate : RATES) {
            if (Math.abs(candidate - rate) < Math.abs(best - rate)) {
                best = candidate;
            }
        }
        return best;
    }
}
