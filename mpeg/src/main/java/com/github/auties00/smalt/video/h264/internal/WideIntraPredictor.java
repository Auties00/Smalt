package com.github.auties00.smalt.video.h264.internal;

/**
 * Computes H.264 intra prediction (ITU-T H.264 8.3) on planes of 16-bit samples, for pictures
 * deeper than 8 bits: the nine Intra_4x4 and Intra_8x8 modes (with the 8x8 reference sample
 * filtering), the four Intra_16x16 modes and the four chroma modes.
 *
 * <p>Predictions read their reference samples directly from the picture plane being decoded
 * and write the prediction into it. Reference samples marked unavailable are never read: they
 * are substituted as the standard specifies, or with the mid-gray value
 * {@code 1 << (bitDepth - 1)} for modes that a conforming stream would not use without them.
 */
final class WideIntraPredictor {
    /**
     * The top reference samples {@code p[x, -1]}, {@code x} from 0 to 15.
     */
    private final int[] top = new int[16];

    /**
     * The left reference samples {@code p[-1, y]}, {@code y} from 0 to 15.
     */
    private final int[] left = new int[16];

    /**
     * The filtered top reference samples of Intra_8x8 prediction.
     */
    private final int[] filteredTop = new int[16];

    /**
     * The filtered left reference samples of Intra_8x8 prediction.
     */
    private final int[] filteredLeft = new int[8];

    /**
     * The mid-gray luma value, {@code 1 << (BitDepthY - 1)}.
     */
    private int lumaGray = 512;

    /**
     * The largest luma value, {@code (1 << BitDepthY) - 1}.
     */
    private int lumaMax = 1023;

    /**
     * The mid-gray chroma value, {@code 1 << (BitDepthC - 1)}.
     */
    private int chromaGray = 512;

    /**
     * The largest chroma value, {@code (1 << BitDepthC) - 1}.
     */
    private int chromaMax = 1023;

    /**
     * Constructs a predictor with its own reference sample buffers.
     */
    WideIntraPredictor() {
    }

    /**
     * Sets the bit depths of the pictures predicted next.
     *
     * @param bitDepthLuma the luma bit depth, 8 to 14
     * @param bitDepthChroma the chroma bit depth, 8 to 14
     */
    void setBitDepths(int bitDepthLuma, int bitDepthChroma) {
        lumaGray = 1 << (bitDepthLuma - 1);
        lumaMax = (1 << bitDepthLuma) - 1;
        chromaGray = 1 << (bitDepthChroma - 1);
        chromaMax = (1 << bitDepthChroma) - 1;
    }

    /**
     * Predicts one 4x4 luma block (ITU-T H.264 8.3.1.2).
     *
     * @param mode the Intra4x4PredMode, 0 to 8
     * @param plane the luma plane
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @param hasLeft whether {@code p[-1, y]} are available
     * @param hasTop whether {@code p[x, -1]} for {@code x} 0 to 3 are available
     * @param hasTopLeft whether {@code p[-1, -1]} is available
     * @param hasTopRight whether {@code p[x, -1]} for {@code x} 4 to 7 are available
     */
    void predict4x4(int mode, short[] plane, int offset, int stride, boolean hasLeft, boolean hasTop,
                    boolean hasTopLeft, boolean hasTopRight) {
        var top = this.top;
        var left = this.left;
        var above = offset - stride;
        if (hasTop) {
            for (var x = 0; x < 4; x++) {
                top[x] = plane[above + x];
            }
            if (hasTopRight) {
                for (var x = 4; x < 8; x++) {
                    top[x] = plane[above + x];
                }
            } else {
                for (var x = 4; x < 8; x++) {
                    top[x] = top[3];
                }
            }
        } else {
            for (var x = 0; x < 8; x++) {
                top[x] = lumaGray;
            }
        }
        if (hasLeft) {
            for (var y = 0; y < 4; y++) {
                left[y] = plane[offset + y * stride - 1];
            }
        } else {
            for (var y = 0; y < 4; y++) {
                left[y] = lumaGray;
            }
        }
        var topLeft = hasTopLeft ? plane[above - 1] : lumaGray;
        switch (mode) {
            case 0 -> {
                for (var y = 0; y < 4; y++) {
                    var row = offset + y * stride;
                    for (var x = 0; x < 4; x++) {
                        plane[row + x] = (short) top[x];
                    }
                }
            }
            case 1 -> {
                for (var y = 0; y < 4; y++) {
                    var row = offset + y * stride;
                    var value = (short) left[y];
                    plane[row] = value;
                    plane[row + 1] = value;
                    plane[row + 2] = value;
                    plane[row + 3] = value;
                }
            }
            case 2 -> {
                int dc;
                if (hasTop && hasLeft) {
                    dc = (top[0] + top[1] + top[2] + top[3] + left[0] + left[1] + left[2] + left[3] + 4) >> 3;
                } else if (hasLeft) {
                    dc = (left[0] + left[1] + left[2] + left[3] + 2) >> 2;
                } else if (hasTop) {
                    dc = (top[0] + top[1] + top[2] + top[3] + 2) >> 2;
                } else {
                    dc = lumaGray;
                }
                fill(plane, offset, stride, 4, dc);
            }
            default -> directional(mode, 4, plane, offset, stride, top, left, topLeft);
        }
    }

