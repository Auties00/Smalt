/* Ports VVdeC 3.3.0 (source/Lib/CommonLib/IntraPrediction.cpp and MatrixIntraPrediction.cpp: intra prediction).
 * Copyright (c) 2018-2026, Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors;
 * Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import java.util.Arrays;

/**
 * Predicts intra blocks (ITU-T H.266 8.4.5.2): planar, DC and the angular modes with wide-angle mapping, reference
 * line selection, reference smoothing and position-dependent filtering, matrix-based prediction and the
 * cross-component linear model modes.
 *
 * <p>Predicted samples are written into an {@code int} block in raster order. Instances are not thread-safe.
 */
final class VvcIntra {
    /**
     * {@code intraPredAngle} of each mode from -14 to 80, at index {@code mode + 14} (Table 8-8).
     */
    private static final int[] ANGLE = new int[95];
    /**
     * {@code invAngle} of each mode from -14 to 80, at index {@code mode + 14}; 0 where the angle is 0.
     */
    private static final int[] INV_ANGLE = new int[95];
    /**
     * {@code DivSigTable} of the cross-component linear model (8.4.5.2.14).
     */
    private static final int[] DIV_SIG = {0, 7, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1, 1, 1, 0};

    static {
        int[] table = {512, 341, 256, 171, 128, 102, 86, 73, 64, 57, 51, 45, 39, 35, 0, 0, 32, 29, 26, 23, 20, 18,
                16, 14, 12, 10, 8, 6, 4, 3, 2, 1, 0, -1, -2, -3, -4, -6, -8, -10, -12, -14, -16, -18, -20, -23, -26,
                -29, -32, -29, -26, -23, -20, -18, -16, -14, -12, -10, -8, -6, -4, -3, -2, -1, 0, 1, 2, 3, 4, 6, 8,
                10, 12, 14, 16, 18, 20, 23, 26, 29, 32, 35, 39, 45, 51, 57, 64, 73, 86, 102, 128, 171, 256, 341, 512};
        // index = mode + 14, modes -14..80
        for (var i = 0; i < 95; i++) {
            ANGLE[i] = table[i];
            if (table[i] != 0) {
                INV_ANGLE[i] = (int) StrictMath.round(512.0 * 32.0 / table[i]);
            }
        }
    }

    /**
     * The per picture maps the neighbors' availability is read from.
     */
    private final VvcFrame f;
    /**
     * The bit depth of the samples being predicted.
     */
    private int bitDepth;
    /**
     * The largest sample value, {@code (1 << bitDepth) - 1}.
     */
    private int maxVal;

    /**
     * The reference samples: {@code refLeft[1 + y]} is {@code p[-1-refIdx][y]} for y from -1-refIdx, {@code
     * refTop[1 + x]} is {@code p[x][-1-refIdx]}, both indexed from {@code -1 - refIdx}.
     */
    private final int[] left = new int[2 * 128 + 8];
    /**
     * The top reference samples, laid out as {@link #left}.
     */
    private final int[] top = new int[2 * 128 + 8];
    /**
     * The filtered left reference samples, laid out as {@link #left}.
     */
    private final int[] leftF = new int[2 * 128 + 8];
    /**
     * The filtered top reference samples, laid out as {@link #left}.
     */
    private final int[] topF = new int[2 * 128 + 8];
    /**
     * Whether each left reference sample is available, laid out as {@link #left}.
     */
    private final boolean[] availLeft = new boolean[2 * 128 + 8];
    /**
     * Whether each top reference sample is available, laid out as {@link #left}.
     */
    private final boolean[] availTop = new boolean[2 * 128 + 8];
    /**
     * The extended main reference of angular prediction, {@code ref[ x ]} at index {@code x + 512}.
     */
    private final int[] ref = new int[1024];
    /**
     * The reduced prediction of matrix-based intra prediction, before upsampling.
     */
    private final int[] mipTemp = new int[64];
    /**
     * The input vector of matrix-based intra prediction ({@code p}).
     */
    private final int[] mipP = new int[8];
    /**
     * The reduced top boundary of matrix-based intra prediction ({@code redT}).
     */
    private final int[] redT = new int[4];
    /**
     * The reduced left boundary of matrix-based intra prediction ({@code redL}).
     */
    private final int[] redL = new int[4];
    /**
     * The down-sampled collocated luma samples of a cross-component prediction block.
     */
    private final int[] lumaDs = new int[64 * 64];

    /**
     * Constructs the intra prediction of a picture size.
     *
     * @param frame the per picture maps
     */
    VvcIntra(VvcFrame frame) {
        this.f = frame;
    }

    /**
     * Sets the bit depth of the samples predicted next.
     *
     * @param bitDepth the bit depth
     */
    void setBitDepth(int bitDepth) {
        this.bitDepth = bitDepth;
        this.maxVal = (1 << bitDepth) - 1;
    }

