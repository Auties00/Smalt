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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Captures cameras with Video4Linux2, screens with the Wayland screen cast portal or X11, and windows
 * with X11 on 64-bit Linux.
 *
 * <p>Cameras are the {@code /dev/videoN} single-planar and multi-planar capture nodes
 * ({@link V4l2Cameras}), captured with memory-mapped streaming I/O in any field order and converted to
 * 4:2:0 from planar, semi-planar and packed Y'CbCr, R'G'B', luma-only or Motion-JPEG frames
 * ({@link V4l2CameraSession}, {@link V4l2FrameLayout}, {@link FrameConverter}), with their controls
 * read and written through {@link V4l2Controls}. In a Wayland session ({@code WAYLAND_DISPLAY} set)
 * screens are the compositor's outputs ({@link WaylandOutputs}), captured through the
 * {@code xdg-desktop-portal} screen cast portal and PipeWire ({@link PortalScreenSession}); otherwise
 * they are the RandR monitors of the display named by {@code DISPLAY} ({@link X11Screens}), grabbed
 * through MIT-SHM or {@code XGetImage} ({@link X11ScreenGrabber}) from TrueColor visuals of 16, 24 or 32
 * bits per pixel. Windows are listed by {@link X11Windows} and grabbed through the Composite extension
 * ({@link X11WindowGrabber}); X11 screens and windows are paced to the requested rate
 * ({@link PacedCaptureSession}) with the pointer drawn through XFixes. Device changes are watched through
 * the {@code /dev} directory ({@link DevWatch}) and a listing every second. Everything is called through
 * the Foreign Function and Memory API: the C library from the process's default lookup,
 * {@code libX11.so.6}, {@code libXext.so.6}, {@code libXrandr.so.2}, {@code libXcomposite.so.1},
 * {@code libXfixes.so.3} and {@code libpipewire-0.3.so.0} by name; the D-Bus and Wayland protocols are
 * spoken directly over their sockets. No library is bundled.
 *
 * <p>Not supported: the V4L2 formats this backend does not convert (Bayer, H.264 and other compressed
 * formats besides JPEG), and window capture of Wayland clients, which the portal offers only through
 * the desktop's own picker; X11 clients of a Wayland session (XWayland) are captured as X11 windows.
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

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation lists the outputs of the Wayland compositor in a Wayland
     * session ({@code WAYLAND_DISPLAY} set), else the RandR monitors of the X11 display.
     */
    @Override
    public List<CaptureDevice.Screen> screens() {
        return screens(WaylandOutputs.socket());
    }

    /**
     * Lists the screens of a Wayland compositor, or of the X11 display when there is none.
     *
     * @param wayland the compositor's socket, or {@code null} outside a Wayland session
     * @return the compositor's outputs when it answers with at least one, else the RandR monitors
     */
    List<CaptureDevice.Screen> screens(Path wayland) {
        if (wayland != null) {
            var outputs = WaylandOutputs.list(wayland);
            if (!outputs.isEmpty()) {
                return outputs;
            }
        }
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

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation captures a Wayland output through the screen cast portal,
     * where the desktop may ask the user which monitor to share, and an X11 monitor through
     * MIT-SHM or {@code XGetImage}.
     */
    @Override
    public VideoCaptureSession open(CaptureDevice.Screen screen, ScreenCaptureOptions options) {
        if (screen.id().startsWith(WaylandOutputs.PREFIX)) {
            return PortalScreenSession.open(screen, ScreenCastPortal.SOURCE_MONITOR, options);
        }
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
            throw new MediaException.Unsupported(what, what + " needs an X11 display");
        }
        return x11;
    }
}
