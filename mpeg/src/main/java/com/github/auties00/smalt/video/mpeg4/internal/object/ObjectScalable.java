/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/vopses.cpp: updateAllRefVOPs for spatial scalability, type/grayc.cpp and type/yuvac.cpp: the upsampling for
 * spatial scalability, sys/tps_enhcbuf.cpp and the buffers of temporal scalability of sys/decoder/vopsedec.cpp).
 * Copyright 1997-2000 Sharp Corporation, Sony Corporation, Samsung AIT, Microsoft Corporation and the contributors
 * named in the sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

import java.util.Arrays;

/**
 * The enhancement layers of scalability (7.9): the upsampling of the reference layer VOP for spatial scalability,
 * including the binary shape of object-based spatial scalability, and the buffers through which an enhancement layer
 * of temporal scalability takes the base layer VOPs as references.
 */
abstract class ObjectScalable extends ObjectHeaders {
    /**
     * A base layer VOP kept for an enhancement layer of temporal scalability ({@code CEnhcBufferDecoder}).
     */
    static final class EnhcBuffer {
        final MbMode[] modes;
        final MotionVector[] vectors;
        int numMBRef;
        int numMBXRef;
        int numMBYRef;
        int offsetForPadY;
        int offsetForPadUV;
        final Rct prevNoExpandY = new Rct();
        final Rct prevNoExpandUV = new Rct();
        boolean codedFutureRef;
        final Rct refVOPY0 = new Rct();
        final Rct refVOPUV0 = new Rct();
        final Rct refVOPY1 = new Rct();
        final Rct refVOPUV1 = new Rct();
        VopPlanes vop;
        int time;

        EnhcBuffer(int width, int height, int aux) {
            var mbx = (width + MB_SIZE - 1) / MB_SIZE;
            var mby = (height + MB_SIZE - 1) / MB_SIZE;
            modes = MbMode.array(mbx * mby, aux);
            vectors = MotionVector.array(mbx * mby * Math.max(PVOP_MV_PER_REF_PER_MB, 2 * BVOP_MV_PER_REF_PER_MB));
        }

        boolean empty() {
            return vop == null;
        }

        void dispose() {
            vop = null;
        }

        private static final int VECTORS = Math.max(PVOP_MV_PER_REF_PER_MB, 2 * BVOP_MV_PER_REF_PER_MB);

        void copyBuf(EnhcBuffer src) {
            numMBRef = src.numMBRef;
            numMBXRef = src.numMBXRef;
            numMBYRef = src.numMBYRef;
            offsetForPadY = src.offsetForPadY;
            offsetForPadUV = src.offsetForPadUV;
            prevNoExpandY.set(src.prevNoExpandY);
            prevNoExpandUV.set(src.prevNoExpandUV);
            refVOPY0.set(src.refVOPY0);
            refVOPUV0.set(src.refVOPUV0);
            refVOPY1.set(src.refVOPY1);
            refVOPUV1.set(src.refVOPUV1);
            codedFutureRef = src.codedFutureRef;
            for (var i = 0; i < numMBRef; i++) {
                modes[i].set(src.modes[i]);
                for (var k = 0; k < VECTORS; k++) {
                    vectors[i * VECTORS + k].set(src.vectors[i * VECTORS + k]);
                }
            }
            vop = new VopPlanes(src.vop, src.vop.alphaUsage, null);
            time = src.time;
        }

