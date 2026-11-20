package com.github.auties00.smalt.video.mpeg4.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Reconstructs the samples of parsed macroblocks (ISO/IEC 14496-2 7.3): it forms each
 * macroblock's prediction from the reference pictures, frame by frame, field by field, by
 * overlapped block motion compensation or by global motion compensation, and adds the inverse
 * transformed residual.
 *
 * <p>Macroblocks are independent once parsed, so disjoint ranges of macroblock rows may be
 * reconstructed concurrently, each by its own instance (which owns its scratch buffers).
 */
public final class Reconstructor {
    /**
     * The table rounding a sum of sixteenths to half samples (Table 7-6).
     */
    private static final int[] SIXTEENTHS = {0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2};

    /**
     * The motion compensator.
     */
    private final MotionCompensator compensator = new MotionCompensator();

    /**
     * The backward luminance prediction of a bidirectional macroblock, 16x16.
     */
    private final byte[] backLuma = new byte[256];

    /**
     * The backward blue-difference prediction, 8x8.
     */
    private final byte[] backU = new byte[64];

    /**
     * The backward red-difference prediction, 8x8.
     */
    private final byte[] backV = new byte[64];

    /**
     * The vectors of overlapped motion compensation: current, above, below, left, right.
     */
    private final int[] obmcVectors = new int[10];

    /**
     * The inverse DCT work array.
     */
    private final int[] idctWork = new int[64];

    /**
     * The factor by which a macroblock row of this decoder outweighs the rows of the picture
     * operations that {@link ParallelBands#bands(int, long)} is tuned for.
     */
    private static final int WORK_PER_SAMPLE = 8;

    /**
     * The number of bands per participating thread, so that threads that finish early claim more.
     */
    private static final int BANDS_PER_THREAD = 2;

    /**
     * Returns the number of bands to reconstruct a picture in.
     *
     * @param threads the most threads to use
     * @param rows the number of macroblock rows
     * @param samples the number of luma samples of the picture
     * @return the band count, at least one
     */
    static int bandCount(int threads, int rows, long samples) {
        if (threads <= 1) {
            return 1;
        }
        var participants = Math.min(threads, ParallelBands.bands(rows, samples * WORK_PER_SAMPLE));
        return participants <= 1 ? 1 : Math.min(rows, participants * BANDS_PER_THREAD);
    }

    /**
     * Creates a reconstructor.
     */
    public Reconstructor() {
    }

    /**
     * Reconstructs a range of macroblock rows.
     *
     * @param blocks the parsed macroblocks
     * @param dsp the kernels of the picture
     * @param current the picture
     * @param forwardRef the forward reference, or {@code null}
     * @param backwardRef the backward reference, or {@code null}
     * @param gmc the global motion parameters of an S(GMC)-VOP, or {@code null}
     * @param layer the layer of the VOP
     * @param rounding the rounding control of the picture
     * @param firstRow the first macroblock row
     * @param endRow the macroblock row after the range
     */
    public void reconstruct(Macroblocks blocks, Mpeg4Dsp dsp, Frame current, Frame forwardRef, Frame backwardRef,
                            GlobalMotion gmc, VideoObjectLayer layer, int rounding, int firstRow, int endRow) {
        compensator.setDsp(dsp);
        var mbWidth = current.mbWidth;
        for (var my = firstRow; my < endRow; my++) {
            for (var mx = 0; mx < mbWidth; mx++) {
                reconstructMacroblock(blocks, dsp, current, forwardRef, backwardRef, gmc, layer, rounding,
                        my * mbWidth + mx, mx, my);
            }
        }
    }

