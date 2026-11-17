package com.github.auties00.smalt.video.vc1.internal;

/**
 * Holds the decoders of the VC-1 variable length code tables: the small tables of SMPTE ST 421 sections 7 to 9 and the
 * tables of section 11.
 *
 * <p>The small tables are written as code strings indexed by symbol; their symbols are documented with each field. This
 * class is thread-safe.
 */
final class Vc1Codes {
    /**
     * Prevents instantiation.
     */
    private Vc1Codes() {
    }

    /**
     * Builds a decoder from code strings indexed by symbol.
     *
     * @param codes the binary code of each symbol, or {@code null} for a symbol without code
     * @return the decoder
     */
    static Vlc of(String... codes) {
        var packed = new int[codes.length];
        for (var i = 0; i < codes.length; i++) {
            if (codes[i] != null) {
                packed[i] = (codes[i].length() << 27) | Integer.parseInt(codes[i], 2);
            }
        }
        return new Vlc(packed);
    }

    /**
     * The advanced profile picture type (Table 35): 0 I, 1 P, 2 B, 3 BI, 4 skipped.
     */
    static final Vlc ADVANCED_PICTURE_TYPE = of("110", "0", "10", "1110", "1111");

    /**
     * The frame coding mode (Table 41): 0 progressive, 1 frame interlace, 2 field interlace.
     */
    static final Vlc FRAME_CODING_MODE = of("0", "10", "11");

    /**
     * The transform AC coding set index (Table 39).
     */
    static final Vlc TRANSFORM_AC_INDEX = of("0", "10", "11");

    /**
     * The motion vector range (Table 37): 0 default, then the three extended ranges.
     */
    static final Vlc MV_RANGE = of("0", "10", "110", "111");

    /**
     * The BFRACTION code (Table 40): symbols 0 to 6 are the short codes 000b to 110b, symbols 7 to 22 the long codes
     * 1110000b to 1111111b; 21 is reserved and 22 signals a BI picture in the main profile.
     */
    static final Vlc B_FRACTION = of("000", "001", "010", "011", "100", "101", "110",
            "1110000", "1110001", "1110010", "1110011", "1110100", "1110101", "1110110", "1110111",
            "1111000", "1111001", "1111010", "1111011", "1111100", "1111101", "1111110", "1111111");

    /**
     * 1-MV half sample with bilinear interpolation; the motion vector modes are the symbols of the mode tables.
     */
    static final int MV_1_HALF_BILINEAR = 0;

    /**
     * 1-MV with quarter sample bicubic interpolation.
     */
    static final int MV_1 = 1;

    /**
     * 1-MV half sample with bicubic interpolation.
     */
    static final int MV_1_HALF = 2;

    /**
     * Mixed-MV: 1-MV and 4-MV macroblocks.
     */
    static final int MV_MIXED = 3;

    /**
     * Intensity compensation, followed by MVMODE2.
     */
    static final int MV_INTENSITY = 4;

    /**
     * The P picture MVMODE for PQUANT above 12 (Table 46).
     */
    static final Vlc MV_MODE_LOW_RATE = of("1", "01", "001", "0000", "0001");

    /**
     * The P picture MVMODE for PQUANT up to 12 (Table 47).
     */
    static final Vlc MV_MODE_HIGH_RATE = of("0000", "1", "001", "01", "0001");

    /**
     * The P picture MVMODE2 for PQUANT above 12 (Table 49).
     */
    static final Vlc MV_MODE2_LOW_RATE = of("1", "01", "001", "000");

    /**
     * The P picture MVMODE2 for PQUANT up to 12 (Table 50).
     */
    static final Vlc MV_MODE2_HIGH_RATE = of("000", "1", "001", "01");

    /**
     * The 8x8 transform; the transform types are the values of TTFRM.
     */
    static final int TT_8X8 = 0;

    /**
     * The 8x4 transform: two 8-wide, 4-tall subblocks.
     */
    static final int TT_8X4 = 1;

    /**
     * The 4x8 transform: two 4-wide, 8-tall subblocks.
     */
    static final int TT_4X8 = 2;

    /**
     * The 4x4 transform: four subblocks.
     */
    static final int TT_4X4 = 3;

