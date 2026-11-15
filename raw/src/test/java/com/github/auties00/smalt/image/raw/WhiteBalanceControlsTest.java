package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the temperature and tint white balance and the automatic estimates through the public API, on synthetic
 * DNG files whose photosites have known colors.
 */
class WhiteBalanceControlsTest {
    /**
     * A DNG file of uniform red, green and blue photosites (black 0, white 65535), with one 2x2 block clipped and
     * one bright block when asked.
     */
    private static byte[] uniform(int red, int green, int blue, boolean clipped, boolean bright) {
        var dng = new TinyDng();
        dng.width = 64;
        dng.height = 48;
        dng.white = 65535;
        dng.neutral = new double[]{0.5, 1, 0.8};
        dng.samples = new int[dng.width * dng.height];
        for (var y = 0; y < dng.height; y++) {
            for (var x = 0; x < dng.width; x++) {
                var color = (y & 1) == 0 ? ((x & 1) == 0 ? red : green) : ((x & 1) == 0 ? green : blue);
                dng.samples[y * dng.width + x] = color;
            }
        }
        if (clipped) {
            for (var y = 10; y < 12; y++) {
                for (var x = 10; x < 12; x++) {
                    dng.samples[y * dng.width + x] = 65535;
                }
            }
        }
        if (bright) {
            // one block in 100 holding a white patch of other colors: the brightest blocks
            for (var y = 0; y < dng.height; y += 2) {
                for (var x = 0; x < dng.width; x += 2) {
                    if ((y / 2 * 32 + x / 2) % 100 == 7) {
                        dng.samples[y * dng.width + x] = 40000;
                        dng.samples[y * dng.width + x + 1] = 50000;
                        dng.samples[(y + 1) * dng.width + x] = 50000;
                        dng.samples[(y + 1) * dng.width + x + 1] = 60000;
                    }
                }
            }
        }
        return dng.write();
    }

    private static double[] neutral(byte[] file, RawWhiteBalance balance) {
        return RawDecoder.decodeRaw(MediaSource.of(file), RawDecoderOptions.builder().whiteBalance(balance).build())
                .cameraNeutral();
    }

    @Nested
    @DisplayName("automatic")
    class Automatic {
        @ParameterizedTest
        @EnumSource(RawWhiteBalance.Automatic.Method.class)
        @DisplayName("finds the neutral of a uniformly colored image, ignoring a clipped block")
        void uniformImage(RawWhiteBalance.Automatic.Method method) {
            var file = uniform(8000, 16000, 32000, true, false);
            assertArrayEquals(new double[]{0.25, 0.5, 1}, neutral(file, RawWhiteBalance.automatic(method)), 1e-4);
        }

        @ParameterizedTest
        @EnumSource(RawWhiteBalance.Automatic.Method.class)
        @DisplayName("measures an X-Trans image over blocks of its 6x6 repeat")
        void xTrans(RawWhiteBalance.Automatic.Method method) {
            var pattern = "GGRGGB" + "GGBGGR" + "BRGRBG" + "GGBGGR" + "GGRGGB" + "RBGBRG";
            var dng = new TinyDng();
            dng.width = 72;
            dng.height = 48;
            dng.white = 65535;
            dng.cfa = pattern;
            dng.neutral = new double[]{0.5, 1, 0.8};
            dng.samples = new int[dng.width * dng.height];
            for (var y = 0; y < dng.height; y++) {
                for (var x = 0; x < dng.width; x++) {
                    var color = pattern.charAt(y % 6 * 6 + x % 6);
                    dng.samples[y * dng.width + x] = color == 'R' ? 8000 : color == 'G' ? 16000 : 32000;
                }
            }
            var file = dng.write();
            assertArrayEquals(new double[]{0.25, 0.5, 1}, neutral(file, RawWhiteBalance.automatic(method)), 1e-4);
        }

