/* Ports the ISO/IEC 13818-5 MPEG-2 video reference decoder (MPEG Software Simulation Group mpeg2decode, getvlc.h and
getvlc.c). Copyright (C) 1996, MPEG Software Simulation Group; available without license fee or royalty, as is.
Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg2.internal;

import com.github.auties00.smalt.internal.bits.MsbBitReader;

/**
 * Decodes the variable length codes of ISO/IEC 13818-2 Annex B (and the identical ones of ISO/IEC 11172-2 Annex B)
 * from a {@link MsbBitReader}: macroblock addressing, macroblock types, coded block patterns, motion codes, the
 * sizes of intra DC differentials and the DCT coefficient tables B-14 and B-15.
 *
 * <p>Each table is held as the lookup arrays of the reference decoder, indexed by the leading bits of the code; an
 * entry packs a value and the code length. A decoder returns {@link #ERROR} without consuming bits for a code that
 * is not in its table. This class is thread-safe.
 */
final class Vlc {
    /**
     * The value returned for a code that is not in its table.
     */
    static final int ERROR = -1;

    /**
     * The {@code macroblock_intra} flag of a macroblock type.
     */
    static final int INTRA = 1;

    /**
     * The {@code macroblock_pattern} flag of a macroblock type.
     */
    static final int PATTERN = 2;

    /**
     * The {@code macroblock_motion_backward} flag of a macroblock type.
     */
    static final int BACKWARD = 4;

    /**
     * The {@code macroblock_motion_forward} flag of a macroblock type.
     */
    static final int FORWARD = 8;

    /**
     * The {@code macroblock_quant} flag of a macroblock type.
     */
    static final int QUANT = 16;

    /**
     * The run of a DCT table entry that codes {@code End of Block}.
     */
    static final int EOB = 64;

    /**
     * The run of a DCT table entry that codes {@code Escape}.
     */
    static final int ESCAPE = 65;

    /**
     * Table B-3, macroblock_type in P-pictures, codes 001 to 1xx.
     */
    private static final int[] P_TYPE_0 = pack(ERROR, 0, FORWARD, 3, PATTERN, 2, PATTERN, 2, FORWARD | PATTERN, 1,
            FORWARD | PATTERN, 1, FORWARD | PATTERN, 1, FORWARD | PATTERN, 1);

    /**
     * Table B-3, macroblock_type in P-pictures, codes 000001 to 00011x.
     */
    private static final int[] P_TYPE_1 = pack(ERROR, 0, QUANT | INTRA, 6, QUANT | PATTERN, 5, QUANT | PATTERN, 5,
            QUANT | FORWARD | PATTERN, 5, QUANT | FORWARD | PATTERN, 5, INTRA, 5, INTRA, 5);

    /**
     * Table B-4, macroblock_type in B-pictures, codes 0010 to 11xx.
     */
    private static final int[] B_TYPE_0 = pack(ERROR, 0, ERROR, 0, FORWARD, 4, FORWARD | PATTERN, 4, BACKWARD, 3,
            BACKWARD, 3, BACKWARD | PATTERN, 3, BACKWARD | PATTERN, 3, FORWARD | BACKWARD, 2, FORWARD | BACKWARD, 2,
            FORWARD | BACKWARD, 2, FORWARD | BACKWARD, 2, FORWARD | BACKWARD | PATTERN, 2,
            FORWARD | BACKWARD | PATTERN, 2, FORWARD | BACKWARD | PATTERN, 2, FORWARD | BACKWARD | PATTERN, 2);

    /**
     * Table B-4, macroblock_type in B-pictures, codes 000001 to 00011x.
     */
    private static final int[] B_TYPE_1 = pack(ERROR, 0, QUANT | INTRA, 6, QUANT | BACKWARD | PATTERN, 6,
            QUANT | FORWARD | PATTERN, 6, QUANT | FORWARD | BACKWARD | PATTERN, 5,
            QUANT | FORWARD | BACKWARD | PATTERN, 5, INTRA, 5, INTRA, 5);

    /**
     * Table B-10, motion_code, codes 0001 to 01xx.
     */
    private static final int[] MOTION_0 = pack(ERROR, 0, 3, 3, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1);

    /**
     * Table B-10, motion_code, codes 0000011 to 000011x.
     */
    private static final int[] MOTION_1 = pack(ERROR, 0, ERROR, 0, ERROR, 0, 7, 6, 6, 6, 5, 6, 4, 5, 4, 5);

