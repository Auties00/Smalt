package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.track.Track;

import java.util.Objects;

/**
 * Describes one stream of a transcode's output.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param sourceTrackId the identifier of the input track the stream comes from, or {@code -1} for
 *                      an input that has no tracks (an animated image)
 * @param track         the output track as declared to the muxer: its identifier, codec, time
 *                      base and format
 * @param copied        whether the packets were copied rather than encoded
 */
public record TranscodedStream(int sourceTrackId, Track track, boolean copied) {
    /**
     * Validates the stream.
     *
     * @throws NullPointerException if {@code track} is {@code null}
     */
    public TranscodedStream {
        Objects.requireNonNull(track, "track");
    }
}
