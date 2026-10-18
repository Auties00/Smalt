package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.internal.io.ByteArrays;

import java.util.Arrays;

/**
 * Forms the intra predictions of clause 8.3 from the constructed samples around a block.
 *
 * <p>Neighboring samples are read from a picture plane at fixed offsets from the block's
 * top-left sample; the caller states which neighbors are available (inside the picture and the
 * same slice). The prediction is written to a destination block, which may be the block itself
 * because every neighbor is read before the first sample is written.
 */
final class IntraPredictor {
    /**
     * The vertical mode of every intra block size.
     */
    static final int VERTICAL = 0;

    /**
     * The horizontal mode of every intra block size.
     */
    static final int HORIZONTAL = 1;

    /**
     * The DC mode of 4x4, 8x8 and 16x16 luma prediction.
     */
    static final int DC = 2;

    /**
     * The plane mode of 16x16 luma prediction.
     */
    static final int PLANE = 3;

    /**
     * The DC mode of chroma prediction.
     */
    static final int CHROMA_DC = 0;

    /**
     * The horizontal mode of chroma prediction.
     */
    static final int CHROMA_HORIZONTAL = 1;

    /**
     * The vertical mode of chroma prediction.
     */
    static final int CHROMA_VERTICAL = 2;

    /**
     * The plane mode of chroma prediction.
     */
    static final int CHROMA_PLANE = 3;

    /**
     * The top neighbors {@code p[x, -1]}, index {@code x + 1}, so index 0 holds the top-left
     * corner.
     */
    private final int[] top = new int[17];

    /**
     * The left neighbors {@code p[-1, y]}, index {@code y + 1}, so index 0 holds the top-left
     * corner.
     */
    private final int[] left = new int[17];

    /**
     * The filtered top neighbors of 8x8 prediction, laid out like {@link #top}.
     */
    private final int[] filteredTop = new int[17];

    /**
     * The filtered left neighbors of 8x8 prediction, laid out like {@link #left}.
     */
    private final int[] filteredLeft = new int[9];

    /**
     * The pixel kernels the plane predictions are computed with.
     */
    private PixelKernels pixels = Kernels.SCALAR_PIXELS;

    /**
     * Constructs a predictor with its own scratch buffers; instances are not thread-safe.
     */
    IntraPredictor() {
    }

    /**
     * Replaces the kernels the plane predictions are computed with, between two pictures.
     *
     * @param pixels the pixel kernels
     */
    void useKernels(PixelKernels pixels) {
        this.pixels = pixels;
    }

    /**
     * Returns whether a 4x4 or 8x8 luma mode can be used with the given neighbors.
     *
     * @param mode the prediction mode, from 0 to 8
     * @param hasTop whether the samples above are available
     * @param hasLeft whether the samples to the left are available
     * @param hasTopLeft whether the sample above-left is available
     * @return whether the mode's inputs are available
     */
    static boolean isAvailableNxN(int mode, boolean hasTop, boolean hasLeft, boolean hasTopLeft) {
        return switch (mode) {
            case 0, 3, 7 -> hasTop;
            case 1, 8 -> hasLeft;
            case 2 -> true;
            default -> hasTop && hasLeft && hasTopLeft;
        };
    }

    /**
     * Returns whether a 16x16 luma or chroma mode can be used with the given neighbors.
     *
     * @param mode the prediction mode in the numbering of 16x16 luma prediction
     * @param hasTop whether the samples above are available
     * @param hasLeft whether the samples to the left are available
     * @param hasTopLeft whether the sample above-left is available
     * @return whether the mode's inputs are available
     */
    static boolean isAvailable16x16(int mode, boolean hasTop, boolean hasLeft, boolean hasTopLeft) {
        return switch (mode) {
            case VERTICAL -> hasTop;
            case HORIZONTAL -> hasLeft;
            case DC -> true;
            case PLANE -> hasTop && hasLeft && hasTopLeft;
            default -> throw new IllegalArgumentException("Intra_16x16 mode " + mode);
        };
    }

