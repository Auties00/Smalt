package com.github.auties00.smalt.video.dnxhd.internal;

import com.github.auties00.smalt.video.intra.internal.BlockKernel;
import com.github.auties00.smalt.video.intra.internal.IntraBitWriter;
import com.github.auties00.smalt.video.intra.internal.IntraPlanes;

import java.util.Arrays;

/**
 * Encodes the macroblock scan lines of VC-3 coding units (SMPTE ST 2019-1): gathers and transforms
 * the blocks of a scan line, measures the coded size of every macroblock at a quantization scale
 * factor, and writes the scan line with the scale factor chosen for each macroblock, its video
 * macroblocks followed, for a coding unit with alpha, by its alpha macroblocks (Figure 27).
 *
 * <p>The DCT coefficients of a coding unit live in {@link Vc3Coefficients} stores shared by the
 * threads, each scan line written by one thread. Instances hold the scratch buffers of one
 * encoding thread and are not thread-safe.
 *
 * @implNote This implementation quantizes with equation 8.2 of ST 2019-1, truncating the scaled
 * magnitude, which the mid-interval reconstruction of equation 8.1 expects. A DCT-coded
 * alpha macroblock takes the scale factor of the video macroblock it follows. For
 * compression ID 1260 it codes each macroblock in field macroblock mode when the
 * weighted sum of the magnitudes of its AC coefficients is smaller with the lines of
 * each block taken from one field than from the frame, which predicts the cheaper mode
 * at every scale factor. Measuring and writing visit only the non-zero levels of a block:
 * the quantizer reports a lane mask per coefficient position, which is transposed into one
 * 64-bit mask per block in zigzag order, and the length and codeword of a coefficient come
 * from tables indexed by amplitude ({@code Vc3Costs}).
 */
public final class Vc3RowEncoder {
    /**
     * The index of the alpha channel's DC predictor in {@link #predictors}.
     */
    private static final int ALPHA = 3;

    /**
     * An upper bound of the bytes one macroblock codes to: twelve blocks of up to 63 codewords of
     * 32 bits, a DC codeword and the end of block.
     */
    private static final int MAX_MACROBLOCK_BYTES = 4096;

    /**
     * The quantized levels of a macroblock, one array per batch.
     */
    private final int[][] levels = new int[2][BlockKernel.BATCH_LENGTH];

    /**
     * The sample plane of every block of the batch being gathered.
     */
    private final short[][] sources = new short[BlockKernel.LANES][];

    /**
     * The index of the first sample of every block of the batch being gathered.
     */
    private final int[] offsets = new int[BlockKernel.LANES];

    /**
     * The row stride of every block of the batch being gathered.
     */
    private final int[] strides = new int[BlockKernel.LANES];

    /**
     * The work array of the block loader.
     */
    private final float[] loadScratch = new float[64];

    /**
     * The coefficients of the macroblock being coded, one array per batch, whose signs are the
     * signs of the levels.
     */
    private final float[][] coefficients = new float[2][];

    /**
     * The non-zero lane masks of the quantized levels of a macroblock, one array of 64 raster
     * positions per batch.
     */
    private final int[][] masks = new int[2][64];

    /**
     * The non-zero coefficients of every block of a macroblock after the DC coefficient, bit
     * {@code i} set when the level at zigzag index {@code i} is not zero.
     */
    private final long[] blockMasks = new long[16];

    /**
     * The field-mode coefficients of a macroblock being transformed, one array per batch.
     */
    private final float[][] fieldBatches = new float[2][BlockKernel.BATCH_LENGTH];

    /**
     * The reciprocal quantizer steps of every batch of the macroblock layout being coded,
     * interleaved like a batch: the luma steps in the lanes of luma blocks and the chroma steps in
     * the others.
     */
    private final float[][] steps = new float[2][BlockKernel.BATCH_LENGTH];

    /**
     * The scale factor the steps were computed for, or {@code 0}.
     */
    private int stepsScale;

