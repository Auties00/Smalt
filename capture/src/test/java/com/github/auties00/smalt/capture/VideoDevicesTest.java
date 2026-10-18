package com.github.auties00.smalt.capture;

import com.github.auties00.smalt.capture.audio.AudioDevices;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the argument checks of the capture facades, which run before any operating system call.
 */
@DisplayName("capture facades")
class VideoDevicesTest {
    private static final CaptureDevice.Screen SCREEN = new CaptureDevice.Screen("screen", "Screen", 0, 0, 64, 32, true);

    private static final CaptureDevice.Camera CAMERA = new CaptureDevice.Camera("camera", "Camera", List.of());

    @Test
    @DisplayName("the lookups by id reject a null id")
    void lookups() {
        assertThrows(NullPointerException.class, () -> VideoDevices.camera(null));
        assertThrows(NullPointerException.class, () -> VideoDevices.screen(null));
    }

    @Test
    @DisplayName("the openCapture verbs reject null arguments and a non-positive rate")
    void openCapture() {
        assertThrows(NullPointerException.class, () -> VideoDevices.openCapture((CaptureDevice.Camera) null));
        assertThrows(NullPointerException.class, () -> VideoDevices.openCapture(null, CaptureMode.of(640, 480, 30)));
        assertThrows(NullPointerException.class, () -> VideoDevices.openCapture(CAMERA, null));
        assertThrows(NullPointerException.class, () -> VideoDevices.openCapture(null, 30));
        assertThrows(IllegalArgumentException.class, () -> VideoDevices.openCapture(SCREEN, 0));
        assertThrows(NullPointerException.class, () -> VideoDevices.openCapture(null, new Rational(30, 1)));
        assertThrows(NullPointerException.class, () -> VideoDevices.openCapture(SCREEN, (Rational) null));
    }

    @Test
    @DisplayName("the default-options loopback rejects a null device")
    void loopback() {
        assertThrows(NullPointerException.class, () -> AudioDevices.openLoopback(null));
    }
}
