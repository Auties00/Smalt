package com.github.auties00.smalt.video.vp8.internal;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Implements {@link RtDsp} with the Vector API; the results equal {@link ScalarRtDsp}'s.
 *
 * @implNote This implementation widens the 16-bit difference sums to ints before reducing them,
 * since their total, up to {@code 256 * 255} in magnitude, does not fit 16 signed bits; the lane
 * layouts and the rules that keep the kernels compiled are described at the top of the class
 * body.
 */
public final class VectorRtDsp extends RtDsp {
    /*
     * A 16-sample row is one vector of 16 bytes widened to 16 shorts. Absolute differences are formed
     * on bytes with unsigned maximum and minimum and summed in 16-bit lanes; the sum of a 16x16 block
     * is at most 256 * 255, which the 16-bit lane reduction holds as an unsigned value. Differences
     * are formed and squared on shorts: a square is at most 255^2, below 2^16, so the 16-bit product
     * is exact as an unsigned value; each pair of squares is read as one int lo + 65536 hi and
     * accumulated together with its high halves hi, which recovers the exact sum(lo + hi) as
     * sum(lo + 65536 hi) - 65535 sum(hi) with one 16-bit multiply per row instead of two 32-bit ones.
     * The several-candidate sums of absolute differences load each source row once for all
     * candidates, and the half-pixel pairs filter the shared rows once for both neighbors, with the
     * average (a + b + 1) >> 1 that the half-pixel taps 64, 64 reduce to. The bilinear passes of the
     * sub-pixel variance run on shorts, where every product and sum stays below 32768, through a
     * scratch array holding the horizontally filtered rows. A zero offset makes its pass the identity,
     * (128 * s + 64) >> 7 == s, so a block with one zero offset is filtered in a single pass straight
     * from the reference.
     *
     * The group kernels hold one block per int lane: a 32-bit lane of an interleaved row is one
     * block's row of four samples, so a sample is a shift and a mask away, and the transforms are the
     * scalar arithmetic applied to eight blocks at once, with the 16-bit wrap of the inverse
     * transform's first pass as a shift left and right by 16. The kernels process two rows per
     * iteration (the sum of absolute differences, the smallest, four), which keeps each of them above
     * the JIT compiler's inlining limit for hot methods, so it is compiled on its own rather than
     * inside the motion search that calls it.
     */

    /*
     * Every Vector API operation goes through the per-shape methods (lanewise with an operator and a
     * vector operand, compare, blend, rearrange); constants are static final vectors; no method takes
     * or returns a vector. The shape-generic convenience methods (add, min, the scalar-operand
     * overloads) dispatch through code every vector shape shares, which the JIT compiler may leave
     * unintrinsified, so every call would run an allocating fallback. For the same reason every
     * vector loaded, converted or reinterpreted is cast through a static final constant holding its
     * exact class (such as SHORTS_16), and reinterpretations use reinterpretShape: Class.cast on a
     * constant class gives the compiler the exact class without a type profile. A vector or mask
     * computed before a loop and used inside it as the receiver of a call, or assigned in a loop and
     * used after it, is cast again at that call, because the compiler parses a loop before its back
     * edge and would otherwise see only the declared abstract type.
     */

    /**
     * The species of 16 bytes.
     */
    private static final VectorSpecies<Byte> B128 = ByteVector.SPECIES_128;

    /**
     * The species of 16 shorts.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * The species of 8 ints.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * The species of 32 bytes.
     */
    private static final VectorSpecies<Byte> B256 = ByteVector.SPECIES_256;

    /**
     * The species of 8 shorts.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * The exact class of 32-byte vectors.
     */
    private static final Class<ByteVector> BYTES_32 = vectorClass(B256);

    /**
     * The exact class of 8-short vectors.
     */
    private static final Class<ShortVector> SHORTS_8 = vectorClass(S128);

    /**
     * The exact class of 16-byte vectors.
     */
    private static final Class<ByteVector> BYTES_16 = vectorClass(B128);

    /**
     * The exact class of 16-short vectors.
     */
    private static final Class<ShortVector> SHORTS_16 = vectorClass(S256);

    /**
     * The exact class of 8-int vectors.
     */
    private static final Class<IntVector> INTS_8 = vectorClass(I256);

    /**
     * Sixteen shorts equal to the rounding term of the bilinear passes.
     */
    private static final ShortVector ROUND_16 = ShortVector.broadcast(S256, (short) 64);

    /**
     * The mask of the upper eight of sixteen short lanes.
     */
    private static final VectorMask<Short> UPPER_8 = VectorMask.fromLong(S256, 0xFF00L);

    /**
     * Sixteen shorts equal to {@code 255}, the largest sample.
     */
    private static final ShortVector MAX_SAMPLE_16 = ShortVector.broadcast(S256, (short) 255);

    /**
     * Eight ints equal to {@code 0xFFFF}, the low half of a lane.
     */
    private static final IntVector LOW_16 = IntVector.broadcast(I256, 0xFFFF);

    /**
     * Reads and writes eight bytes of a byte array as a little-endian {@code long}.
     */
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Sixteen shorts equal to {@code 1}, the rounding of the half-pixel average.
     */
    private static final ShortVector ONE_16 = ShortVector.broadcast(S256, (short) 1);

    /**
     * Eight ints equal to {@code 255}, the mask of one packed sample.
     */
    private static final IntVector I_255 = IntVector.broadcast(I256, 255);

    /**
     * Eight ints equal to {@code 2217}, the first rotation factor of the forward DCT.
     */
    private static final IntVector I_2217 = IntVector.broadcast(I256, 2217);

    /**
     * Eight ints equal to {@code 5352}, the second rotation factor of the forward DCT.
     */
    private static final IntVector I_5352 = IntVector.broadcast(I256, 5352);

    /**
     * Eight ints equal to {@code 1812}, the rounding of the first odd output of the forward DCT's
     * first pass.
     */
    private static final IntVector I_1812 = IntVector.broadcast(I256, 1812);

    /**
     * Eight ints equal to {@code 937}, the rounding of the second odd output of the forward DCT's
     * first pass.
     */
    private static final IntVector I_937 = IntVector.broadcast(I256, 937);

    /**
     * Eight ints equal to {@code 12000}, the rounding of the first odd output of the forward
     * DCT's second pass.
     */
    private static final IntVector I_12000 = IntVector.broadcast(I256, 12000);

    /**
     * Eight ints equal to {@code 51000}, the rounding of the second odd output of the forward
     * DCT's second pass.
     */
    private static final IntVector I_51000 = IntVector.broadcast(I256, 51000);

    /**
     * Eight ints equal to {@code 7}, the rounding of the even outputs of the forward DCT's second
     * pass.
     */
    private static final IntVector I_7 = IntVector.broadcast(I256, 7);

    /**
     * Eight ints equal to {@code 1}.
     */
    private static final IntVector I_1 = IntVector.broadcast(I256, 1);

    /**
     * Eight ints equal to {@code 4}, the rounding of the inverse DCT's second pass.
     */
    private static final IntVector I_4 = IntVector.broadcast(I256, 4);

    /**
     * Eight ints equal to the inverse DCT's {@code sqrt(2) * cos(pi / 8) - 1} in Q16.
     */
    private static final IntVector I_20091 = IntVector.broadcast(I256, ScalarVp8Dsp.COS_PI8_SQRT2_MINUS1);

    /**
     * Eight ints equal to the inverse DCT's {@code sqrt(2) * sin(pi / 8)} in Q16.
     */
    private static final IntVector I_35468 = IntVector.broadcast(I256, ScalarVp8Dsp.SIN_PI8_SQRT2);

    /**
     * Eight ints equal to {@link RtDsp#MAX_LEVEL}.
     */
    private static final IntVector I_MAX_LEVEL = IntVector.broadcast(I256, MAX_LEVEL);

