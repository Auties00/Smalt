package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Decodes JPEG-LS streams against references: the ITU-T T.87 conformance data set (its lossless streams against
 * the source images, its near-lossless ones within their bound of the sources and bit for bit against CharLS's or
 * the ISO/IEC 10918-7 reference software A's decode), CharLS's encodings of the synthetic images of
 * {@code tools/testdata/jpegls} and the reference software A's encodings with subsampling, restart intervals and a
 * height defined by DNL, each against the samples its encoder's decoder reconstructs.
 */
@DisplayName("JPEG-LS conformance")
class JpegLsConformanceTest {
    private static final List<String[]> T87 = JpegLsVectors.rows("t87.tsv");

    private static String oracle(String entry) {
        for (var row : T87) {
            if (row[0].equals(entry)) {
                return row[2];
            }
        }
        throw new IllegalArgumentException(entry);
    }

    private static int[] component(String entry, int component) {
        return JpegLsVectors.pnmComponent(JpegLsVectors.pnm(JpegLsVectors.t87(entry)), component);
    }

    @Nested
    @DisplayName("ITU-T T.87 data set")
    class DataSet {
        static Stream<Arguments> color() {
            return Stream.of(Arguments.of("T8C0E0", 0), Arguments.of("T8C1E0", 0), Arguments.of("T8C2E0", 0),
                    Arguments.of("T8C0E3", 3), Arguments.of("T8C1E3", 3), Arguments.of("T8C2E3", 3));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("color")
        @DisplayName("decodes the color streams of every interleave mode to TEST8 within NEAR and as the oracle does")
        void colorStreams(String name, int near) {
            var entry = "T87_test-1-2-3-4-5-6/" + name + ".JLS";
            var planes = JpegLsVectors.decode(JpegLsVectors.t87(entry));
            assertEquals(3, planes.length);
            for (var c = 0; c < 3; c++) {
                var reference = component("T87_test-1-2-3-4-5-6/TEST8.PPM", c);
                (near == 0 ? Tolerance.exact() : Tolerance.maxAbs(near)).assertWithin(name + " component " + c,
                        reference, planes[c]);
            }
            assertEquals(oracle(entry), JpegLsVectors.sha256(planes), name);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("subsampledStreams")
        @DisplayName("decodes the line-interleaved subsampled streams to their component images")
        void subsampled(String name, int near) {
            var entry = "T87_test-7-8/" + name + ".JLS";
            var planes = JpegLsVectors.decode(JpegLsVectors.t87(entry));
            var references = new String[]{"TEST8R.PGM", "TEST8GR4.PGM", "TEST8BS2.PGM"};
            assertEquals(3, planes.length);
            for (var c = 0; c < 3; c++) {
                var reference = component("T87_test-7-8/" + references[c], 0);
                (near == 0 ? Tolerance.exact() : Tolerance.maxAbs(near)).assertWithin(name + " component " + c,
                        reference, planes[c]);
            }
            assertEquals(oracle(entry), JpegLsVectors.sha256(planes), name);
        }

        static Stream<Arguments> subsampledStreams() {
            return Stream.of(Arguments.of("T8SSE0", 0), Arguments.of("T8SSE3", 3));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("singleStreams")
        @DisplayName("decodes the gray streams, the non-default parameters and the 12-bit ones included")
        void gray(String directory, String name, String reference, int near) {
            var entry = directory + "/" + name + ".JLS";
            var planes = JpegLsVectors.decode(JpegLsVectors.t87(entry));
            assertEquals(1, planes.length);
            (near == 0 ? Tolerance.exact() : Tolerance.maxAbs(near)).assertWithin(name,
                    component(directory + "/" + reference, 0), planes[0]);
            assertEquals(oracle(entry), JpegLsVectors.sha256(planes), name);
        }

        static Stream<Arguments> singleStreams() {
            return Stream.of(Arguments.of("T87_test-9-10", "T8NDE0", "TEST8BS2.PGM", 0),
                    Arguments.of("T87_test-9-10", "T8NDE3", "TEST8BS2.PGM", 3),
                    Arguments.of("T87_test-11-12", "T16E0", "TEST16.PGM", 0),
                    Arguments.of("T87_test-11-12", "T16E3", "TEST16.PGM", 3));
        }

        @Test
        @DisplayName("decode through JpegDecoder to RGB, 12-bit gray and replicated subsampled components")
        void pictures() {
            var test8 = JpegLsVectors.pnm(JpegLsVectors.t87("T87_test-1-2-3-4-5-6/TEST8.PPM"));
            var rgb = assertInstanceOf(RgbaPicture.class, JpegDecoder.decode(MediaSource.of(
                    JpegLsVectors.t87("T87_test-1-2-3-4-5-6/T8C1E0.JLS"))).picture());
            for (var i = 0; i < 256 * 256; i++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(test8[4 + 3 * i + c], rgb.pixels()[(i / 256) * rgb.stride() + 4 * (i % 256) + c] & 0xFF);
                }
            }
            var t16 = JpegLsVectors.t87("T87_test-11-12/T16E0.JLS");
            var reference = component("T87_test-11-12/TEST16.PGM", 0);
            var wide = assertInstanceOf(Yuv16Picture.class, JpegDecoder.decode(MediaSource.of(t16),
                    ImageDecoderOptions.builder().highBitDepth(true).build()).picture());
            assertEquals(12, wide.bitDepth());
            var narrow = assertInstanceOf(YuvPicture.class, JpegDecoder.decode(MediaSource.of(t16)).picture());
            for (var y = 0; y < 256; y++) {
                for (var x = 0; x < 256; x++) {
                    var value = reference[y * 256 + x];
                    assertEquals(value, wide.y()[y * wide.yStride() + x]);
                    assertEquals((value * 510 + 4095) / 8190, narrow.y()[y * narrow.yStride() + x] & 0xFF);
                }
            }
            var sse = assertInstanceOf(RgbaPicture.class, JpegDecoder.decode(MediaSource.of(
                    JpegLsVectors.t87("T87_test-7-8/T8SSE0.JLS"))).picture());
            var red = component("T87_test-7-8/TEST8R.PGM", 0);
            var green = component("T87_test-7-8/TEST8GR4.PGM", 0);
            var blue = component("T87_test-7-8/TEST8BS2.PGM", 0);
            for (var y = 0; y < 256; y++) {
                for (var x = 0; x < 256; x++) {
                    var at = y * sse.stride() + 4 * x;
                    assertEquals(red[y * 256 + x], sse.pixels()[at] & 0xFF);
                    assertEquals(green[(y / 4) * 256 + x], sse.pixels()[at + 1] & 0xFF);
                    assertEquals(blue[(y / 2) * 128 + x / 2], sse.pixels()[at + 2] & 0xFF);
                }
            }
        }
    }

    @Nested
    @DisplayName("CharLS vectors")
    class CharLs {
        static List<JpegLsVectors.CharLsCase> cases() {
            return JpegLsVectors.charls();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cases")
        @DisplayName("decode as CharLS decodes them, losslessly coded ones to the synthetic image")
        void decode(JpegLsVectors.CharLsCase vector) {
            var stream = JpegLsVectors.resource("charls/" + vector.name() + ".jls");
            var planes = JpegLsVectors.decode(stream);
            var source = JpegLsVectors.synthetic(vector.width(), vector.height(), vector.components(), vector.bits(),
                    vector.seed(), null);
            if (vector.mapped()) {
                var entry = vector.entrySize();
                assertEquals(entry == 3 ? 3 : 1, planes.length);
                for (var c = 0; c < planes.length; c++) {
                    var expected = new int[source[0].length];
                    for (var i = 0; i < expected.length; i++) {
                        var index = source[0][i];
                        if (entry == 3) {
                            expected[i] = JpegLsVectors.tableByte(index, c);
                        } else {
                            var value = 0;
                            for (var b = 0; b < entry; b++) {
                                value = (value << 8) | JpegLsVectors.tableByte(index, b);
                            }
                            expected[i] = value;
                        }
                    }
                    Tolerance.exact().assertWithin(vector + " component " + c, expected, planes[c]);
                }
                return;
            }
            assertEquals(vector.decoded(), JpegLsVectors.sha256(planes), vector.name());
            for (var c = 0; c < vector.components(); c++) {
                (vector.near() == 0 ? Tolerance.exact() : Tolerance.maxAbs(vector.near()))
                        .assertWithin(vector + " component " + c, source[c], planes[c]);
            }
        }
    }

    @Nested
    @DisplayName("reference software A vectors")
    class ReferenceA {
        static List<JpegLsVectors.ReferenceCase> cases() {
            return JpegLsVectors.reference();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cases")
        @DisplayName("decode the subsampled, restart-interval and DNL streams as the reference decodes them")
        void decode(JpegLsVectors.ReferenceCase vector) {
            var planes = JpegLsVectors.decode(JpegLsVectors.resource("referenceA/" + vector.name() + ".jls"));
            assertEquals(vector.components(), planes.length);
            assertEquals(vector.decoded(), JpegLsVectors.sha256(planes), vector.name());
        }
    }
}
