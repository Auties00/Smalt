package com.github.auties00.smalt.image.jpeg.internal;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import static com.github.auties00.smalt.image.jpeg.internal.ScalarColorKernel.*;

/**
 * Converts RGBA rows to Y'CbCr with the Vector API, eight pixels at a time.
 *
 * <p>Eight packed pixels are loaded as eight 32-bit lanes, the red, green and blue samples are
 * extracted with shifts and masks, and the three weighted sums are narrowed to bytes. A row tail
 * shorter than eight pixels is converted with scalar arithmetic. The output equals
 * {@link ScalarColorKernel}'s.
 *
 * <p>Every vector that a load or conversion produces is cast to the exact class of its species
 * before it is used as the receiver of a call, and so is the receiver of every {@code intoArray},
 * reinterpretation and shift by a scalar count, so that the compiler binds those calls without
 * consulting receiver-type profiles; arithmetic uses {@code lanewise} with vector constants and
 * no vector crosses a method boundary.
 * Reinterpretations use the per-shape {@code reinterpretShape}, never the {@code reinterpretAs}
 * methods.
 *
 * <p>This class references {@code jdk.incubator.vector} types and is only loaded through
 * {@link VectorKernels} when the Vector API is available.
 */
final class VectorColorKernel implements ColorKernel {
    /**
     * The species of eight 32-bit lanes.
     */
    private static final VectorSpecies<Integer> INTS = IntVector.SPECIES_256;

    /**
     * The species of 32 bytes, eight packed pixels.
     */
    private static final VectorSpecies<Byte> PIXELS = ByteVector.SPECIES_256;

    /**
     * The species of eight bytes, one output sample per lane.
     */
    private static final VectorSpecies<Byte> SAMPLES = ByteVector.SPECIES_64;

    /**
     * The exact class of the vectors of {@link #INTS}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(INTS);

    /**
     * The exact class of the vectors of {@link #PIXELS}.
     */
    private static final Class<ByteVector> PIXEL_VECTOR = vectorClass(PIXELS);

    /**
     * The exact class of the vectors of {@link #SAMPLES}.
     */
    private static final Class<ByteVector> SAMPLE_VECTOR = vectorClass(SAMPLES);

    /**
     * The low byte of every lane.
     */
    private static final IntVector LOW_BYTE = IntVector.broadcast(INTS, 0xFF);

    /**
     * {@link ScalarColorKernel#R_Y} in every lane.
     */
    private static final IntVector V_R_Y = IntVector.broadcast(INTS, R_Y);

    /**
     * {@link ScalarColorKernel#G_Y} in every lane.
     */
    private static final IntVector V_G_Y = IntVector.broadcast(INTS, G_Y);

    /**
     * {@link ScalarColorKernel#B_Y} in every lane.
     */
    private static final IntVector V_B_Y = IntVector.broadcast(INTS, B_Y);

    /**
     * {@link ScalarColorKernel#R_CB} in every lane.
     */
    private static final IntVector V_R_CB = IntVector.broadcast(INTS, R_CB);

    /**
     * {@link ScalarColorKernel#G_CB} in every lane.
     */
    private static final IntVector V_G_CB = IntVector.broadcast(INTS, G_CB);

    /**
     * {@link ScalarColorKernel#G_CR} in every lane.
     */
    private static final IntVector V_G_CR = IntVector.broadcast(INTS, G_CR);

    /**
     * {@link ScalarColorKernel#B_CR} in every lane.
     */
    private static final IntVector V_B_CR = IntVector.broadcast(INTS, B_CR);

    /**
     * {@link ScalarColorKernel#HALF} in every lane.
     */
    private static final IntVector V_HALF = IntVector.broadcast(INTS, HALF);

    /**
     * {@link ScalarColorKernel#Y_ROUND} in every lane.
     */
    private static final IntVector V_Y_ROUND = IntVector.broadcast(INTS, Y_ROUND);

    /**
     * {@link ScalarColorKernel#C_ROUND} in every lane.
     */
    private static final IntVector V_C_ROUND = IntVector.broadcast(INTS, C_ROUND);