    /**
     * Reconstructs one macroblock.
     *
     * @param blocks the parsed macroblocks
     * @param dsp the kernels
     * @param current the picture
     * @param forwardRef the forward reference, or {@code null}
     * @param backwardRef the backward reference, or {@code null}
     * @param gmc the global motion parameters, or {@code null}
     * @param layer the layer of the VOP
     * @param rounding the rounding control
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     */
    private void reconstructMacroblock(Macroblocks blocks, Mpeg4Dsp dsp, Frame current, Frame forwardRef,
                                       Frame backwardRef, GlobalMotion gmc, VideoObjectLayer layer, int rounding,
                                       int mb, int mx, int my) {
        var lumaStride = current.lumaStride;
        var chromaStride = current.chromaStride;
        var lumaOffset = current.lumaOrigin + my * 16 * lumaStride + mx * 16;
        var chromaOffset = current.chromaOrigin + my * 8 * chromaStride + mx * 8;
        var coefficients = blocks.coefficients;
        var base = mb * 384;
        var fieldDct = (blocks.flags[mb] & Macroblocks.FIELD_DCT) != 0;
        var kind = blocks.kind[mb];
        if (kind == Macroblocks.INTRA) {
            for (var block = 0; block < 4; block++) {
                dsp.idctPut(coefficients, base + block * 64, current.y, lumaBlockOffset(lumaOffset, lumaStride, block,
                        fieldDct), fieldDct ? 2 * lumaStride : lumaStride, idctWork);
            }
            dsp.idctPut(coefficients, base + 256, current.u, chromaOffset, chromaStride, idctWork);
            dsp.idctPut(coefficients, base + 320, current.v, chromaOffset, chromaStride, idctWork);
            return;
        }
        var quarter = layer.quarterSample;
        var direction = blocks.direction[mb];
        var x = mx * 16;
        var y = my * 16;
        switch (kind) {
            case Macroblocks.GMC -> {
                gmc.predictLuma(forwardRef.y, forwardRef.lumaOrigin, forwardRef.lumaStride, layer.width,
                        layer.height, current.y, lumaOffset, lumaStride, x, y);
                var chromaWidth = (layer.width + 1) >> 1;
                var chromaHeight = (layer.height + 1) >> 1;
                gmc.predictChroma(forwardRef.u, forwardRef.chromaOrigin, forwardRef.chromaStride, chromaWidth,
                        chromaHeight, current.u, chromaOffset, chromaStride, x >> 1, y >> 1);
                gmc.predictChroma(forwardRef.v, forwardRef.chromaOrigin, forwardRef.chromaStride, chromaWidth,
                        chromaHeight, current.v, chromaOffset, chromaStride, x >> 1, y >> 1);
            }
            case Macroblocks.LOST -> conceal(current, forwardRef, lumaOffset, chromaOffset, x, y);
            case Macroblocks.FIELD -> predictField(blocks, current, forwardRef, backwardRef, mb, x, y, lumaOffset,
                    chromaOffset, quarter, rounding, direction);
            default -> {
                var overlapped = (blocks.flags[mb] & Macroblocks.OVERLAPPED) != 0;
                var segments = blocks.segmented ? blocks.segment : null;
                if ((direction & Macroblocks.FORWARD) != 0) {
                    predictFrame(blocks.forward, current, forwardRef, mb, mx, my, x, y, current.y, lumaOffset,
                            lumaStride, current.u, current.v, chromaOffset, chromaStride, kind, quarter, rounding,
                            overlapped, segments);
                    if ((direction & Macroblocks.BACKWARD) != 0) {
                        predictFrame(blocks.backward, current, backwardRef, mb, mx, my, x, y, backLuma, 0, 16, backU,
                                backV, 0, 8, kind, quarter, rounding, false, null);
                        compensator.average(backLuma, 0, 16, current.y, lumaOffset, lumaStride, 16, 16);
                        compensator.average(backU, 0, 8, current.u, chromaOffset, chromaStride, 8, 8);
                        compensator.average(backV, 0, 8, current.v, chromaOffset, chromaStride, 8, 8);
                    }
                } else {
                    predictFrame(blocks.backward, current, backwardRef, mb, mx, my, x, y, current.y, lumaOffset,
                            lumaStride, current.u, current.v, chromaOffset, chromaStride, kind, quarter, rounding,
                            false, null);
                }
            }
        }
        var cbp = blocks.cbp[mb];
        if (cbp == 0) {
            return;
        }
        for (var block = 0; block < 4; block++) {
            if ((cbp & (1 << block)) != 0) {
                dsp.idctAdd(coefficients, base + block * 64, current.y, lumaBlockOffset(lumaOffset, lumaStride, block,
                        fieldDct), fieldDct ? 2 * lumaStride : lumaStride, idctWork);
            }
        }
        if ((cbp & 16) != 0) {
            dsp.idctAdd(coefficients, base + 256, current.u, chromaOffset, chromaStride, idctWork);
        }
        if ((cbp & 32) != 0) {
            dsp.idctAdd(coefficients, base + 320, current.v, chromaOffset, chromaStride, idctWork);
        }
    }

    /**
     * Returns the index of a luminance block's top-left sample, for frame or field DCT coding.
     *
     * @param lumaOffset the index of the macroblock's top-left sample
     * @param stride the luminance row stride
     * @param block the block index, 0 to 3
     * @param fieldDct whether the macroblock is field DCT coded
     * @return the index
     */
    private static int lumaBlockOffset(int lumaOffset, int stride, int block, boolean fieldDct) {
        var column = (block & 1) * 8;
        if (fieldDct) {
            return lumaOffset + (block >> 1) * stride + column;
        }
        return lumaOffset + (block >> 1) * 8 * stride + column;
    }

