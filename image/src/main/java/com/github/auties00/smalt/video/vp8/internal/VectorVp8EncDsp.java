/* Ports libwebp 1.6.0 (src/dsp/enc.c: the arithmetic of FTransform_C, ITransformOne_C, TTransform_C, QuantizeBlock_C,
   SSE4x4_C). Copyright (c) 2010, Google Inc.; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Implements {@link Vp8EncDsp} with the Vector API; the results equal {@link ScalarVp8EncDsp}'s.
 *
 * @implNote This implementation computes in 32-bit lanes, where every intermediate value fits,
 * with a layout of its own rather than libwebp's SSE2 one, since the Vector API has no 16-bit
 * high-half multiply and no cheap interleaving unpacks. The lane layouts and the rules that keep
 * the kernels compiled are described at the top of the class body.
 */
public final class VectorVp8EncDsp extends Vp8EncDsp {
    /*
     * A 4x4 block is held in two 8-lane 32-bit vectors, each carrying two rows. Vertical butterflies
     * combine the two vectors lane by lane after swapping their 128-bit halves; horizontal butterflies
     * combine lanes of one row through constant lane permutations within each 4-lane group, so no
     * transpose is needed. tDisto4x4 carries the same row of both blocks in one vector and leaves the
     * transformed coefficients in a permuted order, which the weights follow.
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
     * The 8-lane int species of two block rows.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * The 4-lane int species of one block row.
     */
    private static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;

    /**
     * The 2-lane int species used to gather two 4-byte rows.
     */
    private static final VectorSpecies<Integer> I64 = IntVector.SPECIES_64;

    /**
     * The 8-lane byte species of pixel runs.
     */
    private static final VectorSpecies<Byte> B64 = ByteVector.SPECIES_64;

    /**
     * The 8-lane short species of coefficient pairs of rows.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * The 16-lane short species of one block of levels.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * The exact class of 8-lane byte vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<ByteVector> BYTES_8 = (Class<ByteVector>) B64.vectorType();

    /**
     * The exact class of 2-lane int vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<IntVector> INTS_2 = (Class<IntVector>) I64.vectorType();

    /**
     * The exact class of 4-lane int vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<IntVector> INTS_4 = (Class<IntVector>) I128.vectorType();

    /**
     * The exact class of 8-lane int vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<IntVector> INTS_8 = (Class<IntVector>) I256.vectorType();

    /**
     * The exact class of 8-lane short vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<ShortVector> SHORTS_8 = (Class<ShortVector>) S128.vectorType();

    /**
     * The exact class of 16-lane short vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<ShortVector> SHORTS_16 = (Class<ShortVector>) S256.vectorType();

    /**
     * Eight ints equal to {@code 1}.
     */
    private static final IntVector I256_1 = IntVector.broadcast(I256, 1);

    /**
     * Eight ints equal to {@code 4}.
     */
    private static final IntVector I256_4 = IntVector.broadcast(I256, 4);

    /**
     * Eight ints equal to {@code 7}.
     */
    private static final IntVector I256_7 = IntVector.broadcast(I256, 7);

    /**
     * Eight ints equal to {@code 255}.
     */
    private static final IntVector I256_255 = IntVector.broadcast(I256, 255);

    /**
     * Eight ints equal to {@code 2217}.
     */
    private static final IntVector I256_8A9 = IntVector.broadcast(I256, 2217);

    /**
     * Eight ints equal to {@code 20091}.
     */
    private static final IntVector I256_4E7B = IntVector.broadcast(I256, 20091);

    /**
     * Eight ints equal to {@code 35468}.
     */
    private static final IntVector I256_8A8C = IntVector.broadcast(I256, 35468);

    /**
     * Eight shorts equal to {@code 0}.
     */
    private static final ShortVector S128_0 = ShortVector.broadcast(S128, (short) 0);

    /**
     * Sixteen shorts equal to {@code 0}.
     */
    private static final ShortVector S256_0 = ShortVector.broadcast(S256, (short) 0);

    /**
     * Eight zero ints, the receiver that turns a loop's accumulator back into a plain operand.
     */
    private static final IntVector ZERO_I256 = IntVector.zero(I256);

    /**
     * Reads and writes four bytes of a byte array as a little-endian {@code int}.
     */
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Reverses the lanes of each 4-lane group.
     */
    private static final VectorShuffle<Integer> REVERSE = VectorShuffle.fromValues(I256, 3, 2, 1, 0, 7, 6, 5, 4);

    /**
     * Swaps adjacent lanes of each 4-lane group.
     */
    private static final VectorShuffle<Integer> SWAP1 = VectorShuffle.fromValues(I256, 1, 0, 3, 2, 5, 4, 7, 6);

    /**
     * Swaps the lane pairs of each 4-lane group.
     */
    private static final VectorShuffle<Integer> SWAP2 = VectorShuffle.fromValues(I256, 2, 3, 0, 1, 6, 7, 4, 5);

    /**
     * Swaps the two 4-lane halves.
     */
    private static final VectorShuffle<Integer> SWAP_HALVES = VectorShuffle.fromValues(I256, 4, 5, 6, 7, 0, 1, 2, 3);

    /**
     * Broadcasts lane 0 of each 4-lane group.
     */
    private static final VectorShuffle<Integer> LANE0 = VectorShuffle.fromValues(I256, 0, 0, 0, 0, 4, 4, 4, 4);

    /**
     * Broadcasts lane 1 of each 4-lane group.
     */
    private static final VectorShuffle<Integer> LANE1 = VectorShuffle.fromValues(I256, 1, 1, 1, 1, 5, 5, 5, 5);

    /**
     * Broadcasts lane 2 of each 4-lane group.
     */
    private static final VectorShuffle<Integer> LANE2 = VectorShuffle.fromValues(I256, 2, 2, 2, 2, 6, 6, 6, 6);

