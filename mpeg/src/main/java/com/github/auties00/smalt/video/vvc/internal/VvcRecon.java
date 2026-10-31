/* Ports VVdeC 3.3.0 (source/Lib/DecoderLib/DecCu.cpp: coding unit reconstruction). Copyright (c) 2018-2026,
 * Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD License.
 * Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Reconstructs the coding units of a picture as the slice decoder parses them (ITU-T H.266 8.4, 8.5, 8.6 and 8.7):
 * intra, inter and IBC prediction, residual decoding and the reconstruction with luma mapping and chroma residual
 * scaling, and records what the deblocking filter needs.
 *
 * <p>Instances are not thread-safe.
 */
final class VvcRecon {
    /**
     * The per picture maps of the picture being reconstructed.
     */
    private final VvcFrame f;
    /**
     * The intra prediction.
     */
    private final VvcIntra intra;
    /**
     * The scaling and inverse transforms.
     */
    private final VvcTransform transform = new VvcTransform();
    /**
     * The parameters of the transform block being reconstructed.
     */
    private final VvcTransform.Block block = new VvcTransform.Block();
    /**
     * The inter prediction.
     */
    final VvcInter inter;
    /**
     * The motion derivation, which the inter prediction of the coding units reads.
     */
    private VvcMotion motion;

    /**
     * The sequence parameter set of the picture.
     */
    private VvcSps sps;
    /**
     * The picture parameter set of the picture.
     */
    private VvcPps pps;
    /**
     * The header of the slice being reconstructed.
     */
    private VvcSliceHeader sh;
    /**
     * The adaptive loop filter sets, by identifier.
     */
    private VvcAps[] alfAps;
    /**
     * The luma mapping with chroma scaling sets, by identifier.
     */
    private VvcAps[] lmcsAps;
    /**
     * The scaling list sets, by identifier.
     */
    private VvcAps[] scalingAps;
    /**
     * The scaling list of the slice, or {@code null} for flat scaling.
     */
    private VvcScalingList scalingList;
    /**
     * The luma mapping of the slice.
     */
    final VvcLmcs lmcs = new VvcLmcs();
    /**
     * Whether the slice uses luma mapping.
     */
    private boolean lmcsUsed;
    /**
     * Whether the slice scales its chroma residuals ({@code ph_chroma_residual_scale_flag}).
     */
    private boolean chromaScaling;
    /**
     * The bit depth of the samples.
     */
    private int bitDepth;
    /**
     * The largest sample value.
     */
    private int maxVal;
    /**
     * The horizontal chroma subsampling shift.
     */
    private int sx;
    /**
     * The vertical chroma subsampling shift.
     */
    private int sy;

    /**
     * The prediction of the block being reconstructed.
     */
    private final int[] pred = new int[128 * 128];
    /**
     * The residual of the block being reconstructed, the Cb residual of a chroma block.
     */
    private final int[] res = new int[128 * 128];
    /**
     * The Cr residual of a chroma block.
     */
    private final int[] res2 = new int[64 * 64];

    /**
     * Constructs the reconstruction of a picture size.
     *
     * @param frame the per picture maps
     */
    VvcRecon(VvcFrame frame) {
        this.f = frame;
        this.intra = new VvcIntra(frame);
        this.inter = new VvcInter(frame);
    }

    /**
     * Sets the motion derivation the inter prediction reads.
     *
     * @param motion the motion derivation
     */
    void setMotion(VvcMotion motion) {
        this.motion = motion;
    }

    /**
     * Sets the parameter set tables the slices of the picture refer to.
     *
     * @param alf the adaptive loop filter sets
     * @param lmcsTable the luma mapping with chroma scaling sets
     * @param scaling the scaling list sets
     */
    void setApsTables(VvcAps[] alf, VvcAps[] lmcsTable, VvcAps[] scaling) {
        this.alfAps = alf;
        this.lmcsAps = lmcsTable;
        this.scalingAps = scaling;
    }

    /**
     * {@return the adaptive loop filter set with an identifier, or {@code null}}
     *
     * @param id {@code aps_adaptation_parameter_set_id}
     */
    VvcAps alfAps(int id) {
        return alfAps[id];
    }

    /**
     * Prepares the reconstruction of a picture.
     *
     * @param sps its sequence parameter set
     * @param ph its picture header
     */
    void beginPicture(VvcSps sps, VvcPictureHeader ph) {
        this.sps = sps;
        bitDepth = sps.bitDepth;
        maxVal = (1 << bitDepth) - 1;
        sx = sps.chromaShiftX();
        sy = sps.chromaShiftY();
        intra.setBitDepth(bitDepth);
        inter.setBitDepth(bitDepth);
        if (ph.lmcsEnabled) {
            var aps = lmcsAps[ph.lmcsAps];
            if (aps == null) {
                throw new MediaException.Malformed("vvc picture refers to a missing LMCS parameter set");
            }
            lmcs.init(aps, bitDepth);
        }
    }

