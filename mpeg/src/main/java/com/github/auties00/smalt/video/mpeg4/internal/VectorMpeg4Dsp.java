/* Ports libjpeg-turbo (jidctint.c, jpeg_idct_islow). Copyright (C) 1991-1998, Thomas G. Lane; libjpeg-turbo
   Modifications Copyright (C) 2015, 2020, 2022, 2026, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link Mpeg4Dsp} with the Vector API, inheriting the scalar kernels where SIMD does not
 * pay off; the output equals {@link ScalarMpeg4Dsp} sample for sample.
 *
 * @implNote This implementation follows the kernel rules of
 * {@link com.github.auties00.smalt.internal.simd.Simd Simd}.
 */
final class VectorMpeg4Dsp extends ScalarMpeg4Dsp {
    /*
     * Lane layouts. The inverse DCT of a block with more than one nonzero row runs both passes in
     * 32-bit lanes, the first with one lane per row (the coefficients are stored column-major for
     * it), then an 8x8 transposition of the intermediate values in registers, then the second pass
     * with one lane per column, followed by the clipped store or the clipped addition to the
     * prediction; a block whose only nonzero row is the first runs the scalar first pass. The half
     * sample interpolation and the averaging of bidirectional predictions run in 16-bit lanes over
     * rows of 8 or 16 samples, and the quarter sample interpolation runs its 8-tap filters and the
     * final averaging in 32-bit lanes over the scalar kernel's mirror-padded copy of the block.
     */

    /**
     * Eight 32-bit lanes.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * Eight byte lanes.
     */
    private static final VectorSpecies<Byte> B64 = ByteVector.SPECIES_64;

    /**
     * Sixteen byte lanes.
     */
    private static final VectorSpecies<Byte> B128 = ByteVector.SPECIES_128;

    /**
     * Eight 16-bit lanes.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * Sixteen 16-bit lanes.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * The exact class of the vectors of {@link #I256}.
     */
    private static final Class<IntVector> INT_256 = vectorClass(I256);

    /**
     * The exact class of the vectors of {@link #B64}.
     */
    private static final Class<ByteVector> BYTE_64 = vectorClass(B64);

    /**
     * The exact class of the vectors of {@link #B128}.
     */
    private static final Class<ByteVector> BYTE_128 = vectorClass(B128);

    /**
     * The exact class of the vectors of {@link #S128}.
     */
    private static final Class<ShortVector> SHORT_128 = vectorClass(S128);

    /**
     * The exact class of the vectors of {@link #S256}.
     */
    private static final Class<ShortVector> SHORT_256 = vectorClass(S256);

    /**
     * Zero in 32-bit lanes.
     */
    private static final IntVector I_0 = IntVector.zero(I256);

    /**
     * 255 in 32-bit lanes.
     */
    private static final IntVector I_255 = IntVector.broadcast(I256, 255);

    /**
     * The rounding term of the second pass.
     */
    private static final IntVector I_ROUND = IntVector.broadcast(I256, 1 << (CONST_BITS + PASS1_BITS + 2));

    /**
     * The rounding term of the first pass.
     */
    private static final IntVector I_ROUND_FIRST = IntVector.broadcast(I256, 1 << (CONST_BITS - PASS1_BITS - 1));

    /**
     * Swaps the lane pairs {@code (j, j ^ 1)}.
     */
    private static final VectorShuffle<Integer> SWAP_1 = VectorShuffle.fromValues(I256, 1, 0, 3, 2, 5, 4, 7, 6);

    /**
     * Swaps the lane pairs {@code (j, j ^ 2)}.
     */
    private static final VectorShuffle<Integer> SWAP_2 = VectorShuffle.fromValues(I256, 2, 3, 0, 1, 6, 7, 4, 5);

    /**
     * Swaps the lane pairs {@code (j, j ^ 4)}.
     */
    private static final VectorShuffle<Integer> SWAP_4 = VectorShuffle.fromValues(I256, 4, 5, 6, 7, 0, 1, 2, 3);

    /**
     * The lanes with bit 0 set.
     */
    private static final VectorMask<Integer> ODD_1 = VectorMask.fromValues(I256, false, true, false, true, false, true, false, true);

    /**
     * The lanes with bit 1 set.
     */
    private static final VectorMask<Integer> ODD_2 = VectorMask.fromValues(I256, false, false, true, true, false, false, true, true);

    /**
     * The lanes with bit 2 set.
     */
    private static final VectorMask<Integer> ODD_4 = VectorMask.fromValues(I256, false, false, false, false, true, true, true, true);

    /**
     * {@code FIX(0.541196100)} in 32-bit lanes.
     */
    private static final IntVector C_0_541196100 = IntVector.broadcast(I256, FIX_0_541196100);

    /**
     * {@code -FIX(1.847759065)} in 32-bit lanes.
     */
    private static final IntVector C_M1_847759065 = IntVector.broadcast(I256, -FIX_1_847759065);

    /**
     * {@code FIX(0.765366865)} in 32-bit lanes.
     */
    private static final IntVector C_0_765366865 = IntVector.broadcast(I256, FIX_0_765366865);

    /**
     * {@code FIX(1.175875602)} in 32-bit lanes.
     */
    private static final IntVector C_1_175875602 = IntVector.broadcast(I256, FIX_1_175875602);

    /**
     * {@code FIX(0.298631336)} in 32-bit lanes.
     */
    private static final IntVector C_0_298631336 = IntVector.broadcast(I256, FIX_0_298631336);

