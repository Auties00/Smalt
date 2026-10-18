package com.github.auties00.smalt.capture.internal.convert;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link ConversionKernel} with the Vector API; the output equals
 * {@link ScalarConversionKernel} byte for byte.
 *
 * <p>Pixels and macropixels load as one 32-bit lane per four bytes, little-endian, so the byte at
 * position {@code i} is {@code (lane >>> 8 * i) & 0xFF}: 8 lanes on 256-bit hardware, 4 on
 * 128-bit hardware. A 2x2 chroma sum adds the two rows lane-wise and then the even and odd lanes of
 * two consecutive vectors, with R and B packed into the two halves of a lane so one addition sums
 * both; a layout whose R and B are not two bytes apart has its chroma converted by the scalar kernel.
 * Packed luma and interleaved pairs load as 16-bit lanes. Each loop stops where its next load or
 * store would leave the row, and {@link ScalarConversionKernel} converts the rest.
 *
 * @implNote This implementation follows the kernel rules of
 * {@link com.github.auties00.smalt.internal.simd.Simd}: per-shape operations only, every
 * receiver cast to the exact class of its species, one loop per method, and interleaves of two
 * vectors spelled as two single-vector {@code rearrange} calls selected by a {@code blend}.
 */
public final class VectorConversionKernel implements ConversionKernel {
    private static final VectorSpecies<Integer> I = IntVector.SPECIES_PREFERRED.vectorBitSize() >= 256
            ? IntVector.SPECIES_256 : IntVector.SPECIES_128;

    /**
     * The number of 32-bit lanes per vector.
     */
    private static final int L = I.length();

    /**
     * The byte species of the same size as {@link #I}, used for every load.
     */
    private static final VectorSpecies<Byte> BP = VectorSpecies.of(byte.class, VectorShape.forBitSize(I.vectorBitSize()));

    /**
     * The short species of the same size as {@link #I}.
     */
    private static final VectorSpecies<Short> S = VectorSpecies.of(short.class, VectorShape.forBitSize(I.vectorBitSize()));

    /**
     * The byte species of half the size of {@link #I}, receiving narrowed 16-bit lanes.
     */
    private static final VectorSpecies<Byte> BH = VectorSpecies.of(byte.class, VectorShape.forBitSize(I.vectorBitSize() / 2));

    /**
     * The 64-bit byte species receiving narrowed 32-bit lanes; a store writes 8 bytes, of which the
     * first {@link #L} are results.
     */
    private static final VectorSpecies<Byte> B8 = ByteVector.SPECIES_64;

    private static final Class<IntVector> INT_VECTOR = vectorClass(I);
    private static final Class<ByteVector> BYTE_VECTOR = vectorClass(BP);
    private static final Class<ShortVector> SHORT_VECTOR = vectorClass(S);
    private static final Class<ByteVector> HALF_BYTE_VECTOR = vectorClass(BH);
    private static final Class<ByteVector> BYTE_64 = vectorClass(B8);

    private static final IntVector I_0 = IntVector.broadcast(I, 0);
    private static final IntVector I_1 = IntVector.broadcast(I, 1);
    private static final IntVector I_255 = IntVector.broadcast(I, 0xFF);
    private static final IntVector I_FF00 = IntVector.broadcast(I, 0xFF00);
    private static final IntVector I_FF0000 = IntVector.broadcast(I, 0xFF0000);

    /**
     * The mask of one 10-bit channel field of a 2x2 sum.
     */
    private static final IntVector I_FIELD = IntVector.broadcast(I, 0x3FF);

    /**
     * Selects the even lanes of the concatenation of two vectors, from the first vector where
     * {@link #SECOND} is unset and from the second where it is set.
     */
    private static final VectorShuffle<Integer> EVEN = shuffle(0);

    /**
     * Selects the odd lanes of the concatenation of two vectors, like {@link #EVEN}.
     */
    private static final VectorShuffle<Integer> ODD = shuffle(1);

    /**
     * The upper half of the lanes, which {@link #EVEN} and {@link #ODD} take from the second vector.
     */
    private static final VectorMask<Integer> SECOND = VectorMask.fromLong(I, -1L << (L / 2));

