package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.testkit.Conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The AOM AVIF conformance files (github.com/AOMediaCodec/av1-avif, directory {@code testFiles}), read from the
 * hosted test data. Vector names are {@code avif-} followed by the path with slashes replaced by dashes.
 */
final class AvifVectors {
    private AvifVectors() {
    }

    static Path path(String path) {
        return Conformance.vector("avif-" + path.replace('/', '-'));
    }

    static byte[] read(String path) {
        try {
            return Files.readAllBytes(path(path));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
