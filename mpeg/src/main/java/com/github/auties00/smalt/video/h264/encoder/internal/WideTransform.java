package com.github.auties00.smalt.video.h264.encoder.internal;

/**
 * Computes the transforms and the quantization of the extended engine on residual blocks of any
 * bit depth: the forward integer transforms and quantizers, which an encoder may choose, and the
 * normative scaling and inverse transforms (ITU-T H.264 8.5.6 to 8.5.13) with flat scaling lists,
 * whose results a decoder reproduces exactly.
 *
 * <p>Blocks are in raster order. Quantizers are {@code qP} values with the bit depth offset
 * included ({@code QP'Y} or {@code QP'C}), from 0 to {@code 51 + QpBdOffset}. Arguments are not
 * checked; values outside the documented ranges give unspecified results. Instances hold scratch
 * space and are not thread-safe.
 */
final class WideTransform {
    /**
     * Scratch space of one block.
     */
    private final int[] scratch = new int[64];

    /**
     * The forward quantization multipliers of the 4x4 transform for {@code qP % 6}, by position
     * class: both coordinates even, both odd, mixed.
     */
    private static final int[][] QUANT4 = {
            {13107, 5243, 8066}, {11916, 4660, 7490}, {10082, 4194, 6554},
            {9362, 3647, 5825}, {8192, 3355, 5243}, {7282, 2893, 4559}
    };

    /**
     * The forward quantization multipliers of the 8x8 transform for {@code qP % 6}, by the position
     * classes of {@link #CLASS8}.
     */
    private static final int[][] QUANT8 = {
            {13107, 11428, 20972, 12222, 16777, 15481},
            {11916, 10826, 19174, 11058, 14980, 14290},
            {10082, 8943, 15978, 9675, 12710, 11985},
            {9362, 8228, 14913, 8931, 11984, 11259},
            {8192, 7346, 13159, 7740, 10486, 9777},
            {7282, 6428, 11570, 6830, 9118, 8640}
    };

    /**
     * The {@code normAdjust4x4} values of ITU-T H.264 8.5.9 for {@code qP % 6}, by position class.
     */
    private static final int[][] NORM4 = {
            {10, 16, 13}, {11, 18, 14}, {13, 20, 16}, {14, 23, 18}, {16, 25, 20}, {18, 29, 23}
    };

    /**
     * The {@code normAdjust8x8} values of ITU-T H.264 8.5.9 for {@code qP % 6}, by the position
     * classes of {@link #CLASS8}.
     */
    private static final int[][] NORM8 = {
            {20, 18, 32, 19, 25, 24}, {22, 19, 35, 21, 28, 26}, {26, 23, 42, 24, 33, 31},
            {28, 25, 45, 26, 35, 33}, {32, 28, 51, 30, 40, 38}, {36, 32, 58, 34, 46, 43}
    };

    /**
     * The position class of each coefficient of a 4x4 block.
     */
    private static final byte[] CLASS4 = new byte[16];

    /**
     * The position class of each coefficient of an 8x8 block.
     */
    private static final byte[] CLASS8 = new byte[64];

    static {
        for (var i = 0; i < 4; i++) {
            for (var j = 0; j < 4; j++) {
                CLASS4[i * 4 + j] = (byte) ((i & 1) == 0 && (j & 1) == 0 ? 0 : (i & 1) == 1 && (j & 1) == 1 ? 1 : 2);
            }
        }
        for (var i = 0; i < 8; i++) {
            for (var j = 0; j < 8; j++) {
                int c;
                if (i % 4 == 0 && j % 4 == 0) {
                    c = 0;
                } else if (i % 2 == 1 && j % 2 == 1) {
                    c = 1;
                } else if (i % 4 == 2 && j % 4 == 2) {
                    c = 2;
                } else if (i % 4 == 0 && j % 2 == 1 || i % 2 == 1 && j % 4 == 0) {
                    c = 3;
                } else if (i % 4 == 0 && j % 4 == 2 || i % 4 == 2 && j % 4 == 0) {
                    c = 4;
                } else {
                    c = 5;
                }
                CLASS8[i * 8 + j] = (byte) c;
            }
        }
    }

    /**
     * Constructs a transform with its own scratch space.
     */
    WideTransform() {
    }

