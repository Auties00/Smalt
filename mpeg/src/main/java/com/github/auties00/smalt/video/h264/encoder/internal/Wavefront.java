package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Synchronizes the workers of a row wavefront, in which each worker processes whole macroblock
 * rows and a macroblock may only start once the row above has progressed far enough.
 *
 * <p>Progress is published per row with volatile writes and read with volatile reads, so every
 * sample written before a macroblock's progress was published is visible to the worker that
 * waited for it. A wavefront can be aborted when one of its workers fails, which makes every
 * waiting worker give up instead of waiting forever. A worker that blocks declares the wait to
 * the module's shared pool with {@link ParallelBands#beginBlocking()}, so while a pool helper is
 * blocked here another helper may run posted work.
 *
 * @implNote This implementation keeps every row's counter on its own 64-byte cache line, so a
 * worker publishing its row does not invalidate the line the other workers poll. A waiting worker
 * spins briefly, since the row it waits for is normally only a few macroblocks ahead, then blocks
 * on a condition of that row until its publisher signals it. The number of workers blocked on a
 * row lives next to the row's counter, so a publisher reads it from the line it has just written
 * and only takes the lock while a worker waits for its own row; workers waiting for other rows
 * are not woken. The waiter does not yield between spinning and blocking: on a machine with
 * fewer free processors than runnable threads, a yield can give the processor away for a whole
 * scheduling quantum to an unrelated thread, and a wavefront that waited through a series of
 * yields measured tens of times slower than one whose waiters blocked and were woken as soon as
 * the row advanced.
 */
final class Wavefront {
    /**
     * The number of busy-wait iterations before a waiting worker blocks.
     */
    private static final int SPINS = 256;

    /**
     * The number of macroblocks between two publications of a row's progress in the wavefronts
     * whose per-macroblock work is short, the look-ahead analysis and the deblocking filter.
     *
     * @implNote This implementation publishes those rows every four macroblocks, and their
     * workers remember the progress they last read, because at a fraction of a microsecond per
     * macroblock a cache-line transfer for every macroblock costs as much as the work.
     */
    static final int GROUP = 4;

    /**
     * The distance in {@code int}s between the counters of consecutive rows: one cache line.
     */
    private static final int STRIDE = 16;

    /**
     * The progress of each row, at index {@code row * STRIDE}, and the number of workers blocked
     * or about to block on the row, at index {@code row * STRIDE + 1}.
     */
    private final AtomicIntegerArray progress;

    /**
     * Whether a worker of the wavefront failed.
     */
    private final AtomicBoolean aborted = new AtomicBoolean();

    /**
     * The lock blocked workers wait under.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * The condition of each row, on which the workers waiting for the row block; a publication
     * of the row signals it while any of them is blocked.
     */
    private final Condition[] published;

    /**
     * The number of rows.
     */
    private final int rows;

    /**
     * Constructs a wavefront.
     *
     * @param rows the number of rows
     */
    Wavefront(int rows) {
        this.rows = rows;
        this.progress = new AtomicIntegerArray(rows * STRIDE);
        this.published = new Condition[rows];
        for (var row = 0; row < rows; row++) {
            published[row] = lock.newCondition();
        }
    }

    /**
     * Resets every row's progress to zero and clears the abort flag, before the workers start.
     */
    void reset() {
        for (var row = 0; row < rows; row++) {
            progress.set(row * STRIDE, 0);
        }
        aborted.set(false);
    }

    /**
     * Makes every current and future wait of this wavefront fail, until the next
     * {@link #reset()}.
     */
    void abort() {
        aborted.set(true);
        lock.lock();
        try {
            for (var condition : published) {
                condition.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns whether the wavefront was aborted.
     *
     * @return whether a worker failed since the last {@link #reset()}
     */
    boolean aborted() {
        return aborted.get();
    }

    /**
     * Returns a row's published progress.
     *
     * @param row the macroblock row, from 0
     * @return the progress
     */
    int progress(int row) {
        return progress.get(row * STRIDE);
    }

    /**
     * Waits until a row has published at least the given progress and returns the progress it
     * observed, which may exceed the one waited for.
     *
     * @param row the macroblock row, from 0
     * @param needed the progress to wait for
     * @return the row's progress, at least {@code needed}
     * @throws CancellationException if the wavefront was aborted
     * @throws IllegalStateException if the thread is interrupted while blocked
     */
    int awaitProgress(int row, int needed) {
        await(row, needed);
        return progress.get(row * STRIDE);
    }

    /**
     * Waits until a row has published at least the given progress.
     *
     * @param row the macroblock row, from 0
     * @param needed the progress to wait for
     * @throws CancellationException if the wavefront was aborted
     * @throws IllegalStateException if the thread is interrupted while blocked
     */
    void await(int row, int needed) {
        var index = row * STRIDE;
        var spins = 0;
        while (progress.get(index) < needed) {
            if (aborted.get()) {
                throw new CancellationException("another encoder thread failed");
            }
            if (spins >= SPINS) {
                block(row, needed);
                return;
            }
            Thread.onSpinWait();
            spins++;
        }
    }

    /**
     * Blocks until a row has published at least the given progress.
     *
     * @param row the macroblock row, from 0
     * @param needed the progress to wait for
     * @throws CancellationException if the wavefront was aborted
     * @throws IllegalStateException if the thread is interrupted while blocked
     */
    private void block(int row, int needed) {
        var index = row * STRIDE;
        ParallelBands.beginBlocking();
        progress.incrementAndGet(index + 1);
        lock.lock();
        try {
            while (progress.get(index) < needed) {
                if (aborted.get()) {
                    throw new CancellationException("another encoder thread failed");
                }
                published[row].await();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for another encoder thread", exception);
        } finally {
            lock.unlock();
            progress.decrementAndGet(index + 1);
            ParallelBands.endBlocking();
        }
    }

    /**
     * Publishes a row's progress, waking the workers blocked on the row if there are any.
     *
     * @param row the macroblock row, from 0
     * @param done the number of finished macroblocks of the row
     */
    void publish(int row, int done) {
        var index = row * STRIDE;
        progress.set(index, done);
        if (progress.get(index + 1) > 0) {
            lock.lock();
            try {
                published[row].signalAll();
            } finally {
                lock.unlock();
            }
        }
    }
}
