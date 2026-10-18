/* Ports libopus 1.5.2 (silk/shell_coder.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeDecoder;
import com.github.auties00.smalt.audio.opus.internal.celt.RangeEncoder;

/**
 * Codes the pulse amplitudes of one 16-sample shell block as a binary tree of pulse-count splits.
 *
 * @implNote This implementation keeps the tree levels of the decoder in local variables instead of stack arrays; the
 * order of the coded symbols is unchanged.
 */
public final class ShellCoder {
    private ShellCoder() {
        throw new AssertionError();
    }

    /**
     * Sums adjacent pairs of pulse counts ({@code combine_pulses}).
     *
     * @param out   receives {@code len} sums
     * @param in    the input counts
     * @param inOff the index of the first input count
     * @param len   the number of output sums
     */
    private static void combinePulses(int[] out, int[] in, int inOff, int len) {
        for (var k = 0; k < len; k++) {
            out[k] = in[inOff + 2 * k] + in[inOff + 2 * k + 1];
        }
    }

    /**
     * Encodes the split of {@code p} pulses into a first child of {@code p_child1}
     * ({@code encode_split}).
     *
     * @param psRangeEnc  the range encoder
     * @param p_child1    the pulse count of the first child
     * @param p           the pulse count of the parent
     * @param shell_table the table of split iCDFs of this tree level
     */
    private static void encodeSplit(RangeEncoder psRangeEnc, int p_child1, int p, short[] shell_table) {
        if (p > 0) {
            psRangeEnc.encIcdf(p_child1, shell_table, Tables.silk_shell_code_table_offsets[p], 8);
        }
    }

    /**
     * Decodes the pulse count of the first child of a parent with {@code p} pulses
     * ({@code decode_split}); the second child holds the remaining {@code p - child1} pulses.
     *
     * @param psRangeDec  the range decoder
     * @param p           the pulse count of the parent
     * @param shell_table the table of split iCDFs of this tree level
     * @return the pulse count of the first child, zero when {@code p} is zero
     */
    private static int decodeSplit(RangeDecoder psRangeDec, int p, short[] shell_table) {
        if (p > 0) {
            return psRangeDec.decIcdf(shell_table, Tables.silk_shell_code_table_offsets[p], 8);
        }
        return 0;
    }

    /**
     * Encodes one shell block of 16 non-negative pulse amplitudes ({@code silk_shell_encoder}).
     *
     * @param psRangeEnc the range encoder
     * @param pulses0    the pulse amplitudes
     * @param off        the index of the first amplitude of the block
     */
    public static void shellEncoder(RangeEncoder psRangeEnc, int[] pulses0, int off) {
        shellEncoder(psRangeEnc, pulses0, off, new int[8], new int[4], new int[2], new int[1]);
    }

