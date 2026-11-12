/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/gmc_util.cpp and gmc_motion.cpp, and the interpolation helpers of sys/spt.cpp). Copyright 1996-2000 Microsoft
 * Corporation, Hitachi, Ltd., and the contributors named in the sources; ISO/IEC free license for products conforming
 * to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

/**
 * Global motion compensation of S(GMC)-VOPs: the warped prediction of a macroblock from the warping points and the
 * averaged vector of a GMC macroblock (7.8.7).
 */
abstract class ObjectGmc extends ObjectBase {
    /**
     * Predicts the luminance of a macroblock by global motion compensation ({@code FindGlobalPredForGMC}).
     *
     * @param cx the horizontal position of the macroblock
     * @param cy the vertical position of the macroblock
     * @param pred receives the 16x16 prediction
     * @param predOffset the index of the prediction in {@code pred}
     * @param ref the reference plane, whose index 0 is the top-left of the reference frame
     */
    final void findGlobalPredForGMC(int cx, int cy, int[] pred, int predOffset, int[] ref) {
        switch (numOfPnts) {
            case 0 -> stationalWarpForGMC(cx, cy, pred, predOffset, ref);
            case 1 -> translationalWarpForGMC(cx, cy, pred, predOffset, ref);
            default -> fastAffineWarpForGMC(cx, cy, pred, predOffset, ref);
        }
    }

    /**
     * Predicts the chrominance of a macroblock by global motion compensation ({@code FindGlobalChromPredForGMC}).
     */
    final void findGlobalChromPredForGMC(int cx, int cy, int[] predU, int[] predV) {
        switch (numOfPnts) {
            case 0 -> stationalWarpChromForGMC(cx, cy, predU, predV);
            case 1 -> translationalWarpChromForGMC(cx, cy, predU, predV);
            default -> fastAffineWarpChromForGMC(cx, cy, predU, predV);
        }
    }

    private void stationalWarpForGMC(int cx, int cy, int[] pred, int predOffset, int[] ref) {
        var refWidth = rctRefFrameY.right - rctRefFrameY.left;
        var refLeft = rctRefVOPY0.left + EXPANDY_REFVOP;
        var refTop = rctRefVOPY0.top + EXPANDY_REFVOP;
        var refRight = rctRefVOPY0.right - EXPANDY_REFVOP + 31;
        var refBottom = rctRefVOPY0.bottom - EXPANDY_REFVOP + 31;
        var prev = (EXPANDY_REF_FRAME - 16) * frameWidthY + EXPANDY_REF_FRAME - 16;
        for (int yj = 0, ty = cy; yj < MB_SIZE; yj++, ty++) {
            for (int xi = 0, tx = cx; xi < MB_SIZE; xi++, tx++) {
                var x = Math.min(Math.max(tx, refLeft), refRight);
                var y = Math.min(Math.max(ty, refTop), refBottom);
                pred[predOffset + xi + yj * MB_SIZE] = ref[prev + y * refWidth + x];
            }
        }
    }

    private void stationalWarpChromForGMC(int cxy, int cyy, int[] predU, int[] predV) {
        var accuracy = warpingAccuracy + 1;
        var refWidth = (rctRefFrameY.right - rctRefFrameY.left) >> 1;
        var refLeft = ((rctRefVOPY0.left / 2) + EXPANDUV_REFVOP) << accuracy;
        var refTop = ((rctRefVOPY0.top / 2) + EXPANDUV_REFVOP) << accuracy;
        var refRight = ((rctRefVOPY0.right / 2) - EXPANDUV_REFVOP + 15) << accuracy;
        var refBottom = ((rctRefVOPY0.bottom / 2) - EXPANDUV_REFVOP + 15) << accuracy;
        var u = refQ0.u.pixels;
        var v = refQ0.v.pixels;
        var prev = (EXPANDUV_REF_FRAME - 8) * frameWidthUV + EXPANDUV_REF_FRAME - 8;
        var cx = cxy >> 1;
        var cy = cyy >> 1;
        for (int yj = 0, ty = cy; yj < MB_SIZE / 2; yj++, ty++) {
            for (int xi = 0, tx = cx; xi < MB_SIZE / 2; xi++, tx++) {
                var x = tx;
                var y = ty;
                if (x < refLeft || y < refTop || x > refRight || y > refBottom) {
                    if (x < refLeft) {
                        x = refLeft;
                    }
                    if (x > refRight) {
                        x = refRight;
                    }
                    if (y < refTop) {
                        y = refTop;
                    }
                    if (y > refBottom) {
                        y = refBottom;
                    }
                }
                var at = prev + y * refWidth + x;
                predU[xi + yj * BLOCK_SIZE] = u[at];
                predV[xi + yj * BLOCK_SIZE] = v[at];
            }
        }
    }