        void getBuf(ObjectScalable base) {
            codedFutureRef = base.codedFutureRef;
            MbMode[] srcModes;
            MotionVector[] srcVectors;
            if (base.vopmd.predType != VopMode.BVOP) {
                srcModes = base.mbmdRef;
                srcVectors = base.mvRef;
                numMBRef = base.numMBRef;
                numMBXRef = base.numMBXRef;
                numMBYRef = base.numMBYRef;
            } else {
                srcModes = base.mbmd;
                srcVectors = base.mv;
                numMBRef = base.numMB;
                numMBXRef = base.numMBX;
                numMBYRef = base.numMBY;
            }
            for (var i = 0; i < numMBRef && i < modes.length; i++) {
                modes[i].set(srcModes[i]);
                for (var k = 0; k < VECTORS; k++) {
                    var index = i * VECTORS + k;
                    if (index < srcVectors.length) {
                        vectors[index].set(srcVectors[index]);
                    }
                }
            }
            time = base.t;
            var recon = base.reconCurr();
            vop = new VopPlanes(recon, recon.alphaUsage, null);
            if (base.vopmd.predType != VopMode.BVOP) {
                offsetForPadY = base.offsetForPadY;
                offsetForPadUV = base.offsetForPadUV;
                prevNoExpandY.set(base.rctPrevNoExpandY);
                prevNoExpandUV.set(base.rctPrevNoExpandUV);
                refVOPY1.set(base.rctRefVOPY1);
                refVOPUV1.set(base.rctRefVOPUV1);
            } else {
                offsetForPadY = base.bvopOffsetForPadY;
                offsetForPadUV = base.bvopOffsetForPadUV;
                prevNoExpandY.set(base.rctBVOPPrevNoExpandY);
                prevNoExpandUV.set(base.rctBVOPPrevNoExpandUV);
                refVOPY1.set(base.rctBVOPRefVOPY1);
                refVOPUV1.set(base.rctBVOPRefVOPUV1);
            }
        }
    }

    EnhcBuffer buffP1;
    EnhcBuffer buffP2;
    EnhcBuffer buffB1;
    EnhcBuffer buffB2;
    EnhcBuffer buffE;

    final void allocateEnhancementBuffers() {
        var w = rctRefFrameY.width;
        var h = rctRefFrameY.height();
        buffP1 = new EnhcBuffer(w, h, volmd.auxCompCount);
        buffP2 = new EnhcBuffer(w, h, volmd.auxCompCount);
        buffB1 = new EnhcBuffer(w, h, volmd.auxCompCount);
        buffB2 = new EnhcBuffer(w, h, volmd.auxCompCount);
        buffE = new EnhcBuffer(w, h, volmd.auxCompCount);
    }

    /**
     * Keeps the VOP the base layer just decoded for the enhancement layer of temporal scalability
     * ({@code updateBuffVOPsBase}).
     */
    final void updateBuffVOPsBase(ObjectScalable enhancement) {
        switch (vopmd.predType) {
            case VopMode.IVOP -> {
                if (!enhancement.buffP2.empty() && enhancement.buffP2.codedFutureRef) {
                    enhancement.buffP1.copyBuf(enhancement.buffP2);
                }
                enhancement.buffP2.getBuf(this);
            }
            case VopMode.PVOP -> {
                if (enhancement.buffP2.codedFutureRef && !enhancement.buffP2.empty()) {
                    enhancement.buffP1.copyBuf(enhancement.buffP2);
                }
                enhancement.buffP2.getBuf(this);
            }
            case VopMode.BVOP -> {
                if (!enhancement.buffB2.empty() && enhancement.buffB2.codedFutureRef) {
                    enhancement.buffB1.copyBuf(enhancement.buffB2);
                }
                enhancement.buffB2.getBuf(this);
            }
            default -> throw CorruptStream.INSTANCE;
        }
    }

    private boolean shapeEnhancement(boolean forwardRef) {
        if (volmd.enhnType != 1) {
            return false;
        }
        var code = vopmd.refSelectCode;
        if (forwardRef) {
            return ((code == 1 || code == 2) && vopmd.predType == VopMode.PVOP)
                    || (code == 3 && vopmd.predType == VopMode.BVOP);
        }
        return ((code == 1 || code == 2) && vopmd.predType == VopMode.PVOP)
                || ((code == 1 || code == 3) && vopmd.predType == VopMode.BVOP);
    }

