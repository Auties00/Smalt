package com.github.auties00.smalt.video.h264.internal;

/**
 * Applies the deblocking filter of the scalable decoding process (ITU-T H.264 F.8.7) to the
 * constructed samples of a layer picture: the filter of the intra macroblocks of a reference
 * layer before Intra_Base resampling (F.8.7.1) and the filter of a target representation
 * (F.8.7.2), for frames of 4:2:0 8-bit samples.
 */
final class SvcDeblocker {
    /**
     * Finds the slices of the access unit that coded the macroblocks being filtered.
     */
    interface Slices {
        /**
         * Returns the slice that coded a macroblock of the layer picture being filtered.
         *
         * @param sliceIdc the {@code sliceIdc} of the macroblock
         * @return the slice
         */
        SvcSlice slice(int sliceIdc);

        /**
         * Returns the slice whose reference picture lists a macroblock of the target
         * representation predicts from.
         *
         * @param sliceIdc the {@code sliceIdc} of the macroblock
         * @return the slice holding the lists
         */
        SvcSlice listSlice(int sliceIdc);
    }

    /**
     * The edge filter kernels.
     */
    private final H264Dsp dsp = H264Dsp.INSTANCE;

    /**
     * The scratch space of the edge filter kernels.
     */
    private final byte[] scratch = new byte[H264Dsp.EDGE_SCRATCH];

    /**
     * The filter parameters of the chroma edges of one macroblock edge, as the chroma kernels of {@link H264Dsp} take
     * them.
     */
    private final int[] chromaParams = new int[8];

    /**
     * The luma quantization parameter of each macroblock for the filter, {@code qPp} and {@code qPq} of ITU-T H.264
     * F.8.7.3.
     */
    private int[] qpY = new int[0];

    /**
     * The Cb quantization parameter of each macroblock for the filter.
     */
    private int[] qpCb = new int[0];

    /**
     * The Cr quantization parameter of each macroblock for the filter.
     */
    private int[] qpCr = new int[0];

    /**
     * The luma 4x4 blocks of each macroblock holding non-zero residual samples (see {@link #residualBlocks}).
     */
    private short[] residualMask = new short[0];

    /**
     * The luma 4x4 blocks of each macroblock holding non-zero scaled transform coefficients (see
     * {@link #coefficientBlocks}).
     */
    private short[] coeffMask = new short[0];

    /**
     * The boundary strengths of the four vertical luma edges of the current macroblock, -1 for an edge that is not
     * filtered.
     */
    private final int[] verticalStrengths = new int[4];

    /**
     * The boundary strengths of the four horizontal luma edges of the current macroblock, -1 for an edge that is not
     * filtered.
     */
    private final int[] horizontalStrengths = new int[4];

    /**
     * The arrays of the layer picture being filtered.
     */
    private SvcLayer layer;

    /**
     * The slices of the access unit being filtered.
     */
    private Slices slices;

    /**
     * Whether the filter runs on a reference layer before resampling, with the inter-layer deblocking settings.
     */
    private boolean interLayer;

    /**
     * Whether the representation being filtered has {@code SpatialResolutionChangeFlag} set.
     */
    private boolean resolutionChange;

    /**
     * Whether the representation being filtered belongs to the Scalable Baseline profile ({@code profile_idc} 83),
     * whose boundary strengths count transform coefficients of the reference layer (ITU-T H.264 F.8.7.4.3).
     */
    private boolean profile83;

    /**
     * The {@code disable_deblocking_filter_idc} of the slice being filtered, or its inter-layer counterpart.
     */
    private int disableIdc;

    /**
     * The {@code FilterOffsetA} of the slice being filtered.
     */
    private int offsetA;

    /**
     * The {@code FilterOffsetB} of the slice being filtered.
     */
    private int offsetB;

    /**
     * Constructs a deblocker with its working storage.
     */
    SvcDeblocker() {
    }

