package com.github.auties00.smalt.image.jpeg.internal;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

import java.util.Arrays;

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
 * Implements {@link BatchIdctKernel} with the Vector API, one block per 32-bit lane of a 256-bit
 * vector.
 *
 * <p>The column pass loads the eight coefficients of one column of every block as eight
 * vectors, computes the butterflies lane-wise and stores the results in place; the row pass
 * loads the eight column-pass results of one row of every block, computes the butterflies,
 * range-limits the samples, packs each block's row into one integer (or two, for eight samples)
 * and stores the row of all eight blocks with one or two wide stores. The zero-column and
 * zero-row shortcuts of the scalar transforms are reproduced with lane masks and blends, so the
 * samples equal {@link ScalarBatchIdctKernel}'s for every input.
 *
 * <p>Every operation is invoked through the per-operation methods ({@code lanewise} with an
 * operator, {@code compare}, {@code blend}, single-vector {@code rearrange}) with vector
 * constants, and no vector is passed to or returned from a helper: each pass stores its results
 * in the batch for the next. Every vector that a load produces is cast to the exact class of its
 * species before it is used as the receiver of a call, and so is the receiver of every
 * {@code intoArray}, reinterpretation and shift by a scalar count, so that the compiler binds
 * those calls without consulting receiver-type profiles: those profiles are shared by every
 * caller of the Vector API's methods, and code using other species of the same element type
 * makes them useless, which leaves operations unintrinsified or their vectors boxed.
 * Reinterpretations use the per-shape {@code reinterpretShape}, never the {@code reinterpretAs}
 * methods.
 * The
 * interleaving of two vectors is spelled as two single-vector rearrangements and a blend,
 * because the Vector API's two-vector rearrangement blends internally on a vector whose class
 * only such a profile knows. A batch of fewer than eight blocks is transformed by the scalar
 * kernel, since the wide stores would write past the batch.
 *
 * <p>This class references {@code jdk.incubator.vector} types and is only loaded through
 * {@link VectorKernels} when the Vector API is available.
 *
 * @implNote This implementation passes shift counts as {@code int} constants, the one exception
 * to vector operands: they compile to immediate shifts, while broadcast shift-count
 * vectors compile to per-lane variable shifts and made the 8x8 transform about 10
 * percent slower, with no difference in allocation.
 */
final class VectorBatchIdctKernel implements BatchIdctKernel {
    /**
     * Eight 32-bit lanes, one per block.
     */
    private static final VectorSpecies<Integer> I = IntVector.SPECIES_256;

    /**
     * Eight 16-bit lanes, one packed 2x1 row per block.
     */
    private static final VectorSpecies<Short> S = ShortVector.SPECIES_128;

    /**
     * The distance between two rows of coefficients of one block in the interleaved layout.
     */
    private static final int ROW = 8 * LANES;

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
     * {@code -FIX(0.211164243)} in every lane.
     */
    private static final IntVector N_0_211164243 = IntVector.broadcast(I, -1730);

    /**
     * {@code -FIX(0.509795579)} in every lane.
     */
    private static final IntVector N_0_509795579 = IntVector.broadcast(I, -4176);

    /**
     * {@code -FIX(0.601344887)} in every lane.
     */
    private static final IntVector N_0_601344887 = IntVector.broadcast(I, -4926);

    /**
     * {@code -FIX(0.720959822)} in every lane.
     */
    private static final IntVector N_0_720959822 = IntVector.broadcast(I, -5906);

    /**
     * {@code -FIX(0.765366865)} in every lane.
     */
    private static final IntVector N_0_765366865 = IntVector.broadcast(I, -FIX_0_765366865);

    /**
     * {@code FIX(0.850430095)} in every lane.
     */
    private static final IntVector F_0_850430095 = IntVector.broadcast(I, 6967);

    /**
     * {@code FIX(0.899976223)} in every lane.
     */
    private static final IntVector F_0_899976223 = IntVector.broadcast(I, FIX_0_899976223);

    /**
     * {@code FIX(1.061594337)} in every lane.
     */
    private static final IntVector F_1_061594337 = IntVector.broadcast(I, 8697);

    /**
     * {@code -FIX(1.272758580)} in every lane.
     */
    private static final IntVector N_1_272758580 = IntVector.broadcast(I, -10426);

    /**
     * {@code FIX(1.451774981)} in every lane.
     */
    private static final IntVector F_1_451774981 = IntVector.broadcast(I, 11893);

    /**
     * {@code FIX(1.847759065)} in every lane.
     */
    private static final IntVector F_1_847759065 = IntVector.broadcast(I, FIX_1_847759065);

    /**
     * {@code -FIX(2.172734803)} in every lane.
     */
    private static final IntVector N_2_172734803 = IntVector.broadcast(I, -17799);

    /**
     * {@code FIX(2.562915447)} in every lane.
     */
    private static final IntVector F_2_562915447 = IntVector.broadcast(I, FIX_2_562915447);

    /**
     * {@code FIX(3.624509785)} in every lane.
     */
    private static final IntVector F_3_624509785 = IntVector.broadcast(I, 29692);

