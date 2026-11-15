package com.github.auties00.smalt.video.vc1.internal;

/**
 * Holds one AC coding set of SMPTE ST 421 section 8.1.3.4: the code table, the run and level of each index, and the
 * delta tables of escape modes 1 and 2.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class AcCodingSet {
    /**
     * The code table of the run-level indices; its last index is the escape code.
     */
    final Vlc codes;

    /**
     * The index of the escape code.
     */
    final int escape;

    /**
     * The first index of the codes that end the block (LAST = 1).
     */
    final int startOfLast;

    /**
     * The run of each index.
     */
    final byte[] run;

    /**
     * The absolute level of each index.
     */
    final byte[] level;

    /**
     * The DeltaLevel of escape mode 1 for the codes with LAST = 0, indexed by run.
     */
    final byte[] deltaLevelNotLast;

    /**
     * The DeltaLevel of escape mode 1 for the codes with LAST = 1, indexed by run.
     */
    final byte[] deltaLevelLast;

    /**
     * The DeltaRun of escape mode 2 for the codes with LAST = 0, indexed by level - 1.
     */
    final byte[] deltaRunNotLast;

    /**
     * The DeltaRun of escape mode 2 for the codes with LAST = 1, indexed by level - 1.
     */
    final byte[] deltaRunLast;

    /**
     * Constructs a coding set.
     *
     * @param codes the packed codes of the indices, the escape last
     * @param runLevel the run (high byte) and level (low byte) of each index but the escape
     * @param startOfLast the first index whose code ends the block
     * @param deltaLevel the DeltaLevel table, the LAST = 0 entries then the LAST = 1 entries
     * @param deltaLevelSplit the number of LAST = 0 entries of {@code deltaLevel}
     * @param deltaRun the DeltaRun table, the LAST = 0 entries then the LAST = 1 entries
     * @param deltaRunSplit the number of LAST = 0 entries of {@code deltaRun}
     */
    AcCodingSet(int[] codes, int[] runLevel, int startOfLast, int[] deltaLevel, int deltaLevelSplit, int[] deltaRun,
                int deltaRunSplit) {
        this.codes = new Vlc(codes);
        this.escape = codes.length - 1;
        this.startOfLast = startOfLast;
        this.run = new byte[runLevel.length];
        this.level = new byte[runLevel.length];
        for (var i = 0; i < runLevel.length; i++) {
            run[i] = (byte) (runLevel[i] >>> 8);
            level[i] = (byte) runLevel[i];
        }
        this.deltaLevelNotLast = slice(deltaLevel, 0, deltaLevelSplit);
        this.deltaLevelLast = slice(deltaLevel, deltaLevelSplit, deltaLevel.length);
        this.deltaRunNotLast = slice(deltaRun, 0, deltaRunSplit);
        this.deltaRunLast = slice(deltaRun, deltaRunSplit, deltaRun.length);
    }

    /**
     * Copies a range of a table into bytes.
     *
     * @param values the table
     * @param from the first index
     * @param to the index after the last
     * @return the entries
     */
    private static byte[] slice(int[] values, int from, int to) {
        var out = new byte[to - from];
        for (var i = from; i < to; i++) {
            out[i - from] = (byte) values[i];
        }
        return out;
    }
}
