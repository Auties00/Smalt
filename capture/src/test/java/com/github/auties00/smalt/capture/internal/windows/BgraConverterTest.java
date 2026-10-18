package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.SplittableRandom;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the conversion of captured BGRX images in native memory: strides, bottom-up images,
 * odd sizes, rotated outputs, rescaling after a resolution change, and the pointer overlay.
 */
@DisplayName("BGRX frame conversion")
class BgraConverterTest {
    /** Returns a random BGRX image of tightly packed rows. */
    private static byte[] image(SplittableRandom random, int width, int height) {
        var pixels = new byte[4 * width * height];
        random.nextBytes(pixels);
        return pixels;
    }

    /** Converts the top-left even part of a tightly packed BGRX image with ImageOps. */
    private static YuvPicture reference(byte[] bgra, int width, int height) {
        var evenWidth = width & ~1;
        var evenHeight = height & ~1;
        var rgba = new byte[4 * evenWidth * evenHeight];
        for (var y = 0; y < evenHeight; y++) {
            for (var x = 0; x < evenWidth; x++) {
                var source = 4 * (y * width + x);
                var target = 4 * (y * evenWidth + x);
                rgba[target] = bgra[source + 2];
                rgba[target + 1] = bgra[source + 1];
                rgba[target + 2] = bgra[source];
                rgba[target + 3] = (byte) 255;
            }
        }
        return ImageOps.toYuv(RgbaPicture.of(evenWidth, evenHeight, 4 * evenWidth, rgba, false), ChromaFormat.YUV420, YuvCoefficients.BT709_LIMITED.color());
    }

