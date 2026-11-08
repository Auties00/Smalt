package com.github.auties00.smalt.capture;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.CaptureBackend;
import com.github.auties00.smalt.capture.internal.VideoEventHub;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Lists and opens the cameras, screens and windows of the running computer, and reports cameras and
 * screens as they come and go.
 *
 * <p>Video capture talks to the operating system's own APIs through the Foreign Function and Memory API
 * and loads no bundled native library: Media Foundation, DXGI desktop duplication and Windows Graphics
 * Capture on Windows, AVFoundation, ScreenCaptureKit and CoreGraphics on macOS, Video4Linux2, X11
 * (with the Composite and XFixes extensions) and, in a Wayland session, the {@code xdg-desktop-portal}
 * screen cast portal with PipeWire on Linux. On a platform without a backend the lists are
 * empty and opening a device raises {@link MediaException.Unsupported}. This class is thread-safe.
 *
 * <p>Listing the cameras asks every camera for its modes, which on Windows activates each camera's media
 * source; {@link #defaultCamera()} and {@link #camera(String)} ask only the camera they return.
 * Microphones and speakers are listed and opened the same way by
 * {@link com.github.auties00.smalt.capture.audio.AudioDevices AudioDevices}. A camera has a system
 * default, as an audio device does; a screen has none, and the first screen listed is the primary one
 * ({@link CaptureDevice.Screen#primary()}).
 */
public final class VideoDevices {
    private VideoDevices() {
        throw new AssertionError();
    }

    /**
     * Lists the cameras with their modes.
     *
     * @return the cameras, the system's default one first; empty when there is none
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    public static List<CaptureDevice.Camera> cameras() {
        return CaptureBackend.current().cameras();
    }

    /**
     * Lists the screens.
     *
     * @return the screens, the primary one first; empty when capture is unavailable
     * @throws MediaException.Io if the platform's display enumeration fails
     */
    public static List<CaptureDevice.Screen> screens() {
        return CaptureBackend.current().screens();
    }

    /**
     * Returns the system's default camera.
     *
     * @return the camera, or empty when there is none
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    public static Optional<CaptureDevice.Camera> defaultCamera() {
        return CaptureBackend.current().defaultCamera();
    }

    /**
     * Returns the primary screen.
     *
     * @return the screen, or empty when capture is unavailable
     * @throws MediaException.Io if the platform's display enumeration fails
     */
    public static Optional<CaptureDevice.Screen> primaryScreen() {
        return screens().stream().findFirst();
    }

    /**
     * Returns the camera with an identifier.
     *
     * @param id the identifier, as returned by {@link CaptureDevice#id()}
     * @return the camera, or empty when no camera has that identifier
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    public static Optional<CaptureDevice.Camera> camera(String id) {
        Objects.requireNonNull(id, "id");
        return CaptureBackend.current().camera(id);
    }

    /**
     * Returns the screen with an identifier.
     *
     * @param id the identifier, as returned by {@link CaptureDevice#id()}
     * @return the screen, or empty when no screen has that identifier
     * @throws MediaException.Io if the platform's display enumeration fails
     */
    public static Optional<CaptureDevice.Screen> screen(String id) {
        Objects.requireNonNull(id, "id");
        return screens().stream().filter(screen -> screen.id().equals(id)).findFirst();
    }

    /**
     * Lists the top-level windows of the applications that can be captured.
     *
     * <p>The list holds the visible windows that have a size, front to back where the platform knows
     * the order; it leaves out the desktop itself and minimized windows, and on Windows the windows that
     * have no title, are cloaked (on another virtual desktop), are tool windows or belong to another window
     * without asking for a taskbar button. On Linux it needs an X11 display; without one it is empty.
     *
     * @return the windows; empty when window capture is unavailable
     * @throws MediaException.Io if the platform's window enumeration fails
     */
    public static List<CaptureDevice.Window> windows() {
        return CaptureBackend.current().windows();
    }

    /**
     * Returns the window with an identifier.
     *
     * @param id the identifier, as returned by {@link CaptureDevice#id()}
     * @return the window as it is now, or empty when no window has that identifier
     * @throws MediaException.Io if the platform's window enumeration fails
     */
    public static Optional<CaptureDevice.Window> window(String id) {
        Objects.requireNonNull(id, "id");
        return CaptureBackend.current().window(id);
    }

    /**
     * Registers a listener of camera and screen changes.
     *
     * <p>The watch starts with the first listener and stops when the last one is removed; events report
     * only the changes after the watch started. Adding a listener twice delivers each event to it twice.
     *
     * @implNote This implementation reacts to the operating system's device notifications where it
     * subscribes to them (the device interface notifications of Windows, the {@code /dev} directory on
     * Linux) and lists the devices again at a fixed interval besides, which also catches the changes of
     * screen arrangement no notification reports.
     *
     * @param listener the listener, called on the dispatch thread
     * @throws MediaException.Io if the watch cannot be started
     */
    public static void addListener(VideoDeviceListener listener) {
        VideoEventHub.instance().add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Unregisters a listener of camera and screen changes; an unknown listener is ignored.
     *
     * @param listener the listener to remove
     */
    public static void removeListener(VideoDeviceListener listener) {
        VideoEventHub.instance().remove(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Opens a camera in its default mode.
     *
     * @param camera the camera to open
     * @return the session, which the caller closes
     * @throws MediaException.Io if the camera cannot be opened
     * @throws MediaException.Unsupported if capture is unavailable on this platform
     */
    public static CameraCaptureSession openCapture(CaptureDevice.Camera camera) {
        return CaptureBackend.current().open(Objects.requireNonNull(camera, "camera"), null);
    }

    /**
     * Opens a camera in a mode.
     *
     * @param camera the camera to open
     * @param mode the mode, normally one of {@link CaptureDevice.Camera#modes()}
     * @return the session, which the caller closes
     * @throws MediaException.Io if the camera cannot be opened
     * @throws MediaException.Unsupported if capture is unavailable on this platform or the camera cannot
     * capture in {@code mode}
     */
    public static CameraCaptureSession openCapture(CaptureDevice.Camera camera, CaptureMode mode) {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(mode, "mode");
        return CaptureBackend.current().open(camera, mode);
    }

    /**
     * Opens a screen at an integer frame rate, capturing the whole screen with the pointer drawn.
     *
     * @param screen the screen to capture
     * @param fps the frames per second to capture at, positive
     * @return the session, which the caller closes
     * @throws IllegalArgumentException if {@code fps} is not positive
     * @throws MediaException.Io if the screen cannot be captured
     * @throws MediaException.Unsupported if capture is unavailable on this platform
     */
    public static VideoCaptureSession openCapture(CaptureDevice.Screen screen, int fps) {
        Objects.requireNonNull(screen, "screen");
        Checks.positive("frame rate", fps);
        return CaptureBackend.current().open(screen, ScreenCaptureOptions.builder().frameRate(fps).build());
    }

    /**
     * Opens a screen at a fractional frame rate, such as {@code 30000/1001} for 29.97 frames per second,
     * capturing the whole screen with the pointer drawn.
     *
     * <p>An integer rate {@code n/1} captures exactly as {@link #openCapture(CaptureDevice.Screen, int)} with
     * {@code n}.
     *
     * @param screen the screen to capture
     * @param frameRate the frames per second to capture at
     * @return the session, which the caller closes
     * @throws MediaException.Io if the screen cannot be captured
     * @throws MediaException.Unsupported if capture is unavailable on this platform
     */
    public static VideoCaptureSession openCapture(CaptureDevice.Screen screen, Rational frameRate) {
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(frameRate, "frameRate");
        return CaptureBackend.current().open(screen, ScreenCaptureOptions.builder().frameRate(frameRate).build());
    }

    /**
     * Opens a screen with a set of options: a frame rate, a region and whether the pointer is drawn.
     *
     * <p>The pictures have the size of the captured area: the screen, or the part of the region that lies
     * on it, cropped to even dimensions.
     *
     * <p>In a Wayland session the screen cast portal decides what is captured: the desktop may ask the
     * user which monitor to share, and the session captures the monitor chosen there, at its size.
     *
     * @param screen the screen to capture
     * @param options the frame rate, region and pointer settings
     * @return the session, which the caller closes
     * @throws IllegalArgumentException if the region of {@code options} lies outside the screen
     * @throws MediaException.Io if the screen cannot be captured, or the user declines to share it
     * @throws MediaException.Unsupported if capture is unavailable on this platform, or the captured area
     * is smaller than 2x2 pixels
     */
    public static VideoCaptureSession openCapture(CaptureDevice.Screen screen, ScreenCaptureOptions options) {
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(options, "options");
        checkRegion(options, screen.width(), screen.height(), "the screen " + screen.name());
        return CaptureBackend.current().open(screen, options);
    }

    /**
     * Opens a window with a set of options: a frame rate, a region of the window and whether the pointer
     * is drawn.
     *
     * <p>The session captures the window's own image, also while other windows cover it where the platform
     * allows it (Windows Graphics Capture, ScreenCaptureKit, the X11 Composite extension); the platform
     * decides whether the frame and title bar are part of that image. The pictures keep the size of the
     * captured area when the session opened, cropped to even dimensions; a window resized later is
     * rescaled to it. When the window is closed, {@link VideoCaptureSession#read()} returns {@code null}.
     *
     * @param window the window to capture
     * @param options the frame rate, region and pointer settings
     * @return the session, which the caller closes
     * @throws IllegalArgumentException if the region of {@code options} lies outside the window's listed
     * size
     * @throws MediaException.Io if the window no longer exists or cannot be captured
     * @throws MediaException.Unsupported if window capture is unavailable on this platform, or the captured
     * area is smaller than 2x2 pixels
     */
    public static VideoCaptureSession openCapture(CaptureDevice.Window window, ScreenCaptureOptions options) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(options, "options");
        checkRegion(options, window.width(), window.height(), "the window " + window.name());
        return CaptureBackend.current().open(window, options);
    }

    /**
     * Checks that the region of a set of options overlaps a source of a size.
     *
     * @throws IllegalArgumentException if it does not
     */
    private static void checkRegion(ScreenCaptureOptions options, int width, int height, String what) {
        var region = options.region();
        if (region.isPresent() && region.get().clip(width, height).isEmpty()) {
            throw new IllegalArgumentException("the region " + region.get() + " lies outside " + what
                    + " (" + width + "x" + height + ")");
        }
    }
}
