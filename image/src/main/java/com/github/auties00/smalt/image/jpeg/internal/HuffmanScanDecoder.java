package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * Decodes the Huffman-coded entropy data of sequential and progressive scans.
 *
 * <p>The decoder reads the unstuffed segments of {@link EntropySegments} through a 64-bit
 * left-aligned bit cache refilled eight bytes at a time. Past the end of a segment it reads zero
 * bits, as libjpeg does after hitting a marker. After every MCU it checks whether the MCU needed
 * bits beyond the segment; from then on the rest of the restart interval is left at zero
 * coefficients (uniform gray), and decoding resumes at the next restart marker found by
 * {@link EntropySegments#resync(int, int)}. A scan therefore never fails because of corrupt or
 * truncated entropy data; {@link #decode} instead reports how many MCUs were decoded from real
 * data.
 *
 * <p>Sequential scans in pixel mode dequantize while decoding. At full size and at 1/2 and 1/4
 * of it, the blocks of each component row are collected in batches of
 * {@link BatchIdctKernel#LANES} horizontally adjacent blocks, and each full batch (and the
 * partial batch at the end of a row) is transformed at once by {@link BatchIdctKernel}; an MCU
 * without data leaves its blocks at zero coefficients, whose transform is mid-gray. At 1/8 of the
 * size only the DC coefficient is used and each block becomes one sample right away. Sequential
 * scans in coefficient mode and all progressive scans store quantized coefficients.
 *
 * <p>Without the Vector API a scan decoded on the calling thread alone transforms each block on
 * its own instead, filling a block without AC coefficients with its DC value; the scans decoded
 * on several threads always use batches.
 *
 * <p>A sequential scan decoded into sample planes whose restart intervals are all delimited by
 * the expected, consecutive restart markers (many phone cameras write one per MCU row) is decoded
 * one run of intervals per thread: every interval starts with fresh predictors at its own
 * segment, which is exactly where the sequential decoder resynchronizes, and the intervals write
 * disjoint blocks, so the planes are identical to a sequential decode. A large sequential scan
 * without restart markers is entropy-decoded speculatively in several runs by
 * {@link SpeculativeScanDecoder}, one window of runs at a time, whose stitched blocks are then
 * transformed in bands, again with exactly the planes of a sequential decode.
 *
 * @implNote This implementation follows libjpeg-turbo's jdhuff.c (sequential) and jdphuff.c
 * (progressive, including every successive-approximation refinement rule and end-of-band runs),
 * with the same treatment of invalid codes, out-of-range run lengths (clamped to coefficient 63
 * through the padded {@link JpegTables#ZIGZAG}), and 16-bit truncation of coefficients. Batches
 * pay off only with the vector transform: measured without the Vector API, the scalar batch
 * transform is about 20 percent slower per full-size block than the per-block one, which also
 * skips the transform of blocks without AC coefficients.
 */
final class HuffmanScanDecoder {
    /**
     * Reads a big-endian {@code long} from a byte array at any offset.
     */
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The number of lookahead bits of the Huffman tables.
     */
    private static final int LOOKAHEAD = HuffmanDecodingTable.LOOKAHEAD;

    /**
     * Writes a {@code long} into a byte array at any offset.
     */
    private static final VarHandle LONG_NATIVE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    /**
     * Writes an {@code int} into a byte array at any offset.
     */
    private static final VarHandle INT_NATIVE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());

    /**
     * The zigzag to natural order map, padded to 80 entries.
     */
    private static final int[] ZIGZAG = JpegTables.ZIGZAG;

    /**
     * The zigzag to batch index map: coefficient {@code k} of lane {@code b} of a
     * {@link BatchIdctKernel} batch is at {@code ZIGZAG_BATCH[k] + b}; padded to 80 entries.
     */
    private static final int[] ZIGZAG_BATCH = batchZigzag();

    /**
     * A dequantization table of ones, which makes {@link #decodeBlock} return raw coefficients.
     */
    private static final int[] UNIT_QUANT = unitQuant();

    /**
     * The mode decoding sequential scans into sample planes.
     */
    private static final int BASELINE_PIXELS = 0;

    /**
     * The approximate number of operations to decode and transform one block, which weighs a
     * scan's work against {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long BLOCK_WORK = 256;

    /**
     * The smallest share of the entropy-coded data, in bytes, worth decoding speculatively on
     * its own thread.
     */
    private static final int MIN_RUN_BYTES = 64 * 1024;

    /**
     * The number of entropy-coded bits each speculative run of a window decodes, 128 KiB.
     *
     * @implNote This implementation sizes windows so that the stored blocks of one window, about
     * five bytes for every byte of entropy-coded data, stay within a few megabytes and mostly in
     * the processor caches until they are transformed, while every run still spans thousands of
     * blocks against the few that runs take to synchronize.
     */
    private static final long RUN_BITS = 8L * 128 * 1024;

    /**
     * The mode decoding sequential scans into coefficient buffers.
     */
    private static final int BASELINE_COEFFICIENTS = 1;

    /**
     * The mode decoding the first scan of a progressive DC band.
     */
    private static final int DC_FIRST = 2;

    /**
     * The mode decoding a refinement scan of a progressive DC band.
     */
    private static final int DC_REFINE = 3;

    /**
     * The mode decoding the first scan of a progressive AC band.
     */
    private static final int AC_FIRST = 4;

    /**
     * The mode decoding a refinement scan of a progressive AC band.
     */
    private static final int AC_REFINE = 5;

    /**
     * The source of the entropy-coded data.
     */
    private final EntropySegments segments;

    /**
     * The limits the working storage of speculative decoding is checked against.
     */
    private final MediaLimits limits;

    /**
     * The dequantized coefficients of the block being decoded in pixel mode; all zero between
     * blocks.
     */
    private final int[] block = new int[64];

    /**
     * The DC predictor of each scan component.
     */
    private final int[] predictors = new int[4];

    /**
     * Whether the scan belongs to a differential frame, whose DC coefficients are not predicted.
     */
    private boolean differential;

    /**
     * The unstuffed entropy-coded data.
     */
    private byte[] data;

    /**
     * The index of the next byte to load into {@link #cache}; may run past {@link #segmentEnd},
     * in which case zero bytes are loaded.
     */
    private int position;

    /**
     * The index of the first byte of the current segment.
     */
    private int segmentStart;

    /**
     * The index one past the last byte of the current segment.
     */
    private int segmentEnd;

    /**
     * The largest {@link #position} from which eight bytes of the current segment can be loaded
     * at once.
     */
    private int fastLimit;

    /**
     * The upcoming bits, left-aligned.
     */
    private long cache;

    /**
     * The number of valid bits in {@link #cache}.
     */
    private int count;

    /**
     * The index of the segment whose terminating marker is the next unread marker.
     */
    private int segment;

    /**
     * Whether the current restart interval ran out of data.
     */
    private boolean insufficient;

    /**
     * The restart number expected at the next restart boundary.
     */
    private int nextRestart;

    /**
     * The remaining length of the current end-of-band run of a progressive AC scan.
     */
    private int eobRun;

    /**
     * The DC table of each scan component in the current scan.
     */
    private HuffmanDecodingTable[] dcTables;

    /**
     * The AC table of each scan component in the current scan.
     */
    private HuffmanDecodingTable[] acTables;

    /**
     * The zigzag-ordered dequantization table of each scan component in the current scan.
     */
    private int[][] quant;

    /**
     * The sample plane of each scan component in the current scan, in pixel mode.
     */
    private byte[][] planes;

    /**
     * The plane stride of each scan component in the current scan, in pixel mode.
     */
    private int[] strides;

    /**
     * The coefficient buffer of each scan component in the current scan, in coefficient mode.
     */
    private short[][] coefficients;

    /**
     * The coefficient buffer row length, in blocks, of each scan component in the current scan.
     */
    private int[] rowBlocks;

    /**
     * The nonzero masks of each scan component in the current progressive scan, or {@code null}
     * entries otherwise.
     */
    private long[][] masks;

    /**
     * The batch of blocks being collected for each batch slot of the current scan, in batched
     * pixel mode; all zero except the lanes of pending blocks.
     */
    private int[][] batches;

    /**
     * The number of blocks collected in each batch slot.
     */
    private int[] pending;

    /**
     * The batch slot of each block of an MCU of the current scan.
     */
    private int[] blockSlot;

    /**
     * The scan component of each batch slot.
     */
    private int[] slotComponent;

    /**
     * The block row of the pending blocks of each batch slot.
     */
    private int[] pendingRow;

    /**
     * The block column of the last pending block of each batch slot.
     */
    private int[] pendingColumn;

    /**
     * Constructs a decoder over the segments of the current scan.
     *
     * @param segments the segment store filled before every scan
     * @param limits the limits the working storage of speculative decoding is checked against
     */
    HuffmanScanDecoder(EntropySegments segments, MediaLimits limits) {
        this.segments = segments;
        this.limits = limits;
    }

    /**
     * Constructs a decoder sharing the scan configuration of another one (tables, dequantization,
     * planes, entropy data) with its own bit reader, predictors and batches, to decode a run of
     * restart intervals of the same scan.
     *
     * @param template the decoder configured for the scan
     */
    private HuffmanScanDecoder(HuffmanScanDecoder template) {
        this.segments = template.segments;
        this.limits = template.limits;
        this.dcTables = template.dcTables;
        this.acTables = template.acTables;
        this.quant = template.quant;
        this.planes = template.planes;
        this.strides = template.strides;
        this.coefficients = template.coefficients;
        this.rowBlocks = template.rowBlocks;
        this.masks = template.masks;
        this.data = template.data;
    }

    /**
     * Decodes one scan.
     *
     * @param scan the scan being decoded
     * @param dc the DC table of each scan component; {@code null} entries are allowed where the
     * scan does not use a DC table
     * @param ac the AC table of each scan component; {@code null} entries are allowed where the
     * scan does not use an AC table
     * @param restartInterval the number of MCUs per restart interval, or zero
     * @param target where decoded blocks go
     * @param progressive whether the frame is progressive
     * @return the number of MCUs decoded entirely from data of the scan
     */
    long decode(JpegScan scan, HuffmanDecodingTable[] dc, HuffmanDecodingTable[] ac, int restartInterval,
                DecodeTarget target, boolean progressive) {
        var components = scan.components.length;
        this.dcTables = dc;
        this.acTables = ac;
        this.differential = target.differential;
        this.quant = new int[components][];
        this.planes = new byte[components][];
        this.strides = new int[components];
        this.coefficients = new short[components][];
        this.rowBlocks = new int[components];
        this.masks = new long[components][];
        for (var ci = 0; ci < components; ci++) {
            var component = scan.components[ci];
            quant[ci] = target.zigzagQuant[component.index];
            if (target.pixels()) {
                planes[ci] = target.planes[component.index];
                strides[ci] = target.strides[component.index];
            } else {
                coefficients[ci] = target.coefficients[component.index];
                masks[ci] = target.nonzero == null ? null : target.nonzero[component.index];
            }
            rowBlocks[ci] = component.paddedBlocksWide;
            predictors[ci] = 0;
        }
        int mode;
        if (!progressive) {
            mode = target.pixels() ? BASELINE_PIXELS : BASELINE_COEFFICIENTS;
        } else if (scan.ss == 0) {
            mode = scan.ah == 0 ? DC_FIRST : DC_REFINE;
        } else {
            mode = scan.ah == 0 ? AC_FIRST : AC_REFINE;
        }
        data = segments.data();
        segment = 0;
        startSegment(segments.start(0), segments.end(0));
        insufficient = false;
        nextRestart = 0;
        eobRun = 0;
        var scaleShift = target.scaleShift;
        var batched = mode == BASELINE_PIXELS && scaleShift < 3 && BatchIdctKernel.batches();
        if (batched) {
            prepareBatches(scan);
        }
        var window = target.scanWindow(scan);
        if (mode == BASELINE_PIXELS && restartInterval != 0) {
            var bands = intervalBands(scan, restartInterval);
            if (bands > 1) {
                return decodeIntervals(scan, restartInterval, scaleShift, bands, window);
            }
        }
        if (mode == BASELINE_PIXELS && restartInterval == 0 && (window == null || window[3] == scan.mcusHigh)) {
            var runs = speculativeRuns(scan);
            if (runs > 1) {
                var decoded = decodeSpeculative(scan, scaleShift, runs, window);
                if (decoded >= 0) {
                    return decoded;
                }
            }
        }
        var left = restartInterval;
        var decoded = 0L;
        var lastRow = window == null ? scan.mcusHigh : window[3];
        for (var mcuY = 0; mcuY < lastRow; mcuY++) {
            for (var mcuX = 0; mcuX < scan.mcusWide; mcuX++) {
                if (restartInterval != 0) {
                    if (left == 0) {
                        restart(components);
                        left = restartInterval;
                    }
                    left--;
                }
                var inside = window == null || (mcuY >= window[1] && mcuX >= window[0] && mcuX < window[2]);
                var x = window == null ? mcuX : mcuX - window[0];
                var y = window == null ? mcuY : mcuY - window[1];
                if (insufficient) {
                    if (!inside) {
                        continue;
                    }
                    if (batched) {
                        skipMcu(scan, x, y, scaleShift);
                    } else if (mode == BASELINE_PIXELS) {
                        fillMcu(scan, x, y, scaleShift);
                    }
                    continue;
                }
                if (!inside) {
                    discardMcu(scan);
                    if (exhausted()) {
                        insufficient = true;
                    } else {
                        decoded++;
                    }
                    continue;
                }
                switch (mode) {
                    case BASELINE_PIXELS -> {
                        if (batched || scaleShift == 3) {
                            baselinePixels(scan, x, y, scaleShift);
                        } else {
                            blockPixels(scan, x, y, scaleShift);
                        }
                    }
                    case BASELINE_COEFFICIENTS -> baselineCoefficients(scan, mcuX, mcuY);
                    case DC_FIRST -> dcFirst(scan, mcuX, mcuY);
                    case DC_REFINE -> dcRefine(scan, mcuX, mcuY);
                    case AC_FIRST -> acFirst(scan, mcuX, mcuY);
                    default -> acRefine(scan, mcuX, mcuY);
                }
                if (exhausted()) {
                    insufficient = true;
                } else {
                    decoded++;
                }
            }
            if (batched) {
                flushPending(scaleShift);
            }
        }
        return decoded;
    }

    /**
     * Returns the number of runs a sequential scan without restart markers is decoded in
     * speculatively, one when it is decoded sequentially.
     *
     * <p>Speculative runs decode some data twice and transform the blocks in a separate pass, so
     * they pay only when other threads decode runs at the same time: the count is at most one more
     * than the helpers {@link ParallelBands#available()} now, and one when none is.
     *
     * @param scan the scan being decoded
     * @return the run count
     */
    private int speculativeRuns(JpegScan scan) {
        var bytes = segments.end(0) - segments.start(0);
        var mcus = (long) scan.mcusWide * scan.mcusHigh;
        return Math.min(ParallelBands.bands(bytes / MIN_RUN_BYTES, mcus * scan.blocksPerMcu * BLOCK_WORK),
                ParallelBands.available() + 1);
    }

    /**
     * Decodes a sequential scan without restart markers into sample planes: entropy-decodes it
     * in speculative runs on several threads, stitches the runs into the true block sequence,
     * resolves the DC predictions and the end of the data as the sequential decoder would, and
     * transforms the blocks in bands on several threads.
     *
     * <p>The scan is processed in windows of {@code runCount} runs of about {@link #RUN_BITS}
     * bits each. The first run of a window starts where the true sequence of the previous window
     * ended, so it is true, and the blocks of a window are transformed before the next window is
     * decoded; the runs of one window are reused by the next, so the working storage is that of
     * one window whatever the size of the scan. MCUs after the one during which the data ran out
     * become mid-gray, as in a sequential decode.
     *
     * @param scan the scan being decoded
     * @param scaleShift the binary logarithm of the size reduction
     * @param runCount the number of runs of a window
     * @param window the MCUs the planes hold, as {@link DecodeTarget#scanWindow} returns them, reaching the last
     * MCU row, or {@code null} for every MCU
     * @return the number of MCUs decoded entirely from data of the scan, or {@code -1} when the
     * scan must be decoded sequentially instead because the working storage would exceed the limits
     * or the stitched sequence misses blocks; the planes are then rewritten entirely by the
     * sequential decode
     */
    private long decodeSpeculative(JpegScan scan, int scaleShift, int runCount, int[] window) {
        var speculative = new SpeculativeScanDecoder(data, segments.start(0), segments.end(0), scan, dcTables, acTables,
                limits);
        var mcus = (long) scan.mcusWide * scan.mcusHigh;
        var perMcu = scan.blocksPerMcu;
        var totalBlocks = mcus * perMcu;
        var dataBits = speculative.dataBits();
        if (totalBlocks > Integer.MAX_VALUE - 16 || dataBits > Integer.MAX_VALUE - (1 << 20)) {
            return -1;
        }
        var windowBits = runCount * RUN_BITS;
        var runs = new SpeculativeScanDecoder.Run[runCount];
        var starts = new long[runCount];
        var workers = new HuffmanScanDecoder[ParallelBands.bands(Integer.MAX_VALUE, Long.MAX_VALUE)];
        var predictors = new int[4];
        var dcValues = new int[0];
        var startBit = 0L;
        var startPhase = 0;
        var done = 0L;
        var exhaustedMcu = mcus;
        while (done < totalBlocks && exhaustedMcu == mcus) {
            var last = dataBits - startBit <= windowBits;
            var endBit = last ? dataBits : startBit + windowBits;
            var count = last ? (int) Math.clamp((endBit - startBit) / (MIN_RUN_BYTES * 8L), 1, runCount) : runCount;
            starts[0] = startBit;
            for (var w = 1; w < count; w++) {
                starts[w] = (startBit + (endBit - startBit) * w / count) / 8 * 8;
            }
            var remaining = totalBlocks - done;
            var phase = startPhase;
            var stopBit = last ? Long.MAX_VALUE : endBit;
            List<SpeculativeScanDecoder.Piece> pieces;
            try {
                ParallelBands.run(count, w -> runs[w] = speculative.decode(runs[w], starts[w], w == 0 ? phase : 0,
                        w == count - 1 ? stopBit : starts[w + 1] + SpeculativeScanDecoder.OVERLAP_BITS,
                        w == count - 1 && last, remaining));
                pieces = speculative.stitch(runs, starts, count, remaining, stopBit, last);
                var available = 0L;
                for (var piece : pieces) {
                    available += piece.length();
                }
                if (dcValues.length < available) {
                    LimitChecks.checkAllocation(limits, available * 4);
                    dcValues = new int[(int) available];
                }
            } catch (MediaException.LimitExceeded exception) {
                return -1;
            }
            var block = 0;
            for (var piece : pieces) {
                var run = piece.run();
                for (var index = piece.from(); index < piece.to(); index++, block++) {
                    var absolute = done + block;
                    var blockPhase = (int) (absolute % perMcu);
                    if (run.phases[index] != blockPhase) {
                        return -1;
                    }
                    var component = scan.blockComponent[blockPhase];
                    predictors[component] += run.values[run.offsets[index]];
                    dcValues[block] = predictors[component];
                    if (blockPhase == perMcu - 1 && exhaustedMcu == mcus) {
                        var end = index + 1 < run.blocks ? run.positions[index + 1] : run.endPosition;
                        if (end > dataBits) {
                            exhaustedMcu = absolute / perMcu;
                        }
                    }
                }
            }
            if (block == 0) {
                return -1;
            }
            var usable = (int) Math.min(block, (exhaustedMcu + 1) * perMcu - done);
            var pieceStarts = new int[pieces.size() + 1];
            for (var i = 0; i < pieces.size(); i++) {
                pieceStarts[i + 1] = pieceStarts[i] + pieces.get(i).length();
            }
            var first = done;
            var values = dcValues;
            var bands = ParallelBands.bands(Math.max(1, usable / perMcu), (long) usable * BLOCK_WORK);
            ParallelBands.run(bands, band -> worker(workers, band, scan).transformStored(scan, scaleShift, pieces,
                    pieceStarts, values, first, ParallelBands.start(band, bands, usable),
                    ParallelBands.start(band + 1, bands, usable), window));
            done += block;
            var tail = pieces.getLast();
            if (last || tail.to() != tail.run().blocks) {
                break;
            }
            startBit = tail.run().endPosition;
            startPhase = tail.run().endPhase;
        }
        if (exhaustedMcu < mcus) {
            worker(workers, 0, scan).fillGray(scan, scaleShift, exhaustedMcu + 1, window);
            return exhaustedMcu;
        }
        return done < totalBlocks ? -1 : mcus;
    }

    /**
     * Returns the decoder of a band of a speculatively decoded scan, creating it on first use;
     * every band keeps its decoder, and with it its batches, across the windows of the scan.
     *
     * @param workers the decoders by band
     * @param band the band index
     * @param scan the scan being decoded
     * @return the decoder
     */
    private HuffmanScanDecoder worker(HuffmanScanDecoder[] workers, int band, JpegScan scan) {
        var worker = workers[band];
        if (worker == null) {
            worker = new HuffmanScanDecoder(this);
            worker.prepareBatches(scan);
            workers[band] = worker;
        }
        return worker;
    }

    /**
     * Transforms blocks {@code [from, to)} of a window of the stitched block sequence of a
     * speculatively decoded scan into the sample planes.
     *
     * @param scan the scan being decoded
     * @param scaleShift the binary logarithm of the size reduction
     * @param pieces the pieces of the window's true block sequence
     * @param pieceStarts the index in the window of each piece's first block, with the window's
     * block count as the last entry
     * @param dcValues the resolved DC coefficient (before dequantization) of every block of the
     * window
     * @param first the index in the scan of the window's first block
     * @param from the index in the window of the first block to transform
     * @param to the index in the window of the block after the last one
     * @param window the MCUs the planes hold, or {@code null} for every MCU
     */
    private void transformStored(JpegScan scan, int scaleShift, List<SpeculativeScanDecoder.Piece> pieces,
                                 int[] pieceStarts, int[] dcValues, long first, int from, int to, int[] window) {
        if (from >= to) {
            return;
        }
        var batched = scaleShift < 3;
        var mcusWide = scan.mcusWide;
        var perMcu = scan.blocksPerMcu;
        var piece = Arrays.binarySearch(pieceStarts, from);
        piece = piece >= 0 ? piece : -piece - 2;
        var index = pieces.get(piece).from() + (from - pieceStarts[piece]);
        var mcu = (first + from) / perMcu;
        var b = (int) ((first + from) % perMcu);
        var mcuX = (int) (mcu % mcusWide);
        var mcuY = (int) (mcu / mcusWide);
        for (var block = from; block < to; block++, index++) {
            while (index >= pieces.get(piece).to()) {
                piece++;
                index = pieces.get(piece).from();
            }
            var run = pieces.get(piece).run();
            var ci = scan.blockComponent[b];
            var q = quant[ci];
            var dc = ((short) dcValues[block]) * q[0];
            var inside = window == null || (mcuY >= window[1] && mcuX >= window[0] && mcuX < window[2]);
            var x = window == null ? mcuX : mcuX - window[0];
            var y = window == null ? mcuY : mcuY - window[1];
            if (!inside) {
                // the block is decoded for its DC prediction only
            } else if (batched) {
                var slot = blockSlot[b];
                var batch = batches[slot];
                var lane = pending[slot];
                batch[lane] = dc;
                var mask = run.masks[index];
                var values = run.values;
                var value = run.offsets[index] + 1;
                while (mask != 0) {
                    var k = Long.numberOfTrailingZeros(mask);
                    mask &= mask - 1;
                    batch[ZIGZAG_BATCH[k] + lane] = values[value++] * q[k];
                }
                commitBlock(slot, scan.blockRow(y, b), scan.blockColumn(x, b), scaleShift);
            } else {
                planes[ci][scan.blockRow(y, b) * strides[ci] + scan.blockColumn(x, b)] = ReducedIdct.dcValue1(dc);
            }
            if (++b == perMcu) {
                b = 0;
                if (++mcuX == mcusWide) {
                    mcuX = 0;
                    mcuY++;
                    if (batched) {
                        flushPending(scaleShift);
                    }
                }
            }
        }
        if (batched) {
            flushPending(scaleShift);
        }
    }

    /**
     * Makes every MCU from {@code firstMcu} to the end of a sequential scan mid-gray, the output
     * of MCUs without data.
     *
     * @param scan the scan being decoded
     * @param scaleShift the binary logarithm of the size reduction
     * @param firstMcu the first MCU
     * @param window the MCUs the planes hold, or {@code null} for every MCU
     */
    private void fillGray(JpegScan scan, int scaleShift, long firstMcu, int[] window) {
        var batched = scaleShift < 3;
        var mcusWide = scan.mcusWide;
        var mcus = (long) mcusWide * scan.mcusHigh;
        for (var m = firstMcu; m < mcus; m++) {
            var mcuX = (int) (m % mcusWide);
            var mcuY = (int) (m / mcusWide);
            if (window != null && (mcuY < window[1] || mcuX < window[0] || mcuX >= window[2])) {
                if (batched && mcuX == mcusWide - 1) {
                    flushPending(scaleShift);
                }
                continue;
            }
            var x = window == null ? mcuX : mcuX - window[0];
            var y = window == null ? mcuY : mcuY - window[1];
            if (batched) {
                skipMcu(scan, x, y, scaleShift);
                if (mcuX == mcusWide - 1) {
                    flushPending(scaleShift);
                }
            } else {
                fillMcu(scan, x, y, 3);
            }
        }
        if (batched) {
            flushPending(scaleShift);
        }
    }

    /**
     * Returns the number of bands of restart intervals a sequential scan can be decoded in, one
     * when it must be decoded sequentially.
     *
     * <p>Bands are possible only when the entropy data has a segment for every interval and each
     * of the first {@code intervals - 1} segments ends with the restart marker the sequential
     * decoder expects next, so every interval is decoded from the same segment either way.
     *
     * @param scan the scan being decoded
     * @param restartInterval the number of MCUs per restart interval
     * @return the band count
     */
    private int intervalBands(JpegScan scan, int restartInterval) {
        var mcus = (long) scan.mcusWide * scan.mcusHigh;
        var intervals = (mcus + restartInterval - 1) / restartInterval;
        if (intervals < 2 || intervals > segments.count()) {
            return 1;
        }
        var bands = ParallelBands.bands((int) intervals, mcus * scan.blocksPerMcu * BLOCK_WORK);
        if (bands <= 1) {
            return 1;
        }
        for (var k = 0; k < intervals - 1; k++) {
            if (segments.marker(k) != 0xD0 + (k & 7)) {
                return 1;
            }
        }
        return bands;
    }

    /**
     * Decodes the restart intervals of a sequential scan into sample planes in bands of
     * consecutive intervals on several threads.
     *
     * @param scan the scan being decoded
     * <p>With a window, only the intervals that hold an MCU of the window are decoded: every interval starts at
     * its own segment, so the others are skipped without reading them.
     *
     * @param restartInterval the number of MCUs per restart interval
     * @param scaleShift the binary logarithm of the size reduction
     * @param bands the number of bands
     * @param window the MCUs the planes hold, or {@code null} for every MCU
     * @return the number of MCUs decoded entirely from data of the scan
     */
    private long decodeIntervals(JpegScan scan, int restartInterval, int scaleShift, int bands, int[] window) {
        var mcusWide = scan.mcusWide;
        var mcus = (long) mcusWide * scan.mcusHigh;
        var intervals = (int) ((mcus + restartInterval - 1) / restartInterval);
        var selected = new int[intervals];
        var held = 0;
        for (var k = 0; k < intervals; k++) {
            if (window == null || holds(k, restartInterval, mcusWide, mcus, window)) {
                selected[held++] = k;
            }
        }
        var selectedCount = held;
        var decoded = new long[bands];
        ParallelBands.run(bands, band -> decoded[band] = new HuffmanScanDecoder(this).decodeIntervalRange(scan,
                restartInterval, scaleShift, selected, ParallelBands.start(band, bands, selectedCount),
                ParallelBands.start(band + 1, bands, selectedCount), window));
        var total = 0L;
        for (var count : decoded) {
            total += count;
        }
        return total;
    }

    /**
     * Returns whether a restart interval holds an MCU of a window.
     *
     * @param interval the interval index
     * @param restartInterval the number of MCUs per restart interval
     * @param mcusWide the number of MCUs per row of the scan
     * @param mcus the number of MCUs of the scan
     * @param window the MCUs the planes hold, as {@link DecodeTarget#scanWindow} returns them
     * @return whether one of its MCUs lies in the window
     */
    private static boolean holds(int interval, int restartInterval, int mcusWide, long mcus, int[] window) {
        var end = Math.min((long) (interval + 1) * restartInterval, mcus);
        for (var m = (long) interval * restartInterval; m < end; m++) {
            var row = m / mcusWide;
            if (row >= window[3]) {
                return false;
            }
            var column = m % mcusWide;
            if (row >= window[1] && column >= window[0] && column < window[2]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decodes some restart intervals of a sequential scan into sample planes, interval {@code k} from segment
     * {@code k}.
     *
     * @param scan the scan being decoded
     * @param restartInterval the number of MCUs per restart interval
     * @param scaleShift the binary logarithm of the size reduction
     * @param intervals the indices of the intervals to decode, ascending
     * @param first the index in {@code intervals} of the first interval
     * @param last the index in {@code intervals} after the last one
     * @param window the MCUs the planes hold, or {@code null} for every MCU
     * @return the number of MCUs decoded entirely from data of the scan
     */
    private long decodeIntervalRange(JpegScan scan, int restartInterval, int scaleShift, int[] intervals, int first,
                                     int last, int[] window) {
        var batched = scaleShift < 3;
        if (batched) {
            prepareBatches(scan);
        }
        var mcusWide = scan.mcusWide;
        var mcus = (long) mcusWide * scan.mcusHigh;
        var decoded = 0L;
        for (var i = first; i < last; i++) {
            var k = intervals[i];
            segment = k;
            startSegment(segments.start(k), segments.end(k));
            insufficient = false;
            Arrays.fill(predictors, 0);
            eobRun = 0;
            var end = Math.min((long) (k + 1) * restartInterval, mcus);
            for (var m = (long) k * restartInterval; m < end; m++) {
                var mcuY = (int) (m / mcusWide);
                var mcuX = (int) (m % mcusWide);
                var inside = window == null || (mcuY >= window[1] && mcuY < window[3] && mcuX >= window[0]
                        && mcuX < window[2]);
                var x = window == null ? mcuX : mcuX - window[0];
                var y = window == null ? mcuY : mcuY - window[1];
                if (insufficient) {
                    if (!inside) {
                        // an MCU outside the window holds nothing to fill
                    } else if (batched) {
                        skipMcu(scan, x, y, scaleShift);
                    } else {
                        fillMcu(scan, x, y, 3);
                    }
                } else {
                    if (inside) {
                        baselinePixels(scan, x, y, scaleShift);
                    } else {
                        discardMcu(scan);
                    }
                    if (exhausted()) {
                        insufficient = true;
                    } else {
                        decoded++;
                    }
                }
                if (batched && mcuX == mcusWide - 1) {
                    flushPending(scaleShift);
                }
            }
        }
        if (batched) {
            flushPending(scaleShift);
        }
        return decoded;
    }

    /**
     * Builds {@link #ZIGZAG_BATCH}.
     *
     * @return the zigzag index of every coefficient times {@link BatchIdctKernel#LANES}
     */
    private static int[] batchZigzag() {
        var table = new int[ZIGZAG.length];
        for (var k = 0; k < table.length; k++) {
            table[k] = ZIGZAG[k] * BatchIdctKernel.LANES;
        }
        return table;
    }

    /**
     * Allocates the batch slots of a sequential scan decoded into sample planes: one slot per
     * block row of each component within an MCU.
     *
     * @param scan the scan being decoded
     */
    private void prepareBatches(JpegScan scan) {
        var components = scan.components.length;
        var interleaved = scan.interleaved();
        var first = new int[components];
        var slots = 0;
        for (var ci = 0; ci < components; ci++) {
            first[ci] = slots;
            slots += interleaved ? scan.components[ci].v : 1;
        }
        batches = new int[slots][BatchIdctKernel.BATCH_LENGTH];
        pending = new int[slots];
        pendingRow = new int[slots];
        pendingColumn = new int[slots];
        slotComponent = new int[slots];
        for (var ci = 0; ci < components; ci++) {
            var rows = interleaved ? scan.components[ci].v : 1;
            for (var row = 0; row < rows; row++) {
                slotComponent[first[ci] + row] = ci;
            }
        }
        blockSlot = new int[scan.blocksPerMcu];
        for (var b = 0; b < scan.blocksPerMcu; b++) {
            blockSlot[b] = first[scan.blockComponent[b]] + (interleaved ? scan.blockY[b] : 0);
        }
    }

    /**
     * Counts one block into its batch slot and transforms the batch once it is full.
     *
     * @param slot the batch slot
     * @param row the block row of the block
     * @param column the block column of the block
     * @param scaleShift the binary logarithm of the size reduction, below 3
     */
    private void commitBlock(int slot, int row, int column, int scaleShift) {
        var count = pending[slot] + 1;
        if (count == BatchIdctKernel.LANES) {
            transformBatch(slot, row, column - (BatchIdctKernel.LANES - 1), BatchIdctKernel.LANES, scaleShift);
            count = 0;
        }
        pending[slot] = count;
        pendingRow[slot] = row;
        pendingColumn[slot] = column;
    }

    /**
     * Transforms the partial batches pending in every slot: at the end of an MCU row, where the
     * next blocks of a slot belong to another block row, and at the end of a run of restart
     * intervals.
     *
     * @param scaleShift the binary logarithm of the size reduction, below 3
     */
    private void flushPending(int scaleShift) {
        for (var slot = 0; slot < batches.length; slot++) {
            var count = pending[slot];
            if (count != 0) {
                transformBatch(slot, pendingRow[slot], pendingColumn[slot] - count + 1, count, scaleShift);
                pending[slot] = 0;
            }
        }
    }

    /**
     * Transforms the blocks of one batch slot into their component plane.
     *
     * @param slot the batch slot
     * @param row the block row of the batch
     * @param column the block column of the batch's first block
     * @param count the number of blocks in the batch
     * @param scaleShift the binary logarithm of the size reduction, below 3
     */
    private void transformBatch(int slot, int row, int column, int count, int scaleShift) {
        var ci = slotComponent[slot];
        var stride = strides[ci];
        var size = 8 >> scaleShift;
        var offset = row * size * stride + column * size;
        var kernel = BatchIdctKernel.INSTANCE;
        switch (scaleShift) {
            case 0 -> kernel.idct8(batches[slot], count, planes[ci], offset, stride);
            case 1 -> kernel.idct4(batches[slot], count, planes[ci], offset, stride);
            default -> kernel.idct2(batches[slot], count, planes[ci], offset, stride);
        }
    }

    /**
     * Builds {@link #UNIT_QUANT}.
     *
     * @return 80 ones
     */
    private static int[] unitQuant() {
        var table = new int[80];
        java.util.Arrays.fill(table, 1);
        return table;
    }

    /**
     * Processes a restart boundary: resynchronizes on the next restart marker and resets the
     * predictors and the end-of-band run.
     *
     * @param components the number of scan components
     */
    private void restart(int components) {
        var next = segments.resync(segment, nextRestart);
        if (next >= 0) {
            segment = next;
            startSegment(segments.start(next), segments.end(next));
            insufficient = false;
        } else {
            startSegment(0, 0);
        }
        nextRestart = (nextRestart + 1) & 7;
        for (var ci = 0; ci < components; ci++) {
            predictors[ci] = 0;
        }
        eobRun = 0;
    }

    /**
     * Starts reading a segment.
     *
     * @param start the index of the first byte
     * @param end the index one past the last byte
     */
    private void startSegment(int start, int end) {
        position = start;
        segmentStart = start;
        segmentEnd = end;
        fastLimit = end - 8;
        cache = 0;
        count = 0;
    }

    /**
     * Returns whether the bits consumed so far exceed the current segment.
     *
     * @return {@code true} when zero bits past the segment end were used
     */
    private boolean exhausted() {
        return (long) (position - segmentStart) * 8 - count > (long) (segmentEnd - segmentStart) * 8;
    }

    /**
     * Tops the cache up to at least 56 valid bits.
     */
    private void refill() {
        if (position <= fastLimit) {
            cache |= ((long) LONG_BE.get(data, position)) >>> count;
            position += (63 - count) >>> 3;
            count |= 56;
            return;
        }
        while (count <= 56) {
            if (position < segmentEnd) {
                cache |= (data[position] & 0xFFL) << (56 - count);
            }
            position++;
            count += 8;
        }
    }

    /**
     * Reads bits; the cache must hold at least {@code bits} valid bits.
     *
     * @param bits the number of bits, from 1 to 31
     * @return the unsigned value
     */
    private int bits(int bits) {
        var value = (int) (cache >>> (64 - bits));
        cache <<= bits;
        count -= bits;
        return value;
    }

    /**
     * Decodes one symbol; the cache must hold at least 17 valid bits.
     *
     * @param table the Huffman table
     * @return the symbol
     */
    private int symbol(HuffmanDecodingTable table) {
        var entry = table.lookup[(int) (cache >>> (64 - LOOKAHEAD))];
        if (entry == 0) {
            entry = table.decodeSlow(cache);
        }
        var length = entry >>> 8;
        cache <<= length;
        count -= length;
        return entry & 0xFF;
    }

    /**
     * Decodes one MCU of a sequential scan and transforms its blocks into the sample planes.
     *
     * @param scan the scan being decoded
     * @param mcuX the MCU column
     * @param mcuY the MCU row
     * @param scaleShift the binary logarithm of the size reduction
     */
    private void baselinePixels(JpegScan scan, int mcuX, int mcuY, int scaleShift) {
        if (scaleShift < 3) {
            for (var b = 0; b < scan.blocksPerMcu; b++) {
                var ci = scan.blockComponent[b];
                var slot = blockSlot[b];
                decodeBlock(batches[slot], pending[slot], ZIGZAG_BATCH, dcTables[ci], acTables[ci], quant[ci], ci);
                commitBlock(slot, scan.blockRow(mcuY, b), scan.blockColumn(mcuX, b), scaleShift);
            }
            return;
        }
        var block = this.block;
        for (var b = 0; b < scan.blocksPerMcu; b++) {
            var ci = scan.blockComponent[b];
            var offset = scan.blockRow(mcuY, b) * strides[ci] + scan.blockColumn(mcuX, b);
            decodeDcOnly(block, dcTables[ci], acTables[ci], quant[ci], ci);
            planes[ci][offset] = ReducedIdct.dcValue1(block[0]);
            block[0] = 0;
        }
    }

    /**
     * Decodes one MCU of a sequential scan without storing it: its DC coefficients update the predictors and its AC
     * coefficients are skipped.
     *
     * @param scan the scan being decoded
     */
    private void discardMcu(JpegScan scan) {
        var block = this.block;
        for (var b = 0; b < scan.blocksPerMcu; b++) {
            var ci = scan.blockComponent[b];
            decodeDcOnly(block, dcTables[ci], acTables[ci], quant[ci], ci);
            block[0] = 0;
        }
    }

    /**
     * Counts the blocks of an MCU without data into their batches, leaving their coefficients at
     * zero, which the batch transform turns into mid-gray.
     *
     * @param scan the scan being decoded
     * @param mcuX the MCU column
     * @param mcuY the MCU row
     * @param scaleShift the binary logarithm of the size reduction, below 3
     */
    private void skipMcu(JpegScan scan, int mcuX, int mcuY, int scaleShift) {
        for (var b = 0; b < scan.blocksPerMcu; b++) {
            commitBlock(blockSlot[b], scan.blockRow(mcuY, b), scan.blockColumn(mcuX, b), scaleShift);
        }
    }

    /**
     * Decodes one MCU of a sequential scan and transforms each of its blocks on its own into the
     * sample planes, filling a block without AC coefficients with its DC value directly.
     *
     * @param scan the scan being decoded
     * @param mcuX the MCU column
     * @param mcuY the MCU row
     * @param scaleShift the binary logarithm of the size reduction, below 3
     */
    private void blockPixels(JpegScan scan, int mcuX, int mcuY, int scaleShift) {
        var block = this.block;
        var size = 8 >> scaleShift;
        for (var b = 0; b < scan.blocksPerMcu; b++) {
            var ci = scan.blockComponent[b];
            var plane = planes[ci];
            var stride = strides[ci];
            var offset = scan.blockRow(mcuY, b) * size * stride + scan.blockColumn(mcuX, b) * size;
            var last = decodeBlock(block, 0, ZIGZAG, dcTables[ci], acTables[ci], quant[ci], ci);
            if (last == 0) {
                var value = ReducedIdct.dcValue(block[0]);
                block[0] = 0;
                fill(plane, offset, stride, size, value);
            } else if (scaleShift == 0) {
                ScalarIdctKernel.INSTANCE.idct8(block, plane, offset, stride);
            } else if (scaleShift == 1) {
                ReducedIdct.idct4(block, plane, offset, stride);
            } else {
                ReducedIdct.idct2(block, plane, offset, stride);
            }
        }
    }

    /**
     * Fills every block of an MCU with mid-gray, the output of an all-zero block.
     *
     * @param scan the scan being decoded
     * @param mcuX the MCU column
     * @param mcuY the MCU row
     * @param scaleShift the binary logarithm of the size reduction
     */
    private void fillMcu(JpegScan scan, int mcuX, int mcuY, int scaleShift) {
        var size = 8 >> scaleShift;
        for (var b = 0; b < scan.blocksPerMcu; b++) {
            var ci = scan.blockComponent[b];
            var stride = strides[ci];
            var offset = scan.blockRow(mcuY, b) * size * stride + scan.blockColumn(mcuX, b) * size;
            fill(planes[ci], offset, stride, size, (byte) 128);
        }
    }

    /**
     * Fills a square of samples with one value.
     *
     * @param plane the destination plane
     * @param offset the index of the top-left sample
     * @param stride the plane stride
     * @param size the side of the square: 8, 4, 2 or 1
     * @param value the sample value
     */
    private static void fill(byte[] plane, int offset, int stride, int size, byte value) {
        switch (size) {
            case 8 -> {
                var pattern = (value & 0xFFL) * 0x0101010101010101L;
                for (var row = 0; row < 8; row++) {
                    LONG_NATIVE.set(plane, offset + row * stride, pattern);
                }
            }
            case 4 -> {
                var pattern = (value & 0xFF) * 0x01010101;
                for (var row = 0; row < 4; row++) {
                    INT_NATIVE.set(plane, offset + row * stride, pattern);
                }
            }
            case 2 -> {
                plane[offset] = value;
                plane[offset + 1] = value;
                plane[offset + stride] = value;
                plane[offset + stride + 1] = value;
            }
            default -> plane[offset] = value;
        }
    }

    /**
     * Decodes one block of a sequential scan into dequantized natural-order coefficients.
     *
     * <p>Coefficient {@code k} (zigzag order) is stored at {@code block[zigzag[k] + lane]}, so
     * the same code fills a plain 64-coefficient block and one lane of a batch.
     *
     * @param block the destination, zero at every index the block can write on entry
     * @param lane the offset added to every index
     * @param zigzag the index of every zigzag position, 80 entries
     * @param dcTable the DC table
     * @param acTable the AC table
     * @param quant the zigzag-ordered dequantization table, 80 entries
     * @param component the scan component index, which selects the DC predictor
     * @return the zigzag index of the last coefficient written, zero when only the DC coefficient
     * was written
     */
    private int decodeBlock(int[] block, int lane, int[] zigzag, HuffmanDecodingTable dcTable,
                            HuffmanDecodingTable acTable, int[] quant, int component) {
        var data = this.data;
        var position = this.position;
        var fastLimit = this.fastLimit;
        var cache = this.cache;
        var count = this.count;
        if (count < 32) {
            if (position <= fastLimit) {
                cache |= ((long) LONG_BE.get(data, position)) >>> count;
                position += (63 - count) >>> 3;
                count |= 56;
            } else {
                while (count <= 56) {
                    if (position < segmentEnd) {
                        cache |= (data[position] & 0xFFL) << (56 - count);
                    }
                    position++;
                    count += 8;
                }
            }
        }
        var peek = (int) (cache >>> (64 - LOOKAHEAD));
        var entry = dcTable.fast[peek];
        int diff;
        if (entry != 0) {
            var length = entry & 15;
            cache <<= length;
            count -= length;
            diff = entry >> 16;
        } else {
            entry = dcTable.lookup[peek];
            if (entry == 0) {
                entry = dcTable.decodeSlow(cache);
            }
            var length = entry >>> 8;
            cache <<= length;
            count -= length;
            var size = entry & 15;
            if (size != 0) {
                var bits = (int) (cache >>> (64 - size));
                cache <<= size;
                count -= size;
                diff = bits < (1 << (size - 1)) ? bits - (1 << size) + 1 : bits;
            } else {
                diff = 0;
            }
        }
        var dc = predictors[component] + diff;
        predictors[component] = differential ? 0 : dc;
        block[lane] = ((short) dc) * quant[0];
        var fast = acTable.fast;
        var lookup = acTable.lookup;
        var last = 0;
        var k = 1;
        while (k < 64) {
            if (count < 32) {
                if (position <= fastLimit) {
                    cache |= ((long) LONG_BE.get(data, position)) >>> count;
                    position += (63 - count) >>> 3;
                    count |= 56;
                } else {
                    while (count <= 56) {
                        if (position < segmentEnd) {
                            cache |= (data[position] & 0xFFL) << (56 - count);
                        }
                        position++;
                        count += 8;
                    }
                }
            }
            peek = (int) (cache >>> (64 - LOOKAHEAD));
            entry = fast[peek];
            if (entry != 0) {
                k += (entry >>> 4) & 15;
                var length = entry & 15;
                cache <<= length;
                count -= length;
                block[zigzag[k] + lane] = (entry >> 16) * quant[k];
                last = k;
                k++;
                continue;
            }
            entry = lookup[peek];
            if (entry == 0) {
                entry = acTable.decodeSlow(cache);
            }
            var length = entry >>> 8;
            cache <<= length;
            count -= length;
            var run = (entry >>> 4) & 15;
            var size = entry & 15;
            if (size != 0) {
                k += run;
                var bits = (int) (cache >>> (64 - size));
                cache <<= size;
                count -= size;
                var value = bits < (1 << (size - 1)) ? bits - (1 << size) + 1 : bits;
                block[zigzag[k] + lane] = value * quant[k];
                last = k;
                k++;
            } else if (run == 15) {
                k += 16;
            } else {
                break;
            }
        }
        this.position = position;
        this.cache = cache;
        this.count = count;
        return last;
    }

    /**
     * Decodes one block of a sequential scan keeping only the dequantized DC coefficient, for
     * 1/8-size decoding; the AC coefficients are parsed and discarded.
     *
     * @param block the destination; only element 0 is written
     * @param dcTable the DC table
     * @param acTable the AC table
     * @param quant the zigzag-ordered dequantization table
     * @param component the scan component index, which selects the DC predictor
     */
    private void decodeDcOnly(int[] block, HuffmanDecodingTable dcTable, HuffmanDecodingTable acTable,
                              int[] quant, int component) {
        var data = this.data;
        var position = this.position;
        var fastLimit = this.fastLimit;
        var cache = this.cache;
        var count = this.count;
        if (count < 32) {
            if (position <= fastLimit) {
                cache |= ((long) LONG_BE.get(data, position)) >>> count;
                position += (63 - count) >>> 3;
                count |= 56;
            } else {
                while (count <= 56) {
                    if (position < segmentEnd) {
                        cache |= (data[position] & 0xFFL) << (56 - count);
                    }
                    position++;
                    count += 8;
                }
            }
        }
        var peek = (int) (cache >>> (64 - LOOKAHEAD));
        var entry = dcTable.fast[peek];
        int diff;
        if (entry != 0) {
            var length = entry & 15;
            cache <<= length;
            count -= length;
            diff = entry >> 16;
        } else {
            entry = dcTable.decode(cache);
            var length = entry >>> 8;
            cache <<= length;
            count -= length;
            var size = entry & 15;
            diff = 0;
            if (size != 0) {
                var bits = (int) (cache >>> (64 - size));
                cache <<= size;
                count -= size;
                diff = bits < (1 << (size - 1)) ? bits - (1 << size) + 1 : bits;
            }
        }
        var dc = predictors[component] + diff;
        predictors[component] = differential ? 0 : dc;
        block[0] = ((short) dc) * quant[0];
        var fast = acTable.fast;
        var lookup = acTable.lookup;
        var k = 1;
        while (k < 64) {
            if (count < 32) {
                if (position <= fastLimit) {
                    cache |= ((long) LONG_BE.get(data, position)) >>> count;
                    position += (63 - count) >>> 3;
                    count |= 56;
                } else {
                    while (count <= 56) {
                        if (position < segmentEnd) {
                            cache |= (data[position] & 0xFFL) << (56 - count);
                        }
                        position++;
                        count += 8;
                    }
                }
            }
            peek = (int) (cache >>> (64 - LOOKAHEAD));
            entry = fast[peek];
            if (entry != 0) {
                k += ((entry >>> 4) & 15) + 1;
                var length = entry & 15;
                cache <<= length;
                count -= length;
                continue;
            }
            entry = lookup[peek];
            if (entry == 0) {
                entry = acTable.decodeSlow(cache);
            }
            var length = (entry >>> 8) + (entry & 15);
            cache <<= length;
            count -= length;
            var run = (entry >>> 4) & 15;
            if ((entry & 15) != 0) {
                k += run + 1;
            } else if (run == 15) {
                k += 16;
            } else {
                break;
            }
        }
        this.position = position;
        this.cache = cache;
        this.count = count;
    }

    /**
     * Decodes one MCU of a sequential scan into the coefficient buffers.
     *
     * @param scan the scan being decoded
     * @param mcuX the MCU column
     * @param mcuY the MCU row
     */
    private void baselineCoefficients(JpegScan scan, int mcuX, int mcuY) {
        var block = this.block;
        for (var b = 0; b < scan.blocksPerMcu; b++) {
            var ci = scan.blockComponent[b];
            var coef = coefficients[ci];
            var offset = (scan.blockRow(mcuY, b) * rowBlocks[ci] + scan.blockColumn(mcuX, b)) * 64;
            decodeBlock(block, 0, ZIGZAG, dcTables[ci], acTables[ci], UNIT_QUANT, ci);
            for (var i = 0; i < 64; i++) {
                coef[offset + i] = (short) block[i];
                block[i] = 0;
            }
        }
    }

    /**
     * Decodes one MCU of the first scan of a progressive DC band.
     *
     * @param scan the scan being decoded
     * @param mcuX the MCU column
     * @param mcuY the MCU row
     */
    private void dcFirst(JpegScan scan, int mcuX, int mcuY) {
        var al = scan.al;
        for (var b = 0; b < scan.blocksPerMcu; b++) {
            var ci = scan.blockComponent[b];
            var offset = (scan.blockRow(mcuY, b) * rowBlocks[ci] + scan.blockColumn(mcuX, b)) * 64;
            if (count < 32) {
                refill();
            }
            var table = dcTables[ci];
            var entry = table.fast[(int) (cache >>> (64 - LOOKAHEAD))];
            int diff;
            if (entry != 0) {
                var length = entry & 15;
                cache <<= length;
                count -= length;
                diff = entry >> 16;
            } else {
                var size = symbol(table);
                diff = size == 0 ? 0 : HuffmanDecodingTable.extend(bits(size), size);
            }
            var dc = predictors[ci] + diff;
            predictors[ci] = differential ? 0 : dc;
            coefficients[ci][offset] = (short) (dc << al);
        }
    }

    /**
     * Decodes one MCU of a refinement scan of a progressive DC band.
     *
     * @param scan the scan being decoded
     * @param mcuX the MCU column
     * @param mcuY the MCU row
     */
    private void dcRefine(JpegScan scan, int mcuX, int mcuY) {
        var p1 = 1 << scan.al;
        for (var b = 0; b < scan.blocksPerMcu; b++) {
            var ci = scan.blockComponent[b];
            var offset = (scan.blockRow(mcuY, b) * rowBlocks[ci] + scan.blockColumn(mcuX, b)) * 64;
            if (count < 32) {
                refill();
            }
            if (bits(1) != 0) {
                var coef = coefficients[ci];
                coef[offset] = (short) (coef[offset] | p1);
            }
        }
    }

    /**
     * Decodes one block of the first scan of a progressive AC band.
     *
     * @param scan the scan, which has a single component
     * @param mcuX the block column
     * @param mcuY the block row
     */
    private void acFirst(JpegScan scan, int mcuX, int mcuY) {
        if (eobRun > 0) {
            eobRun--;
            return;
        }
        var coef = coefficients[0];
        var blockIndex = mcuY * rowBlocks[0] + mcuX;
        var offset = blockIndex * 64;
        var mask = masks[0][blockIndex];
        var table = acTables[0];
        var fast = table.fast;
        var lookup = table.lookup;
        var se = scan.se;
        var al = scan.al;
        var data = this.data;
        var position = this.position;
        var fastLimit = this.fastLimit;
        var cache = this.cache;
        var count = this.count;
        var k = scan.ss;
        while (k <= se) {
            if (count < 32) {
                if (position <= fastLimit) {
                    cache |= ((long) LONG_BE.get(data, position)) >>> count;
                    position += (63 - count) >>> 3;
                    count |= 56;
                } else {
                    while (count <= 56) {
                        if (position < segmentEnd) {
                            cache |= (data[position] & 0xFFL) << (56 - count);
                        }
                        position++;
                        count += 8;
                    }
                }
            }
            var peek = (int) (cache >>> (64 - LOOKAHEAD));
            var entry = fast[peek];
            if (entry != 0) {
                k += (entry >>> 4) & 15;
                var length = entry & 15;
                cache <<= length;
                count -= length;
                var stored = (short) ((entry >> 16) << al);
                coef[offset + ZIGZAG[k]] = stored;
                mask = track(mask, k, stored);
                k++;
                continue;
            }
            entry = lookup[peek];
            if (entry == 0) {
                entry = table.decodeSlow(cache);
            }
            var length = entry >>> 8;
            cache <<= length;
            count -= length;
            var run = (entry >>> 4) & 15;
            var size = entry & 15;
            if (size != 0) {
                k += run;
                var bits = (int) (cache >>> (64 - size));
                cache <<= size;
                count -= size;
                var value = bits < (1 << (size - 1)) ? bits - (1 << size) + 1 : bits;
                var stored = (short) (value << al);
                coef[offset + ZIGZAG[k]] = stored;
                mask = track(mask, k, stored);
                k++;
            } else if (run == 15) {
                k += 16;
            } else {
                var runLength = 1 << run;
                if (run != 0) {
                    runLength += (int) (cache >>> (64 - run));
                    cache <<= run;
                    count -= run;
                }
                eobRun = runLength - 1;
                break;
            }
        }
        masks[0][blockIndex] = mask;
        this.position = position;
        this.cache = cache;
        this.count = count;
    }

    /**
     * Records in a nonzero mask whether the coefficient stored at a zigzag index is nonzero.
     *
     * @param mask the zigzag-order nonzero mask of the block
     * @param k the zigzag index written, up to 79 (indices above 63 alias coefficient 63)
     * @param value the value stored
     * @return the updated mask
     */
    private static long track(long mask, int k, short value) {
        var bit = 1L << Math.min(k, 63);
        return value != 0 ? mask | bit : mask & ~bit;
    }

    /**
     * Returns the mask of zigzag indices from {@code from} to {@code to}, both inclusive.
     *
     * @param from the first index, from 0 to 64
     * @param to the last index, from -1 to 63
     * @return the mask, empty when {@code from > to}
     */
    private static long range(int from, int to) {
        if (from > to || from > 63) {
            return 0;
        }
        return (-1L << from) & (to >= 63 ? -1L : (1L << (to + 1)) - 1);
    }

    /**
     * Decodes one block of a refinement scan of a progressive AC band.
     *
     * <p>The block's nonzero mask lets the correction bits of previously nonzero coefficients be
     * read in bulk and the coefficient a run lands on be found with bit operations, so the work
     * is proportional to the symbols and nonzero coefficients rather than to the band width.
     *
     * @param scan the scan, which has a single component
     * @param mcuX the block column
     * @param mcuY the block row
     */
    private void acRefine(JpegScan scan, int mcuX, int mcuY) {
        var coef = coefficients[0];
        var blockIndex = mcuY * rowBlocks[0] + mcuX;
        var offset = blockIndex * 64;
        var mask = masks[0][blockIndex];
        var table = acTables[0];
        var lookup = table.lookup;
        var se = scan.se;
        var p1 = 1 << scan.al;
        var m1 = -1 << scan.al;
        var data = this.data;
        var position = this.position;
        var fastLimit = this.fastLimit;
        var segmentEnd = this.segmentEnd;
        var cache = this.cache;
        var count = this.count;
        var k = scan.ss;
        var bandEnd = -1L >>> (63 - se);
        var eobRun = this.eobRun;
        if (eobRun == 0) {
            while (k <= se) {
                if (count < 32) {
                    if (position <= fastLimit) {
                        cache |= ((long) LONG_BE.get(data, position)) >>> count;
                        position += (63 - count) >>> 3;
                        count |= 56;
                    } else {
                        while (count <= 56) {
                            if (position < segmentEnd) {
                                cache |= (data[position] & 0xFFL) << (56 - count);
                            }
                            position++;
                            count += 8;
                        }
                    }
                }
                var entry = lookup[(int) (cache >>> (64 - LOOKAHEAD))];
                if (entry == 0) {
                    entry = table.decodeSlow(cache);
                }
                var length = entry >>> 8;
                cache <<= length;
                count -= length;
                var run = (entry >>> 4) & 15;
                var value = 0;
                if ((entry & 15) != 0) {
                    value = cache < 0 ? p1 : m1;
                    cache <<= 1;
                    count--;
                } else if (run != 15) {
                    eobRun = 1 << run;
                    if (run != 0) {
                        eobRun += (int) (cache >>> (64 - run));
                        cache <<= run;
                        count -= run;
                    }
                    break;
                }
                var band = (-1L << k) & bandEnd;
                var selected = Long.expand(1L << run, ~mask & band);
                var target = selected != 0 ? Long.numberOfTrailingZeros(selected) : se + 1;
                var pending = mask & band & (selected - 1);
                while (pending != 0) {
                    if (count == 0) {
                        if (position <= fastLimit) {
                            cache = (long) LONG_BE.get(data, position);
                            position += 7;
                            count = 56;
                        } else {
                            cache = position < segmentEnd ? (data[position] & 0xFFL) << 56 : 0;
                            position++;
                            count = 8;
                        }
                    }
                    var n = Math.min(Long.bitCount(pending), count);
                    var bits = cache >>> (64 - n);
                    cache <<= n;
                    count -= n;
                    for (var i = n - 1; i >= 0; i--) {
                        var index = offset + ZIGZAG[Long.numberOfTrailingZeros(pending)];
                        pending &= pending - 1;
                        var current = coef[index];
                        var apply = (int) (bits >>> i) & (((current & p1) - 1) >>> 31);
                        coef[index] = (short) (current + apply * (current >= 0 ? p1 : m1));
                    }
                }
                k = target;
                if (value != 0) {
                    coef[offset + ZIGZAG[k]] = (short) value;
                    mask |= 1L << Math.min(k, 63);
                }
                k++;
            }
        }
        if (eobRun > 0) {
            var pending = mask & range(k, se);
            while (pending != 0) {
                if (count == 0) {
                    if (position <= fastLimit) {
                        cache = (long) LONG_BE.get(data, position);
                        position += 7;
                        count = 56;
                    } else {
                        cache = position < segmentEnd ? (data[position] & 0xFFL) << 56 : 0;
                        position++;
                        count = 8;
                    }
                }
                var n = Math.min(Long.bitCount(pending), count);
                var bits = cache >>> (64 - n);
                cache <<= n;
                count -= n;
                for (var i = n - 1; i >= 0; i--) {
                    var index = offset + ZIGZAG[Long.numberOfTrailingZeros(pending)];
                    pending &= pending - 1;
                    var current = coef[index];
                    var apply = (int) (bits >>> i) & (((current & p1) - 1) >>> 31);
                    coef[index] = (short) (current + apply * (current >= 0 ? p1 : m1));
                }
            }
            eobRun--;
        }
        masks[0][blockIndex] = mask;
        this.eobRun = eobRun;
        this.position = position;
        this.cache = cache;
        this.count = count;
    }
}