    /**
     * Fills a square block with one value.
     *
     * @param plane the samples receiving the prediction
     * @param offset the index of the top-left sample
     * @param stride the row stride
     * @param size the block size
     * @param value the sample value
     */
    private static void fill(short[] plane, int offset, int stride, int size, int value) {
        var sample = (short) value;
        for (var y = 0; y < size; y++) {
            var row = offset + y * stride;
            for (var x = 0; x < size; x++) {
                plane[row + x] = sample;
            }
        }
    }

    /**
     * Computes the six directional modes (3 to 8) shared by Intra_4x4 and Intra_8x8 prediction.
     *
     * @param mode the prediction mode, 3 to 8
     * @param size the block size, 4 or 8
     * @param plane the samples receiving the prediction
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @param top the top references {@code p[x, -1]}, {@code x} from 0 to {@code 2 * size - 1}
     * @param left the left references {@code p[-1, y]}, {@code y} from 0 to {@code size - 1}
     * @param topLeft the corner reference {@code p[-1, -1]}
     */
    private static void directional(int mode, int size, short[] plane, int offset, int stride, int[] top, int[] left,
                                    int topLeft) {
        var last = size - 1;
        for (var y = 0; y < size; y++) {
            var row = offset + y * stride;
            for (var x = 0; x < size; x++) {
                int value;
                switch (mode) {
                    case 3 -> {
                        if (x == last && y == last) {
                            value = (top[2 * size - 2] + 3 * top[2 * size - 1] + 2) >> 2;
                        } else {
                            value = (top[x + y] + 2 * top[x + y + 1] + top[x + y + 2] + 2) >> 2;
                        }
                    }
                    case 4 -> {
                        if (x > y) {
                            value = (sampleTop(top, topLeft, x - y - 2) + 2 * sampleTop(top, topLeft, x - y - 1)
                                    + top[x - y] + 2) >> 2;
                        } else if (x < y) {
                            value = (sampleLeft(left, topLeft, y - x - 2) + 2 * sampleLeft(left, topLeft, y - x - 1)
                                    + left[y - x] + 2) >> 2;
                        } else {
                            value = (top[0] + 2 * topLeft + left[0] + 2) >> 2;
                        }
                    }
                    case 5 -> {
                        var z = 2 * x - y;
                        if (z >= 0 && (z & 1) == 0) {
                            value = (sampleTop(top, topLeft, x - (y >> 1) - 1) + top[x - (y >> 1)] + 1) >> 1;
                        } else if (z >= 0) {
                            value = (sampleTop(top, topLeft, x - (y >> 1) - 2) + 2 * sampleTop(top, topLeft, x - (y >> 1) - 1)
                                    + top[x - (y >> 1)] + 2) >> 2;
                        } else if (z == -1) {
                            value = (left[0] + 2 * topLeft + top[0] + 2) >> 2;
                        } else {
                            value = (sampleLeft(left, topLeft, y - 2 * x - 1) + 2 * sampleLeft(left, topLeft, y - 2 * x - 2)
                                    + sampleLeft(left, topLeft, y - 2 * x - 3) + 2) >> 2;
                        }
                    }
                    case 6 -> {
                        var z = 2 * y - x;
                        if (z >= 0 && (z & 1) == 0) {
                            value = (sampleLeft(left, topLeft, y - (x >> 1) - 1) + left[y - (x >> 1)] + 1) >> 1;
                        } else if (z >= 0) {
                            value = (sampleLeft(left, topLeft, y - (x >> 1) - 2) + 2 * sampleLeft(left, topLeft, y - (x >> 1) - 1)
                                    + left[y - (x >> 1)] + 2) >> 2;
                        } else if (z == -1) {
                            value = (left[0] + 2 * topLeft + top[0] + 2) >> 2;
                        } else {
                            value = (sampleTop(top, topLeft, x - 2 * y - 1) + 2 * sampleTop(top, topLeft, x - 2 * y - 2)
                                    + sampleTop(top, topLeft, x - 2 * y - 3) + 2) >> 2;
                        }
                    }
                    case 7 -> {
                        var i = x + (y >> 1);
                        if ((y & 1) == 0) {
                            value = (top[i] + top[i + 1] + 1) >> 1;
                        } else {
                            value = (top[i] + 2 * top[i + 1] + top[i + 2] + 2) >> 2;
                        }
                    }
                    default -> {
                        var z = x + 2 * y;
                        var limit = 2 * size - 3;
                        var i = y + (x >> 1);
                        if (z < limit && (z & 1) == 0) {
                            value = (left[i] + left[i + 1] + 1) >> 1;
                        } else if (z < limit) {
                            value = (left[i] + 2 * left[i + 1] + left[i + 2] + 2) >> 2;
                        } else if (z == limit) {
                            value = (left[size - 2] + 3 * left[size - 1] + 2) >> 2;
                        } else {
                            value = left[size - 1];
                        }
                    }
                }
                plane[row + x] = (short) value;
            }
        }
    }

