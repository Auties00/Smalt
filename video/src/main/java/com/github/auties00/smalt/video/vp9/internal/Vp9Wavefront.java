package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Synchronizes the workers of a wavefront over rows, in which each worker processes whole rows and
 * a superblock may only start once the row it depends on has progressed far enough.
 *
 * <p>Progress is published per row with volatile writes and read with volatile reads, so every
 * sample written before a superblock's progress was published is visible to the worker that
 * waited for it. A wavefront is aborted when one of its workers fails, which makes every waiting
 * worker give up instead of waiting forever. A worker that blocks declares the wait to the
 * module's shared pool with {@link ParallelBands#beginBlocking()}, so another helper may run
 * posted work meanwhile.
 *
 * <p>An interruptible wavefront fails a wait that an interrupt ends; the others keep waiting and
 * return with the interrupt status still set, so that an interrupt never fails a decode.
 *
 * @implNote This implementation keeps every row's counter on its own 64-byte cache line, so a
 * worker publishing its row does not invalidate the line the other workers poll. A waiting worker
 * spins briefly, then blocks on a condition of that row; the number of workers blocked on a row
 * lives next to its counter, so a publisher takes the lock only while a worker waits for its row.
 */
final class Vp9Wavefront {
    /**
     * The busy-wait iterations before a waiting worker blocks, when the constructor names none.
     */
    private static final int DEFAULT_SPINS = 512;

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
     * The condition of each row, which a publication of the row signals while a worker is
     * blocked on it.
     */
    private final Condition[] published;

    /**
     * The number of rows the wavefront can hold.
     */
    private final int capacity;

    /**
     * The busy-wait iterations before a waiting worker blocks.
     */
    private final int spins;

    /**
     * What the workers are, as failure messages name them.
     */
    private final String workers;

    /**
     * Whether an interrupt fails a blocked wait.
     */
    private final boolean interruptible;

    /**
     * Constructs a wavefront of loop filter workers.
     *
     * @param capacity the largest number of rows
     * @param interruptible whether an interrupt fails a blocked wait
     */
    Vp9Wavefront(int capacity, boolean interruptible) {
        this(capacity, DEFAULT_SPINS, "loop filter thread", interruptible);
    }

    /**
     * Constructs a wavefront.
     *
     * @param capacity the largest number of rows
     * @param spins the busy-wait iterations before a waiting worker blocks
     * @param workers what the workers are, for example {@code "loop filter thread"}
     * @param interruptible whether an interrupt fails a blocked wait
     */
    Vp9Wavefront(int capacity, int spins, String workers, boolean interruptible) {
        this.capacity = capacity;
        this.spins = spins;
        this.workers = workers;
        this.interruptible = interruptible;
        this.progress = new AtomicIntegerArray(capacity * STRIDE);
        this.published = new Condition[capacity];
        for (var row = 0; row < capacity; row++) {
            published[row] = lock.newCondition();
        }
    }

    /**
     * {@return the largest number of rows the wavefront can hold}
     */
    int capacity() {
        return capacity;
    }

    /**
     * Resets the progress of the first rows to zero and clears the abort flag, before the
     * workers start.
     *
     * @param rows the number of rows of the next wavefront, at most the capacity
     */
    void reset(int rows) {
        for (var row = 0; row < rows; row++) {
            progress.set(row * STRIDE, 0);
        }
        aborted.set(false);
    }

    /**
     * Makes every current and future wait of this wavefront fail, until the next reset.
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
     * Waits until a row has published at least the given progress and returns the progress it
     * observed, which may exceed the one waited for.
     *
     * @param row the row, from 0 to the capacity minus one
     * @param needed the progress to wait for
     * @return the row's progress, at least {@code needed}
     * @throws CancellationException if the wavefront was aborted
     * @throws IllegalStateException if the wavefront is interruptible and the thread is interrupted
     * while blocked
     */
    int awaitProgress(int row, int needed) {
        var index = row * STRIDE;
        var spun = 0;
        int value;
        while ((value = progress.get(index)) < needed) {
            if (aborted.get()) {
                throw new CancellationException("another " + workers + " failed");
            }
            if (spun >= spins) {
                return block(row, needed);
            }
            Thread.onSpinWait();
            spun++;
        }
        return value;
    }

    /**
     * Blocks until a row has published at least the given progress.
     *
     * @param row the row, from 0 to the capacity minus one
     * @param needed the progress to wait for
     * @return the row's progress
     * @throws CancellationException if the wavefront was aborted
     * @throws IllegalStateException if the wavefront is interruptible and the thread is interrupted
     * while blocked
     */
    private int block(int row, int needed) {
        var index = row * STRIDE;
        ParallelBands.beginBlocking();
        progress.incrementAndGet(index + 1);
        lock.lock();
        try {
            int value;
            while ((value = progress.get(index)) < needed) {
                if (aborted.get()) {
                    throw new CancellationException("another " + workers + " failed");
                }
                if (interruptible) {
                    published[row].await();
                } else {
                    published[row].awaitUninterruptibly();
                }
            }
            return value;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for another " + workers, exception);
        } finally {
            lock.unlock();
            progress.decrementAndGet(index + 1);
            ParallelBands.endBlocking();
        }
    }

    /**
     * Publishes a row's progress, waking the workers blocked on the row if there are any.
     *
     * @param row the row, from 0 to the capacity minus one
     * @param done the number of finished superblocks of the row
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
