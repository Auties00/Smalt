package com.github.auties00.smalt.audio.amr;

/**
 * Names a voice activity detector of the AMR-NB encoder (3GPP TS 26.094), which decides the
 * frames that discontinuous transmission replaces by comfort noise.
 *
 * <p>Both detectors are normative alternatives: a decoder handles the output of either, and the
 * choice only changes which frames are coded as speech.
 */
public enum AmrVad {
    /**
     * VAD option 1: a nine-band filter bank with pitch, tone and complex-signal detectors.
     */
    OPTION_1,

    /**
     * VAD option 2: a 16-channel FFT analysis of each half frame with a voice metric, a
     * hangover driven by the signal-to-noise ratio and a long-term prediction gain detector.
     */
    OPTION_2
}
