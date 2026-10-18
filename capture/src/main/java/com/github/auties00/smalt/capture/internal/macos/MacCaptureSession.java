package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.internal.FrameExchange;
import com.github.auties00.smalt.image.ColorInfo;

import com.github.auties00.smalt.internal.limits.LimitChecks;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implements the frame delivery and the close protocol shared by the macOS sessions.
 *
 * <p>A session owns a {@link FrameExchange} of three pictures. The native side captures into the
 * exchange from its own thread; {@link #read()} takes frames from it and, while waiting, checks
 * every {@link #HEALTH_CHECK_NANOS} whether the device is still {@link #alive() alive}.
 * {@link #close()} first closes the exchange, which wakes a blocked reader, then releases the
 * native resources exactly once, under a lock that also guards the health checks so that no
 * native call runs on a released object.
 */
abstract sealed class MacCaptureSession implements VideoCaptureSession
        permits MacCameraSession, MacStreamSession, MacSnapshotSession {
    /**
     * How often a waiting reader checks the device.
     */
    private static final long HEALTH_CHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    /**
     * The captured device.
     */
    private final CaptureDevice device;

    /**
     * The width of the delivered pictures.
     */
    private final int width;

    /**
     * The height of the delivered pictures.
     */
    private final int height;

    /**
     * The exchange between the capturing thread and the reader.
     */
    final FrameExchange exchange;

    /**
     * Guards {@link #released} and the native calls of health checks and release.
     */
    private final ReentrantLock state;

    /**
     * Whether the native resources were released.
     */
    private boolean released;

    /**
     * Allocates the pictures and the exchange.
     *
     * @param device the captured device
     * @param width the picture width, even and positive
     * @param height the picture height, even and positive
     * @param repeatNanos the repeat interval of the exchange, or {@code 0}
     * @throws MediaException.LimitExceeded if the pictures would exceed {@link MediaLimits#DEFAULT}
     * @throws IllegalArgumentException if a dimension is odd or not positive
     */
    MacCaptureSession(CaptureDevice device, int width, int height, long repeatNanos) {
        this.device = Objects.requireNonNull(device, "device");
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("invalid capture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(MediaLimits.DEFAULT, width, height);
        LimitChecks.checkAllocation(MediaLimits.DEFAULT, 3L * width * height * 3 / 2);
        this.width = width;
        this.height = height;
        this.exchange = new FrameExchange(width, height, ColorInfo.BT709_LIMITED, repeatNanos, System::nanoTime,
                System.nanoTime());
        this.state = new ReentrantLock();
    }

    @Override
    public CaptureDevice device() {
        return device;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public Frame read() throws InterruptedException {
        while (true) {
            var frame = exchange.poll(HEALTH_CHECK_NANOS);
            if (frame != null || exchange.isDone()) {
                return frame;
            }
            checkHealth();
        }
    }

    /**
     * Ends the exchange when the device stopped.
     */
    private void checkHealth() {
        state.lock();
        try {
            if (!released && !alive()) {
                exchange.finish();
            }
        } finally {
            state.unlock();
        }
    }

    @Override
    public void close() {
        exchange.close();
        state.lock();
        try {
            if (released) {
                return;
            }
            released = true;
            PlatformThreads.run(this::release);
        } finally {
            state.unlock();
        }
    }

    /**
     * Returns whether the device still delivers frames.
     *
     * <p>Called with the state lock held and the resources not released; may call native code.
     *
     * @return whether the device is running
     */
    boolean alive() {
        return true;
    }

    /**
     * Stops capturing and releases every native resource.
     *
     * <p>Called once, with the state lock held, on a platform thread inside an autorelease pool.
     *
     * @implSpec
     * Implementations must tolerate resources that were never created, since a session that
     * failed to start is released too, and must not throw.
     */
    abstract void release();
}