    /**
     * {@code FIX(2.053119869)} in 32-bit lanes.
     */
    private static final IntVector C_2_053119869 = IntVector.broadcast(I256, FIX_2_053119869);

    /**
     * {@code FIX(3.072711026)} in 32-bit lanes.
     */
    private static final IntVector C_3_072711026 = IntVector.broadcast(I256, FIX_3_072711026);

    /**
     * {@code FIX(1.501321110)} in 32-bit lanes.
     */
    private static final IntVector C_1_501321110 = IntVector.broadcast(I256, FIX_1_501321110);

    /**
     * {@code -FIX(0.899976223)} in 32-bit lanes.
     */
    private static final IntVector C_M0_899976223 = IntVector.broadcast(I256, -FIX_0_899976223);

    /**
     * {@code -FIX(2.562915447)} in 32-bit lanes.
     */
    private static final IntVector C_M2_562915447 = IntVector.broadcast(I256, -FIX_2_562915447);

    /**
     * {@code -FIX(1.961570560)} in 32-bit lanes.
     */
    private static final IntVector C_M1_961570560 = IntVector.broadcast(I256, -FIX_1_961570560);

    /**
     * {@code -FIX(0.390180644)} in 32-bit lanes.
     */
    private static final IntVector C_M0_390180644 = IntVector.broadcast(I256, -FIX_0_390180644);

    /**
     * One in 32-bit lanes.
     */
    private static final IntVector I_1 = IntVector.broadcast(I256, 1);

    /**
     * Three in 32-bit lanes.
     */
    private static final IntVector I_3 = IntVector.broadcast(I256, 3);

    /**
     * Six in 32-bit lanes.
     */
    private static final IntVector I_6 = IntVector.broadcast(I256, 6);

    /**
     * Fifteen in 32-bit lanes, the rounding term of the quarter sample filter with rounding control 1.
     */
    private static final IntVector I_15 = IntVector.broadcast(I256, 15);

    /**
     * Sixteen in 32-bit lanes, the rounding term of the quarter sample filter with rounding control 0.
     */
    private static final IntVector I_16 = IntVector.broadcast(I256, 16);

    /**
     * Twenty in 32-bit lanes.
     */
    private static final IntVector I_20 = IntVector.broadcast(I256, 20);



    /**
     * Zero in eight 16-bit lanes.
     */
    private static final ShortVector S128_0 = ShortVector.zero(S128);

    /**
     * Zero in sixteen 16-bit lanes.
     */
    private static final ShortVector S256_0 = ShortVector.zero(S256);

    /**
     * One in eight 16-bit lanes.
     */
    private static final ShortVector S128_1 = ShortVector.broadcast(S128, (short) 1);

    /**
     * Two in eight 16-bit lanes.
     */
    private static final ShortVector S128_2 = ShortVector.broadcast(S128, (short) 2);

    /**
     * One in sixteen 16-bit lanes.
     */
    private static final ShortVector S256_1 = ShortVector.broadcast(S256, (short) 1);

    /**
     * Two in sixteen 16-bit lanes.
     */
    private static final ShortVector S256_2 = ShortVector.broadcast(S256, (short) 2);

    /**
     * Constructs the kernels.
     */
    private VectorMpeg4Dsp() {
    }

    /**
     * {@return a new kernel set}
     */
    static Mpeg4Dsp create() {
        return new VectorMpeg4Dsp();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species a vector species
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void idctPut(short[] coefficients, int offset, byte[] dst, int dstOffset, int stride, int[] work) {
        var mask = firstPass(coefficients, offset, work);
        if (mask > 1) {
            secondPass(work, dst, dstOffset, stride, false);
            return;
        }
        if (mask == 1) {
            putFirstRow(work, dst, dstOffset, stride);
        } else {
            for (var row = 0; row < 8; row++) {
                BYTE_64.cast(ByteVector.zero(B64)).intoArray(dst, dstOffset + row * stride);
            }
        }
    }

    @Override
    public void idctAdd(short[] coefficients, int offset, byte[] dst, int dstOffset, int stride, int[] work) {
        var mask = firstPass(coefficients, offset, work);
        if (mask > 1) {
            secondPass(work, dst, dstOffset, stride, true);
        } else if (mask == 1) {
            addFirstRow(work, dst, dstOffset, stride);
        }
    }

    /**
     * Stores a block whose first pass left only the first row nonzero: all eight rows equal the
     * second pass of that row, clamped.
     *
     * @param work the first pass values
     * @param dst the destination plane
     * @param dstOffset the index of the top-left sample
     * @param stride the row stride
     */
    private static void putFirstRow(int[] work, byte[] dst, int dstOffset, int stride) {
        var value = INT_256.cast(IntVector.fromArray(I256, work, 0)).lanewise(VectorOperators.LSHL, CONST_BITS)
                .lanewise(VectorOperators.ADD, I_ROUND).lanewise(VectorOperators.ASHR, CONST_BITS + PASS1_BITS + 3)
                .lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255);
        var bytes = BYTE_64.cast(value.convertShape(VectorOperators.I2B, B64, 0));
        bytes.intoArray(dst, dstOffset);
        bytes.intoArray(dst, dstOffset + stride);
        bytes.intoArray(dst, dstOffset + 2 * stride);
        bytes.intoArray(dst, dstOffset + 3 * stride);
        bytes.intoArray(dst, dstOffset + 4 * stride);
        bytes.intoArray(dst, dstOffset + 5 * stride);
        bytes.intoArray(dst, dstOffset + 6 * stride);
        bytes.intoArray(dst, dstOffset + 7 * stride);
    }

