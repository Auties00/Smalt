/* Ports VVdeC 3.3.0 (source/Lib/CommonLib/TrQuant.cpp, TrQuant_EMT.cpp, Quant.cpp and DepQuant.cpp: dequantization
 * and the inverse transforms). Copyright (c) 2018-2026, Fraunhofer-Gesellschaft zur Foerderung der angewandten
 * Forschung e.V. & The VVdeC Authors; Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

/**
 * Scales transform coefficient levels and inverse-transforms them into residual samples (ITU-T H.266 8.7.2 to
 * 8.7.4): dequantization with scaling lists and dependent quantization, BDPCM accumulation, LFNST, the DCT-II,
 * DST-VII and DCT-VIII kernels of the explicit and implicit multiple transform selection, and transform skip.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results. Instances are not
 * thread-safe.
 */
final class VvcTransform {
    /**
     * Constructs a transform with its scratch buffers.
     */
    VvcTransform() {
    }

    /**
     * The smallest intermediate coefficient value, {@code CoeffMinY} and {@code CoeffMinC} of the 16-bit precision.
     */
    private static final int COEFF_MIN = -(1 << 15);
    /**
     * The largest intermediate coefficient value, {@code CoeffMaxY} and {@code CoeffMaxC} of the 16-bit precision.
     */
    private static final int COEFF_MAX = (1 << 15) - 1;

    /**
     * The sample loop kernels.
     */
    private static final ScalarVvcDsp DSP = VvcDsp.KERNELS;
    /**
     * {@code levelScale}, for even and odd {@code rectNonTsFlag}s (8.7.3).
     */
    private static final int[][] LEVEL_SCALE = {{40, 45, 51, 57, 64, 72}, {57, 64, 72, 80, 90, 102}};
    /**
     * The scaling matrix identifier ({@code matrixId}, Table 36) of intra blocks by component and size exponent, -1
     * where none applies.
     */
    private static final int[][] SCALING_ID_INTRA = {
            {-1, 2, 8, 14, 20, 26}, {0, 3, 9, 15, 21, 21}, {1, 4, 10, 16, 22, 22}};
    /**
     * The scaling matrix identifier of inter blocks by component and size exponent, -1 where none applies.
     */
    private static final int[][] SCALING_ID_INTER = {
            {-1, 5, 11, 17, 23, 27}, {0, 6, 12, 18, 24, 24}, {1, 7, 13, 19, 25, 25}};

    /**
     * The scaled coefficients {@code d} of the block.
     */
    private final int[] d = new int[64 * 64];
    /**
     * The intermediate samples {@code e} of the separable transform.
     */
    private final int[] e = new int[64 * 64];
    /**
     * The input vector of the low-frequency non-separable transform.
     */
    private final int[] u = new int[16];
    /**
     * The output vector of the low-frequency non-separable transform.
     */
    private final int[] v = new int[48];
    /**
     * One column of the block, the input of a vertical one-dimensional transform.
     */
    private final int[] column = new int[64];
    /**
     * One column of the block, the output of a vertical one-dimensional transform.
     */
    private final int[] column2 = new int[64];

    /**
     * The parameters of one transform block.
     */
    static final class Block {
        /**
         * Constructs the parameters of a block, set by the residual decoding.
         */
        Block() {
        }

        /**
         * The block width in samples.
         */
        int w;
        /**
         * The block height in samples.
         */
        int h;
        /**
         * The quantization parameter of the block.
         */
        int qp;
        /**
         * The bit depth of the component.
         */
        int bitDepth;
        /**
         * {@code transform_skip_flag}.
         */
        boolean transformSkip;
        /**
         * Whether dependent quantization applies.
         */
        boolean depQuant;
        /**
         * 0 none, 1 horizontal, 2 vertical.
         */
        int bdpcm;
        /**
         * The scaling matrix of the block, or {@code null} for flat scaling.
         */
        int[] scalingMatrix;
        /**
         * The DC value of the scaling matrix of a 16x16 or larger block.
         */
        int scalingDc;
        /**
         * The size of the coded scaling matrix: 2, 4 or 8.
         */
        int scalingSize;
        /**
         * {@code trTypeHor}: 0 DCT-II, 1 DST-VII, 2 DCT-VIII.
         */
        int trTypeHor;
        /**
         * {@code trTypeVer}: 0 DCT-II, 1 DST-VII, 2 DCT-VIII.
         */
        int trTypeVer;
        /**
         * {@code lfnst_idx}.
         */
        int lfnstIdx;
        /**
         * The LFNST mode after wide-angle mapping, when {@link #lfnstIdx} is not 0.
         */
        int lfnstMode;
        /**
         * {@code QpPrimeTsMin}.
         */
        int minQpTs;
    }

    /**
     * Returns the scaling matrix identifier of Table 38.
     *
     * @param intra whether the block is intra coded
     * @param c the component
     * @param w the block width
     * @param h the block height
     * @return the scaling matrix identifier, or -1 when no scaling matrix applies
     */
    static int scalingId(boolean intra, int c, int w, int h) {
        var log2 = Integer.numberOfTrailingZeros(Math.max(w, h));
        return (intra ? SCALING_ID_INTRA : SCALING_ID_INTER)[c][log2 - 1];
    }

