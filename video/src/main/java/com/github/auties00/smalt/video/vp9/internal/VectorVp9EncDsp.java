package com.github.auties00.smalt.video.vp9.internal;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link Vp9EncDsp} with the Vector API; every result equals {@link ScalarVp9EncDsp}'s.
 *
 * <p>Blocks 8, 16, 32 and 64 samples wide run vector kernels; other widths use the scalar ones.
 *
 * @implNote This implementation follows the kernel rules of
 * {@link com.github.auties00.smalt.internal.simd.Simd}. The note at the top of the class body
 * explains the lane widths and why the block kernels handle several widths in one method.
 */
final class VectorVp9EncDsp extends Vp9EncDsp {
    /*
     * Samples are widened to 16-bit lanes before arithmetic. Sums of absolute differences and sums of samples
     * accumulate in unsigned 16-bit lanes, which cannot overflow for blocks up to 64x64; squared differences, at most
     * 255 * 255, are formed in 16-bit lanes, where they fit as unsigned values, and added in pairs into 32-bit lanes.
     *
     * The block kernels are larger than the C2 compiler's inlining limit for hot methods, by handling two or four
     * rows per iteration, so that they are compiled on their own rather than inlined into the large mode decision
     * methods, where the compiler can fail to turn their vector operations into vector instructions. A kernel over
     * blocks 16 and 32 samples wide is one method holding a loop of its own for each width and runs blocks 64 samples
     * wide as two 32-wide halves: the invocation counts of every width add up, so the method reaches the optimizing
     * compiler within the first frames, while a method per width that only the rarer large blocks call would stay
     * on the interpreted or first-tier path, where every vector operation allocates. A third unrolled loop would take
     * the method past 2000 bytes of bytecode. The sums are exact integers, so the split changes no result.
     *
     * Every shift takes an int count, never a vector of counts, which the compiler can leave on the allocating
     * fallback in a JVM running several codecs.
     */

    /**
     * The species of 8 bytes.
     */
    private static final VectorSpecies<Byte> B64 = ByteVector.SPECIES_64;

    /**
     * The species of 16 bytes.
     */
    private static final VectorSpecies<Byte> B128 = ByteVector.SPECIES_128;

    /**
     * The species of 32 bytes.
     */
    private static final VectorSpecies<Byte> B256 = ByteVector.SPECIES_256;

    /**
     * The species of 4 shorts.
     */
    private static final VectorSpecies<Short> S64 = ShortVector.SPECIES_64;

    /**
     * The species of 8 shorts.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * The species of 16 shorts.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * The species of 8 ints.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * The species of 4 ints.
     */
    private static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;

    /**
     * The species of 4 longs.
     */
    private static final VectorSpecies<Long> L256 = LongVector.SPECIES_256;

    /**
     * The exact class of 8-byte vectors.
     */
    private static final Class<ByteVector> BYTES_8 = vectorClass(B64);

    /**
     * The exact class of 16-byte vectors.
     */
    private static final Class<ByteVector> BYTES_16 = vectorClass(B128);

    /**
     * The exact class of 32-byte vectors.
     */
    private static final Class<ByteVector> BYTES_32 = vectorClass(B256);

    /**
     * The exact class of 4-short vectors.
     */
    private static final Class<ShortVector> SHORTS_4 = vectorClass(S64);

    /**
     * The exact class of 8-short vectors.
     */
    private static final Class<ShortVector> SHORTS_8 = vectorClass(S128);

    /**
     * The exact class of 16-short vectors.
     */
    private static final Class<ShortVector> SHORTS_16 = vectorClass(S256);

    /**
     * The exact class of 8-int vectors.
     */
    private static final Class<IntVector> INTS_8 = vectorClass(I256);

    /**
     * The exact class of 4-int vectors.
     */
    private static final Class<IntVector> INTS_4 = vectorClass(I128);

    /**
     * The exact class of 4-long vectors.
     */
    private static final Class<LongVector> LONGS_4 = vectorClass(L256);

    /**
     * The largest 16-bit value, the saturation limit of the quantizer, 8 lanes.
     */
    private static final IntVector INT16_MAX_8 = IntVector.broadcast(I256, 32767);