    /**
     * Subblock patterns of 8x4 and 4x8 transforms: bit 1 the top or left subblock, bit 0 the bottom or right one.
     * A TTMB or TTBLK symbol packs the transform type in bits 0 and 1, the pattern in bits 2 and 3 (0 when the type
     * is 8x8 or 4x4) and, for TTMB, the macroblock signal level in bit 4.
     */
    static final int TT_MACROBLOCK = 16;

    /**
     * Packs a TTMB or TTBLK symbol.
     *
     * @param type the transform type
     * @param pattern the subblock pattern
     * @param macroblock whether the type applies to the whole macroblock
     * @return the symbol
     */
    private static int tt(int type, int pattern, boolean macroblock) {
        return type | (pattern << 2) | (macroblock ? TT_MACROBLOCK : 0);
    }

    /**
     * Builds a decoder from code strings and the symbol of each.
     *
     * @param codes the binary codes
     * @param symbols the symbol of each code
     * @return the decoder
     */
    private static Vlc symbols(String[] codes, int[] symbols) {
        var max = 0;
        for (var symbol : symbols) {
            max = Math.max(max, symbol);
        }
        var byIndex = new String[max + 1];
        for (var i = 0; i < codes.length; i++) {
            byIndex[symbols[i]] = codes[i];
        }
        return of(byIndex);
    }

    /**
     * The symbols of the TTMB codes, in the order of the tables.
     */
    private static final int[] TTMB_SYMBOLS = {
            tt(TT_8X8, 0, false), tt(TT_8X4, 1, false), tt(TT_8X4, 2, false), tt(TT_8X4, 3, false),
            tt(TT_4X8, 1, false), tt(TT_4X8, 2, false), tt(TT_4X8, 3, false), tt(TT_4X4, 0, false),
            tt(TT_8X8, 0, true), tt(TT_8X4, 1, true), tt(TT_8X4, 2, true), tt(TT_8X4, 3, true),
            tt(TT_4X8, 1, true), tt(TT_4X8, 2, true), tt(TT_4X8, 3, true), tt(TT_4X4, 0, true)
    };

    /**
     * The TTMB code for PQUANT below 5 (Table 54).
     */
    static final Vlc TTMB_HIGH_RATE = symbols(new String[]{"11", "101110", "1011111", "00", "10110", "10101", "01",
            "100", "10100", "1011110001", "101111001", "101111011", "101111000000", "101111000001", "10111100001",
            "101111010"}, TTMB_SYMBOLS);

    /**
     * The TTMB code for PQUANT from 5 to 12 (Table 55).
     */
    static final Vlc TTMB_MEDIUM_RATE = symbols(new String[]{"110", "0110", "0011", "0111", "1111", "1110", "000",
            "010", "10", "0010100", "0010001", "001011", "001001", "00100001", "0010101", "00100000"}, TTMB_SYMBOLS);

    /**
     * The TTMB code for PQUANT from 13 (Table 56).
     */
    static final Vlc TTMB_LOW_RATE = symbols(new String[]{"110", "000", "1110", "00101", "010", "011", "0011", "1111",
            "10", "0010000001", "00100001", "001001", "00100000001", "001000001", "0010001", "00100000000"},
            TTMB_SYMBOLS);

    /**
     * The symbols of the TTBLK codes, in the order of the tables.
     */
    private static final int[] TTBLK_SYMBOLS = {
            tt(TT_8X8, 0, false), tt(TT_8X4, 1, false), tt(TT_8X4, 2, false), tt(TT_8X4, 3, false),
            tt(TT_4X8, 1, false), tt(TT_4X8, 2, false), tt(TT_4X8, 3, false), tt(TT_4X4, 0, false)
    };

    /**
     * The TTBLK code for PQUANT below 5 (Table 62).
     */
    static final Vlc TTBLK_HIGH_RATE = symbols(new String[]{"11", "10001", "10000", "00", "10010", "10011", "01",
            "101"}, TTBLK_SYMBOLS);

    /**
     * The TTBLK code for PQUANT from 5 to 12 (Table 63).
     */
    static final Vlc TTBLK_MEDIUM_RATE = symbols(new String[]{"11", "1000", "1001", "011", "000", "001", "101", "010"},
            TTBLK_SYMBOLS);

