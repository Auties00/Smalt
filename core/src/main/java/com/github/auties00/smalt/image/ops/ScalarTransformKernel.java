package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Implements {@link TransformKernel} with plain Java arithmetic.
 *
 * <p>Transpositions walk {@link TransformKernel#BLOCK}-byte square blocks. Inside a block, 8-bit
 * planes are transposed 8x8 samples at a time as eight 64-bit words with three rounds of masked
 * swaps (a SIMD-within-a-register transpose), and 32-bit pixels are moved one at a time.
 */
final class ScalarTransformKernel implements TransformKernel {
    /**
     * Constructs the kernel.
     */
    ScalarTransformKernel() {
    }

    @Override
    public void transpose8(byte[] src, int sOff, int sStride, int width, int height, byte[] dst, int dOff, int dStride,
                           boolean reverseRows, boolean reverseColumns) {
        for (var bx = 0; bx < width; bx += BLOCK) {
            var xEnd = Math.min(bx + BLOCK, width);
            for (var by = 0; by < height; by += BLOCK) {
                var yEnd = Math.min(by + BLOCK, height);
                for (var ty = by; ty < yEnd; ty += 8) {
                    for (var tx = bx; tx < xEnd; tx += 8) {
                        if (ty + 8 <= height && tx + 8 <= width) {
                            tile8(src, sOff, sStride, width, height, dst, dOff, dStride, reverseRows, reverseColumns, tx, ty);
                        } else {
                            transpose8Range(src, sOff, sStride, width, height, dst, dOff, dStride, reverseRows, reverseColumns,
                                    tx, Math.min(tx + 8, width), ty, Math.min(ty + 8, height));
                        }
                    }
                }
            }
        }
    }

    /**
     * Transposes one sample at a time over a rectangle of the source.
     *
     * @param src            the source samples
     * @param sOff           the index of the source's first sample
     * @param sStride        the source row stride
     * @param width          the source width
     * @param height         the source height
     * @param dst            the destination samples
     * @param dOff           the index of the destination's first sample
     * @param dStride        the destination row stride
     * @param reverseRows    whether destination rows run from the last source column
     * @param reverseColumns whether destination columns run from the last source row
     * @param x0             the rectangle's first column
     * @param x1             the column after the rectangle
     * @param y0             the rectangle's first row
     * @param y1             the row after the rectangle
     */
    static void transpose8Range(byte[] src, int sOff, int sStride, int width, int height, byte[] dst, int dOff, int dStride,
                                boolean reverseRows, boolean reverseColumns, int x0, int x1, int y0, int y1) {
        for (var y = y0; y < y1; y++) {
            var column = reverseColumns ? height - 1 - y : y;
            for (var x = x0; x < x1; x++) {
                var row = reverseRows ? width - 1 - x : x;
                dst[dOff + row * dStride + column] = src[sOff + y * sStride + x];
            }
        }
    }

    /**
     * Transposes one full 8x8 tile as eight 64-bit words.
     *
     * @param src            the source samples
     * @param sOff           the index of the source's first sample
     * @param sStride        the source row stride
     * @param width          the source width
     * @param height         the source height
     * @param dst            the destination samples
     * @param dOff           the index of the destination's first sample
     * @param dStride        the destination row stride
     * @param reverseRows    whether destination rows run from the last source column
     * @param reverseColumns whether destination columns run from the last source row
     * @param tx             the tile's first column
     * @param ty             the tile's first row
     */
    private static void tile8(byte[] src, int sOff, int sStride, int width, int height, byte[] dst, int dOff, int dStride,
                              boolean reverseRows, boolean reverseColumns, int tx, int ty) {
        var base = sOff + ty * sStride + tx;
        var a0 = ByteArrays.i64le(src, base);
        var a1 = ByteArrays.i64le(src, base + sStride);
        var a2 = ByteArrays.i64le(src, base + 2 * sStride);
        var a3 = ByteArrays.i64le(src, base + 3 * sStride);
        var a4 = ByteArrays.i64le(src, base + 4 * sStride);
        var a5 = ByteArrays.i64le(src, base + 5 * sStride);
        var a6 = ByteArrays.i64le(src, base + 6 * sStride);
        var a7 = ByteArrays.i64le(src, base + 7 * sStride);
        // swap the off-diagonal 4x4 blocks, then 2x2 blocks, then single samples
        var m4 = 0x00000000FFFFFFFFL;
        var t = ((a0 >>> 32) ^ a4) & m4;
        a4 ^= t;
        a0 ^= t << 32;
        t = ((a1 >>> 32) ^ a5) & m4;
        a5 ^= t;
        a1 ^= t << 32;
        t = ((a2 >>> 32) ^ a6) & m4;
        a6 ^= t;
        a2 ^= t << 32;
        t = ((a3 >>> 32) ^ a7) & m4;
        a7 ^= t;
        a3 ^= t << 32;
        var m2 = 0x0000FFFF0000FFFFL;
        t = ((a0 >>> 16) ^ a2) & m2;
        a2 ^= t;
        a0 ^= t << 16;
        t = ((a1 >>> 16) ^ a3) & m2;
        a3 ^= t;
        a1 ^= t << 16;
        t = ((a4 >>> 16) ^ a6) & m2;
        a6 ^= t;
        a4 ^= t << 16;
        t = ((a5 >>> 16) ^ a7) & m2;
        a7 ^= t;
        a5 ^= t << 16;
        var m1 = 0x00FF00FF00FF00FFL;
        t = ((a0 >>> 8) ^ a1) & m1;
        a1 ^= t;
        a0 ^= t << 8;
        t = ((a2 >>> 8) ^ a3) & m1;
        a3 ^= t;
        a2 ^= t << 8;
        t = ((a4 >>> 8) ^ a5) & m1;
        a5 ^= t;
        a4 ^= t << 8;
        t = ((a6 >>> 8) ^ a7) & m1;
        a7 ^= t;
        a6 ^= t << 8;
        var column = reverseColumns ? height - 8 - ty : ty;
        var rowStep = reverseRows ? -dStride : dStride;
        var out = dOff + (reverseRows ? width - 1 - tx : tx) * dStride + column;
        if (reverseColumns) {
            a0 = Long.reverseBytes(a0);
            a1 = Long.reverseBytes(a1);
            a2 = Long.reverseBytes(a2);
            a3 = Long.reverseBytes(a3);
            a4 = Long.reverseBytes(a4);
            a5 = Long.reverseBytes(a5);
            a6 = Long.reverseBytes(a6);
            a7 = Long.reverseBytes(a7);
        }
        ByteArrays.put64le(dst, out, a0);
        ByteArrays.put64le(dst, out + rowStep, a1);
        ByteArrays.put64le(dst, out + 2 * rowStep, a2);
        ByteArrays.put64le(dst, out + 3 * rowStep, a3);
        ByteArrays.put64le(dst, out + 4 * rowStep, a4);
        ByteArrays.put64le(dst, out + 5 * rowStep, a5);
        ByteArrays.put64le(dst, out + 6 * rowStep, a6);
        ByteArrays.put64le(dst, out + 7 * rowStep, a7);
    }

    /**
     * Transposes one 8x8 tile of bytes: byte {@code c} of source row {@code r} becomes byte
     * {@code r} of destination row {@code c}.
     *
     * @param src     the source bytes
     * @param sOff    the index of the first source row
     * @param sStride the source row stride
     * @param dst     the destination bytes
     * @param dOff    the index of the first destination row
     * @param dStride the destination row stride
     */
    static void transposeTile(byte[] src, int sOff, int sStride, byte[] dst, int dOff, int dStride) {
        var a0 = ByteArrays.i64le(src, sOff);
        var a1 = ByteArrays.i64le(src, sOff + sStride);
        var a2 = ByteArrays.i64le(src, sOff + 2 * sStride);
        var a3 = ByteArrays.i64le(src, sOff + 3 * sStride);
        var a4 = ByteArrays.i64le(src, sOff + 4 * sStride);
        var a5 = ByteArrays.i64le(src, sOff + 5 * sStride);
        var a6 = ByteArrays.i64le(src, sOff + 6 * sStride);
        var a7 = ByteArrays.i64le(src, sOff + 7 * sStride);
        var m4 = 0x00000000FFFFFFFFL;
        var t = ((a0 >>> 32) ^ a4) & m4;
        a4 ^= t;
        a0 ^= t << 32;
        t = ((a1 >>> 32) ^ a5) & m4;
        a5 ^= t;
        a1 ^= t << 32;
        t = ((a2 >>> 32) ^ a6) & m4;
        a6 ^= t;
        a2 ^= t << 32;
        t = ((a3 >>> 32) ^ a7) & m4;
        a7 ^= t;
        a3 ^= t << 32;
        var m2 = 0x0000FFFF0000FFFFL;
        t = ((a0 >>> 16) ^ a2) & m2;
        a2 ^= t;
        a0 ^= t << 16;
        t = ((a1 >>> 16) ^ a3) & m2;
        a3 ^= t;
        a1 ^= t << 16;
        t = ((a4 >>> 16) ^ a6) & m2;
        a6 ^= t;
        a4 ^= t << 16;
        t = ((a5 >>> 16) ^ a7) & m2;
        a7 ^= t;
        a5 ^= t << 16;
        var m1 = 0x00FF00FF00FF00FFL;
        t = ((a0 >>> 8) ^ a1) & m1;
        a1 ^= t;
        a0 ^= t << 8;
        t = ((a2 >>> 8) ^ a3) & m1;
        a3 ^= t;
        a2 ^= t << 8;
        t = ((a4 >>> 8) ^ a5) & m1;
        a5 ^= t;
        a4 ^= t << 8;
        t = ((a6 >>> 8) ^ a7) & m1;
        a7 ^= t;
        a6 ^= t << 8;
        ByteArrays.put64le(dst, dOff, a0);
        ByteArrays.put64le(dst, dOff + dStride, a1);
        ByteArrays.put64le(dst, dOff + 2 * dStride, a2);
        ByteArrays.put64le(dst, dOff + 3 * dStride, a3);
        ByteArrays.put64le(dst, dOff + 4 * dStride, a4);
        ByteArrays.put64le(dst, dOff + 5 * dStride, a5);
        ByteArrays.put64le(dst, dOff + 6 * dStride, a6);
        ByteArrays.put64le(dst, dOff + 7 * dStride, a7);
    }

    @Override
    public void transpose32(byte[] src, int sOff, int sStride, int width, int height, byte[] dst, int dOff, int dStride,
                            boolean reverseRows, boolean reverseColumns) {
        var block = BLOCK / 4;
        for (var bx = 0; bx < width; bx += block) {
            for (var by = 0; by < height; by += block) {
                var yEnd = Math.min(by + block, height);
                transpose32Range(src, sOff, sStride, width, height, dst, dOff, dStride, reverseRows, reverseColumns,
                        bx, Math.min(bx + block, width), by, yEnd);
            }
        }
    }

    /**
     * Transposes one pixel at a time over a rectangle of the source.
     *
     * @param src            the source bytes
     * @param sOff           the index of the source's first byte
     * @param sStride        the source row stride in bytes
     * @param width          the source width in pixels
     * @param height         the source height in pixels
     * @param dst            the destination bytes
     * @param dOff           the index of the destination's first byte
     * @param dStride        the destination row stride in bytes
     * @param reverseRows    whether destination rows run from the last source column
     * @param reverseColumns whether destination columns run from the last source row
     * @param x0             the rectangle's first column
     * @param x1             the column after the rectangle
     * @param y0             the rectangle's first row
     * @param y1             the row after the rectangle
     */
    static void transpose32Range(byte[] src, int sOff, int sStride, int width, int height, byte[] dst, int dOff, int dStride,
                                 boolean reverseRows, boolean reverseColumns, int x0, int x1, int y0, int y1) {
        for (var x = x0; x < x1; x++) {
            var out = dOff + (reverseRows ? width - 1 - x : x) * dStride;
            for (var y = y0; y < y1; y++) {
                var column = reverseColumns ? height - 1 - y : y;
                ByteArrays.put32le(dst, out + 4 * column, ByteArrays.i32le(src, sOff + y * sStride + 4 * x));
            }
        }
    }

    @Override
    public void reverse8(byte[] src, int sOff, byte[] dst, int dOff, int count) {
        var i = 0;
        for (; i + 8 <= count; i += 8) {
            ByteArrays.put64le(dst, dOff + i, Long.reverseBytes(ByteArrays.i64le(src, sOff + count - 8 - i)));
        }
        for (; i < count; i++) {
            dst[dOff + i] = src[sOff + count - 1 - i];
        }
    }

    @Override
    public void reverse32(byte[] src, int sOff, byte[] dst, int dOff, int count) {
        for (var i = 0; i < count; i++) {
            ByteArrays.put32le(dst, dOff + 4 * i, ByteArrays.i32le(src, sOff + 4 * (count - 1 - i)));
        }
    }
}