    private static YuvPicture picture(int width, int height) {
        return YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, YuvCoefficients.BT709_LIMITED.color());
    }

    private static void assertSame(YuvPicture expected, YuvPicture actual) {
        assertArrayEquals(expected.y(), actual.y(), "luma");
        assertArrayEquals(expected.u(), actual.u(), "cb");
        assertArrayEquals(expected.v(), actual.v(), "cr");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"64x48", "65x49", "3x3", "2x2", "1921x1081"})
    @DisplayName("converts a padded top-down image, cropping odd sizes, like ImageOps")
    void padded(String size) {
        var width = Integer.parseInt(size.substring(0, size.indexOf('x')));
        var height = Integer.parseInt(size.substring(size.indexOf('x') + 1));
        var random = new SplittableRandom(width * 31L + height);
        var tight = image(random, width, height);
        var pitch = 4 * width + 12;
        try (var arena = Arena.ofConfined()) {
            var memory = arena.allocate((long) pitch * height);
            for (var y = 0; y < height; y++) {
                MemorySegment.copy(tight, 4 * y * width, memory, JAVA_BYTE, (long) y * pitch, 4 * width);
            }
            var out = picture(width & ~1, height & ~1);
            assertTrue(new BgraConverter().convert(memory, 0, pitch, width, height, 1, null, out));
            assertSame(reference(tight, width, height), out);
        }
    }

    @Test
    @DisplayName("converts a bottom-up image through a negative pitch")
    void bottomUp() {
        var random = new SplittableRandom(9);
        var width = 34;
        var height = 20;
        var tight = image(random, width, height);
        try (var arena = Arena.ofConfined()) {
            var memory = arena.allocate(4L * width * height);
            for (var y = 0; y < height; y++) {
                MemorySegment.copy(tight, 4 * y * width, memory, JAVA_BYTE, 4L * (height - 1 - y) * width, 4 * width);
            }
            var out = picture(width, height);
            new BgraConverter().convert(memory, 4L * (height - 1) * width, -4L * width, width, height, 0, null, out);
            assertSame(reference(tight, width, height), out);
        }
    }

    @Test
    @DisplayName("rejects an image that does not fit its memory instead of reading past it")
    void bounds() {
        try (var arena = Arena.ofConfined()) {
            var memory = arena.allocate(4L * 16 * 15);
            var out = picture(16, 16);
            assertThrows(IndexOutOfBoundsException.class, () -> new BgraConverter().convert(memory, 0, 64, 16, 16, 0, null, out));
        }
    }

    @Test
    @DisplayName("converts nothing from an image without an even part")
    void tooSmall() {
        var memory = MemorySegment.ofArray(new byte[4]);
        assertFalse(new BgraConverter().convert(memory, 0, 4, 1, 1, 0, null, picture(2, 2)));
    }

    @Test
    @DisplayName("rescales an image whose size differs from the picture's")
    void rescales() {
        var random = new SplittableRandom(4);
        var tight = image(random, 64, 48);
        var out = picture(32, 24);
        new BgraConverter().convert(MemorySegment.ofArray(tight), 0, 256, 64, 48, 0, null, out);
        var expected = (YuvPicture) ImageOps.resize(reference(tight, 64, 48), 32, 24,
                ResizeFilter.BILINEAR);
        assertSame(expected, out);
    }

    @Nested
    @DisplayName("rotated outputs")
    class Rotation {
        private int[] rotate(int[] stored, int width, int height, int rotation) {
            var bytes = new byte[4 * stored.length];
            MemorySegment.ofArray(bytes).copyFrom(MemorySegment.ofArray(stored));
            var result = new BgraConverter().rotate(MemorySegment.ofArray(bytes), 0, 4L * width, width, height, rotation);
            return Arrays.copyOf(result, width * height);
        }

        @Test
        @DisplayName("turns a 3x2 stored image upright as the Desktop Duplication sample does")
        void mapping() {
            // stored:  a b c
            //          d e f
            int[] stored = {1, 2, 3, 4, 5, 6};
            // 90: upright 2x3, stored (sx, sy) at (W - 1 - sy, sx)
            assertArrayEquals(new int[]{4, 1, 5, 2, 6, 3}, rotate(stored, 3, 2, Dxgi.ROTATE_90));
            assertArrayEquals(new int[]{6, 5, 4, 3, 2, 1}, rotate(stored, 3, 2, Dxgi.ROTATE_180));
            // 270: stored (sx, sy) at (sy, H - 1 - sx)
            assertArrayEquals(new int[]{3, 6, 2, 5, 1, 4}, rotate(stored, 3, 2, Dxgi.ROTATE_270));
        }

        @Test
        @DisplayName("undoes a 90-degree turn with a 270-degree turn")
        void inverse() {
            var random = new SplittableRandom(3);
            var stored = random.ints(7 * 5).toArray();
            var turned = rotate(stored, 7, 5, Dxgi.ROTATE_90);
            assertArrayEquals(stored, rotate(turned, 5, 7, Dxgi.ROTATE_270));
            assertArrayEquals(stored, rotate(rotate(stored, 7, 5, Dxgi.ROTATE_180), 7, 5, Dxgi.ROTATE_180));
        }

        @Test
        @DisplayName("converts a rotated output at its upright size")
        void converts() {
            var random = new SplittableRandom(8);
            var width = 20;
            var height = 12;
            var stored = image(random, width, height);
            var out = picture(height, width);
            new BgraConverter().convert(MemorySegment.ofArray(stored), 0, 4L * width, width, height, Dxgi.ROTATE_90, null, out);
            var ints = new int[width * height];
            MemorySegment.ofArray(ints).copyFrom(MemorySegment.ofArray(stored));
            var upright = rotate(ints, width, height, Dxgi.ROTATE_90);
            var uprightBytes = new byte[stored.length];
            MemorySegment.ofArray(uprightBytes).copyFrom(MemorySegment.ofArray(upright));
            assertSame(reference(uprightBytes, height, width), out);
        }
    }

    @Nested
    @DisplayName("pointer overlay")
    class Pointer {
        private static byte[] row(int width, int b, int g, int r) {
            var row = new byte[4 * width];
            for (var x = 0; x < width; x++) {
                row[4 * x] = (byte) b;
                row[4 * x + 1] = (byte) g;
                row[4 * x + 2] = (byte) r;
            }
            return row;
        }

        private static int pixel(byte[] row, int x) {
            return (row[4 * x] & 0xFF) | (row[4 * x + 1] & 0xFF) << 8 | (row[4 * x + 2] & 0xFF) << 16;
        }

        @Test
        @DisplayName("blends a color shape with straight alpha and clips it at the row's edges")
        void color() {
            var shape = new byte[]{
                    (byte) 255, 0, 0, (byte) 255,
                    0, (byte) 255, 0, 0,
                    0, 0, (byte) 255, (byte) 128
            };
            var overlay = new PointerOverlay();
            overlay.shape(MemorySegment.ofArray(shape), Dxgi.POINTER_COLOR, 3, 1, 12);
            overlay.position(-1, 5, true);
            var row = row(4, 100, 100, 100);
            overlay.apply(row, 0, 5, 4);
            assertEquals(0x646464, pixel(row, 0));
            var red = (255 * 128 + 100 * 127 + 127) / 255;
            var other = (100 * 127 + 127) / 255;
            assertEquals(red << 16 | other << 8 | other, pixel(row, 1));
            assertEquals(0x646464, pixel(row, 2));
            var below = row(4, 100, 100, 100);
            overlay.apply(below, 0, 6, 4);
            assertEquals(0x646464, pixel(below, 1));
        }

        @Test
        @DisplayName("replaces or XORs with a masked color shape")
        void masked() {
            var shape = new byte[]{
                    0x10, 0x20, 0x30, 0,
                    (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
            };
            var overlay = new PointerOverlay();
            overlay.shape(MemorySegment.ofArray(shape), Dxgi.POINTER_MASKED_COLOR, 2, 1, 8);
            overlay.position(0, 0, true);
            var row = row(2, 0x0F, 0x0F, 0x0F);
            overlay.apply(row, 0, 0, 2);
            assertEquals(0x302010, pixel(row, 0));
            assertEquals(0xF0F0F0, pixel(row, 1));
        }

        @Test
        @DisplayName("applies a monochrome shape's AND and XOR masks")
        void monochrome() {
            // AND row: 1 1 0 0 ; XOR row: 0 1 0 1 -> keep, invert, black, white
            var shape = new byte[]{(byte) 0b1100_0000, (byte) 0b0101_0000};
            var overlay = new PointerOverlay();
            overlay.shape(MemorySegment.ofArray(shape), Dxgi.POINTER_MONOCHROME, 4, 2, 1);
            overlay.position(0, 3, true);
            var row = row(4, 0x12, 0x34, 0x56);
            overlay.apply(row, 0, 3, 4);
            assertEquals(0x563412, pixel(row, 0));
            assertEquals(0xA9CBED, pixel(row, 1));
            assertEquals(0x000000, pixel(row, 2));
            assertEquals(0xFFFFFF, pixel(row, 3));
        }

        @Test
        @DisplayName("draws nothing when hidden or when the shape does not fit its buffer")
        void hiddenOrInvalid() {
            var overlay = new PointerOverlay();
            overlay.shape(MemorySegment.ofArray(new byte[8]), Dxgi.POINTER_COLOR, 2, 1, 8);
            overlay.position(0, 0, false);
            var row = row(2, 1, 2, 3);
            overlay.apply(row, 0, 0, 2);
            assertEquals(0x030201, pixel(row, 0));
            overlay.position(0, 0, true);
            overlay.shape(MemorySegment.ofArray(new byte[8]), Dxgi.POINTER_COLOR, 2, 2, 8);
            overlay.apply(row, 0, 0, 2);
            assertEquals(0x030201, pixel(row, 0));
            overlay.shape(MemorySegment.ofArray(new byte[8]), 3, 2, 1, 8);
            overlay.apply(row, 0, 0, 2);
            assertEquals(0x030201, pixel(row, 0));
        }
    }
}
