package com.github.auties00.smalt.image.jpeg.internal;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.CONST_BITS;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_0_298631336;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_0_390180644;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_0_541196100;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_0_765366865;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_0_899976223;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_1_175875602;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_1_501321110;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_1_847759065;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_1_961570560;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_2_053119869;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_2_562915447;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.FIX_3_072711026;
import static com.github.auties00.smalt.image.jpeg.internal.ScalarIdctKernel.PASS1_BITS;

/**
 * Implements {@link BatchFdctKernel} with the Vector API, one block per 32-bit lane of a 256-bit
 * vector.
 *
 * <p>The transform runs in four stages that exchange data through arrays: the first transposes
 * each sample row of the eight blocks, as bytes, so that the eight samples of one column of all
 * the blocks are contiguous; the second widens them and runs the row pass lane-wise,
 * storing its results in the output array; the third runs the column pass in place; the fourth
 * quantizes each coefficient of all the blocks at once and collects the nonzero lanes into the
 * blocks' masks. The results equal {@link ScalarBatchFdctKernel}'s for every input.
 *
 * <p>Every vector that a load, broadcast or conversion produces is cast to the exact class of its
 * species before it is used as the receiver of a call, and so is the receiver of every
 * {@code intoArray}, reinterpretation and shift by a scalar count, so that the compiler binds
 * those calls without consulting receiver-type profiles; arithmetic uses the per-operation
 * methods ({@code lanewise} with a vector operand, {@code blend}, {@code compare},
 * {@code rearrange}) and no vector crosses a method boundary.
 * Reinterpretations use the per-shape {@code reinterpretShape}, never the {@code reinterpretAs}
 * methods.
 *
 * <p>This class references {@code jdk.incubator.vector} types and is only loaded through
 * {@link VectorKernels} when the Vector API is available.
 *
 * @implNote This implementation vectorizes libjpeg-turbo's {@code jpeg_fdct_islow} (jfdctint.c)
 * and {@code quantize} (jcdctmgr.c) across blocks rather than within one block. The
 * nonzero lanes of each group of eight coefficients are gathered into an 8x8 bit
 * matrix and transposed with three masked-swap steps, which yields a byte of each
 * block's mask without a per-lane loop.
 */
final class VectorBatchFdctKernel implements BatchFdctKernel {
    /**
     * Eight 32-bit lanes, one per block.
     */
    private static final VectorSpecies<Integer> I = IntVector.SPECIES_256;

    /**
     * Thirty-two 8-bit lanes: one sample row of four blocks.
     */
    private static final VectorSpecies<Byte> B = ByteVector.SPECIES_256;

    /**
     * Eight 8-bit lanes: one sample column of the eight blocks.
     */
    private static final VectorSpecies<Byte> B8 = ByteVector.SPECIES_64;

    /**
     * The exact class of the vectors of {@link #I}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(I);

    /**
     * The exact class of the vectors of {@link #B}.
     */
    private static final Class<ByteVector> BYTE_VECTOR = vectorClass(B);

    /**
     * The exact class of the vectors of {@link #B8}.
     */
    private static final Class<ByteVector> SMALL_BYTE_VECTOR = vectorClass(B8);

    /**
     * Regroups one sample row of four blocks by column: lane {@code 4x + b} takes sample
     * {@code x} of block {@code b}, lane {@code 8b + x}.
     */
    private static final VectorShuffle<Byte> BY_COLUMN = byColumn();

    /**
     * Repeats each lane of the low half twice: lane {@code j} takes lane {@code j / 2}.
     */
    private static final VectorShuffle<Integer> SPREAD_LOW = VectorShuffle.fromValues(I, 0, 0, 1, 1, 2, 2, 3, 3);

    /**
     * Repeats each lane of the high half twice: lane {@code j} takes lane {@code 4 + j / 2}.
     */
    private static final VectorShuffle<Integer> SPREAD_HIGH = VectorShuffle.fromValues(I, 4, 4, 5, 5, 6, 6, 7, 7);

    /**
     * Selects the odd lanes.
     */
    private static final VectorMask<Integer> ODD_LANES = VectorMask.fromValues(I, false, true, false, true, false, true,
            false, true);

    /**
     * The sign bit of a byte in every lane: an exclusive or with it turns an unsigned sample into
     * the sample minus 128.
     */
    private static final ByteVector CENTER = ByteVector.broadcast(B, (byte) 0x80);

