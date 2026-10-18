package com.github.auties00.smalt.image.ops;

/**
 * Enumerates the weightings {@link ImageAdjustments#grayscale} reduces a color to one intensity
 * with.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum GrayscaleMode {
    /**
     * The BT.601 luma of the gamma-encoded samples, {@code 0.299 R' + 0.587 G' + 0.114 B'},
     * computed in 16-bit fixed point as {@code (19595 R' + 38470 G' + 7471 B' + 32768) >> 16},
     * which is how Pillow converts to {@code L} and how JPEG defines luma.
     */
    BT601,

    /**
     * The BT.709 luma of the gamma-encoded samples, {@code 0.2126 R' + 0.7152 G' + 0.0722 B'},
     * computed as {@code (13933 R' + 46871 G' + 4732 B' + 32768) >> 16}.
     */
    BT709,

    /**
     * The relative luminance: the BT.709 (sRGB) weights applied to the linear-light values of the
     * samples, then encoded back with the sRGB transfer function. This is the perceptually
     * faithful gray of a color, and the slowest mode.
     */
    LUMINANCE
}
