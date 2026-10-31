package com.github.auties00.smalt.capture;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.CaptureArea;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the value types of the screen, window and camera control API: the screen capture options, the area
 * they resolve to on a source, the camera control ranges, and the argument checks of the new facade verbs.
 */
@DisplayName("capture options and values")
class CaptureOptionsTest {
    @Nested
    @DisplayName("screen capture options")
    class Options {
        @Test
        @DisplayName("the defaults are 30 frames per second, the whole source and the pointer drawn")
        void defaults() {
            var defaults = ScreenCaptureOptions.defaults();
            assertEquals(new Rational(30, 1), defaults.frameRate());
            assertEquals(ScreenCaptureOptions.DEFAULT_FRAME_RATE, defaults.frameRate());
            assertEquals(Optional.empty(), defaults.region());
            assertTrue(defaults.cursor());
            assertEquals(defaults, ScreenCaptureOptions.builder().build());
            assertEquals(defaults.hashCode(), ScreenCaptureOptions.builder().build().hashCode());
        }

        @Test
        @DisplayName("the builder sets every value, toBuilder copies them, and wholeSource resets the region")
        void builder() {
            var options = ScreenCaptureOptions.builder().frameRate(new Rational(30_000, 1_001)).region(10, 20, 300, 200)
                    .cursor(false).build();
            assertEquals(new Rational(30_000, 1_001), options.frameRate());
            assertEquals(Optional.of(new ScreenCaptureOptions.Region(10, 20, 300, 200)), options.region());
            assertFalse(options.cursor());
            assertEquals(options, options.toBuilder().build());
            var whole = options.toBuilder().wholeSource().frameRate(60).build();
            assertEquals(Optional.empty(), whole.region());
            assertEquals(new Rational(60, 1), whole.frameRate());
            assertFalse(whole.cursor());
            assertNotEquals(options, whole);
            assertTrue(options.toString().contains("cursor=false"), options.toString());
        }

        @Test
        @DisplayName("the builder rejects null values and non-positive rates")
        void rejects() {
            var builder = ScreenCaptureOptions.builder();
            assertThrows(NullPointerException.class, () -> builder.frameRate(null));
            assertThrows(NullPointerException.class, () -> builder.region(null));
            assertThrows(IllegalArgumentException.class, () -> builder.frameRate(0));
            assertThrows(IllegalArgumentException.class, () -> builder.region(-1, 0, 10, 10));
            assertThrows(IllegalArgumentException.class, () -> builder.region(0, -1, 10, 10));
            assertThrows(IllegalArgumentException.class, () -> builder.region(0, 0, 0, 10));
            assertThrows(IllegalArgumentException.class, () -> builder.region(0, 0, 10, 0));
        }

        @ParameterizedTest(name = "region {0},{1} {2}x{3} on 100x50 clips to {4}")
        @CsvSource({
                "0, 0, 100, 50, 0:0:100:50",
                "10, 5, 20, 10, 10:5:20:10",
                "90, 40, 50, 50, 90:40:10:10",
                "0, 0, 2147483647, 2147483647, 0:0:100:50",
                "100, 0, 10, 10, none",
                "0, 50, 10, 10, none"
        })
        @DisplayName("a region is clipped to the source")
        void clip(int x, int y, int width, int height, String expected) {
            var clipped = new ScreenCaptureOptions.Region(x, y, width, height).clip(100, 50);
            assertEquals(expected, clipped.map(r -> r.x() + ":" + r.y() + ":" + r.width() + ":" + r.height()).orElse("none"));
        }
    }

    @Nested
    @DisplayName("capture area")
    class Area {
        @Test
        @DisplayName("the whole source is cropped to even dimensions")
        void whole() {
            var area = CaptureArea.of(ScreenCaptureOptions.defaults(), 1921, 1081, "the screen");
            assertEquals(new CaptureArea(0, 0, 1920, 1080), area);
            assertTrue(area.covers(1921, 1081));
            assertTrue(area.covers(1920, 1080));
            assertFalse(area.covers(1922, 1080));
        }

        @Test
        @DisplayName("a region is clipped to the source and cropped to even dimensions")
        void region() {
            var options = ScreenCaptureOptions.builder().region(101, 51, 1000, 1000).build();
            var area = CaptureArea.of(options, 200, 100, "the window");
            assertEquals(new CaptureArea(101, 51, 98, 48), area);
            assertFalse(area.covers(200, 100));
        }

        @Test
        @DisplayName("a region outside the source, a tiny part of one, and a tiny source are unsupported")
        void unsupported() {
            assertThrows(MediaException.Unsupported.class, () -> CaptureArea.of(
                    ScreenCaptureOptions.builder().region(200, 0, 10, 10).build(), 200, 100, "the screen"));
            assertThrows(MediaException.Unsupported.class, () -> CaptureArea.of(
                    ScreenCaptureOptions.builder().region(199, 0, 10, 10).build(), 200, 100, "the screen"));
            assertThrows(MediaException.Unsupported.class, () -> CaptureArea.of(ScreenCaptureOptions.defaults(), 1, 100, "x"));
        }
    }

