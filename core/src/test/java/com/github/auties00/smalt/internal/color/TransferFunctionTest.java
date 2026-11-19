package com.github.auties00.smalt.internal.color;

import com.github.auties00.smalt.image.TransferCharacteristics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the ITU-T H.273 Table 3 transfer functions against values computed by hand from the published formulas.
 */
class TransferFunctionTest {
    private static final double ALPHA = 1.099296826809442;
    private static final double BETA = 0.018053968510807;

    @Nested
    @DisplayName("golden values")
    class Golden {
        @ParameterizedTest(name = "{0}({1}) = {2}")
        @CsvSource({
                "LOG100, 1, 1",
                "LOG100, 0.1, 0.5",
                "LOG100, 0.01, 0",
                "LOG100, 0.005, 0",
                "LOG316, 1, 1",
                "LOG316, 0.1, 0.6",
                "LOG316, 0.0031622776601683794, 0",
                "LOG316, 0.003, 0",
                "SMPTE428, 1, 0.967042675317934",
                "SMPTE428, 1.0910416666666667, 1",
                "SMPTE428, 0, 0",
                "PQ, 1, 1",
                "PQ, 0.01, 0.508078421517399",
                "PQ, 0.1, 0.751827096247041",
                "PQ, 0, 7.309559025783966E-7",
                "HLG, 0.08333333333333333, 0.5",
                "HLG, 1, 0.999999995536569",
                "HLG, 0.25, 0.738549268065827",
                "SRGB, 0.0031308, 0.04044990748269014",
                "SRGB, 0.003, 0.03876",
                "SRGB, 1, 1",
                "LINEAR, 0.37, 0.37",
                "GAMMA22, 0.25, 0.532520544719981",
                "GAMMA28, 0.25, 0.609506827102238",
                "BT709, 1, 1",
                "BT709, 0.018053968510807, 0.081242858298632",
                "BT2020_12, 0.5, 0.705435553055618",
                "SMPTE240M, 1, 1",
                "SMPTE240M, 0.01, 0.04",
                "BT1361, 1.2, 1.093994640179462",
                "BT1361, -0.004, -0.018",
                "BT1361, -0.25, -0.25",
                "IEC61966_2_4, -0.5, -0.705435553055618",
                "UNSPECIFIED, 0.5, 0.705435553055618"
        })
        @DisplayName("encode matches the published formula")
        void encode(TransferCharacteristics transfer, double linear, double signal) {
            assertEquals(signal, TransferFunction.of(transfer).encode(linear), 1e-12);
        }

        @Test
        @DisplayName("the BT.709 family uses the H.273 alpha and beta in its power segment")
        void bt709Power() {
            for (var l : new double[]{0.02, 0.1, 0.5, 0.9}) {
                var expected = ALPHA * Math.pow(l, 0.45) - (ALPHA - 1);
                for (var transfer : new TransferCharacteristics[]{TransferCharacteristics.BT709,
                        TransferCharacteristics.SMPTE170M, TransferCharacteristics.BT2020_10,
                        TransferCharacteristics.BT2020_12, TransferCharacteristics.IEC61966_2_4,
                        TransferCharacteristics.BT1361}) {
                    assertEquals(expected, TransferFunction.of(transfer).encode(l), 1e-15);
                }
            }
            assertEquals(4.5 * 0.01, TransferFunction.of(TransferCharacteristics.BT709).encode(0.01), 1e-15);
        }

        @Test
        @DisplayName("BT.1361 joins its negative segments at minus beta over four")
        void bt1361Negative() {
            var function = TransferFunction.of(TransferCharacteristics.BT1361);
            var gamma = BETA / 4;
            var below = function.encode(-gamma - 1e-12);
            var above = function.encode(-gamma + 1e-12);
            assertEquals(-4.5 * gamma, below, 1e-10);
            assertEquals(below, above, 1e-10);
            assertEquals(-(ALPHA * Math.pow(0.4, 0.45) - (ALPHA - 1)) / 4, function.encode(-0.1), 1e-15);
        }

        @Test
        @DisplayName("an unspecified transfer is BT.709")
        void unspecified() {
            assertSame(TransferCharacteristics.BT709, TransferFunction.of(TransferCharacteristics.UNSPECIFIED).transfer());
        }
    }

    @Nested
    @DisplayName("shape")
    class Shape {
        @ParameterizedTest(name = "{0}")
        @EnumSource(TransferCharacteristics.class)
        @DisplayName("decode inverts encode over the nominal and extended ranges")
        void roundTrip(TransferCharacteristics transfer) {
            var function = TransferFunction.of(transfer);
            var floor = switch (transfer) {
                case LOG100 -> 0.01;
                case LOG316 -> Math.sqrt(10) / 1000;
                default -> 0.0;
            };
            // a LOG100 or LOG316 signal of 0 decodes to 0, not to the bottom of the logarithmic segment
            for (var i = floor > 0 ? 1 : 0; i <= 2000; i++) {
                var l = floor + (1.2 - floor) * i / 2000.0;
                if (transfer == TransferCharacteristics.PQ && l > 1) {
                    break;
                }
                var signal = function.encode(l);
                assertEquals(l, function.decode(signal), 1e-12 + 1e-12 * l, transfer + " at " + l);
                if (floor == 0 && l > 0) {
                    assertEquals(-l, function.decode(function.encode(-l)), 1e-12 + 1e-12 * l);
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(TransferCharacteristics.class)
        @DisplayName("encode is non-decreasing and maps black to black")
        void monotonic(TransferCharacteristics transfer) {
            var function = TransferFunction.of(transfer);
            var previous = function.encode(-0.25);
            for (var i = -249; i <= 1000; i++) {
                var value = function.encode(i / 1000.0);
                assertTrue(value >= previous, transfer + " at " + i);
                previous = value;
            }
            if (transfer != TransferCharacteristics.PQ) {
                assertEquals(0, function.encode(0), 0);
            }
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"BT709, 0.018053968510807, 4.5", "SMPTE240M, 0.022821585529445, 4.0"})
        @DisplayName("the linear and power segments join with continuous value and slope")
        void continuity(TransferCharacteristics transfer, double beta, double slope) {
            var function = TransferFunction.of(transfer);
            var h = 1e-7;
            var left = function.encode(beta - h);
            var right = function.encode(beta + h);
            assertEquals(slope * beta, function.encode(beta), 1e-9);
            assertEquals((right - function.encode(beta)) / h, slope, 1e-5);
            assertEquals((function.encode(beta) - left) / h, slope, 1e-5);
        }

        @Test
        @DisplayName("a PQ signal above 1 decodes as the peak")
        void pqClamp() {
            var function = TransferFunction.of(TransferCharacteristics.PQ);
            assertEquals(1, function.decode(1.4), 1e-12);
            assertEquals(1, function.decode(1), 1e-12);
        }
    }
}
