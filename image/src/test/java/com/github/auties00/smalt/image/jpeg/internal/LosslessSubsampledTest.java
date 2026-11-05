package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes lossless JPEG scans whose interleaved components have sampling factors other than one (T.81 annex H
 * with the minimum coded units of A.2.3): the component planes come back exactly, through the scan decoder and
 * through {@link JpegDecoder}, and ffmpeg's decoder reads the test encoder's streams to the same planes.
 */
@DisplayName("block-interleaved lossless JPEG")
class LosslessSubsampledTest {
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    record Layout(String name, int[][] factors) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final Layout[] LAYOUTS = {
            new Layout("4:2:0", new int[][]{{2, 2}, {1, 1}, {1, 1}}),
            new Layout("4:2:2", new int[][]{{2, 1}, {1, 1}, {1, 1}}),
            new Layout("4:4:0", new int[][]{{1, 2}, {1, 1}, {1, 1}}),
            new Layout("4:1:1", new int[][]{{4, 1}, {1, 1}, {1, 1}}),
            new Layout("2x2 everywhere", new int[][]{{2, 2}, {2, 2}, {2, 2}}),
            new Layout("larger chroma", new int[][]{{1, 1}, {2, 2}, {1, 2}}),
            new Layout("4x4 and 3x1, two components", new int[][]{{4, 4}, {3, 1}}),
            new Layout("four components", new int[][]{{2, 2}, {1, 1}, {1, 1}, {2, 2}})
    };

    static int componentWidth(int width, int[][] factors, int c) {
        var hMax = Arrays.stream(factors).mapToInt(f -> f[0]).max().orElseThrow();
        return (int) Math.ceil((double) width * factors[c][0] / hMax);
    }

    static int componentHeight(int height, int[][] factors, int c) {
        var vMax = Arrays.stream(factors).mapToInt(f -> f[1]).max().orElseThrow();
        return (int) Math.ceil((double) height * factors[c][1] / vMax);
    }

    static int[][] planes(int width, int height, int[][] factors, int precision, long seed) {
        var planes = new int[factors.length][];
        for (var c = 0; c < factors.length; c++) {
            planes[c] = LosslessJpegTest.noise(componentWidth(width, factors, c) * componentHeight(height, factors, c),
                    precision, seed + c);
        }
        return planes;
    }

    static int[][] decodeComponents(byte[] jpeg, int width, int height, int[][] factors) {
        var scan = new LosslessJpeg(jpeg, 0, jpeg.length, 4, 0, MediaLimits.DEFAULT);
        assertFalse(scan.streamsRows());
        var out = new int[factors.length][];
        for (var c = 0; c < factors.length; c++) {
            out[c] = new int[componentWidth(width, factors, c) * componentHeight(height, factors, c)];
        }
        var rows = new int[factors.length];
        scan.decodeComponents((c, row, samples) -> {
            assertEquals(rows[c]++, row);
            var w = componentWidth(width, factors, c);
            System.arraycopy(samples, 0, out[c], row * w, w);
        });
        for (var c = 0; c < factors.length; c++) {
            assertEquals(componentHeight(height, factors, c), rows[c], "rows of component " + c);
        }
        return out;
    }

    static Stream<Arguments> settings() {
        var arguments = Stream.<Arguments>builder();
        var sizes = new int[][]{{37, 29}, {1, 1}, {64, 2}, {5, 17}};
        var index = 0;
        for (var layout : LAYOUTS) {
            for (var predictor = 1; predictor <= 7; predictor++) {
                var size = sizes[index++ % sizes.length];
                arguments.add(Arguments.of(layout, predictor, size[0], size[1], 8, 0, 0));
            }
            arguments.add(Arguments.of(layout, 4, 37, 29, 12, 1, 0));
            arguments.add(Arguments.of(layout, 7, 23, 19, 16, 1, 3));
            arguments.add(Arguments.of(layout, 6, 31, 21, 8, 2, 2));
        }
        return arguments.build();
    }