    /**
     * The luma weights the steps were computed for.
     */
    private int[] lumaWeights;

    /**
     * The chroma weights the steps were computed for.
     */
    private int[] chromaWeights;

    /**
     * The layout the steps were computed for.
     */
    private Vc3Layout stepsLayout;

    /**
     * The inverse quantization parameter the steps were computed for.
     */
    private int stepsDequantization;

    /**
     * The DC predictor of every component type: the three video components, then alpha.
     */
    private final int[] predictors = new int[4];

    /**
     * The coder of lossless alpha macroblocks.
     */
    private final Vc3LosslessAlpha lossless = new Vc3LosslessAlpha();

    /**
     * The writer of the scan lines this encoder writes.
     */
    private final IntraBitWriter rowWriter = new IntraBitWriter(1 << 15);

    /**
     * Returns the writer for the scan lines this encoder writes, which the caller resets before
     * each scan line.
     *
     * @return the writer
     */
    public IntraBitWriter writer() {
        return rowWriter;
    }

    /**
     * Constructs a row encoder.
     */
    public Vc3RowEncoder() {
    }

    /**
     * Gathers and transforms every macroblock of a scan line into a coefficient store, choosing
     * the macroblock mode of each macroblock of an adaptive compression ID.
     *
     * @param planes the prepared planes; for RGB the red, green and blue planes
     * @param store the coefficient store of the coding unit: video, or alpha read from the alpha
     * plane
     * @param row the scan line
     */
    public void transform(IntraPlanes planes, Vc3Coefficients store, int row) {
        var blocks = store.layout().blocks();
        var kernel = BlockKernel.INSTANCE;
        for (var mb = 0; mb < store.widthInMb(); mb++) {
            var frameCost = 0.0;
            var fieldCost = 0.0;
            for (var batch = 0; batch * BlockKernel.LANES < blocks; batch++) {
                var values = store.batch(row, mb, batch);
                gather(planes, store, row, mb, batch, false, values);
                kernel.forward(values);
                if (store.adaptive()) {
                    var field = fieldBatches[batch];
                    gather(planes, store, row, mb, batch, true, field);
                    kernel.forward(field);
                    frameCost += activity(store, batch, values);
                    fieldCost += activity(store, batch, field);
                }
            }
            if (store.adaptive()) {
                var field = fieldCost < frameCost;
                store.fieldMode(row, mb, field);
                if (field) {
                    for (var batch = 0; batch * BlockKernel.LANES < blocks; batch++) {
                        System.arraycopy(fieldBatches[batch], 0, store.batch(row, mb, batch), 0, BlockKernel.BATCH_LENGTH);
                    }
                }
            }
        }
    }

    /**
     * Gathers the samples of one batch of blocks of a macroblock, level-shifted and scaled for
     * the transform.
     *
     * @param planes the prepared planes
     * @param store the coefficient store
     * @param row the scan line
     * @param mb the macroblock
     * @param batch the index of the batch of {@link BlockKernel#LANES} blocks
     * @param field whether to take the lines of field macroblock mode: the upper blocks of a
     * 16-line component from the top field, the lower ones from the bottom field
     * @param values receives the samples
     */
    private void gather(IntraPlanes planes, Vc3Coefficients store, int row, int mb, int batch, boolean field,
                        float[] values) {
        var layout = store.layout();
        var blocks = layout.blocks();
        var offset = 1 << (planes.depth() - 1);
        var scale = planes.depth() == 12 ? 0.25f : 1f;
        var lanes = Math.min(BlockKernel.LANES, blocks - batch * BlockKernel.LANES);
        for (var lane = 0; lane < lanes; lane++) {
            var block = batch * BlockKernel.LANES + lane;
            var component = layout.components[block];
            var shiftX = component == 0 ? 0 : layout.chromaShiftX;
            var shiftY = component == 0 ? 0 : layout.chromaShiftY;
            var x = (mb << (4 - shiftX)) + layout.xs[block];
            var top = row << (4 - shiftY);
            int stride;
            if (store.alpha()) {
                sources[lane] = planes.alpha();
                stride = planes.lumaStride();
            } else {
                sources[lane] = component == 0 ? planes.first() : component == 1 ? planes.second() : planes.third();
                stride = component == 0 ? planes.lumaStride() : planes.chromaStride();
            }
            int first;
            int step;
            if (field && shiftY == 0) {
                first = top + (layout.ys[block] == 0 ? 0 : 1);
                step = 2;
            } else {
                first = top + layout.ys[block];
                step = 1;
            }
            offsets[lane] = (first * store.rowStep() + (store.rowOffset() >> shiftY)) * stride + x;
            strides[lane] = step * store.rowStep() * stride;
        }
        BlockKernel.INSTANCE.loadBlocks(sources, offsets, strides, lanes, offset, scale, values, loadScratch);
    }

