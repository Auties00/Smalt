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
    private static final String TURBO = "https://raw.githubusercontent.com/libjpeg-turbo/libjpeg-turbo/3.1.2/testimages/";
    private static final String TURBO_OLD = "https://raw.githubusercontent.com/libjpeg-turbo/libjpeg-turbo/2.1.5.1/testimages/";
    private static final String IJG = "https://raw.githubusercontent.com/libjpeg-turbo/libjpeg-turbo/jpeg-6b/";

    private JpegTestVectors() {
        throw new AssertionError();
    }

    static byte[] testorig() {
        return fetch("ljt-testorig.jpg", TURBO + "testorig.jpg", "acc6ec555d41d15b368320edaa3b20958ee6fa97cb6e4a18d1213d5ae8bec73b");
    }

    static byte[] testimgint() {
        return fetch("ljt-testimgint.jpg", TURBO + "testimgint.jpg", "491679b8057739b3c8e5bacd1e918efb1691d271cbbd69820ff8d480dcb90963");
    }

    static byte[] testimgari() {
        return fetch("ljt-testimgari.jpg", TURBO + "testimgari.jpg", "4672c7f08864cd0a8c73a4fa4b66ca32b635d38464551c1ecf06564ae8c89b38");
    }

    static byte[] testorigPpm() {
        return fetch("ljt-testorig.ppm", TURBO + "testorig.ppm", "4afe49cb62ba87be1a958d7fd29b822a2ba1a0e966d1136f616ee5353691a002");
    }

    static byte[] testorig12() {
        return fetch("ljt-testorig12.jpg", TURBO_OLD + "testorig12.jpg", "34790770f76db8e60a7765b52ca4edf5f16bc21bcb8c6045ca2efef39a8a013e");
    }

    static byte[] ijgTestimgPpm() {
        return fetch("ijg6b-testimg.ppm", IJG + "testimg.ppm", "b64a47d529e289e5570648db9f0385d9b240448724cdde7716fa0139b121d491");
    }

    static byte[] ijgTestimgJpg() {
        return fetch("ijg6b-testimg.jpg", IJG + "testimg.jpg", "ff6133bb58d3dae4f13ecbe05256dc4aaa05b17786b2c67f172cc3e934a00331");
    }

    static byte[] ijgTestprog() {
        return fetch("ijg6b-testprog.jpg", IJG + "testprog.jpg", "24770f706b81b40a71944af3b39aad8a3f7ffb21c4f2725447022e518222d823");
    }

    static byte[] ijgTestimgp() {
        return fetch("ijg6b-testimgp.jpg", IJG + "testimgp.jpg", "e9486e697b7082a324fe2812310303e13b0ddf67b073eacb4b9d8a53e50b7ea7");
    }

    static byte[] fetch(String name, String url, String sha256) {
        Path path = Conformance.fetch(name, url, sha256);
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