    /**
     * Predicts a frame-predicted macroblock from one reference: one 16x16 or four 8x8 luminance
     * blocks, possibly overlapped, and the chrominance blocks with the derived vector.
     *
     * @param vectors the block vectors of the direction
     * @param current the picture, for the side information of overlapped motion compensation
     * @param ref the reference
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     * @param x the macroblock's left luminance column
     * @param y the macroblock's top luminance row
     * @param lumaDst the luminance destination
     * @param lumaOffset the index of the destination's top-left luminance sample
     * @param lumaStride the luminance destination stride
     * @param uDst the blue-difference destination
     * @param vDst the red-difference destination
     * @param chromaOffset the index of the destination's top-left chrominance sample
     * @param chromaStride the chrominance destination stride
     * @param kind {@link Macroblocks#INTER16} or {@link Macroblocks#INTER8}
     * @param quarter whether vectors are in quarter samples
     * @param rounding the rounding control
     * @param overlapped whether overlapped motion compensation applies
     * @param segments the first macroblock of the slice of every macroblock, or {@code null} when
     *        the picture is not coded in slices
     */
    private void predictFrame(int[] vectors, Frame current, Frame ref, int mb, int mx, int my, int x, int y,
                              byte[] lumaDst, int lumaOffset, int lumaStride, byte[] uDst, byte[] vDst,
                              int chromaOffset, int chromaStride, byte kind, boolean quarter, int rounding,
                              boolean overlapped, int[] segments) {
        var at = mb * 8;
        int chromaX;
        int chromaY;
        if (overlapped) {
            for (var block = 0; block < 4; block++) {
                overlappedVectors(current, vectors, segments, mb, mx, my, block, obmcVectors);
                compensator.overlapped(ref, lumaDst, lumaOffset + (block >> 1) * 8 * lumaStride + (block & 1) * 8,
                        lumaStride, x + (block & 1) * 8, y + (block >> 1) * 8, obmcVectors, quarter, rounding);
            }
        } else if (kind == Macroblocks.INTER16) {
            compensator.luma(ref, lumaDst, lumaOffset, lumaStride, x, y, 16, vectors[at], vectors[at + 1], quarter,
                    rounding);
        } else {
            for (var block = 0; block < 4; block++) {
                compensator.luma(ref, lumaDst, lumaOffset + (block >> 1) * 8 * lumaStride + (block & 1) * 8,
                        lumaStride, x + (block & 1) * 8, y + (block >> 1) * 8, 8, vectors[at + 2 * block],
                        vectors[at + 2 * block + 1], quarter, rounding);
            }
        }
        if (kind == Macroblocks.INTER16) {
            chromaX = chromaOne(vectors[at], quarter);
            chromaY = chromaOne(vectors[at + 1], quarter);
        } else {
            chromaX = chromaFour(vectors, at, quarter);
            chromaY = chromaFour(vectors, at + 1, quarter);
        }
        compensator.chroma(ref, uDst, vDst, chromaOffset, chromaStride, x >> 1, y >> 1, chromaX, chromaY, rounding);
    }

    /**
     * Derives the chrominance vector component of a macroblock with one vector (Table 7-9).
     *
     * @param v the luminance vector component
     * @param quarter whether it is in quarter samples
     * @return the chrominance component in half samples
     */
    static int chromaOne(int v, boolean quarter) {
        if (quarter) {
            v = v / 2;
        }
        return (v >> 1) | (v & 1);
    }

    /**
     * Derives the chrominance vector component of a macroblock with four vectors: the sum of the
     * block vectors divided by 8 and rounded towards the nearest half sample (Table 7-6).
     *
     * @param vectors the block vectors
     * @param at the index of block 0's component
     * @param quarter whether the vectors are in quarter samples
     * @return the chrominance component in half samples
     */
    static int chromaFour(int[] vectors, int at, boolean quarter) {
        int sum;
        if (quarter) {
            sum = vectors[at] / 2 + vectors[at + 2] / 2 + vectors[at + 4] / 2 + vectors[at + 6] / 2;
        } else {
            sum = vectors[at] + vectors[at + 2] + vectors[at + 4] + vectors[at + 6];
        }
        if (sum >= 0) {
            return (sum >> 4) * 2 + SIXTEENTHS[sum & 15];
        }
        var negative = -sum;
        return -((negative >> 4) * 2 + SIXTEENTHS[negative & 15]);
    }

