package com.github.auties00.smalt.image.ops;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link ResampleKernel} with the Vector API; the output equals {@link ScalarResampleKernel} sample for
 * sample.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd}; it
 * makes every tap one multiply-add of a whole vector by a broadcast weight, with no horizontal reduction: the vertical
 * pass runs across the samples of a row with eight accumulators per iteration, the horizontal pass across the rows of a
 * lane-interleaved block.
 */
final class VectorResampleKernel implements ResampleKernel {
    /**
     * The widest integer species: 256 bits when the hardware has them, else 128 bits.
     */
    private static final VectorSpecies<Integer> I = IntVector.SPECIES_PREFERRED.vectorBitSize() >= 256
            ? IntVector.SPECIES_256 : IntVector.SPECIES_128;

    /**
     * The number of lanes of {@link #I}.
     */
    private static final int L = I.length();

    /**
     * The 128-bit integer species, holding the sums of four pixels during unpremultiplication.
     */
    private static final VectorSpecies<Integer> I4 = IntVector.SPECIES_128;

    /**
     * The byte species used to load 8 samples, of which the first {@link #L} are used.
     */
    private static final VectorSpecies<Byte> B8 = ByteVector.SPECIES_64;

    /**
     * The short species holding {@link #L} 16-bit samples.
     */
    private static final VectorSpecies<Short> S = VectorSpecies.of(short.class, VectorShape.forBitSize(L * 16));

    /**
     * The byte species holding {@link #L} packed RGBA pixels.
     */
    private static final VectorSpecies<Byte> BP = VectorSpecies.of(byte.class, VectorShape.forBitSize(I.vectorBitSize()));

    /**
     * The byte species holding four packed RGBA pixels.
     */
    private static final VectorSpecies<Byte> BP4 = ByteVector.SPECIES_128;

    /**
     * The double species holding four values, or {@code null} when 256-bit vectors are not
     * supported by the hardware.
     */
    private static final VectorSpecies<Double> D4 = DoubleVector.SPECIES_PREFERRED.length() >= 4 ? DoubleVector.SPECIES_256 : null;

    /**
     * The exact class of the vectors of {@link #I}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(I);

    /**
     * The exact class of the vectors of {@link #I4}.
     */
    private static final Class<IntVector> INT4_VECTOR = vectorClass(I4);

    /**
     * The exact class of the vectors of {@link #B8}.
     */
    private static final Class<ByteVector> SAMPLE_VECTOR = vectorClass(B8);

    /**
     * The exact class of the vectors of {@link #S}.
     */
    private static final Class<ShortVector> SHORT_VECTOR = vectorClass(S);

    /**
     * The exact class of the vectors of {@link #BP}.
     */
    private static final Class<ByteVector> PIXEL_VECTOR = vectorClass(BP);

    /**
     * The exact class of the vectors of {@link #BP4}.
     */
    private static final Class<ByteVector> PIXEL4_VECTOR = vectorClass(BP4);

    /**
     * The exact class of the vectors of {@link #D4}, or {@code null} without it.
     */
    private static final Class<DoubleVector> DOUBLE_VECTOR = D4 == null ? null : vectorClass(D4);

    /**
     * Repeats each lane of the low half twice: lane {@code j} takes lane {@code j / 2}.
     */
    private static final VectorShuffle<Integer> SPREAD_LOW = spread(false);

    /**
     * Repeats each lane of the high half twice: lane {@code j} takes lane
     * {@code L / 2 + j / 2}.
     */
    private static final VectorShuffle<Integer> SPREAD_HIGH = spread(true);

    /**
     * Selects the odd lanes.
     */
    private static final VectorMask<Integer> ODD_LANES = VectorMask.fromLong(I, 0xAAAAAAAAL & ((1L << L) - 1));

    /**
     * Zero in every lane.
     */
    private static final IntVector ZERO = IntVector.zero(I);

    /**
     * The largest sample value in every lane.
     */
    private static final IntVector MAX_SAMPLE = IntVector.broadcast(I, 255);

