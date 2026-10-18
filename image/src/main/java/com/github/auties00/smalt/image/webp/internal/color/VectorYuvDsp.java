package com.github.auties00.smalt.image.webp.internal.color;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements the color conversion kernels with the Vector API, one vector of pixels at a time,
 * with the scalar kernel finishing each tail.
 *
 * <p>Every Vector API operation goes through the per-shape methods ({@code lanewise} with an
 * operator and a vector operand, {@code compare}, {@code blend}, {@code rearrange}), constants
 * are {@code static final} vectors, no method takes or returns a vector, and a vector carried
 * across loop iterations is only ever an operand. The convenience methods ({@code add},
 * {@code min}, the scalar-operand overloads) dispatch through code that every vector shape
 * shares: when the JIT compiler meets them in a kernel it compiled into a caller before
 * profiling it, or in a program that uses several shapes, it can leave them unintrinsified, and
 * every call then runs an allocating Java fallback.
 * The methods the abstract vector classes implement once for every shape ({@code intoArray},
 * {@code lanewise} with a scalar operand, {@code reinterpretAsInts} and its siblings) call
 * methods on the vector itself, which the JIT compiler binds through a type profile that every
 * shape of the program shares: unless it knows the vector's exact class it keeps a virtual call,
 * and the vector is then allocated on every execution. Reinterpretations therefore use the
 * per-shape {@code reinterpretShape}, and every load, conversion and reinterpretation is cast
 * through the constant exact class of its species, which gives the JIT compiler the class
 * without a type profile.
 *
 * @implNote This class references {@code jdk.incubator.vector} types, so it is loaded only when
 * {@link com.github.auties00.smalt.internal.simd.Simd#ENABLED} is {@code true}. It needs at
 * least eight integer lanes so that the luma bytes of a vector fill a 64-bit byte vector; on
 * narrower hardware {@link #create()} returns the scalar kernel.
 */
final class VectorYuvDsp implements YuvDsp {
    /**
     * The integer vector shape.
     */
    private static final VectorSpecies<Integer> INTS = IntVector.SPECIES_PREFERRED;

    /**
     * The byte vector shape holding one byte per integer lane.
     */
    private static final VectorSpecies<Byte> BYTES_PER_LANE =
            VectorSpecies.of(byte.class, VectorShape.forBitSize(Math.max(64, INTS.length() * 8)));

    /**
     * The byte vector shape holding four bytes per integer lane.
     */
    private static final VectorSpecies<Byte> BYTES_PER_PIXEL = VectorSpecies.of(byte.class, INTS.vectorShape());

    /**
     * The exact class of the vectors of {@link #INTS}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(INTS);

    /**
     * The exact class of the vectors of {@link #BYTES_PER_LANE}.
     */
    private static final Class<ByteVector> LANE_BYTE_VECTOR = vectorClass(BYTES_PER_LANE);

    /**
     * The exact class of the vectors of {@link #BYTES_PER_PIXEL}.
     */
    private static final Class<ByteVector> PIXEL_BYTE_VECTOR = vectorClass(BYTES_PER_PIXEL);

    /**
     * The ints of {@link #INTS} equal to {@code 0}.
     */
    private static final IntVector INTS_0 = IntVector.zero(INTS);

    /**
     * The ints of {@link #INTS} equal to {@code 255}.
     */
    private static final IntVector INTS_255 = IntVector.broadcast(INTS, 255);

    /**
     * The ints of {@link #INTS} equal to {@code 6419}.
     */
    private static final IntVector INTS_1913 = IntVector.broadcast(INTS, 6419);

    /**
     * The ints of {@link #INTS} equal to {@code 6420}.
     */
    private static final IntVector INTS_1914 = IntVector.broadcast(INTS, 6420);

    /**
     * The ints of {@link #INTS} equal to {@code 8708}.
     */
    private static final IntVector INTS_2204 = IntVector.broadcast(INTS, 8708);

    /**
     * The ints of {@link #INTS} equal to {@code 13320}.
     */
    private static final IntVector INTS_3408 = IntVector.broadcast(INTS, 13320);

    /**
     * The ints of {@link #INTS} equal to {@code 14234}.
     */
    private static final IntVector INTS_379A = IntVector.broadcast(INTS, 14234);

    /**
     * The ints of {@link #INTS} equal to {@code 16839}.
     */
    private static final IntVector INTS_41C7 = IntVector.broadcast(INTS, 16839);

    /**
     * The ints of {@link #INTS} equal to {@code 17685}.
     */
    private static final IntVector INTS_4515 = IntVector.broadcast(INTS, 17685);

    /**
     * The ints of {@link #INTS} equal to {@code 19077}.
     */
    private static final IntVector INTS_4A85 = IntVector.broadcast(INTS, 19077);

    /**
     * The ints of {@link #INTS} equal to {@code 26149}.
     */
    private static final IntVector INTS_6625 = IntVector.broadcast(INTS, 26149);

    /**
     * The ints of {@link #INTS} equal to {@code 33050}.
     */
    private static final IntVector INTS_811A = IntVector.broadcast(INTS, 33050);

    /**
     * The ints of {@link #INTS} equal to {@code 33059}.
     */
    private static final IntVector INTS_8123 = IntVector.broadcast(INTS, 33059);

    /**
     * The ints of {@link #INTS} equal to {@code 0xFF000000}.
     */
    private static final IntVector INTS_FF000000 = IntVector.broadcast(INTS, 0xFF000000);

    /**
     * The reference kernel used for tails.
     */
    private final ScalarYuvDsp scalar;

    /**
     * Constructs the kernel.
     */
    private VectorYuvDsp() {
        this.scalar = new ScalarYuvDsp();
    }

    /**
     * Creates the vectorized kernel, or the scalar one when vectors hold fewer than eight
     * integers.
     *
     * @return the kernel
     */
    static YuvDsp create() {
        return INTS.length() >= 8 ? new VectorYuvDsp() : new ScalarYuvDsp();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the species
     * @param <E>     the element type
     * @param <V>     the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void yuvToRgbaRow(byte[] y, int yPos, int[] u, int[] v, byte[] dst, int dstPos, int count) {
        var bound = INTS.loopBound(count);
        var i = 0;
        for (; i < bound; i += INTS.length()) {
            var yy = INT_VECTOR.cast(LANE_BYTE_VECTOR.cast(ByteVector.fromArray(BYTES_PER_LANE, y, yPos + i))
                    .convertShape(VectorOperators.B2I, INTS, 0)).lanewise(VectorOperators.AND, INTS_255)
                    .lanewise(VectorOperators.MUL, INTS_4A85).lanewise(VectorOperators.ASHR, 8);
            var uu = INT_VECTOR.cast(IntVector.fromArray(INTS, u, i));
            var vv = INT_VECTOR.cast(IntVector.fromArray(INTS, v, i));
            var r = yy.lanewise(VectorOperators.ADD, vv.lanewise(VectorOperators.MUL, INTS_6625).lanewise(VectorOperators.ASHR, 8)).lanewise(VectorOperators.SUB, INTS_379A)
                    .lanewise(VectorOperators.ASHR, 6).lanewise(VectorOperators.MAX, INTS_0).lanewise(VectorOperators.MIN, INTS_255);
            var g = yy.lanewise(VectorOperators.SUB, uu.lanewise(VectorOperators.MUL, INTS_1913).lanewise(VectorOperators.ASHR, 8))
                    .lanewise(VectorOperators.SUB, vv.lanewise(VectorOperators.MUL, INTS_3408).lanewise(VectorOperators.ASHR, 8)).lanewise(VectorOperators.ADD, INTS_2204)
                    .lanewise(VectorOperators.ASHR, 6).lanewise(VectorOperators.MAX, INTS_0).lanewise(VectorOperators.MIN, INTS_255);
            var b = yy.lanewise(VectorOperators.ADD, uu.lanewise(VectorOperators.MUL, INTS_811A).lanewise(VectorOperators.ASHR, 8)).lanewise(VectorOperators.SUB, INTS_4515)
                    .lanewise(VectorOperators.ASHR, 6).lanewise(VectorOperators.MAX, INTS_0).lanewise(VectorOperators.MIN, INTS_255);
            var packed = r.lanewise(VectorOperators.OR, g.lanewise(VectorOperators.LSHL, 8)).lanewise(VectorOperators.OR, b.lanewise(VectorOperators.LSHL, 16)).lanewise(VectorOperators.OR, INTS_FF000000);
            PIXEL_BYTE_VECTOR.cast(packed.reinterpretShape(BYTES_PER_PIXEL, 0)).intoArray(dst, dstPos + (i << 2));
        }
        scalar.yuvToRgbaRowFrom(y, yPos, u, v, dst, dstPos, i, count);
    }

    @Override
    public void rgbaToYRow(byte[] rgba, int rgbaPos, byte[] y, int yPos, int count) {
        var bound = INTS.loopBound(count);
        var i = 0;
        for (; i < bound; i += INTS.length()) {
            var px = INT_VECTOR.cast(PIXEL_BYTE_VECTOR.cast(ByteVector.fromArray(BYTES_PER_PIXEL, rgba, rgbaPos + (i << 2))).reinterpretShape(INTS, 0));
            var r = px.lanewise(VectorOperators.AND, INTS_255);
            var g = px.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, INTS_255);
            var b = px.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.AND, INTS_255);
            var luma = r.lanewise(VectorOperators.MUL, INTS_41C7).lanewise(VectorOperators.ADD, g.lanewise(VectorOperators.MUL, INTS_8123)).lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, INTS_1914)).lanewise(VectorOperators.ADD, IntVector.broadcast(INTS, (int) ((1 << 15) + (16 << 16))))
                    .lanewise(VectorOperators.ASHR, 16);
            LANE_BYTE_VECTOR.cast(luma.convertShape(VectorOperators.I2B, BYTES_PER_LANE, 0))
                    .intoArray(y, yPos + i);
        }
        scalar.rgbaToYRow(rgba, rgbaPos + (i << 2), y, yPos + i, count - i);
    }
}
