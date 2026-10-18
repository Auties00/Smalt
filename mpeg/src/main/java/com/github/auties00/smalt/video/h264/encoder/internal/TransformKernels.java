package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.internal.H264Tables;

/**
 * Computes the forward and inverse integer transforms, quantization and dequantization of H.264
 * residual blocks.
 *
 * <p>This class is the scalar reference implementation; a Vector API subclass overrides the hot
 * methods with identical results. The inverse transforms and the dequantization reproduce the
 * normative decoding process of clause 8.5 exactly, so the encoder's reconstruction equals every
 * conforming decoder's. Coefficient blocks and quantized levels are stored in raster order
 * ({@code row * size + column}); the entropy coders read levels through the scan tables.
 */
class TransformKernels {
    /**
     * Constructs the scalar kernels.
     */
    TransformKernels() {
    }

    /**
     * Computes the forward 4x4 core transform of the difference of a source and a prediction
     * block.
     *
     * @param src the source samples
     * @param srcOffset the index of the source's top-left sample
     * @param srcStride the source row stride
     * @param pred the prediction samples
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param out receives the 16 coefficients in raster order
     * @param outOffset the index of the first coefficient
     */
    void forward4x4(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset, int predStride,
                    int[] out, int outOffset) {
        for (var i = 0; i < 4; i++) {
            var s = srcOffset + i * srcStride;
            var p = predOffset + i * predStride;
            var r0 = (src[s] & 0xFF) - (pred[p] & 0xFF);
            var r1 = (src[s + 1] & 0xFF) - (pred[p + 1] & 0xFF);
            var r2 = (src[s + 2] & 0xFF) - (pred[p + 2] & 0xFF);
            var r3 = (src[s + 3] & 0xFF) - (pred[p + 3] & 0xFF);
            var p0 = r0 + r3;
            var p1 = r1 + r2;
            var p2 = r1 - r2;
            var p3 = r0 - r3;
            var o = outOffset + i * 4;
            out[o] = p0 + p1;
            out[o + 1] = 2 * p3 + p2;
            out[o + 2] = p0 - p1;
            out[o + 3] = p3 - 2 * p2;
        }
        for (var j = 0; j < 4; j++) {
            var o = outOffset + j;
            var r0 = out[o];
            var r1 = out[o + 4];
            var r2 = out[o + 8];
            var r3 = out[o + 12];
            var p0 = r0 + r3;
            var p1 = r1 + r2;
            var p2 = r1 - r2;
            var p3 = r0 - r3;
            out[o] = p0 + p1;
            out[o + 4] = 2 * p3 + p2;
            out[o + 8] = p0 - p1;
            out[o + 12] = p3 - 2 * p2;
        }
    }

    /**
     * Adds the inverse 4x4 transform of dequantized coefficients to a block in place (clause
     * 8.5.12.2 followed by the picture construction of clause 8.5.14).
     *
     * @param coef the 16 dequantized coefficients in raster order; overwritten
     * @param coefOffset the index of the first coefficient
     * @param dst the samples holding the prediction, receiving the reconstruction
     * @param dstOffset the index of the block's top-left sample
     * @param dstStride the row stride
     */
    void inverse4x4Add(int[] coef, int coefOffset, byte[] dst, int dstOffset, int dstStride) {
        for (var i = 0; i < 4; i++) {
            var o = coefOffset + i * 4;
            var d0 = coef[o];
            var d1 = coef[o + 1];
            var d2 = coef[o + 2];
            var d3 = coef[o + 3];
            var e = d0 + d2;
            var f = d0 - d2;
            var g = (d1 >> 1) - d3;
            var h = d1 + (d3 >> 1);
            coef[o] = e + h;
            coef[o + 1] = f + g;
            coef[o + 2] = f - g;
            coef[o + 3] = e - h;
        }
        for (var j = 0; j < 4; j++) {
            var o = coefOffset + j;
            var d0 = coef[o];
            var d1 = coef[o + 4];
            var d2 = coef[o + 8];
            var d3 = coef[o + 12];
            var e = d0 + d2;
            var f = d0 - d2;
            var g = (d1 >> 1) - d3;
            var h = d1 + (d3 >> 1);
            var p = dstOffset + j;
            dst[p] = clip(dst[p], (e + h + 32) >> 6);
            dst[p + dstStride] = clip(dst[p + dstStride], (f + g + 32) >> 6);
            dst[p + 2 * dstStride] = clip(dst[p + 2 * dstStride], (f - g + 32) >> 6);
            dst[p + 3 * dstStride] = clip(dst[p + 3 * dstStride], (e - h + 32) >> 6);
        }
    }

