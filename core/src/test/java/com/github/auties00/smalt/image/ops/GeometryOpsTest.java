package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("geometry operations")
class GeometryOpsTest {
    private static RgbaPicture rgb(int width, int height) {
        return Pictures.patternRgba(width, height, false);
    }

    private static byte[] pixels(Picture picture) {
        return Pictures.tight((RgbaPicture) picture);
    }

    @Nested
    @DisplayName("rotate")
    class Rotate {
        @ParameterizedTest(name = "{0} degrees")
        @ValueSource(ints = {90, 180, 270, -90, 450})
        @DisplayName("by right angles equals the matching orientation, for every interpolation")
        void rightAngles(int degrees) {
            var picture = rgb(37, 21);
            var orientation = switch (Math.floorMod(degrees, 360)) {
                case 90 -> Orientation.ROTATE_90;
                case 180 -> Orientation.ROTATE_180;
                default -> Orientation.ROTATE_270;
            };
            var expected = pixels(ImageOps.orient(picture, orientation));
            for (var interpolation : Interpolation.values()) {
                assertArrayEquals(expected, pixels(ImageOps.rotate(picture, degrees, interpolation, RotationCanvas.EXPAND, 0)));
            }
        }

        @Test
        @DisplayName("by a multiple of 360 degrees returns the input")
        void fullTurn() {
            var picture = rgb(9, 7);
            assertSame(picture, ImageOps.rotate(picture, 0));
            assertSame(picture, ImageOps.rotate(picture, 720));
        }

        @Test
        @DisplayName("keeps the representation of a Y'CbCr picture on right angles")
        void yuvRightAngle() {
            var yuv = ImageOps.toYuv(rgb(16, 10), ChromaFormat.YUV420, ColorInfo.JPEG);
            assertInstanceOf(YuvPicture.class, ImageOps.rotate(yuv, 90));
            assertInstanceOf(RgbaPicture.class, ImageOps.rotate(yuv, 30));
        }

        @Test
        @DisplayName("keeps the canvas size or crops on right angles with KEEP and INSCRIBED")
        void rightAngleCanvases() {
            var picture = rgb(40, 20);
            var kept = ImageOps.rotate(picture, 90, Interpolation.BILINEAR, RotationCanvas.KEEP, 0xFF00FF00);
            assertEquals(40, kept.width());
            assertEquals(20, kept.height());
            var inscribed = ImageOps.rotate(picture, 90, Interpolation.BILINEAR, RotationCanvas.INSCRIBED, 0);
            assertEquals(20, inscribed.width());
            assertEquals(10, inscribed.height());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Interpolation.class)
        @DisplayName("sizes the expanded canvas to the rotated bounding box")
        void expandedSize(Interpolation interpolation) {
            var rotated = ImageOps.rotate(rgb(100, 50), 30, interpolation, RotationCanvas.EXPAND, 0);
            assertEquals((int) Math.ceil(100 * Math.cos(Math.PI / 6) + 50 * 0.5 - 1e-9), rotated.width());
            assertEquals((int) Math.ceil(100 * 0.5 + 50 * Math.cos(Math.PI / 6) - 1e-9), rotated.height());
            assertTrue(rotated.hasAlpha());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Interpolation.class)
        @DisplayName("shows no background on an inscribed canvas")
        void inscribedHasNoBackground(Interpolation interpolation) {
            var picture = Pictures.smoothRgba(80, 60, false);
            var rotated = (RgbaPicture) ImageOps.rotate(picture, 17, interpolation, RotationCanvas.INSCRIBED, 0);
            assertFalse(rotated.hasAlpha());
            assertTrue(rotated.width() < 80 && rotated.height() < 60);
            var opaque = (RgbaPicture) ImageOps.rotate(picture, 17, interpolation, RotationCanvas.INSCRIBED, 0x00FF00FF);
            for (var i = 0; i < opaque.width() * opaque.height(); i++) {
                assertEquals(0xFF, opaque.pixels()[4 * i + 3] & 0xFF);
                assertFalse((opaque.pixels()[4 * i] & 0xFF) == 0xFF && (opaque.pixels()[4 * i + 2] & 0xFF) == 0xFF
                        && opaque.pixels()[4 * i + 1] == 0, "background shows at " + i);
            }
        }

