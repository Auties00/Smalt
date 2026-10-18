package com.github.auties00.smalt.audio.mp3;

import java.util.Arrays;

/**
 * Holds the coded form of one granule of one channel: the side information fields, the
 * scalefactors and the quantized spectrum in coding order.
 *
 * <p>For long, start and stop blocks the spectrum is in frequency order; for short blocks it is
 * in the coding order of ISO/IEC 11172-3 2.4.3.4.6: scalefactor band by band, window by window
 * within a band, frequency within a window. Quantized values are signed.
 */
final class Mp3CodedGranule {
    /**
     * The normal long block.
     */
    static final int LONG = 0;

    /**
     * The start block, a long block that leads into short blocks.
     */
    static final int START = 1;

    /**
     * The three short blocks.
     */
    static final int SHORT = 2;

    /**
     * The stop block, a long block that follows short blocks.
     */
    static final int STOP = 3;

    /**
     * The quantized spectrum, signed, in coding order.
     */
    final int[] values = new int[576];

    /**
     * The long-block scalefactors of bands 0 to 20, or the short-block scalefactors indexed
     * {@code band * 3 + window} for bands 0 to 11; preemphasis excluded.
     */
    final int[] scalefactors = new int[39];

    /**
     * The block type: {@link #LONG}, {@link #START}, {@link #SHORT} or {@link #STOP}.
     */
    int blockType;

    /**
     * The global gain, from 0 to 255.
     */
    int globalGain;

    /**
     * The gains of the three short windows, from 0 to 7.
     */
    final int[] subblockGain = new int[3];

    /**
     * Whether scalefactor steps are two quarter-powers of two (0) or four (1).
     */
    int scalefacScale;

    /**
     * Whether the preemphasis of {@link Mp3EncoderTables#PRETAB} is added to the long-block
     * scalefactors.
     */
    boolean preflag;

    /**
     * The coded {@code scalefac_compress} value: 4 bits for MPEG-1, 9 bits for MPEG-2.
     */
    int scalefacCompress;

    /**
     * The number of scalefactor bits ({@code part2_length}).
     */
    int part2Length;

    /**
     * The number of Huffman bits ({@code part3_length}).
     */
    int part3Length;

    /**
     * The number of value pairs in the big-value region.
     */
    int bigValues;

    /**
     * The number of quadruples in the count1 region.
     */
    int count1;

    /**
     * The count1 table: {@code 0} for table A, {@code 1} for table B.
     */
    int count1Table;

    /**
     * The big-value tables of regions 0, 1 and 2.
     */
    final int[] tableSelect = new int[3];

    /**
     * The number of scalefactor bands in region 0 minus one ({@code region0_count}).
     */
    int region0Count;

    /**
     * The number of scalefactor bands in region 1 minus one ({@code region1_count}).
     */
    int region1Count;

    /**
     * The MPEG-1 scalefactor selection information of the four band groups; set only for the
     * second granule.
     */
    final boolean[] scfsi = new boolean[4];

    /**
     * Resets every field to a silent long granule.
     */
    void clear() {
        Arrays.fill(values, 0);
        Arrays.fill(scalefactors, 0);
        Arrays.fill(subblockGain, 0);
        Arrays.fill(tableSelect, 0);
        Arrays.fill(scfsi, false);
        blockType = LONG;
        globalGain = 0;
        scalefacScale = 0;
        preflag = false;
        scalefacCompress = 0;
        part2Length = 0;
        part3Length = 0;
        bigValues = 0;
        count1 = 0;
        count1Table = 0;
        region0Count = 0;
        region1Count = 0;
    }

    /**
     * Copies another granule's coding into this one.
     *
     * @param other the source
     */
    void copyFrom(Mp3CodedGranule other) {
        System.arraycopy(other.values, 0, values, 0, 576);
        System.arraycopy(other.scalefactors, 0, scalefactors, 0, scalefactors.length);
        System.arraycopy(other.subblockGain, 0, subblockGain, 0, 3);
        System.arraycopy(other.tableSelect, 0, tableSelect, 0, 3);
        System.arraycopy(other.scfsi, 0, scfsi, 0, 4);
        blockType = other.blockType;
        globalGain = other.globalGain;
        scalefacScale = other.scalefacScale;
        preflag = other.preflag;
        scalefacCompress = other.scalefacCompress;
        part2Length = other.part2Length;
        part3Length = other.part3Length;
        bigValues = other.bigValues;
        count1 = other.count1;
        count1Table = other.count1Table;
        region0Count = other.region0Count;
        region1Count = other.region1Count;
    }

    /**
     * Returns the total number of main data bits ({@code part2_3_length}).
     *
     * @return the bits
     */
    int part23Length() {
        return part2Length + part3Length;
    }
}
