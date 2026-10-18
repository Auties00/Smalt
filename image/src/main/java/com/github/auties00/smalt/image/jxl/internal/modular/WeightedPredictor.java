/* Ports jxl-oxide 0.12.6 (crates/jxl-modular: SelfCorrectingPredictor). Copyright Wonwoo Choi and the jxl-oxide
   contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.modular;

/**
 * Runs the weighted (self-correcting) predictor of modular images (ISO/IEC 18181-1 section
 * H.5.3) over one channel, sample by sample in raster order.
 *
 * <p>Before a sample is decoded, {@link #predict(int, int, int, int, int)} combines four
 * sub-predictions, weighted by their recent errors; after it is decoded, {@link #record(int)}
 * stores the errors the next samples use. Instances are not thread-safe.
 *
 * <p>Predictions, including those of the first row and the last column, match the reference
 * decoder exactly.
 */
final class WeightedPredictor {
    /**
     * The values {@code 2^24 / i} for {@code i} from 1 to 64.
     */
    private static final int[] DIV_LOOKUP = new int[65];

    static {
        for (var i = 1; i <= 64; i++) {
            DIV_LOOKUP[i] = (1 << 24) / i;
        }
    }

    /**
     * The predictor parameters.
     */
    private final WpHeader wp;

    /**
     * The channel width.
     */
    private final int width;

    /**
     * The true errors of the previous row, overwritten by the current one.
     */
    private final int[] trueErrRow;

    /**
     * The four sub-predictor errors of each sample of the previous row, overwritten by the
     * current one.
     */
    private final int[] subErrRow;

    /**
     * The current column.
     */
    private int x;

    /**
     * The current row.
     */
    private int y;

    /**
     * The true error of the west sample.
     */
    private int trueErrW;

    /**
     * The true error of the north-west sample.
     */
    private int trueErrNw;

    /**
     * The true error of the north sample.
     */
    private int trueErrN;

    /**
     * The true error of the north-east sample.
     */
    private int trueErrNe;

    /**
     * The sub-predictor errors carried from the north-west and west-west samples.
     */
    private final int[] subErrNwWw = new int[4];

    /**
     * The sub-predictor errors carried from the north and west samples.
     */
    private final int[] subErrNW = new int[4];

    /**
     * The sub-predictor errors of the north-east sample.
     */
    private final int[] subErrNe = new int[4];

    /**
     * The four sub-predictions of the current sample, scaled by 8.
     */
    private final long[] subPred = new long[4];

    /**
     * The weighted prediction of the current sample, scaled by 8.
     */
    long prediction;

    /**
     * The largest-magnitude true error among the neighbors, the error property of the MA tree.
     */
    int maxError;

    /**
     * Creates a predictor for a channel.
     *
     * @param wp the parameters
     * @param width the channel width
     */
    WeightedPredictor(WpHeader wp, int width) {
        this.wp = wp;
        this.width = width;
        this.trueErrRow = new int[width];
        this.subErrRow = new int[width * 4];
    }

