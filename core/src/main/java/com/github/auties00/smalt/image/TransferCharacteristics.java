package com.github.auties00.smalt.image;

/**
 * Enumerates the opto-electronic transfer characteristics of a picture, with the code points of
 * ITU-T H.273 (the {@code transfer_characteristics} field of H.264, H.265, VP9 and AV1 streams).
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum TransferCharacteristics {
    /**
     * ITU-R BT.709 (code point 1).
     */
    BT709(1),

    /**
     * Not specified by the stream (code point 2).
     */
    UNSPECIFIED(2),

    /**
     * Assumed display gamma 2.2 (code point 4).
     */
    GAMMA22(4),

    /**
     * Assumed display gamma 2.8 (code point 5).
     */
    GAMMA28(5),

    /**
     * SMPTE 170M, the BT.601 transfer (code point 6).
     */
    SMPTE170M(6),

    /**
     * SMPTE 240M (code point 7).
     */
    SMPTE240M(7),

    /**
     * Linear light (code point 8).
     */
    LINEAR(8),

    /**
     * Logarithmic, 100:1 range (code point 9).
     */
    LOG100(9),

    /**
     * Logarithmic, 316.22777:1 range (code point 10).
     */
    LOG316(10),

    /**
     * IEC 61966-2-4 xvYCC (code point 11).
     */
    IEC61966_2_4(11),

    /**
     * ITU-R BT.1361 extended color gamut (code point 12).
     */
    BT1361(12),

    /**
     * IEC 61966-2-1 sRGB (code point 13).
     */
    SRGB(13),

    /**
     * ITU-R BT.2020 10-bit (code point 14).
     */
    BT2020_10(14),

    /**
     * ITU-R BT.2020 12-bit (code point 15).
     */
    BT2020_12(15),

    /**
     * SMPTE ST 2084 perceptual quantizer, the HDR10 and Dolby Vision transfer (code point 16).
     */
    PQ(16),

    /**
     * SMPTE ST 428-1 (code point 17).
     */
    SMPTE428(17),

    /**
     * ARIB STD-B67 hybrid log-gamma (code point 18).
     */
    HLG(18);

    /**
     * The H.273 code point.
     */
    private final int code;

    /**
     * Constructs a constant.
     *
     * @param code the H.273 code point
     */
    TransferCharacteristics(int code) {
        this.code = code;
    }

    /**
     * Returns the H.273 code point.
     *
     * @return the code point
     */
    public int code() {
        return code;
    }

    /**
     * Returns whether this transfer encodes high-dynamic-range content.
     *
     * @return {@code true} for {@link #PQ} and {@link #HLG}
     */
    public boolean isHdr() {
        return this == PQ || this == HLG;
    }

    /**
     * Returns the constant for an H.273 code point.
     *
     * @param code the code point read from a stream
     * @return the matching constant, or {@link #UNSPECIFIED} for reserved or unknown values
     */
    public static TransferCharacteristics of(int code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return UNSPECIFIED;
    }
}