    /**
     * Builds the reference samples {@code p[x][y]} of a block (8.4.5.2.8 and 8.4.5.2.9).
     *
     * @param plane the reconstructed samples of the component
     * @param stride the row stride of the plane, in samples
     * @param x0 the left column of the block, in component samples
     * @param y0 the top row of the block, in component samples
     * @param refW the number of reference samples above the block
     * @param refH the number of reference samples left of the block
     * @param refIdx the reference line, 0 to 2
     * @param c the component, 0 for luma
     * @param sx the horizontal subsampling shift of the component
     * @param sy the vertical subsampling shift of the component
     */
    private void buildReferences(short[] plane, int stride, int x0, int y0, int refW, int refH, int refIdx, int c,
                                 int sx, int sy) {
        var chroma = c > 0;
        var xCurY = x0 << sx;
        var yCurY = y0 << sy;
        var leftCount = refH + 1 + refIdx;
        var topCount = refW + refIdx;
        var any = false;
        var all = true;
        var xl = x0 - 1 - refIdx;
        var lastUnit = Integer.MIN_VALUE;
        var lastAvail = false;
        for (var i = 0; i < leftCount; i++) {
            var y = y0 - 1 - refIdx + i;
            var xn = xl << sx;
            var yn = y << sy;
            var unit = xn < 0 || yn < 0 ? -1 : ((yn >> 2) << 16) | (xn >> 2);
            boolean a;
            if (unit == lastUnit) {
                a = lastAvail;
            } else {
                a = unit >= 0 && f.sampleAvailable(xn, yn, xCurY, yCurY, chroma);
                lastUnit = unit;
                lastAvail = a;
            }
            availLeft[i] = a;
            if (a) {
                left[i] = plane[y * stride + xl];
                any = true;
            } else {
                all = false;
            }
        }
        var yt = y0 - 1 - refIdx;
        lastUnit = Integer.MIN_VALUE;
        for (var i = 0; i < topCount; i++) {
            var x = x0 - refIdx + i;
            var xn = x << sx;
            var yn = yt << sy;
            var unit = xn < 0 || yn < 0 ? -1 : ((yn >> 2) << 16) | (xn >> 2);
            boolean a;
            if (unit == lastUnit) {
                a = lastAvail;
            } else {
                a = unit >= 0 && f.sampleAvailable(xn, yn, xCurY, yCurY, chroma);
                lastUnit = unit;
                lastAvail = a;
            }
            availTop[i] = a;
            if (a) {
                top[i] = plane[yt * stride + x];
                any = true;
            } else {
                all = false;
            }
        }
        if (!any) {
            var mid = 1 << (bitDepth - 1);
            Arrays.fill(left, 0, leftCount, mid);
            Arrays.fill(top, 0, topCount, mid);
            return;
        }
        if (all) {
            return;
        }
        // substitution: the scan runs from the bottom of the left column up to the corner, then along the top row
        var bottom = leftCount - 1;
        if (!availLeft[bottom]) {
            var found = -1;
            var fromTop = false;
            for (var i = bottom; i >= 0; i--) {
                if (availLeft[i]) {
                    found = left[i];
                    break;
                }
            }
            if (found < 0) {
                for (var i = 0; i < topCount; i++) {
                    if (availTop[i]) {
                        found = top[i];
                        fromTop = true;
                        break;
                    }
                }
            }
            left[bottom] = found;
            availLeft[bottom] = true;
        }
        for (var i = bottom - 1; i >= 0; i--) {
            if (!availLeft[i]) {
                left[i] = left[i + 1];
            }
        }
        // the corner p[-1-refIdx][-1-refIdx] is left[0]; top[0] is p[-refIdx][-1-refIdx]
        for (var i = 0; i < topCount; i++) {
            if (!availTop[i]) {
                top[i] = i == 0 ? left[0] : top[i - 1];
            }
        }
    }

    /**
     * Predicts a block with a regular intra mode (8.4.5.2.6).
     *
     * @param out the predicted samples, {@code w * h} in raster order
     * @param plane the component plane being reconstructed
     * @param stride the plane stride
     * @param x0 the block column in component samples
     * @param y0 the block row in component samples
     * @param w the block width
     * @param h the block height
     * @param mode the intra prediction mode before wide-angle mapping
     * @param c the component index
     * @param refIdx the reference line index
     * @param cbW the coding block width for ISP reference sizes and mode mapping, else {@code w}
     * @param cbH the coding block height, else {@code h}
     * @param isp whether the luma block is an intra subpartition
     * @param bdpcm whether the block uses BDPCM
     * @param sx the horizontal subsampling shift of the component
     * @param sy the vertical subsampling shift
     */
    void predict(int[] out, short[] plane, int stride, int x0, int y0, int w, int h, int mode, int c, int refIdx,
                 int cbW, int cbH, boolean isp, boolean bdpcm, int sx, int sy) {
        int refW;
        int refH;
        if (!isp || c > 0) {
            refW = 2 * w;
            refH = 2 * h;
        } else {
            refW = cbW + w;
            refH = cbH + h;
        }
        var nW = !isp || c > 0 ? w : cbW;
        var nH = !isp || c > 0 ? h : cbH;
        var predMode = mode;
        if (nW != nH && mode >= 2 && mode <= 66) {
            var ratio = Math.abs(Integer.numberOfTrailingZeros(nW) - Integer.numberOfTrailingZeros(nH));
            if (nW > nH && mode < (ratio > 1 ? 8 + 2 * ratio : 8)) {
                predMode = mode + 65;
            } else if (nH > nW && mode > (ratio > 1 ? 60 - 2 * ratio : 60)) {
                predMode = mode - 67;
            }
        }
        var refFilterFlag = predMode == 0 || predMode == -14 || predMode == -12 || predMode == -10
                || predMode == -6 || predMode == 2 || predMode == 34 || predMode == 66 || predMode == 72
                || predMode == 76 || predMode == 78 || predMode == 80;
        buildReferences(plane, stride, x0, y0, refW, refH, refIdx, c, sx, sy);
        var filter = refIdx == 0 && w * h > 32 && c == 0 && !isp && refFilterFlag;
        int[] l = left;
        int[] t = top;
        if (filter) {
            // indices: l[0] corner, l[1 + y] p[-1][y]; t[0] p[0][-1]
            var corner = left[0];
            leftF[0] = (left[1] + 2 * corner + top[0] + 2) >> 2;
            for (var y = 0; y < refH - 1; y++) {
                var below = left[y + 2];
                var above = y == 0 ? corner : left[y];
                leftF[y + 1] = (below + 2 * left[y + 1] + above + 2) >> 2;
            }
            leftF[refH] = left[refH];
            for (var x = 0; x < refW - 1; x++) {
                var before = x == 0 ? corner : top[x - 1];
                topF[x] = (before + 2 * top[x] + top[x + 1] + 2) >> 2;
            }
            topF[refW - 1] = top[refW - 1];
            l = leftF;
            t = topF;
        }
        if (predMode == 0) {
            planar(out, w, h, l, t);
        } else if (predMode == 1) {
            dc(out, w, h, l, t, refIdx);
        } else {
            angular(out, w, h, predMode, refIdx, refW, refH, refFilterFlag, isp, c, l, t);
        }
        if (w >= 4 && h >= 4 && refIdx == 0 && !bdpcm
                && (predMode == 0 || predMode == 1 || predMode <= 18 || (predMode >= 50 && predMode < 81))) {
            pdpc(out, w, h, predMode, l, t);
        }
    }

