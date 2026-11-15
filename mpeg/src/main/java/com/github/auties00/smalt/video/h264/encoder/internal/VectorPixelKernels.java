package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.internal.simd.Simd;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.Vector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link PixelKernels} with the Vector API; the results equal the scalar kernels exactly.
 *
 * <p>Rows of 16 and 8 samples are one vector each; other widths fall back to the scalar kernels, and so do the
 * methods written for 256-bit vectors unless {@link Simd#WIDE} holds.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd Simd}
 * and computes the 4x4 Hadamard transforms of four (or two) adjacent blocks together, exchanging neighboring lanes by
 * shifts inside their 32-bit containers and an int permutation of their 64-bit containers, which is a single
 * instruction on AVX2 where lane rotations are not. The last butterfly stage folds into the absolute sum with
 * {@code |p + q| + |p - q| = 2 * max(|p|, |q|)}, and the sums accumulate in 16-bit lanes that provably stay below
 * 32768.
 */
final class VectorPixelKernels extends PixelKernels {
    /**
     * Sixteen bytes.
     */
    private static final VectorSpecies<Byte> B128 = ByteVector.SPECIES_128;

    /**
     * Eight bytes.
     */
    private static final VectorSpecies<Byte> B64 = ByteVector.SPECIES_64;

    /**
     * Sixteen shorts.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * Eight shorts.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * Eight ints.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

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
    private static final Class<IntVector> INTS_4 = vectorClass(IntVector.SPECIES_128);

    /**
     * Sixteen shorts equal to one.
     */
    private static final ShortVector ONE_256 = ShortVector.broadcast(S256, (short) 1);

    /**
     * Eight ints equal to {@code 0xFFFF}.
     */
    private static final IntVector LOW_HALVES = IntVector.broadcast(I256, 0xFFFF);

    /**
     * Sixteen zero shorts.
     */
    private static final ShortVector ZERO_S256 = ShortVector.zero(S256);

    /**
     * Eight zero shorts.
     */
    private static final ShortVector ZERO_S128 = ShortVector.zero(S128);

    /**
     * Eight zero ints.
     */
    private static final IntVector ZERO_I256 = IntVector.zero(I256);

    /**
     * Sixteen shorts equal to their lane index.
     */
    private static final ShortVector IOTA_S256 = ShortVector.zero(S256).addIndex(1);

    /**
     * Eight shorts equal to their lane index.
     */
    private static final ShortVector IOTA_S128 = ShortVector.zero(S128).addIndex(1);

    /**
     * Sixteen shorts equal to the largest sample value.
     */
    private static final ShortVector MAX_SAMPLE_S256 = ShortVector.broadcast(S256, (short) 255);

    /**
     * Eight shorts equal to the largest sample value.
     */
    private static final ShortVector MAX_SAMPLE_S128 = ShortVector.broadcast(S128, (short) 255);

    /**
     * Four ints equal to {@code 0xFFFF}.
     */
    private static final IntVector LOW_HALVES_128 = IntVector.broadcast(IntVector.SPECIES_128, 0xFFFF);

    /**
     * Eight ints equal to two.
     */
    private static final IntVector TWO_I256 = IntVector.broadcast(I256, 2);

    /**
     * The odd lanes of sixteen shorts.
     */
    private static final VectorMask<Short> ODD_256 = VectorMask.fromLong(S256, 0xAAAAL);

    /**
     * The odd lanes of eight shorts.
     */
    private static final VectorMask<Short> ODD_128 = VectorMask.fromLong(S128, 0xAAL);

    /**
     * Exchanges the two 64-bit halves of every 128-bit container of eight ints.
     */
    private static final VectorShuffle<Integer> SWAP_PAIRS_256 = VectorShuffle.fromValues(IntVector.SPECIES_256, 2, 3,
            0, 1, 6, 7, 4, 5);

    /**
     * Exchanges the two 32-bit halves of every 64-bit container of eight ints.
     */
    private static final VectorShuffle<Integer> SWAP_256 = VectorShuffle.fromValues(IntVector.SPECIES_256, 1, 0, 3, 2,
            5, 4, 7, 6);

    /**
     * Exchanges the two 32-bit halves of every 64-bit container of four ints.
     */
    private static final VectorShuffle<Integer> SWAP_128 = VectorShuffle.fromValues(IntVector.SPECIES_128, 1, 0, 3, 2);

    /**
     * Constructs the vector kernels.
     */
    VectorPixelKernels() {
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
    int sad(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride, int width, int height) {
        if (!Simd.WIDE) {
            return super.sad(a, aOffset, aStride, b, bOffset, bStride, width, height);
        }
        if (width == 16) {
            var acc = SHORTS_16.cast(ShortVector.zero(S256));
            for (var y = 0; y < height; y++) {
                var va = BYTES_16.cast(ByteVector.fromArray(B128, a, aOffset + y * aStride));
                var vb = BYTES_16.cast(ByteVector.fromArray(B128, b, bOffset + y * bStride));
                var diff = va.lanewise(VectorOperators.UMAX, vb).lanewise(VectorOperators.SUB, va.lanewise(VectorOperators.UMIN, vb));
                acc = SHORTS_16.cast(diff.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.ADD, acc);
            }
            var lanes = INTS_8.cast(ZERO_S256.lanewise(VectorOperators.ADD, acc).reinterpretShape(I256, 0));
            return lanes.lanewise(VectorOperators.AND, LOW_HALVES).lanewise(VectorOperators.ADD, lanes.lanewise(VectorOperators.LSHR, 16))
                    .reduceLanes(VectorOperators.ADD);
        }
        if (width == 8) {
            var acc = SHORTS_8.cast(ShortVector.zero(S128));
            for (var y = 0; y < height; y++) {
                var va = BYTES_8.cast(ByteVector.fromArray(B64, a, aOffset + y * aStride));
                var vb = BYTES_8.cast(ByteVector.fromArray(B64, b, bOffset + y * bStride));
                var diff = va.lanewise(VectorOperators.UMAX, vb).lanewise(VectorOperators.SUB, va.lanewise(VectorOperators.UMIN, vb));
                acc = SHORTS_8.cast(diff.convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.ADD, acc);
            }
            var lanes = INTS_4.cast(ZERO_S128.lanewise(VectorOperators.ADD, acc).reinterpretShape(IntVector.SPECIES_128, 0));
            return lanes.lanewise(VectorOperators.AND, LOW_HALVES_128).lanewise(VectorOperators.ADD, lanes.lanewise(VectorOperators.LSHR, 16))
                    .reduceLanes(VectorOperators.ADD);
        }
        return super.sad(a, aOffset, aStride, b, bOffset, bStride, width, height);
    }

    @Override
    long ssd(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride, int width, int height) {
        if (!Simd.WIDE) {
            return super.ssd(a, aOffset, aStride, b, bOffset, bStride, width, height);
        }
        if (width != 16 && width != 8) {
            return super.ssd(a, aOffset, aStride, b, bOffset, bStride, width, height);
        }
        if (width == 16) {
            var acc = INTS_8.cast(IntVector.zero(I256));
            for (var y = 0; y < height; y++) {
                var diff = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, aOffset + y * aStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                        .lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bOffset + y * bStride))
                                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
                var low = INTS_8.cast(diff.convertShape(VectorOperators.S2I, I256, 0));
                var high = INTS_8.cast(diff.convertShape(VectorOperators.S2I, I256, 1));
                acc = low.lanewise(VectorOperators.MUL, low).lanewise(VectorOperators.ADD, high.lanewise(VectorOperators.MUL, high))
                        .lanewise(VectorOperators.ADD, acc);
            }
            return ZERO_I256.lanewise(VectorOperators.ADD, acc).reduceLanesToLong(VectorOperators.ADD);
        }
        var acc = INTS_8.cast(IntVector.zero(I256));
        for (var y = 0; y < height; y++) {
            var diff = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, a, aOffset + y * aStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0))
                    .lanewise(VectorOperators.SUB, SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, b, bOffset + y * bStride))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)));
            var wide = INTS_8.cast(diff.convertShape(VectorOperators.S2I, I256, 0));
            acc = wide.lanewise(VectorOperators.MUL, wide).lanewise(VectorOperators.ADD, acc);
        }
        return ZERO_I256.lanewise(VectorOperators.ADD, acc).reduceLanesToLong(VectorOperators.ADD);
    }

    @Override
    int satd(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride, int width, int height) {
        if (!Simd.WIDE) {
            return super.satd(a, aOffset, aStride, b, bOffset, bStride, width, height);
        }
        if (width == 16) {
            return satd16(a, aOffset, aStride, b, bOffset, bStride, height);
        }
        if (width == 8) {
            return satd8(a, aOffset, aStride, b, bOffset, bStride, height);
        }
        return super.satd(a, aOffset, aStride, b, bOffset, bStride, width, height);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation runs {@link #differenceRows16} and {@link #hadamardQuadrants},
     * which exchange the vertically transformed rows through the scratch array.
     */
    @Override
    long satdQuadrants(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride, short[] scratch) {
        if (!Simd.WIDE) {
            return super.satdQuadrants(a, aOffset, aStride, b, bOffset, bStride, scratch);
        }
        differenceRows16(a, aOffset, aStride, b, bOffset, bStride, 16, scratch);
        return hadamardQuadrants(scratch, 2);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation runs {@link #averageDifferenceRows16} and
     * {@link #hadamardQuadrants}, which exchange the vertically transformed rows through the
     * scratch array, so the averaged prediction is never written to memory.
     */
    @Override
    long satdAverageQuadrants(byte[] src, int srcOffset, int srcStride, byte[] a, int aOffset, byte[] b, int bOffset,
                              int stride, short[] scratch) {
        if (!Simd.WIDE) {
            return super.satdAverageQuadrants(src, srcOffset, srcStride, a, aOffset, b, bOffset, stride, scratch);
        }
        averageDifferenceRows16(src, srcOffset, srcStride, a, aOffset, b, bOffset, stride, 16, scratch);
        return hadamardQuadrants(scratch, 2);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation runs {@link #averageDifferenceRows16} and
     * {@link #hadamardSum}, which exchange the vertically transformed rows through the scratch
     * array, so the averaged prediction is never written to memory.
     */
    @Override
    int satdAverage16(byte[] src, int srcOffset, int srcStride, byte[] a, int aOffset, byte[] b, int bOffset,
                      int stride, int height, short[] scratch) {
        if (!Simd.WIDE) {
            return super.satdAverage16(src, srcOffset, srcStride, a, aOffset, b, bOffset, stride, height, scratch);
        }
        if ((height & 7) != 0 || height > 16) {
            return super.satdAverage16(src, srcOffset, srcStride, a, aOffset, b, bOffset, stride, height, scratch);
        }
        averageDifferenceRows16(src, srcOffset, srcStride, a, aOffset, b, bOffset, stride, height, scratch);
        return hadamardSum(scratch, height) >> 1;
    }

    /**
     * Writes the vertical 4-point Hadamard butterflies of the differences of two blocks 16
     * samples wide, one group of four rows at a time: group {@code g} fills
     * {@code scratch[64 * g]} to {@code scratch[64 * g + 63]} with its four transformed rows.
     *
     * <p>This method or {@link #averageDifferenceRows16}, followed by {@link #hadamardQuadrants}
     * or {@link #hadamardSum}, split one SATD computation into two compilation units that
     * exchange the rows through memory.
     *
     * @implNote This implementation keeps the vertical transform apart from the horizontal
     * butterflies and the reduction because, compiled as one method, their Vector API calls come
     * close enough to the JIT compiler's node budget that in some JVMs part of them stays on the
     * allocating fallback. Both units are larger than the compiler's inlining limit for hot
     * methods, so each is compiled on its own rather than inside the motion search.
     *
     * @param a the first block's samples
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b the second block's samples
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @param rows the number of rows, a multiple of 4 up to 16
     * @param scratch receives the transformed rows
     */
    private static void differenceRows16(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride,
                                         int rows, short[] scratch) {
        for (var y = 0; y < rows; y += 4) {
            var ai = aOffset + y * aStride;
            var bi = bOffset + y * bStride;
            var d0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, ai)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bi)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var d1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, ai + aStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bi + bStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var d2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, ai + 2 * aStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bi + 2 * bStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var d3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, ai + 3 * aStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bi + 3 * bStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var s01 = d0.lanewise(VectorOperators.ADD, d1);
            var m01 = d0.lanewise(VectorOperators.SUB, d1);
            var s23 = d2.lanewise(VectorOperators.ADD, d3);
            var m23 = d2.lanewise(VectorOperators.SUB, d3);
            var row = y * 16;
            SHORTS_16.cast(s01.lanewise(VectorOperators.ADD, s23)).intoArray(scratch, row);
            SHORTS_16.cast(s01.lanewise(VectorOperators.SUB, s23)).intoArray(scratch, row + 16);
            SHORTS_16.cast(m01.lanewise(VectorOperators.SUB, m23)).intoArray(scratch, row + 32);
            SHORTS_16.cast(m01.lanewise(VectorOperators.ADD, m23)).intoArray(scratch, row + 48);
        }
    }

    /**
     * Writes the vertical 4-point Hadamard butterflies of the differences of a block 16 samples
     * wide and the rounded-up average {@code (a + b + 1) >> 1} of two others, laid out like
     * {@link #differenceRows16}.
     *
     * @implNote This implementation averages the two predictions in the 16-bit lanes the
     * differences are formed in, which costs two additions and a shift per row and no store; an
     * average formed on bytes needs byte shifts, which x86 processors lack.
     *
     * @param src the block's samples
     * @param srcOffset the index of the block's top-left sample
     * @param srcStride the block's row stride
     * @param a the first averaged block's samples
     * @param aOffset the index of the first averaged block's top-left sample
     * @param b the second averaged block's samples
     * @param bOffset the index of the second averaged block's top-left sample
     * @param stride the row stride of both averaged blocks
     * @param rows the number of rows, a multiple of 4 up to 16
     * @param scratch receives the transformed rows
     */
    private static void averageDifferenceRows16(byte[] src, int srcOffset, int srcStride, byte[] a, int aOffset,
                                                byte[] b, int bOffset, int stride, int rows, short[] scratch) {
        for (var y = 0; y < rows; y += 4) {
            var si = srcOffset + y * srcStride;
            var ai = aOffset + y * stride;
            var bi = bOffset + y * stride;
            var p0 = SHORTS_16.cast(SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, ai)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.ADD, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bi)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)))
                    .lanewise(VectorOperators.ADD, ONE_256)).lanewise(VectorOperators.LSHR, 1);
            var d0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, si)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, p0);
            var p1 = SHORTS_16.cast(SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, ai + stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.ADD, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bi + stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)))
                    .lanewise(VectorOperators.ADD, ONE_256)).lanewise(VectorOperators.LSHR, 1);
            var d1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, si + srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, p1);
            var p2 = SHORTS_16.cast(SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, ai + 2 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.ADD, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bi + 2 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)))
                    .lanewise(VectorOperators.ADD, ONE_256)).lanewise(VectorOperators.LSHR, 1);
            var d2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, si + 2 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, p2);
            var p3 = SHORTS_16.cast(SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, ai + 3 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.ADD, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bi + 3 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)))
                    .lanewise(VectorOperators.ADD, ONE_256)).lanewise(VectorOperators.LSHR, 1);
            var d3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, si + 3 * srcStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, p3);
            var s01 = d0.lanewise(VectorOperators.ADD, d1);
            var m01 = d0.lanewise(VectorOperators.SUB, d1);
            var s23 = d2.lanewise(VectorOperators.ADD, d3);
            var m23 = d2.lanewise(VectorOperators.SUB, d3);
            var row = y * 16;
            SHORTS_16.cast(s01.lanewise(VectorOperators.ADD, s23)).intoArray(scratch, row);
            SHORTS_16.cast(s01.lanewise(VectorOperators.SUB, s23)).intoArray(scratch, row + 16);
            SHORTS_16.cast(m01.lanewise(VectorOperators.SUB, m23)).intoArray(scratch, row + 32);
            SHORTS_16.cast(m01.lanewise(VectorOperators.ADD, m23)).intoArray(scratch, row + 48);
        }
    }

    /**
     * Completes the SATDs of the rows {@link #differenceRows16} or
     * {@link #averageDifferenceRows16} wrote: applies the horizontal butterflies to every row and
     * adds up the absolute coefficients of each 8x8 quadrant.
     *
     * @param scratch the transformed rows, two groups of four per 8-row half
     * @param halves the number of 8-row halves, 1 or 2
     * @return the unhalved SATDs of the quadrants packed as by {@link #satdQuadrants}, the fields
     * of a missing half zero
     */
    private static long hadamardQuadrants(short[] scratch, int halves) {
        var packed = 0L;
        for (var half = 0; half < halves; half++) {
            var acc = SHORTS_16.cast(ShortVector.zero(S256));
            for (var group = 0; group < 2; group++) {
                var row = (half * 2 + group) * 64;
                var w0 = INTS_8.cast(SHORTS_16.cast(ShortVector.fromArray(S256, scratch, row)).reinterpretShape(I256, 0));
                var t0 = SHORTS_16.cast(w0.lanewise(VectorOperators.ADD, w0.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S256, 0))
                        .blend(SHORTS_16.cast(w0.lanewise(VectorOperators.SUB, w0.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S256, 0)), ODD_256)
                        .lanewise(VectorOperators.ABS);
                var w1 = INTS_8.cast(SHORTS_16.cast(ShortVector.fromArray(S256, scratch, row + 16)).reinterpretShape(I256, 0));
                var t1 = SHORTS_16.cast(w1.lanewise(VectorOperators.ADD, w1.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S256, 0))
                        .blend(SHORTS_16.cast(w1.lanewise(VectorOperators.SUB, w1.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S256, 0)), ODD_256)
                        .lanewise(VectorOperators.ABS);
                var w2 = INTS_8.cast(SHORTS_16.cast(ShortVector.fromArray(S256, scratch, row + 32)).reinterpretShape(I256, 0));
                var t2 = SHORTS_16.cast(w2.lanewise(VectorOperators.ADD, w2.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S256, 0))
                        .blend(SHORTS_16.cast(w2.lanewise(VectorOperators.SUB, w2.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S256, 0)), ODD_256)
                        .lanewise(VectorOperators.ABS);
                var w3 = INTS_8.cast(SHORTS_16.cast(ShortVector.fromArray(S256, scratch, row + 48)).reinterpretShape(I256, 0));
                var t3 = SHORTS_16.cast(w3.lanewise(VectorOperators.ADD, w3.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S256, 0))
                        .blend(SHORTS_16.cast(w3.lanewise(VectorOperators.SUB, w3.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S256, 0)), ODD_256)
                        .lanewise(VectorOperators.ABS);
                var e = t0.lanewise(VectorOperators.MAX, SHORTS_16.cast(INTS_8.cast(t0.reinterpretShape(I256, 0)).rearrange(SWAP_256).reinterpretShape(S256, 0))).lanewise(VectorOperators.ADD, t1.lanewise(VectorOperators.MAX, SHORTS_16.cast(INTS_8.cast(t1.reinterpretShape(I256, 0)).rearrange(SWAP_256).reinterpretShape(S256, 0)))).lanewise(VectorOperators.ADD, t2.lanewise(VectorOperators.MAX, SHORTS_16.cast(INTS_8.cast(t2.reinterpretShape(I256, 0)).rearrange(SWAP_256).reinterpretShape(S256, 0)))).lanewise(VectorOperators.ADD, t3.lanewise(VectorOperators.MAX, SHORTS_16.cast(INTS_8.cast(t3.reinterpretShape(I256, 0)).rearrange(SWAP_256).reinterpretShape(S256, 0))));
                acc = e.lanewise(VectorOperators.ADD, acc);
            }
            var lanes = INTS_8.cast(ZERO_S256.lanewise(VectorOperators.ADD, acc).reinterpretShape(I256, 0));
            var sums = lanes.lanewise(VectorOperators.AND, LOW_HALVES).lanewise(VectorOperators.ADD, lanes.lanewise(VectorOperators.LSHR, 16));
            var pairs = sums.lanewise(VectorOperators.ADD, sums.rearrange(SWAP_256));
            var quads = pairs.lanewise(VectorOperators.ADD, pairs.rearrange(SWAP_PAIRS_256));
            packed |= ((quads.lane(0) & 0xFFFFL) | ((quads.lane(4) & 0xFFFFL) << 16)) << (32 * half);
        }
        return packed;
    }

    /**
     * Completes the SATD of the rows {@link #averageDifferenceRows16} wrote: applies the
     * horizontal butterflies to every row and adds up all absolute coefficients.
     *
     * @implNote This implementation accumulates the coefficients of every group in the same
     * 16-bit lanes and reduces them once, as {@link #satd16} does; each lane's sum stays below
     * {@code 65536}, since the absolute Hadamard coefficients of a 4x4 block of differences add
     * up to at most {@code 16 * 4 * 255}.
     *
     * @param scratch the transformed rows
     * @param rows the number of rows, a multiple of 4 up to 16
     * @return the unhalved SATD
     */
    private static int hadamardSum(short[] scratch, int rows) {
        var acc = SHORTS_16.cast(ShortVector.zero(S256));
        for (var y = 0; y < rows; y += 4) {
            var row = y * 16;
            var w0 = INTS_8.cast(SHORTS_16.cast(ShortVector.fromArray(S256, scratch, row)).reinterpretShape(I256, 0));
            var t0 = SHORTS_16.cast(w0.lanewise(VectorOperators.ADD, w0.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S256, 0))
                    .blend(SHORTS_16.cast(w0.lanewise(VectorOperators.SUB, w0.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S256, 0)), ODD_256)
                    .lanewise(VectorOperators.ABS);
            var w1 = INTS_8.cast(SHORTS_16.cast(ShortVector.fromArray(S256, scratch, row + 16)).reinterpretShape(I256, 0));
            var t1 = SHORTS_16.cast(w1.lanewise(VectorOperators.ADD, w1.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S256, 0))
                    .blend(SHORTS_16.cast(w1.lanewise(VectorOperators.SUB, w1.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S256, 0)), ODD_256)
                    .lanewise(VectorOperators.ABS);
            var w2 = INTS_8.cast(SHORTS_16.cast(ShortVector.fromArray(S256, scratch, row + 32)).reinterpretShape(I256, 0));
            var t2 = SHORTS_16.cast(w2.lanewise(VectorOperators.ADD, w2.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S256, 0))
                    .blend(SHORTS_16.cast(w2.lanewise(VectorOperators.SUB, w2.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S256, 0)), ODD_256)
                    .lanewise(VectorOperators.ABS);
            var w3 = INTS_8.cast(SHORTS_16.cast(ShortVector.fromArray(S256, scratch, row + 48)).reinterpretShape(I256, 0));
            var t3 = SHORTS_16.cast(w3.lanewise(VectorOperators.ADD, w3.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S256, 0))
                    .blend(SHORTS_16.cast(w3.lanewise(VectorOperators.SUB, w3.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S256, 0)), ODD_256)
                    .lanewise(VectorOperators.ABS);
            var e = t0.lanewise(VectorOperators.MAX, SHORTS_16.cast(INTS_8.cast(t0.reinterpretShape(I256, 0)).rearrange(SWAP_256).reinterpretShape(S256, 0))).lanewise(VectorOperators.ADD, t1.lanewise(VectorOperators.MAX, SHORTS_16.cast(INTS_8.cast(t1.reinterpretShape(I256, 0)).rearrange(SWAP_256).reinterpretShape(S256, 0)))).lanewise(VectorOperators.ADD, t2.lanewise(VectorOperators.MAX, SHORTS_16.cast(INTS_8.cast(t2.reinterpretShape(I256, 0)).rearrange(SWAP_256).reinterpretShape(S256, 0)))).lanewise(VectorOperators.ADD, t3.lanewise(VectorOperators.MAX, SHORTS_16.cast(INTS_8.cast(t3.reinterpretShape(I256, 0)).rearrange(SWAP_256).reinterpretShape(S256, 0))));
            acc = e.lanewise(VectorOperators.ADD, acc);
        }
        var lanes = INTS_8.cast(ZERO_S256.lanewise(VectorOperators.ADD, acc).reinterpretShape(I256, 0));
        return lanes.lanewise(VectorOperators.AND, LOW_HALVES).lanewise(VectorOperators.ADD, lanes.lanewise(VectorOperators.LSHR, 16))
                .reduceLanes(VectorOperators.ADD);
    }

    /**
     * Returns the SATD of a block 16 samples wide; the horizontal butterflies exchange lanes by
     * rotating their 32- and 64-bit containers, and the code is written out without helper
     * calls so the JIT compiles it as one intrinsified unit.
     *
     * @param a the first block's samples
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b the second block's samples
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @param height the block height, a multiple of 4 up to 16
     * @return the SATD
     */
    private static int satd16(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride, int height) {
        var acc = SHORTS_16.cast(ShortVector.zero(S256));
        for (var y = 0; y < height; y += 4) {
            var ai = aOffset + y * aStride;
            var bi = bOffset + y * bStride;
            var d0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, ai)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bi)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var d1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, ai + aStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bi + bStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var d2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, ai + 2 * aStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bi + 2 * bStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var d3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, ai + 3 * aStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.SUB, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bi + 3 * bStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)));
            var s01 = d0.lanewise(VectorOperators.ADD, d1);
            var m01 = d0.lanewise(VectorOperators.SUB, d1);
            var s23 = d2.lanewise(VectorOperators.ADD, d3);
            var m23 = d2.lanewise(VectorOperators.SUB, d3);
            var w0 = INTS_8.cast(s01.lanewise(VectorOperators.ADD, s23).reinterpretShape(I256, 0));
            var t0 = SHORTS_16.cast(w0.lanewise(VectorOperators.ADD, w0.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S256, 0))
                    .blend(SHORTS_16.cast(w0.lanewise(VectorOperators.SUB, w0.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S256, 0)), ODD_256)
                    .lanewise(VectorOperators.ABS);
            var w1 = INTS_8.cast(s01.lanewise(VectorOperators.SUB, s23).reinterpretShape(I256, 0));
            var t1 = SHORTS_16.cast(w1.lanewise(VectorOperators.ADD, w1.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S256, 0))
                    .blend(SHORTS_16.cast(w1.lanewise(VectorOperators.SUB, w1.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S256, 0)), ODD_256)
                    .lanewise(VectorOperators.ABS);
            var w2 = INTS_8.cast(m01.lanewise(VectorOperators.SUB, m23).reinterpretShape(I256, 0));
            var t2 = SHORTS_16.cast(w2.lanewise(VectorOperators.ADD, w2.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S256, 0))
                    .blend(SHORTS_16.cast(w2.lanewise(VectorOperators.SUB, w2.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S256, 0)), ODD_256)
                    .lanewise(VectorOperators.ABS);
            var w3 = INTS_8.cast(m01.lanewise(VectorOperators.ADD, m23).reinterpretShape(I256, 0));
            var t3 = SHORTS_16.cast(w3.lanewise(VectorOperators.ADD, w3.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S256, 0))
                    .blend(SHORTS_16.cast(w3.lanewise(VectorOperators.SUB, w3.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S256, 0)), ODD_256)
                    .lanewise(VectorOperators.ABS);
            var e = t0.lanewise(VectorOperators.MAX, SHORTS_16.cast(INTS_8.cast(t0.reinterpretShape(I256, 0)).rearrange(SWAP_256).reinterpretShape(S256, 0))).lanewise(VectorOperators.ADD, t1.lanewise(VectorOperators.MAX, SHORTS_16.cast(INTS_8.cast(t1.reinterpretShape(I256, 0)).rearrange(SWAP_256).reinterpretShape(S256, 0)))).lanewise(VectorOperators.ADD, t2.lanewise(VectorOperators.MAX, SHORTS_16.cast(INTS_8.cast(t2.reinterpretShape(I256, 0)).rearrange(SWAP_256).reinterpretShape(S256, 0)))).lanewise(VectorOperators.ADD, t3.lanewise(VectorOperators.MAX, SHORTS_16.cast(INTS_8.cast(t3.reinterpretShape(I256, 0)).rearrange(SWAP_256).reinterpretShape(S256, 0))));
            acc = e.lanewise(VectorOperators.ADD, acc);
        }
        var lanes = INTS_8.cast(ZERO_S256.lanewise(VectorOperators.ADD, acc).reinterpretShape(I256, 0));
        return lanes.lanewise(VectorOperators.AND, LOW_HALVES).lanewise(VectorOperators.ADD, lanes.lanewise(VectorOperators.LSHR, 16))
                .reduceLanes(VectorOperators.ADD) >> 1;
    }

    /**
     * Returns the SATD of a block 8 samples wide.
     *
     * @param a the first block's samples
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b the second block's samples
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @param height the block height, a multiple of 4 up to 16
     * @return the SATD
     */
    private static int satd8(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride, int height) {
        var acc = SHORTS_8.cast(ShortVector.zero(S128));
        for (var y = 0; y < height; y += 4) {
            var ai = aOffset + y * aStride;
            var bi = bOffset + y * bStride;
            var d0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, a, ai)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.SUB, SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, b, bi)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)));
            var d1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, a, ai + aStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.SUB, SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, b, bi + bStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)));
            var d2 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, a, ai + 2 * aStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.SUB, SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, b, bi + 2 * bStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)));
            var d3 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, a, ai + 3 * aStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.SUB, SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, b, bi + 3 * bStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)));
            var s01 = d0.lanewise(VectorOperators.ADD, d1);
            var m01 = d0.lanewise(VectorOperators.SUB, d1);
            var s23 = d2.lanewise(VectorOperators.ADD, d3);
            var m23 = d2.lanewise(VectorOperators.SUB, d3);
            var w0 = INTS_4.cast(s01.lanewise(VectorOperators.ADD, s23).reinterpretShape(IntVector.SPECIES_128, 0));
            var t0 = SHORTS_8.cast(w0.lanewise(VectorOperators.ADD, w0.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(w0.lanewise(VectorOperators.SUB, w0.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var w1 = INTS_4.cast(s01.lanewise(VectorOperators.SUB, s23).reinterpretShape(IntVector.SPECIES_128, 0));
            var t1 = SHORTS_8.cast(w1.lanewise(VectorOperators.ADD, w1.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(w1.lanewise(VectorOperators.SUB, w1.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var w2 = INTS_4.cast(m01.lanewise(VectorOperators.SUB, m23).reinterpretShape(IntVector.SPECIES_128, 0));
            var t2 = SHORTS_8.cast(w2.lanewise(VectorOperators.ADD, w2.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(w2.lanewise(VectorOperators.SUB, w2.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var w3 = INTS_4.cast(m01.lanewise(VectorOperators.ADD, m23).reinterpretShape(IntVector.SPECIES_128, 0));
            var t3 = SHORTS_8.cast(w3.lanewise(VectorOperators.ADD, w3.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(w3.lanewise(VectorOperators.SUB, w3.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var e = t0.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(t0.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0))).lanewise(VectorOperators.ADD, t1.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(t1.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0)))).lanewise(VectorOperators.ADD, t2.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(t2.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0)))).lanewise(VectorOperators.ADD, t3.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(t3.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0))));
            acc = e.lanewise(VectorOperators.ADD, acc);
        }
        var lanes = INTS_4.cast(ZERO_S128.lanewise(VectorOperators.ADD, acc).reinterpretShape(IntVector.SPECIES_128, 0));
        return lanes.lanewise(VectorOperators.AND, LOW_HALVES_128).lanewise(VectorOperators.ADD, lanes.lanewise(VectorOperators.LSHR, 16))
                .reduceLanes(VectorOperators.ADD) >> 1;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation runs the Hadamard transform of each prediction in its own
     * method, forming the prediction by broadcasting instead of writing it to memory, and each
     * method transforms the block as two 8x4 halves in a loop, reloading the rows in every
     * iteration. A single method holding the three transforms, or a straight-line one holding
     * both halves, is so large that the JIT compilers can give up on it or exhaust their node
     * budget, leaving its vector operations on their allocating fallback.
     */
    @Override
    int intraSatd8x8(byte[] src, int offset, int stride, int dc, boolean top, boolean left) {
        var best = intraSatdDc(src, offset, stride, dc);
        if (top) {
            best = Math.min(best, intraSatdTop(src, offset, stride));
        }
        if (left) {
            best = Math.min(best, intraSatdLeft(src, offset, stride));
        }
        return best;
    }

    /**
     * Returns the SATD of an 8x8 block against its DC prediction.
     *
     * @param src the samples
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @param dc the DC prediction value
     * @return the SATD
     */
    private static int intraSatdDc(byte[] src, int offset, int stride, int dc) {
        var sum = 0;
        for (var half = 0; half < 2; half++) {
            var o = offset + 4 * half * stride;
            var r0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, o)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, o + stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r2 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, o + 2 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r3 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, o + 3 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var dcRow = SHORTS_8.cast(ShortVector.broadcast(S128, (short) dc));
            var dc0 = r0.lanewise(VectorOperators.SUB, dcRow);
            var dc1 = r1.lanewise(VectorOperators.SUB, dcRow);
            var dc2 = r2.lanewise(VectorOperators.SUB, dcRow);
            var dc3 = r3.lanewise(VectorOperators.SUB, dcRow);
            var sdc001 = dc0.lanewise(VectorOperators.ADD, dc1);
            var mdc001 = dc0.lanewise(VectorOperators.SUB, dc1);
            var sdc023 = dc2.lanewise(VectorOperators.ADD, dc3);
            var mdc023 = dc2.lanewise(VectorOperators.SUB, dc3);
            var wdc00 = INTS_4.cast(sdc001.lanewise(VectorOperators.ADD, sdc023).reinterpretShape(IntVector.SPECIES_128, 0));
            var tdc00 = SHORTS_8.cast(wdc00.lanewise(VectorOperators.ADD, wdc00.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(wdc00.lanewise(VectorOperators.SUB, wdc00.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var wdc01 = INTS_4.cast(sdc001.lanewise(VectorOperators.SUB, sdc023).reinterpretShape(IntVector.SPECIES_128, 0));
            var tdc01 = SHORTS_8.cast(wdc01.lanewise(VectorOperators.ADD, wdc01.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(wdc01.lanewise(VectorOperators.SUB, wdc01.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var wdc02 = INTS_4.cast(mdc001.lanewise(VectorOperators.SUB, mdc023).reinterpretShape(IntVector.SPECIES_128, 0));
            var tdc02 = SHORTS_8.cast(wdc02.lanewise(VectorOperators.ADD, wdc02.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(wdc02.lanewise(VectorOperators.SUB, wdc02.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var wdc03 = INTS_4.cast(mdc001.lanewise(VectorOperators.ADD, mdc023).reinterpretShape(IntVector.SPECIES_128, 0));
            var tdc03 = SHORTS_8.cast(wdc03.lanewise(VectorOperators.ADD, wdc03.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(wdc03.lanewise(VectorOperators.SUB, wdc03.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var edc0 = tdc00.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(tdc00.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0))).lanewise(VectorOperators.ADD, tdc01.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(tdc01.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0)))).lanewise(VectorOperators.ADD, tdc02.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(tdc02.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0)))).lanewise(VectorOperators.ADD, tdc03.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(tdc03.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0))));
            var lanes = INTS_4.cast(edc0.reinterpretShape(IntVector.SPECIES_128, 0));
            sum += lanes.lanewise(VectorOperators.AND, LOW_HALVES_128)
                    .lanewise(VectorOperators.ADD, lanes.lanewise(VectorOperators.LSHR, 16))
                    .reduceLanes(VectorOperators.ADD);
        }
        return sum >> 1;
    }

    /**
     * Returns the SATD of an 8x8 block against its vertical prediction, the 8 samples above the
     * block repeated on every row.
     *
     * @param src the samples, with the row above the block
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @return the SATD
     */
    private static int intraSatdTop(byte[] src, int offset, int stride) {
        var sum = 0;
        for (var half = 0; half < 2; half++) {
            var o = offset + 4 * half * stride;
            var r0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, o)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, o + stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r2 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, o + 2 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r3 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, o + 3 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var topRow = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, offset - stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var tp0 = r0.lanewise(VectorOperators.SUB, topRow);
            var tp1 = r1.lanewise(VectorOperators.SUB, topRow);
            var tp2 = r2.lanewise(VectorOperators.SUB, topRow);
            var tp3 = r3.lanewise(VectorOperators.SUB, topRow);
            var stp001 = tp0.lanewise(VectorOperators.ADD, tp1);
            var mtp001 = tp0.lanewise(VectorOperators.SUB, tp1);
            var stp023 = tp2.lanewise(VectorOperators.ADD, tp3);
            var mtp023 = tp2.lanewise(VectorOperators.SUB, tp3);
            var wtp00 = INTS_4.cast(stp001.lanewise(VectorOperators.ADD, stp023).reinterpretShape(IntVector.SPECIES_128, 0));
            var ttp00 = SHORTS_8.cast(wtp00.lanewise(VectorOperators.ADD, wtp00.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(wtp00.lanewise(VectorOperators.SUB, wtp00.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var wtp01 = INTS_4.cast(stp001.lanewise(VectorOperators.SUB, stp023).reinterpretShape(IntVector.SPECIES_128, 0));
            var ttp01 = SHORTS_8.cast(wtp01.lanewise(VectorOperators.ADD, wtp01.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(wtp01.lanewise(VectorOperators.SUB, wtp01.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var wtp02 = INTS_4.cast(mtp001.lanewise(VectorOperators.SUB, mtp023).reinterpretShape(IntVector.SPECIES_128, 0));
            var ttp02 = SHORTS_8.cast(wtp02.lanewise(VectorOperators.ADD, wtp02.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(wtp02.lanewise(VectorOperators.SUB, wtp02.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var wtp03 = INTS_4.cast(mtp001.lanewise(VectorOperators.ADD, mtp023).reinterpretShape(IntVector.SPECIES_128, 0));
            var ttp03 = SHORTS_8.cast(wtp03.lanewise(VectorOperators.ADD, wtp03.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(wtp03.lanewise(VectorOperators.SUB, wtp03.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var etp0 = ttp00.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(ttp00.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0))).lanewise(VectorOperators.ADD, ttp01.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(ttp01.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0)))).lanewise(VectorOperators.ADD, ttp02.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(ttp02.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0)))).lanewise(VectorOperators.ADD, ttp03.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(ttp03.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0))));
            var lanes = INTS_4.cast(etp0.reinterpretShape(IntVector.SPECIES_128, 0));
            sum += lanes.lanewise(VectorOperators.AND, LOW_HALVES_128)
                    .lanewise(VectorOperators.ADD, lanes.lanewise(VectorOperators.LSHR, 16))
                    .reduceLanes(VectorOperators.ADD);
        }
        return sum >> 1;
    }

    /**
     * Returns the SATD of an 8x8 block against its horizontal prediction, the sample left of each
     * row repeated along the row.
     *
     * @param src the samples, with the column left of the block
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @return the SATD
     */
    private static int intraSatdLeft(byte[] src, int offset, int stride) {
        var sum = 0;
        for (var half = 0; half < 2; half++) {
            var o = offset + 4 * half * stride;
            var r0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, o)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, o + stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r2 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, o + 2 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var r3 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, o + 3 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var lf0 = r0.lanewise(VectorOperators.SUB, ShortVector.broadcast(S128, (short) (src[o - 1] & 0xFF)));
            var lf1 = r1.lanewise(VectorOperators.SUB, ShortVector.broadcast(S128, (short) (src[o + stride - 1] & 0xFF)));
            var lf2 = r2.lanewise(VectorOperators.SUB, ShortVector.broadcast(S128, (short) (src[o + 2 * stride - 1] & 0xFF)));
            var lf3 = r3.lanewise(VectorOperators.SUB, ShortVector.broadcast(S128, (short) (src[o + 3 * stride - 1] & 0xFF)));
            var slf001 = lf0.lanewise(VectorOperators.ADD, lf1);
            var mlf001 = lf0.lanewise(VectorOperators.SUB, lf1);
            var slf023 = lf2.lanewise(VectorOperators.ADD, lf3);
            var mlf023 = lf2.lanewise(VectorOperators.SUB, lf3);
            var wlf00 = INTS_4.cast(slf001.lanewise(VectorOperators.ADD, slf023).reinterpretShape(IntVector.SPECIES_128, 0));
            var tlf00 = SHORTS_8.cast(wlf00.lanewise(VectorOperators.ADD, wlf00.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(wlf00.lanewise(VectorOperators.SUB, wlf00.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var wlf01 = INTS_4.cast(slf001.lanewise(VectorOperators.SUB, slf023).reinterpretShape(IntVector.SPECIES_128, 0));
            var tlf01 = SHORTS_8.cast(wlf01.lanewise(VectorOperators.ADD, wlf01.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(wlf01.lanewise(VectorOperators.SUB, wlf01.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var wlf02 = INTS_4.cast(mlf001.lanewise(VectorOperators.SUB, mlf023).reinterpretShape(IntVector.SPECIES_128, 0));
            var tlf02 = SHORTS_8.cast(wlf02.lanewise(VectorOperators.ADD, wlf02.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(wlf02.lanewise(VectorOperators.SUB, wlf02.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var wlf03 = INTS_4.cast(mlf001.lanewise(VectorOperators.ADD, mlf023).reinterpretShape(IntVector.SPECIES_128, 0));
            var tlf03 = SHORTS_8.cast(wlf03.lanewise(VectorOperators.ADD, wlf03.lanewise(VectorOperators.LSHR, 16)).reinterpretShape(S128, 0))
                    .blend(SHORTS_8.cast(wlf03.lanewise(VectorOperators.SUB, wlf03.lanewise(VectorOperators.LSHL, 16)).reinterpretShape(S128, 0)), ODD_128)
                    .lanewise(VectorOperators.ABS);
            var elf0 = tlf00.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(tlf00.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0))).lanewise(VectorOperators.ADD, tlf01.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(tlf01.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0)))).lanewise(VectorOperators.ADD, tlf02.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(tlf02.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0)))).lanewise(VectorOperators.ADD, tlf03.lanewise(VectorOperators.MAX, SHORTS_8.cast(INTS_4.cast(tlf03.reinterpretShape(IntVector.SPECIES_128, 0)).rearrange(SWAP_128).reinterpretShape(S128, 0))));
            var lanes = INTS_4.cast(elf0.reinterpretShape(IntVector.SPECIES_128, 0));
            sum += lanes.lanewise(VectorOperators.AND, LOW_HALVES_128)
                    .lanewise(VectorOperators.ADD, lanes.lanewise(VectorOperators.LSHR, 16))
                    .reduceLanes(VectorOperators.ADD);
        }
        return sum >> 1;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation adds the two input rows as 16-bit lanes and sums horizontal
     * pairs by viewing each pair of lanes as one 32-bit lane, eight output samples at a time.
     */
    @Override
    void downscale(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int width,
                   int rows) {
        if (!Simd.WIDE) {
            super.downscale(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, rows);
            return;
        }
        for (var y = 0; y < rows; y++) {
            var s0 = srcOffset + 2 * y * srcStride;
            var s1 = s0 + srcStride;
            var d = dstOffset + y * dstStride;
            var x = 0;
            for (; x + 8 <= width; x += 8) {
                var top = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s0 + 2 * x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var pairs = INTS_8.cast(top.lanewise(VectorOperators.ADD, SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, s1 + 2 * x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))).reinterpretShape(I256, 0));
                var sums = INTS_8.cast(pairs.lanewise(VectorOperators.AND, LOW_HALVES)
                        .lanewise(VectorOperators.ADD, pairs.lanewise(VectorOperators.LSHR, 16))
                        .lanewise(VectorOperators.ADD, TWO_I256))
                        .lanewise(VectorOperators.LSHR, 2);
                BYTES_8.cast(sums.convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, d + x);
            }
            for (; x < width; x++) {
                dst[d + x] = (byte) (((src[s0 + 2 * x] & 0xFF) + (src[s0 + 2 * x + 1] & 0xFF)
                        + (src[s1 + 2 * x] & 0xFF) + (src[s1 + 2 * x + 1] & 0xFF) + 2) >> 2);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation moves rows 16 or 8 samples wide as one vector each, which is
     * cheaper than a {@link System#arraycopy} call per row for such short rows.
     */
    @Override
    void copy(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int width, int height) {
        if (width == 16) {
            for (var y = 0; y < height; y++) {
                BYTES_16.cast(ByteVector.fromArray(B128, src, srcOffset + y * srcStride)).intoArray(dst, dstOffset + y * dstStride);
            }
        } else if (width == 8) {
            for (var y = 0; y < height; y++) {
                BYTES_8.cast(ByteVector.fromArray(B64, src, srcOffset + y * srcStride)).intoArray(dst, dstOffset + y * dstStride);
            }
        } else {
            super.copy(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation averages two rows per loop iteration. Besides halving the
     * loop overhead, this keeps the method larger than the JIT compiler's inlining limit for hot
     * methods: the motion search calls it from large methods, and a kernel inlined into a
     * compilation that has already exhausted most of the compiler's node budget can be left with
     * part of its vector operations on their allocating fallback, while a kernel compiled on its
     * own is small enough to be intrinsified completely.
     */
    @Override
    void average(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride,
                 byte[] dst, int dstOffset, int dstStride, int width, int height) {
        if (width == 16) {
            var y = 0;
            for (; y + 2 <= height; y += 2) {
                var va = BYTES_16.cast(ByteVector.fromArray(B128, a, aOffset + y * aStride));
                var vb = BYTES_16.cast(ByteVector.fromArray(B128, b, bOffset + y * bStride));
                BYTES_16.cast(va.lanewise(VectorOperators.OR, vb)
                        .lanewise(VectorOperators.SUB, BYTES_16.cast(va.lanewise(VectorOperators.XOR, vb)).lanewise(VectorOperators.LSHR, 1)))
                        .intoArray(dst, dstOffset + y * dstStride);
                var wa = BYTES_16.cast(ByteVector.fromArray(B128, a, aOffset + (y + 1) * aStride));
                var wb = BYTES_16.cast(ByteVector.fromArray(B128, b, bOffset + (y + 1) * bStride));
                BYTES_16.cast(wa.lanewise(VectorOperators.OR, wb)
                        .lanewise(VectorOperators.SUB, BYTES_16.cast(wa.lanewise(VectorOperators.XOR, wb)).lanewise(VectorOperators.LSHR, 1)))
                        .intoArray(dst, dstOffset + (y + 1) * dstStride);
            }
            if (y < height) {
                var va = BYTES_16.cast(ByteVector.fromArray(B128, a, aOffset + y * aStride));
                var vb = BYTES_16.cast(ByteVector.fromArray(B128, b, bOffset + y * bStride));
                BYTES_16.cast(va.lanewise(VectorOperators.OR, vb)
                        .lanewise(VectorOperators.SUB, BYTES_16.cast(va.lanewise(VectorOperators.XOR, vb)).lanewise(VectorOperators.LSHR, 1)))
                        .intoArray(dst, dstOffset + y * dstStride);
            }
        } else if (width == 8) {
            var y = 0;
            for (; y + 2 <= height; y += 2) {
                var va = BYTES_8.cast(ByteVector.fromArray(B64, a, aOffset + y * aStride));
                var vb = BYTES_8.cast(ByteVector.fromArray(B64, b, bOffset + y * bStride));
                BYTES_8.cast(va.lanewise(VectorOperators.OR, vb)
                        .lanewise(VectorOperators.SUB, BYTES_8.cast(va.lanewise(VectorOperators.XOR, vb)).lanewise(VectorOperators.LSHR, 1)))
                        .intoArray(dst, dstOffset + y * dstStride);
                var wa = BYTES_8.cast(ByteVector.fromArray(B64, a, aOffset + (y + 1) * aStride));
                var wb = BYTES_8.cast(ByteVector.fromArray(B64, b, bOffset + (y + 1) * bStride));
                BYTES_8.cast(wa.lanewise(VectorOperators.OR, wb)
                        .lanewise(VectorOperators.SUB, BYTES_8.cast(wa.lanewise(VectorOperators.XOR, wb)).lanewise(VectorOperators.LSHR, 1)))
                        .intoArray(dst, dstOffset + (y + 1) * dstStride);
            }
            if (y < height) {
                var va = BYTES_8.cast(ByteVector.fromArray(B64, a, aOffset + y * aStride));
                var vb = BYTES_8.cast(ByteVector.fromArray(B64, b, bOffset + y * bStride));
                BYTES_8.cast(va.lanewise(VectorOperators.OR, vb)
                        .lanewise(VectorOperators.SUB, BYTES_8.cast(va.lanewise(VectorOperators.XOR, vb)).lanewise(VectorOperators.LSHR, 1)))
                        .intoArray(dst, dstOffset + y * dstStride);
            }
        } else {
            super.average(a, aOffset, aStride, b, bOffset, bStride, dst, dstOffset, dstStride, width, height);
        }
    }

    @Override
    long sumAndSquares(byte[] src, int offset, int stride, int width, int height) {
        if (!Simd.WIDE) {
            return super.sumAndSquares(src, offset, stride, width, height);
        }
        if (width != 8 && width != 16) {
            return super.sumAndSquares(src, offset, stride, width, height);
        }
        if (width == 16) {
            var sums = INTS_8.cast(IntVector.zero(I256));
            var squares = INTS_8.cast(IntVector.zero(I256));
            for (var y = 0; y < height; y++) {
                var row = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, offset + y * stride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var low = INTS_8.cast(row.convertShape(VectorOperators.S2I, I256, 0));
                var high = INTS_8.cast(row.convertShape(VectorOperators.S2I, I256, 1));
                sums = low.lanewise(VectorOperators.ADD, high).lanewise(VectorOperators.ADD, sums);
                squares = low.lanewise(VectorOperators.MUL, low).lanewise(VectorOperators.ADD, high.lanewise(VectorOperators.MUL, high))
                        .lanewise(VectorOperators.ADD, squares);
            }
            return ZERO_I256.lanewise(VectorOperators.ADD, sums).reduceLanes(VectorOperators.ADD)
                    | (ZERO_I256.lanewise(VectorOperators.ADD, squares).reduceLanesToLong(VectorOperators.ADD) << 32);
        }
        var sums = INTS_8.cast(IntVector.zero(I256));
        var squares = INTS_8.cast(IntVector.zero(I256));
        for (var y = 0; y < height; y++) {
            var wide = INTS_8.cast(SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, offset + y * stride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).convertShape(VectorOperators.S2I, I256, 0));
            sums = wide.lanewise(VectorOperators.ADD, sums);
            squares = wide.lanewise(VectorOperators.MUL, wide).lanewise(VectorOperators.ADD, squares);
        }
        return ZERO_I256.lanewise(VectorOperators.ADD, sums).reduceLanes(VectorOperators.ADD)
                | (ZERO_I256.lanewise(VectorOperators.ADD, squares).reduceLanesToLong(VectorOperators.ADD) << 32);
    }

    @Override
    void planePrediction(byte[] dst, int offset, int stride, int size, int a, int b, int c, int center) {
        if (!Simd.WIDE) {
            super.planePrediction(dst, offset, stride, size, a, b, c, center);
            return;
        }
        if (size == 16) {
            var ramp = IOTA_S256.lanewise(VectorOperators.MUL, ShortVector.broadcast(S256, (short) b));
            for (var y = 0; y < 16; y++) {
                var row = SHORTS_16.cast(SHORTS_16.cast(ramp).lanewise(VectorOperators.ADD, ShortVector.broadcast(S256, (short) (a + c * (y - center) - b * center + 16))))
                        .lanewise(VectorOperators.ASHR, 5)
                        .lanewise(VectorOperators.MAX, ZERO_S256)
                        .lanewise(VectorOperators.MIN, MAX_SAMPLE_S256);
                BYTES_16.cast(row.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, offset + y * stride);
            }
        } else if (size == 8) {
            var ramp = IOTA_S128.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) b));
            for (var y = 0; y < 8; y++) {
                var row = SHORTS_8.cast(SHORTS_8.cast(ramp).lanewise(VectorOperators.ADD, ShortVector.broadcast(S128, (short) (a + c * (y - center) - b * center + 16))))
                        .lanewise(VectorOperators.ASHR, 5)
                        .lanewise(VectorOperators.MAX, ZERO_S128)
                        .lanewise(VectorOperators.MIN, MAX_SAMPLE_S128);
                BYTES_8.cast(row.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, offset + y * stride);
            }
        } else {
            super.planePrediction(dst, offset, stride, size, a, b, c, center);
        }
    }
}
