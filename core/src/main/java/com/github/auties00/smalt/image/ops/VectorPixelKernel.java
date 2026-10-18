package com.github.auties00.smalt.image.ops;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link PixelKernel} with the Vector API; the output equals {@link ScalarPixelKernel} sample for sample.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd}; it
 * scans opacity by AND-ing whole vectors of bytes and testing once per row, and composites packed pixels one per 32-bit
 * lane.
 */
final class VectorPixelKernel implements PixelKernel {
    /**
     * The byte species of the widest supported vector.
     */
    private static final VectorSpecies<Byte> B = ByteVector.SPECIES_PREFERRED;

    /**
     * The integer species: 256 bits when the hardware has them, else 128 bits.
     */
    private static final VectorSpecies<Integer> I = IntVector.SPECIES_PREFERRED.vectorBitSize() >= 256
            ? IntVector.SPECIES_256 : IntVector.SPECIES_128;

    /**
     * The number of lanes of {@link #I}.
     */
    private static final int L = I.length();

    /**
     * The byte species holding {@link #L} packed RGBA pixels.
     */
    private static final VectorSpecies<Byte> BP = VectorSpecies.of(byte.class, VectorShape.forBitSize(I.vectorBitSize()));

    /**
     * The byte species used to load 8 samples, of which the first {@link #L} are used.
     */
    private static final VectorSpecies<Byte> B8 = ByteVector.SPECIES_64;

    /**
     * The exact class of the vectors of {@link #B}.
     */
    private static final Class<ByteVector> BYTE_VECTOR = vectorClass(B);

    /**
     * The exact class of the vectors of {@link #I}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(I);

    /**
     * The exact class of the vectors of {@link #BP}.
     */
    private static final Class<ByteVector> PIXEL_VECTOR = vectorClass(BP);

    /**
     * The exact class of the vectors of {@link #B8}.
     */
    private static final Class<ByteVector> SAMPLE_VECTOR = vectorClass(B8);

    /**
     * All bits set in every byte lane.
     */
    private static final ByteVector ALL_ONE_BYTES = ByteVector.broadcast(B, (byte) -1);

    /**
     * The largest sample value in every lane.
     */
    private static final IntVector MAX_SAMPLE = IntVector.broadcast(I, 255);

    /**
     * The low byte of every lane.
     */
    private static final IntVector LOW_BYTE = IntVector.broadcast(I, 0xFF);

    /**
     * The rounding bias of the division by 255.
     */
    private static final IntVector DIVIDE_BIAS = IntVector.broadcast(I, 128);

    /**
     * An opaque alpha byte in the top byte of every lane.
     */
    private static final IntVector OPAQUE_ALPHA = IntVector.broadcast(I, 0xFF000000);

    /**
     * The scalar kernel handling the row ends.
     */
    private final ScalarPixelKernel scalar;

    /**
     * Constructs the kernel.
     */
    private VectorPixelKernel() {
        this.scalar = new ScalarPixelKernel();
    }