    /**
     * Table B-10, motion_code, codes 0000001100 to 000001011x.
     */
    private static final int[] MOTION_2 = pack(16, 9, 15, 9, 14, 9, 13, 9, 12, 9, 11, 9, 10, 8, 10, 8, 9, 8, 9, 8,
            8, 8, 8, 8);

    /**
     * Table B-9, coded_block_pattern, codes 01000 to 111xx.
     */
    private static final int[] PATTERN_0 = pack(ERROR, 0, ERROR, 0, ERROR, 0, ERROR, 0, ERROR, 0, ERROR, 0, ERROR, 0,
            ERROR, 0, 62, 5, 2, 5, 61, 5, 1, 5, 56, 5, 52, 5, 44, 5, 28, 5, 40, 5, 20, 5, 48, 5, 12, 5, 32, 4, 32, 4,
            16, 4, 16, 4, 8, 4, 8, 4, 4, 4, 4, 4, 60, 3, 60, 3, 60, 3, 60, 3);

    /**
     * Table B-9, coded_block_pattern, codes 00000100 to 001111xx.
     */
    private static final int[] PATTERN_1 = pack(ERROR, 0, ERROR, 0, ERROR, 0, ERROR, 0, 58, 8, 54, 8, 46, 8, 30, 8,
            57, 8, 53, 8, 45, 8, 29, 8, 38, 8, 26, 8, 37, 8, 25, 8, 43, 8, 23, 8, 51, 8, 15, 8, 42, 8, 22, 8, 50, 8,
            14, 8, 41, 8, 21, 8, 49, 8, 13, 8, 35, 8, 19, 8, 11, 8, 7, 8, 34, 7, 34, 7, 18, 7, 18, 7, 10, 7, 10, 7, 6,
            7, 6, 7, 33, 7, 33, 7, 17, 7, 17, 7, 9, 7, 9, 7, 5, 7, 5, 7, 63, 6, 63, 6, 63, 6, 63, 6, 3, 6, 3, 6, 3, 6,
            3, 6, 36, 6, 36, 6, 36, 6, 36, 6, 24, 6, 24, 6, 24, 6, 24, 6);

    /**
     * Table B-9, coded_block_pattern, codes 000000001 to 000000111.
     */
    private static final int[] PATTERN_2 = pack(ERROR, 0, 0, 9, 39, 9, 27, 9, 59, 9, 55, 9, 47, 9, 31, 9);

    /**
     * Table B-1, macroblock_address_increment, codes 00010 to 011xx.
     */
    private static final int[] ADDRESS_1 = pack(ERROR, 0, ERROR, 0, 7, 5, 6, 5, 5, 4, 5, 4, 4, 4, 4, 4, 3, 3, 3, 3,
            3, 3, 3, 3, 2, 3, 2, 3, 2, 3, 2, 3);

    /**
     * Table B-1, macroblock_address_increment, codes 00000011000 to 0000111xxxx.
     */
    private static final int[] ADDRESS_2 = addressTable2();

    /**
     * Table B-12, dct_dc_size_luminance, codes 00xxx to 11110.
     */
    private static final int[] LUMA_DC_0 = pack(1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 0, 3, 0, 3, 0, 3, 0, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 3, 4, 3, 4, 3, 4, 3, 5, 4, 5,
            4, 6, 5, ERROR, 0);

    /**
     * Table B-12, dct_dc_size_luminance, codes 111110xxx to 111111111.
     */
    private static final int[] LUMA_DC_1 = pack(7, 6, 7, 6, 7, 6, 7, 6, 7, 6, 7, 6, 7, 6, 7, 6, 8, 7, 8, 7, 8, 7, 8,
            7, 9, 8, 9, 8, 10, 9, 11, 9);

    /**
     * Table B-13, dct_dc_size_chrominance, codes 00xxx to 11110.
     */
    private static final int[] CHROMA_DC_0 = pack(0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 1, 2, 1, 2, 1, 2, 1,
            2, 1, 2, 1, 2, 1, 2, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4,
            4, 5, 5, ERROR, 0);

    /**
     * Table B-13, dct_dc_size_chrominance, codes 111110xxxx to 1111111111.
     */
    private static final int[] CHROMA_DC_1 = pack(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 9, 9, 9,
            9, 10, 10, 11, 10);

