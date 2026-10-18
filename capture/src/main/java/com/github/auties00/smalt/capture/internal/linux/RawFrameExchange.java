package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;

import java.io.IOException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hands the newest raw frame from a producer thread to a consumer thread.
 *
 * <p>Three buffers rotate: the producer fills its back buffer and publishes it, which swaps it
 * with the pending buffer; the consumer takes the pending buffer, which swaps it with its front
 * buffer. A frame published before the consumer took the previous one replaces it, so the
 * consumer always receives the newest frame and never falls behind, and neither side waits for
 * the other except for the swap itself. The buffers are sized once, so steady-state capture
 * allocates nothing.
 *
 * <p>There must be one producer and one consumer at a time. The frame returned by
 * {@link #take()} stays untouched until the next {@link #take()}.
 */
final class RawFrameExchange {
    /**
     * Guards the pending buffer and the state flags.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Signaled when a frame is published, the producer finishes, or the exchange closes.
     */
    private final Condition changed = lock.newCondition();

    /**
     * The buffer the producer fills; accessed only by the producer.
     */
    private RawFrame back;

    /**
     * The newest published buffer, or a stale one when {@link #hasPending} is {@code false}.
     */
    private RawFrame pending;

    /**
     * The buffer the consumer reads; accessed only by the consumer.
     */
    private RawFrame front;

    /**
     * Whether {@link #pending} holds a frame the consumer has not taken.
     */
    private boolean hasPending;

    /**
     * Whether the producer has stopped.
     */
    private boolean finished;

    /**
     * The reason the producer stopped, or {@code null} when the device simply stopped.
     */
    private IOException failure;

    /**
     * Whether the exchange is closed.
     */
    private boolean closed;

    /**
     * Allocates the three buffers.
     *
     * @param capacity the size of each buffer in bytes; not negative
     */
    RawFrameExchange(int capacity) {
        this.back = new RawFrame(new byte[capacity]);
        this.pending = new RawFrame(new byte[capacity]);
        this.front = new RawFrame(new byte[capacity]);
    }

    /**
     * Returns the buffer the producer fills next; called only by the producer.
     *
     * @return the back buffer
     */
    RawFrame producerFrame() {
        return back;
    }

    /**
     * Publishes the back buffer as the newest frame, replacing a frame the consumer has not taken;
     * called only by the producer.
     */
    void publish() {
        lock.lock();
        try {
            var published = back;
            back = pending;
            pending = published;
            hasPending = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records that the producer stopped; the consumer still receives the pending frame.
     *
     * @param failure the reason, or {@code null} when the device stopped normally
     */
    void finish(IOException failure) {
        lock.lock();
        try {
            if (!finished) {
                finished = true;
                this.failure = failure;
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits for a frame newer than the last one taken.
     *
     * @return the frame, valid until the next call, or {@code null} once the exchange is closed or
     * the producer stopped normally
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws MediaException.Io if the producer stopped because of a failure
     */
    RawFrame take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (!closed && !hasPending && !finished) {
                changed.await();
            }
            if (closed) {
                return null;
            }
            if (hasPending) {
                var taken = pending;
                pending = front;
                front = taken;
                hasPending = false;
                return taken;
            }
            if (failure != null) {
                throw new MediaException.Io(failure.getMessage(), failure);
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes the exchange, waking a waiting consumer; idempotent.
     */
    void close() {
        lock.lock();
        try {
            closed = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Holds one raw frame.
     */
    static final class RawFrame {
        /**
         * The frame bytes, from index {@code 0}.
         */
        private final byte[] data;

        /**
         * The number of valid bytes.
         */
        private int length;

        /**
         * The capture time on the session clock, in microseconds.
         */
        private long timestampMicros;

        /**
         * Wraps a buffer.
         *
         * @param data the buffer
         */
        RawFrame(byte[] data) {
            this.data = data;
        }

        /**
         * Returns the buffer.
         *
         * @return the frame bytes, from index {@code 0}
         */
        byte[] data() {
            return data;
        }

        /**
         * Returns the number of valid bytes.
         *
         * @return the length
         */
        int length() {
            return length;
        }

        /**
         * Returns the capture time.
         *
         * @return the time on the session clock, in microseconds
         */
        long timestampMicros() {
            return timestampMicros;
        }

        /**
         * Records the content written into the buffer.
         *
         * @param length the number of valid bytes
         * @param timestampMicros the capture time on the session clock, in microseconds
         */
        void set(int length, long timestampMicros) {
            this.length = length;
            this.timestampMicros = timestampMicros;
        }
    }
}