    /**
     * Predicts a block with planar prediction (8.4.5.2.11).
     *
     * @param out the prediction, {@code w} samples per row
     * @param w the block width
     * @param h the block height
     * @param l the left reference, {@code l[1 + y]} for y from -1
     * @param t the top reference, {@code t[1 + x]} for x from -1
     */
    private static void planar(int[] out, int w, int h, int[] l, int[] t) {
        var log2W = Integer.numberOfTrailingZeros(w);
        var log2H = Integer.numberOfTrailingZeros(h);
        var bottomLeft = l[1 + h];
        var topRight = t[w];
        var shift = log2W + log2H + 1;
        for (var y = 0; y < h; y++) {
            var leftY = l[1 + y];
            for (var x = 0; x < w; x++) {
                var v = ((h - 1 - y) * t[x] + (y + 1) * bottomLeft) << log2W;
                var hz = ((w - 1 - x) * leftY + (x + 1) * topRight) << log2H;
                out[y * w + x] = (v + hz + w * h) >> shift;
            }
        }
    }

    /**
     * Predicts a block with DC prediction (8.4.5.2.12).
     *
     * @param out the prediction, {@code w} samples per row
     * @param w the block width
     * @param h the block height
     * @param l the left reference
     * @param t the top reference
     * @param refIdx the reference line index
     */
    private static void dc(int[] out, int w, int h, int[] l, int[] t, int refIdx) {
        var sum = 0;
        int value;
        if (w == h) {
            for (var i = 0; i < w; i++) {
                sum += t[refIdx + i];
                sum += l[1 + refIdx + i];
            }
            value = (sum + w) >> (Integer.numberOfTrailingZeros(w) + 1);
        } else if (w > h) {
            for (var i = 0; i < w; i++) {
                sum += t[refIdx + i];
            }
            value = (sum + (w >> 1)) >> Integer.numberOfTrailingZeros(w);
        } else {
            for (var i = 0; i < h; i++) {
                sum += l[1 + refIdx + i];
            }
            value = (sum + (h >> 1)) >> Integer.numberOfTrailingZeros(h);
        }
        Arrays.fill(out, 0, w * h, value);
    }

