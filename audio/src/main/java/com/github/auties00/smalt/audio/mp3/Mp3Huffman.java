package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;

import java.util.Arrays;

/**
 * Codes the quantized spectrum of a granule with the Layer III Huffman tables: splits it into the
 * big-value, count1 and zero regions, chooses the region boundaries and the table of every
 * region, counts the bits and writes them.
 *
 * <p>The big-value region is cut into up to three regions at scalefactor band boundaries. Long
 * blocks either try every {@code region0_count} and {@code region1_count} and keep the split with
 * the fewest bits, or take a split at about a third and two thirds of the region; blocks with
 * window switching use the fixed split of ISO/IEC 11172-3 2.4.2.7. Each region takes the cheapest
 * table among those that can code its largest value. The count1 region takes the cheaper of
 * tables A and B.
 *
 * <p>An instance keeps scratch arrays and is not thread-safe.
 *
 * @implNote This implementation counts the code lengths of all big-value tables in one pass per
 * band: {@link #PACKED} holds, for every value pair, the code lengths of four tables in the four
 * 16-bit fields of a {@code long}, so summing four {@code long}s per pair gives every table's
 * total at once (a band holds at most 288 pairs of at most 19 bits, which fits a field). The bits
 * of each table are then accumulated over bands, so the cost of any run of bands is a
 * difference of two prefix sums.
 */
final class Mp3Huffman {
    /**
     * A bit count larger than any granule can hold, marking an unusable table.
     */
    private static final int INFINITE = 1 << 28;

    /**
     * The bits a band reports for a table that cannot code it: large enough never to be chosen,
     * small enough that 41 of them still fit an {@code int}. A table is only ever chosen for a
     * run of bands whose largest value it can code.
     */
    private static final int UNUSABLE = 1 << 20;

    /**
     * The tables whose code lengths share each packed {@code long}, four per word; table 16 and
     * 24 stand for their escape families, coded with values clamped to 15.
     */
    private static final int[][] PACKING = {{1, 2, 3, 5}, {6, 7, 8, 9}, {10, 11, 12, 13}, {15, 16, 24, 0}};

    /**
     * The packed code lengths: entry {@code 4 * (x * 16 + y) + w} holds the lengths of the tables
     * of {@code PACKING[w]} for the pair {@code (x, y)}, sign and escape bits excluded.
     */
    private static final long[] PACKED = new long[4 * 256];

    /**
     * The non-escape tables grouped by value range: the tables of each group code values below
     * the group's {@link #GROUP_RANGES range}.
     */
    private static final int[][] GROUPS = {{1}, {2, 3}, {5, 6}, {7, 8, 9}, {10, 11, 12}, {13, 15}};

    /**
     * The value range of each table group of {@link #GROUPS}.
     */
    private static final int[] GROUP_RANGES = {2, 3, 4, 6, 8, 16};

    /**
     * The escape tables 16 to 23 and 24 to 31, whose escape fields have the bits of
     * {@link Mp3EncoderTables#LINBITS}.
     */
    private static final int[][] ESCAPE_FAMILIES = {{16, 17, 18, 19, 20, 21, 22, 23}, {24, 25, 26, 27, 28, 29, 30, 31}};

    /**
     * The stride of the per-table arrays: one more than the most bands a granule has.
     */
    private static final int STRIDE = 41;

    static {
        for (var pair = 0; pair < 256; pair++) {
            for (var w = 0; w < 4; w++) {
                var word = 0L;
                for (var f = 0; f < 4; f++) {
                    var table = PACKING[w][f];
                    if (table != 0) {
                        word |= (long) Mp3EncoderTables.PAIR_LENGTHS[table][pair] << (16 * f);
                    }
                }
                PACKED[4 * pair + w] = word;
            }
        }
    }

    /**
     * The magnitudes of the spectrum being coded.
     */
    private final int[] magnitudes = new int[576];

