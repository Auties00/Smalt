package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CameraControl;
import com.github.auties00.smalt.capture.CameraControls;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the mapping of {@link V4l2Controls} onto the driver controls of a simulated Video4Linux2 driver that
 * answers {@code VIDIOC_QUERYCTRL}, {@code VIDIOC_QUERYMENU}, {@code VIDIOC_G_CTRL} and {@code VIDIOC_S_CTRL}
 * at the offsets of the kernel ABI and records every write.
 */
@DisplayName("V4L2 camera controls")
class V4l2ControlsTest {
    /**
     * A driver with a fixed set of controls.
     */
    private static final class FakeControls implements V4l2Device {
        record Control(int type, int minimum, int maximum, int step, int defaultValue, int flags, Set<Integer> menu) {
        }

        final Map<Integer, Control> controls = new HashMap<>();
        final Map<Integer, Integer> values = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        int failWrites;

        FakeControls integer(int id, int minimum, int maximum, int step, int defaultValue) {
            controls.put(id, new Control(V4l2.CTRL_TYPE_INTEGER, minimum, maximum, step, defaultValue, 0, Set.of()));
            values.put(id, defaultValue);
            return this;
        }

        FakeControls bool(int id, int value) {
            controls.put(id, new Control(V4l2.CTRL_TYPE_BOOLEAN, 0, 1, 1, value, 0, Set.of()));
            values.put(id, value);
            return this;
        }

        FakeControls menu(int id, int maximum, int value, Integer... entries) {
            controls.put(id, new Control(V4l2.CTRL_TYPE_MENU, 0, maximum, 1, value, 0, Set.of(entries)));
            values.put(id, value);
            return this;
        }

        FakeControls flags(int id, int flags) {
            var control = controls.get(id);
            controls.put(id, new Control(control.type(), control.minimum(), control.maximum(), control.step(),
                    control.defaultValue(), flags, control.menu()));
            return this;
        }

        @Override
        public int ioctl(long request, MemorySegment argument) {
            if (request == V4l2.VIDIOC_QUERYCTRL) {
                var control = controls.get(argument.get(JAVA_INT, V4l2.QUERYCTRL_ID));
                if (control == null) {
                    return Posix.EINVAL;
                }
                argument.set(JAVA_INT, V4l2.QUERYCTRL_TYPE, control.type());
                argument.set(JAVA_INT, V4l2.QUERYCTRL_MINIMUM, control.minimum());
                argument.set(JAVA_INT, V4l2.QUERYCTRL_MAXIMUM, control.maximum());
                argument.set(JAVA_INT, V4l2.QUERYCTRL_STEP, control.step());
                argument.set(JAVA_INT, V4l2.QUERYCTRL_DEFAULT, control.defaultValue());
                argument.set(JAVA_INT, V4l2.QUERYCTRL_FLAGS, control.flags());
                return 0;
            }
            if (request == V4l2.VIDIOC_QUERYMENU) {
                var control = controls.get(argument.get(JAVA_INT, V4l2.QUERYMENU_ID));
                var index = argument.get(JAVA_INT, V4l2.QUERYMENU_INDEX);
                return control != null && control.menu().contains(index) ? 0 : Posix.EINVAL;
            }
            if (request == V4l2.VIDIOC_G_CTRL) {
                var value = values.get(argument.get(JAVA_INT, V4l2.CONTROL_ID));
                if (value == null) {
                    return Posix.EINVAL;
                }
                argument.set(JAVA_INT, V4l2.CONTROL_VALUE, value);
                return 0;
            }
            if (request == V4l2.VIDIOC_S_CTRL) {
                if (failWrites > 0) {
                    failWrites--;
                    return Posix.EBUSY;
                }
                var id = argument.get(JAVA_INT, V4l2.CONTROL_ID);
                var value = argument.get(JAVA_INT, V4l2.CONTROL_VALUE);
                if (!controls.containsKey(id)) {
                    return Posix.EINVAL;
                }
                values.put(id, value);
                writes.add(Integer.toHexString(id) + "=" + value);
                return 0;
            }
            return Posix.EINVAL;
        }
    }

    /**
     * A UVC webcam: picture controls, automatic white balance, an exposure menu without the full automatic
     * entry, autofocus and a power-line menu with the automatic entry.
     */
    private static FakeControls webcam() {
        return new FakeControls()
                .integer(V4l2.CID_BRIGHTNESS, -64, 64, 1, 0)
                .integer(V4l2.CID_CONTRAST, 0, 95, 1, 32)
                .integer(V4l2.CID_GAIN, 0, 100, 1, 0)
                .integer(V4l2.CID_WHITE_BALANCE_TEMPERATURE, 2800, 6500, 10, 4600)
                .bool(V4l2.CID_AUTO_WHITE_BALANCE, 1)
                .menu(V4l2.CID_POWER_LINE_FREQUENCY, 3, V4l2.PLF_60HZ, 0, 1, 2, 3)
                .menu(V4l2.CID_EXPOSURE_AUTO, 3, V4l2.EXPOSURE_APERTURE_PRIORITY, V4l2.EXPOSURE_MANUAL,
                        V4l2.EXPOSURE_APERTURE_PRIORITY)
                .integer(V4l2.CID_EXPOSURE_ABSOLUTE, 3, 2047, 1, 166)
                .integer(V4l2.CID_FOCUS_ABSOLUTE, 0, 250, 5, 0)
                .bool(V4l2.CID_FOCUS_AUTO, 1)
                .integer(V4l2.CID_ZOOM_ABSOLUTE, 100, 500, 1, 100)
                .integer(V4l2.CID_PAN_ABSOLUTE, -36000, 36000, 3600, 0);
    }

