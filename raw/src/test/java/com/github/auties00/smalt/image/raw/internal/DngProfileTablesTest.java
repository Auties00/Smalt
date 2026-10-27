package com.github.auties00.smalt.image.raw.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the DNG profile tables against values computed by hand: the gain table maps of DNG 1.6 and 1.7 (parsing
 * in either byte order, the four gain encodings, the spatial and input interpolation) and the masked RGB tables
 * (parsing, the transfer curves, trilinear lookup, gamut extension), plus the four-color projection.
 */
class DngProfileTablesTest {
    /**
     * Writes a gain table map: the header, then for the second version the data type, gamma and gain range, then
     * the gains as floats, or as the data type encodes them.
     */
    static byte[] gainMap(ByteOrder order, int pointsV, int pointsH, double spacingV, double spacingH, double originV,
                          double originH, int pointsN, float[] weights, int type, float gamma, float minimum,
                          float maximum, double[] gains) {
        var second = type >= 0;
        var size = type == 0 ? 1 : type == 3 || type < 0 ? 4 : 2;
        var b = ByteBuffer.allocate((second ? 80 : 64) + size * gains.length).order(order);
        b.putInt(pointsV).putInt(pointsH).putDouble(spacingV).putDouble(spacingH).putDouble(originV).putDouble(originH)
                .putInt(pointsN);
        for (var weight : weights) {
            b.putFloat(weight);
        }
        if (second) {
            b.putInt(type).putFloat(gamma).putFloat(minimum).putFloat(maximum);
        }
        for (var gain : gains) {
            switch (type) {
                case 0 -> b.put((byte) Math.round((gain - minimum) / (maximum - minimum) * 255));
                case 1 -> b.putShort((short) Math.round((gain - minimum) / (maximum - minimum) * 65535));
                case 2 -> b.putShort(Float.floatToFloat16((float) gain));
                default -> b.putFloat((float) gain);
            }
        }
        return b.array();
    }

    static final float[] RED = {1, 0, 0, 0, 0};

