package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.ScreenCaptureOptions;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lists the outputs of the headless Wayland compositor CI provisions and captures a screen through
 * the screen cast portal of its session bus ({@code DBUS_SESSION_BUS_ADDRESS}) and PipeWire. The
 * compositor's socket is named by {@code SMALT_TEST_WAYLAND_DISPLAY}; the portal is CI's scripted
 * one, which shares a PipeWire video source of the solid color {@code SMALT_TEST_WAYLAND_COLOR} at
 * the output's size without asking, through the portal's own requests, signals and descriptor
 * passing, so the whole path from the session bus to the converted frames runs.
 */
@DisplayName("Wayland screen capture (live)")
@EnabledOnOs(value = OS.LINUX, disabledReason = "[platform:linux] the Linux backend runs only on Linux")
class WaylandLiveTest {
    private static CaptureDevice.Screen output() {
        var socket = Skip.setting("smalt.test.waylandDisplay", "SMALT_TEST_WAYLAND_DISPLAY");
        Skip.unlessDevice(!socket.isEmpty(), "wayland-screen", "set SMALT_TEST_WAYLAND_DISPLAY to a compositor's socket");
        var screens = new LinuxCaptureBackend().screens(Path.of(socket));
        Skip.unlessDevice(!screens.isEmpty() && screens.getFirst().id().startsWith(WaylandOutputs.PREFIX), "wayland-screen",
                "the compositor at " + socket + " lists no output");
        assertEquals(1, screens.size(), screens.toString());
        assertEquals(WaylandOutputs.PREFIX + "HEADLESS-1", screens.getFirst().id(), screens.toString());
        return screens.getFirst();
    }

    private static void assertColor(VideoCaptureSession.Frame frame, int x, int y) {
        var color = Integer.parseInt(Skip.setting("smalt.test.waylandColor", "SMALT_TEST_WAYLAND_COLOR"), 16);
        var rgba = ImageOps.toRgba(frame.picture());
        var offset = y * rgba.stride() + x * 4;
        for (var channel = 0; channel < 3; channel++) {
            var actual = rgba.pixels()[offset + channel] & 0xFF;
            var wanted = (color >> (16 - 8 * channel)) & 0xFF;
            assertTrue(Math.abs(actual - wanted) <= 8, "channel " + channel + " at " + x + "," + y + " is " + actual + " instead of "
                    + wanted);
        }
    }

    @Test
    @DisplayName("the compositor's output is listed with its logical size")
    void listed() {
        var screen = output();
        assertEquals(1280, screen.width());
        assertEquals(720, screen.height());
        assertTrue(screen.primary());
        assertEquals(java.util.List.of(), WaylandOutputs.list(Path.of("/nonexistent/wayland-0")));
    }

    @Test
    @DisplayName("the output is captured at its size with the background color, repeating while nothing changes")
    void capture() {
        var screen = output();
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var backend = new LinuxCaptureBackend();
            try (var session = backend.open(screen, ScreenCaptureOptions.builder().frameRate(10).build())) {
                assertEquals(1280, session.width());
                assertEquals(720, session.height());
                assertEquals(screen, session.device());
                var previous = -1L;
                var start = System.nanoTime();
                for (var index = 0; index < 8; index++) {
                    var frame = session.read();
                    assertNotNull(frame);
                    assertEquals(ChromaFormat.YUV420, frame.picture().chromaFormat());
                    assertEquals(1280, frame.picture().width());
                    assertTrue(frame.timestampMicros() > previous);
                    previous = frame.timestampMicros();
                    assertColor(frame, 640, 360);
                    assertColor(frame, 10, 10);
                    assertColor(frame, 1269, 709);
                }
                var seconds = (System.nanoTime() - start) / 1e9;
                assertTrue(seconds < 5, "8 frames at 10 per second took " + seconds + " s");
            }
        });
    }

    @Test
    @DisplayName("a region is cropped from the shared output, and a close from another thread ends a read")
    void regionAndClose() {
        var screen = output();
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var options = ScreenCaptureOptions.builder().frameRate(5).region(101, 50, 301, 121).cursor(false).build();
            var session = new LinuxCaptureBackend().open(screen, options);
            try {
                assertEquals(300, session.width());
                assertEquals(120, session.height());
                var frame = session.read();
                assertNotNull(frame);
                assertColor(frame, 150, 60);
                var blocked = CompletableFuture.supplyAsync(() -> {
                    try {
                        while (session.read() != null) {
                            Thread.onSpinWait();
                        }
                        return true;
                    } catch (InterruptedException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
                Thread.sleep(300);
                session.close();
                assertTrue(blocked.get(5, TimeUnit.SECONDS));
                assertNull(session.read());
            } finally {
                session.close();
            }
            try (var again = new LinuxCaptureBackend().open(screen, ScreenCaptureOptions.defaults())) {
                assertNotNull(again.read(), "the portal shares the output again once the first session closed");
            }
        });
    }
}
