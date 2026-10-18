package com.github.auties00.smalt.image.ops;

/**
 * Enumerates the ways {@link ImageOps#findSmartCrop} places a crop of a fixed aspect ratio.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum SmartCropStrategy {
    /**
     * Centers the crop, ignoring the content.
     */
    CENTER,

    /**
     * Keeps the area with the most detail: the crop window shrinks from the full picture by
     * repeatedly removing a strip from whichever side has the lower Shannon entropy of luma.
     */
    ENTROPY,

    /**
     * Keeps the area the eye is drawn to: the crop position with the highest score of edge
     * strength, color saturation and skin tones, weighted towards the center and the lines of
     * the rule of thirds and away from the crop's edges, as smartcrop.js scores it.
     */
    ATTENTION
}
