package com.github.auties00.smalt.audio.aac.internal.sbr;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link SbrKernel} with the Vector API.
 *
 * <p>Every kernel works on whole vectors of consecutive outputs and finishes any remainder with
 * the scalar reference code; each lane evaluates the same float operations in the same order as
 * {@link ScalarSbrKernel}, so the results match it bit for bit.
 *
 * @implNote This implementation uses 256-bit vectors when the platform prefers vectors of at
 * least that width and 128-bit vectors otherwise; both divide the 64- and 32-sample slots. It
 * follows the rules the module's JIT guard enforces: only per-shape {@code lanewise} calls,
 * every loaded vector cast through the constant exact class of the species, the window sums
 * unrolled so that no vector crosses a loop back edge, and each kernel small enough for the
 * C1 and C2 compilers.
 */
final class VectorSbrKernel implements SbrKernel {
    /**
     * The float vector shape.
     */
    private static final VectorSpecies<Float> FLOATS = FloatVector.SPECIES_PREFERRED.vectorBitSize() >= 256
            ? FloatVector.SPECIES_256 : FloatVector.SPECIES_128;

    /**
     * The number of lanes.
     */
    private static final int LANES = FLOATS.length();

    /**
     * The exact class of the vectors of {@link #FLOATS}.
     */
    @SuppressWarnings("unchecked")
    private static final Class<FloatVector> FLOAT_VECTOR = (Class<FloatVector>) FLOATS.vectorType();

    /**
     * The double vector shape of the covariance sums: as many lanes as a quarter of the float
     * vector's bits hold floats.
     */
    private static final VectorSpecies<Double> DOUBLES = LANES >= 8 ? DoubleVector.SPECIES_256 : DoubleVector.SPECIES_128;

    /**
     * The number of lanes of {@link #DOUBLES}.
     */
    private static final int DOUBLE_LANES = DOUBLES.length();

    /**
     * The exact class of the vectors of {@link #DOUBLES}.
     */
    @SuppressWarnings("unchecked")
    private static final Class<DoubleVector> DOUBLE_VECTOR = (Class<DoubleVector>) DOUBLES.vectorType();

    /**
     * The float vector shape with as many lanes as {@link #DOUBLES}.
     */
    private static final VectorSpecies<Float> NARROW = DOUBLE_LANES == 4 ? FloatVector.SPECIES_128 : FloatVector.SPECIES_64;

    /**
     * The exact class of the vectors of {@link #NARROW}.
     */
    @SuppressWarnings("unchecked")
    private static final Class<FloatVector> NARROW_VECTOR = (Class<FloatVector>) NARROW.vectorType();

    /**
     * The square root of one half, the twiddle of the eight-point butterflies.
     */
    private static final float ROOT_HALF = (float) Math.sqrt(0.5);

    /**
     * Creates the kernel.
     */
    VectorSbrKernel() {
    }

    @Override
    public int width() {
        return LANES;
    }

