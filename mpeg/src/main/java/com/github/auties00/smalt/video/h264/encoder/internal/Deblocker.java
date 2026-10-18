package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.internal.H264Dsp;
import com.github.auties00.smalt.video.h264.internal.H264Tables;

/**
 * Applies the in-loop deblocking filter of clause 8.7 to a reconstructed picture.
 *
 * <p>The slice is coded with {@code disable_deblocking_filter_idc} 0 and zero filter offsets. Macroblocks are
 * processed in raster order; within a macroblock the vertical edges are filtered before the
 * horizontal ones, which the normative result depends on. Boundary strengths follow clause
 * 8.7.2.1 for frame macroblocks of P and I slices.
 *
 * <p>Several deblockers can filter one picture together in a wavefront of macroblock rows (see
 * {@link #filterRow}): a macroblock is filtered only once the row above has completed the
 * macroblock above and the one to its right, the last two macroblocks whose filtering touches
 * samples this macroblock reads. The result is identical to raster order. Instances hold
 * scratch state and are not thread-safe; each worker uses its own.
 */
final class Deblocker {
    /**
     * The {@code chroma_qp_index_offset}.
     */
    private final int chromaQpOffset;

    /**
     * The boundary strengths of the current macroblock's vertical edges, {@code edge * 4 + segment}.
     */
    private final int[] verticalStrength = new int[16];

    /**
     * The boundary strengths of the current macroblock's horizontal edges,
     * {@code edge * 4 + segment}.
     */
    private final int[] horizontalStrength = new int[16];

    /**
     * The thresholds and packed {@code tC0} of the chroma edges, in the layout of
     * {@link H264Dsp#chromaEdgesVertical}.
     */
    private final int[] chromaParams = new int[8];

    /**
     * The working storage of the edge kernels.
     */
    private final byte[] scratch = new byte[H264Dsp.EDGE_SCRATCH];

    /**
     * Constructs a deblocker.
     *
     * @param chromaQpOffset the {@code chroma_qp_index_offset}
     */
    Deblocker(int chromaQpOffset) {
        this.chromaQpOffset = chromaQpOffset;
    }

    /**
     * Filters one macroblock row in place as one step of a wavefront over the picture.
     *
     * <p>Before filtering macroblock {@code x} of the row, the worker waits until the row above
     * reports at least {@code x + 2} filtered macroblocks (or the whole row), the last two
     * macroblocks whose filtering touches samples this macroblock reads; it publishes its own
     * progress after every {@link Wavefront#GROUP} macroblocks and at the end of the row. The
     * result is identical to filtering the macroblocks in raster order. Every row's progress must
     * be {@code 0} before the wavefront starts, and each row must be filtered by exactly one
     * worker, after the row above was handed to one.
     *
     * @param frame the reconstruction
     * @param pi the coded parameters of every macroblock
     * @param mbY the macroblock row
     * @param progress the wavefront holding the number of filtered macroblocks of every row
     * @param base the index of row {@code 0}'s progress in {@code progress}
     * @throws java.util.concurrent.CancellationException if the wavefront was aborted
     */
    void filterRow(Frame frame, PictureInfo pi, int mbY, Wavefront progress, int base) {
        var mbWidth = pi.mbWidth;
        var above = 0;
        for (var mbX = 0; mbX < mbWidth; mbX++) {
            var needed = Math.min(mbX + 2, mbWidth);
            if (mbY > 0 && above < needed) {
                above = progress.awaitProgress(base + mbY - 1, needed);
            }
            filterMacroblock(frame, pi, mbX, mbY);
            if ((mbX + 1) % Wavefront.GROUP == 0 || mbX + 1 == mbWidth) {
                progress.publish(base + mbY, mbX + 1);
            }
        }
    }

    /**
     * Returns whether a macroblock moves as one block and codes no luma residual: a
     * {@code P_Skip} or a {@code P_L0_16x16} macroblock whose luma coded block pattern is empty.
     *
     * <p>Every internal edge of such a macroblock has boundary strength 0.
     *
     * @param pi the coded parameters of every macroblock
     * @param address the macroblock address
     * @return whether the macroblock is uniformly moved and uncoded in luma
     */
    private static boolean uniformlyStill(PictureInfo pi, int address) {
        var type = pi.type[address];
        return type == MbType.P_SKIP || type == MbType.P16x16 && (pi.cbp[address] & 15) == 0;
    }

