package com.github.auties00.smalt.image.raw.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the pieces of the high-dynamic-range profile rendering against the DNG 1.7.1 specification: the encoding
 * function of {@code ProfileDynamicRange} and its inverse, the natural cubic spline of the tone curve and its slope
 * extension, and the parsing of the tag in either byte order.
 */
class HdrEncodingTest {
    @Nested
    @DisplayName("the encoding function")
    class Encoding {
        @Test
        @DisplayName("maps 0 to 0, 1 to 257/512 and 16 to 1, as the specification's formula does")
        void points() {
            assertEquals(0, HdrEncoding.encode(0f));
            assertEquals(257.0 / 512, HdrEncoding.encode(1f), 1e-7);
            assertEquals(1, HdrEncoding.encode(16f), 1e-7);
            for (var x = 0.001; x < 64; x *= 1.1) {
                assertEquals(x * (256 + x) / (256 * (1 + x)), HdrEncoding.encode((float) x), 1e-6, "x " + x);
            }
        }

        @Test
        @DisplayName("is inverted by the decoding function, which equals the specification's form")
        void inverse() {
            for (var y = 1e-6; y < 1.2; y *= 1.05) {
                var spec = 16 * (8 * y - 8 + Math.sqrt(64 * y * y - 127 * y + 64));
                var decoded = HdrEncoding.decode((float) y);
                assertEquals(spec, decoded, Math.max(1e-6, 1e-6 * spec), "y " + y);
                assertEquals((float) y, HdrEncoding.encode(decoded), 2e-7f * (float) Math.max(1, y), "y " + y);
            }
        }

        @Test
        @DisplayName("keeps its precision near 0, where the specification's form of the inverse cancels")
        void small() {
            for (var x = 1e-9f; x < 1e-3f; x *= 3) {
                assertEquals(x, HdrEncoding.decode(HdrEncoding.encode(x)), x * 1e-6f, "x " + x);
            }
        }

        @Test
        @DisplayName("is increasing and continues by the identity below 0")
        void monotonic() {
            var previous = Float.NEGATIVE_INFINITY;
            for (var x = -2f; x < 100; x += 0.01f) {
                var y = HdrEncoding.encode(x);
                assertTrue(y > previous, "x " + x);
                previous = y;
                if (x <= 0) {
                    assertEquals(x, y);
                    assertEquals(x, HdrEncoding.decode(x));
                }
            }
            assertTrue(Float.isNaN(HdrEncoding.encode(Float.NaN)));
        }

        @Test
        @DisplayName("rows are encoded and decoded in place")
        void rows() {
            float[] r = {0, 1, 4};
            float[] g = {16, 2, 0.5f};
            float[] b = {8, 0.25f, 32};
            HdrEncoding.encode(r, g, b, 3);
            assertEquals(1, g[0], 1e-7);
            HdrEncoding.decode(r, g, b, 3);
            assertEquals(4, r[2], 1e-5);
            assertEquals(32, b[2], 1e-4);
        }
    }

    @Nested
    @DisplayName("the tone curve spline")
    class Spline {
        @Test
        @DisplayName("is the natural cubic spline: second derivative -1.8 and end slope 0.45 through three points")
        void threePoints() {
            float[] points = {0, 0, 0.5f, 0.6f, 1, 0.9f};
            var second = ColorPipeline.spline(points);
            assertEquals(0, second[0]);
            assertEquals(-1.8, second[1], 1e-6);
            assertEquals(0, second[2]);
            assertEquals(0.45, ColorPipeline.endSlope(points, second), 1e-6);
            // on [0.5, 1] at t = 0.625: 0.7875 + h^2/6 * ((1 - t)^3 - (1 - t)) * M1
            var t = 0.625;
            var expected = 0.6 + t * 0.3 + 0.25 / 6 * (Math.pow(1 - t, 3) - (1 - t)) * -1.8;
            assertEquals(expected, ColorPipeline.splineValue(points, second, 0.8125), 1e-6);
            assertEquals(0.6, ColorPipeline.splineValue(points, second, 0.5), 1e-7);
            assertEquals(0.9 + 0.1 * 0.45, ColorPipeline.splineValue(points, second, 1.1), 1e-6);
            assertEquals(0, ColorPipeline.splineValue(points, second, -1));
        }

