/* Ports libopus 1.5.2 (include/opus_defines.h, src/opus_private.h). Copyright Xiph.Org Foundation and Skype Limited;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal;

/**
 * Holds the constants of the Opus API: error codes, modes, bandwidths, applications, signal
 * types and frame-size selectors.
 */
public final class OpusDefines {
    /**
     * No error.
     */
    public static final int OPUS_OK = 0;

    /**
     * One or more invalid or out of range arguments.
     */
    public static final int OPUS_BAD_ARG = -1;

    /**
     * Not enough bytes allocated in the buffer.
     */
    public static final int OPUS_BUFFER_TOO_SMALL = -2;

    /**
     * An internal error was detected.
     */
    public static final int OPUS_INTERNAL_ERROR = -3;

    /**
     * The compressed data passed is corrupted.
     */
    public static final int OPUS_INVALID_PACKET = -4;

    /**
     * Invalid or unsupported request number.
     */
    public static final int OPUS_UNIMPLEMENTED = -5;

    /**
     * An encoder or decoder structure is invalid or already freed.
     */
    public static final int OPUS_INVALID_STATE = -6;

    /**
     * Memory allocation has failed.
     */
    public static final int OPUS_ALLOC_FAIL = -7;

    /**
     * The automatic or default value of a setting.
     */
    public static final int OPUS_AUTO = -1000;

    /**
     * The maximum bitrate the packet size allows.
     */
    public static final int OPUS_BITRATE_MAX = -1;

    /**
     * The application optimized for voice (the SILK-leaning mode decisions).
     */
    public static final int OPUS_APPLICATION_VOIP = 2048;

    /**
     * The application optimized for general audio.
     */
    public static final int OPUS_APPLICATION_AUDIO = 2049;

    /**
     * The application restricted to CELT for the lowest delay.
     */
    public static final int OPUS_APPLICATION_RESTRICTED_LOWDELAY = 2051;

    /**
     * The signal is voice.
     */
    public static final int OPUS_SIGNAL_VOICE = 3001;

    /**
     * The signal is music.
     */
    public static final int OPUS_SIGNAL_MUSIC = 3002;

    /**
     * The 4 kHz narrowband audio bandwidth.
     */
    public static final int OPUS_BANDWIDTH_NARROWBAND = 1101;

    /**
     * The 6 kHz mediumband audio bandwidth.
     */
    public static final int OPUS_BANDWIDTH_MEDIUMBAND = 1102;

    /**
     * The 8 kHz wideband audio bandwidth.
     */
    public static final int OPUS_BANDWIDTH_WIDEBAND = 1103;

    /**
     * The 12 kHz super-wideband audio bandwidth.
     */
    public static final int OPUS_BANDWIDTH_SUPERWIDEBAND = 1104;

    /**
     * The 20 kHz fullband audio bandwidth.
     */
    public static final int OPUS_BANDWIDTH_FULLBAND = 1105;

    /**
     * The SILK-only coding mode.
     */
    public static final int MODE_SILK_ONLY = 1000;

    /**
     * The hybrid SILK plus CELT coding mode.
     */
    public static final int MODE_HYBRID = 1001;

    /**
     * The CELT-only coding mode.
     */
    public static final int MODE_CELT_ONLY = 1002;

    private OpusDefines() {
        throw new AssertionError();
    }

    /**
     * Returns the libopus name of an error code, for diagnostics ({@code opus_strerror}).
     *
     * @param error the error code
     * @return a short description
     */
    public static String strerror(int error) {
        return switch (error) {
            case OPUS_OK -> "success";
            case OPUS_BAD_ARG -> "invalid argument";
            case OPUS_BUFFER_TOO_SMALL -> "buffer too small";
            case OPUS_INTERNAL_ERROR -> "internal error";
            case OPUS_INVALID_PACKET -> "corrupted stream";
            case OPUS_UNIMPLEMENTED -> "request not implemented";
            case OPUS_INVALID_STATE -> "invalid state";
            case OPUS_ALLOC_FAIL -> "memory allocation failed";
            default -> "unknown error " + error;
        };
    }
}
