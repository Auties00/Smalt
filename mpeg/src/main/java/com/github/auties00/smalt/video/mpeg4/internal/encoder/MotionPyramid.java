package com.github.auties00.smalt.video.mpeg4.internal.encoder;

import com.github.auties00.smalt.video.mpeg4.internal.Frame;

/**
 * Holds the luminance of a picture at half and quarter resolution, so that a motion search can find large
 * displacements with an exhaustive search over a small area before it refines them at full resolution.
 *
 * <p>Each level averages two by two samples of the level above, rounding half up, over the coded area of the picture
 * (a whole number of macroblocks). An instance is sized for one picture size at construction, rebuilt in place for
 * every picture, and read by any number of threads once built.
 */
public final class MotionPyramid {
    /**
     * The width of the half resolution level.
     */
    final int halfWidth;

    /**
     * The height of the half resolution level.
     */
    final int halfHeight;

    /**
     * The width of the quarter resolution level.
     */
    final int quarterWidth;

    /**
     * The height of the quarter resolution level.
     */
    final int quarterHeight;

    /**
     * The half resolution samples, row by row.
     */
    final byte[] half;

    /**
     * The quarter resolution samples, row by row.
     */
    final byte[] quarter;

    /**
     * Constructs a pyramid for a coded picture size.
     *
     * @param mbWidth the number of macroblock columns
     * @param mbHeight the number of macroblock rows
     */
    public MotionPyramid(int mbWidth, int mbHeight) {
        this.halfWidth = mbWidth * 8;
        this.halfHeight = mbHeight * 8;
        this.quarterWidth = mbWidth * 4;
        this.quarterHeight = mbHeight * 4;
        this.half = new byte[halfWidth * halfHeight];
        this.quarter = new byte[quarterWidth * quarterHeight];
    }

    /**
     * Rebuilds both levels from the luminance of a picture.
     *
     * @param frame the picture, of the size this pyramid was constructed for
     */
    public void build(Frame frame) {
        var plane = frame.y;
        var stride = frame.lumaStride;
        for (var row = 0; row < halfHeight; row++) {
            var top = frame.lumaOrigin + 2 * row * stride;
            var out = row * halfWidth;
            for (var col = 0; col < halfWidth; col++) {
                var at = top + 2 * col;
                half[out + col] = (byte) (((plane[at] & 0xFF) + (plane[at + 1] & 0xFF) + (plane[at + stride] & 0xFF)
                        + (plane[at + stride + 1] & 0xFF) + 2) >> 2);
            }
        }
        for (var row = 0; row < quarterHeight; row++) {
            var top = 2 * row * halfWidth;
            var out = row * quarterWidth;
            for (var col = 0; col < quarterWidth; col++) {
                var at = top + 2 * col;
                quarter[out + col] = (byte) (((half[at] & 0xFF) + (half[at + 1] & 0xFF) + (half[at + halfWidth] & 0xFF)
                        + (half[at + halfWidth + 1] & 0xFF) + 2) >> 2);
            }
        }
    }

    /**
     * Returns the sum of absolute differences of two square blocks of one level.
     *
     * @param a the first level's samples
     * @param aOffset the index of the first block's top-left sample
     * @param b the second level's samples
     * @param bOffset the index of the second block's top-left sample
     * @param stride the row stride of both levels
     * @param size the block size
     * @param limit the sum at which the computation may stop early
     * @return the sum, or a value of at least {@code limit} once it reaches {@code limit}
     */
    static int sad(byte[] a, int aOffset, byte[] b, int bOffset, int stride, int size, int limit) {
        var sum = 0;
        for (var row = 0; row < size && sum < limit; row++) {
            var i = aOffset + row * stride;
            var j = bOffset + row * stride;
            for (var col = 0; col < size; col++) {
                sum += Math.abs((a[i + col] & 0xFF) - (b[j + col] & 0xFF));
            }
        }
        return sum;
    }
}
