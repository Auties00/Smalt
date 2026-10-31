package com.github.auties00.smalt.video.ffv1.internal;

/**
 * Receives the decoded lines of FFV1 slices.
 *
 * <p>Implementations must accept the lines of different slices from different threads at once; the lines of one
 * slice arrive from one thread, top to bottom.
 */
public interface Ffv1SampleSink {
    /**
     * Receives a line of one Y'CbCr plane.
     *
     * @param plane the plane: 0 luma, 1 Cb, 2 Cr, 3 transparency
     * @param x the column of the first sample in the plane
     * @param y the row in the plane
     * @param samples the samples, valid only during the call
     * @param offset the index of the first sample
     * @param count the number of samples
     */
    void line(int plane, int x, int y, int[] samples, int offset, int count);

    /**
     * Receives a line of RGB samples.
     *
     * @param x the column of the first pixel
     * @param y the row
     * @param red the red samples from index 0, valid only during the call
     * @param green the green samples from index 0
     * @param blue the blue samples from index 0
     * @param alpha the transparency samples from index 2, or {@code null} without a transparency plane
     * @param count the number of pixels
     */
    void rgbLine(int x, int y, int[] red, int[] green, int[] blue, int[] alpha, int count);
}