    /**
     * Zero in every lane.
     */
    private static final IntVector ZERO = IntVector.zero(I);

    /**
     * {@code FIX(0.298631336)} in every lane.
     */
    private static final IntVector F_0_298631336 = IntVector.broadcast(I, FIX_0_298631336);

    /**
     * {@code -FIX(0.390180644)} in every lane.
     */
    private static final IntVector N_0_390180644 = IntVector.broadcast(I, -FIX_0_390180644);

    /**
     * {@code FIX(0.541196100)} in every lane.
     */
    private static final IntVector F_0_541196100 = IntVector.broadcast(I, FIX_0_541196100);

    /**
     * {@code FIX(0.765366865)} in every lane.
     */
    private static final IntVector F_0_765366865 = IntVector.broadcast(I, FIX_0_765366865);

    /**
     * {@code -FIX(0.899976223)} in every lane.
     */
    private static final IntVector N_0_899976223 = IntVector.broadcast(I, -FIX_0_899976223);

    /**
     * {@code FIX(1.175875602)} in every lane.
     */
    private static final IntVector F_1_175875602 = IntVector.broadcast(I, FIX_1_175875602);

    /**
     * {@code FIX(1.501321110)} in every lane.
     */
    private static final IntVector F_1_501321110 = IntVector.broadcast(I, FIX_1_501321110);

    /**
     * {@code -FIX(1.847759065)} in every lane.
     */
    private static final IntVector N_1_847759065 = IntVector.broadcast(I, -FIX_1_847759065);

    /**
     * {@code -FIX(1.961570560)} in every lane.
     */
    private static final IntVector N_1_961570560 = IntVector.broadcast(I, -FIX_1_961570560);

    /**
     * {@code FIX(2.053119869)} in every lane.
     */
    private static final IntVector F_2_053119869 = IntVector.broadcast(I, FIX_2_053119869);

    /**
     * {@code -FIX(2.562915447)} in every lane.
     */
    private static final IntVector N_2_562915447 = IntVector.broadcast(I, -FIX_2_562915447);

    /**
     * {@code FIX(3.072711026)} in every lane.
     */
    private static final IntVector F_3_072711026 = IntVector.broadcast(I, FIX_3_072711026);

    /**
     * The rounding term of the row pass's odd and scaled even outputs.
     */
    private static final IntVector ROUND_ROW = IntVector.broadcast(I, 1 << (CONST_BITS - PASS1_BITS - 1));

    /**
     * The rounding term of the column pass's first and fifth outputs.
     */
    private static final IntVector ROUND_COLUMN_DC = IntVector.broadcast(I, 1 << (PASS1_BITS - 1));

    /**
     * The rounding term of the column pass's other outputs.
     */
    private static final IntVector ROUND_COLUMN = IntVector.broadcast(I, 1 << (CONST_BITS + PASS1_BITS - 1));

