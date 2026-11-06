package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.internal.simd.Simd;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link H264WideDsp} with the Vector API; the output equals {@link ScalarH264WideDsp}
 * sample for sample.
 *
 * <p>Blocks narrower than the vectors fall back to the scalar kernels, and so do the methods
 * written for 256-bit vectors unless {@link Simd#WIDE} holds.
 *
 * @implNote This implementation follows the kernel rules of
 * {@link com.github.auties00.smalt.internal.simd.Simd Simd}; the interpolation filters run in
 * 32-bit lanes, eight samples at a time, because the 6-tap sums of samples deeper than 9 bits
 * exceed 16 bits, while the averages run in 16-bit lanes, whose sums of two samples of up to 14
 * bits plus one still fit.
 */
final class VectorH264WideDsp extends ScalarH264WideDsp {
    // TODO: vectorize weighted prediction, the inverse transforms and the deblocking edge filters, which still run
    //  the scalar kernels

    /**
     * Eight int lanes.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * Four int lanes.
     */
    private static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;

    /**
     * Eight short lanes.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * Four short lanes.
     */
    private static final VectorSpecies<Short> S64 = ShortVector.SPECIES_64;

    /**
     * The exact class of eight-int vectors.
     */
    private static final Class<IntVector> INTS_8 = vectorClass(I256);

    /**
     * The exact class of four-int vectors.
     */
    private static final Class<IntVector> INTS_4 = vectorClass(I128);

    /**
     * The exact class of eight-short vectors.
     */
    private static final Class<ShortVector> SHORTS_8 = vectorClass(S128);

    /**
     * The exact class of four-short vectors.
     */
    private static final Class<ShortVector> SHORTS_4 = vectorClass(S64);

    /**
     * Eight ints equal to {@code 0}.
     */
    private static final IntVector I256_0 = IntVector.broadcast(I256, 0);

    /**
     * Eight ints equal to {@code 5}.
     */
    private static final IntVector I256_5 = IntVector.broadcast(I256, 5);

    /**
     * Eight ints equal to {@code 16}.
     */
    private static final IntVector I256_16 = IntVector.broadcast(I256, 16);

    /**
     * Eight ints equal to {@code 20}.
     */
    private static final IntVector I256_20 = IntVector.broadcast(I256, 20);

    /**
     * Eight ints equal to {@code 1}.
     */
    private static final IntVector I256_1 = IntVector.broadcast(I256, 1);

    /**
     * Eight ints equal to {@code 32}.
     */
    private static final IntVector I256_32 = IntVector.broadcast(I256, 32);

    /**
     * Eight ints equal to {@code 512}.
     */
    private static final IntVector I256_512 = IntVector.broadcast(I256, 512);

    /**
     * Four ints equal to {@code 32}.
     */
    private static final IntVector I128_32 = IntVector.broadcast(I128, 32);

    /**
     * Eight shorts equal to {@code 1}.
     */
    private static final ShortVector S128_1 = ShortVector.broadcast(S128, (short) 1);

    /**
     * Four shorts equal to {@code 1}.
     */
    private static final ShortVector S64_1 = ShortVector.broadcast(S64, (short) 1);

    /**
     * Constructs the Vector API kernel set.
     */
    VectorH264WideDsp() {
    }

    /**
     * Returns the vector class of a species, for exact-type casts.
     *
     * @param species the vector shape
     * @param <V> the vector type
     * @return the species' vector class
     */
    @SuppressWarnings("unchecked")
    private static <V> Class<V> vectorClass(VectorSpecies<?> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void lumaMc(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride,
                       int width, int height, int fracX, int fracY, int[] sums, short[] scratch, int max) {
        if (!Simd.WIDE) {
            super.lumaMc(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, fracX, fracY, sums,
                    scratch, max);
            return;
        }
        if (width < 8) {
            super.lumaMc(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, fracX, fracY, sums,
                    scratch, max);
            return;
        }
        switch ((fracY << 2) | fracX) {
            case 0 -> {
                for (var y = 0; y < height; y++) {
                    System.arraycopy(src, srcOffset + y * srcStride, dst, dstOffset + y * dstStride, width);
                }
            }
            case 1 -> horizontalQuarter(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, 0, max);
            case 2 -> horizontal(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, max);
            case 3 -> horizontalQuarter(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, 1, max);
            case 4 -> verticalQuarter(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, 0, max);
            case 8 -> vertical(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, max);
            case 12 -> verticalQuarter(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, srcStride,
                    max);
            case 10 -> {
                centerSums(src, srcOffset, srcStride, width, height, sums);
                center(sums, dst, dstOffset, dstStride, width, height, max);
            }
            case 6, 14 -> {
                centerSums(src, srcOffset, srcStride, width, height, sums);
                center(sums, scratch, 0, 16, width, height, max);
                horizontal(src, fracY == 1 ? srcOffset : srcOffset + srcStride, srcStride, scratch, 256, 16, width,
                        height, max);
                averageRows(scratch, dst, dstOffset, dstStride, width, height);
            }
            case 9, 11 -> {
                centerSums(src, srcOffset, srcStride, width, height, sums);
                center(sums, scratch, 0, 16, width, height, max);
                vertical(src, fracX == 1 ? srcOffset : srcOffset + 1, srcStride, scratch, 256, 16, width, height, max);
                averageRows(scratch, dst, dstOffset, dstStride, width, height);
            }
            default -> {
                horizontal(src, fracY == 1 ? srcOffset : srcOffset + srcStride, srcStride, scratch, 0, 16, width,
                        height, max);
                vertical(src, fracX == 1 ? srcOffset : srcOffset + 1, srcStride, scratch, 256, 16, width, height, max);
                averageRows(scratch, dst, dstOffset, dstStride, width, height);
            }
        }
    }

    /**
     * Applies the horizontal half-sample filter, eight samples at a time.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param width the block width, 8 or 16
     * @param height the block height
     * @param max the largest sample value
     */
    private static void horizontal(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset,
                                   int dstStride, int width, int height, int max) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x += 8) {
                var s = srcOffset + y * srcStride + x;
                var a = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s - 2)).convertShape(VectorOperators.S2I, I256, 0));
                var b = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s - 1)).convertShape(VectorOperators.S2I, I256, 0));
                var c = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s)).convertShape(VectorOperators.S2I, I256, 0));
                var d = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + 1)).convertShape(VectorOperators.S2I, I256, 0));
                var e = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + 2)).convertShape(VectorOperators.S2I, I256, 0));
                var f = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + 3)).convertShape(VectorOperators.S2I, I256, 0));
                var limit = IntVector.broadcast(I256, max);
                var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, I256_5))
                        .lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, I256_20))
                        .lanewise(VectorOperators.ADD, I256_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, I256_0)
                        .lanewise(VectorOperators.MIN, limit);
                SHORTS_8.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, dstOffset + y * dstStride + x);
            }
        }
    }

    /**
     * Interpolates a horizontal quarter-sample position: the horizontal half samples averaged
     * with the integer samples of one column offset.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param width the block width, 8 or 16
     * @param height the block height
     * @param integer the column offset (0 or 1) of the integer samples
     * @param max the largest sample value
     */
    private static void horizontalQuarter(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset,
                                          int dstStride, int width, int height, int integer, int max) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x += 8) {
                var s = srcOffset + y * srcStride + x;
                var a = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s - 2)).convertShape(VectorOperators.S2I, I256, 0));
                var b = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s - 1)).convertShape(VectorOperators.S2I, I256, 0));
                var c = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s)).convertShape(VectorOperators.S2I, I256, 0));
                var d = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + 1)).convertShape(VectorOperators.S2I, I256, 0));
                var e = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + 2)).convertShape(VectorOperators.S2I, I256, 0));
                var f = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + 3)).convertShape(VectorOperators.S2I, I256, 0));
                var sample = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + integer)).convertShape(VectorOperators.S2I, I256, 0));
                var limit = IntVector.broadcast(I256, max);
                var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, I256_5))
                        .lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, I256_20))
                        .lanewise(VectorOperators.ADD, I256_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, I256_0)
                        .lanewise(VectorOperators.MIN, limit).lanewise(VectorOperators.ADD, sample).lanewise(VectorOperators.ADD, I256_1)
                        .lanewise(VectorOperators.ASHR, 1);
                SHORTS_8.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, dstOffset + y * dstStride + x);
            }
        }
    }

    /**
     * Applies the vertical half-sample filter, eight samples at a time.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param width the block width, 8 or 16
     * @param height the block height
     * @param max the largest sample value
     */
    private static void vertical(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset,
                                 int dstStride, int width, int height, int max) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x += 8) {
                var s = srcOffset + y * srcStride + x;
                var a = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s - 2 * srcStride)).convertShape(VectorOperators.S2I, I256, 0));
                var b = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s - srcStride)).convertShape(VectorOperators.S2I, I256, 0));
                var c = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s)).convertShape(VectorOperators.S2I, I256, 0));
                var d = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + srcStride)).convertShape(VectorOperators.S2I, I256, 0));
                var e = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + 2 * srcStride)).convertShape(VectorOperators.S2I, I256, 0));
                var f = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + 3 * srcStride)).convertShape(VectorOperators.S2I, I256, 0));
                var limit = IntVector.broadcast(I256, max);
                var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, I256_5))
                        .lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, I256_20))
                        .lanewise(VectorOperators.ADD, I256_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, I256_0)
                        .lanewise(VectorOperators.MIN, limit);
                SHORTS_8.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, dstOffset + y * dstStride + x);
            }
        }
    }

    /**
     * Interpolates a vertical quarter-sample position: the vertical half samples averaged with
     * the integer samples of one row offset.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param width the block width, 8 or 16
     * @param height the block height
     * @param integer the index offset (0 or {@code srcStride}) of the integer samples
     * @param max the largest sample value
     */
    private static void verticalQuarter(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset,
                                        int dstStride, int width, int height, int integer, int max) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x += 8) {
                var s = srcOffset + y * srcStride + x;
                var a = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s - 2 * srcStride)).convertShape(VectorOperators.S2I, I256, 0));
                var b = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s - srcStride)).convertShape(VectorOperators.S2I, I256, 0));
                var c = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s)).convertShape(VectorOperators.S2I, I256, 0));
                var d = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + srcStride)).convertShape(VectorOperators.S2I, I256, 0));
                var e = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + 2 * srcStride)).convertShape(VectorOperators.S2I, I256, 0));
                var f = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + 3 * srcStride)).convertShape(VectorOperators.S2I, I256, 0));
                var sample = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + integer)).convertShape(VectorOperators.S2I, I256, 0));
                var limit = IntVector.broadcast(I256, max);
                var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, I256_5))
                        .lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, I256_20))
                        .lanewise(VectorOperators.ADD, I256_16).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.MAX, I256_0)
                        .lanewise(VectorOperators.MIN, limit).lanewise(VectorOperators.ADD, sample).lanewise(VectorOperators.ADD, I256_1)
                        .lanewise(VectorOperators.ASHR, 1);
                SHORTS_8.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, dstOffset + y * dstStride + x);
            }
        }
    }

    /**
     * Computes the unrounded horizontal 6-tap sums of the {@code height + 5} rows the center
     * half-sample filter reads, {@code width} per row.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param width the block width, 8 or 16
     * @param height the block height
     * @param sums receives the sums
     */
    private static void centerSums(short[] src, int srcOffset, int srcStride, int width, int height, int[] sums) {
        var first = srcOffset - 2 * srcStride;
        for (var row = 0; row < height + 5; row++) {
            for (var x = 0; x < width; x += 8) {
                var s = first + row * srcStride + x;
                var a = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s - 2)).convertShape(VectorOperators.S2I, I256, 0));
                var b = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s - 1)).convertShape(VectorOperators.S2I, I256, 0));
                var c = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s)).convertShape(VectorOperators.S2I, I256, 0));
                var d = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + 1)).convertShape(VectorOperators.S2I, I256, 0));
                var e = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + 2)).convertShape(VectorOperators.S2I, I256, 0));
                var f = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + 3)).convertShape(VectorOperators.S2I, I256, 0));
                var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, I256_5))
                        .lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, I256_20));
                INTS_8.cast(value).intoArray(sums, row * width + x);
            }
        }
    }

    /**
     * Filters the horizontal sums of {@link #centerSums} vertically into the center half
     * samples.
     *
     * @param sums the horizontal sums, {@code width} per row
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param width the block width, 8 or 16
     * @param height the block height
     * @param max the largest sample value
     */
    private static void center(int[] sums, short[] dst, int dstOffset, int dstStride, int width, int height, int max) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x += 8) {
                var r = y * width + x;
                var a = INTS_8.cast(IntVector.fromArray(I256, sums, r));
                var b = INTS_8.cast(IntVector.fromArray(I256, sums, r + width));
                var c = INTS_8.cast(IntVector.fromArray(I256, sums, r + 2 * width));
                var d = INTS_8.cast(IntVector.fromArray(I256, sums, r + 3 * width));
                var e = INTS_8.cast(IntVector.fromArray(I256, sums, r + 4 * width));
                var f = INTS_8.cast(IntVector.fromArray(I256, sums, r + 5 * width));
                var limit = IntVector.broadcast(I256, max);
                var value = a.lanewise(VectorOperators.ADD, f).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.ADD, e).lanewise(VectorOperators.MUL, I256_5))
                        .lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.ADD, d).lanewise(VectorOperators.MUL, I256_20))
                        .lanewise(VectorOperators.ADD, I256_512).lanewise(VectorOperators.ASHR, 10).lanewise(VectorOperators.MAX, I256_0)
                        .lanewise(VectorOperators.MIN, limit);
                SHORTS_8.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, dstOffset + y * dstStride + x);
            }
        }
    }

    /**
     * Writes {@code (a + b + 1) >> 1} of the two scratch blocks of stride 16 at indices 0 and 256.
     *
     * @param scratch the two blocks
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param width the block width, 8 or 16
     * @param height the block height
     */
    private static void averageRows(short[] scratch, short[] dst, int dstOffset, int dstStride, int width,
                                    int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x += 8) {
                var a = SHORTS_8.cast(ShortVector.fromArray(S128, scratch, y * 16 + x));
                var b = SHORTS_8.cast(ShortVector.fromArray(S128, scratch, 256 + y * 16 + x));
                SHORTS_8.cast(a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.ASHR, 1))
                        .intoArray(dst, dstOffset + y * dstStride + x);
            }
        }
    }

    @Override
    public void chromaMc(short[] srcCb, short[] srcCr, int srcOffset, int srcStride, short[] dstCb, short[] dstCr,
                         int dstOffset, int dstStride, int width, int height, int fracX, int fracY) {
        if (!Simd.WIDE) {
            super.chromaMc(srcCb, srcCr, srcOffset, srcStride, dstCb, dstCr, dstOffset, dstStride, width, height,
                    fracX, fracY);
            return;
        }
        if (width == 8) {
            chroma8(srcCb, srcOffset, srcStride, dstCb, dstOffset, dstStride, height, fracX, fracY);
            chroma8(srcCr, srcOffset, srcStride, dstCr, dstOffset, dstStride, height, fracX, fracY);
        } else if (width == 4) {
            chroma4(srcCb, srcOffset, srcStride, dstCb, dstOffset, dstStride, height, fracX, fracY);
            chroma4(srcCr, srcOffset, srcStride, dstCr, dstOffset, dstStride, height, fracX, fracY);
        } else {
            super.chromaMc(srcCb, srcCr, srcOffset, srcStride, dstCb, dstCr, dstOffset, dstStride, width, height, fracX,
                    fracY);
        }
    }

    /**
     * Interpolates one chroma block 8 samples wide at eighth-sample precision.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     * @param fracX the horizontal eighth-sample fraction, 0 to 7
     * @param fracY the vertical eighth-sample fraction, 0 to 7
     */
    private static void chroma8(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride,
                                int height, int fracX, int fracY) {
        var weightA = (8 - fracX) * (8 - fracY);
        var weightB = fracX * (8 - fracY);
        var weightC = (8 - fracX) * fracY;
        var weightD = fracX * fracY;
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var a = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s)).convertShape(VectorOperators.S2I, I256, 0));
            var b = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + 1)).convertShape(VectorOperators.S2I, I256, 0));
            var c = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + srcStride)).convertShape(VectorOperators.S2I, I256, 0));
            var d = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, src, s + srcStride + 1)).convertShape(VectorOperators.S2I, I256, 0));
            var value = a.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, weightA))
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, weightB)))
                    .lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, weightC)))
                    .lanewise(VectorOperators.ADD, d.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, weightD)))
                    .lanewise(VectorOperators.ADD, I256_32).lanewise(VectorOperators.ASHR, 6);
            SHORTS_8.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, dstOffset + y * dstStride);
        }
    }

    /**
     * Interpolates one chroma block 4 samples wide at eighth-sample precision.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param height the block height
     * @param fracX the horizontal eighth-sample fraction, 0 to 7
     * @param fracY the vertical eighth-sample fraction, 0 to 7
     */
    private static void chroma4(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride,
                                int height, int fracX, int fracY) {
        var weightA = (8 - fracX) * (8 - fracY);
        var weightB = fracX * (8 - fracY);
        var weightC = (8 - fracX) * fracY;
        var weightD = fracX * fracY;
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var a = INTS_4.cast(SHORTS_4.cast(ShortVector.fromArray(S64, src, s)).convertShape(VectorOperators.S2I, I128, 0));
            var b = INTS_4.cast(SHORTS_4.cast(ShortVector.fromArray(S64, src, s + 1)).convertShape(VectorOperators.S2I, I128, 0));
            var c = INTS_4.cast(SHORTS_4.cast(ShortVector.fromArray(S64, src, s + srcStride)).convertShape(VectorOperators.S2I, I128, 0));
            var d = INTS_4.cast(SHORTS_4.cast(ShortVector.fromArray(S64, src, s + srcStride + 1)).convertShape(VectorOperators.S2I, I128, 0));
            var value = a.lanewise(VectorOperators.MUL, IntVector.broadcast(I128, weightA))
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, IntVector.broadcast(I128, weightB)))
                    .lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.MUL, IntVector.broadcast(I128, weightC)))
                    .lanewise(VectorOperators.ADD, d.lanewise(VectorOperators.MUL, IntVector.broadcast(I128, weightD)))
                    .lanewise(VectorOperators.ADD, I128_32).lanewise(VectorOperators.ASHR, 6);
            SHORTS_4.cast(value.convertShape(VectorOperators.I2S, S64, 0)).intoArray(dst, dstOffset + y * dstStride);
        }
    }

    @Override
    public void averageBlocks(short[] a, int aOffset, int aStride, short[] b, int bOffset, int bStride, short[] dst,
                              int dstOffset, int dstStride, int width, int height) {
        if (width >= 8) {
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x += 8) {
                    var first = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOffset + y * aStride + x));
                    var second = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOffset + y * bStride + x));
                    SHORTS_8.cast(first.lanewise(VectorOperators.ADD, second).lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.ASHR, 1))
                            .intoArray(dst, dstOffset + y * dstStride + x);
                }
            }
        } else if (width == 4) {
            for (var y = 0; y < height; y++) {
                var first = SHORTS_4.cast(ShortVector.fromArray(S64, a, aOffset + y * aStride));
                var second = SHORTS_4.cast(ShortVector.fromArray(S64, b, bOffset + y * bStride));
                SHORTS_4.cast(first.lanewise(VectorOperators.ADD, second).lanewise(VectorOperators.ADD, S64_1).lanewise(VectorOperators.ASHR, 1))
                        .intoArray(dst, dstOffset + y * dstStride);
            }
        } else {
            super.averageBlocks(a, aOffset, aStride, b, bOffset, bStride, dst, dstOffset, dstStride, width, height);
        }
    }

    @Override
    public void average(short[] dst, int dstOffset, int dstStride, short[] src, int srcOffset, int srcStride, int width,
                        int height) {
        averageBlocks(dst, dstOffset, dstStride, src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height);
    }
}
