package com.github.auties00.smalt.image.ops;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link EffectKernel} with the Vector API; the output equals {@link ScalarEffectKernel} sample for sample.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd}; it
 * widens eight 8-bit samples into 32-bit lanes for the integer methods, and blends in two phases: a small loop per
 * blend mode computes the blended colors, then one loop composites the channels and alpha.
 */
final class VectorEffectKernel implements EffectKernel {
    /**
     * The integer species: 256 bits when the hardware has them, else 128 bits.
     */
    private static final VectorSpecies<Integer> I = IntVector.SPECIES_PREFERRED.vectorBitSize() >= 256
            ? IntVector.SPECIES_256 : IntVector.SPECIES_128;

    /**
     * The float species with the lane count of {@link #I}.
     */
    private static final VectorSpecies<Float> F = I.vectorBitSize() >= 256 ? FloatVector.SPECIES_256 : FloatVector.SPECIES_128;

    /**
     * The number of lanes of {@link #I} and {@link #F}.
     */
    private static final int L = I.length();

    /**
     * The byte species holding {@link #L} packed RGBA pixels.
     */
    private static final VectorSpecies<Byte> BP = VectorSpecies.of(byte.class, VectorShape.forBitSize(I.vectorBitSize()));

    /**
     * The byte species used to load 8 samples, of which the first {@link #L} are used.
     */
    private static final VectorSpecies<Byte> B8 = ByteVector.SPECIES_64;

    /**
     * The exact class of the vectors of {@link #I}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(I);

    /**
     * The exact class of the vectors of {@link #F}.
     */
    private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(F);

    /**
     * The exact class of the vectors of {@link #BP}.
     */
    private static final Class<ByteVector> PIXEL_VECTOR = vectorClass(BP);

    /**
     * The exact class of the vectors of {@link #B8}.
     */
    private static final Class<ByteVector> SAMPLE_VECTOR = vectorClass(B8);

    /**
     * The low byte of every lane.
     */
    private static final IntVector LOW_BYTE = IntVector.broadcast(I, 0xFF);

    /**
     * The alpha byte of every packed pixel.
     */
    private static final IntVector ALPHA_BYTE = IntVector.broadcast(I, 0xFF000000);

    /**
     * Zero in every integer lane.
     */
    private static final IntVector INT_ZERO = IntVector.broadcast(I, 0);

    /**
     * The largest sample value in every lane.
     */
    private static final IntVector MAX_SAMPLE = IntVector.broadcast(I, 255);

    /**
     * The rounding bias of the box filter's 24 fractional bits.
     */
    private static final IntVector BOX_BIAS = IntVector.broadcast(I, 1 << 23);

    /**
     * Zero in every float lane.
     */
    private static final FloatVector ZERO = FloatVector.broadcast(F, 0);

    /**
     * One half in every float lane.
     */
    private static final FloatVector HALF = FloatVector.broadcast(F, 0.5f);

    /**
     * One quarter in every float lane.
     */
    private static final FloatVector QUARTER = FloatVector.broadcast(F, 0.25f);

    /**
     * One in every float lane.
     */
    private static final FloatVector ONE = FloatVector.broadcast(F, 1);

    /**
     * Two in every float lane.
     */
    private static final FloatVector TWO = FloatVector.broadcast(F, 2);

    /**
     * Four in every float lane.
     */
    private static final FloatVector FOUR = FloatVector.broadcast(F, 4);

    /**
     * Twelve in every float lane.
     */
    private static final FloatVector TWELVE = FloatVector.broadcast(F, 12);

    /**
     * Sixteen in every float lane.
     */
    private static final FloatVector SIXTEEN = FloatVector.broadcast(F, 16);

    /**
     * One hundred in every float lane, the divisor of the unsharp strength.
     */
    private static final FloatVector HUNDRED = FloatVector.broadcast(F, 100);

    /**
     * The scalar kernel handling the row ends.
     */
    private final ScalarEffectKernel scalar;

    /**
     * Constructs the kernel.
     */
    private VectorEffectKernel() {
        this.scalar = new ScalarEffectKernel();
    }