    /**
     * Filters a layer picture.
     *
     * @param layer the arrays of the layer picture
     * @param rep the layer representation whose syntax controls the filter: the reference layer
     * representation for the filter before resampling, the target representation otherwise
     * @param slices the slices of the access unit
     * @param interLayerRep the layer representation whose inter-layer deblocking settings apply,
     * for the filter before resampling, or {@code null} for the filter of a target representation
     */
    void filter(SvcLayer layer, SvcRepresentation rep, Slices slices, SvcRepresentation interLayerRep) {
        this.layer = layer;
        this.slices = slices;
        this.interLayer = interLayerRep != null;
        this.resolutionChange = rep.spatialResolutionChange;
        this.profile83 = rep.sps.profileIdc == 83;
        prepare(rep);
        if (interLayer) {
            SvcSlice source = null;
            for (var slice : interLayerRep.slices) {
                if (!slice.noInterLayerPred) {
                    source = slice;
                    break;
                }
            }
            if (source == null) {
                return;
            }
            disableIdc = source.interLayerDeblockingIdc;
            offsetA = source.interLayerOffsetA;
            offsetB = source.interLayerOffsetB;
            for (var mb = 0; mb < layer.mbCount; mb++) {
                filterMacroblock(mb, false);
            }
            if (disableIdc == 3 || disableIdc == 6) {
                for (var mb = 0; mb < layer.mbCount; mb++) {
                    filterMacroblock(mb, true);
                }
            }
            return;
        }
        for (var pass = 0; pass < 2; pass++) {
            for (var mb = 0; mb < layer.mbCount; mb++) {
                var idc = layer.sliceIdc[mb];
                if (idc < 0) {
                    continue;
                }
                var header = slices.slice(idc).header;
                disableIdc = header.disableDeblockingFilterIdc;
                offsetA = header.filterOffsetA;
                offsetB = header.filterOffsetB;
                if (pass == 0) {
                    filterMacroblock(mb, false);
                } else if (disableIdc == 3 || disableIdc == 6) {
                    filterMacroblock(mb, true);
                }
            }
        }
    }

    /**
     * Derives the quantization parameters of the filter (ITU-T H.264 F.8.7.3) and the residual
     * and coefficient masks of the macroblocks.
     *
     * @param rep the layer representation whose syntax controls the filter
     */
    private void prepare(SvcRepresentation rep) {
        var count = layer.mbCount;
        if (qpY.length < count) {
            qpY = new int[count];
            qpCb = new int[count];
            qpCr = new int[count];
            residualMask = new short[count];
            coeffMask = new short[count];
        }
        for (var mb = 0; mb < count; mb++) {
            qpY[mb] = layer.qpY[mb];
        }
        if (rep.maxTcoeffLevelPred) {
            var map = rep.groupMap;
            var groups = rep.pps.numSliceGroups;
            for (var group = 0; group < groups; group++) {
                var last = -1;
                for (var mb = 0; mb < count; mb++) {
                    if (map != null && map[mb] != group) {
                        continue;
                    }
                    if (last >= 0 && layer.mbType[mb] != SvcMb.I16X16 && coefficientsZero(mb)) {
                        qpY[mb] = qpY[last];
                    }
                    last = mb;
                }
            }
        }
        var pps = rep.pps;
        for (var mb = 0; mb < count; mb++) {
            if (layer.mbType[mb] == SvcMb.IPCM) {
                qpY[mb] = 0;
            }
            qpCb[mb] = SvcCoefficients.chromaQp(qpY[mb], pps.chromaQpIndexOffset);
            qpCr[mb] = SvcCoefficients.chromaQp(qpY[mb], pps.secondChromaQpIndexOffset);
            residualMask[mb] = residualBlocks(mb);
            coeffMask[mb] = coefficientBlocks(mb);
        }
    }

