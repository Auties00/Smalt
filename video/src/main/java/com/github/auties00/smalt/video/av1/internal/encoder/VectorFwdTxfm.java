package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.Tables;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Computes the forward two-dimensional transforms of {@link FwdTxfm} with the Vector API, eight
 * one-dimensional transforms at a time: the columns of eight adjacent columns, then, after a
 * transposition, the rows of eight adjacent rows. Blocks with a 4-sample side or a side over
 * 16 samples, which the real-time encoder seldom codes, use the scalar transforms: a vector kernel
 * called that rarely would stay on the compiler's slow, allocating tiers.
 *
 * <p>Every coefficient is identical to {@link FwdTxfm}'s: the butterflies are the same
 * statements in the same order on 32-bit lanes, whose products and sums stay within 32 bits for
 * 8-bit residuals as the transforms' stage ranges are designed to.
 *
 * @implNote This implementation is generated from libaom's {@code av1/encoder/av1_fwd_txfm1d.c}
 * by the same statement extraction as {@link FwdTxfm}; every vector is loaded from and
 * stored to the scratch arrays within one statement, and the butterfly stages are
 * split into methods of at most twelve statements, which keeps every method far below
 * the compiler's size limits.
 */
final class VectorFwdTxfm {
    /**
     * The 8-lane int species.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * The exact class of 8-int vectors.
     */
    private static final Class<IntVector> INTS_8 = vectorClass(I256);

