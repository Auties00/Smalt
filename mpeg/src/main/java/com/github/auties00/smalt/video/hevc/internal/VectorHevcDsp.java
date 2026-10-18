package com.github.auties00.smalt.video.hevc.internal;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Implements {@link HevcDsp} with the Vector API; the output equals {@link ScalarHevcDsp} sample for
 * sample.
 *
 * @implNote This implementation follows the kernel rules of
 * {@link com.github.auties00.smalt.internal.simd.Simd Simd}, and also casts again, at the call, a
 * vector or mask computed before a loop and used inside it as the receiver of a call. Blocks whose
 * width is not a multiple of 4, predictions of odd height and rows narrower than 16 samples use
 * the scalar kernels.
 */
final class VectorHevcDsp extends HevcDsp {
    /*
     * Lane layouts. Blocks whose width is a multiple of 4 are interpolated one separable pass at a
     * time into 16-bit intermediate samples: the columns up to the last multiple of 8 eight at a
     * time, and a remaining group of 4 columns four at a time. A pass over the 8-bit samples of a
     * block whose width is a multiple of 16 filters 16 outputs per vector in 16-bit lanes: every
     * partial sum of a filter over 8-bit samples lies between -6120 and 22440, so it fits a 16-bit
     * lane. The other passes filter in 32-bit lanes and narrow the results; the second pass of a
     * luma block filtered in both directions checks that its results fit 16 bits. The default
     * weighted prediction runs in 16-bit lanes with saturating additions where a sum may leave 16
     * bits (a saturated sum is at least 32767, and every sum of at least 32768 shifted down already
     * exceeds the largest sample, so the clipped result is the same); the explicit weighted
     * prediction runs in 32-bit lanes; both process two rows per iteration. The sample adaptive
     * offsets run 16 samples per vector in 16-bit lanes, a row whose width is not a multiple of 16
     * ending with a vector that overlaps the one before it.
     *
     * The kernels of 8-bit planes load bytes zero-extended into the same 16-bit lanes and narrow
     * their results back to bytes; a group of 4 columns loads 8 bytes, of which the plane's padding
     * covers the 4 past a block at the plane's end, and stores of blocks narrower than 8 columns use
     * the scalar kernels, so that no store crosses the block. The luma deblocking of a horizontal
     * edge of an 8-bit plane filters 16 columns per call, the normal and the strong filter as two
     * kernels that each rewrite the columns of the other unchanged; a vertical edge is filtered 16
     * rows per call by the same kernels after a transposition of the 8 samples across the edge of
     * each row into a scratch buffer, 8 rows at a time on 64-bit words, and the transposition back.
     * The inverse DCT of 8 to 32 points computes its vertical stage 8 columns per vector and its
     * horizontal stage 8 (4 for 8 points) outputs per vector, the even and odd frequency sums kept
     * apart and combined into the first and, reversed, the second half of each row; the 4-point DCT
     * uses the scalar kernel.
     */

    /**
     * The 4-lane short species of 4-sample loads and stores.
     */
    private static final VectorSpecies<Short> S64 = ShortVector.SPECIES_64;

    /**
     * The 8-lane short species of 8-sample loads and stores.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * The 16-lane short species of 16-bit arithmetic.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * The 4-lane int species of the 32-bit sums of 4 columns.
     */
    private static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;

    /**
     * The 8-lane int species of the 32-bit sums of 8 columns.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * The 8-lane byte species of 8-sample loads and stores of 8-bit planes.
     */
    private static final VectorSpecies<Byte> B64 = ByteVector.SPECIES_64;

    /**
     * The 16-lane byte species of 8-bit output samples and of 16-sample loads and stores of 8-bit
     * planes.
     */
    private static final VectorSpecies<Byte> B128 = ByteVector.SPECIES_128;

    /**
     * The exact class of {@link #S64} vectors.
     */
    private static final Class<ShortVector> SHORT64 = vectorClass(S64);

    /**
     * The exact class of {@link #S128} vectors.
     */
    private static final Class<ShortVector> SHORT128 = vectorClass(S128);

    /**
     * The exact class of {@link #S256} vectors.
     */
    private static final Class<ShortVector> SHORT256 = vectorClass(S256);

    /**
     * The exact class of {@link #I128} vectors.
     */
    private static final Class<IntVector> INT128 = vectorClass(I128);

    /**
     * The exact class of {@link #I256} vectors.
     */
    private static final Class<IntVector> INT256 = vectorClass(I256);

    /**
     * The exact class of {@link #B64} vectors.
     */
    private static final Class<ByteVector> BYTE64 = vectorClass(B64);

    /**
     * The exact class of {@link #B128} vectors.
     */
    private static final Class<ByteVector> BYTE128 = vectorClass(B128);