    /**
     * Returns {@code p[x, -1]}, where {@code x = -1} is the corner sample.
     *
     * @param top the top references
     * @param topLeft the corner reference
     * @param x the horizontal position, from -1
     * @return the reference sample
     */
    private static int sampleTop(int[] top, int topLeft, int x) {
        return x < 0 ? topLeft : top[x];
    }

    /**
     * Returns {@code p[-1, y]}, where {@code y = -1} is the corner sample.
     *
     * @param left the left references
     * @param topLeft the corner reference
     * @param y the vertical position, from -1
     * @return the reference sample
     */
    private static int sampleLeft(int[] left, int topLeft, int y) {
        return y < 0 ? topLeft : left[y];
    }

    /**
     * Predicts one 8x8 luma block, filtering the reference samples first (ITU-T H.264 8.3.2.2).
     *
     * @param mode the Intra8x8PredMode, 0 to 8
     * @param plane the luma plane
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @param hasLeft whether {@code p[-1, y]} are available
     * @param hasTop whether {@code p[x, -1]} for {@code x} 0 to 7 are available
     * @param hasTopLeft whether {@code p[-1, -1]} is available
     * @param hasTopRight whether {@code p[x, -1]} for {@code x} 8 to 15 are available
     */
    void predict8x8(int mode, short[] plane, int offset, int stride, boolean hasLeft, boolean hasTop,
                    boolean hasTopLeft, boolean hasTopRight) {
        var top = this.top;
        var left = this.left;
        var above = offset - stride;
        if (hasTop) {
            for (var x = 0; x < 8; x++) {
                top[x] = plane[above + x];
            }
            if (hasTopRight) {
                for (var x = 8; x < 16; x++) {
                    top[x] = plane[above + x];
                }
            } else {
                for (var x = 8; x < 16; x++) {
                    top[x] = top[7];
                }
            }
        }
        if (hasLeft) {
            for (var y = 0; y < 8; y++) {
                left[y] = plane[offset + y * stride - 1];
            }
        }
        var topLeft = hasTopLeft ? plane[above - 1] : lumaGray;
        var ft = filteredTop;
        var fl = filteredLeft;
        if (hasTop) {
            ft[0] = hasTopLeft ? (topLeft + 2 * top[0] + top[1] + 2) >> 2 : (3 * top[0] + top[1] + 2) >> 2;
            for (var x = 1; x < 15; x++) {
                ft[x] = (top[x - 1] + 2 * top[x] + top[x + 1] + 2) >> 2;
            }
            ft[15] = (top[14] + 3 * top[15] + 2) >> 2;
        } else {
            for (var x = 0; x < 16; x++) {
                ft[x] = lumaGray;
            }
        }
        var filteredTopLeft = lumaGray;
        if (hasTopLeft) {
            if (!hasTop || !hasLeft) {
                if (hasTop) {
                    filteredTopLeft = (3 * topLeft + top[0] + 2) >> 2;
                } else if (hasLeft) {
                    filteredTopLeft = (3 * topLeft + left[0] + 2) >> 2;
                } else {
                    filteredTopLeft = topLeft;
                }
            } else {
                filteredTopLeft = (top[0] + 2 * topLeft + left[0] + 2) >> 2;
            }
        }
        if (hasLeft) {
            fl[0] = hasTopLeft ? (topLeft + 2 * left[0] + left[1] + 2) >> 2 : (3 * left[0] + left[1] + 2) >> 2;
            for (var y = 1; y < 7; y++) {
                fl[y] = (left[y - 1] + 2 * left[y] + left[y + 1] + 2) >> 2;
            }
            fl[7] = (left[6] + 3 * left[7] + 2) >> 2;
        } else {
            for (var y = 0; y < 8; y++) {
                fl[y] = lumaGray;
            }
        }
        switch (mode) {
            case 0 -> {
                for (var y = 0; y < 8; y++) {
                    var row = offset + y * stride;
                    for (var x = 0; x < 8; x++) {
                        plane[row + x] = (short) ft[x];
                    }
                }
            }
            case 1 -> {
                for (var y = 0; y < 8; y++) {
                    var row = offset + y * stride;
                    var value = (short) fl[y];
                    for (var x = 0; x < 8; x++) {
                        plane[row + x] = value;
                    }
                }
            }
            case 2 -> {
                int dc;
                if (hasTop && hasLeft) {
                    var sum = 8;
                    for (var i = 0; i < 8; i++) {
                        sum += ft[i] + fl[i];
                    }
                    dc = sum >> 4;
                } else if (hasLeft) {
                    var sum = 4;
                    for (var i = 0; i < 8; i++) {
                        sum += fl[i];
                    }
                    dc = sum >> 3;
                } else if (hasTop) {
                    var sum = 4;
                    for (var i = 0; i < 8; i++) {
                        sum += ft[i];
                    }
                    dc = sum >> 3;
                } else {
                    dc = lumaGray;
                }
                fill(plane, offset, stride, 8, dc);
            }
            default -> directional(mode, 8, plane, offset, stride, ft, fl, filteredTopLeft);
        }
    }