    /**
     * Writes an Intra_16x16 prediction (clause 8.3.3).
     *
     * @param mode the mode: {@link #VERTICAL}, {@link #HORIZONTAL}, {@link #DC} or
     * {@link #PLANE}
     * @param plane the constructed luma samples
     * @param offset the index of the macroblock's top-left sample
     * @param stride the plane row stride
     * @param hasTop whether the macroblock above is available
     * @param hasLeft whether the macroblock to the left is available
     * @param dst the destination samples
     * @param dstOffset the index of the destination's top-left sample
     * @param dstStride the destination row stride
     */
    void predict16x16(int mode, byte[] plane, int offset, int stride, boolean hasTop, boolean hasLeft, byte[] dst,
                      int dstOffset, int dstStride) {
        switch (mode) {
            case VERTICAL -> {
                for (var y = 0; y < 16; y++) {
                    System.arraycopy(plane, offset - stride, dst, dstOffset + y * dstStride, 16);
                }
            }
            case HORIZONTAL -> {
                for (var y = 0; y < 16; y++) {
                    var d = dstOffset + y * dstStride;
                    fillRow(dst, d, 16, plane[offset + y * stride - 1]);
                }
            }
            case DC -> {
                var sum = 0;
                int value;
                if (hasTop && hasLeft) {
                    for (var i = 0; i < 16; i++) {
                        sum += (plane[offset - stride + i] & 0xFF) + (plane[offset + i * stride - 1] & 0xFF);
                    }
                    value = (sum + 16) >> 5;
                } else if (hasLeft) {
                    for (var i = 0; i < 16; i++) {
                        sum += plane[offset + i * stride - 1] & 0xFF;
                    }
                    value = (sum + 8) >> 4;
                } else if (hasTop) {
                    for (var i = 0; i < 16; i++) {
                        sum += plane[offset - stride + i] & 0xFF;
                    }
                    value = (sum + 8) >> 4;
                } else {
                    value = 128;
                }
                fill(dst, dstOffset, dstStride, 16, 16, value);
            }
            case PLANE -> {
                var t = offset - stride;
                var h = 0;
                var v = 0;
                for (var i = 0; i < 8; i++) {
                    h += (i + 1) * ((plane[t + 8 + i] & 0xFF) - (plane[t + 6 - i] & 0xFF));
                    v += (i + 1) * ((plane[offset + (8 + i) * stride - 1] & 0xFF)
                            - (plane[offset + (6 - i) * stride - 1] & 0xFF));
                }
                var a = 16 * ((plane[offset + 15 * stride - 1] & 0xFF) + (plane[t + 15] & 0xFF));
                var b = (5 * h + 32) >> 6;
                var c = (5 * v + 32) >> 6;
                pixels.planePrediction(dst, dstOffset, dstStride, 16, a, b, c, 7);
            }
        }
    }

