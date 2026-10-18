package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;

/**
 * Counts, plans and writes the Huffman coding of quantized spectra: the bits every codebook
 * needs for a band, the optimal partition of a window group's bands into sections, and the
 * codewords, sign bits and escape sequences themselves.
 *
 * <p>Quantized values are signed integers of magnitude at most 8191; a band is a contiguous run
 * whose length is a multiple of four.
 */
public final class SpectralCoder {
    /**
     * The cost standing for a codebook that cannot code a band.
     */
    public static final int FORBIDDEN = 1 << 24;

    /**
     * The number of codebook slots per band in cost arrays: the zero codebook and codebooks 1
     * to 11.
     */
    public static final int BOOKS = 12;

    /**
     * The codeword lengths of each spectrum codebook.
     */
    private static final byte[][] LENGTHS = HuffmanTables.SPECTRUM_LENGTHS;

    /**
     * The codewords of each spectrum codebook.
     */
    private static final int[][] CODES = HuffmanTables.SPECTRUM_CODES;

    /**
     * The best cost reaching each band boundary in the section planner.
     */
    private final int[] best = new int[65];

    /**
     * The section start of the best plan reaching each boundary.
     */
    private final int[] from = new int[65];

    /**
     * The codebook of the last section of the best plan reaching each boundary.
     */
    private final int[] bookAt = new int[65];

    /**
     * The largest number of header-length levels a plan can use: 64 bands at the short-window
     * escape of 7 bands.
     */
    private static final int MAX_LEVELS = 64 / 7 + 1;

    /**
     * The prefix sums of each codebook's band costs, including the extra bit of an all-zero band
     * coded with a spectrum codebook: 65 per codebook.
     */
    private final int[] prefix = new int[BOOKS * 65];

    /**
     * The first band a section of each codebook may start at: one past the last band that
     * forbids the codebook.
     */
    private final int[] firstStart = new int[BOOKS];

    /**
     * The monotonic queues of candidate section starts, one per codebook and header-length level,
     * 65 entries each.
     */
    private final int[] queues = new int[BOOKS * MAX_LEVELS * 65];

    /**
     * The index of the front of each queue in {@link #queues}.
     */
    private final int[] heads = new int[BOOKS * MAX_LEVELS];

    /**
     * The index after the back of each queue in {@link #queues}.
     */
    private final int[] tails = new int[BOOKS * MAX_LEVELS];

    /**
     * Creates a coder with its planning scratch memory.
     */
    public SpectralCoder() {
    }

    /**
     * Counts the bits of one band in every codebook able to code it.
     *
     * <p>Codebooks with a largest value below the band's largest magnitude cost
     * {@link #FORBIDDEN}; the zero codebook costs 0 when the band is all zero. When
     * {@code exhaustive} is false only the two cheapest-looking codebook pairs are counted, and
     * the others are forbidden.
     *
     * @param quant      the signed quantized values
     * @param start      the first line of the band
     * @param end        the line after the last
     * @param maxValue   the largest magnitude of the band
     * @param costs      receives {@link #BOOKS} costs at {@code costOffset}
     * @param costOffset the index of the zero codebook's cost
     * @param exhaustive whether to count every eligible codebook
     */
    public static void countBand(int[] quant, int start, int end, int maxValue, int[] costs, int costOffset,
                                 boolean exhaustive) {
        for (var book = 0; book < BOOKS; book++) {
            costs[costOffset + book] = FORBIDDEN;
        }
        if (maxValue == 0) {
            costs[costOffset] = 0;
            if (!exhaustive) {
                return;
            }
        }
        var pairs = 0;
        if (maxValue <= 1) {
            countQuadsSigned(quant, start, end, costs, costOffset);
            pairs++;
        }
        if (maxValue <= 2 && (exhaustive || pairs < 2)) {
            countQuadsUnsigned(quant, start, end, costs, costOffset);
            pairs++;
        }
        if (maxValue <= 4 && (exhaustive || pairs < 2)) {
            countPairsSigned(quant, start, end, costs, costOffset);
            pairs++;
        }
        if (maxValue <= 7 && (exhaustive || pairs < 2)) {
            countPairsUnsigned(quant, start, end, costs, costOffset, 7, 8);
            pairs++;
        }
        if (maxValue <= 12 && (exhaustive || pairs < 2)) {
            countPairsUnsigned(quant, start, end, costs, costOffset, 9, 13);
            pairs++;
        }
        if (exhaustive || pairs < 2) {
            costs[costOffset + Codebooks.ESC_HCB] = countEscape(quant, start, end);
        }
    }

