package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.internal.convert.RgbLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Expands random X11 images of the 16-bit, packed 24-bit and 30-bit TrueColor formats and compares
 * every sample with one computed from the pixel value and the masks.
 */
@DisplayName("X11 pixel formats")
class X11PixelFormatTest {
    static Stream<Arguments> formats() {
        return Stream.of(
                Arguments.of("RGB565", 16, 0xF800L, 0x07E0L, 0x001FL),
                Arguments.of("RGB555", 16, 0x7C00L, 0x03E0L, 0x001FL),
                Arguments.of("BGR565", 16, 0x001FL, 0x07E0L, 0xF800L),
                Arguments.of("packed RGB888", 24, 0xFF0000L, 0x00FF00L, 0x0000FFL),
                Arguments.of("packed BGR888", 24, 0x0000FFL, 0x00FF00L, 0xFF0000L),
                Arguments.of("packed 24-bit of narrow channels", 24, 0xFC0000L, 0x03F000L, 0x000FC0L),
                Arguments.of("depth 30", 32, 0x3FF00000L, 0x000FFC00L, 0x000003FFL),
                Arguments.of("depth 30 BGR", 32, 0x000003FFL, 0x000FFC00L, 0x3FF00000L));
    }

    /** Scales a channel value to 8 bits as the X11 visual's mask defines it. */
    private static int expected(long value, long mask) {
        var bits = Long.bitCount(mask);
        var sample = (value & mask) >>> Long.numberOfTrailingZeros(mask);
        var max = (1L << bits) - 1;
        return bits > 8 ? (int) (sample >>> (bits - 8)) : (int) Math.round(sample * 255.0 / max);
    }

    @ParameterizedTest(name = "{0}, {1} bpp")
    @MethodSource("formats")
    @DisplayName("every pixel of both byte orders expands to its scaled channels in B, G, R, X order")
    void expand(String name, int bitsPerPixel, long red, long green, long blue) {
        for (var byteOrder : new int[]{X11ScreenGrabber.LSB_FIRST, X11ScreenGrabber.MSB_FIRST}) {
            var format = X11PixelFormat.of(bitsPerPixel, byteOrder, red, green, blue);
            assertNotNull(format, name);
            assertNull(format.direct(), "pixels that are not whole bytes are expanded");
            var bytes = bitsPerPixel / 8;
            assertEquals(bytes, format.bytesPerPixel());
            var width = 37;
            var height = 5;
            var stride = bytes * width + 3;
            var source = new byte[stride * height];
            new SplittableRandom(bitsPerPixel * 7L + byteOrder + red).nextBytes(source);
            var target = new byte[4 * width * height];
            format.expand(source, stride, width, height, target);
            for (var row = 0; row < height; row++) {
                for (var x = 0; x < width; x++) {
                    var value = 0L;
                    for (var index = 0; index < bytes; index++) {
                        var sample = source[row * stride + bytes * x + index] & 0xFFL;
                        var position = byteOrder == X11ScreenGrabber.LSB_FIRST ? index : bytes - 1 - index;
                        value |= sample << (8 * position);
                    }
                    var at = 4 * (row * width + x);
                    var where = name + " order " + byteOrder + " pixel " + x + "," + row;
                    assertEquals(expected(value, blue), target[at] & 0xFF, where + " B");
                    assertEquals(expected(value, green), target[at + 1] & 0xFF, where + " G");
                    assertEquals(expected(value, red), target[at + 2] & 0xFF, where + " R");
                    assertEquals(255, target[at + 3] & 0xFF, where + " X");
                }
            }
        }
    }

    @Test
    @DisplayName("the extremes of a 5-bit and a 6-bit channel reach 0 and 255")
    void extremes() {
        var format = X11PixelFormat.of(16, X11ScreenGrabber.LSB_FIRST, 0xF800, 0x07E0, 0x001F);
        var target = new byte[8];
        format.expand(new byte[]{(byte) 0xFF, (byte) 0xFF, 0, 0}, 4, 2, 1, target);
        assertEquals(255, target[0] & 0xFF);
        assertEquals(255, target[1] & 0xFF);
        assertEquals(255, target[2] & 0xFF);
        assertEquals(0, target[4]);
        assertEquals(0, target[5]);
        assertEquals(0, target[6]);
    }

    @Test
    @DisplayName("32-bit pixels of whole-byte channels are converted in place")
    void direct() {
        var format = X11PixelFormat.of(32, X11ScreenGrabber.LSB_FIRST, 0xFF0000, 0xFF00, 0xFF);
        assertEquals(RgbLayout.BGRX, format.direct());
        assertEquals(4, format.bytesPerPixel());
    }

    @Test
    @DisplayName("palettes, unknown byte orders and broken masks are unsupported")
    void unsupported() {
        assertNull(X11PixelFormat.of(8, X11ScreenGrabber.LSB_FIRST, 0xE0, 0x1C, 0x03), "8 bits per pixel");
        assertNull(X11PixelFormat.of(16, X11ScreenGrabber.LSB_FIRST, 0, 0, 0), "a PseudoColor visual has no masks");
        assertNull(X11PixelFormat.of(16, 2, 0xF800, 0x07E0, 0x001F), "unknown byte order");
        assertNull(X11PixelFormat.of(16, X11ScreenGrabber.LSB_FIRST, 0x1F800, 0x07E0, 0x001F), "a mask beyond the pixel");
        assertNull(X11PixelFormat.of(16, X11ScreenGrabber.LSB_FIRST, 0xF801, 0x07E0, 0x001E), "a mask with a gap");
        assertNull(X11PixelFormat.of(16, X11ScreenGrabber.LSB_FIRST, 0xFC00, 0x07E0, 0x001F), "overlapping masks");
        assertNull(X11PixelFormat.of(32, X11ScreenGrabber.LSB_FIRST, 0xFFFFF000L, 0x0F00, 0x00FF), "a channel of 20 bits");
    }
}
