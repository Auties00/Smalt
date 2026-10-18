package com.github.auties00.smalt.audio.opus.internal;

import com.github.auties00.smalt.audio.opus.internal.celt.MathOps;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link AnalysisKernels} with the Vector API; the output equals {@link ScalarAnalysisKernels} bit for
 * bit.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd Simd};
 * {@link #phases} and {@link #chunk} are separate kernels to stay under the JIT inlining budget. Each lane evaluates
 * one bin with both branches of {@code fast_atan2f} and rounds through the exact {@code (v + 1.5 * 2^23) - 1.5 * 2^23}
 * identity, valid below {@code 2^22}; a chunk holding a larger or non-finite value is redone by the scalar code before
 * any state is written.
 */
final class VectorAnalysisKernels implements AnalysisKernels {
    /**
     * The preferred float species of the platform.
     */
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    /**
     * The number of float lanes in {@link #SPECIES}.
     */
    private static final int LANES = SPECIES.length();

    /**
     * The exact class of the vectors of {@link #SPECIES}.
     */
    private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(SPECIES);

    /**
     * The constant whose addition and subtraction rounds a float below {@code 2^22} to the
     * nearest integer, ties to even.
     */
    private static final float ROUNDER = 12582912f;

    /**
     * The magnitude below which {@link #ROUNDER} rounds exactly, {@code 2^22}.
     */
    private static final float ROUNDABLE = 4194304f;

    /**
     * The first coefficient of the {@code fast_atan2f} rational approximation.
     */
    private static final float CA = 0.43157974f;

    /**
     * The second coefficient of the {@code fast_atan2f} rational approximation.
     */
    private static final float CB = 0.67848403f;

    /**
     * The third coefficient of the {@code fast_atan2f} rational approximation.
     */
    private static final float CC = 0.08595542f;

    /**
     * A quarter turn in radians, {@code PI / 2} in single precision.
     */
    private static final float CE = MathOps.PI / 2;

    /**
     * Creates the vector kernels.
     */
    private VectorAnalysisKernels() {
    }

    /**
     * Creates the vector kernels.
     *
     * @return the kernels
     */
    static AnalysisKernels create() {
        return new VectorAnalysisKernels();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the species whose vector class to return
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void tonalityBins(float[] re, float[] im, float[] reRev, float[] imRev, float[] A, float[] dA, float[] d2A,
                             float[] noisiness, float[] tonality, float[] tonality2) {
        var bound = 1 + (239 / LANES) * LANES;
        phases(re, reRev, im, imRev, tonality, bound);
        phases(im, imRev, reRev, re, tonality2, bound);
        for (var i = 1; i < bound; i += LANES) {
            if (!chunk(i, A, dA, d2A, noisiness, tonality, tonality2)) {
                ScalarAnalysisKernels.bins(i, i + LANES, re, im, reRev, imRev, A, dA, d2A, noisiness, tonality, tonality2);
            }
        }
        if (bound < 240) {
            ScalarAnalysisKernels.bins(bound, 240, re, im, reRev, imRev, A, dA, d2A, noisiness, tonality, tonality2);
        }
    }

    /**
     * Computes the phase, in turns, of the spectrum {@code (xa + xb) + i (ya - yb)} for the bins
     * {@code [1, bound)} with {@code fast_atan2f}.
     *
     * <p>Every lane evaluates both branches of {@code fast_atan2f} and selects the one the scalar
     * code takes.
     *
     * @param xa    the first addend of the real part
     * @param xb    the second addend of the real part
     * @param ya    the minuend of the imaginary part
     * @param yb    the subtrahend of the imaginary part
     * @param out   receives the phases
     * @param bound the end of the bins, one more than a multiple of {@link #LANES}
     */
    private static void phases(float[] xa, float[] xb, float[] ya, float[] yb, float[] out, int bound) {
        var turns = FloatVector.broadcast(SPECIES, TURNS);
        var zero = FloatVector.zero(SPECIES);
        var tinyLimit = FloatVector.broadcast(SPECIES, 1e-18f);
        var ca = FloatVector.broadcast(SPECIES, CA);
        var cb = FloatVector.broadcast(SPECIES, CB);
        var cc = FloatVector.broadcast(SPECIES, CC);
        var ce = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, CE));
        var minusCe = FloatVector.broadcast(SPECIES, -CE);
        for (var i = 1; i < bound; i += LANES) {
            var x = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, xa, i))
                    .lanewise(VectorOperators.ADD, FloatVector.fromArray(SPECIES, xb, i));
            var y = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, ya, i))
                    .lanewise(VectorOperators.SUB, FloatVector.fromArray(SPECIES, yb, i));
            var x2 = x.lanewise(VectorOperators.MUL, x);
            var y2 = y.lanewise(VectorOperators.MUL, y);
            var tiny = x2.lanewise(VectorOperators.ADD, y2).compare(VectorOperators.LT, tinyLimit);
            var steep = x2.compare(VectorOperators.LT, y2);
            var ySign = FLOAT_VECTOR.cast(ce).blend(minusCe, y.compare(VectorOperators.LT, zero));
            var xy = x.lanewise(VectorOperators.MUL, y);
            var steepDen = y2.lanewise(VectorOperators.ADD, x2.lanewise(VectorOperators.MUL, cb))
                    .lanewise(VectorOperators.MUL, y2.lanewise(VectorOperators.ADD, x2.lanewise(VectorOperators.MUL, cc)));
            var steepValue = x.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, y)
                    .lanewise(VectorOperators.MUL, y2.lanewise(VectorOperators.ADD, x2.lanewise(VectorOperators.MUL, ca)))
                    .lanewise(VectorOperators.DIV, steepDen).lanewise(VectorOperators.ADD, ySign);
            var flatDen = x2.lanewise(VectorOperators.ADD, y2.lanewise(VectorOperators.MUL, cb))
                    .lanewise(VectorOperators.MUL, x2.lanewise(VectorOperators.ADD, y2.lanewise(VectorOperators.MUL, cc)));
            var xySign = FLOAT_VECTOR.cast(ce).blend(minusCe, xy.compare(VectorOperators.LT, zero));
            var flatValue = xy.lanewise(VectorOperators.MUL, x2.lanewise(VectorOperators.ADD, y2.lanewise(VectorOperators.MUL, ca)))
                    .lanewise(VectorOperators.DIV, flatDen).lanewise(VectorOperators.ADD, ySign)
                    .lanewise(VectorOperators.SUB, xySign);
            flatValue.blend(steepValue, steep).blend(zero, tiny).lanewise(VectorOperators.MUL, turns).intoArray(out, i);
        }
    }

    /**
     * Evaluates the bins of one chunk of {@link #LANES} lanes starting at {@code i} from the phases
     * {@link #phases} left in {@code tonality} and {@code tonality2}, or leaves the state arrays
     * untouched when a lane's phase deviation is too large to round exactly.
     *
     * @param i         the first bin of the chunk
     * @param A         the previous phases, updated
     * @param dA        the previous phase differences, updated
     * @param d2A       the previous second differences, updated
     * @param noisiness receives the noisiness of each bin
     * @param tonality  holds the first phase of each bin; receives the tonality
     * @param tonality2 holds the second phase of each bin; receives the single-frame tonality
     * @return {@code true} if the chunk was evaluated, {@code false} if the scalar code must evaluate it
     */
    private static boolean chunk(int i, float[] A, float[] dA, float[] d2A, float[] noisiness, float[] tonality,
                                 float[] tonality2) {
        var angle = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tonality, i));
        var angle2 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tonality2, i));
        var d_angle = angle.lanewise(VectorOperators.SUB, FloatVector.fromArray(SPECIES, A, i));
        var d2_angle = d_angle.lanewise(VectorOperators.SUB, FloatVector.fromArray(SPECIES, dA, i));
        var d_angle2 = angle2.lanewise(VectorOperators.SUB, angle);
        var d2_angle2 = d_angle2.lanewise(VectorOperators.SUB, d_angle);
        var roundable = FloatVector.broadcast(SPECIES, ROUNDABLE);
        VectorMask<Float> safe = d2_angle.lanewise(VectorOperators.ABS).compare(VectorOperators.LT, roundable)
                .and(d2_angle2.lanewise(VectorOperators.ABS).compare(VectorOperators.LT, roundable));
        if (!safe.allTrue()) {
            return false;
        }
        var rounder = FloatVector.broadcast(SPECIES, ROUNDER);
        var mod1 = d2_angle.lanewise(VectorOperators.SUB,
                d2_angle.lanewise(VectorOperators.ADD, rounder).lanewise(VectorOperators.SUB, rounder));
        var noise = mod1.lanewise(VectorOperators.ABS);
        mod1 = mod1.lanewise(VectorOperators.MUL, mod1);
        mod1 = mod1.lanewise(VectorOperators.MUL, mod1);
        var mod2 = d2_angle2.lanewise(VectorOperators.SUB,
                d2_angle2.lanewise(VectorOperators.ADD, rounder).lanewise(VectorOperators.SUB, rounder));
        noise = noise.lanewise(VectorOperators.ADD, mod2.lanewise(VectorOperators.ABS));
        mod2 = mod2.lanewise(VectorOperators.MUL, mod2);
        mod2 = mod2.lanewise(VectorOperators.MUL, mod2);
        var weight = FloatVector.broadcast(SPECIES, TONALITY_WEIGHT);
        var one = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, 1.f));
        var bias = FloatVector.broadcast(SPECIES, .015f);
        var avg_mod = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, d2A, i)).lanewise(VectorOperators.ADD, mod1)
                .lanewise(VectorOperators.ADD, mod2.lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, 2f)))
                .lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, .25f));
        one.lanewise(VectorOperators.DIV, avg_mod.lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, one))
                .lanewise(VectorOperators.SUB, bias).intoArray(tonality, i);
        one.lanewise(VectorOperators.DIV, mod2.lanewise(VectorOperators.MUL, weight).lanewise(VectorOperators.ADD, one))
                .lanewise(VectorOperators.SUB, bias).intoArray(tonality2, i);
        noise.intoArray(noisiness, i);
        angle2.intoArray(A, i);
        d_angle2.intoArray(dA, i);
        mod2.intoArray(d2A, i);
        return true;
    }
}