    private VopPlanes withPlainShape(VopPlanes vop) {
        var copy = new VopPlanes(volmd.alphaUsage == VopPlanes.RECTANGLE ? VopPlanes.ONE_BIT : volmd.alphaUsage,
                vop.whereY, volmd.auxCompCount);
        copy.y.pixels = vop.y.pixels.clone();
        copy.u.pixels = vop.u.pixels.clone();
        copy.v.pixels = vop.v.pixels.clone();
        var inner = new Rct(rctRefFrameY);
        inner.expand(-EXPANDY_REF_FRAME);
        var innerUV = new Rct(rctRefFrameUV);
        innerUV.expand(-EXPANDY_REF_FRAME / 2);
        Arrays.fill(copy.by.pixels, 0);
        Arrays.fill(copy.buv.pixels, 0);
        copy.by.or(new Plane(inner, OPAQUE));
        copy.buv.or(new Plane(innerUV, OPAQUE));
        return copy;
    }

    private void putBuf(EnhcBuffer buffer, boolean toQ0) {
        var vop = new VopPlanes(buffer.vop, buffer.vop.alphaUsage, null);
        if (shapeEnhancement(toQ0)) {
            vop = withPlainShape(vop);
        }
        codedFutureRef = buffer.codedFutureRef;
        numMBRef = buffer.numMBRef;
        numMBXRef = buffer.numMBXRef;
        numMBYRef = buffer.numMBYRef;
        var vectors = Math.max(PVOP_MV_PER_REF_PER_MB, 2 * BVOP_MV_PER_REF_PER_MB);
        for (var i = 0; i < numMBRef && i < mbmdRef.length; i++) {
            mbmdRef[i].set(buffer.modes[i]);
            if (toQ0 ? shapeEnhancement(true) : volmd.enhnType == 1) {
                mbmdRef[i].shpmd = MbMode.ALL_OPAQUE;
            }
            for (var k = 0; k < vectors; k++) {
                var index = i * vectors + k;
                if (index < mvRef.length) {
                    mvRef[index].set(buffer.vectors[index]);
                }
            }
        }
        if (toQ0) {
            refQ0 = vop;
            saveShapeMode();
        } else {
            refQ1 = vop;
        }
        offsetForPadY = buffer.offsetForPadY;
        offsetForPadUV = buffer.offsetForPadUV;
        rctPrevNoExpandY.set(buffer.prevNoExpandY);
        rctPrevNoExpandUV.set(buffer.prevNoExpandUV);
        if (toQ0) {
            rctRefVOPY0.set(buffer.refVOPY1);
            rctRefVOPUV0.set(buffer.refVOPUV1);
        } else {
            rctRefVOPY1.set(buffer.refVOPY1);
            rctRefVOPUV1.set(buffer.refVOPUV1);
        }
        vop.setBoundRct(buffer.refVOPY1);
        repeatPadYOrA(vop.y.pixels, offsetForPadY, vop);
        repeatPadUV(vop);
        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
            repeatPadYOrA(vop.a[0].pixels, offsetForPadY, vop);
        }
    }

    private int takeQ0(EnhcBuffer preferred, EnhcBuffer fallback) {
        var buffer = !preferred.empty() ? preferred : fallback;
        putBuf(buffer, true);
        return buffer.time;
    }

    private int takeQ1(EnhcBuffer preferred, EnhcBuffer fallback) {
        var buffer = !preferred.empty() ? preferred : fallback;
        putBuf(buffer, false);
        return buffer.time;
    }

    /**
     * Takes the references of an enhancement layer VOP of temporal scalability from the buffers
     * ({@code updateRefVOPsEnhc}).
     */
    final void updateRefVOPsEnhc() {
        switch (vopmd.predType) {
            case VopMode.IVOP -> {
            }
            case VopMode.PVOP -> {
                int tQ0;
                switch (vopmd.refSelectCode) {
                    case 0 -> {
                        if (buffE.empty()) {
                            throw CorruptStream.INSTANCE;
                        }
                        putBuf(buffE, true);
                        tQ0 = buffE.time;
                    }
                    case 1, 3 -> tQ0 = takeQ0(buffB1, buffP1);
                    default -> tQ0 = takeQ0(buffB2, buffP2);
                }
                tPastRef = tQ0;
            }
            case VopMode.BVOP -> {
                int tQ0;
                int tQ1;
                switch (vopmd.refSelectCode) {
                    case 0, 1 -> {
                        if (buffE.empty()) {
                            throw CorruptStream.INSTANCE;
                        }
                        putBuf(buffE, true);
                        tQ0 = buffE.time;
                        tQ1 = takeQ1(buffB1, buffP1);
                    }
                    case 2 -> {
                        if (buffE.empty()) {
                            throw CorruptStream.INSTANCE;
                        }
                        putBuf(buffE, true);
                        tQ0 = buffE.time;
                        tQ1 = takeQ1(buffB2, buffP2);
                    }
                    default -> {
                        tQ0 = takeQ0(buffB1, buffP1);
                        tQ1 = takeQ1(buffB2, buffP2);
                        bCount = t - tQ0;
                    }
                }
                tPastRef = tQ0;
                tFutureRef = tQ1;
            }
            default -> throw CorruptStream.INSTANCE;
        }
    }

    /**
     * Keeps the VOP an enhancement layer just decoded as its own reference ({@code updateBuffVOPsEnhc}).
     */
    final void updateBuffVOPsEnhc() {
        if (codedFutureRef && buffE != null) {
            buffE.getBuf(this);
        }
    }

    final void bufferB2flush() {
        if (!buffB2.empty()) {
            if (buffB2.codedFutureRef) {
                buffB1.copyBuf(buffB2);
            }
            buffB2.dispose();
        }
    }

    final void bufferB1flush() {
        buffB1.dispose();
    }

    final void copyBufP2ToB1() {
        if (buffP2.codedFutureRef && !buffP2.empty()) {
            buffB1.copyBuf(buffP2);
        }
    }

    /**
     * Copies the future reference to the past one, for a base layer that ends with a B-VOP
     * ({@code copyRefQ1ToQ0}).
     */
    final void copyRefQ1ToQ0() {
        refQ0 = new VopPlanes(refQ1, refQ1.alphaUsage, null);
    }

    private Rct scaledBase(VopPlanes upsampled) {
        var tmp = new Rct(rctBase);
        tmp.left = tmp.left * volmd.horSamplingFactorNShape / volmd.horSamplingFactorMShape;
        tmp.right = tmp.right * volmd.horSamplingFactorNShape / volmd.horSamplingFactorMShape;
        tmp.top = tmp.top * volmd.verSamplingFactorNShape / volmd.verSamplingFactorMShape;
        tmp.bottom = tmp.bottom * volmd.verSamplingFactorNShape / volmd.verSamplingFactorMShape;
        tmp.width = tmp.right - tmp.left;
        tmp.left -= 32;
        tmp.right += 32;
        tmp.top -= 32;
        tmp.bottom += 32;
        tmp.width += 64;
        var w = upsampled.whereY;
        if (!tmp.inside(w)) {
            if (tmp.left < w.left) {
                tmp.left = w.left;
            }
            if (tmp.top < w.top) {
                tmp.top = w.top;
            }
            if (tmp.right > w.right) {
                tmp.right = w.right;
            }
            if (tmp.bottom > w.bottom) {
                tmp.bottom = w.bottom;
            }
        }
        return tmp;
    }

    /**
     * Takes the upsampled VOP of the reference layer as the reference of an enhancement layer VOP of spatial
     * scalability ({@code updateAllRefVOPs (const CVOPU8YUVBA*)}).
     */
    final void updateAllRefVOPs(VopPlanes base) {
        var upsampled = upsample(base);
        var partialEnhancement = volmd.spatialScalability && volmd.hierarchyType == 0 && volmd.enhnType != 0
                && volmd.useRefShape == 1;
        if (predictedLike()) {
            if (volmd.alphaUsage == VopPlanes.RECTANGLE) {
                rctRefVOPY0.set(rctRefVOPY1);
            } else if (partialEnhancement) {
                if (upsampled.alphaUsage == VopPlanes.RECTANGLE) {
                    rctRefVOPY0.set(upsampled.whereY);
                } else {
                    rctRefVOPY0.set(scaledBase(upsampled));
                }
            } else if (volmd.alphaUsage == VopPlanes.ONE_BIT) {
                rctRefVOPY0.set(scaledBase(upsampled));
            }
            refQ0 = upsampled;
            refQ0.setBoundRct(rctRefVOPY0);
        } else if (vopmd.predType == VopMode.BVOP) {
            Rct tmp;
            if (volmd.alphaUsage == VopPlanes.RECTANGLE) {
                tmp = new Rct(rctRefVOPY0);
            } else if (partialEnhancement) {
                tmp = upsampled.alphaUsage == VopPlanes.RECTANGLE ? new Rct(upsampled.whereY) : scaledBase(upsampled);
            } else if (volmd.alphaUsage == VopPlanes.ONE_BIT) {
                tmp = scaledBase(upsampled);
            } else {
                tmp = new Rct(rctRefVOPY0);
            }
            rctRefVOPY0.set(rctRefVOPY1);
            rctRefVOPY1.set(tmp);
            refQ0 = refQ1;
            refQ1 = upsampled;
            refQ0.setBoundRct(rctRefVOPY0);
            refQ1.setBoundRct(rctRefVOPY1);
        }
    }

    /**
     * Upsamples a VOP of the reference layer to the enhancement layer, for the background composition of spatial
     * scalability.
     *
     * @param base the reference layer VOP
     * @return the upsampled VOP
     */
    final VopPlanes upsampleReference(VopPlanes base) {
        return upsample(base);
    }

    /**
     * Upsamples the VOP of the reference layer to the enhancement layer
     * ({@code CVOPU8YUVBA::upsampleForSpatialScalability}).
     */
    private VopPlanes upsample(VopPlanes base) {
        var expand = EXPANDY_REF_FRAME;
        var width = volmd.frmWidthSS;
        var height = volmd.frmHeightSS;
        var result = new VopPlanes(base.alphaUsage,
                new Rct(base.whereY.left, base.whereY.top, width + expand, height + expand), volmd.auxCompCount);
        if (result.by == null) {
            result.by = new Plane(result.whereY);
            result.buv = new Plane(result.whereUV);
        }
        upsampleTexture(base.y, result.y, 1, width, height);
        upsampleTexture(base.u, result.u, 2, width, height);
        upsampleTexture(base.v, result.v, 2, width, height);
        if (base.alphaUsage == VopPlanes.ONE_BIT) {
            upsampleShape(base.by, result.by, 1, width, height);
            upsampleShape(base.buv, result.buv, 2, width, height);
        } else {
            Arrays.fill(result.by.pixels, OPAQUE);
            Arrays.fill(result.buv.pixels, OPAQUE);
        }
        var bound = new Rct(result.whereY);
        bound.expand(-expand);
        result.setBoundRct(bound);
        return result;
    }

    private void upsampleTexture(Plane src, Plane dst, int type, int frameWidth, int frameHeight) {
        if (volmd.shapeOnly) {
            return;
        }
        var e = EXPANDY_REF_FRAME / type;
        var srcWidth = src.where.width;
        var srcInnerW = srcWidth - 2 * e;
        var srcInnerH = src.where.height() - 2 * e;
        var dstWidth = dst.where.width;
        var dstInnerH = dst.where.height() - 2 * e;
        var dstInnerW = dstWidth - 2 * e;
        var vM = volmd.verSamplingFactorM;
        var vN = volmd.verSamplingFactorN;
        var hM = volmd.horSamplingFactorM;
        var hN = volmd.horSamplingFactorN;
        var pict = srcWidth * e + e;
        var vert = new int[Math.max(1, srcInnerW * Math.max(1, dstInnerH))];
        for (var x = 0; x < srcInnerW; x++) {
            for (var y = 0; y < dstInnerH; y++) {
                var y1 = y * vM / vN;
                var y2 = y1 < srcInnerH - 1 ? y1 + 1 : y1;
                var phase = (16 * ((y * vM) % vN) + vN / 2) / vN;
                vert[y * srcInnerW + x] = (16 - phase) * pixel(src, pict + y1 * srcWidth + x)
                        + phase * pixel(src, pict + y2 * srcWidth + x);
            }
        }
        for (var y = 0; y < dstInnerH; y++) {
            for (var x = 0; x < dstInnerW; x++) {
                var x1 = x * hM / hN;
                var x2 = x1 < srcInnerW - 1 ? x1 + 1 : x1;
                var phase = (16 * ((x * hM) % hN) + hN / 2) / hN;
                var value = ((16 - phase) * vert(vert, y * srcInnerW + x1) + phase * vert(vert, y * srcInnerW + x2)
                        + 128) >> 8;
                dst.pixels[(x + e) + (y + e) * dstWidth] = value & 0xFF;
            }
        }
    }

    private static int pixel(Plane plane, int index) {
        return index >= 0 && index < plane.pixels.length ? plane.pixels[index] : 0;
    }

    private static int vert(int[] vert, int index) {
        return index >= 0 && index < vert.length ? vert[index] : 0;
    }

    private static int log2Floor(double value) {
        return (int) Math.floor(value + 0.000001);
    }

    /**
     * Upsamples a binary shape by sample repetition and records the rows and columns that came from the reference
     * layer ({@code upsampleSegForSpatialScalability}).
     */
    private void upsampleShape(Plane src, Plane dst, int type, int frameWidth, int frameHeight) {
        var e = EXPANDY_REF_FRAME / type;
        var hM = volmd.horSamplingFactorMShape;
        var hN = volmd.horSamplingFactorNShape;
        var vM = volmd.verSamplingFactorMShape;
        var vN = volmd.verSamplingFactorNShape;
        var dstRect = dst.where;
        var srcWidth = src.where.width;
        var srcHeight = src.where.height();
        var dstWidth = dstRect.width;
        var dstHeight = dstRect.bottom - dstRect.top;
        var hFactor = StrictMath.log((double) hN / hM) / StrictMath.log(2);
        var hFactorInt = log2Floor(hFactor);
        var vFactor = StrictMath.log((double) vN / vM) / StrictMath.log(2);
        var vFactorInt = log2Floor(vFactor);
        var buffXWidth = (dstRect.right - e) / (1 << hFactorInt);
        var srcBottom = src.where.bottom - e;
        var buffX = new int[Math.max(1, buffXWidth * Math.max(1, srcBottom))];
        var buffX2Width = dstRect.right - e;
        var buffX2 = new int[Math.max(1, buffX2Width * Math.max(1, srcBottom))];
        var buffYHeight = (dstRect.bottom - e) / (1 << vFactorInt);
        var buffY = new int[Math.max(1, buffX2Width * Math.max(1, buffYHeight))];
        var xCheck1 = new boolean[buffXWidth + 1024];
        var xCheck2 = new boolean[buffX2Width + 1024];
        var yCheck1 = new boolean[buffYHeight + 1024];
        var yCheck2 = new boolean[(dstRect.bottom - e) + 1024];
        Arrays.fill(dst.pixels, 0);
        var pict = srcWidth * e + e;
        var srcHeightNet = srcHeight - e * 2;
        var srcWidthNet = srcWidth - e * 2;
        var dstHeightNet = dstHeight - e * 2;
        var hScale = 1 << hFactorInt;
        var vScale = 1 << vFactorInt;
        if (hFactor - hFactorInt > 0.000001) {
            for (var j = 0; j < srcHeight - e; j++) {
                var dstX = 0;
                var copyPel = 0;
                var nextCopyPel = 0;
                for (var i = 0; i < srcWidth - e; i++) {
                    if (i == nextCopyPel) {
                        if (i < srcWidthNet && j < srcHeightNet) {
                            store(buffX, dstX + j * buffXWidth, pixel(src, pict + j * srcWidth + i));
                        }
                        set(xCheck1, dstX, false);
                        dstX++;
                        copyPel++;
                        nextCopyPel = (int) Math.floor((double) (hM * hScale) / (hN - hM * hScale) * copyPel);
                    }
                    if (i < srcWidthNet && j < srcHeightNet) {
                        store(buffX, dstX + j * buffXWidth, pixel(src, pict + j * srcWidth + i));
                    }
                    set(xCheck1, dstX, true);
                    dstX++;
                }
            }
            for (var j = 0; j < srcHeight - e; j++) {
                for (var i = 0; i < buffXWidth + e; i++) {
                    var dstX = hScale;
                    do {
                        if (i < buffXWidth && j < srcHeightNet) {
                            store(buffX2, -(dstX - 1) + (i + 1) * hScale - 1 + j * buffXWidth * hScale,
                                    load(buffX, j * buffXWidth + i));
                        }
                        set(xCheck2, (i + 1) * hScale - 1 - (dstX - 1), dstX != 1 ? false : get(xCheck1, i));
                        dstX--;
                    } while (dstX != 0);
                }
            }
        } else {
            for (var j = 0; j < srcHeight - e; j++) {
                for (var i = 0; i < buffXWidth + e; i++) {
                    var dstX = hScale;
                    do {
                        if (i < buffXWidth && j < srcHeightNet) {
                            store(buffX2, -(dstX - 1) + (i + 1) * hScale - 1 + j * buffXWidth * hScale,
                                    pixel(src, pict + j * srcWidth + i));
                        }
                        set(xCheck2, (i + 1) * hScale - 1 - (dstX - 1), dstX == 1);
                        dstX--;
                    } while (dstX != 0);
                }
            }
        }
        var rowWidth = buffXWidth * hScale;
        if (vFactor - vFactorInt > 0.000001) {
            var copyLine = 0;
            var dstY = 0;
            var nextCopyLine = 0;
            for (var j = 0; j < srcHeight - e; j++) {
                if (j == nextCopyLine) {
                    if (j < srcHeightNet) {
                        for (var i = 0; i < dstRect.width - e * 2; i++) {
                            store(buffY, i + dstY * rowWidth, load(buffX2, j * rowWidth + i));
                        }
                    }
                    set(yCheck1, dstY, false);
                    dstY++;
                    copyLine++;
                    nextCopyLine = (int) Math.floor((double) (vM * vScale) / (vN - vM * vScale) * copyLine);
                }
                if (j < srcHeightNet) {
                    for (var i = 0; i < rowWidth; i++) {
                        store(buffY, i + dstY * rowWidth, load(buffX2, j * rowWidth + i));
                    }
                }
                set(yCheck1, dstY, true);
                dstY++;
            }
            for (var j = 0; j < dstHeightNet / vScale + e; j++) {
                var dy = vScale;
                do {
                    if (j < dstHeightNet / vScale) {
                        for (var i = 0; i < rowWidth; i++) {
                            store(dst.pixels, (i + e) + ((j + 1) * vScale - 1 - (dy - 1) + e) * dstRect.width,
                                    load(buffY, j * rowWidth + i));
                        }
                    }
                    set(yCheck2, (j + 1) * vScale - 1 - (dy - 1), dy != 1 ? false : get(yCheck1, j));
                    dy--;
                } while (dy != 0);
            }
        } else {
            for (var j = 0; j < dstHeightNet / vScale + e; j++) {
                var dy = vScale;
                do {
                    if (j < dstHeightNet / vScale) {
                        for (var i = 0; i < rowWidth; i++) {
                            store(dst.pixels, (i + e) + ((j + 1) * vScale - 1 - (dy - 1) + e) * dstRect.width,
                                    load(buffX2, j * rowWidth + i));
                        }
                    }
                    set(yCheck2, (j + 1) * vScale - 1 - (dy - 1), dy == 1);
                    dy--;
                } while (dy != 0);
            }
        }
        dst.horSamplingCheck = xCheck2;
        dst.verSamplingCheck = yCheck2;
    }

    private static void store(int[] array, int index, int value) {
        if (index >= 0 && index < array.length) {
            array[index] = value;
        }
    }

    private static int load(int[] array, int index) {
        return index >= 0 && index < array.length ? array[index] : 0;
    }

    private static void set(boolean[] array, int index, boolean value) {
        if (index >= 0 && index < array.length) {
            array[index] = value;
        }
    }

    private static boolean get(boolean[] array, int index) {
        return index >= 0 && index < array.length && array[index];
    }
}