    /**
     * The number of samples past the last result that a store of narrowed 32-bit lanes writes.
     */
    private static final int SLACK = Math.max(0, B8.length() - L);

    private final ScalarConversionKernel scalar = new ScalarConversionKernel();

    private VectorConversionKernel() {
    }

    /**
     * Creates the kernel; called only when {@link com.github.auties00.smalt.internal.simd.Simd#ENABLED}.
     *
     * @return the kernel
     */
    public static VectorConversionKernel create() {
        return new VectorConversionKernel();
    }

    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    /**
     * Builds the shuffle whose lane {@code j} takes lane {@code 2j + parity} of two concatenated
     * vectors, modulo the lane count.
     */
    private static VectorShuffle<Integer> shuffle(int parity) {
        var indexes = new int[L];
        for (var j = 0; j < L; j++) {
            var lane = 2 * j + parity;
            indexes[j] = lane < L ? lane : lane - L;
        }
        return VectorShuffle.fromArray(I, indexes, 0);
    }

    /**
     * Returns where the vector loop of a row stops: a multiple of {@code step} such that every
     * iteration's loads and stores, which reach {@code slack} samples past it, stay in the row.
     */
    private static int vectorEnd(int count, int step, int slack) {
        var last = count - step - slack;
        return last < 0 ? 0 : (last / step + 1) * step;
    }

    @Override
    public void rgbToLuma(byte[] src, int off, int width, RgbLayout layout, YuvCoefficients coefficients,
                          byte[] y, int yOff) {
        var first = firstByte(layout);
        var end = first < 0 ? 0 : vectorEnd(width - first, L, SLACK);
        var c = coefficients;
        rgbToLumaRow(src, off + first, y, yOff, end, weight(layout, first, c.yr, c.yg, c.yb),
                weight(layout, first + 1, c.yr, c.yg, c.yb), weight(layout, first + 2, c.yr, c.yg, c.yb), c.yBias);
        scalar.rgbToLumaRange(src, off, layout, coefficients, y, yOff, end, width);
    }