    /**
     * Table B-14, codes 0100 to 1xxx, for the first coefficient of a non-intra block.
     */
    private static final int[] DCT_FIRST = dct(0, 2, 4, 2, 1, 4, 1, 1, 3, 1, 1, 3, 0, 1, 1, 0, 1, 1, 0, 1, 1, 0, 1, 1,
            0, 1, 1, 0, 1, 1, 0, 1, 1, 0, 1, 1);

    /**
     * Table B-14, codes 0100 to 1xxx, for every other coefficient.
     */
    private static final int[] DCT_NEXT = dct(0, 2, 4, 2, 1, 4, 1, 1, 3, 1, 1, 3, EOB, 0, 2, EOB, 0, 2, EOB, 0, 2, EOB,
            0, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2);

    /**
     * Table B-14, codes 000001xx to 00111xxx.
     */
    private static final int[] DCT_0 = dct(ESCAPE, 0, 6, ESCAPE, 0, 6, ESCAPE, 0, 6, ESCAPE, 0, 6, 2, 2, 7, 2, 2, 7,
            9, 1, 7, 9, 1, 7, 0, 4, 7, 0, 4, 7, 8, 1, 7, 8, 1, 7, 7, 1, 6, 7, 1, 6, 7, 1, 6, 7, 1, 6, 6, 1, 6, 6, 1, 6,
            6, 1, 6, 6, 1, 6, 1, 2, 6, 1, 2, 6, 1, 2, 6, 1, 2, 6, 5, 1, 6, 5, 1, 6, 5, 1, 6, 5, 1, 6, 13, 1, 8, 0, 6,
            8, 12, 1, 8, 11, 1, 8, 3, 2, 8, 1, 3, 8, 0, 5, 8, 10, 1, 8, 0, 3, 5, 0, 3, 5, 0, 3, 5, 0, 3, 5, 0, 3, 5, 0,
            3, 5, 0, 3, 5, 0, 3, 5, 4, 1, 5, 4, 1, 5, 4, 1, 5, 4, 1, 5, 4, 1, 5, 4, 1, 5, 4, 1, 5, 4, 1, 5, 3, 1, 5, 3,
            1, 5, 3, 1, 5, 3, 1, 5, 3, 1, 5, 3, 1, 5, 3, 1, 5, 3, 1, 5);

    /**
     * Table B-15, codes 000001xx to 11111111.
     */
    private static final int[] DCT_0A = dctTable0a();

    /**
     * Table B-14, codes 0000001000 to 0000001111.
     */
    private static final int[] DCT_1 = dct(16, 1, 10, 5, 2, 10, 0, 7, 10, 2, 3, 10, 1, 4, 10, 15, 1, 10, 14, 1, 10,
            4, 2, 10);

    /**
     * Table B-15, codes 000000100x to 000000111x.
     */
    private static final int[] DCT_1A = dct(5, 2, 9, 5, 2, 9, 14, 1, 9, 14, 1, 9, 2, 4, 10, 16, 1, 10, 15, 1, 9, 15,
            1, 9);

    /**
     * Tables B-14 and B-15, codes 000000010000 to 000000011111.
     */
    private static final int[] DCT_2 = dct(0, 11, 12, 8, 2, 12, 4, 3, 12, 0, 10, 12, 2, 4, 12, 7, 2, 12, 21, 1, 12,
            20, 1, 12, 0, 9, 12, 19, 1, 12, 18, 1, 12, 1, 5, 12, 3, 3, 12, 0, 8, 12, 6, 2, 12, 17, 1, 12);

    /**
     * Tables B-14 and B-15, codes 0000000010000 to 0000000011111.
     */
    private static final int[] DCT_3 = dct(10, 2, 13, 9, 2, 13, 5, 3, 13, 3, 4, 13, 2, 5, 13, 1, 7, 13, 1, 6, 13, 0,
            15, 13, 0, 14, 13, 0, 13, 13, 0, 12, 13, 26, 1, 13, 25, 1, 13, 24, 1, 13, 23, 1, 13, 22, 1, 13);

