/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/spt.cpp and sys/decoder/sptdec.cpp). Copyright 1996-2000 Microsoft Corporation, Sharp Corporation, Hughes
 * Electronics and the contributors named in the sources; ISO/IEC free license for products conforming to ISO/IEC
 * 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Static sprites (7.8): the sprite trajectories, the warping of the sprite into a sprite VOP (projective, and the
 * fast affine warping of 7.8.5), the sprite brightness change, and the low latency sprite pieces and updates.
 */
abstract class ObjectSprite extends ObjectErrorResilience {
    private static final Vlc VLC_WRP_PNT = new Vlc(RefTables.VLC_WRP_PNT);
    private static final int NOT_DONE = 0;
    private static final int PIECE_DONE = 1;
    private static final int UPDATE_DONE = 2;

    final Rct rctSptPieceY = new Rct();
    final Rct rctSptQ = new Rct();
    int pieceWidth;
    int pieceHeight;
    int pieceXoffset;
    int pieceYoffset;
    int tPiece;
    int oldSptXmitMode;
    boolean lightChange;
    double brightnessFactor = 1;
    private int[][] pieceStatus;
    private int[][] updateStatus;
    private MbMode[][] spriteModes;
    private MbMode[] spriteModesFlat;
    private int[][][][] spriteMemory;

    /**
     * Decodes the warping points of a sprite VOP ({@code decodeWarpPoints}).
     */
    @Override
    final void decodeWarpPoints() {
        var du = new int[4];
        var dv = new int[4];
        for (var j = 0; j < numOfPnts; j++) {
            for (var xy = 0; xy < 2; xy++) {
                var size = decode(VLC_WRP_PNT);
                int value;
                if (bits.peekBits(1) == 1) {
                    value = bits.getBits(size);
                } else {
                    var abs = ~bits.getBits(size);
                    var mask = ~(0xFFFFFFFF << size);
                    value = -(abs & mask);
                }
                bits.getBits(1);
                if (xy == 0) {
                    du[j] = value;
                } else {
                    dv[j] = value;
                }
            }
        }
        var r = rctCurrVOPY;
        double[] sx = {r.left, r.right, r.left, r.right};
        double[] sy = {r.top, r.top, r.bottom, r.bottom};
        for (var j = 0; j < numOfPnts; j++) {
            srcQx[j] = sx[j];
            srcQy[j] = sy[j];
        }
        var u = new int[4];
        var v = new int[4];
        u[0] = du[0];
        v[0] = dv[0];
        u[1] = du[1] + u[0];
        v[1] = dv[1] + v[0];
        u[2] = du[2] + u[0];
        v[2] = dv[2] + v[0];
        u[3] = du[3] + u[2] + u[1] - u[0];
        v[3] = dv[3] + v[2] + v[1] - v[0];
        for (var j = 0; j < numOfPnts; j++) {
            dstQx[j] = (u[j] + 2 * srcQx[j]) / 2.0;
            dstQy[j] = (v[j] + 2 * srcQy[j]) / 2.0;
        }
    }

    /**
     * Decodes the brightness change factor of a sprite VOP (6.3.5.4, {@code decode_brightness_change_factor} of the
     * MoMuSys reference software).
     */
    final void decodeBrightnessChangeFactor() {
        var code = bits.peekBits(4);
        int value;
        if (code <= 7) {
            bits.getBits(1);
            value = bits.getBits(5);
            if (value < 16) {
                value -= 31;
            }
            value = value < 0 ? value + 15 : value - 15;
        } else if (code <= 11) {
            bits.getBits(2);
            value = bits.getBits(6);
            if (value < 32) {
                value -= 63;
            }
            value = value < 0 ? value + 15 : value - 15;
        } else if (code <= 13) {
            bits.getBits(3);
            value = bits.getBits(7);
            if (value < 64) {
                value -= 127;
            }
            value = value < 0 ? value + 15 : value - 15;
        } else if (code == 14) {
            bits.getBits(4);
            value = bits.getBits(9) + 113;
        } else {
            bits.getBits(4);
            value = bits.getBits(10) + 625;
        }
        brightnessFactor = value / 100.0 + 1;
    }

    /**
     * Scales the luminance of a warped sprite VOP by the brightness change factor (7.8.7).
     */
    final void applyBrightnessChange(VopPlanes vop, Rct bound) {
        if (brightnessFactor == 1) {
            return;
        }
        var max = (1 << volmd.nBits) - 1;
        var y = vop.y;
        for (var row = bound.top; row < bound.bottom; row++) {
            var p = y.offset(bound.left, row);
            for (var x = bound.left; x < bound.right; x++, p++) {
                var value = (int) Math.floor(y.pixels[p] * brightnessFactor + 0.5);
                y.pixels[p] = Math.clamp(value, 0, max);
            }
        }
    }

