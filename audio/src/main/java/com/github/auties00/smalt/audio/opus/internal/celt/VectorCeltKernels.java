package com.github.auties00.smalt.audio.opus.internal.celt;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link CeltKernels} with the Vector API; the output equals {@link ScalarCeltKernels} bit for bit.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd Simd},
 * vectorizes across independent outputs and never reassociates a floating-point sum.
 */
final class VectorCeltKernels implements CeltKernels {
    /**
     * The preferred float species of the platform.
     */
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    /**
     * The 128-bit float species used for short runs of lags.
     */
    private static final VectorSpecies<Float> SPECIES_128 = FloatVector.SPECIES_128;

    /**
     * The number of float lanes in {@link #SPECIES}.
     */
    private static final int LANES = SPECIES.length();

    /**
     * The int species with as many lanes as {@link #SPECIES}.
     */
    private static final VectorSpecies<Integer> INT_SPECIES = VectorSpecies.of(int.class, SPECIES.vectorShape());

    /**
     * The lane indices {@code 0, 1, ..., LANES - 1}.
     */
    private static final IntVector IOTA = IntVector.zero(INT_SPECIES).addIndex(1);

    /**
     * The exact class of the vectors of {@link #SPECIES}.
     */
    private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(SPECIES);

    /**
     * The exact class of the vectors of {@link #SPECIES_128}.
     */
    private static final Class<FloatVector> FLOAT_VECTOR_128 = vectorClass(SPECIES_128);

    /**
     * The exact class of the masks of {@link #SPECIES}.
     */
    private static final Class<VectorMask<Float>> FLOAT_MASK = maskClass(SPECIES);

    /**
     * The exact class of the masks of {@link #INT_SPECIES}.
     */
    private static final Class<VectorMask<Integer>> INT_MASK = maskClass(INT_SPECIES);

    /**
     * The scalar kernels that finish the lags left over by the vector loops.
     */
    private final ScalarCeltKernels scalar = new ScalarCeltKernels();

    /**
     * Creates the vector kernels.
     */
    private VectorCeltKernels() {
    }