    /**
     * The bits of each table summed over the first {@code b} bands of the big-value region,
     * indexed {@code table * STRIDE + b}; a band adds {@link #UNUSABLE} for a table that cannot
     * code it.
     */
    private final int[] prefix = new int[32 * STRIDE];

    /**
     * The largest magnitude of each band of the big-value region.
     */
    private final int[] bandMax = new int[STRIDE];

    /**
     * The largest magnitude of the bands from each band to the end of the big-value region.
     */
    private final int[] suffixMax = new int[STRIDE + 1];

    /**
     * The bits of each table in the band being counted.
     */
    private final int[] bandBits = new int[32];

    /**
     * Scratch: the four packed length sums of the band being counted.
     */
    private final long[] words = new long[4];

    /**
     * Holds the table chosen by the last {@link #rangeBits} call.
     */
    private int chosenTable;

    /**
     * Counts the Huffman bits of a granule and records the region layout and tables in it.
     *
     * <p>The granule's {@link Mp3CodedGranule#values} and block type must be set; on return its
     * {@code bigValues}, {@code count1}, {@code count1Table}, {@code tableSelect},
     * {@code region0Count}, {@code region1Count} and {@code part3Length} hold the coding.
     *
     * @param granule the coded granule
     * @param widths the band widths of the granule's block type, as the decoder walks them: the
     * long-block widths, or for short blocks each band width once per window;
     * zero-terminated
     * @param search {@code true} to try every region split of long blocks and every usable
     * table, {@code false} for a fixed split and the smallest usable table group
     * (faster, a few bits more)
     * @return the number of Huffman bits
     */
    int code(Mp3CodedGranule granule, int[] widths, boolean search) {
        var values = granule.values;
        var abs = magnitudes;
        for (var i = 0; i < 576; i++) {
            var v = values[i];
            abs[i] = v < 0 ? -v : v;
        }
        var i = 576;
        while (i >= 2 && (abs[i - 1] | abs[i - 2]) == 0) {
            i -= 2;
        }
        var count1 = 0;
        while (i >= 4 && (abs[i - 1] | abs[i - 2] | abs[i - 3] | abs[i - 4]) <= 1) {
            i -= 4;
            count1++;
        }
        var bigEnd = i;
        granule.bigValues = bigEnd >> 1;
        granule.count1 = count1;

        var quadA = 0;
        var signs = 0;
        var lengthsA = Mp3EncoderTables.QUAD_LENGTHS[0];
        for (int k = bigEnd, end = bigEnd + 4 * count1; k < end; k += 4) {
            var a = abs[k];
            var b = abs[k + 1];
            var c = abs[k + 2];
            var d = abs[k + 3];
            signs += a + b + c + d;
            quadA += lengthsA[a << 3 | b << 2 | c << 1 | d];
        }
        var quadB = 4 * count1;
        granule.count1Table = quadB < quadA ? 1 : 0;
        var bits = Math.min(quadA, quadB) + signs;

        Arrays.fill(granule.tableSelect, 0);
        if (bigEnd == 0) {
            granule.region0Count = granule.blockType == Mp3CodedGranule.LONG ? 0
                    : granule.blockType == Mp3CodedGranule.SHORT ? 8 : 7;
            granule.region1Count = 0;
            granule.part3Length = bits;
            return bits;
        }

        var bands = prepareBands(widths, bigEnd);
        if (granule.blockType != Mp3CodedGranule.LONG) {
            var r0 = granule.blockType == Mp3CodedGranule.SHORT ? 8 : 7;
            granule.region0Count = r0;
            granule.region1Count = 0;
            var split = Math.min(r0 + 1, bands);
            bits += rangeBits(0, split, search);
            granule.tableSelect[0] = chosenTable;
            bits += rangeBits(split, bands, search);
            granule.tableSelect[1] = chosenTable;
            granule.part3Length = bits;
            return bits;
        }

        if (!search) {
            var r0 = 0;
            while (r0 + 1 < 16 && r0 + 1 < bands && bandEnd(widths, r0 + 1) * 3 < bigEnd) {
                r0++;
            }
            var r1 = 0;
            while (r1 + 1 < 8 && r0 + r1 + 2 < bands && bandEnd(widths, r0 + r1 + 2) * 3 < bigEnd * 2) {
                r1++;
            }
            bits += applySplit(granule, r0, r1, bands, false);
            granule.part3Length = bits;
            return bits;
        }

        var best = INFINITE;
        var bestR0 = 0;
        var bestR1 = 0;
        var suffix = suffixMax;
        suffix[bands] = 0;
        for (var b = bands - 1; b >= 0; b--) {
            suffix[b] = Math.max(suffix[b + 1], bandMax[b]);
        }
        var max0 = 0;
        for (var r0 = 0; r0 < 16; r0++) {
            var end0 = Math.min(r0 + 1, bands);
            max0 = Math.max(max0, bandMax[end0 - 1]);
            var cost0 = tableBits(0, end0, max0, true);
            if (cost0 < best) {
                var max1 = 0;
                for (var r1 = 0; r1 < 8; r1++) {
                    var end1 = Math.min(r0 + r1 + 2, bands);
                    if (end1 > end0) {
                        max1 = Math.max(max1, bandMax[end1 - 1]);
                    }
                    var cost = cost0 + tableBits(end0, end1, max1, true);
                    if (cost < best) {
                        cost += tableBits(end1, bands, suffix[end1], true);
                        if (cost < best) {
                            best = cost;
                            bestR0 = r0;
                            bestR1 = r1;
                        }
                    }
                    if (end1 == bands) {
                        break;
                    }
                }
            }
            if (end0 == bands) {
                break;
            }
        }
        bits += applySplit(granule, bestR0, bestR1, bands, true);
        granule.part3Length = bits;
        return bits;
    }

