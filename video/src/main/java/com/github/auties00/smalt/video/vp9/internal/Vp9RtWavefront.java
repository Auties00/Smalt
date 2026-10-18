/* Ports libvpx 1.17.0 (vp9/encoder/vp9_ethread.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Codes the superblock rows of every tile of a frame on several threads of the module's shared
 * pool, in a wavefront: a superblock is coded once the superblock above it, in the same tile,
 * is done.
 *
 * <p>Rows are claimed in raster order of superblock rows and, within a row, of tiles, so every
 * row a claimed row depends on was claimed before it. When the frame is also filtered, the
 * participant that completes the last tile of a superblock row filters the rows this makes ready
 * before it claims its next row: a row is ready once every tile has coded it and the row below it
 * (whose intra prediction reads the unfiltered bottom of the row), and the filters of consecutive
 * rows follow each other superblock by superblock as the decoder's do. The progress of the rows
 * is a {@link Vp9Wavefront} indexed by {@code sbRow * tiles + tile}. A failure in any participant
 * aborts the others.
 *
 * @implNote This implementation replaces libvpx's row multithreading
 * ({@code vp9_encode_tiles_row_mt} with {@code vp9_row_mt_sync_read} and
 * {@code vp9_row_mt_sync_write}) with the module's pool; superblocks only read their own column of
 * the row above, so a lag of one superblock suffices.
 */
final class Vp9RtWavefront {
    /**
     * The busy-wait iterations before a waiting participant blocks.
     */
    private static final int SPINS = 256;

    /**
     * The encoder whose rows are coded.
     */
    private final Vp9RtEncoder enc;

    /**
     * The superblocks each row has finished, by row index {@code sbRow * tiles + tile}.
     */
    private final Vp9Wavefront progress;

    /**
     * The next row to claim.
     */
    private final AtomicInteger nextJob = new AtomicInteger();

    /**
     * The helper participants of the current frame.
     */
    private ParallelBands.Pending<?>[] participants = new ParallelBands.Pending<?>[0];

    /**
     * The number of tiles of the current frame.
     */
    private int tiles;

    /**
     * Whether the current frame's rows are also filtered.
     */
    private boolean filter;

    /**
     * The number of tiles that have coded each superblock row of the current frame.
     */
    private final AtomicIntegerArray tilesDone;

    /**
     * Whether a participant has taken the loop filter of each superblock row of the current frame.
     */
    private final AtomicIntegerArray filterTaken;

    /**
     * The progress value of a fully coded row.
     */
    static final int ROW_DONE = Integer.MAX_VALUE >> 1;

    /**
     * Constructs a wavefront.
     *
     * @param enc the encoder
     * @param capacity the largest number of rows, tiles times superblock rows
     */
    Vp9RtWavefront(Vp9RtEncoder enc, int capacity) {
        this.enc = enc;
        this.progress = new Vp9Wavefront(capacity, SPINS, "vp9 encoder thread", true);
        this.tilesDone = new AtomicIntegerArray(enc.sbRows);
        this.filterTaken = new AtomicIntegerArray(enc.sbRows);
    }

    /**
     * Codes every row of every tile and, when asked and the frame is coded on several threads,
     * filters every superblock row.
     *
     * @param coders the coders, one per participant, the first for the calling thread
     * @param tiles the number of tile columns
     * @param filter whether to filter the rows too, with the levels the encoder has set
     * @return whether the rows were filtered
     */
    boolean run(Vp9RtCoder[] coders, int tiles, boolean filter) {
        this.tiles = tiles;
        var rows = enc.sbRows * tiles;
        var helpers = 0;
        if (coders.length > 1 && rows > 1 && ParallelBands.enabled()) {
            helpers = Math.min(coders.length - 1, ParallelBands.available());
        }
        if (helpers == 0) {
            for (var job = 0; job < rows; job++) {
                enc.codeRow(coders[0], job % tiles, job / tiles, null);
                ParallelBands.checkpoint();
            }
            return false;
        }
        this.filter = filter;
        var jobs = rows;
        progress.reset(rows);
        for (var row = 0; row < enc.sbRows; row++) {
            tilesDone.set(row, 0);
            filterTaken.set(row, 0);
        }
        nextJob.set(0);
        if (participants.length < helpers) {
            participants = new ParallelBands.Pending<?>[helpers];
        }
        Throwable failure = null;
        try {
            for (var i = 0; i < helpers; i++) {
                var coder = coders[i + 1];
                var participant = i + 1;
                participants[i] = ParallelBands.start(() -> participate(coder, participant, jobs));
            }
            participate(coders[0], 0, jobs);
        } catch (RuntimeException | Error exception) {
            abort();
            failure = exception;
        }
        failure = finish(helpers, failure);
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return filter;
    }

    /**
     * Returns the index of a row in {@link #progress}.
     *
     * @param tile the tile column
     * @param sbRow the superblock row
     * @return {@code sbRow * tiles + tile}
     */
    private int index(int tile, int sbRow) {
        return sbRow * tiles + tile;
    }

    /**
     * Claims and codes rows until none is left, filtering the superblock rows each completed row
     * makes ready.
     *
     * @param coder the participant's coder
     * @param participant the index of the participant's coder
     * @param jobs the number of rows
     */
    private void participate(Vp9RtCoder coder, int participant, int jobs) {
        try {
            for (var job = nextJob.getAndIncrement(); job < jobs; job = nextJob.getAndIncrement()) {
                var row = job / tiles;
                enc.codeRow(coder, job % tiles, row, this);
                if (filter && tilesDone.incrementAndGet(row) == tiles) {
                    filterReady(participant, row - 1);
                    filterReady(participant, row);
                }
            }
        } catch (RuntimeException | Error exception) {
            abort();
            throw exception;
        }
    }

    /**
     * Filters a superblock row if every tile has coded it and the row below it and no other
     * participant has taken it.
     *
     * @param participant the index of the calling thread's coder
     * @param sbRow the superblock row, or -1 for none
     * @throws CancellationException if another participant failed
     */
    private void filterReady(int participant, int sbRow) {
        if (sbRow < 0 || tilesDone.get(sbRow) != tiles) {
            return;
        }
        if (sbRow + 1 < enc.sbRows && tilesDone.get(sbRow + 1) != tiles) {
            return;
        }
        if (filterTaken.compareAndSet(sbRow, 0, 1)) {
            enc.filterRow(participant, sbRow);
        }
    }

    /**
     * Waits for the helpers, canceling those that never started, and merges their failures.
     *
     * @param helpers the number of helpers
     * @param failure the calling thread's failure, or {@code null}
     * @return the failure to rethrow, or {@code null}
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
     * Aborts the frame, waking every waiting participant.
     */
    private void abort() {
        progress.abort();
        if (filter) {
            enc.abortFilter();
        }
    }

    /**
     * Waits until a row has finished a number of superblocks.
     *
     * @param tile the row's tile
     * @param sbRow the row
     * @param needed the number of superblocks
     * @throws CancellationException if another participant failed
     * @throws IllegalStateException if the thread is interrupted while blocked
     */
    void await(int tile, int sbRow, int needed) {
        progress.awaitProgress(index(tile, sbRow), needed);
    }

    /**
     * Records that a row has finished a number of superblocks.
     *
     * @param tile the row's tile
     * @param sbRow the row
     * @param done the number of superblocks finished
     */
    void publish(int tile, int sbRow, int done) {
        progress.publish(index(tile, sbRow), done);
    }
}
