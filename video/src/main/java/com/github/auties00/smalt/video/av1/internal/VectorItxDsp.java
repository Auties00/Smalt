package com.github.auties00.smalt.video.av1.internal;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.util.Arrays;

/**
 * Implements the inverse transforms with the Vector API, producing output identical to
 * {@link ScalarItxDsp}.
 *
 * <p>A block whose one-dimensional transforms are all at most 32 points long runs both passes
 * 8 or 4 transforms at a time, one per 32-bit lane (the 32-point DCT only 8, in six methods that
 * pass their live values through the elements of the odd inputs it has consumed). The row pass works
 * in place on the coefficients, whose column-major order puts the same element of consecutive rows
 * next to each other; the rounding between the passes transposes the rows into the intermediate
 * buffer, where the same element of consecutive columns is contiguous for the column pass; the
 * result is added to the destination a row at a time. The one-dimensional kernels are generated
 * from the scalar ones statement by statement, so they compute exactly the same values. A block
 * with a 64-point DCT runs its one-dimensional transforms through {@link ScalarItxDsp} and
 * the rounding between the passes and the final addition through the vector kernels. Blocks with
 * only a DC coefficient and the lossless Walsh-Hadamard transform use {@link ScalarItxDsp}.
 *
 * <p>Every Vector API operation goes through the per-shape methods, constants are
 * {@code static final} vectors, no method takes or returns a vector, and every loaded or converted
 * vector, and every vector stored or shifted by a scalar, is first cast through the
 * {@code static final} class of its species; see {@link VectorMcDsp}.
 *
 * <p>Generated from {@code tools/codegen/av1}; edit the template.
 *
 * @implNote This implementation follows the structure of dav1d's x86 inverse transforms
 *           ({@code src/x86/itx16_avx2.asm}), which also transform several rows or columns per
 *           vector with 32-bit intermediates.
 */
public final class VectorItxDsp extends ItxDsp {
    /**
     * The 8-lane int species of 8 transforms at once.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * The 4-lane int species of 4 transforms at once.
     */
    private static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;

    /**
     * The 8-lane short species of 8-sample destination rows.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * The 4-lane short species of 4-sample destination rows.
     */
    private static final VectorSpecies<Short> S64 = ShortVector.SPECIES_64;

    /**
     * The exact class of {@link #I256} vectors.
     */
    private static final Class<IntVector> INT256 = vectorClass(I256);

    /**
     * The exact class of {@link #I128} vectors.
     */
    private static final Class<IntVector> INT128 = vectorClass(I128);

    /**
     * The exact class of {@link #S128} vectors.
     */
    private static final Class<ShortVector> SHORT128 = vectorClass(S128);

    /**
     * The exact class of {@link #S64} vectors.
     */
    private static final Class<ShortVector> SHORT64 = vectorClass(S64);

    /**
     * Four ints equal to {@code -1614}.
     */
    private static final IntVector K4_M1614 = IntVector.broadcast(I128, -1614);

    /**
     * Four ints equal to {@code -1061}.
     */
    private static final IntVector K4_M1061 = IntVector.broadcast(I128, -1061);

    /**
     * Four ints equal to {@code -752}.
     */
    private static final IntVector K4_M752 = IntVector.broadcast(I128, -752);

    /**
     * Four ints equal to {@code -690}.
     */
    private static final IntVector K4_M690 = IntVector.broadcast(I128, -690);

    /**
     * Four ints equal to {@code -583}.
     */
    private static final IntVector K4_M583 = IntVector.broadcast(I128, -583);

    /**
     * Four ints equal to {@code -484}.
     */
    private static final IntVector K4_M484 = IntVector.broadcast(I128, -484);

    /**
     * Four ints equal to {@code -393}.
     */
    private static final IntVector K4_M393 = IntVector.broadcast(I128, -393);

    /**
     * Four ints equal to {@code -312}.
     */
    private static final IntVector K4_M312 = IntVector.broadcast(I128, -312);

    /**
     * Four ints equal to {@code -293}.
     */
    private static final IntVector K4_M293 = IntVector.broadcast(I128, -293);

    /**
     * Four ints equal to {@code -239}.
     */
    private static final IntVector K4_M239 = IntVector.broadcast(I128, -239);

    /**
     * Four ints equal to {@code -176}.
     */
    private static final IntVector K4_M176 = IntVector.broadcast(I128, -176);

    /**
     * Four ints equal to {@code -123}.
     */
    private static final IntVector K4_M123 = IntVector.broadcast(I128, -123);

    /**
     * Four ints equal to {@code -79}.
     */
    private static final IntVector K4_M79 = IntVector.broadcast(I128, -79);

    /**
     * Four ints equal to {@code -44}.
     */
    private static final IntVector K4_M44 = IntVector.broadcast(I128, -44);

    /**
     * Four ints equal to {@code -20}.
     */
    private static final IntVector K4_M20 = IntVector.broadcast(I128, -20);

    /**
     * Four ints equal to {@code -5}.
     */
    private static final IntVector K4_M5 = IntVector.broadcast(I128, -5);

    /**
     * Four ints equal to {@code 8}.
     */
    private static final IntVector K4_8 = IntVector.broadcast(I128, 8);

    /**
     * Four ints equal to {@code 128}.
     */
    private static final IntVector K4_128 = IntVector.broadcast(I128, 128);

    /**
     * Four ints equal to {@code 181}.
     */
    private static final IntVector K4_181 = IntVector.broadcast(I128, 181);

    /**
     * Four ints equal to {@code 201}.
     */
    private static final IntVector K4_201 = IntVector.broadcast(I128, 201);

    /**
     * Four ints equal to {@code 209}.
     */
    private static final IntVector K4_209 = IntVector.broadcast(I128, 209);

    /**
     * Four ints equal to {@code 401}.
     */
    private static final IntVector K4_401 = IntVector.broadcast(I128, 401);

    /**
     * Four ints equal to {@code 601}.
     */
    private static final IntVector K4_601 = IntVector.broadcast(I128, 601);

    /**
     * Four ints equal to {@code 799}.
     */
    private static final IntVector K4_799 = IntVector.broadcast(I128, 799);

    /**
     * Four ints equal to {@code 995}.
     */
    private static final IntVector K4_995 = IntVector.broadcast(I128, 995);

    /**
     * Four ints equal to {@code 1024}.
     */
    private static final IntVector K4_1024 = IntVector.broadcast(I128, 1024);

    /**
     * Four ints equal to {@code 1138}.
     */
    private static final IntVector K4_1138 = IntVector.broadcast(I128, 1138);

    /**
     * Four ints equal to {@code 1189}.
     */
    private static final IntVector K4_1189 = IntVector.broadcast(I128, 1189);

    /**
     * Four ints equal to {@code 1220}.
     */
    private static final IntVector K4_1220 = IntVector.broadcast(I128, 1220);

    /**
     * Four ints equal to {@code 1299}.
     */
    private static final IntVector K4_1299 = IntVector.broadcast(I128, 1299);

    /**
     * Four ints equal to {@code 1321}.
     */
    private static final IntVector K4_1321 = IntVector.broadcast(I128, 1321);

    /**
     * Four ints equal to {@code 1380}.
     */
    private static final IntVector K4_1380 = IntVector.broadcast(I128, 1380);

    /**
     * Four ints equal to {@code 1567}.
     */
    private static final IntVector K4_1567 = IntVector.broadcast(I128, 1567);

    /**
     * Four ints equal to {@code 1583}.
     */
    private static final IntVector K4_1583 = IntVector.broadcast(I128, 1583);

    /**
     * Four ints equal to {@code 1645}.
     */
    private static final IntVector K4_1645 = IntVector.broadcast(I128, 1645);

    /**
     * Four ints equal to {@code 1697}.
     */
    private static final IntVector K4_1697 = IntVector.broadcast(I128, 1697);

    /**
     * Four ints equal to {@code 1703}.
     */
    private static final IntVector K4_1703 = IntVector.broadcast(I128, 1703);

    /**
     * Four ints equal to {@code 1751}.
     */
    private static final IntVector K4_1751 = IntVector.broadcast(I128, 1751);

    /**
     * Four ints equal to {@code 1931}.
     */
    private static final IntVector K4_1931 = IntVector.broadcast(I128, 1931);

    /**
     * Four ints equal to {@code 2048}.
     */
    private static final IntVector K4_2048 = IntVector.broadcast(I128, 2048);

    /**
     * Four ints equal to {@code 2106}.
     */
    private static final IntVector K4_2106 = IntVector.broadcast(I128, 2106);

    /**
     * Four ints equal to {@code 2276}.
     */
    private static final IntVector K4_2276 = IntVector.broadcast(I128, 2276);

    /**
     * Four ints equal to {@code 2751}.
     */
    private static final IntVector K4_2751 = IntVector.broadcast(I128, 2751);

    /**
     * Eight ints equal to {@code -1614}.
     */
    private static final IntVector K8_M1614 = IntVector.broadcast(I256, -1614);

    /**
     * Eight ints equal to {@code -1061}.
     */
    private static final IntVector K8_M1061 = IntVector.broadcast(I256, -1061);

    /**
     * Eight ints equal to {@code -752}.
     */
    private static final IntVector K8_M752 = IntVector.broadcast(I256, -752);

    /**
     * Eight ints equal to {@code -690}.
     */
    private static final IntVector K8_M690 = IntVector.broadcast(I256, -690);

    /**
     * Eight ints equal to {@code -583}.
     */
    private static final IntVector K8_M583 = IntVector.broadcast(I256, -583);

    /**
     * Eight ints equal to {@code -484}.
     */
    private static final IntVector K8_M484 = IntVector.broadcast(I256, -484);

    /**
     * Eight ints equal to {@code -393}.
     */
    private static final IntVector K8_M393 = IntVector.broadcast(I256, -393);

    /**
     * Eight ints equal to {@code -312}.
     */
    private static final IntVector K8_M312 = IntVector.broadcast(I256, -312);

    /**
     * Eight ints equal to {@code -293}.
     */
    private static final IntVector K8_M293 = IntVector.broadcast(I256, -293);

    /**
     * Eight ints equal to {@code -239}.
     */
    private static final IntVector K8_M239 = IntVector.broadcast(I256, -239);

    /**
     * Eight ints equal to {@code -176}.
     */
    private static final IntVector K8_M176 = IntVector.broadcast(I256, -176);

    /**
     * Eight ints equal to {@code -123}.
     */
    private static final IntVector K8_M123 = IntVector.broadcast(I256, -123);

    /**
     * Eight ints equal to {@code -79}.
     */
    private static final IntVector K8_M79 = IntVector.broadcast(I256, -79);

    /**
     * Eight ints equal to {@code -44}.
     */
    private static final IntVector K8_M44 = IntVector.broadcast(I256, -44);

    /**
     * Eight ints equal to {@code -20}.
     */
    private static final IntVector K8_M20 = IntVector.broadcast(I256, -20);

    /**
     * Eight ints equal to {@code -5}.
     */
    private static final IntVector K8_M5 = IntVector.broadcast(I256, -5);

    /**
     * Eight ints equal to {@code 8}.
     */
    private static final IntVector K8_8 = IntVector.broadcast(I256, 8);

    /**
     * Eight ints equal to {@code 128}.
     */
    private static final IntVector K8_128 = IntVector.broadcast(I256, 128);

    /**
     * Eight ints equal to {@code 181}.
     */
    private static final IntVector K8_181 = IntVector.broadcast(I256, 181);

    /**
     * Eight ints equal to {@code 201}.
     */
    private static final IntVector K8_201 = IntVector.broadcast(I256, 201);

    /**
     * Eight ints equal to {@code 209}.
     */
    private static final IntVector K8_209 = IntVector.broadcast(I256, 209);

    /**
     * Eight ints equal to {@code 401}.
     */
    private static final IntVector K8_401 = IntVector.broadcast(I256, 401);

    /**
     * Eight ints equal to {@code 601}.
     */
    private static final IntVector K8_601 = IntVector.broadcast(I256, 601);

    /**
     * Eight ints equal to {@code 799}.
     */
    private static final IntVector K8_799 = IntVector.broadcast(I256, 799);

    /**
     * Eight ints equal to {@code 995}.
     */
    private static final IntVector K8_995 = IntVector.broadcast(I256, 995);

    /**
     * Eight ints equal to {@code 1024}.
     */
    private static final IntVector K8_1024 = IntVector.broadcast(I256, 1024);

    /**
     * Eight ints equal to {@code 1138}.
     */
    private static final IntVector K8_1138 = IntVector.broadcast(I256, 1138);

    /**
     * Eight ints equal to {@code 1189}.
     */
    private static final IntVector K8_1189 = IntVector.broadcast(I256, 1189);

    /**
     * Eight ints equal to {@code 1220}.
     */
    private static final IntVector K8_1220 = IntVector.broadcast(I256, 1220);

    /**
     * Eight ints equal to {@code 1299}.
     */
    private static final IntVector K8_1299 = IntVector.broadcast(I256, 1299);

    /**
     * Eight ints equal to {@code 1321}.
     */
    private static final IntVector K8_1321 = IntVector.broadcast(I256, 1321);

    /**
     * Eight ints equal to {@code 1380}.
     */
    private static final IntVector K8_1380 = IntVector.broadcast(I256, 1380);

    /**
     * Eight ints equal to {@code 1567}.
     */
    private static final IntVector K8_1567 = IntVector.broadcast(I256, 1567);

    /**
     * Eight ints equal to {@code 1583}.
     */
    private static final IntVector K8_1583 = IntVector.broadcast(I256, 1583);

    /**
     * Eight ints equal to {@code 1645}.
     */
    private static final IntVector K8_1645 = IntVector.broadcast(I256, 1645);

    /**
     * Eight ints equal to {@code 1697}.
     */
    private static final IntVector K8_1697 = IntVector.broadcast(I256, 1697);

    /**
     * Eight ints equal to {@code 1703}.
     */
    private static final IntVector K8_1703 = IntVector.broadcast(I256, 1703);

    /**
     * Eight ints equal to {@code 1751}.
     */
    private static final IntVector K8_1751 = IntVector.broadcast(I256, 1751);

    /**
     * Eight ints equal to {@code 1931}.
     */
    private static final IntVector K8_1931 = IntVector.broadcast(I256, 1931);

    /**
     * Eight ints equal to {@code 2048}.
     */
    private static final IntVector K8_2048 = IntVector.broadcast(I256, 2048);

    /**
     * Eight ints equal to {@code 2106}.
     */
    private static final IntVector K8_2106 = IntVector.broadcast(I256, 2106);

    /**
     * Eight ints equal to {@code 2276}.
     */
    private static final IntVector K8_2276 = IntVector.broadcast(I256, 2276);

    /**
     * Eight ints equal to {@code 2751}.
     */
    private static final IntVector K8_2751 = IntVector.broadcast(I256, 2751);

    /**
     * The kernels used for the cases this class does not vectorize.
     */
    private final ScalarItxDsp scalar;

    /**
     * Constructs the kernels.
     */
    private VectorItxDsp() {
        this.scalar = new ScalarItxDsp();
    }