        @Test
        @DisplayName("gray world averages every block, white patch the brightest ones")
        void methodsDiffer() {
            var file = uniform(8000, 16000, 32000, false, true);
            var patch = neutral(file, RawWhiteBalance.automatic(RawWhiteBalance.Automatic.Method.WHITE_PATCH));
            assertArrayEquals(new double[]{40000 / 60000.0, 50000 / 60000.0, 1}, patch, 1e-4);
            var gray = neutral(file, RawWhiteBalance.automatic());
            // 1 block in 100 of the patch moves the gray world mean only slightly from the background
            assertEquals(0.25, gray[0] / gray[2], 0.01);
            assertNotEquals(patch[0], gray[0], 0.1);
        }

        @Test
        @DisplayName("an image without a measurable block renders as shot")
        void unmeasurable() {
            var file = uniform(65535, 65535, 65535, false, false);
            assertArrayEquals(neutral(file, RawWhiteBalance.asShot()), neutral(file, RawWhiteBalance.automatic()),
                    1e-12);
        }

        @Test
        @DisplayName("rendering with the estimate turns the image gray")
        void rendersGray() {
            var file = uniform(8000, 16000, 32000, false, false);
            var picture = (com.github.auties00.smalt.image.RgbaPicture) RawDecoder.decode(MediaSource.of(file),
                    RawDecoderOptions.builder().whiteBalance(RawWhiteBalance.automatic()).embeddedPreview(false)
                            .build()).picture();
            var pixels = picture.pixels();
            var at = picture.height() / 2 * picture.stride() + picture.width() / 2 * 4;
            var r = pixels[at] & 0xFF;
            var g = pixels[at + 1] & 0xFF;
            var b = pixels[at + 2] & 0xFF;
            assertTrue(Math.abs(r - g) <= 2 && Math.abs(b - g) <= 2, r + " " + g + " " + b);
            var shot = (com.github.auties00.smalt.image.RgbaPicture) RawDecoder.decode(MediaSource.of(file),
                    RawDecoderOptions.builder().embeddedPreview(false).build()).picture();
            var p = shot.pixels();
            assertTrue(Math.abs((p[at] & 0xFF) - (p[at + 2] & 0xFF)) > 10, "as shot is not gray");
        }

        @Test
        @DisplayName("DNG conversion writes the estimated neutral")
        void conversion() {
            var file = uniform(8000, 16000, 32000, false, false);
            var options = RawDecoderOptions.builder().whiteBalance(RawWhiteBalance.automatic()).build();
            var dng = DngEncoder.convert(MediaSource.of(file), options, DngEncoderOptions.defaults());
            assertArrayEquals(new double[]{0.25, 0.5, 1}, RawDecoder.decodeRaw(MediaSource.of(dng)).cameraNeutral(),
                    1e-4);
        }
    }

    @Nested
    @DisplayName("tint")
    class Tint {
        @Test
        @DisplayName("a positive tint describes a greener light, whose camera neutral has more green")
        void direction() {
            var file = TinyDng.ramp(32, 32).write();
            var plain = neutral(file, RawWhiteBalance.temperature(5000));
            var green = neutral(file, RawWhiteBalance.temperature(5000, 40));
            var magenta = neutral(file, RawWhiteBalance.temperature(5000, -40));
            assertTrue(greenness(green) > greenness(plain) && greenness(plain) > greenness(magenta));
            assertArrayEquals(plain, neutral(file, RawWhiteBalance.temperature(5000, 0)), 0);
        }

        private static double greenness(double[] neutral) {
            return neutral[1] / Math.sqrt(neutral[0] * neutral[2]);
        }

        @ParameterizedTest
        @ValueSource(doubles = {-150.5, 150.5, Double.NaN})
        @DisplayName("rejects a tint outside -150 to 150")
        void range(double tint) {
            assertThrows(IllegalArgumentException.class, () -> RawWhiteBalance.temperature(5000, tint));
        }

        @Test
        @DisplayName("a temperature without a tint has tint 0")
        void defaults() {
            assertEquals(new RawWhiteBalance.Temperature(5000, 0), RawWhiteBalance.temperature(5000));
            assertEquals(0, RawWhiteBalance.daylight().tint());
        }
    }
}