    /**
     * Gathers the vectors of overlapped motion compensation for one luminance block (7.6.6 and
     * ITU-T H.263 F.3): the block's own vector, and the remote vectors of the nearest blocks above
     * or below and left or right. A remote vector of a neighbor in another slice, intra coded or
     * absent (outside the picture) is replaced by the block's own vector, one of a skipped neighbor
     * is zero, and the vector below the bottom blocks of a macroblock is the block's own.
     *
     * @param current the picture with the side information of every macroblock
     * @param vectors the block vectors of the current picture
     * @param segments the first macroblock of the slice of every macroblock, or {@code null} when
     *        the picture is not coded in slices
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     * @param block the block index, 0 to 3
     * @param out the vectors as {@code x, y} pairs: current, above, below, left, right
     */
    static void overlappedVectors(Frame current, int[] vectors, int[] segments, int mb, int mx, int my, int block,
                                  int[] out) {
        var at = mb * 8 + 2 * block;
        var cx = vectors[at];
        var cy = vectors[at + 1];
        out[0] = cx;
        out[1] = cy;
        var row = block >> 1;
        var col = block & 1;
        if (row == 0) {
            remote(current, segments, mb, my > 0 ? mb - current.mbWidth : -1, block + 2, cx, cy, out, 2);
            out[4] = vectors[mb * 8 + 2 * (block + 2)];
            out[5] = vectors[mb * 8 + 2 * (block + 2) + 1];
        } else {
            out[2] = vectors[mb * 8 + 2 * (block - 2)];
            out[3] = vectors[mb * 8 + 2 * (block - 2) + 1];
            out[4] = cx;
            out[5] = cy;
        }
        if (col == 0) {
            remote(current, segments, mb, mx > 0 ? mb - 1 : -1, block + 1, cx, cy, out, 6);
            out[8] = vectors[mb * 8 + 2 * (block + 1)];
            out[9] = vectors[mb * 8 + 2 * (block + 1) + 1];
        } else {
            out[6] = vectors[mb * 8 + 2 * (block - 1)];
            out[7] = vectors[mb * 8 + 2 * (block - 1) + 1];
            remote(current, segments, mb, mx < current.mbWidth - 1 ? mb + 1 : -1, block - 1, cx, cy, out, 8);
        }
    }

    /**
     * Stores the remote vector of a neighboring macroblock's block for overlapped motion
     * compensation.
     *
     * @param current the picture
     * @param segments the first macroblock of the slice of every macroblock, or {@code null}
     * @param mb the current macroblock
     * @param neighbor the neighboring macroblock, or {@code -1} when absent
     * @param block the block of the neighbor
     * @param cx the current block's horizontal vector
     * @param cy the current block's vertical vector
     * @param out the vectors of overlapped motion compensation
     * @param slot the index of the remote vector in {@code out}
     */
    private static void remote(Frame current, int[] segments, int mb, int neighbor, int block, int cx, int cy,
                               int[] out, int slot) {
        if (neighbor < 0 || segments != null && segments[neighbor] != segments[mb]) {
            out[slot] = cx;
            out[slot + 1] = cy;
            return;
        }
        var type = current.mbType[neighbor];
        if (type == Frame.MB_INTRA || type == Frame.MB_GMC) {
            out[slot] = cx;
            out[slot + 1] = cy;
        } else if (type == Frame.MB_SKIPPED) {
            out[slot] = 0;
            out[slot + 1] = 0;
        } else {
            out[slot] = current.mv[neighbor * 8 + 2 * block];
            out[slot + 1] = current.mv[neighbor * 8 + 2 * block + 1];
        }
    }

