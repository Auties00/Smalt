package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;

/**
 * Decodes the slices of one picture and applies its in-loop filters row by row as the rows of
 * coding tree blocks complete, publishing the final rows for the pictures that reference it.
 *
 * <p>A row of coding tree blocks is deblocked as soon as it is decoded: the intra prediction of the
 * row below reads the unfiltered bottom line of the row above from a copy taken when each coding
 * tree block was decoded ({@link HevcFrame#edge8}). The sample adaptive offset follows the
 * deblocking by four sample rows, because deblocking the next row modifies the three bottom rows
 * of this one and the offsets of a sample read the row below it; the rows it has completed are
 * published as final. With tiles, rows complete out of raster order; the filters follow the
 * leading rows that are complete.
 *
 * <p>With several threads, the wavefront rows of a slice segment coded with
 * {@code entropy_coding_sync_enabled_flag} and entry points are decoded concurrently, one row per
 * stage of a {@link ParallelBands#pipeline}. Whichever thread completes a row runs the filters the
 * completed rows allow, one thread at a time.
 *
 * <p>A decoder is reused for one picture at a time.
 */
final class PictureDecoder implements IntConsumer {
    /**
     * The most rows decoded concurrently by one pipeline: the most stages of
     * {@link ParallelBands#pipeline}.
     */
    private static final int MAX_STAGES = 31;

    /**
     * The slice data decoder of the slice segments decoded on the calling thread.
     */
    private final SliceDecoder slices = new SliceDecoder();

    /**
     * The idle slice data decoders of the wavefront rows.
     */
    private final Queue<SliceDecoder> rowDecoders = new ConcurrentLinkedQueue<>();

    /**
     * The deblocking filter.
     */
    private final Deblocker deblocker = new Deblocker();

    /**
     * The sample adaptive offset filter.
     */
    private final SaoFilter sao = new SaoFilter();

    /**
     * Serializes the in-loop filters of the rows completed by concurrent wavefront rows.
     */
    private final ReentrantLock filterLock = new ReentrantLock();

    /**
     * The progress of the wavefront rows decoded concurrently, or {@code null} before the first.
     */
    private Wavefront wavefront;

    /**
     * The picture being decoded.
     */
    private HevcFrame frame;

    /**
     * The coding information of the picture being decoded.
     */
    private PictureState pic;

    /**
     * Whether some samples of the picture may have to skip the sample adaptive offset.
     */
    private boolean protect;

    /**
     * Whether the in-loop filters wait for the whole picture: the picture may reference itself,
     * and intra block copy reads its samples before in-loop filtering.
     */
    private boolean deferFilters;

    /**
     * The number of decoded coding tree blocks of each row.
     */
    private AtomicIntegerArray rowCount = new AtomicIntegerArray(0);

    /**
     * The number of leading rows of coding tree blocks that are completely decoded; guarded by
     * {@link #filterLock}.
     */
    private int decodedRows;

    /**
     * The number of leading rows of coding tree blocks that are deblocked; guarded by
     * {@link #filterLock}.
     */
    private int deblockedRows;

    /**
     * The number of leading luma sample rows that are final; guarded by {@link #filterLock}.
     */
    private int finalLines;

    /**
     * Constructs a decoder.
     */
    PictureDecoder() {
    }

    /**
     * Decodes a picture: its slices, its in-loop filters and the check of its decoded picture
     * hash. Corrupt slices are skipped after marking the picture damaged; every row is filtered and
     * published whatever happens. The color planes of a picture coded with separate color planes
     * are decoded one after the other, or concurrently by other decoders taken from the job when
     * its work may run on several threads.
     *
     * @param job the picture
     * @return the first failure of the picture, or {@code null}
     */
    MediaException decode(PictureJob job) {
        var picture = job.frame;
        var sps = job.states[0].sps;
        MediaException failure = null;
        if (picture.planes == null) {
            failure = decodePlane(picture, job.states[0], job, -1);
        } else {
            var results = new MediaException[picture.planes.length];
            if (job.parallel) {
                // the planes do not depend on one another; a plane waits only for the rows of its references
                var helpers = new PictureDecoder[]{this, job.borrow(), job.borrow()};
                try {
                    ParallelBands.pipeline(results.length, k -> results[k] = helpers[k].colorPlane(job, k));
                } finally {
                    job.release(helpers[1]);
                    job.release(helpers[2]);
                }
            } else {
                for (var k = 0; k < results.length; k++) {
                    results[k] = colorPlane(job, k);
                }
            }
            for (var k = 0; k < results.length; k++) {
                picture.damaged |= picture.planes[k].damaged;
                if (failure == null) {
                    failure = results[k];
                }
            }
        }
        if (failure == null && job.hashType >= 0 && !PictureHash.matches(picture, sps, job.hashType, job.hash)) {
            failure = new MediaException.Malformed("decoded picture hash mismatch at picture order count " + picture.poc);
        }
        return failure;
    }