    private void translationalWarpForGMC(int cx, int cy, int[] pred, int predOffset, int[] ref) {
        var accuracy = warpingAccuracy + 1;
        var refWidth = rctRefFrameY.right - rctRefFrameY.left;
        var refLeft = (rctRefVOPY0.left + EXPANDY_REFVOP) << accuracy;
        var refTop = (rctRefVOPY0.top + EXPANDY_REFVOP) << accuracy;
        var refRight = (rctRefVOPY0.right - EXPANDY_REFVOP + 31) << accuracy;
        var refBottom = (rctRefVOPY0.bottom - EXPANDY_REFVOP + 31) << accuracy;
        var prev = (EXPANDY_REF_FRAME - 16) * frameWidthY + EXPANDY_REF_FRAME - 16;
        var powerWA = 1 << accuracy;
        var warpMask = powerWA - 1;
        var sqrWac = accuracy << 1;
        var bias = 1 << (sqrWac - 1);
        var xi0 = rctCurrVOPY.left;
        var yj0 = rctCurrVOPY.top;
        var xi0prime = ((int) ((dstQx[0] + 16) * 2.0)) << warpingAccuracy;
        var yj0prime = ((int) ((dstQy[0] + 16) * 2.0)) << warpingAccuracy;
        var cyy = yj0prime;
        xi0prime += powerWA * (cx - xi0);
        cyy += powerWA * (cy - yj0);
        for (var yj = 0; yj < MB_SIZE; cyy += powerWA, yj++) {
            for (int xi = 0, cxx = xi0prime; xi < MB_SIZE; cxx += powerWA, xi++) {
                var x = Math.min(Math.max(cxx, refLeft), refRight);
                var y = Math.min(Math.max(cyy, refTop), refBottom);
                var offset = (y >> accuracy) * refWidth + (x >> accuracy);
                pred[predOffset + xi + yj * MB_SIZE] = interpolatePixelValue(ref, prev + offset, refWidth,
                        x & warpMask, y & warpMask, powerWA, bias, sqrWac);
            }
        }
    }

