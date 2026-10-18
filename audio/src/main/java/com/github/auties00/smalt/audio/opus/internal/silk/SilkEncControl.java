/* Ports libopus 1.5.2 (silk/control.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

/**
 * Carries the parameters the Opus layer passes to the SILK encoder and the status it reads back.
 *
 * @implNote This implementation keeps the libopus field names.
 */
public final class SilkEncControl {
    /**
     * The number of API channels, 1 or 2 (input).
     */
    public int nChannelsAPI;

    /**
     * The number of coded channels, 1 or 2 (input).
     */
    public int nChannelsInternal;

    /**
     * The input sampling rate in Hz: 8000, 12000, 16000, 24000, 32000, 44100 or 48000 (input).
     */
    public int API_sampleRate;

    /**
     * The maximum internal sampling rate in Hz: 8000, 12000 or 16000 (input).
     */
    public int maxInternalSampleRate;

    /**
     * The minimum internal sampling rate in Hz: 8000, 12000 or 16000 (input).
     */
    public int minInternalSampleRate;

    /**
     * The soft request for the internal sampling rate in Hz: 8000, 12000 or 16000 (input).
     */
    public int desiredInternalSampleRate;

    /**
     * The duration of a packet in milliseconds: 10, 20, 40 or 60 (input).
     */
    public int payloadSize_ms;

    /**
     * The bitrate during active speech in bits per second, limited internally (input).
     */
    public int bitRate;

    /**
     * The uplink packet loss in percent, 0 to 100 (input).
     */
    public int packetLossPercentage;

    /**
     * The complexity, 0 (lowest) to 10 (highest) (input).
     */
    public int complexity;

    /**
     * Whether in-band forward error correction is enabled (input).
     */
    public int useInBandFEC;

    /**
     * Whether in-band FEC is actually coded in the current packet (input).
     */
    public int LBRR_coded;

    /**
     * Whether discontinuous transmission is enabled (input).
     */
    public int useDTX;

    /**
     * Whether constant bitrate is used (input).
     */
    public int useCBR;

    /**
     * The maximum number of bits allowed for the frame (input).
     */
    public int maxBits;

    /**
     * Whether a smooth downmix to mono is performed (input).
     */
    public int toMono;

    /**
     * Whether the Opus encoder allows a bandwidth switch (input).
     */
    public int opusCanSwitch;

    /**
     * Whether frames are made as independent as possible (input).
     */
    public int reducedDependency;

    /**
     * The internal sampling rate used, in Hz: 8000, 12000 or 16000 (output).
     */
    public int internalSampleRate;

    /**
     * Whether bandwidth switching is allowed because of low voice activity (output).
     */
    public int allowBandwidthSwitch;

    /**
     * Whether SILK runs in wideband without the variable low-pass filter (output).
     */
    public int inWBmodeWithoutVariableLP;

    /**
     * The stereo width in Q14 (output).
     */
    public int stereoWidth_Q14;

    /**
     * Whether SILK is ready to switch bandwidth (output).
     */
    public int switchReady;

    /**
     * The SILK signal type (output).
     */
    public int signalType;

    /**
     * The SILK quantization offset type (output).
     */
    public int offset;

    /**
     * Creates zeroed controls.
     */
    public SilkEncControl() {
    }
}
