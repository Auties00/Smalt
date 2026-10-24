package com.github.auties00.smalt.internal.color;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the real-valued ITU-T H.273 models against independent evaluations of the published equations on color
 * bars and gray ramps, the published BT.2020 constant-luminance constants, and round trips.
 */
class YcbcrModelTest {
    private static final double[][] BARS = {
            {1, 1, 1}, {1, 1, 0}, {0, 1, 1}, {0, 1, 0}, {1, 0, 1}, {1, 0, 0}, {0, 0, 1}, {0, 0, 0},
            {0.75, 0.75, 0.75}, {0.75, 0.75, 0}, {0, 0.75, 0.75}, {0, 0.75, 0}, {0.75, 0, 0.75}, {0.75, 0, 0},
            {0, 0, 0.75}, {0.2, 0.5, 0.9}, {0.95, 0.1, 0.4}
    };

    private static ColorInfo color(MatrixCoefficients matrix, ColorPrimaries primaries,
                                   TransferCharacteristics transfer) {
        return new ColorInfo(matrix, ColorRange.LIMITED, primaries, transfer);
    }

    private static List<double[]> samples() {
        var samples = new ArrayList<double[]>(List.of(BARS));
        for (var i = 0; i <= 32; i++) {
            samples.add(new double[]{i / 32.0, i / 32.0, i / 32.0});
        }
        return samples;
    }

    static Stream<Arguments> descriptions() {
        var list = new ArrayList<Arguments>();
        for (var matrix : MatrixCoefficients.values()) {
            if (YcgcoR.applies(matrix)) {
                continue;
            }
            for (var transfer : List.of(TransferCharacteristics.BT2020_10, TransferCharacteristics.PQ,
                    TransferCharacteristics.HLG, TransferCharacteristics.SRGB, TransferCharacteristics.LOG100,
                    TransferCharacteristics.SMPTE428, TransferCharacteristics.UNSPECIFIED)) {
                for (var primaries : List.of(ColorPrimaries.BT2020, ColorPrimaries.BT709, ColorPrimaries.SMPTE428)) {
                    list.add(Arguments.of(color(matrix, primaries, transfer)));
                }
            }
        }
        return list.stream();
    }

    @Nested
    @DisplayName("constant luminance")
    class ConstantLuminance {
        @Test
        @DisplayName("derives the BT.2020 Table 4 divisors 1.9404, 1.5816, 1.7184 and 0.9936")
        void bt2020Constants() {
            var tf = TransferFunction.of(TransferCharacteristics.BT2020_10);
            assertEquals(1.9404, 2 * tf.encode(1 - 0.0593), 5e-4);
            assertEquals(1.5816, 2 * (1 - tf.encode(0.0593)), 5e-4);
            assertEquals(1.7184, 2 * tf.encode(1 - 0.2627), 5e-4);
            assertEquals(0.9936, 2 * (1 - tf.encode(0.2627)), 5e-4);
            // the primaries and their complements reach the ends of the color difference range
            var model = YcbcrModel.of(color(MatrixCoefficients.BT2020_CL, ColorPrimaries.BT2020,
                    TransferCharacteristics.BT2020_10));
            var out = new double[3];
            model.toYcbcr(0, 0, 1, out);
            assertEquals(0.5, out[1], 1e-12);
            model.toYcbcr(1, 1, 0, out);
            assertEquals(-0.5, out[1], 1e-12);
            model.toYcbcr(1, 0, 0, out);
            assertEquals(0.5, out[2], 1e-12);
            model.toYcbcr(0, 1, 1, out);
            assertEquals(-0.5, out[2], 1e-12);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = MatrixCoefficients.class, names = {"BT2020_CL", "CHROMA_DERIVED_CL"})
        @DisplayName("matches an independent evaluation of equations 66 to 75")
        void reference(MatrixCoefficients matrix) {
            for (var primaries : List.of(ColorPrimaries.BT2020, ColorPrimaries.SMPTE432, ColorPrimaries.FILM)) {
                for (var transfer : List.of(TransferCharacteristics.BT2020_12, TransferCharacteristics.PQ,
                        TransferCharacteristics.HLG)) {
                    var model = YcbcrModel.of(color(matrix, primaries, transfer));
                    var weights = matrix == MatrixCoefficients.BT2020_CL ? LumaWeights.BT2020
                            : LumaWeights.of(primaries);
                    for (var rgb : samples()) {
                        var expected = constantLuminance(rgb, weights.kr(), weights.kb(), transfer);
                        var actual = new double[3];
                        model.toYcbcr(rgb[0], rgb[1], rgb[2], actual);
                        assertArrayEquals(expected, actual, 1e-12);
                    }
                }
            }
        }