    /**
     * Prepares the reconstruction of a slice: its luma mapping, chroma scaling and scaling list, and, for the
     * primary reconstruction of the slice, the adaptive loop filter parameter sets of the header.
     *
     * @param header the slice header
     * @param primary whether to complete the header with the adaptive loop filter parameter sets it refers to, which
     * one reconstruction does for each slice before the others are prepared
     * @throws MediaException.Malformed if the slice refers to a missing parameter set
     */
    void beginSlice(VvcSliceHeader header, boolean primary) {
        sh = header;
        pps = header.pps;
        lmcsUsed = header.lmcsUsed;
        chromaScaling = lmcsUsed && header.ph.chromaResidualScale;
        scalingList = null;
        if (header.scalingListUsed) {
            var aps = scalingAps[header.ph.scalingListAps];
            if (aps == null) {
                throw new MediaException.Malformed("vvc slice refers to a missing scaling list parameter set");
            }
            scalingList = aps.scalingList;
        }
        inter.beginSlice(header);
        if (!primary) {
            return;
        }
        for (var i = 0; i < header.numAlfApsLuma; i++) {
            header.alfLumaApsRefs[i] = alfAps[header.alfApsLuma[i]];
        }
        header.alfChromaApsRef = alfAps[header.alfApsChroma];
        header.ccAlfApsRefs[0] = alfAps[header.ccAlfCbAps];
        header.ccAlfApsRefs[1] = alfAps[header.ccAlfCrAps];
        if (header.alfEnabled) {
            for (var i = 0; i < header.numAlfApsLuma; i++) {
                if (header.alfLumaApsRefs[i] == null || !header.alfLumaApsRefs[i].alfLumaSignalled) {
                    throw new MediaException.Malformed("vvc slice refers to a missing ALF parameter set");
                }
            }
            if ((header.alfCb || header.alfCr)
                    && (header.alfChromaApsRef == null || !header.alfChromaApsRef.alfChromaSignalled)) {
                throw new MediaException.Malformed("vvc slice refers to a missing chroma ALF parameter set");
            }
        }
        if ((header.ccAlfCb && (header.ccAlfApsRefs[0] == null || !header.ccAlfApsRefs[0].ccAlfSignalled[0]))
                || (header.ccAlfCr && (header.ccAlfApsRefs[1] == null || !header.ccAlfApsRefs[1].ccAlfSignalled[1]))) {
            throw new MediaException.Malformed("vvc slice refers to a missing CC-ALF parameter set");
        }
    }

    /**
     * Reconstructs a coding unit.
     *
     * @param cu the coding unit
     * @param tus its transform units
     */
    void reconstruct(VvcCu cu, VvcTus tus) {
        recordCu(cu, tus);
        if (cu.predMode == VvcCu.MODE_INTRA) {
            if (cu.hasLuma()) {
                intraLuma(cu, tus);
            }
            if (cu.hasChroma(sps.chromaFormatIdc)) {
                intraChroma(cu, tus);
            }
        } else {
            motion.derive(cu);
            if (cu.predMode == VvcCu.MODE_IBC) {
                inter.predictIbc(cu, motion);
            } else {
                inter.predict(cu, motion);
                if (cu.ciip) {
                    ciip(cu);
                }
            }
            interResidual(cu, tus);
        }
        if (cu.hasLuma()) {
            f.markDecoded(cu.x, cu.y, cu.w, cu.h, false);
        }
        if (cu.hasChroma(sps.chromaFormatIdc)) {
            f.markDecoded(cu.x, cu.y, cu.w, cu.h, true);
        }
    }

