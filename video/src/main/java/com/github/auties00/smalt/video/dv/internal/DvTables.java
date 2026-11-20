package com.github.auties00.smalt.video.dv.internal;

/**
 * Holds the constant tables of DV video coding: the variable length code of SMPTE ST 314 table 25 (equal to table
 * 28 of SMPTE ST 370), the coefficient orders, the area numbers, the quantization steps and the DCT weights.
 *
 * <p>Coefficient positions are raster indices {@code v * 8 + h}, {@code v} the vertical and {@code h} the
 * horizontal frequency. This class is thread-safe.
 */
public final class DvTables {
    /**
     * The code word of the end of a block, {@code 0110}.
     */
    public static final int EOB = 0b0110;

    /**
     * The length of {@link #EOB}.
     */
    public static final int EOB_LENGTH = 4;

    /**
     * The flag of a decoding table entry that is the end of a block.
     */
    public static final int ENTRY_EOB = 1 << 21;

    /**
     * The flag of a decoding table entry whose amplitude is negative.
     */
    public static final int ENTRY_NEGATIVE = 1 << 20;

    /**
     * The decoding table, indexed by the next 16 bits of the stream. An entry holds the code length with its sign
     * bit in bits 0 to 4 (0 for no code), the run in bits 5 to 10, the amplitude in bits 11 to 19,
     * {@link #ENTRY_NEGATIVE} and {@link #ENTRY_EOB}.
     */
    public static final int[] DECODE = new int[1 << 16];

    /**
     * The code words of the pairs the table codes directly, indexed by {@code run * 256 + amplitude}, without the
     * sign bit; valid where {@link #CODE_LENGTHS} is not zero.
     */
    public static final int[] CODES = new int[64 * 256];

    /**
     * The lengths of {@link #CODES} without the sign bit, 0 for a pair the table does not code directly.
     */
    public static final byte[] CODE_LENGTHS = new byte[64 * 256];

    /**
     * The transmission order of the coefficients in the 8-8 mode (SMPTE ST 314 figure 27, SMPTE ST 370 figure 36):
     * the raster position of the {@code i}th coefficient.
     */
    public static final int[] ORDER_88 = order(new int[]{
            1, 2, 6, 7, 15, 16, 28, 29,
            3, 5, 8, 14, 17, 27, 30, 43,
            4, 9, 13, 18, 26, 31, 42, 44,
            10, 12, 19, 25, 32, 41, 45, 54,
            11, 20, 24, 33, 40, 46, 53, 55,
            21, 23, 34, 39, 47, 52, 56, 61,
            22, 35, 38, 48, 51, 57, 60, 62,
            36, 37, 49, 50, 58, 59, 63, 64});

    /**
     * The transmission order of the coefficients in the 2-4-8 mode (SMPTE ST 314 figure 27): rows 0 to 3 hold the
     * sums of the field pairs, rows 4 to 7 their differences.
     */
    public static final int[] ORDER_248 = order(new int[]{
            1, 3, 7, 19, 21, 35, 37, 51,
            5, 9, 17, 23, 33, 39, 49, 53,
            11, 15, 25, 31, 41, 47, 55, 61,
            13, 27, 29, 43, 45, 57, 59, 63,
            2, 4, 8, 20, 22, 36, 38, 52,
            6, 10, 18, 24, 34, 40, 50, 54,
            12, 16, 26, 32, 42, 48, 56, 62,
            14, 28, 30, 44, 46, 58, 60, 64});

    /**
     * The area number of each raster position in the 8-8 mode (SMPTE ST 314 figure 28).
     */
    public static final byte[] AREA_88 = {
            0, 0, 0, 1, 1, 1, 2, 2,
            0, 0, 1, 1, 1, 2, 2, 2,
            0, 1, 1, 1, 2, 2, 2, 3,
            1, 1, 1, 2, 2, 2, 3, 3,
            1, 1, 2, 2, 2, 3, 3, 3,
            1, 2, 2, 2, 3, 3, 3, 3,
            2, 2, 2, 3, 3, 3, 3, 3,
            2, 2, 3, 3, 3, 3, 3, 3};

    /**
     * The area number of each raster position in the 2-4-8 mode (SMPTE ST 314 figure 28).
     */
    public static final byte[] AREA_248 = {
            0, 0, 1, 1, 1, 2, 2, 3,
            0, 1, 1, 2, 2, 2, 3, 3,
            1, 1, 2, 2, 2, 3, 3, 3,
            1, 2, 2, 2, 3, 3, 3, 3,
            0, 0, 1, 1, 2, 2, 2, 3,
            0, 1, 1, 2, 2, 2, 3, 3,
            1, 1, 2, 2, 2, 3, 3, 3,
            1, 2, 2, 3, 3, 3, 3, 3};

