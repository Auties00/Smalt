package com.github.auties00.smalt.transcode;

import java.util.List;
import java.util.Objects;

/**
 * Describes what a transcode wrote.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param container      the file written
 * @param durationMicros the presentation duration of the output in microseconds
 * @param streams        the output streams, in output track order
 * @param remuxed        whether every stream was copied
 */
public record TranscodeResult(OutputContainer container, long durationMicros, List<TranscodedStream> streams,
                              boolean remuxed) {
    /**
     * Validates the result and copies the stream list.
     *
     * @throws NullPointerException if an argument is {@code null}
     */
    public TranscodeResult {
        Objects.requireNonNull(container, "container");
        streams = List.copyOf(streams);
    }
}
