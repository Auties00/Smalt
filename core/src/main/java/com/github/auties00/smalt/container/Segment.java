package com.github.auties00.smalt.container;

import java.util.Objects;

/**
 * Describes one segment of a segmented output, as a muxer hands it to a {@link SegmentSink}.
 *
 * <p>A fragmented MP4 output starts with one {@link Kind#INIT} segment, whose sequence number, start and
 * duration are zero and which counts as independent, followed by {@link Kind#MEDIA} segments; an MPEG-2
 * transport stream output has media segments only. Media segments are numbered from one.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param kind           whether this is the initialization segment or a media segment
 * @param sequenceNumber the number of a media segment, from one (the {@code mfhd} sequence number of a
 *                       fragmented MP4 segment); zero for the initialization segment
 * @param startMicros    the earliest presentation time of the segment on its reference track, in
 *                       microseconds; zero for the initialization segment
 * @param durationMicros the duration of the segment on its reference track, in microseconds; zero for the
 *                       initialization segment
 * @param independent    whether the segment starts with a key frame of the reference track (of every
 *                       video track, for a fragmented MP4 segment)
 * @param size           the size of the segment in bytes, which is what the muxer writes to the sink
 */
public record Segment(Kind kind, int sequenceNumber, long startMicros, long durationMicros, boolean independent,
                      long size) {
    /**
     * A kind of segment.
     */
    public enum Kind {
        /**
         * The initialization segment of a fragmented MP4 output: {@code ftyp} and {@code moov}.
         */
        INIT,

        /**
         * A media segment: {@code styp}, optional {@code sidx}, {@code moof} and {@code mdat} in a
         * fragmented MP4 output, transport packets in an MPEG-2 transport stream.
         */
        MEDIA
    }

    /**
     * Validates the description.
     *
     * @throws IllegalArgumentException if {@code sequenceNumber} or {@code size} is negative
     */
    public Segment {
        Objects.requireNonNull(kind, "kind");
        if (sequenceNumber < 0 || size < 0) {
            throw new IllegalArgumentException("invalid segment " + sequenceNumber + " of " + size + " bytes");
        }
    }
}
