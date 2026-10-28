/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/shape.cpp, mb.cpp, error.cpp and the motion vector prediction of mv.cpp). Copyright 1996-2000 Microsoft
 * Corporation, Toshiba Corporation, Hyundai Electronics, Samsung AIT, Sharp Corporation, Fraunhofer HHI and the
 * contributors named in the sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for
 * Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

import java.util.Arrays;

/**
 * The macroblock-level shape helpers: transparency status, chrominance shape subsampling, the borders and contexts
 * of binary alpha blocks, their up- and downsampling (7.5.4), the scans of the scalable shape coding (7.5.5), shape
 * motion vector prediction (7.5.2.1), luminance motion vector prediction (7.6.5) and the video packet boundaries.
 */
abstract class ObjectShape extends ObjectPadding {
    static final int OPAQUE = 255;
    static final int TRANSPARENT = 0;
    static final int BAB_BORDER_BOTH = 4;

    int[] caeSymbol;
    int caeSymbolOffset;
    int inverseCR;
    boolean vpNoLeft;
    boolean vpNoRightTop;
    boolean vpNoTop;
    boolean vpNoLeftTop;
    boolean vpNoRight;
    boolean vpNoBottom;

    private final int[] upSampleTmp = new int[12 * 12];
    private final int[] upSampleValues = new int[12];
    private final int[] upSampleX = new int[12];
    private final int[] upSampleY = new int[12];
    final int[] candX = new int[3];
    final int[] candY = new int[3];
    final boolean[] inBound = new boolean[3];

    final int vpMbNum(int mbx, int mby) {
        return mby * numMBX + mbx;
    }

    final boolean vpNoLeft(int mbNum, int mbx) {
        return mbNum <= vpMBnum || mbx == 0;
    }

    final boolean vpNoRightTop(int mbNum, int mbx) {
        return mbNum - numMBX + 1 < vpMBnum || mbx == numMBX - 1;
    }

    final boolean vpNoTop(int mbNum) {
        return mbNum - numMBX < vpMBnum;
    }

    final boolean vpNoLeftTop(int mbNum, int mbx) {
        return mbNum - numMBX - 1 < vpMBnum || mbx == 0;
    }

    final boolean vpNoRight(int mbx) {
        return mbx == numMBX - 1;
    }

    final boolean vpNoBottom(int mby) {
        return mby == numMBY - 1;
    }

    /**
     * Copies the binary shape of a macroblock of a reference plane into a macroblock buffer
     * ({@code copyRefShapeToMb}).
     */
    final void copyRefShapeToMb(int[] dst, int[] src, int srcOff) {
        for (var i = 0; i < MB_SIZE; i++) {
            System.arraycopy(src, srcOff, dst, i * MB_SIZE, MB_SIZE);
            srcOff += frameWidthY;
        }
    }

    /**
     * Decides whether the intra DC coefficients of a macroblock are coded with the AC coefficients
     * ({@code setDCVLCMode}).
     *
     * @param boundary whether the macroblock starts a video packet; cleared by the call
     */
    final void setDCVLCMode(MbMode mode, boolean[] boundary) {
        if (mode.transpStatus[0] == MbMode.ALL) {
            return;
        }
        if (mode.dctMd == MbMode.INTRA || mode.dctMd == MbMode.INTRAQ || !mode.skip) {
            int qp;
            if (boundary[0]) {
                boundary[0] = false;
                qp = mode.stepSize;
            } else {
                qp = mode.stepSize - mode.intStepDelta;
            }
            var thr = vopmd.intraDcSwitchThr;
            mode.codeDcAsAc = thr != 0 && (thr == 7 || qp >= 2 * thr + 11);
            mode.codeDcAsAcAlpha = false;
        }
    }

    /**
     * Sets the transparency status of a macroblock and of its luminance blocks from its binary shape
     * ({@code decideTransparencyStatus}).
     */
    final void decideTransparencyStatus(MbMode mode, int[] by, int byOff) {
        int n1 = 0;
        int n2 = 0;
        int n3 = 0;
        int n4 = 0;
        for (var iy = 0; iy < BLOCK_SIZE; iy++) {
            var row = byOff + iy * MB_SIZE;
            for (var ix = 0; ix < BLOCK_SIZE; ix++) {
                n1 += by[row + ix];
                n2 += by[row + BLOCK_SIZE + ix];
                n3 += by[row + MB_SIZE * BLOCK_SIZE + ix];
                n4 += by[row + MB_SIZE * BLOCK_SIZE + BLOCK_SIZE + ix];
            }
        }
        n1 /= OPAQUE;
        n2 /= OPAQUE;
        n3 /= OPAQUE;
        n4 /= OPAQUE;
        var counts = mode.numNonTranspPixels;
        counts[0] = n1 + n2 + n3 + n4;
        counts[1] = n1;
        counts[2] = n2;
        counts[3] = n3;
        counts[4] = n4;
        if (counts[0] == 0) {
            mode.transpStatus[0] = MbMode.ALL;
            mode.dctMd = MbMode.INTER;
        } else if (counts[0] == MB_SQUARE_SIZE) {
            mode.transpStatus[0] = MbMode.NONE;
        } else {
            mode.transpStatus[0] = MbMode.PARTIAL;
        }
        for (var i = Y_BLOCK1; i < U_BLOCK; i++) {
            mode.transpStatus[i] = counts[i] == 0 ? MbMode.ALL
                    : counts[i] == BLOCK_SQUARE_SIZE ? MbMode.NONE : MbMode.PARTIAL;
        }
        mode.transpStatus[V_BLOCK] = mode.transpStatus[U_BLOCK] = mode.transpStatus[0];
    }

    /**
     * Sets the transparency status of a macroblock from the shape of the macroblock buffer
     * ({@code decideMBTransparencyStatus}).
     */
    final void decideMBTransparencyStatus(MbMode mode) {
        var n = 0;
        for (var i = 0; i < MB_SQUARE_SIZE; i++) {
            n += currMBBY[i];
        }
        n /= OPAQUE;
        mode.numNonTranspPixels[0] = n;
        if (n == 0) {
            mode.transpStatus[0] = MbMode.ALL;
            mode.dctMd = MbMode.INTER;
        } else if (n == MB_SQUARE_SIZE) {
            mode.transpStatus[0] = MbMode.NONE;
        } else {
            mode.transpStatus[0] = MbMode.PARTIAL;
        }
    }

