package com.github.auties00.smalt.audio.vorbis;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link VorbisKernel} with the Vector API; the output equals {@link ScalarVorbisKernel} bit for bit.
 *
 * @implNote This implementation follows the kernel rules of
 * {@link com.github.auties00.smalt.internal.simd.Simd Simd}: vectors are cast to the exact class of the
 * species and receive only per-shape {@code lanewise}, {@code compare}, {@code blend} and single-vector
 * {@code rearrange} calls, a two-vector merge being two rearrangements selected by a blend. Transforms
 * shorter than two vectors of FFT points, and lane counts outside 4 to 16, run the scalar transform.
 */
final class VectorVorbisKernel extends VorbisKernel {
    /**
     * The float vector shape used by every kernel.
     */
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    /**
     * The number of lanes of {@link #SPECIES}.
     */
    private static final int LANES = SPECIES.length();

    /**
     * The exact class of the vectors of {@link #SPECIES}.
     */
    private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(SPECIES);

    /**
     * Whether the vector FFT supports the lane count; with more than 16 lanes the smallest
     * transform (16 points) would not fill a vector, and the scalar FFT runs instead.
     */
    private static final boolean VECTOR_FFT = LANES >= 4 && LANES <= 16;

    /**
     * Marks the upper half of the lanes, which take their value from the second vector of a
     * two-vector merge.
     */
    private static final VectorMask<Float> UPPER_HALF = mask(lane -> lane >= LANES / 2);

    /**
     * Marks the odd lanes, which take their value from the second vector of an interleave.
     */
    private static final VectorMask<Float> ODD_LANES = mask(lane -> (lane & 1) != 0);

    /**
     * Moves lane {@code 2 * lane} (modulo the lane count) to each lane: blended over
     * {@link #UPPER_HALF}, the even coefficients of two concatenated vectors.
     */
    private static final VectorShuffle<Float> EVENS = VectorShuffle.fromOp(SPECIES, lane -> (2 * lane) & (LANES - 1));

    /**
     * Moves lane {@code LANES - 1 - 2 * lane} (modulo the lane count) to each lane: blended over
     * {@link #UPPER_HALF}, the reversed odd coefficients of two concatenated vectors when the
     * vector at the higher address comes first.
     */
    private static final VectorShuffle<Float> ODDS_REVERSED = VectorShuffle.fromOp(SPECIES,
            lane -> (LANES - 1 - 2 * lane) & (LANES - 1));

    /**
     * Reverses the lanes of a vector.
     */
    private static final VectorShuffle<Float> REVERSE = VectorShuffle.fromOp(SPECIES, lane -> LANES - 1 - lane);

    /**
     * Moves lane {@code lane / 2} to each lane: blended over {@link #ODD_LANES}, the interleaved
     * lower halves of two vectors, {@code a0 b0 a1 b1 ...}.
     */
    private static final VectorShuffle<Float> ZIP_LOW = VectorShuffle.fromOp(SPECIES, lane -> lane / 2);

    /**
     * Moves lane {@code LANES / 2 + lane / 2} to each lane: blended over {@link #ODD_LANES}, the
     * interleaved upper halves of two vectors.
     */
    private static final VectorShuffle<Float> ZIP_HIGH = VectorShuffle.fromOp(SPECIES, lane -> LANES / 2 + lane / 2);

    /**
     * Swaps lanes eight apart.
     */
    private static final VectorShuffle<Float> SWAP_8 = LANES > 8 ? swap(8) : null;

    /**
     * Swaps lanes four apart.
     */
    private static final VectorShuffle<Float> SWAP_4 = LANES > 4 ? swap(4) : null;

    /**
     * Swaps lanes two apart.
     */
    private static final VectorShuffle<Float> SWAP_2 = swap(2);

    /**
     * Swaps adjacent lanes.
     */
    private static final VectorShuffle<Float> SWAP_1 = swap(1);

    /**
     * Marks the lanes whose index has the bit of value 8 set.
     */
    private static final VectorMask<Float> HIGH_8 = LANES > 8 ? high(8) : null;

    /**
     * Marks the lanes whose index has the bit of value 4 set.
     */
    private static final VectorMask<Float> HIGH_4 = LANES > 4 ? high(4) : null;

    /**
     * Marks the lanes whose index has the bit of value 2 set.
     */
    private static final VectorMask<Float> HIGH_2 = high(2);

