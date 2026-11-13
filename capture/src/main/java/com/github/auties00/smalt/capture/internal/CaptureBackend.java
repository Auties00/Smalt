package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CameraCaptureSession;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.ScreenCaptureOptions;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.VideoDeviceEvent;
import com.github.auties00.smalt.capture.internal.linux.LinuxCaptureBackend;
import com.github.auties00.smalt.capture.internal.macos.MacCaptureBackend;
import com.github.auties00.smalt.capture.internal.windows.WindowsCaptureBackend;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

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
     * Lists the identifiers of the cameras without reading their modes.
     *
     * @implSpec The default implementation maps {@link #cameras()}.
     *
     * @return the identifiers, the default camera's first
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    default List<String> cameraIds() {
        return cameras().stream().map(CaptureDevice.Camera::id).toList();
    }

    /**
     * Lists the screens.
     *
     * @return the screens, the primary one first
     * @throws MediaException.Io if the platform's display enumeration fails
     */
    List<CaptureDevice.Screen> screens();

    /**
     * Lists the top-level windows that can be captured.
     *
     * @implSpec The default implementation returns an empty list.
     *
     * @return the windows, front to back where the platform knows the order
     * @throws MediaException.Io if the platform's window enumeration fails
     */
    default List<CaptureDevice.Window> windows() {
        return List.of();
    }

    /**
     * Returns the window with an identifier.
     *
     * @implSpec The default implementation searches {@link #windows()}.
     *
     * @param id the identifier, as {@link CaptureDevice#id()} returns it
     * @return the window, or empty when no window has that identifier
     * @throws MediaException.Io if the platform's window enumeration fails
     */
    default Optional<CaptureDevice.Window> window(String id) {
        return windows().stream().filter(window -> window.id().equals(id)).findFirst();
    }

    /**
     * Starts watching the cameras and screens for changes.
     *
     * @implSpec The default implementation polls: it lists the screens every second and the cameras
     * every five seconds.
     *
     * @param sink receives the events, on a thread of the watch
     * @return what stops the watch
     * @throws MediaException.Io if the watch cannot be started
     */
    default AutoCloseable watch(Consumer<VideoDeviceEvent> sink) {
        return new VideoDeviceWatch(this::cameraIds, this::screens, sink, Duration.ofSeconds(1), 5);
    }

    /**
     * Opens a camera.
     *
     * @param mode the requested mode, or {@code null} for the camera's default
     * @return the session
     * @throws MediaException.Io if the camera cannot be opened
     * @throws MediaException.Unsupported if the camera cannot capture in {@code mode}
     */
    CameraCaptureSession open(CaptureDevice.Camera camera, CaptureMode mode);

    /**
     * Opens a screen.
     *
     * @param options the frame rate, region and pointer settings; a region was checked to overlap the
     * screen's listed size
     * @return the session
     * @throws MediaException.Io if the screen cannot be captured
     * @throws MediaException.Unsupported if screen capture is unavailable, as in a pure Wayland session
     */
    VideoCaptureSession open(CaptureDevice.Screen screen, ScreenCaptureOptions options);

    /**
     * Opens a window.
     *
     * @implSpec The default implementation throws {@link MediaException.Unsupported}.
     *
     * @param options the frame rate, region and pointer settings; a region was checked to overlap the
     * window's listed size
     * @return the session
     * @throws MediaException.Io if the window no longer exists or cannot be captured
     * @throws MediaException.Unsupported if window capture is unavailable
     */
    default VideoCaptureSession open(CaptureDevice.Window window, ScreenCaptureOptions options) {
        throw new MediaException.Unsupported("window capture", "window capture is not available on " + Platform.osName());
    }

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