        @Test
        @DisplayName("turned there and back reproduces the interior closely")
        void roundTrip() {
            var picture = Pictures.smoothRgba(64, 64, false);
            var there = ImageOps.rotate(picture, 23, Interpolation.BICUBIC, RotationCanvas.KEEP, 0xFF000000);
            var back = (RgbaPicture) ImageOps.rotate(there, -23, Interpolation.BICUBIC, RotationCanvas.KEEP, 0xFF000000);
            var worst = 0;
            for (var y = 20; y < 44; y++) {
                for (var x = 20; x < 44; x++) {
                    for (var c = 0; c < 3; c++) {
                        var i = y * back.stride() + 4 * x + c;
                        worst = Math.max(worst, Math.abs((back.pixels()[i] & 0xFF) - (picture.pixels()[i] & 0xFF)));
                    }
                }
            }
            assertTrue(worst <= 3, "worst difference " + worst);
        }

        @Test
        @DisplayName("antialiases edges against a transparent background without darkening them")
        void premultipliedEdges() {
            var white = RgbaPicture.allocate(20, 20, false);
            java.util.Arrays.fill(white.pixels(), (byte) 255);
            var rotated = (RgbaPicture) ImageOps.rotate(white, 10, Interpolation.BILINEAR, RotationCanvas.EXPAND, 0);
            var partial = 0;
            for (var i = 0; i < rotated.width() * rotated.height(); i++) {
                var alpha = rotated.pixels()[4 * i + 3] & 0xFF;
                if (alpha > 0) {
                    assertEquals(255, rotated.pixels()[4 * i] & 0xFF, "color of pixel " + i + " with alpha " + alpha);
                    if (alpha < 255) {
                        partial++;
                    }
                }
            }
            assertTrue(partial > 0);
        }

        @Test
        @DisplayName("keeps 16-bit depth and agrees with the 8-bit rotation")
        void sixteenBit() {
            var picture = Pictures.smoothRgba(30, 24, false);
            var deep = ImageOps.rotate(ImageOps.toSixteenBit(picture), 33, Interpolation.BICUBIC, RotationCanvas.EXPAND, 0x80FF0000);
            var shallow = (RgbaPicture) ImageOps.rotate(picture, 33, Interpolation.BICUBIC, RotationCanvas.EXPAND, 0x80FF0000);
            var reduced = ImageOps.toEightBit((Rgba16Picture) deep);
            assertEquals(shallow.width(), reduced.width());
            for (var i = 0; i < shallow.pixels().length; i++) {
                assertTrue(Math.abs((shallow.pixels()[i] & 0xFF) - (reduced.pixels()[i] & 0xFF)) <= 1, "sample " + i);
            }
        }

        @Test
        @DisplayName("rejects non-finite angles")
        void invalid() {
            assertThrows(IllegalArgumentException.class, () -> ImageOps.rotate(rgb(4, 4), Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.rotate(rgb(4, 4), Double.POSITIVE_INFINITY));
        }
    }

    @Nested
    @DisplayName("affine")
    class Affine {
        @ParameterizedTest(name = "{0}")
        @EnumSource(Interpolation.class)
        @DisplayName("the identity copies every visible pixel exactly")
        void identity(Interpolation interpolation) {
            var picture = Pictures.randomRgba(new SplittableRandom(5), 23, 17, true);
            // a fully transparent pixel has no color to preserve: interpolation returns it as transparent black
            for (var y = 0; y < 17; y++) {
                for (var x = 0; x < 23; x++) {
                    var alpha = y * picture.stride() + 4 * x + 3;
                    picture.pixels()[alpha] = (byte) Math.max(1, picture.pixels()[alpha] & 0xFF);
                }
            }
            var copy = ImageOps.affine(picture, AffineTransform.identity(), 23, 17, interpolation, 0);
            assertArrayEquals(pixels(picture), pixels(copy));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Interpolation.class)
        @DisplayName("an integer translation shifts the picture exactly")
        void translation(Interpolation interpolation) {
            var picture = rgb(20, 12);
            var shifted = (RgbaPicture) ImageOps.affine(picture, AffineTransform.translation(3, 2), 20, 12, interpolation,
                    0xFF000000);
            for (var y = 0; y < 12; y++) {
                for (var x = 0; x < 20; x++) {
                    for (var c = 0; c < 4; c++) {
                        var actual = shifted.pixels()[y * shifted.stride() + 4 * x + c] & 0xFF;
                        var expected = x < 3 || y < 2 ? (c == 3 ? 255 : 0) : picture.pixels()[(y - 2) * picture.stride() + 4 * (x - 3) + c] & 0xFF;
                        assertEquals(expected, actual, "(" + x + "," + y + ")[" + c + "]");
                    }
                }
            }
        }