    /**
     * The TTBLK code for PQUANT from 13 (Table 64).
     */
    static final Vlc TTBLK_LOW_RATE = symbols(new String[]{"01", "100", "1011", "1010", "110", "111", "000", "001"},
            TTBLK_SYMBOLS);

    /**
     * The 4x4 subblock pattern for PQUANT below 5 (Table 65), indexed by the pattern (8 SB0 + 4 SB1 + 2 SB2 + SB3).
     */
    static final Vlc SUBBLOCK_PATTERN_HIGH_RATE = of(null, "01110", "01100", "00111", "01011", "01001", "011010",
            "0010", "01010", "011011", "01000", "0000", "00110", "0001", "01111", "1");

    /**
     * The 4x4 subblock pattern for PQUANT from 5 to 12 (Table 66).
     */
    static final Vlc SUBBLOCK_PATTERN_MEDIUM_RATE = of(null, "1110", "000", "1000", "1111", "1010", "00100", "10111",
            "1101", "00101", "1001", "11001", "0011", "11000", "10110", "01");

    /**
     * The 4x4 subblock pattern for PQUANT from 13 (Table 67).
     */
    static final Vlc SUBBLOCK_PATTERN_LOW_RATE = of(null, "101", "110", "0010", "010", "1000", "00000", "11100",
            "011", "00001", "0011", "11101", "0001", "10011", "10010", "1111");

    /**
     * The 8x4 and 4x8 subblock pattern of progressive pictures (Table 68), in the bit layout of the TT symbols.
     */
    static final Vlc HALF_SUBBLOCK_PATTERN = of(null, "10", "11", "0");

    /**
     * The bitplane coding mode (Table 69): 0 raw, 1 Norm-2, 2 Diff-2, 3 Norm-6, 4 Diff-6, 5 row-skip, 6 column-skip.
     */
    static final Vlc BITPLANE_MODE = of("0000", "10", "001", "11", "0001", "010", "011");

    /**
     * The Norm-2 and Diff-2 symbol pair code (Table 80), indexed by the first symbol plus twice the second.
     */
    static final Vlc NORM2 = of("0", "100", "101", "11");

    /**
     * The 3x2 and 2x3 tile code (Table 81), the escape and the code that follows it joined into one code.
     */
    static final Vlc NORM6 = norm6();

    /**
     * Builds the Norm-6 decoder of Table 81.
     *
     * @return the decoder
     */
    private static Vlc norm6() {
        int[][] rows = {
                {1, 1}, {2, 4}, {3, 4}, {0, 8}, {4, 4}, {1, 8}, {2, 8}, {2, 5, 7, 5}, {5, 4}, {3, 8}, {4, 8},
                {2, 5, 11, 5}, {5, 8}, {2, 5, 13, 5}, {2, 5, 14, 5}, {3, 5, 14, 8}, {6, 4}, {6, 8}, {7, 8},
                {2, 5, 19, 5}, {8, 8}, {2, 5, 21, 5}, {2, 5, 22, 5}, {3, 5, 13, 8}, {9, 8}, {2, 5, 25, 5},
                {2, 5, 26, 5}, {3, 5, 12, 8}, {2, 5, 28, 5}, {3, 5, 11, 8}, {3, 5, 10, 8}, {3, 5, 7, 4}, {7, 4},
                {10, 8}, {11, 8}, {2, 5, 3, 5}, {12, 8}, {2, 5, 5, 5}, {2, 5, 6, 5}, {3, 5, 9, 8}, {13, 8},
                {2, 5, 9, 5}, {2, 5, 10, 5}, {3, 5, 8, 8}, {2, 5, 12, 5}, {3, 5, 7, 8}, {3, 5, 6, 8}, {3, 5, 6, 4},
                {14, 8}, {2, 5, 17, 5}, {2, 5, 18, 5}, {3, 5, 5, 8}, {2, 5, 20, 5}, {3, 5, 4, 8}, {3, 5, 3, 8},
                {3, 5, 5, 4}, {2, 5, 24, 5}, {3, 5, 2, 8}, {3, 5, 1, 8}, {3, 5, 4, 4}, {3, 5, 0, 8}, {3, 5, 3, 4},
                {3, 5, 2, 4}, {3, 5, 1, 1}
        };
        var packed = new int[64];
        for (var k = 0; k < 64; k++) {
            var row = rows[k];
            var code = row[0];
            var length = row[1];
            if (row.length == 4) {
                code = (code << row[3]) | row[2];
                length += row[3];
            }
            packed[k] = (length << 27) | code;
        }
        return new Vlc(packed);
    }

