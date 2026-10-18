package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs the macroblock rows of a range in a wavefront: the calling thread and helpers of the
 * module's shared pool claim rows in order, and a row waits only for the progress of the row
 * above, as published by {@link #publish(int, int)}.
 *
 * <p>Progress is published per row with volatile writes and read with volatile reads, so every
 * sample, mode and context a row wrote before publishing is visible to the row that waited for
 * it. Rows are claimed in order from a shared counter and wait only for the row above, so every
 * row a participant waits for belongs to a participant that is running it, at most one
 * participant waits for a row at a time, and the range completes even when no helper is free. A
 * participant that fails aborts the wavefront, which makes every waiting participant give up
 * instead of waiting forever. Instances are used by one run at a time.
 *
 * @implNote This implementation keeps every row's counter on its own 64-byte cache line, together
 * with the progress the participant parked on the row waits for. A waiting participant spins for
 * at most {@link #SPIN_NANOS}, a few macroblocks of coding, so that participants running in step
 * do not pay a park and unpark round trip, and then parks, declared to the shared pool with
 * {@link ParallelBands#beginBlocking()} so another helper may run posted work.
 */
final class RowWavefront {
    /**
     * The code a participant runs for one row.
     */
    @FunctionalInterface
    interface RowBody {
        /**
         * Runs one row.
         *
         * @param participant the index of the participant, from zero, the calling thread's
         * @param row the index of the row to run
         */
        void run(int participant, int row);
    }

    /**
     * The longest a waiting participant spins before it parks, in nanoseconds.
     */
    private static final long SPIN_NANOS = TimeUnit.MICROSECONDS.toNanos(10);

    /**
     * The mask of the spin iteration count at which a spinning participant reads the clock.
     */
    private static final int SPIN_CHECK_MASK = 15;

    /**
     * The distance in ints between the counters of consecutive rows: one cache line.
     */
    private static final int STRIDE = 16;

    /**
     * No tasks, the tasks of a {@link #run(int, int, int, RowBody)}.
     */
    private static final Runnable[] NO_TASKS = new Runnable[0];

    /**
     * The progress of each row at {@code row * STRIDE}, and at {@code row * STRIDE + 1} the
     * progress the participant parked on the row, or about to park, waits for, or {@code 0} when
     * none is.
     */
    private final AtomicIntegerArray progress;

    /**
     * The next row to claim.
     */
    private final AtomicInteger nextRow = new AtomicInteger();

    /**
     * Whether a participant failed.
     */
    private final AtomicBoolean aborted = new AtomicBoolean();

    /**
     * The tasks of the current run, claimed before the rows.
     */
    private volatile Runnable[] tasks = NO_TASKS;

    /**
     * The next unclaimed task.
     */
    private final AtomicInteger nextTask = new AtomicInteger();

    /**
     * The first failure of a task of the current run.
     */
    private final AtomicReference<Throwable> taskFailure = new AtomicReference<>();

    /**
     * The participant waiting for each row, written before the row's awaited progress is set and
     * read after it is seen set; a stale entry only grants that thread a spurious wake-up, after
     * which it checks its condition again.
     */
    private final Thread[] waiters;

    /**
     * The number of rows.
     */
    private final int rows;

    /**
     * The started helper participants of the current run.
     */
    private ParallelBands.Pending<?>[] helpers = new ParallelBands.Pending<?>[0];

    /**
     * Creates the wavefront of a frame.
     *
     * @param rows the number of macroblock rows of the frame
     */
    RowWavefront(int rows) {
        this.rows = rows;
        this.progress = new AtomicIntegerArray(rows * STRIDE);
        this.waiters = new Thread[rows];
    }

    /**
     * Sets the progress of a row before a run.
     *
     * @param row the index of the row
     * @param done the progress the row starts from
     */
    void reset(int row, int done) {
        progress.set(row * STRIDE, done);
        progress.set(row * STRIDE + 1, 0);
    }

    /**
     * Runs the rows from {@code firstRow} up to {@code endRow} and returns once all have run.
     *
     * @param participants the number of participants, the calling thread included, at least 1
     * @param firstRow the first row
     * @param endRow the row after the last
     * @param body the code of one row
     * @throws CancellationException if a participant failed and the failure was a cancellation
     */
    void run(int participants, int firstRow, int endRow, RowBody body) {
        run(participants, firstRow, endRow, body, NO_TASKS);
    }

    /**
     * Runs independent tasks, then the rows from {@code firstRow} up to {@code endRow}, and returns
     * once every task and row has run; a failure of a task is rethrown after the rows complete.
     *
     * <p>Every participant claims and runs tasks until none is left, then joins the rows, so each
     * task runs exactly once, on the calling thread when no helper starts in time.
     *
     * @param participants the number of participants, the calling thread included, at least 1
     * @param firstRow the first row
     * @param endRow the row after the last
     * @param body the code of one row
     * @param tasks the tasks, which share no state with each other or with the rows
     * @throws CancellationException if a participant failed and the failure was a cancellation
     */
    void run(int participants, int firstRow, int endRow, RowBody body, Runnable[] tasks) {
        aborted.set(false);
        nextRow.set(firstRow);
        taskFailure.set(null);
        nextTask.set(0);
        this.tasks = tasks;
        var count = participants - 1;
        if (helpers.length < count) {
            helpers = new ParallelBands.Pending<?>[count];
        }
        Throwable failure = null;
        try {
            for (var i = 0; i < count; i++) {
                var index = i + 1;
                helpers[i] = ParallelBands.start(() -> participate(index, endRow, body));
            }
            participate(0, endRow, body);
        } catch (RuntimeException | Error exception) {
            abort();
            failure = exception;
        }
        failure = finish(count, failure);
        this.tasks = NO_TASKS;
        if (failure == null) {
            failure = taskFailure.get();
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /**
     * Claims and runs tasks until none is left, then claims and runs rows until none is left.
     *
     * @param participant the index of the participant
     * @param endRow the row after the last
     * @param body the code of one row
     */
    private void participate(int participant, int endRow, RowBody body) {
        var work = tasks;
        for (var task = nextTask.getAndIncrement(); task < work.length; task = nextTask.getAndIncrement()) {
            try {
                work[task].run();
            } catch (RuntimeException | Error exception) {
                taskFailure.compareAndSet(null, exception);
            }
        }
        try {
            for (var row = nextRow.getAndIncrement(); row < endRow; row = nextRow.getAndIncrement()) {
                body.run(participant, row);
            }
        } catch (RuntimeException | Error exception) {
            abort();
            throw exception;
        }
    }

    /**
     * Cancels the helpers that no pool thread claimed, waits for the others, and returns the
     * failure to report: the calling thread's, unless it is a cancellation and a helper failed
     * otherwise.
     *
     * @param count the number of started helpers
     * @param failure the calling thread's failure, or {@code null}
     * @return the failure to report, or {@code null}
     */
    private Throwable finish(int count, Throwable failure) {
        for (var i = 0; i < count; i++) {
            if (helpers[i] != null && helpers[i].tryCancel()) {
                helpers[i] = null;
            }
        }
        for (var i = 0; i < count; i++) {
            var helper = helpers[i];
            if (helper == null) {
                continue;
            }
            helpers[i] = null;
            try {
                helper.join();
            } catch (RuntimeException | Error exception) {
                if (failure == null || failure instanceof CancellationException
                        && !(exception instanceof CancellationException)) {
                    failure = exception;
                }
            }
        }
        return failure;
    }

    /**
     * Makes every current and future wait of this run fail.
     */
    private void abort() {
        aborted.set(true);
        for (var row = 0; row < rows; row++) {
            if (progress.get(row * STRIDE + 1) != 0) {
                LockSupport.unpark(waiters[row]);
            }
        }
    }

    /**
     * Waits until a row has published at least the given progress and returns the progress it
     * observed.
     *
     * @param row the index of the row waited for
     * @param needed the progress to wait for, positive
     * @return the row's progress, at least {@code needed}
     * @throws CancellationException if the wavefront was aborted
     * @throws IllegalStateException if the thread is interrupted while parked
     */
    int await(int row, int needed) {
        var index = row * STRIDE;
        var value = progress.get(index);
        if (value >= needed) {
            return value;
        }
        var start = System.nanoTime();
        var spins = 0;
        while ((value = progress.get(index)) < needed) {
            if (aborted.get()) {
                throw new CancellationException("another vp8 encoder thread failed");
            }
            if ((++spins & SPIN_CHECK_MASK) == 0 && System.nanoTime() - start >= SPIN_NANOS) {
                return park(row, needed);
            }
            Thread.onSpinWait();
        }
        return value;
    }

    /**
     * Parks the calling participant until a row has published at least the given progress and
     * returns the progress it observed.
     *
     * <p>The participant records itself as the row's waiter and the progress it waits for before
     * it checks the progress again, and {@link #publish} sets the progress before it reads the
     * awaited one; both are volatile accesses, so either the participant sees the new progress or
     * the publication sees the awaited progress and unparks it once it is reached.
     *
     * @param row the index of the row waited for
     * @param needed the progress to wait for, positive
     * @return the row's progress, at least {@code needed}
     * @throws CancellationException if the wavefront was aborted
     * @throws IllegalStateException if the thread is interrupted while parked
     */
    private int park(int row, int needed) {
        var index = row * STRIDE;
        waiters[row] = Thread.currentThread();
        ParallelBands.beginBlocking();
        try {
            progress.set(index + 1, needed);
            int value;
            while ((value = progress.get(index)) < needed) {
                if (aborted.get()) {
                    throw new CancellationException("another vp8 encoder thread failed");
                }
                LockSupport.park(this);
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("interrupted while waiting for another vp8 encoder thread");
                }
            }
            return value;
        } finally {
            progress.set(index + 1, 0);
            ParallelBands.endBlocking();
        }
    }

    /**
     * Publishes a row's progress, unparking the participant parked on the row once the progress
     * reaches the one it waits for.
     *
     * @param row the index of the row
     * @param done the row's progress, which never decreases during a run
     */
    void publish(int row, int done) {
        var index = row * STRIDE;
        progress.set(index, done);
        var awaited = progress.get(index + 1);
        if (awaited != 0 && done >= awaited) {
            LockSupport.unpark(waiters[row]);
        }
    }
}