    /**
     * Marks the lanes whose index has the bit of value 1 set.
     */
    private static final VectorMask<Float> HIGH_1 = high(1);

    /**
     * Marks the lanes after the first four, whose outputs of the in-vector radix-4 stage with
     * butterflies 4 points apart take a twiddle.
     */
    private static final VectorMask<Float> AFTER_4 = LANES == 16 ? mask(lane -> lane >= 4) : null;

    /**
     * The twiddle cosines of the radix-4 stage with butterflies 4 points apart, for the first
     * vector of each group of 16 points (the only one with 16 lanes).
     */
    private static final FloatVector STAGE_16_COS = LANES == 8 || LANES == 16
            ? FloatVector.fromArray(SPECIES, stage16(true), 0) : null;

    /**
     * The twiddle sines of the radix-4 stage with butterflies 4 points apart, for the first vector
     * of each group of 16 points.
     */
    private static final FloatVector STAGE_16_SIN = LANES == 8 || LANES == 16
            ? FloatVector.fromArray(SPECIES, stage16(false), 0) : null;

    /**
     * The twiddle cosines of the radix-4 stage with butterflies 4 points apart, for the second
     * vector of each group of 16 points with 8 lanes.
     */
    private static final FloatVector STAGE_16_COS_HIGH = LANES == 8
            ? FloatVector.fromArray(SPECIES, stage16(true), 8) : null;

    /**
     * The twiddle sines of the radix-4 stage with butterflies 4 points apart, for the second vector
     * of each group of 16 points with 8 lanes.
     */
    private static final FloatVector STAGE_16_SIN_HIGH = LANES == 8
            ? FloatVector.fromArray(SPECIES, stage16(false), 8) : null;

    /**
     * Constructs the kernel.
     */
    private VectorVorbisKernel() {
    }

