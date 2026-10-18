package com.github.auties00.smalt.capture.audio;

import java.util.Objects;

/**
 * A format an audio device runs at without conversion: a sampling rate, a channel count and a
 * sample encoding.
 *
 * <p>Formats are listed by {@link AudioDevice#formats()}. A session may be opened at any rate and
 * channel count in {@link AudioShareMode#SHARED} mode, where the operating system or the session
 * converts; an {@link AudioShareMode#EXCLUSIVE} session opens only at one of the device's formats.
 * Instances are immutable and thread-safe.
 *
 * @param sampleRate the sampling rate in hertz; positive
 * @param channels the number of channels; positive
 * @param sampleFormat the encoding of one sample
 */
public record AudioDeviceFormat(int sampleRate, int channels, AudioSampleFormat sampleFormat) {
    /**
     * Validates the format.
     *
     * @throws IllegalArgumentException if {@code sampleRate} or {@code channels} is not positive
     */
    public AudioDeviceFormat {
        Objects.requireNonNull(sampleFormat, "sampleFormat");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("invalid sample rate " + sampleRate);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("invalid channel count " + channels);
        }
    }

    /**
     * {@return the size of one frame (one sample of every channel), in bytes}
     */
    public int frameBytes() {
        return channels * sampleFormat.bytes();
    }
}
