package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.testkit.Conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The HEIF conformance files of Nokia (github.com/nokiatech/heif_conformance, directory
 * {@code conformance_files}) and the HEIC samples of pillow_heif (github.com/bigcat88/pillow_heif,
 * BSD-3-Clause: iPhone, Xiaomi and Canon photos and libheif-written test images), read from the hosted test data.
 * Vector names are {@code heic-nokia-} and {@code heic-pillow-} followed by the path with slashes replaced by
 * dashes.
 */
final class HeicVectors {
    private HeicVectors() {
    }

    static Path nokia(String name) {
        return Conformance.vector("heic-nokia-" + name);
    }

    static Path pillow(String path) {
        return Conformance.vector("heic-pillow-" + path.replace('/', '-'));
    }

    static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
