package com.github.auties00.smalt.audio.voice;

/**
 * Selects the automatic gain controller of a {@link VoiceProcessor}, which brings the speech
 * level of the capture signal to a target level and keeps it there.
 *
 * <p>Every mode except {@link #OFF} applies a digital compressor with the target level
 * ({@link VoiceProcessorOptions#targetLevelDbfs()}), compression gain
 * ({@link VoiceProcessorOptions#compressionGainDb()}) and limiter
 * ({@link VoiceProcessorOptions#limiter()}) of the options; the modes differ in how they adapt
 * the input level.
 */
public enum GainControl {
    /**
     * Disables gain control.
     */
    OFF,

    /**
     * Adapts the level with a virtual microphone volume applied to the signal itself, then
     * compresses it: the mode for applications that cannot change the microphone volume.
     */
    ADAPTIVE_DIGITAL,

    /**
     * Applies only the fixed compressor, without level adaptation; the compression gain is then
     * the gain applied to low-level speech.
     */
    FIXED_DIGITAL,

    /**
     * Recommends a microphone volume, which the application applies to the device, and
     * compresses the signal. The application reports the volume in effect through
     * {@link VoiceProcessor#setAnalogLevel(int)}, at least once before the first capture frame, and
     * reads the recommendation back from {@link VoiceProcessor#recommendedAnalogLevel()}, both on
     * the scale {@code [0, 255]}.
     */
    ADAPTIVE_ANALOG
}