    /**
     * Computes the forward 4x4 transform of the difference of a source and a prediction block and
     * quantizes it, with every intermediate value kept in registers.
     *
     * <p>The result equals {@link #forward4x4} followed by {@link #quantize4x4} with
     * {@code start} 0.
     *
     * @param src the source samples
     * @param srcOffset the index of the source's top-left sample
     * @param srcStride the source row stride
     * @param pred the prediction samples
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param mf the 16 multipliers in raster order
     * @param bias the rounding offset
     * @param shift the right shift
     * @param limit the largest level magnitude the entropy coder can represent
     * @param levels receives the 16 levels in raster order
     * @param levelOffset the index of the first level
     * @return the number of non-zero levels
     */
    int forwardQuantize4x4(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset, int predStride,
                           int[] mf, int bias, int shift, int limit, int[] levels, int levelOffset) {
        var s0 = srcOffset + 0 * srcStride;
        var q0 = predOffset + 0 * predStride;
        var r00 = (src[s0] & 0xFF) - (pred[q0] & 0xFF);
        var r01 = (src[s0 + 1] & 0xFF) - (pred[q0 + 1] & 0xFF);
        var r02 = (src[s0 + 2] & 0xFF) - (pred[q0 + 2] & 0xFF);
        var r03 = (src[s0 + 3] & 0xFF) - (pred[q0 + 3] & 0xFF);
        var a00 = r00 + r03 + r01 + r02;
        var a01 = 2 * (r00 - r03) + (r01 - r02);
        var a02 = r00 + r03 - r01 - r02;
        var a03 = (r00 - r03) - 2 * (r01 - r02);
        var s1 = srcOffset + 1 * srcStride;
        var q1 = predOffset + 1 * predStride;
        var r10 = (src[s1] & 0xFF) - (pred[q1] & 0xFF);
        var r11 = (src[s1 + 1] & 0xFF) - (pred[q1 + 1] & 0xFF);
        var r12 = (src[s1 + 2] & 0xFF) - (pred[q1 + 2] & 0xFF);
        var r13 = (src[s1 + 3] & 0xFF) - (pred[q1 + 3] & 0xFF);
        var a10 = r10 + r13 + r11 + r12;
        var a11 = 2 * (r10 - r13) + (r11 - r12);
        var a12 = r10 + r13 - r11 - r12;
        var a13 = (r10 - r13) - 2 * (r11 - r12);
        var s2 = srcOffset + 2 * srcStride;
        var q2 = predOffset + 2 * predStride;
        var r20 = (src[s2] & 0xFF) - (pred[q2] & 0xFF);
        var r21 = (src[s2 + 1] & 0xFF) - (pred[q2 + 1] & 0xFF);
        var r22 = (src[s2 + 2] & 0xFF) - (pred[q2 + 2] & 0xFF);
        var r23 = (src[s2 + 3] & 0xFF) - (pred[q2 + 3] & 0xFF);
        var a20 = r20 + r23 + r21 + r22;
        var a21 = 2 * (r20 - r23) + (r21 - r22);
        var a22 = r20 + r23 - r21 - r22;
        var a23 = (r20 - r23) - 2 * (r21 - r22);
        var s3 = srcOffset + 3 * srcStride;
        var q3 = predOffset + 3 * predStride;
        var r30 = (src[s3] & 0xFF) - (pred[q3] & 0xFF);
        var r31 = (src[s3 + 1] & 0xFF) - (pred[q3 + 1] & 0xFF);
        var r32 = (src[s3 + 2] & 0xFF) - (pred[q3 + 2] & 0xFF);
        var r33 = (src[s3 + 3] & 0xFF) - (pred[q3 + 3] & 0xFF);
        var a30 = r30 + r33 + r31 + r32;
        var a31 = 2 * (r30 - r33) + (r31 - r32);
        var a32 = r30 + r33 - r31 - r32;
        var a33 = (r30 - r33) - 2 * (r31 - r32);
        var count = 0;
        {
            var p0 = a00 + a30;
            var p1 = a10 + a20;
            var p2 = a10 - a20;
            var p3 = a00 - a30;
            var c0 = p0 + p1;
            var l0 = Math.min(limit, (Math.abs(c0) * mf[0] + bias) >> shift);
            levels[levelOffset + 0] = (l0 ^ (c0 >> 31)) - (c0 >> 31);
            count += l0 != 0 ? 1 : 0;
            var c4 = 2 * p3 + p2;
            var l4 = Math.min(limit, (Math.abs(c4) * mf[4] + bias) >> shift);
            levels[levelOffset + 4] = (l4 ^ (c4 >> 31)) - (c4 >> 31);
            count += l4 != 0 ? 1 : 0;
            var c8 = p0 - p1;
            var l8 = Math.min(limit, (Math.abs(c8) * mf[8] + bias) >> shift);
            levels[levelOffset + 8] = (l8 ^ (c8 >> 31)) - (c8 >> 31);
            count += l8 != 0 ? 1 : 0;
            var c12 = p3 - 2 * p2;
            var l12 = Math.min(limit, (Math.abs(c12) * mf[12] + bias) >> shift);
            levels[levelOffset + 12] = (l12 ^ (c12 >> 31)) - (c12 >> 31);
            count += l12 != 0 ? 1 : 0;
        }
        {
            var p0 = a01 + a31;
            var p1 = a11 + a21;
            var p2 = a11 - a21;
            var p3 = a01 - a31;
            var c1 = p0 + p1;
            var l1 = Math.min(limit, (Math.abs(c1) * mf[1] + bias) >> shift);
            levels[levelOffset + 1] = (l1 ^ (c1 >> 31)) - (c1 >> 31);
            count += l1 != 0 ? 1 : 0;
            var c5 = 2 * p3 + p2;
            var l5 = Math.min(limit, (Math.abs(c5) * mf[5] + bias) >> shift);
            levels[levelOffset + 5] = (l5 ^ (c5 >> 31)) - (c5 >> 31);
            count += l5 != 0 ? 1 : 0;
            var c9 = p0 - p1;
            var l9 = Math.min(limit, (Math.abs(c9) * mf[9] + bias) >> shift);
            levels[levelOffset + 9] = (l9 ^ (c9 >> 31)) - (c9 >> 31);
            count += l9 != 0 ? 1 : 0;
            var c13 = p3 - 2 * p2;
            var l13 = Math.min(limit, (Math.abs(c13) * mf[13] + bias) >> shift);
            levels[levelOffset + 13] = (l13 ^ (c13 >> 31)) - (c13 >> 31);
            count += l13 != 0 ? 1 : 0;
        }
        {
            var p0 = a02 + a32;
            var p1 = a12 + a22;
            var p2 = a12 - a22;
            var p3 = a02 - a32;
            var c2 = p0 + p1;
            var l2 = Math.min(limit, (Math.abs(c2) * mf[2] + bias) >> shift);
            levels[levelOffset + 2] = (l2 ^ (c2 >> 31)) - (c2 >> 31);
            count += l2 != 0 ? 1 : 0;
            var c6 = 2 * p3 + p2;
            var l6 = Math.min(limit, (Math.abs(c6) * mf[6] + bias) >> shift);
            levels[levelOffset + 6] = (l6 ^ (c6 >> 31)) - (c6 >> 31);
            count += l6 != 0 ? 1 : 0;
            var c10 = p0 - p1;
            var l10 = Math.min(limit, (Math.abs(c10) * mf[10] + bias) >> shift);
            levels[levelOffset + 10] = (l10 ^ (c10 >> 31)) - (c10 >> 31);
            count += l10 != 0 ? 1 : 0;
            var c14 = p3 - 2 * p2;
            var l14 = Math.min(limit, (Math.abs(c14) * mf[14] + bias) >> shift);
            levels[levelOffset + 14] = (l14 ^ (c14 >> 31)) - (c14 >> 31);
            count += l14 != 0 ? 1 : 0;
        }
        {
            var p0 = a03 + a33;
            var p1 = a13 + a23;
            var p2 = a13 - a23;
            var p3 = a03 - a33;
            var c3 = p0 + p1;
            var l3 = Math.min(limit, (Math.abs(c3) * mf[3] + bias) >> shift);
            levels[levelOffset + 3] = (l3 ^ (c3 >> 31)) - (c3 >> 31);
            count += l3 != 0 ? 1 : 0;
            var c7 = 2 * p3 + p2;
            var l7 = Math.min(limit, (Math.abs(c7) * mf[7] + bias) >> shift);
            levels[levelOffset + 7] = (l7 ^ (c7 >> 31)) - (c7 >> 31);
            count += l7 != 0 ? 1 : 0;
            var c11 = p0 - p1;
            var l11 = Math.min(limit, (Math.abs(c11) * mf[11] + bias) >> shift);
            levels[levelOffset + 11] = (l11 ^ (c11 >> 31)) - (c11 >> 31);
            count += l11 != 0 ? 1 : 0;
            var c15 = p3 - 2 * p2;
            var l15 = Math.min(limit, (Math.abs(c15) * mf[15] + bias) >> shift);
            levels[levelOffset + 15] = (l15 ^ (c15 >> 31)) - (c15 >> 31);
            count += l15 != 0 ? 1 : 0;
        }
        return count;
    }