    /**
     * The quantization steps of the four areas for each row of SMPTE ST 314 table 23, from the finest; the row of a
     * block is {@code 15 - QNO + CLASS_ROW_OFFSET[class]}.
     */
    private static final int[][] SD_STEP_ROWS = {
            {1, 1, 1, 1}, {1, 1, 1, 1}, {1, 1, 1, 1}, {1, 1, 1, 1}, {1, 1, 1, 1}, {1, 1, 1, 1}, {1, 1, 1, 1},
            {1, 1, 1, 2}, {1, 1, 2, 2}, {1, 1, 2, 2}, {1, 2, 2, 4}, {1, 2, 2, 4}, {2, 2, 4, 4}, {2, 2, 4, 4},
            {2, 4, 4, 8}, {2, 4, 4, 8}, {4, 4, 8, 8}, {4, 4, 8, 8}, {4, 8, 8, 16}, {4, 8, 8, 16},
            {8, 8, 16, 16}, {8, 8, 16, 16}};

    /**
     * The offset of each class number into {@link #SD_STEP_ROWS}.
     */
    private static final int[] CLASS_ROW_OFFSET = {0, 3, 6, 5};

    /**
     * The quantization step of each QNO and class of SMPTE ST 370 table 26; the steps the table leaves empty double
     * the step of the class before, and QNO 0, which the table does not define, takes the steps of QNO 1.
     */
    private static final int[][] HD_STEPS = {
            {1, 2, 4, 8}, {1, 2, 4, 8}, {2, 4, 8, 16}, {3, 6, 12, 24}, {4, 8, 16, 32}, {5, 10, 20, 40},
            {6, 12, 24, 48}, {7, 14, 28, 56}, {8, 16, 32, 64}, {16, 32, 64, 128}, {18, 36, 72, 144},
            {20, 40, 80, 160}, {22, 44, 88, 176}, {24, 48, 96, 192}, {28, 56, 112, 224}, {52, 104, 208, 416}};

    /**
     * The quantizer matrix of luminance for the 1080-line systems (SMPTE ST 370 figures 33 and 34).
     */
    private static final int[] MATRIX_1080_LUMA = {
            128, 16, 17, 18, 18, 19, 42, 44,
            16, 17, 18, 18, 19, 38, 43, 45,
            17, 18, 19, 19, 40, 41, 45, 48,
            18, 18, 19, 40, 41, 42, 46, 49,
            18, 19, 40, 41, 42, 43, 48, 101,
            19, 38, 41, 42, 43, 44, 98, 104,
            42, 43, 45, 46, 48, 98, 109, 116,
            44, 45, 48, 49, 101, 104, 116, 123};

    /**
     * The quantizer matrix of color difference for the 1080-line systems (SMPTE ST 370 figures 33 and 34).
     */
    private static final int[] MATRIX_1080_CHROMA = {
            128, 16, 17, 25, 26, 26, 42, 44,
            16, 17, 25, 25, 26, 38, 43, 91,
            17, 25, 26, 27, 40, 41, 91, 96,
            25, 25, 27, 40, 41, 84, 93, 197,
            26, 26, 40, 41, 84, 86, 191, 203,
            26, 38, 41, 84, 86, 177, 197, 209,
            42, 43, 91, 93, 191, 197, 219, 232,
            44, 91, 96, 197, 203, 209, 232, 246};

    /**
     * The quantizer matrix of luminance for the 720-line systems (SMPTE ST 370 figure 35).
     */
    private static final int[] MATRIX_720_LUMA = {
            128, 16, 17, 18, 18, 19, 42, 44,
            16, 17, 18, 18, 19, 38, 43, 68,
            17, 18, 19, 19, 40, 41, 68, 96,
            18, 18, 19, 40, 41, 63, 92, 98,
            18, 19, 40, 41, 63, 86, 96, 202,
            19, 38, 41, 63, 86, 88, 196, 208,
            42, 43, 68, 92, 96, 196, 218, 232,
            44, 68, 96, 98, 202, 208, 232, 246};