    /**
     * Constructs the kernel.
     */
    VectorBatchFdctKernel() {
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
     * Builds {@link #BY_COLUMN}.
     *
     * @return the shuffle
     */
    private static VectorShuffle<Byte> byColumn() {
        var indexes = new int[32];
        for (var p = 0; p < 32; p++) {
            indexes[p] = (p & 3) * 8 + (p >> 2);
        }
        return VectorShuffle.fromArray(B, indexes, 0);
    }

    @Override
    public void transform(byte[] samples, int offset, int stride, QuantDivisors divisors, int[] output, long[] masks,
                          byte[] scratch) {
        transpose(samples, offset, stride, scratch);
        rows(scratch, output);
        columns(output);
        quantize(output, divisors, masks);
    }

    /**
     * Transposes each sample row of the eight blocks and centers the samples: row {@code r} of
     * the scratch array holds sample {@code x} of block {@code b}, minus 128, at
     * {@code 64r + 8x + b}.
     *
     * @param samples the source samples
     * @param offset the index of the first block's top-left sample
     * @param stride the distance between sample rows
     * @param scratch the destination
     */
    private static void transpose(byte[] samples, int offset, int stride, byte[] scratch) {
        for (var r = 0; r < 8; r++) {
            var at = offset + r * stride;
            var low = INT_VECTOR.cast(BYTE_VECTOR.cast(BYTE_VECTOR.cast(ByteVector.fromArray(B, samples, at))
                    .rearrange(BY_COLUMN).lanewise(VectorOperators.XOR, CENTER)).reinterpretShape(I, 0));
            var high = INT_VECTOR.cast(BYTE_VECTOR.cast(BYTE_VECTOR.cast(ByteVector.fromArray(B, samples, at + 32))
                    .rearrange(BY_COLUMN).lanewise(VectorOperators.XOR, CENTER)).reinterpretShape(I, 0));
            BYTE_VECTOR.cast(INT_VECTOR.cast(low.rearrange(SPREAD_LOW).blend(high.rearrange(SPREAD_LOW), ODD_LANES))
                    .reinterpretShape(B, 0)).intoArray(scratch, 64 * r);
            BYTE_VECTOR.cast(INT_VECTOR.cast(low.rearrange(SPREAD_HIGH).blend(high.rearrange(SPREAD_HIGH), ODD_LANES))
                    .reinterpretShape(B, 0)).intoArray(scratch, 64 * r + 32);
        }
    }

    /**
     * Runs the row pass on the transposed samples: widens the eight columns of each row and
     * stores output {@code u} of row {@code r} of every block at {@code (8r + u) * 8}.
     *
     * @param scratch the transposed samples
     * @param d the destination
     */
    private static void rows(byte[] scratch, int[] d) {
        var shift = CONST_BITS - PASS1_BITS;
        for (var r = 0; r < 8; r++) {
            var in = 64 * r;
            var s0 = INT_VECTOR.cast(SMALL_BYTE_VECTOR.cast(ByteVector.fromArray(B8, scratch, in))
                    .convertShape(VectorOperators.B2I, I, 0));
            var s1 = INT_VECTOR.cast(SMALL_BYTE_VECTOR.cast(ByteVector.fromArray(B8, scratch, in + 8))
                    .convertShape(VectorOperators.B2I, I, 0));
            var s2 = INT_VECTOR.cast(SMALL_BYTE_VECTOR.cast(ByteVector.fromArray(B8, scratch, in + 16))
                    .convertShape(VectorOperators.B2I, I, 0));
            var s3 = INT_VECTOR.cast(SMALL_BYTE_VECTOR.cast(ByteVector.fromArray(B8, scratch, in + 24))
                    .convertShape(VectorOperators.B2I, I, 0));
            var s4 = INT_VECTOR.cast(SMALL_BYTE_VECTOR.cast(ByteVector.fromArray(B8, scratch, in + 32))
                    .convertShape(VectorOperators.B2I, I, 0));
            var s5 = INT_VECTOR.cast(SMALL_BYTE_VECTOR.cast(ByteVector.fromArray(B8, scratch, in + 40))
                    .convertShape(VectorOperators.B2I, I, 0));
            var s6 = INT_VECTOR.cast(SMALL_BYTE_VECTOR.cast(ByteVector.fromArray(B8, scratch, in + 48))
                    .convertShape(VectorOperators.B2I, I, 0));
            var s7 = INT_VECTOR.cast(SMALL_BYTE_VECTOR.cast(ByteVector.fromArray(B8, scratch, in + 56))
                    .convertShape(VectorOperators.B2I, I, 0));
            var tmp0 = s0.lanewise(VectorOperators.ADD, s7);
            var tmp7 = s0.lanewise(VectorOperators.SUB, s7);
            var tmp1 = s1.lanewise(VectorOperators.ADD, s6);
            var tmp6 = s1.lanewise(VectorOperators.SUB, s6);
            var tmp2 = s2.lanewise(VectorOperators.ADD, s5);
            var tmp5 = s2.lanewise(VectorOperators.SUB, s5);
            var tmp3 = s3.lanewise(VectorOperators.ADD, s4);
            var tmp4 = s3.lanewise(VectorOperators.SUB, s4);
            var tmp10 = tmp0.lanewise(VectorOperators.ADD, tmp3);
            var tmp13 = tmp0.lanewise(VectorOperators.SUB, tmp3);
            var tmp11 = tmp1.lanewise(VectorOperators.ADD, tmp2);
            var tmp12 = tmp1.lanewise(VectorOperators.SUB, tmp2);
            var out = 64 * r;
            INT_VECTOR.cast(INT_VECTOR.cast(tmp10.lanewise(VectorOperators.ADD, tmp11))
                    .lanewise(VectorOperators.LSHL, PASS1_BITS))
                    .intoArray(d, out);
            INT_VECTOR.cast(INT_VECTOR.cast(tmp10.lanewise(VectorOperators.SUB, tmp11))
                    .lanewise(VectorOperators.LSHL, PASS1_BITS))
                    .intoArray(d, out + 32);
            var z1 = tmp12.lanewise(VectorOperators.ADD, tmp13).lanewise(VectorOperators.MUL, F_0_541196100);
            INT_VECTOR.cast(INT_VECTOR.cast(z1.lanewise(VectorOperators.ADD, tmp13
                    .lanewise(VectorOperators.MUL, F_0_765366865))
                    .lanewise(VectorOperators.ADD, ROUND_ROW)).lanewise(VectorOperators.ASHR, shift))
                    .intoArray(d, out + 16);
            INT_VECTOR.cast(INT_VECTOR.cast(z1.lanewise(VectorOperators.ADD, tmp12
                    .lanewise(VectorOperators.MUL, N_1_847759065))
                    .lanewise(VectorOperators.ADD, ROUND_ROW)).lanewise(VectorOperators.ASHR, shift))
                    .intoArray(d, out + 48);
            var z3 = tmp4.lanewise(VectorOperators.ADD, tmp6);
            var z4 = tmp5.lanewise(VectorOperators.ADD, tmp7);
            var z5 = z3.lanewise(VectorOperators.ADD, z4).lanewise(VectorOperators.MUL, F_1_175875602);
            var zo1 = tmp4.lanewise(VectorOperators.ADD, tmp7).lanewise(VectorOperators.MUL, N_0_899976223);
            var zo2 = tmp5.lanewise(VectorOperators.ADD, tmp6).lanewise(VectorOperators.MUL, N_2_562915447);
            z3 = z3.lanewise(VectorOperators.MUL, N_1_961570560).lanewise(VectorOperators.ADD, z5);
            z4 = z4.lanewise(VectorOperators.MUL, N_0_390180644).lanewise(VectorOperators.ADD, z5);
            INT_VECTOR.cast(INT_VECTOR.cast(tmp4.lanewise(VectorOperators.MUL, F_0_298631336)
                    .lanewise(VectorOperators.ADD, zo1)
                    .lanewise(VectorOperators.ADD, z3).lanewise(VectorOperators.ADD, ROUND_ROW))
                    .lanewise(VectorOperators.ASHR, shift)).intoArray(d, out + 56);
            INT_VECTOR.cast(INT_VECTOR.cast(tmp5.lanewise(VectorOperators.MUL, F_2_053119869)
                    .lanewise(VectorOperators.ADD, zo2)
                    .lanewise(VectorOperators.ADD, z4).lanewise(VectorOperators.ADD, ROUND_ROW))
                    .lanewise(VectorOperators.ASHR, shift)).intoArray(d, out + 40);
            INT_VECTOR.cast(INT_VECTOR.cast(tmp6.lanewise(VectorOperators.MUL, F_3_072711026)
                    .lanewise(VectorOperators.ADD, zo2)
                    .lanewise(VectorOperators.ADD, z3).lanewise(VectorOperators.ADD, ROUND_ROW))
                    .lanewise(VectorOperators.ASHR, shift)).intoArray(d, out + 24);
            INT_VECTOR.cast(INT_VECTOR.cast(tmp7.lanewise(VectorOperators.MUL, F_1_501321110)
                    .lanewise(VectorOperators.ADD, zo1)
                    .lanewise(VectorOperators.ADD, z4).lanewise(VectorOperators.ADD, ROUND_ROW))
                    .lanewise(VectorOperators.ASHR, shift)).intoArray(d, out + 8);
        }
    }

    /**
     * Runs the column pass in place on the row-pass outputs, leaving scaled coefficient
     * {@code k} (natural order) of every block at {@code k * 8}.
     *
     * @param d the row-pass outputs; the scaled coefficients on return
     */
    private static void columns(int[] d) {
        var shift = CONST_BITS + PASS1_BITS;
        for (var u = 0; u < 8; u++) {
            var base = 8 * u;
            var d0 = INT_VECTOR.cast(IntVector.fromArray(I, d, base));
            var d1 = INT_VECTOR.cast(IntVector.fromArray(I, d, base + 64));
            var d2 = INT_VECTOR.cast(IntVector.fromArray(I, d, base + 128));
            var d3 = INT_VECTOR.cast(IntVector.fromArray(I, d, base + 192));
            var d4 = INT_VECTOR.cast(IntVector.fromArray(I, d, base + 256));
            var d5 = INT_VECTOR.cast(IntVector.fromArray(I, d, base + 320));
            var d6 = INT_VECTOR.cast(IntVector.fromArray(I, d, base + 384));
            var d7 = INT_VECTOR.cast(IntVector.fromArray(I, d, base + 448));
            var tmp0 = d0.lanewise(VectorOperators.ADD, d7);
            var tmp7 = d0.lanewise(VectorOperators.SUB, d7);
            var tmp1 = d1.lanewise(VectorOperators.ADD, d6);
            var tmp6 = d1.lanewise(VectorOperators.SUB, d6);
            var tmp2 = d2.lanewise(VectorOperators.ADD, d5);
            var tmp5 = d2.lanewise(VectorOperators.SUB, d5);
            var tmp3 = d3.lanewise(VectorOperators.ADD, d4);
            var tmp4 = d3.lanewise(VectorOperators.SUB, d4);
            var tmp10 = tmp0.lanewise(VectorOperators.ADD, tmp3);
            var tmp13 = tmp0.lanewise(VectorOperators.SUB, tmp3);
            var tmp11 = tmp1.lanewise(VectorOperators.ADD, tmp2);
            var tmp12 = tmp1.lanewise(VectorOperators.SUB, tmp2);
            INT_VECTOR.cast(INT_VECTOR.cast(tmp10.lanewise(VectorOperators.ADD, tmp11)
                    .lanewise(VectorOperators.ADD, ROUND_COLUMN_DC))
                    .lanewise(VectorOperators.ASHR, PASS1_BITS)).intoArray(d, base);
            INT_VECTOR.cast(INT_VECTOR.cast(tmp10.lanewise(VectorOperators.SUB, tmp11)
                    .lanewise(VectorOperators.ADD, ROUND_COLUMN_DC))
                    .lanewise(VectorOperators.ASHR, PASS1_BITS)).intoArray(d, base + 256);
            var z1 = tmp12.lanewise(VectorOperators.ADD, tmp13).lanewise(VectorOperators.MUL, F_0_541196100);
            INT_VECTOR.cast(INT_VECTOR.cast(z1.lanewise(VectorOperators.ADD, tmp13
                    .lanewise(VectorOperators.MUL, F_0_765366865))
                    .lanewise(VectorOperators.ADD, ROUND_COLUMN)).lanewise(VectorOperators.ASHR, shift))
                    .intoArray(d, base + 128);
            INT_VECTOR.cast(INT_VECTOR.cast(z1.lanewise(VectorOperators.ADD, tmp12
                    .lanewise(VectorOperators.MUL, N_1_847759065))
                    .lanewise(VectorOperators.ADD, ROUND_COLUMN)).lanewise(VectorOperators.ASHR, shift))
                    .intoArray(d, base + 384);
            var z3 = tmp4.lanewise(VectorOperators.ADD, tmp6);
            var z4 = tmp5.lanewise(VectorOperators.ADD, tmp7);
            var z5 = z3.lanewise(VectorOperators.ADD, z4).lanewise(VectorOperators.MUL, F_1_175875602);
            var zo1 = tmp4.lanewise(VectorOperators.ADD, tmp7).lanewise(VectorOperators.MUL, N_0_899976223);
            var zo2 = tmp5.lanewise(VectorOperators.ADD, tmp6).lanewise(VectorOperators.MUL, N_2_562915447);
            z3 = z3.lanewise(VectorOperators.MUL, N_1_961570560).lanewise(VectorOperators.ADD, z5);
            z4 = z4.lanewise(VectorOperators.MUL, N_0_390180644).lanewise(VectorOperators.ADD, z5);
            INT_VECTOR.cast(INT_VECTOR.cast(tmp4.lanewise(VectorOperators.MUL, F_0_298631336)
                    .lanewise(VectorOperators.ADD, zo1)
                    .lanewise(VectorOperators.ADD, z3).lanewise(VectorOperators.ADD, ROUND_COLUMN))
                    .lanewise(VectorOperators.ASHR, shift)).intoArray(d, base + 448);
            INT_VECTOR.cast(INT_VECTOR.cast(tmp5.lanewise(VectorOperators.MUL, F_2_053119869)
                    .lanewise(VectorOperators.ADD, zo2)
                    .lanewise(VectorOperators.ADD, z4).lanewise(VectorOperators.ADD, ROUND_COLUMN))
                    .lanewise(VectorOperators.ASHR, shift)).intoArray(d, base + 320);
            INT_VECTOR.cast(INT_VECTOR.cast(tmp6.lanewise(VectorOperators.MUL, F_3_072711026)
                    .lanewise(VectorOperators.ADD, zo2)
                    .lanewise(VectorOperators.ADD, z3).lanewise(VectorOperators.ADD, ROUND_COLUMN))
                    .lanewise(VectorOperators.ASHR, shift)).intoArray(d, base + 192);
            INT_VECTOR.cast(INT_VECTOR.cast(tmp7.lanewise(VectorOperators.MUL, F_1_501321110)
                    .lanewise(VectorOperators.ADD, zo1)
                    .lanewise(VectorOperators.ADD, z4).lanewise(VectorOperators.ADD, ROUND_COLUMN))
                    .lanewise(VectorOperators.ASHR, shift)).intoArray(d, base + 64);
        }
    }

    /**
     * Quantizes the scaled coefficients of every block in place, as
     * {@link QuantDivisors#quantize} does, and computes the blocks' nonzero masks.
     *
     * @param d the scaled coefficients, interleaved; the quantized ones on return
     * @param divisors the quantization table
     * @param masks the natural-order nonzero masks of the blocks, written
     */
    private static void quantize(int[] d, QuantDivisors divisors, long[] masks) {
        var correction = divisors.correction;
        var reciprocal = divisors.reciprocal;
        var shifts = divisors.shift;
        long m0 = 0;
        long m1 = 0;
        long m2 = 0;
        long m3 = 0;
        long m4 = 0;
        long m5 = 0;
        long m6 = 0;
        long m7 = 0;
        for (var group = 0; group < 8; group++) {
            var bits = 0L;
            for (var i = 0; i < 8; i++) {
                var k = 8 * group + i;
                var value = INT_VECTOR.cast(IntVector.fromArray(I, d, 8 * k));
                var sign = value.lanewise(VectorOperators.ASHR, 31);
                var magnitude = value.lanewise(VectorOperators.XOR, sign).lanewise(VectorOperators.SUB, sign);
                var quotient = INT_VECTOR.cast(magnitude
                        .lanewise(VectorOperators.ADD, INT_VECTOR.cast(IntVector.broadcast(I, correction[k])))
                        .lanewise(VectorOperators.MUL, INT_VECTOR.cast(IntVector.broadcast(I, reciprocal[k]))))
                        .lanewise(VectorOperators.LSHR, shifts[k]);
                var result = quotient.lanewise(VectorOperators.XOR, sign).lanewise(VectorOperators.SUB, sign);
                INT_VECTOR.cast(result).intoArray(d, 8 * k);
                bits |= result.compare(VectorOperators.NE, ZERO).toLong() << (8 * i);
            }
            bits = (bits & 0xAA55AA55AA55AA55L) | ((bits & 0x00AA00AA00AA00AAL) << 7)
                    | ((bits >>> 7) & 0x00AA00AA00AA00AAL);
            bits = (bits & 0xCCCC3333CCCC3333L) | ((bits & 0x0000CCCC0000CCCCL) << 14)
                    | ((bits >>> 14) & 0x0000CCCC0000CCCCL);
            bits = (bits & 0xF0F0F0F00F0F0F0FL) | ((bits & 0x00000000F0F0F0F0L) << 28)
                    | ((bits >>> 28) & 0x00000000F0F0F0F0L);
            var at = 8 * group;
            m0 |= (bits & 0xFF) << at;
            m1 |= ((bits >>> 8) & 0xFF) << at;
            m2 |= ((bits >>> 16) & 0xFF) << at;
            m3 |= ((bits >>> 24) & 0xFF) << at;
            m4 |= ((bits >>> 32) & 0xFF) << at;
            m5 |= ((bits >>> 40) & 0xFF) << at;
            m6 |= ((bits >>> 48) & 0xFF) << at;
            m7 |= (bits >>> 56) << at;
        }
        masks[0] = m0;
        masks[1] = m1;
        masks[2] = m2;
        masks[3] = m3;
        masks[4] = m4;
        masks[5] = m5;
        masks[6] = m6;
        masks[7] = m7;
    }
}
