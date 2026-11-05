package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Covers the HEIF picture rules at high bit depth: the image overlay composition at 8 and 16 bits,
 * checked against the source-over formula computed independently, and the unpremultiplication of
 * 16-bit pictures.
 */
@DisplayName("HEIF high-depth pictures")
class HeifHighDepthTest {
    /** Builds an {@code iovl} payload with 16-bit offsets. */
    private static byte[] overlay(int[] fill, int width, int height, int[]... offsets) {
        var out = new ByteArrayOutputStream();
        out.write(0);
        out.write(0);
        for (var value : fill) {
            out.write(value >> 8);
            out.write(value);
        }
        out.write(width >> 8);
        out.write(width);
        out.write(height >> 8);
        out.write(height);
        for (var offset : offsets) {
            for (var value : offset) {
                out.write(value >> 8);
                out.write(value);
            }
        }
        return out.toByteArray();
    }

    private static Rgba16Picture random16(SplittableRandom random, int width, int height, boolean alpha) {
        var picture = Rgba16Picture.allocate(width, height, alpha);
        for (var i = 0; i < picture.pixels().length; i++) {
            picture.pixels()[i] = (short) (i % 4 == 3 && !alpha ? 0xFFFF : random.nextInt(65536));
        }
        return picture;
    }

    /** Composites one straight-alpha pixel over another with exact rounding at a maximum value. */
    private static long[] over(long[] below, long[] above, long max) {
        var a = above[3];
        if (a == max) {
            return above.clone();
        }
        if (a == 0) {
            return below.clone();
        }
        var b = below[3];
        var coverage = a * max + b * (max - a);
        var out = new long[4];
        for (var c = 0; c < 3; c++) {
            out[c] = (above[c] * a * max + below[c] * b * (max - a) + coverage / 2) / coverage;
        }
        out[3] = (coverage + max / 2) / max;
        return out;
    }

