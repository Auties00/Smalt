package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.internal.FramePacing;
import com.github.auties00.smalt.track.Rational;

import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Captures a screen at a fixed frame rate by grabbing it when a frame is due.
 *
 * <p>Frame {@code n} is due one period after frame {@code n - 1} was due. {@link #read()} waits
 * until the next frame is due and grabs the screen then, so a reader that keeps up receives
 * frames at the requested rate, and a reader slower than the rate grabs immediately and always
 * receives the current content. A read that comes more than one period late restarts the cadence
 * from its grab. The wait is a {@link Condition} wait, so {@link #close()} from another thread
 * ends it at once and the read returns {@code null}; the grab and the close are serialized by one
 * lock, so the grabber is never released during a grab. Timestamps are taken from
 * {@link System#nanoTime()} after each grab, relative to the creation of the session, and are
 * strictly increasing.
 */
final class PacedCaptureSession implements VideoCaptureSession {
    /**
     * The captured device.
     */
    private final CaptureDevice device;

    /**
     * The source of the pictures.
     */
    private final ScreenGrabber grabber;

    /**
     * The frame period in nanoseconds.
     */
    private final long periodNanos;

    /**
     * The time the session was created, the origin of the timestamps.
     */
    private final long originNanos;

    /**
     * Serializes reads, grabs and the close.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Signaled when the session closes.
     */
    private final Condition closing = lock.newCondition();

    /**
     * The time the next frame is due; guarded by {@link #lock}.
     */
    private long nextDue;

    /**
     * Whether a frame was grabbed; guarded by {@link #lock}.
     */
    private boolean started;

    /**
     * The timestamp of the last frame; guarded by {@link #lock}.
     */
    private long lastTimestamp = -1;

    /**
     * Whether the session is closed; guarded by {@link #lock}.
     */
    private boolean closed;

    /**
     * Whether the screen disappeared; guarded by {@link #lock}.
     */
    private boolean stopped;

    /**
     * Creates a session over a grabber.
     *
     * @param device  the captured device
     * @param grabber the grabber, owned by the session from now on
     * @param frameRate the frames per second
     * @throws NullPointerException if an argument is {@code null}
     */
    PacedCaptureSession(CaptureDevice device, ScreenGrabber grabber, Rational frameRate) {
        this.device = Objects.requireNonNull(device, "device");
        this.grabber = Objects.requireNonNull(grabber, "grabber");
        this.periodNanos = FramePacing.periodNanos(Objects.requireNonNull(frameRate, "frameRate"));
        this.originNanos = System.nanoTime();
    }

    @Override
    public CaptureDevice device() {
        return device;
    }

    @Override
    public int width() {
        return grabber.width();
    }

    @Override
    public int height() {
        return grabber.height();
    }

    @Override
    public Frame read() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            if (started) {
                long remaining;
                while (!closed && (remaining = nextDue - System.nanoTime()) > 0) {
                    closing.awaitNanos(remaining);
                }
            }
            if (closed || stopped) {
                return null;
            }
            var picture = grabber.grab();
            var grabbed = System.nanoTime();
            if (picture == null) {
                stopped = true;
                return null;
            }
            var due = started ? nextDue : grabbed;
            nextDue = due + periodNanos;
            if (nextDue <= grabbed) {
                nextDue = grabbed + periodNanos;
            }
            started = true;
            var timestamp = Math.max((grabbed - originNanos) / 1_000, lastTimestamp + 1);
            lastTimestamp = timestamp;
            return new Frame(picture, timestamp);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            closing.signalAll();
            grabber.close();
        } finally {
            lock.unlock();
        }
    }
}
