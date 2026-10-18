package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaLimits;

import com.github.auties00.smalt.internal.limits.LimitChecks;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decodes the Huffman-coded blocks of a sequential scan without restart markers in several
 * independent runs, each starting at its own byte of the entropy-coded data, and stitches the
 * runs into the block sequence a single decoder would produce.
 *
 * <p>Only the first run starts where the scan does; every other run starts at an arbitrary byte
 * and assumes it is at the first block of an MCU, which is usually wrong. A run's decode is a
 * function of its bit position and of the MCU block it believes it is in (which selects the
 * Huffman tables), so once a run starts a block at a bit position where the true decode also
 * starts a block, believing it is the same block of the MCU, the two decodes coincide from there
 * on. The stitcher finds the first such block in the part of the preceding (already true) run
 * that overlaps the next run and splices the runs there; when no such block exists it decodes
 * the rest of the scan from the end of the true part in one more run, so the result never
 * depends on the runs synchronizing. Runs keep, for every block, its bit position, its block
 * index within the MCU, its DC difference and its nonzero AC coefficients in zigzag order; the
 * caller turns the stitched sequence into samples.
 *
 * <p>Past the end of the data every run reads zero bits, exactly as the sequential decoder does,
 * so a truncated scan stitches to the same blocks.
 *
 * @implNote This implementation follows the self-synchronizing property of Huffman codes that
 * parallel JPEG decoders exploit (Weissenberger and Schmidt, "Accelerating JPEG
 * decompression on GPUs", 2021, and earlier work on speculative Huffman decoding):
 * speculative decoders usually fall into step with the true symbol boundaries within
 * a few blocks. The block decoding itself is the same table-driven decoding as
 * {@link HuffmanScanDecoder}'s, including its handling of invalid codes and of runs that
 * pass coefficient 63.
 */
final class SpeculativeScanDecoder {
    /**
     * Reads a big-endian {@code long} from a byte array at any offset.
     */
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The number of lookahead bits of the Huffman tables.
     */
    private static final int LOOKAHEAD = HuffmanDecodingTable.LOOKAHEAD;

    /**
     * The number of bits a run decodes past the start of the next run, where the stitcher looks
     * for the block both runs start at.
     */
    static final long OVERLAP_BITS = 8L * 1024 * 8;

    /**
     * The unstuffed entropy-coded data.
     */
    private final byte[] data;

    /**
     * The index of the first byte of the scan's data.
     */
    private final int segmentStart;

    /**
     * The index one past the last byte of the scan's data.
     */
    private final int segmentEnd;

    /**
     * The number of blocks of an MCU.
     */
    private final int blocksPerMcu;

    /**
     * The scan component of each block of an MCU.
     */
    private final int[] blockComponent;

    /**
     * The DC table of each scan component.
     */
    private final HuffmanDecodingTable[] dcTables;

    /**
     * The AC table of each scan component.
     */
    private final HuffmanDecodingTable[] acTables;

    /**
     * The limits the runs' storage is checked against.
     */
    private final MediaLimits limits;

    /**
     * Prepares the decoding of a scan.
     *
     * @param data the unstuffed entropy-coded data
     * @param segmentStart the index of the first byte of the scan's data
     * @param segmentEnd the index one past the last byte of the scan's data
     * @param scan the scan to decode
     * @param dcTables the DC table of each scan component
     * @param acTables the AC table of each scan component
     * @param limits the limits the runs' storage is checked against
     */
    SpeculativeScanDecoder(byte[] data, int segmentStart, int segmentEnd, JpegScan scan, HuffmanDecodingTable[] dcTables,
                           HuffmanDecodingTable[] acTables, MediaLimits limits) {
        this.data = data;
        this.segmentStart = segmentStart;
        this.segmentEnd = segmentEnd;
        this.blocksPerMcu = scan.blocksPerMcu;
        this.blockComponent = scan.blockComponent;
        this.dcTables = dcTables;
        this.acTables = acTables;
        this.limits = limits;
    }

    /**
     * Returns the number of bits of the scan's data.
     *
     * @return the data length in bits
     */
    long dataBits() {
        return (long) (segmentEnd - segmentStart) * 8;
    }

    /**
     * The blocks decoded by one run, in decoding order.
     */
    static final class Run {
        /**
         * The bit position, from the start of the scan's data, at which each block starts; the
         * data of a speculatively decoded scan holds fewer than {@code 2^31} bits.
         */
        int[] positions;

        /**
         * The index within its MCU that the run assigned to each block.
         */
        byte[] phases;

        /**
         * The zigzag-order mask of the nonzero AC coefficients of each block (bit 0 clear).
         */
        long[] masks;

        /**
         * The index in {@link #values} of each block's DC difference.
         */
        int[] offsets;

        /**
         * The DC difference and then the nonzero AC coefficients of every block.
         */
        short[] values;

        /**
         * The number of blocks.
         */
        int blocks;

        /**
         * The number of values.
         */
        int count;

        /**
         * The bit position just after the last block.
         */
        long endPosition;

        /**
         * The index within its MCU of the block that would follow the last one.
         */
        int endPhase;

        /**
         * Allocates the storage of a run.
         *
         * @param expectedBlocks the number of blocks the run is expected to decode
         * @param expectedValues the number of values the run is expected to keep
         */
        Run(int expectedBlocks, int expectedValues) {
            var capacity = Math.max(16, expectedBlocks);
            positions = new int[capacity];
            phases = new byte[capacity];
            masks = new long[capacity];
            offsets = new int[capacity];
            values = new short[Math.max(capacity, expectedValues)];
        }

        /**
         * Grows the per-block arrays.
         *
         * @param limits the limits the new storage is checked against
         */
        void growBlocks(MediaLimits limits) {
            var capacity = (long) positions.length * 2;
            LimitChecks.checkAllocation(limits, capacity * 17);
            positions = Arrays.copyOf(positions, (int) capacity);
            phases = Arrays.copyOf(phases, (int) capacity);
            masks = Arrays.copyOf(masks, (int) capacity);
            offsets = Arrays.copyOf(offsets, (int) capacity);
        }

        /**
         * Grows the value array so it can take at least 65 more values.
         *
         * @param limits the limits the new storage is checked against
         */
        void growValues(MediaLimits limits) {
            var capacity = Math.max((long) values.length * 2, (long) count + 65);
            LimitChecks.checkAllocation(limits, capacity * 2);
            values = Arrays.copyOf(values, (int) capacity);
        }
    }

    /**
     * A contiguous part of the true block sequence: blocks {@code [from, to)} of a run.
     *
     * @param run the run whose blocks the piece takes
     * @param from the first block
     * @param to the block after the last one
     */
    record Piece(Run run, int from, int to) {
        /**
         * Returns the number of blocks of the piece.
         *
         * @return {@code to - from}
         */
        int length() {
            return to - from;
        }
    }

    /**
     * Decodes blocks from a bit position, assuming the first block has index {@code phase} within
     * its MCU, until the next block would start at or after {@code stopBit}, or, for a run that
     * decodes to the end, until it has decoded {@code maxBlocks} blocks or a whole MCU's worth of
     * blocks after passing the end of the data.
     *
     * <p>The blocks are stored in {@code reuse} when it is large enough for the expected number
     * of blocks, which then no longer holds its previous blocks, and in a new run otherwise.
     *
     * @param reuse a run whose storage may be reused, or {@code null}
     * @param startBit the bit position of the first block
     * @param phase the index within its MCU of the first block
     * @param stopBit the bit position at which the run stops
     * @param toEnd whether the run decodes to the end of the scan instead of stopping at
     * {@code stopBit}
     * @param maxBlocks the most blocks to decode
     * @return the decoded run
     */
    Run decode(Run reuse, long startBit, int phase, long stopBit, boolean toEnd, long maxBlocks) {
        var span = Math.max(0, Math.min(stopBit, dataBits()) - startBit);
        var expected = (int) Math.min(maxBlocks, Math.max(16, span / 96));
        var expectedValues = (int) Math.min(Integer.MAX_VALUE - 16, span / 5 + 64);
        Run run;
        if (reuse != null && reuse.positions.length >= expected && reuse.values.length >= expectedValues) {
            run = reuse;
            run.blocks = 0;
            run.count = 0;
        } else {
            LimitChecks.checkAllocation(limits, (long) Math.max(16, expected) * 17 + 2L * expectedValues);
            run = new Run(expected, expectedValues);
        }
        var data = this.data;
        var segmentStart = this.segmentStart;
        var segmentEnd = this.segmentEnd;
        var fastLimit = segmentEnd - 8;
        var dataBits = dataBits();
        var position = segmentStart + (int) (startBit >>> 3);
        var cache = 0L;
        var count = 0;
        if (position <= fastLimit) {
            cache = (long) LONG_BE.get(data, position);
            position += 8;
            count = 64;
        } else {
            while (count <= 56) {
                if (position < segmentEnd) {
                    cache |= (data[position] & 0xFFL) << (56 - count);
                }
                position++;
                count += 8;
            }
        }
        var skip = (int) (startBit & 7);
        cache <<= skip;
        count -= skip;
        var afterEnd = -1;
        var blocks = 0;
        while (blocks < maxBlocks) {
            var bitPosition = (long) (position - segmentStart) * 8 - count;
            if (toEnd) {
                if (afterEnd < 0 && bitPosition > dataBits) {
                    afterEnd = 0;
                }
                if (afterEnd >= 0 && afterEnd++ >= blocksPerMcu) {
                    break;
                }
            } else if (bitPosition >= stopBit) {
                break;
            }
            if (blocks == run.positions.length) {
                run.growBlocks(limits);
            }
            if (run.count + 65 > run.values.length) {
                run.growValues(limits);
            }
            run.positions[blocks] = (int) bitPosition;
            run.phases[blocks] = (byte) phase;
            run.offsets[blocks] = run.count;
            var component = blockComponent[phase];
            var dcTable = dcTables[component];
            var acTable = acTables[component];
            var values = run.values;
            var n = run.count;
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
            values[n++] = (short) diff;
            var fast = acTable.fast;
            var lookup = acTable.lookup;
            var mask = 0L;
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
                    mask |= 1L << Math.min(k, 63);
                    values[n++] = (short) (entry >> 16);
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
                var run2 = (entry >>> 4) & 15;
                var size = entry & 15;
                if (size != 0) {
                    k += run2;
                    var bits = (int) (cache >>> (64 - size));
                    cache <<= size;
                    count -= size;
                    mask |= 1L << Math.min(k, 63);
                    values[n++] = (short) (bits < (1 << (size - 1)) ? bits - (1 << size) + 1 : bits);
                    k++;
                } else if (run2 == 15) {
                    k += 16;
                } else {
                    break;
                }
            }
            run.masks[blocks] = mask;
            run.count = n;
            blocks++;
            phase = phase + 1 == blocksPerMcu ? 0 : phase + 1;
        }
        run.blocks = blocks;
        run.endPosition = (long) (position - segmentStart) * 8 - count;
        run.endPhase = phase;
        return run;
    }

    /**
     * Splices runs into the true block sequence of a part of the scan.
     *
     * <p>Run 0 starts at a true block, so it is true. For each following run, the first block of
     * the preceding true run that starts at or after the next run's start and that the next run
     * also starts, at the same index within the MCU, is where the true sequence continues in the
     * next run. When the preceding run holds no such block, the rest of the part is decoded from
     * the end of the preceding run, as the last run was: up to {@code stopBit}, or to the end of
     * the scan.
     *
     * @param runs the runs, run {@code w} starting at {@code starts[w]}
     * @param starts the bit position at which each run starts
     * @param count the number of runs
     * @param totalBlocks the most blocks the sequence may hold
     * @param stopBit the bit position at which the last run stopped
     * @param toEnd whether the last run decoded to the end of the scan
     * @return the pieces of the true block sequence, in order, holding at most
     * {@code totalBlocks} blocks
     */
    List<Piece> stitch(Run[] runs, long[] starts, int count, long totalBlocks, long stopBit, boolean toEnd) {
        var pieces = new ArrayList<Piece>();
        var current = runs[0];
        var from = 0;
        var total = 0L;
        for (var w = 1; w < count && total < totalBlocks; w++) {
            var next = runs[w];
            var i = lowerBound(current.positions, from, current.blocks, starts[w]);
            var j = 0;
            var sync = -1;
            for (; i < current.blocks; i++) {
                var position = current.positions[i];
                while (j < next.blocks && next.positions[j] < position) {
                    j++;
                }
                if (j < next.blocks && next.positions[j] == position && next.phases[j] == current.phases[i]) {
                    sync = i;
                    break;
                }
            }
            if (sync < 0) {
                total += add(pieces, current, from, current.blocks, totalBlocks - total);
                if (total < totalBlocks) {
                    var rest = decode(null, current.endPosition, current.endPhase, stopBit, toEnd, totalBlocks - total);
                    total += add(pieces, rest, 0, rest.blocks, totalBlocks - total);
                }
                return pieces;
            }
            total += add(pieces, current, from, sync, totalBlocks - total);
            current = next;
            from = j;
        }
        add(pieces, current, from, current.blocks, totalBlocks - total);
        return pieces;
    }

    /**
     * Appends a piece, clipped to the blocks still missing from the sequence.
     *
     * @param pieces the pieces so far
     * @param run the run whose blocks the piece takes
     * @param from the first block
     * @param to the block after the last one
     * @param missing the number of blocks still missing
     * @return the number of blocks appended
     */
    private static int add(List<Piece> pieces, Run run, int from, int to, long missing) {
        var end = (int) Math.min(to, from + missing);
        if (end > from) {
            pieces.add(new Piece(run, from, end));
            return end - from;
        }
        return 0;
    }

    /**
     * Returns the first index in {@code [from, to)} whose position is at least {@code key}.
     *
     * @param positions the sorted positions
     * @param from the first index to consider
     * @param to the index after the last one to consider
     * @param key the position searched for
     * @return the index, or {@code to} when every position is smaller
     */
    private static int lowerBound(int[] positions, int from, int to, long key) {
        var low = from;
        var high = to;
        while (low < high) {
            var middle = (low + high) >>> 1;
            if (positions[middle] < key) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