    /**
     * Computes the luma of the pixels {@code [0, end)} of a row whose channels start at {@code off}: the
     * three low bytes of each loaded lane are the channels, with the coefficients {@code w0}, {@code w1}
     * and {@code w2}.
     */
    private static void rgbToLumaRow(byte[] src, int off, byte[] y, int yOff, int end,
                                     int w0, int w1, int w2, int bias) {
        for (var x = 0; x < end; x += L) {
            var pixels = INT_VECTOR.cast(BYTE_VECTOR.cast(ByteVector.fromArray(BP, src, off + 4 * x)).reinterpretShape(I, 0));
            var c0 = pixels.lanewise(VectorOperators.AND, I_255);
            var c1 = INT_VECTOR.cast(pixels.lanewise(VectorOperators.LSHR, 8)).lanewise(VectorOperators.AND, I_255);
            var c2 = INT_VECTOR.cast(pixels.lanewise(VectorOperators.LSHR, 16)).lanewise(VectorOperators.AND, I_255);
            var sum = c0.lanewise(VectorOperators.MUL, IntVector.broadcast(I, w0))
                    .lanewise(VectorOperators.ADD, c1.lanewise(VectorOperators.MUL, IntVector.broadcast(I, w1)))
                    .lanewise(VectorOperators.ADD, c2.lanewise(VectorOperators.MUL, IntVector.broadcast(I, w2)))
                    .lanewise(VectorOperators.ADD, IntVector.broadcast(I, bias));
            BYTE_64.cast(INT_VECTOR.cast(sum.lanewise(VectorOperators.ASHR, YuvCoefficients.BITS))
                    .lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B8, 0)).intoArray(y, yOff + x);
        }
    }

    @Override
    public void rgbToChroma(byte[] src, int off0, int off1, int pairs, RgbLayout layout, YuvCoefficients coefficients,
                            byte[] u, byte[] v, int cOff) {
        var first = firstByte(layout);
        var end = first < 0 ? 0 : vectorEnd(pairs - first, L, SLACK);
        var c = coefficients;
        rgbToChromaRow(src, off0 + first, off1 + first, u, v, cOff, end,
                weight(layout, first, c.ur, c.ug, c.ub), weight(layout, first + 1, c.ur, c.ug, c.ub),
                weight(layout, first + 2, c.ur, c.ug, c.ub), weight(layout, first, c.vr, c.vg, c.vb),
                weight(layout, first + 1, c.vr, c.vg, c.vb), weight(layout, first + 2, c.vr, c.vg, c.vb), c.cBias);
        scalar.rgbToChromaRange(src, off0, off1, layout, coefficients, u, v, cOff, end, pairs);
    }

    /**
     * Computes the chroma samples {@code [0, end)} of a row pair whose channels start at {@code off0} and
     * {@code off1}: the three low bytes of each loaded lane are the channels, with the Cb coefficients
     * {@code u0} to {@code u2} and the Cr coefficients {@code v0} to {@code v2}.
     */
    private static void rgbToChromaRow(byte[] src, int off0, int off1, byte[] u, byte[] v, int cOff, int end,
                                       int u0, int u1, int u2, int v0, int v1, int v2, int bias) {
        for (var k = 0; k < end; k += L) {
            var a0 = INT_VECTOR.cast(BYTE_VECTOR.cast(ByteVector.fromArray(BP, src, off0 + 8 * k)).reinterpretShape(I, 0));
            var a1 = INT_VECTOR.cast(BYTE_VECTOR.cast(ByteVector.fromArray(BP, src, off1 + 8 * k)).reinterpretShape(I, 0));
            var b0 = INT_VECTOR.cast(BYTE_VECTOR.cast(ByteVector.fromArray(BP, src, off0 + 8 * k + 4 * L)).reinterpretShape(I, 0));
            var b1 = INT_VECTOR.cast(BYTE_VECTOR.cast(ByteVector.fromArray(BP, src, off1 + 8 * k + 4 * L)).reinterpretShape(I, 0));
            // the three channel bytes spread into 10-bit fields, so one addition sums all of them
            var first = a0.lanewise(VectorOperators.AND, I_255)
                    .lanewise(VectorOperators.OR, INT_VECTOR.cast(a0.lanewise(VectorOperators.AND, I_FF00)).lanewise(VectorOperators.LSHL, 2))
                    .lanewise(VectorOperators.OR, INT_VECTOR.cast(a0.lanewise(VectorOperators.AND, I_FF0000)).lanewise(VectorOperators.LSHL, 4))
                    .lanewise(VectorOperators.ADD, a1.lanewise(VectorOperators.AND, I_255)
                            .lanewise(VectorOperators.OR, INT_VECTOR.cast(a1.lanewise(VectorOperators.AND, I_FF00)).lanewise(VectorOperators.LSHL, 2))
                            .lanewise(VectorOperators.OR, INT_VECTOR.cast(a1.lanewise(VectorOperators.AND, I_FF0000)).lanewise(VectorOperators.LSHL, 4)));
            var second = b0.lanewise(VectorOperators.AND, I_255)
                    .lanewise(VectorOperators.OR, INT_VECTOR.cast(b0.lanewise(VectorOperators.AND, I_FF00)).lanewise(VectorOperators.LSHL, 2))
                    .lanewise(VectorOperators.OR, INT_VECTOR.cast(b0.lanewise(VectorOperators.AND, I_FF0000)).lanewise(VectorOperators.LSHL, 4))
                    .lanewise(VectorOperators.ADD, b1.lanewise(VectorOperators.AND, I_255)
                            .lanewise(VectorOperators.OR, INT_VECTOR.cast(b1.lanewise(VectorOperators.AND, I_FF00)).lanewise(VectorOperators.LSHL, 2))
                            .lanewise(VectorOperators.OR, INT_VECTOR.cast(b1.lanewise(VectorOperators.AND, I_FF0000)).lanewise(VectorOperators.LSHL, 4)));
            var sums = INT_VECTOR.cast(first.rearrange(EVEN).blend(second.rearrange(EVEN), SECOND)
                    .lanewise(VectorOperators.ADD, first.rearrange(ODD).blend(second.rearrange(ODD), SECOND)));
            var c0 = sums.lanewise(VectorOperators.AND, I_FIELD);
            var c1 = INT_VECTOR.cast(sums.lanewise(VectorOperators.LSHR, 10)).lanewise(VectorOperators.AND, I_FIELD);
            var c2 = INT_VECTOR.cast(sums.lanewise(VectorOperators.LSHR, 20));
            var cb = c0.lanewise(VectorOperators.MUL, IntVector.broadcast(I, u0))
                    .lanewise(VectorOperators.ADD, c1.lanewise(VectorOperators.MUL, IntVector.broadcast(I, u1)))
                    .lanewise(VectorOperators.ADD, c2.lanewise(VectorOperators.MUL, IntVector.broadcast(I, u2)))
                    .lanewise(VectorOperators.ADD, IntVector.broadcast(I, bias));
            var cr = c0.lanewise(VectorOperators.MUL, IntVector.broadcast(I, v0))
                    .lanewise(VectorOperators.ADD, c1.lanewise(VectorOperators.MUL, IntVector.broadcast(I, v1)))
                    .lanewise(VectorOperators.ADD, c2.lanewise(VectorOperators.MUL, IntVector.broadcast(I, v2)))
                    .lanewise(VectorOperators.ADD, IntVector.broadcast(I, bias));
            BYTE_64.cast(INT_VECTOR.cast(cb.lanewise(VectorOperators.ASHR, YuvCoefficients.CHROMA_SHIFT))
                    .lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B8, 0)).intoArray(u, cOff + k);
            BYTE_64.cast(INT_VECTOR.cast(cr.lanewise(VectorOperators.ASHR, YuvCoefficients.CHROMA_SHIFT))
                    .lanewise(VectorOperators.MAX, I_0).lanewise(VectorOperators.MIN, I_255)
                    .convertShape(VectorOperators.I2B, B8, 0)).intoArray(v, cOff + k);
        }
    }

    /**
     * Returns the first of the three consecutive bytes a layout's channels occupy, or -1 when they are
     * not consecutive. Loads start at that byte, so the channels are the three low bytes of each lane;
     * when it is 1, a lane's top byte is the next pixel's first byte and the vector loop stops one pixel
     * earlier, so the loads stay in the row.
     */
    private static int firstByte(RgbLayout layout) {
        var low = Math.min(layout.rIndex(), Math.min(layout.gIndex(), layout.bIndex()));
        var high = Math.max(layout.rIndex(), Math.max(layout.gIndex(), layout.bIndex()));
        return high - low == 2 ? low : -1;
    }

    /**
     * Returns the coefficient, among those of R, G and B, of the channel at a byte position.
     */
    private static int weight(RgbLayout layout, int position, int r, int g, int b) {
        return position == layout.rIndex() ? r : position == layout.gIndex() ? g : b;
    }

    @Override
    public void packedLuma(byte[] src, int off, int width, int lumaIndex, byte[] y, int yOff) {
        var step = S.length();
        var end = vectorEnd(width, step, 0);
        var shift = 8 * lumaIndex;
        for (var x = 0; x < end; x += step) {
            var samples = SHORT_VECTOR.cast(BYTE_VECTOR.cast(ByteVector.fromArray(BP, src, off + 2 * x)).reinterpretShape(S, 0));
            HALF_BYTE_VECTOR.cast(SHORT_VECTOR.cast(samples.lanewise(VectorOperators.LSHR, shift))
                    .convertShape(VectorOperators.S2B, BH, 0)).intoArray(y, yOff + x);
        }
        scalar.packedLumaRange(src, off, lumaIndex, y, yOff, end, width);
    }

    @Override
    public void packedChroma(byte[] src, int off0, int off1, int pairs, int cbIndex, int crIndex,
                             byte[] u, byte[] v, int cOff) {
        var end = vectorEnd(pairs, L, SLACK);
        packedChromaRow(src, off0, off1, u, v, cOff, end, 8 * cbIndex, 8 * crIndex);
        scalar.packedChromaRange(src, off0, off1, cbIndex, crIndex, u, v, cOff, end, pairs);
    }

    /**
     * Averages the chroma of the macropixels {@code [0, end)} of two packed rows; {@code cbs} and
     * {@code crs} are the bit positions of Cb and Cr in a lane.
     */
    private static void packedChromaRow(byte[] src, int off0, int off1, byte[] u, byte[] v, int cOff, int end,
                                        int cbs, int crs) {
        for (var k = 0; k < end; k += L) {
            var upper = INT_VECTOR.cast(BYTE_VECTOR.cast(ByteVector.fromArray(BP, src, off0 + 4 * k)).reinterpretShape(I, 0));
            var lower = INT_VECTOR.cast(BYTE_VECTOR.cast(ByteVector.fromArray(BP, src, off1 + 4 * k)).reinterpretShape(I, 0));
            var cb = INT_VECTOR.cast(upper.lanewise(VectorOperators.LSHR, cbs)).lanewise(VectorOperators.AND, I_255)
                    .lanewise(VectorOperators.ADD, INT_VECTOR.cast(lower.lanewise(VectorOperators.LSHR, cbs)).lanewise(VectorOperators.AND, I_255))
                    .lanewise(VectorOperators.ADD, I_1);
            var cr = INT_VECTOR.cast(upper.lanewise(VectorOperators.LSHR, crs)).lanewise(VectorOperators.AND, I_255)
                    .lanewise(VectorOperators.ADD, INT_VECTOR.cast(lower.lanewise(VectorOperators.LSHR, crs)).lanewise(VectorOperators.AND, I_255))
                    .lanewise(VectorOperators.ADD, I_1);
            BYTE_64.cast(INT_VECTOR.cast(cb.lanewise(VectorOperators.LSHR, 1)).convertShape(VectorOperators.I2B, B8, 0))
                    .intoArray(u, cOff + k);
            BYTE_64.cast(INT_VECTOR.cast(cr.lanewise(VectorOperators.LSHR, 1)).convertShape(VectorOperators.I2B, B8, 0))
                    .intoArray(v, cOff + k);
        }
    }

    @Override
    public void deinterleave(byte[] src, int off, int count, byte[] first, byte[] second, int dOff) {
        var step = S.length();
        var end = vectorEnd(count, step, 0);
        for (var k = 0; k < end; k += step) {
            var pairs = SHORT_VECTOR.cast(BYTE_VECTOR.cast(ByteVector.fromArray(BP, src, off + 2 * k)).reinterpretShape(S, 0));
            HALF_BYTE_VECTOR.cast(pairs.convertShape(VectorOperators.S2B, BH, 0)).intoArray(first, dOff + k);
            HALF_BYTE_VECTOR.cast(SHORT_VECTOR.cast(pairs.lanewise(VectorOperators.LSHR, 8))
                    .convertShape(VectorOperators.S2B, BH, 0)).intoArray(second, dOff + k);
        }
        scalar.deinterleaveRange(src, off, first, second, dOff, end, count);
    }

    @Override
    public void averageRows(byte[] a, int aOff, byte[] b, int bOff, int count, byte[] dst, int dOff) {
        var step = BP.length();
        var end = vectorEnd(count, step, 0);
        for (var i = 0; i < end; i += step) {
            var upper = BYTE_VECTOR.cast(ByteVector.fromArray(BP, a, aOff + i));
            var lower = BYTE_VECTOR.cast(ByteVector.fromArray(BP, b, bOff + i));
            // (a | b) - ((a ^ b) >>> 1) is the average rounding half up, without widening
            BYTE_VECTOR.cast(upper.lanewise(VectorOperators.OR, lower)
                    .lanewise(VectorOperators.SUB, BYTE_VECTOR.cast(upper.lanewise(VectorOperators.XOR, lower)).lanewise(VectorOperators.LSHR, 1)))
                    .intoArray(dst, dOff + i);
        }
        scalar.averageRowsRange(a, aOff, b, bOff, dst, dOff, end, count);
    }
}
