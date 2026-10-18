/* Ports libvpx 1.17.0 (vp9/common/vp9_reconintra.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

/**
 * Predicts 8-bit intra transform blocks from their reconstructed neighbors
 * ({@code build_intra_predictors}), for the decoder and the encoder.
 *
 * <p>The edge samples are gathered with the decoder's availability rules and edge replication and
 * predicted with the {@link Vp9Dsp#INSTANCE} kernels, so the encoder's prediction equals the
 * decoder's. Instances are not thread-safe: they hold the gathered edge.
 */
final class Vp9IntraPredictor {
    /**
     * The index of the above row in {@link #edge}; the top-left sample sits just before it.
     */
    private static final int ABOVE = 80;

    private final Vp9Dsp dsp = Vp9Dsp.INSTANCE;

    /**
     * The gathered edge samples: the left column from index 0, the above row from
     * {@link #ABOVE}.
     */
    private final byte[] edge = new byte[256];

    /**
     * Predicts a transform block ({@code vp9_predict_intra_block}).
     *
     * <p>The decoder predicts in place, passing the same plane, index and stride as {@code ref}
     * and {@code dst}.
     *
     * @param ref the plane holding the neighbors
     * @param refStride the row stride of {@code ref}
     * @param dst the plane receiving the prediction
     * @param dstOff the index of the block's top-left sample in {@code dst}
     * @param dstStride the row stride of {@code dst}
     * @param refOff the index of the block's top-left sample in {@code ref}
     * @param mode the intra mode, from {@code DC_PRED} to {@code TM_PRED}
     * @param txSize the transform size, from {@code TX_4X4} to {@code TX_32X32}
     * @param haveTop whether the above row is available
     * @param haveLeft whether the left column is available
     * @param haveRight whether the samples above and to the right are inside the block
     * @param x0 the block's column in the plane
     * @param y0 the block's row in the plane
     * @param frameWidth the 8-aligned plane width
     * @param frameHeight the 8-aligned plane height
     * @param rightEdge whether the coding block extends past the right edge of the frame
     * ({@code mb_to_right_edge < 0})
     * @param bottomEdge whether the coding block extends past the bottom edge
     * ({@code mb_to_bottom_edge < 0})
     */
    void predict(byte[] ref, int refStride, byte[] dst, int dstOff, int dstStride, int refOff, int mode, int txSize,
                 boolean haveTop, boolean haveLeft, boolean haveRight, int x0, int y0, int frameWidth, int frameHeight,
                 boolean rightEdge, boolean bottomEdge) {
        var e = edge;
        var bs = 4 << txSize;
        var extend = Vp9Constants.EXTEND_MODES[mode];
        byte[] aboveArray = e;
        var aboveIndex = ABOVE;
        if ((extend & Vp9Constants.NEED_LEFT) != 0) {
            if (haveLeft) {
                var count = bs;
                if (bottomEdge && y0 + bs > frameHeight) {
                    count = frameHeight - y0;
                }
                for (var i = 0; i < count; i++) {
                    e[i] = ref[refOff + i * refStride - 1];
                }
                if (count < bs) {
                    Arrays.fill(e, count, bs, ref[refOff + (count - 1) * refStride - 1]);
                }
            } else {
                Arrays.fill(e, 0, bs, (byte) 129);
            }
        }
        if ((extend & Vp9Constants.NEED_ABOVE) != 0) {
            if (haveTop) {
                var aboveRef = refOff - refStride;
                if (rightEdge) {
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
                e[ABOVE - 1] = haveLeft ? ref[aboveRef - 1] : (byte) 129;
            } else {
                Arrays.fill(e, ABOVE, ABOVE + bs, (byte) 127);
                e[ABOVE - 1] = 127;
            }
        }
        if ((extend & Vp9Constants.NEED_ABOVERIGHT) != 0) {
            if (haveTop) {
                var aboveRef = refOff - refStride;
                if (rightEdge) {
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
                }
                e[ABOVE - 1] = haveLeft ? ref[aboveRef - 1] : (byte) 129;
            } else {
                Arrays.fill(e, ABOVE, ABOVE + 2 * bs, (byte) 127);
                e[ABOVE - 1] = 127;
            }
        }
        var dcEdges = (haveTop ? 1 : 0) | (haveLeft ? 2 : 0);
        dsp.intraPredict(mode, txSize, dcEdges, dst, dstOff, dstStride, aboveArray, aboveIndex, e, 0);
    }
}
