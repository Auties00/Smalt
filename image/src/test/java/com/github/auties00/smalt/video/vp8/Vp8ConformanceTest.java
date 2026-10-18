package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes every libvpx VP8 test vector (vp80-00 to vp80-06) and compares the MD5 of each shown
 * frame's visible I420 planes with libvpx's {@code .ivf.md5} file, exactly as libvpx's
 * {@code test_vector_test.cc} does.
 */
@DisplayName("VP8 conformance (libvpx test vectors)")
class Vp8ConformanceTest {
    static Stream<String> vectors() {
        return Vp8Vectors.SHA256.keySet().stream().filter(name -> name.endsWith(".ivf")).sorted();
    }

    static VideoTrack track(int width, int height) {
        return VideoTrack.builder().id(1).codec(Codec.VP8).timeBase(new Rational(1, 30)).width(width).height(height)
                .build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("every shown frame matches libvpx's MD5")
    void vector(String name) throws IOException {
        var ivfPath = Conformance.fetch(name, Vp8Vectors.BASE_URL + name, Vp8Vectors.SHA256.get(name));
        var md5Path = Conformance.fetch(name + ".md5", Vp8Vectors.BASE_URL + name + ".md5", Vp8Vectors.SHA256.get(name + ".md5"));
        var expected = new ArrayList<String>();
        for (var line : Files.readString(md5Path, StandardCharsets.US_ASCII).split("\n")) {
            var trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                expected.add(trimmed.split("\\s+")[0]);
            }
        }
        var ivf = Ivf.parse(Files.readAllBytes(ivfPath));
        var actual = new ArrayList<String>();
        try (var decoder = new Vp8Decoder(track(ivf.width(), ivf.height()))) {
            for (var frame : ivf.frames()) {
                decoder.decode(new Packet(1, frame.data(), frame.pts(), frame.pts(), 0, false),
                        out -> actual.add(Ivf.md5(Ivf.i420((YuvPicture) out.picture()))));
            }
        }
        assertEquals(expected.size(), actual.size(), "shown frame count");
        for (var i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), "frame " + (i + 1));
        }
    }
}
