/* Ports libvpx 1.17.0 (vpx_dsp: vpx_convolve.c, loopfilter.c, inv_txfm.c, intrapred.c). Copyright (c) 2010,
   The WebM Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Implements {@link Vp9Dsp} with the Vector API; the output equals {@link ScalarVp9Dsp} sample for
 * sample for every input a conformant stream can produce.
 *
 * <p>Generated from {@code tools/codegen/vp9}; edit the template.
 *
 * @implNote This implementation follows the kernel rules of
 * {@link com.github.auties00.smalt.internal.simd.Simd} and needs 256-bit vectors: on narrower
 * hardware {@link #create()} returns the scalar kernels. The note at the top of the class body
 * describes the lane layouts.
 */
final class VectorVp9Dsp extends Vp9Dsp {
    /*
     * Sub-pixel motion compensation filters 16 samples per 256-bit vector of 16-bit lanes (8 for blocks narrower than
     * 16). The sum of the eight products of one sample lies in [-13770, 46410] for every VP9 filter, a range narrower
     * than 2^16, so it is accumulated modulo 2^16 on top of a bias of 128 * 128 plus the rounding term: the biased sum
     * then equals the exact one moved into [0, 65535], a logical shift by 7 rounds it, and taking away 128 and clamping
     * gives the 8-bit sample. Two-dimensional filters run the horizontal pass into an intermediate block and the
     * vertical pass from it, as the reference does; the intermediate samples are rounded to 8 bits like the reference's
     * but kept as 16-bit values, which spares two conversions. Scaled prediction runs the scalar kernels.
     *
     * Loop filters process 16 lines across an edge at once in 16-bit lanes: two groups of 8 lines with their own
     * thresholds, or one group of 8 with 8 more lines that are filtered alongside and discarded. The kernels read rows
     * of 16 samples, one per position across the edge, straight from the plane for horizontal edges and from scratch
     * rows for vertical edges, which are transposed there, one 8x8 block per 64-bit lane, and back; they write their
     * results to scratch rows, which are then copied to the plane. The 16-tap filter computes its 15-tap sums with a
     * running window, one kernel per side of the edge, after the 8-tap filter has left its results in scratch rows.
     *
     * Inverse transforms run 8 rows (4 for 4x4 blocks) at once, one per lane, then 8 columns at once: the coefficients
     * are stored column by column within each group of 8 rows (see coefficientLayout), so that each vector loads one
     * element of the 8 transforms, the row pass leaves its outputs transposed, and the columns are transposed back
     * before the column pass; a small kernel then adds the rounded outputs to the prediction row by row. Both passes of
     * a 1-D transform run the same methods, which write their outputs with a stride, so that the compilers see them
     * twice as often. The vector code of each 1-D transform is generated from the scalar one statement by statement,
     * without its 16-bit truncations, which never change a value of a conformant stream; transforms too long for one
     * method run as stages that leave the values later stages read in scratch slots. Blocks whose only coefficient is
     * the DC add one saturated value to every sample, 32 samples per vector. The lossless Walsh-Hadamard transform runs
     * the scalar kernel.
     *
     * The true-motion intra predictor computes 16 samples per vector; the other predictors fill and copy rows with the
     * scalar kernels.
     */

    /**
     * The 8-lane byte species of 8-sample rows.
     */
    private static final VectorSpecies<Byte> B64 = ByteVector.SPECIES_64;

    /**
     * The 16-lane byte species of 16-sample rows.
     */
    private static final VectorSpecies<Byte> B128 = ByteVector.SPECIES_128;

    /**
     * The 32-lane byte species of 32-sample rows.
     */
    private static final VectorSpecies<Byte> B256 = ByteVector.SPECIES_256;

    /**
     * The 8-lane short species of 8-sample filters.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * The 16-lane short species of 16-sample filters and loop filters.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * The 2-lane long species of loop filter rows and transposes.
     */
    private static final VectorSpecies<Long> L128 = LongVector.SPECIES_128;

    /**
     * The 2-lane int species that extracts the first 4 bytes of an 8-byte vector.
     */
    private static final VectorSpecies<Integer> I64 = IntVector.SPECIES_64;

    /**
     * The 4-lane int species of the 4-point transforms.
     */
    private static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;

    /**
     * The 8-lane int species of the 8, 16 and 32-point transforms.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * The exact class of the vectors of {@link #I128}.
     */
    private static final Class<IntVector> INT128 = vectorClass(I128);

    /**
     * The exact class of the vectors of {@link #I256}.
     */
    private static final Class<IntVector> INT256 = vectorClass(I256);

    /**
     * The exact class of the vectors of {@link #B64}.
     */
    private static final Class<ByteVector> BYTE64 = vectorClass(B64);

    /**
     * The exact class of the vectors of {@link #B128}.
     */
    private static final Class<ByteVector> BYTE128 = vectorClass(B128);

    /**
     * The exact class of the vectors of {@link #B256}.
     */
    private static final Class<ByteVector> BYTE256 = vectorClass(B256);

    /**
     * The exact class of the vectors of {@link #S128}.
     */
    private static final Class<ShortVector> SHORT128 = vectorClass(S128);

    /**
     * The exact class of the vectors of {@link #S256}.
     */
    private static final Class<ShortVector> SHORT256 = vectorClass(S256);

    /**
     * The exact class of the vectors of {@link #L128}.
     */
    private static final Class<LongVector> LONG128 = vectorClass(L128);

    /**
     * The exact class of the vectors of {@link #I64}.
     */
    private static final Class<IntVector> INT64 = vectorClass(I64);

    /**
     * The start value of a sub-pixel filter sum: the bias {@code 128 * 128} that keeps the sum
     * non-negative, plus the rounding term 64.
     */
    private static final short FILTER_BIAS = 128 * 128 + 64;

    /**
     * {@link #FILTER_BIAS} in every 16-bit lane of {@link #S256}.
     */
    private static final ShortVector S256_BIAS = ShortVector.broadcast(S256, FILTER_BIAS);

    /**
     * {@link #FILTER_BIAS} in every 16-bit lane of {@link #S128}.
     */
    private static final ShortVector S128_BIAS = ShortVector.broadcast(S128, FILTER_BIAS);

    /**
     * Sixteen shorts equal to {@code 128}, the bias left after the rounding shift.
     */
    private static final ShortVector S256_128 = ShortVector.broadcast(S256, (short) 128);

    /**
     * Sixteen shorts equal to {@code 383}, the biased value of the largest sample.
     */
    private static final ShortVector S256_383 = ShortVector.broadcast(S256, (short) 383);

    /**
     * Eight shorts equal to {@code 128}.
     */
    private static final ShortVector S128_128 = ShortVector.broadcast(S128, (short) 128);

    /**
     * Eight shorts equal to {@code 383}.
     */
    private static final ShortVector S128_383 = ShortVector.broadcast(S128, (short) 383);

    /**
     * Eight shorts equal to {@code 1}.
     */
    private static final ShortVector S128_1 = ShortVector.broadcast(S128, (short) 1);

    /**
     * Eight shorts equal to {@code 0}.
     */
    private static final ShortVector S128_0 = ShortVector.broadcast(S128, (short) 0);

    /**
     * Eight shorts equal to {@code 255}.
     */
    private static final ShortVector S128_255 = ShortVector.broadcast(S128, (short) 255);

    /**
     * Sixteen shorts equal to {@code 0}.
     */
    private static final ShortVector S256_0 = ShortVector.broadcast(S256, (short) 0);

    /**
     * Sixteen shorts equal to {@code -1}.
     */
    private static final ShortVector S256_M1 = ShortVector.broadcast(S256, (short) -1);

    /**
     * Sixteen shorts equal to {@code 1}.
     */
    private static final ShortVector S256_1 = ShortVector.broadcast(S256, (short) 1);

    /**
     * Sixteen shorts equal to {@code 3}.
     */
    private static final ShortVector S256_3 = ShortVector.broadcast(S256, (short) 3);

    /**
     * Sixteen shorts equal to {@code 4}.
     */
    private static final ShortVector S256_4 = ShortVector.broadcast(S256, (short) 4);

    /**
     * Sixteen shorts equal to {@code 7}.
     */
    private static final ShortVector S256_7 = ShortVector.broadcast(S256, (short) 7);

    /**
     * Sixteen shorts equal to {@code 8}.
     */
    private static final ShortVector S256_8 = ShortVector.broadcast(S256, (short) 8);

    /**
     * Sixteen shorts equal to {@code 127}, the largest signed byte.
     */
    private static final ShortVector S256_127 = ShortVector.broadcast(S256, (short) 127);

    /**
     * Sixteen shorts equal to {@code -128}, the smallest signed byte.
     */
    private static final ShortVector S256_M128 = ShortVector.broadcast(S256, (short) -128);

    /**
     * Sixteen shorts equal to {@code 255}, the largest sample.
     */
    private static final ShortVector S256_255 = ShortVector.broadcast(S256, (short) 255);

    /**
     * Selects the odd bytes of each 64-bit lane, for the first transpose step.
     */
    private static final LongVector L128_BYTES = LongVector.broadcast(L128, 0x00FF00FF00FF00FFL);

    /**
     * Selects the odd 16-bit halves of each 32-bit word, for the second transpose step.
     */
    private static final LongVector L128_SHORTS = LongVector.broadcast(L128, 0x0000FFFF0000FFFFL);

    /**
     * Selects the low 32 bits of each 64-bit lane, for the third transpose step.
     */
    private static final LongVector L128_INTS = LongVector.broadcast(L128, 0x00000000FFFFFFFFL);

    /**
     * Reads and writes eight bytes of a byte array as a little-endian {@code long}.
     */
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Reads and writes four bytes of a byte array as a little-endian {@code int}.
     */
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The first of the 3 scratch rows holding the edge limit, interior limit and high edge
     * variance threshold of every line; vertical edges transpose their samples at positions
     * {@code p7} to {@code q7} into the 16 rows before it.
     */
    private static final int THRESHOLD_ROW = 16;

    /**
     * The first of the 4 scratch rows holding the results of {@link #filter4} at positions
     * {@code p1} to {@code q1}.
     */
    private static final int FILTER4_ROW = 19;

    /**
     * The first of the 7 scratch rows holding the results of {@link #filter8} at positions
     * {@code p2} to {@code q2}, then its flags.
     */
    private static final int FILTER8_ROW = 23;

    /**
     * The first of the 14 scratch rows holding the results of {@link #filter16Before} and
     * {@link #filter16After} at positions {@code p6} to {@code q6}.
     */
    private static final int FILTER16_ROW = 30;

    /**
     * The index in the transform scratch array of the slots in which the stages of a transform
     * leave the values later stages read, 8 entries per value, after the 32x32 intermediate block.
     */
    private static final int SLOTS = 32 * 32;

    /**
     * The number of entries of the stage slots: 66 values of 8.
     */
    private static final int SLOT_GROUP = 66 * 8;

    /**
     * The index in the transform scratch array of the input rows of the column pass, after the
     * slots: the values of element {@code k} of the 8 (or 4) transforms of a group are stored
     * together, 8 (or 4) entries per element, {@link #INPUT_GROUP} entries per group.
     */
    private static final int INPUT = SLOTS + SLOT_GROUP;

    /**
     * The number of entries of the input rows of one group of 8 transforms: 32 elements of 8.
     */
    private static final int INPUT_GROUP = 32 * 8;

    /**
     * The index in the transform scratch array of the outputs of the column pass of a group of 8
     * columns, 8 per row, after the input rows of 4 groups.
     */
    private static final int OUTPUT = INPUT + 4 * INPUT_GROUP;

    /**
     * The coefficient layout of each transform size; see {@link #coefficientLayout}.
     */
    private static final short[][] LAYOUTS = {layout(0), layout(1), layout(2), layout(3)};

    /**
     * Four ints equal to {@code 0}.
     */
    private static final IntVector I128_0 = IntVector.broadcast(I128, 0);

    /**
     * Four ints equal to {@code 255}.
     */
    private static final IntVector I128_255 = IntVector.broadcast(I128, 255);

    /**
     * Eight ints equal to {@code 0}.
     */
    private static final IntVector I256_0 = IntVector.broadcast(I256, 0);

    /**
     * Eight ints equal to {@code 255}.
     */
    private static final IntVector I256_255 = IntVector.broadcast(I256, 255);

    /**
     * Four ints equal to {@code 11585}, {@code cospi_16_64}.
     */
    private static final IntVector I128_11585 = IntVector.broadcast(I128, 11585);

    /**
     * Four ints equal to {@code 13377}, {@code sinpi_3_9}.
     */
    private static final IntVector I128_13377 = IntVector.broadcast(I128, 13377);

    /**
     * Four ints equal to {@code 15137}, {@code cospi_8_64}.
     */
    private static final IntVector I128_15137 = IntVector.broadcast(I128, 15137);

    /**
     * Four ints equal to {@code 15212}, {@code sinpi_4_9}.
     */
    private static final IntVector I128_15212 = IntVector.broadcast(I128, 15212);

    /**
     * Four ints equal to {@code 5283}, {@code sinpi_1_9}.
     */
    private static final IntVector I128_5283 = IntVector.broadcast(I128, 5283);

    /**
     * Four ints equal to {@code 6270}, {@code cospi_24_64}.
     */
    private static final IntVector I128_6270 = IntVector.broadcast(I128, 6270);

    /**
     * Four ints equal to {@code 8192}, the rounding term of a 14-bit shift.
     */
    private static final IntVector I128_8192 = IntVector.broadcast(I128, 8192);

    /**
     * Four ints equal to {@code 9929}, {@code sinpi_2_9}.
     */
    private static final IntVector I128_9929 = IntVector.broadcast(I128, 9929);

    /**
     * Eight ints equal to {@code 10394}, {@code cospi_18_64}.
     */
    private static final IntVector I256_10394 = IntVector.broadcast(I256, 10394);

    /**
     * Eight ints equal to {@code 11003}, {@code cospi_17_64}.
     */
    private static final IntVector I256_11003 = IntVector.broadcast(I256, 11003);

    /**
     * Eight ints equal to {@code 11585}, {@code cospi_16_64}.
     */
    private static final IntVector I256_11585 = IntVector.broadcast(I256, 11585);

    /**
     * Eight ints equal to {@code 12140}, {@code cospi_15_64}.
     */
    private static final IntVector I256_12140 = IntVector.broadcast(I256, 12140);

    /**
     * Eight ints equal to {@code 12665}, {@code cospi_14_64}.
     */
    private static final IntVector I256_12665 = IntVector.broadcast(I256, 12665);

    /**
     * Eight ints equal to {@code 13160}, {@code cospi_13_64}.
     */
    private static final IntVector I256_13160 = IntVector.broadcast(I256, 13160);

    /**
     * Eight ints equal to {@code 13623}, {@code cospi_12_64}.
     */
    private static final IntVector I256_13623 = IntVector.broadcast(I256, 13623);

    /**
     * Eight ints equal to {@code 14053}, {@code cospi_11_64}.
     */
    private static final IntVector I256_14053 = IntVector.broadcast(I256, 14053);

    /**
     * Eight ints equal to {@code 14449}, {@code cospi_10_64}.
     */
    private static final IntVector I256_14449 = IntVector.broadcast(I256, 14449);

    /**
     * Eight ints equal to {@code 14811}, {@code cospi_9_64}.
     */
    private static final IntVector I256_14811 = IntVector.broadcast(I256, 14811);

    /**
     * Eight ints equal to {@code 15137}, {@code cospi_8_64}.
     */
    private static final IntVector I256_15137 = IntVector.broadcast(I256, 15137);

    /**
     * Eight ints equal to {@code 15426}, {@code cospi_7_64}.
     */
    private static final IntVector I256_15426 = IntVector.broadcast(I256, 15426);

    /**
     * Eight ints equal to {@code 15679}, {@code cospi_6_64}.
     */
    private static final IntVector I256_15679 = IntVector.broadcast(I256, 15679);

    /**
     * Eight ints equal to {@code 15893}, {@code cospi_5_64}.
     */
    private static final IntVector I256_15893 = IntVector.broadcast(I256, 15893);

    /**
     * Eight ints equal to {@code 1606}, {@code cospi_30_64}.
     */
    private static final IntVector I256_1606 = IntVector.broadcast(I256, 1606);

    /**
     * Eight ints equal to {@code 16069}, {@code cospi_4_64}.
     */
    private static final IntVector I256_16069 = IntVector.broadcast(I256, 16069);

    /**
     * Eight ints equal to {@code 16207}, {@code cospi_3_64}.
     */
    private static final IntVector I256_16207 = IntVector.broadcast(I256, 16207);

    /**
     * Eight ints equal to {@code 16305}, {@code cospi_2_64}.
     */
    private static final IntVector I256_16305 = IntVector.broadcast(I256, 16305);

    /**
     * Eight ints equal to {@code 16364}, {@code cospi_1_64}.
     */
    private static final IntVector I256_16364 = IntVector.broadcast(I256, 16364);

    /**
     * Eight ints equal to {@code 2404}, {@code cospi_29_64}.
     */
    private static final IntVector I256_2404 = IntVector.broadcast(I256, 2404);

    /**
     * Eight ints equal to {@code 3196}, {@code cospi_28_64}.
     */
    private static final IntVector I256_3196 = IntVector.broadcast(I256, 3196);

    /**
     * Eight ints equal to {@code 3981}, {@code cospi_27_64}.
     */
    private static final IntVector I256_3981 = IntVector.broadcast(I256, 3981);

    /**
     * Eight ints equal to {@code 4756}, {@code cospi_26_64}.
     */
    private static final IntVector I256_4756 = IntVector.broadcast(I256, 4756);

    /**
     * Eight ints equal to {@code 5520}, {@code cospi_25_64}.
     */
    private static final IntVector I256_5520 = IntVector.broadcast(I256, 5520);

    /**
     * Eight ints equal to {@code 6270}, {@code cospi_24_64}.
     */
    private static final IntVector I256_6270 = IntVector.broadcast(I256, 6270);

    /**
     * Eight ints equal to {@code 7005}, {@code cospi_23_64}.
     */
    private static final IntVector I256_7005 = IntVector.broadcast(I256, 7005);

    /**
     * Eight ints equal to {@code 7723}, {@code cospi_22_64}.
     */
    private static final IntVector I256_7723 = IntVector.broadcast(I256, 7723);

    /**
     * Eight ints equal to {@code 804}, {@code cospi_31_64}.
     */
    private static final IntVector I256_804 = IntVector.broadcast(I256, 804);

    /**
     * Eight ints equal to {@code 8192}, the rounding term of a 14-bit shift.
     */
    private static final IntVector I256_8192 = IntVector.broadcast(I256, 8192);

    /**
     * Eight ints equal to {@code 8423}, {@code cospi_21_64}.
     */
    private static final IntVector I256_8423 = IntVector.broadcast(I256, 8423);

    /**
     * Eight ints equal to {@code 9102}, {@code cospi_20_64}.
     */
    private static final IntVector I256_9102 = IntVector.broadcast(I256, 9102);

    /**
     * Eight ints equal to {@code 9760}, {@code cospi_19_64}.
     */
    private static final IntVector I256_9760 = IntVector.broadcast(I256, 9760);

    /**
     * Eight ints equal to {@code -11585}, minus {@code cospi_16_64}.
     */
    private static final IntVector I256_M11585 = IntVector.broadcast(I256, -11585);

    /**
     * Eight ints equal to {@code -6270}, minus {@code cospi_24_64}.
     */
    private static final IntVector I256_M6270 = IntVector.broadcast(I256, -6270);

    /**
     * The scalar kernels used where vectors do not pay off.
     */
    private final ScalarVp9Dsp scalar = new ScalarVp9Dsp();

    /**
     * Creates the vector implementation.
     */
    private VectorVp9Dsp() {
    }

