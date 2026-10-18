package com.github.auties00.smalt.image.ops;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link WarpKernel} with the Vector API; the output equals {@link ScalarWarpKernel} sample for sample.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd}; it
 * gives each lane one output pixel, gathers its taps from the packed source and keeps the sums in arrays between calls,
 * so the taps of a pixel are spread over calls of small kernels.
 */
final class VectorWarpKernel implements WarpKernel {
    /**
     * The integer species: 256 bits when the hardware has them, else 128 bits.
     */
    private static final VectorSpecies<Integer> I = IntVector.SPECIES_PREFERRED.vectorBitSize() >= 256
            ? IntVector.SPECIES_256 : IntVector.SPECIES_128;

    /**
     * The float species with the lane count of {@link #I}.
     */
    private static final VectorSpecies<Float> F = I.vectorBitSize() >= 256 ? FloatVector.SPECIES_256 : FloatVector.SPECIES_128;

    /**
     * The number of lanes.
     */
    private static final int L = I.length();

    /**
     * The byte species holding {@link #L} packed RGBA pixels.
     */
    private static final VectorSpecies<Byte> BP = VectorSpecies.of(byte.class, VectorShape.forBitSize(I.vectorBitSize()));

    /**
     * The exact class of the vectors of {@link #I}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(I);

    /**
     * The exact class of the vectors of {@link #F}.
     */
    private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(F);

    /**
     * The exact class of the vectors of {@link #BP}.
     */
    private static final Class<ByteVector> PIXEL_VECTOR = vectorClass(BP);

    /**
     * The low byte of every lane.
     */
    private static final IntVector LOW_BYTE = IntVector.broadcast(I, 0xFF);

    /**
     * Zero in every integer lane.
     */
    private static final IntVector INT_ZERO = IntVector.broadcast(I, 0);

    /**
     * The largest sample value in every lane.
     */
    private static final IntVector MAX_SAMPLE = IntVector.broadcast(I, 255);

    /**
     * One half in every float lane.
     */
    private static final FloatVector HALF = FloatVector.broadcast(F, 0.5f);

    /**
     * One in every float lane.
     */
    private static final FloatVector ONE = FloatVector.broadcast(F, 1);

    /**
     * The scalar kernel handling the ends of runs.
     */
    private final ScalarWarpKernel scalar;

    /**
     * Constructs the kernel.
     */
    private VectorWarpKernel() {
        this.scalar = new ScalarWarpKernel();
    }