    /**
     * The quantizer matrix of color difference for the 720-line systems (SMPTE ST 370 figure 35).
     */
    private static final int[] MATRIX_720_CHROMA = {
            128, 24, 26, 36, 36, 38, 84, 88,
            24, 26, 36, 36, 38, 76, 86, 182,
            26, 36, 38, 38, 80, 82, 182, 192,
            36, 36, 38, 80, 82, 168, 186, 394,
            36, 38, 80, 82, 168, 192, 382, 406,
            38, 76, 82, 168, 172, 354, 394, 418,
            84, 86, 182, 186, 382, 394, 438, 464,
            88, 182, 192, 394, 406, 418, 464, 492};

    /**
     * The weights of SMPTE ST 314 clause 5.2.2 in the 8-8 mode, by raster position.
     */
    public static final double[] WEIGHT_88 = new double[64];

    /**
     * The weights of SMPTE ST 314 clause 5.2.2 in the 2-4-8 mode, by raster position.
     */
    public static final double[] WEIGHT_248 = new double[64];

    static {
        buildWeights();
        buildCodes();
    }

    /**
     * Prevents instantiation.
     */
    private DvTables() {
        throw new AssertionError();
    }

    /**
     * Returns the quantization step of an SD coefficient.
     *
     * @param qno the quantization number, from 0 to 15
     * @param classNumber the class number, from 0 to 3
     * @param area the area number, from 0 to 3
     * @return the step, without the doubling of class 3
     */
    public static int sdStep(int qno, int classNumber, int area) {
        var row = Math.min(15 - qno + CLASS_ROW_OFFSET[classNumber], SD_STEP_ROWS.length - 1);
        return SD_STEP_ROWS[row][area];
    }

    /**
     * Returns the quantization step of a DVCPRO HD coefficient.
     *
     * @param qno the quantization number, from 0 to 15
     * @param classNumber the class number, from 0 to 3
     * @return the step
     */
    public static int hdStep(int qno, int classNumber) {
        return HD_STEPS[qno][classNumber];
    }

    /**
     * Returns whether SMPTE ST 370 table 26 defines a step for a QNO and class.
     *
     * @param qno the quantization number, from 1 to 15
     * @param classNumber the class number, from 0 to 3
     * @return {@code true} when the table has the cell
     */
    public static boolean hdDefined(int qno, int classNumber) {
        if (qno < 1) {
            return false;
        }
        var classes = qno == 1 ? 4 : qno <= 3 || qno >= 9 && qno <= 14 ? 3 : qno == 8 ? 1 : 2;
        return classNumber < classes;
    }

    /**
     * Returns the DVCPRO HD quantizer matrix.
     *
     * @param progressive whether the system is a 720-line system
     * @param chroma whether the block is a color difference block
     * @return the matrix by raster position
     */
    public static int[] hdMatrix(boolean progressive, boolean chroma) {
        if (progressive) {
            return chroma ? MATRIX_720_CHROMA : MATRIX_720_LUMA;
        }
        return chroma ? MATRIX_1080_CHROMA : MATRIX_1080_LUMA;
    }

    /**
     * Inverts a figure of transmission ranks into a list of raster positions.
     *
     * @param ranks the 1-based rank of every raster position
     * @return the raster position of every rank
     */
    private static int[] order(int[] ranks) {
        var order = new int[64];
        for (var position = 0; position < 64; position++) {
            order[ranks[position] - 1] = position;
        }
        return order;
    }

    /**
     * Computes the weights of SMPTE ST 314 clause 5.2.2.
     */
    private static void buildWeights() {
        var cs = new double[8];
        for (var m = 1; m < 8; m++) {
            cs[m] = StrictMath.cos(m * StrictMath.PI / 16);
        }
        double[] w = {1, cs[4] / (4 * cs[7] * cs[2]), cs[4] / (2 * cs[6]), 1 / (2 * cs[5]), 7.0 / 8,
                cs[4] / cs[3], cs[4] / cs[2], cs[4] / cs[1]};
        for (var v = 0; v < 8; v++) {
            for (var h = 0; h < 8; h++) {
                WEIGHT_88[v * 8 + h] = w[h] * w[v] / 2;
                WEIGHT_248[v * 8 + h] = w[h] * w[2 * (v & 3)] / 2;
            }
        }
        WEIGHT_88[0] = 0.25;
        WEIGHT_248[0] = 0.25;
    }

