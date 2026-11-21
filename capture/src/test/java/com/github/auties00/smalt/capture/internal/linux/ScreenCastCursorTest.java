package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.internal.convert.RgbLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads pointer metadata laid out by hand from spa/buffer/meta.h and checks the pixels the pointer
 * leaves, computed by hand from the premultiplied "over" operator.
 */
@DisplayName("screen cast pointer metadata")
class ScreenCastCursorTest {
    private static final int WIDTH = 8;
    private static final int HEIGHT = 6;
    private static final int STRIDE = 4 * WIDTH + 8;

    /** Lays out a spa_meta_cursor followed by a spa_meta_bitmap and its rows. */
    private static byte[] meta(int id, int x, int y, int hotX, int hotY, int format, int width, int height, int stride, byte[] rows,
                               int firstRowOffset) {
        var buffer = ByteBuffer.allocate(28 + 20 + rows.length).order(ByteOrder.nativeOrder());
        buffer.putInt(id).putInt(0).putInt(x).putInt(y).putInt(hotX).putInt(hotY).putInt(28);
        buffer.putInt(format).putInt(width).putInt(height).putInt(stride).putInt(20 + firstRowOffset);
        buffer.put(rows);
        return buffer.array();
    }

    private static byte[] moved(int id, int x, int y) {
        var buffer = ByteBuffer.allocate(28).order(ByteOrder.nativeOrder());
        buffer.putInt(id).putInt(0).putInt(x).putInt(y).putInt(99).putInt(99).putInt(0);
        return buffer.array();
    }

    private static boolean update(ScreenCastCursor cursor, byte[] meta) {
        return cursor.update(MemorySegment.ofArray(meta));
    }