        @Test
        @DisplayName("of two points is their line, extended by its slope")
        void line() {
            float[] points = {0, 0, 0.75f, 0.75f};
            var second = ColorPipeline.spline(points);
            assertEquals(1, ColorPipeline.endSlope(points, second), 1e-7);
            assertEquals(0.9, ColorPipeline.splineValue(points, second, 0.9), 1e-7);
        }

        @Test
        @DisplayName("passes through every point and has a continuous slope at the inner ones")
        void smooth() {
            float[] points = {0, 0, 0.1f, 0.2f, 0.3f, 0.35f, 0.6f, 0.7f, 0.8f, 0.82f, 1, 1};
            var second = ColorPipeline.spline(points);
            for (var index = 0; index < points.length; index += 2) {
                assertEquals(points[index + 1], ColorPipeline.splineValue(points, second, points[index]), 1e-6);
            }
            for (var index = 2; index < points.length - 2; index += 2) {
                var x = points[index];
                var left = (ColorPipeline.splineValue(points, second, x) - ColorPipeline.splineValue(points, second,
                        x - 1e-6)) / 1e-6;
                var right = (ColorPipeline.splineValue(points, second, x + 1e-6) - ColorPipeline.splineValue(points,
                        second, x)) / 1e-6;
                assertEquals(left, right, 1e-4, "point " + x);
            }
        }

        @Test
        @DisplayName("is refused for inputs that do not increase strictly")
        void unordered() {
            assertNull(ColorPipeline.spline(new float[]{0, 0, 0.5f, 0.5f, 0.5f, 0.7f, 1, 1}));
            assertNull(ColorPipeline.spline(new float[]{0, 0, 0.6f, 0.5f, 0.4f, 0.7f}));
            assertNull(ColorPipeline.spline(null));
        }
    }

    @Nested
    @DisplayName("ProfileDynamicRange")
    class Tag {
        static byte[] tag(ByteOrder order, int version, int range, float hint) {
            return ByteBuffer.allocate(8).order(order).putShort((short) version).putShort((short) range)
                    .putFloat(hint).array();
        }

        @ParameterizedTest(name = "little endian file {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("is read in either byte order")
        void orders(boolean little) {
            for (var order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
                var color = new ColorCalibration();
                DngParser.dynamicRange(tag(order, 1, 1, 4.5f), little, color);
                assertTrue(color.highDynamicRange);
                assertEquals(4.5f, color.maxOutputHint);
                DngParser.dynamicRange(tag(order, 1, 0, 1), little, color);
                assertFalse(color.highDynamicRange);
                assertEquals(1, color.maxOutputHint);
            }
        }

        @Test
        @DisplayName("of another version, range or length, or a hint that is not positive, is ignored")
        void invalid() {
            var color = new ColorCalibration();
            var order = ByteOrder.LITTLE_ENDIAN;
            for (var data : new byte[][]{tag(order, 2, 1, 4), tag(order, 0, 1, 4), tag(order, 1, 2, 4),
                    new byte[7], new byte[9], null}) {
                color.highDynamicRange = true;
                DngParser.dynamicRange(data, true, color);
                assertFalse(color.highDynamicRange);
                assertEquals(0, color.maxOutputHint);
            }
            for (var hint : new float[]{0, -2, Float.NaN, Float.POSITIVE_INFINITY}) {
                DngParser.dynamicRange(tag(order, 1, 1, hint), true, color);
                assertTrue(color.highDynamicRange);
                assertEquals(0, color.maxOutputHint);
            }
        }
    }
}