    @Override
    final void decideFieldTransparencyStatus(MbMode mode, int[] by, int byOff, int[] buv, int buvOff) {
        int top = 0;
        int bottom = 0;
        var yFieldSkip = 2 * frameWidthY;
        var t = byOff;
        var b = byOff + frameWidthY;
        for (var iy = 0; iy < BLOCK_SIZE; iy++) {
            for (var ix = 0; ix < MB_SIZE; ix++) {
                top += by[t + ix];
                bottom += by[b + ix];
            }
            t += yFieldSkip;
            b += yFieldSkip;
        }
        mode.fieldTranspStatus[1] = fieldStatus(top / OPAQUE, MB_SQUARE_SIZE / 2);
        mode.fieldTranspStatus[2] = fieldStatus(bottom / OPAQUE, MB_SQUARE_SIZE / 2);
        top = 0;
        bottom = 0;
        var cFieldSkip = 2 * frameWidthUV;
        t = buvOff;
        b = buvOff + frameWidthUV;
        for (var iy = 0; iy < BLOCK_SIZE / 2; iy++) {
            for (var ix = 0; ix < BLOCK_SIZE; ix++) {
                top += buv[t + ix];
                bottom += buv[b + ix];
            }
            t += cFieldSkip;
            b += cFieldSkip;
        }
        mode.fieldTranspStatus[3] = fieldStatus(top / OPAQUE, BLOCK_SQUARE_SIZE / 2);
        mode.fieldTranspStatus[4] = fieldStatus(bottom / OPAQUE, BLOCK_SQUARE_SIZE / 2);
    }

    private static int fieldStatus(int count, int full) {
        return count == 0 ? MbMode.ALL : count == full ? MbMode.NONE : MbMode.PARTIAL;
    }

    @Override
    final void fieldBasedDownSampleBY(int[] by, int byOff, int[] buv, int buvOff) {
        var fieldSkip = 4 * frameWidthY;
        var top = byOff;
        var bottom = byOff + frameWidthY;
        for (var iy = 0; iy < BLOCK_SIZE / 2; iy++) {
            var topNext = top + 2 * frameWidthY;
            var bottomNext = bottom + 2 * frameWidthY;
            for (var ix = 0; ix < BLOCK_SIZE; ix++) {
                var x = 2 * ix;
                buv[buvOff + ix] = by[top + x] | by[top + x + 1] | by[topNext + x] | by[topNext + x + 1];
            }
            buvOff += frameWidthUV;
            for (var ix = 0; ix < BLOCK_SIZE; ix++) {
                var x = 2 * ix;
                buv[buvOff + ix] = by[bottom + x] | by[bottom + x + 1] | by[bottomNext + x]
                        | by[bottomNext + x + 1];
            }
            buvOff += frameWidthUV;
            top += fieldSkip;
            bottom += fieldSkip;
        }
    }

    /**
     * Subsamples the binary shape of a macroblock for its chrominance and sets the chrominance transparency status
     * when a mode is given ({@code downSampleBY}).
     */
    final void downSampleBY(int[] by, int[] buv, MbMode mode) {
        if (vopmd.interlace) {
            var fieldSkip = 4 * MB_SIZE;
            var top = 0;
            var bottom = MB_SIZE;
            var o = 0;
            for (var iy = 0; iy < BLOCK_SIZE / 2; iy++) {
                var topNext = top + 2 * MB_SIZE;
                var bottomNext = bottom + 2 * MB_SIZE;
                for (var ix = 0; ix < BLOCK_SIZE; ix++) {
                    var x = 2 * ix;
                    buv[o + ix] = by[top + x] | by[top + x + 1] | by[topNext + x] | by[topNext + x + 1];
                }
                o += BLOCK_SIZE;
                for (var ix = 0; ix < BLOCK_SIZE; ix++) {
                    var x = 2 * ix;
                    buv[o + ix] = by[bottom + x] | by[bottom + x + 1] | by[bottomNext + x] | by[bottomNext + x + 1];
                }
                o += BLOCK_SIZE;
                top += fieldSkip;
                bottom += fieldSkip;
            }
        } else {
            for (var iy = 0; iy < BLOCK_SIZE; iy++) {
                var row = 2 * iy * MB_SIZE;
                var next = row + MB_SIZE;
                for (var ix = 0; ix < BLOCK_SIZE; ix++) {
                    buv[iy * BLOCK_SIZE + ix] = by[row + 2 * ix] | by[row + 2 * ix + 1] | by[next + 2 * ix]
                            | by[next + 2 * ix + 1];
                }
            }
        }
        if (mode != null) {
            var n = 0;
            for (var i = 0; i < BLOCK_SQUARE_SIZE; i++) {
                n += buv[i];
            }
            n /= OPAQUE;
            mode.numNonTranspPixels[5] = n;
            mode.transpStatus[U_BLOCK] = n == 0 ? MbMode.ALL : n == BLOCK_SQUARE_SIZE ? MbMode.NONE
                    : MbMode.PARTIAL;
            mode.transpStatus[V_BLOCK] = mode.transpStatus[U_BLOCK];
        }
    }

    /**
     * Adds the residual to the prediction of a macroblock and stores the clipped sum in the current VOP
     * ({@code addErrorAndPredToCurrQ}).
     */
    final void addErrorAndPredToCurrQ(int[] y, int offY, int[] u, int[] v, int offUV) {
        var size = MB_SIZE * rrvScale;
        var ic = 0;
        for (var iy = 0; iy < size; iy++) {
            for (var ix = 0; ix < size; ix++, ic++) {
                y[offY + ix] = clip(predMBY[ic] + errorMBY[ic]);
            }
            offY += frameWidthY;
        }
        size = BLOCK_SIZE * rrvScale;
        ic = 0;
        for (var iy = 0; iy < size; iy++) {
            for (var ix = 0; ix < size; ix++, ic++) {
                u[offUV + ix] = clip(predMBU[ic] + errorMBU[ic]);
                v[offUV + ix] = clip(predMBV[ic] + errorMBV[ic]);
            }
            offUV += frameWidthUV;
        }
    }

    /**
     * Adds the residual of an alpha plane to its prediction ({@code addAlphaErrorAndPredToCurrQ}).
     */
    final void addAlphaErrorAndPredToCurrQ(int[] a, int off, int aux) {
        var pred = predMBA[aux];
        var error = errorMBA[aux];
        var ic = 0;
        for (var iy = 0; iy < MB_SIZE; iy++) {
            for (var ix = 0; ix < MB_SIZE; ix++, ic++) {
                a[off + ix] = clip(pred[ic] + error[ic]);
            }
            off += frameWidthY;
        }
    }