    @Nested
    @DisplayName("camera control values")
    class Controls {
        @Test
        @DisplayName("a continuous range accepts the values between its bounds")
        void continuous() {
            var range = new CameraControls.Range(-10, 10, 2, 0, List.of());
            assertTrue(range.accepts(-10));
            assertTrue(range.accepts(10));
            assertTrue(range.accepts(3));
            assertFalse(range.accepts(11));
            assertFalse(range.accepts(-11));
        }

        @Test
        @DisplayName("a discrete range accepts only its values")
        void discrete() {
            var range = new CameraControls.Range(0, 60, 0, 50, List.of(0L, 50L, 60L));
            assertTrue(range.accepts(50));
            assertFalse(range.accepts(55));
            assertThrows(UnsupportedOperationException.class, () -> range.values().add(1L));
        }

        @Test
        @DisplayName("malformed ranges and capabilities are rejected")
        void malformed() {
            assertThrows(IllegalArgumentException.class, () -> new CameraControls.Range(10, 0, 1, 0, List.of()));
            assertThrows(IllegalArgumentException.class, () -> new CameraControls.Range(0, 10, 0, 0, List.of()));
            assertThrows(IllegalArgumentException.class, () -> new CameraControls.Range(0, 10, -1, 0, List.of(1L)));
            assertThrows(IllegalArgumentException.class, () -> new CameraControls.Range(0, 10, 0, 0, List.of(5L, 5L)));
            assertThrows(IllegalArgumentException.class, () -> new CameraControls.Range(0, 10, 0, 0, List.of(5L, 1L)));
            assertThrows(IllegalArgumentException.class, () -> new CameraControls.Range(0, 10, 0, 0, List.of(11L)));
            assertThrows(NullPointerException.class, () -> new CameraControls.Range(0, 10, 0, 0, null));
            assertThrows(IllegalArgumentException.class, () -> new CameraControls.Range(0, 10, 5, 0, List.of(0L, 5L)));
            assertThrows(IllegalArgumentException.class, () -> new CameraControls.Range(0, 10, 1, 11, List.of()));
            assertThrows(IllegalArgumentException.class, () -> new CameraControls.Range(0, 10, 1, -1, List.of()));
            assertThrows(IllegalArgumentException.class, () -> new CameraControls.Range(0, 10, 0, 3, List.of(0L, 10L)));
            assertThrows(IllegalArgumentException.class,
                    () -> new CameraControls.Capability(CameraControl.ZOOM, Optional.empty(), false));
            assertThrows(NullPointerException.class, () -> new CameraControls.Capability(null, Optional.empty(), true));
            assertThrows(NullPointerException.class, () -> new CameraControls.State(null, true));
            assertEquals(OptionalLong.empty(), new CameraControls.State(OptionalLong.empty(), true).value());
        }
    }

    @Nested
    @DisplayName("facade arguments")
    class Facade {
        private static final CaptureDevice.Screen SCREEN = new CaptureDevice.Screen("screen", "Screen", 0, 0, 64, 32, true);

        private static final CaptureDevice.Window WINDOW = new CaptureDevice.Window("1", "Title", "app", 0, 0, 40, 20);

        @Test
        @DisplayName("the window record rejects nulls and empty sizes")
        void window() {
            assertThrows(NullPointerException.class, () -> new CaptureDevice.Window(null, "", "", 0, 0, 1, 1));
            assertThrows(NullPointerException.class, () -> new CaptureDevice.Window("1", null, "", 0, 0, 1, 1));
            assertThrows(NullPointerException.class, () -> new CaptureDevice.Window("1", "", null, 0, 0, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> new CaptureDevice.Window("1", "", "", 0, 0, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> new CaptureDevice.Window("1", "", "", 0, 0, 1, -1));
        }

        @Test
        @DisplayName("the window and listener verbs reject null arguments")
        void nulls() {
            assertThrows(NullPointerException.class, () -> VideoDevices.window(null));
            assertThrows(NullPointerException.class, () -> VideoDevices.addListener(null));
            assertThrows(NullPointerException.class, () -> VideoDevices.removeListener(null));
            assertThrows(NullPointerException.class, () -> VideoDevices.openCapture((CaptureDevice.Window) null,
                    ScreenCaptureOptions.defaults()));
            assertThrows(NullPointerException.class, () -> VideoDevices.openCapture(WINDOW, null));
            assertThrows(NullPointerException.class, () -> VideoDevices.openCapture((CaptureDevice.Screen) null,
                    ScreenCaptureOptions.defaults()));
            assertThrows(NullPointerException.class, () -> VideoDevices.openCapture(SCREEN, (ScreenCaptureOptions) null));
        }

        @Test
        @DisplayName("a region outside the listed screen or window is rejected before any operating system call")
        void regionOutside() {
            var outside = ScreenCaptureOptions.builder().region(64, 0, 8, 8).build();
            assertThrows(IllegalArgumentException.class, () -> VideoDevices.openCapture(SCREEN, outside));
            var belowWindow = ScreenCaptureOptions.builder().region(0, 20, 8, 8).build();
            assertThrows(IllegalArgumentException.class, () -> VideoDevices.openCapture(WINDOW, belowWindow));
        }

        @Test
        @DisplayName("removing a listener that was never added does nothing")
        void unknownListener() {
            VideoDevices.removeListener(event -> {
            });
        }
    }
}