    @Nested
    @DisplayName("gain table maps")
    class GainMaps {
        @ParameterizedTest(name = "little endian {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("the first version parses in either byte order")
        void byteOrder(boolean little) {
            var data = gainMap(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN, 2, 3, 0.5, 0.25, 0.1, -0.2, 4,
                    RED, -1, 1, 0, 0, new double[24]);
            var map = GainTableMap.parse(data, false);
            assertNotNull(map);
            assertEquals(2, map.pointsV);
            assertEquals(3, map.pointsH);
            assertEquals(4, map.pointsN);
            assertEquals(0.5, map.spacingV);
            assertEquals(-0.2, map.originH);
            assertArrayEquals(RED, map.weights);
        }

        @ParameterizedTest(name = "data type {0}")
        @ValueSource(ints = {0, 1, 2, 3})
        @DisplayName("the second version decodes every gain encoding")
        void encodings(int type) {
            var gains = new double[]{0.5, 1, 1.5, 2};
            var data = gainMap(ByteOrder.BIG_ENDIAN, 1, 1, 1, 1, 0, 0, 4, RED, type, 1, 0.5f, 2, gains);
            var map = GainTableMap.parse(data, true);
            assertNotNull(map);
            for (var index = 0; index < 4; index++) {
                assertEquals(gains[index], map.gains[index], type == 0 ? 0.01 : 1e-3);
            }
        }

        @Test
        @DisplayName("invalid maps are ignored")
        void invalid() {
            assertNull(GainTableMap.parse(null, false));
            assertNull(GainTableMap.parse(new byte[63], false));
            var negative = gainMap(ByteOrder.BIG_ENDIAN, 1, 1, 1, 1, 0, 0, 2, RED, -1, 1, 0, 0, new double[]{1, -1});
            assertNull(GainTableMap.parse(negative, false));
            var truncated = gainMap(ByteOrder.BIG_ENDIAN, 2, 2, 1, 1, 0, 0, 2, RED, -1, 1, 0, 0, new double[8]);
            assertNull(GainTableMap.parse(java.util.Arrays.copyOf(truncated, truncated.length - 4), false));
            var badGamma = gainMap(ByteOrder.BIG_ENDIAN, 1, 1, 1, 1, 0, 0, 2, RED, 3, 8, 0, 0, new double[]{1, 1});
            assertNull(GainTableMap.parse(badGamma, true));
            var zeroSpacing = gainMap(ByteOrder.BIG_ENDIAN, 1, 1, 0, 1, 0, 0, 2, RED, -1, 1, 0, 0, new double[]{1, 1});
            assertNull(GainTableMap.parse(zeroSpacing, false));
        }

        @Test
        @DisplayName("the gain is looked up at the input times MapPointsN and interpolated between tables")
        void lookup() {
            // two tables across, at u = 0 and u = 1; the left one's gain is its index, the right one twice that
            var n = 5;
            var gains = new double[2 * n];
            for (var k = 0; k < n; k++) {
                gains[k] = k;
                gains[n + k] = 2 * k;
            }
            var map = GainTableMap.parse(gainMap(ByteOrder.BIG_ENDIAN, 1, 2, 1, 1, 0, 0, n, RED, -1, 1, 0, 0, gains), false);
            assertNotNull(map);
            var r = new float[]{0.3f, 0.3f, 0.3f, 1.2f};
            var g = new float[]{0.1f, 0.1f, 0.1f, 0.1f};
            var b = new float[]{0.2f, 0.2f, 0.2f, 0.2f};
            var tables = new float[2 * n];
            // pixels at u = 0, 0.5, 1 and 1.5 (past the grid, which replicates its edge)
            map.apply(r, g, b, 0, 4, 0, 0.5, 0.7, tables);
            // the input 0.3 indexes 1.5: gain 1.5 on the left, 3 on the right
            assertEquals(0.3f * 1.5f, r[0], 1e-6);
            assertEquals(0.1f * 1.5f, g[0], 1e-6);
            assertEquals(0.3f * 2.25f, r[1], 1e-6);
            assertEquals(0.3f * 3f, r[2], 1e-6);
            // an input clamped to 1 indexes past the last entry, which holds
            assertEquals(1.2f * 8f, r[3], 1e-5);
        }

        @Test
        @DisplayName("the second version raises the input to its gamma")
        void gamma() {
            var gains = new double[]{0, 1, 2, 3, 4};
            var map = GainTableMap.parse(gainMap(ByteOrder.BIG_ENDIAN, 1, 1, 1, 1, 0, 0, 5, RED, 3, 2, 0, 0, gains), true);
            assertNotNull(map);
            var r = new float[]{0.7f, 0.5f};
            map.apply(r, new float[2], new float[2], 1, 1, 0.5, 0, 0.5, new float[5]);
            // 0.5 squared is 0.25, which indexes 1.25; the pixel before the offset is left alone
            assertEquals(0.5f * 1.25f, r[1], 1e-6);
            assertEquals(0.7f, r[0]);
        }
    }