    /**
     * Prevents instantiation.
     */
    private VectorFwdTxfm() {
        throw new AssertionError();
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

    /**
     * Transforms one residual block like {@link FwdTxfm#forward}.
     *
     * @param t the scalar transform, whose scratch buffers this uses
     * @param txSize the transform size, in the decoder's numbering
     * @param txType the transform type
     * @param residual the residual samples
     * @param resOff the offset of the top-left sample
     * @param resStride the distance between rows
     * @param out receives the coefficients in the decoder's layout
     */
    static void forward(FwdTxfm t, int txSize, int txType, int[] residual, int resOff, int resStride, int[] out) {
        var w = Tables.TX_W[txSize] * 4;
        var h = Tables.TX_H[txSize] * 4;
        if (w < 8 || h < 8 || w > 16 || h > 16 || (txType > FwdTxfm.ADST_ADST && txType < FwdTxfm.IDTX)
                || txType > FwdTxfm.H_ADST) {
            t.forward(txSize, txType, residual, resOff, resStride, out);
            return;
        }
        var shift = FwdTxfm.shift(txSize);
        var txw = Tables.TX_LW[txSize];
        var txh = Tables.TX_LH[txSize];
        var cosBitCol = FwdTxfm.cosBitCol(txw, txh);
        var cosBitRow = FwdTxfm.cosBitRow(txw, txh);
        var rect = Math.abs(txw - txh) == 1;
        var colKind = FwdTxfm.columnKind(txType);
        var rowKind = FwdTxfm.rowKind(txType);
        var in = t.vectorIn;
        var tmp = t.vectorOut;
        var step = t.vectorStep;
        var buf = t.vectorBuf;
        var left = shift[0];
        var colRound = -shift[1];
        for (var c0 = 0; c0 < w; c0 += 8) {
            for (var r = 0; r < h; r++) {
                var p = resOff + r * resStride + c0;
                var q = r * 8;
                for (var l = 0; l < 8; l++) {
                    in[q + l] = residual[p + l] << left;
                }
            }
            transform(h, colKind, in, tmp, step, cosBitCol);
            var add = colRound > 0 ? 1 << (colRound - 1) : 0;
            for (var r = 0; r < h; r++) {
                var q = r * 8;
                var d = c0 * h + r;
                for (var l = 0; l < 8; l++) {
                    buf[d + l * h] = colRound > 0 ? (tmp[q + l] + add) >> colRound : tmp[q + l] << -colRound;
                }
            }
        }
        var hOut = Math.min(h, 32);
        var wOut = Math.min(w, 32);
        var rowRound = -shift[2];
        var addRow = rowRound > 0 ? 1 << (rowRound - 1) : 0;
        for (var r0 = 0; r0 < hOut; r0 += 8) {
            for (var j = 0; j < w; j++) {
                System.arraycopy(buf, j * h + r0, in, j * 8, 8);
            }
            transform(w, rowKind, in, tmp, step, cosBitRow);
            for (var j = 0; j < wOut; j++) {
                var q = j * 8;
                var d = j * hOut + r0;
                for (var l = 0; l < 8; l++) {
                    var v = rowRound > 0 ? (tmp[q + l] + addRow) >> rowRound : tmp[q + l] << -rowRound;
                    if (rect) {
                        v = (int) (((long) v * FwdTxfm.NEW_SQRT2 + (1 << (FwdTxfm.NEW_SQRT2_BITS - 1)))
                                >> FwdTxfm.NEW_SQRT2_BITS);
                    }
                    out[d + l] = v;
                }
            }
        }
    }

    /**
     * Applies eight one-dimensional transforms of a size.
     *
     * @param n the size, 8 or 16
     * @param kind {@link FwdTxfm#KIND_DCT}, {@link FwdTxfm#KIND_ADST} or
     * {@link FwdTxfm#KIND_IDENTITY}
     * @param input the input values
     * @param output receives the output values
     * @param step the scratch
     * @param cosBit the cosine precision
     */
    private static void transform(int n, int kind, int[] input, int[] output, int[] step, int cosBit) {
        if (kind == FwdTxfm.KIND_IDENTITY) {
            identity(n, input, output);
            return;
        }
        var adst = kind == FwdTxfm.KIND_ADST;
        switch (n) {
            case 8 -> {
                if (adst) {
                    fadst8(input, output, step, cosBit);
                } else {
                    fdct8(input, output, step, cosBit);
                }
            }
            case 16 -> {
                if (adst) {
                    fadst16(input, output, step, cosBit);
                } else {
                    fdct16(input, output, step, cosBit);
                }
            }
            default -> throw new IllegalArgumentException("unsupported transform size " + n);
        }
    }

    /**
     * Computes eight identity transforms of a size at once ({@code av1_fidentity8_c},
     * {@code av1_fidentity16_c}), lane {@code l} of element {@code i} at index {@code i * 8 + l}.
     *
     * @param n the size, 8 or 16
     * @param input the input samples
     * @param output the output coefficients
     */
    private static void identity(int n, int[] input, int[] output) {
        var count = n * 8;
        if (n == 8) {
            for (var i = 0; i < count; i++) {
                output[i] = input[i] * 2;
            }
        } else {
            var round = 1L << (FwdTxfm.NEW_SQRT2_BITS - 1);
            for (var i = 0; i < count; i++) {
                output[i] = (int) (((long) FwdTxfm.NEW_SQRT2 * 2 * input[i] + round) >> FwdTxfm.NEW_SQRT2_BITS);
            }
        }
    }

    /**
     * Computes eight 8-point forward DCTs at once ({@code av1_fdct8}), lane {@code l} of element
     * {@code i} at index {@code i * 8 + l} of every array.
     *
     * @param input the input samples
     * @param output the output coefficients
     * @param step a scratch array of at least 64 entries
     * @param cosBit the precision of the cosine table
     */
    static void fdct8(int[] input, int[] output, int[] step, int cosBit) {
        var cospi = FwdTxfm.cospi(cosBit);
        fdct8Part0(input, output, cospi, cosBit);
        fdct8Part1(output, step, cospi, cosBit);
        fdct8Part2(step, output, cospi, cosBit);
        fdct8Part3(output, step, cospi, cosBit);
        fdct8Part4(step, output, cospi, cosBit);
    }

    /**
     * Computes part 0 of {@link #fdct8(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct8Part0(int[] in, int[] out, int[] cospi, int cosBit) {
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 0);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 24);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 56);
        }
    }

    /**
     * Computes part 1 of {@link #fdct8(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct8Part1(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 0);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 24);
        }
        System.arraycopy(in, 32, out, 32, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var w0 = IntVector.broadcast(I256, -cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 48);
        }
        System.arraycopy(in, 56, out, 56, 8);
    }

    /**
     * Computes part 2 of {@link #fdct8(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct8Part2(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 0);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var w0 = IntVector.broadcast(I256, -cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var w0 = IntVector.broadcast(I256, cospi[48]);
            var w1 = IntVector.broadcast(I256, cospi[16]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var w0 = IntVector.broadcast(I256, cospi[48]);
            var w1 = IntVector.broadcast(I256, -cospi[16]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 24);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 56);
        }
    }

    /**
     * Computes part 3 of {@link #fdct8(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct8Part3(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        System.arraycopy(in, 0, out, 0, 8);
        System.arraycopy(in, 8, out, 8, 8);
        System.arraycopy(in, 16, out, 16, 8);
        System.arraycopy(in, 24, out, 24, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var w0 = IntVector.broadcast(I256, cospi[56]);
            var w1 = IntVector.broadcast(I256, cospi[8]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var w0 = IntVector.broadcast(I256, cospi[24]);
            var w1 = IntVector.broadcast(I256, cospi[40]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var w0 = IntVector.broadcast(I256, cospi[24]);
            var w1 = IntVector.broadcast(I256, -cospi[40]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var w0 = IntVector.broadcast(I256, cospi[56]);
            var w1 = IntVector.broadcast(I256, -cospi[8]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 56);
        }
    }

    /**
     * Computes part 4 of {@link #fdct8(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct8Part4(int[] in, int[] out, int[] cospi, int cosBit) {
        System.arraycopy(in, 0, out, 0, 8);
        System.arraycopy(in, 32, out, 8, 8);
        System.arraycopy(in, 16, out, 16, 8);
        System.arraycopy(in, 48, out, 24, 8);
        System.arraycopy(in, 8, out, 32, 8);
        System.arraycopy(in, 40, out, 40, 8);
        System.arraycopy(in, 24, out, 48, 8);
        System.arraycopy(in, 56, out, 56, 8);
    }

    /**
     * Computes eight 16-point forward DCTs at once ({@code av1_fdct16}), lane {@code l} of element
     * {@code i} at index {@code i * 8 + l} of every array.
     *
     * @param input the input samples
     * @param output the output coefficients
     * @param step a scratch array of at least 128 entries
     * @param cosBit the precision of the cosine table
     */
    static void fdct16(int[] input, int[] output, int[] step, int cosBit) {
        var cospi = FwdTxfm.cospi(cosBit);
        fdct16Part0(input, output, cospi, cosBit);
        fdct16Part1(input, output, cospi, cosBit);
        fdct16Part2(output, step, cospi, cosBit);
        fdct16Part3(output, step, cospi, cosBit);
        fdct16Part4(step, output, cospi, cosBit);
        fdct16Part5(step, output, cospi, cosBit);
        fdct16Part6(output, step, cospi, cosBit);
        fdct16Part7(output, step, cospi, cosBit);
        fdct16Part8(step, output, cospi, cosBit);
        fdct16Part9(step, output, cospi, cosBit);
        fdct16Part10(output, step, cospi, cosBit);
        fdct16Part11(output, step, cospi, cosBit);
        fdct16Part12(step, output, cospi, cosBit);
        fdct16Part13(step, output, cospi, cosBit);
    }

    /**
     * Computes part 0 of {@link #fdct16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct16Part0(int[] in, int[] out, int[] cospi, int cosBit) {
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 0);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 24);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 56);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 64);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 72);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 80);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 88);
        }
    }

    /**
     * Computes part 1 of {@link #fdct16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct16Part1(int[] in, int[] out, int[] cospi, int cosBit) {
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 96);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 104);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 112);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 120);
        }
    }

    /**
     * Computes part 2 of {@link #fdct16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct16Part2(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 0);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 24);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 56);
        }
        System.arraycopy(in, 64, out, 64, 8);
        System.arraycopy(in, 72, out, 72, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var w0 = IntVector.broadcast(I256, -cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 80);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            var w0 = IntVector.broadcast(I256, -cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 88);
        }
    }

    /**
     * Computes part 3 of {@link #fdct16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct16Part3(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 96);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 104);
        }
        System.arraycopy(in, 112, out, 112, 8);
        System.arraycopy(in, 120, out, 120, 8);
    }

    /**
     * Computes part 4 of {@link #fdct16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct16Part4(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 0);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 24);
        }
        System.arraycopy(in, 32, out, 32, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var w0 = IntVector.broadcast(I256, -cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 48);
        }
        System.arraycopy(in, 56, out, 56, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 64);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 72);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 80);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 88);
        }
    }

    /**
     * Computes part 5 of {@link #fdct16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct16Part5(int[] in, int[] out, int[] cospi, int cosBit) {
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 96);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 104);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 112);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 120);
        }
    }

    /**
     * Computes part 6 of {@link #fdct16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct16Part6(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 0);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var w0 = IntVector.broadcast(I256, -cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var w0 = IntVector.broadcast(I256, cospi[48]);
            var w1 = IntVector.broadcast(I256, cospi[16]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var w0 = IntVector.broadcast(I256, cospi[48]);
            var w1 = IntVector.broadcast(I256, -cospi[16]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 24);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 56);
        }
        System.arraycopy(in, 64, out, 64, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            var w0 = IntVector.broadcast(I256, -cospi[16]);
            var w1 = IntVector.broadcast(I256, cospi[48]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 72);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var w0 = IntVector.broadcast(I256, -cospi[48]);
            var w1 = IntVector.broadcast(I256, -cospi[16]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 80);
        }
        System.arraycopy(in, 88, out, 88, 8);
    }

    /**
     * Computes part 7 of {@link #fdct16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct16Part7(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        System.arraycopy(in, 96, out, 96, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var w0 = IntVector.broadcast(I256, cospi[48]);
            var w1 = IntVector.broadcast(I256, -cospi[16]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 104);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            var w0 = IntVector.broadcast(I256, cospi[16]);
            var w1 = IntVector.broadcast(I256, cospi[48]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 112);
        }
        System.arraycopy(in, 120, out, 120, 8);
    }

    /**
     * Computes part 8 of {@link #fdct16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct16Part8(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        System.arraycopy(in, 0, out, 0, 8);
        System.arraycopy(in, 8, out, 8, 8);
        System.arraycopy(in, 16, out, 16, 8);
        System.arraycopy(in, 24, out, 24, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var w0 = IntVector.broadcast(I256, cospi[56]);
            var w1 = IntVector.broadcast(I256, cospi[8]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var w0 = IntVector.broadcast(I256, cospi[24]);
            var w1 = IntVector.broadcast(I256, cospi[40]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var w0 = IntVector.broadcast(I256, cospi[24]);
            var w1 = IntVector.broadcast(I256, -cospi[40]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var w0 = IntVector.broadcast(I256, cospi[56]);
            var w1 = IntVector.broadcast(I256, -cospi[8]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 56);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 64);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 72);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 80);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 88);
        }
    }

    /**
     * Computes part 9 of {@link #fdct16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct16Part9(int[] in, int[] out, int[] cospi, int cosBit) {
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 96);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 104);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            INTS_8.cast(b.lanewise(VectorOperators.SUB, a)).intoArray(out, 112);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 120);
        }
    }

    /**
     * Computes part 10 of {@link #fdct16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct16Part10(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        System.arraycopy(in, 0, out, 0, 8);
        System.arraycopy(in, 8, out, 8, 8);
        System.arraycopy(in, 16, out, 16, 8);
        System.arraycopy(in, 24, out, 24, 8);
        System.arraycopy(in, 32, out, 32, 8);
        System.arraycopy(in, 40, out, 40, 8);
        System.arraycopy(in, 48, out, 48, 8);
        System.arraycopy(in, 56, out, 56, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            var w0 = IntVector.broadcast(I256, cospi[60]);
            var w1 = IntVector.broadcast(I256, cospi[4]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 64);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            var w0 = IntVector.broadcast(I256, cospi[28]);
            var w1 = IntVector.broadcast(I256, cospi[36]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 72);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var w0 = IntVector.broadcast(I256, cospi[44]);
            var w1 = IntVector.broadcast(I256, cospi[20]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 80);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            var w0 = IntVector.broadcast(I256, cospi[12]);
            var w1 = IntVector.broadcast(I256, cospi[52]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 88);
        }
    }

    /**
     * Computes part 11 of {@link #fdct16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct16Part11(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            var w0 = IntVector.broadcast(I256, cospi[12]);
            var w1 = IntVector.broadcast(I256, -cospi[52]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 96);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var w0 = IntVector.broadcast(I256, cospi[44]);
            var w1 = IntVector.broadcast(I256, -cospi[20]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 104);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            var w0 = IntVector.broadcast(I256, cospi[28]);
            var w1 = IntVector.broadcast(I256, -cospi[36]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 112);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            var w0 = IntVector.broadcast(I256, cospi[60]);
            var w1 = IntVector.broadcast(I256, -cospi[4]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 120);
        }
    }

    /**
     * Computes part 12 of {@link #fdct16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct16Part12(int[] in, int[] out, int[] cospi, int cosBit) {
        System.arraycopy(in, 0, out, 0, 8);
        System.arraycopy(in, 64, out, 8, 8);
        System.arraycopy(in, 32, out, 16, 8);
        System.arraycopy(in, 96, out, 24, 8);
        System.arraycopy(in, 16, out, 32, 8);
        System.arraycopy(in, 80, out, 40, 8);
        System.arraycopy(in, 48, out, 48, 8);
        System.arraycopy(in, 112, out, 56, 8);
        System.arraycopy(in, 8, out, 64, 8);
        System.arraycopy(in, 72, out, 72, 8);
        System.arraycopy(in, 40, out, 80, 8);
        System.arraycopy(in, 104, out, 88, 8);
    }

    /**
     * Computes part 13 of {@link #fdct16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fdct16Part13(int[] in, int[] out, int[] cospi, int cosBit) {
        System.arraycopy(in, 24, out, 96, 8);
        System.arraycopy(in, 88, out, 104, 8);
        System.arraycopy(in, 56, out, 112, 8);
        System.arraycopy(in, 120, out, 120, 8);
    }

    /**
     * Computes eight 8-point forward ADSTs at once ({@code av1_fadst8}), lane {@code l} of element
     * {@code i} at index {@code i * 8 + l} of every array.
     *
     * @param input the input samples
     * @param output the output coefficients
     * @param step a scratch array of at least 64 entries
     * @param cosBit the precision of the cosine table
     */
    static void fadst8(int[] input, int[] output, int[] step, int cosBit) {
        var cospi = FwdTxfm.cospi(cosBit);
        fadst8Part0(input, output, cospi, cosBit);
        fadst8Part1(output, step, cospi, cosBit);
        fadst8Part2(step, output, cospi, cosBit);
        fadst8Part3(output, step, cospi, cosBit);
        fadst8Part4(step, output, cospi, cosBit);
        fadst8Part5(output, step, cospi, cosBit);
        fadst8Part6(step, output, cospi, cosBit);
    }

    /**
     * Computes part 0 of {@link #fadst8(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst8Part0(int[] in, int[] out, int[] cospi, int cosBit) {
        var zero = INTS_8.cast(IntVector.zero(I256));
        System.arraycopy(in, 0, out, 0, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            INTS_8.cast(zero.lanewise(VectorOperators.SUB, a)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            INTS_8.cast(zero.lanewise(VectorOperators.SUB, a)).intoArray(out, 16);
        }
        System.arraycopy(in, 32, out, 24, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            INTS_8.cast(zero.lanewise(VectorOperators.SUB, a)).intoArray(out, 32);
        }
        System.arraycopy(in, 48, out, 40, 8);
        System.arraycopy(in, 16, out, 48, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            INTS_8.cast(zero.lanewise(VectorOperators.SUB, a)).intoArray(out, 56);
        }
    }

    /**
     * Computes part 1 of {@link #fadst8(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst8Part1(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        System.arraycopy(in, 0, out, 0, 8);
        System.arraycopy(in, 8, out, 8, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, -cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 24);
        }
        System.arraycopy(in, 32, out, 32, 8);
        System.arraycopy(in, 40, out, 40, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, -cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 56);
        }
    }

    /**
     * Computes part 2 of {@link #fadst8(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst8Part2(int[] in, int[] out, int[] cospi, int cosBit) {
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 0);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 24);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 56);
        }
    }

    /**
     * Computes part 3 of {@link #fadst8(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst8Part3(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        System.arraycopy(in, 0, out, 0, 8);
        System.arraycopy(in, 8, out, 8, 8);
        System.arraycopy(in, 16, out, 16, 8);
        System.arraycopy(in, 24, out, 24, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var w0 = IntVector.broadcast(I256, cospi[16]);
            var w1 = IntVector.broadcast(I256, cospi[48]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var w0 = IntVector.broadcast(I256, cospi[48]);
            var w1 = IntVector.broadcast(I256, -cospi[16]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var w0 = IntVector.broadcast(I256, -cospi[48]);
            var w1 = IntVector.broadcast(I256, cospi[16]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var w0 = IntVector.broadcast(I256, cospi[16]);
            var w1 = IntVector.broadcast(I256, cospi[48]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 56);
        }
    }

    /**
     * Computes part 4 of {@link #fadst8(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst8Part4(int[] in, int[] out, int[] cospi, int cosBit) {
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 0);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 24);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 56);
        }
    }

    /**
     * Computes part 5 of {@link #fadst8(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst8Part5(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var w0 = IntVector.broadcast(I256, cospi[4]);
            var w1 = IntVector.broadcast(I256, cospi[60]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 0);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var w0 = IntVector.broadcast(I256, cospi[60]);
            var w1 = IntVector.broadcast(I256, -cospi[4]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var w0 = IntVector.broadcast(I256, cospi[20]);
            var w1 = IntVector.broadcast(I256, cospi[44]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var w0 = IntVector.broadcast(I256, cospi[44]);
            var w1 = IntVector.broadcast(I256, -cospi[20]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 24);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var w0 = IntVector.broadcast(I256, cospi[36]);
            var w1 = IntVector.broadcast(I256, cospi[28]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var w0 = IntVector.broadcast(I256, cospi[28]);
            var w1 = IntVector.broadcast(I256, -cospi[36]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var w0 = IntVector.broadcast(I256, cospi[52]);
            var w1 = IntVector.broadcast(I256, cospi[12]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var w0 = IntVector.broadcast(I256, cospi[12]);
            var w1 = IntVector.broadcast(I256, -cospi[52]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 56);
        }
    }

    /**
     * Computes part 6 of {@link #fadst8(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst8Part6(int[] in, int[] out, int[] cospi, int cosBit) {
        System.arraycopy(in, 8, out, 0, 8);
        System.arraycopy(in, 48, out, 8, 8);
        System.arraycopy(in, 24, out, 16, 8);
        System.arraycopy(in, 32, out, 24, 8);
        System.arraycopy(in, 40, out, 32, 8);
        System.arraycopy(in, 16, out, 40, 8);
        System.arraycopy(in, 56, out, 48, 8);
        System.arraycopy(in, 0, out, 56, 8);
    }

    /**
     * Computes eight 16-point forward ADSTs at once ({@code av1_fadst16}), lane {@code l} of element
     * {@code i} at index {@code i * 8 + l} of every array.
     *
     * @param input the input samples
     * @param output the output coefficients
     * @param step a scratch array of at least 128 entries
     * @param cosBit the precision of the cosine table
     */
    static void fadst16(int[] input, int[] output, int[] step, int cosBit) {
        var cospi = FwdTxfm.cospi(cosBit);
        fadst16Part0(input, output, cospi, cosBit);
        fadst16Part1(input, output, cospi, cosBit);
        fadst16Part2(output, step, cospi, cosBit);
        fadst16Part3(output, step, cospi, cosBit);
        fadst16Part4(step, output, cospi, cosBit);
        fadst16Part5(step, output, cospi, cosBit);
        fadst16Part6(output, step, cospi, cosBit);
        fadst16Part7(output, step, cospi, cosBit);
        fadst16Part8(step, output, cospi, cosBit);
        fadst16Part9(step, output, cospi, cosBit);
        fadst16Part10(output, step, cospi, cosBit);
        fadst16Part11(output, step, cospi, cosBit);
        fadst16Part12(step, output, cospi, cosBit);
        fadst16Part13(step, output, cospi, cosBit);
        fadst16Part14(output, step, cospi, cosBit);
        fadst16Part15(output, step, cospi, cosBit);
        fadst16Part16(step, output, cospi, cosBit);
        fadst16Part17(step, output, cospi, cosBit);
    }

    /**
     * Computes part 0 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part0(int[] in, int[] out, int[] cospi, int cosBit) {
        var zero = INTS_8.cast(IntVector.zero(I256));
        System.arraycopy(in, 0, out, 0, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            INTS_8.cast(zero.lanewise(VectorOperators.SUB, a)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            INTS_8.cast(zero.lanewise(VectorOperators.SUB, a)).intoArray(out, 16);
        }
        System.arraycopy(in, 64, out, 24, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            INTS_8.cast(zero.lanewise(VectorOperators.SUB, a)).intoArray(out, 32);
        }
        System.arraycopy(in, 96, out, 40, 8);
        System.arraycopy(in, 32, out, 48, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            INTS_8.cast(zero.lanewise(VectorOperators.SUB, a)).intoArray(out, 56);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            INTS_8.cast(zero.lanewise(VectorOperators.SUB, a)).intoArray(out, 64);
        }
        System.arraycopy(in, 112, out, 72, 8);
        System.arraycopy(in, 48, out, 80, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            INTS_8.cast(zero.lanewise(VectorOperators.SUB, a)).intoArray(out, 88);
        }
    }

    /**
     * Computes part 1 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part1(int[] in, int[] out, int[] cospi, int cosBit) {
        var zero = INTS_8.cast(IntVector.zero(I256));
        System.arraycopy(in, 16, out, 96, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            INTS_8.cast(zero.lanewise(VectorOperators.SUB, a)).intoArray(out, 104);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            INTS_8.cast(zero.lanewise(VectorOperators.SUB, a)).intoArray(out, 112);
        }
        System.arraycopy(in, 80, out, 120, 8);
    }

    /**
     * Computes part 2 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part2(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        System.arraycopy(in, 0, out, 0, 8);
        System.arraycopy(in, 8, out, 8, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, -cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 24);
        }
        System.arraycopy(in, 32, out, 32, 8);
        System.arraycopy(in, 40, out, 40, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, -cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 56);
        }
        System.arraycopy(in, 64, out, 64, 8);
        System.arraycopy(in, 72, out, 72, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 80);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, -cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 88);
        }
    }

    /**
     * Computes part 3 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part3(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        System.arraycopy(in, 96, out, 96, 8);
        System.arraycopy(in, 104, out, 104, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 112);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            var w0 = IntVector.broadcast(I256, cospi[32]);
            var w1 = IntVector.broadcast(I256, -cospi[32]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 120);
        }
    }

    /**
     * Computes part 4 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part4(int[] in, int[] out, int[] cospi, int cosBit) {
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 0);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 24);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 56);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 64);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 72);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 80);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 88);
        }
    }

    /**
     * Computes part 5 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part5(int[] in, int[] out, int[] cospi, int cosBit) {
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 96);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 104);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 112);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 120);
        }
    }

    /**
     * Computes part 6 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part6(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        System.arraycopy(in, 0, out, 0, 8);
        System.arraycopy(in, 8, out, 8, 8);
        System.arraycopy(in, 16, out, 16, 8);
        System.arraycopy(in, 24, out, 24, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var w0 = IntVector.broadcast(I256, cospi[16]);
            var w1 = IntVector.broadcast(I256, cospi[48]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var w0 = IntVector.broadcast(I256, cospi[48]);
            var w1 = IntVector.broadcast(I256, -cospi[16]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var w0 = IntVector.broadcast(I256, -cospi[48]);
            var w1 = IntVector.broadcast(I256, cospi[16]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var w0 = IntVector.broadcast(I256, cospi[16]);
            var w1 = IntVector.broadcast(I256, cospi[48]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 56);
        }
        System.arraycopy(in, 64, out, 64, 8);
        System.arraycopy(in, 72, out, 72, 8);
        System.arraycopy(in, 80, out, 80, 8);
        System.arraycopy(in, 88, out, 88, 8);
    }

    /**
     * Computes part 7 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part7(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var w0 = IntVector.broadcast(I256, cospi[16]);
            var w1 = IntVector.broadcast(I256, cospi[48]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 96);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var w0 = IntVector.broadcast(I256, cospi[48]);
            var w1 = IntVector.broadcast(I256, -cospi[16]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 104);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            var w0 = IntVector.broadcast(I256, -cospi[48]);
            var w1 = IntVector.broadcast(I256, cospi[16]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 112);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            var w0 = IntVector.broadcast(I256, cospi[16]);
            var w1 = IntVector.broadcast(I256, cospi[48]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 120);
        }
    }

    /**
     * Computes part 8 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part8(int[] in, int[] out, int[] cospi, int cosBit) {
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 0);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 24);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 56);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 64);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 72);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 80);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 88);
        }
    }

    /**
     * Computes part 9 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part9(int[] in, int[] out, int[] cospi, int cosBit) {
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 96);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 104);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 112);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 120);
        }
    }

    /**
     * Computes part 10 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part10(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        System.arraycopy(in, 0, out, 0, 8);
        System.arraycopy(in, 8, out, 8, 8);
        System.arraycopy(in, 16, out, 16, 8);
        System.arraycopy(in, 24, out, 24, 8);
        System.arraycopy(in, 32, out, 32, 8);
        System.arraycopy(in, 40, out, 40, 8);
        System.arraycopy(in, 48, out, 48, 8);
        System.arraycopy(in, 56, out, 56, 8);
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            var w0 = IntVector.broadcast(I256, cospi[8]);
            var w1 = IntVector.broadcast(I256, cospi[56]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 64);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            var w0 = IntVector.broadcast(I256, cospi[56]);
            var w1 = IntVector.broadcast(I256, -cospi[8]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 72);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            var w0 = IntVector.broadcast(I256, cospi[40]);
            var w1 = IntVector.broadcast(I256, cospi[24]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 80);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            var w0 = IntVector.broadcast(I256, cospi[24]);
            var w1 = IntVector.broadcast(I256, -cospi[40]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 88);
        }
    }

    /**
     * Computes part 11 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part11(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var w0 = IntVector.broadcast(I256, -cospi[56]);
            var w1 = IntVector.broadcast(I256, cospi[8]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 96);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var w0 = IntVector.broadcast(I256, cospi[8]);
            var w1 = IntVector.broadcast(I256, cospi[56]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 104);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            var w0 = IntVector.broadcast(I256, -cospi[24]);
            var w1 = IntVector.broadcast(I256, cospi[40]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 112);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            var w0 = IntVector.broadcast(I256, cospi[40]);
            var w1 = IntVector.broadcast(I256, cospi[24]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 120);
        }
    }

    /**
     * Computes part 12 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part12(int[] in, int[] out, int[] cospi, int cosBit) {
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 0);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 24);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            INTS_8.cast(a.lanewise(VectorOperators.ADD, b)).intoArray(out, 56);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 64);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 72);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 80);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 88);
        }
    }

    /**
     * Computes part 13 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part13(int[] in, int[] out, int[] cospi, int cosBit) {
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 96);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 104);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 112);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            INTS_8.cast(a.lanewise(VectorOperators.SUB, b)).intoArray(out, 120);
        }
    }

    /**
     * Computes part 14 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part14(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var w0 = IntVector.broadcast(I256, cospi[2]);
            var w1 = IntVector.broadcast(I256, cospi[62]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 0);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 0));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 8));
            var w0 = IntVector.broadcast(I256, cospi[62]);
            var w1 = IntVector.broadcast(I256, -cospi[2]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 8);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var w0 = IntVector.broadcast(I256, cospi[10]);
            var w1 = IntVector.broadcast(I256, cospi[54]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 16);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 16));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 24));
            var w0 = IntVector.broadcast(I256, cospi[54]);
            var w1 = IntVector.broadcast(I256, -cospi[10]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 24);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var w0 = IntVector.broadcast(I256, cospi[18]);
            var w1 = IntVector.broadcast(I256, cospi[46]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 32);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 32));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 40));
            var w0 = IntVector.broadcast(I256, cospi[46]);
            var w1 = IntVector.broadcast(I256, -cospi[18]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 40);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var w0 = IntVector.broadcast(I256, cospi[26]);
            var w1 = IntVector.broadcast(I256, cospi[38]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 48);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 48));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 56));
            var w0 = IntVector.broadcast(I256, cospi[38]);
            var w1 = IntVector.broadcast(I256, -cospi[26]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 56);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            var w0 = IntVector.broadcast(I256, cospi[34]);
            var w1 = IntVector.broadcast(I256, cospi[30]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 64);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 64));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 72));
            var w0 = IntVector.broadcast(I256, cospi[30]);
            var w1 = IntVector.broadcast(I256, -cospi[34]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 72);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            var w0 = IntVector.broadcast(I256, cospi[42]);
            var w1 = IntVector.broadcast(I256, cospi[22]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 80);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 80));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 88));
            var w0 = IntVector.broadcast(I256, cospi[22]);
            var w1 = IntVector.broadcast(I256, -cospi[42]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 88);
        }
    }

    /**
     * Computes part 15 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part15(int[] in, int[] out, int[] cospi, int cosBit) {
        var round = IntVector.broadcast(I256, 1 << (cosBit - 1));
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var w0 = IntVector.broadcast(I256, cospi[50]);
            var w1 = IntVector.broadcast(I256, cospi[14]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 96);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 96));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 104));
            var w0 = IntVector.broadcast(I256, cospi[14]);
            var w1 = IntVector.broadcast(I256, -cospi[50]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 104);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            var w0 = IntVector.broadcast(I256, cospi[58]);
            var w1 = IntVector.broadcast(I256, cospi[6]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 112);
        }
        {
            var a = INTS_8.cast(IntVector.fromArray(I256, in, 112));
            var b = INTS_8.cast(IntVector.fromArray(I256, in, 120));
            var w0 = IntVector.broadcast(I256, cospi[6]);
            var w1 = IntVector.broadcast(I256, -cospi[58]);
            INTS_8.cast(a.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.ADD,
                b.lanewise(VectorOperators.MUL, w1)).lanewise(VectorOperators.ADD, round)
                .lanewise(VectorOperators.ASHR, cosBit)).intoArray(out, 120);
        }
    }

    /**
     * Computes part 16 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part16(int[] in, int[] out, int[] cospi, int cosBit) {
        System.arraycopy(in, 8, out, 0, 8);
        System.arraycopy(in, 112, out, 8, 8);
        System.arraycopy(in, 24, out, 16, 8);
        System.arraycopy(in, 96, out, 24, 8);
        System.arraycopy(in, 40, out, 32, 8);
        System.arraycopy(in, 80, out, 40, 8);
        System.arraycopy(in, 56, out, 48, 8);
        System.arraycopy(in, 64, out, 56, 8);
        System.arraycopy(in, 72, out, 64, 8);
        System.arraycopy(in, 48, out, 72, 8);
        System.arraycopy(in, 88, out, 80, 8);
        System.arraycopy(in, 32, out, 88, 8);
    }

    /**
     * Computes part 17 of {@link #fadst16(int[], int[], int[], int)}.
     *
     * @param in the part input
     * @param out the part output
     * @param cospi the cosine table
     * @param cosBit the precision of the cosine table
     */
    private static void fadst16Part17(int[] in, int[] out, int[] cospi, int cosBit) {
        System.arraycopy(in, 104, out, 96, 8);
        System.arraycopy(in, 16, out, 104, 8);
        System.arraycopy(in, 120, out, 112, 8);
        System.arraycopy(in, 0, out, 120, 8);
    }
}