    /**
     * Creates the kernels.
     *
     * @return the vector kernels
     */
    static ItxDsp create() {
        return new VectorItxDsp();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the species whose vectors are asked for
     * @param <E>     the element type
     * @param <V>     the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void itxfmAdd(int txSize, int txType, short[] dst, int dstOff, int stride, int[] coef, int coefOff,
                         int eob, int[] scratch, int bitdepthMax) {
        if (txType == WHT_WHT || !isSupported(txSize, txType)) {
            scalar.itxfmAdd(txSize, txType, dst, dstOff, stride, coef, coefOff, eob, scratch, bitdepthMax);
            return;
        }
        int lw = ScalarItxDsp.LOG2_W[txSize];
        int lh = ScalarItxDsp.LOG2_H[txSize];
        int type = ScalarItxDsp.SLOT_TYPE[txType];
        int first = ScalarItxDsp.FIRST_1D[type];
        int second = ScalarItxDsp.SECOND_1D[type];
        if (type == ScalarItxDsp.DCT_DCT && eob < 1) {
            scalar.itxfmAdd(txSize, txType, dst, dstOff, stride, coef, coefOff, eob, scratch, bitdepthMax);
            return;
        }
        if (!vectorized(lw, first) || !vectorized(lh, second)) {
            hybridAdd(txSize, type, dst, dstOff, stride, coef, coefOff, eob, scratch, bitdepthMax);
            return;
        }
        var w = 4 << lw;
        var h = 4 << lh;
        int shift = ScalarItxDsp.SHIFT[txSize];
        var rnd = (1 << shift) >> 1;
        int rowClipMin;
        int colClipMin;
        if (bitdepthMax == 255) {
            rowClipMin = Short.MIN_VALUE;
            colClipMin = Short.MIN_VALUE;
        } else {
            rowClipMin = ~bitdepthMax << 7;
            colClipMin = ~bitdepthMax << 5;
        }
        int lastNonzeroCol;
        if (second == ScalarItxDsp.IDENTITY && first != ScalarItxDsp.IDENTITY) {
            lastNonzeroCol = Math.min(h - 1, eob);
        } else if (first == ScalarItxDsp.IDENTITY && second != ScalarItxDsp.IDENTITY) {
            lastNonzeroCol = eob >> (lw + 2);
        } else {
            lastNonzeroCol = ScalarItxDsp.LAST_NONZERO_COL[txSize][eob];
        }
        var rowLanes = h >= 8 ? 8 : 4;
        var rect2 = w * 2 == h || h * 2 == w;
        for (var y0 = 0; y0 <= lastNonzeroCol; y0 += rowLanes) {
            var o = coefOff + y0;
            if (rect2) {
                if (rowLanes == 8) {
                    scaleRect2(coef, o, h, w);
                } else {
                    scaleRect2Narrow(coef, o, h, w);
                }
            }
            transform(lw, first, rowLanes, coef, o, h, rowClipMin, ~rowClipMin);
        }
        var colClipMax = ~colClipMin;
        var rows = lastNonzeroCol + 1;
        for (var y = 0; y < rows; y++) {
            var row = y * w;
            for (int x = 0, c = coefOff + y; x < w; x++, c += h) {
                scratch[row + x] = Math.clamp((coef[c] + rnd) >> shift, colClipMin, colClipMax);
            }
        }
        Arrays.fill(scratch, rows * w, h * w, 0);
        Arrays.fill(coef, coefOff, coefOff + w * h, 0);
        var colLanes = w >= 8 ? 8 : 4;
        for (var x0 = 0; x0 < w; x0 += colLanes) {
            transform(lh, second, colLanes, scratch, x0, w, colClipMin, colClipMax);
        }
        if (colLanes == 8) {
            add(dst, dstOff, stride, scratch, w, h, bitdepthMax);
        } else {
            addNarrow(dst, dstOff, stride, scratch, h, bitdepthMax);
        }
    }

    /**
     * Inverse transforms a block that has a one-dimensional transform without a vector kernel (a
     * 64-point DCT) and adds it to the destination: the one-dimensional transforms run
     * through {@link ScalarItxDsp}, the rounding between the passes and the final addition through
     * the vector kernels.
     *
     * @param txSize      the rectangular transform size
     * @param type        the transform type after the slot mapping
     * @param dst         the destination plane
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the destination row stride
     * @param coef        the coefficient buffer, reset to zero where read
     * @param coefOff     the index of the block's first coefficient
     * @param eob         the scan position of the last coded coefficient
     * @param tmp         the intermediate buffer
     * @param bitdepthMax the largest sample value
     */
    private static void hybridAdd(int txSize, int type, short[] dst, int dstOff, int stride, int[] coef,
                                  int coefOff, int eob, int[] tmp, int bitdepthMax) {
        int lw = ScalarItxDsp.LOG2_W[txSize];
        int lh = ScalarItxDsp.LOG2_H[txSize];
        var w = 4 << lw;
        var h = 4 << lh;
        int shift = ScalarItxDsp.SHIFT[txSize];
        var rect2 = w * 2 == h || h * 2 == w;
        var rnd = (1 << shift) >> 1;
        int first = ScalarItxDsp.FIRST_1D[type];
        int second = ScalarItxDsp.SECOND_1D[type];
        var sh = Math.min(h, 32);
        var sw = Math.min(w, 32);
        int rowClipMin;
        int colClipMin;
        if (bitdepthMax == 255) {
            rowClipMin = Short.MIN_VALUE;
            colClipMin = Short.MIN_VALUE;
        } else {
            rowClipMin = ~bitdepthMax << 7;
            colClipMin = ~bitdepthMax << 5;
        }
        var rowClipMax = ~rowClipMin;
        var colClipMax = ~colClipMin;
        int lastNonzeroCol;
        if (second == ScalarItxDsp.IDENTITY && first != ScalarItxDsp.IDENTITY) {
            lastNonzeroCol = Math.min(sh - 1, eob);
        } else if (first == ScalarItxDsp.IDENTITY && second != ScalarItxDsp.IDENTITY) {
            lastNonzeroCol = eob >> (lw + 2);
        } else {
            lastNonzeroCol = ScalarItxDsp.LAST_NONZERO_COL[txSize][eob];
        }
        var row = 0;
        for (var y = 0; y <= lastNonzeroCol; y++, row += w) {
            var source = coefOff + y;
            if (rect2) {
                for (var x = 0; x < sw; x++) {
                    tmp[row + x] = (coef[source + x * sh] * 181 + 128) >> 8;
                }
            } else {
                for (var x = 0; x < sw; x++) {
                    tmp[row + x] = coef[source + x * sh];
                }
            }
            ScalarItxDsp.transform1d(lw, first, tmp, row, 1, rowClipMin, rowClipMax);
        }
        if (lastNonzeroCol + 1 < sh) {
            Arrays.fill(tmp, row, row + (sh - lastNonzeroCol - 1) * w, 0);
        }
        Arrays.fill(coef, coefOff, coefOff + sw * sh, 0);
        roundClip(tmp, w * sh, rnd, shift, colClipMin, colClipMax);
        for (var x = 0; x < w; x++) {
            ScalarItxDsp.transform1d(lh, second, tmp, x, w, colClipMin, colClipMax);
        }
        add(dst, dstOff, stride, tmp, w, h, bitdepthMax);
    }

    /**
     * Rounds, shifts and clamps a run of intermediate values in place, 8 at a time.
     *
     * @param tmp   the values
     * @param count the number of values, a multiple of 8
     * @param rnd   the rounding constant
     * @param shift the right shift applied after rounding
     * @param min   the lowest value
     * @param max   the highest value
     */
    private static void roundClip(int[] tmp, int count, int rnd, int shift, int min, int max) {
        var round = IntVector.broadcast(I256, rnd);
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        for (var i = 0; i < count; i += 8) {
            var v = INT256.cast(IntVector.fromArray(I256, tmp, i));
            INT256.cast(INT256.cast(v.lanewise(VectorOperators.ADD, INT256.cast(round)))
                            .lanewise(VectorOperators.ASHR, shift)
                            .lanewise(VectorOperators.MAX, INT256.cast(low))
                            .lanewise(VectorOperators.MIN, INT256.cast(high)))
                    .intoArray(tmp, i);
        }
    }

    /**
     * Returns whether a one-dimensional transform has a vector kernel.
     *
     * @param log2Size the base-two logarithm of the length in units of four samples
     * @param type     the one-dimensional transform type
     * @return {@code true} for transforms of at most 32 points
     */
    private static boolean vectorized(int log2Size, int type) {
        return log2Size <= 3;
    }

    /**
     * Runs one-dimensional transforms of a pass, one per lane.
     *
     * @param log2Size the base-two logarithm of the length in units of four samples
     * @param type     the one-dimensional transform type
     * @param lanes    the number of transforms, 8 or 4
     * @param c        the values, transformed in place
     * @param o        the index of the first value of the first transform
     * @param s        the distance between consecutive values of a transform
     * @param min      the lowest intermediate value
     * @param max      the highest intermediate value
     */
    private static void transform(int log2Size, int type, int lanes, int[] c, int o, int s, int min, int max) {
        var n = 4 << log2Size;
        if (lanes == 8) {
            switch (log2Size * 4 + type) {
                case 0 -> vdct48(c, o, s, min, max);
                case 1 -> vadst48(c, o, s, min, max, c, o, s);
                case 2 -> videntity48(c, o, s);
                case 3 -> vadst48(c, o, s, min, max, c, o + (n - 1) * s, -s);
                case 4 -> vdct88(c, o, s, min, max);
                case 5 -> vadst88(c, o, s, min, max, c, o, s);
                case 6 -> videntity88(c, o, s);
                case 7 -> vadst88(c, o, s, min, max, c, o + (n - 1) * s, -s);
                case 8 -> vdct168(c, o, s, min, max);
                case 9 -> vadst168(c, o, s, min, max, c, o, s);
                case 10 -> videntity168(c, o, s);
                case 11 -> vadst168(c, o, s, min, max, c, o + (n - 1) * s, -s);
                case 12 -> vdct328(c, o, s, min, max);
                default -> videntity328(c, o, s);
            }
        } else {
            switch (log2Size * 4 + type) {
                case 0 -> vdct44(c, o, s, min, max);
                case 1 -> vadst44(c, o, s, min, max, c, o, s);
                case 2 -> videntity44(c, o, s);
                case 3 -> vadst44(c, o, s, min, max, c, o + (n - 1) * s, -s);
                case 4 -> vdct84(c, o, s, min, max);
                case 5 -> vadst84(c, o, s, min, max, c, o, s);
                case 6 -> videntity84(c, o, s);
                case 7 -> vadst84(c, o, s, min, max, c, o + (n - 1) * s, -s);
                case 8 -> vdct164(c, o, s, min, max);
                case 9 -> vadst164(c, o, s, min, max, c, o, s);
                case 10 -> videntity164(c, o, s);
                case 11 -> vadst164(c, o, s, min, max, c, o + (n - 1) * s, -s);
                default -> videntity324(c, o, s);
            }
        }
    }

    /**
     * Scales the inputs of 8 rows of a block with a 1:2 or 2:1 aspect ratio by 1/sqrt(2).
     *
     * @param coef the coefficients, column-major
     * @param o    the index of the first row's first coefficient
     * @param h    the column height, the distance between consecutive elements of a row
     * @param w    the row length
     */
    private static void scaleRect2(int[] coef, int o, int h, int w) {
        for (var x = 0; x < w; x++) {
            var v = INT256.cast(IntVector.fromArray(I256, coef, o + x * h));
            INT256.cast(v.lanewise(VectorOperators.MUL, K8_181).lanewise(VectorOperators.ADD, K8_128))
                    .lanewise(VectorOperators.ASHR, 8).intoArray(coef, o + x * h);
        }
    }

    /**
     * Scales the inputs of 4 rows of a block with a 1:2 or 2:1 aspect ratio by 1/sqrt(2).
     *
     * @param coef the coefficients, column-major
     * @param o    the index of the first row's first coefficient
     * @param h    the column height, the distance between consecutive elements of a row
     * @param w    the row length
     */
    private static void scaleRect2Narrow(int[] coef, int o, int h, int w) {
        for (var x = 0; x < w; x++) {
            var v = INT128.cast(IntVector.fromArray(I128, coef, o + x * h));
            INT128.cast(v.lanewise(VectorOperators.MUL, K4_181).lanewise(VectorOperators.ADD, K4_128))
                    .lanewise(VectorOperators.ASHR, 8).intoArray(coef, o + x * h);
        }
    }

    /**
     * Adds the rounded result of a block at least 8 samples wide to the destination.
     *
     * @param dst         the destination plane
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the destination row stride
     * @param tmp         the transformed block, row-major
     * @param w           the block width, a multiple of 8
     * @param h           the block height
     * @param bitdepthMax the largest sample value
     */
    private static void add(short[] dst, int dstOff, int stride, int[] tmp, int w, int h, int bitdepthMax) {
        var top = IntVector.broadcast(I256, bitdepthMax);
        var zero = IntVector.zero(I256);
        for (var y = 0; y < h; y++) {
            var line = dstOff + y * stride;
            var row = y * w;
            for (var x = 0; x < w; x += 8) {
                var d = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, dst, line + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var r = INT256.cast(INT256.cast(IntVector.fromArray(I256, tmp, row + x))
                        .lanewise(VectorOperators.ADD, K8_8))
                        .lanewise(VectorOperators.ASHR, 4);
                var value = d.lanewise(VectorOperators.ADD, r).lanewise(VectorOperators.MAX, INT256.cast(zero))
                        .lanewise(VectorOperators.MIN, INT256.cast(top));
                SHORT128.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, line + x);
            }
        }
    }

