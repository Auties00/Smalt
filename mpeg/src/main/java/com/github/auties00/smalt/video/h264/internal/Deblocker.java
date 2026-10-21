package com.github.auties00.smalt.video.h264.internal;

/**
 * Applies the H.264 deblocking filter to decoded macroblocks (ITU-T H.264 8.7).
 *
 * <p>Macroblocks must be filtered in raster order. The filter reads the decoded samples and the
 * per-macroblock state of the picture (types, quantization parameters, coefficient flags,
 * motion) and modifies the samples in place.
 *
 * <p>The boundary strengths of a macroblock are packed into two {@code long}s, one per edge
 * direction, four bits per 4-sample edge segment: edge {@code e} (0 for the macroblock edge,
 * 1 to 3 for the internal edges) segment {@code s} occupies bits {@code 16e + 4s} to
 * {@code 16e + 4s + 3}, so a whole edge is one 16-bit group that is zero exactly when the edge
 * needs no filtering.
 *
 * @implNote This implementation derives the coefficient-driven strengths of all segments with
 * shifts and small lookup tables over the 16-bit non-zero block masks, and compares motion only
 * for the segments those masks leave open; a macroblock whose 8x8 quarters (or whole area) share
 * one motion needs at most two motion comparisons per macroblock edge and none or two inside.
 */
final class Deblocker {
    /**
     * The packed strengths of an intra macroblock: 4 on the macroblock edge, 3 inside.
     */
    private static final long INTRA_STRENGTHS = 0x3333_3333_3333_4444L;

    /**
     * The packed strengths of the horizontal edges of an intra macroblock of a field: 3
     * everywhere, since horizontal macroblock edges take strength 4 only between frame
     * macroblocks.
     */
    private static final long FIELD_INTRA_HORIZONTAL = 0x3333_3333_3333_3333L;

    /**
     * Selects the strengths of edges 0 and 2, the edges a macroblock with the 8x8 transform
     * filters.
     */
    private static final long EVEN_EDGES = 0x0000_FFFF_0000_FFFFL;

    /**
     * Maps four segment flags to the packed strength 2 of the flagged segments of edge 0.
     */
    private static final long[] CODED_SEGMENTS = new long[16];

    /**
     * Maps the three internal vertical edge flags of one row of 4x4 blocks (bit {@code k} for
     * edge {@code k + 1}) to the packed strength 2 of that row's segment on each flagged edge.
     */
    private static final long[] CODED_ROW = new long[8];

    static {
        for (var bits = 0; bits < 16; bits++) {
            var packed = 0L;
            for (var segment = 0; segment < 4; segment++) {
                if ((bits & (1 << segment)) != 0) {
                    packed |= 2L << (segment * 4);
                }
            }
            CODED_SEGMENTS[bits] = packed;
        }
        for (var bits = 0; bits < 8; bits++) {
            var packed = 0L;
            for (var edge = 1; edge < 4; edge++) {
                if ((bits & (1 << (edge - 1))) != 0) {
                    packed |= 2L << (edge * 16);
                }
            }
            CODED_ROW[bits] = packed;
        }
    }

    /**
     * The luma {@code tC0} byte of each {@code indexA} and boundary strength below 4, indexed
     * {@code (indexA << 3) | strength}: {@code 0xFF} (negative, unfiltered) for strength 0,
     * {@link H264Tables#TC0} otherwise.
     */
    private static final int[] LUMA_TC = new int[52 << 3];

    /**
     * The chroma {@code tC0} byte of each {@code indexA} and boundary strength, laid out as
     * {@link #LUMA_TC} with {@link H264Dsp#CHROMA_STRONG} for strength 4.
     */
    private static final int[] CHROMA_TC = new int[52 << 3];

    static {
        for (var indexA = 0; indexA < 52; indexA++) {
            for (var strength = 0; strength < 5; strength++) {
                int tc;
                if (strength == 0) {
                    tc = 0xFF;
                } else if (strength == 4) {
                    tc = H264Dsp.CHROMA_STRONG;
                } else {
                    tc = H264Tables.TC0[indexA * 4 + strength] & 0xFF;
                }
                CHROMA_TC[(indexA << 3) | strength] = tc;
                LUMA_TC[(indexA << 3) | strength] = strength == 4 ? 0xFF : tc;
            }
        }
    }

    /**
     * The kernels of the picture being filtered, selected by {@link #startPicture}.
     */
    private H264Dsp dsp = H264Dsp.SCALAR;