    private void translationalWarpChromForGMC(int cxy0, int cyy0, int[] predU, int[] predV) {
        var accuracy = warpingAccuracy + 1;
        var refWidth = (rctRefFrameY.right - rctRefFrameY.left) >> 1;
        var refLeft = ((rctRefVOPY0.left / 2) + EXPANDUV_REFVOP) << accuracy;
        var refTop = ((rctRefVOPY0.top / 2) + EXPANDUV_REFVOP) << accuracy;
        var refRight = ((rctRefVOPY0.right / 2) - EXPANDUV_REFVOP + 15) << accuracy;
        var refBottom = ((rctRefVOPY0.bottom / 2) - EXPANDUV_REFVOP + 15) << accuracy;
        var u = refQ0.u.pixels;
        var v = refQ0.v.pixels;
        var prev = (EXPANDUV_REF_FRAME - 8) * frameWidthUV + EXPANDUV_REF_FRAME - 8;
        var powerWA = 1 << accuracy;
        var warpMask = powerWA - 1;
        var sqrWac = accuracy << 1;
        var bias = 1 << (sqrWac - 1);
        var xi0 = rctCurrVOPY.left << warpingAccuracy;
        var yj0 = rctCurrVOPY.top << warpingAccuracy;
        var cx = cxy0 >> 1;
        var cy = cyy0 >> 1;
        var xi0prime = ((int) (dstQx[0] * 2.0)) << warpingAccuracy;
        var yj0prime = ((int) (dstQy[0] * 2.0)) << warpingAccuracy;
        var cxy = ((xi0prime >> 1) | (xi0prime & 1)) + (8 << accuracy) - xi0;
        var cyy = ((yj0prime >> 1) | (yj0prime & 1)) + (8 << accuracy) - yj0;
        cxy += powerWA * cx;
        cyy += powerWA * cy;
        for (var yj = 0; yj < MB_SIZE / 2; cyy += powerWA, yj++) {
            for (int xi = 0, cxx = cxy; xi < MB_SIZE / 2; cxx += powerWA, xi++) {
                var x = Math.min(Math.max(cxx, refLeft), refRight);
                var y = Math.min(Math.max(cyy, refTop), refBottom);
                var offset = prev + (y >> accuracy) * refWidth + (x >> accuracy);
                var rx = x & warpMask;
                var ry = y & warpMask;
                predU[xi + yj * BLOCK_SIZE] = interpolatePixelValue(u, offset, refWidth, rx, ry, powerWA, bias,
                        sqrWac);
                predV[xi + yj * BLOCK_SIZE] = interpolatePixelValue(v, offset, refWidth, rx, ry, powerWA, bias,
                        sqrWac);
            }
        }
    }

    /**
     * The affine parameters of the warping of the current VOP, computed by {@link #affine}.
     */
    private int ipa;
    private int ipb;
    private int ipd;
    private int ipe;
    private int cxyStart;
    private int cyyStart;
    private int idnmPwr;