    /**
     * Predicts a 16x16 luma macroblock (ITU-T H.264 8.3.3).
     *
     * @param mode the Intra16x16PredMode, 0 to 3
     * @param plane the luma plane
     * @param offset the index of the macroblock's top-left sample
     * @param stride the row stride
     * @param hasLeft whether the left references are available
     * @param hasTop whether the top references are available
     * @param hasTopLeft whether the corner reference is available
     */
    void predict16x16(int mode, short[] plane, int offset, int stride, boolean hasLeft, boolean hasTop,
                      boolean hasTopLeft) {
        var above = offset - stride;
        switch (mode) {
            case 0 -> {
                for (var y = 0; y < 16; y++) {
                    System.arraycopy(plane, above, plane, offset + y * stride, 16);
                }
            }
            case 1 -> {
                for (var y = 0; y < 16; y++) {
                    var row = offset + y * stride;
                    var value = plane[row - 1];
                    for (var x = 0; x < 16; x++) {
                        plane[row + x] = value;
                    }
                }
            }
            case 2 -> {
                var sum = 0;
                int dc;
                if (hasTop && hasLeft) {
                    for (var i = 0; i < 16; i++) {
                        sum += plane[above + i] + plane[offset + i * stride - 1];
                    }
                    dc = (sum + 16) >> 5;
                } else if (hasLeft) {
                    for (var i = 0; i < 16; i++) {
                        sum += plane[offset + i * stride - 1];
                    }
                    dc = (sum + 8) >> 4;
                } else if (hasTop) {
                    for (var i = 0; i < 16; i++) {
                        sum += plane[above + i];
                    }
                    dc = (sum + 8) >> 4;
                } else {
                    dc = lumaGray;
                }
                fill(plane, offset, stride, 16, dc);
            }
            default -> {
                if (!hasTop || !hasLeft || !hasTopLeft) {
                    fill(plane, offset, stride, 16, lumaGray);
                    return;
                }
                var h = 0;
                var v = 0;
                for (var i = 0; i < 8; i++) {
                    h += (i + 1) * (plane[above + 8 + i] - plane[above + 6 - i]);
                    v += (i + 1) * (plane[offset + (8 + i) * stride - 1] - plane[offset + (6 - i) * stride - 1]);
                }
                var a = 16 * (plane[offset + 15 * stride - 1] + plane[above + 15]);
                var b = (5 * h + 32) >> 6;
                var c = (5 * v + 32) >> 6;
                for (var y = 0; y < 16; y++) {
                    var row = offset + y * stride;
                    var base = a + c * (y - 7) + 16 - 7 * b;
                    for (var x = 0; x < 16; x++) {
                        plane[row + x] = (short) H264WideDsp.clip((base + b * x) >> 5, lumaMax);
                    }
                }
            }
        }
    }

