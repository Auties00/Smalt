package com.github.auties00.smalt.audio.aac.internal;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link AacKernel} with the Vector API.
 *
 * <p>Every loop processes whole vectors and finishes any remainder with the scalar reference
 * code. Element-wise kernels evaluate the same float operations in the same order as
 * {@link ScalarAacKernel}, so their results match it exactly. The reductions ({@link #distortion},
 * {@link #energy}, {@link #dot}) accumulate one partial sum per lane and add the lanes in lane
 * order, so their results are identical on every run of a given lane count, and agree with the
 * scalar reference to within float rounding.
 *
 * @implNote This implementation uses 256-bit vectors when the platform prefers vectors of at
 * least that width and 128-bit vectors otherwise, which keeps every FFT pass with a quarter
 * block of eight points vectorized on AVX2 and AVX-512 machines alike. It calls only the per-shape
 * {@code lanewise}, {@code compare}, {@code blend} and {@code rearrange} operations, with vector
 * operands broadcast once per call, casts every loaded vector through the constant exact class of
 * the species, so that C2 can intrinsify each operation while it parses the kernel instead of in a
 * later inlining pass (which, without tiered compilation and with every audio kernel in one JVM,
 * left the last operations of the transforms on their allocating fallback), and keeps no vector
 * across a control-flow merge or a loop back edge except accumulators that start from zero;
 * written with the convenience methods and
 * scalar operands, the C2 compiler bound some operations too late to intrinsify them, and they ran
 * on their allocating Java fallback; the Vector API gather does so inside the JDK, so table lookups
 * are scalar (the gathered reconstruction in {@link #distortion} allocated about 7 MB per second of
 * encoded audio). The lane sums are added one lane at a time because the intrinsified
 * {@code reduceLanes(ADD)} adds float lanes in a tree while its fallback adds them in
 * order, which would make results depend on the compilation state.
 */
final class VectorAacKernel implements AacKernel {
    /**
     * The float vector shape.
     */
    static final VectorSpecies<Float> FLOATS = FloatVector.SPECIES_PREFERRED.vectorBitSize() >= 256
            ? FloatVector.SPECIES_256 : FloatVector.SPECIES_128;

    /**
     * The int vector shape with as many lanes as {@link #FLOATS}.
     */
    static final VectorSpecies<Integer> INTS = FLOATS.vectorBitSize() == 256 ? IntVector.SPECIES_256 : IntVector.SPECIES_128;

    /**
     * The number of lanes.
     */
    static final int LANES = FLOATS.length();

    /**
     * The exact class of the vectors of {@link #FLOATS}.
     */
    @SuppressWarnings("unchecked")
    private static final Class<FloatVector> FLOAT_VECTOR = (Class<FloatVector>) FLOATS.vectorType();

    /**
     * The exact class of the vectors of {@link #INTS}.
     */
    @SuppressWarnings("unchecked")
    private static final Class<IntVector> INT_VECTOR = (Class<IntVector>) INTS.vectorType();

    /**
     * The 128-bit float shape, which finishes runs whose length is a multiple of four but not of
     * {@link #LANES}.
     */
    static final VectorSpecies<Float> QUARTER_FLOATS = FloatVector.SPECIES_128;

    /**
     * The shuffle that reverses the lanes of a vector.
     */
    static final VectorShuffle<Float> REVERSE = VectorShuffle.fromOp(FLOATS, lane -> LANES - 1 - lane);

    /**
     * The two-vector shuffle that gathers the even lanes of two consecutive vectors.
     */
    static final VectorShuffle<Float> EVENS = VectorShuffle.fromOp(FLOATS,
            lane -> 2 * lane < LANES ? 2 * lane : 2 * lane - 2 * LANES);

    /**
     * The two-vector shuffle that gathers, from the last lane downwards, the odd-offset lanes of
     * a vector and then of the vector before it.
     */
    static final VectorShuffle<Float> ODDS_REVERSED = VectorShuffle.fromOp(FLOATS,
            lane -> lane < LANES / 2 ? LANES - 1 - 2 * lane : LANES - 1 - 2 * (lane - LANES / 2) - LANES);

    /**
     * The scalar reference used for loop remainders.
     */
    private final ScalarAacKernel scalar = new ScalarAacKernel();

    /**
     * Constructs the kernel.
     */
    VectorAacKernel() {
    }

    @Override
    public void fftRadix4(float[] re, float[] im, int n, int quarter, float[] twiddles, int offset) {
        if (quarter % LANES != 0) {
            scalar.fftRadix4(re, im, n, quarter, twiddles, offset);
            return;
        }
        for (var block = 0; block < n; block += 4 * quarter) {
            for (var j = 0; j < quarter; j += LANES) {
                radix4(re, im, block + j, quarter, twiddles, offset + j);
            }
        }
    }

    /**
     * Computes one vector of radix-4 butterflies: the points {@code i0}, {@code i0 + quarter},
     * {@code i0 + 2 * quarter} and {@code i0 + 3 * quarter} of {@link #LANES} consecutive columns.
     *
     * @param re the real parts, transformed in place
     * @param im the imaginary parts, transformed in place
     * @param i0 the index of the first point of the first column
     * @param quarter the distance between the points of a butterfly
     * @param twiddles the twiddle table
     * @param w the index of the first column's first twiddle in {@code twiddles}
     */
    private static void radix4(float[] re, float[] im, int i0, int quarter, float[] twiddles, int w) {
        var i1 = i0 + quarter;
        var i2 = i1 + quarter;
        var i3 = i2 + quarter;
        var x0r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, i0));
        var x0i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, i0));
        var x1r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, i1));
        var x1i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, i1));
        var x2r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, i2));
        var x2i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, i2));
        var x3r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, i3));
        var x3i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, i3));
        var ar = x0r.lanewise(VectorOperators.ADD, x2r);
        var ai = x0i.lanewise(VectorOperators.ADD, x2i);
        var cr = x0r.lanewise(VectorOperators.SUB, x2r);
        var ci = x0i.lanewise(VectorOperators.SUB, x2i);
        var br = x1r.lanewise(VectorOperators.ADD, x3r);
        var bi = x1i.lanewise(VectorOperators.ADD, x3i);
        var dr = x1i.lanewise(VectorOperators.SUB, x3i);
        var di = x3r.lanewise(VectorOperators.SUB, x1r);
        ar.lanewise(VectorOperators.ADD, br).intoArray(re, i0);
        ai.lanewise(VectorOperators.ADD, bi).intoArray(im, i0);
        var tr = ar.lanewise(VectorOperators.SUB, br);
        var ti = ai.lanewise(VectorOperators.SUB, bi);
        var wr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, twiddles, w + 2 * quarter));
        var wi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, twiddles, w + 3 * quarter));
        tr.lanewise(VectorOperators.MUL, wr).lanewise(VectorOperators.SUB, ti.lanewise(VectorOperators.MUL, wi)).intoArray(re, i1);
        tr.lanewise(VectorOperators.MUL, wi).lanewise(VectorOperators.ADD, ti.lanewise(VectorOperators.MUL, wr)).intoArray(im, i1);
        tr = cr.lanewise(VectorOperators.ADD, dr);
        ti = ci.lanewise(VectorOperators.ADD, di);
        wr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, twiddles, w));
        wi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, twiddles, w + quarter));
        tr.lanewise(VectorOperators.MUL, wr).lanewise(VectorOperators.SUB, ti.lanewise(VectorOperators.MUL, wi)).intoArray(re, i2);
        tr.lanewise(VectorOperators.MUL, wi).lanewise(VectorOperators.ADD, ti.lanewise(VectorOperators.MUL, wr)).intoArray(im, i2);
        tr = cr.lanewise(VectorOperators.SUB, dr);
        ti = ci.lanewise(VectorOperators.SUB, di);
        wr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, twiddles, w + 4 * quarter));
        wi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, twiddles, w + 5 * quarter));
        tr.lanewise(VectorOperators.MUL, wr).lanewise(VectorOperators.SUB, ti.lanewise(VectorOperators.MUL, wi)).intoArray(re, i3);
        tr.lanewise(VectorOperators.MUL, wi).lanewise(VectorOperators.ADD, ti.lanewise(VectorOperators.MUL, wr)).intoArray(im, i3);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation processes two vectors per iteration, for the reason given on
     * {@link #preTwiddle}. Stages with fewer than four vectors of butterflies per group run the
     * scalar reference, which computes the same operations in the same order: the only such stage
     * the transforms plan is the eight-wide radix-2 stage of the 64-point FFT of the short windows,
     * a few vector steps per call and called only on frames with transients, which the JIT compiled
     * so late that the vector code ran its allocating fallback for seconds in a busy JVM, for no
     * measurable gain.
     */
    @Override
    public void fftRadix2(float[] re, float[] im, int n, int half, float[] twiddles, int offset) {
        if (half % LANES != 0 || half < 4 * LANES) {
            scalar.fftRadix2(re, im, n, half, twiddles, offset);
            return;
        }
        for (var block = 0; block < n; block += 2 * half) {
            var j = 0;
            for (; j + 2 * LANES <= half; j += 2 * LANES) {
                var i0 = block + j;
                var i1 = i0 + half;
                var ar = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, i0));
                var ai = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, i0));
                var cr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, i1));
                var ci = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, i1));
                ar.lanewise(VectorOperators.ADD, cr).intoArray(re, i0);
                ai.lanewise(VectorOperators.ADD, ci).intoArray(im, i0);
                var tr = ar.lanewise(VectorOperators.SUB, cr);
                var ti = ai.lanewise(VectorOperators.SUB, ci);
                var wr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, twiddles, offset + j));
                var wi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, twiddles, offset + half + j));
                tr.lanewise(VectorOperators.MUL, wr).lanewise(VectorOperators.SUB, ti.lanewise(VectorOperators.MUL, wi))
                        .intoArray(re, i1);
                tr.lanewise(VectorOperators.MUL, wi).lanewise(VectorOperators.ADD, ti.lanewise(VectorOperators.MUL, wr))
                        .intoArray(im, i1);
                var k0 = i0 + LANES;
                var k1 = i1 + LANES;
                var br = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, k0));
                var bi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, k0));
                var dr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, k1));
                var di = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, k1));
                br.lanewise(VectorOperators.ADD, dr).intoArray(re, k0);
                bi.lanewise(VectorOperators.ADD, di).intoArray(im, k0);
                var ur = br.lanewise(VectorOperators.SUB, dr);
                var ui = bi.lanewise(VectorOperators.SUB, di);
                var vr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, twiddles, offset + j + LANES));
                var vi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, twiddles, offset + half + j + LANES));
                ur.lanewise(VectorOperators.MUL, vr).lanewise(VectorOperators.SUB, ui.lanewise(VectorOperators.MUL, vi))
                        .intoArray(re, k1);
                ur.lanewise(VectorOperators.MUL, vi).lanewise(VectorOperators.ADD, ui.lanewise(VectorOperators.MUL, vr))
                        .intoArray(im, k1);
            }
            for (; j < half; j += LANES) {
                var i0 = block + j;
                var i1 = i0 + half;
                var ar = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, i0));
                var ai = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, i0));
                var cr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, i1));
                var ci = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, i1));
                ar.lanewise(VectorOperators.ADD, cr).intoArray(re, i0);
                ai.lanewise(VectorOperators.ADD, ci).intoArray(im, i0);
                var tr = ar.lanewise(VectorOperators.SUB, cr);
                var ti = ai.lanewise(VectorOperators.SUB, ci);
                var wr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, twiddles, offset + j));
                var wi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, twiddles, offset + half + j));
                tr.lanewise(VectorOperators.MUL, wr).lanewise(VectorOperators.SUB, ti.lanewise(VectorOperators.MUL, wi))
                        .intoArray(re, i1);
                tr.lanewise(VectorOperators.MUL, wi).lanewise(VectorOperators.ADD, ti.lanewise(VectorOperators.MUL, wr))
                        .intoArray(im, i1);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation runs the scalar reference: the table lookup dominates the
     * work, and the Vector API gather binds its index arithmetic too late for C2 in this kernel,
     * falling back to allocating Java code.
     */
    @Override
    public void dequantize(int[] quant, float[] out, int from, int to, float gain) {
        scalar.dequantize(quant, out, from, to, gain);
    }

    @Override
    public void overlapLong(float[] u, int m, float[] window, float[] overlap, float[] out, int outOffset) {
        var h = m >> 1;
        var bound = FLOATS.loopBound(h);
        var n = 0;
        for (; n < bound; n += LANES) {
            var y = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, u, h + n));
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, overlap, n))
                    .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, n)).lanewise(VectorOperators.MUL, y))
                    .intoArray(out, outOffset + n);
        }
        for (; n < h; n++) {
            out[outOffset + n] = overlap[n] + window[n] * u[h + n];
        }
        n = h;
        for (; n + LANES <= m; n += LANES) {
            var y = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, u, m + h - LANES - n)).rearrange(REVERSE);
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, overlap, n))
                    .lanewise(VectorOperators.SUB, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, n)).lanewise(VectorOperators.MUL, y))
                    .intoArray(out, outOffset + n);
        }
        for (; n < m; n++) {
            out[outOffset + n] = overlap[n] - window[n] * u[m + h - 1 - n];
        }
        n = 0;
        for (; n < bound; n += LANES) {
            var y = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, u, h - LANES - n)).rearrange(REVERSE);
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, m + n)).lanewise(VectorOperators.MUL, y).lanewise(VectorOperators.NEG)
                    .intoArray(overlap, n);
        }
        for (; n < h; n++) {
            overlap[n] = -(window[m + n] * u[h - 1 - n]);
        }
        n = h;
        for (; n + LANES <= m; n += LANES) {
            var y = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, u, n - h));
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, m + n)).lanewise(VectorOperators.MUL, y).lanewise(VectorOperators.NEG)
                    .intoArray(overlap, n);
        }
        for (; n < m; n++) {
            overlap[n] = -(window[m + n] * u[n - h]);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation runs the scalar reference, whose loop the JIT compiler
     * vectorizes by itself: the explicit Vector API loop gained nothing, and in some JVM runs it
     * was inlined into a caller too large for C2 to intrinsify it, falling back to allocating
     * Java code.
     */
    @Override
    public void powerLaw(float[] x, float[] magnitude, float[] power34, int from, int to) {
        scalar.powerLaw(x, magnitude, power34, from, to);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation runs the scalar reference, whose loop the JIT compiler
     * vectorizes by itself, for the reason given on {@link #powerLaw}.
     */
    @Override
    public int quantize(float[] power34, int[] quant, int from, int to, float inverseStep) {
        return scalar.quantize(power34, quant, from, to, inverseStep);
    }

    @Override
    public float distortion(float[] magnitude, float[] power34, int[] scratch, int from, int to, float inverseStep,
                            float step) {
        var bound = from + FLOATS.loopBound(to - from);
        var i = from;
        var total = 0.0f;
        if (i < bound) {
            total = distortionFull(magnitude, power34, scratch, from, bound, inverseStep, step);
            i = bound;
        }
        if (LANES == 8 && to - i >= 4) {
            total += distortionQuarter(magnitude, power34, i, inverseStep, step);
            i += 4;
        }
        return total + scalar.distortion(magnitude, power34, scratch, i, to, inverseStep, step);
    }

    /**
     * Returns the squared quantization error of the whole vectors of {@code [from, bound)}, one
     * partial sum per lane added in lane order.
     *
     * <p>The lines are first quantized with vectors into {@code scratch}, clamped to the
     * dequantization table; the errors are then accumulated from those magnitudes.
     *
     * @implNote This implementation computes the error lanes with scalar code, one accumulator per
     * lane, which evaluates the same float operations as a vector loop: the table lookup, a gather,
     * has no efficient Vector API form (the JDK gather binds its index arithmetic too late for C2
     * here and falls back to allocating Java code), and routing the looked-up values back into
     * vectors through memory stalls on store forwarding. Keeping both loops in one method also keeps
     * it above the JIT compiler's inlining size limit, so the vector loop is compiled on its own
     * rather than inside the large rate-control routines.
     *
     * @param magnitude the magnitudes
     * @param power34 the magnitudes raised to 3/4
     * @param quant a work array; its elements in {@code [from, bound)} receive the clamped
     * quantized magnitudes
     * @param from the first line
     * @param bound the end of the lines, {@code from} plus a multiple of {@link #LANES}
     * @param inverseStep the inverse quantizer step
     * @param step the quantizer step
     * @return the squared error
     */
    private static float distortionFull(float[] magnitude, float[] power34, int[] quant, int from, int bound,
                                        float inverseStep, float step) {
        var inverse = FloatVector.broadcast(FLOATS, inverseStep);
        var rounding = FloatVector.broadcast(FLOATS, Quantization.ROUNDING);
        var limit = IntVector.broadcast(INTS, Dequantizer.POW43_SIZE - 1);
        for (var i = from; i < bound; i += LANES) {
            INT_VECTOR.cast(FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, power34, i)).lanewise(VectorOperators.MUL, inverse)
                    .lanewise(VectorOperators.ADD, rounding).convertShape(VectorOperators.F2I, INTS, 0))
                    .lanewise(VectorOperators.MIN, limit).intoArray(quant, i);
        }
        var table = Dequantizer.POW43;
        float s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, s5 = 0, s6 = 0, s7 = 0;
        for (var i = from; i < bound; i += LANES) {
            var e0 = magnitude[i] - table[quant[i]] * step;
            var e1 = magnitude[i + 1] - table[quant[i + 1]] * step;
            var e2 = magnitude[i + 2] - table[quant[i + 2]] * step;
            var e3 = magnitude[i + 3] - table[quant[i + 3]] * step;
            s0 += e0 * e0;
            s1 += e1 * e1;
            s2 += e2 * e2;
            s3 += e3 * e3;
            if (LANES == 8) {
                var e4 = magnitude[i + 4] - table[quant[i + 4]] * step;
                var e5 = magnitude[i + 5] - table[quant[i + 5]] * step;
                var e6 = magnitude[i + 6] - table[quant[i + 6]] * step;
                var e7 = magnitude[i + 7] - table[quant[i + 7]] * step;
                s4 += e4 * e4;
                s5 += e5 * e5;
                s6 += e6 * e6;
                s7 += e7 * e7;
            }
        }
        var total = (((0.0f + s0) + s1) + s2) + s3;
        if (LANES == 8) {
            total = (((total + s4) + s5) + s6) + s7;
        }
        return total;
    }

    /**
     * Returns the squared quantization error of the four lines at {@code i}, added in lane order.
     *
     * @param magnitude the magnitudes
     * @param power34 the magnitudes raised to 3/4
     * @param i the first line
     * @param inverseStep the inverse quantizer step
     * @param step the quantizer step
     * @return the squared error
     */
    private static float distortionQuarter(float[] magnitude, float[] power34, int i, float inverseStep, float step) {
        var table = Dequantizer.POW43;
        var limit = Dequantizer.POW43_SIZE - 1;
        var rounding = Quantization.ROUNDING;
        var e0 = magnitude[i] - table[Math.min((int) (power34[i] * inverseStep + rounding), limit)] * step;
        var e1 = magnitude[i + 1] - table[Math.min((int) (power34[i + 1] * inverseStep + rounding), limit)] * step;
        var e2 = magnitude[i + 2] - table[Math.min((int) (power34[i + 2] * inverseStep + rounding), limit)] * step;
        var e3 = magnitude[i + 3] - table[Math.min((int) (power34[i + 3] * inverseStep + rounding), limit)] * step;
        return (((0.0f + e0 * e0) + e1 * e1) + e2 * e2) + e3 * e3;
    }

    @Override
    public float energy(float[] x, int from, int to) {
        var bound = from + FLOATS.loopBound(to - from);
        var total = 0.0f;
        if (from < bound) {
            total = energyFull(x, from, bound);
        }
        return total + scalar.energy(x, bound, to);
    }

    /**
     * Returns the energy of the whole vectors of {@code [from, bound)}, one partial sum per lane
     * added in lane order, from lane zero upwards to an initial zero.
     *
     * @param x the samples
     * @param from the first sample
     * @param bound the end of the samples, {@code from} plus a multiple of {@link #LANES}
     * @return the energy
     */
    private static float energyFull(float[] x, int from, int bound) {
        var sum = FloatVector.zero(FLOATS);
        for (var i = from; i < bound; i += LANES) {
            var value = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, x, i));
            sum = FLOAT_VECTOR.cast(sum).lanewise(VectorOperators.ADD, value.lanewise(VectorOperators.MUL, value));
        }
        var lanes = FLOAT_VECTOR.cast(sum);
        var total = (((0.0f + lanes.lane(0)) + lanes.lane(1)) + lanes.lane(2)) + lanes.lane(3);
        if (LANES == 8) {
            total = (((total + lanes.lane(4)) + lanes.lane(5)) + lanes.lane(6)) + lanes.lane(7);
        }
        return total;
    }

    @Override
    public void fold(float[] x, int offset, float[] window, float[] folded, int m) {
        var h = m >> 1;
        var k = 0;
        for (; k + LANES <= h; k += LANES) {
            var a = 3 * h - LANES - k;
            var b = 3 * h + k;
            var reversed = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, x, offset + a))
                    .lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, a))).rearrange(REVERSE);
            var forward = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, x, offset + b))
                    .lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, b)));
            reversed.lanewise(VectorOperators.NEG).lanewise(VectorOperators.SUB, forward).intoArray(folded, k);
        }
        for (; k < h; k++) {
            var a = 3 * h - 1 - k;
            var b = 3 * h + k;
            folded[k] = -(x[offset + a] * window[a]) - x[offset + b] * window[b];
        }
        for (; k + LANES <= m; k += LANES) {
            var a = k - h;
            var b = 3 * h - LANES - k;
            var forward = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, x, offset + a))
                    .lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, a)));
            var reversed = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, x, offset + b))
                    .lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, b))).rearrange(REVERSE);
            forward.lanewise(VectorOperators.SUB, reversed).intoArray(folded, k);
        }
        for (; k < m; k++) {
            var a = k - h;
            var b = 3 * h - 1 - k;
            folded[k] = x[offset + a] * window[a] - x[offset + b] * window[b];
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation processes two vectors per iteration, which also keeps the
     * method above the JIT compiler's inlining size limit, so it is compiled on its own: inlined
     * into the large transform routines, its vector operations were in some JVM runs left on the
     * allocating fallback.
     */
    @Override
    public void preTwiddle(float[] in, int offset, int size, float[] cos, float[] sin, float[] re, float[] im) {
        var half = size >> 1;
        var last = offset + size - 1;
        var k = 0;
        for (; k + 2 * LANES <= half; k += 2 * LANES) {
            var low = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in, offset + 2 * k));
            var high = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in, offset + 2 * k + LANES));
            var a = low.rearrange(EVENS, high);
            var tail = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in, last - 2 * k - LANES + 1));
            var head = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in, last - 2 * k - 2 * LANES + 1));
            var b = tail.rearrange(ODDS_REVERSED, head);
            var c = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, cos, k));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, sin, k));
            a.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, s))
                    .intoArray(re, k);
            b.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.SUB, a.lanewise(VectorOperators.MUL, s))
                    .intoArray(im, k);
            var k2 = k + LANES;
            var low2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in, offset + 2 * k2));
            var high2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in, offset + 2 * k2 + LANES));
            var a2 = low2.rearrange(EVENS, high2);
            var tail2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in, last - 2 * k2 - LANES + 1));
            var head2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in, last - 2 * k2 - 2 * LANES + 1));
            var b2 = tail2.rearrange(ODDS_REVERSED, head2);
            var c2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, cos, k2));
            var s2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, sin, k2));
            a2.lanewise(VectorOperators.MUL, c2).lanewise(VectorOperators.ADD, b2.lanewise(VectorOperators.MUL, s2))
                    .intoArray(re, k2);
            b2.lanewise(VectorOperators.MUL, c2).lanewise(VectorOperators.SUB, a2.lanewise(VectorOperators.MUL, s2))
                    .intoArray(im, k2);
        }
        for (; k + LANES <= half; k += LANES) {
            var low = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in, offset + 2 * k));
            var high = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in, offset + 2 * k + LANES));
            var a = low.rearrange(EVENS, high);
            var tail = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in, last - 2 * k - LANES + 1));
            var head = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in, last - 2 * k - 2 * LANES + 1));
            var b = tail.rearrange(ODDS_REVERSED, head);
            var c = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, cos, k));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, sin, k));
            a.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, s))
                    .intoArray(re, k);
            b.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.SUB, a.lanewise(VectorOperators.MUL, s))
                    .intoArray(im, k);
        }
        for (; k < half; k++) {
            var a = in[offset + 2 * k];
            var b = in[last - 2 * k];
            var c = cos[k];
            var s = sin[k];
            re[k] = a * c + b * s;
            im[k] = b * c - a * s;
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation runs the scalar reference, whose loop the JIT compiler
     * vectorizes by itself, for the reason given on {@link #powerLaw}.
     */
    @Override
    public void midSide(float[] left, float[] right, int from, int to) {
        scalar.midSide(left, right, from, to);
    }

    @Override
    public float dot(float[] a, int aOffset, float[] b, int bOffset, int length) {
        var bound = FLOATS.loopBound(length);
        var total = 0.0f;
        if (bound > 0) {
            total = dotFull(a, aOffset, b, bOffset, bound);
        }
        return total + scalar.dot(a, aOffset + bound, b, bOffset + bound, length - bound);
    }

    /**
     * Returns the dot product of the whole vectors of the first {@code bound} elements, one partial
     * sum per lane added in lane order, from lane zero upwards to an initial zero.
     *
     * @param a the first vector
     * @param aOffset the index of the first element of {@code a}
     * @param b the second vector
     * @param bOffset the index of the first element of {@code b}
     * @param bound the number of elements, a multiple of {@link #LANES}
     * @return the dot product
     */
    private static float dotFull(float[] a, int aOffset, float[] b, int bOffset, int bound) {
        var sum = FloatVector.zero(FLOATS);
        for (var i = 0; i < bound; i += LANES) {
            sum = FLOAT_VECTOR.cast(sum).lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, a, aOffset + i))
                    .lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, b, bOffset + i))));
        }
        var lanes = FLOAT_VECTOR.cast(sum);
        var total = (((0.0f + lanes.lane(0)) + lanes.lane(1)) + lanes.lane(2)) + lanes.lane(3);
        if (LANES == 8) {
            total = (((total + lanes.lane(4)) + lanes.lane(5)) + lanes.lane(6)) + lanes.lane(7);
        }
        return total;
    }
}