    /**
     * Stores the per 4x4 block values the deblocking filter reads.
     *
     * @param cu the coding unit
     * @param tus its transform units
     */
    private void recordCu(VvcCu cu, VvcTus tus) {
        var w4 = f.width4;
        var x0 = cu.x >> 2;
        var y0 = cu.y >> 2;
        var x1 = Math.min(w4, (cu.x + cu.w) >> 2);
        var y1 = Math.min(f.height4, (cu.y + cu.h) >> 2);
        var luma = cu.hasLuma();
        var chroma = cu.hasChroma(sps.chromaFormatIdc);
        var intraLike = cu.predMode == VvcCu.MODE_INTRA || cu.ciip;
        for (var y = y0; y < y1; y++) {
            for (var x = x0; x < x1; x++) {
                var i = y * w4 + x;
                var info = f.blockInfo[i];
                if (luma) {
                    info = (byte) ((info & ~(0x01 | 0x08 | 0x10 | 0x40)) | (intraLike ? 0x08 : 0)
                            | (cu.bdpcmLuma != 0 ? 0x10 : 0) | (cu.predMode == VvcCu.MODE_IBC ? 0x40 : 0));
                    f.qp4[i] = (byte) cu.qpY;
                }
                if (chroma) {
                    info = (byte) ((info & ~(0x02 | 0x04 | 0x20 | 0x80)) | (cu.bdpcmChroma != 0 ? 0x20 : 0)
                            | (intraLike ? 0x80 : 0));
                }
                f.blockInfo[i] = info;
            }
        }
        if (luma) {
            markEdges(cu.x, cu.y, cu.w, cu.h, 2, false);
        }
        if (chroma) {
            markEdges(cu.x, cu.y, cu.w, cu.h, 2, true);
        }
        if (tus.count == 0) {
            recordUncoded(cu, luma, chroma, tus);
        }
        for (var t = 0; t < tus.count; t++) {
            if (luma && tus.hasLuma[t]) {
                markEdges(tus.x[t], tus.y[t], tus.w[t], tus.h[t], 1, false);
                setTuInfo(tus.x[t], tus.y[t], tus.w[t], tus.h[t], (tus.cbf[t] & 1) != 0 ? 0x01 : 0, f.tbLumaW, f.tbLumaH,
                        tus.w[t], tus.h[t], 0x01);
            }
            if (chroma && tus.hasChroma[t]) {
                var cxl = tus.cx[t];
                var cyl = tus.cy[t];
                var cwl = tus.cw[t] << sx;
                var chl = tus.ch[t] << sy;
                markEdges(cxl, cyl, cwl, chl, 1, true);
                var bits = ((tus.cbf[t] & 2) != 0 || tus.jointCbCr[t] != 0 ? 0x02 : 0)
                        | ((tus.cbf[t] & 4) != 0 || tus.jointCbCr[t] != 0 ? 0x04 : 0);
                setTuInfo(cxl, cyl, cwl, chl, bits, f.tbChromaW, f.tbChromaH, tus.cw[t], tus.ch[t], 0x06);
                setChromaQp(cu, tus, t, cxl, cyl, cwl, chl);
            }
        }
    }

    /**
     * Records the transform blocks of a coding unit without coded residual: the coding block split at the maximum
     * transform size (8.8.3.3), with no coded coefficients.
     *
     * @param cu the coding unit
     * @param luma whether the luma blocks are recorded
     * @param chroma whether the chroma blocks are recorded
     * @param tus the transform units, which receive the blocks
     */
    private void recordUncoded(VvcCu cu, boolean luma, boolean chroma, VvcTus tus) {
        var maxTb = 1 << sps.log2MaxTbSize;
        var tw = Math.min(cu.w, maxTb);
        var th = Math.min(cu.h, maxTb);
        for (var y = cu.y; y < cu.y + cu.h; y += th) {
            for (var x = cu.x; x < cu.x + cu.w; x += tw) {
                if (luma) {
                    markEdges(x, y, tw, th, 1, false);
                    setTuInfo(x, y, tw, th, 0, f.tbLumaW, f.tbLumaH, tw, th, 0x01);
                }
                if (chroma) {
                    markEdges(x, y, tw, th, 1, true);
                    setTuInfo(x, y, tw, th, 0, f.tbChromaW, f.tbChromaH, tw >> sx, th >> sy, 0x06);
                    setChromaQp(cu, false, tus.cuQpOffsetCb, tus.cuQpOffsetCr, tus.cuQpOffsetCbCr, x, y, tw, th);
                }
            }
        }
    }

    /**
     * Stores the chroma quantization parameters of a transform unit, less {@code QpBdOffset}, for the chroma
     * deblocking filter: {@code QpCbCr} for both components of a unit coded with {@code TuCResMode} 2.
     *
     * @param cu the coding unit
     * @param tus its transform units
     * @param t the index of the transform unit
     * @param x the luma column of the area
     * @param y the luma row of the area
     * @param w the luma width of the area
     * @param h the luma height of the area
     */
    private void setChromaQp(VvcCu cu, VvcTus tus, int t, int x, int y, int w, int h) {
        setChromaQp(cu, tus.jointCbCr[t] == 3, tus.qpOffsetCb[t], tus.qpOffsetCr[t], tus.qpOffsetCbCr[t], x, y, w, h);
    }

    /**
     * Stores the chroma quantization parameters of an area, less {@code QpBdOffset}, for the chroma deblocking filter
     * (8.7.1).
     *
     * @param cu the coding unit
     * @param joint whether the area is coded with {@code TuCResMode} 2, whose {@code QpCbCr} applies to both components
     * @param offsetCb {@code CuQpOffsetCb}
     * @param offsetCr {@code CuQpOffsetCr}
     * @param offsetCbCr {@code CuQpOffsetCbCr}
     * @param x the luma column of the area
     * @param y the luma row of the area
     * @param w the luma width of the area
     * @param h the luma height of the area
     */
    private void setChromaQp(VvcCu cu, boolean joint, int offsetCb, int offsetCr, int offsetCbCr, int x, int y, int w,
            int h) {
        var bd = sps.qpBdOffset;
        var qpc = Math.clamp(cu.qpY, -bd, 63);
        int qpCb;
        int qpCr;
        if (joint) {
            qpCb = Math.clamp(sps.chromaQpTable[2][qpc + bd] + pps.jointCbCrQpOffset + sh.jointCbCrQpOffset
                    + offsetCbCr, -bd, 63);
            qpCr = qpCb;
        } else {
            qpCb = Math.clamp(sps.chromaQpTable[0][qpc + bd] + pps.cbQpOffset + sh.cbQpOffset + offsetCb, -bd, 63);
            qpCr = Math.clamp(sps.chromaQpTable[1][qpc + bd] + pps.crQpOffset + sh.crQpOffset + offsetCr, -bd, 63);
        }
        var w4 = f.width4;
        var x0 = x >> 2;
        var y0 = y >> 2;
        var x1 = Math.min(w4, (x + w + 3) >> 2);
        var y1 = Math.min(f.height4, (y + h + 3) >> 2);
        for (var yy = y0; yy < y1; yy++) {
            for (var xx = x0; xx < x1; xx++) {
                f.qpCb4[yy * w4 + xx] = (byte) qpCb;
                f.qpCr4[yy * w4 + xx] = (byte) qpCr;
            }
        }
    }