    /**
     * Computes the affine warp parameters of the luminance (chroma {@code false}) or chrominance warp, with the
     * rounding of {@code FastAffineWarpForGMC}, {@code FastAffineWarpChromForGMC} or, for motion,
     * {@code FastAffineWarpMotion}.
     *
     * @param mode 0 for the luminance warp, 1 for the chrominance warp, 2 for the motion vector
     */
    private void affine(int mode) {
        var accuracy = warpingAccuracy + 1;
        var rPwr = 4 - accuracy;
        var rat = 1 << (4 - accuracy);
        var xi0 = rctCurrVOPY.left;
        var yj0 = rctCurrVOPY.top;
        var xi1 = rctCurrVOPY.right;
        var yj1 = rctCurrVOPY.top;
        var xi0prime = ((int) (dstQx[0] * 2.0)) << 3;
        var yj0prime = ((int) (dstQy[0] * 2.0)) << 3;
        var xi1prime = ((int) (dstQx[1] * 2.0)) << 3;
        var yj1prime = ((int) (dstQy[1] * 2.0)) << 3;
        int xi2 = 0;
        int yj2 = 0;
        int xi2prime = 0;
        int yj2prime = 0;
        if (numOfPnts == 3) {
            xi2 = rctCurrVOPY.left;
            yj2 = rctCurrVOPY.bottom;
            xi2prime = ((int) (dstQx[2] * 2.0)) << 3;
            yj2prime = ((int) (dstQy[2] * 2.0)) << 3;
        }
        var width = xi1 - xi0;
        var vw = 1;
        var vwPwr = 0;
        while (vw < width) {
            vw <<= 1;
            vwPwr++;
        }
        int height = 0;
        int vh = 1;
        int vhPwr = 0;
        int vwh = 0;
        int vwhPwr = 0;
        if (numOfPnts == 3) {
            height = yj2 - yj0;
            while (vh < height) {
                vh <<= 1;
                vhPwr++;
            }
            vwh = vw * vh;
            vwhPwr = vwPwr + vhPwr;
        }
        var xi1dprime = linearExtrapolation(xi0, xi1, xi0prime, xi1prime, width, vw) + ((xi0 + vw) << 4);
        var yj1dprime = linearExtrapolation(yj0, yj1, yj0prime, yj1prime, width, vw) + (yj0 << 4);
        int xi2dprime = 0;
        int yj2dprime = 0;
        if (numOfPnts == 3) {
            xi2dprime = linearExtrapolation(xi0, xi2, xi0prime, xi2prime, height, vh) + (xi0 << 4);
            yj2dprime = linearExtrapolation(yj0, yj2, yj0prime, yj2prime, height, vh) + ((yj0 + vh) << 4);
        }
        var offset = mode == 2 ? 0 : 256;
        xi0prime += offset;
        yj0prime += offset;
        xi1prime = xi1dprime + offset;
        yj1prime = yj1dprime + offset;
        if (numOfPnts == 3) {
            xi2prime = xi2dprime + offset;
            yj2prime = yj2dprime + offset;
            if (vwPwr <= vhPwr) {
                vh /= vw;
                vwh /= vw;
                vw = 1;
                vhPwr -= vwPwr;
                vwhPwr -= vwPwr;
                vwPwr = 0;
            } else {
                vw /= vh;
                vwh /= vh;
                vh = 1;
                vwPwr -= vhPwr;
                vwhPwr -= vhPwr;
                vhPwr = 0;
            }
        }
        int ipc;
        int ipf;
        if (numOfPnts == 2) {
            ipa = xi1prime - xi0prime;
            ipb = -(yj1prime - yj0prime);
            ipd = -ipb;
            ipe = ipa;
            if (mode == 1) {
                ipc = 2 * vw * xi0prime - 16 * vw;
                ipf = 2 * vw * yj0prime - 16 * vw;
                cxyStart = ipa + ipb + ipc + 2 * rat * vw;
                cyyStart = ipd + ipe + ipf + 2 * rat * vw;
                idnmPwr = rPwr + vwPwr + 2;
            } else {
                ipc = xi0prime * vw;
                ipf = yj0prime * vw;
                cxyStart = mode == 2 ? ipc : ipc + rat * vw / 2;
                cyyStart = mode == 2 ? ipf : ipf + rat * vw / 2;
                rounding = rat * vw / 2;
                idnmPwr = rPwr + vwPwr;
            }
        } else {
            ipa = (xi1prime - xi0prime) * vh;
            ipb = (xi2prime - xi0prime) * vw;
            ipd = (yj1prime - yj0prime) * vh;
            ipe = (yj2prime - yj0prime) * vw;
            if (mode == 1) {
                ipc = 2 * vwh * xi0prime - 16 * vwh;
                ipf = 2 * vwh * yj0prime - 16 * vwh;
                cxyStart = ipa + ipb + ipc + 2 * rat * vwh;
                cyyStart = ipd + ipe + ipf + 2 * rat * vwh;
                idnmPwr = rPwr + vwhPwr + 2;
            } else {
                ipc = xi0prime * vwh;
                ipf = yj0prime * vwh;
                cxyStart = mode == 2 ? ipc : ipc + rat * vwh / 2;
                cyyStart = mode == 2 ? ipf : ipf + rat * vwh / 2;
                rounding = rat * vwh / 2;
                idnmPwr = rPwr + vwhPwr;
            }
        }
    }

    /**
     * The rounding term {@code ipp} of the averaged motion vector.
     */
    private int rounding;
    private final int[] quotRes = new int[2];

