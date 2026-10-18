package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Holds one frame in flight: its frame state, the task contexts of its parsing pass, a private
 * copy of its tile data and the progress of each step of its decoding, which
 * {@link FrameScheduler} drives.
 *
 * <p>The decoder sets the job up on its own thread, submits it to the scheduler and retires it
 * once it is done, releasing what it holds on its own thread. A frame that is not decoded in two
 * passes is decoded at once by {@link #decodeSinglePass()}. The scheduling fields are guarded by
 * the scheduler's lock.
 */
final class FrameJob {
    /**
     * The frame state.
     */
    final FrameContext f = new FrameContext();

    /**
     * The task context of single-pass decoding, also the first context of the parsing pass.
     */
    final TaskContext t = new TaskContext();

    /**
     * The task contexts of the parsing pass, the first being {@link #t}.
     */
    private TaskContext[] parseContexts = {t};

    /**
     * The number of the frame in decoding order, from 1.
     */
    long frame;

    /**
     * The copy of the frame's tile data, when the frame is decoded after the decode call that
     * submitted it returned.
     */
    private byte[] tiles = new byte[0];

    /**
     * The first failure of the frame, or {@code null}; read once the job is done.
     */
    MediaException error;

    /**
     * The tile whose CDFs the frame stores, or {@code -1}, known once the parsing starts.
     */
    int updateTile;

    /**
     * The number of superblock rows.
     */
    int rows;

    /**
     * Whether the frame has a deblocking step: loop filtering, or line backups for later filters.
     */
    boolean deblocks;

    /**
     * Whether the frame has a post-filtering step: CDEF, super-resolution or loop restoration.
     */
    boolean postFilters;

    /**
     * The number of the frame that produced the CDFs and the segmentation map this frame starts
     * from, whose parsing must end before this frame is parsed, or 0 when the frame starts from
     * the default CDFs.
     */
    long cdfDep;

    /**
     * The numbers of the frames whose saved motion this frame projects: the parsing of a
     * superblock row of this frame follows the parsing of the same row of each.
     */
    final long[] motionDeps = new long[4];

    /**
     * The number of entries of {@link #motionDeps}.
     */
    int nMotionDeps;

    /**
     * The number of parallel bands that parse the tile rows of the frame
     * ({@link FrameDecoder#tileRowBands}).
     */
    int bands;

    /**
     * The next superblock row each band parses, or -1 when the band has parsed its last row.
     */
    int[] bandNext = new int[1];

    /**
     * Whether a parsing task of each band is queued or running.
     */
    boolean[] bandQueued = new boolean[1];

    /**
     * The number of bands that have not parsed their last row.
     */
    int bandsLeft;

    /**
     * Whether the preparation of the parsing ended, after which the bands parse.
     */
    boolean headDone;

    /**
     * Whether the preparation task was queued.
     */
    boolean parseQueued;

    /**
     * Whether the parsing ended, successfully or not.
     */
    boolean parseEnded;

    /**
     * The number of leading superblock rows parsed.
     */
    int parsed;

    /**
     * Whether the rows are reconstructed while they are parsed, superblock by superblock
     * ({@link FrameDecoder#earlyReconstruction}); known once the parsing starts.
     */
    boolean early;

    /**
     * The number of leading superblock rows whose parsing started, counted only for
     * {@link #early} frames.
     */
    int parseBegun;

    /**
     * Whether each superblock row is parsed.
     */
    boolean[] parsedRows = new boolean[0];

    /**
     * The next superblock row to queue for reconstruction.
     */
    int reconNext;

    /**
     * Whether each superblock row is reconstructed.
     */
    boolean[] reconDone = new boolean[0];

    /**
     * The number of leading superblock rows reconstructed.
     */
    int reconstructed;

    /**
     * The next superblock row to queue for deblocking, rows being deblocked in order.
     */
    int deblockNext;

    /**
     * Whether a deblocking task of the frame is queued or running.
     */
    boolean deblockBusy;

    /**
     * The number of leading superblock rows deblocked.
     */
    int deblocked;

    /**
     * The next superblock row to queue for post-filtering.
     */
    int postNext;

    /**
     * Whether each superblock row is post-filtered.
     */
    boolean[] postDone = new boolean[0];

    /**
     * The number of leading superblock rows whose samples are final.
     */
    int finalRows;

    /**
     * The number of tasks of the frame queued or running.
     */
    int pending;

    /**
     * Whether the frame failed, so that its remaining steps are skipped.
     */
    boolean failed;

    /**
     * Whether every step of the frame ended.
     */
    boolean done;

    /**
     * Creates an idle job.
     */
    FrameJob() {
    }

    /**
     * Adds a frame whose saved motion this frame projects, unless the list holds it already.
     *
     * @param frame the number of the frame
     */
    void addMotionDep(long frame) {
        for (var i = 0; i < nMotionDeps; i++) {
            if (motionDeps[i] == frame) {
                return;
            }
        }
        motionDeps[nMotionDeps++] = frame;
    }

    /**
     * Prepares the scheduling state for the frame set up in {@link #f}.
     */
    void resetSchedule() {
        var f = this.f;
        rows = f.sbh;
        var hdr = f.frameHdr;
        postFilters = f.seqHdr.cdef || f.resized() || f.restorePlanes != 0;
        deblocks = postFilters || hdr.loopfilterLevelY[0] != 0 || hdr.loopfilterLevelY[1] != 0;
        if (reconDone.length < rows) {
            parsedRows = new boolean[rows];
            reconDone = new boolean[rows];
            postDone = new boolean[rows];
        } else {
            Arrays.fill(parsedRows, 0, rows, false);
            Arrays.fill(reconDone, 0, rows, false);
            Arrays.fill(postDone, 0, rows, false);
        }
        error = null;
        updateTile = -1;
        parseQueued = false;
        parseEnded = false;
        cdfDep = 0;
        nMotionDeps = 0;
        bands = FrameDecoder.tileRowBands(f);
        if (bandNext.length < bands) {
            bandNext = new int[bands];
            bandQueued = new boolean[bands];
        }
        Arrays.fill(bandQueued, false);
        bandsLeft = 0;
        headDone = false;
        parsed = 0;
        early = false;
        parseBegun = 0;
        reconNext = 0;
        reconstructed = 0;
        deblockNext = 0;
        deblockBusy = false;
        deblocked = 0;
        postNext = 0;
        finalRows = 0;
        pending = 0;
        failed = false;
        done = false;
    }

    /**
     * Returns the task contexts of the parsing pass, creating missing ones.
     *
     * @return the contexts, at least {@link FrameDecoder#tileRowBands(FrameContext)} times
     * {@link FrameDecoder#decodeBands(FrameContext)}
     */
    TaskContext[] parseContexts() {
        var needed = FrameDecoder.tileRowBands(f) * FrameDecoder.decodeBands(f);
        if (parseContexts.length < needed) {
            var grown = Arrays.copyOf(parseContexts, needed);
            for (var i = parseContexts.length; i < needed; i++) {
                grown[i] = new TaskContext();
            }
            parseContexts = grown;
        }
        return parseContexts;
    }

    /**
     * Copies the frame's tile data into a buffer the job owns, so that the frame may be decoded
     * after the packets holding the data are reused.
     */
    void copyTiles() {
        var total = 0;
        for (var i = 0; i < f.nTileData; i++) {
            total += f.tileSize[i];
        }
        if (tiles.length < total) {
            tiles = new byte[total];
        }
        var pos = 0;
        for (var i = 0; i < f.nTileData; i++) {
            System.arraycopy(f.tileData[i], f.tileOffset[i], tiles, pos, f.tileSize[i]);
            f.tileData[i] = tiles;
            f.tileOffset[i] = pos;
            pos += f.tileSize[i];
        }
    }

    /**
     * Prepares the tiles of the frame ({@link FrameDecoder#initTiles}) and checks that no reference
     * frame failed; called once every earlier frame is parsed.
     *
     * @throws MediaException.Malformed if a reference is corrupt or the tile sizes are inconsistent
     */
    void initTiles() {
        if (f.frameHdr.isInterOrSwitch()) {
            for (var i = 0; i < 7; i++) {
                if (f.refp[i].progress.failed()) {
                    throw new MediaException.Malformed("av1: reference frame is corrupt");
                }
            }
        }
        try {
            updateTile = FrameDecoder.initTiles(f);
        } catch (IllegalStateException exception) {
            throw new MediaException.Malformed("av1: " + exception.getMessage());
        }
    }

    /**
     * Stores the frame's CDFs from the tile that updates them, once every tile is parsed.
     */
    void storeCdfs() {
        var hdr = f.frameHdr;
        if (hdr.refreshContext && updateTile >= 0) {
            Cdf.update(f.outCdf, f.ts[updateTile].cdf, hdr.isKeyOrIntra());
        }
    }

    /**
     * Decodes a frame that is not decoded in two passes, on the calling thread, and marks its
     * picture as decoded; never throws: the failure is kept in {@link #error}.
     */
    void decodeSinglePass() {
        var progress = f.srCur.progress;
        error = null;
        try {
            initTiles();
            if (!FrameDecoder.decodeMain(f, t)) {
                throw new MediaException.Malformed("av1: corrupt tile data");
            }
            storeCdfs();
        } catch (MediaException exception) {
            error = exception;
        } catch (RuntimeException exception) {
            error = new MediaException.Malformed("corrupt av1 packet", exception);
        } finally {
            if (error != null) {
                progress.fail();
            }
            progress.finish();
            done = true;
        }
    }
}