    /**
     * The AC escape mode (Table 58): 0 mode 1, 1 mode 2, 2 mode 3.
     */
    static final Vlc ESCAPE_MODE = of("1", "01", "00");

    /**
     * The conservative mode 3 level size (Table 59), indexed by the size.
     */
    static final Vlc ESCAPE_LEVEL_SIZE_CONSERVATIVE = of(null, "001", "010", "011", "100", "101", "110", "111",
            "00000", "00001", "00010", "00011");

    /**
     * The efficient mode 3 level size (Table 60), indexed by the size.
     */
    static final Vlc ESCAPE_LEVEL_SIZE_EFFICIENT = of(null, null, "1", "01", "001", "0001", "00001", "000001",
            "000000");

    /**
     * The progressive B macroblock prediction type (Table 57): 0 for 0b, 1 for 10b, 2 for 11b.
     */
    static final Vlc B_MV_TYPE = of("0", "10", "11");

    /**
     * The extended differential motion vector range (Table 97): 0 none, 1 horizontal, 2 vertical, 3 both.
     */
    static final Vlc DMV_RANGE = of("0", "10", "110", "111");

    /**
     * The fields intensity compensation applies to (Table 109): 0 both, 1 top, 2 bottom.
     */
    static final Vlc INTENSITY_FIELDS = of("1", "00", "01");

    /**
     * The B field picture MVMODE for PQUANT above 12 (Table 107).
     */
    static final Vlc B_FIELD_MV_MODE_LOW_RATE = MV_MODE2_LOW_RATE;

    /**
     * The B field picture MVMODE for PQUANT up to 12 (Table 108).
     */
    static final Vlc B_FIELD_MV_MODE_HIGH_RATE = MV_MODE2_HIGH_RATE;

    /**
     * The B field macroblock prediction type when not forward (Table 110): 0 backward, 1 direct, 2 interpolated.
     */
    static final Vlc B_FIELD_MV_TYPE = of("0", "10", "11");

    /**
     * The interlace field mixed-MV macroblock modes, indexed by MBMODETAB.
     */
    static final Vlc[] FIELD_MIXED_MV_MBMODE = {
            new Vlc(Vc1BlockTables.FIELD_MIXED_MV_MBMODE_0), new Vlc(Vc1BlockTables.FIELD_MIXED_MV_MBMODE_1),
            new Vlc(Vc1BlockTables.FIELD_MIXED_MV_MBMODE_2), new Vlc(Vc1BlockTables.FIELD_MIXED_MV_MBMODE_3),
            new Vlc(Vc1BlockTables.FIELD_MIXED_MV_MBMODE_4), new Vlc(Vc1BlockTables.FIELD_MIXED_MV_MBMODE_5),
            new Vlc(Vc1BlockTables.FIELD_MIXED_MV_MBMODE_6), new Vlc(Vc1BlockTables.FIELD_MIXED_MV_MBMODE_7)
    };

    /**
     * The interlace field 1-MV macroblock modes, indexed by MBMODETAB.
     */
    static final Vlc[] FIELD_ONE_MV_MBMODE = {
            new Vlc(Vc1BlockTables.FIELD_ONE_MV_MBMODE_0), new Vlc(Vc1BlockTables.FIELD_ONE_MV_MBMODE_1),
            new Vlc(Vc1BlockTables.FIELD_ONE_MV_MBMODE_2), new Vlc(Vc1BlockTables.FIELD_ONE_MV_MBMODE_3),
            new Vlc(Vc1BlockTables.FIELD_ONE_MV_MBMODE_4), new Vlc(Vc1BlockTables.FIELD_ONE_MV_MBMODE_5),
            new Vlc(Vc1BlockTables.FIELD_ONE_MV_MBMODE_6), new Vlc(Vc1BlockTables.FIELD_ONE_MV_MBMODE_7)
    };

