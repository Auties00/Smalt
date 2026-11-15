package com.github.auties00.smalt.audio.celp.internal;

import java.util.Arrays;

/**
 * The fields of one frame of a CELP audio object, its base layer and its enhancement layers (ISO/IEC 14496-3
 * subclauses 3.3 and 3.4), as the readers fill them.
 *
 * <p>Instances are not thread-safe.
 */
final class CelpFrame {
    /**
     * The largest number of subframes of a frame.
     */
    static final int MAX_SUBFRAMES = 10;

    /**
     * {@code TX_flag}: 1 for an active frame, 0, 2 or 3 for the non-active frames of silence compression.
     */
    int txFlag;

    /**
     * {@code interpolation_flag}.
     */
    boolean interpolation;

    /**
     * {@code LPC_Present}.
     */
    boolean lpcPresent;

    /**
     * {@code lpc_indices}, 0 to 4 or 0 to 9 of the core, 5 to 10 of the bandwidth extension tool; for a SID frame
     * the indices {@code SID_lpc_indices} map to.
     */
    final int[] lpc = new int[11];

    /**
     * {@code signal_mode}.
     */
    int signalMode;

    /**
     * {@code rms_index}, or {@code SID_rms_index} of a SID frame.
     */
    int rms;

    /**
     * {@code shape_delay} of each subframe.
     */
    final int[] delay = new int[MAX_SUBFRAMES];

    /**
     * {@code shape_positions}, or {@code shape_index} with regular pulse excitation, of each subframe.
     */
    final long[] positions = new long[MAX_SUBFRAMES];

    /**
     * {@code shape_signs} of each subframe.
     */
    final int[] signs = new int[MAX_SUBFRAMES];

    /**
     * {@code gain_index}, or {@code gain_indices[0]} with regular pulse excitation, of each subframe.
     */
    final int[] gain = new int[MAX_SUBFRAMES];

    /**
     * {@code gain_indices[1]} of each subframe with regular pulse excitation.
     */
    final int[] fixedGain = new int[MAX_SUBFRAMES];

    /**
     * {@code shape_enh_positions} of each enhancement layer and subframe.
     */
    final int[][] enhancementPositions = new int[3][MAX_SUBFRAMES];

    /**
     * {@code shape_enh_signs} of each enhancement layer and subframe.
     */
    final int[][] enhancementSigns = new int[3][MAX_SUBFRAMES];

    /**
     * {@code gain_enh_index} of each enhancement layer and subframe.
     */
    final int[][] enhancementGain = new int[3][MAX_SUBFRAMES];

    /**
     * {@code shape_bws_delay} of each subframe of the bandwidth extension tool.
     */
    final int[] bandwidthDelay = new int[MAX_SUBFRAMES];

    /**
     * {@code shape_bws_positions} of each subframe of the bandwidth extension tool.
     */
    final long[] bandwidthPositions = new long[MAX_SUBFRAMES];

    /**
     * {@code shape_bws_signs} of each subframe of the bandwidth extension tool.
     */
    final int[] bandwidthSigns = new int[MAX_SUBFRAMES];

    /**
     * {@code gain_bws_index} of each subframe of the bandwidth extension tool.
     */
    final int[] bandwidthGain = new int[MAX_SUBFRAMES];

    /**
     * Clears every field before a frame is read, so that fields assembled bit by bit start at zero.
     */
    void clear() {
        txFlag = 1;
        interpolation = false;
        lpcPresent = true;
        Arrays.fill(lpc, 0);
        signalMode = 0;
        rms = 0;
        Arrays.fill(delay, 0);
        Arrays.fill(positions, 0);
        Arrays.fill(signs, 0);
        Arrays.fill(gain, 0);
        Arrays.fill(fixedGain, 0);
        for (var layer = 0; layer < 3; layer++) {
            Arrays.fill(enhancementPositions[layer], 0);
            Arrays.fill(enhancementSigns[layer], 0);
            Arrays.fill(enhancementGain[layer], 0);
        }
        Arrays.fill(bandwidthDelay, 0);
        Arrays.fill(bandwidthPositions, 0);
        Arrays.fill(bandwidthSigns, 0);
        Arrays.fill(bandwidthGain, 0);
    }
}