    @Override
    public void analysisWindow(float[] x, int base, float[] window, float[] out) {
        for (var m = 0; m < 64; m += LANES) {
            var w0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, m));
            var w1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, m + 64));
            var w2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, m + 128));
            var w3 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, m + 192));
            var w4 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, m + 256));
            for (var l = 0; l < LANES; l++) {
                var index = base + 32 * l + m;
                var x0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, x, index));
                var x1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, x, index - 64));
                var x2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, x, index - 128));
                var x3 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, x, index - 192));
                var x4 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, x, index - 256));
                var sum = x0.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(w0));
                sum = sum.lanewise(VectorOperators.ADD, x1.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(w1)));
                sum = sum.lanewise(VectorOperators.ADD, x2.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(w2)));
                sum = sum.lanewise(VectorOperators.ADD, x3.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(w3)));
                sum = sum.lanewise(VectorOperators.ADD, x4.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(w4)));
                FLOAT_VECTOR.cast(sum).intoArray(out, 64 * l + m);
            }
        }
    }

    @Override
    public void foldRows(float[] u, float[] cos, float[] sin, float[] fr, float[] fi) {
        for (var k = 0; k < 16; k++) {
            var x0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, u, (63 - 2 * k) * LANES));
            var x1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, u, (32 + 2 * k) * LANES));
            var x2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, u, (31 - 2 * k) * LANES));
            var x3 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, u, 2 * k * LANES));
            var c = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, cos[k]));
            var s = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, sin[k]));
            var a = x0.lanewise(VectorOperators.SUB, x3);
            var b = x1.lanewise(VectorOperators.SUB, x2);
            var a2 = x1.lanewise(VectorOperators.ADD, x2);
            var b2 = x0.lanewise(VectorOperators.ADD, x3);
            FLOAT_VECTOR.cast(a.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, s))).intoArray(fr, k * LANES);
            FLOAT_VECTOR.cast(b.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.SUB, a.lanewise(VectorOperators.MUL, s))).intoArray(fi, k * LANES);
            FLOAT_VECTOR.cast(a2.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.ADD, b2.lanewise(VectorOperators.MUL, s))).intoArray(fr, (16 + k) * LANES);
            FLOAT_VECTOR.cast(b2.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.SUB, a2.lanewise(VectorOperators.MUL, s))).intoArray(fi, (16 + k) * LANES);
        }
    }

    @Override
    public void radix2(float[] fr, float[] fi, int base, float[] tw) {
        for (var j = 0; j < 8; j++) {
            var i0 = (base + j) * LANES;
            var i1 = i0 + 8 * LANES;
            var ar = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, i0));
            var ai = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, i0));
            var br = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, i1));
            var bi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, i1));
            var c = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, tw[j]));
            var s = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, tw[8 + j]));
            FLOAT_VECTOR.cast(ar.lanewise(VectorOperators.ADD, br)).intoArray(fr, i0);
            FLOAT_VECTOR.cast(ai.lanewise(VectorOperators.ADD, bi)).intoArray(fi, i0);
            var dr = ar.lanewise(VectorOperators.SUB, br);
            var di = ai.lanewise(VectorOperators.SUB, bi);
            FLOAT_VECTOR.cast(dr.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.SUB, di.lanewise(VectorOperators.MUL, s))).intoArray(fr, i1);
            FLOAT_VECTOR.cast(dr.lanewise(VectorOperators.MUL, s).lanewise(VectorOperators.ADD, di.lanewise(VectorOperators.MUL, c))).intoArray(fi, i1);
        }
    }

    @Override
    public void radix4(float[] fr, float[] fi, int base, float[] tw) {
        for (var j = 0; j < 8; j++) {
            var i0 = (base + j) * LANES;
            var i1 = i0 + 8 * LANES;
            var i2 = i1 + 8 * LANES;
            var i3 = i2 + 8 * LANES;
            var x0r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, i0));
            var x0i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, i0));
            var x1r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, i1));
            var x1i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, i1));
            var x2r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, i2));
            var x2i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, i2));
            var x3r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, i3));
            var x3i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, i3));
            var ar = x0r.lanewise(VectorOperators.ADD, x2r);
            var ai = x0i.lanewise(VectorOperators.ADD, x2i);
            var br = x1r.lanewise(VectorOperators.ADD, x3r);
            var bi = x1i.lanewise(VectorOperators.ADD, x3i);
            var cr = x0r.lanewise(VectorOperators.SUB, x2r);
            var ci = x0i.lanewise(VectorOperators.SUB, x2i);
            var dr = x1r.lanewise(VectorOperators.SUB, x3r);
            var di = x1i.lanewise(VectorOperators.SUB, x3i);
            FLOAT_VECTOR.cast(ar.lanewise(VectorOperators.ADD, br)).intoArray(fr, i0);
            FLOAT_VECTOR.cast(ai.lanewise(VectorOperators.ADD, bi)).intoArray(fi, i0);
            var y1r = cr.lanewise(VectorOperators.ADD, di);
            var y1i = ci.lanewise(VectorOperators.SUB, dr);
            var y2r = ar.lanewise(VectorOperators.SUB, br);
            var y2i = ai.lanewise(VectorOperators.SUB, bi);
            var y3r = cr.lanewise(VectorOperators.SUB, di);
            var y3i = ci.lanewise(VectorOperators.ADD, dr);
            var c1 = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, tw[j]));
            var c2 = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, tw[8 + j]));
            var c3 = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, tw[16 + j]));
            var s1 = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, tw[24 + j]));
            var s2 = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, tw[32 + j]));
            var s3 = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, tw[40 + j]));
            FLOAT_VECTOR.cast(y1r.lanewise(VectorOperators.MUL, c1).lanewise(VectorOperators.SUB, y1i.lanewise(VectorOperators.MUL, s1))).intoArray(fr, i1);
            FLOAT_VECTOR.cast(y1r.lanewise(VectorOperators.MUL, s1).lanewise(VectorOperators.ADD, y1i.lanewise(VectorOperators.MUL, c1))).intoArray(fi, i1);
            FLOAT_VECTOR.cast(y2r.lanewise(VectorOperators.MUL, c2).lanewise(VectorOperators.SUB, y2i.lanewise(VectorOperators.MUL, s2))).intoArray(fr, i2);
            FLOAT_VECTOR.cast(y2r.lanewise(VectorOperators.MUL, s2).lanewise(VectorOperators.ADD, y2i.lanewise(VectorOperators.MUL, c2))).intoArray(fi, i2);
            FLOAT_VECTOR.cast(y3r.lanewise(VectorOperators.MUL, c3).lanewise(VectorOperators.SUB, y3i.lanewise(VectorOperators.MUL, s3))).intoArray(fr, i3);
            FLOAT_VECTOR.cast(y3r.lanewise(VectorOperators.MUL, s3).lanewise(VectorOperators.ADD, y3i.lanewise(VectorOperators.MUL, c3))).intoArray(fi, i3);
        }
    }

    @Override
    public void tail8(float[] fr, float[] fi, int base, int blocks) {
        for (var block = 0; block < blocks; block++) {
            var at = (base + 8 * block) * LANES;
            var x0r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, at));
            var x0i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, at));
            var x1r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, at + 1 * LANES));
            var x1i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, at + 1 * LANES));
            var x2r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, at + 2 * LANES));
            var x2i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, at + 2 * LANES));
            var x3r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, at + 3 * LANES));
            var x3i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, at + 3 * LANES));
            var x4r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, at + 4 * LANES));
            var x4i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, at + 4 * LANES));
            var x5r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, at + 5 * LANES));
            var x5i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, at + 5 * LANES));
            var x6r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, at + 6 * LANES));
            var x6i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, at + 6 * LANES));
            var x7r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, at + 7 * LANES));
            var x7i = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, at + 7 * LANES));
            var r = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, ROOT_HALF));
            var a0r = x0r.lanewise(VectorOperators.ADD, x4r);
            var a0i = x0i.lanewise(VectorOperators.ADD, x4i);
            var a1r = x1r.lanewise(VectorOperators.ADD, x5r);
            var a1i = x1i.lanewise(VectorOperators.ADD, x5i);
            var a2r = x2r.lanewise(VectorOperators.ADD, x6r);
            var a2i = x2i.lanewise(VectorOperators.ADD, x6i);
            var a3r = x3r.lanewise(VectorOperators.ADD, x7r);
            var a3i = x3i.lanewise(VectorOperators.ADD, x7i);
            var a4r = x0r.lanewise(VectorOperators.SUB, x4r);
            var a4i = x0i.lanewise(VectorOperators.SUB, x4i);
            var tr = x1r.lanewise(VectorOperators.SUB, x5r);
            var ti = x1i.lanewise(VectorOperators.SUB, x5i);
            var a5r = tr.lanewise(VectorOperators.ADD, ti).lanewise(VectorOperators.MUL, r);
            var a5i = ti.lanewise(VectorOperators.SUB, tr).lanewise(VectorOperators.MUL, r);
            var a6r = x2i.lanewise(VectorOperators.SUB, x6i);
            var a6i = x6r.lanewise(VectorOperators.SUB, x2r);
            var tr2 = x3r.lanewise(VectorOperators.SUB, x7r);
            var ti2 = x3i.lanewise(VectorOperators.SUB, x7i);
            var a7r = ti2.lanewise(VectorOperators.SUB, tr2).lanewise(VectorOperators.MUL, r);
            var a7i = tr2.lanewise(VectorOperators.ADD, ti2).lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, r);
            var b0r = a0r.lanewise(VectorOperators.ADD, a2r);
            var b0i = a0i.lanewise(VectorOperators.ADD, a2i);
            var b1r = a1r.lanewise(VectorOperators.ADD, a3r);
            var b1i = a1i.lanewise(VectorOperators.ADD, a3i);
            var b2r = a0r.lanewise(VectorOperators.SUB, a2r);
            var b2i = a0i.lanewise(VectorOperators.SUB, a2i);
            var b3r = a1i.lanewise(VectorOperators.SUB, a3i);
            var b3i = a3r.lanewise(VectorOperators.SUB, a1r);
            var b4r = a4r.lanewise(VectorOperators.ADD, a6r);
            var b4i = a4i.lanewise(VectorOperators.ADD, a6i);
            var b5r = a5r.lanewise(VectorOperators.ADD, a7r);
            var b5i = a5i.lanewise(VectorOperators.ADD, a7i);
            var b6r = a4r.lanewise(VectorOperators.SUB, a6r);
            var b6i = a4i.lanewise(VectorOperators.SUB, a6i);
            var b7r = a5i.lanewise(VectorOperators.SUB, a7i);
            var b7i = a7r.lanewise(VectorOperators.SUB, a5r);
            FLOAT_VECTOR.cast(b0r.lanewise(VectorOperators.ADD, b1r)).intoArray(fr, at);
            FLOAT_VECTOR.cast(b0i.lanewise(VectorOperators.ADD, b1i)).intoArray(fi, at);
            FLOAT_VECTOR.cast(b0r.lanewise(VectorOperators.SUB, b1r)).intoArray(fr, at + 1 * LANES);
            FLOAT_VECTOR.cast(b0i.lanewise(VectorOperators.SUB, b1i)).intoArray(fi, at + 1 * LANES);
            FLOAT_VECTOR.cast(b2r.lanewise(VectorOperators.ADD, b3r)).intoArray(fr, at + 2 * LANES);
            FLOAT_VECTOR.cast(b2i.lanewise(VectorOperators.ADD, b3i)).intoArray(fi, at + 2 * LANES);
            FLOAT_VECTOR.cast(b2r.lanewise(VectorOperators.SUB, b3r)).intoArray(fr, at + 3 * LANES);
            FLOAT_VECTOR.cast(b2i.lanewise(VectorOperators.SUB, b3i)).intoArray(fi, at + 3 * LANES);
            FLOAT_VECTOR.cast(b4r.lanewise(VectorOperators.ADD, b5r)).intoArray(fr, at + 4 * LANES);
            FLOAT_VECTOR.cast(b4i.lanewise(VectorOperators.ADD, b5i)).intoArray(fi, at + 4 * LANES);
            FLOAT_VECTOR.cast(b4r.lanewise(VectorOperators.SUB, b5r)).intoArray(fr, at + 5 * LANES);
            FLOAT_VECTOR.cast(b4i.lanewise(VectorOperators.SUB, b5i)).intoArray(fi, at + 5 * LANES);
            FLOAT_VECTOR.cast(b6r.lanewise(VectorOperators.ADD, b7r)).intoArray(fr, at + 6 * LANES);
            FLOAT_VECTOR.cast(b6i.lanewise(VectorOperators.ADD, b7i)).intoArray(fi, at + 6 * LANES);
            FLOAT_VECTOR.cast(b6r.lanewise(VectorOperators.SUB, b7r)).intoArray(fr, at + 7 * LANES);
            FLOAT_VECTOR.cast(b6i.lanewise(VectorOperators.SUB, b7i)).intoArray(fi, at + 7 * LANES);
        }
    }

    @Override
    public void analysisOutput(float[] fr, float[] fi, int[] positions, float[] cos, float[] sin, float[] rotRe,
                               float[] rotIm, float[] re, float[] im) {
        for (var j = 0; j < 16; j++) {
            var even = 2 * j;
            var odd = 31 - 2 * j;
            var p = positions[j] * LANES;
            var q = p + 16 * LANES;
            var xr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, p));
            var xi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, p));
            var yr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, q));
            var yi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, q));
            var c = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, cos[j]));
            var s = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, sin[j]));
            var x0 = xr.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.ADD, xi.lanewise(VectorOperators.MUL, s));
            var x1 = xr.lanewise(VectorOperators.MUL, s).lanewise(VectorOperators.SUB, xi.lanewise(VectorOperators.MUL, c));
            var y0 = yr.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.ADD, yi.lanewise(VectorOperators.MUL, s));
            var y1 = yi.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.SUB, yr.lanewise(VectorOperators.MUL, s));
            var rer = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, rotRe[even]));
            var rei = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, rotIm[even]));
            var ror = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, rotRe[odd]));
            var roi = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, rotIm[odd]));
            FLOAT_VECTOR.cast(x0.lanewise(VectorOperators.MUL, rer).lanewise(VectorOperators.SUB, y0.lanewise(VectorOperators.MUL, rei))).intoArray(re, even * LANES);
            FLOAT_VECTOR.cast(y0.lanewise(VectorOperators.MUL, rer).lanewise(VectorOperators.ADD, x0.lanewise(VectorOperators.MUL, rei))).intoArray(im, even * LANES);
            FLOAT_VECTOR.cast(x1.lanewise(VectorOperators.MUL, ror).lanewise(VectorOperators.SUB, y1.lanewise(VectorOperators.MUL, roi))).intoArray(re, odd * LANES);
            FLOAT_VECTOR.cast(y1.lanewise(VectorOperators.MUL, ror).lanewise(VectorOperators.ADD, x1.lanewise(VectorOperators.MUL, roi))).intoArray(im, odd * LANES);
        }
    }

    @Override
    public void synthesisInput(float[] re, float[] im, int bands, float[] cos, float[] sin, float[] fr, float[] fi) {
        if (bands == 64) {
            for (var k = 0; k < 32; k++) {
                var even = 2 * k * LANES;
                var odd = (63 - 2 * k) * LANES;
                var a = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, even));
                var b = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, odd));
                var a2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, odd));
                var b2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, even));
                var c = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, cos[k]));
                var s = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, sin[k]));
                FLOAT_VECTOR.cast(a.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, s))).intoArray(fr, k * LANES);
                FLOAT_VECTOR.cast(b.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.SUB, a.lanewise(VectorOperators.MUL, s))).intoArray(fi, k * LANES);
                FLOAT_VECTOR.cast(a2.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.ADD, b2.lanewise(VectorOperators.MUL, s))).intoArray(fr, (32 + k) * LANES);
                FLOAT_VECTOR.cast(b2.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.SUB, a2.lanewise(VectorOperators.MUL, s))).intoArray(fi, (32 + k) * LANES);
            }
        } else {
            for (var k = 0; k < 16; k++) {
                var even = 2 * k * LANES;
                var odd = (31 - 2 * k) * LANES;
                var a = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, even));
                var b = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, odd));
                var a2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, odd));
                var b2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, even));
                var c = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, cos[k]));
                var s = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, sin[k]));
                FLOAT_VECTOR.cast(a.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, s))).intoArray(fr, k * LANES);
                FLOAT_VECTOR.cast(b.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.SUB, a.lanewise(VectorOperators.MUL, s))).intoArray(fi, k * LANES);
                FLOAT_VECTOR.cast(a2.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.ADD, b2.lanewise(VectorOperators.MUL, s))).intoArray(fr, (16 + k) * LANES);
                FLOAT_VECTOR.cast(b2.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.SUB, a2.lanewise(VectorOperators.MUL, s))).intoArray(fi, (16 + k) * LANES);
            }
        }
    }

    @Override
    public void synthesisOutput(float[] fr, float[] fi, int[] positions, float[] cos, float[] sin, int bands,
                                float[] v) {
        if (bands == 64) {
            for (var m = 0; m < 32; m++) {
                var other = 31 - m;
                var low0 = 2 * m * LANES;
                var high0 = (127 - 2 * m) * LANES;
                var pA = positions[m] * LANES;
                var pB = positions[other] * LANES;
                var cA = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, cos[m]));
                var sA = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, sin[m]));
                var cB = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, cos[other]));
                var sB = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, sin[other]));
                var par = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, pA));
                var pai = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, pA));
                var pbr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, pB));
                var pbi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, pB));
                var qar = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, pA + 32 * LANES));
                var qai = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, pA + 32 * LANES));
                var qbr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, pB + 32 * LANES));
                var qbi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, pB + 32 * LANES));
                var p0 = par.lanewise(VectorOperators.MUL, cA).lanewise(VectorOperators.ADD, pai.lanewise(VectorOperators.MUL, sA));
                var p1 = pbr.lanewise(VectorOperators.MUL, sB).lanewise(VectorOperators.SUB, pbi.lanewise(VectorOperators.MUL, cB));
                var q0 = qar.lanewise(VectorOperators.MUL, cA).lanewise(VectorOperators.ADD, qai.lanewise(VectorOperators.MUL, sA));
                var q1 = qbi.lanewise(VectorOperators.MUL, cB).lanewise(VectorOperators.SUB, qbr.lanewise(VectorOperators.MUL, sB));
                FLOAT_VECTOR.cast(q0.lanewise(VectorOperators.SUB, p0)).intoArray(v, low0);
                FLOAT_VECTOR.cast(q1.lanewise(VectorOperators.SUB, p1)).intoArray(v, low0 + LANES);
                FLOAT_VECTOR.cast(p0.lanewise(VectorOperators.ADD, q0)).intoArray(v, high0);
                FLOAT_VECTOR.cast(p1.lanewise(VectorOperators.ADD, q1)).intoArray(v, high0 - LANES);
            }
        } else {
            for (var m = 0; m < 16; m++) {
                var other = 15 - m;
                var low0 = 2 * m * LANES;
                var high0 = (63 - 2 * m) * LANES;
                var pA = positions[m] * LANES;
                var pB = positions[other] * LANES;
                var cA = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, cos[m]));
                var sA = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, sin[m]));
                var cB = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, cos[other]));
                var sB = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, sin[other]));
                var par = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, pA));
                var pai = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, pA));
                var pbr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, pB));
                var pbi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, pB));
                var qar = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, pA + 16 * LANES));
                var qai = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, pA + 16 * LANES));
                var qbr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fr, pB + 16 * LANES));
                var qbi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, fi, pB + 16 * LANES));
                var p0 = par.lanewise(VectorOperators.MUL, cA).lanewise(VectorOperators.ADD, pai.lanewise(VectorOperators.MUL, sA));
                var p1 = pbr.lanewise(VectorOperators.MUL, sB).lanewise(VectorOperators.SUB, pbi.lanewise(VectorOperators.MUL, cB));
                var q0 = qar.lanewise(VectorOperators.MUL, cA).lanewise(VectorOperators.ADD, qai.lanewise(VectorOperators.MUL, sA));
                var q1 = qbi.lanewise(VectorOperators.MUL, cB).lanewise(VectorOperators.SUB, qbr.lanewise(VectorOperators.MUL, sB));
                FLOAT_VECTOR.cast(q0.lanewise(VectorOperators.SUB, p0)).intoArray(v, low0);
                FLOAT_VECTOR.cast(q1.lanewise(VectorOperators.SUB, p1)).intoArray(v, low0 + LANES);
                FLOAT_VECTOR.cast(p0.lanewise(VectorOperators.ADD, q0)).intoArray(v, high0);
                FLOAT_VECTOR.cast(p1.lanewise(VectorOperators.ADD, q1)).intoArray(v, high0 - LANES);
            }
        }
    }

    @Override
    public void synthesisWindow(float[] v, int at, float[] window, int bands, float[] out, int offset, int slots) {
        if (bands == 64) {
            for (var j = 0; j < 64; j += LANES) {
                var wa0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 0 + j));
                var wb0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 64 + j));
                var wa1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 128 + j));
                var wb1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 192 + j));
                var wa2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 256 + j));
                var wb2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 320 + j));
                var wa3 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 384 + j));
                var wb3 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 448 + j));
                var wa4 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 512 + j));
                var wb4 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 576 + j));
                for (var s = 0; s < slots; s++) {
                    var at0 = at + s * 128 + j;
                    var va0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0));
                    var vb0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 64));
                    var va1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 256));
                    var vb1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 320));
                    var va2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 512));
                    var vb2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 576));
                    var va3 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 768));
                    var vb3 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 832));
                    var va4 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 1024));
                    var vb4 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 1088));
                    var sum = va0.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wa0));
                    sum = sum.lanewise(VectorOperators.ADD, vb0.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wb0)));
                    sum = sum.lanewise(VectorOperators.ADD, va1.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wa1)));
                    sum = sum.lanewise(VectorOperators.ADD, vb1.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wb1)));
                    sum = sum.lanewise(VectorOperators.ADD, va2.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wa2)));
                    sum = sum.lanewise(VectorOperators.ADD, vb2.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wb2)));
                    sum = sum.lanewise(VectorOperators.ADD, va3.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wa3)));
                    sum = sum.lanewise(VectorOperators.ADD, vb3.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wb3)));
                    sum = sum.lanewise(VectorOperators.ADD, va4.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wa4)));
                    sum = sum.lanewise(VectorOperators.ADD, vb4.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wb4)));
                    FLOAT_VECTOR.cast(sum).intoArray(out, offset + s * 64 + j);
                }
            }
        } else {
            for (var j = 0; j < 32; j += LANES) {
                var wa0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 0 + j));
                var wb0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 32 + j));
                var wa1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 64 + j));
                var wb1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 96 + j));
                var wa2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 128 + j));
                var wb2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 160 + j));
                var wa3 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 192 + j));
                var wb3 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 224 + j));
                var wa4 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 256 + j));
                var wb4 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, 288 + j));
                for (var s = 0; s < slots; s++) {
                    var at0 = at + s * 64 + j;
                    var va0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0));
                    var vb0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 32));
                    var va1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 128));
                    var vb1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 160));
                    var va2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 256));
                    var vb2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 288));
                    var va3 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 384));
                    var vb3 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 416));
                    var va4 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 512));
                    var vb4 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, v, at0 - 544));
                    var sum = va0.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wa0));
                    sum = sum.lanewise(VectorOperators.ADD, vb0.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wb0)));
                    sum = sum.lanewise(VectorOperators.ADD, va1.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wa1)));
                    sum = sum.lanewise(VectorOperators.ADD, vb1.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wb1)));
                    sum = sum.lanewise(VectorOperators.ADD, va2.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wa2)));
                    sum = sum.lanewise(VectorOperators.ADD, vb2.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wb2)));
                    sum = sum.lanewise(VectorOperators.ADD, va3.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wa3)));
                    sum = sum.lanewise(VectorOperators.ADD, vb3.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wb3)));
                    sum = sum.lanewise(VectorOperators.ADD, va4.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wa4)));
                    sum = sum.lanewise(VectorOperators.ADD, vb4.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(wb4)));
                    FLOAT_VECTOR.cast(sum).intoArray(out, offset + s * 32 + j);
                }
            }
        }
    }

    @Override
    public void transpose(float[] src, int srcOffset, int srcStride, float[] dst, int dstOffset, int dstStride,
                          int rows, int cols) {
        for (var i = 0; i < rows; i++) {
            for (var j = 0; j < cols; j++) {
                dst[dstOffset + j * dstStride + i] = src[srcOffset + i * srcStride + j];
            }
        }
    }

    @Override
    public void covariance(float[] re, float[] im, int bands, int count, double[] out) {
        var band = 1;
        for (; band + DOUBLE_LANES <= bands; band += DOUBLE_LANES) {
            covarianceLanes(re, im, band, count, out);
        }
        for (; band < bands; band++) {
            covarianceOne(re, im, band, count, out);
        }
    }

    /**
     * Accumulates the covariance of one subband with plain loops.
     *
     * @param re    the real parts of the low band
     * @param im    the imaginary parts
     * @param band  the subband
     * @param count the number of slots summed
     * @param out   receives the eight sums
     */
    private static void covarianceOne(float[] re, float[] im, int band, int count, double[] out) {
        double r01r = 0;
        double r01i = 0;
        double r02r = 0;
        double r02i = 0;
        double r11 = 0;
        double r12r = 0;
        double r12i = 0;
        double r22 = 0;
        for (var n = 0; n < count; n++) {
            var i0 = (n + 2) * 32 + band;
            var i1 = (n + 1) * 32 + band;
            var i2 = n * 32 + band;
            double ar = re[i0];
            double ai = im[i0];
            double br = re[i1];
            double bi = im[i1];
            double cr = re[i2];
            double ci = im[i2];
            r01r += ar * br + ai * bi;
            r01i += ai * br - ar * bi;
            r02r += ar * cr + ai * ci;
            r02i += ai * cr - ar * ci;
            r11 += br * br + bi * bi;
            r12r += br * cr + bi * ci;
            r12i += bi * cr - br * ci;
            r22 += cr * cr + ci * ci;
        }
        out[band] = r01r;
        out[32 + band] = r01i;
        out[64 + band] = r02r;
        out[96 + band] = r02i;
        out[128 + band] = r11;
        out[160 + band] = r12r;
        out[192 + band] = r12i;
        out[224 + band] = r22;
    }

    /**
     * Accumulates the covariance of {@link #DOUBLE_LANES} consecutive subbands.
     *
     * @param re    the real parts of the low band
     * @param im    the imaginary parts
     * @param band  the first subband
     * @param count the number of slots summed
     * @param out   receives the eight sums of each subband
     */
    private static void covarianceLanes(float[] re, float[] im, int band, int count, double[] out) {
        var r01r = DoubleVector.zero(DOUBLES);
        var r01i = DoubleVector.zero(DOUBLES);
        var r02r = DoubleVector.zero(DOUBLES);
        var r02i = DoubleVector.zero(DOUBLES);
        var r11 = DoubleVector.zero(DOUBLES);
        var r12r = DoubleVector.zero(DOUBLES);
        var r12i = DoubleVector.zero(DOUBLES);
        var r22 = DoubleVector.zero(DOUBLES);
        for (var n = 0; n < count; n++) {
            var i0 = (n + 2) * 32 + band;
            var i1 = (n + 1) * 32 + band;
            var i2 = n * 32 + band;
            var ar = DOUBLE_VECTOR.cast(NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, re, i0))
                    .convertShape(VectorOperators.F2D, DOUBLES, 0));
            var ai = DOUBLE_VECTOR.cast(NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, im, i0))
                    .convertShape(VectorOperators.F2D, DOUBLES, 0));
            var br = DOUBLE_VECTOR.cast(NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, re, i1))
                    .convertShape(VectorOperators.F2D, DOUBLES, 0));
            var bi = DOUBLE_VECTOR.cast(NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, im, i1))
                    .convertShape(VectorOperators.F2D, DOUBLES, 0));
            var cr = DOUBLE_VECTOR.cast(NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, re, i2))
                    .convertShape(VectorOperators.F2D, DOUBLES, 0));
            var ci = DOUBLE_VECTOR.cast(NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, im, i2))
                    .convertShape(VectorOperators.F2D, DOUBLES, 0));
            r01r = DOUBLE_VECTOR.cast(r01r).lanewise(VectorOperators.ADD, ar.lanewise(VectorOperators.MUL, br)
                    .lanewise(VectorOperators.ADD, ai.lanewise(VectorOperators.MUL, bi)));
            r01i = DOUBLE_VECTOR.cast(r01i).lanewise(VectorOperators.ADD, ai.lanewise(VectorOperators.MUL, br)
                    .lanewise(VectorOperators.SUB, ar.lanewise(VectorOperators.MUL, bi)));
            r02r = DOUBLE_VECTOR.cast(r02r).lanewise(VectorOperators.ADD, ar.lanewise(VectorOperators.MUL, cr)
                    .lanewise(VectorOperators.ADD, ai.lanewise(VectorOperators.MUL, ci)));
            r02i = DOUBLE_VECTOR.cast(r02i).lanewise(VectorOperators.ADD, ai.lanewise(VectorOperators.MUL, cr)
                    .lanewise(VectorOperators.SUB, ar.lanewise(VectorOperators.MUL, ci)));
            r11 = DOUBLE_VECTOR.cast(r11).lanewise(VectorOperators.ADD, br.lanewise(VectorOperators.MUL, br)
                    .lanewise(VectorOperators.ADD, bi.lanewise(VectorOperators.MUL, bi)));
            r12r = DOUBLE_VECTOR.cast(r12r).lanewise(VectorOperators.ADD, br.lanewise(VectorOperators.MUL, cr)
                    .lanewise(VectorOperators.ADD, bi.lanewise(VectorOperators.MUL, ci)));
            r12i = DOUBLE_VECTOR.cast(r12i).lanewise(VectorOperators.ADD, bi.lanewise(VectorOperators.MUL, cr)
                    .lanewise(VectorOperators.SUB, br.lanewise(VectorOperators.MUL, ci)));
            r22 = DOUBLE_VECTOR.cast(r22).lanewise(VectorOperators.ADD, cr.lanewise(VectorOperators.MUL, cr)
                    .lanewise(VectorOperators.ADD, ci.lanewise(VectorOperators.MUL, ci)));
        }
        DOUBLE_VECTOR.cast(r01r).intoArray(out, band);
        DOUBLE_VECTOR.cast(r01i).intoArray(out, 32 + band);
        DOUBLE_VECTOR.cast(r02r).intoArray(out, 64 + band);
        DOUBLE_VECTOR.cast(r02i).intoArray(out, 96 + band);
        DOUBLE_VECTOR.cast(r11).intoArray(out, 128 + band);
        DOUBLE_VECTOR.cast(r12r).intoArray(out, 160 + band);
        DOUBLE_VECTOR.cast(r12i).intoArray(out, 192 + band);
        DOUBLE_VECTOR.cast(r22).intoArray(out, 224 + band);
    }

    @Override
    public void applyGains(float[] re, float[] im, int offset, float[] gain, float[] noise, float[] noiseRe,
                           float[] noiseIm, int noiseOffset, int count) {
        var bound = FLOATS.loopBound(count);
        for (var k = 0; k < bound; k += LANES) {
            gainLanes(re, im, offset + k, gain, noise, k, noiseRe, noiseIm, noiseOffset + k);
        }
        for (var k = bound; k < count; k++) {
            re[offset + k] = re[offset + k] * gain[k] + noise[k] * noiseRe[noiseOffset + k];
            im[offset + k] = im[offset + k] * gain[k] + noise[k] * noiseIm[noiseOffset + k];
        }
    }

    /**
     * Applies gains and noise to {@link #LANES} consecutive subbands.
     *
     * @param re      the real parts
     * @param im      the imaginary parts
     * @param index   the index of the first subband
     * @param gain    the gains
     * @param noise   the noise levels
     * @param k       the index of the first gain and noise level
     * @param noiseRe the real parts of the noise table
     * @param noiseIm the imaginary parts of the noise table
     * @param n       the index of the first noise sample
     */
    private static void gainLanes(float[] re, float[] im, int index, float[] gain, float[] noise, int k,
                                  float[] noiseRe, float[] noiseIm, int n) {
        var g = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, gain, k));
        var q = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, noise, k));
        var xr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, index));
        var xi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, index));
        var nr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, noiseRe, n));
        var ni = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, noiseIm, n));
        var outRe = xr.lanewise(VectorOperators.MUL, g).lanewise(VectorOperators.ADD, q.lanewise(VectorOperators.MUL, nr));
        var outIm = xi.lanewise(VectorOperators.MUL, g).lanewise(VectorOperators.ADD, q.lanewise(VectorOperators.MUL, ni));
        FLOAT_VECTOR.cast(outRe).intoArray(re, index);
        FLOAT_VECTOR.cast(outIm).intoArray(im, index);
    }
}
