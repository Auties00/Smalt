/* Ports libxaac (decoder/ixheaacd_main.h, the state of ia_usac_lpd_decoder_struct), commit 2fbadd57.
   Copyright 2018 The Android Open Source Project; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import java.util.Arrays;

/**
 * Holds the state the linear prediction domain decoder of one channel carries between superframes.
 *
 * <p>Instances are not thread-safe.
 */
final class LpdState {
    static final int ORDER = 16;
    static final int MAX_PITCH = 411;
    static final int INTERPOLATION_ORDER = 16;
    static final int FILTER_DELAY = 12;
    static final int SUBFRAME = 64;
    static final int FAC_LENGTH = 128;
    static final int SYNTH_DELAY_MAX = 7 * SUBFRAME;

    final float[] lspOld = new float[ORDER];
    final float[] lsfPrevious = new float[ORDER];

    /**
     * The mode of the last coded division: 0 ACELP, 1 to 3 TCX, -1 after a long FD frame, -2 after short
     * FD windows.
     */
    int modePrevious = -1;

    final int[] pitchPrevious = new int[8];
    final float[] gainPrevious = new float[8];
    boolean bassPostFilterPrevious;
    float gainThreshold;

    /**
     * The LPC filters of the last two subframes of the previous division.
     */
    final float[] filterPrevious = new float[2 * (ORDER + 1)];

    /**
     * The tail of the last division: the unwindowed TCX overlap, or the ACELP synthesis end and zero input
     * response ({@code exc_prev}).
     */
    final float[] tail = new float[1 + 2 * FAC_LENGTH];

    final float[] excitationPrevious = new float[MAX_PITCH + INTERPOLATION_ORDER + 1];
    final float[] synthPrevious = new float[MAX_PITCH + SYNTH_DELAY_MAX];
    final float[] bassPrevious = new float[FILTER_DELAY + SUBFRAME];

    float facGain;
    final float[] facWeights = new float[FAC_LENGTH / 4];

    /**
     * The last LPC filter and the ACELP tail when the last division was ACELP, for the FAC of a following
     * frequency domain frame.
     */
    final float[] lastFilter = new float[ORDER + 1];
    final float[] acelpTail = new float[1 + 2 * FAC_LENGTH];

    LpdState() {
        Arrays.fill(pitchPrevious, 64);
        System.arraycopy(LpdTables.LSF_INIT, 0, lsfPrevious, 0, ORDER);
        for (var i = 0; i < ORDER; i++) {
            lspOld[i] = (float) StrictMath.cos(lsfPrevious[i] * Math.PI / 6400.0);
        }
    }
}
