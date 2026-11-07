package com.github.auties00.smalt.video.intra.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.color.LumaWeights;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.prores.ProResDecoder;
import com.github.auties00.smalt.video.prores.ProResEncoder;
import com.github.auties00.smalt.video.prores.ProResEncoderOptions;
import com.github.auties00.smalt.video.prores.ProResProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the RGB to Y'CbCr conversion of the intra encoders' input: the luma weights of every
 * matrix, the BT.709 fallback of an unspecified matrix, the matrices it converts as the core conversion
 * does, and a ProRes round trip of RGB input in the FCC matrix.
 */
@DisplayName("intra encoder input")
class IntraInputTest {
    static RgbaPicture solid(int red, int green, int blue) {
        var pixels = new byte[16 * 16 * 4];
        for (var i = 0; i < pixels.length; i += 4) {
            pixels[i] = (byte) red;
            pixels[i + 1] = (byte) green;
            pixels[i + 2] = (byte) blue;
            pixels[i + 3] = (byte) 255;
        }
        return RgbaPicture.of(16, 16, 64, pixels, false);
    }

    static IntraPlanes convert(RgbaPicture picture, MatrixCoefficients matrix) {
        return IntraInput.yuv(picture, ChromaFormat.YUV444, 10, ColorInfo.BT709_LIMITED.withMatrix(matrix), 0, 16, 16,
                1, null);
    }

    @ParameterizedTest
    @EnumSource(value = MatrixCoefficients.class, names = {"BT601", "BT470BG", "BT709", "FCC", "SMPTE240M", "BT2020_NCL"})
    @DisplayName("converts with the luma weights of the requested matrix")
    void weights(MatrixCoefficients matrix) {
        var weights = LumaWeights.of(matrix).orElseThrow();
        for (var channel = 0; channel < 3; channel++) {
            var planes = convert(solid(channel == 0 ? 255 : 0, channel == 1 ? 255 : 0, channel == 2 ? 255 : 0), matrix);
            var weight = channel == 0 ? weights.kr() : channel == 1 ? weights.kg() : weights.kb();
            var expected = (short) Math.round((16 + 219 * weight) * 4);
            assertEquals(expected, planes.first()[0], matrix + " channel " + channel);
            assertEquals(matrix, planes.color().matrix());
        }
    }

    @Test
    @DisplayName("converts an unspecified matrix with BT.709 and keeps it unspecified")
    void unspecified() {
        var green = solid(0, 255, 0);
        var planes = convert(green, MatrixCoefficients.UNSPECIFIED);
        assertEquals(0, Arrays.compare(convert(green, MatrixCoefficients.BT709).first(), planes.first()));
        assertEquals(MatrixCoefficients.UNSPECIFIED, planes.color().matrix());
    }

    static RgbaPicture saturated(int width, int height) {
        var pixels = new byte[width * height * 4];
        for (var i = 0; i < pixels.length; i += 4) {
            var x = i / 4 % width;
            var y = i / 4 / width;
            pixels[i] = (byte) (x * 255 / (width - 1));
            pixels[i + 1] = (byte) ((x + y) % 2 == 0 ? 255 - y * 255 / (height - 1) : 0);
            pixels[i + 2] = (byte) (y * 255 / (height - 1));
            pixels[i + 3] = (byte) 255;
        }
        return RgbaPicture.of(width, height, width * 4, pixels, false);
    }

    static double roundTrip(RgbaPicture source, MatrixCoefficients encodeWith) {
        var options = ProResEncoderOptions.of(ProResProfile.PRORES_4444).toBuilder().quantizationIndex(1)
                .color(ColorInfo.BT709_LIMITED.withMatrix(encodeWith)).threads(1).build();
        try (var encoder = new ProResEncoder(source.width(), source.height(), options)) {
            var data = encoder.encodeFrame(new VideoFrame(source, 0, 1, true)).data();
            var decoded = (Yuv16Picture) new ProResDecoder().decodeFrame(data, 0, data.length);
            var fcc = decoded.toBuilder().color(decoded.color().withMatrix(MatrixCoefficients.FCC)).build();
            var rgba = ImageOps.toRgba(fcc);
            var squared = 0.0;
            for (var y = 0; y < source.height(); y++) {
                for (var x = 0; x < source.width(); x++) {
                    for (var c = 0; c < 3; c++) {
                        var difference = (source.pixels()[y * source.stride() + x * 4 + c] & 0xFF)
                                - (rgba.pixels()[y * rgba.stride() + x * 4 + c] & 0xFF);
                        squared += difference * difference;
                    }
                }
            }
            return 10 * Math.log10(255.0 * 255.0 * 3 * source.width() * source.height() / Math.max(squared, 1e-9));
        }
    }

    @Test
    @DisplayName("RGB input signaled as FCC decodes closer with the FCC weights than with BT.601's")
    void fccRoundTrip() {
        var source = saturated(64, 48);
        var fcc = roundTrip(source, MatrixCoefficients.FCC);
        var bt601 = roundTrip(source, MatrixCoefficients.BT601);
        assertTrue(fcc > bt601 + 0.5, "FCC " + fcc + " dB, BT.601 weights " + bt601 + " dB");
    }

