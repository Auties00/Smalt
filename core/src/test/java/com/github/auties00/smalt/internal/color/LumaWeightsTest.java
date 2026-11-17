package com.github.auties00.smalt.internal.color;

import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.MatrixCoefficients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the luma weight source against the weights the color conversion of core uses.
 */
class LumaWeightsTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource({"BT601, 0.299, 0.114", "BT470BG, 0.299, 0.114", "BT709, 0.2126, 0.0722", "FCC, 0.30, 0.11",
            "SMPTE240M, 0.212, 0.087", "BT2020_NCL, 0.2627, 0.0593"})
    @DisplayName("gives the weights of ITU-T H.273 Table 4 bit for bit")
    void weights(MatrixCoefficients matrix, double kr, double kb) {
        var weights = LumaWeights.of(matrix).orElseThrow();
        assertEquals(Double.doubleToLongBits(kr), Double.doubleToLongBits(weights.kr()));
        assertEquals(Double.doubleToLongBits(kb), Double.doubleToLongBits(weights.kb()));
        assertEquals(1 - kr - kb, weights.kg());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MatrixCoefficients.class)
    @DisplayName("is empty exactly for the matrices two weights do not define")
    void empty(MatrixCoefficients matrix) {
        var undefined = Set.of(MatrixCoefficients.IDENTITY, MatrixCoefficients.UNSPECIFIED, MatrixCoefficients.YCGCO,
                MatrixCoefficients.BT2020_CL, MatrixCoefficients.SMPTE2085, MatrixCoefficients.CHROMA_DERIVED_NCL,
                MatrixCoefficients.CHROMA_DERIVED_CL, MatrixCoefficients.ICTCP, MatrixCoefficients.IPT_C2,
                MatrixCoefficients.YCGCO_RE, MatrixCoefficients.YCGCO_RO);
        assertEquals(undefined.contains(matrix), LumaWeights.of(matrix).isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ColorPrimaries.class, names = "UNSPECIFIED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("derives the weights of primaries as the luminance row of their RGB to XYZ matrix")
    void derived(ColorPrimaries primaries) {
        var weights = LumaWeights.of(primaries);
        var luminance = luminanceRow(primaries);
        assertEquals(luminance[0], weights.kr(), 1e-12);
        assertEquals(luminance[2], weights.kb(), 1e-12);
        assertEquals(luminance[1], weights.kg(), 1e-12);
    }

    @Test
    @DisplayName("derives the published weights of BT.709 and BT.2020 to four decimals")
    void derivedPublished() {
        var bt709 = LumaWeights.of(ColorPrimaries.BT709);
        assertEquals(0.2126, bt709.kr(), 5e-5);
        assertEquals(0.0722, bt709.kb(), 5e-5);
        var bt2020 = LumaWeights.of(ColorPrimaries.BT2020);
        assertEquals(0.2627, bt2020.kr(), 5e-5);
        assertEquals(0.0593, bt2020.kb(), 5e-5);
        assertEquals(bt709, LumaWeights.of(ColorPrimaries.UNSPECIFIED));
        var xyz = LumaWeights.of(ColorPrimaries.SMPTE428);
        assertEquals(0, xyz.kr(), 1e-15);
        assertEquals(0, xyz.kb(), 1e-15);
    }

    @Test
    @DisplayName("resolves the chromaticity-derived and constant-luminance matrices with the primaries")
    void withPrimaries() {
        assertEquals(Optional.of(LumaWeights.of(ColorPrimaries.SMPTE432)),
                LumaWeights.of(MatrixCoefficients.CHROMA_DERIVED_NCL, ColorPrimaries.SMPTE432));
        assertEquals(Optional.of(LumaWeights.of(ColorPrimaries.FILM)),
                LumaWeights.of(MatrixCoefficients.CHROMA_DERIVED_CL, ColorPrimaries.FILM));
        assertEquals(Optional.of(LumaWeights.BT2020),
                LumaWeights.of(MatrixCoefficients.BT2020_CL, ColorPrimaries.BT709));
        assertEquals(Optional.of(LumaWeights.BT709), LumaWeights.of(MatrixCoefficients.BT709, ColorPrimaries.BT2020));
        assertEquals(Optional.empty(), LumaWeights.of(MatrixCoefficients.ICTCP, ColorPrimaries.BT2020));
    }

    // Y row of the normalized primary matrix: solves for the primary scales that sum to the white point, a
    // derivation independent of the closed form of H.273 equations 39 and 40.
    private static double[] luminanceRow(ColorPrimaries primaries) {
        var points = new double[4][];
        var list = new java.util.ArrayList<Chromaticity>();
        list.add(primaries.red().orElseThrow());
        list.add(primaries.green().orElseThrow());
        list.add(primaries.blue().orElseThrow());
        list.add(primaries.white().orElseThrow());
        for (var i = 0; i < 4; i++) {
            var c = list.get(i);
            points[i] = new double[]{c.x(), c.y(), 1 - c.x() - c.y()};
        }
        var w = points[3];
        var white = new double[]{w[0] / w[1], 1, w[2] / w[1]};
        var m = new double[3][3];
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                m[row][column] = points[column][row];
            }
        }
        var scales = solve(m, white);
        return new double[]{scales[0] * m[1][0], scales[1] * m[1][1], scales[2] * m[1][2]};
    }

    private static double[] solve(double[][] m, double[] v) {
        var a = new double[3][4];
        for (var i = 0; i < 3; i++) {
            System.arraycopy(m[i], 0, a[i], 0, 3);
            a[i][3] = v[i];
        }
        for (var c = 0; c < 3; c++) {
            var pivot = c;
            for (var r = c + 1; r < 3; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[pivot][c])) {
                    pivot = r;
                }
            }
            var t = a[c];
            a[c] = a[pivot];
            a[pivot] = t;
            for (var r = 0; r < 3; r++) {
                if (r != c) {
                    var f = a[r][c] / a[c][c];
                    for (var k = c; k < 4; k++) {
                        a[r][k] -= f * a[c][k];
                    }
                }
            }
        }
        return new double[]{a[0][3] / a[0][0], a[1][3] / a[1][1], a[2][3] / a[2][2]};
    }

    @Test
    @DisplayName("leaves the UNSPECIFIED fallback to the caller")
    void unspecified() {
        assertEquals(Optional.empty(), LumaWeights.of(MatrixCoefficients.UNSPECIFIED));
        assertTrue(LumaWeights.of(MatrixCoefficients.UNSPECIFIED).or(() -> Optional.of(LumaWeights.BT709)).isPresent());
    }
}