    /**
     * Records the transform block size and flags of the 4x4 blocks of an area.
     *
     * @param x the luma column of the area
     * @param y the luma row of the area
     * @param w the luma width of the area
     * @param h the luma height of the area
     * @param bits the flags to set in {@code blockInfo}
     * @param widths the map of transform block widths
     * @param heights the map of transform block heights
     * @param tw the transform block width
     * @param th the transform block height
     * @param mask the flags of {@code blockInfo} the area replaces
     */
    private void setTuInfo(int x, int y, int w, int h, int bits, byte[] widths, byte[] heights, int tw, int th,
            int mask) {
        var w4 = f.width4;
        var x0 = x >> 2;
        var y0 = y >> 2;
        var x1 = Math.min(w4, (x + w + 3) >> 2);
        var y1 = Math.min(f.height4, (y + h + 3) >> 2);
        for (var yy = y0; yy < y1; yy++) {
            for (var xx = x0; xx < x1; xx++) {
                var i = yy * w4 + xx;
                f.blockInfo[i] = (byte) ((f.blockInfo[i] & ~mask) | bits);
                widths[i] = (byte) tw;
                heights[i] = (byte) th;
            }
        }
    }

    /**
     * Marks the left and top boundaries of a block as deblocking edges of the given kind (bit 0 transform, bit 1
     * coding block), for luma (low half of the edge flags of a direction) or chroma (high half).
     *
     * @param x the luma column of the block
     * @param y the luma row of the block
     * @param w the luma width of the block
     * @param h the luma height of the block
     * @param kind the edge kind
     * @param chroma whether the chroma edges are marked rather than the luma ones
     */
    void markEdges(int x, int y, int w, int h, int kind, boolean chroma) {
        var w4 = f.width4;
        var bitV = (chroma ? kind << 2 : kind);
        var bitH = bitV << 4;
        var x0 = x >> 2;
        var y0 = y >> 2;
        var x1 = Math.min(w4, (x + w + 3) >> 2);
        var y1 = Math.min(f.height4, (y + h + 3) >> 2);
        if ((x & 3) == 0) {
            for (var yy = y0; yy < y1; yy++) {
                f.edges[yy * w4 + x0] |= (byte) bitV;
            }
        }
        if ((y & 3) == 0) {
            for (var xx = x0; xx < x1; xx++) {
                f.edges[y0 * w4 + xx] |= (byte) bitH;
            }
        }
    }

    /**
     * Reconstructs the luma blocks of an intra coding unit, sub-partition by sub-partition, each predicted from the
     * reconstruction of the previous ones.
     *
     * @param cu the coding unit
     * @param tus its transform units
     */
    private void intraLuma(VvcCu cu, VvcTus tus) {
        var plane = f.pic.planes[0];
        var stride = f.pic.planeWidth[0];
        var isp = cu.ispMode != 0;
        var pbW = 0;
        var groupX = 0;
        for (var t = 0; t < tus.count; t++) {
            if (!tus.hasLuma[t]) {
                continue;
            }
            var x = tus.x[t];
            var y = tus.y[t];
            var w = tus.w[t];
            var h = tus.h[t];
            var pw = w;
            var px = 0;
            if (isp && w < 4) {
                pw = 4;
                var factor = 4 / w;
                var index = (x - cu.x) / w;
                px = (index % factor) * w;
                if (index % factor == 0) {
                    predictLuma(cu, x, y, 4, h, plane, stride, isp);
                }
            } else if (cu.mip) {
                intra.predictMip(pred, plane, stride, x, y, w, h, cu.mipMode, cu.mipTranspose);
            } else {
                predictLuma(cu, x, y, w, h, plane, stride, isp);
            }
            var hasRes = (tus.cbf[t] & 1) != 0;
            if (hasRes) {
                lumaResidual(cu, tus, t, w, h);
            }
            for (var j = 0; j < h; j++) {
                var row = (y + j) * stride + x;
                for (var i = 0; i < w; i++) {
                    var p = pred[j * pw + px + i];
                    var v = hasRes ? p + res[j * w + i] : p;
                    plane[row + i] = (short) Math.clamp(v, 0, maxVal);
                }
            }
            f.markDecoded(x, y, w, h, false);
        }
    }

