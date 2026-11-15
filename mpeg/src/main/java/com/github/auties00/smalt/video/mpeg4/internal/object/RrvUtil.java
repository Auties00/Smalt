/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/rrv.cpp: the decoder side of the reduced resolution VOP tools). Copyright 1999-2000 Sharp Corporation,
 * Microsoft Corporation and the contributors named in the sources; ISO/IEC free license for products conforming to
 * ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

/**
 * The helpers of reduced resolution VOPs (7.6.10): the upsampling of 8x8 residual blocks, the scaling of motion
 * vectors and the block boundary filter of the reconstructed VOP.
 */
final class RrvUtil {
    private static final int RRV_BLOCK_SIZE = 16;

    private RrvUtil() {
    }

    /**
     * Copies a square block into a plane ({@code writeCubicRct}).
     */
    static void writeCubicRct(int size, int lineInterval, int[] src, int srcOff, int[] dst, int dstOff) {
        for (var y = 0; y < size; y++) {
            System.arraycopy(src, srcOff, dst, dstOff, size);
            srcOff += size;
            dstOff += lineInterval;
        }
    }

    /**
     * Upsamples a block by two in both directions with the 9-3-3-1 filter of 7.6.10.2 ({@code MeanUpSampling}).
     */
    static void meanUpSampling(int[] src, int[] dst, int width, int height) {
        for (var j = 0; j < height; j++) {
            for (var i = 0; i < width; i++) {
                var ix1 = Math.max(0, i - 1);
                var iy1 = Math.max(0, j - 1);
                var ix2 = Math.min(width - 1, i + 1);
                var iy2 = Math.min(height - 1, j + 1);
                var c = src[i + j * width];
                var top = j * 2 * width * 2;
                var bottom = (j * 2 + 1) * width * 2;
                if (i == 0 && j == 0) {
                    dst[i * 2 + top] = c;
                } else {
                    dst[i * 2 + top] = (9 * c + 3 * src[ix1 + j * width] + 3 * src[i + iy1 * width]
                            + src[ix1 + iy1 * width] + 8) / 16;
                }
                if (i == width - 1 && j == 0) {
                    dst[i * 2 + 1 + top] = c;
                } else {
                    dst[i * 2 + 1 + top] = (9 * c + 3 * src[ix2 + j * width] + 3 * src[i + iy1 * width]
                            + src[ix2 + iy1 * width] + 8) / 16;
                }
                if (i == 0 && j == height - 1) {
                    dst[i * 2 + bottom] = c;
                } else {
                    dst[i * 2 + bottom] = (9 * c + 3 * src[ix1 + j * width] + 3 * src[i + iy2 * width]
                            + src[ix1 + iy2 * width] + 8) / 16;
                }
                if (i == width - 1 && j == height - 1) {
                    dst[i * 2 + 1 + bottom] = c;
                } else {
                    dst[i * 2 + 1 + bottom] = (9 * c + 3 * src[ix2 + j * width] + 3 * src[i + iy2 * width]
                            + src[ix2 + iy2 * width] + 8) / 16;
                }
            }
        }
    }

    private static int nint(double x) {
        return x > 0 ? (int) (x + .5) : (int) (x - .5);
    }

    /**
     * Halves the motion vectors of a VOP ({@code MotionVectorScalingDown}).
     */
    static void scaleDown(MotionVector[] vectors, int count) {
        for (var i = 0; i < count; i++) {
            scale(vectors[i], false);
        }
    }

    /**
     * Doubles the motion vectors of a VOP ({@code MotionVectorScalingUp}).
     */
    static void scaleUp(MotionVector[] vectors, int count) {
        for (var i = 0; i < count; i++) {
            scale(vectors[i], true);
        }
    }

    private static void scale(MotionVector v, boolean up) {
        v.computeMV();
        var vx = v.mvx + v.halfX / 2.0;
        var vy = v.mvy + v.halfY / 2.0;
        vx = up ? scaleUp(vx) : scaleDown(vx);
        vy = up ? scaleUp(vy) : scaleDown(vy);
        v.mvx = nint(vx);
        v.halfX = nint((vx - v.mvx) * 2.0);
        v.mvy = nint(vy);
        v.halfY = nint((vy - v.mvy) * 2.0);
        v.computeTrueMV();
    }

    private static double scaleDown(double value) {
        if (value == 0.0) {
            return 0.0;
        }
        return value > 0.0 ? (value + 0.5) / 2.0 : (value - 0.5) / 2.0;
    }

    private static double scaleUp(double value) {
        if (value == 0.0) {
            return 0.0;
        }
        return value > 0.0 ? 2.0 * value - 0.5 : 2.0 * value + 0.5;
    }