    /**
     * Stores the prediction of a macroblock in the current VOP ({@code assignPredToCurrQ}).
     */
    final void assignPredToCurrQ(int[] y, int offY, int[] u, int[] v, int offUV) {
        var widthY = MB_SIZE * rrvScale;
        var widthUV = BLOCK_SIZE * rrvScale;
        var py = 0;
        var puv = 0;
        for (var i = 0; i < BLOCK_SIZE * rrvScale; i++) {
            System.arraycopy(predMBY, py, y, offY, widthY);
            System.arraycopy(predMBU, puv, u, offUV, widthUV);
            System.arraycopy(predMBV, puv, v, offUV, widthUV);
            py += widthY;
            puv += widthUV;
            offY += frameWidthY;
            offUV += frameWidthUV;
            System.arraycopy(predMBY, py, y, offY, widthY);
            py += widthY;
            offY += frameWidthY;
        }
    }

    /**
     * Stores the prediction of an alpha plane macroblock in the current VOP ({@code assignAlphaPredToCurrQ}).
     */
    final void assignAlphaPredToCurrQ(int[] a, int off, int aux) {
        var pred = predMBA[aux];
        for (var i = 0; i < MB_SIZE; i++) {
            System.arraycopy(pred, i * MB_SIZE, a, off, MB_SIZE);
            off += frameWidthY;
        }
    }

