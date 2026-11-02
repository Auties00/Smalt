package com.github.auties00.smalt.image.jpeg;

/**
 * Enumerates how {@link JpegTransforms} fills the blocks it makes up: the area a crop extension adds
 * around the image ({@link JpegTransformOptions#crop()}) and the area a wipe clears
 * ({@link JpegTransformOptions#wipe()}), the choices of {@code jpegtran}'s {@code f} and {@code r} flags.
 *
 * <p>Every fill works in the coefficient domain, on whole blocks.
 */
public enum JpegFill {
    /**
     * Fills with neutral gray: every coefficient of the filled blocks is zero.
     */
    GRAY,

    /**
     * Fills each block with a flat color and no detail: only its DC coefficient is set. A crop
     * extension repeats the DC coefficient of the nearest image block of the same block row (the
     * area it adds above or below the image stays gray); a wipe takes the average of the DC
     * coefficients of the blocks left and right of the wiped area in each block row, or of the one
     * that exists.
     */
    FLAT,

    /**
     * Fills with repeated mirror images of the adjacent image area, block by block, so edges and
     * texture continue. A crop extension reflects the image into the area it adds left and right of
     * it (the area above or below stays gray, as does all of it when the image is narrower than one
     * MCU). A wipe reflects only when the wiped area, rounded to
     * MCU boundaries, spans the full image height and touches its left or right edge, so the rest of
     * the image lies beside it; any other wiped area is filled with {@link #GRAY}.
     */
    REFLECT
}