    /**
     * Broadcasts lane 3 of each 4-lane group.
     */
    private static final VectorShuffle<Integer> LANE3 = VectorShuffle.fromValues(I256, 3, 3, 3, 3, 7, 7, 7, 7);

    /**
     * Orders four frequency weights like the outputs of the horizontal butterflies of
     * {@link #tDisto4x4}.
     */
    private static final VectorShuffle<Integer> WEIGHT_ORDER = VectorShuffle.fromValues(I128, 0, 3, 1, 2);

    /**
     * Orders the weights of two block rows like the outputs of the horizontal Hadamard
     * butterflies of {@link #spectralSum}: {@code 0, 3, 1, 2} within each 4-lane group.
     */
    private static final VectorShuffle<Integer> WEIGHT_ORDER_2 = VectorShuffle.fromValues(I256, 0, 3, 1, 2, 4, 7, 5, 6);

    /**
     * Selects the odd lanes.
     */
    private static final VectorMask<Integer> ODD = VectorMask.fromValues(I256, false, true, false, true, false, true, false, true);

    /**
     * Selects the two middle lanes of each 4-lane group.
     */
    private static final VectorMask<Integer> MIDDLE = VectorMask.fromValues(I256, false, true, true, false, false, true, true, false);

    /**
     * Selects the upper two lanes of each 4-lane group.
     */
    private static final VectorMask<Integer> UPPER_PAIR = VectorMask.fromValues(I256, false, false, true, true, false, false, true, true);

    /**
     * Selects the upper 4-lane half.
     */
    private static final VectorMask<Integer> HIGH = VectorMask.fromValues(I256, false, false, false, false, true, true, true, true);

    /**
     * Selects the lower 4-lane half.
     */
    private static final VectorMask<Integer> LOW = VectorMask.fromValues(I256, true, true, true, true, false, false, false, false);

    /**
     * Multiplies the first operand of the forward transform's horizontal pass; the even outputs are
     * scaled by 512 so that all four share the final shift by 9.
     */
    private static final int[] F_K1 = {4096, 2217, -4096, -2217, 4096, 2217, -4096, -2217};

    /**
     * Multiplies the second operand of the forward transform's horizontal pass.
     */
    private static final int[] F_K2 = {4096, 5352, 4096, 5352, 4096, 5352, 4096, 5352};

    /**
     * Holds the rounding terms of the forward transform's horizontal pass.
     */
    private static final int[] F_R1 = {0, 1812, 0, 937, 0, 1812, 0, 937};

    /**
     * Multiplies the vertical differences of the forward transform's odd outputs.
     */
    private static final int[] F_K3 = {5352, 5352, 5352, 5352, -5352, -5352, -5352, -5352};

    /**
     * Holds the rounding terms of the forward transform's odd vertical outputs.
     */
    private static final int[] F_R2 = {12000, 12000, 12000, 12000, 51000, 51000, 51000, 51000};

    /**
     * Holds the raster positions of zigzag positions 0 to 7, relative to the first 8 coefficients.
     */
    private static final VectorShuffle<Short> ZIGZAG_LOW_A = VectorShuffle.fromValues(S128, 0, 1, 4, 0, 5, 2, 3, 6);

    /**
     * Holds the raster position of zigzag position 3, relative to the last 8 coefficients.
     */
    private static final VectorShuffle<Short> ZIGZAG_LOW_B = VectorShuffle.fromValues(S128, 0, 0, 0, 0, 0, 0, 0, 0);

    /**
     * Selects the zigzag positions 0 to 7 taken from the last 8 coefficients.
     */
    private static final VectorMask<Short> ZIGZAG_LOW_MASK = VectorMask.fromValues(S128, false, false, false, true, false, false, false, false);

    /**
     * Holds the raster position of zigzag position 12, relative to the first 8 coefficients.
     */
    private static final VectorShuffle<Short> ZIGZAG_HIGH_A = VectorShuffle.fromValues(S128, 7, 7, 7, 7, 7, 7, 7, 7);

    /**
     * Holds the raster positions of zigzag positions 8 to 15, relative to the last 8 coefficients.
     */
    private static final VectorShuffle<Short> ZIGZAG_HIGH_B = VectorShuffle.fromValues(S128, 1, 4, 5, 2, 0, 3, 6, 7);

    /**
     * Selects the zigzag positions 8 to 15 taken from the first 8 coefficients.
     */
    private static final VectorMask<Short> ZIGZAG_HIGH_MASK = VectorMask.fromValues(S128, false, false, false, false, true, false, false, false);

    /**
     * The scalar kernels used for block widths without a vector path; 4-pixel rows are too short for
     * vectors to pay off.
     */
    private final ScalarVp8EncDsp scalar = new ScalarVp8EncDsp();

    /**
     * Creates the vector implementation.
     */
    private VectorVp8EncDsp() {
    }

    /**
     * Creates the vector implementation; called only when the Vector API is available.
     *
     * @return the implementation
     */
    public static Vp8EncDsp create() {
        return new VectorVp8EncDsp();
    }

