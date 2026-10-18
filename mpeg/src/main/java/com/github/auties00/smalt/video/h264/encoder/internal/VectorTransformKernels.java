package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.internal.H264Tables;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

import java.util.Arrays;

/**
 * Implements {@link TransformKernels} with the Vector API; the results equal the scalar kernels exactly.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd Simd}
 * and transforms four horizontally adjacent 4x4 blocks per 16-lane vector, which leaves each group of four lanes
 * holding the horizontal frequencies in the order 0, 3, 2, 1 with the last two negated; quantization uses a multiplier
 * table in that layout. The forward quantizers handle one band and one frequency row per loop iteration to stay under
 * the JIT node budget, and write each block row with an unmasked store, since a masked store whose full vector would
 * leave the array takes a path that keeps the vectors on the heap.
 */
final class VectorTransformKernels extends TransformKernels {
    /**
     * Sixteen bytes.
     */
    private static final VectorSpecies<Byte> B128 = ByteVector.SPECIES_128;

    /**
     * Eight bytes.
     */
    private static final VectorSpecies<Byte> B64 = ByteVector.SPECIES_64;

    /**
     * Eight shorts.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * Sixteen shorts.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * Eight ints.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * Eight ints equal to {@code 0}.
     */
    private static final IntVector I256_0 = IntVector.broadcast(I256, 0);

    /**
     * The lanes holding the horizontal frequency placed at position 1 of each group.
     */
    private static final VectorMask<Short> POSITION_1 = VectorMask.fromLong(S256, 0x2222L);

    /**
     * The lanes holding the horizontal frequency placed at position 2 of each group.
     */
    private static final VectorMask<Short> POSITION_2 = VectorMask.fromLong(S256, 0x4444L);

    /**
     * The lanes holding the horizontal frequency placed at position 3 of each group.
     */
    private static final VectorMask<Short> POSITION_3 = VectorMask.fromLong(S256, 0x8888L);

    /**
     * The lanes of eight shorts holding the horizontal frequency placed at position 1 of each
     * group.
     */
    private static final VectorMask<Short> POSITION_1_8 = VectorMask.fromLong(S128, 0x22L);

    /**
     * The lanes of eight shorts holding the horizontal frequency placed at position 2 of each
     * group.
     */
    private static final VectorMask<Short> POSITION_2_8 = VectorMask.fromLong(S128, 0x44L);

    /**
     * The lanes of eight shorts holding the horizontal frequency placed at position 3 of each
     * group.
     */
    private static final VectorMask<Short> POSITION_3_8 = VectorMask.fromLong(S128, 0x88L);

    /**
     * Exchanges the two 32-bit halves of every 64-bit container of eight ints.
     */
    private static final VectorShuffle<Integer> SWAP_256 = VectorShuffle.fromValues(I256, 1, 0, 3, 2, 5, 4, 7, 6);

    /**
     * Exchanges the two 32-bit halves of every 64-bit container of four ints.
     */
    private static final VectorShuffle<Integer> SWAP_128 = VectorShuffle.fromValues(IntVector.SPECIES_128, 1, 0, 3,
            2);

    /**
     * Reorders each group of four quantized lanes, holding the horizontal frequencies 0, 3, 2
     * and 1, into raster order.
     */
    private static final VectorShuffle<Integer> RASTER_ORDER = VectorShuffle.fromValues(I256, 0, 3, 2, 1, 4, 7, 6,
            5);

    /**
     * The horizontal frequency stored at each position of a group of four lanes.
     */
    private static final int[] FREQUENCY = {0, 3, 2, 1};

    /**
     * The quantization multipliers in the lane layout, indexed by
     * {@code ((qp % 6) * 4 + verticalFrequency) * 16 + lane}.
     */
    private static final int[] LANE_MULTIPLIERS = new int[6 * 4 * 16];

    static {
        for (var m = 0; m < 6; m++) {
            for (var k = 0; k < 4; k++) {
                for (var lane = 0; lane < 16; lane++) {
                    LANE_MULTIPLIERS[(m * 4 + k) * 16 + lane] = Tables.QUANT4[m][k * 4 + FREQUENCY[lane & 3]];
                }
            }
        }
    }

    /**
     * The thresholds of {@link TransformKernels#INTER_NONZERO_THRESHOLDS} in the lane layout of
     * the luma kernels, indexed by {@code (qp * 4 + verticalFrequency) * 16 + lane}.
     */
    private static final short[] LANE_NONZERO = laneThresholds(INTER_NONZERO_THRESHOLDS);

    /**
     * The thresholds of {@link TransformKernels#INTER_BIG_THRESHOLDS} in the lane layout of
     * {@link #LANE_NONZERO}.
     */
    private static final short[] LANE_BIG = laneThresholds(INTER_BIG_THRESHOLDS);

    /**
     * Sixteen zero shorts.
     */
    private static final ShortVector ZERO_16 = ShortVector.zero(S256);

    /**
     * The bits of every group of four lane bits that hold horizontal frequency 0 or 2, which keep
     * their position in raster order.
     */
    private static final long FIXED_FREQUENCIES = 0x5555555555555555L;

    /**
     * The bits of every group of four lane bits that hold horizontal frequency 3, which moves up
     * two positions in raster order.
     */
    private static final long FREQUENCY_3 = 0x2222222222222222L;

    /**
     * The bits of every group of four lane bits that hold horizontal frequency 1, which moves down
     * two positions in raster order.
     */
    private static final long FREQUENCY_1 = 0x8888888888888888L;

    /**
     * The bits of every group of four lane bits that hold horizontal frequency 2 or 1, whose
     * coefficients the butterflies leave negated.
     */
    private static final long NEGATED_FREQUENCIES = 0xCCCCCCCCCCCCCCCCL;

    /**
     * The nibbles of one block's lanes in the four frequency rows of a 16-lane mask set, for the
     * first block of the band; the masks of block {@code j} are this shifted by {@code 4 * j}.
     */
    private static final long BLOCK_NIBBLES_16 = 0x000F000F000F000FL;