    /**
     * Adds a block whose first pass left only the first row nonzero to the prediction.
     *
     * @param work the first pass values
     * @param dst the plane holding the prediction
     * @param dstOffset the index of the top-left sample
     * @param stride the row stride
     */
    private static void addFirstRow(int[] work, byte[] dst, int dstOffset, int stride) {
        var delta = INT_256.cast(IntVector.fromArray(I256, work, 0)).lanewise(VectorOperators.LSHL, CONST_BITS)
                .lanewise(VectorOperators.ADD, I_ROUND).lanewise(VectorOperators.ASHR, CONST_BITS + PASS1_BITS + 3);
        for (var row = 0; row < 8; row++) {
            var out = dstOffset + row * stride;
            var prediction = INT_256.cast(BYTE_64.cast(ByteVector.fromArray(B64, dst, out))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
            var value = prediction.lanewise(VectorOperators.ADD, INT_256.cast(delta))
                    .lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255);
            BYTE_64.cast(value.convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, out);
        }
    }

    /**
     * Runs the first pass of the inverse DCT in 32-bit lanes when more than the first row is
     * nonzero.
     *
     * <p>The pass has one lane per row of coefficients, reading the column-major block with one
     * vector load per column. Its results are stored as eight vectors, one per output column,
     * that {@link #secondPass} transposes. A block with at most the first row nonzero takes the
     * scalar first pass and leaves its intermediate values for the first-row kernels.
     *
     * @param c the coefficients in column-major order; cleared on return when more than the
     * first row is nonzero
     * @param offset the index of the first coefficient
     * @param w receives the intermediate values: the vectors of {@link #secondPass} when the
     * result is greater than 1, the first pass of the scalar twin otherwise
     * @return the bit set of the nonzero rows
     */
    private static int firstPass(short[] c, int offset, int[] w) {
        var v0 = SHORT_128.cast(ShortVector.fromArray(S128, c, offset));
        var v1 = SHORT_128.cast(ShortVector.fromArray(S128, c, offset + 8));
        var v2 = SHORT_128.cast(ShortVector.fromArray(S128, c, offset + 16));
        var v3 = SHORT_128.cast(ShortVector.fromArray(S128, c, offset + 24));
        var v4 = SHORT_128.cast(ShortVector.fromArray(S128, c, offset + 32));
        var v5 = SHORT_128.cast(ShortVector.fromArray(S128, c, offset + 40));
        var v6 = SHORT_128.cast(ShortVector.fromArray(S128, c, offset + 48));
        var v7 = SHORT_128.cast(ShortVector.fromArray(S128, c, offset + 56));
        var nonzero = (int) v0.lanewise(VectorOperators.OR, v1).lanewise(VectorOperators.OR, v2)
                .lanewise(VectorOperators.OR, v3).lanewise(VectorOperators.OR, v4).lanewise(VectorOperators.OR, v5)
                .lanewise(VectorOperators.OR, v6).lanewise(VectorOperators.OR, v7)
                .compare(VectorOperators.NE, S128_0).toLong();
        if (nonzero <= 1) {
            return rows(c, offset, w);
        }
        SHORT_256.cast(S256_0).intoArray(c, offset);
        SHORT_256.cast(S256_0).intoArray(c, offset + 16);
        SHORT_256.cast(S256_0).intoArray(c, offset + 32);
        SHORT_256.cast(S256_0).intoArray(c, offset + 48);
        var d0 = INT_256.cast(v0.convertShape(VectorOperators.S2I, I256, 0));
        var d1 = INT_256.cast(v1.convertShape(VectorOperators.S2I, I256, 0));
        var d2 = INT_256.cast(v2.convertShape(VectorOperators.S2I, I256, 0));
        var d3 = INT_256.cast(v3.convertShape(VectorOperators.S2I, I256, 0));
        var d4 = INT_256.cast(v4.convertShape(VectorOperators.S2I, I256, 0));
        var d5 = INT_256.cast(v5.convertShape(VectorOperators.S2I, I256, 0));
        var d6 = INT_256.cast(v6.convertShape(VectorOperators.S2I, I256, 0));
        var d7 = INT_256.cast(v7.convertShape(VectorOperators.S2I, I256, 0));
        var z1 = d2.lanewise(VectorOperators.ADD, d6).lanewise(VectorOperators.MUL, C_0_541196100);
        var tmp2 = z1.lanewise(VectorOperators.ADD, d6.lanewise(VectorOperators.MUL, C_M1_847759065));
        var tmp3 = z1.lanewise(VectorOperators.ADD, d2.lanewise(VectorOperators.MUL, C_0_765366865));
        var tmp0 = d0.lanewise(VectorOperators.ADD, d4).lanewise(VectorOperators.LSHL, CONST_BITS);
        var tmp1 = d0.lanewise(VectorOperators.SUB, d4).lanewise(VectorOperators.LSHL, CONST_BITS);
        var tmp10 = tmp0.lanewise(VectorOperators.ADD, tmp3).lanewise(VectorOperators.ADD, I_ROUND_FIRST);
        var tmp13 = tmp0.lanewise(VectorOperators.SUB, tmp3).lanewise(VectorOperators.ADD, I_ROUND_FIRST);
        var tmp11 = tmp1.lanewise(VectorOperators.ADD, tmp2).lanewise(VectorOperators.ADD, I_ROUND_FIRST);
        var tmp12 = tmp1.lanewise(VectorOperators.SUB, tmp2).lanewise(VectorOperators.ADD, I_ROUND_FIRST);
        var z3 = d7.lanewise(VectorOperators.ADD, d3);
        var z4 = d5.lanewise(VectorOperators.ADD, d1);
        var z5 = z3.lanewise(VectorOperators.ADD, z4).lanewise(VectorOperators.MUL, C_1_175875602);
        var o1 = d7.lanewise(VectorOperators.ADD, d1).lanewise(VectorOperators.MUL, C_M0_899976223);
        var o2 = d5.lanewise(VectorOperators.ADD, d3).lanewise(VectorOperators.MUL, C_M2_562915447);
        z3 = z3.lanewise(VectorOperators.MUL, C_M1_961570560).lanewise(VectorOperators.ADD, z5);
        z4 = z4.lanewise(VectorOperators.MUL, C_M0_390180644).lanewise(VectorOperators.ADD, z5);
        var odd0 = d7.lanewise(VectorOperators.MUL, C_0_298631336).lanewise(VectorOperators.ADD, o1)
                .lanewise(VectorOperators.ADD, z3);
        var odd1 = d5.lanewise(VectorOperators.MUL, C_2_053119869).lanewise(VectorOperators.ADD, o2)
                .lanewise(VectorOperators.ADD, z4);
        var odd2 = d3.lanewise(VectorOperators.MUL, C_3_072711026).lanewise(VectorOperators.ADD, o2)
                .lanewise(VectorOperators.ADD, z3);
        var odd3 = d1.lanewise(VectorOperators.MUL, C_1_501321110).lanewise(VectorOperators.ADD, o1)
                .lanewise(VectorOperators.ADD, z4);
        var first = CONST_BITS - PASS1_BITS;
        var x0 = tmp10.lanewise(VectorOperators.ADD, odd3).lanewise(VectorOperators.ASHR, first);
        var x7 = tmp10.lanewise(VectorOperators.SUB, odd3).lanewise(VectorOperators.ASHR, first);
        var x1 = tmp11.lanewise(VectorOperators.ADD, odd2).lanewise(VectorOperators.ASHR, first);
        var x6 = tmp11.lanewise(VectorOperators.SUB, odd2).lanewise(VectorOperators.ASHR, first);
        var x2 = tmp12.lanewise(VectorOperators.ADD, odd1).lanewise(VectorOperators.ASHR, first);
        var x5 = tmp12.lanewise(VectorOperators.SUB, odd1).lanewise(VectorOperators.ASHR, first);
        var x3 = tmp13.lanewise(VectorOperators.ADD, odd0).lanewise(VectorOperators.ASHR, first);
        var x4 = tmp13.lanewise(VectorOperators.SUB, odd0).lanewise(VectorOperators.ASHR, first);
        var a0 = x0.blend(x4.rearrange(SWAP_4), ODD_4);
        var a4 = x0.rearrange(SWAP_4).blend(x4, ODD_4);
        var a1 = x1.blend(x5.rearrange(SWAP_4), ODD_4);
        var a5 = x1.rearrange(SWAP_4).blend(x5, ODD_4);
        var a2 = x2.blend(x6.rearrange(SWAP_4), ODD_4);
        var a6 = x2.rearrange(SWAP_4).blend(x6, ODD_4);
        var a3 = x3.blend(x7.rearrange(SWAP_4), ODD_4);
        var a7 = x3.rearrange(SWAP_4).blend(x7, ODD_4);
        var b0 = a0.blend(a2.rearrange(SWAP_2), ODD_2);
        var b2 = a0.rearrange(SWAP_2).blend(a2, ODD_2);
        var b1 = a1.blend(a3.rearrange(SWAP_2), ODD_2);
        var b3 = a1.rearrange(SWAP_2).blend(a3, ODD_2);
        var b4 = a4.blend(a6.rearrange(SWAP_2), ODD_2);
        var b6 = a4.rearrange(SWAP_2).blend(a6, ODD_2);
        var b5 = a5.blend(a7.rearrange(SWAP_2), ODD_2);
        var b7 = a5.rearrange(SWAP_2).blend(a7, ODD_2);
        d0 = b0.blend(b1.rearrange(SWAP_1), ODD_1);
        d1 = b0.rearrange(SWAP_1).blend(b1, ODD_1);
        d2 = b2.blend(b3.rearrange(SWAP_1), ODD_1);
        d3 = b2.rearrange(SWAP_1).blend(b3, ODD_1);
        d4 = b4.blend(b5.rearrange(SWAP_1), ODD_1);
        d5 = b4.rearrange(SWAP_1).blend(b5, ODD_1);
        d6 = b6.blend(b7.rearrange(SWAP_1), ODD_1);
        d7 = b6.rearrange(SWAP_1).blend(b7, ODD_1);
        d0.intoArray(w, 0);
        d1.intoArray(w, 8);
        d2.intoArray(w, 16);
        d3.intoArray(w, 24);
        d4.intoArray(w, 32);
        d5.intoArray(w, 40);
        d6.intoArray(w, 48);
        d7.intoArray(w, 56);
        return nonzero;
    }

