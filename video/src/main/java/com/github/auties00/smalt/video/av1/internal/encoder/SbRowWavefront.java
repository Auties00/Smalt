package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs the superblock rows of the tiles of a frame of the real-time AV1 encoder in a wavefront:
 * the calling thread and helpers of the module's shared pool claim rows in order, and a superblock
 * waits until the row above it in the same tile has encoded the superblock above and to its right.
 *
 * <p>Rows are units numbered in the order they are claimed: superblock row by superblock row, the
 * tile columns of a row left to right, so every unit a unit waits for was claimed before it.
 * Progress is published per unit with volatile writes and read with volatile reads, so every
 * sample, context and motion record a row wrote before publishing is visible to the row that
 * waited for it. Every unit a participant waits for belongs to a participant that is running it,
 * so the frame completes even when no helper is free. A participant that fails aborts the
 * wavefront, which makes every waiting participant give up instead of waiting forever.
 *
 * @implNote This implementation follows the VP8 encoder's macroblock row wavefront: every unit's
 * counter sits on its own 64-byte cache line, a waiting participant spins briefly, then
 * blocks on a condition of the unit, declaring the wait to the shared pool with
 * {@link ParallelBands#beginBlocking()} so another helper may run posted work. The
 * dependency on the superblock above and to the right is libaom's row-based
 * multithreading synchronization ({@code av1/encoder/ethread.c}:
 * {@code av1_row_mt_sync_read} with {@code nsync} of one superblock).
 */
final class SbRowWavefront {
    /**
     * The number of busy-wait iterations before a waiting participant blocks.
     */
    private static final int SPINS = 256;

    /**
     * The distance in ints between the counters of consecutive units: one cache line.
     */
    private static final int STRIDE = 16;

    /**
     * Encodes one unit on a participant.
     */
    @FunctionalInterface
    interface UnitCoder {
        /**
         * Encodes a unit.
         *
         * @param participant the participant's index, 0 for the calling thread
         * @param unit the unit index
         */
        void code(int participant, int unit);
    }

    /**
     * The progress of each unit at {@code unit * STRIDE}, and the number of participants blocked
     * on the unit at {@code unit * STRIDE + 1}.
     */
    private AtomicIntegerArray progress = new AtomicIntegerArray(0);

    /**
     * The condition of each unit, signaled when the unit publishes while a participant waits for
     * it.
     */
    private Condition[] published = new Condition[0];

    /**
     * The number of units of the current frame.
     */
    private int units;

    /**
     * The next unit to claim.
     */
    private final AtomicInteger nextUnit = new AtomicInteger();

    /**
     * Whether a participant failed.
     */
    private final AtomicBoolean aborted = new AtomicBoolean();

    /**
     * The lock blocked participants wait under.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * The started helper participants of the current frame.
     */
    private ParallelBands.Pending<?>[] participants = new ParallelBands.Pending<?>[0];

    /**
     * Creates a wavefront.
     */
    SbRowWavefront() {
    }

    /**
     * The number of progress slots of the current frame: the units and the extra slots.
     */
    private int slots;

    /**
     * The progress a unit publishes once it is completely encoded.
     */
    static final int DONE = Integer.MAX_VALUE;

    /**
     * Encodes every unit of the current frame and returns once all are encoded, leaving a
     * follower computation running.
     *
     * <p>The follower starts alongside the units and may wait for them with {@link #await}; it may
     * still be running when this method returns, and the caller joins it through the returned
     * handle. When a participant fails, the follower is canceled or joined before the failure is
     * rethrown.
     *
     * <p>Progress slots past the units, which no unit encoder claims, let the unit encoders
     * publish and await other work of the frame, as the units publish their progress.
     *
     * @param units the number of units
     * @param extra the number of progress slots past the units
     * @param participants the most participants, the calling thread included
     * @param coder the unit encoder
     * @param follower the task that consumes the completed units, run on another thread
     * @return the follower's handle
     * @throws CancellationException if a participant failed and the failure was a cancellation
     */
    ParallelBands.Pending<Void> run(int units, int extra, int participants, UnitCoder coder, Runnable follower) {
        ensure(units + extra);
        for (var unit = 0; unit < units + extra; unit++) {
            progress.set(unit * STRIDE, 0);
        }
        this.units = units;
        this.slots = units + extra;
        aborted.set(false);
        nextUnit.set(0);
        var helpers = 0;
        if (participants > 1 && units > 1 && ParallelBands.enabled()) {
            helpers = Math.min(participants - 1, ParallelBands.available());
        }
        if (helpers == 0) {
            for (var unit = 0; unit < units; unit++) {
                coder.code(0, unit);
                ParallelBands.checkpoint();
            }
            return ParallelBands.start(follower);
        }
        if (this.participants.length < helpers) {
            this.participants = new ParallelBands.Pending<?>[helpers];
        }
        Throwable failure = null;
        ParallelBands.Pending<Void> following = null;
        try {
            for (var i = 0; i < helpers; i++) {
                var index = i + 1;
                this.participants[i] = ParallelBands.start(() -> participate(index, coder));
            }
            following = ParallelBands.start(follower);
            participate(0, coder);
        } catch (RuntimeException | Error exception) {
            abort();
            failure = exception;
        }
        failure = finish(helpers, failure);
        if (failure == null) {
            return following;
        }
        if (following != null && !following.tryCancel()) {
            try {
                following.join();
            } catch (RuntimeException | Error ignored) {
                // the follower fails on the aborted wavefront; the participant's failure is the one reported
            }
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw (Error) failure;
    }

    /**
     * Grows the progress counters and conditions to hold a number of units.
     *
     * @param units the number of units
     */
    private void ensure(int units) {
        if (published.length >= units) {
            return;
        }
        progress = new AtomicIntegerArray(units * STRIDE);
        published = new Condition[units];
        for (var unit = 0; unit < units; unit++) {
            published[unit] = lock.newCondition();
        }
    }

    /**
     * Claims and encodes units until none is left.
     *
     * @param participant the participant's index
     * @param coder the unit encoder
     */
    private void participate(int participant, UnitCoder coder) {
        try {
            for (var unit = nextUnit.getAndIncrement(); unit < units; unit = nextUnit.getAndIncrement()) {
                coder.code(participant, unit);
            }
        } catch (RuntimeException | Error exception) {
            abort();
            throw exception;
        }
    }

    /**
     * Cancels the participants no helper claimed, waits for the others, and returns the failure
     * to report.
     *
     * @param helpers the number of started participants
     * @param failure the calling thread's failure, or {@code null}
     * @return the failure to report, or {@code null}
     */
    private Throwable finish(int helpers, Throwable failure) {
        for (var i = 0; i < helpers; i++) {
            if (participants[i] != null && participants[i].tryCancel()) {
                participants[i] = null;
            }
        }
        for (var i = 0; i < helpers; i++) {
            var participant = participants[i];
            if (participant == null) {
                continue;
            }
            participants[i] = null;
            try {
                participant.join();
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
     * Makes every current and future wait of this frame fail.
     */
    private void abort() {
        aborted.set(true);
        lock.lock();
        try {
            for (var unit = 0; unit < slots; unit++) {
                published[unit].signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until a unit has published at least the given progress. When the units run in order on
     * the calling thread, the unit is already complete and this returns at once.
     *
     * @param unit the unit index
     * @param needed the number of encoded superblocks to wait for
     * @throws CancellationException if the wavefront was aborted
     * @throws IllegalStateException if the thread is interrupted while blocked
     */
    void await(int unit, int needed) {
        var index = unit * STRIDE;
        var spins = 0;
        while (progress.get(index) < needed) {
            if (aborted.get()) {
                throw new CancellationException("another av1 encoder thread failed");
            }
            if (spins >= SPINS) {
                block(unit, needed);
                return;
            }
            Thread.onSpinWait();
            spins++;
        }
    }

    /**
     * Blocks until a unit has published at least the given progress.
     *
     * @param unit the unit index
     * @param needed the progress to wait for
     * @throws CancellationException if the wavefront was aborted
     * @throws IllegalStateException if the thread is interrupted while blocked
     */
    private void block(int unit, int needed) {
        var index = unit * STRIDE;
        ParallelBands.beginBlocking();
        progress.incrementAndGet(index + 1);
        lock.lock();
        try {
            while (progress.get(index) < needed) {
                if (aborted.get()) {
                    throw new CancellationException("another av1 encoder thread failed");
                }
                published[unit].await();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for another av1 encoder thread", exception);
        } finally {
            lock.unlock();
            progress.decrementAndGet(index + 1);
            ParallelBands.endBlocking();
        }
    }

    /**
     * Publishes that a unit is completely encoded.
     *
     * @param unit the unit index
     */
    void complete(int unit) {
        publish(unit, DONE);
    }

    /**
     * Publishes a unit's progress, waking the participants blocked on the unit.
     *
     * @param unit the unit index
     * @param done the number of encoded superblocks of the unit
     */
    void publish(int unit, int done) {
        var index = unit * STRIDE;
        progress.set(index, done);
        if (progress.get(index + 1) > 0) {
            lock.lock();
            try {
                published[unit].signalAll();
            } finally {
                lock.unlock();
            }
        }
    }
}
