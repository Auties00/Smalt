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
     * software resampling; on macOS the process takes the device's hog mode, which keeps other
     * processes off the device until the session closes, sets the device to the stream's rate and
     * restores that rate afterwards. A macOS device has one owner, so its input and output streams
     * may both be exclusive in one process only at one rate, and a shared capture the process already
     * has on the device stops delivering while the device runs at another rate than it opened at.
     */
    EXCLUSIVE
}