    /**
     * Creates the vector kernels.
     *
     * @return the kernels
     */
    static CeltKernels create() {
        return new VectorCeltKernels();
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

    /**
     * Returns the exact class of the masks of a species.
     *
     * @param species the species whose mask class to return
     * @param <E> the element type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E> Class<VectorMask<E>> maskClass(VectorSpecies<E> species) {
        return (Class<VectorMask<E>>) species.maskAll(false).getClass();
    }

    @Override
    public void pitchXcorr(float[] x, int xOff, float[] y, int yOff, float[] xcorr, int xcorrOff, int len, int maxPitch) {
        var i = 0;
        for (; i <= maxPitch - 4 * LANES; i += 4 * LANES) {
            var s0 = FloatVector.zero(SPECIES);
            var s1 = FloatVector.zero(SPECIES);
            var s2 = FloatVector.zero(SPECIES);
            var s3 = FloatVector.zero(SPECIES);
            var yi = yOff + i;
            for (var j = 0; j < len; j++) {
                var xj = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, x[xOff + j]));
                var base = yi + j;
                s0 = FLOAT_VECTOR.cast(s0).lanewise(VectorOperators.ADD, xj.lanewise(VectorOperators.MUL, FloatVector.fromArray(SPECIES, y, base)));
                s1 = FLOAT_VECTOR.cast(s1).lanewise(VectorOperators.ADD, xj.lanewise(VectorOperators.MUL,
                        FloatVector.fromArray(SPECIES, y, base + LANES)));
                s2 = FLOAT_VECTOR.cast(s2).lanewise(VectorOperators.ADD, xj.lanewise(VectorOperators.MUL,
                        FloatVector.fromArray(SPECIES, y, base + 2 * LANES)));
                s3 = FLOAT_VECTOR.cast(s3).lanewise(VectorOperators.ADD, xj.lanewise(VectorOperators.MUL,
                        FloatVector.fromArray(SPECIES, y, base + 3 * LANES)));
            }
            FLOAT_VECTOR.cast(s0).intoArray(xcorr, xcorrOff + i);
            FLOAT_VECTOR.cast(s1).intoArray(xcorr, xcorrOff + i + LANES);
            FLOAT_VECTOR.cast(s2).intoArray(xcorr, xcorrOff + i + 2 * LANES);
            FLOAT_VECTOR.cast(s3).intoArray(xcorr, xcorrOff + i + 3 * LANES);
        }
        for (; i <= maxPitch - LANES; i += LANES) {
            var s0 = FloatVector.zero(SPECIES);
            var yi = yOff + i;
            for (var j = 0; j < len; j++) {
                s0 = FLOAT_VECTOR.cast(s0).lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, x[xOff + j]))
                        .lanewise(VectorOperators.MUL, FloatVector.fromArray(SPECIES, y, yi + j)));
            }
            FLOAT_VECTOR.cast(s0).intoArray(xcorr, xcorrOff + i);
        }
        if (LANES > 4) {
            for (; i <= maxPitch - 4; i += 4) {
                var s0 = FloatVector.zero(SPECIES_128);
                var yi = yOff + i;
                for (var j = 0; j < len; j++) {
                    s0 = FLOAT_VECTOR_128.cast(s0).lanewise(VectorOperators.ADD, FLOAT_VECTOR_128.cast(FloatVector.broadcast(SPECIES_128, x[xOff + j]))
                            .lanewise(VectorOperators.MUL, FloatVector.fromArray(SPECIES_128, y, yi + j)));
                }
                FLOAT_VECTOR_128.cast(s0).intoArray(xcorr, xcorrOff + i);
            }
        }
        if (i < maxPitch) {
            scalar.pitchXcorr(x, xOff, y, yOff + i, xcorr, xcorrOff + i, len, maxPitch - i);
        }
    }

    @Override
    public void combFilterConst(float[] y, int yOff, float[] x, int xOff, int T, int N, float g10, float g11, float g12) {
        // In place, a block of LANES outputs may only read samples at least one block back.
        if (y == x && (yOff != xOff || LANES > T - 2)) {
            scalar.combFilterConst(y, yOff, x, xOff, T, N, g10, g11, g12);
            return;
        }
        var gain0 = FloatVector.broadcast(SPECIES, g10);
        var gain1 = FloatVector.broadcast(SPECIES, g11);
        var gain2 = FloatVector.broadcast(SPECIES, g12);
        var i = 0;
        for (; i <= N - LANES; i += LANES) {
            var b = xOff + i;
            var v = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, x, b))
                    .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, x, b - T))
                            .lanewise(VectorOperators.MUL, gain0))
                    .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, x, b - T + 1))
                            .lanewise(VectorOperators.ADD, FloatVector.fromArray(SPECIES, x, b - T - 1))
                            .lanewise(VectorOperators.MUL, gain1))
                    .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, x, b - T + 2))
                            .lanewise(VectorOperators.ADD, FloatVector.fromArray(SPECIES, x, b - T - 2))
                            .lanewise(VectorOperators.MUL, gain2));
            v.intoArray(y, yOff + i);
        }
        if (i < N) {
            scalar.combFilterConst(y, yOff + i, x, xOff + i, T, N - i, g10, g11, g12);
        }
    }

    @Override
    public void fftSoa(KissFftState st, float[] a, int re, int im) {
        var factors = st.factors;
        var fstride = st.fstride;
        var L = st.stages;
        int m = factors[2 * L - 1];
        for (var i = L - 1; i >= 0; i--) {
            var m2 = i != 0 ? factors[2 * i - 1] : 1;
            switch (factors[2 * i]) {
                case 2 -> FftSoa.bfly2(a, re, im, fstride[i]);
                case 4 -> {
                    if (m == 1) {
                        FftSoa.bfly4m1(a, re, im, fstride[i]);
                    } else {
                        bfly4(a, re, im, st.stageTwiddles[i], m, fstride[i], m2);
                    }
                }
                case 3 -> bfly3(a, re, im, st.stageTwiddles[i], st.stageConstants[i], m, fstride[i], m2);
                case 5 -> bfly5(a, re, im, st.stageTwiddles[i], st.stageConstants[i], m, fstride[i], m2);
                default -> {
                }
            }
            m = m2;
        }
    }

    /**
     * Runs a radix-4 stage with {@code m > 1}, vectorized across the butterflies of each group.
     *
     * @param a  the data
     * @param re the index of the first real part
     * @param im the index of the first imaginary part
     * @param tw the stage twiddles
     * @param m  the length of each sub-transform
     * @param N  the number of butterfly groups
     * @param mm the distance between groups
     */
    private static void bfly4(float[] a, int re, int im, float[] tw, int m, int N, int mm) {
        var mVec = m - m % LANES;
        for (var i = 0; i < N; i++) {
            var r = re + i * mm;
            var q = im + i * mm;
            for (var j = 0; j < mVec; j += LANES) {
                var a1r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + j + m));
                var a1i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + j + m));
                var a2r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + j + 2 * m));
                var a2i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + j + 2 * m));
                var a3r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + j + 3 * m));
                var a3i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + j + 3 * m));
                var b1r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, j));
                var b1i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, m + j));
                var b2r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, 2 * m + j));
                var b2i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, 3 * m + j));
                var b3r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, 4 * m + j));
                var b3i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, 5 * m + j));
                var s0r = a1r.lanewise(VectorOperators.MUL, b1r).lanewise(VectorOperators.SUB, a1i.lanewise(VectorOperators.MUL, b1i));
                var s0i = a1r.lanewise(VectorOperators.MUL, b1i).lanewise(VectorOperators.ADD, a1i.lanewise(VectorOperators.MUL, b1r));
                var s1r = a2r.lanewise(VectorOperators.MUL, b2r).lanewise(VectorOperators.SUB, a2i.lanewise(VectorOperators.MUL, b2i));
                var s1i = a2r.lanewise(VectorOperators.MUL, b2i).lanewise(VectorOperators.ADD, a2i.lanewise(VectorOperators.MUL, b2r));
                var s2r = a3r.lanewise(VectorOperators.MUL, b3r).lanewise(VectorOperators.SUB, a3i.lanewise(VectorOperators.MUL, b3i));
                var s2i = a3r.lanewise(VectorOperators.MUL, b3i).lanewise(VectorOperators.ADD, a3i.lanewise(VectorOperators.MUL, b3r));
                var f0r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + j));
                var f0i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + j));
                var s5r = f0r.lanewise(VectorOperators.SUB, s1r);
                var s5i = f0i.lanewise(VectorOperators.SUB, s1i);
                f0r = f0r.lanewise(VectorOperators.ADD, s1r);
                f0i = f0i.lanewise(VectorOperators.ADD, s1i);
                var s3r = s0r.lanewise(VectorOperators.ADD, s2r);
                var s3i = s0i.lanewise(VectorOperators.ADD, s2i);
                var s4r = s0r.lanewise(VectorOperators.SUB, s2r);
                var s4i = s0i.lanewise(VectorOperators.SUB, s2i);
                f0r.lanewise(VectorOperators.SUB, s3r).intoArray(a, r + j + 2 * m);
                f0i.lanewise(VectorOperators.SUB, s3i).intoArray(a, q + j + 2 * m);
                f0r.lanewise(VectorOperators.ADD, s3r).intoArray(a, r + j);
                f0i.lanewise(VectorOperators.ADD, s3i).intoArray(a, q + j);
                s5r.lanewise(VectorOperators.ADD, s4i).intoArray(a, r + j + m);
                s5i.lanewise(VectorOperators.SUB, s4r).intoArray(a, q + j + m);
                s5r.lanewise(VectorOperators.SUB, s4i).intoArray(a, r + j + 3 * m);
                s5i.lanewise(VectorOperators.ADD, s4r).intoArray(a, q + j + 3 * m);
            }
        }
        if (mVec < m) {
            FftSoa.bfly4(a, re, im, tw, m, N, mm, mVec);
        }
    }

    /**
     * Runs a radix-3 stage, vectorized across the butterflies of each group.
     *
     * @param a         the data
     * @param re        the index of the first real part
     * @param im        the index of the first imaginary part
     * @param tw        the stage twiddles
     * @param constants the stage constants
     * @param m         the length of each sub-transform
     * @param N         the number of butterfly groups
     * @param mm        the distance between groups
     */
    private static void bfly3(float[] a, int re, int im, float[] tw, float[] constants, int m, int N, int mm) {
        var mVec = m - m % LANES;
        var epi3i = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, constants[0]));
        var half = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, .5f));
        for (var i = 0; i < N; i++) {
            var r = re + i * mm;
            var q = im + i * mm;
            for (var j = 0; j < mVec; j += LANES) {
                var a1r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + j + m));
                var a1i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + j + m));
                var a2r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + j + 2 * m));
                var a2i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + j + 2 * m));
                var b1r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, j));
                var b1i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, m + j));
                var b2r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, 2 * m + j));
                var b2i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, 3 * m + j));
                var s1r = a1r.lanewise(VectorOperators.MUL, b1r).lanewise(VectorOperators.SUB, a1i.lanewise(VectorOperators.MUL, b1i));
                var s1i = a1r.lanewise(VectorOperators.MUL, b1i).lanewise(VectorOperators.ADD, a1i.lanewise(VectorOperators.MUL, b1r));
                var s2r = a2r.lanewise(VectorOperators.MUL, b2r).lanewise(VectorOperators.SUB, a2i.lanewise(VectorOperators.MUL, b2i));
                var s2i = a2r.lanewise(VectorOperators.MUL, b2i).lanewise(VectorOperators.ADD, a2i.lanewise(VectorOperators.MUL, b2r));
                var s3r = s1r.lanewise(VectorOperators.ADD, s2r);
                var s3i = s1i.lanewise(VectorOperators.ADD, s2i);
                var s0r = s1r.lanewise(VectorOperators.SUB, s2r).lanewise(VectorOperators.MUL, epi3i);
                var s0i = s1i.lanewise(VectorOperators.SUB, s2i).lanewise(VectorOperators.MUL, epi3i);
                var f0r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + j));
                var f0i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + j));
                var fmr = f0r.lanewise(VectorOperators.SUB, s3r.lanewise(VectorOperators.MUL, half));
                var fmi = f0i.lanewise(VectorOperators.SUB, s3i.lanewise(VectorOperators.MUL, half));
                f0r.lanewise(VectorOperators.ADD, s3r).intoArray(a, r + j);
                f0i.lanewise(VectorOperators.ADD, s3i).intoArray(a, q + j);
                fmr.lanewise(VectorOperators.ADD, s0i).intoArray(a, r + j + 2 * m);
                fmi.lanewise(VectorOperators.SUB, s0r).intoArray(a, q + j + 2 * m);
                fmr.lanewise(VectorOperators.SUB, s0i).intoArray(a, r + j + m);
                fmi.lanewise(VectorOperators.ADD, s0r).intoArray(a, q + j + m);
            }
        }
        if (mVec < m) {
            FftSoa.bfly3(a, re, im, tw, constants, m, N, mm, mVec);
        }
    }

    /**
     * Runs a radix-5 stage, vectorized across the butterflies of each group.
     *
     * <p>The stage runs in three passes over the whole vectors of each group. {@link #twiddle5}
     * multiplies the four rotated inputs of every butterfly by their twiddles and replaces them with
     * the sums and differences of their outer and inner pairs, storing the real parts of the
     * differences in the imaginary half of the data and their imaginary parts in the real half, so
     * that the outputs {@link #combine5Real} computes depend only on the real half and those
     * {@link #combine5Imag} computes only on the imaginary half. Each intermediate value is rounded
     * to a float in either form, and every output is computed with the same operations in the same
     * order, so the result equals the single pass of {@link FftSoa#bfly5} bit for bit.
     *
     * @implNote This implementation splits the butterfly in three methods: a fused loop body exceeds
     * the size the JIT compiles without leaving Vector API operations on their allocating fallback.
     *
     * @param a         the data
     * @param re        the index of the first real part
     * @param im        the index of the first imaginary part
     * @param tw        the stage twiddles
     * @param constants the stage constants
     * @param m         the length of each sub-transform
     * @param N         the number of butterfly groups
     * @param mm        the distance between groups
     */
    private static void bfly5(float[] a, int re, int im, float[] tw, float[] constants, int m, int N, int mm) {
        var mVec = m - m % LANES;
        twiddle5(a, re, im, tw, m, N, mm, mVec);
        combine5Real(a, re, constants, m, N, mm, mVec);
        combine5Imag(a, im, constants, m, N, mm, mVec);
        if (mVec < m) {
            FftSoa.bfly5(a, re, im, tw, constants, m, N, mm, mVec);
        }
    }

    /**
     * Replaces the four rotated inputs of every radix-5 butterfly over the whole vectors of each
     * group with the sums and differences of their products by the stage twiddles.
     *
     * <p>With {@code s1} to {@code s4} the twiddled inputs, the slots of the four inputs receive, in
     * order, {@code s7 = s1 + s4}, {@code s8 = s2 + s3}, {@code s9 = s2 - s3} and
     * {@code s10 = s1 - s4}; {@code s7} and {@code s8} keep their real and imaginary parts in place,
     * while {@code s9} and {@code s10} store their imaginary part in the real half and their real
     * part in the imaginary half.
     *
     * @param a    the data
     * @param re   the index of the first real part
     * @param im   the index of the first imaginary part
     * @param tw   the stage twiddles
     * @param m    the length of each sub-transform
     * @param N    the number of butterfly groups
     * @param mm   the distance between groups
     * @param mVec the number of butterflies of each group that whole vectors cover
     */
    private static void twiddle5(float[] a, int re, int im, float[] tw, int m, int N, int mm, int mVec) {
        for (var i = 0; i < N; i++) {
            var r = re + i * mm;
            var q = im + i * mm;
            for (var u = 0; u < mVec; u += LANES) {
                var x1r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + u + m));
                var x1i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + u + m));
                var x2r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + u + 2 * m));
                var x2i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + u + 2 * m));
                var x3r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + u + 3 * m));
                var x3i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + u + 3 * m));
                var x4r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + u + 4 * m));
                var x4i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + u + 4 * m));
                var t1r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, u));
                var t1i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, m + u));
                var t2r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, 2 * m + u));
                var t2i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, 3 * m + u));
                var t3r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, 4 * m + u));
                var t3i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, 5 * m + u));
                var t4r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, 6 * m + u));
                var t4i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, tw, 7 * m + u));
                var s1r = x1r.lanewise(VectorOperators.MUL, t1r).lanewise(VectorOperators.SUB, x1i.lanewise(VectorOperators.MUL, t1i));
                var s1i = x1r.lanewise(VectorOperators.MUL, t1i).lanewise(VectorOperators.ADD, x1i.lanewise(VectorOperators.MUL, t1r));
                var s2r = x2r.lanewise(VectorOperators.MUL, t2r).lanewise(VectorOperators.SUB, x2i.lanewise(VectorOperators.MUL, t2i));
                var s2i = x2r.lanewise(VectorOperators.MUL, t2i).lanewise(VectorOperators.ADD, x2i.lanewise(VectorOperators.MUL, t2r));
                var s3r = x3r.lanewise(VectorOperators.MUL, t3r).lanewise(VectorOperators.SUB, x3i.lanewise(VectorOperators.MUL, t3i));
                var s3i = x3r.lanewise(VectorOperators.MUL, t3i).lanewise(VectorOperators.ADD, x3i.lanewise(VectorOperators.MUL, t3r));
                var s4r = x4r.lanewise(VectorOperators.MUL, t4r).lanewise(VectorOperators.SUB, x4i.lanewise(VectorOperators.MUL, t4i));
                var s4i = x4r.lanewise(VectorOperators.MUL, t4i).lanewise(VectorOperators.ADD, x4i.lanewise(VectorOperators.MUL, t4r));
                s1r.lanewise(VectorOperators.ADD, s4r).intoArray(a, r + u + m);
                s1i.lanewise(VectorOperators.ADD, s4i).intoArray(a, q + u + m);
                s2r.lanewise(VectorOperators.ADD, s3r).intoArray(a, r + u + 2 * m);
                s2i.lanewise(VectorOperators.ADD, s3i).intoArray(a, q + u + 2 * m);
                s2i.lanewise(VectorOperators.SUB, s3i).intoArray(a, r + u + 3 * m);
                s2r.lanewise(VectorOperators.SUB, s3r).intoArray(a, q + u + 3 * m);
                s1i.lanewise(VectorOperators.SUB, s4i).intoArray(a, r + u + 4 * m);
                s1r.lanewise(VectorOperators.SUB, s4r).intoArray(a, q + u + 4 * m);
            }
        }
    }

    /**
     * Computes the real parts of the five outputs of every radix-5 butterfly over the whole vectors
     * of each group from the unrotated input and the sums and differences {@link #twiddle5} left in
     * the real half of the data.
     *
     * @param a         the data
     * @param re        the index of the first real part
     * @param constants the stage constants
     * @param m         the length of each sub-transform
     * @param N         the number of butterfly groups
     * @param mm        the distance between groups
     * @param mVec      the number of butterflies of each group that whole vectors cover
     */
    private static void combine5Real(float[] a, int re, float[] constants, int m, int N, int mm, int mVec) {
        var yar = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, constants[0]));
        var yai = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, constants[1]));
        var ybr = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, constants[2]));
        var ybi = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, constants[3]));
        for (var i = 0; i < N; i++) {
            var r = re + i * mm;
            for (var u = 0; u < mVec; u += LANES) {
                var s0r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + u));
                var s7r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + u + m));
                var s8r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + u + 2 * m));
                var s9i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + u + 3 * m));
                var s10i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, r + u + 4 * m));
                s0r.lanewise(VectorOperators.ADD, s7r.lanewise(VectorOperators.ADD, s8r)).intoArray(a, r + u);
                var s5r = s0r.lanewise(VectorOperators.ADD, s7r.lanewise(VectorOperators.MUL, yar).lanewise(VectorOperators.ADD, s8r.lanewise(VectorOperators.MUL, ybr)));
                var s6r = s10i.lanewise(VectorOperators.MUL, yai).lanewise(VectorOperators.ADD, s9i.lanewise(VectorOperators.MUL, ybi));
                s5r.lanewise(VectorOperators.SUB, s6r).intoArray(a, r + u + m);
                s5r.lanewise(VectorOperators.ADD, s6r).intoArray(a, r + u + 4 * m);
                var s11r = s0r.lanewise(VectorOperators.ADD, s7r.lanewise(VectorOperators.MUL, ybr).lanewise(VectorOperators.ADD, s8r.lanewise(VectorOperators.MUL, yar)));
                var s12r = s9i.lanewise(VectorOperators.MUL, yai).lanewise(VectorOperators.SUB, s10i.lanewise(VectorOperators.MUL, ybi));
                s11r.lanewise(VectorOperators.ADD, s12r).intoArray(a, r + u + 2 * m);
                s11r.lanewise(VectorOperators.SUB, s12r).intoArray(a, r + u + 3 * m);
            }
        }
    }

    /**
     * Computes the imaginary parts of the five outputs of every radix-5 butterfly over the whole
     * vectors of each group from the unrotated input and the sums and differences
     * {@link #twiddle5} left in the imaginary half of the data.
     *
     * @param a         the data
     * @param im        the index of the first imaginary part
     * @param constants the stage constants
     * @param m         the length of each sub-transform
     * @param N         the number of butterfly groups
     * @param mm        the distance between groups
     * @param mVec      the number of butterflies of each group that whole vectors cover
     */
    private static void combine5Imag(float[] a, int im, float[] constants, int m, int N, int mm, int mVec) {
        var yar = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, constants[0]));
        var yai = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, constants[1]));
        var ybr = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, constants[2]));
        var ybi = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, constants[3]));
        for (var i = 0; i < N; i++) {
            var q = im + i * mm;
            for (var u = 0; u < mVec; u += LANES) {
                var s0i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + u));
                var s7i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + u + m));
                var s8i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + u + 2 * m));
                var s9r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + u + 3 * m));
                var s10r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, q + u + 4 * m));
                s0i.lanewise(VectorOperators.ADD, s7i.lanewise(VectorOperators.ADD, s8i)).intoArray(a, q + u);
                var s5i = s0i.lanewise(VectorOperators.ADD, s7i.lanewise(VectorOperators.MUL, yar).lanewise(VectorOperators.ADD, s8i.lanewise(VectorOperators.MUL, ybr)));
                var s6i = s10r.lanewise(VectorOperators.MUL, yai).lanewise(VectorOperators.ADD, s9r.lanewise(VectorOperators.MUL, ybi)).lanewise(VectorOperators.NEG);
                s5i.lanewise(VectorOperators.SUB, s6i).intoArray(a, q + u + m);
                s5i.lanewise(VectorOperators.ADD, s6i).intoArray(a, q + u + 4 * m);
                var s11i = s0i.lanewise(VectorOperators.ADD, s7i.lanewise(VectorOperators.MUL, ybr).lanewise(VectorOperators.ADD, s8i.lanewise(VectorOperators.MUL, yar)));
                var s12i = s10r.lanewise(VectorOperators.MUL, ybi).lanewise(VectorOperators.SUB, s9r.lanewise(VectorOperators.MUL, yai));
                s11i.lanewise(VectorOperators.ADD, s12i).intoArray(a, q + u + 2 * m);
                s11i.lanewise(VectorOperators.SUB, s12i).intoArray(a, q + u + 3 * m);
            }
        }
    }

    @Override
    public int pvqSearchBest(float[] X, int xOff, float[] y, int N, float xy, float yy) {
        if (N < 2 * LANES) {
            return scalar.pvqSearchBest(X, xOff, y, N, xy, yy);
        }
        var nVec = N - N % LANES;
        var xyV = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, xy));
        var yyV = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, yy));
        // Rough pass: the largest num/den ratio names the likely winner.
        var maxKey = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, Float.NEGATIVE_INFINITY));
        for (var j = 0; j < nVec; j += LANES) {
            var rxy = FLOAT_VECTOR.cast(xyV).lanewise(VectorOperators.ADD, FloatVector.fromArray(SPECIES, X, xOff + j));
            var num = rxy.lanewise(VectorOperators.MUL, rxy);
            var den = FLOAT_VECTOR.cast(yyV).lanewise(VectorOperators.ADD, FloatVector.fromArray(SPECIES, y, j));
            maxKey = FLOAT_VECTOR.cast(maxKey).lanewise(VectorOperators.MAX, num.lanewise(VectorOperators.DIV, den));
        }
        var best = FLOAT_VECTOR.cast(maxKey).reduceLanes(VectorOperators.MAX);
        for (var j = nVec; j < N; j++) {
            var rxy = xy + X[xOff + j];
            var key = (rxy * rxy) / (yy + y[j]);
            if (key > best) {
                best = key;
            }
        }
        var m = -1;
        var bestV = FloatVector.broadcast(SPECIES, best);
        for (var j = 0; j < nVec; j += LANES) {
            var rxy = FLOAT_VECTOR.cast(xyV).lanewise(VectorOperators.ADD, FloatVector.fromArray(SPECIES, X, xOff + j));
            var num = rxy.lanewise(VectorOperators.MUL, rxy);
            var den = FLOAT_VECTOR.cast(yyV).lanewise(VectorOperators.ADD, FloatVector.fromArray(SPECIES, y, j));
            var hit = num.lanewise(VectorOperators.DIV, den).compare(VectorOperators.EQ, bestV);
            if (hit.anyTrue()) {
                m = j + hit.firstTrue();
                break;
            }
        }
        if (m < 0) {
            for (var j = nVec; j < N; j++) {
                var rxy = xy + X[xOff + j];
                if ((rxy * rxy) / (yy + y[j]) == best) {
                    m = j;
                    break;
                }
            }
        }
        if (m < 0) {
            return scalar.pvqSearchBest(X, xOff, y, N, xy, yy);
        }
        // Exact check with the reference comparison: m must beat every earlier candidate and no
        // later candidate may beat m; then a sequential scan ends at m as well.
        var rm = xy + X[xOff + m];
        var numM = rm * rm;
        var denM = yy + y[m];
        var numMV = FloatVector.broadcast(SPECIES, numM);
        var denMV = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, denM));
        var mV = IntVector.broadcast(INT_SPECIES, m);
        for (var j = 0; j < nVec; j += LANES) {
            var rxy = FLOAT_VECTOR.cast(xyV).lanewise(VectorOperators.ADD, FloatVector.fromArray(SPECIES, X, xOff + j));
            var num = rxy.lanewise(VectorOperators.MUL, rxy);
            var den = FLOAT_VECTOR.cast(yyV).lanewise(VectorOperators.ADD, FloatVector.fromArray(SPECIES, y, j));
            var a = den.lanewise(VectorOperators.MUL, numMV);
            var b = FLOAT_VECTOR.cast(denMV).lanewise(VectorOperators.MUL, num);
            var index = IOTA.lanewise(VectorOperators.ADD, IntVector.broadcast(INT_SPECIES, j));
            var earlier = index.compare(VectorOperators.LT, mV);
            var later = index.compare(VectorOperators.GT, mV);
            var fail = INT_MASK.cast(a.compare(VectorOperators.LE, b).cast(INT_SPECIES)).and(earlier)
                    .or(INT_MASK.cast(a.compare(VectorOperators.LT, b).cast(INT_SPECIES)).and(later));
            if (fail.anyTrue()) {
                return scalar.pvqSearchBest(X, xOff, y, N, xy, yy);
            }
        }
        for (var j = nVec; j < N; j++) {
            if (j == m) {
                continue;
            }
            var rxy = xy + X[xOff + j];
            var num = rxy * rxy;
            var den = yy + y[j];
            var a = den * numM;
            var b = denM * num;
            if (j < m ? !(a > b) : b > a) {
                return scalar.pvqSearchBest(X, xOff, y, N, xy, yy);
            }
        }
        return m;
    }

    @Override
    public float maxabs(float[] x, int off, int len) {
        var i = 0;
        var vmax = FLOAT_VECTOR.cast(FloatVector.zero(SPECIES));
        var vmin = FLOAT_VECTOR.cast(FloatVector.zero(SPECIES));
        var nan = FLOAT_MASK.cast(SPECIES.maskAll(false));
        for (; i <= len - LANES; i += LANES) {
            var v = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, x, off + i));
            nan = FLOAT_MASK.cast(nan).or(v.test(VectorOperators.IS_NAN));
            vmax = FLOAT_VECTOR.cast(vmax).lanewise(VectorOperators.MAX, v);
            vmin = FLOAT_VECTOR.cast(vmin).lanewise(VectorOperators.MIN, v);
        }
        var hasNan = FLOAT_MASK.cast(nan).anyTrue();
        for (var j = i; j < len; j++) {
            hasNan |= Float.isNaN(x[off + j]);
        }
        // The reference's ternaries let a NaN reset the running extremes: only a sequential scan
        // reproduces that.
        if (hasNan) {
            return scalar.maxabs(x, off, len);
        }
        var maxval = FLOAT_VECTOR.cast(vmax).reduceLanes(VectorOperators.MAX);
        var minval = FLOAT_VECTOR.cast(vmin).reduceLanes(VectorOperators.MIN);
        for (; i < len; i++) {
            maxval = MathOps.max(maxval, x[off + i]);
            minval = MathOps.min(minval, x[off + i]);
        }
        return MathOps.max(maxval, -minval);
    }

    @Override
    public void expRotation1(float[] X, int xOff, int len, int stride, float c, float s) {
        // A block of LANES rotations only depends on rotations at least stride away.
        if (stride < LANES) {
            scalar.expRotation1(X, xOff, len, stride, c, s);
            return;
        }
        var ms = -s;
        var cV = FloatVector.broadcast(SPECIES, c);
        var sV = FloatVector.broadcast(SPECIES, s);
        var msV = FloatVector.broadcast(SPECIES, ms);
        var i = 0;
        var n = len - stride;
        for (; i <= n - LANES; i += LANES) {
            var x1 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, X, xOff + i));
            var x2 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, X, xOff + i + stride));
            x2.lanewise(VectorOperators.MUL, cV).lanewise(VectorOperators.ADD, x1.lanewise(VectorOperators.MUL, sV))
                    .intoArray(X, xOff + i + stride);
            x1.lanewise(VectorOperators.MUL, cV).lanewise(VectorOperators.ADD, x2.lanewise(VectorOperators.MUL, msV))
                    .intoArray(X, xOff + i);
        }
        for (; i < n; i++) {
            var x1 = X[xOff + i];
            var x2 = X[xOff + i + stride];
            X[xOff + i + stride] = c * x2 + s * x1;
            X[xOff + i] = c * x1 + ms * x2;
        }
        i = len - 2 * stride - 1;
        for (; i >= LANES - 1; i -= LANES) {
            var lo = xOff + i - LANES + 1;
            var x1 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, X, lo));
            var x2 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, X, lo + stride));
            x2.lanewise(VectorOperators.MUL, cV).lanewise(VectorOperators.ADD, x1.lanewise(VectorOperators.MUL, sV))
                    .intoArray(X, lo + stride);
            x1.lanewise(VectorOperators.MUL, cV).lanewise(VectorOperators.ADD, x2.lanewise(VectorOperators.MUL, msV))
                    .intoArray(X, lo);
        }
        for (; i >= 0; i--) {
            var x1 = X[xOff + i];
            var x2 = X[xOff + i + stride];
            X[xOff + i + stride] = c * x2 + s * x1;
            X[xOff + i] = c * x1 + ms * x2;
        }
    }

    @Override
    public void haar1(float[] X, int xOff, int N0, int stride) {
        // The blocks of one pair are contiguous across i, so a vector covers LANES blocks.
        if (stride < LANES) {
            scalar.haar1(X, xOff, N0, stride);
            return;
        }
        N0 >>= 1;
        var iVec = stride - stride % LANES;
        var scale = FloatVector.broadcast(SPECIES, .70710678f);
        for (var j = 0; j < N0; j++) {
            var a = xOff + stride * 2 * j;
            var b = xOff + stride * (2 * j + 1);
            for (var i = 0; i < iVec; i += LANES) {
                var tmp1 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, X, a + i)).lanewise(VectorOperators.MUL, scale);
                var tmp2 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, X, b + i)).lanewise(VectorOperators.MUL, scale);
                tmp1.lanewise(VectorOperators.ADD, tmp2).intoArray(X, a + i);
                tmp1.lanewise(VectorOperators.SUB, tmp2).intoArray(X, b + i);
            }
            for (var i = iVec; i < stride; i++) {
                var tmp1 = .70710678f * X[a + i];
                var tmp2 = .70710678f * X[b + i];
                X[a + i] = tmp1 + tmp2;
                X[b + i] = tmp1 - tmp2;
            }
        }
    }
}