    /**
     * Writes an 8x8 chroma prediction of a 4:2:0 macroblock (clause 8.3.4).
     *
     * @param mode the mode: {@link #CHROMA_DC}, {@link #CHROMA_HORIZONTAL},
     * {@link #CHROMA_VERTICAL} or {@link #CHROMA_PLANE}
     * @param plane the constructed chroma samples
     * @param offset the index of the macroblock's top-left chroma sample
     * @param stride the plane row stride
     * @param hasTop whether the macroblock above is available
     * @param hasLeft whether the macroblock to the left is available
     * @param dst the destination samples
     * @param dstOffset the index of the destination's top-left sample
     * @param dstStride the destination row stride
     */
    void predictChroma(int mode, byte[] plane, int offset, int stride, boolean hasTop, boolean hasLeft, byte[] dst,
                       int dstOffset, int dstStride) {
        switch (mode) {
            case CHROMA_VERTICAL -> {
                for (var y = 0; y < 8; y++) {
                    System.arraycopy(plane, offset - stride, dst, dstOffset + y * dstStride, 8);
                }
            }
            case CHROMA_HORIZONTAL -> {
                for (var y = 0; y < 8; y++) {
                    var d = dstOffset + y * dstStride;
                    fillRow(dst, d, 8, plane[offset + y * stride - 1]);
                }
            }
            case CHROMA_DC -> {
                for (var block = 0; block < 4; block++) {
                    var xo = (block & 1) * 4;
                    var yo = (block >> 1) * 4;
                    var sumTop = 0;
                    var sumLeft = 0;
                    if (hasTop) {
                        for (var i = 0; i < 4; i++) {
                            sumTop += plane[offset - stride + xo + i] & 0xFF;
                        }
                    }
                    if (hasLeft) {
                        for (var i = 0; i < 4; i++) {
                            sumLeft += plane[offset + (yo + i) * stride - 1] & 0xFF;
                        }
                    }
                    int value;
                    if (xo == yo) {
                        if (hasTop && hasLeft) {
                            value = (sumTop + sumLeft + 4) >> 3;
                        } else if (hasLeft) {
                            value = (sumLeft + 2) >> 2;
                        } else if (hasTop) {
                            value = (sumTop + 2) >> 2;
                        } else {
                            value = 128;
                        }
                    } else if (xo > 0) {
                        if (hasTop) {
                            value = (sumTop + 2) >> 2;
                        } else if (hasLeft) {
                            value = (sumLeft + 2) >> 2;
                        } else {
                            value = 128;
                        }
                    } else {
                        if (hasLeft) {
                            value = (sumLeft + 2) >> 2;
                        } else if (hasTop) {
                            value = (sumTop + 2) >> 2;
                        } else {
                            value = 128;
                        }
                    }
                    fill(dst, dstOffset + yo * dstStride + xo, dstStride, 4, 4, value);
                }
            }
            default -> {
                var t = offset - stride;
                var h = 0;
                var v = 0;
                for (var i = 0; i < 4; i++) {
                    h += (i + 1) * ((plane[t + 4 + i] & 0xFF) - (plane[t + 2 - i] & 0xFF));
                    v += (i + 1) * ((plane[offset + (4 + i) * stride - 1] & 0xFF)
                            - (plane[offset + (2 - i) * stride - 1] & 0xFF));
                }
                var a = 16 * ((plane[offset + 7 * stride - 1] & 0xFF) + (plane[t + 7] & 0xFF));
                var b = (34 * h + 32) >> 6;
                var c = (34 * v + 32) >> 6;
                pixels.planePrediction(dst, dstOffset, dstStride, 8, a, b, c, 3);
            }
        }
    }

    /**
     * Writes an Intra_4x4 prediction (clause 8.3.1.2).
     *
     * @param mode the mode, from 0 to 8
     * @param plane the constructed luma samples
     * @param offset the index of the block's top-left sample
     * @param stride the plane row stride
     * @param hasTop whether the samples above are available
     * @param hasTopRight whether the samples above-right are available
     * @param hasLeft whether the samples to the left are available
     * @param hasTopLeft whether the sample above-left is available
     * @param dst the destination samples
     * @param dstOffset the index of the destination's top-left sample
     * @param dstStride the destination row stride
     */
    void predict4x4(int mode, byte[] plane, int offset, int stride, boolean hasTop, boolean hasTopRight,
                    boolean hasLeft, boolean hasTopLeft, byte[] dst, int dstOffset, int dstStride) {
        var p = top;
        var l = left;
        if (hasTop) {
            var t = offset - stride;
            for (var i = 0; i < 4; i++) {
                p[i + 1] = plane[t + i] & 0xFF;
            }
            if (hasTopRight) {
                for (var i = 4; i < 8; i++) {
                    p[i + 1] = plane[t + i] & 0xFF;
                }
            } else {
                for (var i = 4; i < 8; i++) {
                    p[i + 1] = p[4];
                }
            }
        }
        if (hasLeft) {
            for (var i = 0; i < 4; i++) {
                l[i + 1] = plane[offset + i * stride - 1] & 0xFF;
            }
        }
        if (hasTopLeft) {
            p[0] = plane[offset - stride - 1] & 0xFF;
            l[0] = p[0];
        }
        predictNxN(mode, 4, p, l, hasTop, hasLeft, dst, dstOffset, dstStride);
    }

