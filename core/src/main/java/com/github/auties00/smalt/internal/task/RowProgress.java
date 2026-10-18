package com.github.auties00.smalt.internal.task;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

/**
 * Tracks how far each row of a picture has been processed, for wavefronts in which a row waits
 * for the row above it to get ahead, one row per thread on {@link ParallelBands}.
 *
 * <p>A batch starts with {@link #begin(int)}; each row publishes its progress with
 * {@link #advance(int, int)} and waits for another row with {@link #await(int, int)}. At most one
 * thread waits on a row at a time (in a wavefront, the row below it). When a row fails,
 * {@link #fail()} releases every waiting row, whose {@code await} then returns {@code false}, so no
 * thread waits forever. {@code advance}, {@code await} and {@code fail} may be called from any
 * thread; {@code begin} only while no row of the previous batch runs.
 *
 * @implNote This implementation spins briefly and then parks, and wakes a parked row as soon as
 * the row it waits on advances instead of letting it poll: a short timed park lasts a whole timer
 * period on some platforms (about a millisecond on Windows). The timed park remains only as a
 * safety net. A parked row is declared to {@link ParallelBands#beginBlocking()}.
 */
public final class RowProgress {
    /**
     * The number of spins before a waiting row starts to park.
     */
    private static final int SPINS = 200;

    /**
     * How long a waiting row parks before it checks again without having been woken, in
     * nanoseconds.
     */
    private static final long PARK_NANOS = 1_000_000;

    /**
     * The progress of each row of the batch.
     */
    private AtomicIntegerArray progress = new AtomicIntegerArray(0);

    /**
     * The thread parked waiting on each row of the batch, or {@code null} entries.
     */
    private AtomicReferenceArray<Thread> waiters = new AtomicReferenceArray<>(0);

    /**
     * Whether a row of the batch failed.
     */
    private volatile boolean failed;

    /**
     * Constructs a tracker with no rows.
     */
    public RowProgress() {
    }

    /**
     * Starts a batch of rows, none of which has made progress.
     *
     * @param rows the number of rows, non-negative
     */
    public void begin(int rows) {
        if (progress.length() < rows) {
            progress = new AtomicIntegerArray(rows);
            waiters = new AtomicReferenceArray<>(rows);
        } else {
            for (var i = 0; i < rows; i++) {
                progress.set(i, 0);
            }
        }
        failed = false;
    }

    /**
     * Waits until a row has reached a progress value or the batch failed.
     *
     * @param row the row waited on, from 0 to the batch size minus one
     * @param needed the progress the caller needs
     * @return {@code true} once the row reached {@code needed}, {@code false} when the batch failed
     * first
     */
    public boolean await(int row, int needed) {
        if (progress.get(row) >= needed) {
            return true;
        }
        for (var spin = 0; spin < SPINS; spin++) {
            if (progress.get(row) >= needed) {
                return true;
            }
            if (failed) {
                return false;
            }
            Thread.onSpinWait();
        }
        ParallelBands.beginBlocking();
        waiters.set(row, Thread.currentThread());
        try {
            while (progress.get(row) < needed) {
                if (failed) {
                    return false;
                }
                LockSupport.parkNanos(this, PARK_NANOS);
            }
            return true;
        } finally {
            waiters.set(row, null);
            ParallelBands.endBlocking();
        }
    }

    /**
     * Publishes the progress of a row and wakes the thread waiting on it.
     *
     * @param row the row, from 0 to the batch size minus one
     * @param value the progress, never lower than a value published before for the row
     */
    public void advance(int row, int value) {
        progress.set(row, value);
        var waiter = waiters.get(row);
        if (waiter != null) {
            LockSupport.unpark(waiter);
        }
    }

    /**
     * {@return the progress a row published last, 0 before the first}
     *
     * @param row the row, from 0 to the batch size minus one
     */
    public int progress(int row) {
        return progress.get(row);
    }

    /**
     * Marks the batch as failed and wakes every waiting row.
     */
    public void fail() {
        failed = true;
        for (var i = 0; i < waiters.length(); i++) {
            var waiter = waiters.get(i);
            if (waiter != null) {
                LockSupport.unpark(waiter);
            }
        }
    }

    /**
     * {@return whether a row of the batch failed}
     */
    public boolean failed() {
        return failed;
    }
}
