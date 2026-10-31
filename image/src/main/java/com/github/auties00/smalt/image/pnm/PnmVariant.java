package com.github.auties00.smalt.image.pnm;

/**
 * The image kinds of the Netpbm family and the portable float maps.
 */
public enum PnmVariant {
    /**
     * Portable bitmap: one bit per pixel, 1 being black; magic {@code P1} (plain) or {@code P4} (raw).
     */
    PBM,

    /**
     * Portable graymap: one gray sample per pixel of up to 16 bits; magic {@code P2} (plain) or {@code P5} (raw).
     */
    PGM,

    /**
     * Portable pixmap: red, green and blue samples of up to 16 bits; magic {@code P3} (plain) or {@code P6} (raw).
     */
    PPM,

    /**
     * Portable arbitrary map: any number of samples of up to 16 bits per pixel, named by a tuple type such as
     * {@code GRAYSCALE_ALPHA} or {@code RGB_ALPHA}; magic {@code P7}, raw only.
     */
    PAM,

    /**
     * Portable float map: one gray or three color 32-bit IEEE 754 samples per pixel, rows stored bottom to top;
     * magic {@code Pf} (gray) or {@code PF} (color), raw only.
     */
    PFM,

    /**
     * Portable half float map: as {@link #PFM} with 16-bit IEEE 754 samples; magic {@code Ph} (gray) or
     * {@code PH} (color), raw only.
     */
    PHM
}