    /**
     * Writes an Intra_8x8 prediction, filtering the reference samples first (clause 8.3.2).
     *
     * @param mode the mode, from 0 to 8
     * @param plane the constructed luma samples
     * @param offset the index of the block's top-left sample
     * @param stride the plane row stride
     * @param hasTop whether the samples above are available
     * @param hasTopRight whether the samples above-right are available
     * @param hasLeft whether the samples to the left are available
     * @param hasTopLeft whether the sample above-left is available
     * @param dst the destination samples
     * @param dstOffset the index of the destination's top-left sample
     * @param dstStride the destination row stride
     */
    void predict8x8(int mode, byte[] plane, int offset, int stride, boolean hasTop, boolean hasTopRight,
                    boolean hasLeft, boolean hasTopLeft, byte[] dst, int dstOffset, int dstStride) {
        var p = top;
        var l = left;
        if (hasTop) {
            var t = offset - stride;
            for (var i = 0; i < 8; i++) {
                p[i + 1] = plane[t + i] & 0xFF;
            }
            if (hasTopRight) {
                for (var i = 8; i < 16; i++) {
                    p[i + 1] = plane[t + i] & 0xFF;
                }
            } else {
                for (var i = 8; i < 16; i++) {
                    p[i + 1] = p[8];
                }
            }
        }
        if (hasLeft) {
            for (var i = 0; i < 8; i++) {
                l[i + 1] = plane[offset + i * stride - 1] & 0xFF;
            }
        }
        var corner = hasTopLeft ? plane[offset - stride - 1] & 0xFF : 0;
        var fp = filteredTop;
        var fl = filteredLeft;
        if (hasTop) {
            fp[1] = hasTopLeft ? (corner + 2 * p[1] + p[2] + 2) >> 2 : (3 * p[1] + p[2] + 2) >> 2;
            for (var x = 1; x < 15; x++) {
                fp[x + 1] = (p[x] + 2 * p[x + 1] + p[x + 2] + 2) >> 2;
            }
            fp[16] = (p[15] + 3 * p[16] + 2) >> 2;
        }
        if (hasTopLeft) {
            if (!hasTop || !hasLeft) {
                if (hasTop) {
                    fp[0] = (3 * corner + p[1] + 2) >> 2;
                } else if (hasLeft) {
                    fp[0] = (3 * corner + l[1] + 2) >> 2;
                } else {
                    fp[0] = corner;
                }
            } else {
                fp[0] = (p[1] + 2 * corner + l[1] + 2) >> 2;
            }
            fl[0] = fp[0];
        }
        if (hasLeft) {
            fl[1] = hasTopLeft ? (corner + 2 * l[1] + l[2] + 2) >> 2 : (3 * l[1] + l[2] + 2) >> 2;
            for (var y = 1; y < 7; y++) {
                fl[y + 1] = (l[y] + 2 * l[y + 1] + l[y + 2] + 2) >> 2;
            }
            fl[8] = (l[7] + 3 * l[8] + 2) >> 2;
        }
        predictNxN(mode, 8, fp, fl, hasTop, hasLeft, dst, dstOffset, dstStride);
    }

