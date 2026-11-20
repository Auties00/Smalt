package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.io.MediaSink;

import java.util.Objects;

/**
 * One output of a transcode that writes several files from one read of its inputs, such as the renditions of an
 * adaptive streaming ladder.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param sink the output, written from its position; not closed
 * @param options the description of the file written
 */
public record TranscodeOutput(MediaSink sink, TranscodeOptions options) {
    /**
     * Validates the output.
     *
     * @throws NullPointerException if an argument is {@code null}
     */
    public TranscodeOutput {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(options, "options");
    }
}
