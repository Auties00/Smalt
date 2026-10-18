package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.video.vp9.internal.Vp9FrameDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes every libvpx VP9 test vector (vp90-2 for profile 0, vp91-2, vp92-2 and vp93-2 for
 * profiles 1 to 3) and compares the MD5 of each shown frame's visible planes with libvpx's
 * {@code .md5} file, exactly as libvpx's {@code test_vector_test.cc} does: 8-bit samples as
 * bytes, 10 and 12-bit samples as 16-bit little-endian values. Frames are read from the internal
 * frame decoder so that high bit depth and 4:4:0 frames are checked at full precision.
 */
@DisplayName("VP9 conformance (libvpx test vectors)")
class Vp9ConformanceTest {
    static Stream<String> vectors() {
        return Vp9Vectors.SHA256.keySet().stream();
    }

    // vp90-2-13-largescaling has a 31680x128 frame, wider than the default limits allow.
    static final MediaLimits LIMITS = MediaLimits.builder().maxDimension(65_536).build();

    static List<String> decode(String name, boolean parallel) throws IOException {
        var bytes = Files.readAllBytes(Vp9Vectors.stream(name));
        var decoder = new Vp9FrameDecoder(LIMITS, parallel);
        var actual = new ArrayList<String>();
        for (var packet : Vp9Vectors.packets(name, bytes)) {
            decoder.decodePacket(packet, 0, packet.length);
            var frame = decoder.shownFrame();
            if (frame != null) {
                actual.add(Vp9Vectors.md5(frame));
            }
        }
        return actual;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("every shown frame matches libvpx's MD5")
    void vector(String name) throws IOException {
        var expected = Vp9Vectors.expectedMd5(name);
        var actual = decode(name, false);
        for (var i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            assertEquals(expected.get(i), actual.get(i), "frame " + (i + 1));
        }
        assertEquals(expected.size(), actual.size(), "shown frame count");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("decoding on helper threads gives libvpx's MD5 for every shown frame")
    void inParallel(String name) throws IOException {
        var expected = Vp9Vectors.expectedMd5(name);
        var actual = decode(name, true);
        assertEquals(expected.size(), actual.size(), "shown frame count");
        for (var i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), "frame " + (i + 1));
        }
    }
}
