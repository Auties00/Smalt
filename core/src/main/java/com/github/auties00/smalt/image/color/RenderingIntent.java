package com.github.auties00.smalt.image.color;

/**
 * Selects how colors outside the target gamut, and the difference between the source and target
 * media, are handled by a {@link ColorTransform} (ICC.1:2022 section 6.2).
 *
 * <p>Matrix/TRC profiles implement only colorimetric conversions: for them the perceptual and
 * saturation intents behave like {@link #RELATIVE_COLORIMETRIC}. Lookup-table profiles may hold a
 * different table for each intent; when the requested one is missing the perceptual table is
 * used.
 */
public enum RenderingIntent {
    /**
     * Compresses the source gamut into the target gamut, preserving the overall appearance; the
     * usual choice for photographs.
     */
    PERCEPTUAL,

    /**
     * Reproduces in-gamut colors exactly relative to the media whites and clips out-of-gamut
     * colors; the media white maps to the target white.
     */
    RELATIVE_COLORIMETRIC,

    /**
     * Favors vivid colors over accuracy; meant for business graphics.
     */
    SATURATION,

    /**
     * Reproduces in-gamut colors exactly, without adapting the media white; used for proofing.
     */
    ABSOLUTE_COLORIMETRIC;

    /**
     * Returns the ICC code of the intent.
     *
     * @return {@code 0} to {@code 3}
     */
    int code() {
        return ordinal();
    }
}
