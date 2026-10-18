package com.github.auties00.smalt.video.vp8.internal;

/**
 * Holds the state the real-time encoder keeps for one macroblock row of the frame being coded:
 * its coefficient tokens, its left entropy contexts, and the adaptive mode search thresholds.
 *
 * <p>Every row starts from the thresholds the encoder carried over from the previous frame and
 * adapts them along the row only, so the decisions of a row never depend on another row's: the
 * stream is the same whichever thread codes which row, and in whatever order.
 */
final class RtRow {
    /**
     * The coefficient tokens of the row, in coding order.
     */
    final TokenBuffer tokens;

    /**
     * The nonzero flags of the blocks along the left edge of the next macroblock: four luma
     * rows, two U rows, two V rows, and the Y2 block.
     */
    final byte[] left = new byte[9];

    /**
     * The multiplier, in 1/128, of each mode's baseline threshold.
     */
    final int[] rdThreshMult = new int[RtTables.MAX_MODES];

    /**
     * The score a macroblock's best mode must exceed before each mode is tried.
     */
    final int[] rdThreshes = new int[RtTables.MAX_MODES];

    /**
     * The number of times each mode was tried in this row.
     */
    final int[] modeTestHitCounts = new int[RtTables.MAX_MODES];

    /**
     * The number of macroblocks of this row the inter mode search has run on.
     */
    int mbsTested;

    /**
     * The number of macroblocks of this row biased away from zero motion against dot artifacts.
     */
    int dotSuppressed;

    /**
     * Creates the state of one row.
     *
     * @param mbCols the number of macroblocks per row
     */
    RtRow(int mbCols) {
        tokens = new TokenBuffer(mbCols * 64);
    }

    /**
     * Prepares the row for a new frame.
     *
     * @param threshMult the thresholds' multipliers carried over from the previous frame
     * @param baseline   the baseline thresholds of this frame
     */
    void reset(int[] threshMult, int[] baseline) {
        tokens.clear();
        java.util.Arrays.fill(left, (byte) 0);
        System.arraycopy(threshMult, 0, rdThreshMult, 0, RtTables.MAX_MODES);
        System.arraycopy(baseline, 0, rdThreshes, 0, RtTables.MAX_MODES);
        java.util.Arrays.fill(modeTestHitCounts, 0);
        mbsTested = 0;
        dotSuppressed = 0;
    }
}
