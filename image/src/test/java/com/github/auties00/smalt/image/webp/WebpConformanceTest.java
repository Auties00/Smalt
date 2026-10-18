package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decodes the official libwebp test corpus and compares every output with its reference: the
 * lossless vectors with the grid/peak/color-transform PAM files, and every file listed in
 * libwebp_tests.md5 with the MD5 of dwebp's PAM (RGBA) and PGM (Y'CbCr) output. Lossy files need
 * VP8 pixels and are skipped until the VP8 workstream is merged.
 */
class WebpConformanceTest {
    static Stream<String> vec1() {
        return IntStream.range(0, 16).mapToObj(i -> "lossless_vec_1_" + i + ".webp");
    }

    static Stream<String> vec2() {
        return IntStream.range(0, 16).mapToObj(i -> "lossless_vec_2_" + i + ".webp");
    }

    static Stream<String> pamListed() {
        var list = LibwebpTestData.md5List();
        return list.keySet().stream().filter(name -> name.endsWith(".webp.pam"))
                .map(name -> name.substring(0, name.length() - 4)).sorted();
    }

    static Stream<String> pgmListed() {
        var list = LibwebpTestData.md5List();
        return list.keySet().stream().filter(name -> name.endsWith(".webp.pgm"))
                .map(name -> name.substring(0, name.length() - 4)).sorted();
    }

    private static byte[] decodeRgbaPixels(String name) {
        return WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(LibwebpTestData.read(name)));
    }

    @Nested
    @DisplayName("lossless vectors")
    class Lossless {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.webp.WebpConformanceTest#vec1")
        @DisplayName("lossless_vec_1_* decode to grid.pam")
        void grid(String name) {
            var expected = WebpTestSupport.pamPixels(LibwebpTestData.read("grid.pam"));
            assertArrayEquals(expected, decodeRgbaPixels(name));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.webp.WebpConformanceTest#vec2")
        @DisplayName("lossless_vec_2_* decode to peak.pam")
        void peak(String name) {
            var expected = WebpTestSupport.pamPixels(LibwebpTestData.read("peak.pam"));
            assertArrayEquals(expected, decodeRgbaPixels(name));
        }

        @Test
        @DisplayName("lossless_color_transform decodes to its PAM")
        void colorTransform() {
            var expected = WebpTestSupport.pamPixels(LibwebpTestData.read("lossless_color_transform.pam"));
            assertArrayEquals(expected, decodeRgbaPixels("lossless_color_transform.webp"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pamListed")
    @DisplayName("every corpus file decodes to the RGBA whose MD5 libwebp_tests.md5 lists")
    void pamMd5(String name) {
        var expected = LibwebpTestData.md5List().get(name + ".pam");
        var picture = WebpTestSupport.withVp8(() -> WebpTestSupport.decodeRgba(LibwebpTestData.read(name)));
        assertEquals(expected, WebpTestSupport.md5(WebpTestSupport.pam(picture)), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pgmListed")
    @DisplayName("every lossy corpus file decodes to the Y'CbCr(A) whose MD5 libwebp_tests.md5 lists")
    void pgmMd5(String name) {
        var file = LibwebpTestData.read(name);
        var probe = WebpDecoder.probe(MediaSource.of(file));
        var decoded = WebpTestSupport.withVp8(() -> WebpDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()));
        if (decoded.picture() instanceof RgbaPicture) {
            return;
        }
        var picture = (YuvPicture) decoded.picture();
        assertEquals(probe.width(), picture.width());
        var expected = LibwebpTestData.md5List().get(name + ".pgm");
        assertEquals(expected, WebpTestSupport.md5(WebpTestSupport.pgm(picture)), name);
    }
}
