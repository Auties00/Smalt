package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code ImageOps.convertColor} and {@code ImageOps.toSrgb} on every picture type.
 */
class PictureConversionTest {
    static RgbaPicture randomRgba(int width, int height, boolean alpha, long seed) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        new SplittableRandom(seed).nextBytes(picture.pixels());
        return picture;
    }

    static YuvPicture randomYuv(int width, int height, ColorInfo color, long seed) {
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, color);
        var random = new SplittableRandom(seed);
        random.nextBytes(picture.y());
        random.nextBytes(picture.u());
        random.nextBytes(picture.v());
        return picture;
    }

    @Nested
    @DisplayName("convertColor")
    class Convert {
        @Test
        @DisplayName("converts packed RGBA like the raw transform and keeps alpha")
        void rgba() {
            var picture = randomRgba(97, 61, true, 1);
            var transform = ColorTransform.create(ColorProfile.displayP3(), ColorProfile.srgb());
            var converted = (RgbaPicture) ImageOps.convertColor(picture, transform);
            var expected = picture.pixels().clone();
            transform.transform(expected, 0, 4, expected, 0, 4, 97 * 61);
            assertArrayEquals(expected, converted.pixels());
            assertTrue(converted.hasAlpha());
        }

        @Test
        @DisplayName("converts planar pictures through their RGBA form")
        void yuv() {
            var picture = randomYuv(64, 48, ColorInfo.JPEG, 2);
            var direct = (RgbaPicture) ImageOps.convertColor(picture, ColorProfile.adobeRgb(), ColorProfile.srgb());
            var viaRgba = (RgbaPicture) ImageOps.convertColor(ImageOps.toRgba(picture), ColorProfile.adobeRgb(),
                    ColorProfile.srgb());
            assertArrayEquals(viaRgba.pixels(), direct.pixels());
        }

        @Test
        @DisplayName("keeps 16-bit pictures at 16 bits and agrees with 8 bits")
        void sixteen() {
            var picture = randomRgba(40, 30, false, 3);
            var wide = ImageOps.toSixteenBit(picture);
            var converted = ImageOps.convertColor(wide, ColorProfile.proPhotoRgb(), ColorProfile.srgb());
            assertInstanceOf(Rgba16Picture.class, converted);
            var narrow = (RgbaPicture) ImageOps.convertColor(picture, ColorProfile.proPhotoRgb(), ColorProfile.srgb());
            var reduced = ImageOps.toEightBit((Rgba16Picture) converted);
            for (var i = 0; i < narrow.pixels().length; i++) {
                assertTrue(Math.abs((narrow.pixels()[i] & 0xFF) - (reduced.pixels()[i] & 0xFF)) <= 1, "sample " + i);
            }
        }

        @Test
        @DisplayName("reads gray from red and writes gray to all three channels")
        void gray() {
            var picture = randomRgba(16, 16, false, 4);
            var toGray = (RgbaPicture) ImageOps.convertColor(picture, ColorProfile.srgb(), ColorProfile.grayGamma22());
            for (var i = 0; i < toGray.pixels().length; i += 4) {
                assertEquals(toGray.pixels()[i], toGray.pixels()[i + 1]);
                assertEquals(toGray.pixels()[i], toGray.pixels()[i + 2]);
            }
            var fromGray = (RgbaPicture) ImageOps.convertColor(toGray, ColorProfile.grayGamma22(), ColorProfile.srgb());
            for (var i = 0; i < fromGray.pixels().length; i += 4) {
                assertEquals(fromGray.pixels()[i], fromGray.pixels()[i + 1]);
            }
        }

        @Test
        @DisplayName("rejects profiles that are neither RGB nor gray")
        void rejectsCmyk() {
            var cmyk = TestProfiles.profile("skia-icc-misc-Coated_FOGRA39_CMYK.icc");
            assertThrows(IllegalArgumentException.class, () -> ImageOps.convertColor(randomRgba(4, 4, false, 5), cmyk,
                    ColorProfile.srgb()));
        }
    }

    @Nested
    @DisplayName("toSrgb")
    class ToSrgb {
        @Test
        @DisplayName("leaves BT.709 and JPEG pictures as toRgba converts them")
        void identity() {
            for (var color : new ColorInfo[]{ColorInfo.BT709_LIMITED, ColorInfo.JPEG}) {
                var picture = randomYuv(33, 21, color, 6);
                assertArrayEquals(ImageOps.toRgba(picture).pixels(), ImageOps.toSrgb(picture).pixels());
            }
            var rgba = randomRgba(5, 5, true, 7);
            assertSame(rgba, ImageOps.toSrgb(rgba));
        }

        @Test
        @DisplayName("tone-maps a 10-bit PQ BT.2020 frame: reference white becomes a bright neutral")
        void hdrFrame() {
            var color = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ);
            var picture = Yuv16Picture.allocate(8, 8, ChromaFormat.YUV420, 10, false, color);
            var white = (int) Math.round(Hdr.nitsToPq(203) * 876 + 64);
            java.util.Arrays.fill(picture.y(), (short) white);
            java.util.Arrays.fill(picture.u(), (short) 512);
            java.util.Arrays.fill(picture.v(), (short) 512);
            var converted = ImageOps.toSrgb(picture);
            var pixels = converted.pixels();
            for (var i = 0; i < pixels.length; i += 4) {
                assertTrue((pixels[i] & 0xFF) >= 220, "pixel " + i / 4 + " is " + (pixels[i] & 0xFF));
                assertTrue(Math.abs((pixels[i] & 0xFF) - (pixels[i + 1] & 0xFF)) <= 1);
                assertTrue(Math.abs((pixels[i] & 0xFF) - (pixels[i + 2] & 0xFF)) <= 1);
            }
            var clipped = ImageOps.toSrgb(picture, ColorTransformOptions.builder().toneMapping(ToneMapping.CLIP).build());
            assertTrue((clipped.pixels()[0] & 0xFF) >= 253, "clipped white is " + (clipped.pixels()[0] & 0xFF));
        }

        @Test
        @DisplayName("converts a decoded image with its profile and leaves one without a profile alone")
        void decodedImage() {
            var picture = randomRgba(10, 10, false, 8);
            var plain = new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 10, 10, Optional.empty(), Optional.empty());
            assertSame(plain, ImageOps.toSrgb(plain));
            var p3 = new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 10, 10,
                    Optional.of(ColorProfile.displayP3().toByteArray()), Optional.empty());
            assertEquals(ColorProfile.displayP3(), p3.colorProfile().orElseThrow());
            var converted = ImageOps.toSrgb(p3);
            assertTrue(converted.iccProfile().isEmpty());
            assertEquals(ColorProfile.srgb(), converted.colorProfile().orElseThrow());
            var expected = (RgbaPicture) ImageOps.convertColor(picture, ColorProfile.displayP3(), ColorProfile.srgb());
            assertArrayEquals(expected.pixels(), ((RgbaPicture) converted.picture()).pixels());
            var broken = new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 10, 10,
                    Optional.of(new byte[]{1, 2, 3}), Optional.empty());
            assertTrue(broken.colorProfile().isEmpty());
            assertSame(broken, ImageOps.toSrgb(broken));
        }
    }
}