    /**
     * Returns whether all transform coefficient levels of a macroblock are 0.
     *
     * @param mb the macroblock address
     * @return {@code true} when every {@code tCoeffLevel} is 0
     */
    private boolean coefficientsZero(int mb) {
        var base = mb * SvcLayer.COEFFS;
        for (var i = 0; i < SvcLayer.COEFFS; i++) {
            if (layer.tCoeff[base + i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the luma 4x4 blocks of a macroblock holding non-zero residual samples, an 8x8
     * transform macroblock reporting all four blocks of each such 8x8 block.
     *
     * @param mb the macroblock address
     * @return the mask, bit {@code y * 4 + x} for the block at {@code (x, y)}
     */
    private short residualBlocks(int mb) {
        var w = layer.width;
        var x0 = (mb % layer.widthMbs) * 16;
        var y0 = (mb / layer.widthMbs) * 16;
        var mask = 0;
        for (var by = 0; by < 4; by++) {
            for (var bx = 0; bx < 4; bx++) {
                var at = (y0 + by * 4) * w + x0 + bx * 4;
                search:
                for (var y = 0; y < 4; y++) {
                    for (var x = 0; x < 4; x++) {
                        if (layer.rY[at + y * w + x] != 0) {
                            mask |= 1 << (by * 4 + bx);
                            break search;
                        }
                    }
                }
            }
        }
        return (short) (layer.cTrafo[mb] == SvcLayer.T_8X8 ? spread8x8(mask) : mask);
    }

    /**
     * Returns the luma 4x4 blocks of a macroblock holding non-zero scaled transform coefficients,
     * as {@link #residualBlocks}.
     *
     * @param mb the macroblock address
     * @return the mask
     */
    private short coefficientBlocks(int mb) {
        var base = mb * SvcLayer.COEFFS;
        var mask = 0;
        if (layer.cTrafo[mb] == SvcLayer.T_8X8) {
            for (var b8 = 0; b8 < 4; b8++) {
                for (var i = 0; i < 64; i++) {
                    if (layer.sTCoeff[base + b8 * 64 + i] != 0) {
                        mask |= 0x33 << ((b8 >> 1) * 8 + (b8 & 1) * 2);
                        break;
                    }
                }
            }
            return (short) mask;
        }
        for (var blk = 0; blk < 16; blk++) {
            for (var i = 0; i < 16; i++) {
                if (layer.sTCoeff[base + blk * 16 + i] != 0) {
                    mask |= 1 << (H264Tables.BLOCK_Y[blk] * 4 + H264Tables.BLOCK_X[blk]);
                    break;
                }
            }
        }
        return (short) mask;
    }

    /**
     * Extends a 4x4 block mask so that every block of an 8x8 block with a set block is set.
     *
     * @param mask the mask, bit {@code y * 4 + x}
     * @return the extended mask
     */
    private static int spread8x8(int mask) {
        var result = 0;
        for (var b8 = 0; b8 < 4; b8++) {
            var quad = 0x33 << ((b8 >> 1) * 8 + (b8 & 1) * 2);
            if ((mask & quad) != 0) {
                result |= quad;
            }
        }
        return result;
    }

    /**
     * Converts a mask indexed by {@code luma4x4BlkIdx} into one indexed by raster position.
     *
     * @param coded the mask, bit {@code luma4x4BlkIdx}
     * @return the mask, bit {@code y * 4 + x}
     */
    private static int rasterMask(int coded) {
        var result = 0;
        for (var blk = 0; blk < 16; blk++) {
            if ((coded & (1 << blk)) != 0) {
                result |= 1 << (H264Tables.BLOCK_Y[blk] * 4 + H264Tables.BLOCK_X[blk]);
            }
        }
        return result;
    }

    /**
     * Filters the edges of one macroblock (ITU-T H.264 F.8.7.4).
     *
     * @param mb the macroblock address
     * @param boundariesOnly the {@code sliceBoundariesOnlyFlag}
     */
    private void filterMacroblock(int mb, boolean boundariesOnly) {
        var w = layer.widthMbs;
        var mbX = mb % w;
        var mbY = mb / w;
        var inter = SvcMb.inter(layer.mbType[mb]);
        if (disableIdc == 1 || (interLayer && inter)) {
            return;
        }
        var current = layer.sliceIdc[mb];
        var left = mbX > 0 && edgeAllowed(mb - 1, current, boundariesOnly);
        var top = mbY > 0 && edgeAllowed(mb - w, current, boundariesOnly);
        var internal = !((disableIdc == 3 || disableIdc == 6) && boundariesOnly);
        var t8x8 = layer.cTrafo[mb] == SvcLayer.T_8X8;
        var stride = layer.stride;
        var origin = mbY * 16 * stride + SvcLayer.PAD + mbX * 16;
        var chromaStride = layer.chromaStride;
        var chromaOrigin = mbY * 8 * chromaStride + SvcLayer.PAD + mbX * 8;
        var chroma = disableIdc < 4;
        var verticalStrengths = this.verticalStrengths;
        var horizontalStrengths = this.horizontalStrengths;

        for (var edge = 0; edge < 4; edge++) {
            var filterEdge = edge == 0 ? left : internal && !(t8x8 && (edge & 1) != 0);
            verticalStrengths[edge] = filterEdge ? edgeStrengths(mb, edge, true) : -1;
            if (filterEdge && verticalStrengths[edge] != 0) {
                var p = edge == 0 ? mb - 1 : mb;
                filterLuma(layer.cY, origin + edge * 4, stride, verticalStrengths[edge], (qpY[p] + qpY[mb] + 1) >> 1,
                        true);
            }
        }
        for (var edge = 0; edge < 4; edge++) {
            var filterEdge = edge == 0 ? top : internal && !(t8x8 && (edge & 1) != 0);
            horizontalStrengths[edge] = filterEdge ? edgeStrengths(mb, edge, false) : -1;
            if (filterEdge && horizontalStrengths[edge] != 0) {
                var p = edge == 0 ? mb - w : mb;
                filterLuma(layer.cY, origin + edge * 4 * stride, stride, horizontalStrengths[edge],
                        (qpY[p] + qpY[mb] + 1) >> 1, false);
            }
        }
        if (!chroma) {
            return;
        }
        var edges = 0;
        if (verticalStrengths[0] > 0 && chromaEdge(mb - 1, mb, verticalStrengths[0], 0)) {
            edges |= 1;
        }
        if (verticalStrengths[2] > 0 && chromaEdge(mb, mb, verticalStrengths[2], 4)) {
            edges |= 2;
        }
        if (edges != 0) {
            dsp.chromaEdgesVertical(layer.cCb, layer.cCr, chromaOrigin, chromaStride, edges, chromaParams, scratch);
        }
        if (horizontalStrengths[0] > 0 && chromaEdge(mb - w, mb, horizontalStrengths[0], 0)) {
            dsp.chromaEdgeHorizontal(layer.cCb, layer.cCr, chromaOrigin, chromaStride, chromaParams, 0);
        }
        if (horizontalStrengths[2] > 0 && chromaEdge(mb, mb, horizontalStrengths[2], 0)) {
            dsp.chromaEdgeHorizontal(layer.cCb, layer.cCr, chromaOrigin + 4 * chromaStride, chromaStride,
                    chromaParams, 0);
        }
    }

    /**
     * Returns whether the edge between a macroblock and its left or upper neighbor is filtered.
     *
     * @param neighbor the neighbor address
     * @param current the {@code sliceIdc} of the current macroblock
     * @param boundariesOnly the {@code sliceBoundariesOnlyFlag}
     * @return {@code true} when the edge is filtered
     */
    private boolean edgeAllowed(int neighbor, int current, boolean boundariesOnly) {
        var same = layer.sliceIdc[neighbor] == current;
        if ((disableIdc == 2 || disableIdc == 5) && !same) {
            return false;
        }
        if (disableIdc == 3 || disableIdc == 6) {
            return boundariesOnly != same;
        }
        return !boundariesOnly;
    }

    /**
     * Derives the boundary strengths of the four segments of a luma edge (ITU-T H.264
     * F.8.7.4.3).
     *
     * @param mb the macroblock on the {@code q} side
     * @param edge the edge index, 0 to 3
     * @param vertical whether the edge is vertical
     * @return the strengths, segment {@code i} in bits {@code 4i..4i+3}
     */
    private int edgeStrengths(int mb, int edge, boolean vertical) {
        var w = layer.widthMbs;
        var packed = 0;
        for (var k = 0; k < 4; k++) {
            int mbP;
            int blockP;
            int blockQ;
            if (vertical) {
                blockQ = k * 4 + edge;
                if (edge == 0) {
                    mbP = mb - 1;
                    blockP = k * 4 + 3;
                } else {
                    mbP = mb;
                    blockP = k * 4 + edge - 1;
                }
            } else {
                blockQ = edge * 4 + k;
                if (edge == 0) {
                    mbP = mb - w;
                    blockP = 12 + k;
                } else {
                    mbP = mb;
                    blockP = (edge - 1) * 4 + k;
                }
            }
            packed |= strength(mbP, blockP, mb, blockQ, edge == 0) << (4 * k);
        }
        return packed;
    }

    /**
     * Derives the boundary strength between two 4x4 luma blocks.
     *
     * @param mbP the macroblock of the {@code p} block
     * @param blockP the raster index of the {@code p} block
     * @param mbQ the macroblock of the {@code q} block
     * @param blockQ the raster index of the {@code q} block
     * @param macroblockEdge whether the edge is a macroblock edge
     * @return the strength, 0 to 4
     */
    private int strength(int mbP, int blockP, int mbQ, int blockQ, boolean macroblockEdge) {
        var typeP = layer.mbType[mbP];
        var typeQ = layer.mbType[mbQ];
        var intraP = SvcMb.intra(typeP);
        var intraQ = SvcMb.intra(typeQ);
        if (interLayer && !intraP) {
            return 0;
        }
        if (resolutionChange && (typeP == SvcMb.IBL || typeQ == SvcMb.IBL)) {
            if ((intraP && typeP != SvcMb.IBL) || (intraQ && typeQ != SvcMb.IBL)) {
                return 4;
            }
            if (typeP == SvcMb.IBL && typeQ == SvcMb.IBL) {
                return codedBlock(mbP, blockP) || codedBlock(mbQ, blockQ) ? 1 : 0;
            }
            return (!intraP && (residualMask[mbP] & (1 << blockP)) != 0)
                    || (!intraQ && (residualMask[mbQ] & (1 << blockQ)) != 0) ? 2 : 1;
        }
        if (intraP || intraQ) {
            return macroblockEdge ? 4 : 3;
        }
        if (nonZero(mbP, blockP) || nonZero(mbQ, blockQ)) {
            return 2;
        }
        if (profile83 && ((coeffMask[mbP] & (1 << blockP)) != 0 || (coeffMask[mbQ] & (1 << blockQ)) != 0)) {
            return 2;
        }
        return motionDiffers(mbP, blockP, mbQ, blockQ) ? 1 : 0;
    }

    /**
     * Returns whether the transform block of a 4x4 luma block holds non-zero transform
     * coefficient levels in the slice that coded the macroblock.
     *
     * @param mb the macroblock address
     * @param block the raster index of the block
     * @return {@code true} when the levels are not all 0
     */
    private boolean codedBlock(int mb, int block) {
        var mask = rasterMask(layer.codedLuma[mb] & 0xFFFF);
        if (layer.cTrafo[mb] == SvcLayer.T_8X8) {
            mask = spread8x8(mask);
        }
        return (mask & (1 << block)) != 0;
    }

    /**
     * Returns whether a 4x4 luma block of an inter macroblock has non-zero residual samples or,
     * in a layer representation with DQId 0, coded coefficients (the strength 2 conditions of
     * ITU-T H.264 F.8.7.4.3).
     *
     * @param mb the macroblock address
     * @param block the raster index of the block
     * @return {@code true} for a strength of at least 2
     */
    private boolean nonZero(int mb, int block) {
        if ((residualMask[mb] & (1 << block)) != 0) {
            return true;
        }
        return (layer.sliceIdc[mb] & 127) == 0 && codedBlock(mb, block);
    }

    /**
     * Returns whether the motion of two inter 4x4 blocks differs enough for strength 1 (ITU-T
     * H.264 F.8.7.4.3).
     *
     * @param mbP the macroblock of the {@code p} block
     * @param blockP the raster index of the {@code p} block
     * @param mbQ the macroblock of the {@code q} block
     * @param blockQ the raster index of the {@code q} block
     * @return {@code true} for strength 1
     */
    private boolean motionDiffers(int mbP, int blockP, int mbQ, int blockQ) {
        var quadP = mbP * 4 + (blockP >> 3) * 2 + ((blockP & 3) >> 1);
        var quadQ = mbQ * 4 + (blockQ >> 3) * 2 + ((blockQ & 3) >> 1);
        var listsP = slices.listSlice(layer.sliceIdc[mbP]).refLists;
        var listsQ = slices.listSlice(layer.sliceIdc[mbQ]).refLists;
        var refP0 = reference(listsP, 0, layer.refIdx0[quadP]);
        var refP1 = reference(listsP, 1, layer.refIdx1[quadP]);
        var refQ0 = reference(listsQ, 0, layer.refIdx0[quadQ]);
        var refQ1 = reference(listsQ, 1, layer.refIdx1[quadQ]);
        var countP = (layer.refIdx0[quadP] >= 0 ? 1 : 0) + (layer.refIdx1[quadP] >= 0 ? 1 : 0);
        var countQ = (layer.refIdx0[quadQ] >= 0 ? 1 : 0) + (layer.refIdx1[quadQ] >= 0 ? 1 : 0);
        if (countP != countQ) {
            return true;
        }
        var mvP = mbP * 32 + blockP * 2;
        var mvQ = mbQ * 32 + blockQ * 2;
        if (countP == 1) {
            var refP = layer.refIdx0[quadP] >= 0 ? refP0 : refP1;
            var refQ = layer.refIdx0[quadQ] >= 0 ? refQ0 : refQ1;
            var mvsP = layer.refIdx0[quadP] >= 0 ? layer.mv0 : layer.mv1;
            var mvsQ = layer.refIdx0[quadQ] >= 0 ? layer.mv0 : layer.mv1;
            return refP != refQ || far(mvsP, mvP, mvsQ, mvQ);
        }
        if (refP0 != refP1) {
            if ((refP0 != refQ0 || refP1 != refQ1) && (refP0 != refQ1 || refP1 != refQ0)) {
                return true;
            }
            if (refP0 == refQ0 && refP1 == refQ1) {
                return far(layer.mv0, mvP, layer.mv0, mvQ) || far(layer.mv1, mvP, layer.mv1, mvQ);
            }
            return far(layer.mv0, mvP, layer.mv1, mvQ) || far(layer.mv1, mvP, layer.mv0, mvQ);
        }
        if (refQ0 != refP0 || refQ1 != refP0) {
            return true;
        }
        return (far(layer.mv0, mvP, layer.mv0, mvQ) || far(layer.mv1, mvP, layer.mv1, mvQ))
                && (far(layer.mv0, mvP, layer.mv1, mvQ) || far(layer.mv1, mvP, layer.mv0, mvQ));
    }

    /**
     * Returns the reference picture a reference index names.
     *
     * @param lists the reference picture lists of the slice
     * @param list the reference picture list index, 0 or 1
     * @param refIdx the reference index
     * @return the picture, or {@code null} when the index names none
     */
    private static Object reference(SvcPicture[][] lists, int list, int refIdx) {
        if (refIdx < 0 || lists == null || refIdx >= lists[list].length) {
            return null;
        }
        var picture = lists[list][refIdx];
        return picture != null && picture.frame != null ? picture.frame : picture;
    }

    /**
     * Returns whether two motion vectors differ by at least four quarter samples in a
     * component.
     *
     * @param a the first vector array
     * @param at the index of the first vector
     * @param b the second vector array
     * @param bt the index of the second vector
     * @return {@code true} when they differ by four or more
     */
    private static boolean far(short[] a, int at, short[] b, int bt) {
        return Math.abs(a[at] - b[bt]) >= 4 || Math.abs(a[at + 1] - b[bt + 1]) >= 4;
    }

    /**
     * Filters one luma edge.
     *
     * @param plane the luma plane
     * @param q0 the index of the first {@code q0} sample
     * @param stride the row stride
     * @param segments the packed strengths of the four segments
     * @param qpAv the average quantization parameter
     * @param vertical whether the edge is vertical
     */
    private void filterLuma(byte[] plane, int q0, int stride, int segments, int qpAv, boolean vertical) {
        var indexA = Math.clamp(qpAv + offsetA, 0, 51);
        var alpha = H264Tables.ALPHA[indexA] & 0xFF;
        var beta = H264Tables.BETA[Math.clamp(qpAv + offsetB, 0, 51)] & 0xFF;
        if (alpha == 0 || beta == 0) {
            return;
        }
        if ((segments & 0xF) == 4) {
            if (vertical) {
                dsp.lumaEdgeVerticalStrong(plane, q0, stride, alpha, beta, scratch);
            } else {
                dsp.lumaEdgeHorizontalStrong(plane, q0, stride, alpha, beta);
            }
            return;
        }
        var tc0 = packTc0(segments, indexA, false);
        if (vertical) {
            dsp.lumaEdgeVertical(plane, q0, stride, alpha, beta, tc0, scratch);
        } else {
            dsp.lumaEdgeHorizontal(plane, q0, stride, alpha, beta, tc0);
        }
    }

    /**
     * Fills the chroma parameters of one edge into {@link #chromaParams}.
     *
     * @param mbP the macroblock on the {@code p} side
     * @param mbQ the macroblock on the {@code q} side
     * @param segments the packed strengths of the luma edge's segments
     * @param at the index of the edge's first parameter
     * @return whether either plane has thresholds that can filter
     */
    private boolean chromaEdge(int mbP, int mbQ, int segments, int at) {
        var any = false;
        for (var plane = 0; plane < 2; plane++) {
            var qps = plane == 0 ? qpCb : qpCr;
            var qpAv = (qps[mbP] + qps[mbQ] + 1) >> 1;
            var indexA = Math.clamp(qpAv + offsetA, 0, 51);
            var alpha = H264Tables.ALPHA[indexA] & 0xFF;
            var beta = H264Tables.BETA[Math.clamp(qpAv + offsetB, 0, 51)] & 0xFF;
            if (alpha == 0 || beta == 0) {
                chromaParams[at + plane] = 0;
                chromaParams[at + 2 + plane] = -1;
            } else {
                chromaParams[at + plane] = alpha | (beta << 8);
                chromaParams[at + 2 + plane] = packTc0(segments, indexA, true);
                any = true;
            }
        }
        return any;
    }

    /**
     * Packs the {@code tC0} of four edge segments into the byte layout of the kernels.
     *
     * @param segments the packed strengths of the segments
     * @param indexA the {@code indexA} of the edge
     * @param chroma whether strength 4 selects {@link H264Dsp#CHROMA_STRONG}
     * @return the packed {@code tC0}, one signed byte per segment
     */
    private static int packTc0(int segments, int indexA, boolean chroma) {
        var packed = 0;
        for (var k = 0; k < 4; k++) {
            var bs = (segments >> (4 * k)) & 0xF;
            int tc;
            if (bs == 0) {
                tc = 0xFF;
            } else if (bs == 4) {
                tc = chroma ? H264Dsp.CHROMA_STRONG : 0xFF;
            } else {
                tc = H264Tables.TC0[indexA * 4 + bs] & 0xFF;
            }
            packed |= tc << (8 * k);
        }
        return packed;
    }
}
