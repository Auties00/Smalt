/* Ports jxl-oxide 0.12.6 (crates/jxl-modular: the predictors). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
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
