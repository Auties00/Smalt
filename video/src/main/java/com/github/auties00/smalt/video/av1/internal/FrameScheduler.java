package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Decodes the frames of one decoder in two passes ({@link BlockRecords}) as tasks run by the
 * module's shared helper threads and by the decoding thread while it waits for a frame.
 *
 * <p>Each frame has four kinds of task: one parses the whole frame, superblock row by superblock
 * row; one per row reconstructs it; one per row deblocks it; one per row runs its remaining
 * in-loop filters. A task is queued only once what it reads is ready:
 * <ul>
 *   <li>a frame is prepared for parsing once the frame that produced the CDFs and segmentation map
 *       it starts from is parsed; then each band of its tile rows parses its superblock rows one
 *       task each, a row once each frame whose saved motion the frame projects has parsed the same
 *       row, so that a frame follows those frames closely instead of waiting for their end;</li>
 *   <li>a row is reconstructed once it is parsed and the rows its inter blocks read from the
 *       reference frames are final; consecutive rows of a frame are reconstructed concurrently,
 *       each following the row above as a wavefront ({@link ReconWavefront}). When every
 *       reference is final and one thread parses the frame in order
 *       ({@link FrameDecoder#earlyReconstruction}), a row is instead handed to reconstruction as
 *       its parsing starts and follows it superblock by superblock;</li>
 *   <li>a row is deblocked once it is reconstructed and the row above is deblocked;</li>
 *   <li>a row is post-filtered once it is deblocked, in any order.</li>
 * </ul>
 * The rows a frame's last step completes in order are published on its picture
 * ({@link Av1Picture#progress}) for the frames that reference it. The parsing tasks come first, being
 * the critical path, then the tasks of the oldest frames, upper rows first.
 *
 * <p>No task ever waits for a task that has not started: the only task that waits is the
 * reconstruction of a row, for the row above, which was queued and therefore started before it,
 * and for the parsing of its own superblocks, which queued it and is therefore running.
 * Tasks therefore may run in any thread, nested in any wait of the module's pool, without risk
 * of deadlock. Every output depends only on the frames, never on the scheduling.
 *
 * @implNote This implementation follows the task threading of dav1d ({@code src/thread_task.c}):
 * the task types, their dependencies, the priority of entropy decoding and the
 * reference row checks with an 8-row allowance for the in-loop filters of the
 * references. Unlike dav1d, which reconstructs the rows of a tile one after the other,
 * the rows of a frame are reconstructed as a wavefront, so that a frame decoded on its
 * own scales with the threads too.
 */
final class FrameScheduler {
    /**
     * The longest a worker loop runs tasks before it gives its helper thread back to the module's
     * pool, in nanoseconds.
     */
    private static final long WORKER_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(4);

    /**
     * The task that parses a frame: its preparation, or one superblock row of one of its bands.
     */
    private static final int PARSE = 0;

    /**
     * The row of the task that prepares the parsing of a frame.
     */
    private static final int HEAD = -1;

    /**
     * The task that reconstructs a superblock row.
     */
    private static final int RECON = 1;

    /**
     * The task that deblocks a superblock row.
     */
    private static final int DEBLOCK = 2;

    /**
     * The task that post-filters a superblock row.
     */
    private static final int POST = 3;

    /**
     * One queued task.
     */
    private static final class Task {
        /**
         * The frame.
         */
        FrameJob job;

        /**
         * The kind, one of {@link #PARSE}, {@link #RECON}, {@link #DEBLOCK} and {@link #POST}.
         */
        int kind;

        /**
         * The superblock row, or for {@link #PARSE} the band, {@link #HEAD} for the preparation.
         */
        int row;

        /**
         * The priority, lower first.
         */
        long key;
    }

    /**
     * Guards every field and the scheduling state of the jobs.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Signaled when a task is queued or a frame is done.
     */
    private final Condition changed = lock.newCondition();

    /**
     * The queued tasks, as a binary heap on {@link Task#key}.
     */
    private Task[] heap = new Task[64];

    /**
     * The number of queued tasks.
     */
    private int size;

    /**
     * The task objects not in use.
     */
    private final ArrayDeque<Task> freeTasks = new ArrayDeque<>();

    /**
     * The frames submitted and not yet done, in decoding order.
     */
    private final ArrayDeque<FrameJob> jobs = new ArrayDeque<>();

    /**
     * The number of worker loops started and not yet ended.
     */
    private int workers;

    /**
     * The number of worker loops running a task, which take no queued task until it ends.
     */
    private int busy;

    /**
     * The most worker loops: one per helper thread of the module.
     */
    private final int maxWorkers = ParallelBands.enabled() ? ParallelBands.THREADS - 1 : 0;

    /**
     * The worker loops to start once the lock is released.
     */
    private int toStart;

    /**
     * The number of threads waiting in {@link #await(FrameJob)}.
     */
    private int waiting;

    /**
     * The worker loop.
     */
    private final Runnable workLoop = this::work;

    /**
     * Creates a scheduler with no frame.
     */
    FrameScheduler() {
    }

    /**
     * Submits a frame prepared for two-pass decoding; called by the decoding thread, in decoding
     * order.
     *
     * @param job the frame, with {@link FrameJob#resetSchedule()} called
     */
    void submit(FrameJob job) {
        lock.lock();
        try {
            jobs.addLast(job);
            queueParse(job);
        } finally {
            unlockAndStart();
        }
    }

    /**
     * Waits until a frame is done, running queued tasks meanwhile.
     *
     * @param job the frame
     */
    void await(FrameJob job) {
        lock.lock();
        try {
            while (!job.done) {
                var task = poll();
                if (task != null) {
                    lock.unlock();
                    try {
                        run(task, false);
                    } finally {
                        lock.lock();
                    }
                    continue;
                }
                waiting++;
                changed.awaitUninterruptibly();
                waiting--;
            }
        } finally {
            unlockAndStart();
        }
    }

    /**
     * Runs queued tasks until none is left or {@link #WORKER_SLICE_NANOS} have passed, then, if
     * tasks are left, posts the loop again behind the work queued on the module's pool meanwhile;
     * the body of the worker loops.
     *
     * <p>Frames in flight keep tasks queued for as long as a stream decodes, so a loop that ran
     * until the queue is empty would hold its helper thread for the whole stream, and the work
     * that other codecs and decoders post on the pool would wait for it.
     */
    private void work() {
        var start = System.nanoTime();
        while (true) {
            Task task;
            lock.lock();
            try {
                task = poll();
                if (task == null) {
                    workers--;
                    return;
                }
                busy++;
            } finally {
                lock.unlock();
            }
            run(task, true);
            if (System.nanoTime() - start >= WORKER_SLICE_NANOS) {
                lock.lock();
                if (size > 0) {
                    toStart++;
                } else {
                    workers--;
                }
                unlockAndStart();
                return;
            }
        }
    }

    /**
     * Releases the lock and starts the worker loops queued tasks asked for.
     */
    private void unlockAndStart() {
        var n = toStart;
        toStart = 0;
        lock.unlock();
        for (var i = 0; i < n; i++) {
            ParallelBands.start(workLoop);
        }
    }

    /**
     * Runs one task and records its completion.
     *
     * @param task the task, no longer queued
     * @param worker whether a worker loop runs it, counted in {@link #busy}
     */
    private void run(Task task, boolean worker) {
        var job = task.job;
        var row = task.row;
        switch (task.kind) {
            case PARSE -> parse(job, row);
            case RECON -> reconstruct(job, row);
            case DEBLOCK -> deblock(job, row);
            default -> postFilter(job, row);
        }
        lock.lock();
        try {
            if (worker) {
                busy--;
            }
            task.job = null;
            freeTasks.push(task);
            job.pending--;
            finishIfDone(job);
        } finally {
            unlockAndStart();
        }
    }

    /**
     * Runs a parsing task: the preparation of a frame, or one superblock row of a band of it.
     *
     * @param job the frame
     * @param band the band, or {@link #HEAD} for the preparation
     */
    private void parse(FrameJob job, int band) {
        if (band == HEAD) {
            prepareParse(job);
        } else {
            parseBand(job, band);
        }
    }

    /**
     * Prepares the parsing of a frame: its tiles, the contexts of its bands and its reconstruction
     * wavefront; then lets the bands parse.
     *
     * @param job the frame
     */
    private void prepareParse(FrameJob job) {
        var f = job.f;
        MediaException failure = null;
        try {
            var contexts = job.parseContexts();
            job.initTiles();
            FrameDecoder.beginTwoPass(f, contexts);
            var early = FrameDecoder.earlyReconstruction(f);
            lock.lock();
            try {
                job.early = early;
            } finally {
                lock.unlock();
            }
        } catch (MediaException exception) {
            failure = exception;
        } catch (RuntimeException exception) {
            failure = new MediaException.Malformed("corrupt av1 packet", exception);
        }
        var empty = false;
        lock.lock();
        try {
            if (failure != null) {
                fail(job, failure);
                job.parseEnded = true;
            } else {
                job.headDone = true;
                for (var band = 0; band < job.bands; band++) {
                    var first = firstRow(job, band);
                    job.bandNext[band] = first;
                    if (first >= 0) {
                        job.bandsLeft++;
                    }
                }
                empty = job.bandsLeft == 0;
            }
            for (var next : jobs) {
                queueParse(next);
            }
        } finally {
            unlockAndStart();
        }
        if (failure != null) {
            f.wavefront.endParse(true);
        } else if (empty) {
            endParse(job);
        }
    }

    /**
     * Parses the next superblock row of a band of a frame, queues the reconstruction it allows and
     * ends the parsing of the frame when it was the last row of the last band.
     *
     * @param job the frame
     * @param band the index of the band of tile rows
     */
    private void parseBand(FrameJob job, int band) {
        var f = job.f;
        var sby = job.bandNext[band];
        MediaException failure = null;
        var valid = true;
        try {
            var contexts = job.parseContexts();
            var first = band * FrameDecoder.decodeBands(f);
            if (job.early) {
                lock.lock();
                try {
                    job.parseBegun = sby + 1;
                    queueReconstruction(job);
                } finally {
                    unlockAndStart();
                }
            }
            if (job.failed || !FrameDecoder.parseRow(f, contexts, first, sby)) {
                valid = job.failed;
            } else {
                lock.lock();
                try {
                    job.parsedRows[sby] = true;
                    while (job.parsed < job.rows && job.parsedRows[job.parsed]) {
                        job.parsed++;
                    }
                    queueReconstruction(job);
                } finally {
                    unlockAndStart();
                }
            }
        } catch (MediaException exception) {
            failure = exception;
        } catch (RuntimeException exception) {
            failure = new MediaException.Malformed("corrupt av1 packet", exception);
        }
        var last = false;
        lock.lock();
        try {
            if (failure == null && !valid) {
                failure = new MediaException.Malformed("av1: corrupt tile data");
            }
            if (failure != null) {
                fail(job, failure);
            }
            var next = job.failed ? -1 : successor(job, sby);
            if (next >= 0) {
                job.bandNext[band] = next;
                job.bandQueued[band] = false;
            } else {
                job.bandNext[band] = -1;
                last = --job.bandsLeft == 0;
            }
            for (var other : jobs) {
                queueParse(other);
            }
        } finally {
            unlockAndStart();
        }
        if (last) {
            endParse(job);
        }
    }

    /**
     * Ends the parsing of a frame once every band parsed its last row or stopped: stores the CDFs
     * the next frames start from and releases the frames that wait for the parsing.
     *
     * @param job the frame
     */
    private void endParse(FrameJob job) {
        var f = job.f;
        MediaException failure = null;
        try {
            if (!job.failed && f.frameHdr.refreshContext) {
                job.storeCdfs();
            }
        } catch (MediaException exception) {
            failure = exception;
        } catch (RuntimeException exception) {
            failure = new MediaException.Malformed("corrupt av1 packet", exception);
        }
        boolean aborted;
        lock.lock();
        try {
            if (failure != null) {
                fail(job, failure);
            }
            aborted = job.failed;
            job.parseEnded = true;
            for (var next : jobs) {
                queueParse(next);
            }
        } finally {
            unlockAndStart();
        }
        f.wavefront.endParse(aborted);
    }

    /**
     * Returns the first superblock row a band parses.
     *
     * @param job the frame
     * @param band the index of the band of tile rows
     * @return the row, or -1 when the band has no row
     */
    private static int firstRow(FrameJob job, int band) {
        var hdr = job.f.frameHdr;
        for (var tileRow = band; tileRow < hdr.tilingRows; tileRow += job.bands) {
            var start = hdr.rowStartSb[tileRow];
            if (start < Math.min(hdr.rowStartSb[tileRow + 1], job.rows)) {
                return start;
            }
        }
        return -1;
    }

    /**
     * Returns the superblock row a band parses after a row.
     *
     * @param job the frame
     * @param sby the row
     * @return the row, or -1 when it was the band's last
     */
    private static int successor(FrameJob job, int sby) {
        var hdr = job.f.frameHdr;
        var tileRow = FrameDecoder.tileRow(job.f, sby);
        if (sby + 1 < Math.min(hdr.rowStartSb[tileRow + 1], job.rows)) {
            return sby + 1;
        }
        for (var next = tileRow + job.bands; next < hdr.tilingRows; next += job.bands) {
            var start = hdr.rowStartSb[next];
            if (start < Math.min(hdr.rowStartSb[next + 1], job.rows)) {
                return start;
            }
        }
        return -1;
    }

    /**
     * Reconstructs a superblock row and queues its deblocking.
     *
     * @param job the frame
     * @param row the superblock row
     */
    private void reconstruct(FrameJob job, int row) {
        var f = job.f;
        MediaException failure = null;
        var t = f.takeReconContext();
        try {
            if (!job.failed) {
                FrameDecoder.reconRow(f, t, row);
            }
        } catch (MediaException exception) {
            failure = exception;
        } catch (RuntimeException exception) {
            failure = new MediaException.Malformed("corrupt av1 packet", exception);
        } finally {
            f.wavefront.complete(row);
            f.returnReconContext(t);
        }
        lock.lock();
        try {
            if (failure != null) {
                fail(job, failure);
            }
            job.reconDone[row] = true;
            while (job.reconstructed < job.rows && job.reconDone[job.reconstructed]) {
                job.reconstructed++;
            }
            if (job.deblocks) {
                queueDeblocking(job);
            } else {
                advanceFinal(job, job.reconstructed);
            }
        } finally {
            unlockAndStart();
        }
    }

    /**
     * Deblocks a superblock row and queues the deblocking of the next row and the post-filtering
     * of this one.
     *
     * @param job the frame
     * @param row the superblock row
     */
    private void deblock(FrameJob job, int row) {
        MediaException failure = null;
        try {
            if (!job.failed) {
                FrameDecoder.deblockRow(job.f, row);
            }
        } catch (MediaException exception) {
            failure = exception;
        } catch (RuntimeException exception) {
            failure = new MediaException.Malformed("corrupt av1 packet", exception);
        }
        lock.lock();
        try {
            if (failure != null) {
                fail(job, failure);
            }
            job.deblocked = row + 1;
            job.deblockBusy = false;
            if (job.postFilters) {
                while (!job.failed && job.postNext < job.deblocked) {
                    queue(job, POST, job.postNext++);
                }
            } else {
                advanceFinal(job, job.deblocked);
            }
            queueDeblocking(job);
        } finally {
            unlockAndStart();
        }
    }

    /**
     * Post-filters a superblock row.
     *
     * @param job the frame
     * @param row the superblock row
     */
    private void postFilter(FrameJob job, int row) {
        var f = job.f;
        MediaException failure = null;
        var t = f.takeReconContext();
        try {
            if (!job.failed) {
                FrameDecoder.postFilterRow(t, f, row);
            }
        } catch (MediaException exception) {
            failure = exception;
        } catch (RuntimeException exception) {
            failure = new MediaException.Malformed("corrupt av1 packet", exception);
        } finally {
            f.returnReconContext(t);
        }
        lock.lock();
        try {
            if (failure != null) {
                fail(job, failure);
            }
            job.postDone[row] = true;
            var rows = job.finalRows;
            while (rows < job.rows && job.postDone[rows]) {
                rows++;
            }
            advanceFinal(job, rows);
        } finally {
            unlockAndStart();
        }
    }

    /**
     * Queues the parsing tasks of a frame that are ready and not queued or running; called with the
     * lock held. The preparation of the frame follows the parsing of the frame that produced its
     * CDFs; then each band parses its next superblock row once each frame whose saved motion the
     * frame projects has parsed the same row. A frame that is not in flight any more is parsed.
     *
     * @param job the frame
     */
    private void queueParse(FrameJob job) {
        if (job.parseEnded) {
            return;
        }
        if (!job.headDone) {
            if (job.parseQueued) {
                return;
            }
            for (var other : jobs) {
                if (other != job && !other.parseEnded && other.frame == job.cdfDep) {
                    return;
                }
            }
            job.parseQueued = true;
            queue(job, PARSE, HEAD);
            return;
        }
        for (var band = 0; band < job.bands; band++) {
            var row = job.bandNext[band];
            if (row >= 0 && !job.bandQueued[band] && motionParsed(job, row)) {
                job.bandQueued[band] = true;
                queue(job, PARSE, band);
            }
        }
    }

    /**
     * Returns whether the frames whose saved motion a frame projects have parsed a row; called with
     * the lock held.
     *
     * @param job the frame
     * @param row the superblock row
     * @return {@code true} when the row of every such frame in flight is parsed
     */
    private boolean motionParsed(FrameJob job, int row) {
        for (var other : jobs) {
            if (other == job || other.parseEnded || row >= other.rows || other.parsedRows[row]) {
                continue;
            }
            for (var i = 0; i < job.nMotionDeps; i++) {
                if (other.frame == job.motionDeps[i]) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Queues the reconstruction of the parsed rows of a frame whose reference rows are final, or of
     * the rows whose parsing started when the frame is reconstructed as it is parsed
     * ({@link FrameJob#early}), in order; called with the lock held.
     *
     * @param job the frame
     */
    private void queueReconstruction(FrameJob job) {
        var ready = job.early ? job.parseBegun : job.parsed;
        while (!job.failed && job.reconNext < ready && FrameDecoder.referencesReady(job.f, job.reconNext)) {
            queue(job, RECON, job.reconNext++);
        }
    }

    /**
     * Queues the deblocking of the next row of a frame when it is reconstructed and no deblocking
     * of the frame is queued or running; called with the lock held.
     *
     * @param job the frame
     */
    private void queueDeblocking(FrameJob job) {
        if (!job.failed && !job.deblockBusy && job.deblockNext < job.reconstructed) {
            job.deblockBusy = true;
            queue(job, DEBLOCK, job.deblockNext++);
        }
    }

    /**
     * Records the rows of a frame whose samples are final, publishes them for the frames that
     * reference it and queues the reconstruction those frames waited for; called with the lock
     * held.
     *
     * @param job the frame
     * @param rows the number of leading superblock rows whose samples are final
     */
    private void advanceFinal(FrameJob job, int rows) {
        if (rows <= job.finalRows) {
            return;
        }
        job.finalRows = rows;
        var f = job.f;
        if (f.frameThreaded) {
            f.srCur.progress.publishRows(rows >= job.rows ? Integer.MAX_VALUE : rows * f.sbStep * 4);
            for (var other : jobs) {
                if (other != job) {
                    queueReconstruction(other);
                }
            }
        }
    }

    /**
     * Records the failure of a frame, whose remaining steps are skipped; called with the lock held.
     *
     * @param job the frame
     * @param failure the exception the frame is reported with
     */
    private void fail(FrameJob job, MediaException failure) {
        if (job.error == null) {
            job.error = failure;
        }
        job.failed = true;
    }

    /**
     * Marks a frame as done once every step ended or, for a failed frame, once its parsing and
     * every task it queued ended, waking the threads that wait for it; called with the lock held.
     *
     * @param job the frame
     */
    private void finishIfDone(FrameJob job) {
        if (job.done || job.pending != 0 || !job.parseEnded || (!job.failed && job.finalRows < job.rows)) {
            return;
        }
        job.done = true;
        jobs.remove(job);
        var progress = job.f.srCur.progress;
        if (job.failed) {
            progress.fail();
        }
        progress.finish();
        for (var other : jobs) {
            queueReconstruction(other);
        }
        changed.signalAll();
    }

    /**
     * Queues a task, starting a worker loop when the loops not running a task and the threads
     * waiting for a frame are fewer than the queued tasks; called with the lock held.
     *
     * @param job the frame
     * @param kind the step the task runs: {@link #PARSE}, {@link #RECON}, {@link #DEBLOCK} or {@link #POST}
     * @param row the superblock row
     */
    private void queue(FrameJob job, int kind, int row) {
        var task = freeTasks.poll();
        if (task == null) {
            task = new Task();
        }
        task.job = job;
        task.kind = kind;
        task.row = row;
        task.key = kind == PARSE ? Long.MIN_VALUE + job.frame : job.frame << 24 | (long) row << 2 | (kind - 1);
        job.pending++;
        if (size == heap.length) {
            heap = java.util.Arrays.copyOf(heap, size * 2);
        }
        var i = size++;
        while (i > 0) {
            var parent = (i - 1) >> 1;
            if (heap[parent].key <= task.key) {
                break;
            }
            heap[i] = heap[parent];
            i = parent;
        }
        heap[i] = task;
        if (waiting > 0) {
            changed.signal();
        }
        if (workers < maxWorkers && workers - busy + waiting < size) {
            workers++;
            toStart++;
        }
    }

    /**
     * Takes the queued task of lowest key; called with the lock held.
     *
     * @return the task, or {@code null} when none is queued
     */
    private Task poll() {
        if (size == 0) {
            return null;
        }
        var top = heap[0];
        var last = heap[--size];
        heap[size] = null;
        if (size > 0) {
            var i = 0;
            while (true) {
                var child = 2 * i + 1;
                if (child >= size) {
                    break;
                }
                if (child + 1 < size && heap[child + 1].key < heap[child].key) {
                    child++;
                }
                if (heap[child].key >= last.key) {
                    break;
                }
                heap[i] = heap[child];
                i = child;
            }
            heap[i] = last;
        }
        return top;
    }
}
