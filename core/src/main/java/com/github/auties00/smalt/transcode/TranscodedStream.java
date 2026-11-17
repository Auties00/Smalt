package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.track.Track;

import java.util.Objects;

/**
 * Describes one stream of a transcode's output.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param sourceInput the index of the input the stream comes from, in the list the transcode was given; {@code 0}
 * for a transcode of one input
 * @param sourceTrackId the identifier of the input track the stream comes from, as the input's demuxer (the first
 * file's, for a concatenation) numbers it, or {@code -1} for an input that has no tracks (an animated image)
 * @param track the output track as declared to the muxer: its identifier, codec, time base and format
 * @param copied whether the packets were copied rather than encoded
 */
public record TranscodedStream(int sourceInput, int sourceTrackId, Track track, boolean copied) {
    /**
     * Validates the stream.
     *
     * @throws NullPointerException if {@code track} is {@code null}
     * @throws IllegalArgumentException if {@code sourceInput} is negative
     */
    public TranscodedStream {
        Objects.requireNonNull(track, "track");
        if (sourceInput < 0) {
            throw new IllegalArgumentException("input index " + sourceInput + " is negative");
        }
    }

    /**
     * Constructs a stream that comes from the first input.
     *
     * @param sourceTrackId the identifier of the input track, or {@code -1} for an input without tracks
     * @param track the output track
     * @param copied whether the packets were copied rather than encoded
     */
    public TranscodedStream(int sourceTrackId, Track track, boolean copied) {
        this(0, sourceTrackId, track, copied);
    }
}
