package com.github.auties00.smalt.video.vvc.internal;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link ScalarVvcDsp} with the Vector API, inheriting the scalar kernels for the shapes it does not
 * vectorize; the output equals {@link ScalarVvcDsp} value for value.
 *
 * <p>Every kernel computes in 32-bit lanes, 8 columns per vector, with a 4-lane vector for a remaining group of 4
 * columns: the filters, the averaging and the transform passes over block widths that are multiples of 4, and the
 * optical flow gradients over widths that are multiples of 8 (its blocks are 8 or 16 samples wide). Narrower
 * blocks, and the 2-tap filters, run the scalar kernels.
 *
 * @implNote This implementation follows the kernel rules of
 * {@link com.github.auties00.smalt.internal.simd.Simd Simd}, and uses 256-bit vectors, so the decoder selects it
 * only where {@code Simd.WIDE} holds.
 */
final class VectorVvcDsp extends ScalarVvcDsp {
    /**
     * Eight 32-bit lanes.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * Four 32-bit lanes.
     */
    private static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;

    /**
     * Eight 16-bit lanes.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * Four 16-bit lanes.
     */
    private static final VectorSpecies<Short> S64 = ShortVector.SPECIES_64;

    /**
     * The exact class of the vectors of {@link #I256}.
     */
    private static final Class<IntVector> INT256 = vectorClass(I256);

    /**
     * The exact class of the vectors of {@link #I128}.
     */
    private static final Class<IntVector> INT128 = vectorClass(I128);

    /**
     * The exact class of the vectors of {@link #S128}.
     */
    private static final Class<ShortVector> SHORT128 = vectorClass(S128);

    /**
     * The exact class of the vectors of {@link #S64}.
     */
    private static final Class<ShortVector> SHORT64 = vectorClass(S64);

    /**
     * Constructs the kernels.
     */
    private VectorVvcDsp() {
    }

    /**
     * {@return new kernels}
     */
    static ScalarVvcDsp create() {
        return new VectorVvcDsp();
    }

