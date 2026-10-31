package com.github.auties00.smalt.track;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Describes one elementary stream of a container.
 *
 * <p>Timestamps of the stream's {@link Packet}s are expressed in {@link #timeBase()} units. The
 * {@link #codecConfig()} carries the out-of-band decoder configuration the container stores for
 * the codec, in the codec's canonical binary form:
 * <ul>
 *   <li>H.264: an {@code AVCDecoderConfigurationRecord} ({@code avcC})</li>
 *   <li>HEVC: an {@code HEVCDecoderConfigurationRecord} ({@code hvcC})</li>
 *   <li>VVC: a {@code VvcDecoderConfigurationRecord}, the {@code vvcC} box body without its full box header</li>
 *   <li>VP9 and AV1: the {@code vpcC} and {@code av1C} record bodies</li>
 *   <li>AAC: an {@code AudioSpecificConfig}</li>
 *   <li>Opus: an {@code OpusHead} packet</li>
 *   <li>Vorbis: the three header packets, Xiph-laced as in Matroska {@code CodecPrivate}</li>
 *   <li>FLAC: the {@code fLaC} marker followed by the metadata blocks</li>
 *   <li>ALAC: the {@code ALACSpecificConfig}</li>
 *   <li>ProRes and DNxHD: the four-character QuickTime sample entry type in ASCII (for example
 *       {@code apch} or {@code AVdh}), which names the ProRes flavor</li>
 * </ul>
 * and is empty when the codec needs none or the container stores the configuration in band.
 *
 * <p>Implementations in this package are immutable and thread-safe.
 */
public sealed interface Track permits VideoTrack, AudioTrack, DataTrack {
    /**
     * The value of {@link #duration()} when the duration is unknown, the same value as
     * {@link Packet#UNKNOWN_DURATION}.
     */
    long UNKNOWN_DURATION = Packet.UNKNOWN_DURATION;

    /**
     * Returns the identifier the track's packets carry in {@link Packet#trackId()}.
     *
     * @return the track identifier, unique within its container
     */
    int id();

    /**
     * Returns the coding format of the stream.
     *
     * @return the codec
     */
    Codec codec();

    /**
     * Returns the unit of the stream's timestamps and durations.
     *
     * @return the time base
     */
    Rational timeBase();

    /**
     * Returns the stream duration.
     *
     * @return the duration in {@link #timeBase()} units, or {@link #UNKNOWN_DURATION} when unknown
     */
    long duration();

    /**
     * Returns the stream duration in microseconds, rounded to nearest.
     *
     * @implSpec The default implementation converts {@link #duration()} with
     * {@link Rational#toMicros(long) timeBase().toMicros}, and returns empty when the duration is
     * {@link #UNKNOWN_DURATION} or the conversion overflows.
     *
     * @return the duration, or empty when it is unknown
     */
    default OptionalLong durationMicros() {
        var duration = duration();
        if (duration == UNKNOWN_DURATION) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(timeBase().toMicros(duration));
        } catch (ArithmeticException exception) {
            return OptionalLong.empty();
        }
    }

    /**
     * Returns the out-of-band decoder configuration.
     *
     * @return the configuration bytes, or empty when there is none
     */
    Optional<byte[]> codecConfig();

    /**
     * Returns the ISO 639-2 language code declared by the container.
     *
     * @return the language, or empty when undeclared
     */
    Optional<String> language();

    /**
     * Returns the kind of stream.
     *
     * @return the kind implied by {@link #codec()}
     */
    default TrackKind kind() {
        return codec().kind();
    }
}