    /**
     * The nibbles of one block's lanes in the four frequency rows of an 8-lane mask set, laid out
     * like {@link #BLOCK_NIBBLES_16}.
     */
    private static final long BLOCK_NIBBLES_8 = 0x0F0F0F0FL;

    /**
     * Reorders the four bits of every lane group of a mask set, holding the horizontal
     * frequencies 0, 3, 2 and 1, into raster order.
     *
     * @param lanes the mask bits, one group of four per block and frequency row
     * @return the bits with each group in raster order
     */
    private static long rasterOrder(long lanes) {
        return (lanes & FIXED_FREQUENCIES) | ((lanes & FREQUENCY_3) << 2) | ((lanes & FREQUENCY_1) >>> 2);
    }

    /**
     * The weights of the vertical butterfly outputs {@code p0..p3} forming each frequency row
     * {@code k} of a 4x4 transform, as four 16-lane vectors per row: {@code p0 + p1},
     * {@code p2 + 2 * p3}, {@code p0 - p1} and {@code p3 - 2 * p2}.
     */
    private static final short[] ROW_WEIGHTS_16 = rowWeights(16);

    /**
     * The weights of {@link #ROW_WEIGHTS_16} as 8-lane vectors.
     */
    private static final short[] ROW_WEIGHTS_8 = rowWeights(8);

    /**
     * All bits set in the lanes holding the negated horizontal frequencies 1 and 2 after
     * {@link #RASTER_ORDER} and clear elsewhere, so {@code (x ^ MIDDLE_SIGNS) - MIDDLE_SIGNS}
     * restores their sign.
     */
    private static final IntVector MIDDLE_SIGNS = IntVector.fromArray(I256, new int[] {0, -1, -1, 0, 0, -1, -1, 0}, 0);

    /**
     * All bits set in every lane except the DC lanes of the two blocks of eight quantized lanes,
     * so a bitwise and clears the DC coefficients.
     */
    private static final IntVector AC_LANES = IntVector.fromArray(I256, new int[] {0, -1, -1, -1, 0, -1, -1, -1}, 0);

    /**
     * Four ints, one row of a block's levels.
     */
    private static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;

    /**
     * The exact class of 16-lane byte vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<ByteVector> BYTES_16 = (Class<ByteVector>) B128.vectorType();

    /**
     * The exact class of 8-lane byte vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<ByteVector> BYTES_8 = (Class<ByteVector>) B64.vectorType();

    /**
     * The exact class of 4-lane int vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<IntVector> INTS_4 = (Class<IntVector>) I128.vectorType();

    /**
     * The exact class of 8-lane int vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<IntVector> INTS_8 = (Class<IntVector>) I256.vectorType();

    /**
     * The exact class of 16-lane short vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<ShortVector> SHORTS_16 = (Class<ShortVector>) S256.vectorType();

    /**
     * The exact class of 8-lane short vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<ShortVector> SHORTS_8 = (Class<ShortVector>) S128.vectorType();

    /**
     * The permutation exchanging the two halves of eight ints, which moves the levels of the
     * right block of a row pair to the low half.
     */
    private static final VectorShuffle<Integer> HALVES = VectorShuffle.fromValues(I256, 4, 5, 6, 7, 0, 1, 2, 3);

    /**
     * Constructs the vector kernels.
     */
    VectorTransformKernels() {
    }

    /**
     * Returns the frequency row weights of the vertical butterfly outputs, each weight repeated
     * across one vector.
     *
     * @param lanes the number of lanes of a vector
     * @return the weights of row {@code k} and output {@code j} at {@code (k * 4 + j) * lanes}
     */
    private static short[] rowWeights(int lanes) {
        short[][] weights = {{1, 1, 0, 0}, {0, 0, 1, 2}, {1, -1, 0, 0}, {0, 0, -2, 1}};
        var table = new short[4 * 4 * lanes];
        for (var k = 0; k < 4; k++) {
            for (var j = 0; j < 4; j++) {
                Arrays.fill(table, (k * 4 + j) * lanes, (k * 4 + j + 1) * lanes, weights[k][j]);
            }
        }
        return table;
    }

    @Override
    void forwardQuantizeLuma(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset, int predStride,
                             int qp, int bias, int limit, int[] levels, int[] counts) {
        quantizeLuma(src, srcOffset, srcStride, pred, predOffset, predStride, qp, bias, limit, levels, counts, null);
    }

    @Override
    void forwardQuantizeLumaAc(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset,
                               int predStride, int qp, int bias, int limit, int[] levels, int[] counts, int[] dc) {
        quantizeLuma(src, srcOffset, srcStride, pred, predOffset, predStride, qp, bias, limit, levels, counts, dc);
    }

    /**
     * Transforms and quantizes the sixteen 4x4 luma blocks of a macroblock residual, optionally
     * keeping the DC coefficients unquantized.
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
     * @param dc receives the 16 unquantized DC coefficients in raster block order, or
     * {@code null} to quantize them like the other coefficients
     */
    private static void quantizeLuma(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset,
                                     int predStride, int qp, int bias, int limit, int[] levels, int[] counts,
                                     int[] dc) {
        var shift = 15 + qp / 6;
        var tableBase = (qp % 6) * 64;
        for (var band = 0; band < 4; band++) {
            var packed = dc == null
                    ? quantizeLumaBand(src, srcOffset, srcStride, pred, predOffset, predStride, band, shift, tableBase,
                    bias, limit, levels)
                    : quantizeLumaBandAc(src, srcOffset, srcStride, pred, predOffset, predStride, band, shift,
                    tableBase, bias, limit, levels, dc);
            counts[H264Tables.BLOCK_RASTER[band * 4]] = packed & 0xFF;
            counts[H264Tables.BLOCK_RASTER[band * 4 + 1]] = (packed >>> 8) & 0xFF;
            counts[H264Tables.BLOCK_RASTER[band * 4 + 2]] = (packed >>> 16) & 0xFF;
            counts[H264Tables.BLOCK_RASTER[band * 4 + 3]] = packed >>> 24;
        }
    }

