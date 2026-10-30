/* Ports VVdeC 3.3.0 (source/Lib/CommonLib/InterpolationFilter.cpp, source/Lib/CommonLib/Buffer.cpp,
 * source/Lib/CommonLib/InterPrediction.cpp: the bi-directional optical flow, source/Lib/CommonLib/TrQuant_EMT.cpp).
 * Copyright (c) 2018-2026, Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e.V. & The VVdeC
 * Authors; Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

/**
 * The sample loops of the VVC decoder that have Vector API twins in {@link VectorVvcDsp}: the separable
 * interpolation filters (H.266 clause 8.5.6.3), the default weighted sample prediction (8.5.6.6.2), the gradients
 * and refined samples of the bi-directional optical flow (8.5.6.5) and the two passes of the inverse transform
 * (8.7.4.2).
 *
 * <p>Instances hold no state and are thread-safe.
 */
class ScalarVvcDsp {
    /**
     * The offset the first interpolation stage subtracts, {@code 1 << 13}.
     */
    static final int OFFS = 1 << 13;

    /**
     * The smallest 16-bit coefficient.
     */
    static final int COEFF_MIN = -(1 << 15);

    /**
     * The largest 16-bit coefficient.
     */
    static final int COEFF_MAX = (1 << 15) - 1;

    /**
     * Constructs the kernels.
     */
    ScalarVvcDsp() {
    }

    /**
     * Applies a horizontal filter producing {@code (sum >> shift) - (1 << 13)} for 8 and 4 taps and
     * {@code sum >> shift} for 2 taps, the first stage of 8.5.6.3.
     *
     * @param src the input samples
     * @param srcOff the index of the first input sample
     * @param ss the row stride of the input
     * @param dst receives the filtered samples
     * @param dstOff the index of the first output sample
     * @param ds the row stride of the output
     * @param w the block width
     * @param h the block height
     * @param c the filter coefficients
     * @param taps the number of filter taps: 8, 4 or 2
     * @param shift the output shift
     */
    void filterRows(int[] src, int srcOff, int ss, int[] dst, int dstOff, int ds, int w, int h, int[] c, int taps,
                    int shift) {
        for (var j = 0; j < h; j++) {
            var s = srcOff + j * ss;
            var d = dstOff + j * ds;
            if (taps == 8) {
                for (var i = 0; i < w; i++) {
                    var p = s + i;
                    var sum = src[p] * c[0] + src[p + 1] * c[1] + src[p + 2] * c[2] + src[p + 3] * c[3]
                            + src[p + 4] * c[4] + src[p + 5] * c[5] + src[p + 6] * c[6] + src[p + 7] * c[7];
                    dst[d + i] = (sum >> shift) - OFFS;
                }
            } else if (taps == 4) {
                for (var i = 0; i < w; i++) {
                    var p = s + i;
                    var sum = src[p] * c[0] + src[p + 1] * c[1] + src[p + 2] * c[2] + src[p + 3] * c[3];
                    dst[d + i] = (sum >> shift) - OFFS;
                }
            } else {
                for (var i = 0; i < w; i++) {
                    var p = s + i;
                    dst[d + i] = (src[p] * c[0] + src[p + 1] * c[1]) >> shift;
                }
            }
        }
    }

    /**
     * Applies a vertical filter producing {@code (sum >> shift) + bias} for 8 and 4 taps and the rounded
     * {@code sum >> shift} for 2 taps; the bias folds the intermediate offset of a first stage.
     *
     * @param src the input samples
     * @param srcOff the index of the first input sample
     * @param ss the row stride of the input
     * @param dst receives the filtered samples
     * @param dstOff the index of the first output sample
     * @param ds the row stride of the output
     * @param w the block width
     * @param h the block height
     * @param c the filter coefficients
     * @param taps the number of filter taps: 8, 4 or 2
     * @param shift the output shift
     * @param bias the value added after the shift
     */
    void filterCols(int[] src, int srcOff, int ss, int[] dst, int dstOff, int ds, int w, int h, int[] c, int taps,
                    int shift, int bias) {
        for (var j = 0; j < h; j++) {
            var s = srcOff + j * ss;
            var d = dstOff + j * ds;
            if (taps == 8) {
                for (var i = 0; i < w; i++) {
                    var p = s + i;
                    var sum = src[p] * c[0] + src[p + ss] * c[1] + src[p + 2 * ss] * c[2] + src[p + 3 * ss] * c[3]
                            + src[p + 4 * ss] * c[4] + src[p + 5 * ss] * c[5] + src[p + 6 * ss] * c[6]
                            + src[p + 7 * ss] * c[7];
                    dst[d + i] = (sum >> shift) + bias;
                }
            } else if (taps == 4) {
                for (var i = 0; i < w; i++) {
                    var p = s + i;
                    var sum = src[p] * c[0] + src[p + ss] * c[1] + src[p + 2 * ss] * c[2] + src[p + 3 * ss] * c[3];
                    dst[d + i] = (sum >> shift) + bias;
                }
            } else {
                for (var i = 0; i < w; i++) {
                    var p = s + i;
                    dst[d + i] = (src[p] * c[0] + src[p + ss] * c[1] + (1 << (shift - 1))) >> shift;
                }
            }
        }
    }