    /**
     * Decodes a sprite VOP: its warping points, its pieces, and the warping ({@code decodeSpt}).
     *
     * @throws MediaException.Malformed if the VOP warps a sprite the stream has not decoded
     */
    final void decodeSpt() {
        if (numOfPnts > 0) {
            decodeWarpPoints();
        }
        if (lightChange) {
            decodeBrightnessChangeFactor();
        } else {
            brightnessFactor = 1;
        }
        if (sptMode != BASIC_SPRITE) {
            decodeSpritePieces();
        }
        if (numOfPnts > 0) {
            if (sptQ == null) {
                throw new MediaException.Malformed("MPEG-4 sprite VOP before its sprite");
            }
            var warp = volmd.alphaUsage != VopPlanes.RECTANGLE ? new Rct(rctCurrVOPY)
                    : new Rct(0, 0, volWidth, volHeight);
            if (numOfPnts == 2 || numOfPnts == 3) {
                fastAffineWarp(warp, warp.divide(2), warpingAccuracy, numOfPnts);
            } else {
                var persp = new Perspective(numOfPnts, srcQx, srcQy, dstQx, dstQy, warpingAccuracy);
                warpYA(persp, warp, warpingAccuracy);
                var sxUV = new double[4];
                var syUV = new double[4];
                var dxUV = new double[4];
                var dyUV = new double[4];
                for (var i = 0; i < numOfPnts; i++) {
                    sxUV[i] = (srcQx[i] - 0.5) / 2;
                    syUV[i] = (srcQy[i] - 0.5) / 2;
                    dxUV[i] = (dstQx[i] - 0.5) / 2;
                    dyUV[i] = (dstQy[i] - 0.5) / 2;
                }
                var perspUV = new Perspective(numOfPnts, sxUV, syUV, dxUV, dyUV, warpingAccuracy);
                warpUV(perspUV, warp.divide(2), warpingAccuracy);
            }
            applyBrightnessChange(currQ, warp);
        }
    }

    private static int bilinear(Plane plane, int x, int y, int accuracy, int max) {
        var uis = 1 << (accuracy + 1);
        var accuracy1 = accuracy + 1;
        var dx = (double) x / uis;
        var dy = (double) y / uis;
        var w = plane.where;
        var left = Math.clamp((int) Math.floor(dx), w.left, w.right - 1);
        var right = Math.clamp((int) Math.ceil(dx), w.left, w.right - 1);
        var top = Math.clamp((int) Math.floor(dy), w.top, w.bottom - 1);
        var bottom = Math.clamp((int) Math.ceil(dy), w.top, w.bottom - 1);
        var accuracy2 = (accuracy << 1) + 2;
        var lt = plane.pixel(left, top);
        var rt = plane.pixel(right, top);
        var lb = plane.pixel(left, bottom);
        var rb = plane.pixel(right, bottom);
        var distX = x - (left << accuracy1);
        var distY = y - (top << accuracy1);
        var value = ((uis - distY) * ((uis - distX) * lt + distX * rt) + distY * ((uis - distX) * lb + distX * rb)
                + (1 << (accuracy2 - 1))) >> accuracy2;
        return Math.clamp(value, 0, max) & (max == 255 ? 0xFF : 0xFFFF);
    }

    private static boolean includes(Rct r, int x, int y) {
        return x >= r.left && x < r.right && y >= r.top && y < r.bottom;
    }

    /**
     * Warps the luminance and the shape of the sprite into the current VOP ({@code warpYA}).
     */
    final void warpYA(Perspective persp, Rct bound, int accuracy) {
        var max = (1 << volmd.nBits) - 1;
        var curr = currQ;
        var sprite = sptQ;
        var currY = curr.y;
        var currBY = curr.by;
        var eight = sprite.alphaUsage == VopPlanes.EIGHT_BIT;
        var currA = eight ? curr.a[0] : curr.by;
        var sptY = sprite.y;
        var sptBY = sprite.by;
        var sptA = eight ? sprite.a[0] : sprite.by;
        var rctSptY = sprite.whereY;
        Arrays.fill(currY.pixels, 0);
        if (currBY != null) {
            Arrays.fill(currBY.pixels, 0);
        }
        if (currA != null) {
            Arrays.fill(currA.pixels, 0);
        }
        var width = curr.whereY.width;
        var accuracy1 = accuracy + 1;
        var slice = currY.offset(bound.left, bound.top);
        for (var topMB = bound.top; topMB < bound.bottom; topMB += MB_SIZE) {
            var block = slice;
            for (var leftMB = bound.left; leftMB < bound.right; leftMB += MB_SIZE) {
                var offsetLine = width - Math.min(MB_SIZE, bound.right - leftMB);
                var p = block;
                for (var y = topMB; y < Math.min(topMB + MB_SIZE, bound.bottom); y++) {
                    for (var x = leftMB; x < Math.min(leftMB + MB_SIZE, bound.right); x++) {
                        if (!persp.apply(x, y)) {
                            continue;
                        }
                        var sx = persp.resultX;
                        var sy = persp.resultY;
                        var dx = (double) sx / (1 << accuracy1);
                        var dy = (double) sy / (1 << accuracy1);
                        var fx = (int) Math.floor(dx);
                        var fy = (int) Math.floor(dy);
                        var cx = (int) Math.ceil(dx);
                        var cy = (int) Math.ceil(dy);
                        if (includes(rctSptY, fx, fy) && includes(rctSptY, fx, cy) && includes(rctSptY, cx, fy)
                                && includes(rctSptY, cx, cy)) {
                            var warpedBY = sptBY == null ? 255 : bilinear(sptBY, sx, sy, accuracy, 255);
                            if (warpedBY >= 128) {
                                if (currBY != null) {
                                    currBY.pixels[p] = OPAQUE;
                                }
                                currY.pixels[p] = bilinear(sptY, sx, sy, accuracy, max);
                                if (eight) {
                                    currA.pixels[p] = bilinear(sptA, sx, sy, accuracy, 255);
                                }
                            }
                        }
                        p++;
                    }
                    p += offsetLine;
                }
                block += MB_SIZE;
            }
            slice += width * MB_SIZE;
        }
    }