    /**
     * Creates the kernel.
     *
     * @return the vector kernel
     */
    static PixelKernel create() {
        return new VectorPixelKernel();
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

    @Override
    public boolean isOpaqueRgba(byte[] pixels, int offset, int width) {
        var x = 0;
        var all = INT_VECTOR.cast(IntVector.broadcast(I, -1));
        for (; x + L <= width; x += L) {
            all = INT_VECTOR.cast(PIXEL_VECTOR.cast(ByteVector.fromArray(BP, pixels, offset + 4 * x))
                    .reinterpretShape(I, 0))
                    .lanewise(VectorOperators.AND, all);
        }
        if (INT_VECTOR.cast(all).lanewise(VectorOperators.LSHR, 24).compare(VectorOperators.NE, MAX_SAMPLE).anyTrue()) {
            return false;
        }
        return scalar.isOpaqueRgbaRange(pixels, offset, x, width);
    }

    @Override
    public boolean isOpaquePlane(byte[] plane, int offset, int width) {
        var x = 0;
        var all = BYTE_VECTOR.cast(ByteVector.broadcast(B, (byte) -1));
        for (; x + B.length() <= width; x += B.length()) {
            all = BYTE_VECTOR.cast(ByteVector.fromArray(B, plane, offset + x)).lanewise(VectorOperators.AND, all);
        }
        if (BYTE_VECTOR.cast(all).compare(VectorOperators.NE, ALL_ONE_BYTES).anyTrue()) {
            return false;
        }
        return scalar.isOpaquePlaneRange(plane, offset, x, width);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation divides by 255 with {@code (t + 128 + ((t + 128) >> 8)) >> 8}, exact for the sums
     * of two products of samples.
     */
    @Override
    public void flattenRgba(byte[] src, int sOff, int width, byte[] dst, int dOff, int rgb) {
        var red = INT_VECTOR.cast(IntVector.broadcast(I, (rgb >>> 16) & 0xFF));
        var green = INT_VECTOR.cast(IntVector.broadcast(I, (rgb >>> 8) & 0xFF));
        var blue = INT_VECTOR.cast(IntVector.broadcast(I, rgb & 0xFF));
        var x = 0;
        for (; x + L <= width; x += L) {
            var pixels = INT_VECTOR.cast(PIXEL_VECTOR.cast(ByteVector.fromArray(BP, src, sOff + 4 * x))
                    .reinterpretShape(I, 0));
            var a = pixels.lanewise(VectorOperators.LSHR, 24);
            var inverse = MAX_SAMPLE.lanewise(VectorOperators.SUB, a);
            var biasedR = pixels.lanewise(VectorOperators.AND, LOW_BYTE).lanewise(VectorOperators.MUL, a)
                    .lanewise(VectorOperators.ADD, inverse.lanewise(VectorOperators.MUL, red))
                    .lanewise(VectorOperators.ADD, DIVIDE_BIAS);
            var r = INT_VECTOR.cast(biasedR.lanewise(VectorOperators.ADD, INT_VECTOR.cast(biasedR)
                    .lanewise(VectorOperators.ASHR, 8)))
                    .lanewise(VectorOperators.ASHR, 8);
            var biasedG = pixels.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE)
                    .lanewise(VectorOperators.MUL, a).lanewise(VectorOperators.ADD, inverse
                    .lanewise(VectorOperators.MUL, green))
                    .lanewise(VectorOperators.ADD, DIVIDE_BIAS);
            var g = INT_VECTOR.cast(biasedG.lanewise(VectorOperators.ADD, INT_VECTOR.cast(biasedG)
                    .lanewise(VectorOperators.ASHR, 8)))
                    .lanewise(VectorOperators.ASHR, 8);
            var biasedB = pixels.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.AND, LOW_BYTE)
                    .lanewise(VectorOperators.MUL, a).lanewise(VectorOperators.ADD, inverse
                    .lanewise(VectorOperators.MUL, blue))
                    .lanewise(VectorOperators.ADD, DIVIDE_BIAS);
            var b = INT_VECTOR.cast(biasedB.lanewise(VectorOperators.ADD, INT_VECTOR.cast(biasedB)
                    .lanewise(VectorOperators.ASHR, 8)))
                    .lanewise(VectorOperators.ASHR, 8);
            var packed = r.lanewise(VectorOperators.OR, INT_VECTOR.cast(g).lanewise(VectorOperators.LSHL, 8))
                    .lanewise(VectorOperators.OR, INT_VECTOR.cast(b).lanewise(VectorOperators.LSHL, 16))
                    .lanewise(VectorOperators.OR, OPAQUE_ALPHA);
            PIXEL_VECTOR.cast(INT_VECTOR.cast(packed).reinterpretShape(BP, 0)).intoArray(dst, dOff + 4 * x);
        }
        scalar.flattenRgbaRange(src, sOff, dst, dOff, rgb, x, width);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation divides by 255 with {@code (t + 128 + ((t + 128) >> 8)) >> 8}, exact for the sums
     * of two products of samples.
     */
    @Override
    public void flattenPlane(byte[] src, int sOff, byte[] alpha, int aOff, int width, int background, byte[] dst, int dOff) {
        var fill = INT_VECTOR.cast(IntVector.broadcast(I, background));
        var x = 0;
        for (; x + 8 <= width; x += L) {
            var a = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, alpha, aOff + x))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var c = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, sOff + x))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var biased = c.lanewise(VectorOperators.MUL, a)
                    .lanewise(VectorOperators.ADD, MAX_SAMPLE.lanewise(VectorOperators.SUB, a)
                    .lanewise(VectorOperators.MUL, fill))
                    .lanewise(VectorOperators.ADD, DIVIDE_BIAS);
            var blended = INT_VECTOR.cast(biased.lanewise(VectorOperators.ADD, INT_VECTOR.cast(biased)
                    .lanewise(VectorOperators.ASHR, 8)))
                    .lanewise(VectorOperators.ASHR, 8);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(blended).convertShape(VectorOperators.I2B, B8, 0))
                    .intoArray(dst, dOff + x);
        }
        scalar.flattenPlaneRange(src, sOff, alpha, aOff, background, dst, dOff, x, width);
    }
}
