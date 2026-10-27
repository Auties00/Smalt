package com.github.auties00.smalt.image.jp2.internal;

import java.util.Arrays;

/**
 * Tracks one code-block of a precinct while its packets are read: the codeword segments the
 * packets contribute and where their bytes are.
 *
 * <p>Segments hold the passes coded between two terminations of the arithmetic coder (one
 * segment unless a code-block style terminates passes early). A high-throughput block has one segment per packet
 * contribution and segment boundary of ISO/IEC 15444-15 B.2, so one of its HT segments may span several.
 *
 * @implNote This implementation follows OpenJPEG's {@code opj_tcd_cblk_dec_t} and
 * {@code opj_tcd_seg_t}, with the segment fields held in parallel arrays.
 */
final class CodeBlock {
    /**
     * The {@link #coder} of a block coded with the block coder of ISO/IEC 15444-1.
     */
    static final int LEGACY = 0;

    /**
     * The {@link #coder} of a high-throughput block whose first HT cleanup pass has not been read yet.
     */
    static final int HT_PLACEHOLDER = 1;

    /**
     * The {@link #coder} of a high-throughput block whose first HT cleanup pass has been read.
     */
    static final int HT = 2;

    /**
     * The left edge in sub-band coordinates.
     */
    final long x0;

    /**
     * The top edge in sub-band coordinates.
     */
    final long y0;

    /**
     * The right edge in sub-band coordinates (exclusive).
     */
    final long x1;

    /**
     * The bottom edge in sub-band coordinates (exclusive).
     */
    final long y1;

    /**
     * The number of magnitude bit-planes coded, once the block is first included.
     */
    int bitPlanes;

    /**
     * The number of bits of the next length indicator, before the pass count adjustment.
     */
    int lengthBits;

    /**
     * The number of segments started, zero until the block is first included.
     */
    int segments;

    /**
     * The number of segments holding data.
     */
    int dataSegments;

    /**
     * The number of passes the current packet contributes.
     */
    int newPasses;

    /**
     * Whether a packet of this block could not be read completely; its data is then ignored.
     */
    boolean corrupted;

    /**
     * The block coder: {@link #LEGACY}, {@link #HT_PLACEHOLDER} or {@link #HT}.
     */
    int coder;

    /**
     * The number of coding passes the packet headers read so far contribute, placeholder passes included.
     */
    int passes;

    /**
     * The index of the segment receiving the current packet's first new passes.
     */
    int firstNewSegment;

    /**
     * The number of segments the current packet contributes to.
     */
    int newSegments;

    /**
     * The index of the first coding pass of each segment; kept for high-throughput blocks only.
     */
    int[] segmentStart = new int[1];

    /**
     * The byte length of each segment.
     */
    int[] segmentLengths = new int[1];

    /**
     * The number of passes in each segment.
     */
    int[] segmentPasses = new int[1];

    /**
     * The number of passes of each segment whose bytes were read, which excludes the passes of
     * skipped packets.
     */
    int[] segmentDataPasses = new int[1];

    /**
     * The largest number of passes each segment may hold.
     */
    int[] segmentMaxPasses = new int[1];

    /**
     * The number of passes of each segment the current packet contributes.
     */
    int[] segmentNewPasses = new int[1];

    /**
     * The byte length of each segment's contribution in the current packet.
     */
    int[] segmentNewLengths = new int[1];

    /**
     * The start and length of every chunk of codeword bytes, two entries per chunk, as offsets
     * into the tile body.
     */
    int[] chunks = new int[2];

    /**
     * The number of entries used in {@link #chunks}.
     */
    int chunkEntries;

    /**
     * Constructs a code-block.
     *
     * @param x0 the left edge
     * @param y0 the top edge
     * @param x1 the right edge
     * @param y1 the bottom edge
     */
    CodeBlock(long x0, long y0, long x1, long y1) {
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    /**
     * Returns the width.
     *
     * @return the width in samples
     */
    int width() {
        return (int) (x1 - x0);
    }

    /**
     * Returns the height.
     *
     * @return the height in samples
     */
    int height() {
        return (int) (y1 - y0);
    }

    /**
     * Starts a segment, setting how many passes it can hold.
     *
     * @implNote This implementation follows OpenJPEG's {@code opj_t2_init_seg}: a block coded
     * with {@link ComponentCoding#TERMINATE_ALL} has one pass per segment; with
     * {@link ComponentCoding#BYPASS} the first segment holds the first ten passes and later
     * segments alternate between two raw passes and one arithmetic-coded pass; otherwise a
     * segment holds up to 109 passes, (37 - 1) * 3 + 1.
     * @param index the segment index
     * @param style the code-block style flags
     * @param first whether this is the block's first segment
     */
    void startSegment(int index, int style, boolean first) {
        if (index >= segmentLengths.length) {
            var capacity = Math.max(index + 1, segmentLengths.length * 2);
            segmentLengths = Arrays.copyOf(segmentLengths, capacity);
            segmentPasses = Arrays.copyOf(segmentPasses, capacity);
            segmentDataPasses = Arrays.copyOf(segmentDataPasses, capacity);
            segmentMaxPasses = Arrays.copyOf(segmentMaxPasses, capacity);
            segmentNewPasses = Arrays.copyOf(segmentNewPasses, capacity);
            segmentNewLengths = Arrays.copyOf(segmentNewLengths, capacity);
            segmentStart = Arrays.copyOf(segmentStart, capacity);
        }
        segmentLengths[index] = 0;
        segmentPasses[index] = 0;
        segmentDataPasses[index] = 0;
        segmentNewPasses[index] = 0;
        segmentNewLengths[index] = 0;
        int max;
        if ((style & ComponentCoding.TERMINATE_ALL) != 0) {
            max = 1;
        } else if ((style & ComponentCoding.BYPASS) != 0) {
            if (first) {
                max = 10;
            } else {
                var previous = segmentMaxPasses[index - 1];
                max = previous == 1 || previous == 10 ? 2 : 1;
            }
        } else {
            max = 109;
        }
        segmentMaxPasses[index] = max;
    }

    /**
     * Starts a codeword segment of a high-throughput block, which holds the passes of one packet that end at or
     * before a segment boundary of ISO/IEC 15444-15 B.2.
     *
     * @param index the segment index
     * @param start the index of the segment's first coding pass
     */
    void startHtSegment(int index, int start) {
        startSegment(index, 0, index == 0);
        segmentMaxPasses[index] = Integer.MAX_VALUE;
        segmentStart[index] = start;
    }

    /**
     * Records a chunk of codeword bytes.
     *
     * @param start  the offset of the chunk in the tile body
     * @param length the chunk length
     */
    void addChunk(int start, int length) {
        if (chunkEntries == chunks.length) {
            chunks = Arrays.copyOf(chunks, chunks.length * 2);
        }
        chunks[chunkEntries++] = start;
        chunks[chunkEntries++] = length;
    }
}
