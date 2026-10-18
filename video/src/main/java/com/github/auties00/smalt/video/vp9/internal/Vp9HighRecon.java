/* Ports libvpx (vp9/common/vp9_reconintra.c, vp9/decoder/vp9_decodeframe.c). Copyright (c) 2010, The WebM Project
   authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Reconstructs the blocks of 10 and 12-bit frames for a {@link Vp9TileDecoder}: the edges of
 * intra prediction and the edge extension of motion compensation, on {@code short} samples.
 *
 * <p>An instance belongs to one tile decoder and holds its high bit depth scratch buffers.
 *
 * @implNote This implementation follows libvpx's {@code build_intra_predictors_high}
 * ({@code vp9/common/vp9_reconintra.c}) and the high bit depth branches of
 * {@code dec_build_inter_predictors} ({@code vp9/decoder/vp9_decodeframe.c}).
 */
final class Vp9HighRecon {
    /**
     * The width and height of the border extension buffer.
     */
    private static final int MC_BUFFER_SIZE = 160;

    /**
     * The index in {@link #edge} of the first above sample.
     */
    private static final int ABOVE = 80;

    /**
     * The neighbors of an intra predicted block: the left column at 0, the above row at
     * {@link #ABOVE}.
     */
    private final short[] edge = new short[256];

    /**
     * The reference block extended past the frame edge.
     */
    private final short[] mcBuffer = new short[MC_BUFFER_SIZE * MC_BUFFER_SIZE + 64];

    /**
     * The intermediate rows of two-dimensional motion compensation.
     */
    private final short[] convolveScratch = new short[Vp9Dsp.CONVOLVE_SCRATCH];

    /**
     * Creates the high bit depth reconstruction of a tile decoder.
     */
    Vp9HighRecon() {
    }

