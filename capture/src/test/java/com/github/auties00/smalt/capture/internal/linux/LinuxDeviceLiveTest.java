package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.CameraControl;
import com.github.auties00.smalt.capture.CameraControls;
import com.github.auties00.smalt.capture.FixtureWindow;
import com.github.auties00.smalt.capture.ScreenCaptureOptions;
import com.github.auties00.smalt.capture.VideoDeviceEvent;
import com.github.auties00.smalt.capture.VideoDeviceListener;
import com.github.auties00.smalt.capture.VideoDevices;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the Linux window capture, camera controls and camera hotplug against the devices CI provisions: an
 * X11 display with the Composite extension, a {@code vivid} test camera named by
 * {@code SMALT_TEST_CAMERA_CONTROLS}, and a script named by {@code SMALT_TEST_CAMERA_HOTPLUG} that adds and
 * removes a {@code v4l2loopback} camera. The control values are checked against {@code v4l2-ctl}, which
 * reads and writes the same driver controls through its own code.
 */
@DisplayName("Linux devices (live)")
@EnabledOnOs(value = OS.LINUX, disabledReason = "[platform:linux] the Linux backend runs only on Linux")
class LinuxDeviceLiveTest {
    /**
     * The {@code v4l2-ctl} names of the controls whose names every kernel shares.
     */
    private static final Map<CameraControl, String> CTL_NAMES = Map.of(CameraControl.BRIGHTNESS, "brightness",
            CameraControl.CONTRAST, "contrast", CameraControl.SATURATION, "saturation", CameraControl.HUE, "hue",
            CameraControl.GAIN, "gain");

    @Test
    @DisplayName("a window is captured through its Composite pixmap")
    void composite() {
        Skip.unlessDevice(!VideoDevices.screens().isEmpty(), "window", "no X11 display");
        var windowing = X11Windowing.instance();
        assertNotNull(windowing);
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
            try (var fixture = FixtureWindow.split(40, 40, 200, 100)) {
                var window = fixture.find(Duration.ofSeconds(15));
                var grabber = X11WindowGrabber.open(windowing, window, ScreenCaptureOptions.defaults());
                try {
                    Skip.unlessDevice(grabber.composited(), "x11-composite", "the X server or libXcomposite lacks Composite");
                    assertNotNull(grabber.grab());
                    assertEquals(200, grabber.width());
                    assertEquals(100, grabber.height());
                } finally {
                    grabber.close();
                }
            }
        });
    }

    @Test
    @DisplayName("the controls of the vivid camera agree with v4l2-ctl in both directions")
    void controls() {
        var path = Skip.setting("smalt.test.cameraControls", "SMALT_TEST_CAMERA_CONTROLS");
        Skip.unlessDevice(!path.isEmpty(), "camera-controls", "set SMALT_TEST_CAMERA_CONTROLS to a vivid node");
        var camera = VideoDevices.camera(path);
        Skip.unlessDevice(camera.isPresent(), "camera-controls", path + " is not a camera");
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            try (var session = VideoDevices.openCapture(camera.get())) {
                assertNotNull(session.read());
                var controls = session.controls();
                var checked = new ArrayList<CameraControl>();
                for (var capability : controls.capabilities()) {
                    var range = capability.range();
                    if (range.isEmpty()) {
                        continue;
                    }
                    var value = target(range.get(), controls.get(capability.control()).value().orElse(range.get().defaultValue()));
                    controls.set(capability.control(), value);
                    assertEquals(value, controls.get(capability.control()).value().orElseThrow(), capability.control().toString());
                    var name = CTL_NAMES.get(capability.control());
                    if (name != null) {
                        assertEquals(value, ctlGet(path, name), name);
                        var other = target(range.get(), value);
                        ctlSet(path, name, other);
                        assertEquals(other, controls.get(capability.control()).value().orElseThrow(), name + " set by v4l2-ctl");
                        checked.add(capability.control());
                    }
                    if (capability.automatic()) {
                        controls.setAutomatic(capability.control(), true);
                        assertTrue(controls.get(capability.control()).automatic(), capability.control().toString());
                        controls.setAutomatic(capability.control(), false);
                        assertFalse(controls.get(capability.control()).automatic(), capability.control().toString());
                    }
                }
                assertFalse(checked.isEmpty(), "no control shared with v4l2-ctl among " + controls.capabilities());
            }
        });
    }

    @Test
    @DisplayName("a camera plugged in and removed while a listener is registered is reported both ways")
    void hotplug() {
        var script = Skip.setting("smalt.test.cameraHotplug", "SMALT_TEST_CAMERA_HOTPLUG");
        Skip.unlessDevice(!script.isEmpty(), "camera-hotplug", "set SMALT_TEST_CAMERA_HOTPLUG to an add/remove script");
        assertTimeoutPreemptively(Duration.ofSeconds(150), () -> {
            var events = new LinkedBlockingQueue<VideoDeviceEvent>();
            VideoDeviceListener listener = events::add;
            VideoDevices.addListener(listener);
            String added = null;
            try {
                run(script, "add");
                added = awaitCamera(events, true, null);
                assertTrue(VideoDevices.camera(added).isPresent(), added);
                run(script, "remove");
                assertEquals(added, awaitCamera(events, false, added));
                assertFalse(VideoDevices.camera(added).isPresent(), added);
            } finally {
                VideoDevices.removeListener(listener);
                if (added != null && VideoDevices.camera(added).isPresent()) {
                    run(script, "remove");
                }
            }
        });
    }

    /**
     * Waits for a camera event of a kind.
     *
     * @param arrival whether to wait for an arrival rather than a removal
     * @param id the camera, or {@code null} for any
     * @return the camera's identifier
     */
    private static String awaitCamera(LinkedBlockingQueue<VideoDeviceEvent> events, boolean arrival, String id)
            throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        var seen = new ArrayList<VideoDeviceEvent>();
        while (System.nanoTime() < deadline) {
            var event = events.poll(1, TimeUnit.SECONDS);
            if (event == null) {
                continue;
            }
            seen.add(event);
            if (arrival && event instanceof VideoDeviceEvent.CameraAdded(var camera) && (id == null || id.equals(camera))) {
                return camera;
            }
            if (!arrival && event instanceof VideoDeviceEvent.CameraRemoved(var camera) && (id == null || id.equals(camera))) {
                return camera;
            }
        }
        throw new AssertionError("no " + (arrival ? "arrival" : "removal") + " among " + seen);
    }

    /**
     * Returns an accepted value other than {@code current}, or {@code current} when the range has one value.
     */
    private static long target(CameraControls.Range range, long current) {
        if (!range.values().isEmpty()) {
            return range.values().stream().filter(value -> value != current).findFirst().orElse(current);
        }
        var candidate = current + range.step() <= range.maximum() ? current + range.step() : current - range.step();
        return candidate < range.minimum() ? current : candidate;
    }

    private static long ctlGet(String device, String name) throws IOException, InterruptedException {
        var output = run("v4l2-ctl", "-d", device, "--get-ctrl=" + name);
        var matcher = Pattern.compile(name + ":\\s*(-?\\d+)").matcher(output);
        assertTrue(matcher.find(), output);
        return Long.parseLong(matcher.group(1));
    }

    private static void ctlSet(String device, String name, long value) throws IOException, InterruptedException {
        run("v4l2-ctl", "-d", device, "--set-ctrl=" + name + "=" + value);
    }

    private static String run(String... command) throws IOException, InterruptedException {
        var process = new ProcessBuilder(List.of(command)).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), String.join(" ", command));
        assertEquals(0, process.exitValue(), String.join(" ", command) + ": " + output);
        return output;
    }
}
