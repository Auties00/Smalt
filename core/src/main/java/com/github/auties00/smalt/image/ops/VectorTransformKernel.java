package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.simd.Simd;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link TransformKernel} with the Vector API.
 *
 * <p>Transpositions walk {@link TransformKernel#BLOCK}-byte square blocks and transpose square
 * tiles held in registers: 16x16 samples of an 8-bit plane in sixteen 128-bit vectors, and 8x8
 * (or, on 128-bit hardware, 4x4) 32-bit pixels. A tile of {@code n} rows is transposed by
 * {@code log2(n)} identical rounds that interleave row {@code k} with row {@code k + n / 2}; each
 * round rotates the bits of the row and column indices by one position, so the rounds together
 * swap them. Mirroring reads the tile's source rows bottom-up (which reverses every output row)
 * or walks the output rows backwards, so no lane reversal is needed. Partial tiles at the right
 * and bottom edges use {@link ScalarTransformKernel}. Tiles are written out inline, without
 * helper methods, so their vectors never leave registers; an 8-bit tile is transposed in two
 * halves, each loading all sixteen rows and producing eight output rows, since the first round
 * already separates the left eight columns from the right eight, and a single method for the
 * whole tile would exceed 2000 bytecodes, the size up to which a kernel stays well inside the
 * compiler's node budget.
 *
 * <p>An interleave of two vectors is spelled as two single-vector rearrangements that spread one
 * half of each vector over every other lane and a blend that takes the odd lanes from the second:
 * the Vector API's two-vector rearrangement blends internally on a vector whose class the
 * compiler only knows from a profile shared by every caller. Every vector that a load or
 * reinterpretation produces is cast to the exact class of its species before it is used as the
 * receiver of a call, and so is the receiver of every {@code intoArray} and reinterpretation, so
 * that the compiler binds those calls without consulting receiver-type profiles.
 * Reinterpretations use the per-shape {@code reinterpretShape}, never the {@code reinterpretAs}
 * methods.
 *
 * <p>Generated from {@code tools/codegen/transpose}; edit the template.
 */
final class VectorTransformKernel implements TransformKernel {
    /**
     * The byte species holding one 16-sample tile row.
     */
    private static final VectorSpecies<Byte> B16 = ByteVector.SPECIES_128;

    /**
     * The widest byte species, used for row reversal.
     */
    private static final VectorSpecies<Byte> B = ByteVector.SPECIES_PREFERRED;

    /**
     * The integer species of pixel tiles: 256 bits where {@link Simd#WIDE} holds, else 128 bits.
     */
    private static final VectorSpecies<Integer> I = Simd.WIDE
            ? IntVector.SPECIES_256 : IntVector.SPECIES_128;

    /**
     * The side of a pixel tile.
     */
    private static final int L = I.length();

    /**
     * The byte species holding {@link #L} packed pixels.
     */
    private static final VectorSpecies<Byte> BP = VectorSpecies.of(byte.class, VectorShape.forBitSize(I.vectorBitSize()));

    /**
     * The byte species of one row of an 8x8 pixel tile.
     */
    private static final VectorSpecies<Byte> BP8 = ByteVector.SPECIES_256;

    /**
     * The byte species of one row of a 4x4 pixel tile.
     */
    private static final VectorSpecies<Byte> BP4 = ByteVector.SPECIES_128;

    /**
     * Repeats each lane of the low half of a 16-byte vector twice.
     */
    private static final VectorShuffle<Byte> SPREAD_LO_8 = spread(ByteVector.SPECIES_128, false);

    /**
     * Repeats each lane of the high half of a 16-byte vector twice.
     */
    private static final VectorShuffle<Byte> SPREAD_HI_8 = spread(ByteVector.SPECIES_128, true);

    /**
     * Selects the odd lanes of a 16-byte vector.
     */
    private static final VectorMask<Byte> ODD_8 = odd(ByteVector.SPECIES_128);

    /**
     * Reverses the lanes of the widest byte vector.
     */
    private static final VectorShuffle<Byte> REVERSE_B = reverse(ByteVector.SPECIES_PREFERRED);

    /**
     * Repeats each lane of the low half of an 8-lane integer vector twice.
     */
    private static final VectorShuffle<Integer> SPREAD_LO_32 = spread(IntVector.SPECIES_256, false);

    /**
     * Repeats each lane of the high half of an 8-lane integer vector twice.
     */
    private static final VectorShuffle<Integer> SPREAD_HI_32 = spread(IntVector.SPECIES_256, true);

    /**
     * Selects the odd lanes of an 8-lane integer vector.
     */
    private static final VectorMask<Integer> ODD_32 = odd(IntVector.SPECIES_256);

    /**
     * Repeats each lane of the low half of a 4-lane integer vector twice.
     */
    private static final VectorShuffle<Integer> SPREAD_LO_32_4 = spread(IntVector.SPECIES_128, false);

    /**
     * Repeats each lane of the high half of a 4-lane integer vector twice.
     */
    private static final VectorShuffle<Integer> SPREAD_HI_32_4 = spread(IntVector.SPECIES_128, true);

    /**
     * Selects the odd lanes of a 4-lane integer vector.
     */
    private static final VectorMask<Integer> ODD_32_4 = odd(IntVector.SPECIES_128);

    /**
     * The exact class of 16-byte vectors.
     */
    private static final Class<ByteVector> BYTE16 = vectorClass(ByteVector.SPECIES_128);

    /**
     * The exact class of the vectors of {@link #B}.
     */
    private static final Class<ByteVector> BYTE_WIDE = vectorClass(B);

    /**
     * The exact class of the vectors of {@link #BP}.
     */
    private static final Class<ByteVector> PIXEL_BYTES = vectorClass(BP);

    /**
     * The exact class of the vectors of {@link #I}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(I);

    /**
     * The exact class of 8-lane integer vectors.
     */
    private static final Class<IntVector> INT8 = vectorClass(IntVector.SPECIES_256);

    /**
     * The exact class of 4-lane integer vectors.
     */
    private static final Class<IntVector> INT4 = vectorClass(IntVector.SPECIES_128);

    /**
     * The exact class of 32-byte vectors.
     */
    private static final Class<ByteVector> BYTE32 = vectorClass(ByteVector.SPECIES_256);

    /**
     * Reverses the lanes of the pixel-tile integer vector.
     */
    private static final VectorShuffle<Integer> REVERSE_I = reverse(I);

    /**
     * Constructs the kernel.
     */
    private VectorTransformKernel() {
    }

    /**
     * Creates the kernel.
     *
     * @return the vector kernel
     */
    static TransformKernel create() {
        return new VectorTransformKernel();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the vector shape and lane type
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    /**
     * Builds the shuffle that repeats each lane of one half of a vector twice: lane {@code j}
     * takes lane {@code j / 2}, plus half the length for the high half.
     *
     * @param species the vector shape and lane type of the shuffle
     * @param high whether to spread the high half
     * @param <E> the lane type
     * @return the shuffle
     */
    private static <E> VectorShuffle<E> spread(VectorSpecies<E> species, boolean high) {
        var length = species.length();
        var indexes = new int[length];
        for (var j = 0; j < length; j++) {
            indexes[j] = j / 2 + (high ? length / 2 : 0);
        }
        return VectorShuffle.fromArray(species, indexes, 0);
    }

    /**
     * Builds the mask of the odd lanes of a vector.
     *
     * @param species the vector shape and lane type of the mask
     * @param <E> the lane type
     * @return the mask
     */
    private static <E> VectorMask<E> odd(VectorSpecies<E> species) {
        return VectorMask.fromLong(species, 0xAAAAAAAAAAAAAAAAL & (species.length() == 64 ? -1L : (1L << species.length()) - 1));
    }

    /**
     * Builds the shuffle that reverses the lanes of a vector.
     *
     * @param species the vector shape and lane type of the shuffle
     * @param <E> the lane type
     * @return the shuffle
     */
    private static <E> VectorShuffle<E> reverse(VectorSpecies<E> species) {
        var length = species.length();
        var indexes = new int[length];
        for (var j = 0; j < length; j++) {
            indexes[j] = length - 1 - j;
        }
        return VectorShuffle.fromArray(species, indexes, 0);
    }

    @Override
    public void transpose8(byte[] src, int sOff, int sStride, int width, int height, byte[] dst, int dOff, int dStride,
                           boolean reverseRows, boolean reverseColumns) {
        for (var bx = 0; bx < width; bx += BLOCK) {
            var xEnd = Math.min(bx + BLOCK, width);
            for (var by = 0; by < height; by += BLOCK) {
                var yEnd = Math.min(by + BLOCK, height);
                for (var ty = by; ty < yEnd; ty += 16) {
                    for (var tx = bx; tx < xEnd; tx += 16) {
                        if (ty + 16 <= height && tx + 16 <= width) {
                            tile8Left(src, sOff, sStride, width, height, dst, dOff, dStride, reverseRows, reverseColumns,
                                    tx, ty);
                            tile8Right(src, sOff, sStride, width, height, dst, dOff, dStride, reverseRows,
                                    reverseColumns, tx, ty);
                        } else {
                            ScalarTransformKernel.transpose8Range(src, sOff, sStride, width, height, dst, dOff, dStride,
                                    reverseRows, reverseColumns, tx, Math.min(tx + 16, width), ty, Math.min(ty + 16, height));
                        }
                    }
                }
            }
        }
    }

    /**
     * Transposes the left half of one full 16x16 tile of 8-bit samples: the destination rows that
     * hold source columns 0 to 7 of the tile.
     *
     * <p>Every destination row needs one sample of each of the sixteen source rows, but the first
     * interleaving step already separates source columns 0 to 7 from 8 to 15, so each half loads the
     * sixteen rows and carries half of the interleavings on.
     *
     * @param src            the source samples
     * @param sOff           the index of the source's first sample
     * @param sStride        the source row stride
     * @param width          the source width
     * @param height         the source height
     * @param dst            the destination samples
     * @param dOff           the index of the destination's first sample
     * @param dStride        the destination row stride
     * @param reverseRows    whether destination rows run from the last source column
     * @param reverseColumns whether destination columns run from the last source row
     * @param tx             the tile's first column
     * @param ty             the tile's first row
     */
    private static void tile8Left(byte[] src, int sOff, int sStride, int width, int height, byte[] dst, int dOff,
                                 int dStride, boolean reverseRows, boolean reverseColumns, int tx, int ty) {
        var srcStep = reverseColumns ? -sStride : sStride;
        var base = sOff + (reverseColumns ? ty + 15 : ty) * sStride + tx;
        var rowStep = reverseRows ? -dStride : dStride;
        var out = dOff + (reverseRows ? width - 1 - tx : tx) * dStride + (reverseColumns ? height - 16 - ty : ty);
        var r0 = BYTE16.cast(ByteVector.fromArray(B16, src, base));
        var r1 = BYTE16.cast(ByteVector.fromArray(B16, src, base + srcStep));
        var r2 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 2 * srcStep));
        var r3 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 3 * srcStep));
        var r4 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 4 * srcStep));
        var r5 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 5 * srcStep));
        var r6 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 6 * srcStep));
        var r7 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 7 * srcStep));
        var r8 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 8 * srcStep));
        var r9 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 9 * srcStep));
        var r10 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 10 * srcStep));
        var r11 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 11 * srcStep));
        var r12 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 12 * srcStep));
        var r13 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 13 * srcStep));
        var r14 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 14 * srcStep));
        var r15 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 15 * srcStep));
        var s0 = r0.rearrange(SPREAD_LO_8).blend(r8.rearrange(SPREAD_LO_8), ODD_8);
        var s2 = r1.rearrange(SPREAD_LO_8).blend(r9.rearrange(SPREAD_LO_8), ODD_8);
        var s4 = r2.rearrange(SPREAD_LO_8).blend(r10.rearrange(SPREAD_LO_8), ODD_8);
        var s6 = r3.rearrange(SPREAD_LO_8).blend(r11.rearrange(SPREAD_LO_8), ODD_8);
        var s8 = r4.rearrange(SPREAD_LO_8).blend(r12.rearrange(SPREAD_LO_8), ODD_8);
        var s10 = r5.rearrange(SPREAD_LO_8).blend(r13.rearrange(SPREAD_LO_8), ODD_8);
        var s12 = r6.rearrange(SPREAD_LO_8).blend(r14.rearrange(SPREAD_LO_8), ODD_8);
        var s14 = r7.rearrange(SPREAD_LO_8).blend(r15.rearrange(SPREAD_LO_8), ODD_8);
        var t0 = s0.rearrange(SPREAD_LO_8).blend(s8.rearrange(SPREAD_LO_8), ODD_8);
        var t1 = s0.rearrange(SPREAD_HI_8).blend(s8.rearrange(SPREAD_HI_8), ODD_8);
        var t4 = s2.rearrange(SPREAD_LO_8).blend(s10.rearrange(SPREAD_LO_8), ODD_8);
        var t5 = s2.rearrange(SPREAD_HI_8).blend(s10.rearrange(SPREAD_HI_8), ODD_8);
        var t8 = s4.rearrange(SPREAD_LO_8).blend(s12.rearrange(SPREAD_LO_8), ODD_8);
        var t9 = s4.rearrange(SPREAD_HI_8).blend(s12.rearrange(SPREAD_HI_8), ODD_8);
        var t12 = s6.rearrange(SPREAD_LO_8).blend(s14.rearrange(SPREAD_LO_8), ODD_8);
        var t13 = s6.rearrange(SPREAD_HI_8).blend(s14.rearrange(SPREAD_HI_8), ODD_8);
        var u0 = t0.rearrange(SPREAD_LO_8).blend(t8.rearrange(SPREAD_LO_8), ODD_8);
        var u1 = t0.rearrange(SPREAD_HI_8).blend(t8.rearrange(SPREAD_HI_8), ODD_8);
        var u2 = t1.rearrange(SPREAD_LO_8).blend(t9.rearrange(SPREAD_LO_8), ODD_8);
        var u3 = t1.rearrange(SPREAD_HI_8).blend(t9.rearrange(SPREAD_HI_8), ODD_8);
        var u8 = t4.rearrange(SPREAD_LO_8).blend(t12.rearrange(SPREAD_LO_8), ODD_8);
        var u9 = t4.rearrange(SPREAD_HI_8).blend(t12.rearrange(SPREAD_HI_8), ODD_8);
        var u10 = t5.rearrange(SPREAD_LO_8).blend(t13.rearrange(SPREAD_LO_8), ODD_8);
        var u11 = t5.rearrange(SPREAD_HI_8).blend(t13.rearrange(SPREAD_HI_8), ODD_8);
        var v0 = u0.rearrange(SPREAD_LO_8).blend(u8.rearrange(SPREAD_LO_8), ODD_8);
        var v1 = u0.rearrange(SPREAD_HI_8).blend(u8.rearrange(SPREAD_HI_8), ODD_8);
        var v2 = u1.rearrange(SPREAD_LO_8).blend(u9.rearrange(SPREAD_LO_8), ODD_8);
        var v3 = u1.rearrange(SPREAD_HI_8).blend(u9.rearrange(SPREAD_HI_8), ODD_8);
        var v4 = u2.rearrange(SPREAD_LO_8).blend(u10.rearrange(SPREAD_LO_8), ODD_8);
        var v5 = u2.rearrange(SPREAD_HI_8).blend(u10.rearrange(SPREAD_HI_8), ODD_8);
        var v6 = u3.rearrange(SPREAD_LO_8).blend(u11.rearrange(SPREAD_LO_8), ODD_8);
        var v7 = u3.rearrange(SPREAD_HI_8).blend(u11.rearrange(SPREAD_HI_8), ODD_8);
        BYTE16.cast(v0).intoArray(dst, out);
        BYTE16.cast(v1).intoArray(dst, out + rowStep);
        BYTE16.cast(v2).intoArray(dst, out + 2 * rowStep);
        BYTE16.cast(v3).intoArray(dst, out + 3 * rowStep);
        BYTE16.cast(v4).intoArray(dst, out + 4 * rowStep);
        BYTE16.cast(v5).intoArray(dst, out + 5 * rowStep);
        BYTE16.cast(v6).intoArray(dst, out + 6 * rowStep);
        BYTE16.cast(v7).intoArray(dst, out + 7 * rowStep);
    }

    /**
     * Transposes the right half of one full 16x16 tile of 8-bit samples: the destination rows that
     * hold source columns 8 to 15 of the tile.
     *
     * <p>Every destination row needs one sample of each of the sixteen source rows, but the first
     * interleaving step already separates source columns 0 to 7 from 8 to 15, so each half loads the
     * sixteen rows and carries half of the interleavings on.
     *
     * @param src            the source samples
     * @param sOff           the index of the source's first sample
     * @param sStride        the source row stride
     * @param width          the source width
     * @param height         the source height
     * @param dst            the destination samples
     * @param dOff           the index of the destination's first sample
     * @param dStride        the destination row stride
     * @param reverseRows    whether destination rows run from the last source column
     * @param reverseColumns whether destination columns run from the last source row
     * @param tx             the tile's first column
     * @param ty             the tile's first row
     */
    private static void tile8Right(byte[] src, int sOff, int sStride, int width, int height, byte[] dst, int dOff,
                                  int dStride, boolean reverseRows, boolean reverseColumns, int tx, int ty) {
        var srcStep = reverseColumns ? -sStride : sStride;
        var base = sOff + (reverseColumns ? ty + 15 : ty) * sStride + tx;
        var rowStep = reverseRows ? -dStride : dStride;
        var out = dOff + (reverseRows ? width - 1 - tx : tx) * dStride + (reverseColumns ? height - 16 - ty : ty);
        var r0 = BYTE16.cast(ByteVector.fromArray(B16, src, base));
        var r1 = BYTE16.cast(ByteVector.fromArray(B16, src, base + srcStep));
        var r2 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 2 * srcStep));
        var r3 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 3 * srcStep));
        var r4 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 4 * srcStep));
        var r5 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 5 * srcStep));
        var r6 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 6 * srcStep));
        var r7 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 7 * srcStep));
        var r8 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 8 * srcStep));
        var r9 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 9 * srcStep));
        var r10 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 10 * srcStep));
        var r11 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 11 * srcStep));
        var r12 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 12 * srcStep));
        var r13 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 13 * srcStep));
        var r14 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 14 * srcStep));
        var r15 = BYTE16.cast(ByteVector.fromArray(B16, src, base + 15 * srcStep));
        var s1 = r0.rearrange(SPREAD_HI_8).blend(r8.rearrange(SPREAD_HI_8), ODD_8);
        var s3 = r1.rearrange(SPREAD_HI_8).blend(r9.rearrange(SPREAD_HI_8), ODD_8);
        var s5 = r2.rearrange(SPREAD_HI_8).blend(r10.rearrange(SPREAD_HI_8), ODD_8);
        var s7 = r3.rearrange(SPREAD_HI_8).blend(r11.rearrange(SPREAD_HI_8), ODD_8);
        var s9 = r4.rearrange(SPREAD_HI_8).blend(r12.rearrange(SPREAD_HI_8), ODD_8);
        var s11 = r5.rearrange(SPREAD_HI_8).blend(r13.rearrange(SPREAD_HI_8), ODD_8);
        var s13 = r6.rearrange(SPREAD_HI_8).blend(r14.rearrange(SPREAD_HI_8), ODD_8);
        var s15 = r7.rearrange(SPREAD_HI_8).blend(r15.rearrange(SPREAD_HI_8), ODD_8);
        var t2 = s1.rearrange(SPREAD_LO_8).blend(s9.rearrange(SPREAD_LO_8), ODD_8);
        var t3 = s1.rearrange(SPREAD_HI_8).blend(s9.rearrange(SPREAD_HI_8), ODD_8);
        var t6 = s3.rearrange(SPREAD_LO_8).blend(s11.rearrange(SPREAD_LO_8), ODD_8);
        var t7 = s3.rearrange(SPREAD_HI_8).blend(s11.rearrange(SPREAD_HI_8), ODD_8);
        var t10 = s5.rearrange(SPREAD_LO_8).blend(s13.rearrange(SPREAD_LO_8), ODD_8);
        var t11 = s5.rearrange(SPREAD_HI_8).blend(s13.rearrange(SPREAD_HI_8), ODD_8);
        var t14 = s7.rearrange(SPREAD_LO_8).blend(s15.rearrange(SPREAD_LO_8), ODD_8);
        var t15 = s7.rearrange(SPREAD_HI_8).blend(s15.rearrange(SPREAD_HI_8), ODD_8);
        var u4 = t2.rearrange(SPREAD_LO_8).blend(t10.rearrange(SPREAD_LO_8), ODD_8);
        var u5 = t2.rearrange(SPREAD_HI_8).blend(t10.rearrange(SPREAD_HI_8), ODD_8);
        var u6 = t3.rearrange(SPREAD_LO_8).blend(t11.rearrange(SPREAD_LO_8), ODD_8);
        var u7 = t3.rearrange(SPREAD_HI_8).blend(t11.rearrange(SPREAD_HI_8), ODD_8);
        var u12 = t6.rearrange(SPREAD_LO_8).blend(t14.rearrange(SPREAD_LO_8), ODD_8);
        var u13 = t6.rearrange(SPREAD_HI_8).blend(t14.rearrange(SPREAD_HI_8), ODD_8);
        var u14 = t7.rearrange(SPREAD_LO_8).blend(t15.rearrange(SPREAD_LO_8), ODD_8);
        var u15 = t7.rearrange(SPREAD_HI_8).blend(t15.rearrange(SPREAD_HI_8), ODD_8);
        var v8 = u4.rearrange(SPREAD_LO_8).blend(u12.rearrange(SPREAD_LO_8), ODD_8);
        var v9 = u4.rearrange(SPREAD_HI_8).blend(u12.rearrange(SPREAD_HI_8), ODD_8);
        var v10 = u5.rearrange(SPREAD_LO_8).blend(u13.rearrange(SPREAD_LO_8), ODD_8);
        var v11 = u5.rearrange(SPREAD_HI_8).blend(u13.rearrange(SPREAD_HI_8), ODD_8);
        var v12 = u6.rearrange(SPREAD_LO_8).blend(u14.rearrange(SPREAD_LO_8), ODD_8);
        var v13 = u6.rearrange(SPREAD_HI_8).blend(u14.rearrange(SPREAD_HI_8), ODD_8);
        var v14 = u7.rearrange(SPREAD_LO_8).blend(u15.rearrange(SPREAD_LO_8), ODD_8);
        var v15 = u7.rearrange(SPREAD_HI_8).blend(u15.rearrange(SPREAD_HI_8), ODD_8);
        BYTE16.cast(v8).intoArray(dst, out + 8 * rowStep);
        BYTE16.cast(v9).intoArray(dst, out + 9 * rowStep);
        BYTE16.cast(v10).intoArray(dst, out + 10 * rowStep);
        BYTE16.cast(v11).intoArray(dst, out + 11 * rowStep);
        BYTE16.cast(v12).intoArray(dst, out + 12 * rowStep);
        BYTE16.cast(v13).intoArray(dst, out + 13 * rowStep);
        BYTE16.cast(v14).intoArray(dst, out + 14 * rowStep);
        BYTE16.cast(v15).intoArray(dst, out + 15 * rowStep);
    }

    @Override
    public void transpose32(byte[] src, int sOff, int sStride, int width, int height, byte[] dst, int dOff, int dStride,
                            boolean reverseRows, boolean reverseColumns) {
        var block = BLOCK / 4;
        for (var bx = 0; bx < width; bx += block) {
            for (var by = 0; by < height; by += block) {
                var yEnd = Math.min(by + block, height);
                var xEnd = Math.min(bx + block, width);
                for (var ty = by; ty < yEnd; ty += L) {
                    for (var tx = bx; tx < xEnd; tx += L) {
                        if (ty + L <= height && tx + L <= width) {
                            if (Simd.WIDE) {
                                tile32x8(src, sOff, sStride, width, height, dst, dOff, dStride, reverseRows, reverseColumns, tx, ty);
                            } else {
                                tile32x4(src, sOff, sStride, width, height, dst, dOff, dStride, reverseRows, reverseColumns, tx, ty);
                            }
                        } else {
                            ScalarTransformKernel.transpose32Range(src, sOff, sStride, width, height, dst, dOff, dStride,
                                    reverseRows, reverseColumns, tx, Math.min(tx + L, width), ty, Math.min(ty + L, height));
                        }
                    }
                }
            }
        }
    }

    /**
     * Transposes one full 8x8 tile of 32-bit pixels.
     *
     * @param src            the source bytes
     * @param sOff           the index of the source's first byte
     * @param sStride        the source row stride in bytes
     * @param width          the source width in pixels
     * @param height         the source height in pixels
     * @param dst            the destination bytes
     * @param dOff           the index of the destination's first byte
     * @param dStride        the destination row stride in bytes
     * @param reverseRows    whether destination rows run from the last source column
     * @param reverseColumns whether destination columns run from the last source row
     * @param tx             the tile's first column
     * @param ty             the tile's first row
     */
    private static void tile32x8(byte[] src, int sOff, int sStride, int width, int height, byte[] dst, int dOff, int dStride,
                                 boolean reverseRows, boolean reverseColumns, int tx, int ty) {
        var srcStep = reverseColumns ? -sStride : sStride;
        var base = sOff + (reverseColumns ? ty + 7 : ty) * sStride + 4 * tx;
        var rowStep = reverseRows ? -dStride : dStride;
        var out = dOff + (reverseRows ? width - 1 - tx : tx) * dStride + 4 * (reverseColumns ? height - 8 - ty : ty);
        var r0 = INT8.cast(BYTE32.cast(ByteVector.fromArray(BP8, src, base)).reinterpretShape(IntVector.SPECIES_256, 0));
        var r1 = INT8.cast(BYTE32.cast(ByteVector.fromArray(BP8, src, base + srcStep)).reinterpretShape(IntVector.SPECIES_256, 0));
        var r2 = INT8.cast(BYTE32.cast(ByteVector.fromArray(BP8, src, base + 2 * srcStep)).reinterpretShape(IntVector.SPECIES_256, 0));
        var r3 = INT8.cast(BYTE32.cast(ByteVector.fromArray(BP8, src, base + 3 * srcStep)).reinterpretShape(IntVector.SPECIES_256, 0));
        var r4 = INT8.cast(BYTE32.cast(ByteVector.fromArray(BP8, src, base + 4 * srcStep)).reinterpretShape(IntVector.SPECIES_256, 0));
        var r5 = INT8.cast(BYTE32.cast(ByteVector.fromArray(BP8, src, base + 5 * srcStep)).reinterpretShape(IntVector.SPECIES_256, 0));
        var r6 = INT8.cast(BYTE32.cast(ByteVector.fromArray(BP8, src, base + 6 * srcStep)).reinterpretShape(IntVector.SPECIES_256, 0));
        var r7 = INT8.cast(BYTE32.cast(ByteVector.fromArray(BP8, src, base + 7 * srcStep)).reinterpretShape(IntVector.SPECIES_256, 0));
        var s0 = r0.rearrange(SPREAD_LO_32).blend(r4.rearrange(SPREAD_LO_32), ODD_32);
        var s1 = r0.rearrange(SPREAD_HI_32).blend(r4.rearrange(SPREAD_HI_32), ODD_32);
        var s2 = r1.rearrange(SPREAD_LO_32).blend(r5.rearrange(SPREAD_LO_32), ODD_32);
        var s3 = r1.rearrange(SPREAD_HI_32).blend(r5.rearrange(SPREAD_HI_32), ODD_32);
        var s4 = r2.rearrange(SPREAD_LO_32).blend(r6.rearrange(SPREAD_LO_32), ODD_32);
        var s5 = r2.rearrange(SPREAD_HI_32).blend(r6.rearrange(SPREAD_HI_32), ODD_32);
        var s6 = r3.rearrange(SPREAD_LO_32).blend(r7.rearrange(SPREAD_LO_32), ODD_32);
        var s7 = r3.rearrange(SPREAD_HI_32).blend(r7.rearrange(SPREAD_HI_32), ODD_32);
        var t0 = s0.rearrange(SPREAD_LO_32).blend(s4.rearrange(SPREAD_LO_32), ODD_32);
        var t1 = s0.rearrange(SPREAD_HI_32).blend(s4.rearrange(SPREAD_HI_32), ODD_32);
        var t2 = s1.rearrange(SPREAD_LO_32).blend(s5.rearrange(SPREAD_LO_32), ODD_32);
        var t3 = s1.rearrange(SPREAD_HI_32).blend(s5.rearrange(SPREAD_HI_32), ODD_32);
        var t4 = s2.rearrange(SPREAD_LO_32).blend(s6.rearrange(SPREAD_LO_32), ODD_32);
        var t5 = s2.rearrange(SPREAD_HI_32).blend(s6.rearrange(SPREAD_HI_32), ODD_32);
        var t6 = s3.rearrange(SPREAD_LO_32).blend(s7.rearrange(SPREAD_LO_32), ODD_32);
        var t7 = s3.rearrange(SPREAD_HI_32).blend(s7.rearrange(SPREAD_HI_32), ODD_32);
        var u0 = t0.rearrange(SPREAD_LO_32).blend(t4.rearrange(SPREAD_LO_32), ODD_32);
        var u1 = t0.rearrange(SPREAD_HI_32).blend(t4.rearrange(SPREAD_HI_32), ODD_32);
        var u2 = t1.rearrange(SPREAD_LO_32).blend(t5.rearrange(SPREAD_LO_32), ODD_32);
        var u3 = t1.rearrange(SPREAD_HI_32).blend(t5.rearrange(SPREAD_HI_32), ODD_32);
        var u4 = t2.rearrange(SPREAD_LO_32).blend(t6.rearrange(SPREAD_LO_32), ODD_32);
        var u5 = t2.rearrange(SPREAD_HI_32).blend(t6.rearrange(SPREAD_HI_32), ODD_32);
        var u6 = t3.rearrange(SPREAD_LO_32).blend(t7.rearrange(SPREAD_LO_32), ODD_32);
        var u7 = t3.rearrange(SPREAD_HI_32).blend(t7.rearrange(SPREAD_HI_32), ODD_32);
        BYTE32.cast(INT8.cast(u0).reinterpretShape(ByteVector.SPECIES_256, 0)).intoArray(dst, out);
        BYTE32.cast(INT8.cast(u1).reinterpretShape(ByteVector.SPECIES_256, 0)).intoArray(dst, out + rowStep);
        BYTE32.cast(INT8.cast(u2).reinterpretShape(ByteVector.SPECIES_256, 0)).intoArray(dst, out + 2 * rowStep);
        BYTE32.cast(INT8.cast(u3).reinterpretShape(ByteVector.SPECIES_256, 0)).intoArray(dst, out + 3 * rowStep);
        BYTE32.cast(INT8.cast(u4).reinterpretShape(ByteVector.SPECIES_256, 0)).intoArray(dst, out + 4 * rowStep);
        BYTE32.cast(INT8.cast(u5).reinterpretShape(ByteVector.SPECIES_256, 0)).intoArray(dst, out + 5 * rowStep);
        BYTE32.cast(INT8.cast(u6).reinterpretShape(ByteVector.SPECIES_256, 0)).intoArray(dst, out + 6 * rowStep);
        BYTE32.cast(INT8.cast(u7).reinterpretShape(ByteVector.SPECIES_256, 0)).intoArray(dst, out + 7 * rowStep);
    }

    /**
     * Transposes one full 4x4 tile of 32-bit pixels.
     *
     * @param src            the source bytes
     * @param sOff           the index of the source's first byte
     * @param sStride        the source row stride in bytes
     * @param width          the source width in pixels
     * @param height         the source height in pixels
     * @param dst            the destination bytes
     * @param dOff           the index of the destination's first byte
     * @param dStride        the destination row stride in bytes
     * @param reverseRows    whether destination rows run from the last source column
     * @param reverseColumns whether destination columns run from the last source row
     * @param tx             the tile's first column
     * @param ty             the tile's first row
     */
    private static void tile32x4(byte[] src, int sOff, int sStride, int width, int height, byte[] dst, int dOff, int dStride,
                                 boolean reverseRows, boolean reverseColumns, int tx, int ty) {
        var srcStep = reverseColumns ? -sStride : sStride;
        var base = sOff + (reverseColumns ? ty + 3 : ty) * sStride + 4 * tx;
        var rowStep = reverseRows ? -dStride : dStride;
        var out = dOff + (reverseRows ? width - 1 - tx : tx) * dStride + 4 * (reverseColumns ? height - 4 - ty : ty);
        var r0 = INT4.cast(BYTE16.cast(ByteVector.fromArray(BP4, src, base)).reinterpretShape(IntVector.SPECIES_128, 0));
        var r1 = INT4.cast(BYTE16.cast(ByteVector.fromArray(BP4, src, base + srcStep)).reinterpretShape(IntVector.SPECIES_128, 0));
        var r2 = INT4.cast(BYTE16.cast(ByteVector.fromArray(BP4, src, base + 2 * srcStep)).reinterpretShape(IntVector.SPECIES_128, 0));
        var r3 = INT4.cast(BYTE16.cast(ByteVector.fromArray(BP4, src, base + 3 * srcStep)).reinterpretShape(IntVector.SPECIES_128, 0));
        var s0 = r0.rearrange(SPREAD_LO_32_4).blend(r2.rearrange(SPREAD_LO_32_4), ODD_32_4);
        var s1 = r0.rearrange(SPREAD_HI_32_4).blend(r2.rearrange(SPREAD_HI_32_4), ODD_32_4);
        var s2 = r1.rearrange(SPREAD_LO_32_4).blend(r3.rearrange(SPREAD_LO_32_4), ODD_32_4);
        var s3 = r1.rearrange(SPREAD_HI_32_4).blend(r3.rearrange(SPREAD_HI_32_4), ODD_32_4);
        var t0 = s0.rearrange(SPREAD_LO_32_4).blend(s2.rearrange(SPREAD_LO_32_4), ODD_32_4);
        var t1 = s0.rearrange(SPREAD_HI_32_4).blend(s2.rearrange(SPREAD_HI_32_4), ODD_32_4);
        var t2 = s1.rearrange(SPREAD_LO_32_4).blend(s3.rearrange(SPREAD_LO_32_4), ODD_32_4);
        var t3 = s1.rearrange(SPREAD_HI_32_4).blend(s3.rearrange(SPREAD_HI_32_4), ODD_32_4);
        BYTE16.cast(INT4.cast(t0).reinterpretShape(ByteVector.SPECIES_128, 0)).intoArray(dst, out);
        BYTE16.cast(INT4.cast(t1).reinterpretShape(ByteVector.SPECIES_128, 0)).intoArray(dst, out + rowStep);
        BYTE16.cast(INT4.cast(t2).reinterpretShape(ByteVector.SPECIES_128, 0)).intoArray(dst, out + 2 * rowStep);
        BYTE16.cast(INT4.cast(t3).reinterpretShape(ByteVector.SPECIES_128, 0)).intoArray(dst, out + 3 * rowStep);
    }

    @Override
    public void reverse8(byte[] src, int sOff, byte[] dst, int dOff, int count) {
        var length = B.length();
        var i = 0;
        for (; i + length <= count; i += length) {
            BYTE_WIDE.cast(BYTE_WIDE.cast(ByteVector.fromArray(B, src, sOff + count - length - i)).rearrange(REVERSE_B))
                    .intoArray(dst, dOff + i);
        }
        for (; i < count; i++) {
            dst[dOff + i] = src[sOff + count - 1 - i];
        }
    }

    @Override
    public void reverse32(byte[] src, int sOff, byte[] dst, int dOff, int count) {
        var i = 0;
        for (; i + L <= count; i += L) {
            var pixels = INT_VECTOR.cast(PIXEL_BYTES.cast(ByteVector.fromArray(BP, src, sOff + 4 * (count - L - i)))
                    .reinterpretShape(I, 0));
            PIXEL_BYTES.cast(INT_VECTOR.cast(pixels.rearrange(REVERSE_I)).reinterpretShape(BP, 0))
                    .intoArray(dst, dOff + 4 * i);
        }
        for (; i < count; i++) {
            System.arraycopy(src, sOff + 4 * (count - 1 - i), dst, dOff + 4 * i, 4);
        }
    }
}