    /**
     * Fills the coding and decoding tables from the code words of SMPTE ST 314 table 25.
     */
    private static void buildCodes() {
        // run, amplitude, code word as a binary string without the sign bit
        String[] table = {
                "0 1 00", "0 2 010", "1 1 0111", "0 3 1000", "0 4 1001", "2 1 10100", "1 2 10101", "0 5 10110",
                "0 6 10111", "3 1 110000", "4 1 110001", "0 7 110010", "0 8 110011", "5 1 1101000",
                "6 1 1101001", "2 2 1101010", "1 3 1101011", "1 4 1101100", "0 9 1101101", "0 10 1101110",
                "0 11 1101111", "7 1 11100000", "8 1 11100001", "9 1 11100010", "10 1 11100011", "3 2 11100100",
                "4 2 11100101", "2 3 11100110", "1 5 11100111", "1 6 11101000", "1 7 11101001", "0 12 11101010",
                "0 13 11101011", "0 14 11101100", "0 15 11101101", "0 16 11101110", "0 17 11101111",
                "11 1 111100000", "12 1 111100001", "13 1 111100010", "14 1 111100011", "5 2 111100100",
                "6 2 111100101", "3 3 111100110", "4 3 111100111", "2 4 111101000", "2 5 111101001",
                "1 8 111101010", "0 18 111101011", "0 19 111101100", "0 20 111101101", "0 21 111101110",
                "0 22 111101111", "5 3 1111100000", "3 4 1111100001", "3 5 1111100010", "2 6 1111100011",
                "1 9 1111100100", "1 10 1111100101", "1 11 1111100110", "0 0 11111001110", "1 0 11111001111",
                "6 3 11111010000", "4 4 11111010001", "3 6 11111010010", "1 12 11111010011", "1 13 11111010100",
                "1 14 11111010101", "2 0 111110101100", "3 0 111110101101", "4 0 111110101110",
                "5 0 111110101111", "7 2 111110110000", "8 2 111110110001", "9 2 111110110010",
                "10 2 111110110011", "7 3 111110110100", "8 3 111110110101", "4 5 111110110110",
                "3 7 111110110111", "2 7 111110111000", "2 8 111110111001", "2 9 111110111010",
                "2 10 111110111011", "2 11 111110111100", "1 15 111110111101", "1 16 111110111110",
                "1 17 111110111111"};
        for (var row : table) {
            var parts = row.split(" ");
            var run = Integer.parseInt(parts[0]);
            var amplitude = Integer.parseInt(parts[1]);
            var code = Integer.parseInt(parts[2], 2);
            var length = parts[2].length();
            CODES[run * 256 + amplitude] = code;
            CODE_LENGTHS[run * 256 + amplitude] = (byte) length;
            addDecode(code, length, run, amplitude);
        }
        for (var run = 6; run < 62; run++) {
            var code = 0b1111110 << 6 | run;
            CODES[run * 256] = code;
            CODE_LENGTHS[run * 256] = 13;
            addDecode(code, 13, run, 0);
        }
        for (var amplitude = 23; amplitude < 256; amplitude++) {
            var code = 0b1111111 << 8 | amplitude;
            CODES[amplitude] = code;
            CODE_LENGTHS[amplitude] = 15;
            addDecode(code, 15, 0, amplitude);
        }
        var eob = EOB << (16 - EOB_LENGTH);
        for (var low = 0; low < 1 << (16 - EOB_LENGTH); low++) {
            DECODE[eob | low] = EOB_LENGTH | ENTRY_EOB;
        }
    }

    /**
     * Adds a code word to the decoding table, both signs when the amplitude is not zero.
     *
     * @param code the code word without the sign bit
     * @param length the length of the code word
     * @param run the number of zero coefficients before the level
     * @param amplitude the magnitude of the level, 0 for a run of zeros alone
     */
    private static void addDecode(int code, int length, int run, int amplitude) {
        if (amplitude == 0) {
            fill(code, length, length | run << 5);
            return;
        }
        fill(code << 1, length + 1, (length + 1) | run << 5 | amplitude << 11);
        fill(code << 1 | 1, length + 1, (length + 1) | run << 5 | amplitude << 11 | ENTRY_NEGATIVE);
    }

    /**
     * Fills every decoding table entry whose leading bits are a code word.
     *
     * @param code the code word
     * @param length its length, at most 16
     * @param entry the packed table entry
     */
    private static void fill(int code, int length, int entry) {
        var shift = 16 - length;
        var base = code << shift;
        for (var low = 0; low < 1 << shift; low++) {
            DECODE[base | low] = entry;
        }
    }
}
