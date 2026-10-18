package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BufferedImages")
class BufferedImagesTest {
    static RgbaPicture gradient(int width, int height, boolean alpha) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                pixels[at] = (byte) (x * 7 + y);
                pixels[at + 1] = (byte) (y * 5 + 3 * x);
                pixels[at + 2] = (byte) (x * y + 11);
                // never zero, so lossless encoders keep the color under it
                pixels[at + 3] = alpha ? (byte) (1 + (x * 13 + y * 3) % 255) : (byte) 0xFF;
            }
        }
        return picture;
    }

    static int argb(RgbaPicture picture, int x, int y) {
        var at = y * picture.stride() + x * 4;
        var pixels = picture.pixels();
        return (pixels[at + 3] & 0xFF) << 24 | (pixels[at] & 0xFF) << 16 | (pixels[at + 1] & 0xFF) << 8 | (pixels[at + 2] & 0xFF);
    }

    @Nested
    @DisplayName("picture to image")
    class ToImage {
        @Test
        @DisplayName("an RGBA picture with alpha becomes TYPE_INT_ARGB with every sample kept")
        void argb() {
            var picture = gradient(37, 19, true);
            var image = BufferedImages.toBufferedImage(picture);
            assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
            for (var y = 0; y < 19; y++) {
                for (var x = 0; x < 37; x++) {
                    assertEquals(BufferedImagesTest.argb(picture, x, y), image.getRGB(x, y));
                }
            }
        }

        @Test
        @DisplayName("an opaque picture becomes TYPE_INT_RGB")
        void rgb() {
            var picture = gradient(8, 8, false);
            var image = BufferedImages.toBufferedImage(picture);
            assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
            assertEquals(BufferedImagesTest.argb(picture, 5, 3), image.getRGB(5, 3));
        }

        @Test
        @DisplayName("a monochrome YUV picture becomes TYPE_BYTE_GRAY with its RGB-converted luma stored as is")
        void gray() {
            var picture = YuvPicture.allocate(9, 4, ChromaFormat.MONOCHROME, false, ColorInfo.JPEG);
            for (var i = 0; i < picture.y().length; i++) {
                picture.y()[i] = (byte) (i * 7);
            }
            var image = BufferedImages.toBufferedImage(picture);
            assertEquals(BufferedImage.TYPE_BYTE_GRAY, image.getType());
            var rgba = ImageOps.toRgba(picture);
            var raster = image.getRaster();
            for (var y = 0; y < 4; y++) {
                for (var x = 0; x < 9; x++) {
                    assertEquals(rgba.pixels()[y * rgba.stride() + x * 4] & 0xFF, raster.getSample(x, y, 0));
                }
            }
        }

        @ParameterizedTest(name = "type {0}")
        @ValueSource(ints = {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_INT_ARGB_PRE})
        @DisplayName("an explicit alpha type keeps the pixels")
        void explicitType(int type) {
            var picture = gradient(13, 6, true);
            var image = BufferedImages.toBufferedImage(picture, type);
            assertEquals(type, image.getType());
            var back = BufferedImages.toPicture(image);
            for (var y = 0; y < 6; y++) {
                for (var x = 0; x < 13; x++) {
                    var expected = BufferedImagesTest.argb(picture, x, y);
                    var actual = BufferedImagesTest.argb(back, x, y);
                    if (type == BufferedImage.TYPE_INT_ARGB_PRE) {
                        // premultiplication rounds the color of translucent pixels
                        assertEquals(expected >>> 24, actual >>> 24);
                    } else {
                        assertEquals(expected, actual);
                    }
                }
            }
        }

        @Test
        @DisplayName("a custom type is rejected")
        void custom() {
            assertThrows(IllegalArgumentException.class,
                    () -> BufferedImages.toBufferedImage(gradient(2, 2, false), BufferedImage.TYPE_CUSTOM));
        }
    }

    @Nested
    @DisplayName("image to picture")
    class ToPicture {
        @ParameterizedTest(name = "type {0}")
        @ValueSource(ints = {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_4BYTE_ABGR,
                BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_INT_BGR, BufferedImage.TYPE_USHORT_565_RGB})
        @DisplayName("every type converts to the pixels getRGB reports")
        void types(int type) {
            var source = BufferedImages.toBufferedImage(gradient(21, 11, true), BufferedImage.TYPE_INT_ARGB);
            var image = new BufferedImage(21, 11, type);
            image.getGraphics().drawImage(source, 0, 0, null);
            var picture = BufferedImages.toPicture(image);
            assertEquals(image.getColorModel().hasAlpha(), picture.hasAlpha());
            for (var y = 0; y < 11; y++) {
                for (var x = 0; x < 21; x++) {
                    var expected = image.getRGB(x, y);
                    if (!picture.hasAlpha()) {
                        expected |= 0xFF000000;
                    }
                    assertEquals(expected, BufferedImagesTest.argb(picture, x, y), "pixel " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("a gray image keeps its stored samples")
        void gray() {
            var image = new BufferedImage(5, 3, BufferedImage.TYPE_BYTE_GRAY);
            for (var i = 0; i < 15; i++) {
                image.getRaster().setSample(i % 5, i / 5, 0, i * 17);
            }
            var picture = BufferedImages.toPicture(image);
            assertFalse(picture.hasAlpha());
            assertEquals(0xFF000000 | (7 * 17) * 0x010101, BufferedImagesTest.argb(picture, 2, 1));
        }

        @Test
        @DisplayName("a sub-image reads its own pixels, not the parent's origin")
        void subImage() {
            var parent = BufferedImages.toBufferedImage(gradient(30, 20, true));
            var child = parent.getSubimage(7, 5, 10, 8);
            var picture = BufferedImages.toPicture(child);
            assertEquals(10, picture.width());
            assertEquals(parent.getRGB(7 + 3, 5 + 2), BufferedImagesTest.argb(picture, 3, 2));
        }

        @Test
        @DisplayName("a region with steps samples every n-th pixel")
        void sampled() {
            var image = BufferedImages.toBufferedImage(gradient(30, 20, false));
            var picture = BufferedImages.toPicture(image, new Rectangle(1, 2, 25, 17), 3, 4);
            assertEquals(9, picture.width());
            assertEquals(5, picture.height());
            assertEquals(image.getRGB(1 + 3 * 8, 2 + 4 * 4), BufferedImagesTest.argb(picture, 8, 4));
        }
    }

    @Nested
    @DisplayName("image to 16-bit picture")
    class ToRgba16Picture {
        static BufferedImage sixteenBit(boolean gray, boolean alpha, int width, int height) {
            var space = ColorSpace.getInstance(gray ? ColorSpace.CS_GRAY : ColorSpace.CS_sRGB);
            var model = new ComponentColorModel(space, alpha, false,
                    alpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
            var image = new BufferedImage(model, model.createCompatibleWritableRaster(width, height), false, null);
            var raster = image.getRaster();
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    for (var band = 0; band < raster.getNumBands(); band++) {
                        raster.setSample(x, y, band, (x * 4099 + y * 257 + band * 9001 + 1) & 0xFFFF);
                    }
                }
            }
            return image;
        }

        @ParameterizedTest(name = "gray {0}, alpha {1}")
        @CsvSource({"false, false", "false, true", "true, false", "true, true"})
        @DisplayName("16-bit sRGB and gray components are copied sample for sample")
        void sixteenBitComponents(boolean gray, boolean alpha) {
            var image = sixteenBit(gray, alpha, 13, 7);
            var picture = BufferedImages.toRgba16Picture(image);
            assertEquals(alpha, picture.hasAlpha());
            var raster = image.getRaster();
            for (var y = 0; y < 7; y++) {
                for (var x = 0; x < 13; x++) {
                    var at = y * picture.stride() + x * 4;
                    for (var channel = 0; channel < 3; channel++) {
                        var band = gray ? 0 : channel;
                        assertEquals(raster.getSample(x, y, band), picture.pixels()[at + channel] & 0xFFFF);
                    }
                    var expectedAlpha = alpha ? raster.getSample(x, y, raster.getNumBands() - 1) : 0xFFFF;
                    assertEquals(expectedAlpha, picture.pixels()[at + 3] & 0xFFFF);
                }
            }
        }

        @Test
        @DisplayName("TYPE_USHORT_GRAY keeps every 16-bit sample")
        void ushortGray() {
            var image = new BufferedImage(5, 3, BufferedImage.TYPE_USHORT_GRAY);
            for (var i = 0; i < 15; i++) {
                image.getRaster().setSample(i % 5, i / 5, 0, i * 4111 + 3);
            }
            var picture = BufferedImages.toRgba16Picture(image);
            assertFalse(picture.hasAlpha());
            var at = picture.stride() + 2 * 4;
            assertEquals(7 * 4111 + 3, picture.pixels()[at] & 0xFFFF);
            assertEquals(7 * 4111 + 3, picture.pixels()[at + 2] & 0xFFFF);
            assertEquals(0xFFFF, picture.pixels()[at + 3] & 0xFFFF);
        }

        @ParameterizedTest(name = "type {0}")
        @ValueSource(ints = {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_BYTE_GRAY,
                BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_INT_ARGB_PRE})
        @DisplayName("8-bit images widen each sample of toPicture by 257")
        void eightBit(int type) {
            var source = BufferedImages.toBufferedImage(gradient(9, 6, true), BufferedImage.TYPE_INT_ARGB);
            var image = new BufferedImage(9, 6, type);
            image.getGraphics().drawImage(source, 0, 0, null);
            var narrow = BufferedImages.toPicture(image);
            var wide = BufferedImages.toRgba16Picture(image);
            assertEquals(narrow.hasAlpha(), wide.hasAlpha());
            for (var y = 0; y < 6; y++) {
                for (var i = 0; i < 9 * 4; i++) {
                    var expected = (narrow.pixels()[y * narrow.stride() + i] & 0xFF) * 257;
                    assertEquals(expected, wide.pixels()[y * wide.stride() + i] & 0xFFFF);
                }
            }
        }
    }

    @Test
    @DisplayName("a reduced-size picture is sampled at the subsampled positions scaled to its size")
    void reducedSize() {
        // a 20x16 image decoded at half size (10x8) and read with 2x2 subsampling maps 1:1
        var reduced = gradient(10, 8, false);
        var destination = new BufferedImage(10, 8, BufferedImage.TYPE_INT_RGB);
        BufferedImages.copy(reduced, 20, 16, new Rectangle(0, 0, 20, 16), 2, 2, destination, 0, 0, null);
        for (var y = 0; y < 8; y++) {
            for (var x = 0; x < 10; x++) {
                assertEquals(argb(reduced, x, y), destination.getRGB(x, y));
            }
        }
        // with 4x4 subsampling every other reduced pixel is taken
        var quarter = new BufferedImage(5, 4, BufferedImage.TYPE_INT_RGB);
        BufferedImages.copy(reduced, 20, 16, new Rectangle(0, 0, 20, 16), 4, 4, quarter, 0, 0, null);
        assertEquals(argb(reduced, 6, 4), quarter.getRGB(3, 2));
    }

    @Test
    @DisplayName("picture -> image -> picture is lossless for RGBA")
    void roundTrip() {
        var picture = gradient(64, 33, true);
        var back = BufferedImages.toPicture(BufferedImages.toBufferedImage(picture));
        assertTrue(back.hasAlpha());
        var expected = new byte[64 * 33 * 4];
        var actual = new byte[64 * 33 * 4];
        System.arraycopy(picture.pixels(), 0, expected, 0, expected.length);
        System.arraycopy(back.pixels(), 0, actual, 0, actual.length);
        assertArrayEquals(expected, actual);
    }
}
