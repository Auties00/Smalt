/* Ports libvpx (the structure of vp8/common/x86/loopfilter_sse2.asm and subpixel_sse2.asm). Copyright (c) 2010 The
   WebM project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Implements {@link Vp8Dsp} with the Vector API; the output equals {@link ScalarVp8Dsp}'s.
 *
 * @implNote This implementation follows the structure of libvpx's SSE2 kernels, whose saturating
 * arithmetic is exact: repeated saturating additions of same-signed terms clamp like one clamped
 * sum, and the six-tap filter accumulates its positive and negative taps separately in unsigned
 * 16-bit lanes before a saturating subtraction. The lane layouts and the rules that keep the
 * kernels compiled are described at the top of the class body.
 */
public final class VectorVp8Dsp extends Vp8Dsp {
    /*
     * Loop filters process an edge with saturating signed byte arithmetic, 16 or 32 pixels per
     * vector. Horizontal edges read rows directly. Vertical edges are transposed into scratch memory
     * as 32-byte columns holding the 16 luma rows and the 8 rows of each chroma plane, so one 256-bit
     * vector filters the luma and both chroma planes of the macroblock edge and of the edge 4 pixels
     * in; the transposes run in registers on 64-bit lanes, one 8x8 block per lane. When the left
     * macroblock edge is filtered, the two windows on either side of it are written back together:
     * pairing their columns before the transpose leaves each row's 16 bytes contiguous in a vector.
     * Sub-pixel motion compensation filters 16 (or 8) pixels per vector in 16-bit lanes. The inverse
     * DCT holds a block in two 8-lane 32-bit vectors of two rows each: the vertical pass combines
     * them after swapping their halves, and the horizontal pass broadcasts each column within its
     * 4-lane group.
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
     * The 16-lane byte species of edge filters.
     */
    private static final VectorSpecies<Byte> B128 = ByteVector.SPECIES_128;

    /**
     * The 32-lane byte species of edge filters spanning luma and chroma.
     */
    private static final VectorSpecies<Byte> B256 = ByteVector.SPECIES_256;

    /**
     * The 8-lane int species of two rows of an inverse DCT block.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * The 4-lane int species of four 4-pixel rows.
     */
    private static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;

    /**
     * The 2-lane int species of two 4-pixel rows.
     */
    private static final VectorSpecies<Integer> I64 = IntVector.SPECIES_64;

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
     * The 2-lane long species of luma transposes.
     */
    private static final VectorSpecies<Long> L128 = LongVector.SPECIES_128;

    /**
     * The 4-lane long species of luma and chroma transposes.
     */
    private static final VectorSpecies<Long> L256 = LongVector.SPECIES_256;

    /**
     * The exact class of 4-lane long vectors, through which loads and reinterpretations are cast
     * so that the JIT compiler knows their class without a type profile.
     */
    @SuppressWarnings("unchecked")
    private static final Class<LongVector> LONGS_4 = (Class<LongVector>) L256.vectorType();

    /**
     * The exact class of 16-lane byte vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<ByteVector> BYTES_16 = (Class<ByteVector>) B128.vectorType();

    /**
     * Repeats lanes 0 and 1 of a 4-lane long vector as {@code 0, 0, 1, 1}.
     */
    private static final VectorShuffle<Long> PAIR_LOW = VectorShuffle.fromValues(L256, 0, 0, 1, 1);

    /**
     * Repeats lanes 2 and 3 of a 4-lane long vector as {@code 2, 2, 3, 3}.
     */
    private static final VectorShuffle<Long> PAIR_HIGH = VectorShuffle.fromValues(L256, 2, 2, 3, 3);

    /**
     * Swaps the two 128-bit halves of a 4-lane long vector.
     */
    private static final VectorShuffle<Long> SWAP_LONG_HALVES = VectorShuffle.fromValues(L256, 2, 3, 0, 1);

    /**
     * Selects lanes 1 and 3 of a 4-lane long vector.
     */
    private static final VectorMask<Long> ODD_LANES = VectorMask.fromValues(L256, false, true, false, true);

    /**
     * The bytes of each 64-bit lane that the first round of an 8x8 byte transpose exchanges.
     */
    private static final LongVector SWAP_BYTES = LongVector.broadcast(L256, 0x00FF00FF00FF00FFL);

    /**
     * The 16-bit pairs of each 64-bit lane that the second round of an 8x8 byte transpose exchanges.
     */
    private static final LongVector SWAP_SHORTS = LongVector.broadcast(L256, 0x0000FFFF0000FFFFL);

    /**
     * The 32-bit halves of each 64-bit lane that the third round of an 8x8 byte transpose exchanges.
     */
    private static final LongVector SWAP_INTS = LongVector.broadcast(L256, 0x00000000FFFFFFFFL);

    /**
     * The 8-lane byte species of 8-pixel filters.
     */
    private static final VectorSpecies<Byte> B64 = ByteVector.SPECIES_64;

    /**
     * The 16-lane short species of 16-pixel filters.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * The 8-lane short species of 8-pixel filters.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * The exact class of 32-lane byte vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<ByteVector> BYTES_32 = (Class<ByteVector>) B256.vectorType();

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
     * The exact class of 2-lane long vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<LongVector> LONGS_2 = (Class<LongVector>) L128.vectorType();

    /**
     * The exact class of 16-lane short vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<ShortVector> SHORTS_16 = (Class<ShortVector>) S256.vectorType();

    /**
     * The exact class of 8-lane short vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<ShortVector> SHORTS_8 = (Class<ShortVector>) S128.vectorType();

    /**
     * Sixteen bytes equal to {@code 1}.
     */
    private static final ByteVector B128_1 = ByteVector.broadcast(B128, (byte) 1);

    /**
     * Sixteen bytes equal to {@code 3}.
     */
    private static final ByteVector B128_3 = ByteVector.broadcast(B128, (byte) 3);

    /**
     * Sixteen bytes equal to {@code 4}.
     */
    private static final ByteVector B128_4 = ByteVector.broadcast(B128, (byte) 4);

    /**
     * Sixteen bytes equal to {@code 0x80}.
     */
    private static final ByteVector B128_128 = ByteVector.broadcast(B128, (byte) 0x80);

    /**
     * Thirty-two bytes equal to {@code 1}.
     */
    private static final ByteVector B256_1 = ByteVector.broadcast(B256, (byte) 1);

    /**
     * Thirty-two bytes equal to {@code 3}.
     */
    private static final ByteVector B256_3 = ByteVector.broadcast(B256, (byte) 3);

    /**
     * Thirty-two bytes equal to {@code 4}.
     */
    private static final ByteVector B256_4 = ByteVector.broadcast(B256, (byte) 4);

    /**
     * Thirty-two bytes equal to {@code 0x80}.
     */
    private static final ByteVector B256_128 = ByteVector.broadcast(B256, (byte) 0x80);

    /**
     * Eight ints equal to {@code 0}.
     */
    private static final IntVector I256_0 = IntVector.broadcast(I256, 0);

    /**
     * Eight ints equal to {@code 4}.
     */
    private static final IntVector I256_4 = IntVector.broadcast(I256, 4);

    /**
     * Eight ints equal to {@code 255}.
     */
    private static final IntVector I256_255 = IntVector.broadcast(I256, 255);

    /**
     * Eight shorts equal to {@code 64}.
     */
    private static final ShortVector S128_64 = ShortVector.broadcast(S128, (short) 64);

    /**
     * Eight shorts equal to {@code 255}.
     */
    private static final ShortVector S128_255 = ShortVector.broadcast(S128, (short) 255);

    /**
     * Sixteen shorts equal to {@code 9}.
     */
    private static final ShortVector S256_9 = ShortVector.broadcast(S256, (short) 9);

    /**
     * Sixteen shorts equal to {@code 18}.
     */
    private static final ShortVector S256_18 = ShortVector.broadcast(S256, (short) 18);

    /**
     * Sixteen shorts equal to {@code 27}.
     */
    private static final ShortVector S256_27 = ShortVector.broadcast(S256, (short) 27);

    /**
     * Sixteen shorts equal to {@code 63}.
     */
    private static final ShortVector S256_63 = ShortVector.broadcast(S256, (short) 63);

    /**
     * Sixteen shorts equal to {@code 64}.
     */
    private static final ShortVector S256_64 = ShortVector.broadcast(S256, (short) 64);

    /**
     * Sixteen shorts equal to {@code 0xFF}.
     */
    private static final ShortVector S256_255 = ShortVector.broadcast(S256, (short) 0xFF);

    /**
     * Reads and writes eight bytes of a byte array as a little-endian {@code long}.
     */
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Reads and writes four bytes of a byte array as a little-endian {@code int}.
     */
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The offset in the scratch array of the transposed columns: 24 columns of 32 bytes, starting 8
     * pixels left of the macroblock.
     *
     * <p>Transpose windows are aligned to 8 pixels so that each 64-bit row load falls inside one
     * earlier 128-bit store of the previous macroblock's horizontal edge filters, which the processor
     * forwards without a stall.
     */
    private static final int COLS = 0;

    /**
     * The offset in the scratch array of the paired chroma rows (12 rows of U and V).
     */
    private static final int CHROMA_ROWS = 768;

    /**
     * The scalar kernels used where vectors do not pay off.
     */
    private final ScalarVp8Dsp scalar = new ScalarVp8Dsp();

    /**
     * Creates the vector implementation.
     */
    private VectorVp8Dsp() {
    }

    /**
     * Creates the vector implementation; called only when the Vector API is available.
     *
     * @return the implementation
     */
    public static Vp8Dsp create() {
        return new VectorVp8Dsp();
    }

