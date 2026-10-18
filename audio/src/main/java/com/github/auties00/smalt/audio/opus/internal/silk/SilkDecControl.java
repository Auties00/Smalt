/* Ports libopus 1.5.2 (silk/control.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

/**
 * Carries the parameters the Opus layer passes to {@link SilkDecoder#decode} and the status it
 * reads back.
 *
 * @implNote This implementation keeps the libopus field names and drops the deep PLC and OSCE fields.
 */
public final class SilkDecControl {
    /**
     * The number of output channels, 1 or 2 (input).
     */
    public int nChannelsAPI;

    /**
     * The number of coded channels, 1 or 2 (input).
     */
    public int nChannelsInternal;

    /**
     * The output sampling rate in Hz: 8000, 12000, 16000, 24000, 32000, 44100 or 48000 (input).
     */
    public int API_sampleRate;

    /**
     * The internal sampling rate in Hz: 8000, 12000 or 16000 (input).
     */
    public int internalSampleRate;

    /**
     * The duration of the packet in milliseconds: 10, 20, 40 or 60 (input).
     */
    public int payloadSize_ms;

    /**
     * The pitch lag of the previous frame in samples at 48 kHz, or zero when unvoiced (output).
     */
    public int prevPitchLag;

    /**
     * Creates zeroed controls.
     */
    public SilkDecControl() {
    }
}
