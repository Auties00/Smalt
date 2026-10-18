/* Ports JM, the ITU-T / ISO/IEC H.264 reference software (app/lencod/block.c: the coefficient decimation costs and
   the default rounding offsets). Copyright ITU-T and ISO/IEC; royalty-free license with notice. Modified for Smalt. */
package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.internal.H264Dsp;
import com.github.auties00.smalt.video.h264.internal.H264Tables;

import java.util.Arrays;

/**
 * Transforms, quantizes and reconstructs the residual of a macroblock.
 *
 * <p>Each method takes the source and the prediction, fills the levels, counts and coded block
 * pattern bits of a {@link Macroblock}, and writes the reconstruction exactly as a decoder
 * would build it from those levels. Quantization uses a dead-zone rounding offset of one third of
 * a step for intra blocks and one fifth for inter blocks. Inter residuals are decimated: an 8x8
 * block, and then the whole macroblock, whose only levels are a few isolated ones is zeroed,
 * because coding them costs more than the distortion they remove.
 *
 * @implNote This implementation takes the rounding offsets and the decimation scores and
 * thresholds from the ITU-T JM reference encoder ({@code COEFF_COST4x4}, {@code COEFF_COST8x8},
 * {@code LUMA_COEFF_COST}, {@code LUMA_MB_COEFF_COST}), except that the inter offset is a fifth of
 * a step instead of JM's sixth and that
 * {@link com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed#SLOW} zeroes isolated
 * levels only below a block score of 3 and a macroblock score of 2, which cost fewer bits at equal
 * PSNR-Y and SSIM-Y on the test clips (see
 * {@link com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions}).
 */
