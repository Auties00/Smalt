package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.track.Rational;

import java.util.List;

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
    public VideoCaptureSession open(CaptureDevice.Camera camera, CaptureMode mode) {
        throw unsupported();
    }

    /**
     * Fails: capture is not available.
     *
     * @throws MediaException.Unsupported always
     */
    @Override
    public VideoCaptureSession open(CaptureDevice.Screen screen, Rational frameRate) {
        throw unsupported();
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