    /**
     * Predicts a luma block of an intra coding unit into {@link #pred}.
     *
     * @param cu the coding unit
     * @param x the luma column of the block
     * @param y the luma row of the block
     * @param w the block width
     * @param h the block height
     * @param plane the luma plane
     * @param stride the row stride of the plane
     * @param isp whether the block is an intra sub-partition
     */
    private void predictLuma(VvcCu cu, int x, int y, int w, int h, short[] plane, int stride, boolean isp) {
        intra.predict(pred, plane, stride, x, y, w, h, cu.intraLuma, 0, cu.multiRefIdx, cu.w, cu.h, isp,
                cu.bdpcmLuma != 0, 0, 0);
    }

    /**
     * Computes the luma residual of a transform unit into {@link #res}.
     *
     * @param cu the coding unit
     * @param tus its transform units
     * @param t the index of the transform unit
     * @param w the block width
     * @param h the block height
     */
    private void lumaResidual(VvcCu cu, VvcTus tus, int t, int w, int h) {
        var b = block;
        b.w = w;
        b.h = h;
        b.bitDepth = bitDepth;
        b.qp = cu.qpY + sps.qpBdOffset;
        b.transformSkip = (tus.ts[t] & 1) != 0;
        b.depQuant = sh.depQuant && !b.transformSkip;
        b.bdpcm = cu.predMode == VvcCu.MODE_INTRA ? cu.bdpcmLuma : 0;
        b.minQpTs = 4 + 6 * sps.minQpPrimeTs;
        setTransformTypes(cu, 0, w, h);
        var lfnst = cu.lfnstIdx != 0 && !b.transformSkip;
        b.lfnstIdx = lfnst ? cu.lfnstIdx : 0;
        if (lfnst) {
            var mode = cu.mip ? 0 : cu.intraLuma;
            b.lfnstMode = wideAngle(mode, cu.ispMode != 0 ? cu.w : w, cu.ispMode != 0 ? cu.h : h);
        }
        setScaling(cu, 0, w, h, lfnst);
        transform.residual(b, tus.coeff[0], tus.offset[0][t], res);
    }

    /**
     * Derives {@code trTypeHor} and {@code trTypeVer} (8.7.4.1).
     *
     * @param cu the coding unit
     * @param c the component
     * @param w the block width
     * @param h the block height
     */
    private void setTransformTypes(VvcCu cu, int c, int w, int h) {
        var b = block;
        b.trTypeHor = 0;
        b.trTypeVer = 0;
        if (c > 0 || (cu.ispMode != 0 && cu.lfnstIdx != 0)) {
            return;
        }
        var implicit = sps.mts && (cu.ispMode != 0 || (cu.sbtIdx != 0 && Math.max(w, h) <= 32)
                || (!sps.explicitMtsIntra && cu.predMode == VvcCu.MODE_INTRA && cu.lfnstIdx == 0 && !cu.mip));
        if (implicit) {
            if (cu.sbtIdx != 0) {
                if (!cu.sbtHor) {
                    b.trTypeHor = cu.sbtPos == 0 ? 2 : 1;
                    b.trTypeVer = 1;
                } else {
                    b.trTypeHor = 1;
                    b.trTypeVer = cu.sbtPos == 0 ? 2 : 1;
                }
            } else {
                b.trTypeHor = w >= 4 && w <= 16 ? 1 : 0;
                b.trTypeVer = h >= 4 && h <= 16 ? 1 : 0;
            }
        } else {
            switch (cu.mtsIdx) {
                case 1 -> {
                    b.trTypeHor = 1;
                    b.trTypeVer = 1;
                }
                case 2 -> {
                    b.trTypeHor = 2;
                    b.trTypeVer = 1;
                }
                case 3 -> {
                    b.trTypeHor = 1;
                    b.trTypeVer = 2;
                }
                case 4 -> {
                    b.trTypeHor = 2;
                    b.trTypeVer = 2;
                }
                default -> {
                }
            }
        }
    }

    /**
     * Selects the scaling matrix of a transform block (8.7.3), flat scaling when the slice has no scaling list, the
     * block is transform skipped, or the low-frequency non-separable transform is used and the sequence disables its
     * scaling.
     *
     * @param cu the coding unit
     * @param c the component
     * @param w the block width
     * @param h the block height
     * @param lfnst whether the low-frequency non-separable transform applies to the block
     */
    private void setScaling(VvcCu cu, int c, int w, int h, boolean lfnst) {
        var b = block;
        b.scalingMatrix = null;
        b.scalingDc = -1;
        if (scalingList == null || b.transformSkip || (sps.scalingMatrixForLfnstDisabled && lfnst)) {
            return;
        }
        var id = VvcTransform.scalingId(cu.predMode == VvcCu.MODE_INTRA, c, w, h);
        if (id < 0) {
            return;
        }
        b.scalingMatrix = scalingList.coeff[id];
        b.scalingSize = VvcScalingList.matrixSize(id);
        b.scalingDc = id > 13 ? scalingList.dc[id] : -1;
    }