    /**
     * Decodes one color plane of a picture coded with separate color planes, or fills it as
     * {@link #missingPlane} does when no slice segment codes it, then publishes that the plane is
     * done.
     *
     * @param job the picture holding the slice segments
     * @param plane the {@code colour_plane_id}
     * @return the first failure of the plane, or {@code null}
     */
    private MediaException colorPlane(PictureJob job, int plane) {
        var target = job.frame.planes[plane];
        try {
            return hasPlane(job, plane) ? decodePlane(target, job.states[plane], job, plane)
                    : missingPlane(target, job.states[plane].sps, plane);
        } finally {
            target.publishDone();
        }
    }

    /**
     * Returns whether a job holds a slice segment of a color plane.
     *
     * @param job the picture holding the slice segments
     * @param plane the {@code colour_plane_id}
     * @return {@code true} when a slice segment of the job codes the plane
     */
    private static boolean hasPlane(PictureJob job, int plane) {
        for (var slice : job.slices) {
            if (slice.header().colorPlaneId == plane) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fills a color plane that no slice segment of its picture codes with mid-gray samples and
     * intra motion, as a missing reference picture is generated, and marks it damaged.
     *
     * @param target the picture of the color plane
     * @param sps the active sequence parameter set
     * @param plane the {@code colour_plane_id}
     * @return the failure reporting the missing plane
     */
    private static MediaException missingPlane(HevcFrame target, HevcSps sps, int plane) {
        target.damaged = true;
        Arrays.fill(target.ctbSlice, -1);
        if (target.eightBit()) {
            Arrays.fill(target.y8, (byte) 128);
        } else {
            Arrays.fill(target.y, (short) (1 << (sps.bitDepthLuma - 1)));
        }
        target.publishRows(target.height);
        return new MediaException.Malformed("picture at picture order count " + target.poc + " lacks color plane "
                + plane);
    }

    /**
     * Decodes one picture, or one color plane of a picture coded with separate color planes: its
     * slices and its in-loop filters. Corrupt slices are skipped after marking the picture
     * damaged; every row is filtered and published whatever happens.
     *
     * @param target the picture, or the picture of the color plane
     * @param state the coding information of {@code target}
     * @param job the job holding the slices
     * @param plane the {@code colour_plane_id} of the slices to decode, or -1 for every slice
     * @return the first failure, or {@code null}
     */
    private MediaException decodePlane(HevcFrame target, PictureState state, PictureJob job, int plane) {
        frame = target;
        pic = state;
        var sps = pic.sps;
        var rows = sps.heightInCtbs;
        if (rowCount.length() < rows) {
            rowCount = new AtomicIntegerArray(rows);
        } else {
            for (var i = 0; i < rows; i++) {
                rowCount.set(i, 0);
            }
        }
        frame.prepareEdges(rows);
        decodedRows = 0;
        deblockedRows = 0;
        finalLines = 0;
        protect = sps.pcmEnabled && sps.pcmLoopFilterDisabled;
        deferFilters = false;
        for (var slice : job.slices) {
            protect |= slice.header().pps.transquantBypassEnabled;
            deferFilters |= slice.header().pps.currPicRefEnabled;
        }
        MediaException failure = null;
        try {
            for (var slice : job.slices) {
                if (plane >= 0 && slice.header().colorPlaneId != plane) {
                    continue;
                }
                try {
                    if (slice.substreams() != null && slice.header().pps.tilesEnabled) {
                        decodeTiles(slice);
                    } else if (slice.substreams() != null) {
                        decodeWavefront(slice);
                    } else {
                        slices.decode(frame, pic, slice.header(), slice.refList(), slice.sliceIndex(), slice.data(),
                                slice.size(), this);
                    }
                } catch (MediaException.Malformed exception) {
                    frame.damaged = true;
                    if (failure == null) {
                        failure = exception;
                    }
                } catch (RuntimeException exception) {
                    if (exception instanceof MediaException) {
                        throw exception;
                    }
                    frame.damaged = true;
                    if (failure == null) {
                        failure = new MediaException.Malformed("corrupt HEVC slice segment", exception);
                    }
                }
            }
            filterLock.lock();
            try {
                filter(rows, frame.height);
            } finally {
                filterLock.unlock();
            }
        } finally {
            frame.publishRows(frame.height);
            frame = null;
            pic = null;
        }
        return failure;
    }

    /**
     * Decodes the tiles of a slice segment concurrently, one substream per tile. Tiles are
     * independent for parsing and reconstruction, so they run in any order; the loop filters
     * follow the rows of coding tree blocks that every tile has completed.
     *
     * @param slice the slice segment, which starts at the beginning of a tile
     * @throws MediaException.Malformed if a substream is corrupt or the substreams do not match the
     * tiles
     */
    private void decodeTiles(PictureJob.Slice slice) {
        var header = slice.header();
        var pps = header.pps;
        var firstTile = pps.tileId[pps.ctbAddrRsToTs[header.segmentAddress]];
        var starts = slice.substreams();
        var count = starts.length;
        for (var batch = 0; batch < count; batch += MAX_STAGES) {
            var first = batch;
            // A pipeline rather than independent bands: a tile waits for the rows of its reference
            // pictures, which the threads that would otherwise run it may be decoding.
            ParallelBands.pipeline(Math.min(MAX_STAGES, count - batch), stage -> {
                var k = first + stage;
                var decoder = rowDecoders.poll();
                if (decoder == null) {
                    decoder = new SliceDecoder();
                }
                try {
                    var end = k + 1 < count ? starts[k + 1] : slice.size();
                    var ended = decoder.decodeTile(frame, pic, header, slice.refList(), slice.sliceIndex(),
                            slice.data(), starts[k], end, firstTile + k, k == 0, this);
                    if (ended != (k == count - 1)) {
                        throw new MediaException.Malformed(ended ? "slice segment ends before its last tile"
                                : "slice segment continues past its last tile");
                    }
                } finally {
                    rowDecoders.add(decoder);
                }
            });
        }
    }

    /**
     * Decodes the wavefront rows of a slice segment concurrently, in batches of at most
     * {@link #MAX_STAGES} rows.
     *
     * @param slice the slice segment, which starts at the beginning of a row
     * @throws MediaException.Malformed if a substream is corrupt or the substreams do not match the
     * rows
     */
    private void decodeWavefront(PictureJob.Slice slice) {
        var header = slice.header();
        var widthCtbs = header.sps.widthInCtbs;
        var firstRow = header.segmentAddress / widthCtbs;
        var starts = slice.substreams();
        var count = starts.length;
        if (firstRow + count > header.sps.heightInCtbs) {
            throw new MediaException.Malformed("more wavefront substreams than rows");
        }
        var sync = wavefront;
        if (sync == null || sync.width() != widthCtbs) {
            sync = new Wavefront(widthCtbs);
            wavefront = sync;
        }
        var tracker = sync;
        for (var batch = 0; batch < count; batch += MAX_STAGES) {
            var first = batch;
            var stages = Math.min(MAX_STAGES, count - batch);
            tracker.begin(firstRow + first, stages);
            ParallelBands.pipeline(stages, stage -> {
                var k = first + stage;
                var decoder = rowDecoders.poll();
                if (decoder == null) {
                    decoder = new SliceDecoder();
                }
                try {
                    var end = k + 1 < count ? starts[k + 1] : slice.size();
                    var ended = decoder.decodeRow(frame, pic, header, slice.refList(), slice.sliceIndex(),
                            slice.data(), starts[k], end, firstRow + k, k == 0, this, tracker);
                    if (ended != (k == count - 1)) {
                        throw new MediaException.Malformed(ended ? "slice segment ends before its last substream"
                                : "slice segment continues past its last substream");
                    }
                } catch (RuntimeException exception) {
                    tracker.fail();
                    throw exception;
                } finally {
                    rowDecoders.add(decoder);
                }
            });
        }
    }

    /**
     * Records a decoded coding tree block and runs the filters the rows completed so far allow.
     *
     * <p>Safe to call from the threads of concurrent wavefront rows: when another thread is
     * filtering, the completed row is left to it.
     *
     * @param ctbAddrRs the raster address of the coding tree block
     */
    @Override
    public void accept(int ctbAddrRs) {
        var widthCtbs = pic.sps.widthInCtbs;
        if (rowCount.incrementAndGet(ctbAddrRs / widthCtbs) < widthCtbs) {
            return;
        }
        var rows = pic.sps.heightInCtbs;
        while (filterLock.tryLock()) {
            try {
                var decoded = decodedRows;
                while (decoded < rows && rowCount.get(decoded) == widthCtbs) {
                    decoded++;
                }
                if (decoded > decodedRows) {
                    decodedRows = decoded;
                    frame.publishDecodedRows(decoded << pic.sps.log2CtbSize);
                    if (!deferFilters) {
                        filter(decoded, decoded >= rows ? frame.height : (decoded << pic.sps.log2CtbSize) - 4);
                    }
                }
            } finally {
                filterLock.unlock();
            }
            if (decodedRows >= rows || rowCount.get(decodedRows) < widthCtbs) {
                return;
            }
        }
    }

    /**
     * Deblocks the rows before {@code deblockTo} and applies the offsets to the luma sample rows
     * before {@code finalTo} that are not processed yet, then publishes the final rows; the caller
     * holds {@link #filterLock}.
     *
     * <p>Deblocking a row of coding tree blocks changes the three sample rows above it, and the
     * offsets of a sample row read the row below, so with the rows before {@code deblockTo}
     * deblocked the final rows are those before the last four of them.
     *
     * @param deblockTo the row of coding tree blocks after the last that may be deblocked
     * @param finalTo the luma sample row after the last that may be completed
     */
    private void filter(int deblockTo, int finalTo) {
        if (deblockTo > deblockedRows) {
            deblocker.filterRows(frame, pic, deblockedRows, deblockTo);
            deblockedRows = deblockTo;
        }
        if (finalTo > finalLines) {
            sao.filterLines(frame, pic, finalLines, finalTo, protect);
            finalLines = finalTo;
            frame.publishRows(finalTo);
        }
    }
}