    /**
     * The kernels of pictures deeper than 8 bits.
     */
    private final H264WideDsp wideDsp = H264WideDsp.INSTANCE;

    /**
     * The thresholds and packed {@code tC0} of up to two chroma edges, in the layout of
     * {@link H264Dsp#chromaEdgesVertical}.
     */
    private final int[] chromaParams = new int[8];

    /**
     * The working storage of the vertical edge filters.
     */
    private final byte[] edgeScratch = new byte[H264Dsp.EDGE_SCRATCH];

    /**
     * The packed strengths of the vertical edges of the macroblock being filtered.
     */
    private long verticalStrengths;

    /**
     * The packed strengths of the horizontal edges of the macroblock being filtered.
     */
    private long horizontalStrengths;

    /**
     * The first slice number of the picture being filtered; macroblocks with a lower number were
     * not decoded in this picture.
     */
    private int firstSlice;

    /**
     * Whether the picture being filtered is a field.
     */
    private boolean field;

    /**
     * The parity of the field being filtered: 0 top, 1 bottom; 0 for a frame.
     */
    private int parity;

    /**
     * The vertical motion vector difference, minus one, from which two blocks set strength 1:
     * 3 for frame macroblocks (4 quarter frame samples), 1 for field macroblocks (2 quarter field
     * samples).
     */
    private int verticalLimit = 3;

    /**
     * The packed strengths of the horizontal edges of an intra macroblock of the picture.
     */
    private long intraHorizontal = INTRA_STRENGTHS;

    /**
     * The packed strength of a horizontal macroblock edge below an intra macroblock.
     */
    private long intraTopEdge = 0x4444L;

    /**
     * Constructs a deblocker.
     */
    Deblocker() {
    }

    /**
     * Prepares filtering of a picture.
     *
     * @param firstSlice the number of the picture's first slice
     * @param dsp the kernels the picture is filtered with
     * @param structure the picture structure: {@link DecoderFrame#FRAME}, {@link DecoderFrame#TOP}
     * or {@link DecoderFrame#BOTTOM}
     */
    void startPicture(int firstSlice, H264Dsp dsp, int structure) {
        this.firstSlice = firstSlice;
        this.dsp = dsp;
        this.field = structure != DecoderFrame.FRAME;
        this.parity = structure == DecoderFrame.BOTTOM ? 1 : 0;
        this.verticalLimit = field ? 1 : 3;
        this.intraHorizontal = field ? FIELD_INTRA_HORIZONTAL : INTRA_STRENGTHS;
        this.intraTopEdge = field ? 0x3333L : 0x4444L;
    }

    /**
     * Filters one row of macroblocks of the picture.
     *
     * @param frame the picture being filtered
     * @param pic the per-macroblock state
     * @param row the macroblock row of the picture
     */
    void filterRow(DecoderFrame frame, PictureState pic, int row) {
        var width = frame.mbWidth;
        var start = field ? (2 * row + parity) * width : row * width;
        for (var x = 0; x < width; x++) {
            filterMacroblock(frame, pic, start + x, x, row);
        }
    }

    /**
     * Filters one macroblock.
     *
     * @param frame the picture being filtered
     * @param pic the per-macroblock state
     * @param mbAddr the macroblock index in the per-macroblock arrays
     * @param mbX the macroblock column
     * @param mbY the macroblock row of the picture
     */
    void filterMacroblock(DecoderFrame frame, PictureState pic, int mbAddr, int mbX, int mbY) {
        var params = pic.filter[mbAddr];
        var idc = params & 3;
        var slices = pic.sliceNum;
        var slice = slices[mbAddr];
        if (idc == 1 || slice < firstSlice) {
            return;
        }
        var mbStride = field ? 2 * frame.mbWidth : frame.mbWidth;
        var left = mbX > 0 && (idc == 0 ? slices[mbAddr - 1] >= firstSlice : slices[mbAddr - 1] == slice);
        var top = mbY > 0 && (idc == 0 ? slices[mbAddr - mbStride] >= firstSlice : slices[mbAddr - mbStride] == slice);
        var lumaStride = field ? 2 * frame.lumaStride : frame.lumaStride;
        var chromaStride = field ? 2 * frame.chromaStride : frame.chromaStride;
        var luma = frame.lumaOrigin + parity * frame.lumaStride + mbY * 16 * lumaStride + mbX * 16;
        var chroma = frame.chromaOrigin + parity * frame.chromaStride + mbY * frame.chromaMbHeight * chromaStride
                + mbX * 8;
        filterEdges(frame, pic, mbAddr, mbStride, left, top, luma, lumaStride, chroma, chromaStride);
    }

