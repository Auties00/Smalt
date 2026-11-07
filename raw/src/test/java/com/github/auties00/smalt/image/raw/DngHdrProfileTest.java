package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the rendering of DNG 1.7 high-dynamic-range camera profiles ({@code ProfileDynamicRange}) on synthetic
 * linear raw files whose camera space is linear ProPhoto RGB ({@link HdrProfileFiles}), so that every expected
 * value follows from the specification's formulas alone: the encoding function and its inverse around the tone
 * curve (a natural cubic spline continued by its end slope), the tables of three dimensions and the RGB tables, the
 * floating-point output that keeps values above 1, and the standard-dynamic-range profiles that render as before.
 * The comparison with the DNG SDK's own rendering is in {@link DngValidateTest}.
 */
class DngHdrProfileTest {
    /** The floating-point ProPhoto RGB rendering the tests compare. */
    static final RawDecoderOptions FLOAT = RawDecoderOptions.builder().colorSpace(RawColorSpace.PROPHOTO_RGB)
            .floatingPoint(true).build();

    /**
     * The bound on the difference of encoded values from the specification's: the color matrix is stored to four
     * decimals, so the camera to ProPhoto mapping is the identity within about {@code 2e-4}.
     */
    static final double ENCODED = 1.5e-3;

    /** {@return the specification's encoding function} */
    static double encode(double x) {
        return x * (256 + x) / (256 * (1 + x));
    }

    /** {@return the specification's decoding function, written as the specification writes it} */
    static double decode(double y) {
        return 16 * (8 * y - 8 + Math.sqrt(64 * y * y - 127 * y + 64));
    }

