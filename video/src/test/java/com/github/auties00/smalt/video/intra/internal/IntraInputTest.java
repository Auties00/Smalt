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
 * matrix, the BT.709 fallback of an unspecified matrix, the matrices it cannot convert with, and a
 * ProRes round trip of RGB input in the FCC matrix.
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
    @EnumSource(value = MatrixCoefficients.class, names = {"IDENTITY", "YCGCO", "BT2020_CL", "SMPTE2085",
            "CHROMA_DERIVED_NCL", "CHROMA_DERIVED_CL", "ICTCP"})
    @DisplayName("rejects RGB input for a matrix that two luma weights do not define")
    void unsupported(MatrixCoefficients matrix) {
        assertThrows(MediaException.Unsupported.class, () -> convert(solid(10, 200, 30), matrix));
    }
}