    /**
     * Computes the prediction and error property of the current sample.
     *
     * @param n the north sample
     * @param nw the north-west sample
     * @param ne the north-east sample
     * @param w the west sample
     * @param nn the north-north sample
     */
    void predict(int n, int nw, int ne, int w, int nn) {
        long errW = trueErrW;
        long errNw = trueErrNw;
        long errN = trueErrN;
        long errNe = trueErrNe;
        var n3 = (long) n << 3;
        var nw3 = (long) nw << 3;
        var ne3 = (long) ne << 3;
        var w3 = (long) w << 3;
        var nn3 = (long) nn << 3;
        subPred[0] = w3 + ne3 - n3;
        subPred[1] = n3 - (((errW + errN + errNe) * wp.p1()) >> 5);
        subPred[2] = w3 - (((errW + errN + errNw) * wp.p2()) >> 5);
        subPred[3] = n3 - ((errNw * wp.p3a() + errN * wp.p3b() + errNe * wp.p3c() + (nn3 - n3) * wp.p3d()
                + (nw3 - w3) * wp.p3e()) >> 5);
        var w0 = weight(0, wp.w0());
        var w1 = weight(1, wp.w1());
        var w2 = weight(2, wp.w2());
        var w3w = weight(3, wp.w3());
        var sum = (long) w0 + w1 + w2 + w3w;
        var logWeight = 63 - Long.numberOfLeadingZeros(sum >>> 4);
        w0 >>>= logWeight;
        w1 >>>= logWeight;
        w2 >>>= logWeight;
        w3w >>>= logWeight;
        var sumWeights = w0 + w1 + w2 + w3w;
        var s = (long) (sumWeights >> 1) - 1;
        s += subPred[0] * w0 + subPred[1] * w1 + subPred[2] * w2 + subPred[3] * w3w;
        var p = (s * DIV_LOOKUP[sumWeights]) >> 24;
        if (((errN ^ errW) | (errN ^ errNw)) <= 0) {
            var min = Math.min(n3, Math.min(w3, ne3));
            var max = Math.max(n3, Math.max(w3, ne3));
            p = Math.clamp(p, min, max);
        }
        prediction = p;
        var max = errW;
        if (Math.abs(errN) > Math.abs(max)) {
            max = errN;
        }
        if (Math.abs(errNw) > Math.abs(max)) {
            max = errNw;
        }
        if (Math.abs(errNe) > Math.abs(max)) {
            max = errNe;
        }
        maxError = (int) max;
    }

    /**
     * Computes the weight of a sub-predictor from its accumulated errors.
     *
     * @param index the sub-predictor
     * @param maxWeight its maximum weight
     * @return the weight
     */
    private int weight(int index, int maxWeight) {
        var errSum = (subErrNwWw[index] + subErrNW[index] + subErrNe[index]) & 0xFFFF_FFFFL;
        var scaled = (errSum + 1) >>> 5;
        var shift = scaled == 0 ? 0 : 63 - Long.numberOfLeadingZeros(scaled);
        return 4 + (int) (((long) maxWeight * DIV_LOOKUP[(int) (errSum >>> shift) + 1]) >>> shift);
    }

    /**
     * Records the decoded value of the current sample and moves to the next one.
     *
     * @param sample the decoded sample
     */
    void record(int sample) {
        var s8 = (long) sample << 3;
        var trueErr = (int) (prediction - s8);
        var e0 = (int) ((Math.abs(subPred[0] - s8) + 3) >>> 3);
        var e1 = (int) ((Math.abs(subPred[1] - s8) + 3) >>> 3);
        var e2 = (int) ((Math.abs(subPred[2] - s8) + 3) >>> 3);
        var e3 = (int) ((Math.abs(subPred[3] - s8) + 3) >>> 3);
        trueErrRow[x] = trueErr;
        var base = x * 4;
        subErrRow[base] = e0;
        subErrRow[base + 1] = e1;
        subErrRow[base + 2] = e2;
        subErrRow[base + 3] = e3;
        x++;
        if (x >= width) {
            y++;
            x = 0;
            trueErrW = 0;
            trueErrN = trueErrRow[0];
            trueErrNw = trueErrN;
            System.arraycopy(subErrRow, 0, subErrNW, 0, 4);
            System.arraycopy(subErrRow, 0, subErrNwWw, 0, 4);
            if (width <= 1) {
                trueErrNe = trueErrN;
                System.arraycopy(subErrNW, 0, subErrNe, 0, 4);
            } else {
                trueErrNe = trueErrRow[1];
                System.arraycopy(subErrRow, 4, subErrNe, 0, 4);
            }
        } else {
            trueErrW = trueErr;
            trueErrNw = trueErrN;
            trueErrN = trueErrNe;
            System.arraycopy(subErrNW, 0, subErrNwWw, 0, 4);
            subErrNW[0] = subErrNe[0] + e0;
            subErrNW[1] = subErrNe[1] + e1;
            subErrNW[2] = subErrNe[2] + e2;
            subErrNW[3] = subErrNe[3] + e3;
            if (x + 1 >= width) {
                trueErrNe = trueErrN;
                System.arraycopy(subErrNW, 0, subErrNe, 0, 4);
            } else if (y != 0) {
                trueErrNe = trueErrRow[x + 1];
                System.arraycopy(subErrRow, (x + 1) * 4, subErrNe, 0, 4);
            }
        }
    }
}