    /**
     * Predicts one high bit depth transform block from its decoded neighbors
     * ({@code build_intra_predictors_high}).
     *
     * @param td the tile decoder, whose current block holds the transform block
     * @param frame the frame being decoded
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param dst the index of the transform block's top-left sample
     * @param mode the intra mode
     * @param txSize the transform size
     * @param haveTop whether the row above is available
     * @param haveLeft whether the column to the left is available
     * @param haveRight whether the samples above and to the right belong to the same block
     * @param x the transform block's horizontal offset within the block, in samples
     * @param y the transform block's vertical offset within the block, in samples
     */
    void predictIntra(Vp9TileDecoder td, Vp9Frame frame, int plane, int dst, int mode, int txSize, boolean haveTop,
                      boolean haveLeft, boolean haveRight, int x, int y) {
        var ref = frame.planes16[plane];
        var stride = frame.strides[plane];
        var e = edge;
        var bs = 4 << txSize;
        var sx = plane == 0 ? 0 : frame.ssX;
        var sy = plane == 0 ? 0 : frame.ssY;
        var frameWidth = frame.planeWidths[plane];
        var frameHeight = frame.planeHeights[plane];
        var x0 = (-td.mbToLeftEdge >> (3 + sx)) + x;
        var y0 = (-td.mbToTopEdge >> (3 + sy)) + y;
        var base = 128 << (frame.bitDepth - 8);
        var extend = Vp9Constants.EXTEND_MODES[mode];
        short[] aboveArray = e;
        var aboveIndex = ABOVE;
        if ((extend & Vp9Constants.NEED_LEFT) != 0) {
            if (haveLeft) {
                var count = bs;
                if (td.mbToBottomEdge < 0 && y0 + bs > frameHeight) {
                    count = frameHeight - y0;
                }
                for (var i = 0; i < count; i++) {
                    e[i] = ref[dst + i * stride - 1];
                }
                if (count < bs) {
                    Arrays.fill(e, count, bs, ref[dst + (count - 1) * stride - 1]);
                }
            } else {
                Arrays.fill(e, 0, bs, (short) (base + 1));
            }
        }
        if ((extend & Vp9Constants.NEED_ABOVE) != 0) {
            if (haveTop) {
                var aboveRef = dst - stride;
                if (td.mbToRightEdge < 0) {
                    if (x0 + bs <= frameWidth) {
                        System.arraycopy(ref, aboveRef, e, ABOVE, bs);
                    } else if (x0 <= frameWidth) {
                        var r = frameWidth - x0;
                        System.arraycopy(ref, aboveRef, e, ABOVE, r);
                        Arrays.fill(e, ABOVE + r, ABOVE + bs, e[ABOVE + r - 1]);
                    }
                } else if (bs == 4 && haveRight && haveLeft) {
                    aboveArray = ref;
                    aboveIndex = aboveRef;
                } else {
                    System.arraycopy(ref, aboveRef, e, ABOVE, bs);
                }
                e[ABOVE - 1] = haveLeft ? ref[aboveRef - 1] : (short) (base + 1);
            } else {
                Arrays.fill(e, ABOVE, ABOVE + bs, (short) (base - 1));
                e[ABOVE - 1] = (short) (base - 1);
            }
        }
        if ((extend & Vp9Constants.NEED_ABOVERIGHT) != 0) {
            if (haveTop) {
                var aboveRef = dst - stride;
                if (td.mbToRightEdge < 0) {
                    if (x0 + 2 * bs <= frameWidth) {
                        if (haveRight && bs == 4) {
                            System.arraycopy(ref, aboveRef, e, ABOVE, 2 * bs);
                        } else {
                            System.arraycopy(ref, aboveRef, e, ABOVE, bs);
                            Arrays.fill(e, ABOVE + bs, ABOVE + 2 * bs, e[ABOVE + bs - 1]);
                        }
                    } else if (x0 + bs <= frameWidth) {
                        var r = frameWidth - x0;
                        if (haveRight && bs == 4) {
                            System.arraycopy(ref, aboveRef, e, ABOVE, r);
                            Arrays.fill(e, ABOVE + r, ABOVE + 2 * bs, e[ABOVE + r - 1]);
                        } else {
                            System.arraycopy(ref, aboveRef, e, ABOVE, bs);
                            Arrays.fill(e, ABOVE + bs, ABOVE + 2 * bs, e[ABOVE + bs - 1]);
                        }
                    } else if (x0 <= frameWidth) {
                        var r = frameWidth - x0;
                        System.arraycopy(ref, aboveRef, e, ABOVE, r);
                        Arrays.fill(e, ABOVE + r, ABOVE + 2 * bs, e[ABOVE + r - 1]);
                    }
                    e[ABOVE - 1] = haveLeft ? ref[aboveRef - 1] : (short) (base + 1);
                } else if (bs == 4 && haveRight && haveLeft) {
                    aboveArray = ref;
                    aboveIndex = aboveRef;
                } else {
                    System.arraycopy(ref, aboveRef, e, ABOVE, bs);
                    if (bs == 4 && haveRight) {
                        System.arraycopy(ref, aboveRef + bs, e, ABOVE + bs, bs);
                    } else {
                        Arrays.fill(e, ABOVE + bs, ABOVE + 2 * bs, e[ABOVE + bs - 1]);
                    }
                    e[ABOVE - 1] = haveLeft ? ref[aboveRef - 1] : (short) (base + 1);
                }
            } else {
                Arrays.fill(e, ABOVE, ABOVE + 2 * bs, (short) (base - 1));
                e[ABOVE - 1] = (short) (base - 1);
            }
        }
        var dcEdges = (haveTop ? 1 : 0) | (haveLeft ? 2 : 0);
        Vp9HighDsp.intraPredict(mode, txSize, dcEdges, ref, dst, stride, aboveArray, aboveIndex, e, 0, frame.bitDepth);
    }

