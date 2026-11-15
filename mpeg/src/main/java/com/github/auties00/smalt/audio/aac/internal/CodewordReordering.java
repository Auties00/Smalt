/* Ports libxaac (decoder/ixheaacd_huff_code_reorder.c: the codebook priorities, the section sorting, the
   segmentation grid and the set-wise decoding of the non-priority codewords).
   Copyright (C) 2018 The Android Open Source Project, Ittiam Systems; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Decodes the Huffman codeword reordering of the error resilient object types (ISO/IEC 14496-3
 * subclause 4.6.3.3, {@code aacSpectralDataResilienceFlag}): the {@code reordered_spectral_data} of one
 * channel stream.
 *
 * <p>The codewords of a stream are sorted by codebook priority and assigned to segments: each priority
 * codeword starts a segment, and the remaining codewords are decoded in sets of one codeword per
 * segment, each set reading the bits its segments have left in the opposite direction of the previous
 * set and continuing a codeword in the next segment when it does not fit. Short-window spectra are
 * interleaved in units of four lines across the windows before sorting.
 *
 * <p>Errors are concealed by muting: a codeword that does not decode, overruns its segment, exceeds its
 * longest length or holds a value larger than its (virtual) codebook codes contributes zeros, as does a
 * codeword the segments run out of bits for; side information that leaves no room for a segment (a longest
 * codeword longer than the data) mutes the whole stream. Only data shorter than the declared length is
 * rejected.
 *
 * <p>Instances are not thread-safe.
 */
final class CodewordReordering {
    /**
     * The number of spectral lines of an interleaving unit.
     */
    private static final int UNIT = 4;

    /**
     * The longest codeword of each codebook, sign bits and escapes included, indexed by the (virtual)
     * codebook.
     */
    private static final int[] MAX_CODEWORD = {
            0, 11, 9, 20, 16, 13, 11, 14, 12, 17, 14, 49, 0, 0, 0, 0,
            14, 17, 21, 21, 25, 25, 29, 29, 29, 29, 33, 33, 33, 37, 37, 41
    };

    /**
     * The sorting priority of each codebook, 0 for the codebooks that code no values.
     */
    private static final int[] PRIORITY = {
            0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 22, 0, 0, 0, 0,
            6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21
    };

    /**
     * The longest saved partial codeword, in bits.
     */
    private static final int MAX_SAVED = 64;

    /**
     * The codebook of each section, in spectral order.
     */
    private final int[] sectionCodebooks = new int[1024 / UNIT];

    /**
     * The number of lines of each section, in spectral order.
     */
    private final int[] sectionLines = new int[1024 / UNIT];

    /**
     * The number of sections built so far.
     */
    private int sectionCount;

    /**
     * The number of sorted sections that code values.
     */
    private int sortedCount;

    /**
     * The section of each sorted position.
     */
    private final int[] sorted = new int[1024 / UNIT];

    /**
     * The line offset of each section in the interleaved spectrum.
     */
    private final int[] sectionOffsets = new int[1024 / UNIT];

    /**
     * The codebook of each codeword, in sorted order.
     */
    private final int[] codewordBooks = new int[1024 / 2];

    /**
     * The interleaved line of the first value of each codeword, in sorted order.
     */
    private final int[] codewordLines = new int[1024 / 2];

    /**
     * Whether each codeword is decoded.
     */
    private final boolean[] decoded = new boolean[1024 / 2];

    /**
     * The number of saved bits of each partially read codeword.
     */
    private final int[] savedCount = new int[1024 / 2];

    /**
     * The saved bits of each partially read codeword, {@link #MAX_SAVED} per codeword.
     */
    private final byte[] savedBits = new byte[1024 / 2 * MAX_SAVED];

    /**
     * The left bit position of each segment, relative to the start of the reordered data.
     */
    private final int[] segmentLeft = new int[1024 / 2];

    /**
     * The right bit position of each segment, inclusive.
     */
    private final int[] segmentRight = new int[1024 / 2];

    /**
     * The number of unread bits of each segment.
     */
    private final int[] segmentBits = new int[1024 / 2];

    /**
     * The interleaved spectrum.
     */
    private final int[] interleaved = new int[1024];

    /**
     * The scratch buffer a codeword is assembled in.
     */
    private final byte[] scratch = new byte[(2 * MAX_SAVED) / 8 + 16];

    /**
     * The reader over {@link #scratch}.
     */
    private final AacBitReader scratchReader = new AacBitReader(scratch);

    /**
     * Whether the last stream decoded had codewords muted.
     */
    private boolean concealed;

