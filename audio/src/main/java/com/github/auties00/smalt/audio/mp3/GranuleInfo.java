package com.github.auties00.smalt.audio.mp3;

/**
 * Holds the Layer III side information of one granule of one channel.
 *
 * <p>Fields mirror the {@code gr_info} syntax of ISO/IEC 11172-3 2.4.1.7; the scalefactor band
 * table and band counts are derived from the block type while the side information is read.
 */
final class GranuleInfo {
    /**
     * The scalefactor band widths in effect, zero-terminated.
     */
    int[] sfb;

    /**
     * The number of main-data bits of the granule ({@code part2_3_length}).
     */
    int part23Length;

    /**
     * The number of big-value pairs.
     */
    int bigValues;

    /**
     * The scalefactor length selector.
     */
    int scalefacCompress;

    /**
     * The quantizer step size.
     */
    int globalGain;

    /**
     * The block type: 0 normal, 1 start, 2 short, 3 stop.
     */
    int blockType;

    /**
     * Whether the lowest subbands use long blocks inside a short-block granule.
     */
    boolean mixedBlock;

    /**
     * The number of long scalefactor bands.
     */
    int longBands;

    /**
     * The number of short scalefactor bands, three per short band.
     */
    int shortBands;

    /**
     * The Huffman table of each big-value region.
     */
    final int[] tableSelect = new int[3];

    /**
     * The number of scalefactor bands of each big-value region, minus one.
     */
    final int[] regionCount = new int[3];

    /**
     * The gain offset of each short window.
     */
    final int[] subblockGain = new int[3];

    /**
     * Whether pre-emphasis applies.
     */
    boolean preflag;

    /**
     * The scalefactor step selector.
     */
    int scalefacScale;

    /**
     * The count1 Huffman table selector.
     */
    int count1Table;

    /**
     * The scalefactor reuse flags of the four band groups, MSB first.
     */
    int scfsi;
}
