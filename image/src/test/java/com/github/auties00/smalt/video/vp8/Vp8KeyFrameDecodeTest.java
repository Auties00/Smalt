package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.webp.internal.riff.WebpParser;
import com.github.auties00.smalt.video.vp8.internal.Vp8;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Decodes the VP8 chunk of WebP files produced by cwebp with many settings through
 * {@link Vp8#decodeKeyFrame} and compares the planes bit-exactly with {@code dwebp -yuv}.
 */
@DisplayName("Vp8.decodeKeyFrame")
class Vp8KeyFrameDecodeTest {
    static final String SOURCE = "testsrc2=size=320x240:rate=1";

    static Stream<Arguments> qualityMethod() {
        var arguments = new ArrayList<Arguments>();
        for (var q : new int[]{0, 50, 90, 100}) {
            for (var m = 0; m <= 6; m++) {
                arguments.add(Arguments.of(List.of("-q", Integer.toString(q), "-m", Integer.toString(m))));
            }
        }
        return arguments.stream();
    }

    static Stream<Arguments> variants() {
        return Stream.of(
                Arguments.of(List.of("-sns", "0")),
                Arguments.of(List.of("-sns", "100")),
                Arguments.of(List.of("-f", "0")),
                Arguments.of(List.of("-f", "100")),
                Arguments.of(List.of("-f", "60", "-sharpness", "7")),
                Arguments.of(List.of("-sharpness", "3")),
                Arguments.of(List.of("-nostrong")),
                Arguments.of(List.of("-nostrong", "-f", "100")),
                Arguments.of(List.of("-af")),
                Arguments.of(List.of("-segments", "1")),
                Arguments.of(List.of("-segments", "2")),
                Arguments.of(List.of("-segments", "3")),
                Arguments.of(List.of("-segments", "4", "-sns", "100", "-q", "30")),
                Arguments.of(List.of("-preset", "photo")),
                Arguments.of(List.of("-preset", "drawing")),
                Arguments.of(List.of("-preset", "text")),
                Arguments.of(List.of("-partition_limit", "100", "-q", "100"))
        );
    }

    static Stream<Arguments> sizes() {
        return Stream.of(Arguments.of(1, 1), Arguments.of(2, 2), Arguments.of(15, 17), Arguments.of(33, 17),
                Arguments.of(16, 16), Arguments.of(17, 1), Arguments.of(1, 40), Arguments.of(257, 129));
    }

    static void assertMatchesDwebp(byte[] webp) {
        var vp8 = Ivf.vp8Chunk(webp);
        var picture = Vp8.decodeKeyFrame(vp8, 0, vp8.length, MediaLimits.DEFAULT);
        assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
        assertEquals(ColorInfo.BT601_LIMITED, picture.color());
        var size = WebpParser.vp8Dimensions(Arrays.copyOf(vp8, 10), vp8.length);
        assertEquals(picture.width(), size[0]);
        assertEquals(picture.height(), size[1]);
        assertArrayEquals(WebpTools.dwebpYuv(webp), Ivf.i420(picture));
    }

    @ParameterizedTest(name = "cwebp {0}")
    @MethodSource("qualityMethod")
    @DisplayName("matches dwebp for every quality and method")
    void qualities(List<String> options) {
        assertMatchesDwebp(WebpTools.cwebp(WebpTools.png(SOURCE, 320, 240), options));
    }

    @ParameterizedTest(name = "cwebp {0}")
    @MethodSource("variants")
    @DisplayName("matches dwebp for filter, noise shaping and segment variants")
    void variantsMatch(List<String> options) {
        assertMatchesDwebp(WebpTools.cwebp(WebpTools.png(SOURCE, 320, 240), options));
    }

    @ParameterizedTest(name = "{0}x{1}")
    @MethodSource("sizes")
    @DisplayName("matches dwebp for odd and tiny sizes")
    void oddSizes(int width, int height) {
        assertMatchesDwebp(WebpTools.cwebp(WebpTools.png("testsrc2=size=320x240:rate=1", width, height), List.of("-q", "75")));
    }

    @Test
    @DisplayName("matches dwebp for a noisy photographic-like image")
    void noise() {
        assertMatchesDwebp(WebpTools.cwebp(WebpTools.png("mandelbrot=size=640x480:rate=1", 640, 480), List.of("-q", "80")));
    }

    @Nested
    @DisplayName("rejects")
    class Rejects {
        @Test
        @DisplayName("an inter frame")
        void interFrame() {
            var vp8 = Ivf.vp8Chunk(WebpTools.cwebp(WebpTools.png(SOURCE, 64, 48), List.of("-q", "75")));
            vp8[0] |= 1;
            assertThrows(MediaException.Malformed.class, () -> Vp8.decodeKeyFrame(vp8, 0, vp8.length, MediaLimits.DEFAULT));
        }

        @Test
        @DisplayName("a frame that is not displayable")
        void hidden() {
            var vp8 = Ivf.vp8Chunk(WebpTools.cwebp(WebpTools.png(SOURCE, 64, 48), List.of("-q", "75")));
            vp8[0] &= ~0x10;
            assertThrows(MediaException.Unsupported.class, () -> Vp8.decodeKeyFrame(vp8, 0, vp8.length, MediaLimits.DEFAULT));
        }

        @Test
        @DisplayName("a frame larger than the limits")
        void limits() {
            var vp8 = Ivf.vp8Chunk(WebpTools.cwebp(WebpTools.png(SOURCE, 64, 48), List.of("-q", "75")));
            var limits = MediaLimits.builder().maxDimension(32).maxPixels(1 << 20).maxFrames(1).maxChannels(1)
                    .maxBytes(1 << 20).build();
            assertThrows(MediaException.LimitExceeded.class, () -> Vp8.decodeKeyFrame(vp8, 0, vp8.length, limits));
        }

        @Test
        @DisplayName("empty and truncated headers")
        void truncated() {
            var vp8 = Ivf.vp8Chunk(WebpTools.cwebp(WebpTools.png(SOURCE, 64, 48), List.of("-q", "75")));
            for (var length = 0; length < 11; length++) {
                var n = length;
                assertThrows(MediaException.Malformed.class, () -> Vp8.decodeKeyFrame(vp8, 0, n, MediaLimits.DEFAULT));
            }
        }
    }
}