    /**
     * Writes the clipped weighted sum of two 14-bit predictions,
     * {@code clip((p0 * w0 + p1 * w1 + offset) >> shift)}: the default weighted sample prediction of a
     * bi-predicted block (8.5.6.6.2) with {@code w0 = w1 = 1}, or with the weights of a CU-level weight index.
     *
     * @param p0 the list 0 prediction
     * @param p1 the list 1 prediction
     * @param off the index of the block in the predictions
     * @param bs the row stride of the predictions
     * @param plane the output plane
     * @param d0 the index of the block in the output plane
     * @param stride the row stride of the output plane
     * @param w the block width
     * @param h the block height
     * @param w0 the weight of the list 0 prediction
     * @param w1 the weight of the list 1 prediction
     * @param shift the right shift
     * @param offset the rounding offset, which folds the intermediate offsets of the predictions
     * @param maxVal the largest sample value
     */
    void average(int[] p0, int[] p1, int off, int bs, short[] plane, int d0, int stride, int w, int h, int w0, int w1,
                 int shift, int offset, int maxVal) {
        for (var j = 0; j < h; j++) {
            var d = d0 + j * stride;
            var s = off + j * bs;
            for (var i = 0; i < w; i++) {
                plane[d + i] = (short) Math.clamp((p0[s + i] * w0 + p1[s + i] * w1 + offset) >> shift, 0, maxVal);
            }
        }
    }

    /**
     * Writes the clipped samples of one 14-bit prediction, {@code clip((p + offset) >> shift)}: the default weighted
     * sample prediction of a uni-predicted block (8.5.6.6.2).
     *
     * @param p the prediction
     * @param off the index of the block in the prediction
     * @param bs the row stride of the prediction
     * @param plane the output plane
     * @param d0 the index of the block in the output plane
     * @param stride the row stride of the output plane
     * @param w the block width
     * @param h the block height
     * @param shift the right shift
     * @param offset the rounding offset, which folds the intermediate offset of the prediction
     * @param maxVal the largest sample value
     */
    void single(int[] p, int off, int bs, short[] plane, int d0, int stride, int w, int h, int shift, int offset,
                int maxVal) {
        for (var j = 0; j < h; j++) {
            var d = d0 + j * stride;
            var s = off + j * bs;
            for (var i = 0; i < w; i++) {
                plane[d + i] = (short) Math.clamp((p[s + i] + offset) >> shift, 0, maxVal);
            }
        }
    }

    /**
     * Computes the gradients of a bi-directional optical flow prediction (equations 8-857 to 8-860) inside its
     * one-sample border: {@code gx = (src[p + 1] >> 6) - (src[p - 1] >> 6)} and
     * {@code gy = (src[p + es] >> 6) - (src[p - es] >> 6)} at every position of rows and columns 1 to {@code h}
     * and {@code w}.
     *
     * @param src the extended prediction, {@code es} samples per row
     * @param gx receives the horizontal gradients
     * @param gy receives the vertical gradients
     * @param es the row stride, {@code w + 2}
     * @param w the block width
     * @param h the block height
     */
    void gradients(int[] src, int[] gx, int[] gy, int es, int w, int h) {
        for (var j = 1; j <= h; j++) {
            for (var i = 1; i <= w; i++) {
                var p = j * es + i;
                gy[p] = (src[p + es] >> 6) - (src[p - es] >> 6);
                gx[p] = (src[p + 1] >> 6) - (src[p - 1] >> 6);
            }
        }
    }

