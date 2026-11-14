package com.github.auties00.smalt.track;

import java.util.Objects;
import java.util.Set;

/**
 * Holds one coded unit of an elementary stream: a video access unit, or one or more audio frames.
 *
 * <p>The payload is the whole {@link #data()} array, in the codec's canonical packet form:
 * length-prefixed NAL units for H.264 and HEVC (the prefix size comes from the track's
 * configuration record), raw frames for every other codec. Timestamps and the duration are in the
 * owning track's time base; {@link #NO_TIMESTAMP} marks an unknown timestamp and
 * {@link #UNKNOWN_DURATION} an unknown duration. The array is shared, not copied, so
 * neither producers nor consumers may modify it after the packet is created. The {@link #flags()}
 * describe the packet ({@link PacketFlag#KEY} for a random access point), and the
 * {@link #sideData()} carries out-of-band information such as discard padding, a new decoder
 * configuration or HDR10+ metadata. A demuxer that reads samples out of a sample table, such as the MP4 one,
 * numbers them with {@link #sampleIndex()}, so that a reader can tell which sample of a track a packet is
 * after a seek.
 *
 * <p>Equality is that of the record components, so two packets are equal only when they share the
 * same payload array: packets with equal but distinct payloads are not equal. Compare
 * {@link #data()} with {@link java.util.Arrays#equals(byte[], byte[])} to compare payload contents.
 *
 * <p>Instances are immutable and thread-safe, provided the payload is not modified.
 *
 * @param trackId the identifier of the owning track
 * @param data the coded payload
 * @param pts the presentation timestamp, or {@link #NO_TIMESTAMP}
 * @param dts the decoding timestamp, or {@link #NO_TIMESTAMP}
 * @param duration the presentation duration, zero or more, or {@link #UNKNOWN_DURATION}
 * @param flags the properties of the packet, unmodifiable
 * @param sideData the out-of-band information, {@link PacketSideData#NONE} when there is none
 * @param sampleIndex the zero-based index of the sample that carried the packet among the samples of its track,
 * in decoding order, or {@link #NO_SAMPLE_INDEX} when the source does not number its samples
 */
