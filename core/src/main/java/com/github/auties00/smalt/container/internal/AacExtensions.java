package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

/**
 * Finds the output format of AAC tracks whose container declares the rate of the AAC core
 * instead of the rate spectral band replication (HE-AAC) decodes to.
 *
 * <p>Containers describe an HE-AAC track inconsistently: an MP4 sample entry or a Matroska
 * {@code SamplingFrequency} often carries the core rate, the {@code AudioSpecificConfig} may
 * signal the extensions explicitly or leave them to the raw data blocks (implicit signaling),
 * and a mono core may decode to stereo through parametric stereo. A demuxer passes such a track
 * and its first packet to {@link #refine(AudioTrack, byte[])}, which decodes the packet with the
 * registered AAC decoder, as players do, and declares the rate and layout the decoder outputs.
 * Only tracks that can carry the extensions are decoded: those whose configuration signals them
 * explicitly (object types 5 and 29), and Low Complexity tracks at a core rate of at most 24 kHz,
 * where implicit HE-AAC is found. The time base, and with it every timestamp, stays as the
 * container declares it.
 */
public final class AacExtensions {
    /**
     * The highest core rate at which an implicitly signaled extension is looked for; above it,
     * HE-AAC would output more than 48 kHz and is not found in practice.
     */
    public static final int MAX_IMPLICIT_CORE_RATE = 24000;

    /**
     * The object type of AAC Low Complexity.
     */
    private static final int AOT_LC = 2;

    /**
     * The object type of spectral band replication.
     */
    private static final int AOT_SBR = 5;

    /**
     * The object type of parametric stereo.
     */
    private static final int AOT_PS = 29;

    /**
     * The sampling rates of the sampling frequency indices of ISO/IEC 14496-3.
     */
    private static final int[] SAMPLE_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000,
            11025, 8000, 7350};

    /**
     * The format the AAC decoder outputs.
     *
     * @param sampleRate the output rate
     * @param layout the output layout
     */
    public record Format(int sampleRate, ChannelLayout layout) {
    }
    private AacExtensions() {
        throw new AssertionError();
    }

    /**
     * Returns whether a track may decode to another rate or layout than it declares.
     *
     * <p>That is an AAC track with an {@code AudioSpecificConfig} that signals spectral band
     * replication explicitly, or a Low Complexity one at a core rate of at most
     * {@link #MAX_IMPLICIT_CORE_RATE}, that declares no rate or the core rate.
     *
     * @param track a track as the container declares it
     * @return {@code true} when {@link #refine(AudioTrack, byte[])} should look at its first packet
     */
    public static boolean mayRefine(AudioTrack track) {
        if (track.codec() != Codec.AAC || track.codecConfig().isEmpty()) {
            return false;
        }
        var config = track.codecConfig().get();
        if (config.length < 2) {
            return false;
        }
        try {
            var reader = new MsbBitReader(config);
            var objectType = reader.read(5);
            if (objectType == 31) {
                objectType = 32 + reader.read(6);
            }
            var index = reader.read(4);
            var coreRate = index == 15 ? reader.read(24) : index < SAMPLE_RATES.length ? SAMPLE_RATES[index] : 0;
            if (coreRate <= 0) {
                return false;
            }
            var declared = track.sampleRate();
            if (declared != 0 && declared != coreRate) {
                return false;
            }
            return objectType == AOT_SBR || objectType == AOT_PS
                    || (objectType == AOT_LC && coreRate <= MAX_IMPLICIT_CORE_RATE);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Decodes a packet of a track and returns the format of the first buffer the decoder outputs.
     *
     * <p>The packet is decoded with a copy of the track that declares no rate, so that the
     * decoder decides the output rate from the configuration and the packet alone.
     *
     * @param track the AAC track
     * @param packet the payload of its first packet
     * @return the format, or {@code null} when no AAC decoder is registered or the packet does not decode
     */
    public static Format decodeFormat(AudioTrack track, byte[] packet) {
        if (!AudioDecoders.isSupported(Codec.AAC)) {
            return null;
        }
        var probe = track.toBuilder().sampleRate(0).skipSamples(0).build();
        var format = new Format[1];
        try (var decoder = AudioDecoders.create(probe, AudioDecoderOptions.defaults())) {
            decoder.decode(new Packet(probe.id(), packet, 0, 0, Packet.UNKNOWN_DURATION, true), buffer -> {
                if (format[0] == null) {
                    format[0] = new Format(buffer.sampleRate(), buffer.layout());
                }
            });
        } catch (RuntimeException exception) {
            return null;
        }
        return format[0];
    }

    /**
     * Returns a track that declares the format its first packet decodes to.
     *
     * <p>When {@link #mayRefine(AudioTrack)} holds and the packet decodes to another rate or
     * layout than the track declares, the returned copy declares the decoded ones, with
     * {@link AudioTrack#skipSamples()} rescaled from the declared rate to the decoded one;
     * otherwise {@code track} itself is returned.
     *
     * @param track a track as the container declares it
     * @param packet the payload of its first packet, or {@code null} when it cannot be read
     * @return the refined track, or {@code track}
     */
    public static AudioTrack refine(AudioTrack track, byte[] packet) {
        if (packet == null || !mayRefine(track)) {
            return track;
        }
        var format = decodeFormat(track, packet);
        if (format == null || format.sampleRate() <= 0) {
            return track;
        }
        var declared = track.sampleRate();
        var layout = track.layout().orElse(null);
        if (format.sampleRate() == declared && format.layout().equals(layout)) {
            return track;
        }
        var skip = declared > 0 ? track.skipSamples() * format.sampleRate() / declared : track.skipSamples();
        return track.toBuilder()
                .sampleRate(format.sampleRate())
                .layout(format.layout())
                .skipSamples(skip)
                .build();
    }
}
