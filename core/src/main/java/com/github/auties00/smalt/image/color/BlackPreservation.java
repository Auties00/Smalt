package com.github.auties00.smalt.image.color;

/**
 * Selects whether a {@link ColorTransform} from a CMYK profile to a CMYK output profile keeps the black ink of the
 * source, which the ICC rendering intents recompute from the color like the other inks.
 *
 * <p>Both preserving modes map the source black through the curve that keeps its lightness: the black-to-lightness
 * curve of the source joined with the inverse of the target's. They are combined with the perceptual, relative
 * colorimetric or saturation intent, which converts the colors they do not preserve, and apply only when the chain
 * starts with a CMYK profile and ends with a CMYK output profile whose black ink darkens monotonically (CMYK device
 * links after it apply unchanged); other chains convert with the rendering intent alone. With either mode, black
 * point compensation applies only when it is requested, also for version 4 profiles, whose perceptual and saturation
 * intents otherwise always apply it.
 */
public enum BlackPreservation {
    /**
     * Converts black like every other ink, with the rendering intent alone.
     */
    NONE,

    /**
     * Keeps the colors printed with black ink alone (cyan, magenta and yellow zero) on the black channel, mapped
     * through the lightness-preserving curve; every other color converts with the rendering intent. Text and black
     * line art stay single-ink.
     */
    K_ONLY,

    /**
     * Keeps the black channel of every color, mapped through the lightness-preserving curve, and searches the cyan,
     * magenta and yellow that reproduce the colorimetric conversion with that black, within the total ink limit
     * the target profile shows; colors whose black already matches, and colors the search cannot reach, convert with
     * the rendering intent. The most expensive mode to create.
     */
    K_PLANE
}