    /**
     * Reads and writes 8 bytes of a {@code byte[]} as one little-endian {@code long}.
     */
    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class,
            ByteOrder.LITTLE_ENDIAN);

    /**
     * Reverses the 8 lanes of an {@link #I256} vector.
     */
    private static final VectorShuffle<Integer> REVERSE8 = VectorShuffle.fromValues(I256, 7, 6, 5, 4, 3, 2, 1, 0);

    /**
     * Reverses the 4 lanes of an {@link #I128} vector.
     */
    private static final VectorShuffle<Integer> REVERSE4 = VectorShuffle.fromValues(I128, 3, 2, 1, 0);

    /**
     * The kernels used for the cases this class does not vectorize.
     */
    private final ScalarHevcDsp scalar;

    /**
     * Constructs the kernels.
     */
    private VectorHevcDsp() {
        this.scalar = new ScalarHevcDsp();
    }

    /**
     * {@return new kernels}
     */
    static HevcDsp create() {
        return new VectorHevcDsp();
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
    public boolean lumaMc(short[] src, int srcOff, int srcStride, short[] dst, int w, int h, int xFrac, int yFrac,
                          int bitDepth, short[] scratch) {
        if ((w & 3) != 0) {
            return scalar.lumaMc(src, srcOff, srcStride, dst, w, h, xFrac, yFrac, bitDepth, scratch);
        }
        var f = LUMA_FILTER;
        if (xFrac == 0 && yFrac == 0) {
            copy(src, srcOff, srcStride, dst, w, h, 14 - bitDepth);
            return true;
        }
        if (yFrac == 0) {
            var t = xFrac * 8;
            taps8(src, srcOff - 3, 1, srcStride, dst, w, h, f[t], f[t + 1], f[t + 2], f[t + 3], f[t + 4], f[t + 5],
                    f[t + 6], f[t + 7], bitDepth);
            return true;
        }
        if (xFrac == 0) {
            var t = yFrac * 8;
            taps8(src, srcOff - 3 * srcStride, srcStride, srcStride, dst, w, h, f[t], f[t + 1], f[t + 2], f[t + 3],
                    f[t + 4], f[t + 5], f[t + 6], f[t + 7], bitDepth);
            return true;
        }
        var t = xFrac * 8;
        taps8(src, srcOff - 3 * srcStride - 3, 1, srcStride, scratch, w, h + 7, f[t], f[t + 1], f[t + 2], f[t + 3],
                f[t + 4], f[t + 5], f[t + 6], f[t + 7], bitDepth);
        var v = yFrac * 8;
        var wide = w & ~7;
        var fits = true;
        if (wide > 0) {
            fits = values8(scratch, 0, w, dst, 0, wide, h, f[v], f[v + 1], f[v + 2], f[v + 3], f[v + 4], f[v + 5],
                    f[v + 6], f[v + 7]);
        }
        if ((w & 4) != 0) {
            fits &= values8x4(scratch, wide, w, dst, wide, h, f[v], f[v + 1], f[v + 2], f[v + 3], f[v + 4], f[v + 5],
                    f[v + 6], f[v + 7]);
        }
        return fits;
    }

    @Override
    public boolean chromaMc(short[] src, int srcOff, int srcStride, short[] dst, int w, int h, int xFrac, int yFrac,
                            int bitDepth, short[] scratch) {
        if ((w & 3) != 0) {
            return scalar.chromaMc(src, srcOff, srcStride, dst, w, h, xFrac, yFrac, bitDepth, scratch);
        }
        var f = CHROMA_FILTER;
        if (xFrac == 0 && yFrac == 0) {
            copy(src, srcOff, srcStride, dst, w, h, 14 - bitDepth);
            return true;
        }
        if (yFrac == 0) {
            var t = xFrac * 4;
            taps4(src, srcOff - 1, 1, srcStride, dst, w, h, f[t], f[t + 1], f[t + 2], f[t + 3], bitDepth);
            return true;
        }
        if (xFrac == 0) {
            var t = yFrac * 4;
            taps4(src, srcOff - srcStride, srcStride, srcStride, dst, w, h, f[t], f[t + 1], f[t + 2], f[t + 3],
                    bitDepth);
            return true;
        }
        var t = xFrac * 4;
        taps4(src, srcOff - srcStride - 1, 1, srcStride, scratch, w, h + 3, f[t], f[t + 1], f[t + 2], f[t + 3],
                bitDepth);
        var v = yFrac * 4;
        var wide = w & ~7;
        if (wide > 0) {
            values4(scratch, 0, w, dst, 0, wide, h, f[v], f[v + 1], f[v + 2], f[v + 3]);
        }
        if ((w & 4) != 0) {
            values4x4(scratch, wide, w, dst, wide, h, f[v], f[v + 1], f[v + 2], f[v + 3]);
        }
        return true;
    }

    /**
     * Scales a block of samples whose width is a multiple of 4 to the 14-bit intermediate
     * precision: 8 columns at a time, then a remaining group of 4.
     *
     * @param src the source plane
     * @param srcOff the index of the block's top-left sample
     * @param srcStride the source row stride
     * @param dst receives the block, {@code w} values per row
     * @param w the block width, a multiple of 4
     * @param h the block height
     * @param shift the left shift, {@code 14 - bitDepth}
     */
    private static void copy(short[] src, int srcOff, int srcStride, short[] dst, int w, int h, int shift) {
        var wide = w & ~7;
        var tail = (w & 4) != 0;
        for (var y = 0; y < h; y++) {
            var row = srcOff + y * srcStride;
            var out = y * w;
            for (var x = 0; x < wide; x += 8) {
                var value = SHORT128.cast(ShortVector.fromArray(S128, src, row + x));
                SHORT128.cast(value.lanewise(VectorOperators.LSHL, shift)).intoArray(dst, out + x);
            }
            if (tail) {
                var value = SHORT64.cast(ShortVector.fromArray(S64, src, row + wide));
                SHORT64.cast(value.lanewise(VectorOperators.LSHL, shift)).intoArray(dst, out + wide);
            }
        }
    }

    /**
     * Applies an 8-tap filter to a block of samples whose width is a multiple of 4:
     * {@code dst = sum(t[k] * src[origin + k * step]) >> (bitDepth - 8)}. Filters over 8-bit samples
     * run in 16-bit lanes, 16 columns per vector when the columns up to the last multiple of 8 are a
     * multiple of 16 and 8 otherwise; deeper samples run in 32-bit lanes, 8 columns per vector. A
     * remaining group of 4 columns uses 4-lane vectors.
     *
     * @param src the source samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination, {@code w} values per row
     * @param w the number of outputs per row, a multiple of 4
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     * @param bitDepth the sample bit depth, from 8 to 12
     */
    private static void taps8(short[] src, int origin, int step, int srcStride, short[] dst, int w, int rows, int t0,
                              int t1, int t2, int t3, int t4, int t5, int t6, int t7, int bitDepth) {
        var wide = w & ~7;
        if (bitDepth == 8) {
            if ((wide & 15) == 0) {
                if (wide > 0) {
                    narrow8(src, origin, step, srcStride, dst, w, wide, rows, t0, t1, t2, t3, t4, t5, t6, t7);
                }
            } else {
                narrow8x8(src, origin, step, srcStride, dst, w, wide, rows, t0, t1, t2, t3, t4, t5, t6, t7);
            }
            if ((w & 4) != 0) {
                narrow8x4(src, origin + wide, step, srcStride, dst, wide, w, rows, t0, t1, t2, t3, t4, t5, t6, t7);
            }
            return;
        }
        if (wide > 0) {
            samples8(src, origin, step, srcStride, dst, w, wide, rows, t0, t1, t2, t3, t4, t5, t6, t7, bitDepth - 8);
        }
        if ((w & 4) != 0) {
            samples8x4(src, origin + wide, step, srcStride, dst, wide, w, rows, t0, t1, t2, t3, t4, t5, t6, t7,
                    bitDepth - 8);
        }
    }

    /**
     * Applies a 4-tap filter to a block of samples whose width is a multiple of 4:
     * {@code dst = sum(t[k] * src[origin + k * step]) >> (bitDepth - 8)}, choosing the lanes as
     * {@link #taps8} does.
     *
     * @param src the source samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination, {@code w} values per row
     * @param w the number of outputs per row, a multiple of 4
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param bitDepth the sample bit depth, from 8 to 12
     */
    private static void taps4(short[] src, int origin, int step, int srcStride, short[] dst, int w, int rows, int t0,
                              int t1, int t2, int t3, int bitDepth) {
        var wide = w & ~7;
        if (bitDepth == 8) {
            if ((wide & 15) == 0) {
                if (wide > 0) {
                    narrow4(src, origin, step, srcStride, dst, w, wide, rows, t0, t1, t2, t3);
                }
            } else {
                narrow4x8(src, origin, step, srcStride, dst, w, wide, rows, t0, t1, t2, t3);
            }
            if ((w & 4) != 0) {
                narrow4x4(src, origin + wide, step, srcStride, dst, wide, w, rows, t0, t1, t2, t3);
            }
            return;
        }
        if (wide > 0) {
            samples4(src, origin, step, srcStride, dst, w, wide, rows, t0, t1, t2, t3, bitDepth - 8);
        }
        if ((w & 4) != 0) {
            samples4x4(src, origin + wide, step, srcStride, dst, wide, w, rows, t0, t1, t2, t3, bitDepth - 8);
        }
    }

    /**
     * Applies a 8-tap filter to a block of samples, 8 outputs at a time in 32-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step]) >> shift}, narrowed to 16 bits.
     *
     * @param src the source samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 8, from the first column
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     * @param shift the right shift, {@code bitDepth - 8}
     */
    private static void samples8(short[] src, int origin, int step, int srcStride, short[] dst, int dstStride, int w,
                                 int rows, int t0, int t1, int t2, int t3, int t4, int t5, int t6, int t7, int shift) {
        var c0 = INT256.cast(IntVector.broadcast(I256, t0));
        var c1 = INT256.cast(IntVector.broadcast(I256, t1));
        var c2 = INT256.cast(IntVector.broadcast(I256, t2));
        var c3 = INT256.cast(IntVector.broadcast(I256, t3));
        var c4 = INT256.cast(IntVector.broadcast(I256, t4));
        var c5 = INT256.cast(IntVector.broadcast(I256, t5));
        var c6 = INT256.cast(IntVector.broadcast(I256, t6));
        var c7 = INT256.cast(IntVector.broadcast(I256, t7));
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = y * dstStride;
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
                var sum = s0.lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, c3))
                        .lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, c4))
                        .lanewise(VectorOperators.ADD, s5.lanewise(VectorOperators.MUL, c5))
                        .lanewise(VectorOperators.ADD, s6.lanewise(VectorOperators.MUL, c6))
                        .lanewise(VectorOperators.ADD, s7.lanewise(VectorOperators.MUL, c7));
                var shifted = INT256.cast(INT256.cast(sum).lanewise(VectorOperators.ASHR, shift));
                SHORT128.cast(shifted.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, out + x);
            }
        }
    }

    /**
     * Applies a 8-tap filter to a column group of 4 samples, in 32-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step]) >> shift}, narrowed to 16 bits.
     *
     * @param src the source samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output of the first row
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
     * @param shift the right shift, {@code bitDepth - 8}
     */
    private static void samples8x4(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                                   int dstStride, int rows, int t0, int t1, int t2, int t3, int t4, int t5, int t6,
                                   int t7, int shift) {
        var c0 = INT128.cast(IntVector.broadcast(I128, t0));
        var c1 = INT128.cast(IntVector.broadcast(I128, t1));
        var c2 = INT128.cast(IntVector.broadcast(I128, t2));
        var c3 = INT128.cast(IntVector.broadcast(I128, t3));
        var c4 = INT128.cast(IntVector.broadcast(I128, t4));
        var c5 = INT128.cast(IntVector.broadcast(I128, t5));
        var c6 = INT128.cast(IntVector.broadcast(I128, t6));
        var c7 = INT128.cast(IntVector.broadcast(I128, t7));
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = dstOff + y * dstStride;
            {
                var p = row;
                var s0 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s1 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + step))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s2 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 2 * step))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s3 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 3 * step))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s4 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 4 * step))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s5 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 5 * step))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s6 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 6 * step))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s7 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 7 * step))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var sum = s0.lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, c3))
                        .lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, c4))
                        .lanewise(VectorOperators.ADD, s5.lanewise(VectorOperators.MUL, c5))
                        .lanewise(VectorOperators.ADD, s6.lanewise(VectorOperators.MUL, c6))
                        .lanewise(VectorOperators.ADD, s7.lanewise(VectorOperators.MUL, c7));
                var shifted = INT128.cast(INT128.cast(sum).lanewise(VectorOperators.ASHR, shift));
                SHORT64.cast(shifted.convertShape(VectorOperators.I2S, S64, 0)).intoArray(dst, out);
            }
        }
    }

    /**
     * Applies a 8-tap filter to a block of 8-bit samples, 16 outputs at a time in 16-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step])}. Every sum of a filter over 8-bit samples fits
     * a 16-bit lane.
     *
     * @param src the source samples, from 0 to 255
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 16, from the first column
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     */
    private static void narrow8(short[] src, int origin, int step, int srcStride, short[] dst, int dstStride, int w,
                                int rows, int t0, int t1, int t2, int t3, int t4, int t5, int t6, int t7) {
        var c0 = SHORT256.cast(ShortVector.broadcast(S256, (short) t0));
        var c1 = SHORT256.cast(ShortVector.broadcast(S256, (short) t1));
        var c2 = SHORT256.cast(ShortVector.broadcast(S256, (short) t2));
        var c3 = SHORT256.cast(ShortVector.broadcast(S256, (short) t3));
        var c4 = SHORT256.cast(ShortVector.broadcast(S256, (short) t4));
        var c5 = SHORT256.cast(ShortVector.broadcast(S256, (short) t5));
        var c6 = SHORT256.cast(ShortVector.broadcast(S256, (short) t6));
        var c7 = SHORT256.cast(ShortVector.broadcast(S256, (short) t7));
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = y * dstStride;
            for (var x = 0; x < w; x += 16) {
                var p = row + x;
                var sum = SHORT256.cast(ShortVector.fromArray(S256, src, p)).lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + step))
                                .lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 2 * step))
                                .lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 3 * step))
                                .lanewise(VectorOperators.MUL, c3))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 4 * step))
                                .lanewise(VectorOperators.MUL, c4))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 5 * step))
                                .lanewise(VectorOperators.MUL, c5))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 6 * step))
                                .lanewise(VectorOperators.MUL, c6))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 7 * step))
                                .lanewise(VectorOperators.MUL, c7));
                SHORT256.cast(sum).intoArray(dst, out + x);
            }
        }
    }

    /**
     * Applies a 8-tap filter to a block of 8-bit samples, 8 outputs at a time in 16-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step])}. Every sum of a filter over 8-bit samples fits
     * a 16-bit lane.
     *
     * @param src the source samples, from 0 to 255
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 8, from the first column
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     */
    private static void narrow8x8(short[] src, int origin, int step, int srcStride, short[] dst, int dstStride, int w,
                                  int rows, int t0, int t1, int t2, int t3, int t4, int t5, int t6, int t7) {
        var c0 = SHORT128.cast(ShortVector.broadcast(S128, (short) t0));
        var c1 = SHORT128.cast(ShortVector.broadcast(S128, (short) t1));
        var c2 = SHORT128.cast(ShortVector.broadcast(S128, (short) t2));
        var c3 = SHORT128.cast(ShortVector.broadcast(S128, (short) t3));
        var c4 = SHORT128.cast(ShortVector.broadcast(S128, (short) t4));
        var c5 = SHORT128.cast(ShortVector.broadcast(S128, (short) t5));
        var c6 = SHORT128.cast(ShortVector.broadcast(S128, (short) t6));
        var c7 = SHORT128.cast(ShortVector.broadcast(S128, (short) t7));
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = y * dstStride;
            for (var x = 0; x < w; x += 8) {
                var p = row + x;
                var sum = SHORT128.cast(ShortVector.fromArray(S128, src, p)).lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + step))
                                .lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 2 * step))
                                .lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 3 * step))
                                .lanewise(VectorOperators.MUL, c3))
                        .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 4 * step))
                                .lanewise(VectorOperators.MUL, c4))
                        .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 5 * step))
                                .lanewise(VectorOperators.MUL, c5))
                        .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 6 * step))
                                .lanewise(VectorOperators.MUL, c6))
                        .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 7 * step))
                                .lanewise(VectorOperators.MUL, c7));
                SHORT128.cast(sum).intoArray(dst, out + x);
            }
        }
    }

    /**
     * Applies a 8-tap filter to a column group of 4 8-bit samples in 16-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step])}. Every sum of a filter over 8-bit samples fits
     * a 16-bit lane.
     *
     * @param src the source samples, from 0 to 255
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output of the first row
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
     */
    private static void narrow8x4(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                                  int dstStride, int rows, int t0, int t1, int t2, int t3, int t4, int t5, int t6,
                                  int t7) {
        var c0 = SHORT64.cast(ShortVector.broadcast(S64, (short) t0));
        var c1 = SHORT64.cast(ShortVector.broadcast(S64, (short) t1));
        var c2 = SHORT64.cast(ShortVector.broadcast(S64, (short) t2));
        var c3 = SHORT64.cast(ShortVector.broadcast(S64, (short) t3));
        var c4 = SHORT64.cast(ShortVector.broadcast(S64, (short) t4));
        var c5 = SHORT64.cast(ShortVector.broadcast(S64, (short) t5));
        var c6 = SHORT64.cast(ShortVector.broadcast(S64, (short) t6));
        var c7 = SHORT64.cast(ShortVector.broadcast(S64, (short) t7));
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = dstOff + y * dstStride;
            {
                var p = row;
                var sum = SHORT64.cast(ShortVector.fromArray(S64, src, p)).lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, SHORT64.cast(ShortVector.fromArray(S64, src, p + step))
                                .lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, SHORT64.cast(ShortVector.fromArray(S64, src, p + 2 * step))
                                .lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, SHORT64.cast(ShortVector.fromArray(S64, src, p + 3 * step))
                                .lanewise(VectorOperators.MUL, c3))
                        .lanewise(VectorOperators.ADD, SHORT64.cast(ShortVector.fromArray(S64, src, p + 4 * step))
                                .lanewise(VectorOperators.MUL, c4))
                        .lanewise(VectorOperators.ADD, SHORT64.cast(ShortVector.fromArray(S64, src, p + 5 * step))
                                .lanewise(VectorOperators.MUL, c5))
                        .lanewise(VectorOperators.ADD, SHORT64.cast(ShortVector.fromArray(S64, src, p + 6 * step))
                                .lanewise(VectorOperators.MUL, c6))
                        .lanewise(VectorOperators.ADD, SHORT64.cast(ShortVector.fromArray(S64, src, p + 7 * step))
                                .lanewise(VectorOperators.MUL, c7));
                SHORT64.cast(sum).intoArray(dst, out);
            }
        }
    }

    /**
     * Applies a 8-tap filter vertically to a block of 16-bit intermediate values, 8 outputs at a
     * time in 32-bit lanes: {@code dst = sum(t[k] * src[k * stride]) >> 6}, narrowed to 16
     * bits; the source has 7 rows of margin.
     *
     * @param src the intermediate values, {@code stride} per row
     * @param srcOff the index of the first value of the first row
     * @param stride the row stride of {@code src} and {@code dst}
     * @param dst the destination
     * @param dstOff the index of the first output of the first row
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
     * @return {@code true} when every output fits 16 bits
     */
    private static boolean values8(short[] src, int srcOff, int stride, short[] dst, int dstOff, int w, int rows,
                                   int t0, int t1, int t2, int t3, int t4, int t5, int t6, int t7) {
        var c0 = INT256.cast(IntVector.broadcast(I256, t0));
        var c1 = INT256.cast(IntVector.broadcast(I256, t1));
        var c2 = INT256.cast(IntVector.broadcast(I256, t2));
        var c3 = INT256.cast(IntVector.broadcast(I256, t3));
        var c4 = INT256.cast(IntVector.broadcast(I256, t4));
        var c5 = INT256.cast(IntVector.broadcast(I256, t5));
        var c6 = INT256.cast(IntVector.broadcast(I256, t6));
        var c7 = INT256.cast(IntVector.broadcast(I256, t7));
        var high = INT256.cast(IntVector.broadcast(I256, Short.MAX_VALUE));
        var fits = true;
        for (var y = 0; y < rows; y++) {
            var row = srcOff + y * stride;
            var out = dstOff + y * stride;
            for (var x = 0; x < w; x += 8) {
                var p = row + x;
                var s0 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s1 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + stride))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s2 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 2 * stride))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s3 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 3 * stride))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s4 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 4 * stride))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s5 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 5 * stride))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s6 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 6 * stride))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s7 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 7 * stride))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var sum = s0.lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, c3))
                        .lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, c4))
                        .lanewise(VectorOperators.ADD, s5.lanewise(VectorOperators.MUL, c5))
                        .lanewise(VectorOperators.ADD, s6.lanewise(VectorOperators.MUL, c6))
                        .lanewise(VectorOperators.ADD, s7.lanewise(VectorOperators.MUL, c7));
                var shifted = INT256.cast(INT256.cast(sum).lanewise(VectorOperators.ASHR, 6));
                // The outputs are at least -16830, so only the upper bound can be exceeded.
                fits &= !shifted.compare(VectorOperators.GT, high).anyTrue();
                SHORT128.cast(shifted.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, out + x);
            }
        }
        return fits;
    }

    /**
     * Applies a 8-tap filter vertically to a column group of 4 intermediate values, in 32-bit
     * lanes: {@code dst = sum(t[k] * src[k * stride]) >> 6}, narrowed to 16
     * bits; the source has 7 rows of margin.
     *
     * @param src the intermediate values, {@code stride} per row
     * @param srcOff the index of the first value of the first row
     * @param stride the row stride of {@code src} and {@code dst}
     * @param dst the destination
     * @param dstOff the index of the first output of the first row
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     * @return {@code true} when every output fits 16 bits
     */
    private static boolean values8x4(short[] src, int srcOff, int stride, short[] dst, int dstOff, int rows, int t0,
                                     int t1, int t2, int t3, int t4, int t5, int t6, int t7) {
        var c0 = INT128.cast(IntVector.broadcast(I128, t0));
        var c1 = INT128.cast(IntVector.broadcast(I128, t1));
        var c2 = INT128.cast(IntVector.broadcast(I128, t2));
        var c3 = INT128.cast(IntVector.broadcast(I128, t3));
        var c4 = INT128.cast(IntVector.broadcast(I128, t4));
        var c5 = INT128.cast(IntVector.broadcast(I128, t5));
        var c6 = INT128.cast(IntVector.broadcast(I128, t6));
        var c7 = INT128.cast(IntVector.broadcast(I128, t7));
        var high = INT128.cast(IntVector.broadcast(I128, Short.MAX_VALUE));
        var fits = true;
        for (var y = 0; y < rows; y++) {
            var row = srcOff + y * stride;
            var out = dstOff + y * stride;
            {
                var p = row;
                var s0 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s1 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + stride))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s2 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 2 * stride))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s3 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 3 * stride))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s4 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 4 * stride))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s5 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 5 * stride))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s6 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 6 * stride))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s7 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 7 * stride))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var sum = s0.lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, c3))
                        .lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, c4))
                        .lanewise(VectorOperators.ADD, s5.lanewise(VectorOperators.MUL, c5))
                        .lanewise(VectorOperators.ADD, s6.lanewise(VectorOperators.MUL, c6))
                        .lanewise(VectorOperators.ADD, s7.lanewise(VectorOperators.MUL, c7));
                var shifted = INT128.cast(INT128.cast(sum).lanewise(VectorOperators.ASHR, 6));
                // The outputs are at least -16830, so only the upper bound can be exceeded.
                fits &= !shifted.compare(VectorOperators.GT, high).anyTrue();
                SHORT64.cast(shifted.convertShape(VectorOperators.I2S, S64, 0)).intoArray(dst, out);
            }
        }
        return fits;
    }

    /**
     * Applies a 4-tap filter to a block of samples, 8 outputs at a time in 32-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step]) >> shift}, narrowed to 16 bits.
     *
     * @param src the source samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 8, from the first column
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param shift the right shift, {@code bitDepth - 8}
     */
    private static void samples4(short[] src, int origin, int step, int srcStride, short[] dst, int dstStride, int w,
                                 int rows, int t0, int t1, int t2, int t3, int shift) {
        var c0 = INT256.cast(IntVector.broadcast(I256, t0));
        var c1 = INT256.cast(IntVector.broadcast(I256, t1));
        var c2 = INT256.cast(IntVector.broadcast(I256, t2));
        var c3 = INT256.cast(IntVector.broadcast(I256, t3));
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = y * dstStride;
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
                var sum = s0.lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, c3));
                var shifted = INT256.cast(INT256.cast(sum).lanewise(VectorOperators.ASHR, shift));
                SHORT128.cast(shifted.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, out + x);
            }
        }
    }

    /**
     * Applies a 4-tap filter to a column group of 4 samples, in 32-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step]) >> shift}, narrowed to 16 bits.
     *
     * @param src the source samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output of the first row
     * @param dstStride the destination row stride
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param shift the right shift, {@code bitDepth - 8}
     */
    private static void samples4x4(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                                   int dstStride, int rows, int t0, int t1, int t2, int t3, int shift) {
        var c0 = INT128.cast(IntVector.broadcast(I128, t0));
        var c1 = INT128.cast(IntVector.broadcast(I128, t1));
        var c2 = INT128.cast(IntVector.broadcast(I128, t2));
        var c3 = INT128.cast(IntVector.broadcast(I128, t3));
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = dstOff + y * dstStride;
            {
                var p = row;
                var s0 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s1 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + step))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s2 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 2 * step))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s3 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 3 * step))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var sum = s0.lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, c3));
                var shifted = INT128.cast(INT128.cast(sum).lanewise(VectorOperators.ASHR, shift));
                SHORT64.cast(shifted.convertShape(VectorOperators.I2S, S64, 0)).intoArray(dst, out);
            }
        }
    }

    /**
     * Applies a 4-tap filter to a block of 8-bit samples, 16 outputs at a time in 16-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step])}. Every sum of a filter over 8-bit samples fits
     * a 16-bit lane.
     *
     * @param src the source samples, from 0 to 255
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 16, from the first column
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     */
    private static void narrow4(short[] src, int origin, int step, int srcStride, short[] dst, int dstStride, int w,
                                int rows, int t0, int t1, int t2, int t3) {
        var c0 = SHORT256.cast(ShortVector.broadcast(S256, (short) t0));
        var c1 = SHORT256.cast(ShortVector.broadcast(S256, (short) t1));
        var c2 = SHORT256.cast(ShortVector.broadcast(S256, (short) t2));
        var c3 = SHORT256.cast(ShortVector.broadcast(S256, (short) t3));
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = y * dstStride;
            for (var x = 0; x < w; x += 16) {
                var p = row + x;
                var sum = SHORT256.cast(ShortVector.fromArray(S256, src, p)).lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + step))
                                .lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 2 * step))
                                .lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(ShortVector.fromArray(S256, src, p + 3 * step))
                                .lanewise(VectorOperators.MUL, c3));
                SHORT256.cast(sum).intoArray(dst, out + x);
            }
        }
    }

    /**
     * Applies a 4-tap filter to a block of 8-bit samples, 8 outputs at a time in 16-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step])}. Every sum of a filter over 8-bit samples fits
     * a 16-bit lane.
     *
     * @param src the source samples, from 0 to 255
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 8, from the first column
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     */
    private static void narrow4x8(short[] src, int origin, int step, int srcStride, short[] dst, int dstStride, int w,
                                  int rows, int t0, int t1, int t2, int t3) {
        var c0 = SHORT128.cast(ShortVector.broadcast(S128, (short) t0));
        var c1 = SHORT128.cast(ShortVector.broadcast(S128, (short) t1));
        var c2 = SHORT128.cast(ShortVector.broadcast(S128, (short) t2));
        var c3 = SHORT128.cast(ShortVector.broadcast(S128, (short) t3));
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = y * dstStride;
            for (var x = 0; x < w; x += 8) {
                var p = row + x;
                var sum = SHORT128.cast(ShortVector.fromArray(S128, src, p)).lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + step))
                                .lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 2 * step))
                                .lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, SHORT128.cast(ShortVector.fromArray(S128, src, p + 3 * step))
                                .lanewise(VectorOperators.MUL, c3));
                SHORT128.cast(sum).intoArray(dst, out + x);
            }
        }
    }

    /**
     * Applies a 4-tap filter to a column group of 4 8-bit samples in 16-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step])}. Every sum of a filter over 8-bit samples fits
     * a 16-bit lane.
     *
     * @param src the source samples, from 0 to 255
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output of the first row
     * @param dstStride the destination row stride
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     */
    private static void narrow4x4(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                                  int dstStride, int rows, int t0, int t1, int t2, int t3) {
        var c0 = SHORT64.cast(ShortVector.broadcast(S64, (short) t0));
        var c1 = SHORT64.cast(ShortVector.broadcast(S64, (short) t1));
        var c2 = SHORT64.cast(ShortVector.broadcast(S64, (short) t2));
        var c3 = SHORT64.cast(ShortVector.broadcast(S64, (short) t3));
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = dstOff + y * dstStride;
            {
                var p = row;
                var sum = SHORT64.cast(ShortVector.fromArray(S64, src, p)).lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, SHORT64.cast(ShortVector.fromArray(S64, src, p + step))
                                .lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, SHORT64.cast(ShortVector.fromArray(S64, src, p + 2 * step))
                                .lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, SHORT64.cast(ShortVector.fromArray(S64, src, p + 3 * step))
                                .lanewise(VectorOperators.MUL, c3));
                SHORT64.cast(sum).intoArray(dst, out);
            }
        }
    }

    /**
     * Applies a 4-tap filter vertically to a block of 16-bit intermediate values, 8 outputs at a
     * time in 32-bit lanes: {@code dst = sum(t[k] * src[k * stride]) >> 6}, narrowed to 16
     * bits; the source has 3 rows of margin.
     *
     * @param src the intermediate values, {@code stride} per row
     * @param srcOff the index of the first value of the first row
     * @param stride the row stride of {@code src} and {@code dst}
     * @param dst the destination
     * @param dstOff the index of the first output of the first row
     * @param w the number of outputs per row, a multiple of 8
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @return {@code true}: the chroma filters keep every output within 16 bits
     */
    private static boolean values4(short[] src, int srcOff, int stride, short[] dst, int dstOff, int w, int rows,
                                   int t0, int t1, int t2, int t3) {
        var c0 = INT256.cast(IntVector.broadcast(I256, t0));
        var c1 = INT256.cast(IntVector.broadcast(I256, t1));
        var c2 = INT256.cast(IntVector.broadcast(I256, t2));
        var c3 = INT256.cast(IntVector.broadcast(I256, t3));
        for (var y = 0; y < rows; y++) {
            var row = srcOff + y * stride;
            var out = dstOff + y * stride;
            for (var x = 0; x < w; x += 8) {
                var p = row + x;
                var s0 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s1 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + stride))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s2 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 2 * stride))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var s3 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, p + 3 * stride))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var sum = s0.lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, c3));
                var shifted = INT256.cast(INT256.cast(sum).lanewise(VectorOperators.ASHR, 6));
                SHORT128.cast(shifted.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, out + x);
            }
        }
        return true;
    }

    /**
     * Applies a 4-tap filter vertically to a column group of 4 intermediate values, in 32-bit
     * lanes: {@code dst = sum(t[k] * src[k * stride]) >> 6}, narrowed to 16
     * bits; the source has 3 rows of margin.
     *
     * @param src the intermediate values, {@code stride} per row
     * @param srcOff the index of the first value of the first row
     * @param stride the row stride of {@code src} and {@code dst}
     * @param dst the destination
     * @param dstOff the index of the first output of the first row
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @return {@code true}: the chroma filters keep every output within 16 bits
     */
    private static boolean values4x4(short[] src, int srcOff, int stride, short[] dst, int dstOff, int rows, int t0,
                                     int t1, int t2, int t3) {
        var c0 = INT128.cast(IntVector.broadcast(I128, t0));
        var c1 = INT128.cast(IntVector.broadcast(I128, t1));
        var c2 = INT128.cast(IntVector.broadcast(I128, t2));
        var c3 = INT128.cast(IntVector.broadcast(I128, t3));
        for (var y = 0; y < rows; y++) {
            var row = srcOff + y * stride;
            var out = dstOff + y * stride;
            {
                var p = row;
                var s0 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s1 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + stride))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s2 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 2 * stride))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var s3 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, src, p + 3 * stride))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var sum = s0.lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, c3));
                var shifted = INT128.cast(INT128.cast(sum).lanewise(VectorOperators.ASHR, 6));
                SHORT64.cast(shifted.convertShape(VectorOperators.I2S, S64, 0)).intoArray(dst, out);
            }
        }
        return true;
    }

    @Override
    public void putUni(short[] dst, int dstOff, int dstStride, short[] pred, int w, int h, int bitDepth) {
        if ((h & 1) != 0 || (w & 3) != 0) {
            scalar.putUni(dst, dstOff, dstStride, pred, w, h, bitDepth);
            return;
        }
        var wide = w & ~7;
        if ((wide & 15) == 0) {
            if (wide > 0) {
                putUni16(dst, dstOff + 0, dstStride, pred, 0, w, wide, h, bitDepth);
            }
        } else {
            putUni8(dst, dstOff + 0, dstStride, pred, 0, w, wide, h, bitDepth);
        }
        if ((w & 4) != 0) {
            putUni4(dst, dstOff + wide, dstStride, pred, wide, w, 4, h, bitDepth);
        }
    }

    /**
     * Writes 16 columns at a time of a uni-directional prediction:
     * {@code clip((pred + offset1) >> shift1)} with a saturating addition.
     *
     * @param dst the destination plane
     * @param dstOff the index of the top-left sample of the columns
     * @param dstStride the plane's row stride
     * @param pred the prediction
     * @param predOff the index of the first prediction value of the columns
     * @param predStride the row stride of the prediction
     * @param w the number of columns, a multiple of 16
     * @param h the block height, even
     * @param bitDepth the bit depth, from 8 to 12
     */
    private static void putUni16(short[] dst, int dstOff, int dstStride, short[] pred, int predOff, int predStride,
                                 int w, int h, int bitDepth) {
        var shift = 14 - bitDepth;
        var offset = SHORT256.cast(ShortVector.broadcast(S256, (short) (1 << (shift - 1))));
        var zero = SHORT256.cast(ShortVector.zero(S256));
        var max = SHORT256.cast(ShortVector.broadcast(S256, (short) ((1 << bitDepth) - 1)));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = predOff + y * predStride;
            for (var x = 0; x < w; x += 16) {
                var a = SHORT256.cast(ShortVector.fromArray(S256, pred, in + x))
                        .lanewise(VectorOperators.SADD, offset);
                var b = SHORT256.cast(ShortVector.fromArray(S256, pred, in + predStride + x))
                        .lanewise(VectorOperators.SADD, offset);
                SHORT256.cast(SHORT256.cast(a).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max)).intoArray(dst, row + x);
                SHORT256.cast(SHORT256.cast(b).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    /**
     * Writes 8 columns at a time of a uni-directional prediction:
     * {@code clip((pred + offset1) >> shift1)} with a saturating addition.
     *
     * @param dst the destination plane
     * @param dstOff the index of the top-left sample of the columns
     * @param dstStride the plane's row stride
     * @param pred the prediction
     * @param predOff the index of the first prediction value of the columns
     * @param predStride the row stride of the prediction
     * @param w the number of columns, a multiple of 8
     * @param h the block height, even
     * @param bitDepth the bit depth, from 8 to 12
     */
    private static void putUni8(short[] dst, int dstOff, int dstStride, short[] pred, int predOff, int predStride,
                                int w, int h, int bitDepth) {
        var shift = 14 - bitDepth;
        var offset = SHORT128.cast(ShortVector.broadcast(S128, (short) (1 << (shift - 1))));
        var zero = SHORT128.cast(ShortVector.zero(S128));
        var max = SHORT128.cast(ShortVector.broadcast(S128, (short) ((1 << bitDepth) - 1)));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = predOff + y * predStride;
            for (var x = 0; x < w; x += 8) {
                var a = SHORT128.cast(ShortVector.fromArray(S128, pred, in + x))
                        .lanewise(VectorOperators.SADD, offset);
                var b = SHORT128.cast(ShortVector.fromArray(S128, pred, in + predStride + x))
                        .lanewise(VectorOperators.SADD, offset);
                SHORT128.cast(SHORT128.cast(a).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max)).intoArray(dst, row + x);
                SHORT128.cast(SHORT128.cast(b).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    /**
     * Writes 4 columns at a time of a uni-directional prediction:
     * {@code clip((pred + offset1) >> shift1)} with a saturating addition.
     *
     * @param dst the destination plane
     * @param dstOff the index of the top-left sample of the columns
     * @param dstStride the plane's row stride
     * @param pred the prediction
     * @param predOff the index of the first prediction value of the columns
     * @param predStride the row stride of the prediction
     * @param w the number of columns, 4
     * @param h the block height, even
     * @param bitDepth the bit depth, from 8 to 12
     */
    private static void putUni4(short[] dst, int dstOff, int dstStride, short[] pred, int predOff, int predStride,
                                int w, int h, int bitDepth) {
        var shift = 14 - bitDepth;
        var offset = SHORT64.cast(ShortVector.broadcast(S64, (short) (1 << (shift - 1))));
        var zero = SHORT64.cast(ShortVector.zero(S64));
        var max = SHORT64.cast(ShortVector.broadcast(S64, (short) ((1 << bitDepth) - 1)));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = predOff + y * predStride;
            for (var x = 0; x < w; x += 4) {
                var a = SHORT64.cast(ShortVector.fromArray(S64, pred, in + x))
                        .lanewise(VectorOperators.SADD, offset);
                var b = SHORT64.cast(ShortVector.fromArray(S64, pred, in + predStride + x))
                        .lanewise(VectorOperators.SADD, offset);
                SHORT64.cast(SHORT64.cast(a).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max)).intoArray(dst, row + x);
                SHORT64.cast(SHORT64.cast(b).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    @Override
    public void putBi(short[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int w, int h,
                      int bitDepth) {
        if ((h & 1) != 0 || (w & 3) != 0) {
            scalar.putBi(dst, dstOff, dstStride, pred0, pred1, w, h, bitDepth);
            return;
        }
        var wide = w & ~7;
        if ((wide & 15) == 0) {
            if (wide > 0) {
                putBi16(dst, dstOff + 0, dstStride, pred0, pred1, 0, w, wide, h, bitDepth);
            }
        } else {
            putBi8(dst, dstOff + 0, dstStride, pred0, pred1, 0, w, wide, h, bitDepth);
        }
        if ((w & 4) != 0) {
            putBi4(dst, dstOff + wide, dstStride, pred0, pred1, wide, w, 4, h, bitDepth);
        }
    }

    /**
     * Writes 16 columns at a time of a bi-directional prediction. The sum
     * {@code pred0 + pred1} may leave 16 bits, so the kernel uses
     * {@code (pred0 + pred1 + offset2) >> shift2 == (h + (offset2 >> 1)) >> (shift2 - 1)} with the
     * halved sum {@code h = (pred0 >> 1) + (pred1 >> 1) + (pred0 & pred1 & 1)}, which fits 16 bits,
     * and a saturating addition.
     *
     * @param dst the destination plane
     * @param dstOff the index of the top-left sample of the columns
     * @param dstStride the plane's row stride
     * @param pred0 the list 0 prediction
     * @param pred1 the list 1 prediction
     * @param predOff the index of the first prediction value of the columns
     * @param predStride the row stride of the predictions
     * @param w the number of columns, a multiple of 16
     * @param h the block height, even
     * @param bitDepth the bit depth, from 8 to 12
     */
    private static void putBi16(short[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int predOff,
                                int predStride, int w, int h, int bitDepth) {
        var shift = 14 - bitDepth;
        var offset = SHORT256.cast(ShortVector.broadcast(S256, (short) (1 << (shift - 1))));
        var one = SHORT256.cast(ShortVector.broadcast(S256, (short) 1));
        var zero = SHORT256.cast(ShortVector.zero(S256));
        var max = SHORT256.cast(ShortVector.broadcast(S256, (short) ((1 << bitDepth) - 1)));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = predOff + y * predStride;
            for (var x = 0; x < w; x += 16) {
                var a0 = SHORT256.cast(ShortVector.fromArray(S256, pred0, in + x));
                var a1 = SHORT256.cast(ShortVector.fromArray(S256, pred1, in + x));
                var a = SHORT256.cast(a0.lanewise(VectorOperators.ASHR, 1))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(a1.lanewise(VectorOperators.ASHR, 1)))
                        .lanewise(VectorOperators.ADD, a0.lanewise(VectorOperators.AND, a1)
                                .lanewise(VectorOperators.AND, one))
                        .lanewise(VectorOperators.SADD, offset);
                var b0 = SHORT256.cast(ShortVector.fromArray(S256, pred0, in + predStride + x));
                var b1 = SHORT256.cast(ShortVector.fromArray(S256, pred1, in + predStride + x));
                var b = SHORT256.cast(b0.lanewise(VectorOperators.ASHR, 1))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(b1.lanewise(VectorOperators.ASHR, 1)))
                        .lanewise(VectorOperators.ADD, b0.lanewise(VectorOperators.AND, b1)
                                .lanewise(VectorOperators.AND, one))
                        .lanewise(VectorOperators.SADD, offset);
                SHORT256.cast(SHORT256.cast(a).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max)).intoArray(dst, row + x);
                SHORT256.cast(SHORT256.cast(b).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    /**
     * Writes 8 columns at a time of a bi-directional prediction. The sum
     * {@code pred0 + pred1} may leave 16 bits, so the kernel uses
     * {@code (pred0 + pred1 + offset2) >> shift2 == (h + (offset2 >> 1)) >> (shift2 - 1)} with the
     * halved sum {@code h = (pred0 >> 1) + (pred1 >> 1) + (pred0 & pred1 & 1)}, which fits 16 bits,
     * and a saturating addition.
     *
     * @param dst the destination plane
     * @param dstOff the index of the top-left sample of the columns
     * @param dstStride the plane's row stride
     * @param pred0 the list 0 prediction
     * @param pred1 the list 1 prediction
     * @param predOff the index of the first prediction value of the columns
     * @param predStride the row stride of the predictions
     * @param w the number of columns, a multiple of 8
     * @param h the block height, even
     * @param bitDepth the bit depth, from 8 to 12
     */
    private static void putBi8(short[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int predOff,
                               int predStride, int w, int h, int bitDepth) {
        var shift = 14 - bitDepth;
        var offset = SHORT128.cast(ShortVector.broadcast(S128, (short) (1 << (shift - 1))));
        var one = SHORT128.cast(ShortVector.broadcast(S128, (short) 1));
        var zero = SHORT128.cast(ShortVector.zero(S128));
        var max = SHORT128.cast(ShortVector.broadcast(S128, (short) ((1 << bitDepth) - 1)));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = predOff + y * predStride;
            for (var x = 0; x < w; x += 8) {
                var a0 = SHORT128.cast(ShortVector.fromArray(S128, pred0, in + x));
                var a1 = SHORT128.cast(ShortVector.fromArray(S128, pred1, in + x));
                var a = SHORT128.cast(a0.lanewise(VectorOperators.ASHR, 1))
                        .lanewise(VectorOperators.ADD, SHORT128.cast(a1.lanewise(VectorOperators.ASHR, 1)))
                        .lanewise(VectorOperators.ADD, a0.lanewise(VectorOperators.AND, a1)
                                .lanewise(VectorOperators.AND, one))
                        .lanewise(VectorOperators.SADD, offset);
                var b0 = SHORT128.cast(ShortVector.fromArray(S128, pred0, in + predStride + x));
                var b1 = SHORT128.cast(ShortVector.fromArray(S128, pred1, in + predStride + x));
                var b = SHORT128.cast(b0.lanewise(VectorOperators.ASHR, 1))
                        .lanewise(VectorOperators.ADD, SHORT128.cast(b1.lanewise(VectorOperators.ASHR, 1)))
                        .lanewise(VectorOperators.ADD, b0.lanewise(VectorOperators.AND, b1)
                                .lanewise(VectorOperators.AND, one))
                        .lanewise(VectorOperators.SADD, offset);
                SHORT128.cast(SHORT128.cast(a).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max)).intoArray(dst, row + x);
                SHORT128.cast(SHORT128.cast(b).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    /**
     * Writes 4 columns at a time of a bi-directional prediction. The sum
     * {@code pred0 + pred1} may leave 16 bits, so the kernel uses
     * {@code (pred0 + pred1 + offset2) >> shift2 == (h + (offset2 >> 1)) >> (shift2 - 1)} with the
     * halved sum {@code h = (pred0 >> 1) + (pred1 >> 1) + (pred0 & pred1 & 1)}, which fits 16 bits,
     * and a saturating addition.
     *
     * @param dst the destination plane
     * @param dstOff the index of the top-left sample of the columns
     * @param dstStride the plane's row stride
     * @param pred0 the list 0 prediction
     * @param pred1 the list 1 prediction
     * @param predOff the index of the first prediction value of the columns
     * @param predStride the row stride of the predictions
     * @param w the number of columns, 4
     * @param h the block height, even
     * @param bitDepth the bit depth, from 8 to 12
     */
    private static void putBi4(short[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int predOff,
                               int predStride, int w, int h, int bitDepth) {
        var shift = 14 - bitDepth;
        var offset = SHORT64.cast(ShortVector.broadcast(S64, (short) (1 << (shift - 1))));
        var one = SHORT64.cast(ShortVector.broadcast(S64, (short) 1));
        var zero = SHORT64.cast(ShortVector.zero(S64));
        var max = SHORT64.cast(ShortVector.broadcast(S64, (short) ((1 << bitDepth) - 1)));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = predOff + y * predStride;
            for (var x = 0; x < w; x += 4) {
                var a0 = SHORT64.cast(ShortVector.fromArray(S64, pred0, in + x));
                var a1 = SHORT64.cast(ShortVector.fromArray(S64, pred1, in + x));
                var a = SHORT64.cast(a0.lanewise(VectorOperators.ASHR, 1))
                        .lanewise(VectorOperators.ADD, SHORT64.cast(a1.lanewise(VectorOperators.ASHR, 1)))
                        .lanewise(VectorOperators.ADD, a0.lanewise(VectorOperators.AND, a1)
                                .lanewise(VectorOperators.AND, one))
                        .lanewise(VectorOperators.SADD, offset);
                var b0 = SHORT64.cast(ShortVector.fromArray(S64, pred0, in + predStride + x));
                var b1 = SHORT64.cast(ShortVector.fromArray(S64, pred1, in + predStride + x));
                var b = SHORT64.cast(b0.lanewise(VectorOperators.ASHR, 1))
                        .lanewise(VectorOperators.ADD, SHORT64.cast(b1.lanewise(VectorOperators.ASHR, 1)))
                        .lanewise(VectorOperators.ADD, b0.lanewise(VectorOperators.AND, b1)
                                .lanewise(VectorOperators.AND, one))
                        .lanewise(VectorOperators.SADD, offset);
                SHORT64.cast(SHORT64.cast(a).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max)).intoArray(dst, row + x);
                SHORT64.cast(SHORT64.cast(b).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    @Override
    public void putWeightedUni(short[] dst, int dstOff, int dstStride, short[] pred, int w, int h, int bitDepth,
                               int weight, int offset, int log2Wd) {
        if ((w & 3) != 0 || (h & 1) != 0) {
            scalar.putWeightedUni(dst, dstOff, dstStride, pred, w, h, bitDepth, weight, offset, log2Wd);
            return;
        }
        var wide = w & ~7;
        if (wide > 0) {
            putWeightedUni8(dst, dstOff, dstStride, pred, 0, w, wide, h, bitDepth, weight, offset, log2Wd);
        }
        if ((w & 4) != 0) {
            putWeightedUni4(dst, dstOff + wide, dstStride, pred, wide, w, 4, h, bitDepth, weight, offset, log2Wd);
        }
    }

    /**
     * Writes 8 columns at a time of a uni-directional prediction with explicit weighted
     * sample prediction, in 32-bit lanes.
     *
     * @param dst the destination plane
     * @param dstOff the index of the top-left sample of the columns
     * @param dstStride the plane's row stride
     * @param pred the prediction
     * @param predOff the index of the first prediction value of the columns
     * @param predStride the row stride of the prediction
     * @param w the number of columns, a multiple of 8
     * @param h the block height, even
     * @param bitDepth the bit depth, from 8 to 12
     * @param weight the weighting factor of the reference picture ({@code w0} of equation 8-265)
     * @param offset the offset, scaled to the bit depth
     * @param log2Wd the rounding shift, at least 1
     */
    private static void putWeightedUni8(short[] dst, int dstOff, int dstStride, short[] pred, int predOff,
                                        int predStride, int w, int h, int bitDepth, int weight, int offset,
                                        int log2Wd) {
        var factor = INT256.cast(IntVector.broadcast(I256, weight));
        var round = INT256.cast(IntVector.broadcast(I256, 1 << (log2Wd - 1)));
        var add = INT256.cast(IntVector.broadcast(I256, offset));
        var shift = log2Wd;
        var zero = INT256.cast(IntVector.zero(I256));
        var max = INT256.cast(IntVector.broadcast(I256, (1 << bitDepth) - 1));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = predOff + y * predStride;
            for (var x = 0; x < w; x += 8) {
                var a = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, pred, in + x))
                        .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.MUL, factor)
                        .lanewise(VectorOperators.ADD, round);
                var b = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, pred, in + predStride + x))
                        .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.MUL, factor)
                        .lanewise(VectorOperators.ADD, round);
                var first = INT256.cast(a).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.ADD, add)
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, max);
                var second = INT256.cast(b).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.ADD, add)
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, max);
                SHORT128.cast(first.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, row + x);
                SHORT128.cast(second.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    /**
     * Writes 4 columns at a time of a uni-directional prediction with explicit weighted
     * sample prediction, in 32-bit lanes.
     *
     * @param dst the destination plane
     * @param dstOff the index of the top-left sample of the columns
     * @param dstStride the plane's row stride
     * @param pred the prediction
     * @param predOff the index of the first prediction value of the columns
     * @param predStride the row stride of the prediction
     * @param w the number of columns, 4
     * @param h the block height, even
     * @param bitDepth the bit depth, from 8 to 12
     * @param weight the weighting factor of the reference picture ({@code w0} of equation 8-265)
     * @param offset the offset, scaled to the bit depth
     * @param log2Wd the rounding shift, at least 1
     */
    private static void putWeightedUni4(short[] dst, int dstOff, int dstStride, short[] pred, int predOff,
                                        int predStride, int w, int h, int bitDepth, int weight, int offset,
                                        int log2Wd) {
        var factor = INT128.cast(IntVector.broadcast(I128, weight));
        var round = INT128.cast(IntVector.broadcast(I128, 1 << (log2Wd - 1)));
        var add = INT128.cast(IntVector.broadcast(I128, offset));
        var shift = log2Wd;
        var zero = INT128.cast(IntVector.zero(I128));
        var max = INT128.cast(IntVector.broadcast(I128, (1 << bitDepth) - 1));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = predOff + y * predStride;
            for (var x = 0; x < w; x += 4) {
                var a = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, pred, in + x))
                        .convertShape(VectorOperators.S2I, I128, 0)).lanewise(VectorOperators.MUL, factor)
                        .lanewise(VectorOperators.ADD, round);
                var b = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, pred, in + predStride + x))
                        .convertShape(VectorOperators.S2I, I128, 0)).lanewise(VectorOperators.MUL, factor)
                        .lanewise(VectorOperators.ADD, round);
                var first = INT128.cast(a).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.ADD, add)
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, max);
                var second = INT128.cast(b).lanewise(VectorOperators.ASHR, shift).lanewise(VectorOperators.ADD, add)
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, max);
                SHORT64.cast(first.convertShape(VectorOperators.I2S, S64, 0)).intoArray(dst, row + x);
                SHORT64.cast(second.convertShape(VectorOperators.I2S, S64, 0)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    @Override
    public void putWeightedBi(short[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int w, int h,
                              int bitDepth, int weight0, int weight1, int offsets, int log2Wd) {
        if ((w & 3) != 0 || (h & 1) != 0) {
            scalar.putWeightedBi(dst, dstOff, dstStride, pred0, pred1, w, h, bitDepth, weight0, weight1, offsets,
                    log2Wd);
            return;
        }
        var wide = w & ~7;
        if (wide > 0) {
            putWeightedBi8(dst, dstOff, dstStride, pred0, pred1, 0, w, wide, h, bitDepth, weight0, weight1, offsets,
                    log2Wd);
        }
        if ((w & 4) != 0) {
            putWeightedBi4(dst, dstOff + wide, dstStride, pred0, pred1, wide, w, 4, h, bitDepth, weight0, weight1,
                    offsets, log2Wd);
        }
    }

    /**
     * Writes 8 columns at a time of a bi-directional prediction with explicit weighted
     * sample prediction, in 32-bit lanes.
     *
     * @param dst the destination plane
     * @param dstOff the index of the top-left sample of the columns
     * @param dstStride the plane's row stride
     * @param pred0 the list 0 prediction
     * @param pred1 the list 1 prediction
     * @param predOff the index of the first prediction value of the columns
     * @param predStride the row stride of the predictions
     * @param w the number of columns, a multiple of 8
     * @param h the block height, even
     * @param bitDepth the bit depth, from 8 to 12
     * @param weight0 the list 0 weight
     * @param weight1 the list 1 weight
     * @param offsets the sum of the two offsets scaled to the bit depth
     * @param log2Wd the rounding shift, at least 1
     */
    private static void putWeightedBi8(short[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1,
                                       int predOff, int predStride, int w, int h, int bitDepth, int weight0,
                                       int weight1, int offsets, int log2Wd) {
        var factor0 = INT256.cast(IntVector.broadcast(I256, weight0));
        var factor1 = INT256.cast(IntVector.broadcast(I256, weight1));
        var round = INT256.cast(IntVector.broadcast(I256, (offsets + 1) << log2Wd));
        var shift = log2Wd + 1;
        var zero = INT256.cast(IntVector.zero(I256));
        var max = INT256.cast(IntVector.broadcast(I256, (1 << bitDepth) - 1));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = predOff + y * predStride;
            for (var x = 0; x < w; x += 8) {
                var a0 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, pred0, in + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var a1 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, pred1, in + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var a = a0.lanewise(VectorOperators.MUL, factor0)
                        .lanewise(VectorOperators.ADD, a1.lanewise(VectorOperators.MUL, factor1))
                        .lanewise(VectorOperators.ADD, round);
                var b0 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, pred0, in + predStride + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var b1 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, pred1, in + predStride + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var b = b0.lanewise(VectorOperators.MUL, factor0)
                        .lanewise(VectorOperators.ADD, b1.lanewise(VectorOperators.MUL, factor1))
                        .lanewise(VectorOperators.ADD, round);
                var first = INT256.cast(a).lanewise(VectorOperators.ASHR, shift)
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, max);
                var second = INT256.cast(b).lanewise(VectorOperators.ASHR, shift)
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, max);
                SHORT128.cast(first.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, row + x);
                SHORT128.cast(second.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    /**
     * Writes 4 columns at a time of a bi-directional prediction with explicit weighted
     * sample prediction, in 32-bit lanes.
     *
     * @param dst the destination plane
     * @param dstOff the index of the top-left sample of the columns
     * @param dstStride the plane's row stride
     * @param pred0 the list 0 prediction
     * @param pred1 the list 1 prediction
     * @param predOff the index of the first prediction value of the columns
     * @param predStride the row stride of the predictions
     * @param w the number of columns, 4
     * @param h the block height, even
     * @param bitDepth the bit depth, from 8 to 12
     * @param weight0 the list 0 weight
     * @param weight1 the list 1 weight
     * @param offsets the sum of the two offsets scaled to the bit depth
     * @param log2Wd the rounding shift, at least 1
     */
    private static void putWeightedBi4(short[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1,
                                       int predOff, int predStride, int w, int h, int bitDepth, int weight0,
                                       int weight1, int offsets, int log2Wd) {
        var factor0 = INT128.cast(IntVector.broadcast(I128, weight0));
        var factor1 = INT128.cast(IntVector.broadcast(I128, weight1));
        var round = INT128.cast(IntVector.broadcast(I128, (offsets + 1) << log2Wd));
        var shift = log2Wd + 1;
        var zero = INT128.cast(IntVector.zero(I128));
        var max = INT128.cast(IntVector.broadcast(I128, (1 << bitDepth) - 1));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = predOff + y * predStride;
            for (var x = 0; x < w; x += 4) {
                var a0 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, pred0, in + x))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var a1 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, pred1, in + x))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var a = a0.lanewise(VectorOperators.MUL, factor0)
                        .lanewise(VectorOperators.ADD, a1.lanewise(VectorOperators.MUL, factor1))
                        .lanewise(VectorOperators.ADD, round);
                var b0 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, pred0, in + predStride + x))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var b1 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, pred1, in + predStride + x))
                        .convertShape(VectorOperators.S2I, I128, 0));
                var b = b0.lanewise(VectorOperators.MUL, factor0)
                        .lanewise(VectorOperators.ADD, b1.lanewise(VectorOperators.MUL, factor1))
                        .lanewise(VectorOperators.ADD, round);
                var first = INT128.cast(a).lanewise(VectorOperators.ASHR, shift)
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, max);
                var second = INT128.cast(b).lanewise(VectorOperators.ASHR, shift)
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, max);
                SHORT64.cast(first.convertShape(VectorOperators.I2S, S64, 0)).intoArray(dst, row + x);
                SHORT64.cast(second.convertShape(VectorOperators.I2S, S64, 0)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    @Override
    public void saoEdge(short[] dst, short[] src, int offset, int stride, int w, int h, int deltaA, int deltaB,
                        int[] lut, int max) {
        if (w < 16) {
            scalar.saoEdge(dst, src, offset, stride, w, h, deltaA, deltaB, lut, max);
            return;
        }
        var zero = SHORT256.cast(ShortVector.zero(S256));
        var one = SHORT256.cast(ShortVector.broadcast(S256, (short) 1));
        var minusOne = SHORT256.cast(ShortVector.broadcast(S256, (short) -1));
        var minusTwo = SHORT256.cast(ShortVector.broadcast(S256, (short) -2));
        var two = SHORT256.cast(ShortVector.broadcast(S256, (short) 2));
        var l0 = SHORT256.cast(ShortVector.broadcast(S256, (short) lut[0]));
        var l1 = SHORT256.cast(ShortVector.broadcast(S256, (short) lut[1]));
        var l3 = SHORT256.cast(ShortVector.broadcast(S256, (short) lut[3]));
        var l4 = SHORT256.cast(ShortVector.broadcast(S256, (short) lut[4]));
        var high = SHORT256.cast(ShortVector.broadcast(S256, (short) max));
        for (var y = 0; y < h; y++) {
            var start = offset + y * stride;
            for (var x = 0; x < w; x += 16) {
                var p = start + Math.min(x, w - 16);
                var value = SHORT256.cast(ShortVector.fromArray(S256, src, p));
                var a = SHORT256.cast(ShortVector.fromArray(S256, src, p + deltaA));
                var b = SHORT256.cast(ShortVector.fromArray(S256, src, p + deltaB));
                var signA = SHORT256.cast(zero).blend(one, value.compare(VectorOperators.GT, a))
                        .blend(minusOne, value.compare(VectorOperators.LT, a));
                var signB = SHORT256.cast(zero).blend(one, value.compare(VectorOperators.GT, b))
                        .blend(minusOne, value.compare(VectorOperators.LT, b));
                var sum = signA.lanewise(VectorOperators.ADD, signB);
                var add = SHORT256.cast(zero).blend(l0, sum.compare(VectorOperators.EQ, minusTwo))
                        .blend(l1, sum.compare(VectorOperators.EQ, minusOne))
                        .blend(l3, sum.compare(VectorOperators.EQ, one))
                        .blend(l4, sum.compare(VectorOperators.EQ, two));
                SHORT256.cast(value.lanewise(VectorOperators.ADD, add).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, high)).intoArray(dst, p);
            }
        }
    }

    @Override
    public void saoBand(short[] dst, short[] src, int offset, int stride, int w, int h, int position, int shift,
                        int o0, int o1, int o2, int o3, int max) {
        if (w < 16) {
            scalar.saoBand(dst, src, offset, stride, w, h, position, shift, o0, o1, o2, o3, max);
            return;
        }
        var zero = SHORT256.cast(ShortVector.zero(S256));
        var one = SHORT256.cast(ShortVector.broadcast(S256, (short) 1));
        var two = SHORT256.cast(ShortVector.broadcast(S256, (short) 2));
        var three = SHORT256.cast(ShortVector.broadcast(S256, (short) 3));
        var start = SHORT256.cast(ShortVector.broadcast(S256, (short) position));
        var mask = SHORT256.cast(ShortVector.broadcast(S256, (short) 31));
        var b0 = SHORT256.cast(ShortVector.broadcast(S256, (short) o0));
        var b1 = SHORT256.cast(ShortVector.broadcast(S256, (short) o1));
        var b2 = SHORT256.cast(ShortVector.broadcast(S256, (short) o2));
        var b3 = SHORT256.cast(ShortVector.broadcast(S256, (short) o3));
        var high = SHORT256.cast(ShortVector.broadcast(S256, (short) max));
        for (var y = 0; y < h; y++) {
            var row = offset + y * stride;
            for (var x = 0; x < w; x += 16) {
                var p = row + Math.min(x, w - 16);
                var value = SHORT256.cast(ShortVector.fromArray(S256, src, p));
                var band = SHORT256.cast(value).lanewise(VectorOperators.ASHR, shift)
                        .lanewise(VectorOperators.SUB, start).lanewise(VectorOperators.AND, mask);
                var add = SHORT256.cast(zero).blend(b0, band.compare(VectorOperators.EQ, zero))
                        .blend(b1, band.compare(VectorOperators.EQ, one))
                        .blend(b2, band.compare(VectorOperators.EQ, two))
                        .blend(b3, band.compare(VectorOperators.EQ, three));
                SHORT256.cast(value.lanewise(VectorOperators.ADD, add).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, high)).intoArray(dst, p);
            }
        }
    }

    @Override
    public void addResidual(short[] plane, int offset, int stride, int[] residual, int size, int max) {
        if (size < 8) {
            addResidual4(plane, offset, stride, residual, max);
            return;
        }
        var zero = INT256.cast(IntVector.zero(I256));
        var high = INT256.cast(IntVector.broadcast(I256, max));
        for (var y = 0; y < size; y += 2) {
            var row = offset + y * stride;
            var in = y * size;
            for (var x = 0; x < size; x += 8) {
                var a = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, plane, row + x))
                                .convertShape(VectorOperators.S2I, I256, 0))
                        .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, residual, in + x)))
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, high);
                var b = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, plane, row + stride + x))
                                .convertShape(VectorOperators.S2I, I256, 0))
                        .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, residual, in + size + x)))
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, high);
                SHORT128.cast(INT256.cast(a).convertShape(VectorOperators.I2S, S128, 0)).intoArray(plane, row + x);
                SHORT128.cast(INT256.cast(b).convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(plane, row + stride + x);
            }
        }
    }

    /**
     * Adds a 4 by 4 block of residual samples to a plane and clips the sums, two rows per
     * iteration in 4-lane vectors.
     *
     * @param plane the plane holding the predicted samples, modified in place
     * @param offset the index of the block's top-left sample
     * @param stride the plane's row stride
     * @param residual the residual samples, 4 per row
     * @param max the largest sample value
     */
    private static void addResidual4(short[] plane, int offset, int stride, int[] residual, int max) {
        var zero = INT128.cast(IntVector.zero(I128));
        var high = INT128.cast(IntVector.broadcast(I128, max));
        for (var y = 0; y < 4; y += 2) {
            var row = offset + y * stride;
            var a = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, plane, row))
                            .convertShape(VectorOperators.S2I, I128, 0))
                    .lanewise(VectorOperators.ADD, INT128.cast(IntVector.fromArray(I128, residual, y * 4)))
                    .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, high);
            var b = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, plane, row + stride))
                            .convertShape(VectorOperators.S2I, I128, 0))
                    .lanewise(VectorOperators.ADD, INT128.cast(IntVector.fromArray(I128, residual, y * 4 + 4)))
                    .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, high);
            SHORT64.cast(INT128.cast(a).convertShape(VectorOperators.I2S, S64, 0)).intoArray(plane, row);
            SHORT64.cast(INT128.cast(b).convertShape(VectorOperators.I2S, S64, 0)).intoArray(plane, row + stride);
        }
    }

    @Override
    public void deblockLuma(byte[] plane, int origin, int stride, short[] params) {
        deblockNormal(plane, origin, stride, params);
        deblockStrong(plane, origin, stride, params);
    }

    @Override
    public void deblockLumaVertical(byte[] plane, int origin, int stride, short[] params, byte[] scratch) {
        var first = origin - 4;
        var ninth = first + 8 * stride;
        transpose8x8(plane, first, stride, scratch, 0, 16);
        transpose8x8(plane, ninth, stride, scratch, 8, 16);
        deblockNormal(scratch, 64, 16, params);
        deblockStrong(scratch, 64, 16, params);
        transpose8x8(scratch, 0, 16, plane, first, stride);
        transpose8x8(scratch, 8, 16, plane, ninth, stride);
    }

    /**
     * Transposes an 8x8 block of bytes: byte {@code k} of source row {@code i} becomes byte
     * {@code i} of destination row {@code k}. The source and the destination must not overlap.
     *
     * @implNote This implementation reads each row as one little-endian {@code long} and exchanges
     * 4x4, then 2x2, then 1x1 sub-blocks of bytes between pairs of rows with masked
     * exclusive-or swaps, the byte form of the transposition in Hacker's Delight 7-3.
     * @param src the source array
     * @param srcOff the index of the first byte of the source block
     * @param srcStride the source row stride
     * @param dst the destination array
     * @param dstOff the index of the first byte of the destination block
     * @param dstStride the destination row stride
     */
    private static void transpose8x8(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride) {
        var r0 = (long) LONGS.get(src, srcOff);
        var r1 = (long) LONGS.get(src, srcOff + srcStride);
        var r2 = (long) LONGS.get(src, srcOff + 2 * srcStride);
        var r3 = (long) LONGS.get(src, srcOff + 3 * srcStride);
        var r4 = (long) LONGS.get(src, srcOff + 4 * srcStride);
        var r5 = (long) LONGS.get(src, srcOff + 5 * srcStride);
        var r6 = (long) LONGS.get(src, srcOff + 6 * srcStride);
        var r7 = (long) LONGS.get(src, srcOff + 7 * srcStride);
        var t = ((r0 >>> 32) ^ r4) & 0xFFFFFFFFL;
        r0 ^= t << 32;
        r4 ^= t;
        t = ((r1 >>> 32) ^ r5) & 0xFFFFFFFFL;
        r1 ^= t << 32;
        r5 ^= t;
        t = ((r2 >>> 32) ^ r6) & 0xFFFFFFFFL;
        r2 ^= t << 32;
        r6 ^= t;
        t = ((r3 >>> 32) ^ r7) & 0xFFFFFFFFL;
        r3 ^= t << 32;
        r7 ^= t;
        t = ((r0 >>> 16) ^ r2) & 0x0000FFFF0000FFFFL;
        r0 ^= t << 16;
        r2 ^= t;
        t = ((r1 >>> 16) ^ r3) & 0x0000FFFF0000FFFFL;
        r1 ^= t << 16;
        r3 ^= t;
        t = ((r4 >>> 16) ^ r6) & 0x0000FFFF0000FFFFL;
        r4 ^= t << 16;
        r6 ^= t;
        t = ((r5 >>> 16) ^ r7) & 0x0000FFFF0000FFFFL;
        r5 ^= t << 16;
        r7 ^= t;
        t = ((r0 >>> 8) ^ r1) & 0x00FF00FF00FF00FFL;
        r0 ^= t << 8;
        r1 ^= t;
        t = ((r2 >>> 8) ^ r3) & 0x00FF00FF00FF00FFL;
        r2 ^= t << 8;
        r3 ^= t;
        t = ((r4 >>> 8) ^ r5) & 0x00FF00FF00FF00FFL;
        r4 ^= t << 8;
        r5 ^= t;
        t = ((r6 >>> 8) ^ r7) & 0x00FF00FF00FF00FFL;
        r6 ^= t << 8;
        r7 ^= t;
        LONGS.set(dst, dstOff, r0);
        LONGS.set(dst, dstOff + dstStride, r1);
        LONGS.set(dst, dstOff + 2 * dstStride, r2);
        LONGS.set(dst, dstOff + 3 * dstStride, r3);
        LONGS.set(dst, dstOff + 4 * dstStride, r4);
        LONGS.set(dst, dstOff + 5 * dstStride, r5);
        LONGS.set(dst, dstOff + 6 * dstStride, r6);
        LONGS.set(dst, dstOff + 7 * dstStride, r7);
    }

    /**
     * Applies the normal luma filter of {@link #deblockLuma} to the columns flagged
     * {@link #DEBLOCK_NORMAL} and not {@link #DEBLOCK_STRONG}, rewriting the other columns
     * unchanged.
     *
     * @param plane the plane, modified in place
     * @param origin the index of {@code q0} of the first column
     * @param stride the plane's row stride
     * @param params {@code tC} and the flags of each column
     */
    private static void deblockNormal(byte[] plane, int origin, int stride, short[] params) {
        var tc = SHORT256.cast(ShortVector.fromArray(S256, params, 0));
        var flags = SHORT256.cast(ShortVector.fromArray(S256, params, 16));
        var p2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, plane, origin - 3 * stride))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, plane, origin - 2 * stride))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, plane, origin - stride))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, plane, origin))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, plane, origin + stride))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, plane, origin + 2 * stride))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var zero = SHORT256.cast(ShortVector.zero(S256));
        var high = SHORT256.cast(ShortVector.broadcast(S256, (short) 255));
        var one = SHORT256.cast(ShortVector.broadcast(S256, (short) 1));
        var three = SHORT256.cast(ShortVector.broadcast(S256, (short) 3));
        var eight = SHORT256.cast(ShortVector.broadcast(S256, (short) 8));
        var nine = SHORT256.cast(ShortVector.broadcast(S256, (short) 9));
        var ten = SHORT256.cast(ShortVector.broadcast(S256, (short) 10));
        var normalP = SHORT256.cast(ShortVector.broadcast(S256, (short) (DEBLOCK_NORMAL | DEBLOCK_WRITE_P)));
        var normalQ = SHORT256.cast(ShortVector.broadcast(S256, (short) (DEBLOCK_NORMAL | DEBLOCK_WRITE_Q)));
        var normalP1 = SHORT256.cast(ShortVector.broadcast(S256, (short) (DEBLOCK_NORMAL | DEBLOCK_WRITE_P | DEBLOCK_P1)));
        var normalQ1 = SHORT256.cast(ShortVector.broadcast(S256, (short) (DEBLOCK_NORMAL | DEBLOCK_WRITE_Q | DEBLOCK_Q1)));
        var strongBit = SHORT256.cast(ShortVector.broadcast(S256, DEBLOCK_STRONG));
        var raw = SHORT256.cast(q0.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.MUL, nine)
                        .lanewise(VectorOperators.SUB, q1.lanewise(VectorOperators.SUB, p1)
                                .lanewise(VectorOperators.MUL, three))
                        .lanewise(VectorOperators.ADD, eight)).lanewise(VectorOperators.ASHR, 4);
        var active = raw.lanewise(VectorOperators.ABS).compare(VectorOperators.LT,
                tc.lanewise(VectorOperators.MUL, ten));
        var delta = raw.lanewise(VectorOperators.MAX, zero.lanewise(VectorOperators.SUB, tc))
                .lanewise(VectorOperators.MIN, tc);
        var halfTc = SHORT256.cast(tc).lanewise(VectorOperators.ASHR, 1);
        var normalP0 = p0.lanewise(VectorOperators.ADD, delta).lanewise(VectorOperators.MAX, zero)
                .lanewise(VectorOperators.MIN, high);
        var normalQ0 = q0.lanewise(VectorOperators.SUB, delta).lanewise(VectorOperators.MAX, zero)
                .lanewise(VectorOperators.MIN, high);
        var deltaP = SHORT256.cast(SHORT256.cast(p2.lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, one))
                        .lanewise(VectorOperators.ASHR, 1).lanewise(VectorOperators.SUB, p1)
                        .lanewise(VectorOperators.ADD, delta)).lanewise(VectorOperators.ASHR, 1)
                .lanewise(VectorOperators.MAX, zero.lanewise(VectorOperators.SUB, halfTc))
                .lanewise(VectorOperators.MIN, halfTc);
        var deltaQ = SHORT256.cast(SHORT256.cast(q2.lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, one))
                        .lanewise(VectorOperators.ASHR, 1).lanewise(VectorOperators.SUB, q1)
                        .lanewise(VectorOperators.SUB, delta)).lanewise(VectorOperators.ASHR, 1)
                .lanewise(VectorOperators.MAX, zero.lanewise(VectorOperators.SUB, halfTc))
                .lanewise(VectorOperators.MIN, halfTc);
        var normalP1Value = p1.lanewise(VectorOperators.ADD, deltaP).lanewise(VectorOperators.MAX, zero)
                .lanewise(VectorOperators.MIN, high);
        var normalQ1Value = q1.lanewise(VectorOperators.ADD, deltaQ).lanewise(VectorOperators.MAX, zero)
                .lanewise(VectorOperators.MIN, high);
        var isNormalP = flags.lanewise(VectorOperators.AND, normalP.lanewise(VectorOperators.OR, strongBit))
                .compare(VectorOperators.EQ, normalP)
                .and(active);
        var isNormalQ = flags.lanewise(VectorOperators.AND, normalQ.lanewise(VectorOperators.OR, strongBit))
                .compare(VectorOperators.EQ, normalQ)
                .and(active);
        var isNormalP1 = flags.lanewise(VectorOperators.AND, normalP1.lanewise(VectorOperators.OR, strongBit))
                .compare(VectorOperators.EQ, normalP1)
                .and(active);
        var isNormalQ1 = flags.lanewise(VectorOperators.AND, normalQ1.lanewise(VectorOperators.OR, strongBit))
                .compare(VectorOperators.EQ, normalQ1)
                .and(active);
        var outP1 = p1.blend(normalP1Value, isNormalP1);
        var outP0 = p0.blend(normalP0, isNormalP);
        var outQ0 = q0.blend(normalQ0, isNormalQ);
        var outQ1 = q1.blend(normalQ1Value, isNormalQ1);
        BYTE128.cast(SHORT256.cast(outP1).convertShape(VectorOperators.S2B, B128, 0))
                .intoArray(plane, origin - 2 * stride);
        BYTE128.cast(SHORT256.cast(outP0).convertShape(VectorOperators.S2B, B128, 0)).intoArray(plane, origin - stride);
        BYTE128.cast(SHORT256.cast(outQ0).convertShape(VectorOperators.S2B, B128, 0)).intoArray(plane, origin);
        BYTE128.cast(SHORT256.cast(outQ1).convertShape(VectorOperators.S2B, B128, 0)).intoArray(plane, origin + stride);
    }

    /**
     * Applies the strong luma filter of {@link #deblockLuma} to the columns flagged
     * {@link #DEBLOCK_STRONG}, rewriting the other columns unchanged.
     *
     * @param plane the plane, modified in place
     * @param origin the index of {@code q0} of the first column
     * @param stride the plane's row stride
     * @param params {@code tC} and the flags of each column
     */
    private static void deblockStrong(byte[] plane, int origin, int stride, short[] params) {
        var tc = SHORT256.cast(ShortVector.fromArray(S256, params, 0));
        var flags = SHORT256.cast(ShortVector.fromArray(S256, params, 16));
        var p3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, plane, origin - 4 * stride))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, plane, origin - 3 * stride))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, plane, origin - 2 * stride))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, plane, origin - stride))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, plane, origin))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, plane, origin + stride))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, plane, origin + 2 * stride))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, plane, origin + 3 * stride))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var zero = SHORT256.cast(ShortVector.zero(S256));
        var two = SHORT256.cast(ShortVector.broadcast(S256, (short) 2));
        var four = SHORT256.cast(ShortVector.broadcast(S256, (short) 4));
        var strongP = SHORT256.cast(ShortVector.broadcast(S256, (short) (DEBLOCK_STRONG | DEBLOCK_WRITE_P)));
        var strongQ = SHORT256.cast(ShortVector.broadcast(S256, (short) (DEBLOCK_STRONG | DEBLOCK_WRITE_Q)));
        var tc2 = tc.lanewise(VectorOperators.ADD, tc);
        var sumP = p2.lanewise(VectorOperators.ADD, p1).lanewise(VectorOperators.ADD, p0)
                .lanewise(VectorOperators.ADD, q0);
        var sumQ = q2.lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, q0)
                .lanewise(VectorOperators.ADD, p0);
        var strongP0 = SHORT256.cast(sumP.lanewise(VectorOperators.ADD, p1).lanewise(VectorOperators.ADD, p0)
                        .lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, q1)
                        .lanewise(VectorOperators.ADD, four)).lanewise(VectorOperators.ASHR, 3)
                .lanewise(VectorOperators.MAX, p0.lanewise(VectorOperators.SUB, tc2))
                .lanewise(VectorOperators.MIN, p0.lanewise(VectorOperators.ADD, tc2));
        var strongP1 = SHORT256.cast(sumP.lanewise(VectorOperators.ADD, two)).lanewise(VectorOperators.ASHR, 2)
                .lanewise(VectorOperators.MAX, p1.lanewise(VectorOperators.SUB, tc2))
                .lanewise(VectorOperators.MIN, p1.lanewise(VectorOperators.ADD, tc2));
        var strongP2 = SHORT256.cast(sumP.lanewise(VectorOperators.ADD, p3).lanewise(VectorOperators.ADD, p3)
                        .lanewise(VectorOperators.ADD, p2).lanewise(VectorOperators.ADD, p2)
                        .lanewise(VectorOperators.ADD, four)).lanewise(VectorOperators.ASHR, 3)
                .lanewise(VectorOperators.MAX, p2.lanewise(VectorOperators.SUB, tc2))
                .lanewise(VectorOperators.MIN, p2.lanewise(VectorOperators.ADD, tc2));
        var strongQ0 = SHORT256.cast(sumQ.lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, q0)
                        .lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, p1)
                        .lanewise(VectorOperators.ADD, four)).lanewise(VectorOperators.ASHR, 3)
                .lanewise(VectorOperators.MAX, q0.lanewise(VectorOperators.SUB, tc2))
                .lanewise(VectorOperators.MIN, q0.lanewise(VectorOperators.ADD, tc2));
        var strongQ1 = SHORT256.cast(sumQ.lanewise(VectorOperators.ADD, two)).lanewise(VectorOperators.ASHR, 2)
                .lanewise(VectorOperators.MAX, q1.lanewise(VectorOperators.SUB, tc2))
                .lanewise(VectorOperators.MIN, q1.lanewise(VectorOperators.ADD, tc2));
        var strongQ2 = SHORT256.cast(sumQ.lanewise(VectorOperators.ADD, q3).lanewise(VectorOperators.ADD, q3)
                        .lanewise(VectorOperators.ADD, q2).lanewise(VectorOperators.ADD, q2)
                        .lanewise(VectorOperators.ADD, four)).lanewise(VectorOperators.ASHR, 3)
                .lanewise(VectorOperators.MAX, q2.lanewise(VectorOperators.SUB, tc2))
                .lanewise(VectorOperators.MIN, q2.lanewise(VectorOperators.ADD, tc2));
        var isStrongP = flags.lanewise(VectorOperators.AND, strongP).compare(VectorOperators.EQ, strongP);
        var isStrongQ = flags.lanewise(VectorOperators.AND, strongQ).compare(VectorOperators.EQ, strongQ);
        var outP2 = p2.blend(strongP2, isStrongP);
        var outP1 = p1.blend(strongP1, isStrongP);
        var outP0 = p0.blend(strongP0, isStrongP);
        var outQ0 = q0.blend(strongQ0, isStrongQ);
        var outQ1 = q1.blend(strongQ1, isStrongQ);
        var outQ2 = q2.blend(strongQ2, isStrongQ);
        BYTE128.cast(SHORT256.cast(outP2).convertShape(VectorOperators.S2B, B128, 0))
                .intoArray(plane, origin - 3 * stride);
        BYTE128.cast(SHORT256.cast(outP1).convertShape(VectorOperators.S2B, B128, 0))
                .intoArray(plane, origin - 2 * stride);
        BYTE128.cast(SHORT256.cast(outP0).convertShape(VectorOperators.S2B, B128, 0)).intoArray(plane, origin - stride);
        BYTE128.cast(SHORT256.cast(outQ0).convertShape(VectorOperators.S2B, B128, 0)).intoArray(plane, origin);
        BYTE128.cast(SHORT256.cast(outQ1).convertShape(VectorOperators.S2B, B128, 0)).intoArray(plane, origin + stride);
        BYTE128.cast(SHORT256.cast(outQ2).convertShape(VectorOperators.S2B, B128, 0))
                .intoArray(plane, origin + 2 * stride);
    }

    @Override
    public void inverseDct(int[] coefficients, int log2Size, int bdShift, int columns, int rows, int[] residual,
                           int[] scratch) {
        if (log2Size == 2) {
            scalar.inverseDct(coefficients, log2Size, bdShift, columns, rows, residual, scratch);
            return;
        }
        switch (log2Size) {
            case 3 -> {
                for (var x = 0; x < columns; x += 8) {
                    dctColumns8(coefficients, x, rows, scratch);
                }
                dctRows4(scratch, 128, columns, bdShift, residual);
            }
            case 4 -> {
                for (var x = 0; x < columns; x += 8) {
                    dctColumns16(coefficients, x, rows, scratch);
                }
                dctRows16(scratch, 0, columns, bdShift, residual);
            }
            default -> {
                for (var x = 0; x < columns; x += 8) {
                    dctColumns32(coefficients, x, rows, scratch);
                }
                for (var n = 0; n < 16; n += 8) {
                    dctRows32(scratch, n, columns, bdShift, residual);
                }
            }
        }
    }

    /**
     * Applies the first, vertical, stage of an inverse DCT of 8 points to a group of 8
     * columns: for each output row {@code n} of the first half, the even and odd frequency sums of
     * the 8 columns, combined into rows {@code n} and {@code 7 - n}, rounded, shifted by 7 and
     * clipped to 16 bits.
     *
     * @param coefficients the coefficients, 8 per row
     * @param x the first column of the group
     * @param rows the number of leading rows that may hold non-zero coefficients
     * @param out receives the intermediate samples, 8 per row
     */
    private static void dctColumns8(int[] coefficients, int x, int rows, int[] out) {
        var basis = HevcTransform.DCT32;
        var round = INT256.cast(IntVector.broadcast(I256, 64));
        var low = INT256.cast(IntVector.broadcast(I256, Short.MIN_VALUE));
        var high = INT256.cast(IntVector.broadcast(I256, Short.MAX_VALUE));
        var half = 4;
        for (var n = 0; n < half; n++) {
            var even = INT256.cast(IntVector.zero(I256));
            var odd = INT256.cast(IntVector.zero(I256));
            for (var k = 0; k < rows; k += 2) {
                var c = INT256.cast(IntVector.fromArray(I256, coefficients, k * 8 + x));
                even = INT256.cast(even).lanewise(VectorOperators.ADD,
                        c.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, basis[k * 128 + n])));
            }
            for (var k = 1; k < rows; k += 2) {
                var c = INT256.cast(IntVector.fromArray(I256, coefficients, k * 8 + x));
                odd = INT256.cast(odd).lanewise(VectorOperators.ADD,
                        c.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, basis[k * 128 + n])));
            }
            var sum = INT256.cast(INT256.cast(even).lanewise(VectorOperators.ADD, odd).lanewise(VectorOperators.ADD, round));
            var difference = INT256.cast(INT256.cast(even).lanewise(VectorOperators.SUB, odd).lanewise(VectorOperators.ADD, round));
            INT256.cast(sum.lanewise(VectorOperators.ASHR, 7).lanewise(VectorOperators.MAX, low)
                    .lanewise(VectorOperators.MIN, high)).intoArray(out, n * 8 + x);
            INT256.cast(difference.lanewise(VectorOperators.ASHR, 7).lanewise(VectorOperators.MAX, low)
                    .lanewise(VectorOperators.MIN, high)).intoArray(out, (7 - n) * 8 + x);
        }
    }

    /**
     * Applies the first, vertical, stage of an inverse DCT of 16 points to a group of 8
     * columns: for each output row {@code n} of the first half, the even and odd frequency sums of
     * the 8 columns, combined into rows {@code n} and {@code 15 - n}, rounded, shifted by 7 and
     * clipped to 16 bits.
     *
     * @param coefficients the coefficients, 16 per row
     * @param x the first column of the group
     * @param rows the number of leading rows that may hold non-zero coefficients
     * @param out receives the intermediate samples, 16 per row
     */
    private static void dctColumns16(int[] coefficients, int x, int rows, int[] out) {
        var basis = HevcTransform.DCT32;
        var round = INT256.cast(IntVector.broadcast(I256, 64));
        var low = INT256.cast(IntVector.broadcast(I256, Short.MIN_VALUE));
        var high = INT256.cast(IntVector.broadcast(I256, Short.MAX_VALUE));
        var half = 8;
        for (var n = 0; n < half; n++) {
            var even = INT256.cast(IntVector.zero(I256));
            var odd = INT256.cast(IntVector.zero(I256));
            for (var k = 0; k < rows; k += 2) {
                var c = INT256.cast(IntVector.fromArray(I256, coefficients, k * 16 + x));
                even = INT256.cast(even).lanewise(VectorOperators.ADD,
                        c.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, basis[k * 64 + n])));
            }
            for (var k = 1; k < rows; k += 2) {
                var c = INT256.cast(IntVector.fromArray(I256, coefficients, k * 16 + x));
                odd = INT256.cast(odd).lanewise(VectorOperators.ADD,
                        c.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, basis[k * 64 + n])));
            }
            var sum = INT256.cast(INT256.cast(even).lanewise(VectorOperators.ADD, odd).lanewise(VectorOperators.ADD, round));
            var difference = INT256.cast(INT256.cast(even).lanewise(VectorOperators.SUB, odd).lanewise(VectorOperators.ADD, round));
            INT256.cast(sum.lanewise(VectorOperators.ASHR, 7).lanewise(VectorOperators.MAX, low)
                    .lanewise(VectorOperators.MIN, high)).intoArray(out, n * 16 + x);
            INT256.cast(difference.lanewise(VectorOperators.ASHR, 7).lanewise(VectorOperators.MAX, low)
                    .lanewise(VectorOperators.MIN, high)).intoArray(out, (15 - n) * 16 + x);
        }
    }

    /**
     * Applies the first, vertical, stage of an inverse DCT of 32 points to a group of 8
     * columns: for each output row {@code n} of the first half, the even and odd frequency sums of
     * the 8 columns, combined into rows {@code n} and {@code 31 - n}, rounded, shifted by 7 and
     * clipped to 16 bits.
     *
     * @param coefficients the coefficients, 32 per row
     * @param x the first column of the group
     * @param rows the number of leading rows that may hold non-zero coefficients
     * @param out receives the intermediate samples, 32 per row
     */
    private static void dctColumns32(int[] coefficients, int x, int rows, int[] out) {
        var basis = HevcTransform.DCT32;
        var round = INT256.cast(IntVector.broadcast(I256, 64));
        var low = INT256.cast(IntVector.broadcast(I256, Short.MIN_VALUE));
        var high = INT256.cast(IntVector.broadcast(I256, Short.MAX_VALUE));
        var half = 16;
        for (var n = 0; n < half; n++) {
            var even = INT256.cast(IntVector.zero(I256));
            var odd = INT256.cast(IntVector.zero(I256));
            for (var k = 0; k < rows; k += 2) {
                var c = INT256.cast(IntVector.fromArray(I256, coefficients, k * 32 + x));
                even = INT256.cast(even).lanewise(VectorOperators.ADD,
                        c.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, basis[k * 32 + n])));
            }
            for (var k = 1; k < rows; k += 2) {
                var c = INT256.cast(IntVector.fromArray(I256, coefficients, k * 32 + x));
                odd = INT256.cast(odd).lanewise(VectorOperators.ADD,
                        c.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, basis[k * 32 + n])));
            }
            var sum = INT256.cast(INT256.cast(even).lanewise(VectorOperators.ADD, odd).lanewise(VectorOperators.ADD, round));
            var difference = INT256.cast(INT256.cast(even).lanewise(VectorOperators.SUB, odd).lanewise(VectorOperators.ADD, round));
            INT256.cast(sum.lanewise(VectorOperators.ASHR, 7).lanewise(VectorOperators.MAX, low)
                    .lanewise(VectorOperators.MIN, high)).intoArray(out, n * 32 + x);
            INT256.cast(difference.lanewise(VectorOperators.ASHR, 7).lanewise(VectorOperators.MAX, low)
                    .lanewise(VectorOperators.MIN, high)).intoArray(out, (31 - n) * 32 + x);
        }
    }

    /**
     * Applies the second, horizontal, stage of an inverse DCT of 16 points to 8 outputs of
     * the first half of every row: the even and odd frequency sums of outputs {@code n} to
     * {@code n + 7}, combined into those outputs and, in reverse order, into outputs
     * {@code 8 - n} to {@code 15 - n}, rounded and shifted by {@code bdShift}.
     *
     * @param mid the intermediate samples of the first stage, 16 per row
     * @param n the first output of the group, a multiple of 8 below 8
     * @param columns the number of leading columns that may hold non-zero coefficients
     * @param bdShift the final shift
     * @param residual receives the residual samples, 16 per row
     */
    private static void dctRows16(int[] mid, int n, int columns, int bdShift, int[] residual) {
        var basis = HevcTransform.DCT32;
        var round = INT256.cast(IntVector.broadcast(I256, 1 << (bdShift - 1)));
        for (var y = 0; y < 16; y++) {
            var row = y * 16;
            var even = INT256.cast(IntVector.zero(I256));
            var odd = INT256.cast(IntVector.zero(I256));
            for (var k = 0; k < columns; k += 2) {
                var b = INT256.cast(IntVector.fromArray(I256, basis, k * 64 + n));
                even = INT256.cast(even).lanewise(VectorOperators.ADD,
                        b.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, mid[row + k])));
            }
            for (var k = 1; k < columns; k += 2) {
                var b = INT256.cast(IntVector.fromArray(I256, basis, k * 64 + n));
                odd = INT256.cast(odd).lanewise(VectorOperators.ADD,
                        b.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, mid[row + k])));
            }
            var sum = INT256.cast(INT256.cast(even).lanewise(VectorOperators.ADD, odd).lanewise(VectorOperators.ADD, round));
            var difference = INT256.cast(INT256.cast(even).lanewise(VectorOperators.SUB, odd).lanewise(VectorOperators.ADD, round));
            INT256.cast(sum.lanewise(VectorOperators.ASHR, bdShift)).intoArray(residual, row + n);
            INT256.cast(INT256.cast(difference.lanewise(VectorOperators.ASHR, bdShift)).rearrange(REVERSE8))
                    .intoArray(residual, row + 8 - n);
        }
    }

    /**
     * Applies the second, horizontal, stage of an inverse DCT of 32 points to 8 outputs of
     * the first half of every row: the even and odd frequency sums of outputs {@code n} to
     * {@code n + 7}, combined into those outputs and, in reverse order, into outputs
     * {@code 24 - n} to {@code 31 - n}, rounded and shifted by {@code bdShift}.
     *
     * @param mid the intermediate samples of the first stage, 32 per row
     * @param n the first output of the group, a multiple of 8 below 16
     * @param columns the number of leading columns that may hold non-zero coefficients
     * @param bdShift the final shift
     * @param residual receives the residual samples, 32 per row
     */
    private static void dctRows32(int[] mid, int n, int columns, int bdShift, int[] residual) {
        var basis = HevcTransform.DCT32;
        var round = INT256.cast(IntVector.broadcast(I256, 1 << (bdShift - 1)));
        for (var y = 0; y < 32; y++) {
            var row = y * 32;
            var even = INT256.cast(IntVector.zero(I256));
            var odd = INT256.cast(IntVector.zero(I256));
            for (var k = 0; k < columns; k += 2) {
                var b = INT256.cast(IntVector.fromArray(I256, basis, k * 32 + n));
                even = INT256.cast(even).lanewise(VectorOperators.ADD,
                        b.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, mid[row + k])));
            }
            for (var k = 1; k < columns; k += 2) {
                var b = INT256.cast(IntVector.fromArray(I256, basis, k * 32 + n));
                odd = INT256.cast(odd).lanewise(VectorOperators.ADD,
                        b.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, mid[row + k])));
            }
            var sum = INT256.cast(INT256.cast(even).lanewise(VectorOperators.ADD, odd).lanewise(VectorOperators.ADD, round));
            var difference = INT256.cast(INT256.cast(even).lanewise(VectorOperators.SUB, odd).lanewise(VectorOperators.ADD, round));
            INT256.cast(sum.lanewise(VectorOperators.ASHR, bdShift)).intoArray(residual, row + n);
            INT256.cast(INT256.cast(difference.lanewise(VectorOperators.ASHR, bdShift)).rearrange(REVERSE8))
                    .intoArray(residual, row + 24 - n);
        }
    }

    /**
     * Applies the second, horizontal, stage of an 8-point inverse DCT to every row: the even and
     * odd frequency sums of the first 4 outputs, combined into outputs 0 to 3 and, in reverse order,
     * into outputs 4 to 7, rounded and shifted by {@code bdShift}.
     *
     * @param mid the intermediate samples of the first stage, 8 per row
     * @param step the distance between the basis rows of consecutive frequencies in
     * {@link HevcTransform#DCT32}
     * @param columns the number of leading columns that may hold non-zero coefficients
     * @param bdShift the final shift
     * @param residual receives the residual samples, 8 per row
     */
    private static void dctRows4(int[] mid, int step, int columns, int bdShift, int[] residual) {
        var basis = HevcTransform.DCT32;
        var round = INT128.cast(IntVector.broadcast(I128, 1 << (bdShift - 1)));
        for (var y = 0; y < 8; y++) {
            var row = y * 8;
            var even = INT128.cast(IntVector.zero(I128));
            var odd = INT128.cast(IntVector.zero(I128));
            for (var k = 0; k < columns; k += 2) {
                var b = INT128.cast(IntVector.fromArray(I128, basis, k * step));
                even = INT128.cast(even).lanewise(VectorOperators.ADD,
                        b.lanewise(VectorOperators.MUL, IntVector.broadcast(I128, mid[row + k])));
            }
            for (var k = 1; k < columns; k += 2) {
                var b = INT128.cast(IntVector.fromArray(I128, basis, k * step));
                odd = INT128.cast(odd).lanewise(VectorOperators.ADD,
                        b.lanewise(VectorOperators.MUL, IntVector.broadcast(I128, mid[row + k])));
            }
            var sum = INT128.cast(INT128.cast(even).lanewise(VectorOperators.ADD, odd).lanewise(VectorOperators.ADD, round));
            var difference = INT128.cast(INT128.cast(even).lanewise(VectorOperators.SUB, odd).lanewise(VectorOperators.ADD, round));
            INT128.cast(sum.lanewise(VectorOperators.ASHR, bdShift)).intoArray(residual, row);
            INT128.cast(INT128.cast(difference.lanewise(VectorOperators.ASHR, bdShift)).rearrange(REVERSE4))
                    .intoArray(residual, row + 4);
        }
    }

    @Override
    public boolean lumaMc(byte[] src, int srcOff, int srcStride, short[] dst, int w, int h, int xFrac, int yFrac,
                          short[] scratch) {
        if ((w & 3) != 0) {
            return scalar.lumaMc(src, srcOff, srcStride, dst, w, h, xFrac, yFrac, scratch);
        }
        var f = LUMA_FILTER;
        if (xFrac == 0 && yFrac == 0) {
            copy(src, srcOff, srcStride, dst, w, h);
            return true;
        }
        if (yFrac == 0) {
            var t = xFrac * 8;
            bytes8(src, srcOff - 3, 1, srcStride, dst, w, h, f[t], f[t + 1], f[t + 2], f[t + 3], f[t + 4], f[t + 5],
                    f[t + 6], f[t + 7]);
            return true;
        }
        if (xFrac == 0) {
            var t = yFrac * 8;
            bytes8(src, srcOff - 3 * srcStride, srcStride, srcStride, dst, w, h, f[t], f[t + 1], f[t + 2], f[t + 3],
                    f[t + 4], f[t + 5], f[t + 6], f[t + 7]);
            return true;
        }
        var t = xFrac * 8;
        bytes8(src, srcOff - 3 * srcStride - 3, 1, srcStride, scratch, w, h + 7, f[t], f[t + 1], f[t + 2], f[t + 3],
                f[t + 4], f[t + 5], f[t + 6], f[t + 7]);
        var v = yFrac * 8;
        var wide = w & ~7;
        var fits = true;
        if (wide > 0) {
            fits = values8(scratch, 0, w, dst, 0, wide, h, f[v], f[v + 1], f[v + 2], f[v + 3], f[v + 4], f[v + 5],
                    f[v + 6], f[v + 7]);
        }
        if ((w & 4) != 0) {
            fits &= values8x4(scratch, wide, w, dst, wide, h, f[v], f[v + 1], f[v + 2], f[v + 3], f[v + 4], f[v + 5],
                    f[v + 6], f[v + 7]);
        }
        return fits;
    }

    @Override
    public boolean chromaMc(byte[] src, int srcOff, int srcStride, short[] dst, int w, int h, int xFrac, int yFrac,
                            short[] scratch) {
        if ((w & 3) != 0) {
            return scalar.chromaMc(src, srcOff, srcStride, dst, w, h, xFrac, yFrac, scratch);
        }
        var f = CHROMA_FILTER;
        if (xFrac == 0 && yFrac == 0) {
            copy(src, srcOff, srcStride, dst, w, h);
            return true;
        }
        if (yFrac == 0) {
            var t = xFrac * 4;
            bytes4(src, srcOff - 1, 1, srcStride, dst, w, h, f[t], f[t + 1], f[t + 2], f[t + 3]);
            return true;
        }
        if (xFrac == 0) {
            var t = yFrac * 4;
            bytes4(src, srcOff - srcStride, srcStride, srcStride, dst, w, h, f[t], f[t + 1], f[t + 2], f[t + 3]);
            return true;
        }
        var t = xFrac * 4;
        bytes4(src, srcOff - srcStride - 1, 1, srcStride, scratch, w, h + 3, f[t], f[t + 1], f[t + 2], f[t + 3]);
        var v = yFrac * 4;
        var wide = w & ~7;
        if (wide > 0) {
            values4(scratch, 0, w, dst, 0, wide, h, f[v], f[v + 1], f[v + 2], f[v + 3]);
        }
        if ((w & 4) != 0) {
            values4x4(scratch, wide, w, dst, wide, h, f[v], f[v + 1], f[v + 2], f[v + 3]);
        }
        return true;
    }

    /**
     * Scales a block of 8-bit samples whose width is a multiple of 4 to the 14-bit intermediate
     * precision: 8 columns at a time, then a remaining group of 4.
     *
     * @param src the source plane
     * @param srcOff the index of the block's top-left sample
     * @param srcStride the source row stride
     * @param dst receives the block, {@code w} values per row
     * @param w the block width, a multiple of 4
     * @param h the block height
     */
    private static void copy(byte[] src, int srcOff, int srcStride, short[] dst, int w, int h) {
        var wide = w & ~7;
        var tail = (w & 4) != 0;
        for (var y = 0; y < h; y++) {
            var row = srcOff + y * srcStride;
            var out = y * w;
            for (var x = 0; x < wide; x += 8) {
                var value = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, row + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
                SHORT128.cast(value.lanewise(VectorOperators.LSHL, 6)).intoArray(dst, out + x);
            }
            if (tail) {
                var value = SHORT64.cast(BYTE64.cast(ByteVector.fromArray(B64, src, row + wide))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S64, 0));
                SHORT64.cast(value.lanewise(VectorOperators.LSHL, 6)).intoArray(dst, out + wide);
            }
        }
    }

    /**
     * Applies an 8-tap filter to a block of 8-bit samples whose width is a multiple of 4, as
     * {@link #taps8} does at bit depth 8.
     *
     * @param src the 8-bit samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination, {@code w} values per row
     * @param w the number of outputs per row, a multiple of 4
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     */
    private static void bytes8(byte[] src, int origin, int step, int srcStride, short[] dst, int w, int rows, int t0,
                               int t1, int t2, int t3, int t4, int t5, int t6, int t7) {
        var wide = w & ~7;
        if ((wide & 15) == 0) {
            if (wide > 0) {
                bytes8x16(src, origin, step, srcStride, dst, w, wide, rows, t0, t1, t2, t3, t4, t5, t6, t7);
            }
        } else {
            bytes8x8(src, origin, step, srcStride, dst, w, wide, rows, t0, t1, t2, t3, t4, t5, t6, t7);
        }
        if ((w & 4) != 0) {
            bytes8x4(src, origin + wide, step, srcStride, dst, wide, w, rows, t0, t1, t2, t3, t4, t5, t6, t7);
        }
    }

    /**
     * Applies a 4-tap filter to a block of 8-bit samples whose width is a multiple of 4, as
     * {@link #taps4} does at bit depth 8.
     *
     * @param src the 8-bit samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination, {@code w} values per row
     * @param w the number of outputs per row, a multiple of 4
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     */
    private static void bytes4(byte[] src, int origin, int step, int srcStride, short[] dst, int w, int rows, int t0,
                               int t1, int t2, int t3) {
        var wide = w & ~7;
        if ((wide & 15) == 0) {
            if (wide > 0) {
                bytes4x16(src, origin, step, srcStride, dst, w, wide, rows, t0, t1, t2, t3);
            }
        } else {
            bytes4x8(src, origin, step, srcStride, dst, w, wide, rows, t0, t1, t2, t3);
        }
        if ((w & 4) != 0) {
            bytes4x4(src, origin + wide, step, srcStride, dst, wide, w, rows, t0, t1, t2, t3);
        }
    }

    @Override
    public void putUni(byte[] dst, int dstOff, int dstStride, short[] pred, int w, int h) {
        if ((h & 1) != 0 || (w & 7) != 0) {
            scalar.putUni(dst, dstOff, dstStride, pred, w, h);
            return;
        }
        if ((w & 15) == 0) {
            putUniBytes16(dst, dstOff, dstStride, pred, w, h);
        } else {
            putUniBytes8(dst, dstOff, dstStride, pred, w, h);
        }
    }

    /**
     * Writes 16 columns at a time of a uni-directional prediction into an 8-bit plane:
     * {@code clip((pred + 32) >> 6)} with a saturating addition.
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred the prediction, {@code w} values per row
     * @param w the block width, a multiple of 16
     * @param h the block height, even
     */
    private static void putUniBytes16(byte[] dst, int dstOff, int dstStride, short[] pred, int w, int h) {
        var offset = SHORT256.cast(ShortVector.broadcast(S256, (short) 32));
        var zero = SHORT256.cast(ShortVector.zero(S256));
        var max = SHORT256.cast(ShortVector.broadcast(S256, (short) 255));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x += 16) {
                var a = SHORT256.cast(ShortVector.fromArray(S256, pred, in + x))
                        .lanewise(VectorOperators.SADD, offset);
                var b = SHORT256.cast(ShortVector.fromArray(S256, pred, in + w + x))
                        .lanewise(VectorOperators.SADD, offset);
                var first = SHORT256.cast(a).lanewise(VectorOperators.ASHR, 6).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max);
                var second = SHORT256.cast(b).lanewise(VectorOperators.ASHR, 6).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max);
                BYTE128.cast(first.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, row + x);
                BYTE128.cast(second.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    /**
     * Writes 8 columns at a time of a uni-directional prediction into an 8-bit plane:
     * {@code clip((pred + 32) >> 6)} with a saturating addition.
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred the prediction, {@code w} values per row
     * @param w the block width, a multiple of 8
     * @param h the block height, even
     */
    private static void putUniBytes8(byte[] dst, int dstOff, int dstStride, short[] pred, int w, int h) {
        var offset = SHORT128.cast(ShortVector.broadcast(S128, (short) 32));
        var zero = SHORT128.cast(ShortVector.zero(S128));
        var max = SHORT128.cast(ShortVector.broadcast(S128, (short) 255));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x += 8) {
                var a = SHORT128.cast(ShortVector.fromArray(S128, pred, in + x))
                        .lanewise(VectorOperators.SADD, offset);
                var b = SHORT128.cast(ShortVector.fromArray(S128, pred, in + w + x))
                        .lanewise(VectorOperators.SADD, offset);
                var first = SHORT128.cast(a).lanewise(VectorOperators.ASHR, 6).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max);
                var second = SHORT128.cast(b).lanewise(VectorOperators.ASHR, 6).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max);
                BYTE64.cast(first.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, row + x);
                BYTE64.cast(second.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    @Override
    public void putBi(byte[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int w, int h) {
        if ((h & 1) != 0 || (w & 7) != 0) {
            scalar.putBi(dst, dstOff, dstStride, pred0, pred1, w, h);
            return;
        }
        if ((w & 15) == 0) {
            putBiBytes16(dst, dstOff, dstStride, pred0, pred1, w, h);
        } else {
            putBiBytes8(dst, dstOff, dstStride, pred0, pred1, w, h);
        }
    }

    /**
     * Writes 16 columns at a time of a bi-directional prediction into an 8-bit plane, with the
     * halved sum of {@link #putBi16}: {@code (h + 32) >> 6} with a saturating addition.
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred0 the list 0 prediction, {@code w} values per row
     * @param pred1 the list 1 prediction, {@code w} values per row
     * @param w the block width, a multiple of 16
     * @param h the block height, even
     */
    private static void putBiBytes16(byte[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int w,
                                     int h) {
        var offset = SHORT256.cast(ShortVector.broadcast(S256, (short) 32));
        var one = SHORT256.cast(ShortVector.broadcast(S256, (short) 1));
        var zero = SHORT256.cast(ShortVector.zero(S256));
        var max = SHORT256.cast(ShortVector.broadcast(S256, (short) 255));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x += 16) {
                var a0 = SHORT256.cast(ShortVector.fromArray(S256, pred0, in + x));
                var a1 = SHORT256.cast(ShortVector.fromArray(S256, pred1, in + x));
                var a = SHORT256.cast(a0.lanewise(VectorOperators.ASHR, 1))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(a1.lanewise(VectorOperators.ASHR, 1)))
                        .lanewise(VectorOperators.ADD, a0.lanewise(VectorOperators.AND, a1)
                                .lanewise(VectorOperators.AND, one))
                        .lanewise(VectorOperators.SADD, offset);
                var b0 = SHORT256.cast(ShortVector.fromArray(S256, pred0, in + w + x));
                var b1 = SHORT256.cast(ShortVector.fromArray(S256, pred1, in + w + x));
                var b = SHORT256.cast(b0.lanewise(VectorOperators.ASHR, 1))
                        .lanewise(VectorOperators.ADD, SHORT256.cast(b1.lanewise(VectorOperators.ASHR, 1)))
                        .lanewise(VectorOperators.ADD, b0.lanewise(VectorOperators.AND, b1)
                                .lanewise(VectorOperators.AND, one))
                        .lanewise(VectorOperators.SADD, offset);
                var first = SHORT256.cast(a).lanewise(VectorOperators.ASHR, 6).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max);
                var second = SHORT256.cast(b).lanewise(VectorOperators.ASHR, 6).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max);
                BYTE128.cast(first.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, row + x);
                BYTE128.cast(second.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    /**
     * Writes 8 columns at a time of a bi-directional prediction into an 8-bit plane, with the
     * halved sum of {@link #putBi8}: {@code (h + 32) >> 6} with a saturating addition.
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred0 the list 0 prediction, {@code w} values per row
     * @param pred1 the list 1 prediction, {@code w} values per row
     * @param w the block width, a multiple of 8
     * @param h the block height, even
     */
    private static void putBiBytes8(byte[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int w,
                                    int h) {
        var offset = SHORT128.cast(ShortVector.broadcast(S128, (short) 32));
        var one = SHORT128.cast(ShortVector.broadcast(S128, (short) 1));
        var zero = SHORT128.cast(ShortVector.zero(S128));
        var max = SHORT128.cast(ShortVector.broadcast(S128, (short) 255));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x += 8) {
                var a0 = SHORT128.cast(ShortVector.fromArray(S128, pred0, in + x));
                var a1 = SHORT128.cast(ShortVector.fromArray(S128, pred1, in + x));
                var a = SHORT128.cast(a0.lanewise(VectorOperators.ASHR, 1))
                        .lanewise(VectorOperators.ADD, SHORT128.cast(a1.lanewise(VectorOperators.ASHR, 1)))
                        .lanewise(VectorOperators.ADD, a0.lanewise(VectorOperators.AND, a1)
                                .lanewise(VectorOperators.AND, one))
                        .lanewise(VectorOperators.SADD, offset);
                var b0 = SHORT128.cast(ShortVector.fromArray(S128, pred0, in + w + x));
                var b1 = SHORT128.cast(ShortVector.fromArray(S128, pred1, in + w + x));
                var b = SHORT128.cast(b0.lanewise(VectorOperators.ASHR, 1))
                        .lanewise(VectorOperators.ADD, SHORT128.cast(b1.lanewise(VectorOperators.ASHR, 1)))
                        .lanewise(VectorOperators.ADD, b0.lanewise(VectorOperators.AND, b1)
                                .lanewise(VectorOperators.AND, one))
                        .lanewise(VectorOperators.SADD, offset);
                var first = SHORT128.cast(a).lanewise(VectorOperators.ASHR, 6).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max);
                var second = SHORT128.cast(b).lanewise(VectorOperators.ASHR, 6).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, max);
                BYTE64.cast(first.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, row + x);
                BYTE64.cast(second.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    @Override
    public void putWeightedUni(byte[] dst, int dstOff, int dstStride, short[] pred, int w, int h, int weight,
                               int offset, int log2Wd) {
        if ((w & 7) != 0 || (h & 1) != 0) {
            scalar.putWeightedUni(dst, dstOff, dstStride, pred, w, h, weight, offset, log2Wd);
            return;
        }
        var factor = INT256.cast(IntVector.broadcast(I256, weight));
        var round = INT256.cast(IntVector.broadcast(I256, 1 << (log2Wd - 1)));
        var add = INT256.cast(IntVector.broadcast(I256, offset));
        var zero = INT256.cast(IntVector.zero(I256));
        var max = INT256.cast(IntVector.broadcast(I256, 255));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x += 8) {
                var a = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, pred, in + x))
                        .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.MUL, factor)
                        .lanewise(VectorOperators.ADD, round);
                var b = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, pred, in + w + x))
                        .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.MUL, factor)
                        .lanewise(VectorOperators.ADD, round);
                var first = INT256.cast(a).lanewise(VectorOperators.ASHR, log2Wd).lanewise(VectorOperators.ADD, add)
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, max);
                var second = INT256.cast(b).lanewise(VectorOperators.ASHR, log2Wd).lanewise(VectorOperators.ADD, add)
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, max);
                BYTE64.cast(first.convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, row + x);
                BYTE64.cast(second.convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    @Override
    public void putWeightedBi(byte[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int w, int h,
                              int weight0, int weight1, int offsets, int log2Wd) {
        if ((w & 7) != 0 || (h & 1) != 0) {
            scalar.putWeightedBi(dst, dstOff, dstStride, pred0, pred1, w, h, weight0, weight1, offsets, log2Wd);
            return;
        }
        var factor0 = INT256.cast(IntVector.broadcast(I256, weight0));
        var factor1 = INT256.cast(IntVector.broadcast(I256, weight1));
        var round = INT256.cast(IntVector.broadcast(I256, (offsets + 1) << log2Wd));
        var shift = log2Wd + 1;
        var zero = INT256.cast(IntVector.zero(I256));
        var max = INT256.cast(IntVector.broadcast(I256, 255));
        for (var y = 0; y < h; y += 2) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x += 8) {
                var a0 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, pred0, in + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var a1 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, pred1, in + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var a = a0.lanewise(VectorOperators.MUL, factor0)
                        .lanewise(VectorOperators.ADD, a1.lanewise(VectorOperators.MUL, factor1))
                        .lanewise(VectorOperators.ADD, round);
                var b0 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, pred0, in + w + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var b1 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, pred1, in + w + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var b = b0.lanewise(VectorOperators.MUL, factor0)
                        .lanewise(VectorOperators.ADD, b1.lanewise(VectorOperators.MUL, factor1))
                        .lanewise(VectorOperators.ADD, round);
                var first = INT256.cast(a).lanewise(VectorOperators.ASHR, shift)
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, max);
                var second = INT256.cast(b).lanewise(VectorOperators.ASHR, shift)
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, max);
                BYTE64.cast(first.convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, row + x);
                BYTE64.cast(second.convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, row + dstStride + x);
            }
        }
    }

    @Override
    public void saoEdge(byte[] dst, byte[] src, int offset, int stride, int w, int h, int deltaA, int deltaB,
                        int[] lut) {
        if (w < 16) {
            scalar.saoEdge(dst, src, offset, stride, w, h, deltaA, deltaB, lut);
            return;
        }
        var zero = SHORT256.cast(ShortVector.zero(S256));
        var one = SHORT256.cast(ShortVector.broadcast(S256, (short) 1));
        var minusOne = SHORT256.cast(ShortVector.broadcast(S256, (short) -1));
        var minusTwo = SHORT256.cast(ShortVector.broadcast(S256, (short) -2));
        var two = SHORT256.cast(ShortVector.broadcast(S256, (short) 2));
        var l0 = SHORT256.cast(ShortVector.broadcast(S256, (short) lut[0]));
        var l1 = SHORT256.cast(ShortVector.broadcast(S256, (short) lut[1]));
        var l3 = SHORT256.cast(ShortVector.broadcast(S256, (short) lut[3]));
        var l4 = SHORT256.cast(ShortVector.broadcast(S256, (short) lut[4]));
        var high = SHORT256.cast(ShortVector.broadcast(S256, (short) 255));
        for (var y = 0; y < h; y++) {
            var start = offset + y * stride;
            for (var x = 0; x < w; x += 16) {
                var p = start + Math.min(x, w - 16);
                var value = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var a = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p + deltaA))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var b = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p + deltaB))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var signA = SHORT256.cast(zero).blend(one, value.compare(VectorOperators.GT, a))
                        .blend(minusOne, value.compare(VectorOperators.LT, a));
                var signB = SHORT256.cast(zero).blend(one, value.compare(VectorOperators.GT, b))
                        .blend(minusOne, value.compare(VectorOperators.LT, b));
                var sum = signA.lanewise(VectorOperators.ADD, signB);
                var add = SHORT256.cast(zero).blend(l0, sum.compare(VectorOperators.EQ, minusTwo))
                        .blend(l1, sum.compare(VectorOperators.EQ, minusOne))
                        .blend(l3, sum.compare(VectorOperators.EQ, one))
                        .blend(l4, sum.compare(VectorOperators.EQ, two));
                var result = value.lanewise(VectorOperators.ADD, add).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, high);
                BYTE128.cast(SHORT256.cast(result).convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, p);
            }
        }
    }

    @Override
    public void saoBand(byte[] dst, byte[] src, int offset, int stride, int w, int h, int position, int o0, int o1,
                        int o2, int o3) {
        if (w < 16) {
            scalar.saoBand(dst, src, offset, stride, w, h, position, o0, o1, o2, o3);
            return;
        }
        var zero = SHORT256.cast(ShortVector.zero(S256));
        var one = SHORT256.cast(ShortVector.broadcast(S256, (short) 1));
        var two = SHORT256.cast(ShortVector.broadcast(S256, (short) 2));
        var three = SHORT256.cast(ShortVector.broadcast(S256, (short) 3));
        var start = SHORT256.cast(ShortVector.broadcast(S256, (short) position));
        var mask = SHORT256.cast(ShortVector.broadcast(S256, (short) 31));
        var b0 = SHORT256.cast(ShortVector.broadcast(S256, (short) o0));
        var b1 = SHORT256.cast(ShortVector.broadcast(S256, (short) o1));
        var b2 = SHORT256.cast(ShortVector.broadcast(S256, (short) o2));
        var b3 = SHORT256.cast(ShortVector.broadcast(S256, (short) o3));
        var high = SHORT256.cast(ShortVector.broadcast(S256, (short) 255));
        for (var y = 0; y < h; y++) {
            var row = offset + y * stride;
            for (var x = 0; x < w; x += 16) {
                var p = row + Math.min(x, w - 16);
                var value = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var band = SHORT256.cast(value).lanewise(VectorOperators.ASHR, 3)
                        .lanewise(VectorOperators.SUB, start).lanewise(VectorOperators.AND, mask);
                var add = SHORT256.cast(zero).blend(b0, band.compare(VectorOperators.EQ, zero))
                        .blend(b1, band.compare(VectorOperators.EQ, one))
                        .blend(b2, band.compare(VectorOperators.EQ, two))
                        .blend(b3, band.compare(VectorOperators.EQ, three));
                var result = value.lanewise(VectorOperators.ADD, add).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, high);
                BYTE128.cast(SHORT256.cast(result).convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, p);
            }
        }
    }

    @Override
    public void addConstant(byte[] plane, int offset, int stride, int size, int value) {
        if (size < 8) {
            scalar.addConstant(plane, offset, stride, size, value);
            return;
        }
        if (size == 8) {
            var add = SHORT128.cast(ShortVector.broadcast(S128, (short) value));
            var zero = SHORT128.cast(ShortVector.zero(S128));
            var high = SHORT128.cast(ShortVector.broadcast(S128, (short) 255));
            for (var y = 0; y < 8; y++) {
                var row = offset + y * stride;
                var a = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, plane, row))
                                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0))
                        .lanewise(VectorOperators.ADD, add).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, high);
                BYTE64.cast(SHORT128.cast(a).convertShape(VectorOperators.S2B, B64, 0)).intoArray(plane, row);
            }
            return;
        }
        var add = SHORT256.cast(ShortVector.broadcast(S256, (short) value));
        var zero = SHORT256.cast(ShortVector.zero(S256));
        var high = SHORT256.cast(ShortVector.broadcast(S256, (short) 255));
        for (var y = 0; y < size; y++) {
            var row = offset + y * stride;
            for (var x = 0; x < size; x += 16) {
                var a = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, plane, row + x))
                                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                        .lanewise(VectorOperators.ADD, add).lanewise(VectorOperators.MAX, zero)
                        .lanewise(VectorOperators.MIN, high);
                BYTE128.cast(SHORT256.cast(a).convertShape(VectorOperators.S2B, B128, 0)).intoArray(plane, row + x);
            }
        }
    }

    @Override
    public void addResidual(byte[] plane, int offset, int stride, int[] residual, int size) {
        if (size < 8) {
            scalar.addResidual(plane, offset, stride, residual, size);
            return;
        }
        var zero = INT256.cast(IntVector.zero(I256));
        var high = INT256.cast(IntVector.broadcast(I256, 255));
        for (var y = 0; y < size; y += 2) {
            var row = offset + y * stride;
            var in = y * size;
            for (var x = 0; x < size; x += 8) {
                var a = INT256.cast(BYTE64.cast(ByteVector.fromArray(B64, plane, row + x))
                                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0))
                        .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, residual, in + x)))
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, high);
                var b = INT256.cast(BYTE64.cast(ByteVector.fromArray(B64, plane, row + stride + x))
                                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0))
                        .lanewise(VectorOperators.ADD, INT256.cast(IntVector.fromArray(I256, residual, in + size + x)))
                        .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, high);
                BYTE64.cast(INT256.cast(a).convertShape(VectorOperators.I2B, B64, 0)).intoArray(plane, row + x);
                BYTE64.cast(INT256.cast(b).convertShape(VectorOperators.I2B, B64, 0))
                        .intoArray(plane, row + stride + x);
            }
        }
    }

    /**
     * Applies an 8-tap filter to a block of 8-bit samples, 16 outputs at a time in 16-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step])}, which fits 16 bits.
     *
     * @param src the 8-bit samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 16, from the first
     * column
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     */
    private static void bytes8x16(byte[] src, int origin, int step, int srcStride, short[] dst, int dstStride,
                                  int w, int rows, int t0, int t1, int t2, int t3, int t4, int t5, int t6, int t7) {
        var c0 = SHORT256.cast(ShortVector.broadcast(S256, (short) t0));
        var c1 = SHORT256.cast(ShortVector.broadcast(S256, (short) t1));
        var c2 = SHORT256.cast(ShortVector.broadcast(S256, (short) t2));
        var c3 = SHORT256.cast(ShortVector.broadcast(S256, (short) t3));
        var c4 = SHORT256.cast(ShortVector.broadcast(S256, (short) t4));
        var c5 = SHORT256.cast(ShortVector.broadcast(S256, (short) t5));
        var c6 = SHORT256.cast(ShortVector.broadcast(S256, (short) t6));
        var c7 = SHORT256.cast(ShortVector.broadcast(S256, (short) t7));
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = y * dstStride;
            for (var x = 0; x < w; x += 16) {
                var p = row + x;
                var s0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var s1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p + step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var s2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p + 2 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var s3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p + 3 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var s4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p + 4 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var s5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p + 5 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var s6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p + 6 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var s7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p + 7 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var sum = s0.lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, c3))
                        .lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, c4))
                        .lanewise(VectorOperators.ADD, s5.lanewise(VectorOperators.MUL, c5))
                        .lanewise(VectorOperators.ADD, s6.lanewise(VectorOperators.MUL, c6))
                        .lanewise(VectorOperators.ADD, s7.lanewise(VectorOperators.MUL, c7));
                SHORT256.cast(sum).intoArray(dst, out + x);
            }
        }
    }

    /**
     * Applies an 8-tap filter to a block of 8-bit samples, 8 outputs at a time in 16-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step])}, which fits 16 bits.
     *
     * @param src the 8-bit samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 8, from the first
     * column
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     * @param t4 the fifth tap
     * @param t5 the sixth tap
     * @param t6 the seventh tap
     * @param t7 the eighth tap
     */
    private static void bytes8x8(byte[] src, int origin, int step, int srcStride, short[] dst, int dstStride,
                                 int w, int rows, int t0, int t1, int t2, int t3, int t4, int t5, int t6, int t7) {
        var c0 = SHORT128.cast(ShortVector.broadcast(S128, (short) t0));
        var c1 = SHORT128.cast(ShortVector.broadcast(S128, (short) t1));
        var c2 = SHORT128.cast(ShortVector.broadcast(S128, (short) t2));
        var c3 = SHORT128.cast(ShortVector.broadcast(S128, (short) t3));
        var c4 = SHORT128.cast(ShortVector.broadcast(S128, (short) t4));
        var c5 = SHORT128.cast(ShortVector.broadcast(S128, (short) t5));
        var c6 = SHORT128.cast(ShortVector.broadcast(S128, (short) t6));
        var c7 = SHORT128.cast(ShortVector.broadcast(S128, (short) t7));
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = y * dstStride;
            for (var x = 0; x < w; x += 8) {
                var p = row + x;
                var s0 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
                var s1 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
                var s2 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 2 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
                var s3 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 3 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
                var s4 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 4 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
                var s5 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 5 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
                var s6 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 6 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
                var s7 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 7 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
                var sum = s0.lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, c3))
                        .lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, c4))
                        .lanewise(VectorOperators.ADD, s5.lanewise(VectorOperators.MUL, c5))
                        .lanewise(VectorOperators.ADD, s6.lanewise(VectorOperators.MUL, c6))
                        .lanewise(VectorOperators.ADD, s7.lanewise(VectorOperators.MUL, c7));
                SHORT128.cast(sum).intoArray(dst, out + x);
            }
        }
    }

    /**
     * Applies an 8-tap filter to a block of 8-bit samples, one group of 4 columns in 16-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step])}, which fits 16 bits.
     *
     * @param src the 8-bit samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
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
     */
    private static void bytes8x4(byte[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                                 int dstStride, int rows, int t0, int t1, int t2, int t3,
                                 int t4, int t5, int t6, int t7) {
        var c0 = SHORT64.cast(ShortVector.broadcast(S64, (short) t0));
        var c1 = SHORT64.cast(ShortVector.broadcast(S64, (short) t1));
        var c2 = SHORT64.cast(ShortVector.broadcast(S64, (short) t2));
        var c3 = SHORT64.cast(ShortVector.broadcast(S64, (short) t3));
        var c4 = SHORT64.cast(ShortVector.broadcast(S64, (short) t4));
        var c5 = SHORT64.cast(ShortVector.broadcast(S64, (short) t5));
        var c6 = SHORT64.cast(ShortVector.broadcast(S64, (short) t6));
        var c7 = SHORT64.cast(ShortVector.broadcast(S64, (short) t7));
        for (var y = 0; y < rows; y++) {
            var p = origin + y * srcStride;
            var s0 = SHORT64.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S64, 0));
            var s1 = SHORT64.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S64, 0));
            var s2 = SHORT64.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 2 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S64, 0));
            var s3 = SHORT64.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 3 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S64, 0));
            var s4 = SHORT64.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 4 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S64, 0));
            var s5 = SHORT64.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 5 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S64, 0));
            var s6 = SHORT64.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 6 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S64, 0));
            var s7 = SHORT64.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 7 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S64, 0));
            var sum = s0.lanewise(VectorOperators.MUL, c0)
                    .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, c1))
                    .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, c2))
                    .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, c3))
                    .lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, c4))
                    .lanewise(VectorOperators.ADD, s5.lanewise(VectorOperators.MUL, c5))
                    .lanewise(VectorOperators.ADD, s6.lanewise(VectorOperators.MUL, c6))
                    .lanewise(VectorOperators.ADD, s7.lanewise(VectorOperators.MUL, c7));
            SHORT64.cast(sum).intoArray(dst, dstOff + y * dstStride);
        }
    }

    /**
     * Applies a 4-tap filter to a block of 8-bit samples, 16 outputs at a time in 16-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step])}, which fits 16 bits.
     *
     * @param src the 8-bit samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 16, from the first
     * column
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     */
    private static void bytes4x16(byte[] src, int origin, int step, int srcStride, short[] dst, int dstStride,
                                  int w, int rows, int t0, int t1, int t2, int t3) {
        var c0 = SHORT256.cast(ShortVector.broadcast(S256, (short) t0));
        var c1 = SHORT256.cast(ShortVector.broadcast(S256, (short) t1));
        var c2 = SHORT256.cast(ShortVector.broadcast(S256, (short) t2));
        var c3 = SHORT256.cast(ShortVector.broadcast(S256, (short) t3));
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = y * dstStride;
            for (var x = 0; x < w; x += 16) {
                var p = row + x;
                var s0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var s1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p + step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var s2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p + 2 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var s3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, p + 3 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
                var sum = s0.lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, c3));
                SHORT256.cast(sum).intoArray(dst, out + x);
            }
        }
    }

    /**
     * Applies a 4-tap filter to a block of 8-bit samples, 8 outputs at a time in 16-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step])}, which fits 16 bits.
     *
     * @param src the 8-bit samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstStride the destination row stride
     * @param w the number of outputs per row, a multiple of 8, from the first
     * column
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     */
    private static void bytes4x8(byte[] src, int origin, int step, int srcStride, short[] dst, int dstStride,
                                 int w, int rows, int t0, int t1, int t2, int t3) {
        var c0 = SHORT128.cast(ShortVector.broadcast(S128, (short) t0));
        var c1 = SHORT128.cast(ShortVector.broadcast(S128, (short) t1));
        var c2 = SHORT128.cast(ShortVector.broadcast(S128, (short) t2));
        var c3 = SHORT128.cast(ShortVector.broadcast(S128, (short) t3));
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = y * dstStride;
            for (var x = 0; x < w; x += 8) {
                var p = row + x;
                var s0 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
                var s1 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
                var s2 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 2 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
                var s3 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 3 * step))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
                var sum = s0.lanewise(VectorOperators.MUL, c0)
                        .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, c1))
                        .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, c2))
                        .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, c3));
                SHORT128.cast(sum).intoArray(dst, out + x);
            }
        }
    }

    /**
     * Applies a 4-tap filter to a block of 8-bit samples, one group of 4 columns in 16-bit lanes:
     * {@code dst = sum(t[k] * src[origin + k * step])}, which fits 16 bits.
     *
     * @param src the 8-bit samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param rows the number of rows
     * @param t0 the first tap
     * @param t1 the second tap
     * @param t2 the third tap
     * @param t3 the fourth tap
     */
    private static void bytes4x4(byte[] src, int origin, int step, int srcStride, short[] dst, int dstOff,
                                 int dstStride, int rows, int t0, int t1, int t2, int t3) {
        var c0 = SHORT64.cast(ShortVector.broadcast(S64, (short) t0));
        var c1 = SHORT64.cast(ShortVector.broadcast(S64, (short) t1));
        var c2 = SHORT64.cast(ShortVector.broadcast(S64, (short) t2));
        var c3 = SHORT64.cast(ShortVector.broadcast(S64, (short) t3));
        for (var y = 0; y < rows; y++) {
            var p = origin + y * srcStride;
            var s0 = SHORT64.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S64, 0));
            var s1 = SHORT64.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S64, 0));
            var s2 = SHORT64.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 2 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S64, 0));
            var s3 = SHORT64.cast(BYTE64.cast(ByteVector.fromArray(B64, src, p + 3 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S64, 0));
            var sum = s0.lanewise(VectorOperators.MUL, c0)
                    .lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, c1))
                    .lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, c2))
                    .lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, c3));
            SHORT64.cast(sum).intoArray(dst, dstOff + y * dstStride);
        }
    }
}
