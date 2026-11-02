package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CameraCaptureSession;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.ScreenCaptureOptions;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.VideoDeviceEvent;
import com.github.auties00.smalt.capture.internal.CaptureBackend;
import com.github.auties00.smalt.capture.internal.FramePacing;
import com.github.auties00.smalt.capture.internal.VideoDeviceWatch;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Captures cameras with Video4Linux2 and screens and windows with X11 on 64-bit Linux.
 *
 * <p>Cameras are the {@code /dev/videoN} capture nodes ({@link V4l2Cameras}), captured with
 * memory-mapped streaming I/O and converted to 4:2:0 from planar or semi-planar 4:2:0, packed
 * 4:2:2 or Motion-JPEG ({@link V4l2CameraSession}), with their controls read and written through
 * {@link V4l2Controls}. Screens are the RandR monitors of the display named by {@code DISPLAY}
 * ({@link X11Screens}), grabbed through MIT-SHM or {@code XGetImage} ({@link X11ScreenGrabber}); windows
 * are listed by {@link X11Windows} and grabbed through the Composite extension ({@link X11WindowGrabber});
 * both are paced to the requested rate ({@link PacedCaptureSession}) with the pointer drawn through
 * XFixes. Device changes are watched through the {@code /dev} directory ({@link DevWatch}) and a listing
 * every second. Everything is called through the Foreign Function and Memory API: the C library from the
 * process's default lookup, {@code libX11.so.6}, {@code libXext.so.6}, {@code libXrandr.so.2},
 * {@code libXcomposite.so.1} and {@code libXfixes.so.3} by name; no library is bundled.
 *
 * <p>Not supported: multi-planar capture devices ({@code V4L2_CAP_VIDEO_CAPTURE_MPLANE} only),
 * the formats this backend does not convert (Bayer, H.264, RGB), and screen and window capture of a pure
 * Wayland session, which requires the {@code xdg-desktop-portal} screen cast portal and PipeWire.
 *
 * @implNote This implementation calls restricted methods of the Foreign Function and Memory API,
 * so the JVM warns once unless the module is granted native access
 * ({@code --enable-native-access=com.github.auties00.smalt}), and throws
 * {@link IllegalCallerException} when native access is denied.
 */
public final class LinuxCaptureBackend implements CaptureBackend {
    /**
     * The number of screen listings between two camera listings without a change of {@code /dev}.
     */
    private static final int CAMERA_EVERY = 10;

    /**
     * The highest frame rate a screen or window session paces itself at.
     */
    private static final int MAX_RATE = 1_000;

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
    public List<String> cameraIds() {
        return V4l2Cameras.ids();
    }

    @Override
    public List<CaptureDevice.Screen> screens() {
        return X11Screens.list();
    }

    @Override
    public List<CaptureDevice.Window> windows() {
        return X11Windows.list();
    }

    @Override
    public Optional<CaptureDevice.Window> window(String id) {
        return X11Windows.find(id);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation lists the cameras again as soon as a {@code video} node appears in or
     * disappears from {@code /dev}, and every ten seconds besides.
     */
    @Override
    public AutoCloseable watch(Consumer<VideoDeviceEvent> sink) {
        var watch = new VideoDeviceWatch(this::cameraIds, this::screens, sink, Duration.ofSeconds(1), CAMERA_EVERY);
        var dev = DevWatch.start(watch::poke);
        return () -> {
            if (dev != null) {
                dev.close();
            }
            watch.close();
        };
    }

    @Override
    public CameraCaptureSession open(CaptureDevice.Camera camera, CaptureMode mode) {
        return V4l2CameraSession.open(camera, mode);
    }

    @Override
    public VideoCaptureSession open(CaptureDevice.Screen screen, ScreenCaptureOptions options) {
        var x11 = requireDisplay("screen capture");
        return new PacedCaptureSession(screen, X11ScreenGrabber.open(x11, screen, options),
                FramePacing.atMost(options.frameRate(), MAX_RATE));
    }

    @Override
    public VideoCaptureSession open(CaptureDevice.Window window, ScreenCaptureOptions options) {
        requireDisplay("window capture");
        var windowing = X11Windowing.instance();
        if (windowing == null) {
            throw new MediaException.Unsupported("window capture", "window capture needs libX11");
        }
        return new PacedCaptureSession(window, X11WindowGrabber.open(windowing, window, options),
                FramePacing.atMost(options.frameRate(), MAX_RATE));
    }

    /**
     * Returns the Xlib bindings when an X11 display is configured.
     *
     * @param what the capture, named in the failure
     * @throws MediaException.Unsupported without {@code DISPLAY} or {@code libX11.so.6}
     */
    private static X11 requireDisplay(String what) {
        var display = System.getenv("DISPLAY");
        var x11 = X11.instance();
        if (display == null || display.isEmpty() || x11 == null) {
            throw new MediaException.Unsupported(what, what + " needs an X11 display;"
                    + " Wayland capture through PipeWire is not implemented");
        }
        return x11;
    }
}