    /**
     * Records a long-block region split and its tables in a granule.
     *
     * @param granule the coded granule
     * @param r0 {@code region0_count}
     * @param r1 {@code region1_count}
     * @param bands the number of bands of the big-value region
     * @param search whether every usable table is tried
     * @return the big-value bits
     */
    private int applySplit(Mp3CodedGranule granule, int r0, int r1, int bands, boolean search) {
        var end0 = Math.min(r0 + 1, bands);
        var end1 = Math.min(r0 + r1 + 2, bands);
        granule.region0Count = r0;
        granule.region1Count = r1;
        var bits = rangeBits(0, end0, search);
        granule.tableSelect[0] = chosenTable;
        bits += rangeBits(end0, end1, search);
        granule.tableSelect[1] = chosenTable;
        bits += rangeBits(end1, bands, search);
        granule.tableSelect[2] = chosenTable;
        return bits;
    }

    /**
     * Splits the big-value region into bands, counts each band's bits under every table and
     * accumulates them into {@link #prefix}.
     *
     * @param widths the band widths, zero-terminated
     * @param bigEnd the end of the big-value region
     * @return the number of bands that overlap the big-value region
     */
    private int prepareBands(int[] widths, int bigEnd) {
        var sums = prefix;
        for (var t = 0; t < 32; t++) {
            sums[t * STRIDE] = 0;
        }
        var bands = 0;
        var start = 0;
        while (start < bigEnd) {
            var width = bands < 39 ? widths[bands] : 0;
            var end = width == 0 ? bigEnd : Math.min(start + width, bigEnd);
            countBand(bands, start, end);
            var row = bandBits;
            for (var t = 0; t < 32; t++) {
                var previous = sums[t * STRIDE + bands];
                sums[t * STRIDE + bands + 1] = previous + row[t];
            }
            start = end;
            bands++;
        }
        return bands;
    }

