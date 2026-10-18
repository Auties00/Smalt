package com.github.auties00.smalt.internal.color;

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
                MatrixCoefficients.CHROMA_DERIVED_CL, MatrixCoefficients.ICTCP);
        assertEquals(undefined.contains(matrix), LumaWeights.of(matrix).isEmpty());
    }

    @Test
    @DisplayName("leaves the UNSPECIFIED fallback to the caller")
    void unspecified() {
        assertEquals(Optional.empty(), LumaWeights.of(MatrixCoefficients.UNSPECIFIED));
        assertTrue(LumaWeights.of(MatrixCoefficients.UNSPECIFIED).or(() -> Optional.of(LumaWeights.BT709)).isPresent());
    }
}