    private void fastAffineWarpForGMC(int cx, int cy, int[] pred, int predOffset, int[] ref) {
        var accuracy = warpingAccuracy + 1;
        var refWidth = rctRefFrameY.right - rctRefFrameY.left;
        var refLeft = (rctRefVOPY0.left + EXPANDY_REFVOP) << accuracy;
        var refTop = (rctRefVOPY0.top + EXPANDY_REFVOP) << accuracy;
        var refRight = (rctRefVOPY0.right - EXPANDY_REFVOP + 31) << accuracy;
        var refBottom = (rctRefVOPY0.bottom - EXPANDY_REFVOP + 31) << accuracy;
        var prev = (EXPANDY_REF_FRAME - 16) * frameWidthY + EXPANDY_REF_FRAME - 16;
        var powerWA = 1 << accuracy;
        var warpMask = powerWA - 1;
        var sqrWac = accuracy << 1;
        var bias = 1 << (sqrWac - 1);
        affine(0);
        var xi0 = rctCurrVOPY.left;
        var yj0 = rctCurrVOPY.top;
        var cxy = cxyStart + ipb * (cy - yj0) + ipa * (cx - xi0);
        var cyy = cyyStart + ipe * (cy - yj0) + ipd * (cx - xi0);
        fourSlashesShift(cxy, idnmPwr);
        var cxyI = quotRes[0];
        var cxyF = quotRes[1];
        fourSlashesShift(cyy, idnmPwr);
        var cyyI = quotRes[0];
        var cyyF = quotRes[1];
        fourSlashesShift(ipa, idnmPwr);
        var ipaI = quotRes[0];
        var ipaF = quotRes[1];
        fourSlashesShift(ipb, idnmPwr);
        var ipbI = quotRes[0];
        var ipbF = quotRes[1];
        fourSlashesShift(ipd, idnmPwr);
        var ipdI = quotRes[0];
        var ipdF = quotRes[1];
        fourSlashesShift(ipe, idnmPwr);
        var ipeI = quotRes[0];
        var ipeF = quotRes[1];
        var fracMask = (1 << idnmPwr) - 1;
        for (var yj = 0; yj < MB_SIZE; cxyI += ipbI, cxyF += ipbF, cyyI += ipeI, cyyF += ipeF, yj++) {
            cxyI += cxyF >> idnmPwr;
            cyyI += cyyF >> idnmPwr;
            cxyF &= fracMask;
            cyyF &= fracMask;
            int cxxI = cxyI;
            int cxxF = cxyF;
            int cyxI = cyyI;
            int cyxF = cyyF;
            for (var xi = 0; xi < MB_SIZE; cxxI += ipaI, cxxF += ipaF, cyxI += ipdI, cyxF += ipdF, xi++) {
                cxxI += cxxF >> idnmPwr;
                cyxI += cyxF >> idnmPwr;
                cxxF &= fracMask;
                cyxF &= fracMask;
                var x = cxxI;
                var y = cyxI;
                if (x < refLeft || y < refTop || x > refRight || y > refBottom) {
                    if (x <= refLeft) {
                        x = refLeft;
                    }
                    if (x > refRight) {
                        x = refRight;
                    }
                    if (y <= refTop) {
                        y = refTop;
                    }
                    if (y > refBottom) {
                        y = refBottom;
                    }
                }
                var offset = (y >> accuracy) * refWidth + (x >> accuracy);
                pred[predOffset + xi + yj * MB_SIZE] = interpolatePixelValue(ref, prev + offset, refWidth,
                        x & warpMask, y & warpMask, powerWA, bias, sqrWac);
            }
        }
    }