    /**
     * Counts codebooks 1 and 2.
     *
     * @param quant      the values
     * @param start      the first line
     * @param end        the line after the last
     * @param costs      the cost array
     * @param costOffset the cost offset
     */
    private static void countQuadsSigned(int[] quant, int start, int end, int[] costs, int costOffset) {
        var one = LENGTHS[1];
        var two = LENGTHS[2];
        var bits1 = 0;
        var bits2 = 0;
        for (var k = start; k < end; k += 4) {
            var index = 27 * (quant[k] + 1) + 9 * (quant[k + 1] + 1) + 3 * (quant[k + 2] + 1) + quant[k + 3] + 1;
            bits1 += one[index];
            bits2 += two[index];
        }
        costs[costOffset + 1] = bits1;
        costs[costOffset + 2] = bits2;
    }

    /**
     * Counts codebooks 3 and 4.
     *
     * @param quant      the values
     * @param start      the first line
     * @param end        the line after the last
     * @param costs      the cost array
     * @param costOffset the cost offset
     */
    private static void countQuadsUnsigned(int[] quant, int start, int end, int[] costs, int costOffset) {
        var three = LENGTHS[3];
        var four = LENGTHS[4];
        var bits3 = 0;
        var bits4 = 0;
        var signs = 0;
        for (var k = start; k < end; k += 4) {
            var a = Math.abs(quant[k]);
            var b = Math.abs(quant[k + 1]);
            var c = Math.abs(quant[k + 2]);
            var d = Math.abs(quant[k + 3]);
            var index = 27 * a + 9 * b + 3 * c + d;
            bits3 += three[index];
            bits4 += four[index];
            signs += (a != 0 ? 1 : 0) + (b != 0 ? 1 : 0) + (c != 0 ? 1 : 0) + (d != 0 ? 1 : 0);
        }
        costs[costOffset + 3] = bits3 + signs;
        costs[costOffset + 4] = bits4 + signs;
    }

    /**
     * Counts codebooks 5 and 6.
     *
     * @param quant      the values
     * @param start      the first line
     * @param end        the line after the last
     * @param costs      the cost array
     * @param costOffset the cost offset
     */
    private static void countPairsSigned(int[] quant, int start, int end, int[] costs, int costOffset) {
        var five = LENGTHS[5];
        var six = LENGTHS[6];
        var bits5 = 0;
        var bits6 = 0;
        for (var k = start; k < end; k += 2) {
            var index = 9 * (quant[k] + 4) + quant[k + 1] + 4;
            bits5 += five[index];
            bits6 += six[index];
        }
        costs[costOffset + 5] = bits5;
        costs[costOffset + 6] = bits6;
    }

    /**
     * Counts an unsigned pair codebook couple (7 and 8, or 9 and 10).
     *
     * @param quant      the values
     * @param start      the first line
     * @param end        the line after the last
     * @param costs      the cost array
     * @param costOffset the cost offset
     * @param first      the first codebook of the couple
     * @param modulo     the symbol index modulus of the couple
     */
    private static void countPairsUnsigned(int[] quant, int start, int end, int[] costs, int costOffset,
                                           int first, int modulo) {
        var lengthsA = LENGTHS[first];
        var lengthsB = LENGTHS[first + 1];
        var bitsA = 0;
        var bitsB = 0;
        var signs = 0;
        for (var k = start; k < end; k += 2) {
            var a = Math.abs(quant[k]);
            var b = Math.abs(quant[k + 1]);
            var index = modulo * a + b;
            bitsA += lengthsA[index];
            bitsB += lengthsB[index];
            signs += (a != 0 ? 1 : 0) + (b != 0 ? 1 : 0);
        }
        costs[costOffset + first] = bitsA + signs;
        costs[costOffset + first + 1] = bitsB + signs;
    }