    /**
     * Creates the kernel; only reachable when the Vector API module is present.
     *
     * @return the kernel
     */
    static VorbisKernel create() {
        return new VectorVorbisKernel();
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

    /**
     * Creates the shuffle exchanging each lane with the lane {@code span} away.
     *
     * @param span the half-span, a power of two below the lane count
     * @return the shuffle {@code lane -> lane ^ span}
     */
    private static VectorShuffle<Float> swap(int span) {
        return VectorShuffle.fromOp(SPECIES, lane -> lane ^ span);
    }

    /**
     * Creates the mask of lanes whose {@code span} bit is set.
     *
     * @param span the half-span
     * @return the mask
     */
    private static VectorMask<Float> high(int span) {
        return mask(lane -> (lane & span) != 0);
    }

    /**
     * Creates the mask of the lanes a predicate selects.
     *
     * @param set whether a lane is set
     * @return the mask
     */
    private static VectorMask<Float> mask(java.util.function.IntPredicate set) {
        var bits = new boolean[LANES];
        for (var lane = 0; lane < LANES; lane++) {
            bits[lane] = set.test(lane);
        }
        return VectorMask.fromArray(SPECIES, bits, 0);
    }

    /**
     * Computes the twiddles of the radix-4 stage with butterflies 4 points apart for the points of
     * a group of 16: point {@code 4q + k} takes {@code w^2k}, {@code w^k} and {@code w^3k} for
     * {@code q} 1, 2 and 3, the order in which the butterfly writes its outputs, and one for
     * {@code q} 0, whose output takes no twiddle.
     *
     * @param cosine whether to return the real parts rather than the imaginary parts
     * @return the 16 twiddles
     */
    private static float[] stage16(boolean cosine) {
        var table = Imdct.RADIX4_16;
        var values = new float[16];
        for (var point = 0; point < 16; point++) {
            var q = point / 4;
            var k = point % 4;
            if (q == 0) {
                values[point] = cosine ? 1 : 0;
            } else {
                var power = q == 1 ? 2 : q == 2 ? 1 : 3;
                values[point] = table[(2 * power - (cosine ? 2 : 1)) * 4 + k];
            }
        }
        return values;
    }

    @Override
    void imdct(Imdct plan, float[] spectrum, float[] output, float[] re, float[] im) {
        if (!VECTOR_FFT || plan.quarter < 2 * LANES) {
            ScalarVorbisKernel.preRotate(plan, spectrum, re, im);
            ScalarVorbisKernel.fft(plan, re, im);
            ScalarVorbisKernel.postRotate(plan, re, im, output);
            return;
        }
        preRotate(plan, spectrum, re, im);
        fft(plan, re, im);
        postRotate(plan, re, im, spectrum, output);
    }

    /**
     * Packs the coefficients into complex FFT input and applies the pre-rotation, a vector of
     * FFT inputs at a time.
     *
     * @param plan the tables of the block size
     * @param spectrum the {@code n/2} coefficients
     * @param re receives the real parts
     * @param im receives the imaginary parts
     */
    private static void preRotate(Imdct plan, float[] spectrum, float[] re, float[] im) {
        var quarter = plan.quarter;
        var half = plan.half;
        var cos = plan.rotationCos;
        var sin = plan.rotationSin;
        for (var p = 0; p < quarter; p += LANES) {
            var low = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, spectrum, 2 * p));
            var high = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, spectrum, 2 * p + LANES));
            var a = low.rearrange(EVENS).blend(high.rearrange(EVENS), UPPER_HALF);
            var top = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, spectrum, half - 2 * p - LANES));
            var below = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, spectrum, half - 2 * p - 2 * LANES));
            var b = top.rearrange(ODDS_REVERSED).blend(below.rearrange(ODDS_REVERSED), UPPER_HALF);
            var c = FloatVector.fromArray(SPECIES, cos, p);
            var s = FloatVector.fromArray(SPECIES, sin, p);
            a.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, s))
                    .intoArray(re, p);
            b.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.SUB, a.lanewise(VectorOperators.MUL, s))
                    .intoArray(im, p);
        }
    }

    /**
     * Applies the post-rotation to the bit-reversed FFT output and unfolds the DCT-IV values into
     * the {@code n} inverse MDCT samples.
     *
     * <p>With {@code E[q]} and {@code O[q]} the real part and the negated imaginary part of the
     * rotated output {@code q} (the DCT-IV values {@code u[2q]} and {@code u[n/2 - 1 - 2q]}), and
     * {@code L = n/4}, the samples interleave one forward and one reversed run:
     * {@snippet :
     * y[2j]            =  E[L/2 + j]    y[2j + 1]            =  O[L/2 - 1 - j]    j < L/2
     * y[n/4 + 2j]      = -O[j]          y[n/4 + 2j + 1]      = -E[L - 1 - j]      j < L
     * y[3n/4 + 2j]     = -E[j]          y[3n/4 + 2j + 1]     = -O[L - 1 - j]      j < L/2
     * }
     *
     * @param plan the tables of the block size
     * @param re the real parts of the FFT output, bit-reversed
     * @param im the imaginary parts of the FFT output, bit-reversed
     * @param scratch receives {@code E} and {@code O}, at least {@code n/2} long
     * @param output receives the {@code n} samples
     */
    private static void postRotate(Imdct plan, float[] re, float[] im, float[] scratch, float[] output) {
        var quarter = plan.quarter;
        var cos = plan.rotationCos;
        var sin = plan.rotationSin;
        var reverse = plan.bitReverse;
        for (var q = 0; q < quarter; q += LANES) {
            var tr = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, re, 0, reverse, q));
            var ti = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, im, 0, reverse, q));
            var c = FloatVector.fromArray(SPECIES, cos, q);
            var s = FloatVector.fromArray(SPECIES, sin, q);
            tr.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.ADD, ti.lanewise(VectorOperators.MUL, s))
                    .intoArray(scratch, q);
            tr.lanewise(VectorOperators.MUL, s).lanewise(VectorOperators.SUB, ti.lanewise(VectorOperators.MUL, c))
                    .intoArray(scratch, quarter + q);
        }
        var eighth = quarter / 2;
        for (var j = 0; j < eighth; j += LANES) {
            var forward = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, scratch, eighth + j));
            var backward = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, scratch, quarter + eighth - LANES - j))
                    .rearrange(REVERSE);
            forward.rearrange(ZIP_LOW).blend(backward.rearrange(ZIP_LOW), ODD_LANES).intoArray(output, 2 * j);
            forward.rearrange(ZIP_HIGH).blend(backward.rearrange(ZIP_HIGH), ODD_LANES).intoArray(output, 2 * j + LANES);
        }
        var second = quarter;
        for (var j = 0; j < quarter; j += LANES) {
            var forward = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, scratch, quarter + j))
                    .lanewise(VectorOperators.NEG);
            var backward = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, scratch, quarter - LANES - j))
                    .rearrange(REVERSE).lanewise(VectorOperators.NEG);
            forward.rearrange(ZIP_LOW).blend(backward.rearrange(ZIP_LOW), ODD_LANES).intoArray(output, second + 2 * j);
            forward.rearrange(ZIP_HIGH).blend(backward.rearrange(ZIP_HIGH), ODD_LANES)
                    .intoArray(output, second + 2 * j + LANES);
        }
        var third = 3 * quarter;
        for (var j = 0; j < eighth; j += LANES) {
            var forward = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, scratch, j)).lanewise(VectorOperators.NEG);
            var backward = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, scratch, 2 * quarter - LANES - j))
                    .rearrange(REVERSE).lanewise(VectorOperators.NEG);
            forward.rearrange(ZIP_LOW).blend(backward.rearrange(ZIP_LOW), ODD_LANES).intoArray(output, third + 2 * j);
            forward.rearrange(ZIP_HIGH).blend(backward.rearrange(ZIP_HIGH), ODD_LANES)
                    .intoArray(output, third + 2 * j + LANES);
        }
    }

    /**
     * Computes the in-place decimation-in-frequency FFT, leaving the output bit-reversed.
     *
     * @param plan the tables of the block size
     * @param re the real parts
     * @param im the imaginary parts
     */
    private static void fft(Imdct plan, float[] re, float[] im) {
        var n = plan.quarter;
        var length = n;
        if (plan.radix2) {
            var h = n / 2;
            radix2(re, im, h, plan.radix2Twiddles);
            length = h;
        }
        var tw = plan.radix4Twiddles;
        var offset = 0;
        for (; length >= 4 * LANES; length >>= 2) {
            var s = length / 4;
            for (var base = 0; base < n; base += length) {
                radix4(re, im, base, s, tw, offset);
            }
            offset += 6 * s;
        }
        if (LANES == 8) {
            for (var base = 0; base < n; base += 16) {
                twoVectorRadix4(re, im, base);
            }
        }
        for (var v = 0; v < n; v += LANES) {
            withinVector(re, im, v);
        }
    }

    /**
     * Runs the first radix-2 stage, whose butterflies are half the transform apart.
     *
     * @param re the real parts
     * @param im the imaginary parts
     * @param h half the transform size
     * @param tw the {@code h} twiddle cosines followed by the {@code h} sines
     */
    private static void radix2(float[] re, float[] im, int h, float[] tw) {
        for (var k = 0; k < h; k += LANES) {
            var ar = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, re, k));
            var ai = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, im, k));
            var br = FloatVector.fromArray(SPECIES, re, k + h);
            var bi = FloatVector.fromArray(SPECIES, im, k + h);
            var wr = FloatVector.fromArray(SPECIES, tw, k);
            var wi = FloatVector.fromArray(SPECIES, tw, h + k);
            ar.lanewise(VectorOperators.ADD, br).intoArray(re, k);
            ai.lanewise(VectorOperators.ADD, bi).intoArray(im, k);
            var dr = ar.lanewise(VectorOperators.SUB, br);
            var di = ai.lanewise(VectorOperators.SUB, bi);
            dr.lanewise(VectorOperators.MUL, wr).lanewise(VectorOperators.SUB, di.lanewise(VectorOperators.MUL, wi))
                    .intoArray(re, k + h);
            dr.lanewise(VectorOperators.MUL, wi).lanewise(VectorOperators.ADD, di.lanewise(VectorOperators.MUL, wr))
                    .intoArray(im, k + h);
        }
    }

    /**
     * Runs the radix-4 stage with butterflies 4 points apart on one group of 16 points held in two
     * vectors of 8 lanes.
     *
     * @param re the real parts
     * @param im the imaginary parts
     * @param base the first index of the group
     */
    private static void twoVectorRadix4(float[] re, float[] im, int base) {
        var lowR = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, re, base));
        var lowI = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, im, base));
        var highR = FloatVector.fromArray(SPECIES, re, base + 8);
        var highI = FloatVector.fromArray(SPECIES, im, base + 8);
        // sums holds a0 | a2 and differences a1 | a3 of every butterfly
        var sumR = lowR.lanewise(VectorOperators.ADD, highR);
        var sumI = lowI.lanewise(VectorOperators.ADD, highI);
        var differenceR = lowR.lanewise(VectorOperators.SUB, highR);
        var differenceI = lowI.lanewise(VectorOperators.SUB, highI);
        var swappedSumR = sumR.rearrange(SWAP_4);
        var swappedSumI = sumI.rearrange(SWAP_4);
        var swappedDifferenceR = differenceR.rearrange(SWAP_4);
        var swappedDifferenceI = differenceI.rearrange(SWAP_4);
        var firstR = sumR.lanewise(VectorOperators.ADD, swappedSumR);
        var firstI = sumI.lanewise(VectorOperators.ADD, swappedSumI);
        var secondR = swappedSumR.lanewise(VectorOperators.SUB, sumR);
        var secondI = swappedSumI.lanewise(VectorOperators.SUB, sumI);
        firstR.blend(secondR.lanewise(VectorOperators.MUL, STAGE_16_COS)
                .lanewise(VectorOperators.SUB, secondI.lanewise(VectorOperators.MUL, STAGE_16_SIN)), HIGH_4)
                .intoArray(re, base);
        firstI.blend(secondR.lanewise(VectorOperators.MUL, STAGE_16_SIN)
                .lanewise(VectorOperators.ADD, secondI.lanewise(VectorOperators.MUL, STAGE_16_COS)), HIGH_4)
                .intoArray(im, base);
        var tR = differenceR.lanewise(VectorOperators.ADD, swappedDifferenceI)
                .blend(swappedDifferenceR.lanewise(VectorOperators.SUB, differenceI), HIGH_4);
        var tI = differenceI.lanewise(VectorOperators.SUB, swappedDifferenceR)
                .blend(swappedDifferenceI.lanewise(VectorOperators.ADD, differenceR), HIGH_4);
        tR.lanewise(VectorOperators.MUL, STAGE_16_COS_HIGH)
                .lanewise(VectorOperators.SUB, tI.lanewise(VectorOperators.MUL, STAGE_16_SIN_HIGH))
                .intoArray(re, base + 8);
        tR.lanewise(VectorOperators.MUL, STAGE_16_SIN_HIGH)
                .lanewise(VectorOperators.ADD, tI.lanewise(VectorOperators.MUL, STAGE_16_COS_HIGH))
                .intoArray(im, base + 8);
    }

    /**
     * Runs the radix-4 butterflies of one group, a vector of butterflies at a time.
     *
     * @param re the real parts
     * @param im the imaginary parts
     * @param base the first index of the group
     * @param s the distance between the four inputs of a butterfly, a multiple of the lane
     * count
     * @param tw the radix-4 twiddle table
     * @param offset the offset of this stage's twiddles
     */
    private static void radix4(float[] re, float[] im, int base, int s, float[] tw, int offset) {
        for (var k = 0; k < s; k += LANES) {
            var i0 = base + k;
            var i1 = i0 + s;
            var i2 = i1 + s;
            var i3 = i2 + s;
            var x0r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, re, i0));
            var x0i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, im, i0));
            var x1r = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, re, i1));
            var x1i = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, im, i1));
            var x2r = FloatVector.fromArray(SPECIES, re, i2);
            var x2i = FloatVector.fromArray(SPECIES, im, i2);
            var x3r = FloatVector.fromArray(SPECIES, re, i3);
            var x3i = FloatVector.fromArray(SPECIES, im, i3);
            var a0r = x0r.lanewise(VectorOperators.ADD, x2r);
            var a0i = x0i.lanewise(VectorOperators.ADD, x2i);
            var a1r = x0r.lanewise(VectorOperators.SUB, x2r);
            var a1i = x0i.lanewise(VectorOperators.SUB, x2i);
            var a2r = x1r.lanewise(VectorOperators.ADD, x3r);
            var a2i = x1i.lanewise(VectorOperators.ADD, x3i);
            var a3r = x1r.lanewise(VectorOperators.SUB, x3r);
            var a3i = x1i.lanewise(VectorOperators.SUB, x3i);
            a0r.lanewise(VectorOperators.ADD, a2r).intoArray(re, i0);
            a0i.lanewise(VectorOperators.ADD, a2i).intoArray(im, i0);
            var t2r = a0r.lanewise(VectorOperators.SUB, a2r);
            var t2i = a0i.lanewise(VectorOperators.SUB, a2i);
            var t1r = a1r.lanewise(VectorOperators.ADD, a3i);
            var t1i = a1i.lanewise(VectorOperators.SUB, a3r);
            var t3r = a1r.lanewise(VectorOperators.SUB, a3i);
            var t3i = a1i.lanewise(VectorOperators.ADD, a3r);
            var w1r = FloatVector.fromArray(SPECIES, tw, offset + k);
            var w1i = FloatVector.fromArray(SPECIES, tw, offset + s + k);
            var w2r = FloatVector.fromArray(SPECIES, tw, offset + 2 * s + k);
            var w2i = FloatVector.fromArray(SPECIES, tw, offset + 3 * s + k);
            var w3r = FloatVector.fromArray(SPECIES, tw, offset + 4 * s + k);
            var w3i = FloatVector.fromArray(SPECIES, tw, offset + 5 * s + k);
            t2r.lanewise(VectorOperators.MUL, w2r).lanewise(VectorOperators.SUB, t2i.lanewise(VectorOperators.MUL, w2i))
                    .intoArray(re, i1);
            t2r.lanewise(VectorOperators.MUL, w2i).lanewise(VectorOperators.ADD, t2i.lanewise(VectorOperators.MUL, w2r))
                    .intoArray(im, i1);
            t1r.lanewise(VectorOperators.MUL, w1r).lanewise(VectorOperators.SUB, t1i.lanewise(VectorOperators.MUL, w1i))
                    .intoArray(re, i2);
            t1r.lanewise(VectorOperators.MUL, w1i).lanewise(VectorOperators.ADD, t1i.lanewise(VectorOperators.MUL, w1r))
                    .intoArray(im, i2);
            t3r.lanewise(VectorOperators.MUL, w3r).lanewise(VectorOperators.SUB, t3i.lanewise(VectorOperators.MUL, w3i))
                    .intoArray(re, i3);
            t3r.lanewise(VectorOperators.MUL, w3i).lanewise(VectorOperators.ADD, t3i.lanewise(VectorOperators.MUL, w3r))
                    .intoArray(im, i3);
        }
    }

    /**
     * Runs the radix-4 stages whose butterflies lie within one vector: the stage with butterflies 4
     * points apart when a vector holds 16 lanes, then the last stage.
     *
     * <p>Each stage gathers the sums {@code a0}, {@code a2} and the differences {@code a1},
     * {@code a3} of every butterfly into the order {@code a0 a2 a1 a3}, pairs them with the order
     * {@code a2 a0 a3 a1} by a lane swap, and selects each output from the combinations of the two.
     *
     * @param re the real parts
     * @param im the imaginary parts
     * @param v the index of the vector
     */
    private static void withinVector(float[] re, float[] im, int v) {
        var xr = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, re, v));
        var xi = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, im, v));
        if (LANES == 16) {
            var pr = xr.rearrange(SWAP_8);
            var pi = xi.rearrange(SWAP_8);
            var ar = xr.lanewise(VectorOperators.ADD, pr).blend(pr.lanewise(VectorOperators.SUB, xr), HIGH_8);
            var ai = xi.lanewise(VectorOperators.ADD, pi).blend(pi.lanewise(VectorOperators.SUB, xi), HIGH_8);
            var br = ar.rearrange(SWAP_4);
            var bi = ai.rearrange(SWAP_4);
            var tr = ar.lanewise(VectorOperators.ADD, br).blend(br.lanewise(VectorOperators.SUB, ar), HIGH_4)
                    .blend(ar.lanewise(VectorOperators.ADD, bi).blend(br.lanewise(VectorOperators.SUB, ai), HIGH_4),
                            HIGH_8);
            var ti = ai.lanewise(VectorOperators.ADD, bi).blend(bi.lanewise(VectorOperators.SUB, ai), HIGH_4)
                    .blend(ai.lanewise(VectorOperators.SUB, br).blend(bi.lanewise(VectorOperators.ADD, ar), HIGH_4),
                            HIGH_8);
            xr = tr.blend(tr.lanewise(VectorOperators.MUL, STAGE_16_COS)
                    .lanewise(VectorOperators.SUB, ti.lanewise(VectorOperators.MUL, STAGE_16_SIN)), AFTER_4);
            xi = ti.blend(tr.lanewise(VectorOperators.MUL, STAGE_16_SIN)
                    .lanewise(VectorOperators.ADD, ti.lanewise(VectorOperators.MUL, STAGE_16_COS)), AFTER_4);
        }
        var pr = xr.rearrange(SWAP_2);
        var pi = xi.rearrange(SWAP_2);
        var ar = xr.lanewise(VectorOperators.ADD, pr).blend(pr.lanewise(VectorOperators.SUB, xr), HIGH_2);
        var ai = xi.lanewise(VectorOperators.ADD, pi).blend(pi.lanewise(VectorOperators.SUB, xi), HIGH_2);
        var br = ar.rearrange(SWAP_1);
        var bi = ai.rearrange(SWAP_1);
        ar.lanewise(VectorOperators.ADD, br).blend(br.lanewise(VectorOperators.SUB, ar), HIGH_1)
                .blend(ar.lanewise(VectorOperators.ADD, bi).blend(br.lanewise(VectorOperators.SUB, ai), HIGH_1), HIGH_2)
                .intoArray(re, v);
        ai.lanewise(VectorOperators.ADD, bi).blend(bi.lanewise(VectorOperators.SUB, ai), HIGH_1)
                .blend(ai.lanewise(VectorOperators.SUB, br).blend(bi.lanewise(VectorOperators.ADD, ar), HIGH_1), HIGH_2)
                .intoArray(im, v);
    }

    @Override
    void inverseCoupling(float[] magnitude, float[] angle, int count) {
        var zero = FloatVector.zero(SPECIES);
        var i = 0;
        for (; i <= count - LANES; i += LANES) {
            var m = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, magnitude, i));
            var a = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, angle, i));
            var difference = m.lanewise(VectorOperators.SUB, a);
            var sum = m.lanewise(VectorOperators.ADD, a);
            var positiveM = m.compare(VectorOperators.GT, zero);
            var positiveA = a.compare(VectorOperators.GT, zero);
            // M - A when M > 0 and A > 0 agree, M + A otherwise; A > 0 decides which output keeps M.
            var t = sum.blend(difference, positiveM).blend(difference.blend(sum, positiveM), positiveA.not());
            t.blend(m, positiveA).intoArray(magnitude, i);
            m.blend(t, positiveA).intoArray(angle, i);
        }
        for (; i < count; i++) {
            var m = magnitude[i];
            var a = angle[i];
            if (m > 0) {
                if (a > 0) {
                    angle[i] = m - a;
                } else {
                    angle[i] = m;
                    magnitude[i] = m + a;
                }
            } else {
                if (a > 0) {
                    angle[i] = m + a;
                } else {
                    angle[i] = m;
                    magnitude[i] = m - a;
                }
            }
        }
    }

    @Override
    void multiplyAdd(float[] a, int i, float[] b, int j, float[] dst, int d, int count) {
        var k = 0;
        for (; k <= count - LANES; k += LANES) {
            var x = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, i + k));
            var y = FloatVector.fromArray(SPECIES, b, j + k);
            x.lanewise(VectorOperators.MUL, y).lanewise(VectorOperators.ADD, FloatVector.fromArray(SPECIES, dst, d + k))
                    .intoArray(dst, d + k);
        }
        for (; k < count; k++) {
            dst[d + k] += a[i + k] * b[j + k];
        }
    }

    @Override
    void multiply(float[] a, int i, float[] b, int j, float[] dst, int d, int count) {
        var k = 0;
        for (; k <= count - LANES; k += LANES) {
            FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, a, i + k))
                    .lanewise(VectorOperators.MUL, FloatVector.fromArray(SPECIES, b, j + k)).intoArray(dst, d + k);
        }
        for (; k < count; k++) {
            dst[d + k] = a[i + k] * b[j + k];
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation is a plain loop, which the C2 compiler vectorizes by itself:
     * the decoder adds a flat run only where a long window meets a short one, too seldom for a
     * Vector API loop to reach the C2 compiler in a long-running decode, and below it the Vector
     * API operations run their allocating Java fallback.
     */
    @Override
    void add(float[] a, int i, float[] dst, int d, int count) {
        for (var k = 0; k < count; k++) {
            dst[d + k] += a[i + k];
        }
    }
}