    /**
     * Tables B-14 and B-15, codes 00000000010000 to 00000000011111.
     */
    private static final int[] DCT_4 = dct(0, 31, 14, 0, 30, 14, 0, 29, 14, 0, 28, 14, 0, 27, 14, 0, 26, 14, 0, 25,
            14, 0, 24, 14, 0, 23, 14, 0, 22, 14, 0, 21, 14, 0, 20, 14, 0, 19, 14, 0, 18, 14, 0, 17, 14, 0, 16, 14);

    /**
     * Tables B-14 and B-15, codes 000000000010000 to 000000000011111.
     */
    private static final int[] DCT_5 = dct(0, 40, 15, 0, 39, 15, 0, 38, 15, 0, 37, 15, 0, 36, 15, 0, 35, 15, 0, 34,
            15, 0, 33, 15, 0, 32, 15, 1, 14, 15, 1, 13, 15, 1, 12, 15, 1, 11, 15, 1, 10, 15, 1, 9, 15, 1, 8, 15);

    /**
     * Tables B-14 and B-15, codes 0000000000010000 to 0000000000011111.
     */
    private static final int[] DCT_6 = dct(1, 18, 16, 1, 17, 16, 1, 16, 16, 1, 15, 16, 6, 3, 16, 16, 2, 16, 15, 2,
            16, 14, 2, 16, 13, 2, 16, 12, 2, 16, 11, 2, 16, 31, 1, 16, 30, 1, 16, 29, 1, 16, 28, 1, 16, 27, 1, 16);

    private Vlc() {
        throw new AssertionError();
    }

    /**
     * Packs value and length pairs.
     *
     * @param pairs the values and code lengths, alternating
     * @return the entries, each {@code value << 8 | length}
     */
    private static int[] pack(int... pairs) {
        var table = new int[pairs.length / 2];
        for (var index = 0; index < table.length; index++) {
            table[index] = pairs[2 * index] << 8 | pairs[2 * index + 1];
        }
        return table;
    }

    /**
     * Packs run, level and length triples of a DCT coefficient table.
     *
     * @param triples the runs, levels and code lengths, in turn
     * @return the entries, each {@code run << 16 | level << 8 | length}
     */
    private static int[] dct(int... triples) {
        var table = new int[triples.length / 3];
        for (var index = 0; index < table.length; index++) {
            table[index] = triples[3 * index] << 16 | triples[3 * index + 1] << 8 | triples[3 * index + 2];
        }
        return table;
    }

    /**
     * Builds Table B-1 for the codes 00000011000 to 0000111xxxx, whose entries repeat in runs.
     *
     * @return the 104 entries
     */
    private static int[] addressTable2() {
        var pairs = new int[]{33, 11, 32, 11, 31, 11, 30, 11, 29, 11, 28, 11, 27, 11, 26, 11, 25, 11, 24, 11, 23, 11,
                22, 11};
        var table = new int[104];
        var index = 0;
        for (var pair = 0; pair < pairs.length; pair += 2) {
            table[index++] = pairs[pair] << 8 | pairs[pair + 1];
        }
        for (var value = 21; value >= 16; value--) {
            table[index++] = value << 8 | 10;
            table[index++] = value << 8 | 10;
        }
        for (var value = 15; value >= 10; value--) {
            for (var repeat = 0; repeat < 8; repeat++) {
                table[index++] = value << 8 | 8;
            }
        }
        for (var value = 9; value >= 8; value--) {
            for (var repeat = 0; repeat < 16; repeat++) {
                table[index++] = value << 8 | 7;
            }
        }
        return table;
    }