    /**
     * Warps the chrominance of the sprite into the current VOP where its shape is opaque ({@code warpUV}).
     */
    final void warpUV(Perspective persp, Rct bound, int accuracy) {
        var max = (1 << volmd.nBits) - 1;
        var curr = currQ;
        var sprite = sptQ;
        var rctSptUV = sprite.whereUV;
        var widthUV = curr.whereUV.width;
        var widthY = curr.whereY.width;
        var gray = volmd.nBits > 8 ? 1 << (volmd.nBits - 1) : 128;
        Arrays.fill(curr.u.pixels, gray);
        Arrays.fill(curr.v.pixels, gray);
        var by = curr.by;
        var accuracy1 = accuracy + 1;
        var sliceUV = curr.u.offset(bound.left, bound.top);
        var sliceBY = curr.y.offset(2 * bound.left, 2 * bound.top);
        for (var topMB = bound.top; topMB < bound.bottom; topMB += BLOCK_SIZE) {
            var blockUV = sliceUV;
            var blockBY = sliceBY;
            for (var leftMB = bound.left; leftMB < bound.right; leftMB += BLOCK_SIZE) {
                var pUV = blockUV;
                var pBY = blockBY;
                var offsetLineUV = widthUV - Math.min(BLOCK_SIZE, bound.right - leftMB);
                var offsetLineBY = 2 * (widthY - Math.min(BLOCK_SIZE, bound.right - leftMB));
                for (var y = topMB; y < Math.min(topMB + BLOCK_SIZE, bound.bottom); y++) {
                    for (var x = leftMB; x < Math.min(leftMB + BLOCK_SIZE, bound.right); x++) {
                        if (!persp.apply(x, y)) {
                            continue;
                        }
                        var sx = persp.resultX;
                        var sy = persp.resultY;
                        var dx = (double) sx / (1 << accuracy1);
                        var dy = (double) sy / (1 << accuracy1);
                        var fx = (int) Math.floor(dx);
                        var fy = (int) Math.floor(dy);
                        var cx = (int) Math.ceil(dx);
                        var cy = (int) Math.ceil(dy);
                        if (includes(rctSptUV, fx, fy) && includes(rctSptUV, fx, cy) && includes(rctSptUV, cx, fy)
                                && includes(rctSptUV, cx, cy)) {
                            var opaque = by == null || (by.pixels[pBY] | by.pixels[pBY + 1] | by.pixels[pBY + widthY]
                                    | by.pixels[pBY + widthY + 1]) != 0;
                            if (opaque) {
                                curr.u.pixels[pUV] = bilinear(sprite.u, sx, sy, accuracy, max);
                                curr.v.pixels[pUV] = bilinear(sprite.v, sx, sy, accuracy, max);
                            }
                        }
                        pBY += 2;
                        pUV++;
                    }
                    pBY += offsetLineBY;
                    pUV += offsetLineUV;
                }
                blockBY += MB_SIZE;
                blockUV += BLOCK_SIZE;
            }
            sliceBY += widthY * MB_SIZE;
            sliceUV += widthUV * BLOCK_SIZE;
        }
    }