    private void fastAffineWarpChromForGMC(int cxy0, int cyy0, int[] predU, int[] predV) {
        var accuracy = warpingAccuracy + 1;
        var refWidth = (rctRefFrameY.right - rctRefFrameY.left) >> 1;
        var refLeft = ((rctRefVOPY0.left / 2) + EXPANDUV_REFVOP) << accuracy;
        var refTop = ((rctRefVOPY0.top / 2) + EXPANDUV_REFVOP) << accuracy;
        var refRight = ((rctRefVOPY0.right / 2) - EXPANDUV_REFVOP + 15) << accuracy;
        var refBottom = ((rctRefVOPY0.bottom / 2) - EXPANDUV_REFVOP + 15) << accuracy;
        var u = refQ0.u.pixels;
        var v = refQ0.v.pixels;
        var prev = (EXPANDUV_REF_FRAME - 8) * frameWidthUV + EXPANDUV_REF_FRAME - 8;
        var powerWA = 1 << accuracy;
        var warpMask = powerWA - 1;
        var sqrWac = accuracy << 1;
        var bias = 1 << (sqrWac - 1);
        var cx = cxy0 - rctCurrVOPY.left;
        var cy = cyy0 - rctCurrVOPY.top;
        affine(1);
        var cxy = cxyStart + ((ipb * cy + ipa * cx) << 1);
        var cyy = cyyStart + ((ipe * cy + ipd * cx) << 1);
        fourSlashesShift(ipa, idnmPwr - 2);
        var ipaI = quotRes[0];
        var ipaF = quotRes[1] * 4;
        fourSlashesShift(ipb, idnmPwr - 2);
        var ipbI = quotRes[0];
        var ipbF = quotRes[1] * 4;
        fourSlashesShift(ipd, idnmPwr - 2);
        var ipdI = quotRes[0];
        var ipdF = quotRes[1] * 4;
        fourSlashesShift(ipe, idnmPwr - 2);
        var ipeI = quotRes[0];
        var ipeF = quotRes[1] * 4;
        fourSlashesShift(cxy, idnmPwr);
        var cxyI = quotRes[0];
        var cxyF = quotRes[1];
        fourSlashesShift(cyy, idnmPwr);
        var cyyI = quotRes[0];
        var cyyF = quotRes[1];
        var fracMask = (1 << idnmPwr) - 1;
        for (var yj = 0; yj < MB_SIZE / 2; cxyI += ipbI, cxyF += ipbF, cyyI += ipeI, cyyF += ipeF, yj++) {
            cxyI += cxyF >> idnmPwr;
            cyyI += cyyF >> idnmPwr;
            cxyF &= fracMask;
            cyyF &= fracMask;
            int cxxI = cxyI;
            int cxxF = cxyF;
            int cyxI = cyyI;
            int cyxF = cyyF;
            for (var xi = 0; xi < MB_SIZE / 2; cxxI += ipaI, cxxF += ipaF, cyxI += ipdI, cyxF += ipdF, xi++) {
                cxxI += cxxF >> idnmPwr;
                cyxI += cyxF >> idnmPwr;
                cxxF &= fracMask;
                cyxF &= fracMask;
                var x = cxxI;
                var y = cyxI;
                if (x < refLeft || y < refTop || x > refRight || y > refBottom) {
                    if (x <= refLeft) {
                        x = refLeft;
                    }
                    if (x > refRight) {
                        x = refRight;
                    }
                    if (y <= refTop) {
                        y = refTop;
                    }
                    if (y > refBottom) {
                        y = refBottom;
                    }
                }
                var offset = prev + (y >> accuracy) * refWidth + (x >> accuracy);
                var rx = x & warpMask;
                var ry = y & warpMask;
                predU[xi + yj * BLOCK_SIZE] = interpolatePixelValue(u, offset, refWidth, rx, ry, powerWA, bias,
                        sqrWac);
                predV[xi + yj * BLOCK_SIZE] = interpolatePixelValue(v, offset, refWidth, rx, ry, powerWA, bias,
                        sqrWac);
            }
        }
    }

    /**
     * Computes the motion vector a GMC macroblock leaves for the prediction of later vectors ({@code globalmv}).
     *
     * @param result receives the whole and half sample parts of the horizontal and vertical components
     */
    final void globalMv(int[] result, int xCurr, int yCurr, int searchRange, boolean quarterSample) {
        switch (numOfPnts) {
            case 0 -> {
                result[0] = 0;
                result[1] = 0;
                result[2] = 0;
                result[3] = 0;
            }
            case 1 -> translationalWarpMotion(result, searchRange, quarterSample);
            default -> fastAffineWarpMotion(result, xCurr, yCurr, searchRange, quarterSample);
        }
    }