    @Override
    public void fTransform(byte[] src, int srcOff, byte[] ref, int refOff, short[] out, int outOff) {
        var s01 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(src, srcOff))
                .withLane(1, (int) INT.get(src, srcOff + BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var p01 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(ref, refOff))
                .withLane(1, (int) INT.get(ref, refOff + BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var s23 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(src, srcOff + 2 * BPS))
                .withLane(1, (int) INT.get(src, srcOff + 3 * BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var p23 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(ref, refOff + 2 * BPS))
                .withLane(1, (int) INT.get(ref, refOff + 3 * BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var k1 = INTS_8.cast(IntVector.fromArray(I256, F_K1, 0));
        var k2 = INTS_8.cast(IntVector.fromArray(I256, F_K2, 0));
        var r1 = INTS_8.cast(IntVector.fromArray(I256, F_R1, 0));
        var d01 = s01.lanewise(VectorOperators.SUB, p01);
        var rev01 = d01.rearrange(REVERSE);
        var sum01 = d01.lanewise(VectorOperators.ADD, rev01);
        var dif01 = d01.lanewise(VectorOperators.SUB, rev01);
        var t01 = sum01.blend(dif01, ODD).lanewise(VectorOperators.MUL, k1)
                .lanewise(VectorOperators.ADD, dif01.blend(sum01, ODD).rearrange(SWAP1).lanewise(VectorOperators.MUL, k2))
                .lanewise(VectorOperators.ADD, r1).lanewise(VectorOperators.ASHR, 9);
        var d23 = s23.lanewise(VectorOperators.SUB, p23);
        var rev23 = d23.rearrange(REVERSE);
        var sum23 = d23.lanewise(VectorOperators.ADD, rev23);
        var dif23 = d23.lanewise(VectorOperators.SUB, rev23);
        var t23 = sum23.blend(dif23, ODD).lanewise(VectorOperators.MUL, k1)
                .lanewise(VectorOperators.ADD, dif23.blend(sum23, ODD).rearrange(SWAP1).lanewise(VectorOperators.MUL, k2))
                .lanewise(VectorOperators.ADD, r1).lanewise(VectorOperators.ASHR, 9);
        var swapped23 = t23.rearrange(SWAP_HALVES);
        var a = t01.lanewise(VectorOperators.ADD, swapped23);
        var b = t01.lanewise(VectorOperators.SUB, swapped23);
        var swappedA = a.rearrange(SWAP_HALVES);
        var even = a.lanewise(VectorOperators.ADD, swappedA).lanewise(VectorOperators.ADD, I256_7).lanewise(VectorOperators.ASHR, 4);
        var evenDiff = a.lanewise(VectorOperators.SUB, swappedA).lanewise(VectorOperators.ADD, I256_7).lanewise(VectorOperators.ASHR, 4);
        var odd = b.lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, F_K3, 0)))
                .lanewise(VectorOperators.ADD, b.rearrange(SWAP_HALVES).lanewise(VectorOperators.MUL, I256_8A9))
                .lanewise(VectorOperators.ADD, INTS_8.cast(IntVector.fromArray(I256, F_R2, 0)))
                .lanewise(VectorOperators.ASHR, 16)
                .lanewise(VectorOperators.ADD, I256_1, b.compare(VectorOperators.NE, ZERO_I256).and(LOW));
        SHORTS_8.cast(even.blend(odd.rearrange(SWAP_HALVES), HIGH).convertShape(VectorOperators.I2S, S128, 0))
                .intoArray(out, outOff);
        SHORTS_8.cast(evenDiff.blend(odd, HIGH).convertShape(VectorOperators.I2S, S128, 0))
                .intoArray(out, outOff + 8);
    }

    @Override
    public void iTransform(byte[] ref, int refOff, short[] in, int inOff, byte[] dst, int dstOff) {
        var v01 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, inOff)).convertShape(VectorOperators.S2I, I256, 0));
        var v23 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, inOff + 8)).convertShape(VectorOperators.S2I, I256, 0));
        var sum = v01.lanewise(VectorOperators.ADD, v23);
        var dif = v01.lanewise(VectorOperators.SUB, v23);
        var c = v01.lanewise(VectorOperators.MUL, I256_8A8C).lanewise(VectorOperators.ASHR, 16)
                .lanewise(VectorOperators.SUB, v23.lanewise(VectorOperators.MUL, I256_4E7B).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, v23));
        var d = v01.lanewise(VectorOperators.MUL, I256_4E7B).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, v01)
                .lanewise(VectorOperators.ADD, v23.lanewise(VectorOperators.MUL, I256_8A8C).lanewise(VectorOperators.ASHR, 16));
        var ab = sum.blend(dif.rearrange(SWAP_HALVES), HIGH);
        var dc = d.rearrange(SWAP_HALVES).blend(c, HIGH);
        var t01 = ab.lanewise(VectorOperators.ADD, dc);
        var t23 = ab.lanewise(VectorOperators.SUB, dc).rearrange(SWAP_HALVES);

        var e0 = t01.rearrange(LANE0);
        var e1 = t01.rearrange(LANE1);
        var e2 = t01.rearrange(LANE2);
        var e3 = t01.rearrange(LANE3);
        var ab01 = e0.lanewise(VectorOperators.ADD, e2).blend(e0.lanewise(VectorOperators.SUB, e2), MIDDLE);
        var dc01 = e1.lanewise(VectorOperators.MUL, I256_4E7B).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, e1)
                .lanewise(VectorOperators.ADD, e3.lanewise(VectorOperators.MUL, I256_8A8C).lanewise(VectorOperators.ASHR, 16))
                .blend(e1.lanewise(VectorOperators.MUL, I256_8A8C).lanewise(VectorOperators.ASHR, 16)
                        .lanewise(VectorOperators.SUB, e3.lanewise(VectorOperators.MUL, I256_4E7B).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, e3)), MIDDLE);
        var res01 = ab01.lanewise(VectorOperators.ADD, dc01).blend(ab01.lanewise(VectorOperators.SUB, dc01), UPPER_PAIR).lanewise(VectorOperators.ADD, I256_4).lanewise(VectorOperators.ASHR, 3)
                .lanewise(VectorOperators.ADD, INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(ref, refOff))
                        .withLane(1, (int) INT.get(ref, refOff + BPS)).reinterpretShape(B64, 0))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0)))
                .lanewise(VectorOperators.MAX, ZERO_I256).lanewise(VectorOperators.MIN, I256_255);
        var out01 = INTS_2.cast(BYTES_8.cast(res01.convertShape(VectorOperators.I2B, B64, 0)).reinterpretShape(I64, 0));
        INT.set(dst, dstOff, out01.lane(0));
        INT.set(dst, dstOff + BPS, out01.lane(1));

        var g0 = t23.rearrange(LANE0);
        var g1 = t23.rearrange(LANE1);
        var g2 = t23.rearrange(LANE2);
        var g3 = t23.rearrange(LANE3);
        var ab23 = g0.lanewise(VectorOperators.ADD, g2).blend(g0.lanewise(VectorOperators.SUB, g2), MIDDLE);
        var dc23 = g1.lanewise(VectorOperators.MUL, I256_4E7B).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, g1)
                .lanewise(VectorOperators.ADD, g3.lanewise(VectorOperators.MUL, I256_8A8C).lanewise(VectorOperators.ASHR, 16))
                .blend(g1.lanewise(VectorOperators.MUL, I256_8A8C).lanewise(VectorOperators.ASHR, 16)
                        .lanewise(VectorOperators.SUB, g3.lanewise(VectorOperators.MUL, I256_4E7B).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, g3)), MIDDLE);
        var res23 = ab23.lanewise(VectorOperators.ADD, dc23).blend(ab23.lanewise(VectorOperators.SUB, dc23), UPPER_PAIR).lanewise(VectorOperators.ADD, I256_4).lanewise(VectorOperators.ASHR, 3)
                .lanewise(VectorOperators.ADD, INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(ref, refOff + 2 * BPS))
                        .withLane(1, (int) INT.get(ref, refOff + 3 * BPS)).reinterpretShape(B64, 0))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0)))
                .lanewise(VectorOperators.MAX, ZERO_I256).lanewise(VectorOperators.MIN, I256_255);
        var out23 = INTS_2.cast(BYTES_8.cast(res23.convertShape(VectorOperators.I2B, B64, 0)).reinterpretShape(I64, 0));
        INT.set(dst, dstOff + 2 * BPS, out23.lane(0));
        INT.set(dst, dstOff + 3 * BPS, out23.lane(1));
    }

    @Override
    public int sse(byte[] a, int aOff, byte[] b, int bOff, int width, int height) {
        if (width == 16 || width == 8) {
            var acc = INTS_8.cast(IntVector.zero(I256));
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x += 8) {
                    var va = INTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, a, aOff + y * BPS + x))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
                    var vb = INTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, b, bOff + y * BPS + x))
                            .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
                    var diff = va.lanewise(VectorOperators.SUB, vb);
                    acc = diff.lanewise(VectorOperators.MUL, diff).lanewise(VectorOperators.ADD, acc);
                }
            }
            return ZERO_I256.lanewise(VectorOperators.ADD, acc).reduceLanes(VectorOperators.ADD);
        }
        return scalar.sse(a, aOff, b, bOff, width, height);
    }

    @Override
    public int tDisto4x4(byte[] a, int aOff, byte[] b, int bOff, int[] weights) {
        var r0 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(a, aOff))
                .withLane(1, (int) INT.get(b, bOff)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var r1 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(a, aOff + BPS))
                .withLane(1, (int) INT.get(b, bOff + BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var r2 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(a, aOff + 2 * BPS))
                .withLane(1, (int) INT.get(b, bOff + 2 * BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var r3 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(a, aOff + 3 * BPS))
                .withLane(1, (int) INT.get(b, bOff + 3 * BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var a0 = r0.lanewise(VectorOperators.ADD, r2);
        var a1 = r1.lanewise(VectorOperators.ADD, r3);
        var a2 = r1.lanewise(VectorOperators.SUB, r3);
        var a3 = r0.lanewise(VectorOperators.SUB, r2);

        var v = a0.lanewise(VectorOperators.ADD, a1);
        var s = v.rearrange(SWAP2);
        var u = v.lanewise(VectorOperators.ADD, s).blend(s.lanewise(VectorOperators.SUB, v), UPPER_PAIR);
        var t = u.rearrange(SWAP1);
        var w = u.lanewise(VectorOperators.ADD, t).blend(u.lanewise(VectorOperators.SUB, t), ODD).lanewise(VectorOperators.ABS);
        var acc = INTS_4.cast(w.rearrange(SWAP_HALVES).lanewise(VectorOperators.SUB, w).reinterpretShape(I128, 0))
                .lanewise(VectorOperators.MUL, INTS_4.cast(IntVector.fromArray(I128, weights, 0)).rearrange(WEIGHT_ORDER));

        v = a3.lanewise(VectorOperators.ADD, a2);
        s = v.rearrange(SWAP2);
        u = v.lanewise(VectorOperators.ADD, s).blend(s.lanewise(VectorOperators.SUB, v), UPPER_PAIR);
        t = u.rearrange(SWAP1);
        w = u.lanewise(VectorOperators.ADD, t).blend(u.lanewise(VectorOperators.SUB, t), ODD).lanewise(VectorOperators.ABS);
        acc = acc.lanewise(VectorOperators.ADD, INTS_4.cast(w.rearrange(SWAP_HALVES).lanewise(VectorOperators.SUB, w).reinterpretShape(I128, 0))
                .lanewise(VectorOperators.MUL, INTS_4.cast(IntVector.fromArray(I128, weights, 4)).rearrange(WEIGHT_ORDER)));

        v = a3.lanewise(VectorOperators.SUB, a2);
        s = v.rearrange(SWAP2);
        u = v.lanewise(VectorOperators.ADD, s).blend(s.lanewise(VectorOperators.SUB, v), UPPER_PAIR);
        t = u.rearrange(SWAP1);
        w = u.lanewise(VectorOperators.ADD, t).blend(u.lanewise(VectorOperators.SUB, t), ODD).lanewise(VectorOperators.ABS);
        acc = acc.lanewise(VectorOperators.ADD, INTS_4.cast(w.rearrange(SWAP_HALVES).lanewise(VectorOperators.SUB, w).reinterpretShape(I128, 0))
                .lanewise(VectorOperators.MUL, INTS_4.cast(IntVector.fromArray(I128, weights, 8)).rearrange(WEIGHT_ORDER)));

        v = a0.lanewise(VectorOperators.SUB, a1);
        s = v.rearrange(SWAP2);
        u = v.lanewise(VectorOperators.ADD, s).blend(s.lanewise(VectorOperators.SUB, v), UPPER_PAIR);
        t = u.rearrange(SWAP1);
        w = u.lanewise(VectorOperators.ADD, t).blend(u.lanewise(VectorOperators.SUB, t), ODD).lanewise(VectorOperators.ABS);
        acc = acc.lanewise(VectorOperators.ADD, INTS_4.cast(w.rearrange(SWAP_HALVES).lanewise(VectorOperators.SUB, w).reinterpretShape(I128, 0))
                .lanewise(VectorOperators.MUL, INTS_4.cast(IntVector.fromArray(I128, weights, 12)).rearrange(WEIGHT_ORDER)));
        return Math.abs(acc.reduceLanes(VectorOperators.ADD)) >> 5;
    }

    @Override
    public int spectralSum(byte[] a, int aOff, int[] weights) {
        var v01 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(a, aOff))
                .withLane(1, (int) INT.get(a, aOff + BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var v23 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(a, aOff + 2 * BPS))
                .withLane(1, (int) INT.get(a, aOff + 3 * BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var sum = v01.lanewise(VectorOperators.ADD, v23);
        var dif = v01.lanewise(VectorOperators.SUB, v23);
        var sumSwapped = sum.rearrange(SWAP_HALVES);
        var difSwapped = dif.rearrange(SWAP_HALVES);
        var rows01 = sum.lanewise(VectorOperators.ADD, sumSwapped).blend(dif.lanewise(VectorOperators.ADD, difSwapped), HIGH);
        var rows23 = dif.lanewise(VectorOperators.SUB, difSwapped).blend(sum.lanewise(VectorOperators.SUB, sumSwapped), HIGH);
        var s = rows01.rearrange(SWAP2);
        var u = rows01.lanewise(VectorOperators.ADD, s).blend(s.lanewise(VectorOperators.SUB, rows01), UPPER_PAIR);
        var t = u.rearrange(SWAP1);
        var h01 = u.lanewise(VectorOperators.ADD, t).blend(u.lanewise(VectorOperators.SUB, t), ODD).lanewise(VectorOperators.ABS);
        s = rows23.rearrange(SWAP2);
        u = rows23.lanewise(VectorOperators.ADD, s).blend(s.lanewise(VectorOperators.SUB, rows23), UPPER_PAIR);
        t = u.rearrange(SWAP1);
        var h23 = u.lanewise(VectorOperators.ADD, t).blend(u.lanewise(VectorOperators.SUB, t), ODD).lanewise(VectorOperators.ABS);
        return h01.lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, weights, 0)).rearrange(WEIGHT_ORDER_2))
                .lanewise(VectorOperators.ADD, h23.lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, weights, 8)).rearrange(WEIGHT_ORDER_2)))
                .reduceLanes(VectorOperators.ADD);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation runs in two kernels, {@link #quantizeCandidate} and
     * {@link #reconstructCandidate}, that exchange the dequantized coefficients through
     * {@code scratch}: one kernel holding every vector of the pass exceeds the JIT compiler's
     * inlining budget in some profiles, which leaves vector operations uninlined and boxes their
     * results on the heap.
     */
    @Override
    public long evaluateBlock(byte[] src, int srcOff, byte[] pred, int predOff, QuantMatrix m, short[] levels,
                              int levelsOff, byte[] recon, int reconOff, int[] weights, short[] scratch) {
        var nonZero = quantizeCandidate(src, srcOff, pred, predOff, m, levels, levelsOff, scratch);
        return reconstructCandidate(src, srcOff, pred, predOff, recon, reconOff, weights, scratch) | nonZero;
    }

    /**
     * Transforms the difference between a source block and its prediction and quantizes it, like
     * {@link #fTransform} followed by {@link #quantizeBlock}.
     *
     * @param src       the source samples
     * @param srcOff    the index of the source block
     * @param pred      the prediction samples
     * @param predOff   the index of the prediction block
     * @param m         the quantization matrix
     * @param levels    the quantized levels destination, in zigzag order
     * @param levelsOff the index of the first level
     * @param scratch   receives the 16 dequantized coefficients in raster order
     * @return {@code 1} when a level is nonzero, {@code 0} otherwise
     */
    private static long quantizeCandidate(byte[] src, int srcOff, byte[] pred, int predOff, QuantMatrix m,
                                          short[] levels, int levelsOff, short[] scratch) {
        var s01 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(src, srcOff))
                .withLane(1, (int) INT.get(src, srcOff + BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var p01 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(pred, predOff))
                .withLane(1, (int) INT.get(pred, predOff + BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var s23 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(src, srcOff + 2 * BPS))
                .withLane(1, (int) INT.get(src, srcOff + 3 * BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var p23 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(pred, predOff + 2 * BPS))
                .withLane(1, (int) INT.get(pred, predOff + 3 * BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));

        var k1 = INTS_8.cast(IntVector.fromArray(I256, F_K1, 0));
        var k2 = INTS_8.cast(IntVector.fromArray(I256, F_K2, 0));
        var r1 = INTS_8.cast(IntVector.fromArray(I256, F_R1, 0));
        var d01 = s01.lanewise(VectorOperators.SUB, p01);
        var rev01 = d01.rearrange(REVERSE);
        var sum01 = d01.lanewise(VectorOperators.ADD, rev01);
        var dif01 = d01.lanewise(VectorOperators.SUB, rev01);
        var t01 = sum01.blend(dif01, ODD).lanewise(VectorOperators.MUL, k1)
                .lanewise(VectorOperators.ADD, dif01.blend(sum01, ODD).rearrange(SWAP1).lanewise(VectorOperators.MUL, k2))
                .lanewise(VectorOperators.ADD, r1).lanewise(VectorOperators.ASHR, 9);
        var d23 = s23.lanewise(VectorOperators.SUB, p23);
        var rev23 = d23.rearrange(REVERSE);
        var sum23 = d23.lanewise(VectorOperators.ADD, rev23);
        var dif23 = d23.lanewise(VectorOperators.SUB, rev23);
        var t23 = sum23.blend(dif23, ODD).lanewise(VectorOperators.MUL, k1)
                .lanewise(VectorOperators.ADD, dif23.blend(sum23, ODD).rearrange(SWAP1).lanewise(VectorOperators.MUL, k2))
                .lanewise(VectorOperators.ADD, r1).lanewise(VectorOperators.ASHR, 9);
        var swapped23 = t23.rearrange(SWAP_HALVES);
        var fa = t01.lanewise(VectorOperators.ADD, swapped23);
        var fb = t01.lanewise(VectorOperators.SUB, swapped23);
        var swappedA = fa.rearrange(SWAP_HALVES);
        var even = fa.lanewise(VectorOperators.ADD, swappedA).lanewise(VectorOperators.ADD, I256_7).lanewise(VectorOperators.ASHR, 4);
        var evenDiff = fa.lanewise(VectorOperators.SUB, swappedA).lanewise(VectorOperators.ADD, I256_7).lanewise(VectorOperators.ASHR, 4);
        var odd = fb.lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, F_K3, 0)))
                .lanewise(VectorOperators.ADD, fb.rearrange(SWAP_HALVES).lanewise(VectorOperators.MUL, I256_8A9))
                .lanewise(VectorOperators.ADD, INTS_8.cast(IntVector.fromArray(I256, F_R2, 0)))
                .lanewise(VectorOperators.ASHR, 16)
                .lanewise(VectorOperators.ADD, I256_1, fb.compare(VectorOperators.NE, ZERO_I256).and(LOW));
        var c0 = even.blend(odd.rearrange(SWAP_HALVES), HIGH);
        var c1 = evenDiff.blend(odd, HIGH);

        var coeff0 = c0.lanewise(VectorOperators.ABS).lanewise(VectorOperators.ADD, INTS_8.cast(IntVector.fromArray(I256, m.sharpen, 0)));
        var level0 = coeff0.lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, m.iq, 0))).lanewise(VectorOperators.ADD, INTS_8.cast(IntVector.fromArray(I256, m.bias, 0)))
                .lanewise(VectorOperators.LSHR, QuantMatrix.QFIX).lanewise(VectorOperators.MIN, IntVector.broadcast(I256, ScalarVp8EncDsp.MAX_LEVEL));
        level0 = level0.lanewise(VectorOperators.NEG, c0.compare(VectorOperators.LT, ZERO_I256))
                .blend(ZERO_I256, coeff0.compare(VectorOperators.LE, INTS_8.cast(IntVector.fromArray(I256, m.zthresh, 0))));
        var coeff1 = c1.lanewise(VectorOperators.ABS).lanewise(VectorOperators.ADD, INTS_8.cast(IntVector.fromArray(I256, m.sharpen, 8)));
        var level1 = coeff1.lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, m.iq, 8))).lanewise(VectorOperators.ADD, INTS_8.cast(IntVector.fromArray(I256, m.bias, 8)))
                .lanewise(VectorOperators.LSHR, QuantMatrix.QFIX).lanewise(VectorOperators.MIN, IntVector.broadcast(I256, ScalarVp8EncDsp.MAX_LEVEL));
        level1 = level1.lanewise(VectorOperators.NEG, c1.compare(VectorOperators.LT, ZERO_I256))
                .blend(ZERO_I256, coeff1.compare(VectorOperators.LE, INTS_8.cast(IntVector.fromArray(I256, m.zthresh, 8))));
        var l0 = SHORTS_8.cast(level0.convertShape(VectorOperators.I2S, S128, 0));
        var l1 = SHORTS_8.cast(level1.convertShape(VectorOperators.I2S, S128, 0));
        l0.rearrange(ZIGZAG_LOW_A).blend(l1.rearrange(ZIGZAG_LOW_B), ZIGZAG_LOW_MASK).intoArray(levels, levelsOff);
        l1.rearrange(ZIGZAG_HIGH_B).blend(l0.rearrange(ZIGZAG_HIGH_A), ZIGZAG_HIGH_MASK).intoArray(levels, levelsOff + 8);
        SHORTS_8.cast(level0.lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, m.q, 0))).convertShape(VectorOperators.I2S, S128, 0))
                .intoArray(scratch, 0);
        SHORTS_8.cast(level1.lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, m.q, 8))).convertShape(VectorOperators.I2S, S128, 0))
                .intoArray(scratch, 8);
        return l0.lanewise(VectorOperators.OR, l1).compare(VectorOperators.NE, S128_0).anyTrue() ? 1L : 0L;
    }

    /**
     * Reconstructs a block from its dequantized coefficients and measures it, like
     * {@link #iTransform} followed by {@link #sse} and {@link #spectralSum}.
     *
     * @param src      the source samples
     * @param srcOff   the index of the source block
     * @param pred     the prediction samples
     * @param predOff  the index of the prediction block
     * @param recon    the reconstruction destination
     * @param reconOff the index of the reconstructed block
     * @param weights  the 16 frequency weights of the spectral sum
     * @param scratch  the 16 dequantized coefficients in raster order
     * @return {@code (spectralSum << 32) | (sse << 1)}
     */
    private static long reconstructCandidate(byte[] src, int srcOff, byte[] pred, int predOff, byte[] recon,
                                             int reconOff, int[] weights, short[] scratch) {
        var s01 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(src, srcOff))
                .withLane(1, (int) INT.get(src, srcOff + BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var p01 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(pred, predOff))
                .withLane(1, (int) INT.get(pred, predOff + BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var s23 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(src, srcOff + 2 * BPS))
                .withLane(1, (int) INT.get(src, srcOff + 3 * BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var p23 = INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(pred, predOff + 2 * BPS))
                .withLane(1, (int) INT.get(pred, predOff + 3 * BPS)).reinterpretShape(B64, 0))
                .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0));
        var v01 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, scratch, 0)).convertShape(VectorOperators.S2I, I256, 0));
        var v23 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, scratch, 8)).convertShape(VectorOperators.S2I, I256, 0));
        var isum = v01.lanewise(VectorOperators.ADD, v23);
        var idif = v01.lanewise(VectorOperators.SUB, v23);
        var ic = v01.lanewise(VectorOperators.MUL, I256_8A8C).lanewise(VectorOperators.ASHR, 16)
                .lanewise(VectorOperators.SUB, v23.lanewise(VectorOperators.MUL, I256_4E7B).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, v23));
        var id = v01.lanewise(VectorOperators.MUL, I256_4E7B).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, v01)
                .lanewise(VectorOperators.ADD, v23.lanewise(VectorOperators.MUL, I256_8A8C).lanewise(VectorOperators.ASHR, 16));
        var iab = isum.blend(idif.rearrange(SWAP_HALVES), HIGH);
        var idc = id.rearrange(SWAP_HALVES).blend(ic, HIGH);
        var it01 = iab.lanewise(VectorOperators.ADD, idc);
        var it23 = iab.lanewise(VectorOperators.SUB, idc).rearrange(SWAP_HALVES);
        var e0 = it01.rearrange(LANE0);
        var e1 = it01.rearrange(LANE1);
        var e2 = it01.rearrange(LANE2);
        var e3 = it01.rearrange(LANE3);
        var ab01 = e0.lanewise(VectorOperators.ADD, e2).blend(e0.lanewise(VectorOperators.SUB, e2), MIDDLE);
        var dc01 = e1.lanewise(VectorOperators.MUL, I256_4E7B).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, e1)
                .lanewise(VectorOperators.ADD, e3.lanewise(VectorOperators.MUL, I256_8A8C).lanewise(VectorOperators.ASHR, 16))
                .blend(e1.lanewise(VectorOperators.MUL, I256_8A8C).lanewise(VectorOperators.ASHR, 16)
                        .lanewise(VectorOperators.SUB, e3.lanewise(VectorOperators.MUL, I256_4E7B).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, e3)), MIDDLE);
        var res01 = ab01.lanewise(VectorOperators.ADD, dc01).blend(ab01.lanewise(VectorOperators.SUB, dc01), UPPER_PAIR).lanewise(VectorOperators.ADD, I256_4).lanewise(VectorOperators.ASHR, 3)
                .lanewise(VectorOperators.ADD, p01).lanewise(VectorOperators.MAX, ZERO_I256).lanewise(VectorOperators.MIN, I256_255);
        var g0 = it23.rearrange(LANE0);
        var g1 = it23.rearrange(LANE1);
        var g2 = it23.rearrange(LANE2);
        var g3 = it23.rearrange(LANE3);
        var ab23 = g0.lanewise(VectorOperators.ADD, g2).blend(g0.lanewise(VectorOperators.SUB, g2), MIDDLE);
        var dc23 = g1.lanewise(VectorOperators.MUL, I256_4E7B).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, g1)
                .lanewise(VectorOperators.ADD, g3.lanewise(VectorOperators.MUL, I256_8A8C).lanewise(VectorOperators.ASHR, 16))
                .blend(g1.lanewise(VectorOperators.MUL, I256_8A8C).lanewise(VectorOperators.ASHR, 16)
                        .lanewise(VectorOperators.SUB, g3.lanewise(VectorOperators.MUL, I256_4E7B).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, g3)), MIDDLE);
        var res23 = ab23.lanewise(VectorOperators.ADD, dc23).blend(ab23.lanewise(VectorOperators.SUB, dc23), UPPER_PAIR).lanewise(VectorOperators.ADD, I256_4).lanewise(VectorOperators.ASHR, 3)
                .lanewise(VectorOperators.ADD, p23).lanewise(VectorOperators.MAX, ZERO_I256).lanewise(VectorOperators.MIN, I256_255);
        var out01 = INTS_2.cast(BYTES_8.cast(res01.convertShape(VectorOperators.I2B, B64, 0)).reinterpretShape(I64, 0));
        INT.set(recon, reconOff, out01.lane(0));
        INT.set(recon, reconOff + BPS, out01.lane(1));
        var out23 = INTS_2.cast(BYTES_8.cast(res23.convertShape(VectorOperators.I2B, B64, 0)).reinterpretShape(I64, 0));
        INT.set(recon, reconOff + 2 * BPS, out23.lane(0));
        INT.set(recon, reconOff + 3 * BPS, out23.lane(1));

        var err01 = s01.lanewise(VectorOperators.SUB, res01);
        var err23 = s23.lanewise(VectorOperators.SUB, res23);
        var sse = err01.lanewise(VectorOperators.MUL, err01).lanewise(VectorOperators.ADD, err23.lanewise(VectorOperators.MUL, err23)).reduceLanes(VectorOperators.ADD);

        var hsum = res01.lanewise(VectorOperators.ADD, res23);
        var hdif = res01.lanewise(VectorOperators.SUB, res23);
        var hsumSwapped = hsum.rearrange(SWAP_HALVES);
        var hdifSwapped = hdif.rearrange(SWAP_HALVES);
        var rows01 = hsum.lanewise(VectorOperators.ADD, hsumSwapped).blend(hdif.lanewise(VectorOperators.ADD, hdifSwapped), HIGH);
        var rows23 = hdif.lanewise(VectorOperators.SUB, hdifSwapped).blend(hsum.lanewise(VectorOperators.SUB, hsumSwapped), HIGH);
        var hs = rows01.rearrange(SWAP2);
        var hu = rows01.lanewise(VectorOperators.ADD, hs).blend(hs.lanewise(VectorOperators.SUB, rows01), UPPER_PAIR);
        var ht = hu.rearrange(SWAP1);
        var h01 = hu.lanewise(VectorOperators.ADD, ht).blend(hu.lanewise(VectorOperators.SUB, ht), ODD).lanewise(VectorOperators.ABS);
        hs = rows23.rearrange(SWAP2);
        hu = rows23.lanewise(VectorOperators.ADD, hs).blend(hs.lanewise(VectorOperators.SUB, rows23), UPPER_PAIR);
        ht = hu.rearrange(SWAP1);
        var h23 = hu.lanewise(VectorOperators.ADD, ht).blend(hu.lanewise(VectorOperators.SUB, ht), ODD).lanewise(VectorOperators.ABS);
        var spectral = h01.lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, weights, 0)).rearrange(WEIGHT_ORDER_2))
                .lanewise(VectorOperators.ADD, h23.lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, weights, 8)).rearrange(WEIGHT_ORDER_2)))
                .reduceLanes(VectorOperators.ADD);
        return ((long) spectral << 32) | ((long) sse << 1);
    }

    @Override
    public int quantizeBlock(short[] in, int inOff, short[] out, int outOff, QuantMatrix m) {
        var v0 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, inOff)).convertShape(VectorOperators.S2I, I256, 0));
        var v1 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, in, inOff + 8)).convertShape(VectorOperators.S2I, I256, 0));
        var coeff0 = v0.lanewise(VectorOperators.ABS).lanewise(VectorOperators.ADD, INTS_8.cast(IntVector.fromArray(I256, m.sharpen, 0)));
        var level0 = coeff0.lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, m.iq, 0))).lanewise(VectorOperators.ADD, INTS_8.cast(IntVector.fromArray(I256, m.bias, 0)))
                .lanewise(VectorOperators.LSHR, QuantMatrix.QFIX).lanewise(VectorOperators.MIN, IntVector.broadcast(I256, ScalarVp8EncDsp.MAX_LEVEL));
        level0 = level0.lanewise(VectorOperators.NEG, v0.compare(VectorOperators.LT, ZERO_I256))
                .blend(ZERO_I256, coeff0.compare(VectorOperators.LE, INTS_8.cast(IntVector.fromArray(I256, m.zthresh, 0))));
        var coeff1 = v1.lanewise(VectorOperators.ABS).lanewise(VectorOperators.ADD, INTS_8.cast(IntVector.fromArray(I256, m.sharpen, 8)));
        var level1 = coeff1.lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, m.iq, 8))).lanewise(VectorOperators.ADD, INTS_8.cast(IntVector.fromArray(I256, m.bias, 8)))
                .lanewise(VectorOperators.LSHR, QuantMatrix.QFIX).lanewise(VectorOperators.MIN, IntVector.broadcast(I256, ScalarVp8EncDsp.MAX_LEVEL));
        level1 = level1.lanewise(VectorOperators.NEG, v1.compare(VectorOperators.LT, ZERO_I256))
                .blend(ZERO_I256, coeff1.compare(VectorOperators.LE, INTS_8.cast(IntVector.fromArray(I256, m.zthresh, 8))));
        SHORTS_8.cast(level0.lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, m.q, 0))).convertShape(VectorOperators.I2S, S128, 0))
                .intoArray(in, inOff);
        SHORTS_8.cast(level1.lanewise(VectorOperators.MUL, INTS_8.cast(IntVector.fromArray(I256, m.q, 8))).convertShape(VectorOperators.I2S, S128, 0))
                .intoArray(in, inOff + 8);
        var l0 = SHORTS_8.cast(level0.convertShape(VectorOperators.I2S, S128, 0));
        var l1 = SHORTS_8.cast(level1.convertShape(VectorOperators.I2S, S128, 0));
        l0.rearrange(ZIGZAG_LOW_A).blend(l1.rearrange(ZIGZAG_LOW_B), ZIGZAG_LOW_MASK).intoArray(out, outOff);
        l1.rearrange(ZIGZAG_HIGH_B).blend(l0.rearrange(ZIGZAG_HIGH_A), ZIGZAG_HIGH_MASK).intoArray(out, outOff + 8);
        return l0.lanewise(VectorOperators.OR, l1).compare(VectorOperators.NE, S128_0).anyTrue() ? 1 : 0;
    }

    @Override
    public int lastNonZero(short[] levels, int off) {
        var bits = SHORTS_16.cast(ShortVector.fromArray(S256, levels, off)).compare(VectorOperators.NE, S256_0).toLong();
        return 63 - Long.numberOfLeadingZeros(bits);
    }

    @Override
    public int countNonZeroAc(short[] levels, int off, int blocks, int limit) {
        var count = 0;
        for (var b = 0; b < blocks; b++) {
            var bits = SHORTS_16.cast(ShortVector.fromArray(S256, levels, off + b * 16)).compare(VectorOperators.NE, S256_0).toLong();
            count += Long.bitCount(bits & ~1L);
            if (count > limit) {
                return count;
            }
        }
        return count;
    }
}