    /**
     * Returns whether two macroblocks of which {@link #uniformlyStill} holds predict from the
     * same reference with vectors less than one sample apart in each component, which makes the
     * boundary strength of the edge between them 0.
     *
     * @param pi the coded parameters of every macroblock
     * @param a the first macroblock address
     * @param b the second macroblock address
     * @return whether the edge between them is not filtered
     */
    private static boolean sameMotion(PictureInfo pi, int a, int b) {
        return pi.refs[a * 4] == pi.refs[b * 4] && Math.abs(pi.mvX[a * 16] - pi.mvX[b * 16]) < 4
                && Math.abs(pi.mvY[a * 16] - pi.mvY[b * 16]) < 4;
    }

    /**
     * Filters the edges of one macroblock.
     *
     * <p>A macroblock whose edges all have boundary strength 0 is recognized without computing
     * the strengths when it and its left and top neighbors move as one block each, code no luma
     * residual and move alike (see {@link #uniformlyStill} and {@link #sameMotion}).
     *
     * @param frame the reconstruction
     * @param pi the coded parameters of every macroblock
     * @param mbX the macroblock column
     * @param mbY the macroblock row
     */
    private void filterMacroblock(Frame frame, PictureInfo pi, int mbX, int mbY) {
        var address = mbY * pi.mbWidth + mbX;
        if (uniformlyStill(pi, address)
                && (mbX == 0 || uniformlyStill(pi, address - 1) && sameMotion(pi, address, address - 1))
                && (mbY == 0 || uniformlyStill(pi, address - pi.mbWidth)
                && sameMotion(pi, address, address - pi.mbWidth))) {
            return;
        }
        var type = pi.type[address];
        var transform8x8 = pi.transform8x8[address];
        var intra = MbType.isIntra(type);
        var qp = type == MbType.I_PCM ? 0 : pi.qp[address];
        computeStrengths(pi, address, mbX, mbY, intra);
        var lumaStride = frame.lumaStride;
        var chromaStride = frame.chromaStride;
        var lumaOffset = frame.lumaOrigin + mbY * 16 * lumaStride + mbX * 16;
        var chromaOffset = frame.chromaOrigin + mbY * 8 * chromaStride + mbX * 8;
        var leftQp = mbX > 0 ? edgeQp(pi, address - 1) : 0;
        var topQp = mbY > 0 ? edgeQp(pi, address - pi.mbWidth) : 0;
        for (var edge = 0; edge < 4; edge++) {
            if ((edge == 0 && mbX == 0) || (transform8x8 && (edge & 1) != 0)) {
                continue;
            }
            var edgeQp = edge == 0 ? (qp + leftQp + 1) >> 1 : qp;
            filterLuma(frame.y, lumaOffset + edge * 4, lumaStride, true, edgeQp, verticalStrength, edge);
        }
        for (var edge = 0; edge < 4; edge++) {
            if ((edge == 0 && mbY == 0) || (transform8x8 && (edge & 1) != 0)) {
                continue;
            }
            var edgeQp = edge == 0 ? (qp + topQp + 1) >> 1 : qp;
            filterLuma(frame.y, lumaOffset + edge * 4 * lumaStride, lumaStride, false, edgeQp, horizontalStrength,
                    edge);
        }
        var chromaQp = Tables.chromaQp(qp, chromaQpOffset);
        var leftChromaQp = Tables.chromaQp(leftQp, chromaQpOffset);
        var topChromaQp = Tables.chromaQp(topQp, chromaQpOffset);
        var edges = 0;
        if (mbX > 0 && chromaEdge((chromaQp + leftChromaQp + 1) >> 1, verticalStrength, 0, 0)) {
            edges |= 1;
        }
        if (chromaEdge(chromaQp, verticalStrength, 2, 4)) {
            edges |= 2;
        }
        if (edges != 0) {
            H264Dsp.INSTANCE.chromaEdgesVertical(frame.u, frame.v, chromaOffset, chromaStride, edges, chromaParams,
                    scratch);
        }
        for (var edge = 0; edge < 4; edge += 2) {
            if ((edge != 0 || mbY > 0)
                    && chromaEdge(edge == 0 ? (chromaQp + topChromaQp + 1) >> 1 : chromaQp, horizontalStrength, edge, 0)) {
                H264Dsp.INSTANCE.chromaEdgeHorizontal(frame.u, frame.v, chromaOffset + edge * 2 * chromaStride,
                        chromaStride, chromaParams, 0);
            }
        }
    }

