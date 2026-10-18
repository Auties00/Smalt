package com.github.auties00.smalt.transcode;

import java.util.concurrent.CancellationException;

/**
 * Carries the cancellation request of one transcode between the thread that runs it and the
 * threads that may cancel it.
 *
 * <p>An instance is thread-safe.
 */
final class Cancellation {
    /**
     * Whether cancellation was requested.
     */
    private volatile boolean canceled;

    /**
     * Constructs an instance that is not canceled.
     */
    Cancellation() {
    }

    /**
     * Requests cancellation.
     */
    void cancel() {
        canceled = true;
    }

    /**
     * Returns whether cancellation was requested.
     *
     * @return {@code true} once {@link #cancel()} ran
     */
    boolean isCanceled() {
        return canceled;
    }

    /**
     * Stops the transcode when cancellation was requested or the running thread was interrupted.
     *
     * <p>An interrupted thread keeps its interrupt status.
     *
     * @throws CancellationException if the transcode must stop
     */
    void check() {
        if (canceled) {
            throw new CancellationException("the transcode was canceled");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("the transcode thread was interrupted");
        }
    }
}