    /**
     * Warps the sprite with two or three points by the fast affine warping of 7.8.5 ({@code FastAffineWarp}).
     */
    final void fastAffineWarp(Rct bound, Rct boundUV, int accuracy, int points) {
        var curr = currQ;
        var sprite = sptQ;
        var eight = sprite.alphaUsage == VopPlanes.EIGHT_BIT;
        var currY = curr.y.pixels;
        var currBY = curr.by != null ? curr.by.pixels : null;
        var currA = eight ? curr.a[0].pixels : currBY;
        Arrays.fill(currY, 0);
        if (currBY != null) {
            Arrays.fill(currBY, 0);
        }
        if (currA != null) {
            Arrays.fill(currA, 0);
        }
        var offset = curr.whereY.width - bound.width;
        var pY = curr.y.offset(bound.left, bound.top);
        var spriteLeftEdge = sprite.whereY.left;
        var spriteTopEdge = sprite.whereY.top;
        var sptY = sprite.y.pixels;
        var sptBY = sprite.by != null ? sprite.by.pixels : null;
        var sptA = eight ? sprite.a[0].pixels : sptBY;
        var sptBase = sprite.y.offset(spriteLeftEdge, spriteTopEdge);
        var gray = volmd.nBits > 8 ? 1 << (volmd.nBits - 1) : 128;
        Arrays.fill(curr.u.pixels, gray);
        Arrays.fill(curr.v.pixels, gray);
        var offsetUV = curr.whereUV.width - boundUV.width;
        var offsetBY = 2 * (curr.whereY.width - boundUV.width);
        var pU = curr.u.offset(boundUV.left, boundUV.top);
        var sptBaseUV = sprite.u.offset(spriteLeftEdge / 2, spriteTopEdge / 2);
        var accuracy1 = accuracy + 1;
        var scale = 1 << accuracy1;
        var width = bound.right - bound.left;
        var height = bound.bottom - bound.top;
        var rPwr = 3 - accuracy;
        var r = 1 << rPwr;
        var x0p = (int) (dstQx[0] * scale * r);
        var y0p = (int) (dstQy[0] * scale * r);
        var x1p = (int) (dstQx[1] * scale * r);
        var y1p = (int) (dstQy[1] * scale * r);
        var w = width;
        var vw = 1;
        var vwPwr = 0;
        while (vw < w) {
            vw <<= 1;
            vwPwr++;
        }
        var ex1p = linearExtrapolation(0, w, x0p, x1p, w, vw) + (vw << 4);
        var ey1p = linearExtrapolation(0, 0, y0p, y1p, w, vw);
        var ex2p = 0;
        var ey2p = 0;
        var vh = 1;
        var vhPwr = 0;
        var vwh = 0;
        var vwhPwr = 0;
        if (points == 3) {
            var x2p = (int) (dstQx[2] * scale * r);
            var y2p = (int) (dstQy[2] * scale * r);
            var h = height;
            while (vh < h) {
                vh <<= 1;
                vhPwr++;
            }
            vwh = vw * vh;
            vwhPwr = vwPwr + vhPwr;
            ex2p = linearExtrapolation(0, 0, x0p, x2p, h, vh);
            ey2p = linearExtrapolation(0, h, y0p, y2p, h, vh) + (vh << 4);
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
            ex2p -= spriteLeftEdge * 16;
            ey2p -= spriteTopEdge * 16;
        }
        x0p -= spriteLeftEdge * 16;
        y0p -= spriteTopEdge * 16;
        ex1p -= spriteLeftEdge * 16;
        ey1p -= spriteTopEdge * 16;
        int a;
        int b;
        int c;
        int d;
        int e;
        int f;
        int dnmPwr;
        int cxy;
        int cyy;
        if (points == 2) {
            a = ex1p - x0p;
            b = y0p - ey1p;
            c = x0p * vw;
            d = -b;
            e = a;
            f = y0p * vw;
            dnmPwr = rPwr + vwPwr;
            cxy = c + r * vw / 2;
            cyy = f + r * vw / 2;
        } else {
            a = (ex1p - x0p) * vh;
            b = (ex2p - x0p) * vw;
            c = x0p * vwh;
            d = (ey1p - y0p) * vh;
            e = (ey2p - y0p) * vw;
            f = y0p * vwh;
            dnmPwr = rPwr + vwhPwr;
            cxy = c + r * vwh / 2;
            cyy = f + r * vwh / 2;
        }
        var spriteWidth = sprite.whereY.width;
        var spriteHeight = sprite.whereY.bottom - sprite.whereY.top;
        var q = new int[2];
        fourSlashes(cxy, 1 << dnmPwr, q);
        var cxyI = q[0];
        var cxyF = q[1];
        fourSlashes(cyy, 1 << dnmPwr, q);
        var cyyI = q[0];
        var cyyF = q[1];
        fourSlashes(a, 1 << dnmPwr, q);
        var aI = q[0];
        var aF = q[1];
        fourSlashes(b, 1 << dnmPwr, q);
        var bI = q[0];
        var bF = q[1];
        fourSlashes(d, 1 << dnmPwr, q);
        var dI = q[0];
        var dF = q[1];
        fourSlashes(e, 1 << dnmPwr, q);
        var eI = q[0];
        var eF = q[1];
        var fracmask = (1 << dnmPwr) - 1;
        var accuracy2 = accuracy1 << 1;
        var warpmask = scale - 1;
        var bias = 1 << (accuracy2 - 1);
        var pBY = pY;
        var pA = pY;
        for (var y = 0; y < height; cxyI += bI, cxyF += bF, cyyI += eI, cyyF += eF, y++) {
            cxyI += cxyF >> dnmPwr;
            cyyI += cyyF >> dnmPwr;
            cxyF &= fracmask;
            cyyF &= fracmask;
            var cxxI = cxyI;
            var cxxF = cxyF;
            var cyxI = cyyI;
            var cyxF = cyyF;
            for (var x = 0; x < width; cxxI += aI, cxxF += aF, cyxI += dI, cyxF += dF, x++) {
                cxxI += cxxF >> dnmPwr;
                cyxI += cyxF >> dnmPwr;
                cxxF &= fracmask;
                cyxF &= fracmask;
                if (cxxI >= EXPANDY_REFVOP && cyxI >= EXPANDY_REFVOP
                        && cxxI <= ((spriteWidth - 1 - EXPANDY_REFVOP) << accuracy1)
                        && cyxI <= ((spriteHeight - 1 - EXPANDY_REFVOP) << accuracy1)) {
                    var addr = sptBase + (cyxI >> accuracy1) * spriteWidth + (cxxI >> accuracy1);
                    var rx = cxxI & warpmask;
                    var ry = cyxI & warpmask;
                    var warpedBY = sptBY == null ? 255
                            : interpolatePixelValue(sptBY, addr, spriteWidth, rx, ry, scale, bias, accuracy2);
                    if (warpedBY >= 128) {
                        if (currBY != null) {
                            currBY[pBY] = OPAQUE;
                        }
                        currY[pY] = interpolatePixelValue(sptY, addr, spriteWidth, rx, ry, scale, bias, accuracy2);
                        if (eight) {
                            currA[pA] = interpolatePixelValue(sptA, addr, spriteWidth, rx, ry, scale, bias,
                                    accuracy2);
                        }
                    }
                }
                pY++;
                pBY++;
                pA++;
            }
            pY += offset;
            pBY += offset;
            pA += offset;
        }
        pBY = curr.y.offset(bound.left, bound.top);
        var pBYNext = pBY + curr.whereY.width;
        width /= 2;
        height /= 2;
        spriteWidth /= 2;
        spriteHeight /= 2;
        if (points == 2) {
            c = 2 * x0p * vw - 16 * vw;
            f = 2 * y0p * vw - 16 * vw;
            cxy = a + b + c + 2 * r * vw;
            cyy = d + e + f + 2 * r * vw;
        } else {
            c = 2 * x0p * vwh - 16 * vwh;
            f = 2 * y0p * vwh - 16 * vwh;
            cxy = a + b + c + 2 * vwh * r;
            cyy = d + e + f + 2 * vwh * r;
        }
        aF *= 4;
        bF *= 4;
        dF *= 4;
        eF *= 4;
        dnmPwr += 2;
        fourSlashes(cxy, 1 << dnmPwr, q);
        cxyI = q[0];
        cxyF = q[1];
        fourSlashes(cyy, 1 << dnmPwr, q);
        cyyI = q[0];
        cyyF = q[1];
        fracmask = (1 << dnmPwr) - 1;
        var sptU = sprite.u.pixels;
        var sptV = sprite.v.pixels;
        for (var y = 0; y < height; cxyI += bI, cxyF += bF, cyyI += eI, cyyF += eF, y++) {
            cxyI += cxyF >> dnmPwr;
            cyyI += cyyF >> dnmPwr;
            cxyF &= fracmask;
            cyyF &= fracmask;
            var cxxI = cxyI;
            var cxxF = cxyF;
            var cyxI = cyyI;
            var cyxF = cyyF;
            for (var x = 0; x < width; cxxI += aI, cxxF += aF, cyxI += dI, cyxF += dF, x++) {
                cxxI += cxxF >> dnmPwr;
                cyxI += cyxF >> dnmPwr;
                cxxF &= fracmask;
                cyxF &= fracmask;
                if (cxxI >= EXPANDUV_REFVOP && cyxI >= EXPANDUV_REFVOP
                        && cxxI <= ((spriteWidth - 1 - EXPANDUV_REFVOP) << accuracy1)
                        && cyxI <= ((spriteHeight - 1 - EXPANDUV_REFVOP) << accuracy1)) {
                    var addr = sptBaseUV + (cyxI >> accuracy1) * spriteWidth + (cxxI >> accuracy1);
                    var rx = cxxI & warpmask;
                    var ry = cyxI & warpmask;
                    var opaque = currBY == null || (currBY[pBY] | currBY[pBY + 1] | currBY[pBYNext]
                            | currBY[pBYNext + 1]) != 0;
                    if (opaque) {
                        curr.u.pixels[pU] = interpolatePixelValue(sptU, addr, spriteWidth, rx, ry, scale, bias,
                                accuracy2);
                        curr.v.pixels[pU] = interpolatePixelValue(sptV, addr, spriteWidth, rx, ry, scale, bias,
                                accuracy2);
                    }
                }
                pBY += 2;
                pBYNext += 2;
                pU++;
            }
            pBY += offsetBY;
            pBYNext += offsetBY;
            pU += offsetUV;
        }
    }

