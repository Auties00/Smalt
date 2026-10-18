package com.github.auties00.smalt.video.dnxhd.internal;

/**
 * Holds the code lengths and the packed codewords of the VC-3 variable-length codes of one code
 * set and bit depth, indexed by quantized level, so that measuring and writing a coefficient take
 * one table lookup instead of the symbol assembly of ST 2019-1 clause 7.3.
 *
 * <p>A coefficient with amplitude {@code a} and a run of zeros before it codes, in order: the
 * amplitude codeword (with the run flag when the run is not zero), the sign bit, the index bits of
 * a large amplitude, and the run codeword when the run is not zero. Entries are indexed by
 * {@code 2 * a + (run == 0 ? 0 : 1)}.
 *
 * <p>Instances are immutable and shared.
 */
final class Vc3Costs {
    /**
     * The tables by code set and depth: index {@code 2 * set + (depth == 8 ? 0 : 1)}.
     */
    private static final Vc3Costs[] CACHE = new Vc3Costs[12];

    /**
     * The number of bits of a coefficient except its run codeword, by {@code 2 * a + runFlag}.
     */
    final int[] bits;

    /**
     * The amplitude codeword, the sign bit clear, and the index bits packed in coding order, by
     * {@code 2 * a + runFlag}; its length is {@link #bits}.
     */
    final int[] words;

    /**
     * The sign bit of a negative coefficient within {@link #words}, by amplitude.
     */
    final int[] signs;

    /**
     * The run codeword length by run, {@code 0} for a run of zero.
     */
    final int[] runBits;

    /**
     * The run codeword by run.
     */
    final int[] runCodes;

    /**
     * The end-of-block codeword.
     */
    final int endCode;

    /**
     * The end-of-block codeword length.
     */
    final int endBits;

    /**
     * The DC size codeword by size.
     */
    final int[] dcCodes;

    /**
     * The DC size codeword length by size.
     */
    final int[] dcBits;

    /**
     * Whether an amplitude word and a run codeword always fit one 32-bit write.
     */
    final boolean combinable;

    /**
     * Builds the tables of a set.
     *
     * @param set   the code set, from 0 to 5
     * @param depth the sample depth, 8 or more
     */
    private Vc3Costs(int set, int depth) {
        var amplitudes = Vc3Tables.amplitude(set);
        var runs = Vc3Tables.run(set);
        var dcs = Vc3Tables.dc(set);
        var indexBits = depth == 8 ? 4 : 6;
        var maxLevel = depth == 8 ? 1024 : 4096;
        bits = new int[2 * (maxLevel + 1)];
        words = new int[2 * (maxLevel + 1)];
        signs = new int[maxLevel + 1];
        runBits = new int[64];
        runCodes = new int[64];
        var maxWord = 0;
        for (var amplitude = 1; amplitude <= maxLevel; amplitude++) {
            var a = amplitude;
            var symbol = 0;
            var extra = -1;
            if (a > 64) {
                extra = (a - 1) >>> 6;
                a -= extra * 64;
                symbol |= Vc3Tables.INDEX_FLAG;
            }
            symbol |= a;
            var extraBits = extra >= 0 ? indexBits : 0;
            signs[amplitude] = 1 << extraBits;
            for (var flag = 0; flag < 2; flag++) {
                var s = flag == 0 ? symbol : symbol | Vc3Tables.RUN_FLAG;
                var length = amplitudes.codeLength(s);
                bits[2 * amplitude + flag] = length + 1 + extraBits;
                var code = length == 0 ? 0 : amplitudes.code(s);
                words[2 * amplitude + flag] = code << (1 + extraBits) | Math.max(extra, 0);
                maxWord = Math.max(maxWord, length + 1 + extraBits);
            }
        }
        var maxRun = 0;
        for (var run = 1; run < 64; run++) {
            runBits[run] = runs.codeLength(run);
            runCodes[run] = runBits[run] == 0 ? 0 : runs.code(run);
            maxRun = Math.max(maxRun, runBits[run]);
        }
        combinable = maxWord + maxRun <= 32;
        endCode = amplitudes.code(Vc3Tables.END_OF_BLOCK);
        endBits = amplitudes.codeLength(Vc3Tables.END_OF_BLOCK);
        dcCodes = new int[16];
        dcBits = new int[16];
        for (var size = 0; size < 16; size++) {
            dcBits[size] = dcs.codeLength(size);
            dcCodes[size] = dcBits[size] == 0 ? 0 : dcs.code(size);
        }
    }

    /**
     * Returns the tables of a code set and depth.
     *
     * @param set   the code set, from 0 to 5
     * @param depth the sample depth
     * @return the shared tables
     */
    static Vc3Costs of(int set, int depth) {
        var slot = 2 * set + (depth == 8 ? 0 : 1);
        var costs = CACHE[slot];
        if (costs == null) {
            costs = new Vc3Costs(set, depth);
            CACHE[slot] = costs;
        }
        return costs;
    }
}