        private static double[] constantLuminance(double[] rgb, double kr, double kb, TransferCharacteristics t) {
            var tf = TransferFunction.of(t);
            var y = kr * tf.decode(rgb[0]) + (1 - kr - kb) * tf.decode(rgb[1]) + kb * tf.decode(rgb[2]);
            var ey = tf.encode(y);
            var nb = tf.encode(1 - kb);
            var pb = 1 - tf.encode(kb);
            var nr = tf.encode(1 - kr);
            var pr = 1 - tf.encode(kr);
            var db = rgb[2] - ey;
            var dr = rgb[0] - ey;
            return new double[]{ey, db <= 0 ? db / (2 * nb) : db / (2 * pb), dr <= 0 ? dr / (2 * nr) : dr / (2 * pr)};
        }
    }

    @Nested
    @DisplayName("ICtCp and IPT-C2")
    class Lms {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = TransferCharacteristics.class, names = {"PQ", "HLG"})
        @DisplayName("ICtCp matches an independent evaluation of BT.2100 Table 7")
        void ictcp(TransferCharacteristics transfer) {
            var model = YcbcrModel.of(color(MatrixCoefficients.ICTCP, ColorPrimaries.BT2020, transfer));
            var tf = TransferFunction.of(transfer);
            for (var rgb : samples()) {
                var r = tf.decode(rgb[0]);
                var g = tf.decode(rgb[1]);
                var b = tf.decode(rgb[2]);
                var l = tf.encode((1688 * r + 2146 * g + 262 * b) / 4096);
                var m = tf.encode((683 * r + 2951 * g + 462 * b) / 4096);
                var s = tf.encode((99 * r + 309 * g + 3688 * b) / 4096);
                double[] expected;
                if (transfer == TransferCharacteristics.PQ) {
                    expected = new double[]{0.5 * (l + m), (6610 * l - 13613 * m + 7003 * s) / 4096,
                            (17933 * l - 17390 * m - 543 * s) / 4096};
                } else {
                    expected = new double[]{0.5 * (l + m), (3625 * l - 7465 * m + 3840 * s) / 4096,
                            (9500 * l - 9212 * m - 288 * s) / 4096};
                }
                var actual = new double[3];
                model.toYcbcr(rgb[0], rgb[1], rgb[2], actual);
                assertArrayEquals(expected, actual, 1e-12);
            }
        }

        @Test
        @DisplayName("IPT-C2 matches an independent evaluation of equations 17 to 19 and 85 to 87")
        void iptC2() {
            var model = YcbcrModel.of(color(MatrixCoefficients.IPT_C2, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ));
            var tf = TransferFunction.of(TransferCharacteristics.PQ);
            for (var rgb : samples()) {
                var r = tf.decode(rgb[0]);
                var g = tf.decode(rgb[1]);
                var b = tf.decode(rgb[2]);
                var l = tf.encode((1747 * r + 2169 * g + 180 * b) / 4096);
                var m = tf.encode((673 * r + 3029 * g + 394 * b) / 4096);
                var s = tf.encode((50 * r + 207 * g + 3839 * b) / 4096);
                var expected = new double[]{(1638 * l + 1638 * m + 820 * s) / 4096,
                        (18248 * l - 19870 * m + 1622 * s) / 4096, (3300 * l + 1463 * m - 4763 * s) / 4096};
                var actual = new double[3];
                model.toYcbcr(rgb[0], rgb[1], rgb[2], actual);
                assertArrayEquals(expected, actual, 1e-12);
            }
        }

