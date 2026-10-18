package com.github.auties00.smalt.image.ops;

/**
 * Enumerates the ways {@link ImageOps#extend} fills the border it adds around a picture.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum ExtendMode {
    /**
     * Fills the border with a background color.
     */
    BACKGROUND,

    /**
     * Repeats the edge pixels outwards.
     */
    REPLICATE,

    /**
     * Mirrors the picture at its edges, repeating the edge pixel ({@code ... c b a | a b c ...}),
     * and keeps mirroring for borders wider than the picture.
     */
    MIRROR,

    /**
     * Tiles the picture, so the border continues from the opposite edge.
     */
    REPEAT
}
