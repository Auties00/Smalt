package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.internal.CaptureBackend;
import com.github.auties00.smalt.track.Rational;

import java.util.List;
import java.util.Optional;

/**
 * Captures cameras with Video4Linux2 and screens with X11 on 64-bit Linux.
 *
 * <p>Cameras are the {@code /dev/videoN} capture nodes ({@link V4l2Cameras}), captured with
 * memory-mapped streaming I/O and converted to 4:2:0 from planar or semi-planar 4:2:0, packed
 * 4:2:2 or Motion-JPEG ({@link V4l2CameraSession}). Screens are the RandR monitors of the display
 * named by {@code DISPLAY} ({@link X11Screens}), grabbed through MIT-SHM or {@code XGetImage}
 * ({@link X11ScreenGrabber}) and paced to the requested rate ({@link PacedCaptureSession}).
 * Everything is called through the Foreign Function and Memory API: the C library from the
 * process's default lookup, {@code libX11.so.6}, {@code libXext.so.6} and {@code libXrandr.so.2}
 * by name; no library is bundled.
 *
 * <p>Not supported: multi-planar capture devices ({@code V4L2_CAP_VIDEO_CAPTURE_MPLANE} only),
 * the formats this backend does not convert (Bayer, H.264, RGB), and screen capture of a pure
 * Wayland session, which requires the {@code xdg-desktop-portal} screen cast portal and PipeWire.
 *
 * @implNote This implementation calls restricted methods of the Foreign Function and Memory API,
 * so the JVM warns once unless the module is granted native access
 * ({@code --enable-native-access=com.github.auties00.smalt}), and throws
 * {@link IllegalCallerException} when native access is denied.
 */
public final class LinuxCaptureBackend implements CaptureBackend {
    /**
     * Constructs the backend; the libraries are bound on first use.
     */
    public LinuxCaptureBackend() {
    }

    @Override
    public List<CaptureDevice.Camera> cameras() {
        return V4l2Cameras.list();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation queries the nodes in order up to the first camera.
     */
    @Override
    public Optional<CaptureDevice.Camera> defaultCamera() {
        return V4l2Cameras.first();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation queries only the node the identifier resolves to.
     */
    @Override
    public Optional<CaptureDevice.Camera> camera(String id) {
        return V4l2Cameras.find(id);
    }

    @Override
    public List<CaptureDevice.Screen> screens() {
        return X11Screens.list();
    }

    @Override
    public VideoCaptureSession open(CaptureDevice.Camera camera, CaptureMode mode) {
        return V4l2CameraSession.open(camera, mode);
    }

    /**
     * Opens a screen.
     *
     * @param frameRate the frames per second to capture at
     * @return the session
     * @throws MediaException.Io if the screen cannot be captured
     * @throws MediaException.Unsupported if X11 is not available, as in a pure Wayland session,
     * or the screen's pixel format is not supported
     */
    @Override
    public VideoCaptureSession open(CaptureDevice.Screen screen, Rational frameRate) {
        var display = System.getenv("DISPLAY");
        var x11 = X11.instance();
        if (display == null || display.isEmpty() || x11 == null) {
            throw new MediaException.Unsupported("screen capture", "screen capture needs an X11 display;"
                    + " Wayland screen capture through PipeWire is not implemented");
        }
        return new PacedCaptureSession(screen, X11ScreenGrabber.open(x11, screen), frameRate);
    }
}
