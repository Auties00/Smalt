package com.github.auties00.smalt.capture.audio;

/**
 * Whether a stream shares its device with other applications.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a {@code default}
 * branch.
 */
public enum AudioShareMode {
    /**
     * Shares the device through the operating system's mixer, which converts the stream's rate,
     * channel count and sample encoding to the device's. Every backend supports it; the mixer adds
     * latency.
     */
    SHARED,

    /**
     * Takes the device for this stream alone, at one of its {@linkplain AudioDevice#formats()
     * formats}, bypassing the mixer for the lowest latency. Windows supports it (WASAPI exclusive
     * mode, which the user may forbid per device); on Linux it opens the ALSA device without
     * software resampling; macOS does not support it.
     */
    EXCLUSIVE
}