    /**
     * The low byte of every lane.
     */
    private static final IntVector LOW_BYTE = IntVector.broadcast(I, 0xFF);

    /**
     * An opaque alpha byte in the top byte of every lane.
     */
    private static final IntVector OPAQUE_ALPHA = IntVector.broadcast(I, 0xFF000000);

    /**
     * Zero in every lane of {@link #I4}.
     */
    private static final IntVector ZERO4 = IntVector.zero(I4);

    /**
     * The largest sample value in every lane of {@link #I4}.
     */
    private static final IntVector MAX_SAMPLE4 = IntVector.broadcast(I4, 255);

    /**
     * The margin added before truncating a reciprocal-multiplied quotient, which makes it exact
     * for the operand ranges of {@link #unpremultiply}.
     */
    private static final double QUOTIENT_MARGIN = 0x1p-40;

    /**
     * The scalar kernel handling the samples the vector loops do not cover.
     */
    private final ScalarResampleKernel scalar;

    /**
     * Constructs the kernel.
     */
    private VectorResampleKernel() {
        this.scalar = new ScalarResampleKernel();
    }

    /**
     * Creates the kernel.
     *
     * @return the vector kernel
     */
    static ResampleKernel create() {
        return new VectorResampleKernel();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the vector species whose class to return
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    /**
     * Builds the shuffle that repeats each lane of one half of a vector twice.
     *
     * @param high whether to spread the high half
     * @return the shuffle
     */
    private static VectorShuffle<Integer> spread(boolean high) {
        var indexes = new int[L];
        for (var j = 0; j < L; j++) {
            indexes[j] = j / 2 + (high ? L / 2 : 0);
        }
        return VectorShuffle.fromArray(I, indexes, 0);
    }

    @Override
    public int lanes() {
        return L;
    }

    @Override
    public void vertical(byte[] src, int[] offsets, int[] weights, int wOff, int taps, int length, int[] dst, int shift) {
        var half = INT_VECTOR.cast(IntVector.broadcast(I, 1 << (shift - 1)));
        var x = 0;
        for (; x + 7 * L + 8 <= length; x += 8 * L) {
            var a0 = half;
            var a1 = half;
            var a2 = half;
            var a3 = half;
            var a4 = half;
            var a5 = half;
            var a6 = half;
            var a7 = half;
            for (var k = 0; k < taps; k++) {
                var weight = INT_VECTOR.cast(IntVector.broadcast(I, weights[wOff + k]));
                var offset = offsets[k] + x;
                a0 = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, offset))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0))
                        .lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, a0);
                a1 = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, offset + L))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0))
                        .lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, a1);
                a2 = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, offset + 2 * L))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0))
                        .lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, a2);
                a3 = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, offset + 3 * L))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0))
                        .lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, a3);
                a4 = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, offset + 4 * L))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0))
                        .lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, a4);
                a5 = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, offset + 5 * L))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0))
                        .lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, a5);
                a6 = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, offset + 6 * L))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0))
                        .lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, a6);
                a7 = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, offset + 7 * L))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0))
                        .lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, a7);
            }
            INT_VECTOR.cast(INT_VECTOR.cast(a0).lanewise(VectorOperators.ASHR, shift)).intoArray(dst, x);
            INT_VECTOR.cast(INT_VECTOR.cast(a1).lanewise(VectorOperators.ASHR, shift)).intoArray(dst, x + L);
            INT_VECTOR.cast(INT_VECTOR.cast(a2).lanewise(VectorOperators.ASHR, shift)).intoArray(dst, x + 2 * L);
            INT_VECTOR.cast(INT_VECTOR.cast(a3).lanewise(VectorOperators.ASHR, shift)).intoArray(dst, x + 3 * L);
            INT_VECTOR.cast(INT_VECTOR.cast(a4).lanewise(VectorOperators.ASHR, shift)).intoArray(dst, x + 4 * L);
            INT_VECTOR.cast(INT_VECTOR.cast(a5).lanewise(VectorOperators.ASHR, shift)).intoArray(dst, x + 5 * L);
            INT_VECTOR.cast(INT_VECTOR.cast(a6).lanewise(VectorOperators.ASHR, shift)).intoArray(dst, x + 6 * L);
            INT_VECTOR.cast(INT_VECTOR.cast(a7).lanewise(VectorOperators.ASHR, shift)).intoArray(dst, x + 7 * L);
        }
        for (; x + 3 * L + 8 <= length; x += 4 * L) {
            var a0 = half;
            var a1 = half;
            var a2 = half;
            var a3 = half;
            for (var k = 0; k < taps; k++) {
                var weight = INT_VECTOR.cast(IntVector.broadcast(I, weights[wOff + k]));
                var offset = offsets[k] + x;
                a0 = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, offset))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0))
                        .lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, a0);
                a1 = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, offset + L))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0))
                        .lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, a1);
                a2 = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, offset + 2 * L))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0))
                        .lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, a2);
                a3 = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, offset + 3 * L))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0))
                        .lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, a3);
            }
            INT_VECTOR.cast(INT_VECTOR.cast(a0).lanewise(VectorOperators.ASHR, shift)).intoArray(dst, x);
            INT_VECTOR.cast(INT_VECTOR.cast(a1).lanewise(VectorOperators.ASHR, shift)).intoArray(dst, x + L);
            INT_VECTOR.cast(INT_VECTOR.cast(a2).lanewise(VectorOperators.ASHR, shift)).intoArray(dst, x + 2 * L);
            INT_VECTOR.cast(INT_VECTOR.cast(a3).lanewise(VectorOperators.ASHR, shift)).intoArray(dst, x + 3 * L);
        }
        for (; x + 8 <= length; x += L) {
            var a0 = half;
            for (var k = 0; k < taps; k++) {
                var weight = INT_VECTOR.cast(IntVector.broadcast(I, weights[wOff + k]));
                a0 = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, offsets[k] + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0))
                        .lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, a0);
            }
            INT_VECTOR.cast(INT_VECTOR.cast(a0).lanewise(VectorOperators.ASHR, shift)).intoArray(dst, x);
        }
        scalar.verticalRange(src, offsets, weights, wOff, taps, dst, shift, x, length);
    }

    @Override
    public void verticalWide(char[] src, int[] offsets, int[] weights, int wOff, int taps, int length, int[] dst,
                             boolean accumulate, int shift) {
        var x = 0;
        var round = INT_VECTOR.cast(IntVector.broadcast(I, shift > 0 ? 1 << (shift - 1) : 0));
        for (; x + 2 * L <= length; x += 2 * L) {
            var a0 = accumulate ? INT_VECTOR.cast(IntVector.fromArray(I, dst, x)) : INT_VECTOR.cast(IntVector.zero(I));
            var a1 = accumulate ? INT_VECTOR.cast(IntVector.fromArray(I, dst, x + L)) : INT_VECTOR.cast(IntVector.zero(I));
            for (var k = 0; k < taps; k++) {
                var weight = INT_VECTOR.cast(IntVector.broadcast(I, weights[wOff + k]));
                var offset = offsets[k] + x;
                a0 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromCharArray(S, src, offset))
                        .convertShape(VectorOperators.ZERO_EXTEND_S2I, I, 0))
                        .lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, a0);
                a1 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromCharArray(S, src, offset + L))
                        .convertShape(VectorOperators.ZERO_EXTEND_S2I, I, 0))
                        .lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, a1);
            }
            if (shift > 0) {
                INT_VECTOR.cast(INT_VECTOR.cast(INT_VECTOR.cast(round).lanewise(VectorOperators.ADD, a0))
                        .lanewise(VectorOperators.ASHR, shift))
                        .intoArray(dst, x);
                INT_VECTOR.cast(INT_VECTOR.cast(INT_VECTOR.cast(round).lanewise(VectorOperators.ADD, a1))
                        .lanewise(VectorOperators.ASHR, shift))
                        .intoArray(dst, x + L);
            } else {
                INT_VECTOR.cast(a0).intoArray(dst, x);
                INT_VECTOR.cast(a1).intoArray(dst, x + L);
            }
        }
        scalar.verticalWideRange(src, offsets, weights, wOff, taps, dst, accumulate, shift, x, length);
    }

    @Override
    public void gatherRows(int[][] rows, int rowCount, int offset, int pixelStride, int length, int lanes, int[] dst) {
        if (L != 8 || rowCount != 8 || pixelStride != 1) {
            scalar.gatherRows(rows, rowCount, offset, pixelStride, length, lanes, dst);
            return;
        }
        var x = 0;
        for (; x + 8 <= length; x += 8) {
            gatherTile(rows, offset + x, dst, 8 * x);
        }
        for (; x < length; x++) {
            for (var r = 0; r < 8; r++) {
                dst[x * 8 + r] = rows[r][offset + x];
            }
        }
    }

    /**
     * Transposes one 8x8 tile of a block of 8 rows into lane-interleaved order.
     *
     * <p>Three rounds interleave row {@code k} with row {@code k + 4}; each rotates the bits of the
     * row and column indices by one position, so together they swap them.
     *
     * @param rows the 8 rows
     * @param offset the index of the tile's first column in every row
     * @param dst the lane-interleaved destination
     * @param dOff the index of the tile's first value in the destination
     */
    private static void gatherTile(int[][] rows, int offset, int[] dst, int dOff) {
        var r0 = INT_VECTOR.cast(IntVector.fromArray(I, rows[0], offset));
        var r1 = INT_VECTOR.cast(IntVector.fromArray(I, rows[1], offset));
        var r2 = INT_VECTOR.cast(IntVector.fromArray(I, rows[2], offset));
        var r3 = INT_VECTOR.cast(IntVector.fromArray(I, rows[3], offset));
        var r4 = INT_VECTOR.cast(IntVector.fromArray(I, rows[4], offset));
        var r5 = INT_VECTOR.cast(IntVector.fromArray(I, rows[5], offset));
        var r6 = INT_VECTOR.cast(IntVector.fromArray(I, rows[6], offset));
        var r7 = INT_VECTOR.cast(IntVector.fromArray(I, rows[7], offset));
        var s0 = r0.rearrange(SPREAD_LOW).blend(r4.rearrange(SPREAD_LOW), ODD_LANES);
        var s1 = r0.rearrange(SPREAD_HIGH).blend(r4.rearrange(SPREAD_HIGH), ODD_LANES);
        var s2 = r1.rearrange(SPREAD_LOW).blend(r5.rearrange(SPREAD_LOW), ODD_LANES);
        var s3 = r1.rearrange(SPREAD_HIGH).blend(r5.rearrange(SPREAD_HIGH), ODD_LANES);
        var s4 = r2.rearrange(SPREAD_LOW).blend(r6.rearrange(SPREAD_LOW), ODD_LANES);
        var s5 = r2.rearrange(SPREAD_HIGH).blend(r6.rearrange(SPREAD_HIGH), ODD_LANES);
        var s6 = r3.rearrange(SPREAD_LOW).blend(r7.rearrange(SPREAD_LOW), ODD_LANES);
        var s7 = r3.rearrange(SPREAD_HIGH).blend(r7.rearrange(SPREAD_HIGH), ODD_LANES);
        var t0 = s0.rearrange(SPREAD_LOW).blend(s4.rearrange(SPREAD_LOW), ODD_LANES);
        var t1 = s0.rearrange(SPREAD_HIGH).blend(s4.rearrange(SPREAD_HIGH), ODD_LANES);
        var t2 = s1.rearrange(SPREAD_LOW).blend(s5.rearrange(SPREAD_LOW), ODD_LANES);
        var t3 = s1.rearrange(SPREAD_HIGH).blend(s5.rearrange(SPREAD_HIGH), ODD_LANES);
        var t4 = s2.rearrange(SPREAD_LOW).blend(s6.rearrange(SPREAD_LOW), ODD_LANES);
        var t5 = s2.rearrange(SPREAD_HIGH).blend(s6.rearrange(SPREAD_HIGH), ODD_LANES);
        var t6 = s3.rearrange(SPREAD_LOW).blend(s7.rearrange(SPREAD_LOW), ODD_LANES);
        var t7 = s3.rearrange(SPREAD_HIGH).blend(s7.rearrange(SPREAD_HIGH), ODD_LANES);
        var u0 = t0.rearrange(SPREAD_LOW).blend(t4.rearrange(SPREAD_LOW), ODD_LANES);
        var u1 = t0.rearrange(SPREAD_HIGH).blend(t4.rearrange(SPREAD_HIGH), ODD_LANES);
        var u2 = t1.rearrange(SPREAD_LOW).blend(t5.rearrange(SPREAD_LOW), ODD_LANES);
        var u3 = t1.rearrange(SPREAD_HIGH).blend(t5.rearrange(SPREAD_HIGH), ODD_LANES);
        var u4 = t2.rearrange(SPREAD_LOW).blend(t6.rearrange(SPREAD_LOW), ODD_LANES);
        var u5 = t2.rearrange(SPREAD_HIGH).blend(t6.rearrange(SPREAD_HIGH), ODD_LANES);
        var u6 = t3.rearrange(SPREAD_LOW).blend(t7.rearrange(SPREAD_LOW), ODD_LANES);
        var u7 = t3.rearrange(SPREAD_HIGH).blend(t7.rearrange(SPREAD_HIGH), ODD_LANES);
        INT_VECTOR.cast(u0).intoArray(dst, dOff);
        INT_VECTOR.cast(u1).intoArray(dst, dOff + 8);
        INT_VECTOR.cast(u2).intoArray(dst, dOff + 16);
        INT_VECTOR.cast(u3).intoArray(dst, dOff + 24);
        INT_VECTOR.cast(u4).intoArray(dst, dOff + 32);
        INT_VECTOR.cast(u5).intoArray(dst, dOff + 40);
        INT_VECTOR.cast(u6).intoArray(dst, dOff + 48);
        INT_VECTOR.cast(u7).intoArray(dst, dOff + 56);
    }

    @Override
    public void horizontalRows(int[] src, int lanes, int[] starts, int[] counts, int[] weights, int stride, int count,
                               byte[] dst, int shift) {
        var half = INT_VECTOR.cast(IntVector.broadcast(I, 1 << (shift - 1)));
        for (var i = 0; i < count; i++) {
            var base = starts[i] * L;
            var w = i * stride;
            var taps = counts[i];
            var acc = half;
            for (var k = 0; k < taps; k++) {
                acc = INT_VECTOR.cast(IntVector.fromArray(I, src, base + k * L))
                        .lanewise(VectorOperators.MUL, INT_VECTOR.cast(IntVector.broadcast(I, weights[w + k])))
                        .lanewise(VectorOperators.ADD, acc);
            }
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(INT_VECTOR.cast(acc).lanewise(VectorOperators.ASHR, shift)
                            .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE))
                    .convertShape(VectorOperators.I2B, B8, 0)).intoArray(dst, i * L);
        }
    }

    @Override
    public void horizontalRowsWide(int[] src, int lanes, int[] starts, int[] counts, int[] weights, int stride, int count, int[] dst) {
        for (var i = 0; i < count; i++) {
            var base = starts[i] * L;
            var w = i * stride;
            var taps = counts[i];
            var acc = INT_VECTOR.cast(IntVector.zero(I));
            for (var k = 0; k < taps; k++) {
                acc = INT_VECTOR.cast(IntVector.fromArray(I, src, base + k * L))
                        .lanewise(VectorOperators.MUL, INT_VECTOR.cast(IntVector.broadcast(I, weights[w + k])))
                        .lanewise(VectorOperators.ADD, acc);
            }
            INT_VECTOR.cast(acc).intoArray(dst, i * L);
        }
    }

    @Override
    public void interleave(byte[] planes, int pOff, int width, boolean opaque, byte[] dst, int dOff) {
        var end = width - 8 < 0 ? 0 : ((width - 8) / L + 1) * L;
        if (opaque) {
            for (var x = 0; x < end; x += L) {
                var red = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, planes, pOff + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
                var green = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, planes, pOff + width + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
                var blue = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, planes, pOff + 2 * width + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
                var packed = red.lanewise(VectorOperators.OR, green.lanewise(VectorOperators.LSHL, 8))
                        .lanewise(VectorOperators.OR, blue.lanewise(VectorOperators.LSHL, 16))
                        .lanewise(VectorOperators.OR, OPAQUE_ALPHA);
                PIXEL_VECTOR.cast(INT_VECTOR.cast(packed).reinterpretShape(BP, 0)).intoArray(dst, dOff + 4 * x);
            }
        } else {
            for (var x = 0; x < end; x += L) {
                var red = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, planes, pOff + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
                var green = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, planes, pOff + width + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
                var blue = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, planes, pOff + 2 * width + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
                var alpha = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, planes, pOff + 3 * width + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
                var packed = red.lanewise(VectorOperators.OR, green.lanewise(VectorOperators.LSHL, 8))
                        .lanewise(VectorOperators.OR, blue.lanewise(VectorOperators.LSHL, 16))
                        .lanewise(VectorOperators.OR, alpha.lanewise(VectorOperators.LSHL, 24));
                PIXEL_VECTOR.cast(INT_VECTOR.cast(packed).reinterpretShape(BP, 0)).intoArray(dst, dOff + 4 * x);
            }
        }
        scalar.interleaveRange(planes, pOff, width, opaque, dst, dOff, end, width);
    }

    @Override
    public void premultiply(byte[] src, int sOff, int width, char[] dst, int dOff) {
        var x = 0;
        for (; x + L <= width; x += L) {
            var pixels = INT_VECTOR.cast(PIXEL_VECTOR.cast(ByteVector.fromArray(BP, src, sOff + 4 * x))
                    .reinterpretShape(I, 0));
            var a = pixels.lanewise(VectorOperators.LSHR, 24);
            var r = pixels.lanewise(VectorOperators.AND, LOW_BYTE).lanewise(VectorOperators.MUL, a);
            var g = pixels.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE)
                    .lanewise(VectorOperators.MUL, a);
            var b = pixels.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.AND, LOW_BYTE)
                    .lanewise(VectorOperators.MUL, a);
            SHORT_VECTOR.cast(r.convertShape(VectorOperators.I2S, S, 0)).intoCharArray(dst, dOff + x);
            SHORT_VECTOR.cast(g.convertShape(VectorOperators.I2S, S, 0)).intoCharArray(dst, dOff + width + x);
            SHORT_VECTOR.cast(b.convertShape(VectorOperators.I2S, S, 0)).intoCharArray(dst, dOff + 2 * width + x);
            SHORT_VECTOR.cast(a.lanewise(VectorOperators.MUL, MAX_SAMPLE).convertShape(VectorOperators.I2S, S, 0))
                    .intoCharArray(dst, dOff + 3 * width + x);
        }
        scalar.premultiplyRange(src, sOff, width, dst, dOff, x, width);
    }

    /**
     * Divides planar premultiplied sums by their alpha with exact rounding, 4 pixels at a time.
     *
     * <p>Each color is {@code clamp(floor((510 * color + alpha) * r + 2^-40), 0, 255)} with
     * {@code r = 1 / (2 * alpha)} in double precision. For operands below {@code 2^31} the
     * reciprocal-multiplied quotient is within {@code 2^-43} of the exact one whenever the exact
     * one is below 256, while distinct quotients of these operands differ by at least
     * {@code 2^-31}; adding {@link #QUOTIENT_MARGIN} before truncating therefore yields the exact
     * floor. Larger or negative quotients clamp to the same values either way.
     */
    @Override
    public void unpremultiply(int[] src, int sOff, int width, int scale, byte[] dst, int dOff) {
        var x = 0;
        if (D4 != null) {
            var alphaFactor = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 1.0 / (510.0 * scale)));
            var alphaBias = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 255.0 * scale));
            var twice = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 2.0));
            var colorScale = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 510.0));
            var half = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 0.5));
            var margin = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, QUOTIENT_MARGIN));
            var low = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, -1.0));
            var high = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 256.0));
            var zero = DOUBLE_VECTOR.cast(DoubleVector.zero(D4));
            for (; x + 4 <= width; x += 4) {
                var a = DOUBLE_VECTOR.cast(INT4_VECTOR.cast(IntVector.fromArray(I4, src, sOff + 3 * width + x))
                        .convertShape(VectorOperators.I2D, D4, 0));
                var red = DOUBLE_VECTOR.cast(INT4_VECTOR.cast(IntVector.fromArray(I4, src, sOff + x))
                        .convertShape(VectorOperators.I2D, D4, 0));
                var green = DOUBLE_VECTOR.cast(INT4_VECTOR.cast(IntVector.fromArray(I4, src, sOff + width + x))
                        .convertShape(VectorOperators.I2D, D4, 0));
                var blue = DOUBLE_VECTOR.cast(INT4_VECTOR.cast(IntVector.fromArray(I4, src, sOff + 2 * width + x))
                        .convertShape(VectorOperators.I2D, D4, 0));
                var reciprocal = DOUBLE_VECTOR.cast(half).lanewise(VectorOperators.DIV, a);
                var r = INT4_VECTOR.cast(red.lanewise(VectorOperators.MUL, colorScale).lanewise(VectorOperators.ADD, a)
                                .lanewise(VectorOperators.MUL, reciprocal).lanewise(VectorOperators.ADD, margin)
                                .lanewise(VectorOperators.MAX, low).lanewise(VectorOperators.MIN, high)
                                .convertShape(VectorOperators.D2I, I4, 0))
                        .lanewise(VectorOperators.MAX, ZERO4).lanewise(VectorOperators.MIN, MAX_SAMPLE4);
                var g = INT4_VECTOR.cast(green.lanewise(VectorOperators.MUL, colorScale)
                        .lanewise(VectorOperators.ADD, a)
                                .lanewise(VectorOperators.MUL, reciprocal).lanewise(VectorOperators.ADD, margin)
                                .lanewise(VectorOperators.MAX, low).lanewise(VectorOperators.MIN, high)
                                .convertShape(VectorOperators.D2I, I4, 0))
                        .lanewise(VectorOperators.MAX, ZERO4).lanewise(VectorOperators.MIN, MAX_SAMPLE4);
                var b = INT4_VECTOR.cast(blue.lanewise(VectorOperators.MUL, colorScale)
                        .lanewise(VectorOperators.ADD, a)
                                .lanewise(VectorOperators.MUL, reciprocal).lanewise(VectorOperators.ADD, margin)
                                .lanewise(VectorOperators.MAX, low).lanewise(VectorOperators.MIN, high)
                                .convertShape(VectorOperators.D2I, I4, 0))
                        .lanewise(VectorOperators.MAX, ZERO4).lanewise(VectorOperators.MIN, MAX_SAMPLE4);
                var alpha = INT4_VECTOR.cast(a.lanewise(VectorOperators.MUL, twice)
                        .lanewise(VectorOperators.ADD, alphaBias)
                                .lanewise(VectorOperators.MUL, alphaFactor).lanewise(VectorOperators.ADD, margin)
                                .convertShape(VectorOperators.D2I, I4, 0))
                        .lanewise(VectorOperators.MIN, MAX_SAMPLE4);
                var transparent = a.compare(VectorOperators.LE, zero).cast(I4);
                var packed = r.lanewise(VectorOperators.OR, INT4_VECTOR.cast(g).lanewise(VectorOperators.LSHL, 8))
                        .lanewise(VectorOperators.OR, INT4_VECTOR.cast(b).lanewise(VectorOperators.LSHL, 16))
                        .lanewise(VectorOperators.OR, INT4_VECTOR.cast(alpha).lanewise(VectorOperators.LSHL, 24))
                        .blend(ZERO4, transparent);
                PIXEL4_VECTOR.cast(INT4_VECTOR.cast(packed).reinterpretShape(BP4, 0)).intoArray(dst, dOff + 4 * x);
            }
        }
        scalar.unpremultiplyRange(src, sOff, width, scale, dst, dOff, x, width);
    }
}