        @Test
        @DisplayName("a mirror scaling equals the horizontal flip")
        void mirror() {
            var picture = rgb(15, 9);
            var mirrored = ImageOps.affine(picture, AffineTransform.scaling(-1, 1).then(AffineTransform.translation(15, 0)),
                    15, 9, Interpolation.BICUBIC, 0);
            assertArrayEquals(pixels(ImageOps.flipHorizontal(picture)), pixels(mirrored));
        }

        @Test
        @DisplayName("composes, inverts and rejects singular maps")
        void algebra() {
            var transform = AffineTransform.rotation(30, 5, 7).then(AffineTransform.shearing(0.2, -0.1))
                    .then(AffineTransform.scaling(2, 3));
            var roundTrip = transform.then(transform.inverse());
            assertEquals(4.0, roundTrip.mapX(4, 9), 1e-9);
            assertEquals(9.0, roundTrip.mapY(4, 9), 1e-9);
            assertFalse(AffineTransform.scaling(0, 1).isInvertible());
            assertThrows(IllegalArgumentException.class,
                    () -> ImageOps.affine(rgb(4, 4), AffineTransform.scaling(0, 1), 4, 4, Interpolation.NEAREST, 0));
            assertEquals(0.0, AffineTransform.rotation(90).m00());
            assertEquals(1.0, AffineTransform.rotation(90).m10());
        }
    }

    @Nested
    @DisplayName("flips and transposes")
    class Flips {
        @Test
        @DisplayName("flipping twice restores the picture, for every representation")
        void involutions() {
            var rgba = rgb(11, 7);
            var yuv = ImageOps.toYuv(rgba, ChromaFormat.YUV420, ColorInfo.JPEG);
            for (var picture : new Picture[]{rgba, yuv, ImageOps.toSixteenBit(rgba)}) {
                for (var flip : new java.util.function.UnaryOperator[]{
                        p -> ImageOps.flipHorizontal((Picture) p), p -> ImageOps.flipVertical((Picture) p),
                        p -> ImageOps.transpose((Picture) p), p -> ImageOps.transverse((Picture) p)}) {
                    @SuppressWarnings("unchecked")
                    var twice = (Picture) flip.apply(flip.apply(picture));
                    assertEquals(picture.getClass(), twice.getClass());
                    assertArrayEquals(pixels(ImageOps.toRgba(picture)), pixels(ImageOps.toRgba(twice)));
                }
            }
        }
    }

    @Nested
    @DisplayName("trim")
    class Trim {
        @Test
        @DisplayName("removes a uniform border and keeps the representation")
        void border() {
            var content = rgb(10, 6);
            var padded = ImageOps.extend(content, 3, 5, 2, 4, ExtendMode.BACKGROUND, 0xFF102030);
            assertEquals(new Region(4, 3, 10, 6), ImageOps.trimBounds(padded, 0).orElseThrow());
            assertArrayEquals(pixels(content), pixels(ImageOps.trim(padded, 0)));
            var yuv = ImageOps.toYuv(padded, ChromaFormat.YUV444, ColorInfo.JPEG);
            assertInstanceOf(YuvPicture.class, ImageOps.trim(yuv, 2));
        }

        @Test
        @DisplayName("honors the tolerance and an explicit border color")
        void tolerance() {
            var picture = RgbaPicture.allocate(8, 8, false);
            java.util.Arrays.fill(picture.pixels(), (byte) 200);
            picture.pixels()[4 * (3 * 8 + 3)] = (byte) 205;
            assertEquals(new Region(3, 3, 1, 1), ImageOps.trimBounds(picture, 4).orElseThrow());
            assertTrue(ImageOps.trimBounds(picture, 5).isEmpty());
            assertSame(picture, ImageOps.trim(picture, 5));
            assertEquals(new Region(0, 0, 8, 8), ImageOps.trimBounds(picture, 0xFF000000, 0).orElseThrow());
        }

        @Test
        @DisplayName("works on 16-bit pictures")
        void sixteenBit() {
            var deep = ImageOps.toSixteenBit((RgbaPicture) ImageOps.extend(rgb(5, 5), 1, 1, 1, 1, ExtendMode.BACKGROUND, 0xFFFFFFFF));
            assertEquals(new Region(1, 1, 5, 5), ImageOps.trimBounds(deep, 0).orElseThrow());
            assertInstanceOf(Rgba16Picture.class, ImageOps.trim(deep, 0));
        }
    }

