package com.github.auties00.smalt.image.ops;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

import java.util.Arrays;

/**
 * Implements {@link ColorKernel} with the Vector API; the output equals {@link ScalarColorKernel} sample for sample.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd}; it
 * converts one vector of pixels in 32-bit lanes per iteration with chroma upsampling fused through lane rearrangements,
 * and leaves the row ends the vector loop cannot cover, and the YCgCo and identity matrices, to
 * {@link ScalarColorKernel}.
 */
final class VectorColorKernel implements ColorKernel {
    /**
     * The integer species: 256 bits when the hardware has them, else 128 bits.
     */
    private static final VectorSpecies<Integer> I = IntVector.SPECIES_PREFERRED.vectorBitSize() >= 256
            ? IntVector.SPECIES_256 : IntVector.SPECIES_128;

    /**
     * The number of pixels per vector.
     */
    private static final int L = I.length();

    /**
     * The byte species used to load 8 samples, of which the first {@link #L} are used.
     */
    private static final VectorSpecies<Byte> B8 = ByteVector.SPECIES_64;

    /**
     * The byte species holding {@link #L} packed RGBA pixels.
     */
    private static final VectorSpecies<Byte> BP = VectorSpecies.of(byte.class, VectorShape.forBitSize(I.vectorBitSize()));

    /**
     * The exact class of the vectors of {@link #I}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(I);

    /**
     * The exact class of the vectors of {@link #B8}.
     */
    private static final Class<ByteVector> SAMPLE_VECTOR = vectorClass(B8);

    /**
     * The exact class of the vectors of {@link #BP}.
     */
    private static final Class<ByteVector> PIXEL_VECTOR = vectorClass(BP);

    /**
     * Duplicates lanes: lane {@code j} takes lane {@code j / 2}.
     */
    private static final VectorShuffle<Integer> DUPLICATE = shuffle(0);

    /**
     * Selects the center sample of fancy upsampling from a vector loaded one sample early: lane
     * {@code j} takes lane {@code j / 2 + 1}.
     */
    private static final VectorShuffle<Integer> CENTER = shuffle(1);

    /**
     * Selects the neighbor sample of fancy upsampling from a vector loaded one sample early:
     * even lanes take the left neighbor, odd lanes the right one.
     */
    private static final VectorShuffle<Integer> NEIGHBOR = shuffle(2);

    /**
     * Gathers the even lanes into both halves: lane {@code j} takes lane {@code 2j mod L}.
     */
    private static final VectorShuffle<Integer> EVEN = shuffle(3);

    /**
     * Gathers the odd lanes into both halves: lane {@code j} takes lane {@code (2j + 1) mod L}.
     */
    private static final VectorShuffle<Integer> ODD = shuffle(4);

    /**
     * Selects the high half of the lanes.
     */
    private static final VectorMask<Integer> HIGH_HALF = VectorMask.fromLong(I, ((1L << L) - 1) & ~((1L << (L / 2)) - 1));

    /**
     * The rounding biases of {@code h2v1} fancy upsampling for even and odd pixels.
     */
    private static final IntVector BIAS_H = IntVector.fromArray(I, pattern(1, 2), 0);

    /**
     * The rounding biases of {@code h2v2} fancy upsampling for even and odd pixels.
     */
    private static final IntVector BIAS_HV = IntVector.fromArray(I, pattern(8, 7), 0);

    /**
     * Zero in every lane.
     */
    private static final IntVector ZERO = IntVector.zero(I);

    /**
     * The largest sample value in every lane.
     */
    private static final IntVector MAX_SAMPLE = IntVector.broadcast(I, 255);

    /**
     * The low byte of every lane.
     */
    private static final IntVector LOW_BYTE = IntVector.broadcast(I, 0xFF);

    /**
     * The low 16 bits of every lane.
     */
    private static final IntVector LOW_HALF = IntVector.broadcast(I, 0xFFFF);

    /**
     * The red and blue bytes of every packed pixel lane.
     */
    private static final IntVector RED_BLUE = IntVector.broadcast(I, 0x00FF00FF);

    /**
     * The gray replication factor: one gray sample times it fills the red, green and blue bytes.
     */
    private static final IntVector GRAY_BYTES = IntVector.broadcast(I, 0x010101);

    /**
     * A row of opaque alpha samples, read in place of a missing alpha plane so the loops need no
     * branch.
     */
    private static final byte[] OPAQUE = opaque(16_384 + 16);

    /**
     * The scalar kernel handling the row ends.
     */
    private final ScalarColorKernel scalar;

    /**
     * Constructs the kernel.
     */
    private VectorColorKernel() {
        this.scalar = new ScalarColorKernel();
    }