    @ParameterizedTest
    @EnumSource(value = MatrixCoefficients.class, names = {"YCGCO", "BT2020_CL", "SMPTE2085",
            "CHROMA_DERIVED_CL", "ICTCP", "IPT_C2", "YCGCO_RE", "YCGCO_RO"})
    @DisplayName("converts RGB input in a matrix weighted luma does not define as the core conversion does")
    void exact(MatrixCoefficients matrix) {
        var source = saturated(30, 18);
        var color = ColorInfo.BT709_LIMITED.withMatrix(matrix);
        for (var format : new ChromaFormat[]{ChromaFormat.YUV444, ChromaFormat.YUV422}) {
            var planes = IntraInput.yuv(source, format, 10, color, 0, 32, 18, 1, null);
            var expected = ImageOps.toYuv16(source, format, color, 10);
            assertEquals(color, planes.color());
            for (var y = 0; y < 18; y++) {
                for (var x = 0; x < 30; x++) {
                    assertEquals(expected.y()[y * expected.yStride() + x], planes.first()[y * 32 + x], matrix + " luma");
                }
                for (var x = 0; x < expected.chromaWidth(); x++) {
                    assertEquals(expected.u()[y * expected.uvStride() + x], planes.second()[y * planes.chromaStride() + x]);
                    assertEquals(expected.v()[y * expected.uvStride() + x], planes.third()[y * planes.chromaStride() + x]);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0} {1} at {2} bits")
    @org.junit.jupiter.params.provider.CsvSource({"YCGCO_RE, LIMITED, 8", "YCGCO_RE, FULL, 10", "YCGCO_RO, LIMITED, 9",
            "YCGCO, FULL, 10", "IDENTITY, FULL, 10"})
    @DisplayName("converts planar input whose samples a per-sample mapping would recolor as the core conversion does")
    void planar(MatrixCoefficients matrix, com.github.auties00.smalt.image.ColorRange range, int sourceDepth) {
        var color = ColorInfo.BT709_LIMITED.withMatrix(matrix).withRange(range);
        var source = ImageOps.toYuv16(saturated(30, 18), ChromaFormat.YUV444, color, sourceDepth);
        var limited = color.withRange(com.github.auties00.smalt.image.ColorRange.LIMITED);
        var expected = ImageOps.toYuv16(source, ChromaFormat.YUV444, limited, 12);
        for (var picture : java.util.List.<com.github.auties00.smalt.image.Picture>of(source,
                ImageOps.toEightBit(source))) {
            if (picture instanceof com.github.auties00.smalt.image.YuvPicture && sourceDepth != 8) {
                continue;
            }
            var planes = IntraInput.yuv(picture, ChromaFormat.YUV444, 12, ColorInfo.BT709_LIMITED, 0, 32, 18, 1, null);
            assertEquals(limited, planes.color());
            for (var y = 0; y < 18; y++) {
                for (var x = 0; x < 30; x++) {
                    var at = y * expected.yStride() + x;
                    assertEquals(expected.y()[at], planes.first()[y * 32 + x], matrix + " luma");
                    assertEquals(expected.u()[at], planes.second()[y * planes.chromaStride() + x], matrix + " Cb");
                    assertEquals(expected.v()[at], planes.third()[y * planes.chromaStride() + x], matrix + " Cr");
                }
            }
        }
        // the conversion keeps the colors: 8-bit R'G'B' within one code value of the source
        var back = ImageOps.toRgba(expected);
        var reference = ImageOps.toRgba(source);
        for (var i = 0; i < 30 * 18 * 4; i++) {
            assertTrue(Math.abs((back.pixels()[i] & 0xFF) - (reference.pixels()[i] & 0xFF)) <= 1, matrix + " " + i);
        }
        assertEquals(null, IntraInput.yuvDeferred(source, ChromaFormat.YUV444, 12, 32, 32, null));
    }

    @Test
    @DisplayName("rejects RGB input in the identity matrix, which the intra formats cannot signal for Y'CbCr")
    void identity() {
        var color = ColorInfo.BT709_LIMITED.withMatrix(MatrixCoefficients.IDENTITY);
        assertThrows(MediaException.Unsupported.class,
                () -> IntraInput.yuv(saturated(30, 18), ChromaFormat.YUV444, 10, color, 0, 32, 18, 1, null));
        var options = ProResEncoderOptions.of(ProResProfile.PRORES_4444).toBuilder().threads(1).color(color).build();
        try (var encoder = new ProResEncoder(30, 18, options)) {
            assertThrows(MediaException.Unsupported.class,
                    () -> encoder.encodeFrame(new VideoFrame(saturated(30, 18), 0, 1, true)));
        }
        var yuv = ImageOps.toYuv16(saturated(30, 18), ChromaFormat.YUV444, ColorInfo.BT709_LIMITED, 10);
        assertEquals(ColorInfo.BT709_LIMITED,
                IntraInput.yuv(yuv, ChromaFormat.YUV444, 10, color, 0, 32, 18, 1, null).color());
    }

    @Test
    @DisplayName("converts the chromaticity-derived non-constant-luminance matrix with the weights of the primaries")
    void chromaticityDerived() {
        var color = ColorInfo.BT709_LIMITED.withMatrix(MatrixCoefficients.CHROMA_DERIVED_NCL)
                .withPrimaries(com.github.auties00.smalt.image.ColorPrimaries.SMPTE432);
        var weights = LumaWeights.of(com.github.auties00.smalt.image.ColorPrimaries.SMPTE432);
        var planes = IntraInput.yuv(solid(0, 0, 255), ChromaFormat.YUV444, 10, color, 0, 16, 16, 1, null);
        assertEquals((short) Math.round((16 + 219 * weights.kb()) * 4), planes.first()[0]);
        assertEquals(java.util.Optional.of(weights), IntraInput.weights(color));
        assertTrue(IntraInput.weights(ColorInfo.BT709_LIMITED.withMatrix(MatrixCoefficients.ICTCP)).isEmpty());
    }
}
