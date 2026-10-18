package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.internal.task.RowProgress;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

/**
 * Tracks the superblocks the reconstruction pass of two-pass decoding has completed in each
 * superblock row of a frame, so that consecutive rows are reconstructed concurrently as a
 * wavefront: a row reconstructs a superblock once the row above has reconstructed the superblock
 * above and to the right of it, which holds every sample, motion record and context its
 * reconstruction reads from the row above.
 *
 * <p>It also tracks the superblocks the parsing pass has recorded in each tile superblock row,
 * with the record array holding them, so that a row may be reconstructed while it is still being
 * parsed: a superblock is reconstructed once the parsing pass has recorded it
 * ({@link #awaitParsed}).
 *
 * <p>Each row is reconstructed by one thread, and waits only for the row above it, through a
 * {@link RowProgress}, and for the parsing of its own superblocks.
 *
 * @implNote This implementation wakes a row waiting for parsed superblocks as soon as they are
 * published; the timed park only bounds a missed wake-up.
 */
final class ReconWavefront {
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
     * The number of superblocks each row has reconstructed, by superblock row.
     */
    private final RowProgress progress = new RowProgress();

    /**
     * The thread parked waiting for the parsed superblocks of each row, or {@code null} entries.
     */
    private AtomicReferenceArray<Thread> waiters = new AtomicReferenceArray<>(0);

    /**
     * The number of superblocks the parsing pass has recorded, by tile superblock row
     * ({@code sby * tilingCols + tileCol}).
     */
    private AtomicIntegerArray parsed = new AtomicIntegerArray(0);

    /**
     * The record array of each tile superblock row, published with {@link #parsed}.
     */
    private AtomicReferenceArray<int[]> records = new AtomicReferenceArray<>(0);

    /**
     * Whether the parsing pass of the frame stopped before recording every superblock.
     */
    private volatile boolean parseAborted;

    /**
     * The number of superblocks of a row.
     */
    private int width;

    /**
     * The superblocks of the row above a superblock that must be reconstructed first, beyond the
     * one above it: 1 for the one above and to the right, or the whole row.
     */
    private int lag;

    /**
     * Constructs an empty tracker.
     */
    ReconWavefront() {
    }

    /**
     * Prepares the tracker for a frame, with no superblock reconstructed; called before any row
     * starts.
     *
     * @param rows the number of superblock rows
     * @param width the number of superblocks of a row
     * @param sequential whether each row must wait for the whole row above, as for intra block
     * copy, whose prediction may read any reconstructed sample above
     * @param tileRows the number of tile superblock rows, superblock rows times tile columns
     */
    void begin(int rows, int width, boolean sequential, int tileRows) {
        progress.begin(rows);
        if (waiters.length() < rows) {
            waiters = new AtomicReferenceArray<>(rows);
        }
        if (parsed.length() < tileRows) {
            parsed = new AtomicIntegerArray(tileRows);
            records = new AtomicReferenceArray<>(tileRows);
        } else {
            for (var i = 0; i < tileRows; i++) {
                parsed.set(i, 0);
            }
        }
        parseAborted = false;
        this.width = width;
        this.lag = sequential ? width : 1;
    }

    /**
     * Publishes the superblocks the parsing pass has recorded in a tile superblock row, waking
     * the row's reconstruction when it waits for them.
     *
     * @param row the superblock row
     * @param idx the tile superblock row, {@code row * tilingCols + tileCol}
     * @param recs the record array holding them, which later superblocks may replace
     * @param done the number of superblocks recorded
     */
    void advanceParsed(int row, int idx, int[] recs, int done) {
        records.set(idx, recs);
        parsed.set(idx, done);
        var waiter = waiters.get(row);
        if (waiter != null) {
            LockSupport.unpark(waiter);
        }
    }

    /**
     * Ends the parsing pass of the frame; when it stopped early, releases every reconstruction
     * waiting for superblocks that will never be recorded.
     *
     * @param aborted whether the parsing stopped before recording every superblock
     */
    void endParse(boolean aborted) {
        if (!aborted) {
            return;
        }
        parseAborted = true;
        for (var i = 0; i < waiters.length(); i++) {
            var waiter = waiters.get(i);
            if (waiter != null) {
                LockSupport.unpark(waiter);
            }
        }
    }

    /**
     * Waits until the parsing pass has recorded the first {@code needed} superblocks of a tile
     * superblock row.
     *
     * @param row the superblock row
     * @param idx the tile superblock row
     * @param needed the number of superblocks
     * @return {@code false} when the parsing stopped before recording them
     */
    boolean awaitParsed(int row, int idx, int needed) {
        if (parsed.get(idx) >= needed) {
            return true;
        }
        for (var spin = 0; spin < SPINS; spin++) {
            if (parsed.get(idx) >= needed) {
                return true;
            }
            if (parseAborted) {
                return false;
            }
            Thread.onSpinWait();
        }
        ParallelBands.beginBlocking();
        waiters.set(row, Thread.currentThread());
        try {
            while (parsed.get(idx) < needed) {
                if (parseAborted) {
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
     * Returns the record array of a tile superblock row as last published with its recorded
     * superblocks.
     *
     * @param idx the tile superblock row
     * @return the records
     */
    int[] records(int idx) {
        return records.get(idx);
    }

    /**
     * Waits until the row above has reconstructed the superblocks a superblock depends on.
     *
     * @param row the superblock row
     * @param x the superblock column
     */
    void awaitAbove(int row, int x) {
        if (row != 0) {
            progress.await(row - 1, Math.min(x + 1 + lag, width));
        }
    }

    /**
     * Publishes the number of superblocks a row has reconstructed, waking the row below when it
     * waits.
     *
     * @param row the superblock row
     * @param done the number of its reconstructed superblocks
     */
    void advance(int row, int done) {
        progress.advance(row, done);
    }

    /**
     * Marks a row as complete, releasing the row below whatever it waits for; used when the row
     * stops early.
     *
     * @param row the superblock row
     */
    void complete(int row) {
        advance(row, Integer.MAX_VALUE);
    }
}