    /**
     * Returns the sum of the magnitudes of the AC coefficients of a batch, each divided by its
     * quantization weight, a predictor of the batch's coded size.
     *
     * @param store the coefficient store
     * @param batch the batch index
     * @param values the coefficients
     * @return the weighted activity
     */
    private static double activity(Vc3Coefficients store, int batch, float[] values) {
        var layout = store.layout();
        var lanes = Math.min(BlockKernel.LANES, layout.blocks() - batch * BlockKernel.LANES);
        var sum = 0.0;
        for (var lane = 0; lane < lanes; lane++) {
            var weights = layout.components[batch * BlockKernel.LANES + lane] == 0 ? store.lumaWeights()
                    : store.chromaWeights();
            for (var position = 1; position < 64; position++) {
                sum += Math.abs(values[position * BlockKernel.LANES + lane]) / weights[position];
            }
        }
        return sum;
    }

    /**
     * Returns the coded size in bits of every macroblock of a scan line at one scale factor,
     * together with the alpha macroblock that follows it.
     *
     * @param store the video coefficient store
     * @param alpha the alpha coefficient store, or {@code null}
     * @param lossless the size of every lossless alpha macroblock of the scan line, or {@code null}
     * @param row the scan line
     * @param scale the quantization scale factor
     * @param bits receives the size of each macroblock, including its 12-bit header and its
     * alpha macroblock
     */
    public void measure(Vc3Coefficients store, Vc3Coefficients alpha, int[] lossless, int row, int scale, int[] bits) {
        Arrays.fill(predictors, 0);
        for (var mb = 0; mb < store.widthInMb(); mb++) {
            quantize(store, row, mb, scale);
            bits[mb] = 12 + measureBlocks(store);
        }
        if (alpha != null) {
            for (var mb = 0; mb < store.widthInMb(); mb++) {
                quantize(alpha, row, mb, scale);
                bits[mb] += 12 + measureBlocks(alpha);
            }
        } else if (lossless != null) {
            for (var mb = 0; mb < store.widthInMb(); mb++) {
                bits[mb] += lossless[mb];
            }
        }
    }

    /**
     * Measures every lossless alpha macroblock of a scan line.
     *
     * @param planes the prepared planes, with an alpha plane
     * @param store the video coefficient store, for the geometry of the coding unit
     * @param row the scan line
     * @param bits receives the size of each alpha macroblock
     */
    public void measureLossless(IntraPlanes planes, Vc3Coefficients store, int row, int[] bits) {
        for (var mb = 0; mb < store.widthInMb(); mb++) {
            bits[mb] = lossless.code(planes.alpha(), planes.lumaStride(), mb << 4, row << 4, store.rowStep(),
                    store.rowOffset(), planes.alphaDepth(), null);
        }
    }