    /**
     * Writes a 4x4 or 8x8 directional prediction from gathered reference samples.
     *
     * @param mode the mode, from 0 to 8
     * @param size the block size, 4 or 8
     * @param p the top references {@code p[x, -1]} at index {@code x + 1}, with the corner
     * at index 0
     * @param l the left references {@code p[-1, y]} at index {@code y + 1}, with the
     * corner at index 0
     * @param hasTop whether the top references are available
     * @param hasLeft whether the left references are available
     * @param dst the destination samples
     * @param dstOffset the index of the destination's top-left sample
     * @param dstStride the destination row stride
     */
    private static void predictNxN(int mode, int size, int[] p, int[] l, boolean hasTop, boolean hasLeft,
                                   byte[] dst, int dstOffset, int dstStride) {
        var last = size - 1;
        switch (mode) {
            case 0 -> {
                for (var y = 0; y < size; y++) {
                    for (var x = 0; x < size; x++) {
                        dst[dstOffset + y * dstStride + x] = (byte) p[x + 1];
                    }
                }
            }
            case 1 -> {
                for (var y = 0; y < size; y++) {
                    var d = dstOffset + y * dstStride;
                    fillRow(dst, d, size, l[y + 1]);
                }
            }
            case 2 -> {
                var sum = 0;
                int value;
                var log = size == 4 ? 2 : 3;
                if (hasTop && hasLeft) {
                    for (var i = 1; i <= size; i++) {
                        sum += p[i] + l[i];
                    }
                    value = (sum + size) >> (log + 1);
                } else if (hasTop) {
                    for (var i = 1; i <= size; i++) {
                        sum += p[i];
                    }
                    value = (sum + (size >> 1)) >> log;
                } else if (hasLeft) {
                    for (var i = 1; i <= size; i++) {
                        sum += l[i];
                    }
                    value = (sum + (size >> 1)) >> log;
                } else {
                    value = 128;
                }
                fill(dst, dstOffset, dstStride, size, size, value);
            }
            case 3 -> {
                for (var y = 0; y < size; y++) {
                    for (var x = 0; x < size; x++) {
                        int value;
                        if (x == last && y == last) {
                            value = (p[2 * size - 1] + 3 * p[2 * size] + 2) >> 2;
                        } else {
                            value = (p[x + y + 1] + 2 * p[x + y + 2] + p[x + y + 3] + 2) >> 2;
                        }
                        dst[dstOffset + y * dstStride + x] = (byte) value;
                    }
                }
            }
            case 4 -> {
                for (var y = 0; y < size; y++) {
                    for (var x = 0; x < size; x++) {
                        int value;
                        if (x > y) {
                            value = (at(p, l, x - y - 2, -1) + 2 * at(p, l, x - y - 1, -1) + p[x - y + 1] + 2) >> 2;
                        } else if (x < y) {
                            value = (at(p, l, -1, y - x - 2) + 2 * at(p, l, -1, y - x - 1) + l[y - x + 1] + 2) >> 2;
                        } else {
                            value = (p[1] + 2 * p[0] + l[1] + 2) >> 2;
                        }
                        dst[dstOffset + y * dstStride + x] = (byte) value;
                    }
                }
            }
            case 5 -> {
                for (var y = 0; y < size; y++) {
                    for (var x = 0; x < size; x++) {
                        var z = 2 * x - y;
                        int value;
                        if (z >= 0 && (z & 1) == 0) {
                            var i = x - (y >> 1);
                            value = (at(p, l, i - 1, -1) + p[i + 1] + 1) >> 1;
                        } else if (z > 0) {
                            var i = x - (y >> 1);
                            value = (at(p, l, i - 2, -1) + 2 * at(p, l, i - 1, -1) + p[i + 1] + 2) >> 2;
                        } else if (z == -1) {
                            value = (l[1] + 2 * p[0] + p[1] + 2) >> 2;
                        } else {
                            var j = y - 2 * x;
                            value = (at(p, l, -1, j - 1) + 2 * at(p, l, -1, j - 2) + at(p, l, -1, j - 3) + 2) >> 2;
                        }
                        dst[dstOffset + y * dstStride + x] = (byte) value;
                    }
                }
            }
            case 6 -> {
                for (var y = 0; y < size; y++) {
                    for (var x = 0; x < size; x++) {
                        var z = 2 * y - x;
                        int value;
                        if (z >= 0 && (z & 1) == 0) {
                            var j = y - (x >> 1);
                            value = (at(p, l, -1, j - 1) + l[j + 1] + 1) >> 1;
                        } else if (z > 0) {
                            var j = y - (x >> 1);
                            value = (at(p, l, -1, j - 2) + 2 * at(p, l, -1, j - 1) + l[j + 1] + 2) >> 2;
                        } else if (z == -1) {
                            value = (l[1] + 2 * p[0] + p[1] + 2) >> 2;
                        } else {
                            var i = x - 2 * y;
                            value = (at(p, l, i - 1, -1) + 2 * at(p, l, i - 2, -1) + at(p, l, i - 3, -1) + 2) >> 2;
                        }
                        dst[dstOffset + y * dstStride + x] = (byte) value;
                    }
                }
            }
            case 7 -> {
                for (var y = 0; y < size; y++) {
                    for (var x = 0; x < size; x++) {
                        var i = x + (y >> 1);
                        int value;
                        if ((y & 1) == 0) {
                            value = (p[i + 1] + p[i + 2] + 1) >> 1;
                        } else {
                            value = (p[i + 1] + 2 * p[i + 2] + p[i + 3] + 2) >> 2;
                        }
                        dst[dstOffset + y * dstStride + x] = (byte) value;
                    }
                }
            }
            default -> {
                var limit = 2 * size - 3;
                for (var y = 0; y < size; y++) {
                    for (var x = 0; x < size; x++) {
                        var z = x + 2 * y;
                        var j = y + (x >> 1);
                        int value;
                        if (z < limit && (z & 1) == 0) {
                            value = (l[j + 1] + l[j + 2] + 1) >> 1;
                        } else if (z < limit) {
                            value = (l[j + 1] + 2 * l[j + 2] + l[j + 3] + 2) >> 2;
                        } else if (z == limit) {
                            value = (l[size - 1] + 3 * l[size] + 2) >> 2;
                        } else {
                            value = l[size];
                        }
                        dst[dstOffset + y * dstStride + x] = (byte) value;
                    }
                }
            }
        }
    }