    /**
     * {@return whether the last call to {@link #decode} muted codewords}
     */
    boolean concealed() {
        return concealed;
    }

    /**
     * Decodes {@code reordered_spectral_data} into quantized values.
     *
     * @param reader the bit source, at the first bit of the reordered data; left after it
     * @param reorderedBits the length of the reordered data ({@code length_of_reordered_spectral_data})
     * @param longestCodeword the longest codeword ({@code length_of_longest_codeword})
     * @param codebooks the (virtual) codebook of each band, indexed {@code group * MAX_BANDS + band}, with
     * noise and intensity bands as any codebook above 12 and below 16
     * @param offsets the band offsets of one window
     * @param maxBands the number of transmitted bands per group
     * @param groupLengths the number of windows of each group
     * @param groupCount the number of groups
     * @param windowCount the number of windows, 1 or 8
     * @param windowLength the number of lines of one window
     * @param quant receives the quantized values, window by window
     * @throws MediaException.Malformed if the data is shorter than {@code reorderedBits}
     */
    void decode(AacBitReader reader, int reorderedBits, int longestCodeword, int[] codebooks, int[] offsets,
                int maxBands, int[] groupLengths, int groupCount, int windowCount, int windowLength, int[] quant) {
        var start = reader.bitPosition();
        if (reorderedBits > reader.bitsLeft()) {
            throw new MediaException.Malformed("AAC reordered spectral data truncated");
        }
        var sections = buildSections(codebooks, offsets, maxBands, groupLengths, groupCount, windowCount);
        Arrays.fill(interleaved, 0, windowCount * windowLength, 0);
        var codewords = sortSections(sections);
        concealed = false;
        if (codewords > 0) {
            var segments = reorderedBits < longestCodeword ? 0
                    : buildSegments(sections, reorderedBits, longestCodeword);
            if (segments == 0) {
                concealed = true;
            } else {
                decodePriority(reader, start, segments);
                decodeSets(reader, start, segments, codewords);
                for (var codeword = 0; codeword < codewords; codeword++) {
                    if (!decoded[codeword]) {
                        mute(codeword);
                    }
                }
            }
        }
        if (windowCount == 8) {
            var units = windowLength / UNIT;
            for (var window = 0; window < 8; window++) {
                for (var unit = 0; unit < units; unit++) {
                    System.arraycopy(interleaved, (unit * 8 + window) * UNIT, quant, window * windowLength + unit * UNIT,
                            UNIT);
                }
            }
        } else {
            System.arraycopy(interleaved, 0, quant, 0, windowLength);
        }
        reader.seek(start + reorderedBits);
    }

    /**
     * Splits the spectrum into sections of one codebook, in the line order of the reordering: band by
     * band for long windows, unit by unit and window by window within each band for short windows.
     *
     * @return the number of sections
     */
    private int buildSections(int[] codebooks, int[] offsets, int maxBands, int[] groupLengths, int groupCount,
                              int windowCount) {
        sectionCount = 0;
        for (var band = 0; band < maxBands; band++) {
            var width = offsets[band + 1] - offsets[band];
            if (windowCount == 1) {
                append(codebook(codebooks[band]), width);
                continue;
            }
            for (var unit = 0; unit < width / UNIT; unit++) {
                for (var group = 0; group < groupCount; group++) {
                    var codebook = codebook(codebooks[group * ChannelStream.MAX_BANDS + band]);
                    for (var window = 0; window < groupLengths[group]; window++) {
                        append(codebook, UNIT);
                    }
                }
            }
        }
        return sectionCount;
    }

    /**
     * Appends lines to the last section, or starts a section when the codebook changes.
     *
     * @param codebook the codebook of the lines
     * @param lines the number of lines
     */
    private void append(int codebook, int lines) {
        if (sectionCount > 0 && sectionCodebooks[sectionCount - 1] == codebook) {
            sectionLines[sectionCount - 1] += lines;
            return;
        }
        sectionCodebooks[sectionCount] = codebook;
        sectionLines[sectionCount] = lines;
        sectionCount++;
    }

    /**
     * Maps a band codebook to the codebook of the reordering: noise and intensity bands code no values.
     *
     * @param codebook the band codebook
     * @return the codebook, 0 for bands without values
     */
    private static int codebook(int codebook) {
        return codebook >= Codebooks.RESERVED_HCB && codebook < 16 ? Codebooks.ZERO_HCB : codebook;
    }