    /**
     * Applies the forward 4x4 core transform to a residual block, in place.
     *
     * @param block the residual, 16 values in raster order, replaced by the coefficients
     * @param offset the index of the first value
     */
    static void forward4x4(int[] block, int offset) {
        for (var i = 0; i < 4; i++) {
            var r = offset + i * 4;
            var s0 = block[r] + block[r + 3];
            var s1 = block[r + 1] + block[r + 2];
            var d0 = block[r] - block[r + 3];
            var d1 = block[r + 1] - block[r + 2];
            block[r] = s0 + s1;
            block[r + 1] = 2 * d0 + d1;
            block[r + 2] = s0 - s1;
            block[r + 3] = d0 - 2 * d1;
        }
        for (var j = 0; j < 4; j++) {
            var c = offset + j;
            var s0 = block[c] + block[c + 12];
            var s1 = block[c + 4] + block[c + 8];
            var d0 = block[c] - block[c + 12];
            var d1 = block[c + 4] - block[c + 8];
            block[c] = s0 + s1;
            block[c + 4] = 2 * d0 + d1;
            block[c + 8] = s0 - s1;
            block[c + 12] = d0 - 2 * d1;
        }
    }

    /**
     * Applies the forward 8x8 integer transform to a residual block, in place.
     *
     * @param block the residual, 64 values in raster order, replaced by the coefficients
     * @param offset the index of the first value
     */
    static void forward8x8(int[] block, int offset) {
        for (var i = 0; i < 8; i++) {
            forward8(block, offset + i * 8, 1);
        }
        for (var j = 0; j < 8; j++) {
            forward8(block, offset + j, 8);
        }
    }

    /**
     * Applies the one-dimensional forward 8-point transform to eight values.
     *
     * @param v the values
     * @param o the index of the first value
     * @param s the distance between two values
     */
    private static void forward8(int[] v, int o, int s) {
        var p0 = v[o];
        var p1 = v[o + s];
        var p2 = v[o + 2 * s];
        var p3 = v[o + 3 * s];
        var p4 = v[o + 4 * s];
        var p5 = v[o + 5 * s];
        var p6 = v[o + 6 * s];
        var p7 = v[o + 7 * s];
        var a0 = p0 + p7;
        var a1 = p1 + p6;
        var a2 = p2 + p5;
        var a3 = p3 + p4;
        var b0 = a0 + a3;
        var b1 = a1 + a2;
        var b2 = a0 - a3;
        var b3 = a1 - a2;
        var a4 = p0 - p7;
        var a5 = p1 - p6;
        var a6 = p2 - p5;
        var a7 = p3 - p4;
        var b4 = a5 + a6 + ((a4 >> 1) + a4);
        var b5 = a4 - a7 - ((a6 >> 1) + a6);
        var b6 = a4 + a7 - ((a5 >> 1) + a5);
        var b7 = a5 - a6 + ((a7 >> 1) + a7);
        v[o] = b0 + b1;
        v[o + 2 * s] = b2 + (b3 >> 1);
        v[o + 4 * s] = b0 - b1;
        v[o + 6 * s] = (b2 >> 1) - b3;
        v[o + s] = b4 + (b7 >> 2);
        v[o + 3 * s] = b5 + (b6 >> 2);
        v[o + 5 * s] = b6 - (b5 >> 2);
        v[o + 7 * s] = (b4 >> 2) - b7;
    }

