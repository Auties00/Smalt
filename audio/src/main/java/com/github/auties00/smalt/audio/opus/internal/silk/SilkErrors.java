/* Ports libopus 1.5.2 (silk/errors.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

/**
 * Holds the error codes returned by the SILK encoder and decoder.
 *
 * @implNote This implementation keeps the libopus macro names.
 */
public final class SilkErrors {
    /**
     * No error.
     */
    public static final int SILK_NO_ERROR = 0;

    /**
     * The input length is not a multiple of 10 ms or exceeds the packet size.
     */
    public static final int SILK_ENC_INPUT_INVALID_NO_OF_SAMPLES = -101;

    /**
     * The sampling rate is not supported.
     */
    public static final int SILK_ENC_FS_NOT_SUPPORTED = -102;

    /**
     * The packet size is not supported.
     */
    public static final int SILK_ENC_PACKET_SIZE_NOT_SUPPORTED = -103;

    /**
     * The packet loss rate is invalid.
     */
    public static final int SILK_ENC_INVALID_LOSS_RATE = -105;

    /**
     * The complexity setting is invalid.
     */
    public static final int SILK_ENC_INVALID_COMPLEXITY_SETTING = -106;

    /**
     * The in-band FEC setting is invalid.
     */
    public static final int SILK_ENC_INVALID_INBAND_FEC_SETTING = -107;

    /**
     * The DTX setting is invalid.
     */
    public static final int SILK_ENC_INVALID_DTX_SETTING = -108;

    /**
     * The CBR setting is invalid.
     */
    public static final int SILK_ENC_INVALID_CBR_SETTING = -109;

    /**
     * The number of channels is invalid.
     */
    public static final int SILK_ENC_INVALID_NUMBER_OF_CHANNELS_ERROR = -111;

    /**
     * The output sampling rate is invalid.
     */
    public static final int SILK_DEC_INVALID_SAMPLING_FREQUENCY = -200;

    /**
     * The frame size is invalid.
     */
    public static final int SILK_DEC_INVALID_FRAME_SIZE = -203;

    private SilkErrors() {
        throw new AssertionError();
    }
}