    /**
     * Transforms and quantizes the four horizontally adjacent 4x4 luma blocks of a band.
     *
     * <p>Each iteration of the loop handles one vertical frequency of one pair of blocks (the
     * left or the right half of the band): it reloads the band's rows, recomputes their vertical
     * butterflies, forms the frequency's coefficients, quantizes the pair's half of them and
     * stores each block's four levels, one row of the block, with a masked store. No vector
     * outlives an iteration, so the JIT compiles one small loop body instead of a straight-line
     * method whose size would exhaust its inlining budget.
     *
     * @param src the source samples
     * @param srcOffset the index of the macroblock's top-left source sample
     * @param srcStride the source row stride
     * @param pred the prediction samples
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param band the band of four blocks, 0 to 3 from the top
     * @param shift the quantization shift
     * @param tableBase the index of the quantization multipliers of {@code qp % 6}
     * @param bias the rounding offset
     * @param limit the largest level magnitude the entropy coder can represent
     * @param levels receives the levels
     * @return the non-zero counts of the band's four blocks in raster order, one per byte from
     * the least significant
     */
    private static int quantizeLumaBand(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset,
                                        int predStride, int band, int shift, int tableBase, int bias, int limit,
                                        int[] levels) {
        var s = srcOffset + band * 4 * srcStride;
        var p = predOffset + band * 4 * predStride;
        var counts = 0;
        for (var step = 0; step < 8; step++) {
            var frequency = step >> 1;
            var half = step & 1;
            var d0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, p)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var d1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, p + predStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var d2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, p + 2 * predStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var d3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 3 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, p + 3 * predStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var p0 = d0.lanewise(VectorOperators.ADD, d3);
            var p1 = d1.lanewise(VectorOperators.ADD, d2);
            var p2 = d1.lanewise(VectorOperators.SUB, d2);
            var p3 = d0.lanewise(VectorOperators.SUB, d3);
            var weights = frequency * 64;
            var x = p0.lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weights)))
                    .lanewise(VectorOperators.ADD, p1.lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weights + 16))))
                    .lanewise(VectorOperators.ADD, p2.lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weights + 32))))
                    .lanewise(VectorOperators.ADD, p3.lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weights + 48))));
            var reversed = SHORTS_16.cast(INTS_8.cast(x.reinterpretShape(I256, 0)).rearrange(SWAP_256).lanewise(VectorOperators.ROR, 16).reinterpretShape(S256, 0));
            var sum = x.lanewise(VectorOperators.ADD, reversed);
            var difference = x.lanewise(VectorOperators.SUB, reversed);
            var sumSwapped = SHORTS_16.cast(INTS_8.cast(sum.reinterpretShape(I256, 0)).lanewise(VectorOperators.ROR, 16).reinterpretShape(S256, 0));
            var differenceSwapped = SHORTS_16.cast(INTS_8.cast(difference.reinterpretShape(I256, 0)).lanewise(VectorOperators.ROR, 16).reinterpretShape(S256, 0));
            var doubled = difference.lanewise(VectorOperators.ADD, difference);
            var w = sum.lanewise(VectorOperators.ADD, sumSwapped)
                    .blend(differenceSwapped.lanewise(VectorOperators.SUB, doubled), POSITION_1)
                    .blend(sum.lanewise(VectorOperators.SUB, sumSwapped), POSITION_2)
                    .blend(doubled.lanewise(VectorOperators.ADD, differenceSwapped), POSITION_3);
            var c = INTS_8.cast(w.convertShape(VectorOperators.S2I, I256, half));
            var sign = c.lanewise(VectorOperators.ASHR, 31);
            var raster = c.lanewise(VectorOperators.ABS)
                    .lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, LANE_MULTIPLIERS, tableBase + frequency * 16 + half * 8)))
                    .lanewise(VectorOperators.ADD, IntVector.broadcast(I256, bias)).lanewise(VectorOperators.ASHR, shift)
                    .lanewise(VectorOperators.MIN, IntVector.broadcast(I256, limit))
                    .lanewise(VectorOperators.XOR, sign).lanewise(VectorOperators.SUB, sign).rearrange(RASTER_ORDER)
                    .lanewise(VectorOperators.XOR, MIDDLE_SIGNS).lanewise(VectorOperators.SUB, MIDDLE_SIGNS);
            var nonZero = raster.compare(VectorOperators.NE, I256_0).toLong();
            counts += (Long.bitCount(nonZero & 15) | (Long.bitCount(nonZero >>> 4) << 8)) << (half * 16);
            INTS_4.cast(raster.reinterpretShape(I128, 0))
                    .intoArray(levels, H264Tables.BLOCK_RASTER[band * 4 + half * 2] * 16 + frequency * 4);
            INTS_4.cast(raster.rearrange(HALVES).reinterpretShape(I128, 0))
                    .intoArray(levels, H264Tables.BLOCK_RASTER[band * 4 + half * 2 + 1] * 16 + frequency * 4);
        }
        return counts;
    }

    /**
     * Transforms and quantizes the four horizontally adjacent 4x4 luma blocks of a band, keeping
     * their DC coefficients unquantized.
     *
     * <p>Each iteration of the loop handles one vertical frequency of one pair of blocks (the
     * left or the right half of the band): it reloads the band's rows, recomputes their vertical
     * butterflies, forms the frequency's coefficients, quantizes the pair's half of them and
     * stores each block's four levels, one row of the block, with a masked store. No vector
     * outlives an iteration, so the JIT compiles one small loop body instead of a straight-line
     * method whose size would exhaust its inlining budget.
     *
     * @param src the source samples
     * @param srcOffset the index of the macroblock's top-left source sample
     * @param srcStride the source row stride
     * @param pred the prediction samples
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param band the band of four blocks, 0 to 3 from the top
     * @param shift the quantization shift
     * @param tableBase the index of the quantization multipliers of {@code qp % 6}
     * @param bias the rounding offset
     * @param limit the largest level magnitude the entropy coder can represent
     * @param levels receives the levels
     * @param dc receives the unquantized DC coefficients
     * @return the non-zero counts of the band's four blocks in raster order, one per byte from
     * the least significant
     */
    private static int quantizeLumaBandAc(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset,
                                        int predStride, int band, int shift, int tableBase, int bias, int limit,
                                        int[] levels, int[] dc) {
        var s = srcOffset + band * 4 * srcStride;
        var p = predOffset + band * 4 * predStride;
        var counts = 0;
        for (var step = 0; step < 8; step++) {
            var frequency = step >> 1;
            var half = step & 1;
            var d0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, p)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var d1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, p + predStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var d2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, p + 2 * predStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var d3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 3 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, p + 3 * predStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var p0 = d0.lanewise(VectorOperators.ADD, d3);
            var p1 = d1.lanewise(VectorOperators.ADD, d2);
            var p2 = d1.lanewise(VectorOperators.SUB, d2);
            var p3 = d0.lanewise(VectorOperators.SUB, d3);
            var weights = frequency * 64;
            var x = p0.lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weights)))
                    .lanewise(VectorOperators.ADD, p1.lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weights + 16))))
                    .lanewise(VectorOperators.ADD, p2.lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weights + 32))))
                    .lanewise(VectorOperators.ADD, p3.lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weights + 48))));
            var reversed = SHORTS_16.cast(INTS_8.cast(x.reinterpretShape(I256, 0)).rearrange(SWAP_256).lanewise(VectorOperators.ROR, 16).reinterpretShape(S256, 0));
            var sum = x.lanewise(VectorOperators.ADD, reversed);
            var difference = x.lanewise(VectorOperators.SUB, reversed);
            var sumSwapped = SHORTS_16.cast(INTS_8.cast(sum.reinterpretShape(I256, 0)).lanewise(VectorOperators.ROR, 16).reinterpretShape(S256, 0));
            var differenceSwapped = SHORTS_16.cast(INTS_8.cast(difference.reinterpretShape(I256, 0)).lanewise(VectorOperators.ROR, 16).reinterpretShape(S256, 0));
            var doubled = difference.lanewise(VectorOperators.ADD, difference);
            var w = sum.lanewise(VectorOperators.ADD, sumSwapped)
                    .blend(differenceSwapped.lanewise(VectorOperators.SUB, doubled), POSITION_1)
                    .blend(sum.lanewise(VectorOperators.SUB, sumSwapped), POSITION_2)
                    .blend(doubled.lanewise(VectorOperators.ADD, differenceSwapped), POSITION_3);
            var keep = AC_LANES.lanewise(VectorOperators.OR, IntVector.broadcast(I256, frequency == 0 ? 0 : -1));
            var c = INTS_8.cast(w.convertShape(VectorOperators.S2I, I256, half));
            var sign = c.lanewise(VectorOperators.ASHR, 31);
            var raster = c.lanewise(VectorOperators.ABS)
                    .lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, LANE_MULTIPLIERS, tableBase + frequency * 16 + half * 8)))
                    .lanewise(VectorOperators.ADD, IntVector.broadcast(I256, bias)).lanewise(VectorOperators.ASHR, shift)
                    .lanewise(VectorOperators.MIN, IntVector.broadcast(I256, limit))
                    .lanewise(VectorOperators.XOR, sign).lanewise(VectorOperators.SUB, sign).rearrange(RASTER_ORDER)
                    .lanewise(VectorOperators.XOR, MIDDLE_SIGNS).lanewise(VectorOperators.SUB, MIDDLE_SIGNS).lanewise(VectorOperators.AND, keep);
            if (frequency == 0) {
                dc[band * 4 + half * 2] = c.lane(0);
                dc[band * 4 + half * 2 + 1] = c.lane(4);
            }
            var nonZero = raster.compare(VectorOperators.NE, I256_0).toLong();
            counts += (Long.bitCount(nonZero & 15) | (Long.bitCount(nonZero >>> 4) << 8)) << (half * 16);
            INTS_4.cast(raster.reinterpretShape(I128, 0))
                    .intoArray(levels, H264Tables.BLOCK_RASTER[band * 4 + half * 2] * 16 + frequency * 4);
            INTS_4.cast(raster.rearrange(HALVES).reinterpretShape(I128, 0))
                    .intoArray(levels, H264Tables.BLOCK_RASTER[band * 4 + half * 2 + 1] * 16 + frequency * 4);
        }
        return counts;
    }

    /**
     * Rearranges a table of per-position thresholds into the lane layout of the luma kernels.
     *
     * @param raster the thresholds at {@code 16 * qp + position}
     * @return the thresholds at {@code (qp * 4 + verticalFrequency) * 16 + lane}
     */
    private static short[] laneThresholds(int[] raster) {
        var table = new short[52 * 64];
        for (var qp = 0; qp < 52; qp++) {
            for (var k = 0; k < 4; k++) {
                for (var lane = 0; lane < 16; lane++) {
                    table[(qp * 4 + k) * 16 + lane] = (short) raster[qp * 16 + k * 4 + FREQUENCY[lane & 3]];
                }
            }
        }
        return table;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation computes the coefficients like {@link #forwardQuantizeLuma},
     * four blocks per vector, and compares their magnitudes with per-lane thresholds instead of
     * quantizing them, so no value leaves the 16-bit lanes.
     */
    @Override
    boolean quantizeLumaMasks(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset,
                              int predStride, int qp, int[] masks, int[] signs) {
        var tableBase = qp * 64;
        for (var band = 0; band < 4; band++) {
            var s = srcOffset + band * 4 * srcStride;
            var p = predOffset + band * 4 * predStride;
            var d0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, p)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var d1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, p + predStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var d2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, p + 2 * predStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var d3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 3 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, p + 3 * predStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var p0 = d0.lanewise(VectorOperators.ADD, d3);
            var p1 = d1.lanewise(VectorOperators.ADD, d2);
            var p2 = d1.lanewise(VectorOperators.SUB, d2);
            var p3 = d0.lanewise(VectorOperators.SUB, d3);
            long nonZero = 0;
            long big = 0;
            long negative = 0;
            for (var pair = 0; pair < 2; pair++) {
                var weightsEven = pair * 128;
                var weightsOdd = weightsEven + 64;
                var thresholdsEven = tableBase + pair * 32;
                var thresholdsOdd = thresholdsEven + 16;
                var xEven = SHORTS_16.cast(p0).lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weightsEven)))
                        .lanewise(VectorOperators.ADD, SHORTS_16.cast(p1).lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weightsEven + 16))))
                        .lanewise(VectorOperators.ADD, SHORTS_16.cast(p2).lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weightsEven + 32))))
                        .lanewise(VectorOperators.ADD, SHORTS_16.cast(p3).lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weightsEven + 48))));
                var reversedEven = SHORTS_16.cast(INTS_8.cast(xEven.reinterpretShape(I256, 0)).rearrange(SWAP_256).lanewise(VectorOperators.ROR, 16).reinterpretShape(S256, 0));
                var sumEven = xEven.lanewise(VectorOperators.ADD, reversedEven);
                var differenceEven = xEven.lanewise(VectorOperators.SUB, reversedEven);
                var sumSwappedEven = SHORTS_16.cast(INTS_8.cast(sumEven.reinterpretShape(I256, 0)).lanewise(VectorOperators.ROR, 16).reinterpretShape(S256, 0));
                var differenceSwappedEven = SHORTS_16.cast(INTS_8.cast(differenceEven.reinterpretShape(I256, 0)).lanewise(VectorOperators.ROR, 16).reinterpretShape(S256, 0));
                var doubledEven = differenceEven.lanewise(VectorOperators.ADD, differenceEven);
                var coefficientsEven = sumEven.lanewise(VectorOperators.ADD, sumSwappedEven)
                        .blend(differenceSwappedEven.lanewise(VectorOperators.SUB, doubledEven), POSITION_1)
                        .blend(sumEven.lanewise(VectorOperators.SUB, sumSwappedEven), POSITION_2)
                        .blend(doubledEven.lanewise(VectorOperators.ADD, differenceSwappedEven), POSITION_3);
                var magnitudeEven = coefficientsEven.lanewise(VectorOperators.ABS);
                var xOdd = SHORTS_16.cast(p0).lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weightsOdd)))
                        .lanewise(VectorOperators.ADD, SHORTS_16.cast(p1).lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weightsOdd + 16))))
                        .lanewise(VectorOperators.ADD, SHORTS_16.cast(p2).lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weightsOdd + 32))))
                        .lanewise(VectorOperators.ADD, SHORTS_16.cast(p3).lanewise(VectorOperators.MUL, SHORTS_16.cast(ShortVector.fromArray(S256, ROW_WEIGHTS_16, weightsOdd + 48))));
                var reversedOdd = SHORTS_16.cast(INTS_8.cast(xOdd.reinterpretShape(I256, 0)).rearrange(SWAP_256).lanewise(VectorOperators.ROR, 16).reinterpretShape(S256, 0));
                var sumOdd = xOdd.lanewise(VectorOperators.ADD, reversedOdd);
                var differenceOdd = xOdd.lanewise(VectorOperators.SUB, reversedOdd);
                var sumSwappedOdd = SHORTS_16.cast(INTS_8.cast(sumOdd.reinterpretShape(I256, 0)).lanewise(VectorOperators.ROR, 16).reinterpretShape(S256, 0));
                var differenceSwappedOdd = SHORTS_16.cast(INTS_8.cast(differenceOdd.reinterpretShape(I256, 0)).lanewise(VectorOperators.ROR, 16).reinterpretShape(S256, 0));
                var doubledOdd = differenceOdd.lanewise(VectorOperators.ADD, differenceOdd);
                var coefficientsOdd = sumOdd.lanewise(VectorOperators.ADD, sumSwappedOdd)
                        .blend(differenceSwappedOdd.lanewise(VectorOperators.SUB, doubledOdd), POSITION_1)
                        .blend(sumOdd.lanewise(VectorOperators.SUB, sumSwappedOdd), POSITION_2)
                        .blend(doubledOdd.lanewise(VectorOperators.ADD, differenceSwappedOdd), POSITION_3);
                var magnitudeOdd = coefficientsOdd.lanewise(VectorOperators.ABS);
                var shiftEven = pair * 32;
                nonZero |= magnitudeEven.compare(VectorOperators.GE, SHORTS_16.cast(ShortVector.fromArray(S256, LANE_NONZERO, thresholdsEven))).toLong() << shiftEven;
                nonZero |= magnitudeOdd.compare(VectorOperators.GE, SHORTS_16.cast(ShortVector.fromArray(S256, LANE_NONZERO, thresholdsOdd))).toLong() << (shiftEven + 16);
                big |= magnitudeEven.compare(VectorOperators.GE, SHORTS_16.cast(ShortVector.fromArray(S256, LANE_BIG, thresholdsEven))).toLong() << shiftEven;
                big |= magnitudeOdd.compare(VectorOperators.GE, SHORTS_16.cast(ShortVector.fromArray(S256, LANE_BIG, thresholdsOdd))).toLong() << (shiftEven + 16);
                negative |= coefficientsEven.compare(VectorOperators.LT, ZERO_16).toLong() << shiftEven;
                negative |= coefficientsOdd.compare(VectorOperators.LT, ZERO_16).toLong() << (shiftEven + 16);
            }
            var nonZeroRaster = rasterOrder(nonZero);
            var bigRaster = rasterOrder(big);
            var negativeRaster = rasterOrder((negative ^ NEGATED_FREQUENCIES) & nonZero);
            for (var block = 0; block < 4; block++) {
                var select = BLOCK_NIBBLES_16 << (4 * block);
                var blk = H264Tables.BLOCK_RASTER[band * 4 + block];
                masks[blk] = (int) Long.compress(nonZeroRaster, select) | ((int) Long.compress(bigRaster, select) << 16);
                signs[blk] = (int) Long.compress(negativeRaster, select);
            }
            if (big != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation transforms the two horizontally adjacent 4x4 blocks of each
     * half of a component together, one 8-lane vector per row, with the lane layout of
     * {@link #forwardQuantizeLuma}.
     */
    @Override
    void forwardQuantizeChroma(byte[] srcU, byte[] srcV, int srcOffset, int srcStride, byte[] predU, byte[] predV,
                               int predOffset, int predStride, int qp, int bias, int limit, int[] levels,
                               int[] counts, int[] dc) {
        var shift = 15 + qp / 6;
        var tableBase = (qp % 6) * 64;
        for (var band = 0; band < 4; band++) {
            var packed = quantizeChromaBand(band < 2 ? srcU : srcV, srcOffset, srcStride, band < 2 ? predU : predV,
                    predOffset, predStride, band, shift, tableBase, bias, limit, levels, dc);
            var first = (band >> 1) * 4 + (band & 1) * 2;
            counts[first] = packed & 0xFF;
            counts[first + 1] = packed >>> 8;
        }
    }

    /**
     * Transforms and quantizes the two horizontally adjacent 4x4 blocks of one half of a chroma
     * component.
     *
     * <p>Each iteration of the loop handles one vertical frequency of both blocks: it reloads the
     * rows, recomputes their vertical butterflies, forms the frequency's coefficients, quantizes
     * them and stores each block's four levels, one row of the block, with a masked store. No
     * vector outlives an iteration, so the JIT compiles the loop body once rather than four copies
     * of it.
     *
     * @param src the component's source samples
     * @param srcOffset the index of the component's top-left source sample
     * @param srcStride the source row stride
     * @param pred the component's prediction
     * @param predOffset the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param band the band: bit 1 selects the component, bit 0 the lower half
     * @param shift the quantization shift
     * @param tableBase the index of the quantization multipliers of {@code qp % 6}
     * @param bias the rounding offset
     * @param limit the largest level magnitude the entropy coder can represent
     * @param levels receives the levels
     * @param dc receives the unquantized DC coefficients
     * @return the non-zero counts of the band's two blocks, the left one in the low byte
     */
    private static int quantizeChromaBand(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset,
                                          int predStride, int band, int shift, int tableBase, int bias, int limit,
                                          int[] levels, int[] dc) {
        var half = band & 1;
        var first = (band >> 1) * 4 + half * 2;
        var s = srcOffset + half * 4 * srcStride;
        var p = predOffset + half * 4 * predStride;
        var countA = 0;
        var countB = 0;
        for (var frequency = 0; frequency < 4; frequency++) {
            var d0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0))
                    .lanewise(VectorOperators.SUB, SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, pred, p)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)));
            var d1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0))
                    .lanewise(VectorOperators.SUB, SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, pred, p + predStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)));
            var d2 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0))
                    .lanewise(VectorOperators.SUB, SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, pred, p + 2 * predStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)));
            var d3 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 3 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0))
                    .lanewise(VectorOperators.SUB, SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, pred, p + 3 * predStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)));
            var p0 = d0.lanewise(VectorOperators.ADD, d3);
            var p1 = d1.lanewise(VectorOperators.ADD, d2);
            var p2 = d1.lanewise(VectorOperators.SUB, d2);
            var p3 = d0.lanewise(VectorOperators.SUB, d3);
            var weights = frequency * 32;
            var x = p0.lanewise(VectorOperators.MUL, SHORTS_8.cast(ShortVector.fromArray(S128, ROW_WEIGHTS_8, weights)))
                    .lanewise(VectorOperators.ADD, p1.lanewise(VectorOperators.MUL, SHORTS_8.cast(ShortVector.fromArray(S128, ROW_WEIGHTS_8, weights + 8))))
                    .lanewise(VectorOperators.ADD, p2.lanewise(VectorOperators.MUL, SHORTS_8.cast(ShortVector.fromArray(S128, ROW_WEIGHTS_8, weights + 16))))
                    .lanewise(VectorOperators.ADD, p3.lanewise(VectorOperators.MUL, SHORTS_8.cast(ShortVector.fromArray(S128, ROW_WEIGHTS_8, weights + 24))));
            var reversed = SHORTS_8.cast(INTS_4.cast(x.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).lanewise(VectorOperators.ROR, 16).reinterpretShape(S128, 0));
            var sum = x.lanewise(VectorOperators.ADD, reversed);
            var difference = x.lanewise(VectorOperators.SUB, reversed);
            var sumSwapped = SHORTS_8.cast(INTS_4.cast(sum.reinterpretShape(IntVector.SPECIES_128, 0)).lanewise(VectorOperators.ROR, 16).reinterpretShape(S128, 0));
            var differenceSwapped = SHORTS_8.cast(INTS_4.cast(difference.reinterpretShape(IntVector.SPECIES_128, 0)).lanewise(VectorOperators.ROR, 16).reinterpretShape(S128, 0));
            var doubled = difference.lanewise(VectorOperators.ADD, difference);
            var w = sum.lanewise(VectorOperators.ADD, sumSwapped)
                    .blend(differenceSwapped.lanewise(VectorOperators.SUB, doubled), POSITION_1_8)
                    .blend(sum.lanewise(VectorOperators.SUB, sumSwapped), POSITION_2_8)
                    .blend(doubled.lanewise(VectorOperators.ADD, differenceSwapped), POSITION_3_8);
            var keep = AC_LANES.lanewise(VectorOperators.OR, IntVector.broadcast(I256, frequency == 0 ? 0 : -1));
            var c = INTS_8.cast(w.convertShape(VectorOperators.S2I, I256, 0));
            var sign = c.lanewise(VectorOperators.ASHR, 31);
            var raster = c.lanewise(VectorOperators.ABS).lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, LANE_MULTIPLIERS, tableBase + frequency * 16)))
                    .lanewise(VectorOperators.ADD, IntVector.broadcast(I256, bias)).lanewise(VectorOperators.ASHR, shift)
                    .lanewise(VectorOperators.MIN, IntVector.broadcast(I256, limit))
                    .lanewise(VectorOperators.XOR, sign).lanewise(VectorOperators.SUB, sign).rearrange(RASTER_ORDER)
                    .lanewise(VectorOperators.XOR, MIDDLE_SIGNS).lanewise(VectorOperators.SUB, MIDDLE_SIGNS).lanewise(VectorOperators.AND, keep);
            if (frequency == 0) {
                dc[first] = c.lane(0);
                dc[first + 1] = c.lane(4);
            }
            var nonZero = raster.compare(VectorOperators.NE, I256_0).toLong();
            countA += Long.bitCount(nonZero & 15);
            countB += Long.bitCount(nonZero >>> 4);
            INTS_4.cast(raster.reinterpretShape(I128, 0))
                    .intoArray(levels, first * 16 + frequency * 4);
            INTS_4.cast(raster.rearrange(HALVES).reinterpretShape(I128, 0))
                    .intoArray(levels, (first + 1) * 16 + frequency * 4);
        }
        return countA | (countB << 8);
    }

    /**
     * The thresholds of {@link TransformKernels#INTER_NONZERO_THRESHOLDS} in the lane layout of
     * the chroma kernels, indexed by {@code (qp * 4 + verticalFrequency) * 8 + lane}.
     */
    private static final short[] LANE_NONZERO_8 = laneThresholds8(INTER_NONZERO_THRESHOLDS);

    /**
     * The thresholds of {@link TransformKernels#INTER_BIG_THRESHOLDS} in the lane layout of
     * {@link #LANE_NONZERO_8}.
     */
    private static final short[] LANE_BIG_8 = laneThresholds8(INTER_BIG_THRESHOLDS);

    /**
     * Rearranges a table of per-position thresholds into the lane layout of the chroma kernels.
     *
     * @param raster the thresholds at {@code 16 * qp + position}
     * @return the thresholds at {@code (qp * 4 + verticalFrequency) * 8 + lane}
     */
    private static short[] laneThresholds8(int[] raster) {
        var table = new short[52 * 32];
        for (var qp = 0; qp < 52; qp++) {
            for (var k = 0; k < 4; k++) {
                for (var lane = 0; lane < 8; lane++) {
                    table[(qp * 4 + k) * 8 + lane] = (short) raster[qp * 16 + k * 4 + FREQUENCY[lane & 3]];
                }
            }
        }
        return table;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation computes the coefficients like
     * {@link #forwardQuantizeChroma}, two blocks per vector, and compares their magnitudes with
     * per-lane thresholds instead of quantizing them.
     */
    @Override
    boolean quantizeChromaMasks(byte[] srcU, byte[] srcV, int srcOffset, int srcStride, byte[] predU,
                                byte[] predV, int predOffset, int predStride, int qp, int[] masks, int[] dc) {
        var tableBase = qp * 32;
        for (var band = 0; band < 4; band++) {
            var component = band >> 1;
            var src = component == 0 ? srcU : srcV;
            var pred = component == 0 ? predU : predV;
            var half = band & 1;
            var first = component * 4 + half * 2;
            var s = srcOffset + half * 4 * srcStride;
            var p = predOffset + half * 4 * predStride;
            var d0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.SUB, SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, pred, p)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)));
            var d1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.SUB, SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, pred, p + predStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)));
            var d2 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.SUB, SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, pred, p + 2 * predStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)));
            var d3 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 3 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.SUB, SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, pred, p + 3 * predStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)));
            var p0 = d0.lanewise(VectorOperators.ADD, d3);
            var p1 = d1.lanewise(VectorOperators.ADD, d2);
            var p2 = d1.lanewise(VectorOperators.SUB, d2);
            var p3 = d0.lanewise(VectorOperators.SUB, d3);
            long nonZero = 0;
            long big = 0;
            for (var pair = 0; pair < 2; pair++) {
                var weightsEven = pair * 64;
                var weightsOdd = weightsEven + 32;
                var thresholdsEven = tableBase + pair * 16;
                var thresholdsOdd = thresholdsEven + 8;
                var xEven = SHORTS_8.cast(p0).lanewise(VectorOperators.MUL, SHORTS_8.cast(ShortVector.fromArray(S128, ROW_WEIGHTS_8, weightsEven)))
                        .lanewise(VectorOperators.ADD, SHORTS_8.cast(p1).lanewise(VectorOperators.MUL, SHORTS_8.cast(ShortVector.fromArray(S128, ROW_WEIGHTS_8, weightsEven + 8))))
                        .lanewise(VectorOperators.ADD, SHORTS_8.cast(p2).lanewise(VectorOperators.MUL, SHORTS_8.cast(ShortVector.fromArray(S128, ROW_WEIGHTS_8, weightsEven + 16))))
                        .lanewise(VectorOperators.ADD, SHORTS_8.cast(p3).lanewise(VectorOperators.MUL, SHORTS_8.cast(ShortVector.fromArray(S128, ROW_WEIGHTS_8, weightsEven + 24))));
                var reversedEven = SHORTS_8.cast(INTS_4.cast(xEven.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).lanewise(VectorOperators.ROR, 16).reinterpretShape(S128, 0));
                var sumEven = xEven.lanewise(VectorOperators.ADD, reversedEven);
                var differenceEven = xEven.lanewise(VectorOperators.SUB, reversedEven);
                var sumSwappedEven = SHORTS_8.cast(INTS_4.cast(sumEven.reinterpretShape(IntVector.SPECIES_128, 0)).lanewise(VectorOperators.ROR, 16).reinterpretShape(S128, 0));
                var differenceSwappedEven = SHORTS_8.cast(INTS_4.cast(differenceEven.reinterpretShape(IntVector.SPECIES_128, 0)).lanewise(VectorOperators.ROR, 16).reinterpretShape(S128, 0));
                var doubledEven = differenceEven.lanewise(VectorOperators.ADD, differenceEven);
                var wEven = sumEven.lanewise(VectorOperators.ADD, sumSwappedEven)
                        .blend(differenceSwappedEven.lanewise(VectorOperators.SUB, doubledEven), POSITION_1_8)
                        .blend(sumEven.lanewise(VectorOperators.SUB, sumSwappedEven), POSITION_2_8)
                        .blend(doubledEven.lanewise(VectorOperators.ADD, differenceSwappedEven), POSITION_3_8);
                var xOdd = SHORTS_8.cast(p0).lanewise(VectorOperators.MUL, SHORTS_8.cast(ShortVector.fromArray(S128, ROW_WEIGHTS_8, weightsOdd)))
                        .lanewise(VectorOperators.ADD, SHORTS_8.cast(p1).lanewise(VectorOperators.MUL, SHORTS_8.cast(ShortVector.fromArray(S128, ROW_WEIGHTS_8, weightsOdd + 8))))
                        .lanewise(VectorOperators.ADD, SHORTS_8.cast(p2).lanewise(VectorOperators.MUL, SHORTS_8.cast(ShortVector.fromArray(S128, ROW_WEIGHTS_8, weightsOdd + 16))))
                        .lanewise(VectorOperators.ADD, SHORTS_8.cast(p3).lanewise(VectorOperators.MUL, SHORTS_8.cast(ShortVector.fromArray(S128, ROW_WEIGHTS_8, weightsOdd + 24))));
                var reversedOdd = SHORTS_8.cast(INTS_4.cast(xOdd.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).lanewise(VectorOperators.ROR, 16).reinterpretShape(S128, 0));
                var sumOdd = xOdd.lanewise(VectorOperators.ADD, reversedOdd);
                var differenceOdd = xOdd.lanewise(VectorOperators.SUB, reversedOdd);
                var sumSwappedOdd = SHORTS_8.cast(INTS_4.cast(sumOdd.reinterpretShape(IntVector.SPECIES_128, 0)).lanewise(VectorOperators.ROR, 16).reinterpretShape(S128, 0));
                var differenceSwappedOdd = SHORTS_8.cast(INTS_4.cast(differenceOdd.reinterpretShape(IntVector.SPECIES_128, 0)).lanewise(VectorOperators.ROR, 16).reinterpretShape(S128, 0));
                var doubledOdd = differenceOdd.lanewise(VectorOperators.ADD, differenceOdd);
                var wOdd = sumOdd.lanewise(VectorOperators.ADD, sumSwappedOdd)
                        .blend(differenceSwappedOdd.lanewise(VectorOperators.SUB, doubledOdd), POSITION_1_8)
                        .blend(sumOdd.lanewise(VectorOperators.SUB, sumSwappedOdd), POSITION_2_8)
                        .blend(doubledOdd.lanewise(VectorOperators.ADD, differenceSwappedOdd), POSITION_3_8);
                if (pair == 0) {
                    dc[first] = wEven.lane(0);
                    dc[first + 1] = wEven.lane(4);
                }
                var magnitudeEven = wEven.lanewise(VectorOperators.ABS);
                var magnitudeOdd = wOdd.lanewise(VectorOperators.ABS);
                var shiftEven = pair * 16;
                nonZero |= magnitudeEven.compare(VectorOperators.GE, SHORTS_8.cast(ShortVector.fromArray(S128, LANE_NONZERO_8, thresholdsEven))).toLong() << shiftEven;
                nonZero |= magnitudeOdd.compare(VectorOperators.GE, SHORTS_8.cast(ShortVector.fromArray(S128, LANE_NONZERO_8, thresholdsOdd))).toLong() << (shiftEven + 8);
                big |= magnitudeEven.compare(VectorOperators.GE, SHORTS_8.cast(ShortVector.fromArray(S128, LANE_BIG_8, thresholdsEven))).toLong() << shiftEven;
                big |= magnitudeOdd.compare(VectorOperators.GE, SHORTS_8.cast(ShortVector.fromArray(S128, LANE_BIG_8, thresholdsOdd))).toLong() << (shiftEven + 8);
            }
            var bigAc = 0;
            var nonZeroRaster = rasterOrder(nonZero);
            var bigRaster = rasterOrder(big);
            for (var block = 0; block < 2; block++) {
                var select = BLOCK_NIBBLES_8 << (4 * block);
                var blockMasks = ((int) Long.compress(nonZeroRaster, select)
                        | ((int) Long.compress(bigRaster, select) << 16)) & ~0x10001;
                masks[first + block] = blockMasks;
                bigAc |= blockMasks >>> 16;
            }
            if (bigAc != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    void dequantize4x4(int[] levels, int levelOffset, int start, int qp, int[] out, int outOffset) {
        var v = Tables.DEQUANT4[qp % 6];
        var shift = qp / 6;
        INTS_8.cast(IntVector.fromArray(I256, levels, levelOffset)).lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, v, 0)))
                .lanewise(VectorOperators.LSHL, shift).intoArray(out, outOffset);
        INTS_8.cast(IntVector.fromArray(I256, levels, levelOffset + 8)).lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, v, 8)))
                .lanewise(VectorOperators.LSHL, shift).intoArray(out, outOffset + 8);
        if (start != 0) {
            out[outOffset] = 0;
        }
    }
}