    /**
     * Writes a scan line, padded to a multiple of 32 bits.
     *
     * @param store the video coefficient store
     * @param alpha the alpha coefficient store, or {@code null}
     * @param planes the prepared planes when the alpha macroblocks are coded losslessly,
     * otherwise {@code null}
     * @param row the scan line
     * @param scales the quantization scale factor of every macroblock
     * @param alternate whether the video macroblocks set the alternate color space flag
     * {@code ACF} (compression IDs 1256 and 1270 only)
     * @param writer the writer, reset by the caller
     */
    public void write(Vc3Coefficients store, Vc3Coefficients alpha, IntraPlanes planes, int row, int[] scales,
                      boolean alternate, IntraBitWriter writer) {
        Arrays.fill(predictors, 0);
        for (var mb = 0; mb < store.widthInMb(); mb++) {
            writeMacroblock(store, row, mb, scales[mb], alternate, writer);
        }
        if (alpha != null) {
            // Each DCT alpha macroblock gets its own 12-bit header (clause 7.3.1.1), with ACF clear;
            // Figure 43 shows none, see Vc3RowDecoder.decodeAlpha for the two readings.
            for (var mb = 0; mb < store.widthInMb(); mb++) {
                writeMacroblock(alpha, row, mb, scales[mb], false, writer);
            }
        } else if (planes != null) {
            for (var mb = 0; mb < store.widthInMb(); mb++) {
                lossless.code(planes.alpha(), planes.lumaStride(), mb << 4, row << 4, store.rowStep(),
                        store.rowOffset(), planes.alphaDepth(), writer);
            }
        }
        var pad = (int) ((32 - writer.bitCount() % 32) % 32);
        writer.write(0, pad);
    }

    /**
     * Writes one DCT-coded macroblock: its 12-bit header (ST 2019-1 Figures 30 to 32) and its
     * blocks.
     *
     * @param store the coefficient store
     * @param row the scan line
     * @param mb the macroblock
     * @param scale the quantization scale factor
     * @param alternate whether to set the alternate color space flag {@code ACF}, the least
     * significant bit of the header of Figure 31
     * @param writer the bit writer of the scan line
     */
    private void writeMacroblock(Vc3Coefficients store, int row, int mb, int scale, boolean alternate,
                                 IntraBitWriter writer) {
        quantize(store, row, mb, scale);
        var header = store.adaptive() ? (store.fieldMode(row, mb) ? 1 << 11 : 0) | (scale & 0x3FF) << 1
                : (scale & 0x7FF) << 1 | (alternate ? 1 : 0);
        writer.write(header, 12);
        writeBlocks(store, writer);
    }

    /**
     * Quantizes the blocks of a macroblock into {@link #levels} and records which levels are not
     * zero.
     *
     * @param store the coefficient store
     * @param row the scan line
     * @param mb the macroblock
     * @param scale the quantization scale factor
     */
    private void quantize(Vc3Coefficients store, int row, int mb, int scale) {
        var compression = store.compression();
        var layout = store.layout();
        if (stepsScale != scale || lumaWeights != store.lumaWeights() || chromaWeights != store.chromaWeights()
                || stepsLayout != layout || stepsDequantization != compression.dequantization()) {
            var p = compression.dequantization();
            var luma = store.lumaWeights();
            var chroma = store.chromaWeights();
            var blocks = layout.blocks();
            for (var position = 1; position < 64; position++) {
                var l = (float) p / ((long) luma[position] * scale);
                var c = (float) p / ((long) chroma[position] * scale);
                for (var batch = 0; batch < 2; batch++) {
                    for (var lane = 0; lane < BlockKernel.LANES; lane++) {
                        var block = batch * BlockKernel.LANES + lane;
                        var chromaBlock = block < blocks && layout.components[block] != 0;
                        steps[batch][position * BlockKernel.LANES + lane] = chromaBlock ? c : l;
                    }
                }
            }
            stepsScale = scale;
            lumaWeights = store.lumaWeights();
            chromaWeights = store.chromaWeights();
            stepsLayout = layout;
            stepsDequantization = p;
        }
        var kernel = BlockKernel.INSTANCE;
        var maxLevel = store.depth() == 8 ? 1024 : 4096;
        var blocks = layout.blocks();
        for (var batch = 0; batch * BlockKernel.LANES < blocks; batch++) {
            var values = store.batch(row, mb, batch);
            coefficients[batch] = values;
            var out = levels[batch];
            kernel.quantizeMasked(values, steps[batch], 0f, maxLevel, out, masks[batch]);
            for (var lane = 0; lane < BlockKernel.LANES; lane++) {
                out[lane] = Math.round(values[lane]);
            }
            blockMasks(batch, Math.min(BlockKernel.LANES, blocks - batch * BlockKernel.LANES));
        }
    }