    /**
     * Transforms and quantizes the sixteen 4x4 luma blocks of a macroblock residual.
     *
     * <p>The result equals {@link #forwardQuantize4x4} on every block, with the levels of block
     * {@code luma4x4BlkIdx} at index {@code 16 * luma4x4BlkIdx} and its number of non-zero
     * levels in {@code counts[luma4x4BlkIdx]}.
     *
     * @param src the source samples
     * @param srcOffset the index of the macroblock's top-left source sample
     * @param srcStride the source row stride
     * @param pred the prediction samples
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param qp the quantization parameter
     * @param bias the rounding offset
     * @param limit the largest level magnitude the entropy coder can represent
     * @param levels receives the 256 levels
     * @param counts receives the 16 non-zero counts
     */
    void forwardQuantizeLuma(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset, int predStride,
                             int qp, int bias, int limit, int[] levels, int[] counts) {
        var mf = Tables.QUANT4[qp % 6];
        var shift = 15 + qp / 6;
        for (var blk = 0; blk < 16; blk++) {
            var x = H264Tables.BLOCK_X[blk] * 4;
            var y = H264Tables.BLOCK_Y[blk] * 4;
            counts[blk] = forwardQuantize4x4(src, srcOffset + y * srcStride + x, srcStride, pred,
                    predOffset + y * predStride + x, predStride, mf, bias, shift, limit, levels, blk * 16);
        }
    }

    /**
     * The smallest coefficient magnitude that quantizes to a non-zero level with the inter rounding
     * offset, per quantizer and raster position: {@code ceil((2^shift - bias) / mf)} at
     * {@code 16 * qp + position}.
     */
    static final int[] INTER_NONZERO_THRESHOLDS = interThresholds(1);

    /**
     * The smallest coefficient magnitude that quantizes to a level of magnitude 2 or more with the
     * inter rounding offset, per quantizer and raster position, laid out like
     * {@link #INTER_NONZERO_THRESHOLDS}.
     */
    static final int[] INTER_BIG_THRESHOLDS = interThresholds(2);

    /**
     * Computes the smallest coefficient magnitudes that quantize to at least a level with the inter
     * rounding offset.
     *
     * @param level the level magnitude, 1 or 2
     * @return the thresholds at {@code 16 * qp + position}
     */
    private static int[] interThresholds(int level) {
        var table = new int[52 * 16];
        for (var qp = 0; qp < 52; qp++) {
            var shift = 15 + qp / 6;
            var target = ((long) level << shift) - ResidualCoder.bias(shift, false);
            for (var position = 0; position < 16; position++) {
                var mf = Tables.QUANT4[qp % 6][position];
                table[qp * 16 + position] = (int) ((target + mf - 1) / mf);
            }
        }
        return table;
    }

