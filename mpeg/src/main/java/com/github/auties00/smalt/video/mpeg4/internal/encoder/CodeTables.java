package com.github.auties00.smalt.video.mpeg4.internal.encoder;

import com.github.auties00.smalt.video.mpeg4.internal.Tables;
import com.github.auties00.smalt.video.mpeg4.internal.Vlc;

/**
 * Holds the code tables of ISO/IEC 14496-2 Annex B and ITU-T H.263 that the encoders write, derived from the
 * decoding tables of {@link Tables} so that both directions share one source.
 *
 * <p>A code is packed as {@code bits << 5 | length}, zero where the symbol has no code. Coefficient codes are
 * indexed by {@link #event(int, int, int)} and leave out the sign bit, which follows every coefficient code.
 */
public final class CodeTables {
    /**
     * The intra coefficient codes of Table B-16.
     */
    public static final int[] INTRA = coefficients(Tables.INTRA_TCOEF);

    /**
     * The inter coefficient codes of Table B-17 (Table 16 of ITU-T H.263).
     */
    public static final int[] INTER = coefficients(Tables.INTER_TCOEF);

    /**
     * The Advanced INTRA Coding coefficient codes of Table I.2 of ITU-T H.263.
     */
    public static final int[] ADVANCED_INTRA = coefficients(Tables.AIC_TCOEF);

    /**
     * The intra codes of the reversible coefficient table B-23.
     */
    public static final int[] REVERSIBLE_INTRA = coefficients(Tables.RVLC_INTRA);

    /**
     * The inter codes of the reversible coefficient table B-23.
     */
    public static final int[] REVERSIBLE_INTER = coefficients(Tables.RVLC_INTER);

    /**
     * The {@code mcbpc} codes of I-VOPs, indexed {@code type << 2 | cbpc}.
     */
    public static final int[] MCBPC_I = symbols(Tables.MCBPC_I, 32);

    /**
     * The {@code mcbpc} codes of P-VOPs, indexed {@code type << 2 | cbpc}.
     */
    public static final int[] MCBPC_P = symbols(Tables.MCBPC_P, 32);

    /**
     * The {@code mcbpc} codes of H.263 P-pictures with an extended picture type, indexed {@code type << 2 | cbpc}.
     */
    public static final int[] MCBPC_P_EXTENDED = symbols(Tables.MCBPC_P_EXTENDED, 32);

    /**
     * The {@code cbpy} codes, indexed by the intra pattern (block 0 in bit 3).
     */
    public static final int[] CBPY = symbols(Tables.CBPY, 16);

    /**
     * The motion vector difference codes of Table B-12, indexed {@code mvd + 32}.
     */
    public static final int[] MVD = symbols(Tables.MVD, 65);

    /**
     * The {@code dct_dc_size_luminance} codes, indexed by the size.
     */
    public static final int[] DC_SIZE_LUMA = symbols(Tables.DC_SIZE_LUMA, 13);

    /**
     * The {@code dct_dc_size_chrominance} codes, indexed by the size.
     */
    public static final int[] DC_SIZE_CHROMA = symbols(Tables.DC_SIZE_CHROMA, 13);

    /**
     * The escape code of the coefficient tables B-16 and B-17 (and of H.263).
     */
    public static final int ESCAPE = 0b0000011 << 5 | 7;

    /**
     * The escape code of the reversible coefficient table B-23.
     */
    public static final int REVERSIBLE_ESCAPE = 0b00001 << 5 | 5;

    /**
     * Prevents instantiation.
     */
    private CodeTables() {
        throw new AssertionError();
    }

    /**
     * Returns the index of a coefficient event in the coefficient tables.
     *
     * @param last the last flag, 0 or 1
     * @param run the run of zero coefficients, from 0 to 63
     * @param level the magnitude, from 1 to 127
     * @return the index
     */
    public static int event(int last, int run, int level) {
        return last << 13 | run << 7 | level;
    }

    /**
     * Writes a packed code.
     *
     * @param writer the stream
     * @param code the code, nonzero
     */
    public static void write(BitWriter writer, int code) {
        writer.write(code >>> 5, code & 31);
    }

    /**
     * {@return the length of a packed code in bits}
     *
     * @param code the packed code
     */
    public static int length(int code) {
        return code & 31;
    }

    /**
     * Builds the encoding table of a coefficient table whose decoding symbols fold in the sign.
     *
     * @param table the decoding table
     * @return the codes without their sign bit, indexed by {@link #event(int, int, int)}
     */
    private static int[] coefficients(Vlc table) {
        var result = new int[2 << 13];
        for (var i = 0; i < table.size(); i++) {
            var symbol = table.symbol(i);
            if (symbol == Tables.ESCAPE || (symbol & 0x100) != 0) {
                continue;
            }
            var last = symbol >>> 15 & 1;
            var run = symbol >>> 9 & 63;
            var level = symbol & 0xFF;
            result[event(last, run, level)] = (table.code(i) >>> 1) << 5 | (table.length(i) - 1);
        }
        return result;
    }

    /**
     * Builds the encoding table of a table of small symbols.
     *
     * @param table the decoding table
     * @param size the number of symbols
     * @return the codes indexed by symbol
     */
    private static int[] symbols(Vlc table, int size) {
        var result = new int[size];
        for (var i = 0; i < table.size(); i++) {
            var symbol = table.symbol(i);
            if (symbol < size) {
                result[symbol] = table.code(i) << 5 | table.length(i);
            }
        }
        return result;
    }
}