    @ParameterizedTest(name = "{0}, predictor {1}, {2}x{3}, {4} bits, restart every {5} unit rows, Pt {6}")
    @MethodSource("settings")
    @DisplayName("decodes every layout, predictor, precision, restart interval and point transform exactly")
    void roundTrip(Layout layout, int predictor, int width, int height, int precision, int restartRows,
                   int transform) {
        var factors = layout.factors();
        var source = planes(width, height, factors, precision, predictor * 977L + width);
        var hMax = Arrays.stream(factors).mapToInt(f -> f[0]).max().orElseThrow();
        var unitsWide = (width + hMax - 1) / hMax;
        var jpeg = Lj92Encoder.encodeSampled(source, width, height, factors, precision, predictor,
                restartRows * unitsWide, transform);
        var mask = -(1 << transform);
        var expected = new int[source.length][];
        for (var c = 0; c < source.length; c++) {
            expected[c] = Arrays.stream(source[c]).map(v -> v & mask).toArray();
        }
        var decoded = decodeComponents(jpeg, width, height, factors);
        for (var c = 0; c < factors.length; c++) {
            assertArrayEquals(expected[c], decoded[c], "component " + c);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("directLayouts")
    @DisplayName("JpegDecoder hands out 4:2:0, 4:2:2 and 4:4:0 YCbCr frames in their stored subsampling with the samples as coded")
    void stillDecoder(ChromaFormat format, int[][] factors) {
        var width = 37;
        var height = 29;
        for (var precision : new int[]{8, 12}) {
            var source = planes(width, height, factors, precision, precision);
            var jpeg = withJfif(Lj92Encoder.encodeSampled(source, width, height, factors, precision, 1, 0, 0));
            if (precision == 8) {
                var picture = assertInstanceOf(YuvPicture.class, JpegDecoder.decode(MediaSource.of(jpeg)).picture());
                assertEquals(format, picture.chromaFormat());
                assertPlane(source[0], width, height, picture.y(), picture.yStride());
                assertPlane(source[1], picture.chromaWidth(), picture.chromaHeight(), picture.u(), picture.uvStride());
                assertPlane(source[2], picture.chromaWidth(), picture.chromaHeight(), picture.v(), picture.uvStride());
            } else {
                var picture = assertInstanceOf(Yuv16Picture.class,
                        JpegDecoder.decode(MediaSource.of(jpeg), WIDE).picture());
                assertEquals(format, picture.chromaFormat());
                assertEquals(12, picture.bitDepth());
                assertPlane(source[0], width, height, picture.y(), picture.yStride());
                assertPlane(source[1], picture.chromaWidth(), picture.chromaHeight(), picture.u(), picture.uvStride());
                assertPlane(source[2], picture.chromaWidth(), picture.chromaHeight(), picture.v(), picture.uvStride());
            }
        }
    }

    static Stream<Arguments> directLayouts() {
        return Stream.of(Arguments.of(ChromaFormat.YUV420, new int[][]{{2, 2}, {1, 1}, {1, 1}}),
                Arguments.of(ChromaFormat.YUV422, new int[][]{{2, 1}, {1, 1}, {1, 1}}),
                Arguments.of(ChromaFormat.YUV440, new int[][]{{1, 2}, {1, 1}, {1, 1}}));
    }

    @Test
    @DisplayName("replicates the components of other layouts up to full resolution for the 16-bit output")
    void replicated() {
        var width = 21;
        var height = 13;
        var factors = new int[][]{{4, 1}, {1, 1}, {1, 1}};
        var source = planes(width, height, factors, 10, 5);
        var jpeg = withJfif(Lj92Encoder.encodeSampled(source, width, height, factors, 10, 2, 0, 0));
        var picture = assertInstanceOf(Yuv16Picture.class, JpegDecoder.decode(MediaSource.of(jpeg), WIDE).picture());
        assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
        var chromaWidth = componentWidth(width, factors, 1);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                assertEquals(source[0][y * width + x], picture.y()[y * picture.yStride() + x]);
                assertEquals(source[1][y * chromaWidth + x / 4], picture.u()[y * picture.uvStride() + x]);
                assertEquals(source[2][y * chromaWidth + x / 4], picture.v()[y * picture.uvStride() + x]);
            }
        }
    }