    /**
     * Returns the reference sample {@code p[x, y]} for {@code x == -1} or {@code y == -1}.
     *
     * @param p the top references, corner at index 0
     * @param l the left references, corner at index 0
     * @param x the column, {@code -1} for the left references
     * @param y the row, {@code -1} for the top references
     * @return the sample value
     */
    private static int at(int[] p, int[] l, int x, int y) {
        return y < 0 ? p[x + 1] : l[y + 1];
    }

    /**
     * Fills a block with one value.
     *
     * @param dst the destination samples
     * @param offset the index of the top-left sample
     * @param stride the row stride
     * @param width the block width
     * @param height the block height
     * @param value the sample value
     */
    private static void fill(byte[] dst, int offset, int stride, int width, int height, int value) {
        for (var y = 0; y < height; y++) {
            fillRow(dst, offset + y * stride, width, value);
        }
    }

    /**
     * Sets a row of samples to one value.
     *
     * @implNote This implementation stores rows of 4, 8 and 16 samples as whole 32- and 64-bit
     * words, which costs a fraction of {@link Arrays#fill(byte[], int, int, byte)} on rows this
     * short.
     * @param dst the destination
     * @param offset the index of the first sample of the row
     * @param width the number of samples
     * @param value the sample value, of which only the low 8 bits are used
     */
    private static void fillRow(byte[] dst, int offset, int width, int value) {
        var word = (value & 0xFFL) * 0x0101010101010101L;
        switch (width) {
            case 4 -> ByteArrays.put32le(dst, offset, (int) word);
            case 8 -> ByteArrays.put64le(dst, offset, word);
            case 16 -> {
                ByteArrays.put64le(dst, offset, word);
                ByteArrays.put64le(dst, offset + 8, word);
            }
            default -> Arrays.fill(dst, offset, offset + width, (byte) value);
        }
    }
}