    /**
     * Returns the number of values a codeword of a (virtual) codebook codes.
     *
     * @param codebook the spectrum codebook of a section, from 1 to 11 or 16 to 31
     * @return 4 for codebooks 1 to 4, 2 for the others
     */
    private static int dimension(int codebook) {
        return codebook >= 1 && codebook <= 4 ? 4 : 2;
    }

    /**
     * Returns the codebook whose Huffman table decodes a (virtual) codebook.
     *
     * @param codebook the spectrum codebook of a section, from 1 to 11 or 16 to 31
     * @return the codebook itself, or 11 for a virtual codebook
     */
    private static int table(int codebook) {
        return codebook >= 16 ? Codebooks.ESC_HCB : codebook;
    }

    /**
     * Sorts the sections by codebook priority, keeping the spectral order within a priority, and lists
     * the codewords in sorted order.
     *
     * @param sections the number of sections
     * @return the number of codewords
     */
    private int sortSections(int sections) {
        var line = 0;
        for (var section = 0; section < sections; section++) {
            sectionOffsets[section] = line;
            line += sectionLines[section];
        }
        var position = 0;
        for (var priority = 22; priority >= 1; priority--) {
            for (var section = 0; section < sections; section++) {
                if (PRIORITY[sectionCodebooks[section]] == priority) {
                    sorted[position++] = section;
                }
            }
        }
        var codewords = 0;
        for (var i = 0; i < position; i++) {
            var section = sorted[i];
            var codebook = sectionCodebooks[section];
            var dimension = dimension(codebook);
            var count = sectionLines[section] / dimension;
            for (var k = 0; k < count; k++) {
                if (codewords == codewordBooks.length) {
                    throw new MediaException.Malformed("too many AAC codewords");
                }
                codewordBooks[codewords] = codebook;
                codewordLines[codewords] = sectionOffsets[section] + k * dimension;
                codewords++;
            }
        }
        sortedCount = position;
        return codewords;
    }

    /**
     * Lays out one segment per priority codeword, each as wide as the longest codeword of its codebook or
     * of the stream, the last one extended to the end of the reordered data.
     *
     * @param sections the number of sections
     * @param reorderedBits the length of the reordered data
     * @param longestCodeword the longest codeword of the stream
     * @return the number of segments, 0 when no segment fits
     */
    private int buildSegments(int sections, int reorderedBits, int longestCodeword) {
        var segments = 0;
        var begin = 0;
        layout:
        for (var i = 0; i < sortedCount; i++) {
            var section = sorted[i];
            var codebook = sectionCodebooks[section];
            var width = Math.min(MAX_CODEWORD[codebook], longestCodeword);
            var count = sectionLines[section] / dimension(codebook);
            for (var k = 0; k < count; k++) {
                if (begin + width <= reorderedBits) {
                    segmentLeft[segments] = begin;
                    segmentRight[segments] = begin + width - 1;
                    segmentBits[segments] = width;
                    begin += width;
                    segments++;
                } else {
                    if (segments == 0) {
                        break layout;
                    }
                    var last = segments - 1;
                    segmentRight[last] = reorderedBits - 1;
                    segmentBits[last] = reorderedBits - segmentLeft[last];
                    break layout;
                }
            }
        }
        return segments;
    }

    /**
     * Decodes the priority codewords, one at the start of each segment.
     *
     * @param reader the bit source
     * @param start the bit position of the reordered data
     * @param segments the number of segments
     */
    private void decodePriority(AacBitReader reader, long start, int segments) {
        for (var segment = 0; segment < segments; segment++) {
            decoded[segment] = true;
            savedCount[segment] = 0;
            reader.seek(start + segmentLeft[segment]);
            try {
                Codebooks.decodeCodeword(reader, table(codewordBooks[segment]), interleaved, codewordLines[segment]);
            } catch (MediaException.Malformed exception) {
                mute(segment);
                segmentBits[segment] = 0;
                continue;
            }
            var length = (int) (reader.bitPosition() - start - segmentLeft[segment]);
            if (length > segmentBits[segment]) {
                mute(segment);
                segmentBits[segment] = 0;
                continue;
            }
            segmentLeft[segment] += length;
            segmentBits[segment] -= length;
            checkLargest(segment);
        }
    }

    /**
     * Mutes a decoded codeword holding a value larger than its codebook codes.
     *
     * @param codeword the index of the codeword, in decoding order
     */
    private void checkLargest(int codeword) {
        var codebook = codewordBooks[codeword];
        var largest = Codebooks.largestValue(codebook);
        var line = codewordLines[codeword];
        for (var i = 0; i < dimension(codebook); i++) {
            if (Math.abs(interleaved[line + i]) > largest) {
                mute(codeword);
                return;
            }
        }
    }

