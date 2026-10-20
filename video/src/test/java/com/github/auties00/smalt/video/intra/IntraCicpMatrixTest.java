package com.github.auties00.smalt.video.intra;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.dnxhd.DnxhdDecoder;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoder;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions;
import com.github.auties00.smalt.video.dnxhd.DnxhdProfile;
import com.github.auties00.smalt.video.prores.ProResDecoder;
import com.github.auties00.smalt.video.prores.ProResEncoder;
import com.github.auties00.smalt.video.prores.ProResEncoderOptions;
import com.github.auties00.smalt.video.prores.ProResProfile;
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
 * Covers RGB input of the ProRes and DNxHD encoders in the matrices that need the transfer function, the primaries or
 * the integer YCgCo-R transform: the header signals the matrix, and the decoded frames convert back to the source.
 */
@DisplayName("intra encoders with CICP matrices")
class IntraCicpMatrixTest {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;

    static Stream<Arguments> colors() {
        return Stream.of(
                Arguments.of(new ColorInfo(MatrixCoefficients.BT2020_CL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                        TransferCharacteristics.BT2020_10)),
                Arguments.of(new ColorInfo(MatrixCoefficients.CHROMA_DERIVED_CL, ColorRange.LIMITED,
                        ColorPrimaries.SMPTE432, TransferCharacteristics.BT709)),
                Arguments.of(new ColorInfo(MatrixCoefficients.CHROMA_DERIVED_NCL, ColorRange.LIMITED,
                        ColorPrimaries.SMPTE431, TransferCharacteristics.BT709)),
                Arguments.of(new ColorInfo(MatrixCoefficients.ICTCP, ColorRange.LIMITED, ColorPrimaries.BT2020,
                        TransferCharacteristics.PQ)),
                Arguments.of(new ColorInfo(MatrixCoefficients.SMPTE2085, ColorRange.LIMITED, ColorPrimaries.SMPTE428,
                        TransferCharacteristics.SMPTE428)),
                Arguments.of(new ColorInfo(MatrixCoefficients.YCGCO_RE, ColorRange.LIMITED, ColorPrimaries.BT709,
                        TransferCharacteristics.BT709)),
                Arguments.of(new ColorInfo(MatrixCoefficients.YCGCO, ColorRange.LIMITED, ColorPrimaries.BT709,
                        TransferCharacteristics.BT709)));
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
    @DisplayName("ProRes 4444 codes RGB input in the matrix, signals its code point and decodes back to the source")
    void prores(ColorInfo color) {
        var source = source();
        var options = ProResEncoderOptions.of(ProResProfile.PRORES_4444).toBuilder().threads(1).quantizationIndex(1)
                .color(color).build();
        try (var encoder = new ProResEncoder(WIDTH, HEIGHT, options)) {
            var data = encoder.encodeFrame(new VideoFrame(source, 0, 1, true)).data();
            var decoded = (Yuv16Picture) new ProResDecoder().decodeFrame(data, 0, data.length);
            assertEquals(color.matrix(), decoded.color().matrix());
            assertEquals(color.primaries(), decoded.color().primaries());
            assertEquals(color.transfer(), decoded.color().transfer());
            var direct = ImageOps.toYuv16(source, ChromaFormat.YUV444, color, 12);
            var psnr = psnr(source, decoded);
            var reference = psnr(source, direct);
            assertTrue(psnr > 38 && reference > 45, color.matrix() + ": " + psnr + " dB, conversion " + reference);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("colors")
    @DisplayName("DNxHR HQX codes RGB input in the matrix and decodes back to the source")
    void dnxhd(ColorInfo color) {
        var source = source();
        var options = DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQX).toBuilder().threads(1).color(color).build();
        var encoder = new DnxhdEncoder(WIDTH, HEIGHT, options);
        var data = encoder.encodeFrame(new VideoFrame(source, 0, 1, true)).data();
        var expectedVolume = color.matrix() == MatrixCoefficients.BT2020_CL ? 2 : 0;
        assertEquals(expectedVolume, (data[0x2C] >>> 1) & 3);
        var decoded = new DnxhdDecoder().decodeFrame(data, 0, data.length);
        // DNxHD signals only the BT.709 and BT.2020 color volumes, so other matrices are relabeled here
        var relabeled = switch (decoded) {
            case Yuv16Picture yuv when expectedVolume == 0 -> yuv.toBuilder().color(color).build();
            default -> decoded;
        };
        var psnr = psnr(source, relabeled);
        assertTrue(psnr > 34, color.matrix() + ": " + psnr + " dB");
    }

    @Test
    @DisplayName("DNxHD color volume 2 decodes as BT.2020 constant luminance and converts to R'G'B'")
    void colorVolumeTwo() {
        var color = new ColorInfo(MatrixCoefficients.BT2020_CL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                TransferCharacteristics.BT2020_10);
        var source = source();
        var yuv = ImageOps.toYuv16(source, ChromaFormat.YUV422, color, 10);
        var options = DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQX).toBuilder().threads(1).build();
        var data = new DnxhdEncoder(WIDTH, HEIGHT, options).encodeFrame(new VideoFrame(yuv, 0, 1, true)).data();
        assertEquals(2, (data[0x2C] >>> 1) & 3);
        var decoded = (Yuv16Picture) new DnxhdDecoder().decodeFrame(data, 0, data.length);
        assertEquals(MatrixCoefficients.BT2020_CL, decoded.color().matrix());
        assertEquals(ColorPrimaries.BT2020, decoded.color().primaries());
        // the stream carries no transfer, which the conversion resolves to the BT.2020 function
        assertEquals(TransferCharacteristics.UNSPECIFIED, decoded.color().transfer());
        var psnr = psnr(source, decoded);
        var asNcl = psnr(source, decoded.toBuilder().color(decoded.color().withMatrix(MatrixCoefficients.BT2020_NCL))
                .build());
        assertTrue(psnr > 34 && psnr > asNcl + 3, psnr + " dB, read as non-constant " + asNcl + " dB");
        var ncl = ImageOps.toYuv16(source, ChromaFormat.YUV422, color.withMatrix(MatrixCoefficients.BT2020_NCL), 10);
        var nclData = new DnxhdEncoder(WIDTH, HEIGHT, options).encodeFrame(new VideoFrame(ncl, 0, 1, true)).data();
        assertEquals(1, (nclData[0x2C] >>> 1) & 3);
    }
}