    /**
     * Creates the kernel.
     *
     * @return the vector kernel
     */
    static WarpKernel create() {
        return new VectorWarpKernel();
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
    public void accumulate2(int[] src, int offset, int[] base, float[] w0, float[] w1, float[] wy, float[] red,
                            float[] green, float[] blue, float[] alpha, int count) {
        var i = 0;
        for (; i + L <= count; i += L) {
            var row = FLOAT_VECTOR.cast(FloatVector.fromArray(F, wy, i));
            var r = FLOAT_VECTOR.cast(FloatVector.fromArray(F, red, i));
            var g = FLOAT_VECTOR.cast(FloatVector.fromArray(F, green, i));
            var b = FLOAT_VECTOR.cast(FloatVector.fromArray(F, blue, i));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(F, alpha, i));
            var p = INT_VECTOR.cast(IntVector.fromArray(I, src, offset, base, i));
            var a = FLOAT_VECTOR.cast(FloatVector.fromArray(F, w0, i)).lanewise(VectorOperators.MUL, row)
                    .lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(INT_VECTOR.cast(p).lanewise(VectorOperators.LSHR, 24)
                            .convertShape(VectorOperators.I2F, F, 0)));
            r = r.lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(p
                    .lanewise(VectorOperators.AND, LOW_BYTE).convertShape(VectorOperators.I2F, F, 0))));
            g = g.lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(INT_VECTOR.cast(p)
                    .lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE).convertShape(VectorOperators.I2F, F, 0))));
            b = b.lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(INT_VECTOR.cast(p)
                    .lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.AND, LOW_BYTE).convertShape(VectorOperators.I2F, F, 0))));
            s = s.lanewise(VectorOperators.ADD, a);
            var q = INT_VECTOR.cast(IntVector.fromArray(I, src, offset + 1, base, i));
            var c = FLOAT_VECTOR.cast(FloatVector.fromArray(F, w1, i)).lanewise(VectorOperators.MUL, row)
                    .lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(INT_VECTOR.cast(q).lanewise(VectorOperators.LSHR, 24)
                            .convertShape(VectorOperators.I2F, F, 0)));
            r = r.lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(q
                    .lanewise(VectorOperators.AND, LOW_BYTE).convertShape(VectorOperators.I2F, F, 0))));
            g = g.lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(INT_VECTOR.cast(q)
                    .lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE).convertShape(VectorOperators.I2F, F, 0))));
            b = b.lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(INT_VECTOR.cast(q)
                    .lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.AND, LOW_BYTE).convertShape(VectorOperators.I2F, F, 0))));
            s = s.lanewise(VectorOperators.ADD, c);
            FLOAT_VECTOR.cast(r).intoArray(red, i);
            FLOAT_VECTOR.cast(g).intoArray(green, i);
            FLOAT_VECTOR.cast(b).intoArray(blue, i);
            FLOAT_VECTOR.cast(s).intoArray(alpha, i);
        }
        scalar.accumulate2Range(src, offset, base, w0, w1, wy, red, green, blue, alpha, i, count);
    }

    @Override
    public void accumulate4(int[] src, int offset, int[] base, float[] w0, float[] w1, float[] w2, float[] w3,
                            float[] wy, float[] red, float[] green, float[] blue, float[] alpha, int count) {
        accumulate2(src, offset, base, w0, w1, wy, red, green, blue, alpha, count);
        accumulate2(src, offset + 2, base, w2, w3, wy, red, green, blue, alpha, count);
    }

    @Override
    public void resolve(float[] red, float[] green, float[] blue, float[] alpha, int count, byte[] dst, int dstOffset) {
        var i = 0;
        for (; i + L <= count; i += L) {
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(F, alpha, i));
            var a = INT_VECTOR.cast(FLOAT_VECTOR.cast(s.lanewise(VectorOperators.ADD, HALF))
                    .convertShape(VectorOperators.F2I, I, 0));
            var visible = a.compare(VectorOperators.GT, INT_ZERO);
            var reciprocal = ONE.lanewise(VectorOperators.DIV, s);
            var r = INT_VECTOR.cast(FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(FloatVector.fromArray(F, red, i))
                    .lanewise(VectorOperators.MUL, reciprocal).lanewise(VectorOperators.ADD, HALF))
                    .convertShape(VectorOperators.F2I, I, 0)).lanewise(VectorOperators.MAX, INT_ZERO)
                    .lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var g = INT_VECTOR.cast(FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(FloatVector.fromArray(F, green, i))
                    .lanewise(VectorOperators.MUL, reciprocal).lanewise(VectorOperators.ADD, HALF))
                    .convertShape(VectorOperators.F2I, I, 0)).lanewise(VectorOperators.MAX, INT_ZERO)
                    .lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var b = INT_VECTOR.cast(FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(FloatVector.fromArray(F, blue, i))
                    .lanewise(VectorOperators.MUL, reciprocal).lanewise(VectorOperators.ADD, HALF))
                    .convertShape(VectorOperators.F2I, I, 0)).lanewise(VectorOperators.MAX, INT_ZERO)
                    .lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var opaque = a.lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var packed = r.lanewise(VectorOperators.OR, INT_VECTOR.cast(g).lanewise(VectorOperators.LSHL, 8))
                    .lanewise(VectorOperators.OR, INT_VECTOR.cast(b).lanewise(VectorOperators.LSHL, 16))
                    .lanewise(VectorOperators.OR, INT_VECTOR.cast(opaque).lanewise(VectorOperators.LSHL, 24));
            var pixels = INT_ZERO.blend(packed, visible);
            PIXEL_VECTOR.cast(INT_VECTOR.cast(pixels).reinterpretShape(BP, 0)).intoArray(dst, dstOffset + 4 * i);
        }
        scalar.resolveRange(red, green, blue, alpha, dst, dstOffset, i, count);
    }
}
