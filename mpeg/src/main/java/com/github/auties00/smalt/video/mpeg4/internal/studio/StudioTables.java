/* Ports the studio DC size VLCs of the MoMuSys MPEG-4 Visual reference software of ISO/IEC 14496-5:2001/Amd.1:2002
 * (StudioProfile-MoMuSys, vm_common/include/studio_vlc_table.h). Copyright 1996-2002 the ACTS-MoMuSys partners and
 * Sony Corporation; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.studio;

import com.github.auties00.smalt.video.mpeg4.internal.Vlc;

/**
 * The variable length codes of the Studio profiles (ISO/IEC 14496-2, Studio Profile amendment): the DC size codes
 * and the twelve AC coefficient group codes of intra blocks.
 *
 * <p>An AC symbol is a coefficient group. Group 0 ends the block; groups 1 to 6 are a run of zeros followed by a
 * coefficient of magnitude 1 (the additional code holds the run and the sign); groups 8 to 13 are a run of zeros;
 * groups 17 to 24 are a level of 1 to 8 bits; group 25 is a fixed-length escape level. The context of the next
 * symbol is the group class of the previous one: context 0 for the first symbol of a block, 16 after a zero-one
 * group, 8 after a zero run, the group itself after a level group, 25 after an escape. Context 8 holds level and
 * escape groups only.
 *
 * <p>The AC codewords were derived from FFmpeg's decoding of crafted streams, run as a black-box oracle; the DC
 * codes are those of the reference software.
 */
final class StudioTables {
    /**
     * The contexts of the AC group VLCs, in the order of {@link #AC_CODES}.
     */
    static final int[] CONTEXTS = {0, 8, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25};

