package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.internal.linux.LinuxCaptureBackend;
import com.github.auties00.smalt.capture.internal.macos.MacCaptureBackend;
import com.github.auties00.smalt.capture.internal.windows.WindowsCaptureBackend;
import com.github.auties00.smalt.track.Rational;

import java.util.List;
import java.util.Optional;

/**
 * Implements camera and screen capture for one operating system through its native APIs, called
 * with the Foreign Function and Memory API.
 *
 * <p>Implementations must not load any library that is not part of the operating system, must
 * return empty lists rather than fail when the platform has no device of a kind, and must raise
 * {@link MediaException.Io} when a device cannot be opened or fails, and
 * {@link MediaException.Unsupported} when a requested mode cannot be honored. The arguments are
 * checked by the public API before they reach a backend.
 */
public interface CaptureBackend {
    /**
     * Lists the cameras with their modes.
     *
     * @return the cameras, the default one first
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    List<CaptureDevice.Camera> cameras();

    /**
     * Returns the system's default camera.
     *
     * @implSpec The default implementation returns the first of {@link #cameras()}.
     *
     * @return the camera, or empty when there is none
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    default Optional<CaptureDevice.Camera> defaultCamera() {
        return cameras().stream().findFirst();
    }

    /**
     * Returns the camera with an identifier.
     *
     * @implSpec The default implementation searches {@link #cameras()}.
     *
     * @param id the identifier, as {@link CaptureDevice#id()} returns it
     * @return the camera, or empty when no camera has that identifier
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    default Optional<CaptureDevice.Camera> camera(String id) {
        return cameras().stream().filter(camera -> camera.id().equals(id)).findFirst();
    }

    /**
     * Lists the screens.
     *
     * @return the screens, the primary one first
     * @throws MediaException.Io if the platform's display enumeration fails
     */
    List<CaptureDevice.Screen> screens();

    /**
     * Opens a camera.
     *
     * @param mode the requested mode, or {@code null} for the camera's default
     * @return the session
     * @throws MediaException.Io if the camera cannot be opened
     * @throws MediaException.Unsupported if the camera cannot capture in {@code mode}
     */
    VideoCaptureSession open(CaptureDevice.Camera camera, CaptureMode mode);

    /**
     * Opens a screen.
     *
     * @param frameRate the frames per second to capture at
     * @return the session
     * @throws MediaException.Io if the screen cannot be captured
     * @throws MediaException.Unsupported if screen capture is unavailable, as in a pure Wayland session
     */
    VideoCaptureSession open(CaptureDevice.Screen screen, Rational frameRate);

    /**
     * Returns the backend of the running operating system, created on first use.
     *
     * @return the backend; one that finds no device and opens none on an unsupported platform
     */
    static CaptureBackend current() {
        return Holder.INSTANCE;
    }

    /**
     * Holds the backend of the running operating system.
     */
    final class Holder {
        private static final CaptureBackend INSTANCE = switch (Platform.current()) {
            case WINDOWS -> new WindowsCaptureBackend();
            case MACOS -> new MacCaptureBackend();
            case LINUX -> new LinuxCaptureBackend();
            case UNSUPPORTED -> new UnsupportedCaptureBackend(Platform.osName());
        };

        private Holder() {
            throw new AssertionError();
        }
    }
}