        @Test
        @DisplayName("an unspecified transfer is PQ")
        void unspecified() {
            var pq = YcbcrModel.of(color(MatrixCoefficients.ICTCP, ColorPrimaries.BT2020, TransferCharacteristics.PQ));
            var unspecified = YcbcrModel.of(color(MatrixCoefficients.ICTCP, ColorPrimaries.UNSPECIFIED,
                    TransferCharacteristics.UNSPECIFIED));
            assertEquals(TransferCharacteristics.PQ, unspecified.transfer().transfer());
            var a = new double[3];
            var b = new double[3];
            pq.toYcbcr(0.3, 0.6, 0.1, a);
            unspecified.toYcbcr(0.3, 0.6, 0.1, b);
            assertArrayEquals(a, b, 0);
        }
    }

    @Nested
    @DisplayName("linear models")
    class Linear {
        @Test
        @DisplayName("SMPTE ST 2085 follows equations 76 to 78")
        void ydzdx() {
            var model = YcbcrModel.of(color(MatrixCoefficients.SMPTE2085, ColorPrimaries.SMPTE428,
                    TransferCharacteristics.SMPTE428));
            for (var xyz : samples()) {
                var out = new double[3];
                model.toYcbcr(xyz[0], xyz[1], xyz[2], out);
                assertArrayEquals(new double[]{xyz[1], (0.986566 * xyz[2] - xyz[1]) / 2,
                        (xyz[0] - 0.991902 * xyz[1]) / 2}, out, 1e-15);
            }
        }

        @Test
        @DisplayName("chromaticity-derived non-constant luminance weighs luma with the derived weights")
        void derived() {
            var weights = LumaWeights.of(ColorPrimaries.SMPTE431);
            var model = YcbcrModel.of(color(MatrixCoefficients.CHROMA_DERIVED_NCL, ColorPrimaries.SMPTE431,
                    TransferCharacteristics.BT709));
            for (var rgb : samples()) {
                var y = weights.kr() * rgb[0] + weights.kg() * rgb[1] + weights.kb() * rgb[2];
                var out = new double[3];
                model.toYcbcr(rgb[0], rgb[1], rgb[2], out);
                assertArrayEquals(new double[]{y, 0.5 * (rgb[2] - y) / (1 - weights.kb()),
                        0.5 * (rgb[0] - y) / (1 - weights.kr())}, out, 1e-15);
            }
        }

        @Test
        @DisplayName("quantizes as equations 27 to 38")
        void quantization() {
            var bt709 = YcbcrModel.of(ColorInfo.BT709_LIMITED);
            assertEquals(219 * 4, bt709.scale(0, ColorRange.LIMITED, 10));
            assertEquals(224 * 4, bt709.scale(1, ColorRange.LIMITED, 10));
            assertEquals(64, bt709.offset(0, ColorRange.LIMITED, 10));
            assertEquals(512, bt709.offset(2, ColorRange.LIMITED, 10));
            assertEquals(4095, bt709.scale(2, ColorRange.FULL, 12));
            assertEquals(0, bt709.offset(0, ColorRange.FULL, 12));
            assertEquals(2048, bt709.offset(1, ColorRange.FULL, 12));
            var identity = YcbcrModel.of(ColorInfo.BT709_LIMITED.withMatrix(MatrixCoefficients.IDENTITY));
            assertEquals(219, identity.scale(1, ColorRange.LIMITED, 8));
            assertEquals(16, identity.offset(2, ColorRange.LIMITED, 8));
            assertEquals(0, identity.offset(2, ColorRange.FULL, 8));
            var ycgco = YcbcrModel.of(ColorInfo.BT709_LIMITED.withMatrix(MatrixCoefficients.YCGCO));
            assertEquals(219 * 4, ycgco.scale(1, ColorRange.LIMITED, 10));
            assertEquals(512, ycgco.offset(1, ColorRange.LIMITED, 10));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("descriptions")
    @DisplayName("toRgb inverts toYcbcr and keeps grays neutral")
    void roundTrip(ColorInfo color) {
        var model = YcbcrModel.of(color);
        var random = new SplittableRandom(color.hashCode());
        var ycbcr = new double[3];
        var rgb = new double[3];
        var matrix = color.matrix().effective();
        var mixesLinearLight = matrix == MatrixCoefficients.BT2020_CL || matrix == MatrixCoefficients.CHROMA_DERIVED_CL
                || matrix == MatrixCoefficients.ICTCP || matrix == MatrixCoefficients.IPT_C2;
        // LOG100 encodes every linear value below 0.01 as 0, so a mix of linear light can lose the input
        var lossy = mixesLinearLight && color.transfer() == TransferCharacteristics.LOG100;
        for (var i = 0; i < (lossy ? 0 : 300); i++) {
            var input = i < BARS.length ? BARS[i] : new double[]{random.nextDouble(), random.nextDouble(),
                    random.nextDouble()};
            model.toYcbcr(input[0], input[1], input[2], ycbcr);
            model.toRgb(ycbcr[0], ycbcr[1], ycbcr[2], rgb);
            // a zero channel comes back as a rounding residue of the linear mix, which the steep start of PQ, HLG
            // and the SMPTE ST 428-1 power raises to a few millionths
            assertArrayEquals(input, rgb, mixesLinearLight ? 2e-5 : 1e-12, () -> color + " " + java.util.Arrays.toString(input));
        }
        if (matrix != MatrixCoefficients.IDENTITY && matrix != MatrixCoefficients.SMPTE2085) {
            for (var i = 0; i <= 16; i++) {
                model.toYcbcr(i / 16.0, i / 16.0, i / 16.0, ycbcr);
                // PQ encodes black as c1^m, about 7.3e-7, so grays are neutral to that precision
                assertEquals(0, ycbcr[1], 1e-6);
                assertEquals(0, ycbcr[2], 1e-6);
                assertEquals(i / 16.0, ycbcr[0], 1e-6);
            }
        }
    }

    @Test
    @DisplayName("rejects the integer YCgCo-R matrices")
    void integer() {
        assertThrows(IllegalArgumentException.class, () -> YcbcrModel.of(ColorInfo.BT709_LIMITED
                .withMatrix(MatrixCoefficients.YCGCO_RE)));
        assertThrows(IllegalArgumentException.class, () -> YcbcrModel.of(ColorInfo.BT709_LIMITED
                .withMatrix(MatrixCoefficients.YCGCO_RO)));
    }
}