    /**
     * Applies the wide-angle mode mapping (8.4.5.2.7) for the block dimensions given.
     *
     * @param mode the intra prediction mode
     * @param w the block width
     * @param h the block height
     * @return the mapped mode, from -14 to 80
     */
    static int wideAngle(int mode, int w, int h) {
        if (w == h || mode < 2 || mode > 66) {
            return mode;
        }
        var ratio = Math.abs(Integer.numberOfTrailingZeros(w) - Integer.numberOfTrailingZeros(h));
        if (w > h && mode < (ratio > 1 ? 8 + 2 * ratio : 8)) {
            return mode + 65;
        }
        if (h > w && mode > (ratio > 1 ? 60 - 2 * ratio : 60)) {
            return mode - 67;
        }
        return mode;
    }

    /**
     * Reconstructs the chroma blocks of an intra coding unit: the prediction, the residuals and their chroma scaling.
     *
     * @param cu the coding unit
     * @param tus its transform units
     */
    private void intraChroma(VvcCu cu, VvcTus tus) {
        var luma = f.pic.planes[0];
        var lumaStride = f.pic.planeWidth[0];
        var mode = cu.intraChroma;
        for (var t = 0; t < tus.count; t++) {
            if (!tus.hasChroma[t]) {
                continue;
            }
            var xc = tus.cx[t] >> sx;
            var yc = tus.cy[t] >> sy;
            var w = tus.cw[t];
            var h = tus.ch[t];
            var present = chromaResiduals(cu, tus, t, w, h);
            var scale = chromaScaling && w * h > 4 && (tus.cbf[t] & 6) != 0
                    ? chromaScaleFactor(tus.cx[t], tus.cy[t]) : -1;
            for (var c = 1; c <= 2; c++) {
                var plane = f.pic.planes[c];
                var stride = f.pic.planeWidth[c];
                if (mode >= 81) {
                    intra.predictCclm(pred, plane, stride, luma, lumaStride, xc, yc, w, h, mode, sx, sy,
                            sps.chromaVerCollocated, sps.ctuSize);
                } else {
                    intra.predict(pred, plane, stride, xc, yc, w, h, mode, c, 0, w, h, false, cu.bdpcmChroma != 0,
                            sx, sy);
                }
                writeChroma(plane, stride, xc, yc, w, h, c == 1 ? res : res2, (present & c) != 0, scale);
            }
            f.markDecoded(tus.cx[t], tus.cy[t], w << sx, h << sy, true);
        }
    }

    /**
     * Writes a chroma block: the prediction in {@link #pred} plus the residual, scaled by {@code varScale} unless it
     * is negative.
     *
     * @param plane the chroma plane
     * @param stride the row stride of the plane
     * @param xc the chroma column of the block
     * @param yc the chroma row of the block
     * @param w the block width
     * @param h the block height
     * @param residual the residual samples, {@code w} per row
     * @param hasRes whether the block has a residual
     * @param scale {@code varScale}, or a negative value without chroma residual scaling
     */
    private void writeChroma(short[] plane, int stride, int xc, int yc, int w, int h, int[] residual, boolean hasRes,
                             int scale) {
        for (var j = 0; j < h; j++) {
            var row = (yc + j) * stride + xc;
            for (var i = 0; i < w; i++) {
                var p = pred[j * w + i];
                int v;
                if (!hasRes) {
                    v = p;
                } else if (scale >= 0) {
                    var r = Math.clamp(residual[j * w + i], -(1 << bitDepth), (1 << bitDepth) - 1);
                    var s = (Math.abs(r) * scale + (1 << 10)) >> 11;
                    v = p + (r < 0 ? -s : s);
                } else {
                    v = p + residual[j * w + i];
                }
                plane[row + i] = (short) Math.clamp(v, 0, maxVal);
            }
        }
    }

    /**
     * Computes the Cb residual into {@link #res} and the Cr residual into {@link #res2} (8.7.2), joint Cb-Cr coding
     * included.
     *
     * @return bit 0 set when Cb has a residual, bit 1 when Cr has one
     * @param cu the coding unit
     * @param tus its transform units
     * @param t the index of the transform unit
     * @param w the chroma block width
     * @param h the chroma block height
     */
    private int chromaResiduals(VvcCu cu, VvcTus tus, int t, int w, int h) {
        var mask = tus.jointCbCr[t];
        var cbf = tus.cbf[t];
        var mode = mask == 0 ? 0 : mask == 2 ? 1 : mask == 3 ? 2 : 3;
        if (mode == 0) {
            var present = 0;
            if ((cbf & 2) != 0) {
                chromaTransform(cu, tus, t, 1, w, h, 0, res);
                present |= 1;
            }
            if ((cbf & 4) != 0) {
                chromaTransform(cu, tus, t, 2, w, h, 0, res2);
                present |= 2;
            }
            return present;
        }
        var sign = sh.ph.jointCbCrSign ? -1 : 1;
        var n = w * h;
        if (mode == 3) {
            chromaTransform(cu, tus, t, 2, w, h, mode, res2);
            for (var i = 0; i < n; i++) {
                res[i] = (sign * res2[i]) >> 1;
            }
        } else {
            chromaTransform(cu, tus, t, 1, w, h, mode, res);
            for (var i = 0; i < n; i++) {
                res2[i] = mode == 2 ? sign * res[i] : (sign * res[i]) >> 1;
            }
        }
        return 3;
    }