    /**
     * Makes the decoded sprite the warping source and resizes the current VOP to the display
     * ({@code swapRefQ1toSpt}, {@code changeSizeofCurrQ}).
     */
    final void swapRefQ1toSpt() {
        sptQ = refQ1;
        sptQ.shift(rctSpt.left, rctSpt.top);
        if (sptQ.alphaUsage == VopPlanes.EIGHT_BIT) {
            sptQ.by.shift(rctSpt.left, rctSpt.top);
        }
        refQ1 = new VopPlanes(volmd.alphaUsage, rctRefFrameY, volmd.auxCompCount);
        var rect = new Rct(displayWindow);
        rect.expand(EXPANDY_REF_FRAME);
        currQ = new VopPlanes(sptQ.alphaUsage, rect, volmd.auxCompCount);
    }

    /**
     * Prepares the low latency sprite: its macroblock status and memory ({@code decodeInitSprite} for pieces).
     */
    final void initSpritePieces() {
        var sptWidth = (rctSpt.width + MB_SIZE - 1) / MB_SIZE * MB_SIZE;
        var sptHeight = (rctSpt.height() + MB_SIZE - 1) / MB_SIZE * MB_SIZE;
        rctSptQ.set(0, 0, sptWidth, sptHeight);
        var mbx = sptWidth / MB_SIZE;
        var mby = sptHeight / MB_SIZE;
        pieceStatus = new int[mby][mbx];
        updateStatus = new int[mby][mbx];
        spriteModes = new MbMode[mby][];
        for (var y = 0; y < mby; y++) {
            spriteModes[y] = MbMode.array(mbx, volmd.auxCompCount);
        }
        spriteModesFlat = MbMode.array(mbx * mby, volmd.auxCompCount);
        var blocks = volmd.alphaUsage == VopPlanes.EIGHT_BIT ? 10 : 6;
        spriteMemory = new int[mby][mbx][blocks][(BLOCK_SIZE << 1) - 1];
        sptQ = new VopPlanes(volmd.alphaUsage, refQ1.whereY, volmd.auxCompCount);
        sptQ.shift(rctSpt.left, rctSpt.top);
        bits.flush();
        tPiece = 0;
        rctCurrVOPY.set(rctSptQ);
        rctCurrVOPUV.set(rctCurrVOPY.downSampleBy2());
        if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
            numMBRef = mbx * mby;
            numMBXRef = mbx;
            numMBYRef = mby;
            offsetForPadY = rctRefFrameY.offset(rctCurrVOPY.left, rctCurrVOPY.top);
            offsetForPadUV = rctRefFrameUV.offset(rctCurrVOPUV.left, rctCurrVOPUV.top);
            rctRefVOPY1.set(rctCurrVOPY);
            rctRefVOPY1.expand(EXPANDY_REFVOP);
            rctRefVOPUV1.set(rctCurrVOPUV);
            rctRefVOPUV1.expand(EXPANDUV_REFVOP);
            refQ1.setBoundRct(rctRefVOPY1);
        }
        oldSptXmitMode = VopMode.PIECE;
        vopmd.spriteXmitMode = VopMode.PAUSE;
    }

    abstract void decodeVopForPiece();

    /**
     * Decodes the sprite pieces and updates that a sprite VOP carries ({@code decodeSpritePieces}).
     */
    final void decodeSpritePieces() {
        if (vopmd.spriteXmitMode == VopMode.STOP) {
            return;
        }
        vopmd.spriteXmitMode = oldSptXmitMode;
        var cached = new Rct(rctCurrVOPY);
        do {
            decodeOneSpritePiece();
        } while (vopmd.spriteXmitMode != VopMode.STOP && vopmd.spriteXmitMode != VopMode.PAUSE);
        rctCurrVOPY.set(cached);
        if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
            padSprite();
        }
        vopmd.predType = VopMode.SPRITE;
        bits.flush(8);
    }

    private void decodeOneSpritePiece() {
        decodeVOSHead();
        if (vopmd.spriteXmitMode == VopMode.STOP || vopmd.spriteXmitMode == VopMode.PAUSE) {
            return;
        }
        vopmd.predType = vopmd.spriteXmitMode == VopMode.PIECE ? VopMode.IVOP : VopMode.PVOP;
        pieceGet(refQ1, sptQ, rctSptPieceY);
        rctCurrVOPY.set(0, 0, rctSptPieceY.width, rctSptPieceY.height());
        rctCurrVOPUV.set(rctCurrVOPY.downSampleBy2());
        setRefStartingPointers();
        computeVOPMembers();
        decodeVopForPiece();
        piecePut(refQ1, sptQ, rctSptPieceY);
    }

    private void decodeVOSHead() {
        oldSptXmitMode = vopmd.spriteXmitMode;
        vopmd.spriteXmitMode = bits.getBits(2);
        if (vopmd.spriteXmitMode != VopMode.STOP && vopmd.spriteXmitMode != VopMode.PAUSE) {
            var step = bits.getBits(5);
            if (vopmd.spriteXmitMode == VopMode.UPDATE) {
                vopmd.intStep = step;
            } else {
                vopmd.intStepI = step;
            }
            pieceWidth = bits.getBits(9);
            pieceHeight = bits.getBits(9);
            bits.getBits(1);
            pieceXoffset = bits.getBits(9);
            pieceYoffset = bits.getBits(9);
            var left = pieceXoffset * MB_SIZE + rctSpt.left;
            var top = pieceYoffset * MB_SIZE + rctSpt.top;
            rctSptPieceY.set(left, top, left + pieceWidth * MB_SIZE, top + pieceHeight * MB_SIZE);
        } else {
            rctSptPieceY.invalidate();
        }
    }

    private void pieceGet(VopPlanes dst, VopPlanes src, Rct piece) {
        var pieceUV = piece.downSampleBy2();
        planeGet(dst.y, src.y, piece);
        planeGet(dst.u, src.u, pieceUV);
        planeGet(dst.v, src.v, pieceUV);
        if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
            planeGet(dst.by, src.by, piece);
            planeGet(dst.buv, src.buv, pieceUV);
            if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
                planeGet(dst.a[0], src.a[0], piece);
            }
        }
    }

    private static void planeGet(Plane dst, Plane src, Rct piece) {
        var d = dst.offset(0, 0);
        var s = src.offset(piece.left, piece.top);
        for (var y = piece.top; y < piece.bottom; y++) {
            System.arraycopy(src.pixels, s, dst.pixels, d, piece.width);
            d += src.where.width;
            s += dst.where.width;
        }
    }

    private void piecePut(VopPlanes src, VopPlanes dst, Rct piece) {
        var pieceUV = piece.downSampleBy2();
        planePut(src.y, dst.y, piece);
        planePut(src.u, dst.u, pieceUV);
        planePut(src.v, dst.v, pieceUV);
        if (volmd.alphaUsage != VopPlanes.RECTANGLE) {
            planePut(src.by, dst.by, piece);
            planePut(src.buv, dst.buv, pieceUV);
            if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
                planePut(src.a[0], dst.a[0], piece);
            }
        }
    }

    private static void planePut(Plane src, Plane dst, Rct piece) {
        var d = dst.offset(piece.left, piece.top);
        var s = src.offset(0, 0);
        for (var y = piece.top; y < piece.bottom; y++) {
            System.arraycopy(src.pixels, s, dst.pixels, d, piece.width);
            d += dst.where.width;
            s += src.where.width;
        }
    }

    @Override
    final boolean spritePieceNotHole(int mbxOffset, int mbyOffset, int mbIndex) {
        if (tPiece < 0) {
            return true;
        }
        var mbx = mbxOffset + pieceXoffset;
        var mby = mbyOffset + pieceYoffset;
        var mbx1 = mbx - 1;
        var numSptMBX = (rctSpt.width + MB_SIZE - 1) / MB_SIZE;
        if (mbxOffset > 0) {
            var left = mbmd[mbIndex - 1];
            if (pieceStatus[mby][mbx1] == NOT_DONE) {
                pieceStatus[mby][mbx1] = PIECE_DONE;
                spriteModes[mby][mbx1].set(left);
                spriteModesFlat[mbx1 + numSptMBX * mby].set(left);
                System.arraycopy(left.predDir, 0, spriteModes[mby][mbx1].predDir, 0,
                        Math.min(10, left.predDir.length));
            } else {
                left.set(spriteModes[mby][mbx1]);
                System.arraycopy(spriteModes[mby][mbx1].predDir, 0, left.predDir, 0,
                        Math.min(10, left.predDir.length));
            }
        }
        if (mbx < rctSptQ.width / MB_SIZE) {
            return pieceStatus[mby][mbx] == NOT_DONE;
        }
        return false;
    }

    @Override
    final boolean spriteUpdateNotHole(int mbxOffset, int mbyOffset, int mbIndex) {
        var mbx = mbxOffset + pieceXoffset;
        var mby = mbyOffset + pieceYoffset;
        var mbx1 = mbx - 1;
        if (mbxOffset > 0 && updateStatus[mby][mbx1] == NOT_DONE) {
            updateStatus[mby][mbx1] = UPDATE_DONE;
        }
        mbmd[mbIndex].set(spriteModes[mby][mbx]);
        return updateStatus[mby][mbx] == NOT_DONE;
    }

    @Override
    final void saveMBmCurrRow(int mbyOffset) {
        var mby = mbyOffset + pieceYoffset;
        var blocks = volmd.alphaUsage == VopPlanes.EIGHT_BIT ? 10 : 6;
        for (var mb = 0; mb < numMBX; mb++) {
            var mbx = mb + pieceXoffset;
            for (var blk = 0; blk < blocks; blk++) {
                System.arraycopy(mbmCurr[mb][blk], 0, spriteMemory[mby][mbx][blk], 0, (BLOCK_SIZE << 1) - 1);
            }
        }
    }

    @Override
    final void restoreMBmCurrRow(int mbyOffset) {
        var mby = mbyOffset + pieceYoffset;
        var blocks = volmd.alphaUsage == VopPlanes.EIGHT_BIT ? 10 : 6;
        for (var mb = 0; mb < numMBX; mb++) {
            var mbx = mb + pieceXoffset;
            for (var blk = 0; blk < blocks; blk++) {
                System.arraycopy(spriteMemory[mby][mbx][blk], 0, mbmCurr[mb][blk], 0, (BLOCK_SIZE << 1) - 1);
            }
        }
    }

    @Override
    final void copyCurrQToPred(int offY, int offUV) {
        var y = refQ1.y.pixels;
        var u = refQ1.u.pixels;
        var v = refQ1.v.pixels;
        var ic = 0;
        for (var iy = 0; iy < MB_SIZE; iy++) {
            System.arraycopy(y, offY + iy * frameWidthY, predMBY, ic, MB_SIZE);
            ic += MB_SIZE;
        }
        ic = 0;
        for (var iy = 0; iy < BLOCK_SIZE; iy++) {
            System.arraycopy(u, offUV + iy * frameWidthUV, predMBU, ic, BLOCK_SIZE);
            System.arraycopy(v, offUV + iy * frameWidthUV, predMBV, ic, BLOCK_SIZE);
            ic += BLOCK_SIZE;
        }
    }

    /**
     * Pads the decoded pieces of the sprite before it is warped ({@code padSprite}).
     */
    final void padSprite() {
        numMBX = rctSptQ.width / MB_SIZE;
        numMBY = rctSptQ.height() / MB_SIZE;
        var sprite = sptQ;
        var mbIndex = 0;
        var savedModes = mbmd;
        mbmd = spriteModesFlat;
        var offsets = volmd.alphaUsage == VopPlanes.EIGHT_BIT ? new int[1] : null;
        for (var mby = 0; mby < numMBY; mby++) {
            var offY = startInRefToCurrRctY + mby * frameWidthYxMBSize;
            var offUV = startInRefToCurrRctUV + mby * frameWidthUVxBlkSize;
            for (var mbx = 0; mbx < numMBX; mbx++, mbIndex++, offY += MB_SIZE, offUV += BLOCK_SIZE) {
                if (offsets != null) {
                    offsets[0] = offY;
                }
                var mode = mbmd[mbIndex];
                if (volmd.shapeOnly || pieceStatus[mby][mbx] != PIECE_DONE) {
                    continue;
                }
                mode.padded = false;
                copyRefShapeToMb(currMBBY, sprite.by.pixels, offY);
                downSampleBY(currMBBY, currMBBUV, null);
                if (mode.transpStatus[0] != MbMode.ALL) {
                    if (mode.transpStatus[0] == MbMode.PARTIAL) {
                        mcPadCurrMB(sprite, offY, offUV, offsets);
                    }
                    padNeighborTranspMBs(mbx, mby, mbIndex, sprite, offY, offUV, offsets);
                } else {
                    padTransparentSpriteMB(mode, mbIndex, mbx, mby, sprite, offY, offUV, offsets);
                }
            }
        }
        mbmd = savedModes;
        rctPrevNoExpandY.set(rctSptQ);
        rctPrevNoExpandUV.set(rctPrevNoExpandY.downSampleBy2());
        repeatPadYOrA(sprite.y.pixels, offsetForPadY, sprite);
        repeatPadUV(sprite);
        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
            repeatPadYOrA(sprite.a[0].pixels, offsetForPadY, sprite);
        }
    }

    private void padTransparentSpriteMB(MbMode mode, int mbIndex, int mbx, int mby, VopPlanes sprite, int offY,
                                        int offUV, int[] offsets) {
        if (mbx > 0 && mbmd[mbIndex - 1].transpStatus[0] != MbMode.ALL && pieceStatus[mby][mbx - 1] == PIECE_DONE) {
            mcPadCurrMBFromLeft(sprite, offY, offUV, offsets);
            mode.padded = true;
        }
        if (mby > 0) {
            var top = mbmd[mbIndex - numMBX];
            if (top.transpStatus[0] != MbMode.ALL && pieceStatus[mby - 1][mbx] == PIECE_DONE) {
                if (!mode.padded) {
                    mcPadCurrMBFromTop(sprite, offY, offUV, offsets);
                    mode.padded = true;
                }
            } else if (!top.padded) {
                mcSetTopMBGray(sprite, offY, offUV, offsets);
            }
        }
        if (mby == numMBY - 1) {
            if (mbx > 0 && mbmd[mbIndex - 1].transpStatus[0] == MbMode.ALL && !mbmd[mbIndex - 1].padded) {
                mcSetLeftMBGray(sprite, offY, offUV, offsets);
            }
            if (mbx == numMBX - 1 && !mode.padded) {
                mcSetCurrMBGray(sprite, offY, offUV, offsets);
            }
        }
    }

    final boolean pieceDone(int mby, int mbx) {
        return pieceStatus[mby][mbx] == PIECE_DONE;
    }
}