    /**
     * Filters one macroblock of an MBAFF frame whose left and top macroblock edges, when
     * filtered, each border a single macroblock of the same kind, frame or field, as itself: the
     * left edge borders the macroblock of the same position in the left pair of the same kind,
     * and the top edge the top macroblock of its own frame pair, the bottom macroblock of the
     * frame pair above, or the macroblock of the same parity of the field pair above. Every other
     * macroblock of an MBAFF frame needs the line-by-line filtering of {@link MbaffDeblocker}.
     *
     * @param frame the picture being filtered
     * @param pic the per-macroblock state
     * @param mbAddr the macroblock index in the per-macroblock arrays
     * @param pairX the column of its pair
     * @param pairY the row of its pair
     * @param bottom 1 for the bottom macroblock of the pair, 0 for the top one
     * @param fieldMb whether the macroblock is a field macroblock
     * @param left whether the left macroblock edge is filtered
     * @param top whether the top macroblock edge is filtered
     */
    void filterMbaffMacroblock(DecoderFrame frame, PictureState pic, int mbAddr, int pairX, int pairY, int bottom,
                               boolean fieldMb, boolean left, boolean top) {
        verticalLimit = fieldMb ? 1 : 3;
        intraHorizontal = fieldMb ? FIELD_INTRA_HORIZONTAL : INTRA_STRENGTHS;
        intraTopEdge = fieldMb ? 0x3333L : 0x4444L;
        var width = frame.mbWidth;
        var mbStride = fieldMb ? 2 * width : width;
        var lumaStride = fieldMb ? 2 * frame.lumaStride : frame.lumaStride;
        var chromaStride = fieldMb ? 2 * frame.chromaStride : frame.chromaStride;
        var lumaY = fieldMb ? 32 * pairY + bottom : 32 * pairY + 16 * bottom;
        var chromaRows = frame.chromaMbHeight;
        var chromaY = fieldMb ? 2 * chromaRows * pairY + bottom : 2 * chromaRows * pairY + chromaRows * bottom;
        var luma = frame.lumaOrigin + lumaY * frame.lumaStride + pairX * 16;
        var chroma = frame.chromaOrigin + chromaY * frame.chromaStride + pairX * 8;
        filterEdges(frame, pic, mbAddr, mbStride, left, top, luma, lumaStride, chroma, chromaStride);
    }

