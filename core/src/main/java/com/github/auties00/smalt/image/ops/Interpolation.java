package com.github.auties00.smalt.image.ops;

/**
 * Enumerates the interpolations that sample a picture at arbitrary positions, used by rotations
 * and affine transforms.
 *
 * <p>Unlike a {@link ResizeFilter}, an interpolation is never stretched: it reconstructs the
 * picture at one point from the pixels around it, so a transform that shrinks a picture by more
 * than about half aliases. Shrink with {@link ImageOps#resize} first, or after an expanding
 * transform.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum Interpolation {
    /**
     * Takes the pixel whose area contains the position.
     */
    NEAREST,

    /**
     * Interpolates linearly between the four pixels around the position.
     */
    BILINEAR,

    /**
     * Interpolates with the Catmull-Rom cubic (Keys' kernel with {@code a = -0.5}) over the
     * sixteen pixels around the position: sharper than {@link #BILINEAR}, with a small overshoot
     * at edges, which is clamped.
     */
    BICUBIC
}