    /**
     * Computes the residual of a transform block.
     *
     * @param b the block parameters
     * @param levels the coefficient levels in raster order
     * @param offset the index of the first level
     * @param res receives the residual samples in raster order
     */
    void residual(Block b, int[] levels, int offset, int[] res) {
        var w = b.w;
        var h = b.h;
        var log2W = Integer.numberOfTrailingZeros(w);
        var log2H = Integer.numberOfTrailingZeros(h);
        int qp;
        int bdShift;
        var rect = 0;
        if (!b.transformSkip) {
            qp = Math.clamp(b.qp, 0, 63 + 6 * (b.bitDepth - 8));
            rect = ((log2W + log2H) & 1) == 1 ? 1 : 0;
            bdShift = b.bitDepth + rect + ((log2W + log2H) / 2) + 10 - 15 + (b.depQuant ? 1 : 0);
        } else {
            qp = Math.clamp(b.qp, b.minQpTs, 63 + 6 * (b.bitDepth - 8));
            bdShift = 10;
        }
        var bdOffset = (1 << bdShift) >> 1;
        var qpAdj = b.depQuant && !b.transformSkip ? qp + 1 : qp;
        var scale = LEVEL_SCALE[rect][qpAdj % 6];
        var shift = qpAdj / 6;
        var n = w * h;
        if (b.bdpcm == 1) {
            for (var y = 0; y < h; y++) {
                var acc = 0;
                for (var x = 0; x < w; x++) {
                    acc = Math.clamp(acc + levels[offset + y * w + x], COEFF_MIN, COEFF_MAX);
                    d[y * w + x] = acc;
                }
            }
        } else if (b.bdpcm == 2) {
            for (var x = 0; x < w; x++) {
                var acc = 0;
                for (var y = 0; y < h; y++) {
                    acc = Math.clamp(acc + levels[offset + y * w + x], COEFF_MIN, COEFF_MAX);
                    d[y * w + x] = acc;
                }
            }
        } else {
            System.arraycopy(levels, offset, d, 0, n);
        }
        var matrix = b.transformSkip ? null : b.scalingMatrix;
        if (matrix == null) {
            long ls = (long) 16 * scale << shift;
            for (var i = 0; i < n; i++) {
                var level = d[i];
                if (level != 0) {
                    d[i] = (int) Math.clamp((level * ls + bdOffset) >> bdShift, COEFF_MIN, COEFF_MAX);
                }
            }
        } else {
            var log2M = Integer.numberOfTrailingZeros(b.scalingSize);
            for (var y = 0; y < h; y++) {
                var j = (y << log2M) >> log2H;
                for (var x = 0; x < w; x++) {
                    var i = y * w + x;
                    var level = d[i];
                    if (level == 0) {
                        continue;
                    }
                    int m;
                    if (x == 0 && y == 0 && b.scalingDc >= 0) {
                        m = b.scalingDc;
                    } else {
                        m = matrix[j * b.scalingSize + ((x << log2M) >> log2W)];
                    }
                    long ls = (long) m * scale << shift;
                    d[i] = (int) Math.clamp((level * ls + bdOffset) >> bdShift, COEFF_MIN, COEFF_MAX);
                }
            }
        }
        if (b.transformSkip) {
            System.arraycopy(d, 0, res, 0, n);
            return;
        }
        inverse(b, res);
    }

    /**
     * Applies the inverse separable transform (8.7.4.2) to the scaled coefficients.
     *
     * @param b the block
     * @param res receives the residual samples, {@code b.w} per row
     */
    private void inverse(Block b, int[] res) {
        var w = b.w;
        var h = b.h;
        int nonZeroW;
        int nonZeroH;
        if (b.lfnstIdx != 0) {
            applyLfnst(b);
            nonZeroW = w == 4 || h == 4 ? 4 : 8;
            nonZeroH = nonZeroW;
            nonZeroW = Math.min(nonZeroW, w);
            nonZeroH = Math.min(nonZeroH, h);
        } else {
            nonZeroW = Math.min(w, b.trTypeHor > 0 ? 16 : 32);
            nonZeroH = Math.min(h, b.trTypeVer > 0 ? 16 : 32);
        }
        var bdShift = (h > 1 && w > 1) ? 5 + 15 - b.bitDepth : 6 + 15 - b.bitDepth;
        var round = 1 << (bdShift - 1);
        if (h > 1 && w > 1) {
            DSP.inverseVertical(d, w, h, nonZeroW, nonZeroH, matrix(h, b.trTypeVer), rowStride(h, b.trTypeVer), e);
        } else if (h > 1) {
            for (var y = 0; y < nonZeroH; y++) {
                column[y] = d[y * w];
            }
            transform1d(column, nonZeroH, h, b.trTypeVer, column2);
            for (var y = 0; y < h; y++) {
                e[y * w] = column2[y];
            }
        } else {
            System.arraycopy(d, 0, e, 0, w);
        }
        if (w > 1) {
            DSP.inverseHorizontal(e, w, h, nonZeroW, matrix(w, b.trTypeHor), rowStride(w, b.trTypeHor), res, round,
                    bdShift);
        } else {
            for (var y = 0; y < h; y++) {
                res[y] = (e[y] + round) >> bdShift;
            }
        }
    }

