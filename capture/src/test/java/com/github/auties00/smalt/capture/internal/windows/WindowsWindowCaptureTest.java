package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.capture.FixtureWindow;
import com.github.auties00.smalt.capture.ScreenCaptureOptions;
import com.github.auties00.smalt.capture.internal.CaptureArea;
import com.github.auties00.smalt.image.ops.ImageOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Covers the Windows window capture: the geometry helpers on every system, and on Windows the two capture
 * paths (Windows Graphics Capture and {@code PrintWindow}) against a {@link FixtureWindow}, and the
 * registration of the device notifications.
 */
@DisplayName("Windows window capture")
class WindowsWindowCaptureTest {
    @Nested
    @DisplayName("helpers")
    class Helpers {
        @Test
        @DisplayName("an executable path becomes its name without the .exe extension")
        void executableName() {
            assertEquals("java", TopWindows.executableName("C:\\Program Files\\Java\\bin\\java.exe"));
            assertEquals("Code", TopWindows.executableName("C:/apps/Code.EXE"));
            assertEquals("tool", TopWindows.executableName("tool"));
            assertEquals("", TopWindows.executableName("C:\\dir\\"));
        }

        @Test
        @DisplayName("an area of the visible part is placed on the image and clipped to the visible part")
        void compose() {
            assertEquals(new CaptureArea(7, 0, 200, 100), WindowImage.compose(null, 7, 0, 201, 101));
            assertEquals(new CaptureArea(17, 5, 50, 20), WindowImage.compose(new CaptureArea(10, 5, 50, 20), 7, 0, 300, 200));
            assertEquals(new CaptureArea(10, 5, 90, 94), WindowImage.compose(new CaptureArea(10, 5, 500, 500), 0, 0, 100, 100));
            assertNull(WindowImage.compose(new CaptureArea(99, 0, 50, 50), 0, 0, 100, 100));
        }

        @Test
        @DisplayName("window identifiers are decimal handles")
        void handles() {
            assertEquals(123456789L, TopWindows.handle("123456789").address());
            assertEquals(MemorySegment.NULL, TopWindows.handle("not a handle"));
            assertEquals(MemorySegment.NULL, TopWindows.handle("-"));
        }

        @Test
        @DisplayName("the Windows Graphics Capture slots follow the IInspectable layout")
        void slots() {
            assertEquals(6, GraphicsCapture.FIRST_METHOD);
            assertEquals(7, GraphicsCapture.ITEM_GET_SIZE);
            assertEquals(7, GraphicsCapture.POOL_TRY_GET_NEXT_FRAME);
            assertEquals(10, GraphicsCapture.POOL_CREATE_CAPTURE_SESSION);
            assertEquals(8, GraphicsCapture.FRAME_GET_CONTENT_SIZE);
            assertEquals(8, GraphicsCapture.SIZE.byteSize());
            assertEquals(416, DeviceNotifications.FILTER_SIZE);
        }
    }

    @Nested
    @DisplayName("live")
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "[platform:windows] the Windows backend runs only on Windows")
    class Live {
        @Test
        @DisplayName("Windows Graphics Capture and PrintWindow both capture the fixture's two halves")
        void paths() {
            assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
                try (var fixture = FixtureWindow.split(160, 120, 320, 240)) {
                    var window = fixture.find(Duration.ofSeconds(15));
                    var options = ScreenCaptureOptions.builder().frameRate(15).build();
                    var supported = Apartment.call("wgc-check",
                            GraphicsCapture::supported);
                    try (var session = ScreenSession.open(window, options, false)) {
                        assertEquals(supported, session.graphicsCapture(), "Windows Graphics Capture supported: " + supported);
                        assertHalves(session);
                    }
                    try (var session = ScreenSession.open(window, options, true)) {
                        assertEquals(false, session.graphicsCapture());
                        assertHalves(session);
                    }
                }
            });
        }

        @Test
        @DisplayName("the camera and monitor interface notifications register and unregister")
        void notifications() {
            var notifications = DeviceNotifications.start(() -> {
            });
            assertNotNull(notifications);
            try {
                assertEquals(3, notifications.registered());
            } finally {
                notifications.close();
            }
            notifications.close();
        }

        private static void assertHalves(ScreenSession session) throws InterruptedException {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            String last = "no frame";
            while (System.nanoTime() < deadline) {
                var frame = session.read();
                assertNotNull(frame);
                var rgba = ImageOps.toRgba(frame.picture());
                var left = pixel(rgba, rgba.width() / 4, rgba.height() / 2);
                var right = pixel(rgba, 3 * rgba.width() / 4, rgba.height() / 2);
                if (near(left, FixtureWindow.RED) && near(right, FixtureWindow.BLUE)) {
                    assertEquals(320, session.width());
                    assertEquals(240, session.height());
                    return;
                }
                last = String.format("left %06x right %06x", left, right);
            }
            throw new AssertionError(last);
        }

        private static int pixel(com.github.auties00.smalt.image.RgbaPicture picture, int x, int y) {
            var offset = y * picture.stride() + 4 * x;
            return (picture.pixels()[offset] & 0xFF) << 16 | (picture.pixels()[offset + 1] & 0xFF) << 8
                   | picture.pixels()[offset + 2] & 0xFF;
        }

        private static boolean near(int actual, int wanted) {
            for (var shift = 0; shift <= 16; shift += 8) {
                if (Math.abs((actual >> shift & 0xFF) - (wanted >> shift & 0xFF)) > 48) {
                    return false;
                }
            }
            return true;
        }
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "[platform:not-windows] the configuration manager exists on Windows")
    @DisplayName("the notifications are absent without the configuration manager")
    void absentElsewhere() {
        assertNull(DeviceNotifications.start(() -> {
        }));
    }
}