    /**
     * The interlace frame 4-MV macroblock modes, indexed by MBMODETAB.
     */
    static final Vlc[] FRAME_FOUR_MV_MBMODE = {
            new Vlc(Vc1BlockTables.FRAME_FOUR_MV_MBMODE_0), new Vlc(Vc1BlockTables.FRAME_FOUR_MV_MBMODE_1),
            new Vlc(Vc1BlockTables.FRAME_FOUR_MV_MBMODE_2), new Vlc(Vc1BlockTables.FRAME_FOUR_MV_MBMODE_3)
    };

    /**
     * The interlace frame non 4-MV macroblock modes, indexed by MBMODETAB.
     */
    static final Vlc[] FRAME_MBMODE = {
            new Vlc(Vc1BlockTables.FRAME_MBMODE_0), new Vlc(Vc1BlockTables.FRAME_MBMODE_1),
            new Vlc(Vc1BlockTables.FRAME_MBMODE_2), new Vlc(Vc1BlockTables.FRAME_MBMODE_3)
    };

    /**
     * The 1-field reference interlace motion vectors, indexed by IMVTAB.
     */
    static final Vlc[] ONE_REF_MV = {
            new Vlc(Vc1MvTables.ONE_REF_MV_0), new Vlc(Vc1MvTables.ONE_REF_MV_1),
            new Vlc(Vc1MvTables.ONE_REF_MV_2), new Vlc(Vc1MvTables.ONE_REF_MV_3)
    };

    /**
     * The 2-field reference interlace motion vectors, indexed by IMVTAB.
     */
    static final Vlc[] TWO_REF_MV = {
            new Vlc(Vc1MvTables.TWO_REF_MV_0), new Vlc(Vc1MvTables.TWO_REF_MV_1),
            new Vlc(Vc1MvTables.TWO_REF_MV_2), new Vlc(Vc1MvTables.TWO_REF_MV_3),
            new Vlc(Vc1MvTables.TWO_REF_MV_4), new Vlc(Vc1MvTables.TWO_REF_MV_5),
            new Vlc(Vc1MvTables.TWO_REF_MV_6), new Vlc(Vc1MvTables.TWO_REF_MV_7)
    };

    /**
     * The interlaced coded block patterns, indexed by ICBPTAB; the symbol is the pattern minus 1.
     */
    static final Vlc[] INTERLACED_CBPCY = {
            new Vlc(Vc1BlockTables.INTERLACED_CBPCY_0), new Vlc(Vc1BlockTables.INTERLACED_CBPCY_1),
            new Vlc(Vc1BlockTables.INTERLACED_CBPCY_2), new Vlc(Vc1BlockTables.INTERLACED_CBPCY_3),
            new Vlc(Vc1BlockTables.INTERLACED_CBPCY_4), new Vlc(Vc1BlockTables.INTERLACED_CBPCY_5),
            new Vlc(Vc1BlockTables.INTERLACED_CBPCY_6), new Vlc(Vc1BlockTables.INTERLACED_CBPCY_7)
    };

    /**
     * The 4-MV block patterns, indexed by 4MVBPTAB.
     */
    static final Vlc[] FOUR_MV_BLOCK_PATTERN = {
            new Vlc(Vc1BlockTables.FOUR_MV_BLOCK_PATTERN_0), new Vlc(Vc1BlockTables.FOUR_MV_BLOCK_PATTERN_1),
            new Vlc(Vc1BlockTables.FOUR_MV_BLOCK_PATTERN_2), new Vlc(Vc1BlockTables.FOUR_MV_BLOCK_PATTERN_3)
    };

    /**
     * The interlace frame 2-MV block patterns, indexed by 2MVBPTAB.
     */
    static final Vlc[] TWO_MV_BLOCK_PATTERN = {
            new Vlc(Vc1BlockTables.TWO_MV_BLOCK_PATTERN_0), new Vlc(Vc1BlockTables.TWO_MV_BLOCK_PATTERN_1),
            new Vlc(Vc1BlockTables.TWO_MV_BLOCK_PATTERN_2), new Vlc(Vc1BlockTables.TWO_MV_BLOCK_PATTERN_3)
    };

    /**
     * The I picture coded block pattern.
     */
    static final Vlc I_CBPCY = new Vlc(Vc1BlockTables.I_CBPCY);

