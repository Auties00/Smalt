package com.github.auties00.smalt.video.av1.internal;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements the motion compensation kernels with the Vector API, producing output identical to
 * {@link ScalarMcDsp}.
 *
 * <p>The 8-tap interpolation of blocks at least 8 samples wide runs as one or two separable passes,
 * each filtering 8 or 16 samples per vector. A pass over 8-bit samples runs in 16-bit lanes: the
 * filter table holds the AV1 sub-sample filters with halved taps, as dav1d's does, so a filter sum
 * over 8-bit samples, plus its rounding constant, fits a 16-bit lane. Every other horizontal pass
 * accumulates in 32-bit lanes. A vertical pass of blocks whose height is a multiple of 4 runs in
 * single-precision floats: the samples, taps and sums are integers below 2^24, so each multiply
 * and add is exact, and one conversion of a source row serves the four outputs it feeds.
 * The compound averages of 8-bit samples run in 16-bit lanes; the other compound weights, the
 * unfiltered compound copies and the overlapped block blends run in 32-bit lanes. A warp without
 * shear is a pair of ordinary 8-tap passes with one filter per direction. Narrower blocks,
 * bilinear and scaled interpolation, warps with shear, masked compound weights, edge emulation and
 * super-resolution use {@link ScalarMcDsp} or the gathering warp kernels.
 *
 * <p>Every Vector API operation goes through the per-shape methods ({@code lanewise} with an
 * operator and a vector operand, {@code convertShape}), constants are broadcast once per call, no
 * method takes or returns a vector, and every loaded or converted vector, and every vector stored
 * or shifted by a scalar, is first cast through the {@code static final} class of its species, so
 * the JIT compiler knows its exact class without a type profile; see the class documentation of
 * the VP8 vector kernels for the failure modes this avoids.
 *
 * @implNote This implementation follows the structure of dav1d's x86 kernels
 * ({@code src/x86/mc16_avx2.asm} and {@code mc_avx2.asm}): 8-bit horizontal and
 * single-pass filters in 16-bit lanes. The second pass differs: x86 accumulates it in 32-bit
 * lanes with {@code pmaddwd}, which the Vector API has no counterpart of, and this
 * implementation computes it in exact single-precision floats, whose multiply costs one
 * instruction instead of two.
 */
public final class VectorMcDsp extends McDsp {
    /**
     * The 16-lane short species of 8-bit passes.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * The 8-lane short species of narrow 8-bit passes and of 32-bit pass loads and stores.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * The 8-lane int species of 32-bit passes.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * The 8-lane float species of the exact floating-point vertical passes.
     */
    private static final VectorSpecies<Float> F256 = FloatVector.SPECIES_256;

    /**
     * The 8-lane byte species of compound masks.
     */
    private static final VectorSpecies<Byte> B64 = ByteVector.SPECIES_64;

    /**
     * The exact class of {@link #S256} vectors.
     */
    private static final Class<ShortVector> SHORT256 = vectorClass(S256);

    /**
     * The exact class of {@link #S128} vectors.
     */
    private static final Class<ShortVector> SHORT128 = vectorClass(S128);

    /**
     * The exact class of {@link #I256} vectors.
     */
    private static final Class<IntVector> INT256 = vectorClass(I256);

    /**
     * The exact class of {@link #F256} vectors.
     */
    private static final Class<FloatVector> FLOAT256 = vectorClass(F256);

    /**
     * The exact class of {@link #B64} vectors.
     */
    private static final Class<ByteVector> BYTE64 = vectorClass(B64);

    /**
     * The warp filter taps packed four to an {@code int}, little-endian, two per filter position:
     * taps 0 to 3, then taps 4 to 7.
     */
    private static final int[] WARP_TAPS = pack(McTables.WARP_FILTER);

    /**
     * The lane indices 0 to 7.
     */
    private static final IntVector IOTA = IntVector.fromArray(I256, new int[]{0, 1, 2, 3, 4, 5, 6, 7}, 0);

    /**
     * Whether every sub-sample filter sum over 8-bit samples, plus the largest rounding constant of
     * a pass over samples (34), fits a signed 16-bit lane, enabling the 16-bit passes.
     */
    private static final boolean NARROW_FITS = narrowFits();

    /**
     * Whether the biased 16-bit horizontal pass of a translation-only warp over 8-bit samples is
     * exact for every warp filter: the sum of {@code (sample - 128) * tap} stays within a signed
     * 16-bit lane once the rounding constant is added.
     */
    private static final boolean WARP_NARROW_FITS = warpNarrowFits();

    /**
     * Whether every filter of {@link McTables#SUBPEL_FILTERS} and {@link McTables#WARP_FILTER} has
     * an absolute tap sum small enough that a sum of 16-bit samples and taps stays below 2^24,
     * which the floating-point vertical passes need to be exact.
     */
    private static final boolean FLOAT_FITS = floatFits();

    /**
     * The kernels used for the cases this class does not vectorize.
     */
    private final ScalarMcDsp scalar;

    /**
     * Constructs the kernels.
     */
    private VectorMcDsp() {
        this.scalar = new ScalarMcDsp();
    }