    private void translationalWarpMotion(int[] result, int searchRange, boolean quarterSample) {
        var xi0 = rctCurrVOPY.left;
        var yj0 = rctCurrVOPY.top;
        int area;
        int xVec;
        int yVec;
        if (quarterSample) {
            area = searchRange / 2;
            xVec = (int) (dstQx[0] * 4.0) - xi0 * 4;
            yVec = (int) (dstQy[0] * 4.0) - yj0 * 4;
        } else {
            area = searchRange;
            xVec = (int) (dstQx[0] * 2.0) - xi0 * 2;
            yVec = (int) (dstQy[0] * 2.0) - yj0 * 2;
        }
        splitVector(result, xVec, yVec, area);
    }

    private static void splitVector(int[] result, int xVec, int yVec, int area) {
        if (xVec < -area) {
            xVec = -area;
        }
        if (xVec >= area) {
            xVec = area - 1;
        }
        if (yVec < -area) {
            yVec = -area;
        }
        if (yVec >= area) {
            yVec = area - 1;
        }
        result[0] = xVec / 2;
        result[1] = yVec / 2;
        result[2] = xVec - result[0] * 2;
        result[3] = yVec - result[1] * 2;
    }

    private void fastAffineWarpMotion(int[] result, int xCurr, int yCurr, int searchRange, boolean quarterSample) {
        var accuracy = warpingAccuracy + 1;
        int area;
        int res;
        if (quarterSample) {
            area = searchRange / 2;
            res = 64 << warpingAccuracy;
        } else {
            area = searchRange;
            res = 128 << warpingAccuracy;
        }
        affine(2);
        var ipp = rounding;
        var cx = xCurr - rctCurrVOPY.left;
        var cy = yCurr - rctCurrVOPY.top;
        var cxy = cxyStart + ipb * cy + ipa * cx;
        var cyy = cyyStart + ipe * cy + ipd * cx;
        fourSlashesShift(cxy, idnmPwr);
        var cxyI = quotRes[0];
        var cxyF = quotRes[1];
        fourSlashesShift(cyy, idnmPwr);
        var cyyI = quotRes[0];
        var cyyF = quotRes[1];
        fourSlashesShift(ipa, idnmPwr);
        var ipaI = quotRes[0];
        var ipaF = quotRes[1];
        fourSlashesShift(ipb, idnmPwr);
        var ipbI = quotRes[0];
        var ipbF = quotRes[1];
        fourSlashesShift(ipd, idnmPwr);
        var ipdI = quotRes[0];
        var ipdF = quotRes[1];
        fourSlashesShift(ipe, idnmPwr);
        var ipeI = quotRes[0];
        var ipeF = quotRes[1];
        var fracMask = (1 << idnmPwr) - 1;
        var avgX = 0;
        var avgY = 0;
        var ty = yCurr;
        for (var yj = 0; yj < MB_SIZE; cxyI += ipbI, cxyF += ipbF, cyyI += ipeI, cyyF += ipeF, yj++, ty++) {
            cxyI += cxyF >> idnmPwr;
            cyyI += cyyF >> idnmPwr;
            cxyF &= fracMask;
            cyyF &= fracMask;
            int cxxI = cxyI;
            int cxxF = cxyF;
            int cyxI = cyyI;
            int cyxF = cyyF;
            var tx = xCurr;
            for (var xi = 0; xi < MB_SIZE; cxxI += ipaI, cxxF += ipaF, cyxI += ipdI, cyxF += ipdF, xi++, tx++) {
                var crx = ((cxxI << idnmPwr) + cxxF + ipp) >> idnmPwr;
                var cry = ((cyxI << idnmPwr) + cyxF + ipp) >> idnmPwr;
                cxxI += cxxF >> idnmPwr;
                cyxI += cyxF >> idnmPwr;
                cxxF &= fracMask;
                cyxF &= fracMask;
                avgX += crx - (tx << accuracy);
                avgY += cry - (ty << accuracy);
            }
        }
        int xVec;
        int yVec;
        if (quarterSample) {
            xVec = (avgX + (avgX < 0 ? res - 1 : res)) >> (6 + accuracy);
            yVec = (avgY + (avgY < 0 ? res - 1 : res)) >> (6 + accuracy);
        } else {
            xVec = (avgX + (avgX < 0 ? res - 1 : res)) >> (7 + accuracy);
            yVec = (avgY + (avgY < 0 ? res - 1 : res)) >> (7 + accuracy);
        }
        splitVector(result, xVec, yVec, area);
    }

