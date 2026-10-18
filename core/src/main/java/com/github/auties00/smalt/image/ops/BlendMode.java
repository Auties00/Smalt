package com.github.auties00.smalt.image.ops;

/**
 * Enumerates the separable blend modes of compositing, as the W3C Compositing and Blending
 * Level 1 specification defines them, plus additive and subtractive blending.
 *
 * <p>Each mode is a function {@code B(cb, cs)} of the backdrop color {@code cb} and the source
 * color {@code cs}, applied to each of red, green and blue normalized to {@code [0, 1]}. Where
 * the backdrop is transparent the source color shows unchanged, and the blended color is then
 * composited source-over (see {@link ImageCompositing#composite}).
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum BlendMode {
    /**
     * {@code B = cs}: the source covers the backdrop.
     */
    NORMAL,

    /**
     * {@code B = cb * cs}: darkens; black stays black and white is neutral.
     */
    MULTIPLY,

    /**
     * {@code B = cb + cs - cb * cs}: lightens; white stays white and black is neutral.
     */
    SCREEN,

    /**
     * {@link #HARD_LIGHT} with the operands swapped: multiplies the backdrop's darks and screens
     * its lights.
     */
    OVERLAY,

    /**
     * {@code B = min(cb, cs)}.
     */
    DARKEN,

    /**
     * {@code B = max(cb, cs)}.
     */
    LIGHTEN,

    /**
     * Brightens the backdrop towards the source: {@code 0} where {@code cb = 0}, {@code 1} where
     * {@code cs = 1}, otherwise {@code min(1, cb / (1 - cs))}.
     */
    COLOR_DODGE,

    /**
     * Darkens the backdrop towards the source: {@code 1} where {@code cb = 1}, {@code 0} where
     * {@code cs = 0}, otherwise {@code 1 - min(1, (1 - cb) / cs)}.
     */
    COLOR_BURN,

    /**
     * Multiplies where the source is dark ({@code cs <= 0.5}: {@code cb * 2cs}) and screens where
     * it is light ({@code SCREEN(cb, 2cs - 1)}).
     */
    HARD_LIGHT,

    /**
     * Darkens or lightens softly, depending on the source, with the W3C formula (which uses a
     * square root on the light side).
     */
    SOFT_LIGHT,

    /**
     * {@code B = |cb - cs|}.
     */
    DIFFERENCE,

    /**
     * {@code B = cb + cs - 2 * cb * cs}: like {@link #DIFFERENCE} with less contrast.
     */
    EXCLUSION,

    /**
     * {@code B = min(1, cb + cs)}, also known as linear dodge.
     */
    ADD,

    /**
     * {@code B = max(0, cb - cs)}.
     */
    SUBTRACT
}