    /**
     * Returns the luma quantizer a macroblock contributes to an edge average.
     *
     * @param pi the coded parameters of every macroblock
     * @param address the macroblock address
     * @return {@code QPY}, or {@code 0} for {@code I_PCM}
     */
    private static int edgeQp(PictureInfo pi, int address) {
        return pi.type[address] == MbType.I_PCM ? 0 : pi.qp[address];
    }

    /**
     * Filters one luma edge.
     *
     * @param plane the luma samples
     * @param offset the index of the first {@code q0} sample
     * @param stride the row stride
     * @param vertical whether the edge is vertical rather than horizontal
     * @param qp the averaged quantizer {@code qPav}
     * @param strength the strengths of the macroblock's edges in this direction
     * @param edge the edge index
     */
    private void filterLuma(byte[] plane, int offset, int stride, boolean vertical, int qp, int[] strength,
                            int edge) {
        var base = edge * 4;
        var s0 = strength[base];
        if ((s0 | strength[base + 1] | strength[base + 2] | strength[base + 3]) == 0) {
            return;
        }
        var alpha = H264Tables.ALPHA[qp] & 0xFF;
        var beta = H264Tables.BETA[qp];
        if (alpha == 0 || beta == 0) {
            return;
        }
        var dsp = H264Dsp.INSTANCE;
        if (s0 == 4) {
            if (vertical) {
                dsp.lumaEdgeVerticalStrong(plane, offset, stride, alpha, beta, scratch);
            } else {
                dsp.lumaEdgeHorizontalStrong(plane, offset, stride, alpha, beta);
            }
            return;
        }
        var tc0 = packedTc0(qp, strength, base);
        if (vertical) {
            dsp.lumaEdgeVertical(plane, offset, stride, alpha, beta, tc0, scratch);
        } else {
            dsp.lumaEdgeHorizontal(plane, offset, stride, alpha, beta, tc0);
        }
    }

    /**
     * Prepares the parameters of one chroma edge of both chroma planes in {@link #chromaParams}.
     *
     * @param qp the averaged chroma quantizer
     * @param strength the strengths of the macroblock's luma edges in this direction
     * @param edge the luma edge index the chroma edge corresponds to
     * @param at the index of the edge's first parameter
     * @return whether the edge filters any sample
     */
    private boolean chromaEdge(int qp, int[] strength, int edge, int at) {
        var base = edge * 4;
        var s0 = strength[base];
        if ((s0 | strength[base + 1] | strength[base + 2] | strength[base + 3]) == 0) {
            return false;
        }
        var alpha = H264Tables.ALPHA[qp] & 0xFF;
        var beta = H264Tables.BETA[qp];
        if (alpha == 0 || beta == 0) {
            return false;
        }
        var thresholds = alpha | beta << 8;
        var tc0 = s0 == 4 ? H264Dsp.CHROMA_STRONG * 0x01010101 : packedTc0(qp, strength, base);
        chromaParams[at] = thresholds;
        chromaParams[at + 1] = thresholds;
        chromaParams[at + 2] = tc0;
        chromaParams[at + 3] = tc0;
        return true;
    }

    /**
     * Packs the {@code tC0} of the four segments of an edge whose strengths are below 4, one
     * signed byte per segment as the edge kernels of {@link H264Dsp} take them.
     *
     * @param qp the averaged quantizer of the edge
     * @param strength the strengths of the macroblock's edges in this direction
     * @param base the index of the edge's first segment in {@code strength}
     * @return the packed {@code tC0}, segment {@code i} in bits {@code 8i..8i+7}, -1 for a
     * segment of strength 0
     */
    private static int packedTc0(int qp, int[] strength, int base) {
        var packed = 0;
        for (var segment = 0; segment < 4; segment++) {
            var bs = strength[base + segment];
            var tc = bs == 0 ? -1 : H264Tables.TC0[qp * 4 + bs];
            packed |= (tc & 0xFF) << (8 * segment);
        }
        return packed;
    }

