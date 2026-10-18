package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.task.ParallelBands;

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
     * published whatever happens.
     *
     * @param job the picture
     * @return the first failure of the picture, or {@code null}
     */
    MediaException decode(PictureJob job) {
        frame = job.frame;
        pic = job.pic;
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
        for (var slice : job.slices) {
            protect |= slice.header().pps.transquantBypassEnabled;
        }
        MediaException failure = null;
        try {
            for (var slice : job.slices) {
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
        }
        if (failure == null && job.hashType >= 0 && !PictureHash.matches(frame, sps, job.hashType, job.hash)) {
            failure = new MediaException.Malformed("decoded picture hash mismatch at picture order count " + frame.poc);
        }
        frame = null;
        pic = null;
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
                    filter(decoded, decoded >= rows ? frame.height : (decoded << pic.sps.log2CtbSize) - 4);
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
