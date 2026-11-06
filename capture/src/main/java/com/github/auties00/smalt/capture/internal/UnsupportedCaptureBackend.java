package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CameraCaptureSession;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.ScreenCaptureOptions;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.VideoDeviceEvent;

import java.util.List;
import java.util.function.Consumer;

/**
 * Serves platforms without a capture backend: it lists no device and opens none.
 *
 * @param platform the name of the running operating system, reported in failures
 */
record UnsupportedCaptureBackend(String platform) implements CaptureBackend {
    /**
     * Returns no camera.
     *
     * @return an empty list
     */
    @Override
    public List<CaptureDevice.Camera> cameras() {
        return List.of();
    }

    /**
     * Returns no screen.
     *
     * @return an empty list
     */
    @Override
    public List<CaptureDevice.Screen> screens() {
        return List.of();
    }

    /**
     * Fails: capture is not available.
     *
     * @throws MediaException.Unsupported always
     */
    @Override
    public CameraCaptureSession open(CaptureDevice.Camera camera, CaptureMode mode) {
        throw unsupported();
    }

    /**
     * Fails: capture is not available.
     *
     * @throws MediaException.Unsupported always
     */
    @Override
    public VideoCaptureSession open(CaptureDevice.Screen screen, ScreenCaptureOptions options) {
        throw unsupported();
    }

    /**
     * Fails: capture is not available.
     *
     * @throws MediaException.Unsupported always
     */
    @Override
    public VideoCaptureSession open(CaptureDevice.Window window, ScreenCaptureOptions options) {
        throw unsupported();
    }

    /**
     * Returns a watch that reports nothing: no device ever appears.
     *
     * @return a watch whose close does nothing
     */
    @Override
    public AutoCloseable watch(Consumer<VideoDeviceEvent> sink) {
        return () -> {
        };
    }

    /**
     * Returns the failure raised by every open.
     *
     * @return the failure
     */
    private MediaException.Unsupported unsupported() {
        return new MediaException.Unsupported("capture", "video capture is not available on " + platform);
    }
}