    private static void assertPlane(int[] expected, int width, int height, byte[] plane, int stride) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                assertEquals(expected[y * width + x], plane[y * stride + x] & 0xFF, "sample " + x + "," + y);
            }
        }
    }

    private static void assertPlane(int[] expected, int width, int height, short[] plane, int stride) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                assertEquals(expected[y * width + x], plane[y * stride + x] & 0xFFFF, "sample " + x + "," + y);
            }
        }
    }

    // A JFIF segment after SOI makes the three components Y'CbCr, as ffmpeg's ljpeg encoder marks them
    static byte[] withJfif(byte[] jpeg) {
        var jfif = new byte[]{(byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 2, 0, 0, 1, 0, 1, 0, 0};
        var out = new byte[jpeg.length + jfif.length];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xD8;
        System.arraycopy(jfif, 0, out, 2, jfif.length);
        System.arraycopy(jpeg, 2, out, 2 + jfif.length, jpeg.length - 2);
        return out;
    }

    @Nested
    @DisplayName("against ffmpeg")
    class Ffmpeg {
        static Stream<Arguments> layouts() {
            return Stream.of(
                    Arguments.of("yuvj420p", new int[][]{{2, 2}, {1, 1}, {1, 1}}),
                    Arguments.of("yuvj422p", new int[][]{{2, 1}, {1, 1}, {1, 1}}),
                    Arguments.of("yuvj440p", new int[][]{{1, 2}, {1, 1}, {1, 1}}),
                    Arguments.of("yuvj411p", new int[][]{{4, 1}, {1, 1}, {1, 1}}));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("layouts")
        @DisplayName("ffmpeg decodes the streams to the same planes, with every predictor and restart markers")
        void sameAsFfmpeg(String pixelFormat, int[][] factors) throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var width = 36;
            var height = 24;
            var hMax = factors[0][0];
            for (var predictor = 1; predictor <= 7; predictor++) {
                var source = planes(width, height, factors, 8, predictor);
                var jpeg = withJfif(Lj92Encoder.encodeSampled(source, width, height, factors, 8, predictor,
                        predictor % 2 == 0 ? 2 * (width / hMax) : 0, 0));
                var path = Files.createTempFile("smalt-lossless", ".jpg");
                try {
                    Files.write(path, jpeg);
                    var raw = Oracle.decodeVideo(path, pixelFormat, 1);
                    var at = 0;
                    var decoded = decodeComponents(jpeg, width, height, factors);
                    for (var c = 0; c < 3; c++) {
                        for (var i = 0; i < decoded[c].length; i++) {
                            assertEquals(source[c][i], decoded[c][i]);
                            assertEquals(source[c][i], raw[at++] & 0xFF, "ffmpeg, predictor " + predictor
                                    + ", component " + c + ", sample " + i);
                        }
                    }
                    assertEquals(raw.length, at);
                } finally {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Nested
    @DisplayName("damaged streams")
    class Damaged {
        @Test
        @DisplayName("a truncated scan decodes every row with zero bits for the missing data")
        void truncated() {
            var factors = new int[][]{{2, 2}, {1, 1}, {1, 1}};
            var source = planes(40, 30, factors, 8, 9);
            var jpeg = Lj92Encoder.encodeSampled(source, 40, 30, factors, 8, 1, 0, 0);
            var cut = Arrays.copyOf(jpeg, jpeg.length / 2);
            var rows = new int[3];
            new LosslessJpeg(cut, 0, cut.length, 4, 0, MediaLimits.DEFAULT).decodeComponents((c, row, samples) -> rows[c]++);
            assertArrayEquals(new int[]{30, 15, 15}, rows);
        }

        @ParameterizedTest(name = "restart every {0} units")
        @ValueSource(ints = {1, 3, 7, 19, 21, 33})
        @DisplayName("a restart interval that splits rows of units restarts the first line at the interval's first unit")
        void partialRowRestart(int interval) {
            var factors = new int[][]{{2, 2}, {1, 1}, {1, 1}};
            var source = planes(40, 30, factors, 8, 13);
            for (var predictor = 1; predictor <= 7; predictor++) {
                var jpeg = withJfif(Lj92Encoder.encodeFrame(source, 40, 30, factors, 8, false,
                        List.of(Lj92Encoder.ScanSpec.of(new int[]{0, 1, 2}, predictor, 0, interval))));
                var decoded = decodeComponents(jpeg, 40, 30, factors);
                for (var c = 0; c < 3; c++) {
                    assertArrayEquals(source[c], decoded[c], "predictor " + predictor + ", component " + c);
                }
                var picture = assertInstanceOf(YuvPicture.class, JpegDecoder.decode(MediaSource.of(jpeg)).picture());
                assertPlane(source[1], picture.chromaWidth(), picture.chromaHeight(), picture.u(), picture.uvStride());
            }
        }

        @Test
        @DisplayName("random damage raises nothing but MediaException")
        void fuzz() {
            var factors = new int[][]{{2, 1}, {1, 1}, {1, 1}};
            var source = planes(24, 16, factors, 8, 11);
            var jpeg = withJfif(Lj92Encoder.encodeSampled(source, 24, 16, factors, 8, 5, 12, 0));
            var random = new Random(42);
            for (var trial = 0; trial < 400; trial++) {
                var damaged = jpeg.clone();
                for (var k = 0; k < 1 + random.nextInt(6); k++) {
                    damaged[random.nextInt(damaged.length)] = (byte) random.nextInt(256);
                }
                var length = random.nextBoolean() ? damaged.length : random.nextInt(damaged.length);
                var input = Arrays.copyOf(damaged, length);
                try {
                    JpegDecoder.decode(MediaSource.of(input));
                } catch (MediaException expected) {
                    assertFalse(expected.getMessage() == null || expected.getMessage().isEmpty());
                }
            }
        }
    }
}