    /**
     * Quantizes the coefficients of a 4x4 block, in place.
     *
     * @param block the coefficients, replaced by the levels
     * @param offset the index of the first coefficient
     * @param qp the quantizer {@code qP}
     * @param intra whether the block belongs to an intra macroblock, which rounds levels up more
     * @param first the first coefficient to quantize, 1 to leave the DC coefficient alone
     * @param maxLevel the largest magnitude of a level
     * @return the number of non-zero levels
     */
    static int quant4x4(int[] block, int offset, int qp, boolean intra, int first, int maxLevel) {
        var bits = 15 + qp / 6;
        var round = (1 << bits) / (intra ? 3 : 6);
        var factors = QUANT4[qp % 6];
        var count = 0;
        for (var i = first; i < 16; i++) {
            var value = block[offset + i];
            var level = (int) Math.min(((long) Math.abs(value) * factors[CLASS4[i]] + round) >> bits, maxLevel);
            block[offset + i] = value < 0 ? -level : level;
            if (level != 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * Quantizes the coefficients of an 8x8 block, in place.
     *
     * @param block the coefficients, replaced by the levels
     * @param offset the index of the first coefficient
     * @param qp the quantizer {@code qP}
     * @param intra whether the block belongs to an intra macroblock
     * @param maxLevel the largest magnitude of a level
     * @return the number of non-zero levels
     */
    static int quant8x8(int[] block, int offset, int qp, boolean intra, int maxLevel) {
        var bits = 16 + qp / 6;
        var round = (1 << bits) / (intra ? 3 : 6);
        var factors = QUANT8[qp % 6];
        var count = 0;
        for (var i = 0; i < 64; i++) {
            var value = block[offset + i];
            var level = (int) Math.min(((long) Math.abs(value) * factors[CLASS8[i]] + round) >> bits, maxLevel);
            block[offset + i] = value < 0 ? -level : level;
            if (level != 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * Quantizes the DC coefficients of a transformed DC array (the luma DC of an Intra_16x16
     * macroblock or the chroma DC of a macroblock), in place.
     *
     * @param dc the transformed DC coefficients, replaced by the levels
     * @param count the number of coefficients
     * @param qp the quantizer {@code qP} of the DC array (for 4:2:2 chroma, {@code QP'C + 3})
     * @param intra whether the macroblock is intra
     * @param maxLevel the largest magnitude of a level
     * @return the number of non-zero levels
     */
    static int quantDc(int[] dc, int count, int qp, boolean intra, int maxLevel) {
        var bits = 16 + qp / 6;
        var round = (1 << bits) / (intra ? 3 : 6);
        var factor = QUANT4[qp % 6][0];
        var nonZero = 0;
        for (var i = 0; i < count; i++) {
            var value = dc[i];
            var level = (int) Math.min(((long) Math.abs(value) * factor + round) >> bits, maxLevel);
            dc[i] = value < 0 ? -level : level;
            if (level != 0) {
                nonZero++;
            }
        }
        return nonZero;
    }

    /**
     * Applies the forward 4x4 Hadamard transform of the luma DC coefficients, in place, halving the
     * result.
     *
     * @param dc the 16 DC coefficients in raster order of their blocks
     */
    static void forwardLumaDc(int[] dc) {
        hadamard4(dc);
        for (var i = 0; i < 16; i++) {
            dc[i] >>= 1;
        }
    }

    /**
     * Applies the 4x4 Hadamard transform, in place.
     *
     * @param v 16 values in raster order
     */
    private static void hadamard4(int[] v) {
        for (var i = 0; i < 4; i++) {
            var r = i * 4;
            var s0 = v[r] + v[r + 1];
            var s1 = v[r + 2] + v[r + 3];
            var d0 = v[r] - v[r + 1];
            var d1 = v[r + 2] - v[r + 3];
            v[r] = s0 + s1;
            v[r + 1] = s0 - s1;
            v[r + 2] = d0 - d1;
            v[r + 3] = d0 + d1;
        }
        for (var j = 0; j < 4; j++) {
            var s0 = v[j] + v[j + 4];
            var s1 = v[j + 8] + v[j + 12];
            var d0 = v[j] - v[j + 4];
            var d1 = v[j + 8] - v[j + 12];
            v[j] = s0 + s1;
            v[j + 4] = s0 - s1;
            v[j + 8] = d0 - d1;
            v[j + 12] = d0 + d1;
        }
    }

    /**
     * Applies the forward chroma DC transform, in place: the 2x2 Hadamard transform for 4:2:0, or
     * for 4:2:2 the transpose of the 4x2 inverse transform of ITU-T H.264 8.5.11.1.
     *
     * @param dc the DC coefficients of the chroma blocks in raster order, 4 or 8 of them
     * @param count the number of blocks, 4 or 8
     */
    void forwardChromaDc(int[] dc, int count) {
        if (count == 4) {
            var a = dc[0];
            var b = dc[1];
            var c = dc[2];
            var d = dc[3];
            dc[0] = a + b + c + d;
            dc[1] = a - b + c - d;
            dc[2] = a + b - c - d;
            dc[3] = a - b - c + d;
            return;
        }
        // The rows of the 4x2 array are the four block rows; inverse f = A c B, forward A^T d B, A symmetric.
        var t = scratch;
        for (var j = 0; j < 2; j++) {
            var d0 = dc[j];
            var d1 = dc[2 + j];
            var d2 = dc[4 + j];
            var d3 = dc[6 + j];
            t[j] = d0 + d1 + d2 + d3;
            t[2 + j] = d0 + d1 - d2 - d3;
            t[4 + j] = d0 - d1 - d2 + d3;
            t[6 + j] = d0 - d1 + d2 - d3;
        }
        for (var i = 0; i < 4; i++) {
            dc[2 * i] = t[2 * i] + t[2 * i + 1];
            dc[2 * i + 1] = t[2 * i] - t[2 * i + 1];
        }
    }

    /**
     * Returns {@code LevelScale4x4} at a position for flat scaling lists.
     *
     * @param qp the quantizer {@code qP}
     * @param index the position in raster order
     * @return the scale
     */
    private static int scale4(int qp, int index) {
        return 16 * NORM4[qp % 6][CLASS4[index]];
    }

    /**
     * Scales the levels of a 4x4 block (ITU-T H.264 8.5.12.1), in place; the DC level of an
     * Intra_16x16 or chroma block is left for the caller, which stores the DC transform's result.
     *
     * @param block the levels in raster order, replaced by the coefficients
     * @param offset the index of the first level
     * @param qp the quantizer {@code qP}
     * @param first 1 to skip the DC level, 0 to scale it too
     */
    static void dequant4x4(int[] block, int offset, int qp, int first) {
        var shift = qp / 6;
        for (var i = first; i < 16; i++) {
            var level = block[offset + i];
            if (level == 0) {
                continue;
            }
            var value = level * scale4(qp, i);
            block[offset + i] = shift >= 4 ? value << (shift - 4) : (value + (1 << (3 - shift))) >> (4 - shift);
        }
    }

    /**
     * Scales the levels of an 8x8 block (ITU-T H.264 8.5.13.1), in place.
     *
     * @param block the levels in raster order, replaced by the coefficients
     * @param offset the index of the first level
     * @param qp the quantizer {@code qP}
     */
    static void dequant8x8(int[] block, int offset, int qp) {
        var shift = qp / 6;
        var norm = NORM8[qp % 6];
        for (var i = 0; i < 64; i++) {
            var level = block[offset + i];
            if (level == 0) {
                continue;
            }
            var value = level * 16 * norm[CLASS8[i]];
            block[offset + i] = shift >= 6 ? value << (shift - 6) : (value + (1 << (5 - shift))) >> (6 - shift);
        }
    }

    /**
     * Applies the inverse luma DC transform and scaling of an Intra_16x16 macroblock
     * (ITU-T H.264 8.5.10), in place.
     *
     * @param dc the 16 DC levels, in the raster order of their blocks, replaced by the DC coefficients
     * @param qp the quantizer {@code qP}
     */
    static void inverseLumaDc(int[] dc, int qp) {
        hadamard4(dc);
        var scale = scale4(qp, 0);
        var shift = qp / 6;
        for (var i = 0; i < 16; i++) {
            var value = dc[i] * scale;
            dc[i] = shift >= 6 ? value << (shift - 6) : (value + (1 << (5 - shift))) >> (6 - shift);
        }
    }

    /**
     * Applies the inverse chroma DC transform and scaling (ITU-T H.264 8.5.11), in place.
     *
     * @param dc the DC levels in the raster order of their blocks, 4 or 8, replaced by the DC
     * coefficients
     * @param count the number of blocks, 4 for 4:2:0 or 8 for 4:2:2
     * @param qp the quantizer {@code QP'C} of the chroma component
     */
    void inverseChromaDc(int[] dc, int count, int qp) {
        if (count == 4) {
            var a = dc[0];
            var b = dc[1];
            var c = dc[2];
            var d = dc[3];
            var f0 = a + b + c + d;
            var f1 = a - b + c - d;
            var f2 = a + b - c - d;
            var f3 = a - b - c + d;
            var scale = scale4(qp, 0);
            var shift = qp / 6;
            dc[0] = ((f0 * scale) << shift) >> 5;
            dc[1] = ((f1 * scale) << shift) >> 5;
            dc[2] = ((f2 * scale) << shift) >> 5;
            dc[3] = ((f3 * scale) << shift) >> 5;
            return;
        }
        var t = scratch;
        for (var j = 0; j < 2; j++) {
            var c0 = dc[j];
            var c1 = dc[2 + j];
            var c2 = dc[4 + j];
            var c3 = dc[6 + j];
            t[j] = c0 + c1 + c2 + c3;
            t[2 + j] = c0 + c1 - c2 - c3;
            t[4 + j] = c0 - c1 - c2 + c3;
            t[6 + j] = c0 - c1 + c2 - c3;
        }
        var qpDc = qp + 3;
        var scale = scale4(qpDc, 0);
        var shift = qpDc / 6;
        for (var i = 0; i < 4; i++) {
            var f0 = (t[2 * i] + t[2 * i + 1]) * scale;
            var f1 = (t[2 * i] - t[2 * i + 1]) * scale;
            if (shift >= 6) {
                dc[2 * i] = f0 << (shift - 6);
                dc[2 * i + 1] = f1 << (shift - 6);
            } else {
                dc[2 * i] = (f0 + (1 << (5 - shift))) >> (6 - shift);
                dc[2 * i + 1] = (f1 + (1 << (5 - shift))) >> (6 - shift);
            }
        }
    }

    /**
     * Applies the inverse 4x4 transform to a block of coefficients and adds the residual to the
     * prediction in a plane (ITU-T H.264 8.5.12.2 and 8.5.14).
     *
     * @param block the coefficients in raster order, left unchanged
     * @param offset the index of the first coefficient
     * @param plane the samples holding the prediction
     * @param at the index of the block's top-left sample
     * @param stride the row stride
     * @param max the largest sample value
     */
    void inverse4x4Add(int[] block, int offset, short[] plane, int at, int stride, int max) {
        var t = scratch;
        for (var i = 0; i < 4; i++) {
            var r = offset + i * 4;
            var e = block[r] + block[r + 2];
            var f = block[r] - block[r + 2];
            var g = (block[r + 1] >> 1) - block[r + 3];
            var h = block[r + 1] + (block[r + 3] >> 1);
            t[i * 4] = e + h;
            t[i * 4 + 1] = f + g;
            t[i * 4 + 2] = f - g;
            t[i * 4 + 3] = e - h;
        }
        for (var j = 0; j < 4; j++) {
            var e = t[j] + t[8 + j];
            var f = t[j] - t[8 + j];
            var g = (t[4 + j] >> 1) - t[12 + j];
            var h = t[4 + j] + (t[12 + j] >> 1);
            addSample(plane, at + j, max, e + h);
            addSample(plane, at + stride + j, max, f + g);
            addSample(plane, at + 2 * stride + j, max, f - g);
            addSample(plane, at + 3 * stride + j, max, e - h);
        }
    }

    /**
     * Adds one transform output to a predicted sample, rounding it and clipping the sum.
     *
     * @param plane the samples
     * @param at the sample's index
     * @param max the largest sample value
     * @param value the transform output, scaled by 64
     */
    private static void addSample(short[] plane, int at, int max, int value) {
        plane[at] = (short) Math.clamp(plane[at] + ((value + 32) >> 6), 0, max);
    }

    /**
     * Applies the inverse 8x8 transform to a block of coefficients and adds the residual to the
     * prediction in a plane (ITU-T H.264 8.5.13.2 and 8.5.14).
     *
     * @param block the coefficients in raster order, left unchanged
     * @param offset the index of the first coefficient
     * @param plane the samples holding the prediction
     * @param at the index of the block's top-left sample
     * @param stride the row stride
     * @param max the largest sample value
     */
    void inverse8x8Add(int[] block, int offset, short[] plane, int at, int stride, int max) {
        var t = scratch;
        System.arraycopy(block, offset, t, 0, 64);
        for (var i = 0; i < 8; i++) {
            inverse8(t, i * 8, 1);
        }
        for (var j = 0; j < 8; j++) {
            inverse8(t, j, 8);
        }
        for (var y = 0; y < 8; y++) {
            for (var x = 0; x < 8; x++) {
                addSample(plane, at + y * stride + x, max, t[y * 8 + x]);
            }
        }
    }

    /**
     * Applies the one-dimensional inverse 8-point transform of ITU-T H.264 8.5.13.2 to eight values.
     *
     * @param v the values
     * @param o the index of the first value
     * @param s the distance between two values
     */
    private static void inverse8(int[] v, int o, int s) {
        var d0 = v[o];
        var d1 = v[o + s];
        var d2 = v[o + 2 * s];
        var d3 = v[o + 3 * s];
        var d4 = v[o + 4 * s];
        var d5 = v[o + 5 * s];
        var d6 = v[o + 6 * s];
        var d7 = v[o + 7 * s];
        var e0 = d0 + d4;
        var e1 = -d3 + d5 - d7 - (d7 >> 1);
        var e2 = d0 - d4;
        var e3 = d1 + d7 - d3 - (d3 >> 1);
        var e4 = (d2 >> 1) - d6;
        var e5 = -d1 + d7 + d5 + (d5 >> 1);
        var e6 = d2 + (d6 >> 1);
        var e7 = d3 + d5 + d1 + (d1 >> 1);
        var f0 = e0 + e6;
        var f1 = e1 + (e7 >> 2);
        var f2 = e2 + e4;
        var f3 = e3 + (e5 >> 2);
        var f4 = e2 - e4;
        var f5 = (e3 >> 2) - e5;
        var f6 = e0 - e6;
        var f7 = e7 - (e1 >> 2);
        v[o] = f0 + f7;
        v[o + s] = f2 + f5;
        v[o + 2 * s] = f4 + f3;
        v[o + 3 * s] = f6 + f1;
        v[o + 4 * s] = f6 - f1;
        v[o + 5 * s] = f4 - f3;
        v[o + 6 * s] = f2 - f5;
        v[o + 7 * s] = f0 - f7;
    }
}