    /**
     * Filters the edges of one macroblock whose filtering is enabled.
     *
     * @param frame the picture being filtered
     * @param pic the per-macroblock state
     * @param mbAddr the macroblock index in the per-macroblock arrays
     * @param mbStride the index distance to the macroblock above it across the top edge
     * @param left whether the left macroblock edge is filtered
     * @param top whether the top macroblock edge is filtered
     * @param luma the index of its first luma sample
     * @param lumaStride the index distance between its luma rows
     * @param chroma the index of its first chroma sample
     * @param chromaStride the index distance between its chroma rows
     */
    private void filterEdges(DecoderFrame frame, PictureState pic, int mbAddr, int mbStride, boolean left, boolean top,
                             int luma, int lumaStride, int chroma, int chromaStride) {
        var params = pic.filter[mbAddr];
        var offsetA = ((params >> 2) & 0x3F) - 12;
        var offsetB = ((params >> 8) & 0x3F) - 12;
        var transform8x8 = (frame.mbType[mbAddr] & DecoderFrame.MB_T8X8) != 0;
        computeStrengths(frame, pic, mbAddr, mbStride, left, top, transform8x8);
        var vertical = verticalStrengths;
        var horizontal = horizontalStrengths;
        if (!left) {
            vertical &= ~0xFFFFL;
        }
        if (!top) {
            horizontal &= ~0xFFFFL;
        }
        var chroma422 = frame.chromaFormat == 2;
        var chromaHorizontal = chroma422 ? horizontal : horizontal & EVEN_EDGES;
        if (transform8x8) {
            vertical &= EVEN_EDGES;
            horizontal &= EVEN_EDGES;
        }
        if ((vertical | horizontal | chromaHorizontal) == 0) {
            return;
        }
        var qp = pic.qp[mbAddr];
        if (vertical != 0) {
            for (var edge = 0; edge < 4; edge++) {
                var segments = (int) (vertical >>> (edge * 16)) & 0xFFFF;
                if (segments != 0) {
                    var qpP = edge == 0 ? pic.qp[mbAddr - 1] : qp;
                    if (frame.wide) {
                        filterWideLuma(frame.luma16, luma + edge * 4, lumaStride, segments, (qpP + qp + 1) >> 1,
                                offsetA, offsetB, true, frame.bitDepthLuma - 8);
                    } else {
                        filterLuma(frame.luma, luma + edge * 4, lumaStride, segments, (qpP + qp + 1) >> 1,
                                offsetA, offsetB, true);
                    }
                }
            }
            if (frame.hasChroma) {
                if (chroma422) {
                    filterChromaVertical(frame, pic, mbAddr, chroma, chromaStride, spread((int) vertical, 0),
                            spread((int) (vertical >>> 32), 0), offsetA, offsetB);
                    filterChromaVertical(frame, pic, mbAddr, chroma + 8 * chromaStride, chromaStride,
                            spread((int) vertical, 1), spread((int) (vertical >>> 32), 1), offsetA, offsetB);
                } else {
                    filterChromaVertical(frame, pic, mbAddr, chroma, chromaStride, (int) vertical & 0xFFFF,
                            (int) (vertical >>> 32) & 0xFFFF, offsetA, offsetB);
                }
            }
        }
        if (horizontal != 0) {
            for (var edge = 0; edge < 4; edge++) {
                var segments = (int) (horizontal >>> (edge * 16)) & 0xFFFF;
                if (segments != 0) {
                    var qpP = edge == 0 ? pic.qp[mbAddr - mbStride] : qp;
                    if (frame.wide) {
                        filterWideLuma(frame.luma16, luma + edge * 4 * lumaStride, lumaStride, segments,
                                (qpP + qp + 1) >> 1, offsetA, offsetB, false, frame.bitDepthLuma - 8);
                    } else {
                        filterLuma(frame.luma, luma + edge * 4 * lumaStride, lumaStride, segments,
                                (qpP + qp + 1) >> 1, offsetA, offsetB, false);
                    }
                }
            }
        }
        if (frame.hasChroma && chromaHorizontal != 0) {
            var step = chroma422 ? 1 : 2;
            for (var edge = 0; edge < 4; edge += step) {
                var segments = (int) (chromaHorizontal >>> (edge * 16)) & 0xFFFF;
                var mbP = edge == 0 ? mbAddr - mbStride : mbAddr;
                if (segments != 0 && chromaEdge(pic, mbP, mbAddr, segments, offsetA, offsetB, 0)) {
                    var q0 = chroma + edge * 4 / step * chromaStride;
                    if (frame.wide) {
                        wideDsp.chromaEdgeHorizontal(frame.cb16, frame.cr16, q0, chromaStride, chromaParams, 0,
                                frame.bitDepthChroma - 8);
                    } else {
                        dsp.chromaEdgeHorizontal(frame.cb, frame.cr, q0, chromaStride, chromaParams, 0);
                    }
                }
            }
        }
    }

    /**
     * Filters the two vertical chroma edges (chroma columns 0 and 4) of 8 chroma rows of a
     * macroblock.
     *
     * @param frame the picture being filtered
     * @param pic the per-macroblock state
     * @param mbAddr the macroblock index
     * @param chroma the index of the first chroma sample of the rows
     * @param chromaStride the index distance between the chroma rows
     * @param left the packed strengths of the left edge's four 2-row segments
     * @param inner the packed strengths of the internal edge's four 2-row segments
     * @param offsetA the {@code FilterOffsetA}
     * @param offsetB the {@code FilterOffsetB}
     */
    private void filterChromaVertical(DecoderFrame frame, PictureState pic, int mbAddr, int chroma, int chromaStride,
                                      int left, int inner, int offsetA, int offsetB) {
        var edges = 0;
        if (left != 0 && chromaEdge(pic, mbAddr - 1, mbAddr, left, offsetA, offsetB, 0)) {
            edges |= 1;
        }
        if (inner != 0 && chromaEdge(pic, mbAddr, mbAddr, inner, offsetA, offsetB, 4)) {
            edges |= 2;
        }
        if (edges == 0) {
            return;
        }
        if (frame.wide) {
            wideDsp.chromaEdgesVertical(frame.cb16, frame.cr16, chroma, chromaStride, edges, chromaParams,
                    frame.bitDepthChroma - 8);
        } else {
            dsp.chromaEdgesVertical(frame.cb, frame.cr, chroma, chromaStride, edges, chromaParams, edgeScratch);
        }
    }