    /**
     * Constructs the kernel.
     */
    VectorColorKernel() {
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the vector shape
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void toYcc(byte[] rgba, int offset, int count, byte[] y, byte[] cb, byte[] cr) {
        var i = 0;
        for (; i + 8 <= count; i += 8) {
            var pixels = INT_VECTOR.cast(PIXEL_VECTOR.cast(ByteVector.fromArray(PIXELS, rgba, offset + 4 * i))
                    .reinterpretShape(INTS, 0));
            var r = pixels.lanewise(VectorOperators.AND, LOW_BYTE);
            var g = pixels.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE);
            var b = pixels.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.AND, LOW_BYTE);
            var luma = r.lanewise(VectorOperators.MUL, V_R_Y).lanewise(VectorOperators.ADD, g
                    .lanewise(VectorOperators.MUL, V_G_Y))
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, V_B_Y))
                    .lanewise(VectorOperators.ADD, V_Y_ROUND);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(luma).lanewise(VectorOperators.LSHR, 16)
                    .convertShape(VectorOperators.I2B, SAMPLES, 0)).intoArray(y, i);
            var blue = b.lanewise(VectorOperators.MUL, V_HALF).lanewise(VectorOperators.SUB, r
                    .lanewise(VectorOperators.MUL, V_R_CB))
                    .lanewise(VectorOperators.SUB, g.lanewise(VectorOperators.MUL, V_G_CB))
                    .lanewise(VectorOperators.ADD, V_C_ROUND);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(blue).lanewise(VectorOperators.LSHR, 16)
                    .convertShape(VectorOperators.I2B, SAMPLES, 0)).intoArray(cb, i);
            var red = r.lanewise(VectorOperators.MUL, V_HALF).lanewise(VectorOperators.SUB, g
                    .lanewise(VectorOperators.MUL, V_G_CR))
                    .lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.MUL, V_B_CR))
                    .lanewise(VectorOperators.ADD, V_C_ROUND);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(red).lanewise(VectorOperators.LSHR, 16)
                    .convertShape(VectorOperators.I2B, SAMPLES, 0)).intoArray(cr, i);
        }
        for (; i < count; i++) {
            var p = offset + 4 * i;
            var r = rgba[p] & 0xFF;
            var g = rgba[p + 1] & 0xFF;
            var b = rgba[p + 2] & 0xFF;
            y[i] = (byte) ((R_Y * r + G_Y * g + B_Y * b + Y_ROUND) >>> 16);
            cb[i] = (byte) ((-R_CB * r - G_CB * g + HALF * b + C_ROUND) >>> 16);
            cr[i] = (byte) ((HALF * r - G_CR * g - B_CR * b + C_ROUND) >>> 16);
        }
    }

    @Override
    public void toGray(byte[] rgba, int offset, int count, byte[] y) {
        var i = 0;
        for (; i + 8 <= count; i += 8) {
            var pixels = INT_VECTOR.cast(PIXEL_VECTOR.cast(ByteVector.fromArray(PIXELS, rgba, offset + 4 * i))
                    .reinterpretShape(INTS, 0));
            var r = pixels.lanewise(VectorOperators.AND, LOW_BYTE);
            var g = pixels.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE);
            var b = pixels.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.AND, LOW_BYTE);
            var luma = r.lanewise(VectorOperators.MUL, V_R_Y).lanewise(VectorOperators.ADD, g
                    .lanewise(VectorOperators.MUL, V_G_Y))
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, V_B_Y))
                    .lanewise(VectorOperators.ADD, V_Y_ROUND);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(luma).lanewise(VectorOperators.LSHR, 16)
                    .convertShape(VectorOperators.I2B, SAMPLES, 0)).intoArray(y, i);
        }
        for (; i < count; i++) {
            var p = offset + 4 * i;
            y[i] = (byte) ((R_Y * (rgba[p] & 0xFF) + G_Y * (rgba[p + 1] & 0xFF) + B_Y * (rgba[p + 2] & 0xFF) + Y_ROUND) >>> 16);
        }
    }
}