    /**
     * Builds Table B-15 for the codes 000001xx to 11111111, as the reference decoder lists it.
     *
     * @return the 252 entries
     */
    private static int[] dctTable0a() {
        var table = new int[252];
        var index = 0;
        index = fill(table, index, 4, ESCAPE, 0, 6);
        index = fill(table, index, 2, 7, 1, 7);
        index = fill(table, index, 2, 8, 1, 7);
        index = fill(table, index, 2, 6, 1, 7);
        index = fill(table, index, 2, 2, 2, 7);
        index = fill(table, index, 4, 0, 7, 6);
        index = fill(table, index, 4, 0, 6, 6);
        index = fill(table, index, 4, 4, 1, 6);
        index = fill(table, index, 4, 5, 1, 6);
        var eightBit = new int[]{1, 5, 11, 1, 0, 11, 0, 10, 13, 1, 12, 1, 3, 2, 1, 4};
        for (var pair = 0; pair < eightBit.length; pair += 2) {
            index = fill(table, index, 1, eightBit[pair], eightBit[pair + 1], 8);
        }
        index = fill(table, index, 8, 2, 1, 5);
        index = fill(table, index, 8, 1, 2, 5);
        index = fill(table, index, 8, 3, 1, 5);
        index = fill(table, index, 32, 1, 1, 3);
        index = fill(table, index, 16, EOB, 0, 4);
        index = fill(table, index, 16, 0, 3, 4);
        index = fill(table, index, 64, 0, 1, 2);
        index = fill(table, index, 32, 0, 2, 3);
        index = fill(table, index, 8, 0, 4, 5);
        index = fill(table, index, 8, 0, 5, 5);
        index = fill(table, index, 2, 9, 1, 7);
        index = fill(table, index, 2, 1, 3, 7);
        index = fill(table, index, 2, 10, 1, 7);
        index = fill(table, index, 2, 0, 8, 7);
        index = fill(table, index, 2, 0, 9, 7);
        var tail = new int[]{0, 12, 0, 13, 2, 3, 4, 2, 0, 14, 0, 15};
        for (var pair = 0; pair < tail.length; pair += 2) {
            index = fill(table, index, 1, tail[pair], tail[pair + 1], 8);
        }
        if (index != table.length) {
            throw new AssertionError(index);
        }
        return table;
    }

    /**
     * Writes repeated entries into a DCT table.
     *
     * @param table the packed run, level and length entries
     * @param index the first index to write
     * @param count the number of copies
     * @param run the run of zero coefficients
     * @param level the absolute level
     * @param length the code length
     * @return the index after the copies
     */
    private static int fill(int[] table, int index, int count, int run, int level, int length) {
        for (var copy = 0; copy < count; copy++) {
            table[index++] = run << 16 | level << 8 | length;
        }
        return index;
    }

    /**
     * Returns the value of a packed value and length entry, consuming its code.
     *
     * @param bits the reader
     * @param entry the packed table entry of the code the reader is at
     * @return the value, or {@link #ERROR} for an error entry (no bits consumed)
     */
    private static int take(MsbBitReader bits, int entry) {
        var length = entry & 0xFF;
        if (length == 0) {
            return ERROR;
        }
        bits.skip(length);
        return entry >> 8;
    }

    /**
     * Decodes {@code macroblock_type} of an I-picture (Table B-2).
     *
     * @param bits the reader
     * @return the macroblock type flags, or {@link #ERROR}
     */
    static int intraType(MsbBitReader bits) {
        if (bits.readBit() == 1) {
            return INTRA;
        }
        return bits.readBit() == 1 ? INTRA | QUANT : ERROR;
    }

    /**
     * Decodes {@code macroblock_type} of a P-picture (Table B-3).
     *
     * @param bits the reader
     * @return the macroblock type flags, or {@link #ERROR}
     */
    static int predictiveType(MsbBitReader bits) {
        var code = bits.peek(6);
        if (code >= 8) {
            return take(bits, P_TYPE_0[code >> 3]);
        }
        return code == 0 ? ERROR : take(bits, P_TYPE_1[code]);
    }

    /**
     * Decodes {@code macroblock_type} of a B-picture (Table B-4).
     *
     * @param bits the reader
     * @return the macroblock type flags, or {@link #ERROR}
     */
    static int bidirectionalType(MsbBitReader bits) {
        var code = bits.peek(6);
        if (code >= 8) {
            return take(bits, B_TYPE_0[code >> 2]);
        }
        return code == 0 ? ERROR : take(bits, B_TYPE_1[code]);
    }

    /**
     * Decodes {@code motion_code} (Table B-10) with its sign.
     *
     * @param bits the reader
     * @return the motion code, from -16 to 16, or {@code Integer.MIN_VALUE} for an invalid code
     */
    static int motionCode(MsbBitReader bits) {
        if (bits.readBit() == 1) {
            return 0;
        }
        var code = bits.peek(9);
        int entry;
        if (code >= 64) {
            entry = MOTION_0[code >> 6];
        } else if (code >= 24) {
            entry = MOTION_1[code >> 3];
        } else if (code >= 12) {
            entry = MOTION_2[code - 12];
        } else {
            return Integer.MIN_VALUE;
        }
        var value = take(bits, entry);
        if (value == ERROR) {
            return Integer.MIN_VALUE;
        }
        return bits.readBit() == 1 ? -value : value;
    }