    /**
     * Returns the packed strengths of the four 2-row segments of one half of a 4:2:2 vertical
     * chroma edge: chroma row {@code k} takes the strength of luma row {@code k}, so each luma
     * segment covers two chroma segments.
     *
     * @param segments the packed strengths of the luma edge's four segments, in the low 16 bits
     * @param half 0 for chroma rows 0 to 7, 1 for rows 8 to 15
     * @return the packed strengths of the chroma segments
     */
    private static int spread(int segments, int half) {
        var first = (segments >> (8 * half)) & 0xF;
        var second = (segments >> (8 * half + 4)) & 0xF;
        return first | (first << 4) | (second << 8) | (second << 12);
    }

    /**
     * Filters one luma edge.
     *
     * @param plane the luma plane
     * @param q0 the index of the first {@code q0} sample
     * @param stride the row stride
     * @param segments the packed strengths of the edge's four segments
     * @param qpAv the average quantization parameter of the two macroblocks
     * @param offsetA the {@code FilterOffsetA}
     * @param offsetB the {@code FilterOffsetB}
     * @param vertical whether the edge is vertical
     */
    private void filterLuma(byte[] plane, int q0, int stride, int segments, int qpAv, int offsetA, int offsetB,
                            boolean vertical) {
        var indexA = Math.clamp(qpAv + offsetA, 0, 51);
        var alpha = H264Tables.ALPHA[indexA] & 0xFF;
        var beta = H264Tables.BETA[Math.clamp(qpAv + offsetB, 0, 51)] & 0xFF;
        if (alpha == 0 || beta == 0) {
            return;
        }
        if ((segments & 0xF) == 4) {
            if (vertical) {
                dsp.lumaEdgeVerticalStrong(plane, q0, stride, alpha, beta, edgeScratch);
            } else {
                dsp.lumaEdgeHorizontalStrong(plane, q0, stride, alpha, beta);
            }
            return;
        }
        var tc0 = packTc0(segments, indexA, false);
        if (vertical) {
            dsp.lumaEdgeVertical(plane, q0, stride, alpha, beta, tc0, edgeScratch);
        } else {
            dsp.lumaEdgeHorizontal(plane, q0, stride, alpha, beta, tc0);
        }
    }

    /**
     * Filters one luma edge of a picture deeper than 8 bits, with the thresholds and
     * {@code tC0} scaled to its bit depth.
     *
     * @param plane the 16-bit luma plane
     * @param q0 the index of the first {@code q0} sample
     * @param stride the row stride
     * @param segments the packed strengths of the edge's four segments
     * @param qpAv the average quantization parameter of the two macroblocks
     * @param offsetA the {@code FilterOffsetA}
     * @param offsetB the {@code FilterOffsetB}
     * @param vertical whether the edge is vertical
     * @param shift the luma bit depth minus 8
     */
    private void filterWideLuma(short[] plane, int q0, int stride, int segments, int qpAv, int offsetA,
                                int offsetB, boolean vertical, int shift) {
        var indexA = Math.clamp(qpAv + offsetA, 0, 51);
        var alpha = H264Tables.ALPHA[indexA] & 0xFF;
        var beta = H264Tables.BETA[Math.clamp(qpAv + offsetB, 0, 51)] & 0xFF;
        if (alpha == 0 || beta == 0) {
            return;
        }
        if ((segments & 0xF) == 4) {
            if (vertical) {
                wideDsp.lumaEdgeVerticalStrong(plane, q0, stride, alpha, beta, shift);
            } else {
                wideDsp.lumaEdgeHorizontalStrong(plane, q0, stride, alpha, beta, shift);
            }
            return;
        }
        var tc0 = packTc0(segments, indexA, false);
        if (vertical) {
            wideDsp.lumaEdgeVertical(plane, q0, stride, alpha, beta, tc0, shift);
        } else {
            wideDsp.lumaEdgeHorizontal(plane, q0, stride, alpha, beta, tc0, shift);
        }
    }