    /**
     * Gathers the non-zero lane masks of a batch into one mask per block over the zigzag order,
     * transposing eight positions at a time.
     *
     * @param batch the index of the batch of blocks
     * @param lanes the number of blocks of the batch
     */
    private void blockMasks(int batch, int lanes) {
        var positions = masks[batch];
        var zigzag = Vc3Tables.ZIGZAG;
        var first = batch * BlockKernel.LANES;
        for (var lane = 0; lane < lanes; lane++) {
            blockMasks[first + lane] = 0L;
        }
        for (var group = 0; group < 8; group++) {
            var packed = 0L;
            for (var k = 0; k < 8; k++) {
                packed |= (positions[zigzag[group * 8 + k]] & 0xFFL) << (8 * k);
            }
            var flipped = transpose(packed);
            for (var lane = 0; lane < lanes; lane++) {
                blockMasks[first + lane] |= ((flipped >>> (8 * lane)) & 0xFFL) << (8 * group);
            }
        }
        for (var lane = 0; lane < lanes; lane++) {
            blockMasks[first + lane] &= ~1L;
        }
    }

    /**
     * Transposes a matrix of 8x8 bits, bit {@code 8 * row + column} holding the element at
     * {@code row} and {@code column}.
     *
     * @implNote This implementation uses the usual three delta swaps (exchange of the 1x1, 2x2 and
     * 4x4 off-diagonal blocks) of the diagonal flip of a bitboard.
     * @param x the matrix
     * @return the transposed matrix
     */
    static long transpose(long x) {
        var t = 0x5500550055005500L & (x ^ (x << 7));
        x ^= t ^ (t >>> 7);
        t = 0x3333000033330000L & (x ^ (x << 14));
        x ^= t ^ (t >>> 14);
        t = 0x0F0F0F0F00000000L & (x ^ (x << 28));
        x ^= t ^ (t >>> 28);
        return x;
    }

    /**
     * Returns the coded size in bits of the quantized blocks of a macroblock.
     *
     * @param store the coefficient store
     * @return the number of bits
     */
    private int measureBlocks(Vc3Coefficients store) {
        var layout = store.layout();
        var costs = Vc3Costs.of(store.compression().codeSet(), store.depth());
        var amplitudeBits = costs.bits;
        var runBits = costs.runBits;
        var dcBits = costs.dcBits;
        var zigzag = Vc3Tables.ZIGZAG;
        var maxDc = store.depth() == 8 ? 2047 : 8191;
        var alpha = store.alpha();
        var bits = 0;
        for (var block = 0; block < layout.blocks(); block++) {
            var out = levels[block >>> 3];
            var lane = block & 7;
            var component = alpha ? ALPHA : layout.components[block];
            var dc = Math.clamp(out[lane], predictors[component] - maxDc, predictors[component] + maxDc);
            var difference = dc - predictors[component];
            predictors[component] = dc;
            var size = difference == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(Math.abs(difference));
            bits += dcBits[size] + size + costs.endBits;
            var mask = blockMasks[block];
            var previous = 0;
            while (mask != 0) {
                var index = Long.numberOfTrailingZeros(mask);
                mask &= mask - 1;
                var run = index - previous - 1;
                previous = index;
                var amplitude = out[zigzag[index] * BlockKernel.LANES + lane];
                bits += amplitudeBits[2 * amplitude + Math.min(run, 1)] + runBits[run];
            }
        }
        return bits;
    }

