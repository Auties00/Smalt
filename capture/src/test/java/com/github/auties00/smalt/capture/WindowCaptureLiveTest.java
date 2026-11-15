package com.github.auties00.smalt.capture;

import com.github.auties00.smalt.capture.internal.Platform;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Captures windows, screen regions and the pointer through the public API on the machine's own display,
 * against {@link FixtureWindow}s of known content shown by a child JVM.
 *
 * <p>Each test shows a window split into a red and a blue half and checks the colors of the captured
 * pictures at points away from the split, so a capture of the wrong window, the wrong part of it, or one
 * whose channels or orientation are swapped fails. Linux needs an X11 display; macOS needs
 * {@code smalt.capture.live}, as the other live macOS tests do.
 */
@DisplayName("window and region capture (live)")
class WindowCaptureLiveTest {
    /**
     * The tolerance of a color sample after the 4:2:0 round trip, per channel.
     */
    private static final int TOLERANCE = 48;

    private static final int X = 120;

    private static final int Y = 140;

    private static final int WIDTH = 320;

    private static final int HEIGHT = 240;

    @BeforeEach
    void requireDisplay() {
        Skip.unless(Platform.current() != Platform.UNSUPPORTED, "platform:unsupported", "no capture backend on this system");
        if (Platform.current() == Platform.MACOS) {
            Skip.unless(Boolean.parseBoolean(Skip.setting("smalt.capture.live", "SMALT_CAPTURE_LIVE")), "opt-in:capture-live",
                    "set -Dsmalt.capture.live=true (or SMALT_CAPTURE_LIVE=true) to open devices");
        }
        if (Platform.current() == Platform.LINUX) {
            Skip.unlessDevice(!VideoDevices.screens().isEmpty(), "window", "no X11 display to show a window on");
        }
    }

