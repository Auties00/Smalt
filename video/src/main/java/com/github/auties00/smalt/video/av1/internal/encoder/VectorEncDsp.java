package com.github.auties00.smalt.video.av1.internal.encoder;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link EncDsp} with the Vector API for blocks 8, 16, 32 and 64 samples wide and
 * coefficient runs of a multiple of eight; every result equals {@link ScalarEncDsp}'s, which other
 * shapes use.
 *
 * @implNote This implementation follows the kernel rules of
 * {@link com.github.auties00.smalt.internal.simd.Simd Simd}: the pixel kernels of widths 16 to 64
 * step one row per iteration with no column loop, and the coefficient kernels take 32 to 64
 * coefficients per iteration so they compile on their own. Sums of absolute differences accumulate
 * in unsigned 16-bit lanes; signed differences and their squares are added in pairs into 32-bit
 * lanes through the 32-bit view of the 16-bit vector.
 */
final class VectorEncDsp extends EncDsp {
    /**
     * The 8-lane short species.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * The 16-lane short species.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * The 4-lane int species.
     */
    private static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;

    /**
     * The 8-lane int species.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * The 4-lane long species.
     */
    private static final VectorSpecies<Long> L256 = LongVector.SPECIES_256;

    /**
     * The exact class of 8-short vectors.
     */
    private static final Class<ShortVector> SHORTS_8 = vectorClass(S128);

    /**
     * The exact class of 16-short vectors.
     */
    private static final Class<ShortVector> SHORTS_16 = vectorClass(S256);

    /**
     * The exact class of 4-int vectors.
     */
    private static final Class<IntVector> INTS_4 = vectorClass(I128);

    /**
     * The exact class of 8-int vectors.
     */
    private static final Class<IntVector> INTS_8 = vectorClass(I256);

    /**
     * The exact class of 4-long vectors.
     */
    private static final Class<LongVector> LONGS_4 = vectorClass(L256);

    /**
     * The low 16 bits of every 8-int lane.
     */
    private static final IntVector LOW_HALVES_8 = IntVector.broadcast(I256, 0xFFFF);

    /**
     * The low 16 bits of every 4-int lane.
     */
    private static final IntVector LOW_HALVES_4 = IntVector.broadcast(I128, 0xFFFF);

    /**
     * The bilinear rounding offset in 16 lanes.
     */
    private static final ShortVector ROUND_16 = ShortVector.broadcast(S256, (short) 64);

    /**
     * The largest 16-bit value in 8 int lanes, the low-precision quantizer's clamp.
     */
    private static final IntVector INT16_MAX_8 = IntVector.broadcast(I256, Short.MAX_VALUE);

    /**
     * The mask of the first of 8 int lanes, the DC coefficient of a block.
     */
    private static final VectorMask<Integer> DC_LANE = VectorMask.fromLong(I256, 1);

    /**
     * The coefficient magnitude, as a bit pattern, from which {@link #quantizeDz} falls back to
     * the scalar kernel: with the rounding offset the product with the reciprocal multiplier must
     * stay within 32 bits.
     */
    private static final int HUGE_MAGNITUDE = 1 << 14;

    /**
     * The scalar kernels the unsupported shapes fall back to.
     */
    private final ScalarEncDsp scalar = new ScalarEncDsp();

    /**
     * Creates the kernels.
     */
    VectorEncDsp() {
    }