    /**
     * Filters the macroblock and block boundaries of a reduced resolution VOP (7.6.10.3,
     * {@code filterCodedPictureForRRV}).
     *
     * @param modes the macroblock modes of the VOP
     * @param vpEdgeEnable whether boundaries between video packets are filtered
     * @param scratch three working planes, reallocated when smaller than the VOP
     */
    static void filterCodedPicture(int[] y, int offY, int[] u, int[] v, int offUV, int sizeX, int sizeY,
                                   int numMBx, int numMBy, int widthY, int widthUV, MbMode[] modes,
                                   boolean vpEdgeEnable, int[][] scratch) {
        if (scratch[0] == null || scratch[0].length < sizeX * sizeY) {
            scratch[0] = new int[sizeX * sizeY];
            scratch[1] = new int[sizeX * sizeY / 4];
            scratch[2] = new int[sizeX * sizeY / 4];
        }
        var dataY = scratch[0];
        var dataU = scratch[1];
        var dataV = scratch[2];
        for (var j = 0; j < sizeY; j++) {
            System.arraycopy(y, offY + j * widthY, dataY, j * sizeX, sizeX);
        }
        for (var j = 0; j < sizeY / 2; j++) {
            System.arraycopy(u, offUV + j * widthUV, dataU, j * sizeX / 2, sizeX / 2);
            System.arraycopy(v, offUV + j * widthUV, dataV, j * sizeX / 2, sizeX / 2);
        }
        for (var iy = 1; iy < numMBy * 2; iy++) {
            var cur = (iy / 2) * numMBx;
            for (var ix = 0; ix < numMBx * 2; ix += 2, cur++) {
                var pre = iy % 2 != 0 ? cur : cur - numMBx;
                if (filtered(modes, cur, pre, vpEdgeEnable)) {
                    filterHorizontalBorder(dataY, sizeX, ix, iy);
                    filterHorizontalBorder(dataY, sizeX, ix + 1, iy);
                }
            }
        }
        for (var iy = 0; iy < numMBy * 2; iy += 2) {
            var cur = (iy / 2) * numMBx;
            for (var ix = 1; ix < numMBx * 2; ix++) {
                var mb = cur + ix / 2;
                var pre = ix % 2 != 0 ? mb : mb - 1;
                if (filtered(modes, mb, pre, vpEdgeEnable)) {
                    filterVerticalBorder(dataY, sizeX, ix, iy);
                    filterVerticalBorder(dataY, sizeX, ix, iy + 1);
                }
            }
        }
        for (var iy = 1; iy < numMBy; iy++) {
            var cur = iy * numMBx;
            for (var ix = 0; ix < numMBx; ix++, cur++) {
                if (filtered(modes, cur, cur - numMBx, vpEdgeEnable)) {
                    filterHorizontalBorder(dataU, sizeX / 2, ix, iy);
                    filterHorizontalBorder(dataV, sizeX / 2, ix, iy);
                }
            }
        }
        for (var iy = 0; iy < numMBy; iy++) {
            var cur = iy * numMBx;
            for (var ix = 1; ix < numMBx; ix++) {
                cur++;
                if (filtered(modes, cur, cur - 1, vpEdgeEnable)) {
                    filterVerticalBorder(dataU, sizeX / 2, ix, iy);
                    filterVerticalBorder(dataV, sizeX / 2, ix, iy);
                }
            }
        }
        for (var j = 0; j < sizeY; j++) {
            System.arraycopy(dataY, j * sizeX, y, offY + j * widthY, sizeX);
        }
        for (var j = 0; j < sizeY / 2; j++) {
            System.arraycopy(dataU, j * sizeX / 2, u, offUV + j * widthUV, sizeX / 2);
            System.arraycopy(dataV, j * sizeX / 2, v, offUV + j * widthUV, sizeX / 2);
        }
    }

    private static boolean filtered(MbMode[] modes, int mb, int pre, boolean vpEdgeEnable) {
        return (!modes[mb].skip || !modes[pre].skip)
                && (modes[mb].videoPacketNumber == modes[pre].videoPacketNumber || vpEdgeEnable);
    }

    private static void filterVerticalBorder(int[] pic, int sizeX, int curX, int curY) {
        curX *= RRV_BLOCK_SIZE;
        curY *= RRV_BLOCK_SIZE;
        var p1 = (curX - 1) + curY * sizeX;
        var p2 = curX + curY * sizeX;
        for (var i = 0; i < RRV_BLOCK_SIZE; i++) {
            var v1 = pic[p1];
            var v2 = pic[p2];
            pic[p1] = (3 * v1 + v2 + 2) / 4;
            pic[p2] = (v1 + 3 * v2 + 2) / 4;
            p1 += sizeX;
            p2 += sizeX;
        }
    }

    private static void filterHorizontalBorder(int[] pic, int sizeX, int curX, int curY) {
        curX *= RRV_BLOCK_SIZE;
        curY *= RRV_BLOCK_SIZE;
        var p1 = curX + (curY - 1) * sizeX;
        var p2 = curX + curY * sizeX;
        for (var i = 0; i < RRV_BLOCK_SIZE; i++) {
            var v1 = pic[p1];
            var v2 = pic[p2];
            pic[p1] = (3 * v1 + v2 + 2) / 4;
            pic[p2] = (v1 + 3 * v2 + 2) / 4;
            p1++;
            p2++;
        }
    }
}