    /**
     * Predicts a block with angular prediction (8.4.5.2.13).
     *
     * @param out the prediction, {@code w} samples per row
     * @param w the block width
     * @param h the block height
     * @param predMode the wide-angle mapped prediction mode
     * @param refIdx the reference line index
     * @param refW the width of the top reference
     * @param refH the height of the left reference
     * @param refFilterFlag {@code refFilterFlag}: whether the references were filtered, which selects the smoothing
     * interpolation
     * @param isp whether the block is an intra sub-partition
     * @param c the component
     * @param l the left reference
     * @param t the top reference
     */
    private void angular(int[] out, int w, int h, int predMode, int refIdx, int refW, int refH,
                         boolean refFilterFlag, boolean isp, int c, int[] l, int[] t) {
        var nTbS = (Integer.numberOfTrailingZeros(w) + Integer.numberOfTrailingZeros(h)) >> 1;
        var filterFlag = false;
        if (!refFilterFlag && refIdx == 0 && !isp) {
            var minDist = Math.min(Math.abs(predMode - 50), Math.abs(predMode - 18));
            int threshold = switch (nTbS) {
                case 2 -> 24;
                case 3 -> 14;
                case 4 -> 2;
                default -> nTbS < 2 ? 24 : 0;
            };
            filterFlag = minDist > threshold;
        }
        var angle = ANGLE[predMode + 14];
        var invAngle = INV_ANGLE[predMode + 14];
        // ref[k] is stored at ref[k + base]
        var base = 2 * 128 + 4;
        var vertical = predMode >= 34;
        var mainLen = vertical ? w : h;
        var sideLen = vertical ? h : w;
        var mainRefLen = vertical ? refW : refH;
        // p[-1-refIdx+k][-1-refIdx] for vertical: k = 0 is the corner (left[0]), k >= 1 is top[k - 1]
        for (var k = 0; k <= mainLen + refIdx + 1; k++) {
            ref[base + k] = mainSample(vertical, k, l, t);
        }
        if (angle < 0) {
            for (var k = -sideLen; k <= -1; k++) {
                var idx = Math.min((k * invAngle + 256) >> 9, sideLen);
                ref[base + k] = sideSample(vertical, idx, l, t);
            }
        } else {
            for (var k = mainLen + 2 + refIdx; k <= mainRefLen + refIdx; k++) {
                ref[base + k] = mainSample(vertical, k, l, t);
            }
            var last = mainSample(vertical, mainRefLen, l, t);
            var extra = Math.max(1, mainLen / sideLen) * refIdx + 2;
            for (var k = 1; k <= extra; k++) {
                ref[base + mainRefLen + refIdx + k] = mainSample(vertical, mainRefLen + refIdx, l, t);
            }
            if (last < 0) {
                ref[base] = ref[base];
            }
        }
        var filters = c == 0 ? (filterFlag ? VvcFilters.INTRA_GAUSS : VvcFilters.CHROMA) : null;
        for (var j = 0; j < sideLen; j++) {
            var pos = (j + 1 + refIdx) * angle;
            var iIdx = (pos >> 5) + refIdx;
            var iFact = pos & 31;
            for (var i = 0; i < mainLen; i++) {
                int v;
                if (c == 0) {
                    var ft = filters[iFact];
                    var r = base + i + iIdx;
                    v = (ft[0] * ref[r] + ft[1] * ref[r + 1] + ft[2] * ref[r + 2] + ft[3] * ref[r + 3] + 32) >> 6;
                    v = Math.clamp(v, 0, maxVal);
                } else if (iFact != 0) {
                    var r = base + i + iIdx + 1;
                    v = ((32 - iFact) * ref[r] + iFact * ref[r + 1] + 16) >> 5;
                } else {
                    v = ref[base + i + iIdx + 1];
                }
                if (vertical) {
                    out[j * w + i] = v;
                } else {
                    out[i * w + j] = v;
                }
            }
        }
    }

    /**
     * {@return {@code p[-1-refIdx+k][-1-refIdx]} (vertical) or {@code p[-1-refIdx][-1-refIdx+k]} (horizontal)}
     *
     * @param vertical whether the mode is vertical, the top reference being the main one
     * @param k the index along the main reference, from 0
     * @param l the left reference
     * @param t the top reference
     */
    private static int mainSample(boolean vertical, int k, int[] l, int[] t) {
        if (k == 0) {
            return l[0];
        }
        return vertical ? t[k - 1] : l[k];
    }

    /**
     * {@return the side reference sample {@code k} of a mode: of the left reference for a vertical mode, of the top
     * reference otherwise}
     *
     * @param vertical whether the mode is vertical
     * @param k the index along the side reference, from 0
     * @param l the left reference
     * @param t the top reference
     */
    private static int sideSample(boolean vertical, int k, int[] l, int[] t) {
        if (k == 0) {
            return l[0];
        }
        return vertical ? l[k] : t[k - 1];
    }

    /**
     * Applies position dependent intra prediction combination (8.4.5.2.15) to a predicted block.
     *
     * @param out the prediction, {@code w} samples per row
     * @param w the block width
     * @param h the block height
     * @param predMode the prediction mode
     * @param l the left reference
     * @param t the top reference
     */
    private void pdpc(int[] out, int w, int h, int predMode, int[] l, int[] t) {
        var log2W = Integer.numberOfTrailingZeros(w);
        var log2H = Integer.numberOfTrailingZeros(h);
        // l[1 + y] = p[-1][y], l[0] = p[-1][-1], t[x] = p[x][-1]
        if (predMode == 0 || predMode == 1) {
            var nScale = (log2W + log2H - 2) >> 2;
            for (var y = 0; y < h; y++) {
                var wT = weight((y << 1) >> nScale);
                var refL = l[1 + y];
                for (var x = 0; x < w; x++) {
                    var wL = weight((x << 1) >> nScale);
                    var p = out[y * w + x];
                    out[y * w + x] = Math.clamp((refL * wL + t[x] * wT + (64 - wL - wT) * p + 32) >> 6, 0, maxVal);
                }
            }
        } else if (predMode == 18 || predMode == 50) {
            var nScale = (log2W + log2H - 2) >> 2;
            var corner = l[0];
            for (var y = 0; y < h; y++) {
                var wT = predMode == 18 ? weight((y << 1) >> nScale) : 0;
                for (var x = 0; x < w; x++) {
                    var wL = predMode == 50 ? weight((x << 1) >> nScale) : 0;
                    var p = out[y * w + x];
                    var refL = l[1 + y] - corner + p;
                    var refT = t[x] - corner + p;
                    out[y * w + x] = Math.clamp((refL * wL + refT * wT + (64 - wL - wT) * p + 32) >> 6, 0, maxVal);
                }
            }
        } else if (predMode < 18) {
            var invAngle = INV_ANGLE[predMode + 14];
            var nScale = Math.min(2, log2W - floorLog2(3 * invAngle - 2) + 8);
            if (nScale < 0) {
                return;
            }
            for (var y = 0; y < h && y < (3 << nScale); y++) {
                var wT = weight((y << 1) >> nScale);
                var dXInt = ((y + 1) * invAngle + 256) >> 9;
                for (var x = 0; x < w; x++) {
                    var dX = x + dXInt;
                    var refT = mainRefTop(dX, t, l);
                    var p = out[y * w + x];
                    out[y * w + x] = Math.clamp((refT * wT + (64 - wT) * p + 32) >> 6, 0, maxVal);
                }
            }
        } else if (predMode > 50) {
            var invAngle = INV_ANGLE[predMode + 14];
            var nScale = Math.min(2, log2H - floorLog2(3 * invAngle - 2) + 8);
            if (nScale < 0) {
                return;
            }
            for (var x = 0; x < w && x < (3 << nScale); x++) {
                var wL = weight((x << 1) >> nScale);
                var dYInt = ((x + 1) * invAngle + 256) >> 9;
                for (var y = 0; y < h; y++) {
                    var dY = y + dYInt;
                    var refL = l[1 + dY];
                    var p = out[y * w + x];
                    out[y * w + x] = Math.clamp((refL * wL + (64 - wL) * p + 32) >> 6, 0, maxVal);
                }
            }
        }
    }