    /**
     * Writes an RGBTables tag of tables of two divisions per axis.
     */
    static byte[] rgbTables(ByteOrder order, int method, Object[]... tables) {
        var out = ByteBuffer.allocate(4096).order(order);
        out.putInt(tables.length).putInt(method);
        for (var table : tables) {
            var name = (String) table[0];
            var bytes = name == null ? new byte[0] : name.getBytes(StandardCharsets.US_ASCII);
            out.putShort((short) bytes.length).put(bytes);
            var divisions = (int) table[1];
            out.put((byte) divisions).put((byte) 2).put((byte) (int) table[2]).put((byte) (int) table[3])
                    .put((byte) (int) table[4]);
            for (var value : (float[]) table[5]) {
                out.putFloat(value);
            }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }

    static float[] identity(int divisions) {
        var entries = new float[divisions * divisions * divisions * 3];
        for (var r = 0; r < divisions; r++) {
            for (var g = 0; g < divisions; g++) {
                for (var b = 0; b < divisions; b++) {
                    var at = ((r * divisions + g) * divisions + b) * 3;
                    entries[at] = r / (divisions - 1f);
                    entries[at + 1] = g / (divisions - 1f);
                    entries[at + 2] = b / (divisions - 1f);
                }
            }
        }
        return entries;
    }

    @Nested
    @DisplayName("RGB tables")
    class Tables {
        @ParameterizedTest(name = "gamma encoding {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4})
        @DisplayName("the transfer curves invert each other")
        void curves(int gamma) {
            for (var x = 0f; x <= 1f; x += 1 / 64f) {
                assertEquals(x, RgbTables.decode(gamma, RgbTables.encode(gamma, x)), 2e-5, "at " + x);
            }
        }

        @ParameterizedTest(name = "primaries {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4})
        @DisplayName("an identity table leaves in-gamut colors unchanged, and with gamut extension the others too")
        void identityTable(int primaries) {
            var tables = RgbTables.parse(rgbTables(ByteOrder.BIG_ENDIAN, 0,
                    new Object[]{null, 5, 1, primaries, 1, identity(5)}));
            assertNotNull(tables);
            var out = new float[3];
            var scratch = new float[3];
            for (var color : new float[][]{{0.2f, 0.3f, 0.1f}, {0.5f, 0.5f, 0.5f}, {0.05f, 0.4f, 0.02f}}) {
                RgbTables.transform(tables.tables.getFirst(), color, out, scratch);
                assertArrayEquals(color, out, 2e-4f);
            }
            // a color out of every table's gamut keeps its out-of-gamut part
            var vivid = new float[]{0.9f, 0.01f, 0.0f};
            RgbTables.transform(tables.tables.getFirst(), vivid, out, scratch);
            assertArrayEquals(vivid, out, 2e-4f);
        }

        @Test
        @DisplayName("a constant table maps every color to its value, clamped without gamut extension")
        void constant() {
            var entries = new float[8 * 3];
            java.util.Arrays.fill(entries, 0.25f);
            var tables = RgbTables.parse(rgbTables(ByteOrder.LITTLE_ENDIAN, 1, new Object[]{"sky", 2, 0, 4, 0, entries}));
            assertNotNull(tables, "a little-endian tag parses too");
            assertTrue(tables.sequential);
            assertEquals("sky", tables.tables.getFirst().name());
            var out = new float[3];
            RgbTables.transform(tables.tables.getFirst(), new float[]{0.9f, 2f, 0.1f}, out, new float[3]);
            assertArrayEquals(new float[]{0.25f, 0.25f, 0.25f}, out, 1e-5f);
        }

        @Test
        @DisplayName("tags with two background tables, trailing bytes or bad fields are ignored")
        void invalid() {
            var table = new Object[]{null, 2, 0, 4, 0, identity(2)};
            assertNull(RgbTables.parse(rgbTables(ByteOrder.BIG_ENDIAN, 0, table, table)));
            var valid = rgbTables(ByteOrder.BIG_ENDIAN, 0, table);
            assertNull(RgbTables.parse(java.util.Arrays.copyOf(valid, valid.length + 1)));
            assertNull(RgbTables.parse(rgbTables(ByteOrder.BIG_ENDIAN, 2, table)));
            assertNull(RgbTables.parse(rgbTables(ByteOrder.BIG_ENDIAN, 0, new Object[]{null, 1, 0, 4, 0, new float[3]})));
            assertNull(RgbTables.parse(rgbTables(ByteOrder.BIG_ENDIAN, 0, new Object[]{null, 2, 5, 4, 0, identity(2)})));
        }
    }

    @Nested
    @DisplayName("four-color projection")
    class Projections {
        static final float[] FOUR = {0.25f, 0.25f, 0.25f, 0.25f, 0, 1, 0, 0, 0, 0, 0, 1, 2, 1, 1, 1.5f};

        @Test
        @DisplayName("clipping limits each white-balanced value to 1")
        void clip() {
            var out = new float[3];
            Projection.project(new float[]{0.4f, 0.5f, 2f, 1f}, FOUR, false, out, 0);
            assertArrayEquals(new float[]{(0.8f + 0.5f + 1f + 1f) / 4, 0.5f, 1f}, out, 1e-6f);
        }

        @Test
        @DisplayName("blending leaves values below saturation unchanged and turns clipped highlights neutral")
        void blend() {
            var out = new float[3];
            Projection.project(new float[]{0.2f, 0.5f, 0.3f, 0.6f}, FOUR, true, out, 0);
            assertArrayEquals(new float[]{(0.4f + 0.5f + 0.3f + 0.9f) / 4, 0.5f, 0.9f}, out, 1e-6f);
            // every channel at its saturation: all four reach 1 after blending
            Projection.project(new float[]{1f, 1f, 1f, 1f}, FOUR, true, out, 0);
            assertArrayEquals(new float[]{1f, 1f, 1f}, out, 1e-6f);
        }
    }
}
