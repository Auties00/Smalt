package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.internal.convert.RgbLayout;
import com.github.auties00.smalt.testkit.Scratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the platform-independent parts of the X11 window capture and the device watch: the pointer
 * composition, the {@code WM_CLASS} parsing, and the {@code /dev} watch over a scratch directory.
 */
@DisplayName("X11 and device watch helpers")
class X11HelpersTest {
    @Nested
    @DisplayName("pointer composition")
    class Cursor {
        @Test
        @DisplayName("premultiplied pixels blend over the pixels with the over operator in the layout's sample order")
        void blend() {
            // a 2x1 BGRX image: blue-ish and white
            var pixels = new byte[]{(byte) 200, 100, 50, 0, (byte) 255, (byte) 255, (byte) 255, 0};
            // opaque red over the first pixel, half-transparent black (premultiplied) over the second
            var image = new int[]{0xFFFF0000, 0x80000000};
            X11Cursor.compose(image, 2, 1, 0, 0, pixels, 8, RgbLayout.BGRX, 2, 1);
            assertArrayEquals(new byte[]{0, 0, (byte) 255, 0, 127, 127, 127, 0}, pixels);
        }

        @Test
        @DisplayName("an image partly outside the pixels is clipped and fully transparent pixels are skipped")
        void clip() {
            var pixels = new byte[4 * 2 * 2];
            java.util.Arrays.fill(pixels, (byte) 10);
            var image = new int[]{0xFF00FF00, 0x00000000, 0xFF0000FF, 0xFFFFFFFF};
            // the image's top-left pixel lands outside, at (-1, -1); only its bottom-right one is drawn, at (0, 0)
            X11Cursor.compose(image, 2, 2, -1, -1, pixels, 8, RgbLayout.BGRX, 2, 2);
            assertArrayEquals(new byte[]{(byte) 255, (byte) 255, (byte) 255, 10, 10, 10, 10, 10,
                    10, 10, 10, 10, 10, 10, 10, 10}, pixels);
            var untouched = new byte[16];
            X11Cursor.compose(image, 2, 2, 5, 5, untouched, 8, RgbLayout.BGRX, 2, 2);
            assertArrayEquals(new byte[16], untouched);
        }

        @Test
        @DisplayName("an RGBX layout receives the red sample first")
        void layout() {
            var pixels = new byte[4];
            X11Cursor.compose(new int[]{0xFF102030}, 1, 1, 0, 0, pixels, 4, new RgbLayout(0, 1, 2), 1, 1);
            assertArrayEquals(new byte[]{0x10, 0x20, 0x30, 0}, pixels);
        }
    }

    @Nested
    @DisplayName("WM_CLASS")
    class WindowClass {
        @Test
        @DisplayName("the class is the second string, or the first when the second is missing or empty")
        void parse() {
            assertEquals("Firefox", X11Windows.className(bytes("Navigator\0Firefox\0")));
            assertEquals("xterm", X11Windows.className(bytes("xterm\0")));
            assertEquals("solo", X11Windows.className(bytes("solo")));
            assertEquals("first", X11Windows.className(bytes("first\0\0")));
            assertEquals("", X11Windows.className(new byte[0]));
        }

        private static byte[] bytes(String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Nested
    @DisplayName("device node watch")
    class Nodes {
        @Test
        @DisplayName("creating or deleting a video node calls back, other files do not")
        void watch() throws IOException, InterruptedException {
            var directory = Files.createDirectories(Scratch.directory("dev-watch").resolve("dev"));
            var calls = new Semaphore(0);
            var watch = DevWatch.start(directory, calls::release);
            assertNotNull(watch);
            try {
                Files.writeString(directory.resolve("random"), "x");
                assertFalse(calls.tryAcquire(500, TimeUnit.MILLISECONDS));
                var node = Files.writeString(directory.resolve("video7"), "x");
                assertTrue(calls.tryAcquire(15, TimeUnit.SECONDS));
                calls.drainPermits();
                Files.delete(node);
                assertTrue(calls.tryAcquire(15, TimeUnit.SECONDS));
            } finally {
                watch.close();
            }
            calls.drainPermits();
            Files.writeString(directory.resolve("video8"), "x");
            assertFalse(calls.tryAcquire(500, TimeUnit.MILLISECONDS));
        }

        @Test
        @DisplayName("a directory that does not exist cannot be watched")
        void missing() {
            assertEquals(null, DevWatch.start(Scratch.directory("dev-watch").resolve("absent"), () -> {
            }));
        }
    }
}