    /**
     * {@return the PDPC weight {@code 32 >> shift}, 0 for shifts of 6 or more}
     *
     * @param shift the weight shift
     */
    private static int weight(int shift) {
        return shift > 5 ? 0 : 32 >> shift;
    }

    /**
     * {@return the top reference sample of a column}
     *
     * @param x the column, from -1
     * @param t the top reference
     * @param l the left reference
     */
    private static int mainRefTop(int x, int[] t, int[] l) {
        return t[x];
    }

    /**
     * {@return {@code Floor( Log2( v ) )} for a positive value}
     *
     * @param v the value
     */
    private static int floorLog2(int v) {
        return 31 - Integer.numberOfLeadingZeros(v);
    }

    /**
     * Predicts a luma block with matrix-based intra prediction (8.4.5.2.2).
     *
     * @param out the prediction, {@code w} samples per row
     * @param plane the reconstructed luma plane
     * @param stride the row stride of the plane
     * @param x0 the luma column of the block
     * @param y0 the luma row of the block
     * @param w the block width
     * @param h the block height
     * @param modeId {@code modeId}, the matrix index
     * @param transposed {@code intra_mip_transposed_flag}
     */
    void predictMip(int[] out, short[] plane, int stride, int x0, int y0, int w, int h, int modeId,
                    boolean transposed) {
        var sizeId = w == 4 && h == 4 ? 0 : (w == 4 || h == 4 || (w == 8 && h == 8)) ? 1 : 2;
        var boundary = sizeId == 0 ? 2 : 4;
        var predSize = sizeId < 2 ? 4 : 8;
        var inSize = 2 * boundary - (sizeId == 2 ? 1 : 0);
        buildReferences(plane, stride, x0, y0, w + 1, h + 1, 0, 0, 0, 0);
        // refT[x] = top[x], refL[y] = left[1 + y]
        downsample(top, 0, w, boundary, redT);
        downsample(left, 1, h, boundary, redL);
        var pTemp = mipTemp;
        for (var i = 0; i < boundary; i++) {
            pTemp[i] = transposed ? redL[i] : redT[i];
            pTemp[i + boundary] = transposed ? redT[i] : redL[i];
        }
        var p = mipP;
        if (sizeId == 2) {
            for (var i = 0; i < inSize; i++) {
                p[i] = pTemp[i + 1] - pTemp[0];
            }
        } else {
            p[0] = (1 << (bitDepth - 1)) - pTemp[0];
            for (var i = 1; i < inSize; i++) {
                p[i] = pTemp[i] - pTemp[0];
            }
        }
        var sum = 0;
        for (var i = 0; i < inSize; i++) {
            sum += p[i];
        }
        var oW = 32 - 32 * sum;
        var weights = VvcMipData.WEIGHTS[sizeId][modeId];
        var pred = mipPred;
        for (var pos = 0; pos < predSize * predSize; pos++) {
            var acc = oW;
            var wBase = pos * inSize;
            for (var i = 0; i < inSize; i++) {
                acc += weights[wBase + i] * p[i];
            }
            pred[pos] = Math.clamp((acc >> 6) + pTemp[0], 0, maxVal);
        }
        if (transposed) {
            for (var y = 0; y < predSize; y++) {
                for (var x = y + 1; x < predSize; x++) {
                    var a = pred[y * predSize + x];
                    pred[y * predSize + x] = pred[x * predSize + y];
                    pred[x * predSize + y] = a;
                }
            }
        }
        if (w == predSize && h == predSize) {
            System.arraycopy(pred, 0, out, 0, w * h);
            return;
        }
        var upHor = w / predSize;
        var upVer = h / predSize;
        Arrays.fill(out, 0, w * h, 0);
        for (var y = 0; y < predSize; y++) {
            for (var x = 0; x < predSize; x++) {
                out[((y + 1) * upVer - 1) * w + (x + 1) * upHor - 1] = pred[y * predSize + x];
            }
        }
        if (upHor > 1) {
            for (var n = 1; n <= predSize; n++) {
                var yy = n * upVer - 1;
                for (var m = 0; m < predSize; m++) {
                    var xx = m * upHor - 1;
                    var a = xx < 0 ? left[1 + yy] : out[yy * w + xx];
                    var b = out[yy * w + xx + upHor];
                    for (var d = 1; d < upHor; d++) {
                        out[yy * w + xx + d] = ((upHor - d) * a + d * b + upHor / 2) / upHor;
                    }
                }
            }
        }
        if (upVer > 1) {
            for (var n = 0; n < predSize; n++) {
                var yy = n * upVer - 1;
                for (var x = 0; x < w; x++) {
                    var a = yy < 0 ? top[x] : out[yy * w + x];
                    var b = out[(yy + upVer) * w + x];
                    for (var d = 1; d < upVer; d++) {
                        out[(yy + d) * w + x] = ((upVer - d) * a + d * b + upVer / 2) / upVer;
                    }
                }
            }
        }
    }