    /**
     * Computes the boundary strengths of every edge of a macroblock (clause 8.7.2.1).
     *
     * @param pi the coded parameters of every macroblock
     * @param address the macroblock address
     * @param mbX the macroblock column
     * @param mbY the macroblock row
     * @param intra whether the macroblock is intra
     */
    private void computeStrengths(PictureInfo pi, int address, int mbX, int mbY, boolean intra) {
        if (intra) {
            for (var i = 0; i < 16; i++) {
                var edgeStrength = i < 4 ? 4 : 3;
                verticalStrength[i] = edgeStrength;
                horizontalStrength[i] = edgeStrength;
            }
            return;
        }
        var base = address * 16;
        if (mbX > 0) {
            var left = address - 1;
            var leftIntra = MbType.isIntra(pi.type[left]);
            for (var s = 0; s < 4; s++) {
                verticalStrength[s] = leftIntra ? 4 : interStrength(pi, left * 16 + s * 4 + 3, base + s * 4);
            }
        }
        if (mbY > 0) {
            var top = address - pi.mbWidth;
            var topIntra = MbType.isIntra(pi.type[top]);
            for (var s = 0; s < 4; s++) {
                horizontalStrength[s] = topIntra ? 4 : interStrength(pi, top * 16 + 12 + s, base + s);
            }
        }
        var type = pi.type[address];
        if (type == MbType.P_SKIP || type == MbType.P16x16) {
            var coefficients = pi.totalCoeff;
            for (var edge = 1; edge < 4; edge++) {
                for (var s = 0; s < 4; s++) {
                    verticalStrength[edge * 4 + s] = (coefficients[base + s * 4 + edge - 1]
                            | coefficients[base + s * 4 + edge]) != 0 ? 2 : 0;
                    horizontalStrength[edge * 4 + s] = (coefficients[base + (edge - 1) * 4 + s]
                            | coefficients[base + edge * 4 + s]) != 0 ? 2 : 0;
                }
            }
            return;
        }
        for (var edge = 1; edge < 4; edge++) {
            for (var s = 0; s < 4; s++) {
                verticalStrength[edge * 4 + s] = interStrength(pi, base + s * 4 + edge - 1, base + s * 4 + edge);
                horizontalStrength[edge * 4 + s] = interStrength(pi, base + (edge - 1) * 4 + s, base + edge * 4 + s);
            }
        }
    }

    /**
     * Returns the strength of an edge between two 4x4 blocks of inter macroblocks.
     *
     * @param pi the coded parameters of every macroblock
     * @param p the picture-wide 4x4 index of the block containing {@code p0}
     * @param q the picture-wide 4x4 index of the block containing {@code q0}
     * @return 2 when either block has coefficients, 1 when the motion differs, 0 otherwise
     */
    private static int interStrength(PictureInfo pi, int p, int q) {
        if (pi.totalCoeff[p] != 0 || pi.totalCoeff[q] != 0) {
            return 2;
        }
        var pQuadrant = (p >> 4) * 4 + ((p & 15) >> 3) * 2 + ((p & 3) >> 1);
        var qQuadrant = (q >> 4) * 4 + ((q & 15) >> 3) * 2 + ((q & 3) >> 1);
        if (pi.bPicture) {
            var p0 = pi.refs[pQuadrant] >= 0;
            var p1 = pi.refs1[pQuadrant] >= 0;
            if (p0 != (pi.refs[qQuadrant] >= 0) || p1 != (pi.refs1[qQuadrant] >= 0)) {
                return 1;
            }
            if (p0 && (Math.abs(pi.mvX[p] - pi.mvX[q]) >= 4 || Math.abs(pi.mvY[p] - pi.mvY[q]) >= 4)) {
                return 1;
            }
            if (p1 && (Math.abs(pi.mvX1[p] - pi.mvX1[q]) >= 4 || Math.abs(pi.mvY1[p] - pi.mvY1[q]) >= 4)) {
                return 1;
            }
            return 0;
        }
        if (pi.refs[pQuadrant] != pi.refs[qQuadrant]) {
            return 1;
        }
        if (Math.abs(pi.mvX[p] - pi.mvX[q]) >= 4 || Math.abs(pi.mvY[p] - pi.mvY[q]) >= 4) {
            return 1;
        }
        return 0;
    }
}
