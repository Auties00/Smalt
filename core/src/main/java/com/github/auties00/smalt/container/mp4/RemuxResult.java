package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.track.Track;

import java.util.List;
import java.util.Objects;

/**
 * Reports what {@link Mp4Remuxer#remux} wrote.
 *
 * @param inputFormat the container format of the input
 * @param tracks the tracks of the output, with the identifiers they have in it
 * @param packets the number of packets copied
 * @param durationMicros the presentation duration of the output in microseconds
 * @param outputSize the number of bytes written to the output
 */
public record RemuxResult(ContainerFormat inputFormat, List<Track> tracks, long packets, long durationMicros,
                          long outputSize) {
    /**
     * Validates the result and copies the track list.
     */
    public RemuxResult {
        Objects.requireNonNull(inputFormat, "inputFormat");
        tracks = List.copyOf(tracks);
    }
}
