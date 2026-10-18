package com.github.auties00.smalt.video.av1.internal;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements the hottest intra prediction kernels with the Vector API, producing output identical
 * to {@link ScalarIpredDsp}: the constant fills, the smooth predictors of blocks at least 8
 * samples wide and the recursive filter predictor. Every other mode, chroma-from-luma and palette
 * prediction run through {@link ScalarIpredDsp}.
 *
 * <p>Every Vector API operation goes through the per-shape methods, constants are broadcast once
 * per call, no method takes or returns a vector, and every loaded or converted vector, and every
 * vector stored or shifted by a scalar, is first cast through the {@code static final} class of
 * its species, so the JIT compiler knows its exact class without a type profile.
 */
public final class VectorIpredDsp extends IpredDsp {
    /**
     * The 16-lane short species of wide fills.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * The 8-lane short species of 8-wide fills and of the packed results of 8 integer lanes.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * The 4-lane short species of 4-wide fills and of the packed results of 4 integer lanes.
     */
    private static final VectorSpecies<Short> S64 = ShortVector.SPECIES_64;

    /**
     * The 8-lane int species of the smooth predictors.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * The 4-lane int species of the filter predictor.
     */
    private static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;

    /**
     * The exact class of {@link #S256} vectors.
     */
    private static final Class<ShortVector> SHORT256 = vectorClass(S256);

    /**
     * The exact class of {@link #S128} vectors.
     */
    private static final Class<ShortVector> SHORT128 = vectorClass(S128);

    /**
     * The exact class of {@link #S64} vectors.
     */
    private static final Class<ShortVector> SHORT64 = vectorClass(S64);

    /**
     * The exact class of {@link #I256} vectors.
     */
    private static final Class<IntVector> INT256 = vectorClass(I256);

    /**
     * The exact class of {@link #I128} vectors.
     */
    private static final Class<IntVector> INT128 = vectorClass(I128);

    /**
     * The number of filter predictor modes.
     */
    private static final int FILTER_MODES = 5;

    /**
     * The filter predictor taps regrouped by tap: for each mode, output row and tap, the four
     * weights of the row's four outputs, at index {@code ((mode * 2 + row) * 7 + tap) * 4 + column}.
     */
    private static final int[] FILTER_TAPS = regroupFilterTaps();

    /**
     * The kernels used for the modes this class does not vectorize.
     */
    private final ScalarIpredDsp scalar;

