package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CameraCaptureSession;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.ScreenCaptureOptions;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.internal.CaptureBackend;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Implements capture on macOS 12 and later, on Apple silicon and Intel, with the system
 * frameworks only.
 *
 * <p>Cameras are enumerated and captured with AVFoundation ({@link MacCameras},
 * {@link MacCameraSession}), and their focus, exposure, white balance and zoom set through
 * {@link AvFoundationControls}; screens are enumerated with CoreGraphics ({@link MacDisplays}) and
 * captured with ScreenCaptureKit ({@link MacStreamSession}) from macOS 12.3, or with
 * {@code CGDisplayCreateImage} snapshots ({@link MacSnapshotSession}) before; windows are listed
 * ({@link MacWindows}) and captured with ScreenCaptureKit only. Device changes are found by listing the
 * devices again at a fixed interval. Every framework is
 * reached through the Foreign Function and Memory API: plain C functions directly, Objective-C
 * through {@code objc_msgSend}, delegates through a class built at run time
 * ({@link ObjcBridge}) and completion handlers through hand-built block literals
 * ({@link BlockLayout}). Nothing is loaded until the first call, and all Objective-C work runs on
 * platform threads inside autorelease pools ({@link PlatformThreads}).
 *
 * <p>The process needs the Camera and Screen Recording permissions, which macOS grants to the
 * application that launched the JVM (the terminal, the IDE, or a bundled application whose
 * {@code Info.plist} carries {@code NSCameraUsageDescription}), and native access enabled for
 * this module ({@code --enable-native-access}).
 */
public final class MacCaptureBackend implements CaptureBackend {
    /**
     * Guards the lazy creation of {@link #bridge}.
     */
    private final ReentrantLock lock;

    /**
     * The Objective-C bridge, created on first use.
     */
    private ObjcBridge bridge;

    /**
     * Constructs the backend; the frameworks are bound on first use.
     */
    public MacCaptureBackend() {
        this.lock = new ReentrantLock();
    }

    @Override
    public List<CaptureDevice.Camera> cameras() {
        return guard("cannot list the cameras", () -> PlatformThreads.call(MacCameras::list));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation reads the formats of the returned camera only.
     */
    @Override
    public Optional<CaptureDevice.Camera> defaultCamera() {
        return guard("cannot list the cameras", () -> PlatformThreads.call(MacCameras::first));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation reads the formats of the returned camera only.
     */
    @Override
    public Optional<CaptureDevice.Camera> camera(String id) {
        return guard("cannot list the cameras", () -> PlatformThreads.call(() -> MacCameras.find(id)));
    }

    @Override
    public List<CaptureDevice.Screen> screens() {
        return guard("cannot list the screens", () -> PlatformThreads.call(MacDisplays::list));
    }

    @Override
    public CameraCaptureSession open(CaptureDevice.Camera camera, CaptureMode mode) {
        return guard("cannot open the camera " + camera.name(), () -> {
            var objc = bridge();
            return PlatformThreads.call(() -> MacCameraSession.open(objc, camera, mode));
        });
    }

    @Override
    public VideoCaptureSession open(CaptureDevice.Screen screen, ScreenCaptureOptions options) {
        return guard("cannot open the screen " + screen.name(), () -> {
            if (!isDisplayId(screen)) {
                throw new MediaException.Io("the screen " + screen.name() + " is not a macOS display",
                        new IOException("invalid display id " + screen.id()));
            }
            if (ScreenCaptureKit.AVAILABLE) {
                var objc = bridge();
                return PlatformThreads.call(() -> MacStreamSession.open(objc, screen, options));
            }
            if (CoreGraphics.canSnapshot()) {
                return PlatformThreads.call(() -> MacSnapshotSession.open(screen, options));
            }
            throw new MediaException.Unsupported("screen capture",
                    "this macOS version offers neither ScreenCaptureKit nor CGDisplayCreateImage");
        });
    }

    @Override
    public List<CaptureDevice.Window> windows() {
        if (!ScreenCaptureKit.AVAILABLE) {
            return List.of();
        }
        return guard("cannot list the windows", () -> {
            var objc = bridge();
            return PlatformThreads.call(() -> MacWindows.list(objc));
        });
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Unsupported if ScreenCaptureKit is missing (macOS before 12.3)
     */
    @Override
    public VideoCaptureSession open(CaptureDevice.Window window, ScreenCaptureOptions options) {
        if (!ScreenCaptureKit.AVAILABLE) {
            throw new MediaException.Unsupported("window capture", "window capture needs ScreenCaptureKit (macOS 12.3 or later)");
        }
        return guard("cannot open the window " + window.name(), () -> {
            var objc = bridge();
            return PlatformThreads.call(() -> MacStreamSession.open(objc, window, options));
        });
    }

    /**
     * Returns whether a screen's id is a display identifier.
     *
     * @return whether the id parses as an unsigned 32-bit number
     */
    private static boolean isDisplayId(CaptureDevice.Screen screen) {
        try {
            MacDisplays.displayId(screen);
            return true;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    /**
     * Returns the bridge, creating it on first use.
     *
     * @return the bridge
     */
    private ObjcBridge bridge() {
        lock.lock();
        try {
            if (bridge == null) {
                bridge = PlatformThreads.call(ObjcBridge::new);
            }
            return bridge;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs a backend operation, turning a failure to reach the system frameworks into
     * {@link MediaException.Io} and a picture too large for the limits into
     * {@link MediaException.Unsupported}.
     *
     * @param message what failed, in words
     * @param body the operation
     * @param <T> the type of the result
     * @return the result
     * @throws MediaException the failures of the operation itself
     */
    private static <T> T guard(String message, Supplier<T> body) {
        try {
            return body.get();
        } catch (MediaException.LimitExceeded tooLarge) {
            throw new MediaException.Unsupported("capture size", message + ": " + tooLarge.getMessage());
        } catch (LinkageError | IllegalCallerException | IllegalStateException failure) {
            var cause = failure.getCause() != null ? failure.getCause() : failure;
            throw new MediaException.Io(message + ": " + cause, new IOException(failure));
        }
    }
}
