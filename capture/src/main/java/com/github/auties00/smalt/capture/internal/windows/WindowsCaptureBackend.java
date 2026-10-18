package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.internal.CaptureBackend;
import com.github.auties00.smalt.track.Rational;

import java.util.List;
import java.util.Optional;

/**
 * Implements capture on Windows 10 and later with the system's own libraries.
 *
 * <p>Cameras are Media Foundation video capture devices ({@code mfplat.dll}, {@code mf.dll},
 * {@code mfreadwrite.dll}), identified by their symbolic link and captured with a synchronous
 * source reader. Screens are the monitors of the desktop, identified by their GDI device name and
 * captured with DXGI desktop duplication ({@code dxgi.dll}, {@code d3d11.dll}) or, where that is
 * unavailable, with GDI ({@code user32.dll}, {@code gdi32.dll}). Every native call goes through
 * the Foreign Function and Memory API; COM interfaces are called through their virtual function
 * tables. Enumeration runs on a short-lived platform thread in the COM multithreaded apartment,
 * and every session captures on its own platform thread.
 */
public final class WindowsCaptureBackend implements CaptureBackend {
    /**
     * Creates the backend.
     */
    public WindowsCaptureBackend() {
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation returns an empty list when Media Foundation is not installed
     * (the N editions of Windows without the Media Feature Pack).
     */
    @Override
    public List<CaptureDevice.Camera> cameras() {
        if (!MediaFoundation.available()) {
            return List.of();
        }
        return Apartment.call("smalt-camera-enumeration", () -> {
            MediaFoundation.startup();
            try {
                return CameraDevices.list();
            } finally {
                MediaFoundation.shutdown();
            }
        });
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation reads the modes of the first camera only.
     */
    @Override
    public Optional<CaptureDevice.Camera> defaultCamera() {
        return findCamera(null);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation reads the modes of the matching camera only.
     */
    @Override
    public Optional<CaptureDevice.Camera> camera(String id) {
        return findCamera(id);
    }

    /**
     * Finds a camera by its symbolic link, or the first one when {@code id} is {@code null}.
     */
    private static Optional<CaptureDevice.Camera> findCamera(String id) {
        if (!MediaFoundation.available()) {
            return Optional.empty();
        }
        return Apartment.call("smalt-camera-enumeration", () -> {
            MediaFoundation.startup();
            try {
                return CameraDevices.find(id);
            } finally {
                MediaFoundation.shutdown();
            }
        });
    }

    @Override
    public List<CaptureDevice.Screen> screens() {
        if (!Dxgi.available() && !Gdi.available()) {
            return List.of();
        }
        return Apartment.call("smalt-screen-enumeration", Displays::list);
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Unsupported if Media Foundation is not installed, or the camera
     * offers no media type of the mode's size and frame rate
     * in a pixel format this backend converts
     */
    @Override
    public VideoCaptureSession open(CaptureDevice.Camera camera, CaptureMode mode) {
        return CameraSession.open(camera, mode);
    }

    @Override
    public VideoCaptureSession open(CaptureDevice.Screen screen, Rational frameRate) {
        return ScreenSession.open(screen, frameRate);
    }
}