    /**
     * Constructs the kernels.
     */
    public VectorIpredDsp() {
        this.scalar = new ScalarIpredDsp();
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

    /**
     * Regroups {@link IpredTables#FILTER_INTRA_TAPS}, stored per output, into vectors of one tap
     * across the four outputs of a row.
     *
     * @return the regrouped table
     */
    private static int[] regroupFilterTaps() {
        var taps = IpredTables.FILTER_INTRA_TAPS;
        var result = new int[FILTER_MODES * 2 * 7 * 4];
        for (var mode = 0; mode < FILTER_MODES; mode++) {
            for (var row = 0; row < 2; row++) {
                for (var tap = 0; tap < 7; tap++) {
                    for (var column = 0; column < 4; column++) {
                        result[((mode * 2 + row) * 7 + tap) * 4 + column] =
                                taps[mode * 56 + (row * 4 + column) * 7 + tap];
                    }
                }
            }
        }
        return result;
    }

    @Override
    public void intraPred(int mode, short[] dst, int dstOff, int stride, short[] topleft, int topleftOff, int width,
                          int height, int angle, int maxWidth, int maxHeight, short[] scratch, int bitdepthMax) {
        switch (mode) {
            case DC_PRED -> splat(dst, dstOff, stride, width, height,
                    ScalarIpredDsp.dcGen(topleft, topleftOff, width, height, bitdepthMax));
            case LEFT_DC_PRED -> splat(dst, dstOff, stride, width, height,
                    ScalarIpredDsp.dcGenLeft(topleft, topleftOff, height));
            case TOP_DC_PRED -> splat(dst, dstOff, stride, width, height,
                    ScalarIpredDsp.dcGenTop(topleft, topleftOff, width));
            case DC_128_PRED -> splat(dst, dstOff, stride, width, height, (bitdepthMax + 1) >> 1);
            case SMOOTH_PRED, SMOOTH_V_PRED, SMOOTH_H_PRED -> {
                if (width < 8) {
                    scalar.intraPred(mode, dst, dstOff, stride, topleft, topleftOff, width, height, angle, maxWidth,
                            maxHeight, scratch, bitdepthMax);
                } else if (mode == SMOOTH_PRED) {
                    smooth(dst, dstOff, stride, topleft, topleftOff, width, height);
                } else if (mode == SMOOTH_V_PRED) {
                    smoothV(dst, dstOff, stride, topleft, topleftOff, width, height);
                } else {
                    smoothH(dst, dstOff, stride, topleft, topleftOff, width, height);
                }
            }
            case FILTER_PRED -> filter(dst, dstOff, stride, topleft, topleftOff, width, height, angle, bitdepthMax);
            default -> scalar.intraPred(mode, dst, dstOff, stride, topleft, topleftOff, width, height, angle,
                    maxWidth, maxHeight, scratch, bitdepthMax);
        }
    }

    @Override
    public void cflAc(int layout, short[] ac, short[] y, int yOff, int stride, int wPad, int hPad, int cw, int ch) {
        scalar.cflAc(layout, ac, y, yOff, stride, wPad, hPad, cw, ch);
    }

    @Override
    public void cflPred(int mode, short[] dst, int dstOff, int stride, short[] topleft, int topleftOff, int width,
                        int height, short[] ac, int alpha, int bitdepthMax) {
        scalar.cflPred(mode, dst, dstOff, stride, topleft, topleftOff, width, height, ac, alpha, bitdepthMax);
    }

    @Override
    public void palPred(short[] dst, int dstOff, int stride, short[] pal, int palOff, byte[] idx, int idxOff, int w,
                        int h) {
        scalar.palPred(dst, dstOff, stride, pal, palOff, idx, idxOff, w, h);
    }

    /**
     * Fills a block with one value.
     *
     * @param dst    the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the plane's row stride
     * @param width  the block width, 4 or a multiple of 8
     * @param height the block height
     * @param value  the sample written everywhere
     */
    private static void splat(short[] dst, int dstOff, int stride, int width, int height, int value) {
        if (width >= 16) {
            var fill = ShortVector.broadcast(S256, (short) value);
            for (var y = 0; y < height; y++, dstOff += stride) {
                for (var x = 0; x < width; x += 16) {
                    SHORT256.cast(fill).intoArray(dst, dstOff + x);
                }
            }
        } else if (width == 8) {
            var fill = ShortVector.broadcast(S128, (short) value);
            for (var y = 0; y < height; y++, dstOff += stride) {
                SHORT128.cast(fill).intoArray(dst, dstOff);
            }
        } else {
            var fill = ShortVector.broadcast(S64, (short) value);
            for (var y = 0; y < height; y++, dstOff += stride) {
                SHORT64.cast(fill).intoArray(dst, dstOff);
            }
        }
    }

    /**
     * Predicts a block with the smooth predictor, 8 samples at a time.
     *
     * @param dst    the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the plane's row stride
     * @param tl     the edge buffer
     * @param tlOff  the index of the top-left sample in the edge buffer
     * @param width  the block width, a multiple of 8
     * @param height the block height
     */
    private static void smooth(short[] dst, int dstOff, int stride, short[] tl, int tlOff, int width, int height) {
        var weights = IpredTables.SM_WEIGHTS;
        int right = tl[tlOff + width];
        int bottom = tl[tlOff - height];
        for (var y = 0; y < height; y++, dstOff += stride) {
            var wv = weights[height + y];
            int left = tl[tlOff - (1 + y)];
            var weightV = IntVector.broadcast(I256, wv);
            var weightH = IntVector.broadcast(I256, left - right);
            var constant = IntVector.broadcast(I256, (256 - wv) * bottom + 256 * right + 256);
            for (var x = 0; x < width; x += 8) {
                var top = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tl, tlOff + 1 + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var wh = IntVector.fromArray(I256, weights, width + x);
                var pred = top.lanewise(VectorOperators.MUL, INT256.cast(weightV))
                        .lanewise(VectorOperators.ADD, INT256.cast(wh).lanewise(VectorOperators.MUL,
                                INT256.cast(weightH)))
                        .lanewise(VectorOperators.ADD, INT256.cast(constant));
                SHORT128.cast(INT256.cast(pred).lanewise(VectorOperators.ASHR, 9)
                                .convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, dstOff + x);
            }
        }
    }

    /**
     * Predicts a block with the vertical smooth predictor, 8 samples at a time.
     *
     * @param dst    the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the plane's row stride
     * @param tl     the edge buffer
     * @param tlOff  the index of the top-left sample in the edge buffer
     * @param width  the block width, a multiple of 8
     * @param height the block height
     */
    private static void smoothV(short[] dst, int dstOff, int stride, short[] tl, int tlOff, int width, int height) {
        var weights = IpredTables.SM_WEIGHTS;
        int bottom = tl[tlOff - height];
        for (var y = 0; y < height; y++, dstOff += stride) {
            var wv = weights[height + y];
            var weightV = IntVector.broadcast(I256, wv);
            var constant = IntVector.broadcast(I256, (256 - wv) * bottom + 128);
            for (var x = 0; x < width; x += 8) {
                var top = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tl, tlOff + 1 + x))
                        .convertShape(VectorOperators.S2I, I256, 0));
                var pred = top.lanewise(VectorOperators.MUL, INT256.cast(weightV))
                        .lanewise(VectorOperators.ADD, INT256.cast(constant));
                SHORT128.cast(INT256.cast(pred).lanewise(VectorOperators.ASHR, 8)
                                .convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, dstOff + x);
            }
        }
    }

    /**
     * Predicts a block with the horizontal smooth predictor, 8 samples at a time.
     *
     * @param dst    the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the plane's row stride
     * @param tl     the edge buffer
     * @param tlOff  the index of the top-left sample in the edge buffer
     * @param width  the block width, a multiple of 8
     * @param height the block height
     */
    private static void smoothH(short[] dst, int dstOff, int stride, short[] tl, int tlOff, int width, int height) {
        var weights = IpredTables.SM_WEIGHTS;
        int right = tl[tlOff + width];
        for (var y = 0; y < height; y++, dstOff += stride) {
            int left = tl[tlOff - (y + 1)];
            var weightH = IntVector.broadcast(I256, left - right);
            var constant = IntVector.broadcast(I256, 256 * right + 128);
            for (var x = 0; x < width; x += 8) {
                var wh = IntVector.fromArray(I256, weights, width + x);
                var pred = INT256.cast(wh).lanewise(VectorOperators.MUL, INT256.cast(weightH))
                        .lanewise(VectorOperators.ADD, INT256.cast(constant));
                SHORT128.cast(INT256.cast(pred).lanewise(VectorOperators.ASHR, 8)
                                .convertShape(VectorOperators.I2S, S128, 0))
                        .intoArray(dst, dstOff + x);
            }
        }
    }

    /**
     * Predicts a block with the recursive filter predictor: each 4x2 block is a weighted sum of
     * the seven neighboring samples, the top-left, four top and two left ones, whose results the
     * following blocks use as their neighbors.
     *
     * @param dst         the destination plane
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the plane's row stride
     * @param tl          the edge buffer
     * @param tlOff       the index of the top-left sample in the edge buffer
     * @param width       the block width, a multiple of 4
     * @param height      the block height, even
     * @param filterIndex the filter mode in its low 9 bits, 0 to 4
     * @param bitdepthMax the largest sample value
     */
    private static void filter(short[] dst, int dstOff, int stride, short[] tl, int tlOff, int width, int height,
                               int filterIndex, int bitdepthMax) {
        var taps = FILTER_TAPS;
        var base = ((filterIndex & 511) * 2) * 7 * 4;
        var round = IntVector.broadcast(I128, 8);
        var zero = IntVector.zero(I128);
        var high = IntVector.broadcast(I128, bitdepthMax);
        var topArr = tl;
        var topOff = tlOff + 1;
        for (var y = 0; y < height; y += 2) {
            var cornerArr = tl;
            var cornerOff = tlOff - y;
            var leftArr = tl;
            var leftOff = cornerOff - 1;
            var leftStride = -1;
            for (var x = 0; x < width; x += 4) {
                var p0 = IntVector.broadcast(I128, cornerArr[cornerOff]);
                var p1 = IntVector.broadcast(I128, topArr[topOff]);
                var p2 = IntVector.broadcast(I128, topArr[topOff + 1]);
                var p3 = IntVector.broadcast(I128, topArr[topOff + 2]);
                var p4 = IntVector.broadcast(I128, topArr[topOff + 3]);
                var p5 = IntVector.broadcast(I128, leftArr[leftOff]);
                var p6 = IntVector.broadcast(I128, leftArr[leftOff + leftStride]);
                var first = INT128.cast(p0).lanewise(VectorOperators.MUL,
                                INT128.cast(IntVector.fromArray(I128, taps, base)))
                        .lanewise(VectorOperators.ADD, INT128.cast(p1).lanewise(VectorOperators.MUL,
                                INT128.cast(IntVector.fromArray(I128, taps, base + 4))))
                        .lanewise(VectorOperators.ADD, INT128.cast(p2).lanewise(VectorOperators.MUL,
                                INT128.cast(IntVector.fromArray(I128, taps, base + 8))))
                        .lanewise(VectorOperators.ADD, INT128.cast(p3).lanewise(VectorOperators.MUL,
                                INT128.cast(IntVector.fromArray(I128, taps, base + 12))))
                        .lanewise(VectorOperators.ADD, INT128.cast(p4).lanewise(VectorOperators.MUL,
                                INT128.cast(IntVector.fromArray(I128, taps, base + 16))))
                        .lanewise(VectorOperators.ADD, INT128.cast(p5).lanewise(VectorOperators.MUL,
                                INT128.cast(IntVector.fromArray(I128, taps, base + 20))))
                        .lanewise(VectorOperators.ADD, INT128.cast(p6).lanewise(VectorOperators.MUL,
                                INT128.cast(IntVector.fromArray(I128, taps, base + 24))))
                        .lanewise(VectorOperators.ADD, INT128.cast(round));
                var second = INT128.cast(p0).lanewise(VectorOperators.MUL,
                                INT128.cast(IntVector.fromArray(I128, taps, base + 28)))
                        .lanewise(VectorOperators.ADD, INT128.cast(p1).lanewise(VectorOperators.MUL,
                                INT128.cast(IntVector.fromArray(I128, taps, base + 32))))
                        .lanewise(VectorOperators.ADD, INT128.cast(p2).lanewise(VectorOperators.MUL,
                                INT128.cast(IntVector.fromArray(I128, taps, base + 36))))
                        .lanewise(VectorOperators.ADD, INT128.cast(p3).lanewise(VectorOperators.MUL,
                                INT128.cast(IntVector.fromArray(I128, taps, base + 40))))
                        .lanewise(VectorOperators.ADD, INT128.cast(p4).lanewise(VectorOperators.MUL,
                                INT128.cast(IntVector.fromArray(I128, taps, base + 44))))
                        .lanewise(VectorOperators.ADD, INT128.cast(p5).lanewise(VectorOperators.MUL,
                                INT128.cast(IntVector.fromArray(I128, taps, base + 48))))
                        .lanewise(VectorOperators.ADD, INT128.cast(p6).lanewise(VectorOperators.MUL,
                                INT128.cast(IntVector.fromArray(I128, taps, base + 52))))
                        .lanewise(VectorOperators.ADD, INT128.cast(round));
                var ptr = dstOff + x;
                SHORT64.cast(INT128.cast(first).lanewise(VectorOperators.ASHR, 4)
                                .lanewise(VectorOperators.MAX, INT128.cast(zero))
                                .lanewise(VectorOperators.MIN, INT128.cast(high))
                                .convertShape(VectorOperators.I2S, S64, 0))
                        .intoArray(dst, ptr);
                SHORT64.cast(INT128.cast(second).lanewise(VectorOperators.ASHR, 4)
                                .lanewise(VectorOperators.MAX, INT128.cast(zero))
                                .lanewise(VectorOperators.MIN, INT128.cast(high))
                                .convertShape(VectorOperators.I2S, S64, 0))
                        .intoArray(dst, ptr + stride);
                leftArr = dst;
                leftOff = dstOff + x + 3;
                leftStride = stride;
                topOff += 4;
                cornerArr = topArr;
                cornerOff = topOff - 1;
            }
            topArr = dst;
            topOff = dstOff + stride;
            dstOff += stride * 2;
        }
    }
}