    /**
     * Creates the kernels.
     *
     * @return the vector kernels
     */
    static McDsp create() {
        return new VectorMcDsp();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the species whose vectors are asked for
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    /**
     * Packs a byte table four entries to an {@code int}, little-endian.
     *
     * @param table the table, whose length is a multiple of 4
     * @return the packed copy
     */
    private static int[] pack(byte[] table) {
        var result = new int[table.length / 4];
        for (var i = 0; i < result.length; i++) {
            result[i] = (table[4 * i] & 0xFF) | (table[4 * i + 1] & 0xFF) << 8 | (table[4 * i + 2] & 0xFF) << 16
                    | table[4 * i + 3] << 24;
        }
        return result;
    }

    /**
     * Returns whether the 16-bit passes are exact for every sub-sample filter.
     *
     * @return {@code true} when every filter sum over 8-bit samples, plus 34, lies within the
     * range of a signed 16-bit lane
     */
    private static boolean narrowFits() {
        var filters = McTables.SUBPEL_FILTERS;
        for (var start = 0; start + McTables.TAPS <= filters.length; start += McTables.TAPS) {
            var positive = 0;
            var negative = 0;
            for (var k = 0; k < McTables.TAPS; k++) {
                var tap = filters[start + k];
                if (tap > 0) {
                    positive += tap * 255;
                } else {
                    negative += tap * 255;
                }
            }
            if (positive + 34 > Short.MAX_VALUE || negative < Short.MIN_VALUE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether the floating-point vertical passes are exact for every filter.
     *
     * @return {@code true} when the absolute tap sum of every filter, times 32768 plus the largest
     * rounding constant, stays below 2^24, the range in which a float holds every integer
     */
    private static boolean floatFits() {
        for (var filters : new byte[][]{McTables.SUBPEL_FILTERS, McTables.WARP_FILTER}) {
            for (var start = 0; start + McTables.TAPS <= filters.length; start += McTables.TAPS) {
                var magnitude = 0;
                for (var k = 0; k < McTables.TAPS; k++) {
                    magnitude += Math.abs(filters[start + k]);
                }
                if ((long) magnitude * 32768 + 4096 >= (1 << 24)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns whether the biased 16-bit warp pass is exact for every warp filter.
     *
     * @return {@code true} when every warp filter sums to 128 and has an absolute tap sum small
     * enough for {@code 128 * sum + 8} to fit a signed 16-bit lane
     */
    private static boolean warpNarrowFits() {
        var filters = McTables.WARP_FILTER;
        for (var start = 0; start + McTables.TAPS <= filters.length; start += McTables.TAPS) {
            var sum = 0;
            var magnitude = 0;
            for (var k = 0; k < McTables.TAPS; k++) {
                sum += filters[start + k];
                magnitude += Math.abs(filters[start + k]);
            }
            if (sum != 128 || 128 * magnitude + 8 > Short.MAX_VALUE) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void mc(int filter2d, short[] dst, int dstOff, int dstStride, short[] src, int srcOff, int srcStride,
                   int w, int h, int mx, int my, short[] scratch, int bitdepthMax) {
        if (filter2d == FILTER_2D_BILINEAR || w < 8) {
            scalar.mc(filter2d, dst, dstOff, dstStride, src, srcOff, srcStride, w, h, mx, my, scratch, bitdepthMax);
            return;
        }
        var type = ScalarMcDsp.filterType(filter2d);
        var fh = ScalarMcDsp.horizontalFilter(mx, w, type);
        var fv = ScalarMcDsp.verticalFilter(my, h, type);
        var ib = ScalarMcDsp.intermediateBits(bitdepthMax);
        var narrow = bitdepthMax == 255 && NARROW_FITS;
        if (fh >= 0) {
            if (fv >= 0) {
                var hSh = 6 - ib;
                pass(narrow, src, srcOff - 3 * srcStride, 1, srcStride, scratch, 0, ScalarMcDsp.MID_STRIDE, w, h + 7,
                        fh, (1 << hSh) >> 1, hSh, 0, Short.MIN_VALUE, Short.MAX_VALUE);
                var vSh = 6 + ib;
                pass(false, scratch, 3 * ScalarMcDsp.MID_STRIDE, ScalarMcDsp.MID_STRIDE, ScalarMcDsp.MID_STRIDE, dst,
                        dstOff, dstStride, w, h, fv, (1 << vSh) >> 1, vSh, 0, 0, bitdepthMax);
            } else {
                pass(narrow, src, srcOff, 1, srcStride, dst, dstOff, dstStride, w, h, fh,
                        32 + ((1 << (6 - ib)) >> 1), 6, 0, 0, bitdepthMax);
            }
        } else if (fv >= 0) {
            pass(narrow, src, srcOff, srcStride, srcStride, dst, dstOff, dstStride, w, h, fv, 32, 6, 0, 0,
                    bitdepthMax);
        } else {
            for (var y = 0; y < h; y++) {
                System.arraycopy(src, srcOff + y * srcStride, dst, dstOff + y * dstStride, w);
            }
        }
    }

    @Override
    public void mcScaled(int filter2d, short[] dst, int dstOff, int dstStride, short[] src, int srcOff,
                         int srcStride, int w, int h, int mx, int my, int dx, int dy, short[] scratch,
                         int bitdepthMax) {
        scalar.mcScaled(filter2d, dst, dstOff, dstStride, src, srcOff, srcStride, w, h, mx, my, dx, dy, scratch,
                bitdepthMax);
    }

    @Override
    public void mct(int filter2d, short[] tmp, int tmpOff, short[] src, int srcOff, int srcStride, int w, int h,
                    int mx, int my, short[] scratch, int bitdepthMax) {
        if (filter2d == FILTER_2D_BILINEAR || w < 8) {
            scalar.mct(filter2d, tmp, tmpOff, src, srcOff, srcStride, w, h, mx, my, scratch, bitdepthMax);
            return;
        }
        var type = ScalarMcDsp.filterType(filter2d);
        var fh = ScalarMcDsp.horizontalFilter(mx, w, type);
        var fv = ScalarMcDsp.verticalFilter(my, h, type);
        var ib = ScalarMcDsp.intermediateBits(bitdepthMax);
        var bias = ScalarMcDsp.prepBias(bitdepthMax);
        var narrow = bitdepthMax == 255 && NARROW_FITS;
        var hSh = 6 - ib;
        var hRnd = (1 << hSh) >> 1;
        if (fh >= 0) {
            if (fv >= 0) {
                pass(narrow, src, srcOff - 3 * srcStride, 1, srcStride, scratch, 0, ScalarMcDsp.MID_STRIDE, w, h + 7,
                        fh, hRnd, hSh, 0, Short.MIN_VALUE, Short.MAX_VALUE);
                pass(false, scratch, 3 * ScalarMcDsp.MID_STRIDE, ScalarMcDsp.MID_STRIDE, ScalarMcDsp.MID_STRIDE, tmp,
                        tmpOff, w, w, h, fv, 32, 6, bias, Short.MIN_VALUE, Short.MAX_VALUE);
            } else {
                pass(narrow, src, srcOff, 1, srcStride, tmp, tmpOff, w, w, h, fh, hRnd, hSh, bias, Short.MIN_VALUE,
                        Short.MAX_VALUE);
            }
        } else if (fv >= 0) {
            pass(narrow, src, srcOff, srcStride, srcStride, tmp, tmpOff, w, w, h, fv, hRnd, hSh, bias,
                    Short.MIN_VALUE, Short.MAX_VALUE);
        } else {
            prepCopy(tmp, tmpOff, src, srcOff, srcStride, w, h, ib, bias);
        }
    }

    @Override
    public void mctScaled(int filter2d, short[] tmp, int tmpOff, short[] src, int srcOff, int srcStride, int w,
                          int h, int mx, int my, int dx, int dy, short[] scratch, int bitdepthMax) {
        scalar.mctScaled(filter2d, tmp, tmpOff, src, srcOff, srcStride, w, h, mx, my, dx, dy, scratch, bitdepthMax);
    }

    /**
     * Runs a filter pass whose first and last taps are zero (every 8-tap regular and smooth filter)
     * with a kernel that skips them, when one exists for the pass.
     *
     * @param narrow whether the source holds 8-bit samples to filter in 16-bit lanes
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 8
     * @param rows the number of rows
     * @param filter the index of the filter's first tap in {@link McTables#SUBPEL_FILTERS}
     * @param rnd the rounding constant
     * @param sh the shift
     * @param sub the value subtracted after the shift, 0 for a narrow pass
     * @param lo the lower clamp
     * @param hi the upper clamp
     * @return {@code true} when the pass was run
     */
    private static boolean passSix(boolean narrow, short[] src, int origin, int step, int srcStride, short[] dst,
                                   int dstOff, int dstStride, int w, int rows, int filter, int rnd, int sh,
                                   int sub, int lo, int hi) {
        var f = McTables.SUBPEL_FILTERS;
        var unclamped = lo == Short.MIN_VALUE && hi == Short.MAX_VALUE;
        if (narrow) {
            if (w >= 16) {
                if (unclamped && step == 1) {
                    narrow16PlainSixH(src, origin, srcStride, dst, dstOff, dstStride, w, rows, f[filter + 1],
                            f[filter + 2], f[filter + 3], f[filter + 4], f[filter + 5], f[filter + 6], rnd, sh);
                } else if (unclamped) {
                    narrow16PlainSix(src, origin, step, srcStride, dst, dstOff, dstStride, w, rows, f[filter + 1],
                            f[filter + 2], f[filter + 3], f[filter + 4], f[filter + 5], f[filter + 6], rnd, sh);
                } else {
                    narrow16Six(src, origin, step, srcStride, dst, dstOff, dstStride, w, rows, f[filter + 1],
                            f[filter + 2], f[filter + 3], f[filter + 4], f[filter + 5], f[filter + 6], rnd, sh, lo,
                            hi);
                }
            } else if (unclamped) {
                narrow8PlainSix(src, origin, step, srcStride, dst, dstOff, dstStride, rows, f[filter + 1],
                        f[filter + 2], f[filter + 3], f[filter + 4], f[filter + 5], f[filter + 6], rnd, sh);
            } else {
                narrow8Six(src, origin, step, srcStride, dst, dstOff, dstStride, rows, f[filter + 1],
                        f[filter + 2], f[filter + 3], f[filter + 4], f[filter + 5], f[filter + 6], rnd, sh, lo, hi);
            }
            return true;
        }
        if (FLOAT_FITS && step == srcStride && (rows & 3) == 0 && (sub == 0 || unclamped)) {
            if (unclamped) {
                verticalPlainSix(src, origin, srcStride, dst, dstOff, dstStride, w, rows, f[filter + 1],
                        f[filter + 2], f[filter + 3], f[filter + 4], f[filter + 5], f[filter + 6], rnd, sh, sub);
            } else {
                verticalSix(src, origin, srcStride, dst, dstOff, dstStride, w, rows, f[filter + 1], f[filter + 2],
                        f[filter + 3], f[filter + 4], f[filter + 5], f[filter + 6], rnd, sh, lo, hi);
            }
            return true;
        }
        return false;
    }

    /**
     * Runs one 8-tap filter pass: {@code clamp(((sum + rnd) >> sh) - sub, lo, hi)} for every output
     * sample, where {@code sum} is the filter applied at the sample's position.
     *
     * @param narrow whether the source holds 8-bit samples to filter in 16-bit lanes
     * @param src the source
     * @param srcOff the index of the source sample under the fourth tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 8
     * @param rows the number of rows
     * @param filter the index of the filter's first tap in {@link McTables#SUBPEL_FILTERS}
     * @param rnd the rounding constant
     * @param sh the shift
     * @param sub the value subtracted after the shift, 0 for a narrow pass
     * @param lo the lower clamp
     * @param hi the upper clamp
     */
    private static void pass(boolean narrow, short[] src, int srcOff, int step, int srcStride, short[] dst,
                             int dstOff, int dstStride, int w, int rows, int filter, int rnd, int sh, int sub, int lo,
                             int hi) {
        var f = McTables.SUBPEL_FILTERS;
        var origin = srcOff - 3 * step;
        if (f[filter] == 0 && f[filter + 7] == 0
                && passSix(narrow, src, origin, step, srcStride, dst, dstOff, dstStride, w, rows, filter, rnd, sh,
                sub, lo, hi)) {
            return;
        }
        if (narrow) {
            var unclamped = lo == Short.MIN_VALUE && hi == Short.MAX_VALUE;
            if (w >= 16) {
                if (unclamped) {
                    narrow16Plain(src, origin, step, srcStride, dst, dstOff, dstStride, w, rows, f[filter],
                            f[filter + 1], f[filter + 2], f[filter + 3], f[filter + 4], f[filter + 5], f[filter + 6],
                            f[filter + 7], rnd, sh);
                } else {
                    narrow16(src, origin, step, srcStride, dst, dstOff, dstStride, w, rows, f[filter], f[filter + 1],
                            f[filter + 2], f[filter + 3], f[filter + 4], f[filter + 5], f[filter + 6], f[filter + 7],
                            rnd, sh, lo, hi);
                }
            } else if (unclamped) {
                narrow8Plain(src, origin, step, srcStride, dst, dstOff, dstStride, rows, f[filter], f[filter + 1],
                        f[filter + 2], f[filter + 3], f[filter + 4], f[filter + 5], f[filter + 6], f[filter + 7], rnd,
                        sh);
            } else {
                narrow8(src, origin, step, srcStride, dst, dstOff, dstStride, rows, f[filter], f[filter + 1],
                        f[filter + 2], f[filter + 3], f[filter + 4], f[filter + 5], f[filter + 6], f[filter + 7], rnd,
                        sh, lo, hi);
            }
        } else if (FLOAT_FITS && step == srcStride && (rows & 3) == 0
                && (sub == 0
                || (lo == Short.MIN_VALUE && hi == Short.MAX_VALUE))) {
            if (lo == Short.MIN_VALUE && hi == Short.MAX_VALUE) {
                verticalPlain(src, origin, srcStride, dst, dstOff, dstStride, w, rows, f[filter], f[filter + 1],
                        f[filter + 2], f[filter + 3], f[filter + 4], f[filter + 5], f[filter + 6], f[filter + 7], rnd,
                        sh, sub);
            } else {
                vertical(src, origin, srcStride, dst, dstOff, dstStride, w, rows, f[filter], f[filter + 1],
                        f[filter + 2], f[filter + 3], f[filter + 4], f[filter + 5], f[filter + 6], f[filter + 7], rnd,
                        sh, lo, hi);
            }
        } else if (lo == Short.MIN_VALUE && hi == Short.MAX_VALUE) {
            widePlain(src, origin, step, srcStride, dst, dstOff, dstStride, w, rows, f[filter], f[filter + 1],
                    f[filter + 2], f[filter + 3], f[filter + 4], f[filter + 5], f[filter + 6], f[filter + 7], rnd, sh,
                    sub);
        } else {
            wide(src, origin, step, srcStride, dst, dstOff, dstStride, w, rows, f[filter], f[filter + 1],
                    f[filter + 2], f[filter + 3], f[filter + 4], f[filter + 5], f[filter + 6], f[filter + 7], rnd, sh,
                    sub, lo, hi);
        }
    }

    /**
     * Filters rows 16 outputs at a time in 16-bit lanes.
     *
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param step the distance between taps
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 16
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     * @param rnd the rounding constant
     * @param sh the shift
     * @param lo the lower clamp
     * @param hi the upper clamp
     */
    private static void narrow16(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                                 int dstStride, int w, int rows, int t0, int t1, int t2, int t3, int t4, int t5,
                                 int t6, int t7, int rnd, int sh, int lo, int hi) {
        var c0 = ShortVector.broadcast(S256, (short) t0);
        var c1 = ShortVector.broadcast(S256, (short) t1);
        var c2 = ShortVector.broadcast(S256, (short) t2);
        var c3 = ShortVector.broadcast(S256, (short) t3);
        var c4 = ShortVector.broadcast(S256, (short) t4);
        var c5 = ShortVector.broadcast(S256, (short) t5);
        var c6 = ShortVector.broadcast(S256, (short) t6);
        var c7 = ShortVector.broadcast(S256, (short) t7);
        var round = ShortVector.broadcast(S256, (short) rnd);
        var low = ShortVector.broadcast(S256, (short) lo);
        var high = ShortVector.broadcast(S256, (short) hi);
        var row = origin;
        var out = dstOff;
        for (var y = 0; y < rows; y++, row += srcStride, out += dstStride) {
            for (var x = 0; x < w; x += 16) {
                var p = row + x;
                var sum = SHORT256.cast(ShortVector.fromArray(S256, src, p))
                        .lanewise(VectorOperators.MUL, SHORT256.cast(c0))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c1)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 2 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c2)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 3 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c3)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 4 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c4)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 5 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c5)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 6 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c6)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 7 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c7)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(round));
                SHORT256.cast(SHORT256.cast(sum).lanewise(VectorOperators.ASHR, sh)
                                .lanewise(VectorOperators.MAX, SHORT256.cast(low))
                                .lanewise(VectorOperators.MIN, SHORT256.cast(high)))
                        .intoArray(dst, out + x);
            }
        }
    }

    /**
     * Filters rows 16 outputs at a time in 16-bit lanes without clamping the result.
     *
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param step the distance between taps
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 16
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     * @param rnd the rounding constant
     * @param sh the shift
     */
    private static void narrow16Plain(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                                      int dstStride, int w, int rows, int t0, int t1, int t2, int t3, int t4,
                                      int t5, int t6, int t7, int rnd, int sh) {
        var c0 = ShortVector.broadcast(S256, (short) t0);
        var c1 = ShortVector.broadcast(S256, (short) t1);
        var c2 = ShortVector.broadcast(S256, (short) t2);
        var c3 = ShortVector.broadcast(S256, (short) t3);
        var c4 = ShortVector.broadcast(S256, (short) t4);
        var c5 = ShortVector.broadcast(S256, (short) t5);
        var c6 = ShortVector.broadcast(S256, (short) t6);
        var c7 = ShortVector.broadcast(S256, (short) t7);
        var round = ShortVector.broadcast(S256, (short) rnd);
        var row = origin;
        var out = dstOff;
        for (var y = 0; y < rows; y++, row += srcStride, out += dstStride) {
            for (var x = 0; x < w; x += 16) {
                var p = row + x;
                var sum = SHORT256.cast(ShortVector.fromArray(S256, src, p))
                        .lanewise(VectorOperators.MUL, SHORT256.cast(c0))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c1)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 2 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c2)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 3 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c3)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 4 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c4)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 5 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c5)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 6 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c6)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 7 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c7)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(round));
                SHORT256.cast(SHORT256.cast(sum).lanewise(VectorOperators.ASHR, sh))
                        .intoArray(dst, out + x);
            }
        }
    }

    /**
     * Filters rows of 8 outputs in 16-bit lanes.
     *
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param step the distance between taps
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     * @param rnd the rounding constant
     * @param sh the shift
     * @param lo the lower clamp
     * @param hi the upper clamp
     */
    private static void narrow8(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                                int dstStride, int rows, int t0, int t1, int t2, int t3, int t4, int t5, int t6,
                                int t7, int rnd, int sh, int lo, int hi) {
        var c0 = ShortVector.broadcast(S128, (short) t0);
        var c1 = ShortVector.broadcast(S128, (short) t1);
        var c2 = ShortVector.broadcast(S128, (short) t2);
        var c3 = ShortVector.broadcast(S128, (short) t3);
        var c4 = ShortVector.broadcast(S128, (short) t4);
        var c5 = ShortVector.broadcast(S128, (short) t5);
        var c6 = ShortVector.broadcast(S128, (short) t6);
        var c7 = ShortVector.broadcast(S128, (short) t7);
        var round = ShortVector.broadcast(S128, (short) rnd);
        var low = ShortVector.broadcast(S128, (short) lo);
        var high = ShortVector.broadcast(S128, (short) hi);
        for (var y = 0; y < rows; y++) {
            var p = origin + y * srcStride;
            var sum = SHORT128.cast(ShortVector.fromArray(S128, src, p))
                    .lanewise(VectorOperators.MUL, SHORT128.cast(c0))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c1)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 2 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c2)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 3 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c3)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 4 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c4)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 5 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c5)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 6 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c6)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 7 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c7)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(round));
            SHORT128.cast(SHORT128.cast(sum).lanewise(VectorOperators.ASHR, sh)
                            .lanewise(VectorOperators.MAX, SHORT128.cast(low))
                            .lanewise(VectorOperators.MIN, SHORT128.cast(high)))
                    .intoArray(dst, dstOff + y * dstStride);
        }
    }

    /**
     * Filters rows of 8 outputs in 16-bit lanes without clamping the result.
     *
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param step the distance between taps
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     * @param rnd the rounding constant
     * @param sh the shift
     */
    private static void narrow8Plain(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                                     int dstStride, int rows, int t0, int t1, int t2, int t3, int t4, int t5,
                                     int t6, int t7, int rnd, int sh) {
        var c0 = ShortVector.broadcast(S128, (short) t0);
        var c1 = ShortVector.broadcast(S128, (short) t1);
        var c2 = ShortVector.broadcast(S128, (short) t2);
        var c3 = ShortVector.broadcast(S128, (short) t3);
        var c4 = ShortVector.broadcast(S128, (short) t4);
        var c5 = ShortVector.broadcast(S128, (short) t5);
        var c6 = ShortVector.broadcast(S128, (short) t6);
        var c7 = ShortVector.broadcast(S128, (short) t7);
        var round = ShortVector.broadcast(S128, (short) rnd);
        for (var y = 0; y < rows; y++) {
            var p = origin + y * srcStride;
            var sum = SHORT128.cast(ShortVector.fromArray(S128, src, p))
                    .lanewise(VectorOperators.MUL, SHORT128.cast(c0))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c1)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 2 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c2)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 3 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c3)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 4 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c4)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 5 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c5)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 6 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c6)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 7 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c7)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(round));
            SHORT128.cast(SHORT128.cast(sum).lanewise(VectorOperators.ASHR, sh))
                    .intoArray(dst, dstOff + y * dstStride);
        }
    }

    /**
     * Filters rows 8 outputs at a time in 32-bit lanes.
     *
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param step the distance between taps
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 8
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     * @param rnd the rounding constant
     * @param sh the shift
     * @param sub the value subtracted after the shift
     * @param lo the lower clamp
     * @param hi the upper clamp
     */
    private static void wide(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                             int dstStride, int w, int rows, int t0, int t1, int t2, int t3, int t4, int t5, int t6,
                             int t7, int rnd, int sh, int sub, int lo, int hi) {
        var c0 = IntVector.broadcast(I256, t0);
        var c1 = IntVector.broadcast(I256, t1);
        var c2 = IntVector.broadcast(I256, t2);
        var c3 = IntVector.broadcast(I256, t3);
        var c4 = IntVector.broadcast(I256, t4);
        var c5 = IntVector.broadcast(I256, t5);
        var c6 = IntVector.broadcast(I256, t6);
        var c7 = IntVector.broadcast(I256, t7);
        var round = IntVector.broadcast(I256, rnd);
        var minus = IntVector.broadcast(I256, sub);
        var low = IntVector.broadcast(I256, lo);
        var high = IntVector.broadcast(I256, hi);
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = dstOff + y * dstStride;
            for (var x = 0; x < w; x += 8) {
                var p = row + x;
                var s0 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s1 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + step))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s2 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 2 * step))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s3 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 3 * step))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s4 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 4 * step))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s5 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 5 * step))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s6 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 6 * step))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s7 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 7 * step))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var sum = s0.lanewise(VectorOperators.MUL, INT256.cast(c0))
                        .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, INT256.cast(c1)))
                        .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, INT256.cast(c2)))
                        .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, INT256.cast(c3)))
                        .lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, INT256.cast(c4)))
                        .lanewise(VectorOperators.ADD, s5.lanewise(VectorOperators.MUL, INT256.cast(c5)))
                        .lanewise(VectorOperators.ADD, s6.lanewise(VectorOperators.MUL, INT256.cast(c6)))
                        .lanewise(VectorOperators.ADD, s7.lanewise(VectorOperators.MUL, INT256.cast(c7)))
                        .lanewise(VectorOperators.ADD, INT256.cast(round));
                var value = INT256.cast(sum).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.SUB, INT256.cast(minus))
                        .lanewise(VectorOperators.MAX, INT256.cast(low))
                        .lanewise(VectorOperators.MIN, INT256.cast(high));
                SHORT128.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, out + x);
            }
        }
    }

    // Begin generated by tools/codegen/av1/gen_vmc_vertical.py; edit the generator.
    /**
     * Filters columns vertically, 8 outputs wide and 4 rows at a time, with the arithmetic in
     * single-precision floats: the samples, the taps and every partial sum are integers below
     * 2^24 in magnitude, so each float operation is exact and the result is the same integer sum
     * a 32-bit pass computes. Each source row is converted once for the up to 4 outputs it
     * feeds, and a single-precision multiply and add each cost one instruction where a 32-bit
     * multiply costs two.
     *
     * <p>The value of each output is {@code (sum + rnd) >> sh}, clamped to the bounds.
     *
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param srcStride the source row stride, which is also the distance between taps
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 8
     * @param rows the number of rows, a multiple of 4
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     * @param rnd the rounding constant
     * @param sh the shift
     * @param lo the lower clamp
     * @param hi the upper clamp
     */
    private static void vertical(short[] src, int origin, int srcStride, short[] dst, int dstOff,
                                 int dstStride, int w, int rows, int t0, int t1, int t2, int t3, int t4, int t5,
                                 int t6, int t7, int rnd, int sh, int lo, int hi) {
        var c0 = FloatVector.broadcast(F256, (float) t0);
        var c1 = FloatVector.broadcast(F256, (float) t1);
        var c2 = FloatVector.broadcast(F256, (float) t2);
        var c3 = FloatVector.broadcast(F256, (float) t3);
        var c4 = FloatVector.broadcast(F256, (float) t4);
        var c5 = FloatVector.broadcast(F256, (float) t5);
        var c6 = FloatVector.broadcast(F256, (float) t6);
        var c7 = FloatVector.broadcast(F256, (float) t7);
        var round = FloatVector.broadcast(F256, (float) rnd);
        var low = IntVector.broadcast(I256, lo);
        var high = IntVector.broadcast(I256, hi);
        var row = origin;
        var out = dstOff;
        for (var y = 0; y < rows; y += 4, row += 4 * srcStride, out += 4 * dstStride) {
            var k0 = FLOAT256.cast(c0);
            var k1 = FLOAT256.cast(c1);
            var k2 = FLOAT256.cast(c2);
            var k3 = FLOAT256.cast(c3);
            var k4 = FLOAT256.cast(c4);
            var k5 = FLOAT256.cast(c5);
            var k6 = FLOAT256.cast(c6);
            var k7 = FLOAT256.cast(c7);
            var bias = FLOAT256.cast(round);
            for (var x = 0; x < w; x += 8) {
                var p = row + x;
                var m0 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 0))
                        .convertShape(VectorOperators.S2F, F256, 0));
                var a0 = m0.lanewise(VectorOperators.MUL, k0).lanewise(VectorOperators.ADD, bias);
                var m1 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 1 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m1.lanewise(VectorOperators.MUL, k1));
                var a1 = m1.lanewise(VectorOperators.MUL, k0).lanewise(VectorOperators.ADD, bias);
                var m2 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 2 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m2.lanewise(VectorOperators.MUL, k2));
                a1 = a1.lanewise(VectorOperators.ADD, m2.lanewise(VectorOperators.MUL, k1));
                var a2 = m2.lanewise(VectorOperators.MUL, k0).lanewise(VectorOperators.ADD, bias);
                var m3 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 3 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m3.lanewise(VectorOperators.MUL, k3));
                a1 = a1.lanewise(VectorOperators.ADD, m3.lanewise(VectorOperators.MUL, k2));
                a2 = a2.lanewise(VectorOperators.ADD, m3.lanewise(VectorOperators.MUL, k1));
                var a3 = m3.lanewise(VectorOperators.MUL, k0).lanewise(VectorOperators.ADD, bias);
                var m4 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 4 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m4.lanewise(VectorOperators.MUL, k4));
                a1 = a1.lanewise(VectorOperators.ADD, m4.lanewise(VectorOperators.MUL, k3));
                a2 = a2.lanewise(VectorOperators.ADD, m4.lanewise(VectorOperators.MUL, k2));
                a3 = a3.lanewise(VectorOperators.ADD, m4.lanewise(VectorOperators.MUL, k1));
                var m5 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 5 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k5));
                a1 = a1.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k4));
                a2 = a2.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k3));
                a3 = a3.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k2));
                var m6 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 6 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k6));
                a1 = a1.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k5));
                a2 = a2.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k4));
                a3 = a3.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k3));
                var m7 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 7 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m7.lanewise(VectorOperators.MUL, k7));
                a1 = a1.lanewise(VectorOperators.ADD, m7.lanewise(VectorOperators.MUL, k6));
                a2 = a2.lanewise(VectorOperators.ADD, m7.lanewise(VectorOperators.MUL, k5));
                a3 = a3.lanewise(VectorOperators.ADD, m7.lanewise(VectorOperators.MUL, k4));
                var m8 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 8 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a1 = a1.lanewise(VectorOperators.ADD, m8.lanewise(VectorOperators.MUL, k7));
                a2 = a2.lanewise(VectorOperators.ADD, m8.lanewise(VectorOperators.MUL, k6));
                a3 = a3.lanewise(VectorOperators.ADD, m8.lanewise(VectorOperators.MUL, k5));
                var m9 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 9 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a2 = a2.lanewise(VectorOperators.ADD, m9.lanewise(VectorOperators.MUL, k7));
                a3 = a3.lanewise(VectorOperators.ADD, m9.lanewise(VectorOperators.MUL, k6));
                var m10 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 10 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a3 = a3.lanewise(VectorOperators.ADD, m10.lanewise(VectorOperators.MUL, k7));
                var v0 = INT256.cast(a0.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.MAX, INT256.cast(low))
                        .lanewise(VectorOperators.MIN, INT256.cast(high));
                SHORT128.cast(v0.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 0);
                var v1 = INT256.cast(a1.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.MAX, INT256.cast(low))
                        .lanewise(VectorOperators.MIN, INT256.cast(high));
                SHORT128.cast(v1.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 1 * dstStride);
                var v2 = INT256.cast(a2.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.MAX, INT256.cast(low))
                        .lanewise(VectorOperators.MIN, INT256.cast(high));
                SHORT128.cast(v2.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 2 * dstStride);
                var v3 = INT256.cast(a3.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.MAX, INT256.cast(low))
                        .lanewise(VectorOperators.MIN, INT256.cast(high));
                SHORT128.cast(v3.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 3 * dstStride);
            }
        }
    }

    /**
     * Filters columns vertically, 8 outputs wide and 4 rows at a time, with the arithmetic in
     * single-precision floats: the samples, the taps and every partial sum are integers below
     * 2^24 in magnitude, so each float operation is exact and the result is the same integer sum
     * a 32-bit pass computes. Each source row is converted once for the up to 4 outputs it
     * feeds, and a single-precision multiply and add each cost one instruction where a 32-bit
     * multiply costs two.
     *
     * <p>The value of each output is {@code (sum + rnd) >> sh}, minus {@code sub}.
     *
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param srcStride the source row stride, which is also the distance between taps
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 8
     * @param rows the number of rows, a multiple of 4
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     * @param rnd the rounding constant
     * @param sh the shift
     * @param sub the value subtracted after the shift
     */
    private static void verticalPlain(short[] src, int origin, int srcStride, short[] dst, int dstOff,
                                      int dstStride, int w, int rows, int t0, int t1, int t2, int t3, int t4, int t5,
                                      int t6, int t7, int rnd, int sh, int sub) {
        var c0 = FloatVector.broadcast(F256, (float) t0);
        var c1 = FloatVector.broadcast(F256, (float) t1);
        var c2 = FloatVector.broadcast(F256, (float) t2);
        var c3 = FloatVector.broadcast(F256, (float) t3);
        var c4 = FloatVector.broadcast(F256, (float) t4);
        var c5 = FloatVector.broadcast(F256, (float) t5);
        var c6 = FloatVector.broadcast(F256, (float) t6);
        var c7 = FloatVector.broadcast(F256, (float) t7);
        var round = FloatVector.broadcast(F256, (float) rnd);
        var minus = IntVector.broadcast(I256, sub);
        var row = origin;
        var out = dstOff;
        for (var y = 0; y < rows; y += 4, row += 4 * srcStride, out += 4 * dstStride) {
            var k0 = FLOAT256.cast(c0);
            var k1 = FLOAT256.cast(c1);
            var k2 = FLOAT256.cast(c2);
            var k3 = FLOAT256.cast(c3);
            var k4 = FLOAT256.cast(c4);
            var k5 = FLOAT256.cast(c5);
            var k6 = FLOAT256.cast(c6);
            var k7 = FLOAT256.cast(c7);
            var bias = FLOAT256.cast(round);
            for (var x = 0; x < w; x += 8) {
                var p = row + x;
                var m0 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 0))
                        .convertShape(VectorOperators.S2F, F256, 0));
                var a0 = m0.lanewise(VectorOperators.MUL, k0).lanewise(VectorOperators.ADD, bias);
                var m1 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 1 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m1.lanewise(VectorOperators.MUL, k1));
                var a1 = m1.lanewise(VectorOperators.MUL, k0).lanewise(VectorOperators.ADD, bias);
                var m2 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 2 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m2.lanewise(VectorOperators.MUL, k2));
                a1 = a1.lanewise(VectorOperators.ADD, m2.lanewise(VectorOperators.MUL, k1));
                var a2 = m2.lanewise(VectorOperators.MUL, k0).lanewise(VectorOperators.ADD, bias);
                var m3 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 3 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m3.lanewise(VectorOperators.MUL, k3));
                a1 = a1.lanewise(VectorOperators.ADD, m3.lanewise(VectorOperators.MUL, k2));
                a2 = a2.lanewise(VectorOperators.ADD, m3.lanewise(VectorOperators.MUL, k1));
                var a3 = m3.lanewise(VectorOperators.MUL, k0).lanewise(VectorOperators.ADD, bias);
                var m4 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 4 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m4.lanewise(VectorOperators.MUL, k4));
                a1 = a1.lanewise(VectorOperators.ADD, m4.lanewise(VectorOperators.MUL, k3));
                a2 = a2.lanewise(VectorOperators.ADD, m4.lanewise(VectorOperators.MUL, k2));
                a3 = a3.lanewise(VectorOperators.ADD, m4.lanewise(VectorOperators.MUL, k1));
                var m5 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 5 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k5));
                a1 = a1.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k4));
                a2 = a2.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k3));
                a3 = a3.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k2));
                var m6 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 6 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k6));
                a1 = a1.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k5));
                a2 = a2.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k4));
                a3 = a3.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k3));
                var m7 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 7 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m7.lanewise(VectorOperators.MUL, k7));
                a1 = a1.lanewise(VectorOperators.ADD, m7.lanewise(VectorOperators.MUL, k6));
                a2 = a2.lanewise(VectorOperators.ADD, m7.lanewise(VectorOperators.MUL, k5));
                a3 = a3.lanewise(VectorOperators.ADD, m7.lanewise(VectorOperators.MUL, k4));
                var m8 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 8 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a1 = a1.lanewise(VectorOperators.ADD, m8.lanewise(VectorOperators.MUL, k7));
                a2 = a2.lanewise(VectorOperators.ADD, m8.lanewise(VectorOperators.MUL, k6));
                a3 = a3.lanewise(VectorOperators.ADD, m8.lanewise(VectorOperators.MUL, k5));
                var m9 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 9 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a2 = a2.lanewise(VectorOperators.ADD, m9.lanewise(VectorOperators.MUL, k7));
                a3 = a3.lanewise(VectorOperators.ADD, m9.lanewise(VectorOperators.MUL, k6));
                var m10 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 10 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a3 = a3.lanewise(VectorOperators.ADD, m10.lanewise(VectorOperators.MUL, k7));
                var v0 = INT256.cast(a0.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.SUB, INT256.cast(minus));
                SHORT128.cast(v0.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 0);
                var v1 = INT256.cast(a1.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.SUB, INT256.cast(minus));
                SHORT128.cast(v1.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 1 * dstStride);
                var v2 = INT256.cast(a2.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.SUB, INT256.cast(minus));
                SHORT128.cast(v2.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 2 * dstStride);
                var v3 = INT256.cast(a3.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.SUB, INT256.cast(minus));
                SHORT128.cast(v3.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 3 * dstStride);
            }
        }
    }
    // End generated by tools/codegen/av1/gen_vmc_vertical.py.

    /**
     * Filters rows 16 outputs at a time in 16-bit lanes with a filter whose first and last taps are zero.
     *
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param step the distance between taps
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 16
     * @param rows the number of rows
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param rnd the rounding constant
     * @param sh the shift
     * @param lo the lower clamp
     * @param hi the upper clamp
     */
    private static void narrow16Six(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                                 int dstStride, int w, int rows, int t1, int t2, int t3, int t4, int t5,
                                 int t6, int rnd, int sh, int lo, int hi) {
        var c1 = ShortVector.broadcast(S256, (short) t1);
        var c2 = ShortVector.broadcast(S256, (short) t2);
        var c3 = ShortVector.broadcast(S256, (short) t3);
        var c4 = ShortVector.broadcast(S256, (short) t4);
        var c5 = ShortVector.broadcast(S256, (short) t5);
        var c6 = ShortVector.broadcast(S256, (short) t6);
        var round = ShortVector.broadcast(S256, (short) rnd);
        var low = ShortVector.broadcast(S256, (short) lo);
        var high = ShortVector.broadcast(S256, (short) hi);
        var row = origin;
        var out = dstOff;
        for (var y = 0; y < rows; y++, row += srcStride, out += dstStride) {
            for (var x = 0; x < w; x += 16) {
                var p = row + x;
                var sum = SHORT256.cast(ShortVector.fromArray(S256, src, p + step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c1))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 2 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c2)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 3 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c3)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 4 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c4)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 5 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c5)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 6 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c6)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(round));
                SHORT256.cast(SHORT256.cast(sum).lanewise(VectorOperators.ASHR, sh)
                                .lanewise(VectorOperators.MAX, SHORT256.cast(low))
                                .lanewise(VectorOperators.MIN, SHORT256.cast(high)))
                        .intoArray(dst, out + x);
            }
        }
    }

    /**
     * Filters rows 16 outputs at a time in 16-bit lanes without clamping the result with a filter whose first and last
     * taps are zero.
     *
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param step the distance between taps
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 16
     * @param rows the number of rows
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param rnd the rounding constant
     * @param sh the shift
     */
    private static void narrow16PlainSix(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                                      int dstStride, int w, int rows, int t1, int t2, int t3, int t4,
                                      int t5, int t6, int rnd, int sh) {
        var c1 = ShortVector.broadcast(S256, (short) t1);
        var c2 = ShortVector.broadcast(S256, (short) t2);
        var c3 = ShortVector.broadcast(S256, (short) t3);
        var c4 = ShortVector.broadcast(S256, (short) t4);
        var c5 = ShortVector.broadcast(S256, (short) t5);
        var c6 = ShortVector.broadcast(S256, (short) t6);
        var round = ShortVector.broadcast(S256, (short) rnd);
        var row = origin;
        var out = dstOff;
        for (var y = 0; y < rows; y++, row += srcStride, out += dstStride) {
            for (var x = 0; x < w; x += 16) {
                var p = row + x;
                var sum = SHORT256.cast(ShortVector.fromArray(S256, src, p + step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c1))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 2 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c2)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 3 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c3)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 4 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c4)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 5 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c5)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 6 * step))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c6)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(round));
                SHORT256.cast(SHORT256.cast(sum).lanewise(VectorOperators.ASHR, sh))
                        .intoArray(dst, out + x);
            }
        }
    }

    /**
     * Filters rows 16 outputs at a time in 16-bit lanes without clamping the result with a filter whose first and last
     * taps are zero.
     *
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 16
     * @param rows the number of rows
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param rnd the rounding constant
     * @param sh the shift
     */
    private static void narrow16PlainSixH(short[] src, int origin, int srcStride, short[] dst, int dstOff,
                                      int dstStride, int w, int rows, int t1, int t2, int t3, int t4,
                                      int t5, int t6, int rnd, int sh) {
        var c1 = ShortVector.broadcast(S256, (short) t1);
        var c2 = ShortVector.broadcast(S256, (short) t2);
        var c3 = ShortVector.broadcast(S256, (short) t3);
        var c4 = ShortVector.broadcast(S256, (short) t4);
        var c5 = ShortVector.broadcast(S256, (short) t5);
        var c6 = ShortVector.broadcast(S256, (short) t6);
        var round = ShortVector.broadcast(S256, (short) rnd);
        var row = origin;
        var out = dstOff;
        for (var y = 0; y < rows; y++, row += srcStride, out += dstStride) {
            for (var x = 0; x < w; x += 16) {
                var p = row + x;
                var sum = SHORT256.cast(ShortVector.fromArray(S256, src, p + 1))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c1))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 2))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c2)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 3))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c3)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 4))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c4)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 5))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c5)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 6))
                                .lanewise(VectorOperators.MUL, SHORT256.cast(c6)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(round));
                SHORT256.cast(SHORT256.cast(sum).lanewise(VectorOperators.ASHR, sh))
                        .intoArray(dst, out + x);
            }
        }
    }

    /**
     * Filters rows of 8 outputs in 16-bit lanes with a filter whose first and last taps are zero.
     *
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param step the distance between taps
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param rows the number of rows
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param rnd the rounding constant
     * @param sh the shift
     * @param lo the lower clamp
     * @param hi the upper clamp
     */
    private static void narrow8Six(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                                int dstStride, int rows, int t1, int t2, int t3, int t4, int t5, int t6,
                                int rnd, int sh, int lo, int hi) {
        var c1 = ShortVector.broadcast(S128, (short) t1);
        var c2 = ShortVector.broadcast(S128, (short) t2);
        var c3 = ShortVector.broadcast(S128, (short) t3);
        var c4 = ShortVector.broadcast(S128, (short) t4);
        var c5 = ShortVector.broadcast(S128, (short) t5);
        var c6 = ShortVector.broadcast(S128, (short) t6);
        var round = ShortVector.broadcast(S128, (short) rnd);
        var low = ShortVector.broadcast(S128, (short) lo);
        var high = ShortVector.broadcast(S128, (short) hi);
        for (var y = 0; y < rows; y++) {
            var p = origin + y * srcStride;
            var sum = SHORT128.cast(ShortVector.fromArray(S128, src, p + step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c1))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 2 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c2)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 3 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c3)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 4 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c4)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 5 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c5)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 6 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c6)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(round));
            SHORT128.cast(SHORT128.cast(sum).lanewise(VectorOperators.ASHR, sh)
                            .lanewise(VectorOperators.MAX, SHORT128.cast(low))
                            .lanewise(VectorOperators.MIN, SHORT128.cast(high)))
                    .intoArray(dst, dstOff + y * dstStride);
        }
    }

    /**
     * Filters rows of 8 outputs in 16-bit lanes without clamping the result with a filter whose first and last taps are
     * zero.
     *
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param step the distance between taps
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param rows the number of rows
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param rnd the rounding constant
     * @param sh the shift
     */
    private static void narrow8PlainSix(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                                     int dstStride, int rows, int t1, int t2, int t3, int t4, int t5,
                                     int t6, int rnd, int sh) {
        var c1 = ShortVector.broadcast(S128, (short) t1);
        var c2 = ShortVector.broadcast(S128, (short) t2);
        var c3 = ShortVector.broadcast(S128, (short) t3);
        var c4 = ShortVector.broadcast(S128, (short) t4);
        var c5 = ShortVector.broadcast(S128, (short) t5);
        var c6 = ShortVector.broadcast(S128, (short) t6);
        var round = ShortVector.broadcast(S128, (short) rnd);
        for (var y = 0; y < rows; y++) {
            var p = origin + y * srcStride;
            var sum = SHORT128.cast(ShortVector.fromArray(S128, src, p + step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c1))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 2 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c2)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 3 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c3)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 4 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c4)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 5 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c5)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 6 * step))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c6)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(round));
            SHORT128.cast(SHORT128.cast(sum).lanewise(VectorOperators.ASHR, sh))
                    .intoArray(dst, dstOff + y * dstStride);
        }
    }

    // Begin generated by tools/codegen/av1/gen_vmc_vertical.py; edit the generator.
    /**
     * Filters columns vertically, 8 outputs wide and 4 rows at a time, with the arithmetic in
     * single-precision floats, for a filter whose first and last taps are zero: the samples, the
     * taps and every partial sum are integers below 2^24 in magnitude, so each float operation
     * is exact and the result is the same integer sum a 32-bit pass computes. Each source row is
     * converted once for the up to 4 outputs it feeds, and a single-precision multiply and add
     * each cost one instruction where a 32-bit multiply costs two.
     *
     * <p>The value of each output is {@code (sum + rnd) >> sh}, clamped to the bounds.
     *
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param srcStride the source row stride, which is also the distance between taps
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 8
     * @param rows the number of rows, a multiple of 4
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param rnd the rounding constant
     * @param sh the shift
     * @param lo the lower clamp
     * @param hi the upper clamp
     */
    private static void verticalSix(short[] src, int origin, int srcStride, short[] dst, int dstOff,
                                 int dstStride, int w, int rows, int t1, int t2, int t3, int t4, int t5,
                                 int t6, int rnd, int sh, int lo, int hi) {
        var c1 = FloatVector.broadcast(F256, (float) t1);
        var c2 = FloatVector.broadcast(F256, (float) t2);
        var c3 = FloatVector.broadcast(F256, (float) t3);
        var c4 = FloatVector.broadcast(F256, (float) t4);
        var c5 = FloatVector.broadcast(F256, (float) t5);
        var c6 = FloatVector.broadcast(F256, (float) t6);
        var round = FloatVector.broadcast(F256, (float) rnd);
        var low = IntVector.broadcast(I256, lo);
        var high = IntVector.broadcast(I256, hi);
        var row = origin;
        var out = dstOff;
        for (var y = 0; y < rows; y += 4, row += 4 * srcStride, out += 4 * dstStride) {
            var k1 = FLOAT256.cast(c1);
            var k2 = FLOAT256.cast(c2);
            var k3 = FLOAT256.cast(c3);
            var k4 = FLOAT256.cast(c4);
            var k5 = FLOAT256.cast(c5);
            var k6 = FLOAT256.cast(c6);
            var bias = FLOAT256.cast(round);
            for (var x = 0; x < w; x += 8) {
                var p = row + x;
                var m1 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 1 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                var a0 = m1.lanewise(VectorOperators.MUL, k1).lanewise(VectorOperators.ADD, bias);
                var m2 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 2 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m2.lanewise(VectorOperators.MUL, k2));
                var a1 = m2.lanewise(VectorOperators.MUL, k1).lanewise(VectorOperators.ADD, bias);
                var m3 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 3 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m3.lanewise(VectorOperators.MUL, k3));
                a1 = a1.lanewise(VectorOperators.ADD, m3.lanewise(VectorOperators.MUL, k2));
                var a2 = m3.lanewise(VectorOperators.MUL, k1).lanewise(VectorOperators.ADD, bias);
                var m4 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 4 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m4.lanewise(VectorOperators.MUL, k4));
                a1 = a1.lanewise(VectorOperators.ADD, m4.lanewise(VectorOperators.MUL, k3));
                a2 = a2.lanewise(VectorOperators.ADD, m4.lanewise(VectorOperators.MUL, k2));
                var a3 = m4.lanewise(VectorOperators.MUL, k1).lanewise(VectorOperators.ADD, bias);
                var m5 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 5 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k5));
                a1 = a1.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k4));
                a2 = a2.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k3));
                a3 = a3.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k2));
                var m6 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 6 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k6));
                a1 = a1.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k5));
                a2 = a2.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k4));
                a3 = a3.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k3));
                var m7 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 7 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a1 = a1.lanewise(VectorOperators.ADD, m7.lanewise(VectorOperators.MUL, k6));
                a2 = a2.lanewise(VectorOperators.ADD, m7.lanewise(VectorOperators.MUL, k5));
                a3 = a3.lanewise(VectorOperators.ADD, m7.lanewise(VectorOperators.MUL, k4));
                var m8 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 8 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a2 = a2.lanewise(VectorOperators.ADD, m8.lanewise(VectorOperators.MUL, k6));
                a3 = a3.lanewise(VectorOperators.ADD, m8.lanewise(VectorOperators.MUL, k5));
                var m9 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 9 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a3 = a3.lanewise(VectorOperators.ADD, m9.lanewise(VectorOperators.MUL, k6));
                var v0 = INT256.cast(a0.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.MAX, INT256.cast(low))
                        .lanewise(VectorOperators.MIN, INT256.cast(high));
                SHORT128.cast(v0.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 0);
                var v1 = INT256.cast(a1.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.MAX, INT256.cast(low))
                        .lanewise(VectorOperators.MIN, INT256.cast(high));
                SHORT128.cast(v1.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 1 * dstStride);
                var v2 = INT256.cast(a2.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.MAX, INT256.cast(low))
                        .lanewise(VectorOperators.MIN, INT256.cast(high));
                SHORT128.cast(v2.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 2 * dstStride);
                var v3 = INT256.cast(a3.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.MAX, INT256.cast(low))
                        .lanewise(VectorOperators.MIN, INT256.cast(high));
                SHORT128.cast(v3.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 3 * dstStride);
            }
        }
    }

    /**
     * Filters columns vertically, 8 outputs wide and 4 rows at a time, with the arithmetic in
     * single-precision floats, for a filter whose first and last taps are zero: the samples, the
     * taps and every partial sum are integers below 2^24 in magnitude, so each float operation
     * is exact and the result is the same integer sum a 32-bit pass computes. Each source row is
     * converted once for the up to 4 outputs it feeds, and a single-precision multiply and add
     * each cost one instruction where a 32-bit multiply costs two.
     *
     * <p>The value of each output is {@code (sum + rnd) >> sh}, minus {@code sub}.
     *
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param srcStride the source row stride, which is also the distance between taps
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 8
     * @param rows the number of rows, a multiple of 4
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param rnd the rounding constant
     * @param sh the shift
     * @param sub the value subtracted after the shift
     */
    private static void verticalPlainSix(short[] src, int origin, int srcStride, short[] dst, int dstOff,
                                      int dstStride, int w, int rows, int t1, int t2, int t3, int t4, int t5,
                                      int t6, int rnd, int sh, int sub) {
        var c1 = FloatVector.broadcast(F256, (float) t1);
        var c2 = FloatVector.broadcast(F256, (float) t2);
        var c3 = FloatVector.broadcast(F256, (float) t3);
        var c4 = FloatVector.broadcast(F256, (float) t4);
        var c5 = FloatVector.broadcast(F256, (float) t5);
        var c6 = FloatVector.broadcast(F256, (float) t6);
        var round = FloatVector.broadcast(F256, (float) rnd);
        var minus = IntVector.broadcast(I256, sub);
        var row = origin;
        var out = dstOff;
        for (var y = 0; y < rows; y += 4, row += 4 * srcStride, out += 4 * dstStride) {
            var k1 = FLOAT256.cast(c1);
            var k2 = FLOAT256.cast(c2);
            var k3 = FLOAT256.cast(c3);
            var k4 = FLOAT256.cast(c4);
            var k5 = FLOAT256.cast(c5);
            var k6 = FLOAT256.cast(c6);
            var bias = FLOAT256.cast(round);
            for (var x = 0; x < w; x += 8) {
                var p = row + x;
                var m1 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 1 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                var a0 = m1.lanewise(VectorOperators.MUL, k1).lanewise(VectorOperators.ADD, bias);
                var m2 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 2 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m2.lanewise(VectorOperators.MUL, k2));
                var a1 = m2.lanewise(VectorOperators.MUL, k1).lanewise(VectorOperators.ADD, bias);
                var m3 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 3 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m3.lanewise(VectorOperators.MUL, k3));
                a1 = a1.lanewise(VectorOperators.ADD, m3.lanewise(VectorOperators.MUL, k2));
                var a2 = m3.lanewise(VectorOperators.MUL, k1).lanewise(VectorOperators.ADD, bias);
                var m4 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 4 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m4.lanewise(VectorOperators.MUL, k4));
                a1 = a1.lanewise(VectorOperators.ADD, m4.lanewise(VectorOperators.MUL, k3));
                a2 = a2.lanewise(VectorOperators.ADD, m4.lanewise(VectorOperators.MUL, k2));
                var a3 = m4.lanewise(VectorOperators.MUL, k1).lanewise(VectorOperators.ADD, bias);
                var m5 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 5 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k5));
                a1 = a1.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k4));
                a2 = a2.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k3));
                a3 = a3.lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, k2));
                var m6 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 6 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a0 = a0.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k6));
                a1 = a1.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k5));
                a2 = a2.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k4));
                a3 = a3.lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, k3));
                var m7 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 7 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a1 = a1.lanewise(VectorOperators.ADD, m7.lanewise(VectorOperators.MUL, k6));
                a2 = a2.lanewise(VectorOperators.ADD, m7.lanewise(VectorOperators.MUL, k5));
                a3 = a3.lanewise(VectorOperators.ADD, m7.lanewise(VectorOperators.MUL, k4));
                var m8 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 8 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a2 = a2.lanewise(VectorOperators.ADD, m8.lanewise(VectorOperators.MUL, k6));
                a3 = a3.lanewise(VectorOperators.ADD, m8.lanewise(VectorOperators.MUL, k5));
                var m9 = FLOAT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 9 * srcStride))
                        .convertShape(VectorOperators.S2F, F256, 0));
                a3 = a3.lanewise(VectorOperators.ADD, m9.lanewise(VectorOperators.MUL, k6));
                var v0 = INT256.cast(a0.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.SUB, INT256.cast(minus));
                SHORT128.cast(v0.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 0);
                var v1 = INT256.cast(a1.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.SUB, INT256.cast(minus));
                SHORT128.cast(v1.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 1 * dstStride);
                var v2 = INT256.cast(a2.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.SUB, INT256.cast(minus));
                SHORT128.cast(v2.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 2 * dstStride);
                var v3 = INT256.cast(a3.convert(VectorOperators.F2I, 0)).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.SUB, INT256.cast(minus));
                SHORT128.cast(v3.convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x + 3 * dstStride);
            }
        }
    }
    // End generated by tools/codegen/av1/gen_vmc_vertical.py.

    /**
     * Filters rows 8 outputs at a time in 32-bit lanes without clamping the result.
     *
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param step the distance between taps
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 8
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     * @param rnd the rounding constant
     * @param sh the shift
     * @param sub the value subtracted after the shift
     */
    private static void widePlain(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                                  int dstStride, int w, int rows, int t0, int t1, int t2, int t3, int t4, int t5,
                                  int t6, int t7, int rnd, int sh, int sub) {
        var c0 = IntVector.broadcast(I256, t0);
        var c1 = IntVector.broadcast(I256, t1);
        var c2 = IntVector.broadcast(I256, t2);
        var c3 = IntVector.broadcast(I256, t3);
        var c4 = IntVector.broadcast(I256, t4);
        var c5 = IntVector.broadcast(I256, t5);
        var c6 = IntVector.broadcast(I256, t6);
        var c7 = IntVector.broadcast(I256, t7);
        var round = IntVector.broadcast(I256, rnd);
        var minus = IntVector.broadcast(I256, sub);
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = dstOff + y * dstStride;
            for (var x = 0; x < w; x += 8) {
                var p = row + x;
                var s0 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s1 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + step))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s2 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 2 * step))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s3 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 3 * step))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s4 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 4 * step))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s5 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 5 * step))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s6 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 6 * step))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s7 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 7 * step))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var sum = s0.lanewise(VectorOperators.MUL, INT256.cast(c0))
                        .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, INT256.cast(c1)))
                        .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, INT256.cast(c2)))
                        .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, INT256.cast(c3)))
                        .lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, INT256.cast(c4)))
                        .lanewise(VectorOperators.ADD, s5.lanewise(VectorOperators.MUL, INT256.cast(c5)))
                        .lanewise(VectorOperators.ADD, s6.lanewise(VectorOperators.MUL, INT256.cast(c6)))
                        .lanewise(VectorOperators.ADD, s7.lanewise(VectorOperators.MUL, INT256.cast(c7)))
                        .lanewise(VectorOperators.ADD, INT256.cast(round));
                var value = INT256.cast(sum).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.SUB, INT256.cast(minus));
                SHORT128.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, out + x);
            }
        }
    }

    /**
     * Scales a block into a compound intermediate without filtering, 8 samples at a time.
     *
     * @param tmp the intermediate buffer
     * @param tmpOff the index of the first intermediate value
     * @param src the source plane
     * @param srcOff the index of the source top-left sample
     * @param srcStride the source row stride
     * @param w the block width, a multiple of 8
     * @param h the block height
     * @param shift the number of fractional bits of the intermediates
     * @param bias the bias subtracted from the intermediates
     */
    private static void prepCopy(short[] tmp, int tmpOff, short[] src, int srcOff, int srcStride, int w, int h,
                                 int shift, int bias) {
        var minus = ShortVector.broadcast(S128, (short) bias);
        for (var y = 0; y < h; y++) {
            var row = srcOff + y * srcStride;
            var out = tmpOff + y * w;
            for (var x = 0; x < w; x += 8) {
                SHORT128.cast(SHORT128.cast(ShortVector.fromArray(S128, src, row + x))
                                .lanewise(VectorOperators.LSHL, shift))
                        .lanewise(VectorOperators.SUB, SHORT128.cast(minus))
                        .intoArray(tmp, out + x);
            }
        }
    }

    @Override
    public void avg(short[] dst, int dstOff, int dstStride, short[] tmp1, int tmp1Off, short[] tmp2, int tmp2Off,
                    int w, int h, int bitdepthMax) {
        if (w < 8) {
            scalar.avg(dst, dstOff, dstStride, tmp1, tmp1Off, tmp2, tmp2Off, w, h, bitdepthMax);
            return;
        }
        if (bitdepthMax == 255) {
            if (w >= 16) {
                avg16(dst, dstOff, dstStride, tmp1, tmp1Off, tmp2, tmp2Off, w, h);
            } else {
                avg8(dst, dstOff, dstStride, tmp1, tmp1Off, tmp2, tmp2Off, h);
            }
            return;
        }
        var ib = ScalarMcDsp.intermediateBits(bitdepthMax);
        weighted(dst, dstOff, dstStride, tmp1, tmp1Off, tmp2, tmp2Off, w, h, 1, 1,
                (1 << ib) + ScalarMcDsp.prepBias(bitdepthMax) * 2, ib + 1, bitdepthMax);
    }

    /**
     * Averages two 8-bit compound intermediates, 16 samples at a time in 16-bit lanes:
     * {@code clip((t1 + t2 + 16) >> 5)}. The intermediates of 8-bit samples lie within
     * {@code [-5132, 9212]}, so their sum fits a lane.
     *
     * @param dst the destination plane
     * @param dstOff the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param tmp1 the first intermediate
     * @param tmp1Off the index of the first intermediate's first value
     * @param tmp2 the second intermediate
     * @param tmp2Off the index of the second intermediate's first value
     * @param w the block width, a multiple of 16
     * @param h the block height
     */
    private static void avg16(short[] dst, int dstOff, int dstStride, short[] tmp1, int tmp1Off, short[] tmp2,
                              int tmp2Off, int w, int h) {
        var round = ShortVector.broadcast(S256, (short) 16);
        var zero = ShortVector.zero(S256);
        var high = ShortVector.broadcast(S256, (short) 255);
        var a = tmp1Off;
        var b = tmp2Off;
        var out = dstOff;
        for (var y = 0; y < h; y++, a += w, b += w, out += dstStride) {
            for (var x = 0; x < w; x += 16) {
                var sum = SHORT256.cast(ShortVector.fromArray(S256, tmp1, a + x))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, tmp2, b + x)))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(round));
                SHORT256.cast(SHORT256.cast(sum).lanewise(VectorOperators.ASHR, 5)
                                .lanewise(VectorOperators.MAX, SHORT256.cast(zero))
                                .lanewise(VectorOperators.MIN, SHORT256.cast(high)))
                        .intoArray(dst, out + x);
            }
        }
    }

    /**
     * Averages two 8-bit compound intermediates of width 8 in 16-bit lanes.
     *
     * @param dst the destination plane
     * @param dstOff the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param tmp1 the first intermediate
     * @param tmp1Off the index of the first intermediate's first value
     * @param tmp2 the second intermediate
     * @param tmp2Off the index of the second intermediate's first value
     * @param h the block height
     */
    private static void avg8(short[] dst, int dstOff, int dstStride, short[] tmp1, int tmp1Off, short[] tmp2,
                             int tmp2Off, int h) {
        var round = ShortVector.broadcast(S128, (short) 16);
        var zero = ShortVector.zero(S128);
        var high = ShortVector.broadcast(S128, (short) 255);
        var a = tmp1Off;
        var b = tmp2Off;
        var out = dstOff;
        for (var y = 0; y < h; y++, a += 8, b += 8, out += dstStride) {
            var sum = SHORT128.cast(ShortVector.fromArray(S128, tmp1, a))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, tmp2, b)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(round));
            SHORT128.cast(SHORT128.cast(sum).lanewise(VectorOperators.ASHR, 5)
                            .lanewise(VectorOperators.MAX, SHORT128.cast(zero))
                            .lanewise(VectorOperators.MIN, SHORT128.cast(high)))
                    .intoArray(dst, out);
        }
    }

    @Override
    public void wAvg(short[] dst, int dstOff, int dstStride, short[] tmp1, int tmp1Off, short[] tmp2, int tmp2Off,
                     int w, int h, int weight, int bitdepthMax) {
        if (w < 8) {
            scalar.wAvg(dst, dstOff, dstStride, tmp1, tmp1Off, tmp2, tmp2Off, w, h, weight, bitdepthMax);
            return;
        }
        var ib = ScalarMcDsp.intermediateBits(bitdepthMax);
        weighted(dst, dstOff, dstStride, tmp1, tmp1Off, tmp2, tmp2Off, w, h, weight, 16 - weight,
                (8 << ib) + ScalarMcDsp.prepBias(bitdepthMax) * 16, ib + 4, bitdepthMax);
    }

    /**
     * Combines two compound intermediates with fixed weights, 8 samples at a time:
     * {@code clip((t1 * w1 + t2 * w2 + rnd) >> sh)}.
     *
     * @param dst the destination plane
     * @param dstOff the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param tmp1 the first intermediate
     * @param tmp1Off the index of the first intermediate's first value
     * @param tmp2 the second intermediate
     * @param tmp2Off the index of the second intermediate's first value
     * @param w the block width, a multiple of 8
     * @param h the block height
     * @param w1 the weight of the first intermediate
     * @param w2 the weight of the second intermediate
     * @param rnd the rounding constant, including the removal of the intermediates' bias
     * @param sh the shift
     * @param bitdepthMax the largest sample value
     */
    private static void weighted(short[] dst, int dstOff, int dstStride, short[] tmp1, int tmp1Off, short[] tmp2,
                                 int tmp2Off, int w, int h, int w1, int w2, int rnd, int sh, int bitdepthMax) {
        var weight1 = IntVector.broadcast(I256, w1);
        var weight2 = IntVector.broadcast(I256, w2);
        var round = IntVector.broadcast(I256, rnd);
        var zero = IntVector.zero(I256);
        var high = IntVector.broadcast(I256, bitdepthMax);
        for (var y = 0; y < h; y++) {
            var a = tmp1Off + y * w;
            var b = tmp2Off + y * w;
            var out = dstOff + y * dstStride;
            for (var x = 0; x < w; x += 8) {
                var first = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp1, a + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var second = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp2, b + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var sum = first.lanewise(VectorOperators.MUL, INT256.cast(weight1))
                        .lanewise(VectorOperators.ADD, second.lanewise(VectorOperators.MUL, INT256.cast(weight2)))
                        .lanewise(VectorOperators.ADD, INT256.cast(round));
                var value = INT256.cast(sum).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.MAX, INT256.cast(zero))
                        .lanewise(VectorOperators.MIN, INT256.cast(high));
                SHORT128.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, out + x);
            }
        }
    }

    @Override
    public void mask(short[] dst, int dstOff, int dstStride, short[] tmp1, int tmp1Off, short[] tmp2, int tmp2Off,
                     int w, int h, byte[] mask, int maskOff, int bitdepthMax) {
        if (w < 8) {
            scalar.mask(dst, dstOff, dstStride, tmp1, tmp1Off, tmp2, tmp2Off, w, h, mask, maskOff, bitdepthMax);
            return;
        }
        var ib = ScalarMcDsp.intermediateBits(bitdepthMax);
        var sh = ib + 6;
        var round = IntVector.broadcast(I256, (32 << ib) + ScalarMcDsp.prepBias(bitdepthMax) * 64);
        var full = IntVector.broadcast(I256, 64);
        var zero = IntVector.zero(I256);
        var high = IntVector.broadcast(I256, bitdepthMax);
        for (var y = 0; y < h; y++) {
            var a = tmp1Off + y * w;
            var b = tmp2Off + y * w;
            var mk = maskOff + y * w;
            var out = dstOff + y * dstStride;
            for (var x = 0; x < w; x += 8) {
                var first = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp1, a + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var second = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp2, b + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var weight = INT256.cast(BYTE64.cast(ByteVector.fromArray(B64, mask, mk + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
                var sum = first.lanewise(VectorOperators.MUL, weight)
                        .lanewise(VectorOperators.ADD, second.lanewise(VectorOperators.MUL,
                                INT256.cast(full).lanewise(VectorOperators.SUB, weight)))
                        .lanewise(VectorOperators.ADD, INT256.cast(round));
                var value = INT256.cast(sum).lanewise(VectorOperators.ASHR, sh)
                        .lanewise(VectorOperators.MAX, INT256.cast(zero))
                        .lanewise(VectorOperators.MIN, INT256.cast(high));
                SHORT128.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, out + x);
            }
        }
    }

    @Override
    public void wMask(int layoutIndex, short[] dst, int dstOff, int dstStride, short[] tmp1, int tmp1Off,
                      short[] tmp2, int tmp2Off, int w, int h, byte[] mask, int maskOff, int sign, int bitdepthMax) {
        scalar.wMask(layoutIndex, dst, dstOff, dstStride, tmp1, tmp1Off, tmp2, tmp2Off, w, h, mask, maskOff, sign,
                bitdepthMax);
    }

    @Override
    public void blend(short[] dst, int dstOff, int dstStride, short[] tmp, int tmpOff, int w, int h, byte[] mask,
                      int maskOff) {
        if (w < 8) {
            scalar.blend(dst, dstOff, dstStride, tmp, tmpOff, w, h, mask, maskOff);
            return;
        }
        var full = IntVector.broadcast(I256, 64);
        var round = IntVector.broadcast(I256, 32);
        for (var y = 0; y < h; y++) {
            var out = dstOff + y * dstStride;
            var t = tmpOff + y * w;
            var mk = maskOff + y * w;
            for (var x = 0; x < w; x += 8) {
                var a = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, dst, out + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var b = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var weight = INT256.cast(BYTE64.cast(ByteVector.fromArray(B64, mask, mk + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
                var sum = a.lanewise(VectorOperators.MUL, INT256.cast(full).lanewise(VectorOperators.SUB, weight))
                        .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, weight))
                        .lanewise(VectorOperators.ADD, INT256.cast(round));
                SHORT128.cast(INT256.cast(sum).lanewise(VectorOperators.ASHR, 6)
                                .convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x);
            }
        }
    }

    @Override
    public void blendV(short[] dst, int dstOff, int dstStride, short[] tmp, int tmpOff, int w, int h) {
        scalar.blendV(dst, dstOff, dstStride, tmp, tmpOff, w, h);
    }

    @Override
    public void blendH(short[] dst, int dstOff, int dstStride, short[] tmp, int tmpOff, int w, int h) {
        if (w < 8) {
            scalar.blendH(dst, dstOff, dstStride, tmp, tmpOff, w, h);
            return;
        }
        var masks = McTables.OBMC_MASKS;
        var rows = (h * 3) >> 2;
        var full = IntVector.broadcast(I256, 64);
        var round = IntVector.broadcast(I256, 32);
        for (var y = 0; y < rows; y++) {
            var weight = IntVector.broadcast(I256, masks[h + y] & 0xFF);
            var inverse = INT256.cast(full).lanewise(VectorOperators.SUB, weight);
            var out = dstOff + y * dstStride;
            var t = tmpOff + y * w;
            for (var x = 0; x < w; x += 8) {
                var a = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, dst, out + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var b = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var sum = a.lanewise(VectorOperators.MUL, INT256.cast(inverse))
                        .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, INT256.cast(weight)))
                        .lanewise(VectorOperators.ADD, INT256.cast(round));
                SHORT128.cast(INT256.cast(sum).lanewise(VectorOperators.ASHR, 6)
                                .convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, out + x);
            }
        }
    }

    @Override
    public void warp8x8(short[] dst, int dstOff, int dstStride, short[] src, int srcOff, int srcStride,
                        short[] abcd, int mx, int my, short[] scratch, int[] indices, int bitdepthMax) {
        var ib = ScalarMcDsp.intermediateBits(bitdepthMax);
        var hSh = 7 - ib;
        var vSh = 7 + ib;
        if ((abcd[0] | abcd[1] | abcd[2] | abcd[3]) == 0) {
            warpTranslate(scratch, src, srcOff - 3 * srcStride - 3, srcStride, mx, hSh, bitdepthMax);
            var f = (64 + ((my + 512) >> 10)) * McTables.TAPS;
            var w = McTables.WARP_FILTER;
            if (FLOAT_FITS) {
                warpVertical(scratch, 8, dst, dstOff, dstStride, 8, 8, f, (1 << vSh) >> 1, vSh, 0, 0, bitdepthMax);
            } else {
                wide(scratch, 0, 8, 8, dst, dstOff, dstStride, 8, 8, w[f], w[f + 1], w[f + 2], w[f + 3], w[f + 4],
                        w[f + 5], w[f + 6], w[f + 7], (1 << vSh) >> 1, vSh, 0, 0, bitdepthMax);
            }
            return;
        }
        warpRows(scratch, src, srcOff - 3 * srcStride - 3, srcStride, abcd[0], abcd[1], mx, (1 << hSh) >> 1, hSh,
                indices);
        warpColumns(dst, dstOff, dstStride, scratch, abcd[2], abcd[3], my, (1 << vSh) >> 1, vSh, 0, 0, bitdepthMax,
                indices);
    }

    /**
     * Filters the rows of the scratch buffer of a translation-only warp vertically with one
     * filter for every output, in single-precision floats, skipping the taps a filter with a
     * window of six non-zero taps does not need.
     *
     * @param mid the scratch buffer filled by the horizontal pass
     * @param stride the row stride of {@code mid}
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 8
     * @param rows the number of rows, a multiple of 4
     * @param f the index of the filter's first tap in {@link McTables#WARP_FILTER}
     * @param rnd the rounding constant
     * @param sh the shift
     * @param sub the value subtracted after the shift
     * @param lo the lower clamp
     * @param hi the upper clamp
     */
    private static void warpVertical(short[] mid, int stride, short[] dst, int dstOff, int dstStride, int w, int rows,
                                     int f, int rnd, int sh, int sub, int lo, int hi) {
        var t = McTables.WARP_FILTER;
        var plain = lo == Short.MIN_VALUE && hi == Short.MAX_VALUE;
        var a = t[f + 6] == 0 && t[f + 7] == 0 ? 0 : t[f] == 0 && t[f + 7] == 0 ? 1 : t[f] == 0 && t[f + 1] == 0 ? 2 : -1;
        if (a >= 0) {
            var origin = (a - 1) * stride;
            var g = f + a;
            if (plain) {
                verticalPlainSix(mid, origin, stride, dst, dstOff, dstStride, w, rows, t[g], t[g + 1], t[g + 2],
                        t[g + 3], t[g + 4], t[g + 5], rnd, sh, sub);
            } else {
                verticalSix(mid, origin, stride, dst, dstOff, dstStride, w, rows, t[g], t[g + 1], t[g + 2], t[g + 3],
                        t[g + 4], t[g + 5], rnd, sh, lo, hi);
            }
        } else if (plain) {
            verticalPlain(mid, 0, stride, dst, dstOff, dstStride, w, rows, t[f], t[f + 1], t[f + 2], t[f + 3],
                    t[f + 4], t[f + 5], t[f + 6], t[f + 7], rnd, sh, sub);
        } else {
            vertical(mid, 0, stride, dst, dstOff, dstStride, w, rows, t[f], t[f + 1], t[f + 2], t[f + 3], t[f + 4],
                    t[f + 5], t[f + 6], t[f + 7], rnd, sh, lo, hi);
        }
    }

    @Override
    public void warp8x8t(short[] tmp, int tmpOff, int tmpStride, short[] src, int srcOff, int srcStride,
                         short[] abcd, int mx, int my, short[] scratch, int[] indices, int bitdepthMax) {
        var ib = ScalarMcDsp.intermediateBits(bitdepthMax);
        var hSh = 7 - ib;
        if ((abcd[0] | abcd[1] | abcd[2] | abcd[3]) == 0) {
            warpTranslate(scratch, src, srcOff - 3 * srcStride - 3, srcStride, mx, hSh, bitdepthMax);
            var f = (64 + ((my + 512) >> 10)) * McTables.TAPS;
            var w = McTables.WARP_FILTER;
            if (FLOAT_FITS) {
                warpVertical(scratch, 8, tmp, tmpOff, tmpStride, 8, 8, f, 64, 7, ScalarMcDsp.prepBias(bitdepthMax),
                        Short.MIN_VALUE, Short.MAX_VALUE);
            } else {
                widePlain(scratch, 0, 8, 8, tmp, tmpOff, tmpStride, 8, 8, w[f], w[f + 1], w[f + 2], w[f + 3],
                        w[f + 4], w[f + 5], w[f + 6], w[f + 7], 64, 7, ScalarMcDsp.prepBias(bitdepthMax));
            }
            return;
        }
        warpRows(scratch, src, srcOff - 3 * srcStride - 3, srcStride, abcd[0], abcd[1], mx, (1 << hSh) >> 1, hSh,
                indices);
        warpColumns(tmp, tmpOff, tmpStride, scratch, abcd[2], abcd[3], my, 64, 7, ScalarMcDsp.prepBias(bitdepthMax),
                Short.MIN_VALUE, Short.MAX_VALUE, indices);
    }

    /**
     * Filters the 15 rows of a translation-only warp horizontally into the scratch buffer. With no
     * shear every output of every row uses the same filter, so the pass is an ordinary 8-tap
     * filter with broadcast taps: in 16-bit lanes over 8-bit samples, in 32-bit lanes otherwise.
     *
     * @param mid the scratch buffer, receiving 15 rows of 8 values
     * @param src the reference plane
     * @param origin the index of the reference sample 3 rows above and 3 columns left of the
     * block's integer position
     * @param srcStride the reference row stride
     * @param mx the horizontal filter position
     * @param sh the shift
     * @param bitdepthMax the maximum sample value
     */
    private static void warpTranslate(short[] mid, short[] src, int origin, int srcStride, int mx, int sh,
                                      int bitdepthMax) {
        var f = (64 + ((mx + 512) >> 10)) * McTables.TAPS;
        var w = McTables.WARP_FILTER;
        if (bitdepthMax == 255 && WARP_NARROW_FITS) {
            var a = w[f + 6] == 0 && w[f + 7] == 0 ? 0 : w[f] == 0 && w[f + 7] == 0 ? 1 : w[f] == 0 && w[f + 1] == 0 ? 2 : -1;
            if (a >= 0) {
                var g = f + a;
                warpTranslate8Six(mid, src, origin + a, srcStride, w[g], w[g + 1], w[g + 2], w[g + 3], w[g + 4],
                        w[g + 5], (1 << sh) >> 1, sh);
            } else {
                warpTranslate8(mid, src, origin, srcStride, w[f], w[f + 1], w[f + 2], w[f + 3], w[f + 4], w[f + 5],
                        w[f + 6], w[f + 7], (1 << sh) >> 1, sh);
            }
        } else {
            widePlain(src, origin, 1, srcStride, mid, 0, 8, 8, 15, w[f], w[f + 1], w[f + 2], w[f + 3], w[f + 4],
                    w[f + 5], w[f + 6], w[f + 7], (1 << sh) >> 1, sh, 0);
        }
    }

    /**
     * Filters 15 rows of 8-bit samples horizontally in 16-bit lanes, one filter for every output.
     *
     * <p>The products and their sum wrap modulo 2^16. The filter sums to 128, so the sum of
     * {@code sample * tap} is the exact sum of {@code (sample - 128) * tap}, which fits a lane,
     * plus 16384; subtracting 16384 in the lane therefore recovers the exact value, and the
     * rounding shift distributes over the added constant because the shift divides 16384.
     *
     * @param mid the destination, 15 rows of 8 values
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param srcStride the source row stride
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     * @param rnd the rounding constant
     * @param sh the shift, at most 14
     */
    private static void warpTranslate8(short[] mid, short[] src, int origin, int srcStride, int t0, int t1, int t2,
                                       int t3, int t4, int t5, int t6, int t7, int rnd, int sh) {
        var c0 = ShortVector.broadcast(S128, (short) t0);
        var c1 = ShortVector.broadcast(S128, (short) t1);
        var c2 = ShortVector.broadcast(S128, (short) t2);
        var c3 = ShortVector.broadcast(S128, (short) t3);
        var c4 = ShortVector.broadcast(S128, (short) t4);
        var c5 = ShortVector.broadcast(S128, (short) t5);
        var c6 = ShortVector.broadcast(S128, (short) t6);
        var c7 = ShortVector.broadcast(S128, (short) t7);
        var round = ShortVector.broadcast(S128, (short) (rnd - 16384));
        var bias = ShortVector.broadcast(S128, (short) (16384 >> sh));
        for (var y = 0; y < 15; y++) {
            var p = origin + y * srcStride;
            var sum = SHORT128.cast(ShortVector.fromArray(S128, src, p))
                    .lanewise(VectorOperators.MUL, SHORT128.cast(c0))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 1))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c1)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 2))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c2)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 3))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c3)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 4))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c4)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 5))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c5)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 6))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c6)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 7))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c7)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(round));
            SHORT128.cast(SHORT128.cast(sum).lanewise(VectorOperators.ASHR, sh)
                            .lanewise(VectorOperators.ADD, SHORT128.cast(bias)))
                    .intoArray(mid, y * 8);
        }
    }

    /**
     * Filters 15 rows of 8-bit samples horizontally in 16-bit lanes, one filter of six taps for every output.
     *
     * <p>The products and their sum wrap modulo 2^16. The filter sums to 128, so the sum of
     * {@code sample * tap} is the exact sum of {@code (sample - 128) * tap}, which fits a lane,
     * plus 16384; subtracting 16384 in the lane therefore recovers the exact value, and the
     * rounding shift distributes over the added constant because the shift divides 16384.
     *
     * @param mid the destination, 15 rows of 8 values
     * @param src the source
     * @param origin the index of the source sample under the first tap of the first output
     * @param srcStride the source row stride
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param rnd the rounding constant
     * @param sh the shift, at most 14
     */
    private static void warpTranslate8Six(short[] mid, short[] src, int origin, int srcStride, int t0, int t1, int t2,
                                          int t3, int t4, int t5, int rnd, int sh) {
        var c0 = ShortVector.broadcast(S128, (short) t0);
        var c1 = ShortVector.broadcast(S128, (short) t1);
        var c2 = ShortVector.broadcast(S128, (short) t2);
        var c3 = ShortVector.broadcast(S128, (short) t3);
        var c4 = ShortVector.broadcast(S128, (short) t4);
        var c5 = ShortVector.broadcast(S128, (short) t5);
        var round = ShortVector.broadcast(S128, (short) (rnd - 16384));
        var bias = ShortVector.broadcast(S128, (short) (16384 >> sh));
        for (var y = 0; y < 15; y++) {
            var p = origin + y * srcStride;
            var sum = SHORT128.cast(ShortVector.fromArray(S128, src, p))
                    .lanewise(VectorOperators.MUL, SHORT128.cast(c0))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 1))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c1)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 2))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c2)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 3))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c3)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 4))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c4)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 5))
                            .lanewise(VectorOperators.MUL, SHORT128.cast(c5)))
                    .lanewise(VectorOperators.ADD, SHORT128.cast(round));
            SHORT128.cast(SHORT128.cast(sum).lanewise(VectorOperators.ASHR, sh)
                            .lanewise(VectorOperators.ADD, SHORT128.cast(bias)))
                    .intoArray(mid, y * 8);
        }
    }


    /**
     * Filters the 15 rows of an affine warp horizontally into the scratch buffer, gathering the
     * packed taps of each output's filter from {@link #WARP_TAPS} and unpacking them with shifts.
     *
     * @param mid the scratch buffer, receiving 15 rows of 8 values
     * @param src the reference plane
     * @param origin the index of the reference sample 3 rows above and 3 columns left of the
     * block's integer position
     * @param srcStride the reference row stride
     * @param alpha the horizontal filter step per column
     * @param beta the horizontal filter step per row
     * @param mx the horizontal filter position of the top-left sample
     * @param rnd the rounding constant
     * @param sh the shift
     * @param indices the index scratch buffer
     */
    private static void warpRows(short[] mid, short[] src, int origin, int srcStride, int alpha, int beta, int mx,
                                 int rnd, int sh, int[] indices) {
        var steps = IOTA.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, alpha));
        var center = IntVector.broadcast(I256, 64);
        var round = IntVector.broadcast(I256, rnd);
        for (var y = 0; y < 15; y++) {
            var position = INT256.cast(steps).lanewise(VectorOperators.ADD,
                    IntVector.broadcast(I256, mx + y * beta + 512));
            INT256.cast(INT256.cast(position).lanewise(VectorOperators.ASHR, 10)
                    .lanewise(VectorOperators.ADD, INT256.cast(center)))
                    .lanewise(VectorOperators.LSHL, 1).intoArray(indices, 0);
            var p = origin + y * srcStride;
            var s0 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var first = INT256.cast(IntVector.fromArray(I256, WARP_TAPS, 0, indices, 0));
            var second = INT256.cast(IntVector.fromArray(I256, WARP_TAPS, 1, indices, 0));
            var s1 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 1))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s2 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 2))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s3 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 3))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s4 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 4))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s5 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 5))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s6 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 6))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s7 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 7))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var f0 = INT256.cast(first.lanewise(VectorOperators.LSHL, 24)).lanewise(VectorOperators.ASHR, 24);
            var f1 = INT256.cast(first.lanewise(VectorOperators.LSHL, 16)).lanewise(VectorOperators.ASHR, 24);
            var f2 = INT256.cast(first.lanewise(VectorOperators.LSHL, 8)).lanewise(VectorOperators.ASHR, 24);
            var f3 = first.lanewise(VectorOperators.ASHR, 24);
            var f4 = INT256.cast(second.lanewise(VectorOperators.LSHL, 24)).lanewise(VectorOperators.ASHR, 24);
            var f5 = INT256.cast(second.lanewise(VectorOperators.LSHL, 16)).lanewise(VectorOperators.ASHR, 24);
            var f6 = INT256.cast(second.lanewise(VectorOperators.LSHL, 8)).lanewise(VectorOperators.ASHR, 24);
            var f7 = second.lanewise(VectorOperators.ASHR, 24);
            var sum = s0.lanewise(VectorOperators.MUL, f0)
                    .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, f1))
                    .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, f2))
                    .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, f3))
                    .lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, f4))
                    .lanewise(VectorOperators.ADD, s5.lanewise(VectorOperators.MUL, f5))
                    .lanewise(VectorOperators.ADD, s6.lanewise(VectorOperators.MUL, f6))
                    .lanewise(VectorOperators.ADD, s7.lanewise(VectorOperators.MUL, f7))
                    .lanewise(VectorOperators.ADD, INT256.cast(round));
            SHORT128.cast(INT256.cast(sum).lanewise(VectorOperators.ASHR, sh)
                            .convertShape(VectorOperators.I2S, S128, 0))
                    .intoArray(mid, y * 8);
        }
    }

    /**
     * Filters the rows of an affine warp vertically from the scratch buffer, gathering the packed
     * taps of each output's filter from {@link #WARP_TAPS} and unpacking them with shifts:
     * {@code clamp(((sum + rnd) >> sh) - sub, lo, hi)}.
     *
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param mid the scratch buffer filled by {@link #warpRows}
     * @param gamma the vertical filter step per column
     * @param delta the vertical filter step per row
     * @param my the vertical filter position of the top-left sample
     * @param rnd the rounding constant
     * @param sh the shift
     * @param sub the value subtracted after the shift
     * @param lo the lower clamp
     * @param hi the upper clamp
     * @param indices the index scratch buffer
     */
    private static void warpColumns(short[] dst, int dstOff, int dstStride, short[] mid, int gamma, int delta, int my,
                                    int rnd, int sh, int sub, int lo, int hi, int[] indices) {
        var steps = IOTA.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, gamma));
        var center = IntVector.broadcast(I256, 64);
        var round = IntVector.broadcast(I256, rnd);
        var minus = IntVector.broadcast(I256, sub);
        var low = IntVector.broadcast(I256, lo);
        var high = IntVector.broadcast(I256, hi);
        for (var y = 0; y < 8; y++) {
            var position = INT256.cast(steps).lanewise(VectorOperators.ADD,
                    IntVector.broadcast(I256, my + y * delta + 512));
            INT256.cast(INT256.cast(position).lanewise(VectorOperators.ASHR, 10)
                    .lanewise(VectorOperators.ADD, INT256.cast(center)))
                    .lanewise(VectorOperators.LSHL, 1).intoArray(indices, 0);
            var p = y * 8;
            var s0 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, mid, p))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var first = INT256.cast(IntVector.fromArray(I256, WARP_TAPS, 0, indices, 0));
            var second = INT256.cast(IntVector.fromArray(I256, WARP_TAPS, 1, indices, 0));
            var s1 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, mid, p + 8))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s2 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, mid, p + 16))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s3 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, mid, p + 24))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s4 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, mid, p + 32))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s5 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, mid, p + 40))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s6 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, mid, p + 48))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s7 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, mid, p + 56))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var f0 = INT256.cast(first.lanewise(VectorOperators.LSHL, 24)).lanewise(VectorOperators.ASHR, 24);
            var f1 = INT256.cast(first.lanewise(VectorOperators.LSHL, 16)).lanewise(VectorOperators.ASHR, 24);
            var f2 = INT256.cast(first.lanewise(VectorOperators.LSHL, 8)).lanewise(VectorOperators.ASHR, 24);
            var f3 = first.lanewise(VectorOperators.ASHR, 24);
            var f4 = INT256.cast(second.lanewise(VectorOperators.LSHL, 24)).lanewise(VectorOperators.ASHR, 24);
            var f5 = INT256.cast(second.lanewise(VectorOperators.LSHL, 16)).lanewise(VectorOperators.ASHR, 24);
            var f6 = INT256.cast(second.lanewise(VectorOperators.LSHL, 8)).lanewise(VectorOperators.ASHR, 24);
            var f7 = second.lanewise(VectorOperators.ASHR, 24);
            var sum = s0.lanewise(VectorOperators.MUL, f0)
                    .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, f1))
                    .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, f2))
                    .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, f3))
                    .lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, f4))
                    .lanewise(VectorOperators.ADD, s5.lanewise(VectorOperators.MUL, f5))
                    .lanewise(VectorOperators.ADD, s6.lanewise(VectorOperators.MUL, f6))
                    .lanewise(VectorOperators.ADD, s7.lanewise(VectorOperators.MUL, f7))
                    .lanewise(VectorOperators.ADD, INT256.cast(round));
            var value = INT256.cast(sum).lanewise(VectorOperators.ASHR, sh)
                    .lanewise(VectorOperators.SUB, INT256.cast(minus))
                    .lanewise(VectorOperators.MAX, INT256.cast(low)).lanewise(VectorOperators.MIN, INT256.cast(high));
            SHORT128.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, dstOff + y * dstStride);
        }
    }

    @Override
    public void emuEdge(int bw, int bh, int iw, int ih, int x, int y, short[] dst, int dstOff, int dstStride,
                        short[] ref, int refOff, int refStride) {
        scalar.emuEdge(bw, bh, iw, ih, x, y, dst, dstOff, dstStride, ref, refOff, refStride);
    }

    @Override
    public void resize(short[] dst, int dstOff, int dstStride, short[] src, int srcOff, int srcStride, int dstW,
                       int h, int srcW, int dx, int mx0, int bitdepthMax) {
        scalar.resize(dst, dstOff, dstStride, src, srcOff, srcStride, dstW, h, srcW, dx, mx0, bitdepthMax);
    }
}