    @Nested
    @DisplayName("overlay composition")
    class Composition {
        @Test
        @DisplayName("a deep canvas keeps the 16-bit fill exactly and blends 16-bit inputs at 16 bits")
        void deep() {
            var random = new SplittableRandom(1);
            var fill = new int[]{0x1234, 0x8001, 0xFFFE, 0x7FFF};
            var parsed = HeifOverlay.parse(overlay(fill, 9, 7, new int[]{2, 1}, new int[]{0xFFFE, 0xFFFF}), 2);
            var composition = parsed.compose(9, 7, true);
            var opaque = random16(random, 4, 3, false);
            var translucent = random16(random, 5, 5, true);
            composition.draw(opaque, parsed.offsetsX()[0], parsed.offsetsY()[0]);
            composition.draw(translucent, parsed.offsetsX()[1], parsed.offsetsY()[1]);
            var result = assertInstanceOf(Rgba16Picture.class, composition.result());
            for (var y = 0; y < 7; y++) {
                for (var x = 0; x < 9; x++) {
                    var expected = new long[]{fill[0], fill[1], fill[2], fill[3]};
                    if (x >= 2 && x < 6 && y >= 1 && y < 4) {
                        expected = pixel(opaque, x - 2, y - 1);
                    }
                    if (x + 2 < 5 && y + 1 < 5) {
                        expected = over(expected, pixel(translucent, x + 2, y + 1), 0xFFFF);
                    }
                    assertArrayEquals(expected, pixel(result, x, y), x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("an 8-bit canvas reduces the fill and blends at 8 bits")
        void eightBit() {
            var random = new SplittableRandom(2);
            var fill = new int[]{0x1234, 0x8001, 0xFFFE, 0x7FFF};
            var parsed = HeifOverlay.parse(overlay(fill, 6, 4, new int[]{1, 1}), 1);
            var composition = parsed.compose(6, 4, false);
            var input = ImageOps.toEightBit(random16(random, 3, 2, true));
            composition.draw(input, 1, 1);
            var result = assertInstanceOf(RgbaPicture.class, composition.result());
            for (var y = 0; y < 4; y++) {
                for (var x = 0; x < 6; x++) {
                    var expected = new long[4];
                    for (var c = 0; c < 4; c++) {
                        expected[c] = (fill[c] * 255L + 32767) / 65535;
                    }
                    if (x >= 1 && x < 4 && y >= 1 && y < 3) {
                        var p = (y - 1) * input.stride() + 4 * (x - 1);
                        var above = new long[4];
                        for (var c = 0; c < 4; c++) {
                            above[c] = input.pixels()[p + c] & 0xFF;
                        }
                        expected = over(expected, above, 255);
                    }
                    var q = y * result.stride() + 4 * x;
                    for (var c = 0; c < 4; c++) {
                        assertEquals(expected[c], result.pixels()[q + c] & 0xFF, x + "," + y + " channel " + c);
                    }
                }
            }
        }

        @Test
        @DisplayName("a canvas that keeps the depth composes 8-bit inputs exactly as an 8-bit canvas does")
        void deepCanvasEightBitInputs() {
            var random = new SplittableRandom(3);
            var parsed = HeifOverlay.parse(overlay(new int[]{0x1234, 0, 0, 0xFFFF}, 5, 5, new int[]{1, 1}), 1);
            var input = ImageOps.toEightBit(random16(random, 3, 3, true));
            var deep = parsed.compose(5, 5, true);
            deep.draw(input, 1, 1);
            var plain = parsed.compose(5, 5, false);
            plain.draw(input, 1, 1);
            assertArrayEquals(((RgbaPicture) plain.result()).pixels(),
                    assertInstanceOf(RgbaPicture.class, deep.result()).pixels());
        }

        @Test
        @DisplayName("a deep input turns the canvas to 16 bits: exact fill where untouched, widened pixels elsewhere")
        void promotion() {
            var random = new SplittableRandom(6);
            var fill = new int[]{0x1234, 0x5678, 0x9ABC, 0xFFFF};
            // a 40x4 canvas spans three words of the changed-pixel set; the 8-bit input sits in the second and third
            var parsed = HeifOverlay.parse(overlay(fill, 40, 4, new int[]{30, 1}, new int[]{4, 2}), 2);
            var eight = ImageOps.toEightBit(random16(random, 3, 3, false));
            var deepInput = random16(random, 2, 2, false);
            var composition = parsed.compose(40, 4, true);
            composition.draw(eight, 30, 1);
            assertInstanceOf(RgbaPicture.class, composition.result());
            composition.draw(deepInput, 4, 2);
            var result = assertInstanceOf(Rgba16Picture.class, composition.result());
            for (var y = 0; y < 4; y++) {
                for (var x = 0; x < 40; x++) {
                    long[] expected;
                    if (x >= 30 && x < 33 && y >= 1) {
                        expected = new long[4];
                        for (var c = 0; c < 3; c++) {
                            expected[c] = (eight.pixels()[(y - 1) * eight.stride() + 4 * (x - 30) + c] & 0xFF) * 257L;
                        }
                        expected[3] = 0xFFFF;
                    } else if (x >= 4 && x < 6 && y >= 2) {
                        expected = pixel(deepInput, x - 4, y - 2);
                    } else {
                        expected = new long[]{fill[0], fill[1], fill[2], fill[3]};
                    }
                    assertArrayEquals(expected, pixel(result, x, y), x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("planar 10-bit inputs are converted at full precision and clipped to the canvas")
        void planarInput() {
            var yuv = Yuv16Picture.allocate(4, 4, ChromaFormat.YUV420, 10, false, ColorInfo.BT709_LIMITED);
            for (var i = 0; i < 16; i++) {
                yuv.y()[i] = (short) (64 + 50 * i);
            }
            java.util.Arrays.fill(yuv.u(), (short) 512);
            java.util.Arrays.fill(yuv.v(), (short) 512);
            var parsed = HeifOverlay.parse(overlay(new int[]{0, 0, 0, 0}, 3, 3, new int[]{0xFFFF, 0xFFFF}), 1);
            var composition = parsed.compose(3, 3, true);
            composition.draw(yuv, -1, -1);
            var result = assertInstanceOf(Rgba16Picture.class, composition.result());
            var rgba = ImageOps.toRgba16(yuv);
            for (var y = 0; y < 3; y++) {
                for (var x = 0; x < 3; x++) {
                    assertArrayEquals(pixel(rgba, x + 1, y + 1), pixel(result, x, y));
                }
            }
        }
    }

    @Nested
    @DisplayName("unpremultiplication")
    class Unpremultiply {
        @Test
        @DisplayName("16-bit RGBA is divided by its alpha at 16 bits with exact rounding")
        void rgba16() {
            var random = new SplittableRandom(4);
            var picture = random16(random, 7, 5, true);
            for (var i = 0; i < 7 * 5; i++) {
                var alpha = picture.pixels()[4 * i + 3] & 0xFFFF;
                for (var c = 0; c < 3; c++) {
                    // premultiplied colors never exceed their alpha
                    picture.pixels()[4 * i + c] = (short) ((picture.pixels()[4 * i + c] & 0xFFFF) * (long) alpha / 65535);
                }
            }
            picture.pixels()[3] = 0;
            picture.pixels()[7] = (short) 0xFFFF;
            var premultiplied = picture.pixels().clone();
            var straight = assertInstanceOf(Rgba16Picture.class, HeifPictures.unpremultiply(picture));
            for (var i = 0; i < 7 * 5; i++) {
                long alpha = premultiplied[4 * i + 3] & 0xFFFF;
                for (var c = 0; c < 3; c++) {
                    long value = premultiplied[4 * i + c] & 0xFFFF;
                    var expected = alpha == 0 ? 0 : Math.min(65535, (value * 65535 * 2 + alpha) / (2 * alpha));
                    assertEquals(expected, straight.pixels()[4 * i + c] & 0xFFFF, "pixel " + i + " channel " + c);
                }
                assertEquals(alpha, straight.pixels()[4 * i + 3] & 0xFFFF);
            }
        }

        @Test
        @DisplayName("a 10-bit planar picture becomes straight 16-bit RGBA, an 8-bit one straight 8-bit RGBA")
        void planar() {
            var random = new SplittableRandom(5);
            var yuv = Yuv16Picture.allocate(6, 4, ChromaFormat.YUV444, 10, true, ColorInfo.BT709_LIMITED);
            for (var i = 0; i < 24; i++) {
                var alpha = random.nextInt(1024);
                yuv.alpha()[i] = (short) alpha;
                yuv.y()[i] = (short) (64 + 876 * alpha / 1023 * random.nextInt(1000) / 1000);
                yuv.u()[i] = 512;
                yuv.v()[i] = 512;
            }
            var expected = ImageOps.toRgba16(yuv);
            var straight = assertInstanceOf(Rgba16Picture.class, HeifPictures.unpremultiply(yuv));
            for (var i = 0; i < 24; i++) {
                long alpha = expected.pixels()[4 * i + 3] & 0xFFFF;
                long value = expected.pixels()[4 * i] & 0xFFFF;
                var divided = alpha == 0 ? 0 : Math.min(65535, (value * 65535 * 2 + alpha) / (2 * alpha));
                assertEquals(divided, straight.pixels()[4 * i] & 0xFFFF, "pixel " + i);
            }
            var eight = YuvPicture.allocate(6, 4, ChromaFormat.YUV444, true, ColorInfo.JPEG);
            assertInstanceOf(RgbaPicture.class, HeifPictures.unpremultiply(eight));
        }
    }

    private static long[] pixel(Rgba16Picture picture, int x, int y) {
        var p = y * picture.stride() + 4 * x;
        var out = new long[4];
        for (var c = 0; c < 4; c++) {
            out[c] = picture.pixels()[p + c] & 0xFFFF;
        }
        if (!picture.hasAlpha()) {
            out[3] = 0xFFFF;
        }
        return out;
    }
}
