package com.github.auties00.smalt.image.jxs;

import java.util.Optional;

/**
 * Enumerates the JPEG XS profiles of ISO/IEC 21122-2, by the code {@code Ppih} the picture header carries.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a {@code default} branch.
 */
public enum JpegXsProfile {
    /**
     * Unrestricted, code {@code 0x0000}: no profile constraint.
     */
    UNRESTRICTED(0x0000),
    /**
     * Light 422.10, code {@code 0x1500}.
     */
    LIGHT_422_10(0x1500),
    /**
     * Light 444.12, code {@code 0x1A00}.
     */
    LIGHT_444_12(0x1A00),
    /**
     * Light-Subline 422.10, code {@code 0x2500}.
     */
    LIGHT_SUBLINE_422_10(0x2500),
    /**
     * Main 420.12, code {@code 0x3240}.
     */
    MAIN_420_12(0x3240),
    /**
     * Main 422.10, code {@code 0x3540}.
     */
    MAIN_422_10(0x3540),
    /**
     * Main 444.12, code {@code 0x3A40}.
     */
    MAIN_444_12(0x3A40),
    /**
     * Main 4444.12, code {@code 0x3E40}.
     */
    MAIN_4444_12(0x3E40),
    /**
     * High 420.12, code {@code 0x4240}.
     */
    HIGH_420_12(0x4240),
    /**
     * High 444.12, code {@code 0x4A40}.
     */
    HIGH_444_12(0x4A40),
    /**
     * High 4444.12, code {@code 0x4E40}.
     */
    HIGH_4444_12(0x4E40),
    /**
     * CHigh 444.12, code {@code 0x4A44}.
     */
    CHIGH_444_12(0x4A44),
    /**
     * TDC 444.12 (temporal differential coding), code {@code 0x4A45}.
     */
    TDC_444_12(0x4A45),
    /**
     * TDC MLS 444.12 (temporal differential coding, mathematically lossless), code {@code 0x6A45}.
     */
    TDC_MLS_444_12(0x6A45),
    /**
     * MLS.12 (mathematically lossless, up to 12 bits), code {@code 0x6EC0}.
     */
    MLS_12(0x6EC0),
    /**
     * MLS.16 (mathematically lossless, up to 16 bits), code {@code 0x6ED0}.
     */
    MLS_16(0x6ED0),
    /**
     * Light Bayer (color filter array data with the Star-Tetrix transform), code {@code 0x9300}.
     */
    LIGHT_BAYER(0x9300),
    /**
     * Main Bayer, code {@code 0xB340}.
     */
    MAIN_BAYER(0xB340),
    /**
     * High Bayer, code {@code 0xC340}.
     */
    HIGH_BAYER(0xC340);

    private final int code;

    JpegXsProfile(int code) {
        this.code = code;
    }

    /**
     * {@return the profile code {@code Ppih}}
     */
    public int code() {
        return code;
    }

    /**
     * Returns the profile of a code.
     *
     * @param code the profile code {@code Ppih}
     * @return the profile, or empty for a code this enum does not name
     */
    public static Optional<JpegXsProfile> of(int code) {
        for (var profile : values()) {
            if (profile.code == code) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }
}