    /**
     * Fills the chroma parameters of one edge into {@link #chromaParams}.
     *
     * @param pic the per-macroblock state
     * @param mbP the macroblock on the {@code p} side of the edge
     * @param mbQ the macroblock on the {@code q} side of the edge
     * @param segments the packed strengths of the edge's four segments
     * @param offsetA the {@code FilterOffsetA}
     * @param offsetB the {@code FilterOffsetB}
     * @param at the index of the edge's first parameter
     * @return whether either plane has thresholds that can filter
     */
    private boolean chromaEdge(PictureState pic, int mbP, int mbQ, int segments, int offsetA, int offsetB, int at) {
        var params = chromaParams;
        var any = false;
        for (var plane = 0; plane < 2; plane++) {
            var qps = plane == 0 ? pic.qpCb : pic.qpCr;
            var qpAv = (qps[mbP] + qps[mbQ] + 1) >> 1;
            var indexA = Math.clamp(qpAv + offsetA, 0, 51);
            var alpha = H264Tables.ALPHA[indexA] & 0xFF;
            var beta = H264Tables.BETA[Math.clamp(qpAv + offsetB, 0, 51)] & 0xFF;
            if (alpha == 0 || beta == 0) {
                params[at + plane] = 0;
                params[at + 2 + plane] = -1;
            } else {
                params[at + plane] = alpha | (beta << 8);
                params[at + 2 + plane] = packTc0(segments, indexA, true);
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
        var table = chroma ? CHROMA_TC : LUMA_TC;
        var base = indexA << 3;
        return table[base | (segments & 0xF)] | (table[base | ((segments >> 4) & 0xF)] << 8)
                | (table[base | ((segments >> 8) & 0xF)] << 16) | (table[base | ((segments >> 12) & 0xF)] << 24);
    }

    /**
     * Computes the packed boundary strengths of every edge segment of a macroblock (ITU-T H.264
     * 8.7.2.1) into {@link #verticalStrengths} and {@link #horizontalStrengths}.
     *
     * <p>The strengths of a macroblock edge whose neighbor is not filtered are unspecified; the
     * caller masks them. The odd internal edges of a macroblock with the 8x8 transform carry the
     * strengths of their samples, which only 4:2:2 chroma edges use.
     *
     * @param frame the picture being filtered
     * @param pic the per-macroblock state
     * @param mbAddr the macroblock index
     * @param mbStride the index distance to the macroblock above in the picture
     * @param left whether the left macroblock edge is filtered
     * @param top whether the top macroblock edge is filtered
     * @param transform8x8 whether the macroblock uses the 8x8 transform
     */
    private void computeStrengths(DecoderFrame frame, PictureState pic, int mbAddr, int mbStride, boolean left,
                                  boolean top, boolean transform8x8) {
        var types = frame.mbType;
        var type = types[mbAddr];
        var limit = verticalLimit;
        if ((type & DecoderFrame.MB_INTRA) != 0) {
            verticalStrengths = INTRA_STRENGTHS;
            horizontalStrengths = intraHorizontal;
            return;
        }
        var nonZero = pic.nonZero[mbAddr];
        var uniform = (type & DecoderFrame.MB_UNIFORM) != 0;
        var uniform8 = (type & DecoderFrame.MB_UNIFORM8) != 0;
        var rows = nonZero | (nonZero >>> 1);
        var vertical = CODED_ROW[rows & 7] | (CODED_ROW[(rows >>> 4) & 7] << 4)
                | (CODED_ROW[(rows >>> 8) & 7] << 8) | (CODED_ROW[(rows >>> 12) & 7] << 12);
        var columns = nonZero | (nonZero >>> 4);
        var horizontal = (CODED_SEGMENTS[columns & 15] << 16) | (CODED_SEGMENTS[(columns >>> 4) & 15] << 32)
                | (CODED_SEGMENTS[(columns >>> 8) & 15] << 48);
        if (uniform8) {
            if (!uniform) {
                var upper = motion(limit, frame, mbAddr, 1, mbAddr, 2);
                var lower = motion(limit, frame, mbAddr, 9, mbAddr, 10);
                vertical = merge(vertical, ((long) (upper * 0x0011) | (lower * 0x1100L)) << 32);
                var leftHalf = motion(limit, frame, mbAddr, 4, mbAddr, 8);
                var rightHalf = motion(limit, frame, mbAddr, 6, mbAddr, 10);
                horizontal = merge(horizontal, ((long) (leftHalf * 0x0011) | (rightHalf * 0x1100L)) << 32);
            }
        } else {
            var motionVertical = 0L;
            var motionHorizontal = 0L;
            for (var edge = 1; edge < 4; edge++) {
                if (transform8x8 && (edge & 1) != 0) {
                    continue;
                }
                for (var i = 0; i < 4; i++) {
                    var shift = edge * 16 + i * 4;
                    if (((vertical >>> shift) & 0xF) == 0) {
                        motionVertical |= (long) motion(limit, frame, mbAddr, i * 4 + edge - 1, mbAddr, i * 4 + edge) << shift;
                    }
                    if (((horizontal >>> shift) & 0xF) == 0) {
                        motionHorizontal |= (long) motion(limit, frame, mbAddr, (edge - 1) * 4 + i, mbAddr, edge * 4 + i) << shift;
                    }
                }
            }
            vertical |= motionVertical;
            horizontal |= motionHorizontal;
        }
        if (left) {
            var a = mbAddr - 1;
            var typeA = types[a];
            if ((typeA & DecoderFrame.MB_INTRA) != 0) {
                vertical |= 0x4444L;
            } else {
                var edge = (nonZero & 0x1111) | ((pic.nonZero[a] >>> 3) & 0x1111);
                var coded = (edge | (edge >>> 3) | (edge >>> 6) | (edge >>> 9)) & 15;
                vertical |= edgeStrengths(limit, frame, a, mbAddr, coded, uniform8 && (typeA & DecoderFrame.MB_UNIFORM8) != 0,
                        uniform && (typeA & DecoderFrame.MB_UNIFORM) != 0, true);
            }
        }
        if (top) {
            var b = mbAddr - mbStride;
            var typeB = types[b];
            if ((typeB & DecoderFrame.MB_INTRA) != 0) {
                horizontal |= intraTopEdge;
            } else {
                var coded = (nonZero | (pic.nonZero[b] >>> 12)) & 15;
                horizontal |= edgeStrengths(limit, frame, b, mbAddr, coded, uniform8 && (typeB & DecoderFrame.MB_UNIFORM8) != 0,
                        uniform && (typeB & DecoderFrame.MB_UNIFORM) != 0, false);
            }
        }
        verticalStrengths = vertical;
        horizontalStrengths = horizontal;
    }

    /**
     * Returns the packed strengths of a macroblock edge between two inter-coded macroblocks.
     *
     * @param limit the vertical motion vector difference limit minus one, 3 or 1
     * @param frame the picture being filtered
     * @param mbP the neighboring macroblock
     * @param mbQ the current macroblock
     * @param coded the segments with non-zero coefficients on either side, bit {@code s} for
     * segment {@code s}
     * @param uniform8 whether both macroblocks have uniform motion in every 8x8 quarter
     * @param uniform whether both macroblocks have uniform motion over their whole area
     * @param vertical whether the edge is the left (vertical) macroblock edge
     * @return the packed strengths of edge 0
     */
    private static long edgeStrengths(int limit, DecoderFrame frame, int mbP, int mbQ, int coded, boolean uniform8,
                                      boolean uniform, boolean vertical) {
        var codedStrengths = CODED_SEGMENTS[coded];
        if (coded == 15) {
            return codedStrengths;
        }
        long motions;
        if (uniform8) {
            var first = (coded & 3) == 3 ? 0 : vertical ? motion(limit, frame, mbP, 3, mbQ, 0) : motion(limit, frame, mbP, 12, mbQ, 0);
            int second;
            if ((coded & 12) == 12) {
                second = 0;
            } else if (uniform) {
                second = (coded & 3) == 3
                        ? (vertical ? motion(limit, frame, mbP, 3, mbQ, 0) : motion(limit, frame, mbP, 12, mbQ, 0))
                        : first;
            } else {
                second = vertical ? motion(limit, frame, mbP, 11, mbQ, 8) : motion(limit, frame, mbP, 14, mbQ, 2);
            }
            motions = (first * 0x0011L) | (second * 0x1100L);
        } else {
            motions = 0;
            for (var segment = 0; segment < 4; segment++) {
                if ((coded & (1 << segment)) == 0) {
                    var motion = vertical ? motion(limit, frame, mbP, segment * 4 + 3, mbQ, segment * 4)
                            : motion(limit, frame, mbP, 12 + segment, mbQ, segment);
                    motions |= (long) motion << (segment * 4);
                }
            }
        }
        return merge(codedStrengths, motions);
    }

    /**
     * Combines coefficient-driven strengths (2 or 0 per segment) with motion-driven strengths
     * (1 or 0 per segment): a segment keeps 2 when coded and takes its motion strength otherwise.
     *
     * @param coded the packed coefficient-driven strengths
     * @param motions the packed motion-driven strengths
     * @return the packed combined strengths
     */
    private static long merge(long coded, long motions) {
        return coded | (motions & ~(coded >>> 1));
    }

    /**
     * Returns the boundary strength implied by the motion of two inter-coded 4x4 blocks
     * neither of which has non-zero coefficients: 1 when they use different reference pictures,
     * a different number of motion vectors, or motion vectors four or more quarter samples
     * apart, and 0 otherwise.
     *
     * @implNote This implementation loads both blocks' reference identities and motion vectors
     * of both lists up front, independently of each other, and orders each block's pair of
     * references (an unused list, {@code -1}, first) together with its vectors: two blocks then
     * use the same reference pictures exactly when their ordered pairs are equal, and the
     * vectors to compare are those in the same position, so only the number of distinct
     * references (none, one, two, or one picture referenced twice) selects the comparison.
     * @param limit the vertical motion vector difference limit minus one: 3 for frame
     * macroblocks, 1 for field macroblocks
     * @param frame the picture being filtered
     * @param mbP the macroblock of block {@code p}
     * @param blockP the raster 4x4 index of block {@code p}
     * @param mbQ the macroblock of block {@code q}
     * @param blockQ the raster 4x4 index of block {@code q}
     * @return 0 or 1
     */
    static int motion(int limit, DecoderFrame frame, int mbP, int blockP, int mbQ, int blockQ) {
        var partP = mbP * 4 + ((blockP >> 3) << 1) + ((blockP & 3) >> 1);
        var partQ = mbQ * 4 + ((blockQ >> 3) << 1) + ((blockQ & 3) >> 1);
        var indexP = mbP * 16 + blockP;
        var indexQ = mbQ * 16 + blockQ;
        var ids0 = frame.refId[0];
        var ids1 = frame.refId[1];
        var mvs0 = frame.mv[0];
        var mvs1 = frame.mv[1];
        var p0 = ids0[partP];
        var p1 = ids1[partP];
        var q0 = ids0[partQ];
        var q1 = ids1[partQ];
        var mvP0 = mvs0[indexP];
        var mvP1 = mvs1[indexP];
        var mvQ0 = mvs0[indexQ];
        var mvQ1 = mvs1[indexQ];
        var swapP = p0 > p1;
        var pLow = swapP ? p1 : p0;
        var pHigh = swapP ? p0 : p1;
        var mvPLow = swapP ? mvP1 : mvP0;
        var mvPHigh = swapP ? mvP0 : mvP1;
        var swapQ = q0 > q1;
        var qLow = swapQ ? q1 : q0;
        var qHigh = swapQ ? q0 : q1;
        var mvQLow = swapQ ? mvQ1 : mvQ0;
        var mvQHigh = swapQ ? mvQ0 : mvQ1;
        if (pLow != qLow || pHigh != qHigh) {
            return 1;
        }
        if (pLow < 0) {
            return pHigh < 0 ? 0 : differs(limit, mvPHigh, mvQHigh);
        }
        var direct = differs(limit, mvPLow, mvQLow) | differs(limit, mvPHigh, mvQHigh);
        if (pLow != pHigh) {
            return direct;
        }
        return direct & (differs(limit, mvPLow, mvQHigh) | differs(limit, mvPHigh, mvQLow));
    }

    /**
     * Returns whether two packed motion vectors differ by four or more quarter samples
     * horizontally, or by {@code limit + 1} or more vertically.
     *
     * @implNote This implementation tests each component difference {@code d} without a branch:
     * {@code d + l} lies in {@code 0..2l} exactly when {@code |d| <= l}, and the sign bit of
     * {@code (d + l) | (2l - (d + l))} is set exactly when it does not.
     * @param limit the vertical limit minus one: 3 for frame macroblocks, 1 for field macroblocks,
     * whose vectors count quarter field samples
     * @param a the first vector
     * @param b the second vector
     * @return 1 when the vectors differ enough to filter, 0 otherwise
     */
    private static int differs(int limit, int a, int b) {
        var dx = (short) a - (short) b + 3;
        var dy = (a >> 16) - (b >> 16) + limit;
        return (dx | (6 - dx) | dy | (2 * limit - dy)) >>> 31;
    }
}