    /**
     * Creates the vector implementation when the hardware has 256-bit vectors, otherwise the
     * scalar one; called only when the Vector API is available.
     *
     * @return the implementation
     */
    static Vp9Dsp create() {
        return ShortVector.SPECIES_PREFERRED.vectorBitSize() >= 256 ? new VectorVp9Dsp() : new ScalarVp9Dsp();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the species whose vector class is wanted
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void inverseTransformAdd(int txSize, int txType, int eob, int[] coef, int coefOff, byte[] dst,
                                    int dstOff, int stride, int[] scratch) {
        if (txType == Vp9Constants.DCT_DCT && eob == 1) {
            var out = ((short) coef[coefOff] * ScalarVp9Dsp.COSPI_16_64 + 8192) >> 14;
            out = (out * ScalarVp9Dsp.COSPI_16_64 + 8192) >> 14;
            var shift = txSize == Vp9Constants.TX_4X4 ? 4 : txSize == Vp9Constants.TX_8X8 ? 5 : 6;
            dcAdd(4 << txSize, (out + (1 << (shift - 1))) >> shift, dst, dstOff, stride);
            return;
        }
        var rowAdst = (txType & 2) != 0;
        var columnAdst = (txType & 1) != 0;
        switch (txSize) {
            case Vp9Constants.TX_4X4 -> {
                if (rowAdst) {
                    iadst4(coef, coefOff, scratch, 0, 4);
                } else {
                    idct4(coef, coefOff, scratch, 0, 4);
                }
                transposeIntermediate(scratch, 4, 4, 1);
                if (columnAdst) {
                    iadst4(scratch, INPUT, scratch, OUTPUT, 4);
                } else {
                    idct4(scratch, INPUT, scratch, OUTPUT, 4);
                }
                addColumns4(scratch, dst, dstOff, stride);
            }
            case Vp9Constants.TX_8X8 -> {
                if (rowAdst) {
                    iadst8(coef, coefOff, scratch, 0, 8);
                } else {
                    idct8(coef, coefOff, scratch, 0, 8);
                }
                transposeIntermediate(scratch, 8, 8, 1);
                if (columnAdst) {
                    iadst8(scratch, INPUT, scratch, OUTPUT, 8);
                } else {
                    idct8(scratch, INPUT, scratch, OUTPUT, 8);
                }
                addColumns8(scratch, dst, dstOff, stride, 8, 5);
            }
            case Vp9Constants.TX_16X16 -> {
                var groups = txType == Vp9Constants.DCT_DCT ? (ScalarVp9Dsp.partialRows(txSize, eob) + 7) >> 3 : 2;
                for (var g = 0; g < groups; g++) {
                    if (rowAdst) {
                        iadst16(coef, coefOff + g * INPUT_GROUP, scratch, g * 8, 16);
                    } else {
                        idct16(coef, coefOff + g * INPUT_GROUP, scratch, g * 8, 16);
                    }
                }
                clearRows(scratch, 16, groups * 8);
                transposeIntermediate(scratch, 16, 8, 2);
                for (var g = 0; g < 2; g++) {
                    if (columnAdst) {
                        iadst16(scratch, INPUT + g * INPUT_GROUP, scratch, OUTPUT, 8);
                    } else {
                        idct16(scratch, INPUT + g * INPUT_GROUP, scratch, OUTPUT, 8);
                    }
                    addColumns8(scratch, dst, dstOff + g * 8, stride, 16, 6);
                }
            }
            default -> {
                var groups = (ScalarVp9Dsp.partialRows(txSize, eob) + 7) >> 3;
                for (var g = 0; g < groups; g++) {
                    idct32(coef, coefOff + g * INPUT_GROUP, scratch, g * 8, 32);
                }
                clearRows(scratch, 32, groups * 8);
                transposeIntermediate(scratch, 32, 8, 4);
                for (var g = 0; g < 4; g++) {
                    idct32(scratch, INPUT + g * INPUT_GROUP, scratch, OUTPUT, 8);
                    addColumns8(scratch, dst, dstOff + g * 8, stride, 32, 6);
                }
            }
        }
    }

    /**
     * Rounds the outputs of the column pass of a group of 8 columns, adds them to the prediction
     * and clamps the result to 8 bits, two rows per iteration.
     *
     * @param s the scratch array holding the outputs from {@link #OUTPUT}, 8 per row
     * @param dst the plane holding the prediction
     * @param dstOff the index of the group's top-left sample
     * @param stride the plane's row stride
     * @param rows the number of rows, even
     * @param shift the final rounding shift of the transform size
     */
    private static void addColumns8(int[] s, byte[] dst, int dstOff, int stride, int rows, int shift) {
        var round = INT256.cast(IntVector.broadcast(I256, 1 << (shift - 1)));
        for (var j = 0; j < rows; j += 2) {
            var d0 = dstOff + j * stride;
            var d1 = d0 + stride;
            var v0 = INT256.cast(IntVector.fromArray(I256, s, OUTPUT + 8 * j)).lanewise(VectorOperators.ADD, round)
                    .lanewise(VectorOperators.ASHR, shift);
            var v1 = INT256.cast(IntVector.fromArray(I256, s, OUTPUT + 8 * j + 8)).lanewise(VectorOperators.ADD, round)
                    .lanewise(VectorOperators.ASHR, shift);
            BYTE64.cast(INT256.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d0))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0)).lanewise(VectorOperators.ADD, v0)
                    .lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, d0);
            BYTE64.cast(INT256.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0)).lanewise(VectorOperators.ADD, v1)
                    .lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).intoArray(dst, d1);
        }
    }

    /**
     * Rounds the outputs of the column pass of a 4x4 block, adds them to the prediction and clamps
     * the result to 8 bits.
     *
     * @param s the scratch array holding the outputs from {@link #OUTPUT}, 4 per row
     * @param dst the plane holding the prediction
     * @param dstOff the index of the block's top-left sample
     * @param stride the plane's row stride
     */
    private static void addColumns4(int[] s, byte[] dst, int dstOff, int stride) {
        var round = INT128.cast(IntVector.broadcast(I128, 8));
        for (var j = 0; j < 4; j += 2) {
            var d0 = dstOff + j * stride;
            var d1 = d0 + stride;
            var v0 = INT128.cast(IntVector.fromArray(I128, s, OUTPUT + 4 * j)).lanewise(VectorOperators.ADD, round)
                    .lanewise(VectorOperators.ASHR, 4);
            var v1 = INT128.cast(IntVector.fromArray(I128, s, OUTPUT + 4 * j + 4)).lanewise(VectorOperators.ADD, round)
                    .lanewise(VectorOperators.ASHR, 4);
            INT.set(dst, d0, INT64.cast(BYTE64.cast(INT128.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d0))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I128, 0)).lanewise(VectorOperators.ADD, v0)
                    .lanewise(VectorOperators.MAX, I128_0).lanewise(VectorOperators.MIN, I128_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).reinterpretShape(I64, 0)).lane(0));
            INT.set(dst, d1, INT64.cast(BYTE64.cast(INT128.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I128, 0)).lanewise(VectorOperators.ADD, v1)
                    .lanewise(VectorOperators.MAX, I128_0).lanewise(VectorOperators.MIN, I128_255)
                    .convertShape(VectorOperators.I2B, B64, 0)).reinterpretShape(I64, 0)).lane(0));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation stores the coefficients of each group of 8 rows (4 for 4x4 blocks)
     * column by column: the coefficient of row {@code r} and column {@code c} is at
     * {@code INPUT_GROUP * (r / 8) + 8 * c + r % 8} ({@code 4 * c + r} for 4x4 blocks), so that the
     * row pass loads element {@code c} of 8 rows as one vector.
     */
    @Override
    public short[] coefficientLayout(int txSize) {
        return LAYOUTS[txSize];
    }

    /**
     * Builds the coefficient layout of a transform size, as {@link #coefficientLayout} describes.
     *
     * @param txSize the transform size
     * @return the layout
     */
    private static short[] layout(int txSize) {
        var size = 4 << txSize;
        var lanes = txSize == Vp9Constants.TX_4X4 ? 4 : 8;
        var layout = new short[size * size];
        for (var r = 0; r < size; r++) {
            for (var c = 0; c < size; c++) {
                layout[r * size + c] = (short) (INPUT_GROUP * (r / lanes) + lanes * c + r % lanes);
            }
        }
        return layout;
    }

    /**
     * Copies groups of columns of the intermediate block, which the row pass leaves transposed at
     * the start of the scratch array, to the input rows of the scratch array, so that row
     * {@code j} of the columns of a group is contiguous.
     *
     * @param s the scratch array
     * @param size the block size
     * @param lanes the number of columns in a group
     * @param groups the number of groups
     */
    private static void transposeIntermediate(int[] s, int size, int lanes, int groups) {
        for (var g = 0; g < groups; g++) {
            var base = INPUT + g * INPUT_GROUP;
            for (var i = 0; i < lanes; i++) {
                var column = (g * lanes + i) * size;
                for (var j = 0; j < size; j++) {
                    s[base + j * lanes + i] = s[column + j];
                }
            }
        }
    }

    /**
     * Clears the entries of the transposed intermediate block that belong to rows the row pass
     * skipped because their coefficients are all zero.
     *
     * @param t the scratch array holding the transposed block from index 0
     * @param size the block size
     * @param from the first skipped row
     */
    private static void clearRows(int[] t, int size, int from) {
        if (from < size) {
            for (var k = 0; k < size; k++) {
                Arrays.fill(t, k * size + from, k * size + size, 0);
            }
        }
    }

    /**
     * Adds one value to every sample of a square block, clamping to {@code [0, 255]}.
     *
     * <p>The value is applied as a saturating unsigned addition of its positive part followed
     * by a saturating subtraction of its negative part, which clamps exactly like the reference.
     *
     * @param size the block size, 4 to 32
     * @param value the value to add
     * @param dst the plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the plane's row stride
     */
    private static void dcAdd(int size, int value, byte[] dst, int dstOff, int stride) {
        var plus = (byte) Math.clamp(value, 0, 255);
        var minus = (byte) Math.clamp(-value, 0, 255);
        if (size == 32) {
            var add = BYTE256.cast(ByteVector.broadcast(B256, plus));
            var sub = BYTE256.cast(ByteVector.broadcast(B256, minus));
            for (var y = 0; y < 32; y++) {
                var row = dstOff + y * stride;
                BYTE256.cast(BYTE256.cast(ByteVector.fromArray(B256, dst, row)).lanewise(VectorOperators.SUADD, add)
                        .lanewise(VectorOperators.SUSUB, sub)).intoArray(dst, row);
            }
        } else if (size == 16) {
            var add = BYTE128.cast(ByteVector.broadcast(B128, plus));
            var sub = BYTE128.cast(ByteVector.broadcast(B128, minus));
            for (var y = 0; y < 16; y++) {
                var row = dstOff + y * stride;
                BYTE128.cast(BYTE128.cast(ByteVector.fromArray(B128, dst, row)).lanewise(VectorOperators.SUADD, add)
                        .lanewise(VectorOperators.SUSUB, sub)).intoArray(dst, row);
            }
        } else {
            var add = BYTE64.cast(ByteVector.broadcast(B64, plus));
            var sub = BYTE64.cast(ByteVector.broadcast(B64, minus));
            for (var y = 0; y < size; y++) {
                var row = dstOff + y * stride;
                var sum = BYTE64.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, row)).lanewise(VectorOperators.SUADD, add)
                        .lanewise(VectorOperators.SUSUB, sub));
                if (size == 8) {
                    sum.intoArray(dst, row);
                } else {
                    INT.set(dst, row, INT64.cast(sum.reinterpretShape(I64, 0)).lane(0));
                }
            }
        }
    }

    /**
     * Computes the 4-point inverse DCT of 4 rows or 4 columns of a block at once, one per
     * lane.
     *
     * <p>Element {@code k} of the 4 transforms is read at {@code inOff + 4 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct4(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var in0 = INT128.cast(IntVector.fromArray(I128, in, inBase + 0));
        var in2 = INT128.cast(IntVector.fromArray(I128, in, inBase + 8));
        var in1 = INT128.cast(IntVector.fromArray(I128, in, inBase + 4));
        var in3 = INT128.cast(IntVector.fromArray(I128, in, inBase + 12));
        var temp1 = in0.lanewise(VectorOperators.ADD, in2).lanewise(VectorOperators.MUL, I128_11585);
        var temp2 = in0.lanewise(VectorOperators.SUB, in2).lanewise(VectorOperators.MUL, I128_11585);
        var step_0 = temp1.lanewise(VectorOperators.ADD, I128_8192).lanewise(VectorOperators.ASHR, 14);
        var step_1 = temp2.lanewise(VectorOperators.ADD, I128_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = in1.lanewise(VectorOperators.MUL, I128_6270).lanewise(VectorOperators.SUB, in3
                .lanewise(VectorOperators.MUL, I128_15137));
        temp2 = in1.lanewise(VectorOperators.MUL, I128_15137).lanewise(VectorOperators.ADD, in3
                .lanewise(VectorOperators.MUL, I128_6270));
        var step_2 = temp1.lanewise(VectorOperators.ADD, I128_8192).lanewise(VectorOperators.ASHR, 14);
        var step_3 = temp2.lanewise(VectorOperators.ADD, I128_8192).lanewise(VectorOperators.ASHR, 14);
        INT128.cast(step_0.lanewise(VectorOperators.ADD, step_3)).intoArray(s, tOff);
        INT128.cast(step_1.lanewise(VectorOperators.ADD, step_2)).intoArray(s, tOff + 1 * tStride);
        INT128.cast(step_1.lanewise(VectorOperators.SUB, step_2)).intoArray(s, tOff + 2 * tStride);
        INT128.cast(step_0.lanewise(VectorOperators.SUB, step_3)).intoArray(s, tOff + 3 * tStride);
    }

    /**
     * Computes the 4-point inverse ADST of 4 rows or 4 columns of a block at once, one per
     * lane.
     *
     * <p>Element {@code k} of the 4 transforms is read at {@code inOff + 4 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void iadst4(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var in0 = INT128.cast(IntVector.fromArray(I128, in, inBase + 0));
        var in1 = INT128.cast(IntVector.fromArray(I128, in, inBase + 4));
        var in2 = INT128.cast(IntVector.fromArray(I128, in, inBase + 8));
        var in3 = INT128.cast(IntVector.fromArray(I128, in, inBase + 12));
        var x0 = INT128.cast(in0);
        var x1 = INT128.cast(in1);
        var x2 = INT128.cast(in2);
        var x3 = INT128.cast(in3);
        var s0 = x0.lanewise(VectorOperators.MUL, I128_5283);
        var s1 = x0.lanewise(VectorOperators.MUL, I128_9929);
        var s2 = x1.lanewise(VectorOperators.MUL, I128_13377);
        var s3 = x2.lanewise(VectorOperators.MUL, I128_15212);
        var s4 = x2.lanewise(VectorOperators.MUL, I128_5283);
        var s5 = x3.lanewise(VectorOperators.MUL, I128_9929);
        var s6 = x3.lanewise(VectorOperators.MUL, I128_15212);
        var s7 = x0.lanewise(VectorOperators.SUB, x2).lanewise(VectorOperators.ADD, x3);
        s0 = s0.lanewise(VectorOperators.ADD, s3).lanewise(VectorOperators.ADD, s5);
        s1 = s1.lanewise(VectorOperators.SUB, s4).lanewise(VectorOperators.SUB, s6);
        s3 = INT128.cast(s2);
        s2 = s7.lanewise(VectorOperators.MUL, I128_13377);
        INT128.cast(s0.lanewise(VectorOperators.ADD, s3).lanewise(VectorOperators.ADD, I128_8192)
                .lanewise(VectorOperators.ASHR, 14)).intoArray(s, tOff);
        INT128.cast(s1.lanewise(VectorOperators.ADD, s3).lanewise(VectorOperators.ADD, I128_8192)
                .lanewise(VectorOperators.ASHR, 14)).intoArray(s, tOff + 1 * tStride);
        INT128.cast(s2.lanewise(VectorOperators.ADD, I128_8192).lanewise(VectorOperators.ASHR, 14))
                .intoArray(s, tOff + 2 * tStride);
        INT128.cast(s0.lanewise(VectorOperators.ADD, s1).lanewise(VectorOperators.SUB, s3)
                .lanewise(VectorOperators.ADD, I128_8192).lanewise(VectorOperators.ASHR, 14))
                .intoArray(s, tOff + 3 * tStride);
    }

    /**
     * Computes the 8-point inverse DCT of 8 rows or 8 columns of a block at once, one per
     * lane.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct8(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var in0 = INT256.cast(IntVector.fromArray(I256, in, inBase + 0));
        var in4 = INT256.cast(IntVector.fromArray(I256, in, inBase + 32));
        var in2 = INT256.cast(IntVector.fromArray(I256, in, inBase + 16));
        var in6 = INT256.cast(IntVector.fromArray(I256, in, inBase + 48));
        var in1 = INT256.cast(IntVector.fromArray(I256, in, inBase + 8));
        var in7 = INT256.cast(IntVector.fromArray(I256, in, inBase + 56));
        var in3 = INT256.cast(IntVector.fromArray(I256, in, inBase + 24));
        var in5 = INT256.cast(IntVector.fromArray(I256, in, inBase + 40));
        var step1_0 = INT256.cast(in0);
        var step1_2 = INT256.cast(in4);
        var step1_1 = INT256.cast(in2);
        var step1_3 = INT256.cast(in6);
        var temp1 = in1.lanewise(VectorOperators.MUL, I256_3196).lanewise(VectorOperators.SUB, in7
                .lanewise(VectorOperators.MUL, I256_16069));
        var temp2 = in1.lanewise(VectorOperators.MUL, I256_16069).lanewise(VectorOperators.ADD, in7
                .lanewise(VectorOperators.MUL, I256_3196));
        var step1_4 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_7 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = in5.lanewise(VectorOperators.MUL, I256_13623).lanewise(VectorOperators.SUB, in3
                .lanewise(VectorOperators.MUL, I256_9102));
        temp2 = in5.lanewise(VectorOperators.MUL, I256_9102).lanewise(VectorOperators.ADD, in3
                .lanewise(VectorOperators.MUL, I256_13623));
        var step1_5 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_6 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step1_0.lanewise(VectorOperators.ADD, step1_2).lanewise(VectorOperators.MUL, I256_11585);
        temp2 = step1_0.lanewise(VectorOperators.SUB, step1_2).lanewise(VectorOperators.MUL, I256_11585);
        var step2_0 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_1 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step1_1.lanewise(VectorOperators.MUL, I256_6270).lanewise(VectorOperators.SUB, step1_3
                .lanewise(VectorOperators.MUL, I256_15137));
        temp2 = step1_1.lanewise(VectorOperators.MUL, I256_15137).lanewise(VectorOperators.ADD, step1_3
                .lanewise(VectorOperators.MUL, I256_6270));
        var step2_2 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_3 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_4 = step1_4.lanewise(VectorOperators.ADD, step1_5);
        var step2_5 = step1_4.lanewise(VectorOperators.SUB, step1_5);
        var step2_6 = step1_6.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_7);
        var step2_7 = step1_6.lanewise(VectorOperators.ADD, step1_7);
        step1_0 = step2_0.lanewise(VectorOperators.ADD, step2_3);
        step1_1 = step2_1.lanewise(VectorOperators.ADD, step2_2);
        step1_2 = step2_1.lanewise(VectorOperators.SUB, step2_2);
        step1_3 = step2_0.lanewise(VectorOperators.SUB, step2_3);
        step1_4 = INT256.cast(step2_4);
        temp1 = step2_6.lanewise(VectorOperators.SUB, step2_5).lanewise(VectorOperators.MUL, I256_11585);
        temp2 = step2_5.lanewise(VectorOperators.ADD, step2_6).lanewise(VectorOperators.MUL, I256_11585);
        step1_5 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        step1_6 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        step1_7 = INT256.cast(step2_7);
        INT256.cast(step1_0.lanewise(VectorOperators.ADD, step1_7)).intoArray(s, tOff);
        INT256.cast(step1_1.lanewise(VectorOperators.ADD, step1_6)).intoArray(s, tOff + 1 * tStride);
        INT256.cast(step1_2.lanewise(VectorOperators.ADD, step1_5)).intoArray(s, tOff + 2 * tStride);
        INT256.cast(step1_3.lanewise(VectorOperators.ADD, step1_4)).intoArray(s, tOff + 3 * tStride);
        INT256.cast(step1_3.lanewise(VectorOperators.SUB, step1_4)).intoArray(s, tOff + 4 * tStride);
        INT256.cast(step1_2.lanewise(VectorOperators.SUB, step1_5)).intoArray(s, tOff + 5 * tStride);
        INT256.cast(step1_1.lanewise(VectorOperators.SUB, step1_6)).intoArray(s, tOff + 6 * tStride);
        INT256.cast(step1_0.lanewise(VectorOperators.SUB, step1_7)).intoArray(s, tOff + 7 * tStride);
    }

    /**
     * Computes the 8-point inverse ADST of 8 rows or 8 columns of a block at once, one per
     * lane.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void iadst8(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var in7 = INT256.cast(IntVector.fromArray(I256, in, inBase + 56));
        var in0 = INT256.cast(IntVector.fromArray(I256, in, inBase + 0));
        var in5 = INT256.cast(IntVector.fromArray(I256, in, inBase + 40));
        var in2 = INT256.cast(IntVector.fromArray(I256, in, inBase + 16));
        var in3 = INT256.cast(IntVector.fromArray(I256, in, inBase + 24));
        var in4 = INT256.cast(IntVector.fromArray(I256, in, inBase + 32));
        var in1 = INT256.cast(IntVector.fromArray(I256, in, inBase + 8));
        var in6 = INT256.cast(IntVector.fromArray(I256, in, inBase + 48));
        var x0 = INT256.cast(in7);
        var x1 = INT256.cast(in0);
        var x2 = INT256.cast(in5);
        var x3 = INT256.cast(in2);
        var x4 = INT256.cast(in3);
        var x5 = INT256.cast(in4);
        var x6 = INT256.cast(in1);
        var x7 = INT256.cast(in6);
        var s0 = x0.lanewise(VectorOperators.MUL, I256_16305).lanewise(VectorOperators.ADD, x1
                .lanewise(VectorOperators.MUL, I256_1606));
        var s1 = x0.lanewise(VectorOperators.MUL, I256_1606).lanewise(VectorOperators.SUB, x1
                .lanewise(VectorOperators.MUL, I256_16305));
        var s2 = x2.lanewise(VectorOperators.MUL, I256_14449).lanewise(VectorOperators.ADD, x3
                .lanewise(VectorOperators.MUL, I256_7723));
        var s3 = x2.lanewise(VectorOperators.MUL, I256_7723).lanewise(VectorOperators.SUB, x3
                .lanewise(VectorOperators.MUL, I256_14449));
        var s4 = x4.lanewise(VectorOperators.MUL, I256_10394).lanewise(VectorOperators.ADD, x5
                .lanewise(VectorOperators.MUL, I256_12665));
        var s5 = x4.lanewise(VectorOperators.MUL, I256_12665).lanewise(VectorOperators.SUB, x5
                .lanewise(VectorOperators.MUL, I256_10394));
        var s6 = x6.lanewise(VectorOperators.MUL, I256_4756).lanewise(VectorOperators.ADD, x7
                .lanewise(VectorOperators.MUL, I256_15679));
        var s7 = x6.lanewise(VectorOperators.MUL, I256_15679).lanewise(VectorOperators.SUB, x7
                .lanewise(VectorOperators.MUL, I256_4756));
        x0 = s0.lanewise(VectorOperators.ADD, s4).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x1 = s1.lanewise(VectorOperators.ADD, s5).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x2 = s2.lanewise(VectorOperators.ADD, s6).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x3 = s3.lanewise(VectorOperators.ADD, s7).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x4 = s0.lanewise(VectorOperators.SUB, s4).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x5 = s1.lanewise(VectorOperators.SUB, s5).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x6 = s2.lanewise(VectorOperators.SUB, s6).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x7 = s3.lanewise(VectorOperators.SUB, s7).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        s0 = INT256.cast(x0);
        s1 = INT256.cast(x1);
        s2 = INT256.cast(x2);
        s3 = INT256.cast(x3);
        s4 = x4.lanewise(VectorOperators.MUL, I256_15137).lanewise(VectorOperators.ADD, x5
                .lanewise(VectorOperators.MUL, I256_6270));
        s5 = x4.lanewise(VectorOperators.MUL, I256_6270).lanewise(VectorOperators.SUB, x5
                .lanewise(VectorOperators.MUL, I256_15137));
        s6 = x6.lanewise(VectorOperators.MUL, I256_M6270).lanewise(VectorOperators.ADD, x7
                .lanewise(VectorOperators.MUL, I256_15137));
        s7 = x6.lanewise(VectorOperators.MUL, I256_15137).lanewise(VectorOperators.ADD, x7
                .lanewise(VectorOperators.MUL, I256_6270));
        x0 = s0.lanewise(VectorOperators.ADD, s2);
        x1 = s1.lanewise(VectorOperators.ADD, s3);
        x2 = s0.lanewise(VectorOperators.SUB, s2);
        x3 = s1.lanewise(VectorOperators.SUB, s3);
        x4 = s4.lanewise(VectorOperators.ADD, s6).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x5 = s5.lanewise(VectorOperators.ADD, s7).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x6 = s4.lanewise(VectorOperators.SUB, s6).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x7 = s5.lanewise(VectorOperators.SUB, s7).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        s2 = x2.lanewise(VectorOperators.ADD, x3).lanewise(VectorOperators.MUL, I256_11585);
        s3 = x2.lanewise(VectorOperators.SUB, x3).lanewise(VectorOperators.MUL, I256_11585);
        s6 = x6.lanewise(VectorOperators.ADD, x7).lanewise(VectorOperators.MUL, I256_11585);
        s7 = x6.lanewise(VectorOperators.SUB, x7).lanewise(VectorOperators.MUL, I256_11585);
        x2 = s2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        x3 = s3.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        x6 = s6.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        x7 = s7.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        INT256.cast(INT256.cast(x0)).intoArray(s, tOff);
        INT256.cast(x4.lanewise(VectorOperators.NEG)).intoArray(s, tOff + 1 * tStride);
        INT256.cast(INT256.cast(x6)).intoArray(s, tOff + 2 * tStride);
        INT256.cast(x2.lanewise(VectorOperators.NEG)).intoArray(s, tOff + 3 * tStride);
        INT256.cast(INT256.cast(x3)).intoArray(s, tOff + 4 * tStride);
        INT256.cast(x7.lanewise(VectorOperators.NEG)).intoArray(s, tOff + 5 * tStride);
        INT256.cast(INT256.cast(x5)).intoArray(s, tOff + 6 * tStride);
        INT256.cast(x1.lanewise(VectorOperators.NEG)).intoArray(s, tOff + 7 * tStride);
    }

    /**
     * Computes the 16-point inverse DCT of 8 rows or 8 columns of a block at once, one per
     * lane, stage 1 of 3.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct16Stage0(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var in0 = INT256.cast(IntVector.fromArray(I256, in, inBase + 0));
        var in8 = INT256.cast(IntVector.fromArray(I256, in, inBase + 64));
        var in4 = INT256.cast(IntVector.fromArray(I256, in, inBase + 32));
        var in12 = INT256.cast(IntVector.fromArray(I256, in, inBase + 96));
        var in2 = INT256.cast(IntVector.fromArray(I256, in, inBase + 16));
        var in10 = INT256.cast(IntVector.fromArray(I256, in, inBase + 80));
        var in6 = INT256.cast(IntVector.fromArray(I256, in, inBase + 48));
        var in14 = INT256.cast(IntVector.fromArray(I256, in, inBase + 112));
        var in1 = INT256.cast(IntVector.fromArray(I256, in, inBase + 8));
        var in9 = INT256.cast(IntVector.fromArray(I256, in, inBase + 72));
        var in5 = INT256.cast(IntVector.fromArray(I256, in, inBase + 40));
        var in13 = INT256.cast(IntVector.fromArray(I256, in, inBase + 104));
        var in3 = INT256.cast(IntVector.fromArray(I256, in, inBase + 24));
        var in11 = INT256.cast(IntVector.fromArray(I256, in, inBase + 88));
        var in7 = INT256.cast(IntVector.fromArray(I256, in, inBase + 56));
        var in15 = INT256.cast(IntVector.fromArray(I256, in, inBase + 120));
        var step1_0 = INT256.cast(in0);
        var step1_1 = INT256.cast(in8);
        var step1_2 = INT256.cast(in4);
        var step1_3 = INT256.cast(in12);
        var step1_4 = INT256.cast(in2);
        var step1_5 = INT256.cast(in10);
        var step1_6 = INT256.cast(in6);
        var step1_7 = INT256.cast(in14);
        var step1_8 = INT256.cast(in1);
        var step1_9 = INT256.cast(in9);
        var step1_10 = INT256.cast(in5);
        var step1_11 = INT256.cast(in13);
        var step1_12 = INT256.cast(in3);
        var step1_13 = INT256.cast(in11);
        var step1_14 = INT256.cast(in7);
        var step1_15 = INT256.cast(in15);
        var step2_0 = INT256.cast(step1_0);
        var step2_1 = INT256.cast(step1_1);
        var step2_2 = INT256.cast(step1_2);
        var step2_3 = INT256.cast(step1_3);
        var step2_4 = INT256.cast(step1_4);
        var step2_5 = INT256.cast(step1_5);
        var step2_6 = INT256.cast(step1_6);
        var step2_7 = INT256.cast(step1_7);
        var temp1 = step1_8.lanewise(VectorOperators.MUL, I256_1606).lanewise(VectorOperators.SUB, step1_15
                .lanewise(VectorOperators.MUL, I256_16305));
        var temp2 = step1_8.lanewise(VectorOperators.MUL, I256_16305).lanewise(VectorOperators.ADD, step1_15
                .lanewise(VectorOperators.MUL, I256_1606));
        var step2_8 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_15 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step1_9.lanewise(VectorOperators.MUL, I256_12665).lanewise(VectorOperators.SUB, step1_14
                .lanewise(VectorOperators.MUL, I256_10394));
        temp2 = step1_9.lanewise(VectorOperators.MUL, I256_10394).lanewise(VectorOperators.ADD, step1_14
                .lanewise(VectorOperators.MUL, I256_12665));
        var step2_9 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_14 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step1_10.lanewise(VectorOperators.MUL, I256_7723).lanewise(VectorOperators.SUB, step1_13
                .lanewise(VectorOperators.MUL, I256_14449));
        temp2 = step1_10.lanewise(VectorOperators.MUL, I256_14449).lanewise(VectorOperators.ADD, step1_13
                .lanewise(VectorOperators.MUL, I256_7723));
        var step2_10 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_13 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step1_11.lanewise(VectorOperators.MUL, I256_15679).lanewise(VectorOperators.SUB, step1_12
                .lanewise(VectorOperators.MUL, I256_4756));
        temp2 = step1_11.lanewise(VectorOperators.MUL, I256_4756).lanewise(VectorOperators.ADD, step1_12
                .lanewise(VectorOperators.MUL, I256_15679));
        var step2_11 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_12 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        step1_0 = INT256.cast(step2_0);
        step1_1 = INT256.cast(step2_1);
        step1_2 = INT256.cast(step2_2);
        step1_3 = INT256.cast(step2_3);
        temp1 = step2_4.lanewise(VectorOperators.MUL, I256_3196).lanewise(VectorOperators.SUB, step2_7
                .lanewise(VectorOperators.MUL, I256_16069));
        temp2 = step2_4.lanewise(VectorOperators.MUL, I256_16069).lanewise(VectorOperators.ADD, step2_7
                .lanewise(VectorOperators.MUL, I256_3196));
        step1_4 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        step1_7 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step2_5.lanewise(VectorOperators.MUL, I256_13623).lanewise(VectorOperators.SUB, step2_6
                .lanewise(VectorOperators.MUL, I256_9102));
        temp2 = step2_5.lanewise(VectorOperators.MUL, I256_9102).lanewise(VectorOperators.ADD, step2_6
                .lanewise(VectorOperators.MUL, I256_13623));
        step1_5 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        step1_6 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        INT256.cast(step1_0).intoArray(s, slotBase + 0);
        INT256.cast(step1_1).intoArray(s, slotBase + 8);
        INT256.cast(step1_2).intoArray(s, slotBase + 16);
        INT256.cast(step1_3).intoArray(s, slotBase + 24);
        INT256.cast(step1_4).intoArray(s, slotBase + 32);
        INT256.cast(step1_5).intoArray(s, slotBase + 40);
        INT256.cast(step1_6).intoArray(s, slotBase + 48);
        INT256.cast(step1_7).intoArray(s, slotBase + 56);
        INT256.cast(step2_8).intoArray(s, slotBase + 64);
        INT256.cast(step2_15).intoArray(s, slotBase + 72);
        INT256.cast(step2_9).intoArray(s, slotBase + 80);
        INT256.cast(step2_14).intoArray(s, slotBase + 88);
        INT256.cast(step2_10).intoArray(s, slotBase + 96);
        INT256.cast(step2_13).intoArray(s, slotBase + 104);
        INT256.cast(step2_11).intoArray(s, slotBase + 112);
        INT256.cast(step2_12).intoArray(s, slotBase + 120);
    }

    /**
     * Computes the 16-point inverse DCT of 8 rows or 8 columns of a block at once, one per
     * lane, stage 2 of 3.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct16Stage1(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var step2_8 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 64));
        var step2_9 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 80));
        var step2_10 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 96));
        var step2_11 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 112));
        var step2_12 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 120));
        var step2_13 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 104));
        var step2_14 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 88));
        var step2_15 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 72));
        var step1_0 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 0));
        var step1_1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 8));
        var step1_2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 16));
        var step1_3 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 24));
        var step1_4 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 32));
        var step1_5 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 40));
        var step1_6 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 48));
        var step1_7 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 56));
        var step1_8 = step2_8.lanewise(VectorOperators.ADD, step2_9);
        var step1_9 = step2_8.lanewise(VectorOperators.SUB, step2_9);
        var step1_10 = step2_10.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step2_11);
        var step1_11 = step2_10.lanewise(VectorOperators.ADD, step2_11);
        var step1_12 = step2_12.lanewise(VectorOperators.ADD, step2_13);
        var step1_13 = step2_12.lanewise(VectorOperators.SUB, step2_13);
        var step1_14 = step2_14.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step2_15);
        var step1_15 = step2_14.lanewise(VectorOperators.ADD, step2_15);
        var temp1 = step1_0.lanewise(VectorOperators.ADD, step1_1).lanewise(VectorOperators.MUL, I256_11585);
        var temp2 = step1_0.lanewise(VectorOperators.SUB, step1_1).lanewise(VectorOperators.MUL, I256_11585);
        var step2_0 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_1 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step1_2.lanewise(VectorOperators.MUL, I256_6270).lanewise(VectorOperators.SUB, step1_3
                .lanewise(VectorOperators.MUL, I256_15137));
        temp2 = step1_2.lanewise(VectorOperators.MUL, I256_15137).lanewise(VectorOperators.ADD, step1_3
                .lanewise(VectorOperators.MUL, I256_6270));
        var step2_2 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_3 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_4 = step1_4.lanewise(VectorOperators.ADD, step1_5);
        var step2_5 = step1_4.lanewise(VectorOperators.SUB, step1_5);
        var step2_6 = step1_6.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_7);
        var step2_7 = step1_6.lanewise(VectorOperators.ADD, step1_7);
        step2_8 = INT256.cast(step1_8);
        step2_15 = INT256.cast(step1_15);
        temp1 = step1_9.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_15137)
                .lanewise(VectorOperators.ADD, step1_14.lanewise(VectorOperators.MUL, I256_6270));
        temp2 = step1_9.lanewise(VectorOperators.MUL, I256_6270).lanewise(VectorOperators.ADD, step1_14
                .lanewise(VectorOperators.MUL, I256_15137));
        step2_9 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        step2_14 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step1_10.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_6270)
                .lanewise(VectorOperators.SUB, step1_13.lanewise(VectorOperators.MUL, I256_15137));
        temp2 = step1_10.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_15137)
                .lanewise(VectorOperators.ADD, step1_13.lanewise(VectorOperators.MUL, I256_6270));
        step2_10 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        step2_13 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        step2_11 = INT256.cast(step1_11);
        step2_12 = INT256.cast(step1_12);
        step1_0 = step2_0.lanewise(VectorOperators.ADD, step2_3);
        step1_1 = step2_1.lanewise(VectorOperators.ADD, step2_2);
        step1_2 = step2_1.lanewise(VectorOperators.SUB, step2_2);
        step1_3 = step2_0.lanewise(VectorOperators.SUB, step2_3);
        step1_4 = INT256.cast(step2_4);
        temp1 = step2_6.lanewise(VectorOperators.SUB, step2_5).lanewise(VectorOperators.MUL, I256_11585);
        temp2 = step2_5.lanewise(VectorOperators.ADD, step2_6).lanewise(VectorOperators.MUL, I256_11585);
        step1_5 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        step1_6 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        step1_7 = INT256.cast(step2_7);
        step1_8 = step2_8.lanewise(VectorOperators.ADD, step2_11);
        INT256.cast(step1_8).intoArray(s, slotBase + 128);
        INT256.cast(step2_8).intoArray(s, slotBase + 64);
        INT256.cast(step2_15).intoArray(s, slotBase + 72);
        INT256.cast(step2_9).intoArray(s, slotBase + 80);
        INT256.cast(step2_14).intoArray(s, slotBase + 88);
        INT256.cast(step2_10).intoArray(s, slotBase + 96);
        INT256.cast(step2_13).intoArray(s, slotBase + 104);
        INT256.cast(step2_11).intoArray(s, slotBase + 112);
        INT256.cast(step2_12).intoArray(s, slotBase + 120);
        INT256.cast(step1_0).intoArray(s, slotBase + 0);
        INT256.cast(step1_1).intoArray(s, slotBase + 8);
        INT256.cast(step1_2).intoArray(s, slotBase + 16);
        INT256.cast(step1_3).intoArray(s, slotBase + 24);
        INT256.cast(step1_4).intoArray(s, slotBase + 32);
        INT256.cast(step1_5).intoArray(s, slotBase + 40);
        INT256.cast(step1_6).intoArray(s, slotBase + 48);
        INT256.cast(step1_7).intoArray(s, slotBase + 56);
    }

    /**
     * Computes the 16-point inverse DCT of 8 rows or 8 columns of a block at once, one per
     * lane, stage 3 of 3.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct16Stage2(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var step2_10 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 96));
        var step2_9 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 80));
        var step2_11 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 112));
        var step2_8 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 64));
        var step2_12 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 120));
        var step2_15 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 72));
        var step2_13 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 104));
        var step2_14 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 88));
        var step1_0 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 0));
        var step1_7 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 56));
        var step1_1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 8));
        var step1_6 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 48));
        var step1_2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 16));
        var step1_5 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 40));
        var step1_3 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 24));
        var step1_4 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 32));
        var step1_8 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 128));
        var step1_9 = step2_9.lanewise(VectorOperators.ADD, step2_10);
        var step1_10 = step2_9.lanewise(VectorOperators.SUB, step2_10);
        var step1_11 = step2_8.lanewise(VectorOperators.SUB, step2_11);
        var step1_12 = step2_12.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step2_15);
        var step1_13 = step2_13.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step2_14);
        var step1_14 = step2_13.lanewise(VectorOperators.ADD, step2_14);
        var step1_15 = step2_12.lanewise(VectorOperators.ADD, step2_15);
        var step2_0 = step1_0.lanewise(VectorOperators.ADD, step1_7);
        var step2_1 = step1_1.lanewise(VectorOperators.ADD, step1_6);
        var step2_2 = step1_2.lanewise(VectorOperators.ADD, step1_5);
        var step2_3 = step1_3.lanewise(VectorOperators.ADD, step1_4);
        var step2_4 = step1_3.lanewise(VectorOperators.SUB, step1_4);
        var step2_5 = step1_2.lanewise(VectorOperators.SUB, step1_5);
        var step2_6 = step1_1.lanewise(VectorOperators.SUB, step1_6);
        var step2_7 = step1_0.lanewise(VectorOperators.SUB, step1_7);
        step2_8 = INT256.cast(step1_8);
        step2_9 = INT256.cast(step1_9);
        var temp1 = step1_10.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_13)
                .lanewise(VectorOperators.MUL, I256_11585);
        var temp2 = step1_10.lanewise(VectorOperators.ADD, step1_13).lanewise(VectorOperators.MUL, I256_11585);
        step2_10 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        step2_13 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step1_11.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_12)
                .lanewise(VectorOperators.MUL, I256_11585);
        temp2 = step1_11.lanewise(VectorOperators.ADD, step1_12).lanewise(VectorOperators.MUL, I256_11585);
        step2_11 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        step2_12 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        step2_14 = INT256.cast(step1_14);
        step2_15 = INT256.cast(step1_15);
        INT256.cast(step2_0.lanewise(VectorOperators.ADD, step2_15)).intoArray(s, tOff);
        INT256.cast(step2_1.lanewise(VectorOperators.ADD, step2_14)).intoArray(s, tOff + 1 * tStride);
        INT256.cast(step2_2.lanewise(VectorOperators.ADD, step2_13)).intoArray(s, tOff + 2 * tStride);
        INT256.cast(step2_3.lanewise(VectorOperators.ADD, step2_12)).intoArray(s, tOff + 3 * tStride);
        INT256.cast(step2_4.lanewise(VectorOperators.ADD, step2_11)).intoArray(s, tOff + 4 * tStride);
        INT256.cast(step2_5.lanewise(VectorOperators.ADD, step2_10)).intoArray(s, tOff + 5 * tStride);
        INT256.cast(step2_6.lanewise(VectorOperators.ADD, step2_9)).intoArray(s, tOff + 6 * tStride);
        INT256.cast(step2_7.lanewise(VectorOperators.ADD, step2_8)).intoArray(s, tOff + 7 * tStride);
        INT256.cast(step2_7.lanewise(VectorOperators.SUB, step2_8)).intoArray(s, tOff + 8 * tStride);
        INT256.cast(step2_6.lanewise(VectorOperators.SUB, step2_9)).intoArray(s, tOff + 9 * tStride);
        INT256.cast(step2_5.lanewise(VectorOperators.SUB, step2_10)).intoArray(s, tOff + 10 * tStride);
        INT256.cast(step2_4.lanewise(VectorOperators.SUB, step2_11)).intoArray(s, tOff + 11 * tStride);
        INT256.cast(step2_3.lanewise(VectorOperators.SUB, step2_12)).intoArray(s, tOff + 12 * tStride);
        INT256.cast(step2_2.lanewise(VectorOperators.SUB, step2_13)).intoArray(s, tOff + 13 * tStride);
        INT256.cast(step2_1.lanewise(VectorOperators.SUB, step2_14)).intoArray(s, tOff + 14 * tStride);
        INT256.cast(step2_0.lanewise(VectorOperators.SUB, step2_15)).intoArray(s, tOff + 15 * tStride);
    }

    /**
     * Computes the 16-point inverse DCT of 8 rows or 8 columns of a block at once, running its
     * stages in order.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct16(int[] in, int inOff, int[] s, int tOff, int tStride) {
        idct16Stage0(in, inOff, s, tOff, tStride);
        idct16Stage1(in, inOff, s, tOff, tStride);
        idct16Stage2(in, inOff, s, tOff, tStride);
    }

    /**
     * Computes the 16-point inverse ADST of 8 rows or 8 columns of a block at once, one per
     * lane, stage 1 of 4.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void iadst16Stage0(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var in15 = INT256.cast(IntVector.fromArray(I256, in, inBase + 120));
        var in0 = INT256.cast(IntVector.fromArray(I256, in, inBase + 0));
        var in13 = INT256.cast(IntVector.fromArray(I256, in, inBase + 104));
        var in2 = INT256.cast(IntVector.fromArray(I256, in, inBase + 16));
        var in11 = INT256.cast(IntVector.fromArray(I256, in, inBase + 88));
        var in4 = INT256.cast(IntVector.fromArray(I256, in, inBase + 32));
        var in9 = INT256.cast(IntVector.fromArray(I256, in, inBase + 72));
        var in6 = INT256.cast(IntVector.fromArray(I256, in, inBase + 48));
        var in7 = INT256.cast(IntVector.fromArray(I256, in, inBase + 56));
        var in8 = INT256.cast(IntVector.fromArray(I256, in, inBase + 64));
        var in5 = INT256.cast(IntVector.fromArray(I256, in, inBase + 40));
        var in10 = INT256.cast(IntVector.fromArray(I256, in, inBase + 80));
        var in3 = INT256.cast(IntVector.fromArray(I256, in, inBase + 24));
        var in12 = INT256.cast(IntVector.fromArray(I256, in, inBase + 96));
        var in1 = INT256.cast(IntVector.fromArray(I256, in, inBase + 8));
        var in14 = INT256.cast(IntVector.fromArray(I256, in, inBase + 112));
        var x0 = INT256.cast(in15);
        var x1 = INT256.cast(in0);
        var x2 = INT256.cast(in13);
        var x3 = INT256.cast(in2);
        var x4 = INT256.cast(in11);
        var x5 = INT256.cast(in4);
        var x6 = INT256.cast(in9);
        var x7 = INT256.cast(in6);
        var x8 = INT256.cast(in7);
        var x9 = INT256.cast(in8);
        var x10 = INT256.cast(in5);
        var x11 = INT256.cast(in10);
        var x12 = INT256.cast(in3);
        var x13 = INT256.cast(in12);
        var x14 = INT256.cast(in1);
        var x15 = INT256.cast(in14);
        var s0 = x0.lanewise(VectorOperators.MUL, I256_16364).lanewise(VectorOperators.ADD, x1
                .lanewise(VectorOperators.MUL, I256_804));
        var s1 = x0.lanewise(VectorOperators.MUL, I256_804).lanewise(VectorOperators.SUB, x1
                .lanewise(VectorOperators.MUL, I256_16364));
        var s2 = x2.lanewise(VectorOperators.MUL, I256_15893).lanewise(VectorOperators.ADD, x3
                .lanewise(VectorOperators.MUL, I256_3981));
        var s3 = x2.lanewise(VectorOperators.MUL, I256_3981).lanewise(VectorOperators.SUB, x3
                .lanewise(VectorOperators.MUL, I256_15893));
        var s4 = x4.lanewise(VectorOperators.MUL, I256_14811).lanewise(VectorOperators.ADD, x5
                .lanewise(VectorOperators.MUL, I256_7005));
        var s5 = x4.lanewise(VectorOperators.MUL, I256_7005).lanewise(VectorOperators.SUB, x5
                .lanewise(VectorOperators.MUL, I256_14811));
        var s6 = x6.lanewise(VectorOperators.MUL, I256_13160).lanewise(VectorOperators.ADD, x7
                .lanewise(VectorOperators.MUL, I256_9760));
        var s7 = x6.lanewise(VectorOperators.MUL, I256_9760).lanewise(VectorOperators.SUB, x7
                .lanewise(VectorOperators.MUL, I256_13160));
        var s8 = x8.lanewise(VectorOperators.MUL, I256_11003).lanewise(VectorOperators.ADD, x9
                .lanewise(VectorOperators.MUL, I256_12140));
        var s9 = x8.lanewise(VectorOperators.MUL, I256_12140).lanewise(VectorOperators.SUB, x9
                .lanewise(VectorOperators.MUL, I256_11003));
        var s10 = x10.lanewise(VectorOperators.MUL, I256_8423).lanewise(VectorOperators.ADD, x11
                .lanewise(VectorOperators.MUL, I256_14053));
        var s11 = x10.lanewise(VectorOperators.MUL, I256_14053).lanewise(VectorOperators.SUB, x11
                .lanewise(VectorOperators.MUL, I256_8423));
        var s12 = x12.lanewise(VectorOperators.MUL, I256_5520).lanewise(VectorOperators.ADD, x13
                .lanewise(VectorOperators.MUL, I256_15426));
        var s13 = x12.lanewise(VectorOperators.MUL, I256_15426).lanewise(VectorOperators.SUB, x13
                .lanewise(VectorOperators.MUL, I256_5520));
        var s14 = x14.lanewise(VectorOperators.MUL, I256_2404).lanewise(VectorOperators.ADD, x15
                .lanewise(VectorOperators.MUL, I256_16207));
        var s15 = x14.lanewise(VectorOperators.MUL, I256_16207).lanewise(VectorOperators.SUB, x15
                .lanewise(VectorOperators.MUL, I256_2404));
        x0 = s0.lanewise(VectorOperators.ADD, s8).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x1 = s1.lanewise(VectorOperators.ADD, s9).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x2 = s2.lanewise(VectorOperators.ADD, s10).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x3 = s3.lanewise(VectorOperators.ADD, s11).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        INT256.cast(x0).intoArray(s, slotBase + 0);
        INT256.cast(x1).intoArray(s, slotBase + 8);
        INT256.cast(x2).intoArray(s, slotBase + 16);
        INT256.cast(x3).intoArray(s, slotBase + 24);
        INT256.cast(s0).intoArray(s, slotBase + 32);
        INT256.cast(s1).intoArray(s, slotBase + 40);
        INT256.cast(s2).intoArray(s, slotBase + 48);
        INT256.cast(s3).intoArray(s, slotBase + 56);
        INT256.cast(s4).intoArray(s, slotBase + 64);
        INT256.cast(s5).intoArray(s, slotBase + 72);
        INT256.cast(s6).intoArray(s, slotBase + 80);
        INT256.cast(s7).intoArray(s, slotBase + 88);
        INT256.cast(s8).intoArray(s, slotBase + 96);
        INT256.cast(s9).intoArray(s, slotBase + 104);
        INT256.cast(s10).intoArray(s, slotBase + 112);
        INT256.cast(s11).intoArray(s, slotBase + 120);
        INT256.cast(s12).intoArray(s, slotBase + 128);
        INT256.cast(s13).intoArray(s, slotBase + 136);
        INT256.cast(s14).intoArray(s, slotBase + 144);
        INT256.cast(s15).intoArray(s, slotBase + 152);
    }

    /**
     * Computes the 16-point inverse ADST of 8 rows or 8 columns of a block at once, one per
     * lane, stage 2 of 4.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void iadst16Stage1(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var s12 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 128));
        var s4 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 64));
        var s13 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 136));
        var s5 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 72));
        var s14 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 144));
        var s6 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 80));
        var s15 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 152));
        var s7 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 88));
        var s0 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 32));
        var s8 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 96));
        var s1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 40));
        var s9 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 104));
        var s10 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 112));
        var s2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 48));
        var s11 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 120));
        var s3 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 56));
        var x0 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 0));
        var x1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 8));
        var x2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 16));
        var x3 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 24));
        var x4 = s4.lanewise(VectorOperators.ADD, s12).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x5 = s5.lanewise(VectorOperators.ADD, s13).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x6 = s6.lanewise(VectorOperators.ADD, s14).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x7 = s7.lanewise(VectorOperators.ADD, s15).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x8 = s0.lanewise(VectorOperators.SUB, s8).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x9 = s1.lanewise(VectorOperators.SUB, s9).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x10 = s2.lanewise(VectorOperators.SUB, s10).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x11 = s3.lanewise(VectorOperators.SUB, s11).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x12 = s4.lanewise(VectorOperators.SUB, s12).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x13 = s5.lanewise(VectorOperators.SUB, s13).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x14 = s6.lanewise(VectorOperators.SUB, s14).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x15 = s7.lanewise(VectorOperators.SUB, s15).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        s0 = INT256.cast(x0);
        s1 = INT256.cast(x1);
        s2 = INT256.cast(x2);
        s3 = INT256.cast(x3);
        s4 = INT256.cast(x4);
        s5 = INT256.cast(x5);
        s6 = INT256.cast(x6);
        s7 = INT256.cast(x7);
        s8 = x8.lanewise(VectorOperators.MUL, I256_16069).lanewise(VectorOperators.ADD, x9
                .lanewise(VectorOperators.MUL, I256_3196));
        s9 = x8.lanewise(VectorOperators.MUL, I256_3196).lanewise(VectorOperators.SUB, x9
                .lanewise(VectorOperators.MUL, I256_16069));
        s10 = x10.lanewise(VectorOperators.MUL, I256_9102).lanewise(VectorOperators.ADD, x11
                .lanewise(VectorOperators.MUL, I256_13623));
        s11 = x10.lanewise(VectorOperators.MUL, I256_13623).lanewise(VectorOperators.SUB, x11
                .lanewise(VectorOperators.MUL, I256_9102));
        s12 = x12.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_3196)
                .lanewise(VectorOperators.ADD, x13.lanewise(VectorOperators.MUL, I256_16069));
        s13 = x12.lanewise(VectorOperators.MUL, I256_16069).lanewise(VectorOperators.ADD, x13
                .lanewise(VectorOperators.MUL, I256_3196));
        s14 = x14.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_13623)
                .lanewise(VectorOperators.ADD, x15.lanewise(VectorOperators.MUL, I256_9102));
        s15 = x14.lanewise(VectorOperators.MUL, I256_9102).lanewise(VectorOperators.ADD, x15
                .lanewise(VectorOperators.MUL, I256_13623));
        x0 = s0.lanewise(VectorOperators.ADD, s4);
        x1 = s1.lanewise(VectorOperators.ADD, s5);
        x2 = s2.lanewise(VectorOperators.ADD, s6);
        INT256.cast(s0).intoArray(s, slotBase + 32);
        INT256.cast(s1).intoArray(s, slotBase + 40);
        INT256.cast(s2).intoArray(s, slotBase + 48);
        INT256.cast(s3).intoArray(s, slotBase + 56);
        INT256.cast(s4).intoArray(s, slotBase + 64);
        INT256.cast(s5).intoArray(s, slotBase + 72);
        INT256.cast(s6).intoArray(s, slotBase + 80);
        INT256.cast(s7).intoArray(s, slotBase + 88);
        INT256.cast(s8).intoArray(s, slotBase + 96);
        INT256.cast(s9).intoArray(s, slotBase + 104);
        INT256.cast(s10).intoArray(s, slotBase + 112);
        INT256.cast(s11).intoArray(s, slotBase + 120);
        INT256.cast(s12).intoArray(s, slotBase + 128);
        INT256.cast(s13).intoArray(s, slotBase + 136);
        INT256.cast(s14).intoArray(s, slotBase + 144);
        INT256.cast(s15).intoArray(s, slotBase + 152);
        INT256.cast(x0).intoArray(s, slotBase + 0);
        INT256.cast(x1).intoArray(s, slotBase + 8);
        INT256.cast(x2).intoArray(s, slotBase + 16);
    }

    /**
     * Computes the 16-point inverse ADST of 8 rows or 8 columns of a block at once, one per
     * lane, stage 3 of 4.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void iadst16Stage2(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var s3 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 56));
        var s7 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 88));
        var s0 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 32));
        var s4 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 64));
        var s1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 40));
        var s5 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 72));
        var s2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 48));
        var s6 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 80));
        var s12 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 128));
        var s8 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 96));
        var s13 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 136));
        var s9 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 104));
        var s10 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 112));
        var s14 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 144));
        var s11 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 120));
        var s15 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 152));
        var x0 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 0));
        var x1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 8));
        var x2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 16));
        var x3 = s3.lanewise(VectorOperators.ADD, s7);
        var x4 = s0.lanewise(VectorOperators.SUB, s4);
        var x5 = s1.lanewise(VectorOperators.SUB, s5);
        var x6 = s2.lanewise(VectorOperators.SUB, s6);
        var x7 = s3.lanewise(VectorOperators.SUB, s7);
        var x8 = s8.lanewise(VectorOperators.ADD, s12).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x9 = s9.lanewise(VectorOperators.ADD, s13).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x10 = s10.lanewise(VectorOperators.ADD, s14).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x11 = s11.lanewise(VectorOperators.ADD, s15).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x12 = s8.lanewise(VectorOperators.SUB, s12).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x13 = s9.lanewise(VectorOperators.SUB, s13).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x14 = s10.lanewise(VectorOperators.SUB, s14).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x15 = s11.lanewise(VectorOperators.SUB, s15).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        s0 = INT256.cast(x0);
        s1 = INT256.cast(x1);
        s2 = INT256.cast(x2);
        s3 = INT256.cast(x3);
        s4 = x4.lanewise(VectorOperators.MUL, I256_15137).lanewise(VectorOperators.ADD, x5
                .lanewise(VectorOperators.MUL, I256_6270));
        s5 = x4.lanewise(VectorOperators.MUL, I256_6270).lanewise(VectorOperators.SUB, x5
                .lanewise(VectorOperators.MUL, I256_15137));
        s6 = x6.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_6270).lanewise(VectorOperators.ADD, x7
                .lanewise(VectorOperators.MUL, I256_15137));
        s7 = x6.lanewise(VectorOperators.MUL, I256_15137).lanewise(VectorOperators.ADD, x7
                .lanewise(VectorOperators.MUL, I256_6270));
        s8 = INT256.cast(x8);
        s9 = INT256.cast(x9);
        s10 = INT256.cast(x10);
        s11 = INT256.cast(x11);
        s12 = x12.lanewise(VectorOperators.MUL, I256_15137).lanewise(VectorOperators.ADD, x13
                .lanewise(VectorOperators.MUL, I256_6270));
        s13 = x12.lanewise(VectorOperators.MUL, I256_6270).lanewise(VectorOperators.SUB, x13
                .lanewise(VectorOperators.MUL, I256_15137));
        s14 = x14.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_6270)
                .lanewise(VectorOperators.ADD, x15.lanewise(VectorOperators.MUL, I256_15137));
        s15 = x14.lanewise(VectorOperators.MUL, I256_15137).lanewise(VectorOperators.ADD, x15
                .lanewise(VectorOperators.MUL, I256_6270));
        x0 = s0.lanewise(VectorOperators.ADD, s2);
        x1 = s1.lanewise(VectorOperators.ADD, s3);
        x2 = s0.lanewise(VectorOperators.SUB, s2);
        x3 = s1.lanewise(VectorOperators.SUB, s3);
        x4 = s4.lanewise(VectorOperators.ADD, s6).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x5 = s5.lanewise(VectorOperators.ADD, s7).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x6 = s4.lanewise(VectorOperators.SUB, s6).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        x7 = s5.lanewise(VectorOperators.SUB, s7).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        INT256.cast(x3).intoArray(s, slotBase + 24);
        INT256.cast(x4).intoArray(s, slotBase + 160);
        INT256.cast(x5).intoArray(s, slotBase + 168);
        INT256.cast(x6).intoArray(s, slotBase + 176);
        INT256.cast(x7).intoArray(s, slotBase + 184);
        INT256.cast(s8).intoArray(s, slotBase + 96);
        INT256.cast(s9).intoArray(s, slotBase + 104);
        INT256.cast(s10).intoArray(s, slotBase + 112);
        INT256.cast(s11).intoArray(s, slotBase + 120);
        INT256.cast(s12).intoArray(s, slotBase + 128);
        INT256.cast(s13).intoArray(s, slotBase + 136);
        INT256.cast(s14).intoArray(s, slotBase + 144);
        INT256.cast(s15).intoArray(s, slotBase + 152);
        INT256.cast(x0).intoArray(s, slotBase + 0);
        INT256.cast(x1).intoArray(s, slotBase + 8);
        INT256.cast(x2).intoArray(s, slotBase + 16);
    }

    /**
     * Computes the 16-point inverse ADST of 8 rows or 8 columns of a block at once, one per
     * lane, stage 4 of 4.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void iadst16Stage3(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var s10 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 112));
        var s8 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 96));
        var s11 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 120));
        var s9 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 104));
        var s12 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 128));
        var s14 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 144));
        var s13 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 136));
        var s15 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 152));
        var x2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 16));
        var x3 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 24));
        var x6 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 176));
        var x7 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 184));
        var x0 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 0));
        var x4 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 160));
        var x5 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 168));
        var x1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 8));
        var x8 = s8.lanewise(VectorOperators.ADD, s10);
        var x9 = s9.lanewise(VectorOperators.ADD, s11);
        var x10 = s8.lanewise(VectorOperators.SUB, s10);
        var x11 = s9.lanewise(VectorOperators.SUB, s11);
        var x12 = s12.lanewise(VectorOperators.ADD, s14).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x13 = s13.lanewise(VectorOperators.ADD, s15).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x14 = s12.lanewise(VectorOperators.SUB, s14).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var x15 = s13.lanewise(VectorOperators.SUB, s15).lanewise(VectorOperators.ADD, I256_8192)
                .lanewise(VectorOperators.ASHR, 14);
        var s2 = x2.lanewise(VectorOperators.ADD, x3).lanewise(VectorOperators.MUL, I256_M11585);
        var s3 = x2.lanewise(VectorOperators.SUB, x3).lanewise(VectorOperators.MUL, I256_11585);
        var s6 = x6.lanewise(VectorOperators.ADD, x7).lanewise(VectorOperators.MUL, I256_11585);
        var s7 = x6.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, x7)
                .lanewise(VectorOperators.MUL, I256_11585);
        s10 = x10.lanewise(VectorOperators.ADD, x11).lanewise(VectorOperators.MUL, I256_11585);
        s11 = x10.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, x11)
                .lanewise(VectorOperators.MUL, I256_11585);
        s14 = x14.lanewise(VectorOperators.ADD, x15).lanewise(VectorOperators.MUL, I256_M11585);
        s15 = x14.lanewise(VectorOperators.SUB, x15).lanewise(VectorOperators.MUL, I256_11585);
        x2 = s2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        x3 = s3.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        x6 = s6.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        x7 = s7.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        x10 = s10.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        x11 = s11.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        x14 = s14.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        x15 = s15.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        INT256.cast(INT256.cast(x0)).intoArray(s, tOff);
        INT256.cast(x8.lanewise(VectorOperators.NEG)).intoArray(s, tOff + 1 * tStride);
        INT256.cast(INT256.cast(x12)).intoArray(s, tOff + 2 * tStride);
        INT256.cast(x4.lanewise(VectorOperators.NEG)).intoArray(s, tOff + 3 * tStride);
        INT256.cast(INT256.cast(x6)).intoArray(s, tOff + 4 * tStride);
        INT256.cast(INT256.cast(x14)).intoArray(s, tOff + 5 * tStride);
        INT256.cast(INT256.cast(x10)).intoArray(s, tOff + 6 * tStride);
        INT256.cast(INT256.cast(x2)).intoArray(s, tOff + 7 * tStride);
        INT256.cast(INT256.cast(x3)).intoArray(s, tOff + 8 * tStride);
        INT256.cast(INT256.cast(x11)).intoArray(s, tOff + 9 * tStride);
        INT256.cast(INT256.cast(x15)).intoArray(s, tOff + 10 * tStride);
        INT256.cast(INT256.cast(x7)).intoArray(s, tOff + 11 * tStride);
        INT256.cast(INT256.cast(x5)).intoArray(s, tOff + 12 * tStride);
        INT256.cast(x13.lanewise(VectorOperators.NEG)).intoArray(s, tOff + 13 * tStride);
        INT256.cast(INT256.cast(x9)).intoArray(s, tOff + 14 * tStride);
        INT256.cast(x1.lanewise(VectorOperators.NEG)).intoArray(s, tOff + 15 * tStride);
    }

    /**
     * Computes the 16-point inverse ADST of 8 rows or 8 columns of a block at once, running its
     * stages in order.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void iadst16(int[] in, int inOff, int[] s, int tOff, int tStride) {
        iadst16Stage0(in, inOff, s, tOff, tStride);
        iadst16Stage1(in, inOff, s, tOff, tStride);
        iadst16Stage2(in, inOff, s, tOff, tStride);
        iadst16Stage3(in, inOff, s, tOff, tStride);
    }

    /**
     * Computes the 32-point inverse DCT of 8 rows or 8 columns of a block at once, one per
     * lane, stage 1 of 10.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct32Stage0(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var in0 = INT256.cast(IntVector.fromArray(I256, in, inBase + 0));
        var in16 = INT256.cast(IntVector.fromArray(I256, in, inBase + 128));
        var in8 = INT256.cast(IntVector.fromArray(I256, in, inBase + 64));
        var in24 = INT256.cast(IntVector.fromArray(I256, in, inBase + 192));
        var in4 = INT256.cast(IntVector.fromArray(I256, in, inBase + 32));
        var in20 = INT256.cast(IntVector.fromArray(I256, in, inBase + 160));
        var in12 = INT256.cast(IntVector.fromArray(I256, in, inBase + 96));
        var in28 = INT256.cast(IntVector.fromArray(I256, in, inBase + 224));
        var in2 = INT256.cast(IntVector.fromArray(I256, in, inBase + 16));
        var in18 = INT256.cast(IntVector.fromArray(I256, in, inBase + 144));
        var in10 = INT256.cast(IntVector.fromArray(I256, in, inBase + 80));
        var in26 = INT256.cast(IntVector.fromArray(I256, in, inBase + 208));
        var in6 = INT256.cast(IntVector.fromArray(I256, in, inBase + 48));
        var in22 = INT256.cast(IntVector.fromArray(I256, in, inBase + 176));
        var in14 = INT256.cast(IntVector.fromArray(I256, in, inBase + 112));
        var in30 = INT256.cast(IntVector.fromArray(I256, in, inBase + 240));
        var in1 = INT256.cast(IntVector.fromArray(I256, in, inBase + 8));
        var in31 = INT256.cast(IntVector.fromArray(I256, in, inBase + 248));
        var in15 = INT256.cast(IntVector.fromArray(I256, in, inBase + 120));
        var in17 = INT256.cast(IntVector.fromArray(I256, in, inBase + 136));
        var in23 = INT256.cast(IntVector.fromArray(I256, in, inBase + 184));
        var in9 = INT256.cast(IntVector.fromArray(I256, in, inBase + 72));
        var in25 = INT256.cast(IntVector.fromArray(I256, in, inBase + 200));
        var in7 = INT256.cast(IntVector.fromArray(I256, in, inBase + 56));
        var in27 = INT256.cast(IntVector.fromArray(I256, in, inBase + 216));
        var in5 = INT256.cast(IntVector.fromArray(I256, in, inBase + 40));
        var step1_0 = INT256.cast(in0);
        var step1_1 = INT256.cast(in16);
        var step1_2 = INT256.cast(in8);
        var step1_3 = INT256.cast(in24);
        var step1_4 = INT256.cast(in4);
        var step1_5 = INT256.cast(in20);
        var step1_6 = INT256.cast(in12);
        var step1_7 = INT256.cast(in28);
        var step1_8 = INT256.cast(in2);
        var step1_9 = INT256.cast(in18);
        var step1_10 = INT256.cast(in10);
        var step1_11 = INT256.cast(in26);
        var step1_12 = INT256.cast(in6);
        var step1_13 = INT256.cast(in22);
        var step1_14 = INT256.cast(in14);
        var step1_15 = INT256.cast(in30);
        var temp1 = in1.lanewise(VectorOperators.MUL, I256_804).lanewise(VectorOperators.SUB, in31
                .lanewise(VectorOperators.MUL, I256_16364));
        var temp2 = in1.lanewise(VectorOperators.MUL, I256_16364).lanewise(VectorOperators.ADD, in31
                .lanewise(VectorOperators.MUL, I256_804));
        var step1_16 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_31 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = in17.lanewise(VectorOperators.MUL, I256_12140).lanewise(VectorOperators.SUB, in15
                .lanewise(VectorOperators.MUL, I256_11003));
        temp2 = in17.lanewise(VectorOperators.MUL, I256_11003).lanewise(VectorOperators.ADD, in15
                .lanewise(VectorOperators.MUL, I256_12140));
        var step1_17 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_30 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = in9.lanewise(VectorOperators.MUL, I256_7005).lanewise(VectorOperators.SUB, in23
                .lanewise(VectorOperators.MUL, I256_14811));
        temp2 = in9.lanewise(VectorOperators.MUL, I256_14811).lanewise(VectorOperators.ADD, in23
                .lanewise(VectorOperators.MUL, I256_7005));
        var step1_18 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_29 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = in25.lanewise(VectorOperators.MUL, I256_15426).lanewise(VectorOperators.SUB, in7
                .lanewise(VectorOperators.MUL, I256_5520));
        temp2 = in25.lanewise(VectorOperators.MUL, I256_5520).lanewise(VectorOperators.ADD, in7
                .lanewise(VectorOperators.MUL, I256_15426));
        var step1_19 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_28 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = in5.lanewise(VectorOperators.MUL, I256_3981).lanewise(VectorOperators.SUB, in27
                .lanewise(VectorOperators.MUL, I256_15893));
        INT256.cast(step1_0).intoArray(s, slotBase + 0);
        INT256.cast(step1_1).intoArray(s, slotBase + 8);
        INT256.cast(step1_2).intoArray(s, slotBase + 16);
        INT256.cast(step1_3).intoArray(s, slotBase + 24);
        INT256.cast(step1_4).intoArray(s, slotBase + 32);
        INT256.cast(step1_5).intoArray(s, slotBase + 40);
        INT256.cast(step1_6).intoArray(s, slotBase + 48);
        INT256.cast(step1_7).intoArray(s, slotBase + 56);
        INT256.cast(step1_8).intoArray(s, slotBase + 64);
        INT256.cast(step1_9).intoArray(s, slotBase + 72);
        INT256.cast(step1_10).intoArray(s, slotBase + 80);
        INT256.cast(step1_11).intoArray(s, slotBase + 88);
        INT256.cast(step1_12).intoArray(s, slotBase + 96);
        INT256.cast(step1_13).intoArray(s, slotBase + 104);
        INT256.cast(step1_14).intoArray(s, slotBase + 112);
        INT256.cast(step1_15).intoArray(s, slotBase + 120);
        INT256.cast(temp1).intoArray(s, slotBase + 128);
        INT256.cast(step1_16).intoArray(s, slotBase + 136);
        INT256.cast(step1_31).intoArray(s, slotBase + 144);
        INT256.cast(step1_17).intoArray(s, slotBase + 152);
        INT256.cast(step1_30).intoArray(s, slotBase + 160);
        INT256.cast(step1_18).intoArray(s, slotBase + 168);
        INT256.cast(step1_29).intoArray(s, slotBase + 176);
        INT256.cast(step1_19).intoArray(s, slotBase + 184);
        INT256.cast(step1_28).intoArray(s, slotBase + 192);
    }

    /**
     * Computes the 32-point inverse DCT of 8 rows or 8 columns of a block at once, one per
     * lane, stage 2 of 10.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct32Stage1(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var in27 = INT256.cast(IntVector.fromArray(I256, in, inBase + 216));
        var in5 = INT256.cast(IntVector.fromArray(I256, in, inBase + 40));
        var temp1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 128));
        var in11 = INT256.cast(IntVector.fromArray(I256, in, inBase + 88));
        var in21 = INT256.cast(IntVector.fromArray(I256, in, inBase + 168));
        var in13 = INT256.cast(IntVector.fromArray(I256, in, inBase + 104));
        var in19 = INT256.cast(IntVector.fromArray(I256, in, inBase + 152));
        var in29 = INT256.cast(IntVector.fromArray(I256, in, inBase + 232));
        var in3 = INT256.cast(IntVector.fromArray(I256, in, inBase + 24));
        var step1_0 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 0));
        var step1_1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 8));
        var step1_2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 16));
        var step1_3 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 24));
        var step1_4 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 32));
        var step1_5 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 40));
        var step1_6 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 48));
        var step1_7 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 56));
        var step1_15 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 120));
        var step1_8 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 64));
        var step1_14 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 112));
        var step1_9 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 72));
        var step1_10 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 80));
        var step1_13 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 104));
        var temp2 = in5.lanewise(VectorOperators.MUL, I256_15893).lanewise(VectorOperators.ADD, in27
                .lanewise(VectorOperators.MUL, I256_3981));
        var step1_20 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_27 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = in21.lanewise(VectorOperators.MUL, I256_14053).lanewise(VectorOperators.SUB, in11
                .lanewise(VectorOperators.MUL, I256_8423));
        temp2 = in21.lanewise(VectorOperators.MUL, I256_8423).lanewise(VectorOperators.ADD, in11
                .lanewise(VectorOperators.MUL, I256_14053));
        var step1_21 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_26 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = in13.lanewise(VectorOperators.MUL, I256_9760).lanewise(VectorOperators.SUB, in19
                .lanewise(VectorOperators.MUL, I256_13160));
        temp2 = in13.lanewise(VectorOperators.MUL, I256_13160).lanewise(VectorOperators.ADD, in19
                .lanewise(VectorOperators.MUL, I256_9760));
        var step1_22 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_25 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = in29.lanewise(VectorOperators.MUL, I256_16207).lanewise(VectorOperators.SUB, in3
                .lanewise(VectorOperators.MUL, I256_2404));
        temp2 = in29.lanewise(VectorOperators.MUL, I256_2404).lanewise(VectorOperators.ADD, in3
                .lanewise(VectorOperators.MUL, I256_16207));
        var step1_23 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_24 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_0 = INT256.cast(step1_0);
        var step2_1 = INT256.cast(step1_1);
        var step2_2 = INT256.cast(step1_2);
        var step2_3 = INT256.cast(step1_3);
        var step2_4 = INT256.cast(step1_4);
        var step2_5 = INT256.cast(step1_5);
        var step2_6 = INT256.cast(step1_6);
        var step2_7 = INT256.cast(step1_7);
        temp1 = step1_8.lanewise(VectorOperators.MUL, I256_1606).lanewise(VectorOperators.SUB, step1_15
                .lanewise(VectorOperators.MUL, I256_16305));
        temp2 = step1_8.lanewise(VectorOperators.MUL, I256_16305).lanewise(VectorOperators.ADD, step1_15
                .lanewise(VectorOperators.MUL, I256_1606));
        var step2_8 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_15 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step1_9.lanewise(VectorOperators.MUL, I256_12665).lanewise(VectorOperators.SUB, step1_14
                .lanewise(VectorOperators.MUL, I256_10394));
        temp2 = step1_9.lanewise(VectorOperators.MUL, I256_10394).lanewise(VectorOperators.ADD, step1_14
                .lanewise(VectorOperators.MUL, I256_12665));
        var step2_9 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_14 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step1_10.lanewise(VectorOperators.MUL, I256_7723).lanewise(VectorOperators.SUB, step1_13
                .lanewise(VectorOperators.MUL, I256_14449));
        temp2 = step1_10.lanewise(VectorOperators.MUL, I256_14449).lanewise(VectorOperators.ADD, step1_13
                .lanewise(VectorOperators.MUL, I256_7723));
        var step2_10 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_13 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        INT256.cast(step1_20).intoArray(s, slotBase + 200);
        INT256.cast(step1_27).intoArray(s, slotBase + 208);
        INT256.cast(step1_21).intoArray(s, slotBase + 216);
        INT256.cast(step1_26).intoArray(s, slotBase + 224);
        INT256.cast(step1_22).intoArray(s, slotBase + 232);
        INT256.cast(step1_25).intoArray(s, slotBase + 240);
        INT256.cast(step1_23).intoArray(s, slotBase + 248);
        INT256.cast(step1_24).intoArray(s, slotBase + 256);
        INT256.cast(step2_0).intoArray(s, slotBase + 264);
        INT256.cast(step2_1).intoArray(s, slotBase + 272);
        INT256.cast(step2_2).intoArray(s, slotBase + 280);
        INT256.cast(step2_3).intoArray(s, slotBase + 288);
        INT256.cast(step2_4).intoArray(s, slotBase + 296);
        INT256.cast(step2_5).intoArray(s, slotBase + 304);
        INT256.cast(step2_6).intoArray(s, slotBase + 312);
        INT256.cast(step2_7).intoArray(s, slotBase + 320);
        INT256.cast(step2_8).intoArray(s, slotBase + 328);
        INT256.cast(step2_15).intoArray(s, slotBase + 336);
        INT256.cast(step2_9).intoArray(s, slotBase + 344);
        INT256.cast(step2_14).intoArray(s, slotBase + 352);
        INT256.cast(step2_10).intoArray(s, slotBase + 360);
        INT256.cast(step2_13).intoArray(s, slotBase + 368);
    }

    /**
     * Computes the 32-point inverse DCT of 8 rows or 8 columns of a block at once, one per
     * lane, stage 3 of 10.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct32Stage2(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var step1_11 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 88));
        var step1_12 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 96));
        var step1_16 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 136));
        var step1_17 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 152));
        var step1_18 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 168));
        var step1_19 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 184));
        var step1_20 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 200));
        var step1_21 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 216));
        var step1_22 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 232));
        var step1_23 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 248));
        var step1_24 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 256));
        var step1_25 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 240));
        var step1_26 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 224));
        var step1_27 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 208));
        var step1_28 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 192));
        var step1_29 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 176));
        var step1_30 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 160));
        var step1_31 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 144));
        var step2_0 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 264));
        var step2_1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 272));
        var step2_2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 280));
        var step2_3 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 288));
        var step2_4 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 296));
        var step2_7 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 320));
        var step2_5 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 304));
        var step2_6 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 312));
        var step2_8 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 328));
        var step2_9 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 344));
        var temp1 = step1_11.lanewise(VectorOperators.MUL, I256_15679).lanewise(VectorOperators.SUB, step1_12
                .lanewise(VectorOperators.MUL, I256_4756));
        var temp2 = step1_11.lanewise(VectorOperators.MUL, I256_4756).lanewise(VectorOperators.ADD, step1_12
                .lanewise(VectorOperators.MUL, I256_15679));
        var step2_11 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_12 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_16 = step1_16.lanewise(VectorOperators.ADD, step1_17);
        var step2_17 = step1_16.lanewise(VectorOperators.SUB, step1_17);
        var step2_18 = step1_18.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_19);
        var step2_19 = step1_18.lanewise(VectorOperators.ADD, step1_19);
        var step2_20 = step1_20.lanewise(VectorOperators.ADD, step1_21);
        var step2_21 = step1_20.lanewise(VectorOperators.SUB, step1_21);
        var step2_22 = step1_22.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_23);
        var step2_23 = step1_22.lanewise(VectorOperators.ADD, step1_23);
        var step2_24 = step1_24.lanewise(VectorOperators.ADD, step1_25);
        var step2_25 = step1_24.lanewise(VectorOperators.SUB, step1_25);
        var step2_26 = step1_26.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_27);
        var step2_27 = step1_26.lanewise(VectorOperators.ADD, step1_27);
        var step2_28 = step1_28.lanewise(VectorOperators.ADD, step1_29);
        var step2_29 = step1_28.lanewise(VectorOperators.SUB, step1_29);
        var step2_30 = step1_30.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_31);
        var step2_31 = step1_30.lanewise(VectorOperators.ADD, step1_31);
        var step1_0 = INT256.cast(step2_0);
        var step1_1 = INT256.cast(step2_1);
        var step1_2 = INT256.cast(step2_2);
        var step1_3 = INT256.cast(step2_3);
        temp1 = step2_4.lanewise(VectorOperators.MUL, I256_3196).lanewise(VectorOperators.SUB, step2_7
                .lanewise(VectorOperators.MUL, I256_16069));
        temp2 = step2_4.lanewise(VectorOperators.MUL, I256_16069).lanewise(VectorOperators.ADD, step2_7
                .lanewise(VectorOperators.MUL, I256_3196));
        var step1_4 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_7 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step2_5.lanewise(VectorOperators.MUL, I256_13623).lanewise(VectorOperators.SUB, step2_6
                .lanewise(VectorOperators.MUL, I256_9102));
        temp2 = step2_5.lanewise(VectorOperators.MUL, I256_9102).lanewise(VectorOperators.ADD, step2_6
                .lanewise(VectorOperators.MUL, I256_13623));
        var step1_5 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_6 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_8 = step2_8.lanewise(VectorOperators.ADD, step2_9);
        INT256.cast(step2_11).intoArray(s, slotBase + 376);
        INT256.cast(step2_12).intoArray(s, slotBase + 384);
        INT256.cast(step2_16).intoArray(s, slotBase + 392);
        INT256.cast(step2_17).intoArray(s, slotBase + 400);
        INT256.cast(step2_18).intoArray(s, slotBase + 408);
        INT256.cast(step2_19).intoArray(s, slotBase + 416);
        INT256.cast(step2_20).intoArray(s, slotBase + 424);
        INT256.cast(step2_21).intoArray(s, slotBase + 432);
        INT256.cast(step2_22).intoArray(s, slotBase + 440);
        INT256.cast(step2_23).intoArray(s, slotBase + 448);
        INT256.cast(step2_24).intoArray(s, slotBase + 456);
        INT256.cast(step2_25).intoArray(s, slotBase + 464);
        INT256.cast(step2_26).intoArray(s, slotBase + 472);
        INT256.cast(step2_27).intoArray(s, slotBase + 480);
        INT256.cast(step2_28).intoArray(s, slotBase + 488);
        INT256.cast(step2_29).intoArray(s, slotBase + 496);
        INT256.cast(step2_30).intoArray(s, slotBase + 504);
        INT256.cast(step2_31).intoArray(s, slotBase + 512);
        INT256.cast(step1_0).intoArray(s, slotBase + 0);
        INT256.cast(step1_1).intoArray(s, slotBase + 8);
        INT256.cast(step1_2).intoArray(s, slotBase + 16);
        INT256.cast(step1_3).intoArray(s, slotBase + 24);
        INT256.cast(step1_4).intoArray(s, slotBase + 32);
        INT256.cast(step1_7).intoArray(s, slotBase + 56);
        INT256.cast(step1_5).intoArray(s, slotBase + 40);
        INT256.cast(step1_6).intoArray(s, slotBase + 48);
        INT256.cast(step1_8).intoArray(s, slotBase + 64);
    }

    /**
     * Computes the 32-point inverse DCT of 8 rows or 8 columns of a block at once, one per
     * lane, stage 4 of 10.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct32Stage3(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var step2_8 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 328));
        var step2_9 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 344));
        var step2_10 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 360));
        var step2_11 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 376));
        var step2_12 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 384));
        var step2_13 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 368));
        var step2_14 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 352));
        var step2_15 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 336));
        var step2_16 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 392));
        var step2_31 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 512));
        var step2_17 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 400));
        var step2_30 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 504));
        var step2_18 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 408));
        var step2_29 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 496));
        var step2_19 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 416));
        var step2_20 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 424));
        var step2_21 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 432));
        var step2_26 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 472));
        var step2_22 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 440));
        var step2_25 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 464));
        var step2_23 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 448));
        var step2_24 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 456));
        var step2_27 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 480));
        var step2_28 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 488));
        var step1_0 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 0));
        var step1_1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 8));
        var step1_9 = step2_8.lanewise(VectorOperators.SUB, step2_9);
        var step1_10 = step2_10.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step2_11);
        var step1_11 = step2_10.lanewise(VectorOperators.ADD, step2_11);
        var step1_12 = step2_12.lanewise(VectorOperators.ADD, step2_13);
        var step1_13 = step2_12.lanewise(VectorOperators.SUB, step2_13);
        var step1_14 = step2_14.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step2_15);
        var step1_15 = step2_14.lanewise(VectorOperators.ADD, step2_15);
        var step1_16 = INT256.cast(step2_16);
        var step1_31 = INT256.cast(step2_31);
        var temp1 = step2_17.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_16069)
                .lanewise(VectorOperators.ADD, step2_30.lanewise(VectorOperators.MUL, I256_3196));
        var temp2 = step2_17.lanewise(VectorOperators.MUL, I256_3196).lanewise(VectorOperators.ADD, step2_30
                .lanewise(VectorOperators.MUL, I256_16069));
        var step1_17 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_30 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step2_18.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_3196)
                .lanewise(VectorOperators.SUB, step2_29.lanewise(VectorOperators.MUL, I256_16069));
        temp2 = step2_18.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_16069)
                .lanewise(VectorOperators.ADD, step2_29.lanewise(VectorOperators.MUL, I256_3196));
        var step1_18 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_29 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_19 = INT256.cast(step2_19);
        var step1_20 = INT256.cast(step2_20);
        temp1 = step2_21.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_9102)
                .lanewise(VectorOperators.ADD, step2_26.lanewise(VectorOperators.MUL, I256_13623));
        temp2 = step2_21.lanewise(VectorOperators.MUL, I256_13623).lanewise(VectorOperators.ADD, step2_26
                .lanewise(VectorOperators.MUL, I256_9102));
        var step1_21 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_26 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step2_22.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_13623)
                .lanewise(VectorOperators.SUB, step2_25.lanewise(VectorOperators.MUL, I256_9102));
        temp2 = step2_22.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_9102)
                .lanewise(VectorOperators.ADD, step2_25.lanewise(VectorOperators.MUL, I256_13623));
        var step1_22 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_25 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_23 = INT256.cast(step2_23);
        var step1_24 = INT256.cast(step2_24);
        var step1_27 = INT256.cast(step2_27);
        var step1_28 = INT256.cast(step2_28);
        temp1 = step1_0.lanewise(VectorOperators.ADD, step1_1).lanewise(VectorOperators.MUL, I256_11585);
        temp2 = step1_0.lanewise(VectorOperators.SUB, step1_1).lanewise(VectorOperators.MUL, I256_11585);
        var step2_0 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        INT256.cast(step1_9).intoArray(s, slotBase + 72);
        INT256.cast(step1_10).intoArray(s, slotBase + 80);
        INT256.cast(step1_11).intoArray(s, slotBase + 88);
        INT256.cast(step1_12).intoArray(s, slotBase + 96);
        INT256.cast(step1_13).intoArray(s, slotBase + 104);
        INT256.cast(step1_14).intoArray(s, slotBase + 112);
        INT256.cast(step1_15).intoArray(s, slotBase + 120);
        INT256.cast(step1_16).intoArray(s, slotBase + 136);
        INT256.cast(step1_31).intoArray(s, slotBase + 144);
        INT256.cast(temp2).intoArray(s, slotBase + 520);
        INT256.cast(step1_17).intoArray(s, slotBase + 152);
        INT256.cast(step1_30).intoArray(s, slotBase + 160);
        INT256.cast(step1_18).intoArray(s, slotBase + 168);
        INT256.cast(step1_29).intoArray(s, slotBase + 176);
        INT256.cast(step1_19).intoArray(s, slotBase + 184);
        INT256.cast(step1_20).intoArray(s, slotBase + 200);
        INT256.cast(step1_21).intoArray(s, slotBase + 216);
        INT256.cast(step1_26).intoArray(s, slotBase + 224);
        INT256.cast(step1_22).intoArray(s, slotBase + 232);
        INT256.cast(step1_25).intoArray(s, slotBase + 240);
        INT256.cast(step1_23).intoArray(s, slotBase + 248);
        INT256.cast(step1_24).intoArray(s, slotBase + 256);
        INT256.cast(step1_27).intoArray(s, slotBase + 208);
        INT256.cast(step1_28).intoArray(s, slotBase + 192);
        INT256.cast(step2_0).intoArray(s, slotBase + 264);
    }

    /**
     * Computes the 32-point inverse DCT of 8 rows or 8 columns of a block at once, one per
     * lane, stage 5 of 10.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct32Stage4(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var temp2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 520));
        var step1_2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 16));
        var step1_3 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 24));
        var step1_4 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 32));
        var step1_5 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 40));
        var step1_6 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 48));
        var step1_7 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 56));
        var step1_8 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 64));
        var step1_15 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 120));
        var step1_14 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 112));
        var step1_9 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 72));
        var step1_10 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 80));
        var step1_13 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 104));
        var step1_11 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 88));
        var step1_12 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 96));
        var step1_16 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 136));
        var step1_19 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 184));
        var step1_17 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 152));
        var step1_18 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 168));
        var step1_20 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 200));
        var step1_23 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 248));
        var step1_21 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 216));
        var step1_22 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 232));
        var step1_24 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 256));
        var step1_27 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 208));
        var step1_25 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 240));
        var step1_26 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 224));
        var step2_1 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var temp1 = step1_2.lanewise(VectorOperators.MUL, I256_6270).lanewise(VectorOperators.SUB, step1_3
                .lanewise(VectorOperators.MUL, I256_15137));
        temp2 = step1_2.lanewise(VectorOperators.MUL, I256_15137).lanewise(VectorOperators.ADD, step1_3
                .lanewise(VectorOperators.MUL, I256_6270));
        var step2_2 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_3 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_4 = step1_4.lanewise(VectorOperators.ADD, step1_5);
        var step2_5 = step1_4.lanewise(VectorOperators.SUB, step1_5);
        var step2_6 = step1_6.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_7);
        var step2_7 = step1_6.lanewise(VectorOperators.ADD, step1_7);
        var step2_8 = INT256.cast(step1_8);
        var step2_15 = INT256.cast(step1_15);
        temp1 = step1_9.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_15137)
                .lanewise(VectorOperators.ADD, step1_14.lanewise(VectorOperators.MUL, I256_6270));
        temp2 = step1_9.lanewise(VectorOperators.MUL, I256_6270).lanewise(VectorOperators.ADD, step1_14
                .lanewise(VectorOperators.MUL, I256_15137));
        var step2_9 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_14 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step1_10.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_6270)
                .lanewise(VectorOperators.SUB, step1_13.lanewise(VectorOperators.MUL, I256_15137));
        temp2 = step1_10.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_15137)
                .lanewise(VectorOperators.ADD, step1_13.lanewise(VectorOperators.MUL, I256_6270));
        var step2_10 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_13 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_11 = INT256.cast(step1_11);
        var step2_12 = INT256.cast(step1_12);
        var step2_16 = step1_16.lanewise(VectorOperators.ADD, step1_19);
        var step2_17 = step1_17.lanewise(VectorOperators.ADD, step1_18);
        var step2_18 = step1_17.lanewise(VectorOperators.SUB, step1_18);
        var step2_19 = step1_16.lanewise(VectorOperators.SUB, step1_19);
        var step2_20 = step1_20.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_23);
        var step2_21 = step1_21.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_22);
        var step2_22 = step1_21.lanewise(VectorOperators.ADD, step1_22);
        var step2_23 = step1_20.lanewise(VectorOperators.ADD, step1_23);
        var step2_24 = step1_24.lanewise(VectorOperators.ADD, step1_27);
        var step2_25 = step1_25.lanewise(VectorOperators.ADD, step1_26);
        var step2_26 = step1_25.lanewise(VectorOperators.SUB, step1_26);
        var step2_27 = step1_24.lanewise(VectorOperators.SUB, step1_27);
        INT256.cast(step2_1).intoArray(s, slotBase + 272);
        INT256.cast(step2_2).intoArray(s, slotBase + 280);
        INT256.cast(step2_3).intoArray(s, slotBase + 288);
        INT256.cast(step2_4).intoArray(s, slotBase + 296);
        INT256.cast(step2_5).intoArray(s, slotBase + 304);
        INT256.cast(step2_6).intoArray(s, slotBase + 312);
        INT256.cast(step2_7).intoArray(s, slotBase + 320);
        INT256.cast(step2_8).intoArray(s, slotBase + 328);
        INT256.cast(step2_15).intoArray(s, slotBase + 336);
        INT256.cast(step2_9).intoArray(s, slotBase + 344);
        INT256.cast(step2_14).intoArray(s, slotBase + 352);
        INT256.cast(step2_10).intoArray(s, slotBase + 360);
        INT256.cast(step2_13).intoArray(s, slotBase + 368);
        INT256.cast(step2_11).intoArray(s, slotBase + 376);
        INT256.cast(step2_12).intoArray(s, slotBase + 384);
        INT256.cast(step2_16).intoArray(s, slotBase + 392);
        INT256.cast(step2_17).intoArray(s, slotBase + 400);
        INT256.cast(step2_18).intoArray(s, slotBase + 408);
        INT256.cast(step2_19).intoArray(s, slotBase + 416);
        INT256.cast(step2_20).intoArray(s, slotBase + 424);
        INT256.cast(step2_21).intoArray(s, slotBase + 432);
        INT256.cast(step2_22).intoArray(s, slotBase + 440);
        INT256.cast(step2_23).intoArray(s, slotBase + 448);
        INT256.cast(step2_24).intoArray(s, slotBase + 456);
        INT256.cast(step2_25).intoArray(s, slotBase + 464);
        INT256.cast(step2_26).intoArray(s, slotBase + 472);
        INT256.cast(step2_27).intoArray(s, slotBase + 480);
    }

    /**
     * Computes the 32-point inverse DCT of 8 rows or 8 columns of a block at once, one per
     * lane, stage 6 of 10.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct32Stage5(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var step1_28 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 192));
        var step1_31 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 144));
        var step1_29 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 176));
        var step1_30 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 160));
        var step2_0 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 264));
        var step2_3 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 288));
        var step2_1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 272));
        var step2_2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 280));
        var step2_4 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 296));
        var step2_5 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 304));
        var step2_6 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 312));
        var step2_7 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 320));
        var step2_11 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 376));
        var step2_8 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 328));
        var step2_10 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 360));
        var step2_9 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 344));
        var step2_12 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 384));
        var step2_15 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 336));
        var step2_13 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 368));
        var step2_14 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 352));
        var step2_16 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 392));
        var step2_17 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 400));
        var step2_18 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 408));
        var step2_19 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 416));
        var step2_20 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 424));
        var step2_27 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 480));
        var step2_28 = step1_28.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_31);
        var step2_29 = step1_29.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_30);
        var step2_30 = step1_29.lanewise(VectorOperators.ADD, step1_30);
        var step2_31 = step1_28.lanewise(VectorOperators.ADD, step1_31);
        var step1_0 = step2_0.lanewise(VectorOperators.ADD, step2_3);
        var step1_1 = step2_1.lanewise(VectorOperators.ADD, step2_2);
        var step1_2 = step2_1.lanewise(VectorOperators.SUB, step2_2);
        var step1_3 = step2_0.lanewise(VectorOperators.SUB, step2_3);
        var step1_4 = INT256.cast(step2_4);
        var temp1 = step2_6.lanewise(VectorOperators.SUB, step2_5).lanewise(VectorOperators.MUL, I256_11585);
        var temp2 = step2_5.lanewise(VectorOperators.ADD, step2_6).lanewise(VectorOperators.MUL, I256_11585);
        var step1_5 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_6 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_7 = INT256.cast(step2_7);
        var step1_8 = step2_8.lanewise(VectorOperators.ADD, step2_11);
        var step1_9 = step2_9.lanewise(VectorOperators.ADD, step2_10);
        var step1_10 = step2_9.lanewise(VectorOperators.SUB, step2_10);
        var step1_11 = step2_8.lanewise(VectorOperators.SUB, step2_11);
        var step1_12 = step2_12.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step2_15);
        var step1_13 = step2_13.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step2_14);
        var step1_14 = step2_13.lanewise(VectorOperators.ADD, step2_14);
        var step1_15 = step2_12.lanewise(VectorOperators.ADD, step2_15);
        var step1_16 = INT256.cast(step2_16);
        var step1_17 = INT256.cast(step2_17);
        temp1 = step2_18.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_15137)
                .lanewise(VectorOperators.ADD, step2_29.lanewise(VectorOperators.MUL, I256_6270));
        temp2 = step2_18.lanewise(VectorOperators.MUL, I256_6270).lanewise(VectorOperators.ADD, step2_29
                .lanewise(VectorOperators.MUL, I256_15137));
        var step1_18 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        step1_29 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step2_19.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_15137)
                .lanewise(VectorOperators.ADD, step2_28.lanewise(VectorOperators.MUL, I256_6270));
        temp2 = step2_19.lanewise(VectorOperators.MUL, I256_6270).lanewise(VectorOperators.ADD, step2_28
                .lanewise(VectorOperators.MUL, I256_15137));
        var step1_19 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        step1_28 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step2_20.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_6270)
                .lanewise(VectorOperators.SUB, step2_27.lanewise(VectorOperators.MUL, I256_15137));
        temp2 = step2_20.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_15137)
                .lanewise(VectorOperators.ADD, step2_27.lanewise(VectorOperators.MUL, I256_6270));
        INT256.cast(step2_30).intoArray(s, slotBase + 504);
        INT256.cast(step2_31).intoArray(s, slotBase + 512);
        INT256.cast(step1_0).intoArray(s, slotBase + 0);
        INT256.cast(step1_1).intoArray(s, slotBase + 8);
        INT256.cast(step1_2).intoArray(s, slotBase + 16);
        INT256.cast(step1_3).intoArray(s, slotBase + 24);
        INT256.cast(step1_4).intoArray(s, slotBase + 32);
        INT256.cast(temp1).intoArray(s, slotBase + 128);
        INT256.cast(temp2).intoArray(s, slotBase + 520);
        INT256.cast(step1_5).intoArray(s, slotBase + 40);
        INT256.cast(step1_6).intoArray(s, slotBase + 48);
        INT256.cast(step1_7).intoArray(s, slotBase + 56);
        INT256.cast(step1_8).intoArray(s, slotBase + 64);
        INT256.cast(step1_9).intoArray(s, slotBase + 72);
        INT256.cast(step1_10).intoArray(s, slotBase + 80);
        INT256.cast(step1_11).intoArray(s, slotBase + 88);
        INT256.cast(step1_12).intoArray(s, slotBase + 96);
        INT256.cast(step1_13).intoArray(s, slotBase + 104);
        INT256.cast(step1_14).intoArray(s, slotBase + 112);
        INT256.cast(step1_15).intoArray(s, slotBase + 120);
        INT256.cast(step1_16).intoArray(s, slotBase + 136);
        INT256.cast(step1_17).intoArray(s, slotBase + 152);
        INT256.cast(step1_18).intoArray(s, slotBase + 168);
        INT256.cast(step1_29).intoArray(s, slotBase + 176);
        INT256.cast(step1_19).intoArray(s, slotBase + 184);
        INT256.cast(step1_28).intoArray(s, slotBase + 192);
    }

    /**
     * Computes the 32-point inverse DCT of 8 rows or 8 columns of a block at once, one per
     * lane, stage 7 of 10.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct32Stage6(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var temp1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 128));
        var temp2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 520));
        var step2_21 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 432));
        var step2_26 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 472));
        var step2_22 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 440));
        var step2_23 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 448));
        var step2_24 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 456));
        var step2_25 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 464));
        var step2_30 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 504));
        var step2_31 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 512));
        var step1_0 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 0));
        var step1_7 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 56));
        var step1_1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 8));
        var step1_6 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 48));
        var step1_2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 16));
        var step1_5 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 40));
        var step1_3 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 24));
        var step1_4 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 32));
        var step1_8 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 64));
        var step1_9 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 72));
        var step1_10 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 80));
        var step1_13 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 104));
        var step1_11 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 88));
        var step1_12 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 96));
        var step1_14 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 112));
        var step1_15 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 120));
        var step1_16 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 136));
        var step1_17 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 152));
        var step1_18 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 168));
        var step1_20 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_27 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step2_21.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_6270)
                .lanewise(VectorOperators.SUB, step2_26.lanewise(VectorOperators.MUL, I256_15137));
        temp2 = step2_21.lanewise(VectorOperators.NEG).lanewise(VectorOperators.MUL, I256_15137)
                .lanewise(VectorOperators.ADD, step2_26.lanewise(VectorOperators.MUL, I256_6270));
        var step1_21 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_26 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_22 = INT256.cast(step2_22);
        var step1_23 = INT256.cast(step2_23);
        var step1_24 = INT256.cast(step2_24);
        var step1_25 = INT256.cast(step2_25);
        var step1_30 = INT256.cast(step2_30);
        var step1_31 = INT256.cast(step2_31);
        var step2_0 = step1_0.lanewise(VectorOperators.ADD, step1_7);
        var step2_1 = step1_1.lanewise(VectorOperators.ADD, step1_6);
        var step2_2 = step1_2.lanewise(VectorOperators.ADD, step1_5);
        var step2_3 = step1_3.lanewise(VectorOperators.ADD, step1_4);
        var step2_4 = step1_3.lanewise(VectorOperators.SUB, step1_4);
        var step2_5 = step1_2.lanewise(VectorOperators.SUB, step1_5);
        var step2_6 = step1_1.lanewise(VectorOperators.SUB, step1_6);
        var step2_7 = step1_0.lanewise(VectorOperators.SUB, step1_7);
        var step2_8 = INT256.cast(step1_8);
        var step2_9 = INT256.cast(step1_9);
        temp1 = step1_10.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_13)
                .lanewise(VectorOperators.MUL, I256_11585);
        temp2 = step1_10.lanewise(VectorOperators.ADD, step1_13).lanewise(VectorOperators.MUL, I256_11585);
        var step2_10 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_13 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step1_11.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_12)
                .lanewise(VectorOperators.MUL, I256_11585);
        temp2 = step1_11.lanewise(VectorOperators.ADD, step1_12).lanewise(VectorOperators.MUL, I256_11585);
        var step2_11 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_12 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step2_14 = INT256.cast(step1_14);
        var step2_15 = INT256.cast(step1_15);
        var step2_16 = step1_16.lanewise(VectorOperators.ADD, step1_23);
        var step2_17 = step1_17.lanewise(VectorOperators.ADD, step1_22);
        var step2_18 = step1_18.lanewise(VectorOperators.ADD, step1_21);
        INT256.cast(step1_20).intoArray(s, slotBase + 200);
        INT256.cast(step1_27).intoArray(s, slotBase + 208);
        INT256.cast(step1_21).intoArray(s, slotBase + 216);
        INT256.cast(step1_26).intoArray(s, slotBase + 224);
        INT256.cast(step1_22).intoArray(s, slotBase + 232);
        INT256.cast(step1_23).intoArray(s, slotBase + 248);
        INT256.cast(step1_24).intoArray(s, slotBase + 256);
        INT256.cast(step1_25).intoArray(s, slotBase + 240);
        INT256.cast(step1_30).intoArray(s, slotBase + 160);
        INT256.cast(step1_31).intoArray(s, slotBase + 144);
        INT256.cast(step2_0).intoArray(s, slotBase + 264);
        INT256.cast(step2_1).intoArray(s, slotBase + 272);
        INT256.cast(step2_2).intoArray(s, slotBase + 280);
        INT256.cast(step2_3).intoArray(s, slotBase + 288);
        INT256.cast(step2_4).intoArray(s, slotBase + 296);
        INT256.cast(step2_5).intoArray(s, slotBase + 304);
        INT256.cast(step2_6).intoArray(s, slotBase + 312);
        INT256.cast(step2_7).intoArray(s, slotBase + 320);
        INT256.cast(step2_8).intoArray(s, slotBase + 328);
        INT256.cast(step2_9).intoArray(s, slotBase + 344);
        INT256.cast(step2_10).intoArray(s, slotBase + 360);
        INT256.cast(step2_13).intoArray(s, slotBase + 368);
        INT256.cast(step2_11).intoArray(s, slotBase + 376);
        INT256.cast(step2_12).intoArray(s, slotBase + 384);
        INT256.cast(step2_14).intoArray(s, slotBase + 352);
        INT256.cast(step2_15).intoArray(s, slotBase + 336);
        INT256.cast(step2_16).intoArray(s, slotBase + 392);
        INT256.cast(step2_17).intoArray(s, slotBase + 400);
        INT256.cast(step2_18).intoArray(s, slotBase + 408);
    }

    /**
     * Computes the 32-point inverse DCT of 8 rows or 8 columns of a block at once, one per
     * lane, stage 8 of 10.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct32Stage7(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var step1_19 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 184));
        var step1_20 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 200));
        var step1_18 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 168));
        var step1_21 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 216));
        var step1_17 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 152));
        var step1_22 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 232));
        var step1_16 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 136));
        var step1_23 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 248));
        var step1_24 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 256));
        var step1_31 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 144));
        var step1_25 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 240));
        var step1_30 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 160));
        var step1_26 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 224));
        var step1_29 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 176));
        var step1_27 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 208));
        var step1_28 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 192));
        var step2_0 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 264));
        var step2_15 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 336));
        var step2_1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 272));
        var step2_14 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 352));
        var step2_13 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 368));
        var step2_2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 280));
        var step2_12 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 384));
        var step2_3 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 288));
        var step2_11 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 376));
        var step2_4 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 296));
        var step2_10 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 360));
        var step2_5 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 304));
        var step2_6 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 312));
        var step2_9 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 344));
        var step2_7 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 320));
        var step2_8 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 328));
        var step2_16 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 392));
        var step2_17 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 400));
        var step2_19 = step1_19.lanewise(VectorOperators.ADD, step1_20);
        var step2_20 = step1_19.lanewise(VectorOperators.SUB, step1_20);
        var step2_21 = step1_18.lanewise(VectorOperators.SUB, step1_21);
        var step2_22 = step1_17.lanewise(VectorOperators.SUB, step1_22);
        var step2_23 = step1_16.lanewise(VectorOperators.SUB, step1_23);
        var step2_24 = step1_24.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_31);
        var step2_25 = step1_25.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_30);
        var step2_26 = step1_26.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_29);
        var step2_27 = step1_27.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step1_28);
        var step2_28 = step1_27.lanewise(VectorOperators.ADD, step1_28);
        var step2_29 = step1_26.lanewise(VectorOperators.ADD, step1_29);
        var step2_30 = step1_25.lanewise(VectorOperators.ADD, step1_30);
        var step2_31 = step1_24.lanewise(VectorOperators.ADD, step1_31);
        var step1_0 = step2_0.lanewise(VectorOperators.ADD, step2_15);
        var step1_1 = step2_1.lanewise(VectorOperators.ADD, step2_14);
        var step1_2 = step2_2.lanewise(VectorOperators.ADD, step2_13);
        var step1_3 = step2_3.lanewise(VectorOperators.ADD, step2_12);
        var step1_4 = step2_4.lanewise(VectorOperators.ADD, step2_11);
        var step1_5 = step2_5.lanewise(VectorOperators.ADD, step2_10);
        var step1_6 = step2_6.lanewise(VectorOperators.ADD, step2_9);
        var step1_7 = step2_7.lanewise(VectorOperators.ADD, step2_8);
        var step1_8 = step2_7.lanewise(VectorOperators.SUB, step2_8);
        var step1_9 = step2_6.lanewise(VectorOperators.SUB, step2_9);
        var step1_10 = step2_5.lanewise(VectorOperators.SUB, step2_10);
        var step1_11 = step2_4.lanewise(VectorOperators.SUB, step2_11);
        var step1_12 = step2_3.lanewise(VectorOperators.SUB, step2_12);
        var step1_13 = step2_2.lanewise(VectorOperators.SUB, step2_13);
        var step1_14 = step2_1.lanewise(VectorOperators.SUB, step2_14);
        var step1_15 = step2_0.lanewise(VectorOperators.SUB, step2_15);
        step1_16 = INT256.cast(step2_16);
        step1_17 = INT256.cast(step2_17);
        INT256.cast(step2_19).intoArray(s, slotBase + 416);
        INT256.cast(step2_20).intoArray(s, slotBase + 424);
        INT256.cast(step2_21).intoArray(s, slotBase + 432);
        INT256.cast(step2_22).intoArray(s, slotBase + 440);
        INT256.cast(step2_23).intoArray(s, slotBase + 448);
        INT256.cast(step2_24).intoArray(s, slotBase + 456);
        INT256.cast(step2_25).intoArray(s, slotBase + 464);
        INT256.cast(step2_26).intoArray(s, slotBase + 472);
        INT256.cast(step2_27).intoArray(s, slotBase + 480);
        INT256.cast(step2_28).intoArray(s, slotBase + 488);
        INT256.cast(step2_29).intoArray(s, slotBase + 496);
        INT256.cast(step2_30).intoArray(s, slotBase + 504);
        INT256.cast(step2_31).intoArray(s, slotBase + 512);
        INT256.cast(step1_0).intoArray(s, slotBase + 0);
        INT256.cast(step1_1).intoArray(s, slotBase + 8);
        INT256.cast(step1_2).intoArray(s, slotBase + 16);
        INT256.cast(step1_3).intoArray(s, slotBase + 24);
        INT256.cast(step1_4).intoArray(s, slotBase + 32);
        INT256.cast(step1_5).intoArray(s, slotBase + 40);
        INT256.cast(step1_6).intoArray(s, slotBase + 48);
        INT256.cast(step1_7).intoArray(s, slotBase + 56);
        INT256.cast(step1_8).intoArray(s, slotBase + 64);
        INT256.cast(step1_9).intoArray(s, slotBase + 72);
        INT256.cast(step1_10).intoArray(s, slotBase + 80);
        INT256.cast(step1_11).intoArray(s, slotBase + 88);
        INT256.cast(step1_12).intoArray(s, slotBase + 96);
        INT256.cast(step1_13).intoArray(s, slotBase + 104);
        INT256.cast(step1_14).intoArray(s, slotBase + 112);
        INT256.cast(step1_15).intoArray(s, slotBase + 120);
        INT256.cast(step1_16).intoArray(s, slotBase + 136);
        INT256.cast(step1_17).intoArray(s, slotBase + 152);
    }

    /**
     * Computes the 32-point inverse DCT of 8 rows or 8 columns of a block at once, one per
     * lane, stage 9 of 10.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct32Stage8(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var step2_18 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 408));
        var step2_19 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 416));
        var step2_20 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 424));
        var step2_27 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 480));
        var step2_21 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 432));
        var step2_26 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 472));
        var step2_22 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 440));
        var step2_25 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 464));
        var step2_23 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 448));
        var step2_24 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 456));
        var step2_28 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 488));
        var step2_29 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 496));
        var step2_30 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 504));
        var step2_31 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 512));
        var step1_0 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 0));
        var step1_1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 8));
        var step1_2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 16));
        var step1_3 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 24));
        var step1_4 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 32));
        var step1_5 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 40));
        var step1_6 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 48));
        var step1_7 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 56));
        var step1_8 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 64));
        var step1_9 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 72));
        var step1_10 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 80));
        var step1_18 = INT256.cast(step2_18);
        var step1_19 = INT256.cast(step2_19);
        var temp1 = step2_20.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step2_27)
                .lanewise(VectorOperators.MUL, I256_11585);
        var temp2 = step2_20.lanewise(VectorOperators.ADD, step2_27).lanewise(VectorOperators.MUL, I256_11585);
        var step1_20 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_27 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step2_21.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step2_26)
                .lanewise(VectorOperators.MUL, I256_11585);
        temp2 = step2_21.lanewise(VectorOperators.ADD, step2_26).lanewise(VectorOperators.MUL, I256_11585);
        var step1_21 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_26 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step2_22.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step2_25)
                .lanewise(VectorOperators.MUL, I256_11585);
        temp2 = step2_22.lanewise(VectorOperators.ADD, step2_25).lanewise(VectorOperators.MUL, I256_11585);
        var step1_22 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_25 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        temp1 = step2_23.lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, step2_24)
                .lanewise(VectorOperators.MUL, I256_11585);
        temp2 = step2_23.lanewise(VectorOperators.ADD, step2_24).lanewise(VectorOperators.MUL, I256_11585);
        var step1_23 = temp1.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_24 = temp2.lanewise(VectorOperators.ADD, I256_8192).lanewise(VectorOperators.ASHR, 14);
        var step1_28 = INT256.cast(step2_28);
        var step1_29 = INT256.cast(step2_29);
        var step1_30 = INT256.cast(step2_30);
        var step1_31 = INT256.cast(step2_31);
        INT256.cast(step1_0.lanewise(VectorOperators.ADD, step1_31)).intoArray(s, tOff);
        INT256.cast(step1_1.lanewise(VectorOperators.ADD, step1_30)).intoArray(s, tOff + 1 * tStride);
        INT256.cast(step1_2.lanewise(VectorOperators.ADD, step1_29)).intoArray(s, tOff + 2 * tStride);
        INT256.cast(step1_3.lanewise(VectorOperators.ADD, step1_28)).intoArray(s, tOff + 3 * tStride);
        INT256.cast(step1_4.lanewise(VectorOperators.ADD, step1_27)).intoArray(s, tOff + 4 * tStride);
        INT256.cast(step1_5.lanewise(VectorOperators.ADD, step1_26)).intoArray(s, tOff + 5 * tStride);
        INT256.cast(step1_6.lanewise(VectorOperators.ADD, step1_25)).intoArray(s, tOff + 6 * tStride);
        INT256.cast(step1_7.lanewise(VectorOperators.ADD, step1_24)).intoArray(s, tOff + 7 * tStride);
        INT256.cast(step1_8.lanewise(VectorOperators.ADD, step1_23)).intoArray(s, tOff + 8 * tStride);
        INT256.cast(step1_9.lanewise(VectorOperators.ADD, step1_22)).intoArray(s, tOff + 9 * tStride);
        INT256.cast(step1_10.lanewise(VectorOperators.ADD, step1_21)).intoArray(s, tOff + 10 * tStride);
        INT256.cast(step1_18).intoArray(s, slotBase + 168);
        INT256.cast(step1_19).intoArray(s, slotBase + 184);
        INT256.cast(step1_20).intoArray(s, slotBase + 200);
        INT256.cast(step1_27).intoArray(s, slotBase + 208);
        INT256.cast(step1_21).intoArray(s, slotBase + 216);
        INT256.cast(step1_26).intoArray(s, slotBase + 224);
        INT256.cast(step1_22).intoArray(s, slotBase + 232);
        INT256.cast(step1_25).intoArray(s, slotBase + 240);
        INT256.cast(step1_23).intoArray(s, slotBase + 248);
        INT256.cast(step1_24).intoArray(s, slotBase + 256);
        INT256.cast(step1_28).intoArray(s, slotBase + 192);
        INT256.cast(step1_29).intoArray(s, slotBase + 176);
        INT256.cast(step1_30).intoArray(s, slotBase + 160);
        INT256.cast(step1_31).intoArray(s, slotBase + 144);
    }

    /**
     * Computes the 32-point inverse DCT of 8 rows or 8 columns of a block at once, one per
     * lane, stage 10 of 10.
     *
     * <p>Element {@code k} of the 8 transforms is read at {@code inOff + 8 * k}, and output
     * {@code k} is written at {@code tOff + k * tStride}.
     *
     * <p>Values that later stages read are left in the stage slots of the scratch array.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct32Stage9(int[] in, int inOff, int[] s, int tOff, int tStride) {
        var inBase = inOff;
        var slotBase = SLOTS;
        var step1_11 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 88));
        var step1_20 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 200));
        var step1_12 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 96));
        var step1_19 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 184));
        var step1_13 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 104));
        var step1_18 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 168));
        var step1_14 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 112));
        var step1_17 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 152));
        var step1_15 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 120));
        var step1_16 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 136));
        var step1_10 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 80));
        var step1_21 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 216));
        var step1_22 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 232));
        var step1_9 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 72));
        var step1_23 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 248));
        var step1_8 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 64));
        var step1_24 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 256));
        var step1_7 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 56));
        var step1_25 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 240));
        var step1_6 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 48));
        var step1_26 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 224));
        var step1_5 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 40));
        var step1_27 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 208));
        var step1_4 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 32));
        var step1_28 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 192));
        var step1_3 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 24));
        var step1_2 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 16));
        var step1_29 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 176));
        var step1_1 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 8));
        var step1_30 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 160));
        var step1_0 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 0));
        var step1_31 = INT256.cast(IntVector.fromArray(I256, s, slotBase + 144));
        INT256.cast(step1_11.lanewise(VectorOperators.ADD, step1_20)).intoArray(s, tOff + 11 * tStride);
        INT256.cast(step1_12.lanewise(VectorOperators.ADD, step1_19)).intoArray(s, tOff + 12 * tStride);
        INT256.cast(step1_13.lanewise(VectorOperators.ADD, step1_18)).intoArray(s, tOff + 13 * tStride);
        INT256.cast(step1_14.lanewise(VectorOperators.ADD, step1_17)).intoArray(s, tOff + 14 * tStride);
        INT256.cast(step1_15.lanewise(VectorOperators.ADD, step1_16)).intoArray(s, tOff + 15 * tStride);
        INT256.cast(step1_15.lanewise(VectorOperators.SUB, step1_16)).intoArray(s, tOff + 16 * tStride);
        INT256.cast(step1_14.lanewise(VectorOperators.SUB, step1_17)).intoArray(s, tOff + 17 * tStride);
        INT256.cast(step1_13.lanewise(VectorOperators.SUB, step1_18)).intoArray(s, tOff + 18 * tStride);
        INT256.cast(step1_12.lanewise(VectorOperators.SUB, step1_19)).intoArray(s, tOff + 19 * tStride);
        INT256.cast(step1_11.lanewise(VectorOperators.SUB, step1_20)).intoArray(s, tOff + 20 * tStride);
        INT256.cast(step1_10.lanewise(VectorOperators.SUB, step1_21)).intoArray(s, tOff + 21 * tStride);
        INT256.cast(step1_9.lanewise(VectorOperators.SUB, step1_22)).intoArray(s, tOff + 22 * tStride);
        INT256.cast(step1_8.lanewise(VectorOperators.SUB, step1_23)).intoArray(s, tOff + 23 * tStride);
        INT256.cast(step1_7.lanewise(VectorOperators.SUB, step1_24)).intoArray(s, tOff + 24 * tStride);
        INT256.cast(step1_6.lanewise(VectorOperators.SUB, step1_25)).intoArray(s, tOff + 25 * tStride);
        INT256.cast(step1_5.lanewise(VectorOperators.SUB, step1_26)).intoArray(s, tOff + 26 * tStride);
        INT256.cast(step1_4.lanewise(VectorOperators.SUB, step1_27)).intoArray(s, tOff + 27 * tStride);
        INT256.cast(step1_3.lanewise(VectorOperators.SUB, step1_28)).intoArray(s, tOff + 28 * tStride);
        INT256.cast(step1_2.lanewise(VectorOperators.SUB, step1_29)).intoArray(s, tOff + 29 * tStride);
        INT256.cast(step1_1.lanewise(VectorOperators.SUB, step1_30)).intoArray(s, tOff + 30 * tStride);
        INT256.cast(step1_0.lanewise(VectorOperators.SUB, step1_31)).intoArray(s, tOff + 31 * tStride);
    }

    /**
     * Computes the 32-point inverse DCT of 8 rows or 8 columns of a block at once, running its
     * stages in order.
     *
     * @param in the array holding the input elements
     * @param inOff the index of the first input element
     * @param s the scratch array receiving the outputs and holding the stage slots
     * @param tOff the index in {@code s} of the first output
     * @param tStride the distance in {@code s} between consecutive outputs
     */
    private static void idct32(int[] in, int inOff, int[] s, int tOff, int tStride) {
        idct32Stage0(in, inOff, s, tOff, tStride);
        idct32Stage1(in, inOff, s, tOff, tStride);
        idct32Stage2(in, inOff, s, tOff, tStride);
        idct32Stage3(in, inOff, s, tOff, tStride);
        idct32Stage4(in, inOff, s, tOff, tStride);
        idct32Stage5(in, inOff, s, tOff, tStride);
        idct32Stage6(in, inOff, s, tOff, tStride);
        idct32Stage7(in, inOff, s, tOff, tStride);
        idct32Stage8(in, inOff, s, tOff, tStride);
        idct32Stage9(in, inOff, s, tOff, tStride);
    }

    @Override
    public void inverseWhtAdd(int eob, int[] coef, int coefOff, byte[] dst, int dstOff, int stride, int[] scratch) {
        scalar.inverseWhtAdd(eob, coef, coefOff, dst, dstOff, stride, scratch);
    }

    @Override
    public void convolve(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                         short[] kernels, int kernelBase, int subX, int xStep, int subY, int yStep,
                         int w, int h, boolean average, byte[] scratch) {
        if (xStep != 16 || yStep != 16) {
            scalar.convolve(src, srcOff, srcStride, dst, dstOff, dstStride, kernels, kernelBase, subX, xStep, subY,
                    yStep, w, h, average, scratch);
        } else if (subX == 0 && subY == 0) {
            copyOrAverage(src, srcOff, srcStride, dst, dstOff, dstStride, w, h, average);
        } else if (subY == 0) {
            horizontal(src, srcOff - 3, srcStride, dst, dstOff, dstStride, kernels, kernelBase + subX * 8, w, h, average);
        } else if (subX == 0) {
            vertical(src, srcOff - 3 * srcStride, srcStride, dst, dstOff, dstStride, kernels, kernelBase + subY * 8, w,
                    h, average);
        } else {
            twoDimensional(src, srcOff - 3 * srcStride - 3, srcStride, dst, dstOff, dstStride, kernels,
                    kernelBase + subX * 8, kernelBase + subY * 8, w, h, average, scratch);
        }
    }

    /**
     * Filters a block horizontally into the scratch array, as 16-bit values 64 to a row, and then
     * vertically into the destination, as {@code vpx_convolve8} does through its 8-bit
     * intermediate block.
     *
     * @param src the source plane
     * @param srcOff the index of the source sample 3 rows above and 3 columns left of the first
     * output sample
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param kx the index of the horizontal filter phase in {@code kernels}
     * @param ky the index of the vertical filter phase in {@code kernels}
     * @param w the block width: 4, 8 or a multiple of 16
     * @param h the block height, even
     * @param average whether to average into the destination
     * @param scratch the scratch array, at least {@code (h + 8) * 128} bytes
     */
    private static void twoDimensional(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                                       short[] kernels, int kx, int ky, int w, int h, boolean average,
                                       byte[] scratch) {
        if (w > 16) {
            horizontalWideToShorts(src, srcOff, srcStride, scratch, 0, 128, kernels, kx, w, h + 7);
            verticalWideFromShorts(scratch, 0, 128, dst, dstOff, dstStride, kernels, ky, w, h, average);
        } else if (w == 16) {
            horizontal16ToShorts(src, srcOff, srcStride, scratch, 0, 128, kernels, kx, h + 7);
            vertical16FromShorts(scratch, 0, 128, dst, dstOff, dstStride, kernels, ky, h, average);
        } else if (w == 8) {
            horizontal8ToShorts(src, srcOff, srcStride, scratch, 0, 128, kernels, kx, h + 7);
            vertical8FromShorts(scratch, 0, 128, dst, dstOff, dstStride, kernels, ky, h, average);
        } else {
            horizontal4ToShorts(src, srcOff, srcStride, scratch, 0, 128, kernels, kx, h + 7);
            vertical4FromShorts(scratch, 0, 128, dst, dstOff, dstStride, kernels, ky, h, average);
        }
    }

    /**
     * Filters a block horizontally with the kernel for its width.
     *
     * @param src the source plane
     * @param srcOff the index of the source sample 3 columns left of the first output sample
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param w the block width: 4, 8 or a multiple of 16
     * @param h the number of rows
     * @param average whether to average into the destination
     */
    private static void horizontal(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                                   short[] kernels, int k, int w, int h, boolean average) {
        if (w > 16) {
            horizontalWide(src, srcOff, srcStride, dst, dstOff, dstStride, kernels, k, w, h, average);
        } else if (w == 16) {
            horizontal16(src, srcOff, srcStride, dst, dstOff, dstStride, kernels, k, h, average);
        } else if (w == 8) {
            horizontal8(src, srcOff, srcStride, dst, dstOff, dstStride, kernels, k, h, average);
        } else {
            horizontal4(src, srcOff, srcStride, dst, dstOff, dstStride, kernels, k, h, average);
        }
    }

    /**
     * Filters a block vertically with the kernel for its width.
     *
     * @param src the source plane
     * @param srcOff the index of the source sample 3 rows above the first output sample
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param w the block width: 4, 8 or a multiple of 16
     * @param h the number of rows
     * @param average whether to average into the destination
     */
    private static void vertical(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                                 short[] kernels, int k, int w, int h, boolean average) {
        if (w > 16) {
            verticalWide(src, srcOff, srcStride, dst, dstOff, dstStride, kernels, k, w, h, average);
        } else if (w == 16) {
            vertical16(src, srcOff, srcStride, dst, dstOff, dstStride, kernels, k, h, average);
        } else if (w == 8) {
            vertical8(src, srcOff, srcStride, dst, dstOff, dstStride, kernels, k, h, average);
        } else {
            vertical4(src, srcOff, srcStride, dst, dstOff, dstStride, kernels, k, h, average);
        }
    }

    /**
     * Copies a block, or averages it into the destination rounding up ({@code vpx_convolve_copy},
     * {@code vpx_convolve_avg}).
     *
     * <p>The average of {@code a} and {@code b} is computed per byte as
     * {@code (a | b) - ((a ^ b) >>> 1)}, which equals {@code (a + b + 1) >> 1}.
     *
     * @param src the source plane
     * @param srcOff the index of the first source sample
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param w the block width: 4, 8, 16 or a multiple of 32
     * @param h the block height
     * @param average whether to average into the destination
     */
    private static void copyOrAverage(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                                      int w, int h, boolean average) {
        if (w >= 32) {
            for (var y = 0; y < h; y++) {
                var s = srcOff + y * srcStride;
                var d = dstOff + y * dstStride;
                for (var x = 0; x < w; x += 32) {
                    var a = BYTE256.cast(ByteVector.fromArray(B256, src, s + x));
                    if (average) {
                        var b = BYTE256.cast(ByteVector.fromArray(B256, dst, d + x));
                        BYTE256.cast(a.lanewise(VectorOperators.OR, b).lanewise(VectorOperators.SUB,
                                a.lanewise(VectorOperators.XOR, b).lanewise(VectorOperators.LSHR, 1))).intoArray(dst, d + x);
                    } else {
                        a.intoArray(dst, d + x);
                    }
                }
            }
        } else if (w == 16) {
            for (var y = 0; y < h; y++) {
                var s = srcOff + y * srcStride;
                var d = dstOff + y * dstStride;
                var a = BYTE128.cast(ByteVector.fromArray(B128, src, s));
                if (average) {
                    var b = BYTE128.cast(ByteVector.fromArray(B128, dst, d));
                    BYTE128.cast(a.lanewise(VectorOperators.OR, b).lanewise(VectorOperators.SUB,
                            a.lanewise(VectorOperators.XOR, b).lanewise(VectorOperators.LSHR, 1))).intoArray(dst, d);
                } else {
                    a.intoArray(dst, d);
                }
            }
        } else if (!average) {
            for (var y = 0; y < h; y++) {
                var s = srcOff + y * srcStride;
                var d = dstOff + y * dstStride;
                if (w == 8) {
                    LONG.set(dst, d, (long) LONG.get(src, s));
                } else {
                    INT.set(dst, d, (int) INT.get(src, s));
                }
            }
        } else {
            for (var y = 0; y < h; y++) {
                var s = srcOff + y * srcStride;
                var d = dstOff + y * dstStride;
                var a = BYTE64.cast(ByteVector.fromArray(B64, src, s));
                var b = BYTE64.cast(ByteVector.fromArray(B64, dst, d));
                var mean = BYTE64.cast(a.lanewise(VectorOperators.OR, b).lanewise(VectorOperators.SUB,
                        a.lanewise(VectorOperators.XOR, b).lanewise(VectorOperators.LSHR, 1)));
                if (w == 8) {
                    mean.intoArray(dst, d);
                } else {
                    INT.set(dst, d, INT64.cast(mean.reinterpretShape(I64, 0)).lane(0));
                }
            }
        }
    }

    /**
     * Filters a block at least 32 samples wide horizontally with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the samples of a row 16 at a time, two rows
     * per iteration; with an odd number of rows the last iteration computes the last row twice, so averaging requires
     * an even number.
     *
     * @param src the source plane
     * @param srcOff the index of the source sample 3 columns left of the first output sample
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param w the block width, a multiple of 16
     * @param h the number of rows
     * @param average whether to average into the destination, rounding up
     */
    private static void horizontalWide(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int w, int h, boolean average) {
        var k0 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 0]));
        var k1 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 1]));
        var k2 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 2]));
        var k3 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 3]));
        var k4 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 4]));
        var k5 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 5]));
        var k6 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 6]));
        var k7 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            for (var x = 0; x < w; x += 16) {
                var a0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k0);
                var a1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k1);
                var a2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 2))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k2);
                var a3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 3))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k3);
                var a4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 4))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k4);
                var a5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 5))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k5);
                var a6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 6))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k6);
                var a7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 7))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k7);
                var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                        .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                        .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                        .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S256_BIAS);
                var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                        .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
                if (average) {
                    BYTE128.cast(SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, dst, d + x))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                            .lanewise(VectorOperators.ADD, aValue).lanewise(VectorOperators.ADD, S256_1)
                            .lanewise(VectorOperators.LSHR, 1).convertShape(VectorOperators.S2B, B128, 0))
                            .intoArray(dst, d + x);
                } else {
                    BYTE128.cast(aValue.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d + x);
                }
                var b0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k0);
                var b1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k1);
                var b2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 2))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k2);
                var b3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 3))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k3);
                var b4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 4))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k4);
                var b5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 5))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k5);
                var b6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 6))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k6);
                var b7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 7))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k7);
                var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                        .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                        .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                        .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S256_BIAS);
                var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                        .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
                if (average) {
                    BYTE128.cast(SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, dst, d1 + x))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                            .lanewise(VectorOperators.ADD, bValue).lanewise(VectorOperators.ADD, S256_1)
                            .lanewise(VectorOperators.LSHR, 1).convertShape(VectorOperators.S2B, B128, 0))
                            .intoArray(dst, d1 + x);
                } else {
                    BYTE128.cast(bValue.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d1 + x);
                }
            }
        }
    }

    /**
     * Filters a block 16 samples wide horizontally with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the 16 samples of a row at once, two rows
     * per iteration; with an odd number of rows the last iteration computes the last row twice, so averaging requires
     * an even number.
     *
     * @param src the source plane
     * @param srcOff the index of the source sample 3 columns left of the first output sample
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param h the number of rows
     * @param average whether to average into the destination, rounding up
     */
    private static void horizontal16(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int h, boolean average) {
        var k0 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 0]));
        var k1 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 1]));
        var k2 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 2]));
        var k3 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 3]));
        var k4 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 4]));
        var k5 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 5]));
        var k6 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 6]));
        var k7 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            var a0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k0);
            var a1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k1);
            var a2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 2))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k2);
            var a3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 3))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k3);
            var a4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 4))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k4);
            var a5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 5))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k5);
            var a6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 6))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k6);
            var a7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 7))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k7);
            var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                    .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                    .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                    .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S256_BIAS);
            var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                    .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
            if (average) {
                BYTE128.cast(SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, dst, d))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.ADD, aValue)
                        .lanewise(VectorOperators.ADD, S256_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d);
            } else {
                BYTE128.cast(aValue.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d);
            }
            var b0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k0);
            var b1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k1);
            var b2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 2))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k2);
            var b3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 3))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k3);
            var b4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 4))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k4);
            var b5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 5))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k5);
            var b6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 6))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k6);
            var b7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 7))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k7);
            var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                    .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                    .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                    .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S256_BIAS);
            var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                    .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
            if (average) {
                BYTE128.cast(SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, dst, d1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.ADD, bValue)
                        .lanewise(VectorOperators.ADD, S256_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d1);
            } else {
                BYTE128.cast(bValue.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d1);
            }
        }
    }

    /**
     * Filters a block 8 samples wide horizontally with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the 8 samples of a row at once, two rows per
     * iteration; with an odd number of rows the last iteration computes the last row twice, so averaging requires an
     * even number.
     *
     * @param src the source plane
     * @param srcOff the index of the source sample 3 columns left of the first output sample
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param h the number of rows
     * @param average whether to average into the destination, rounding up
     */
    private static void horizontal8(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int h, boolean average) {
        var k0 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 0]));
        var k1 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 1]));
        var k2 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 2]));
        var k3 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 3]));
        var k4 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 4]));
        var k5 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 5]));
        var k6 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 6]));
        var k7 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            var a0 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k0);
            var a1 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k1);
            var a2 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 2))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k2);
            var a3 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 3))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k3);
            var a4 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 4))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k4);
            var a5 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 5))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k5);
            var a6 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 6))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k6);
            var a7 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 7))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k7);
            var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                    .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                    .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                    .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            if (average) {
                BYTE64.cast(SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.ADD, aValue)
                        .lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d);
            } else {
                BYTE64.cast(aValue.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d);
            }
            var b0 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k0);
            var b1 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k1);
            var b2 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 2))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k2);
            var b3 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 3))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k3);
            var b4 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 4))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k4);
            var b5 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 5))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k5);
            var b6 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 6))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k6);
            var b7 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 7))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k7);
            var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                    .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                    .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                    .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            if (average) {
                BYTE64.cast(SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.ADD, bValue)
                        .lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d1);
            } else {
                BYTE64.cast(bValue.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d1);
            }
        }
    }

    /**
     * Filters a block 4 samples wide horizontally with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the 4 samples of a row as the first half of
     * an 8-lane vector, two rows per iteration; with an odd number of rows the last iteration computes the last row
     * twice, so averaging requires an even number.
     *
     * @param src the source plane
     * @param srcOff the index of the source sample 3 columns left of the first output sample
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param h the number of rows
     * @param average whether to average into the destination, rounding up
     */
    private static void horizontal4(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int h, boolean average) {
        var k0 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 0]));
        var k1 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 1]));
        var k2 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 2]));
        var k3 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 3]));
        var k4 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 4]));
        var k5 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 5]));
        var k6 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 6]));
        var k7 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            var a0 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k0);
            var a1 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k1);
            var a2 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 2))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k2);
            var a3 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 3))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k3);
            var a4 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 4))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k4);
            var a5 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 5))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k5);
            var a6 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 6))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k6);
            var a7 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 7))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k7);
            var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                    .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                    .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                    .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            if (average) {
                INT.set(dst, d, INT64.cast(BYTE64.cast(SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.ADD, aValue)
                        .lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B64, 0)).reinterpretShape(I64, 0)).lane(0));
            } else {
                INT.set(dst, d, INT64.cast(BYTE64.cast(aValue.convertShape(VectorOperators.S2B, B64, 0))
                        .reinterpretShape(I64, 0)).lane(0));
            }
            var b0 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k0);
            var b1 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k1);
            var b2 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 2))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k2);
            var b3 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 3))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k3);
            var b4 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 4))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k4);
            var b5 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 5))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k5);
            var b6 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 6))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k6);
            var b7 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 7))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k7);
            var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                    .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                    .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                    .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            if (average) {
                INT.set(dst, d1, INT64.cast(BYTE64.cast(SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.ADD, bValue)
                        .lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B64, 0)).reinterpretShape(I64, 0)).lane(0));
            } else {
                INT.set(dst, d1, INT64.cast(BYTE64.cast(bValue.convertShape(VectorOperators.S2B, B64, 0))
                        .reinterpretShape(I64, 0)).lane(0));
            }
        }
    }

    /**
     * Filters a block at least 32 samples wide vertically with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the samples of a row 16 at a time, two rows
     * per iteration; with an odd number of rows the last iteration computes the last row twice, so averaging requires
     * an even number.
     *
     * @param src the source plane
     * @param srcOff the index of the source sample 3 rows above the first output sample
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param w the block width, a multiple of 16
     * @param h the number of rows
     * @param average whether to average into the destination, rounding up
     */
    private static void verticalWide(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int w, int h, boolean average) {
        var k0 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 0]));
        var k1 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 1]));
        var k2 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 2]));
        var k3 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 3]));
        var k4 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 4]));
        var k5 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 5]));
        var k6 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 6]));
        var k7 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            for (var x = 0; x < w; x += 16) {
                var a0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k0);
                var a1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 1 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k1);
                var a2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 2 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k2);
                var a3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 3 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k3);
                var a4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 4 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k4);
                var a5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 5 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k5);
                var a6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 6 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k6);
                var a7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 7 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k7);
                var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                        .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                        .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                        .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S256_BIAS);
                var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                        .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
                if (average) {
                    BYTE128.cast(SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, dst, d + x))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                            .lanewise(VectorOperators.ADD, aValue).lanewise(VectorOperators.ADD, S256_1)
                            .lanewise(VectorOperators.LSHR, 1).convertShape(VectorOperators.S2B, B128, 0))
                            .intoArray(dst, d + x);
                } else {
                    BYTE128.cast(aValue.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d + x);
                }
                var b0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k0);
                var b1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 1 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k1);
                var b2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 2 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k2);
                var b3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 3 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k3);
                var b4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 4 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k4);
                var b5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 5 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k5);
                var b6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 6 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k6);
                var b7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 7 * srcStride))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k7);
                var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                        .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                        .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                        .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S256_BIAS);
                var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                        .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
                if (average) {
                    BYTE128.cast(SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, dst, d1 + x))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                            .lanewise(VectorOperators.ADD, bValue).lanewise(VectorOperators.ADD, S256_1)
                            .lanewise(VectorOperators.LSHR, 1).convertShape(VectorOperators.S2B, B128, 0))
                            .intoArray(dst, d1 + x);
                } else {
                    BYTE128.cast(bValue.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d1 + x);
                }
            }
        }
    }

    /**
     * Filters a block 16 samples wide vertically with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the 16 samples of a row at once, two rows
     * per iteration; with an odd number of rows the last iteration computes the last row twice, so averaging requires
     * an even number.
     *
     * @param src the source plane
     * @param srcOff the index of the source sample 3 rows above the first output sample
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param h the number of rows
     * @param average whether to average into the destination, rounding up
     */
    private static void vertical16(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int h, boolean average) {
        var k0 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 0]));
        var k1 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 1]));
        var k2 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 2]));
        var k3 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 3]));
        var k4 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 4]));
        var k5 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 5]));
        var k6 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 6]));
        var k7 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            var a0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k0);
            var a1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 1 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k1);
            var a2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 2 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k2);
            var a3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 3 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k3);
            var a4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 4 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k4);
            var a5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 5 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k5);
            var a6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 6 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k6);
            var a7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 7 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k7);
            var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                    .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                    .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                    .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S256_BIAS);
            var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                    .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
            if (average) {
                BYTE128.cast(SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, dst, d))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.ADD, aValue)
                        .lanewise(VectorOperators.ADD, S256_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d);
            } else {
                BYTE128.cast(aValue.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d);
            }
            var b0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k0);
            var b1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 1 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k1);
            var b2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 2 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k2);
            var b3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 3 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k3);
            var b4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 4 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k4);
            var b5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 5 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k5);
            var b6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 6 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k6);
            var b7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 7 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k7);
            var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                    .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                    .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                    .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S256_BIAS);
            var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                    .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
            if (average) {
                BYTE128.cast(SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, dst, d1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.ADD, bValue)
                        .lanewise(VectorOperators.ADD, S256_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d1);
            } else {
                BYTE128.cast(bValue.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d1);
            }
        }
    }

    /**
     * Filters a block 8 samples wide vertically with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the 8 samples of a row at once, two rows per
     * iteration; with an odd number of rows the last iteration computes the last row twice, so averaging requires an
     * even number.
     *
     * @param src the source plane
     * @param srcOff the index of the source sample 3 rows above the first output sample
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param h the number of rows
     * @param average whether to average into the destination, rounding up
     */
    private static void vertical8(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int h, boolean average) {
        var k0 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 0]));
        var k1 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 1]));
        var k2 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 2]));
        var k3 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 3]));
        var k4 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 4]));
        var k5 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 5]));
        var k6 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 6]));
        var k7 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            var a0 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k0);
            var a1 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 1 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k1);
            var a2 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 2 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k2);
            var a3 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 3 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k3);
            var a4 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 4 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k4);
            var a5 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 5 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k5);
            var a6 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 6 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k6);
            var a7 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 7 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k7);
            var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                    .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                    .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                    .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            if (average) {
                BYTE64.cast(SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.ADD, aValue)
                        .lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d);
            } else {
                BYTE64.cast(aValue.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d);
            }
            var b0 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k0);
            var b1 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 1 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k1);
            var b2 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 2 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k2);
            var b3 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 3 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k3);
            var b4 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 4 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k4);
            var b5 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 5 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k5);
            var b6 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 6 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k6);
            var b7 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 7 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k7);
            var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                    .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                    .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                    .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            if (average) {
                BYTE64.cast(SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.ADD, bValue)
                        .lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d1);
            } else {
                BYTE64.cast(bValue.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d1);
            }
        }
    }

    /**
     * Filters a block 4 samples wide vertically with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the 4 samples of a row as the first half of
     * an 8-lane vector, two rows per iteration; with an odd number of rows the last iteration computes the last row
     * twice, so averaging requires an even number.
     *
     * @param src the source plane
     * @param srcOff the index of the source sample 3 rows above the first output sample
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param h the number of rows
     * @param average whether to average into the destination, rounding up
     */
    private static void vertical4(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int h, boolean average) {
        var k0 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 0]));
        var k1 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 1]));
        var k2 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 2]));
        var k3 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 3]));
        var k4 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 4]));
        var k5 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 5]));
        var k6 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 6]));
        var k7 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            var a0 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k0);
            var a1 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 1 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k1);
            var a2 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 2 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k2);
            var a3 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 3 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k3);
            var a4 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 4 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k4);
            var a5 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 5 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k5);
            var a6 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 6 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k6);
            var a7 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 7 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k7);
            var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                    .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                    .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                    .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            if (average) {
                INT.set(dst, d, INT64.cast(BYTE64.cast(SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.ADD, aValue)
                        .lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B64, 0)).reinterpretShape(I64, 0)).lane(0));
            } else {
                INT.set(dst, d, INT64.cast(BYTE64.cast(aValue.convertShape(VectorOperators.S2B, B64, 0))
                        .reinterpretShape(I64, 0)).lane(0));
            }
            var b0 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k0);
            var b1 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 1 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k1);
            var b2 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 2 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k2);
            var b3 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 3 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k3);
            var b4 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 4 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k4);
            var b5 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 5 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k5);
            var b6 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 6 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k6);
            var b7 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 7 * srcStride))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k7);
            var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                    .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                    .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                    .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            if (average) {
                INT.set(dst, d1, INT64.cast(BYTE64.cast(SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.ADD, bValue)
                        .lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B64, 0)).reinterpretShape(I64, 0)).lane(0));
            } else {
                INT.set(dst, d1, INT64.cast(BYTE64.cast(bValue.convertShape(VectorOperators.S2B, B64, 0))
                        .reinterpretShape(I64, 0)).lane(0));
            }
        }
    }

    /**
     * Filters a block at least 32 samples wide horizontally with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the samples of a row 16 at a time, two rows
     * per iteration; with an odd number of rows the last iteration computes the last row twice.
     * The destination receives the 8-bit results as 16-bit values in native byte order, the
     * input of the vertical pass of a two-dimensional filter.
     *
     * @param src the source plane
     * @param srcOff the index of the source sample 3 columns left of the first output sample
     * @param srcStride the source row stride
     * @param dst the destination scratch array
     * @param dstOff the index of the first destination sample (in bytes)
     * @param dstStride the destination row stride (in bytes)
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param w the block width, a multiple of 16
     * @param h the number of rows
     */
    private static void horizontalWideToShorts(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int w, int h) {
        var k0 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 0]));
        var k1 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 1]));
        var k2 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 2]));
        var k3 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 3]));
        var k4 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 4]));
        var k5 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 5]));
        var k6 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 6]));
        var k7 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            for (var x = 0; x < w; x += 16) {
                var a0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k0);
                var a1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k1);
                var a2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 2))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k2);
                var a3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 3))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k3);
                var a4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 4))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k4);
                var a5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 5))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k5);
                var a6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 6))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k6);
                var a7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + x + 7))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k7);
                var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                        .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                        .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                        .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S256_BIAS);
                var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                        .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
                BYTE256.cast(aValue.reinterpretShape(B256, 0)).intoArray(dst, d + 2 * x);
                var b0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k0);
                var b1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k1);
                var b2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 2))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k2);
                var b3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 3))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k3);
                var b4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 4))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k4);
                var b5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 5))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k5);
                var b6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 6))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k6);
                var b7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + x + 7))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k7);
                var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                        .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                        .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                        .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S256_BIAS);
                var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                        .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
                BYTE256.cast(bValue.reinterpretShape(B256, 0)).intoArray(dst, d1 + 2 * x);
            }
        }
    }

    /**
     * Filters a block 16 samples wide horizontally with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the 16 samples of a row at once, two rows
     * per iteration; with an odd number of rows the last iteration computes the last row twice.
     * The destination receives the 8-bit results as 16-bit values in native byte order, the
     * input of the vertical pass of a two-dimensional filter.
     *
     * @param src the source plane
     * @param srcOff the index of the source sample 3 columns left of the first output sample
     * @param srcStride the source row stride
     * @param dst the destination scratch array
     * @param dstOff the index of the first destination sample (in bytes)
     * @param dstStride the destination row stride (in bytes)
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param h the number of rows
     */
    private static void horizontal16ToShorts(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int h) {
        var k0 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 0]));
        var k1 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 1]));
        var k2 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 2]));
        var k3 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 3]));
        var k4 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 4]));
        var k5 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 5]));
        var k6 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 6]));
        var k7 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            var a0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k0);
            var a1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k1);
            var a2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 2))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k2);
            var a3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 3))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k3);
            var a4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 4))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k4);
            var a5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 5))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k5);
            var a6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 6))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k6);
            var a7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 7))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k7);
            var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                    .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                    .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                    .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S256_BIAS);
            var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                    .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
            BYTE256.cast(aValue.reinterpretShape(B256, 0)).intoArray(dst, d);
            var b0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k0);
            var b1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k1);
            var b2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 2))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k2);
            var b3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 3))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k3);
            var b4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 4))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k4);
            var b5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 5))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k5);
            var b6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 6))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k6);
            var b7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 7))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.MUL, k7);
            var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                    .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                    .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                    .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S256_BIAS);
            var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                    .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
            BYTE256.cast(bValue.reinterpretShape(B256, 0)).intoArray(dst, d1);
        }
    }

    /**
     * Filters a block 8 samples wide horizontally with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the 8 samples of a row at once, two rows per
     * iteration; with an odd number of rows the last iteration computes the last row twice.
     * The destination receives the 8-bit results as 16-bit values in native byte order, the
     * input of the vertical pass of a two-dimensional filter.
     *
     * @param src the source plane
     * @param srcOff the index of the source sample 3 columns left of the first output sample
     * @param srcStride the source row stride
     * @param dst the destination scratch array
     * @param dstOff the index of the first destination sample (in bytes)
     * @param dstStride the destination row stride (in bytes)
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param h the number of rows
     */
    private static void horizontal8ToShorts(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int h) {
        var k0 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 0]));
        var k1 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 1]));
        var k2 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 2]));
        var k3 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 3]));
        var k4 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 4]));
        var k5 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 5]));
        var k6 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 6]));
        var k7 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            var a0 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k0);
            var a1 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k1);
            var a2 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 2))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k2);
            var a3 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 3))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k3);
            var a4 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 4))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k4);
            var a5 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 5))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k5);
            var a6 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 6))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k6);
            var a7 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 7))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k7);
            var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                    .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                    .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                    .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            BYTE128.cast(aValue.reinterpretShape(B128, 0)).intoArray(dst, d);
            var b0 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k0);
            var b1 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k1);
            var b2 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 2))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k2);
            var b3 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 3))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k3);
            var b4 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 4))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k4);
            var b5 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 5))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k5);
            var b6 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 6))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k6);
            var b7 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 7))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k7);
            var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                    .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                    .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                    .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            BYTE128.cast(bValue.reinterpretShape(B128, 0)).intoArray(dst, d1);
        }
    }

    /**
     * Filters a block 4 samples wide horizontally with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the 4 samples of a row as the first half of
     * an 8-lane vector, two rows per iteration; with an odd number of rows the last iteration computes the last row
     * twice.
     * The destination receives the 8-bit results as 16-bit values in native byte order, the
     * input of the vertical pass of a two-dimensional filter.
     *
     * @param src the source plane
     * @param srcOff the index of the source sample 3 columns left of the first output sample
     * @param srcStride the source row stride
     * @param dst the destination scratch array
     * @param dstOff the index of the first destination sample (in bytes)
     * @param dstStride the destination row stride (in bytes)
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param h the number of rows
     */
    private static void horizontal4ToShorts(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int h) {
        var k0 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 0]));
        var k1 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 1]));
        var k2 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 2]));
        var k3 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 3]));
        var k4 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 4]));
        var k5 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 5]));
        var k6 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 6]));
        var k7 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            var a0 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k0);
            var a1 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k1);
            var a2 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 2))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k2);
            var a3 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 3))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k3);
            var a4 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 4))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k4);
            var a5 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 5))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k5);
            var a6 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 6))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k6);
            var a7 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s + 7))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k7);
            var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                    .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                    .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                    .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            BYTE128.cast(aValue.reinterpretShape(B128, 0)).intoArray(dst, d);
            var b0 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k0);
            var b1 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 1))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k1);
            var b2 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 2))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k2);
            var b3 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 3))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k3);
            var b4 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 4))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k4);
            var b5 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 5))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k5);
            var b6 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 6))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k6);
            var b7 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, src, s1 + 7))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.MUL, k7);
            var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                    .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                    .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                    .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            BYTE128.cast(bValue.reinterpretShape(B128, 0)).intoArray(dst, d1);
        }
    }

    /**
     * Filters a block at least 32 samples wide vertically with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the samples of a row 16 at a time, two rows
     * per iteration; with an odd number of rows the last iteration computes the last row twice, so averaging requires
     * an even number.
     * The source holds 8-bit samples as 16-bit values in native byte order, as
     * {@link #horizontalWideToShorts} writes them.
     *
     * @param src the source scratch array
     * @param srcOff the index of the source sample 3 rows above the first output sample (in bytes)
     * @param srcStride the source row stride (in bytes)
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param w the block width, a multiple of 16
     * @param h the number of rows
     * @param average whether to average into the destination, rounding up
     */
    private static void verticalWideFromShorts(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int w, int h, boolean average) {
        var k0 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 0]));
        var k1 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 1]));
        var k2 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 2]));
        var k3 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 3]));
        var k4 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 4]));
        var k5 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 5]));
        var k6 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 6]));
        var k7 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            for (var x = 0; x < w; x += 16) {
                var a0 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s + 2 * x))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k0);
                var a1 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s + 2 * x + 1 * srcStride))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k1);
                var a2 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s + 2 * x + 2 * srcStride))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k2);
                var a3 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s + 2 * x + 3 * srcStride))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k3);
                var a4 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s + 2 * x + 4 * srcStride))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k4);
                var a5 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s + 2 * x + 5 * srcStride))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k5);
                var a6 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s + 2 * x + 6 * srcStride))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k6);
                var a7 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s + 2 * x + 7 * srcStride))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k7);
                var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                        .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                        .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                        .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S256_BIAS);
                var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                        .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
                if (average) {
                    BYTE128.cast(SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, dst, d + x))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                            .lanewise(VectorOperators.ADD, aValue).lanewise(VectorOperators.ADD, S256_1)
                            .lanewise(VectorOperators.LSHR, 1).convertShape(VectorOperators.S2B, B128, 0))
                            .intoArray(dst, d + x);
                } else {
                    BYTE128.cast(aValue.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d + x);
                }
                var b0 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1 + 2 * x))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k0);
                var b1 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1 + 2 * x + 1 * srcStride))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k1);
                var b2 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1 + 2 * x + 2 * srcStride))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k2);
                var b3 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1 + 2 * x + 3 * srcStride))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k3);
                var b4 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1 + 2 * x + 4 * srcStride))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k4);
                var b5 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1 + 2 * x + 5 * srcStride))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k5);
                var b6 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1 + 2 * x + 6 * srcStride))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k6);
                var b7 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1 + 2 * x + 7 * srcStride))
                        .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k7);
                var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                        .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                        .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                        .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S256_BIAS);
                var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                        .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
                if (average) {
                    BYTE128.cast(SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, dst, d1 + x))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0))
                            .lanewise(VectorOperators.ADD, bValue).lanewise(VectorOperators.ADD, S256_1)
                            .lanewise(VectorOperators.LSHR, 1).convertShape(VectorOperators.S2B, B128, 0))
                            .intoArray(dst, d1 + x);
                } else {
                    BYTE128.cast(bValue.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d1 + x);
                }
            }
        }
    }

    /**
     * Filters a block 16 samples wide vertically with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the 16 samples of a row at once, two rows
     * per iteration; with an odd number of rows the last iteration computes the last row twice, so averaging requires
     * an even number.
     * The source holds 8-bit samples as 16-bit values in native byte order, as
     * {@link #horizontal16ToShorts} writes them.
     *
     * @param src the source scratch array
     * @param srcOff the index of the source sample 3 rows above the first output sample (in bytes)
     * @param srcStride the source row stride (in bytes)
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param h the number of rows
     * @param average whether to average into the destination, rounding up
     */
    private static void vertical16FromShorts(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int h, boolean average) {
        var k0 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 0]));
        var k1 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 1]));
        var k2 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 2]));
        var k3 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 3]));
        var k4 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 4]));
        var k5 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 5]));
        var k6 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 6]));
        var k7 = SHORT256.cast(ShortVector.broadcast(S256, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            var a0 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s)).reinterpretShape(S256, 0))
                    .lanewise(VectorOperators.MUL, k0);
            var a1 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s + 1 * srcStride))
                    .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k1);
            var a2 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s + 2 * srcStride))
                    .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k2);
            var a3 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s + 3 * srcStride))
                    .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k3);
            var a4 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s + 4 * srcStride))
                    .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k4);
            var a5 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s + 5 * srcStride))
                    .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k5);
            var a6 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s + 6 * srcStride))
                    .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k6);
            var a7 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s + 7 * srcStride))
                    .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k7);
            var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                    .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                    .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                    .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S256_BIAS);
            var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                    .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
            if (average) {
                BYTE128.cast(SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, dst, d))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.ADD, aValue)
                        .lanewise(VectorOperators.ADD, S256_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d);
            } else {
                BYTE128.cast(aValue.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d);
            }
            var b0 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1)).reinterpretShape(S256, 0))
                    .lanewise(VectorOperators.MUL, k0);
            var b1 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1 + 1 * srcStride))
                    .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k1);
            var b2 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1 + 2 * srcStride))
                    .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k2);
            var b3 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1 + 3 * srcStride))
                    .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k3);
            var b4 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1 + 4 * srcStride))
                    .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k4);
            var b5 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1 + 5 * srcStride))
                    .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k5);
            var b6 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1 + 6 * srcStride))
                    .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k6);
            var b7 = SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, src, s1 + 7 * srcStride))
                    .reinterpretShape(S256, 0)).lanewise(VectorOperators.MUL, k7);
            var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                    .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                    .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                    .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S256_BIAS);
            var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S256_128)
                    .lanewise(VectorOperators.MIN, S256_383).lanewise(VectorOperators.SUB, S256_128);
            if (average) {
                BYTE128.cast(SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, dst, d1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0)).lanewise(VectorOperators.ADD, bValue)
                        .lanewise(VectorOperators.ADD, S256_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d1);
            } else {
                BYTE128.cast(bValue.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d1);
            }
        }
    }

    /**
     * Filters a block 8 samples wide vertically with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the 8 samples of a row at once, two rows per
     * iteration; with an odd number of rows the last iteration computes the last row twice, so averaging requires an
     * even number.
     * The source holds 8-bit samples as 16-bit values in native byte order, as
     * {@link #horizontal8ToShorts} writes them.
     *
     * @param src the source scratch array
     * @param srcOff the index of the source sample 3 rows above the first output sample (in bytes)
     * @param srcStride the source row stride (in bytes)
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param h the number of rows
     * @param average whether to average into the destination, rounding up
     */
    private static void vertical8FromShorts(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int h, boolean average) {
        var k0 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 0]));
        var k1 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 1]));
        var k2 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 2]));
        var k3 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 3]));
        var k4 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 4]));
        var k5 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 5]));
        var k6 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 6]));
        var k7 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            var a0 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s)).reinterpretShape(S128, 0))
                    .lanewise(VectorOperators.MUL, k0);
            var a1 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 1 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k1);
            var a2 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 2 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k2);
            var a3 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 3 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k3);
            var a4 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 4 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k4);
            var a5 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 5 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k5);
            var a6 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 6 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k6);
            var a7 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 7 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k7);
            var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                    .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                    .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                    .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            if (average) {
                BYTE64.cast(SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.ADD, aValue)
                        .lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d);
            } else {
                BYTE64.cast(aValue.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d);
            }
            var b0 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1)).reinterpretShape(S128, 0))
                    .lanewise(VectorOperators.MUL, k0);
            var b1 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 1 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k1);
            var b2 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 2 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k2);
            var b3 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 3 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k3);
            var b4 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 4 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k4);
            var b5 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 5 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k5);
            var b6 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 6 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k6);
            var b7 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 7 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k7);
            var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                    .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                    .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                    .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            if (average) {
                BYTE64.cast(SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.ADD, bValue)
                        .lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d1);
            } else {
                BYTE64.cast(bValue.convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, d1);
            }
        }
    }

    /**
     * Filters a block 4 samples wide vertically with one eight-tap filter.
     *
     * <p>Each sample is rounded and clamped to 8 bits. The kernel computes the 4 samples of a row as the first half of
     * an 8-lane vector, two rows per iteration; with an odd number of rows the last iteration computes the last row
     * twice, so averaging requires an even number.
     * The source holds 8-bit samples as 16-bit values in native byte order, as
     * {@link #horizontal4ToShorts} writes them.
     *
     * @param src the source scratch array
     * @param srcOff the index of the source sample 3 rows above the first output sample (in bytes)
     * @param srcStride the source row stride (in bytes)
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param k the index of the filter phase in {@code kernels}
     * @param h the number of rows
     * @param average whether to average into the destination, rounding up
     */
    private static void vertical4FromShorts(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
            short[] kernels, int k, int h, boolean average) {
        var k0 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 0]));
        var k1 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 1]));
        var k2 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 2]));
        var k3 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 3]));
        var k4 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 4]));
        var k5 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 5]));
        var k6 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 6]));
        var k7 = SHORT128.cast(ShortVector.broadcast(S128, kernels[k + 7]));
        for (var y = 0; y < h; y += 2) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            var next = Math.min(y + 1, h - 1);
            var s1 = srcOff + next * srcStride;
            var d1 = dstOff + next * dstStride;
            var a0 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s)).reinterpretShape(S128, 0))
                    .lanewise(VectorOperators.MUL, k0);
            var a1 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 1 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k1);
            var a2 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 2 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k2);
            var a3 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 3 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k3);
            var a4 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 4 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k4);
            var a5 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 5 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k5);
            var a6 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 6 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k6);
            var a7 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s + 7 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k7);
            var aSum = a0.lanewise(VectorOperators.ADD, a1).lanewise(VectorOperators.ADD, a2
                    .lanewise(VectorOperators.ADD, a3)).lanewise(VectorOperators.ADD, a4
                    .lanewise(VectorOperators.ADD, a5).lanewise(VectorOperators.ADD, a6
                    .lanewise(VectorOperators.ADD, a7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var aValue = aSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            if (average) {
                INT.set(dst, d, INT64.cast(BYTE64.cast(SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.ADD, aValue)
                        .lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B64, 0)).reinterpretShape(I64, 0)).lane(0));
            } else {
                INT.set(dst, d, INT64.cast(BYTE64.cast(aValue.convertShape(VectorOperators.S2B, B64, 0))
                        .reinterpretShape(I64, 0)).lane(0));
            }
            var b0 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1)).reinterpretShape(S128, 0))
                    .lanewise(VectorOperators.MUL, k0);
            var b1 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 1 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k1);
            var b2 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 2 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k2);
            var b3 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 3 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k3);
            var b4 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 4 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k4);
            var b5 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 5 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k5);
            var b6 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 6 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k6);
            var b7 = SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, src, s1 + 7 * srcStride))
                    .reinterpretShape(S128, 0)).lanewise(VectorOperators.MUL, k7);
            var bSum = b0.lanewise(VectorOperators.ADD, b1).lanewise(VectorOperators.ADD, b2
                    .lanewise(VectorOperators.ADD, b3)).lanewise(VectorOperators.ADD, b4
                    .lanewise(VectorOperators.ADD, b5).lanewise(VectorOperators.ADD, b6
                    .lanewise(VectorOperators.ADD, b7))).lanewise(VectorOperators.ADD, S128_BIAS);
            var bValue = bSum.lanewise(VectorOperators.LSHR, 7).lanewise(VectorOperators.MAX, S128_128)
                    .lanewise(VectorOperators.MIN, S128_383).lanewise(VectorOperators.SUB, S128_128);
            if (average) {
                INT.set(dst, d1, INT64.cast(BYTE64.cast(SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, dst, d1))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0)).lanewise(VectorOperators.ADD, bValue)
                        .lanewise(VectorOperators.ADD, S128_1).lanewise(VectorOperators.LSHR, 1)
                        .convertShape(VectorOperators.S2B, B64, 0)).reinterpretShape(I64, 0)).lane(0));
            } else {
                INT.set(dst, d1, INT64.cast(BYTE64.cast(bValue.convertShape(VectorOperators.S2B, B64, 0))
                        .reinterpretShape(I64, 0)).lane(0));
            }
        }
    }


    @Override
    public void lpfHorizontal4(byte[] s, int off, int pitch, int blimit, int limit, int thresh, byte[] scratch) {
        thresholds(scratch, blimit, limit, thresh, blimit, limit, thresh);
        filter4(s, off, pitch, scratch);
        storeRows(scratch, FILTER4_ROW, s, off - 2 * pitch, pitch, 4, false);
    }

    @Override
    public void lpfHorizontal4Dual(byte[] s, int off, int pitch, int blimit0, int limit0, int thresh0,
                                   int blimit1, int limit1, int thresh1, byte[] scratch) {
        thresholds(scratch, blimit0, limit0, thresh0, blimit1, limit1, thresh1);
        filter4(s, off, pitch, scratch);
        storeRows(scratch, FILTER4_ROW, s, off - 2 * pitch, pitch, 4, true);
    }

    @Override
    public void lpfHorizontal8(byte[] s, int off, int pitch, int blimit, int limit, int thresh, byte[] scratch) {
        thresholds(scratch, blimit, limit, thresh, blimit, limit, thresh);
        filter8(s, off, pitch, scratch);
        storeRows(scratch, FILTER8_ROW, s, off - 3 * pitch, pitch, 6, false);
    }

    @Override
    public void lpfHorizontal8Dual(byte[] s, int off, int pitch, int blimit0, int limit0, int thresh0,
                                   int blimit1, int limit1, int thresh1, byte[] scratch) {
        thresholds(scratch, blimit0, limit0, thresh0, blimit1, limit1, thresh1);
        filter8(s, off, pitch, scratch);
        storeRows(scratch, FILTER8_ROW, s, off - 3 * pitch, pitch, 6, true);
    }

    @Override
    public void lpfHorizontal16(byte[] s, int off, int pitch, int blimit, int limit, int thresh, int count,
                                byte[] scratch) {
        thresholds(scratch, blimit, limit, thresh, blimit, limit, thresh);
        filter8(s, off, pitch, scratch);
        filter16Before(s, off, pitch, scratch);
        filter16After(s, off, pitch, scratch);
        storeRows(scratch, FILTER16_ROW, s, off - 7 * pitch, pitch, 14, count == 2);
    }

    @Override
    public void lpfVertical4(byte[] s, int off, int pitch, int blimit, int limit, int thresh, byte[] scratch) {
        transposeIn(s, off - 4, pitch, 0, scratch, 64);
        thresholds(scratch, blimit, limit, thresh, blimit, limit, thresh);
        filter4(scratch, 128, 16, scratch);
        System.arraycopy(scratch, FILTER4_ROW * 16, scratch, 6 * 16, 4 * 16);
        transposeOut(scratch, 64, s, off - 4, pitch, 0);
    }

    @Override
    public void lpfVertical4Dual(byte[] s, int off, int pitch, int blimit0, int limit0, int thresh0,
                                 int blimit1, int limit1, int thresh1, byte[] scratch) {
        transposeIn(s, off - 4, pitch, 8 * pitch, scratch, 64);
        thresholds(scratch, blimit0, limit0, thresh0, blimit1, limit1, thresh1);
        filter4(scratch, 128, 16, scratch);
        System.arraycopy(scratch, FILTER4_ROW * 16, scratch, 6 * 16, 4 * 16);
        transposeOut(scratch, 64, s, off - 4, pitch, 8 * pitch);
    }

    @Override
    public void lpfVertical8(byte[] s, int off, int pitch, int blimit, int limit, int thresh, byte[] scratch) {
        transposeIn(s, off - 4, pitch, 0, scratch, 64);
        thresholds(scratch, blimit, limit, thresh, blimit, limit, thresh);
        filter8(scratch, 128, 16, scratch);
        System.arraycopy(scratch, FILTER8_ROW * 16, scratch, 5 * 16, 6 * 16);
        transposeOut(scratch, 64, s, off - 4, pitch, 0);
    }

    @Override
    public void lpfVertical8Dual(byte[] s, int off, int pitch, int blimit0, int limit0, int thresh0,
                                 int blimit1, int limit1, int thresh1, byte[] scratch) {
        transposeIn(s, off - 4, pitch, 8 * pitch, scratch, 64);
        thresholds(scratch, blimit0, limit0, thresh0, blimit1, limit1, thresh1);
        filter8(scratch, 128, 16, scratch);
        System.arraycopy(scratch, FILTER8_ROW * 16, scratch, 5 * 16, 6 * 16);
        transposeOut(scratch, 64, s, off - 4, pitch, 8 * pitch);
    }

    @Override
    public void lpfVertical16(byte[] s, int off, int pitch, int blimit, int limit, int thresh, int rows,
                              byte[] scratch) {
        var lower = rows == 16 ? 8 * pitch : 0;
        transposeIn(s, off - 8, pitch, lower, scratch, 0);
        transposeIn(s, off, pitch, lower, scratch, 128);
        thresholds(scratch, blimit, limit, thresh, blimit, limit, thresh);
        filter8(scratch, 128, 16, scratch);
        filter16Before(scratch, 128, 16, scratch);
        filter16After(scratch, 128, 16, scratch);
        System.arraycopy(scratch, FILTER16_ROW * 16, scratch, 16, 14 * 16);
        transposeOut(scratch, 0, s, off - 8, pitch, lower);
        transposeOut(scratch, 128, s, off, pitch, lower);
    }

    /**
     * Copies consecutive result rows of the scratch array to consecutive rows of a plane, 8 or 16
     * samples per row.
     *
     * <p>The kernels filter a single group of 8 lines together with the 8 samples that follow it
     * in each row; only the first 8 samples of each result row are copied for it.
     *
     * @param t the scratch array
     * @param firstRow the first scratch row
     * @param s the plane
     * @param to the index of the first sample of the first row
     * @param pitch the row stride
     * @param rows the number of rows
     * @param dual whether to copy 16 samples per row rather than 8
     */
    private static void storeRows(byte[] t, int firstRow, byte[] s, int to, int pitch, int rows, boolean dual) {
        for (var r = 0; r < rows; r++) {
            var from = (firstRow + r) * 16;
            LONG.set(s, to + r * pitch, (long) LONG.get(t, from));
            if (dual) {
                LONG.set(s, to + r * pitch + 8, (long) LONG.get(t, from + 8));
            }
        }
    }

    /**
     * Writes the thresholds of the two groups of 8 lines to the threshold rows of the scratch
     * array, one byte per line.
     *
     * @param t the scratch array
     * @param blimit0 the edge limit of the first 8 lines
     * @param limit0 the interior limit of the first 8 lines
     * @param thresh0 the high edge variance threshold of the first 8 lines
     * @param blimit1 the edge limit of the second 8 lines
     * @param limit1 the interior limit of the second 8 lines
     * @param thresh1 the high edge variance threshold of the second 8 lines
     */
    private static void thresholds(byte[] t, int blimit0, int limit0, int thresh0, int blimit1, int limit1,
                                   int thresh1) {
        LONG.set(t, THRESHOLD_ROW * 16, spread(blimit0));
        LONG.set(t, THRESHOLD_ROW * 16 + 8, spread(blimit1));
        LONG.set(t, THRESHOLD_ROW * 16 + 16, spread(limit0));
        LONG.set(t, THRESHOLD_ROW * 16 + 24, spread(limit1));
        LONG.set(t, THRESHOLD_ROW * 16 + 32, spread(thresh0));
        LONG.set(t, THRESHOLD_ROW * 16 + 40, spread(thresh1));
    }

    /**
     * Returns a byte repeated in the 8 bytes of a {@code long}.
     *
     * @param value the byte, from 0 to 255
     * @return the repeated byte
     */
    private static long spread(int value) {
        return (value & 0xFFL) * 0x0101010101010101L;
    }

    /**
     * Applies the 4-tap loop filter ({@code filter4} under {@code filter_mask}) to 16 lines across an
     * edge in 16-bit lanes, writing the 4 positions around the edge to the result rows.
     *
     * @param a the array holding the samples
     * @param off the index of the first sample after the edge, on the first line
     * @param step the distance between consecutive samples across the edge
     * @param t the loop filter scratch array
     */
    private static void filter4(byte[] a, int off, int step, byte[] t) {
        var p3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 4 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 3 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 2 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 1 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 1 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 2 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 3 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var blimit = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, t, 256))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var limit = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, t, 272))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var thresh = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, t, 288))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var dp1p0 = p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS);
        var dq1q0 = q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS);
        var interior = p3.lanewise(VectorOperators.SUB, p2).lanewise(VectorOperators.ABS)
                .lanewise(VectorOperators.MAX, p2.lanewise(VectorOperators.SUB, p1).lanewise(VectorOperators.ABS))
                .lanewise(VectorOperators.MAX, dp1p0).lanewise(VectorOperators.MAX, dq1q0)
                .lanewise(VectorOperators.MAX, q2.lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.ABS))
                .lanewise(VectorOperators.MAX, q3.lanewise(VectorOperators.SUB, q2).lanewise(VectorOperators.ABS));
        var dp0q0 = p0.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS);
        var edge = dp0q0.lanewise(VectorOperators.ADD, dp0q0).lanewise(VectorOperators.ADD, p1
                .lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.ABS).lanewise(VectorOperators.ASHR, 1));
        var mask = interior.compare(VectorOperators.LE, limit).and(edge.compare(VectorOperators.LE, blimit));
        var hev = dp1p0.lanewise(VectorOperators.MAX, dq1q0).compare(VectorOperators.GT, thresh);
        var outer = S256_0.blend(p1.lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.MAX, S256_M128)
                .lanewise(VectorOperators.MIN, S256_127), hev);
        var delta = q0.lanewise(VectorOperators.SUB, p0);
        var filter = S256_0.blend(outer.lanewise(VectorOperators.ADD, delta).lanewise(VectorOperators.ADD, delta)
                .lanewise(VectorOperators.ADD, delta).lanewise(VectorOperators.MAX, S256_M128)
                .lanewise(VectorOperators.MIN, S256_127), mask);
        var filter1 = filter.lanewise(VectorOperators.ADD, S256_4).lanewise(VectorOperators.MIN, S256_127)
                .lanewise(VectorOperators.ASHR, 3);
        var filter2 = filter.lanewise(VectorOperators.ADD, S256_3).lanewise(VectorOperators.MIN, S256_127)
                .lanewise(VectorOperators.ASHR, 3);
        var adjust = S256_0.blend(filter1.lanewise(VectorOperators.ADD, S256_1)
                .lanewise(VectorOperators.ASHR, 1), hev.not());
        var f4q0 = q0.lanewise(VectorOperators.SUB, filter1).lanewise(VectorOperators.MAX, S256_0)
                .lanewise(VectorOperators.MIN, S256_255);
        var f4p0 = p0.lanewise(VectorOperators.ADD, filter2).lanewise(VectorOperators.MAX, S256_0)
                .lanewise(VectorOperators.MIN, S256_255);
        var f4q1 = q1.lanewise(VectorOperators.SUB, adjust).lanewise(VectorOperators.MAX, S256_0)
                .lanewise(VectorOperators.MIN, S256_255);
        var f4p1 = p1.lanewise(VectorOperators.ADD, adjust).lanewise(VectorOperators.MAX, S256_0)
                .lanewise(VectorOperators.MIN, S256_255);
        BYTE128.cast(f4p1.convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 304);
        BYTE128.cast(f4p0.convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 320);
        BYTE128.cast(f4q0.convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 336);
        BYTE128.cast(f4q1.convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 352);
    }

    /**
     * Applies the 8-tap loop filter ({@code filter8}, falling back to {@code filter4} where the
     * samples are not flat) to 16 lines across an edge in 16-bit lanes.
     *
     * <p>The 6 filtered positions are written to the result rows, followed by a flag row that is
     * {@code -1} where the 8-tap filter applied and {@code 0} elsewhere.
     *
     * @param a the array holding the samples
     * @param off the index of the first sample after the edge, on the first line
     * @param step the distance between consecutive samples across the edge
     * @param t the loop filter scratch array
     */
    private static void filter8(byte[] a, int off, int step, byte[] t) {
        var p3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 4 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 3 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 2 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 1 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 1 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 2 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 3 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var blimit = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, t, 256))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var limit = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, t, 272))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var thresh = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, t, 288))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var dp1p0 = p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS);
        var dq1q0 = q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS);
        var interior = p3.lanewise(VectorOperators.SUB, p2).lanewise(VectorOperators.ABS)
                .lanewise(VectorOperators.MAX, p2.lanewise(VectorOperators.SUB, p1).lanewise(VectorOperators.ABS))
                .lanewise(VectorOperators.MAX, dp1p0).lanewise(VectorOperators.MAX, dq1q0)
                .lanewise(VectorOperators.MAX, q2.lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.ABS))
                .lanewise(VectorOperators.MAX, q3.lanewise(VectorOperators.SUB, q2).lanewise(VectorOperators.ABS));
        var dp0q0 = p0.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS);
        var edge = dp0q0.lanewise(VectorOperators.ADD, dp0q0).lanewise(VectorOperators.ADD, p1
                .lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.ABS).lanewise(VectorOperators.ASHR, 1));
        var mask = interior.compare(VectorOperators.LE, limit).and(edge.compare(VectorOperators.LE, blimit));
        var hev = dp1p0.lanewise(VectorOperators.MAX, dq1q0).compare(VectorOperators.GT, thresh);
        var outer = S256_0.blend(p1.lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.MAX, S256_M128)
                .lanewise(VectorOperators.MIN, S256_127), hev);
        var delta = q0.lanewise(VectorOperators.SUB, p0);
        var filter = S256_0.blend(outer.lanewise(VectorOperators.ADD, delta).lanewise(VectorOperators.ADD, delta)
                .lanewise(VectorOperators.ADD, delta).lanewise(VectorOperators.MAX, S256_M128)
                .lanewise(VectorOperators.MIN, S256_127), mask);
        var filter1 = filter.lanewise(VectorOperators.ADD, S256_4).lanewise(VectorOperators.MIN, S256_127)
                .lanewise(VectorOperators.ASHR, 3);
        var filter2 = filter.lanewise(VectorOperators.ADD, S256_3).lanewise(VectorOperators.MIN, S256_127)
                .lanewise(VectorOperators.ASHR, 3);
        var adjust = S256_0.blend(filter1.lanewise(VectorOperators.ADD, S256_1)
                .lanewise(VectorOperators.ASHR, 1), hev.not());
        var f4q0 = q0.lanewise(VectorOperators.SUB, filter1).lanewise(VectorOperators.MAX, S256_0)
                .lanewise(VectorOperators.MIN, S256_255);
        var f4p0 = p0.lanewise(VectorOperators.ADD, filter2).lanewise(VectorOperators.MAX, S256_0)
                .lanewise(VectorOperators.MIN, S256_255);
        var f4q1 = q1.lanewise(VectorOperators.SUB, adjust).lanewise(VectorOperators.MAX, S256_0)
                .lanewise(VectorOperators.MIN, S256_255);
        var f4p1 = p1.lanewise(VectorOperators.ADD, adjust).lanewise(VectorOperators.MAX, S256_0)
                .lanewise(VectorOperators.MIN, S256_255);
        var flat = dp1p0.lanewise(VectorOperators.MAX, dq1q0).lanewise(VectorOperators.MAX, p2
                .lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS)).lanewise(VectorOperators.MAX, q2
                .lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS)).lanewise(VectorOperators.MAX, p3
                .lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS)).lanewise(VectorOperators.MAX, q3
                .lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS)).compare(VectorOperators.LE, S256_1)
                .and(mask);
        var t0 = p3.lanewise(VectorOperators.ADD, p3).lanewise(VectorOperators.ADD, p3)
                .lanewise(VectorOperators.ADD, p2).lanewise(VectorOperators.ADD, p2).lanewise(VectorOperators.ADD, p1)
                .lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, q0)
                .lanewise(VectorOperators.ADD, S256_4);
        var t1 = t0.lanewise(VectorOperators.SUB, p3).lanewise(VectorOperators.SUB, p2)
                .lanewise(VectorOperators.ADD, p1).lanewise(VectorOperators.ADD, q1);
        var t2 = t1.lanewise(VectorOperators.SUB, p3).lanewise(VectorOperators.SUB, p1)
                .lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, q2);
        var t3 = t2.lanewise(VectorOperators.SUB, p3).lanewise(VectorOperators.SUB, p0)
                .lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, q3);
        var t4 = t3.lanewise(VectorOperators.SUB, p2).lanewise(VectorOperators.SUB, q0)
                .lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, q3);
        var t5 = t4.lanewise(VectorOperators.SUB, p1).lanewise(VectorOperators.SUB, q1)
                .lanewise(VectorOperators.ADD, q2).lanewise(VectorOperators.ADD, q3);
        BYTE128.cast(p2.blend(t0.lanewise(VectorOperators.ASHR, 3), flat).convertShape(VectorOperators.S2B, B128, 0))
                .intoArray(t, 368);
        BYTE128.cast(f4p1.blend(t1.lanewise(VectorOperators.ASHR, 3), flat).convertShape(VectorOperators.S2B, B128, 0))
                .intoArray(t, 384);
        BYTE128.cast(f4p0.blend(t2.lanewise(VectorOperators.ASHR, 3), flat).convertShape(VectorOperators.S2B, B128, 0))
                .intoArray(t, 400);
        BYTE128.cast(f4q0.blend(t3.lanewise(VectorOperators.ASHR, 3), flat).convertShape(VectorOperators.S2B, B128, 0))
                .intoArray(t, 416);
        BYTE128.cast(f4q1.blend(t4.lanewise(VectorOperators.ASHR, 3), flat).convertShape(VectorOperators.S2B, B128, 0))
                .intoArray(t, 432);
        BYTE128.cast(q2.blend(t5.lanewise(VectorOperators.ASHR, 3), flat).convertShape(VectorOperators.S2B, B128, 0))
                .intoArray(t, 448);
        BYTE128.cast(S256_0.blend(S256_M1, flat).convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 464);
    }

    /**
     * Computes the 16-tap loop filter ({@code filter16}) of the 7 positions before the edge, after
     * {@link #filter8} has left its results and flags in the scratch rows.
     *
     * <p>Where the flag is set and the 7 samples on each side are flat, the 15-tap filter
     * applies; elsewhere the 3 positions next to the edge take the results of {@link #filter8}
     * and the outer ones keep their samples. The results go to the 7 rows after the flags.
     *
     * @param a the array holding the samples
     * @param off the index of the first sample after the edge, on the first line
     * @param step the distance between consecutive samples across the edge
     * @param t the loop filter scratch array
     */
    private static void filter16Before(byte[] a, int off, int step, byte[] t) {
        var p7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 8 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 7 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 6 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 5 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 4 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 3 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 2 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 1 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 1 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 2 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 3 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 4 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 5 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 6 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 7 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var f8p2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, t, 368))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var f8p1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, t, 384))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var f8p0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, t, 400))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var flags = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, t, 464))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var flat2 = p4.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).lanewise(VectorOperators.MAX, q4
                .lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS)).lanewise(VectorOperators.MAX, p5
                .lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS)).lanewise(VectorOperators.MAX, q5
                .lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS)).lanewise(VectorOperators.MAX, p6
                .lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS)).lanewise(VectorOperators.MAX, q6
                .lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS)).lanewise(VectorOperators.MAX, p7
                .lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS)).lanewise(VectorOperators.MAX, q7
                .lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS)).compare(VectorOperators.LE, S256_1)
                .and(flags.compare(VectorOperators.NE, S256_0));
        var w1 = p7.lanewise(VectorOperators.MUL, S256_7).lanewise(VectorOperators.ADD, p6)
                .lanewise(VectorOperators.ADD, p5).lanewise(VectorOperators.ADD, p4).lanewise(VectorOperators.ADD, p3)
                .lanewise(VectorOperators.ADD, p2).lanewise(VectorOperators.ADD, p1).lanewise(VectorOperators.ADD, p0)
                .lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, S256_8);
        var w2 = w1.lanewise(VectorOperators.SUB, p7).lanewise(VectorOperators.ADD, q1);
        var w3 = w2.lanewise(VectorOperators.SUB, p7).lanewise(VectorOperators.ADD, q2);
        var w4 = w3.lanewise(VectorOperators.SUB, p7).lanewise(VectorOperators.ADD, q3);
        var w5 = w4.lanewise(VectorOperators.SUB, p7).lanewise(VectorOperators.ADD, q4);
        var w6 = w5.lanewise(VectorOperators.SUB, p7).lanewise(VectorOperators.ADD, q5);
        var w7 = w6.lanewise(VectorOperators.SUB, p7).lanewise(VectorOperators.ADD, q6);
        BYTE128.cast(p6.blend(w1.lanewise(VectorOperators.ADD, p6).lanewise(VectorOperators.ASHR, 4), flat2)
                .convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 480);
        BYTE128.cast(p5.blend(w2.lanewise(VectorOperators.ADD, p5).lanewise(VectorOperators.ASHR, 4), flat2)
                .convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 496);
        BYTE128.cast(p4.blend(w3.lanewise(VectorOperators.ADD, p4).lanewise(VectorOperators.ASHR, 4), flat2)
                .convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 512);
        BYTE128.cast(p3.blend(w4.lanewise(VectorOperators.ADD, p3).lanewise(VectorOperators.ASHR, 4), flat2)
                .convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 528);
        BYTE128.cast(f8p2.blend(w5.lanewise(VectorOperators.ADD, p2).lanewise(VectorOperators.ASHR, 4), flat2)
                .convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 544);
        BYTE128.cast(f8p1.blend(w6.lanewise(VectorOperators.ADD, p1).lanewise(VectorOperators.ASHR, 4), flat2)
                .convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 560);
        BYTE128.cast(f8p0.blend(w7.lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ASHR, 4), flat2)
                .convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 576);
    }

    /**
     * Computes the 16-tap loop filter ({@code filter16}) of the 7 positions after the edge, as
     * {@link #filter16Before} does for the positions before it, into the 7 rows after its
     * results.
     *
     * @param a the array holding the samples
     * @param off the index of the first sample after the edge, on the first line
     * @param step the distance between consecutive samples across the edge
     * @param t the loop filter scratch array
     */
    private static void filter16After(byte[] a, int off, int step, byte[] t) {
        var p7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 8 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 7 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 6 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 5 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 4 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 3 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 2 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var p0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off - 1 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 1 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 2 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q3 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 3 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q4 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 4 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q5 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 5 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q6 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 6 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var q7 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, off + 7 * step))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var f8q0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, t, 416))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var f8q1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, t, 432))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var f8q2 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, t, 448))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var flags = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, t, 464))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
        var flat2 = p4.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).lanewise(VectorOperators.MAX, q4
                .lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS)).lanewise(VectorOperators.MAX, p5
                .lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS)).lanewise(VectorOperators.MAX, q5
                .lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS)).lanewise(VectorOperators.MAX, p6
                .lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS)).lanewise(VectorOperators.MAX, q6
                .lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS)).lanewise(VectorOperators.MAX, p7
                .lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS)).lanewise(VectorOperators.MAX, q7
                .lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS)).compare(VectorOperators.LE, S256_1)
                .and(flags.compare(VectorOperators.NE, S256_0));
        var w8 = p6.lanewise(VectorOperators.ADD, p5).lanewise(VectorOperators.ADD, p4)
                .lanewise(VectorOperators.ADD, p3).lanewise(VectorOperators.ADD, p2).lanewise(VectorOperators.ADD, p1)
                .lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, q1)
                .lanewise(VectorOperators.ADD, q2).lanewise(VectorOperators.ADD, q3).lanewise(VectorOperators.ADD, q4)
                .lanewise(VectorOperators.ADD, q5).lanewise(VectorOperators.ADD, q6).lanewise(VectorOperators.ADD, q7)
                .lanewise(VectorOperators.ADD, S256_8);
        var w9 = w8.lanewise(VectorOperators.SUB, p6).lanewise(VectorOperators.ADD, q7);
        var w10 = w9.lanewise(VectorOperators.SUB, p5).lanewise(VectorOperators.ADD, q7);
        var w11 = w10.lanewise(VectorOperators.SUB, p4).lanewise(VectorOperators.ADD, q7);
        var w12 = w11.lanewise(VectorOperators.SUB, p3).lanewise(VectorOperators.ADD, q7);
        var w13 = w12.lanewise(VectorOperators.SUB, p2).lanewise(VectorOperators.ADD, q7);
        var w14 = w13.lanewise(VectorOperators.SUB, p1).lanewise(VectorOperators.ADD, q7);
        BYTE128.cast(f8q0.blend(w8.lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ASHR, 4), flat2)
                .convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 592);
        BYTE128.cast(f8q1.blend(w9.lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ASHR, 4), flat2)
                .convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 608);
        BYTE128.cast(f8q2.blend(w10.lanewise(VectorOperators.ADD, q2).lanewise(VectorOperators.ASHR, 4), flat2)
                .convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 624);
        BYTE128.cast(q3.blend(w11.lanewise(VectorOperators.ADD, q3).lanewise(VectorOperators.ASHR, 4), flat2)
                .convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 640);
        BYTE128.cast(q4.blend(w12.lanewise(VectorOperators.ADD, q4).lanewise(VectorOperators.ASHR, 4), flat2)
                .convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 656);
        BYTE128.cast(q5.blend(w13.lanewise(VectorOperators.ADD, q5).lanewise(VectorOperators.ASHR, 4), flat2)
                .convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 672);
        BYTE128.cast(q6.blend(w14.lanewise(VectorOperators.ADD, q6).lanewise(VectorOperators.ASHR, 4), flat2)
                .convertShape(VectorOperators.S2B, B128, 0)).intoArray(t, 688);
    }

    /**
     * Transposes an 8-column window of 8 or 16 rows into 8 scratch rows.
     *
     * <p>Column {@code j} is stored as 16 bytes at {@code t[tOff + 16 * j]}: the first 8 rows,
     * then the 8 rows {@code lower} entries further, which repeat the first 8 when
     * {@code lower} is {@code 0}. The transposes run in registers on 64-bit lanes, one 8x8
     * block per lane.
     *
     * @param s the plane
     * @param off the index of the window's top-left sample
     * @param pitch the row stride
     * @param lower the distance from the first 8 rows to the second 8, or {@code 0}
     * @param t the scratch array
     * @param tOff the index of the first transposed column
     */
    private static void transposeIn(byte[] s, int off, int pitch, int lower, byte[] t, int tOff) {
        var r0 = LONG128.cast(LONG128.cast(LongVector.broadcast(L128, (long) LONG.get(s, off)))
                .withLane(1, (long) LONG.get(s, off + lower)));
        var r1 = LONG128.cast(LONG128.cast(LongVector.broadcast(L128, (long) LONG.get(s, off + 1 * pitch)))
                .withLane(1, (long) LONG.get(s, off + 1 * pitch + lower)));
        var r2 = LONG128.cast(LONG128.cast(LongVector.broadcast(L128, (long) LONG.get(s, off + 2 * pitch)))
                .withLane(1, (long) LONG.get(s, off + 2 * pitch + lower)));
        var r3 = LONG128.cast(LONG128.cast(LongVector.broadcast(L128, (long) LONG.get(s, off + 3 * pitch)))
                .withLane(1, (long) LONG.get(s, off + 3 * pitch + lower)));
        var r4 = LONG128.cast(LONG128.cast(LongVector.broadcast(L128, (long) LONG.get(s, off + 4 * pitch)))
                .withLane(1, (long) LONG.get(s, off + 4 * pitch + lower)));
        var r5 = LONG128.cast(LONG128.cast(LongVector.broadcast(L128, (long) LONG.get(s, off + 5 * pitch)))
                .withLane(1, (long) LONG.get(s, off + 5 * pitch + lower)));
        var r6 = LONG128.cast(LONG128.cast(LongVector.broadcast(L128, (long) LONG.get(s, off + 6 * pitch)))
                .withLane(1, (long) LONG.get(s, off + 6 * pitch + lower)));
        var r7 = LONG128.cast(LONG128.cast(LongVector.broadcast(L128, (long) LONG.get(s, off + 7 * pitch)))
                .withLane(1, (long) LONG.get(s, off + 7 * pitch + lower)));
        var x01 = LONG128.cast(r0.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r1)
                .lanewise(VectorOperators.AND, L128_BYTES));
        r1 = r1.lanewise(VectorOperators.XOR, x01);
        r0 = r0.lanewise(VectorOperators.XOR, x01.lanewise(VectorOperators.LSHL, 8));
        var x23 = LONG128.cast(r2.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r3)
                .lanewise(VectorOperators.AND, L128_BYTES));
        r3 = r3.lanewise(VectorOperators.XOR, x23);
        r2 = r2.lanewise(VectorOperators.XOR, x23.lanewise(VectorOperators.LSHL, 8));
        var x45 = LONG128.cast(r4.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r5)
                .lanewise(VectorOperators.AND, L128_BYTES));
        r5 = r5.lanewise(VectorOperators.XOR, x45);
        r4 = r4.lanewise(VectorOperators.XOR, x45.lanewise(VectorOperators.LSHL, 8));
        var x67 = LONG128.cast(r6.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r7)
                .lanewise(VectorOperators.AND, L128_BYTES));
        r7 = r7.lanewise(VectorOperators.XOR, x67);
        r6 = r6.lanewise(VectorOperators.XOR, x67.lanewise(VectorOperators.LSHL, 8));
        var x02 = LONG128.cast(r0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r2)
                .lanewise(VectorOperators.AND, L128_SHORTS));
        r2 = r2.lanewise(VectorOperators.XOR, x02);
        r0 = r0.lanewise(VectorOperators.XOR, x02.lanewise(VectorOperators.LSHL, 16));
        var x13 = LONG128.cast(r1.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r3)
                .lanewise(VectorOperators.AND, L128_SHORTS));
        r3 = r3.lanewise(VectorOperators.XOR, x13);
        r1 = r1.lanewise(VectorOperators.XOR, x13.lanewise(VectorOperators.LSHL, 16));
        var x46 = LONG128.cast(r4.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r6)
                .lanewise(VectorOperators.AND, L128_SHORTS));
        r6 = r6.lanewise(VectorOperators.XOR, x46);
        r4 = r4.lanewise(VectorOperators.XOR, x46.lanewise(VectorOperators.LSHL, 16));
        var x57 = LONG128.cast(r5.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r7)
                .lanewise(VectorOperators.AND, L128_SHORTS));
        r7 = r7.lanewise(VectorOperators.XOR, x57);
        r5 = r5.lanewise(VectorOperators.XOR, x57.lanewise(VectorOperators.LSHL, 16));
        var x04 = LONG128.cast(r0.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r4)
                .lanewise(VectorOperators.AND, L128_INTS));
        r4 = r4.lanewise(VectorOperators.XOR, x04);
        r0 = r0.lanewise(VectorOperators.XOR, x04.lanewise(VectorOperators.LSHL, 32));
        var x15 = LONG128.cast(r1.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r5)
                .lanewise(VectorOperators.AND, L128_INTS));
        r5 = r5.lanewise(VectorOperators.XOR, x15);
        r1 = r1.lanewise(VectorOperators.XOR, x15.lanewise(VectorOperators.LSHL, 32));
        var x26 = LONG128.cast(r2.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r6)
                .lanewise(VectorOperators.AND, L128_INTS));
        r6 = r6.lanewise(VectorOperators.XOR, x26);
        r2 = r2.lanewise(VectorOperators.XOR, x26.lanewise(VectorOperators.LSHL, 32));
        var x37 = LONG128.cast(r3.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r7)
                .lanewise(VectorOperators.AND, L128_INTS));
        r7 = r7.lanewise(VectorOperators.XOR, x37);
        r3 = r3.lanewise(VectorOperators.XOR, x37.lanewise(VectorOperators.LSHL, 32));
        BYTE128.cast(r0.reinterpretShape(B128, 0)).intoArray(t, tOff + 0);
        BYTE128.cast(r1.reinterpretShape(B128, 0)).intoArray(t, tOff + 16);
        BYTE128.cast(r2.reinterpretShape(B128, 0)).intoArray(t, tOff + 32);
        BYTE128.cast(r3.reinterpretShape(B128, 0)).intoArray(t, tOff + 48);
        BYTE128.cast(r4.reinterpretShape(B128, 0)).intoArray(t, tOff + 64);
        BYTE128.cast(r5.reinterpretShape(B128, 0)).intoArray(t, tOff + 80);
        BYTE128.cast(r6.reinterpretShape(B128, 0)).intoArray(t, tOff + 96);
        BYTE128.cast(r7.reinterpretShape(B128, 0)).intoArray(t, tOff + 112);
    }

    /**
     * Writes 8 scratch rows produced by {@link #transposeIn} back to the plane.
     *
     * @param t the scratch array
     * @param tOff the index of the first transposed column
     * @param s the plane
     * @param off the index of the window's top-left sample
     * @param pitch the row stride
     * @param lower the distance from the first 8 rows to the second 8, or {@code 0} to write
     * the first 8 rows only
     */
    private static void transposeOut(byte[] t, int tOff, byte[] s, int off, int pitch, int lower) {
        var r0 = LONG128.cast(BYTE128.cast(ByteVector.fromArray(B128, t, tOff + 0)).reinterpretShape(L128, 0));
        var r1 = LONG128.cast(BYTE128.cast(ByteVector.fromArray(B128, t, tOff + 16)).reinterpretShape(L128, 0));
        var r2 = LONG128.cast(BYTE128.cast(ByteVector.fromArray(B128, t, tOff + 32)).reinterpretShape(L128, 0));
        var r3 = LONG128.cast(BYTE128.cast(ByteVector.fromArray(B128, t, tOff + 48)).reinterpretShape(L128, 0));
        var r4 = LONG128.cast(BYTE128.cast(ByteVector.fromArray(B128, t, tOff + 64)).reinterpretShape(L128, 0));
        var r5 = LONG128.cast(BYTE128.cast(ByteVector.fromArray(B128, t, tOff + 80)).reinterpretShape(L128, 0));
        var r6 = LONG128.cast(BYTE128.cast(ByteVector.fromArray(B128, t, tOff + 96)).reinterpretShape(L128, 0));
        var r7 = LONG128.cast(BYTE128.cast(ByteVector.fromArray(B128, t, tOff + 112)).reinterpretShape(L128, 0));
        var x01 = LONG128.cast(r0.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r1)
                .lanewise(VectorOperators.AND, L128_BYTES));
        r1 = r1.lanewise(VectorOperators.XOR, x01);
        r0 = r0.lanewise(VectorOperators.XOR, x01.lanewise(VectorOperators.LSHL, 8));
        var x23 = LONG128.cast(r2.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r3)
                .lanewise(VectorOperators.AND, L128_BYTES));
        r3 = r3.lanewise(VectorOperators.XOR, x23);
        r2 = r2.lanewise(VectorOperators.XOR, x23.lanewise(VectorOperators.LSHL, 8));
        var x45 = LONG128.cast(r4.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r5)
                .lanewise(VectorOperators.AND, L128_BYTES));
        r5 = r5.lanewise(VectorOperators.XOR, x45);
        r4 = r4.lanewise(VectorOperators.XOR, x45.lanewise(VectorOperators.LSHL, 8));
        var x67 = LONG128.cast(r6.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r7)
                .lanewise(VectorOperators.AND, L128_BYTES));
        r7 = r7.lanewise(VectorOperators.XOR, x67);
        r6 = r6.lanewise(VectorOperators.XOR, x67.lanewise(VectorOperators.LSHL, 8));
        var x02 = LONG128.cast(r0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r2)
                .lanewise(VectorOperators.AND, L128_SHORTS));
        r2 = r2.lanewise(VectorOperators.XOR, x02);
        r0 = r0.lanewise(VectorOperators.XOR, x02.lanewise(VectorOperators.LSHL, 16));
        var x13 = LONG128.cast(r1.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r3)
                .lanewise(VectorOperators.AND, L128_SHORTS));
        r3 = r3.lanewise(VectorOperators.XOR, x13);
        r1 = r1.lanewise(VectorOperators.XOR, x13.lanewise(VectorOperators.LSHL, 16));
        var x46 = LONG128.cast(r4.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r6)
                .lanewise(VectorOperators.AND, L128_SHORTS));
        r6 = r6.lanewise(VectorOperators.XOR, x46);
        r4 = r4.lanewise(VectorOperators.XOR, x46.lanewise(VectorOperators.LSHL, 16));
        var x57 = LONG128.cast(r5.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r7)
                .lanewise(VectorOperators.AND, L128_SHORTS));
        r7 = r7.lanewise(VectorOperators.XOR, x57);
        r5 = r5.lanewise(VectorOperators.XOR, x57.lanewise(VectorOperators.LSHL, 16));
        var x04 = LONG128.cast(r0.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r4)
                .lanewise(VectorOperators.AND, L128_INTS));
        r4 = r4.lanewise(VectorOperators.XOR, x04);
        r0 = r0.lanewise(VectorOperators.XOR, x04.lanewise(VectorOperators.LSHL, 32));
        var x15 = LONG128.cast(r1.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r5)
                .lanewise(VectorOperators.AND, L128_INTS));
        r5 = r5.lanewise(VectorOperators.XOR, x15);
        r1 = r1.lanewise(VectorOperators.XOR, x15.lanewise(VectorOperators.LSHL, 32));
        var x26 = LONG128.cast(r2.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r6)
                .lanewise(VectorOperators.AND, L128_INTS));
        r6 = r6.lanewise(VectorOperators.XOR, x26);
        r2 = r2.lanewise(VectorOperators.XOR, x26.lanewise(VectorOperators.LSHL, 32));
        var x37 = LONG128.cast(r3.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r7)
                .lanewise(VectorOperators.AND, L128_INTS));
        r7 = r7.lanewise(VectorOperators.XOR, x37);
        r3 = r3.lanewise(VectorOperators.XOR, x37.lanewise(VectorOperators.LSHL, 32));
        LONG.set(s, off, r0.lane(0));
        LONG.set(s, off + lower, r0.lane(1));
        LONG.set(s, off + 1 * pitch, r1.lane(0));
        LONG.set(s, off + 1 * pitch + lower, r1.lane(1));
        LONG.set(s, off + 2 * pitch, r2.lane(0));
        LONG.set(s, off + 2 * pitch + lower, r2.lane(1));
        LONG.set(s, off + 3 * pitch, r3.lane(0));
        LONG.set(s, off + 3 * pitch + lower, r3.lane(1));
        LONG.set(s, off + 4 * pitch, r4.lane(0));
        LONG.set(s, off + 4 * pitch + lower, r4.lane(1));
        LONG.set(s, off + 5 * pitch, r5.lane(0));
        LONG.set(s, off + 5 * pitch + lower, r5.lane(1));
        LONG.set(s, off + 6 * pitch, r6.lane(0));
        LONG.set(s, off + 6 * pitch + lower, r6.lane(1));
        LONG.set(s, off + 7 * pitch, r7.lane(0));
        LONG.set(s, off + 7 * pitch + lower, r7.lane(1));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation computes the true-motion predictor with vectors and runs the
     * scalar kernels for the other modes, which fill rows, copy rows and filter the
     * {@code 2 * size} edge samples once.
     */
    @Override
    public void intraPredict(int mode, int txSize, int dcEdges, byte[] dst, int dstOff, int stride,
                             byte[] above, int aboveOff, byte[] left, int leftOff) {
        if (mode == Vp9Constants.TM_PRED) {
            trueMotion(4 << txSize, dst, dstOff, stride, above, aboveOff, left, leftOff);
        } else {
            scalar.intraPredict(mode, txSize, dcEdges, dst, dstOff, stride, above, aboveOff, left, leftOff);
        }
    }

    /**
     * Predicts a block with the true-motion predictor ({@code vpx_tm_predictor_NxN}): sample
     * {@code (r, c)} is {@code left[r] + above[c] - above[-1]} clamped to {@code [0, 255]},
     * computed in 16-bit lanes, 16 samples per vector.
     *
     * @param bs the block size, 4 to 32
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param above the array holding the above row, at least 8 samples from {@code aboveOff}
     * @param aboveOff the index of the first above sample
     * @param left the array holding the left column
     * @param leftOff the index of the first left sample
     */
    private static void trueMotion(int bs, byte[] dst, int dstOff, int stride, byte[] above, int aboveOff,
                                   byte[] left, int leftOff) {
        var topLeft = above[aboveOff - 1] & 0xFF;
        if (bs == 32) {
            var a0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, above, aboveOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var a1 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, above, aboveOff + 16))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            for (var r = 0; r < 32; r++) {
                var l = SHORT256.cast(ShortVector.broadcast(S256, (short) ((left[leftOff + r] & 0xFF) - topLeft)));
                var row = dstOff + r * stride;
                BYTE128.cast(SHORT256.cast(a0).lanewise(VectorOperators.ADD, l).lanewise(VectorOperators.MAX, S256_0)
                        .lanewise(VectorOperators.MIN, S256_255).convertShape(VectorOperators.S2B, B128, 0))
                        .intoArray(dst, row);
                BYTE128.cast(SHORT256.cast(a1).lanewise(VectorOperators.ADD, l).lanewise(VectorOperators.MAX, S256_0)
                        .lanewise(VectorOperators.MIN, S256_255).convertShape(VectorOperators.S2B, B128, 0))
                        .intoArray(dst, row + 16);
            }
        } else if (bs == 16) {
            var a0 = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, above, aboveOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            for (var r = 0; r < 16; r++) {
                var l = SHORT256.cast(ShortVector.broadcast(S256, (short) ((left[leftOff + r] & 0xFF) - topLeft)));
                BYTE128.cast(SHORT256.cast(a0).lanewise(VectorOperators.ADD, l).lanewise(VectorOperators.MAX, S256_0)
                        .lanewise(VectorOperators.MIN, S256_255).convertShape(VectorOperators.S2B, B128, 0))
                        .intoArray(dst, dstOff + r * stride);
            }
        } else {
            var a0 = SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, above, aboveOff))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            for (var r = 0; r < bs; r++) {
                var l = SHORT128.cast(ShortVector.broadcast(S128, (short) ((left[leftOff + r] & 0xFF) - topLeft)));
                var row = BYTE64.cast(SHORT128.cast(a0).lanewise(VectorOperators.ADD, l).lanewise(VectorOperators.MAX, S128_0)
                        .lanewise(VectorOperators.MIN, S128_255).convertShape(VectorOperators.S2B, B64, 0));
                if (bs == 8) {
                    row.intoArray(dst, dstOff + r * stride);
                } else {
                    INT.set(dst, dstOff + r * stride, INT64.cast(row.reinterpretShape(I64, 0)).lane(0));
                }
            }
        }
    }
}
