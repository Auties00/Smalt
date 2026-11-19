/* Ports jxl-oxide 0.12.6 (crates/jxl-modular: the predictors). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. The gradient lookup-table rule ports libjxl 0.11.2 (lib/jxl/modular/encoding/encoding.cc FilterTree
   and the gradient fast track, encoding.h TreeToLookupTable). Copyright the JPEG XL Project Authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.modular;

/**
 * Holds the fixed predictors of modular images (ISO/IEC 18181-1 table H.3).
 *
 * <p>Every method is a pure function of its arguments.
 */
final class Predictors {
    /**
     * Predicts zero.
     */
    static final int ZERO = 0;

    /**
     * Predicts the west sample.
     */
    static final int WEST = 1;

    /**
     * Predicts the north sample.
     */
    static final int NORTH = 2;

    /**
     * Predicts the mean of west and north.
     */
    static final int AVG_W_N = 3;

    /**
     * Predicts west or north, whichever is closer to the gradient.
     */
    static final int SELECT = 4;

    /**
     * Predicts the clamped gradient {@code W + N - NW}.
     */
    static final int GRADIENT = 5;

    /**
     * Predicts with the weighted (self-correcting) predictor.
     */
    static final int WEIGHTED = 6;

    /**
     * Predicts the north-east sample.
     */
    static final int NORTH_EAST = 7;

    /**
     * Predicts the north-west sample.
     */
    static final int NORTH_WEST = 8;

    /**
     * Predicts the west-west sample.
     */
    static final int WEST_WEST = 9;

    /**
     * Predicts the mean of west and north-west.
     */
    static final int AVG_W_NW = 10;

    /**
     * Predicts the mean of north and north-west.
     */
    static final int AVG_N_NW = 11;

    /**
     * Predicts the mean of north and north-east.
     */
    static final int AVG_N_NE = 12;

    /**
     * Predicts a weighted mean of six neighbors.
     */
    static final int AVG_ALL = 13;

    /**
     * Prevents instantiation.
     */
    private Predictors() {
        throw new AssertionError();
    }

    /**
     * The bound of the gradient property under a lookup-table tree: libjxl's decoder saturates the property to
     * {@code [-LOOKUP_RANGE, LOOKUP_RANGE - 1]}.
     */
    static final int LOOKUP_RANGE = 8192;

    /**
     * Returns whether a channel's tree is one libjxl's decoder reads through its gradient lookup table: every
     * split reachable from {@code root}, once the static properties are resolved, is on the gradient property
     * (9) with a value from {@code -LOOKUP_RANGE - 1} to {@code LOOKUP_RANGE - 2}, and every reachable leaf is a
     * gradient predictor without offset and with multiplier 1.
     *
     * <p>Such a decoder computes the property {@code W + N - NW} without 32-bit wrap-around and saturates it to
     * the table's range, where the general path wraps it to 32 bits; the two differ only when the sum overflows
     * (samples whose range spans 2^31, as 32-bit floating-point samples do). Both the encoder and the decoder
     * compute the property the way libjxl's decoder does, through {@link #lookupGradient(int, int, int)}, when
     * this returns {@code true}.
     *
     * @param property the property of each node, negative for a leaf
     * @param value the split value of each node, or the offset of a leaf
     * @param left the child taken when the property exceeds the value
     * @param right the other child, or the predictor of a leaf
     * @param multiplier the multiplier of each leaf
     * @param root the first node of the channel
     * @param channel the channel index, static property 0
     * @param stream the stream index, static property 1
     * @return whether the gradient lookup table reads the channel
     */
    static boolean usesGradientLookup(int[] property, int[] value, int[] left, int[] right, int[] multiplier,
                                      int root, int channel, int stream) {
        var pending = new java.util.ArrayDeque<Integer>();
        pending.push(root);
        var splits = false;
        while (!pending.isEmpty()) {
            var node = pending.pop();
            while (property[node] == 0 || property[node] == 1) {
                var v = property[node] == 0 ? channel : stream;
                node = v > value[node] ? left[node] : right[node];
            }
            if (property[node] < 0) {
                if (right[node] != GRADIENT || value[node] != 0 || multiplier[node] != 1) {
                    return false;
                }
                continue;
            }
            if (property[node] != 9 || value[node] < -LOOKUP_RANGE - 1 || value[node] > LOOKUP_RANGE - 2) {
                return false;
            }
            splits = true;
            pending.push(left[node]);
            pending.push(right[node]);
        }
        return splits;
    }

    /**
     * Computes the gradient property {@code W + N - NW} as libjxl's gradient lookup table reads it: exactly,
     * saturated to {@code [-LOOKUP_RANGE, LOOKUP_RANGE - 1]}.
     *
     * @param n the north sample
     * @param w the west sample
     * @param nw the north-west sample
     * @return the property
     */
    static int lookupGradient(int n, int w, int nw) {
        return (int) Math.clamp((long) w - nw + n, -LOOKUP_RANGE, LOOKUP_RANGE - 1);
    }

    /**
     * Computes the clamped gradient {@code clamp(W + N - NW, min(W, N), max(W, N))}.
     *
     * @param n the north sample
     * @param w the west sample
     * @param nw the north-west sample
     * @return the prediction
     */
    static int gradient(int n, int w, int nw) {
        long lo = Math.min(n, w);
        long hi = Math.max(n, w);
        var g = (long) n + w - nw;
        return (int) (g < lo ? lo : Math.min(g, hi));
    }

    /**
     * Computes a non-weighted prediction.
     *
     * @param predictor the predictor, any constant of this class but {@link #WEIGHTED}
     * @param n the north sample
     * @param w the west sample
     * @param nw the north-west sample
     * @param ne the north-east sample
     * @param nn the north-north sample
     * @param ww the west-west sample
     * @param nee the north-east-east sample
     * @return the prediction
     */
    static int predict(int predictor, int n, int w, int nw, int ne, int nn, int ww, int nee) {
        return switch (predictor) {
            case ZERO -> 0;
            case WEST -> w;
            case NORTH -> n;
            case AVG_W_N -> (int) (((long) w + n) / 2);
            case SELECT -> Math.abs((long) n - nw) < Math.abs((long) w - nw) ? w : n;
            case GRADIENT -> gradient(n, w, nw);
            case NORTH_EAST -> ne;
            case NORTH_WEST -> nw;
            case WEST_WEST -> ww;
            case AVG_W_NW -> (int) (((long) w + nw) / 2);
            case AVG_N_NW -> (int) (((long) n + nw) / 2);
            case AVG_N_NE -> (int) (((long) n + ne) / 2);
            default -> (int) ((6L * n - 2L * nn + 7L * w + ww + nee + 3L * ne + 8) / 16);
        };
    }
}
