package com.github.auties00.smalt.image.webp.internal.lossless;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements the lossless pixel kernels with the Vector API.
 *
 * <p>Every kernel is lane-wise: the per-pixel arithmetic of {@link ScalarLosslessDsp} applied to
 * a vector of pixels at a time, with the scalar kernel finishing each tail. When inverting the
 * predictor transform, the modes whose prediction depends on the reconstructed left neighbor
 * are inherently serial and delegate to the scalar kernel, while the modes that read only the
 * row above (0, 2, 3, 4, 8, 9 and the black sentinels 14 and 15) are vectorized; the forward
 * residuals read original pixels only, so every mode is vectorized. The entropy estimates find
 * runs of equal counts with lane-wise comparisons and scan only the nonzero counts.
 *
 * <p>Every Vector API operation goes through the per-shape methods ({@code lanewise} with an
 * operator and a vector operand, {@code compare}, {@code blend}, {@code rearrange}), constants
 * are {@code static final} vectors, no method takes or returns a vector, and a vector carried
 * across loop iterations is only ever an operand. The convenience methods ({@code add},
 * {@code min}, the scalar-operand overloads) dispatch through code that every vector shape
 * shares: when the JIT compiler meets them in a kernel it compiled into a caller before
 * profiling it, or in a program that uses several shapes, it can leave them unintrinsified, and
 * every call then runs an allocating Java fallback.
 * The methods the abstract vector classes implement once for every shape ({@code intoArray},
 * {@code lanewise} with a scalar operand, {@code reinterpretAsInts} and its siblings) call
 * methods on the vector itself, which the JIT compiler binds through a type profile that every
 * shape of the program shares: unless it knows the vector's exact class it keeps a virtual call,
 * and the vector is then allocated on every execution. Reinterpretations therefore use the
 * per-shape {@code reinterpretShape}, and every load and every reinterpretation is cast through
 * the constant exact class of its species, which gives the JIT compiler the class without a type
 * profile.
 *
 * @implNote This class references {@code jdk.incubator.vector} types, so it is loaded only when
 * {@link com.github.auties00.smalt.internal.simd.Simd#ENABLED} is {@code true}.
 */
final class VectorLosslessDsp implements LosslessDsp {
    /**
     * The integer vector shape used by every kernel.
     */
    private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;

    /**
     * The byte species of the same shape as {@link #SPECIES}, four lanes per pixel.
     */
    private static final VectorSpecies<Byte> BYTES = VectorSpecies.of(byte.class, SPECIES.vectorShape());

    /**
     * The exact class of the vectors of {@link #SPECIES}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(SPECIES);

    /**
     * The exact class of the vectors of {@link #BYTES}.
     */
    private static final Class<ByteVector> BYTE_VECTOR = vectorClass(BYTES);

    /**
     * The lowest byte of every lane.
     */
    private static final IntVector LOW_BYTE = IntVector.broadcast(SPECIES, 0xFF);

    /**
     * The low 16 bits of every lane.
     */
    private static final IntVector LOW_HALF = IntVector.broadcast(SPECIES, 0xFFFF);

    /**
     * One added to the bit above the red and the blue channel of every pixel lane, so that
     * subtracting the green channel from them cannot borrow across channels.
     */
    private static final IntVector GREEN_BORROW = IntVector.broadcast(SPECIES, 0x01000100);

    /**
     * The low seven bits of each byte of every lane.
     */
    private static final IntVector SEVEN_BITS = IntVector.broadcast(SPECIES, 0x7F7F7F7F);

    /**
     * The alpha channel of the black pixel {@code 0xFF000000} in every pixel lane.
     */
    private static final IntVector OPAQUE = IntVector.broadcast(SPECIES, 0xFF000000);

    /**
     * The alpha and green channels of every pixel lane.
     */
    private static final IntVector ALPHA_GREEN = IntVector.broadcast(SPECIES, 0xFF00FF00);

    /**
     * The red and blue channels of every pixel lane.
     */
    private static final IntVector RED_BLUE = IntVector.broadcast(SPECIES, 0x00FF00FF);

    /**
     * The alpha and green channels of the black pixel {@code 0xFF000000}.
     */
    private static final IntVector BLACK_ALPHA_GREEN = IntVector.broadcast(SPECIES, ScalarLosslessDsp.ARGB_BLACK & 0xFF00FF00);

    /**
     * Every bit of a pixel lane except the lowest bit of each channel, kept by the carry-free
     * channel average.
     */
    private static final IntVector HALF_MASK = IntVector.broadcast(SPECIES, 0xFEFEFEFE);

    /**
     * Zero in every lane, the receiver that turns an accumulator back into a plain operand.
     */
    private static final IntVector ZERO = IntVector.zero(SPECIES);

    /**
     * The reference kernel used for tails and serial predictor modes.
     */
    private final ScalarLosslessDsp scalar;

    /**
     * Constructs the kernel.
     */
    private VectorLosslessDsp() {
        this.scalar = new ScalarLosslessDsp();
    }

    /**
     * Creates the vectorized kernel.
     *
     * @return the kernel, typed as its interface so callers never link this class eagerly
     */
    static LosslessDsp create() {
        return new VectorLosslessDsp();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the species
     * @param <E>     the element type
     * @param <V>     the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void addGreenToBlueAndRed(int[] src, int srcPos, int[] dst, int dstPos, int count) {
        var bound = SPECIES.loopBound(count);
        var i = 0;
        for (; i < bound; i += SPECIES.length()) {
            var v = INT_VECTOR.cast(IntVector.fromArray(SPECIES, src, srcPos + i));
            var green = v.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE);
            var redBlue = v.lanewise(VectorOperators.AND, RED_BLUE).lanewise(VectorOperators.ADD, green.lanewise(VectorOperators.LSHL, 16).lanewise(VectorOperators.OR, green)).lanewise(VectorOperators.AND, RED_BLUE);
            v.lanewise(VectorOperators.AND, ALPHA_GREEN).lanewise(VectorOperators.OR, redBlue).intoArray(dst, dstPos + i);
        }
        scalar.addGreenToBlueAndRed(src, srcPos + i, dst, dstPos + i, count - i);
    }

    @Override
    public void subtractGreenFromBlueAndRed(int[] data, int pos, int count) {
        var bound = SPECIES.loopBound(count);
        var i = 0;
        for (; i < bound; i += SPECIES.length()) {
            var v = INT_VECTOR.cast(IntVector.fromArray(SPECIES, data, pos + i));
            var green = v.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, LOW_BYTE);
            var redBlue = v.lanewise(VectorOperators.AND, RED_BLUE).lanewise(VectorOperators.SUB, green.lanewise(VectorOperators.LSHL, 16).lanewise(VectorOperators.OR, green))
                    .lanewise(VectorOperators.ADD, GREEN_BORROW).lanewise(VectorOperators.AND, RED_BLUE);
            v.lanewise(VectorOperators.AND, ALPHA_GREEN).lanewise(VectorOperators.OR, redBlue).intoArray(data, pos + i);
        }
        scalar.subtractGreenFromBlueAndRed(data, pos + i, count - i);
    }

    @Override
    public void transformColorInverse(int code, int[] src, int srcPos, int[] dst, int dstPos, int count) {
        var greenToRed = (int) (byte) code;
        var greenToBlue = (int) (byte) (code >>> 8);
        var redToBlue = (int) (byte) (code >>> 16);
        var bound = SPECIES.loopBound(count);
        var i = 0;
        for (; i < bound; i += SPECIES.length()) {
            var v = INT_VECTOR.cast(IntVector.fromArray(SPECIES, src, srcPos + i));
            var green = v.lanewise(VectorOperators.LSHL, 16).lanewise(VectorOperators.ASHR, 24);
            var newRed = v.lanewise(VectorOperators.LSHR, 16)
                    .lanewise(VectorOperators.ADD, green.lanewise(VectorOperators.MUL, IntVector.broadcast(SPECIES, greenToRed)).lanewise(VectorOperators.ASHR, 5)).lanewise(VectorOperators.AND, LOW_BYTE);
            var red = newRed.lanewise(VectorOperators.LSHL, 24).lanewise(VectorOperators.ASHR, 24);
            var newBlue = v.lanewise(VectorOperators.ADD, green.lanewise(VectorOperators.MUL, IntVector.broadcast(SPECIES, greenToBlue)).lanewise(VectorOperators.ASHR, 5))
                    .lanewise(VectorOperators.ADD, red.lanewise(VectorOperators.MUL, IntVector.broadcast(SPECIES, redToBlue)).lanewise(VectorOperators.ASHR, 5)).lanewise(VectorOperators.AND, LOW_BYTE);
            v.lanewise(VectorOperators.AND, ALPHA_GREEN).lanewise(VectorOperators.OR, newRed.lanewise(VectorOperators.LSHL, 16)).lanewise(VectorOperators.OR, newBlue).intoArray(dst, dstPos + i);
        }
        scalar.transformColorInverse(code, src, srcPos + i, dst, dstPos + i, count - i);
    }

    @Override
    public void transformColor(int code, int[] data, int pos, int count) {
        var greenToRed = (int) (byte) code;
        var greenToBlue = (int) (byte) (code >>> 8);
        var redToBlue = (int) (byte) (code >>> 16);
        var bound = SPECIES.loopBound(count);
        var i = 0;
        for (; i < bound; i += SPECIES.length()) {
            var v = INT_VECTOR.cast(IntVector.fromArray(SPECIES, data, pos + i));
            var green = v.lanewise(VectorOperators.LSHL, 16).lanewise(VectorOperators.ASHR, 24);
            var red = v.lanewise(VectorOperators.LSHL, 8).lanewise(VectorOperators.ASHR, 24);
            var newRed = v.lanewise(VectorOperators.LSHR, 16)
                    .lanewise(VectorOperators.SUB, green.lanewise(VectorOperators.MUL, IntVector.broadcast(SPECIES, greenToRed)).lanewise(VectorOperators.ASHR, 5)).lanewise(VectorOperators.AND, LOW_BYTE);
            var newBlue = v.lanewise(VectorOperators.SUB, green.lanewise(VectorOperators.MUL, IntVector.broadcast(SPECIES, greenToBlue)).lanewise(VectorOperators.ASHR, 5))
                    .lanewise(VectorOperators.SUB, red.lanewise(VectorOperators.MUL, IntVector.broadcast(SPECIES, redToBlue)).lanewise(VectorOperators.ASHR, 5)).lanewise(VectorOperators.AND, LOW_BYTE);
            v.lanewise(VectorOperators.AND, ALPHA_GREEN).lanewise(VectorOperators.OR, newRed.lanewise(VectorOperators.LSHL, 16)).lanewise(VectorOperators.OR, newBlue).intoArray(data, pos + i);
        }
        scalar.transformColor(code, data, pos + i, count - i);
    }

    @Override
    public void predictorAdd(int mode, int[] in, int inPos, int[] out, int outPos, int count, int width) {
        var bound = SPECIES.loopBound(count);
        switch (mode) {
            case 0, 14, 15 -> addBlack(in, inPos, out, outPos, bound);
            case 2 -> addTop(in, inPos, out, outPos, outPos - width, bound);
            case 3 -> addTop(in, inPos, out, outPos, outPos - width + 1, bound);
            case 4 -> addTop(in, inPos, out, outPos, outPos - width - 1, bound);
            case 8 -> addAverageTop(in, inPos, out, outPos, outPos - width - 1, bound);
            case 9 -> addAverageTop(in, inPos, out, outPos, outPos - width, bound);
            default -> bound = 0;
        }
        scalar.predictorAdd(mode, in, inPos + bound, out, outPos + bound, count - bound, width);
    }

    /**
     * Adds the black pixel prediction (modes 0, 14 and 15) to residuals over whole vectors.
     *
     * @param in     the residuals
     * @param inPos  the index of the first residual
     * @param out    the reconstructed pixels
     * @param outPos the index of the first reconstructed pixel
     * @param bound  the number of pixels, a multiple of the vector length
     */
    private static void addBlack(int[] in, int inPos, int[] out, int outPos, int bound) {
        for (var i = 0; i < bound; i += SPECIES.length()) {
            var v = INT_VECTOR.cast(IntVector.fromArray(SPECIES, in, inPos + i));
            var alphaGreen = v.lanewise(VectorOperators.AND, ALPHA_GREEN).lanewise(VectorOperators.ADD, BLACK_ALPHA_GREEN)
                    .lanewise(VectorOperators.AND, ALPHA_GREEN);
            alphaGreen.lanewise(VectorOperators.OR, v.lanewise(VectorOperators.AND, RED_BLUE)).intoArray(out, outPos + i);
        }
    }

    /**
     * Adds a prediction from the row above (modes 2, 3 and 4) to residuals over whole vectors.
     *
     * @param in     the residuals
     * @param inPos  the index of the first residual
     * @param out    the reconstructed pixels, holding the row above
     * @param outPos the index of the first reconstructed pixel
     * @param topPos the index of the predicting pixel of the first reconstructed pixel
     * @param bound  the number of pixels, a multiple of the vector length
     */
    private static void addTop(int[] in, int inPos, int[] out, int outPos, int topPos, int bound) {
        for (var i = 0; i < bound; i += SPECIES.length()) {
            var v = INT_VECTOR.cast(IntVector.fromArray(SPECIES, in, inPos + i));
            var t = INT_VECTOR.cast(IntVector.fromArray(SPECIES, out, topPos + i));
            var alphaGreen = v.lanewise(VectorOperators.AND, ALPHA_GREEN)
                    .lanewise(VectorOperators.ADD, t.lanewise(VectorOperators.AND, ALPHA_GREEN))
                    .lanewise(VectorOperators.AND, ALPHA_GREEN);
            var redBlue = v.lanewise(VectorOperators.AND, RED_BLUE)
                    .lanewise(VectorOperators.ADD, t.lanewise(VectorOperators.AND, RED_BLUE))
                    .lanewise(VectorOperators.AND, RED_BLUE);
            alphaGreen.lanewise(VectorOperators.OR, redBlue).intoArray(out, outPos + i);
        }
    }

    /**
     * Adds the average of two horizontally adjacent pixels of the row above (modes 8 and 9) to
     * residuals over whole vectors.
     *
     * @param in     the residuals
     * @param inPos  the index of the first residual
     * @param out    the reconstructed pixels, holding the row above
     * @param outPos the index of the first reconstructed pixel
     * @param topPos the index of the left pixel of the first averaged pair
     * @param bound  the number of pixels, a multiple of the vector length
     */
    private static void addAverageTop(int[] in, int inPos, int[] out, int outPos, int topPos, int bound) {
        for (var i = 0; i < bound; i += SPECIES.length()) {
            var v = INT_VECTOR.cast(IntVector.fromArray(SPECIES, in, inPos + i));
            var a = INT_VECTOR.cast(IntVector.fromArray(SPECIES, out, topPos + i));
            var b = INT_VECTOR.cast(IntVector.fromArray(SPECIES, out, topPos + i + 1));
            var average = a.lanewise(VectorOperators.XOR, b).lanewise(VectorOperators.AND, HALF_MASK)
                    .lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.AND, b));
            var alphaGreen = v.lanewise(VectorOperators.AND, ALPHA_GREEN)
                    .lanewise(VectorOperators.ADD, average.lanewise(VectorOperators.AND, ALPHA_GREEN))
                    .lanewise(VectorOperators.AND, ALPHA_GREEN);
            var redBlue = v.lanewise(VectorOperators.AND, RED_BLUE)
                    .lanewise(VectorOperators.ADD, average.lanewise(VectorOperators.AND, RED_BLUE))
                    .lanewise(VectorOperators.AND, RED_BLUE);
            alphaGreen.lanewise(VectorOperators.OR, redBlue).intoArray(out, outPos + i);
        }
    }

    @Override
    public void predictorResiduals(int mode, int[] current, int curPos, int[] upper, int upperPos, int count, int[] out,
                                   int outPos) {
        var bound = SPECIES.loopBound(count);
        switch (mode) {
            case 1 -> residualsLeft(current, curPos, bound, out, outPos);
            case 2 -> residualsCopy(current, curPos, upper, upperPos, bound, out, outPos);
            case 3 -> residualsCopy(current, curPos, upper, upperPos + 1, bound, out, outPos);
            case 4 -> residualsCopy(current, curPos, upper, upperPos - 1, bound, out, outPos);
            case 5 -> residualsAverage3(current, curPos, upper, upperPos, bound, out, outPos);
            case 6 -> residualsAverageLeft(current, curPos, upper, upperPos - 1, bound, out, outPos);
            case 7 -> residualsAverageLeft(current, curPos, upper, upperPos, bound, out, outPos);
            case 8 -> residualsAverageTop(current, curPos, upper, upperPos - 1, bound, out, outPos);
            case 9 -> residualsAverageTop(current, curPos, upper, upperPos, bound, out, outPos);
            case 10 -> residualsAverage4(current, curPos, upper, upperPos, bound, out, outPos);
            case 11 -> residualsSelect(current, curPos, upper, upperPos, bound, out, outPos);
            case 12 -> residualsClampFull(current, curPos, upper, upperPos, bound, out, outPos);
            case 13 -> residualsClampHalf(current, curPos, upper, upperPos, bound, out, outPos);
            default -> residualsBlack(current, curPos, bound, out, outPos);
        }
        scalar.predictorResiduals(mode, current, curPos + bound, upper, upperPos + bound, count - bound, out,
                outPos + bound);
    }

    /**
     * Computes black-predicted residuals (mode 0) over whole vectors.
     *
     * @param current the pixels
     * @param curPos  the index of the first pixel
     * @param bound   the number of pixels, a multiple of the vector length
     * @param out     the residuals
     * @param outPos  the index of the first residual
     */
    private static void residualsBlack(int[] current, int curPos, int bound, int[] out, int outPos) {
        for (var i = 0; i < bound; i += SPECIES.length()) {
            var c = INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i));
            var alphaGreen = c.lanewise(VectorOperators.AND, ALPHA_GREEN).lanewise(VectorOperators.ADD, RED_BLUE).lanewise(VectorOperators.SUB, OPAQUE).lanewise(VectorOperators.AND, ALPHA_GREEN);
            alphaGreen.lanewise(VectorOperators.OR, c.lanewise(VectorOperators.AND, RED_BLUE)).intoArray(out, outPos + i);
        }
    }

    /**
     * Computes left-predicted residuals (mode 1) over whole vectors.
     *
     * @param current the pixels
     * @param curPos  the index of the first pixel
     * @param bound   the number of pixels, a multiple of the vector length
     * @param out     the residuals
     * @param outPos  the index of the first residual
     */
    private static void residualsLeft(int[] current, int curPos, int bound, int[] out, int outPos) {
        for (var i = 0; i < bound; i += SPECIES.length()) {
            var c = BYTE_VECTOR.cast(INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i)).reinterpretShape(BYTES, 0));
            var l = BYTE_VECTOR.cast(INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i - 1)).reinterpretShape(BYTES, 0));
            INT_VECTOR.cast(c.lanewise(VectorOperators.SUB, l).reinterpretShape(SPECIES, 0)).intoArray(out, outPos + i);
        }
    }

    /**
     * Computes residuals predicted by one neighbor of the row above (modes 2, 3 and 4) over
     * whole vectors.
     *
     * @param current the pixels
     * @param curPos  the index of the first pixel
     * @param upper   the row above
     * @param topPos  the index of the first pixel's predicting neighbor
     * @param bound   the number of pixels, a multiple of the vector length
     * @param out     the residuals
     * @param outPos  the index of the first residual
     */
    private static void residualsCopy(int[] current, int curPos, int[] upper, int topPos, int bound, int[] out,
                                      int outPos) {
        for (var i = 0; i < bound; i += SPECIES.length()) {
            var c = BYTE_VECTOR.cast(INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i)).reinterpretShape(BYTES, 0));
            var t = BYTE_VECTOR.cast(INT_VECTOR.cast(IntVector.fromArray(SPECIES, upper, topPos + i)).reinterpretShape(BYTES, 0));
            INT_VECTOR.cast(c.lanewise(VectorOperators.SUB, t).reinterpretShape(SPECIES, 0)).intoArray(out, outPos + i);
        }
    }

    /**
     * Computes residuals predicted by the average of the top pixel and the left/top-right
     * average (mode 5) over whole vectors.
     *
     * @param current  the pixels
     * @param curPos   the index of the first pixel
     * @param upper    the row above
     * @param upperPos the index of the first pixel's top neighbor
     * @param bound    the number of pixels, a multiple of the vector length
     * @param out      the residuals
     * @param outPos   the index of the first residual
     */
    private static void residualsAverage3(int[] current, int curPos, int[] upper, int upperPos, int bound, int[] out,
                                          int outPos) {
        for (var i = 0; i < bound; i += SPECIES.length()) {
            var c = INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i));
            var l = INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i - 1));
            var t = INT_VECTOR.cast(IntVector.fromArray(SPECIES, upper, upperPos + i));
            var tr = INT_VECTOR.cast(IntVector.fromArray(SPECIES, upper, upperPos + i + 1));
            var a = l.lanewise(VectorOperators.XOR, tr).lanewise(VectorOperators.AND, HALF_MASK).lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.ADD, l.lanewise(VectorOperators.AND, tr));
            var pred = a.lanewise(VectorOperators.XOR, t).lanewise(VectorOperators.AND, HALF_MASK).lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.AND, t));
            INT_VECTOR.cast(BYTE_VECTOR.cast(c.reinterpretShape(BYTES, 0)).lanewise(VectorOperators.SUB, BYTE_VECTOR.cast(pred.reinterpretShape(BYTES, 0))).reinterpretShape(SPECIES, 0)).intoArray(out, outPos + i);
        }
    }

    /**
     * Computes residuals predicted by the average of the left pixel and one pixel of the row
     * above (modes 6 and 7) over whole vectors.
     *
     * @param current the pixels
     * @param curPos  the index of the first pixel
     * @param upper   the row above
     * @param topPos  the index of the first pixel's averaged upper neighbor
     * @param bound   the number of pixels, a multiple of the vector length
     * @param out     the residuals
     * @param outPos  the index of the first residual
     */
    private static void residualsAverageLeft(int[] current, int curPos, int[] upper, int topPos, int bound, int[] out,
                                             int outPos) {
        for (var i = 0; i < bound; i += SPECIES.length()) {
            var c = INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i));
            var l = INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i - 1));
            var t = INT_VECTOR.cast(IntVector.fromArray(SPECIES, upper, topPos + i));
            var pred = l.lanewise(VectorOperators.XOR, t).lanewise(VectorOperators.AND, HALF_MASK).lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.ADD, l.lanewise(VectorOperators.AND, t));
            INT_VECTOR.cast(BYTE_VECTOR.cast(c.reinterpretShape(BYTES, 0)).lanewise(VectorOperators.SUB, BYTE_VECTOR.cast(pred.reinterpretShape(BYTES, 0))).reinterpretShape(SPECIES, 0)).intoArray(out, outPos + i);
        }
    }

    /**
     * Computes residuals predicted by the average of two adjacent pixels of the row above (modes
     * 8 and 9) over whole vectors.
     *
     * @param current the pixels
     * @param curPos  the index of the first pixel
     * @param upper   the row above
     * @param topPos  the index of the first pixel's left averaged neighbor
     * @param bound   the number of pixels, a multiple of the vector length
     * @param out     the residuals
     * @param outPos  the index of the first residual
     */
    private static void residualsAverageTop(int[] current, int curPos, int[] upper, int topPos, int bound, int[] out,
                                            int outPos) {
        for (var i = 0; i < bound; i += SPECIES.length()) {
            var c = INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i));
            var a = INT_VECTOR.cast(IntVector.fromArray(SPECIES, upper, topPos + i));
            var b = INT_VECTOR.cast(IntVector.fromArray(SPECIES, upper, topPos + i + 1));
            var pred = a.lanewise(VectorOperators.XOR, b).lanewise(VectorOperators.AND, HALF_MASK).lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.AND, b));
            INT_VECTOR.cast(BYTE_VECTOR.cast(c.reinterpretShape(BYTES, 0)).lanewise(VectorOperators.SUB, BYTE_VECTOR.cast(pred.reinterpretShape(BYTES, 0))).reinterpretShape(SPECIES, 0)).intoArray(out, outPos + i);
        }
    }

    /**
     * Computes residuals predicted by the average of the left/top-left and top/top-right
     * averages (mode 10) over whole vectors.
     *
     * @param current  the pixels
     * @param curPos   the index of the first pixel
     * @param upper    the row above
     * @param upperPos the index of the first pixel's top neighbor
     * @param bound    the number of pixels, a multiple of the vector length
     * @param out      the residuals
     * @param outPos   the index of the first residual
     */
    private static void residualsAverage4(int[] current, int curPos, int[] upper, int upperPos, int bound, int[] out,
                                          int outPos) {
        for (var i = 0; i < bound; i += SPECIES.length()) {
            var c = INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i));
            var l = INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i - 1));
            var tl = INT_VECTOR.cast(IntVector.fromArray(SPECIES, upper, upperPos + i - 1));
            var t = INT_VECTOR.cast(IntVector.fromArray(SPECIES, upper, upperPos + i));
            var tr = INT_VECTOR.cast(IntVector.fromArray(SPECIES, upper, upperPos + i + 1));
            var a = l.lanewise(VectorOperators.XOR, tl).lanewise(VectorOperators.AND, HALF_MASK).lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.ADD, l.lanewise(VectorOperators.AND, tl));
            var b = t.lanewise(VectorOperators.XOR, tr).lanewise(VectorOperators.AND, HALF_MASK).lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.ADD, t.lanewise(VectorOperators.AND, tr));
            var pred = a.lanewise(VectorOperators.XOR, b).lanewise(VectorOperators.AND, HALF_MASK).lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.ADD, a.lanewise(VectorOperators.AND, b));
            INT_VECTOR.cast(BYTE_VECTOR.cast(c.reinterpretShape(BYTES, 0)).lanewise(VectorOperators.SUB, BYTE_VECTOR.cast(pred.reinterpretShape(BYTES, 0))).reinterpretShape(SPECIES, 0)).intoArray(out, outPos + i);
        }
    }

    /**
     * Computes residuals predicted by the select predictor (mode 11) over whole vectors: the top
     * pixel when the left pixel's channel-wise distance to the top-left is at most the top
     * pixel's, the left pixel otherwise.
     *
     * @param current  the pixels
     * @param curPos   the index of the first pixel
     * @param upper    the row above
     * @param upperPos the index of the first pixel's top neighbor
     * @param bound    the number of pixels, a multiple of the vector length
     * @param out      the residuals
     * @param outPos   the index of the first residual
     */
    private static void residualsSelect(int[] current, int curPos, int[] upper, int upperPos, int bound, int[] out,
                                        int outPos) {
        for (var i = 0; i < bound; i += SPECIES.length()) {
            var c = INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i));
            var l = INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i - 1));
            var tl = BYTE_VECTOR.cast(INT_VECTOR.cast(IntVector.fromArray(SPECIES, upper, upperPos + i - 1)).reinterpretShape(BYTES, 0));
            var t = INT_VECTOR.cast(IntVector.fromArray(SPECIES, upper, upperPos + i));
            var lb = BYTE_VECTOR.cast(l.reinterpretShape(BYTES, 0));
            var tb = BYTE_VECTOR.cast(t.reinterpretShape(BYTES, 0));
            var leftDistance = INT_VECTOR.cast(lb.lanewise(VectorOperators.UMAX, tl).lanewise(VectorOperators.SUB, lb.lanewise(VectorOperators.UMIN, tl)).reinterpretShape(SPECIES, 0));
            var topDistance = INT_VECTOR.cast(tb.lanewise(VectorOperators.UMAX, tl).lanewise(VectorOperators.SUB, tb.lanewise(VectorOperators.UMIN, tl)).reinterpretShape(SPECIES, 0));
            var leftPairs = leftDistance.lanewise(VectorOperators.AND, RED_BLUE).lanewise(VectorOperators.ADD, leftDistance.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, RED_BLUE));
            var topPairs = topDistance.lanewise(VectorOperators.AND, RED_BLUE).lanewise(VectorOperators.ADD, topDistance.lanewise(VectorOperators.LSHR, 8).lanewise(VectorOperators.AND, RED_BLUE));
            var leftSum = leftPairs.lanewise(VectorOperators.AND, LOW_HALF).lanewise(VectorOperators.ADD, leftPairs.lanewise(VectorOperators.LSHR, 16));
            var topSum = topPairs.lanewise(VectorOperators.AND, LOW_HALF).lanewise(VectorOperators.ADD, topPairs.lanewise(VectorOperators.LSHR, 16));
            var pred = l.blend(t, leftSum.compare(VectorOperators.LE, topSum));
            INT_VECTOR.cast(BYTE_VECTOR.cast(c.reinterpretShape(BYTES, 0)).lanewise(VectorOperators.SUB, BYTE_VECTOR.cast(pred.reinterpretShape(BYTES, 0))).reinterpretShape(SPECIES, 0)).intoArray(out, outPos + i);
        }
    }

    /**
     * Computes residuals predicted by {@code clamp(left + top - topLeft)} per channel (mode 12)
     * over whole vectors, as saturating unsigned byte arithmetic: one of {@code top - topLeft}
     * and {@code topLeft - top} always saturates to zero.
     *
     * @param current  the pixels
     * @param curPos   the index of the first pixel
     * @param upper    the row above
     * @param upperPos the index of the first pixel's top neighbor
     * @param bound    the number of pixels, a multiple of the vector length
     * @param out      the residuals
     * @param outPos   the index of the first residual
     */
    private static void residualsClampFull(int[] current, int curPos, int[] upper, int upperPos, int bound, int[] out,
                                           int outPos) {
        for (var i = 0; i < bound; i += SPECIES.length()) {
            var c = BYTE_VECTOR.cast(INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i)).reinterpretShape(BYTES, 0));
            var l = BYTE_VECTOR.cast(INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i - 1)).reinterpretShape(BYTES, 0));
            var tl = BYTE_VECTOR.cast(INT_VECTOR.cast(IntVector.fromArray(SPECIES, upper, upperPos + i - 1)).reinterpretShape(BYTES, 0));
            var t = BYTE_VECTOR.cast(INT_VECTOR.cast(IntVector.fromArray(SPECIES, upper, upperPos + i)).reinterpretShape(BYTES, 0));
            var pred = l.lanewise(VectorOperators.SUADD, t.lanewise(VectorOperators.SUSUB, tl))
                    .lanewise(VectorOperators.SUSUB, tl.lanewise(VectorOperators.SUSUB, t));
            INT_VECTOR.cast(c.lanewise(VectorOperators.SUB, pred).reinterpretShape(SPECIES, 0)).intoArray(out, outPos + i);
        }
    }

    /**
     * Computes residuals predicted by {@code clamp(a + (a - topLeft) / 2)} per channel, where
     * {@code a} averages the left and top pixels (mode 13), over whole vectors, as saturating
     * unsigned byte arithmetic on the two halved one-sided differences.
     *
     * @param current  the pixels
     * @param curPos   the index of the first pixel
     * @param upper    the row above
     * @param upperPos the index of the first pixel's top neighbor
     * @param bound    the number of pixels, a multiple of the vector length
     * @param out      the residuals
     * @param outPos   the index of the first residual
     */
    private static void residualsClampHalf(int[] current, int curPos, int[] upper, int upperPos, int bound, int[] out,
                                           int outPos) {
        for (var i = 0; i < bound; i += SPECIES.length()) {
            var c = INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i));
            var l = INT_VECTOR.cast(IntVector.fromArray(SPECIES, current, curPos + i - 1));
            var tl = BYTE_VECTOR.cast(INT_VECTOR.cast(IntVector.fromArray(SPECIES, upper, upperPos + i - 1)).reinterpretShape(BYTES, 0));
            var t = INT_VECTOR.cast(IntVector.fromArray(SPECIES, upper, upperPos + i));
            var ave = BYTE_VECTOR.cast(l.lanewise(VectorOperators.XOR, t).lanewise(VectorOperators.AND, HALF_MASK).lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.ADD, l.lanewise(VectorOperators.AND, t)).reinterpretShape(BYTES, 0));
            var up = BYTE_VECTOR.cast(INT_VECTOR.cast(ave.lanewise(VectorOperators.SUSUB, tl).reinterpretShape(SPECIES, 0))
                    .lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.AND, SEVEN_BITS).reinterpretShape(BYTES, 0));
            var down = BYTE_VECTOR.cast(INT_VECTOR.cast(tl.lanewise(VectorOperators.SUSUB, ave).reinterpretShape(SPECIES, 0))
                    .lanewise(VectorOperators.LSHR, 1).lanewise(VectorOperators.AND, SEVEN_BITS).reinterpretShape(BYTES, 0));
            var pred = ave.lanewise(VectorOperators.SUADD, up).lanewise(VectorOperators.SUSUB, down);
            INT_VECTOR.cast(BYTE_VECTOR.cast(c.reinterpretShape(BYTES, 0)).lanewise(VectorOperators.SUB, pred).reinterpretShape(SPECIES, 0)).intoArray(out, outPos + i);
        }
    }

    @Override
    public void argbToRgba(int[] src, int srcPos, byte[] dst, int dstPos, int count) {
        var bound = SPECIES.loopBound(count);
        var i = 0;
        for (; i < bound; i += SPECIES.length()) {
            var v = INT_VECTOR.cast(IntVector.fromArray(SPECIES, src, srcPos + i));
            var swapped = v.lanewise(VectorOperators.AND, ALPHA_GREEN)
                    .lanewise(VectorOperators.OR, v.lanewise(VectorOperators.LSHR, 16).lanewise(VectorOperators.AND, LOW_BYTE))
                    .lanewise(VectorOperators.OR, v.lanewise(VectorOperators.AND, LOW_BYTE).lanewise(VectorOperators.LSHL, 16));
            BYTE_VECTOR.cast(swapped.reinterpretShape(BYTES, 0)).intoArray(dst, dstPos + (i << 2));
        }
        scalar.argbToRgba(src, srcPos + i, dst, dstPos + (i << 2), count - i);
    }

    @Override
    public long combinedShannonEntropy(int[] x, int xOff, int[] y, int yOff) {
        long retval = 0;
        var sumX = IntVector.zero(SPECIES);
        var sumXY = IntVector.zero(SPECIES);
        for (var i = 0; i < 256; i += SPECIES.length()) {
            var vx = INT_VECTOR.cast(IntVector.fromArray(SPECIES, x, xOff + i));
            var vxy = vx.lanewise(VectorOperators.ADD, INT_VECTOR.cast(IntVector.fromArray(SPECIES, y, yOff + i)));
            sumX = vx.lanewise(VectorOperators.ADD, sumX);
            sumXY = vxy.lanewise(VectorOperators.ADD, sumXY);
            var nonZeroX = vx.compare(VectorOperators.NE, ZERO).toLong();
            var nonZeroXY = vxy.compare(VectorOperators.NE, ZERO).toLong();
            while (nonZeroX != 0) {
                retval += EntropyMath.fastSLog2(x[xOff + i + Long.numberOfTrailingZeros(nonZeroX)]);
                nonZeroX &= nonZeroX - 1;
            }
            while (nonZeroXY != 0) {
                var k = i + Long.numberOfTrailingZeros(nonZeroXY);
                retval += EntropyMath.fastSLog2(x[xOff + k] + y[yOff + k]);
                nonZeroXY &= nonZeroXY - 1;
            }
        }
        return EntropyMath.fastSLog2(ZERO.lanewise(VectorOperators.ADD, sumX).reduceLanes(VectorOperators.ADD))
                + EntropyMath.fastSLog2(ZERO.lanewise(VectorOperators.ADD, sumXY).reduceLanes(VectorOperators.ADD)) - retval;
    }

    @Override
    public void entropyUnrefined(int[] x, int[] y, int length, EntropyMath.Stats stats) {
        stats.reset();
        var lanes = SPECIES.length();
        var start = 0;
        var i = 1;
        int previous;
        if (y == null) {
            previous = x[0];
            for (; i + lanes <= length; i += lanes) {
                var changes = INT_VECTOR.cast(IntVector.fromArray(SPECIES, x, i))
                        .compare(VectorOperators.NE, INT_VECTOR.cast(IntVector.fromArray(SPECIES, x, i - 1))).toLong();
                while (changes != 0) {
                    var k = i + Long.numberOfTrailingZeros(changes);
                    stats.streak(previous, k - start, start);
                    previous = x[k];
                    start = k;
                    changes &= changes - 1;
                }
            }
            for (; i < length; i++) {
                var value = x[i];
                if (value != previous) {
                    stats.streak(previous, i - start, start);
                    previous = value;
                    start = i;
                }
            }
        } else {
            previous = x[0] + y[0];
            for (; i + lanes <= length; i += lanes) {
                var current = INT_VECTOR.cast(IntVector.fromArray(SPECIES, x, i)).lanewise(VectorOperators.ADD, INT_VECTOR.cast(IntVector.fromArray(SPECIES, y, i)));
                var before = INT_VECTOR.cast(IntVector.fromArray(SPECIES, x, i - 1)).lanewise(VectorOperators.ADD, INT_VECTOR.cast(IntVector.fromArray(SPECIES, y, i - 1)));
                var changes = current.compare(VectorOperators.NE, before).toLong();
                while (changes != 0) {
                    var k = i + Long.numberOfTrailingZeros(changes);
                    stats.streak(previous, k - start, start);
                    previous = x[k] + y[k];
                    start = k;
                    changes &= changes - 1;
                }
            }
            for (; i < length; i++) {
                var value = x[i] + y[i];
                if (value != previous) {
                    stats.streak(previous, i - start, start);
                    previous = value;
                    start = i;
                }
            }
        }
        stats.streak(previous, length - start, start);
        stats.entropy = EntropyMath.fastSLog2(stats.sum) - stats.entropy;
    }

    @Override
    public long combinedShannonEntropy(int[] x, int xOff, int[] y, int yOff, int sumY, long entropyY) {
        var retval = entropyY;
        var sumX = IntVector.zero(SPECIES);
        for (var i = 0; i < 256; i += SPECIES.length()) {
            var vx = INT_VECTOR.cast(IntVector.fromArray(SPECIES, x, xOff + i));
            var nonZero = vx.compare(VectorOperators.NE, ZERO).toLong();
            sumX = vx.lanewise(VectorOperators.ADD, sumX);
            if (nonZero != 0) {
                do {
                    var k = i + Long.numberOfTrailingZeros(nonZero);
                    var xv = x[xOff + k];
                    var yv = y[yOff + k];
                    retval += EntropyMath.fastSLog2(xv) + EntropyMath.fastSLog2(xv + yv) - EntropyMath.fastSLog2(yv);
                    nonZero &= nonZero - 1;
                } while (nonZero != 0);
            }
        }
        var totalX = ZERO.lanewise(VectorOperators.ADD, sumX).reduceLanes(VectorOperators.ADD);
        return EntropyMath.fastSLog2(totalX) + EntropyMath.fastSLog2(totalX + sumY) - retval;
    }

    @Override
    public void collectColorRedTransforms(int[] argb, int pos, int stride, int tileWidth, int tileHeight, int greenToRed,
                                          int[] histo, int[] scratch) {
        var bound = SPECIES.loopBound(tileWidth);
        var lanes = SPECIES.length();
        var g2r = (int) (byte) greenToRed;
        for (var y = 0; y < tileHeight; y++) {
            var row = pos + y * stride;
            var x = 0;
            for (; x < bound; x += lanes) {
                var pixel = INT_VECTOR.cast(IntVector.fromArray(SPECIES, argb, row + x));
                var green = pixel.lanewise(VectorOperators.LSHL, 16).lanewise(VectorOperators.ASHR, 24);
                pixel.lanewise(VectorOperators.LSHR, 16)
                        .lanewise(VectorOperators.SUB, green.lanewise(VectorOperators.MUL, IntVector.broadcast(SPECIES, g2r)).lanewise(VectorOperators.ASHR, 5))
                        .lanewise(VectorOperators.AND, LOW_BYTE)
                        .intoArray(scratch, 0);
                for (var k = 0; k < lanes; k++) {
                    ++histo[scratch[k]];
                }
            }
            scalar.collectColorRedTransforms(argb, row + x, stride, tileWidth - x, 1, greenToRed, histo, scratch);
        }
    }

    @Override
    public void collectColorBlueTransforms(int[] argb, int pos, int stride, int tileWidth, int tileHeight, int greenToBlue,
                                           int redToBlue, int[] histo, int[] scratch) {
        var bound = SPECIES.loopBound(tileWidth);
        var lanes = SPECIES.length();
        var g2b = (int) (byte) greenToBlue;
        var r2b = (int) (byte) redToBlue;
        for (var y = 0; y < tileHeight; y++) {
            var row = pos + y * stride;
            var x = 0;
            for (; x < bound; x += lanes) {
                var pixel = INT_VECTOR.cast(IntVector.fromArray(SPECIES, argb, row + x));
                var green = pixel.lanewise(VectorOperators.LSHL, 16).lanewise(VectorOperators.ASHR, 24);
                var red = pixel.lanewise(VectorOperators.LSHL, 8).lanewise(VectorOperators.ASHR, 24);
                pixel.lanewise(VectorOperators.SUB, green.lanewise(VectorOperators.MUL, IntVector.broadcast(SPECIES, g2b)).lanewise(VectorOperators.ASHR, 5))
                        .lanewise(VectorOperators.SUB, red.lanewise(VectorOperators.MUL, IntVector.broadcast(SPECIES, r2b)).lanewise(VectorOperators.ASHR, 5))
                        .lanewise(VectorOperators.AND, LOW_BYTE)
                        .intoArray(scratch, 0);
                for (var k = 0; k < lanes; k++) {
                    ++histo[scratch[k]];
                }
            }
            scalar.collectColorBlueTransforms(argb, row + x, stride, tileWidth - x, 1, greenToBlue, redToBlue, histo,
                    scratch);
        }
    }
}