    /**
     * Returns the exact vector class of a species.
     *
     * @param species a vector species
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class of the species' vectors
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    void filterRows(int[] src, int srcOff, int ss, int[] dst, int dstOff, int ds, int w, int h, int[] c, int taps,
                    int shift) {
        if (taps == 2 || (w & 3) != 0) {
            super.filterRows(src, srcOff, ss, dst, dstOff, ds, w, h, c, taps, shift);
            return;
        }
        filter(src, srcOff, 1, ss, dst, dstOff, ds, w, h, c, taps, shift, -OFFS);
    }

    @Override
    void filterCols(int[] src, int srcOff, int ss, int[] dst, int dstOff, int ds, int w, int h, int[] c, int taps,
                    int shift, int bias) {
        if (taps == 2 || (w & 3) != 0) {
            super.filterCols(src, srcOff, ss, dst, dstOff, ds, w, h, c, taps, shift, bias);
            return;
        }
        filter(src, srcOff, ss, ss, dst, dstOff, ds, w, h, c, taps, shift, bias);
    }

    /**
     * Applies an 8- or 4-tap filter to a block whose width is a multiple of 4:
     * {@code dst = (sum(c[k] * src[p + k * step]) >> shift) + bias}.
     *
     * @param src the input samples
     * @param srcOff the index of the input sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param ss the row stride of the input
     * @param dst receives the filtered samples
     * @param dstOff the index of the first output sample
     * @param ds the row stride of the output
     * @param w the block width, a multiple of 4
     * @param h the block height
     * @param c the filter coefficients
     * @param taps the number of filter taps: 8 or 4
     * @param shift the output shift
     * @param bias the value added after the shift
     */
    private static void filter(int[] src, int srcOff, int step, int ss, int[] dst, int dstOff, int ds, int w, int h,
                               int[] c, int taps, int shift, int bias) {
        var wide = w & ~7;
        if (taps == 8) {
            if (wide > 0) {
                taps8(src, srcOff, step, ss, dst, dstOff, ds, wide, h, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7],
                        shift, bias);
            }
            if ((w & 4) != 0) {
                taps8x4(src, srcOff + wide, step, ss, dst, dstOff + wide, ds, h, c[0], c[1], c[2], c[3], c[4], c[5],
                        c[6], c[7], shift, bias);
            }
        } else {
            if (wide > 0) {
                taps4(src, srcOff, step, ss, dst, dstOff, ds, wide, h, c[0], c[1], c[2], c[3], shift, bias);
            }
            if ((w & 4) != 0) {
                taps4x4(src, srcOff + wide, step, ss, dst, dstOff + wide, ds, h, c[0], c[1], c[2], c[3], shift, bias);
            }
        }
    }

    /**
     * Applies an 8-tap filter to columns that are a multiple of 8, 8 per vector.
     *
     * @param src the input samples
     * @param srcOff the index of the input sample under the first tap of the first output
     * @param step the distance between taps
     * @param ss the row stride of the input
     * @param dst receives the filtered samples
     * @param dstOff the index of the first output sample
     * @param ds the row stride of the output
     * @param w the number of columns, a multiple of 8
     * @param h the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     * @param shift the output shift
     * @param bias the value added after the shift
     */
    private static void taps8(int[] src, int srcOff, int step, int ss, int[] dst, int dstOff, int ds, int w, int h,
                              int t0, int t1, int t2, int t3, int t4, int t5, int t6, int t7, int shift, int bias) {
        var c0 = INT256.cast(IntVector.broadcast(I256, t0));
        var c1 = INT256.cast(IntVector.broadcast(I256, t1));
        var c2 = INT256.cast(IntVector.broadcast(I256, t2));
        var c3 = INT256.cast(IntVector.broadcast(I256, t3));
        var c4 = INT256.cast(IntVector.broadcast(I256, t4));
        var c5 = INT256.cast(IntVector.broadcast(I256, t5));
        var c6 = INT256.cast(IntVector.broadcast(I256, t6));
        var c7 = INT256.cast(IntVector.broadcast(I256, t7));
        var b = INT256.cast(IntVector.broadcast(I256, bias));
        for (var j = 0; j < h; j++) {
            var row = srcOff + j * ss;
            var out = dstOff + j * ds;
            for (var i = 0; i < w; i += 8) {
                var p = row + i;
                var sum = INT256.cast(IntVector.fromArray(I256, src, p)).lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, src, p + step))
                                .lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, src, p + 2 * step))
                                .lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, src, p + 3 * step))
                                .lanewise(VectorOperators.MUL, c3))
                        .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, src, p + 4 * step))
                                .lanewise(VectorOperators.MUL, c4))
                        .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, src, p + 5 * step))
                                .lanewise(VectorOperators.MUL, c5))
                        .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, src, p + 6 * step))
                                .lanewise(VectorOperators.MUL, c6))
                        .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, src, p + 7 * step))
                                .lanewise(VectorOperators.MUL, c7));
                INT256.cast(INT256.cast(sum).lanewise(VectorOperators.ASHR, shift)).lanewise(VectorOperators.ADD, b)
                        .intoArray(dst, out + i);
            }
        }
    }

    /**
     * Applies an 8-tap filter to one group of 4 columns.
     *
     * @param src the input samples
     * @param srcOff the index of the input sample under the first tap of the first output
     * @param step the distance between taps
     * @param ss the row stride of the input
     * @param dst receives the filtered samples
     * @param dstOff the index of the first output sample
     * @param ds the row stride of the output
     * @param h the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     * @param shift the output shift
     * @param bias the value added after the shift
     */
    private static void taps8x4(int[] src, int srcOff, int step, int ss, int[] dst, int dstOff, int ds, int h, int t0,
                                int t1, int t2, int t3, int t4, int t5, int t6, int t7, int shift, int bias) {
        var c0 = INT128.cast(IntVector.broadcast(I128, t0));
        var c1 = INT128.cast(IntVector.broadcast(I128, t1));
        var c2 = INT128.cast(IntVector.broadcast(I128, t2));
        var c3 = INT128.cast(IntVector.broadcast(I128, t3));
        var c4 = INT128.cast(IntVector.broadcast(I128, t4));
        var c5 = INT128.cast(IntVector.broadcast(I128, t5));
        var c6 = INT128.cast(IntVector.broadcast(I128, t6));
        var c7 = INT128.cast(IntVector.broadcast(I128, t7));
        var b = INT128.cast(IntVector.broadcast(I128, bias));
        for (var j = 0; j < h; j++) {
            var p = srcOff + j * ss;
            var sum = INT128.cast(IntVector.fromArray(I128, src, p)).lanewise(VectorOperators.MUL, c0)
                    .lanewise(VectorOperators.ADD, INT128.cast(IntVector.fromArray(I128, src, p + step))
                            .lanewise(VectorOperators.MUL, c1))
                    .lanewise(VectorOperators.ADD, INT128.cast(IntVector.fromArray(I128, src, p + 2 * step))
                            .lanewise(VectorOperators.MUL, c2))
                    .lanewise(VectorOperators.ADD, INT128.cast(IntVector.fromArray(I128, src, p + 3 * step))
                            .lanewise(VectorOperators.MUL, c3))
                    .lanewise(VectorOperators.ADD, INT128.cast(IntVector.fromArray(I128, src, p + 4 * step))
                            .lanewise(VectorOperators.MUL, c4))
                    .lanewise(VectorOperators.ADD, INT128.cast(IntVector.fromArray(I128, src, p + 5 * step))
                            .lanewise(VectorOperators.MUL, c5))
                    .lanewise(VectorOperators.ADD, INT128.cast(IntVector.fromArray(I128, src, p + 6 * step))
                            .lanewise(VectorOperators.MUL, c6))
                    .lanewise(VectorOperators.ADD, INT128.cast(IntVector.fromArray(I128, src, p + 7 * step))
                            .lanewise(VectorOperators.MUL, c7));
            INT128.cast(INT128.cast(sum).lanewise(VectorOperators.ASHR, shift)).lanewise(VectorOperators.ADD, b)
                    .intoArray(dst, dstOff + j * ds);
        }
    }

    /**
     * Applies a 4-tap filter to columns that are a multiple of 8, 8 per vector.
     *
     * @param src the input samples
     * @param srcOff the index of the input sample under the first tap of the first output
     * @param step the distance between taps
     * @param ss the row stride of the input
     * @param dst receives the filtered samples
     * @param dstOff the index of the first output sample
     * @param ds the row stride of the output
     * @param w the number of columns, a multiple of 8
     * @param h the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param shift the output shift
     * @param bias the value added after the shift
     */
    private static void taps4(int[] src, int srcOff, int step, int ss, int[] dst, int dstOff, int ds, int w, int h,
                              int t0, int t1, int t2, int t3, int shift, int bias) {
        var c0 = INT256.cast(IntVector.broadcast(I256, t0));
        var c1 = INT256.cast(IntVector.broadcast(I256, t1));
        var c2 = INT256.cast(IntVector.broadcast(I256, t2));
        var c3 = INT256.cast(IntVector.broadcast(I256, t3));
        var b = INT256.cast(IntVector.broadcast(I256, bias));
        for (var j = 0; j < h; j++) {
            var row = srcOff + j * ss;
            var out = dstOff + j * ds;
            for (var i = 0; i < w; i += 8) {
                var p = row + i;
                var sum = INT256.cast(IntVector.fromArray(I256, src, p)).lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, src, p + step))
                                .lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, src, p + 2 * step))
                                .lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, src, p + 3 * step))
                                .lanewise(VectorOperators.MUL, c3));
                INT256.cast(INT256.cast(sum).lanewise(VectorOperators.ASHR, shift)).lanewise(VectorOperators.ADD, b)
                        .intoArray(dst, out + i);
            }
        }
    }

    /**
     * Applies a 4-tap filter to one group of 4 columns.
     *
     * @param src the input samples
     * @param srcOff the index of the input sample under the first tap of the first output
     * @param step the distance between taps
     * @param ss the row stride of the input
     * @param dst receives the filtered samples
     * @param dstOff the index of the first output sample
     * @param ds the row stride of the output
     * @param h the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param shift the output shift
     * @param bias the value added after the shift
     */
    private static void taps4x4(int[] src, int srcOff, int step, int ss, int[] dst, int dstOff, int ds, int h, int t0,
                                int t1, int t2, int t3, int shift, int bias) {
        var c0 = INT128.cast(IntVector.broadcast(I128, t0));
        var c1 = INT128.cast(IntVector.broadcast(I128, t1));
        var c2 = INT128.cast(IntVector.broadcast(I128, t2));
        var c3 = INT128.cast(IntVector.broadcast(I128, t3));
        var b = INT128.cast(IntVector.broadcast(I128, bias));
        for (var j = 0; j < h; j++) {
            var p = srcOff + j * ss;
            var sum = INT128.cast(IntVector.fromArray(I128, src, p)).lanewise(VectorOperators.MUL, c0)
                    .lanewise(VectorOperators.ADD, INT128.cast(IntVector.fromArray(I128, src, p + step))
                            .lanewise(VectorOperators.MUL, c1))
                    .lanewise(VectorOperators.ADD, INT128.cast(IntVector.fromArray(I128, src, p + 2 * step))
                            .lanewise(VectorOperators.MUL, c2))
                    .lanewise(VectorOperators.ADD, INT128.cast(IntVector.fromArray(I128, src, p + 3 * step))
                            .lanewise(VectorOperators.MUL, c3));
            INT128.cast(INT128.cast(sum).lanewise(VectorOperators.ASHR, shift)).lanewise(VectorOperators.ADD, b)
                    .intoArray(dst, dstOff + j * ds);
        }
    }

    @Override
    void average(int[] p0, int[] p1, int off, int bs, short[] plane, int d0, int stride, int w, int h, int w0, int w1,
                 int shift, int offset, int maxVal) {
        if ((w & 3) != 0) {
            super.average(p0, p1, off, bs, plane, d0, stride, w, h, w0, w1, shift, offset, maxVal);
            return;
        }
        var wide = w & ~7;
        if (wide > 0) {
            average8(p0, p1, off, bs, plane, d0, stride, wide, h, w0, w1, shift, offset, maxVal);
        }
        if ((w & 4) != 0) {
            average4(p0, p1, off + wide, bs, plane, d0 + wide, stride, h, w0, w1, shift, offset, maxVal);
        }
    }

    /**
     * Writes the clipped weighted sum of two predictions over columns that are a multiple of 8.
     *
     * @param p0 the list 0 prediction
     * @param p1 the list 1 prediction
     * @param off the index of the block in the predictions
     * @param bs the row stride of the predictions
     * @param plane the output plane
     * @param d0 the index of the block in the output plane
     * @param stride the row stride of the output plane
     * @param w the number of columns, a multiple of 8
     * @param h the number of rows
     * @param w0 the weight of the list 0 prediction
     * @param w1 the weight of the list 1 prediction
     * @param shift the right shift
     * @param offset the rounding offset
     * @param maxVal the largest sample value
     */
    private static void average8(int[] p0, int[] p1, int off, int bs, short[] plane, int d0, int stride, int w, int h,
                                 int w0, int w1, int shift, int offset, int maxVal) {
        var a = INT256.cast(IntVector.broadcast(I256, w0));
        var b = INT256.cast(IntVector.broadcast(I256, w1));
        var round = INT256.cast(IntVector.broadcast(I256, offset));
        var zero = INT256.cast(IntVector.zero(I256));
        var max = INT256.cast(IntVector.broadcast(I256, maxVal));
        for (var j = 0; j < h; j++) {
            var s = off + j * bs;
            var d = d0 + j * stride;
            for (var i = 0; i < w; i += 8) {
                var sum = INT256.cast(IntVector.fromArray(I256, p0, s + i)).lanewise(VectorOperators.MUL, a)
                        .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, p1, s + i))
                                .lanewise(VectorOperators.MUL, b))
                        .lanewise(VectorOperators.ADD, round);
                var clipped = INT256.cast(INT256.cast(INT256.cast(sum).lanewise(VectorOperators.ASHR, shift))
                        .lanewise(VectorOperators.MAX, zero)).lanewise(VectorOperators.MIN, max);
                SHORT128.cast(INT256.cast(clipped).convertShape(VectorOperators.I2S, S128, 0)).intoArray(plane, d + i);
            }
        }
    }

    /**
     * Writes the clipped weighted sum of two predictions over one group of 4 columns.
     *
     * @param p0 the list 0 prediction
     * @param p1 the list 1 prediction
     * @param off the index of the columns in the predictions
     * @param bs the row stride of the predictions
     * @param plane the output plane
     * @param d0 the index of the columns in the output plane
     * @param stride the row stride of the output plane
     * @param h the number of rows
     * @param w0 the weight of the list 0 prediction
     * @param w1 the weight of the list 1 prediction
     * @param shift the right shift
     * @param offset the rounding offset
     * @param maxVal the largest sample value
     */
    private static void average4(int[] p0, int[] p1, int off, int bs, short[] plane, int d0, int stride, int h, int w0,
                                 int w1, int shift, int offset, int maxVal) {
        var a = INT128.cast(IntVector.broadcast(I128, w0));
        var b = INT128.cast(IntVector.broadcast(I128, w1));
        var round = INT128.cast(IntVector.broadcast(I128, offset));
        var zero = INT128.cast(IntVector.zero(I128));
        var max = INT128.cast(IntVector.broadcast(I128, maxVal));
        for (var j = 0; j < h; j++) {
            var s = off + j * bs;
            var sum = INT128.cast(IntVector.fromArray(I128, p0, s)).lanewise(VectorOperators.MUL, a)
                    .lanewise(VectorOperators.ADD, INT128.cast(IntVector.fromArray(I128, p1, s))
                            .lanewise(VectorOperators.MUL, b))
                    .lanewise(VectorOperators.ADD, round);
            var clipped = INT128.cast(INT128.cast(INT128.cast(sum).lanewise(VectorOperators.ASHR, shift))
                    .lanewise(VectorOperators.MAX, zero)).lanewise(VectorOperators.MIN, max);
            SHORT64.cast(INT128.cast(clipped).convertShape(VectorOperators.I2S, S64, 0))
                    .intoArray(plane, d0 + j * stride);
        }
    }

    @Override
    void single(int[] p, int off, int bs, short[] plane, int d0, int stride, int w, int h, int shift, int offset,
                int maxVal) {
        if ((w & 3) != 0) {
            super.single(p, off, bs, plane, d0, stride, w, h, shift, offset, maxVal);
            return;
        }
        var wide = w & ~7;
        if (wide > 0) {
            single8(p, off, bs, plane, d0, stride, wide, h, shift, offset, maxVal);
        }
        if ((w & 4) != 0) {
            single4(p, off + wide, bs, plane, d0 + wide, stride, h, shift, offset, maxVal);
        }
    }

    /**
     * Writes the clipped samples of one prediction over columns that are a multiple of 8.
     *
     * @param p the prediction
     * @param off the index of the block in the prediction
     * @param bs the row stride of the prediction
     * @param plane the output plane
     * @param d0 the index of the block in the output plane
     * @param stride the row stride of the output plane
     * @param w the number of columns, a multiple of 8
     * @param h the number of rows
     * @param shift the right shift
     * @param offset the rounding offset
     * @param maxVal the largest sample value
     */
    private static void single8(int[] p, int off, int bs, short[] plane, int d0, int stride, int w, int h, int shift,
                                int offset, int maxVal) {
        var round = INT256.cast(IntVector.broadcast(I256, offset));
        var zero = INT256.cast(IntVector.zero(I256));
        var max = INT256.cast(IntVector.broadcast(I256, maxVal));
        for (var j = 0; j < h; j++) {
            var s = off + j * bs;
            var d = d0 + j * stride;
            for (var i = 0; i < w; i += 8) {
                var sum = INT256.cast(IntVector.fromArray(I256, p, s + i)).lanewise(VectorOperators.ADD, round);
                var clipped = INT256.cast(INT256.cast(INT256.cast(sum).lanewise(VectorOperators.ASHR, shift))
                        .lanewise(VectorOperators.MAX, zero)).lanewise(VectorOperators.MIN, max);
                SHORT128.cast(INT256.cast(clipped).convertShape(VectorOperators.I2S, S128, 0)).intoArray(plane, d + i);
            }
        }
    }

    /**
     * Writes the clipped samples of one prediction over one group of 4 columns.
     *
     * @param p the prediction
     * @param off the index of the columns in the prediction
     * @param bs the row stride of the prediction
     * @param plane the output plane
     * @param d0 the index of the columns in the output plane
     * @param stride the row stride of the output plane
     * @param h the number of rows
     * @param shift the right shift
     * @param offset the rounding offset
     * @param maxVal the largest sample value
     */
    private static void single4(int[] p, int off, int bs, short[] plane, int d0, int stride, int h, int shift,
                                int offset, int maxVal) {
        var round = INT128.cast(IntVector.broadcast(I128, offset));
        var zero = INT128.cast(IntVector.zero(I128));
        var max = INT128.cast(IntVector.broadcast(I128, maxVal));
        for (var j = 0; j < h; j++) {
            var sum = INT128.cast(IntVector.fromArray(I128, p, off + j * bs)).lanewise(VectorOperators.ADD, round);
            var clipped = INT128.cast(INT128.cast(INT128.cast(sum).lanewise(VectorOperators.ASHR, shift))
                    .lanewise(VectorOperators.MAX, zero)).lanewise(VectorOperators.MIN, max);
            SHORT64.cast(INT128.cast(clipped).convertShape(VectorOperators.I2S, S64, 0))
                    .intoArray(plane, d0 + j * stride);
        }
    }

    @Override
    void gradients(int[] src, int[] gx, int[] gy, int es, int w, int h) {
        if ((w & 7) != 0) {
            super.gradients(src, gx, gy, es, w, h);
            return;
        }
        for (var j = 1; j <= h; j++) {
            var row = j * es + 1;
            for (var i = 0; i < w; i += 8) {
                var p = row + i;
                var below = INT256.cast(IntVector.fromArray(I256, src, p + es)).lanewise(VectorOperators.ASHR, 6);
                var above = INT256.cast(IntVector.fromArray(I256, src, p - es)).lanewise(VectorOperators.ASHR, 6);
                INT256.cast(below).lanewise(VectorOperators.SUB, above).intoArray(gy, p);
                var right = INT256.cast(IntVector.fromArray(I256, src, p + 1)).lanewise(VectorOperators.ASHR, 6);
                var left = INT256.cast(IntVector.fromArray(I256, src, p - 1)).lanewise(VectorOperators.ASHR, 6);
                INT256.cast(right).lanewise(VectorOperators.SUB, left).intoArray(gx, p);
            }
        }
    }

    @Override
    void bdofSamples(int[] s0, int[] s1, int[] gx0, int[] gx1, int[] gy0, int[] gy1, int p, int es, short[] plane,
                     int d0, int stride, int tx, int ty, int shift, int offset, int maxVal) {
        var vx = INT128.cast(IntVector.broadcast(I128, tx));
        var vy = INT128.cast(IntVector.broadcast(I128, ty));
        var round = INT128.cast(IntVector.broadcast(I128, offset));
        var zero = INT128.cast(IntVector.zero(I128));
        var max = INT128.cast(IntVector.broadcast(I128, maxVal));
        for (var j = 0; j < 4; j++) {
            var q = p + j * es;
            var dx = INT128.cast(IntVector.fromArray(I128, gx0, q))
                    .lanewise(VectorOperators.SUB, INT128.cast(IntVector.fromArray(I128, gx1, q)));
            var dy = INT128.cast(IntVector.fromArray(I128, gy0, q))
                    .lanewise(VectorOperators.SUB, INT128.cast(IntVector.fromArray(I128, gy1, q)));
            var sum = INT128.cast(IntVector.fromArray(I128, s0, q))
                    .lanewise(VectorOperators.ADD, INT128.cast(IntVector.fromArray(I128, s1, q)))
                    .lanewise(VectorOperators.ADD, INT128.cast(dx).lanewise(VectorOperators.MUL, vx))
                    .lanewise(VectorOperators.ADD, INT128.cast(dy).lanewise(VectorOperators.MUL, vy))
                    .lanewise(VectorOperators.ADD, round);
            // the 16-bit truncation the scalar kernel applies before clipping
            var truncated = INT128.cast(INT128.cast(INT128.cast(sum).lanewise(VectorOperators.ASHR, shift))
                    .lanewise(VectorOperators.LSHL, 16)).lanewise(VectorOperators.ASHR, 16);
            var clipped = INT128.cast(INT128.cast(truncated).lanewise(VectorOperators.MAX, zero))
                    .lanewise(VectorOperators.MIN, max);
            SHORT64.cast(INT128.cast(clipped).convertShape(VectorOperators.I2S, S64, 0))
                    .intoArray(plane, d0 + j * stride);
        }
    }

    @Override
    void inverseVertical(int[] d, int w, int h, int nonZeroW, int nonZeroH, int[] m, int rowStride, int[] e) {
        if ((nonZeroW & 3) != 0) {
            super.inverseVertical(d, w, h, nonZeroW, nonZeroH, m, rowStride, e);
            return;
        }
        var wide = nonZeroW & ~7;
        if (wide > 0) {
            vertical8(d, w, h, wide, nonZeroH, m, rowStride, e);
        }
        if ((nonZeroW & 4) != 0) {
            vertical4(d, wide, w, h, nonZeroH, m, rowStride, e);
        }
    }

    /**
     * Applies the vertical pass of the inverse transform to the first columns, a multiple of 8.
     *
     * @param d the scaled coefficients, {@code w} per row
     * @param w the block width
     * @param h the block height
     * @param columns the number of columns, a multiple of 8
     * @param nonZeroH the number of leading rows that may hold nonzero coefficients
     * @param m the transform matrix
     * @param rowStride the distance between basis functions in {@code m}
     * @param e receives the intermediate values, {@code w} per row
     */
    private static void vertical8(int[] d, int w, int h, int columns, int nonZeroH, int[] m, int rowStride, int[] e) {
        var round = INT256.cast(IntVector.broadcast(I256, 64));
        var low = INT256.cast(IntVector.broadcast(I256, COEFF_MIN));
        var high = INT256.cast(IntVector.broadcast(I256, COEFF_MAX));
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < columns; x += 8) {
                var sum = INT256.cast(IntVector.zero(I256));
                for (var j = 0; j < nonZeroH; j++) {
                    var c = INT256.cast(IntVector.broadcast(I256, m[j * rowStride + y]));
                    sum = INT256.cast(INT256.cast(sum).lanewise(VectorOperators.ADD,
                            INT256.cast(IntVector.fromArray(I256, d, j * w + x)).lanewise(VectorOperators.MUL, c)));
                }
                var shifted = INT256.cast(INT256.cast(INT256.cast(sum).lanewise(VectorOperators.ADD, round))
                        .lanewise(VectorOperators.ASHR, 7));
                INT256.cast(INT256.cast(shifted.lanewise(VectorOperators.MAX, low)).lanewise(VectorOperators.MIN, high))
                        .intoArray(e, y * w + x);
            }
        }
    }

    /**
     * Applies the vertical pass of the inverse transform to one group of 4 columns.
     *
     * @param d the scaled coefficients, {@code w} per row
     * @param x the first column of the group
     * @param w the block width
     * @param h the block height
     * @param nonZeroH the number of leading rows that may hold nonzero coefficients
     * @param m the transform matrix
     * @param rowStride the distance between basis functions in {@code m}
     * @param e receives the intermediate values, {@code w} per row
     */
    private static void vertical4(int[] d, int x, int w, int h, int nonZeroH, int[] m, int rowStride, int[] e) {
        var round = INT128.cast(IntVector.broadcast(I128, 64));
        var low = INT128.cast(IntVector.broadcast(I128, COEFF_MIN));
        var high = INT128.cast(IntVector.broadcast(I128, COEFF_MAX));
        for (var y = 0; y < h; y++) {
            var sum = INT128.cast(IntVector.zero(I128));
            for (var j = 0; j < nonZeroH; j++) {
                var c = INT128.cast(IntVector.broadcast(I128, m[j * rowStride + y]));
                sum = INT128.cast(INT128.cast(sum).lanewise(VectorOperators.ADD,
                        INT128.cast(IntVector.fromArray(I128, d, j * w + x)).lanewise(VectorOperators.MUL, c)));
            }
            var shifted = INT128.cast(INT128.cast(INT128.cast(sum).lanewise(VectorOperators.ADD, round))
                    .lanewise(VectorOperators.ASHR, 7));
            INT128.cast(INT128.cast(shifted.lanewise(VectorOperators.MAX, low)).lanewise(VectorOperators.MIN, high))
                    .intoArray(e, y * w + x);
        }
    }

    @Override
    void inverseHorizontal(int[] e, int w, int h, int nonZeroW, int[] m, int rowStride, int[] res, int round,
                           int shift) {
        if ((w & 3) != 0) {
            super.inverseHorizontal(e, w, h, nonZeroW, m, rowStride, res, round, shift);
            return;
        }
        var wide = w & ~7;
        if (wide > 0) {
            horizontal8(e, w, h, wide, nonZeroW, m, rowStride, res, round, shift);
        }
        if ((w & 4) != 0) {
            horizontal4(e, wide, w, h, nonZeroW, m, rowStride, res, round, shift);
        }
    }

    /**
     * Applies the horizontal pass of the inverse transform to the first columns, a multiple of 8.
     *
     * @param e the intermediate values, {@code w} per row
     * @param w the block width
     * @param h the block height
     * @param columns the number of columns, a multiple of 8
     * @param nonZeroW the number of leading columns that may hold nonzero values
     * @param m the transform matrix
     * @param rowStride the distance between basis functions in {@code m}
     * @param res receives the residual samples, {@code w} per row
     * @param round the rounding offset
     * @param shift the right shift
     */
    private static void horizontal8(int[] e, int w, int h, int columns, int nonZeroW, int[] m, int rowStride,
                                    int[] res, int round, int shift) {
        var r = INT256.cast(IntVector.broadcast(I256, round));
        for (var y = 0; y < h; y++) {
            var row = y * w;
            for (var x = 0; x < columns; x += 8) {
                var sum = INT256.cast(IntVector.zero(I256));
                for (var j = 0; j < nonZeroW; j++) {
                    var c = INT256.cast(IntVector.broadcast(I256, e[row + j]));
                    sum = INT256.cast(INT256.cast(sum).lanewise(VectorOperators.ADD,
                            INT256.cast(IntVector.fromArray(I256, m, j * rowStride + x)).lanewise(VectorOperators.MUL, c)));
                }
                INT256.cast(INT256.cast(INT256.cast(sum).lanewise(VectorOperators.ADD, r))
                        .lanewise(VectorOperators.ASHR, shift)).intoArray(res, row + x);
            }
        }
    }

    /**
     * Applies the horizontal pass of the inverse transform to one group of 4 columns.
     *
     * @param e the intermediate values, {@code w} per row
     * @param x the first column of the group
     * @param w the block width
     * @param h the block height
     * @param nonZeroW the number of leading columns that may hold nonzero values
     * @param m the transform matrix
     * @param rowStride the distance between basis functions in {@code m}
     * @param res receives the residual samples, {@code w} per row
     * @param round the rounding offset
     * @param shift the right shift
     */
    private static void horizontal4(int[] e, int x, int w, int h, int nonZeroW, int[] m, int rowStride, int[] res,
                                    int round, int shift) {
        var r = INT128.cast(IntVector.broadcast(I128, round));
        for (var y = 0; y < h; y++) {
            var row = y * w;
            var sum = INT128.cast(IntVector.zero(I128));
            for (var j = 0; j < nonZeroW; j++) {
                var c = INT128.cast(IntVector.broadcast(I128, e[row + j]));
                sum = INT128.cast(INT128.cast(sum).lanewise(VectorOperators.ADD,
                        INT128.cast(IntVector.fromArray(I128, m, j * rowStride + x)).lanewise(VectorOperators.MUL, c)));
            }
            INT128.cast(INT128.cast(INT128.cast(sum).lanewise(VectorOperators.ADD, r))
                    .lanewise(VectorOperators.ASHR, shift)).intoArray(res, row + x);
        }
    }
}