    @Nested
    @DisplayName("extend and canvas")
    class Extend {
        @ParameterizedTest(name = "{0}")
        @EnumSource(ExtendMode.class)
        @DisplayName("fills the border as the mode says")
        void modes(ExtendMode mode) {
            var picture = RgbaPicture.allocate(3, 1, false);
            for (var x = 0; x < 3; x++) {
                picture.pixels()[4 * x] = (byte) (10 * (x + 1));
            }
            var extended = (RgbaPicture) ImageOps.extend(picture, 0, 4, 0, 4, mode, 0xFF000001);
            var reds = new int[extended.width()];
            for (var x = 0; x < reds.length; x++) {
                reds[x] = extended.pixels()[4 * x] & 0xFF;
            }
            var expected = switch (mode) {
                case BACKGROUND -> new int[]{0, 0, 0, 0, 10, 20, 30, 0, 0, 0, 0};
                case REPLICATE -> new int[]{10, 10, 10, 10, 10, 20, 30, 30, 30, 30, 30};
                case MIRROR -> new int[]{30, 30, 20, 10, 10, 20, 30, 30, 20, 10, 10};
                case REPEAT -> new int[]{30, 10, 20, 30, 10, 20, 30, 10, 20, 30, 10};
            };
            assertArrayEquals(expected, reds);
        }

        @Test
        @DisplayName("crops without converting and pads with the background")
        void canvas() {
            var yuv = ImageOps.toYuv(rgb(20, 20), ChromaFormat.YUV420, ColorInfo.JPEG);
            var cropped = ImageOps.resizeCanvas(yuv, 10, 8, Anchor.BOTTOM_RIGHT, 0);
            assertInstanceOf(YuvPicture.class, cropped);
            var padded = (RgbaPicture) ImageOps.resizeCanvas(rgb(4, 4), 6, 6, Anchor.TOP_LEFT, 0xFFABCDEF);
            assertEquals(0xAB, padded.pixels()[4 * 35] & 0xFF);
            assertArrayEquals(java.util.Arrays.copyOfRange(rgb(4, 4).pixels(), 0, 16), java.util.Arrays.copyOfRange(padded.pixels(), 0, 16));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.extend(rgb(2, 2), -1, 0, 0, 0, ExtendMode.MIRROR, 0));
        }
    }

    @Nested
    @DisplayName("smart crop")
    class Smart {
        private static RgbaPicture withDetailOnTheRight() {
            var picture = RgbaPicture.allocate(300, 100, false);
            for (var y = 0; y < 100; y++) {
                for (var x = 0; x < 300; x++) {
                    var p = 4 * (y * 300 + x);
                    var busy = x >= 220 && ((x / 3 + y / 3) & 1) == 0;
                    picture.pixels()[p] = (byte) (busy ? 250 : 120);
                    picture.pixels()[p + 1] = (byte) (busy ? 20 : 120);
                    picture.pixels()[p + 2] = (byte) (busy ? 30 : 120);
                    picture.pixels()[p + 3] = (byte) 255;
                }
            }
            return picture;
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(SmartCropStrategy.class)
        @DisplayName("returns the largest region of the aspect ratio")
        void size(SmartCropStrategy strategy) {
            var region = ImageOps.findSmartCrop(withDetailOnTheRight(), 1, 1, strategy);
            assertEquals(100, region.width());
            assertEquals(100, region.height());
            assertTrue(region.x() >= 0 && region.x() + 100 <= 300);
        }

        @Test
        @DisplayName("centers with CENTER and follows the detail with ENTROPY and ATTENTION")
        void placement() {
            var picture = withDetailOnTheRight();
            assertEquals(100, ImageOps.findSmartCrop(picture, 1, 1, SmartCropStrategy.CENTER).x());
            assertTrue(ImageOps.findSmartCrop(picture, 1, 1, SmartCropStrategy.ENTROPY).x() >= 150);
            assertTrue(ImageOps.findSmartCrop(picture, 1, 1, SmartCropStrategy.ATTENTION).x() >= 150);
        }

        @Test
        @DisplayName("crops and resizes to the requested size")
        void crop() {
            var cropped = ImageOps.smartCrop(withDetailOnTheRight(), 64, 48, SmartCropStrategy.ATTENTION, ResizeFilter.LANCZOS3);
            assertEquals(64, cropped.width());
            assertEquals(48, cropped.height());
        }
    }
}
