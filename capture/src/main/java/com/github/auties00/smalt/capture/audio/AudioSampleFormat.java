package com.github.auties00.smalt.capture.audio;

/**
 * A sample encoding an audio device runs at.
 *
 * <p>Sessions always hand samples to the caller as floats in {@code [-1, 1]} or as signed 16-bit
 * integers, whatever the device's encoding; this enum only describes the device, in
 * {@link AudioDeviceFormat#sampleFormat()}.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a {@code default}
 * branch.
 */
public enum AudioSampleFormat {
    /**
     * Signed 16-bit little-endian integers.
     */
    PCM_S16(2),

    /**
     * Signed 24-bit little-endian integers packed in three bytes.
     */
    PCM_S24(3),

    /**
     * Signed 32-bit little-endian integers; also carries 24-bit samples left-justified in a 32-bit
     * container, which read the same way.
     */
    PCM_S32(4),

    /**
     * IEEE 754 32-bit little-endian floats, nominally in {@code [-1, 1]}.
     */
    FLOAT32(4);

    private final int bytes;

    AudioSampleFormat(int bytes) {
        this.bytes = bytes;
    }

    /**
     * {@return the size of one sample of one channel, in bytes}
     */
    public int bytes() {
        return bytes;
    }
}