    /**
     * Computes which levels of the sixteen 4x4 luma blocks of an inter macroblock residual are
     * non-zero and which exceed magnitude 1, without producing the levels, stopping early once a
     * level exceeds magnitude 1.
     *
     * <p>{@code masks[luma4x4BlkIdx]} receives the block's masks: bit {@code r} is set when the
     * level at raster position {@code r} is non-zero, bit {@code 16 + r} when its magnitude exceeds
     * 1, and {@code signs[luma4x4BlkIdx]} bit {@code r} when the level is negative. The masks are
     * those of the levels {@link #forwardQuantizeLuma} produces with the inter rounding offset
     * {@code ResidualCoder.bias(shift, false)} and any limit of at least 2. The blocks are
     * processed by bands of four rows of samples, top first; when a band holds a level of
     * magnitude above 1 the method returns after that band, and the masks of the blocks below are
     * left unspecified.
     *
     * @param src the source samples
     * @param srcOffset the index of the macroblock's top-left source sample
     * @param srcStride the source row stride
     * @param pred the prediction samples
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param qp the quantization parameter
     * @param masks receives the 16 masks
     * @param signs receives the 16 sign masks
     * @return {@code true} when every block was processed, {@code false} when the method stopped
     * after a band holding a level of magnitude above 1
     */
    boolean quantizeLumaMasks(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset,
                              int predStride, int qp, int[] masks, int[] signs) {
        for (var band = 0; band < 4; band++) {
            var big = 0;
            for (var column = 0; column < 4; column++) {
                var blockMasks = blockMasks(src, srcOffset + band * 4 * srcStride + column * 4, srcStride, pred,
                        predOffset + band * 4 * predStride + column * 4, predStride, qp * 16);
                var blk = H264Tables.BLOCK_RASTER[band * 4 + column];
                masks[blk] = (int) blockMasks;
                signs[blk] = (int) (blockMasks >>> 32);
                big |= (int) blockMasks >>> 16;
            }
            if (big != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Computes which AC levels of the eight 4x4 blocks of both chroma residuals of an inter
     * macroblock are non-zero and which exceed magnitude 1, and their unquantized DC
     * coefficients, without producing the levels, stopping early once an AC level exceeds
     * magnitude 1.
     *
     * <p>Block {@code blk} of component {@code c} (0 for Cb, 1 for Cr, blocks in raster order)
     * gets {@code masks[4 * c + blk]}, with the AC bits of {@link #quantizeLumaMasks} (bit 0 and
     * bit 16 clear), and {@code dc[4 * c + blk]}, the coefficient {@link #forwardQuantizeChroma}
     * stores there. The masks are those of the AC levels {@link #forwardQuantizeChroma} produces
     * with the inter rounding offset {@code ResidualCoder.bias(shift, false)} and any limit of at
     * least 2. The blocks are processed by bands of four rows of samples, the two bands of Cb
     * first; when a band holds an AC level of magnitude above 1 the method returns after that
     * band, and the masks and coefficients of the later bands are left unspecified.
     *
     * @param srcU the source Cb samples
     * @param srcV the source Cr samples
     * @param srcOffset the index of the top-left source sample in both planes
     * @param srcStride the source row stride
     * @param predU the Cb prediction
     * @param predV the Cr prediction
     * @param predOffset the index of the top-left prediction sample in both planes
     * @param predStride the prediction row stride
     * @param qp the chroma quantization parameter
     * @param masks receives the 8 masks
     * @param dc receives the 8 unquantized DC coefficients
     * @return {@code true} when every block was processed, {@code false} when the method stopped
     * after a band holding an AC level of magnitude above 1
     */
    boolean quantizeChromaMasks(byte[] srcU, byte[] srcV, int srcOffset, int srcStride, byte[] predU,
                                byte[] predV, int predOffset, int predStride, int qp, int[] masks, int[] dc) {
        for (var band = 0; band < 4; band++) {
            var src = band < 2 ? srcU : srcV;
            var pred = band < 2 ? predU : predV;
            var half = band & 1;
            var big = 0;
            for (var column = 0; column < 2; column++) {
                var index = (band >> 1) * 4 + half * 2 + column;
                var s = srcOffset + half * 4 * srcStride + column * 4;
                var p = predOffset + half * 4 * predStride + column * 4;
                var blockMasks = (int) blockMasks(src, s, srcStride, pred, p, predStride, qp * 16) & ~0x10001;
                masks[index] = blockMasks;
                var sum = 0;
                for (var y = 0; y < 4; y++) {
                    for (var x = 0; x < 4; x++) {
                        sum += (src[s + y * srcStride + x] & 0xFF) - (pred[p + y * predStride + x] & 0xFF);
                    }
                }
                dc[index] = sum;
                big |= blockMasks >>> 16;
            }
            if (big != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Computes the masks of {@link #quantizeLumaMasks} for one 4x4 block, with every intermediate
     * value kept in registers.
     *
     * @param src the source samples
     * @param srcOffset the index of the source's top-left sample
     * @param srcStride the source row stride
     * @param pred the prediction samples
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param table the index of the quantizer's first threshold
     * @return the non-zero mask in bits 0 to 15, the large-level mask in bits 16 to 31 and the
     * negative-level mask in bits 32 to 47
     */
    private static long blockMasks(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset,
                                  int predStride, int table) {
        var s0 = srcOffset + 0 * srcStride;
        var q0 = predOffset + 0 * predStride;
        var r00 = (src[s0] & 0xFF) - (pred[q0] & 0xFF);
        var r01 = (src[s0 + 1] & 0xFF) - (pred[q0 + 1] & 0xFF);
        var r02 = (src[s0 + 2] & 0xFF) - (pred[q0 + 2] & 0xFF);
        var r03 = (src[s0 + 3] & 0xFF) - (pred[q0 + 3] & 0xFF);
        var a00 = r00 + r03 + r01 + r02;
        var a01 = 2 * (r00 - r03) + (r01 - r02);
        var a02 = r00 + r03 - r01 - r02;
        var a03 = (r00 - r03) - 2 * (r01 - r02);
        var s1 = srcOffset + 1 * srcStride;
        var q1 = predOffset + 1 * predStride;
        var r10 = (src[s1] & 0xFF) - (pred[q1] & 0xFF);
        var r11 = (src[s1 + 1] & 0xFF) - (pred[q1 + 1] & 0xFF);
        var r12 = (src[s1 + 2] & 0xFF) - (pred[q1 + 2] & 0xFF);
        var r13 = (src[s1 + 3] & 0xFF) - (pred[q1 + 3] & 0xFF);
        var a10 = r10 + r13 + r11 + r12;
        var a11 = 2 * (r10 - r13) + (r11 - r12);
        var a12 = r10 + r13 - r11 - r12;
        var a13 = (r10 - r13) - 2 * (r11 - r12);
        var s2 = srcOffset + 2 * srcStride;
        var q2 = predOffset + 2 * predStride;
        var r20 = (src[s2] & 0xFF) - (pred[q2] & 0xFF);
        var r21 = (src[s2 + 1] & 0xFF) - (pred[q2 + 1] & 0xFF);
        var r22 = (src[s2 + 2] & 0xFF) - (pred[q2 + 2] & 0xFF);
        var r23 = (src[s2 + 3] & 0xFF) - (pred[q2 + 3] & 0xFF);
        var a20 = r20 + r23 + r21 + r22;
        var a21 = 2 * (r20 - r23) + (r21 - r22);
        var a22 = r20 + r23 - r21 - r22;
        var a23 = (r20 - r23) - 2 * (r21 - r22);
        var s3 = srcOffset + 3 * srcStride;
        var q3 = predOffset + 3 * predStride;
        var r30 = (src[s3] & 0xFF) - (pred[q3] & 0xFF);
        var r31 = (src[s3 + 1] & 0xFF) - (pred[q3 + 1] & 0xFF);
        var r32 = (src[s3 + 2] & 0xFF) - (pred[q3 + 2] & 0xFF);
        var r33 = (src[s3 + 3] & 0xFF) - (pred[q3 + 3] & 0xFF);
        var a30 = r30 + r33 + r31 + r32;
        var a31 = 2 * (r30 - r33) + (r31 - r32);
        var a32 = r30 + r33 - r31 - r32;
        var a33 = (r30 - r33) - 2 * (r31 - r32);
        long masks = 0;
        {
            var p0 = a00 + a30;
            var p1 = a10 + a20;
            var p2 = a10 - a20;
            var p3 = a00 - a30;
            masks |= positionMasks(p0 + p1, table, 0);
            masks |= positionMasks(2 * p3 + p2, table, 4);
            masks |= positionMasks(p0 - p1, table, 8);
            masks |= positionMasks(p3 - 2 * p2, table, 12);
        }
        {
            var p0 = a01 + a31;
            var p1 = a11 + a21;
            var p2 = a11 - a21;
            var p3 = a01 - a31;
            masks |= positionMasks(p0 + p1, table, 1);
            masks |= positionMasks(2 * p3 + p2, table, 5);
            masks |= positionMasks(p0 - p1, table, 9);
            masks |= positionMasks(p3 - 2 * p2, table, 13);
        }
        {
            var p0 = a02 + a32;
            var p1 = a12 + a22;
            var p2 = a12 - a22;
            var p3 = a02 - a32;
            masks |= positionMasks(p0 + p1, table, 2);
            masks |= positionMasks(2 * p3 + p2, table, 6);
            masks |= positionMasks(p0 - p1, table, 10);
            masks |= positionMasks(p3 - 2 * p2, table, 14);
        }
        {
            var p0 = a03 + a33;
            var p1 = a13 + a23;
            var p2 = a13 - a23;
            var p3 = a03 - a33;
            masks |= positionMasks(p0 + p1, table, 3);
            masks |= positionMasks(2 * p3 + p2, table, 7);
            masks |= positionMasks(p0 - p1, table, 11);
            masks |= positionMasks(p3 - 2 * p2, table, 15);
        }
        return masks;
    }

    /**
     * Returns the mask bits of one coefficient for {@link #blockMasks}.
     *
     * @param coefficient the transform coefficient before quantization
     * @param table the index of the quantizer's first threshold
     * @param position the coefficient's raster position
     * @return bit {@code position} when the level is non-zero, plus bit {@code 16 + position} when
     * its magnitude exceeds 1 and bit {@code 32 + position} when it is negative
     */
    private static long positionMasks(int coefficient, int table, int position) {
        var magnitude = Math.abs(coefficient);
        long nonZero = magnitude >= INTER_NONZERO_THRESHOLDS[table + position] ? 1 : 0;
        long big = magnitude >= INTER_BIG_THRESHOLDS[table + position] ? 1 : 0;
        long negative = coefficient < 0 ? nonZero : 0;
        return (nonZero << position) | (big << (16 + position)) | (negative << (32 + position));
    }

    /**
     * Transforms the sixteen 4x4 luma blocks of a macroblock residual, storing the DC
     * coefficients unquantized and quantizing the AC coefficients.
     *
     * <p>The result equals {@link #forwardQuantizeAc4x4} on every block, with the levels of block
     * {@code luma4x4BlkIdx} at index {@code 16 * luma4x4BlkIdx}, its number of non-zero AC levels
     * in {@code counts[luma4x4BlkIdx]} and its DC coefficient in {@code dc} at the block's raster
     * position {@code 4 * row + column}.
     *
     * @param src the source samples
     * @param srcOffset the index of the macroblock's top-left source sample
     * @param srcStride the source row stride
     * @param pred the prediction samples
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param qp the quantization parameter
     * @param bias the rounding offset
     * @param limit the largest level magnitude the entropy coder can represent
     * @param levels receives the 256 levels, the DC positions zero
     * @param counts receives the 16 non-zero AC counts
     * @param dc receives the 16 unquantized DC coefficients
     */
    void forwardQuantizeLumaAc(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset,
                               int predStride, int qp, int bias, int limit, int[] levels, int[] counts, int[] dc) {
        var mf = Tables.QUANT4[qp % 6];
        var shift = 15 + qp / 6;
        for (var blk = 0; blk < 16; blk++) {
            var x = H264Tables.BLOCK_X[blk];
            var y = H264Tables.BLOCK_Y[blk];
            counts[blk] = forwardQuantizeAc4x4(src, srcOffset + y * 4 * srcStride + x * 4, srcStride, pred,
                    predOffset + y * 4 * predStride + x * 4, predStride, mf, bias, shift, limit, levels, blk * 16, dc,
                    y * 4 + x);
        }
    }

    /**
     * Transforms both 8x8 chroma residuals of a macroblock, storing the DC coefficients
     * unquantized and quantizing the AC coefficients.
     *
     * <p>The result equals {@link #forwardQuantizeAc4x4} on every block: block {@code blk} of
     * component {@code c} (0 for Cb, 1 for Cr) has its levels at index {@code 16 * (4 * c + blk)},
     * its number of non-zero AC levels in {@code counts[4 * c + blk]} and its DC coefficient in
     * {@code dc[4 * c + blk]}; the blocks of a component are in raster order.
     *
     * @param srcU the source Cb samples
     * @param srcV the source Cr samples
     * @param srcOffset the index of the top-left source sample in both planes
     * @param srcStride the source row stride
     * @param predU the Cb prediction
     * @param predV the Cr prediction
     * @param predOffset the index of the top-left prediction sample in both planes
     * @param predStride the prediction row stride
     * @param qp the chroma quantization parameter
     * @param bias the rounding offset
     * @param limit the largest level magnitude the entropy coder can represent
     * @param levels receives the 128 levels, the DC positions zero
     * @param counts receives the 8 non-zero AC counts
     * @param dc receives the 8 unquantized DC coefficients
     */
    void forwardQuantizeChroma(byte[] srcU, byte[] srcV, int srcOffset, int srcStride, byte[] predU, byte[] predV,
                               int predOffset, int predStride, int qp, int bias, int limit, int[] levels,
                               int[] counts, int[] dc) {
        var mf = Tables.QUANT4[qp % 6];
        var shift = 15 + qp / 6;
        for (var c = 0; c < 2; c++) {
            var src = c == 0 ? srcU : srcV;
            var pred = c == 0 ? predU : predV;
            for (var blk = 0; blk < 4; blk++) {
                var x = (blk & 1) * 4;
                var y = (blk >> 1) * 4;
                counts[c * 4 + blk] = forwardQuantizeAc4x4(src, srcOffset + y * srcStride + x, srcStride, pred,
                        predOffset + y * predStride + x, predStride, mf, bias, shift, limit, levels,
                        (c * 4 + blk) * 16, dc, c * 4 + blk);
            }
        }
    }

    /**
     * Computes the forward 4x4 transform of the difference of a source and a prediction block,
     * stores its DC coefficient unquantized and quantizes the fifteen AC coefficients, with every
     * intermediate value kept in registers.
     *
     * <p>The result equals {@link #forward4x4} followed by {@link #quantize4x4} with
     * {@code start} 1, the DC coefficient going to {@code dc[dcIndex]}; {@code levels[levelOffset]}
     * is set to zero.
     *
     * @param src the source samples
     * @param srcOffset the index of the source's top-left sample
     * @param srcStride the source row stride
     * @param pred the prediction samples
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param mf the 16 multipliers in raster order
     * @param bias the rounding offset
     * @param shift the right shift
     * @param limit the largest level magnitude the entropy coder can represent
     * @param levels receives the 16 levels in raster order, the DC position zero
     * @param levelOffset the index of the first level
     * @param dc receives the unquantized DC coefficient
     * @param dcIndex the index in {@code dc}
     * @return the number of non-zero AC levels
     */
    int forwardQuantizeAc4x4(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset, int predStride,
                             int[] mf, int bias, int shift, int limit, int[] levels, int levelOffset, int[] dc,
                             int dcIndex) {
        var s0 = srcOffset + 0 * srcStride;
        var q0 = predOffset + 0 * predStride;
        var r00 = (src[s0] & 0xFF) - (pred[q0] & 0xFF);
        var r01 = (src[s0 + 1] & 0xFF) - (pred[q0 + 1] & 0xFF);
        var r02 = (src[s0 + 2] & 0xFF) - (pred[q0 + 2] & 0xFF);
        var r03 = (src[s0 + 3] & 0xFF) - (pred[q0 + 3] & 0xFF);
        var a00 = r00 + r03 + r01 + r02;
        var a01 = 2 * (r00 - r03) + (r01 - r02);
        var a02 = r00 + r03 - r01 - r02;
        var a03 = (r00 - r03) - 2 * (r01 - r02);
        var s1 = srcOffset + 1 * srcStride;
        var q1 = predOffset + 1 * predStride;
        var r10 = (src[s1] & 0xFF) - (pred[q1] & 0xFF);
        var r11 = (src[s1 + 1] & 0xFF) - (pred[q1 + 1] & 0xFF);
        var r12 = (src[s1 + 2] & 0xFF) - (pred[q1 + 2] & 0xFF);
        var r13 = (src[s1 + 3] & 0xFF) - (pred[q1 + 3] & 0xFF);
        var a10 = r10 + r13 + r11 + r12;
        var a11 = 2 * (r10 - r13) + (r11 - r12);
        var a12 = r10 + r13 - r11 - r12;
        var a13 = (r10 - r13) - 2 * (r11 - r12);
        var s2 = srcOffset + 2 * srcStride;
        var q2 = predOffset + 2 * predStride;
        var r20 = (src[s2] & 0xFF) - (pred[q2] & 0xFF);
        var r21 = (src[s2 + 1] & 0xFF) - (pred[q2 + 1] & 0xFF);
        var r22 = (src[s2 + 2] & 0xFF) - (pred[q2 + 2] & 0xFF);
        var r23 = (src[s2 + 3] & 0xFF) - (pred[q2 + 3] & 0xFF);
        var a20 = r20 + r23 + r21 + r22;
        var a21 = 2 * (r20 - r23) + (r21 - r22);
        var a22 = r20 + r23 - r21 - r22;
        var a23 = (r20 - r23) - 2 * (r21 - r22);
        var s3 = srcOffset + 3 * srcStride;
        var q3 = predOffset + 3 * predStride;
        var r30 = (src[s3] & 0xFF) - (pred[q3] & 0xFF);
        var r31 = (src[s3 + 1] & 0xFF) - (pred[q3 + 1] & 0xFF);
        var r32 = (src[s3 + 2] & 0xFF) - (pred[q3 + 2] & 0xFF);
        var r33 = (src[s3 + 3] & 0xFF) - (pred[q3 + 3] & 0xFF);
        var a30 = r30 + r33 + r31 + r32;
        var a31 = 2 * (r30 - r33) + (r31 - r32);
        var a32 = r30 + r33 - r31 - r32;
        var a33 = (r30 - r33) - 2 * (r31 - r32);
        var count = 0;
        {
            var p0 = a00 + a30;
            var p1 = a10 + a20;
            var p2 = a10 - a20;
            var p3 = a00 - a30;
            dc[dcIndex] = p0 + p1;
            levels[levelOffset] = 0;
            var c4 = 2 * p3 + p2;
            var l4 = Math.min(limit, (Math.abs(c4) * mf[4] + bias) >> shift);
            levels[levelOffset + 4] = (l4 ^ (c4 >> 31)) - (c4 >> 31);
            count += l4 != 0 ? 1 : 0;
            var c8 = p0 - p1;
            var l8 = Math.min(limit, (Math.abs(c8) * mf[8] + bias) >> shift);
            levels[levelOffset + 8] = (l8 ^ (c8 >> 31)) - (c8 >> 31);
            count += l8 != 0 ? 1 : 0;
            var c12 = p3 - 2 * p2;
            var l12 = Math.min(limit, (Math.abs(c12) * mf[12] + bias) >> shift);
            levels[levelOffset + 12] = (l12 ^ (c12 >> 31)) - (c12 >> 31);
            count += l12 != 0 ? 1 : 0;
        }
        {
            var p0 = a01 + a31;
            var p1 = a11 + a21;
            var p2 = a11 - a21;
            var p3 = a01 - a31;
            var c1 = p0 + p1;
            var l1 = Math.min(limit, (Math.abs(c1) * mf[1] + bias) >> shift);
            levels[levelOffset + 1] = (l1 ^ (c1 >> 31)) - (c1 >> 31);
            count += l1 != 0 ? 1 : 0;
            var c5 = 2 * p3 + p2;
            var l5 = Math.min(limit, (Math.abs(c5) * mf[5] + bias) >> shift);
            levels[levelOffset + 5] = (l5 ^ (c5 >> 31)) - (c5 >> 31);
            count += l5 != 0 ? 1 : 0;
            var c9 = p0 - p1;
            var l9 = Math.min(limit, (Math.abs(c9) * mf[9] + bias) >> shift);
            levels[levelOffset + 9] = (l9 ^ (c9 >> 31)) - (c9 >> 31);
            count += l9 != 0 ? 1 : 0;
            var c13 = p3 - 2 * p2;
            var l13 = Math.min(limit, (Math.abs(c13) * mf[13] + bias) >> shift);
            levels[levelOffset + 13] = (l13 ^ (c13 >> 31)) - (c13 >> 31);
            count += l13 != 0 ? 1 : 0;
        }
        {
            var p0 = a02 + a32;
            var p1 = a12 + a22;
            var p2 = a12 - a22;
            var p3 = a02 - a32;
            var c2 = p0 + p1;
            var l2 = Math.min(limit, (Math.abs(c2) * mf[2] + bias) >> shift);
            levels[levelOffset + 2] = (l2 ^ (c2 >> 31)) - (c2 >> 31);
            count += l2 != 0 ? 1 : 0;
            var c6 = 2 * p3 + p2;
            var l6 = Math.min(limit, (Math.abs(c6) * mf[6] + bias) >> shift);
            levels[levelOffset + 6] = (l6 ^ (c6 >> 31)) - (c6 >> 31);
            count += l6 != 0 ? 1 : 0;
            var c10 = p0 - p1;
            var l10 = Math.min(limit, (Math.abs(c10) * mf[10] + bias) >> shift);
            levels[levelOffset + 10] = (l10 ^ (c10 >> 31)) - (c10 >> 31);
            count += l10 != 0 ? 1 : 0;
            var c14 = p3 - 2 * p2;
            var l14 = Math.min(limit, (Math.abs(c14) * mf[14] + bias) >> shift);
            levels[levelOffset + 14] = (l14 ^ (c14 >> 31)) - (c14 >> 31);
            count += l14 != 0 ? 1 : 0;
        }
        {
            var p0 = a03 + a33;
            var p1 = a13 + a23;
            var p2 = a13 - a23;
            var p3 = a03 - a33;
            var c3 = p0 + p1;
            var l3 = Math.min(limit, (Math.abs(c3) * mf[3] + bias) >> shift);
            levels[levelOffset + 3] = (l3 ^ (c3 >> 31)) - (c3 >> 31);
            count += l3 != 0 ? 1 : 0;
            var c7 = 2 * p3 + p2;
            var l7 = Math.min(limit, (Math.abs(c7) * mf[7] + bias) >> shift);
            levels[levelOffset + 7] = (l7 ^ (c7 >> 31)) - (c7 >> 31);
            count += l7 != 0 ? 1 : 0;
            var c11 = p0 - p1;
            var l11 = Math.min(limit, (Math.abs(c11) * mf[11] + bias) >> shift);
            levels[levelOffset + 11] = (l11 ^ (c11 >> 31)) - (c11 >> 31);
            count += l11 != 0 ? 1 : 0;
            var c15 = p3 - 2 * p2;
            var l15 = Math.min(limit, (Math.abs(c15) * mf[15] + bias) >> shift);
            levels[levelOffset + 15] = (l15 ^ (c15 >> 31)) - (c15 >> 31);
            count += l15 != 0 ? 1 : 0;
        }
        return count;
    }

    /**
     * Dequantizes a 4x4 block of levels, inverse transforms it and adds it to a block in place,
     * with every intermediate value kept in registers.
     *
     * <p>The result equals {@link #dequantize4x4} followed by {@link #inverse4x4Add}, except
     * that with {@code start} 1 the dequantized DC coefficient is {@code dc} instead of being
     * derived from a level.
     *
     * @param levels the levels in raster order
     * @param levelOffset the index of the first level
     * @param start {@code 1} to take the DC coefficient from {@code dc}, {@code 0} to
     * dequantize it from the levels
     * @param dc the dequantized DC coefficient when {@code start} is 1
     * @param qp the quantization parameter
     * @param dst the samples holding the prediction, receiving the reconstruction
     * @param dstOffset the index of the block's top-left sample
     * @param dstStride the row stride
     */
    void dequantizeInverseAdd4x4(int[] levels, int levelOffset, int start, int dc, int qp, byte[] dst, int dstOffset,
                                 int dstStride) {
        var v = Tables.DEQUANT4[qp % 6];
        var q = qp / 6;
        var d0 = start != 0 ? dc : (levels[levelOffset] * v[0]) << q;
        var d1 = (levels[levelOffset + 1] * v[1]) << q;
        var d2 = (levels[levelOffset + 2] * v[2]) << q;
        var d3 = (levels[levelOffset + 3] * v[3]) << q;
        var d4 = (levels[levelOffset + 4] * v[4]) << q;
        var d5 = (levels[levelOffset + 5] * v[5]) << q;
        var d6 = (levels[levelOffset + 6] * v[6]) << q;
        var d7 = (levels[levelOffset + 7] * v[7]) << q;
        var d8 = (levels[levelOffset + 8] * v[8]) << q;
        var d9 = (levels[levelOffset + 9] * v[9]) << q;
        var d10 = (levels[levelOffset + 10] * v[10]) << q;
        var d11 = (levels[levelOffset + 11] * v[11]) << q;
        var d12 = (levels[levelOffset + 12] * v[12]) << q;
        var d13 = (levels[levelOffset + 13] * v[13]) << q;
        var d14 = (levels[levelOffset + 14] * v[14]) << q;
        var d15 = (levels[levelOffset + 15] * v[15]) << q;
        var e00 = d0 + d2;
        var e01 = d0 - d2;
        var e02 = (d1 >> 1) - d3;
        var e03 = d1 + (d3 >> 1);
        var f00 = e00 + e03;
        var f01 = e01 + e02;
        var f02 = e01 - e02;
        var f03 = e00 - e03;
        var e10 = d4 + d6;
        var e11 = d4 - d6;
        var e12 = (d5 >> 1) - d7;
        var e13 = d5 + (d7 >> 1);
        var f10 = e10 + e13;
        var f11 = e11 + e12;
        var f12 = e11 - e12;
        var f13 = e10 - e13;
        var e20 = d8 + d10;
        var e21 = d8 - d10;
        var e22 = (d9 >> 1) - d11;
        var e23 = d9 + (d11 >> 1);
        var f20 = e20 + e23;
        var f21 = e21 + e22;
        var f22 = e21 - e22;
        var f23 = e20 - e23;
        var e30 = d12 + d14;
        var e31 = d12 - d14;
        var e32 = (d13 >> 1) - d15;
        var e33 = d13 + (d15 >> 1);
        var f30 = e30 + e33;
        var f31 = e31 + e32;
        var f32 = e31 - e32;
        var f33 = e30 - e33;
        {
            var e = f00 + f20;
            var f = f00 - f20;
            var g = (f10 >> 1) - f30;
            var h = f10 + (f30 >> 1);
            var p = dstOffset + 0;
            dst[p] = clip(dst[p], (e + h + 32) >> 6);
            dst[p + dstStride] = clip(dst[p + dstStride], (f + g + 32) >> 6);
            dst[p + 2 * dstStride] = clip(dst[p + 2 * dstStride], (f - g + 32) >> 6);
            dst[p + 3 * dstStride] = clip(dst[p + 3 * dstStride], (e - h + 32) >> 6);
        }
        {
            var e = f01 + f21;
            var f = f01 - f21;
            var g = (f11 >> 1) - f31;
            var h = f11 + (f31 >> 1);
            var p = dstOffset + 1;
            dst[p] = clip(dst[p], (e + h + 32) >> 6);
            dst[p + dstStride] = clip(dst[p + dstStride], (f + g + 32) >> 6);
            dst[p + 2 * dstStride] = clip(dst[p + 2 * dstStride], (f - g + 32) >> 6);
            dst[p + 3 * dstStride] = clip(dst[p + 3 * dstStride], (e - h + 32) >> 6);
        }
        {
            var e = f02 + f22;
            var f = f02 - f22;
            var g = (f12 >> 1) - f32;
            var h = f12 + (f32 >> 1);
            var p = dstOffset + 2;
            dst[p] = clip(dst[p], (e + h + 32) >> 6);
            dst[p + dstStride] = clip(dst[p + dstStride], (f + g + 32) >> 6);
            dst[p + 2 * dstStride] = clip(dst[p + 2 * dstStride], (f - g + 32) >> 6);
            dst[p + 3 * dstStride] = clip(dst[p + 3 * dstStride], (e - h + 32) >> 6);
        }
        {
            var e = f03 + f23;
            var f = f03 - f23;
            var g = (f13 >> 1) - f33;
            var h = f13 + (f33 >> 1);
            var p = dstOffset + 3;
            dst[p] = clip(dst[p], (e + h + 32) >> 6);
            dst[p + dstStride] = clip(dst[p + dstStride], (f + g + 32) >> 6);
            dst[p + 2 * dstStride] = clip(dst[p + 2 * dstStride], (f - g + 32) >> 6);
            dst[p + 3 * dstStride] = clip(dst[p + 3 * dstStride], (e - h + 32) >> 6);
        }
    }

    /**
     * Computes the forward 8x8 transform of the difference of a source and a prediction block.
     *
     * @param src the source samples
     * @param srcOffset the index of the source's top-left sample
     * @param srcStride the source row stride
     * @param pred the prediction samples
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param out receives the 64 coefficients in raster order
     * @param outOffset the index of the first coefficient
     */
    void forward8x8(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset, int predStride,
                    int[] out, int outOffset) {
        for (var i = 0; i < 8; i++) {
            var s = srcOffset + i * srcStride;
            var p = predOffset + i * predStride;
            var o = outOffset + i * 8;
            for (var j = 0; j < 8; j++) {
                out[o + j] = (src[s + j] & 0xFF) - (pred[p + j] & 0xFF);
            }
            forward8(out, o, 1);
        }
        for (var j = 0; j < 8; j++) {
            forward8(out, outOffset + j, 8);
        }
    }

    /**
     * Applies the 8-point forward transform in place.
     *
     * @param v the values
     * @param offset the index of the first value
     * @param step the distance between consecutive values
     */
    private static void forward8(int[] v, int offset, int step) {
        var x0 = v[offset];
        var x1 = v[offset + step];
        var x2 = v[offset + 2 * step];
        var x3 = v[offset + 3 * step];
        var x4 = v[offset + 4 * step];
        var x5 = v[offset + 5 * step];
        var x6 = v[offset + 6 * step];
        var x7 = v[offset + 7 * step];
        var s07 = x0 + x7;
        var s16 = x1 + x6;
        var s25 = x2 + x5;
        var s34 = x3 + x4;
        var a0 = s07 + s34;
        var a1 = s16 + s25;
        var a2 = s07 - s34;
        var a3 = s16 - s25;
        var d07 = x0 - x7;
        var d16 = x1 - x6;
        var d25 = x2 - x5;
        var d34 = x3 - x4;
        var a4 = d16 + d25 + (d07 + (d07 >> 1));
        var a5 = d07 - d34 - (d25 + (d25 >> 1));
        var a6 = d07 + d34 - (d16 + (d16 >> 1));
        var a7 = d16 - d25 + (d34 + (d34 >> 1));
        v[offset] = a0 + a1;
        v[offset + step] = a4 + (a7 >> 2);
        v[offset + 2 * step] = a2 + (a3 >> 1);
        v[offset + 3 * step] = a5 + (a6 >> 2);
        v[offset + 4 * step] = a0 - a1;
        v[offset + 5 * step] = a6 - (a5 >> 2);
        v[offset + 6 * step] = (a2 >> 1) - a3;
        v[offset + 7 * step] = (a4 >> 2) - a7;
    }

    /**
     * Quantizes a 4x4 coefficient block with a dead-zone rounding offset.
     *
     * <p>Each coefficient {@code c} becomes {@code sign(c) * ((|c| * mf + bias) >> shift)},
     * clamped to {@code limit}, written in raster order.
     *
     * @param coef the 16 coefficients in raster order
     * @param coefOffset the index of the first coefficient
     * @param mf the 16 multipliers in raster order
     * @param bias the rounding offset
     * @param shift the right shift
     * @param limit the largest level magnitude the entropy coder can represent
     * @param start {@code 1} to skip the DC coefficient, whose level is left untouched,
     * or {@code 0}
     * @param levels receives the levels in raster order
     * @param levelOffset the index of scan position 0
     * @return the number of non-zero levels
     */
    int quantize4x4(int[] coef, int coefOffset, int[] mf, int bias, int shift, int limit, int start,
                    int[] levels, int levelOffset) {
        var count = 0;
        for (var r = start; r < 16; r++) {
            var c = coef[coefOffset + r];
            var level = Math.min(limit, (Math.abs(c) * mf[r] + bias) >> shift);
            level = (level ^ (c >> 31)) - (c >> 31);
            levels[levelOffset + r] = level;
            count += level != 0 ? 1 : 0;
        }
        return count;
    }

    /**
     * Quantizes an 8x8 coefficient block with a dead-zone rounding offset.
     *
     * @param coef the 64 coefficients in raster order
     * @param coefOffset the index of the first coefficient
     * @param mf the 64 multipliers in raster order
     * @param bias the rounding offset
     * @param shift the right shift
     * @param limit the largest level magnitude the entropy coder can represent
     * @param levels receives the levels in raster order
     * @param levelOffset the index of the first level
     * @return the number of non-zero levels
     */
    int quantize8x8(int[] coef, int coefOffset, int[] mf, int bias, int shift, int limit, int[] levels, int levelOffset) {
        var count = 0;
        for (var r = 0; r < 64; r++) {
            var c = coef[coefOffset + r];
            var level = (int) Math.min(limit, ((long) Math.abs(c) * mf[r] + bias) >> shift);
            level = (level ^ (c >> 31)) - (c >> 31);
            levels[levelOffset + r] = level;
            count += level != 0 ? 1 : 0;
        }
        return count;
    }

    /**
     * Dequantizes a 4x4 block of levels into raster-ordered coefficients (clause 8.5.12.1 with a
     * flat scaling matrix).
     *
     * @param levels the levels in raster order
     * @param levelOffset the index of the first level
     * @param start {@code 1} to skip the DC coefficient, whose output is set to zero, or
     * {@code 0}
     * @param qp the quantization parameter
     * @param out receives the 16 coefficients in raster order
     * @param outOffset the index of the first coefficient
     */
    void dequantize4x4(int[] levels, int levelOffset, int start, int qp, int[] out, int outOffset) {
        var v = Tables.DEQUANT4[qp % 6];
        var shift = qp / 6;
        if (start != 0) {
            out[outOffset] = 0;
        }
        for (var r = start; r < 16; r++) {
            out[outOffset + r] = (levels[levelOffset + r] * v[r]) << shift;
        }
    }

    /**
     * Dequantizes an 8x8 block of levels into raster-ordered coefficients (clause 8.5.13.1 with a
     * flat scaling matrix).
     *
     * @param levels the levels in raster order
     * @param levelOffset the index of the first level
     * @param qp the quantization parameter
     * @param out receives the 64 coefficients in raster order
     * @param outOffset the index of the first coefficient
     */
    void dequantize8x8(int[] levels, int levelOffset, int qp, int[] out, int outOffset) {
        var v = Tables.DEQUANT8[qp % 6];
        var q = qp / 6;
        if (q >= 6) {
            var shift = q - 6;
            for (var r = 0; r < 64; r++) {
                out[outOffset + r] = (levels[levelOffset + r] * v[r]) << shift;
            }
        } else {
            var shift = 6 - q;
            var round = 1 << (5 - q);
            for (var r = 0; r < 64; r++) {
                out[outOffset + r] = (levels[levelOffset + r] * v[r] + round) >> shift;
            }
        }
    }

    /**
     * Clips a sample plus a residual to the 8-bit range.
     *
     * @param sample the prediction sample
     * @param residual the reconstructed residual to add
     * @return the reconstructed sample
     */
    static byte clip(byte sample, int residual) {
        var value = (sample & 0xFF) + residual;
        return (byte) (value < 0 ? 0 : Math.min(value, 255));
    }
}