    /**
     * Scales and inverse-transforms the coefficients a chroma component of a transform unit codes.
     *
     * @param codedC the coded component, 1 or 2
     * @param mode {@code TuCResMode}
     * @param cu the coding unit
     * @param tus its transform units
     * @param t the index of the transform unit
     * @param w the chroma block width
     * @param h the chroma block height
     * @param out receives the residual
     */
    private void chromaTransform(VvcCu cu, VvcTus tus, int t, int codedC, int w, int h, int mode, int[] out) {
        var b = block;
        b.w = w;
        b.h = h;
        b.bitDepth = bitDepth;
        var bd = sps.qpBdOffset;
        var qpc = Math.clamp(cu.qpY, -bd, 63);
        if (mode == 2) {
            b.qp = Math.clamp(sps.chromaQpTable[2][qpc + bd] + pps.jointCbCrQpOffset + sh.jointCbCrQpOffset
                    + tus.qpOffsetCbCr[t], -bd, 63) + bd;
        } else if (codedC == 1) {
            b.qp = Math.clamp(sps.chromaQpTable[0][qpc + bd] + pps.cbQpOffset + sh.cbQpOffset + tus.qpOffsetCb[t],
                    -bd, 63) + bd;
        } else {
            b.qp = Math.clamp(sps.chromaQpTable[1][qpc + bd] + pps.crQpOffset + sh.crQpOffset + tus.qpOffsetCr[t],
                    -bd, 63) + bd;
        }
        b.transformSkip = (tus.ts[t] & (1 << codedC)) != 0;
        b.depQuant = sh.depQuant && !b.transformSkip;
        b.bdpcm = cu.predMode == VvcCu.MODE_INTRA ? cu.bdpcmChroma : 0;
        b.minQpTs = 4 + 6 * sps.minQpPrimeTs;
        setTransformTypes(cu, codedC, w, h);
        var lfnst = cu.lfnstIdx != 0 && !b.transformSkip && cu.treeType == VvcCu.TREE_CHROMA;
        b.lfnstIdx = lfnst ? cu.lfnstIdx : 0;
        if (lfnst) {
            var m = cu.intraChroma;
            if (m >= 81) {
                var center = f.cuAt(Math.min(cu.x + cu.w / 2, f.width - 1), Math.min(cu.y + cu.h / 2, f.height - 1),
                        false);
                if (center == null || center.mip) {
                    m = 0;
                } else if (center.predMode == VvcCu.MODE_IBC) {
                    m = 1;
                } else {
                    m = center.intraLuma;
                }
            }
            b.lfnstMode = wideAngle(m, w, h);
        }
        setScaling(cu, codedC, w, h, lfnst);
        transform.residual(b, tus.coeff[codedC], tus.offset[codedC][t], out);
    }

    /**
     * Computes {@code varScale} of a chroma transform block (8.7.5.3) from its luma location.
     *
     * @param xY the luma column of the block
     * @param yY the luma row of the block
     * @return {@code varScale}
     */
    private int chromaScaleFactor(int xY, int yY) {
        var sizeY = Math.min(sps.ctuSize, 64);
        var vx = xY / sizeY * sizeY;
        var vy = yY / sizeY * sizeY;
        var cu = f.cuAt(vx, vy, false);
        var xCu = cu != null ? cu.x : vx;
        var yCu = cu != null ? cu.y : vy;
        var luma = f.pic.planes[0];
        var stride = f.pic.planeWidth[0];
        var availL = f.sampleAvailable(xCu - 1, yCu, xCu, yCu, false);
        var availT = f.sampleAvailable(xCu, yCu - 1, xCu, yCu, false);
        var sum = 0;
        var cnt = 0;
        if (availL) {
            for (var i = 0; i < sizeY; i++) {
                sum += luma[Math.min(yCu + i, f.height - 1) * stride + xCu - 1];
            }
            cnt += sizeY;
        }
        if (availT) {
            for (var i = 0; i < sizeY; i++) {
                sum += luma[(yCu - 1) * stride + Math.min(xCu + i, f.width - 1)];
            }
            cnt += sizeY;
        }
        var avg = cnt > 0 ? (sum + (cnt >> 1)) >> Integer.numberOfTrailingZeros(cnt) : 1 << (bitDepth - 1);
        return lmcs.chromaScale(avg);
    }

