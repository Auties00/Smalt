package com.github.auties00.smalt.video.av1.internal;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements the loop restoration kernels with the Vector API, producing output identical to
 * {@link ScalarLoopRestorationDsp}.
 *
 * <p>The Wiener filter's row spans run 8 samples per vector in 32-bit lanes: the horizontal span
 * widens 8 samples per tap and accumulates the 7 taps, the vertical span accumulates 7 ring rows;
 * the last samples of a span that do not fill a vector use the scalar span. Everything else, the
 * streaming of the stripe through the ring of rows and the self-guided filter, is inherited from
 * {@link ScalarLoopRestorationDsp}.
 *
 * <p>Every Vector API operation goes through the per-shape methods, no method takes or returns a
 * vector, and every loaded or converted vector, and every vector stored or shifted by a scalar, is
 * first cast through the {@code static final} class of its species; see {@link VectorMcDsp}.
 *
 * @implNote This implementation follows dav1d's {@code wiener_filter_h} and
 *           {@code wiener_filter_v} x86 kernels ({@code src/x86/looprestoration16_avx2.asm}), which
 *           also filter a row 8 or 16 samples at a time with 32-bit accumulation.
 */
public final class VectorLoopRestorationDsp extends ScalarLoopRestorationDsp {
    /**
     * The 8-lane int species of the spans.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * The 8-lane short species of the span's sample loads and stores.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * The exact class of {@link #I256} vectors.
     */
    private static final Class<IntVector> INT256 = vectorClass(I256);

    /**
     * The exact class of {@link #S128} vectors.
     */
    private static final Class<ShortVector> SHORT128 = vectorClass(S128);

    /**
     * Constructs the kernels.
     */
    private VectorLoopRestorationDsp() {
    }

    /**
     * Creates the kernels.
     *
     * @return the vector kernels
     */
    static LoopRestorationDsp create() {
        return new VectorLoopRestorationDsp();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the species whose vectors are asked for
     * @param <E>     the element type
     * @param <V>     the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    void wienerHSpan(int[] sc, int out, short[] src, int srcOff, int x0, int x1, short[] filter, int bias, int shift,
                     int clipLimit) {
        var c0 = IntVector.broadcast(I256, filter[0]);
        var c1 = IntVector.broadcast(I256, filter[1]);
        var c2 = IntVector.broadcast(I256, filter[2]);
        var c3 = IntVector.broadcast(I256, filter[3]);
        var c4 = IntVector.broadcast(I256, filter[4]);
        var c5 = IntVector.broadcast(I256, filter[5]);
        var c6 = IntVector.broadcast(I256, filter[6]);
        var start = IntVector.broadcast(I256, bias);
        var zero = IntVector.zero(I256);
        var limit = IntVector.broadcast(I256, clipLimit);
        var x = x0;
        for (; x + 8 <= x1; x += 8) {
            var s = srcOff + x - 3;
            var s0 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, s))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s1 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, s + 1))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s2 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, s + 2))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s3 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, s + 3))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s4 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, s + 4))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s5 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, s + 5))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s6 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, s + 6))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var sum = s0.lanewise(VectorOperators.MUL, INT256.cast(c0))
                    .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, INT256.cast(c1)))
                    .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, INT256.cast(c2)))
                    .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, INT256.cast(c3)))
                    .lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, INT256.cast(c4)))
                    .lanewise(VectorOperators.ADD, s5.lanewise(VectorOperators.MUL, INT256.cast(c5)))
                    .lanewise(VectorOperators.ADD, s6.lanewise(VectorOperators.MUL, INT256.cast(c6)))
                    .lanewise(VectorOperators.ADD, INT256.cast(start));
            INT256.cast(INT256.cast(sum).lanewise(VectorOperators.ASHR, shift)
                            .lanewise(VectorOperators.MAX, INT256.cast(zero))
                            .lanewise(VectorOperators.MIN, INT256.cast(limit)))
                    .intoArray(sc, out + x);
        }
        if (x < x1) {
            super.wienerHSpan(sc, out, src, srcOff, x, x1, filter, bias, shift, clipLimit);
        }
    }

    @Override
    void wienerVSpan(int[] sc, short[] dst, int dstOff, int r0, int r1, int r2, int r3, int r4, int r5, int r6,
                     short[] filter, int w, int bias, int shift, int bitdepthMax) {
        var c0 = IntVector.broadcast(I256, filter[8]);
        var c1 = IntVector.broadcast(I256, filter[9]);
        var c2 = IntVector.broadcast(I256, filter[10]);
        var c3 = IntVector.broadcast(I256, filter[11]);
        var c4 = IntVector.broadcast(I256, filter[12]);
        var c5 = IntVector.broadcast(I256, filter[13]);
        var c6 = IntVector.broadcast(I256, filter[14]);
        var start = IntVector.broadcast(I256, bias);
        var zero = IntVector.zero(I256);
        var top = IntVector.broadcast(I256, bitdepthMax);
        var i = 0;
        for (; i + 8 <= w; i += 8) {
            var sum = INT256.cast(IntVector.fromArray(I256, sc, r0 + i)).lanewise(VectorOperators.MUL, INT256.cast(c0))
                    .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, sc, r1 + i))
                            .lanewise(VectorOperators.MUL, INT256.cast(c1)))
                    .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, sc, r2 + i))
                            .lanewise(VectorOperators.MUL, INT256.cast(c2)))
                    .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, sc, r3 + i))
                            .lanewise(VectorOperators.MUL, INT256.cast(c3)))
                    .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, sc, r4 + i))
                            .lanewise(VectorOperators.MUL, INT256.cast(c4)))
                    .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, sc, r5 + i))
                            .lanewise(VectorOperators.MUL, INT256.cast(c5)))
                    .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, sc, r6 + i))
                            .lanewise(VectorOperators.MUL, INT256.cast(c6)))
                    .lanewise(VectorOperators.ADD, INT256.cast(start));
            var value = INT256.cast(sum).lanewise(VectorOperators.ASHR, shift)
                    .lanewise(VectorOperators.MAX, INT256.cast(zero)).lanewise(VectorOperators.MIN, INT256.cast(top));
            SHORT128.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, dstOff + i);
        }
        if (i < w) {
            super.wienerVSpan(sc, dst, dstOff + i, r0 + i, r1 + i, r2 + i, r3 + i, r4 + i, r5 + i, r6 + i, filter,
                    w - i, bias, shift, bitdepthMax);
        }
    }
}