    /**
     * Decodes {@code dmvector} (Table B-11).
     *
     * @param bits the reader
     * @return -1, 0 or 1
     */
    static int dualPrimeVector(MsbBitReader bits) {
        if (bits.readBit() == 0) {
            return 0;
        }
        return bits.readBit() == 1 ? -1 : 1;
    }

    /**
     * Decodes {@code coded_block_pattern_420} (Table B-9).
     *
     * @param bits the reader
     * @return the six-bit pattern, or {@link #ERROR}
     */
    static int codedBlockPattern(MsbBitReader bits) {
        var code = bits.peek(9);
        if (code >= 128) {
            return take(bits, PATTERN_0[code >> 4]);
        }
        if (code >= 8) {
            return take(bits, PATTERN_1[code >> 1]);
        }
        return code < 1 ? ERROR : take(bits, PATTERN_2[code]);
    }

    /**
     * Decodes {@code macroblock_address_increment} (Table B-1), with any {@code macroblock_escape} and
     * {@code macroblock_stuffing} codes before it.
     *
     * @param bits the reader
     * @return the increment, at least 1, or {@link #ERROR}
     */
    static int addressIncrement(MsbBitReader bits) {
        var value = 0;
        int code;
        while ((code = bits.peek(11)) < 24) {
            if (code == 8) {
                value += 33;
            } else if (code != 15) {
                return ERROR;
            }
            bits.skip(11);
            if (value > 1 << 16) {
                return ERROR;
            }
        }
        if (code >= 1024) {
            bits.skip(1);
            return value + 1;
        }
        if (code >= 128) {
            var increment = take(bits, ADDRESS_1[code >> 6]);
            return increment == ERROR ? ERROR : value + increment;
        }
        return value + take(bits, ADDRESS_2[code - 24]);
    }

    /**
     * Decodes {@code dct_dc_size_luminance} (Table B-12).
     *
     * @param bits the reader
     * @return the size, from 0 to 11, or {@link #ERROR}
     */
    static int lumaDcSize(MsbBitReader bits) {
        var code = bits.peek(5);
        if (code < 31) {
            return take(bits, LUMA_DC_0[code]);
        }
        return take(bits, LUMA_DC_1[bits.peek(9) - 0x1F0]);
    }

    /**
     * Decodes {@code dct_dc_size_chrominance} (Table B-13).
     *
     * @param bits the reader
     * @return the size, from 0 to 11, or {@link #ERROR}
     */
    static int chromaDcSize(MsbBitReader bits) {
        var code = bits.peek(5);
        if (code < 31) {
            return take(bits, CHROMA_DC_0[code]);
        }
        return take(bits, CHROMA_DC_1[bits.peek(10) - 0x3E0]);
    }

    /**
     * Looks up a DCT coefficient code of Table B-14 or B-15 from the next 16 bits, without consuming them.
     *
     * @param code the next 16 bits
     * @param first whether the code is the first coefficient of a non-intra block, which Table B-14 codes as
     * {@code 1s} instead of {@code End of Block}
     * @param tableOne whether Table B-15 applies ({@code intra_vlc_format} in an intra block)
     * @return the entry, {@code run << 16 | level << 8 | length}, with run {@link #EOB} or {@link #ESCAPE} for
     * those codes; 0 for a code in neither table
     */
    static int coefficient(int code, boolean first, boolean tableOne) {
        if (code >= 16384 && !tableOne) {
            return first ? DCT_FIRST[(code >> 12) - 4] : DCT_NEXT[(code >> 12) - 4];
        }
        if (code >= 1024) {
            return tableOne ? DCT_0A[(code >> 8) - 4] : DCT_0[(code >> 8) - 4];
        }
        if (code >= 512) {
            return tableOne ? DCT_1A[(code >> 6) - 8] : DCT_1[(code >> 6) - 8];
        }
        if (code >= 256) {
            return DCT_2[(code >> 4) - 16];
        }
        if (code >= 128) {
            return DCT_3[(code >> 3) - 16];
        }
        if (code >= 64) {
            return DCT_4[(code >> 2) - 16];
        }
        if (code >= 32) {
            return DCT_5[(code >> 1) - 16];
        }
        if (code >= 16) {
            return DCT_6[code - 16];
        }
        return 0;
    }
}