    /**
     * Predicts one high bit depth block from a reference, extending the reference edge when the
     * block reads outside it (the high bit depth tail of {@code dec_build_inter_predictors}).
     *
     * @param td the tile decoder
     * @param src the reference plane
     * @param srcStride the reference row stride
     * @param frameWidth the reference plane's visible width
     * @param frameHeight the reference plane's visible height
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the destination row stride
     * @param x0 the reference column of the block's first sample
     * @param y0 the reference row of the block's first sample
     * @param x016 the reference column of the block's first sample, in sixteenths
     * @param y016 the reference row of the block's first sample, in sixteenths
     * @param subX the horizontal phase
     * @param subY the vertical phase
     * @param xs the horizontal step in sixteenths
     * @param ys the vertical step in sixteenths
     * @param w the block width
     * @param h the block height
     * @param checkBorder whether the block may read outside the reference: it moved, the
     * reference is scaled, or the frame size is not a multiple of 8
     * @param kernelBase the index of the block's filter in {@link Vp9Constants#FILTER_KERNELS}
     * @param average whether to average into the destination
     * @param bitDepth the bit depth
     * @throws MediaException.Malformed if the area to extend is too large
     */
    void predictInter(Vp9TileDecoder td, short[] src, int srcStride, int frameWidth, int frameHeight, short[] dst,
                      int dstOff, int dstStride, int x0, int y0, int x016, int y016, int subX, int subY, int xs,
                      int ys, int w, int h, boolean checkBorder, int kernelBase, boolean average, int bitDepth) {
        var srcOffset = y0 * srcStride + x0;
        if (checkBorder) {
            var y1 = ((y016 + (h - 1) * ys) >> 4) + 1;
            var x1 = ((x016 + (w - 1) * xs) >> 4) + 1;
            var xPad = 0;
            var yPad = 0;
            if (subX != 0 || xs != 16) {
                x0 -= 3;
                x1 += 4;
                xPad = 1;
            }
            if (subY != 0 || ys != 16) {
                y0 -= 3;
                y1 += 4;
                yPad = 1;
            }
            if (x0 < 0 || x0 > frameWidth - 1 || x1 < 0 || x1 > frameWidth - 1 || y0 < 0 || y0 > frameHeight - 1
                    || y1 < 0 || y1 > frameHeight - 1) {
                var bW = x1 - x0 + 1;
                var bH = y1 - y0 + 1;
                if (bW > MC_BUFFER_SIZE || bH > MC_BUFFER_SIZE) {
                    throw new MediaException.Malformed("vp9 motion compensation area too large");
                }
                buildMcBorder(src, srcStride, mcBuffer, bW, x0, y0, bW, bH, frameWidth, frameHeight);
                var borderOffset = yPad * 3 * bW + xPad * 3;
                Vp9HighDsp.convolve(mcBuffer, borderOffset, bW, dst, dstOff, dstStride, Vp9Constants.FILTER_KERNELS,
                        kernelBase, subX, xs, subY, ys, w, h, average, bitDepth, convolveScratch);
                return;
            }
        }
        Vp9HighDsp.convolve(src, srcOffset, srcStride, dst, dstOff, dstStride, Vp9Constants.FILTER_KERNELS,
                kernelBase, subX, xs, subY, ys, w, h, average, bitDepth, convolveScratch);
    }

    /**
     * Copies a reference area into a buffer, replicating the frame's edge samples where the area
     * lies outside the frame ({@code high_build_mc_border}).
     *
     * @param src the reference plane
     * @param srcStride the reference row stride
     * @param dst the buffer
     * @param dstStride the buffer row stride
     * @param x the area's left column, possibly negative
     * @param y the area's top row, possibly negative
     * @param bW the area width
     * @param bH the area height
     * @param w the frame width
     * @param h the frame height
     */
    private static void buildMcBorder(short[] src, int srcStride, short[] dst, int dstStride, int x, int y, int bW,
                                      int bH, int w, int h) {
        var refRow = y >= h ? (h - 1) * srcStride : (y > 0 ? y * srcStride : 0);
        var out = 0;
        for (var row = 0; row < bH; row++) {
            var left = x < 0 ? -x : 0;
            if (left > bW) {
                left = bW;
            }
            var right = x + bW > w ? x + bW - w : 0;
            if (right > bW) {
                right = bW;
            }
            var copy = bW - left - right;
            if (left > 0) {
                Arrays.fill(dst, out, out + left, src[refRow]);
            }
            if (copy > 0) {
                System.arraycopy(src, refRow + x + left, dst, out + left, copy);
            }
            if (right > 0) {
                Arrays.fill(dst, out + left + copy, out + bW, src[refRow + w - 1]);
            }
            out += dstStride;
            y++;
            if (y > 0 && y < h) {
                refRow += srcStride;
            }
        }
    }
}
