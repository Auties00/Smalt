package com.github.auties00.smalt.video.ffv1.internal;

/**
 * Supplies the samples of the picture being encoded to FFV1 slices.
 *
 * <p>Implementations must allow the lines of different slices to be read from different threads at once.
 */
public interface Ffv1SampleSource {
    /**
     * Reads part of a line of one Y'CbCr plane.
     *
     * @param plane the plane: 0 luma, 1 Cb, 2 Cr, 3 transparency
     * @param x the column of the first sample in the plane
     * @param y the row in the plane
     * @param samples receives the samples
     * @param offset the index of the first sample in {@code samples}
     * @param count the number of samples
     */
    void line(int plane, int x, int y, int[] samples, int offset, int count);

    /**
     * Reads part of a row of RGB pixels.
     *
     * @param x the column of the first pixel
     * @param y the row
     * @param red receives the red samples from index 0
     * @param green receives the green samples from index 0
     * @param blue receives the blue samples from index 0
     * @param alpha receives the transparency samples from index 0, or {@code null} when none are coded
     * @param count the number of pixels
     */
    void rgbLine(int x, int y, int[] red, int[] green, int[] blue, int[] alpha, int count);
}
