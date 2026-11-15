package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.internal.simd.Simd;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Implements {@link H264Dsp} with the Vector API; the output equals {@link ScalarH264Dsp} sample for sample.
 *
 * <p>Block widths where SIMD does not pay off fall back to the scalar kernels, and so do the methods written for
 * 256-bit vectors unless {@link Simd#WIDE} holds.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd Simd};
 * vertical edge filters transpose, filter and write back in separate methods to stay under the JIT node budget, and the
 * second pass of the center half-sample filter and the inverse transforms run in 32-bit lanes because their sums exceed
 * 16 bits.
 */
public final class VectorH264Dsp extends ScalarH264Dsp {
    /**
     * Sixteen byte lanes.
     */
    private static final VectorSpecies<Byte> B128 = ByteVector.SPECIES_128;

    /**
     * Eight byte lanes.
     */
    private static final VectorSpecies<Byte> B64 = ByteVector.SPECIES_64;

    /**
     * Thirty-two byte lanes.
     */
    private static final VectorSpecies<Byte> B256 = ByteVector.SPECIES_256;

    /**
     * Sixteen short lanes.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * Eight short lanes.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * Eight int lanes.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * Four int lanes.
     */
    private static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;

    /**
     * Two int lanes.
     */
    private static final VectorSpecies<Integer> I64 = IntVector.SPECIES_64;

    /**
     * Four long lanes.
     */
    private static final VectorSpecies<Long> L256 = LongVector.SPECIES_256;

    /**
     * Two long lanes.
     */
    private static final VectorSpecies<Long> L128 = LongVector.SPECIES_128;

    /**
     * The exact class of thirty-two-byte vectors.
     */
    private static final Class<ByteVector> BYTES_32 = vectorClass(B256);

    /**
     * The exact class of sixteen-byte vectors.
     */
    private static final Class<ByteVector> BYTES_16 = vectorClass(B128);

    /**
     * The exact class of eight-byte vectors.
     */
    private static final Class<ByteVector> BYTES_8 = vectorClass(B64);

    /**
     * The exact class of sixteen-short vectors.
     */
    private static final Class<ShortVector> SHORTS_16 = vectorClass(S256);

    /**
     * The exact class of eight-short vectors.
     */
    private static final Class<ShortVector> SHORTS_8 = vectorClass(S128);

    /**
     * The exact class of eight-int vectors.
     */
    private static final Class<IntVector> INTS_8 = vectorClass(I256);

    /**
     * The exact class of four-int vectors.
     */
    private static final Class<IntVector> INTS_4 = vectorClass(I128);

    /**
     * The exact class of two-int vectors.
     */
    private static final Class<IntVector> INTS_2 = vectorClass(I64);

    /**
     * The exact class of four-long vectors.
     */
    private static final Class<LongVector> LONGS_4 = vectorClass(L256);

    /**
     * The exact class of two-long vectors.
     */
    private static final Class<LongVector> LONGS_2 = vectorClass(L128);

    /**
     * Four ints equal to {@code 0}.
     */
    private static final IntVector I128_0 = IntVector.broadcast(I128, 0);

    /**
     * Four ints equal to {@code 32}.
     */
    private static final IntVector I128_32 = IntVector.broadcast(I128, 32);

    /**
     * Four ints equal to {@code 255}.
     */
    private static final IntVector I128_255 = IntVector.broadcast(I128, 255);

    /**
     * Eight ints equal to {@code 0}.
     */
    private static final IntVector I256_0 = IntVector.broadcast(I256, 0);

    /**
     * Eight ints equal to {@code 5}.
     */
    private static final IntVector I256_5 = IntVector.broadcast(I256, 5);

    /**
     * Eight ints equal to {@code 20}.
     */
    private static final IntVector I256_20 = IntVector.broadcast(I256, 20);

    /**
     * Eight ints equal to {@code 32}.
     */
    private static final IntVector I256_32 = IntVector.broadcast(I256, 32);

    /**
     * Eight ints equal to {@code 255}.
     */
    private static final IntVector I256_255 = IntVector.broadcast(I256, 255);

    /**
     * Eight ints equal to {@code 512}.
     */
    private static final IntVector I256_512 = IntVector.broadcast(I256, 512);

    /**
     * Eight shorts equal to {@code 0}.
     */
    private static final ShortVector S128_0 = ShortVector.broadcast(S128, (short) 0);

    /**
     * Eight shorts equal to {@code 1}.
     */
    private static final ShortVector S128_1 = ShortVector.broadcast(S128, (short) 1);

    /**
     * Eight shorts equal to {@code 5}.
     */
    private static final ShortVector S128_5 = ShortVector.broadcast(S128, (short) 5);

    /**
     * Eight shorts equal to {@code 16}.
     */
    private static final ShortVector S128_16 = ShortVector.broadcast(S128, (short) 16);

    /**
     * Eight shorts equal to {@code 20}.
     */
    private static final ShortVector S128_20 = ShortVector.broadcast(S128, (short) 20);

    /**
     * Eight shorts equal to {@code 32}.
     */
    private static final ShortVector S128_32 = ShortVector.broadcast(S128, (short) 32);

    /**
     * Eight shorts equal to {@code 255}.
     */
    private static final ShortVector S128_255 = ShortVector.broadcast(S128, (short) 255);

    /**
     * Sixteen shorts equal to {@code 0}.
     */
    private static final ShortVector S256_0 = ShortVector.broadcast(S256, (short) 0);

    /**
     * Sixteen shorts equal to {@code 1}.
     */
    private static final ShortVector S256_1 = ShortVector.broadcast(S256, (short) 1);

    /**
     * Sixteen shorts equal to {@code 2}.
     */
    private static final ShortVector S256_2 = ShortVector.broadcast(S256, (short) 2);

    /**
     * Sixteen shorts equal to {@code 3}.
     */
    private static final ShortVector S256_3 = ShortVector.broadcast(S256, (short) 3);

    /**
     * Sixteen shorts equal to {@code 4}.
     */
    private static final ShortVector S256_4 = ShortVector.broadcast(S256, (short) 4);

    /**
     * Sixteen shorts equal to {@code 5}.
     */
    private static final ShortVector S256_5 = ShortVector.broadcast(S256, (short) 5);

    /**
     * Sixteen shorts equal to {@code 16}.
     */
    private static final ShortVector S256_16 = ShortVector.broadcast(S256, (short) 16);

    /**
     * Sixteen shorts equal to {@code 20}.
     */
    private static final ShortVector S256_20 = ShortVector.broadcast(S256, (short) 20);

    /**
     * Sixteen shorts equal to {@code 255}.
     */
    private static final ShortVector S256_255 = ShortVector.broadcast(S256, (short) 255);

    /**
     * Interleaves int lanes 0 and 1 of two 4-lane vectors: {@code [a0, b0, a1, b1]}.
     */
    private static final VectorShuffle<Integer> INTERLEAVE_LOW = VectorShuffle.fromValues(I128, 0, 4, 1, 5);

    /**
     * Interleaves int lanes 2 and 3 of two 4-lane vectors: {@code [a2, b2, a3, b3]}.
     */
    private static final VectorShuffle<Integer> INTERLEAVE_HIGH = VectorShuffle.fromValues(I128, 2, 6, 3, 7);

    /**
     * Concatenates the low halves of two 4-lane int vectors: {@code [a0, a1, b0, b1]}.
     */
    private static final VectorShuffle<Integer> PAIR_LOW_128 = VectorShuffle.fromValues(I128, 0, 1, 4, 5);

    /**
     * Concatenates the high halves of two 4-lane int vectors: {@code [a2, a3, b2, b3]}.
     */
    private static final VectorShuffle<Integer> PAIR_HIGH_128 = VectorShuffle.fromValues(I128, 2, 3, 6, 7);

    /**
     * Reorders the 32-bit lanes of two columns laid out {@code [a0, b0, a1, b1]} in 64-bit units
     * (each unit eight lines of one column, {@code 0} lines 0 to 7 and {@code 1} lines 8 to 15)
     * into {@code [a0, a1, b0, b1]}, one sixteen-line column per 128-bit half.
     */
    private static final VectorShuffle<Integer> COLUMN_HALVES = VectorShuffle.fromValues(I256, 0, 1, 4, 5, 2, 3, 6, 7);

    /**
     * Selects byte lanes 8 to 15 of sixteen, where a row pair holds its second row.
     */
    private static final VectorMask<Byte> SECOND_ROW = VectorMask.fromLong(B128, 0xFF00L);

    /**
     * Eight ints equal to {@code 0xFFFF}, the low 16 bits of each lane.
     */
    private static final IntVector I256_LOW16 = IntVector.broadcast(I256, 0xFFFF);

    /**
     * Eight ints equal to {@code 0xFFFF0000}, the high 16 bits of each lane.
     */
    private static final IntVector I256_HIGH16 = IntVector.broadcast(I256, 0xFFFF0000);

    /**
     * Four longs equal to {@code 0xFFFFFFFF}, the low 32 bits of each lane.
     */
    private static final LongVector L256_LOW32 = LongVector.broadcast(L256, 0xFFFFFFFFL);

    /**
     * Four longs equal to {@code 0xFFFFFFFF00000000}, the high 32 bits of each lane.
     */
    private static final LongVector L256_HIGH32 = LongVector.broadcast(L256, 0xFFFFFFFF00000000L);

    /**
     * Spreads the four packed luma {@code tC0} bytes over the sixteen lines of an edge, byte
     * {@code i} to lanes {@code 4i..4i+3}.
     */
    private static final VectorShuffle<Byte> LUMA_TC_SPREAD =
            VectorShuffle.fromValues(B128, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3);

    /**
     * Spreads the eight packed chroma {@code tC0} bytes (four of Cb, four of Cr) over the
     * sixteen lines of a two-plane chroma edge, byte {@code i} to lanes {@code 2i} and
     * {@code 2i + 1}.
     */
    private static final VectorShuffle<Byte> CHROMA_TC_SPREAD =
            VectorShuffle.fromValues(B128, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7);

    /**
     * Selects short lanes 4 to 7 of eight, the right 4x4 block of a chroma row.
     */
    private static final VectorMask<Short> RIGHT_HALF_8 = VectorMask.fromLong(S128, 0xF0L);

    /**
     * Selects short lanes 8 to 15, the Cr lines of a two-plane chroma edge.
     */
    private static final VectorMask<Short> UPPER_HALF = VectorMask.fromLong(S256, 0xFF00L);

    /**
     * Reads and writes little-endian {@code short}s in byte arrays.
     */
    private static final VarHandle SHORT_LE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Reads and writes little-endian {@code int}s in byte arrays.
     */
    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Reads and writes little-endian {@code long}s in byte arrays.
     */
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Constructs the vector kernel set.
     */
    public VectorH264Dsp() {
    }

    /**
     * Returns the exact class of a species' vectors.
     *
     * @param species the species whose vector class to return
     * @param <V> the vector type
     * @return the species' vector class
     */
    @SuppressWarnings("unchecked")
    private static <V> Class<V> vectorClass(VectorSpecies<?> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void lumaMc(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                       int width, int height, int fracX, int fracY, short[] sums, byte[] scratch) {
        if (!Simd.WIDE) {
            super.lumaMc(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, fracX, fracY, sums, scratch);
            return;
        }
        if (width == 16) {
            luma16(src, srcOffset, srcStride, dst, dstOffset, dstStride, height, fracX, fracY, sums, scratch);
        } else if (width == 8) {
            luma8(src, srcOffset, srcStride, dst, dstOffset, dstStride, height, fracX, fracY, sums, scratch);
        } else {
            super.lumaMc(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, fracX, fracY, sums, scratch);
        }
    }

    /**
     * Interpolates a luma block 16 samples wide.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     * @param fracX the horizontal quarter-sample fraction
     * @param fracY the vertical quarter-sample fraction
     * @param sums scratch for the first pass of the center filter
     * @param scratch scratch for the center half samples of the averaged positions
     */
    private static void luma16(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                               int height, int fracX, int fracY, short[] sums, byte[] scratch) {
        switch ((fracY << 2) | fracX) {
            case 0 -> copy16(src, srcOffset, srcStride, dst, dstOffset, dstStride, height);
            case 1 -> horizontalQuarter16(src, srcOffset, srcStride, dst, dstOffset, dstStride, height, 0);
            case 2 -> horizontal16(src, srcOffset, srcStride, dst, dstOffset, dstStride, height);
            case 3 -> horizontalQuarter16(src, srcOffset, srcStride, dst, dstOffset, dstStride, height, 1);
            case 4 -> verticalQuarter16(src, srcOffset, srcStride, dst, dstOffset, dstStride, height, 0);
            case 8 -> vertical16(src, srcOffset, srcStride, dst, dstOffset, dstStride, height);
            case 12 -> verticalQuarter16(src, srcOffset, srcStride, dst, dstOffset, dstStride, height, srcStride);
            case 10 -> center16(src, srcOffset, srcStride, dst, dstOffset, dstStride, height, sums);
            case 6, 14 -> {
                center16(src, srcOffset, srcStride, scratch, 0, 16, height, sums);
                horizontalAverage16(src, fracY == 1 ? srcOffset : srcOffset + srcStride, srcStride, scratch, dst, dstOffset,
                        dstStride, height);
            }
            case 9, 11 -> {
                center16(src, srcOffset, srcStride, scratch, 0, 16, height, sums);
                verticalAverage16(src, fracX == 1 ? srcOffset : srcOffset + 1, srcStride, scratch, dst, dstOffset,
                        dstStride, height);
            }
            default -> diagonal16(src, fracY == 1 ? srcOffset : srcOffset + srcStride, fracX == 1 ? srcOffset : srcOffset + 1,
                    srcStride, dst, dstOffset, dstStride, height);
        }
    }

    /**
     * Copies a block 16 samples wide at an integer position.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     */
    private static void copy16(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                               int height) {
        for (var y = 0; y < height; y++) {
            BYTES_16.cast(ByteVector.fromArray(B128, src, srcOffset + y * srcStride)).intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * Applies the horizontal half-sample filter to a block 16 samples wide.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     */
    private static void horizontal16(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                     int dstStride, int height) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s - 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s - 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var c = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var d = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var e = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var f = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 3)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, S256_5)).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, S256_20))
                    .lanewise(VectorOperators.ADD, S256_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255);
            BYTES_16.cast(value.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * Interpolates a horizontal quarter-sample position of a block 16 samples wide: the
     * horizontal half samples averaged with the integer samples of one column offset.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     * @param integer the column offset (0 or 1) of the integer samples
     */
    private static void horizontalQuarter16(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                            int dstStride, int height, int integer) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s - 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s - 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var c = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var d = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var e = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var f = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 3)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, S256_5)).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, S256_20))
                    .lanewise(VectorOperators.ADD, S256_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255);
            var sample = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + integer)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            BYTES_16.cast(value.lanewise(VectorOperators.ADD, sample).lanewise(VectorOperators.ADD, S256_1).lanewise(VectorOperators.ASHR, 1)
                    .convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * Applies the vertical half-sample filter to a block 16 samples wide, two rows per step so
     * that the rows they share are loaded once.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     */
    private static void vertical16(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                   int dstStride, int height) {
        for (var y = 0; y < height; y += 2) {
            var s = srcOffset + y * srcStride;
            var r0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s - 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s - srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r4 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r5 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 3 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r6 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 4 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var first = r0.lanewise(VectorOperators.ADD, r5).lanewise(VectorOperators.SUB, r1.lanewise(VectorOperators.ADD, r4).lanewise(VectorOperators.MUL, S256_5)).lanewise(VectorOperators.ADD, r2.lanewise(VectorOperators.ADD, r3).lanewise(VectorOperators.MUL, S256_20))
                    .lanewise(VectorOperators.ADD, S256_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255);
            var second = r1.lanewise(VectorOperators.ADD, r6).lanewise(VectorOperators.SUB, r2.lanewise(VectorOperators.ADD, r5).lanewise(VectorOperators.MUL, S256_5)).lanewise(VectorOperators.ADD, r3.lanewise(VectorOperators.ADD, r4).lanewise(VectorOperators.MUL, S256_20))
                    .lanewise(VectorOperators.ADD, S256_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255);
            var d = dstOffset + y * dstStride;
            BYTES_16.cast(first.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d);
            BYTES_16.cast(second.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d + dstStride);
        }
    }

    /**
     * Interpolates a vertical quarter-sample position of a block 16 samples wide: the vertical
     * half samples averaged with the integer samples of one row offset, two rows per step so that the
     * rows they share are loaded once.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     * @param integer the index offset (0 or {@code srcStride}) of the integer samples
     */
    private static void verticalQuarter16(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                          int dstStride, int height, int integer) {
        for (var y = 0; y < height; y += 2) {
            var s = srcOffset + y * srcStride;
            var r0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s - 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s - srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r4 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r5 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 3 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r6 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 4 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var first = r0.lanewise(VectorOperators.ADD, r5).lanewise(VectorOperators.SUB, r1.lanewise(VectorOperators.ADD, r4).lanewise(VectorOperators.MUL, S256_5)).lanewise(VectorOperators.ADD, r2.lanewise(VectorOperators.ADD, r3).lanewise(VectorOperators.MUL, S256_20))
                    .lanewise(VectorOperators.ADD, S256_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255);
            var second = r1.lanewise(VectorOperators.ADD, r6).lanewise(VectorOperators.SUB, r2.lanewise(VectorOperators.ADD, r5).lanewise(VectorOperators.MUL, S256_5)).lanewise(VectorOperators.ADD, r3.lanewise(VectorOperators.ADD, r4).lanewise(VectorOperators.MUL, S256_20))
                    .lanewise(VectorOperators.ADD, S256_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255);
            var d = dstOffset + y * dstStride;
            var upper = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + integer)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var lower = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + srcStride + integer)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            BYTES_16.cast(first.lanewise(VectorOperators.ADD, upper).lanewise(VectorOperators.ADD, S256_1).lanewise(VectorOperators.ASHR, 1)
                    .convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d);
            BYTES_16.cast(second.lanewise(VectorOperators.ADD, lower).lanewise(VectorOperators.ADD, S256_1).lanewise(VectorOperators.ASHR, 1)
                    .convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d + dstStride);
        }
    }

    /**
     * Applies the center (two-dimensional) half-sample filter to a block 16 samples wide.
     *
     * <p>The first pass filters {@code height + 5} rows horizontally into 16-bit sums (their range,
     * {@code -2550..10710}, fits); the second pass adds the sums of the rows sharing a tap weight
     * in 16 bits (the pair sums, {@code -5100..21420}, still fit) and weights and rounds them in
     * 32-bit lanes.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     * @param sums scratch for {@code (height + 5) * 16} horizontal sums
     */
    private static void center16(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                                 int height, short[] sums) {
        var first = srcOffset - 2 * srcStride;
        for (var row = 0; row < height + 5; row++) {
            var s = first + row * srcStride;
            var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s - 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s - 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var c = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var d = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var e = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var f = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 3)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, S256_5)).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, S256_20));
            value.intoArray(sums, row * 16);
        }
        for (var y = 0; y < height; y++) {
            var r = y * 16;
            for (var part = 0; part < 16; part += 8) {
                var outer = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, sums, r + part))
                        .lanewise(VectorOperators.ADD, SHORTS_8.cast(ShortVector.fromArray(S128, sums, r + 80 + part))).convertShape(VectorOperators.S2I, I256, 0));
                var inner = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, sums, r + 16 + part))
                        .lanewise(VectorOperators.ADD, SHORTS_8.cast(ShortVector.fromArray(S128, sums, r + 64 + part))).convertShape(VectorOperators.S2I, I256, 0));
                var middle = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, sums, r + 32 + part))
                        .lanewise(VectorOperators.ADD, SHORTS_8.cast(ShortVector.fromArray(S128, sums, r + 48 + part))).convertShape(VectorOperators.S2I, I256, 0));
                var value = outer.lanewise(VectorOperators.SUB, inner.lanewise(VectorOperators.MUL, I256_5)).lanewise(VectorOperators.ADD, middle.lanewise(VectorOperators.MUL, I256_20)).lanewise(VectorOperators.ADD, I256_512)
                        .lanewise(VectorOperators.ASHR, 10).lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255);
                BYTES_8.cast(value.convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + y * dstStride + part);
            }
        }
    }

    /**
     * Applies the horizontal half-sample filter to a block 16 samples wide and averages it
     * with precomputed center half samples.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the top-left corner of the filtered rows
     * @param srcStride the reference row stride
     * @param centers the center half samples, 16 per row
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     */
    private static void horizontalAverage16(byte[] src, int srcOffset, int srcStride, byte[] centers, byte[] dst,
                                            int dstOffset, int dstStride, int height) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s - 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s - 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var c = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var d = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var e = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var f = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 3)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, S256_5)).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, S256_20))
                    .lanewise(VectorOperators.ADD, S256_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255);
            var center = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, centers, y * 16)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            BYTES_16.cast(value.lanewise(VectorOperators.ADD, center).lanewise(VectorOperators.ADD, S256_1).lanewise(VectorOperators.ASHR, 1)
                    .convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * Applies the vertical half-sample filter to a block 16 samples wide and averages it with
     * precomputed center half samples.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the top-left corner of the filtered columns
     * @param srcStride the reference row stride
     * @param centers the center half samples, 16 per row
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     */
    private static void verticalAverage16(byte[] src, int srcOffset, int srcStride, byte[] centers, byte[] dst,
                                          int dstOffset, int dstStride, int height) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s - 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s - srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var c = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var d = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var e = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var f = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s + 3 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, S256_5)).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, S256_20))
                    .lanewise(VectorOperators.ADD, S256_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255);
            var center = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, centers, y * 16)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            BYTES_16.cast(value.lanewise(VectorOperators.ADD, center).lanewise(VectorOperators.ADD, S256_1).lanewise(VectorOperators.ASHR, 1)
                    .convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * Interpolates a diagonal quarter-sample position of a block 16 samples wide: the average
     * of a horizontal and a vertical half-sample filter, computed together per row.
     *
     * @param src the reference plane
     * @param rowOffset the index of the integer sample at the top-left corner of the horizontally
     * filtered rows
     * @param columnOffset the index of the integer sample at the top-left corner of the vertically
     * filtered columns
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     */
    private static void diagonal16(byte[] src, int rowOffset, int columnOffset, int srcStride, byte[] dst,
                                   int dstOffset, int dstStride, int height) {
        for (var y = 0; y < height; y++) {
            var h = rowOffset + y * srcStride;
            var v = columnOffset + y * srcStride;
            var a = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, h - 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var b = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, h - 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var c = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, h)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var d = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, h + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var e = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, h + 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var f = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, h + 3)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var horizontal = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, S256_5)).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, S256_20))
                    .lanewise(VectorOperators.ADD, S256_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255);
            var p = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, v - 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var q = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, v - srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, v)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var t = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, v + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var u = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, v + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var z = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, v + 3 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var vertical = p.lanewise(VectorOperators.ADD, z).lanewise(VectorOperators.SUB, q.lanewise(VectorOperators.ADD, u).lanewise(VectorOperators.MUL, S256_5)).lanewise(VectorOperators.ADD, r.lanewise(VectorOperators.ADD, t).lanewise(VectorOperators.MUL, S256_20))
                    .lanewise(VectorOperators.ADD, S256_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255);
            BYTES_16.cast(horizontal.lanewise(VectorOperators.ADD, vertical).lanewise(VectorOperators.ADD, S256_1).lanewise(VectorOperators.ASHR, 1)
                    .convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * Interpolates a luma block 8 samples wide.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     * @param fracX the horizontal quarter-sample fraction
     * @param fracY the vertical quarter-sample fraction
     * @param sums scratch for the first pass of the center filter
     * @param scratch scratch for the center half samples of the averaged positions
     */
    private static void luma8(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                               int height, int fracX, int fracY, short[] sums, byte[] scratch) {
        switch ((fracY << 2) | fracX) {
            case 0 -> copy8(src, srcOffset, srcStride, dst, dstOffset, dstStride, height);
            case 1 -> horizontalQuarter8(src, srcOffset, srcStride, dst, dstOffset, dstStride, height, 0);
            case 2 -> horizontal8(src, srcOffset, srcStride, dst, dstOffset, dstStride, height);
            case 3 -> horizontalQuarter8(src, srcOffset, srcStride, dst, dstOffset, dstStride, height, 1);
            case 4 -> verticalQuarter8(src, srcOffset, srcStride, dst, dstOffset, dstStride, height, 0);
            case 8 -> vertical8(src, srcOffset, srcStride, dst, dstOffset, dstStride, height);
            case 12 -> verticalQuarter8(src, srcOffset, srcStride, dst, dstOffset, dstStride, height, srcStride);
            case 10 -> center8(src, srcOffset, srcStride, dst, dstOffset, dstStride, height, sums);
            case 6, 14 -> {
                center8(src, srcOffset, srcStride, scratch, 0, 16, height, sums);
                horizontalAverage8(src, fracY == 1 ? srcOffset : srcOffset + srcStride, srcStride, scratch, dst, dstOffset,
                        dstStride, height);
            }
            case 9, 11 -> {
                center8(src, srcOffset, srcStride, scratch, 0, 16, height, sums);
                verticalAverage8(src, fracX == 1 ? srcOffset : srcOffset + 1, srcStride, scratch, dst, dstOffset,
                        dstStride, height);
            }
            default -> diagonal8(src, fracY == 1 ? srcOffset : srcOffset + srcStride, fracX == 1 ? srcOffset : srcOffset + 1,
                    srcStride, dst, dstOffset, dstStride, height);
        }
    }

    /**
     * Copies a block 8 samples wide at an integer position.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     */
    private static void copy8(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                               int height) {
        for (var y = 0; y < height; y++) {
            BYTES_8.cast(ByteVector.fromArray(B64, src, srcOffset + y * srcStride)).intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * Applies the horizontal half-sample filter to a block 8 samples wide.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     */
    private static void horizontal8(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                     int dstStride, int height) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var a = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s - 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s - 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var c = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var d = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var e = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var f = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 3)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, S128_5)).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, S128_20))
                    .lanewise(VectorOperators.ADD, S128_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S128_0).lanewise(VectorOperators.MIN, S128_255);
            BYTES_8.cast(value.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * Interpolates a horizontal quarter-sample position of a block 8 samples wide: the
     * horizontal half samples averaged with the integer samples of one column offset.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     * @param integer the column offset (0 or 1) of the integer samples
     */
    private static void horizontalQuarter8(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                            int dstStride, int height, int integer) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var a = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s - 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s - 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var c = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var d = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var e = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var f = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 3)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, S128_5)).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, S128_20))
                    .lanewise(VectorOperators.ADD, S128_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S128_0).lanewise(VectorOperators.MIN, S128_255);
            var sample = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + integer)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            BYTES_8.cast(value.lanewise(VectorOperators.ADD, sample).lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.ASHR, 1)
                    .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * Applies the vertical half-sample filter to a block 8 samples wide, two rows per step so
     * that the rows they share are loaded once.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     */
    private static void vertical8(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                   int dstStride, int height) {
        for (var y = 0; y < height; y += 2) {
            var s = srcOffset + y * srcStride;
            var r0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s - 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s - srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r2 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r3 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r4 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r5 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 3 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r6 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 4 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var first = r0.lanewise(VectorOperators.ADD, r5).lanewise(VectorOperators.SUB, r1.lanewise(VectorOperators.ADD, r4).lanewise(VectorOperators.MUL, S128_5)).lanewise(VectorOperators.ADD, r2.lanewise(VectorOperators.ADD, r3).lanewise(VectorOperators.MUL, S128_20))
                    .lanewise(VectorOperators.ADD, S128_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S128_0).lanewise(VectorOperators.MIN, S128_255);
            var second = r1.lanewise(VectorOperators.ADD, r6).lanewise(VectorOperators.SUB, r2.lanewise(VectorOperators.ADD, r5).lanewise(VectorOperators.MUL, S128_5)).lanewise(VectorOperators.ADD, r3.lanewise(VectorOperators.ADD, r4).lanewise(VectorOperators.MUL, S128_20))
                    .lanewise(VectorOperators.ADD, S128_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S128_0).lanewise(VectorOperators.MIN, S128_255);
            var d = dstOffset + y * dstStride;
            BYTES_8.cast(first.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d);
            BYTES_8.cast(second.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d + dstStride);
        }
    }

    /**
     * Interpolates a vertical quarter-sample position of a block 8 samples wide: the vertical
     * half samples averaged with the integer samples of one row offset, two rows per step so that the
     * rows they share are loaded once.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     * @param integer the index offset (0 or {@code srcStride}) of the integer samples
     */
    private static void verticalQuarter8(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                          int dstStride, int height, int integer) {
        for (var y = 0; y < height; y += 2) {
            var s = srcOffset + y * srcStride;
            var r0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s - 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s - srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r2 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r3 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r4 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r5 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 3 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r6 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 4 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var first = r0.lanewise(VectorOperators.ADD, r5).lanewise(VectorOperators.SUB, r1.lanewise(VectorOperators.ADD, r4).lanewise(VectorOperators.MUL, S128_5)).lanewise(VectorOperators.ADD, r2.lanewise(VectorOperators.ADD, r3).lanewise(VectorOperators.MUL, S128_20))
                    .lanewise(VectorOperators.ADD, S128_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S128_0).lanewise(VectorOperators.MIN, S128_255);
            var second = r1.lanewise(VectorOperators.ADD, r6).lanewise(VectorOperators.SUB, r2.lanewise(VectorOperators.ADD, r5).lanewise(VectorOperators.MUL, S128_5)).lanewise(VectorOperators.ADD, r3.lanewise(VectorOperators.ADD, r4).lanewise(VectorOperators.MUL, S128_20))
                    .lanewise(VectorOperators.ADD, S128_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S128_0).lanewise(VectorOperators.MIN, S128_255);
            var d = dstOffset + y * dstStride;
            var upper = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + integer)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var lower = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + srcStride + integer)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            BYTES_8.cast(first.lanewise(VectorOperators.ADD, upper).lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.ASHR, 1)
                    .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d);
            BYTES_8.cast(second.lanewise(VectorOperators.ADD, lower).lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.ASHR, 1)
                    .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d + dstStride);
        }
    }

    /**
     * Applies the center (two-dimensional) half-sample filter to a block 8 samples wide.
     *
     * <p>The first pass filters {@code height + 5} rows horizontally into 16-bit sums (their range,
     * {@code -2550..10710}, fits); the second pass adds the sums of the rows sharing a tap weight
     * in 16 bits (the pair sums, {@code -5100..21420}, still fit) and weights and rounds them in
     * 32-bit lanes.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     * @param sums scratch for {@code (height + 5) * 16} horizontal sums
     */
    private static void center8(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                                 int height, short[] sums) {
        var first = srcOffset - 2 * srcStride;
        for (var row = 0; row < height + 5; row++) {
            var s = first + row * srcStride;
            var a = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s - 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s - 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var c = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var d = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var e = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var f = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 3)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, S128_5)).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, S128_20));
            value.intoArray(sums, row * 16);
        }
        for (var y = 0; y < height; y++) {
            var r = y * 16;
            var outer = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, sums, r))
                    .lanewise(VectorOperators.ADD, SHORTS_8.cast(ShortVector.fromArray(S128, sums, r + 80))).convertShape(VectorOperators.S2I, I256, 0));
            var inner = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, sums, r + 16))
                    .lanewise(VectorOperators.ADD, SHORTS_8.cast(ShortVector.fromArray(S128, sums, r + 64))).convertShape(VectorOperators.S2I, I256, 0));
            var middle = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, sums, r + 32))
                    .lanewise(VectorOperators.ADD, SHORTS_8.cast(ShortVector.fromArray(S128, sums, r + 48))).convertShape(VectorOperators.S2I, I256, 0));
            var value = outer.lanewise(VectorOperators.SUB, inner.lanewise(VectorOperators.MUL, I256_5)).lanewise(VectorOperators.ADD, middle.lanewise(VectorOperators.MUL, I256_20)).lanewise(VectorOperators.ADD, I256_512)
                    .lanewise(VectorOperators.ASHR, 10).lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255);
            BYTES_8.cast(value.convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * Applies the horizontal half-sample filter to a block 8 samples wide and averages it
     * with precomputed center half samples.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the top-left corner of the filtered rows
     * @param srcStride the reference row stride
     * @param centers the center half samples, 16 per row
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     */
    private static void horizontalAverage8(byte[] src, int srcOffset, int srcStride, byte[] centers, byte[] dst,
                                            int dstOffset, int dstStride, int height) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var a = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s - 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s - 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var c = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var d = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var e = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var f = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 3)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, S128_5)).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, S128_20))
                    .lanewise(VectorOperators.ADD, S128_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S128_0).lanewise(VectorOperators.MIN, S128_255);
            var center = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, centers, y * 16)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            BYTES_8.cast(value.lanewise(VectorOperators.ADD, center).lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.ASHR, 1)
                    .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * Applies the vertical half-sample filter to a block 8 samples wide and averages it with
     * precomputed center half samples.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the top-left corner of the filtered columns
     * @param srcStride the reference row stride
     * @param centers the center half samples, 16 per row
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     */
    private static void verticalAverage8(byte[] src, int srcOffset, int srcStride, byte[] centers, byte[] dst,
                                          int dstOffset, int dstStride, int height) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var a = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s - 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s - srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var c = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var d = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var e = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var f = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 3 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, S128_5)).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, S128_20))
                    .lanewise(VectorOperators.ADD, S128_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S128_0).lanewise(VectorOperators.MIN, S128_255);
            var center = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, centers, y * 16)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            BYTES_8.cast(value.lanewise(VectorOperators.ADD, center).lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.ASHR, 1)
                    .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * Interpolates a diagonal quarter-sample position of a block 8 samples wide: the average
     * of a horizontal and a vertical half-sample filter, computed together per row.
     *
     * @param src the reference plane
     * @param rowOffset the index of the integer sample at the top-left corner of the horizontally
     * filtered rows
     * @param columnOffset the index of the integer sample at the top-left corner of the vertically
     * filtered columns
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     */
    private static void diagonal8(byte[] src, int rowOffset, int columnOffset, int srcStride, byte[] dst,
                                   int dstOffset, int dstStride, int height) {
        for (var y = 0; y < height; y++) {
            var h = rowOffset + y * srcStride;
            var v = columnOffset + y * srcStride;
            var a = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, h - 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, h - 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var c = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, h)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var d = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, h + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var e = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, h + 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var f = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, h + 3)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var horizontal = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, S128_5)).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, S128_20))
                    .lanewise(VectorOperators.ADD, S128_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S128_0).lanewise(VectorOperators.MIN, S128_255);
            var p = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, v - 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var q = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, v - srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, v)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var t = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, v + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var u = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, v + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var z = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, v + 3 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var vertical = p.lanewise(VectorOperators.ADD, z).lanewise(VectorOperators.SUB, q.lanewise(VectorOperators.ADD, u).lanewise(VectorOperators.MUL, S128_5)).lanewise(VectorOperators.ADD, r.lanewise(VectorOperators.ADD, t).lanewise(VectorOperators.MUL, S128_20))
                    .lanewise(VectorOperators.ADD, S128_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, S128_0).lanewise(VectorOperators.MIN, S128_255);
            BYTES_8.cast(horizontal.lanewise(VectorOperators.ADD, vertical).lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.ASHR, 1)
                    .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, dstOffset + y * dstStride);
        }
    }


    /**
     * Writes the rounded average of two blocks 16 samples wide.
     *
     * @param a the first block
     * @param aOffset the index of the first block
     * @param aStride the stride of the first block
     * @param b the second block
     * @param bOffset the index of the second block
     * @param bStride the stride of the second block
     * @param dst the destination plane
     * @param dstOffset the destination top-left index
     * @param dstStride the destination stride
     * @param height the block height
     */
    private static void average16(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride, byte[] dst,
                                  int dstOffset, int dstStride, int height) {
        for (var y = 0; y < height; y++) {
            var x = BYTES_16.cast(ByteVector.fromArray(B128, a, aOffset + y * aStride));
            var z = BYTES_16.cast(ByteVector.fromArray(B128, b, bOffset + y * bStride));
            x.lanewise(VectorOperators.OR, z).lanewise(VectorOperators.SUB, x.lanewise(VectorOperators.XOR, z).lanewise(VectorOperators.LSHR, 1))
                    .intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * Writes the rounded average of two blocks 8 samples wide.
     *
     * @param a the first block
     * @param aOffset the index of the first block
     * @param aStride the stride of the first block
     * @param b the second block
     * @param bOffset the index of the second block
     * @param bStride the stride of the second block
     * @param dst the destination plane
     * @param dstOffset the destination top-left index
     * @param dstStride the destination stride
     * @param height the block height
     */
    private static void average8(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride, byte[] dst,
                                 int dstOffset, int dstStride, int height) {
        for (var y = 0; y < height; y++) {
            var x = BYTES_8.cast(ByteVector.fromArray(B64, a, aOffset + y * aStride));
            var z = BYTES_8.cast(ByteVector.fromArray(B64, b, bOffset + y * bStride));
            x.lanewise(VectorOperators.OR, z).lanewise(VectorOperators.SUB, x.lanewise(VectorOperators.XOR, z).lanewise(VectorOperators.LSHR, 1))
                    .intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation specializes the four fraction cases: full-sample blocks are
     * copied, one-dimensional fractions use two taps, and vertical fractions (and two-dimensional
     * ones of 4-sample wide blocks) interpolate two rows per step so that the row between them is
     * loaded once. Blocks 8 samples wide use eight 16-bit lanes per plane and interpolate
     * two-dimensional fractions one plane at a time, through one call of {@link #chroma8Plane} in
     * a loop over the planes so that the kernel is compiled once; blocks 4 samples wide pack the
     * Cb row into lanes 0 to 3 and the Cr row into lanes 4 to 7 of one vector. The whole dispatch
     * stays in this one method, which is too large for the JIT to inline into its callers: the
     * kernels are then compiled within this method's inlining budget instead of that of the large
     * inter-prediction method calling it, where Vector API operations that fail to inline would
     * box their vectors.
     */
    @Override
    public void chromaMc(byte[] srcCb, byte[] srcCr, int srcOffset, int srcStride, byte[] dstCb, byte[] dstCr,
                         int dstOffset, int dstStride, int width, int height, int fracX, int fracY) {
        if (width == 8) {
            if ((fracX | fracY) == 0) {
                copy8(srcCb, srcOffset, srcStride, dstCb, dstOffset, dstStride, height);
                copy8(srcCr, srcOffset, srcStride, dstCr, dstOffset, dstStride, height);
            } else if (fracY == 0) {
                chroma8Horizontal(srcCb, srcCr, srcOffset, srcStride, dstCb, dstCr, dstOffset, dstStride, height, fracX);
            } else if (fracX == 0) {
                chroma8Vertical(srcCb, srcCr, srcOffset, srcStride, dstCb, dstCr, dstOffset, dstStride, height, fracY);
            } else {
                for (var plane = 0; plane < 2; plane++) {
                    chroma8Plane(plane == 0 ? srcCb : srcCr, srcOffset, srcStride, plane == 0 ? dstCb : dstCr,
                            dstOffset, dstStride, height, fracX, fracY);
                }
            }
        } else if (width == 4) {
            if ((fracX | fracY) == 0) {
                for (var y = 0; y < height; y++) {
                    var s = srcOffset + y * srcStride;
                    var d = dstOffset + y * dstStride;
                    INT_LE.set(dstCb, d, (int) INT_LE.get(srcCb, s));
                    INT_LE.set(dstCr, d, (int) INT_LE.get(srcCr, s));
                }
            } else if (fracY == 0) {
                chroma4Horizontal(srcCb, srcCr, srcOffset, srcStride, dstCb, dstCr, dstOffset, dstStride, height, fracX);
            } else {
                chroma4Bilinear(srcCb, srcCr, srcOffset, srcStride, dstCb, dstCr, dstOffset, dstStride, height,
                        fracX, fracY);
            }
        } else {
            super.chromaMc(srcCb, srcCr, srcOffset, srcStride, dstCb, dstCr, dstOffset, dstStride, width, height,
                    fracX, fracY);
        }
    }

    /**
     * Interpolates co-located chroma blocks 8 samples wide at a horizontal-only fraction.
     *
     * @param srcCb the Cb reference plane
     * @param srcCr the Cr reference plane
     * @param srcOffset the index of the integer sample at the blocks' top-left corner
     * @param srcStride the reference row stride
     * @param dstCb the Cb destination
     * @param dstCr the Cr destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     * @param fracX the horizontal eighth-sample fraction, 1 to 7
     */
    private static void chroma8Horizontal(byte[] srcCb, byte[] srcCr, int srcOffset, int srcStride, byte[] dstCb,
                                          byte[] dstCr, int dstOffset, int dstStride, int height, int fracX) {
        var wa = (short) ((8 - fracX) * 8);
        var wb = (short) (fracX * 8);
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var d = dstOffset + y * dstStride;
            var a = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, srcCb, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, srcCb, s + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var e = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, srcCr, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var f = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, srcCr, s + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            BYTES_8.cast(a.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wa)).lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wb))).lanewise(VectorOperators.ADD, S128_32).lanewise(VectorOperators.ASHR, 6)
                    .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dstCb, d);
            BYTES_8.cast(e.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wa)).lanewise(VectorOperators.ADD, f.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wb))).lanewise(VectorOperators.ADD, S128_32).lanewise(VectorOperators.ASHR, 6)
                    .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dstCr, d);
        }
    }

    /**
     * Interpolates co-located chroma blocks 8 samples wide at a vertical-only fraction, two rows
     * per step.
     *
     * @param srcCb the Cb reference plane
     * @param srcCr the Cr reference plane
     * @param srcOffset the index of the integer sample at the blocks' top-left corner
     * @param srcStride the reference row stride
     * @param dstCb the Cb destination
     * @param dstCr the Cr destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height, even
     * @param fracY the vertical eighth-sample fraction, 1 to 7
     */
    private static void chroma8Vertical(byte[] srcCb, byte[] srcCr, int srcOffset, int srcStride, byte[] dstCb,
                                        byte[] dstCr, int dstOffset, int dstStride, int height, int fracY) {
        var wa = (short) ((8 - fracY) * 8);
        var wc = (short) (fracY * 8);
        for (var y = 0; y < height; y += 2) {
            var s = srcOffset + y * srcStride;
            var d = dstOffset + y * dstStride;
            var a0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, srcCb, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var a1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, srcCb, s + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var a2 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, srcCb, s + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var e0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, srcCr, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var e1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, srcCr, s + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var e2 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, srcCr, s + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var middleCb = a1.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wa));
            var middleCr = e1.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wa));
            var a1c = a1.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wc));
            var e1c = e1.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wc));
            BYTES_8.cast(a0.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wa)).lanewise(VectorOperators.ADD, a1c).lanewise(VectorOperators.ADD, S128_32).lanewise(VectorOperators.ASHR, 6)
                    .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dstCb, d);
            BYTES_8.cast(middleCb.lanewise(VectorOperators.ADD, a2.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wc))).lanewise(VectorOperators.ADD, S128_32).lanewise(VectorOperators.ASHR, 6)
                    .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dstCb, d + dstStride);
            BYTES_8.cast(e0.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wa)).lanewise(VectorOperators.ADD, e1c).lanewise(VectorOperators.ADD, S128_32).lanewise(VectorOperators.ASHR, 6)
                    .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dstCr, d);
            BYTES_8.cast(middleCr.lanewise(VectorOperators.ADD, e2.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wc))).lanewise(VectorOperators.ADD, S128_32).lanewise(VectorOperators.ASHR, 6)
                    .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dstCr, d + dstStride);
        }
    }

    /**
     * Interpolates one chroma block 8 samples wide at a two-dimensional fraction, one row per
     * step.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     * @param fracX the horizontal eighth-sample fraction, 1 to 7
     * @param fracY the vertical eighth-sample fraction, 1 to 7
     */
    private static void chroma8Plane(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                                     int height, int fracX, int fracY) {
        var wa = (short) ((8 - fracX) * (8 - fracY));
        var wb = (short) (fracX * (8 - fracY));
        var wc = (short) ((8 - fracX) * fracY);
        var wd = (short) (fracX * fracY);
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var a = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var c = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var e = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s + srcStride + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            BYTES_8.cast(a.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wa)).lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wb))).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wc))).lanewise(VectorOperators.ADD, e.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wd))).lanewise(VectorOperators.ADD, S128_32)
                    .lanewise(VectorOperators.ASHR, 6).convertShape(VectorOperators.S2B, B64, 0))
                    .intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * Interpolates co-located chroma blocks 4 samples wide at a horizontal-only fraction, the Cb
     * row in lanes 0 to 3 and the Cr row in lanes 4 to 7.
     *
     * @param srcCb the Cb reference plane
     * @param srcCr the Cr reference plane
     * @param srcOffset the index of the integer sample at the blocks' top-left corner
     * @param srcStride the reference row stride
     * @param dstCb the Cb destination
     * @param dstCr the Cr destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     * @param fracX the horizontal eighth-sample fraction, 1 to 7
     */
    private static void chroma4Horizontal(byte[] srcCb, byte[] srcCr, int srcOffset, int srcStride, byte[] dstCb,
                                          byte[] dstCr, int dstOffset, int dstStride, int height, int fracX) {
        var wa = (short) ((8 - fracX) * 8);
        var wb = (short) (fracX * 8);
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var d = dstOffset + y * dstStride;
            var a = SHORTS_8.cast(BYTES_8.cast(INTS_2.cast(IntVector.zero(I64)).withLane(0, (int) INT_LE.get(srcCb, s))
                    .withLane(1, (int) INT_LE.get(srcCr, s)).reinterpretShape(B64, 0))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b = SHORTS_8.cast(BYTES_8.cast(INTS_2.cast(IntVector.zero(I64)).withLane(0, (int) INT_LE.get(srcCb, s + 1))
                    .withLane(1, (int) INT_LE.get(srcCr, s + 1)).reinterpretShape(B64, 0))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var out = INTS_2.cast(BYTES_8.cast(a.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wa)).lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wb))).lanewise(VectorOperators.ADD, S128_32).lanewise(VectorOperators.ASHR, 6)
                    .convertShape(VectorOperators.S2B, B64, 0)).reinterpretShape(I64, 0));
            INT_LE.set(dstCb, d, out.lane(0));
            INT_LE.set(dstCr, d, out.lane(1));
        }
    }

    /**
     * Interpolates co-located chroma blocks 4 samples wide at a fraction with a vertical
     * component, two rows per step, the Cb row in lanes 0 to 3 and the Cr row in lanes 4 to 7.
     *
     * @param srcCb the Cb reference plane
     * @param srcCr the Cr reference plane
     * @param srcOffset the index of the integer sample at the blocks' top-left corner
     * @param srcStride the reference row stride
     * @param dstCb the Cb destination
     * @param dstCr the Cr destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height, even
     * @param fracX the horizontal eighth-sample fraction, 0 to 7
     * @param fracY the vertical eighth-sample fraction, 1 to 7
     */
    private static void chroma4Bilinear(byte[] srcCb, byte[] srcCr, int srcOffset, int srcStride, byte[] dstCb,
                                        byte[] dstCr, int dstOffset, int dstStride, int height, int fracX, int fracY) {
        var wa = (short) ((8 - fracX) * (8 - fracY));
        var wb = (short) (fracX * (8 - fracY));
        var wc = (short) ((8 - fracX) * fracY);
        var wd = (short) (fracX * fracY);
        for (var y = 0; y < height; y += 2) {
            var s = srcOffset + y * srcStride;
            var d = dstOffset + y * dstStride;
            var t = s + srcStride;
            var u = t + srcStride;
            var a0 = SHORTS_8.cast(BYTES_8.cast(INTS_2.cast(IntVector.zero(I64)).withLane(0, (int) INT_LE.get(srcCb, s))
                    .withLane(1, (int) INT_LE.get(srcCr, s)).reinterpretShape(B64, 0))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b0 = SHORTS_8.cast(BYTES_8.cast(INTS_2.cast(IntVector.zero(I64)).withLane(0, (int) INT_LE.get(srcCb, s + 1))
                    .withLane(1, (int) INT_LE.get(srcCr, s + 1)).reinterpretShape(B64, 0))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var a1 = SHORTS_8.cast(BYTES_8.cast(INTS_2.cast(IntVector.zero(I64)).withLane(0, (int) INT_LE.get(srcCb, t))
                    .withLane(1, (int) INT_LE.get(srcCr, t)).reinterpretShape(B64, 0))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b1 = SHORTS_8.cast(BYTES_8.cast(INTS_2.cast(IntVector.zero(I64)).withLane(0, (int) INT_LE.get(srcCb, t + 1))
                    .withLane(1, (int) INT_LE.get(srcCr, t + 1)).reinterpretShape(B64, 0))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var a2 = SHORTS_8.cast(BYTES_8.cast(INTS_2.cast(IntVector.zero(I64)).withLane(0, (int) INT_LE.get(srcCb, u))
                    .withLane(1, (int) INT_LE.get(srcCr, u)).reinterpretShape(B64, 0))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var b2 = SHORTS_8.cast(BYTES_8.cast(INTS_2.cast(IntVector.zero(I64)).withLane(0, (int) INT_LE.get(srcCb, u + 1))
                    .withLane(1, (int) INT_LE.get(srcCr, u + 1)).reinterpretShape(B64, 0))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var first = INTS_2.cast(BYTES_8.cast(a0.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wa)).lanewise(VectorOperators.ADD, b0.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wb))).lanewise(VectorOperators.ADD, a1.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wc))).lanewise(VectorOperators.ADD, b1.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wd))).lanewise(VectorOperators.ADD, S128_32)
                    .lanewise(VectorOperators.ASHR, 6).convertShape(VectorOperators.S2B, B64, 0)).reinterpretShape(I64, 0));
            var second = INTS_2.cast(BYTES_8.cast(a1.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wa)).lanewise(VectorOperators.ADD, b1.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wb))).lanewise(VectorOperators.ADD, a2.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wc))).lanewise(VectorOperators.ADD, b2.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) wd))).lanewise(VectorOperators.ADD, S128_32)
                    .lanewise(VectorOperators.ASHR, 6).convertShape(VectorOperators.S2B, B64, 0)).reinterpretShape(I64, 0));
            INT_LE.set(dstCb, d, first.lane(0));
            INT_LE.set(dstCr, d, first.lane(1));
            INT_LE.set(dstCb, d + dstStride, second.lane(0));
            INT_LE.set(dstCr, d + dstStride, second.lane(1));
        }
    }

    @Override
    public void averageBlocks(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride,
                              byte[] dst, int dstOffset, int dstStride, int width, int height) {
        if (width == 16) {
            average16(a, aOffset, aStride, b, bOffset, bStride, dst, dstOffset, dstStride, height);
        } else if (width == 8) {
            average8(a, aOffset, aStride, b, bOffset, bStride, dst, dstOffset, dstStride, height);
        } else {
            super.averageBlocks(a, aOffset, aStride, b, bOffset, bStride, dst, dstOffset, dstStride, width, height);
        }
    }

    @Override
    public void average(byte[] dst, int dstOffset, int dstStride, byte[] src, int srcOffset, int srcStride,
                        int width, int height) {
        if (width == 16) {
            average16(dst, dstOffset, dstStride, src, srcOffset, srcStride, dst, dstOffset, dstStride, height);
        } else if (width == 8) {
            average8(dst, dstOffset, dstStride, src, srcOffset, srcStride, dst, dstOffset, dstStride, height);
        } else {
            super.average(dst, dstOffset, dstStride, src, srcOffset, srcStride, width, height);
        }
    }

    @Override
    public void biWeight(byte[] dst, int dstOffset, int dstStride, byte[] src, int srcOffset, int srcStride,
                         int width, int height, int logWd, int weight0, int weight1, int offset) {
        if (!Simd.WIDE) {
            super.biWeight(dst, dstOffset, dstStride, src, srcOffset, srcStride, width, height, logWd, weight0, weight1, offset);
            return;
        }
        if (width != 16 && width != 8) {
            super.biWeight(dst, dstOffset, dstStride, src, srcOffset, srcStride, width, height, logWd, weight0, weight1, offset);
            return;
        }
        var round = 1 << logWd;
        var shift = logWd + 1;
        var groups = width >> 3;
        for (var y = 0; y < height; y++) {
            for (var g = 0; g < groups; g++) {
                var d = dstOffset + y * dstStride + g * 8;
                var s = srcOffset + y * srcStride + g * 8;
                var p0 = INTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, dst, d)).convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
                var p1 = INTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, s)).convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
                var value = p0.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, weight0)).lanewise(VectorOperators.ADD, p1.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, weight1))).lanewise(VectorOperators.ADD, IntVector.broadcast(I256, round)).lanewise(VectorOperators.ASHR, shift)
                        .lanewise(VectorOperators.ADD, IntVector.broadcast(I256, offset)).lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255);
                BYTES_8.cast(value.convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, d);
            }
        }
    }

    @Override
    public void weight(byte[] dst, int dstOffset, int dstStride, int width, int height,
                       int logWd, int weight, int offset) {
        if (!Simd.WIDE) {
            super.weight(dst, dstOffset, dstStride, width, height, logWd, weight, offset);
            return;
        }
        if (width != 16 && width != 8) {
            super.weight(dst, dstOffset, dstStride, width, height, logWd, weight, offset);
            return;
        }
        var round = logWd >= 1 ? 1 << (logWd - 1) : 0;
        var groups = width >> 3;
        for (var y = 0; y < height; y++) {
            for (var g = 0; g < groups; g++) {
                var d = dstOffset + y * dstStride + g * 8;
                var p = INTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, dst, d)).convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
                var value = p.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, weight)).lanewise(VectorOperators.ADD, IntVector.broadcast(I256, round)).lanewise(VectorOperators.ASHR, logWd).lanewise(VectorOperators.ADD, IntVector.broadcast(I256, offset)).lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255);
                BYTES_8.cast(value.convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, d);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation keeps the four rows in 4-lane vectors: a register
     * transpose turns the columns into vectors so that the horizontal pass runs lane-wise, a
     * second transpose restores rows for the vertical pass, and each row of four samples is
     * reconstructed with one 32-bit load and one 32-bit store.
     */
    @Override
    public void idct4x4Add(int[] c, int o, byte[] dst, int dstOffset, int dstStride) {
        var r0 = INTS_4.cast(IntVector.fromArray(I128, c, o));
        var r1 = INTS_4.cast(IntVector.fromArray(I128, c, o + 4));
        var r2 = INTS_4.cast(IntVector.fromArray(I128, c, o + 8));
        var r3 = INTS_4.cast(IntVector.fromArray(I128, c, o + 12));
        Arrays.fill(c, o, o + 16, 0);
        var t0 = r0.rearrange(INTERLEAVE_LOW, r1);
        var t1 = r2.rearrange(INTERLEAVE_LOW, r3);
        var t2 = r0.rearrange(INTERLEAVE_HIGH, r1);
        var t3 = r2.rearrange(INTERLEAVE_HIGH, r3);
        var d0 = t0.rearrange(PAIR_LOW_128, t1);
        var d1 = t0.rearrange(PAIR_HIGH_128, t1);
        var d2 = t2.rearrange(PAIR_LOW_128, t3);
        var d3 = t2.rearrange(PAIR_HIGH_128, t3);
        var e = d0.lanewise(VectorOperators.ADD, d2);
        var f = d0.lanewise(VectorOperators.SUB, d2);
        var g = d1.lanewise(VectorOperators.ASHR, 1).lanewise(VectorOperators.SUB, d3);
        var h = d1.lanewise(VectorOperators.ADD, d3.lanewise(VectorOperators.ASHR, 1));
        var c0 = e.lanewise(VectorOperators.ADD, h);
        var c1 = f.lanewise(VectorOperators.ADD, g);
        var c2 = f.lanewise(VectorOperators.SUB, g);
        var c3 = e.lanewise(VectorOperators.SUB, h);
        t0 = c0.rearrange(INTERLEAVE_LOW, c1);
        t1 = c2.rearrange(INTERLEAVE_LOW, c3);
        t2 = c0.rearrange(INTERLEAVE_HIGH, c1);
        t3 = c2.rearrange(INTERLEAVE_HIGH, c3);
        var s0 = t0.rearrange(PAIR_LOW_128, t1);
        var s1 = t0.rearrange(PAIR_HIGH_128, t1);
        var s2 = t2.rearrange(PAIR_LOW_128, t3);
        var s3 = t2.rearrange(PAIR_HIGH_128, t3);
        e = s0.lanewise(VectorOperators.ADD, s2);
        f = s0.lanewise(VectorOperators.SUB, s2);
        g = s1.lanewise(VectorOperators.ASHR, 1).lanewise(VectorOperators.SUB, s3);
        h = s1.lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.ASHR, 1));
        var row1 = dstOffset + dstStride;
        var row2 = row1 + dstStride;
        var row3 = row2 + dstStride;
        var out0 = INTS_4.cast(BYTES_16.cast(INTS_4.cast(IntVector.zero(I128)).withLane(0, (int) INT_LE.get(dst, dstOffset)).reinterpretShape(B128, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I128, 0))
                .lanewise(VectorOperators.ADD, e.lanewise(VectorOperators.ADD, h).lanewise(VectorOperators.ADD, I128_32).lanewise(VectorOperators.ASHR, 6)).lanewise(VectorOperators.MAX, I128_0).lanewise(VectorOperators.MIN, I128_255);
        var out1 = INTS_4.cast(BYTES_16.cast(INTS_4.cast(IntVector.zero(I128)).withLane(0, (int) INT_LE.get(dst, row1)).reinterpretShape(B128, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I128, 0))
                .lanewise(VectorOperators.ADD, f.lanewise(VectorOperators.ADD, g).lanewise(VectorOperators.ADD, I128_32).lanewise(VectorOperators.ASHR, 6)).lanewise(VectorOperators.MAX, I128_0).lanewise(VectorOperators.MIN, I128_255);
        var out2 = INTS_4.cast(BYTES_16.cast(INTS_4.cast(IntVector.zero(I128)).withLane(0, (int) INT_LE.get(dst, row2)).reinterpretShape(B128, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I128, 0))
                .lanewise(VectorOperators.ADD, f.lanewise(VectorOperators.SUB, g).lanewise(VectorOperators.ADD, I128_32).lanewise(VectorOperators.ASHR, 6)).lanewise(VectorOperators.MAX, I128_0).lanewise(VectorOperators.MIN, I128_255);
        var out3 = INTS_4.cast(BYTES_16.cast(INTS_4.cast(IntVector.zero(I128)).withLane(0, (int) INT_LE.get(dst, row3)).reinterpretShape(B128, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I128, 0))
                .lanewise(VectorOperators.ADD, e.lanewise(VectorOperators.SUB, h).lanewise(VectorOperators.ADD, I128_32).lanewise(VectorOperators.ASHR, 6)).lanewise(VectorOperators.MAX, I128_0).lanewise(VectorOperators.MIN, I128_255);
        INT_LE.set(dst, dstOffset, INTS_2.cast(BYTES_8.cast(out0.convertShape(VectorOperators.I2B, B64, 0)).reinterpretShape(I64, 0)).lane(0));
        INT_LE.set(dst, row1, INTS_2.cast(BYTES_8.cast(out1.convertShape(VectorOperators.I2B, B64, 0)).reinterpretShape(I64, 0)).lane(0));
        INT_LE.set(dst, row2, INTS_2.cast(BYTES_8.cast(out2.convertShape(VectorOperators.I2B, B64, 0)).reinterpretShape(I64, 0)).lane(0));
        INT_LE.set(dst, row3, INTS_2.cast(BYTES_8.cast(out3.convertShape(VectorOperators.I2B, B64, 0)).reinterpretShape(I64, 0)).lane(0));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation runs the horizontal pass with the scalar transform, row by
     * row in place, and the vertical pass lane-wise on eight 8-lane row vectors, which needs no
     * transpose; each row of samples is reconstructed with one 64-bit load and store.
     */
    @Override
    public void idct8x8Add(int[] c, int o, byte[] dst, int dstOffset, int dstStride) {
        if (!Simd.WIDE) {
            super.idct8x8Add(c, o, dst, dstOffset, dstStride);
            return;
        }
        for (var i = 0; i < 8; i++) {
            transform8(c, o + i * 8, 1);
        }
        var d0 = INTS_8.cast(IntVector.fromArray(I256, c, o));
        var d1 = INTS_8.cast(IntVector.fromArray(I256, c, o + 8));
        var d2 = INTS_8.cast(IntVector.fromArray(I256, c, o + 16));
        var d3 = INTS_8.cast(IntVector.fromArray(I256, c, o + 24));
        var d4 = INTS_8.cast(IntVector.fromArray(I256, c, o + 32));
        var d5 = INTS_8.cast(IntVector.fromArray(I256, c, o + 40));
        var d6 = INTS_8.cast(IntVector.fromArray(I256, c, o + 48));
        var d7 = INTS_8.cast(IntVector.fromArray(I256, c, o + 56));
        Arrays.fill(c, o, o + 64, 0);
        var e0 = d0.lanewise(VectorOperators.ADD, d4);
        var e1 = d5.lanewise(VectorOperators.SUB, d3).lanewise(VectorOperators.SUB, d7).lanewise(VectorOperators.SUB, d7.lanewise(VectorOperators.ASHR, 1));
        var e2 = d0.lanewise(VectorOperators.SUB, d4);
        var e3 = d1.lanewise(VectorOperators.ADD, d7).lanewise(VectorOperators.SUB, d3).lanewise(VectorOperators.SUB, d3.lanewise(VectorOperators.ASHR, 1));
        var e4 = d2.lanewise(VectorOperators.ASHR, 1).lanewise(VectorOperators.SUB, d6);
        var e5 = d7.lanewise(VectorOperators.SUB, d1).lanewise(VectorOperators.ADD, d5).lanewise(VectorOperators.ADD, d5.lanewise(VectorOperators.ASHR, 1));
        var e6 = d2.lanewise(VectorOperators.ADD, d6.lanewise(VectorOperators.ASHR, 1));
        var e7 = d3.lanewise(VectorOperators.ADD, d5).lanewise(VectorOperators.ADD, d1).lanewise(VectorOperators.ADD, d1.lanewise(VectorOperators.ASHR, 1));
        var f0 = e0.lanewise(VectorOperators.ADD, e6);
        var f1 = e1.lanewise(VectorOperators.ADD, e7.lanewise(VectorOperators.ASHR, 2));
        var f2 = e2.lanewise(VectorOperators.ADD, e4);
        var f3 = e3.lanewise(VectorOperators.ADD, e5.lanewise(VectorOperators.ASHR, 2));
        var f4 = e2.lanewise(VectorOperators.SUB, e4);
        var f5 = e3.lanewise(VectorOperators.ASHR, 2).lanewise(VectorOperators.SUB, e5);
        var f6 = e0.lanewise(VectorOperators.SUB, e6);
        var f7 = e7.lanewise(VectorOperators.SUB, e1.lanewise(VectorOperators.ASHR, 2));
        BYTES_8.cast(INTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, dst, dstOffset)).convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0))
                .lanewise(VectorOperators.ADD, f0.lanewise(VectorOperators.ADD, f7).lanewise(VectorOperators.ADD, I256_32).lanewise(VectorOperators.ASHR, 6)).lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255)
                .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset);
        BYTES_8.cast(INTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, dst, dstOffset + 1 * dstStride)).convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0))
                .lanewise(VectorOperators.ADD, f2.lanewise(VectorOperators.ADD, f5).lanewise(VectorOperators.ADD, I256_32).lanewise(VectorOperators.ASHR, 6)).lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255)
                .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + 1 * dstStride);
        BYTES_8.cast(INTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, dst, dstOffset + 2 * dstStride)).convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0))
                .lanewise(VectorOperators.ADD, f4.lanewise(VectorOperators.ADD, f3).lanewise(VectorOperators.ADD, I256_32).lanewise(VectorOperators.ASHR, 6)).lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255)
                .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + 2 * dstStride);
        BYTES_8.cast(INTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, dst, dstOffset + 3 * dstStride)).convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0))
                .lanewise(VectorOperators.ADD, f6.lanewise(VectorOperators.ADD, f1).lanewise(VectorOperators.ADD, I256_32).lanewise(VectorOperators.ASHR, 6)).lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255)
                .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + 3 * dstStride);
        BYTES_8.cast(INTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, dst, dstOffset + 4 * dstStride)).convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0))
                .lanewise(VectorOperators.ADD, f6.lanewise(VectorOperators.SUB, f1).lanewise(VectorOperators.ADD, I256_32).lanewise(VectorOperators.ASHR, 6)).lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255)
                .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + 4 * dstStride);
        BYTES_8.cast(INTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, dst, dstOffset + 5 * dstStride)).convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0))
                .lanewise(VectorOperators.ADD, f4.lanewise(VectorOperators.SUB, f3).lanewise(VectorOperators.ADD, I256_32).lanewise(VectorOperators.ASHR, 6)).lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255)
                .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + 5 * dstStride);
        BYTES_8.cast(INTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, dst, dstOffset + 6 * dstStride)).convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0))
                .lanewise(VectorOperators.ADD, f2.lanewise(VectorOperators.SUB, f5).lanewise(VectorOperators.ADD, I256_32).lanewise(VectorOperators.ASHR, 6)).lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255)
                .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + 6 * dstStride);
        BYTES_8.cast(INTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, dst, dstOffset + 7 * dstStride)).convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0))
                .lanewise(VectorOperators.ADD, f0.lanewise(VectorOperators.SUB, f7).lanewise(VectorOperators.ADD, I256_32).lanewise(VectorOperators.ASHR, 6)).lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255)
                .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, dstOffset + 7 * dstStride);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation reconstructs the 8x8 block row by row with 8-lane vectors
     * whose left and right halves carry the DC terms of the left and right blocks; each DC term
     * is first clamped to {@code [-255, 255]}, which leaves every clamped sample unchanged.
     */
    @Override
    public void chromaDcAdd(int[] c, int o, byte[] dst, int dstOffset, int dstStride) {
        var dc0 = (short) Math.clamp((c[o] + 32) >> 6, -255, 255);
        var dc1 = (short) Math.clamp((c[o + 16] + 32) >> 6, -255, 255);
        var dc2 = (short) Math.clamp((c[o + 32] + 32) >> 6, -255, 255);
        var dc3 = (short) Math.clamp((c[o + 48] + 32) >> 6, -255, 255);
        c[o] = 0;
        c[o + 16] = 0;
        c[o + 32] = 0;
        c[o + 48] = 0;
        var upper = SHORTS_8.cast(ShortVector.broadcast(S128, dc0)).blend(ShortVector.broadcast(S128, (short) dc1), RIGHT_HALF_8);
        var lower = SHORTS_8.cast(ShortVector.broadcast(S128, dc2)).blend(ShortVector.broadcast(S128, (short) dc3), RIGHT_HALF_8);
        for (var y = 0; y < 4; y++) {
            var row = dstOffset + y * dstStride;
            BYTES_8.cast(SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, dst, row)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0))
                    .lanewise(VectorOperators.ADD, upper).lanewise(VectorOperators.MAX, S128_0).lanewise(VectorOperators.MIN, S128_255).convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, row);
        }
        for (var y = 4; y < 8; y++) {
            var row = dstOffset + y * dstStride;
            BYTES_8.cast(SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, dst, row)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0))
                    .lanewise(VectorOperators.ADD, lower).lanewise(VectorOperators.MAX, S128_0).lanewise(VectorOperators.MIN, S128_255).convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, row);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation gathers the four rows of four samples into one 16-lane
     * vector, adds and clamps them together and scatters them back with 32-bit stores. The DC
     * term is first clamped to {@code [-255, 255]}, which leaves every clamped sample unchanged
     * and keeps the sums within 16 bits.
     */
    @Override
    public void idct4x4DcAdd(int[] c, int o, byte[] dst, int dstOffset, int dstStride) {
        if (!Simd.WIDE) {
            super.idct4x4DcAdd(c, o, dst, dstOffset, dstStride);
            return;
        }
        var dc = (short) Math.clamp((c[o] + 32) >> 6, -255, 255);
        c[o] = 0;
        var row1 = dstOffset + dstStride;
        var row2 = row1 + dstStride;
        var row3 = row2 + dstStride;
        var sum = SHORTS_16.cast(BYTES_16.cast(INTS_4.cast(IntVector.zero(I128)).withLane(0, (int) INT_LE.get(dst, dstOffset))
                .withLane(1, (int) INT_LE.get(dst, row1)).withLane(2, (int) INT_LE.get(dst, row2))
                .withLane(3, (int) INT_LE.get(dst, row3)).reinterpretShape(B128, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                .lanewise(VectorOperators.ADD, ShortVector.broadcast(S256, (short) dc)).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255);
        var out = INTS_4.cast(BYTES_16.cast(sum.convertShape(VectorOperators.S2B, B128, 0)).reinterpretShape(I128, 0));
        INT_LE.set(dst, dstOffset, out.lane(0));
        INT_LE.set(dst, row1, out.lane(1));
        INT_LE.set(dst, row2, out.lane(2));
        INT_LE.set(dst, row3, out.lane(3));
    }

    /**
     * Filters sixteen lines across a luma edge with boundary strengths below 4, the lines being
     * the columns of consecutive rows of an array.
     *
     * @param pix the samples
     * @param q the index of the {@code q0} row
     * @param stride the distance between rows
     * @param alpha the {@code alpha} threshold
     * @param beta the {@code beta} threshold
     * @param tc0 the packed {@code tC0} of each group of four lines, negative to skip
     */
    private static void lumaNormal16(byte[] pix, int q, int stride, int alpha, int beta, int tc0) {
        var p2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pix, q - 3 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pix, q - 2 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pix, q - stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pix, q)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pix, q + stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pix, q + 2 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var tcBase = SHORTS_16.cast(BYTES_16.cast(INTS_4.cast(IntVector.broadcast(I128, tc0)).reinterpretShape(B128, 0))
                .rearrange(LUMA_TC_SPREAD).convertShape(VectorOperators.B2S, S256, 0));
        var b = (short) beta;
        var mask = p0.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, ShortVector.broadcast(S256, (short) alpha))
                .and(p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, ShortVector.broadcast(S256, (short) b)))
                .and(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, ShortVector.broadcast(S256, (short) b)))
                .and(tcBase.compare(VectorOperators.GE, S256_0));
        if (!mask.anyTrue()) {
            return;
        }
        var ap = p2.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, ShortVector.broadcast(S256, (short) b));
        var aq = q2.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, ShortVector.broadcast(S256, (short) b));
        var tc = tcBase.lanewise(VectorOperators.SUB, ap.toVector()).lanewise(VectorOperators.SUB, aq.toVector());
        var delta = q0.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.LSHL, 2).lanewise(VectorOperators.ADD, p1.lanewise(VectorOperators.SUB, q1)).lanewise(VectorOperators.ADD, S256_4)
                .lanewise(VectorOperators.ASHR, 3).lanewise(VectorOperators.MAX, tc.lanewise(VectorOperators.NEG)).lanewise(VectorOperators.MIN, tc);
        var newP0 = p0.blend(p0.lanewise(VectorOperators.ADD, delta).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255), mask);
        var newQ0 = q0.blend(q0.lanewise(VectorOperators.SUB, delta).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255), mask);
        var average = p0.lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, S256_1).lanewise(VectorOperators.ASHR, 1);
        var negTc0 = tcBase.lanewise(VectorOperators.NEG);
        var newP1 = p1.blend(p1.lanewise(VectorOperators.ADD, p2.lanewise(VectorOperators.ADD, average).lanewise(VectorOperators.SUB, p1.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ASHR, 1).lanewise(VectorOperators.MAX, negTc0).lanewise(VectorOperators.MIN, tcBase)), mask.and(ap));
        var newQ1 = q1.blend(q1.lanewise(VectorOperators.ADD, q2.lanewise(VectorOperators.ADD, average).lanewise(VectorOperators.SUB, q1.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ASHR, 1).lanewise(VectorOperators.MAX, negTc0).lanewise(VectorOperators.MIN, tcBase)), mask.and(aq));
        BYTES_16.cast(newP1.convertShape(VectorOperators.S2B, B128, 0)).intoArray(pix, q - 2 * stride);
        BYTES_16.cast(newP0.convertShape(VectorOperators.S2B, B128, 0)).intoArray(pix, q - stride);
        BYTES_16.cast(newQ0.convertShape(VectorOperators.S2B, B128, 0)).intoArray(pix, q);
        BYTES_16.cast(newQ1.convertShape(VectorOperators.S2B, B128, 0)).intoArray(pix, q + stride);
    }

    /**
     * Filters sixteen lines across a luma edge with boundary strength 4, the lines being the
     * columns of consecutive rows of an array.
     *
     * @param pix the samples
     * @param q the index of the {@code q0} row
     * @param stride the distance between rows
     * @param alpha the {@code alpha} threshold
     * @param beta the {@code beta} threshold
     */
    private static void lumaStrong16(byte[] pix, int q, int stride, int alpha, int beta) {
        var p3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pix, q - 4 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pix, q - 3 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pix, q - 2 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pix, q - stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pix, q)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pix, q + stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pix, q + 2 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, pix, q + 3 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var b = (short) beta;
        var difference = p0.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS);
        var mask = difference.compare(VectorOperators.LT, ShortVector.broadcast(S256, (short) alpha)).and(p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, ShortVector.broadcast(S256, (short) b))).and(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, ShortVector.broadcast(S256, (short) b)));
        if (!mask.anyTrue()) {
            return;
        }
        var strong = mask.and(difference.compare(VectorOperators.LT, ShortVector.broadcast(S256, (short) ((alpha >> 2) + 2))));
        var ap = strong.and(p2.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, ShortVector.broadcast(S256, (short) b)));
        var aq = strong.and(q2.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, ShortVector.broadcast(S256, (short) b)));
        var sum = p1.lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, q0);
        var p0Strong = p2.lanewise(VectorOperators.ADD, p1.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, p0.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ADD, q0.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, S256_4).lanewise(VectorOperators.ASHR, 3);
        var p1Strong = p2.lanewise(VectorOperators.ADD, sum).lanewise(VectorOperators.ADD, S256_2).lanewise(VectorOperators.ASHR, 2);
        var p2Strong = p3.lanewise(VectorOperators.LSHL, 1).lanewise(VectorOperators.ADD, p2.lanewise(VectorOperators.MUL, S256_3)).lanewise(VectorOperators.ADD, sum).lanewise(VectorOperators.ADD, S256_4)
                .lanewise(VectorOperators.ASHR, 3);
        var p0Weak = p1.lanewise(VectorOperators.LSHL, 1).lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, S256_2).lanewise(VectorOperators.ASHR, 2);
        var sumQ = q1.lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, p0);
        var q0Strong = p1.lanewise(VectorOperators.ADD, p0.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, q0.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ADD, q1.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, q2).lanewise(VectorOperators.ADD, S256_4).lanewise(VectorOperators.ASHR, 3);
        var q1Strong = q2.lanewise(VectorOperators.ADD, sumQ).lanewise(VectorOperators.ADD, S256_2).lanewise(VectorOperators.ASHR, 2);
        var q2Strong = q3.lanewise(VectorOperators.LSHL, 1).lanewise(VectorOperators.ADD, q2.lanewise(VectorOperators.MUL, S256_3)).lanewise(VectorOperators.ADD, sumQ).lanewise(VectorOperators.ADD, S256_4)
                .lanewise(VectorOperators.ASHR, 3);
        var q0Weak = q1.lanewise(VectorOperators.LSHL, 1).lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, p1).lanewise(VectorOperators.ADD, S256_2).lanewise(VectorOperators.ASHR, 2);
        var newP2 = p2.blend(p2Strong, ap);
        var newP1 = p1.blend(p1Strong, ap);
        var newP0 = p0.blend(p0Weak.blend(p0Strong, ap), mask);
        var newQ0 = q0.blend(q0Weak.blend(q0Strong, aq), mask);
        var newQ1 = q1.blend(q1Strong, aq);
        var newQ2 = q2.blend(q2Strong, aq);
        BYTES_16.cast(newP2.convertShape(VectorOperators.S2B, B128, 0)).intoArray(pix, q - 3 * stride);
        BYTES_16.cast(newP1.convertShape(VectorOperators.S2B, B128, 0)).intoArray(pix, q - 2 * stride);
        BYTES_16.cast(newP0.convertShape(VectorOperators.S2B, B128, 0)).intoArray(pix, q - stride);
        BYTES_16.cast(newQ0.convertShape(VectorOperators.S2B, B128, 0)).intoArray(pix, q);
        BYTES_16.cast(newQ1.convertShape(VectorOperators.S2B, B128, 0)).intoArray(pix, q + stride);
        BYTES_16.cast(newQ2.convertShape(VectorOperators.S2B, B128, 0)).intoArray(pix, q + 2 * stride);
    }

    @Override
    public void lumaEdgeHorizontal(byte[] pix, int offset, int stride, int alpha, int beta, int tc0) {
        if (!Simd.WIDE) {
            super.lumaEdgeHorizontal(pix, offset, stride, alpha, beta, tc0);
            return;
        }
        lumaNormal16(pix, offset, stride, alpha, beta, tc0);
    }

    @Override
    public void lumaEdgeHorizontalStrong(byte[] pix, int offset, int stride, int alpha, int beta) {
        if (!Simd.WIDE) {
            super.lumaEdgeHorizontalStrong(pix, offset, stride, alpha, beta);
            return;
        }
        lumaStrong16(pix, offset, stride, alpha, beta);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation transposes the sixteen 8-sample rows around the edge into
     * sixteen-line columns ({@link #transposeRows}), filters the columns and writes the four
     * columns the filter can change back with one 32-bit store per row
     * ({@link #lumaNormalRows}). The two steps are separate methods exchanging the columns
     * through {@code scratch}, which keeps each compilation well inside the JIT compiler's
     * budgets.
     */
    @Override
    public void lumaEdgeVertical(byte[] pix, int offset, int stride, int alpha, int beta, int tc0, byte[] scratch) {
        if (!Simd.WIDE) {
            super.lumaEdgeVertical(pix, offset, stride, alpha, beta, tc0, scratch);
            return;
        }
        transposeRows(pix, offset - 4, pix, offset - 4 + 8 * stride, stride, scratch);
        lumaNormalRows(pix, offset - 2, stride, alpha, beta, tc0, scratch);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation transposes the rows as {@link #lumaEdgeVertical} does,
     * filters the columns ({@link #lumaStrongRows}) and writes every row's eight samples back
     * with one 64-bit store ({@link #lumaStrongOut}).
     */
    @Override
    public void lumaEdgeVerticalStrong(byte[] pix, int offset, int stride, int alpha, int beta, byte[] scratch) {
        if (!Simd.WIDE) {
            super.lumaEdgeVerticalStrong(pix, offset, stride, alpha, beta, scratch);
            return;
        }
        transposeRows(pix, offset - 4, pix, offset - 4 + 8 * stride, stride, scratch);
        if (lumaStrongRows(alpha, beta, scratch)) {
            lumaStrongOut(pix, offset - 4, stride, scratch);
        }
    }

    /**
     * Transposes sixteen rows of eight samples, the first eight from one array and the last eight
     * from another, into eight columns of sixteen samples.
     *
     * <p>Each row pair (row {@code i} of both halves) is loaded into one 16-lane vector with two
     * overlapping loads and a blend; zero-extension and shifts then interleave the rows of the
     * pairs byte-wise, the 32-bit lanes interleave them 16-bit-wise and the 64-bit lanes 32-bit-wise,
     * after which every 64-bit lane holds eight lines of one column, and one lane permutation per
     * vector joins each column's two halves. Column {@code c} (sample {@code c} of every row) is
     * stored as 16 bytes, line {@code i} in byte {@code i}, at
     * {@code scratch[32 * (c & 3) + 16 * (c >> 2)]}.
     *
     * @param upper the array of lines 0 to 7
     * @param upperOffset the index of the first sample of line 0
     * @param lower the array of lines 8 to 15
     * @param lowerOffset the index of the first sample of line 8; the 8 samples before it are
     * read too
     * @param stride the row stride of both arrays
     * @param scratch receives the columns
     */
    private static void transposeRows(byte[] upper, int upperOffset, byte[] lower, int lowerOffset, int stride,
                                      byte[] scratch) {
        var x0 = BYTES_16.cast(ByteVector.fromArray(B128, upper, upperOffset))
                .blend(BYTES_16.cast(ByteVector.fromArray(B128, lower, lowerOffset - 8)), SECOND_ROW);
        var x1 = BYTES_16.cast(ByteVector.fromArray(B128, upper, upperOffset + stride))
                .blend(BYTES_16.cast(ByteVector.fromArray(B128, lower, lowerOffset + stride - 8)), SECOND_ROW);
        var x2 = BYTES_16.cast(ByteVector.fromArray(B128, upper, upperOffset + 2 * stride))
                .blend(BYTES_16.cast(ByteVector.fromArray(B128, lower, lowerOffset + 2 * stride - 8)), SECOND_ROW);
        var x3 = BYTES_16.cast(ByteVector.fromArray(B128, upper, upperOffset + 3 * stride))
                .blend(BYTES_16.cast(ByteVector.fromArray(B128, lower, lowerOffset + 3 * stride - 8)), SECOND_ROW);
        var x4 = BYTES_16.cast(ByteVector.fromArray(B128, upper, upperOffset + 4 * stride))
                .blend(BYTES_16.cast(ByteVector.fromArray(B128, lower, lowerOffset + 4 * stride - 8)), SECOND_ROW);
        var x5 = BYTES_16.cast(ByteVector.fromArray(B128, upper, upperOffset + 5 * stride))
                .blend(BYTES_16.cast(ByteVector.fromArray(B128, lower, lowerOffset + 5 * stride - 8)), SECOND_ROW);
        var x6 = BYTES_16.cast(ByteVector.fromArray(B128, upper, upperOffset + 6 * stride))
                .blend(BYTES_16.cast(ByteVector.fromArray(B128, lower, lowerOffset + 6 * stride - 8)), SECOND_ROW);
        var x7 = BYTES_16.cast(ByteVector.fromArray(B128, upper, upperOffset + 7 * stride))
                .blend(BYTES_16.cast(ByteVector.fromArray(B128, lower, lowerOffset + 7 * stride - 8)), SECOND_ROW);
        var y0 = SHORTS_16.cast(x0.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                .lanewise(VectorOperators.OR, SHORTS_16.cast(x1.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.LSHL, 8));
        var y1 = SHORTS_16.cast(x2.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                .lanewise(VectorOperators.OR, SHORTS_16.cast(x3.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.LSHL, 8));
        var y2 = SHORTS_16.cast(x4.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                .lanewise(VectorOperators.OR, SHORTS_16.cast(x5.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.LSHL, 8));
        var y3 = SHORTS_16.cast(x6.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                .lanewise(VectorOperators.OR, SHORTS_16.cast(x7.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.LSHL, 8));
        var w0 = INTS_8.cast(y0.reinterpretShape(I256, 0));
        var w1 = INTS_8.cast(y1.reinterpretShape(I256, 0));
        var w2 = INTS_8.cast(y2.reinterpretShape(I256, 0));
        var w3 = INTS_8.cast(y3.reinterpretShape(I256, 0));
        var even01 = w0.lanewise(VectorOperators.AND, I256_LOW16).lanewise(VectorOperators.OR, w1.lanewise(VectorOperators.LSHL, 16));
        var odd01 = w0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.OR, w1.lanewise(VectorOperators.AND, I256_HIGH16));
        var even23 = w2.lanewise(VectorOperators.AND, I256_LOW16).lanewise(VectorOperators.OR, w3.lanewise(VectorOperators.LSHL, 16));
        var odd23 = w2.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.OR, w3.lanewise(VectorOperators.AND, I256_HIGH16));
        var e01 = LONGS_4.cast(even01.reinterpretShape(L256, 0));
        var e23 = LONGS_4.cast(even23.reinterpretShape(L256, 0));
        var o01 = LONGS_4.cast(odd01.reinterpretShape(L256, 0));
        var o23 = LONGS_4.cast(odd23.reinterpretShape(L256, 0));
        var c04 = e01.lanewise(VectorOperators.AND, L256_LOW32).lanewise(VectorOperators.OR, e23.lanewise(VectorOperators.LSHL, 32));
        var c26 = e01.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.OR, e23.lanewise(VectorOperators.AND, L256_HIGH32));
        var c15 = o01.lanewise(VectorOperators.AND, L256_LOW32).lanewise(VectorOperators.OR, o23.lanewise(VectorOperators.LSHL, 32));
        var c37 = o01.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.OR, o23.lanewise(VectorOperators.AND, L256_HIGH32));
        BYTES_32.cast(INTS_8.cast(c04.reinterpretShape(I256, 0)).rearrange(COLUMN_HALVES)
                .reinterpretShape(B256, 0)).intoArray(scratch, 0);
        BYTES_32.cast(INTS_8.cast(c15.reinterpretShape(I256, 0)).rearrange(COLUMN_HALVES)
                .reinterpretShape(B256, 0)).intoArray(scratch, 32);
        BYTES_32.cast(INTS_8.cast(c26.reinterpretShape(I256, 0)).rearrange(COLUMN_HALVES)
                .reinterpretShape(B256, 0)).intoArray(scratch, 64);
        BYTES_32.cast(INTS_8.cast(c37.reinterpretShape(I256, 0)).rearrange(COLUMN_HALVES)
                .reinterpretShape(B256, 0)).intoArray(scratch, 96);
    }

    /**
     * Filters the columns of a vertical luma edge transposed by {@link #transposeRows} with
     * boundary strengths below 4 and writes columns {@code p1}, {@code p0}, {@code q0} and
     * {@code q1} of every row back with one 32-bit store, unless no line passes the filter
     * conditions.
     *
     * @param pix the plane
     * @param out the index of the {@code p1} sample of the top row
     * @param stride the row stride
     * @param alpha the {@code alpha} threshold
     * @param beta the {@code beta} threshold
     * @param tc0 the packed per-segment {@code tC0}
     * @param scratch the transposed columns
     */
    private static void lumaNormalRows(byte[] pix, int out, int stride, int alpha, int beta, int tc0, byte[] scratch) {
        var p2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, 32)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, 64)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, 96)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, 16)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, 48)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, 80)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var tcBase = SHORTS_16.cast(BYTES_16.cast(INTS_4.cast(IntVector.broadcast(I128, tc0)).reinterpretShape(B128, 0))
                .rearrange(LUMA_TC_SPREAD).convertShape(VectorOperators.B2S, S256, 0));
        var b = SHORTS_16.cast(ShortVector.broadcast(S256, (short) beta));
        var mask = p0.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, ShortVector.broadcast(S256, (short) alpha))
                .and(p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, b))
                .and(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, b))
                .and(tcBase.compare(VectorOperators.GE, S256_0));
        if (!mask.anyTrue()) {
            return;
        }
        var ap = p2.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, b);
        var aq = q2.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, b);
        var tc = tcBase.lanewise(VectorOperators.SUB, ap.toVector()).lanewise(VectorOperators.SUB, aq.toVector());
        var delta = q0.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.LSHL, 2).lanewise(VectorOperators.ADD, p1.lanewise(VectorOperators.SUB, q1)).lanewise(VectorOperators.ADD, S256_4)
                .lanewise(VectorOperators.ASHR, 3).lanewise(VectorOperators.MAX, tc.lanewise(VectorOperators.NEG)).lanewise(VectorOperators.MIN, tc);
        var newP0 = p0.blend(p0.lanewise(VectorOperators.ADD, delta).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255), mask);
        var newQ0 = q0.blend(q0.lanewise(VectorOperators.SUB, delta).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255), mask);
        var average = p0.lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, S256_1).lanewise(VectorOperators.ASHR, 1);
        var negTc0 = tcBase.lanewise(VectorOperators.NEG);
        var newP1 = p1.blend(p1.lanewise(VectorOperators.ADD, p2.lanewise(VectorOperators.ADD, average).lanewise(VectorOperators.SUB, p1.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ASHR, 1).lanewise(VectorOperators.MAX, negTc0).lanewise(VectorOperators.MIN, tcBase)), mask.and(ap));
        var newQ1 = q1.blend(q1.lanewise(VectorOperators.ADD, q2.lanewise(VectorOperators.ADD, average).lanewise(VectorOperators.SUB, q1.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ASHR, 1).lanewise(VectorOperators.MAX, negTc0).lanewise(VectorOperators.MIN, tcBase)), mask.and(aq));
        var left = INTS_8.cast(newP1.lanewise(VectorOperators.OR, newP0.lanewise(VectorOperators.LSHL, 8)).reinterpretShape(I256, 0));
        var right = INTS_8.cast(newQ0.lanewise(VectorOperators.OR, newQ1.lanewise(VectorOperators.LSHL, 8)).reinterpretShape(I256, 0));
        var even = left.lanewise(VectorOperators.AND, I256_LOW16).lanewise(VectorOperators.OR, right.lanewise(VectorOperators.LSHL, 16));
        var odd = left.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.OR, right.lanewise(VectorOperators.AND, I256_HIGH16));
        INT_LE.set(pix, out, even.lane(0));
        INT_LE.set(pix, out + stride, odd.lane(0));
        INT_LE.set(pix, out + 2 * stride, even.lane(1));
        INT_LE.set(pix, out + 3 * stride, odd.lane(1));
        INT_LE.set(pix, out + 4 * stride, even.lane(2));
        INT_LE.set(pix, out + 5 * stride, odd.lane(2));
        INT_LE.set(pix, out + 6 * stride, even.lane(3));
        INT_LE.set(pix, out + 7 * stride, odd.lane(3));
        INT_LE.set(pix, out + 8 * stride, even.lane(4));
        INT_LE.set(pix, out + 9 * stride, odd.lane(4));
        INT_LE.set(pix, out + 10 * stride, even.lane(5));
        INT_LE.set(pix, out + 11 * stride, odd.lane(5));
        INT_LE.set(pix, out + 12 * stride, even.lane(6));
        INT_LE.set(pix, out + 13 * stride, odd.lane(6));
        INT_LE.set(pix, out + 14 * stride, even.lane(7));
        INT_LE.set(pix, out + 15 * stride, odd.lane(7));
    }

    /**
     * Filters the columns of a vertical luma edge transposed by {@link #transposeRows} with
     * boundary strength 4 and stores the eight result columns, paired into 16-bit lanes (column
     * {@code 2k} in the low byte, column {@code 2k + 1} in the high byte), as sixteen shorts at
     * {@code scratch[128 + 32 * k]}.
     *
     * @param alpha the {@code alpha} threshold
     * @param beta the {@code beta} threshold
     * @param scratch the transposed columns; receives the paired result columns
     * @return whether any sample changes
     */
    private static boolean lumaStrongRows(int alpha, int beta, byte[] scratch) {
        var p3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, 0)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, 32)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, 64)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, 96)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, 16)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, 48)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, 80)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, 112)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var b = SHORTS_16.cast(ShortVector.broadcast(S256, (short) beta));
        var difference = p0.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS);
        var mask = difference.compare(VectorOperators.LT, ShortVector.broadcast(S256, (short) alpha))
                .and(p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, b))
                .and(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, b));
        if (!mask.anyTrue()) {
            return false;
        }
        var strong = mask.and(difference.compare(VectorOperators.LT, ShortVector.broadcast(S256, (short) ((alpha >> 2) + 2))));
        var ap = strong.and(p2.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, b));
        var aq = strong.and(q2.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, b));
        var sum = p1.lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, q0);
        var p0Strong = p2.lanewise(VectorOperators.ADD, p1.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, p0.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ADD, q0.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, S256_4).lanewise(VectorOperators.ASHR, 3);
        var p1Strong = p2.lanewise(VectorOperators.ADD, sum).lanewise(VectorOperators.ADD, S256_2).lanewise(VectorOperators.ASHR, 2);
        var p2Strong = p3.lanewise(VectorOperators.LSHL, 1).lanewise(VectorOperators.ADD, p2.lanewise(VectorOperators.MUL, S256_3)).lanewise(VectorOperators.ADD, sum).lanewise(VectorOperators.ADD, S256_4)
                .lanewise(VectorOperators.ASHR, 3);
        var p0Weak = p1.lanewise(VectorOperators.LSHL, 1).lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, S256_2).lanewise(VectorOperators.ASHR, 2);
        var sumQ = q1.lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, p0);
        var q0Strong = p1.lanewise(VectorOperators.ADD, p0.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, q0.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ADD, q1.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, q2).lanewise(VectorOperators.ADD, S256_4).lanewise(VectorOperators.ASHR, 3);
        var q1Strong = q2.lanewise(VectorOperators.ADD, sumQ).lanewise(VectorOperators.ADD, S256_2).lanewise(VectorOperators.ASHR, 2);
        var q2Strong = q3.lanewise(VectorOperators.LSHL, 1).lanewise(VectorOperators.ADD, q2.lanewise(VectorOperators.MUL, S256_3)).lanewise(VectorOperators.ADD, sumQ).lanewise(VectorOperators.ADD, S256_4)
                .lanewise(VectorOperators.ASHR, 3);
        var q0Weak = q1.lanewise(VectorOperators.LSHL, 1).lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, p1).lanewise(VectorOperators.ADD, S256_2).lanewise(VectorOperators.ASHR, 2);
        var newP2 = p2.blend(p2Strong, ap);
        var newP1 = p1.blend(p1Strong, ap);
        var newP0 = p0.blend(p0Weak.blend(p0Strong, ap), mask);
        var newQ0 = q0.blend(q0Weak.blend(q0Strong, aq), mask);
        var newQ1 = q1.blend(q1Strong, aq);
        var newQ2 = q2.blend(q2Strong, aq);
        BYTES_32.cast(p3.lanewise(VectorOperators.OR, newP2.lanewise(VectorOperators.LSHL, 8)).reinterpretShape(B256, 0)).intoArray(scratch, 128);
        BYTES_32.cast(newP1.lanewise(VectorOperators.OR, newP0.lanewise(VectorOperators.LSHL, 8)).reinterpretShape(B256, 0)).intoArray(scratch, 160);
        BYTES_32.cast(newQ0.lanewise(VectorOperators.OR, newQ1.lanewise(VectorOperators.LSHL, 8)).reinterpretShape(B256, 0)).intoArray(scratch, 192);
        BYTES_32.cast(newQ2.lanewise(VectorOperators.OR, q3.lanewise(VectorOperators.LSHL, 8)).reinterpretShape(B256, 0)).intoArray(scratch, 224);
        return true;
    }

    /**
     * Writes the paired columns stored by {@link #lumaStrongRows} back to the plane, one 64-bit
     * store per row: the 32-bit lanes interleave the column pairs into the four samples on each
     * side of the edge, and the 64-bit lanes join both sides.
     *
     * @param pix the plane
     * @param row the index of the {@code p3} sample of the top row
     * @param stride the row stride
     * @param scratch the paired result columns
     */
    private static void lumaStrongOut(byte[] pix, int row, int stride, byte[] scratch) {
        var p32 = INTS_8.cast(BYTES_32.cast(ByteVector.fromArray(B256, scratch, 128)).reinterpretShape(I256, 0));
        var p10 = INTS_8.cast(BYTES_32.cast(ByteVector.fromArray(B256, scratch, 160)).reinterpretShape(I256, 0));
        var q01 = INTS_8.cast(BYTES_32.cast(ByteVector.fromArray(B256, scratch, 192)).reinterpretShape(I256, 0));
        var q23 = INTS_8.cast(BYTES_32.cast(ByteVector.fromArray(B256, scratch, 224)).reinterpretShape(I256, 0));
        var leftEven = p32.lanewise(VectorOperators.AND, I256_LOW16).lanewise(VectorOperators.OR, p10.lanewise(VectorOperators.LSHL, 16));
        var leftOdd = p32.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.OR, p10.lanewise(VectorOperators.AND, I256_HIGH16));
        var rightEven = q01.lanewise(VectorOperators.AND, I256_LOW16).lanewise(VectorOperators.OR, q23.lanewise(VectorOperators.LSHL, 16));
        var rightOdd = q01.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.OR, q23.lanewise(VectorOperators.AND, I256_HIGH16));
        var le = LONGS_4.cast(leftEven.reinterpretShape(L256, 0));
        var lo = LONGS_4.cast(leftOdd.reinterpretShape(L256, 0));
        var re = LONGS_4.cast(rightEven.reinterpretShape(L256, 0));
        var ro = LONGS_4.cast(rightOdd.reinterpretShape(L256, 0));
        var rows0 = le.lanewise(VectorOperators.AND, L256_LOW32).lanewise(VectorOperators.OR, re.lanewise(VectorOperators.LSHL, 32));
        var rows2 = le.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.OR, re.lanewise(VectorOperators.AND, L256_HIGH32));
        var rows1 = lo.lanewise(VectorOperators.AND, L256_LOW32).lanewise(VectorOperators.OR, ro.lanewise(VectorOperators.LSHL, 32));
        var rows3 = lo.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.OR, ro.lanewise(VectorOperators.AND, L256_HIGH32));
        LONG_LE.set(pix, row, rows0.lane(0));
        LONG_LE.set(pix, row + stride, rows1.lane(0));
        LONG_LE.set(pix, row + 2 * stride, rows2.lane(0));
        LONG_LE.set(pix, row + 3 * stride, rows3.lane(0));
        LONG_LE.set(pix, row + 4 * stride, rows0.lane(1));
        LONG_LE.set(pix, row + 5 * stride, rows1.lane(1));
        LONG_LE.set(pix, row + 6 * stride, rows2.lane(1));
        LONG_LE.set(pix, row + 7 * stride, rows3.lane(1));
        LONG_LE.set(pix, row + 8 * stride, rows0.lane(2));
        LONG_LE.set(pix, row + 9 * stride, rows1.lane(2));
        LONG_LE.set(pix, row + 10 * stride, rows2.lane(2));
        LONG_LE.set(pix, row + 11 * stride, rows3.lane(2));
        LONG_LE.set(pix, row + 12 * stride, rows0.lane(3));
        LONG_LE.set(pix, row + 13 * stride, rows1.lane(3));
        LONG_LE.set(pix, row + 14 * stride, rows2.lane(3));
        LONG_LE.set(pix, row + 15 * stride, rows3.lane(3));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation filters both planes at once: the eight Cb rows fill lanes
     * 0 to 7 and the eight Cr rows lanes 8 to 15. Chroma columns {@code -2..5} of the rows are
     * transposed together ({@link #transposeRows}), which serves both edges, and
     * {@link #chromaEdgeRows} filters each edge and writes back the two samples of every row it
     * can change with one 16-bit store.
     */
    @Override
    public void chromaEdgesVertical(byte[] cb, byte[] cr, int offset, int stride, int edges, int[] params,
                                    byte[] scratch) {
        if (!Simd.WIDE) {
            super.chromaEdgesVertical(cb, cr, offset, stride, edges, params, scratch);
            return;
        }
        transposeRows(cb, offset - 2, cr, offset - 2, stride, scratch);
        if ((edges & 1) != 0) {
            chromaEdgeRows(cb, cr, offset - 1, stride, params, 0, scratch, 0);
        }
        if ((edges & 2) != 0) {
            chromaEdgeRows(cb, cr, offset + 3, stride, params, 4, scratch, 16);
        }
    }

    /**
     * Filters one vertical chroma edge of both planes from the columns transposed by
     * {@link #transposeRows} and writes the two samples of every row the edge can change back
     * with one 16-bit store.
     *
     * @param cb the Cb plane
     * @param cr the Cr plane
     * @param out the index of the {@code p0} sample of the top row in both planes
     * @param stride the row stride of both planes
     * @param params the per-edge thresholds and packed {@code tC0}
     * @param at the index of the edge's first parameter
     * @param scratch the transposed columns
     * @param first the index of the edge's {@code p1} column in {@code scratch}: 0 for chroma
     * column -2, 16 for chroma column 2
     */
    private static void chromaEdgeRows(byte[] cb, byte[] cr, int out, int stride, int[] params, int at,
                                       byte[] scratch, int first) {
        var alpha = SHORTS_16.cast(ShortVector.broadcast(S256, (short) (params[at] & 0xFF)))
                .blend(ShortVector.broadcast(S256, (short) (params[at + 1] & 0xFF)), UPPER_HALF);
        var beta = SHORTS_16.cast(ShortVector.broadcast(S256, (short) ((params[at] >> 8) & 0xFF)))
                .blend(ShortVector.broadcast(S256, (short) ((params[at + 1] >> 8) & 0xFF)), UPPER_HALF);
        var tcBase = SHORTS_16.cast(BYTES_16.cast(LONGS_2.cast(LongVector.broadcast(L128,
                        (params[at + 2] & 0xFFFFFFFFL) | ((long) params[at + 3] << 32))).reinterpretShape(B128, 0))
                .rearrange(CHROMA_TC_SPREAD).convertShape(VectorOperators.B2S, S256, 0));
        var p1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, first)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, first + 32)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, first + 64)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, scratch, first + 96)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var mask = p0.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, alpha)
                .and(p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, beta))
                .and(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, beta))
                .and(tcBase.compare(VectorOperators.GE, S256_0));
        if (!mask.anyTrue()) {
            return;
        }
        var strong = tcBase.compare(VectorOperators.EQ, ShortVector.broadcast(S256, (short) CHROMA_STRONG));
        var tc = tcBase.lanewise(VectorOperators.ADD, S256_1);
        var delta = q0.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.LSHL, 2).lanewise(VectorOperators.ADD, p1.lanewise(VectorOperators.SUB, q1)).lanewise(VectorOperators.ADD, S256_4)
                .lanewise(VectorOperators.ASHR, 3).lanewise(VectorOperators.MAX, tc.lanewise(VectorOperators.NEG)).lanewise(VectorOperators.MIN, tc);
        var newP0 = p0.lanewise(VectorOperators.ADD, delta).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255);
        var newQ0 = q0.lanewise(VectorOperators.SUB, delta).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255);
        var strongP0 = p1.lanewise(VectorOperators.LSHL, 1).lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, S256_2).lanewise(VectorOperators.ASHR, 2);
        var strongQ0 = q1.lanewise(VectorOperators.LSHL, 1).lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, p1).lanewise(VectorOperators.ADD, S256_2).lanewise(VectorOperators.ASHR, 2);
        var outP0 = p0.blend(newP0.blend(strongP0, strong), mask);
        var outQ0 = q0.blend(newQ0.blend(strongQ0, strong), mask);
        var pairs = outP0.lanewise(VectorOperators.OR, outQ0.lanewise(VectorOperators.LSHL, 8));
        SHORT_LE.set(cb, out, pairs.lane(0));
        SHORT_LE.set(cb, out + 1 * stride, pairs.lane(1));
        SHORT_LE.set(cb, out + 2 * stride, pairs.lane(2));
        SHORT_LE.set(cb, out + 3 * stride, pairs.lane(3));
        SHORT_LE.set(cb, out + 4 * stride, pairs.lane(4));
        SHORT_LE.set(cb, out + 5 * stride, pairs.lane(5));
        SHORT_LE.set(cb, out + 6 * stride, pairs.lane(6));
        SHORT_LE.set(cb, out + 7 * stride, pairs.lane(7));
        SHORT_LE.set(cr, out, pairs.lane(8));
        SHORT_LE.set(cr, out + 1 * stride, pairs.lane(9));
        SHORT_LE.set(cr, out + 2 * stride, pairs.lane(10));
        SHORT_LE.set(cr, out + 3 * stride, pairs.lane(11));
        SHORT_LE.set(cr, out + 4 * stride, pairs.lane(12));
        SHORT_LE.set(cr, out + 5 * stride, pairs.lane(13));
        SHORT_LE.set(cr, out + 6 * stride, pairs.lane(14));
        SHORT_LE.set(cr, out + 7 * stride, pairs.lane(15));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation filters both planes at once, the Cb row in lanes 0 to 7 and
     * the Cr row in lanes 8 to 15, each vector built from two 64-bit row loads.
     */
    @Override
    public void chromaEdgeHorizontal(byte[] cb, byte[] cr, int offset, int stride, int[] params, int at) {
        if (!Simd.WIDE) {
            super.chromaEdgeHorizontal(cb, cr, offset, stride, params, at);
            return;
        }
        var p1 = SHORTS_16.cast(BYTES_16.cast(LONGS_2.cast(LongVector.zero(L128)).withLane(0, (long) LONG_LE.get(cb, offset - 2 * stride))
                .withLane(1, (long) LONG_LE.get(cr, offset - 2 * stride)).reinterpretShape(B128, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p0 = SHORTS_16.cast(BYTES_16.cast(LONGS_2.cast(LongVector.zero(L128)).withLane(0, (long) LONG_LE.get(cb, offset - stride))
                .withLane(1, (long) LONG_LE.get(cr, offset - stride)).reinterpretShape(B128, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q0 = SHORTS_16.cast(BYTES_16.cast(LONGS_2.cast(LongVector.zero(L128)).withLane(0, (long) LONG_LE.get(cb, offset))
                .withLane(1, (long) LONG_LE.get(cr, offset)).reinterpretShape(B128, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q1 = SHORTS_16.cast(BYTES_16.cast(LONGS_2.cast(LongVector.zero(L128)).withLane(0, (long) LONG_LE.get(cb, offset + stride))
                .withLane(1, (long) LONG_LE.get(cr, offset + stride)).reinterpretShape(B128, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var alpha = SHORTS_16.cast(ShortVector.broadcast(S256, (short) (params[at] & 0xFF)))
                .blend(ShortVector.broadcast(S256, (short) (params[at + 1] & 0xFF)), UPPER_HALF);
        var beta = SHORTS_16.cast(ShortVector.broadcast(S256, (short) ((params[at] >> 8) & 0xFF)))
                .blend(ShortVector.broadcast(S256, (short) ((params[at + 1] >> 8) & 0xFF)), UPPER_HALF);
        var tcBase = SHORTS_16.cast(BYTES_16.cast(LONGS_2.cast(LongVector.broadcast(L128,
                        (params[at + 2] & 0xFFFFFFFFL) | ((long) params[at + 3] << 32))).reinterpretShape(B128, 0))
                .rearrange(CHROMA_TC_SPREAD).convertShape(VectorOperators.B2S, S256, 0));
        var mask = p0.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, alpha)
                .and(p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, beta))
                .and(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LT, beta))
                .and(tcBase.compare(VectorOperators.GE, S256_0));
        if (!mask.anyTrue()) {
            return;
        }
        var strong = tcBase.compare(VectorOperators.EQ, ShortVector.broadcast(S256, (short) CHROMA_STRONG));
        var tc = tcBase.lanewise(VectorOperators.ADD, S256_1);
        var delta = q0.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.LSHL, 2).lanewise(VectorOperators.ADD, p1.lanewise(VectorOperators.SUB, q1)).lanewise(VectorOperators.ADD, S256_4)
                .lanewise(VectorOperators.ASHR, 3).lanewise(VectorOperators.MAX, tc.lanewise(VectorOperators.NEG)).lanewise(VectorOperators.MIN, tc);
        var newP0 = p0.lanewise(VectorOperators.ADD, delta).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255);
        var newQ0 = q0.lanewise(VectorOperators.SUB, delta).lanewise(VectorOperators.MAX, S256_0).lanewise(VectorOperators.MIN, S256_255);
        var strongP0 = p1.lanewise(VectorOperators.LSHL, 1).lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, S256_2).lanewise(VectorOperators.ASHR, 2);
        var strongQ0 = q1.lanewise(VectorOperators.LSHL, 1).lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, p1).lanewise(VectorOperators.ADD, S256_2).lanewise(VectorOperators.ASHR, 2);
        var outP0 = p0.blend(newP0.blend(strongP0, strong), mask);
        var outQ0 = q0.blend(newQ0.blend(strongQ0, strong), mask);
        var rowP0 = LONGS_2.cast(BYTES_16.cast(outP0.convertShape(VectorOperators.S2B, B128, 0)).reinterpretShape(L128, 0));
        var rowQ0 = LONGS_2.cast(BYTES_16.cast(outQ0.convertShape(VectorOperators.S2B, B128, 0)).reinterpretShape(L128, 0));
        LONG_LE.set(cb, offset - stride, rowP0.lane(0));
        LONG_LE.set(cr, offset - stride, rowP0.lane(1));
        LONG_LE.set(cb, offset, rowQ0.lane(0));
        LONG_LE.set(cr, offset, rowQ0.lane(1));
    }
}