    /**
     * Returns the exact vector class of a species.
     *
     * @param species the vector species
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <V> Class<V> vectorClass(VectorSpecies<?> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    long levelError(int[] coeff, int[] levels, int n, int dcDq, int acDq, int logScale) {
        if ((n & 7) != 0) {
            return scalar.levelError(coeff, levels, n, dcDq, acDq, logScale);
        }
        var acV = INTS_8.cast(IntVector.broadcast(I256, acDq));
        var errors = LongVector.zero(L256);
        var c0 = INTS_8.cast(IntVector.fromArray(I256, coeff, 0));
        var l0 = INTS_8.cast(IntVector.fromArray(I256, levels, 0));
        var d0 = l0.lanewise(VectorOperators.ABS).lanewise(VectorOperators.MUL,
                acV.blend(INTS_8.cast(IntVector.broadcast(I256, dcDq)), DC_LANE)).lanewise(VectorOperators.ASHR,
                logScale);
        var s0 = l0.lanewise(VectorOperators.ASHR, 31);
        var e0 = c0.lanewise(VectorOperators.SUB, d0.lanewise(VectorOperators.XOR, s0).lanewise(VectorOperators.SUB,
                s0));
        var lo0 = LONGS_4.cast(e0.convertShape(VectorOperators.I2L, L256, 0));
        var hi0 = LONGS_4.cast(e0.convertShape(VectorOperators.I2L, L256, 1));
        errors = lo0.lanewise(VectorOperators.MUL, lo0).lanewise(VectorOperators.ADD,
                hi0.lanewise(VectorOperators.MUL, hi0));
        for (var i = 8; i < n; i += 8) {
            var c = INTS_8.cast(IntVector.fromArray(I256, coeff, i));
            var l = INTS_8.cast(IntVector.fromArray(I256, levels, i));
            var d = l.lanewise(VectorOperators.ABS).lanewise(VectorOperators.MUL, INTS_8.cast(acV))
                    .lanewise(VectorOperators.ASHR, logScale);
            var s = l.lanewise(VectorOperators.ASHR, 31);
            var e = c.lanewise(VectorOperators.SUB, d.lanewise(VectorOperators.XOR, s).lanewise(VectorOperators.SUB,
                    s));
            var lo = LONGS_4.cast(e.convertShape(VectorOperators.I2L, L256, 0));
            var hi = LONGS_4.cast(e.convertShape(VectorOperators.I2L, L256, 1));
            errors = lo.lanewise(VectorOperators.MUL, lo).lanewise(VectorOperators.ADD,
                    hi.lanewise(VectorOperators.MUL, hi)).lanewise(VectorOperators.ADD, LONGS_4.cast(errors));
        }
        return LONGS_4.cast(errors).reduceLanes(VectorOperators.ADD);
    }

    @Override
    int quantizeDz(int[] coeff, int n, int[] iscan, int[] qcoeff, int zbinDc, int zbinAc, int roundDc,
                          int roundAc, int quantDc, int quantAc, int shiftDc, int shiftAc, int limit) {
        if ((n & 7) != 0) {
            return scalar.quantizeDz(coeff, n, iscan, qcoeff, zbinDc, zbinAc, roundDc, roundAc, quantDc, quantAc,
                    shiftDc, shiftAc, limit);
        }
        var cap = INTS_8.cast(IntVector.broadcast(I256, limit));
        var zbin = INTS_8.cast(IntVector.broadcast(I256, zbinAc));
        var round = INTS_8.cast(IntVector.broadcast(I256, roundAc));
        var quant = INTS_8.cast(IntVector.broadcast(I256, quantAc));
        var zero = INTS_8.cast(IntVector.zero(I256));
        var c0 = INTS_8.cast(IntVector.fromArray(I256, coeff, 0));
        var m0 = c0.lanewise(VectorOperators.ABS);
        var keep0 = m0.compare(VectorOperators.GE, zbin.blend(INTS_8.cast(IntVector.broadcast(I256, zbinDc)),
                DC_LANE));
        var tmp0 = m0.lanewise(VectorOperators.ADD, round.blend(INTS_8.cast(IntVector.broadcast(I256, roundDc)),
                DC_LANE)).lanewise(VectorOperators.MIN, cap);
        var t20 = tmp0.lanewise(VectorOperators.MUL, quant.blend(INTS_8.cast(IntVector.broadcast(I256, quantDc)),
                DC_LANE)).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, tmp0);
        var level0 = t20.lanewise(VectorOperators.ASHR, shiftAc).blend(t20.lanewise(VectorOperators.ASHR, shiftDc),
                DC_LANE);
        var kept0 = zero.blend(level0, keep0);
        var sign0 = c0.lanewise(VectorOperators.ASHR, 31);
        INTS_8.cast(kept0.lanewise(VectorOperators.XOR, sign0).lanewise(VectorOperators.SUB, sign0))
                .intoArray(qcoeff, 0);
        var eobs = INTS_8.cast(IntVector.zero(I256)).blend(INTS_8.cast(IntVector.fromArray(I256, iscan, 0)),
                kept0.compare(VectorOperators.NE, zero));
        var big = m0;
        for (var i = 8; i < n; i += 8) {
            var c = INTS_8.cast(IntVector.fromArray(I256, coeff, i));
            var magnitude = c.lanewise(VectorOperators.ABS);
            big = INTS_8.cast(big).lanewise(VectorOperators.OR, magnitude);
            var keep = magnitude.compare(VectorOperators.GE, INTS_8.cast(zbin));
            var tmp = magnitude.lanewise(VectorOperators.ADD, INTS_8.cast(round)).lanewise(VectorOperators.MIN,
                    INTS_8.cast(cap));
            var t2 = tmp.lanewise(VectorOperators.MUL, INTS_8.cast(quant)).lanewise(VectorOperators.ASHR, 16)
                    .lanewise(VectorOperators.ADD, tmp);
            var level = t2.lanewise(VectorOperators.ASHR, shiftAc);
            var kept = INTS_8.cast(zero).blend(level, keep);
            var sign = c.lanewise(VectorOperators.ASHR, 31);
            INTS_8.cast(kept.lanewise(VectorOperators.XOR, sign).lanewise(VectorOperators.SUB, sign))
                    .intoArray(qcoeff, i);
            var position = INTS_8.cast(IntVector.fromArray(I256, iscan, i));
            eobs = INTS_8.cast(eobs).lanewise(VectorOperators.MAX, INTS_8.cast(zero).blend(position,
                    kept.compare(VectorOperators.NE, INTS_8.cast(zero))));
        }
        if (INTS_8.cast(big).reduceLanes(VectorOperators.OR) >= HUGE_MAGNITUDE) {
            return scalar.quantizeDz(coeff, n, iscan, qcoeff, zbinDc, zbinAc, roundDc, roundAc, quantDc, quantAc,
                    shiftDc, shiftAc, limit);
        }
        return INTS_8.cast(eobs).reduceLanes(VectorOperators.MAX);
    }

    @Override
    long planeSse(short[] a, short[] b, int off, int stride, int w, int h) {
        long sum = 0;
        var low = INTS_8.cast(LOW_HALVES_8);
        var vw = w & ~15;
        for (var y = 0; y < h; y++) {
            var o = off + y * stride;
            var acc = IntVector.zero(I256);
            for (var x = 0; x < vw; x += 16) {
                var d = SHORTS_16.cast(ShortVector.fromArray(S256, a, o + x)).lanewise(VectorOperators.SUB,
                        SHORTS_16.cast(ShortVector.fromArray(S256, b, o + x)));
                var q = INTS_8.cast(SHORTS_16.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I256, 0));
                acc = q.lanewise(VectorOperators.AND, INTS_8.cast(low)).lanewise(VectorOperators.ADD,
                        q.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, INTS_8.cast(acc));
            }
            sum += INTS_8.cast(acc).reduceLanes(VectorOperators.ADD);
            for (var x = vw; x < w; x++) {
                var d = a[o + x] - b[o + x];
                sum += d * d;
            }
        }
        return sum;
    }

    @Override
    void average(short[] a, int aOff, short[] b, int bOff, int n) {
        var one = ShortVector.broadcast(S256, (short) 1);
        var i = 0;
        for (; i + 16 <= n; i += 16) {
            var sum = SHORTS_16.cast(ShortVector.fromArray(S256, a, aOff + i)).lanewise(VectorOperators.ADD,
                    SHORTS_16.cast(ShortVector.fromArray(S256, b, bOff + i))).lanewise(VectorOperators.ADD,
                    SHORTS_16.cast(one));
            SHORTS_16.cast(sum.lanewise(VectorOperators.ASHR, 1)).intoArray(a, aOff + i);
        }
        for (; i < n; i++) {
            a[aOff + i] = (short) ((a[aOff + i] + b[bOff + i] + 1) >> 1);
        }
    }

    @Override
    int sad(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride, int w, int h) {
        if ((h & 1) == 0 && h <= 64) {
            if (w == 8) {
                return sad8(a, aOff, aStride, b, bOff, bStride, h);
            }
            if (w == 16) {
                return sad16(a, aOff, aStride, b, bOff, bStride, h);
            }
            if (w == 32) {
                return sad32(a, aOff, aStride, b, bOff, bStride, h);
            }
            if (w == 64) {
                return sad32(a, aOff, aStride, b, bOff, bStride, h) + sad32(a, aOff + 32, aStride, b, bOff + 32, bStride, h);
            }
        }
        return scalar.sad(a, aOff, aStride, b, bOff, bStride, w, h);
    }

    /**
     * Returns the sum of absolute differences of two 8-wide blocks, four rows at a time.
     *
     * @param a the first plane
     * @param aOff the index of the first block's top-left sample
     * @param aStride the first plane's stride
     * @param b the second plane
     * @param bOff the index of the second block's top-left sample
     * @param bStride the second plane's stride
     * @param h the height, even and at most 64
     * @return the sum
     */
    private static int sad8(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride, int h) {
        var acc = ShortVector.zero(S128);
        var r = 0;
        for (; r + 4 <= h; r += 4, aOff += 4 * aStride, bOff += 4 * bStride) {
            var a0 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff));
            var b0 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff));
            var a1 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff + aStride));
            var b1 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff + bStride));
            var a2 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff + 2 * aStride));
            var b2 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff + 2 * bStride));
            var a3 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff + 3 * aStride));
            var b3 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff + 3 * bStride));
            var d0 = a0.lanewise(VectorOperators.SUB, b0).lanewise(VectorOperators.ABS);
            var d1 = a1.lanewise(VectorOperators.SUB, b1).lanewise(VectorOperators.ABS);
            var d2 = a2.lanewise(VectorOperators.SUB, b2).lanewise(VectorOperators.ABS);
            var d3 = a3.lanewise(VectorOperators.SUB, b3).lanewise(VectorOperators.ABS);
            acc = d0.lanewise(VectorOperators.ADD, d1).lanewise(VectorOperators.ADD,
                    d2.lanewise(VectorOperators.ADD, d3)).lanewise(VectorOperators.ADD, SHORTS_8.cast(acc));
        }
        if (r < h) {
            var a0 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff));
            var b0 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff));
            var a1 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff + aStride));
            var b1 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff + bStride));
            var d0 = a0.lanewise(VectorOperators.SUB, b0).lanewise(VectorOperators.ABS);
            var d1 = a1.lanewise(VectorOperators.SUB, b1).lanewise(VectorOperators.ABS);
            acc = d0.lanewise(VectorOperators.ADD, d1).lanewise(VectorOperators.ADD, SHORTS_8.cast(acc));
        }
        var lanes = INTS_4.cast(SHORTS_8.cast(acc).reinterpretShape(I128, 0));
        var low = INTS_4.cast(LOW_HALVES_4);
        return lanes.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                lanes.lanewise(VectorOperators.LSHR, 16)).reduceLanes(VectorOperators.ADD);
    }

    @Override
    long sseSum(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride, int w, int h) {
        if ((h & 1) == 0 && h <= 64) {
            if (w == 8) {
                return sseSum8(a, aOff, aStride, b, bOff, bStride, h);
            }
            if (w == 16) {
                return sseSum16(a, aOff, aStride, b, bOff, bStride, h);
            }
            if (w == 32) {
                return sseSum32(a, aOff, aStride, b, bOff, bStride, h);
            }
            if (w == 64) {
                var left = sseSum32(a, aOff, aStride, b, bOff, bStride, h);
                var right = sseSum32(a, aOff + 32, aStride, b, bOff + 32, bStride, h);
                return (left >>> 32) + (right >>> 32) << 32 | ((int) left + (int) right) & 0xFFFFFFFFL;
            }
        }
        return scalar.sseSum(a, aOff, aStride, b, bOff, bStride, w, h);
    }

    /**
     * Returns the sums of squared and of signed differences of two 8-wide blocks, packed like
     * {@link EncDsp#sseSum}, two rows at a time.
     *
     * @param a the first plane
     * @param aOff the index of the first block's top-left sample
     * @param aStride the first plane's stride
     * @param b the second plane
     * @param bOff the index of the second block's top-left sample
     * @param bStride the second plane's stride
     * @param h the height, even and at most 64
     * @return the packed sums
     */
    private static long sseSum8(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride, int h) {
        var sum = ShortVector.zero(S128);
        var sse = IntVector.zero(I128);
        var low = INTS_4.cast(LOW_HALVES_4);
        for (var r = 0; r < h; r += 2, aOff += 2 * aStride, bOff += 2 * bStride) {
            var a0 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff));
            var b0 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff));
            var a1 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff + aStride));
            var b1 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff + bStride));
            var d0 = a0.lanewise(VectorOperators.SUB, b0);
            var d1 = a1.lanewise(VectorOperators.SUB, b1);
            sum = d0.lanewise(VectorOperators.ADD, d1).lanewise(VectorOperators.ADD, SHORTS_8.cast(sum));
            var q0 = INTS_4.cast(SHORTS_8.cast(d0.lanewise(VectorOperators.MUL, d0)).reinterpretShape(I128, 0));
            var q1 = INTS_4.cast(SHORTS_8.cast(d1.lanewise(VectorOperators.MUL, d1)).reinterpretShape(I128, 0));
            sse = q0.lanewise(VectorOperators.AND, INTS_4.cast(low)).lanewise(VectorOperators.ADD,
                    q0.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD,
                    q1.lanewise(VectorOperators.AND, INTS_4.cast(low)).lanewise(VectorOperators.ADD,
                            q1.lanewise(VectorOperators.LSHR, 16))).lanewise(VectorOperators.ADD, INTS_4.cast(sse));
        }
        var sums = INTS_4.cast(SHORTS_8.cast(sum).reinterpretShape(I128, 0));
        var total = sums.lanewise(VectorOperators.LSHL, 16).lanewise(VectorOperators.ASHR, 16).lanewise(
                VectorOperators.ADD, sums.lanewise(VectorOperators.ASHR, 16)).reduceLanes(VectorOperators.ADD);
        var squares = INTS_4.cast(sse).reduceLanes(VectorOperators.ADD);
        return (long) squares << 32 | (total & 0xFFFFFFFFL);
    }

    @Override
    int avgDiff8x8(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride) {
        var a0 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff));
        var a1 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff + aStride));
        var a2 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff + 2 * aStride));
        var a3 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff + 3 * aStride));
        var a4 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff + 4 * aStride));
        var a5 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff + 5 * aStride));
        var a6 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff + 6 * aStride));
        var a7 = SHORTS_8.cast(ShortVector.fromArray(S128, a, aOff + 7 * aStride));
        var sumA = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD,
                a2.lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD,
                a4.lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD,
                        a6.lanewise(VectorOperators.ADD, a7)));
        var b0 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff));
        var b1 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff + bStride));
        var b2 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff + 2 * bStride));
        var b3 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff + 3 * bStride));
        var b4 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff + 4 * bStride));
        var b5 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff + 5 * bStride));
        var b6 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff + 6 * bStride));
        var b7 = SHORTS_8.cast(ShortVector.fromArray(S128, b, bOff + 7 * bStride));
        var sumB = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD,
                b2.lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD,
                b4.lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD,
                        b6.lanewise(VectorOperators.ADD, b7)));
        return ((SHORTS_8.cast(sumA).reduceLanes(VectorOperators.ADD) + 32) >> 6)
                - ((SHORTS_8.cast(sumB).reduceLanes(VectorOperators.ADD) + 32) >> 6);
    }

    @Override
    int avg4x4(short[] s, int off, int stride) {
        return scalar.avg4x4(s, off, stride);
    }

    @Override
    void subtract(short[] src, int srcOff, int srcStride, short[] pred, int predOff, int predStride, int[] diff,
                         int w, int h) {
        if (w == 8) {
            subtract8(src, srcOff, srcStride, pred, predOff, predStride, diff, h);
        } else if (w == 16) {
            subtract16(src, srcOff, srcStride, pred, predOff, predStride, diff, 0, 16, h);
        } else if (w == 32) {
            subtract32(src, srcOff, srcStride, pred, predOff, predStride, diff, 0, 32, h);
        } else if (w == 64) {
            subtract32(src, srcOff, srcStride, pred, predOff, predStride, diff, 0, 64, h);
            subtract32(src, srcOff + 32, srcStride, pred, predOff + 32, predStride, diff, 32, 64, h);
        } else {
            scalar.subtract(src, srcOff, srcStride, pred, predOff, predStride, diff, w, h);
        }
    }

    @Override
    void hadamard8x8(int[] diff, int diffOff, int stride, int[] coeff, int coeffOff, int[] scratch) {
        hadamardColumns(diff, diffOff, stride, coeff, coeffOff);
        for (var j = 0; j < 8; j++) {
            for (var i = 0; i < 8; i++) {
                scratch[i * 8 + j] = coeff[coeffOff + j * 8 + i];
            }
        }
        hadamardColumns(scratch, 0, 8, coeff, coeffOff);
    }

    /**
     * Applies the 8-point Hadamard butterflies of {@code aom_hadamard_lp_8x8} down the eight
     * columns of a block at once, writing output position {@code k} of every column as row
     * {@code k} of the result.
     *
     * @param in the block
     * @param inOff the offset of its top-left value
     * @param stride its row stride
     * @param out receives the eight result rows
     * @param outOff the offset of the result
     */
    private static void hadamardColumns(int[] in, int inOff, int stride, int[] out, int outOff) {
        var x0 = INTS_8.cast(IntVector.fromArray(I256, in, inOff));
        var x1 = INTS_8.cast(IntVector.fromArray(I256, in, inOff + stride));
        var x2 = INTS_8.cast(IntVector.fromArray(I256, in, inOff + 2 * stride));
        var x3 = INTS_8.cast(IntVector.fromArray(I256, in, inOff + 3 * stride));
        var x4 = INTS_8.cast(IntVector.fromArray(I256, in, inOff + 4 * stride));
        var x5 = INTS_8.cast(IntVector.fromArray(I256, in, inOff + 5 * stride));
        var x6 = INTS_8.cast(IntVector.fromArray(I256, in, inOff + 6 * stride));
        var x7 = INTS_8.cast(IntVector.fromArray(I256, in, inOff + 7 * stride));
        var b0 = x0.lanewise(VectorOperators.ADD, x1);
        var b1 = x0.lanewise(VectorOperators.SUB, x1);
        var b2 = x2.lanewise(VectorOperators.ADD, x3);
        var b3 = x2.lanewise(VectorOperators.SUB, x3);
        var b4 = x4.lanewise(VectorOperators.ADD, x5);
        var b5 = x4.lanewise(VectorOperators.SUB, x5);
        var b6 = x6.lanewise(VectorOperators.ADD, x7);
        var b7 = x6.lanewise(VectorOperators.SUB, x7);
        var c0 = b0.lanewise(VectorOperators.ADD, b2);
        var c1 = b1.lanewise(VectorOperators.ADD, b3);
        var c2 = b0.lanewise(VectorOperators.SUB, b2);
        var c3 = b1.lanewise(VectorOperators.SUB, b3);
        var c4 = b4.lanewise(VectorOperators.ADD, b6);
        var c5 = b5.lanewise(VectorOperators.ADD, b7);
        var c6 = b4.lanewise(VectorOperators.SUB, b6);
        var c7 = b5.lanewise(VectorOperators.SUB, b7);
        INTS_8.cast(c0.lanewise(VectorOperators.ADD, c4)).intoArray(out, outOff);
        INTS_8.cast(c2.lanewise(VectorOperators.SUB, c6)).intoArray(out, outOff + 8);
        INTS_8.cast(c0.lanewise(VectorOperators.SUB, c4)).intoArray(out, outOff + 16);
        INTS_8.cast(c2.lanewise(VectorOperators.ADD, c6)).intoArray(out, outOff + 24);
        INTS_8.cast(c3.lanewise(VectorOperators.ADD, c7)).intoArray(out, outOff + 32);
        INTS_8.cast(c3.lanewise(VectorOperators.SUB, c7)).intoArray(out, outOff + 40);
        INTS_8.cast(c1.lanewise(VectorOperators.SUB, c5)).intoArray(out, outOff + 48);
        INTS_8.cast(c1.lanewise(VectorOperators.ADD, c5)).intoArray(out, outOff + 56);
    }

    @Override
    void hadamard16x16(int[] diff, int diffOff, int stride, int[] coeff, int coeffOff, int[] scratch) {
        for (var idx = 0; idx < 4; idx++) {
            hadamard8x8(diff, diffOff + (idx >> 1) * 8 * stride + (idx & 1) * 8, stride, coeff, coeffOff + idx * 64,
                    scratch);
        }
        for (var idx = 0; idx < 64; idx += 8) {
            var p = coeffOff + idx;
            var a0 = INTS_8.cast(IntVector.fromArray(I256, coeff, p));
            var a1 = INTS_8.cast(IntVector.fromArray(I256, coeff, p + 64));
            var a2 = INTS_8.cast(IntVector.fromArray(I256, coeff, p + 128));
            var a3 = INTS_8.cast(IntVector.fromArray(I256, coeff, p + 192));
            var b0 = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ASHR, 1);
            var b1 = a0.lanewise(VectorOperators.SUB, a1).lanewise(VectorOperators.ASHR, 1);
            var b2 = a2.lanewise(VectorOperators.ADD, a3).lanewise(VectorOperators.ASHR, 1);
            var b3 = a2.lanewise(VectorOperators.SUB, a3).lanewise(VectorOperators.ASHR, 1);
            INTS_8.cast(b0.lanewise(VectorOperators.ADD, b2)).intoArray(coeff, p);
            INTS_8.cast(b1.lanewise(VectorOperators.ADD, b3)).intoArray(coeff, p + 64);
            INTS_8.cast(b0.lanewise(VectorOperators.SUB, b2)).intoArray(coeff, p + 128);
            INTS_8.cast(b1.lanewise(VectorOperators.SUB, b3)).intoArray(coeff, p + 192);
        }
    }

    @Override
    int quantizeLp(int[] coeff, int coeffOff, int n, int[] roundFp, int[] quantFp, int[] dequant, int[] scan,
                          int[] iscan, int[] qcoeff, int[] dqcoeff) {
        if (iscan == null || (n & 7) != 0) {
            return scalar.quantizeLp(coeff, coeffOff, n, roundFp, quantFp, dequant, scan, iscan, qcoeff, dqcoeff);
        }
        var zero = IntVector.zero(I256);
        var max16 = INTS_8.cast(INT16_MAX_8);
        var roundAc = IntVector.broadcast(I256, roundFp[1]);
        var quantAc = IntVector.broadcast(I256, quantFp[1]);
        var dequantAc = IntVector.broadcast(I256, dequant[1]);
        var round0 = roundAc.blend(IntVector.broadcast(I256, roundFp[0]), DC_LANE);
        var quant0 = quantAc.blend(IntVector.broadcast(I256, quantFp[0]), DC_LANE);
        var dequant0 = dequantAc.blend(IntVector.broadcast(I256, dequant[0]), DC_LANE);
        var c0 = INTS_8.cast(IntVector.fromArray(I256, coeff, coeffOff));
        var t0 = c0.lanewise(VectorOperators.ABS).lanewise(VectorOperators.ADD, round0)
                .lanewise(VectorOperators.MIN, max16).lanewise(VectorOperators.MUL, quant0)
                .lanewise(VectorOperators.ASHR, 16);
        var q0 = t0.blend(zero.lanewise(VectorOperators.SUB, t0), c0.compare(VectorOperators.LT, zero));
        INTS_8.cast(q0).intoArray(qcoeff, 0);
        INTS_8.cast(q0.lanewise(VectorOperators.MUL, dequant0)).intoArray(dqcoeff, 0);
        var pos0 = INTS_8.cast(IntVector.fromArray(I256, iscan, 0));
        var first = INTS_8.cast(zero.blend(pos0, t0.compare(VectorOperators.NE, zero)));
        var eobs = IntVector.zero(I256);
        for (var i = 8; i < n; i += 8) {
            var c = INTS_8.cast(IntVector.fromArray(I256, coeff, coeffOff + i));
            var t = c.lanewise(VectorOperators.ABS).lanewise(VectorOperators.ADD, INTS_8.cast(roundAc))
                    .lanewise(VectorOperators.MIN, INTS_8.cast(max16))
                    .lanewise(VectorOperators.MUL, INTS_8.cast(quantAc))
                    .lanewise(VectorOperators.ASHR, 16);
            var q = t.blend(INTS_8.cast(zero).lanewise(VectorOperators.SUB, t),
                    c.compare(VectorOperators.LT, INTS_8.cast(zero)));
            INTS_8.cast(q).intoArray(qcoeff, i);
            INTS_8.cast(q.lanewise(VectorOperators.MUL, INTS_8.cast(dequantAc))).intoArray(dqcoeff, i);
            var pos = INTS_8.cast(IntVector.fromArray(I256, iscan, i));
            eobs = INTS_8.cast(INTS_8.cast(zero).blend(pos, t.compare(VectorOperators.NE, INTS_8.cast(zero))))
                    .lanewise(VectorOperators.MAX, INTS_8.cast(eobs));
        }
        return INTS_8.cast(eobs).lanewise(VectorOperators.MAX, first).reduceLanes(VectorOperators.MAX);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation quantizes and measures in one pass over the coefficients in
     * raster order, 8 at a time, without storing the levels: the first 8 coefficients,
     * which hold the DC coefficient, are handled apart so the loop over the others uses
     * constant parameters. The end of block is the largest scan position among the
     * non-zero levels, and the squared errors are widened to 64-bit lanes as
     * {@link #blockError} does.
     */
    @Override
    int quantizeLpRd(int[] coeff, int n, int[] roundFp, int[] quantFp, int[] dequant, int[] scan,
                            int[] iscan, int[] qcoeff, int[] dqcoeff, long[] result) {
        if (iscan == null || (n & 7) != 0) {
            return super.quantizeLpRd(coeff, n, roundFp, quantFp, dequant, scan, iscan, qcoeff, dqcoeff, result);
        }
        var max16 = INTS_8.cast(INT16_MAX_8);
        var roundAc = INTS_8.cast(IntVector.broadcast(I256, roundFp[1]));
        var quantAc = INTS_8.cast(IntVector.broadcast(I256, quantFp[1]));
        var dequantAc = INTS_8.cast(IntVector.broadcast(I256, dequant[1]));
        var round0 = INTS_8.cast(roundAc.blend(IntVector.broadcast(I256, roundFp[0]), DC_LANE));
        var quant0 = INTS_8.cast(quantAc.blend(IntVector.broadcast(I256, quantFp[0]), DC_LANE));
        var dequant0 = INTS_8.cast(dequantAc.blend(IntVector.broadcast(I256, dequant[0]), DC_LANE));
        var c0 = INTS_8.cast(IntVector.fromArray(I256, coeff, 0));
        var sign0 = c0.lanewise(VectorOperators.ASHR, 31);
        var firstLevels = c0.lanewise(VectorOperators.ABS).lanewise(VectorOperators.ADD, round0)
                .lanewise(VectorOperators.MIN, max16).lanewise(VectorOperators.MUL, quant0)
                .lanewise(VectorOperators.ASHR, 16);
        var diff0 = c0.lanewise(VectorOperators.SUB, firstLevels.lanewise(VectorOperators.MUL, dequant0)
                .lanewise(VectorOperators.XOR, sign0).lanewise(VectorOperators.SUB, sign0));
        var square0 = diff0.lanewise(VectorOperators.MUL, diff0);
        var firstErrors = LONGS_4.cast(square0.convertShape(VectorOperators.I2L, L256, 0))
                .lanewise(VectorOperators.ADD, LONGS_4.cast(square0.convertShape(VectorOperators.I2L, L256, 1)));
        var firstEobs = INTS_8.cast(IntVector.fromArray(I256, iscan, 0)).lanewise(VectorOperators.AND,
                firstLevels.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ASHR, 31));
        var eobs = IntVector.zero(I256);
        var sums = IntVector.zero(I256);
        var errors = LongVector.zero(L256);
        for (var i = 8; i < n; i += 8) {
            var c = INTS_8.cast(IntVector.fromArray(I256, coeff, i));
            var sign = c.lanewise(VectorOperators.ASHR, 31);
            var levels = c.lanewise(VectorOperators.ABS).lanewise(VectorOperators.ADD, INTS_8.cast(roundAc))
                    .lanewise(VectorOperators.MIN, INTS_8.cast(max16))
                    .lanewise(VectorOperators.MUL, INTS_8.cast(quantAc))
                    .lanewise(VectorOperators.ASHR, 16);
            var diff = c.lanewise(VectorOperators.SUB, levels.lanewise(VectorOperators.MUL, INTS_8.cast(dequantAc))
                    .lanewise(VectorOperators.XOR, sign).lanewise(VectorOperators.SUB, sign));
            var square = diff.lanewise(VectorOperators.MUL, diff);
            errors = LONGS_4.cast(square.convertShape(VectorOperators.I2L, L256, 0)).lanewise(VectorOperators.ADD,
                    LONGS_4.cast(square.convertShape(VectorOperators.I2L, L256, 1))).lanewise(VectorOperators.ADD,
                    LONGS_4.cast(errors));
            sums = levels.lanewise(VectorOperators.ADD, INTS_8.cast(sums));
            var positions = INTS_8.cast(IntVector.fromArray(I256, iscan, i)).lanewise(VectorOperators.AND,
                    levels.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ASHR, 31));
            eobs = positions.lanewise(VectorOperators.MAX, INTS_8.cast(eobs));
        }
        result[0] = INTS_8.cast(sums).lanewise(VectorOperators.ADD, firstLevels).reduceLanes(VectorOperators.ADD);
        result[1] = LONGS_4.cast(errors).lanewise(VectorOperators.ADD, firstErrors)
                .reduceLanes(VectorOperators.ADD);
        return INTS_8.cast(eobs).lanewise(VectorOperators.MAX, firstEobs).reduceLanes(VectorOperators.MAX);
    }

    @Override
    void forwardTransform(FwdTxfm t, int txSize, int txType, int[] residual, int resOff, int resStride,
                                 int[] out) {
        VectorFwdTxfm.forward(t, txSize, txType, residual, resOff, resStride, out);
    }

    @Override
    int satd(int[] c, int n) {
        if ((n & 15) != 0) {
            return scalar.satd(c, n);
        }
        var acc = IntVector.zero(I256);
        var i = 0;
        for (; i + 64 <= n; i += 64) {
            var a0 = INTS_8.cast(IntVector.fromArray(I256, c, i)).lanewise(VectorOperators.ABS);
            var a1 = INTS_8.cast(IntVector.fromArray(I256, c, i + 8)).lanewise(VectorOperators.ABS);
            var a2 = INTS_8.cast(IntVector.fromArray(I256, c, i + 16)).lanewise(VectorOperators.ABS);
            var a3 = INTS_8.cast(IntVector.fromArray(I256, c, i + 24)).lanewise(VectorOperators.ABS);
            var a4 = INTS_8.cast(IntVector.fromArray(I256, c, i + 32)).lanewise(VectorOperators.ABS);
            var a5 = INTS_8.cast(IntVector.fromArray(I256, c, i + 40)).lanewise(VectorOperators.ABS);
            var a6 = INTS_8.cast(IntVector.fromArray(I256, c, i + 48)).lanewise(VectorOperators.ABS);
            var a7 = INTS_8.cast(IntVector.fromArray(I256, c, i + 56)).lanewise(VectorOperators.ABS);
            acc = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD,
                    a2.lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD,
                    a4.lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD,
                            a6.lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, INTS_8.cast(acc));
        }
        for (; i < n; i += 16) {
            var a = INTS_8.cast(IntVector.fromArray(I256, c, i));
            var b = INTS_8.cast(IntVector.fromArray(I256, c, i + 8));
            acc = a.lanewise(VectorOperators.ABS).lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.ABS))
                    .lanewise(VectorOperators.ADD, INTS_8.cast(acc));
        }
        return INTS_8.cast(acc).reduceLanes(VectorOperators.ADD);
    }

    @Override
    long blockError(int[] a, int aOff, int[] b, int n) {
        if ((n & 15) != 0) {
            return scalar.blockError(a, aOff, b, n);
        }
        var acc = LongVector.zero(L256);
        var i = 0;
        for (; i + 32 <= n; i += 32) {
            var d0 = INTS_8.cast(IntVector.fromArray(I256, a, aOff + i)).lanewise(VectorOperators.SUB,
                    INTS_8.cast(IntVector.fromArray(I256, b, i)));
            var q0 = d0.lanewise(VectorOperators.MUL, d0);
            var l0 = LONGS_4.cast(q0.convertShape(VectorOperators.I2L, L256, 0)).lanewise(VectorOperators.ADD,
                    LONGS_4.cast(q0.convertShape(VectorOperators.I2L, L256, 1)));
            var d1 = INTS_8.cast(IntVector.fromArray(I256, a, aOff + i + 8)).lanewise(VectorOperators.SUB,
                    INTS_8.cast(IntVector.fromArray(I256, b, i + 8)));
            var q1 = d1.lanewise(VectorOperators.MUL, d1);
            var l1 = LONGS_4.cast(q1.convertShape(VectorOperators.I2L, L256, 0)).lanewise(VectorOperators.ADD,
                    LONGS_4.cast(q1.convertShape(VectorOperators.I2L, L256, 1)));
            var d2 = INTS_8.cast(IntVector.fromArray(I256, a, aOff + i + 16)).lanewise(VectorOperators.SUB,
                    INTS_8.cast(IntVector.fromArray(I256, b, i + 16)));
            var q2 = d2.lanewise(VectorOperators.MUL, d2);
            var l2 = LONGS_4.cast(q2.convertShape(VectorOperators.I2L, L256, 0)).lanewise(VectorOperators.ADD,
                    LONGS_4.cast(q2.convertShape(VectorOperators.I2L, L256, 1)));
            var d3 = INTS_8.cast(IntVector.fromArray(I256, a, aOff + i + 24)).lanewise(VectorOperators.SUB,
                    INTS_8.cast(IntVector.fromArray(I256, b, i + 24)));
            var q3 = d3.lanewise(VectorOperators.MUL, d3);
            var l3 = LONGS_4.cast(q3.convertShape(VectorOperators.I2L, L256, 0)).lanewise(VectorOperators.ADD,
                    LONGS_4.cast(q3.convertShape(VectorOperators.I2L, L256, 1)));
            acc = l0.lanewise(VectorOperators.ADD, l1).lanewise(VectorOperators.ADD,
                    l2.lanewise(VectorOperators.ADD, l3)).lanewise(VectorOperators.ADD, LONGS_4.cast(acc));
        }
        if (i < n) {
            var d0 = INTS_8.cast(IntVector.fromArray(I256, a, aOff + i)).lanewise(VectorOperators.SUB,
                    INTS_8.cast(IntVector.fromArray(I256, b, i)));
            var d1 = INTS_8.cast(IntVector.fromArray(I256, a, aOff + i + 8)).lanewise(VectorOperators.SUB,
                    INTS_8.cast(IntVector.fromArray(I256, b, i + 8)));
            var q0 = d0.lanewise(VectorOperators.MUL, d0);
            var q1 = d1.lanewise(VectorOperators.MUL, d1);
            var l0 = LONGS_4.cast(q0.convertShape(VectorOperators.I2L, L256, 0));
            var l1 = LONGS_4.cast(q0.convertShape(VectorOperators.I2L, L256, 1));
            var l2 = LONGS_4.cast(q1.convertShape(VectorOperators.I2L, L256, 0));
            var l3 = LONGS_4.cast(q1.convertShape(VectorOperators.I2L, L256, 1));
            acc = l0.lanewise(VectorOperators.ADD, l1).lanewise(VectorOperators.ADD,
                    l2.lanewise(VectorOperators.ADD, l3)).lanewise(VectorOperators.ADD, LONGS_4.cast(acc));
        }
        return LONGS_4.cast(acc).reduceLanes(VectorOperators.ADD);
    }

    @Override
    long subpelSseSum(short[] ref, int refOff, int refStride, int xoff, int yoff, short[] src, int srcOff,
                             int srcStride, int w, int h, short[] scratch) {
        if (w > 64 || (h & 1) != 0 || h > 64 || (w != 8 && w != 16 && w != 32 && w != 64)) {
            return scalar.subpelSseSum(ref, refOff, refStride, xoff, yoff, src, srcOff, srcStride, w, h, scratch);
        }
        if (w == 8) {
            if (xoff == 0) {
                return bilinearSseSum8(ref, refOff, refStride, BILINEAR[yoff * 2], BILINEAR[yoff * 2 + 1], src,
                        srcOff, srcStride, h);
            }
            bilinearRows8(ref, refOff, refStride, BILINEAR[xoff * 2], BILINEAR[xoff * 2 + 1], scratch, h + 1);
            return bilinearSseSum8(scratch, 0, 8, BILINEAR[yoff * 2], BILINEAR[yoff * 2 + 1], src, srcOff, srcStride,
                    h);
        }
        var g0 = BILINEAR[yoff * 2];
        var g1 = BILINEAR[yoff * 2 + 1];
        var f0 = BILINEAR[xoff * 2];
        var f1 = BILINEAR[xoff * 2 + 1];
        if (w == 16) {
            if (xoff == 0) {
                return bilinearSseSum16(ref, refOff, refStride, g0, g1, src, srcOff, srcStride, h);
            }
            bilinearRows16(ref, refOff, refStride, f0, f1, scratch, 0, 16, h + 1);
            return bilinearSseSum16(scratch, 0, 16, g0, g1, src, srcOff, srcStride, h);
        }
        long left;
        long right;
        if (w == 32) {
            if (xoff == 0) {
                return bilinearSseSum32(ref, refOff, refStride, g0, g1, src, srcOff, srcStride, h);
            }
            bilinearRows32(ref, refOff, refStride, f0, f1, scratch, 0, 32, h + 1);
            return bilinearSseSum32(scratch, 0, 32, g0, g1, src, srcOff, srcStride, h);
        }
        if (xoff == 0) {
            left = bilinearSseSum32(ref, refOff, refStride, g0, g1, src, srcOff, srcStride, h);
            right = bilinearSseSum32(ref, refOff + 32, refStride, g0, g1, src, srcOff + 32, srcStride, h);
        } else {
            bilinearRows32(ref, refOff, refStride, f0, f1, scratch, 0, 64, h + 1);
            bilinearRows32(ref, refOff + 32, refStride, f0, f1, scratch, 32, 64, h + 1);
            left = bilinearSseSum32(scratch, 0, 64, g0, g1, src, srcOff, srcStride, h);
            right = bilinearSseSum32(scratch, 32, 64, g0, g1, src, srcOff + 32, srcStride, h);
        }
        return (left >>> 32) + (right >>> 32) << 32 | ((int) left + (int) right) & 0xFFFFFFFFL;
    }

    /**
     * Filters rows of a plane horizontally with a bilinear 2-tap filter into a scratch block of
     * 8-wide rows, two rows at a time.
     *
     * @param ref the plane
     * @param refOff the index of the block's top-left sample
     * @param refStride the plane's stride
     * @param f0 the first tap
     * @param f1 the second tap
     * @param out receives the filtered rows, 8 samples apart
     * @param rows the number of rows, odd
     */
    private static void bilinearRows8(short[] ref, int refOff, int refStride, int f0, int f1, short[] out, int rows) {
        var t0 = ShortVector.broadcast(S128, (short) f0);
        var t1 = ShortVector.broadcast(S128, (short) f1);
        var round = ShortVector.broadcast(S128, (short) 64);
        var r = 0;
        for (; r + 2 <= rows; r += 2, refOff += 2 * refStride) {
            var a0 = SHORTS_8.cast(ShortVector.fromArray(S128, ref, refOff));
            var b0 = SHORTS_8.cast(ShortVector.fromArray(S128, ref, refOff + 1));
            var a1 = SHORTS_8.cast(ShortVector.fromArray(S128, ref, refOff + refStride));
            var b1 = SHORTS_8.cast(ShortVector.fromArray(S128, ref, refOff + refStride + 1));
            var v0 = a0.lanewise(VectorOperators.MUL, SHORTS_8.cast(t0)).lanewise(VectorOperators.ADD,
                    b0.lanewise(VectorOperators.MUL, SHORTS_8.cast(t1)))
                    .lanewise(VectorOperators.ADD, SHORTS_8.cast(round));
            var v1 = a1.lanewise(VectorOperators.MUL, SHORTS_8.cast(t0)).lanewise(VectorOperators.ADD,
                    b1.lanewise(VectorOperators.MUL, SHORTS_8.cast(t1)))
                    .lanewise(VectorOperators.ADD, SHORTS_8.cast(round));
            SHORTS_8.cast(v0.lanewise(VectorOperators.ASHR, 7)).intoArray(out, r * 8);
            SHORTS_8.cast(v1.lanewise(VectorOperators.ASHR, 7)).intoArray(out, r * 8 + 8);
        }
        if (r < rows) {
            var a = SHORTS_8.cast(ShortVector.fromArray(S128, ref, refOff));
            var b = SHORTS_8.cast(ShortVector.fromArray(S128, ref, refOff + 1));
            var v = a.lanewise(VectorOperators.MUL, SHORTS_8.cast(t0)).lanewise(VectorOperators.ADD,
                    b.lanewise(VectorOperators.MUL, SHORTS_8.cast(t1)))
                    .lanewise(VectorOperators.ADD, SHORTS_8.cast(round));
            SHORTS_8.cast(v.lanewise(VectorOperators.ASHR, 7)).intoArray(out, r * 8);
        }
    }

    /**
     * Filters 8-wide rows vertically with a bilinear 2-tap filter and returns the sums of squared
     * and of signed differences against a source block, packed like {@link EncDsp#sseSum}.
     *
     * @param tmp the rows, one more than the height
     * @param tmpOff the index of the first row
     * @param tmpStride the distance between rows
     * @param g0 the first tap
     * @param g1 the second tap
     * @param src the source plane
     * @param srcOff the index of the source block's top-left sample
     * @param srcStride the source stride
     * @param h the height, even
     * @return the packed sums
     */
    private static long bilinearSseSum8(short[] tmp, int tmpOff, int tmpStride, int g0, int g1, short[] src,
                                        int srcOff, int srcStride, int h) {
        var t0 = ShortVector.broadcast(S128, (short) g0);
        var t1 = ShortVector.broadcast(S128, (short) g1);
        var round = ShortVector.broadcast(S128, (short) 64);
        var low = INTS_4.cast(LOW_HALVES_4);
        var sum = IntVector.zero(I128);
        var sse = IntVector.zero(I128);
        for (var r = 0; r < h; r++, srcOff += srcStride) {
            var o = tmpOff + r * tmpStride;
            var a = SHORTS_8.cast(ShortVector.fromArray(S128, tmp, o));
            var b = SHORTS_8.cast(ShortVector.fromArray(S128, tmp, o + tmpStride));
            var s = SHORTS_8.cast(ShortVector.fromArray(S128, src, srcOff));
            var v = a.lanewise(VectorOperators.MUL, SHORTS_8.cast(t0)).lanewise(VectorOperators.ADD,
                    b.lanewise(VectorOperators.MUL, SHORTS_8.cast(t1)))
                    .lanewise(VectorOperators.ADD, SHORTS_8.cast(round))
                    .lanewise(VectorOperators.ASHR, 7);
            var d = v.lanewise(VectorOperators.SUB, s);
            var di = INTS_4.cast(SHORTS_8.cast(d).reinterpretShape(I128, 0));
            sum = di.lanewise(VectorOperators.LSHL, 16).lanewise(VectorOperators.ASHR, 16).lanewise(
                    VectorOperators.ADD, di.lanewise(VectorOperators.ASHR, 16))
                    .lanewise(VectorOperators.ADD, INTS_4.cast(sum));
            var q = INTS_4.cast(SHORTS_8.cast(d.lanewise(VectorOperators.MUL, d)).reinterpretShape(I128, 0));
            sse = q.lanewise(VectorOperators.AND, INTS_4.cast(low)).lanewise(VectorOperators.ADD,
                    q.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, INTS_4.cast(sse));
        }
        var total = INTS_4.cast(sum).reduceLanes(VectorOperators.ADD);
        var squares = INTS_4.cast(sse).reduceLanes(VectorOperators.ADD);
        return (long) squares << 32 | (total & 0xFFFFFFFFL);
    }

    @Override
    void intProRow(int[] hbuf, short[] ref, int refOff, int refStride, int width, int height, int norm) {
        scalar.intProRow(hbuf, ref, refOff, refStride, width, height, norm);
    }

    @Override
    void intProCol(int[] vbuf, short[] ref, int refOff, int refStride, int width, int height, int norm) {
        scalar.intProCol(vbuf, ref, refOff, refStride, width, height, norm);
    }

    /**
     * Computes the residual of an 8-wide block, one row at a time.
     *
     * @param src the source plane
     * @param srcOff the index of the source block's top-left sample
     * @param srcStride the source stride
     * @param pred the prediction plane
     * @param predOff the index of the prediction block's top-left sample
     * @param predStride the prediction stride
     * @param diff receives the differences, row-major with the block width as stride
     * @param h the height
     */
    private static void subtract8(short[] src, int srcOff, int srcStride, short[] pred, int predOff, int predStride,
                                  int[] diff, int h) {
        var d = 0;
        for (var r = 0; r < h; r++, srcOff += srcStride, predOff += predStride, d += 8) {
            var v = SHORTS_8.cast(ShortVector.fromArray(S128, src, srcOff)).lanewise(VectorOperators.SUB,
                    SHORTS_8.cast(ShortVector.fromArray(S128, pred, predOff)));
            INTS_8.cast(v.convertShape(VectorOperators.S2I, I256, 0)).intoArray(diff, d);
        }
    }

    /**
     * Returns the sum of absolute differences of two 16-wide blocks, one row at a time.
     *
     * @param a the first plane
     * @param aOff the index of the first block's top-left sample
     * @param aStride the first plane's stride
     * @param b the second plane
     * @param bOff the index of the second block's top-left sample
     * @param bStride the second plane's stride
     * @param h the height, at most 64
     * @return the sum
     */
    private static int sad16(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride, int h) {
        var acc = ShortVector.zero(S256);
        for (var r = 0; r < h; r++, aOff += aStride, bOff += bStride) {
            var d0 = SHORTS_16.cast(ShortVector.fromArray(S256, a, aOff)).lanewise(VectorOperators.SUB,
                    SHORTS_16.cast(ShortVector.fromArray(S256, b, bOff))).lanewise(VectorOperators.ABS);
            acc = SHORTS_16.cast(acc).lanewise(VectorOperators.ADD, SHORTS_16.cast(d0));
        }
        var lanes = INTS_8.cast(SHORTS_16.cast(acc).reinterpretShape(I256, 0));
        var low = INTS_8.cast(LOW_HALVES_8);
        return lanes.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                lanes.lanewise(VectorOperators.LSHR, 16)).reduceLanes(VectorOperators.ADD);
    }

    /**
     * Returns the sum of absolute differences of two 32-wide blocks, one row at a time.
     *
     * @param a the first plane
     * @param aOff the index of the first block's top-left sample
     * @param aStride the first plane's stride
     * @param b the second plane
     * @param bOff the index of the second block's top-left sample
     * @param bStride the second plane's stride
     * @param h the height, at most 64
     * @return the sum
     */
    private static int sad32(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride, int h) {
        var acc = ShortVector.zero(S256);
        for (var r = 0; r < h; r++, aOff += aStride, bOff += bStride) {
            var d0 = SHORTS_16.cast(ShortVector.fromArray(S256, a, aOff)).lanewise(VectorOperators.SUB,
                    SHORTS_16.cast(ShortVector.fromArray(S256, b, bOff))).lanewise(VectorOperators.ABS);
            var d1 = SHORTS_16.cast(ShortVector.fromArray(S256, a, aOff + 16)).lanewise(VectorOperators.SUB,
                    SHORTS_16.cast(ShortVector.fromArray(S256, b, bOff + 16))).lanewise(VectorOperators.ABS);
            acc = SHORTS_16.cast(acc).lanewise(VectorOperators.ADD, SHORTS_16.cast(d0.lanewise(VectorOperators.ADD, d1)));
        }
        var lanes = INTS_8.cast(SHORTS_16.cast(acc).reinterpretShape(I256, 0));
        var low = INTS_8.cast(LOW_HALVES_8);
        return lanes.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.ADD,
                lanes.lanewise(VectorOperators.LSHR, 16)).reduceLanes(VectorOperators.ADD);
    }

    /**
     * Returns the sums of squared and of signed differences of two 16-wide blocks, packed like
     * {@link EncDsp#sseSum}, one row at a time.
     *
     * @param a the first plane
     * @param aOff the index of the first block's top-left sample
     * @param aStride the first plane's stride
     * @param b the second plane
     * @param bOff the index of the second block's top-left sample
     * @param bStride the second plane's stride
     * @param h the height, at most 64
     * @return the packed sums
     */
    private static long sseSum16(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride, int h) {
        var sum = ShortVector.zero(S256);
        var sse = IntVector.zero(I256);
        var low = INTS_8.cast(LOW_HALVES_8);
        for (var r = 0; r < h; r++, aOff += aStride, bOff += bStride) {
            var d0 = SHORTS_16.cast(ShortVector.fromArray(S256, a, aOff)).lanewise(VectorOperators.SUB,
                    SHORTS_16.cast(ShortVector.fromArray(S256, b, bOff)));
            sum = SHORTS_16.cast(sum).lanewise(VectorOperators.ADD, SHORTS_16.cast(d0));
            var q0 = INTS_8.cast(SHORTS_16.cast(d0.lanewise(VectorOperators.MUL, d0)).reinterpretShape(I256, 0));
            sse = q0.lanewise(VectorOperators.AND, INTS_8.cast(low)).lanewise(VectorOperators.ADD,
                    q0.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, INTS_8.cast(sse));
        }
        var s = INTS_8.cast(SHORTS_16.cast(sum).reinterpretShape(I256, 0));
        var total = s.lanewise(VectorOperators.LSHL, 16).lanewise(VectorOperators.ASHR, 16).lanewise(
                VectorOperators.ADD, s.lanewise(VectorOperators.ASHR, 16)).reduceLanes(VectorOperators.ADD);
        var squares = INTS_8.cast(sse).reduceLanes(VectorOperators.ADD);
        return (long) squares << 32 | (total & 0xFFFFFFFFL);
    }

    /**
     * Returns the sums of squared and of signed differences of two 32-wide blocks, packed like
     * {@link EncDsp#sseSum}, one row at a time.
     *
     * @param a the first plane
     * @param aOff the index of the first block's top-left sample
     * @param aStride the first plane's stride
     * @param b the second plane
     * @param bOff the index of the second block's top-left sample
     * @param bStride the second plane's stride
     * @param h the height, at most 64
     * @return the packed sums
     */
    private static long sseSum32(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride, int h) {
        var sum = ShortVector.zero(S256);
        var sse = IntVector.zero(I256);
        var low = INTS_8.cast(LOW_HALVES_8);
        for (var r = 0; r < h; r++, aOff += aStride, bOff += bStride) {
            var d0 = SHORTS_16.cast(ShortVector.fromArray(S256, a, aOff)).lanewise(VectorOperators.SUB,
                    SHORTS_16.cast(ShortVector.fromArray(S256, b, bOff)));
            var d1 = SHORTS_16.cast(ShortVector.fromArray(S256, a, aOff + 16)).lanewise(VectorOperators.SUB,
                    SHORTS_16.cast(ShortVector.fromArray(S256, b, bOff + 16)));
            sum = SHORTS_16.cast(sum).lanewise(VectorOperators.ADD, SHORTS_16.cast(d0.lanewise(VectorOperators.ADD, d1)));
            var q0 = INTS_8.cast(SHORTS_16.cast(d0.lanewise(VectorOperators.MUL, d0)).reinterpretShape(I256, 0));
            var q1 = INTS_8.cast(SHORTS_16.cast(d1.lanewise(VectorOperators.MUL, d1)).reinterpretShape(I256, 0));
            sse = q0.lanewise(VectorOperators.AND, INTS_8.cast(low)).lanewise(VectorOperators.ADD,
                    q0.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, q1.lanewise(VectorOperators.AND, INTS_8.cast(low)).lanewise(VectorOperators.ADD,
                    q1.lanewise(VectorOperators.LSHR, 16))).lanewise(VectorOperators.ADD, INTS_8.cast(sse));
        }
        var s = INTS_8.cast(SHORTS_16.cast(sum).reinterpretShape(I256, 0));
        var total = s.lanewise(VectorOperators.LSHL, 16).lanewise(VectorOperators.ASHR, 16).lanewise(
                VectorOperators.ADD, s.lanewise(VectorOperators.ASHR, 16)).reduceLanes(VectorOperators.ADD);
        var squares = INTS_8.cast(sse).reduceLanes(VectorOperators.ADD);
        return (long) squares << 32 | (total & 0xFFFFFFFFL);
    }

    /**
     * Computes the residual of a 16-wide block, one row at a time.
     *
     * @param src the source plane
     * @param srcOff the index of the source block's top-left sample
     * @param srcStride the source stride
     * @param pred the prediction plane
     * @param predOff the index of the prediction block's top-left sample
     * @param predStride the prediction stride
     * @param diff receives the differences
     * @param diffOff the index of the first difference
     * @param diffStride the distance between rows of differences
     * @param h the height
     */
    private static void subtract16(short[] src, int srcOff, int srcStride, short[] pred, int predOff, int predStride,
                                   int[] diff, int diffOff, int diffStride, int h) {
        for (var r = 0; r < h; r++, srcOff += srcStride, predOff += predStride, diffOff += diffStride) {
            var v0 = SHORTS_16.cast(ShortVector.fromArray(S256, src, srcOff)).lanewise(VectorOperators.SUB,
                    SHORTS_16.cast(ShortVector.fromArray(S256, pred, predOff)));
            INTS_8.cast(v0.convertShape(VectorOperators.S2I, I256, 0)).intoArray(diff, diffOff);
            INTS_8.cast(v0.convertShape(VectorOperators.S2I, I256, 1)).intoArray(diff, diffOff + 8);
        }
    }

    /**
     * Computes the residual of a 32-wide block, one row at a time.
     *
     * @param src the source plane
     * @param srcOff the index of the source block's top-left sample
     * @param srcStride the source stride
     * @param pred the prediction plane
     * @param predOff the index of the prediction block's top-left sample
     * @param predStride the prediction stride
     * @param diff receives the differences
     * @param diffOff the index of the first difference
     * @param diffStride the distance between rows of differences
     * @param h the height
     */
    private static void subtract32(short[] src, int srcOff, int srcStride, short[] pred, int predOff, int predStride,
                                   int[] diff, int diffOff, int diffStride, int h) {
        for (var r = 0; r < h; r++, srcOff += srcStride, predOff += predStride, diffOff += diffStride) {
            var v0 = SHORTS_16.cast(ShortVector.fromArray(S256, src, srcOff)).lanewise(VectorOperators.SUB,
                    SHORTS_16.cast(ShortVector.fromArray(S256, pred, predOff)));
            INTS_8.cast(v0.convertShape(VectorOperators.S2I, I256, 0)).intoArray(diff, diffOff);
            INTS_8.cast(v0.convertShape(VectorOperators.S2I, I256, 1)).intoArray(diff, diffOff + 8);
            var v1 = SHORTS_16.cast(ShortVector.fromArray(S256, src, srcOff + 16)).lanewise(VectorOperators.SUB,
                    SHORTS_16.cast(ShortVector.fromArray(S256, pred, predOff + 16)));
            INTS_8.cast(v1.convertShape(VectorOperators.S2I, I256, 0)).intoArray(diff, diffOff + 16);
            INTS_8.cast(v1.convertShape(VectorOperators.S2I, I256, 1)).intoArray(diff, diffOff + 24);
        }
    }

    /**
     * Filters rows of a plane horizontally with a bilinear 2-tap filter into a scratch block of
     * 16-wide strips ({@code aom_var_filter_block2d_bil_first_pass}).
     *
     * @param ref the plane
     * @param refOff the index of the block's top-left sample
     * @param refStride the plane's stride
     * @param f0 the first tap
     * @param f1 the second tap
     * @param out receives the filtered rows
     * @param outOff the index of the first filtered sample
     * @param outStride the distance between filtered rows
     * @param rows the number of rows
     */
    private static void bilinearRows16(short[] ref, int refOff, int refStride, int f0, int f1, short[] out,
                                       int outOff, int outStride, int rows) {
        var t0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) f0));
        var t1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) f1));
        var round = SHORTS_16.cast(ROUND_16);
        for (var r = 0; r < rows; r++, refOff += refStride, outOff += outStride) {
            var a0 = SHORTS_16.cast(ShortVector.fromArray(S256, ref, refOff));
            var b0 = SHORTS_16.cast(ShortVector.fromArray(S256, ref, refOff + 1));
            var v0 = a0.lanewise(VectorOperators.MUL, t0).lanewise(VectorOperators.ADD,
                    b0.lanewise(VectorOperators.MUL, t1)).lanewise(VectorOperators.ADD, round);
            SHORTS_16.cast(v0.lanewise(VectorOperators.ASHR, 7)).intoArray(out, outOff);
        }
    }

    /**
     * Filters rows of a plane horizontally with a bilinear 2-tap filter into a scratch block of
     * 32-wide strips ({@code aom_var_filter_block2d_bil_first_pass}).
     *
     * @param ref the plane
     * @param refOff the index of the block's top-left sample
     * @param refStride the plane's stride
     * @param f0 the first tap
     * @param f1 the second tap
     * @param out receives the filtered rows
     * @param outOff the index of the first filtered sample
     * @param outStride the distance between filtered rows
     * @param rows the number of rows
     */
    private static void bilinearRows32(short[] ref, int refOff, int refStride, int f0, int f1, short[] out,
                                       int outOff, int outStride, int rows) {
        var t0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) f0));
        var t1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) f1));
        var round = SHORTS_16.cast(ROUND_16);
        for (var r = 0; r < rows; r++, refOff += refStride, outOff += outStride) {
            var a0 = SHORTS_16.cast(ShortVector.fromArray(S256, ref, refOff));
            var b0 = SHORTS_16.cast(ShortVector.fromArray(S256, ref, refOff + 1));
            var v0 = a0.lanewise(VectorOperators.MUL, t0).lanewise(VectorOperators.ADD,
                    b0.lanewise(VectorOperators.MUL, t1)).lanewise(VectorOperators.ADD, round);
            SHORTS_16.cast(v0.lanewise(VectorOperators.ASHR, 7)).intoArray(out, outOff);
            var a1 = SHORTS_16.cast(ShortVector.fromArray(S256, ref, refOff + 16));
            var b1 = SHORTS_16.cast(ShortVector.fromArray(S256, ref, refOff + 17));
            var v1 = a1.lanewise(VectorOperators.MUL, t0).lanewise(VectorOperators.ADD,
                    b1.lanewise(VectorOperators.MUL, t1)).lanewise(VectorOperators.ADD, round);
            SHORTS_16.cast(v1.lanewise(VectorOperators.ASHR, 7)).intoArray(out, outOff + 16);
        }
    }

    /**
     * Filters 16-wide rows vertically with a bilinear 2-tap filter and returns the sums of squared
     * and of signed differences against a source block, packed like {@link EncDsp#sseSum}
     * ({@code aom_var_filter_block2d_bil_second_pass} with the variance).
     *
     * @param tmp the rows, one more than the height
     * @param tmpOff the index of the first row
     * @param tmpStride the distance between rows
     * @param g0 the first tap
     * @param g1 the second tap
     * @param src the source plane
     * @param srcOff the index of the source block's top-left sample
     * @param srcStride the source stride
     * @param h the height
     * @return the packed sums
     */
    private static long bilinearSseSum16(short[] tmp, int tmpOff, int tmpStride, int g0, int g1, short[] src,
                                         int srcOff, int srcStride, int h) {
        var t0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) g0));
        var t1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) g1));
        var round = SHORTS_16.cast(ROUND_16);
        var low = INTS_8.cast(LOW_HALVES_8);
        var sum = ShortVector.zero(S256);
        var sse = IntVector.zero(I256);
        for (var r = 0; r < h; r++, tmpOff += tmpStride, srcOff += srcStride) {
            var a0 = SHORTS_16.cast(ShortVector.fromArray(S256, tmp, tmpOff));
            var b0 = SHORTS_16.cast(ShortVector.fromArray(S256, tmp, tmpOff + tmpStride));
            var d0 = a0.lanewise(VectorOperators.MUL, t0).lanewise(VectorOperators.ADD,
                    b0.lanewise(VectorOperators.MUL, t1)).lanewise(VectorOperators.ADD, round)
                    .lanewise(VectorOperators.ASHR, 7).lanewise(VectorOperators.SUB,
                    SHORTS_16.cast(ShortVector.fromArray(S256, src, srcOff)));
            sum = SHORTS_16.cast(sum).lanewise(VectorOperators.ADD, SHORTS_16.cast(d0));
            var q0 = INTS_8.cast(SHORTS_16.cast(d0.lanewise(VectorOperators.MUL, d0)).reinterpretShape(I256, 0));
            sse = q0.lanewise(VectorOperators.AND, INTS_8.cast(low)).lanewise(VectorOperators.ADD,
                    q0.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, INTS_8.cast(sse));
        }
        var s = INTS_8.cast(SHORTS_16.cast(sum).reinterpretShape(I256, 0));
        var total = s.lanewise(VectorOperators.LSHL, 16).lanewise(VectorOperators.ASHR, 16).lanewise(
                VectorOperators.ADD, s.lanewise(VectorOperators.ASHR, 16)).reduceLanes(VectorOperators.ADD);
        var squares = INTS_8.cast(sse).reduceLanes(VectorOperators.ADD);
        return (long) squares << 32 | (total & 0xFFFFFFFFL);
    }

    /**
     * Filters 32-wide rows vertically with a bilinear 2-tap filter and returns the sums of squared
     * and of signed differences against a source block, packed like {@link EncDsp#sseSum}
     * ({@code aom_var_filter_block2d_bil_second_pass} with the variance).
     *
     * @param tmp the rows, one more than the height
     * @param tmpOff the index of the first row
     * @param tmpStride the distance between rows
     * @param g0 the first tap
     * @param g1 the second tap
     * @param src the source plane
     * @param srcOff the index of the source block's top-left sample
     * @param srcStride the source stride
     * @param h the height
     * @return the packed sums
     */
    private static long bilinearSseSum32(short[] tmp, int tmpOff, int tmpStride, int g0, int g1, short[] src,
                                         int srcOff, int srcStride, int h) {
        var t0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) g0));
        var t1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) g1));
        var round = SHORTS_16.cast(ROUND_16);
        var low = INTS_8.cast(LOW_HALVES_8);
        var sum = ShortVector.zero(S256);
        var sse = IntVector.zero(I256);
        for (var r = 0; r < h; r++, tmpOff += tmpStride, srcOff += srcStride) {
            var a0 = SHORTS_16.cast(ShortVector.fromArray(S256, tmp, tmpOff));
            var b0 = SHORTS_16.cast(ShortVector.fromArray(S256, tmp, tmpOff + tmpStride));
            var d0 = a0.lanewise(VectorOperators.MUL, t0).lanewise(VectorOperators.ADD,
                    b0.lanewise(VectorOperators.MUL, t1)).lanewise(VectorOperators.ADD, round)
                    .lanewise(VectorOperators.ASHR, 7).lanewise(VectorOperators.SUB,
                    SHORTS_16.cast(ShortVector.fromArray(S256, src, srcOff)));
            var a1 = SHORTS_16.cast(ShortVector.fromArray(S256, tmp, tmpOff + 16));
            var b1 = SHORTS_16.cast(ShortVector.fromArray(S256, tmp, tmpOff + tmpStride + 16));
            var d1 = a1.lanewise(VectorOperators.MUL, t0).lanewise(VectorOperators.ADD,
                    b1.lanewise(VectorOperators.MUL, t1)).lanewise(VectorOperators.ADD, round)
                    .lanewise(VectorOperators.ASHR, 7).lanewise(VectorOperators.SUB,
                    SHORTS_16.cast(ShortVector.fromArray(S256, src, srcOff + 16)));
            sum = SHORTS_16.cast(sum).lanewise(VectorOperators.ADD, SHORTS_16.cast(d0.lanewise(VectorOperators.ADD, d1)));
            var q0 = INTS_8.cast(SHORTS_16.cast(d0.lanewise(VectorOperators.MUL, d0)).reinterpretShape(I256, 0));
            var q1 = INTS_8.cast(SHORTS_16.cast(d1.lanewise(VectorOperators.MUL, d1)).reinterpretShape(I256, 0));
            sse = q0.lanewise(VectorOperators.AND, INTS_8.cast(low)).lanewise(VectorOperators.ADD,
                    q0.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.ADD, q1.lanewise(VectorOperators.AND, INTS_8.cast(low)).lanewise(VectorOperators.ADD,
                    q1.lanewise(VectorOperators.LSHR, 16))).lanewise(VectorOperators.ADD, INTS_8.cast(sse));
        }
        var s = INTS_8.cast(SHORTS_16.cast(sum).reinterpretShape(I256, 0));
        var total = s.lanewise(VectorOperators.LSHL, 16).lanewise(VectorOperators.ASHR, 16).lanewise(
                VectorOperators.ADD, s.lanewise(VectorOperators.ASHR, 16)).reduceLanes(VectorOperators.ADD);
        var squares = INTS_8.cast(sse).reduceLanes(VectorOperators.ADD);
        return (long) squares << 32 | (total & 0xFFFFFFFFL);
    }
}
