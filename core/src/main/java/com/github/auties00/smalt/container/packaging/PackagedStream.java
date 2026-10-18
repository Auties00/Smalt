package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.TrackKind;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes one stream a {@link Packager} wrote: a CMAF track, or the muxed audio and video of a
 * rendition in transport stream segments.
 *
 * @param name the name the stream's files start with
 * @param rendition the name of the rendition it comes from
 * @param codecs the codecs it carries, video first
 * @param kinds the kinds of the tracks it carries, in the order of {@code codecs}
 * @param codecString the {@code CODECS} value of the stream, comma-separated for a muxed stream
 * @param segments the number of media segments
 * @param longestSegment the longest media segment, in microseconds
 * @param durationMicros the duration of the stream, in microseconds
 * @param peakBandwidth the highest bit rate of one media segment, in bits per second
 * @param averageBandwidth the bit rate of the whole stream, in bits per second
 * @param playlist the name of the stream's HLS media playlist, or empty without HLS
 */
public record PackagedStream(String name, String rendition, List<Codec> codecs, List<TrackKind> kinds,
                             String codecString, int segments, long longestSegment, long durationMicros,
                             long peakBandwidth, long averageBandwidth, Optional<String> playlist) {
    /**
     * Validates the description and makes the lists unmodifiable.
     *
     * @throws NullPointerException if an object component or an element is {@code null}
     */
    public PackagedStream {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rendition, "rendition");
        codecs = List.copyOf(codecs);
        kinds = List.copyOf(kinds);
        Objects.requireNonNull(codecString, "codecString");
        Objects.requireNonNull(playlist, "playlist");
    }
}