public record Packet(int trackId, byte[] data, long pts, long dts, long duration, Set<PacketFlag> flags,
                     PacketSideData sideData, long sampleIndex) {
    /**
     * The timestamp value meaning "unknown".
     */
    public static final long NO_TIMESTAMP = Long.MIN_VALUE;

    /**
     * The duration value meaning "unknown", shared by {@link Track#duration()} and
     * {@link com.github.auties00.smalt.video.VideoFrame#duration()}.
     */
    public static final long UNKNOWN_DURATION = -1;

    /**
     * The sample index value meaning "unknown".
     */
    public static final long NO_SAMPLE_INDEX = -1;

    /**
     * The flags of a key frame without other properties.
     */
    private static final Set<PacketFlag> KEY_ONLY = Set.of(PacketFlag.KEY);

    /**
     * The flags of a packet without properties.
     */
    private static final Set<PacketFlag> NO_FLAGS = Set.of();

    /**
     * Validates the packet and makes the flags unmodifiable.
     *
     * @throws NullPointerException if an element of {@code flags} is {@code null}
     * @throws IllegalArgumentException if {@code duration} is negative and not {@link #UNKNOWN_DURATION}, or
     * {@code sampleIndex} is negative and not {@link #NO_SAMPLE_INDEX}
     */
    public Packet {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(sideData, "sideData");
        flags = Set.copyOf(flags);
        if (duration < 0 && duration != UNKNOWN_DURATION) {
            throw new IllegalArgumentException("negative packet duration " + duration);
        }
        if (sampleIndex < 0 && sampleIndex != NO_SAMPLE_INDEX) {
            throw new IllegalArgumentException("negative sample index " + sampleIndex);
        }
    }

    /**
     * Creates a packet without a sample index.
     *
     * @param trackId the identifier of the owning track
     * @param data the coded payload
     * @param pts the presentation timestamp, or {@link #NO_TIMESTAMP}
     * @param dts the decoding timestamp, or {@link #NO_TIMESTAMP}
     * @param duration the presentation duration, zero or more, or {@link #UNKNOWN_DURATION}
     * @param flags the properties of the packet
     * @param sideData the out-of-band information, {@link PacketSideData#NONE} when there is none
     * @throws NullPointerException if an element of {@code flags} is {@code null}
     * @throws IllegalArgumentException if {@code duration} is negative and not {@link #UNKNOWN_DURATION}
     */
    public Packet(int trackId, byte[] data, long pts, long dts, long duration, Set<PacketFlag> flags,
                  PacketSideData sideData) {
        this(trackId, data, pts, dts, duration, flags, sideData, NO_SAMPLE_INDEX);
    }

    /**
     * Creates a packet without side data or sample index whose only possible flag is {@link PacketFlag#KEY}.
     *
     * @param trackId the identifier of the owning track
     * @param data the coded payload
     * @param pts the presentation timestamp, or {@link #NO_TIMESTAMP}
     * @param dts the decoding timestamp, or {@link #NO_TIMESTAMP}
     * @param duration the presentation duration, zero or more, or {@link #UNKNOWN_DURATION}
     * @param keyframe whether decoding can start at this packet
     * @throws IllegalArgumentException if {@code duration} is negative and not {@link #UNKNOWN_DURATION}
     */
    public Packet(int trackId, byte[] data, long pts, long dts, long duration, boolean keyframe) {
        this(trackId, data, pts, dts, duration, keyframe ? KEY_ONLY : NO_FLAGS, PacketSideData.NONE, NO_SAMPLE_INDEX);
    }

    /**
     * {@return whether decoding can start at this packet: {@link #flags()} contains
     * {@link PacketFlag#KEY}}
     */
    public boolean keyframe() {
        return flags.contains(PacketFlag.KEY);
    }

    /**
     * {@return the payload size in bytes}
     */
    public int size() {
        return data.length;
    }

    /**
     * Returns a copy of this packet owned by another track.
     *
     * @param trackId the identifier of the new owning track
     * @return the modified packet, sharing the payload
     */
    public Packet withTrackId(int trackId) {
        return new Packet(trackId, data, pts, dts, duration, flags, sideData, sampleIndex);
    }

    /**
     * Returns a copy of this packet with another payload.
     *
     * @param data the new payload
     * @return the modified packet
     */
    public Packet withData(byte[] data) {
        return new Packet(trackId, data, pts, dts, duration, flags, sideData, sampleIndex);
    }

    /**
     * Returns a copy of this packet with other timestamps and duration.
     *
     * @param pts the presentation timestamp, or {@link #NO_TIMESTAMP}
     * @param dts the decoding timestamp, or {@link #NO_TIMESTAMP}
     * @param duration the presentation duration, zero or more, or {@link #UNKNOWN_DURATION}
     * @return the modified packet, sharing the payload
     * @throws IllegalArgumentException if {@code duration} is negative and not {@link #UNKNOWN_DURATION}
     */
    public Packet withTiming(long pts, long dts, long duration) {
        return new Packet(trackId, data, pts, dts, duration, flags, sideData, sampleIndex);
    }

    /**
     * Returns a copy of this packet with other flags.
     *
     * @param flags the new flags
     * @return the modified packet, sharing the payload
     * @throws NullPointerException if an element of {@code flags} is {@code null}
     */
    public Packet withFlags(Set<PacketFlag> flags) {
        return new Packet(trackId, data, pts, dts, duration, flags, sideData, sampleIndex);
    }

    /**
     * Returns a copy of this packet with other side data.
     *
     * @param sideData the new side data
     * @return the modified packet, sharing the payload
     */
    public Packet withSideData(PacketSideData sideData) {
        return new Packet(trackId, data, pts, dts, duration, flags, sideData, sampleIndex);
    }

    /**
     * Returns a copy of this packet with another sample index.
     *
     * @param sampleIndex the zero-based index of the sample among the samples of its track, or
     * {@link #NO_SAMPLE_INDEX}
     * @return the modified packet, sharing the payload
     * @throws IllegalArgumentException if {@code sampleIndex} is negative and not {@link #NO_SAMPLE_INDEX}
     */
    public Packet withSampleIndex(long sampleIndex) {
        return new Packet(trackId, data, pts, dts, duration, flags, sideData, sampleIndex);
    }
}