    /**
     * The codewords of the AC coefficient group VLCs, one row per context in {@link #CONTEXTS} order.
     */
    private static final String[][] AC_CODES = {
            // context 0
            {"0101", "0011", "00011", "000101", "0000100", "0000011", "00000101", "0100", "0000101", "000001001",
                    "00000100001", "000001000001", "0000010000001", "11", "101", "100", "011", "0010", "000100",
                    "000011", "0000010001", "0000010000000"},
            // context 8
            {"1", "01", "001", "0001", "00001", "0000011", "00000101", "00000100"},
            // context 16
            {"101", "100", "011", "0011", "0010", "000011", "0000010001", "00011", "00010", "0000011", "000001001",
                    "00000100000011", "00000100000010", "11", "010", "000010", "00000101", "00000100001",
                    "0000010000011", "00000100000001", "000001000000001", "0000010000010"},
            // context 17
            {"00101", "011", "0100", "0011", "00011", "00000101", "0000010000001", "0101", "00100", "000011",
                    "000001001", "0000010000011", "0000010000010", "11", "10", "00010", "000010", "0000011",
                    "0000010001", "000001000011", "0000010000000", "000001000010"},
            // context 18
            {"0000101", "011", "0010", "000101", "0000011", "0000010001", "000001000000001", "0011", "00011",
                    "0000100", "000001001", "0000010000011", "00000100000001", "11", "10", "010", "000100", "000011",
                    "00000101", "00000100001", "0000010000010", "0000010000001"},
            // context 19
            {"0000010011", "00010", "0000100", "00000101", "00000100011", "0000010000001", "00000100000001", "00011",
                    "0000101", "0000010010", "000001000011", "0000010000011", "0000010000010", "11", "10", "01", "001",
                    "000011", "0000011", "00000100010", "000001000000001", "000001000010"},
            // context 20
            {"00000100011", "000011", "00000110", "0000010010", "000001000011", "00000100000001", "000001000000001",
                    "0001", "00000111", "0000010011", "00000100010", "00000100000011", "00000100000010", "011", "010",
                    "11", "10", "001", "000010", "00000101", "000001000010", "000001000001"},
            // context 21
            {"000001011", "0000101", "00000110", "000001010", "0000010011", "00000100010", "000001000000", "00011",
                    "00000111", "0000111", "0000110", "00000100011", "000001000001", "0011", "0010", "11", "10", "01",
                    "00010", "0000100", "0000010010", "00000100001"},
            // context 22
            {"0000010101", "00000110", "000001011", "0000010011", "0000010010", "000001000010", "0000010000000",
                    "000011", "0000010100", "00000100011", "00000111", "000001000011", "0000010000001", "0001", "011",
                    "010", "11", "10", "001", "000010", "00000100010", "000001000001"},
            // context 23
            {"000001000011", "0000101", "0000100", "00000110", "0000010010", "000001000001", "000001000000", "000101",
                    "00000111", "000100", "000011", "0000010011", "000001000010", "00011", "0011", "011", "010", "11",
                    "10", "0010", "00000101", "0000010001"},
            // context 24
            {"0000010000011", "0000100", "00000110", "000001011", "000001010", "0000010001", "0000010000000",
                    "0000101", "00000111", "0011", "00000100001", "0000010000010", "0000010000001", "00011", "00010",
                    "0010", "011", "010", "11", "10", "000011", "000001001"},
            // context 25
            {"0000010011", "00010", "000010", "00000111", "000001011", "0000010010", "0000010001", "00011", "000011",
                    "000001101", "000001100", "00000100001", "00000100000", "101", "100", "0101", "0100", "0011",
                    "0010", "011", "11", "000001010"},
    };
    /**
     * The coefficient groups the codewords of {@link #AC_CODES} stand for.
     */
    private static final int[][] AC_SYMBOLS = {
            {0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 17, 18, 19, 20, 21, 22, 23, 24, 25},
            {18, 19, 20, 21, 22, 23, 24, 25},
            {0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 17, 18, 19, 20, 21, 22, 23, 24, 25},
            {0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 17, 18, 19, 20, 21, 22, 23, 24, 25},
            {0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 17, 18, 19, 20, 21, 22, 23, 24, 25},
            {0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 17, 18, 19, 20, 21, 22, 23, 24, 25},
            {0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 17, 18, 19, 20, 21, 22, 23, 24, 25},
            {0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 17, 18, 19, 20, 21, 22, 23, 24, 25},
            {0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 17, 18, 19, 20, 21, 22, 23, 24, 25},
            {0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 17, 18, 19, 20, 21, 22, 23, 24, 25},
            {0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 17, 18, 19, 20, 21, 22, 23, 24, 25},
            {0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 17, 18, 19, 20, 21, 22, 23, 24, 25},
    };
    /**
     * The luminance DC size VLC, indexed by size.
     */
    private static final String[] DC_LUMA = {"001110", "00110", "0000", "0010", "111", "101", "011", "010", "100",
            "110", "0001", "0011110", "00111110", "001111110", "0011111110", "00111111110", "001111111110",
            "0011111111110", "0011111111111"};
    /**
     * The chrominance DC size VLC, indexed by size.
     */
    private static final String[] DC_CHROMA = {"0000", "0010", "111", "101", "011", "010", "100", "110", "0001",
            "00110", "001110", "0011110", "00111110", "001111110", "0011111110", "00111111110", "001111111110",
            "0011111111110", "0011111111111"};

    /**
     * The luminance DC size VLC, whose symbols are the sizes.
     */
    static final Vlc DC_LUMA_VLC = sized(DC_LUMA);

    /**
     * The chrominance DC size VLC, whose symbols are the sizes.
     */
    static final Vlc DC_CHROMA_VLC = sized(DC_CHROMA);

    /**
     * The AC group VLC of every context, indexed by context (entries for contexts without a table are {@code null}).
     */
    static final Vlc[] AC_VLC = acVlcs();

    private StudioTables() {
    }

    /**
     * Builds a VLC whose symbol is the index of the codeword.
     *
     * @param codes the codewords
     * @return the VLC
     */
    private static Vlc sized(String[] codes) {
        var symbols = new int[codes.length];
        for (var i = 0; i < symbols.length; i++) {
            symbols[i] = i;
        }
        return new Vlc(8, codes, symbols);
    }

    /**
     * Builds the AC group VLCs.
     *
     * @return the VLCs indexed by context
     */
    private static Vlc[] acVlcs() {
        var vlcs = new Vlc[26];
        for (var i = 0; i < CONTEXTS.length; i++) {
            vlcs[CONTEXTS[i]] = new Vlc(8, AC_CODES[i], AC_SYMBOLS[i]);
        }
        return vlcs;
    }
}