    /**
     * Interpolates a sample bilinearly at a sub-sample position ({@code CInterpolatePixelValue}).
     */
    final int interpolatePixelValue(int[] f, int pos, int width, int rx, int ry, int wpc, int bias, int powDenom) {
        var rxc = wpc - rx;
        var ryc = wpc - ry;
        var hstep = rx != 0 ? 1 : 0;
        var vstep = ry != 0 ? width : 0;
        return (ryc * (rxc * f[pos] + rx * f[pos + hstep]) + ry * (rxc * f[pos + vstep] + rx * f[pos + vstep
                + hstep]) + bias - vopmd.roundingControl) >> powDenom;
    }

    /**
     * Divides rounding toward minus infinity by a power of two, into {@code quotRes} ({@code FourSlashesShift}).
     */
    private void fourSlashesShift(int num, int denomPwr) {
        var quot = Math.abs(num) >> denomPwr;
        if (num < 0) {
            quot = -quot;
        }
        int res;
        if (quot << denomPwr == num) {
            res = 0;
        } else {
            if (num < 0) {
                quot -= 1;
            }
            res = num - (quot << denomPwr);
        }
        quotRes[0] = quot;
        quotRes[1] = res;
    }

    /**
     * Divides rounding toward minus infinity, into {@code result} ({@code FourSlashes}).
     */
    static void fourSlashes(int num, int denom, int[] result) {
        var quot = num / denom;
        int res;
        if (quot * denom == num) {
            res = 0;
        } else {
            if (num < 0) {
                quot -= 1;
            }
            res = num - denom * quot;
        }
        result[0] = quot;
        result[1] = res;
    }

    /**
     * Divides rounding toward minus infinity for a negative numerator as {@link #fourSlashes} does, and returns the
     * quotient.
     */
    private static int quotient(int num, int denom) {
        var quot = num / denom;
        if (quot * denom != num && num < 0) {
            quot -= 1;
        }
        return quot;
    }

    /**
     * Extrapolates a warping point to a power-of-two distance ({@code LinearExtrapolation}).
     */
    static int linearExtrapolation(int x0, int x1, int x0p, int x1p, int w, int vw) {
        var num = x0p - (x0 << 4);
        var q = quotient(num, w);
        var extrapolated = q * (w - vw);
        num = (num - w * q) * (w - vw);
        q = quotient(num, w);
        extrapolated += q;
        var resSum = num - w * q;
        num = x1p - (x1 << 4);
        q = quotient(num, w);
        var r = num - w * q;
        extrapolated += q * vw + r;
        num = r * (vw - w);
        q = quotient(num, w);
        extrapolated += q;
        resSum += num - w * q;
        q = quotient(resSum, w);
        r = resSum - w * q;
        extrapolated += q;
        if (extrapolated >= 0) {
            if (r >= (w + 1) / 2) {
                extrapolated++;
            }
        } else if (r > w / 2) {
            extrapolated++;
        }
        return extrapolated;
    }
}
