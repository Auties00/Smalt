package com.github.auties00.smalt.audio.amr.internal.nb;

/**
 * A voice activity detector of the AMR-NB encoder, together with the hooks through which the
 * open-loop pitch search feeds it.
 *
 * <p>The encoder calls {@link #vad(short[], int, Flag)} once per frame before the LP analysis,
 * then, for the same frame, {@link #openLoopStart()}, the open-loop search (which calls the
 * {@code tone*}, {@code complex*} and {@link #pitchSelected(int, int, Flag)} hooks) and
 * {@link #openLoopEnd(int, short[], Flag)}. Each implementation ignores the hooks its algorithm
 * does not use, so the arithmetic and the saturation flag follow the reference encoder built
 * with that detector.
 */
sealed interface VoiceActivityDetector permits Vad1, Vad2 {
    /**
     * Resets the detector.
     */
    void reset();

    /**
     * Decides whether a frame holds speech.
     *
     * @param in the speech buffer, with the 40 samples before the frame available
     * @param inOffset the first sample of the frame
     * @param overflow the saturation flag
     * @return 1 for speech, 0 otherwise
     */
    short vad(short[] in, int inOffset, Flag overflow);

    /**
     * Prepares the detector for the open-loop search of the frame.
     */
    void openLoopStart();

    /**
     * Shifts the tone flags at the start of an open-loop search.
     *
     * @param oneLagPerFrame whether the mode searches one lag per frame
     * @param overflow the saturation flag
     */
    void toneDetectionUpdate(boolean oneLagPerFrame, Flag overflow);

    /**
     * Inspects the maximum correlation and the energy of one lag range of the open-loop search.
     *
     * @param t0 the maximum correlation
     * @param t1 the energy
     * @param overflow the saturation flag
     */
    void toneDetection(int t0, int t1, Flag overflow);

    /**
     * {@return whether the detector needs the high-pass filtered correlation maximum of
     * {@link #complexDetectionUpdate(short)}}
     */
    boolean usesComplexDetection();

    /**
     * Records the high-pass filtered correlation maximum of the open-loop pitch search.
     *
     * @param bestCorrHp the correlation, Q15
     */
    void complexDetectionUpdate(short bestCorrHp);

    /**
     * Records the correlation and the energy of the lag an open-loop search selected.
     *
     * @param rmax the maximum correlation of the selected lag
     * @param r0 the energy of the selected lag
     * @param overflow the saturation flag
     */
    void pitchSelected(int rmax, int r0, Flag overflow);

    /**
     * Completes the open-loop analysis of the frame.
     *
     * @param mode the codec mode
     * @param tOp the two open-loop lags
     * @param overflow the saturation flag
     */
    void openLoopEnd(int mode, short[] tOp, Flag overflow);
}