    /** {@return the linear ProPhoto RGB values of a floating-point ProPhoto rendering, pixel by pixel} */
    static double[] linear(byte[] dng, RawDecoderOptions options) {
        var picture = assertInstanceOf(ChannelFloatPicture.class, RawDecoder.decode(MediaSource.of(dng), options)
                .picture());
        assertEquals(PixelLayout.RGB, picture.layout());
        var values = new double[picture.width() * picture.height() * 3];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width() * 3; x++) {
                values[y * picture.width() * 3 + x] = Math.pow(picture.pixels()[y * picture.stride() + x], 1.8);
            }
        }
        return values;
    }

    static double[] linear(byte[] dng) {
        return linear(dng, FLOAT);
    }

    /** Checks that every channel of a rendering is a function of the same channel of the stored image. */
    static void assertRendering(byte[] dng, DoubleUnaryOperator expected) {
        var values = linear(dng);
        var worst = 0.0;
        for (var y = 0; y < HdrProfileFiles.SIZE; y++) {
            for (var x = 0; x < HdrProfileFiles.SIZE; x++) {
                for (var c = 0; c < 3; c++) {
                    var want = expected.applyAsDouble(HdrProfileFiles.sample(x, y, c));
                    var got = values[(y * HdrProfileFiles.SIZE + x) * 3 + c];
                    var error = Math.abs(encode(got) - encode(want));
                    if (error > worst) {
                        worst = error;
                    }
                    if (error > ENCODED) {
                        assertEquals(want, got, "pixel " + x + "," + y + " channel " + c);
                    }
                }
            }
        }
    }

    static double max(double[] values) {
        var max = 0.0;
        for (var value : values) {
            max = Math.max(max, value);
        }
        return max;
    }

    /** A natural cubic spline through points, solved directly; continued by its end slope past the last point. */
    static DoubleUnaryOperator spline(double... points) {
        var n = points.length / 2;
        var x = new double[n];
        var y = new double[n];
        for (var i = 0; i < n; i++) {
            x[i] = points[2 * i];
            y[i] = points[2 * i + 1];
        }
        var m = new double[n];
        if (n > 2) {
            // the full system of the interior second derivatives, by Gaussian elimination
            var size = n - 2;
            var a = new double[size][size + 1];
            for (var i = 1; i < n - 1; i++) {
                var row = a[i - 1];
                if (i > 1) {
                    row[i - 2] = x[i] - x[i - 1];
                }
                row[i - 1] = 2 * (x[i + 1] - x[i - 1]);
                if (i < n - 2) {
                    row[i] = x[i + 1] - x[i];
                }
                row[size] = 6 * ((y[i + 1] - y[i]) / (x[i + 1] - x[i]) - (y[i] - y[i - 1]) / (x[i] - x[i - 1]));
            }
            for (var col = 0; col < size; col++) {
                for (var row = col + 1; row < size; row++) {
                    var factor = a[row][col] / a[col][col];
                    for (var k = col; k <= size; k++) {
                        a[row][k] -= factor * a[col][k];
                    }
                }
            }
            for (var row = size - 1; row >= 0; row--) {
                var sum = a[row][size];
                for (var k = row + 1; k < size; k++) {
                    sum -= a[row][k] * m[k + 1];
                }
                m[row + 1] = sum / a[row][row];
            }
        }
        var h = x[n - 1] - x[n - 2];
        var slope = (y[n - 1] - y[n - 2]) / h + h * (m[n - 2] + 2 * m[n - 1]) / 6;
        return t -> {
            if (t <= x[0]) {
                return y[0];
            }
            if (t >= x[n - 1]) {
                return y[n - 1] + (t - x[n - 1]) * slope;
            }
            var i = 0;
            while (x[i + 1] < t) {
                i++;
            }
            var w = x[i + 1] - x[i];
            var p = (x[i + 1] - t) / w;
            var q = (t - x[i]) / w;
            return p * y[i] + q * y[i + 1] + ((p * p * p - p) * m[i] + (q * q * q - q) * m[i + 1]) * w * w / 6;
        };
    }

    @Nested
    @DisplayName("the tone curve")
    class ToneCurves {
        @Test
        @DisplayName("an identity curve keeps the exposure's values above reference white")
        void identity() {
            var file = new HdrProfileFiles();
            var dng = file.write();
            assertRendering(dng, x -> 4 * x);
            assertTrue(max(linear(dng)) > 3.9);
        }

        @Test
        @DisplayName("the curve applies to encoded values, interpolated by a natural cubic spline")
        void encoded() {
            var file = new HdrProfileFiles();
            file.toneCurve = new float[]{0, 0, 0.5f, 0.6f, 1, 0.9f};
            var curve = spline(0, 0, 0.5f, 0.6f, 1, 0.9f);
            assertRendering(file.write(), x -> decode(curve.applyAsDouble(encode(4 * x))));
        }

        @Test
        @DisplayName("values past the curve's last point follow its end slope, beyond 16 times white too")
        void slopeExtension() {
            var file = new HdrProfileFiles();
            file.toneCurve = new float[]{0, 0, 0.75f, 0.75f};
            file.baselineExposure = 5;
            var dng = file.write();
            assertRendering(dng, x -> 32 * x);
            assertTrue(max(linear(dng)) > 31);
            file.toneCurve = new float[]{0, 0, 0.25f, 0.35f, 0.5f, 0.6f, 0.75f, 0.8f};
            var curve = spline(0, 0, 0.25f, 0.35f, 0.5f, 0.6f, 0.75f, 0.8f);
            assertRendering(file.write(), x -> decode(curve.applyAsDouble(encode(32 * x))));
        }

        @Test
        @DisplayName("without a curve the default curve applies to encoded values, mapping 16 times white to itself")
        void defaultCurve() {
            var file = new HdrProfileFiles();
            file.toneCurve = null;
            file.baselineExposure = 4;
            var values = linear(file.write());
            // the brightest stored value, 1, exposed by 4 stops is 16, which the default curve keeps
            assertEquals(16, values[(63 * HdrProfileFiles.SIZE + 63) * 3], 0.05);
            var sdr = new HdrProfileFiles();
            sdr.toneCurve = null;
            sdr.dynamicRange = -1;
            sdr.baselineExposure = 4;
            assertTrue(max(linear(sdr.write())) <= 1);
        }

        @Test
        @DisplayName("a curve whose last input lies above 1 is evaluated up to that input, then by its end slope")
        void beyondOne() {
            var file = new HdrProfileFiles();
            file.toneCurve = new float[]{0, 0, 0.5f, 0.7f, 1.5f, 1.2f};
            file.baselineExposure = 5;
            var curve = spline(0, 0, 0.5f, 0.7f, 1.5f, 1.2f);
            assertRendering(file.write(), x -> decode(curve.applyAsDouble(encode(32 * x))));
        }

        @Test
        @DisplayName("a malformed curve renders finite, non-negative values at every depth")
        void malformed() {
            var curves = List.of(new float[]{0, 0, 0.6f, 0.5f, 0.4f, 0.8f, 1, 1},
                    new float[]{0, 0, 0.5f, Float.NaN, 1, 1},
                    new float[]{0, 0, 0.5f, 0.5f, Float.POSITIVE_INFINITY, 1},
                    new float[]{0, 0, Float.NaN, 0.5f, 1, 1},
                    new float[]{0.2f, 0.3f, 0.2f, 0.4f},
                    new float[]{0, 0, 1e30f, 1e30f});
            for (var curve : curves) {
                var file = new HdrProfileFiles();
                file.toneCurve = curve;
                var dng = file.write();
                for (var value : linear(dng)) {
                    assertTrue(Double.isFinite(value) && value >= 0, "curve " + Arrays.toString(curve) + ": " + value);
                }
                RawDecoder.decode(MediaSource.of(dng));
                RawDecoder.decode(MediaSource.of(dng), RawDecoderOptions.builder().highBitDepth(true).build());
            }
        }
    }

    @Nested
    @DisplayName("the profile tables")
    class Tables {
        static float[] scaled(int values, float scale) {
            return HdrProfileFiles.table(6, 2, values, (h, s, v) -> new float[]{0, 1, scale});
        }

        @Test
        @DisplayName("a hue, saturation and value map with value divisions scales the encoded value, before exposure")
        void hueSatMap() {
            var file = new HdrProfileFiles();
            file.hueSatDims = new int[]{6, 2, 2};
            file.hueSat = scaled(2, 0.5f);
            assertRendering(file.write(), x -> 4 * decode(0.5 * encode(x)));
        }

        @Test
        @DisplayName("a look table with value divisions scales the encoded value, after exposure")
        void lookTable() {
            var file = new HdrProfileFiles();
            file.lookDims = new int[]{6, 2, 3};
            file.look = scaled(3, 0.7f);
            assertRendering(file.write(), x -> decode(0.7 * encode(4 * x)));
        }

        @Test
        @DisplayName("a table without value divisions scales the linear value, which stays above 1")
        void flatTable() {
            var file = new HdrProfileFiles();
            file.lookDims = new int[]{6, 2, 1};
            file.look = scaled(1, 0.7f);
            var dng = file.write();
            assertRendering(dng, x -> 0.7 * 4 * x);
            assertTrue(max(linear(dng)) > 2.7);
        }

        @Test
        @DisplayName("RGB tables look up the encoded values")
        void rgbTables() {
            var file = new HdrProfileFiles();
            // a table that halves its encoded input
            file.rgbTables = HdrProfileFiles.rgbTable(2, (r, g, b) -> new float[]{r / 2, g / 2, b / 2});
            assertRendering(file.write(), x -> decode(Math.min(encode(4 * x), 1) / 2));
        }

        static double srgb(double x) {
            return x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - 0.055;
        }

        static double srgbInverse(double y) {
            return y <= 0.04045 ? y / 12.92 : Math.pow((y + 0.055) / 1.055, 2.4);
        }

        @ParameterizedTest(name = "high dynamic range {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("the value scale of an sRGB-encoded table applies to the sRGB-encoded value")
        void srgbEncoded(boolean high) {
            var file = new HdrProfileFiles();
            file.lookDims = new int[]{6, 2, 3};
            file.look = scaled(3, 0.6f);
            file.lookSrgb = true;
            file.baselineExposure = high ? 2 : 0;
            file.dynamicRange = high ? 1 : -1;
            var values = linear(file.write());
            var exposure = high ? 4 : 1;
            for (var y = 0; y < HdrProfileFiles.SIZE; y++) {
                for (var x = 0; x < HdrProfileFiles.SIZE; x++) {
                    var e = new double[3];
                    var max = 0.0;
                    for (var c = 0; c < 3; c++) {
                        var v = exposure * HdrProfileFiles.sample(x, y, c);
                        e[c] = high ? encode(v) : v;
                        max = Math.max(max, e[c]);
                    }
                    var factor = max > 0 ? srgbInverse(0.6 * srgb(max)) / max : 0;
                    for (var c = 0; c < 3; c++) {
                        var want = high ? decode(e[c] * factor) : e[c] * factor;
                        var got = values[(y * HdrProfileFiles.SIZE + x) * 3 + c];
                        if (Math.abs(encode(got) - encode(want)) > ENCODED) {
                            assertEquals(want, got, "pixel " + x + "," + y + " channel " + c);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("standard-dynamic-range tables keep their linear meaning")
        void standard() {
            var file = new HdrProfileFiles();
            file.dynamicRange = -1;
            file.baselineExposure = 0;
            file.lookDims = new int[]{6, 2, 3};
            file.look = scaled(3, 0.7f);
            assertRendering(file.write(), x -> 0.7 * x);
        }
    }

    @Nested
    @DisplayName("standard-dynamic-range profiles")
    class Standard {
        static HdrProfileFiles varied(int range) {
            var file = new HdrProfileFiles();
            file.dynamicRange = range;
            file.maxOutputHint = range < 0 ? 0 : 1;
            file.toneCurve = new float[]{0, 0, 0.3f, 0.45f, 1, 1};
            file.baselineExposure = 0.5;
            file.hueSatDims = new int[]{6, 2, 2};
            file.hueSat = HdrProfileFiles.varied(6, 2, 2);
            file.lookDims = new int[]{6, 3, 3};
            file.look = HdrProfileFiles.varied(6, 3, 3);
            return file;
        }

        @Test
        @DisplayName("a ProfileDynamicRange of standard range renders as no tag does, at every depth")
        void unchanged() {
            var plain = varied(-1).write();
            var tagged = varied(0).write();
            for (var options : List.of(RawDecoderOptions.defaults(), RawDecoderOptions.builder().highBitDepth(true)
                    .build(), FLOAT)) {
                Tolerance.exact().assertWithin(options.toString(), RawDecoder.decode(MediaSource.of(plain), options)
                        .picture(), RawDecoder.decode(MediaSource.of(tagged), options).picture());
            }
            assertFalse(RawDecoder.info(MediaSource.of(tagged)).cameraProfileDetails().getFirst().highDynamicRange());
        }

        @Test
        @DisplayName("the floating-point output equals the 16-bit output, clipped to 0 to 1")
        void floatingPoint() {
            var dng = varied(-1).write();
            for (var space : RawColorSpace.values()) {
                var deep = (Rgba16Picture) RawDecoder.decode(MediaSource.of(dng), RawDecoderOptions.builder()
                        .colorSpace(space).highBitDepth(true).build()).picture();
                var floats = (ChannelFloatPicture) RawDecoder.decode(MediaSource.of(dng), RawDecoderOptions.builder()
                        .colorSpace(space).floatingPoint(true).build()).picture();
                for (var i = 0; i < HdrProfileFiles.SIZE * HdrProfileFiles.SIZE; i++) {
                    for (var c = 0; c < 3; c++) {
                        var value = floats.pixels()[i * 3 + c];
                        assertTrue(value >= 0 && value <= 1, space + " value " + value);
                        // the 16-bit output looks its transfer table up at steps of 2^-20 of linear light, coarser
                        // than a 16-bit code where a pure power curve rises steeply from black
                        var bound = value < 0.03 ? 2e-3 : 2.5 / 65535;
                        assertEquals((deep.pixels()[i * 4 + c] & 0xFFFF) / 65535.0, value, bound,
                                space + " pixel " + i);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("integer outputs")
    class Integers {
        @Test
        @DisplayName("clip a high-dynamic-range rendering to white")
        void clip() {
            var hdr = new HdrProfileFiles().write();
            var sdr = new HdrProfileFiles();
            sdr.dynamicRange = -1;
            var options = RawDecoderOptions.builder().highBitDepth(true).build();
            var expected = RawDecoder.decode(MediaSource.of(sdr.write()), options).picture();
            Tolerance.maxAbs(1).assertWithin("16-bit", expected, RawDecoder.decode(MediaSource.of(hdr), options)
                    .picture());
            assertInstanceOf(RgbaPicture.class, RawDecoder.decode(MediaSource.of(hdr)).picture());
        }

        @Test
        @DisplayName("the linear tone curve leaves the profile's look out and clips to white")
        void linearCurve() {
            var options = FLOAT.toBuilder().toneCurve(RawToneCurve.LINEAR).build();
            var values = linear(new HdrProfileFiles().write(), options);
            assertTrue(max(values) <= 1);
        }
    }

    @Nested
    @DisplayName("ProfileDynamicRange")
    class Tag {
        @ParameterizedTest(name = "big endian {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("is read in the file's byte order, or in the other when only that one gives version 1")
        void byteOrder(boolean bigEndian) {
            var file = new HdrProfileFiles();
            file.bigEndian = bigEndian;
            var expected = linear(file.write());
            assertTrue(max(expected) > 3.9);
            file.dynamicRangeBytes = HdrProfileFiles.dynamicRange(1, 8, bigEndian ? ByteOrder.LITTLE_ENDIAN
                    : ByteOrder.BIG_ENDIAN);
            assertArrayEquals(expected, linear(file.write()));
            assertEquals(new RawCameraProfile("Test", "", true, 8),
                    RawDecoder.info(MediaSource.of(file.write())).cameraProfileDetails().getFirst());
        }

        @Test
        @DisplayName("of another version, length or range leaves the profile standard-range")
        void invalid() {
            var plain = new HdrProfileFiles();
            plain.dynamicRange = -1;
            var expected = linear(plain.write());
            for (var bytes : List.of(new byte[]{2, 0, 1, 0, 0, 0, 0, 65}, new byte[]{1, 0, 1, 0, 0, 0, 0},
                    new byte[]{1, 0, 2, 0, 0, 0, 0, 65}, new byte[]{1, 0, 1, 0, 0, 0, 0, 65, 0})) {
                var file = new HdrProfileFiles();
                file.dynamicRangeBytes = bytes;
                assertArrayEquals(expected, linear(file.write()));
            }
            var hintless = new HdrProfileFiles();
            hintless.maxOutputHint = -1;
            assertEquals(new RawCameraProfile("Test", "", true, 0),
                    RawDecoder.info(MediaSource.of(hintless.write())).cameraProfileDetails().getFirst());
        }

        @Test
        @DisplayName("of each camera profile is listed with its group, and a profile selected by name renders by it")
        void profiles() {
            var sdr = new HdrProfileFiles();
            sdr.name = "Portrait SDR";
            sdr.group = "Portrait";
            sdr.dynamicRange = 0;
            sdr.maxOutputHint = 1;
            var file = new HdrProfileFiles();
            file.name = "Portrait HDR";
            file.group = "Portrait";
            file.extraProfiles = new byte[][]{sdr.profile()};
            var dng = file.write();
            var info = RawDecoder.info(MediaSource.of(dng));
            assertEquals(List.of("Portrait HDR", "Portrait SDR"), info.cameraProfiles());
            assertEquals(List.of(new RawCameraProfile("Portrait HDR", "Portrait", true, 8),
                    new RawCameraProfile("Portrait SDR", "Portrait", false, 1)), info.cameraProfileDetails());
            assertTrue(max(linear(dng)) > 3.9);
            assertTrue(max(linear(dng, FLOAT.toBuilder().cameraProfile("Portrait SDR").build())) <= 1);
            var other = new HdrProfileFiles();
            other.name = "Other";
            other.dynamicRange = -1;
            other.extraProfiles = new byte[][]{new HdrProfileFiles().profile()};
            var selected = linear(other.write(), FLOAT.toBuilder().cameraProfile("Test").build());
            assertArrayEquals(linear(new HdrProfileFiles().write()), selected);
        }

        @Test
        @DisplayName("ProfileGroupName is read and written as UTF-8")
        void utf8Group() {
            var file = new HdrProfileFiles();
            file.group = "Porträt été";
            var source = file.write();
            var expected = List.of(new RawCameraProfile("Test", "Porträt été", true, 8));
            assertEquals(expected, RawDecoder.info(MediaSource.of(source)).cameraProfileDetails());
            assertEquals(expected, RawDecoder.info(MediaSource.of(DngEncoder.convert(MediaSource.of(source))))
                    .cameraProfileDetails());
        }

        @Test
        @DisplayName("is written by the DNG encoder for the profile a conversion renders with")
        void converted() {
            var file = new HdrProfileFiles();
            file.group = "Group";
            file.toneCurve = new float[]{0, 0, 0.5f, 0.6f, 1, 0.9f};
            var source = file.write();
            var converted = DngEncoder.convert(MediaSource.of(source));
            assertEquals(List.of(new RawCameraProfile("Test", "Group", true, 8)),
                    RawDecoder.info(MediaSource.of(converted)).cameraProfileDetails());
            assertArrayEquals(linear(source), linear(converted), 1e-4);
            var other = new HdrProfileFiles();
            other.name = "Other";
            other.dynamicRange = -1;
            other.extraProfiles = new byte[][]{file.profile()};
            var chosen = DngEncoder.convert(MediaSource.of(other.write()), RawDecoderOptions.builder()
                    .cameraProfile("Test").build(), DngEncoderOptions.defaults());
            assertEquals(List.of(new RawCameraProfile("Test", "Group", true, 8)),
                    RawDecoder.info(MediaSource.of(chosen)).cameraProfileDetails());
        }
    }
}
