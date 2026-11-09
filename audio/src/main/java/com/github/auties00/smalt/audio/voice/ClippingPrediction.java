package com.github.auties00.smalt.audio.voice;

/**
 * Selects how the managed analog gain controller ({@link GainControl#MANAGED_ANALOG}) anticipates
 * clipping, which lowers the microphone volume before the input clips instead of after.
 *
 * <p>Every mode except {@link #OFF} compares the level of the latest frames
 * ({@link AnalogGainControllerOptions#predictionWindowFrames()}) with a reference window of older
 * frames.
 */
public enum ClippingPrediction {
    /**
     * Disables clipping prediction: the volume is lowered only after the input clipped.
     */
    OFF,

    /**
     * Predicts clipping when the latest frames peak above the threshold and their crest factor
     * fell by more than the margin below the reference window's; the volume is lowered by the
     * clipped level step.
     */
    CLIPPING_EVENT,

    /**
     * Projects the peak level of the latest frames from their mean level and the reference
     * window's crest factor, and lowers the volume by as much as the projected peak exceeds full
     * scale, at least by the clipped level step.
     */
    ADAPTIVE_STEP_PEAK,

    /**
     * Projects the peak level as {@link #ADAPTIVE_STEP_PEAK} does, and lowers the volume by the
     * clipped level step when the projected peak exceeds the threshold.
     */
    FIXED_STEP_PEAK
}