    @Test
    @DisplayName("lists the fixture window with its title, application and bounds and captures its two halves")
    void window() {
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            try (var fixture = FixtureWindow.split(X, Y, WIDTH, HEIGHT)) {
                var window = fixture.find(Duration.ofSeconds(15));
                // macOS names a source-launched program's application after its launcher class
                assertTrue(Platform.current() == Platform.MACOS ? !window.application().isEmpty()
                        : window.application().toLowerCase(Locale.ROOT).contains("java"), window.toString());
                if (Platform.current() != Platform.MACOS) {
                    assertEquals(List.of(X, Y, WIDTH, HEIGHT), List.of(window.x(), window.y(), window.width(), window.height()),
                            window.toString());
                }
                assertEquals(VideoDevices.window(window.id()).orElseThrow().id(), window.id());
                try (var session = VideoDevices.openCapture(window, ScreenCaptureOptions.defaults())) {
                    assertEquals(window, session.device());
                    assertEquals(window.width() & ~1, session.width());
                    assertEquals(window.height() & ~1, session.height());
                    awaitColors(session, new double[][]{{0.25, 0.5}, {0.1, 0.1}, {0.4, 0.9}}, FixtureWindow.RED,
                            new double[][]{{0.75, 0.5}, {0.9, 0.1}, {0.6, 0.9}}, FixtureWindow.BLUE);
                }
            }
        });
    }

    @Test
    @DisplayName("a region of a window delivers only that part, at its size")
    void windowRegion() {
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            try (var fixture = FixtureWindow.split(X, Y, WIDTH, HEIGHT)) {
                var window = fixture.find(Duration.ofSeconds(15));
                var half = window.width() / 2;
                var options = ScreenCaptureOptions.builder().region(half, 0, window.width() - half, window.height())
                        .frameRate(15).build();
                try (var session = VideoDevices.openCapture(window, options)) {
                    assertEquals((window.width() - half) & ~1, session.width());
                    assertEquals(window.height() & ~1, session.height());
                    awaitColors(session, new double[][]{{0.1, 0.5}, {0.5, 0.5}, {0.9, 0.9}}, FixtureWindow.BLUE,
                            new double[0][], FixtureWindow.RED);
                }
                var left = ScreenCaptureOptions.builder().region(0, 0, half, window.height()).build();
                try (var session = VideoDevices.openCapture(window, left)) {
                    awaitColors(session, new double[][]{{0.1, 0.5}, {0.5, 0.5}, {0.9, 0.1}}, FixtureWindow.RED,
                            new double[0][], FixtureWindow.BLUE);
                }
            }
        });
    }

    @Test
    @DisplayName("a window covered by another one is still captured whole")
    void covered() {
        assertTimeoutPreemptively(Duration.ofSeconds(150), () -> {
            try (var fixture = FixtureWindow.split(X, Y, WIDTH, HEIGHT)) {
                var window = fixture.find(Duration.ofSeconds(15));
                try (var cover = FixtureWindow.solidOnTop(FixtureWindow.GREEN, X, Y, WIDTH * 3 / 4, HEIGHT)) {
                    cover.find(Duration.ofSeconds(15));
                    try (var session = VideoDevices.openCapture(window, ScreenCaptureOptions.defaults())) {
                        awaitColors(session, new double[][]{{0.25, 0.5}}, FixtureWindow.RED,
                                new double[][]{{0.6, 0.5}, {0.9, 0.5}}, FixtureWindow.BLUE);
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("closing the captured window ends the session")
    void closedWindow() {
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            var fixture = FixtureWindow.split(X, Y, WIDTH, HEIGHT);
            var window = fixture.find(Duration.ofSeconds(15));
            try (var session = VideoDevices.openCapture(window, ScreenCaptureOptions.builder().frameRate(20).build())) {
                assertNotNull(session.read());
                fixture.close();
                var ended = CompletableFuture.supplyAsync(() -> {
                    try {
                        for (var frame = session.read(); frame != null; frame = session.read()) {
                            // frames may still arrive until the session notices the window is gone
                        }
                        return true;
                    } catch (InterruptedException exception) {
                        return false;
                    }
                });
                assertTrue(ended.get(30, TimeUnit.SECONDS));
                assertNull(session.read());
            } finally {
                fixture.close();
            }
        });
    }

    @Test
    @DisplayName("a region of a screen equals the same part of a capture of the whole screen")
    void screenRegion() {
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            var screen = VideoDevices.primaryScreen();
            Skip.unlessDevice(screen.isPresent(), "screen", "no screen");
            try (var fixture = FixtureWindow.split(X, Y, WIDTH, HEIGHT)) {
                fixture.find(Duration.ofSeconds(15));
                var whole = grab(screen.get(), ScreenCaptureOptions.builder().cursor(false).build());
                var x = whole.width() / 4 & ~1;
                var y = whole.height() / 4 & ~1;
                var width = whole.width() / 3 & ~1;
                var height = whole.height() / 3 & ~1;
                var region = grab(screen.get(), ScreenCaptureOptions.builder().cursor(false).region(x, y, width, height).build());
                assertEquals(width, region.width());
                assertEquals(height, region.height());
                var difference = 0L;
                for (var row = 0; row < height; row++) {
                    for (var column = 0; column < width; column++) {
                        var a = region.pixels()[row * region.stride() + 4 * column + 1] & 0xFF;
                        var b = whole.pixels()[(y + row) * whole.stride() + 4 * (x + column) + 1] & 0xFF;
                        difference += Math.abs(a - b);
                    }
                }
                var mean = difference / (double) (width * height);
                assertTrue(mean < 4, "mean green difference " + mean + " between the region and the whole screen");
            }
        });
    }

    @Test
    @DisplayName("on Windows and Linux a screen region at the window's bounds sees the window")
    void screenRegionAtWindow() {
        Skip.unless(Platform.current() != Platform.MACOS, "platform:not-macos",
                "macOS places windows in points and captures screens in pixels");
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            try (var fixture = FixtureWindow.split(X, Y, WIDTH, HEIGHT)) {
                var window = fixture.find(Duration.ofSeconds(15));
                var screen = VideoDevices.screens().stream().filter(candidate -> contains(candidate, window)).findFirst();
                Skip.unlessDevice(screen.isPresent(), "screen", "no screen holds the fixture window");
                var options = ScreenCaptureOptions.builder().cursor(false)
                        .region(window.x() - screen.get().x(), window.y() - screen.get().y(), window.width(), window.height()).build();
                try (var session = VideoDevices.openCapture(screen.get(), options)) {
                    assertEquals(WIDTH, session.width());
                    assertEquals(HEIGHT, session.height());
                    awaitColors(session, new double[][]{{0.25, 0.5}}, FixtureWindow.RED, new double[][]{{0.75, 0.5}},
                            FixtureWindow.BLUE);
                }
            }
        });
    }

    @Test
    @DisplayName("the pointer is drawn over a screen and a window when asked, and left out otherwise")
    void pointer() {
        Skip.unless(Platform.current() != Platform.MACOS, "platform:not-macos",
                "the fixture's pointer position is in points while the capture is in pixels");
        assertTimeoutPreemptively(Duration.ofSeconds(150), () -> {
            try (var fixture = FixtureWindow.solidOnTop(FixtureWindow.RED, X, Y, WIDTH, HEIGHT)) {
                var window = fixture.find(Duration.ofSeconds(15));
                var pointerX = X + WIDTH / 2;
                var pointerY = Y + HEIGHT / 2;
                Skip.unlessDevice(fixture.movePointer(pointerX, pointerY), "cursor", "the pointer cannot be moved");
                var screen = VideoDevices.screens().stream().filter(candidate -> contains(candidate, window)).findFirst();
                Skip.unlessDevice(screen.isPresent(), "screen", "no screen holds the fixture window");
                var region = ScreenCaptureOptions.builder().region(pointerX - screen.get().x() - 32, pointerY - screen.get().y() - 32, 64, 64);
                var without = grab(screen.get(), region.cursor(false).build());
                assertEquals(0, deviating(without, FixtureWindow.RED), "the pointer was drawn although it was left out");
                var with = grab(screen.get(), region.cursor(true).build());
                Skip.unlessDevice(deviating(with, FixtureWindow.RED) > 4, "cursor",
                        "the system shows no pointer on this display");
                var windowRegion = ScreenCaptureOptions.builder().region(WIDTH / 2 - 32, HEIGHT / 2 - 32, 64, 64);
                assertEquals(0, deviating(grab(window, windowRegion.cursor(false).build()), FixtureWindow.RED));
                assertTrue(deviating(grab(window, windowRegion.cursor(true).build()), FixtureWindow.RED) > 4,
                        "the pointer is missing from the window capture");
            }
        });
    }

    @Test
    @DisplayName("a device listener can be added and removed on the real backend")
    void listener() throws InterruptedException {
        var events = new LinkedBlockingQueue<VideoDeviceEvent>();
        VideoDeviceListener listener = events::add;
        VideoDevices.addListener(listener);
        try {
            // nothing is plugged in during the test: an event here would come from a spurious difference
            assertNull(events.poll(3, TimeUnit.SECONDS), String.valueOf(events));
        } finally {
            VideoDevices.removeListener(listener);
        }
        Thread.sleep(200);
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive()
                && (thread.getName().equals("smalt-video-watch") || thread.getName().equals("smalt-video-events"))));
    }

    private static boolean contains(CaptureDevice.Screen screen, CaptureDevice.Window window) {
        return window.x() >= screen.x() && window.y() >= screen.y() && window.x() + window.width() <= screen.x() + screen.width()
               && window.y() + window.height() <= screen.y() + screen.height();
    }

    /**
     * Captures a few frames and returns the last one in RGBA.
     */
    private static RgbaPicture grab(CaptureDevice device, ScreenCaptureOptions options)
            throws InterruptedException {
        try (var session = device instanceof CaptureDevice.Screen screen ? VideoDevices.openCapture(screen, options)
                : VideoDevices.openCapture((CaptureDevice.Window) device, options)) {
            VideoCaptureSession.Frame frame = null;
            for (var i = 0; i < 8; i++) {
                var next = session.read();
                assertNotNull(next);
                frame = next;
            }
            return ImageOps.toRgba(frame.picture());
        }
    }

    /**
     * Counts the pixels whose color is far from a color.
     */
    private static int deviating(RgbaPicture picture, int color) {
        var count = 0;
        for (var row = 0; row < picture.height(); row++) {
            for (var column = 0; column < picture.width(); column++) {
                if (!near(picture, column, row, color)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Reads frames until the points of each list have their color, failing after ten seconds.
     *
     * @param first the points of the first color, as fractions of the width and height
     * @param second the points of the second color
     */
    private static void awaitColors(VideoCaptureSession session, double[][] first, int firstColor, double[][] second,
                                    int secondColor) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String last = "no frame";
        while (System.nanoTime() < deadline) {
            var frame = session.read();
            assertNotNull(frame);
            var rgba = ImageOps.toRgba(frame.picture());
            var failure = check(rgba, first, firstColor);
            if (failure == null) {
                failure = check(rgba, second, secondColor);
            }
            if (failure == null) {
                return;
            }
            last = failure;
        }
        throw new AssertionError(last);
    }

    private static String check(RgbaPicture picture, double[][] points, int color) {
        for (var point : points) {
            var x = Math.min(picture.width() - 1, (int) (point[0] * picture.width()));
            var y = Math.min(picture.height() - 1, (int) (point[1] * picture.height()));
            if (!near(picture, x, y, color)) {
                var offset = y * picture.stride() + 4 * x;
                return String.format("pixel %02x%02x%02x at %d,%d of %dx%d instead of %06x", picture.pixels()[offset] & 0xFF,
                        picture.pixels()[offset + 1] & 0xFF, picture.pixels()[offset + 2] & 0xFF, x, y, picture.width(),
                        picture.height(), color);
            }
        }
        return null;
    }

    private static boolean near(RgbaPicture picture, int x, int y, int color) {
        var offset = y * picture.stride() + 4 * x;
        for (var channel = 0; channel < 3; channel++) {
            var actual = picture.pixels()[offset + channel] & 0xFF;
            var wanted = (color >> (16 - 8 * channel)) & 0xFF;
            if (Math.abs(actual - wanted) > TOLERANCE) {
                return false;
            }
        }
        return true;
    }
}