    /**
     * Counts the bits of one band under every table into {@link #bandBits}, and its largest value
     * into {@link #bandMax}.
     *
     * @param band the band index
     * @param start the first line
     * @param end the end line
     */
    private void countBand(int band, int start, int end) {
        var abs = magnitudes;
        var packed = PACKED;
        long s0 = 0;
        long s1 = 0;
        long s2 = 0;
        long s3 = 0;
        var nonzero = 0;
        var escapes = 0;
        var max = 0;
        for (var k = start; k < end; k += 2) {
            var x = abs[k];
            var y = abs[k + 1];
            max = Math.max(max, Math.max(x, y));
            var hx = Math.min(x, 15);
            var hy = Math.min(y, 15);
            var index = 4 * (hx << 4 | hy);
            s0 += packed[index];
            s1 += packed[index + 1];
            s2 += packed[index + 2];
            s3 += packed[index + 3];
            nonzero += (-x >>> 31) + (-y >>> 31);
            escapes += (hx == 15 ? 1 : 0) + (hy == 15 ? 1 : 0);
        }
        bandMax[band] = max;
        var bits = bandBits;
        Arrays.fill(bits, UNUSABLE);
        bits[0] = max == 0 ? 0 : UNUSABLE;
        var words = this.words;
        words[0] = s0;
        words[1] = s1;
        words[2] = s2;
        words[3] = s3;
        for (var w = 0; w < 4; w++) {
            for (var f = 0; f < 4; f++) {
                var table = PACKING[w][f];
                if (table == 0) {
                    continue;
                }
                var length = (int) (words[w] >>> (16 * f)) & 0xFFFF;
                if (table == 16 || table == 24) {
                    var family = ESCAPE_FAMILIES[table == 16 ? 0 : 1];
                    for (var t : family) {
                        var linbits = Mp3EncoderTables.LINBITS[t];
                        if (max <= 15 + (1 << linbits) - 1) {
                            bits[t] = length + nonzero + escapes * linbits;
                        }
                    }
                } else if (max < Mp3EncoderTables.TABLE_RANGE[table]) {
                    bits[table] = length + nonzero;
                }
            }
        }
    }

    /**
     * Returns the fewest bits that code a run of bands with one table and records that table in
     * {@link #chosenTable}.
     *
     * @param from the first band
     * @param to the end band
     * @param search whether every usable table is tried rather than the smallest usable group
     * @return the bits, {@code 0} for an empty run
     */
    private int rangeBits(int from, int to, boolean search) {
        var max = 0;
        for (var b = from; b < to; b++) {
            max = Math.max(max, bandMax[b]);
        }
        return tableBits(from, to, max, search);
    }

    /**
     * Returns the fewest bits that code a run of bands whose largest value is known with one
     * table, and records that table in {@link #chosenTable}.
     *
     * @param from the first band
     * @param to the end band
     * @param max the largest magnitude of the run
     * @param search whether every usable table is tried rather than the smallest usable group
     * @return the bits, {@code 0} for an empty or all-zero run
     */
    private int tableBits(int from, int to, int max, boolean search) {
        if (from >= to || max == 0) {
            chosenTable = 0;
            return 0;
        }
        var sums = prefix;
        var best = INFINITE;
        var bestTable = 0;
        if (max < 16) {
            for (var g = 0; g < GROUPS.length; g++) {
                if (GROUP_RANGES[g] <= max) {
                    continue;
                }
                for (var table : GROUPS[g]) {
                    var cost = sums[table * STRIDE + to] - sums[table * STRIDE + from];
                    if (cost < best) {
                        best = cost;
                        bestTable = table;
                    }
                }
                if (!search) {
                    break;
                }
            }
            if (!search) {
                chosenTable = bestTable;
                return best;
            }
        }
        for (var family : ESCAPE_FAMILIES) {
            for (var table : family) {
                var linbits = Mp3EncoderTables.LINBITS[table];
                if (max > 15 + (1 << linbits) - 1) {
                    continue;
                }
                var cost = sums[table * STRIDE + to] - sums[table * STRIDE + from];
                if (cost < best) {
                    best = cost;
                    bestTable = table;
                }
                break;
            }
        }
        chosenTable = bestTable;
        return best;
    }