    /**
     * Adds the rounded result of a block 4 samples wide to the destination.
     *
     * @param dst         the destination plane
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the destination row stride
     * @param tmp         the transformed block, row-major
     * @param h           the block height
     * @param bitdepthMax the largest sample value
     */
    private static void addNarrow(short[] dst, int dstOff, int stride, int[] tmp, int h, int bitdepthMax) {
        var top = IntVector.broadcast(I128, bitdepthMax);
        var zero = IntVector.zero(I128);
        for (var y = 0; y < h; y++) {
            var line = dstOff + y * stride;
            var d = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, dst, line))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var r = INT128.cast(INT128.cast(IntVector.fromArray(I128, tmp, 4 * y)).lanewise(VectorOperators.ADD, K4_8))
                    .lanewise(VectorOperators.ASHR, 4);
            var value = d.lanewise(VectorOperators.ADD, r).lanewise(VectorOperators.MAX, INT128.cast(zero))
                    .lanewise(VectorOperators.MIN, INT128.cast(top));
            SHORT64.cast(value.convertShape(VectorOperators.I2S, S64, 0)).intoArray(dst, line);
        }
    }

    /**
     * Runs {@code dct4} of {@link ScalarItxDsp} on 8 transforms at once, one per lane: element
     * {@code k} of lane {@code j} is at index {@code o + k * s + j} (the inputs and outputs of an ADST
     * at their own offsets and strides).
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct48(int[] c, int o, int s, int min, int max) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        var in0 = INT256.cast(IntVector.fromArray(I256, c, o));
        var in1 = INT256.cast(IntVector.fromArray(I256, c, o + s));
        var in2 = INT256.cast(IntVector.fromArray(I256, c, o + 2 * s));
        var in3 = INT256.cast(IntVector.fromArray(I256, c, o + 3 * s));
        var t0 = in0.lanewise(VectorOperators.ADD, in2).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        var t1 = in0.lanewise(VectorOperators.SUB, in2).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        var t2 = in1.lanewise(VectorOperators.MUL, K8_1567)
                .lanewise(VectorOperators.SUB, in3.lanewise(VectorOperators.MUL, K8_M312))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in3);
        var t3 = in1.lanewise(VectorOperators.MUL, K8_M312)
                .lanewise(VectorOperators.ADD, in3.lanewise(VectorOperators.MUL, K8_1567))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in1);
        INT256.cast(t0.lanewise(VectorOperators.ADD, t3).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o);
        INT256.cast(t1.lanewise(VectorOperators.ADD, t2).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + s);
        INT256.cast(t1.lanewise(VectorOperators.SUB, t2).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 2 * s);
        INT256.cast(t0.lanewise(VectorOperators.SUB, t3).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 3 * s);
    }

    /**
     * Runs {@code dct8} of {@link ScalarItxDsp} on 8 transforms at once, one per lane: element
     * {@code k} of lane {@code j} is at index {@code o + k * s + j} (the inputs and outputs of an ADST
     * at their own offsets and strides).
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct88(int[] c, int o, int s, int min, int max) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        vdct48(c, o, s << 1, min, max);
        var in1 = INT256.cast(IntVector.fromArray(I256, c, o + s));
        var in3 = INT256.cast(IntVector.fromArray(I256, c, o + 3 * s));
        var in5 = INT256.cast(IntVector.fromArray(I256, c, o + 5 * s));
        var in7 = INT256.cast(IntVector.fromArray(I256, c, o + 7 * s));
        var t4a = in1.lanewise(VectorOperators.MUL, K8_799)
                .lanewise(VectorOperators.SUB, in7.lanewise(VectorOperators.MUL, K8_M79))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in7);
        var t5a = in5.lanewise(VectorOperators.MUL, K8_1703)
                .lanewise(VectorOperators.SUB, in3.lanewise(VectorOperators.MUL, K8_1138))
                .lanewise(VectorOperators.ADD, K8_1024).lanewise(VectorOperators.ASHR, 11);
        var t6a = in5.lanewise(VectorOperators.MUL, K8_1138)
                .lanewise(VectorOperators.ADD, in3.lanewise(VectorOperators.MUL, K8_1703))
                .lanewise(VectorOperators.ADD, K8_1024).lanewise(VectorOperators.ASHR, 11);
        var t7a = in1.lanewise(VectorOperators.MUL, K8_M79)
                .lanewise(VectorOperators.ADD, in7.lanewise(VectorOperators.MUL, K8_799))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in1);
        var t4 = t4a.lanewise(VectorOperators.ADD, t5a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t5a = t4a.lanewise(VectorOperators.SUB, t5a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t7 = t7a.lanewise(VectorOperators.ADD, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t6a = t7a.lanewise(VectorOperators.SUB, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t5 = t6a.lanewise(VectorOperators.SUB, t5a).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        var t6 = t6a.lanewise(VectorOperators.ADD, t5a).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        var t0 = INT256.cast(IntVector.fromArray(I256, c, o));
        var t1 = INT256.cast(IntVector.fromArray(I256, c, o + 2 * s));
        var t2 = INT256.cast(IntVector.fromArray(I256, c, o + 4 * s));
        var t3 = INT256.cast(IntVector.fromArray(I256, c, o + 6 * s));
        INT256.cast(t0.lanewise(VectorOperators.ADD, t7).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o);
        INT256.cast(t1.lanewise(VectorOperators.ADD, t6).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + s);
        INT256.cast(t2.lanewise(VectorOperators.ADD, t5).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 2 * s);
        INT256.cast(t3.lanewise(VectorOperators.ADD, t4).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 3 * s);
        INT256.cast(t3.lanewise(VectorOperators.SUB, t4).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 4 * s);
        INT256.cast(t2.lanewise(VectorOperators.SUB, t5).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 5 * s);
        INT256.cast(t1.lanewise(VectorOperators.SUB, t6).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 6 * s);
        INT256.cast(t0.lanewise(VectorOperators.SUB, t7).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 7 * s);
    }

    /**
     * Runs {@code dct16} of {@link ScalarItxDsp} on 8 transforms at once, one per lane: element
     * {@code k} of lane {@code j} is at index {@code o + k * s + j} (the inputs and outputs of an ADST
     * at their own offsets and strides).
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct168(int[] c, int o, int s, int min, int max) {
        vdct168p0(c, o, s, min, max);
        vdct168p1(c, o, s, min, max);
    }

    /**
     * Runs part 1 of 2 of {@link #vdct168}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct168p0(int[] c, int o, int s, int min, int max) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        vdct88(c, o, s << 1, min, max);
        var in1 = INT256.cast(IntVector.fromArray(I256, c, o + s));
        var in3 = INT256.cast(IntVector.fromArray(I256, c, o + 3 * s));
        var in5 = INT256.cast(IntVector.fromArray(I256, c, o + 5 * s));
        var in7 = INT256.cast(IntVector.fromArray(I256, c, o + 7 * s));
        var in9 = INT256.cast(IntVector.fromArray(I256, c, o + 9 * s));
        var in11 = INT256.cast(IntVector.fromArray(I256, c, o + 11 * s));
        var in13 = INT256.cast(IntVector.fromArray(I256, c, o + 13 * s));
        var in15 = INT256.cast(IntVector.fromArray(I256, c, o + 15 * s));
        var t8a = in1.lanewise(VectorOperators.MUL, K8_401)
                .lanewise(VectorOperators.SUB, in15.lanewise(VectorOperators.MUL, K8_M20))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in15);
        var t9a = in9.lanewise(VectorOperators.MUL, K8_1583)
                .lanewise(VectorOperators.SUB, in7.lanewise(VectorOperators.MUL, K8_1299))
                .lanewise(VectorOperators.ADD, K8_1024).lanewise(VectorOperators.ASHR, 11);
        var t10a = in5.lanewise(VectorOperators.MUL, K8_1931)
                .lanewise(VectorOperators.SUB, in11.lanewise(VectorOperators.MUL, K8_M484))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in11);
        var t11a = in13.lanewise(VectorOperators.MUL, K8_M176)
                .lanewise(VectorOperators.SUB, in3.lanewise(VectorOperators.MUL, K8_1189))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in13);
        var t12a = in13.lanewise(VectorOperators.MUL, K8_1189)
                .lanewise(VectorOperators.ADD, in3.lanewise(VectorOperators.MUL, K8_M176))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in3);
        var t13a = in5.lanewise(VectorOperators.MUL, K8_M484)
                .lanewise(VectorOperators.ADD, in11.lanewise(VectorOperators.MUL, K8_1931))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in5);
        var t14a = in9.lanewise(VectorOperators.MUL, K8_1299)
                .lanewise(VectorOperators.ADD, in7.lanewise(VectorOperators.MUL, K8_1583))
                .lanewise(VectorOperators.ADD, K8_1024).lanewise(VectorOperators.ASHR, 11);
        var t15a = in1.lanewise(VectorOperators.MUL, K8_M20)
                .lanewise(VectorOperators.ADD, in15.lanewise(VectorOperators.MUL, K8_401))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in1);
        var t8 = t8a.lanewise(VectorOperators.ADD, t9a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t9 = t8a.lanewise(VectorOperators.SUB, t9a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t10 = t11a.lanewise(VectorOperators.SUB, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t11 = t11a.lanewise(VectorOperators.ADD, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t12 = t12a.lanewise(VectorOperators.ADD, t13a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t13 = t12a.lanewise(VectorOperators.SUB, t13a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t14 = t15a.lanewise(VectorOperators.SUB, t14a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t15 = t15a.lanewise(VectorOperators.ADD, t14a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t9a = t14.lanewise(VectorOperators.MUL, K8_1567)
                .lanewise(VectorOperators.SUB, t9.lanewise(VectorOperators.MUL, K8_M312))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t9);
        t14a = t14.lanewise(VectorOperators.MUL, K8_M312)
                .lanewise(VectorOperators.ADD, t9.lanewise(VectorOperators.MUL, K8_1567))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t14);
        t10a = t13.lanewise(VectorOperators.MUL, K8_M312)
                .lanewise(VectorOperators.ADD, t10.lanewise(VectorOperators.MUL, K8_1567)).lanewise(VectorOperators.NEG)
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t13);
        t13a = t13.lanewise(VectorOperators.MUL, K8_1567)
                .lanewise(VectorOperators.SUB, t10.lanewise(VectorOperators.MUL, K8_M312))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t10);
        t8a = t8.lanewise(VectorOperators.ADD, t11).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t9 = t9a.lanewise(VectorOperators.ADD, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t10 = t9a.lanewise(VectorOperators.SUB, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t11a = t8.lanewise(VectorOperators.SUB, t11).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t12a = t15.lanewise(VectorOperators.SUB, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t13 = t14a.lanewise(VectorOperators.SUB, t13a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t14 = t14a.lanewise(VectorOperators.ADD, t13a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t15a = t15.lanewise(VectorOperators.ADD, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t10a = t13.lanewise(VectorOperators.SUB, t10).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        t13a = t13.lanewise(VectorOperators.ADD, t10).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        t11 = t12a.lanewise(VectorOperators.SUB, t11a).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        t12 = t12a.lanewise(VectorOperators.ADD, t11a).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        INT256.cast(t8a).intoArray(c, o + 1 * s);
        INT256.cast(t10a).intoArray(c, o + 3 * s);
        INT256.cast(t13a).intoArray(c, o + 5 * s);
        INT256.cast(t15a).intoArray(c, o + 7 * s);
        INT256.cast(t9).intoArray(c, o + 9 * s);
        INT256.cast(t11).intoArray(c, o + 11 * s);
        INT256.cast(t12).intoArray(c, o + 13 * s);
        INT256.cast(t14).intoArray(c, o + 15 * s);
    }

    /**
     * Runs part 2 of 2 of {@link #vdct168}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct168p1(int[] c, int o, int s, int min, int max) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        var t8a = INT256.cast(IntVector.fromArray(I256, c, o + 1 * s));
        var t10a = INT256.cast(IntVector.fromArray(I256, c, o + 3 * s));
        var t13a = INT256.cast(IntVector.fromArray(I256, c, o + 5 * s));
        var t15a = INT256.cast(IntVector.fromArray(I256, c, o + 7 * s));
        var t9 = INT256.cast(IntVector.fromArray(I256, c, o + 9 * s));
        var t11 = INT256.cast(IntVector.fromArray(I256, c, o + 11 * s));
        var t12 = INT256.cast(IntVector.fromArray(I256, c, o + 13 * s));
        var t14 = INT256.cast(IntVector.fromArray(I256, c, o + 15 * s));
        var t0 = INT256.cast(IntVector.fromArray(I256, c, o));
        var t1 = INT256.cast(IntVector.fromArray(I256, c, o + 2 * s));
        var t2 = INT256.cast(IntVector.fromArray(I256, c, o + 4 * s));
        var t3 = INT256.cast(IntVector.fromArray(I256, c, o + 6 * s));
        var t4 = INT256.cast(IntVector.fromArray(I256, c, o + 8 * s));
        var t5 = INT256.cast(IntVector.fromArray(I256, c, o + 10 * s));
        var t6 = INT256.cast(IntVector.fromArray(I256, c, o + 12 * s));
        var t7 = INT256.cast(IntVector.fromArray(I256, c, o + 14 * s));
        INT256.cast(t0.lanewise(VectorOperators.ADD, t15a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o);
        INT256.cast(t1.lanewise(VectorOperators.ADD, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + s);
        INT256.cast(t2.lanewise(VectorOperators.ADD, t13a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 2 * s);
        INT256.cast(t3.lanewise(VectorOperators.ADD, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 3 * s);
        INT256.cast(t4.lanewise(VectorOperators.ADD, t11).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 4 * s);
        INT256.cast(t5.lanewise(VectorOperators.ADD, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 5 * s);
        INT256.cast(t6.lanewise(VectorOperators.ADD, t9).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 6 * s);
        INT256.cast(t7.lanewise(VectorOperators.ADD, t8a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 7 * s);
        INT256.cast(t7.lanewise(VectorOperators.SUB, t8a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 8 * s);
        INT256.cast(t6.lanewise(VectorOperators.SUB, t9).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 9 * s);
        INT256.cast(t5.lanewise(VectorOperators.SUB, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 10 * s);
        INT256.cast(t4.lanewise(VectorOperators.SUB, t11).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 11 * s);
        INT256.cast(t3.lanewise(VectorOperators.SUB, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 12 * s);
        INT256.cast(t2.lanewise(VectorOperators.SUB, t13a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 13 * s);
        INT256.cast(t1.lanewise(VectorOperators.SUB, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 14 * s);
        INT256.cast(t0.lanewise(VectorOperators.SUB, t15a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 15 * s);
    }

    /**
     * Runs {@code dct32} of {@link ScalarItxDsp} on 8 transforms at once, one per lane: element
     * {@code k} of lane {@code j} is at index {@code o + k * s + j} (the inputs and outputs of an ADST
     * at their own offsets and strides).
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct328(int[] c, int o, int s, int min, int max) {
        vdct328p0(c, o, s, min, max);
        vdct328p1(c, o, s, min, max);
        vdct328p2(c, o, s, min, max);
        vdct328p3(c, o, s, min, max);
        vdct328p4(c, o, s, min, max);
        vdct328p5(c, o, s, min, max);
    }

    /**
     * Runs part 1 of 6 of {@link #vdct328}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct328p0(int[] c, int o, int s, int min, int max) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        vdct168(c, o, s << 1, min, max);
        var in1 = INT256.cast(IntVector.fromArray(I256, c, o + s));
        var in3 = INT256.cast(IntVector.fromArray(I256, c, o + 3 * s));
        var in5 = INT256.cast(IntVector.fromArray(I256, c, o + 5 * s));
        var in7 = INT256.cast(IntVector.fromArray(I256, c, o + 7 * s));
        var in9 = INT256.cast(IntVector.fromArray(I256, c, o + 9 * s));
        var in11 = INT256.cast(IntVector.fromArray(I256, c, o + 11 * s));
        var in13 = INT256.cast(IntVector.fromArray(I256, c, o + 13 * s));
        var in15 = INT256.cast(IntVector.fromArray(I256, c, o + 15 * s));
        var in17 = INT256.cast(IntVector.fromArray(I256, c, o + 17 * s));
        var in19 = INT256.cast(IntVector.fromArray(I256, c, o + 19 * s));
        var in21 = INT256.cast(IntVector.fromArray(I256, c, o + 21 * s));
        var in23 = INT256.cast(IntVector.fromArray(I256, c, o + 23 * s));
        var in25 = INT256.cast(IntVector.fromArray(I256, c, o + 25 * s));
        var in27 = INT256.cast(IntVector.fromArray(I256, c, o + 27 * s));
        var in29 = INT256.cast(IntVector.fromArray(I256, c, o + 29 * s));
        var in31 = INT256.cast(IntVector.fromArray(I256, c, o + 31 * s));
        var t16a = in1.lanewise(VectorOperators.MUL, K8_201)
                .lanewise(VectorOperators.SUB, in31.lanewise(VectorOperators.MUL, K8_M5))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in31);
        var t17a = in17.lanewise(VectorOperators.MUL, K8_M1061)
                .lanewise(VectorOperators.SUB, in15.lanewise(VectorOperators.MUL, K8_2751))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in17);
        var t18a = in9.lanewise(VectorOperators.MUL, K8_1751)
                .lanewise(VectorOperators.SUB, in23.lanewise(VectorOperators.MUL, K8_M393))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in23);
        var t19a = in25.lanewise(VectorOperators.MUL, K8_M239)
                .lanewise(VectorOperators.SUB, in7.lanewise(VectorOperators.MUL, K8_1380))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in25);
        var t20a = in5.lanewise(VectorOperators.MUL, K8_995)
                .lanewise(VectorOperators.SUB, in27.lanewise(VectorOperators.MUL, K8_M123))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in27);
        var t21a = in21.lanewise(VectorOperators.MUL, K8_M583)
                .lanewise(VectorOperators.SUB, in11.lanewise(VectorOperators.MUL, K8_2106))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in21);
        var t22a = in13.lanewise(VectorOperators.MUL, K8_1220)
                .lanewise(VectorOperators.SUB, in19.lanewise(VectorOperators.MUL, K8_1645))
                .lanewise(VectorOperators.ADD, K8_1024).lanewise(VectorOperators.ASHR, 11);
        var t23a = in29.lanewise(VectorOperators.MUL, K8_M44)
                .lanewise(VectorOperators.SUB, in3.lanewise(VectorOperators.MUL, K8_601))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in29);
        var t24a = in29.lanewise(VectorOperators.MUL, K8_601)
                .lanewise(VectorOperators.ADD, in3.lanewise(VectorOperators.MUL, K8_M44))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in3);
        var t25a = in13.lanewise(VectorOperators.MUL, K8_1645)
                .lanewise(VectorOperators.ADD, in19.lanewise(VectorOperators.MUL, K8_1220))
                .lanewise(VectorOperators.ADD, K8_1024).lanewise(VectorOperators.ASHR, 11);
        var t26a = in21.lanewise(VectorOperators.MUL, K8_2106)
                .lanewise(VectorOperators.ADD, in11.lanewise(VectorOperators.MUL, K8_M583))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in11);
        var t27a = in5.lanewise(VectorOperators.MUL, K8_M123)
                .lanewise(VectorOperators.ADD, in27.lanewise(VectorOperators.MUL, K8_995))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in5);
        var t28a = in25.lanewise(VectorOperators.MUL, K8_1380)
                .lanewise(VectorOperators.ADD, in7.lanewise(VectorOperators.MUL, K8_M239))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in7);
        var t29a = in9.lanewise(VectorOperators.MUL, K8_M393)
                .lanewise(VectorOperators.ADD, in23.lanewise(VectorOperators.MUL, K8_1751))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in9);
        var t30a = in17.lanewise(VectorOperators.MUL, K8_2751)
                .lanewise(VectorOperators.ADD, in15.lanewise(VectorOperators.MUL, K8_M1061))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in15);
        var t31a = in1.lanewise(VectorOperators.MUL, K8_M5)
                .lanewise(VectorOperators.ADD, in31.lanewise(VectorOperators.MUL, K8_201))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in1);
        INT256.cast(t16a).intoArray(c, o + 1 * s);
        INT256.cast(t17a).intoArray(c, o + 3 * s);
        INT256.cast(t18a).intoArray(c, o + 5 * s);
        INT256.cast(t19a).intoArray(c, o + 7 * s);
        INT256.cast(t20a).intoArray(c, o + 9 * s);
        INT256.cast(t21a).intoArray(c, o + 11 * s);
        INT256.cast(t22a).intoArray(c, o + 13 * s);
        INT256.cast(t23a).intoArray(c, o + 15 * s);
        INT256.cast(t24a).intoArray(c, o + 17 * s);
        INT256.cast(t25a).intoArray(c, o + 19 * s);
        INT256.cast(t26a).intoArray(c, o + 21 * s);
        INT256.cast(t27a).intoArray(c, o + 23 * s);
        INT256.cast(t28a).intoArray(c, o + 25 * s);
        INT256.cast(t29a).intoArray(c, o + 27 * s);
        INT256.cast(t30a).intoArray(c, o + 29 * s);
        INT256.cast(t31a).intoArray(c, o + 31 * s);
    }

    /**
     * Runs part 2 of 6 of {@link #vdct328}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct328p1(int[] c, int o, int s, int min, int max) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        var t16a = INT256.cast(IntVector.fromArray(I256, c, o + 1 * s));
        var t17a = INT256.cast(IntVector.fromArray(I256, c, o + 3 * s));
        var t18a = INT256.cast(IntVector.fromArray(I256, c, o + 5 * s));
        var t19a = INT256.cast(IntVector.fromArray(I256, c, o + 7 * s));
        var t20a = INT256.cast(IntVector.fromArray(I256, c, o + 9 * s));
        var t21a = INT256.cast(IntVector.fromArray(I256, c, o + 11 * s));
        var t22a = INT256.cast(IntVector.fromArray(I256, c, o + 13 * s));
        var t23a = INT256.cast(IntVector.fromArray(I256, c, o + 15 * s));
        var t24a = INT256.cast(IntVector.fromArray(I256, c, o + 17 * s));
        var t25a = INT256.cast(IntVector.fromArray(I256, c, o + 19 * s));
        var t26a = INT256.cast(IntVector.fromArray(I256, c, o + 21 * s));
        var t27a = INT256.cast(IntVector.fromArray(I256, c, o + 23 * s));
        var t28a = INT256.cast(IntVector.fromArray(I256, c, o + 25 * s));
        var t29a = INT256.cast(IntVector.fromArray(I256, c, o + 27 * s));
        var t30a = INT256.cast(IntVector.fromArray(I256, c, o + 29 * s));
        var t31a = INT256.cast(IntVector.fromArray(I256, c, o + 31 * s));
        var t16 = t16a.lanewise(VectorOperators.ADD, t17a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t17 = t16a.lanewise(VectorOperators.SUB, t17a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t18 = t19a.lanewise(VectorOperators.SUB, t18a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t19 = t19a.lanewise(VectorOperators.ADD, t18a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t20 = t20a.lanewise(VectorOperators.ADD, t21a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t21 = t20a.lanewise(VectorOperators.SUB, t21a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t22 = t23a.lanewise(VectorOperators.SUB, t22a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t23 = t23a.lanewise(VectorOperators.ADD, t22a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t24 = t24a.lanewise(VectorOperators.ADD, t25a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t25 = t24a.lanewise(VectorOperators.SUB, t25a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t26 = t27a.lanewise(VectorOperators.SUB, t26a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t27 = t27a.lanewise(VectorOperators.ADD, t26a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t28 = t28a.lanewise(VectorOperators.ADD, t29a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t29 = t28a.lanewise(VectorOperators.SUB, t29a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t30 = t31a.lanewise(VectorOperators.SUB, t30a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t31 = t31a.lanewise(VectorOperators.ADD, t30a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t17a = t30.lanewise(VectorOperators.MUL, K8_799)
                .lanewise(VectorOperators.SUB, t17.lanewise(VectorOperators.MUL, K8_M79))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t17);
        t30a = t30.lanewise(VectorOperators.MUL, K8_M79)
                .lanewise(VectorOperators.ADD, t17.lanewise(VectorOperators.MUL, K8_799))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t30);
        t18a = t29.lanewise(VectorOperators.MUL, K8_M79)
                .lanewise(VectorOperators.ADD, t18.lanewise(VectorOperators.MUL, K8_799)).lanewise(VectorOperators.NEG)
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t29);
        t29a = t29.lanewise(VectorOperators.MUL, K8_799)
                .lanewise(VectorOperators.SUB, t18.lanewise(VectorOperators.MUL, K8_M79))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t18);
        t21a = t26.lanewise(VectorOperators.MUL, K8_1703)
                .lanewise(VectorOperators.SUB, t21.lanewise(VectorOperators.MUL, K8_1138))
                .lanewise(VectorOperators.ADD, K8_1024).lanewise(VectorOperators.ASHR, 11);
        t26a = t26.lanewise(VectorOperators.MUL, K8_1138)
                .lanewise(VectorOperators.ADD, t21.lanewise(VectorOperators.MUL, K8_1703))
                .lanewise(VectorOperators.ADD, K8_1024).lanewise(VectorOperators.ASHR, 11);
        t22a = t25.lanewise(VectorOperators.MUL, K8_1138)
                .lanewise(VectorOperators.ADD, t22.lanewise(VectorOperators.MUL, K8_1703)).lanewise(VectorOperators.NEG)
                .lanewise(VectorOperators.ADD, K8_1024).lanewise(VectorOperators.ASHR, 11);
        t25a = t25.lanewise(VectorOperators.MUL, K8_1703)
                .lanewise(VectorOperators.SUB, t22.lanewise(VectorOperators.MUL, K8_1138))
                .lanewise(VectorOperators.ADD, K8_1024).lanewise(VectorOperators.ASHR, 11);
        INT256.cast(t17a).intoArray(c, o + 1 * s);
        INT256.cast(t18a).intoArray(c, o + 3 * s);
        INT256.cast(t21a).intoArray(c, o + 5 * s);
        INT256.cast(t22a).intoArray(c, o + 7 * s);
        INT256.cast(t25a).intoArray(c, o + 9 * s);
        INT256.cast(t26a).intoArray(c, o + 11 * s);
        INT256.cast(t29a).intoArray(c, o + 13 * s);
        INT256.cast(t30a).intoArray(c, o + 15 * s);
        INT256.cast(t16).intoArray(c, o + 17 * s);
        INT256.cast(t19).intoArray(c, o + 19 * s);
        INT256.cast(t20).intoArray(c, o + 21 * s);
        INT256.cast(t23).intoArray(c, o + 23 * s);
        INT256.cast(t24).intoArray(c, o + 25 * s);
        INT256.cast(t27).intoArray(c, o + 27 * s);
        INT256.cast(t28).intoArray(c, o + 29 * s);
        INT256.cast(t31).intoArray(c, o + 31 * s);
    }

    /**
     * Runs part 3 of 6 of {@link #vdct328}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct328p2(int[] c, int o, int s, int min, int max) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        var t17a = INT256.cast(IntVector.fromArray(I256, c, o + 1 * s));
        var t18a = INT256.cast(IntVector.fromArray(I256, c, o + 3 * s));
        var t21a = INT256.cast(IntVector.fromArray(I256, c, o + 5 * s));
        var t22a = INT256.cast(IntVector.fromArray(I256, c, o + 7 * s));
        var t25a = INT256.cast(IntVector.fromArray(I256, c, o + 9 * s));
        var t26a = INT256.cast(IntVector.fromArray(I256, c, o + 11 * s));
        var t29a = INT256.cast(IntVector.fromArray(I256, c, o + 13 * s));
        var t30a = INT256.cast(IntVector.fromArray(I256, c, o + 15 * s));
        var t16 = INT256.cast(IntVector.fromArray(I256, c, o + 17 * s));
        var t19 = INT256.cast(IntVector.fromArray(I256, c, o + 19 * s));
        var t20 = INT256.cast(IntVector.fromArray(I256, c, o + 21 * s));
        var t23 = INT256.cast(IntVector.fromArray(I256, c, o + 23 * s));
        var t24 = INT256.cast(IntVector.fromArray(I256, c, o + 25 * s));
        var t27 = INT256.cast(IntVector.fromArray(I256, c, o + 27 * s));
        var t28 = INT256.cast(IntVector.fromArray(I256, c, o + 29 * s));
        var t31 = INT256.cast(IntVector.fromArray(I256, c, o + 31 * s));
        var t16a = t16.lanewise(VectorOperators.ADD, t19).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t17 = t17a.lanewise(VectorOperators.ADD, t18a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t18 = t17a.lanewise(VectorOperators.SUB, t18a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t19a = t16.lanewise(VectorOperators.SUB, t19).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t20a = t23.lanewise(VectorOperators.SUB, t20).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t21 = t22a.lanewise(VectorOperators.SUB, t21a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t22 = t22a.lanewise(VectorOperators.ADD, t21a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t23a = t23.lanewise(VectorOperators.ADD, t20).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t24a = t24.lanewise(VectorOperators.ADD, t27).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t25 = t25a.lanewise(VectorOperators.ADD, t26a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t26 = t25a.lanewise(VectorOperators.SUB, t26a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t27a = t24.lanewise(VectorOperators.SUB, t27).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t28a = t31.lanewise(VectorOperators.SUB, t28).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t29 = t30a.lanewise(VectorOperators.SUB, t29a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t30 = t30a.lanewise(VectorOperators.ADD, t29a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t31a = t31.lanewise(VectorOperators.ADD, t28).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t18a = t29.lanewise(VectorOperators.MUL, K8_1567)
                .lanewise(VectorOperators.SUB, t18.lanewise(VectorOperators.MUL, K8_M312))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t18);
        t29a = t29.lanewise(VectorOperators.MUL, K8_M312)
                .lanewise(VectorOperators.ADD, t18.lanewise(VectorOperators.MUL, K8_1567))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t29);
        t19 = t28a.lanewise(VectorOperators.MUL, K8_1567)
                .lanewise(VectorOperators.SUB, t19a.lanewise(VectorOperators.MUL, K8_M312))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t19a);
        t28 = t28a.lanewise(VectorOperators.MUL, K8_M312)
                .lanewise(VectorOperators.ADD, t19a.lanewise(VectorOperators.MUL, K8_1567))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t28a);
        t20 = t27a.lanewise(VectorOperators.MUL, K8_M312)
                .lanewise(VectorOperators.ADD, t20a.lanewise(VectorOperators.MUL, K8_1567))
                .lanewise(VectorOperators.NEG).lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t27a);
        t27 = t27a.lanewise(VectorOperators.MUL, K8_1567)
                .lanewise(VectorOperators.SUB, t20a.lanewise(VectorOperators.MUL, K8_M312))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t20a);
        t21a = t26.lanewise(VectorOperators.MUL, K8_M312)
                .lanewise(VectorOperators.ADD, t21.lanewise(VectorOperators.MUL, K8_1567)).lanewise(VectorOperators.NEG)
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t26);
        t26a = t26.lanewise(VectorOperators.MUL, K8_1567)
                .lanewise(VectorOperators.SUB, t21.lanewise(VectorOperators.MUL, K8_M312))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t21);
        INT256.cast(t16a).intoArray(c, o + 1 * s);
        INT256.cast(t18a).intoArray(c, o + 3 * s);
        INT256.cast(t21a).intoArray(c, o + 5 * s);
        INT256.cast(t23a).intoArray(c, o + 7 * s);
        INT256.cast(t24a).intoArray(c, o + 9 * s);
        INT256.cast(t26a).intoArray(c, o + 11 * s);
        INT256.cast(t29a).intoArray(c, o + 13 * s);
        INT256.cast(t31a).intoArray(c, o + 15 * s);
        INT256.cast(t17).intoArray(c, o + 17 * s);
        INT256.cast(t19).intoArray(c, o + 19 * s);
        INT256.cast(t20).intoArray(c, o + 21 * s);
        INT256.cast(t22).intoArray(c, o + 23 * s);
        INT256.cast(t25).intoArray(c, o + 25 * s);
        INT256.cast(t27).intoArray(c, o + 27 * s);
        INT256.cast(t28).intoArray(c, o + 29 * s);
        INT256.cast(t30).intoArray(c, o + 31 * s);
    }

    /**
     * Runs part 4 of 6 of {@link #vdct328}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct328p3(int[] c, int o, int s, int min, int max) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        var t16a = INT256.cast(IntVector.fromArray(I256, c, o + 1 * s));
        var t18a = INT256.cast(IntVector.fromArray(I256, c, o + 3 * s));
        var t21a = INT256.cast(IntVector.fromArray(I256, c, o + 5 * s));
        var t23a = INT256.cast(IntVector.fromArray(I256, c, o + 7 * s));
        var t24a = INT256.cast(IntVector.fromArray(I256, c, o + 9 * s));
        var t26a = INT256.cast(IntVector.fromArray(I256, c, o + 11 * s));
        var t29a = INT256.cast(IntVector.fromArray(I256, c, o + 13 * s));
        var t31a = INT256.cast(IntVector.fromArray(I256, c, o + 15 * s));
        var t17 = INT256.cast(IntVector.fromArray(I256, c, o + 17 * s));
        var t19 = INT256.cast(IntVector.fromArray(I256, c, o + 19 * s));
        var t20 = INT256.cast(IntVector.fromArray(I256, c, o + 21 * s));
        var t22 = INT256.cast(IntVector.fromArray(I256, c, o + 23 * s));
        var t25 = INT256.cast(IntVector.fromArray(I256, c, o + 25 * s));
        var t27 = INT256.cast(IntVector.fromArray(I256, c, o + 27 * s));
        var t28 = INT256.cast(IntVector.fromArray(I256, c, o + 29 * s));
        var t30 = INT256.cast(IntVector.fromArray(I256, c, o + 31 * s));
        var t16 = t16a.lanewise(VectorOperators.ADD, t23a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t17a = t17.lanewise(VectorOperators.ADD, t22).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t18 = t18a.lanewise(VectorOperators.ADD, t21a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t19a = t19.lanewise(VectorOperators.ADD, t20).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t20a = t19.lanewise(VectorOperators.SUB, t20).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t21 = t18a.lanewise(VectorOperators.SUB, t21a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t22a = t17.lanewise(VectorOperators.SUB, t22).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t23 = t16a.lanewise(VectorOperators.SUB, t23a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t24 = t31a.lanewise(VectorOperators.SUB, t24a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t25a = t30.lanewise(VectorOperators.SUB, t25).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t26 = t29a.lanewise(VectorOperators.SUB, t26a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t27a = t28.lanewise(VectorOperators.SUB, t27).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t28a = t28.lanewise(VectorOperators.ADD, t27).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t29 = t29a.lanewise(VectorOperators.ADD, t26a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t30a = t30.lanewise(VectorOperators.ADD, t25).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t31 = t31a.lanewise(VectorOperators.ADD, t24a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t20 = t27a.lanewise(VectorOperators.SUB, t20a).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        t27 = t27a.lanewise(VectorOperators.ADD, t20a).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        t21a = t26.lanewise(VectorOperators.SUB, t21).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        t26a = t26.lanewise(VectorOperators.ADD, t21).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        t22 = t25a.lanewise(VectorOperators.SUB, t22a).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        t25 = t25a.lanewise(VectorOperators.ADD, t22a).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        t23a = t24.lanewise(VectorOperators.SUB, t23).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        t24a = t24.lanewise(VectorOperators.ADD, t23).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8);
        INT256.cast(t31).intoArray(c, o + 1 * s);
        INT256.cast(t30a).intoArray(c, o + 15 * s);
        INT256.cast(t29).intoArray(c, o + 23 * s);
        INT256.cast(t28a).intoArray(c, o + 3 * s);
        INT256.cast(t27).intoArray(c, o + 27 * s);
        INT256.cast(t26a).intoArray(c, o + 5 * s);
        INT256.cast(t25).intoArray(c, o + 7 * s);
        INT256.cast(t24a).intoArray(c, o + 9 * s);
        INT256.cast(t23a).intoArray(c, o + 29 * s);
        INT256.cast(t22).intoArray(c, o + 11 * s);
        INT256.cast(t21a).intoArray(c, o + 13 * s);
        INT256.cast(t20).intoArray(c, o + 17 * s);
        INT256.cast(t19a).intoArray(c, o + 19 * s);
        INT256.cast(t18).intoArray(c, o + 21 * s);
        INT256.cast(t17a).intoArray(c, o + 25 * s);
        INT256.cast(t16).intoArray(c, o + 31 * s);
    }

    /**
     * Runs part 5 of 6 of {@link #vdct328}, the output butterflies of a subset of the pairs.
     * The pairs of the later part read only elements the earlier parts did not write.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct328p4(int[] c, int o, int s, int min, int max) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        var t0 = INT256.cast(IntVector.fromArray(I256, c, o + 0 * s));
        var x0 = INT256.cast(IntVector.fromArray(I256, c, o + 1 * s));
        var t1 = INT256.cast(IntVector.fromArray(I256, c, o + 2 * s));
        var x1 = INT256.cast(IntVector.fromArray(I256, c, o + 15 * s));
        var t2 = INT256.cast(IntVector.fromArray(I256, c, o + 4 * s));
        var x2 = INT256.cast(IntVector.fromArray(I256, c, o + 23 * s));
        var t4 = INT256.cast(IntVector.fromArray(I256, c, o + 8 * s));
        var x4 = INT256.cast(IntVector.fromArray(I256, c, o + 27 * s));
        var t8 = INT256.cast(IntVector.fromArray(I256, c, o + 16 * s));
        var x8 = INT256.cast(IntVector.fromArray(I256, c, o + 29 * s));
        var t15 = INT256.cast(IntVector.fromArray(I256, c, o + 30 * s));
        var x15 = INT256.cast(IntVector.fromArray(I256, c, o + 31 * s));
        INT256.cast(t0.lanewise(VectorOperators.ADD, x0).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 0 * s);
        INT256.cast(t0.lanewise(VectorOperators.SUB, x0).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 31 * s);
        INT256.cast(t1.lanewise(VectorOperators.ADD, x1).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 1 * s);
        INT256.cast(t1.lanewise(VectorOperators.SUB, x1).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 30 * s);
        INT256.cast(t2.lanewise(VectorOperators.ADD, x2).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 2 * s);
        INT256.cast(t2.lanewise(VectorOperators.SUB, x2).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 29 * s);
        INT256.cast(t4.lanewise(VectorOperators.ADD, x4).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 4 * s);
        INT256.cast(t4.lanewise(VectorOperators.SUB, x4).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 27 * s);
        INT256.cast(t8.lanewise(VectorOperators.ADD, x8).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 8 * s);
        INT256.cast(t8.lanewise(VectorOperators.SUB, x8).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 23 * s);
        INT256.cast(t15.lanewise(VectorOperators.ADD, x15).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 15 * s);
        INT256.cast(t15.lanewise(VectorOperators.SUB, x15).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 16 * s);
    }

    /**
     * Runs part 6 of 6 of {@link #vdct328}, the output butterflies of a subset of the pairs.
     * The pairs of the later part read only elements the earlier parts did not write.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct328p5(int[] c, int o, int s, int min, int max) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        var t3 = INT256.cast(IntVector.fromArray(I256, c, o + 6 * s));
        var x3 = INT256.cast(IntVector.fromArray(I256, c, o + 3 * s));
        var t5 = INT256.cast(IntVector.fromArray(I256, c, o + 10 * s));
        var x5 = INT256.cast(IntVector.fromArray(I256, c, o + 5 * s));
        var t6 = INT256.cast(IntVector.fromArray(I256, c, o + 12 * s));
        var x6 = INT256.cast(IntVector.fromArray(I256, c, o + 7 * s));
        var t7 = INT256.cast(IntVector.fromArray(I256, c, o + 14 * s));
        var x7 = INT256.cast(IntVector.fromArray(I256, c, o + 9 * s));
        var t9 = INT256.cast(IntVector.fromArray(I256, c, o + 18 * s));
        var x9 = INT256.cast(IntVector.fromArray(I256, c, o + 11 * s));
        var t10 = INT256.cast(IntVector.fromArray(I256, c, o + 20 * s));
        var x10 = INT256.cast(IntVector.fromArray(I256, c, o + 13 * s));
        var t11 = INT256.cast(IntVector.fromArray(I256, c, o + 22 * s));
        var x11 = INT256.cast(IntVector.fromArray(I256, c, o + 17 * s));
        var t12 = INT256.cast(IntVector.fromArray(I256, c, o + 24 * s));
        var x12 = INT256.cast(IntVector.fromArray(I256, c, o + 19 * s));
        var t13 = INT256.cast(IntVector.fromArray(I256, c, o + 26 * s));
        var x13 = INT256.cast(IntVector.fromArray(I256, c, o + 21 * s));
        var t14 = INT256.cast(IntVector.fromArray(I256, c, o + 28 * s));
        var x14 = INT256.cast(IntVector.fromArray(I256, c, o + 25 * s));
        INT256.cast(t3.lanewise(VectorOperators.ADD, x3).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 3 * s);
        INT256.cast(t3.lanewise(VectorOperators.SUB, x3).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 28 * s);
        INT256.cast(t5.lanewise(VectorOperators.ADD, x5).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 5 * s);
        INT256.cast(t5.lanewise(VectorOperators.SUB, x5).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 26 * s);
        INT256.cast(t6.lanewise(VectorOperators.ADD, x6).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 6 * s);
        INT256.cast(t6.lanewise(VectorOperators.SUB, x6).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 25 * s);
        INT256.cast(t7.lanewise(VectorOperators.ADD, x7).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 7 * s);
        INT256.cast(t7.lanewise(VectorOperators.SUB, x7).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 24 * s);
        INT256.cast(t9.lanewise(VectorOperators.ADD, x9).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 9 * s);
        INT256.cast(t9.lanewise(VectorOperators.SUB, x9).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 22 * s);
        INT256.cast(t10.lanewise(VectorOperators.ADD, x10).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 10 * s);
        INT256.cast(t10.lanewise(VectorOperators.SUB, x10).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 21 * s);
        INT256.cast(t11.lanewise(VectorOperators.ADD, x11).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 11 * s);
        INT256.cast(t11.lanewise(VectorOperators.SUB, x11).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 20 * s);
        INT256.cast(t12.lanewise(VectorOperators.ADD, x12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 12 * s);
        INT256.cast(t12.lanewise(VectorOperators.SUB, x12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 19 * s);
        INT256.cast(t13.lanewise(VectorOperators.ADD, x13).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 13 * s);
        INT256.cast(t13.lanewise(VectorOperators.SUB, x13).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 18 * s);
        INT256.cast(t14.lanewise(VectorOperators.ADD, x14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 14 * s);
        INT256.cast(t14.lanewise(VectorOperators.SUB, x14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 17 * s);
    }

    /**
     * Runs {@code adst4} of {@link ScalarItxDsp} on 8 transforms at once, one per lane: element
     * {@code k} of lane {@code j} is at index {@code o + k * s + j} (the inputs and outputs of an ADST
     * at their own offsets and strides).
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst48(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        var in0 = INT256.cast(IntVector.fromArray(I256, in, io));
        var in1 = INT256.cast(IntVector.fromArray(I256, in, io + is));
        var in2 = INT256.cast(IntVector.fromArray(I256, in, io + 2 * is));
        var in3 = INT256.cast(IntVector.fromArray(I256, in, io + 3 * is));
        INT256.cast(in0.lanewise(VectorOperators.MUL, K8_1321)
                .lanewise(VectorOperators.ADD, in2.lanewise(VectorOperators.MUL, K8_M293))
                .lanewise(VectorOperators.ADD, in3.lanewise(VectorOperators.MUL, K8_M1614))
                .lanewise(VectorOperators.ADD, in1.lanewise(VectorOperators.MUL, K8_M752))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in2).lanewise(VectorOperators.ADD, in3)
                .lanewise(VectorOperators.ADD, in1)).intoArray(out, oo);
        INT256.cast(in0.lanewise(VectorOperators.MUL, K8_M1614)
                .lanewise(VectorOperators.SUB, in2.lanewise(VectorOperators.MUL, K8_1321))
                .lanewise(VectorOperators.SUB, in3.lanewise(VectorOperators.MUL, K8_M293))
                .lanewise(VectorOperators.ADD, in1.lanewise(VectorOperators.MUL, K8_M752))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in0).lanewise(VectorOperators.SUB, in3)
                .lanewise(VectorOperators.ADD, in1)).intoArray(out, oo + os);
        INT256.cast(in0.lanewise(VectorOperators.SUB, in2).lanewise(VectorOperators.ADD, in3)
                .lanewise(VectorOperators.MUL, K8_209).lanewise(VectorOperators.ADD, K8_128)
                .lanewise(VectorOperators.ASHR, 8)).intoArray(out, oo + 2 * os);
        INT256.cast(in0.lanewise(VectorOperators.MUL, K8_M293)
                .lanewise(VectorOperators.ADD, in2.lanewise(VectorOperators.MUL, K8_M1614))
                .lanewise(VectorOperators.SUB, in3.lanewise(VectorOperators.MUL, K8_1321))
                .lanewise(VectorOperators.SUB, in1.lanewise(VectorOperators.MUL, K8_M752))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in0).lanewise(VectorOperators.ADD, in2)
                .lanewise(VectorOperators.SUB, in1)).intoArray(out, oo + 3 * os);
    }

    /**
     * Runs {@code adst8} of {@link ScalarItxDsp} on 8 transforms at once, one per lane: element
     * {@code k} of lane {@code j} is at index {@code o + k * s + j} (the inputs and outputs of an ADST
     * at their own offsets and strides).
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst88(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        var in0 = INT256.cast(IntVector.fromArray(I256, in, io));
        var in1 = INT256.cast(IntVector.fromArray(I256, in, io + is));
        var in2 = INT256.cast(IntVector.fromArray(I256, in, io + 2 * is));
        var in3 = INT256.cast(IntVector.fromArray(I256, in, io + 3 * is));
        var in4 = INT256.cast(IntVector.fromArray(I256, in, io + 4 * is));
        var in5 = INT256.cast(IntVector.fromArray(I256, in, io + 5 * is));
        var in6 = INT256.cast(IntVector.fromArray(I256, in, io + 6 * is));
        var in7 = INT256.cast(IntVector.fromArray(I256, in, io + 7 * is));
        var t0a = in7.lanewise(VectorOperators.MUL, K8_M20)
                .lanewise(VectorOperators.ADD, in0.lanewise(VectorOperators.MUL, K8_401))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in7);
        var t1a = in7.lanewise(VectorOperators.MUL, K8_401)
                .lanewise(VectorOperators.SUB, in0.lanewise(VectorOperators.MUL, K8_M20))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in0);
        var t2a = in5.lanewise(VectorOperators.MUL, K8_M484)
                .lanewise(VectorOperators.ADD, in2.lanewise(VectorOperators.MUL, K8_1931))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in5);
        var t3a = in5.lanewise(VectorOperators.MUL, K8_1931)
                .lanewise(VectorOperators.SUB, in2.lanewise(VectorOperators.MUL, K8_M484))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in2);
        var t4a = in3.lanewise(VectorOperators.MUL, K8_1299)
                .lanewise(VectorOperators.ADD, in4.lanewise(VectorOperators.MUL, K8_1583))
                .lanewise(VectorOperators.ADD, K8_1024).lanewise(VectorOperators.ASHR, 11);
        var t5a = in3.lanewise(VectorOperators.MUL, K8_1583)
                .lanewise(VectorOperators.SUB, in4.lanewise(VectorOperators.MUL, K8_1299))
                .lanewise(VectorOperators.ADD, K8_1024).lanewise(VectorOperators.ASHR, 11);
        var t6a = in1.lanewise(VectorOperators.MUL, K8_1189)
                .lanewise(VectorOperators.ADD, in6.lanewise(VectorOperators.MUL, K8_M176))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in6);
        var t7a = in1.lanewise(VectorOperators.MUL, K8_M176)
                .lanewise(VectorOperators.SUB, in6.lanewise(VectorOperators.MUL, K8_1189))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in1);
        var t0 = t0a.lanewise(VectorOperators.ADD, t4a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t1 = t1a.lanewise(VectorOperators.ADD, t5a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t2 = t2a.lanewise(VectorOperators.ADD, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t3 = t3a.lanewise(VectorOperators.ADD, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t4 = t0a.lanewise(VectorOperators.SUB, t4a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t5 = t1a.lanewise(VectorOperators.SUB, t5a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t6 = t2a.lanewise(VectorOperators.SUB, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t7 = t3a.lanewise(VectorOperators.SUB, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t4a = t4.lanewise(VectorOperators.MUL, K8_M312)
                .lanewise(VectorOperators.ADD, t5.lanewise(VectorOperators.MUL, K8_1567))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t4);
        t5a = t4.lanewise(VectorOperators.MUL, K8_1567)
                .lanewise(VectorOperators.SUB, t5.lanewise(VectorOperators.MUL, K8_M312))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t5);
        t6a = t7.lanewise(VectorOperators.MUL, K8_M312)
                .lanewise(VectorOperators.SUB, t6.lanewise(VectorOperators.MUL, K8_1567))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t7);
        t7a = t7.lanewise(VectorOperators.MUL, K8_1567)
                .lanewise(VectorOperators.ADD, t6.lanewise(VectorOperators.MUL, K8_M312))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t6);
        INT256.cast(t0.lanewise(VectorOperators.ADD, t2).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(out, oo);
        INT256.cast(t1.lanewise(VectorOperators.ADD, t3).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high).lanewise(VectorOperators.NEG)).intoArray(out, oo + 7 * os);
        t2 = t0.lanewise(VectorOperators.SUB, t2).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t3 = t1.lanewise(VectorOperators.SUB, t3).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        INT256.cast(t4a.lanewise(VectorOperators.ADD, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high).lanewise(VectorOperators.NEG)).intoArray(out, oo + os);
        INT256.cast(t5a.lanewise(VectorOperators.ADD, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(out, oo + 6 * os);
        t6 = t4a.lanewise(VectorOperators.SUB, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t7 = t5a.lanewise(VectorOperators.SUB, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        INT256.cast(t2.lanewise(VectorOperators.ADD, t3).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8).lanewise(VectorOperators.NEG))
                .intoArray(out, oo + 3 * os);
        INT256.cast(t2.lanewise(VectorOperators.SUB, t3).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8)).intoArray(out, oo + 4 * os);
        INT256.cast(t6.lanewise(VectorOperators.ADD, t7).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8)).intoArray(out, oo + 2 * os);
        INT256.cast(t6.lanewise(VectorOperators.SUB, t7).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8).lanewise(VectorOperators.NEG))
                .intoArray(out, oo + 5 * os);
    }

    /**
     * Runs {@code adst16} of {@link ScalarItxDsp} on 8 transforms at once, one per lane: element
     * {@code k} of lane {@code j} is at index {@code o + k * s + j} (the inputs and outputs of an ADST
     * at their own offsets and strides).
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst168(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        vadst168p0(in, io, is, min, max, out, oo, os);
        vadst168p1(in, io, is, min, max, out, oo, os);
        vadst168p2(in, io, is, min, max, out, oo, os);
        vadst168p3(in, io, is, min, max, out, oo, os);
        vadst168p4(in, io, is, min, max, out, oo, os);
    }

    /**
     * Runs part 1 of 5 of {@link #vadst168}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst168p0(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        var in0 = INT256.cast(IntVector.fromArray(I256, in, io));
        var in1 = INT256.cast(IntVector.fromArray(I256, in, io + is));
        var in2 = INT256.cast(IntVector.fromArray(I256, in, io + 2 * is));
        var in3 = INT256.cast(IntVector.fromArray(I256, in, io + 3 * is));
        var in4 = INT256.cast(IntVector.fromArray(I256, in, io + 4 * is));
        var in5 = INT256.cast(IntVector.fromArray(I256, in, io + 5 * is));
        var in6 = INT256.cast(IntVector.fromArray(I256, in, io + 6 * is));
        var in7 = INT256.cast(IntVector.fromArray(I256, in, io + 7 * is));
        var in8 = INT256.cast(IntVector.fromArray(I256, in, io + 8 * is));
        var in9 = INT256.cast(IntVector.fromArray(I256, in, io + 9 * is));
        var in10 = INT256.cast(IntVector.fromArray(I256, in, io + 10 * is));
        var in11 = INT256.cast(IntVector.fromArray(I256, in, io + 11 * is));
        var in12 = INT256.cast(IntVector.fromArray(I256, in, io + 12 * is));
        var in13 = INT256.cast(IntVector.fromArray(I256, in, io + 13 * is));
        var in14 = INT256.cast(IntVector.fromArray(I256, in, io + 14 * is));
        var in15 = INT256.cast(IntVector.fromArray(I256, in, io + 15 * is));
        var t0 = in15.lanewise(VectorOperators.MUL, K8_M5)
                .lanewise(VectorOperators.ADD, in0.lanewise(VectorOperators.MUL, K8_201))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in15);
        var t1 = in15.lanewise(VectorOperators.MUL, K8_201)
                .lanewise(VectorOperators.SUB, in0.lanewise(VectorOperators.MUL, K8_M5))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in0);
        var t2 = in13.lanewise(VectorOperators.MUL, K8_M123)
                .lanewise(VectorOperators.ADD, in2.lanewise(VectorOperators.MUL, K8_995))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in13);
        var t3 = in13.lanewise(VectorOperators.MUL, K8_995)
                .lanewise(VectorOperators.SUB, in2.lanewise(VectorOperators.MUL, K8_M123))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in2);
        var t4 = in11.lanewise(VectorOperators.MUL, K8_M393)
                .lanewise(VectorOperators.ADD, in4.lanewise(VectorOperators.MUL, K8_1751))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in11);
        var t5 = in11.lanewise(VectorOperators.MUL, K8_1751)
                .lanewise(VectorOperators.SUB, in4.lanewise(VectorOperators.MUL, K8_M393))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in4);
        var t6 = in9.lanewise(VectorOperators.MUL, K8_1645)
                .lanewise(VectorOperators.ADD, in6.lanewise(VectorOperators.MUL, K8_1220))
                .lanewise(VectorOperators.ADD, K8_1024).lanewise(VectorOperators.ASHR, 11);
        var t7 = in9.lanewise(VectorOperators.MUL, K8_1220)
                .lanewise(VectorOperators.SUB, in6.lanewise(VectorOperators.MUL, K8_1645))
                .lanewise(VectorOperators.ADD, K8_1024).lanewise(VectorOperators.ASHR, 11);
        var t8 = in7.lanewise(VectorOperators.MUL, K8_2751)
                .lanewise(VectorOperators.ADD, in8.lanewise(VectorOperators.MUL, K8_M1061))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in8);
        var t9 = in7.lanewise(VectorOperators.MUL, K8_M1061)
                .lanewise(VectorOperators.SUB, in8.lanewise(VectorOperators.MUL, K8_2751))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in7);
        var t10 = in5.lanewise(VectorOperators.MUL, K8_2106)
                .lanewise(VectorOperators.ADD, in10.lanewise(VectorOperators.MUL, K8_M583))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in10);
        var t11 = in5.lanewise(VectorOperators.MUL, K8_M583)
                .lanewise(VectorOperators.SUB, in10.lanewise(VectorOperators.MUL, K8_2106))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in5);
        var t12 = in3.lanewise(VectorOperators.MUL, K8_1380)
                .lanewise(VectorOperators.ADD, in12.lanewise(VectorOperators.MUL, K8_M239))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in12);
        var t13 = in3.lanewise(VectorOperators.MUL, K8_M239)
                .lanewise(VectorOperators.SUB, in12.lanewise(VectorOperators.MUL, K8_1380))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in3);
        var t14 = in1.lanewise(VectorOperators.MUL, K8_601)
                .lanewise(VectorOperators.ADD, in14.lanewise(VectorOperators.MUL, K8_M44))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in14);
        var t15 = in1.lanewise(VectorOperators.MUL, K8_M44)
                .lanewise(VectorOperators.SUB, in14.lanewise(VectorOperators.MUL, K8_601))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in1);
        INT256.cast(t0).intoArray(in, io + 0 * is);
        INT256.cast(t1).intoArray(in, io + 1 * is);
        INT256.cast(t2).intoArray(in, io + 2 * is);
        INT256.cast(t3).intoArray(in, io + 3 * is);
        INT256.cast(t4).intoArray(in, io + 4 * is);
        INT256.cast(t5).intoArray(in, io + 5 * is);
        INT256.cast(t6).intoArray(in, io + 6 * is);
        INT256.cast(t7).intoArray(in, io + 7 * is);
        INT256.cast(t8).intoArray(in, io + 8 * is);
        INT256.cast(t9).intoArray(in, io + 9 * is);
        INT256.cast(t10).intoArray(in, io + 10 * is);
        INT256.cast(t11).intoArray(in, io + 11 * is);
        INT256.cast(t12).intoArray(in, io + 12 * is);
        INT256.cast(t13).intoArray(in, io + 13 * is);
        INT256.cast(t14).intoArray(in, io + 14 * is);
        INT256.cast(t15).intoArray(in, io + 15 * is);
    }

    /**
     * Runs part 2 of 5 of {@link #vadst168}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst168p1(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        var t0 = INT256.cast(IntVector.fromArray(I256, in, io + 0 * is));
        var t1 = INT256.cast(IntVector.fromArray(I256, in, io + 1 * is));
        var t2 = INT256.cast(IntVector.fromArray(I256, in, io + 2 * is));
        var t3 = INT256.cast(IntVector.fromArray(I256, in, io + 3 * is));
        var t4 = INT256.cast(IntVector.fromArray(I256, in, io + 4 * is));
        var t5 = INT256.cast(IntVector.fromArray(I256, in, io + 5 * is));
        var t6 = INT256.cast(IntVector.fromArray(I256, in, io + 6 * is));
        var t7 = INT256.cast(IntVector.fromArray(I256, in, io + 7 * is));
        var t8 = INT256.cast(IntVector.fromArray(I256, in, io + 8 * is));
        var t9 = INT256.cast(IntVector.fromArray(I256, in, io + 9 * is));
        var t10 = INT256.cast(IntVector.fromArray(I256, in, io + 10 * is));
        var t11 = INT256.cast(IntVector.fromArray(I256, in, io + 11 * is));
        var t12 = INT256.cast(IntVector.fromArray(I256, in, io + 12 * is));
        var t13 = INT256.cast(IntVector.fromArray(I256, in, io + 13 * is));
        var t14 = INT256.cast(IntVector.fromArray(I256, in, io + 14 * is));
        var t15 = INT256.cast(IntVector.fromArray(I256, in, io + 15 * is));
        var t0a = t0.lanewise(VectorOperators.ADD, t8).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t1a = t1.lanewise(VectorOperators.ADD, t9).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t2a = t2.lanewise(VectorOperators.ADD, t10).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t3a = t3.lanewise(VectorOperators.ADD, t11).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t4a = t4.lanewise(VectorOperators.ADD, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t5a = t5.lanewise(VectorOperators.ADD, t13).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t6a = t6.lanewise(VectorOperators.ADD, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t7a = t7.lanewise(VectorOperators.ADD, t15).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t8a = t0.lanewise(VectorOperators.SUB, t8).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t9a = t1.lanewise(VectorOperators.SUB, t9).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t10a = t2.lanewise(VectorOperators.SUB, t10).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t11a = t3.lanewise(VectorOperators.SUB, t11).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t12a = t4.lanewise(VectorOperators.SUB, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t13a = t5.lanewise(VectorOperators.SUB, t13).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t14a = t6.lanewise(VectorOperators.SUB, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t15a = t7.lanewise(VectorOperators.SUB, t15).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        INT256.cast(t0a).intoArray(in, io + 0 * is);
        INT256.cast(t1a).intoArray(in, io + 1 * is);
        INT256.cast(t2a).intoArray(in, io + 2 * is);
        INT256.cast(t3a).intoArray(in, io + 3 * is);
        INT256.cast(t4a).intoArray(in, io + 4 * is);
        INT256.cast(t5a).intoArray(in, io + 5 * is);
        INT256.cast(t6a).intoArray(in, io + 6 * is);
        INT256.cast(t7a).intoArray(in, io + 7 * is);
        INT256.cast(t8a).intoArray(in, io + 8 * is);
        INT256.cast(t9a).intoArray(in, io + 9 * is);
        INT256.cast(t10a).intoArray(in, io + 10 * is);
        INT256.cast(t11a).intoArray(in, io + 11 * is);
        INT256.cast(t12a).intoArray(in, io + 12 * is);
        INT256.cast(t13a).intoArray(in, io + 13 * is);
        INT256.cast(t14a).intoArray(in, io + 14 * is);
        INT256.cast(t15a).intoArray(in, io + 15 * is);
    }

    /**
     * Runs part 3 of 5 of {@link #vadst168}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst168p2(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        var t0a = INT256.cast(IntVector.fromArray(I256, in, io + 0 * is));
        var t1a = INT256.cast(IntVector.fromArray(I256, in, io + 1 * is));
        var t2a = INT256.cast(IntVector.fromArray(I256, in, io + 2 * is));
        var t3a = INT256.cast(IntVector.fromArray(I256, in, io + 3 * is));
        var t4a = INT256.cast(IntVector.fromArray(I256, in, io + 4 * is));
        var t5a = INT256.cast(IntVector.fromArray(I256, in, io + 5 * is));
        var t6a = INT256.cast(IntVector.fromArray(I256, in, io + 6 * is));
        var t7a = INT256.cast(IntVector.fromArray(I256, in, io + 7 * is));
        var t8a = INT256.cast(IntVector.fromArray(I256, in, io + 8 * is));
        var t9a = INT256.cast(IntVector.fromArray(I256, in, io + 9 * is));
        var t10a = INT256.cast(IntVector.fromArray(I256, in, io + 10 * is));
        var t11a = INT256.cast(IntVector.fromArray(I256, in, io + 11 * is));
        var t12a = INT256.cast(IntVector.fromArray(I256, in, io + 12 * is));
        var t13a = INT256.cast(IntVector.fromArray(I256, in, io + 13 * is));
        var t14a = INT256.cast(IntVector.fromArray(I256, in, io + 14 * is));
        var t15a = INT256.cast(IntVector.fromArray(I256, in, io + 15 * is));
        var t8 = t8a.lanewise(VectorOperators.MUL, K8_M79)
                .lanewise(VectorOperators.ADD, t9a.lanewise(VectorOperators.MUL, K8_799))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t8a);
        var t9 = t8a.lanewise(VectorOperators.MUL, K8_799)
                .lanewise(VectorOperators.SUB, t9a.lanewise(VectorOperators.MUL, K8_M79))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t9a);
        var t10 = t10a.lanewise(VectorOperators.MUL, K8_2276)
                .lanewise(VectorOperators.ADD, t11a.lanewise(VectorOperators.MUL, K8_M690))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t11a);
        var t11 = t10a.lanewise(VectorOperators.MUL, K8_M690)
                .lanewise(VectorOperators.SUB, t11a.lanewise(VectorOperators.MUL, K8_2276))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t10a);
        var t12 = t13a.lanewise(VectorOperators.MUL, K8_M79)
                .lanewise(VectorOperators.SUB, t12a.lanewise(VectorOperators.MUL, K8_799))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t13a);
        var t13 = t13a.lanewise(VectorOperators.MUL, K8_799)
                .lanewise(VectorOperators.ADD, t12a.lanewise(VectorOperators.MUL, K8_M79))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t12a);
        var t14 = t15a.lanewise(VectorOperators.MUL, K8_2276)
                .lanewise(VectorOperators.SUB, t14a.lanewise(VectorOperators.MUL, K8_M690))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t14a);
        var t15 = t15a.lanewise(VectorOperators.MUL, K8_M690)
                .lanewise(VectorOperators.ADD, t14a.lanewise(VectorOperators.MUL, K8_2276))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t15a);
        INT256.cast(t8).intoArray(in, io + 0 * is);
        INT256.cast(t9).intoArray(in, io + 1 * is);
        INT256.cast(t10).intoArray(in, io + 2 * is);
        INT256.cast(t11).intoArray(in, io + 3 * is);
        INT256.cast(t12).intoArray(in, io + 4 * is);
        INT256.cast(t13).intoArray(in, io + 5 * is);
        INT256.cast(t14).intoArray(in, io + 6 * is);
        INT256.cast(t15).intoArray(in, io + 7 * is);
        INT256.cast(t0a).intoArray(in, io + 8 * is);
        INT256.cast(t1a).intoArray(in, io + 9 * is);
        INT256.cast(t2a).intoArray(in, io + 10 * is);
        INT256.cast(t3a).intoArray(in, io + 11 * is);
        INT256.cast(t4a).intoArray(in, io + 12 * is);
        INT256.cast(t5a).intoArray(in, io + 13 * is);
        INT256.cast(t6a).intoArray(in, io + 14 * is);
        INT256.cast(t7a).intoArray(in, io + 15 * is);
    }

    /**
     * Runs part 4 of 5 of {@link #vadst168}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst168p3(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        var t8 = INT256.cast(IntVector.fromArray(I256, in, io + 0 * is));
        var t9 = INT256.cast(IntVector.fromArray(I256, in, io + 1 * is));
        var t10 = INT256.cast(IntVector.fromArray(I256, in, io + 2 * is));
        var t11 = INT256.cast(IntVector.fromArray(I256, in, io + 3 * is));
        var t12 = INT256.cast(IntVector.fromArray(I256, in, io + 4 * is));
        var t13 = INT256.cast(IntVector.fromArray(I256, in, io + 5 * is));
        var t14 = INT256.cast(IntVector.fromArray(I256, in, io + 6 * is));
        var t15 = INT256.cast(IntVector.fromArray(I256, in, io + 7 * is));
        var t0a = INT256.cast(IntVector.fromArray(I256, in, io + 8 * is));
        var t1a = INT256.cast(IntVector.fromArray(I256, in, io + 9 * is));
        var t2a = INT256.cast(IntVector.fromArray(I256, in, io + 10 * is));
        var t3a = INT256.cast(IntVector.fromArray(I256, in, io + 11 * is));
        var t4a = INT256.cast(IntVector.fromArray(I256, in, io + 12 * is));
        var t5a = INT256.cast(IntVector.fromArray(I256, in, io + 13 * is));
        var t6a = INT256.cast(IntVector.fromArray(I256, in, io + 14 * is));
        var t7a = INT256.cast(IntVector.fromArray(I256, in, io + 15 * is));
        var t0 = t0a.lanewise(VectorOperators.ADD, t4a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t1 = t1a.lanewise(VectorOperators.ADD, t5a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t2 = t2a.lanewise(VectorOperators.ADD, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t3 = t3a.lanewise(VectorOperators.ADD, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t4 = t0a.lanewise(VectorOperators.SUB, t4a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t5 = t1a.lanewise(VectorOperators.SUB, t5a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t6 = t2a.lanewise(VectorOperators.SUB, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t7 = t3a.lanewise(VectorOperators.SUB, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t8a = t8.lanewise(VectorOperators.ADD, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t9a = t9.lanewise(VectorOperators.ADD, t13).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t10a = t10.lanewise(VectorOperators.ADD, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t11a = t11.lanewise(VectorOperators.ADD, t15).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t12a = t8.lanewise(VectorOperators.SUB, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t13a = t9.lanewise(VectorOperators.SUB, t13).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t14a = t10.lanewise(VectorOperators.SUB, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t15a = t11.lanewise(VectorOperators.SUB, t15).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t4a = t4.lanewise(VectorOperators.MUL, K8_M312)
                .lanewise(VectorOperators.ADD, t5.lanewise(VectorOperators.MUL, K8_1567))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t4);
        t5a = t4.lanewise(VectorOperators.MUL, K8_1567)
                .lanewise(VectorOperators.SUB, t5.lanewise(VectorOperators.MUL, K8_M312))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t5);
        t6a = t7.lanewise(VectorOperators.MUL, K8_M312)
                .lanewise(VectorOperators.SUB, t6.lanewise(VectorOperators.MUL, K8_1567))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t7);
        t7a = t7.lanewise(VectorOperators.MUL, K8_1567)
                .lanewise(VectorOperators.ADD, t6.lanewise(VectorOperators.MUL, K8_M312))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t6);
        t12 = t12a.lanewise(VectorOperators.MUL, K8_M312)
                .lanewise(VectorOperators.ADD, t13a.lanewise(VectorOperators.MUL, K8_1567))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t12a);
        t13 = t12a.lanewise(VectorOperators.MUL, K8_1567)
                .lanewise(VectorOperators.SUB, t13a.lanewise(VectorOperators.MUL, K8_M312))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t13a);
        t14 = t15a.lanewise(VectorOperators.MUL, K8_M312)
                .lanewise(VectorOperators.SUB, t14a.lanewise(VectorOperators.MUL, K8_1567))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t15a);
        t15 = t15a.lanewise(VectorOperators.MUL, K8_1567)
                .lanewise(VectorOperators.ADD, t14a.lanewise(VectorOperators.MUL, K8_M312))
                .lanewise(VectorOperators.ADD, K8_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t14a);
        INT256.cast(t0).intoArray(in, io + 0 * is);
        INT256.cast(t1).intoArray(in, io + 1 * is);
        INT256.cast(t2).intoArray(in, io + 2 * is);
        INT256.cast(t3).intoArray(in, io + 3 * is);
        INT256.cast(t12).intoArray(in, io + 4 * is);
        INT256.cast(t13).intoArray(in, io + 5 * is);
        INT256.cast(t14).intoArray(in, io + 6 * is);
        INT256.cast(t15).intoArray(in, io + 7 * is);
        INT256.cast(t4a).intoArray(in, io + 8 * is);
        INT256.cast(t5a).intoArray(in, io + 9 * is);
        INT256.cast(t6a).intoArray(in, io + 10 * is);
        INT256.cast(t7a).intoArray(in, io + 11 * is);
        INT256.cast(t8a).intoArray(in, io + 12 * is);
        INT256.cast(t9a).intoArray(in, io + 13 * is);
        INT256.cast(t10a).intoArray(in, io + 14 * is);
        INT256.cast(t11a).intoArray(in, io + 15 * is);
    }

    /**
     * Runs part 5 of 5 of {@link #vadst168}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst168p4(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        var low = IntVector.broadcast(I256, min);
        var high = IntVector.broadcast(I256, max);
        var t0 = INT256.cast(IntVector.fromArray(I256, in, io + 0 * is));
        var t1 = INT256.cast(IntVector.fromArray(I256, in, io + 1 * is));
        var t2 = INT256.cast(IntVector.fromArray(I256, in, io + 2 * is));
        var t3 = INT256.cast(IntVector.fromArray(I256, in, io + 3 * is));
        var t12 = INT256.cast(IntVector.fromArray(I256, in, io + 4 * is));
        var t13 = INT256.cast(IntVector.fromArray(I256, in, io + 5 * is));
        var t14 = INT256.cast(IntVector.fromArray(I256, in, io + 6 * is));
        var t15 = INT256.cast(IntVector.fromArray(I256, in, io + 7 * is));
        var t4a = INT256.cast(IntVector.fromArray(I256, in, io + 8 * is));
        var t5a = INT256.cast(IntVector.fromArray(I256, in, io + 9 * is));
        var t6a = INT256.cast(IntVector.fromArray(I256, in, io + 10 * is));
        var t7a = INT256.cast(IntVector.fromArray(I256, in, io + 11 * is));
        var t8a = INT256.cast(IntVector.fromArray(I256, in, io + 12 * is));
        var t9a = INT256.cast(IntVector.fromArray(I256, in, io + 13 * is));
        var t10a = INT256.cast(IntVector.fromArray(I256, in, io + 14 * is));
        var t11a = INT256.cast(IntVector.fromArray(I256, in, io + 15 * is));
        INT256.cast(t0.lanewise(VectorOperators.ADD, t2).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(out, oo);
        INT256.cast(t1.lanewise(VectorOperators.ADD, t3).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high).lanewise(VectorOperators.NEG)).intoArray(out, oo + 15 * os);
        var t2a = t0.lanewise(VectorOperators.SUB, t2).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t3a = t1.lanewise(VectorOperators.SUB, t3).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        INT256.cast(t4a.lanewise(VectorOperators.ADD, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high).lanewise(VectorOperators.NEG)).intoArray(out, oo + 3 * os);
        INT256.cast(t5a.lanewise(VectorOperators.ADD, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(out, oo + 12 * os);
        var t6 = t4a.lanewise(VectorOperators.SUB, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t7 = t5a.lanewise(VectorOperators.SUB, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        INT256.cast(t8a.lanewise(VectorOperators.ADD, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high).lanewise(VectorOperators.NEG)).intoArray(out, oo + os);
        INT256.cast(t9a.lanewise(VectorOperators.ADD, t11a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(out, oo + 14 * os);
        var t10 = t8a.lanewise(VectorOperators.SUB, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t11 = t9a.lanewise(VectorOperators.SUB, t11a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        INT256.cast(t12.lanewise(VectorOperators.ADD, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(out, oo + 2 * os);
        INT256.cast(t13.lanewise(VectorOperators.ADD, t15).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high).lanewise(VectorOperators.NEG)).intoArray(out, oo + 13 * os);
        var t14a = t12.lanewise(VectorOperators.SUB, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t15a = t13.lanewise(VectorOperators.SUB, t15).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        INT256.cast(t2a.lanewise(VectorOperators.ADD, t3a).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8).lanewise(VectorOperators.NEG))
                .intoArray(out, oo + 7 * os);
        INT256.cast(t2a.lanewise(VectorOperators.SUB, t3a).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8)).intoArray(out, oo + 8 * os);
        INT256.cast(t6.lanewise(VectorOperators.ADD, t7).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8)).intoArray(out, oo + 4 * os);
        INT256.cast(t6.lanewise(VectorOperators.SUB, t7).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8).lanewise(VectorOperators.NEG))
                .intoArray(out, oo + 11 * os);
        INT256.cast(t10.lanewise(VectorOperators.ADD, t11).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8)).intoArray(out, oo + 6 * os);
        INT256.cast(t10.lanewise(VectorOperators.SUB, t11).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8).lanewise(VectorOperators.NEG))
                .intoArray(out, oo + 9 * os);
        INT256.cast(t14a.lanewise(VectorOperators.ADD, t15a).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8).lanewise(VectorOperators.NEG))
                .intoArray(out, oo + 5 * os);
        INT256.cast(t14a.lanewise(VectorOperators.SUB, t15a).lanewise(VectorOperators.MUL, K8_181)
                .lanewise(VectorOperators.ADD, K8_128).lanewise(VectorOperators.ASHR, 8)).intoArray(out, oo + 10 * os);
    }

    /**
     * Runs {@code identity4} of {@link ScalarItxDsp} on 8 transforms at once, one per lane.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     */
    private static void videntity48(int[] c, int o, int s) {
        for (var i = 0; i < 4; i++) {
            var v = INT256.cast(IntVector.fromArray(I256, c, o + i * s));
            INT256.cast(v.lanewise(VectorOperators.ADD, v.lanewise(VectorOperators.MUL, K8_1697)
                    .lanewise(VectorOperators.ADD, K8_2048)
                    .lanewise(VectorOperators.ASHR, 12))).intoArray(c, o + i * s);
        }
    }

    /**
     * Runs {@code identity8} of {@link ScalarItxDsp} on 8 transforms at once, one per lane.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     */
    private static void videntity88(int[] c, int o, int s) {
        for (var i = 0; i < 8; i++) {
            var v = INT256.cast(IntVector.fromArray(I256, c, o + i * s));
            INT256.cast(v.lanewise(VectorOperators.LSHL, 1)).intoArray(c, o + i * s);
        }
    }

    /**
     * Runs {@code identity16} of {@link ScalarItxDsp} on 8 transforms at once, one per lane.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     */
    private static void videntity168(int[] c, int o, int s) {
        for (var i = 0; i < 16; i++) {
            var v = INT256.cast(IntVector.fromArray(I256, c, o + i * s));
            INT256.cast(v.lanewise(VectorOperators.LSHL, 1)
                    .lanewise(VectorOperators.ADD, v.lanewise(VectorOperators.MUL, K8_1697)
                    .lanewise(VectorOperators.ADD, K8_1024).lanewise(VectorOperators.ASHR, 11)))
                            .intoArray(c, o + i * s);
        }
    }

    /**
     * Runs {@code identity32} of {@link ScalarItxDsp} on 8 transforms at once, one per lane.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     */
    private static void videntity328(int[] c, int o, int s) {
        for (var i = 0; i < 32; i++) {
            var v = INT256.cast(IntVector.fromArray(I256, c, o + i * s));
            INT256.cast(v.lanewise(VectorOperators.LSHL, 2)).intoArray(c, o + i * s);
        }
    }

    /**
     * Runs {@code dct4} of {@link ScalarItxDsp} on 4 transforms at once, one per lane: element
     * {@code k} of lane {@code j} is at index {@code o + k * s + j} (the inputs and outputs of an ADST
     * at their own offsets and strides).
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct44(int[] c, int o, int s, int min, int max) {
        var low = IntVector.broadcast(I128, min);
        var high = IntVector.broadcast(I128, max);
        var in0 = INT128.cast(IntVector.fromArray(I128, c, o));
        var in1 = INT128.cast(IntVector.fromArray(I128, c, o + s));
        var in2 = INT128.cast(IntVector.fromArray(I128, c, o + 2 * s));
        var in3 = INT128.cast(IntVector.fromArray(I128, c, o + 3 * s));
        var t0 = in0.lanewise(VectorOperators.ADD, in2).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8);
        var t1 = in0.lanewise(VectorOperators.SUB, in2).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8);
        var t2 = in1.lanewise(VectorOperators.MUL, K4_1567)
                .lanewise(VectorOperators.SUB, in3.lanewise(VectorOperators.MUL, K4_M312))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in3);
        var t3 = in1.lanewise(VectorOperators.MUL, K4_M312)
                .lanewise(VectorOperators.ADD, in3.lanewise(VectorOperators.MUL, K4_1567))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in1);
        INT128.cast(t0.lanewise(VectorOperators.ADD, t3).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o);
        INT128.cast(t1.lanewise(VectorOperators.ADD, t2).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + s);
        INT128.cast(t1.lanewise(VectorOperators.SUB, t2).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 2 * s);
        INT128.cast(t0.lanewise(VectorOperators.SUB, t3).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 3 * s);
    }

    /**
     * Runs {@code dct8} of {@link ScalarItxDsp} on 4 transforms at once, one per lane: element
     * {@code k} of lane {@code j} is at index {@code o + k * s + j} (the inputs and outputs of an ADST
     * at their own offsets and strides).
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct84(int[] c, int o, int s, int min, int max) {
        var low = IntVector.broadcast(I128, min);
        var high = IntVector.broadcast(I128, max);
        vdct44(c, o, s << 1, min, max);
        var in1 = INT128.cast(IntVector.fromArray(I128, c, o + s));
        var in3 = INT128.cast(IntVector.fromArray(I128, c, o + 3 * s));
        var in5 = INT128.cast(IntVector.fromArray(I128, c, o + 5 * s));
        var in7 = INT128.cast(IntVector.fromArray(I128, c, o + 7 * s));
        var t4a = in1.lanewise(VectorOperators.MUL, K4_799)
                .lanewise(VectorOperators.SUB, in7.lanewise(VectorOperators.MUL, K4_M79))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in7);
        var t5a = in5.lanewise(VectorOperators.MUL, K4_1703)
                .lanewise(VectorOperators.SUB, in3.lanewise(VectorOperators.MUL, K4_1138))
                .lanewise(VectorOperators.ADD, K4_1024).lanewise(VectorOperators.ASHR, 11);
        var t6a = in5.lanewise(VectorOperators.MUL, K4_1138)
                .lanewise(VectorOperators.ADD, in3.lanewise(VectorOperators.MUL, K4_1703))
                .lanewise(VectorOperators.ADD, K4_1024).lanewise(VectorOperators.ASHR, 11);
        var t7a = in1.lanewise(VectorOperators.MUL, K4_M79)
                .lanewise(VectorOperators.ADD, in7.lanewise(VectorOperators.MUL, K4_799))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in1);
        var t4 = t4a.lanewise(VectorOperators.ADD, t5a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t5a = t4a.lanewise(VectorOperators.SUB, t5a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t7 = t7a.lanewise(VectorOperators.ADD, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t6a = t7a.lanewise(VectorOperators.SUB, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t5 = t6a.lanewise(VectorOperators.SUB, t5a).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8);
        var t6 = t6a.lanewise(VectorOperators.ADD, t5a).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8);
        var t0 = INT128.cast(IntVector.fromArray(I128, c, o));
        var t1 = INT128.cast(IntVector.fromArray(I128, c, o + 2 * s));
        var t2 = INT128.cast(IntVector.fromArray(I128, c, o + 4 * s));
        var t3 = INT128.cast(IntVector.fromArray(I128, c, o + 6 * s));
        INT128.cast(t0.lanewise(VectorOperators.ADD, t7).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o);
        INT128.cast(t1.lanewise(VectorOperators.ADD, t6).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + s);
        INT128.cast(t2.lanewise(VectorOperators.ADD, t5).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 2 * s);
        INT128.cast(t3.lanewise(VectorOperators.ADD, t4).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 3 * s);
        INT128.cast(t3.lanewise(VectorOperators.SUB, t4).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 4 * s);
        INT128.cast(t2.lanewise(VectorOperators.SUB, t5).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 5 * s);
        INT128.cast(t1.lanewise(VectorOperators.SUB, t6).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 6 * s);
        INT128.cast(t0.lanewise(VectorOperators.SUB, t7).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 7 * s);
    }

    /**
     * Runs {@code dct16} of {@link ScalarItxDsp} on 4 transforms at once, one per lane: element
     * {@code k} of lane {@code j} is at index {@code o + k * s + j} (the inputs and outputs of an ADST
     * at their own offsets and strides).
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct164(int[] c, int o, int s, int min, int max) {
        vdct164p0(c, o, s, min, max);
        vdct164p1(c, o, s, min, max);
    }

    /**
     * Runs part 1 of 2 of {@link #vdct164}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct164p0(int[] c, int o, int s, int min, int max) {
        var low = IntVector.broadcast(I128, min);
        var high = IntVector.broadcast(I128, max);
        vdct84(c, o, s << 1, min, max);
        var in1 = INT128.cast(IntVector.fromArray(I128, c, o + s));
        var in3 = INT128.cast(IntVector.fromArray(I128, c, o + 3 * s));
        var in5 = INT128.cast(IntVector.fromArray(I128, c, o + 5 * s));
        var in7 = INT128.cast(IntVector.fromArray(I128, c, o + 7 * s));
        var in9 = INT128.cast(IntVector.fromArray(I128, c, o + 9 * s));
        var in11 = INT128.cast(IntVector.fromArray(I128, c, o + 11 * s));
        var in13 = INT128.cast(IntVector.fromArray(I128, c, o + 13 * s));
        var in15 = INT128.cast(IntVector.fromArray(I128, c, o + 15 * s));
        var t8a = in1.lanewise(VectorOperators.MUL, K4_401)
                .lanewise(VectorOperators.SUB, in15.lanewise(VectorOperators.MUL, K4_M20))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in15);
        var t9a = in9.lanewise(VectorOperators.MUL, K4_1583)
                .lanewise(VectorOperators.SUB, in7.lanewise(VectorOperators.MUL, K4_1299))
                .lanewise(VectorOperators.ADD, K4_1024).lanewise(VectorOperators.ASHR, 11);
        var t10a = in5.lanewise(VectorOperators.MUL, K4_1931)
                .lanewise(VectorOperators.SUB, in11.lanewise(VectorOperators.MUL, K4_M484))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in11);
        var t11a = in13.lanewise(VectorOperators.MUL, K4_M176)
                .lanewise(VectorOperators.SUB, in3.lanewise(VectorOperators.MUL, K4_1189))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in13);
        var t12a = in13.lanewise(VectorOperators.MUL, K4_1189)
                .lanewise(VectorOperators.ADD, in3.lanewise(VectorOperators.MUL, K4_M176))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in3);
        var t13a = in5.lanewise(VectorOperators.MUL, K4_M484)
                .lanewise(VectorOperators.ADD, in11.lanewise(VectorOperators.MUL, K4_1931))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in5);
        var t14a = in9.lanewise(VectorOperators.MUL, K4_1299)
                .lanewise(VectorOperators.ADD, in7.lanewise(VectorOperators.MUL, K4_1583))
                .lanewise(VectorOperators.ADD, K4_1024).lanewise(VectorOperators.ASHR, 11);
        var t15a = in1.lanewise(VectorOperators.MUL, K4_M20)
                .lanewise(VectorOperators.ADD, in15.lanewise(VectorOperators.MUL, K4_401))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in1);
        var t8 = t8a.lanewise(VectorOperators.ADD, t9a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t9 = t8a.lanewise(VectorOperators.SUB, t9a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t10 = t11a.lanewise(VectorOperators.SUB, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t11 = t11a.lanewise(VectorOperators.ADD, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t12 = t12a.lanewise(VectorOperators.ADD, t13a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t13 = t12a.lanewise(VectorOperators.SUB, t13a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t14 = t15a.lanewise(VectorOperators.SUB, t14a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t15 = t15a.lanewise(VectorOperators.ADD, t14a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t9a = t14.lanewise(VectorOperators.MUL, K4_1567)
                .lanewise(VectorOperators.SUB, t9.lanewise(VectorOperators.MUL, K4_M312))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t9);
        t14a = t14.lanewise(VectorOperators.MUL, K4_M312)
                .lanewise(VectorOperators.ADD, t9.lanewise(VectorOperators.MUL, K4_1567))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t14);
        t10a = t13.lanewise(VectorOperators.MUL, K4_M312)
                .lanewise(VectorOperators.ADD, t10.lanewise(VectorOperators.MUL, K4_1567)).lanewise(VectorOperators.NEG)
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t13);
        t13a = t13.lanewise(VectorOperators.MUL, K4_1567)
                .lanewise(VectorOperators.SUB, t10.lanewise(VectorOperators.MUL, K4_M312))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t10);
        t8a = t8.lanewise(VectorOperators.ADD, t11).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t9 = t9a.lanewise(VectorOperators.ADD, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t10 = t9a.lanewise(VectorOperators.SUB, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t11a = t8.lanewise(VectorOperators.SUB, t11).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t12a = t15.lanewise(VectorOperators.SUB, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t13 = t14a.lanewise(VectorOperators.SUB, t13a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t14 = t14a.lanewise(VectorOperators.ADD, t13a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t15a = t15.lanewise(VectorOperators.ADD, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t10a = t13.lanewise(VectorOperators.SUB, t10).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8);
        t13a = t13.lanewise(VectorOperators.ADD, t10).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8);
        t11 = t12a.lanewise(VectorOperators.SUB, t11a).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8);
        t12 = t12a.lanewise(VectorOperators.ADD, t11a).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8);
        INT128.cast(t8a).intoArray(c, o + 1 * s);
        INT128.cast(t10a).intoArray(c, o + 3 * s);
        INT128.cast(t13a).intoArray(c, o + 5 * s);
        INT128.cast(t15a).intoArray(c, o + 7 * s);
        INT128.cast(t9).intoArray(c, o + 9 * s);
        INT128.cast(t11).intoArray(c, o + 11 * s);
        INT128.cast(t12).intoArray(c, o + 13 * s);
        INT128.cast(t14).intoArray(c, o + 15 * s);
    }

    /**
     * Runs part 2 of 2 of {@link #vdct164}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     */
    private static void vdct164p1(int[] c, int o, int s, int min, int max) {
        var low = IntVector.broadcast(I128, min);
        var high = IntVector.broadcast(I128, max);
        var t8a = INT128.cast(IntVector.fromArray(I128, c, o + 1 * s));
        var t10a = INT128.cast(IntVector.fromArray(I128, c, o + 3 * s));
        var t13a = INT128.cast(IntVector.fromArray(I128, c, o + 5 * s));
        var t15a = INT128.cast(IntVector.fromArray(I128, c, o + 7 * s));
        var t9 = INT128.cast(IntVector.fromArray(I128, c, o + 9 * s));
        var t11 = INT128.cast(IntVector.fromArray(I128, c, o + 11 * s));
        var t12 = INT128.cast(IntVector.fromArray(I128, c, o + 13 * s));
        var t14 = INT128.cast(IntVector.fromArray(I128, c, o + 15 * s));
        var t0 = INT128.cast(IntVector.fromArray(I128, c, o));
        var t1 = INT128.cast(IntVector.fromArray(I128, c, o + 2 * s));
        var t2 = INT128.cast(IntVector.fromArray(I128, c, o + 4 * s));
        var t3 = INT128.cast(IntVector.fromArray(I128, c, o + 6 * s));
        var t4 = INT128.cast(IntVector.fromArray(I128, c, o + 8 * s));
        var t5 = INT128.cast(IntVector.fromArray(I128, c, o + 10 * s));
        var t6 = INT128.cast(IntVector.fromArray(I128, c, o + 12 * s));
        var t7 = INT128.cast(IntVector.fromArray(I128, c, o + 14 * s));
        INT128.cast(t0.lanewise(VectorOperators.ADD, t15a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o);
        INT128.cast(t1.lanewise(VectorOperators.ADD, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + s);
        INT128.cast(t2.lanewise(VectorOperators.ADD, t13a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 2 * s);
        INT128.cast(t3.lanewise(VectorOperators.ADD, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 3 * s);
        INT128.cast(t4.lanewise(VectorOperators.ADD, t11).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 4 * s);
        INT128.cast(t5.lanewise(VectorOperators.ADD, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 5 * s);
        INT128.cast(t6.lanewise(VectorOperators.ADD, t9).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 6 * s);
        INT128.cast(t7.lanewise(VectorOperators.ADD, t8a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 7 * s);
        INT128.cast(t7.lanewise(VectorOperators.SUB, t8a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 8 * s);
        INT128.cast(t6.lanewise(VectorOperators.SUB, t9).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 9 * s);
        INT128.cast(t5.lanewise(VectorOperators.SUB, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 10 * s);
        INT128.cast(t4.lanewise(VectorOperators.SUB, t11).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 11 * s);
        INT128.cast(t3.lanewise(VectorOperators.SUB, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 12 * s);
        INT128.cast(t2.lanewise(VectorOperators.SUB, t13a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 13 * s);
        INT128.cast(t1.lanewise(VectorOperators.SUB, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 14 * s);
        INT128.cast(t0.lanewise(VectorOperators.SUB, t15a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + 15 * s);
    }

    /**
     * Runs {@code adst4} of {@link ScalarItxDsp} on 4 transforms at once, one per lane: element
     * {@code k} of lane {@code j} is at index {@code o + k * s + j} (the inputs and outputs of an ADST
     * at their own offsets and strides).
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst44(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        var low = IntVector.broadcast(I128, min);
        var high = IntVector.broadcast(I128, max);
        var in0 = INT128.cast(IntVector.fromArray(I128, in, io));
        var in1 = INT128.cast(IntVector.fromArray(I128, in, io + is));
        var in2 = INT128.cast(IntVector.fromArray(I128, in, io + 2 * is));
        var in3 = INT128.cast(IntVector.fromArray(I128, in, io + 3 * is));
        INT128.cast(in0.lanewise(VectorOperators.MUL, K4_1321)
                .lanewise(VectorOperators.ADD, in2.lanewise(VectorOperators.MUL, K4_M293))
                .lanewise(VectorOperators.ADD, in3.lanewise(VectorOperators.MUL, K4_M1614))
                .lanewise(VectorOperators.ADD, in1.lanewise(VectorOperators.MUL, K4_M752))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in2).lanewise(VectorOperators.ADD, in3)
                .lanewise(VectorOperators.ADD, in1)).intoArray(out, oo);
        INT128.cast(in0.lanewise(VectorOperators.MUL, K4_M1614)
                .lanewise(VectorOperators.SUB, in2.lanewise(VectorOperators.MUL, K4_1321))
                .lanewise(VectorOperators.SUB, in3.lanewise(VectorOperators.MUL, K4_M293))
                .lanewise(VectorOperators.ADD, in1.lanewise(VectorOperators.MUL, K4_M752))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in0).lanewise(VectorOperators.SUB, in3)
                .lanewise(VectorOperators.ADD, in1)).intoArray(out, oo + os);
        INT128.cast(in0.lanewise(VectorOperators.SUB, in2).lanewise(VectorOperators.ADD, in3)
                .lanewise(VectorOperators.MUL, K4_209).lanewise(VectorOperators.ADD, K4_128)
                .lanewise(VectorOperators.ASHR, 8)).intoArray(out, oo + 2 * os);
        INT128.cast(in0.lanewise(VectorOperators.MUL, K4_M293)
                .lanewise(VectorOperators.ADD, in2.lanewise(VectorOperators.MUL, K4_M1614))
                .lanewise(VectorOperators.SUB, in3.lanewise(VectorOperators.MUL, K4_1321))
                .lanewise(VectorOperators.SUB, in1.lanewise(VectorOperators.MUL, K4_M752))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in0).lanewise(VectorOperators.ADD, in2)
                .lanewise(VectorOperators.SUB, in1)).intoArray(out, oo + 3 * os);
    }

    /**
     * Runs {@code adst8} of {@link ScalarItxDsp} on 4 transforms at once, one per lane: element
     * {@code k} of lane {@code j} is at index {@code o + k * s + j} (the inputs and outputs of an ADST
     * at their own offsets and strides).
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst84(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        var low = IntVector.broadcast(I128, min);
        var high = IntVector.broadcast(I128, max);
        var in0 = INT128.cast(IntVector.fromArray(I128, in, io));
        var in1 = INT128.cast(IntVector.fromArray(I128, in, io + is));
        var in2 = INT128.cast(IntVector.fromArray(I128, in, io + 2 * is));
        var in3 = INT128.cast(IntVector.fromArray(I128, in, io + 3 * is));
        var in4 = INT128.cast(IntVector.fromArray(I128, in, io + 4 * is));
        var in5 = INT128.cast(IntVector.fromArray(I128, in, io + 5 * is));
        var in6 = INT128.cast(IntVector.fromArray(I128, in, io + 6 * is));
        var in7 = INT128.cast(IntVector.fromArray(I128, in, io + 7 * is));
        var t0a = in7.lanewise(VectorOperators.MUL, K4_M20)
                .lanewise(VectorOperators.ADD, in0.lanewise(VectorOperators.MUL, K4_401))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in7);
        var t1a = in7.lanewise(VectorOperators.MUL, K4_401)
                .lanewise(VectorOperators.SUB, in0.lanewise(VectorOperators.MUL, K4_M20))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in0);
        var t2a = in5.lanewise(VectorOperators.MUL, K4_M484)
                .lanewise(VectorOperators.ADD, in2.lanewise(VectorOperators.MUL, K4_1931))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in5);
        var t3a = in5.lanewise(VectorOperators.MUL, K4_1931)
                .lanewise(VectorOperators.SUB, in2.lanewise(VectorOperators.MUL, K4_M484))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in2);
        var t4a = in3.lanewise(VectorOperators.MUL, K4_1299)
                .lanewise(VectorOperators.ADD, in4.lanewise(VectorOperators.MUL, K4_1583))
                .lanewise(VectorOperators.ADD, K4_1024).lanewise(VectorOperators.ASHR, 11);
        var t5a = in3.lanewise(VectorOperators.MUL, K4_1583)
                .lanewise(VectorOperators.SUB, in4.lanewise(VectorOperators.MUL, K4_1299))
                .lanewise(VectorOperators.ADD, K4_1024).lanewise(VectorOperators.ASHR, 11);
        var t6a = in1.lanewise(VectorOperators.MUL, K4_1189)
                .lanewise(VectorOperators.ADD, in6.lanewise(VectorOperators.MUL, K4_M176))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in6);
        var t7a = in1.lanewise(VectorOperators.MUL, K4_M176)
                .lanewise(VectorOperators.SUB, in6.lanewise(VectorOperators.MUL, K4_1189))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in1);
        var t0 = t0a.lanewise(VectorOperators.ADD, t4a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t1 = t1a.lanewise(VectorOperators.ADD, t5a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t2 = t2a.lanewise(VectorOperators.ADD, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t3 = t3a.lanewise(VectorOperators.ADD, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t4 = t0a.lanewise(VectorOperators.SUB, t4a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t5 = t1a.lanewise(VectorOperators.SUB, t5a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t6 = t2a.lanewise(VectorOperators.SUB, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t7 = t3a.lanewise(VectorOperators.SUB, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t4a = t4.lanewise(VectorOperators.MUL, K4_M312)
                .lanewise(VectorOperators.ADD, t5.lanewise(VectorOperators.MUL, K4_1567))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t4);
        t5a = t4.lanewise(VectorOperators.MUL, K4_1567)
                .lanewise(VectorOperators.SUB, t5.lanewise(VectorOperators.MUL, K4_M312))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t5);
        t6a = t7.lanewise(VectorOperators.MUL, K4_M312)
                .lanewise(VectorOperators.SUB, t6.lanewise(VectorOperators.MUL, K4_1567))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t7);
        t7a = t7.lanewise(VectorOperators.MUL, K4_1567)
                .lanewise(VectorOperators.ADD, t6.lanewise(VectorOperators.MUL, K4_M312))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t6);
        INT128.cast(t0.lanewise(VectorOperators.ADD, t2).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(out, oo);
        INT128.cast(t1.lanewise(VectorOperators.ADD, t3).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high).lanewise(VectorOperators.NEG)).intoArray(out, oo + 7 * os);
        t2 = t0.lanewise(VectorOperators.SUB, t2).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t3 = t1.lanewise(VectorOperators.SUB, t3).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        INT128.cast(t4a.lanewise(VectorOperators.ADD, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high).lanewise(VectorOperators.NEG)).intoArray(out, oo + os);
        INT128.cast(t5a.lanewise(VectorOperators.ADD, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(out, oo + 6 * os);
        t6 = t4a.lanewise(VectorOperators.SUB, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t7 = t5a.lanewise(VectorOperators.SUB, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        INT128.cast(t2.lanewise(VectorOperators.ADD, t3).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8).lanewise(VectorOperators.NEG))
                .intoArray(out, oo + 3 * os);
        INT128.cast(t2.lanewise(VectorOperators.SUB, t3).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8)).intoArray(out, oo + 4 * os);
        INT128.cast(t6.lanewise(VectorOperators.ADD, t7).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8)).intoArray(out, oo + 2 * os);
        INT128.cast(t6.lanewise(VectorOperators.SUB, t7).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8).lanewise(VectorOperators.NEG))
                .intoArray(out, oo + 5 * os);
    }

    /**
     * Runs {@code adst16} of {@link ScalarItxDsp} on 4 transforms at once, one per lane: element
     * {@code k} of lane {@code j} is at index {@code o + k * s + j} (the inputs and outputs of an ADST
     * at their own offsets and strides).
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst164(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        vadst164p0(in, io, is, min, max, out, oo, os);
        vadst164p1(in, io, is, min, max, out, oo, os);
        vadst164p2(in, io, is, min, max, out, oo, os);
        vadst164p3(in, io, is, min, max, out, oo, os);
        vadst164p4(in, io, is, min, max, out, oo, os);
    }

    /**
     * Runs part 1 of 5 of {@link #vadst164}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst164p0(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        var low = IntVector.broadcast(I128, min);
        var high = IntVector.broadcast(I128, max);
        var in0 = INT128.cast(IntVector.fromArray(I128, in, io));
        var in1 = INT128.cast(IntVector.fromArray(I128, in, io + is));
        var in2 = INT128.cast(IntVector.fromArray(I128, in, io + 2 * is));
        var in3 = INT128.cast(IntVector.fromArray(I128, in, io + 3 * is));
        var in4 = INT128.cast(IntVector.fromArray(I128, in, io + 4 * is));
        var in5 = INT128.cast(IntVector.fromArray(I128, in, io + 5 * is));
        var in6 = INT128.cast(IntVector.fromArray(I128, in, io + 6 * is));
        var in7 = INT128.cast(IntVector.fromArray(I128, in, io + 7 * is));
        var in8 = INT128.cast(IntVector.fromArray(I128, in, io + 8 * is));
        var in9 = INT128.cast(IntVector.fromArray(I128, in, io + 9 * is));
        var in10 = INT128.cast(IntVector.fromArray(I128, in, io + 10 * is));
        var in11 = INT128.cast(IntVector.fromArray(I128, in, io + 11 * is));
        var in12 = INT128.cast(IntVector.fromArray(I128, in, io + 12 * is));
        var in13 = INT128.cast(IntVector.fromArray(I128, in, io + 13 * is));
        var in14 = INT128.cast(IntVector.fromArray(I128, in, io + 14 * is));
        var in15 = INT128.cast(IntVector.fromArray(I128, in, io + 15 * is));
        var t0 = in15.lanewise(VectorOperators.MUL, K4_M5)
                .lanewise(VectorOperators.ADD, in0.lanewise(VectorOperators.MUL, K4_201))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in15);
        var t1 = in15.lanewise(VectorOperators.MUL, K4_201)
                .lanewise(VectorOperators.SUB, in0.lanewise(VectorOperators.MUL, K4_M5))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in0);
        var t2 = in13.lanewise(VectorOperators.MUL, K4_M123)
                .lanewise(VectorOperators.ADD, in2.lanewise(VectorOperators.MUL, K4_995))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in13);
        var t3 = in13.lanewise(VectorOperators.MUL, K4_995)
                .lanewise(VectorOperators.SUB, in2.lanewise(VectorOperators.MUL, K4_M123))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in2);
        var t4 = in11.lanewise(VectorOperators.MUL, K4_M393)
                .lanewise(VectorOperators.ADD, in4.lanewise(VectorOperators.MUL, K4_1751))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in11);
        var t5 = in11.lanewise(VectorOperators.MUL, K4_1751)
                .lanewise(VectorOperators.SUB, in4.lanewise(VectorOperators.MUL, K4_M393))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, in4);
        var t6 = in9.lanewise(VectorOperators.MUL, K4_1645)
                .lanewise(VectorOperators.ADD, in6.lanewise(VectorOperators.MUL, K4_1220))
                .lanewise(VectorOperators.ADD, K4_1024).lanewise(VectorOperators.ASHR, 11);
        var t7 = in9.lanewise(VectorOperators.MUL, K4_1220)
                .lanewise(VectorOperators.SUB, in6.lanewise(VectorOperators.MUL, K4_1645))
                .lanewise(VectorOperators.ADD, K4_1024).lanewise(VectorOperators.ASHR, 11);
        var t8 = in7.lanewise(VectorOperators.MUL, K4_2751)
                .lanewise(VectorOperators.ADD, in8.lanewise(VectorOperators.MUL, K4_M1061))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in8);
        var t9 = in7.lanewise(VectorOperators.MUL, K4_M1061)
                .lanewise(VectorOperators.SUB, in8.lanewise(VectorOperators.MUL, K4_2751))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in7);
        var t10 = in5.lanewise(VectorOperators.MUL, K4_2106)
                .lanewise(VectorOperators.ADD, in10.lanewise(VectorOperators.MUL, K4_M583))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in10);
        var t11 = in5.lanewise(VectorOperators.MUL, K4_M583)
                .lanewise(VectorOperators.SUB, in10.lanewise(VectorOperators.MUL, K4_2106))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in5);
        var t12 = in3.lanewise(VectorOperators.MUL, K4_1380)
                .lanewise(VectorOperators.ADD, in12.lanewise(VectorOperators.MUL, K4_M239))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in12);
        var t13 = in3.lanewise(VectorOperators.MUL, K4_M239)
                .lanewise(VectorOperators.SUB, in12.lanewise(VectorOperators.MUL, K4_1380))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in3);
        var t14 = in1.lanewise(VectorOperators.MUL, K4_601)
                .lanewise(VectorOperators.ADD, in14.lanewise(VectorOperators.MUL, K4_M44))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in14);
        var t15 = in1.lanewise(VectorOperators.MUL, K4_M44)
                .lanewise(VectorOperators.SUB, in14.lanewise(VectorOperators.MUL, K4_601))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, in1);
        INT128.cast(t0).intoArray(in, io + 0 * is);
        INT128.cast(t1).intoArray(in, io + 1 * is);
        INT128.cast(t2).intoArray(in, io + 2 * is);
        INT128.cast(t3).intoArray(in, io + 3 * is);
        INT128.cast(t4).intoArray(in, io + 4 * is);
        INT128.cast(t5).intoArray(in, io + 5 * is);
        INT128.cast(t6).intoArray(in, io + 6 * is);
        INT128.cast(t7).intoArray(in, io + 7 * is);
        INT128.cast(t8).intoArray(in, io + 8 * is);
        INT128.cast(t9).intoArray(in, io + 9 * is);
        INT128.cast(t10).intoArray(in, io + 10 * is);
        INT128.cast(t11).intoArray(in, io + 11 * is);
        INT128.cast(t12).intoArray(in, io + 12 * is);
        INT128.cast(t13).intoArray(in, io + 13 * is);
        INT128.cast(t14).intoArray(in, io + 14 * is);
        INT128.cast(t15).intoArray(in, io + 15 * is);
    }

    /**
     * Runs part 2 of 5 of {@link #vadst164}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst164p1(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        var low = IntVector.broadcast(I128, min);
        var high = IntVector.broadcast(I128, max);
        var t0 = INT128.cast(IntVector.fromArray(I128, in, io + 0 * is));
        var t1 = INT128.cast(IntVector.fromArray(I128, in, io + 1 * is));
        var t2 = INT128.cast(IntVector.fromArray(I128, in, io + 2 * is));
        var t3 = INT128.cast(IntVector.fromArray(I128, in, io + 3 * is));
        var t4 = INT128.cast(IntVector.fromArray(I128, in, io + 4 * is));
        var t5 = INT128.cast(IntVector.fromArray(I128, in, io + 5 * is));
        var t6 = INT128.cast(IntVector.fromArray(I128, in, io + 6 * is));
        var t7 = INT128.cast(IntVector.fromArray(I128, in, io + 7 * is));
        var t8 = INT128.cast(IntVector.fromArray(I128, in, io + 8 * is));
        var t9 = INT128.cast(IntVector.fromArray(I128, in, io + 9 * is));
        var t10 = INT128.cast(IntVector.fromArray(I128, in, io + 10 * is));
        var t11 = INT128.cast(IntVector.fromArray(I128, in, io + 11 * is));
        var t12 = INT128.cast(IntVector.fromArray(I128, in, io + 12 * is));
        var t13 = INT128.cast(IntVector.fromArray(I128, in, io + 13 * is));
        var t14 = INT128.cast(IntVector.fromArray(I128, in, io + 14 * is));
        var t15 = INT128.cast(IntVector.fromArray(I128, in, io + 15 * is));
        var t0a = t0.lanewise(VectorOperators.ADD, t8).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t1a = t1.lanewise(VectorOperators.ADD, t9).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t2a = t2.lanewise(VectorOperators.ADD, t10).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t3a = t3.lanewise(VectorOperators.ADD, t11).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t4a = t4.lanewise(VectorOperators.ADD, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t5a = t5.lanewise(VectorOperators.ADD, t13).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t6a = t6.lanewise(VectorOperators.ADD, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t7a = t7.lanewise(VectorOperators.ADD, t15).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t8a = t0.lanewise(VectorOperators.SUB, t8).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t9a = t1.lanewise(VectorOperators.SUB, t9).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t10a = t2.lanewise(VectorOperators.SUB, t10).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t11a = t3.lanewise(VectorOperators.SUB, t11).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t12a = t4.lanewise(VectorOperators.SUB, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t13a = t5.lanewise(VectorOperators.SUB, t13).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t14a = t6.lanewise(VectorOperators.SUB, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t15a = t7.lanewise(VectorOperators.SUB, t15).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        INT128.cast(t0a).intoArray(in, io + 0 * is);
        INT128.cast(t1a).intoArray(in, io + 1 * is);
        INT128.cast(t2a).intoArray(in, io + 2 * is);
        INT128.cast(t3a).intoArray(in, io + 3 * is);
        INT128.cast(t4a).intoArray(in, io + 4 * is);
        INT128.cast(t5a).intoArray(in, io + 5 * is);
        INT128.cast(t6a).intoArray(in, io + 6 * is);
        INT128.cast(t7a).intoArray(in, io + 7 * is);
        INT128.cast(t8a).intoArray(in, io + 8 * is);
        INT128.cast(t9a).intoArray(in, io + 9 * is);
        INT128.cast(t10a).intoArray(in, io + 10 * is);
        INT128.cast(t11a).intoArray(in, io + 11 * is);
        INT128.cast(t12a).intoArray(in, io + 12 * is);
        INT128.cast(t13a).intoArray(in, io + 13 * is);
        INT128.cast(t14a).intoArray(in, io + 14 * is);
        INT128.cast(t15a).intoArray(in, io + 15 * is);
    }

    /**
     * Runs part 3 of 5 of {@link #vadst164}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst164p2(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        var low = IntVector.broadcast(I128, min);
        var high = IntVector.broadcast(I128, max);
        var t0a = INT128.cast(IntVector.fromArray(I128, in, io + 0 * is));
        var t1a = INT128.cast(IntVector.fromArray(I128, in, io + 1 * is));
        var t2a = INT128.cast(IntVector.fromArray(I128, in, io + 2 * is));
        var t3a = INT128.cast(IntVector.fromArray(I128, in, io + 3 * is));
        var t4a = INT128.cast(IntVector.fromArray(I128, in, io + 4 * is));
        var t5a = INT128.cast(IntVector.fromArray(I128, in, io + 5 * is));
        var t6a = INT128.cast(IntVector.fromArray(I128, in, io + 6 * is));
        var t7a = INT128.cast(IntVector.fromArray(I128, in, io + 7 * is));
        var t8a = INT128.cast(IntVector.fromArray(I128, in, io + 8 * is));
        var t9a = INT128.cast(IntVector.fromArray(I128, in, io + 9 * is));
        var t10a = INT128.cast(IntVector.fromArray(I128, in, io + 10 * is));
        var t11a = INT128.cast(IntVector.fromArray(I128, in, io + 11 * is));
        var t12a = INT128.cast(IntVector.fromArray(I128, in, io + 12 * is));
        var t13a = INT128.cast(IntVector.fromArray(I128, in, io + 13 * is));
        var t14a = INT128.cast(IntVector.fromArray(I128, in, io + 14 * is));
        var t15a = INT128.cast(IntVector.fromArray(I128, in, io + 15 * is));
        var t8 = t8a.lanewise(VectorOperators.MUL, K4_M79)
                .lanewise(VectorOperators.ADD, t9a.lanewise(VectorOperators.MUL, K4_799))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t8a);
        var t9 = t8a.lanewise(VectorOperators.MUL, K4_799)
                .lanewise(VectorOperators.SUB, t9a.lanewise(VectorOperators.MUL, K4_M79))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t9a);
        var t10 = t10a.lanewise(VectorOperators.MUL, K4_2276)
                .lanewise(VectorOperators.ADD, t11a.lanewise(VectorOperators.MUL, K4_M690))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t11a);
        var t11 = t10a.lanewise(VectorOperators.MUL, K4_M690)
                .lanewise(VectorOperators.SUB, t11a.lanewise(VectorOperators.MUL, K4_2276))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t10a);
        var t12 = t13a.lanewise(VectorOperators.MUL, K4_M79)
                .lanewise(VectorOperators.SUB, t12a.lanewise(VectorOperators.MUL, K4_799))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t13a);
        var t13 = t13a.lanewise(VectorOperators.MUL, K4_799)
                .lanewise(VectorOperators.ADD, t12a.lanewise(VectorOperators.MUL, K4_M79))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t12a);
        var t14 = t15a.lanewise(VectorOperators.MUL, K4_2276)
                .lanewise(VectorOperators.SUB, t14a.lanewise(VectorOperators.MUL, K4_M690))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t14a);
        var t15 = t15a.lanewise(VectorOperators.MUL, K4_M690)
                .lanewise(VectorOperators.ADD, t14a.lanewise(VectorOperators.MUL, K4_2276))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t15a);
        INT128.cast(t8).intoArray(in, io + 0 * is);
        INT128.cast(t9).intoArray(in, io + 1 * is);
        INT128.cast(t10).intoArray(in, io + 2 * is);
        INT128.cast(t11).intoArray(in, io + 3 * is);
        INT128.cast(t12).intoArray(in, io + 4 * is);
        INT128.cast(t13).intoArray(in, io + 5 * is);
        INT128.cast(t14).intoArray(in, io + 6 * is);
        INT128.cast(t15).intoArray(in, io + 7 * is);
        INT128.cast(t0a).intoArray(in, io + 8 * is);
        INT128.cast(t1a).intoArray(in, io + 9 * is);
        INT128.cast(t2a).intoArray(in, io + 10 * is);
        INT128.cast(t3a).intoArray(in, io + 11 * is);
        INT128.cast(t4a).intoArray(in, io + 12 * is);
        INT128.cast(t5a).intoArray(in, io + 13 * is);
        INT128.cast(t6a).intoArray(in, io + 14 * is);
        INT128.cast(t7a).intoArray(in, io + 15 * is);
    }

    /**
     * Runs part 4 of 5 of {@link #vadst164}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst164p3(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        var low = IntVector.broadcast(I128, min);
        var high = IntVector.broadcast(I128, max);
        var t8 = INT128.cast(IntVector.fromArray(I128, in, io + 0 * is));
        var t9 = INT128.cast(IntVector.fromArray(I128, in, io + 1 * is));
        var t10 = INT128.cast(IntVector.fromArray(I128, in, io + 2 * is));
        var t11 = INT128.cast(IntVector.fromArray(I128, in, io + 3 * is));
        var t12 = INT128.cast(IntVector.fromArray(I128, in, io + 4 * is));
        var t13 = INT128.cast(IntVector.fromArray(I128, in, io + 5 * is));
        var t14 = INT128.cast(IntVector.fromArray(I128, in, io + 6 * is));
        var t15 = INT128.cast(IntVector.fromArray(I128, in, io + 7 * is));
        var t0a = INT128.cast(IntVector.fromArray(I128, in, io + 8 * is));
        var t1a = INT128.cast(IntVector.fromArray(I128, in, io + 9 * is));
        var t2a = INT128.cast(IntVector.fromArray(I128, in, io + 10 * is));
        var t3a = INT128.cast(IntVector.fromArray(I128, in, io + 11 * is));
        var t4a = INT128.cast(IntVector.fromArray(I128, in, io + 12 * is));
        var t5a = INT128.cast(IntVector.fromArray(I128, in, io + 13 * is));
        var t6a = INT128.cast(IntVector.fromArray(I128, in, io + 14 * is));
        var t7a = INT128.cast(IntVector.fromArray(I128, in, io + 15 * is));
        var t0 = t0a.lanewise(VectorOperators.ADD, t4a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t1 = t1a.lanewise(VectorOperators.ADD, t5a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t2 = t2a.lanewise(VectorOperators.ADD, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t3 = t3a.lanewise(VectorOperators.ADD, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t4 = t0a.lanewise(VectorOperators.SUB, t4a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t5 = t1a.lanewise(VectorOperators.SUB, t5a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t6 = t2a.lanewise(VectorOperators.SUB, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t7 = t3a.lanewise(VectorOperators.SUB, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t8a = t8.lanewise(VectorOperators.ADD, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t9a = t9.lanewise(VectorOperators.ADD, t13).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t10a = t10.lanewise(VectorOperators.ADD, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t11a = t11.lanewise(VectorOperators.ADD, t15).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t12a = t8.lanewise(VectorOperators.SUB, t12).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t13a = t9.lanewise(VectorOperators.SUB, t13).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t14a = t10.lanewise(VectorOperators.SUB, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t15a = t11.lanewise(VectorOperators.SUB, t15).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        t4a = t4.lanewise(VectorOperators.MUL, K4_M312)
                .lanewise(VectorOperators.ADD, t5.lanewise(VectorOperators.MUL, K4_1567))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t4);
        t5a = t4.lanewise(VectorOperators.MUL, K4_1567)
                .lanewise(VectorOperators.SUB, t5.lanewise(VectorOperators.MUL, K4_M312))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t5);
        t6a = t7.lanewise(VectorOperators.MUL, K4_M312)
                .lanewise(VectorOperators.SUB, t6.lanewise(VectorOperators.MUL, K4_1567))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t7);
        t7a = t7.lanewise(VectorOperators.MUL, K4_1567)
                .lanewise(VectorOperators.ADD, t6.lanewise(VectorOperators.MUL, K4_M312))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t6);
        t12 = t12a.lanewise(VectorOperators.MUL, K4_M312)
                .lanewise(VectorOperators.ADD, t13a.lanewise(VectorOperators.MUL, K4_1567))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t12a);
        t13 = t12a.lanewise(VectorOperators.MUL, K4_1567)
                .lanewise(VectorOperators.SUB, t13a.lanewise(VectorOperators.MUL, K4_M312))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.SUB, t13a);
        t14 = t15a.lanewise(VectorOperators.MUL, K4_M312)
                .lanewise(VectorOperators.SUB, t14a.lanewise(VectorOperators.MUL, K4_1567))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t15a);
        t15 = t15a.lanewise(VectorOperators.MUL, K4_1567)
                .lanewise(VectorOperators.ADD, t14a.lanewise(VectorOperators.MUL, K4_M312))
                .lanewise(VectorOperators.ADD, K4_2048).lanewise(VectorOperators.ASHR, 12)
                .lanewise(VectorOperators.ADD, t14a);
        INT128.cast(t0).intoArray(in, io + 0 * is);
        INT128.cast(t1).intoArray(in, io + 1 * is);
        INT128.cast(t2).intoArray(in, io + 2 * is);
        INT128.cast(t3).intoArray(in, io + 3 * is);
        INT128.cast(t12).intoArray(in, io + 4 * is);
        INT128.cast(t13).intoArray(in, io + 5 * is);
        INT128.cast(t14).intoArray(in, io + 6 * is);
        INT128.cast(t15).intoArray(in, io + 7 * is);
        INT128.cast(t4a).intoArray(in, io + 8 * is);
        INT128.cast(t5a).intoArray(in, io + 9 * is);
        INT128.cast(t6a).intoArray(in, io + 10 * is);
        INT128.cast(t7a).intoArray(in, io + 11 * is);
        INT128.cast(t8a).intoArray(in, io + 12 * is);
        INT128.cast(t9a).intoArray(in, io + 13 * is);
        INT128.cast(t10a).intoArray(in, io + 14 * is);
        INT128.cast(t11a).intoArray(in, io + 15 * is);
    }

    /**
     * Runs part 5 of 5 of {@link #vadst164}, passing its live values to the next part in the
     * array elements whose inputs it has consumed.
     *
     * @param in the inputs
     * @param io the index of the first input of the first lane
     * @param is the distance between consecutive inputs of a lane
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @param out the outputs, which may be the inputs
     * @param oo the index of the first output of the first lane
     * @param os the distance between consecutive outputs of a lane, negative for a flipped ADST
     */
    private static void vadst164p4(int[] in, int io, int is, int min, int max, int[] out, int oo, int os) {
        var low = IntVector.broadcast(I128, min);
        var high = IntVector.broadcast(I128, max);
        var t0 = INT128.cast(IntVector.fromArray(I128, in, io + 0 * is));
        var t1 = INT128.cast(IntVector.fromArray(I128, in, io + 1 * is));
        var t2 = INT128.cast(IntVector.fromArray(I128, in, io + 2 * is));
        var t3 = INT128.cast(IntVector.fromArray(I128, in, io + 3 * is));
        var t12 = INT128.cast(IntVector.fromArray(I128, in, io + 4 * is));
        var t13 = INT128.cast(IntVector.fromArray(I128, in, io + 5 * is));
        var t14 = INT128.cast(IntVector.fromArray(I128, in, io + 6 * is));
        var t15 = INT128.cast(IntVector.fromArray(I128, in, io + 7 * is));
        var t4a = INT128.cast(IntVector.fromArray(I128, in, io + 8 * is));
        var t5a = INT128.cast(IntVector.fromArray(I128, in, io + 9 * is));
        var t6a = INT128.cast(IntVector.fromArray(I128, in, io + 10 * is));
        var t7a = INT128.cast(IntVector.fromArray(I128, in, io + 11 * is));
        var t8a = INT128.cast(IntVector.fromArray(I128, in, io + 12 * is));
        var t9a = INT128.cast(IntVector.fromArray(I128, in, io + 13 * is));
        var t10a = INT128.cast(IntVector.fromArray(I128, in, io + 14 * is));
        var t11a = INT128.cast(IntVector.fromArray(I128, in, io + 15 * is));
        INT128.cast(t0.lanewise(VectorOperators.ADD, t2).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(out, oo);
        INT128.cast(t1.lanewise(VectorOperators.ADD, t3).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high).lanewise(VectorOperators.NEG)).intoArray(out, oo + 15 * os);
        var t2a = t0.lanewise(VectorOperators.SUB, t2).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t3a = t1.lanewise(VectorOperators.SUB, t3).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        INT128.cast(t4a.lanewise(VectorOperators.ADD, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high).lanewise(VectorOperators.NEG)).intoArray(out, oo + 3 * os);
        INT128.cast(t5a.lanewise(VectorOperators.ADD, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(out, oo + 12 * os);
        var t6 = t4a.lanewise(VectorOperators.SUB, t6a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t7 = t5a.lanewise(VectorOperators.SUB, t7a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        INT128.cast(t8a.lanewise(VectorOperators.ADD, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high).lanewise(VectorOperators.NEG)).intoArray(out, oo + os);
        INT128.cast(t9a.lanewise(VectorOperators.ADD, t11a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(out, oo + 14 * os);
        var t10 = t8a.lanewise(VectorOperators.SUB, t10a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t11 = t9a.lanewise(VectorOperators.SUB, t11a).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        INT128.cast(t12.lanewise(VectorOperators.ADD, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high)).intoArray(out, oo + 2 * os);
        INT128.cast(t13.lanewise(VectorOperators.ADD, t15).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high).lanewise(VectorOperators.NEG)).intoArray(out, oo + 13 * os);
        var t14a = t12.lanewise(VectorOperators.SUB, t14).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var t15a = t13.lanewise(VectorOperators.SUB, t15).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        INT128.cast(t2a.lanewise(VectorOperators.ADD, t3a).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8).lanewise(VectorOperators.NEG))
                .intoArray(out, oo + 7 * os);
        INT128.cast(t2a.lanewise(VectorOperators.SUB, t3a).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8)).intoArray(out, oo + 8 * os);
        INT128.cast(t6.lanewise(VectorOperators.ADD, t7).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8)).intoArray(out, oo + 4 * os);
        INT128.cast(t6.lanewise(VectorOperators.SUB, t7).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8).lanewise(VectorOperators.NEG))
                .intoArray(out, oo + 11 * os);
        INT128.cast(t10.lanewise(VectorOperators.ADD, t11).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8)).intoArray(out, oo + 6 * os);
        INT128.cast(t10.lanewise(VectorOperators.SUB, t11).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8).lanewise(VectorOperators.NEG))
                .intoArray(out, oo + 9 * os);
        INT128.cast(t14a.lanewise(VectorOperators.ADD, t15a).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8).lanewise(VectorOperators.NEG))
                .intoArray(out, oo + 5 * os);
        INT128.cast(t14a.lanewise(VectorOperators.SUB, t15a).lanewise(VectorOperators.MUL, K4_181)
                .lanewise(VectorOperators.ADD, K4_128).lanewise(VectorOperators.ASHR, 8)).intoArray(out, oo + 10 * os);
    }

    /**
     * Runs {@code identity4} of {@link ScalarItxDsp} on 4 transforms at once, one per lane.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     */
    private static void videntity44(int[] c, int o, int s) {
        for (var i = 0; i < 4; i++) {
            var v = INT128.cast(IntVector.fromArray(I128, c, o + i * s));
            INT128.cast(v.lanewise(VectorOperators.ADD, v.lanewise(VectorOperators.MUL, K4_1697)
                    .lanewise(VectorOperators.ADD, K4_2048)
                    .lanewise(VectorOperators.ASHR, 12))).intoArray(c, o + i * s);
        }
    }

    /**
     * Runs {@code identity8} of {@link ScalarItxDsp} on 4 transforms at once, one per lane.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     */
    private static void videntity84(int[] c, int o, int s) {
        for (var i = 0; i < 8; i++) {
            var v = INT128.cast(IntVector.fromArray(I128, c, o + i * s));
            INT128.cast(v.lanewise(VectorOperators.LSHL, 1)).intoArray(c, o + i * s);
        }
    }

    /**
     * Runs {@code identity16} of {@link ScalarItxDsp} on 4 transforms at once, one per lane.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     */
    private static void videntity164(int[] c, int o, int s) {
        for (var i = 0; i < 16; i++) {
            var v = INT128.cast(IntVector.fromArray(I128, c, o + i * s));
            INT128.cast(v.lanewise(VectorOperators.LSHL, 1)
                    .lanewise(VectorOperators.ADD, v.lanewise(VectorOperators.MUL, K4_1697)
                    .lanewise(VectorOperators.ADD, K4_1024).lanewise(VectorOperators.ASHR, 11)))
                            .intoArray(c, o + i * s);
        }
    }

    /**
     * Runs {@code identity32} of {@link ScalarItxDsp} on 4 transforms at once, one per lane.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     */
    private static void videntity324(int[] c, int o, int s) {
        for (var i = 0; i < 32; i++) {
            var v = INT128.cast(IntVector.fromArray(I128, c, o + i * s));
            INT128.cast(v.lanewise(VectorOperators.LSHL, 2)).intoArray(c, o + i * s);
        }
    }
}