    /**
     * Predicts the 8x8 (4:2:0) or 8x16 (4:2:2) chroma block of a macroblock (ITU-T H.264
     * 8.3.4).
     *
     * @param mode the intra_chroma_pred_mode, 0 to 3
     * @param plane the chroma plane
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @param rows the block height {@code MbHeightC}, 8 or 16
     * @param leftBlocks the 4-row groups whose left references are available, bit {@code i} for
     * rows {@code 4i} to {@code 4i + 3}
     * @param hasTop whether the top references are available
     * @param hasTopLeft whether the corner reference is available
     */
    void predictChroma(int mode, short[] plane, int offset, int stride, int rows, int leftBlocks, boolean hasTop,
                       boolean hasTopLeft) {
        var above = offset - stride;
        var blockRows = rows >> 2;
        switch (mode) {
            case 0 -> {
                var sumTop0 = 0;
                var sumTop1 = 0;
                if (hasTop) {
                    for (var i = 0; i < 4; i++) {
                        sumTop0 += plane[above + i];
                        sumTop1 += plane[above + 4 + i];
                    }
                }
                for (var by = 0; by < blockRows; by++) {
                    var hasLeft = ((leftBlocks >> by) & 1) != 0;
                    var sumLeft = 0;
                    if (hasLeft) {
                        for (var i = 0; i < 4; i++) {
                            sumLeft += plane[offset + (by * 4 + i) * stride - 1];
                        }
                    }
                    for (var bx = 0; bx < 2; bx++) {
                        var sumTop = bx == 0 ? sumTop0 : sumTop1;
                        int dc;
                        if ((bx == 0) == (by == 0)) {
                            if (hasTop && hasLeft) {
                                dc = (sumTop + sumLeft + 4) >> 3;
                            } else if (hasLeft) {
                                dc = (sumLeft + 2) >> 2;
                            } else if (hasTop) {
                                dc = (sumTop + 2) >> 2;
                            } else {
                                dc = chromaGray;
                            }
                        } else if (bx == 1) {
                            if (hasTop) {
                                dc = (sumTop + 2) >> 2;
                            } else if (hasLeft) {
                                dc = (sumLeft + 2) >> 2;
                            } else {
                                dc = chromaGray;
                            }
                        } else {
                            if (hasLeft) {
                                dc = (sumLeft + 2) >> 2;
                            } else if (hasTop) {
                                dc = (sumTop + 2) >> 2;
                            } else {
                                dc = chromaGray;
                            }
                        }
                        fill(plane, offset + by * 4 * stride + bx * 4, stride, 4, dc);
                    }
                }
            }
            case 1 -> {
                for (var y = 0; y < rows; y++) {
                    var row = offset + y * stride;
                    var value = plane[row - 1];
                    for (var x = 0; x < 8; x++) {
                        plane[row + x] = value;
                    }
                }
            }
            case 2 -> {
                for (var y = 0; y < rows; y++) {
                    System.arraycopy(plane, above, plane, offset + y * stride, 8);
                }
            }
            default -> {
                if (!hasTop || leftBlocks != (1 << blockRows) - 1 || !hasTopLeft) {
                    for (var y = 0; y < rows; y += 8) {
                        fill(plane, offset + y * stride, stride, 8, chromaGray);
                    }
                    return;
                }
                var yCF = rows == 16 ? 4 : 0;
                var h = 0;
                var v = 0;
                for (var i = 0; i < 4; i++) {
                    h += (i + 1) * (plane[above + 4 + i] - plane[above + 2 - i]);
                }
                for (var i = 0; i < 4 + yCF; i++) {
                    v += (i + 1) * (plane[offset + (4 + yCF + i) * stride - 1]
                            - plane[offset + (2 + yCF - i) * stride - 1]);
                }
                var a = 16 * (plane[offset + (rows - 1) * stride - 1] + plane[above + 7]);
                var b = (34 * h + 32) >> 6;
                var c = ((yCF != 0 ? 5 : 34) * v + 32) >> 6;
                for (var y = 0; y < rows; y++) {
                    var row = offset + y * stride;
                    var start = a + c * (y - 3 - yCF) + 16 - 3 * b;
                    for (var x = 0; x < 8; x++) {
                        plane[row + x] = (short) H264WideDsp.clip((start + b * x) >> 5, chromaMax);
                    }
                }
            }
        }
    }
}
