package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.internal.FramePacing;
import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.track.Rational;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.locks.LockSupport;

/**
 * Captures a display by taking a snapshot every frame interval, for macOS versions without
 * ScreenCaptureKit (before 12.3).
 *
 * <p>A platform thread wakes once per frame interval, takes a {@code CGDisplayCreateImage}
 * snapshot, draws it scaled into an sRGB BGRX bitmap context of the session size (which also
 * normalizes the pixel format and color space of whatever the snapshot holds), and converts the
 * bitmap to limited-range BT.709 4:2:0. When a snapshot takes longer than an interval the missed
 * ticks are skipped rather than queued. A snapshot that fails (the display disconnected, or the
 * Screen Recording permission was revoked) fails the session.
 */
final class MacSnapshotSession extends MacCaptureSession {
    /**
     * The name of the capture thread.
     */
    private static final String THREAD_NAME = "smalt-capture-macos-snapshot";

    /**
     * The display to capture.
     */
    private final int displayId;

    /**
     * The frame interval in nanoseconds.
     */
    private final long intervalNanos;

    /**
     * The converter from the bitmap to the pictures.
     */
    private final FrameConverter converter;

    /**
     * The arena owning the bitmap.
     */
    private final Arena arena;

    /**
     * The BGRX bitmap the snapshots are drawn into.
     */
    private final MemorySegment bitmap;

    /**
     * The bitmap's row stride in bytes.
     */
    private final long stride;

    /**
     * Cleared to stop the capture thread.
     */
    private volatile boolean running;

    /**
     * The owned sRGB color space, or {@code NULL}.
     */
    private MemorySegment colorSpace;

    /**
     * The owned bitmap context, or {@code NULL}.
     */
    private MemorySegment context;

    /**
     * The capture thread, or {@code null} before it starts.
     */
    private Thread thread;

    /**
     * Creates a session.
     *
     * @param displayId the display
     * @param width the picture width, even
     * @param height the picture height, even
     * @param frameRate the frames per second
     */
    private MacSnapshotSession(CaptureDevice.Screen screen, int displayId, int width, int height, Rational frameRate) {
        super(screen, width, height, 0);
        this.displayId = displayId;
        this.intervalNanos = FramePacing.periodNanos(frameRate);
        this.converter = new FrameConverter();
        this.arena = Arena.ofShared();
        this.stride = 4L * width;
        this.bitmap = arena.allocate(stride * height, 64);
        this.colorSpace = MemorySegment.NULL;
        this.context = MemorySegment.NULL;
    }

    /**
     * Opens a screen.
     *
     * <p>Runs on a platform thread inside an autorelease pool.
     *
     * @param frameRate the frames per second
     * @return the running session
     * @throws MediaException.Io if the permission is missing or the drawing context cannot
     * be created
     * @throws MediaException.Unsupported if the screen is smaller than 2x2 pixels
     */
    static MacSnapshotSession open(CaptureDevice.Screen screen, Rational frameRate) {
        MacDisplays.requireCapturePermission();
        var displayId = MacDisplays.displayId(screen);
        var width = screen.width() & ~1;
        var height = screen.height() & ~1;
        if (width == 0 || height == 0) {
            throw new MediaException.Unsupported("capture size", "the screen " + screen.name() + " is smaller than 2x2 pixels");
        }
        var session = new MacSnapshotSession(screen, displayId, width, height, frameRate);
        try {
            session.start();
            return session;
        } catch (Throwable failure) {
            session.close();
            throw failure;
        }
    }

    /**
     * Creates the drawing context and starts the capture thread.
     *
     * @throws MediaException.Io if the context cannot be created
     */
    private void start() {
        colorSpace = CoreGraphics.createSrgb();
        context = ObjC.isNull(colorSpace) ? MemorySegment.NULL
                : CoreGraphics.createBitmapContext(bitmap, width(), height(), stride, colorSpace);
        if (ObjC.isNull(context)) {
            throw new MediaException.Io("cannot create the screen capture bitmap", new IOException("CGBitmapContextCreate failed"));
        }
        running = true;
        thread = Thread.ofPlatform().name(THREAD_NAME).daemon(true).start(this::capture);
    }

    /**
     * Runs the capture loop until the session is released or a snapshot fails.
     */
    private void capture() {
        var next = System.nanoTime();
        while (running) {
            try {
                if (!snapshot()) {
                    exchange.fail(new MediaException.Io("cannot capture the screen " + device().name()
                            + "; it was disconnected or the Screen Recording permission was revoked",
                            new IOException("CGDisplayCreateImage returned NULL")));
                    return;
                }
                var timestamp = exchange.micros(System.nanoTime());
                var target = exchange.acquire();
                converter.bgra(bitmap, stride, width(), height(), target, YuvCoefficients.BT709_LIMITED);
                exchange.publish(target, timestamp);
            } catch (Throwable failure) {
                exchange.fail(failure instanceof MediaException.Io io ? io
                        : new MediaException.Io("screen capture failed", new IOException(String.valueOf(failure), failure)));
                return;
            }
            next += intervalNanos;
            var now = System.nanoTime();
            if (next - now < 0) {
                next = now;
            }
            while (running && next - System.nanoTime() > 0) {
                LockSupport.parkNanos(this, next - System.nanoTime());
            }
        }
    }

    /**
     * Takes a snapshot and draws it into the bitmap.
     *
     * @return whether the snapshot succeeded
     */
    private boolean snapshot() {
        var pool = ObjC.pushPool();
        try {
            var image = CoreGraphics.snapshot(displayId);
            if (ObjC.isNull(image)) {
                return false;
            }
            try {
                CoreGraphics.drawImage(context, width(), height(), image);
            } finally {
                CoreGraphics.releaseImage(image);
            }
            return true;
        } finally {
            ObjC.popPool(pool);
        }
    }

    @Override
    void release() {
        running = false;
        if (thread != null) {
            LockSupport.unpark(thread);
            var interrupted = false;
            while (true) {
                try {
                    thread.join();
                    break;
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        CoreGraphics.releaseContext(context);
        CoreGraphics.releaseColorSpace(colorSpace);
        context = MemorySegment.NULL;
        colorSpace = MemorySegment.NULL;
        arena.close();
    }
}
