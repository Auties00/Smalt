package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CameraControl;
import com.github.auties00.smalt.capture.CameraControls;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the checks {@link NativeCameraControls} applies before a platform's control calls, over a recording
 * implementation.
 */
@DisplayName("camera control checks")
class NativeCameraControlsTest {
    private static final CameraControls.Range ZOOM = new CameraControls.Range(100, 400, 10, 100, List.of());

    private static final CameraControls.Range FREQUENCY = new CameraControls.Range(0, 60, 0, 50, List.of(0L, 50L, 60L));

    /**
     * Records the calls that pass the checks.
     */
    private static final class Recording extends NativeCameraControls {
        final List<String> calls = new ArrayList<>();
        int released;

        Recording(List<Capability> capabilities) {
            super(capabilities);
        }

        @Override
        protected State read(Capability capability) {
            calls.add("read " + capability.control());
            return new State(OptionalLong.of(7), false);
        }

        @Override
        protected void write(Capability capability, long value) {
            calls.add("write " + capability.control() + " " + value);
        }

        @Override
        protected void writeAutomatic(Capability capability, boolean automatic) {
            calls.add("auto " + capability.control() + " " + automatic);
        }

        @Override
        protected void release() {
            released++;
        }
    }

    private static Recording controls() {
        return new Recording(List.of(
                new CameraControls.Capability(CameraControl.POWER_LINE_FREQUENCY, Optional.of(FREQUENCY), true),
                new CameraControls.Capability(CameraControl.ZOOM, Optional.of(ZOOM), false),
                new CameraControls.Capability(CameraControl.FOCUS, Optional.empty(), true)));
    }

    @Test
    @DisplayName("capabilities are listed in the order of the controls and looked up by control")
    void capabilities() {
        var controls = controls();
        assertEquals(List.of(CameraControl.POWER_LINE_FREQUENCY, CameraControl.FOCUS, CameraControl.ZOOM),
                controls.capabilities().stream().map(CameraControls.Capability::control).toList());
        assertEquals(Optional.of(ZOOM), controls.capability(CameraControl.ZOOM).flatMap(CameraControls.Capability::range));
        assertEquals(Optional.empty(), controls.capability(CameraControl.EXPOSURE));
        assertThrows(NullPointerException.class, () -> controls.capability(null));
    }

    @Test
    @DisplayName("requests the camera accepts reach the platform")
    void accepted() {
        var controls = controls();
        assertEquals(new CameraControls.State(OptionalLong.of(7), false), controls.get(CameraControl.ZOOM));
        controls.set(CameraControl.ZOOM, 250);
        controls.set(CameraControl.POWER_LINE_FREQUENCY, 60);
        controls.setAutomatic(CameraControl.FOCUS, true);
        controls.setAutomatic(CameraControl.POWER_LINE_FREQUENCY, false);
        assertEquals(List.of("read ZOOM", "write ZOOM 250", "write POWER_LINE_FREQUENCY 60", "auto FOCUS true",
                "auto POWER_LINE_FREQUENCY false"), controls.calls);
    }

    @Test
    @DisplayName("values outside the range or the discrete values, and missing modes, never reach the platform")
    void rejected() {
        var controls = controls();
        assertThrows(IllegalArgumentException.class, () -> controls.set(CameraControl.ZOOM, 99));
        assertThrows(IllegalArgumentException.class, () -> controls.set(CameraControl.ZOOM, 401));
        assertThrows(IllegalArgumentException.class, () -> controls.set(CameraControl.POWER_LINE_FREQUENCY, 55));
        assertThrows(MediaException.Unsupported.class, () -> controls.set(CameraControl.FOCUS, 3));
        assertThrows(MediaException.Unsupported.class, () -> controls.setAutomatic(CameraControl.ZOOM, true));
        assertThrows(MediaException.Unsupported.class, () -> controls.get(CameraControl.EXPOSURE));
        assertThrows(MediaException.Unsupported.class, () -> controls.set(CameraControl.EXPOSURE, 1000));
        assertThrows(NullPointerException.class, () -> controls.get(null));
        assertEquals(List.of(), controls.calls);
    }

    @Test
    @DisplayName("closing releases once and makes every request fail, while the capabilities stay readable")
    void closed() {
        var controls = controls();
        controls.close();
        controls.close();
        assertEquals(1, controls.released);
        assertThrows(IllegalStateException.class, () -> controls.get(CameraControl.ZOOM));
        assertThrows(IllegalStateException.class, () -> controls.set(CameraControl.ZOOM, 200));
        assertThrows(IllegalStateException.class, () -> controls.setAutomatic(CameraControl.FOCUS, false));
        assertEquals(3, controls.capabilities().size());
        assertTrue(controls.capability(CameraControl.FOCUS).isPresent());
        assertEquals(List.of(), controls.calls);
    }

    @Test
    @DisplayName("the empty controls offer nothing and reject every control")
    void none() {
        var controls = NativeCameraControls.none();
        assertEquals(List.of(), controls.capabilities());
        for (var control : CameraControl.values()) {
            assertFalse(controls.capability(control).isPresent());
            assertThrows(MediaException.Unsupported.class, () -> controls.get(control));
        }
    }

    @Test
    @DisplayName("two capabilities of one control are rejected")
    void duplicates() {
        var capability = new CameraControls.Capability(CameraControl.ZOOM, Optional.of(ZOOM), false);
        assertThrows(IllegalArgumentException.class, () -> new Recording(List.of(capability, capability)));
    }
}