    @Test
    @DisplayName("the controls the driver offers are mapped, in their units, with their automatic modes")
    void capabilities() {
        var controls = V4l2Controls.query(webcam());
        assertEquals(List.of(CameraControl.BRIGHTNESS, CameraControl.CONTRAST, CameraControl.GAIN, CameraControl.WHITE_BALANCE,
                        CameraControl.POWER_LINE_FREQUENCY, CameraControl.EXPOSURE, CameraControl.FOCUS, CameraControl.ZOOM,
                        CameraControl.PAN),
                controls.capabilities().stream().map(CameraControls.Capability::control).toList());
        assertEquals(new CameraControls.Capability(CameraControl.BRIGHTNESS,
                Optional.of(new CameraControls.Range(-64, 64, 1, 0, List.of())), false), cap(controls, CameraControl.BRIGHTNESS));
        assertEquals(new CameraControls.Capability(CameraControl.WHITE_BALANCE,
                Optional.of(new CameraControls.Range(2800, 6500, 10, 4600, List.of())), true), cap(controls, CameraControl.WHITE_BALANCE));
        assertEquals(new CameraControls.Capability(CameraControl.EXPOSURE,
                Optional.of(new CameraControls.Range(300, 204_700, 100, 16_600, List.of())), true), cap(controls, CameraControl.EXPOSURE));
        assertEquals(new CameraControls.Capability(CameraControl.POWER_LINE_FREQUENCY,
                Optional.of(new CameraControls.Range(0, 60, 0, 60, List.of(0L, 50L, 60L))), true),
                cap(controls, CameraControl.POWER_LINE_FREQUENCY));
        assertFalse(cap(controls, CameraControl.GAIN).automatic());
        assertTrue(cap(controls, CameraControl.FOCUS).automatic());
        assertEquals(new CameraControls.Range(-36000, 36000, 3600, 0, List.of()), cap(controls, CameraControl.PAN).range().orElseThrow());
    }

    @Test
    @DisplayName("reads convert exposure to microseconds and the power-line menu to hertz")
    void reads() {
        var device = webcam();
        var controls = V4l2Controls.query(device);
        assertEquals(new CameraControls.State(OptionalLong.of(16_600), true), controls.get(CameraControl.EXPOSURE));
        assertEquals(new CameraControls.State(OptionalLong.of(60), false), controls.get(CameraControl.POWER_LINE_FREQUENCY));
        assertEquals(new CameraControls.State(OptionalLong.of(4600), true), controls.get(CameraControl.WHITE_BALANCE));
        assertEquals(new CameraControls.State(OptionalLong.of(32), false), controls.get(CameraControl.CONTRAST));
        device.values.put(V4l2.CID_POWER_LINE_FREQUENCY, V4l2.PLF_AUTO);
        assertEquals(new CameraControls.State(OptionalLong.empty(), true), controls.get(CameraControl.POWER_LINE_FREQUENCY));
        device.values.put(V4l2.CID_EXPOSURE_AUTO, V4l2.EXPOSURE_MANUAL);
        assertFalse(controls.get(CameraControl.EXPOSURE).automatic());
    }

    @Test
    @DisplayName("a manual value leaves the automatic mode first, then writes the value in driver units")
    void writes() {
        var device = webcam();
        var controls = V4l2Controls.query(device);
        controls.set(CameraControl.EXPOSURE, 10_000);
        controls.set(CameraControl.WHITE_BALANCE, 3200);
        controls.set(CameraControl.POWER_LINE_FREQUENCY, 50);
        controls.set(CameraControl.BRIGHTNESS, -3);
        assertEquals(List.of(
                hex(V4l2.CID_EXPOSURE_AUTO) + "=" + V4l2.EXPOSURE_MANUAL, hex(V4l2.CID_EXPOSURE_ABSOLUTE) + "=100",
                hex(V4l2.CID_AUTO_WHITE_BALANCE) + "=0", hex(V4l2.CID_WHITE_BALANCE_TEMPERATURE) + "=3200",
                hex(V4l2.CID_POWER_LINE_FREQUENCY) + "=" + V4l2.PLF_50HZ, hex(V4l2.CID_BRIGHTNESS) + "=-3"), device.writes);
    }

