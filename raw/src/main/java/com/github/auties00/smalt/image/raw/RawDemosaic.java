package com.github.auties00.smalt.image.raw;

/**
 * An algorithm that reconstructs full-color pixels from the one color each photosite of a color
 * filter array records.
 *
 * <p>Both algorithms handle Bayer arrays with their own method and every other array (Fujifilm
 * X-Trans, for example) with a color-difference interpolation that adapts to the pattern; linear
 * raw and monochrome images need no demosaicing.
 */
public enum RawDemosaic {
    /**
     * Bilinear interpolation of each missing color from its nearest neighbors: the fastest,
     * with soft edges and color fringes on fine detail.
     */
    BILINEAR,

    /**
     * Directional filtering with a posteriori decision (Menon, Andriani and Calvagno, 2007),
     * followed by its refinement step: green is interpolated along the direction with the
     * smaller color-difference gradient, then red and blue from color differences. Sharper
     * than bilinear and largely free of zipper artifacts, at a few times its cost.
     */
    MENON
}
