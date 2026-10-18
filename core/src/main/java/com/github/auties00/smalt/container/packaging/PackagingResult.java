package com.github.auties00.smalt.container.packaging;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes what a {@link Packager} wrote.
 *
 * @param files the names of every file written, in the order they were completed
 * @param masterPlaylist the name of the HLS master playlist, or empty without HLS
 * @param manifest the name of the DASH manifest, or empty without DASH
 * @param durationMicros the duration of the presentation, in microseconds
 * @param streams the streams, in rendition order
 */
public record PackagingResult(List<String> files, Optional<String> masterPlaylist, Optional<String> manifest,
                              long durationMicros, List<PackagedStream> streams) {
    /**
     * Validates the description and makes the lists unmodifiable.
     *
     * @throws NullPointerException if a component or an element is {@code null}
     */
    public PackagingResult {
        files = List.copyOf(files);
        Objects.requireNonNull(masterPlaylist, "masterPlaylist");
        Objects.requireNonNull(manifest, "manifest");
        streams = List.copyOf(streams);
    }
}