    /**
     * Creates the kernel.
     *
     * @return the vector kernel
     */
    static ColorKernel create() {
        return new VectorColorKernel();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the vector species whose class to return
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    /**
     * Builds one of the constant shuffles of the integer species.
     *
     * @param kind {@code 0} duplicate, {@code 1} center, {@code 2} neighbor, {@code 3} even lanes, {@code 4} odd lanes
     * @return the shuffle
     */
    private static VectorShuffle<Integer> shuffle(int kind) {
        var indexes = new int[L];
        for (var j = 0; j < L; j++) {
            indexes[j] = switch (kind) {
                case 0 -> j / 2;
                case 1 -> j / 2 + 1;
                case 2 -> (j & 1) == 0 ? j / 2 : j / 2 + 2;
                case 3 -> (2 * j) % L;
                default -> (2 * j + 1) % L;
            };
        }
        return VectorShuffle.fromArray(I, indexes, 0);
    }

    /**
     * Builds an alternating lane pattern.
     *
     * @param even the value of even lanes
     * @param odd the value of odd lanes
     * @return the pattern, {@link #L} values long
     */
    private static int[] pattern(int even, int odd) {
        var values = new int[L];
        for (var j = 0; j < L; j++) {
            values[j] = (j & 1) == 0 ? even : odd;
        }
        return values;
    }

    /**
     * Builds a row of opaque alpha samples.
     *
     * @param length the row length
     * @return the row, filled with {@code 255}
     */
    private static byte[] opaque(int length) {
        var row = new byte[length];
        Arrays.fill(row, (byte) -1);
        return row;
    }

    /**
     * Returns the alpha row to read: the plane itself, or an opaque row long enough for
     * {@code width} pixels.
     *
     * @param alpha the alpha plane, or {@code null}
     * @param width the row width
     * @return the row
     */
    private static byte[] alphaSource(byte[] alpha, int width) {
        if (alpha != null) {
            return alpha;
        }
        return width + 8 <= OPAQUE.length ? OPAQUE : opaque(width + 8);
    }

    /**
     * Returns the end of a vector loop starting at {@code start} whose iterations may start at
     * most at {@code lastStart}.
     *
     * @param start the first iteration's pixel
     * @param lastStart the largest pixel an iteration may start at
     * @return the pixel after the last vector iteration, or {@code start} when no iteration fits
     */
    private static int vectorEnd(int start, int lastStart) {
        if (lastStart < start) {
            return start;
        }
        return start + ((lastStart - start) / L + 1) * L;
    }

    @Override
    public void yuvToRgba(byte[] y, int yOff, byte[] u, byte[] v, int cOff, int farOff, byte[] alpha, int aOff,
                          byte[] dst, int dOff, int width, int mode, ColorMatrix m) {
        if (!m.standard) {
            scalar.yuvToRgbaRange(y, yOff, u, v, cOff, farOff, alpha, aOff, dst, dOff, width, mode, m, 0, width);
            return;
        }
        var chromaWidth = (width + 1) >> 1;
        var a = alphaSource(alpha, width);
        var alphaOffset = alpha == null ? 0 : aOff;
        int start;
        int end;
        switch (mode) {
            case CHROMA_FULL -> {
                start = 0;
                end = vectorEnd(0, width - 8);
                full(y, yOff, u, v, cOff, a, alphaOffset, dst, dOff, m, end);
            }
            case CHROMA_NEAREST -> {
                start = 0;
                end = vectorEnd(0, Math.min(width - 8, 2 * (chromaWidth - 8)));
                nearest(y, yOff, u, v, cOff, a, alphaOffset, dst, dOff, m, end);
            }
            case CHROMA_FANCY_H -> {
                start = 2;
                end = vectorEnd(2, Math.min(width - 8, 2 * (chromaWidth - 7)));
                fancyH(y, yOff, u, v, cOff, a, alphaOffset, dst, dOff, m, end);
            }
            case CHROMA_FANCY_HV -> {
                start = 2;
                end = vectorEnd(2, Math.min(width - 8, 2 * (chromaWidth - 7)));
                fancyHV(y, yOff, u, v, cOff, farOff, a, alphaOffset, dst, dOff, m, end);
            }
            default -> throw new IllegalArgumentException("unknown chroma mode " + mode);
        }
        if (end <= start) {
            scalar.yuvToRgbaRange(y, yOff, u, v, cOff, farOff, alpha, aOff, dst, dOff, width, mode, m, 0, width);
            return;
        }
        scalar.yuvToRgbaRange(y, yOff, u, v, cOff, farOff, alpha, aOff, dst, dOff, width, mode, m, 0, start);
        scalar.yuvToRgbaRange(y, yOff, u, v, cOff, farOff, alpha, aOff, dst, dOff, width, mode, m, end, width);
    }

    /**
     * Converts pixels {@code [0, end)} of a row whose chroma has one sample per pixel.
     *
     * @param y the luma plane
     * @param yOff the index of the row's first luma sample
     * @param u the Cb plane
     * @param v the Cr plane
     * @param cOff the index of the row's first chroma sample
     * @param alpha the alpha samples
     * @param aOff the index of the row's first alpha sample
     * @param dst the RGBA destination
     * @param dOff the index of the row's first destination byte
     * @param m the conversion coefficients, in standard form
     * @param end the pixel after the last vector iteration
     */
    private static void full(byte[] y, int yOff, byte[] u, byte[] v, int cOff, byte[] alpha, int aOff,
                             byte[] dst, int dOff, ColorMatrix m, int end) {
        var ir0 = INT_VECTOR.cast(IntVector.broadcast(I, m.ir[0]));
        var ir2 = INT_VECTOR.cast(IntVector.broadcast(I, m.ir[2]));
        var ig1 = INT_VECTOR.cast(IntVector.broadcast(I, m.ig[1]));
        var ig2 = INT_VECTOR.cast(IntVector.broadcast(I, m.ig[2]));
        var ib1 = INT_VECTOR.cast(IntVector.broadcast(I, m.ib[1]));
        var ik0 = INT_VECTOR.cast(IntVector.broadcast(I, m.ik[0]));
        var ik1 = INT_VECTOR.cast(IntVector.broadcast(I, m.ik[1]));
        var ik2 = INT_VECTOR.cast(IntVector.broadcast(I, m.ik[2]));
        for (var x = 0; x < end; x += L) {
            var luma = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, y, yOff + x))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var cb = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, u, cOff + x))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var cr = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, v, cOff + x))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var opacity = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, alpha, aOff + x))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var scaled = luma.lanewise(VectorOperators.MUL, ir0);
            var red = INT_VECTOR.cast(scaled.lanewise(VectorOperators.ADD, cr.lanewise(VectorOperators.MUL, ir2))
                            .lanewise(VectorOperators.ADD, ik0))
                    .lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var green = INT_VECTOR.cast(scaled.lanewise(VectorOperators.ADD, cb.lanewise(VectorOperators.MUL, ig1))
                            .lanewise(VectorOperators.ADD, cr.lanewise(VectorOperators.MUL, ig2))
                            .lanewise(VectorOperators.ADD, ik1))
                    .lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var blue = INT_VECTOR.cast(scaled.lanewise(VectorOperators.ADD, cb.lanewise(VectorOperators.MUL, ib1))
                            .lanewise(VectorOperators.ADD, ik2))
                    .lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var packed = red.lanewise(VectorOperators.OR, INT_VECTOR.cast(green).lanewise(VectorOperators.LSHL, 8))
                    .lanewise(VectorOperators.OR, INT_VECTOR.cast(blue).lanewise(VectorOperators.LSHL, 16))
                    .lanewise(VectorOperators.OR, opacity.lanewise(VectorOperators.LSHL, 24));
            PIXEL_VECTOR.cast(INT_VECTOR.cast(packed).reinterpretShape(BP, 0)).intoArray(dst, dOff + 4 * x);
        }
    }

    /**
     * Converts pixels {@code [0, end)} of a row replicating each chroma sample over two pixels.
     *
     * @param y the luma plane
     * @param yOff the index of the row's first luma sample
     * @param u the Cb plane
     * @param v the Cr plane
     * @param cOff the index of the row's first chroma sample
     * @param alpha the alpha samples
     * @param aOff the index of the row's first alpha sample
     * @param dst the RGBA destination
     * @param dOff the index of the row's first destination byte
     * @param m the conversion coefficients, in standard form
     * @param end the pixel after the last vector iteration
     */
    private static void nearest(byte[] y, int yOff, byte[] u, byte[] v, int cOff, byte[] alpha, int aOff,
                                byte[] dst, int dOff, ColorMatrix m, int end) {
        var ir0 = INT_VECTOR.cast(IntVector.broadcast(I, m.ir[0]));
        var ir2 = INT_VECTOR.cast(IntVector.broadcast(I, m.ir[2]));
        var ig1 = INT_VECTOR.cast(IntVector.broadcast(I, m.ig[1]));
        var ig2 = INT_VECTOR.cast(IntVector.broadcast(I, m.ig[2]));
        var ib1 = INT_VECTOR.cast(IntVector.broadcast(I, m.ib[1]));
        var ik0 = INT_VECTOR.cast(IntVector.broadcast(I, m.ik[0]));
        var ik1 = INT_VECTOR.cast(IntVector.broadcast(I, m.ik[1]));
        var ik2 = INT_VECTOR.cast(IntVector.broadcast(I, m.ik[2]));
        for (var x = 0; x < end; x += L) {
            var k = cOff + (x >> 1);
            var luma = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, y, yOff + x))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var cb = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, u, k))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0)).rearrange(DUPLICATE);
            var cr = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, v, k))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0)).rearrange(DUPLICATE);
            var opacity = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, alpha, aOff + x))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var scaled = luma.lanewise(VectorOperators.MUL, ir0);
            var red = INT_VECTOR.cast(scaled.lanewise(VectorOperators.ADD, cr.lanewise(VectorOperators.MUL, ir2))
                            .lanewise(VectorOperators.ADD, ik0))
                    .lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var green = INT_VECTOR.cast(scaled.lanewise(VectorOperators.ADD, cb.lanewise(VectorOperators.MUL, ig1))
                            .lanewise(VectorOperators.ADD, cr.lanewise(VectorOperators.MUL, ig2))
                            .lanewise(VectorOperators.ADD, ik1))
                    .lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var blue = INT_VECTOR.cast(scaled.lanewise(VectorOperators.ADD, cb.lanewise(VectorOperators.MUL, ib1))
                            .lanewise(VectorOperators.ADD, ik2))
                    .lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var packed = red.lanewise(VectorOperators.OR, INT_VECTOR.cast(green).lanewise(VectorOperators.LSHL, 8))
                    .lanewise(VectorOperators.OR, INT_VECTOR.cast(blue).lanewise(VectorOperators.LSHL, 16))
                    .lanewise(VectorOperators.OR, opacity.lanewise(VectorOperators.LSHL, 24));
            PIXEL_VECTOR.cast(INT_VECTOR.cast(packed).reinterpretShape(BP, 0)).intoArray(dst, dOff + 4 * x);
        }
    }

    /**
     * Converts pixels {@code [2, end)} of a row with {@code h2v1} fancy chroma upsampling.
     *
     * @param y the luma plane
     * @param yOff the index of the row's first luma sample
     * @param u the Cb plane
     * @param v the Cr plane
     * @param cOff the index of the row's first chroma sample
     * @param alpha the alpha samples
     * @param aOff the index of the row's first alpha sample
     * @param dst the RGBA destination
     * @param dOff the index of the row's first destination byte
     * @param m the conversion coefficients, in standard form
     * @param end the pixel after the last vector iteration
     */
    private static void fancyH(byte[] y, int yOff, byte[] u, byte[] v, int cOff, byte[] alpha, int aOff,
                               byte[] dst, int dOff, ColorMatrix m, int end) {
        var ir0 = INT_VECTOR.cast(IntVector.broadcast(I, m.ir[0]));
        var ir2 = INT_VECTOR.cast(IntVector.broadcast(I, m.ir[2]));
        var ig1 = INT_VECTOR.cast(IntVector.broadcast(I, m.ig[1]));
        var ig2 = INT_VECTOR.cast(IntVector.broadcast(I, m.ig[2]));
        var ib1 = INT_VECTOR.cast(IntVector.broadcast(I, m.ib[1]));
        var ik0 = INT_VECTOR.cast(IntVector.broadcast(I, m.ik[0]));
        var ik1 = INT_VECTOR.cast(IntVector.broadcast(I, m.ik[1]));
        var ik2 = INT_VECTOR.cast(IntVector.broadcast(I, m.ik[2]));
        for (var x = 2; x < end; x += L) {
            var k = cOff + (x >> 1) - 1;
            var su = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, u, k))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var sv = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, v, k))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var centerU = su.rearrange(CENTER);
            var centerV = sv.rearrange(CENTER);
            var cb = INT_VECTOR.cast(centerU.lanewise(VectorOperators.ADD, INT_VECTOR.cast(centerU)
                    .lanewise(VectorOperators.LSHL, 1))
                            .lanewise(VectorOperators.ADD, su.rearrange(NEIGHBOR))
                            .lanewise(VectorOperators.ADD, BIAS_H))
                    .lanewise(VectorOperators.ASHR, 2);
            var cr = INT_VECTOR.cast(centerV.lanewise(VectorOperators.ADD, INT_VECTOR.cast(centerV)
                    .lanewise(VectorOperators.LSHL, 1))
                            .lanewise(VectorOperators.ADD, sv.rearrange(NEIGHBOR))
                            .lanewise(VectorOperators.ADD, BIAS_H))
                    .lanewise(VectorOperators.ASHR, 2);
            var luma = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, y, yOff + x))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var opacity = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, alpha, aOff + x))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var scaled = luma.lanewise(VectorOperators.MUL, ir0);
            var red = INT_VECTOR.cast(scaled.lanewise(VectorOperators.ADD, cr.lanewise(VectorOperators.MUL, ir2))
                            .lanewise(VectorOperators.ADD, ik0))
                    .lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var green = INT_VECTOR.cast(scaled.lanewise(VectorOperators.ADD, cb.lanewise(VectorOperators.MUL, ig1))
                            .lanewise(VectorOperators.ADD, cr.lanewise(VectorOperators.MUL, ig2))
                            .lanewise(VectorOperators.ADD, ik1))
                    .lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var blue = INT_VECTOR.cast(scaled.lanewise(VectorOperators.ADD, cb.lanewise(VectorOperators.MUL, ib1))
                            .lanewise(VectorOperators.ADD, ik2))
                    .lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var packed = red.lanewise(VectorOperators.OR, INT_VECTOR.cast(green).lanewise(VectorOperators.LSHL, 8))
                    .lanewise(VectorOperators.OR, INT_VECTOR.cast(blue).lanewise(VectorOperators.LSHL, 16))
                    .lanewise(VectorOperators.OR, opacity.lanewise(VectorOperators.LSHL, 24));
            PIXEL_VECTOR.cast(INT_VECTOR.cast(packed).reinterpretShape(BP, 0)).intoArray(dst, dOff + 4 * x);
        }
    }

    /**
     * Converts pixels {@code [2, end)} of a row with {@code h2v2} fancy chroma upsampling.
     *
     * @param y the luma plane
     * @param yOff the index of the row's first luma sample
     * @param u the Cb plane
     * @param v the Cr plane
     * @param cOff the index of the nearer chroma row's first sample
     * @param farOff the index of the farther chroma row's first sample
     * @param alpha the alpha samples
     * @param aOff the index of the row's first alpha sample
     * @param dst the RGBA destination
     * @param dOff the index of the row's first destination byte
     * @param m the conversion coefficients, in standard form
     * @param end the pixel after the last vector iteration
     */
    private static void fancyHV(byte[] y, int yOff, byte[] u, byte[] v, int cOff, int farOff, byte[] alpha, int aOff,
                                byte[] dst, int dOff, ColorMatrix m, int end) {
        var ir0 = INT_VECTOR.cast(IntVector.broadcast(I, m.ir[0]));
        var ir2 = INT_VECTOR.cast(IntVector.broadcast(I, m.ir[2]));
        var ig1 = INT_VECTOR.cast(IntVector.broadcast(I, m.ig[1]));
        var ig2 = INT_VECTOR.cast(IntVector.broadcast(I, m.ig[2]));
        var ib1 = INT_VECTOR.cast(IntVector.broadcast(I, m.ib[1]));
        var ik0 = INT_VECTOR.cast(IntVector.broadcast(I, m.ik[0]));
        var ik1 = INT_VECTOR.cast(IntVector.broadcast(I, m.ik[1]));
        var ik2 = INT_VECTOR.cast(IntVector.broadcast(I, m.ik[2]));
        for (var x = 2; x < end; x += L) {
            var k = (x >> 1) - 1;
            var nearU = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, u, cOff + k))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var nearV = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, v, cOff + k))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var su = nearU.lanewise(VectorOperators.ADD, nearU.lanewise(VectorOperators.LSHL, 1))
                    .lanewise(VectorOperators.ADD, INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, u, farOff + k))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0)));
            var sv = nearV.lanewise(VectorOperators.ADD, nearV.lanewise(VectorOperators.LSHL, 1))
                    .lanewise(VectorOperators.ADD, INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, v, farOff + k))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0)));
            var centerU = su.rearrange(CENTER);
            var centerV = sv.rearrange(CENTER);
            var cb = INT_VECTOR.cast(centerU.lanewise(VectorOperators.ADD, INT_VECTOR.cast(centerU)
                    .lanewise(VectorOperators.LSHL, 1))
                            .lanewise(VectorOperators.ADD, su.rearrange(NEIGHBOR))
                            .lanewise(VectorOperators.ADD, BIAS_HV))
                    .lanewise(VectorOperators.ASHR, 4);
            var cr = INT_VECTOR.cast(centerV.lanewise(VectorOperators.ADD, INT_VECTOR.cast(centerV)
                    .lanewise(VectorOperators.LSHL, 1))
                            .lanewise(VectorOperators.ADD, sv.rearrange(NEIGHBOR))
                            .lanewise(VectorOperators.ADD, BIAS_HV))
                    .lanewise(VectorOperators.ASHR, 4);
            var luma = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, y, yOff + x))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var opacity = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, alpha, aOff + x))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var scaled = luma.lanewise(VectorOperators.MUL, ir0);
            var red = INT_VECTOR.cast(scaled.lanewise(VectorOperators.ADD, cr.lanewise(VectorOperators.MUL, ir2))
                            .lanewise(VectorOperators.ADD, ik0))
                    .lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var green = INT_VECTOR.cast(scaled.lanewise(VectorOperators.ADD, cb.lanewise(VectorOperators.MUL, ig1))
                            .lanewise(VectorOperators.ADD, cr.lanewise(VectorOperators.MUL, ig2))
                            .lanewise(VectorOperators.ADD, ik1))
                    .lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var blue = INT_VECTOR.cast(scaled.lanewise(VectorOperators.ADD, cb.lanewise(VectorOperators.MUL, ib1))
                            .lanewise(VectorOperators.ADD, ik2))
                    .lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var packed = red.lanewise(VectorOperators.OR, INT_VECTOR.cast(green).lanewise(VectorOperators.LSHL, 8))
                    .lanewise(VectorOperators.OR, INT_VECTOR.cast(blue).lanewise(VectorOperators.LSHL, 16))
                    .lanewise(VectorOperators.OR, opacity.lanewise(VectorOperators.LSHL, 24));
            PIXEL_VECTOR.cast(INT_VECTOR.cast(packed).reinterpretShape(BP, 0)).intoArray(dst, dOff + 4 * x);
        }
    }

    @Override
    public void grayToRgba(byte[] y, int yOff, byte[] alpha, int aOff, byte[] dst, int dOff, int width, ColorMatrix m) {
        var end = vectorEnd(0, width - 8);
        var a = alphaSource(alpha, width);
        var alphaOffset = alpha == null ? 0 : aOff;
        var scale = INT_VECTOR.cast(IntVector.broadcast(I, m.grayScale));
        var constant = INT_VECTOR.cast(IntVector.broadcast(I, m.grayConstant));
        for (var x = 0; x < end; x += L) {
            var gray = INT_VECTOR.cast(INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, y, yOff + x))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0))
                            .lanewise(VectorOperators.MUL, scale).lanewise(VectorOperators.ADD, constant))
                    .lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var opacity = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, a, alphaOffset + x))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var packed = gray.lanewise(VectorOperators.MUL, GRAY_BYTES)
                    .lanewise(VectorOperators.OR, opacity.lanewise(VectorOperators.LSHL, 24));
            PIXEL_VECTOR.cast(INT_VECTOR.cast(packed).reinterpretShape(BP, 0)).intoArray(dst, dOff + 4 * x);
        }
        scalar.grayToRgbaRange(y, yOff, alpha, aOff, dst, dOff, m, end, width);
    }

    @Override
    public void rgbaToLuma(byte[] src, int sOff, int width, byte[] y, int yOff, byte[] alpha, int aOff, ColorMatrix m) {
        var end = vectorEnd(0, width - 8);
        if (alpha == null) {
            luma(src, sOff, y, yOff, m, end);
        } else {
            lumaAndAlpha(src, sOff, y, yOff, alpha, aOff, m, end);
        }
        scalar.rgbaToLumaRange(src, sOff, y, yOff, alpha, aOff, m, end, width);
    }

    /**
     * Computes the luma of pixels {@code [0, end)} of a row.
     *
     * @param src the RGBA source
     * @param sOff the index of the row's first source byte
     * @param y the luma destination
     * @param yOff the index of the row's first luma sample
     * @param m the conversion coefficients
     * @param end the pixel after the last vector iteration
     */
    private static void luma(byte[] src, int sOff, byte[] y, int yOff, ColorMatrix m, int end) {
        var c0 = INT_VECTOR.cast(IntVector.broadcast(I, m.fy[0]));
        var c1 = INT_VECTOR.cast(IntVector.broadcast(I, m.fy[1]));
        var c2 = INT_VECTOR.cast(IntVector.broadcast(I, m.fy[2]));
        var k = INT_VECTOR.cast(IntVector.broadcast(I, m.fk[0]));
        for (var x = 0; x < end; x += L) {
            var pixels = INT_VECTOR.cast(PIXEL_VECTOR.cast(ByteVector.fromArray(BP, src, sOff + 4 * x))
                    .reinterpretShape(I, 0));
            var r = pixels.lanewise(VectorOperators.AND, LOW_BYTE);
            var g = pixels.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE);
            var b = pixels.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.AND, LOW_BYTE);
            var sum = r.lanewise(VectorOperators.MUL, c0).lanewise(VectorOperators.ADD, g
                    .lanewise(VectorOperators.MUL, c1))
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, c2))
                    .lanewise(VectorOperators.ADD, k);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(INT_VECTOR.cast(sum).lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE))
                    .convertShape(VectorOperators.I2B, B8, 0)).intoArray(y, yOff + x);
        }
    }

    /**
     * Computes the luma, and copies the alpha, of pixels {@code [0, end)} of a row.
     *
     * @param src the RGBA source
     * @param sOff the index of the row's first source byte
     * @param y the luma destination
     * @param yOff the index of the row's first luma sample
     * @param alpha the alpha destination
     * @param aOff the index of the row's first alpha sample
     * @param m the conversion coefficients
     * @param end the pixel after the last vector iteration
     */
    private static void lumaAndAlpha(byte[] src, int sOff, byte[] y, int yOff, byte[] alpha, int aOff, ColorMatrix m, int end) {
        var c0 = INT_VECTOR.cast(IntVector.broadcast(I, m.fy[0]));
        var c1 = INT_VECTOR.cast(IntVector.broadcast(I, m.fy[1]));
        var c2 = INT_VECTOR.cast(IntVector.broadcast(I, m.fy[2]));
        var k = INT_VECTOR.cast(IntVector.broadcast(I, m.fk[0]));
        for (var x = 0; x < end; x += L) {
            var pixels = INT_VECTOR.cast(PIXEL_VECTOR.cast(ByteVector.fromArray(BP, src, sOff + 4 * x))
                    .reinterpretShape(I, 0));
            var r = pixels.lanewise(VectorOperators.AND, LOW_BYTE);
            var g = pixels.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE);
            var b = pixels.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.AND, LOW_BYTE);
            var sum = r.lanewise(VectorOperators.MUL, c0).lanewise(VectorOperators.ADD, g
                    .lanewise(VectorOperators.MUL, c1))
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, c2))
                    .lanewise(VectorOperators.ADD, k);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(INT_VECTOR.cast(sum).lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE))
                    .convertShape(VectorOperators.I2B, B8, 0)).intoArray(y, yOff + x);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(pixels.lanewise(VectorOperators.LSHR, 24))
                    .convertShape(VectorOperators.I2B, B8, 0)).intoArray(alpha, aOff + x);
        }
    }

    @Override
    public void rgbaToChroma(byte[] src, int off0, int off1, int width, boolean pairX, boolean pairY,
                             byte[] u, byte[] v, int cOff, ColorMatrix m) {
        var count = pairX ? (width + 1) >> 1 : width;
        int end;
        if (pairX) {
            end = vectorEnd(0, Math.min(count - 8, Math.floorDiv(width - 2 * L, 2)));
            if (pairY) {
                chroma2x2(src, off0, off1, u, v, cOff, m, end);
            } else {
                chroma2x1(src, off0, u, v, cOff, m, end);
            }
        } else if (!pairY) {
            end = vectorEnd(0, width - 8);
            chroma1x1(src, off0, u, v, cOff, m, end);
        } else {
            end = 0;
        }
        scalar.rgbaToChromaRange(src, off0, off1, width, pairX, pairY, u, v, cOff, m, end, count);
    }

    /**
     * Computes chroma samples {@code [0, end)} of a row, one per pixel.
     *
     * @param src the RGBA source
     * @param off0 the index of the row's first source byte
     * @param u the Cb destination
     * @param v the Cr destination
     * @param cOff the index of the row's first chroma sample
     * @param m the conversion coefficients
     * @param end the sample after the last vector iteration
     */
    private static void chroma1x1(byte[] src, int off0, byte[] u, byte[] v, int cOff, ColorMatrix m, int end) {
        var u0 = INT_VECTOR.cast(IntVector.broadcast(I, m.fu[0]));
        var u1 = INT_VECTOR.cast(IntVector.broadcast(I, m.fu[1]));
        var u2 = INT_VECTOR.cast(IntVector.broadcast(I, m.fu[2]));
        var v0 = INT_VECTOR.cast(IntVector.broadcast(I, m.fv[0]));
        var v1 = INT_VECTOR.cast(IntVector.broadcast(I, m.fv[1]));
        var v2 = INT_VECTOR.cast(IntVector.broadcast(I, m.fv[2]));
        var ku = INT_VECTOR.cast(IntVector.broadcast(I, m.fk[1]));
        var kv = INT_VECTOR.cast(IntVector.broadcast(I, m.fk[2]));
        for (var k = 0; k < end; k += L) {
            var pixels = INT_VECTOR.cast(PIXEL_VECTOR.cast(ByteVector.fromArray(BP, src, off0 + 4 * k))
                    .reinterpretShape(I, 0));
            var r = pixels.lanewise(VectorOperators.AND, LOW_BYTE);
            var g = pixels.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE);
            var b = pixels.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.AND, LOW_BYTE);
            var blue = r.lanewise(VectorOperators.MUL, u0).lanewise(VectorOperators.ADD, g
                    .lanewise(VectorOperators.MUL, u1))
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, u2))
                    .lanewise(VectorOperators.ADD, ku);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(INT_VECTOR.cast(blue).lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE))
                    .convertShape(VectorOperators.I2B, B8, 0)).intoArray(u, cOff + k);
            var red = r.lanewise(VectorOperators.MUL, v0).lanewise(VectorOperators.ADD, g
                    .lanewise(VectorOperators.MUL, v1))
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, v2))
                    .lanewise(VectorOperators.ADD, kv);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(INT_VECTOR.cast(red).lanewise(VectorOperators.ASHR, ColorMatrix.BITS)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE))
                    .convertShape(VectorOperators.I2B, B8, 0)).intoArray(v, cOff + k);
        }
    }

    /**
     * Computes chroma samples {@code [0, end)} of a row, each averaging two horizontal pixels.
     *
     * @param src the RGBA source
     * @param off0 the index of the row's first source byte
     * @param u the Cb destination
     * @param v the Cr destination
     * @param cOff the index of the row's first chroma sample
     * @param m the conversion coefficients
     * @param end the sample after the last vector iteration
     */
    private static void chroma2x1(byte[] src, int off0, byte[] u, byte[] v, int cOff, ColorMatrix m, int end) {
        var shift = ColorMatrix.BITS + 1;
        var u0 = INT_VECTOR.cast(IntVector.broadcast(I, m.fu[0]));
        var u1 = INT_VECTOR.cast(IntVector.broadcast(I, m.fu[1]));
        var u2 = INT_VECTOR.cast(IntVector.broadcast(I, m.fu[2]));
        var v0 = INT_VECTOR.cast(IntVector.broadcast(I, m.fv[0]));
        var v1 = INT_VECTOR.cast(IntVector.broadcast(I, m.fv[1]));
        var v2 = INT_VECTOR.cast(IntVector.broadcast(I, m.fv[2]));
        var ku = INT_VECTOR.cast(IntVector.broadcast(I, (m.offsets[1] << shift) + (1 << (shift - 1)) - 1));
        var kv = INT_VECTOR.cast(IntVector.broadcast(I, (m.offsets[2] << shift) + (1 << (shift - 1)) - 1));
        for (var k = 0; k < end; k += L) {
            var a = INT_VECTOR.cast(PIXEL_VECTOR.cast(ByteVector.fromArray(BP, src, off0 + 8 * k)).reinterpretShape(I, 0));
            var c = INT_VECTOR.cast(PIXEL_VECTOR.cast(ByteVector.fromArray(BP, src, off0 + 8 * k + 4 * L))
                    .reinterpretShape(I, 0));
            var redBlueA = a.lanewise(VectorOperators.AND, RED_BLUE);
            var redBlueC = c.lanewise(VectorOperators.AND, RED_BLUE);
            var greenA = a.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE);
            var greenC = c.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE);
            var redBlue = redBlueA.rearrange(EVEN).blend(redBlueC.rearrange(EVEN), HIGH_HALF)
                    .lanewise(VectorOperators.ADD, redBlueA.rearrange(ODD).blend(redBlueC.rearrange(ODD), HIGH_HALF));
            var g = greenA.rearrange(EVEN).blend(greenC.rearrange(EVEN), HIGH_HALF)
                    .lanewise(VectorOperators.ADD, greenA.rearrange(ODD).blend(greenC.rearrange(ODD), HIGH_HALF));
            var r = redBlue.lanewise(VectorOperators.AND, LOW_HALF);
            var b = INT_VECTOR.cast(redBlue).lanewise(VectorOperators.LSHR, 16);
            var blue = r.lanewise(VectorOperators.MUL, u0).lanewise(VectorOperators.ADD, g
                    .lanewise(VectorOperators.MUL, u1))
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, u2))
                    .lanewise(VectorOperators.ADD, ku);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(INT_VECTOR.cast(blue).lanewise(VectorOperators.ASHR, shift)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE))
                    .convertShape(VectorOperators.I2B, B8, 0)).intoArray(u, cOff + k);
            var red = r.lanewise(VectorOperators.MUL, v0).lanewise(VectorOperators.ADD, g
                    .lanewise(VectorOperators.MUL, v1))
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, v2))
                    .lanewise(VectorOperators.ADD, kv);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(INT_VECTOR.cast(red).lanewise(VectorOperators.ASHR, shift)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE))
                    .convertShape(VectorOperators.I2B, B8, 0)).intoArray(v, cOff + k);
        }
    }

    /**
     * Computes chroma samples {@code [0, end)} of a row pair, each averaging a 2x2 block.
     *
     * @param src the RGBA source
     * @param off0 the index of the upper row's first source byte
     * @param off1 the index of the lower row's first source byte
     * @param u the Cb destination
     * @param v the Cr destination
     * @param cOff the index of the row's first chroma sample
     * @param m the conversion coefficients
     * @param end the sample after the last vector iteration
     */
    private static void chroma2x2(byte[] src, int off0, int off1, byte[] u, byte[] v, int cOff, ColorMatrix m, int end) {
        var shift = ColorMatrix.BITS + 2;
        var u0 = INT_VECTOR.cast(IntVector.broadcast(I, m.fu[0]));
        var u1 = INT_VECTOR.cast(IntVector.broadcast(I, m.fu[1]));
        var u2 = INT_VECTOR.cast(IntVector.broadcast(I, m.fu[2]));
        var v0 = INT_VECTOR.cast(IntVector.broadcast(I, m.fv[0]));
        var v1 = INT_VECTOR.cast(IntVector.broadcast(I, m.fv[1]));
        var v2 = INT_VECTOR.cast(IntVector.broadcast(I, m.fv[2]));
        var ku = INT_VECTOR.cast(IntVector.broadcast(I, (m.offsets[1] << shift) + (1 << (shift - 1)) - 1));
        var kv = INT_VECTOR.cast(IntVector.broadcast(I, (m.offsets[2] << shift) + (1 << (shift - 1)) - 1));
        for (var k = 0; k < end; k += L) {
            var a0 = INT_VECTOR.cast(PIXEL_VECTOR.cast(ByteVector.fromArray(BP, src, off0 + 8 * k))
                    .reinterpretShape(I, 0));
            var c0 = INT_VECTOR.cast(PIXEL_VECTOR.cast(ByteVector.fromArray(BP, src, off0 + 8 * k + 4 * L))
                    .reinterpretShape(I, 0));
            var a1 = INT_VECTOR.cast(PIXEL_VECTOR.cast(ByteVector.fromArray(BP, src, off1 + 8 * k))
                    .reinterpretShape(I, 0));
            var c1 = INT_VECTOR.cast(PIXEL_VECTOR.cast(ByteVector.fromArray(BP, src, off1 + 8 * k + 4 * L))
                    .reinterpretShape(I, 0));
            var redBlueA = a0.lanewise(VectorOperators.AND, RED_BLUE).lanewise(VectorOperators.ADD, a1
                    .lanewise(VectorOperators.AND, RED_BLUE));
            var redBlueC = c0.lanewise(VectorOperators.AND, RED_BLUE).lanewise(VectorOperators.ADD, c1
                    .lanewise(VectorOperators.AND, RED_BLUE));
            var greenA = a0.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE)
                    .lanewise(VectorOperators.ADD, a1.lanewise(VectorOperators.LSHR, 8)
                    .lanewise(VectorOperators.AND, LOW_BYTE));
            var greenC = c0.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE)
                    .lanewise(VectorOperators.ADD, c1.lanewise(VectorOperators.LSHR, 8)
                    .lanewise(VectorOperators.AND, LOW_BYTE));
            var redBlue = redBlueA.rearrange(EVEN).blend(redBlueC.rearrange(EVEN), HIGH_HALF)
                    .lanewise(VectorOperators.ADD, redBlueA.rearrange(ODD).blend(redBlueC.rearrange(ODD), HIGH_HALF));
            var g = greenA.rearrange(EVEN).blend(greenC.rearrange(EVEN), HIGH_HALF)
                    .lanewise(VectorOperators.ADD, greenA.rearrange(ODD).blend(greenC.rearrange(ODD), HIGH_HALF));
            var r = redBlue.lanewise(VectorOperators.AND, LOW_HALF);
            var b = INT_VECTOR.cast(redBlue).lanewise(VectorOperators.LSHR, 16);
            var blue = r.lanewise(VectorOperators.MUL, u0).lanewise(VectorOperators.ADD, g
                    .lanewise(VectorOperators.MUL, u1))
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, u2))
                    .lanewise(VectorOperators.ADD, ku);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(INT_VECTOR.cast(blue).lanewise(VectorOperators.ASHR, shift)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE))
                    .convertShape(VectorOperators.I2B, B8, 0)).intoArray(u, cOff + k);
            var red = r.lanewise(VectorOperators.MUL, v0).lanewise(VectorOperators.ADD, g
                    .lanewise(VectorOperators.MUL, v1))
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, v2))
                    .lanewise(VectorOperators.ADD, kv);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(INT_VECTOR.cast(red).lanewise(VectorOperators.ASHR, shift)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE))
                    .convertShape(VectorOperators.I2B, B8, 0)).intoArray(v, cOff + k);
        }
    }
}
