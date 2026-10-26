package com.github.auties00.smalt.audio.mp3;

/**
 * Holds the constant tables of the MPEG audio Layer III encoder.
 *
 * <p>The Huffman code words are not stored: they are derived once, at class initialization, by
 * walking the decoder's lookup tables ({@link Mp3Tables#HUFFMAN}, {@link Mp3Tables#COUNT1_A} and
 * {@link Mp3Tables#COUNT1_B}) over every bit prefix, so the encoder writes exactly the codes the
 * decoder reads (ISO/IEC 11172-3 Table B.7). The scalefactor band layouts are likewise taken from
 * the decoder's {@link Mp3Tables#SFB_LONG} and {@link Mp3Tables#SFB_SHORT}.
 */
final class Mp3EncoderTables {
    /**
     * The sampling rates of the three MPEG versions, MPEG-1 first, then MPEG-2 and MPEG-2.5, each
     * in the order of the header's two sample-rate bits (ISO/IEC 11172-3 2.4.2.3, ISO/IEC 13818-3
     * 2.4.2.3).
     */
    static final int[] SAMPLE_RATES = {44100, 48000, 32000, 22050, 24000, 16000, 11025, 12000, 8000};

    /**
     * The Layer III bitrates in kilobits per second by bitrate index, for MPEG-1 (row 0) and for
     * MPEG-2 and MPEG-2.5 (row 1); index 0 is the free format and is not used.
     */
    static final int[][] BITRATES = {
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}
    };

    /**
     * The coefficients {@code C[i]} of the polyphase analysis window multiplied by {@code 2^21},
     * which makes every one an integer (ISO/IEC 11172-3 Table C.1).
     */
    static final int[] ANALYSIS_WINDOW = {
            0, -1, -1, -1, -1, -1, -1, -2, -2, -2, -2, -3, -3, -4, -4, -5,
            -5, -6, -7, -7, -8, -9, -10, -11, -13, -14, -16, -17, -19, -21, -24, -26,
            -29, -31, -35, -38, -41, -45, -49, -53, -58, -63, -68, -73, -79, -85, -91, -97,
            -104, -111, -117, -125, -132, -139, -147, -154, -161, -169, -176, -183, -190, -196, -202, -208,
            213, 218, 222, 225, 227, 228, 228, 227, 224, 221, 215, 208, 200, 189, 177, 163,
            146, 127, 106, 83, 57, 29, -2, -36, -72, -111, -153, -197, -244, -294, -347, -401,
            -459, -519, -581, -645, -711, -779, -848, -919, -991, -1064, -1137, -1210, -1283, -1356, -1428, -1498,
            -1567, -1634, -1698, -1759, -1817, -1870, -1919, -1962, -2001, -2032, -2057, -2075, -2085, -2087, -2080, -2063,
            2037, 2000, 1952, 1893, 1822, 1739, 1644, 1535, 1414, 1280, 1131, 970, 794, 605, 402, 185,
            -45, -288, -545, -814, -1095, -1388, -1692, -2006, -2330, -2663, -3004, -3351, -3705, -4063, -4425, -4788,
            -5153, -5517, -5879, -6237, -6589, -6935, -7271, -7597, -7910, -8209, -8491, -8755, -8998, -9219, -9416, -9585,
            -9727, -9838, -9916, -9959, -9966, -9935, -9863, -9750, -9592, -9389, -9139, -8840, -8492, -8092, -7640, -7134,
            6574, 5959, 5288, 4561, 3776, 2935, 2037, 1082, 70, -998, -2122, -3300, -4533, -5818, -7154, -8540,
            -9975, -11455, -12980, -14548, -16155, -17799, -19478, -21189, -22929, -24694, -26482, -28289, -30112, -31947, -33791, -35640,
            -37489, -39336, -41176, -43006, -44821, -46617, -48390, -50137, -51853, -53534, -55178, -56778, -58333, -59838, -61289, -62684,
            -64019, -65290, -66494, -67629, -68692, -69679, -70590, -71420, -72169, -72835, -73415, -73908, -74313, -74630, -74856, -74992,
            75038, 74992, 74856, 74630, 74313, 73908, 73415, 72835, 72169, 71420, 70590, 69679, 68692, 67629, 66494, 65290,
            64019, 62684, 61289, 59838, 58333, 56778, 55178, 53534, 51853, 50137, 48390, 46617, 44821, 43006, 41176, 39336,
            37489, 35640, 33791, 31947, 30112, 28289, 26482, 24694, 22929, 21189, 19478, 17799, 16155, 14548, 12980, 11455,
            9975, 8540, 7154, 5818, 4533, 3300, 2122, 998, -70, -1082, -2037, -2935, -3776, -4561, -5288, -5959,
            6574, 7134, 7640, 8092, 8492, 8840, 9139, 9389, 9592, 9750, 9863, 9935, 9966, 9959, 9916, 9838,
            9727, 9585, 9416, 9219, 8998, 8755, 8491, 8209, 7910, 7597, 7271, 6935, 6589, 6237, 5879, 5517,
            5153, 4788, 4425, 4063, 3705, 3351, 3004, 2663, 2330, 2006, 1692, 1388, 1095, 814, 545, 288,
            45, -185, -402, -605, -794, -970, -1131, -1280, -1414, -1535, -1644, -1739, -1822, -1893, -1952, -2000,
            2037, 2063, 2080, 2087, 2085, 2075, 2057, 2032, 2001, 1962, 1919, 1870, 1817, 1759, 1698, 1634,
            1567, 1498, 1428, 1356, 1283, 1210, 1137, 1064, 991, 919, 848, 779, 711, 645, 581, 519,
            459, 401, 347, 294, 244, 197, 153, 111, 72, 36, 2, -29, -57, -83, -106, -127,
            -146, -163, -177, -189, -200, -208, -215, -221, -224, -227, -228, -228, -227, -225, -222, -218,
            213, 208, 202, 196, 190, 183, 176, 169, 161, 154, 147, 139, 132, 125, 117, 111,
            104, 97, 91, 85, 79, 73, 68, 63, 58, 53, 49, 45, 41, 38, 35, 31,
            29, 26, 24, 21, 19, 17, 16, 14, 13, 11, 10, 9, 8, 7, 7, 6,
            5, 5, 4, 4, 3, 3, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1
    };

    /**
     * The pre-emphasis added to the long-block scalefactors of bands 0 to 21 when {@code preflag}
     * is set (ISO/IEC 11172-3 Table B.6).
     */
    static final int[] PRETAB = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 3, 2, 0};

    /**
     * The MPEG-1 scalefactor lengths {@code slen1} by {@code scalefac_compress} (ISO/IEC 11172-3
     * 2.4.2.7).
     */
    static final int[] SLEN1 = {0, 0, 0, 0, 3, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4};

    /**
     * The MPEG-1 scalefactor lengths {@code slen2} by {@code scalefac_compress} (ISO/IEC 11172-3
     * 2.4.2.7).
     */
    static final int[] SLEN2 = {0, 1, 2, 3, 0, 1, 2, 3, 1, 2, 3, 1, 2, 3, 2, 3};

    /**
     * The MPEG-2 scalefactor partition sizes {@code nr_of_sfb_block}, indexed by table (the
     * {@code scalefac_compress} range), then long or short blocks, then partition; short-block
     * counts are in bands times windows (ISO/IEC 13818-3 Table B.1, non-intensity rows).
     */
    static final int[][][] LSF_PARTITIONS = {
            {{6, 5, 5, 5}, {9, 9, 9, 9}},
            {{6, 5, 7, 3}, {9, 9, 12, 6}},
            {{11, 10, 0, 0}, {18, 18, 0, 0}}
    };

    /**
     * The scalefactor bands of the MPEG-1 scalefactor-selection groups {@code scfsi_band}: the
     * first band of each of the four groups and the end of the last (ISO/IEC 11172-3 Table B.5).
     */
    static final int[] SCFSI_BANDS = {0, 6, 11, 16, 21};

    /**
     * The number of escape bits of each big-value table (ISO/IEC 11172-3 Table B.7).
     */
    static final int[] LINBITS = Mp3Tables.LINBITS;

    /**
     * The value range of each big-value table: values must be below this bound ({@code xlen}),
     * 16 for the escape tables, 0 for the tables that do not exist.
     */
    static final int[] TABLE_RANGE = {
            1, 2, 3, 3, 0, 4, 4, 6, 6, 6, 8, 8, 8, 16, 0, 16,
            16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16
    };

    /**
     * The Huffman code word of each big-value pair {@code x * 16 + y} by table; tables 16 to 23
     * and 24 to 31 share the code words of tables 16 and 24.
     */
    static final int[][] PAIR_CODES = new int[32][];

    /**
     * The code word length of each big-value pair {@code x * 16 + y} by table, sign bits excluded.
     */
    static final byte[][] PAIR_LENGTHS = new byte[32][];

    /**
     * The code word of each count1 quadruple {@code v << 3 | w << 2 | x << 1 | y}, for table A (row
     * 0) and table B (row 1).
     */
    static final int[][] QUAD_CODES = new int[2][16];

    /**
     * The code word length of each count1 quadruple, sign bits excluded, for tables A and B.
     */
    static final byte[][] QUAD_LENGTHS = new byte[2][16];

    /**
     * The value {@code x^(4/3)} for every quantized magnitude a big-value table codes, {@code 0}
     * to {@code 15 + 2^13 - 1}.
     */
    static final float[] POW43 = new float[8207];

    /**
     * The largest quantized magnitude Layer III codes: 15 plus the 13 escape bits of tables 23 and
     * 31.
     */
    static final int MAX_QUANTIZED = 8206;

    static {
        for (var table = 1; table < 32; table++) {
            if (TABLE_RANGE[table] == 0) {
                continue;
            }
            if (table > 16 && table < 24) {
                PAIR_CODES[table] = PAIR_CODES[16];
                PAIR_LENGTHS[table] = PAIR_LENGTHS[16];
                continue;
            }
            if (table > 24) {
                PAIR_CODES[table] = PAIR_CODES[24];
                PAIR_LENGTHS[table] = PAIR_LENGTHS[24];
                continue;
            }
            var codes = new int[256];
            var lengths = new byte[256];
            derivePairCodes(Mp3Tables.HUFFMAN_INDEX[table], 0, 0, codes, lengths);
            PAIR_CODES[table] = codes;
            PAIR_LENGTHS[table] = lengths;
        }
        PAIR_CODES[0] = new int[256];
        PAIR_LENGTHS[0] = new byte[256];
        deriveQuadCodes(0, 0, 0);
        deriveQuadCodes(1, 0, 0);
        for (var i = 0; i < POW43.length; i++) {
            POW43[i] = (float) StrictMath.pow(i, 4.0 / 3.0);
        }
    }

    /**
     * Prevents instantiation.
     */
    private Mp3EncoderTables() {
        throw new AssertionError();
    }

    /**
     * Records the code word of every pair whose code starts with a prefix, by decoding the prefix
     * with the decoder's lookup table and descending until the code length equals the prefix
     * length.
     *
     * @param codebook the offset of the table in {@link Mp3Tables#HUFFMAN}
     * @param prefix   the prefix bits, right-aligned
     * @param length   the prefix length, at most 19 (the longest Layer III code)
     * @param codes    receives the code words
     * @param lengths  receives the code lengths
     */
    private static void derivePairCodes(int codebook, int prefix, int length, int[] codes, byte[] lengths) {
        var cache = length == 0 ? 0 : prefix << (32 - length);
        var consumed = 0;
        var w = 5;
        int leaf = Mp3Tables.HUFFMAN[codebook + (cache >>> 27)];
        while (leaf < 0) {
            cache <<= w;
            consumed += w;
            w = leaf & 7;
            leaf = Mp3Tables.HUFFMAN[codebook + (cache >>> (32 - w)) - (leaf >> 3)];
        }
        consumed += leaf >> 8;
        if (consumed == length) {
            var x = leaf & 0x0F;
            var y = (leaf >> 4) & 0x0F;
            codes[x * 16 + y] = prefix;
            lengths[x * 16 + y] = (byte) length;
            return;
        }
        if (length >= 19) {
            throw new AssertionError("Huffman code longer than 19 bits");
        }
        derivePairCodes(codebook, prefix << 1, length + 1, codes, lengths);
        derivePairCodes(codebook, prefix << 1 | 1, length + 1, codes, lengths);
    }

    /**
     * Records the code word of every count1 quadruple whose code starts with a prefix.
     *
     * @param table  {@code 0} for table A, {@code 1} for table B
     * @param prefix the prefix bits, right-aligned
     * @param length the prefix length, at most 6
     */
    private static void deriveQuadCodes(int table, int prefix, int length) {
        var cache = length == 0 ? 0 : prefix << (32 - length);
        var lookup = table == 0 ? Mp3Tables.COUNT1_A : Mp3Tables.COUNT1_B;
        var leaf = lookup[cache >>> 28];
        if ((leaf & 8) == 0) {
            leaf = lookup[(leaf >> 3) + ((cache << 4) >>> (32 - (leaf & 3)))];
        }
        var consumed = leaf & 7;
        if (consumed == length) {
            var quad = (leaf >> 4) & 0x0F;
            QUAD_CODES[table][quad] = prefix;
            QUAD_LENGTHS[table][quad] = (byte) length;
            return;
        }
        if (length >= 6) {
            throw new AssertionError("count1 code longer than 6 bits");
        }
        deriveQuadCodes(table, prefix << 1, length + 1);
        deriveQuadCodes(table, prefix << 1 | 1, length + 1);
    }

    /**
     * Returns the index of a sampling rate in {@link #SAMPLE_RATES}.
     *
     * @param sampleRate the rate in hertz
     * @return the index, or {@code -1} when Layer III does not code the rate
     */
    static int sampleRateIndex(int sampleRate) {
        for (var i = 0; i < SAMPLE_RATES.length; i++) {
            if (SAMPLE_RATES[i] == sampleRate) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the long-block scalefactor band boundaries of a sampling rate.
     *
     * @param rateIndex the index in {@link #SAMPLE_RATES}
     * @return 23 ascending line indices, from 0 to 576
     */
    static int[] longBands(int rateIndex) {
        var widths = Mp3Tables.SFB_LONG[decoderClass(rateIndex)];
        var bounds = new int[23];
        for (var i = 0; i < 22; i++) {
            bounds[i + 1] = bounds[i] + widths[i];
        }
        return bounds;
    }

    /**
     * Returns the short-block scalefactor band boundaries of a sampling rate, in lines of one
     * short window.
     *
     * @param rateIndex the index in {@link #SAMPLE_RATES}
     * @return 14 ascending line indices, from 0 to 192
     */
    static int[] shortBands(int rateIndex) {
        var widths = Mp3Tables.SFB_SHORT[decoderClass(rateIndex)];
        var bounds = new int[14];
        for (var i = 0; i < 13; i++) {
            bounds[i + 1] = bounds[i] + widths[3 * i];
        }
        return bounds;
    }

    /**
     * Returns the row of the decoder's band tables for a sampling rate, the same class the decoder
     * derives from the frame header.
     *
     * @param rateIndex the index in {@link #SAMPLE_RATES}
     * @return the row index
     */
    private static int decoderClass(int rateIndex) {
        var version = rateIndex / 3;
        var rateBits = rateIndex % 3;
        var raw = rateBits + (version == 0 ? 6 : version == 1 ? 3 : 0);
        return raw != 0 ? raw - 1 : 0;
    }
}