    /**
     * The rounding term of the 8x8 column pass.
     */
    private static final IntVector ROUND_COLUMN8 = IntVector.broadcast(I, 1 << (CONST_BITS - PASS1_BITS - 1));

    /**
     * The rounding term of the 4x4 column pass.
     */
    private static final IntVector ROUND_COLUMN4 = IntVector.broadcast(I, 1 << (CONST_BITS - PASS1_BITS));

    /**
     * The rounding term of the 2x2 column pass.
     */
    private static final IntVector ROUND_COLUMN2 = IntVector.broadcast(I, 1 << (CONST_BITS - PASS1_BITS + 1));

    /**
     * The rounding term of the 8x8 row pass.
     */
    private static final IntVector ROUND_ROW8 = IntVector.broadcast(I, 1 << (CONST_BITS + PASS1_BITS + 2));

    /**
     * The rounding term of the 4x4 row pass.
     */
    private static final IntVector ROUND_ROW4 = IntVector.broadcast(I, 1 << (CONST_BITS + PASS1_BITS + 3));

    /**
     * The rounding term of the 2x2 row pass.
     */
    private static final IntVector ROUND_ROW2 = IntVector.broadcast(I, 1 << (CONST_BITS + PASS1_BITS + 4));

    /**
     * The rounding term of a row whose only nonzero value is the first.
     */
    private static final IntVector ROUND_FLAT = IntVector.broadcast(I, 1 << (PASS1_BITS + 2));

    /**
     * The bias that moves a descaled sample's range-limit index so that masking to 10 bits and
     * subtracting {@link #LIMIT_OFFSET} reproduces the range-limit table before clamping.
     */
    private static final IntVector LIMIT_BIAS = IntVector.broadcast(I, 512);

    /**
     * The mask of the range-limit table index.
     */
    private static final IntVector LIMIT_MASK = IntVector.broadcast(I, 1023);

    /**
     * The offset subtracted from the biased, masked index.
     */
    private static final IntVector LIMIT_OFFSET = IntVector.broadcast(I, 384);

    /**
     * The largest sample value in every lane.
     */
    private static final IntVector MAX_SAMPLE = IntVector.broadcast(I, 255);

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
    private static final VectorMask<Integer> ODD_LANES = VectorMask.fromValues(I, false, true, false, true, false, true, false, true);

    /**
     * The exact class of the vectors of {@link #I}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(I);

    /**
     * The exact class of 256-bit byte vectors.
     */
    private static final Class<ByteVector> BYTE_VECTOR = vectorClass(ByteVector.SPECIES_256);

    /**
     * The exact class of the vectors of {@link #S}.
     */
    private static final Class<ShortVector> SHORT_VECTOR = vectorClass(S);

    /**
     * The exact class of 128-bit byte vectors.
     */
    private static final Class<ByteVector> SMALL_BYTE_VECTOR = vectorClass(ByteVector.SPECIES_128);

    /**
     * The scalar kernel, which writes batches of fewer than eight blocks.
     */
    private final ScalarBatchIdctKernel scalar;

