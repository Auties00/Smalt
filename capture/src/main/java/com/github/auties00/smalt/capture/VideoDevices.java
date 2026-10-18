package com.github.auties00.smalt.capture;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.CaptureBackend;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Lists and opens the cameras and screens of the running computer.
 *
 * <p>Video capture talks to the operating system's own APIs through the Foreign Function and Memory API
 * and loads no bundled native library: Media Foundation and the DXGI desktop duplication on
 * Windows, AVFoundation and CoreGraphics on macOS, Video4Linux2 and X11 on Linux. On a platform
 * without a backend the lists are empty and opening a device raises
 * {@link MediaException.Unsupported}. This class is thread-safe.
 *
 * <p>Listing the cameras asks every camera for its modes, which on Windows activates each camera's media
 * source; {@link #defaultCamera()} and {@link #camera(String)} ask only the camera they return.
 * Microphones and speakers are listed and opened the same way by
 * {@link com.github.auties00.smalt.capture.audio.AudioDevices AudioDevices}. A camera has a system
 * default, as an audio device does; a screen has none, and the first screen listed is the primary one
 * ({@link CaptureDevice.Screen#primary()}).
 */
// TODO: a listener for cameras and screens added or removed, like AudioDevices.addListener.
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
     * Opens a camera in its default mode.
     *
     * @param camera the camera to open
     * @return the session, which the caller closes
     * @throws MediaException.Io if the camera cannot be opened
     * @throws MediaException.Unsupported if capture is unavailable on this platform
     */
    public static VideoCaptureSession openCapture(CaptureDevice.Camera camera) {
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
    public static VideoCaptureSession openCapture(CaptureDevice.Camera camera, CaptureMode mode) {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(mode, "mode");
        return CaptureBackend.current().open(camera, mode);
    }

    /**
     * Opens a screen at an integer frame rate.
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
        return CaptureBackend.current().open(screen, new Rational(fps, 1));
    }

    /**
     * Opens a screen at a fractional frame rate, such as {@code 30000/1001} for 29.97 frames per second.
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
        return CaptureBackend.current().open(screen, frameRate);
    }
}