    /**
     * The P and B picture coded block patterns, indexed by CBPTAB.
     */
    static final Vlc[] P_CBPCY = {
            new Vlc(Vc1BlockTables.P_CBPCY_0), new Vlc(Vc1BlockTables.P_CBPCY_1),
            new Vlc(Vc1BlockTables.P_CBPCY_2), new Vlc(Vc1BlockTables.P_CBPCY_3)
    };

    /**
     * The progressive motion vector differentials, indexed by MVTAB.
     */
    static final Vlc[] MV_DIFF = {
            new Vlc(Vc1MvTables.MV_DIFF_0), new Vlc(Vc1MvTables.MV_DIFF_1),
            new Vlc(Vc1MvTables.MV_DIFF_2), new Vlc(Vc1MvTables.MV_DIFF_3)
    };

    /**
     * The DC differentials: low-motion luma, low-motion color-difference, high-motion luma, high-motion
     * color-difference. The escape is the symbol after the last value.
     */
    static final Vlc[] DC = {
            new Vlc(Vc1BlockTables.LOW_MOTION_LUMA_DC), new Vlc(Vc1BlockTables.LOW_MOTION_CHROMA_DC),
            new Vlc(Vc1BlockTables.HIGH_MOTION_LUMA_DC), new Vlc(Vc1BlockTables.HIGH_MOTION_CHROMA_DC)
    };

    /**
     * The symbol of the DC differential escape code.
     */
    static final int DC_ESCAPE = Vc1BlockTables.LOW_MOTION_LUMA_DC.length - 1;

    /**
     * The AC coding sets: high motion intra and inter, low motion intra and inter, mid rate intra and inter, high
     * rate intra and inter.
     */
    static final AcCodingSet HIGH_MOTION_INTRA = new AcCodingSet(Vc1AcTables.HIGH_MOTION_INTRA_CODES,
            Vc1AcTables.HIGH_MOTION_INTRA_RUN_LEVEL, Vc1AcTables.HIGH_MOTION_INTRA_START_OF_LAST,
            Vc1AcTables.HIGH_MOTION_INTRA_DELTA_LEVEL, Vc1AcTables.HIGH_MOTION_INTRA_DELTA_LEVEL_SPLIT,
            Vc1AcTables.HIGH_MOTION_INTRA_DELTA_RUN, Vc1AcTables.HIGH_MOTION_INTRA_DELTA_RUN_SPLIT);

    /**
     * The high motion inter coding set.
     */
    static final AcCodingSet HIGH_MOTION_INTER = new AcCodingSet(Vc1AcTables.HIGH_MOTION_INTER_CODES,
            Vc1AcTables.HIGH_MOTION_INTER_RUN_LEVEL, Vc1AcTables.HIGH_MOTION_INTER_START_OF_LAST,
            Vc1AcTables.HIGH_MOTION_INTER_DELTA_LEVEL, Vc1AcTables.HIGH_MOTION_INTER_DELTA_LEVEL_SPLIT,
            Vc1AcTables.HIGH_MOTION_INTER_DELTA_RUN, Vc1AcTables.HIGH_MOTION_INTER_DELTA_RUN_SPLIT);

    /**
     * The low motion intra coding set.
     */
    static final AcCodingSet LOW_MOTION_INTRA = new AcCodingSet(Vc1AcTables.LOW_MOTION_INTRA_CODES,
            Vc1AcTables.LOW_MOTION_INTRA_RUN_LEVEL, Vc1AcTables.LOW_MOTION_INTRA_START_OF_LAST,
            Vc1AcTables.LOW_MOTION_INTRA_DELTA_LEVEL, Vc1AcTables.LOW_MOTION_INTRA_DELTA_LEVEL_SPLIT,
            Vc1AcTables.LOW_MOTION_INTRA_DELTA_RUN, Vc1AcTables.LOW_MOTION_INTRA_DELTA_RUN_SPLIT);

    /**
     * The low motion inter coding set.
     */
    static final AcCodingSet LOW_MOTION_INTER = new AcCodingSet(Vc1AcTables.LOW_MOTION_INTER_CODES,
            Vc1AcTables.LOW_MOTION_INTER_RUN_LEVEL, Vc1AcTables.LOW_MOTION_INTER_START_OF_LAST,
            Vc1AcTables.LOW_MOTION_INTER_DELTA_LEVEL, Vc1AcTables.LOW_MOTION_INTER_DELTA_LEVEL_SPLIT,
            Vc1AcTables.LOW_MOTION_INTER_DELTA_RUN, Vc1AcTables.LOW_MOTION_INTER_DELTA_RUN_SPLIT);