    /**
     * The lane selectors of the DC parameters: all bits set in the first lane of the first 8
     * entries, which select the DC coefficient of a block's first 8 coefficients, and zero
     * elsewhere, which the other coefficients load.
     */
    private static final int[] DC_SELECT = {-1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    /**
     * The mask of the low 16 bits of an int lane.
     */
    private static final IntVector LOW_HALVES_8 = IntVector.broadcast(I256, 0xFFFF);

    /**
     * The mask of the low 16 bits of an int lane, 4 lanes.
     */
    private static final IntVector LOW_HALVES_4 = IntVector.broadcast(I128, 0xFFFF);

    /**
     * The mask of the low byte of a short lane, 16 lanes.
     */
    private static final ShortVector LOW_BYTES_16 = ShortVector.broadcast(S256, (short) 0xFF);

    /**
     * Swaps neighboring int lanes, the first butterfly stage of an 8-point Hadamard transform.
     */
    private static final VectorShuffle<Integer> SWAP_1 = VectorShuffle.fromOp(I256, lane -> lane ^ 1);

    /**
     * Swaps int lanes two apart, the second butterfly stage of an 8-point Hadamard transform.
     */
    private static final VectorShuffle<Integer> SWAP_2 = VectorShuffle.fromOp(I256, lane -> lane ^ 2);

    /**
     * Swaps int lanes four apart, the third butterfly stage of an 8-point Hadamard transform.
     */
    private static final VectorShuffle<Integer> SWAP_4 = VectorShuffle.fromOp(I256, lane -> lane ^ 4);

    /**
     * Selects the odd int lanes, which take the differences of the first butterfly stage.
     */
    private static final VectorMask<Integer> BIT_1 = VectorMask.fromLong(I256, 0b10101010);

    /**
     * Selects the int lanes whose index has bit 1 set, which take the differences of the second
     * butterfly stage.
     */
    private static final VectorMask<Integer> BIT_2 = VectorMask.fromLong(I256, 0b11001100);

    /**
     * Selects the upper four int lanes, which take the differences of the third butterfly stage.
     */
    private static final VectorMask<Integer> BIT_4 = VectorMask.fromLong(I256, 0b11110000);

    /**
     * Exchanges neighboring lanes of a 4-int vector, the first step of a 4x4 transpose.
     */
    private static final VectorShuffle<Integer> SWAP_1_4 = VectorShuffle.fromOp(I128, lane -> lane ^ 1);

    /**
     * Exchanges lane pairs of a 4-int vector, the second step of a 4x4 transpose.
     */
    private static final VectorShuffle<Integer> SWAP_2_4 = VectorShuffle.fromOp(I128, lane -> lane ^ 2);

    /**
     * Selects the odd lanes of a 4-int vector.
     */
    private static final VectorMask<Integer> BIT_1_4 = VectorMask.fromLong(I128, 0b1010);

    /**
     * Selects the upper two lanes of a 4-int vector.
     */
    private static final VectorMask<Integer> BIT_2_4 = VectorMask.fromLong(I128, 0b1100);

    /**
     * All bits set in the first lane of a 4-int vector and none elsewhere.
     */
    private static final IntVector FIRST_LANE_4 = IntVector.fromArray(I128, new int[]{-1, 0, 0, 0}, 0);

    /**
     * Moves the outputs of the three butterfly stages to the output order of
     * {@code vpx_hadamard_8x8_c}: output {@code p} takes lane {@code p} of this shuffle.
     */
    private static final VectorShuffle<Integer> HADAMARD_ORDER = VectorShuffle.fromValues(I256, 0, 6, 4, 2, 3, 7,
            5, 1);

    /**
     * The value one, 8 lanes.
     */
    private static final IntVector ONE_8 = IntVector.broadcast(I256, 1);

    /**
     * The rounding offset of the cosine transforms' 14-bit fixed-point products, 8 lanes.
     */
    private static final IntVector DCT_ROUND_8 = IntVector.broadcast(I256, 1 << 13);

    /**
     * The precision of the cosine transforms' fixed-point constants, in bits.
     */
    private static final int DCT_SHIFT = 14;

    /**
     * The constant {@code cospi_4_64}, 8 lanes.
     */
    private static final IntVector COS_4 = IntVector.broadcast(I256, ScalarVp9Dsp.COSPI_4_64);

    /**
     * The constant {@code cospi_8_64}, 8 lanes.
     */
    private static final IntVector COS_8 = IntVector.broadcast(I256, ScalarVp9Dsp.COSPI_8_64);

    /**
     * The constant {@code cospi_12_64}, 8 lanes.
     */
    private static final IntVector COS_12 = IntVector.broadcast(I256, ScalarVp9Dsp.COSPI_12_64);

    /**
     * The constant {@code cospi_16_64}, 8 lanes.
     */
    private static final IntVector COS_16 = IntVector.broadcast(I256, ScalarVp9Dsp.COSPI_16_64);

    /**
     * The constant {@code cospi_20_64}, 8 lanes.
     */
    private static final IntVector COS_20 = IntVector.broadcast(I256, ScalarVp9Dsp.COSPI_20_64);

    /**
     * The constant {@code cospi_24_64}, 8 lanes.
     */
    private static final IntVector COS_24 = IntVector.broadcast(I256, ScalarVp9Dsp.COSPI_24_64);

    /**
     * The constant {@code cospi_28_64}, 8 lanes.
     */
    private static final IntVector COS_28 = IntVector.broadcast(I256, ScalarVp9Dsp.COSPI_28_64);

    /**
     * The rounding offset of the bilinear filters, 16 lanes.
     */
    private static final ShortVector ROUND_16 = ShortVector.broadcast(S256, (short) 64);

    /**
     * The rounding offset of the bilinear filters, 8 lanes.
     */
    private static final ShortVector ROUND_8 = ShortVector.broadcast(S128, (short) 64);

    /**
     * The scalar kernels, for the widths without a vector kernel.
     */
    private final ScalarVp9EncDsp scalar = new ScalarVp9EncDsp();

    /**
     * Creates the vector implementation.
     */
    VectorVp9EncDsp() {
    }

    /**
     * Returns the exact vector class of a species.
     *
     * @param species the species whose vector class is wanted
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <V> Class<V> vectorClass(VectorSpecies<?> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    int sad(byte[] src, int srcOff, int srcStride, byte[] ref, int refOff, int refStride, int w, int h) {
        if ((h & 1) == 0) {
            switch (w) {
                case 8 -> {
                    if ((h & 3) == 0) {
                        return sad8(src, srcOff, srcStride, ref, refOff, refStride, h);
                    }
                }
                case 16 -> {
                    return sad16(src, srcOff, srcStride, ref, refOff, refStride, h);
                }
                case 32 -> {
                    return sad32(src, srcOff, srcStride, ref, refOff, refStride, h);
                }
                case 64 -> {
                    return sad64(src, srcOff, srcStride, ref, refOff, refStride, h);
                }
                default -> {
                }
            }
        }
        return scalar.sad(src, srcOff, srcStride, ref, refOff, refStride, w, h);
    }

    /**
     * Returns the sum of absolute differences of two 8-wide blocks, four rows at a time.
     *
     * @param src the first block's plane
     * @param srcOff the index of its top-left sample
     * @param srcStride its row stride
     * @param ref the second block's plane
     * @param refOff the index of its top-left sample
     * @param refStride its row stride
     * @param h the height, a multiple of 4
     * @return the sum
     */
    private static int sad8(byte[] src, int srcOff, int srcStride, byte[] ref, int refOff, int refStride, int h) {
        var acc = SHORTS_8.cast(ShortVector.zero(S128));
        for (var r = 0; r < h; r += 4, srcOff += 4 * srcStride, refOff += 4 * refStride) {
            var a0 = BYTES_8.cast(ByteVector.fromArray(B64, src, srcOff));
            var b0 = BYTES_8.cast(ByteVector.fromArray(B64, ref, refOff));
            var a1 = BYTES_8.cast(ByteVector.fromArray(B64, src, srcOff + srcStride));
            var b1 = BYTES_8.cast(ByteVector.fromArray(B64, ref, refOff + refStride));
            var a2 = BYTES_8.cast(ByteVector.fromArray(B64, src, srcOff + 2 * srcStride));
            var b2 = BYTES_8.cast(ByteVector.fromArray(B64, ref, refOff + 2 * refStride));
            var a3 = BYTES_8.cast(ByteVector.fromArray(B64, src, srcOff + 3 * srcStride));
            var b3 = BYTES_8.cast(ByteVector.fromArray(B64, ref, refOff + 3 * refStride));
            var d0 = a0.lanewise(VectorOperators.UMAX, b0).lanewise(VectorOperators.SUB,
                    a0.lanewise(VectorOperators.UMIN, b0));
            var d1 = a1.lanewise(VectorOperators.UMAX, b1).lanewise(VectorOperators.SUB,
                    a1.lanewise(VectorOperators.UMIN, b1));
            var d2 = a2.lanewise(VectorOperators.UMAX, b2).lanewise(VectorOperators.SUB,
                    a2.lanewise(VectorOperators.UMIN, b2));
            var d3 = a3.lanewise(VectorOperators.UMAX, b3).lanewise(VectorOperators.SUB,
                    a3.lanewise(VectorOperators.UMIN, b3));
            var w0 = SHORTS_8.cast(d0.convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var w1 = SHORTS_8.cast(d1.convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var w2 = SHORTS_8.cast(d2.convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var w3 = SHORTS_8.cast(d3.convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            acc = w0.lanewise(VectorOperators.ADD, w1).lanewise(VectorOperators.ADD,
                    w2.lanewise(VectorOperators.ADD, w3)).lanewise(VectorOperators.ADD, acc);
        }
        return SHORTS_8.cast(acc).reduceLanes(VectorOperators.ADD) & 0xFFFF;
    }

    /**
     * Returns the sum of absolute differences of two 16-wide blocks, two rows at a time.
     *
     * @param src the first block's plane
     * @param srcOff the index of its top-left sample
     * @param srcStride its row stride
     * @param ref the second block's plane
     * @param refOff the index of its top-left sample
     * @param refStride its row stride
     * @param h the height, even
     * @return the sum
     */
    private static int sad16(byte[] src, int srcOff, int srcStride, byte[] ref, int refOff, int refStride, int h) {
        var acc = SHORTS_16.cast(ShortVector.zero(S256));
        for (var r = 0; r < h; r += 2, srcOff += 2 * srcStride, refOff += 2 * refStride) {
            var a0 = BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff));
            var b0 = BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff));
            var a1 = BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + srcStride));
            var b1 = BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride));
            var d0 = a0.lanewise(VectorOperators.UMAX, b0).lanewise(VectorOperators.SUB,
                    a0.lanewise(VectorOperators.UMIN, b0));
            var d1 = a1.lanewise(VectorOperators.UMAX, b1).lanewise(VectorOperators.SUB,
                    a1.lanewise(VectorOperators.UMIN, b1));
            var w0 = SHORTS_16.cast(d0.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var w1 = SHORTS_16.cast(d1.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            acc = w0.lanewise(VectorOperators.ADD, w1).lanewise(VectorOperators.ADD, acc);
        }
        var lanes = INTS_8.cast(SHORTS_16.cast(acc).reinterpretShape(I256, 0));
        return lanes.lanewise(VectorOperators.AND, LOW_HALVES_8).lanewise(VectorOperators.ADD,
                lanes.lanewise(VectorOperators.LSHR, 16)).reduceLanes(VectorOperators.ADD);
    }

    /**
     * Returns the sum of absolute differences of two 32-wide blocks, two rows at a time: the byte
     * differences are split into their even and odd samples by masking and shifting their 16-bit view,
     * which costs less than widening each half.
     *
     * @param src the first block's plane
     * @param srcOff the index of its top-left sample
     * @param srcStride its row stride
     * @param ref the second block's plane
     * @param refOff the index of its top-left sample
     * @param refStride its row stride
     * @param h the height, even and at most 64
     * @return the sum
     */
    private static int sad32(byte[] src, int srcOff, int srcStride, byte[] ref, int refOff, int refStride, int h) {
        var lowBytes = SHORTS_16.cast(LOW_BYTES_16);
        var acc = SHORTS_16.cast(ShortVector.zero(S256));
        for (var r = 0; r < h; r += 2, srcOff += 2 * srcStride, refOff += 2 * refStride) {
            var a0 = BYTES_32.cast(ByteVector.fromArray(B256, src, srcOff));
            var b0 = BYTES_32.cast(ByteVector.fromArray(B256, ref, refOff));
            var a1 = BYTES_32.cast(ByteVector.fromArray(B256, src, srcOff + srcStride));
            var b1 = BYTES_32.cast(ByteVector.fromArray(B256, ref, refOff + refStride));
            var d0 = SHORTS_16.cast(BYTES_32.cast(a0.lanewise(VectorOperators.UMAX, b0)
                    .lanewise(VectorOperators.SUB, a0.lanewise(VectorOperators.UMIN, b0))).reinterpretShape(S256, 0));
            var d1 = SHORTS_16.cast(BYTES_32.cast(a1.lanewise(VectorOperators.UMAX, b1)
                    .lanewise(VectorOperators.SUB, a1.lanewise(VectorOperators.UMIN, b1))).reinterpretShape(S256, 0));
            acc = d0.lanewise(VectorOperators.AND, lowBytes).lanewise(VectorOperators.ADD,
                    d0.lanewise(VectorOperators.LSHR, 8)).lanewise(VectorOperators.ADD,
                    d1.lanewise(VectorOperators.AND, lowBytes).lanewise(VectorOperators.ADD,
                            d1.lanewise(VectorOperators.LSHR, 8))).lanewise(VectorOperators.ADD, acc);
        }
        var lanes = INTS_8.cast(SHORTS_16.cast(acc).reinterpretShape(I256, 0));
        return lanes.lanewise(VectorOperators.AND, LOW_HALVES_8).lanewise(VectorOperators.ADD,
                lanes.lanewise(VectorOperators.LSHR, 16)).reduceLanes(VectorOperators.ADD);
    }

    /**
     * Returns the sum of absolute differences of two 64-wide blocks, one row at a time.
     *
     * @param src the first block's plane
     * @param srcOff the index of its top-left sample
     * @param srcStride its row stride
     * @param ref the second block's plane
     * @param refOff the index of its top-left sample
     * @param refStride its row stride
     * @param h the height, at most 64
     * @return the sum
     */
    private static int sad64(byte[] src, int srcOff, int srcStride, byte[] ref, int refOff, int refStride, int h) {
        var lowBytes = SHORTS_16.cast(LOW_BYTES_16);
        var acc = SHORTS_16.cast(ShortVector.zero(S256));
        for (var r = 0; r < h; r++, srcOff += srcStride, refOff += refStride) {
            var a0 = BYTES_32.cast(ByteVector.fromArray(B256, src, srcOff));
            var b0 = BYTES_32.cast(ByteVector.fromArray(B256, ref, refOff));
            var a1 = BYTES_32.cast(ByteVector.fromArray(B256, src, srcOff + 32));
            var b1 = BYTES_32.cast(ByteVector.fromArray(B256, ref, refOff + 32));
            var d0 = SHORTS_16.cast(BYTES_32.cast(a0.lanewise(VectorOperators.UMAX, b0)
                    .lanewise(VectorOperators.SUB, a0.lanewise(VectorOperators.UMIN, b0))).reinterpretShape(S256, 0));
            var d1 = SHORTS_16.cast(BYTES_32.cast(a1.lanewise(VectorOperators.UMAX, b1)
                    .lanewise(VectorOperators.SUB, a1.lanewise(VectorOperators.UMIN, b1))).reinterpretShape(S256, 0));
            acc = d0.lanewise(VectorOperators.AND, lowBytes).lanewise(VectorOperators.ADD,
                    d0.lanewise(VectorOperators.LSHR, 8)).lanewise(VectorOperators.ADD,
                    d1.lanewise(VectorOperators.AND, lowBytes).lanewise(VectorOperators.ADD,
                            d1.lanewise(VectorOperators.LSHR, 8))).lanewise(VectorOperators.ADD, acc);
        }
        var lanes = INTS_8.cast(SHORTS_16.cast(acc).reinterpretShape(I256, 0));
        return lanes.lanewise(VectorOperators.AND, LOW_HALVES_8).lanewise(VectorOperators.ADD,
                lanes.lanewise(VectorOperators.LSHR, 16)).reduceLanes(VectorOperators.ADD);
    }

    @Override
    void sad4(byte[] src, int srcOff, int srcStride, byte[] ref, int off0, int off1, int off2, int off3,
              int refStride, int w, int h, int[] out) {
        if (w == 16 || w == 32 || w == 64) {
            sad4Wide(src, srcOff, srcStride, ref, off0, off1, off2, off3, refStride, w, h, out);
        } else {
            super.sad4(src, srcOff, srcStride, ref, off0, off1, off2, off3, refStride, w, h, out);
        }
    }

    /**
     * Computes the sums of absolute differences of a block 16, 32 or 64 samples wide with four
     * candidates.
     *
     * @param src the block's plane
     * @param srcOff the index of its top-left sample
     * @param srcStride its row stride
     * @param ref the candidates' plane
     * @param off0 the index of the first candidate
     * @param off1 the index of the second candidate
     * @param off2 the index of the third candidate
     * @param off3 the index of the fourth candidate
     * @param refStride the candidates' row stride
     * @param w the width, 16, 32 or 64
     * @param h the height
     * @param out receives the four sums
     */
    private static void sad4Wide(byte[] src, int srcOff, int srcStride, byte[] ref, int off0, int off1, int off2,
                                 int off3, int refStride, int w, int h, int[] out) {
        if (w == 16) {
            var acc0 = SHORTS_16.cast(ShortVector.zero(S256));
            var acc1 = SHORTS_16.cast(ShortVector.zero(S256));
            var acc2 = SHORTS_16.cast(ShortVector.zero(S256));
            var acc3 = SHORTS_16.cast(ShortVector.zero(S256));
            for (var r = 0; r < h; r++, srcOff += srcStride, off0 += refStride, off1 += refStride, off2 += refStride,
                    off3 += refStride) {
                var a = BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff));
                var b0 = BYTES_16.cast(ByteVector.fromArray(B128, ref, off0));
                var b1 = BYTES_16.cast(ByteVector.fromArray(B128, ref, off1));
                var b2 = BYTES_16.cast(ByteVector.fromArray(B128, ref, off2));
                var b3 = BYTES_16.cast(ByteVector.fromArray(B128, ref, off3));
                var d0 = a.lanewise(VectorOperators.UMAX, b0).lanewise(VectorOperators.SUB,
                        a.lanewise(VectorOperators.UMIN, b0));
                var d1 = a.lanewise(VectorOperators.UMAX, b1).lanewise(VectorOperators.SUB,
                        a.lanewise(VectorOperators.UMIN, b1));
                var d2 = a.lanewise(VectorOperators.UMAX, b2).lanewise(VectorOperators.SUB,
                        a.lanewise(VectorOperators.UMIN, b2));
                var d3 = a.lanewise(VectorOperators.UMAX, b3).lanewise(VectorOperators.SUB,
                        a.lanewise(VectorOperators.UMIN, b3));
                acc0 = SHORTS_16.cast(d0.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                        .lanewise(VectorOperators.ADD, acc0);
                acc1 = SHORTS_16.cast(d1.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                        .lanewise(VectorOperators.ADD, acc1);
                acc2 = SHORTS_16.cast(d2.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                        .lanewise(VectorOperators.ADD, acc2);
                acc3 = SHORTS_16.cast(d3.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                        .lanewise(VectorOperators.ADD, acc3);
            }
            var low = INTS_8.cast(LOW_HALVES_8);
            var l0 = INTS_8.cast(SHORTS_16.cast(acc0).reinterpretShape(I256, 0));
            var l1 = INTS_8.cast(SHORTS_16.cast(acc1).reinterpretShape(I256, 0));
            var l2 = INTS_8.cast(SHORTS_16.cast(acc2).reinterpretShape(I256, 0));
            var l3 = INTS_8.cast(SHORTS_16.cast(acc3).reinterpretShape(I256, 0));
            out[0] = l0.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    l0.lanewise(VectorOperators.LSHR, 16)).reduceLanes(VectorOperators.ADD);
            out[1] = l1.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    l1.lanewise(VectorOperators.LSHR, 16)).reduceLanes(VectorOperators.ADD);
            out[2] = l2.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    l2.lanewise(VectorOperators.LSHR, 16)).reduceLanes(VectorOperators.ADD);
            out[3] = l3.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    l3.lanewise(VectorOperators.LSHR, 16)).reduceLanes(VectorOperators.ADD);
            return;
        }
        if (w == 32) {
            var lowBytes = SHORTS_16.cast(LOW_BYTES_16);
            var acc0 = SHORTS_16.cast(ShortVector.zero(S256));
            var acc1 = SHORTS_16.cast(ShortVector.zero(S256));
            var acc2 = SHORTS_16.cast(ShortVector.zero(S256));
            var acc3 = SHORTS_16.cast(ShortVector.zero(S256));
            for (var r = 0; r < h; r++, srcOff += srcStride, off0 += refStride, off1 += refStride, off2 += refStride,
                    off3 += refStride) {
                var a0 = BYTES_32.cast(ByteVector.fromArray(B256, src, srcOff));
                var b00 = BYTES_32.cast(ByteVector.fromArray(B256, ref, off0));
                var b01 = BYTES_32.cast(ByteVector.fromArray(B256, ref, off1));
                var b02 = BYTES_32.cast(ByteVector.fromArray(B256, ref, off2));
                var b03 = BYTES_32.cast(ByteVector.fromArray(B256, ref, off3));
                var d00 = SHORTS_16.cast(BYTES_32.cast(a0.lanewise(VectorOperators.UMAX, b00)
                        .lanewise(VectorOperators.SUB, a0.lanewise(VectorOperators.UMIN, b00))).reinterpretShape(S256, 0));
                var d01 = SHORTS_16.cast(BYTES_32.cast(a0.lanewise(VectorOperators.UMAX, b01)
                        .lanewise(VectorOperators.SUB, a0.lanewise(VectorOperators.UMIN, b01))).reinterpretShape(S256, 0));
                var d02 = SHORTS_16.cast(BYTES_32.cast(a0.lanewise(VectorOperators.UMAX, b02)
                        .lanewise(VectorOperators.SUB, a0.lanewise(VectorOperators.UMIN, b02))).reinterpretShape(S256, 0));
                var d03 = SHORTS_16.cast(BYTES_32.cast(a0.lanewise(VectorOperators.UMAX, b03)
                        .lanewise(VectorOperators.SUB, a0.lanewise(VectorOperators.UMIN, b03))).reinterpretShape(S256, 0));
                acc0 = d00.lanewise(VectorOperators.AND, lowBytes).lanewise(VectorOperators.ADD,
                        d00.lanewise(VectorOperators.LSHR, 8)).lanewise(VectorOperators.ADD, acc0);
                acc1 = d01.lanewise(VectorOperators.AND, lowBytes).lanewise(VectorOperators.ADD,
                        d01.lanewise(VectorOperators.LSHR, 8)).lanewise(VectorOperators.ADD, acc1);
                acc2 = d02.lanewise(VectorOperators.AND, lowBytes).lanewise(VectorOperators.ADD,
                        d02.lanewise(VectorOperators.LSHR, 8)).lanewise(VectorOperators.ADD, acc2);
                acc3 = d03.lanewise(VectorOperators.AND, lowBytes).lanewise(VectorOperators.ADD,
                        d03.lanewise(VectorOperators.LSHR, 8)).lanewise(VectorOperators.ADD, acc3);
            }
            var low = INTS_8.cast(LOW_HALVES_8);
            var l0 = INTS_8.cast(SHORTS_16.cast(acc0).reinterpretShape(I256, 0));
            var l1 = INTS_8.cast(SHORTS_16.cast(acc1).reinterpretShape(I256, 0));
            var l2 = INTS_8.cast(SHORTS_16.cast(acc2).reinterpretShape(I256, 0));
            var l3 = INTS_8.cast(SHORTS_16.cast(acc3).reinterpretShape(I256, 0));
            out[0] = l0.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    l0.lanewise(VectorOperators.LSHR, 16)).reduceLanes(VectorOperators.ADD);
            out[1] = l1.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    l1.lanewise(VectorOperators.LSHR, 16)).reduceLanes(VectorOperators.ADD);
            out[2] = l2.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    l2.lanewise(VectorOperators.LSHR, 16)).reduceLanes(VectorOperators.ADD);
            out[3] = l3.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    l3.lanewise(VectorOperators.LSHR, 16)).reduceLanes(VectorOperators.ADD);
            return;
        }
        sad4Wide(src, srcOff, srcStride, ref, off0, off1, off2, off3, refStride, 32, h, out);
        var sum0 = out[0];
        var sum1 = out[1];
        var sum2 = out[2];
        var sum3 = out[3];
        sad4Wide(src, srcOff + 32, srcStride, ref, off0 + 32, off1 + 32, off2 + 32, off3 + 32, refStride, 32, h, out);
        out[0] += sum0;
        out[1] += sum1;
        out[2] += sum2;
        out[3] += sum3;
    }

    @Override
    long variance(byte[] src, int srcOff, int srcStride, byte[] ref, int refOff, int refStride, int w, int h) {
        if (w == 8) {
            return variance8(src, srcOff, srcStride, ref, refOff, refStride, h);
        }
        if ((w & 15) == 0) {
            return variance16(src, srcOff, srcStride, ref, refOff, refStride, w, h);
        }
        return scalar.variance(src, srcOff, srcStride, ref, refOff, refStride, w, h);
    }

    /**
     * Returns the packed variance sums of two 8-wide blocks.
     *
     * @param src the first block's plane
     * @param srcOff the index of its top-left sample
     * @param srcStride its row stride
     * @param ref the second block's plane
     * @param refOff the index of its top-left sample
     * @param refStride its row stride
     * @param h the height, at most 64
     * @return the packed sums
     */
    private static long variance8(byte[] src, int srcOff, int srcStride, byte[] ref, int refOff, int refStride,
                                  int h) {
        var low = INTS_4.cast(LOW_HALVES_4);
        var squares = INTS_4.cast(IntVector.zero(I128));
        var sumsA = SHORTS_8.cast(ShortVector.zero(S128));
        var sumsB = SHORTS_8.cast(ShortVector.zero(S128));
        for (var r = 0; r < h; r++, srcOff += srcStride, refOff += refStride) {
            var a = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, srcOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, ref, refOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            sumsA = a.lanewise(VectorOperators.ADD, sumsA);
            sumsB = b.lanewise(VectorOperators.ADD, sumsB);
            var d = a.lanewise(VectorOperators.SUB, b);
            var pairs = INTS_4.cast(SHORTS_8.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I128, 0));
            squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
        }
        var pairsA = INTS_4.cast(SHORTS_8.cast(sumsA).reinterpretShape(I128, 0));
        var pairsB = INTS_4.cast(SHORTS_8.cast(sumsB).reinterpretShape(I128, 0));
        var sum = pairsA.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                pairsA.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.SUB,
                pairsB.lanewise(VectorOperators.AND, low)).lanewise(VectorOperators.SUB,
                pairsB.lanewise(VectorOperators.LSHR, 16));
        return pack(INTS_4.cast(squares).reduceLanes(VectorOperators.ADD),
                INTS_4.cast(sum).reduceLanes(VectorOperators.ADD));
    }

    /**
     * Returns the packed variance sums of two blocks whose width is a multiple of 16.
     *
     * @param src the first block's plane
     * @param srcOff the index of its top-left sample
     * @param srcStride its row stride
     * @param ref the second block's plane
     * @param refOff the index of its top-left sample
     * @param refStride its row stride
     * @param w the width, 16, 32 or 64
     * @param h the height
     * @return the packed sums
     */
    private static long variance16(byte[] src, int srcOff, int srcStride, byte[] ref, int refOff, int refStride,
                                   int w, int h) {
        if (w == 16) {
            var low = INTS_8.cast(LOW_HALVES_8);
            var squares = INTS_8.cast(IntVector.zero(I256));
            var sumsA = SHORTS_16.cast(ShortVector.zero(S256));
            var sumsB = SHORTS_16.cast(ShortVector.zero(S256));
            for (var r = 0; r < h; r++, srcOff += srcStride, refOff += refStride) {
                var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                sumsA = a.lanewise(VectorOperators.ADD, sumsA);
                sumsB = b.lanewise(VectorOperators.ADD, sumsB);
                var d = a.lanewise(VectorOperators.SUB, b);
                var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                        pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
            }
            var pairsA = INTS_8.cast(SHORTS_16.cast(sumsA).reinterpretShape(I256, 0));
            var pairsB = INTS_8.cast(SHORTS_16.cast(sumsB).reinterpretShape(I256, 0));
            var sum = pairsA.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    pairsA.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.SUB,
                    pairsB.lanewise(VectorOperators.AND, low)).lanewise(VectorOperators.SUB,
                    pairsB.lanewise(VectorOperators.LSHR, 16));
            return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD),
                    INTS_8.cast(sum).reduceLanes(VectorOperators.ADD));
        }
        if (w == 32) {
            var low = INTS_8.cast(LOW_HALVES_8);
            var squares = INTS_8.cast(IntVector.zero(I256));
            var sumsA = SHORTS_16.cast(ShortVector.zero(S256));
            var sumsB = SHORTS_16.cast(ShortVector.zero(S256));
            for (var r = 0; r < h; r++, srcOff += srcStride, refOff += refStride) {
                {
                    var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    sumsA = a.lanewise(VectorOperators.ADD, sumsA);
                    sumsB = b.lanewise(VectorOperators.ADD, sumsB);
                    var d = a.lanewise(VectorOperators.SUB, b);
                    var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                    squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                            pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
                }
                {
                    var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    sumsA = a.lanewise(VectorOperators.ADD, sumsA);
                    sumsB = b.lanewise(VectorOperators.ADD, sumsB);
                    var d = a.lanewise(VectorOperators.SUB, b);
                    var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                    squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                            pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
                }
            }
            var pairsA = INTS_8.cast(SHORTS_16.cast(sumsA).reinterpretShape(I256, 0));
            var pairsB = INTS_8.cast(SHORTS_16.cast(sumsB).reinterpretShape(I256, 0));
            var sum = pairsA.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    pairsA.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.SUB,
                    pairsB.lanewise(VectorOperators.AND, low)).lanewise(VectorOperators.SUB,
                    pairsB.lanewise(VectorOperators.LSHR, 16));
            return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD),
                    INTS_8.cast(sum).reduceLanes(VectorOperators.ADD));
        }
        return addPacked(variance16(src, srcOff, srcStride, ref, refOff, refStride, 32, h),
                variance16(src, srcOff + 32, srcStride, ref, refOff + 32, refStride, 32, h));
    }

    /**
     * Returns the packed sums of two disjoint parts of a block: the sums of squared differences
     * and the sums of differences add.
     *
     * @param first the packed sums of the first part
     * @param second the packed sums of the second part
     * @return the packed sums of both parts
     */
    private static long addPacked(long first, long second) {
        return pack((first >>> 32) + (second >>> 32), (int) first + (int) second);
    }

    @Override
    long subPixelVariance(byte[] ref, int refOff, int refStride, int xOffset, int yOffset, byte[] src, int srcOff,
                          int srcStride, int w, int h, short[] scratch) {
        if (w == 8) {
            horizontal8(ref, refOff, refStride, BILINEAR_TAPS[xOffset * 2], BILINEAR_TAPS[xOffset * 2 + 1], scratch,
                    h + 1);
            return vertical8(scratch, BILINEAR_TAPS[yOffset * 2], BILINEAR_TAPS[yOffset * 2 + 1], src, srcOff,
                    srcStride, h);
        }
        if ((w & 15) == 0) {
            if (yOffset == 0) {
                if (xOffset == 0) {
                    return variance16(ref, refOff, refStride, src, srcOff, srcStride, w, h);
                }
                if (xOffset == 4) {
                    return horizontalHalf16(ref, refOff, refStride, src, srcOff, srcStride, w, h);
                }
                return horizontalOnly16(ref, refOff, refStride, BILINEAR_TAPS[xOffset * 2],
                        BILINEAR_TAPS[xOffset * 2 + 1], src, srcOff, srcStride, w, h);
            }
            if (xOffset == 0) {
                if (yOffset == 4) {
                    return verticalHalf16(ref, refOff, refStride, src, srcOff, srcStride, w, h);
                }
                return verticalOnly16(ref, refOff, refStride, BILINEAR_TAPS[yOffset * 2],
                        BILINEAR_TAPS[yOffset * 2 + 1], src, srcOff, srcStride, w, h);
            }
            var x0 = BILINEAR_TAPS[xOffset * 2];
            var x1 = BILINEAR_TAPS[xOffset * 2 + 1];
            var y0 = BILINEAR_TAPS[yOffset * 2];
            var y1 = BILINEAR_TAPS[yOffset * 2 + 1];
            if (w == 64) {
                horizontal16(ref, refOff, refStride, x0, x1, scratch, 32, h + 1);
                var left = vertical16(scratch, y0, y1, src, srcOff, srcStride, 32, h);
                horizontal16(ref, refOff + 32, refStride, x0, x1, scratch, 32, h + 1);
                return addPacked(left, vertical16(scratch, y0, y1, src, srcOff + 32, srcStride, 32, h));
            }
            horizontal16(ref, refOff, refStride, x0, x1, scratch, w, h + 1);
            return vertical16(scratch, y0, y1, src, srcOff, srcStride, w, h);
        }
        return scalar.subPixelVariance(ref, refOff, refStride, xOffset, yOffset, src, srcOff, srcStride, w, h,
                scratch);
    }

    /**
     * Applies the horizontal bilinear pass to rows 8 samples wide, two rows at a time.
     *
     * @param ref the reference plane
     * @param refOff the index of the first sample
     * @param refStride the reference row stride
     * @param t0 the tap of the left sample
     * @param t1 the tap of the right sample
     * @param scratch receives the filtered rows, 8 per row
     * @param rows the number of rows
     */
    private static void horizontal8(byte[] ref, int refOff, int refStride, int t0, int t1, short[] scratch,
                                    int rows) {
        var tap0 = SHORTS_8.cast(ShortVector.broadcast(S128, (short) t0));
        var tap1 = SHORTS_8.cast(ShortVector.broadcast(S128, (short) t1));
        var round = SHORTS_8.cast(ROUND_8);
        var r = 0;
        for (; r + 1 < rows; r += 2, refOff += 2 * refStride) {
            var a0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, ref, refOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, ref, refOff + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var a1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, ref, refOff + refStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, ref, refOff + refStride + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            SHORTS_8.cast(a0.lanewise(VectorOperators.MUL, tap0)
                    .lanewise(VectorOperators.ADD, b0.lanewise(VectorOperators.MUL, tap1))
                    .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7)).intoArray(scratch, r * 8);
            SHORTS_8.cast(a1.lanewise(VectorOperators.MUL, tap0)
                    .lanewise(VectorOperators.ADD, b1.lanewise(VectorOperators.MUL, tap1))
                    .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7)).intoArray(scratch, r * 8 + 8);
        }
        if (r < rows) {
            var a = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, ref, refOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, ref, refOff + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            SHORTS_8.cast(a.lanewise(VectorOperators.MUL, tap0)
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, tap1))
                    .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7)).intoArray(scratch, r * 8);
        }
    }

    /**
     * Applies the vertical bilinear pass to rows 8 samples wide and returns the packed variance
     * sums of the result against a second block.
     *
     * @param scratch the horizontally filtered rows
     * @param t0 the tap of the upper sample
     * @param t1 the tap of the lower sample
     * @param src the second block's plane
     * @param srcOff the index of its top-left sample
     * @param srcStride its row stride
     * @param h the height
     * @return the packed sums
     */
    private static long vertical8(short[] scratch, int t0, int t1, byte[] src, int srcOff, int srcStride, int h) {
        var tap0 = SHORTS_8.cast(ShortVector.broadcast(S128, (short) t0));
        var tap1 = SHORTS_8.cast(ShortVector.broadcast(S128, (short) t1));
        var round = SHORTS_8.cast(ROUND_8);
        var low = INTS_4.cast(LOW_HALVES_4);
        var squares = INTS_4.cast(IntVector.zero(I128));
        var sumsP = SHORTS_8.cast(ShortVector.zero(S128));
        var sumsS = SHORTS_8.cast(ShortVector.zero(S128));
        for (var r = 0; r < h; r++, srcOff += srcStride) {
            var top = SHORTS_8.cast(ShortVector.fromArray(S128, scratch, r * 8));
            var bottom = SHORTS_8.cast(ShortVector.fromArray(S128, scratch, r * 8 + 8));
            var p = top.lanewise(VectorOperators.MUL, tap0)
                    .lanewise(VectorOperators.ADD, bottom.lanewise(VectorOperators.MUL, tap1))
                    .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7);
            var s = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, srcOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            sumsP = p.lanewise(VectorOperators.ADD, sumsP);
            sumsS = s.lanewise(VectorOperators.ADD, sumsS);
            var d = p.lanewise(VectorOperators.SUB, s);
            var pairs = INTS_4.cast(SHORTS_8.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I128, 0));
            squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
        }
        var pairsP = INTS_4.cast(SHORTS_8.cast(sumsP).reinterpretShape(I128, 0));
        var pairsS = INTS_4.cast(SHORTS_8.cast(sumsS).reinterpretShape(I128, 0));
        var sum = pairsP.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                pairsP.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.SUB,
                pairsS.lanewise(VectorOperators.AND, low)).lanewise(VectorOperators.SUB,
                pairsS.lanewise(VectorOperators.LSHR, 16));
        return pack(INTS_4.cast(squares).reduceLanes(VectorOperators.ADD),
                INTS_4.cast(sum).reduceLanes(VectorOperators.ADD));
    }

    /**
     * Applies the horizontal bilinear pass to rows whose width is a multiple of 16, two rows at
     * a time.
     *
     * @param ref the reference plane
     * @param refOff the index of the first sample
     * @param refStride the reference row stride
     * @param t0 the tap of the left sample
     * @param t1 the tap of the right sample
     * @param scratch receives the filtered rows, {@code w} per row
     * @param w the width, 16 or 32
     * @param rows the number of rows
     */
    private static void horizontal16(byte[] ref, int refOff, int refStride, int t0, int t1, short[] scratch, int w,
                                     int rows) {
        if (w == 16) {
            var tap0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t0));
            var tap1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t1));
            var round = SHORTS_16.cast(ROUND_16);
            var r = 0;
            for (; r + 1 < rows; r += 2, refOff += 2 * refStride) {
                var a0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var a1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride + 1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                SHORTS_16.cast(a0.lanewise(VectorOperators.MUL, tap0)
                        .lanewise(VectorOperators.ADD, b0.lanewise(VectorOperators.MUL, tap1))
                        .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7))
                        .intoArray(scratch, r * 16);
                SHORTS_16.cast(a1.lanewise(VectorOperators.MUL, tap0)
                        .lanewise(VectorOperators.ADD, b1.lanewise(VectorOperators.MUL, tap1))
                        .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7))
                        .intoArray(scratch, r * 16 + 16);
            }
            if (r < rows) {
                var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                SHORTS_16.cast(a.lanewise(VectorOperators.MUL, tap0)
                        .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, tap1))
                        .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7))
                        .intoArray(scratch, r * 16);
            }
            return;
        }
        var tap0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t0));
        var tap1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t1));
        var round = SHORTS_16.cast(ROUND_16);
        var r = 0;
        for (; r + 1 < rows; r += 2, refOff += 2 * refStride) {
            {
                var a0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var a1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride + 1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                SHORTS_16.cast(a0.lanewise(VectorOperators.MUL, tap0)
                        .lanewise(VectorOperators.ADD, b0.lanewise(VectorOperators.MUL, tap1))
                        .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7))
                        .intoArray(scratch, r * 32);
                SHORTS_16.cast(a1.lanewise(VectorOperators.MUL, tap0)
                        .lanewise(VectorOperators.ADD, b1.lanewise(VectorOperators.MUL, tap1))
                        .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7))
                        .intoArray(scratch, r * 32 + 32);
            }
            {
                var a0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 16))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 16 + 1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var a1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride + 16))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride + 16 + 1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                SHORTS_16.cast(a0.lanewise(VectorOperators.MUL, tap0)
                        .lanewise(VectorOperators.ADD, b0.lanewise(VectorOperators.MUL, tap1))
                        .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7))
                        .intoArray(scratch, r * 32 + 16);
                SHORTS_16.cast(a1.lanewise(VectorOperators.MUL, tap0)
                        .lanewise(VectorOperators.ADD, b1.lanewise(VectorOperators.MUL, tap1))
                        .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7))
                        .intoArray(scratch, r * 32 + 32 + 16);
            }
        }
        if (r < rows) {
            {
                var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                SHORTS_16.cast(a.lanewise(VectorOperators.MUL, tap0)
                        .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, tap1))
                        .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7))
                        .intoArray(scratch, r * 32);
            }
            {
                var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 16))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 16 + 1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                SHORTS_16.cast(a.lanewise(VectorOperators.MUL, tap0)
                        .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, tap1))
                        .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7))
                        .intoArray(scratch, r * 32 + 16);
            }
        }
    }

    /**
     * Applies the vertical bilinear pass to rows whose width is a multiple of 16 and returns the
     * packed variance sums of the result against a second block.
     *
     * @param scratch the horizontally filtered rows
     * @param t0 the tap of the upper sample
     * @param t1 the tap of the lower sample
     * @param src the second block's plane
     * @param srcOff the index of its top-left sample
     * @param srcStride its row stride
     * @param w the width, 16 or 32
     * @param h the height
     * @return the packed sums
     */
    private static long vertical16(short[] scratch, int t0, int t1, byte[] src, int srcOff, int srcStride, int w,
                                   int h) {
        if (w == 16) {
            var tap0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t0));
            var tap1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t1));
            var round = SHORTS_16.cast(ROUND_16);
            var low = INTS_8.cast(LOW_HALVES_8);
            var squares = INTS_8.cast(IntVector.zero(I256));
            var sumsP = SHORTS_16.cast(ShortVector.zero(S256));
            var sumsS = SHORTS_16.cast(ShortVector.zero(S256));
            for (var r = 0; r < h; r++, srcOff += srcStride) {
                var top = SHORTS_16.cast(ShortVector.fromArray(S256, scratch, r * 16));
                var bottom = SHORTS_16.cast(ShortVector.fromArray(S256, scratch, r * 16 + 16));
                var p = top.lanewise(VectorOperators.MUL, tap0)
                        .lanewise(VectorOperators.ADD, bottom.lanewise(VectorOperators.MUL, tap1))
                        .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7);
                var s = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                sumsP = p.lanewise(VectorOperators.ADD, sumsP);
                sumsS = s.lanewise(VectorOperators.ADD, sumsS);
                var d = p.lanewise(VectorOperators.SUB, s);
                var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                        pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
            }
            var pairsP = INTS_8.cast(SHORTS_16.cast(sumsP).reinterpretShape(I256, 0));
            var pairsS = INTS_8.cast(SHORTS_16.cast(sumsS).reinterpretShape(I256, 0));
            var sum = pairsP.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    pairsP.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.AND, low)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.LSHR, 16));
            return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD),
                    INTS_8.cast(sum).reduceLanes(VectorOperators.ADD));
        }
        var tap0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t0));
        var tap1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t1));
        var round = SHORTS_16.cast(ROUND_16);
        var low = INTS_8.cast(LOW_HALVES_8);
        var squares = INTS_8.cast(IntVector.zero(I256));
        var sumsP = SHORTS_16.cast(ShortVector.zero(S256));
        var sumsS = SHORTS_16.cast(ShortVector.zero(S256));
        for (var r = 0; r < h; r++, srcOff += srcStride) {
            {
                var top = SHORTS_16.cast(ShortVector.fromArray(S256, scratch, r * 32));
                var bottom = SHORTS_16.cast(ShortVector.fromArray(S256, scratch, r * 32 + 32));
                var p = top.lanewise(VectorOperators.MUL, tap0)
                        .lanewise(VectorOperators.ADD, bottom.lanewise(VectorOperators.MUL, tap1))
                        .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7);
                var s = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                sumsP = p.lanewise(VectorOperators.ADD, sumsP);
                sumsS = s.lanewise(VectorOperators.ADD, sumsS);
                var d = p.lanewise(VectorOperators.SUB, s);
                var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                        pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
            }
            {
                var top = SHORTS_16.cast(ShortVector.fromArray(S256, scratch, r * 32 + 16));
                var bottom = SHORTS_16.cast(ShortVector.fromArray(S256, scratch, r * 32 + 32 + 16));
                var p = top.lanewise(VectorOperators.MUL, tap0)
                        .lanewise(VectorOperators.ADD, bottom.lanewise(VectorOperators.MUL, tap1))
                        .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 7);
                var s = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + 16))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                sumsP = p.lanewise(VectorOperators.ADD, sumsP);
                sumsS = s.lanewise(VectorOperators.ADD, sumsS);
                var d = p.lanewise(VectorOperators.SUB, s);
                var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                        pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
            }
        }
        var pairsP = INTS_8.cast(SHORTS_16.cast(sumsP).reinterpretShape(I256, 0));
        var pairsS = INTS_8.cast(SHORTS_16.cast(sumsS).reinterpretShape(I256, 0));
        var sum = pairsP.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                pairsP.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.SUB,
                pairsS.lanewise(VectorOperators.AND, low)).lanewise(VectorOperators.SUB,
                pairsS.lanewise(VectorOperators.LSHR, 16));
        return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD),
                INTS_8.cast(sum).reduceLanes(VectorOperators.ADD));
    }

    /**
     * Returns the packed variance sums of the horizontal bilinear interpolation of a block, whose width is a
     * multiple of 16, against a second block, without the vertical pass.
     *
     * @param ref the reference plane
     * @param refOff the index of the first sample
     * @param refStride the reference row stride
     * @param t0 the tap of the first sample
     * @param t1 the tap of the second sample
     * @param src the second block's plane
     * @param srcOff the index of its top-left sample
     * @param srcStride its row stride
     * @param w the width, a multiple of 16
     * @param h the height
     * @return the packed sums
     */
    private static long horizontalOnly16(byte[] ref, int refOff, int refStride, int t0, int t1,
                                          byte[] src, int srcOff, int srcStride, int w, int h) {
        if (w == 16) {
            var tap0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t0));
            var tap1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t1));
            var round = SHORTS_16.cast(ROUND_16);
            var low = INTS_8.cast(LOW_HALVES_8);
            var squares = INTS_8.cast(IntVector.zero(I256));
            var sumsP = SHORTS_16.cast(ShortVector.zero(S256));
            var sumsS = SHORTS_16.cast(ShortVector.zero(S256));
            for (var r = 0; r < h; r++, refOff += refStride, srcOff += srcStride) {
                var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var p = a.lanewise(VectorOperators.MUL, tap0).lanewise(VectorOperators.ADD,
                        b.lanewise(VectorOperators.MUL, tap1)).lanewise(VectorOperators.ADD, round)
                        .lanewise(VectorOperators.ASHR, 7);
                var s = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                sumsP = p.lanewise(VectorOperators.ADD, sumsP);
                sumsS = s.lanewise(VectorOperators.ADD, sumsS);
                var d = p.lanewise(VectorOperators.SUB, s);
                var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                        pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
            }
            var pairsP = INTS_8.cast(SHORTS_16.cast(sumsP).reinterpretShape(I256, 0));
            var pairsS = INTS_8.cast(SHORTS_16.cast(sumsS).reinterpretShape(I256, 0));
            var sum = pairsP.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    pairsP.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.AND, low)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.LSHR, 16));
            return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD),
                    INTS_8.cast(sum).reduceLanes(VectorOperators.ADD));
        }
        if (w == 32) {
            var tap0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t0));
            var tap1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t1));
            var round = SHORTS_16.cast(ROUND_16);
            var low = INTS_8.cast(LOW_HALVES_8);
            var squares = INTS_8.cast(IntVector.zero(I256));
            var sumsP = SHORTS_16.cast(ShortVector.zero(S256));
            var sumsS = SHORTS_16.cast(ShortVector.zero(S256));
            for (var r = 0; r < h; r++, refOff += refStride, srcOff += srcStride) {
                {
                    var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 1))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var p = a.lanewise(VectorOperators.MUL, tap0).lanewise(VectorOperators.ADD,
                            b.lanewise(VectorOperators.MUL, tap1)).lanewise(VectorOperators.ADD, round)
                            .lanewise(VectorOperators.ASHR, 7);
                    var s = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    sumsP = p.lanewise(VectorOperators.ADD, sumsP);
                    sumsS = s.lanewise(VectorOperators.ADD, sumsS);
                    var d = p.lanewise(VectorOperators.SUB, s);
                    var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                    squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                            pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
                }
                {
                    var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 16 + 1))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var p = a.lanewise(VectorOperators.MUL, tap0).lanewise(VectorOperators.ADD,
                            b.lanewise(VectorOperators.MUL, tap1)).lanewise(VectorOperators.ADD, round)
                            .lanewise(VectorOperators.ASHR, 7);
                    var s = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    sumsP = p.lanewise(VectorOperators.ADD, sumsP);
                    sumsS = s.lanewise(VectorOperators.ADD, sumsS);
                    var d = p.lanewise(VectorOperators.SUB, s);
                    var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                    squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                            pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
                }
            }
            var pairsP = INTS_8.cast(SHORTS_16.cast(sumsP).reinterpretShape(I256, 0));
            var pairsS = INTS_8.cast(SHORTS_16.cast(sumsS).reinterpretShape(I256, 0));
            var sum = pairsP.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    pairsP.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.AND, low)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.LSHR, 16));
            return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD),
                    INTS_8.cast(sum).reduceLanes(VectorOperators.ADD));
        }
        return addPacked(horizontalOnly16(ref, refOff, refStride, t0, t1, src, srcOff, srcStride, 32, h),
                horizontalOnly16(ref, refOff + 32, refStride, t0, t1, src, srcOff + 32, srcStride, 32, h));
    }

    /**
     * Returns the packed variance sums of the half-sample horizontal bilinear interpolation of a block, whose
     * width is a multiple of 16, against a second block.
     *
     * @param ref the reference plane
     * @param refOff the index of the first sample
     * @param refStride the reference row stride
     * @param src the second block's plane
     * @param srcOff the index of its top-left sample
     * @param srcStride its row stride
     * @param w the width, a multiple of 16
     * @param h the height
     * @return the packed sums
     */
    private static long horizontalHalf16(byte[] ref, int refOff, int refStride, byte[] src, int srcOff, int srcStride,
            int w, int h) {
        if (w == 16) {
            var one = SHORTS_16.cast(ShortVector.broadcast(S256, (short) 1));
            var low = INTS_8.cast(LOW_HALVES_8);
            var squares = INTS_8.cast(IntVector.zero(I256));
            var sumsP = SHORTS_16.cast(ShortVector.zero(S256));
            var sumsS = SHORTS_16.cast(ShortVector.zero(S256));
            for (var r = 0; r < h; r++, refOff += refStride, srcOff += srcStride) {
                var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var p = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ADD, one)
                        .lanewise(VectorOperators.ASHR, 1);
                var s = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                sumsP = p.lanewise(VectorOperators.ADD, sumsP);
                sumsS = s.lanewise(VectorOperators.ADD, sumsS);
                var d = p.lanewise(VectorOperators.SUB, s);
                var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                        pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
            }
            var pairsP = INTS_8.cast(SHORTS_16.cast(sumsP).reinterpretShape(I256, 0));
            var pairsS = INTS_8.cast(SHORTS_16.cast(sumsS).reinterpretShape(I256, 0));
            var sum = pairsP.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    pairsP.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.AND, low)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.LSHR, 16));
            return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD),
                    INTS_8.cast(sum).reduceLanes(VectorOperators.ADD));
        }
        if (w == 32) {
            var one = SHORTS_16.cast(ShortVector.broadcast(S256, (short) 1));
            var low = INTS_8.cast(LOW_HALVES_8);
            var squares = INTS_8.cast(IntVector.zero(I256));
            var sumsP = SHORTS_16.cast(ShortVector.zero(S256));
            var sumsS = SHORTS_16.cast(ShortVector.zero(S256));
            for (var r = 0; r < h; r++, refOff += refStride, srcOff += srcStride) {
                {
                    var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 1))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var p = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ADD, one)
                            .lanewise(VectorOperators.ASHR, 1);
                    var s = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    sumsP = p.lanewise(VectorOperators.ADD, sumsP);
                    sumsS = s.lanewise(VectorOperators.ADD, sumsS);
                    var d = p.lanewise(VectorOperators.SUB, s);
                    var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                    squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                            pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
                }
                {
                    var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 16 + 1))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var p = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ADD, one)
                            .lanewise(VectorOperators.ASHR, 1);
                    var s = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    sumsP = p.lanewise(VectorOperators.ADD, sumsP);
                    sumsS = s.lanewise(VectorOperators.ADD, sumsS);
                    var d = p.lanewise(VectorOperators.SUB, s);
                    var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                    squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                            pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
                }
            }
            var pairsP = INTS_8.cast(SHORTS_16.cast(sumsP).reinterpretShape(I256, 0));
            var pairsS = INTS_8.cast(SHORTS_16.cast(sumsS).reinterpretShape(I256, 0));
            var sum = pairsP.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    pairsP.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.AND, low)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.LSHR, 16));
            return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD),
                    INTS_8.cast(sum).reduceLanes(VectorOperators.ADD));
        }
        return addPacked(horizontalHalf16(ref, refOff, refStride, src, srcOff, srcStride, 32, h),
                horizontalHalf16(ref, refOff + 32, refStride, src, srcOff + 32, srcStride, 32, h));
    }

    /**
     * Returns the packed variance sums of the vertical bilinear interpolation of a block, whose width is a
     * multiple of 16, against a second block, without the horizontal pass.
     *
     * @param ref the reference plane
     * @param refOff the index of the first sample
     * @param refStride the reference row stride
     * @param t0 the tap of the first sample
     * @param t1 the tap of the second sample
     * @param src the second block's plane
     * @param srcOff the index of its top-left sample
     * @param srcStride its row stride
     * @param w the width, a multiple of 16
     * @param h the height
     * @return the packed sums
     */
    private static long verticalOnly16(byte[] ref, int refOff, int refStride, int t0, int t1,
                                          byte[] src, int srcOff, int srcStride, int w, int h) {
        if (w == 16) {
            var tap0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t0));
            var tap1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t1));
            var round = SHORTS_16.cast(ROUND_16);
            var low = INTS_8.cast(LOW_HALVES_8);
            var squares = INTS_8.cast(IntVector.zero(I256));
            var sumsP = SHORTS_16.cast(ShortVector.zero(S256));
            var sumsS = SHORTS_16.cast(ShortVector.zero(S256));
            for (var r = 0; r < h; r++, refOff += refStride, srcOff += srcStride) {
                var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var p = a.lanewise(VectorOperators.MUL, tap0).lanewise(VectorOperators.ADD,
                        b.lanewise(VectorOperators.MUL, tap1)).lanewise(VectorOperators.ADD, round)
                        .lanewise(VectorOperators.ASHR, 7);
                var s = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                sumsP = p.lanewise(VectorOperators.ADD, sumsP);
                sumsS = s.lanewise(VectorOperators.ADD, sumsS);
                var d = p.lanewise(VectorOperators.SUB, s);
                var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                        pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
            }
            var pairsP = INTS_8.cast(SHORTS_16.cast(sumsP).reinterpretShape(I256, 0));
            var pairsS = INTS_8.cast(SHORTS_16.cast(sumsS).reinterpretShape(I256, 0));
            var sum = pairsP.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    pairsP.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.AND, low)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.LSHR, 16));
            return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD),
                    INTS_8.cast(sum).reduceLanes(VectorOperators.ADD));
        }
        if (w == 32) {
            var tap0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t0));
            var tap1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t1));
            var round = SHORTS_16.cast(ROUND_16);
            var low = INTS_8.cast(LOW_HALVES_8);
            var squares = INTS_8.cast(IntVector.zero(I256));
            var sumsP = SHORTS_16.cast(ShortVector.zero(S256));
            var sumsS = SHORTS_16.cast(ShortVector.zero(S256));
            for (var r = 0; r < h; r++, refOff += refStride, srcOff += srcStride) {
                {
                    var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var p = a.lanewise(VectorOperators.MUL, tap0).lanewise(VectorOperators.ADD,
                            b.lanewise(VectorOperators.MUL, tap1)).lanewise(VectorOperators.ADD, round)
                            .lanewise(VectorOperators.ASHR, 7);
                    var s = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    sumsP = p.lanewise(VectorOperators.ADD, sumsP);
                    sumsS = s.lanewise(VectorOperators.ADD, sumsS);
                    var d = p.lanewise(VectorOperators.SUB, s);
                    var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                    squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                            pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
                }
                {
                    var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var p = a.lanewise(VectorOperators.MUL, tap0).lanewise(VectorOperators.ADD,
                            b.lanewise(VectorOperators.MUL, tap1)).lanewise(VectorOperators.ADD, round)
                            .lanewise(VectorOperators.ASHR, 7);
                    var s = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    sumsP = p.lanewise(VectorOperators.ADD, sumsP);
                    sumsS = s.lanewise(VectorOperators.ADD, sumsS);
                    var d = p.lanewise(VectorOperators.SUB, s);
                    var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                    squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                            pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
                }
            }
            var pairsP = INTS_8.cast(SHORTS_16.cast(sumsP).reinterpretShape(I256, 0));
            var pairsS = INTS_8.cast(SHORTS_16.cast(sumsS).reinterpretShape(I256, 0));
            var sum = pairsP.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    pairsP.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.AND, low)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.LSHR, 16));
            return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD),
                    INTS_8.cast(sum).reduceLanes(VectorOperators.ADD));
        }
        return addPacked(verticalOnly16(ref, refOff, refStride, t0, t1, src, srcOff, srcStride, 32, h),
                verticalOnly16(ref, refOff + 32, refStride, t0, t1, src, srcOff + 32, srcStride, 32, h));
    }

    /**
     * Returns the packed variance sums of the half-sample vertical bilinear interpolation of a block, whose
     * width is a multiple of 16, against a second block.
     *
     * @param ref the reference plane
     * @param refOff the index of the first sample
     * @param refStride the reference row stride
     * @param src the second block's plane
     * @param srcOff the index of its top-left sample
     * @param srcStride its row stride
     * @param w the width, a multiple of 16
     * @param h the height
     * @return the packed sums
     */
    private static long verticalHalf16(byte[] ref, int refOff, int refStride, byte[] src, int srcOff, int srcStride,
            int w, int h) {
        if (w == 16) {
            var one = SHORTS_16.cast(ShortVector.broadcast(S256, (short) 1));
            var low = INTS_8.cast(LOW_HALVES_8);
            var squares = INTS_8.cast(IntVector.zero(I256));
            var sumsP = SHORTS_16.cast(ShortVector.zero(S256));
            var sumsS = SHORTS_16.cast(ShortVector.zero(S256));
            for (var r = 0; r < h; r++, refOff += refStride, srcOff += srcStride) {
                var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var p = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ADD, one)
                        .lanewise(VectorOperators.ASHR, 1);
                var s = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                sumsP = p.lanewise(VectorOperators.ADD, sumsP);
                sumsS = s.lanewise(VectorOperators.ADD, sumsS);
                var d = p.lanewise(VectorOperators.SUB, s);
                var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                        pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
            }
            var pairsP = INTS_8.cast(SHORTS_16.cast(sumsP).reinterpretShape(I256, 0));
            var pairsS = INTS_8.cast(SHORTS_16.cast(sumsS).reinterpretShape(I256, 0));
            var sum = pairsP.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    pairsP.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.AND, low)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.LSHR, 16));
            return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD),
                    INTS_8.cast(sum).reduceLanes(VectorOperators.ADD));
        }
        if (w == 32) {
            var one = SHORTS_16.cast(ShortVector.broadcast(S256, (short) 1));
            var low = INTS_8.cast(LOW_HALVES_8);
            var squares = INTS_8.cast(IntVector.zero(I256));
            var sumsP = SHORTS_16.cast(ShortVector.zero(S256));
            var sumsS = SHORTS_16.cast(ShortVector.zero(S256));
            for (var r = 0; r < h; r++, refOff += refStride, srcOff += srcStride) {
                {
                    var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var p = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ADD, one)
                            .lanewise(VectorOperators.ASHR, 1);
                    var s = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    sumsP = p.lanewise(VectorOperators.ADD, sumsP);
                    sumsS = s.lanewise(VectorOperators.ADD, sumsS);
                    var d = p.lanewise(VectorOperators.SUB, s);
                    var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                    squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                            pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
                }
                {
                    var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var p = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ADD, one)
                            .lanewise(VectorOperators.ASHR, 1);
                    var s = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    sumsP = p.lanewise(VectorOperators.ADD, sumsP);
                    sumsS = s.lanewise(VectorOperators.ADD, sumsS);
                    var d = p.lanewise(VectorOperators.SUB, s);
                    var pairs = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                    squares = pairs.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                            pairs.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, squares);
                }
            }
            var pairsP = INTS_8.cast(SHORTS_16.cast(sumsP).reinterpretShape(I256, 0));
            var pairsS = INTS_8.cast(SHORTS_16.cast(sumsS).reinterpretShape(I256, 0));
            var sum = pairsP.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                    pairsP.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.AND, low)).lanewise(VectorOperators.SUB,
                    pairsS.lanewise(VectorOperators.LSHR, 16));
            return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD),
                    INTS_8.cast(sum).reduceLanes(VectorOperators.ADD));
        }
        return addPacked(verticalHalf16(ref, refOff, refStride, src, srcOff, srcStride, 32, h),
                verticalHalf16(ref, refOff + 32, refStride, src, srcOff + 32, srcStride, 32, h));
    }

    @Override
    int quantizeFp(int[] coeff, int off, int n, int[] qcoeff, int[] dqcoeff, short[] scan, int[] iscan, int[] round,
                   int[] quant, int[] dequant, int pidx, boolean is32x32) {
        if ((n & 7) != 0) {
            return Vp9Quantizer.quantizeFp(coeff, off, n, qcoeff, dqcoeff, scan, round, quant, dequant, pidx, is32x32);
        }
        if (is32x32) {
            return quantizeFp32(coeff, off, n, qcoeff, dqcoeff, iscan, round[pidx], round[pidx + 1], quant[pidx],
                    quant[pidx + 1], dequant[pidx], dequant[pidx + 1]);
        }
        return quantizeFp8(coeff, off, n, qcoeff, dqcoeff, iscan, round[pidx], round[pidx + 1], quant[pidx],
                quant[pidx + 1], dequant[pidx], dequant[pidx + 1]);
    }

    /**
     * Quantizes a 32x32 block with the fast quantizer, 8 coefficients at a time in raster order:
     * coefficients below a quarter of the step are zeroed, the others are rounded by half the
     * rounding offset and scaled with one more bit of precision, and the dequantized values are
     * halved, as {@code vp9_quantize_fp_32x32_c} does.
     *
     * @param coeff the coefficients in raster order
     * @param off the index of the block's first coefficient in all three arrays
     * @param n the number of coefficients, a multiple of 8
     * @param qcoeff receives the quantized coefficients
     * @param dqcoeff receives the dequantized coefficients
     * @param iscan the position plus one of each raster coefficient in the scan
     * @param dcRound the DC rounding offset
     * @param acRound the AC rounding offset
     * @param dcQuant the DC multiplier
     * @param acQuant the AC multiplier
     * @param dcDequant the DC step
     * @param acDequant the AC step
     * @return the end of block
     */
    private static int quantizeFp32(int[] coeff, int off, int n, int[] qcoeff, int[] dqcoeff, int[] iscan,
                                    int dcRound, int acRound, int dcQuant, int acQuant, int dcDequant,
                                    int acDequant) {
        var limit = INTS_8.cast(INT16_MAX_8);
        var acRounds = INTS_8.cast(IntVector.broadcast(I256, (acRound + 1) >> 1));
        var acQuants = INTS_8.cast(IntVector.broadcast(I256, acQuant));
        var acDequants = INTS_8.cast(IntVector.broadcast(I256, acDequant));
        var acThresholds = INTS_8.cast(IntVector.broadcast(I256, acDequant >> 2));
        var roundDeltas = INTS_8.cast(IntVector.broadcast(I256, ((dcRound + 1) >> 1) - ((acRound + 1) >> 1)));
        var quantDeltas = INTS_8.cast(IntVector.broadcast(I256, dcQuant - acQuant));
        var dequantDeltas = INTS_8.cast(IntVector.broadcast(I256, dcDequant - acDequant));
        var thresholdDeltas = INTS_8.cast(IntVector.broadcast(I256, (dcDequant >> 2) - (acDequant >> 2)));
        var eobs = INTS_8.cast(IntVector.zero(I256));
        for (var i = 0; i < n; i += 8) {
            var dcLane = INTS_8.cast(IntVector.fromArray(I256, DC_SELECT, Math.min(i, 8)));
            var rounds = INTS_8.cast(acRounds).lanewise(VectorOperators.ADD, dcLane.lanewise(VectorOperators.AND, roundDeltas));
            var quants = INTS_8.cast(acQuants).lanewise(VectorOperators.ADD, dcLane.lanewise(VectorOperators.AND, quantDeltas));
            var dequants = INTS_8.cast(acDequants).lanewise(VectorOperators.ADD,
                    dcLane.lanewise(VectorOperators.AND, dequantDeltas));
            var thresholds = INTS_8.cast(acThresholds).lanewise(VectorOperators.ADD,
                    dcLane.lanewise(VectorOperators.AND, thresholdDeltas));
            var c = INTS_8.cast(IntVector.fromArray(I256, coeff, off + i));
            var sign = c.lanewise(VectorOperators.ASHR, 31);
            var abs = c.lanewise(VectorOperators.ABS);
            var kept = abs.lanewise(VectorOperators.SUB, thresholds).lanewise(VectorOperators.ASHR, 31)
                    .lanewise(VectorOperators.NOT);
            var tmp = abs.lanewise(VectorOperators.ADD, rounds).lanewise(VectorOperators.MIN, limit)
                    .lanewise(VectorOperators.MUL, quants).lanewise(VectorOperators.ASHR, 15)
                    .lanewise(VectorOperators.AND, kept);
            tmp.lanewise(VectorOperators.XOR, sign).lanewise(VectorOperators.SUB, sign).intoArray(qcoeff, off + i);
            tmp.lanewise(VectorOperators.MUL, dequants).lanewise(VectorOperators.ASHR, 1)
                    .lanewise(VectorOperators.XOR, sign).lanewise(VectorOperators.SUB, sign).intoArray(dqcoeff, off + i);
            var nonzero = tmp.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ASHR, 31);
            var positions = INTS_8.cast(IntVector.fromArray(I256, iscan, i)).lanewise(VectorOperators.AND, nonzero);
            eobs = INTS_8.cast(eobs).lanewise(VectorOperators.MAX, positions);
        }
        return INTS_8.cast(eobs).reduceLanes(VectorOperators.MAX);
    }

    /**
     * Quantizes a block of at most 16x16 coefficients with the fast quantizer, 8 coefficients
     * at a time in raster order.
     *
     * <p>The end of block is the largest scan position plus one among the nonzero quantized
     * coefficients, which is what the scalar kernel's walk in scan order finds.
     *
     * @param coeff the coefficients in raster order
     * @param off the index of the block's first coefficient in all three arrays
     * @param n the number of coefficients, a multiple of 8
     * @param qcoeff receives the quantized coefficients
     * @param dqcoeff receives the dequantized coefficients
     * @param iscan the position plus one of each raster coefficient in the scan
     * @param dcRound the DC rounding offset
     * @param acRound the AC rounding offset
     * @param dcQuant the DC multiplier
     * @param acQuant the AC multiplier
     * @param dcDequant the DC step
     * @param acDequant the AC step
     * @return the end of block
     */
    private static int quantizeFp8(int[] coeff, int off, int n, int[] qcoeff, int[] dqcoeff, int[] iscan,
                                   int dcRound, int acRound, int dcQuant, int acQuant, int dcDequant,
                                   int acDequant) {
        var limit = INTS_8.cast(INT16_MAX_8);
        var acRounds = INTS_8.cast(IntVector.broadcast(I256, acRound));
        var acQuants = INTS_8.cast(IntVector.broadcast(I256, acQuant));
        var acDequants = INTS_8.cast(IntVector.broadcast(I256, acDequant));
        var roundDeltas = INTS_8.cast(IntVector.broadcast(I256, dcRound - acRound));
        var quantDeltas = INTS_8.cast(IntVector.broadcast(I256, dcQuant - acQuant));
        var dequantDeltas = INTS_8.cast(IntVector.broadcast(I256, dcDequant - acDequant));
        var eobs = INTS_8.cast(IntVector.zero(I256));
        for (var i = 0; i < n; i += 8) {
            var dcLane = INTS_8.cast(IntVector.fromArray(I256, DC_SELECT, Math.min(i, 8)));
            var rounds = INTS_8.cast(acRounds).lanewise(VectorOperators.ADD, dcLane.lanewise(VectorOperators.AND, roundDeltas));
            var quants = INTS_8.cast(acQuants).lanewise(VectorOperators.ADD, dcLane.lanewise(VectorOperators.AND, quantDeltas));
            var dequants = INTS_8.cast(acDequants).lanewise(VectorOperators.ADD,
                    dcLane.lanewise(VectorOperators.AND, dequantDeltas));
            var c = INTS_8.cast(IntVector.fromArray(I256, coeff, off + i));
            var sign = c.lanewise(VectorOperators.ASHR, 31);
            var tmp = c.lanewise(VectorOperators.ABS).lanewise(VectorOperators.ADD, rounds)
                    .lanewise(VectorOperators.MIN, limit).lanewise(VectorOperators.MUL, quants)
                    .lanewise(VectorOperators.ASHR, 16);
            tmp.lanewise(VectorOperators.XOR, sign).lanewise(VectorOperators.SUB, sign).intoArray(qcoeff, off + i);
            tmp.lanewise(VectorOperators.MUL, dequants).lanewise(VectorOperators.XOR, sign)
                    .lanewise(VectorOperators.SUB, sign).intoArray(dqcoeff, off + i);
            var nonzero = tmp.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ASHR, 31);
            var positions = INTS_8.cast(IntVector.fromArray(I256, iscan, i)).lanewise(VectorOperators.AND, nonzero);
            eobs = INTS_8.cast(eobs).lanewise(VectorOperators.MAX, positions);
        }
        return INTS_8.cast(eobs).reduceLanes(VectorOperators.MAX);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation quantizes the block's first 8 coefficients, which hold the DC
     * coefficient, apart from the others, so that the loop over the AC coefficients uses
     * constant parameters, and accumulates the squared error in 32-bit lanes, which
     * cannot overflow for coefficients in the 16-bit range the precondition guarantees.
     */
    @Override
    int quantizeFpRd(int[] coeff, int off, int n, int[] qcoeff, int[] dqcoeff, short[] scan, int[] iscan,
                     int[] round, int[] quant, int[] dequant, int pidx, long[] result) {
        if ((n & 7) != 0) {
            return super.quantizeFpRd(coeff, off, n, qcoeff, dqcoeff, scan, iscan, round, quant, dequant, pidx,
                    result);
        }
        var limit = INTS_8.cast(INT16_MAX_8);
        var dcLane = INTS_8.cast(IntVector.fromArray(I256, DC_SELECT, 0));
        var acRounds = INTS_8.cast(IntVector.broadcast(I256, round[pidx + 1]));
        var acQuants = INTS_8.cast(IntVector.broadcast(I256, quant[pidx + 1]));
        var acDequants = INTS_8.cast(IntVector.broadcast(I256, dequant[pidx + 1]));
        var firstRounds = acRounds.lanewise(VectorOperators.ADD, dcLane.lanewise(VectorOperators.AND,
                INTS_8.cast(IntVector.broadcast(I256, round[pidx] - round[pidx + 1]))));
        var firstQuants = acQuants.lanewise(VectorOperators.ADD, dcLane.lanewise(VectorOperators.AND,
                INTS_8.cast(IntVector.broadcast(I256, quant[pidx] - quant[pidx + 1]))));
        var firstDequants = acDequants.lanewise(VectorOperators.ADD, dcLane.lanewise(VectorOperators.AND,
                INTS_8.cast(IntVector.broadcast(I256, dequant[pidx] - dequant[pidx + 1]))));
        var c0 = INTS_8.cast(IntVector.fromArray(I256, coeff, off));
        var sign0 = c0.lanewise(VectorOperators.ASHR, 31);
        var firstSums = c0.lanewise(VectorOperators.ABS).lanewise(VectorOperators.ADD, firstRounds)
                .lanewise(VectorOperators.MIN, limit).lanewise(VectorOperators.MUL, firstQuants)
                .lanewise(VectorOperators.ASHR, 16);
        var diff0 = c0.lanewise(VectorOperators.SUB, firstSums.lanewise(VectorOperators.MUL, firstDequants)
                .lanewise(VectorOperators.XOR, sign0).lanewise(VectorOperators.SUB, sign0));
        var firstErrors = diff0.lanewise(VectorOperators.MUL, diff0);
        var firstEobs = INTS_8.cast(IntVector.fromArray(I256, iscan, 0)).lanewise(VectorOperators.AND,
                firstSums.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ASHR, 31));
        var eobs = INTS_8.cast(IntVector.zero(I256));
        var sums = INTS_8.cast(IntVector.zero(I256));
        var errors = INTS_8.cast(IntVector.zero(I256));
        for (var i = 8; i < n; i += 8) {
            var c = INTS_8.cast(IntVector.fromArray(I256, coeff, off + i));
            var sign = c.lanewise(VectorOperators.ASHR, 31);
            var tmp = c.lanewise(VectorOperators.ABS).lanewise(VectorOperators.ADD, acRounds)
                    .lanewise(VectorOperators.MIN, limit).lanewise(VectorOperators.MUL, acQuants)
                    .lanewise(VectorOperators.ASHR, 16);
            var diff = c.lanewise(VectorOperators.SUB, tmp.lanewise(VectorOperators.MUL, acDequants)
                    .lanewise(VectorOperators.XOR, sign).lanewise(VectorOperators.SUB, sign));
            errors = diff.lanewise(VectorOperators.MUL, diff).lanewise(VectorOperators.ADD, errors);
            sums = tmp.lanewise(VectorOperators.ADD, sums);
            var nonzero = tmp.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ASHR, 31);
            var positions = INTS_8.cast(IntVector.fromArray(I256, iscan, i)).lanewise(VectorOperators.AND, nonzero);
            eobs = INTS_8.cast(eobs).lanewise(VectorOperators.MAX, positions);
        }
        result[0] = INTS_8.cast(sums).lanewise(VectorOperators.ADD, firstSums).reduceLanes(VectorOperators.ADD);
        result[1] = INTS_8.cast(errors).lanewise(VectorOperators.ADD, firstErrors).reduceLanes(VectorOperators.ADD);
        return INTS_8.cast(eobs).lanewise(VectorOperators.MAX, firstEobs).reduceLanes(VectorOperators.MAX);
    }

    @Override
    int satd(int[] coeff, int off, int n) {
        if ((n & 7) != 0) {
            return scalar.satd(coeff, off, n);
        }
        var sums = INTS_8.cast(IntVector.zero(I256));
        for (var i = 0; i < n; i += 8) {
            var c = INTS_8.cast(IntVector.fromArray(I256, coeff, off + i));
            sums = c.lanewise(VectorOperators.ABS).lanewise(VectorOperators.ADD, sums);
        }
        return INTS_8.cast(sums).reduceLanes(VectorOperators.ADD);
    }

    @Override
    long blockErrorFp(int[] coeff, int[] dqcoeff, int off, int n) {
        if ((n & 7) != 0) {
            return scalar.blockErrorFp(coeff, dqcoeff, off, n);
        }
        var errors = LONGS_4.cast(LongVector.zero(L256));
        for (var i = 0; i < n; i += 8) {
            var c = INTS_8.cast(IntVector.fromArray(I256, coeff, off + i));
            var d = INTS_8.cast(IntVector.fromArray(I256, dqcoeff, off + i));
            var diff = c.lanewise(VectorOperators.SUB, d);
            var lo = LONGS_4.cast(diff.convertShape(VectorOperators.I2L, L256, 0));
            var hi = LONGS_4.cast(diff.convertShape(VectorOperators.I2L, L256, 1));
            errors = lo.lanewise(VectorOperators.MUL, lo).lanewise(VectorOperators.ADD,
                    hi.lanewise(VectorOperators.MUL, hi)).lanewise(VectorOperators.ADD, errors);
        }
        return LONGS_4.cast(errors).reduceLanes(VectorOperators.ADD);
    }

    @Override
    void intProRow(short[] hbuf, int hOff, byte[] ref, int refOff, int stride, int height) {
        var sums = SHORTS_16.cast(ShortVector.zero(S256));
        for (var i = 0; i < height; i++, refOff += stride) {
            var row = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            sums = row.lanewise(VectorOperators.ADD, sums);
        }
        var shift = Integer.numberOfTrailingZeros(height >> 1);
        SHORTS_16.cast(sums).lanewise(VectorOperators.ASHR, shift).intoArray(hbuf, hOff);
    }

    @Override
    int intProCol(byte[] ref, int off, int width) {
        var sums = SHORTS_16.cast(ShortVector.zero(S256));
        for (var i = 0; i < width; i += 16) {
            var row = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, off + i))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            sums = row.lanewise(VectorOperators.ADD, sums);
        }
        return SHORTS_16.cast(sums).reduceLanes(VectorOperators.ADD);
    }

    @Override
    int vectorVar(short[] ref, int refOff, short[] src, int srcOff, int bwl) {
        var width = 4 << bwl;
        var squares = INTS_8.cast(IntVector.zero(I256));
        var sums = INTS_8.cast(IntVector.zero(I256));
        for (var i = 0; i < width; i += 16) {
            var a = SHORTS_16.cast(ShortVector.fromArray(S256, ref, refOff + i));
            var b = SHORTS_16.cast(ShortVector.fromArray(S256, src, srcOff + i));
            var lo = INTS_8.cast(a.convertShape(VectorOperators.S2I, I256, 0))
                    .lanewise(VectorOperators.SUB, INTS_8.cast(b.convertShape(VectorOperators.S2I, I256, 0)));
            var hi = INTS_8.cast(a.convertShape(VectorOperators.S2I, I256, 1))
                    .lanewise(VectorOperators.SUB, INTS_8.cast(b.convertShape(VectorOperators.S2I, I256, 1)));
            sums = lo.lanewise(VectorOperators.ADD, hi).lanewise(VectorOperators.ADD, sums);
            squares = lo.lanewise(VectorOperators.MUL, lo).lanewise(VectorOperators.ADD,
                    hi.lanewise(VectorOperators.MUL, hi)).lanewise(VectorOperators.ADD, squares);
        }
        var sse = INTS_8.cast(squares).reduceLanes(VectorOperators.ADD);
        var mean = INTS_8.cast(sums).reduceLanes(VectorOperators.ADD);
        return sse - ((mean * mean) >> (bwl + 2));
    }

    @Override
    void subtract(short[] diff, int diffOff, int diffStride, byte[] src, int srcOff, int srcStride, byte[] pred,
                  int predOff, int predStride, int w, int h) {
        if (w == 8 && (h & 3) == 0) {
            subtract8(diff, diffOff, diffStride, src, srcOff, srcStride, pred, predOff, predStride, h);
            return;
        }
        if ((w & 15) == 0 && (h & 3) == 0) {
            subtract16(diff, diffOff, diffStride, src, srcOff, srcStride, pred, predOff, predStride, w, h);
            return;
        }
        scalar.subtract(diff, diffOff, diffStride, src, srcOff, srcStride, pred, predOff, predStride, w, h);
    }

    /**
     * Computes the residual of a block 8 samples wide, four rows at a time.
     *
     * @param diff receives the residuals
     * @param diffOff the index of the first residual
     * @param diffStride the residual row stride
     * @param src the source plane
     * @param srcOff the index of the source block's top-left sample
     * @param srcStride the source row stride
     * @param pred the prediction plane
     * @param predOff the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param h the height, a multiple of 4
     */
    private static void subtract8(short[] diff, int diffOff, int diffStride, byte[] src, int srcOff, int srcStride,
                                  byte[] pred, int predOff, int predStride, int h) {
        for (var r = 0; r < h; r += 4, diffOff += 4 * diffStride, srcOff += 4 * srcStride,
                predOff += 4 * predStride) {
            var a0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, srcOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, pred, predOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            SHORTS_8.cast(a0.lanewise(VectorOperators.SUB, b0)).intoArray(diff, diffOff);
            var a1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, srcOff + srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, pred, predOff + predStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            SHORTS_8.cast(a1.lanewise(VectorOperators.SUB, b1)).intoArray(diff, diffOff + diffStride);
            var a2 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, srcOff + 2 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b2 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, pred, predOff + 2 * predStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            SHORTS_8.cast(a2.lanewise(VectorOperators.SUB, b2)).intoArray(diff, diffOff + 2 * diffStride);
            var a3 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, srcOff + 3 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b3 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, pred, predOff + 3 * predStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            SHORTS_8.cast(a3.lanewise(VectorOperators.SUB, b3)).intoArray(diff, diffOff + 3 * diffStride);
        }
    }

    /**
     * Computes the residual of a block whose width is a multiple of 16, four rows at a time.
     *
     * @param diff receives the residuals
     * @param diffOff the index of the first residual
     * @param diffStride the residual row stride
     * @param src the source plane
     * @param srcOff the index of the source block's top-left sample
     * @param srcStride the source row stride
     * @param pred the prediction plane
     * @param predOff the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param w the width
     * @param h the height, a multiple of 4
     */
    private static void subtract16(short[] diff, int diffOff, int diffStride, byte[] src, int srcOff, int srcStride,
                                   byte[] pred, int predOff, int predStride, int w, int h) {
        if (w == 16) {
            for (var r = 0; r < h; r += 4, diffOff += 4 * diffStride, srcOff += 4 * srcStride,
                    predOff += 4 * predStride) {
                var a0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, predOff))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                SHORTS_16.cast(a0.lanewise(VectorOperators.SUB, b0)).intoArray(diff, diffOff);
                var a1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, predOff + predStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                SHORTS_16.cast(a1.lanewise(VectorOperators.SUB, b1)).intoArray(diff, diffOff + diffStride);
                var a2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + 2 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, predOff + 2 * predStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                SHORTS_16.cast(a2.lanewise(VectorOperators.SUB, b2)).intoArray(diff, diffOff + 2 * diffStride);
                var a3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + 3 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, predOff + 3 * predStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                SHORTS_16.cast(a3.lanewise(VectorOperators.SUB, b3)).intoArray(diff, diffOff + 3 * diffStride);
            }
            return;
        }
        if (w == 32) {
            for (var r = 0; r < h; r += 4, diffOff += 4 * diffStride, srcOff += 4 * srcStride,
                    predOff += 4 * predStride) {
                {
                    var a0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, predOff))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    SHORTS_16.cast(a0.lanewise(VectorOperators.SUB, b0)).intoArray(diff, diffOff);
                    var a1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + srcStride))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, predOff + predStride))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    SHORTS_16.cast(a1.lanewise(VectorOperators.SUB, b1)).intoArray(diff, diffOff + diffStride);
                    var a2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + 2 * srcStride))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, predOff + 2 * predStride))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    SHORTS_16.cast(a2.lanewise(VectorOperators.SUB, b2)).intoArray(diff, diffOff + 2 * diffStride);
                    var a3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + 3 * srcStride))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, predOff + 3 * predStride))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    SHORTS_16.cast(a3.lanewise(VectorOperators.SUB, b3)).intoArray(diff, diffOff + 3 * diffStride);
                }
                {
                    var a0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, predOff + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    SHORTS_16.cast(a0.lanewise(VectorOperators.SUB, b0)).intoArray(diff, diffOff + 16);
                    var a1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + srcStride + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, predOff + predStride + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    SHORTS_16.cast(a1.lanewise(VectorOperators.SUB, b1)).intoArray(diff, diffOff + diffStride + 16);
                    var a2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + 2 * srcStride + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, predOff + 2 * predStride + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    SHORTS_16.cast(a2.lanewise(VectorOperators.SUB, b2)).intoArray(diff, diffOff + 2 * diffStride + 16);
                    var a3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + 3 * srcStride + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    var b3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pred, predOff + 3 * predStride + 16))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                    SHORTS_16.cast(a3.lanewise(VectorOperators.SUB, b3)).intoArray(diff, diffOff + 3 * diffStride + 16);
                }
            }
            return;
        }
        subtract16(diff, diffOff, diffStride, src, srcOff, srcStride, pred, predOff, predStride, 32, h);
        subtract16(diff, diffOff + 32, diffStride, src, srcOff + 32, srcStride, pred, predOff + 32, predStride, 32,
                h);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation computes the column transforms of the eight rows in 32-bit
     * lanes, writes them as the rows of the output, and then transforms each output row
     * in place with three butterfly stages of lane swaps and a final lane permutation.
     * Every step adds or subtracts, so wrapping the results to 16 bits once at the end
     * gives the values of libvpx's 16-bit wrap-around after each step.
     */
    @Override
    void hadamard8x8(short[] diff, int diffOff, int stride, int[] coeff, int outOff, short[] buffer) {
        hadamard8(diff, diffOff, stride, coeff, outOff);
    }

    /**
     * Computes the 8x8 Hadamard transform: the column transforms of the eight rows at once, stored
     * as the output rows in the output order of {@code vpx_hadamard_8x8_c}, then the transform of
     * each output row across its lanes, wrapped to 16 bits.
     *
     * @param diff the residuals
     * @param diffOff the index of the first residual
     * @param stride the residual row stride
     * @param coeff receives the 64 coefficients
     * @param outOff the index of the first coefficient
     */
    private static void hadamard8(short[] diff, int diffOff, int stride, int[] coeff, int outOff) {
        var r0 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, diff, diffOff))
                .convertShape(VectorOperators.S2I, I256, 0));
        var r1 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, diff, diffOff + stride))
                .convertShape(VectorOperators.S2I, I256, 0));
        var r2 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, diff, diffOff + 2 * stride))
                .convertShape(VectorOperators.S2I, I256, 0));
        var r3 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, diff, diffOff + 3 * stride))
                .convertShape(VectorOperators.S2I, I256, 0));
        var r4 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, diff, diffOff + 4 * stride))
                .convertShape(VectorOperators.S2I, I256, 0));
        var r5 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, diff, diffOff + 5 * stride))
                .convertShape(VectorOperators.S2I, I256, 0));
        var r6 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, diff, diffOff + 6 * stride))
                .convertShape(VectorOperators.S2I, I256, 0));
        var r7 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, diff, diffOff + 7 * stride))
                .convertShape(VectorOperators.S2I, I256, 0));
        var b0 = r0.lanewise(VectorOperators.ADD, r1);
        var b1 = r0.lanewise(VectorOperators.SUB, r1);
        var b2 = r2.lanewise(VectorOperators.ADD, r3);
        var b3 = r2.lanewise(VectorOperators.SUB, r3);
        var b4 = r4.lanewise(VectorOperators.ADD, r5);
        var b5 = r4.lanewise(VectorOperators.SUB, r5);
        var b6 = r6.lanewise(VectorOperators.ADD, r7);
        var b7 = r6.lanewise(VectorOperators.SUB, r7);
        var c0 = b0.lanewise(VectorOperators.ADD, b2);
        var c1 = b1.lanewise(VectorOperators.ADD, b3);
        var c2 = b0.lanewise(VectorOperators.SUB, b2);
        var c3 = b1.lanewise(VectorOperators.SUB, b3);
        var c4 = b4.lanewise(VectorOperators.ADD, b6);
        var c5 = b5.lanewise(VectorOperators.ADD, b7);
        var c6 = b4.lanewise(VectorOperators.SUB, b6);
        var c7 = b5.lanewise(VectorOperators.SUB, b7);
        INTS_8.cast(c0.lanewise(VectorOperators.ADD, c4)).intoArray(coeff, outOff);
        INTS_8.cast(c2.lanewise(VectorOperators.SUB, c6)).intoArray(coeff, outOff + 8);
        INTS_8.cast(c0.lanewise(VectorOperators.SUB, c4)).intoArray(coeff, outOff + 16);
        INTS_8.cast(c2.lanewise(VectorOperators.ADD, c6)).intoArray(coeff, outOff + 24);
        INTS_8.cast(c3.lanewise(VectorOperators.ADD, c7)).intoArray(coeff, outOff + 32);
        INTS_8.cast(c3.lanewise(VectorOperators.SUB, c7)).intoArray(coeff, outOff + 40);
        INTS_8.cast(c1.lanewise(VectorOperators.SUB, c5)).intoArray(coeff, outOff + 48);
        INTS_8.cast(c1.lanewise(VectorOperators.ADD, c5)).intoArray(coeff, outOff + 56);
        for (var row = 0; row < 64; row += 8) {
            var v = INTS_8.cast(IntVector.fromArray(I256, coeff, outOff + row));
            var u = INTS_8.cast(v.rearrange(SWAP_1));
            var s = INTS_8.cast(u.lanewise(VectorOperators.ADD, v).blend(u.lanewise(VectorOperators.SUB, v), BIT_1));
            u = INTS_8.cast(s.rearrange(SWAP_2));
            s = INTS_8.cast(u.lanewise(VectorOperators.ADD, s).blend(u.lanewise(VectorOperators.SUB, s), BIT_2));
            u = INTS_8.cast(s.rearrange(SWAP_4));
            s = INTS_8.cast(u.lanewise(VectorOperators.ADD, s).blend(u.lanewise(VectorOperators.SUB, s), BIT_4));
            var wide = INTS_8.cast(INTS_8.cast(s.rearrange(HADAMARD_ORDER)).lanewise(VectorOperators.LSHL, 16));
            wide.lanewise(VectorOperators.ASHR, 16).intoArray(coeff, outOff + row);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation combines the four 8x8 transforms 16 coefficients at a time.
     */
    @Override
    void hadamard16x16(short[] diff, int diffOff, int stride, int[] coeff, int outOff, short[] buffer) {
        for (var idx = 0; idx < 4; idx++) {
            var off = diffOff + (idx >> 1) * 8 * stride + (idx & 1) * 8;
            hadamard8(diff, off, stride, coeff, outOff + idx * 64);
        }
        hadamardCombine(coeff, outOff);
    }

    /**
     * Combines the four 8x8 Hadamard transforms of a 16x16 block into its 16x16 transform, two
     * groups of eight coefficients at a time.
     *
     * @param coeff the four 8x8 transforms, replaced by the 16x16 transform
     * @param outOff the index of the first coefficient
     */
    private static void hadamardCombine(int[] coeff, int outOff) {
        for (var idx = 0; idx < 64; idx += 16) {
            var p = outOff + idx;
            var a0 = INTS_8.cast(IntVector.fromArray(I256, coeff, p));
            var a1 = INTS_8.cast(IntVector.fromArray(I256, coeff, p + 64));
            var a2 = INTS_8.cast(IntVector.fromArray(I256, coeff, p + 128));
            var a3 = INTS_8.cast(IntVector.fromArray(I256, coeff, p + 192));
            var b0 = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ASHR, 1);
            var b1 = a0.lanewise(VectorOperators.SUB, a1).lanewise(VectorOperators.ASHR, 1);
            var b2 = a2.lanewise(VectorOperators.ADD, a3).lanewise(VectorOperators.ASHR, 1);
            var b3 = a2.lanewise(VectorOperators.SUB, a3).lanewise(VectorOperators.ASHR, 1);
            INTS_8.cast(b0.lanewise(VectorOperators.ADD, b2)).intoArray(coeff, p);
            INTS_8.cast(b1.lanewise(VectorOperators.ADD, b3)).intoArray(coeff, p + 64);
            INTS_8.cast(b0.lanewise(VectorOperators.SUB, b2)).intoArray(coeff, p + 128);
            INTS_8.cast(b1.lanewise(VectorOperators.SUB, b3)).intoArray(coeff, p + 192);
            var q = outOff + idx + 8;
            var e0 = INTS_8.cast(IntVector.fromArray(I256, coeff, q));
            var e1 = INTS_8.cast(IntVector.fromArray(I256, coeff, q + 64));
            var e2 = INTS_8.cast(IntVector.fromArray(I256, coeff, q + 128));
            var e3 = INTS_8.cast(IntVector.fromArray(I256, coeff, q + 192));
            var f0 = e0.lanewise(VectorOperators.ADD, e1).lanewise(VectorOperators.ASHR, 1);
            var f1 = e0.lanewise(VectorOperators.SUB, e1).lanewise(VectorOperators.ASHR, 1);
            var f2 = e2.lanewise(VectorOperators.ADD, e3).lanewise(VectorOperators.ASHR, 1);
            var f3 = e2.lanewise(VectorOperators.SUB, e3).lanewise(VectorOperators.ASHR, 1);
            INTS_8.cast(f0.lanewise(VectorOperators.ADD, f2)).intoArray(coeff, q);
            INTS_8.cast(f1.lanewise(VectorOperators.ADD, f3)).intoArray(coeff, q + 64);
            INTS_8.cast(f0.lanewise(VectorOperators.SUB, f2)).intoArray(coeff, q + 128);
            INTS_8.cast(f1.lanewise(VectorOperators.SUB, f3)).intoArray(coeff, q + 192);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation computes both passes eight lanes at a time in 32-bit lanes,
     * transposing the 8x8 block in registers after each pass with three stages of lane
     * swaps and blends. The products of residuals of 8-bit samples stay below 2^30, so
     * the 32-bit arithmetic equals libvpx's wider intermediates.
     */
    @Override
    void fdct8x8(short[] in, int inOff, int stride, int[] out, int outOff, long[] tmp) {
        fdct8Columns(in, inOff, stride, out, outOff);
        fdct8Rows(out, outOff);
    }

    /**
     * Computes the first pass of the 8x8 cosine transform on the eight columns at once and stores
     * the result transposed: row {@code j} of the output holds column {@code j}'s eight outputs.
     *
     * @param in the residuals
     * @param inOff the index of the first residual
     * @param stride the residual row stride
     * @param out receives the 64 intermediate values
     * @param outOff the index of the first value
     */
    private static void fdct8Columns(short[] in, int inOff, int stride, int[] out, int outOff) {
        var i0 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, inOff))
                .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.LSHL, 2);
        var i1 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, inOff + stride))
                .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.LSHL, 2);
        var i2 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, inOff + 2 * stride))
                .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.LSHL, 2);
        var i3 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, inOff + 3 * stride))
                .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.LSHL, 2);
        var i4 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, inOff + 4 * stride))
                .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.LSHL, 2);
        var i5 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, inOff + 5 * stride))
                .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.LSHL, 2);
        var i6 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, inOff + 6 * stride))
                .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.LSHL, 2);
        var i7 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, inOff + 7 * stride))
                .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.LSHL, 2);
        var round = INTS_8.cast(DCT_ROUND_8);
        var c4 = INTS_8.cast(COS_4);
        var c8 = INTS_8.cast(COS_8);
        var c12 = INTS_8.cast(COS_12);
        var c16 = INTS_8.cast(COS_16);
        var c20 = INTS_8.cast(COS_20);
        var c24 = INTS_8.cast(COS_24);
        var c28 = INTS_8.cast(COS_28);
        var s0 = i0.lanewise(VectorOperators.ADD, i7);
        var s1 = i1.lanewise(VectorOperators.ADD, i6);
        var s2 = i2.lanewise(VectorOperators.ADD, i5);
        var s3 = i3.lanewise(VectorOperators.ADD, i4);
        var s4 = i3.lanewise(VectorOperators.SUB, i4);
        var s5 = i2.lanewise(VectorOperators.SUB, i5);
        var s6 = i1.lanewise(VectorOperators.SUB, i6);
        var s7 = i0.lanewise(VectorOperators.SUB, i7);
        var x0 = s0.lanewise(VectorOperators.ADD, s3);
        var x1 = s1.lanewise(VectorOperators.ADD, s2);
        var x2 = s1.lanewise(VectorOperators.SUB, s2);
        var x3 = s0.lanewise(VectorOperators.SUB, s3);
        var o0 = x0.lanewise(VectorOperators.ADD, x1).lanewise(VectorOperators.MUL, c16)
                .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var o4 = x0.lanewise(VectorOperators.SUB, x1).lanewise(VectorOperators.MUL, c16)
                .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var o2 = x2.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.ADD,
                x3.lanewise(VectorOperators.MUL, c8)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var o6 = x3.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.SUB,
                x2.lanewise(VectorOperators.MUL, c8)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var t2 = s6.lanewise(VectorOperators.SUB, s5).lanewise(VectorOperators.MUL, c16)
                .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var t3 = s6.lanewise(VectorOperators.ADD, s5).lanewise(VectorOperators.MUL, c16)
                .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var y0 = s4.lanewise(VectorOperators.ADD, t2);
        var y1 = s4.lanewise(VectorOperators.SUB, t2);
        var y2 = s7.lanewise(VectorOperators.SUB, t3);
        var y3 = s7.lanewise(VectorOperators.ADD, t3);
        var o1 = y0.lanewise(VectorOperators.MUL, c28).lanewise(VectorOperators.ADD,
                y3.lanewise(VectorOperators.MUL, c4)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var o3 = y2.lanewise(VectorOperators.MUL, c12).lanewise(VectorOperators.SUB,
                y1.lanewise(VectorOperators.MUL, c20)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var o5 = y1.lanewise(VectorOperators.MUL, c12).lanewise(VectorOperators.ADD,
                y2.lanewise(VectorOperators.MUL, c20)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var o7 = y3.lanewise(VectorOperators.MUL, c28).lanewise(VectorOperators.SUB,
                y0.lanewise(VectorOperators.MUL, c4)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var a0 = INTS_8.cast(o0.blend(o1.rearrange(SWAP_1), BIT_1));
        var a1 = INTS_8.cast(o0.rearrange(SWAP_1).blend(o1, BIT_1));
        var a2 = INTS_8.cast(o2.blend(o3.rearrange(SWAP_1), BIT_1));
        var a3 = INTS_8.cast(o2.rearrange(SWAP_1).blend(o3, BIT_1));
        var a4 = INTS_8.cast(o4.blend(o5.rearrange(SWAP_1), BIT_1));
        var a5 = INTS_8.cast(o4.rearrange(SWAP_1).blend(o5, BIT_1));
        var a6 = INTS_8.cast(o6.blend(o7.rearrange(SWAP_1), BIT_1));
        var a7 = INTS_8.cast(o6.rearrange(SWAP_1).blend(o7, BIT_1));
        var b0 = INTS_8.cast(a0.blend(a2.rearrange(SWAP_2), BIT_2));
        var b2 = INTS_8.cast(a0.rearrange(SWAP_2).blend(a2, BIT_2));
        var b1 = INTS_8.cast(a1.blend(a3.rearrange(SWAP_2), BIT_2));
        var b3 = INTS_8.cast(a1.rearrange(SWAP_2).blend(a3, BIT_2));
        var b4 = INTS_8.cast(a4.blend(a6.rearrange(SWAP_2), BIT_2));
        var b6 = INTS_8.cast(a4.rearrange(SWAP_2).blend(a6, BIT_2));
        var b5 = INTS_8.cast(a5.blend(a7.rearrange(SWAP_2), BIT_2));
        var b7 = INTS_8.cast(a5.rearrange(SWAP_2).blend(a7, BIT_2));
        INTS_8.cast(b0.blend(b4.rearrange(SWAP_4), BIT_4)).intoArray(out, outOff);
        INTS_8.cast(b1.blend(b5.rearrange(SWAP_4), BIT_4)).intoArray(out, outOff + 8);
        INTS_8.cast(b2.blend(b6.rearrange(SWAP_4), BIT_4)).intoArray(out, outOff + 16);
        INTS_8.cast(b3.blend(b7.rearrange(SWAP_4), BIT_4)).intoArray(out, outOff + 24);
        INTS_8.cast(b0.rearrange(SWAP_4).blend(b4, BIT_4)).intoArray(out, outOff + 32);
        INTS_8.cast(b1.rearrange(SWAP_4).blend(b5, BIT_4)).intoArray(out, outOff + 40);
        INTS_8.cast(b2.rearrange(SWAP_4).blend(b6, BIT_4)).intoArray(out, outOff + 48);
        INTS_8.cast(b3.rearrange(SWAP_4).blend(b7, BIT_4)).intoArray(out, outOff + 56);
    }

    /**
     * Computes the second pass of the 8x8 cosine transform in place on the transposed first-pass
     * values, transposes the result back, and halves it rounding toward zero.
     *
     * @param out the 64 transposed first-pass values, replaced by the coefficients
     * @param outOff the index of the first value
     */
    private static void fdct8Rows(int[] out, int outOff) {
        var i0 = INTS_8.cast(IntVector.fromArray(I256, out, outOff));
        var i1 = INTS_8.cast(IntVector.fromArray(I256, out, outOff + 8));
        var i2 = INTS_8.cast(IntVector.fromArray(I256, out, outOff + 16));
        var i3 = INTS_8.cast(IntVector.fromArray(I256, out, outOff + 24));
        var i4 = INTS_8.cast(IntVector.fromArray(I256, out, outOff + 32));
        var i5 = INTS_8.cast(IntVector.fromArray(I256, out, outOff + 40));
        var i6 = INTS_8.cast(IntVector.fromArray(I256, out, outOff + 48));
        var i7 = INTS_8.cast(IntVector.fromArray(I256, out, outOff + 56));
        var round = INTS_8.cast(DCT_ROUND_8);
        var c4 = INTS_8.cast(COS_4);
        var c8 = INTS_8.cast(COS_8);
        var c12 = INTS_8.cast(COS_12);
        var c16 = INTS_8.cast(COS_16);
        var c20 = INTS_8.cast(COS_20);
        var c24 = INTS_8.cast(COS_24);
        var c28 = INTS_8.cast(COS_28);
        var s0 = i0.lanewise(VectorOperators.ADD, i7);
        var s1 = i1.lanewise(VectorOperators.ADD, i6);
        var s2 = i2.lanewise(VectorOperators.ADD, i5);
        var s3 = i3.lanewise(VectorOperators.ADD, i4);
        var s4 = i3.lanewise(VectorOperators.SUB, i4);
        var s5 = i2.lanewise(VectorOperators.SUB, i5);
        var s6 = i1.lanewise(VectorOperators.SUB, i6);
        var s7 = i0.lanewise(VectorOperators.SUB, i7);
        var x0 = s0.lanewise(VectorOperators.ADD, s3);
        var x1 = s1.lanewise(VectorOperators.ADD, s2);
        var x2 = s1.lanewise(VectorOperators.SUB, s2);
        var x3 = s0.lanewise(VectorOperators.SUB, s3);
        var o0 = x0.lanewise(VectorOperators.ADD, x1).lanewise(VectorOperators.MUL, c16)
                .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var o4 = x0.lanewise(VectorOperators.SUB, x1).lanewise(VectorOperators.MUL, c16)
                .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var o2 = x2.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.ADD,
                x3.lanewise(VectorOperators.MUL, c8)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var o6 = x3.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.SUB,
                x2.lanewise(VectorOperators.MUL, c8)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var t2 = s6.lanewise(VectorOperators.SUB, s5).lanewise(VectorOperators.MUL, c16)
                .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var t3 = s6.lanewise(VectorOperators.ADD, s5).lanewise(VectorOperators.MUL, c16)
                .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var y0 = s4.lanewise(VectorOperators.ADD, t2);
        var y1 = s4.lanewise(VectorOperators.SUB, t2);
        var y2 = s7.lanewise(VectorOperators.SUB, t3);
        var y3 = s7.lanewise(VectorOperators.ADD, t3);
        var o1 = y0.lanewise(VectorOperators.MUL, c28).lanewise(VectorOperators.ADD,
                y3.lanewise(VectorOperators.MUL, c4)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var o3 = y2.lanewise(VectorOperators.MUL, c12).lanewise(VectorOperators.SUB,
                y1.lanewise(VectorOperators.MUL, c20)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var o5 = y1.lanewise(VectorOperators.MUL, c12).lanewise(VectorOperators.ADD,
                y2.lanewise(VectorOperators.MUL, c20)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var o7 = y3.lanewise(VectorOperators.MUL, c28).lanewise(VectorOperators.SUB,
                y0.lanewise(VectorOperators.MUL, c4)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var a0 = INTS_8.cast(o0.blend(o1.rearrange(SWAP_1), BIT_1));
        var a1 = INTS_8.cast(o0.rearrange(SWAP_1).blend(o1, BIT_1));
        var a2 = INTS_8.cast(o2.blend(o3.rearrange(SWAP_1), BIT_1));
        var a3 = INTS_8.cast(o2.rearrange(SWAP_1).blend(o3, BIT_1));
        var a4 = INTS_8.cast(o4.blend(o5.rearrange(SWAP_1), BIT_1));
        var a5 = INTS_8.cast(o4.rearrange(SWAP_1).blend(o5, BIT_1));
        var a6 = INTS_8.cast(o6.blend(o7.rearrange(SWAP_1), BIT_1));
        var a7 = INTS_8.cast(o6.rearrange(SWAP_1).blend(o7, BIT_1));
        var b0 = INTS_8.cast(a0.blend(a2.rearrange(SWAP_2), BIT_2));
        var b2 = INTS_8.cast(a0.rearrange(SWAP_2).blend(a2, BIT_2));
        var b1 = INTS_8.cast(a1.blend(a3.rearrange(SWAP_2), BIT_2));
        var b3 = INTS_8.cast(a1.rearrange(SWAP_2).blend(a3, BIT_2));
        var b4 = INTS_8.cast(a4.blend(a6.rearrange(SWAP_2), BIT_2));
        var b6 = INTS_8.cast(a4.rearrange(SWAP_2).blend(a6, BIT_2));
        var b5 = INTS_8.cast(a5.blend(a7.rearrange(SWAP_2), BIT_2));
        var b7 = INTS_8.cast(a5.rearrange(SWAP_2).blend(a7, BIT_2));
        var r0 = INTS_8.cast(b0.blend(b4.rearrange(SWAP_4), BIT_4));
        var r1 = INTS_8.cast(b1.blend(b5.rearrange(SWAP_4), BIT_4));
        var r2 = INTS_8.cast(b2.blend(b6.rearrange(SWAP_4), BIT_4));
        var r3 = INTS_8.cast(b3.blend(b7.rearrange(SWAP_4), BIT_4));
        var r4 = INTS_8.cast(b0.rearrange(SWAP_4).blend(b4, BIT_4));
        var r5 = INTS_8.cast(b1.rearrange(SWAP_4).blend(b5, BIT_4));
        var r6 = INTS_8.cast(b2.rearrange(SWAP_4).blend(b6, BIT_4));
        var r7 = INTS_8.cast(b3.rearrange(SWAP_4).blend(b7, BIT_4));
        INTS_8.cast(r0.lanewise(VectorOperators.ADD, r0.lanewise(VectorOperators.LSHR, 31))
                .lanewise(VectorOperators.ASHR, 1)).intoArray(out, outOff);
        INTS_8.cast(r1.lanewise(VectorOperators.ADD, r1.lanewise(VectorOperators.LSHR, 31))
                .lanewise(VectorOperators.ASHR, 1)).intoArray(out, outOff + 8);
        INTS_8.cast(r2.lanewise(VectorOperators.ADD, r2.lanewise(VectorOperators.LSHR, 31))
                .lanewise(VectorOperators.ASHR, 1)).intoArray(out, outOff + 16);
        INTS_8.cast(r3.lanewise(VectorOperators.ADD, r3.lanewise(VectorOperators.LSHR, 31))
                .lanewise(VectorOperators.ASHR, 1)).intoArray(out, outOff + 24);
        INTS_8.cast(r4.lanewise(VectorOperators.ADD, r4.lanewise(VectorOperators.LSHR, 31))
                .lanewise(VectorOperators.ASHR, 1)).intoArray(out, outOff + 32);
        INTS_8.cast(r5.lanewise(VectorOperators.ADD, r5.lanewise(VectorOperators.LSHR, 31))
                .lanewise(VectorOperators.ASHR, 1)).intoArray(out, outOff + 40);
        INTS_8.cast(r6.lanewise(VectorOperators.ADD, r6.lanewise(VectorOperators.LSHR, 31))
                .lanewise(VectorOperators.ASHR, 1)).intoArray(out, outOff + 48);
        INTS_8.cast(r7.lanewise(VectorOperators.ADD, r7.lanewise(VectorOperators.LSHR, 31))
                .lanewise(VectorOperators.ASHR, 1)).intoArray(out, outOff + 56);
    }

    /**
     * The row of a 16-row block in which {@link #fdct16Columns} leaves each output: the parts of
     * the transform store their values in whichever rows are free.
     */
    private static final int[] FDCT16_ROWS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    /**
     * The row of a 32-row block in which {@link #fdct32Columns} leaves each output.
     */
    private static final int[] FDCT32_ROWS = {13, 0, 2, 3, 4, 16, 6, 30, 19, 10, 7, 26, 12, 18, 9, 15, 14, 17, 1, 24, 5, 21, 22, 23, 20, 25, 8, 27, 28, 29, 11, 31};

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation transforms the four columns at once in 32-bit lanes,
     * transposes the 4x4 block in registers with two stages of lane swaps and blends,
     * transforms the rows the same way and transposes back. The intermediate values of
     * residuals of 8-bit samples stay below 2^30, so the 32-bit arithmetic equals libvpx's
     * wider intermediates.
     */
    @Override
    void fdct4x4(short[] in, int inOff, int stride, int[] out, int outOff, long[] tmp) {
        var a0 = INTS_4.cast(SHORTS_4.cast(ShortVector.fromArray(S64, in, inOff))
                .convertShape(VectorOperators.S2I, I128, 0)).lanewise(VectorOperators.LSHL, 4);
        var a1 = INTS_4.cast(SHORTS_4.cast(ShortVector.fromArray(S64, in, inOff + stride))
                .convertShape(VectorOperators.S2I, I128, 0)).lanewise(VectorOperators.LSHL, 4);
        var a2 = INTS_4.cast(SHORTS_4.cast(ShortVector.fromArray(S64, in, inOff + 2 * stride))
                .convertShape(VectorOperators.S2I, I128, 0)).lanewise(VectorOperators.LSHL, 4);
        var a3 = INTS_4.cast(SHORTS_4.cast(ShortVector.fromArray(S64, in, inOff + 3 * stride))
                .convertShape(VectorOperators.S2I, I128, 0)).lanewise(VectorOperators.LSHL, 4);
        var bump = INTS_4.cast(IntVector.broadcast(I128, in[inOff] != 0 ? 1 : 0))
                .lanewise(VectorOperators.AND, INTS_4.cast(FIRST_LANE_4));
        a0 = INTS_4.cast(a0.lanewise(VectorOperators.ADD, bump));
        var round = INTS_4.cast(IntVector.broadcast(I128, 1 << 13));
        var c8 = INTS_4.cast(IntVector.broadcast(I128, 15137));
        var c16 = INTS_4.cast(IntVector.broadcast(I128, 11585));
        var c24 = INTS_4.cast(IntVector.broadcast(I128, 6270));
        var s0 = a0.lanewise(VectorOperators.ADD, a3);
        var s1 = a1.lanewise(VectorOperators.ADD, a2);
        var s2 = a1.lanewise(VectorOperators.SUB, a2);
        var s3 = a0.lanewise(VectorOperators.SUB, a3);
        var p0 = s0.lanewise(VectorOperators.ADD, s1).lanewise(VectorOperators.MUL, c16)
                .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var p2 = s0.lanewise(VectorOperators.SUB, s1).lanewise(VectorOperators.MUL, c16)
                .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var p1 = s2.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.ADD,
                s3.lanewise(VectorOperators.MUL, c8)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var p3 = s3.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.SUB,
                s2.lanewise(VectorOperators.MUL, c8)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var b0 = INTS_4.cast(p0.blend(p1.rearrange(SWAP_1_4), BIT_1_4));
        var b1 = INTS_4.cast(p0.rearrange(SWAP_1_4).blend(p1, BIT_1_4));
        var b2 = INTS_4.cast(p2.blend(p3.rearrange(SWAP_1_4), BIT_1_4));
        var b3 = INTS_4.cast(p2.rearrange(SWAP_1_4).blend(p3, BIT_1_4));
        var w0 = INTS_4.cast(b0.blend(b2.rearrange(SWAP_2_4), BIT_2_4));
        var w1 = INTS_4.cast(b1.blend(b3.rearrange(SWAP_2_4), BIT_2_4));
        var w2 = INTS_4.cast(b0.rearrange(SWAP_2_4).blend(b2, BIT_2_4));
        var w3 = INTS_4.cast(b1.rearrange(SWAP_2_4).blend(b3, BIT_2_4));
        var t0 = w0.lanewise(VectorOperators.ADD, w3);
        var t1 = w1.lanewise(VectorOperators.ADD, w2);
        var t2 = w1.lanewise(VectorOperators.SUB, w2);
        var t3 = w0.lanewise(VectorOperators.SUB, w3);
        var x0 = t0.lanewise(VectorOperators.ADD, t1).lanewise(VectorOperators.MUL, c16)
                .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var x2 = t0.lanewise(VectorOperators.SUB, t1).lanewise(VectorOperators.MUL, c16)
                .lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var x1 = t2.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.ADD,
                t3.lanewise(VectorOperators.MUL, c8)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var x3 = t3.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.SUB,
                t2.lanewise(VectorOperators.MUL, c8)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var d0 = INTS_4.cast(x0.blend(x1.rearrange(SWAP_1_4), BIT_1_4));
        var d1 = INTS_4.cast(x0.rearrange(SWAP_1_4).blend(x1, BIT_1_4));
        var d2 = INTS_4.cast(x2.blend(x3.rearrange(SWAP_1_4), BIT_1_4));
        var d3 = INTS_4.cast(x2.rearrange(SWAP_1_4).blend(x3, BIT_1_4));
        var one = INTS_4.cast(IntVector.broadcast(I128, 1));
        INTS_4.cast(INTS_4.cast(d0.blend(d2.rearrange(SWAP_2_4), BIT_2_4)).lanewise(VectorOperators.ADD, one)
                .lanewise(VectorOperators.ASHR, 2)).intoArray(out, outOff);
        INTS_4.cast(INTS_4.cast(d1.blend(d3.rearrange(SWAP_2_4), BIT_2_4)).lanewise(VectorOperators.ADD, one)
                .lanewise(VectorOperators.ASHR, 2)).intoArray(out, outOff + 4);
        INTS_4.cast(INTS_4.cast(d0.rearrange(SWAP_2_4).blend(d2, BIT_2_4)).lanewise(VectorOperators.ADD, one)
                .lanewise(VectorOperators.ASHR, 2)).intoArray(out, outOff + 8);
        INTS_4.cast(INTS_4.cast(d1.rearrange(SWAP_2_4).blend(d3, BIT_2_4)).lanewise(VectorOperators.ADD, one)
                .lanewise(VectorOperators.ASHR, 2)).intoArray(out, outOff + 12);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation widens the residuals into {@code out} and transforms its
     * columns eight at a time in 32-bit lanes, transposes the result into
     * {@code scratch} 8x8 blocks at a time while rounding it for the second pass,
     * transforms the columns of the transposed values, which are the first pass's rows,
     * and transposes them back into {@code out}. The intermediate values of residuals of
     * 8-bit samples stay below 2^28, so the 32-bit arithmetic equals libvpx's wider
     * intermediates.
     */
    @Override
    void fdct16x16(short[] in, int inOff, int stride, int[] out, int outOff, long[] tmp, int[] scratch) {
        widenResidual(in, inOff, stride, out, outOff, 16);
        fdct16Columns(out, outOff, 16);
        fdct16Columns(out, outOff + 8, 16);
        transposeRound(out, outOff, scratch, 0, 16, FDCT16_ROWS, 1, 0, 0, 2);
        fdct16Columns(scratch, 0, 16);
        fdct16Columns(scratch, 8, 16);
        transposeRound(scratch, 0, out, outOff, 16, FDCT16_ROWS, 0, 0, 0, 0);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation follows the scheme of {@link #fdct16x16}: both passes
     * transform eight columns at a time in 32-bit lanes, the 32-point transform split
     * into stages that each keep the 32 values of eight columns in registers and pass
     * them on through the array, and the rounding between and after the passes is
     * applied by the transpositions. An upper bound of every intermediate value over all
     * residuals from {@code -255} to {@code 255}, computed from the linear form of each
     * value plus its rounding error, is 2139710628, below 2^31, so the 32-bit
     * arithmetic equals libvpx's wider intermediates.
     */
    @Override
    void fdct32x32(short[] in, int inOff, int stride, int[] out, int outOff, boolean rdVariant, long[] tmp,
                   int[] scratch) {
        widenResidual(in, inOff, stride, out, outOff, 32);
        for (var x = 0; x < 32; x += 8) {
            fdct32Columns(out, outOff + x, false);
        }
        transposeRound(out, outOff, scratch, 0, 32, FDCT32_ROWS, 1, 1, 0, 2);
        for (var x = 0; x < 32; x += 8) {
            fdct32Columns(scratch, x, rdVariant);
        }
        if (rdVariant) {
            transposeRound(scratch, 0, out, outOff, 32, FDCT32_ROWS, 0, 0, 0, 0);
        } else {
            transposeRound(scratch, 0, out, outOff, 32, FDCT32_ROWS, 1, 0, 1, 2);
        }
    }

    /**
     * Computes the 32-point cosine transform of {@code vpx_fdct32} on eight columns of a 32-row
     * block in place, row {@code FDCT32_ROWS[k]} receiving output {@code k}.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param roundMid whether to divide the intermediate values by four after the second stage,
     * as the lower precision variant does
     */
    private static void fdct32Columns(int[] m, int off, boolean roundMid) {

        fdct32Part1(m, off, 32);
        fdct32Part2(m, off, 32);
        fdct32Part3(m, off, 32);
        if (roundMid) {
            halfRoundRows(m, off, 32);
        }
        fdct32Part4(m, off, 32);
        fdct32Part5(m, off, 32);
        fdct32Part6(m, off, 32);
        fdct32Part7(m, off, 32);
        fdct32Part8(m, off, 32);
        fdct32Part9(m, off, 32);
        fdct32Part10(m, off, 32);
        fdct32Part11(m, off, 32);
        fdct32Part12(m, off, 32);
    }

    /**
     * Widens a square block of residuals to 32-bit values times four, the input scaling of both
     * {@link #fdct16x16} and {@link #fdct32x32}, two rows per iteration so that the kernel stays
     * above the compiler's inlining limit.
     *
     * @param in the residuals
     * @param inOff the index of the first residual
     * @param stride the residual row stride
     * @param out receives the values, {@code size} per row
     * @param outOff the index of the first value
     * @param size the block size, 16 or 32
     */
    private static void widenResidual(short[] in, int inOff, int stride, int[] out, int outOff, int size) {
        for (var y = 0; y < size; y += 2) {
            for (var x = 0; x < size; x += 16) {
                var row = inOff + y * stride + x;
                var to = outOff + y * size + x;
                INTS_8.cast(INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, row))
                        .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.LSHL, 2))
                        .intoArray(out, to);
                INTS_8.cast(INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, row + 8))
                        .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.LSHL, 2))
                        .intoArray(out, to + 8);
                INTS_8.cast(INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, row + stride))
                        .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.LSHL, 2))
                        .intoArray(out, to + size);
                INTS_8.cast(INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, row + stride + 8))
                        .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.LSHL, 2))
                        .intoArray(out, to + size + 8);
            }
        }
    }

    /**
     * Transposes a square block of 32-bit values into another array 8x8 blocks at a time and
     * rounds every value: {@code v} becomes
     * {@code (v + add + (v > 0 ? positive : 0) + (v < 0 ? negative : 0)) >> shift}. Row {@code k} of
     * the block is the source row {@code rows[k]}.
     *
     * @param src the block
     * @param srcOff the index of its first value
     * @param dst receives the transposed block
     * @param dstOff the index of its first value
     * @param size the block size, a multiple of 8
     * @param rows the source row of each row of the block
     * @param add the rounding constant
     * @param positive the extra rounding of positive values, 0 or 1
     * @param negative the extra rounding of negative values, 0 or 1
     * @param shift the right shift
     */
    private static void transposeRound(int[] src, int srcOff, int[] dst, int dstOff, int size, int[] rows, int add,
                                       int positive, int negative, int shift) {
        var addend = INTS_8.cast(IntVector.broadcast(I256, add));
        var positiveBias = INTS_8.cast(IntVector.broadcast(I256, positive));
        var negativeBias = INTS_8.cast(IntVector.broadcast(I256, negative));
        for (var by = 0; by < size; by += 8) {
            for (var bx = 0; bx < size; bx += 8) {
                var from = srcOff + bx;
                var o0 = INTS_8.cast(IntVector.fromArray(I256, src, from + rows[by] * size));
                var o1 = INTS_8.cast(IntVector.fromArray(I256, src, from + rows[by + 1] * size));
                var o2 = INTS_8.cast(IntVector.fromArray(I256, src, from + rows[by + 2] * size));
                var o3 = INTS_8.cast(IntVector.fromArray(I256, src, from + rows[by + 3] * size));
                var o4 = INTS_8.cast(IntVector.fromArray(I256, src, from + rows[by + 4] * size));
                var o5 = INTS_8.cast(IntVector.fromArray(I256, src, from + rows[by + 5] * size));
                var o6 = INTS_8.cast(IntVector.fromArray(I256, src, from + rows[by + 6] * size));
                var o7 = INTS_8.cast(IntVector.fromArray(I256, src, from + rows[by + 7] * size));
                var a0 = INTS_8.cast(o0.blend(o1.rearrange(SWAP_1), BIT_1));
                var a1 = INTS_8.cast(o0.rearrange(SWAP_1).blend(o1, BIT_1));
                var a2 = INTS_8.cast(o2.blend(o3.rearrange(SWAP_1), BIT_1));
                var a3 = INTS_8.cast(o2.rearrange(SWAP_1).blend(o3, BIT_1));
                var a4 = INTS_8.cast(o4.blend(o5.rearrange(SWAP_1), BIT_1));
                var a5 = INTS_8.cast(o4.rearrange(SWAP_1).blend(o5, BIT_1));
                var a6 = INTS_8.cast(o6.blend(o7.rearrange(SWAP_1), BIT_1));
                var a7 = INTS_8.cast(o6.rearrange(SWAP_1).blend(o7, BIT_1));
                var b0 = INTS_8.cast(a0.blend(a2.rearrange(SWAP_2), BIT_2));
                var b2 = INTS_8.cast(a0.rearrange(SWAP_2).blend(a2, BIT_2));
                var b1 = INTS_8.cast(a1.blend(a3.rearrange(SWAP_2), BIT_2));
                var b3 = INTS_8.cast(a1.rearrange(SWAP_2).blend(a3, BIT_2));
                var b4 = INTS_8.cast(a4.blend(a6.rearrange(SWAP_2), BIT_2));
                var b6 = INTS_8.cast(a4.rearrange(SWAP_2).blend(a6, BIT_2));
                var b5 = INTS_8.cast(a5.blend(a7.rearrange(SWAP_2), BIT_2));
                var b7 = INTS_8.cast(a5.rearrange(SWAP_2).blend(a7, BIT_2));
                var to = dstOff + bx * size + by;

                var r0 = INTS_8.cast(b0.blend(b4.rearrange(SWAP_4), BIT_4));
                INTS_8.cast(r0.lanewise(VectorOperators.ADD, addend)
                        .lanewise(VectorOperators.ADD, r0.lanewise(VectorOperators.NEG)
                                .lanewise(VectorOperators.LSHR, 31).lanewise(VectorOperators.AND, positiveBias))
                        .lanewise(VectorOperators.ADD, r0.lanewise(VectorOperators.LSHR, 31)
                                .lanewise(VectorOperators.AND, negativeBias))
                        .lanewise(VectorOperators.ASHR, shift)).intoArray(dst, to);
                var r1 = INTS_8.cast(b1.blend(b5.rearrange(SWAP_4), BIT_4));
                INTS_8.cast(r1.lanewise(VectorOperators.ADD, addend)
                        .lanewise(VectorOperators.ADD, r1.lanewise(VectorOperators.NEG)
                                .lanewise(VectorOperators.LSHR, 31).lanewise(VectorOperators.AND, positiveBias))
                        .lanewise(VectorOperators.ADD, r1.lanewise(VectorOperators.LSHR, 31)
                                .lanewise(VectorOperators.AND, negativeBias))
                        .lanewise(VectorOperators.ASHR, shift)).intoArray(dst, to + 1 * size);
                var r2 = INTS_8.cast(b2.blend(b6.rearrange(SWAP_4), BIT_4));
                INTS_8.cast(r2.lanewise(VectorOperators.ADD, addend)
                        .lanewise(VectorOperators.ADD, r2.lanewise(VectorOperators.NEG)
                                .lanewise(VectorOperators.LSHR, 31).lanewise(VectorOperators.AND, positiveBias))
                        .lanewise(VectorOperators.ADD, r2.lanewise(VectorOperators.LSHR, 31)
                                .lanewise(VectorOperators.AND, negativeBias))
                        .lanewise(VectorOperators.ASHR, shift)).intoArray(dst, to + 2 * size);
                var r3 = INTS_8.cast(b3.blend(b7.rearrange(SWAP_4), BIT_4));
                INTS_8.cast(r3.lanewise(VectorOperators.ADD, addend)
                        .lanewise(VectorOperators.ADD, r3.lanewise(VectorOperators.NEG)
                                .lanewise(VectorOperators.LSHR, 31).lanewise(VectorOperators.AND, positiveBias))
                        .lanewise(VectorOperators.ADD, r3.lanewise(VectorOperators.LSHR, 31)
                                .lanewise(VectorOperators.AND, negativeBias))
                        .lanewise(VectorOperators.ASHR, shift)).intoArray(dst, to + 3 * size);
                var r4 = INTS_8.cast(b0.rearrange(SWAP_4).blend(b4, BIT_4));
                INTS_8.cast(r4.lanewise(VectorOperators.ADD, addend)
                        .lanewise(VectorOperators.ADD, r4.lanewise(VectorOperators.NEG)
                                .lanewise(VectorOperators.LSHR, 31).lanewise(VectorOperators.AND, positiveBias))
                        .lanewise(VectorOperators.ADD, r4.lanewise(VectorOperators.LSHR, 31)
                                .lanewise(VectorOperators.AND, negativeBias))
                        .lanewise(VectorOperators.ASHR, shift)).intoArray(dst, to + 4 * size);
                var r5 = INTS_8.cast(b1.rearrange(SWAP_4).blend(b5, BIT_4));
                INTS_8.cast(r5.lanewise(VectorOperators.ADD, addend)
                        .lanewise(VectorOperators.ADD, r5.lanewise(VectorOperators.NEG)
                                .lanewise(VectorOperators.LSHR, 31).lanewise(VectorOperators.AND, positiveBias))
                        .lanewise(VectorOperators.ADD, r5.lanewise(VectorOperators.LSHR, 31)
                                .lanewise(VectorOperators.AND, negativeBias))
                        .lanewise(VectorOperators.ASHR, shift)).intoArray(dst, to + 5 * size);
                var r6 = INTS_8.cast(b2.rearrange(SWAP_4).blend(b6, BIT_4));
                INTS_8.cast(r6.lanewise(VectorOperators.ADD, addend)
                        .lanewise(VectorOperators.ADD, r6.lanewise(VectorOperators.NEG)
                                .lanewise(VectorOperators.LSHR, 31).lanewise(VectorOperators.AND, positiveBias))
                        .lanewise(VectorOperators.ADD, r6.lanewise(VectorOperators.LSHR, 31)
                                .lanewise(VectorOperators.AND, negativeBias))
                        .lanewise(VectorOperators.ASHR, shift)).intoArray(dst, to + 6 * size);
                var r7 = INTS_8.cast(b3.rearrange(SWAP_4).blend(b7, BIT_4));
                INTS_8.cast(r7.lanewise(VectorOperators.ADD, addend)
                        .lanewise(VectorOperators.ADD, r7.lanewise(VectorOperators.NEG)
                                .lanewise(VectorOperators.LSHR, 31).lanewise(VectorOperators.AND, positiveBias))
                        .lanewise(VectorOperators.ADD, r7.lanewise(VectorOperators.LSHR, 31)
                                .lanewise(VectorOperators.AND, negativeBias))
                        .lanewise(VectorOperators.ASHR, shift)).intoArray(dst, to + 7 * size);
            }
        }
    }

    /**
     * Divides the 32 values of eight columns of a 32-row block by four, rounding half away from
     * zero ({@code half_round_shift}), four rows per iteration so that the kernel stays above the
     * compiler's inlining limit.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void halfRoundRows(int[] m, int off, int stride) {
        var one = INTS_8.cast(ONE_8);
        for (var r = 0; r < 32; r += 4) {
            var at = off + r * stride;
            var v = INTS_8.cast(IntVector.fromArray(I256, m, at));
            var w = INTS_8.cast(IntVector.fromArray(I256, m, at + stride));
            var x = INTS_8.cast(IntVector.fromArray(I256, m, at + 2 * stride));
            var y = INTS_8.cast(IntVector.fromArray(I256, m, at + 3 * stride));
            INTS_8.cast(v.lanewise(VectorOperators.ADD, one).lanewise(VectorOperators.ADD,
                    v.lanewise(VectorOperators.LSHR, 31)).lanewise(VectorOperators.ASHR, 2)).intoArray(m, at);
            INTS_8.cast(w.lanewise(VectorOperators.ADD, one).lanewise(VectorOperators.ADD,
                    w.lanewise(VectorOperators.LSHR, 31)).lanewise(VectorOperators.ASHR, 2))
                    .intoArray(m, at + stride);
            INTS_8.cast(x.lanewise(VectorOperators.ADD, one).lanewise(VectorOperators.ADD,
                    x.lanewise(VectorOperators.LSHR, 31)).lanewise(VectorOperators.ASHR, 2))
                    .intoArray(m, at + 2 * stride);
            INTS_8.cast(y.lanewise(VectorOperators.ADD, one).lanewise(VectorOperators.ADD,
                    y.lanewise(VectorOperators.LSHR, 31)).lanewise(VectorOperators.ASHR, 2))
                    .intoArray(m, at + 3 * stride);
        }
    }

    /**
     * Computes the 16-point cosine transform of {@code vpx_fdct16x16} on eight columns of a
     * 16-row block in place, row {@code FDCT16_ROWS[k]} receiving output {@code k}.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct16Columns(int[] m, int off, int stride) {
        fdct16Part1(m, off, stride);
        fdct16Part2(m, off, stride);
        fdct16Part3(m, off, stride);
    }

    /**
     * Computes part 1 of the operations of {@link #fdct16Columns} in place, on eight columns: the
     * values the later parts need stay in rows of the block, in rows the earlier parts free.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct16Part1(int[] m, int off, int stride) {
        var round = INTS_8.cast(DCT_ROUND_8);
        var c24 = INTS_8.cast(IntVector.broadcast(I256, 6270));
        var c16 = INTS_8.cast(IntVector.broadcast(I256, 11585));
        var c8 = INTS_8.cast(IntVector.broadcast(I256, 15137));
        var v0 = INTS_8.cast(IntVector.fromArray(I256, m, off));
        var v15 = INTS_8.cast(IntVector.fromArray(I256, m, off + 15 * stride));
        var v1 = INTS_8.cast(IntVector.fromArray(I256, m, off + 1 * stride));
        var v14 = INTS_8.cast(IntVector.fromArray(I256, m, off + 14 * stride));
        var v2 = INTS_8.cast(IntVector.fromArray(I256, m, off + 2 * stride));
        var v13 = INTS_8.cast(IntVector.fromArray(I256, m, off + 13 * stride));
        var v3 = INTS_8.cast(IntVector.fromArray(I256, m, off + 3 * stride));
        var v12 = INTS_8.cast(IntVector.fromArray(I256, m, off + 12 * stride));
        var v4 = INTS_8.cast(IntVector.fromArray(I256, m, off + 4 * stride));
        var v11 = INTS_8.cast(IntVector.fromArray(I256, m, off + 11 * stride));
        var v5 = INTS_8.cast(IntVector.fromArray(I256, m, off + 5 * stride));
        var v10 = INTS_8.cast(IntVector.fromArray(I256, m, off + 10 * stride));
        var v6 = INTS_8.cast(IntVector.fromArray(I256, m, off + 6 * stride));
        var v9 = INTS_8.cast(IntVector.fromArray(I256, m, off + 9 * stride));
        var v7 = INTS_8.cast(IntVector.fromArray(I256, m, off + 7 * stride));
        var v8 = INTS_8.cast(IntVector.fromArray(I256, m, off + 8 * stride));
        var v16 = v0.lanewise(VectorOperators.ADD, v15);
        var v17 = v1.lanewise(VectorOperators.ADD, v14);
        var v18 = v2.lanewise(VectorOperators.ADD, v13);
        var v19 = v3.lanewise(VectorOperators.ADD, v12);
        var v20 = v4.lanewise(VectorOperators.ADD, v11);
        var v21 = v5.lanewise(VectorOperators.ADD, v10);
        var v22 = v6.lanewise(VectorOperators.ADD, v9);
        var v23 = v7.lanewise(VectorOperators.ADD, v8);
        var v24 = v7.lanewise(VectorOperators.SUB, v8);
        var v25 = v6.lanewise(VectorOperators.SUB, v9);
        var v26 = v5.lanewise(VectorOperators.SUB, v10);
        var v27 = v4.lanewise(VectorOperators.SUB, v11);
        var v28 = v3.lanewise(VectorOperators.SUB, v12);
        var v29 = v2.lanewise(VectorOperators.SUB, v13);
        var v30 = v1.lanewise(VectorOperators.SUB, v14);
        var v31 = v0.lanewise(VectorOperators.SUB, v15);
        var v32 = v16.lanewise(VectorOperators.ADD, v23);
        var v33 = v17.lanewise(VectorOperators.ADD, v22);
        var v34 = v18.lanewise(VectorOperators.ADD, v21);
        var v35 = v19.lanewise(VectorOperators.ADD, v20);
        var v36 = v19.lanewise(VectorOperators.SUB, v20);
        var v37 = v18.lanewise(VectorOperators.SUB, v21);
        var v38 = v17.lanewise(VectorOperators.SUB, v22);
        var v39 = v16.lanewise(VectorOperators.SUB, v23);
        var v40 = v32.lanewise(VectorOperators.ADD, v35);
        var v41 = v33.lanewise(VectorOperators.ADD, v34);
        var v42 = v33.lanewise(VectorOperators.SUB, v34);
        var v43 = v32.lanewise(VectorOperators.SUB, v35);
        var v44 = v40.lanewise(VectorOperators.ADD, v41).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v45 = v43.lanewise(VectorOperators.MUL, c8).lanewise(VectorOperators.ADD, v42.lanewise(VectorOperators.MUL, c24)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v46 = v40.lanewise(VectorOperators.SUB, v41).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v47 = v43.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.SUB, v42.lanewise(VectorOperators.MUL, c8)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v48 = v38.lanewise(VectorOperators.SUB, v37).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v49 = v38.lanewise(VectorOperators.ADD, v37).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        INTS_8.cast(v24).intoArray(m, off + 1 * stride);
        INTS_8.cast(v25).intoArray(m, off + 2 * stride);
        INTS_8.cast(v26).intoArray(m, off + 3 * stride);
        INTS_8.cast(v27).intoArray(m, off + 5 * stride);
        INTS_8.cast(v28).intoArray(m, off + 6 * stride);
        INTS_8.cast(v29).intoArray(m, off + 7 * stride);
        INTS_8.cast(v30).intoArray(m, off + 9 * stride);
        INTS_8.cast(v31).intoArray(m, off + 10 * stride);
        INTS_8.cast(v36).intoArray(m, off + 11 * stride);
        INTS_8.cast(v39).intoArray(m, off + 13 * stride);
        INTS_8.cast(v44).intoArray(m, off);
        INTS_8.cast(v45).intoArray(m, off + 4 * stride);
        INTS_8.cast(v46).intoArray(m, off + 8 * stride);
        INTS_8.cast(v47).intoArray(m, off + 12 * stride);
        INTS_8.cast(v48).intoArray(m, off + 14 * stride);
        INTS_8.cast(v49).intoArray(m, off + 15 * stride);
    }

    /**
     * Computes part 2 of the operations of {@link #fdct16Columns} in place, on eight columns: the
     * values the later parts need stay in rows of the block, in rows the earlier parts free.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct16Part2(int[] m, int off, int stride) {
        var round = INTS_8.cast(DCT_ROUND_8);
        var c28 = INTS_8.cast(IntVector.broadcast(I256, 3196));
        var c20 = INTS_8.cast(IntVector.broadcast(I256, 9102));
        var c16 = INTS_8.cast(IntVector.broadcast(I256, 11585));
        var c12 = INTS_8.cast(IntVector.broadcast(I256, 13623));
        var c4 = INTS_8.cast(IntVector.broadcast(I256, 16069));
        var m20 = INTS_8.cast(IntVector.broadcast(I256, -9102));
        var m4 = INTS_8.cast(IntVector.broadcast(I256, -16069));
        var v36 = INTS_8.cast(IntVector.fromArray(I256, m, off + 11 * stride));
        var v48 = INTS_8.cast(IntVector.fromArray(I256, m, off + 14 * stride));
        var v39 = INTS_8.cast(IntVector.fromArray(I256, m, off + 13 * stride));
        var v49 = INTS_8.cast(IntVector.fromArray(I256, m, off + 15 * stride));
        var v29 = INTS_8.cast(IntVector.fromArray(I256, m, off + 7 * stride));
        var v26 = INTS_8.cast(IntVector.fromArray(I256, m, off + 3 * stride));
        var v28 = INTS_8.cast(IntVector.fromArray(I256, m, off + 6 * stride));
        var v27 = INTS_8.cast(IntVector.fromArray(I256, m, off + 5 * stride));
        var v24 = INTS_8.cast(IntVector.fromArray(I256, m, off + 1 * stride));
        var v25 = INTS_8.cast(IntVector.fromArray(I256, m, off + 2 * stride));
        var v31 = INTS_8.cast(IntVector.fromArray(I256, m, off + 10 * stride));
        var v30 = INTS_8.cast(IntVector.fromArray(I256, m, off + 9 * stride));
        var v50 = v36.lanewise(VectorOperators.ADD, v48);
        var v51 = v36.lanewise(VectorOperators.SUB, v48);
        var v52 = v39.lanewise(VectorOperators.SUB, v49);
        var v53 = v39.lanewise(VectorOperators.ADD, v49);
        var v54 = v50.lanewise(VectorOperators.MUL, c28).lanewise(VectorOperators.ADD, v53.lanewise(VectorOperators.MUL, c4)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v55 = v52.lanewise(VectorOperators.MUL, c12).lanewise(VectorOperators.ADD, v51.lanewise(VectorOperators.MUL, m20)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v56 = v51.lanewise(VectorOperators.MUL, c12).lanewise(VectorOperators.ADD, v52.lanewise(VectorOperators.MUL, c20)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v57 = v53.lanewise(VectorOperators.MUL, c28).lanewise(VectorOperators.ADD, v50.lanewise(VectorOperators.MUL, m4)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v58 = v29.lanewise(VectorOperators.SUB, v26).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v59 = v28.lanewise(VectorOperators.SUB, v27).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v60 = v28.lanewise(VectorOperators.ADD, v27).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v61 = v29.lanewise(VectorOperators.ADD, v26).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v62 = v24.lanewise(VectorOperators.ADD, v59);
        var v63 = v25.lanewise(VectorOperators.ADD, v58);
        var v64 = v25.lanewise(VectorOperators.SUB, v58);
        var v65 = v24.lanewise(VectorOperators.SUB, v59);
        var v66 = v31.lanewise(VectorOperators.SUB, v60);
        var v67 = v30.lanewise(VectorOperators.SUB, v61);
        var v68 = v30.lanewise(VectorOperators.ADD, v61);
        var v69 = v31.lanewise(VectorOperators.ADD, v60);
        INTS_8.cast(v54).intoArray(m, off + 2 * stride);
        INTS_8.cast(v55).intoArray(m, off + 6 * stride);
        INTS_8.cast(v56).intoArray(m, off + 10 * stride);
        INTS_8.cast(v57).intoArray(m, off + 14 * stride);
        INTS_8.cast(v62).intoArray(m, off + 1 * stride);
        INTS_8.cast(v63).intoArray(m, off + 3 * stride);
        INTS_8.cast(v64).intoArray(m, off + 5 * stride);
        INTS_8.cast(v65).intoArray(m, off + 7 * stride);
        INTS_8.cast(v66).intoArray(m, off + 9 * stride);
        INTS_8.cast(v67).intoArray(m, off + 11 * stride);
        INTS_8.cast(v68).intoArray(m, off + 13 * stride);
        INTS_8.cast(v69).intoArray(m, off + 15 * stride);
    }

    /**
     * Computes part 3 of the operations of {@link #fdct16Columns} in place, on eight columns: the
     * values the later parts need stay in rows of the block, in rows the earlier parts free.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct16Part3(int[] m, int off, int stride) {
        var round = INTS_8.cast(DCT_ROUND_8);
        var c30 = INTS_8.cast(IntVector.broadcast(I256, 1606));
        var c26 = INTS_8.cast(IntVector.broadcast(I256, 4756));
        var c24 = INTS_8.cast(IntVector.broadcast(I256, 6270));
        var c22 = INTS_8.cast(IntVector.broadcast(I256, 7723));
        var c18 = INTS_8.cast(IntVector.broadcast(I256, 10394));
        var c14 = INTS_8.cast(IntVector.broadcast(I256, 12665));
        var c10 = INTS_8.cast(IntVector.broadcast(I256, 14449));
        var c8 = INTS_8.cast(IntVector.broadcast(I256, 15137));
        var c6 = INTS_8.cast(IntVector.broadcast(I256, 15679));
        var c2 = INTS_8.cast(IntVector.broadcast(I256, 16305));
        var m26 = INTS_8.cast(IntVector.broadcast(I256, -4756));
        var m18 = INTS_8.cast(IntVector.broadcast(I256, -10394));
        var m10 = INTS_8.cast(IntVector.broadcast(I256, -14449));
        var m8 = INTS_8.cast(IntVector.broadcast(I256, -15137));
        var m2 = INTS_8.cast(IntVector.broadcast(I256, -16305));
        var v63 = INTS_8.cast(IntVector.fromArray(I256, m, off + 3 * stride));
        var v68 = INTS_8.cast(IntVector.fromArray(I256, m, off + 13 * stride));
        var v64 = INTS_8.cast(IntVector.fromArray(I256, m, off + 5 * stride));
        var v67 = INTS_8.cast(IntVector.fromArray(I256, m, off + 11 * stride));
        var v62 = INTS_8.cast(IntVector.fromArray(I256, m, off + 1 * stride));
        var v65 = INTS_8.cast(IntVector.fromArray(I256, m, off + 7 * stride));
        var v66 = INTS_8.cast(IntVector.fromArray(I256, m, off + 9 * stride));
        var v69 = INTS_8.cast(IntVector.fromArray(I256, m, off + 15 * stride));
        var v70 = v63.lanewise(VectorOperators.MUL, m8).lanewise(VectorOperators.ADD, v68.lanewise(VectorOperators.MUL, c24)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v71 = v64.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.ADD, v67.lanewise(VectorOperators.MUL, c8)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v72 = v64.lanewise(VectorOperators.MUL, c8).lanewise(VectorOperators.SUB, v67.lanewise(VectorOperators.MUL, c24)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v73 = v63.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.ADD, v68.lanewise(VectorOperators.MUL, c8)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v74 = v62.lanewise(VectorOperators.ADD, v70);
        var v75 = v62.lanewise(VectorOperators.SUB, v70);
        var v76 = v65.lanewise(VectorOperators.ADD, v71);
        var v77 = v65.lanewise(VectorOperators.SUB, v71);
        var v78 = v66.lanewise(VectorOperators.SUB, v72);
        var v79 = v66.lanewise(VectorOperators.ADD, v72);
        var v80 = v69.lanewise(VectorOperators.SUB, v73);
        var v81 = v69.lanewise(VectorOperators.ADD, v73);
        var v82 = v74.lanewise(VectorOperators.MUL, c30).lanewise(VectorOperators.ADD, v81.lanewise(VectorOperators.MUL, c2)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v83 = v75.lanewise(VectorOperators.MUL, c14).lanewise(VectorOperators.ADD, v80.lanewise(VectorOperators.MUL, c18)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v84 = v76.lanewise(VectorOperators.MUL, c22).lanewise(VectorOperators.ADD, v79.lanewise(VectorOperators.MUL, c10)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v85 = v77.lanewise(VectorOperators.MUL, c6).lanewise(VectorOperators.ADD, v78.lanewise(VectorOperators.MUL, c26)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v86 = v77.lanewise(VectorOperators.MUL, m26).lanewise(VectorOperators.ADD, v78.lanewise(VectorOperators.MUL, c6)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v87 = v76.lanewise(VectorOperators.MUL, m10).lanewise(VectorOperators.ADD, v79.lanewise(VectorOperators.MUL, c22)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v88 = v75.lanewise(VectorOperators.MUL, m18).lanewise(VectorOperators.ADD, v80.lanewise(VectorOperators.MUL, c14)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v89 = v74.lanewise(VectorOperators.MUL, m2).lanewise(VectorOperators.ADD, v81.lanewise(VectorOperators.MUL, c30)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        INTS_8.cast(v82).intoArray(m, off + 1 * stride);
        INTS_8.cast(v83).intoArray(m, off + 9 * stride);
        INTS_8.cast(v84).intoArray(m, off + 5 * stride);
        INTS_8.cast(v85).intoArray(m, off + 13 * stride);
        INTS_8.cast(v86).intoArray(m, off + 3 * stride);
        INTS_8.cast(v87).intoArray(m, off + 11 * stride);
        INTS_8.cast(v88).intoArray(m, off + 7 * stride);
        INTS_8.cast(v89).intoArray(m, off + 15 * stride);
    }

    /**
     * Computes part 1 of the operations of {@link #fdct32Columns} in place, on eight columns: the
     * values the later parts need stay in rows of the block, in rows the earlier parts free.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct32Part1(int[] m, int off, int stride) {
        var v0 = INTS_8.cast(IntVector.fromArray(I256, m, off));
        var v31 = INTS_8.cast(IntVector.fromArray(I256, m, off + 31 * stride));
        var v1 = INTS_8.cast(IntVector.fromArray(I256, m, off + 1 * stride));
        var v30 = INTS_8.cast(IntVector.fromArray(I256, m, off + 30 * stride));
        var v2 = INTS_8.cast(IntVector.fromArray(I256, m, off + 2 * stride));
        var v29 = INTS_8.cast(IntVector.fromArray(I256, m, off + 29 * stride));
        var v3 = INTS_8.cast(IntVector.fromArray(I256, m, off + 3 * stride));
        var v28 = INTS_8.cast(IntVector.fromArray(I256, m, off + 28 * stride));
        var v4 = INTS_8.cast(IntVector.fromArray(I256, m, off + 4 * stride));
        var v27 = INTS_8.cast(IntVector.fromArray(I256, m, off + 27 * stride));
        var v5 = INTS_8.cast(IntVector.fromArray(I256, m, off + 5 * stride));
        var v26 = INTS_8.cast(IntVector.fromArray(I256, m, off + 26 * stride));
        var v6 = INTS_8.cast(IntVector.fromArray(I256, m, off + 6 * stride));
        var v25 = INTS_8.cast(IntVector.fromArray(I256, m, off + 25 * stride));
        var v7 = INTS_8.cast(IntVector.fromArray(I256, m, off + 7 * stride));
        var v24 = INTS_8.cast(IntVector.fromArray(I256, m, off + 24 * stride));
        var v8 = INTS_8.cast(IntVector.fromArray(I256, m, off + 8 * stride));
        var v23 = INTS_8.cast(IntVector.fromArray(I256, m, off + 23 * stride));
        var v9 = INTS_8.cast(IntVector.fromArray(I256, m, off + 9 * stride));
        var v22 = INTS_8.cast(IntVector.fromArray(I256, m, off + 22 * stride));
        var v10 = INTS_8.cast(IntVector.fromArray(I256, m, off + 10 * stride));
        var v21 = INTS_8.cast(IntVector.fromArray(I256, m, off + 21 * stride));
        var v11 = INTS_8.cast(IntVector.fromArray(I256, m, off + 11 * stride));
        var v20 = INTS_8.cast(IntVector.fromArray(I256, m, off + 20 * stride));
        var v32 = v0.lanewise(VectorOperators.ADD, v31);
        var v33 = v0.lanewise(VectorOperators.SUB, v31);
        var v34 = v1.lanewise(VectorOperators.ADD, v30);
        var v35 = v1.lanewise(VectorOperators.SUB, v30);
        var v36 = v2.lanewise(VectorOperators.ADD, v29);
        var v37 = v2.lanewise(VectorOperators.SUB, v29);
        var v38 = v3.lanewise(VectorOperators.ADD, v28);
        var v39 = v3.lanewise(VectorOperators.SUB, v28);
        var v40 = v4.lanewise(VectorOperators.ADD, v27);
        var v41 = v4.lanewise(VectorOperators.SUB, v27);
        var v42 = v5.lanewise(VectorOperators.ADD, v26);
        var v43 = v5.lanewise(VectorOperators.SUB, v26);
        var v44 = v6.lanewise(VectorOperators.ADD, v25);
        var v45 = v6.lanewise(VectorOperators.SUB, v25);
        var v46 = v7.lanewise(VectorOperators.ADD, v24);
        var v47 = v7.lanewise(VectorOperators.SUB, v24);
        var v48 = v8.lanewise(VectorOperators.ADD, v23);
        var v49 = v8.lanewise(VectorOperators.SUB, v23);
        var v50 = v9.lanewise(VectorOperators.ADD, v22);
        var v51 = v9.lanewise(VectorOperators.SUB, v22);
        var v52 = v10.lanewise(VectorOperators.ADD, v21);
        var v53 = v10.lanewise(VectorOperators.SUB, v21);
        var v54 = v11.lanewise(VectorOperators.ADD, v20);
        var v55 = v11.lanewise(VectorOperators.SUB, v20);
        INTS_8.cast(v32).intoArray(m, off);
        INTS_8.cast(v33).intoArray(m, off + 31 * stride);
        INTS_8.cast(v34).intoArray(m, off + 1 * stride);
        INTS_8.cast(v35).intoArray(m, off + 30 * stride);
        INTS_8.cast(v36).intoArray(m, off + 2 * stride);
        INTS_8.cast(v37).intoArray(m, off + 29 * stride);
        INTS_8.cast(v38).intoArray(m, off + 3 * stride);
        INTS_8.cast(v39).intoArray(m, off + 28 * stride);
        INTS_8.cast(v40).intoArray(m, off + 4 * stride);
        INTS_8.cast(v41).intoArray(m, off + 5 * stride);
        INTS_8.cast(v42).intoArray(m, off + 6 * stride);
        INTS_8.cast(v43).intoArray(m, off + 7 * stride);
        INTS_8.cast(v44).intoArray(m, off + 8 * stride);
        INTS_8.cast(v45).intoArray(m, off + 9 * stride);
        INTS_8.cast(v46).intoArray(m, off + 10 * stride);
        INTS_8.cast(v47).intoArray(m, off + 11 * stride);
        INTS_8.cast(v48).intoArray(m, off + 20 * stride);
        INTS_8.cast(v49).intoArray(m, off + 21 * stride);
        INTS_8.cast(v50).intoArray(m, off + 22 * stride);
        INTS_8.cast(v51).intoArray(m, off + 23 * stride);
        INTS_8.cast(v52).intoArray(m, off + 24 * stride);
        INTS_8.cast(v53).intoArray(m, off + 25 * stride);
        INTS_8.cast(v54).intoArray(m, off + 26 * stride);
        INTS_8.cast(v55).intoArray(m, off + 27 * stride);
    }

    /**
     * Computes part 2 of the operations of {@link #fdct32Columns} in place, on eight columns: the
     * values the later parts need stay in rows of the block, in rows the earlier parts free.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct32Part2(int[] m, int off, int stride) {
        var round = INTS_8.cast(DCT_ROUND_8);
        var c16 = INTS_8.cast(IntVector.broadcast(I256, 11585));
        var v12 = INTS_8.cast(IntVector.fromArray(I256, m, off + 12 * stride));
        var v19 = INTS_8.cast(IntVector.fromArray(I256, m, off + 19 * stride));
        var v13 = INTS_8.cast(IntVector.fromArray(I256, m, off + 13 * stride));
        var v18 = INTS_8.cast(IntVector.fromArray(I256, m, off + 18 * stride));
        var v14 = INTS_8.cast(IntVector.fromArray(I256, m, off + 14 * stride));
        var v17 = INTS_8.cast(IntVector.fromArray(I256, m, off + 17 * stride));
        var v15 = INTS_8.cast(IntVector.fromArray(I256, m, off + 15 * stride));
        var v16 = INTS_8.cast(IntVector.fromArray(I256, m, off + 16 * stride));
        var v32 = INTS_8.cast(IntVector.fromArray(I256, m, off));
        var v34 = INTS_8.cast(IntVector.fromArray(I256, m, off + 1 * stride));
        var v36 = INTS_8.cast(IntVector.fromArray(I256, m, off + 2 * stride));
        var v38 = INTS_8.cast(IntVector.fromArray(I256, m, off + 3 * stride));
        var v40 = INTS_8.cast(IntVector.fromArray(I256, m, off + 4 * stride));
        var v54 = INTS_8.cast(IntVector.fromArray(I256, m, off + 26 * stride));
        var v42 = INTS_8.cast(IntVector.fromArray(I256, m, off + 6 * stride));
        var v52 = INTS_8.cast(IntVector.fromArray(I256, m, off + 24 * stride));
        var v44 = INTS_8.cast(IntVector.fromArray(I256, m, off + 8 * stride));
        var v50 = INTS_8.cast(IntVector.fromArray(I256, m, off + 22 * stride));
        var v46 = INTS_8.cast(IntVector.fromArray(I256, m, off + 10 * stride));
        var v48 = INTS_8.cast(IntVector.fromArray(I256, m, off + 20 * stride));
        var v55 = INTS_8.cast(IntVector.fromArray(I256, m, off + 27 * stride));
        var v41 = INTS_8.cast(IntVector.fromArray(I256, m, off + 5 * stride));
        var v56 = v12.lanewise(VectorOperators.ADD, v19);
        var v57 = v12.lanewise(VectorOperators.SUB, v19);
        var v58 = v13.lanewise(VectorOperators.ADD, v18);
        var v59 = v13.lanewise(VectorOperators.SUB, v18);
        var v60 = v14.lanewise(VectorOperators.ADD, v17);
        var v61 = v14.lanewise(VectorOperators.SUB, v17);
        var v62 = v15.lanewise(VectorOperators.ADD, v16);
        var v63 = v15.lanewise(VectorOperators.SUB, v16);
        var v64 = v32.lanewise(VectorOperators.ADD, v62);
        var v65 = v32.lanewise(VectorOperators.SUB, v62);
        var v66 = v34.lanewise(VectorOperators.ADD, v60);
        var v67 = v34.lanewise(VectorOperators.SUB, v60);
        var v68 = v36.lanewise(VectorOperators.ADD, v58);
        var v69 = v36.lanewise(VectorOperators.SUB, v58);
        var v70 = v38.lanewise(VectorOperators.ADD, v56);
        var v71 = v38.lanewise(VectorOperators.SUB, v56);
        var v72 = v40.lanewise(VectorOperators.ADD, v54);
        var v73 = v40.lanewise(VectorOperators.SUB, v54);
        var v74 = v42.lanewise(VectorOperators.ADD, v52);
        var v75 = v42.lanewise(VectorOperators.SUB, v52);
        var v76 = v44.lanewise(VectorOperators.ADD, v50);
        var v77 = v44.lanewise(VectorOperators.SUB, v50);
        var v78 = v46.lanewise(VectorOperators.ADD, v48);
        var v79 = v46.lanewise(VectorOperators.SUB, v48);
        var v80 = v41.lanewise(VectorOperators.SUB, v55).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v81 = v41.lanewise(VectorOperators.ADD, v55).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        INTS_8.cast(v57).intoArray(m, off + 19 * stride);
        INTS_8.cast(v59).intoArray(m, off + 18 * stride);
        INTS_8.cast(v61).intoArray(m, off + 17 * stride);
        INTS_8.cast(v63).intoArray(m, off + 16 * stride);
        INTS_8.cast(v64).intoArray(m, off);
        INTS_8.cast(v65).intoArray(m, off + 15 * stride);
        INTS_8.cast(v66).intoArray(m, off + 1 * stride);
        INTS_8.cast(v67).intoArray(m, off + 14 * stride);
        INTS_8.cast(v68).intoArray(m, off + 2 * stride);
        INTS_8.cast(v69).intoArray(m, off + 13 * stride);
        INTS_8.cast(v70).intoArray(m, off + 3 * stride);
        INTS_8.cast(v71).intoArray(m, off + 12 * stride);
        INTS_8.cast(v72).intoArray(m, off + 4 * stride);
        INTS_8.cast(v73).intoArray(m, off + 22 * stride);
        INTS_8.cast(v74).intoArray(m, off + 5 * stride);
        INTS_8.cast(v75).intoArray(m, off + 10 * stride);
        INTS_8.cast(v76).intoArray(m, off + 6 * stride);
        INTS_8.cast(v77).intoArray(m, off + 24 * stride);
        INTS_8.cast(v78).intoArray(m, off + 26 * stride);
        INTS_8.cast(v79).intoArray(m, off + 8 * stride);
        INTS_8.cast(v80).intoArray(m, off + 20 * stride);
        INTS_8.cast(v81).intoArray(m, off + 27 * stride);
    }

    /**
     * Computes part 3 of the operations of {@link #fdct32Columns} in place, on eight columns: the
     * values the later parts need stay in rows of the block, in rows the earlier parts free.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct32Part3(int[] m, int off, int stride) {
        var round = INTS_8.cast(DCT_ROUND_8);
        var c16 = INTS_8.cast(IntVector.broadcast(I256, 11585));
        var v53 = INTS_8.cast(IntVector.fromArray(I256, m, off + 25 * stride));
        var v43 = INTS_8.cast(IntVector.fromArray(I256, m, off + 7 * stride));
        var v51 = INTS_8.cast(IntVector.fromArray(I256, m, off + 23 * stride));
        var v45 = INTS_8.cast(IntVector.fromArray(I256, m, off + 9 * stride));
        var v49 = INTS_8.cast(IntVector.fromArray(I256, m, off + 21 * stride));
        var v47 = INTS_8.cast(IntVector.fromArray(I256, m, off + 11 * stride));
        var v82 = v43.lanewise(VectorOperators.SUB, v53).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v83 = v43.lanewise(VectorOperators.ADD, v53).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v84 = v45.lanewise(VectorOperators.SUB, v51).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v85 = v45.lanewise(VectorOperators.ADD, v51).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v86 = v47.lanewise(VectorOperators.SUB, v49).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v87 = v47.lanewise(VectorOperators.ADD, v49).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        INTS_8.cast(v82).intoArray(m, off + 21 * stride);
        INTS_8.cast(v83).intoArray(m, off + 7 * stride);
        INTS_8.cast(v84).intoArray(m, off + 9 * stride);
        INTS_8.cast(v85).intoArray(m, off + 25 * stride);
        INTS_8.cast(v86).intoArray(m, off + 23 * stride);
        INTS_8.cast(v87).intoArray(m, off + 11 * stride);
    }

    /**
     * Computes part 4 of the operations of {@link #fdct32Columns} in place, on eight columns: the
     * values the later parts need stay in rows of the block, in rows the earlier parts free.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct32Part4(int[] m, int off, int stride) {
        var round = INTS_8.cast(DCT_ROUND_8);
        var c16 = INTS_8.cast(IntVector.broadcast(I256, 11585));
        var v0 = INTS_8.cast(IntVector.fromArray(I256, m, off));
        var v7 = INTS_8.cast(IntVector.fromArray(I256, m, off + 26 * stride));
        var v1 = INTS_8.cast(IntVector.fromArray(I256, m, off + 1 * stride));
        var v6 = INTS_8.cast(IntVector.fromArray(I256, m, off + 6 * stride));
        var v2 = INTS_8.cast(IntVector.fromArray(I256, m, off + 2 * stride));
        var v5 = INTS_8.cast(IntVector.fromArray(I256, m, off + 5 * stride));
        var v3 = INTS_8.cast(IntVector.fromArray(I256, m, off + 3 * stride));
        var v4 = INTS_8.cast(IntVector.fromArray(I256, m, off + 4 * stride));
        var v10 = INTS_8.cast(IntVector.fromArray(I256, m, off + 10 * stride));
        var v13 = INTS_8.cast(IntVector.fromArray(I256, m, off + 13 * stride));
        var v11 = INTS_8.cast(IntVector.fromArray(I256, m, off + 22 * stride));
        var v12 = INTS_8.cast(IntVector.fromArray(I256, m, off + 12 * stride));
        var v16 = INTS_8.cast(IntVector.fromArray(I256, m, off + 16 * stride));
        var v23 = INTS_8.cast(IntVector.fromArray(I256, m, off + 23 * stride));
        var v17 = INTS_8.cast(IntVector.fromArray(I256, m, off + 17 * stride));
        var v22 = INTS_8.cast(IntVector.fromArray(I256, m, off + 9 * stride));
        var v18 = INTS_8.cast(IntVector.fromArray(I256, m, off + 18 * stride));
        var v21 = INTS_8.cast(IntVector.fromArray(I256, m, off + 21 * stride));
        var v19 = INTS_8.cast(IntVector.fromArray(I256, m, off + 19 * stride));
        var v20 = INTS_8.cast(IntVector.fromArray(I256, m, off + 20 * stride));
        var v32 = v0.lanewise(VectorOperators.ADD, v7);
        var v33 = v1.lanewise(VectorOperators.ADD, v6);
        var v34 = v2.lanewise(VectorOperators.ADD, v5);
        var v35 = v3.lanewise(VectorOperators.ADD, v4);
        var v36 = v3.lanewise(VectorOperators.SUB, v4);
        var v37 = v2.lanewise(VectorOperators.SUB, v5);
        var v38 = v1.lanewise(VectorOperators.SUB, v6);
        var v39 = v0.lanewise(VectorOperators.SUB, v7);
        var v40 = v13.lanewise(VectorOperators.SUB, v10).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v41 = v12.lanewise(VectorOperators.SUB, v11).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v42 = v12.lanewise(VectorOperators.ADD, v11).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v43 = v13.lanewise(VectorOperators.ADD, v10).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v44 = v16.lanewise(VectorOperators.ADD, v23);
        var v45 = v17.lanewise(VectorOperators.ADD, v22);
        var v46 = v18.lanewise(VectorOperators.ADD, v21);
        var v47 = v19.lanewise(VectorOperators.ADD, v20);
        var v48 = v19.lanewise(VectorOperators.SUB, v20);
        var v49 = v18.lanewise(VectorOperators.SUB, v21);
        var v50 = v17.lanewise(VectorOperators.SUB, v22);
        var v51 = v16.lanewise(VectorOperators.SUB, v23);
        INTS_8.cast(v32).intoArray(m, off);
        INTS_8.cast(v33).intoArray(m, off + 1 * stride);
        INTS_8.cast(v34).intoArray(m, off + 2 * stride);
        INTS_8.cast(v35).intoArray(m, off + 3 * stride);
        INTS_8.cast(v36).intoArray(m, off + 4 * stride);
        INTS_8.cast(v37).intoArray(m, off + 5 * stride);
        INTS_8.cast(v38).intoArray(m, off + 6 * stride);
        INTS_8.cast(v39).intoArray(m, off + 9 * stride);
        INTS_8.cast(v40).intoArray(m, off + 10 * stride);
        INTS_8.cast(v41).intoArray(m, off + 12 * stride);
        INTS_8.cast(v42).intoArray(m, off + 13 * stride);
        INTS_8.cast(v43).intoArray(m, off + 16 * stride);
        INTS_8.cast(v44).intoArray(m, off + 17 * stride);
        INTS_8.cast(v45).intoArray(m, off + 18 * stride);
        INTS_8.cast(v46).intoArray(m, off + 19 * stride);
        INTS_8.cast(v47).intoArray(m, off + 20 * stride);
        INTS_8.cast(v48).intoArray(m, off + 21 * stride);
        INTS_8.cast(v49).intoArray(m, off + 22 * stride);
        INTS_8.cast(v50).intoArray(m, off + 23 * stride);
        INTS_8.cast(v51).intoArray(m, off + 26 * stride);
    }

    /**
     * Computes part 5 of the operations of {@link #fdct32Columns} in place, on eight columns: the
     * values the later parts need stay in rows of the block, in rows the earlier parts free.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct32Part5(int[] m, int off, int stride) {
        var round = INTS_8.cast(DCT_ROUND_8);
        var c16 = INTS_8.cast(IntVector.broadcast(I256, 11585));
        var v24 = INTS_8.cast(IntVector.fromArray(I256, m, off + 11 * stride));
        var v31 = INTS_8.cast(IntVector.fromArray(I256, m, off + 31 * stride));
        var v25 = INTS_8.cast(IntVector.fromArray(I256, m, off + 25 * stride));
        var v30 = INTS_8.cast(IntVector.fromArray(I256, m, off + 30 * stride));
        var v26 = INTS_8.cast(IntVector.fromArray(I256, m, off + 7 * stride));
        var v29 = INTS_8.cast(IntVector.fromArray(I256, m, off + 29 * stride));
        var v27 = INTS_8.cast(IntVector.fromArray(I256, m, off + 27 * stride));
        var v28 = INTS_8.cast(IntVector.fromArray(I256, m, off + 28 * stride));
        var v32 = INTS_8.cast(IntVector.fromArray(I256, m, off));
        var v35 = INTS_8.cast(IntVector.fromArray(I256, m, off + 3 * stride));
        var v33 = INTS_8.cast(IntVector.fromArray(I256, m, off + 1 * stride));
        var v34 = INTS_8.cast(IntVector.fromArray(I256, m, off + 2 * stride));
        var v37 = INTS_8.cast(IntVector.fromArray(I256, m, off + 5 * stride));
        var v38 = INTS_8.cast(IntVector.fromArray(I256, m, off + 6 * stride));
        var v8 = INTS_8.cast(IntVector.fromArray(I256, m, off + 8 * stride));
        var v41 = INTS_8.cast(IntVector.fromArray(I256, m, off + 12 * stride));
        var v9 = INTS_8.cast(IntVector.fromArray(I256, m, off + 24 * stride));
        var v40 = INTS_8.cast(IntVector.fromArray(I256, m, off + 10 * stride));
        var v42 = INTS_8.cast(IntVector.fromArray(I256, m, off + 13 * stride));
        var v15 = INTS_8.cast(IntVector.fromArray(I256, m, off + 15 * stride));
        var v43 = INTS_8.cast(IntVector.fromArray(I256, m, off + 16 * stride));
        var v14 = INTS_8.cast(IntVector.fromArray(I256, m, off + 14 * stride));
        var v52 = v31.lanewise(VectorOperators.SUB, v24);
        var v53 = v30.lanewise(VectorOperators.SUB, v25);
        var v54 = v29.lanewise(VectorOperators.SUB, v26);
        var v55 = v28.lanewise(VectorOperators.SUB, v27);
        var v56 = v28.lanewise(VectorOperators.ADD, v27);
        var v57 = v29.lanewise(VectorOperators.ADD, v26);
        var v58 = v30.lanewise(VectorOperators.ADD, v25);
        var v59 = v31.lanewise(VectorOperators.ADD, v24);
        var v60 = v32.lanewise(VectorOperators.ADD, v35);
        var v61 = v33.lanewise(VectorOperators.ADD, v34);
        var v62 = v33.lanewise(VectorOperators.SUB, v34);
        var v63 = v32.lanewise(VectorOperators.SUB, v35);
        var v64 = v38.lanewise(VectorOperators.SUB, v37).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v65 = v38.lanewise(VectorOperators.ADD, v37).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v66 = v8.lanewise(VectorOperators.ADD, v41);
        var v67 = v9.lanewise(VectorOperators.ADD, v40);
        var v68 = v9.lanewise(VectorOperators.SUB, v40);
        var v69 = v8.lanewise(VectorOperators.SUB, v41);
        var v70 = v15.lanewise(VectorOperators.SUB, v42);
        var v71 = v14.lanewise(VectorOperators.SUB, v43);
        var v72 = v14.lanewise(VectorOperators.ADD, v43);
        var v73 = v15.lanewise(VectorOperators.ADD, v42);
        INTS_8.cast(v52).intoArray(m, off);
        INTS_8.cast(v53).intoArray(m, off + 1 * stride);
        INTS_8.cast(v54).intoArray(m, off + 2 * stride);
        INTS_8.cast(v55).intoArray(m, off + 3 * stride);
        INTS_8.cast(v56).intoArray(m, off + 5 * stride);
        INTS_8.cast(v57).intoArray(m, off + 6 * stride);
        INTS_8.cast(v58).intoArray(m, off + 7 * stride);
        INTS_8.cast(v59).intoArray(m, off + 8 * stride);
        INTS_8.cast(v60).intoArray(m, off + 10 * stride);
        INTS_8.cast(v61).intoArray(m, off + 11 * stride);
        INTS_8.cast(v62).intoArray(m, off + 12 * stride);
        INTS_8.cast(v63).intoArray(m, off + 13 * stride);
        INTS_8.cast(v64).intoArray(m, off + 14 * stride);
        INTS_8.cast(v65).intoArray(m, off + 15 * stride);
        INTS_8.cast(v66).intoArray(m, off + 16 * stride);
        INTS_8.cast(v67).intoArray(m, off + 24 * stride);
        INTS_8.cast(v68).intoArray(m, off + 25 * stride);
        INTS_8.cast(v69).intoArray(m, off + 27 * stride);
        INTS_8.cast(v70).intoArray(m, off + 28 * stride);
        INTS_8.cast(v71).intoArray(m, off + 29 * stride);
        INTS_8.cast(v72).intoArray(m, off + 30 * stride);
        INTS_8.cast(v73).intoArray(m, off + 31 * stride);
    }

    /**
     * Computes part 6 of the operations of {@link #fdct32Columns} in place, on eight columns: the
     * values the later parts need stay in rows of the block, in rows the earlier parts free.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct32Part6(int[] m, int off, int stride) {
        var round = INTS_8.cast(DCT_ROUND_8);
        var c24 = INTS_8.cast(IntVector.broadcast(I256, 6270));
        var c16 = INTS_8.cast(IntVector.broadcast(I256, 11585));
        var c8 = INTS_8.cast(IntVector.broadcast(I256, 15137));
        var m24 = INTS_8.cast(IntVector.broadcast(I256, -6270));
        var m8 = INTS_8.cast(IntVector.broadcast(I256, -15137));
        var v46 = INTS_8.cast(IntVector.fromArray(I256, m, off + 19 * stride));
        var v57 = INTS_8.cast(IntVector.fromArray(I256, m, off + 6 * stride));
        var v47 = INTS_8.cast(IntVector.fromArray(I256, m, off + 20 * stride));
        var v56 = INTS_8.cast(IntVector.fromArray(I256, m, off + 5 * stride));
        var v48 = INTS_8.cast(IntVector.fromArray(I256, m, off + 21 * stride));
        var v55 = INTS_8.cast(IntVector.fromArray(I256, m, off + 3 * stride));
        var v49 = INTS_8.cast(IntVector.fromArray(I256, m, off + 22 * stride));
        var v54 = INTS_8.cast(IntVector.fromArray(I256, m, off + 2 * stride));
        var v60 = INTS_8.cast(IntVector.fromArray(I256, m, off + 10 * stride));
        var v61 = INTS_8.cast(IntVector.fromArray(I256, m, off + 11 * stride));
        var v62 = INTS_8.cast(IntVector.fromArray(I256, m, off + 12 * stride));
        var v63 = INTS_8.cast(IntVector.fromArray(I256, m, off + 13 * stride));
        var v36 = INTS_8.cast(IntVector.fromArray(I256, m, off + 4 * stride));
        var v64 = INTS_8.cast(IntVector.fromArray(I256, m, off + 14 * stride));
        var v74 = v46.lanewise(VectorOperators.MUL, m8).lanewise(VectorOperators.ADD, v57.lanewise(VectorOperators.MUL, c24)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v75 = v47.lanewise(VectorOperators.MUL, m8).lanewise(VectorOperators.ADD, v56.lanewise(VectorOperators.MUL, c24)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v76 = v48.lanewise(VectorOperators.MUL, m24).lanewise(VectorOperators.ADD, v55.lanewise(VectorOperators.MUL, m8)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v77 = v49.lanewise(VectorOperators.MUL, m24).lanewise(VectorOperators.ADD, v54.lanewise(VectorOperators.MUL, m8)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v78 = v54.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.ADD, v49.lanewise(VectorOperators.MUL, m8)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v79 = v55.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.ADD, v48.lanewise(VectorOperators.MUL, m8)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v80 = v56.lanewise(VectorOperators.MUL, c8).lanewise(VectorOperators.ADD, v47.lanewise(VectorOperators.MUL, c24)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v81 = v57.lanewise(VectorOperators.MUL, c8).lanewise(VectorOperators.ADD, v46.lanewise(VectorOperators.MUL, c24)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v82 = v60.lanewise(VectorOperators.ADD, v61).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v83 = v60.lanewise(VectorOperators.SUB, v61).lanewise(VectorOperators.MUL, c16).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v84 = v62.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.ADD, v63.lanewise(VectorOperators.MUL, c8)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v85 = v63.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.SUB, v62.lanewise(VectorOperators.MUL, c8)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v86 = v36.lanewise(VectorOperators.ADD, v64);
        var v87 = v36.lanewise(VectorOperators.SUB, v64);
        INTS_8.cast(v74).intoArray(m, off + 2 * stride);
        INTS_8.cast(v75).intoArray(m, off + 3 * stride);
        INTS_8.cast(v76).intoArray(m, off + 4 * stride);
        INTS_8.cast(v77).intoArray(m, off + 5 * stride);
        INTS_8.cast(v78).intoArray(m, off + 6 * stride);
        INTS_8.cast(v79).intoArray(m, off + 10 * stride);
        INTS_8.cast(v80).intoArray(m, off + 11 * stride);
        INTS_8.cast(v81).intoArray(m, off + 12 * stride);
        INTS_8.cast(v82).intoArray(m, off + 13 * stride);
        INTS_8.cast(v83).intoArray(m, off + 14 * stride);
        INTS_8.cast(v84).intoArray(m, off + 19 * stride);
        INTS_8.cast(v85).intoArray(m, off + 20 * stride);
        INTS_8.cast(v86).intoArray(m, off + 21 * stride);
        INTS_8.cast(v87).intoArray(m, off + 22 * stride);
    }

    /**
     * Computes part 7 of the operations of {@link #fdct32Columns} in place, on eight columns: the
     * values the later parts need stay in rows of the block, in rows the earlier parts free.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct32Part7(int[] m, int off, int stride) {
        var round = INTS_8.cast(DCT_ROUND_8);
        var c24 = INTS_8.cast(IntVector.broadcast(I256, 6270));
        var c8 = INTS_8.cast(IntVector.broadcast(I256, 15137));
        var m24 = INTS_8.cast(IntVector.broadcast(I256, -6270));
        var m8 = INTS_8.cast(IntVector.broadcast(I256, -15137));
        var v65 = INTS_8.cast(IntVector.fromArray(I256, m, off + 15 * stride));
        var v39 = INTS_8.cast(IntVector.fromArray(I256, m, off + 9 * stride));
        var v67 = INTS_8.cast(IntVector.fromArray(I256, m, off + 24 * stride));
        var v72 = INTS_8.cast(IntVector.fromArray(I256, m, off + 30 * stride));
        var v68 = INTS_8.cast(IntVector.fromArray(I256, m, off + 25 * stride));
        var v71 = INTS_8.cast(IntVector.fromArray(I256, m, off + 29 * stride));
        var v44 = INTS_8.cast(IntVector.fromArray(I256, m, off + 17 * stride));
        var v75 = INTS_8.cast(IntVector.fromArray(I256, m, off + 3 * stride));
        var v45 = INTS_8.cast(IntVector.fromArray(I256, m, off + 18 * stride));
        var v74 = INTS_8.cast(IntVector.fromArray(I256, m, off + 2 * stride));
        var v76 = INTS_8.cast(IntVector.fromArray(I256, m, off + 4 * stride));
        var v51 = INTS_8.cast(IntVector.fromArray(I256, m, off + 26 * stride));
        var v77 = INTS_8.cast(IntVector.fromArray(I256, m, off + 5 * stride));
        var v50 = INTS_8.cast(IntVector.fromArray(I256, m, off + 23 * stride));
        var v52 = INTS_8.cast(IntVector.fromArray(I256, m, off));
        var v79 = INTS_8.cast(IntVector.fromArray(I256, m, off + 10 * stride));
        var v53 = INTS_8.cast(IntVector.fromArray(I256, m, off + 1 * stride));
        var v78 = INTS_8.cast(IntVector.fromArray(I256, m, off + 6 * stride));
        var v88 = v39.lanewise(VectorOperators.SUB, v65);
        var v89 = v39.lanewise(VectorOperators.ADD, v65);
        var v90 = v67.lanewise(VectorOperators.MUL, m8).lanewise(VectorOperators.ADD, v72.lanewise(VectorOperators.MUL, c24)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v91 = v68.lanewise(VectorOperators.MUL, m24).lanewise(VectorOperators.ADD, v71.lanewise(VectorOperators.MUL, m8)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v92 = v71.lanewise(VectorOperators.MUL, c24).lanewise(VectorOperators.ADD, v68.lanewise(VectorOperators.MUL, m8)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v93 = v72.lanewise(VectorOperators.MUL, c8).lanewise(VectorOperators.ADD, v67.lanewise(VectorOperators.MUL, c24)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v94 = v44.lanewise(VectorOperators.ADD, v75);
        var v95 = v45.lanewise(VectorOperators.ADD, v74);
        var v96 = v45.lanewise(VectorOperators.SUB, v74);
        var v97 = v44.lanewise(VectorOperators.SUB, v75);
        var v98 = v51.lanewise(VectorOperators.SUB, v76);
        var v99 = v50.lanewise(VectorOperators.SUB, v77);
        var v100 = v50.lanewise(VectorOperators.ADD, v77);
        var v101 = v51.lanewise(VectorOperators.ADD, v76);
        var v102 = v52.lanewise(VectorOperators.ADD, v79);
        var v103 = v53.lanewise(VectorOperators.ADD, v78);
        var v104 = v53.lanewise(VectorOperators.SUB, v78);
        var v105 = v52.lanewise(VectorOperators.SUB, v79);
        INTS_8.cast(v88).intoArray(m, off);
        INTS_8.cast(v89).intoArray(m, off + 1 * stride);
        INTS_8.cast(v90).intoArray(m, off + 2 * stride);
        INTS_8.cast(v91).intoArray(m, off + 3 * stride);
        INTS_8.cast(v92).intoArray(m, off + 4 * stride);
        INTS_8.cast(v93).intoArray(m, off + 5 * stride);
        INTS_8.cast(v94).intoArray(m, off + 6 * stride);
        INTS_8.cast(v95).intoArray(m, off + 9 * stride);
        INTS_8.cast(v96).intoArray(m, off + 10 * stride);
        INTS_8.cast(v97).intoArray(m, off + 15 * stride);
        INTS_8.cast(v98).intoArray(m, off + 17 * stride);
        INTS_8.cast(v99).intoArray(m, off + 18 * stride);
        INTS_8.cast(v100).intoArray(m, off + 23 * stride);
        INTS_8.cast(v101).intoArray(m, off + 24 * stride);
        INTS_8.cast(v102).intoArray(m, off + 25 * stride);
        INTS_8.cast(v103).intoArray(m, off + 26 * stride);
        INTS_8.cast(v104).intoArray(m, off + 29 * stride);
        INTS_8.cast(v105).intoArray(m, off + 30 * stride);
    }

    /**
     * Computes part 8 of the operations of {@link #fdct32Columns} in place, on eight columns: the
     * values the later parts need stay in rows of the block, in rows the earlier parts free.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct32Part8(int[] m, int off, int stride) {
        var round = INTS_8.cast(DCT_ROUND_8);
        var c28 = INTS_8.cast(IntVector.broadcast(I256, 3196));
        var c20 = INTS_8.cast(IntVector.broadcast(I256, 9102));
        var c12 = INTS_8.cast(IntVector.broadcast(I256, 13623));
        var c4 = INTS_8.cast(IntVector.broadcast(I256, 16069));
        var m20 = INTS_8.cast(IntVector.broadcast(I256, -9102));
        var m4 = INTS_8.cast(IntVector.broadcast(I256, -16069));
        var v80 = INTS_8.cast(IntVector.fromArray(I256, m, off + 11 * stride));
        var v59 = INTS_8.cast(IntVector.fromArray(I256, m, off + 8 * stride));
        var v81 = INTS_8.cast(IntVector.fromArray(I256, m, off + 12 * stride));
        var v58 = INTS_8.cast(IntVector.fromArray(I256, m, off + 7 * stride));
        var v86 = INTS_8.cast(IntVector.fromArray(I256, m, off + 21 * stride));
        var v89 = INTS_8.cast(IntVector.fromArray(I256, m, off + 1 * stride));
        var v87 = INTS_8.cast(IntVector.fromArray(I256, m, off + 22 * stride));
        var v88 = INTS_8.cast(IntVector.fromArray(I256, m, off));
        var v66 = INTS_8.cast(IntVector.fromArray(I256, m, off + 16 * stride));
        var v90 = INTS_8.cast(IntVector.fromArray(I256, m, off + 2 * stride));
        var v91 = INTS_8.cast(IntVector.fromArray(I256, m, off + 3 * stride));
        var v69 = INTS_8.cast(IntVector.fromArray(I256, m, off + 27 * stride));
        var v70 = INTS_8.cast(IntVector.fromArray(I256, m, off + 28 * stride));
        var v92 = INTS_8.cast(IntVector.fromArray(I256, m, off + 4 * stride));
        var v93 = INTS_8.cast(IntVector.fromArray(I256, m, off + 5 * stride));
        var v73 = INTS_8.cast(IntVector.fromArray(I256, m, off + 31 * stride));
        var v106 = v59.lanewise(VectorOperators.SUB, v80);
        var v107 = v58.lanewise(VectorOperators.SUB, v81);
        var v108 = v58.lanewise(VectorOperators.ADD, v81);
        var v109 = v59.lanewise(VectorOperators.ADD, v80);
        var v110 = v86.lanewise(VectorOperators.MUL, c28).lanewise(VectorOperators.ADD, v89.lanewise(VectorOperators.MUL, c4)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v111 = v87.lanewise(VectorOperators.MUL, c12).lanewise(VectorOperators.ADD, v88.lanewise(VectorOperators.MUL, c20)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v112 = v88.lanewise(VectorOperators.MUL, c12).lanewise(VectorOperators.ADD, v87.lanewise(VectorOperators.MUL, m20)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v113 = v89.lanewise(VectorOperators.MUL, c28).lanewise(VectorOperators.ADD, v86.lanewise(VectorOperators.MUL, m4)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v114 = v66.lanewise(VectorOperators.ADD, v90);
        var v115 = v66.lanewise(VectorOperators.SUB, v90);
        var v116 = v69.lanewise(VectorOperators.SUB, v91);
        var v117 = v69.lanewise(VectorOperators.ADD, v91);
        var v118 = v70.lanewise(VectorOperators.ADD, v92);
        var v119 = v70.lanewise(VectorOperators.SUB, v92);
        var v120 = v73.lanewise(VectorOperators.SUB, v93);
        var v121 = v73.lanewise(VectorOperators.ADD, v93);
        INTS_8.cast(v106).intoArray(m, off);
        INTS_8.cast(v107).intoArray(m, off + 1 * stride);
        INTS_8.cast(v108).intoArray(m, off + 2 * stride);
        INTS_8.cast(v109).intoArray(m, off + 3 * stride);
        INTS_8.cast(v110).intoArray(m, off + 4 * stride);
        INTS_8.cast(v111).intoArray(m, off + 5 * stride);
        INTS_8.cast(v112).intoArray(m, off + 12 * stride);
        INTS_8.cast(v113).intoArray(m, off + 28 * stride);
        INTS_8.cast(v114).intoArray(m, off + 7 * stride);
        INTS_8.cast(v115).intoArray(m, off + 8 * stride);
        INTS_8.cast(v116).intoArray(m, off + 11 * stride);
        INTS_8.cast(v117).intoArray(m, off + 16 * stride);
        INTS_8.cast(v118).intoArray(m, off + 21 * stride);
        INTS_8.cast(v119).intoArray(m, off + 22 * stride);
        INTS_8.cast(v120).intoArray(m, off + 27 * stride);
        INTS_8.cast(v121).intoArray(m, off + 31 * stride);
    }

    /**
     * Computes part 9 of the operations of {@link #fdct32Columns} in place, on eight columns: the
     * values the later parts need stay in rows of the block, in rows the earlier parts free.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct32Part9(int[] m, int off, int stride) {
        var round = INTS_8.cast(DCT_ROUND_8);
        var c28 = INTS_8.cast(IntVector.broadcast(I256, 3196));
        var c20 = INTS_8.cast(IntVector.broadcast(I256, 9102));
        var c12 = INTS_8.cast(IntVector.broadcast(I256, 13623));
        var c4 = INTS_8.cast(IntVector.broadcast(I256, 16069));
        var m28 = INTS_8.cast(IntVector.broadcast(I256, -3196));
        var m20 = INTS_8.cast(IntVector.broadcast(I256, -9102));
        var m12 = INTS_8.cast(IntVector.broadcast(I256, -13623));
        var m4 = INTS_8.cast(IntVector.broadcast(I256, -16069));
        var v95 = INTS_8.cast(IntVector.fromArray(I256, m, off + 9 * stride));
        var v108 = INTS_8.cast(IntVector.fromArray(I256, m, off + 2 * stride));
        var v96 = INTS_8.cast(IntVector.fromArray(I256, m, off + 10 * stride));
        var v107 = INTS_8.cast(IntVector.fromArray(I256, m, off + 1 * stride));
        var v99 = INTS_8.cast(IntVector.fromArray(I256, m, off + 18 * stride));
        var v104 = INTS_8.cast(IntVector.fromArray(I256, m, off + 29 * stride));
        var v100 = INTS_8.cast(IntVector.fromArray(I256, m, off + 23 * stride));
        var v103 = INTS_8.cast(IntVector.fromArray(I256, m, off + 26 * stride));
        var v122 = v95.lanewise(VectorOperators.MUL, m4).lanewise(VectorOperators.ADD, v108.lanewise(VectorOperators.MUL, c28)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v123 = v96.lanewise(VectorOperators.MUL, m28).lanewise(VectorOperators.ADD, v107.lanewise(VectorOperators.MUL, m4)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v124 = v99.lanewise(VectorOperators.MUL, m20).lanewise(VectorOperators.ADD, v104.lanewise(VectorOperators.MUL, c12)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v125 = v100.lanewise(VectorOperators.MUL, m12).lanewise(VectorOperators.ADD, v103.lanewise(VectorOperators.MUL, m20)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v126 = v103.lanewise(VectorOperators.MUL, c12).lanewise(VectorOperators.ADD, v100.lanewise(VectorOperators.MUL, m20)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v127 = v104.lanewise(VectorOperators.MUL, c20).lanewise(VectorOperators.ADD, v99.lanewise(VectorOperators.MUL, c12)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v128 = v107.lanewise(VectorOperators.MUL, c28).lanewise(VectorOperators.ADD, v96.lanewise(VectorOperators.MUL, m4)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v129 = v108.lanewise(VectorOperators.MUL, c4).lanewise(VectorOperators.ADD, v95.lanewise(VectorOperators.MUL, c28)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        INTS_8.cast(v122).intoArray(m, off + 1 * stride);
        INTS_8.cast(v123).intoArray(m, off + 2 * stride);
        INTS_8.cast(v124).intoArray(m, off + 9 * stride);
        INTS_8.cast(v125).intoArray(m, off + 10 * stride);
        INTS_8.cast(v126).intoArray(m, off + 18 * stride);
        INTS_8.cast(v127).intoArray(m, off + 23 * stride);
        INTS_8.cast(v128).intoArray(m, off + 26 * stride);
        INTS_8.cast(v129).intoArray(m, off + 29 * stride);
    }

    /**
     * Computes part 10 of the operations of {@link #fdct32Columns} in place, on eight columns: the
     * values the later parts need stay in rows of the block, in rows the earlier parts free.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct32Part10(int[] m, int off, int stride) {
        var round = INTS_8.cast(DCT_ROUND_8);
        var c30 = INTS_8.cast(IntVector.broadcast(I256, 1606));
        var c26 = INTS_8.cast(IntVector.broadcast(I256, 4756));
        var c22 = INTS_8.cast(IntVector.broadcast(I256, 7723));
        var c18 = INTS_8.cast(IntVector.broadcast(I256, 10394));
        var c14 = INTS_8.cast(IntVector.broadcast(I256, 12665));
        var c10 = INTS_8.cast(IntVector.broadcast(I256, 14449));
        var c6 = INTS_8.cast(IntVector.broadcast(I256, 15679));
        var c2 = INTS_8.cast(IntVector.broadcast(I256, 16305));
        var m26 = INTS_8.cast(IntVector.broadcast(I256, -4756));
        var m18 = INTS_8.cast(IntVector.broadcast(I256, -10394));
        var m10 = INTS_8.cast(IntVector.broadcast(I256, -14449));
        var m2 = INTS_8.cast(IntVector.broadcast(I256, -16305));
        var v114 = INTS_8.cast(IntVector.fromArray(I256, m, off + 7 * stride));
        var v121 = INTS_8.cast(IntVector.fromArray(I256, m, off + 31 * stride));
        var v115 = INTS_8.cast(IntVector.fromArray(I256, m, off + 8 * stride));
        var v120 = INTS_8.cast(IntVector.fromArray(I256, m, off + 27 * stride));
        var v116 = INTS_8.cast(IntVector.fromArray(I256, m, off + 11 * stride));
        var v119 = INTS_8.cast(IntVector.fromArray(I256, m, off + 22 * stride));
        var v117 = INTS_8.cast(IntVector.fromArray(I256, m, off + 16 * stride));
        var v118 = INTS_8.cast(IntVector.fromArray(I256, m, off + 21 * stride));
        var v94 = INTS_8.cast(IntVector.fromArray(I256, m, off + 6 * stride));
        var v122 = INTS_8.cast(IntVector.fromArray(I256, m, off + 1 * stride));
        var v123 = INTS_8.cast(IntVector.fromArray(I256, m, off + 2 * stride));
        var v97 = INTS_8.cast(IntVector.fromArray(I256, m, off + 15 * stride));
        var v98 = INTS_8.cast(IntVector.fromArray(I256, m, off + 17 * stride));
        var v124 = INTS_8.cast(IntVector.fromArray(I256, m, off + 9 * stride));
        var v130 = v114.lanewise(VectorOperators.MUL, c30).lanewise(VectorOperators.ADD, v121.lanewise(VectorOperators.MUL, c2)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v131 = v115.lanewise(VectorOperators.MUL, c14).lanewise(VectorOperators.ADD, v120.lanewise(VectorOperators.MUL, c18)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v132 = v116.lanewise(VectorOperators.MUL, c22).lanewise(VectorOperators.ADD, v119.lanewise(VectorOperators.MUL, c10)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v133 = v117.lanewise(VectorOperators.MUL, c6).lanewise(VectorOperators.ADD, v118.lanewise(VectorOperators.MUL, c26)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v134 = v118.lanewise(VectorOperators.MUL, c6).lanewise(VectorOperators.ADD, v117.lanewise(VectorOperators.MUL, m26)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v135 = v119.lanewise(VectorOperators.MUL, c22).lanewise(VectorOperators.ADD, v116.lanewise(VectorOperators.MUL, m10)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v136 = v120.lanewise(VectorOperators.MUL, c14).lanewise(VectorOperators.ADD, v115.lanewise(VectorOperators.MUL, m18)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v137 = v121.lanewise(VectorOperators.MUL, c30).lanewise(VectorOperators.ADD, v114.lanewise(VectorOperators.MUL, m2)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v138 = v94.lanewise(VectorOperators.ADD, v122);
        var v139 = v94.lanewise(VectorOperators.SUB, v122);
        var v140 = v97.lanewise(VectorOperators.SUB, v123);
        var v141 = v97.lanewise(VectorOperators.ADD, v123);
        var v142 = v98.lanewise(VectorOperators.ADD, v124);
        var v143 = v98.lanewise(VectorOperators.SUB, v124);
        INTS_8.cast(v130).intoArray(m, off + 2 * stride);
        INTS_8.cast(v131).intoArray(m, off + 1 * stride);
        INTS_8.cast(v132).intoArray(m, off + 7 * stride);
        INTS_8.cast(v133).intoArray(m, off + 8 * stride);
        INTS_8.cast(v134).intoArray(m, off + 6 * stride);
        INTS_8.cast(v135).intoArray(m, off + 22 * stride);
        INTS_8.cast(v136).intoArray(m, off + 9 * stride);
        INTS_8.cast(v137).intoArray(m, off + 11 * stride);
        INTS_8.cast(v138).intoArray(m, off + 15 * stride);
        INTS_8.cast(v139).intoArray(m, off + 16 * stride);
        INTS_8.cast(v140).intoArray(m, off + 17 * stride);
        INTS_8.cast(v141).intoArray(m, off + 21 * stride);
        INTS_8.cast(v142).intoArray(m, off + 27 * stride);
        INTS_8.cast(v143).intoArray(m, off + 31 * stride);
    }

    /**
     * Computes part 11 of the operations of {@link #fdct32Columns} in place, on eight columns: the
     * values the later parts need stay in rows of the block, in rows the earlier parts free.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct32Part11(int[] m, int off, int stride) {
        var v125 = INTS_8.cast(IntVector.fromArray(I256, m, off + 10 * stride));
        var v101 = INTS_8.cast(IntVector.fromArray(I256, m, off + 24 * stride));
        var v102 = INTS_8.cast(IntVector.fromArray(I256, m, off + 25 * stride));
        var v126 = INTS_8.cast(IntVector.fromArray(I256, m, off + 18 * stride));
        var v127 = INTS_8.cast(IntVector.fromArray(I256, m, off + 23 * stride));
        var v105 = INTS_8.cast(IntVector.fromArray(I256, m, off + 30 * stride));
        var v106 = INTS_8.cast(IntVector.fromArray(I256, m, off));
        var v128 = INTS_8.cast(IntVector.fromArray(I256, m, off + 26 * stride));
        var v129 = INTS_8.cast(IntVector.fromArray(I256, m, off + 29 * stride));
        var v109 = INTS_8.cast(IntVector.fromArray(I256, m, off + 3 * stride));
        var v144 = v101.lanewise(VectorOperators.SUB, v125);
        var v145 = v101.lanewise(VectorOperators.ADD, v125);
        var v146 = v102.lanewise(VectorOperators.ADD, v126);
        var v147 = v102.lanewise(VectorOperators.SUB, v126);
        var v148 = v105.lanewise(VectorOperators.SUB, v127);
        var v149 = v105.lanewise(VectorOperators.ADD, v127);
        var v150 = v106.lanewise(VectorOperators.ADD, v128);
        var v151 = v106.lanewise(VectorOperators.SUB, v128);
        var v152 = v109.lanewise(VectorOperators.SUB, v129);
        var v153 = v109.lanewise(VectorOperators.ADD, v129);
        INTS_8.cast(v144).intoArray(m, off);
        INTS_8.cast(v145).intoArray(m, off + 3 * stride);
        INTS_8.cast(v146).intoArray(m, off + 10 * stride);
        INTS_8.cast(v147).intoArray(m, off + 18 * stride);
        INTS_8.cast(v148).intoArray(m, off + 23 * stride);
        INTS_8.cast(v149).intoArray(m, off + 24 * stride);
        INTS_8.cast(v150).intoArray(m, off + 25 * stride);
        INTS_8.cast(v151).intoArray(m, off + 26 * stride);
        INTS_8.cast(v152).intoArray(m, off + 29 * stride);
        INTS_8.cast(v153).intoArray(m, off + 30 * stride);
    }

    /**
     * Computes part 12 of the operations of {@link #fdct32Columns} in place, on eight columns: the
     * values the later parts need stay in rows of the block, in rows the earlier parts free.
     *
     * @param m the block
     * @param off the index of the first value of the eight columns
     * @param stride the row stride
     */
    private static void fdct32Part12(int[] m, int off, int stride) {
        var round = INTS_8.cast(DCT_ROUND_8);
        var c31 = INTS_8.cast(IntVector.broadcast(I256, 804));
        var c29 = INTS_8.cast(IntVector.broadcast(I256, 2404));
        var c27 = INTS_8.cast(IntVector.broadcast(I256, 3981));
        var c25 = INTS_8.cast(IntVector.broadcast(I256, 5520));
        var c23 = INTS_8.cast(IntVector.broadcast(I256, 7005));
        var c21 = INTS_8.cast(IntVector.broadcast(I256, 8423));
        var c19 = INTS_8.cast(IntVector.broadcast(I256, 9760));
        var c17 = INTS_8.cast(IntVector.broadcast(I256, 11003));
        var c15 = INTS_8.cast(IntVector.broadcast(I256, 12140));
        var c13 = INTS_8.cast(IntVector.broadcast(I256, 13160));
        var c11 = INTS_8.cast(IntVector.broadcast(I256, 14053));
        var c9 = INTS_8.cast(IntVector.broadcast(I256, 14811));
        var c7 = INTS_8.cast(IntVector.broadcast(I256, 15426));
        var c5 = INTS_8.cast(IntVector.broadcast(I256, 15893));
        var c3 = INTS_8.cast(IntVector.broadcast(I256, 16207));
        var c1 = INTS_8.cast(IntVector.broadcast(I256, 16364));
        var m29 = INTS_8.cast(IntVector.broadcast(I256, -2404));
        var m25 = INTS_8.cast(IntVector.broadcast(I256, -5520));
        var m21 = INTS_8.cast(IntVector.broadcast(I256, -8423));
        var m17 = INTS_8.cast(IntVector.broadcast(I256, -11003));
        var m13 = INTS_8.cast(IntVector.broadcast(I256, -13160));
        var m9 = INTS_8.cast(IntVector.broadcast(I256, -14811));
        var m5 = INTS_8.cast(IntVector.broadcast(I256, -15893));
        var m1 = INTS_8.cast(IntVector.broadcast(I256, -16364));
        var v138 = INTS_8.cast(IntVector.fromArray(I256, m, off + 15 * stride));
        var v153 = INTS_8.cast(IntVector.fromArray(I256, m, off + 30 * stride));
        var v139 = INTS_8.cast(IntVector.fromArray(I256, m, off + 16 * stride));
        var v152 = INTS_8.cast(IntVector.fromArray(I256, m, off + 29 * stride));
        var v140 = INTS_8.cast(IntVector.fromArray(I256, m, off + 17 * stride));
        var v151 = INTS_8.cast(IntVector.fromArray(I256, m, off + 26 * stride));
        var v141 = INTS_8.cast(IntVector.fromArray(I256, m, off + 21 * stride));
        var v150 = INTS_8.cast(IntVector.fromArray(I256, m, off + 25 * stride));
        var v142 = INTS_8.cast(IntVector.fromArray(I256, m, off + 27 * stride));
        var v149 = INTS_8.cast(IntVector.fromArray(I256, m, off + 24 * stride));
        var v143 = INTS_8.cast(IntVector.fromArray(I256, m, off + 31 * stride));
        var v148 = INTS_8.cast(IntVector.fromArray(I256, m, off + 23 * stride));
        var v144 = INTS_8.cast(IntVector.fromArray(I256, m, off));
        var v147 = INTS_8.cast(IntVector.fromArray(I256, m, off + 18 * stride));
        var v145 = INTS_8.cast(IntVector.fromArray(I256, m, off + 3 * stride));
        var v146 = INTS_8.cast(IntVector.fromArray(I256, m, off + 10 * stride));
        var v154 = v138.lanewise(VectorOperators.MUL, c31).lanewise(VectorOperators.ADD, v153.lanewise(VectorOperators.MUL, c1)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v155 = v139.lanewise(VectorOperators.MUL, c15).lanewise(VectorOperators.ADD, v152.lanewise(VectorOperators.MUL, c17)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v156 = v140.lanewise(VectorOperators.MUL, c23).lanewise(VectorOperators.ADD, v151.lanewise(VectorOperators.MUL, c9)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v157 = v141.lanewise(VectorOperators.MUL, c7).lanewise(VectorOperators.ADD, v150.lanewise(VectorOperators.MUL, c25)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v158 = v142.lanewise(VectorOperators.MUL, c27).lanewise(VectorOperators.ADD, v149.lanewise(VectorOperators.MUL, c5)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v159 = v143.lanewise(VectorOperators.MUL, c11).lanewise(VectorOperators.ADD, v148.lanewise(VectorOperators.MUL, c21)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v160 = v144.lanewise(VectorOperators.MUL, c19).lanewise(VectorOperators.ADD, v147.lanewise(VectorOperators.MUL, c13)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v161 = v145.lanewise(VectorOperators.MUL, c3).lanewise(VectorOperators.ADD, v146.lanewise(VectorOperators.MUL, c29)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v162 = v146.lanewise(VectorOperators.MUL, c3).lanewise(VectorOperators.ADD, v145.lanewise(VectorOperators.MUL, m29)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v163 = v147.lanewise(VectorOperators.MUL, c19).lanewise(VectorOperators.ADD, v144.lanewise(VectorOperators.MUL, m13)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v164 = v148.lanewise(VectorOperators.MUL, c11).lanewise(VectorOperators.ADD, v143.lanewise(VectorOperators.MUL, m21)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v165 = v149.lanewise(VectorOperators.MUL, c27).lanewise(VectorOperators.ADD, v142.lanewise(VectorOperators.MUL, m5)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v166 = v150.lanewise(VectorOperators.MUL, c7).lanewise(VectorOperators.ADD, v141.lanewise(VectorOperators.MUL, m25)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v167 = v151.lanewise(VectorOperators.MUL, c23).lanewise(VectorOperators.ADD, v140.lanewise(VectorOperators.MUL, m9)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v168 = v152.lanewise(VectorOperators.MUL, c15).lanewise(VectorOperators.ADD, v139.lanewise(VectorOperators.MUL, m17)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        var v169 = v153.lanewise(VectorOperators.MUL, c31).lanewise(VectorOperators.ADD, v138.lanewise(VectorOperators.MUL, m1)).lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, DCT_SHIFT);
        INTS_8.cast(v154).intoArray(m, off);
        INTS_8.cast(v155).intoArray(m, off + 17 * stride);
        INTS_8.cast(v156).intoArray(m, off + 10 * stride);
        INTS_8.cast(v157).intoArray(m, off + 25 * stride);
        INTS_8.cast(v158).intoArray(m, off + 16 * stride);
        INTS_8.cast(v159).intoArray(m, off + 21 * stride);
        INTS_8.cast(v160).intoArray(m, off + 18 * stride);
        INTS_8.cast(v161).intoArray(m, off + 29 * stride);
        INTS_8.cast(v162).intoArray(m, off + 3 * stride);
        INTS_8.cast(v163).intoArray(m, off + 24 * stride);
        INTS_8.cast(v164).intoArray(m, off + 26 * stride);
        INTS_8.cast(v165).intoArray(m, off + 27 * stride);
        INTS_8.cast(v166).intoArray(m, off + 30 * stride);
        INTS_8.cast(v167).intoArray(m, off + 23 * stride);
        INTS_8.cast(v168).intoArray(m, off + 15 * stride);
        INTS_8.cast(v169).intoArray(m, off + 31 * stride);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation sums the 16 columns of the top and bottom halves in 16-bit
     * lanes, two rows at a time, then folds each half's lanes into its left and right
     * block sums with two lane swaps.
     */
    @Override
    void avg8x8Quad(byte[] s, int off, int stride, int[] out) {
        var top = SHORTS_16.cast(ShortVector.zero(S256));
        var bottom = SHORTS_16.cast(ShortVector.zero(S256));
        var lower = off + 8 * stride;
        for (var r = 0; r < 8; r += 2, off += 2 * stride, lower += 2 * stride) {
            var a0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, s, off))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var a1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, s, off + stride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var b0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, s, lower))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var b1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, s, lower + stride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            top = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, top);
            bottom = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, bottom);
        }
        var low = INTS_8.cast(LOW_HALVES_8);
        var t = INTS_8.cast(SHORTS_16.cast(top).reinterpretShape(I256, 0));
        var b = INTS_8.cast(SHORTS_16.cast(bottom).reinterpretShape(I256, 0));
        var tp = INTS_8.cast(t.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                t.lanewise(VectorOperators.LSHR, 16)));
        var bp = INTS_8.cast(b.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.LSHR, 16)));
        var t1 = INTS_8.cast(tp.lanewise(VectorOperators.ADD, tp.rearrange(SWAP_1)));
        var b1 = INTS_8.cast(bp.lanewise(VectorOperators.ADD, bp.rearrange(SWAP_1)));
        var t2 = INTS_8.cast(t1.lanewise(VectorOperators.ADD, t1.rearrange(SWAP_2)));
        var b2 = INTS_8.cast(b1.lanewise(VectorOperators.ADD, b1.rearrange(SWAP_2)));
        out[0] = (t2.lane(0) + 32) >> 6;
        out[1] = (t2.lane(4) + 32) >> 6;
        out[2] = (b2.lane(0) + 32) >> 6;
        out[3] = (b2.lane(4) + 32) >> 6;
    }
}