    /**
     * Runs the second pass of the inverse DCT from the vectors {@link #firstPass} stored, after an
     * 8x8 transposition in registers that gives the pass one lane per column, and stores the
     * samples clipped to 0..255 into a plane or adds them to the prediction already there.
     *
     * @param w the intermediate values of {@link #firstPass}
     * @param dst the plane
     * @param dstOffset the index of the block's top-left sample
     * @param stride the row stride of the plane
     * @param add whether the samples are added to the plane's content
     */
    private static void secondPass(int[] w, byte[] dst, int dstOffset, int stride, boolean add) {
        var d0 = INT_256.cast(IntVector.fromArray(I256, w, 0));
        var d1 = INT_256.cast(IntVector.fromArray(I256, w, 8));
        var d2 = INT_256.cast(IntVector.fromArray(I256, w, 16));
        var d3 = INT_256.cast(IntVector.fromArray(I256, w, 24));
        var d4 = INT_256.cast(IntVector.fromArray(I256, w, 32));
        var d5 = INT_256.cast(IntVector.fromArray(I256, w, 40));
        var d6 = INT_256.cast(IntVector.fromArray(I256, w, 48));
        var d7 = INT_256.cast(IntVector.fromArray(I256, w, 56));
        var z1 = d2.lanewise(VectorOperators.ADD, d6).lanewise(VectorOperators.MUL, C_0_541196100);
        var tmp2 = z1.lanewise(VectorOperators.ADD, d6.lanewise(VectorOperators.MUL, C_M1_847759065));
        var tmp3 = z1.lanewise(VectorOperators.ADD, d2.lanewise(VectorOperators.MUL, C_0_765366865));
        var tmp0 = d0.lanewise(VectorOperators.ADD, d4).lanewise(VectorOperators.LSHL, CONST_BITS);
        var tmp1 = d0.lanewise(VectorOperators.SUB, d4).lanewise(VectorOperators.LSHL, CONST_BITS);
        var tmp10 = tmp0.lanewise(VectorOperators.ADD, tmp3).lanewise(VectorOperators.ADD, I_ROUND);
        var tmp13 = tmp0.lanewise(VectorOperators.SUB, tmp3).lanewise(VectorOperators.ADD, I_ROUND);
        var tmp11 = tmp1.lanewise(VectorOperators.ADD, tmp2).lanewise(VectorOperators.ADD, I_ROUND);
        var tmp12 = tmp1.lanewise(VectorOperators.SUB, tmp2).lanewise(VectorOperators.ADD, I_ROUND);
        var z3 = d7.lanewise(VectorOperators.ADD, d3);
        var z4 = d5.lanewise(VectorOperators.ADD, d1);
        var z5 = z3.lanewise(VectorOperators.ADD, z4).lanewise(VectorOperators.MUL, C_1_175875602);
        var o1 = d7.lanewise(VectorOperators.ADD, d1).lanewise(VectorOperators.MUL, C_M0_899976223);
        var o2 = d5.lanewise(VectorOperators.ADD, d3).lanewise(VectorOperators.MUL, C_M2_562915447);
        z3 = z3.lanewise(VectorOperators.MUL, C_M1_961570560).lanewise(VectorOperators.ADD, z5);
        z4 = z4.lanewise(VectorOperators.MUL, C_M0_390180644).lanewise(VectorOperators.ADD, z5);
        var odd0 = d7.lanewise(VectorOperators.MUL, C_0_298631336).lanewise(VectorOperators.ADD, o1)
                .lanewise(VectorOperators.ADD, z3);
        var odd1 = d5.lanewise(VectorOperators.MUL, C_2_053119869).lanewise(VectorOperators.ADD, o2)
                .lanewise(VectorOperators.ADD, z4);
        var odd2 = d3.lanewise(VectorOperators.MUL, C_3_072711026).lanewise(VectorOperators.ADD, o2)
                .lanewise(VectorOperators.ADD, z3);
        var odd3 = d1.lanewise(VectorOperators.MUL, C_1_501321110).lanewise(VectorOperators.ADD, o1)
                .lanewise(VectorOperators.ADD, z4);
        var shift = CONST_BITS + PASS1_BITS + 3;
        var y0 = tmp10.lanewise(VectorOperators.ADD, odd3).lanewise(VectorOperators.ASHR, shift);
        var y1 = tmp11.lanewise(VectorOperators.ADD, odd2).lanewise(VectorOperators.ASHR, shift);
        var y2 = tmp12.lanewise(VectorOperators.ADD, odd1).lanewise(VectorOperators.ASHR, shift);
        var y3 = tmp13.lanewise(VectorOperators.ADD, odd0).lanewise(VectorOperators.ASHR, shift);
        var y4 = tmp13.lanewise(VectorOperators.SUB, odd0).lanewise(VectorOperators.ASHR, shift);
        var y5 = tmp12.lanewise(VectorOperators.SUB, odd1).lanewise(VectorOperators.ASHR, shift);
        var y6 = tmp11.lanewise(VectorOperators.SUB, odd2).lanewise(VectorOperators.ASHR, shift);
        var y7 = tmp10.lanewise(VectorOperators.SUB, odd3).lanewise(VectorOperators.ASHR, shift);
        if (add) {
            var out0 = dstOffset;
            BYTE_64.cast(INT_256.cast(BYTE_64.cast(ByteVector.fromArray(B64, dst, out0))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0)).lanewise(VectorOperators.ADD, y0)
                    .lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, out0);
            var out1 = dstOffset + 1 * stride;
            BYTE_64.cast(INT_256.cast(BYTE_64.cast(ByteVector.fromArray(B64, dst, out1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0)).lanewise(VectorOperators.ADD, y1)
                    .lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, out1);
            var out2 = dstOffset + 2 * stride;
            BYTE_64.cast(INT_256.cast(BYTE_64.cast(ByteVector.fromArray(B64, dst, out2))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0)).lanewise(VectorOperators.ADD, y2)
                    .lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, out2);
            var out3 = dstOffset + 3 * stride;
            BYTE_64.cast(INT_256.cast(BYTE_64.cast(ByteVector.fromArray(B64, dst, out3))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0)).lanewise(VectorOperators.ADD, y3)
                    .lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, out3);
            var out4 = dstOffset + 4 * stride;
            BYTE_64.cast(INT_256.cast(BYTE_64.cast(ByteVector.fromArray(B64, dst, out4))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0)).lanewise(VectorOperators.ADD, y4)
                    .lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, out4);
            var out5 = dstOffset + 5 * stride;
            BYTE_64.cast(INT_256.cast(BYTE_64.cast(ByteVector.fromArray(B64, dst, out5))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0)).lanewise(VectorOperators.ADD, y5)
                    .lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, out5);
            var out6 = dstOffset + 6 * stride;
            BYTE_64.cast(INT_256.cast(BYTE_64.cast(ByteVector.fromArray(B64, dst, out6))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0)).lanewise(VectorOperators.ADD, y6)
                    .lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, out6);
            var out7 = dstOffset + 7 * stride;
            BYTE_64.cast(INT_256.cast(BYTE_64.cast(ByteVector.fromArray(B64, dst, out7))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0)).lanewise(VectorOperators.ADD, y7)
                    .lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, out7);
        } else {
            BYTE_64.cast(y0.lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset);
            BYTE_64.cast(y1.lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + 1 * stride);
            BYTE_64.cast(y2.lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + 2 * stride);
            BYTE_64.cast(y3.lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + 3 * stride);
            BYTE_64.cast(y4.lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + 4 * stride);
            BYTE_64.cast(y5.lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + 5 * stride);
            BYTE_64.cast(y6.lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + 6 * stride);
            BYTE_64.cast(y7.lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + 7 * stride);
        }
    }

    @Override
    public void halfPel(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                        int width, int height, int halfX, int halfY, int rounding) {
        if ((halfX | halfY) == 0 && (width == 8 || width == 16)) {
            vectorCopy(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height);
            return;
        }
        if ((halfX | halfY) == 0 || (width != 8 && width != 16)) {
            super.halfPel(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, halfX, halfY, rounding);
            return;
        }
        var dx = halfX;
        var dy = halfY * srcStride;
        if (width == 16) {
            if ((halfX & halfY) != 0) {
                halfPel16Both(src, srcOffset, srcStride, dst, dstOffset, dstStride, height, rounding);
            } else {
                halfPel16Line(src, srcOffset, srcStride, dx + dy, dst, dstOffset, dstStride, height, rounding);
            }
        } else if ((halfX & halfY) != 0) {
            halfPel8Both(src, srcOffset, srcStride, dst, dstOffset, dstStride, height, rounding);
        } else {
            halfPel8Line(src, srcOffset, srcStride, dx + dy, dst, dstOffset, dstStride, height, rounding);
        }
    }

    /**
     * Copies a block of 8 or 16 samples per row, the prediction of a full sample motion vector.
     *
     * @param src the reference plane
     * @param srcOffset the index of the top-left reference sample
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the top-left destination sample
     * @param dstStride the destination row stride
     * @param width the block width, 8 or 16
     * @param height the number of rows
     */
    private static void vectorCopy(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                   int dstStride, int width, int height) {
        if (width == 16) {
            for (var row = 0; row < height; row++) {
                BYTE_128.cast(ByteVector.fromArray(B128, src, srcOffset + row * srcStride))
                        .intoArray(dst, dstOffset + row * dstStride);
            }
        } else {
            for (var row = 0; row < height; row++) {
                BYTE_64.cast(ByteVector.fromArray(B64, src, srcOffset + row * srcStride))
                        .intoArray(dst, dstOffset + row * dstStride);
            }
        }
    }

    /**
     * Interpolates rows of 16 samples halfway between each sample and its neighbor at a distance.
     *
     * @param src the reference plane
     * @param srcOffset the index of the top-left reference sample
     * @param srcStride the reference row stride
     * @param distance the index distance of the neighbor: 1 horizontally, the stride vertically
     * @param dst the destination
     * @param dstOffset the index of the top-left destination sample
     * @param dstStride the destination row stride
     * @param height the number of rows
     * @param rounding the rounding control, 0 or 1
     */
    private static void halfPel16Line(byte[] src, int srcOffset, int srcStride, int distance, byte[] dst,
                                      int dstOffset, int dstStride, int height, int rounding) {
        var add = rounding == 0 ? S256_1 : S256_0;
        for (var row = 0; row < height; row++) {
            var s = srcOffset + row * srcStride;
            var a = SHORT_256.cast(BYTE_128.cast(ByteVector.fromArray(B128, src, s))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var b = SHORT_256.cast(BYTE_128.cast(ByteVector.fromArray(B128, src, s + distance))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var value = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ADD, add)
                    .lanewise(VectorOperators.LSHR, 1);
            BYTE_128.cast(value.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, dstOffset + row * dstStride);
        }
    }

    /**
     * Interpolates rows of 16 samples at the center of each sample's 2x2 neighborhood.
     *
     * @param src the reference plane
     * @param srcOffset the index of the top-left reference sample
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the top-left destination sample
     * @param dstStride the destination row stride
     * @param height the number of rows
     * @param rounding the rounding control, 0 or 1
     */
    private static void halfPel16Both(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                      int dstStride, int height, int rounding) {
        var add = rounding == 0 ? S256_2 : S256_1;
        for (var row = 0; row < height; row++) {
            var s = srcOffset + row * srcStride;
            var a = SHORT_256.cast(BYTE_128.cast(ByteVector.fromArray(B128, src, s))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var b = SHORT_256.cast(BYTE_128.cast(ByteVector.fromArray(B128, src, s + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var c = SHORT_256.cast(BYTE_128.cast(ByteVector.fromArray(B128, src, s + srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var d = SHORT_256.cast(BYTE_128.cast(ByteVector.fromArray(B128, src, s + srcStride + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var value = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ADD, c)
                    .lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.ADD, add)
                    .lanewise(VectorOperators.LSHR, 2);
            BYTE_128.cast(value.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, dstOffset + row * dstStride);
        }
    }

    /**
     * Interpolates rows of 8 samples halfway between each sample and its neighbor at a distance.
     *
     * @param src the reference plane
     * @param srcOffset the index of the top-left reference sample
     * @param srcStride the reference row stride
     * @param distance the index distance of the neighbor: 1 horizontally, the stride vertically
     * @param dst the destination
     * @param dstOffset the index of the top-left destination sample
     * @param dstStride the destination row stride
     * @param height the number of rows
     * @param rounding the rounding control, 0 or 1
     */
    private static void halfPel8Line(byte[] src, int srcOffset, int srcStride, int distance, byte[] dst,
                                     int dstOffset, int dstStride, int height, int rounding) {
        var add = rounding == 0 ? S128_1 : S128_0;
        for (var row = 0; row < height; row++) {
            var s = srcOffset + row * srcStride;
            var a = SHORT_128.cast(BYTE_64.cast(ByteVector.fromArray(B64, src, s))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b = SHORT_128.cast(BYTE_64.cast(ByteVector.fromArray(B64, src, s + distance))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var value = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ADD, add)
                    .lanewise(VectorOperators.LSHR, 1);
            BYTE_64.cast(value.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, dstOffset + row * dstStride);
        }
    }

    /**
     * Interpolates rows of 8 samples at the center of each sample's 2x2 neighborhood.
     *
     * @param src the reference plane
     * @param srcOffset the index of the top-left reference sample
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the top-left destination sample
     * @param dstStride the destination row stride
     * @param height the number of rows
     * @param rounding the rounding control, 0 or 1
     */
    private static void halfPel8Both(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                     int dstStride, int height, int rounding) {
        var add = rounding == 0 ? S128_2 : S128_1;
        for (var row = 0; row < height; row++) {
            var s = srcOffset + row * srcStride;
            var a = SHORT_128.cast(BYTE_64.cast(ByteVector.fromArray(B64, src, s))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b = SHORT_128.cast(BYTE_64.cast(ByteVector.fromArray(B64, src, s + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var c = SHORT_128.cast(BYTE_64.cast(ByteVector.fromArray(B64, src, s + srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var d = SHORT_128.cast(BYTE_64.cast(ByteVector.fromArray(B64, src, s + srcStride + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var value = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ADD, c)
                    .lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.ADD, add)
                    .lanewise(VectorOperators.LSHR, 2);
            BYTE_64.cast(value.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, dstOffset + row * dstStride);
        }
    }

    @Override
    public void average(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                        int width, int height) {
        if (width == 16) {
            for (var row = 0; row < height; row++) {
                var s = srcOffset + row * srcStride;
                var d = dstOffset + row * dstStride;
                var a = SHORT_256.cast(BYTE_128.cast(ByteVector.fromArray(B128, src, s))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b = SHORT_256.cast(BYTE_128.cast(ByteVector.fromArray(B128, dst, d))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var value = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ADD, S256_1)
                        .lanewise(VectorOperators.LSHR, 1);
                BYTE_128.cast(value.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d);
            }
        } else if (width == 8) {
            for (var row = 0; row < height; row++) {
                var s = srcOffset + row * srcStride;
                var d = dstOffset + row * dstStride;
                var a = SHORT_128.cast(BYTE_64.cast(ByteVector.fromArray(B64, src, s))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
                var b = SHORT_128.cast(BYTE_64.cast(ByteVector.fromArray(B64, dst, d))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
                var value = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ADD, S128_1)
                        .lanewise(VectorOperators.LSHR, 1);
                BYTE_64.cast(value.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d);
            }
        } else {
            super.average(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height);
        }
    }

    @Override
    public void quarterPel(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                           int width, int height, int quarterX, int quarterY, int rounding, int[] scratch) {
        if ((quarterX | quarterY) == 0 && (width == 8 || width == 16)) {
            vectorCopy(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height);
            return;
        }
        if ((quarterX | quarterY) == 0 || (width != 8 && width != 16)) {
            super.quarterPel(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, quarterX, quarterY,
                    rounding, scratch);
            return;
        }
        var rows = height + 1;
        padReference(src, srcOffset, srcStride, width + 1, rows, scratch);
        var source = INTEGER_PLANE + 3;
        if (quarterX != 0) {
            vectorHorizontalPass(scratch, width, rows, quarterX, rounding);
            for (var k = 0; k < 3; k++) {
                System.arraycopy(scratch, HORIZONTAL_PLANE + (3 + k) * GRID_STRIDE, scratch,
                        HORIZONTAL_PLANE + (2 - k) * GRID_STRIDE, width);
                System.arraycopy(scratch, HORIZONTAL_PLANE + (2 + rows - k) * GRID_STRIDE, scratch,
                        HORIZONTAL_PLANE + (3 + rows + k) * GRID_STRIDE, width);
            }
            source = HORIZONTAL_PLANE;
        }
        vectorVerticalPass(scratch, source, dst, dstOffset, dstStride, width, height, quarterY, rounding);
    }

    /**
     * Interpolates every row of the padded reference block to the horizontal fraction into the
     * horizontal plane, eight samples per vector, as {@link ScalarMpeg4Dsp#horizontalPass} does.
     *
     * @param scratch the scratch buffer
     * @param width the number of samples per row, 8 or 16
     * @param rows the number of rows
     * @param quarterX the horizontal quarter sample fraction, from 1 to 3
     * @param rounding the rounding control, 0 or 1
     */
    private static void vectorHorizontalPass(int[] scratch, int width, int rows, int quarterX, int rounding) {
        var round = rounding == 0 ? I_16 : I_15;
        var add = rounding == 0 ? I_1 : I_0;
        for (var j = 0; j < rows; j++) {
            for (var x = 0; x < width; x += 8) {
                var k = INTEGER_PLANE + (j + 3) * GRID_STRIDE + 3 + x;
                var t0 = INT_256.cast(IntVector.fromArray(I256, scratch, k));
                var t1 = INT_256.cast(IntVector.fromArray(I256, scratch, k + 1));
                var m1 = INT_256.cast(IntVector.fromArray(I256, scratch, k - 1));
                var t2 = INT_256.cast(IntVector.fromArray(I256, scratch, k + 2));
                var m2 = INT_256.cast(IntVector.fromArray(I256, scratch, k - 2));
                var t3 = INT_256.cast(IntVector.fromArray(I256, scratch, k + 3));
                var m3 = INT_256.cast(IntVector.fromArray(I256, scratch, k - 3));
                var t4 = INT_256.cast(IntVector.fromArray(I256, scratch, k + 4));
                var sum = t0.lanewise(VectorOperators.ADD, t1).lanewise(VectorOperators.MUL, I_20)
                        .lanewise(VectorOperators.SUB, m1.lanewise(VectorOperators.ADD, t2)
                                .lanewise(VectorOperators.MUL, I_6))
                        .lanewise(VectorOperators.ADD, m2.lanewise(VectorOperators.ADD, t3)
                                .lanewise(VectorOperators.MUL, I_3))
                        .lanewise(VectorOperators.SUB, m3.lanewise(VectorOperators.ADD, t4));
                var value = sum.lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 5)
                        .lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255);
                if (quarterX != 2) {
                    value = value.lanewise(VectorOperators.ADD, quarterX == 3 ? t1 : t0)
                            .lanewise(VectorOperators.ADD, add).lanewise(VectorOperators.ASHR, 1);
                }
                value.intoArray(scratch, HORIZONTAL_PLANE + (j + 3) * GRID_STRIDE + x);
            }
        }
    }

    /**
     * Interpolates the columns of a plane padded by three mirrored rows above and below to the
     * vertical fraction into the destination, eight samples per vector, as {@link
     * ScalarMpeg4Dsp#verticalPass} does.
     *
     * @param scratch the scratch buffer
     * @param source the index of the first column of the source plane in its top padding row
     * @param dst the destination plane
     * @param dstOffset the index of the destination's top-left sample
     * @param dstStride the row stride of the destination
     * @param width the number of columns, 8 or 16
     * @param height the number of rows
     * @param quarterY the vertical quarter sample fraction, from 0 to 3
     * @param rounding the rounding control, 0 or 1
     */
    private static void vectorVerticalPass(int[] scratch, int source, byte[] dst, int dstOffset, int dstStride,
                                           int width, int height, int quarterY, int rounding) {
        var round = rounding == 0 ? I_16 : I_15;
        var add = rounding == 0 ? I_1 : I_0;
        for (var j = 0; j < height; j++) {
            for (var x = 0; x < width; x += 8) {
                var k = source + (j + 3) * GRID_STRIDE + x;
                var t0 = INT_256.cast(IntVector.fromArray(I256, scratch, k));
                var value = t0;
                if (quarterY != 0) {
                    var t1 = INT_256.cast(IntVector.fromArray(I256, scratch, k + GRID_STRIDE));
                    var m1 = INT_256.cast(IntVector.fromArray(I256, scratch, k - GRID_STRIDE));
                    var t2 = INT_256.cast(IntVector.fromArray(I256, scratch, k + 2 * GRID_STRIDE));
                    var m2 = INT_256.cast(IntVector.fromArray(I256, scratch, k - 2 * GRID_STRIDE));
                    var t3 = INT_256.cast(IntVector.fromArray(I256, scratch, k + 3 * GRID_STRIDE));
                    var m3 = INT_256.cast(IntVector.fromArray(I256, scratch, k - 3 * GRID_STRIDE));
                    var t4 = INT_256.cast(IntVector.fromArray(I256, scratch, k + 4 * GRID_STRIDE));
                    var sum = t0.lanewise(VectorOperators.ADD, t1).lanewise(VectorOperators.MUL, I_20)
                            .lanewise(VectorOperators.SUB, m1.lanewise(VectorOperators.ADD, t2)
                                    .lanewise(VectorOperators.MUL, I_6))
                            .lanewise(VectorOperators.ADD, m2.lanewise(VectorOperators.ADD, t3)
                                    .lanewise(VectorOperators.MUL, I_3))
                            .lanewise(VectorOperators.SUB, m3.lanewise(VectorOperators.ADD, t4));
                    value = sum.lanewise(VectorOperators.ADD, round).lanewise(VectorOperators.ASHR, 5)
                            .lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255);
                    if (quarterY != 2) {
                        value = value.lanewise(VectorOperators.ADD, quarterY == 3 ? t1 : t0)
                                .lanewise(VectorOperators.ADD, add).lanewise(VectorOperators.ASHR, 1);
                    }
                }
                BYTE_64.cast(value.convertShape(VectorOperators.I2B, B64, 0))
                        .intoArray(dst, dstOffset + j * dstStride + x);
            }
        }
    }
}
