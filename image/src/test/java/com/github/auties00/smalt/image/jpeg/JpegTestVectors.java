package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.testkit.Conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pins the libjpeg-turbo and IJG libjpeg 6b test images used by the JPEG tests.
 *
 * <p>The libjpeg-turbo images come from tag 3.1.2 (testorig12.jpg from 2.1.5.1, the last tag that
 * ships it); the IJG images come from the jpeg-6b tag of the libjpeg-turbo repository, where
 * {@code testimg.ppm} is {@code djpeg -dct int} of {@code testorig.jpg} and also of the progressive
 * {@code testprog.jpg}, and {@code testimg.jpg} is {@code cjpeg -dct int} of {@code testimg.ppm}.
 */
final class JpegTestVectors {
    private JpegTestVectors() {
        throw new AssertionError();
    }

    static byte[] testorig() {
        return vector("ljt-testorig.jpg");
    }

    static byte[] testimgint() {
        return vector("ljt-testimgint.jpg");
    }

    static byte[] testimgari() {
        return vector("ljt-testimgari.jpg");
    }

    static byte[] testorigPpm() {
        return vector("ljt-testorig.ppm");
    }

    static byte[] testorig12() {
        return vector("ljt-testorig12.jpg");
    }

    static byte[] test1Icc() {
        return vector("ljt-test1.icc");
    }

    static byte[] testScan() {
        return vector("ljt-test.scan");
    }

    static byte[] testorigPgm() {
        return vector("ljt-testorig.pgm");
    }

    static byte[] monkey16Ppm() {
        return vector("ljt-monkey16.ppm");
    }

    static byte[] monkey16Pgm() {
        return vector("ljt-monkey16.pgm");
    }

    static byte[] ijgTestimgPpm() {
        return vector("ijg6b-testimg.ppm");
    }

    static byte[] ijgTestimgJpg() {
        return vector("ijg6b-testimg.jpg");
    }

    static byte[] ijgTestprog() {
        return vector("ijg6b-testprog.jpg");
    }

    static byte[] ijgTestimgp() {
        return vector("ijg6b-testimgp.jpg");
    }

    static byte[] vector(String name) {
        Path path = Conformance.vector(name);
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