    /**
     * Creates the kernel.
     *
     * @return the vector kernel
     */
    static EffectKernel create() {
        return new VectorEffectKernel();
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

    @Override
    public void boxAccumulate(byte[] src, int offset, int weight, int[] acc, int length) {
        var w = INT_VECTOR.cast(IntVector.broadcast(I, weight));
        var i = 0;
        for (; i + 8 <= length; i += L) {
            var s = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, offset + i))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var a = INT_VECTOR.cast(IntVector.fromArray(I, acc, i));
            INT_VECTOR.cast(a.lanewise(VectorOperators.ADD, s.lanewise(VectorOperators.MUL, w))).intoArray(acc, i);
        }
        scalar.boxAccumulateRange(src, offset, weight, acc, i, length);
    }

    @Override
    public void boxRow(byte[] src, int add, int subtract, int left, int right, int[] acc, int ww, int fw, byte[] dst,
                       int dstOffset, int length) {
        var whole = INT_VECTOR.cast(IntVector.broadcast(I, ww));
        var fraction = INT_VECTOR.cast(IntVector.broadcast(I, fw));
        var i = 0;
        for (; i + 8 <= length; i += L) {
            var entering = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, add + i))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var leaving = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, subtract + i))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var first = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, left + i))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var last = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, src, right + i))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var sum = INT_VECTOR.cast(IntVector.fromArray(I, acc, i))
                    .lanewise(VectorOperators.ADD, entering)
                    .lanewise(VectorOperators.SUB, leaving);
            INT_VECTOR.cast(sum).intoArray(acc, i);
            var bulk = sum.lanewise(VectorOperators.MUL, whole)
                    .lanewise(VectorOperators.ADD, first.lanewise(VectorOperators.ADD, last).lanewise(VectorOperators.MUL, fraction))
                    .lanewise(VectorOperators.ADD, BOX_BIAS);
            var result = INT_VECTOR.cast(bulk).lanewise(VectorOperators.LSHR, 24);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(result).convertShape(VectorOperators.I2B, B8, 0)).intoArray(dst, dstOffset + i);
        }
        scalar.boxRowRange(src, add, subtract, left, right, acc, ww, fw, dst, dstOffset, i, length);
    }

    @Override
    public void mixRow(byte[] src, int srcOffset, int width, int[] matrix, byte[] dst, int dstOffset) {
        var m0 = INT_VECTOR.cast(IntVector.broadcast(I, matrix[0]));
        var m1 = INT_VECTOR.cast(IntVector.broadcast(I, matrix[1]));
        var m2 = INT_VECTOR.cast(IntVector.broadcast(I, matrix[2]));
        var m3 = INT_VECTOR.cast(IntVector.broadcast(I, matrix[3]));
        var m4 = INT_VECTOR.cast(IntVector.broadcast(I, matrix[4]));
        var m5 = INT_VECTOR.cast(IntVector.broadcast(I, matrix[5]));
        var m6 = INT_VECTOR.cast(IntVector.broadcast(I, matrix[6]));
        var m7 = INT_VECTOR.cast(IntVector.broadcast(I, matrix[7]));
        var m8 = INT_VECTOR.cast(IntVector.broadcast(I, matrix[8]));
        var m9 = INT_VECTOR.cast(IntVector.broadcast(I, matrix[9]));
        var m10 = INT_VECTOR.cast(IntVector.broadcast(I, matrix[10]));
        var m11 = INT_VECTOR.cast(IntVector.broadcast(I, matrix[11]));
        var x = 0;
        for (; x + L <= width; x += L) {
            var pixels = INT_VECTOR.cast(PIXEL_VECTOR.cast(ByteVector.fromArray(BP, src, srcOffset + 4 * x))
                    .reinterpretShape(I, 0));
            var r = pixels.lanewise(VectorOperators.AND, LOW_BYTE);
            var g = INT_VECTOR.cast(pixels).lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE);
            var b = INT_VECTOR.cast(pixels).lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.AND, LOW_BYTE);
            var red = r.lanewise(VectorOperators.MUL, m0).lanewise(VectorOperators.ADD, g.lanewise(VectorOperators.MUL, m1))
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, m2)).lanewise(VectorOperators.ADD, m3);
            var green = r.lanewise(VectorOperators.MUL, m4).lanewise(VectorOperators.ADD, g.lanewise(VectorOperators.MUL, m5))
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, m6)).lanewise(VectorOperators.ADD, m7);
            var blue = r.lanewise(VectorOperators.MUL, m8).lanewise(VectorOperators.ADD, g.lanewise(VectorOperators.MUL, m9))
                    .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, m10)).lanewise(VectorOperators.ADD, m11);
            var outR = INT_VECTOR.cast(red).lanewise(VectorOperators.ASHR, 16)
                    .lanewise(VectorOperators.MAX, INT_ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var outG = INT_VECTOR.cast(green).lanewise(VectorOperators.ASHR, 16)
                    .lanewise(VectorOperators.MAX, INT_ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var outB = INT_VECTOR.cast(blue).lanewise(VectorOperators.ASHR, 16)
                    .lanewise(VectorOperators.MAX, INT_ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var packed = outR.lanewise(VectorOperators.OR, INT_VECTOR.cast(outG).lanewise(VectorOperators.LSHL, 8))
                    .lanewise(VectorOperators.OR, INT_VECTOR.cast(outB).lanewise(VectorOperators.LSHL, 16))
                    .lanewise(VectorOperators.OR, pixels.lanewise(VectorOperators.AND, ALPHA_BYTE));
            PIXEL_VECTOR.cast(INT_VECTOR.cast(packed).reinterpretShape(BP, 0)).intoArray(dst, dstOffset + 4 * x);
        }
        scalar.mixRowRange(src, srcOffset, matrix, dst, dstOffset, x, width);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation divides by 100 in single precision and truncates, which equals the integer
     * quotient: the product has at most 22 significant bits and a non-integral quotient lies at least 0.01 from an
     * integer, more than the rounding error of the division.
     */
    @Override
    public void unsharpRow(byte[] original, int originalOffset, byte[] blurred, int blurredOffset, int length,
                           int percent, int threshold, byte[] dst, int dstOffset) {
        var strength = INT_VECTOR.cast(IntVector.broadcast(I, percent));
        var limit = INT_VECTOR.cast(IntVector.broadcast(I, threshold));
        var i = 0;
        for (; i + 8 <= length; i += L) {
            var value = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, original, originalOffset + i))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var soft = INT_VECTOR.cast(SAMPLE_VECTOR.cast(ByteVector.fromArray(B8, blurred, blurredOffset + i))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0));
            var diff = value.lanewise(VectorOperators.SUB, soft);
            var sharpen = diff.lanewise(VectorOperators.ABS).compare(VectorOperators.GT, limit);
            var scaled = FLOAT_VECTOR.cast(INT_VECTOR.cast(diff.lanewise(VectorOperators.MUL, strength))
                    .convertShape(VectorOperators.I2F, F, 0)).lanewise(VectorOperators.DIV, HUNDRED);
            var correction = INT_VECTOR.cast(FLOAT_VECTOR.cast(scaled).convertShape(VectorOperators.F2I, I, 0));
            var sharpened = value.lanewise(VectorOperators.ADD, correction)
                    .lanewise(VectorOperators.MAX, INT_ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            var result = value.blend(sharpened, sharpen);
            SAMPLE_VECTOR.cast(INT_VECTOR.cast(result).convertShape(VectorOperators.I2B, B8, 0))
                    .intoArray(dst, dstOffset + i);
        }
        scalar.unsharpRange(original, originalOffset, blurred, blurredOffset, percent, threshold, dst, dstOffset, i, length);
    }

    @Override
    public void median9Row(byte[] above, byte[] row, byte[] below, int offset, int length, byte[] dst, int dstOffset) {
        var i = 0;
        for (; i + BP.length() <= length; i += BP.length()) {
            var s = offset + i;
            var p0 = PIXEL_VECTOR.cast(ByteVector.fromArray(BP, above, s - 4));
            var p1 = PIXEL_VECTOR.cast(ByteVector.fromArray(BP, above, s));
            var p2 = PIXEL_VECTOR.cast(ByteVector.fromArray(BP, above, s + 4));
            var p3 = PIXEL_VECTOR.cast(ByteVector.fromArray(BP, row, s - 4));
            var p4 = PIXEL_VECTOR.cast(ByteVector.fromArray(BP, row, s));
            var p5 = PIXEL_VECTOR.cast(ByteVector.fromArray(BP, row, s + 4));
            var p6 = PIXEL_VECTOR.cast(ByteVector.fromArray(BP, below, s - 4));
            var p7 = PIXEL_VECTOR.cast(ByteVector.fromArray(BP, below, s));
            var p8 = PIXEL_VECTOR.cast(ByteVector.fromArray(BP, below, s + 4));
            var t = p1.lanewise(VectorOperators.UMIN, p2);
            p2 = p1.lanewise(VectorOperators.UMAX, p2);
            p1 = t;
            t = p4.lanewise(VectorOperators.UMIN, p5);
            p5 = p4.lanewise(VectorOperators.UMAX, p5);
            p4 = t;
            t = p7.lanewise(VectorOperators.UMIN, p8);
            p8 = p7.lanewise(VectorOperators.UMAX, p8);
            p7 = t;
            t = p0.lanewise(VectorOperators.UMIN, p1);
            p1 = p0.lanewise(VectorOperators.UMAX, p1);
            p0 = t;
            t = p3.lanewise(VectorOperators.UMIN, p4);
            p4 = p3.lanewise(VectorOperators.UMAX, p4);
            p3 = t;
            t = p6.lanewise(VectorOperators.UMIN, p7);
            p7 = p6.lanewise(VectorOperators.UMAX, p7);
            p6 = t;
            t = p1.lanewise(VectorOperators.UMIN, p2);
            p2 = p1.lanewise(VectorOperators.UMAX, p2);
            p1 = t;
            t = p4.lanewise(VectorOperators.UMIN, p5);
            p5 = p4.lanewise(VectorOperators.UMAX, p5);
            p4 = t;
            t = p7.lanewise(VectorOperators.UMIN, p8);
            p8 = p7.lanewise(VectorOperators.UMAX, p8);
            p7 = t;
            p3 = p0.lanewise(VectorOperators.UMAX, p3);
            p5 = p5.lanewise(VectorOperators.UMIN, p8);
            t = p4.lanewise(VectorOperators.UMIN, p7);
            p7 = p4.lanewise(VectorOperators.UMAX, p7);
            p4 = t;
            p6 = p3.lanewise(VectorOperators.UMAX, p6);
            p4 = p1.lanewise(VectorOperators.UMAX, p4);
            p2 = p2.lanewise(VectorOperators.UMIN, p5);
            p4 = p4.lanewise(VectorOperators.UMIN, p7);
            t = p4.lanewise(VectorOperators.UMIN, p2);
            p2 = p4.lanewise(VectorOperators.UMAX, p2);
            p4 = p6.lanewise(VectorOperators.UMAX, t);
            PIXEL_VECTOR.cast(p4.lanewise(VectorOperators.UMIN, p2)).intoArray(dst, dstOffset + i);
        }
        scalar.median9Range(above, row, below, offset, dst, dstOffset, i, length);
    }

    @Override
    public void blend(BlendMode mode, float[] br, float[] bg, float[] bb, float[] ba, float[] sr, float[] sg,
                      float[] sb, float[] sa, float opacity, int length) {
        if (mode != BlendMode.NORMAL) {
            mix(mode, br, sr, ba, length);
            mix(mode, bg, sg, ba, length);
            mix(mode, bb, sb, ba, length);
        }
        composite(br, bg, bb, ba, sr, sg, sb, sa, opacity, length);
    }

    /**
     * Replaces each source color of one channel with its mix with the blended color,
     * {@code (1 - ab) * cs + ab * B(cb, cs)}.
     *
     * @param mode the blend mode; not {@link BlendMode#NORMAL}
     * @param cb the backdrop colors
     * @param cs the source colors, replaced by the mix
     * @param ab the backdrop alphas
     * @param length the number of pixels
     */
    private void mix(BlendMode mode, float[] cb, float[] cs, float[] ab, int length) {
        var i = switch (mode) {
            case MULTIPLY -> multiply(cb, cs, ab, length);
            case SCREEN -> screen(cb, cs, ab, length);
            case OVERLAY -> overlay(cb, cs, ab, length);
            case DARKEN -> darken(cb, cs, ab, length);
            case LIGHTEN -> lighten(cb, cs, ab, length);
            case COLOR_DODGE -> dodge(cb, cs, ab, length);
            case COLOR_BURN -> burn(cb, cs, ab, length);
            case HARD_LIGHT -> hardLight(cb, cs, ab, length);
            case SOFT_LIGHT -> softLight(cb, cs, ab, length);
            case DIFFERENCE -> difference(cb, cs, ab, length);
            case EXCLUSION -> exclusion(cb, cs, ab, length);
            case ADD -> add(cb, cs, ab, length);
            case SUBTRACT -> subtract(cb, cs, ab, length);
            case NORMAL -> 0;
        };
        for (; i < length; i++) {
            cs[i] = (1 - ab[i]) * cs[i] + ab[i] * ScalarEffectKernel.blend(mode, cb[i], cs[i]);
        }
    }

    /**
     * Mixes with {@link BlendMode#MULTIPLY}.
     *
     * @param cb the backdrop colors
     * @param cs the source colors, replaced by the mix
     * @param ab the backdrop alphas
     * @param length the number of pixels
     * @return the first pixel left for the scalar tail
     */
    private int multiply(float[] cb, float[] cs, float[] ab, int length) {
        var i = 0;
        for (; i + L <= length; i += L) {
            var b = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cb, i));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cs, i));
            var a = FLOAT_VECTOR.cast(FloatVector.fromArray(F, ab, i));
            var blended = b.lanewise(VectorOperators.MUL, s);
            var mixed = ONE.lanewise(VectorOperators.SUB, a).lanewise(VectorOperators.MUL, s)
                    .lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, blended));
            FLOAT_VECTOR.cast(mixed).intoArray(cs, i);
        }
        return i;
    }

    /**
     * Mixes with {@link BlendMode#SCREEN}.
     *
     * @param cb the backdrop colors
     * @param cs the source colors, replaced by the mix
     * @param ab the backdrop alphas
     * @param length the number of pixels
     * @return the first pixel left for the scalar tail
     */
    private int screen(float[] cb, float[] cs, float[] ab, int length) {
        var i = 0;
        for (; i + L <= length; i += L) {
            var b = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cb, i));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cs, i));
            var a = FLOAT_VECTOR.cast(FloatVector.fromArray(F, ab, i));
            var blended = b.lanewise(VectorOperators.ADD, s).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.MUL, s));
            var mixed = ONE.lanewise(VectorOperators.SUB, a).lanewise(VectorOperators.MUL, s)
                    .lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, blended));
            FLOAT_VECTOR.cast(mixed).intoArray(cs, i);
        }
        return i;
    }

    /**
     * Mixes with {@link BlendMode#OVERLAY}.
     *
     * @param cb the backdrop colors
     * @param cs the source colors, replaced by the mix
     * @param ab the backdrop alphas
     * @param length the number of pixels
     * @return the first pixel left for the scalar tail
     */
    private int overlay(float[] cb, float[] cs, float[] ab, int length) {
        var i = 0;
        for (; i + L <= length; i += L) {
            var b = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cb, i));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cs, i));
            var a = FLOAT_VECTOR.cast(FloatVector.fromArray(F, ab, i));
            var low = s.lanewise(VectorOperators.MUL, TWO.lanewise(VectorOperators.MUL, b));
            var t = TWO.lanewise(VectorOperators.MUL, b).lanewise(VectorOperators.SUB, ONE);
            var high = s.lanewise(VectorOperators.ADD, t).lanewise(VectorOperators.SUB, s.lanewise(VectorOperators.MUL, t));
            var blended = high.blend(low, b.compare(VectorOperators.LE, HALF));
            var mixed = ONE.lanewise(VectorOperators.SUB, a).lanewise(VectorOperators.MUL, s)
                    .lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, blended));
            FLOAT_VECTOR.cast(mixed).intoArray(cs, i);
        }
        return i;
    }

    /**
     * Mixes with {@link BlendMode#DARKEN}.
     *
     * @param cb the backdrop colors
     * @param cs the source colors, replaced by the mix
     * @param ab the backdrop alphas
     * @param length the number of pixels
     * @return the first pixel left for the scalar tail
     */
    private int darken(float[] cb, float[] cs, float[] ab, int length) {
        var i = 0;
        for (; i + L <= length; i += L) {
            var b = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cb, i));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cs, i));
            var a = FLOAT_VECTOR.cast(FloatVector.fromArray(F, ab, i));
            var blended = b.lanewise(VectorOperators.MIN, s);
            var mixed = ONE.lanewise(VectorOperators.SUB, a).lanewise(VectorOperators.MUL, s)
                    .lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, blended));
            FLOAT_VECTOR.cast(mixed).intoArray(cs, i);
        }
        return i;
    }

    /**
     * Mixes with {@link BlendMode#LIGHTEN}.
     *
     * @param cb the backdrop colors
     * @param cs the source colors, replaced by the mix
     * @param ab the backdrop alphas
     * @param length the number of pixels
     * @return the first pixel left for the scalar tail
     */
    private int lighten(float[] cb, float[] cs, float[] ab, int length) {
        var i = 0;
        for (; i + L <= length; i += L) {
            var b = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cb, i));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cs, i));
            var a = FLOAT_VECTOR.cast(FloatVector.fromArray(F, ab, i));
            var blended = b.lanewise(VectorOperators.MAX, s);
            var mixed = ONE.lanewise(VectorOperators.SUB, a).lanewise(VectorOperators.MUL, s)
                    .lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, blended));
            FLOAT_VECTOR.cast(mixed).intoArray(cs, i);
        }
        return i;
    }

    /**
     * Mixes with {@link BlendMode#COLOR_DODGE}.
     *
     * @param cb the backdrop colors
     * @param cs the source colors, replaced by the mix
     * @param ab the backdrop alphas
     * @param length the number of pixels
     * @return the first pixel left for the scalar tail
     */
    private int dodge(float[] cb, float[] cs, float[] ab, int length) {
        var i = 0;
        for (; i + L <= length; i += L) {
            var b = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cb, i));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cs, i));
            var a = FLOAT_VECTOR.cast(FloatVector.fromArray(F, ab, i));
            var quotient = b.lanewise(VectorOperators.DIV, ONE.lanewise(VectorOperators.SUB, s)).lanewise(VectorOperators.MIN, ONE);
            var blended = quotient.blend(ONE, s.compare(VectorOperators.GE, ONE)).blend(ZERO, b.compare(VectorOperators.LE, ZERO));
            var mixed = ONE.lanewise(VectorOperators.SUB, a).lanewise(VectorOperators.MUL, s)
                    .lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, blended));
            FLOAT_VECTOR.cast(mixed).intoArray(cs, i);
        }
        return i;
    }

    /**
     * Mixes with {@link BlendMode#COLOR_BURN}.
     *
     * @param cb the backdrop colors
     * @param cs the source colors, replaced by the mix
     * @param ab the backdrop alphas
     * @param length the number of pixels
     * @return the first pixel left for the scalar tail
     */
    private int burn(float[] cb, float[] cs, float[] ab, int length) {
        var i = 0;
        for (; i + L <= length; i += L) {
            var b = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cb, i));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cs, i));
            var a = FLOAT_VECTOR.cast(FloatVector.fromArray(F, ab, i));
            var quotient = ONE.lanewise(VectorOperators.SUB, ONE.lanewise(VectorOperators.SUB, b)
                    .lanewise(VectorOperators.DIV, s).lanewise(VectorOperators.MIN, ONE));
            var blended = quotient.blend(ZERO, s.compare(VectorOperators.LE, ZERO)).blend(ONE, b.compare(VectorOperators.GE, ONE));
            var mixed = ONE.lanewise(VectorOperators.SUB, a).lanewise(VectorOperators.MUL, s)
                    .lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, blended));
            FLOAT_VECTOR.cast(mixed).intoArray(cs, i);
        }
        return i;
    }

    /**
     * Mixes with {@link BlendMode#HARD_LIGHT}.
     *
     * @param cb the backdrop colors
     * @param cs the source colors, replaced by the mix
     * @param ab the backdrop alphas
     * @param length the number of pixels
     * @return the first pixel left for the scalar tail
     */
    private int hardLight(float[] cb, float[] cs, float[] ab, int length) {
        var i = 0;
        for (; i + L <= length; i += L) {
            var b = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cb, i));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cs, i));
            var a = FLOAT_VECTOR.cast(FloatVector.fromArray(F, ab, i));
            var low = b.lanewise(VectorOperators.MUL, TWO.lanewise(VectorOperators.MUL, s));
            var t = TWO.lanewise(VectorOperators.MUL, s).lanewise(VectorOperators.SUB, ONE);
            var high = b.lanewise(VectorOperators.ADD, t).lanewise(VectorOperators.SUB, b.lanewise(VectorOperators.MUL, t));
            var blended = high.blend(low, s.compare(VectorOperators.LE, HALF));
            var mixed = ONE.lanewise(VectorOperators.SUB, a).lanewise(VectorOperators.MUL, s)
                    .lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, blended));
            FLOAT_VECTOR.cast(mixed).intoArray(cs, i);
        }
        return i;
    }

    /**
     * Mixes with {@link BlendMode#SOFT_LIGHT}.
     *
     * @param cb the backdrop colors
     * @param cs the source colors, replaced by the mix
     * @param ab the backdrop alphas
     * @param length the number of pixels
     * @return the first pixel left for the scalar tail
     */
    private int softLight(float[] cb, float[] cs, float[] ab, int length) {
        var i = 0;
        for (; i + L <= length; i += L) {
            var b = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cb, i));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cs, i));
            var a = FLOAT_VECTOR.cast(FloatVector.fromArray(F, ab, i));
            var low = b.lanewise(VectorOperators.SUB, ONE.lanewise(VectorOperators.SUB, TWO.lanewise(VectorOperators.MUL, s))
                    .lanewise(VectorOperators.MUL, b).lanewise(VectorOperators.MUL, ONE.lanewise(VectorOperators.SUB, b)));
            var polynomial = SIXTEEN.lanewise(VectorOperators.MUL, b).lanewise(VectorOperators.SUB, TWELVE)
                    .lanewise(VectorOperators.MUL, b).lanewise(VectorOperators.ADD, FOUR).lanewise(VectorOperators.MUL, b);
            var root = b.lanewise(VectorOperators.SQRT);
            var d = root.blend(polynomial, b.compare(VectorOperators.LE, QUARTER));
            var high = b.lanewise(VectorOperators.ADD, TWO.lanewise(VectorOperators.MUL, s).lanewise(VectorOperators.SUB, ONE)
                    .lanewise(VectorOperators.MUL, d.lanewise(VectorOperators.SUB, b)));
            var blended = high.blend(low, s.compare(VectorOperators.LE, HALF));
            var mixed = ONE.lanewise(VectorOperators.SUB, a).lanewise(VectorOperators.MUL, s)
                    .lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, blended));
            FLOAT_VECTOR.cast(mixed).intoArray(cs, i);
        }
        return i;
    }

    /**
     * Mixes with {@link BlendMode#DIFFERENCE}.
     *
     * @param cb the backdrop colors
     * @param cs the source colors, replaced by the mix
     * @param ab the backdrop alphas
     * @param length the number of pixels
     * @return the first pixel left for the scalar tail
     */
    private int difference(float[] cb, float[] cs, float[] ab, int length) {
        var i = 0;
        for (; i + L <= length; i += L) {
            var b = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cb, i));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cs, i));
            var a = FLOAT_VECTOR.cast(FloatVector.fromArray(F, ab, i));
            var blended = b.lanewise(VectorOperators.SUB, s).lanewise(VectorOperators.ABS);
            var mixed = ONE.lanewise(VectorOperators.SUB, a).lanewise(VectorOperators.MUL, s)
                    .lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, blended));
            FLOAT_VECTOR.cast(mixed).intoArray(cs, i);
        }
        return i;
    }

    /**
     * Mixes with {@link BlendMode#EXCLUSION}.
     *
     * @param cb the backdrop colors
     * @param cs the source colors, replaced by the mix
     * @param ab the backdrop alphas
     * @param length the number of pixels
     * @return the first pixel left for the scalar tail
     */
    private int exclusion(float[] cb, float[] cs, float[] ab, int length) {
        var i = 0;
        for (; i + L <= length; i += L) {
            var b = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cb, i));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cs, i));
            var a = FLOAT_VECTOR.cast(FloatVector.fromArray(F, ab, i));
            var blended = b.lanewise(VectorOperators.ADD, s)
                    .lanewise(VectorOperators.SUB, TWO.lanewise(VectorOperators.MUL, b.lanewise(VectorOperators.MUL, s)));
            var mixed = ONE.lanewise(VectorOperators.SUB, a).lanewise(VectorOperators.MUL, s)
                    .lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, blended));
            FLOAT_VECTOR.cast(mixed).intoArray(cs, i);
        }
        return i;
    }

    /**
     * Mixes with {@link BlendMode#ADD}.
     *
     * @param cb the backdrop colors
     * @param cs the source colors, replaced by the mix
     * @param ab the backdrop alphas
     * @param length the number of pixels
     * @return the first pixel left for the scalar tail
     */
    private int add(float[] cb, float[] cs, float[] ab, int length) {
        var i = 0;
        for (; i + L <= length; i += L) {
            var b = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cb, i));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cs, i));
            var a = FLOAT_VECTOR.cast(FloatVector.fromArray(F, ab, i));
            var blended = b.lanewise(VectorOperators.ADD, s).lanewise(VectorOperators.MIN, ONE);
            var mixed = ONE.lanewise(VectorOperators.SUB, a).lanewise(VectorOperators.MUL, s)
                    .lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, blended));
            FLOAT_VECTOR.cast(mixed).intoArray(cs, i);
        }
        return i;
    }

    /**
     * Mixes with {@link BlendMode#SUBTRACT}.
     *
     * @param cb the backdrop colors
     * @param cs the source colors, replaced by the mix
     * @param ab the backdrop alphas
     * @param length the number of pixels
     * @return the first pixel left for the scalar tail
     */
    private int subtract(float[] cb, float[] cs, float[] ab, int length) {
        var i = 0;
        for (; i + L <= length; i += L) {
            var b = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cb, i));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(F, cs, i));
            var a = FLOAT_VECTOR.cast(FloatVector.fromArray(F, ab, i));
            var blended = b.lanewise(VectorOperators.SUB, s).lanewise(VectorOperators.MAX, ZERO);
            var mixed = ONE.lanewise(VectorOperators.SUB, a).lanewise(VectorOperators.MUL, s)
                    .lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.MUL, blended));
            FLOAT_VECTOR.cast(mixed).intoArray(cs, i);
        }
        return i;
    }

    /**
     * Composites mixed source colors over the backdrop, source-over.
     *
     * @param br the backdrop red values, replaced by the result
     * @param bg the backdrop green values, replaced by the result
     * @param bb the backdrop blue values, replaced by the result
     * @param ba the backdrop alpha values, replaced by the result
     * @param sr the mixed source red values
     * @param sg the mixed source green values
     * @param sb the mixed source blue values
     * @param sa the source alpha values
     * @param opacity the factor applied to the source alpha
     * @param length the number of pixels
     */
    private void composite(float[] br, float[] bg, float[] bb, float[] ba, float[] sr, float[] sg, float[] sb,
                           float[] sa, float opacity, int length) {
        var factor = FLOAT_VECTOR.cast(FloatVector.broadcast(F, opacity));
        var i = 0;
        for (; i + L <= length; i += L) {
            var as = FLOAT_VECTOR.cast(FloatVector.fromArray(F, sa, i)).lanewise(VectorOperators.MUL, factor);
            var ab = FLOAT_VECTOR.cast(FloatVector.fromArray(F, ba, i));
            var backWeight = ab.lanewise(VectorOperators.MUL, ONE.lanewise(VectorOperators.SUB, as));
            var ao = as.lanewise(VectorOperators.ADD, backWeight);
            var visible = ao.compare(VectorOperators.GT, ZERO);
            var red = as.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(FloatVector.fromArray(F, sr, i)))
                    .lanewise(VectorOperators.ADD, backWeight.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(FloatVector.fromArray(F, br, i))))
                    .lanewise(VectorOperators.DIV, ao);
            var green = as.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(FloatVector.fromArray(F, sg, i)))
                    .lanewise(VectorOperators.ADD, backWeight.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(FloatVector.fromArray(F, bg, i))))
                    .lanewise(VectorOperators.DIV, ao);
            var blue = as.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(FloatVector.fromArray(F, sb, i)))
                    .lanewise(VectorOperators.ADD, backWeight.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(FloatVector.fromArray(F, bb, i))))
                    .lanewise(VectorOperators.DIV, ao);
            FLOAT_VECTOR.cast(ZERO.blend(red, visible)).intoArray(br, i);
            FLOAT_VECTOR.cast(ZERO.blend(green, visible)).intoArray(bg, i);
            FLOAT_VECTOR.cast(ZERO.blend(blue, visible)).intoArray(bb, i);
            FLOAT_VECTOR.cast(ao).intoArray(ba, i);
        }
        scalar.compositeRange(br, bg, bb, ba, sr, sg, sb, sa, opacity, i, length);
    }
}