    /**
     * {@return the matrix of a transform of a size, whose basis functions are {@link #rowStride(int, int)} apart}
     *
     * @param size the transform size
     * @param trType the transform type: 0 DCT-II, 1 DST-VII, 2 DCT-VIII
     */
    private static int[] matrix(int size, int trType) {
        var log2 = Integer.numberOfTrailingZeros(size);
        return trType == 0 ? VvcTransformTables.DCT2_64
                : trType == 1 ? VvcTransformTables.DST7[log2] : VvcTransformTables.DCT8[log2];
    }

    /**
     * {@return the distance between the basis functions of the matrix of {@link #matrix(int, int)}: the 64-point
     * DCT-II matrix holds the basis functions of the smaller sizes every {@code 64 / size} rows}
     *
     * @param size the transform size
     * @param trType the transform type: 0 DCT-II, 1 DST-VII, 2 DCT-VIII
     */
    private static int rowStride(int size, int trType) {
        return trType == 0 ? (64 / size) * 64 : size;
    }

    /**
     * Applies the one-dimensional transform of 8.7.4.4.
     *
     * @param in the input coefficients
     * @param nonZero the number of leading input coefficients that may be nonzero
     * @param size the transform size
     * @param trType the transform type: 0 DCT-II, 1 DST-VII, 2 DCT-VIII
     * @param out receives the output samples
     */
    private static void transform1d(int[] in, int nonZero, int size, int trType, int[] out) {
        var log2 = Integer.numberOfTrailingZeros(size);
        if (trType == 0) {
            var m = VvcTransformTables.DCT2_64;
            var step = 1 << (6 - log2);
            for (var i = 0; i < size; i++) {
                long sum = 0;
                for (var j = 0; j < nonZero; j++) {
                    var c = in[j];
                    if (c != 0) {
                        sum += (long) m[(j * step) * 64 + i] * c;
                    }
                }
                out[i] = (int) sum;
            }
        } else {
            var m = trType == 1 ? VvcTransformTables.DST7[log2] : VvcTransformTables.DCT8[log2];
            for (var i = 0; i < size; i++) {
                long sum = 0;
                for (var j = 0; j < nonZero; j++) {
                    var c = in[j];
                    if (c != 0) {
                        sum += (long) m[j * size + i] * c;
                    }
                }
                out[i] = (int) sum;
            }
        }
    }

    /**
     * Applies the inverse LFNST to the top-left coefficients of {@link #d} (8.7.4.1, 8.7.4.2).
     *
     * @param b the block, whose scaled coefficients are transformed in place
     */
    private void applyLfnst(Block b) {
        var w = b.w;
        var h = b.h;
        var big = w >= 8 && h >= 8;
        var outSize = big ? 48 : 16;
        var log2Size = big ? 3 : 2;
        var nonZeroSize = (w == 4 && h == 4) || (w == 8 && h == 8) ? 8 : 16;
        var scan = VvcScan.diagonal(2, 2);
        for (var i = 0; i < nonZeroSize; i++) {
            var pos = scan[i];
            u[i] = d[(pos >> 2) * w + (pos & 3)];
        }
        var mode = b.lfnstMode;
        int set;
        if (mode < 0) {
            set = 1;
        } else if (mode <= 1) {
            set = 0;
        } else if (mode <= 12) {
            set = 1;
        } else if (mode <= 23) {
            set = 2;
        } else if (mode <= 44) {
            set = 3;
        } else if (mode <= 55) {
            set = 2;
        } else if (mode <= 80) {
            set = 1;
        } else {
            set = 0;
        }
        var matrix = big ? VvcTransformTables.LFNST8[set][b.lfnstIdx - 1] : VvcTransformTables.LFNST4[set][b.lfnstIdx - 1];
        for (var i = 0; i < outSize; i++) {
            var sum = 0;
            var row = i * 16;
            for (var j = 0; j < nonZeroSize; j++) {
                sum += matrix[row + j] * u[j];
            }
            v[i] = Math.clamp((sum + 64) >> 7, COEFF_MIN, COEFF_MAX);
        }
        var n = 1 << log2Size;
        var transpose = mode > 34;
        for (var y = 0; y < n && y < h; y++) {
            for (var x = 0; x < n && x < w; x++) {
                int value;
                if (!transpose) {
                    if (y < 4) {
                        value = v[x + (y << log2Size)];
                    } else if (x < 4) {
                        value = v[32 + x + ((y - 4) << 2)];
                    } else {
                        continue;
                    }
                } else {
                    if (x < 4) {
                        value = v[y + (x << log2Size)];
                    } else if (y < 4) {
                        value = v[32 + y + ((x - 4) << 2)];
                    } else {
                        continue;
                    }
                }
                d[y * w + x] = value;
            }
        }
    }
}