    /**
     * Creates the vector implementation.
     */
    private VectorRtDsp() {
    }

    /**
     * Creates the vector implementation; called only when the Vector API is available.
     *
     * @return the implementation
     */
    static VectorRtDsp create() {
        return new VectorRtDsp();
    }

    /**
     * Returns the exact vector class of a species.
     *
     * @param species the vector species
     * @param <V> the vector type
     * @return the species' vector class
     */
    @SuppressWarnings("unchecked")
    private static <V> Class<V> vectorClass(VectorSpecies<?> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public int sad16(byte[] a, int aOff, int aStride, byte[] b, int bOff, int bStride) {
        var acc = SHORTS_16.cast(ShortVector.zero(S256));
        for (var r = 0; r < 16; r += 4, aOff += 4 * aStride, bOff += 4 * bStride) {
            var a0 = BYTES_16.cast(ByteVector.fromArray(B128, a, aOff));
            var b0 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff));
            var d0 = a0.lanewise(VectorOperators.UMAX, b0).lanewise(VectorOperators.SUB, a0.lanewise(VectorOperators.UMIN, b0));
            var a1 = BYTES_16.cast(ByteVector.fromArray(B128, a, aOff + aStride));
            var b1 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff + bStride));
            var d1 = a1.lanewise(VectorOperators.UMAX, b1).lanewise(VectorOperators.SUB, a1.lanewise(VectorOperators.UMIN, b1));
            var a2 = BYTES_16.cast(ByteVector.fromArray(B128, a, aOff + 2 * aStride));
            var b2 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff + 2 * bStride));
            var d2 = a2.lanewise(VectorOperators.UMAX, b2).lanewise(VectorOperators.SUB, a2.lanewise(VectorOperators.UMIN, b2));
            var a3 = BYTES_16.cast(ByteVector.fromArray(B128, a, aOff + 3 * aStride));
            var b3 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff + 3 * bStride));
            var d3 = a3.lanewise(VectorOperators.UMAX, b3).lanewise(VectorOperators.SUB, a3.lanewise(VectorOperators.UMIN, b3));
            var w0 = SHORTS_16.cast(d0.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var w1 = SHORTS_16.cast(d1.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var w2 = SHORTS_16.cast(d2.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var w3 = SHORTS_16.cast(d3.convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            acc = w0.lanewise(VectorOperators.ADD, w1).lanewise(VectorOperators.ADD, w2.lanewise(VectorOperators.ADD, w3))
                    .lanewise(VectorOperators.ADD, acc);
        }
        return SHORTS_16.cast(acc).reduceLanes(VectorOperators.ADD) & 0xFFFF;
    }

    @Override
    public int blockStats16(byte[] a, int aOff, int aStride, int[] columns) {
        var sums = SHORTS_16.cast(ShortVector.zero(S256));
        var squares = INTS_8.cast(IntVector.zero(I256));
        var highs = INTS_8.cast(IntVector.zero(I256));
        for (var r = 0; r < 16; r += 2, aOff += 2 * aStride) {
            var a0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, aOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var a1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, aOff + aStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            sums = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, sums);
            var q0 = INTS_8.cast(SHORTS_16.cast(a0.lanewise(VectorOperators.MUL, a0)).reinterpretShape(I256, 0));
            var q1 = INTS_8.cast(SHORTS_16.cast(a1.lanewise(VectorOperators.MUL, a1)).reinterpretShape(I256, 0));
            squares = q0.lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, squares);
            highs = q0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.ADD, q1.lanewise(VectorOperators.LSHR, 16))
                    .lanewise(VectorOperators.ADD, highs);
        }
        var columnSums = SHORTS_16.cast(sums);
        INTS_8.cast(columnSums.convertShape(VectorOperators.S2I, I256, 0)).intoArray(columns, 0);
        INTS_8.cast(columnSums.convertShape(VectorOperators.S2I, I256, 1)).intoArray(columns, 8);
        return INTS_8.cast(squares).reduceLanes(VectorOperators.ADD) - 65535 * INTS_8.cast(highs).reduceLanes(VectorOperators.ADD);
    }

    @Override
    public void chromaIntraErrors(byte[] src, int srcOff, int srcStride, byte[] edges, int dcU, int dcV, int[] out) {
        var above = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, edges, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var corner = SHORTS_16.cast(ShortVector.broadcast(S256, (short) (edges[32] & 0xFF)))
                .blend(ShortVector.broadcast(S256, (short) (edges[33] & 0xFF)), UPPER_8);
        var dc = SHORTS_16.cast(ShortVector.broadcast(S256, (short) dcU))
                .blend(ShortVector.broadcast(S256, (short) dcV), UPPER_8);
        var aboveMinusCorner = above.lanewise(VectorOperators.SUB, corner);
        var dcErrors = INTS_8.cast(IntVector.zero(I256));
        var verticalErrors = INTS_8.cast(IntVector.zero(I256));
        var horizontalErrors = INTS_8.cast(IntVector.zero(I256));
        var trueMotionErrors = INTS_8.cast(IntVector.zero(I256));
        for (var r = 0; r < 8; r++) {
            var p = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + r * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var left = SHORTS_16.cast(ShortVector.broadcast(S256, (short) (edges[16 + r] & 0xFF)))
                    .blend(ShortVector.broadcast(S256, (short) (edges[24 + r] & 0xFF)), UPPER_8);
            var tm = left.lanewise(VectorOperators.ADD, aboveMinusCorner).lanewise(VectorOperators.MAX, ShortVector.zero(S256))
                    .lanewise(VectorOperators.MIN, MAX_SAMPLE_16);
            var d0 = p.lanewise(VectorOperators.SUB, dc);
            var d1 = p.lanewise(VectorOperators.SUB, above);
            var d2 = p.lanewise(VectorOperators.SUB, left);
            var d3 = p.lanewise(VectorOperators.SUB, tm);
            var q0 = INTS_8.cast(SHORTS_16.cast(d0.lanewise(VectorOperators.MUL, d0)).reinterpretShape(I256, 0));
            var q1 = INTS_8.cast(SHORTS_16.cast(d1.lanewise(VectorOperators.MUL, d1)).reinterpretShape(I256, 0));
            var q2 = INTS_8.cast(SHORTS_16.cast(d2.lanewise(VectorOperators.MUL, d2)).reinterpretShape(I256, 0));
            var q3 = INTS_8.cast(SHORTS_16.cast(d3.lanewise(VectorOperators.MUL, d3)).reinterpretShape(I256, 0));
            dcErrors = q0.lanewise(VectorOperators.AND, LOW_16).lanewise(VectorOperators.ADD, q0.lanewise(VectorOperators.LSHR, 16))
                    .lanewise(VectorOperators.ADD, dcErrors);
            verticalErrors = q1.lanewise(VectorOperators.AND, LOW_16).lanewise(VectorOperators.ADD, q1.lanewise(VectorOperators.LSHR, 16))
                    .lanewise(VectorOperators.ADD, verticalErrors);
            horizontalErrors = q2.lanewise(VectorOperators.AND, LOW_16).lanewise(VectorOperators.ADD, q2.lanewise(VectorOperators.LSHR, 16))
                    .lanewise(VectorOperators.ADD, horizontalErrors);
            trueMotionErrors = q3.lanewise(VectorOperators.AND, LOW_16).lanewise(VectorOperators.ADD, q3.lanewise(VectorOperators.LSHR, 16))
                    .lanewise(VectorOperators.ADD, trueMotionErrors);
        }
        out[0] = INTS_8.cast(dcErrors).reduceLanes(VectorOperators.ADD);
        out[1] = INTS_8.cast(verticalErrors).reduceLanes(VectorOperators.ADD);
        out[2] = INTS_8.cast(horizontalErrors).reduceLanes(VectorOperators.ADD);
        out[3] = INTS_8.cast(trueMotionErrors).reduceLanes(VectorOperators.ADD);
    }

    @Override
    public void sad16x3(byte[] a, int aOff, int aStride, byte[] b, int bOff0, int bOff1, int bOff2,
                        int bStride, int[] out, int outOff) {
        var acc0 = SHORTS_16.cast(ShortVector.zero(S256));
        var acc1 = SHORTS_16.cast(ShortVector.zero(S256));
        var acc2 = SHORTS_16.cast(ShortVector.zero(S256));
        for (var r = 0; r < 16; r += 2) {
            var a0 = BYTES_16.cast(ByteVector.fromArray(B128, a, aOff));
            var a1 = BYTES_16.cast(ByteVector.fromArray(B128, a, aOff + aStride));
            var p0 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff0));
            var q0 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff0 + bStride));
            acc0 = SHORTS_16.cast(a0.lanewise(VectorOperators.UMAX, p0).lanewise(VectorOperators.SUB, a0.lanewise(VectorOperators.UMIN, p0)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.ADD, SHORTS_16.cast(a1.lanewise(VectorOperators.UMAX, q0).lanewise(VectorOperators.SUB, a1.lanewise(VectorOperators.UMIN, q0)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)))
                    .lanewise(VectorOperators.ADD, acc0);
            var p1 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff1));
            var q1 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff1 + bStride));
            acc1 = SHORTS_16.cast(a0.lanewise(VectorOperators.UMAX, p1).lanewise(VectorOperators.SUB, a0.lanewise(VectorOperators.UMIN, p1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.ADD, SHORTS_16.cast(a1.lanewise(VectorOperators.UMAX, q1).lanewise(VectorOperators.SUB, a1.lanewise(VectorOperators.UMIN, q1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)))
                    .lanewise(VectorOperators.ADD, acc1);
            var p2 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff2));
            var q2 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff2 + bStride));
            acc2 = SHORTS_16.cast(a0.lanewise(VectorOperators.UMAX, p2).lanewise(VectorOperators.SUB, a0.lanewise(VectorOperators.UMIN, p2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.ADD, SHORTS_16.cast(a1.lanewise(VectorOperators.UMAX, q2).lanewise(VectorOperators.SUB, a1.lanewise(VectorOperators.UMIN, q2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)))
                    .lanewise(VectorOperators.ADD, acc2);
            aOff += 2 * aStride;
            bOff0 += 2 * bStride;
            bOff1 += 2 * bStride;
            bOff2 += 2 * bStride;
        }
        out[outOff] = SHORTS_16.cast(acc0).reduceLanes(VectorOperators.ADD) & 0xFFFF;
        out[outOff + 1] = SHORTS_16.cast(acc1).reduceLanes(VectorOperators.ADD) & 0xFFFF;
        out[outOff + 2] = SHORTS_16.cast(acc2).reduceLanes(VectorOperators.ADD) & 0xFFFF;
    }

    @Override
    public void sad16x4(byte[] a, int aOff, int aStride, byte[] b, int bOff0, int bOff1, int bOff2, int bOff3,
                        int bStride, int[] out, int outOff) {
        var acc0 = SHORTS_16.cast(ShortVector.zero(S256));
        var acc1 = SHORTS_16.cast(ShortVector.zero(S256));
        var acc2 = SHORTS_16.cast(ShortVector.zero(S256));
        var acc3 = SHORTS_16.cast(ShortVector.zero(S256));
        for (var r = 0; r < 16; r += 2) {
            var a0 = BYTES_16.cast(ByteVector.fromArray(B128, a, aOff));
            var a1 = BYTES_16.cast(ByteVector.fromArray(B128, a, aOff + aStride));
            var p0 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff0));
            var q0 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff0 + bStride));
            acc0 = SHORTS_16.cast(a0.lanewise(VectorOperators.UMAX, p0).lanewise(VectorOperators.SUB, a0.lanewise(VectorOperators.UMIN, p0)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.ADD, SHORTS_16.cast(a1.lanewise(VectorOperators.UMAX, q0).lanewise(VectorOperators.SUB, a1.lanewise(VectorOperators.UMIN, q0)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)))
                    .lanewise(VectorOperators.ADD, acc0);
            var p1 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff1));
            var q1 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff1 + bStride));
            acc1 = SHORTS_16.cast(a0.lanewise(VectorOperators.UMAX, p1).lanewise(VectorOperators.SUB, a0.lanewise(VectorOperators.UMIN, p1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.ADD, SHORTS_16.cast(a1.lanewise(VectorOperators.UMAX, q1).lanewise(VectorOperators.SUB, a1.lanewise(VectorOperators.UMIN, q1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)))
                    .lanewise(VectorOperators.ADD, acc1);
            var p2 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff2));
            var q2 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff2 + bStride));
            acc2 = SHORTS_16.cast(a0.lanewise(VectorOperators.UMAX, p2).lanewise(VectorOperators.SUB, a0.lanewise(VectorOperators.UMIN, p2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.ADD, SHORTS_16.cast(a1.lanewise(VectorOperators.UMAX, q2).lanewise(VectorOperators.SUB, a1.lanewise(VectorOperators.UMIN, q2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)))
                    .lanewise(VectorOperators.ADD, acc2);
            var p3 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff3));
            var q3 = BYTES_16.cast(ByteVector.fromArray(B128, b, bOff3 + bStride));
            acc3 = SHORTS_16.cast(a0.lanewise(VectorOperators.UMAX, p3).lanewise(VectorOperators.SUB, a0.lanewise(VectorOperators.UMIN, p3)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                    .lanewise(VectorOperators.ADD, SHORTS_16.cast(a1.lanewise(VectorOperators.UMAX, q3).lanewise(VectorOperators.SUB, a1.lanewise(VectorOperators.UMIN, q3)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)))
                    .lanewise(VectorOperators.ADD, acc3);
            aOff += 2 * aStride;
            bOff0 += 2 * bStride;
            bOff1 += 2 * bStride;
            bOff2 += 2 * bStride;
            bOff3 += 2 * bStride;
        }
        out[outOff] = SHORTS_16.cast(acc0).reduceLanes(VectorOperators.ADD) & 0xFFFF;
        out[outOff + 1] = SHORTS_16.cast(acc1).reduceLanes(VectorOperators.ADD) & 0xFFFF;
        out[outOff + 2] = SHORTS_16.cast(acc2).reduceLanes(VectorOperators.ADD) & 0xFFFF;
        out[outOff + 3] = SHORTS_16.cast(acc3).reduceLanes(VectorOperators.ADD) & 0xFFFF;
    }

    @Override
    public long variance16(byte[] a, int aOff, int aStride, byte[] b, int bOff, int bStride) {
        var squares = INTS_8.cast(IntVector.zero(I256));
        var highs = INTS_8.cast(IntVector.zero(I256));
        var sums = SHORTS_16.cast(ShortVector.zero(S256));
        for (var r = 0; r < 16; r += 2, aOff += 2 * aStride, bOff += 2 * bStride) {
            var a0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, aOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var b0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var a1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, a, aOff + aStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var b1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bOff + bStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var d0 = a0.lanewise(VectorOperators.SUB, b0);
            var d1 = a1.lanewise(VectorOperators.SUB, b1);
            sums = d0.lanewise(VectorOperators.ADD, d1).lanewise(VectorOperators.ADD, sums);
            var q0 = INTS_8.cast(SHORTS_16.cast(d0.lanewise(VectorOperators.MUL, d0)).reinterpretShape(I256, 0));
            var q1 = INTS_8.cast(SHORTS_16.cast(d1.lanewise(VectorOperators.MUL, d1)).reinterpretShape(I256, 0));
            squares = q0.lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, squares);
            highs = q0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.ADD, q1.lanewise(VectorOperators.LSHR, 16))
                    .lanewise(VectorOperators.ADD, highs);
        }
        var sum = SHORTS_16.cast(sums);
        var sumLow = INTS_8.cast(sum.convertShape(VectorOperators.S2I, I256, 0));
        var sumHigh = INTS_8.cast(sum.convertShape(VectorOperators.S2I, I256, 1));
        var total = sumLow.lanewise(VectorOperators.ADD, sumHigh).reduceLanes(VectorOperators.ADD);
        return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD)
                - 65535 * INTS_8.cast(highs).reduceLanes(VectorOperators.ADD), total);
    }

    @Override
    public long subpelVariance16(byte[] ref, int refOff, int refStride, int xOffset, int yOffset, byte[] b, int bOff,
                                 int bStride, short[] scratch) {
        if (xOffset == 0) {
            if (yOffset == 0) {
                return variance16(ref, refOff, refStride, b, bOff, bStride);
            }
            return verticalOnly16(ref, refOff, refStride, BILINEAR_TAPS[yOffset * 2], BILINEAR_TAPS[yOffset * 2 + 1], b,
                    bOff, bStride);
        }
        if (yOffset == 0) {
            return horizontalOnly16(ref, refOff, refStride, BILINEAR_TAPS[xOffset * 2], BILINEAR_TAPS[xOffset * 2 + 1], b,
                    bOff, bStride);
        }
        horizontal16(ref, refOff, refStride, BILINEAR_TAPS[xOffset * 2], BILINEAR_TAPS[xOffset * 2 + 1], scratch);
        return vertical16(scratch, BILINEAR_TAPS[yOffset * 2], BILINEAR_TAPS[yOffset * 2 + 1], b, bOff, bStride);
    }

    /**
     * Applies the horizontal bilinear pass to 16 rows of 16 samples and returns the packed sums
     * of the differences of the result with a second block: the sub-pixel variance of a block
     * whose vertical offset is zero.
     *
     * @param ref       the reference samples
     * @param refOff    the index of the first sample
     * @param refStride the reference row stride
     * @param t0        the tap of the left sample
     * @param t1        the tap of the right sample
     * @param b         the second samples
     * @param bOff      the index of the second block's top-left sample
     * @param bStride   the second block's row stride
     * @return the packed sums
     */
    private static long horizontalOnly16(byte[] ref, int refOff, int refStride, int t0, int t1, byte[] b, int bOff,
                                         int bStride) {
        var tap0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t0));
        var tap1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t1));
        var squares = INTS_8.cast(IntVector.zero(I256));
        var highs = INTS_8.cast(IntVector.zero(I256));
        var sums = SHORTS_16.cast(ShortVector.zero(S256));
        for (var r = 0; r < 16; r += 2, refOff += 2 * refStride, bOff += 2 * bStride) {
            var l0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var l1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var p0 = SHORTS_16.cast(l0.lanewise(VectorOperators.MUL, tap0).lanewise(VectorOperators.ADD,
                    r0.lanewise(VectorOperators.MUL, tap1)).lanewise(VectorOperators.ADD, ROUND_16)).lanewise(VectorOperators.LSHR, 7);
            var p1 = SHORTS_16.cast(l1.lanewise(VectorOperators.MUL, tap0).lanewise(VectorOperators.ADD,
                    r1.lanewise(VectorOperators.MUL, tap1)).lanewise(VectorOperators.ADD, ROUND_16)).lanewise(VectorOperators.LSHR, 7);
            var b0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var b1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bOff + bStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var d0 = SHORTS_16.cast(p0).lanewise(VectorOperators.SUB, b0);
            var d1 = SHORTS_16.cast(p1).lanewise(VectorOperators.SUB, b1);
            sums = d0.lanewise(VectorOperators.ADD, d1).lanewise(VectorOperators.ADD, sums);
            var q0 = INTS_8.cast(SHORTS_16.cast(d0.lanewise(VectorOperators.MUL, d0)).reinterpretShape(I256, 0));
            var q1 = INTS_8.cast(SHORTS_16.cast(d1.lanewise(VectorOperators.MUL, d1)).reinterpretShape(I256, 0));
            squares = q0.lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, squares);
            highs = q0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.ADD, q1.lanewise(VectorOperators.LSHR, 16))
                    .lanewise(VectorOperators.ADD, highs);
        }
        var sum = SHORTS_16.cast(sums);
        var sumLow = INTS_8.cast(sum.convertShape(VectorOperators.S2I, I256, 0));
        var sumHigh = INTS_8.cast(sum.convertShape(VectorOperators.S2I, I256, 1));
        var total = sumLow.lanewise(VectorOperators.ADD, sumHigh).reduceLanes(VectorOperators.ADD);
        return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD)
                - 65535 * INTS_8.cast(highs).reduceLanes(VectorOperators.ADD), total);
    }

    /**
     * Applies the vertical bilinear pass to 17 rows of 16 reference samples and returns the
     * packed sums of the differences of the 16x16 result with a second block: the sub-pixel
     * variance of a block whose horizontal offset is zero.
     *
     * @param ref       the reference samples
     * @param refOff    the index of the first sample
     * @param refStride the reference row stride
     * @param t0        the tap of the upper row
     * @param t1        the tap of the lower row
     * @param b         the second samples
     * @param bOff      the index of the second block's top-left sample
     * @param bStride   the second block's row stride
     * @return the packed sums
     */
    private static long verticalOnly16(byte[] ref, int refOff, int refStride, int t0, int t1, byte[] b, int bOff,
                                       int bStride) {
        var tap0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t0));
        var tap1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t1));
        var squares = INTS_8.cast(IntVector.zero(I256));
        var highs = INTS_8.cast(IntVector.zero(I256));
        var sums = SHORTS_16.cast(ShortVector.zero(S256));
        for (var r = 0; r < 16; r += 2, refOff += 2 * refStride, bOff += 2 * bStride) {
            var s0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var s1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var s2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 2 * refStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var p0 = SHORTS_16.cast(s0.lanewise(VectorOperators.MUL, tap0).lanewise(VectorOperators.ADD,
                    s1.lanewise(VectorOperators.MUL, tap1)).lanewise(VectorOperators.ADD, ROUND_16)).lanewise(VectorOperators.LSHR, 7);
            var p1 = SHORTS_16.cast(s1.lanewise(VectorOperators.MUL, tap0).lanewise(VectorOperators.ADD,
                    s2.lanewise(VectorOperators.MUL, tap1)).lanewise(VectorOperators.ADD, ROUND_16)).lanewise(VectorOperators.LSHR, 7);
            var b0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var b1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bOff + bStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var d0 = SHORTS_16.cast(p0).lanewise(VectorOperators.SUB, b0);
            var d1 = SHORTS_16.cast(p1).lanewise(VectorOperators.SUB, b1);
            sums = d0.lanewise(VectorOperators.ADD, d1).lanewise(VectorOperators.ADD, sums);
            var q0 = INTS_8.cast(SHORTS_16.cast(d0.lanewise(VectorOperators.MUL, d0)).reinterpretShape(I256, 0));
            var q1 = INTS_8.cast(SHORTS_16.cast(d1.lanewise(VectorOperators.MUL, d1)).reinterpretShape(I256, 0));
            squares = q0.lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, squares);
            highs = q0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.ADD, q1.lanewise(VectorOperators.LSHR, 16))
                    .lanewise(VectorOperators.ADD, highs);
        }
        var sum = SHORTS_16.cast(sums);
        var sumLow = INTS_8.cast(sum.convertShape(VectorOperators.S2I, I256, 0));
        var sumHigh = INTS_8.cast(sum.convertShape(VectorOperators.S2I, I256, 1));
        var total = sumLow.lanewise(VectorOperators.ADD, sumHigh).reduceLanes(VectorOperators.ADD);
        return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD)
                - 65535 * INTS_8.cast(highs).reduceLanes(VectorOperators.ADD), total);
    }

    /**
     * Applies the horizontal bilinear pass to 17 rows of 16 samples, writing them to the
     * scratch array 16 shorts apart.
     *
     * @param ref       the reference samples
     * @param refOff    the index of the first sample
     * @param refStride the reference row stride
     * @param t0        the tap of the left sample
     * @param t1        the tap of the right sample
     * @param scratch   receives the filtered rows
     */
    private static void horizontal16(byte[] ref, int refOff, int refStride, int t0, int t1, short[] scratch) {
        var tap0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t0));
        var tap1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t1));
        for (var r = 0; r < 16; r += 2, refOff += 2 * refStride) {
            var l0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var l1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var f0 = l0.lanewise(VectorOperators.MUL, tap0).lanewise(VectorOperators.ADD, r0.lanewise(VectorOperators.MUL, tap1))
                    .lanewise(VectorOperators.ADD, ROUND_16);
            var f1 = l1.lanewise(VectorOperators.MUL, tap0).lanewise(VectorOperators.ADD, r1.lanewise(VectorOperators.MUL, tap1))
                    .lanewise(VectorOperators.ADD, ROUND_16);
            SHORTS_16.cast(f0).lanewise(VectorOperators.LSHR, 7).intoArray(scratch, r * 16);
            SHORTS_16.cast(f1).lanewise(VectorOperators.LSHR, 7).intoArray(scratch, r * 16 + 16);
        }
        var left = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var right = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 1))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var last = left.lanewise(VectorOperators.MUL, tap0).lanewise(VectorOperators.ADD, right.lanewise(VectorOperators.MUL, tap1))
                .lanewise(VectorOperators.ADD, ROUND_16);
        SHORTS_16.cast(last).lanewise(VectorOperators.LSHR, 7).intoArray(scratch, 256);
    }

    /**
     * Applies the vertical bilinear pass to the 17 rows of {@link #horizontal16} and returns the
     * packed sums of the differences of the 16x16 result with a second block.
     *
     * @param scratch the horizontally filtered rows
     * @param t0      the tap of the upper row
     * @param t1      the tap of the lower row
     * @param b       the second samples
     * @param bOff    the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @return the packed sums
     */
    private static long vertical16(short[] scratch, int t0, int t1, byte[] b, int bOff, int bStride) {
        var tap0 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t0));
        var tap1 = SHORTS_16.cast(ShortVector.broadcast(S256, (short) t1));
        var squares = INTS_8.cast(IntVector.zero(I256));
        var highs = INTS_8.cast(IntVector.zero(I256));
        var sums = SHORTS_16.cast(ShortVector.zero(S256));
        for (var r = 0; r < 16; r += 2, bOff += 2 * bStride) {
            var s0 = SHORTS_16.cast(ShortVector.fromArray(S256, scratch, r * 16));
            var s1 = SHORTS_16.cast(ShortVector.fromArray(S256, scratch, r * 16 + 16));
            var s2 = SHORTS_16.cast(ShortVector.fromArray(S256, scratch, r * 16 + 32));
            var p0 = SHORTS_16.cast(s0.lanewise(VectorOperators.MUL, tap0).lanewise(VectorOperators.ADD,
                    s1.lanewise(VectorOperators.MUL, tap1)).lanewise(VectorOperators.ADD, ROUND_16)).lanewise(VectorOperators.LSHR, 7);
            var p1 = SHORTS_16.cast(s1.lanewise(VectorOperators.MUL, tap0).lanewise(VectorOperators.ADD,
                    s2.lanewise(VectorOperators.MUL, tap1)).lanewise(VectorOperators.ADD, ROUND_16)).lanewise(VectorOperators.LSHR, 7);
            var b0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var b1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bOff + bStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var d0 = SHORTS_16.cast(p0).lanewise(VectorOperators.SUB, b0);
            var d1 = SHORTS_16.cast(p1).lanewise(VectorOperators.SUB, b1);
            sums = d0.lanewise(VectorOperators.ADD, d1).lanewise(VectorOperators.ADD, sums);
            var q0 = INTS_8.cast(SHORTS_16.cast(d0.lanewise(VectorOperators.MUL, d0)).reinterpretShape(I256, 0));
            var q1 = INTS_8.cast(SHORTS_16.cast(d1.lanewise(VectorOperators.MUL, d1)).reinterpretShape(I256, 0));
            squares = q0.lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, squares);
            highs = q0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.ADD, q1.lanewise(VectorOperators.LSHR, 16))
                    .lanewise(VectorOperators.ADD, highs);
        }
        var sum = SHORTS_16.cast(sums);
        var sumLow = INTS_8.cast(sum.convertShape(VectorOperators.S2I, I256, 0));
        var sumHigh = INTS_8.cast(sum.convertShape(VectorOperators.S2I, I256, 1));
        var total = sumLow.lanewise(VectorOperators.ADD, sumHigh).reduceLanes(VectorOperators.ADD);
        return pack(INTS_8.cast(squares).reduceLanes(VectorOperators.ADD)
                - 65535 * INTS_8.cast(highs).reduceLanes(VectorOperators.ADD), total);
    }

    @Override
    public void horizontalPass16(byte[] ref, int refOff, int refStride, int xOffset, short[] scratch) {
        horizontal16(ref, refOff, refStride, BILINEAR_TAPS[xOffset * 2], BILINEAR_TAPS[xOffset * 2 + 1], scratch);
    }

    @Override
    public long verticalPassVariance16(short[] scratch, int yOffset, byte[] b, int bOff, int bStride) {
        return vertical16(scratch, BILINEAR_TAPS[yOffset * 2], BILINEAR_TAPS[yOffset * 2 + 1], b, bOff, bStride);
    }

    @Override
    public void groupMacroblock(byte[] y, int yOff, int yStride, byte[] u, byte[] v, int uvOff, int uvStride,
                                byte[] groups) {
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 0 * yStride)).intoArray(groups, 0);
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 4 * yStride)).intoArray(groups, 16);
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 1 * yStride)).intoArray(groups, 32);
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 5 * yStride)).intoArray(groups, 48);
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 2 * yStride)).intoArray(groups, 64);
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 6 * yStride)).intoArray(groups, 80);
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 3 * yStride)).intoArray(groups, 96);
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 7 * yStride)).intoArray(groups, 112);
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 8 * yStride)).intoArray(groups, 128);
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 12 * yStride)).intoArray(groups, 144);
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 9 * yStride)).intoArray(groups, 160);
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 13 * yStride)).intoArray(groups, 176);
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 10 * yStride)).intoArray(groups, 192);
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 14 * yStride)).intoArray(groups, 208);
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 11 * yStride)).intoArray(groups, 224);
        BYTES_16.cast(ByteVector.fromArray(B128, y, yOff + 15 * yStride)).intoArray(groups, 240);
        LONG.set(groups, 256, (long) LONG.get(u, uvOff + 0 * uvStride));
        LONG.set(groups, 264, (long) LONG.get(u, uvOff + 4 * uvStride));
        LONG.set(groups, 272, (long) LONG.get(v, uvOff + 0 * uvStride));
        LONG.set(groups, 280, (long) LONG.get(v, uvOff + 4 * uvStride));
        LONG.set(groups, 288, (long) LONG.get(u, uvOff + 1 * uvStride));
        LONG.set(groups, 296, (long) LONG.get(u, uvOff + 5 * uvStride));
        LONG.set(groups, 304, (long) LONG.get(v, uvOff + 1 * uvStride));
        LONG.set(groups, 312, (long) LONG.get(v, uvOff + 5 * uvStride));
        LONG.set(groups, 320, (long) LONG.get(u, uvOff + 2 * uvStride));
        LONG.set(groups, 328, (long) LONG.get(u, uvOff + 6 * uvStride));
        LONG.set(groups, 336, (long) LONG.get(v, uvOff + 2 * uvStride));
        LONG.set(groups, 344, (long) LONG.get(v, uvOff + 6 * uvStride));
        LONG.set(groups, 352, (long) LONG.get(u, uvOff + 3 * uvStride));
        LONG.set(groups, 360, (long) LONG.get(u, uvOff + 7 * uvStride));
        LONG.set(groups, 368, (long) LONG.get(v, uvOff + 3 * uvStride));
        LONG.set(groups, 376, (long) LONG.get(v, uvOff + 7 * uvStride));
    }

    @Override
    public void halfpelHorizontalPair(byte[] ref, int refOff, int refStride, byte[] b, int bOff, int bStride,
                                      long[] out) {
        var leftSquares = INTS_8.cast(IntVector.zero(I256));
        var leftHighs = INTS_8.cast(IntVector.zero(I256));
        var leftSums = SHORTS_16.cast(ShortVector.zero(S256));
        var rightSquares = INTS_8.cast(IntVector.zero(I256));
        var rightHighs = INTS_8.cast(IntVector.zero(I256));
        var rightSums = SHORTS_16.cast(ShortVector.zero(S256));
        for (var r = 0; r < 16; r += 2, refOff += 2 * refStride, bOff += 2 * bStride) {
            var l0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff - 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var c0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var x0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var s0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bOff)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var l1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride - 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var c1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var x1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var s1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bOff + bStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var dl0 = SHORTS_16.cast(l0.lanewise(VectorOperators.ADD, c0).lanewise(VectorOperators.ADD, ONE_16))
                    .lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.SUB, s0);
            var dr0 = SHORTS_16.cast(c0.lanewise(VectorOperators.ADD, x0).lanewise(VectorOperators.ADD, ONE_16))
                    .lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.SUB, s0);
            var dl1 = SHORTS_16.cast(l1.lanewise(VectorOperators.ADD, c1).lanewise(VectorOperators.ADD, ONE_16))
                    .lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.SUB, s1);
            var dr1 = SHORTS_16.cast(c1.lanewise(VectorOperators.ADD, x1).lanewise(VectorOperators.ADD, ONE_16))
                    .lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.SUB, s1);
            leftSums = dl0.lanewise(VectorOperators.ADD, dl1).lanewise(VectorOperators.ADD, leftSums);
            rightSums = dr0.lanewise(VectorOperators.ADD, dr1).lanewise(VectorOperators.ADD, rightSums);
            var ql0 = INTS_8.cast(SHORTS_16.cast(dl0.lanewise(VectorOperators.MUL, dl0)).reinterpretShape(I256, 0));
            var ql1 = INTS_8.cast(SHORTS_16.cast(dl1.lanewise(VectorOperators.MUL, dl1)).reinterpretShape(I256, 0));
            var qr0 = INTS_8.cast(SHORTS_16.cast(dr0.lanewise(VectorOperators.MUL, dr0)).reinterpretShape(I256, 0));
            var qr1 = INTS_8.cast(SHORTS_16.cast(dr1.lanewise(VectorOperators.MUL, dr1)).reinterpretShape(I256, 0));
            leftSquares = ql0.lanewise(VectorOperators.ADD, ql1).lanewise(VectorOperators.ADD, leftSquares);
            rightSquares = qr0.lanewise(VectorOperators.ADD, qr1).lanewise(VectorOperators.ADD, rightSquares);
            leftHighs = ql0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.ADD, ql1.lanewise(VectorOperators.LSHR, 16))
                    .lanewise(VectorOperators.ADD, leftHighs);
            rightHighs = qr0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.ADD, qr1.lanewise(VectorOperators.LSHR, 16))
                    .lanewise(VectorOperators.ADD, rightHighs);
        }
        var left = SHORTS_16.cast(leftSums);
        var right = SHORTS_16.cast(rightSums);
        out[0] = pack(INTS_8.cast(leftSquares).reduceLanes(VectorOperators.ADD)
                        - 65535 * INTS_8.cast(leftHighs).reduceLanes(VectorOperators.ADD),
                INTS_8.cast(left.convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.ADD,
                        INTS_8.cast(left.convertShape(VectorOperators.S2I, I256, 1))).reduceLanes(VectorOperators.ADD));
        out[1] = pack(INTS_8.cast(rightSquares).reduceLanes(VectorOperators.ADD)
                        - 65535 * INTS_8.cast(rightHighs).reduceLanes(VectorOperators.ADD),
                INTS_8.cast(right.convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.ADD,
                        INTS_8.cast(right.convertShape(VectorOperators.S2I, I256, 1))).reduceLanes(VectorOperators.ADD));
    }

    @Override
    public void halfpelVerticalPair(byte[] ref, int refOff, int refStride, byte[] b, int bOff, int bStride,
                                    long[] out) {
        var upSquares = INTS_8.cast(IntVector.zero(I256));
        var upHighs = INTS_8.cast(IntVector.zero(I256));
        var upSums = SHORTS_16.cast(ShortVector.zero(S256));
        var downSquares = INTS_8.cast(IntVector.zero(I256));
        var downHighs = INTS_8.cast(IntVector.zero(I256));
        var downSums = SHORTS_16.cast(ShortVector.zero(S256));
        for (var r = 0; r < 16; r += 2, refOff += 2 * refStride, bOff += 2 * bStride) {
            var above = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff - refStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var row0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var row1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + refStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var below = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, ref, refOff + 2 * refStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var s0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bOff)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var s1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, b, bOff + bStride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var middle = SHORTS_16.cast(row0.lanewise(VectorOperators.ADD, row1).lanewise(VectorOperators.ADD, ONE_16))
                    .lanewise(VectorOperators.LSHR, 1);
            var du0 = SHORTS_16.cast(above.lanewise(VectorOperators.ADD, row0).lanewise(VectorOperators.ADD, ONE_16))
                    .lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.SUB, s0);
            var du1 = middle.lanewise(VectorOperators.SUB, s1);
            var dd0 = middle.lanewise(VectorOperators.SUB, s0);
            var dd1 = SHORTS_16.cast(row1.lanewise(VectorOperators.ADD, below).lanewise(VectorOperators.ADD, ONE_16))
                    .lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.SUB, s1);
            upSums = du0.lanewise(VectorOperators.ADD, du1).lanewise(VectorOperators.ADD, upSums);
            downSums = dd0.lanewise(VectorOperators.ADD, dd1).lanewise(VectorOperators.ADD, downSums);
            var qu0 = INTS_8.cast(SHORTS_16.cast(du0.lanewise(VectorOperators.MUL, du0)).reinterpretShape(I256, 0));
            var qu1 = INTS_8.cast(SHORTS_16.cast(du1.lanewise(VectorOperators.MUL, du1)).reinterpretShape(I256, 0));
            var qd0 = INTS_8.cast(SHORTS_16.cast(dd0.lanewise(VectorOperators.MUL, dd0)).reinterpretShape(I256, 0));
            var qd1 = INTS_8.cast(SHORTS_16.cast(dd1.lanewise(VectorOperators.MUL, dd1)).reinterpretShape(I256, 0));
            upSquares = qu0.lanewise(VectorOperators.ADD, qu1).lanewise(VectorOperators.ADD, upSquares);
            downSquares = qd0.lanewise(VectorOperators.ADD, qd1).lanewise(VectorOperators.ADD, downSquares);
            upHighs = qu0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.ADD, qu1.lanewise(VectorOperators.LSHR, 16))
                    .lanewise(VectorOperators.ADD, upHighs);
            downHighs = qd0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.ADD, qd1.lanewise(VectorOperators.LSHR, 16))
                    .lanewise(VectorOperators.ADD, downHighs);
        }
        var up = SHORTS_16.cast(upSums);
        var down = SHORTS_16.cast(downSums);
        out[0] = pack(INTS_8.cast(upSquares).reduceLanes(VectorOperators.ADD)
                        - 65535 * INTS_8.cast(upHighs).reduceLanes(VectorOperators.ADD),
                INTS_8.cast(up.convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.ADD,
                        INTS_8.cast(up.convertShape(VectorOperators.S2I, I256, 1))).reduceLanes(VectorOperators.ADD));
        out[1] = pack(INTS_8.cast(downSquares).reduceLanes(VectorOperators.ADD)
                        - 65535 * INTS_8.cast(downHighs).reduceLanes(VectorOperators.ADD),
                INTS_8.cast(down.convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.ADD,
                        INTS_8.cast(down.convertShape(VectorOperators.S2I, I256, 1))).reduceLanes(VectorOperators.ADD));
    }

    @Override
    public int forwardDct8(byte[] src, int srcOff, byte[] pred, int predOff, int[] coef, int coefOff) {
        for (var r = 0; r < 4; r++) {
            var s = INTS_8.cast(BYTES_32.cast(ByteVector.fromArray(B256, src, srcOff + 32 * r)).reinterpretShape(I256, 0));
            var p = INTS_8.cast(BYTES_32.cast(ByteVector.fromArray(B256, pred, predOff + 32 * r)).reinterpretShape(I256, 0));
            var d0 = s.lanewise(VectorOperators.AND, I_255).lanewise(VectorOperators.SUB, p.lanewise(VectorOperators.AND, I_255));
            var d1 = s.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, I_255)
                    .lanewise(VectorOperators.SUB, p.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, I_255));
            var d2 = s.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.AND, I_255)
                    .lanewise(VectorOperators.SUB, p.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.AND, I_255));
            var d3 = s.lanewise(VectorOperators.LSHR, 24).lanewise(VectorOperators.SUB, p.lanewise(VectorOperators.LSHR, 24));
            var a0 = d0.lanewise(VectorOperators.ADD, d3);
            var a1 = d1.lanewise(VectorOperators.ADD, d2);
            var a2 = d1.lanewise(VectorOperators.SUB, d2);
            var a3 = d0.lanewise(VectorOperators.SUB, d3);
            var t = coefOff + 32 * r;
            INTS_8.cast(a0.lanewise(VectorOperators.ADD, a1)).lanewise(VectorOperators.LSHL, 3).intoArray(coef, t);
            INTS_8.cast(a2.lanewise(VectorOperators.MUL, I_2217).lanewise(VectorOperators.ADD, a3.lanewise(VectorOperators.MUL, I_5352))
                    .lanewise(VectorOperators.ADD, I_1812)).lanewise(VectorOperators.ASHR, 9).intoArray(coef, t + 8);
            INTS_8.cast(a0.lanewise(VectorOperators.SUB, a1)).lanewise(VectorOperators.LSHL, 3).intoArray(coef, t + 16);
            INTS_8.cast(a3.lanewise(VectorOperators.MUL, I_2217).lanewise(VectorOperators.SUB, a2.lanewise(VectorOperators.MUL, I_5352))
                    .lanewise(VectorOperators.ADD, I_937)).lanewise(VectorOperators.ASHR, 9).intoArray(coef, t + 24);
        }
        var largest = INTS_8.cast(IntVector.zero(I256));
        for (var k = 0; k < 4; k++) {
            var t = coefOff + 8 * k;
            var c0 = INTS_8.cast(IntVector.fromArray(I256, coef, t));
            var c1 = INTS_8.cast(IntVector.fromArray(I256, coef, t + 32));
            var c2 = INTS_8.cast(IntVector.fromArray(I256, coef, t + 64));
            var c3 = INTS_8.cast(IntVector.fromArray(I256, coef, t + 96));
            var a0 = c0.lanewise(VectorOperators.ADD, c3);
            var a1 = c1.lanewise(VectorOperators.ADD, c2);
            var a2 = c1.lanewise(VectorOperators.SUB, c2);
            var a3 = c0.lanewise(VectorOperators.SUB, c3);
            var o0 = INTS_8.cast(INTS_8.cast(a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, I_7))
                    .lanewise(VectorOperators.ASHR, 4));
            var o1 = INTS_8.cast(INTS_8.cast(a2.lanewise(VectorOperators.MUL, I_2217).lanewise(VectorOperators.ADD, a3.lanewise(VectorOperators.MUL, I_5352))
                    .lanewise(VectorOperators.ADD, I_12000)).lanewise(VectorOperators.ASHR, 16)
                    .lanewise(VectorOperators.ADD, I_1, a3.compare(VectorOperators.NE, IntVector.zero(I256))));
            var o2 = INTS_8.cast(INTS_8.cast(a0.lanewise(VectorOperators.SUB, a1).lanewise(VectorOperators.ADD, I_7))
                    .lanewise(VectorOperators.ASHR, 4));
            var o3 = INTS_8.cast(INTS_8.cast(a3.lanewise(VectorOperators.MUL, I_2217).lanewise(VectorOperators.SUB, a2.lanewise(VectorOperators.MUL, I_5352))
                    .lanewise(VectorOperators.ADD, I_51000)).lanewise(VectorOperators.ASHR, 16));
            o0.intoArray(coef, t);
            o1.intoArray(coef, t + 32);
            o2.intoArray(coef, t + 64);
            o3.intoArray(coef, t + 96);
            largest = o0.lanewise(VectorOperators.ABS).lanewise(VectorOperators.AND, IntVector.broadcast(I256, k == 0 ? 0 : -1))
                    .lanewise(VectorOperators.MAX, o1.lanewise(VectorOperators.ABS))
                    .lanewise(VectorOperators.MAX, o2.lanewise(VectorOperators.ABS).lanewise(VectorOperators.MAX, o3.lanewise(VectorOperators.ABS)))
                    .lanewise(VectorOperators.MAX, largest);
        }
        var maxDc = INTS_8.cast(IntVector.fromArray(I256, coef, coefOff)).lanewise(VectorOperators.ABS)
                .reduceLanes(VectorOperators.MAX);
        return (maxDc << 16) | INTS_8.cast(largest).reduceLanes(VectorOperators.MAX);
    }

    @Override
    void quantize8(int[] coef, int coefOff, int first, QuantMatrix m, short[] levels, int levelsOff, int[] eob,
                          int eobOff) {
        var iq = INTS_8.cast(IntVector.broadcast(I256, m.iq[1]));
        var bias = INTS_8.cast(IntVector.broadcast(I256, m.bias[1]));
        var q = SHORTS_8.cast(ShortVector.broadcast(S128, (short) m.q[1]));
        var last = INTS_8.cast(IntVector.zero(I256));
        for (var n = 1; n < 16; n++) {
            var at = coefOff + 8 * ZIGZAG[n];
            var v = INTS_8.cast(IntVector.fromArray(I256, coef, at));
            var magnitude = INTS_8.cast(v.lanewise(VectorOperators.ABS).lanewise(VectorOperators.MUL, iq)
                    .lanewise(VectorOperators.ADD, bias)).lanewise(VectorOperators.LSHR, QuantMatrix.QFIX)
                    .lanewise(VectorOperators.MIN, I_MAX_LEVEL);
            var narrow = SHORTS_8.cast(magnitude.lanewise(VectorOperators.NEG, v.compare(VectorOperators.LT, IntVector.zero(I256)))
                    .convertShape(VectorOperators.I2S, S128, 0));
            narrow.lanewise(VectorOperators.OR, ShortVector.zero(S128)).intoArray(levels, levelsOff + n * LEVEL_STRIDE);
            INTS_8.cast(narrow.lanewise(VectorOperators.MUL, q).convertShape(VectorOperators.S2I, I256, 0)).intoArray(coef, at);
            last = INTS_8.cast(IntVector.zero(I256)).blend(IntVector.broadcast(I256, n + 1),
                    magnitude.compare(VectorOperators.NE, IntVector.zero(I256))).lanewise(VectorOperators.MAX, last);
        }
        if (first > 0) {
            INTS_8.cast(IntVector.zero(I256)).intoArray(coef, coefOff);
            SHORTS_8.cast(ShortVector.zero(S128)).intoArray(levels, levelsOff);
            INTS_8.cast(last).intoArray(eob, eobOff);
            return;
        }
        var v = INTS_8.cast(IntVector.fromArray(I256, coef, coefOff));
        var magnitude = INTS_8.cast(v.lanewise(VectorOperators.ABS).lanewise(VectorOperators.MUL, IntVector.broadcast(I256, m.iq[0]))
                .lanewise(VectorOperators.ADD, IntVector.broadcast(I256, m.bias[0]))).lanewise(VectorOperators.LSHR, QuantMatrix.QFIX)
                .lanewise(VectorOperators.MIN, I_MAX_LEVEL);
        var narrow = SHORTS_8.cast(magnitude.lanewise(VectorOperators.NEG, v.compare(VectorOperators.LT, IntVector.zero(I256)))
                .convertShape(VectorOperators.I2S, S128, 0));
        narrow.lanewise(VectorOperators.OR, ShortVector.zero(S128)).intoArray(levels, levelsOff);
        INTS_8.cast(narrow.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) m.q[0]))
                .convertShape(VectorOperators.S2I, I256, 0)).intoArray(coef, coefOff);
        INTS_8.cast(INTS_8.cast(IntVector.zero(I256)).blend(I_1, magnitude.compare(VectorOperators.NE, IntVector.zero(I256)))
                .lanewise(VectorOperators.MAX, last)).intoArray(eob, eobOff);
    }

    @Override
    public void inverseDctAdd8(int[] coef, int coefOff, byte[] pred, int predOff, byte[] out, int outOff) {
        for (var i = 0; i < 4; i++) {
            var t = coefOff + 8 * i;
            var i0 = INTS_8.cast(IntVector.fromArray(I256, coef, t));
            var i4 = INTS_8.cast(IntVector.fromArray(I256, coef, t + 32));
            var i8 = INTS_8.cast(IntVector.fromArray(I256, coef, t + 64));
            var i12 = INTS_8.cast(IntVector.fromArray(I256, coef, t + 96));
            var a1 = i0.lanewise(VectorOperators.ADD, i8);
            var b1 = i0.lanewise(VectorOperators.SUB, i8);
            var c1 = INTS_8.cast(i4.lanewise(VectorOperators.MUL, I_35468)).lanewise(VectorOperators.ASHR, 16)
                    .lanewise(VectorOperators.SUB, INTS_8.cast(i12.lanewise(VectorOperators.MUL, I_20091))
                            .lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, i12));
            var d1 = INTS_8.cast(i4.lanewise(VectorOperators.MUL, I_20091)).lanewise(VectorOperators.ASHR, 16)
                    .lanewise(VectorOperators.ADD, i4)
                    .lanewise(VectorOperators.ADD, INTS_8.cast(i12.lanewise(VectorOperators.MUL, I_35468))
                            .lanewise(VectorOperators.ASHR, 16));
            INTS_8.cast(INTS_8.cast(a1.lanewise(VectorOperators.ADD, d1)).lanewise(VectorOperators.LSHL, 16))
                    .lanewise(VectorOperators.ASHR, 16).intoArray(coef, t);
            INTS_8.cast(INTS_8.cast(a1.lanewise(VectorOperators.SUB, d1)).lanewise(VectorOperators.LSHL, 16))
                    .lanewise(VectorOperators.ASHR, 16).intoArray(coef, t + 96);
            INTS_8.cast(INTS_8.cast(b1.lanewise(VectorOperators.ADD, c1)).lanewise(VectorOperators.LSHL, 16))
                    .lanewise(VectorOperators.ASHR, 16).intoArray(coef, t + 32);
            INTS_8.cast(INTS_8.cast(b1.lanewise(VectorOperators.SUB, c1)).lanewise(VectorOperators.LSHL, 16))
                    .lanewise(VectorOperators.ASHR, 16).intoArray(coef, t + 64);
        }
        for (var r = 0; r < 4; r++) {
            var t = coefOff + 32 * r;
            var i0 = INTS_8.cast(IntVector.fromArray(I256, coef, t));
            var i1 = INTS_8.cast(IntVector.fromArray(I256, coef, t + 8));
            var i2 = INTS_8.cast(IntVector.fromArray(I256, coef, t + 16));
            var i3 = INTS_8.cast(IntVector.fromArray(I256, coef, t + 24));
            var a1 = i0.lanewise(VectorOperators.ADD, i2).lanewise(VectorOperators.ADD, I_4);
            var b1 = i0.lanewise(VectorOperators.SUB, i2).lanewise(VectorOperators.ADD, I_4);
            var c1 = INTS_8.cast(i1.lanewise(VectorOperators.MUL, I_35468)).lanewise(VectorOperators.ASHR, 16)
                    .lanewise(VectorOperators.SUB, INTS_8.cast(i3.lanewise(VectorOperators.MUL, I_20091))
                            .lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, i3));
            var d1 = INTS_8.cast(i1.lanewise(VectorOperators.MUL, I_20091)).lanewise(VectorOperators.ASHR, 16)
                    .lanewise(VectorOperators.ADD, i1)
                    .lanewise(VectorOperators.ADD, INTS_8.cast(i3.lanewise(VectorOperators.MUL, I_35468))
                            .lanewise(VectorOperators.ASHR, 16));
            var p = INTS_8.cast(BYTES_32.cast(ByteVector.fromArray(B256, pred, predOff + 32 * r)).reinterpretShape(I256, 0));
            var o0 = INTS_8.cast(a1.lanewise(VectorOperators.ADD, d1)).lanewise(VectorOperators.ASHR, 3)
                    .lanewise(VectorOperators.ADD, p.lanewise(VectorOperators.AND, I_255))
                    .lanewise(VectorOperators.MAX, IntVector.zero(I256)).lanewise(VectorOperators.MIN, I_255);
            var o1 = INTS_8.cast(b1.lanewise(VectorOperators.ADD, c1)).lanewise(VectorOperators.ASHR, 3)
                    .lanewise(VectorOperators.ADD, p.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, I_255))
                    .lanewise(VectorOperators.MAX, IntVector.zero(I256)).lanewise(VectorOperators.MIN, I_255);
            var o2 = INTS_8.cast(b1.lanewise(VectorOperators.SUB, c1)).lanewise(VectorOperators.ASHR, 3)
                    .lanewise(VectorOperators.ADD, p.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.AND, I_255))
                    .lanewise(VectorOperators.MAX, IntVector.zero(I256)).lanewise(VectorOperators.MIN, I_255);
            var o3 = INTS_8.cast(a1.lanewise(VectorOperators.SUB, d1)).lanewise(VectorOperators.ASHR, 3)
                    .lanewise(VectorOperators.ADD, p.lanewise(VectorOperators.LSHR, 24))
                    .lanewise(VectorOperators.MAX, IntVector.zero(I256)).lanewise(VectorOperators.MIN, I_255);
            var packed = INTS_8.cast(o1).lanewise(VectorOperators.LSHL, 8)
                    .lanewise(VectorOperators.OR, INTS_8.cast(o2).lanewise(VectorOperators.LSHL, 16))
                    .lanewise(VectorOperators.OR, INTS_8.cast(o3).lanewise(VectorOperators.LSHL, 24))
                    .lanewise(VectorOperators.OR, o0);
            BYTES_32.cast(INTS_8.cast(packed).reinterpretShape(B256, 0)).intoArray(out, outOff + 32 * r);
        }
    }
}
