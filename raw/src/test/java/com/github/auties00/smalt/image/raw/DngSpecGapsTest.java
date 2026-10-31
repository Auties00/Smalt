package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.jxl.JxlDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the DNG features of the rendering on synthetic files written by {@link DngBuilder}: the enhanced image,
 * reduced-resolution linear raw previews and JPEG XL previews for size hints, the gain table maps, the masked RGB
 * tables and their semantic masks, the black rendering hint, the default user crop, the extra camera profiles,
 * and the white balance, matrix and highlight choices of four-color sensors, each against a rendering whose
 * result is known: an equivalent file without the feature, a hand-computed value or an exact sub-image.
 */
class DngSpecGapsTest {
    static final double[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    static void header(DngBuilder.Dir dir, int width, int height, int samples) {
        var depths = new int[samples];
        java.util.Arrays.fill(depths, 16);
        dir.longs(254, 0).longs(256, width).longs(257, height).shorts(258, depths).shorts(259, 1)
                .shorts(277, samples).shorts(262, samples == 1 ? 32803 : 34892);
    }

    static DngBuilder profile(DngBuilder builder) {
        builder.first.bytes(50706, 1, 7, 1, 0).signedRationals(50721, 0.8, -0.1, -0.05, -0.3, 1.2, 0.1, 0, 0.1, 0.7)
                .rationals(50728, 0.6, 1, 0.8).shorts(50778, 21).ascii(271, "Smalt").ascii(272, "Spec");
        return builder;
    }

    static long[] gradient(int width, int height, int samples) {
        var values = new long[width * height * samples];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var s = 0; s < samples; s++) {
                    values[(y * width + x) * samples + s] = 2000 + (x * 731 + y * 389 + s * 4111 + (x * y) % 97 * 50) % 40000;
                }
            }
        }
        return values;
    }

    static byte[] words(long[] values) {
        return DngBuilder.words(values, 16, ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * A linear raw file of three 16-bit samples per pixel.
     */
    static DngBuilder linear(int width, int height) {
        var builder = profile(new DngBuilder());
        header(builder.first, width, height, 3);
        builder.first.strips(height, words(gradient(width, height, 3)));
        return builder;
    }

    static int[] pixels(Picture picture) {
        var rgba = ImageOps.toRgba(picture);
        var values = new int[rgba.width() * rgba.height() * 3];
        for (var y = 0; y < rgba.height(); y++) {
            for (var x = 0; x < rgba.width(); x++) {
                for (var c = 0; c < 3; c++) {
                    values[(y * rgba.width() + x) * 3 + c] = rgba.pixels()[y * rgba.stride() + x * 4 + c] & 0xFF;
                }
            }
        }
        return values;
    }

    static int[] render(byte[] dng, RawDecoderOptions options) {
        return pixels(RawDecoder.decode(MediaSource.of(dng), options).picture());
    }

    static int[] render(byte[] dng) {
        return render(dng, RawDecoderOptions.defaults());
    }

    static void assertClose(int[] expected, int[] actual, int tolerance) {
        assertEquals(expected.length, actual.length);
        for (var index = 0; index < expected.length; index++) {
            if (Math.abs(expected[index] - actual[index]) > tolerance) {
                assertEquals(expected[index], actual[index], tolerance, "value " + index);
            }
        }
    }

    static byte[] gainMap2(int pointsV, int pointsH, int pointsN, float[] gains) {
        var b = ByteBuffer.allocate(80 + 4 * gains.length).order(ByteOrder.BIG_ENDIAN);
        b.putInt(pointsV).putInt(pointsH).putDouble(1.0 / Math.max(1, pointsV - 1)).putDouble(1.0 / Math.max(1, pointsH - 1))
                .putDouble(0).putDouble(0).putInt(pointsN);
        b.putFloat(0.25f).putFloat(0.5f).putFloat(0.25f).putFloat(0).putFloat(0);
        b.putInt(3).putFloat(1).putFloat(0).putFloat(0);
        for (var gain : gains) {
            b.putFloat(gain);
        }
        return b.array();
    }

    static byte[] gainMap1(float[] gains) {
        var b = ByteBuffer.allocate(64 + 4 * gains.length).order(ByteOrder.BIG_ENDIAN);
        b.putInt(2).putInt(2).putDouble(1).putDouble(1).putDouble(0).putDouble(0).putInt(gains.length / 4);
        b.putFloat(0.25f).putFloat(0.5f).putFloat(0.25f).putFloat(0).putFloat(0);
        for (var gain : gains) {
            b.putFloat(gain);
        }
        return b.array();
    }

    @Nested
    @DisplayName("gain table maps")
    class GainTableMaps {
        @Test
        @DisplayName("a uniform gain of 2 renders as an exposure one stop higher")
        void uniform() {
            var plain = linear(32, 24).write();
            var gains = new float[2 * 2 * 3];
            java.util.Arrays.fill(gains, 2);
            var builder = linear(32, 24);
            builder.first.undefined(52544, gainMap2(2, 2, 3, gains));
            var mapped = render(builder.write());
            assertArrayEquals(render(plain, RawDecoderOptions.builder().exposure(1).build()), mapped);
            assertFalse(java.util.Arrays.equals(render(plain), mapped));
        }

        @Test
        @DisplayName("a map of gain 1 on the left and 4 on the right brightens only the right side")
        void spatial() {
            var plain = linear(64, 8).write();
            // two tables across with 2 entries each: 1 at u = 0, 4 at u = 1
            var builder = linear(64, 8);
            var b = ByteBuffer.allocate(64 + 4 * 4).order(ByteOrder.BIG_ENDIAN);
            b.putInt(1).putInt(2).putDouble(1).putDouble(1).putDouble(0).putDouble(0).putInt(2);
            b.putFloat(0.25f).putFloat(0.5f).putFloat(0.25f).putFloat(0).putFloat(0);
            b.putFloat(1).putFloat(1).putFloat(4).putFloat(4);
            builder.first.undefined(52525, b.array());
            var mapped = render(builder.write());
            var base = render(plain);
            var brighter = render(plain, RawDecoderOptions.builder().exposure(2).build());
            // the first column has u = 0.5 / 64, so a gain of about 1.05; the last column about 3.95
            assertClose(java.util.Arrays.copyOfRange(base, 0, 3), java.util.Arrays.copyOfRange(mapped, 0, 3), 4);
            var last = 63 * 3;
            assertClose(java.util.Arrays.copyOfRange(brighter, last, last + 3), java.util.Arrays.copyOfRange(mapped, last, last + 3), 4);
            assertTrue(mapped[last + 1] > base[last + 1] + 20);
        }

        @Test
        @DisplayName("the linear tone curve leaves the map out")
        void linearCurve() {
            var gains = new float[12];
            java.util.Arrays.fill(gains, 2);
            var builder = linear(16, 16);
            builder.first.undefined(52544, gainMap2(2, 2, 3, gains));
            var options = RawDecoderOptions.builder().toneCurve(RawToneCurve.LINEAR).build();
            assertArrayEquals(render(linear(16, 16).write(), options), render(builder.write(), options));
        }
    }

    /**
     * Writes an RGBTables tag of one table of two divisions in ProPhoto primaries, linear, with gamut extension.
     */
    static byte[] constantTable(String name, float value, int method) {
        var bytes = name == null ? new byte[0] : name.getBytes(StandardCharsets.US_ASCII);
        var b = ByteBuffer.allocate(8 + 2 + bytes.length + 5 + 24 * 4).order(ByteOrder.BIG_ENDIAN);
        b.putInt(1).putInt(method).putShort((short) bytes.length).put(bytes);
        b.put((byte) 2).put((byte) 2).put((byte) 0).put((byte) 4).put((byte) 0);
        for (var index = 0; index < 24; index++) {
            b.putFloat(value);
        }
        return b.array();
    }

    static DngBuilder.Dir mask(DngBuilder builder, String name, int width, int height, boolean left) {
        var dir = builder.child();
        var data = new byte[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                data[y * width + x] = (byte) (left == x < width / 2 ? 255 : 0);
            }
        }
        dir.longs(254, 65540).longs(256, width).longs(257, height).shorts(258, 8).shorts(259, 1).shorts(262, 52527)
                .shorts(277, 1).ascii(52526, name).strips(height, data);
        return dir;
    }

    @Nested
    @DisplayName("RGB tables and semantic masks")
    class RgbTablesRendering {
        @ParameterizedTest(name = "composite method {0}")
        @ValueSource(ints = {0, 1})
        @DisplayName("a background table applies everywhere")
        void background(int method) {
            var builder = linear(16, 16);
            builder.first.undefined(52543, constantTable(null, 0.18f, method));
            var pixels = render(builder.write());
            for (var index = 3; index < pixels.length; index++) {
                assertEquals(pixels[index % 3], pixels[index], 1, "value " + index);
            }
            assertNotEquals(render(linear(16, 16).write())[0], pixels[0]);
        }

        @Test
        @DisplayName("a masked table applies where its mask is, the rest renders as without it")
        void masked() {
            var plain = render(linear(32, 16).write());
            var builder = linear(32, 16);
            builder.first.undefined(52543, constantTable("subject", 0f, 0));
            mask(builder, "subject", 8, 4, true);
            var pixels = render(builder.write());
            for (var y = 0; y < 16; y++) {
                for (var x = 0; x < 32; x++) {
                    for (var c = 0; c < 3; c++) {
                        var at = (y * 32 + x) * 3 + c;
                        if (x < 14) {
                            assertEquals(0, pixels[at], 1, "inside the mask at " + x + ", " + y);
                        } else if (x >= 18) {
                            assertEquals(plain[at], pixels[at], 1, "outside the mask at " + x + ", " + y);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("a mask cropped to a MaskSubArea is 0 outside it, an invalid MaskSubArea is ignored")
        void subArea() {
            var plain = render(linear(32, 16).write());
            for (var valid : new boolean[]{true, false}) {
                var builder = linear(32, 16);
                builder.first.undefined(52543, constantTable("subject", 0f, 0));
                // a stored mask of 2 by 4 at the left of a full mask of 8 by 4, or one that overflows a 1 by 4 mask
                var data = new byte[2 * 4];
                java.util.Arrays.fill(data, (byte) 255);
                builder.child().longs(254, 65540).longs(256, 2).longs(257, 4).shorts(258, 8).shorts(259, 1)
                        .shorts(262, 52527).shorts(277, 1).ascii(52526, "subject")
                        .longs(52536, 0, 0, valid ? 8 : 1, 4).strips(4, data);
                var pixels = render(builder.write());
                for (var x = 0; x < 32; x++) {
                    for (var c = 0; c < 3; c++) {
                        var at = (5 * 32 + x) * 3 + c;
                        if (!valid || x < 6) {
                            assertEquals(0, pixels[at], 1, "inside the mask at " + x + ", valid " + valid);
                        } else if (x >= 10) {
                            assertEquals(plain[at], pixels[at], 1, "outside the mask at " + x);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("a table whose mask cannot be decoded is ignored")
        void undecodable() {
            var builder = linear(16, 16);
            builder.first.undefined(52543, constantTable("subject", 0f, 0));
            builder.child().longs(254, 65540).longs(256, 8).longs(257, 8).shorts(258, 8).shorts(259, 99)
                    .shorts(262, 52527).shorts(277, 1).ascii(52526, "subject").strips(8, new byte[64]);
            assertArrayEquals(render(linear(16, 16).write()), render(builder.write()));
        }

        @Test
        @DisplayName("a table whose mask the file lacks is ignored")
        void unmatched() {
            var builder = linear(16, 16);
            builder.first.undefined(52543, constantTable("missing", 0f, 0));
            mask(builder, "other", 8, 8, true);
            assertClose(render(linear(16, 16).write()), render(builder.write()), 1);
        }
    }

    @Nested
    @DisplayName("automatic black subtraction")
    class Black {
        static DngBuilder floor(int blackRender) {
            var builder = new DngBuilder();
            builder.first.bytes(50706, 1, 7, 1, 0).signedRationals(50721, IDENTITY).rationals(50728, 1, 1, 1)
                    .shorts(50778, 21);
            header(builder.first, 32, 32, 3);
            var values = gradient(32, 32, 3);
            for (var index = 0; index < values.length; index++) {
                values[index] = 600 + values[index] / 4;
            }
            builder.first.strips(32, words(values));
            if (blackRender >= 0) {
                builder.first.longs(51110, blackRender);
            }
            return builder;
        }

        @Test
        @DisplayName("the black point is subtracted when asked, unless the profile says None")
        void hint() {
            var auto = RawDecoderOptions.builder().autoBlack(true).build();
            var plain = render(floor(-1).write());
            var subtracted = render(floor(-1).write(), auto);
            var largest = 0;
            for (var index = 0; index < plain.length; index++) {
                assertTrue(subtracted[index] <= plain[index], "value " + index);
                largest = Math.max(largest, plain[index] - subtracted[index]);
            }
            assertTrue(largest >= 3, "the black subtraction darkens a value by " + largest + " at most");
            assertArrayEquals(subtracted, render(floor(0).write(), auto));
            assertArrayEquals(plain, render(floor(1).write(), auto));
        }
    }

    @Nested
    @DisplayName("default user crop")
    class UserCrop {
        @Test
        @DisplayName("narrows the default crop when asked, and the sensor image to the same window")
        void crop() {
            var builder = linear(40, 20);
            builder.first.rationals(50719, 4, 2).rationals(50720, 32, 16).rationals(51125, 0.25, 0.5, 0.75, 1.0);
            var bytes = builder.write();
            var full = RawDecoder.decodeRaw(MediaSource.of(bytes));
            assertEquals(32, full.width());
            var options = RawDecoderOptions.builder().userCrop(true).build();
            var cropped = RawDecoder.decodeRaw(MediaSource.of(bytes), options);
            assertEquals(16, cropped.width());
            assertEquals(8, cropped.height());
            for (var y = 0; y < 8; y++) {
                for (var x = 0; x < 16 * 3; x++) {
                    assertEquals(full.samples()[(y + 4) * 32 * 3 + 16 * 3 + x], cropped.samples()[y * 16 * 3 + x]);
                }
            }
            var picture = RawDecoder.decode(MediaSource.of(bytes), options).picture();
            assertEquals(16, picture.width());
            assertEquals(8, picture.height());
            assertEquals(32, RawDecoder.decode(MediaSource.of(bytes)).picture().width());
        }

        @Test
        @DisplayName("an invalid user crop is ignored")
        void invalid() {
            var builder = linear(16, 16);
            builder.first.rationals(51125, 0.5, 0, 0.25, 1);
            var options = RawDecoderOptions.builder().userCrop(true).build();
            assertEquals(16, RawDecoder.decode(MediaSource.of(builder.write()), options).picture().width());
        }
    }

    @Nested
    @DisplayName("extra camera profiles")
    class Profiles {
        static final double[] ALTERNATE = {1.1, -0.2, 0, -0.1, 1.0, 0.1, 0.05, -0.1, 0.9};

        static byte[] cameraProfile(String name, double[] matrix) {
            var profile = new DngBuilder(true, 0x4352);
            profile.first.ascii(50936, name).signedRationals(50721, matrix).shorts(50778, 21);
            return profile.write();
        }

        static DngBuilder file(String asShot) {
            var builder = linear(16, 16);
            builder.first.ascii(50936, "Primary").blobs(50933, cameraProfile("Alternate", ALTERNATE));
            if (asShot != null) {
                builder.first.ascii(50934, asShot);
            }
            return builder;
        }

        @Test
        @DisplayName("are listed and selected by name, the as-shot one by default")
        void selection() {
            var bytes = file(null).write();
            assertEquals(List.of("Primary", "Alternate"), RawDecoder.info(MediaSource.of(bytes)).cameraProfiles());
            var reference = linear(16, 16);
            reference.first.signedRationals(50721, ALTERNATE);
            var alternate = render(reference.write());
            assertArrayEquals(alternate, render(bytes, RawDecoderOptions.builder().cameraProfile("Alternate").build()));
            var primary = render(bytes);
            assertFalse(java.util.Arrays.equals(alternate, primary));
            assertArrayEquals(primary, render(bytes, RawDecoderOptions.builder().cameraProfile("Primary").build()));
            assertArrayEquals(alternate, render(file("Alternate").write()));
            assertArrayEquals(primary, render(file("Unknown").write()));
            assertThrows(MediaException.Unsupported.class,
                    () -> render(bytes, RawDecoderOptions.builder().cameraProfile("Missing").build()));
        }

        static float[] uniform(float gain) {
            var gains = new float[2 * 2 * 3];
            java.util.Arrays.fill(gains, gain);
            return gains;
        }

        @Test
        @DisplayName("an unnamed extra profile is listed as an empty name and selected by it")
        void unnamed() {
            var builder = linear(16, 16);
            var profile = new DngBuilder(true, 0x4352);
            profile.first.signedRationals(50721, ALTERNATE).shorts(50778, 21);
            builder.first.ascii(50936, "Primary").blobs(50933, profile.write());
            var bytes = builder.write();
            assertEquals(List.of("Primary", ""), RawDecoder.info(MediaSource.of(bytes)).cameraProfiles());
            var reference = linear(16, 16);
            reference.first.signedRationals(50721, ALTERNATE);
            assertArrayEquals(render(reference.write()), render(bytes, RawDecoderOptions.builder().cameraProfile("").build()));
        }

        static byte[] profiles(DngBuilder builder, DngBuilder own) {
            var inherited = new DngBuilder(true, 0x4352);
            inherited.first.ascii(50936, "Inherited").signedRationals(50721, ALTERNATE).shorts(50778, 21);
            own.first.ascii(50936, "Own").signedRationals(50721, ALTERNATE).shorts(50778, 21);
            builder.first.ascii(50936, "Primary").blobs(50933, own.write(), inherited.write());
            return builder.write();
        }

        @Test
        @DisplayName("ProfileGainTableMap2 supersedes ProfileGainTableMap, and a profile's own map the first directory's")
        void gainMapPrecedence() {
            var plain = linear(16, 16).write();
            var reference = linear(16, 16);
            reference.first.signedRationals(50721, ALTERNATE);
            var alternate = reference.write();
            var builder = linear(16, 16);
            builder.first.undefined(52525, gainMap1(uniform(8))).undefined(52544, gainMap2(2, 2, 3, uniform(2)));
            var own = new DngBuilder(true, 0x4352);
            own.first.undefined(52544, gainMap2(2, 2, 3, uniform(4)));
            var bytes = profiles(builder, own);
            // the first directory's second version (gain 2) applies, not the first version (gain 8)
            assertArrayEquals(render(plain, RawDecoderOptions.builder().exposure(1).build()), render(bytes));
            assertArrayEquals(render(alternate, RawDecoderOptions.builder().exposure(1).build()),
                    render(bytes, RawDecoderOptions.builder().cameraProfile("Inherited").build()));
            assertArrayEquals(render(alternate, RawDecoderOptions.builder().exposure(2).build()),
                    render(bytes, RawDecoderOptions.builder().cameraProfile("Own").build()));
            // without a second version, the first applies
            var first = linear(16, 16);
            first.first.undefined(52525, gainMap1(uniform(2)));
            assertArrayEquals(render(plain, RawDecoderOptions.builder().exposure(1).build()), render(first.write()));
        }

        @Test
        @DisplayName("a profile's own RGB tables replace those of the first directory, which apply to a profile without")
        void tablePrecedence() {
            var builder = linear(16, 16);
            builder.first.undefined(52543, constantTable(null, 0.3f, 0));
            var own = new DngBuilder(true, 0x4352);
            own.first.undefined(52543, constantTable(null, 0.6f, 0));
            var bytes = profiles(builder, own);
            var low = linear(16, 16);
            low.first.undefined(52543, constantTable(null, 0.3f, 0));
            var high = linear(16, 16);
            high.first.undefined(52543, constantTable(null, 0.6f, 0));
            assertArrayEquals(render(low.write()), render(bytes));
            assertArrayEquals(render(low.write()), render(bytes, RawDecoderOptions.builder().cameraProfile("Inherited").build()));
            assertArrayEquals(render(high.write()), render(bytes, RawDecoderOptions.builder().cameraProfile("Own").build()));
            assertNotEquals(render(low.write())[0], render(high.write())[0]);
        }

        @Test
        @DisplayName("a profile's BaselineExposureOffset and calibration signature replace the primary profile's")
        void exposureAndCalibration() {
            var calibration = new double[]{1.5, 0, 0, 0, 1, 0, 0, 0, 0.8};
            var builder = linear(16, 16);
            builder.first.ascii(50936, "Primary").signedRationals(51109, 1).signedRationals(50723, calibration)
                    .ascii(50931, "camera").ascii(50932, "camera");
            var matching = new DngBuilder(true, 0x4352);
            matching.first.ascii(50936, "Matching").signedRationals(50721, ALTERNATE).shorts(50778, 21)
                    .ascii(50932, "camera");
            var other = new DngBuilder(true, 0x4352);
            other.first.ascii(50936, "Other").signedRationals(50721, ALTERNATE).shorts(50778, 21)
                    .ascii(50932, "another camera").signedRationals(51109, -0.5);
            builder.first.blobs(50933, matching.write(), other.write());
            var bytes = builder.write();
            var primary = linear(16, 16);
            primary.first.signedRationals(50723, calibration);
            assertArrayEquals(render(primary.write(), RawDecoderOptions.builder().exposure(1).build()), render(bytes));
            var calibrated = linear(16, 16);
            calibrated.first.signedRationals(50721, ALTERNATE).signedRationals(50723, calibration);
            assertArrayEquals(render(calibrated.write()),
                    render(bytes, RawDecoderOptions.builder().cameraProfile("Matching").build()));
            var uncalibrated = linear(16, 16);
            uncalibrated.first.signedRationals(50721, ALTERNATE);
            assertArrayEquals(render(uncalibrated.write(), RawDecoderOptions.builder().exposure(-0.5).build()),
                    render(bytes, RawDecoderOptions.builder().cameraProfile("Other").build()));
            assertFalse(java.util.Arrays.equals(render(calibrated.write()), render(uncalibrated.write())));
        }

        @Test
        @DisplayName("a profile that is not a camera profile structure is left out")
        void malformed() {
            var builder = linear(16, 16);
            builder.first.blobs(50933, new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0});
            assertEquals(List.of(""), RawDecoder.info(MediaSource.of(builder.write())).cameraProfiles());
        }
    }

    @Nested
    @DisplayName("enhanced image")
    class Enhanced {
        static DngBuilder file(int factor) {
            var builder = profile(new DngBuilder());
            header(builder.first, 24, 16, 1);
            builder.first.shorts(33421, 2, 2).bytes(33422, 0, 1, 1, 2).rationals(50719, 2, 2).rationals(50720, 20, 12)
                    .strips(16, words(gradient(24, 16, 1)));
            var enhanced = builder.child();
            header(enhanced, 24 * factor, 16 * factor, 3);
            enhanced.longs(254, 16).shorts(50714, 1000, 1000, 1000).strips(16 * factor,
                    words(gradient(24 * factor, 16 * factor, 3)));
            return builder;
        }

        @Test
        @DisplayName("is decoded instead of the raw image when asked, its black level subtracted")
        void decodeRaw() {
            var bytes = file(1).write();
            assertTrue(RawDecoder.info(MediaSource.of(bytes)).hasEnhancedImage());
            assertEquals(1, RawDecoder.decodeRaw(MediaSource.of(bytes)).channels());
            var options = RawDecoderOptions.builder().enhancedImage(true).defaultCrop(false).build();
            var enhanced = RawDecoder.decodeRaw(MediaSource.of(bytes), options);
            assertEquals(3, enhanced.channels());
            var stored = gradient(24, 16, 3);
            for (var index = 0; index < stored.length; index++) {
                var expected = Math.round((stored[index] - 1000) / (65535.0 - 1000) * 65535);
                assertEquals(expected, enhanced.samples()[index] & 0xFFFF, 1, "sample " + index);
            }
        }

        @ParameterizedTest(name = "factor {0}")
        @ValueSource(ints = {1, 2})
        @DisplayName("renders with the raw image's crop scaled to its size")
        void crop(int factor) {
            var bytes = file(factor).write();
            var picture = RawDecoder.decode(MediaSource.of(bytes), RawDecoderOptions.builder().enhancedImage(true).build());
            assertEquals(20 * factor, picture.picture().width());
            assertEquals(12 * factor, picture.picture().height());
            assertEquals(20 * factor, picture.sourceWidth());
            assertEquals(20, RawDecoder.decode(MediaSource.of(bytes)).picture().width());
        }

        @Test
        @DisplayName("renders like a linear raw file holding the same values")
        void rendering() {
            var bytes = file(1).write();
            var reference = profile(new DngBuilder());
            header(reference.first, 24, 16, 3);
            reference.first.shorts(50714, 1000, 1000, 1000).rationals(50719, 2, 2).rationals(50720, 20, 12)
                    .strips(16, words(gradient(24, 16, 3)));
            assertArrayEquals(render(reference.write()), render(bytes, RawDecoderOptions.builder().enhancedImage(true).build()));
        }
    }

    @Nested
    @DisplayName("previews for size hints")
    class Previews {
        @Test
        @DisplayName("a reduced-resolution linear raw preview is rendered when it meets the hint, its gain divided out")
        void linearRawPreview() {
            var builder = linear(64, 48);
            var proxy = builder.child();
            header(proxy, 40, 30, 3);
            var values = gradient(40, 30, 3);
            var scaled = values.clone();
            for (var index = 0; index < scaled.length; index++) {
                scaled[index] = Math.round(scaled[index] * 1.5);
            }
            proxy.longs(254, 1).rationals(51112, 1.5).strips(30, words(scaled));
            var bytes = builder.write();
            // the half-size rendering (32 by 24) cannot meet the hint, the preview can and is smaller than the full one
            var hinted = RawDecoder.decode(MediaSource.of(bytes), RawDecoderOptions.builder().minSize(34, 25).build());
            assertEquals(40, hinted.picture().width());
            assertEquals(64, hinted.sourceWidth());
            var reference = linear(40, 30);
            assertClose(render(reference.write()), pixels(hinted.picture()), 1);
            var full = RawDecoder.decode(MediaSource.of(bytes), RawDecoderOptions.builder().minSize(60, 40).build());
            assertEquals(64, full.picture().width());
            // a linear raw image has no half-size rendering, so the preview meets a smaller hint too
            var small = RawDecoder.decode(MediaSource.of(bytes), RawDecoderOptions.builder().minSize(30, 20).build());
            assertEquals(40, small.picture().width());
            // the preview shows the default crop with the opcode lists applied, so it serves no rendering without them
            for (var options : List.of(RawDecoderOptions.builder().minSize(34, 25).defaultCrop(false).build(),
                    RawDecoderOptions.builder().minSize(34, 25).opcodes(false).build())) {
                assertEquals(64, RawDecoder.decode(MediaSource.of(bytes), options).picture().width());
            }
        }

        @Test
        @DisplayName("a JPEG XL preview meets a hint, in one tile or two")
        void jpegXl() throws Exception {
            var jxl = Conformance.fetch("jxl-conformance-patches_lossless.jxl",
                    "https://raw.githubusercontent.com/libjxl/conformance/b1d0f990b03e57bf6d137c365cd5dc8b470b9191/testcases/patches_lossless/input.jxl",
                    "2a47b6766264ac6243a43bbb85684688717591bb3bd9b4c2db56226192418b4e");
            var stream = Files.readAllBytes(jxl);
            var expected = ImageOps.toRgba(JxlDecoder.decode(MediaSource.of(jxl)).picture());
            var width = expected.width();
            var height = expected.height();
            for (var tiles = 1; tiles <= 2; tiles++) {
                var builder = linear(16, 16);
                var preview = builder.child();
                preview.longs(254, 1).longs(256, (long) width * tiles).longs(257, height).shorts(258, 8, 8, 8)
                        .shorts(259, 52546).shorts(262, 2).shorts(277, 3);
                if (tiles == 1) {
                    preview.strips(height, stream);
                } else {
                    preview.tiles(width, height, stream, stream);
                }
                var bytes = builder.write();
                var info = RawDecoder.info(MediaSource.of(bytes));
                assertEquals(ImageFormat.JXL, info.previews().getFirst().format());
                // a hint the 16 by 16 raw image cannot meet, which the preview meets without halving
                var decoded = ImageOps.toRgba(RawDecoder.decode(MediaSource.of(bytes),
                        RawDecoderOptions.ofMinimumSize(width * tiles / 2 + 1, height / 2 + 1)).picture());
                assertEquals(width * tiles, decoded.width());
                for (var tile = 0; tile < tiles; tile++) {
                    for (var y = 0; y < height; y += 7) {
                        for (var x = 0; x < width; x += 5) {
                            for (var c = 0; c < 3; c++) {
                                assertEquals(expected.pixels()[y * expected.stride() + x * 4 + c] & 0xFF,
                                        decoded.pixels()[y * decoded.stride() + (tile * width + x) * 4 + c] & 0xFF);
                            }
                        }
                    }
                }
                assertTrue(RawDecoder.extractPreview(MediaSource.of(bytes)).isEmpty());
            }
        }
    }

    @Nested
    @DisplayName("four-color sensors")
    class FourColors {
        static final double[] MATRIX = {0.4, 0.6, -0.1, 0.1, 0.9, 0, 0.6, 0.2, 0.3, -0.1, 0.4, 0.7};

        static DngBuilder file(boolean matrices) {
            var builder = new DngBuilder();
            header(builder.first, 32, 24, 1);
            builder.first.shorts(33421, 2, 2).bytes(33422, 1, 4, 3, 5).bytes(50710, 1, 4, 3, 5)
                    .bytes(50706, 1, 7, 1, 0).rationals(50728, 0.7, 1, 0.8, 0.9).shorts(50778, 21)
                    .longs(50717, 65535).strips(24, words(gradient(32, 24, 1)));
            if (matrices) {
                builder.first.signedRationals(50721, MATRIX);
            }
            return builder;
        }

        @Test
        @DisplayName("a file without matrices renders with a generic matrix, and a twelve-value matrix renders it as the same matrix in the file does")
        void matrixOverride() {
            var without = file(false).write();
            var info = RawDecoder.info(MediaSource.of(without));
            assertTrue(info.isDecodable(), info.unsupportedFeature().orElse(""));
            assertFalse(info.isColorCalibrated());
            assertTrue(RawDecoder.info(MediaSource.of(file(true).write())).isColorCalibrated());
            var generic = render(without);
            assertFalse(java.util.Arrays.equals(render(file(true).write()), generic));
            var options = RawDecoderOptions.builder().colorMatrix(MATRIX).build();
            assertArrayEquals(render(file(true).write()), render(without, options));
            assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decode(MediaSource.of(without),
                    RawDecoderOptions.builder().colorMatrix(IDENTITY).build()));
            assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decode(MediaSource.of(linear(8, 8).write()),
                    options));
        }

        @Test
        @DisplayName("the sensor image of a file without matrices has the as-shot neutral and a generic matrix")
        void decodeRaw() {
            var raw = RawDecoder.decodeRaw(MediaSource.of(file(false).write()));
            assertArrayEquals(new double[]{0.7, 1, 0.8, 0.9}, raw.cameraNeutral(), 1e-4);
            assertEquals(12, raw.balancedToXyz().length);
            var balanced = RawDecoder.decodeRaw(MediaSource.of(file(false).write()), RawDecoderOptions.builder()
                    .whiteBalance(RawWhiteBalance.multipliers(1, 2, 1, 2)).build());
            assertArrayEquals(new double[]{1, 0.5, 1, 0.5}, balanced.cameraNeutral(), 1e-9);
            // the generic matrix gives the camera neutral of a color temperature
            var warm = RawDecoder.decodeRaw(MediaSource.of(file(false).write()), RawDecoderOptions.builder()
                    .whiteBalance(RawWhiteBalance.temperature(3000)).build()).cameraNeutral();
            var cool = RawDecoder.decodeRaw(MediaSource.of(file(false).write()), RawDecoderOptions.builder()
                    .whiteBalance(RawWhiteBalance.temperature(8000)).build()).cameraNeutral();
            assertEquals(4, warm.length);
            assertFalse(java.util.Arrays.equals(warm, cool));
        }

        @Test
        @DisplayName("a file without matrices converts to DNG with the generic matrix it renders with")
        void convertGeneric() {
            var without = file(false).write();
            var dng = DngEncoder.convert(MediaSource.of(without));
            var source = RawDecoder.decodeRaw(MediaSource.of(without));
            var converted = RawDecoder.decodeRaw(MediaSource.of(dng));
            assertArrayEquals(source.cameraNeutral(), converted.cameraNeutral(), 1e-6);
            assertArrayEquals(source.balancedToXyz(), converted.balancedToXyz(), 1e-6);
            assertArrayEquals(source.samples(), converted.samples());
        }

        @Test
        @DisplayName("four multipliers equal to the as-shot balance render as shot, three are refused")
        void multipliers() {
            var bytes = file(true).write();
            var asShot = render(bytes);
            var options = RawDecoderOptions.builder()
                    .whiteBalance(RawWhiteBalance.multipliers(1 / 0.7, 1, 1 / 0.8, 1 / 0.9)).build();
            assertClose(asShot, render(bytes, options), 1);
            assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decode(MediaSource.of(bytes),
                    RawDecoderOptions.builder().whiteBalance(RawWhiteBalance.multipliers(1, 1, 1)).build()));
            assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decode(MediaSource.of(linear(8, 8).write()),
                    RawDecoderOptions.builder().whiteBalance(RawWhiteBalance.multipliers(1, 1, 1, 1)).build()));
        }

        @Test
        @DisplayName("blended highlights turn a fully saturated area neutral and differ from clipped ones")
        void highlights() {
            var builder = file(true);
            var values = gradient(32, 24, 1);
            for (var y = 0; y < 12; y++) {
                for (var x = 0; x < 32; x++) {
                    values[y * 32 + x] = 65535;
                }
            }
            builder.first.strips(24, words(values));
            var bytes = builder.write();
            var blend = render(bytes, RawDecoderOptions.builder().highlights(RawHighlights.BLEND).build());
            var clip = render(bytes);
            var at = (4 * 32 + 16) * 3;
            assertEquals(blend[at], blend[at + 1], 2);
            assertEquals(blend[at + 1], blend[at + 2], 2);
            assertFalse(java.util.Arrays.equals(blend, clip));
        }
    }

    @Nested
    @DisplayName("option and value types")
    class Types {
        @Test
        @DisplayName("the new options round-trip through the builder and take part in equality")
        void options() {
            var options = RawDecoderOptions.builder().enhancedImage(true).userCrop(true).autoBlack(true)
                    .cameraProfile("Neutral").build();
            assertTrue(options.enhancedImage());
            assertTrue(options.userCrop());
            assertTrue(options.autoBlack());
            assertEquals("Neutral", options.cameraProfile().orElseThrow());
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertNotEquals(options, options.toBuilder().asShotProfile().build());
            assertTrue(options.toBuilder().asShotProfile().build().cameraProfile().isEmpty());
            var defaults = RawDecoderOptions.defaults();
            assertFalse(defaults.enhancedImage() || defaults.userCrop() || defaults.autoBlack());
            assertThrows(NullPointerException.class, () -> RawDecoderOptions.builder().cameraProfile(null));
            assertTrue(options.toString().contains("cameraProfile=Neutral"));
        }

        @Test
        @DisplayName("color matrices of nine or twelve values of full column rank are accepted")
        void matrices() {
            assertEquals(12, RawDecoderOptions.builder().colorMatrix(FourColors.MATRIX).build().colorMatrix().orElseThrow().length);
            assertThrows(IllegalArgumentException.class, () -> RawDecoderOptions.builder().colorMatrix(new double[10]).build());
            var rankTwo = new double[]{1, 0, 0, 0, 1, 0, 1, 1, 0, 2, 0, 0};
            assertThrows(IllegalArgumentException.class, () -> RawDecoderOptions.builder().colorMatrix(rankTwo).build());
        }

        @Test
        @DisplayName("multipliers are three or four positive factors")
        void multipliers() {
            var four = RawWhiteBalance.multipliers(1.0, 2.0, 3.0, 4.0);
            assertEquals(List.of(1.0, 2.0, 3.0, 4.0), four.factors());
            assertEquals(2.0, four.green());
            assertEquals(RawWhiteBalance.multipliers(2, 1, 1.5), new RawWhiteBalance.Multipliers(List.of(2.0, 1.0, 1.5)));
            assertThrows(IllegalArgumentException.class, () -> RawWhiteBalance.multipliers(1.0, 2.0));
            assertThrows(IllegalArgumentException.class, () -> RawWhiteBalance.multipliers(1.0, 2.0, 3.0, 4.0, 5.0));
            assertThrows(IllegalArgumentException.class, () -> RawWhiteBalance.multipliers(1.0, 2.0, 3.0, -1.0));
            assertThrows(UnsupportedOperationException.class, () -> four.factors().add(1.0));
        }

        @Test
        @DisplayName("previews are JPEG or JPEG XL images")
        void previews() {
            assertEquals(ImageFormat.JPEG, new RawPreview(4, 4, 10).format());
            assertEquals(ImageFormat.JXL, new RawPreview(4, 4, 10, ImageFormat.JXL).format());
            assertThrows(IllegalArgumentException.class, () -> new RawPreview(4, 4, 10, ImageFormat.PNG));
        }
    }
}