    /**
     * Writes the quantized blocks of a macroblock, packing the bits in local variables.
     *
     * @param store the coefficient store
     * @param writer the bit writer of the scan line
     */
    private void writeBlocks(Vc3Coefficients store, IntraBitWriter writer) {
        var layout = store.layout();
        var costs = Vc3Costs.of(store.compression().codeSet(), store.depth());
        var amplitudeBits = costs.bits;
        var words = costs.words;
        var signs = costs.signs;
        var runBits = costs.runBits;
        var runCodes = costs.runCodes;
        var dcBits = costs.dcBits;
        var dcCodes = costs.dcCodes;
        var combinable = costs.combinable;
        var endCode = costs.endCode;
        var endBits = costs.endBits;
        var zigzag = Vc3Tables.ZIGZAG;
        var maxDc = store.depth() == 8 ? 2047 : 8191;
        var alpha = store.alpha();
        var buffer = writer.reserve(MAX_MACROBLOCK_BYTES);
        var length = writer.length();
        var accumulator = writer.accumulator();
        var pending = writer.pending();
        for (var block = 0; block < layout.blocks(); block++) {
            var out = levels[block >>> 3];
            var lane = block & 7;
            var component = alpha ? ALPHA : layout.components[block];
            var dc = Math.clamp(out[lane], predictors[component] - maxDc, predictors[component] + maxDc);
            var difference = dc - predictors[component];
            predictors[component] = dc;
            var size = difference == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(Math.abs(difference));
            var dcWord = dcCodes[size];
            var dcLength = dcBits[size];
            if (size != 0) {
                var value = difference > 0 ? difference : difference - 1 + (1 << size);
                dcWord = dcWord << size | (value & ((1 << size) - 1));
                dcLength += size;
            }
            accumulator = accumulator << dcLength | (dcWord & (0xFFFFFFFFL >>> (32 - dcLength)));
            pending += dcLength;
            if (pending >= 32) {
                pending -= 32;
                IntraBitWriter.storeWord(buffer, length, (int) (accumulator >>> pending));
                length += 4;
            }
            var mask = blockMasks[block];
            var previous = 0;
            while (mask != 0) {
                var index = Long.numberOfTrailingZeros(mask);
                mask &= mask - 1;
                var run = index - previous - 1;
                previous = index;
                var position = zigzag[index] * BlockKernel.LANES + lane;
                var amplitude = out[position];
                var slot = 2 * amplitude + Math.min(run, 1);
                var negative = Float.floatToRawIntBits(coefficients[block >>> 3][position]) >> 31;
                var word = words[slot] | (signs[amplitude] & negative);
                var wordLength = amplitudeBits[slot];
                if (run != 0) {
                    if (combinable) {
                        word = word << runBits[run] | runCodes[run];
                        wordLength += runBits[run];
                    } else {
                        accumulator = accumulator << wordLength | (word & (0xFFFFFFFFL >>> (32 - wordLength)));
                        pending += wordLength;
                        if (pending >= 32) {
                            pending -= 32;
                            IntraBitWriter.storeWord(buffer, length, (int) (accumulator >>> pending));
                            length += 4;
                        }
                        word = runCodes[run];
                        wordLength = runBits[run];
                    }
                }
                accumulator = accumulator << wordLength | (word & (0xFFFFFFFFL >>> (32 - wordLength)));
                pending += wordLength;
                if (pending >= 32) {
                    pending -= 32;
                    IntraBitWriter.storeWord(buffer, length, (int) (accumulator >>> pending));
                    length += 4;
                }
            }
            accumulator = accumulator << endBits | (endCode & (0xFFFFFFFFL >>> (32 - endBits)));
            pending += endBits;
            if (pending >= 32) {
                pending -= 32;
                IntraBitWriter.storeWord(buffer, length, (int) (accumulator >>> pending));
                length += 4;
            }
        }
        writer.resume(length, accumulator, pending);
    }
}