    @Override
    public void idctAdd(short[] coef, int coefOff, byte[] dst, int dstOff, int stride) {
        var v01 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, coef, coefOff)).convertShape(VectorOperators.S2I, I256, 0));
        var v23 = INTS_8.cast(SHORTS_8.cast(ShortVector.fromArray(S128, coef, coefOff + 8)).convertShape(VectorOperators.S2I, I256, 0));
        var zero = ShortVector.zero(S128).lanewise(VectorOperators.OR, ShortVector.zero(S128));
        zero.intoArray(coef, coefOff);
        zero.intoArray(coef, coefOff + 8);
        var sum = v01.lanewise(VectorOperators.ADD, v23);
        var dif = v01.lanewise(VectorOperators.SUB, v23);
        var c = v01.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, ScalarVp8Dsp.SIN_PI8_SQRT2)).lanewise(VectorOperators.ASHR, 16)
                .lanewise(VectorOperators.SUB, v23.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, ScalarVp8Dsp.COS_PI8_SQRT2_MINUS1)).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, v23));
        var d = v01.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, ScalarVp8Dsp.COS_PI8_SQRT2_MINUS1)).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, v01)
                .lanewise(VectorOperators.ADD, v23.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, ScalarVp8Dsp.SIN_PI8_SQRT2)).lanewise(VectorOperators.ASHR, 16));
        var ab = sum.blend(dif.rearrange(SWAP_HALVES), HIGH);
        var dc = d.rearrange(SWAP_HALVES).blend(c, HIGH);
        var t01 = ab.lanewise(VectorOperators.ADD, dc).lanewise(VectorOperators.LSHL, 16).lanewise(VectorOperators.ASHR, 16);
        var t23 = ab.lanewise(VectorOperators.SUB, dc).rearrange(SWAP_HALVES).lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16);

        var e0 = t01.rearrange(LANE0);
        var e1 = t01.rearrange(LANE1);
        var e2 = t01.rearrange(LANE2);
        var e3 = t01.rearrange(LANE3);
        var ab01 = e0.lanewise(VectorOperators.ADD, e2).blend(e0.lanewise(VectorOperators.SUB, e2), MIDDLE);
        var dc01 = e1.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, ScalarVp8Dsp.COS_PI8_SQRT2_MINUS1)).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, e1)
                .lanewise(VectorOperators.ADD, e3.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, ScalarVp8Dsp.SIN_PI8_SQRT2)).lanewise(VectorOperators.ASHR, 16))
                .blend(e1.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, ScalarVp8Dsp.SIN_PI8_SQRT2)).lanewise(VectorOperators.ASHR, 16)
                        .lanewise(VectorOperators.SUB, e3.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, ScalarVp8Dsp.COS_PI8_SQRT2_MINUS1)).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, e3)),
                        MIDDLE);
        var row1 = dstOff + stride;
        var res01 = ab01.lanewise(VectorOperators.ADD, dc01).blend(ab01.lanewise(VectorOperators.SUB, dc01), UPPER_PAIR).lanewise(VectorOperators.ADD, I256_4).lanewise(VectorOperators.ASHR, 3)
                .lanewise(VectorOperators.ADD, INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(dst, dstOff))
                        .withLane(1, (int) INT.get(dst, row1)).reinterpretShape(B64, 0))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0)))
                .lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255);
        var out01 = INTS_2.cast(BYTES_8.cast(res01.convertShape(VectorOperators.I2B, B64, 0)).reinterpretShape(I64, 0));
        INT.set(dst, dstOff, out01.lane(0));
        INT.set(dst, row1, out01.lane(1));

        var g0 = t23.rearrange(LANE0);
        var g1 = t23.rearrange(LANE1);
        var g2 = t23.rearrange(LANE2);
        var g3 = t23.rearrange(LANE3);
        var ab23 = g0.lanewise(VectorOperators.ADD, g2).blend(g0.lanewise(VectorOperators.SUB, g2), MIDDLE);
        var dc23 = g1.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, ScalarVp8Dsp.COS_PI8_SQRT2_MINUS1)).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, g1)
                .lanewise(VectorOperators.ADD, g3.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, ScalarVp8Dsp.SIN_PI8_SQRT2)).lanewise(VectorOperators.ASHR, 16))
                .blend(g1.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, ScalarVp8Dsp.SIN_PI8_SQRT2)).lanewise(VectorOperators.ASHR, 16)
                        .lanewise(VectorOperators.SUB, g3.lanewise(VectorOperators.MUL, IntVector.broadcast(I256, ScalarVp8Dsp.COS_PI8_SQRT2_MINUS1)).lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.ADD, g3)),
                        MIDDLE);
        var row2 = row1 + stride;
        var row3 = row2 + stride;
        var res23 = ab23.lanewise(VectorOperators.ADD, dc23).blend(ab23.lanewise(VectorOperators.SUB, dc23), UPPER_PAIR).lanewise(VectorOperators.ADD, I256_4).lanewise(VectorOperators.ASHR, 3)
                .lanewise(VectorOperators.ADD, INTS_8.cast(BYTES_8.cast(IntVector.broadcast(I64, (int) INT.get(dst, row2))
                        .withLane(1, (int) INT.get(dst, row3)).reinterpretShape(B64, 0))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0)))
                .lanewise(VectorOperators.MAX, I256_0).lanewise(VectorOperators.MIN, I256_255);
        var out23 = INTS_2.cast(BYTES_8.cast(res23.convertShape(VectorOperators.I2B, B64, 0)).reinterpretShape(I64, 0));
        INT.set(dst, row2, out23.lane(0));
        INT.set(dst, row3, out23.lane(1));
    }

    @Override
    public void dcOnlyAdd(int dc, byte[] dst, int dstOff, int stride) {
        var add = (dc + 4) >> 3;
        var row1 = dstOff + stride;
        var row2 = row1 + stride;
        var row3 = row2 + stride;
        var pixels = BYTES_16.cast(IntVector.broadcast(I128, (int) INT.get(dst, dstOff)).withLane(1, (int) INT.get(dst, row1))
                .withLane(2, (int) INT.get(dst, row2)).withLane(3, (int) INT.get(dst, row3)).reinterpretShape(B128, 0));
        var out = INTS_4.cast(pixels.lanewise(VectorOperators.SUADD, ByteVector.broadcast(B128, (byte) Math.clamp(add, 0, 255)))
                .lanewise(VectorOperators.SUSUB, ByteVector.broadcast(B128, (byte) Math.clamp(-add, 0, 255))).reinterpretShape(I128, 0));
        INT.set(dst, dstOff, out.lane(0));
        INT.set(dst, row1, out.lane(1));
        INT.set(dst, row2, out.lane(2));
        INT.set(dst, row3, out.lane(3));
    }

    @Override
    public void copy(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride, int width, int height) {
        if (width == 16) {
            for (var r = 0; r < height; r++) {
                BYTES_16.cast(ByteVector.fromArray(B128, src, srcOff + r * srcStride)).intoArray(dst, dstOff + r * dstStride);
            }
        } else if (width == 8) {
            for (var r = 0; r < height; r++) {
                LONG.set(dst, dstOff + r * dstStride, (long) LONG.get(src, srcOff + r * srcStride));
            }
        } else {
            for (var r = 0; r < height; r++) {
                INT.set(dst, dstOff + r * dstStride, (int) INT.get(src, srcOff + r * srcStride));
            }
        }
    }

    @Override
    public void sixtap(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                       int width, int height, int mx, int my, byte[] scratch) {
        if (width == 4) {
            scalar.sixtap(src, srcOff, srcStride, dst, dstOff, dstStride, width, height, mx, my, scratch);
            return;
        }
        if (my == 0) {
            if (mx == 0) {
                copy(src, srcOff, srcStride, dst, dstOff, dstStride, width, height);
            } else {
                sixtapPass(src, srcOff, 1, srcStride, dst, dstOff, dstStride, width, height, mx);
            }
        } else if (mx == 0) {
            sixtapPass(src, srcOff, srcStride, srcStride, dst, dstOff, dstStride, width, height, my);
        } else {
            sixtapPass(src, srcOff - 2 * srcStride, 1, srcStride, scratch, 0, width, width, height + 5, mx);
            sixtapPass(scratch, 2 * width, width, width, dst, dstOff, dstStride, width, height, my);
        }
    }

    /**
     * Applies one six-tap filter pass, dispatching to the kernel of the block width and tap count.
     *
     * @param src       the source plane
     * @param srcOff    the index of the first source pixel
     * @param step      the distance between taps: 1 for horizontal, the stride for vertical
     * @param srcStride the source row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the first destination pixel
     * @param dstStride the destination row stride
     * @param width     the number of columns, 8 or 16
     * @param height    the number of rows
     * @param fraction  the fraction in eighths, {@code [1, 7]}
     */
    private static void sixtapPass(byte[] src, int srcOff, int step, int srcStride, byte[] dst, int dstOff,
                                   int dstStride, int width, int height, int fraction) {
        var base = fraction * 6;
        var t = ScalarVp8Dsp.SIXTAP;
        if ((fraction & 1) == 0) {
            if (width == 16) {
                sixTap16(src, srcOff, step, srcStride, dst, dstOff, dstStride, height,
                        t[base], -t[base + 1], t[base + 2], t[base + 3], -t[base + 4], t[base + 5]);
            } else {
                sixTap8(src, srcOff, step, srcStride, dst, dstOff, dstStride, height,
                        t[base], -t[base + 1], t[base + 2], t[base + 3], -t[base + 4], t[base + 5]);
            }
        } else if (width == 16) {
            fourTap16(src, srcOff, step, srcStride, dst, dstOff, dstStride, height,
                    -t[base + 1], t[base + 2], t[base + 3], -t[base + 4]);
        } else {
            fourTap8(src, srcOff, step, srcStride, dst, dstOff, dstStride, height,
                    -t[base + 1], t[base + 2], t[base + 3], -t[base + 4]);
        }
    }

    /**
     * Filters 16-pixel rows with a six-tap filter whose second and fifth taps are negative.
     *
     * The positive taps are accumulated in unsigned 16-bit lanes (at most
     * {@code 160 * 255 + 64}), the negative taps separately, and their saturating difference is
     * shifted, which reproduces the clamped result of the scalar filter.
     *
     * @param src       the source plane
     * @param srcOff    the index of the first source pixel
     * @param step      the distance between taps
     * @param srcStride the source row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the first destination pixel
     * @param dstStride the destination row stride
     * @param height    the number of rows
     * @param f0        the first tap
     * @param f1        the magnitude of the second (negative) tap
     * @param f2        the third tap
     * @param f3        the fourth tap
     * @param f4        the magnitude of the fifth (negative) tap
     * @param f5        the sixth tap
     */
    private static void sixTap16(byte[] src, int srcOff, int step, int srcStride, byte[] dst, int dstOff,
            int dstStride, int height, int f0, int f1, int f2, int f3, int f4, int f5) {
        var c0 = (short) f0;
        var c1 = (short) f1;
        var c2 = (short) f2;
        var c3 = (short) f3;
        var c4 = (short) f4;
        var c5 = (short) f5;
        for (var r = 0; r < height; r++) {
            var p = srcOff + r * srcStride;
            var s0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, p - 2 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var s1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, p - step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var s2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, p))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var s3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, p + step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var s4 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, p + 2 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var s5 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, p + 3 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var pos = s0.lanewise(VectorOperators.MUL, ShortVector.broadcast(S256, (short) c0)).lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, ShortVector.broadcast(S256, (short) c2))).lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, ShortVector.broadcast(S256, (short) c3))).lanewise(VectorOperators.ADD, s5.lanewise(VectorOperators.MUL, ShortVector.broadcast(S256, (short) c5))).lanewise(VectorOperators.ADD, S256_64);
            var neg = s1.lanewise(VectorOperators.MUL, ShortVector.broadcast(S256, (short) c1)).lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, ShortVector.broadcast(S256, (short) c4)));
            BYTES_16.cast(pos.lanewise(VectorOperators.SUSUB, neg).lanewise(VectorOperators.LSHR, 7)
                    .lanewise(VectorOperators.UMIN, S256_255).convertShape(VectorOperators.S2B, B128, 0))
                    .intoArray(dst, dstOff + r * dstStride);
        }
    }

    /**
     * Filters 16-pixel rows with a four-tap filter (the odd fractions, whose outer taps are
     * zero); see {@link #sixTap16}.
     *
     * @param src       the source plane
     * @param srcOff    the index of the first source pixel
     * @param step      the distance between taps
     * @param srcStride the source row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the first destination pixel
     * @param dstStride the destination row stride
     * @param height    the number of rows
     * @param f1        the magnitude of the first (negative) tap
     * @param f2        the second tap
     * @param f3        the third tap
     * @param f4        the magnitude of the fourth (negative) tap
     */
    private static void fourTap16(byte[] src, int srcOff, int step, int srcStride, byte[] dst, int dstOff,
            int dstStride, int height, int f1, int f2, int f3, int f4) {
        var c1 = (short) f1;
        var c2 = (short) f2;
        var c3 = (short) f3;
        var c4 = (short) f4;
        for (var r = 0; r < height; r++) {
            var p = srcOff + r * srcStride;
            var s1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, p - step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var s2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, p))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var s3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, p + step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var s4 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, p + 2 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var pos = s2.lanewise(VectorOperators.MUL, ShortVector.broadcast(S256, (short) c2)).lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, ShortVector.broadcast(S256, (short) c3))).lanewise(VectorOperators.ADD, S256_64);
            var neg = s1.lanewise(VectorOperators.MUL, ShortVector.broadcast(S256, (short) c1)).lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, ShortVector.broadcast(S256, (short) c4)));
            BYTES_16.cast(pos.lanewise(VectorOperators.SUSUB, neg).lanewise(VectorOperators.LSHR, 7)
                    .lanewise(VectorOperators.UMIN, S256_255).convertShape(VectorOperators.S2B, B128, 0))
                    .intoArray(dst, dstOff + r * dstStride);
        }
    }

    /**
     * Filters 8-pixel rows with a six-tap filter; see {@link #sixTap16}.
     *
     * @param src       the source plane
     * @param srcOff    the index of the first source pixel
     * @param step      the distance between taps
     * @param srcStride the source row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the first destination pixel
     * @param dstStride the destination row stride
     * @param height    the number of rows
     * @param f0        the first tap
     * @param f1        the magnitude of the second (negative) tap
     * @param f2        the third tap
     * @param f3        the fourth tap
     * @param f4        the magnitude of the fifth (negative) tap
     * @param f5        the sixth tap
     */
    private static void sixTap8(byte[] src, int srcOff, int step, int srcStride, byte[] dst, int dstOff,
            int dstStride, int height, int f0, int f1, int f2, int f3, int f4, int f5) {
        var c0 = (short) f0;
        var c1 = (short) f1;
        var c2 = (short) f2;
        var c3 = (short) f3;
        var c4 = (short) f4;
        var c5 = (short) f5;
        for (var r = 0; r < height; r++) {
            var p = srcOff + r * srcStride;
            var s0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, p - 2 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var s1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, p - step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var s2 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, p))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var s3 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, p + step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var s4 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, p + 2 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var s5 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, p + 3 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var pos = s0.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) c0)).lanewise(VectorOperators.ADD, s2.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) c2))).lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) c3))).lanewise(VectorOperators.ADD, s5.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) c5))).lanewise(VectorOperators.ADD, S128_64);
            var neg = s1.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) c1)).lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) c4)));
            BYTES_8.cast(pos.lanewise(VectorOperators.SUSUB, neg).lanewise(VectorOperators.LSHR, 7)
                    .lanewise(VectorOperators.UMIN, S128_255).convertShape(VectorOperators.S2B, B64, 0))
                    .intoArray(dst, dstOff + r * dstStride);
        }
    }

    /**
     * Filters 8-pixel rows with a four-tap filter; see {@link #sixTap16}.
     *
     * @param src       the source plane
     * @param srcOff    the index of the first source pixel
     * @param step      the distance between taps
     * @param srcStride the source row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the first destination pixel
     * @param dstStride the destination row stride
     * @param height    the number of rows
     * @param f1        the magnitude of the first (negative) tap
     * @param f2        the second tap
     * @param f3        the third tap
     * @param f4        the magnitude of the fourth (negative) tap
     */
    private static void fourTap8(byte[] src, int srcOff, int step, int srcStride, byte[] dst, int dstOff,
            int dstStride, int height, int f1, int f2, int f3, int f4) {
        var c1 = (short) f1;
        var c2 = (short) f2;
        var c3 = (short) f3;
        var c4 = (short) f4;
        for (var r = 0; r < height; r++) {
            var p = srcOff + r * srcStride;
            var s1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, p - step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var s2 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, p))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var s3 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, p + step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var s4 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, p + 2 * step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var pos = s2.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) c2)).lanewise(VectorOperators.ADD, s3.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) c3))).lanewise(VectorOperators.ADD, S128_64);
            var neg = s1.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) c1)).lanewise(VectorOperators.ADD, s4.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) c4)));
            BYTES_8.cast(pos.lanewise(VectorOperators.SUSUB, neg).lanewise(VectorOperators.LSHR, 7)
                    .lanewise(VectorOperators.UMIN, S128_255).convertShape(VectorOperators.S2B, B64, 0))
                    .intoArray(dst, dstOff + r * dstStride);
        }
    }

    @Override
    public void bilinear(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                         int width, int height, int mx, int my, byte[] scratch) {
        if (width == 4) {
            scalar.bilinear(src, srcOff, srcStride, dst, dstOff, dstStride, width, height, mx, my, scratch);
            return;
        }
        if (my == 0) {
            if (mx == 0) {
                copy(src, srcOff, srcStride, dst, dstOff, dstStride, width, height);
            } else {
                bilinearPass(src, srcOff, 1, srcStride, dst, dstOff, dstStride, width, height, mx);
            }
        } else if (mx == 0) {
            bilinearPass(src, srcOff, srcStride, srcStride, dst, dstOff, dstStride, width, height, my);
        } else {
            bilinearPass(src, srcOff, 1, srcStride, scratch, 0, width, width, height + 1, mx);
            bilinearPass(scratch, 0, width, width, dst, dstOff, dstStride, width, height, my);
        }
    }

    /**
     * Applies one bilinear filter pass, dispatching to the kernel of the block width.
     *
     * @param src       the source plane
     * @param srcOff    the index of the first source pixel
     * @param step      the distance between taps
     * @param srcStride the source row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the first destination pixel
     * @param dstStride the destination row stride
     * @param width     the number of columns, 8 or 16
     * @param height    the number of rows
     * @param fraction  the fraction in eighths, {@code [1, 7]}
     */
    private static void bilinearPass(byte[] src, int srcOff, int step, int srcStride, byte[] dst, int dstOff,
                                     int dstStride, int width, int height, int fraction) {
        var f0 = ScalarVp8Dsp.BILINEAR[fraction];
        if (width == 16) {
            bilinear16(src, srcOff, step, srcStride, dst, dstOff, dstStride, height, f0, 128 - f0);
        } else {
            bilinear8(src, srcOff, step, srcStride, dst, dstOff, dstStride, height, f0, 128 - f0);
        }
    }

    /**
     * Filters 16-pixel rows with a bilinear filter.
     *
     * @param src       the source plane
     * @param srcOff    the index of the first source pixel
     * @param step      the distance between taps
     * @param srcStride the source row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the first destination pixel
     * @param dstStride the destination row stride
     * @param height    the number of rows
     * @param f0        the first tap
     * @param f1        the second tap
     */
    private static void bilinear16(byte[] src, int srcOff, int step, int srcStride, byte[] dst, int dstOff,
            int dstStride, int height, int f0, int f1) {
        var c0 = (short) f0;
        var c1 = (short) f1;
        for (var r = 0; r < height; r++) {
            var p = srcOff + r * srcStride;
            var s0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, p))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var s1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, src, p + step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            BYTES_16.cast(s0.lanewise(VectorOperators.MUL, ShortVector.broadcast(S256, (short) c0)).lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, ShortVector.broadcast(S256, (short) c1))).lanewise(VectorOperators.ADD, S256_64).lanewise(VectorOperators.LSHR, 7)
                    .convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, dstOff + r * dstStride);
        }
    }

    /**
     * Filters 8-pixel rows with a bilinear filter.
     *
     * @param src       the source plane
     * @param srcOff    the index of the first source pixel
     * @param step      the distance between taps
     * @param srcStride the source row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the first destination pixel
     * @param dstStride the destination row stride
     * @param height    the number of rows
     * @param f0        the first tap
     * @param f1        the second tap
     */
    private static void bilinear8(byte[] src, int srcOff, int step, int srcStride, byte[] dst, int dstOff,
            int dstStride, int height, int f0, int f1) {
        var c0 = (short) f0;
        var c1 = (short) f1;
        for (var r = 0; r < height; r++) {
            var p = srcOff + r * srcStride;
            var s0 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, p))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            var s1 = SHORTS_8.cast(BYTES_8.cast(ByteVector.fromArray(B64, src, p + step))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, S128, 0));
            BYTES_8.cast(s0.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) c0)).lanewise(VectorOperators.ADD, s1.lanewise(VectorOperators.MUL, ShortVector.broadcast(S128, (short) c1))).lanewise(VectorOperators.ADD, S128_64).lanewise(VectorOperators.LSHR, 7)
                    .convertShape(VectorOperators.S2B, B64, 0)).intoArray(dst, dstOff + r * dstStride);
        }
    }

    /**
     * Transposes an 8-column window of a macroblock's 16 luma rows and 8 rows of each chroma plane
     * into 8 column vectors of scratch memory.
     *
     * <p>Column {@code j} of the window is stored as 32 bytes at {@code tOff + 32 * j}: luma rows 0 to
     * 15, then the 8 rows of {@code u}, then the 8 rows of {@code v}. Each 64-bit lane of the vectors
     * holds one 8x8 block, and the transpose runs on all four blocks at once with three rounds of
     * masked shifts and exclusive ors.
     *
     * @param y        the luma plane
     * @param yOff     the index of the window's top-left luma pixel
     * @param yStride  the luma row stride
     * @param u        the first chroma plane
     * @param v        the second chroma plane
     * @param uvOff    the index of the window's top-left chroma pixel
     * @param uvStride the chroma row stride
     * @param t        the scratch array
     * @param tOff     the index of the first transposed column
     */
    private static void transposeInWide(byte[] y, int yOff, int yStride, byte[] u, byte[] v, int uvOff, int uvStride,
            byte[] t, int tOff) {
        var r0 = LongVector.broadcast(L256, (long) LONG.get(y, yOff))
                .withLane(1, (long) LONG.get(y, yOff + 8 * yStride))
                .withLane(2, (long) LONG.get(u, uvOff))
                .withLane(3, (long) LONG.get(v, uvOff));
        var r1 = LongVector.broadcast(L256, (long) LONG.get(y, yOff + 1 * yStride))
                .withLane(1, (long) LONG.get(y, yOff + 9 * yStride))
                .withLane(2, (long) LONG.get(u, uvOff + 1 * uvStride))
                .withLane(3, (long) LONG.get(v, uvOff + 1 * uvStride));
        var r2 = LongVector.broadcast(L256, (long) LONG.get(y, yOff + 2 * yStride))
                .withLane(1, (long) LONG.get(y, yOff + 10 * yStride))
                .withLane(2, (long) LONG.get(u, uvOff + 2 * uvStride))
                .withLane(3, (long) LONG.get(v, uvOff + 2 * uvStride));
        var r3 = LongVector.broadcast(L256, (long) LONG.get(y, yOff + 3 * yStride))
                .withLane(1, (long) LONG.get(y, yOff + 11 * yStride))
                .withLane(2, (long) LONG.get(u, uvOff + 3 * uvStride))
                .withLane(3, (long) LONG.get(v, uvOff + 3 * uvStride));
        var r4 = LongVector.broadcast(L256, (long) LONG.get(y, yOff + 4 * yStride))
                .withLane(1, (long) LONG.get(y, yOff + 12 * yStride))
                .withLane(2, (long) LONG.get(u, uvOff + 4 * uvStride))
                .withLane(3, (long) LONG.get(v, uvOff + 4 * uvStride));
        var r5 = LongVector.broadcast(L256, (long) LONG.get(y, yOff + 5 * yStride))
                .withLane(1, (long) LONG.get(y, yOff + 13 * yStride))
                .withLane(2, (long) LONG.get(u, uvOff + 5 * uvStride))
                .withLane(3, (long) LONG.get(v, uvOff + 5 * uvStride));
        var r6 = LongVector.broadcast(L256, (long) LONG.get(y, yOff + 6 * yStride))
                .withLane(1, (long) LONG.get(y, yOff + 14 * yStride))
                .withLane(2, (long) LONG.get(u, uvOff + 6 * uvStride))
                .withLane(3, (long) LONG.get(v, uvOff + 6 * uvStride));
        var r7 = LongVector.broadcast(L256, (long) LONG.get(y, yOff + 7 * yStride))
                .withLane(1, (long) LONG.get(y, yOff + 15 * yStride))
                .withLane(2, (long) LONG.get(u, uvOff + 7 * uvStride))
                .withLane(3, (long) LONG.get(v, uvOff + 7 * uvStride));
        LongVector x;
        x = r0.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r1).lanewise(VectorOperators.AND, LongVector.broadcast(L256, (long) (0x00FF00FF00FF00FFL)));
        r1 = r1.lanewise(VectorOperators.XOR, x);
        r0 = r0.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r2.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r3).lanewise(VectorOperators.AND, LongVector.broadcast(L256, (long) (0x00FF00FF00FF00FFL)));
        r3 = r3.lanewise(VectorOperators.XOR, x);
        r2 = r2.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r4.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r5).lanewise(VectorOperators.AND, LongVector.broadcast(L256, (long) (0x00FF00FF00FF00FFL)));
        r5 = r5.lanewise(VectorOperators.XOR, x);
        r4 = r4.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r6.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r7).lanewise(VectorOperators.AND, LongVector.broadcast(L256, (long) (0x00FF00FF00FF00FFL)));
        r7 = r7.lanewise(VectorOperators.XOR, x);
        r6 = r6.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r2).lanewise(VectorOperators.AND, LongVector.broadcast(L256, (long) (0x0000FFFF0000FFFFL)));
        r2 = r2.lanewise(VectorOperators.XOR, x);
        r0 = r0.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r1.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r3).lanewise(VectorOperators.AND, LongVector.broadcast(L256, (long) (0x0000FFFF0000FFFFL)));
        r3 = r3.lanewise(VectorOperators.XOR, x);
        r1 = r1.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r4.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r6).lanewise(VectorOperators.AND, LongVector.broadcast(L256, (long) (0x0000FFFF0000FFFFL)));
        r6 = r6.lanewise(VectorOperators.XOR, x);
        r4 = r4.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r5.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r7).lanewise(VectorOperators.AND, LongVector.broadcast(L256, (long) (0x0000FFFF0000FFFFL)));
        r7 = r7.lanewise(VectorOperators.XOR, x);
        r5 = r5.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r0.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r4).lanewise(VectorOperators.AND, LongVector.broadcast(L256, (long) (0x00000000FFFFFFFFL)));
        r4 = r4.lanewise(VectorOperators.XOR, x);
        r0 = r0.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        x = r1.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r5).lanewise(VectorOperators.AND, LongVector.broadcast(L256, (long) (0x00000000FFFFFFFFL)));
        r5 = r5.lanewise(VectorOperators.XOR, x);
        r1 = r1.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        x = r2.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r6).lanewise(VectorOperators.AND, LongVector.broadcast(L256, (long) (0x00000000FFFFFFFFL)));
        r6 = r6.lanewise(VectorOperators.XOR, x);
        r2 = r2.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        x = r3.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r7).lanewise(VectorOperators.AND, LongVector.broadcast(L256, (long) (0x00000000FFFFFFFFL)));
        r7 = r7.lanewise(VectorOperators.XOR, x);
        r3 = r3.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        BYTES_32.cast(r0.reinterpretShape(B256, 0)).intoArray(t, tOff + 0);
        BYTES_32.cast(r1.reinterpretShape(B256, 0)).intoArray(t, tOff + 32);
        BYTES_32.cast(r2.reinterpretShape(B256, 0)).intoArray(t, tOff + 64);
        BYTES_32.cast(r3.reinterpretShape(B256, 0)).intoArray(t, tOff + 96);
        BYTES_32.cast(r4.reinterpretShape(B256, 0)).intoArray(t, tOff + 128);
        BYTES_32.cast(r5.reinterpretShape(B256, 0)).intoArray(t, tOff + 160);
        BYTES_32.cast(r6.reinterpretShape(B256, 0)).intoArray(t, tOff + 192);
        BYTES_32.cast(r7.reinterpretShape(B256, 0)).intoArray(t, tOff + 224);
    }

    /**
     * Transposes an 8-column window of a macroblock's 16 luma rows into 8 column vectors of scratch
     * memory.
     *
     * <p>Column {@code j} of the window is stored as the first 16 bytes at {@code tOff + 32 * j}, the
     * layout of {@link #transposeInWide} without the chroma rows.
     *
     * @param y       the luma plane
     * @param yOff    the index of the window's top-left pixel
     * @param yStride the luma row stride
     * @param t       the scratch array
     * @param tOff    the index of the first transposed column
     */
    private static void transposeInLuma(byte[] y, int yOff, int yStride, byte[] t, int tOff) {
        var r0 = LongVector.broadcast(L128, (long) LONG.get(y, yOff))
                .withLane(1, (long) LONG.get(y, yOff + 8 * yStride));
        var r1 = LongVector.broadcast(L128, (long) LONG.get(y, yOff + 1 * yStride))
                .withLane(1, (long) LONG.get(y, yOff + 9 * yStride));
        var r2 = LongVector.broadcast(L128, (long) LONG.get(y, yOff + 2 * yStride))
                .withLane(1, (long) LONG.get(y, yOff + 10 * yStride));
        var r3 = LongVector.broadcast(L128, (long) LONG.get(y, yOff + 3 * yStride))
                .withLane(1, (long) LONG.get(y, yOff + 11 * yStride));
        var r4 = LongVector.broadcast(L128, (long) LONG.get(y, yOff + 4 * yStride))
                .withLane(1, (long) LONG.get(y, yOff + 12 * yStride));
        var r5 = LongVector.broadcast(L128, (long) LONG.get(y, yOff + 5 * yStride))
                .withLane(1, (long) LONG.get(y, yOff + 13 * yStride));
        var r6 = LongVector.broadcast(L128, (long) LONG.get(y, yOff + 6 * yStride))
                .withLane(1, (long) LONG.get(y, yOff + 14 * yStride));
        var r7 = LongVector.broadcast(L128, (long) LONG.get(y, yOff + 7 * yStride))
                .withLane(1, (long) LONG.get(y, yOff + 15 * yStride));
        LongVector x;
        x = r0.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r1).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00FF00FF00FF00FFL)));
        r1 = r1.lanewise(VectorOperators.XOR, x);
        r0 = r0.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r2.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r3).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00FF00FF00FF00FFL)));
        r3 = r3.lanewise(VectorOperators.XOR, x);
        r2 = r2.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r4.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r5).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00FF00FF00FF00FFL)));
        r5 = r5.lanewise(VectorOperators.XOR, x);
        r4 = r4.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r6.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r7).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00FF00FF00FF00FFL)));
        r7 = r7.lanewise(VectorOperators.XOR, x);
        r6 = r6.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r2).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x0000FFFF0000FFFFL)));
        r2 = r2.lanewise(VectorOperators.XOR, x);
        r0 = r0.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r1.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r3).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x0000FFFF0000FFFFL)));
        r3 = r3.lanewise(VectorOperators.XOR, x);
        r1 = r1.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r4.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r6).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x0000FFFF0000FFFFL)));
        r6 = r6.lanewise(VectorOperators.XOR, x);
        r4 = r4.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r5.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r7).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x0000FFFF0000FFFFL)));
        r7 = r7.lanewise(VectorOperators.XOR, x);
        r5 = r5.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r0.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r4).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00000000FFFFFFFFL)));
        r4 = r4.lanewise(VectorOperators.XOR, x);
        r0 = r0.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        x = r1.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r5).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00000000FFFFFFFFL)));
        r5 = r5.lanewise(VectorOperators.XOR, x);
        r1 = r1.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        x = r2.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r6).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00000000FFFFFFFFL)));
        r6 = r6.lanewise(VectorOperators.XOR, x);
        r2 = r2.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        x = r3.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r7).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00000000FFFFFFFFL)));
        r7 = r7.lanewise(VectorOperators.XOR, x);
        r3 = r3.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        BYTES_16.cast(r0.reinterpretShape(B128, 0)).intoArray(t, tOff + 0);
        BYTES_16.cast(r1.reinterpretShape(B128, 0)).intoArray(t, tOff + 32);
        BYTES_16.cast(r2.reinterpretShape(B128, 0)).intoArray(t, tOff + 64);
        BYTES_16.cast(r3.reinterpretShape(B128, 0)).intoArray(t, tOff + 96);
        BYTES_16.cast(r4.reinterpretShape(B128, 0)).intoArray(t, tOff + 128);
        BYTES_16.cast(r5.reinterpretShape(B128, 0)).intoArray(t, tOff + 160);
        BYTES_16.cast(r6.reinterpretShape(B128, 0)).intoArray(t, tOff + 192);
        BYTES_16.cast(r7.reinterpretShape(B128, 0)).intoArray(t, tOff + 224);
    }

    /**
     * Writes 8 column vectors produced by {@link #transposeInLuma} back to the luma plane.
     *
     * @param t       the scratch array
     * @param tOff    the index of the first transposed column
     * @param y       the luma plane
     * @param yOff    the index of the window's top-left pixel
     * @param yStride the luma row stride
     */
    private static void transposeOutLuma(byte[] t, int tOff, byte[] y, int yOff, int yStride) {
        var r0 = LONGS_2.cast(BYTES_16.cast(ByteVector.fromArray(B128, t, tOff + 0)).reinterpretShape(L128, 0));
        var r1 = LONGS_2.cast(BYTES_16.cast(ByteVector.fromArray(B128, t, tOff + 32)).reinterpretShape(L128, 0));
        var r2 = LONGS_2.cast(BYTES_16.cast(ByteVector.fromArray(B128, t, tOff + 64)).reinterpretShape(L128, 0));
        var r3 = LONGS_2.cast(BYTES_16.cast(ByteVector.fromArray(B128, t, tOff + 96)).reinterpretShape(L128, 0));
        var r4 = LONGS_2.cast(BYTES_16.cast(ByteVector.fromArray(B128, t, tOff + 128)).reinterpretShape(L128, 0));
        var r5 = LONGS_2.cast(BYTES_16.cast(ByteVector.fromArray(B128, t, tOff + 160)).reinterpretShape(L128, 0));
        var r6 = LONGS_2.cast(BYTES_16.cast(ByteVector.fromArray(B128, t, tOff + 192)).reinterpretShape(L128, 0));
        var r7 = LONGS_2.cast(BYTES_16.cast(ByteVector.fromArray(B128, t, tOff + 224)).reinterpretShape(L128, 0));
        LongVector x;
        x = r0.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r1).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00FF00FF00FF00FFL)));
        r1 = r1.lanewise(VectorOperators.XOR, x);
        r0 = r0.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r2.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r3).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00FF00FF00FF00FFL)));
        r3 = r3.lanewise(VectorOperators.XOR, x);
        r2 = r2.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r4.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r5).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00FF00FF00FF00FFL)));
        r5 = r5.lanewise(VectorOperators.XOR, x);
        r4 = r4.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r6.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r7).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00FF00FF00FF00FFL)));
        r7 = r7.lanewise(VectorOperators.XOR, x);
        r6 = r6.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r2).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x0000FFFF0000FFFFL)));
        r2 = r2.lanewise(VectorOperators.XOR, x);
        r0 = r0.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r1.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r3).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x0000FFFF0000FFFFL)));
        r3 = r3.lanewise(VectorOperators.XOR, x);
        r1 = r1.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r4.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r6).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x0000FFFF0000FFFFL)));
        r6 = r6.lanewise(VectorOperators.XOR, x);
        r4 = r4.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r5.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r7).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x0000FFFF0000FFFFL)));
        r7 = r7.lanewise(VectorOperators.XOR, x);
        r5 = r5.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r0.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r4).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00000000FFFFFFFFL)));
        r4 = r4.lanewise(VectorOperators.XOR, x);
        r0 = r0.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        x = r1.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r5).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00000000FFFFFFFFL)));
        r5 = r5.lanewise(VectorOperators.XOR, x);
        r1 = r1.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        x = r2.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r6).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00000000FFFFFFFFL)));
        r6 = r6.lanewise(VectorOperators.XOR, x);
        r2 = r2.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        x = r3.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r7).lanewise(VectorOperators.AND, LongVector.broadcast(L128, (long) (0x00000000FFFFFFFFL)));
        r7 = r7.lanewise(VectorOperators.XOR, x);
        r3 = r3.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        LONG.set(y, yOff, r0.lane(0));
        LONG.set(y, yOff + 8 * yStride, r0.lane(1));
        LONG.set(y, yOff + 1 * yStride, r1.lane(0));
        LONG.set(y, yOff + 9 * yStride, r1.lane(1));
        LONG.set(y, yOff + 2 * yStride, r2.lane(0));
        LONG.set(y, yOff + 10 * yStride, r2.lane(1));
        LONG.set(y, yOff + 3 * yStride, r3.lane(0));
        LONG.set(y, yOff + 11 * yStride, r3.lane(1));
        LONG.set(y, yOff + 4 * yStride, r4.lane(0));
        LONG.set(y, yOff + 12 * yStride, r4.lane(1));
        LONG.set(y, yOff + 5 * yStride, r5.lane(0));
        LONG.set(y, yOff + 13 * yStride, r5.lane(1));
        LONG.set(y, yOff + 6 * yStride, r6.lane(0));
        LONG.set(y, yOff + 14 * yStride, r6.lane(1));
        LONG.set(y, yOff + 7 * yStride, r7.lane(0));
        LONG.set(y, yOff + 15 * yStride, r7.lane(1));
    }

    /**
     * Writes the luma rows of the two windows {@link #transposeInWide} transposed for the left
     * macroblock edge back to the luma plane, 16 contiguous bytes per row.
     *
     * <p>Column {@code j} of the left window is at {@code tOff + 32 * j} and of the window right of
     * the edge at {@code tOff + 256 + 32 * j}. Each pair of columns is first arranged as the rows 0
     * to 7 of the left column, the same rows of the right column, then rows 8 to 15 of both, so the
     * transpose leaves the 16 bytes of row {@code i} in the low half of vector {@code i} and those of
     * row {@code i + 8} in its high half.
     *
     * @param t       the scratch array
     * @param tOff    the index of the left window's first transposed column
     * @param y       the luma plane
     * @param yOff    the index of the left window's top-left pixel, 8 pixels left of the macroblock
     * @param yStride the luma row stride
     */
    private static void transposeOutLumaPair(byte[] t, int tOff, byte[] y, int yOff, int yStride) {
        var r0 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 0).reinterpretShape(L256, 0)).rearrange(PAIR_LOW)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 256).reinterpretShape(L256, 0)).rearrange(PAIR_LOW), ODD_LANES);
        var r1 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 32).reinterpretShape(L256, 0)).rearrange(PAIR_LOW)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 288).reinterpretShape(L256, 0)).rearrange(PAIR_LOW), ODD_LANES);
        var r2 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 64).reinterpretShape(L256, 0)).rearrange(PAIR_LOW)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 320).reinterpretShape(L256, 0)).rearrange(PAIR_LOW), ODD_LANES);
        var r3 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 96).reinterpretShape(L256, 0)).rearrange(PAIR_LOW)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 352).reinterpretShape(L256, 0)).rearrange(PAIR_LOW), ODD_LANES);
        var r4 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 128).reinterpretShape(L256, 0)).rearrange(PAIR_LOW)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 384).reinterpretShape(L256, 0)).rearrange(PAIR_LOW), ODD_LANES);
        var r5 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 160).reinterpretShape(L256, 0)).rearrange(PAIR_LOW)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 416).reinterpretShape(L256, 0)).rearrange(PAIR_LOW), ODD_LANES);
        var r6 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 192).reinterpretShape(L256, 0)).rearrange(PAIR_LOW)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 448).reinterpretShape(L256, 0)).rearrange(PAIR_LOW), ODD_LANES);
        var r7 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 224).reinterpretShape(L256, 0)).rearrange(PAIR_LOW)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 480).reinterpretShape(L256, 0)).rearrange(PAIR_LOW), ODD_LANES);
        LongVector x;
        x = r0.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r1).lanewise(VectorOperators.AND, SWAP_BYTES);
        r1 = r1.lanewise(VectorOperators.XOR, x);
        r0 = r0.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r2.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r3).lanewise(VectorOperators.AND, SWAP_BYTES);
        r3 = r3.lanewise(VectorOperators.XOR, x);
        r2 = r2.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r4.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r5).lanewise(VectorOperators.AND, SWAP_BYTES);
        r5 = r5.lanewise(VectorOperators.XOR, x);
        r4 = r4.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r6.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r7).lanewise(VectorOperators.AND, SWAP_BYTES);
        r7 = r7.lanewise(VectorOperators.XOR, x);
        r6 = r6.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r2).lanewise(VectorOperators.AND, SWAP_SHORTS);
        r2 = r2.lanewise(VectorOperators.XOR, x);
        r0 = r0.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r1.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r3).lanewise(VectorOperators.AND, SWAP_SHORTS);
        r3 = r3.lanewise(VectorOperators.XOR, x);
        r1 = r1.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r4.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r6).lanewise(VectorOperators.AND, SWAP_SHORTS);
        r6 = r6.lanewise(VectorOperators.XOR, x);
        r4 = r4.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r5.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r7).lanewise(VectorOperators.AND, SWAP_SHORTS);
        r7 = r7.lanewise(VectorOperators.XOR, x);
        r5 = r5.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r0.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r4).lanewise(VectorOperators.AND, SWAP_INTS);
        r4 = r4.lanewise(VectorOperators.XOR, x);
        r0 = r0.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        x = r1.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r5).lanewise(VectorOperators.AND, SWAP_INTS);
        r5 = r5.lanewise(VectorOperators.XOR, x);
        r1 = r1.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        x = r2.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r6).lanewise(VectorOperators.AND, SWAP_INTS);
        r6 = r6.lanewise(VectorOperators.XOR, x);
        r2 = r2.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        x = r3.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r7).lanewise(VectorOperators.AND, SWAP_INTS);
        r7 = r7.lanewise(VectorOperators.XOR, x);
        r3 = r3.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        var lower = yOff + 8 * yStride;
        BYTES_16.cast(r0.reinterpretShape(B128, 0)).intoArray(y, yOff);
        BYTES_16.cast(r0.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(y, lower);
        BYTES_16.cast(r1.reinterpretShape(B128, 0)).intoArray(y, yOff + yStride);
        BYTES_16.cast(r1.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(y, lower + yStride);
        BYTES_16.cast(r2.reinterpretShape(B128, 0)).intoArray(y, yOff + 2 * yStride);
        BYTES_16.cast(r2.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(y, lower + 2 * yStride);
        BYTES_16.cast(r3.reinterpretShape(B128, 0)).intoArray(y, yOff + 3 * yStride);
        BYTES_16.cast(r3.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(y, lower + 3 * yStride);
        BYTES_16.cast(r4.reinterpretShape(B128, 0)).intoArray(y, yOff + 4 * yStride);
        BYTES_16.cast(r4.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(y, lower + 4 * yStride);
        BYTES_16.cast(r5.reinterpretShape(B128, 0)).intoArray(y, yOff + 5 * yStride);
        BYTES_16.cast(r5.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(y, lower + 5 * yStride);
        BYTES_16.cast(r6.reinterpretShape(B128, 0)).intoArray(y, yOff + 6 * yStride);
        BYTES_16.cast(r6.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(y, lower + 6 * yStride);
        BYTES_16.cast(r7.reinterpretShape(B128, 0)).intoArray(y, yOff + 7 * yStride);
        BYTES_16.cast(r7.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(y, lower + 7 * yStride);
    }

    /**
     * Writes the chroma rows of the two windows {@link #transposeInWide} transposed for the left
     * macroblock edge back to the chroma planes, 16 contiguous bytes per row.
     *
     * <p>The columns are laid out as for {@link #transposeOutLumaPair}; each pair is arranged as the
     * {@code u} rows of the left column, those of the right column, then the {@code v} rows of both,
     * so the transpose leaves row {@code i} of {@code u} in the low half of vector {@code i} and row
     * {@code i} of {@code v} in its high half.
     *
     * @param t        the scratch array
     * @param tOff     the index of the left window's first transposed column
     * @param u        the first chroma plane
     * @param v        the second chroma plane
     * @param uvOff    the index of the left window's top-left chroma pixel, 8 pixels left of the
     *                 macroblock
     * @param uvStride the chroma row stride
     */
    private static void transposeOutChromaPair(byte[] t, int tOff, byte[] u, byte[] v, int uvOff, int uvStride) {
        var r0 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 0).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 256).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH), ODD_LANES);
        var r1 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 32).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 288).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH), ODD_LANES);
        var r2 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 64).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 320).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH), ODD_LANES);
        var r3 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 96).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 352).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH), ODD_LANES);
        var r4 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 128).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 384).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH), ODD_LANES);
        var r5 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 160).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 416).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH), ODD_LANES);
        var r6 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 192).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 448).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH), ODD_LANES);
        var r7 = LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 224).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH)
                .blend(LONGS_4.cast(ByteVector.fromArray(B256, t, tOff + 480).reinterpretShape(L256, 0)).rearrange(PAIR_HIGH), ODD_LANES);
        LongVector x;
        x = r0.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r1).lanewise(VectorOperators.AND, SWAP_BYTES);
        r1 = r1.lanewise(VectorOperators.XOR, x);
        r0 = r0.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r2.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r3).lanewise(VectorOperators.AND, SWAP_BYTES);
        r3 = r3.lanewise(VectorOperators.XOR, x);
        r2 = r2.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r4.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r5).lanewise(VectorOperators.AND, SWAP_BYTES);
        r5 = r5.lanewise(VectorOperators.XOR, x);
        r4 = r4.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r6.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.XOR, r7).lanewise(VectorOperators.AND, SWAP_BYTES);
        r7 = r7.lanewise(VectorOperators.XOR, x);
        r6 = r6.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 8));
        x = r0.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r2).lanewise(VectorOperators.AND, SWAP_SHORTS);
        r2 = r2.lanewise(VectorOperators.XOR, x);
        r0 = r0.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r1.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r3).lanewise(VectorOperators.AND, SWAP_SHORTS);
        r3 = r3.lanewise(VectorOperators.XOR, x);
        r1 = r1.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r4.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r6).lanewise(VectorOperators.AND, SWAP_SHORTS);
        r6 = r6.lanewise(VectorOperators.XOR, x);
        r4 = r4.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r5.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.XOR, r7).lanewise(VectorOperators.AND, SWAP_SHORTS);
        r7 = r7.lanewise(VectorOperators.XOR, x);
        r5 = r5.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 16));
        x = r0.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r4).lanewise(VectorOperators.AND, SWAP_INTS);
        r4 = r4.lanewise(VectorOperators.XOR, x);
        r0 = r0.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        x = r1.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r5).lanewise(VectorOperators.AND, SWAP_INTS);
        r5 = r5.lanewise(VectorOperators.XOR, x);
        r1 = r1.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        x = r2.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r6).lanewise(VectorOperators.AND, SWAP_INTS);
        r6 = r6.lanewise(VectorOperators.XOR, x);
        r2 = r2.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        x = r3.lanewise(VectorOperators.LSHR, 32).lanewise(VectorOperators.XOR, r7).lanewise(VectorOperators.AND, SWAP_INTS);
        r7 = r7.lanewise(VectorOperators.XOR, x);
        r3 = r3.lanewise(VectorOperators.XOR, x.lanewise(VectorOperators.LSHL, 32));
        BYTES_16.cast(r0.reinterpretShape(B128, 0)).intoArray(u, uvOff);
        BYTES_16.cast(r0.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(v, uvOff);
        BYTES_16.cast(r1.reinterpretShape(B128, 0)).intoArray(u, uvOff + uvStride);
        BYTES_16.cast(r1.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(v, uvOff + uvStride);
        BYTES_16.cast(r2.reinterpretShape(B128, 0)).intoArray(u, uvOff + 2 * uvStride);
        BYTES_16.cast(r2.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(v, uvOff + 2 * uvStride);
        BYTES_16.cast(r3.reinterpretShape(B128, 0)).intoArray(u, uvOff + 3 * uvStride);
        BYTES_16.cast(r3.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(v, uvOff + 3 * uvStride);
        BYTES_16.cast(r4.reinterpretShape(B128, 0)).intoArray(u, uvOff + 4 * uvStride);
        BYTES_16.cast(r4.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(v, uvOff + 4 * uvStride);
        BYTES_16.cast(r5.reinterpretShape(B128, 0)).intoArray(u, uvOff + 5 * uvStride);
        BYTES_16.cast(r5.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(v, uvOff + 5 * uvStride);
        BYTES_16.cast(r6.reinterpretShape(B128, 0)).intoArray(u, uvOff + 6 * uvStride);
        BYTES_16.cast(r6.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(v, uvOff + 6 * uvStride);
        BYTES_16.cast(r7.reinterpretShape(B128, 0)).intoArray(u, uvOff + 7 * uvStride);
        BYTES_16.cast(r7.rearrange(SWAP_LONG_HALVES).reinterpretShape(B128, 0)).intoArray(v, uvOff + 7 * uvStride);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation filters a macroblock whose inner edges but not its left edge
     * are filtered, which only the first macroblock of a row is, with the scalar kernels:
     * that case is a few percent of the calls at most, and a vector kernel called that
     * rarely stays below the JIT compiler's thresholds for the C2 tier, running as
     * allocating method calls for the life of the process.
     */
    @Override
    public void filterNormal(byte[] y, int yOff, int yStride, byte[] u, byte[] v, int uvOff, int uvStride,
                             int mbLimit, int subLimit, int interiorLimit, int hevThreshold, int edges,
                             byte[] scratch) {
        if ((edges & (EDGE_LEFT | EDGE_INNER)) == EDGE_INNER) {
            scalar.filterNormal(y, yOff, yStride, u, v, uvOff, uvStride, mbLimit, subLimit, interiorLimit, hevThreshold,
                    edges, scratch);
            return;
        }
        var left = (edges & EDGE_LEFT) != 0;
        var inner = (edges & EDGE_INNER) != 0;
        var mbl = (byte) mbLimit;
        var sbl = (byte) subLimit;
        var il = (byte) interiorLimit;
        var hev = (byte) hevThreshold;
        if (left) {
            transposeInWide(y, yOff - 8, yStride, u, v, uvOff - 8, uvStride, scratch, COLS);
            transposeInWide(y, yOff, yStride, u, v, uvOff, uvStride, scratch, COLS + 8 * 32);
            if (inner) {
                transposeInLuma(y, yOff + 8, yStride, scratch, COLS + 16 * 32);
            }
            mbFilterWide(scratch, COLS + 8 * 32, 32, mbl, il, hev);
            if (inner) {
                subFilterWide(scratch, COLS + 12 * 32, 32, sbl, il, hev);
                subFilter(scratch, COLS + 16 * 32, 32, sbl, il, hev);
                subFilter(scratch, COLS + 20 * 32, 32, sbl, il, hev);
            }
            transposeOutLumaPair(scratch, COLS, y, yOff - 8, yStride);
            transposeOutChromaPair(scratch, COLS, u, v, uvOff - 8, uvStride);
            if (inner) {
                transposeOutLuma(scratch, COLS + 16 * 32, y, yOff + 8, yStride);
            }
        }
        var top = (edges & EDGE_TOP) != 0;
        if (top) {
            mbFilter(y, yOff, yStride, mbl, il, hev);
        }
        if (inner) {
            subFilter(y, yOff + 4 * yStride, yStride, sbl, il, hev);
            subFilter(y, yOff + 8 * yStride, yStride, sbl, il, hev);
            subFilter(y, yOff + 12 * yStride, yStride, sbl, il, hev);
        }
        if (top || inner) {
            var firstRow = top ? -4 : 0;
            var rows = 8 - firstRow;
            for (var r = 0; r < rows; r++) {
                var src = uvOff + (firstRow + r) * uvStride;
                BYTES_16.cast(LongVector.broadcast(L128, (long) LONG.get(u, src)).withLane(1, (long) LONG.get(v, src)).reinterpretShape(B128, 0)).intoArray(scratch, CHROMA_ROWS + r * 16);
            }
            if (top) {
                mbFilter(scratch, CHROMA_ROWS + 4 * 16, 16, mbl, il, hev);
            }
            if (inner) {
                subFilter(scratch, CHROMA_ROWS + (4 - firstRow) * 16, 16, sbl, il, hev);
            }
            for (var r = top ? 1 : 2; r < rows - 1; r++) {
                var dst = uvOff + (firstRow + r) * uvStride;
                var s = CHROMA_ROWS + r * 16;
                LONG.set(u, dst, (long) LONG.get(scratch, s));
                LONG.set(v, dst, (long) LONG.get(scratch, s + 8));
            }
        }
    }

    /**
     * Filters 16 pixels of a macroblock edge with the six-pixel filter of {@code vp8_mbfilter}.
     *
     * <p>The body is one flat sequence of vector operations with no vector-valued helper calls, so
     * the JIT keeps every vector in registers.
     *
     * @param a      the array
     * @param off    the index of the first pixel after the edge
     * @param step   the distance between pixels across the edge
     * @param limit  the edge limit
     * @param ilimit the interior limit
     * @param thresh the high edge variance threshold
     */
    private static void mbFilter(byte[] a, int off, int step, byte limit, byte ilimit, byte thresh) {
        var p3 = BYTES_16.cast(ByteVector.fromArray(B128, a, off - 4 * step));
        var p2 = BYTES_16.cast(ByteVector.fromArray(B128, a, off - 3 * step));
        var p1 = BYTES_16.cast(ByteVector.fromArray(B128, a, off - 2 * step));
        var p0 = BYTES_16.cast(ByteVector.fromArray(B128, a, off - step));
        var q0 = BYTES_16.cast(ByteVector.fromArray(B128, a, off));
        var q1 = BYTES_16.cast(ByteVector.fromArray(B128, a, off + step));
        var q2 = BYTES_16.cast(ByteVector.fromArray(B128, a, off + 2 * step));
        var q3 = BYTES_16.cast(ByteVector.fromArray(B128, a, off + 3 * step));
        var d10 = p1.lanewise(VectorOperators.SUSUB, p0).lanewise(VectorOperators.OR, p0.lanewise(VectorOperators.SUSUB, p1));
        var e10 = q1.lanewise(VectorOperators.SUSUB, q0).lanewise(VectorOperators.OR, q0.lanewise(VectorOperators.SUSUB, q1));
        var interior = p3.lanewise(VectorOperators.SUSUB, p2).lanewise(VectorOperators.OR, p2.lanewise(VectorOperators.SUSUB, p3))
                .lanewise(VectorOperators.UMAX, p2.lanewise(VectorOperators.SUSUB, p1).lanewise(VectorOperators.OR, p1.lanewise(VectorOperators.SUSUB, p2)))
                .lanewise(VectorOperators.UMAX, d10)
                .lanewise(VectorOperators.UMAX, e10)
                .lanewise(VectorOperators.UMAX, q2.lanewise(VectorOperators.SUSUB, q1).lanewise(VectorOperators.OR, q1.lanewise(VectorOperators.SUSUB, q2)))
                .lanewise(VectorOperators.UMAX, q3.lanewise(VectorOperators.SUSUB, q2).lanewise(VectorOperators.OR, q2.lanewise(VectorOperators.SUSUB, q3)));
        var dpq0 = p0.lanewise(VectorOperators.SUSUB, q0).lanewise(VectorOperators.OR, q0.lanewise(VectorOperators.SUSUB, p0));
        var dpq1 = p1.lanewise(VectorOperators.SUSUB, q1).lanewise(VectorOperators.OR, q1.lanewise(VectorOperators.SUSUB, p1))
                .lanewise(VectorOperators.LSHR, 1);
        var mask = interior.compare(VectorOperators.ULE, ByteVector.broadcast(B128, (byte) ilimit)).and(dpq0.lanewise(VectorOperators.SUADD, dpq0)
                .lanewise(VectorOperators.SUADD, dpq1).compare(VectorOperators.ULE, ByteVector.broadcast(B128, (byte) limit)));
        if (!mask.anyTrue()) {
            return;
        }
        var hev = d10.compare(VectorOperators.UGT, ByteVector.broadcast(B128, (byte) thresh)).or(e10.compare(VectorOperators.UGT, ByteVector.broadcast(B128, (byte) thresh)));
        var ps2 = p2.lanewise(VectorOperators.XOR, B128_128);
        var ps1 = p1.lanewise(VectorOperators.XOR, B128_128);
        var ps0 = p0.lanewise(VectorOperators.XOR, B128_128);
        var qs0 = q0.lanewise(VectorOperators.XOR, B128_128);
        var qs1 = q1.lanewise(VectorOperators.XOR, B128_128);
        var qs2 = q2.lanewise(VectorOperators.XOR, B128_128);
        var d = qs0.lanewise(VectorOperators.SSUB, ps0);
        var zero = BYTES_16.cast(ByteVector.zero(B128));
        var fv = zero.blend(ps1.lanewise(VectorOperators.SSUB, qs1).lanewise(VectorOperators.SADD, d)
                .lanewise(VectorOperators.SADD, d).lanewise(VectorOperators.SADD, d), mask);
        var f2 = zero.blend(fv, hev);
        qs0 = qs0.lanewise(VectorOperators.SSUB, f2.lanewise(VectorOperators.SADD, B128_4).lanewise(VectorOperators.ASHR, 3));
        ps0 = ps0.lanewise(VectorOperators.SADD, f2.lanewise(VectorOperators.SADD, B128_3).lanewise(VectorOperators.ASHR, 3));
        var w = SHORTS_16.cast(fv.blend(zero, hev).convertShape(VectorOperators.B2S, S256, 0));
        var u27 = BYTES_16.cast(w.lanewise(VectorOperators.MUL, S256_27).lanewise(VectorOperators.ADD, S256_63).lanewise(VectorOperators.ASHR, 7)
                .convertShape(VectorOperators.S2B, B128, 0));
        var u18 = BYTES_16.cast(w.lanewise(VectorOperators.MUL, S256_18).lanewise(VectorOperators.ADD, S256_63).lanewise(VectorOperators.ASHR, 7)
                .convertShape(VectorOperators.S2B, B128, 0));
        var u9 = BYTES_16.cast(w.lanewise(VectorOperators.MUL, S256_9).lanewise(VectorOperators.ADD, S256_63).lanewise(VectorOperators.ASHR, 7)
                .convertShape(VectorOperators.S2B, B128, 0));
        qs0.lanewise(VectorOperators.SSUB, u27).lanewise(VectorOperators.XOR, B128_128).intoArray(a, off);
        ps0.lanewise(VectorOperators.SADD, u27).lanewise(VectorOperators.XOR, B128_128).intoArray(a, off - step);
        qs1.lanewise(VectorOperators.SSUB, u18).lanewise(VectorOperators.XOR, B128_128).intoArray(a, off + step);
        ps1.lanewise(VectorOperators.SADD, u18).lanewise(VectorOperators.XOR, B128_128).intoArray(a, off - 2 * step);
        qs2.lanewise(VectorOperators.SSUB, u9).lanewise(VectorOperators.XOR, B128_128).intoArray(a, off + 2 * step);
        ps2.lanewise(VectorOperators.SADD, u9).lanewise(VectorOperators.XOR, B128_128).intoArray(a, off - 3 * step);
    }

    /**
     * Filters 16 pixels of an inner edge with the four-pixel filter of {@code vp8_filter}.
     *
     * @param a      the array
     * @param off    the index of the first pixel after the edge
     * @param step   the distance between pixels across the edge
     * @param limit  the edge limit
     * @param ilimit the interior limit
     * @param thresh the high edge variance threshold
     */
    private static void subFilter(byte[] a, int off, int step, byte limit, byte ilimit, byte thresh) {
        var p3 = BYTES_16.cast(ByteVector.fromArray(B128, a, off - 4 * step));
        var p2 = BYTES_16.cast(ByteVector.fromArray(B128, a, off - 3 * step));
        var p1 = BYTES_16.cast(ByteVector.fromArray(B128, a, off - 2 * step));
        var p0 = BYTES_16.cast(ByteVector.fromArray(B128, a, off - step));
        var q0 = BYTES_16.cast(ByteVector.fromArray(B128, a, off));
        var q1 = BYTES_16.cast(ByteVector.fromArray(B128, a, off + step));
        var q2 = BYTES_16.cast(ByteVector.fromArray(B128, a, off + 2 * step));
        var q3 = BYTES_16.cast(ByteVector.fromArray(B128, a, off + 3 * step));
        var d10 = p1.lanewise(VectorOperators.SUSUB, p0).lanewise(VectorOperators.OR, p0.lanewise(VectorOperators.SUSUB, p1));
        var e10 = q1.lanewise(VectorOperators.SUSUB, q0).lanewise(VectorOperators.OR, q0.lanewise(VectorOperators.SUSUB, q1));
        var interior = p3.lanewise(VectorOperators.SUSUB, p2).lanewise(VectorOperators.OR, p2.lanewise(VectorOperators.SUSUB, p3))
                .lanewise(VectorOperators.UMAX, p2.lanewise(VectorOperators.SUSUB, p1).lanewise(VectorOperators.OR, p1.lanewise(VectorOperators.SUSUB, p2)))
                .lanewise(VectorOperators.UMAX, d10)
                .lanewise(VectorOperators.UMAX, e10)
                .lanewise(VectorOperators.UMAX, q2.lanewise(VectorOperators.SUSUB, q1).lanewise(VectorOperators.OR, q1.lanewise(VectorOperators.SUSUB, q2)))
                .lanewise(VectorOperators.UMAX, q3.lanewise(VectorOperators.SUSUB, q2).lanewise(VectorOperators.OR, q2.lanewise(VectorOperators.SUSUB, q3)));
        var dpq0 = p0.lanewise(VectorOperators.SUSUB, q0).lanewise(VectorOperators.OR, q0.lanewise(VectorOperators.SUSUB, p0));
        var dpq1 = p1.lanewise(VectorOperators.SUSUB, q1).lanewise(VectorOperators.OR, q1.lanewise(VectorOperators.SUSUB, p1))
                .lanewise(VectorOperators.LSHR, 1);
        var mask = interior.compare(VectorOperators.ULE, ByteVector.broadcast(B128, (byte) ilimit)).and(dpq0.lanewise(VectorOperators.SUADD, dpq0)
                .lanewise(VectorOperators.SUADD, dpq1).compare(VectorOperators.ULE, ByteVector.broadcast(B128, (byte) limit)));
        if (!mask.anyTrue()) {
            return;
        }
        var hev = d10.compare(VectorOperators.UGT, ByteVector.broadcast(B128, (byte) thresh)).or(e10.compare(VectorOperators.UGT, ByteVector.broadcast(B128, (byte) thresh)));
        var ps1 = p1.lanewise(VectorOperators.XOR, B128_128);
        var ps0 = p0.lanewise(VectorOperators.XOR, B128_128);
        var qs0 = q0.lanewise(VectorOperators.XOR, B128_128);
        var qs1 = q1.lanewise(VectorOperators.XOR, B128_128);
        var zero = BYTES_16.cast(ByteVector.zero(B128));
        var d = qs0.lanewise(VectorOperators.SSUB, ps0);
        var fv = zero.blend(zero.blend(ps1.lanewise(VectorOperators.SSUB, qs1), hev).lanewise(VectorOperators.SADD, d)
                .lanewise(VectorOperators.SADD, d).lanewise(VectorOperators.SADD, d), mask);
        var filter1 = fv.lanewise(VectorOperators.SADD, B128_4).lanewise(VectorOperators.ASHR, 3);
        var filter2 = fv.lanewise(VectorOperators.SADD, B128_3).lanewise(VectorOperators.ASHR, 3);
        qs0.lanewise(VectorOperators.SSUB, filter1).lanewise(VectorOperators.XOR, B128_128).intoArray(a, off);
        ps0.lanewise(VectorOperators.SADD, filter2).lanewise(VectorOperators.XOR, B128_128).intoArray(a, off - step);
        var outer = filter1.lanewise(VectorOperators.ADD, B128_1).lanewise(VectorOperators.ASHR, 1).blend(zero, hev);
        qs1.lanewise(VectorOperators.SSUB, outer).lanewise(VectorOperators.XOR, B128_128).intoArray(a, off + step);
        ps1.lanewise(VectorOperators.SADD, outer).lanewise(VectorOperators.XOR, B128_128).intoArray(a, off - 2 * step);
    }

    /**
     * Filters 32 pixels of a macroblock edge with the six-pixel filter of {@code vp8_mbfilter}.
     *
     * <p>The body is one flat sequence of vector operations with no vector-valued helper calls, so
     * the JIT keeps every vector in registers. The weighted taps are computed in 16-bit lanes by
     * sign-extending the even and odd bytes of the filter value separately, which avoids a widening
     * conversion to a 512-bit shape.
     *
     * @param a      the array
     * @param off    the index of the first pixel after the edge
     * @param step   the distance between pixels across the edge
     * @param limit  the edge limit
     * @param ilimit the interior limit
     * @param thresh the high edge variance threshold
     */
    private static void mbFilterWide(byte[] a, int off, int step, byte limit, byte ilimit, byte thresh) {
        var p3 = BYTES_32.cast(ByteVector.fromArray(B256, a, off - 4 * step));
        var p2 = BYTES_32.cast(ByteVector.fromArray(B256, a, off - 3 * step));
        var p1 = BYTES_32.cast(ByteVector.fromArray(B256, a, off - 2 * step));
        var p0 = BYTES_32.cast(ByteVector.fromArray(B256, a, off - step));
        var q0 = BYTES_32.cast(ByteVector.fromArray(B256, a, off));
        var q1 = BYTES_32.cast(ByteVector.fromArray(B256, a, off + step));
        var q2 = BYTES_32.cast(ByteVector.fromArray(B256, a, off + 2 * step));
        var q3 = BYTES_32.cast(ByteVector.fromArray(B256, a, off + 3 * step));
        var d10 = p1.lanewise(VectorOperators.SUSUB, p0).lanewise(VectorOperators.OR, p0.lanewise(VectorOperators.SUSUB, p1));
        var e10 = q1.lanewise(VectorOperators.SUSUB, q0).lanewise(VectorOperators.OR, q0.lanewise(VectorOperators.SUSUB, q1));
        var interior = p3.lanewise(VectorOperators.SUSUB, p2).lanewise(VectorOperators.OR, p2.lanewise(VectorOperators.SUSUB, p3))
                .lanewise(VectorOperators.UMAX, p2.lanewise(VectorOperators.SUSUB, p1).lanewise(VectorOperators.OR, p1.lanewise(VectorOperators.SUSUB, p2)))
                .lanewise(VectorOperators.UMAX, d10)
                .lanewise(VectorOperators.UMAX, e10)
                .lanewise(VectorOperators.UMAX, q2.lanewise(VectorOperators.SUSUB, q1).lanewise(VectorOperators.OR, q1.lanewise(VectorOperators.SUSUB, q2)))
                .lanewise(VectorOperators.UMAX, q3.lanewise(VectorOperators.SUSUB, q2).lanewise(VectorOperators.OR, q2.lanewise(VectorOperators.SUSUB, q3)));
        var dpq0 = p0.lanewise(VectorOperators.SUSUB, q0).lanewise(VectorOperators.OR, q0.lanewise(VectorOperators.SUSUB, p0));
        var dpq1 = p1.lanewise(VectorOperators.SUSUB, q1).lanewise(VectorOperators.OR, q1.lanewise(VectorOperators.SUSUB, p1))
                .lanewise(VectorOperators.LSHR, 1);
        var mask = interior.compare(VectorOperators.ULE, ByteVector.broadcast(B256, (byte) ilimit)).and(dpq0.lanewise(VectorOperators.SUADD, dpq0)
                .lanewise(VectorOperators.SUADD, dpq1).compare(VectorOperators.ULE, ByteVector.broadcast(B256, (byte) limit)));
        if (!mask.anyTrue()) {
            return;
        }
        var hev = d10.compare(VectorOperators.UGT, ByteVector.broadcast(B256, (byte) thresh)).or(e10.compare(VectorOperators.UGT, ByteVector.broadcast(B256, (byte) thresh)));
        var ps2 = p2.lanewise(VectorOperators.XOR, B256_128);
        var ps1 = p1.lanewise(VectorOperators.XOR, B256_128);
        var ps0 = p0.lanewise(VectorOperators.XOR, B256_128);
        var qs0 = q0.lanewise(VectorOperators.XOR, B256_128);
        var qs1 = q1.lanewise(VectorOperators.XOR, B256_128);
        var qs2 = q2.lanewise(VectorOperators.XOR, B256_128);
        var d = qs0.lanewise(VectorOperators.SSUB, ps0);
        var zero = BYTES_32.cast(ByteVector.zero(B256));
        var fv = zero.blend(ps1.lanewise(VectorOperators.SSUB, qs1).lanewise(VectorOperators.SADD, d)
                .lanewise(VectorOperators.SADD, d).lanewise(VectorOperators.SADD, d), mask);
        var f2 = zero.blend(fv, hev);
        qs0 = qs0.lanewise(VectorOperators.SSUB, f2.lanewise(VectorOperators.SADD, B256_4).lanewise(VectorOperators.ASHR, 3));
        ps0 = ps0.lanewise(VectorOperators.SADD, f2.lanewise(VectorOperators.SADD, B256_3).lanewise(VectorOperators.ASHR, 3));
        var w = SHORTS_16.cast(fv.blend(zero, hev).reinterpretShape(S256, 0));
        var even = w.lanewise(VectorOperators.LSHL, 8).lanewise(VectorOperators.ASHR, 8);
        var odd = w.lanewise(VectorOperators.ASHR, 8);
        var u27 = BYTES_32.cast(even.lanewise(VectorOperators.MUL, S256_27).lanewise(VectorOperators.ADD, S256_63).lanewise(VectorOperators.ASHR, 7)
                .lanewise(VectorOperators.AND, S256_255)
                .lanewise(VectorOperators.OR, odd.lanewise(VectorOperators.MUL, S256_27).lanewise(VectorOperators.ADD, S256_63).lanewise(VectorOperators.ASHR, 7)
                        .lanewise(VectorOperators.LSHL, 8)).reinterpretShape(B256, 0));
        var u18 = BYTES_32.cast(even.lanewise(VectorOperators.MUL, S256_18).lanewise(VectorOperators.ADD, S256_63).lanewise(VectorOperators.ASHR, 7)
                .lanewise(VectorOperators.AND, S256_255)
                .lanewise(VectorOperators.OR, odd.lanewise(VectorOperators.MUL, S256_18).lanewise(VectorOperators.ADD, S256_63).lanewise(VectorOperators.ASHR, 7)
                        .lanewise(VectorOperators.LSHL, 8)).reinterpretShape(B256, 0));
        var u9 = BYTES_32.cast(even.lanewise(VectorOperators.MUL, S256_9).lanewise(VectorOperators.ADD, S256_63).lanewise(VectorOperators.ASHR, 7)
                .lanewise(VectorOperators.AND, S256_255)
                .lanewise(VectorOperators.OR, odd.lanewise(VectorOperators.MUL, S256_9).lanewise(VectorOperators.ADD, S256_63).lanewise(VectorOperators.ASHR, 7)
                        .lanewise(VectorOperators.LSHL, 8)).reinterpretShape(B256, 0));
        qs0.lanewise(VectorOperators.SSUB, u27).lanewise(VectorOperators.XOR, B256_128).intoArray(a, off);
        ps0.lanewise(VectorOperators.SADD, u27).lanewise(VectorOperators.XOR, B256_128).intoArray(a, off - step);
        qs1.lanewise(VectorOperators.SSUB, u18).lanewise(VectorOperators.XOR, B256_128).intoArray(a, off + step);
        ps1.lanewise(VectorOperators.SADD, u18).lanewise(VectorOperators.XOR, B256_128).intoArray(a, off - 2 * step);
        qs2.lanewise(VectorOperators.SSUB, u9).lanewise(VectorOperators.XOR, B256_128).intoArray(a, off + 2 * step);
        ps2.lanewise(VectorOperators.SADD, u9).lanewise(VectorOperators.XOR, B256_128).intoArray(a, off - 3 * step);
    }

    /**
     * Filters 32 pixels of an inner edge with the four-pixel filter of {@code vp8_filter}.
     *
     * @param a      the array
     * @param off    the index of the first pixel after the edge
     * @param step   the distance between pixels across the edge
     * @param limit  the edge limit
     * @param ilimit the interior limit
     * @param thresh the high edge variance threshold
     */
    private static void subFilterWide(byte[] a, int off, int step, byte limit, byte ilimit, byte thresh) {
        var p3 = BYTES_32.cast(ByteVector.fromArray(B256, a, off - 4 * step));
        var p2 = BYTES_32.cast(ByteVector.fromArray(B256, a, off - 3 * step));
        var p1 = BYTES_32.cast(ByteVector.fromArray(B256, a, off - 2 * step));
        var p0 = BYTES_32.cast(ByteVector.fromArray(B256, a, off - step));
        var q0 = BYTES_32.cast(ByteVector.fromArray(B256, a, off));
        var q1 = BYTES_32.cast(ByteVector.fromArray(B256, a, off + step));
        var q2 = BYTES_32.cast(ByteVector.fromArray(B256, a, off + 2 * step));
        var q3 = BYTES_32.cast(ByteVector.fromArray(B256, a, off + 3 * step));
        var d10 = p1.lanewise(VectorOperators.SUSUB, p0).lanewise(VectorOperators.OR, p0.lanewise(VectorOperators.SUSUB, p1));
        var e10 = q1.lanewise(VectorOperators.SUSUB, q0).lanewise(VectorOperators.OR, q0.lanewise(VectorOperators.SUSUB, q1));
        var interior = p3.lanewise(VectorOperators.SUSUB, p2).lanewise(VectorOperators.OR, p2.lanewise(VectorOperators.SUSUB, p3))
                .lanewise(VectorOperators.UMAX, p2.lanewise(VectorOperators.SUSUB, p1).lanewise(VectorOperators.OR, p1.lanewise(VectorOperators.SUSUB, p2)))
                .lanewise(VectorOperators.UMAX, d10)
                .lanewise(VectorOperators.UMAX, e10)
                .lanewise(VectorOperators.UMAX, q2.lanewise(VectorOperators.SUSUB, q1).lanewise(VectorOperators.OR, q1.lanewise(VectorOperators.SUSUB, q2)))
                .lanewise(VectorOperators.UMAX, q3.lanewise(VectorOperators.SUSUB, q2).lanewise(VectorOperators.OR, q2.lanewise(VectorOperators.SUSUB, q3)));
        var dpq0 = p0.lanewise(VectorOperators.SUSUB, q0).lanewise(VectorOperators.OR, q0.lanewise(VectorOperators.SUSUB, p0));
        var dpq1 = p1.lanewise(VectorOperators.SUSUB, q1).lanewise(VectorOperators.OR, q1.lanewise(VectorOperators.SUSUB, p1))
                .lanewise(VectorOperators.LSHR, 1);
        var mask = interior.compare(VectorOperators.ULE, ByteVector.broadcast(B256, (byte) ilimit)).and(dpq0.lanewise(VectorOperators.SUADD, dpq0)
                .lanewise(VectorOperators.SUADD, dpq1).compare(VectorOperators.ULE, ByteVector.broadcast(B256, (byte) limit)));
        if (!mask.anyTrue()) {
            return;
        }
        var hev = d10.compare(VectorOperators.UGT, ByteVector.broadcast(B256, (byte) thresh)).or(e10.compare(VectorOperators.UGT, ByteVector.broadcast(B256, (byte) thresh)));
        var ps1 = p1.lanewise(VectorOperators.XOR, B256_128);
        var ps0 = p0.lanewise(VectorOperators.XOR, B256_128);
        var qs0 = q0.lanewise(VectorOperators.XOR, B256_128);
        var qs1 = q1.lanewise(VectorOperators.XOR, B256_128);
        var zero = BYTES_32.cast(ByteVector.zero(B256));
        var d = qs0.lanewise(VectorOperators.SSUB, ps0);
        var fv = zero.blend(zero.blend(ps1.lanewise(VectorOperators.SSUB, qs1), hev).lanewise(VectorOperators.SADD, d)
                .lanewise(VectorOperators.SADD, d).lanewise(VectorOperators.SADD, d), mask);
        var filter1 = fv.lanewise(VectorOperators.SADD, B256_4).lanewise(VectorOperators.ASHR, 3);
        var filter2 = fv.lanewise(VectorOperators.SADD, B256_3).lanewise(VectorOperators.ASHR, 3);
        qs0.lanewise(VectorOperators.SSUB, filter1).lanewise(VectorOperators.XOR, B256_128).intoArray(a, off);
        ps0.lanewise(VectorOperators.SADD, filter2).lanewise(VectorOperators.XOR, B256_128).intoArray(a, off - step);
        var outer = filter1.lanewise(VectorOperators.ADD, B256_1).lanewise(VectorOperators.ASHR, 1).blend(zero, hev);
        qs1.lanewise(VectorOperators.SSUB, outer).lanewise(VectorOperators.XOR, B256_128).intoArray(a, off + step);
        ps1.lanewise(VectorOperators.SADD, outer).lanewise(VectorOperators.XOR, B256_128).intoArray(a, off - 2 * step);
    }

    /**
     * Filters 16 pixels of an edge with the simple filter.
     *
     * @param a     the array
     * @param off   the index of the first pixel after the edge
     * @param step  the distance between pixels across the edge
     * @param limit the edge limit
     */
    private static void simpleFilter(byte[] a, int off, int step, byte limit) {
        var p1 = BYTES_16.cast(ByteVector.fromArray(B128, a, off - 2 * step));
        var p0 = BYTES_16.cast(ByteVector.fromArray(B128, a, off - step));
        var q0 = BYTES_16.cast(ByteVector.fromArray(B128, a, off));
        var q1 = BYTES_16.cast(ByteVector.fromArray(B128, a, off + step));
        var dpq0 = p0.lanewise(VectorOperators.SUSUB, q0).lanewise(VectorOperators.OR, q0.lanewise(VectorOperators.SUSUB, p0));
        var dpq1 = p1.lanewise(VectorOperators.SUSUB, q1).lanewise(VectorOperators.OR, q1.lanewise(VectorOperators.SUSUB, p1))
                .lanewise(VectorOperators.LSHR, 1);
        var mask = dpq0.lanewise(VectorOperators.SUADD, dpq0).lanewise(VectorOperators.SUADD, dpq1)
                .compare(VectorOperators.ULE, ByteVector.broadcast(B128, (byte) limit));
        if (!mask.anyTrue()) {
            return;
        }
        var ps1 = p1.lanewise(VectorOperators.XOR, B128_128);
        var ps0 = p0.lanewise(VectorOperators.XOR, B128_128);
        var qs0 = q0.lanewise(VectorOperators.XOR, B128_128);
        var qs1 = q1.lanewise(VectorOperators.XOR, B128_128);
        var d = qs0.lanewise(VectorOperators.SSUB, ps0);
        var fv = ByteVector.zero(B128).blend(ps1.lanewise(VectorOperators.SSUB, qs1).lanewise(VectorOperators.SADD, d)
                .lanewise(VectorOperators.SADD, d).lanewise(VectorOperators.SADD, d), mask);
        var filter1 = fv.lanewise(VectorOperators.SADD, B128_4).lanewise(VectorOperators.ASHR, 3);
        var filter2 = fv.lanewise(VectorOperators.SADD, B128_3).lanewise(VectorOperators.ASHR, 3);
        qs0.lanewise(VectorOperators.SSUB, filter1).lanewise(VectorOperators.XOR, B128_128).intoArray(a, off);
        ps0.lanewise(VectorOperators.SADD, filter2).lanewise(VectorOperators.XOR, B128_128).intoArray(a, off - step);
    }

    @Override
    public void filterSimple(byte[] y, int yOff, int yStride, int mbLimit, int subLimit, int edges, byte[] scratch) {
        var left = (edges & EDGE_LEFT) != 0;
        var inner = (edges & EDGE_INNER) != 0;
        var mbl = (byte) mbLimit;
        var sbl = (byte) subLimit;
        if (left || inner) {
            if (left) {
                transposeInLuma(y, yOff - 8, yStride, scratch, COLS);
            }
            transposeInLuma(y, yOff, yStride, scratch, COLS + 8 * 32);
            if (inner) {
                transposeInLuma(y, yOff + 8, yStride, scratch, COLS + 16 * 32);
            }
            if (left) {
                simpleFilter(scratch, COLS + 8 * 32, 32, mbl);
            }
            if (inner) {
                simpleFilter(scratch, COLS + 12 * 32, 32, sbl);
                simpleFilter(scratch, COLS + 16 * 32, 32, sbl);
                simpleFilter(scratch, COLS + 20 * 32, 32, sbl);
            }
            if (left) {
                transposeOutLuma(scratch, COLS, y, yOff - 8, yStride);
            }
            transposeOutLuma(scratch, COLS + 8 * 32, y, yOff, yStride);
            if (inner) {
                transposeOutLuma(scratch, COLS + 16 * 32, y, yOff + 8, yStride);
            }
        }
        if ((edges & EDGE_TOP) != 0) {
            simpleFilter(y, yOff, yStride, mbl);
        }
        if (inner) {
            simpleFilter(y, yOff + 4 * yStride, yStride, sbl);
            simpleFilter(y, yOff + 8 * yStride, yStride, sbl);
            simpleFilter(y, yOff + 12 * yStride, yStride, sbl);
        }
    }
}