    private static byte[] background() {
        var pixels = new byte[STRIDE * HEIGHT];
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                var at = y * STRIDE + 4 * x;
                pixels[at] = 100;
                pixels[at + 1] = 50;
                pixels[at + 2] = (byte) 200;
                pixels[at + 3] = 7;
            }
        }
        return pixels;
    }

    private static int[] bgr(byte[] pixels, int x, int y) {
        var at = y * STRIDE + 4 * x;
        return new int[]{pixels[at] & 0xFF, pixels[at + 1] & 0xFF, pixels[at + 2] & 0xFF, pixels[at + 3] & 0xFF};
    }

    @ParameterizedTest(name = "format {0}")
    @DisplayName("every 4-byte format's samples are read in its byte order, with alpha where it has one")
    @CsvSource({"7,10,20,30,0,255", "8,30,20,10,0,255", "9,0,10,20,30,255", "10,0,30,20,10,255", "11,10,20,30,128,128", "12,30,20,10,128,128",
            "13,128,10,20,30,128", "14,128,30,20,10,128"})
    void formats(int format, int b0, int b1, int b2, int b3, int alpha) {
        // one pixel of red 10, green 20, blue 30 premultiplied, in the format's order
        var cursor = new ScreenCastCursor();
        assertTrue(update(cursor, meta(1, 2, 1, 0, 0, format, 1, 1, 4, new byte[]{(byte) b0, (byte) b1, (byte) b2, (byte) b3}, 0)));
        var pixels = background();
        cursor.draw(pixels, STRIDE, RgbLayout.BGRX, WIDTH, HEIGHT);
        // source + destination * (255 - alpha) / 255, rounded; the background is blue 100, green 50, red 200
        assertEquals(30 + (100 * (255 - alpha) + 127) / 255, bgr(pixels, 2, 1)[0], "blue");
        assertEquals(20 + (50 * (255 - alpha) + 127) / 255, bgr(pixels, 2, 1)[1], "green");
        assertEquals(10 + (200 * (255 - alpha) + 127) / 255, bgr(pixels, 2, 1)[2], "red");
        assertEquals(7, bgr(pixels, 2, 1)[3], "the unused byte of the stream is left alone");
    }

    @Test
    @DisplayName("the image is placed at the position less the hot spot and clipped to the stream, a transparent pixel left alone")
    void placement() {
        // 2x2 RGBA: opaque white, transparent; opaque black, opaque white
        var rows = new byte[]{-1, -1, -1, -1, 0, 0, 0, 0, 0, 0, 0, -1, -1, -1, -1, -1};
        var cursor = new ScreenCastCursor();
        update(cursor, meta(1, 3, 2, 1, 1, 11, 2, 2, 8, rows, 0));
        var pixels = background();
        var clean = pixels.clone();
        cursor.draw(pixels, STRIDE, RgbLayout.BGRX, WIDTH, HEIGHT);
        assertArrayEquals(new int[]{255, 255, 255, 7}, bgr(pixels, 2, 1));
        assertArrayEquals(new int[]{100, 50, 200, 7}, bgr(pixels, 3, 1));
        assertArrayEquals(new int[]{0, 0, 0, 7}, bgr(pixels, 2, 2));
        assertArrayEquals(new int[]{255, 255, 255, 7}, bgr(pixels, 3, 2));
        cursor.erase(pixels);
        assertArrayEquals(clean, pixels, "erasing restores the frame");

        update(cursor, moved(1, 8, 6));
        cursor.draw(pixels, STRIDE, RgbLayout.BGRX, WIDTH, HEIGHT);
        assertArrayEquals(new int[]{255, 255, 255, 7}, bgr(pixels, 7, 5), "only the corner inside the stream, at the bottom right");
        assertArrayEquals(new int[]{100, 50, 200, 7}, bgr(pixels, 6, 5));
        cursor.erase(pixels);
        assertArrayEquals(clean, pixels);

        update(cursor, moved(1, 1, 1));
        cursor.draw(pixels, STRIDE, RgbLayout.BGRX, WIDTH, HEIGHT);
        assertArrayEquals(new int[]{255, 255, 255, 7}, bgr(pixels, 0, 0), "the corner at the top left");
        cursor.erase(pixels);
        assertArrayEquals(clean, pixels);

        for (var outside : new int[][]{{100, 3}, {-100, 3}, {3, 100}, {3, -100}, {Integer.MAX_VALUE, Integer.MAX_VALUE},
                {Integer.MIN_VALUE, Integer.MIN_VALUE}}) {
            update(cursor, moved(1, outside[0], outside[1]));
            cursor.draw(pixels, STRIDE, RgbLayout.BGRX, WIDTH, HEIGHT);
            assertArrayEquals(clean, pixels, "a pointer outside the stream");
            cursor.erase(pixels);
        }
    }

    @Test
    @DisplayName("a negative stride reads the rows bottom-up from the given offset")
    void bottomUp() {
        // memory holds the visual bottom row first
        var rows = new byte[]{0, 0, 0, -1, -1, -1, -1, -1};
        var cursor = new ScreenCastCursor();
        update(cursor, meta(1, 0, 0, 0, 0, 11, 1, 2, -4, rows, 4));
        var pixels = background();
        cursor.draw(pixels, STRIDE, RgbLayout.BGRX, WIDTH, HEIGHT);
        assertArrayEquals(new int[]{255, 255, 255, 7}, bgr(pixels, 0, 0));
        assertArrayEquals(new int[]{0, 0, 0, 7}, bgr(pixels, 0, 1));
    }

    @Test
    @DisplayName("a stride of 0 means tight rows, and padded rows are skipped")
    void strides() {
        var tight = new ScreenCastCursor();
        update(tight, meta(1, 0, 0, 0, 0, 11, 1, 2, 0, new byte[]{-1, -1, -1, -1, 0, 0, 0, -1}, 0));
        var pixels = background();
        tight.draw(pixels, STRIDE, RgbLayout.BGRX, WIDTH, HEIGHT);
        assertArrayEquals(new int[]{255, 255, 255, 7}, bgr(pixels, 0, 0));
        assertArrayEquals(new int[]{0, 0, 0, 7}, bgr(pixels, 0, 1));

        var padded = new ScreenCastCursor();
        update(padded, meta(1, 0, 0, 0, 0, 11, 1, 2, 8, new byte[]{-1, -1, -1, -1, 5, 5, 5, 5, 0, 0, 0, -1}, 0));
        pixels = background();
        padded.draw(pixels, STRIDE, RgbLayout.BGRX, WIDTH, HEIGHT);
        assertArrayEquals(new int[]{0, 0, 0, 7}, bgr(pixels, 0, 1));
    }

    @Test
    @DisplayName("the image stays until a new bitmap, id 0 hides the pointer, and changes are reported")
    void changes() {
        var cursor = new ScreenCastCursor();
        assertFalse(cursor.drawable());
        assertFalse(update(cursor, moved(0, 1, 1)), "no pointer before, none now");
        assertTrue(update(cursor, moved(1, 1, 1)), "appears, still without an image");
        assertFalse(cursor.drawable());
        var opaque = new byte[]{-1, -1, -1, -1};
        assertTrue(update(cursor, meta(1, 1, 1, 0, 0, 11, 1, 1, 4, opaque, 0)), "the first image");
        assertTrue(cursor.drawable());
        assertFalse(update(cursor, moved(1, 1, 1)), "the same place, no bitmap");
        assertTrue(update(cursor, moved(1, 2, 1)), "moved");
        assertTrue(update(cursor, moved(0, 2, 1)), "hidden");
        assertFalse(cursor.drawable());
        assertFalse(update(cursor, moved(0, 5, 5)), "stays hidden");
        assertTrue(update(cursor, moved(1, 2, 1)), "shown again");
        assertTrue(cursor.drawable(), "with the image it had");

        var noNewBitmap = meta(1, 2, 1, 0, 0, 0, 1, 1, 4, opaque, 0);
        assertFalse(update(cursor, noNewBitmap), "format 0 is no new bitmap");
        assertTrue(cursor.drawable());
        assertFalse(cursor.update(MemorySegment.ofArray(new byte[27])), "metadata too small for the structure");
    }

    @Test
    @DisplayName("sizes that leave the metadata or formats not understood leave no image, and nothing is read from elsewhere")
    void hostile() {
        var opaque = new byte[]{-1, -1, -1, -1};
        byte[][] bad = {
                meta(1, 0, 0, 0, 0, 4, 1, 1, 4, opaque, 0),
                meta(1, 0, 0, 0, 0, 11, 2, 1, 8, opaque, 0),
                meta(1, 0, 0, 0, 0, 11, 1, 2, 4, opaque, 0),
                meta(1, 0, 0, 0, 0, 11, 1, 1, 4, opaque, 4),
                meta(1, 0, 0, 0, 0, 11, 1, 1, 2, opaque, 0),
                meta(1, 0, 0, 0, 0, 11, 1, 2, -4, opaque, 0),
                meta(1, 0, 0, 0, 0, 11, 0, 1, 4, opaque, 0),
                meta(1, 0, 0, 0, 0, 11, -1, 1, 4, opaque, 0),
                meta(1, 0, 0, 0, 0, 11, ScreenCastCursor.MAX_SIDE + 1, 1, 4 * (ScreenCastCursor.MAX_SIDE + 1), opaque, 0),
                meta(1, 0, 0, 0, 0, 11, 1, 2, Integer.MIN_VALUE, opaque, 0),
                meta(1, 0, 0, 0, 0, 11, 1, 1, 4, opaque, -20),
        };
        for (var meta : bad) {
            var cursor = new ScreenCastCursor();
            update(cursor, meta(1, 0, 0, 0, 0, 11, 1, 1, 4, opaque, 0));
            assertTrue(cursor.drawable());
            assertTrue(update(cursor, meta), Arrays.toString(meta));
            assertFalse(cursor.drawable(), Arrays.toString(meta));
        }
        var inside = ByteBuffer.wrap(meta(1, 0, 0, 0, 0, 11, 1, 1, 4, opaque, 0)).order(ByteOrder.nativeOrder());
        for (var offset : new int[]{1, 27, 49, -1, Integer.MIN_VALUE}) {
            inside.putInt(24, offset);
            var cursor = new ScreenCastCursor();
            update(cursor, inside.array());
            assertFalse(cursor.drawable(), "bitmap offset " + offset);
        }
        var random = new SplittableRandom(11);
        var valid = meta(1, 3, 2, 1, 1, 11, 2, 2, 8, new byte[16], 0);
        for (var trial = 0; trial < 20_000; trial++) {
            var copy = valid.clone();
            copy[random.nextInt(copy.length)] = (byte) random.nextInt(256);
            var cursor = new ScreenCastCursor();
            update(cursor, copy);
            var pixels = background();
            var clean = pixels.clone();
            cursor.draw(pixels, STRIDE, RgbLayout.BGRX, WIDTH, HEIGHT);
            cursor.erase(pixels);
            assertArrayEquals(clean, pixels);
        }
    }
}
