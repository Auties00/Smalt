package com.github.auties00.smalt.video.hevc.encoder.internal;

/**
 * Measures the difference between blocks of 16-bit samples: the sum of squared errors, the sum of absolute
 * differences and the sum of absolute Hadamard-transformed differences (SATD).
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class Distortion {
    /**
     * Prevents instantiation.
     */
    private Distortion() {
        throw new AssertionError();
    }

    /**
     * Returns the sum of squared errors between two blocks.
     *
     * @param a the first plane
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the row stride of {@code a}
     * @param b the second plane
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the row stride of {@code b}
     * @param width the block width
     * @param height the block height
     * @return the sum
     */
    static long sse(short[] a, int aOffset, int aStride, short[] b, int bOffset, int bStride, int width, int height) {
        long sum = 0;
        for (var y = 0; y < height; y++) {
            var ra = aOffset + y * aStride;
            var rb = bOffset + y * bStride;
            for (var x = 0; x < width; x++) {
                var d = a[ra + x] - b[rb + x];
                sum += d * d;
            }
        }
        return sum;
    }

    /**
     * Returns the sum of absolute differences between two blocks.
     *
     * @param a the first plane
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the row stride of {@code a}
     * @param b the second plane
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the row stride of {@code b}
     * @param width the block width
     * @param height the block height
     * @return the sum
     */
    static int sad(short[] a, int aOffset, int aStride, short[] b, int bOffset, int bStride, int width, int height) {
        var sum = 0;
        for (var y = 0; y < height; y++) {
            var ra = aOffset + y * aStride;
            var rb = bOffset + y * bStride;
            for (var x = 0; x < width; x++) {
                sum += Math.abs(a[ra + x] - b[rb + x]);
            }
        }
        return sum;
    }

    /**
     * Returns the SATD between two blocks whose sides are multiples of 4: the sum over 8x8 sub-blocks, or 4x4
     * sub-blocks when a side is 4, of the absolute values of the Hadamard transform of the differences,
     * halved.
     *
     * @param a the first plane
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the row stride of {@code a}
     * @param b the second plane
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the row stride of {@code b}
     * @param width the block width, a multiple of 4
     * @param height the block height, a multiple of 4
     * @param scratch at least 64 entries
     * @return the SATD
     */
    static int satd(short[] a, int aOffset, int aStride, short[] b, int bOffset, int bStride, int width, int height,
                    int[] scratch) {
        var sum = 0;
        if ((width & 7) == 0 && (height & 7) == 0) {
            for (var y = 0; y < height; y += 8) {
                for (var x = 0; x < width; x += 8) {
                    sum += hadamard8(a, aOffset + y * aStride + x, aStride, b, bOffset + y * bStride + x, bStride,
                            scratch);
                }
            }
            return sum;
        }
        for (var y = 0; y < height; y += 4) {
            for (var x = 0; x < width; x += 4) {
                sum += hadamard4(a, aOffset + y * aStride + x, aStride, b, bOffset + y * bStride + x, bStride,
                        scratch);
            }
        }
        return sum;
    }

    /**
     * Returns the SATD of a 4x4 block.
     *
     * @param a the first plane
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the row stride of {@code a}
     * @param b the second plane
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the row stride of {@code b}
     * @param m at least 16 entries
     * @return the SATD
     */
    private static int hadamard4(short[] a, int aOffset, int aStride, short[] b, int bOffset, int bStride, int[] m) {
        for (var y = 0; y < 4; y++) {
            var ra = aOffset + y * aStride;
            var rb = bOffset + y * bStride;
            var d0 = a[ra] - b[rb];
            var d1 = a[ra + 1] - b[rb + 1];
            var d2 = a[ra + 2] - b[rb + 2];
            var d3 = a[ra + 3] - b[rb + 3];
            var s01 = d0 + d1;
            var t01 = d0 - d1;
            var s23 = d2 + d3;
            var t23 = d2 - d3;
            m[y * 4] = s01 + s23;
            m[y * 4 + 1] = t01 + t23;
            m[y * 4 + 2] = s01 - s23;
            m[y * 4 + 3] = t01 - t23;
        }
        var sum = 0;
        for (var x = 0; x < 4; x++) {
            var d0 = m[x];
            var d1 = m[4 + x];
            var d2 = m[8 + x];
            var d3 = m[12 + x];
            var s01 = d0 + d1;
            var t01 = d0 - d1;
            var s23 = d2 + d3;
            var t23 = d2 - d3;
            sum += Math.abs(s01 + s23) + Math.abs(t01 + t23) + Math.abs(s01 - s23) + Math.abs(t01 - t23);
        }
        return (sum + 1) >> 1;
    }

    /**
     * Returns the SATD of an 8x8 block.
     *
     * @param a the first plane
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the row stride of {@code a}
     * @param b the second plane
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the row stride of {@code b}
     * @param m at least 64 entries
     * @return the SATD
     */
    private static int hadamard8(short[] a, int aOffset, int aStride, short[] b, int bOffset, int bStride, int[] m) {
        for (var y = 0; y < 8; y++) {
            var ra = aOffset + y * aStride;
            var rb = bOffset + y * bStride;
            var d0 = a[ra] - b[rb];
            var d1 = a[ra + 1] - b[rb + 1];
            var d2 = a[ra + 2] - b[rb + 2];
            var d3 = a[ra + 3] - b[rb + 3];
            var d4 = a[ra + 4] - b[rb + 4];
            var d5 = a[ra + 5] - b[rb + 5];
            var d6 = a[ra + 6] - b[rb + 6];
            var d7 = a[ra + 7] - b[rb + 7];
            var e0 = d0 + d4;
            var e1 = d1 + d5;
            var e2 = d2 + d6;
            var e3 = d3 + d7;
            var e4 = d0 - d4;
            var e5 = d1 - d5;
            var e6 = d2 - d6;
            var e7 = d3 - d7;
            var f0 = e0 + e2;
            var f1 = e1 + e3;
            var f2 = e0 - e2;
            var f3 = e1 - e3;
            var f4 = e4 + e6;
            var f5 = e5 + e7;
            var f6 = e4 - e6;
            var f7 = e5 - e7;
            var row = y * 8;
            m[row] = f0 + f1;
            m[row + 1] = f0 - f1;
            m[row + 2] = f2 + f3;
            m[row + 3] = f2 - f3;
            m[row + 4] = f4 + f5;
            m[row + 5] = f4 - f5;
            m[row + 6] = f6 + f7;
            m[row + 7] = f6 - f7;
        }
        var sum = 0;
        for (var x = 0; x < 8; x++) {
            var e0 = m[x] + m[32 + x];
            var e1 = m[8 + x] + m[40 + x];
            var e2 = m[16 + x] + m[48 + x];
            var e3 = m[24 + x] + m[56 + x];
            var e4 = m[x] - m[32 + x];
            var e5 = m[8 + x] - m[40 + x];
            var e6 = m[16 + x] - m[48 + x];
            var e7 = m[24 + x] - m[56 + x];
            var f0 = e0 + e2;
            var f1 = e1 + e3;
            var f2 = e0 - e2;
            var f3 = e1 - e3;
            var f4 = e4 + e6;
            var f5 = e5 + e7;
            var f6 = e4 - e6;
            var f7 = e5 - e7;
            sum += Math.abs(f0 + f1) + Math.abs(f0 - f1) + Math.abs(f2 + f3) + Math.abs(f2 - f3)
                    + Math.abs(f4 + f5) + Math.abs(f4 - f5) + Math.abs(f6 + f7) + Math.abs(f6 - f7);
        }
        return (sum + 2) >> 2;
    }
}
