package com.github.auties00.smalt.capture.internal.audio;

import com.github.auties00.smalt.MediaException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Queues interleaved float frames between a device thread and the caller of a session.
 *
 * <p>The ring holds up to {@link #capacity()} frames of {@link #channels()} samples each. Frames
 * are counted from the start of the stream: {@link #head()} is the number of frames ever
 * appended, {@link #tail()} the number ever consumed or dropped, and their difference the frames
 * queued. A capture session's device thread {@linkplain #append(float[], int, int) appends},
 * dropping the oldest frames when full, and its reader {@linkplain #read(float[], int, int)
 * reads}, blocking; a playback session's writer {@linkplain #write(float[], int, int) writes},
 * blocking while full, and its device thread {@linkplain #poll(float[], int, int) polls}. The
 * device side never blocks.
 *
 * <p>The ring ends in one of three ways: {@link #close()} by the session (blocked and later
 * reads and writes return at once), {@link #end()} when the device stops, or
 * {@link #fail(MediaException)} when it fails, whose exception the next read or write throws
 * once, after the frames queued before the failure have been read. The ring is thread-safe.
 */
public final class AudioRing {
    /**
     * Guards every mutable field.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Signals that frames were appended or the ring ended.
     */
    private final Condition readable = lock.newCondition();

    /**
     * Signals that frames were consumed or the ring ended.
     */
    private final Condition writable = lock.newCondition();

    /**
     * The number of samples of one frame.
     */
    private final int channels;

    /**
     * The capacity in frames.
     */
    private final int capacity;

    /**
     * The samples, {@code capacity * channels} long; frame {@code n} of the stream lives at
     * {@code (n % capacity) * channels}.
     */
    private final float[] data;

    /**
     * The number of frames ever appended.
     */
    private long head;

    /**
     * The number of frames ever consumed or dropped.
     */
    private long tail;

    /**
     * The tail position at which the last {@link #read(float[], int, int)} started.
     */
    private long readStart;

    /**
     * Whether the session closed the ring.
     */
    private boolean closed;

    /**
     * Whether the device stopped or failed.
     */
    private boolean ended;

    /**
     * The failure to report once, or {@code null}.
     */
    private MediaException failure;

    /**
     * Creates an empty ring.
     *
     * @param channels the number of samples of one frame; positive
     * @param capacity the capacity in frames; positive
     * @throws IllegalArgumentException if an argument is not positive or the ring would exceed
     *                                  the largest array
     */
    public AudioRing(int channels, int capacity) {
        if (channels <= 0 || capacity <= 0 || (long) channels * capacity > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("invalid ring of " + capacity + " frames of " + channels + " channels");
        }
        this.channels = channels;
        this.capacity = capacity;
        this.data = new float[channels * capacity];
    }

    /**
     * Returns the number of samples of one frame.
     *
     * @return the channel count
     */
    public int channels() {
        return channels;
    }

    /**
     * Returns the capacity.
     *
     * @return the capacity in frames
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Returns the number of frames ever appended.
     *
     * @return the head position
     */
    public long head() {
        lock.lock();
        try {
            return head;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of frames ever consumed or dropped.
     *
     * @return the tail position
     */
    public long tail() {
        lock.lock();
        try {
            return tail;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the position of the first frame the last successful {@link #read(float[], int, int)}
     * returned.
     *
     * @return the stream position, in frames
     */
    public long readStart() {
        lock.lock();
        try {
            return readStart;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of frames queued.
     *
     * @return the frames between tail and head
     */
    public int size() {
        lock.lock();
        try {
            return (int) (head - tail);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Appends frames without blocking, dropping the oldest queued frames when the ring is full.
     *
     * <p>When more frames are appended than the ring holds, only the newest {@link #capacity()}
     * are kept. Frames appended after the ring ended are discarded.
     *
     * @param source the interleaved samples
     * @param offset the index of the first sample
     * @param frames the number of frames; non-negative
     * @return the number of frames dropped, queued ones and appended ones
     */
    public long append(float[] source, int offset, int frames) {
        lock.lock();
        try {
            if (closed || ended || frames == 0) {
                return 0;
            }
            var dropped = 0L;
            var skip = Math.max(0, frames - capacity);
            var overflow = head - tail + frames - skip - capacity;
            if (overflow > 0) {
                tail += overflow;
                dropped += overflow;
            }
            head += skip;
            tail += skip;
            dropped += skip;
            copyIn(source, offset + skip * channels, frames - skip);
            readable.signalAll();
            return dropped;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes up to {@code frames} frames without blocking.
     *
     * @param target receives the interleaved samples
     * @param offset the index of the first sample written
     * @param frames the largest number of frames to remove
     * @return the number of frames removed
     */
    public int poll(float[] target, int offset, int frames) {
        lock.lock();
        try {
            var count = (int) Math.min(frames, head - tail);
            if (count <= 0) {
                return 0;
            }
            copyOut(target, offset, count);
            writable.signalAll();
            return count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads frames, blocking until {@code frames} are queued or the ring ends.
     *
     * <p>A read of at most half the capacity takes its frames at once, when they are all queued. A
     * longer read, which the ring cannot hold at once, takes them in chunks of at least half the
     * capacity as they arrive.
     *
     * @param target receives the interleaved samples
     * @param offset the index of the first sample written
     * @param frames the number of frames; non-negative
     * @return the number of frames read: {@code frames}; fewer when the ring ended meanwhile, or
     *         when the thread was interrupted after part of a long read was taken, in which case
     *         its interrupt status is set; or {@code -1} when it had ended with nothing queued
     * @throws InterruptedException if the calling thread is interrupted before any frame was
     *                              taken; nothing is consumed then
     * @throws MediaException       the device's failure, once, when no frame is left
     */
    public int read(float[] target, int offset, int frames) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            var copied = 0;
            var chunk = Math.max(1, capacity / 2);
            while (true) {
                if (closed) {
                    return copied > 0 ? copied : -1;
                }
                var remaining = frames - copied;
                var queued = head - tail;
                if (queued >= Math.min(remaining, chunk) || (ended && queued > 0)) {
                    var count = (int) Math.min(remaining, queued);
                    if (copied == 0) {
                        readStart = tail;
                    }
                    copyOut(target, offset + copied * channels, count);
                    copied += count;
                    if (copied == frames) {
                        return copied;
                    }
                    continue;
                }
                if (ended) {
                    if (copied > 0) {
                        return copied;
                    }
                    throwFailure();
                    return frames == 0 ? 0 : -1;
                }
                try {
                    readable.await();
                } catch (InterruptedException exception) {
                    if (copied == 0) {
                        throw exception;
                    }
                    Thread.currentThread().interrupt();
                    return copied;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes frames, blocking while the ring is full.
     *
     * @param source the interleaved samples
     * @param offset the index of the first sample
     * @param frames the number of frames; non-negative
     * @return the number of frames written: {@code frames}, or fewer when the ring ended
     *         meanwhile
     * @throws InterruptedException if the calling thread is interrupted while waiting; the frames
     *                              written so far stay queued
     * @throws MediaException       the device's failure, once
     */
    public int write(float[] source, int offset, int frames) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            var written = 0;
            while (written < frames) {
                if (closed) {
                    return written;
                }
                if (ended) {
                    throwFailure();
                    return written;
                }
                var free = (int) (capacity - (head - tail));
                if (free == 0) {
                    writable.await();
                    continue;
                }
                var count = Math.min(free, frames - written);
                copyIn(source, offset + written * channels, count);
                written += count;
                readable.signalAll();
            }
            return written;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until every queued frame has been consumed or the ring ends.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws MediaException       the device's failure, once
     */
    public void awaitEmpty() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (head > tail && !closed && !ended) {
                writable.await(50, TimeUnit.MILLISECONDS);
            }
            if (ended && !closed) {
                throwFailure();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes the ring: waiting readers and writers return at once, and later calls report the end.
     */
    public void close() {
        lock.lock();
        try {
            closed = true;
            readable.signalAll();
            writable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks the device as stopped: readers receive the queued frames, then the end.
     */
    public void end() {
        lock.lock();
        try {
            ended = true;
            readable.signalAll();
            writable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks the device as failed and stopped.
     *
     * @param exception the failure, thrown once by a read or write
     */
    public void fail(MediaException exception) {
        lock.lock();
        try {
            if (failure == null && !closed && !ended) {
                failure = exception;
            }
            ended = true;
            readable.signalAll();
            writable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns whether the ring was closed or the device stopped.
     *
     * @return whether the device thread should stop
     */
    public boolean stopped() {
        lock.lock();
        try {
            return closed || ended;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Throws the pending failure, clearing it; called with the lock held.
     *
     * @throws MediaException the failure, if one is pending
     */
    private void throwFailure() {
        var pending = failure;
        if (pending != null) {
            failure = null;
            throw pending;
        }
    }

    /**
     * Copies frames in at the head and advances it; called with the lock held and enough room.
     *
     * @param source the interleaved samples
     * @param offset the index of the first sample
     * @param frames the number of frames
     */
    private void copyIn(float[] source, int offset, int frames) {
        var position = (int) (head % capacity);
        var first = Math.min(frames, capacity - position);
        System.arraycopy(source, offset, data, position * channels, first * channels);
        if (first < frames) {
            System.arraycopy(source, offset + first * channels, data, 0, (frames - first) * channels);
        }
        head += frames;
    }

    /**
     * Copies frames out at the tail and advances it; called with the lock held and enough frames.
     *
     * @param target receives the interleaved samples
     * @param offset the index of the first sample written
     * @param frames the number of frames
     */
    private void copyOut(float[] target, int offset, int frames) {
        var position = (int) (tail % capacity);
        var first = Math.min(frames, capacity - position);
        System.arraycopy(data, position * channels, target, offset, first * channels);
        if (first < frames) {
            System.arraycopy(data, 0, target, offset + first * channels, (frames - first) * channels);
        }
        tail += frames;
    }
}