    /**
     * Writes the refined samples of a 4x4 block of a bi-directional optical flow prediction (equation 8-870):
     * {@code clip((short) ((s0 + s1 + tx * (gx0 - gx1) + ty * (gy0 - gy1) + offset) >> shift))}, the 16-bit
     * truncation before the clipping included.
     *
     * @param s0 the extended list 0 prediction
     * @param s1 the extended list 1 prediction
     * @param gx0 the horizontal gradients of list 0
     * @param gx1 the horizontal gradients of list 1
     * @param gy0 the vertical gradients of list 0
     * @param gy1 the vertical gradients of list 1
     * @param p the index of the block's top-left sample in the extended arrays
     * @param es the row stride of the extended arrays
     * @param plane the output plane
     * @param d0 the index of the block in the output plane
     * @param stride the row stride of the output plane
     * @param tx the horizontal motion refinement
     * @param ty the vertical motion refinement
     * @param shift the right shift
     * @param offset the rounding offset
     * @param maxVal the largest sample value
     */
    void bdofSamples(int[] s0, int[] s1, int[] gx0, int[] gx1, int[] gy0, int[] gy1, int p, int es, short[] plane,
                     int d0, int stride, int tx, int ty, int shift, int offset, int maxVal) {
        for (var j = 0; j < 4; j++) {
            var d = d0 + j * stride;
            var q = p + j * es;
            for (var i = 0; i < 4; i++) {
                var b = tx * (gx0[q + i] - gx1[q + i]) + ty * (gy0[q + i] - gy1[q + i]);
                var v = (short) ((s0[q + i] + s1[q + i] + b + offset) >> shift);
                plane[d + i] = (short) Math.clamp(v, 0, maxVal);
            }
        }
    }

    /**
     * Applies the vertical pass of the inverse transform (8.7.4.2) to the first {@code nonZeroW} columns:
     * {@code e[y * w + x] = clip16((sum(m[j * rowStride + y] * d[j * w + x]) + 64) >> 7)} over the first
     * {@code nonZeroH} coefficients of the column.
     *
     * @param d the scaled coefficients, {@code w} per row
     * @param w the block width, above 1
     * @param h the block height
     * @param nonZeroW the number of leading columns that may hold nonzero coefficients
     * @param nonZeroH the number of leading rows that may hold nonzero coefficients
     * @param m the transform matrix, whose basis function {@code j} starts at {@code j * rowStride}
     * @param rowStride the distance between basis functions in {@code m}
     * @param e receives the intermediate values, {@code w} per row
     */
    void inverseVertical(int[] d, int w, int h, int nonZeroW, int nonZeroH, int[] m, int rowStride, int[] e) {
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < nonZeroW; x++) {
                var sum = 0;
                for (var j = 0; j < nonZeroH; j++) {
                    sum += m[j * rowStride + y] * d[j * w + x];
                }
                e[y * w + x] = Math.clamp((sum + 64) >> 7, COEFF_MIN, COEFF_MAX);
            }
        }
    }

    /**
     * Applies the horizontal pass of the inverse transform (8.7.4.2):
     * {@code res[y * w + x] = (sum(e[y * w + j] * m[j * rowStride + x]) + round) >> shift} over the first
     * {@code nonZeroW} values of the row.
     *
     * @param e the intermediate values, {@code w} per row
     * @param w the block width
     * @param h the block height
     * @param nonZeroW the number of leading columns that may hold nonzero values
     * @param m the transform matrix, whose basis function {@code j} starts at {@code j * rowStride}
     * @param rowStride the distance between basis functions in {@code m}
     * @param res receives the residual samples, {@code w} per row
     * @param round the rounding offset
     * @param shift the right shift
     */
    void inverseHorizontal(int[] e, int w, int h, int nonZeroW, int[] m, int rowStride, int[] res, int round,
                           int shift) {
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var sum = 0;
                for (var j = 0; j < nonZeroW; j++) {
                    sum += e[y * w + j] * m[j * rowStride + x];
                }
                res[y * w + x] = (sum + round) >> shift;
            }
        }
    }
}