    /**
     * The mid rate intra coding set.
     */
    static final AcCodingSet MID_RATE_INTRA = new AcCodingSet(Vc1AcTables.MID_RATE_INTRA_CODES,
            Vc1AcTables.MID_RATE_INTRA_RUN_LEVEL, Vc1AcTables.MID_RATE_INTRA_START_OF_LAST,
            Vc1AcTables.MID_RATE_INTRA_DELTA_LEVEL, Vc1AcTables.MID_RATE_INTRA_DELTA_LEVEL_SPLIT,
            Vc1AcTables.MID_RATE_INTRA_DELTA_RUN, Vc1AcTables.MID_RATE_INTRA_DELTA_RUN_SPLIT);

    /**
     * The mid rate inter coding set.
     */
    static final AcCodingSet MID_RATE_INTER = new AcCodingSet(Vc1AcTables.MID_RATE_INTER_CODES,
            Vc1AcTables.MID_RATE_INTER_RUN_LEVEL, Vc1AcTables.MID_RATE_INTER_START_OF_LAST,
            Vc1AcTables.MID_RATE_INTER_DELTA_LEVEL, Vc1AcTables.MID_RATE_INTER_DELTA_LEVEL_SPLIT,
            Vc1AcTables.MID_RATE_INTER_DELTA_RUN, Vc1AcTables.MID_RATE_INTER_DELTA_RUN_SPLIT);

    /**
     * The high rate intra coding set.
     */
    static final AcCodingSet HIGH_RATE_INTRA = new AcCodingSet(Vc1AcTables.HIGH_RATE_INTRA_CODES,
            Vc1AcTables.HIGH_RATE_INTRA_RUN_LEVEL, Vc1AcTables.HIGH_RATE_INTRA_START_OF_LAST,
            Vc1AcTables.HIGH_RATE_INTRA_DELTA_LEVEL, Vc1AcTables.HIGH_RATE_INTRA_DELTA_LEVEL_SPLIT,
            Vc1AcTables.HIGH_RATE_INTRA_DELTA_RUN, Vc1AcTables.HIGH_RATE_INTRA_DELTA_RUN_SPLIT);

    /**
     * The high rate inter coding set.
     */
    static final AcCodingSet HIGH_RATE_INTER = new AcCodingSet(Vc1AcTables.HIGH_RATE_INTER_CODES,
            Vc1AcTables.HIGH_RATE_INTER_RUN_LEVEL, Vc1AcTables.HIGH_RATE_INTER_START_OF_LAST,
            Vc1AcTables.HIGH_RATE_INTER_DELTA_LEVEL, Vc1AcTables.HIGH_RATE_INTER_DELTA_LEVEL_SPLIT,
            Vc1AcTables.HIGH_RATE_INTER_DELTA_RUN, Vc1AcTables.HIGH_RATE_INTER_DELTA_RUN_SPLIT);

    /**
     * Returns the intra coding set of an index (Tables 71, 72, 76 and 77).
     *
     * @param index the coding set index, from 0 to 2
     * @param pqindex the picture quantizer index
     * @return the coding set
     */
    static AcCodingSet intraSet(int index, int pqindex) {
        return switch (index) {
            case 0 -> pqindex <= 8 ? HIGH_RATE_INTRA : LOW_MOTION_INTRA;
            case 1 -> HIGH_MOTION_INTRA;
            default -> MID_RATE_INTRA;
        };
    }

    /**
     * Returns the inter coding set of an index (Tables 71, 72, 78 and 79).
     *
     * @param index the coding set index, from 0 to 2
     * @param pqindex the picture quantizer index
     * @return the coding set
     */
    static AcCodingSet interSet(int index, int pqindex) {
        return switch (index) {
            case 0 -> pqindex <= 8 ? HIGH_RATE_INTER : LOW_MOTION_INTER;
            case 1 -> HIGH_MOTION_INTER;
            default -> MID_RATE_INTER;
        };
    }
}
