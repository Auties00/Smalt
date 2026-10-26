package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.TrackKind;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes one stream a {@link Packager} wrote: a CMAF track, the transport stream segments of a rendition, or
 * the WebVTT segments of a subtitle rendition.
 *
 * @param name the name the stream's files start with
 * @param rendition the name of the rendition it comes from
 * @param renditionKind the kind of that rendition
 * @param format the segment format
 * @param trickPlay whether this is the key-frame stream of a DASH trick-play adaptation set
 * @param codecs the codecs it carries, video first
 * @param kinds the kinds of the tracks it carries, in the order of {@code codecs}
 * @param codecString the {@code CODECS} value of the stream, comma-separated for a muxed stream, empty for WebVTT
 * segments
 * @param segments the number of media segments
 * @param longestSegment the longest media segment, in microseconds
 * @param durationMicros the duration of the stream, in microseconds
 * @param peakBandwidth the highest bit rate of one media segment, in bits per second
 * @param averageBandwidth the bit rate of the whole stream, in bits per second
 * @param playlist the name of the stream's HLS media playlist, or empty without HLS and for the CMAF text and
 * trick-play streams, which DASH alone describes
 * @param iFramePlaylist the name of the stream's HLS I-frame playlist, or empty without trick play
 */
public record PackagedStream(String name, String rendition, Rendition.Kind renditionKind, Format format,
                             boolean trickPlay, List<Codec> codecs, List<TrackKind> kinds, String codecString,
                             int segments, long longestSegment, long durationMicros, long peakBandwidth,
                             long averageBandwidth, Optional<String> playlist, Optional<String> iFramePlaylist) {
    /**
     * Enumerates the segment formats of a stream.
     */
    public enum Format {
        /**
         * MPEG-2 transport stream segments ({@code .ts}).
         */
        TRANSPORT_STREAM,

        /**
         * A CMAF track: an initialization segment and media segments ({@code .m4s}), or one file.
         */
        CMAF,

        /**
         * WebVTT segments ({@code .vtt}) of an HLS subtitle rendition.
         */
        WEBVTT
    }

    /**
     * Validates the description and makes the lists unmodifiable.
     *
     * @throws NullPointerException if an object component or an element is {@code null}
     */
    public PackagedStream {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rendition, "rendition");
        Objects.requireNonNull(renditionKind, "renditionKind");
        Objects.requireNonNull(format, "format");
        codecs = List.copyOf(codecs);
        kinds = List.copyOf(kinds);
        Objects.requireNonNull(codecString, "codecString");
        Objects.requireNonNull(playlist, "playlist");
        Objects.requireNonNull(iFramePlaylist, "iFramePlaylist");
    }
}