    /**
     * The upsampled prediction of matrix-based intra prediction.
     */
    private final int[] mipPred = new int[64];

    /**
     * Averages a boundary down to {@code boundary} samples (8.4.5.2.3), or copies it when it is no longer.
     *
     * @param src the boundary samples
     * @param offset the index of the first sample
     * @param size the number of samples
     * @param boundary the reduced size
     * @param dst receives the reduced boundary
     */
    private static void downsample(int[] src, int offset, int size, int boundary, int[] dst) {
        if (boundary < size) {
            var down = size / boundary;
            var log2 = Integer.numberOfTrailingZeros(down);
            for (var x = 0; x < boundary; x++) {
                var sum = 0;
                for (var i = 0; i < down; i++) {
                    sum += src[offset + x * down + i];
                }
                dst[x] = (sum + (1 << (log2 - 1))) >> log2;
            }
        } else {
            System.arraycopy(src, offset, dst, 0, boundary);
        }
    }

    /**
     * Predicts a chroma block with a cross-component linear model mode (8.4.5.2.14): 81 LT, 82 L, 83 T.
     *
     * @param out the predicted samples
     * @param plane the chroma plane
     * @param stride its stride
     * @param luma the luma plane, reconstructed before the in-loop filters
     * @param lumaStride its stride
     * @param xC the block column in chroma samples
     * @param yC the block row in chroma samples
     * @param w the block width in chroma samples
     * @param h the block height in chroma samples
     * @param mode 81, 82 or 83
     * @param sx the horizontal subsampling shift
     * @param sy the vertical subsampling shift
     * @param verticalCollocated {@code sps_chroma_vertical_collocated_flag}
     * @param ctuSize the CTU size in luma samples
     */
    void predictCclm(int[] out, short[] plane, int stride, short[] luma, int lumaStride, int xC, int yC, int w, int h,
                     int mode, int sx, int sy, boolean verticalCollocated, int ctuSize) {
        var subW = 1 << sx;
        var subH = 1 << sy;
        var xY = xC << sx;
        var yY = yC << sy;
        var availL = f.sampleAvailable(xY - 1, yY, xY, yY, true);
        var availT = f.sampleAvailable(xY, yY - 1, xY, yY, true);
        var numTopRight = 0;
        if (mode == 83) {
            for (var x = w; x < 2 * w; x++) {
                if (!f.sampleAvailable(xY + x * subW, yY - 1, xY, yY, true)) {
                    break;
                }
                numTopRight++;
            }
        }
        var numLeftBelow = 0;
        if (mode == 82) {
            for (var y = h; y < 2 * h; y++) {
                if (!f.sampleAvailable(xY - 1, yY + y * subH, xY, yY, true)) {
                    break;
                }
                numLeftBelow++;
            }
        }
        int numT;
        int numL;
        if (mode == 81) {
            numT = availT ? w : 0;
            numL = availL ? h : 0;
        } else {
            numT = availT && mode == 83 ? w + Math.min(numTopRight, h) : 0;
            numL = availL && mode == 82 ? h + Math.min(numLeftBelow, w) : 0;
        }
        var maxV = maxVal;
        if (numT == 0 && numL == 0) {
            Arrays.fill(out, 0, w * h, 1 << (bitDepth - 1));
            return;
        }
        var ctuBoundary = (yY & (ctuSize - 1)) == 0;
        var numIs4 = availT && availL && mode == 81 ? 0 : 1;
        var cntT = 0;
        var cntL = 0;
        var selC = cclmC;
        var selY = cclmY;
        if (availT && (mode == 81 || mode == 83)) {
            var start = numT >> (2 + numIs4);
            var step = Math.max(1, numT >> (1 + numIs4));
            cntT = Math.min(numT, (1 + numIs4) << 1);
            for (var i = 0; i < cntT; i++) {
                var x = start + i * step;
                selC[i] = plane[(yC - 1) * stride + xC + x];
                selY[i] = topLumaDs(luma, lumaStride, xY, yY, x, sx, sy, ctuBoundary, verticalCollocated, availL);
            }
        }
        if (availL && (mode == 81 || mode == 82)) {
            var start = numL >> (2 + numIs4);
            var step = Math.max(1, numL >> (1 + numIs4));
            cntL = Math.min(numL, (1 + numIs4) << 1);
            for (var i = 0; i < cntL; i++) {
                var y = start + i * step;
                selC[cntT + i] = plane[(yC + y) * stride + xC - 1];
                selY[cntT + i] = leftLumaDs(luma, lumaStride, xY, yY, y, sx, sy, verticalCollocated, availT);
            }
        }
        if (cntT + cntL == 2) {
            selY[3] = selY[0];
            selY[2] = selY[1];
            selY[0] = selY[1];
            selY[1] = selY[3];
            selC[3] = selC[0];
            selC[2] = selC[1];
            selC[0] = selC[1];
            selC[1] = selC[3];
        }
        int min0 = 0;
        int min1 = 2;
        int max0 = 1;
        int max1 = 3;
        if (selY[min0] > selY[min1]) {
            var t = min0;
            min0 = min1;
            min1 = t;
        }
        if (selY[max0] > selY[max1]) {
            var t = max0;
            max0 = max1;
            max1 = t;
        }
        if (selY[min0] > selY[max1]) {
            var t0 = min0;
            var t1 = min1;
            min0 = max0;
            min1 = max1;
            max0 = t0;
            max1 = t1;
        }
        if (selY[min1] > selY[max0]) {
            var t = min1;
            min1 = max0;
            max0 = t;
        }
        var maxY = (selY[max0] + selY[max1] + 1) >> 1;
        var maxC = (selC[max0] + selC[max1] + 1) >> 1;
        var minY = (selY[min0] + selY[min1] + 1) >> 1;
        var minC = (selC[min0] + selC[min1] + 1) >> 1;
        var diff = maxY - minY;
        int a;
        int b;
        int k;
        if (diff != 0) {
            var diffC = maxC - minC;
            var x = floorLog2(diff);
            var normDiff = ((diff << 4) >> x) & 15;
            x += normDiff != 0 ? 1 : 0;
            var y = Math.abs(diffC) > 0 ? floorLog2(Math.abs(diffC)) + 1 : 0;
            var add = y > 0 ? 1 << (y - 1) : 0;
            a = (diffC * (DIV_SIG[normDiff] | 8) + add) >> y;
            k = 3 + x - y < 1 ? 1 : 3 + x - y;
            a = 3 + x - y < 1 ? Integer.signum(a) * 15 : a;
            b = minC - ((a * minY) >> k);
        } else {
            k = 0;
            a = 0;
            b = minC;
        }
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var ds = collocatedLumaDs(luma, lumaStride, xY, yY, x, y, sx, sy, verticalCollocated, availL, availT);
                out[y * w + x] = Math.clamp(((ds * a) >> k) + b, 0, maxV);
            }
        }
    }

    /**
     * The chroma samples of the four points the cross-component linear model is fitted on.
     */
    private final int[] cclmC = new int[4];
    /**
     * The down-sampled luma samples of the four points the cross-component linear model is fitted on.
     */
    private final int[] cclmY = new int[4];

    /**
     * Reads luma sample {@code pY[x][y]} of a CCLM block with the padding rules for unavailable neighbors.
     *
     * @param luma the reconstructed luma plane
     * @param stride the row stride of the plane
     * @param xY the luma column of the block
     * @param yY the luma row of the block
     * @param x the column relative to the block
     * @param y the row relative to the block
     * @param availL whether the left neighbors are available
     * @param availT whether the top neighbors are available
     * @return the sample
     */
    private static int lumaAt(short[] luma, int stride, int xY, int yY, int x, int y, boolean availL,
                              boolean availT) {
        if (y < 0 && !availT) {
            y = 0;
        }
        if (x < 0 && !availL) {
            x = 0;
        }
        return luma[(yY + y) * stride + xY + x];
    }

    /**
     * Returns the down-sampled luma sample collocated with a chroma sample of the block ({@code pDsY}, 8.4.5.2.14).
     *
     * @param luma the reconstructed luma plane
     * @param stride the row stride of the plane
     * @param xY the luma column of the block
     * @param yY the luma row of the block
     * @param x the chroma column relative to the block
     * @param y the chroma row relative to the block
     * @param sx the horizontal chroma subsampling shift
     * @param sy the vertical chroma subsampling shift
     * @param verticalCollocated {@code sps_chroma_vertical_collocated_flag}
     * @param availL whether the left neighbors are available
     * @param availT whether the top neighbors are available
     * @return the down-sampled sample
     */
    private static int collocatedLumaDs(short[] luma, int stride, int xY, int yY, int x, int y, int sx, int sy,
                                        boolean verticalCollocated, boolean availL, boolean availT) {
        if (sx == 0 && sy == 0) {
            return luma[(yY + y) * stride + xY + x];
        }
        var lx = x << sx;
        var ly = y << sy;
        if (sy == 0) {
            return (lumaAt(luma, stride, xY, yY, lx - 1, ly, availL, availT)
                    + 2 * lumaAt(luma, stride, xY, yY, lx, ly, availL, availT)
                    + lumaAt(luma, stride, xY, yY, lx + 1, ly, availL, availT) + 2) >> 2;
        }
        if (verticalCollocated) {
            return (lumaAt(luma, stride, xY, yY, lx, ly - 1, availL, availT)
                    + lumaAt(luma, stride, xY, yY, lx - 1, ly, availL, availT)
                    + 4 * lumaAt(luma, stride, xY, yY, lx, ly, availL, availT)
                    + lumaAt(luma, stride, xY, yY, lx + 1, ly, availL, availT)
                    + lumaAt(luma, stride, xY, yY, lx, ly + 1, availL, availT) + 4) >> 3;
        }
        return (lumaAt(luma, stride, xY, yY, lx - 1, ly, availL, availT)
                + lumaAt(luma, stride, xY, yY, lx - 1, ly + 1, availL, availT)
                + 2 * lumaAt(luma, stride, xY, yY, lx, ly, availL, availT)
                + 2 * lumaAt(luma, stride, xY, yY, lx, ly + 1, availL, availT)
                + lumaAt(luma, stride, xY, yY, lx + 1, ly, availL, availT)
                + lumaAt(luma, stride, xY, yY, lx + 1, ly + 1, availL, availT) + 4) >> 3;
    }

    /**
     * Returns the down-sampled luma sample above a chroma column of the block ({@code pSelDsY} of the top neighbors).
     *
     * @param luma the reconstructed luma plane
     * @param stride the row stride of the plane
     * @param xY the luma column of the block
     * @param yY the luma row of the block
     * @param x the chroma column relative to the block
     * @param sx the horizontal chroma subsampling shift
     * @param sy the vertical chroma subsampling shift
     * @param ctuBoundary whether the block is at the top of a CTU, where one luma row is read
     * @param verticalCollocated {@code sps_chroma_vertical_collocated_flag}
     * @param availL whether the left neighbors are available
     * @return the down-sampled sample
     */
    private static int topLumaDs(short[] luma, int stride, int xY, int yY, int x, int sx, int sy, boolean ctuBoundary,
                                 boolean verticalCollocated, boolean availL) {
        if (sx == 0 && sy == 0) {
            return luma[(yY - 1) * stride + xY + x];
        }
        var lx = x << sx;
        if (sy != 0 && !ctuBoundary) {
            if (verticalCollocated) {
                return (lumaAt(luma, stride, xY, yY, lx, -3, availL, true)
                        + lumaAt(luma, stride, xY, yY, lx - 1, -2, availL, true)
                        + 4 * lumaAt(luma, stride, xY, yY, lx, -2, availL, true)
                        + lumaAt(luma, stride, xY, yY, lx + 1, -2, availL, true)
                        + lumaAt(luma, stride, xY, yY, lx, -1, availL, true) + 4) >> 3;
            }
            return (lumaAt(luma, stride, xY, yY, lx - 1, -1, availL, true)
                    + lumaAt(luma, stride, xY, yY, lx - 1, -2, availL, true)
                    + 2 * lumaAt(luma, stride, xY, yY, lx, -1, availL, true)
                    + 2 * lumaAt(luma, stride, xY, yY, lx, -2, availL, true)
                    + lumaAt(luma, stride, xY, yY, lx + 1, -1, availL, true)
                    + lumaAt(luma, stride, xY, yY, lx + 1, -2, availL, true) + 4) >> 3;
        }
        return (lumaAt(luma, stride, xY, yY, lx - 1, -1, availL, true)
                + 2 * lumaAt(luma, stride, xY, yY, lx, -1, availL, true)
                + lumaAt(luma, stride, xY, yY, lx + 1, -1, availL, true) + 2) >> 2;
    }

    /**
     * Returns the down-sampled luma sample left of a chroma row of the block ({@code pSelDsY} of the left neighbors).
     *
     * @param luma the reconstructed luma plane
     * @param stride the row stride of the plane
     * @param xY the luma column of the block
     * @param yY the luma row of the block
     * @param y the chroma row relative to the block
     * @param sx the horizontal chroma subsampling shift
     * @param sy the vertical chroma subsampling shift
     * @param verticalCollocated {@code sps_chroma_vertical_collocated_flag}
     * @param availT whether the top neighbors are available
     * @return the down-sampled sample
     */
    private static int leftLumaDs(short[] luma, int stride, int xY, int yY, int y, int sx, int sy,
                                  boolean verticalCollocated, boolean availT) {
        var subW = 1 << sx;
        if (sx == 0 && sy == 0) {
            return luma[(yY + y) * stride + xY - 1];
        }
        if (sy == 0) {
            return (lumaAt(luma, stride, xY, yY, -1 - subW, y, true, availT)
                    + 2 * lumaAt(luma, stride, xY, yY, -subW, y, true, availT)
                    + lumaAt(luma, stride, xY, yY, 1 - subW, y, true, availT) + 2) >> 2;
        }
        var ly = y << sy;
        if (verticalCollocated) {
            return (lumaAt(luma, stride, xY, yY, -subW, ly - 1, true, availT)
                    + lumaAt(luma, stride, xY, yY, -1 - subW, ly, true, availT)
                    + 4 * lumaAt(luma, stride, xY, yY, -subW, ly, true, availT)
                    + lumaAt(luma, stride, xY, yY, 1 - subW, ly, true, availT)
                    + lumaAt(luma, stride, xY, yY, -subW, ly + 1, true, availT) + 4) >> 3;
        }
        return (lumaAt(luma, stride, xY, yY, -1 - subW, ly, true, availT)
                + lumaAt(luma, stride, xY, yY, -1 - subW, ly + 1, true, availT)
                + 2 * lumaAt(luma, stride, xY, yY, -subW, ly, true, availT)
                + 2 * lumaAt(luma, stride, xY, yY, -subW, ly + 1, true, availT)
                + lumaAt(luma, stride, xY, yY, 1 - subW, ly, true, availT)
                + lumaAt(luma, stride, xY, yY, 1 - subW, ly + 1, true, availT) + 4) >> 3;
    }
}
