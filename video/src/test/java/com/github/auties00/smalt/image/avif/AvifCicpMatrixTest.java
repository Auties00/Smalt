package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.avif.internal.EncodeImage;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers AVIF encoding of RGB pictures in the matrices that need the transfer function, the primaries or the integer
 * YCgCo-R transform: the prepared samples equal the core conversion, the file signals the matrix, and the decoded
 * image converts back to the source.
 */
@DisplayName("AVIF with CICP matrices")
class AvifCicpMatrixTest {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;

    static Stream<Arguments> colors() {
        return Stream.of(
                Arguments.of(new ColorInfo(MatrixCoefficients.BT2020_CL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                        TransferCharacteristics.BT2020_10)),
                Arguments.of(new ColorInfo(MatrixCoefficients.CHROMA_DERIVED_CL, ColorRange.FULL,
                        ColorPrimaries.SMPTE432, TransferCharacteristics.SRGB)),
                Arguments.of(new ColorInfo(MatrixCoefficients.CHROMA_DERIVED_NCL, ColorRange.FULL,
                        ColorPrimaries.SMPTE432, TransferCharacteristics.SRGB)),
                Arguments.of(new ColorInfo(MatrixCoefficients.ICTCP, ColorRange.LIMITED, ColorPrimaries.BT2020,
                        TransferCharacteristics.PQ)),
                Arguments.of(new ColorInfo(MatrixCoefficients.IPT_C2, ColorRange.LIMITED, ColorPrimaries.BT2020,
                        TransferCharacteristics.PQ)),
                Arguments.of(new ColorInfo(MatrixCoefficients.SMPTE2085, ColorRange.LIMITED, ColorPrimaries.SMPTE428,
                        TransferCharacteristics.SMPTE428)),
                Arguments.of(new ColorInfo(MatrixCoefficients.YCGCO_RE, ColorRange.FULL, ColorPrimaries.BT709,
                        TransferCharacteristics.SRGB)),
                Arguments.of(new ColorInfo(MatrixCoefficients.YCGCO_RO, ColorRange.LIMITED, ColorPrimaries.BT709,
                        TransferCharacteristics.SRGB)),
                Arguments.of(new ColorInfo(MatrixCoefficients.YCGCO, ColorRange.LIMITED, ColorPrimaries.BT709,
                        TransferCharacteristics.SRGB)));
    }

    private static RgbaPicture source() {
        return ImageOps.toRgba(Vp9TestVideo.synthesize(WIDTH, HEIGHT, 1).getFirst());
    }

    private static double psnr(RgbaPicture expected, Picture decoded) {
        var actual = ImageOps.toRgba(decoded);
        var a = new byte[3 * WIDTH * HEIGHT];
        var b = new byte[3 * WIDTH * HEIGHT];
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            System.arraycopy(expected.pixels(), 4 * i, a, 3 * i, 3);
            System.arraycopy(actual.pixels(), 4 * i, b, 3 * i, 3);
        }
        return Quality.psnr(a, b);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("colors")
    @DisplayName("prepares the samples the core conversion gives at the coded depth")
    void samples(ColorInfo color) {
        var source = source();
        for (var format : new ChromaFormat[]{ChromaFormat.YUV444, ChromaFormat.YUV420}) {
            var image = EncodeImage.of(source, format, 10, color, false, false);
            var expected = ImageOps.toYuv16(source, format, color, 10);
            var cw = format.chromaWidth(WIDTH);
            var ch = format.chromaHeight(HEIGHT);
            // YCgCo and the chromaticity-derived weights take the weighted-sum path, whose floating-point order
            // differs from the core conversion's; every other matrix runs the same equations
            var weighted = color.matrix() == MatrixCoefficients.YCGCO
                    || color.matrix() == MatrixCoefficients.CHROMA_DERIVED_NCL;
            var tolerance = weighted ? 1 : 0;
            assertClose(compact(expected.y(), expected.yStride(), WIDTH, HEIGHT), image.planes()[0], tolerance);
            assertClose(compact(expected.u(), expected.uvStride(), cw, ch), image.planes()[1], tolerance);
            assertClose(compact(expected.v(), expected.uvStride(), cw, ch), image.planes()[2], tolerance);
        }
    }

    private static void assertClose(short[] expected, short[] actual, int tolerance) {
        assertEquals(expected.length, actual.length);
        for (var i = 0; i < expected.length; i++) {
            assertTrue(Math.abs(expected[i] - actual[i]) <= tolerance, "sample " + i + ": " + expected[i] + " vs "
                    + actual[i]);
        }
    }

    private static short[] compact(short[] plane, int stride, int width, int height) {
        var out = new short[width * height];
        for (var y = 0; y < height; y++) {
            System.arraycopy(plane, y * stride, out, y * width, width);
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("colors")
    @DisplayName("signals the matrix and decodes back to the source")
    void roundTrip(ColorInfo color) {
        var source = source();
        var options = AvifEncoderOptions.lossy(95).toBuilder().color(color).chromaFormat(ChromaFormat.YUV444)
                .bitDepth(10).build();
        var file = AvifEncoder.encode(source, options);
        var decoded = AvifDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().highBitDepth(true)
                .build()).picture();
        var yuv = (Yuv16Picture) decoded;
        assertEquals(color.matrix(), yuv.color().matrix());
        assertEquals(color.range(), yuv.color().range());
        assertEquals(10, yuv.bitDepth());
        var psnr = psnr(source, decoded);
        assertTrue(psnr > 42, color.matrix() + ": " + psnr + " dB");
    }

    @Test
    @DisplayName("YCgCo-Re lossless coding restores every pixel at 10 bits")
    void ycgcoReLossless() {
        var source = source();
        var color = new ColorInfo(MatrixCoefficients.YCGCO_RE, ColorRange.LIMITED, ColorPrimaries.BT709,
                TransferCharacteristics.SRGB);
        var file = AvifEncoder.encode(source, AvifEncoderOptions.lossless().toBuilder().color(color).build());
        var decoded = (Yuv16Picture) AvifDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder()
                .highBitDepth(true).build()).picture();
        assertEquals(MatrixCoefficients.YCGCO_RE, decoded.color().matrix());
        assertEquals(ColorRange.FULL, decoded.color().range());
        assertEquals(10, decoded.bitDepth());
        var restored = ImageOps.toRgba(decoded);
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            for (var c = 0; c < 3; c++) {
                assertEquals(source.pixels()[4 * i + c], restored.pixels()[4 * i + c], "pixel " + i);
            }
        }
    }

    @Test
    @DisplayName("limited-range YCgCo scales its color differences like luma, as the core conversion does")
    void ycgcoLimited() {
        var source = source();
        var color = new ColorInfo(MatrixCoefficients.YCGCO, ColorRange.LIMITED, ColorPrimaries.BT709,
                TransferCharacteristics.SRGB);
        var image = EncodeImage.of(source, ChromaFormat.YUV444, 8, color, false, false);
        var expected = ImageOps.toYuv(source, ChromaFormat.YUV444, color);
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            assertTrue(Math.abs((expected.u()[i] & 0xFF) - image.planes()[1][i]) <= 1, "Cg " + i);
            assertTrue(Math.abs((expected.v()[i] & 0xFF) - image.planes()[2][i]) <= 1, "Co " + i);
        }
    }
}