    /**
     * Counts the escape codebook.
     *
     * @param quant the values
     * @param start the first line
     * @param end   the line after the last
     * @return the bits
     */
    private static int countEscape(int[] quant, int start, int end) {
        var lengths = LENGTHS[Codebooks.ESC_HCB];
        var bits = 0;
        for (var k = start; k < end; k += 2) {
            var a = Math.abs(quant[k]);
            var b = Math.abs(quant[k + 1]);
            bits += lengths[17 * Math.min(a, 16) + Math.min(b, 16)];
            bits += (a != 0 ? 1 : 0) + (b != 0 ? 1 : 0);
            if (a >= 16) {
                bits += escapeBits(a);
            }
            if (b >= 16) {
                bits += escapeBits(b);
            }
        }
        return bits;
    }

    /**
     * Returns the length of the escape sequence of a magnitude of at least 16.
     *
     * @param value the magnitude
     * @return the prefix, separator and word bits
     */
    private static int escapeBits(int value) {
        var exponent = 31 - Integer.numberOfLeadingZeros(value);
        return 2 * exponent - 3;
    }

    /**
     * Returns the bits of a section's codebook and length fields.
     *
     * @param length      the section length in bands
     * @param shortWindow whether the window sequence is eight short
     * @return the header bits
     */
    public static int sectionHeaderBits(int length, boolean shortWindow) {
        var lengthBits = shortWindow ? 3 : 5;
        var escape = (1 << lengthBits) - 1;
        return 4 + (length / escape + 1) * lengthBits;
    }

    /**
     * Partitions the bands of one window group into sections with minimal total cost.
     *
     * <p>A band coded with a spectrum codebook although all zero is charged one extra bit, the
     * scalefactor difference of zero it then carries. Among plans of equal cost, the last section
     * is the one starting at the highest band, and among those the one with the lowest codebook;
     * the rule applies again to the plan of the bands before it.
     *
     * @implNote This implementation computes the same dynamic program as the direct search over
     * every section start and codebook, in linear time. A section's cost is
     * {@code best[start] - prefix[start] + prefix[end] + header(end - start)}, where the header
     * length only changes every {@code 2^lengthBits - 1} bands; so for each codebook and header
     * level, the candidate starts form a window that slides with the section end, and a
     * monotonic queue holding the starts in increasing order with strictly increasing
     * {@code best[start] - prefix[start]} yields the best start of the window, the highest one on
     * ties. A band that forbids a codebook empties that codebook's queues, since every longer
     * section contains it.
     *
     * @param costs       the costs of each band, {@link #BOOKS} per band from index 0
     * @param bands       the number of bands
     * @param shortWindow whether the window sequence is eight short
     * @param books       receives the codebook of each band
     * @return the total bits of the section data and spectral data
     */
    public int plan(int[] costs, int bands, boolean shortWindow, int[] books) {
        var lengthBits = shortWindow ? 3 : 5;
        var escape = (1 << lengthBits) - 1;
        var levels = bands / escape + 1;
        best[0] = 0;
        for (var book = 0; book < BOOKS; book++) {
            prefix[book * 65] = 0;
            firstStart[book] = 0;
            for (var level = 0; level < levels; level++) {
                var queue = book * MAX_LEVELS + level;
                heads[queue] = queue * 65;
                tails[queue] = queue * 65;
            }
        }
        for (var end = 1; end <= bands; end++) {
            var band = end - 1;
            var base = band * BOOKS;
            var zero = costs[base] == 0 ? 1 : 0;
            var bestCost = Integer.MAX_VALUE;
            var bestStart = -1;
            var bestBook = 0;
            for (var book = 0; book < BOOKS; book++) {
                var cost = costs[base + book];
                var sums = book * 65;
                prefix[sums + end] = prefix[sums + band] + cost + (book != 0 ? zero : 0);
                if (cost >= FORBIDDEN) {
                    firstStart[book] = end;
                }
                var lowest = firstStart[book];
                var ending = prefix[sums + end];
                for (var level = 0; level < levels; level++) {
                    var queue = book * MAX_LEVELS + level;
                    var entering = level == 0 ? band : end - level * escape;
                    if (entering >= lowest) {
                        var key = best[entering] - prefix[sums + entering];
                        var tail = tails[queue];
                        var head = heads[queue];
                        while (tail > head) {
                            var last = queues[tail - 1];
                            if (best[last] - prefix[sums + last] < key) {
                                break;
                            }
                            tail--;
                        }
                        queues[tail] = entering;
                        tails[queue] = tail + 1;
                    }
                    var bound = Math.max(lowest, end - (level + 1) * escape + 1);
                    var head = heads[queue];
                    var tail = tails[queue];
                    while (head < tail && queues[head] < bound) {
                        head++;
                    }
                    heads[queue] = head;
                    if (head == tail) {
                        continue;
                    }
                    var start = queues[head];
                    var total = best[start] - prefix[sums + start] + ending + 4 + (level + 1) * lengthBits;
                    if (total < bestCost || (total == bestCost && start > bestStart)) {
                        bestCost = total;
                        bestStart = start;
                        bestBook = book;
                    }
                }
            }
            best[end] = bestCost;
            from[end] = bestStart;
            bookAt[end] = bestBook;
        }
        var end = bands;
        while (end > 0) {
            var start = from[end];
            for (var band = start; band < end; band++) {
                books[band] = bookAt[end];
            }
            end = start;
        }
        return best[bands];
    }