    /**
     * Adds the residuals of an inter coding unit to its prediction, with the chroma residual scaling, and records the
     * reconstruction.
     *
     * @param cu the coding unit
     * @param tus its transform units
     */
    private void interResidual(VvcCu cu, VvcTus tus) {
        var lumaPlane = f.pic.planes[0];
        var lumaStride = f.pic.planeWidth[0];
        var map = lmcsUsed && cu.predMode == VvcCu.MODE_INTER && !cu.ciip;
        if (cu.hasLuma() && map) {
            for (var y = cu.y; y < cu.y + cu.h; y++) {
                for (var x = cu.x; x < cu.x + cu.w; x++) {
                    var i = y * lumaStride + x;
                    lumaPlane[i] = (short) lmcs.forward(lumaPlane[i]);
                }
            }
        }
        for (var t = 0; t < tus.count; t++) {
            if (tus.hasLuma[t] && (tus.cbf[t] & 1) != 0) {
                var w = tus.w[t];
                var h = tus.h[t];
                lumaResidual(cu, tus, t, w, h);
                for (var j = 0; j < h; j++) {
                    var row = (tus.y[t] + j) * lumaStride + tus.x[t];
                    for (var i = 0; i < w; i++) {
                        lumaPlane[row + i] = (short) Math.clamp(lumaPlane[row + i] + res[j * w + i], 0, maxVal);
                    }
                }
            }
        }
        if (!cu.hasChroma(sps.chromaFormatIdc)) {
            return;
        }
        for (var t = 0; t < tus.count; t++) {
            if (!tus.hasChroma[t]) {
                continue;
            }
            var w = tus.cw[t];
            var h = tus.ch[t];
            var xc = tus.cx[t] >> sx;
            var yc = tus.cy[t] >> sy;
            var present = chromaResiduals(cu, tus, t, w, h);
            if (present == 0) {
                continue;
            }
            var scale = chromaScaling && w * h > 4 ? chromaScaleFactor(tus.cx[t], tus.cy[t]) : -1;
            for (var c = 1; c <= 2; c++) {
                if ((present & c) == 0) {
                    continue;
                }
                var plane = f.pic.planes[c];
                var stride = f.pic.planeWidth[c];
                for (var j = 0; j < h; j++) {
                    var row = (yc + j) * stride + xc;
                    for (var i = 0; i < w; i++) {
                        pred[j * w + i] = plane[row + i];
                    }
                }
                writeChroma(plane, stride, xc, yc, w, h, c == 1 ? res : res2, true, scale);
            }
        }
    }

    /**
     * Blends the planar intra prediction of a combined inter and intra coding unit into its inter prediction
     * (8.5.6.7), the inter luma prediction forward mapped when LMCS is used.
     *
     * @param cu the coding unit
     */
    private void ciip(VvcCu cu) {
        var lumaPlane = f.pic.planes[0];
        var stride = f.pic.planeWidth[0];
        var wt = ciipWeight(cu);
        if (lmcsUsed) {
            for (var y = cu.y; y < cu.y + cu.h; y++) {
                for (var x = cu.x; x < cu.x + cu.w; x++) {
                    var i = y * stride + x;
                    lumaPlane[i] = (short) lmcs.forward(lumaPlane[i]);
                }
            }
        }
        var interBuf = res;
        var comps = sps.chromaFormatIdc == 0 || (cu.w >> sx) <= 2 ? 1 : 3;
        for (var c = 0; c < comps; c++) {
            var plane = f.pic.planes[c];
            var pstride = f.pic.planeWidth[c];
            var cxs = c == 0 ? 0 : sx;
            var cys = c == 0 ? 0 : sy;
            var x0 = cu.x >> cxs;
            var y0 = cu.y >> cys;
            var w = cu.w >> cxs;
            var h = cu.h >> cys;
            for (var j = 0; j < h; j++) {
                var row = (y0 + j) * pstride + x0;
                for (var i = 0; i < w; i++) {
                    interBuf[j * w + i] = plane[row + i];
                }
            }
            intra.predict(pred, plane, pstride, x0, y0, w, h, 0, c, 0, w, h, false, false, cxs, cys);
            for (var j = 0; j < h; j++) {
                var row = (y0 + j) * pstride + x0;
                for (var i = 0; i < w; i++) {
                    plane[row + i] = (short) ((wt * pred[j * w + i] + (4 - wt) * interBuf[j * w + i] + 2) >> 2);
                }
            }
        }
    }

    /**
     * Derives the CIIP intra weight {@code w} (8.5.6.7) from the neighbors' intra modes.
     *
     * @param cu the coding unit
     * @return the weight, 1 to 3
     */
    private int ciipWeight(VvcCu cu) {
        var a = f.available(cu.x - 1, cu.y + cu.h - 1, cu.x, cu.y, false);
        var b = f.available(cu.x + cu.w - 1, cu.y - 1, cu.x, cu.y, false);
        var intraA = a != null && a.predMode == VvcCu.MODE_INTRA ? 1 : 0;
        var intraB = b != null && b.predMode == VvcCu.MODE_INTRA ? 1 : 0;
        var sum = intraA + intraB;
        return sum == 2 ? 3 : sum == 1 ? 2 : 1;
    }
}
