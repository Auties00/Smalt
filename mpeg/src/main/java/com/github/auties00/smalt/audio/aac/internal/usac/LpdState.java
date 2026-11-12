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

    /**
     * The division modes of the last received frame, which a lost frame continues.
     */
    final int[] receivedMod = new int[4];

    /**
     * The ACELP mean energy indices of the last received frame.
     */
    final int[] receivedMeanEnergy = new int[4];

    /**
     * The long term prediction filter flags of the last received frame.
     */
    final int[] receivedLtpFilter = new int[16];

    /**
     * Whether the last received frame enabled the bass postfilter.
     */
    boolean receivedBassPostFilter;

    /**
     * Whether the last received frame was the first of a run of linear prediction domain frames.
     */
    boolean receivedFirstLpd;

    /**
     * The fourth LSF vector of the last received frame, the start of the extrapolation over a loss.
     */
    final float[] receivedLsf4 = new float[ORDER];

    /**
     * The mean of the newest LSF vectors of the last received frame, which the extrapolation moves toward.
     */
    final float[] adaptiveMean = new float[ORDER];

    /**
     * The last LSF vector of the current extrapolation.
     */
    final float[] extrapolatedLsf4 = new float[ORDER];

    /**
     * The LPC stability factor of the last division, 0 to 1.
     */
    float stabilityPrevious;

    /**
     * The number of divisions concealed since the last received frame.
     */
    int lostDivisions;

    /**
     * The integer pitch lag of the last ACELP subframe, in samples.
     */
    int pitchLagPrevious;

    /**
     * The fractional pitch lag of the last ACELP subframe, in quarter samples.
     */
    int pitchFractionPrevious;

    /**
     * The adaptive codebook gain a lost ACELP subframe starts from, decaying per subframe.
     */
    float pastPitchGain;

    /**
     * The innovation gain a lost ACELP subframe starts from, before the innovation energy normalization.
     */
    float pastCodeGain;

    /**
     * The state of the random innovation of lost ACELP subframes.
     */
    short innovationSeed;

    /**
     * The shaped spectrum of the last received TCX block, which a lost long TCX block repeats.
     */
    final float[] tcxSpectrum = new float[1024];

    /**
     * The global gain of the last received TCX block.
     */
    float tcxGain;

    /**
     * The pitch lag estimated from the last received TCX spectrum, in samples, at most 256.
     */
    int tcxPitch;

    /**
     * The LPC filter of the third division of the last received TCX frame, which the TCX excitation extrapolation
     * analyzes the past synthesis with.
     */
    final float[] concealmentFilter = new float[ORDER + 1];

    LpdState() {
        Arrays.fill(pitchPrevious, 64);
        System.arraycopy(LpdTables.LSF_INIT, 0, lsfPrevious, 0, ORDER);
        for (var i = 0; i < ORDER; i++) {
            lspOld[i] = (float) StrictMath.cos(lsfPrevious[i] * Math.PI / 6400.0);
        }
    }
}