    @Test
    @DisplayName("the automatic modes switch through their boolean, the exposure menu and the power-line menu")
    void automatic() {
        var device = webcam();
        var controls = V4l2Controls.query(device);
        controls.setAutomatic(CameraControl.EXPOSURE, false);
        controls.setAutomatic(CameraControl.EXPOSURE, true);
        controls.setAutomatic(CameraControl.FOCUS, false);
        controls.setAutomatic(CameraControl.POWER_LINE_FREQUENCY, true);
        controls.setAutomatic(CameraControl.POWER_LINE_FREQUENCY, false);
        assertEquals(List.of(
                hex(V4l2.CID_EXPOSURE_AUTO) + "=" + V4l2.EXPOSURE_MANUAL,
                hex(V4l2.CID_EXPOSURE_AUTO) + "=" + V4l2.EXPOSURE_APERTURE_PRIORITY,
                hex(V4l2.CID_FOCUS_AUTO) + "=0",
                hex(V4l2.CID_POWER_LINE_FREQUENCY) + "=" + V4l2.PLF_AUTO,
                hex(V4l2.CID_POWER_LINE_FREQUENCY) + "=" + V4l2.PLF_60HZ), device.writes);
        assertThrows(MediaException.Unsupported.class, () -> controls.setAutomatic(CameraControl.BRIGHTNESS, true));
    }

    @Test
    @DisplayName("an exposure menu with the full automatic entry uses it for the automatic mode")
    void fullAutomaticExposure() {
        var device = new FakeControls()
                .menu(V4l2.CID_EXPOSURE_AUTO, 3, V4l2.EXPOSURE_AUTO, V4l2.EXPOSURE_AUTO, V4l2.EXPOSURE_MANUAL,
                        V4l2.EXPOSURE_APERTURE_PRIORITY);
        var controls = V4l2Controls.query(device);
        var exposure = cap(controls, CameraControl.EXPOSURE);
        assertEquals(Optional.empty(), exposure.range());
        assertTrue(exposure.automatic());
        assertEquals(new CameraControls.State(OptionalLong.empty(), true), controls.get(CameraControl.EXPOSURE));
        controls.setAutomatic(CameraControl.EXPOSURE, false);
        controls.setAutomatic(CameraControl.EXPOSURE, true);
        assertEquals(List.of(hex(V4l2.CID_EXPOSURE_AUTO) + "=" + V4l2.EXPOSURE_MANUAL,
                hex(V4l2.CID_EXPOSURE_AUTO) + "=" + V4l2.EXPOSURE_AUTO), device.writes);
        assertThrows(MediaException.Unsupported.class, () -> controls.set(CameraControl.EXPOSURE, 1000));
    }

    @Test
    @DisplayName("disabled controls, read-only values without a mode and controls of the wrong type are left out")
    void filtered() {
        var device = new FakeControls()
                .integer(V4l2.CID_BRIGHTNESS, 0, 255, 1, 128).flags(V4l2.CID_BRIGHTNESS, V4l2.CTRL_FLAG_DISABLED)
                .integer(V4l2.CID_GAIN, 0, 255, 1, 0).flags(V4l2.CID_GAIN, V4l2.CTRL_FLAG_READ_ONLY)
                .bool(V4l2.CID_AUTOGAIN, 1)
                .integer(V4l2.CID_CONTRAST, 0, 255, 1, 0).flags(V4l2.CID_CONTRAST, V4l2.CTRL_FLAG_READ_ONLY)
                .bool(V4l2.CID_SATURATION, 1)
                .menu(V4l2.CID_POWER_LINE_FREQUENCY, 2, V4l2.PLF_DISABLED, 0, 2);
        var controls = V4l2Controls.query(device);
        assertEquals(List.of(CameraControl.GAIN, CameraControl.POWER_LINE_FREQUENCY),
                controls.capabilities().stream().map(CameraControls.Capability::control).toList());
        var gain = cap(controls, CameraControl.GAIN);
        assertEquals(Optional.empty(), gain.range());
        assertTrue(gain.automatic());
        assertEquals(new CameraControls.State(OptionalLong.of(0), true), controls.get(CameraControl.GAIN));
        var frequency = cap(controls, CameraControl.POWER_LINE_FREQUENCY);
        assertEquals(new CameraControls.Range(0, 60, 0, 0, List.of(0L, 60L)), frequency.range().orElseThrow());
        assertFalse(frequency.automatic());
        assertThrows(IllegalArgumentException.class, () -> controls.set(CameraControl.POWER_LINE_FREQUENCY, 50));
    }

    @Test
    @DisplayName("a driver that refuses a request raises an I/O failure, and one without controls offers none")
    void failures() {
        var device = webcam();
        var controls = V4l2Controls.query(device);
        device.failWrites = 1;
        assertThrows(MediaException.Io.class, () -> controls.set(CameraControl.ZOOM, 200));
        controls.set(CameraControl.ZOOM, 200);
        assertEquals(List.of(hex(V4l2.CID_ZOOM_ABSOLUTE) + "=200"), device.writes);
        assertEquals(List.of(), V4l2Controls.query((request, argument) -> Posix.ENOTTY).capabilities());
    }

    private static CameraControls.Capability cap(CameraControls controls, CameraControl control) {
        return controls.capability(control).orElseThrow(() -> new AssertionError(control + " missing"));
    }

    private static String hex(int id) {
        return Integer.toHexString(id);
    }
}