    /**
     * {@return the index of the macroblock of the future reference VOP at a position, or -1 when it is missing or
     * transparent ({@code findColocatedMB})}
     */
    final int findColocatedMB(int mbx, int mby) {
        if (codedFutureRef && mbx < numMBXRef && mbx >= 0 && mby < numMBYRef && mby >= 0) {
            var index = mbx + mby * numMBXRef;
            if (mbmdRef[index].transpStatus[0] != MbMode.ALL) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Downsamples the motion compensated binary alpha block by a conversion ratio ({@code downSampleShapeMCPred}).
     */
    final void downSampleShapeMCPred(int[] src, int[] dst, int rate) {
        var bdrThresh = rate > 2 ? OPAQUE : 0;
        var threshold = switch (rate) {
            case 2 -> OPAQUE;
            case 4 -> 7 * OPAQUE;
            default -> 0;
        };
        var dstSize = 2 * MC_BAB_BORDER + (MC_BAB_SIZE - 2 * MC_BAB_BORDER) / rate;
        var srcScan = (MC_BAB_SIZE + 1) * MC_BAB_BORDER;
        var dstScan = (dstSize + 1) * MC_BAB_BORDER;
        var rateSqd = rate * rate;
        var bdr1 = MC_BAB_SIZE * MC_BAB_BORDER;
        var bdr2 = 2 * MC_BAB_SIZE - 1;
        var bdr3 = MC_BAB_BORDER;
        var bdr4 = MC_BAB_SIZE * (MC_BAB_SIZE - 1) + MC_BAB_BORDER;
        var dstBdr1 = dstSize * MC_BAB_BORDER;
        var dstBdr2 = 2 * dstSize - 1;
        var dstBdr3 = MC_BAB_BORDER;
        var dstBdr4 = dstSize * (dstSize - 1) + MC_BAB_BORDER;
        for (var y = MC_BAB_BORDER; y < dstSize - MC_BAB_BORDER; y++) {
            for (var x = MC_BAB_BORDER; x < dstSize - MC_BAB_BORDER; x++, dstScan++) {
                var sum = 0;
                for (var i = 0; i < rateSqd; i++) {
                    sum += src[srcScan + MC_SCAN[i]];
                }
                dst[dstScan] = sum > threshold ? OPAQUE : TRANSPARENT;
                srcScan += rate;
            }
            dstScan += 2 * MC_BAB_BORDER;
            srcScan += MC_BAB_SIZE * rate - MC_BAB_SIZE + 2 * MC_BAB_BORDER;
            int sum1 = 0;
            int sum2 = 0;
            int sum3 = 0;
            int sum4 = 0;
            for (var i = 0; i < rate; i++) {
                sum1 += src[bdr1 + i * MC_BAB_SIZE];
                sum2 += src[bdr2 + i * MC_BAB_SIZE];
                sum3 += src[bdr3 + i];
                sum4 += src[bdr4 + i];
            }
            dst[dstBdr1] = sum1 > bdrThresh ? OPAQUE : TRANSPARENT;
            dst[dstBdr2] = sum2 > bdrThresh ? OPAQUE : TRANSPARENT;
            dst[dstBdr3++] = sum3 > bdrThresh ? OPAQUE : TRANSPARENT;
            dst[dstBdr4++] = sum4 > bdrThresh ? OPAQUE : TRANSPARENT;
            dstBdr1 += dstSize;
            dstBdr2 += dstSize;
            bdr1 += MC_BAB_SIZE * rate;
            bdr2 += MC_BAB_SIZE * rate;
            bdr3 += rate;
            bdr4 += rate;
        }
        dst[0] = src[0];
        dst[dstSize - 1] = src[MC_BAB_SIZE - 1];
        dst[(dstSize - 1) * dstSize] = src[(MC_BAB_SIZE - 1) * MC_BAB_SIZE];
        dst[dstSize * dstSize - 1] = src[MC_BAB_SIZE * MC_BAB_SIZE - 1];
    }

    private static final int[] MC_SCAN = {0, 1, MC_BAB_SIZE, MC_BAB_SIZE + 1, 2, 3, MC_BAB_SIZE + 2, MC_BAB_SIZE + 3,
            2 * MC_BAB_SIZE, 2 * MC_BAB_SIZE + 1, 2 * MC_BAB_SIZE + 2, 2 * MC_BAB_SIZE + 3, 3 * MC_BAB_SIZE,
            3 * MC_BAB_SIZE + 1, 3 * MC_BAB_SIZE + 2, 3 * MC_BAB_SIZE + 3};

    /**
     * Upsamples a downsampled binary alpha block to 16x16 with the adaptive interpolation filter
     * ({@code upSampleShape}).
     */
    final void upSampleShape(int[] src, int[] dst) {
        if (inverseCR == 2) {
            adaptiveUpSampleShape(src, dst, 8, 8);
            return;
        }
        var tmp = upSampleTmp;
        adaptiveUpSampleShape(src, tmp, 4, 4);
        var sizeTmp = 12;
        var sizeSrc = 8;
        tmp[0] = src[0];
        tmp[1] = src[1];
        tmp[sizeTmp] = src[sizeSrc];
        tmp[sizeTmp + 1] = src[sizeSrc + 1];
        tmp[sizeTmp - 2] = src[sizeSrc - 2];
        tmp[sizeTmp - 1] = src[sizeSrc - 1];
        tmp[(sizeTmp << 1) - 2] = src[(sizeSrc << 1) - 2];
        tmp[(sizeTmp << 1) - 1] = src[(sizeSrc << 1) - 1];
        for (var j = 0; j < 2; j++) {
            for (var i = BAB_BORDER; i < sizeTmp - BAB_BORDER; i++) {
                tmp[j * sizeTmp + i] = src[j * sizeSrc + i / 2 + 1];
            }
        }
        for (var i = 0; i < 2; i++) {
            for (var j = BAB_BORDER; j < sizeTmp - BAB_BORDER; j++) {
                tmp[j * sizeTmp + i] = src[(j / 2 + 1) * sizeSrc + i];
            }
        }
        adaptiveUpSampleShape(tmp, dst, 8, 8);
    }

    private static int contextUS(int a, int b, int c, int d, int e, int f, int g, int h) {
        return a + (b << 1) + (c << 2) + (d << 3) + (e << 4) + (f << 5) + (g << 6) + (h << 7);
    }

    private static int refValue(int[] src, int row, int x, int y, int hSize, int vSize) {
        var stride = hSize + BAB_BORDER_BOTH;
        if (x >= 0 && x < hSize && y >= 0 && y < vSize) {
            return src[row + y * stride + x] > 0 ? 1 : 0;
        }
        if (y < 0 || (x < 0 && y < vSize)) {
            return src[row + y * stride + x] > 0 ? 1 : 0;
        }
        var xLim = x < 0 ? 0 : Math.min(x, hSize - 1);
        var yLim = y < 0 ? 0 : Math.min(y, vSize - 1);
        return src[row + yLim * stride + xLim] > 0 ? 1 : 0;
    }

    private void adaptiveUpSampleShape(int[] src, int[] dst, int hSize, int vSize) {
        var x = upSampleX;
        var y = upSampleY;
        var val = upSampleValues;
        var widthUpBorder = (hSize << 1) + BAB_BORDER_BOTH;
        var row = BAB_BORDER + BAB_BORDER * (hSize + BAB_BORDER_BOTH);
        var th = RefTables.CH_INTERPOLATION_FILTER_TH;
        for (var j = -1; j < vSize; j++) {
            y[0] = y[1] = y[6] = y[7] = j;
            y[2] = y[3] = y[8] = y[9] = j + 1;
            y[4] = y[5] = j - 1;
            y[10] = y[11] = j + 2;
            var pySt = j > -1 ? 0 : 1;
            var pyEn = j < vSize - 1 ? 2 : 1;
            for (var i = -1; i < hSize; i++) {
                x[0] = x[2] = x[4] = x[10] = i;
                x[1] = x[3] = x[5] = x[11] = i + 1;
                x[6] = x[8] = i - 1;
                x[7] = x[9] = i + 2;
                for (var m = 0; m < 12; m++) {
                    val[m] = refValue(src, row, x[m], y[m], hSize, vSize);
                }
                var pxSt = i > -1 ? 0 : 1;
                var pxEn = i < hSize - 1 ? 2 : 1;
                var otherTotal = val[4] + val[5] + val[6] + val[7] + val[8] + val[9] + val[10] + val[11];
                for (var py = pySt; py < pyEn; py++) {
                    var pyP = ((j << 1) + 1 + py + BAB_BORDER) * widthUpBorder + (i << 1) + 1;
                    for (var px = pxSt; px < pxEn; px++) {
                        int context;
                        int rvalue;
                        if (px < 1 && py < 1) {
                            context = contextUS(val[5], val[4], val[6], val[8], val[10], val[11], val[9], val[7]);
                            rvalue = (val[0] << 2) + ((val[1] + val[2] + val[3]) << 1) + otherTotal;
                        } else if (py < 1) {
                            context = contextUS(val[9], val[7], val[5], val[4], val[6], val[8], val[10], val[11]);
                            rvalue = (val[1] << 2) + ((val[0] + val[2] + val[3]) << 1) + otherTotal;
                        } else if (px < 1) {
                            context = contextUS(val[6], val[8], val[10], val[11], val[9], val[7], val[5], val[4]);
                            rvalue = (val[2] << 2) + ((val[1] + val[0] + val[3]) << 1) + otherTotal;
                        } else {
                            context = contextUS(val[10], val[11], val[9], val[7], val[5], val[4], val[6], val[8]);
                            rvalue = (val[3] << 2) + ((val[1] + val[2] + val[0]) << 1) + otherTotal;
                        }
                        dst[pyP + px + BAB_BORDER] = rvalue > th[context] ? OPAQUE : TRANSPARENT;
                    }
                }
            }
        }
    }

    /**
     * Copies the two rows above and the two columns left of a binary alpha block from the reconstructed shape
     * ({@code copyLeftTopBorderFromVOP}).
     *
     * @param src the shape plane of the VOP
     * @param srcOff the index of the top-left sample of the macroblock
     * @param dst the 20x20 block
     */
    final void copyLeftTopBorderFromVOP(int[] src, int srcOff, int[] dst) {
        var top1 = srcOff - BAB_BORDER * frameWidthY - BAB_BORDER;
        var top2 = top1 + frameWidthY;
        var dstTop2 = TOTAL_BAB_SIZE;
        for (var i = 0; i < TOTAL_BAB_SIZE; i++) {
            boolean none;
            if (i < BAB_BORDER) {
                none = vpNoLeftTop;
            } else if (i < TOTAL_BAB_SIZE - BAB_BORDER) {
                none = vpNoTop;
            } else {
                none = vpNoRightTop;
            }
            dst[i] = none ? 0 : src[top1 + i];
            dst[dstTop2 + i] = none ? 0 : src[top2 + i];
        }
        var left1 = top1 + BAB_BORDER * frameWidthY;
        var left2 = left1 + 1;
        var d = BAB_BORDER * TOTAL_BAB_SIZE;
        for (var i = BAB_BORDER; i < TOTAL_BAB_SIZE; i++) {
            if (!vpNoLeft) {
                dst[d] = src[left1];
                dst[d + 1] = src[left2];
            } else {
                dst[d] = 0;
                dst[d + 1] = 0;
            }
            left1 += frameWidthY;
            left2 += frameWidthY;
            d += TOTAL_BAB_SIZE;
        }
        if (!vpNoLeft) {
            var last1 = src[left1 - (BAB_BORDER + 1) * frameWidthY];
            var last2 = src[left2 - (BAB_BORDER + 1) * frameWidthY];
            for (var i = 0; i < BAB_BORDER; i++) {
                d -= TOTAL_BAB_SIZE;
                dst[d] = last1;
                dst[d + 1] = last2;
            }
        }
    }

    /**
     * Subsamples the two rows above and the two columns left of a binary alpha block from the reconstructed shape
     * for a conversion ratio above 1 ({@code subsampleLeftTopBorderFromVOP}).
     */
    final void subsampleLeftTopBorderFromVOP(int[] src, int srcOff, int[] dst) {
        var top1 = srcOff - BAB_BORDER * frameWidthY - BAB_BORDER;
        var top2 = top1 + frameWidthY;
        var left1 = top1;
        var left2 = left1 + 1;
        var width = widthCurrBAB;
        var dstTop2 = width;
        var dstLeft2 = 1;
        var thresh = inverseCR == 2 ? 0 : OPAQUE;
        for (int sub = BAB_BORDER, interval = BAB_BORDER; sub < width - BAB_BORDER; sub++, interval += inverseCR) {
            int sumTop1 = 0;
            int sumTop2 = 0;
            int sumLeft1 = 0;
            int sumLeft2 = 0;
            for (var p = 0; p < inverseCR; p++) {
                sumTop1 += src[top1 + interval + p];
                sumTop2 += src[top2 + interval + p];
                sumLeft1 += src[left1 + (interval + p) * frameWidthY];
                sumLeft2 += src[left2 + (interval + p) * frameWidthY];
            }
            if (!vpNoTop) {
                dst[sub] = sumTop1 > thresh ? OPAQUE : TRANSPARENT;
                dst[dstTop2 + sub] = sumTop2 > thresh ? OPAQUE : TRANSPARENT;
            } else {
                dst[sub] = 0;
                dst[dstTop2 + sub] = 0;
            }
            if (!vpNoLeft) {
                dst[sub * width] = sumLeft1 > thresh ? OPAQUE : TRANSPARENT;
                dst[dstLeft2 + sub * width] = sumLeft2 > thresh ? OPAQUE : TRANSPARENT;
            } else {
                dst[sub * width] = 0;
                dst[dstLeft2 + sub * width] = 0;
            }
        }
        for (var b = 0; b < BAB_BORDER; b++) {
            if (!vpNoLeftTop) {
                dst[b] = src[top1 + b];
                dst[dstTop2 + b] = src[top2 + b];
            } else {
                dst[b] = 0;
                dst[dstTop2 + b] = 0;
            }
            if (!vpNoRightTop) {
                dst[width - 1 - b] = src[top1 + TOTAL_BAB_SIZE - 1 - b];
                dst[dstTop2 + width - 1 - b] = src[top2 + TOTAL_BAB_SIZE - 1 - b];
            } else {
                dst[width - 1 - b] = 0;
                dst[dstTop2 + width - 1 - b] = 0;
            }
        }
        dst[(width - 1) * width] = dst[(width - 2) * width] = dst[(width - 3) * width];
        dst[dstLeft2 + (width - 1) * width] = dst[dstLeft2 + (width - 2) * width] = dst[dstLeft2 + (width - 3)
                * width];
    }

    /**
     * Repeats the last two columns and rows of a block into its right and bottom border
     * ({@code makeRightBottomBorder}).
     */
    static void makeRightBottomBorder(int[] src, int width) {
        var d = BAB_BORDER * width + width - BAB_BORDER;
        for (var i = 0; i < width - 2 * BAB_BORDER; i++) {
            src[d] = src[d - 1];
            src[d + 1] = src[d];
            d += width;
        }
        d = (width - BAB_BORDER) * width;
        for (var i = 0; i < width; i++) {
            src[d] = src[d - width];
            src[d + width] = src[d];
            d++;
        }
    }

    /**
     * Copies the right and bottom border of a binary alpha block from another block ({@code makeRightBottomBorder}
     * of the scalable shape coding).
     */
    final void makeRightBottomBorder(int[] dst, int dstWidth, int[] src, int srcOff, int srcWidth) {
        var d = BAB_BORDER * dstWidth + dstWidth - BAB_BORDER;
        var s = srcOff + MB_SIZE;
        for (var i = 0; i < dstWidth - 2 * BAB_BORDER; i++) {
            if (!vpNoRight) {
                dst[d] = src[s];
                dst[d + 1] = src[s + 1];
            } else {
                dst[d] = 0;
                dst[d + 1] = 0;
            }
            d += dstWidth;
            s += srcWidth;
        }
        d += BAB_BORDER - dstWidth;
        s -= MB_SIZE + 2;
        for (var i = 0; i < BAB_BORDER; i++) {
            if (!vpNoLeft && !vpNoBottom) {
                dst[d] = src[s];
                dst[d + dstWidth] = src[s + srcWidth];
            } else {
                dst[d] = 0;
                dst[d + dstWidth] = 0;
            }
            d++;
            s++;
        }
        for (var i = BAB_BORDER; i < dstWidth - BAB_BORDER; i++) {
            if (!vpNoBottom) {
                dst[d] = src[s];
                dst[d + dstWidth] = src[s + srcWidth];
            } else {
                dst[d] = 0;
                dst[d + dstWidth] = 0;
            }
            d++;
            s++;
        }
        if (!vpNoRight && !vpNoBottom) {
            dst[d] = src[s];
            dst[d + dstWidth] = src[s + srcWidth];
            dst[d + 1] = src[s + 1];
            dst[d + 1 + dstWidth] = src[s + 1 + srcWidth];
        } else {
            dst[d] = 0;
            dst[d + dstWidth] = 0;
            dst[d + 1] = 0;
            dst[d + 1 + dstWidth] = 0;
        }
    }

    final int contextSIHorizontal(int[] src, int at, int upperSkip, int bottomSkip) {
        var w = widthCurrBAB;
        var context = 0;
        context |= (src[at + w * bottomSkip + 1] == OPAQUE ? 1 : 0);
        context |= (src[at + w * bottomSkip] == OPAQUE ? 1 : 0) << 1;
        context |= (src[at + w * bottomSkip - 1] == OPAQUE ? 1 : 0) << 2;
        context |= (src[at - 1] == OPAQUE ? 1 : 0) << 3;
        context |= (src[at - w * upperSkip + 1] == OPAQUE ? 1 : 0) << 4;
        context |= (src[at - w * upperSkip] == OPAQUE ? 1 : 0) << 5;
        context |= (src[at - w * upperSkip - 1] == OPAQUE ? 1 : 0) << 6;
        return context;
    }

    final int contextSIVertical(int[] src, int at, int rightSkip, int leftSkip, int upperSkip, int bottomSkip) {
        var w = widthCurrBAB;
        var context = 0;
        context |= (src[at + w * bottomSkip + rightSkip] == OPAQUE ? 1 : 0);
        context |= (src[at + w * bottomSkip - leftSkip] == OPAQUE ? 1 : 0) << 1;
        context |= (src[at + rightSkip] == OPAQUE ? 1 : 0) << 2;
        context |= (src[at - leftSkip] == OPAQUE ? 1 : 0) << 3;
        context |= (src[at - upperSkip * w + rightSkip] == OPAQUE ? 1 : 0) << 4;
        context |= (src[at - upperSkip * w] == OPAQUE ? 1 : 0) << 5;
        context |= (src[at - upperSkip * w - leftSkip] == OPAQUE ? 1 : 0) << 6;
        return context;
    }

    /**
     * {@return the scan order of an enhancement layer binary alpha block, 0 for horizontal first
     * ({@code decideScanOrder})}
     */
    static int decideScanOrder(int[] pred) {
        var numRight = 0;
        var numBottom = 0;
        var base = MC_BAB_SIZE * MC_BAB_BORDER + MC_BAB_BORDER;
        for (var j = 1; j < MB_SIZE; j += 2) {
            for (var i = 1; i < MB_SIZE; i += 2) {
                var p = base + j * MC_BAB_SIZE + i;
                if (pred[p] != pred[p - 2]) {
                    numRight++;
                }
                if (pred[p] != pred[p - 2 * MC_BAB_SIZE]) {
                    numBottom++;
                }
            }
        }
        return numBottom <= numRight ? 0 : 1;
    }

    final int contextIntra(int[] src, int at) {
        var w = widthCurrBAB;
        var context = 0;
        context |= (src[at - 1] == OPAQUE ? 1 : 0);
        context |= (src[at - 2] == OPAQUE ? 1 : 0) << 1;
        context |= (src[at - w + 2] == OPAQUE ? 1 : 0) << 2;
        context |= (src[at - w + 1] == OPAQUE ? 1 : 0) << 3;
        context |= (src[at - w] == OPAQUE ? 1 : 0) << 4;
        context |= (src[at - w - 1] == OPAQUE ? 1 : 0) << 5;
        context |= (src[at - w - 2] == OPAQUE ? 1 : 0) << 6;
        context |= (src[at - 2 * w + 1] == OPAQUE ? 1 : 0) << 7;
        context |= (src[at - 2 * w] == OPAQUE ? 1 : 0) << 8;
        context |= (src[at - 2 * w - 1] == OPAQUE ? 1 : 0) << 9;
        return context;
    }

    final int contextIntraTranspose(int[] src, int at) {
        var w = widthCurrBAB;
        var context = 0;
        context |= (src[at - w] == OPAQUE ? 1 : 0);
        context |= (src[at - 2 * w] == OPAQUE ? 1 : 0) << 1;
        context |= (src[at - 1 + 2 * w] == OPAQUE ? 1 : 0) << 2;
        context |= (src[at - 1 + w] == OPAQUE ? 1 : 0) << 3;
        context |= (src[at - 1] == OPAQUE ? 1 : 0) << 4;
        context |= (src[at - w - 1] == OPAQUE ? 1 : 0) << 5;
        context |= (src[at - 1 - 2 * w] == OPAQUE ? 1 : 0) << 6;
        context |= (src[at - 2 + w] == OPAQUE ? 1 : 0) << 7;
        context |= (src[at - 2] == OPAQUE ? 1 : 0) << 8;
        context |= (src[at - 2 - w] == OPAQUE ? 1 : 0) << 9;
        return context;
    }

    final int contextInter(int[] src, int at, int[] pred, int predAt) {
        var w = widthCurrBAB;
        var predSize = w - 2 * BAB_BORDER + 2 * MC_BAB_BORDER;
        var context = 0;
        context |= (src[at - 1] == OPAQUE ? 1 : 0);
        context |= (src[at - w + 1] == OPAQUE ? 1 : 0) << 1;
        context |= (src[at - w] == OPAQUE ? 1 : 0) << 2;
        context |= (src[at - w - 1] == OPAQUE ? 1 : 0) << 3;
        context |= (pred[predAt + predSize] == OPAQUE ? 1 : 0) << 4;
        context |= (pred[predAt + 1] == OPAQUE ? 1 : 0) << 5;
        context |= (pred[predAt] == OPAQUE ? 1 : 0) << 6;
        context |= (pred[predAt - 1] == OPAQUE ? 1 : 0) << 7;
        context |= (pred[predAt - predSize] == OPAQUE ? 1 : 0) << 8;
        return context;
    }

    final int contextInterTranspose(int[] src, int at, int[] pred, int predAt) {
        var w = widthCurrBAB;
        var predSize = w - 2 * BAB_BORDER + 2 * MC_BAB_BORDER;
        var context = 0;
        context |= (src[at - w] == OPAQUE ? 1 : 0);
        context |= (src[at + w - 1] == OPAQUE ? 1 : 0) << 1;
        context |= (src[at - 1] == OPAQUE ? 1 : 0) << 2;
        context |= (src[at - w - 1] == OPAQUE ? 1 : 0) << 3;
        context |= (pred[predAt + 1] == OPAQUE ? 1 : 0) << 4;
        context |= (pred[predAt + predSize] == OPAQUE ? 1 : 0) << 5;
        context |= (pred[predAt] == OPAQUE ? 1 : 0) << 6;
        context |= (pred[predAt - predSize] == OPAQUE ? 1 : 0) << 7;
        context |= (pred[predAt - 1] == OPAQUE ? 1 : 0) << 8;
        return context;
    }

    /**
     * Fills the shape of a macroblock buffer and of the reference plane with one value
     * ({@code copyReconShapeToMbAndRef}).
     */
    final void copyReconShapeToMbAndRef(int[] dstMB, int[] refFrm, int refOff, int value) {
        Arrays.fill(dstMB, 0, MB_SQUARE_SIZE, value);
        for (var i = 0; i < MB_SIZE; i++) {
            Arrays.fill(refFrm, refOff, refOff + MB_SIZE, value);
            refOff += frameWidthY;
        }
    }

    /**
     * Copies the chrominance shape of a macroblock to the reference plane ({@code copyReconShapeUVToRef}).
     */
    final void copyReconShapeUVToRef(int[] refFrm, int refOff, int[] src) {
        for (var i = 0; i < BLOCK_SIZE; i++) {
            System.arraycopy(src, i * BLOCK_SIZE, refFrm, refOff, BLOCK_SIZE);
            refOff += frameWidthUV;
        }
    }

    /**
     * Copies the interior of a reconstructed binary alpha block to the macroblock buffer and the reference plane
     * ({@code copyReconShapeToMbAndRef}).
     */
    final void copyReconShapeToMbAndRef(int[] dstMB, int[] refFrm, int refOff, int[] src, int srcWidth,
                                        int border) {
        var s = srcWidth * border + border;
        var d = 0;
        for (var i = 0; i < MB_SIZE; i++) {
            System.arraycopy(src, s, dstMB, d, MB_SIZE);
            System.arraycopy(src, s, refFrm, refOff, MB_SIZE);
            refOff += frameWidthY;
            d += MB_SIZE;
            s += srcWidth;
        }
    }

    private static boolean shapeCoded(int shapeMode) {
        return shapeMode == MbMode.INTER_CAE_MVDZ || shapeMode == MbMode.INTER_CAE_MVDNZ
                || shapeMode == MbMode.MVDZ_NOUPDT || shapeMode == MbMode.MVDNZ_NOUPDT;
    }

    /**
     * Finds the prediction of the shape motion vector of a macroblock (7.5.2.1, {@code findShapeMVP}).
     *
     * @param result receives the prediction
     * @param mvOff the index of the macroblock's texture vectors in {@link #mv}
     * @param mbIndex the index of the macroblock
     */
    final void findShapeMVP(MotionVector result, int mvOff, MotionVector[] shapeVectors, int mbIndex, int mbx,
                            int mby) {
        var mbNum = vpMbNum(mbx, mby);
        var leftBndry = vpNoLeft(mbNum, mbx);
        var topBndry = vpNoTop(mbNum);
        var rightBndry = vpNoRightTop(mbNum, mbx);
        if (mbx == 0 && mby == 0) {
            result.setToZero();
        } else {
            result.set(shapeVectors[mbIndex - 1]);
        }
        if (!leftBndry && shapeCoded(mbmd[mbIndex - 1].shpmd)) {
            return;
        }
        if (mby > 0) {
            result.set(shapeVectors[mbIndex - numMBX]);
            if (!topBndry && shapeCoded(mbmd[mbIndex - numMBX].shpmd)) {
                return;
            }
            result.set(shapeVectors[mbIndex - numMBX + 1]);
            if (!rightBndry && shapeCoded(mbmd[mbIndex - numMBX + 1].shpmd)) {
                return;
            }
        }
        result.setToZero();
        if (!volmd.shapeOnly && (vopmd.predType == VopMode.PVOP
                || (uiSprite == 2 && vopmd.predType == VopMode.SPRITE))) {
            var subDim = volmd.quarterSample ? 4 : 2;
            var cur = mbmd[mbIndex];
            var cand = RefTables.INDEX_OF_CAND_BLK;
            if (!leftBndry && validBlock(cur, mbmd[mbIndex - 1], cand[3])) {
                if (!mbmd[mbIndex - 1].mcsel) {
                    var v = mv[mvOff - PVOP_MV_PER_REF_PER_MB + cand[3]];
                    result.setParts(v.trueX / subDim, v.trueY / subDim, 0, 0);
                }
                return;
            }
            if (mby != 0) {
                if (!topBndry && validBlock(cur, mbmd[mbIndex - numMBX], cand[4])) {
                    if (!mbmd[mbIndex - numMBX].mcsel) {
                        var v = mv[mvOff - numOfTotalMVPerRow + cand[4]];
                        result.setParts(v.trueX / subDim, v.trueY / subDim, 0, 0);
                    }
                    return;
                }
                if (!rightBndry && validBlock(cur, mbmd[mbIndex - numMBX + 1], cand[5])) {
                    if (!mbmd[mbIndex - numMBX + 1].mcsel) {
                        var v = mv[mvOff - numOfTotalMVPerRow + PVOP_MV_PER_REF_PER_MB + cand[5]];
                        result.setParts(v.trueX / subDim, v.trueY / subDim, 0, 0);
                    }
                }
            }
        }
    }

    /**
     * {@return whether a block of a macroblock can serve as a motion vector candidate ({@code validBlock})}
     */
    static boolean validBlock(MbMode current, MbMode mode, int blkn) {
        if (mode.transpStatus[0] == MbMode.ALL) {
            return false;
        }
        return mode != current || mode.transpStatus[blkn] != MbMode.ALL;
    }

    static int median(int a0, int a1, int a2) {
        if (a0 > a1) {
            if (a1 > a2) {
                return a1;
            }
            return Math.min(a0, a2);
        }
        if (a0 > a2) {
            return a0;
        }
        return Math.min(a1, a2);
    }

    private MotionVector candidate(int index) {
        return mv[index];
    }

    /**
     * Finds the prediction of a luminance motion vector, in half samples ({@code findMVpredGeneric}).
     *
     * @param pred receives the horizontal and vertical prediction
     * @param mvOff the index of the macroblock's first vector in {@link #mv}
     * @param blk the block, or 0 for the macroblock vector
     */
    final void findMVpredGeneric(int[] pred, int mvOff, int mbIndex, int blk, int mbx, int mby) {
        Arrays.fill(inBound, false);
        var count = 0;
        Arrays.fill(candX, 0);
        Arrays.fill(candY, 0);
        var mbNum = vpMbNum(mbx, mby);
        var leftBndry = vpNoLeft(mbNum, mbx);
        var topBndry = vpNoTop(mbNum);
        var rightBndry = vpNoRightTop(mbNum, mbx);
        var cur = mbmd[mbIndex];
        var cand = RefTables.INDEX_OF_CAND_BLK;
        var left = mbx > 0 ? mbmd[mbIndex - 1] : null;
        var top = mby > 0 ? mbmd[mbIndex - numMBX] : null;
        var topRight = mby > 0 && mbIndex - numMBX + 1 < mbmd.length ? mbmd[mbIndex - numMBX + 1] : null;
        if (cur.has4MVForward) {
            var c0 = cand[blk * 3];
            var c1 = cand[blk * 3 + 1];
            var c2 = cand[blk * 3 + 2];
            switch (blk) {
                case Y_BLOCK1 -> {
                    if (!leftBndry && validBlock(cur, left, c0)) {
                        count += take(0, mvOff - PVOP_MV_PER_REF_PER_MB + c0);
                    }
                    if (mby != 0) {
                        if (!topBndry && validBlock(cur, top, c1)) {
                            count += take(1, mvOff - numOfTotalMVPerRow + c1);
                        }
                        if (!rightBndry && validBlock(cur, topRight, c2)) {
                            count += take(2, mvOff - numOfTotalMVPerRow + PVOP_MV_PER_REF_PER_MB + c2);
                        }
                    }
                }
                case Y_BLOCK2 -> {
                    if (validBlock(cur, cur, c0)) {
                        count += take(0, mvOff + c0);
                    }
                    if (mby != 0) {
                        if (!topBndry && validBlock(cur, top, c1)) {
                            count += take(1, mvOff - numOfTotalMVPerRow + c1);
                        }
                        if (!rightBndry && validBlock(cur, topRight, c2)) {
                            count += take(2, mvOff - numOfTotalMVPerRow + PVOP_MV_PER_REF_PER_MB + c2);
                        }
                    }
                }
                case Y_BLOCK3 -> {
                    if (!leftBndry && validBlock(cur, left, c0)) {
                        count += take(0, mvOff - PVOP_MV_PER_REF_PER_MB + c0);
                    }
                    if (validBlock(cur, cur, c1)) {
                        count += take(1, mvOff + c1);
                    }
                    if (validBlock(cur, cur, c2)) {
                        count += take(2, mvOff + c2);
                    }
                }
                default -> {
                    if (validBlock(cur, cur, c0)) {
                        count += take(0, mvOff + c0);
                    }
                    if (validBlock(cur, cur, c1)) {
                        count += take(1, mvOff + c1);
                    }
                    if (validBlock(cur, cur, c2)) {
                        count += take(2, mvOff + c2);
                    }
                }
            }
        } else {
            if (!leftBndry && validBlock(cur, left, cand[3])) {
                count += take(0, mvOff - PVOP_MV_PER_REF_PER_MB + cand[3]);
            }
            if (mby != 0) {
                if (!topBndry && validBlock(cur, top, cand[4])) {
                    count += take(1, mvOff - numOfTotalMVPerRow + cand[4]);
                }
                if (!rightBndry && validBlock(cur, topRight, cand[5])) {
                    count += take(2, mvOff - numOfTotalMVPerRow + PVOP_MV_PER_REF_PER_MB + cand[5]);
                }
            }
        }
        if (count == 1) {
            for (var i = 0; i < 3; i++) {
                if (inBound[i]) {
                    pred[0] = candX[i];
                    pred[1] = candY[i];
                    return;
                }
            }
        }
        pred[0] = median(candX[0], candX[1], candX[2]);
        pred[1] = median(candY[0], candY[1], candY[2]);
    }

    private int take(int slot, int index) {
        var v = candidate(index);
        candX[slot] = v.trueX;
        candY[slot] = v.trueY;
        inBound[slot] = true;
        return 1;
    }

    /**
     * Finds the prediction of a B-VOP motion vector from the previous macroblock of the row of the same type
     * ({@code findMVpredictorOfBVOP}).
     */
    final void findMVpredictorOfBVOP(int[] pred, int mvOff, int mbIndex, int mbx) {
        pred[0] = 0;
        pred[1] = 0;
        var type = mbmd[mbIndex].mbType;
        for (var candidateX = mbx - 1; candidateX >= 0; candidateX--) {
            mbIndex--;
            mvOff -= BVOP_MV_PER_REF_PER_MB;
            var mode = mbmd[mbIndex];
            if (mode.skip) {
                return;
            }
            if (mode.mbType == type && mode.transpStatus[0] != MbMode.ALL) {
                pred[0] = mv[mvOff].trueX;
                pred[1] = mv[mvOff].trueY;
                return;
            }
        }
    }

    /**
     * Finds the chrominance vector of a macroblock with shape from the vectors of its opaque blocks
     * ({@code mvLookupUVWithShape}).
     *
     * @param result receives the horizontal and vertical chrominance vector
     */
    final void mvLookupUVWithShape(MbMode mode, MotionVector[] vectors, int mvOff, int[] result) {
        int dx = 0;
        int dy = 0;
        if (mode.has4MVForward || mode.mbType == MbMode.DIRECT) {
            var divisor = 0;
            for (var i = Y_BLOCK1; i <= Y_BLOCK4; i++) {
                var v = vectors[mvOff + i];
                if (mode.transpStatus[i] != MbMode.ALL) {
                    dx += volmd.quarterSample ? v.mvx : v.trueX;
                    dy += volmd.quarterSample ? v.mvy : v.trueY;
                    divisor += 4;
                }
            }
            var round = switch (divisor) {
                case 4 -> RefTables.MV_ROUND4;
                case 8 -> RefTables.MV_ROUND8;
                case 12 -> RefTables.MV_ROUND12;
                case 16 -> RefTables.MV_ROUND16;
                default -> throw CorruptStream.INSTANCE;
            };
            result[0] = Integer.signum(dx) * (round[Math.abs(dx) % divisor] + (Math.abs(dx) / divisor) * 2);
            result[1] = Integer.signum(dy) * (round[Math.abs(dy) % divisor] + (Math.abs(dy) / divisor) * 2);
        } else {
            var v = vectors[mvOff];
            dx = volmd.quarterSample ? v.mvx : v.trueX;
            dy = volmd.quarterSample ? v.mvy : v.trueY;
            result[0] = Integer.signum(dx) * (RefTables.MV_ROUND4[Math.abs(dx) % 4] + (Math.abs(dx) / 4) * 2);
            result[1] = Integer.signum(dy) * (RefTables.MV_ROUND4[Math.abs(dy) % 4] + (Math.abs(dy) / 4) * 2);
        }
    }

    /**
     * Gives the transparent blocks of a macroblock the vector of an opaque neighbor block
     * ({@code padMotionVectors}).
     */
    static void padMotionVectors(MbMode mode, MotionVector[] vectors, int mvOff) {
        var t = mode.transpStatus;
        if (t[0] == MbMode.ALL) {
            return;
        }
        if (t[1] == MbMode.ALL) {
            vectors[mvOff + 1].set(vectors[mvOff + (t[2] != MbMode.ALL ? 2 : t[3] != MbMode.ALL ? 3 : 4)]);
        }
        if (t[2] == MbMode.ALL) {
            vectors[mvOff + 2].set(vectors[mvOff + (t[1] != MbMode.ALL ? 1 : t[4] != MbMode.ALL ? 4 : 3)]);
        }
        if (t[3] == MbMode.ALL) {
            vectors[mvOff + 3].set(vectors[mvOff + (t[4] != MbMode.ALL ? 4 : t[1] != MbMode.ALL ? 1 : 2)]);
        }
        if (t[4] == MbMode.ALL) {
            vectors[mvOff + 4].set(vectors[mvOff + (t[3] != MbMode.ALL ? 3 : t[2] != MbMode.ALL ? 2 : 1)]);
        }
    }
}