    /**
     * Encodes one shell block of 16 non-negative pulse amplitudes using caller-provided work
     * arrays ({@code silk_shell_encoder}).
     *
     * @param psRangeEnc the range encoder
     * @param pulses0    the pulse amplitudes
     * @param off        the index of the first amplitude of the block
     * @param pulses1    a work array of 8 elements, overwritten
     * @param pulses2    a work array of 4 elements, overwritten
     * @param pulses3    a work array of 2 elements, overwritten
     * @param pulses4    a work array of 1 element, overwritten
     */
    public static void shellEncoder(RangeEncoder psRangeEnc, int[] pulses0, int off, int[] pulses1, int[] pulses2,
                                    int[] pulses3, int[] pulses4) {
        combinePulses(pulses1, pulses0, off, 8);
        combinePulses(pulses2, pulses1, 0, 4);
        combinePulses(pulses3, pulses2, 0, 2);
        combinePulses(pulses4, pulses3, 0, 1);
        var t0 = Tables.silk_shell_code_table0;
        var t1 = Tables.silk_shell_code_table1;
        var t2 = Tables.silk_shell_code_table2;
        var t3 = Tables.silk_shell_code_table3;
        encodeSplit(psRangeEnc, pulses3[0], pulses4[0], t3);
        encodeSplit(psRangeEnc, pulses2[0], pulses3[0], t2);
        encodeSplit(psRangeEnc, pulses1[0], pulses2[0], t1);
        encodeSplit(psRangeEnc, pulses0[off], pulses1[0], t0);
        encodeSplit(psRangeEnc, pulses0[off + 2], pulses1[1], t0);
        encodeSplit(psRangeEnc, pulses1[2], pulses2[1], t1);
        encodeSplit(psRangeEnc, pulses0[off + 4], pulses1[2], t0);
        encodeSplit(psRangeEnc, pulses0[off + 6], pulses1[3], t0);
        encodeSplit(psRangeEnc, pulses2[2], pulses3[1], t2);
        encodeSplit(psRangeEnc, pulses1[4], pulses2[2], t1);
        encodeSplit(psRangeEnc, pulses0[off + 8], pulses1[4], t0);
        encodeSplit(psRangeEnc, pulses0[off + 10], pulses1[5], t0);
        encodeSplit(psRangeEnc, pulses1[6], pulses2[3], t1);
        encodeSplit(psRangeEnc, pulses0[off + 12], pulses1[6], t0);
        encodeSplit(psRangeEnc, pulses0[off + 14], pulses1[7], t0);
    }

    /**
     * Decodes one shell block of 16 non-negative pulse amplitudes ({@code silk_shell_decoder}).
     *
     * @param pulses0    receives the pulse amplitudes
     * @param off        the index of the first amplitude of the block
     * @param psRangeDec the range decoder
     * @param pulses4    the total number of pulses of the block
     */
    public static void shellDecoder(short[] pulses0, int off, RangeDecoder psRangeDec, int pulses4) {
        var t0 = Tables.silk_shell_code_table0;
        var t1 = Tables.silk_shell_code_table1;
        var t2 = Tables.silk_shell_code_table2;
        var t3 = Tables.silk_shell_code_table3;
        var p3a = (short) decodeSplit(psRangeDec, pulses4, t3);
        var p3b = (short) (pulses4 - p3a);
        var p2a = (short) decodeSplit(psRangeDec, p3a, t2);
        var p2b = (short) (p3a - p2a);
        var p1a = (short) decodeSplit(psRangeDec, p2a, t1);
        var p1b = (short) (p2a - p1a);
        split(pulses0, off, psRangeDec, p1a, t0);
        split(pulses0, off + 2, psRangeDec, p1b, t0);
        var p1c = (short) decodeSplit(psRangeDec, p2b, t1);
        var p1d = (short) (p2b - p1c);
        split(pulses0, off + 4, psRangeDec, p1c, t0);
        split(pulses0, off + 6, psRangeDec, p1d, t0);
        var p2c = (short) decodeSplit(psRangeDec, p3b, t2);
        var p2d = (short) (p3b - p2c);
        var p1e = (short) decodeSplit(psRangeDec, p2c, t1);
        var p1f = (short) (p2c - p1e);
        split(pulses0, off + 8, psRangeDec, p1e, t0);
        split(pulses0, off + 10, psRangeDec, p1f, t0);
        var p1g = (short) decodeSplit(psRangeDec, p2d, t1);
        var p1h = (short) (p2d - p1g);
        split(pulses0, off + 12, psRangeDec, p1g, t0);
        split(pulses0, off + 14, psRangeDec, p1h, t0);
    }

    /**
     * Decodes the split of {@code p} pulses into two adjacent output amplitudes.
     *
     * @param pulses0    receives the two amplitudes
     * @param off        the index of the first amplitude
     * @param psRangeDec the range decoder
     * @param p          the pulse count of the parent
     * @param table      the table of split iCDFs of the lowest tree level
     */
    private static void split(short[] pulses0, int off, RangeDecoder psRangeDec, int p, short[] table) {
        var child1 = (short) decodeSplit(psRangeDec, p, table);
        pulses0[off] = child1;
        pulses0[off + 1] = (short) (p - child1);
    }
}
