package com.github.auties00.smalt.image.raw.internal;

/**
 * Interpolates a segment of an interior row of a Bayer image bilinearly.
 *
 * <p>The segment's photosites alternate between two colors, {@code a} at its first photosite and
 * {@code b} at the second; the rows above and below hold the other pairs. For a photosite whose own
 * color is {@code p}, the other colors are:
 * <ul>
 *   <li>at a green photosite, the color of its row: {@code H = (left + right) * 0.5}, and the
 *       color of the rows above and below: {@code V = (up + down) * 0.5};</li>
 *   <li>at a red or blue photosite, green: {@code P = (((up + left) + right) + down) * 0.25}, and
 *       the opposite color: {@code X = (((upLeft + upRight) + downLeft) + downRight) * 0.25}.</li>
 * </ul>
 * The arithmetic is single precision in exactly that order, so the scalar and Vector API
 * implementations return bit-identical values, equal to {@link PatternDemosaic}'s bilinear means.
 */
interface BayerKernel {
    /**
     * The color pair red then green.
     */
    int RED_GREEN = 0;

    /**
     * The color pair green then red.
     */
    int GREEN_RED = 1;

    /**
     * The color pair green then blue.
     */
    int GREEN_BLUE = 2;

    /**
     * The color pair blue then green.
     */
    int BLUE_GREEN = 3;

    /**
     * Interpolates a row segment.
     *
     * @param data the image values
     * @param up the index of the photosite above the segment's first one
     * @param mid the index of the segment's first photosite
     * @param down the index of the photosite below the segment's first one
     * @param count the number of photosites; the segment and its neighbors one column to each side
     * lie in the image
     * @param pair the colors of the segment's first two photosites, one of {@link #RED_GREEN},
     * {@link #GREEN_RED}, {@link #GREEN_BLUE} and {@link #BLUE_GREEN}
     * @param r receives the red values
     * @param g receives the green values
     * @param b receives the blue values
     * @param out the index of the first value written
     */
    void row(float[] data, int up, int mid, int down, int count, int pair, float[] r, float[] g, float[] b, int out);
}
