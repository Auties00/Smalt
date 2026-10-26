package com.github.auties00.smalt.video.h263.internal;

import com.github.auties00.smalt.video.mpeg4.internal.BitReader;
import com.github.auties00.smalt.video.mpeg4.internal.Tables;
import com.github.auties00.smalt.video.mpeg4.internal.Vlc;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Holds the code tables of the optional modes of ITU-T H.263 that the baseline tables shared with MPEG-4 Part 2 lack,
 * and reads the regularly constructed codes of Tables D.3 and U.1.
 */
final class PlusTables {
    /**
     * The value a reader of Table D.3 or Table U.1 returns for a code longer than the tables allow.
     */
    static final int INVALID = Integer.MIN_VALUE;

    /**
     * The MODB codes of the PB-frames mode (Table 11): 0 neither CBPB nor MVDB, 1 MVDB, 2 CBPB and MVDB.
     */
    static final Vlc MODB_PB = new Vlc(2, new String[]{"0", "10", "11"}, new int[]{0, 1, 2});

    /**
     * The MODB codes of the Improved PB-frames mode (Table M.1), symbols 0 to 5 as the table's indexes.
     */
    static final Vlc MODB_IMPROVED = new Vlc(5, new String[]{"0", "10", "110", "1110", "11110", "11111"},
            new int[]{0, 1, 2, 3, 4, 5});

    /**
     * The MBTYPE codes of B-pictures (Table O.1), symbols 0 to 13 as the table's indexes.
     */
    static final Vlc MBTYPE_B = new Vlc(9, new String[]{
            "11", "0001", "100", "101", "0011 0", "010", "011", "0011 1", "0010 0", "0010 1", "0000 1", "0000 01",
            "0000 001", "0000 0000 1"
    }, new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13});

    /**
     * The MBTYPE codes of EP-pictures (Table O.2), symbols 0 to 10 as the table's indexes.
     */
    static final Vlc MBTYPE_EP = new Vlc(9, new String[]{
            "1", "001", "010", "011", "0000 1", "0001 0", "0001 1", "0000 01", "0000 001", "0000 0001", "0000 0000 1"
    }, new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10});

    /**
     * The MCBPC codes of EI-pictures (Table O.3), symbols 0 to 16 as the table's indexes.
     */
    static final Vlc MCBPC_EI = new Vlc(9, new String[]{
            "1", "001", "010", "011", "0001", "0000 001", "0000 010", "0000 011", "0000 0001", "0000 1001",
            "0000 1010", "0000 1011", "0000 1100", "0000 1101", "0000 1110", "0000 1111", "0000 0000 1"
    }, new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});

    /**
     * The CBPC codes of B- and EP-pictures (Table O.4); the symbol is the chrominance pattern, Cb in bit 1 and Cr in
     * bit 0.
     */
    static final Vlc CBPC = new Vlc(3, new String[]{"0", "10", "111", "110"}, new int[]{0, 1, 2, 3});

    /**
     * The coefficient events of Table 16 in the order of the table's indexes, as {@code last << 15 | run << 9 |
     * level}; the indexes of the arithmetic coded TCOEF symbols (E.7).
     */
    static final int[] INTER_EVENTS = events(Tables.INTER_TCOEF);

    /**
     * The coefficient events of Table I.2 at the indexes of the Table 16 codes they share, as {@code last << 15 |
     * run << 9 | level}.
     */
    static final int[] INTRA_EVENTS = intraEvents();

    /**
     * Prevents instantiation.
     */
    private PlusTables() {
    }

    /**
     * Reads a vector difference or warping parameter of Table D.3 in half sample units.
     *
     * @param reader the stream
     * @return the value, or {@link #INVALID} for a code longer than the table
     */
    static int readReversible(BitReader reader) {
        if (reader.readBit() == 1) {
            return 0;
        }
        var value = 1;
        for (var i = 0; i < 12; i++) {
            var bit = reader.readBit();
            if (reader.readBit() == 0) {
                return bit == 1 ? -value : value;
            }
            value = value << 1 | bit;
        }
        return INVALID;
    }

    /**
     * Reads a value of Table U.1.
     *
     * @param reader the stream
     * @return the value, zero or more, or {@link #INVALID} for a code longer than the table
     */
    static int readIndex(BitReader reader) {
        if (reader.readBit() == 1) {
            return 0;
        }
        var value = 1;
        for (var i = 0; i < 12; i++) {
            value = value << 1 | reader.readBit();
            if (reader.readBit() == 0) {
                return value - 1;
            }
        }
        return INVALID;
    }

    /**
     * Lists the events of a coefficient table in the order of Table 16's indexes: by LAST, then RUN, then LEVEL.
     *
     * @param table the coefficient table
     * @return the events
     */
    private static int[] events(Vlc table) {
        return codes(table).values().stream().filter(symbol -> (symbol & (1 << 8)) == 0)
                .sorted(Comparator.naturalOrder()).mapToInt(Integer::intValue).toArray();
    }

    /**
     * Lists the events of Table I.2 at the Table 16 index of their code.
     *
     * @return the events
     */
    private static int[] intraEvents() {
        var inter = codes(Tables.INTER_TCOEF);
        var intra = codes(Tables.AIC_TCOEF);
        var byEvent = new HashMap<Integer, String>();
        inter.forEach((code, symbol) -> byEvent.put(symbol, code));
        var result = new int[INTER_EVENTS.length];
        for (var i = 0; i < result.length; i++) {
            result[i] = intra.get(byEvent.get(INTER_EVENTS[i]));
        }
        return result;
    }

    /**
     * Enumerates the codes of a coefficient table with a positive sign bit by probing every bit pattern.
     *
     * @param table the coefficient code table to probe
     * @return the symbols by code, as binary strings
     */
    private static Map<String, Integer> codes(Vlc table) {
        var result = new TreeMap<String, Integer>();
        var reader = new BitReader();
        var bytes = new byte[2];
        for (var length = 2; length <= 13; length++) {
            for (var value = 0; value < 1 << length; value++) {
                var word = value << (16 - length);
                bytes[0] = (byte) (word >>> 8);
                bytes[1] = (byte) word;
                reader.load(bytes, 0, 2);
                var symbol = table.decode(reader);
                if (symbol != Vlc.INVALID && symbol != Tables.ESCAPE && reader.position() == length
                        && (symbol & (1 << 8)) == 0) {
                    var code = Integer.toBinaryString(value | 1 << length).substring(1);
                    result.put(code, symbol);
                }
            }
        }
        if (result.size() != 102) {
            throw new IllegalStateException("coefficient table with " + result.size() + " events: "
                    + Arrays.toString(result.values().toArray()));
        }
        return result;
    }
}
