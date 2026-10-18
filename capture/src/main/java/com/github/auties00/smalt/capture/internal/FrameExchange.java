package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.YuvPicture;

import java.util.Arrays;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Hands the newest captured picture from the thread that captures it to the reader of a session.
 *
 * <p>The exchange owns three pictures, which start black, and rotates them like a triple buffer:
 * one may be held by the reader (the last frame it received), one may be published and not yet
 * read, and the producer writes into the third, which {@link #acquire()} returns. Neither side waits
 * for the other to finish with a picture, and a reader slower than the producer receives the newest
 * frame while the older ones are overwritten; a delivered picture stays intact until the next read.
 *
 * <p>A source that produces pictures only when they change (a screen) keeps a steady rate in one of
 * two ways: the producer publishes the previous picture again with {@link #republish(long)}, or, with
 * a positive repeat interval, a reader that finds no new picture within the interval after its
 * previous frame receives that frame again with a fresh timestamp. Delivered timestamps are at least
 * zero and strictly increase: a timestamp not after the previous one becomes the previous one plus
 * one microsecond.
 *
 * <p>The exchange ends with {@link #close()} (the reader is done: every waiting and later read
 * returns {@code null}), {@link #finish()} (the source stopped) or {@link #fail(MediaException)}
 * (the source failed). Pictures published before the end are still delivered; a failure is then
 * thrown by one read, and every later read returns {@code null}. This class is thread-safe.
 */
public final class FrameExchange {
    private static final int SLOTS = 3;

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Signaled when a picture is published or the exchange ends.
     */
    private final Condition changed = lock.newCondition();

    private final YuvPicture[] slots;

    /**
     * The monotonic nanosecond clock of repeats, pacing waits and {@link #micros(long)}.
     */
    private final LongSupplier clock;

    /**
     * The clock reading at which timestamp zero falls.
     */
    private final long originNanos;

    /**
     * The repeat interval in nanoseconds, or 0 to never repeat on the reader's side.
     */
    private final long repeatNanos;

    /**
     * The slot of the newest published picture, or -1 before the first publication.
     */
    private int published = -1;

    /**
     * The newest published picture: its slot's picture or a picture sharing that slot's planes.
     */
    private YuvPicture publishedPicture;

    private long publishedMicros;

    /**
     * The number of publications so far.
     */
    private long sequence;

    /**
     * The slot the reader holds, or -1.
     */
    private int delivered = -1;

    private YuvPicture deliveredPicture;

    /**
     * The value of {@link #sequence} when the reader last took a published picture.
     */
    private long deliveredSequence;

    /**
     * The timestamp of the last delivery, or -1 before the first one.
     */
    private long deliveredMicros = -1;

    /**
     * The clock reading of the last delivery.
     */
    private long deliveredNanos;

    private boolean closed;
    private boolean finished;

    /**
     * The failure the next read throws, or {@code null}.
     */
    private MediaException failure;

    /**
     * Constructs an exchange that never repeats on the reader's side and times frames with
     * {@link System#nanoTime()} from now.
     *
     * @param width the width of the pictures, positive and even
     * @param height the height of the pictures, positive and even
     * @param color the color of the pictures
     */
    public FrameExchange(int width, int height, ColorInfo color) {
        this(width, height, color, 0, System::nanoTime, System.nanoTime());
    }

    /**
     * Constructs an exchange.
     *
     * @param width the width of the pictures, positive and even
     * @param height the height of the pictures, positive and even
     * @param color the color of the pictures until a frame says otherwise
     * @param repeatNanos the reader-side repeat interval in nanoseconds, or 0 to never repeat
     * @param clock the monotonic nanosecond clock, normally {@link System#nanoTime()}
     * @param originNanos the clock reading at which timestamp zero falls
     * @throws IllegalArgumentException if {@code repeatNanos} is negative
     */
    public FrameExchange(int width, int height, ColorInfo color, long repeatNanos, LongSupplier clock, long originNanos) {
        if (repeatNanos < 0) {
            throw new IllegalArgumentException("negative repeat interval " + repeatNanos);
        }
        this.slots = new YuvPicture[SLOTS];
        var black = (byte) (color.range() == ColorRange.LIMITED ? 16 : 0);
        for (var i = 0; i < SLOTS; i++) {
            var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, color);
            Arrays.fill(picture.y(), black);
            slots[i] = picture;
        }
        this.clock = clock;
        this.originNanos = originNanos;
        this.repeatNanos = repeatNanos;
    }

    /**
     * {@return the width of the pictures, in pixels}
     */
    public int width() {
        return slots[0].width();
    }

    /**
     * {@return the height of the pictures, in pixels}
     */
    public int height() {
        return slots[0].height();
    }

    /**
     * Returns a picture the producer may overwrite: neither the published one nor the one the reader
     * holds.
     *
     * <p>Only the producer calls this method, and it uses the picture only until it publishes it or
     * acquires again.
     *
     * @return the picture
     */
    public YuvPicture acquire() {
        lock.lock();
        try {
            for (var i = 0; i < SLOTS; i++) {
                if (i != published && i != delivered) {
                    return slots[i];
                }
            }
            throw new AssertionError("no free picture");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Publishes a picture the producer filled; publishing after the exchange ended does nothing.
     *
     * @param picture the picture {@link #acquire()} returned, or a picture sharing its planes with
     * another color description
     * @param timestampMicros the capture time on the session clock, in microseconds
     * @throws IllegalArgumentException if {@code picture} shares no planes with a picture of this
     * exchange
     */
    public void publish(YuvPicture picture, long timestampMicros) {
        var slot = slotOf(picture);
        lock.lock();
        try {
            if (closed || finished) {
                return;
            }
            published = slot;
            publishedPicture = picture;
            publishedMicros = timestampMicros;
            sequence++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Publishes the previous picture again with a new timestamp.
     *
     * @param timestampMicros the capture time on the session clock, in microseconds
     * @return whether a picture had been published before
     */
    public boolean republish(long timestampMicros) {
        lock.lock();
        try {
            if (published < 0) {
                return false;
            }
            publish(publishedPicture, timestampMicros);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private int slotOf(YuvPicture picture) {
        for (var i = 0; i < SLOTS; i++) {
            if (slots[i].y() == picture.y()) {
                return i;
            }
        }
        throw new IllegalArgumentException("the picture does not belong to this exchange");
    }

    /**
     * Ends the exchange because the source stopped; pictures already published are still delivered.
     */
    public void finish() {
        lock.lock();
        try {
            finished = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ends the exchange with a failure, unless it already ended.
     *
     * @param error the failure one read throws
     */
    public void fail(MediaException error) {
        lock.lock();
        try {
            if (!closed && !finished) {
                failure = error;
                finished = true;
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ends the exchange from the reader's side: waiting reads and producer waits return.
     */
    public void close() {
        lock.lock();
        try {
            closed = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@return whether the reader closed the exchange}
     */
    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@return whether the producer should stop: the exchange was closed, finished or failed}
     */
    public boolean isStopped() {
        lock.lock();
        try {
            return closed || finished;
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@return whether no frame will be delivered again: the exchange was closed, or it ended and
     * every published picture and its failure were delivered}
     */
    public boolean isDone() {
        lock.lock();
        try {
            return closed || (finished && failure == null && sequence == deliveredSequence);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until a deadline or until the exchange stops, whichever comes first; an interrupt ends the
     * wait early and stays set.
     *
     * @param deadlineNanos the deadline on this exchange's clock
     * @return whether the exchange is still running
     */
    public boolean sleepUntil(long deadlineNanos) {
        lock.lock();
        try {
            while (!closed && !finished) {
                var remaining = deadlineNanos - clock.getAsLong();
                if (remaining <= 0) {
                    return true;
                }
                try {
                    changed.awaitNanos(remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the session timestamp of a clock reading.
     *
     * @param nanos a reading of this exchange's clock
     * @return the microseconds elapsed since the origin, negative before it
     */
    public long micros(long nanos) {
        return (nanos - originNanos) / 1_000;
    }

    /**
     * Takes the newest frame, waiting until one is published or the exchange is done.
     *
     * @return the frame, valid until the next read, or {@code null} once the exchange is done
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws MediaException the failure the source ended with, once
     */
    public VideoCaptureSession.Frame take() throws InterruptedException {
        return poll(Long.MAX_VALUE);
    }

    /**
     * Takes the newest frame, waiting at most a timeout.
     *
     * <p>Returns a newly published picture first; then, with a repeat interval, the last delivered
     * picture again once the interval elapsed since its delivery; then the source's failure, once.
     *
     * @param timeoutNanos the longest wait in nanoseconds; {@link Long#MAX_VALUE} waits without limit
     * @return the frame, valid until the next read, or {@code null} when the timeout elapses first or
     * the exchange is done
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws MediaException the failure the source ended with, once
     */
    public VideoCaptureSession.Frame poll(long timeoutNanos) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            var unbounded = timeoutNanos == Long.MAX_VALUE;
            var deadline = unbounded ? 0 : clock.getAsLong() + Math.max(0, timeoutNanos);
            while (true) {
                if (closed) {
                    delivered = -1;
                    return null;
                }
                if (sequence > deliveredSequence) {
                    deliveredSequence = sequence;
                    delivered = published;
                    deliveredPicture = publishedPicture;
                    return deliver(publishedMicros);
                }
                if (failure != null) {
                    var error = failure;
                    failure = null;
                    delivered = -1;
                    throw error;
                }
                if (finished) {
                    delivered = -1;
                    return null;
                }
                var now = clock.getAsLong();
                var wake = unbounded ? Long.MAX_VALUE : deadline - now;
                if (repeatNanos > 0 && deliveredMicros >= 0) {
                    var untilRepeat = deliveredNanos + repeatNanos - now;
                    if (untilRepeat <= 0) {
                        return deliver(micros(now));
                    }
                    wake = Math.min(wake, untilRepeat);
                }
                if (wake <= 0) {
                    return null;
                }
                if (wake == Long.MAX_VALUE) {
                    changed.await();
                } else {
                    changed.awaitNanos(wake);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Delivers the reader's picture with a timestamp raised to at least zero and above the previous
     * delivery.
     */
    private VideoCaptureSession.Frame deliver(long timestampMicros) {
        var timestamp = Math.max(timestampMicros, deliveredMicros + 1);
        deliveredMicros = timestamp;
        deliveredNanos = clock.getAsLong();
        return new VideoCaptureSession.Frame(deliveredPicture, timestamp);
    }
}