    /**
     * Writes the section data of one window group.
     *
     * @param writer      the bit sink
     * @param books       the codebook of each band
     * @param bands       the number of bands
     * @param shortWindow whether the window sequence is eight short
     */
    public static void writeSections(MsbBitWriter writer, int[] books, int bands, boolean shortWindow) {
        var lengthBits = shortWindow ? 3 : 5;
        var escape = (1 << lengthBits) - 1;
        var band = 0;
        while (band < bands) {
            var book = books[band];
            var end = band + 1;
            while (end < bands && books[end] == book) {
                end++;
            }
            writer.write(book, 4);
            var length = end - band;
            while (length >= escape) {
                writer.write(escape, lengthBits);
                length -= escape;
            }
            writer.write(length, lengthBits);
            band = end;
        }
    }

    /**
     * Writes the spectral data of one band.
     *
     * @param writer the bit sink
     * @param quant  the signed quantized values
     * @param start  the first line
     * @param end    the line after the last
     * @param book   the codebook, from 1 to 11
     */
    public static void writeBand(MsbBitWriter writer, int[] quant, int start, int end, int book) {
        var codes = CODES[book];
        var lengths = LENGTHS[book];
        switch (book) {
            case 1, 2 -> {
                for (var k = start; k < end; k += 4) {
                    var index = 27 * (quant[k] + 1) + 9 * (quant[k + 1] + 1) + 3 * (quant[k + 2] + 1) + quant[k + 3] + 1;
                    writer.write(codes[index], lengths[index]);
                }
            }
            case 3, 4 -> {
                for (var k = start; k < end; k += 4) {
                    var index = 27 * Math.abs(quant[k]) + 9 * Math.abs(quant[k + 1]) + 3 * Math.abs(quant[k + 2])
                            + Math.abs(quant[k + 3]);
                    writer.write(codes[index], lengths[index]);
                    for (var i = 0; i < 4; i++) {
                        if (quant[k + i] != 0) {
                            writer.write(quant[k + i] < 0 ? 1 : 0, 1);
                        }
                    }
                }
            }
            case 5, 6 -> {
                for (var k = start; k < end; k += 2) {
                    var index = 9 * (quant[k] + 4) + quant[k + 1] + 4;
                    writer.write(codes[index], lengths[index]);
                }
            }
            default -> {
                var modulo = book == 7 || book == 8 ? 8 : book == 11 ? 17 : 13;
                for (var k = start; k < end; k += 2) {
                    var a = Math.abs(quant[k]);
                    var b = Math.abs(quant[k + 1]);
                    var index = book == 11 ? 17 * Math.min(a, 16) + Math.min(b, 16) : modulo * a + b;
                    writer.write(codes[index], lengths[index]);
                    if (a != 0) {
                        writer.write(quant[k] < 0 ? 1 : 0, 1);
                    }
                    if (b != 0) {
                        writer.write(quant[k + 1] < 0 ? 1 : 0, 1);
                    }
                    if (book == 11) {
                        if (a >= 16) {
                            writeEscape(writer, a);
                        }
                        if (b >= 16) {
                            writeEscape(writer, b);
                        }
                    }
                }
            }
        }
    }

    /**
     * Writes the escape sequence of a magnitude from 16 to 8191.
     *
     * @param writer the bit sink
     * @param value  the magnitude
     */
    private static void writeEscape(MsbBitWriter writer, int value) {
        var exponent = 31 - Integer.numberOfLeadingZeros(value);
        var prefix = exponent - 4;
        writer.write((1 << (prefix + 1)) - 2, prefix + 1);
        writer.write(value - (1 << exponent), exponent);
    }
}