    /**
     * Constructs the kernel.
     */
    VectorBatchIdctKernel() {
        this.scalar = new ScalarBatchIdctKernel();
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

    @Override
    public void idct8(int[] c, int count, byte[] output, int offset, int stride) {
        if (count < LANES) {
            scalar.idct8(c, count, output, offset, stride);
            return;
        }
        columns8(c);
        rows8(c, output, offset, stride);
    }

    /**
     * Runs the column pass of the 8x8 transform on every column of every block, in place.
     *
     * @param c the interleaved coefficients
     */
    private static void columns8(int[] c) {
        for (var col = 0; col < 8; col++) {
            column8(c, col * LANES);
        }
    }

    /**
     * Runs the column pass of the 8x8 transform on one column of every block, in place.
     *
     * @param c the interleaved coefficients
     * @param base the index of the column's first coefficient
     */
    private static void column8(int[] c, int base) {
        var in0 = INT_VECTOR.cast(IntVector.fromArray(I, c, base));
        var in1 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + ROW));
        var in2 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 2 * ROW));
        var in3 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 3 * ROW));
        var in4 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 4 * ROW));
        var in5 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 5 * ROW));
        var in6 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 6 * ROW));
        var in7 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 7 * ROW));
        var dcOnly = in1.lanewise(VectorOperators.OR, in2).lanewise(VectorOperators.OR, in3)
                .lanewise(VectorOperators.OR, in4).lanewise(VectorOperators.OR, in5)
                .lanewise(VectorOperators.OR, in6).lanewise(VectorOperators.OR, in7)
                .compare(VectorOperators.EQ, ZERO);
        var z1 = in2.lanewise(VectorOperators.ADD, in6).lanewise(VectorOperators.MUL, F_0_541196100);
        var tmp2 = z1.lanewise(VectorOperators.ADD, in6.lanewise(VectorOperators.MUL, N_1_847759065));
        var tmp3 = z1.lanewise(VectorOperators.ADD, in2.lanewise(VectorOperators.MUL, F_0_765366865));
        var tmp0 = INT_VECTOR.cast(in0.lanewise(VectorOperators.ADD, in4)).lanewise(VectorOperators.LSHL, CONST_BITS);
        var tmp1 = INT_VECTOR.cast(in0.lanewise(VectorOperators.SUB, in4)).lanewise(VectorOperators.LSHL, CONST_BITS);
        var tmp10 = tmp0.lanewise(VectorOperators.ADD, tmp3);
        var tmp13 = tmp0.lanewise(VectorOperators.SUB, tmp3);
        var tmp11 = tmp1.lanewise(VectorOperators.ADD, tmp2);
        var tmp12 = tmp1.lanewise(VectorOperators.SUB, tmp2);
        var z3 = in7.lanewise(VectorOperators.ADD, in3);
        var z4 = in5.lanewise(VectorOperators.ADD, in1);
        var z5 = z3.lanewise(VectorOperators.ADD, z4).lanewise(VectorOperators.MUL, F_1_175875602);
        var zo1 = in7.lanewise(VectorOperators.ADD, in1).lanewise(VectorOperators.MUL, N_0_899976223);
        var zo2 = in5.lanewise(VectorOperators.ADD, in3).lanewise(VectorOperators.MUL, N_2_562915447);
        z3 = z3.lanewise(VectorOperators.MUL, N_1_961570560).lanewise(VectorOperators.ADD, z5);
        z4 = z4.lanewise(VectorOperators.MUL, N_0_390180644).lanewise(VectorOperators.ADD, z5);
        var odd0 = in7.lanewise(VectorOperators.MUL, F_0_298631336).lanewise(VectorOperators.ADD, zo1)
                .lanewise(VectorOperators.ADD, z3);
        var odd1 = in5.lanewise(VectorOperators.MUL, F_2_053119869).lanewise(VectorOperators.ADD, zo2)
                .lanewise(VectorOperators.ADD, z4);
        var odd2 = in3.lanewise(VectorOperators.MUL, F_3_072711026).lanewise(VectorOperators.ADD, zo2)
                .lanewise(VectorOperators.ADD, z3);
        var odd3 = in1.lanewise(VectorOperators.MUL, F_1_501321110).lanewise(VectorOperators.ADD, zo1)
                .lanewise(VectorOperators.ADD, z4);
        var dc = INT_VECTOR.cast(in0).lanewise(VectorOperators.LSHL, PASS1_BITS);
        var shift = CONST_BITS - PASS1_BITS;
        tmp10 = tmp10.lanewise(VectorOperators.ADD, ROUND_COLUMN8);
        tmp11 = tmp11.lanewise(VectorOperators.ADD, ROUND_COLUMN8);
        tmp12 = tmp12.lanewise(VectorOperators.ADD, ROUND_COLUMN8);
        tmp13 = tmp13.lanewise(VectorOperators.ADD, ROUND_COLUMN8);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp10.lanewise(VectorOperators.ADD, odd3)).lanewise(VectorOperators.ASHR, shift)
                .blend(dc, dcOnly)).intoArray(c, base);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp10.lanewise(VectorOperators.SUB, odd3)).lanewise(VectorOperators.ASHR, shift)
                .blend(dc, dcOnly)).intoArray(c, base + 7 * ROW);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp11.lanewise(VectorOperators.ADD, odd2)).lanewise(VectorOperators.ASHR, shift)
                .blend(dc, dcOnly)).intoArray(c, base + ROW);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp11.lanewise(VectorOperators.SUB, odd2)).lanewise(VectorOperators.ASHR, shift)
                .blend(dc, dcOnly)).intoArray(c, base + 6 * ROW);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp12.lanewise(VectorOperators.ADD, odd1)).lanewise(VectorOperators.ASHR, shift)
                .blend(dc, dcOnly)).intoArray(c, base + 2 * ROW);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp12.lanewise(VectorOperators.SUB, odd1)).lanewise(VectorOperators.ASHR, shift)
                .blend(dc, dcOnly)).intoArray(c, base + 5 * ROW);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp13.lanewise(VectorOperators.ADD, odd0)).lanewise(VectorOperators.ASHR, shift)
                .blend(dc, dcOnly)).intoArray(c, base + 3 * ROW);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp13.lanewise(VectorOperators.SUB, odd0)).lanewise(VectorOperators.ASHR, shift)
                .blend(dc, dcOnly)).intoArray(c, base + 4 * ROW);
    }

    /**
     * Runs the row pass of the 8x8 transform on every row of every block, stores the samples and
     * clears the coefficients.
     *
     * @param c the interleaved column-pass results
     * @param output the destination samples
     * @param offset the index of the first block's top-left sample
     * @param stride the distance between output rows
     */
    private static void rows8(int[] c, byte[] output, int offset, int stride) {
        for (var row = 0; row < 8; row++) {
            row8(c, row * ROW);
            pack8(c, row * ROW, output, offset + row * stride);
        }
    }

    /**
     * Runs the row pass of the 8x8 transform on one row of every block and stores the row's
     * descaled samples, before range limiting, in place of its column-pass results: sample
     * {@code x} of every block at {@code base + x * LANES}.
     *
     * @param c the interleaved column-pass results
     * @param base the index of the row's first value
     */
    private static void row8(int[] c, int base) {
        var w0 = INT_VECTOR.cast(IntVector.fromArray(I, c, base));
        var w1 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + LANES));
        var w2 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 2 * LANES));
        var w3 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 3 * LANES));
        var w4 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 4 * LANES));
        var w5 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 5 * LANES));
        var w6 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 6 * LANES));
        var w7 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 7 * LANES));
        var flatRow = w1.lanewise(VectorOperators.OR, w2).lanewise(VectorOperators.OR, w3)
                .lanewise(VectorOperators.OR, w4).lanewise(VectorOperators.OR, w5)
                .lanewise(VectorOperators.OR, w6).lanewise(VectorOperators.OR, w7)
                .compare(VectorOperators.EQ, ZERO);
        var z1 = w2.lanewise(VectorOperators.ADD, w6).lanewise(VectorOperators.MUL, F_0_541196100);
        var tmp2 = z1.lanewise(VectorOperators.ADD, w6.lanewise(VectorOperators.MUL, N_1_847759065));
        var tmp3 = z1.lanewise(VectorOperators.ADD, w2.lanewise(VectorOperators.MUL, F_0_765366865));
        var tmp0 = INT_VECTOR.cast(w0.lanewise(VectorOperators.ADD, w4)).lanewise(VectorOperators.LSHL, CONST_BITS);
        var tmp1 = INT_VECTOR.cast(w0.lanewise(VectorOperators.SUB, w4)).lanewise(VectorOperators.LSHL, CONST_BITS);
        var tmp10 = tmp0.lanewise(VectorOperators.ADD, tmp3).lanewise(VectorOperators.ADD, ROUND_ROW8);
        var tmp13 = tmp0.lanewise(VectorOperators.SUB, tmp3).lanewise(VectorOperators.ADD, ROUND_ROW8);
        var tmp11 = tmp1.lanewise(VectorOperators.ADD, tmp2).lanewise(VectorOperators.ADD, ROUND_ROW8);
        var tmp12 = tmp1.lanewise(VectorOperators.SUB, tmp2).lanewise(VectorOperators.ADD, ROUND_ROW8);
        var z3 = w7.lanewise(VectorOperators.ADD, w3);
        var z4 = w5.lanewise(VectorOperators.ADD, w1);
        var z5 = z3.lanewise(VectorOperators.ADD, z4).lanewise(VectorOperators.MUL, F_1_175875602);
        var zo1 = w7.lanewise(VectorOperators.ADD, w1).lanewise(VectorOperators.MUL, N_0_899976223);
        var zo2 = w5.lanewise(VectorOperators.ADD, w3).lanewise(VectorOperators.MUL, N_2_562915447);
        z3 = z3.lanewise(VectorOperators.MUL, N_1_961570560).lanewise(VectorOperators.ADD, z5);
        z4 = z4.lanewise(VectorOperators.MUL, N_0_390180644).lanewise(VectorOperators.ADD, z5);
        var odd0 = w7.lanewise(VectorOperators.MUL, F_0_298631336).lanewise(VectorOperators.ADD, zo1)
                .lanewise(VectorOperators.ADD, z3);
        var odd1 = w5.lanewise(VectorOperators.MUL, F_2_053119869).lanewise(VectorOperators.ADD, zo2)
                .lanewise(VectorOperators.ADD, z4);
        var odd2 = w3.lanewise(VectorOperators.MUL, F_3_072711026).lanewise(VectorOperators.ADD, zo2)
                .lanewise(VectorOperators.ADD, z3);
        var odd3 = w1.lanewise(VectorOperators.MUL, F_1_501321110).lanewise(VectorOperators.ADD, zo1)
                .lanewise(VectorOperators.ADD, z4);
        var flat = INT_VECTOR.cast(w0.lanewise(VectorOperators.ADD, ROUND_FLAT))
                .lanewise(VectorOperators.ASHR, PASS1_BITS + 3);
        var shift = CONST_BITS + PASS1_BITS + 3;
        INT_VECTOR.cast(INT_VECTOR.cast(tmp10.lanewise(VectorOperators.ADD, odd3)).lanewise(VectorOperators.ASHR, shift)
                .blend(flat, flatRow)).intoArray(c, base);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp10.lanewise(VectorOperators.SUB, odd3)).lanewise(VectorOperators.ASHR, shift)
                .blend(flat, flatRow)).intoArray(c, base + 7 * LANES);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp11.lanewise(VectorOperators.ADD, odd2)).lanewise(VectorOperators.ASHR, shift)
                .blend(flat, flatRow)).intoArray(c, base + LANES);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp11.lanewise(VectorOperators.SUB, odd2)).lanewise(VectorOperators.ASHR, shift)
                .blend(flat, flatRow)).intoArray(c, base + 6 * LANES);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp12.lanewise(VectorOperators.ADD, odd1)).lanewise(VectorOperators.ASHR, shift)
                .blend(flat, flatRow)).intoArray(c, base + 2 * LANES);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp12.lanewise(VectorOperators.SUB, odd1)).lanewise(VectorOperators.ASHR, shift)
                .blend(flat, flatRow)).intoArray(c, base + 5 * LANES);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp13.lanewise(VectorOperators.ADD, odd0)).lanewise(VectorOperators.ASHR, shift)
                .blend(flat, flatRow)).intoArray(c, base + 3 * LANES);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp13.lanewise(VectorOperators.SUB, odd0)).lanewise(VectorOperators.ASHR, shift)
                .blend(flat, flatRow)).intoArray(c, base + 4 * LANES);
    }

    /**
     * Range-limits one row of descaled samples of every block, packs each block's eight samples
     * into two integers and stores the row of all eight blocks with two wide stores, then clears
     * the row's values.
     *
     * @param c the interleaved descaled samples
     * @param base the index of the row's first sample
     * @param output the destination samples
     * @param out the index of the first block's first sample of the row
     */
    private static void pack8(int[] c, int base, byte[] output, int out) {
        var x0 = INT_VECTOR.cast(IntVector.fromArray(I, c, base)).lanewise(VectorOperators.ADD, LIMIT_BIAS)
                .lanewise(VectorOperators.AND, LIMIT_MASK).lanewise(VectorOperators.SUB, LIMIT_OFFSET)
                .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
        var x1 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + LANES)).lanewise(VectorOperators.ADD, LIMIT_BIAS)
                .lanewise(VectorOperators.AND, LIMIT_MASK).lanewise(VectorOperators.SUB, LIMIT_OFFSET)
                .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
        var x2 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 2 * LANES)).lanewise(VectorOperators.ADD, LIMIT_BIAS)
                .lanewise(VectorOperators.AND, LIMIT_MASK).lanewise(VectorOperators.SUB, LIMIT_OFFSET)
                .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
        var x3 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 3 * LANES)).lanewise(VectorOperators.ADD, LIMIT_BIAS)
                .lanewise(VectorOperators.AND, LIMIT_MASK).lanewise(VectorOperators.SUB, LIMIT_OFFSET)
                .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
        var x4 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 4 * LANES)).lanewise(VectorOperators.ADD, LIMIT_BIAS)
                .lanewise(VectorOperators.AND, LIMIT_MASK).lanewise(VectorOperators.SUB, LIMIT_OFFSET)
                .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
        var x5 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 5 * LANES)).lanewise(VectorOperators.ADD, LIMIT_BIAS)
                .lanewise(VectorOperators.AND, LIMIT_MASK).lanewise(VectorOperators.SUB, LIMIT_OFFSET)
                .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
        var x6 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 6 * LANES)).lanewise(VectorOperators.ADD, LIMIT_BIAS)
                .lanewise(VectorOperators.AND, LIMIT_MASK).lanewise(VectorOperators.SUB, LIMIT_OFFSET)
                .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
        var x7 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 7 * LANES)).lanewise(VectorOperators.ADD, LIMIT_BIAS)
                .lanewise(VectorOperators.AND, LIMIT_MASK).lanewise(VectorOperators.SUB, LIMIT_OFFSET)
                .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
        var low = x0.lanewise(VectorOperators.OR, INT_VECTOR.cast(x1).lanewise(VectorOperators.LSHL, 8))
                .lanewise(VectorOperators.OR, INT_VECTOR.cast(x2).lanewise(VectorOperators.LSHL, 16))
                .lanewise(VectorOperators.OR, INT_VECTOR.cast(x3).lanewise(VectorOperators.LSHL, 24));
        var high = x4.lanewise(VectorOperators.OR, INT_VECTOR.cast(x5).lanewise(VectorOperators.LSHL, 8))
                .lanewise(VectorOperators.OR, INT_VECTOR.cast(x6).lanewise(VectorOperators.LSHL, 16))
                .lanewise(VectorOperators.OR, INT_VECTOR.cast(x7).lanewise(VectorOperators.LSHL, 24));
        BYTE_VECTOR.cast(INT_VECTOR.cast(low.rearrange(SPREAD_LOW).blend(high.rearrange(SPREAD_LOW), ODD_LANES))
                .reinterpretShape(ByteVector.SPECIES_256, 0)).intoArray(output, out);
        BYTE_VECTOR.cast(INT_VECTOR.cast(low.rearrange(SPREAD_HIGH).blend(high.rearrange(SPREAD_HIGH), ODD_LANES))
                .reinterpretShape(ByteVector.SPECIES_256, 0)).intoArray(output, out + 32);
        for (var x = 0; x < 8; x++) {
            ZERO.intoArray(c, base + x * LANES);
        }
    }

    @Override
    public void idct4(int[] c, int count, byte[] output, int offset, int stride) {
        if (count < LANES) {
            scalar.idct4(c, count, output, offset, stride);
            return;
        }
        columns4(c);
        rows4(c, output, offset, stride);
        Arrays.fill(c, 0, BATCH_LENGTH, 0);
    }

    /**
     * Runs the column pass of the 4x4 reduced transform on the columns the rows pass reads.
     *
     * @param c the interleaved coefficients
     */
    private static void columns4(int[] c) {
        for (var col = 0; col < 8; col++) {
            if (col != 4) {
                column4(c, col * LANES);
            }
        }
    }

    /**
     * Runs the column pass of the 4x4 reduced transform on one column of every block, in place.
     *
     * @param c the interleaved coefficients
     * @param base the index of the column's first coefficient
     */
    private static void column4(int[] c, int base) {
        var in0 = INT_VECTOR.cast(IntVector.fromArray(I, c, base));
        var in1 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + ROW));
        var in2 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 2 * ROW));
        var in3 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 3 * ROW));
        var in5 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 5 * ROW));
        var in6 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 6 * ROW));
        var in7 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 7 * ROW));
        var dcOnly = in1.lanewise(VectorOperators.OR, in2).lanewise(VectorOperators.OR, in3)
                .lanewise(VectorOperators.OR, in5).lanewise(VectorOperators.OR, in6)
                .lanewise(VectorOperators.OR, in7).compare(VectorOperators.EQ, ZERO);
        var even = INT_VECTOR.cast(in0).lanewise(VectorOperators.LSHL, CONST_BITS + 1)
                .lanewise(VectorOperators.ADD, ROUND_COLUMN4);
        var tmp2 = in2.lanewise(VectorOperators.MUL, F_1_847759065)
                .lanewise(VectorOperators.ADD, in6.lanewise(VectorOperators.MUL, N_0_765366865));
        var tmp10 = even.lanewise(VectorOperators.ADD, tmp2);
        var tmp12 = even.lanewise(VectorOperators.SUB, tmp2);
        var odd0 = in7.lanewise(VectorOperators.MUL, N_0_211164243)
                .lanewise(VectorOperators.ADD, in5.lanewise(VectorOperators.MUL, F_1_451774981))
                .lanewise(VectorOperators.ADD, in3.lanewise(VectorOperators.MUL, N_2_172734803))
                .lanewise(VectorOperators.ADD, in1.lanewise(VectorOperators.MUL, F_1_061594337));
        var odd2 = in7.lanewise(VectorOperators.MUL, N_0_509795579)
                .lanewise(VectorOperators.ADD, in5.lanewise(VectorOperators.MUL, N_0_601344887))
                .lanewise(VectorOperators.ADD, in3.lanewise(VectorOperators.MUL, F_0_899976223))
                .lanewise(VectorOperators.ADD, in1.lanewise(VectorOperators.MUL, F_2_562915447));
        var dc = INT_VECTOR.cast(in0).lanewise(VectorOperators.LSHL, PASS1_BITS);
        var shift = CONST_BITS - PASS1_BITS + 1;
        INT_VECTOR.cast(INT_VECTOR.cast(tmp10.lanewise(VectorOperators.ADD, odd2)).lanewise(VectorOperators.ASHR, shift)
                .blend(dc, dcOnly)).intoArray(c, base);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp10.lanewise(VectorOperators.SUB, odd2)).lanewise(VectorOperators.ASHR, shift)
                .blend(dc, dcOnly)).intoArray(c, base + 3 * ROW);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp12.lanewise(VectorOperators.ADD, odd0)).lanewise(VectorOperators.ASHR, shift)
                .blend(dc, dcOnly)).intoArray(c, base + ROW);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp12.lanewise(VectorOperators.SUB, odd0)).lanewise(VectorOperators.ASHR, shift)
                .blend(dc, dcOnly)).intoArray(c, base + 2 * ROW);
    }

    /**
     * Runs the row pass of the 4x4 reduced transform on every row of every block and stores the
     * samples.
     *
     * @param c the interleaved column-pass results
     * @param output the destination samples
     * @param offset the index of the first block's top-left sample
     * @param stride the distance between output rows
     */
    private static void rows4(int[] c, byte[] output, int offset, int stride) {
        for (var row = 0; row < 4; row++) {
            row4(c, row * ROW);
            pack4(c, row * ROW, output, offset + row * stride);
        }
    }

    /**
     * Runs the row pass of the 4x4 reduced transform on one row of every block and stores the
     * row's descaled samples, before range limiting, in place of its first four column-pass
     * results: sample {@code x} of every block at {@code base + x * LANES}.
     *
     * @param c the interleaved column-pass results
     * @param base the index of the row's first value
     */
    private static void row4(int[] c, int base) {
        var w0 = INT_VECTOR.cast(IntVector.fromArray(I, c, base));
        var w1 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + LANES));
        var w2 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 2 * LANES));
        var w3 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 3 * LANES));
        var w5 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 5 * LANES));
        var w6 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 6 * LANES));
        var w7 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 7 * LANES));
        var flatRow = w1.lanewise(VectorOperators.OR, w2).lanewise(VectorOperators.OR, w3)
                .lanewise(VectorOperators.OR, w5).lanewise(VectorOperators.OR, w6)
                .lanewise(VectorOperators.OR, w7).compare(VectorOperators.EQ, ZERO);
        var even = INT_VECTOR.cast(w0).lanewise(VectorOperators.LSHL, CONST_BITS + 1)
                .lanewise(VectorOperators.ADD, ROUND_ROW4);
        var tmp2 = w2.lanewise(VectorOperators.MUL, F_1_847759065)
                .lanewise(VectorOperators.ADD, w6.lanewise(VectorOperators.MUL, N_0_765366865));
        var tmp10 = even.lanewise(VectorOperators.ADD, tmp2);
        var tmp12 = even.lanewise(VectorOperators.SUB, tmp2);
        var odd0 = w7.lanewise(VectorOperators.MUL, N_0_211164243)
                .lanewise(VectorOperators.ADD, w5.lanewise(VectorOperators.MUL, F_1_451774981))
                .lanewise(VectorOperators.ADD, w3.lanewise(VectorOperators.MUL, N_2_172734803))
                .lanewise(VectorOperators.ADD, w1.lanewise(VectorOperators.MUL, F_1_061594337));
        var odd2 = w7.lanewise(VectorOperators.MUL, N_0_509795579)
                .lanewise(VectorOperators.ADD, w5.lanewise(VectorOperators.MUL, N_0_601344887))
                .lanewise(VectorOperators.ADD, w3.lanewise(VectorOperators.MUL, F_0_899976223))
                .lanewise(VectorOperators.ADD, w1.lanewise(VectorOperators.MUL, F_2_562915447));
        var flat = INT_VECTOR.cast(w0.lanewise(VectorOperators.ADD, ROUND_FLAT))
                .lanewise(VectorOperators.ASHR, PASS1_BITS + 3);
        var shift = CONST_BITS + PASS1_BITS + 3 + 1;
        INT_VECTOR.cast(INT_VECTOR.cast(tmp10.lanewise(VectorOperators.ADD, odd2)).lanewise(VectorOperators.ASHR, shift)
                .blend(flat, flatRow)).intoArray(c, base);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp10.lanewise(VectorOperators.SUB, odd2)).lanewise(VectorOperators.ASHR, shift)
                .blend(flat, flatRow)).intoArray(c, base + 3 * LANES);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp12.lanewise(VectorOperators.ADD, odd0)).lanewise(VectorOperators.ASHR, shift)
                .blend(flat, flatRow)).intoArray(c, base + LANES);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp12.lanewise(VectorOperators.SUB, odd0)).lanewise(VectorOperators.ASHR, shift)
                .blend(flat, flatRow)).intoArray(c, base + 2 * LANES);
    }

    /**
     * Range-limits one row of four descaled samples of every block, packs each block's samples
     * into one integer and stores the row of all eight blocks with one wide store.
     *
     * @param c the interleaved descaled samples
     * @param base the index of the row's first sample
     * @param output the destination samples
     * @param out the index of the first block's first sample of the row
     */
    private static void pack4(int[] c, int base, byte[] output, int out) {
        var x0 = INT_VECTOR.cast(IntVector.fromArray(I, c, base)).lanewise(VectorOperators.ADD, LIMIT_BIAS)
                .lanewise(VectorOperators.AND, LIMIT_MASK).lanewise(VectorOperators.SUB, LIMIT_OFFSET)
                .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
        var x1 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + LANES)).lanewise(VectorOperators.ADD, LIMIT_BIAS)
                .lanewise(VectorOperators.AND, LIMIT_MASK).lanewise(VectorOperators.SUB, LIMIT_OFFSET)
                .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
        var x2 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 2 * LANES)).lanewise(VectorOperators.ADD, LIMIT_BIAS)
                .lanewise(VectorOperators.AND, LIMIT_MASK).lanewise(VectorOperators.SUB, LIMIT_OFFSET)
                .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
        var x3 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 3 * LANES)).lanewise(VectorOperators.ADD, LIMIT_BIAS)
                .lanewise(VectorOperators.AND, LIMIT_MASK).lanewise(VectorOperators.SUB, LIMIT_OFFSET)
                .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
        BYTE_VECTOR.cast(INT_VECTOR.cast(x0.lanewise(VectorOperators.OR, INT_VECTOR.cast(x1)
                .lanewise(VectorOperators.LSHL, 8))
                .lanewise(VectorOperators.OR, INT_VECTOR.cast(x2).lanewise(VectorOperators.LSHL, 16))
                .lanewise(VectorOperators.OR, INT_VECTOR.cast(x3).lanewise(VectorOperators.LSHL, 24)))
                .reinterpretShape(ByteVector.SPECIES_256, 0)).intoArray(output, out);
    }

    @Override
    public void idct2(int[] c, int count, byte[] output, int offset, int stride) {
        if (count < LANES) {
            scalar.idct2(c, count, output, offset, stride);
            return;
        }
        columns2(c);
        row2(c, 0, output, offset);
        row2(c, ROW, output, offset + stride);
        Arrays.fill(c, 0, BATCH_LENGTH, 0);
    }

    /**
     * Runs the column pass of the 2x2 reduced transform on the columns the rows pass reads.
     *
     * @param c the interleaved coefficients
     */
    private static void columns2(int[] c) {
        column2(c, 0);
        for (var col = 1; col < 8; col += 2) {
            column2(c, col * LANES);
        }
    }

    /**
     * Runs the column pass of the 2x2 reduced transform on one column of every block, in place.
     *
     * @param c the interleaved coefficients
     * @param base the index of the column's first coefficient
     */
    private static void column2(int[] c, int base) {
        var in0 = INT_VECTOR.cast(IntVector.fromArray(I, c, base));
        var in1 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + ROW));
        var in3 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 3 * ROW));
        var in5 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 5 * ROW));
        var in7 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 7 * ROW));
        var dcOnly = in1.lanewise(VectorOperators.OR, in3).lanewise(VectorOperators.OR, in5)
                .lanewise(VectorOperators.OR, in7).compare(VectorOperators.EQ, ZERO);
        var tmp10 = INT_VECTOR.cast(in0).lanewise(VectorOperators.LSHL, CONST_BITS + 2)
                .lanewise(VectorOperators.ADD, ROUND_COLUMN2);
        var tmp0 = in7.lanewise(VectorOperators.MUL, N_0_720959822)
                .lanewise(VectorOperators.ADD, in5.lanewise(VectorOperators.MUL, F_0_850430095))
                .lanewise(VectorOperators.ADD, in3.lanewise(VectorOperators.MUL, N_1_272758580))
                .lanewise(VectorOperators.ADD, in1.lanewise(VectorOperators.MUL, F_3_624509785));
        var dc = INT_VECTOR.cast(in0).lanewise(VectorOperators.LSHL, PASS1_BITS);
        var shift = CONST_BITS - PASS1_BITS + 2;
        INT_VECTOR.cast(INT_VECTOR.cast(tmp10.lanewise(VectorOperators.ADD, tmp0)).lanewise(VectorOperators.ASHR, shift)
                .blend(dc, dcOnly)).intoArray(c, base);
        INT_VECTOR.cast(INT_VECTOR.cast(tmp10.lanewise(VectorOperators.SUB, tmp0)).lanewise(VectorOperators.ASHR, shift)
                .blend(dc, dcOnly)).intoArray(c, base + ROW);
    }

    /**
     * Runs the row pass of the 2x2 reduced transform on one row of every block and stores the
     * row's samples, two bytes per block.
     *
     * @param c the interleaved column-pass results
     * @param base the index of the row's first value
     * @param output the destination samples
     * @param out the index of the first block's first sample of the row
     */
    private static void row2(int[] c, int base, byte[] output, int out) {
        var w0 = INT_VECTOR.cast(IntVector.fromArray(I, c, base));
        var w1 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + LANES));
        var w3 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 3 * LANES));
        var w5 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 5 * LANES));
        var w7 = INT_VECTOR.cast(IntVector.fromArray(I, c, base + 7 * LANES));
        var flatRow = w1.lanewise(VectorOperators.OR, w3).lanewise(VectorOperators.OR, w5)
                .lanewise(VectorOperators.OR, w7).compare(VectorOperators.EQ, ZERO);
        var tmp10 = INT_VECTOR.cast(w0).lanewise(VectorOperators.LSHL, CONST_BITS + 2)
                .lanewise(VectorOperators.ADD, ROUND_ROW2);
        var tmp0 = w7.lanewise(VectorOperators.MUL, N_0_720959822)
                .lanewise(VectorOperators.ADD, w5.lanewise(VectorOperators.MUL, F_0_850430095))
                .lanewise(VectorOperators.ADD, w3.lanewise(VectorOperators.MUL, N_1_272758580))
                .lanewise(VectorOperators.ADD, w1.lanewise(VectorOperators.MUL, F_3_624509785));
        var flat = INT_VECTOR.cast(w0.lanewise(VectorOperators.ADD, ROUND_FLAT))
                .lanewise(VectorOperators.ASHR, PASS1_BITS + 3);
        var shift = CONST_BITS + PASS1_BITS + 3 + 2;
        var x0 = INT_VECTOR.cast(tmp10.lanewise(VectorOperators.ADD, tmp0)).lanewise(VectorOperators.ASHR, shift)
                .blend(flat, flatRow)
                .lanewise(VectorOperators.ADD, LIMIT_BIAS).lanewise(VectorOperators.AND, LIMIT_MASK)
                .lanewise(VectorOperators.SUB, LIMIT_OFFSET).lanewise(VectorOperators.MAX, ZERO)
                .lanewise(VectorOperators.MIN, MAX_SAMPLE);
        var x1 = INT_VECTOR.cast(tmp10.lanewise(VectorOperators.SUB, tmp0)).lanewise(VectorOperators.ASHR, shift)
                .blend(flat, flatRow)
                .lanewise(VectorOperators.ADD, LIMIT_BIAS).lanewise(VectorOperators.AND, LIMIT_MASK)
                .lanewise(VectorOperators.SUB, LIMIT_OFFSET).lanewise(VectorOperators.MAX, ZERO)
                .lanewise(VectorOperators.MIN, MAX_SAMPLE);
        SMALL_BYTE_VECTOR.cast(SHORT_VECTOR.cast(x0.lanewise(VectorOperators.OR, INT_VECTOR.cast(x1)
                .lanewise(VectorOperators.LSHL, 8))
                .convertShape(VectorOperators.I2S, S, 0)).reinterpretShape(ByteVector.SPECIES_128, 0)).intoArray(output, out);
    }
}