    /**
     * Zeros the values of a codeword and records the concealment.
     *
     * @param codeword the index of the codeword, in decoding order
     */
    private void mute(int codeword) {
        var line = codewordLines[codeword];
        Arrays.fill(interleaved, line, line + dimension(codewordBooks[codeword]), 0);
        concealed = true;
    }

    /**
     * Decodes the codewords after the priority codewords, set by set.
     *
     * @param reader the bit source
     * @param start the bit position of the reordered data
     * @param segments the number of segments
     * @param codewords the number of codewords
     */
    private void decodeSets(AacBitReader reader, long start, int segments, int codewords) {
        for (var codeword = segments; codeword < codewords; codeword++) {
            decoded[codeword] = false;
            savedCount[codeword] = 0;
        }
        var backward = true;
        for (var first = segments; first < codewords; first += segments) {
            var count = Math.min(segments, codewords - first);
            for (var trial = 0; trial < segments; trial++) {
                for (var k = 0; k < count; k++) {
                    var segment = (k + trial) % segments;
                    var codeword = first + k;
                    if (segmentBits[segment] > 0 && !decoded[codeword]) {
                        continueCodeword(reader, start, segment, codeword, backward);
                    }
                }
            }
            backward = !backward;
        }
    }

    /**
     * Continues a codeword with the unread bits of a segment, read in the set's direction, and decodes it
     * when it is complete; otherwise saves every bit read for the next segment.
     *
     * @param reader the bit source
     * @param start the bit position of the reordered data
     * @param segment the index of the segment the bits come from
     * @param codeword the index of the codeword being continued, in decoding order
     * @param backward whether the set reads its segments from right to left
     */
    private void continueCodeword(AacBitReader reader, long start, int segment, int codeword, boolean backward) {
        var saved = savedCount[codeword];
        var available = segmentBits[segment];
        var total = saved + available;
        Arrays.fill(scratch, (byte) 0);
        var base = codeword * MAX_SAVED;
        for (var i = 0; i < saved; i++) {
            setBit(i, savedBits[base + i]);
        }
        for (var i = 0; i < available; i++) {
            var position = backward ? segmentRight[segment] - i : segmentLeft[segment] + i;
            setBit(saved + i, reader.bitAt(start + position));
        }
        scratchReader.reset(scratch, 0, scratch.length);
        var length = Integer.MAX_VALUE;
        try {
            Codebooks.decodeCodeword(scratchReader, table(codewordBooks[codeword]), interleaved,
                    codewordLines[codeword]);
            length = (int) scratchReader.bitPosition();
        } catch (MediaException.Malformed exception) {
            // an escape sequence too long for any codeword
        }
        if (length == Integer.MAX_VALUE || (length > total && total > MAX_SAVED)) {
            fail(segment, codeword, available, backward);
            return;
        }
        if (length <= total) {
            var consumed = length - saved;
            segmentBits[segment] -= consumed;
            if (backward) {
                segmentRight[segment] -= consumed;
            } else {
                segmentLeft[segment] += consumed;
            }
            decoded[codeword] = true;
            savedCount[codeword] = 0;
            checkLargest(codeword);
            return;
        }
        for (var i = saved; i < total; i++) {
            savedBits[base + i] = (byte) (scratch[i >>> 3] >>> (7 - (i & 7)) & 1);
        }
        savedCount[codeword] = total;
        segmentBits[segment] = 0;
        if (backward) {
            segmentRight[segment] -= available;
        } else {
            segmentLeft[segment] += available;
        }
    }

    /**
     * Gives up a codeword that cannot be decoded: mutes it and spends the bits the segment had left.
     *
     * @param segment the index of the segment
     * @param codeword the index of the codeword, in decoding order
     * @param available the bits the segment had left
     * @param backward whether the set reads its segments from right to left
     */
    private void fail(int segment, int codeword, int available, boolean backward) {
        mute(codeword);
        decoded[codeword] = true;
        savedCount[codeword] = 0;
        segmentBits[segment] = 0;
        if (backward) {
            segmentRight[segment] -= available;
        } else {
            segmentLeft[segment] += available;
        }
    }

    /**
     * Sets a bit of the scratch buffer.
     *
     * @param index the bit index
     * @param bit the bit, 0 or 1
     */
    private void setBit(int index, int bit) {
        scratch[index >>> 3] |= (byte) (bit << (7 - (index & 7)));
    }
}
