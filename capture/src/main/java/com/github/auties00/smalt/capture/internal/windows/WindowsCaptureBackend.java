package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CameraCaptureSession;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.ScreenCaptureOptions;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.VideoDeviceEvent;
import com.github.auties00.smalt.capture.internal.CaptureBackend;
import com.github.auties00.smalt.capture.internal.VideoDeviceWatch;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Implements capture on Windows 10 and later with the system's own libraries.
 *
 * <p>Cameras are Media Foundation video capture devices ({@code mfplat.dll}, {@code mf.dll},
 * {@code mfreadwrite.dll}), identified by their symbolic link and captured with a synchronous
 * source reader. Screens are the monitors of the desktop, identified by their GDI device name and
 * captured with DXGI desktop duplication ({@code dxgi.dll}, {@code d3d11.dll}) or, where that is
 * unavailable, with GDI ({@code user32.dll}, {@code gdi32.dll}). Windows are the top-level windows a user
 * can switch to ({@link TopWindows}), captured with Windows Graphics Capture ({@link WgcGrabber}) or, where
 * that is unavailable, with {@code PrintWindow} ({@link PrintWindowGrabber}). Camera controls go through the
 * media source's {@code IAMCameraControl} and {@code IAMVideoProcAmp} ({@link DirectShowControls}), and
 * device changes are reported by the configuration manager's notifications ({@link DeviceNotifications})
 * besides a listing every second. Every native call goes through
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

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation reads the symbolic links of the cameras without activating them.
     */
    @Override
    public List<String> cameraIds() {
        if (!MediaFoundation.available()) {
            return List.of();
        }
        return Apartment.call("smalt-camera-enumeration", () -> {
            MediaFoundation.startup();
            try {
                return CameraDevices.ids();
            } finally {
                MediaFoundation.shutdown();
            }
        });
    }

    @Override
    public List<CaptureDevice.Window> windows() {
        if (!TopWindows.available()) {
            return List.of();
        }
        return Apartment.call("smalt-window-enumeration", TopWindows::list);
    }

    @Override
    public Optional<CaptureDevice.Window> window(String id) {
        if (!TopWindows.available()) {
            return Optional.empty();
        }
        return Apartment.call("smalt-window-enumeration", () -> TopWindows.find(id));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation lists the devices again as soon as the configuration manager reports a
     * camera or monitor interface arriving or leaving, the screens every second and the cameras every five
     * seconds besides.
     */
    @Override
    public AutoCloseable watch(Consumer<VideoDeviceEvent> sink) {
        var watch = new VideoDeviceWatch(this::cameraIds, this::screens, sink, Duration.ofSeconds(1), 5);
        var notifications = DeviceNotifications.start(watch::poke);
        return () -> {
            if (notifications != null) {
                notifications.close();
            }
            watch.close();
        };
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
    public CameraCaptureSession open(CaptureDevice.Camera camera, CaptureMode mode) {
        return CameraSession.open(camera, mode);
    }

    @Override
    public VideoCaptureSession open(CaptureDevice.Screen screen, ScreenCaptureOptions options) {
        return ScreenSession.open(screen, options);
    }

    @Override
    public VideoCaptureSession open(CaptureDevice.Window window, ScreenCaptureOptions options) {
        if (!TopWindows.available() || !Gdi.available()) {
            throw new MediaException.Unsupported("window capture", "the window functions of user32.dll are not available");
        }
        return ScreenSession.open(window, options, false);
    }
}