    /**
     * Writes the Huffman-coded spectrum of a granule whose layout {@link #code} computed.
     *
     * @param granule the coded granule
     * @param widths the band widths passed to {@link #code}
     * @param out the destination
     */
    void write(Mp3CodedGranule granule, int[] widths, MsbBitWriter out) {
        var values = granule.values;
        var bigEnd = granule.bigValues * 2;
        var region0End = Math.min(bandEnd(widths, granule.region0Count + 1), bigEnd);
        var region1End = granule.blockType != Mp3CodedGranule.LONG ? bigEnd
                : Math.min(bandEnd(widths, granule.region0Count + granule.region1Count + 2), bigEnd);
        writePairs(values, 0, region0End, granule.tableSelect[0], out);
        writePairs(values, region0End, region1End, granule.tableSelect[1], out);
        writePairs(values, region1End, bigEnd, granule.tableSelect[2], out);
        var codes = Mp3EncoderTables.QUAD_CODES[granule.count1Table];
        var lengths = Mp3EncoderTables.QUAD_LENGTHS[granule.count1Table];
        for (int k = bigEnd, end = bigEnd + 4 * granule.count1; k < end; k += 4) {
            var a = values[k];
            var b = values[k + 1];
            var c = values[k + 2];
            var d = values[k + 3];
            var quad = (a != 0 ? 8 : 0) | (b != 0 ? 4 : 0) | (c != 0 ? 2 : 0) | (d != 0 ? 1 : 0);
            out.write(codes[quad], lengths[quad]);
            if (a != 0) {
                out.write(a < 0 ? 1 : 0, 1);
            }
            if (b != 0) {
                out.write(b < 0 ? 1 : 0, 1);
            }
            if (c != 0) {
                out.write(c < 0 ? 1 : 0, 1);
            }
            if (d != 0) {
                out.write(d < 0 ? 1 : 0, 1);
            }
        }
    }

    /**
     * Returns the line where a band ends, summing the widths of the bands before it.
     *
     * @param widths the band widths, zero-terminated
     * @param bands the number of bands
     * @return the end line, at most 576
     */
    private static int bandEnd(int[] widths, int bands) {
        var end = 0;
        for (var b = 0; b < bands && widths[b] != 0; b++) {
            end += widths[b];
        }
        return Math.min(end, 576);
    }

    /**
     * Writes a run of value pairs with one big-value table.
     *
     * @param values the signed values
     * @param from the first line, even
     * @param to the end line, even
     * @param table the big-value table number, from 1 to 31
     * @param out the destination
     */
    private static void writePairs(int[] values, int from, int to, int table, MsbBitWriter out) {
        if (from >= to || table == 0) {
            return;
        }
        var codes = Mp3EncoderTables.PAIR_CODES[table];
        var lengths = Mp3EncoderTables.PAIR_LENGTHS[table];
        var linbits = Mp3EncoderTables.LINBITS[table];
        for (var k = from; k < to; k += 2) {
            var x = values[k];
            var y = values[k + 1];
            var ax = x < 0 ? -x : x;
            var ay = y < 0 ? -y : y;
            if (linbits == 0) {
                out.write(codes[ax << 4 | ay], lengths[ax << 4 | ay]);
                if (ax != 0) {
                    out.write(x < 0 ? 1 : 0, 1);
                }
                if (ay != 0) {
                    out.write(y < 0 ? 1 : 0, 1);
                }
            } else {
                var hx = Math.min(ax, 15);
                var hy = Math.min(ay, 15);
                out.write(codes[hx << 4 | hy], lengths[hx << 4 | hy]);
                if (hx == 15) {
                    out.write(ax - 15, linbits);
                }
                if (ax != 0) {
                    out.write(x < 0 ? 1 : 0, 1);
                }
                if (hy == 15) {
                    out.write(ay - 15, linbits);
                }
                if (ay != 0) {
                    out.write(y < 0 ? 1 : 0, 1);
                }
            }
        }
    }
}
