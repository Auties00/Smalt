package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.opus.internal.OpusDefines;

/**
 * An Opus audio bandwidth (RFC 6716 section 2); the constants run from the narrowest to the widest.
 *
 * <p>Every Opus packet codes one of these bandwidths ({@link OpusPackets#bandwidth(byte[], int)});
 * an {@link OpusEncoder} can be limited to a widest bandwidth or forced to one.
 */
public enum OpusBandwidth {
    /**
     * 4 kHz passband, coded at 8 kHz.
     */
    NARROWBAND(OpusDefines.OPUS_BANDWIDTH_NARROWBAND),

    /**
     * 6 kHz passband, coded at 12 kHz.
     */
    MEDIUMBAND(OpusDefines.OPUS_BANDWIDTH_MEDIUMBAND),

    /**
     * 8 kHz passband, coded at 16 kHz.
     */
    WIDEBAND(OpusDefines.OPUS_BANDWIDTH_WIDEBAND),

    /**
     * 12 kHz passband, coded at 24 kHz.
     */
    SUPERWIDEBAND(OpusDefines.OPUS_BANDWIDTH_SUPERWIDEBAND),

    /**
     * 20 kHz passband, coded at 48 kHz.
     */
    FULLBAND(OpusDefines.OPUS_BANDWIDTH_FULLBAND);

    private final int code;

    OpusBandwidth(int code) {
        this.code = code;
    }

    /**
     * {@return the {@code OPUS_BANDWIDTH_*} code of this bandwidth}
     */
    int code() {
        return code;
    }

    /**
     * Returns the bandwidth of an {@code OPUS_BANDWIDTH_*} code.
     *
     * @param code an {@code OPUS_BANDWIDTH_*} code
     * @return the bandwidth; codes above {@link #SUPERWIDEBAND} map to {@link #FULLBAND}
     */
    static OpusBandwidth ofCode(int code) {
        return switch (code) {
            case OpusDefines.OPUS_BANDWIDTH_NARROWBAND -> NARROWBAND;
            case OpusDefines.OPUS_BANDWIDTH_MEDIUMBAND -> MEDIUMBAND;
            case OpusDefines.OPUS_BANDWIDTH_WIDEBAND -> WIDEBAND;
            case OpusDefines.OPUS_BANDWIDTH_SUPERWIDEBAND -> SUPERWIDEBAND;
            default -> FULLBAND;
        };
    }
}