    /**
     * Predicts a field-predicted macroblock (7.7.2), averaging the forward and backward field
     * predictions of bidirectional macroblocks.
     *
     * @param blocks the parsed macroblocks
     * @param current the picture
     * @param forwardRef the forward reference
     * @param backwardRef the backward reference
     * @param mb the macroblock number
     * @param x the macroblock's left luminance column
     * @param y the macroblock's top luminance row
     * @param lumaOffset the index of the macroblock's top-left luminance sample
     * @param chromaOffset the index of the macroblock's top-left chrominance sample
     * @param quarter whether vectors are in quarter samples
     * @param rounding the rounding control
     * @param direction the prediction directions
     */
    private void predictField(Macroblocks blocks, Frame current, Frame forwardRef, Frame backwardRef, int mb, int x,
                              int y, int lumaOffset, int chromaOffset, boolean quarter, int rounding,
                              byte direction) {
        var refs = blocks.fieldRefs[mb];
        var at = mb * 4;
        if ((direction & Macroblocks.FORWARD) != 0) {
            predictFields(forwardRef, blocks.forwardField, at, refs & 3, current.y, lumaOffset, current.lumaStride,
                    current.u, current.v, chromaOffset, current.chromaStride, x, y, quarter, rounding);
            if ((direction & Macroblocks.BACKWARD) != 0) {
                predictFields(backwardRef, blocks.backwardField, at, (refs >> 2) & 3, backLuma, 0, 16, backU, backV,
                        0, 8, x, y, quarter, rounding);
                compensator.average(backLuma, 0, 16, current.y, lumaOffset, current.lumaStride, 16, 16);
                compensator.average(backU, 0, 8, current.u, chromaOffset, current.chromaStride, 8, 8);
                compensator.average(backV, 0, 8, current.v, chromaOffset, current.chromaStride, 8, 8);
            }
        } else {
            predictFields(backwardRef, blocks.backwardField, at, (refs >> 2) & 3, current.y, lumaOffset,
                    current.lumaStride, current.u, current.v, chromaOffset, current.chromaStride, x, y, quarter,
                    rounding);
        }
    }

    /**
     * Predicts both fields of a macroblock from one reference
     * ({@code field_motion_compensate_one_reference} of 7.7.2.1).
     *
     * @param ref the reference
     * @param vectors the field vectors
     * @param at the index of the top field vector
     * @param refs the reference fields: bit 0 for the top field, bit 1 for the bottom
     * @param lumaDst the luminance destination
     * @param lumaOffset the index of its top-left sample
     * @param lumaStride its row stride
     * @param uDst the blue-difference destination
     * @param vDst the red-difference destination
     * @param chromaOffset the index of their top-left sample
     * @param chromaStride their row stride
     * @param x the macroblock's left luminance column
     * @param y the macroblock's top luminance row
     * @param quarter whether vectors are in quarter samples
     * @param rounding the rounding control
     */
    private void predictFields(Frame ref, int[] vectors, int at, int refs, byte[] lumaDst, int lumaOffset,
                               int lumaStride, byte[] uDst, byte[] vDst, int chromaOffset, int chromaStride, int x,
                               int y, boolean quarter, int rounding) {
        for (var field = 0; field < 2; field++) {
            var mvx = vectors[at + 2 * field];
            var mvy = vectors[at + 2 * field + 1];
            var refField = (refs >> field) & 1;
            compensator.lumaField(ref, lumaDst, lumaOffset, lumaStride, x, y, mvx, mvy, quarter, rounding, field,
                    refField);
            var cx = quarter ? mvx / 2 : mvx;
            var cy = quarter ? mvy / 2 : mvy;
            // The vertical component is rounded in field lines, as encoders do, rather than in frame
            // lines as the pseudo code of 7.7.2.1 reads.
            var fieldY = cy >> 1;
            var chromaY = ((fieldY >> 1) | (fieldY & 1)) << 1;
            compensator.chromaField(ref, uDst, vDst, chromaOffset, chromaStride, x >> 1, y >> 1,
                    (cx >> 1) | (cx & 1), chromaY, rounding, field, refField);
        }
    }

    /**
     * Conceals a lost macroblock by copying the co-located macroblock of the forward reference, or
     * by mid-gray without one.
     *
     * @param current the picture
     * @param forwardRef the forward reference, or {@code null}
     * @param lumaOffset the index of the macroblock's top-left luminance sample
     * @param chromaOffset the index of the macroblock's top-left chrominance sample
     * @param x the macroblock's left luminance column
     * @param y the macroblock's top luminance row
     */
    private void conceal(Frame current, Frame forwardRef, int lumaOffset, int chromaOffset, int x, int y) {
        if (forwardRef == null) {
            for (var row = 0; row < 16; row++) {
                java.util.Arrays.fill(current.y, lumaOffset + row * current.lumaStride,
                        lumaOffset + row * current.lumaStride + 16, (byte) 128);
            }
            for (var row = 0; row < 8; row++) {
                var at = chromaOffset + row * current.chromaStride;
                java.util.Arrays.fill(current.u, at, at + 8, (byte) 128);
                java.util.Arrays.fill(current.v, at, at + 8, (byte) 128);
            }
            return;
        }
        compensator.luma(forwardRef, current.y, lumaOffset, current.lumaStride, x, y, 16, 0, 0, false, 0);
        compensator.chroma(forwardRef, current.u, current.v, chromaOffset, current.chromaStride, x >> 1, y >> 1, 0, 0,
                0);
    }
}