final class ResidualCoder {
    /**
     * The decimation score of an isolated level of magnitude 1 after a zero-run in a 4x4 block.
     */
    private static final int[] SCORE4 = {3, 2, 2, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    /**
     * The decimation score of an isolated level of magnitude 1 after a zero-run in an 8x8 block.
     */
    private static final int[] SCORE8 = {
            3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    /**
     * The score below which an 8x8 luma block of an inter macroblock is zeroed.
     */
    private final int blockThreshold;

    /**
     * The score below which all luma of an inter macroblock is zeroed.
     */
    private final int macroblockThreshold;

    /**
     * The score below which the chroma AC of one component of an inter macroblock is zeroed.
     */
    private static final int CHROMA_THRESHOLD = 4;

    /**
     * A score larger than every threshold, given to any level of magnitude above 1.
     */
    private static final int KEEP = 1 << 16;

    /**
     * The squared norm of each 4x4 forward basis function in raster order, relating coefficient
     * errors to sample errors.
     */
    private static final int[] NORM4 = {16, 40, 16, 40, 40, 100, 40, 100, 16, 40, 16, 40, 40, 100, 40, 100};

    /**
     * The Lagrange multiplier of rate-distortion optimized quantization in squared-error units per
     * bit, or {@code 0} when it is disabled.
     */
    private double trellisLambda;

    /**
     * The transform kernels.
     */
    private TransformKernels transform;

    /**
     * The pixel kernels, for moving predictions into the reconstruction.
     */
    private PixelKernels pixels;

    /**
     * The largest level magnitude the entropy coder accepts.
     */
    private final int levelLimit;

    /**
     * Scratch coefficients of one 8x8 block in raster order.
     */
    private final int[] coef = new int[64];

    /**
     * Scratch coefficients of one block.
     */
    private final int[] block = new int[64];

    /**
     * Scratch DC matrix.
     */
    private final int[] dc = new int[16];

    /**
     * The decimation score of each 4x4 block of the macroblock being coded.
     */
    private final int[] scores = new int[16];

    /**
     * The level masks of each 4x4 block of the macroblock being probed, see
     * {@link TransformKernels#quantizeLumaMasks}.
     */
    private final int[] masks = new int[16];

    /**
     * The negative-level masks of each 4x4 luma block of the macroblock being probed, see
     * {@link TransformKernels#quantizeLumaMasks}.
     */
    private final int[] signs = new int[16];

    /**
     * Maps the raster positions 0 to 7 of a 4x4 block, as bits, to their zigzag scan positions.
     */
    private static final int[] ZIGZAG_BITS_LOW = zigzagBits(0);

    /**
     * Maps the raster positions 8 to 15 of a 4x4 block, as bits, to their zigzag scan positions.
     */
    private static final int[] ZIGZAG_BITS_HIGH = zigzagBits(8);

    /**
     * Returns the table mapping eight raster position bits of a 4x4 block to zigzag scan bits.
     *
     * @param first the raster position of the lowest bit
     * @return the 256 scan masks
     */
    private static int[] zigzagBits(int first) {
        var scanOf = new int[16];
        for (var k = 0; k < 16; k++) {
            scanOf[H264Tables.ZIGZAG_4X4[k]] = k;
        }
        var table = new int[256];
        for (var bits = 0; bits < 256; bits++) {
            var scan = 0;
            for (var i = 0; i < 8; i++) {
                if ((bits & (1 << i)) != 0) {
                    scan |= 1 << scanOf[first + i];
                }
            }
            table[bits] = scan;
        }
        return table;
    }

    /**
     * Constructs a residual coder.
     *
     * @param transform the transform kernels
     * @param pixels the pixel kernels
     * @param levelLimit the largest level magnitude the entropy coder accepts
     */
    ResidualCoder(TransformKernels transform, PixelKernels pixels, int levelLimit) {
        this(transform, pixels, levelLimit, 4, 5);
    }

    /**
     * Constructs a residual coder with explicit decimation thresholds.
     *
     * @param transform the transform kernels
     * @param pixels the pixel kernels
     * @param levelLimit the largest level magnitude the entropy coder accepts
     * @param blockThreshold the score below which an 8x8 luma block of an inter macroblock is
     * zeroed
     * @param macroblockThreshold the score below which all luma of an inter macroblock is zeroed
     */
    ResidualCoder(TransformKernels transform, PixelKernels pixels, int levelLimit, int blockThreshold,
                  int macroblockThreshold) {
        this.transform = transform;
        this.pixels = pixels;
        this.levelLimit = levelLimit;
        this.blockThreshold = blockThreshold;
        this.macroblockThreshold = macroblockThreshold;
    }

    /**
     * Replaces the kernels the coder computes with, between two pictures.
     *
     * @param transform the transform kernels
     * @param pixels the pixel kernels
     */
    void useKernels(TransformKernels transform, PixelKernels pixels) {
        this.transform = transform;
        this.pixels = pixels;
    }

    /**
     * Enables or disables rate-distortion optimized quantization of 4x4 luma blocks.
     *
     * @param lambda the Lagrange multiplier in squared-error units per bit, or {@code 0} to
     * disable
     */
    void setTrellis(double lambda) {
        this.trellisLambda = lambda;
    }

    /**
     * Returns the dead-zone rounding offset of a quantizer.
     *
     * @param shift the quantization shift
     * @param intra whether the block is intra
     * @return the rounding offset
     */
    static int bias(int shift, boolean intra) {
        return intra ? (1 << shift) / 3 : (1 << shift) / 5;
    }

    /**
     * Codes the luma residual of an inter macroblock with the 4x4 transform.
     *
     * @param mb the macroblock receiving levels, counts and luma coded block pattern
     * @param src the source luma samples
     * @param srcOffset the index of the macroblock's top-left source sample
     * @param srcStride the source row stride
     * @param pred the prediction
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param rec the reconstruction destination
     * @param recOffset the index of the macroblock's top-left reconstructed sample
     * @param recStride the reconstruction row stride
     * @param qp the luma quantizer
     */
    void lumaInter(Macroblock mb, byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset,
                   int predStride, byte[] rec, int recOffset, int recStride, int qp) {
        quantizeLumaInter(mb, src, srcOffset, srcStride, pred, predOffset, predStride, qp);
        reconstructLumaInter(mb, pred, predOffset, predStride, rec, recOffset, recStride, qp);
    }

    /**
     * Transforms and quantizes the luma residual of an inter macroblock with the 4x4 transform,
     * like {@link #lumaInter} but without reconstructing it.
     *
     * @implNote This implementation, when decimating without rate-distortion optimized
     * quantization, first computes only the positions and signs of the non-zero levels
     * ({@link TransformKernels#quantizeLumaMasks}); when no level exceeds magnitude 1, which is
     * the common case at the quantizers of compressed video, it decimates from those masks and
     * writes the kept levels of magnitude 1 directly, and it runs the full quantization only
     * otherwise. The result is the same.
     *
     * @param mb the macroblock receiving levels, counts and luma coded block pattern
     * @param src the source luma samples
     * @param srcOffset the index of the macroblock's top-left source sample
     * @param srcStride the source row stride
     * @param pred the prediction
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param qp the luma quantizer
     */
    void quantizeLumaInter(Macroblock mb, byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset,
                           int predStride, int qp) {
        var shift = 15 + qp / 6;
        var bias = bias(shift, false);
        var levels = mb.luma;
        var total = 0;
        if (trellisLambda > 0) {
            for (var blk = 0; blk < 16; blk++) {
                var x = H264Tables.BLOCK_X[blk] * 4;
                var y = H264Tables.BLOCK_Y[blk] * 4;
                mb.lumaCount[blk] = optimizedQuantize4x4(src, srcOffset + y * srcStride + x, srcStride, pred,
                        predOffset + y * predStride + x, predStride, qp, false, levels, blk * 16);
            }
        } else if (transform.quantizeLumaMasks(src, srcOffset, srcStride, pred, predOffset, predStride, qp,
                masks, signs)) {
            var cbp = maskedLumaCbp();
            Arrays.fill(levels, 0);
            for (var blk = 0; blk < 16; blk++) {
                if ((cbp & (1 << (blk >> 2))) == 0) {
                    mb.lumaCount[blk] = 0;
                    continue;
                }
                var nonZero = masks[blk] & 0xFFFF;
                mb.lumaCount[blk] = Integer.bitCount(nonZero);
                var negative = signs[blk];
                var base = blk * 16;
                while (nonZero != 0) {
                    var position = Integer.numberOfTrailingZeros(nonZero);
                    levels[base + position] = 1 - (((negative >>> position) & 1) << 1);
                    nonZero &= nonZero - 1;
                }
            }
            mb.cbp = (mb.cbp & ~15) | cbp;
            return;
        } else {
            transform.forwardQuantizeLuma(src, srcOffset, srcStride, pred, predOffset, predStride, qp, bias,
                    levelLimit, levels, mb.lumaCount);
        }
        for (var blk = 0; blk < 16; blk++) {
            scores[blk] = mb.lumaCount[blk] == 0 ? 0 : score4(levels, blk * 16, 0);
        }
        var cbp = 0;
        for (var b8 = 0; b8 < 4; b8++) {
            var nonZero = mb.lumaCount[b8 * 4] + mb.lumaCount[b8 * 4 + 1] + mb.lumaCount[b8 * 4 + 2]
                    + mb.lumaCount[b8 * 4 + 3];
            if (nonZero == 0) {
                continue;
            }
            var score = scores[b8 * 4] + scores[b8 * 4 + 1] + scores[b8 * 4 + 2] + scores[b8 * 4 + 3];
            if (score < blockThreshold) {
                clearLuma4x4(mb, b8);
                continue;
            }
            cbp |= 1 << b8;
            total += score;
        }
        if (cbp != 0 && total < macroblockThreshold) {
            for (var b8 = 0; b8 < 4; b8++) {
                clearLuma4x4(mb, b8);
            }
            cbp = 0;
        }
        mb.cbp = (mb.cbp & ~15) | cbp;
    }

    /**
     * Decides whether the luma residual of an inter macroblock keeps any level after quantization
     * and decimation, without producing the levels.
     *
     * <p>The result describes the levels {@link #quantizeLumaInter} would produce with decimation,
     * which must not use rate-distortion optimized quantization: it is {@code true} exactly when
     * that luma coded block pattern is non-zero.
     *
     * @param src the source luma samples
     * @param srcOffset the index of the macroblock's top-left source sample
     * @param srcStride the source row stride
     * @param pred the prediction
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param qp the luma quantizer
     * @return whether any luma level is kept
     */
    boolean probeLumaInter(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset, int predStride,
                           int qp) {
        if (!transform.quantizeLumaMasks(src, srcOffset, srcStride, pred, predOffset, predStride, qp, masks, signs)) {
            return true;
        }
        return maskedLumaCbp() != 0;
    }

    /**
     * Returns the luma coded block pattern that decimation leaves from the complete level masks
     * of {@link #masks}, all of whose levels have magnitude 1.
     *
     * @return the luma coded block pattern
     */
    private int maskedLumaCbp() {
        var cbp = 0;
        var total = 0;
        for (var b8 = 0; b8 < 4; b8++) {
            var nonZero = 0;
            var score = 0;
            for (var i = 0; i < 4; i++) {
                var blockMasks = masks[b8 * 4 + i];
                var count = Integer.bitCount(blockMasks & 0xFFFF);
                if (count != 0) {
                    nonZero += count;
                    score += maskScore(blockMasks, 0);
                }
            }
            if (nonZero == 0 || score < blockThreshold) {
                continue;
            }
            cbp |= 1 << b8;
            total += score;
        }
        return total >= macroblockThreshold ? cbp : 0;
    }

    /**
     * Returns the decimation score of a 4x4 block from its level masks, equal to
     * {@link #score4(int[], int, int)} on the levels the masks describe.
     *
     * @param blockMasks the raster non-zero mask in the low 16 bits and the raster mask of
     * magnitudes above 1 in the high 16 bits, both clear at scan positions
     * before {@code start}
     * @param start the first scan position, 0 or 1
     * @return the score, {@link #KEEP} when a level exceeds magnitude 1
     */
    private static int maskScore(int blockMasks, int start) {
        if ((blockMasks >>> 16) != 0) {
            return KEEP;
        }
        var scan = ZIGZAG_BITS_LOW[blockMasks & 0xFF] | ZIGZAG_BITS_HIGH[(blockMasks >>> 8) & 0xFF];
        var score = 0;
        var previous = start - 1;
        while (scan != 0) {
            var position = Integer.numberOfTrailingZeros(scan);
            score += SCORE4[position - previous - 1];
            previous = position;
            scan &= scan - 1;
        }
        return score;
    }

    /**
     * Writes the luma reconstruction of an inter macroblock whose 4x4 levels
     * {@link #quantizeLumaInter} computed: the prediction plus the decoded residual.
     *
     * @param mb the macroblock with its luma levels, counts and coded block pattern
     * @param pred the prediction
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param rec the reconstruction destination
     * @param recOffset the index of the macroblock's top-left reconstructed sample
     * @param recStride the reconstruction row stride
     * @param qp the luma quantizer
     */
    void reconstructLumaInter(Macroblock mb, byte[] pred, int predOffset, int predStride, byte[] rec, int recOffset,
                              int recStride, int qp) {
        if (pred != rec || predOffset != recOffset) {
            pixels.copy(pred, predOffset, predStride, rec, recOffset, recStride, 16, 16);
        }
        if ((mb.cbp & 15) == 0) {
            return;
        }
        var levels = mb.luma;
        for (var blk = 0; blk < 16; blk++) {
            if (mb.lumaCount[blk] == 0) {
                continue;
            }
            var x = H264Tables.BLOCK_X[blk] * 4;
            var y = H264Tables.BLOCK_Y[blk] * 4;
            transform.dequantizeInverseAdd4x4(levels, blk * 16, 0, 0, qp, rec, recOffset + y * recStride + x, recStride);
        }
    }

    /**
     * Codes the luma residual of an inter macroblock with the 8x8 transform.
     *
     * @param mb the macroblock receiving levels, counts and luma coded block pattern
     * @param src the source luma samples
     * @param srcOffset the index of the macroblock's top-left source sample
     * @param srcStride the source row stride
     * @param pred the prediction
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param rec the reconstruction destination
     * @param recOffset the index of the macroblock's top-left reconstructed sample
     * @param recStride the reconstruction row stride
     * @param qp the luma quantizer
     */
    void lumaInter8x8(Macroblock mb, byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset,
                      int predStride, byte[] rec, int recOffset, int recStride, int qp) {
        var mf = Tables.QUANT8[qp % 6];
        var shift = 16 + qp / 6;
        var bias = bias(shift, false);
        var levels = mb.luma;
        var cbp = 0;
        var total = 0;
        for (var b8 = 0; b8 < 4; b8++) {
            var x = (b8 & 1) * 8;
            var y = (b8 >> 1) * 8;
            transform.forward8x8(src, srcOffset + y * srcStride + x, srcStride, pred, predOffset + y * predStride + x,
                    predStride, coef, 0);
            var count = transform.quantize8x8(coef, 0, mf, bias, shift, levelLimit, levels, b8 * 64);
            var score = count == 0 ? 0 : score8(levels, b8 * 64);
            if (count == 0 || score < blockThreshold) {
                Arrays.fill(levels, b8 * 64, b8 * 64 + 64, 0);
                count = 0;
            } else {
                cbp |= 1 << b8;
                total += score;
            }
            for (var i = 0; i < 4; i++) {
                mb.lumaCount[b8 * 4 + i] = count;
            }
        }
        if (cbp != 0 && total < macroblockThreshold) {
            Arrays.fill(levels, 0);
            Arrays.fill(mb.lumaCount, 0);
            cbp = 0;
        }
        mb.cbp = (mb.cbp & ~15) | cbp;
        if (pred != rec || predOffset != recOffset) {
            pixels.copy(pred, predOffset, predStride, rec, recOffset, recStride, 16, 16);
        }
        for (var b8 = 0; b8 < 4; b8++) {
            if ((cbp & (1 << b8)) == 0) {
                continue;
            }
            var x = (b8 & 1) * 8;
            var y = (b8 >> 1) * 8;
            transform.dequantize8x8(levels, b8 * 64, qp, block, 0);
            H264Dsp.INSTANCE.idct8x8Add(block, 0, rec, recOffset + y * recStride + x, recStride);
        }
    }

    /**
     * Codes the luma residual of an Intra_16x16 macroblock whose prediction is already in the
     * reconstruction.
     *
     * @param mb the macroblock receiving levels, counts and luma coded block pattern
     * @param src the source luma samples
     * @param srcOffset the index of the macroblock's top-left source sample
     * @param srcStride the source row stride
     * @param rec the reconstruction, holding the prediction
     * @param recOffset the index of the macroblock's top-left reconstructed sample
     * @param recStride the reconstruction row stride
     * @param qp the luma quantizer
     */
    void lumaIntra16x16(Macroblock mb, byte[] src, int srcOffset, int srcStride, byte[] rec, int recOffset,
                        int recStride, int qp) {
        var mf = Tables.QUANT4[qp % 6];
        var shift = 15 + qp / 6;
        var bias = bias(shift, true);
        var levels = mb.luma;
        transform.forwardQuantizeLumaAc(src, srcOffset, srcStride, rec, recOffset, recStride, qp, bias, levelLimit,
                levels, mb.lumaCount, dc);
        hadamard4x4(dc);
        var dcCount = 0;
        var dcMf = mf[0];
        for (var r = 0; r < 16; r++) {
            var value = dc[r] / 2;
            var level = Math.min(levelLimit, (Math.abs(value) * dcMf + 2 * bias) >> (shift + 1));
            level = value < 0 ? -level : level;
            mb.lumaDc[r] = level;
            dcCount += level != 0 ? 1 : 0;
        }
        mb.lumaDcCount = dcCount;
        var acTotal = 0;
        for (var blk = 0; blk < 16; blk++) {
            acTotal += mb.lumaCount[blk];
        }
        if (acTotal == 0) {
            mb.cbp &= ~15;
        } else {
            mb.cbp |= 15;
        }
        reconstructIntra16x16(mb, rec, recOffset, recStride, qp);
    }

    /**
     * Rebuilds the Intra_16x16 luma reconstruction from the levels of a macroblock, adding the
     * residual to the prediction held in the reconstruction.
     *
     * @param mb the macroblock
     * @param rec the reconstruction, holding the prediction
     * @param recOffset the index of the macroblock's top-left reconstructed sample
     * @param recStride the reconstruction row stride
     * @param qp the luma quantizer
     */
    void reconstructIntra16x16(Macroblock mb, byte[] rec, int recOffset, int recStride, int qp) {
        if (mb.lumaDcCount == 0 && (mb.cbp & 15) == 0) {
            return;
        }
        System.arraycopy(mb.lumaDc, 0, dc, 0, 16);
        hadamard4x4(dc);
        var scale = 16 * Tables.DEQUANT4[qp % 6][0];
        var q = qp / 6;
        for (var i = 0; i < 16; i++) {
            if (q >= 6) {
                dc[i] = (dc[i] * scale) << (q - 6);
            } else {
                dc[i] = (dc[i] * scale + (1 << (5 - q))) >> (6 - q);
            }
        }
        var ac = (mb.cbp & 15) != 0;
        for (var blk = 0; blk < 16; blk++) {
            var bx = H264Tables.BLOCK_X[blk];
            var by = H264Tables.BLOCK_Y[blk];
            var d = recOffset + by * 4 * recStride + bx * 4;
            if (ac && mb.lumaCount[blk] != 0) {
                transform.dequantizeInverseAdd4x4(mb.luma, blk * 16, 1, dc[by * 4 + bx], qp, rec, d, recStride);
            } else {
                H264Dsp.INSTANCE.idct4x4DcAdd(dc, by * 4 + bx, rec, d, recStride);
            }
        }
    }

    /**
     * Codes one 4x4 luma block of an Intra_4x4 macroblock whose prediction is already in the
     * reconstruction, and reconstructs it.
     *
     * @param mb the macroblock receiving the block's levels and count
     * @param blk the {@code luma4x4BlkIdx}
     * @param src the source luma samples
     * @param srcOffset the index of the block's top-left source sample
     * @param srcStride the source row stride
     * @param rec the reconstruction, holding the prediction
     * @param recOffset the index of the block's top-left reconstructed sample
     * @param recStride the reconstruction row stride
     * @param qp the luma quantizer
     * @return the number of non-zero levels
     */
    int lumaIntra4x4(Macroblock mb, int blk, byte[] src, int srcOffset, int srcStride, byte[] rec, int recOffset,
                     int recStride, int qp) {
        var shift = 15 + qp / 6;
        var count = trellisLambda > 0
                ? optimizedQuantize4x4(src, srcOffset, srcStride, rec, recOffset, recStride, qp, true, mb.luma, blk * 16)
                : transform.forwardQuantize4x4(src, srcOffset, srcStride, rec, recOffset, recStride,
                Tables.QUANT4[qp % 6], bias(shift, true), shift, levelLimit, mb.luma, blk * 16);
        mb.lumaCount[blk] = count;
        if (count != 0) {
            transform.dequantizeInverseAdd4x4(mb.luma, blk * 16, 0, 0, qp, rec, recOffset, recStride);
        }
        return count;
    }

    /**
     * Codes one 8x8 luma block of an Intra_8x8 macroblock whose prediction is already in the
     * reconstruction, and reconstructs it.
     *
     * @param mb the macroblock receiving the block's levels and counts
     * @param b8 the {@code luma8x8BlkIdx}
     * @param src the source luma samples
     * @param srcOffset the index of the block's top-left source sample
     * @param srcStride the source row stride
     * @param rec the reconstruction, holding the prediction
     * @param recOffset the index of the block's top-left reconstructed sample
     * @param recStride the reconstruction row stride
     * @param qp the luma quantizer
     * @return the number of non-zero levels
     */
    int lumaIntra8x8(Macroblock mb, int b8, byte[] src, int srcOffset, int srcStride, byte[] rec, int recOffset,
                     int recStride, int qp) {
        var shift = 16 + qp / 6;
        transform.forward8x8(src, srcOffset, srcStride, rec, recOffset, recStride, block, 0);
        var count = transform.quantize8x8(block, 0, Tables.QUANT8[qp % 6], bias(shift, true), shift, levelLimit,
                mb.luma, b8 * 64);
        for (var i = 0; i < 4; i++) {
            mb.lumaCount[b8 * 4 + i] = count;
        }
        if (count != 0) {
            transform.dequantize8x8(mb.luma, b8 * 64, qp, block, 0);
            H264Dsp.INSTANCE.idct8x8Add(block, 0, rec, recOffset, recStride);
        }
        return count;
    }

    /**
     * Codes the chroma residual of a macroblock and reconstructs both components.
     *
     * @param mb the macroblock receiving levels, counts and chroma coded block pattern
     * @param srcU the source Cb samples
     * @param srcV the source Cr samples
     * @param srcOffset the index of the macroblock's top-left source chroma sample
     * @param srcStride the source chroma row stride
     * @param predU the Cb prediction
     * @param predV the Cr prediction
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param recU the Cb reconstruction
     * @param recV the Cr reconstruction
     * @param recOffset the index of the macroblock's top-left reconstructed chroma sample
     * @param recStride the reconstruction row stride
     * @param qpc the chroma quantizer
     * @param intra whether the macroblock is intra, which selects the rounding offset and
     * disables decimation
     */
    void chroma(Macroblock mb, byte[] srcU, byte[] srcV, int srcOffset, int srcStride, byte[] predU, byte[] predV,
                int predOffset, int predStride, byte[] recU, byte[] recV, int recOffset, int recStride, int qpc,
                boolean intra) {
        quantizeChroma(mb, srcU, srcV, srcOffset, srcStride, predU, predV, predOffset, predStride, qpc, intra);
        if (predU != recU || predOffset != recOffset || predStride != recStride) {
            pixels.copy(predU, predOffset, predStride, recU, recOffset, recStride, 8, 8);
            pixels.copy(predV, predOffset, predStride, recV, recOffset, recStride, 8, 8);
        }
        reconstructChroma(mb, recU, recV, recOffset, recStride, qpc);
    }

    /**
     * Transforms and quantizes the chroma residual of a macroblock, like {@link #chroma} but
     * without reconstructing it.
     *
     * @implNote This implementation, for an inter macroblock, first computes only the positions
     * of the non-zero AC levels and the DC coefficients
     * ({@link TransformKernels#quantizeChromaMasks}); when decimation removes every AC level of
     * both components, which is almost always the case, only the DC levels are quantized, and the
     * full quantization runs otherwise. The result is the same.
     *
     * @param mb the macroblock receiving levels, counts and chroma coded block pattern
     * @param srcU the source Cb samples
     * @param srcV the source Cr samples
     * @param srcOffset the index of the macroblock's top-left source chroma sample
     * @param srcStride the source chroma row stride
     * @param predU the Cb prediction
     * @param predV the Cr prediction
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param qpc the chroma quantizer
     * @param intra whether the macroblock is intra, which selects the rounding offset and
     * disables decimation
     */
    void quantizeChroma(Macroblock mb, byte[] srcU, byte[] srcV, int srcOffset, int srcStride, byte[] predU,
                        byte[] predV, int predOffset, int predStride, int qpc, boolean intra) {
        var mf = Tables.QUANT4[qpc % 6];
        var shift = 15 + qpc / 6;
        var bias = bias(shift, intra);
        var hasAc = false;
        var hasDc = false;
        if (!intra && transform.quantizeChromaMasks(srcU, srcV, srcOffset, srcStride, predU, predV, predOffset,
                predStride, qpc, masks, dc) && chromaAcDecimated(0) && chromaAcDecimated(1)) {
            Arrays.fill(mb.chroma, 0);
            Arrays.fill(mb.chromaCount, 0);
            for (var c = 0; c < 2; c++) {
                hasDc |= quantizeChromaDcs(mb, c, mf[0], bias, shift) != 0;
            }
            mb.cbp = (mb.cbp & 15) | ((hasDc ? 1 : 0) << 4);
            return;
        }
        transform.forwardQuantizeChroma(srcU, srcV, srcOffset, srcStride, predU, predV, predOffset, predStride, qpc,
                bias, levelLimit, mb.chroma, mb.chromaCount, dc);
        for (var c = 0; c < 2; c++) {
            var acCount = 0;
            var acScore = 0;
            for (var blk = 0; blk < 4; blk++) {
                var count = mb.chromaCount[c * 4 + blk];
                acCount += count;
                if (count != 0) {
                    acScore += score4(mb.chroma, (c * 4 + blk) * 16, 1);
                }
            }
            if (!intra && acCount != 0 && acScore < CHROMA_THRESHOLD) {
                for (var blk = 0; blk < 4; blk++) {
                    var index = (c * 4 + blk) * 16;
                    Arrays.fill(mb.chroma, index, index + 16, 0);
                    mb.chromaCount[c * 4 + blk] = 0;
                }
                acCount = 0;
            }
            hasAc |= acCount != 0;
            hasDc |= quantizeChromaDcs(mb, c, mf[0], bias, shift) != 0;
        }
        var chromaCbp = hasAc ? 2 : hasDc ? 1 : 0;
        if (chromaCbp < 2) {
            Arrays.fill(mb.chroma, 0);
            Arrays.fill(mb.chromaCount, 0);
        }
        mb.cbp = (mb.cbp & 15) | (chromaCbp << 4);
    }

    /**
     * Adds the chroma residual of a macroblock's levels to the prediction held in the
     * reconstruction.
     *
     * @param mb the macroblock
     * @param recU the Cb reconstruction, holding the prediction
     * @param recV the Cr reconstruction, holding the prediction
     * @param recOffset the index of the macroblock's top-left reconstructed chroma sample
     * @param recStride the reconstruction row stride
     * @param qpc the chroma quantizer
     */
    void reconstructChroma(Macroblock mb, byte[] recU, byte[] recV, int recOffset, int recStride, int qpc) {
        var chromaCbp = mb.cbp >> 4;
        if (chromaCbp == 0) {
            return;
        }
        var scale = 16 * Tables.DEQUANT4[qpc % 6][0];
        var q = qpc / 6;
        for (var c = 0; c < 2; c++) {
            var rec = c == 0 ? recU : recV;
            var l0 = mb.chromaDc[c * 4];
            var l1 = mb.chromaDc[c * 4 + 1];
            var l2 = mb.chromaDc[c * 4 + 2];
            var l3 = mb.chromaDc[c * 4 + 3];
            dc[0] = (((l0 + l1 + l2 + l3) * scale) << q) >> 5;
            dc[1] = (((l0 - l1 + l2 - l3) * scale) << q) >> 5;
            dc[2] = (((l0 + l1 - l2 - l3) * scale) << q) >> 5;
            dc[3] = (((l0 - l1 - l2 + l3) * scale) << q) >> 5;
            for (var blk = 0; blk < 4; blk++) {
                var d = recOffset + (blk >> 1) * 4 * recStride + (blk & 1) * 4;
                if (chromaCbp == 2 && mb.chromaCount[c * 4 + blk] != 0) {
                    transform.dequantizeInverseAdd4x4(mb.chroma, (c * 4 + blk) * 16, 1, dc[blk], qpc, rec, d, recStride);
                } else {
                    H264Dsp.INSTANCE.idct4x4DcAdd(dc, blk, rec, d, recStride);
                }
            }
        }
    }

    /**
     * Returns whether the AC levels of one chroma component whose masks
     * {@link TransformKernels#quantizeChromaMasks} computed are all zero after inter decimation.
     *
     * @param component 0 for Cb, 1 for Cr
     * @return whether the component codes no AC level
     */
    private boolean chromaAcDecimated(int component) {
        var score = 0;
        for (var blk = 0; blk < 4; blk++) {
            var blockMasks = masks[component * 4 + blk];
            if ((blockMasks & 0xFFFF) != 0) {
                score += maskScore(blockMasks, 1);
            }
        }
        return score < CHROMA_THRESHOLD;
    }

    /**
     * Transforms the four DC coefficients of one chroma component with the 2x2 Hadamard transform
     * and quantizes them into a macroblock, setting its DC count.
     *
     * @param mb the macroblock
     * @param component 0 for Cb, 1 for Cr
     * @param mf the multiplier of the DC position
     * @param bias the rounding offset of the AC positions
     * @param shift the quantization shift of the AC positions
     * @return the number of non-zero DC levels
     */
    private int quantizeChromaDcs(Macroblock mb, int component, int mf, int bias, int shift) {
        var e = component * 4;
        var d0 = dc[e] + dc[e + 1] + dc[e + 2] + dc[e + 3];
        var d1 = dc[e] - dc[e + 1] + dc[e + 2] - dc[e + 3];
        var d2 = dc[e] + dc[e + 1] - dc[e + 2] - dc[e + 3];
        var d3 = dc[e] - dc[e + 1] - dc[e + 2] + dc[e + 3];
        var dcCount = quantizeChromaDc(mb, e, d0, mf, bias, shift) + quantizeChromaDc(mb, e + 1, d1, mf, bias, shift)
                + quantizeChromaDc(mb, e + 2, d2, mf, bias, shift) + quantizeChromaDc(mb, e + 3, d3, mf, bias, shift);
        mb.chromaDcCount[component] = dcCount;
        return dcCount;
    }

    /**
     * Quantizes one chroma DC coefficient into a macroblock.
     *
     * @param mb the macroblock
     * @param index the index in {@link Macroblock#chromaDc}
     * @param value the Hadamard-transformed DC value
     * @param mf the multiplier of the DC position
     * @param bias the rounding offset of the AC positions
     * @param shift the quantization shift of the AC positions
     * @return {@code 1} when the level is non-zero, {@code 0} otherwise
     */
    private int quantizeChromaDc(Macroblock mb, int index, int value, int mf, int bias, int shift) {
        var level = Math.min(levelLimit, (Math.abs(value) * mf + 2 * bias) >> (shift + 1));
        mb.chromaDc[index] = value < 0 ? -level : level;
        return level != 0 ? 1 : 0;
    }

    /**
     * Transforms and quantizes one 4x4 block choosing every level by rate-distortion cost.
     *
     * <p>Coefficients are first rounded to the nearest level; then, from the highest frequency
     * down, each non-zero level is lowered by one when that saves more weighted bits than it
     * adds squared error, estimated with the basis norms and a simple rate model of CABAC and
     * CAVLC levels; finally the whole block is zeroed when that is cheaper than coding it.
     *
     * @implNote This implementation is a greedy simplification of the rate-distortion optimized
     * quantization of the ITU-T JM and HEVC HM reference encoders, with a fixed rate model
     * instead of the entropy coder's context states.
     *
     * @param src the source samples
     * @param srcOffset the index of the block's top-left source sample
     * @param srcStride the source row stride
     * @param pred the prediction samples
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param qp the quantizer
     * @param intra whether the block is intra, which makes zeroing it less attractive
     * @param levels receives the 16 levels in raster order
     * @param levelOffset the index of the first level
     * @return the number of non-zero levels
     */
    private int optimizedQuantize4x4(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset,
                                     int predStride, int qp, boolean intra, int[] levels, int levelOffset) {
        transform.forward4x4(src, srcOffset, srcStride, pred, predOffset, predStride, block, 0);
        var mf = Tables.QUANT4[qp % 6];
        var shift = 15 + qp / 6;
        var half = 1 << (shift - 1);
        var lambda = trellisLambda;
        var scale = (double) (1L << shift);
        double codedCost = 0;
        double zeroCost = 0;
        var count = 0;
        for (var k = 15; k >= 0; k--) {
            var r = H264Tables.ZIGZAG_4X4[k];
            var c = block[r];
            if (c == 0) {
                levels[levelOffset + r] = 0;
                continue;
            }
            var magnitude = Math.abs(c);
            var level = Math.min(levelLimit, (magnitude * mf[r] + half) >> shift);
            var norm = NORM4[r];
            double zero = (double) magnitude * magnitude / norm;
            zeroCost += zero;
            if (level == 0) {
                levels[levelOffset + r] = 0;
                codedCost += zero;
                continue;
            }
            var step = scale / mf[r];
            var best = level;
            var bestCost = distortion(magnitude, level, step, norm) + lambda * levelBits(level);
            var lower = level - 1;
            var lowerCost = lower == 0 ? zero + lambda * 0.5 : distortion(magnitude, lower, step, norm)
                    + lambda * levelBits(lower);
            if (lowerCost < bestCost) {
                best = lower;
                bestCost = lowerCost;
            }
            codedCost += bestCost;
            levels[levelOffset + r] = c < 0 ? -best : best;
            count += best != 0 ? 1 : 0;
        }
        if (count != 0 && zeroCost <= codedCost + lambda * (intra ? 1 : 3)) {
            for (var r = 0; r < 16; r++) {
                levels[levelOffset + r] = 0;
            }
            return 0;
        }
        return count;
    }

    /**
     * Returns the squared sample error of reconstructing a coefficient magnitude from a level.
     *
     * @param magnitude the coefficient magnitude
     * @param level the level magnitude
     * @param step the coefficient step of one level
     * @param norm the squared norm of the coefficient's basis function
     * @return the squared error
     */
    private static double distortion(int magnitude, int level, double step, int norm) {
        var error = magnitude - level * step;
        return error * error / norm;
    }

    /**
     * Returns the estimated number of bits of a non-zero level magnitude.
     *
     * @param level the level magnitude
     * @return the bits, including significance and sign
     */
    private static double levelBits(int level) {
        if (level == 0) {
            return 0.5;
        }
        if (level == 1) {
            return 3;
        }
        return 4 + 2 * (31 - Integer.numberOfLeadingZeros(level));
    }

    /**
     * Returns the largest SAD of a 4x4 inter luma residual block that is guaranteed to quantize to
     * all-zero levels at a quantizer.
     *
     * <p>Every coefficient of the 4x4 core transform is a weighted sum of the residual with
     * weights of magnitude at most 1, 2 or 4 depending on its position, so its magnitude is at
     * most the SAD times that weight; the bound compares the largest weighted multiplier with the
     * dead zone.
     *
     * @param qp the quantizer
     * @return the SAD bound, possibly negative
     */
    static int zeroSad4x4(int qp) {
        var mf = Tables.QUANT4[qp % 6];
        var shift = 15 + qp / 6;
        var weighted = Math.max(mf[0], Math.max(2 * mf[1], 4 * mf[5]));
        return ((1 << shift) - bias(shift, false) - 1) / weighted;
    }

    /**
     * Returns the largest SAD of an 8x8 inter chroma residual block that is guaranteed to quantize
     * to all-zero DC and AC levels at a chroma quantizer.
     *
     * @param qpc the chroma quantizer
     * @return the SAD bound, possibly negative
     */
    static int zeroSadChroma(int qpc) {
        var mf = Tables.QUANT4[qpc % 6];
        var shift = 15 + qpc / 6;
        var bias = bias(shift, false);
        var dcBound = ((1 << (shift + 1)) - 2 * bias - 1) / mf[0];
        return Math.min(dcBound, zeroSad4x4(qpc));
    }

    /**
     * Applies the 4x4 Hadamard transform of equation 8-320 in place to a raster-ordered matrix.
     *
     * @param m the 16 values
     */
    static void hadamard4x4(int[] m) {
        for (var i = 0; i < 4; i++) {
            var o = i * 4;
            var a = m[o];
            var b = m[o + 1];
            var c = m[o + 2];
            var d = m[o + 3];
            m[o] = a + b + c + d;
            m[o + 1] = a + b - c - d;
            m[o + 2] = a - b - c + d;
            m[o + 3] = a - b + c - d;
        }
        for (var j = 0; j < 4; j++) {
            var a = m[j];
            var b = m[j + 4];
            var c = m[j + 8];
            var d = m[j + 12];
            m[j] = a + b + c + d;
            m[j + 4] = a + b - c - d;
            m[j + 8] = a - b - c + d;
            m[j + 12] = a - b + c - d;
        }
    }

    /**
     * Returns the decimation score of a 4x4 block's levels.
     *
     * @param levels the levels in raster order
     * @param offset the index of the first level
     * @param start the first scan position
     * @return the score, {@link #KEEP} or more when a level exceeds magnitude 1
     */
    private static int score4(int[] levels, int offset, int start) {
        var score = 0;
        var run = 0;
        for (var k = start; k < 16; k++) {
            var level = levels[offset + H264Tables.ZIGZAG_4X4[k]];
            if (level == 0) {
                run++;
            } else if (level == 1 || level == -1) {
                score += SCORE4[run];
                run = 0;
            } else {
                return KEEP;
            }
        }
        return score;
    }

    /**
     * Returns the decimation score of an 8x8 block's levels.
     *
     * @param levels the levels in raster order
     * @param offset the index of the first level
     * @return the score, {@link #KEEP} or more when a level exceeds magnitude 1
     */
    private static int score8(int[] levels, int offset) {
        var score = 0;
        var run = 0;
        for (var k = 0; k < 64; k++) {
            var level = levels[offset + H264Tables.ZIGZAG_8X8[k]];
            if (level == 0) {
                run++;
            } else if (level == 1 || level == -1) {
                score += SCORE8[run];
                run = 0;
            } else {
                return KEEP;
            }
        }
        return score;
    }

    /**
     * Zeroes the levels and counts of the four 4x4 blocks of one 8x8 luma block.
     *
     * @param mb the macroblock
     * @param b8 the {@code luma8x8BlkIdx}
     */
    private static void clearLuma4x4(Macroblock mb, int b8) {
        Arrays.fill(mb.luma, b8 * 64, b8 * 64 + 64, 0);
        for (var i = 0; i < 4; i++) {
            mb.lumaCount[b8 * 4 + i] = 0;
        }
    }
}
