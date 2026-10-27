package com.github.auties00.smalt.video.h264.encoder.internal;

/**
 * Spends the bits of a two-pass average bitrate encode over the whole video, from the sizes its first pass
 * measured.
 *
 * <p>The frames of the first pass form groups in display order: an IDR or P picture, its anchor, and the B
 * pictures before it, which the encoder codes right after it at the anchor's quantizer plus a fixed offset.
 * A frame's size has an invariant part, as large as the smallest P or B picture of the first pass (headers and
 * skipped macroblocks), and a variable part, the rest. The size of a frame coded {@code d} quantizer steps
 * coarser than in the first pass is predicted as
 * {@snippet :
 * bits(d) = invariant + (firstBits - invariant) * exp(intercept + typeOffset[type] + slope * d)
 * }
 * where the intercept and the slope are a least-squares fit of the logarithm of the variable part's ratio
 * against {@code d} over the frames the second pass coded, each weighted by its variable share, with
 * exponential forgetting and priors (no change of size at the same quantizer, and the size halving every
 * {@code 6 / }{@link #GAMMA} steps), and the type offsets are the mean residuals of the fit by picture type;
 * they absorb the differences between the passes, such as the first pass searching less, and how the size of
 * the content at hand reacts to the quantizer.
 *
 * <p>A group's complexity is its first pass size scaled to an anchor quantizer step of one with the
 * exponent {@link #GAMMA}, per second, averaged with its neighbors (weights halving with each group of
 * distance, up to four on each side; an IDR group takes the average of the P groups around it only). Each
 * anchor's quantizer step is a common scale times its complexity to the power {@link #COMPLEXITY_EXPONENT}, an
 * IDR anchor's {@link #idrOffset} quantizer steps finer, so a group's size grows with the 0.6th power of its
 * complexity. Before every anchor the scale is solved again so that the predicted size of every frame not yet
 * coded equals the bits the stream has left: the target size (the bitrate times the duration of the measured
 * frames) less the bits spent, so every misprediction is spread over the rest of the video. The B pictures,
 * whose quantizers follow their anchor's, are planned the same way when their turn comes.
 *
 * <p>Instances are not thread-safe.
 */
final class TwoPassPlan {
    /**
     * The exponent of the quantizer step in the complexity of a group and, before the second pass has measured
     * it, in the size model.
     */
    static final double GAMMA = 1.6;

    /**
     * The exponent of the complexity in an anchor's quantizer step.
     */
    static final double COMPLEXITY_EXPONENT = 0.4 / GAMMA;

    /**
     * The prior slope of the logarithm of the size ratio per quantizer step.
     */
    private static final double PRIOR_SLOPE = -GAMMA * StrictMath.log(2) / 6;

    /**
     * The weight of the prior slope, in squared quantizer steps of observations.
     */
    private static final double SLOPE_PRIOR_WEIGHT = 50;

    /**
     * The weight of the prior intercept of zero, in observations.
     */
    private static final double INTERCEPT_PRIOR_WEIGHT = 1;

    /**
     * The weight of the prior type offsets of zero, in observations.
     */
    private static final double OFFSET_PRIOR_WEIGHT = 2;

    /**
     * The slope range, in natural logarithm per quantizer step: sizes halving every 12 to every 1.5 steps.
     */
    private static final double MIN_SLOPE = -StrictMath.log(2) / 1.5;

    private static final double MAX_SLOPE = -StrictMath.log(2) / 12;

    /**
     * The weight of the earlier observations when a new one arrives.
     */
    private static final double MEMORY = 0.99;

    /**
     * The size exponents of {@link #MAX_SLOPE} and {@link #MIN_SLOPE}, the range of {@link #exponent()}.
     */
    private static final double MIN_EXPONENT = -MAX_SLOPE * 6 / StrictMath.log(2);

    private static final double MAX_EXPONENT = -MIN_SLOPE * 6 / StrictMath.log(2);

    /**
     * The spacing of the size exponents the predicted sizes of the frames left are summed at: the fitted
     * exponent is rounded to a multiple of it above {@link #MIN_EXPONENT}.
     */
    private static final double EXPONENT_STEP = 0.01;

    /**
     * The number of size exponents the sums are kept at.
     */
    private static final int EXPONENTS = (int) Math.round((MAX_EXPONENT - MIN_EXPONENT) / EXPONENT_STEP) + 1;

    /**
     * The largest magnitude of {@link #logStep}, which keeps every predicted size finite for any statistics.
     */
    private static final double MAX_LOG_STEP = 100;

    /**
     * The number of groups on each side whose complexities are averaged.
     */
    private static final int BLUR_RADIUS = 4;

    /**
     * The smallest and largest share of the nominal bits of the frames left that the bits left may count as,
     * which bounds how far the quantizers move to absorb an earlier misprediction.
     */
    private static final double MIN_REMAINING_SHARE = 0.05;

    private static final double MAX_REMAINING_SHARE = 10;

    private final PassRecords records;

    private double bitrate;

    /**
     * The quantizer offset of IDR anchors.
     */
    private final int idrOffset;

    /**
     * The display index of each frame's anchor.
     */
    private final int[] anchor;

    /**
     * The averaged complexity of each frame's group raised to {@link #COMPLEXITY_EXPONENT}, times the step
     * ratio of an IDR anchor, so that an anchor's quantizer step is the scale times this value.
     */
    private final double[] stepFactor;

    /**
     * The natural logarithm of the first pass quantizer step of each frame's anchor over its step factor: the
     * logarithm of how many times coarser than in the first pass the frame is coded at a scale of one.
     */
    private final double[] logStep;

    private final boolean[] coded;

    /**
     * The invariant part of each frame's size, in bits.
     */
    private final double[] invariant;

    /**
     * The sum of {@link #invariant} over the frames not yet coded.
     */
    private double remainingInvariant;

    /**
     * The predicted size, before the intercept and the type offsets, at a scale of one, of the frames not yet
     * coded by picture type ({@code remainingWeight[exponent * TYPES + type]}), at each size exponent of the
     * grid that {@link #summed} marks. A sum is made the first time its exponent is used and kept up to date as
     * frames are coded, so the cost stays linear in the number of frames however the fitted exponent moves.
     */
    private final double[] remainingWeight = new double[EXPONENTS * PassRecords.TYPES];

    /**
     * Whether {@link #remainingWeight} holds the sums of each exponent of the grid.
     */
    private final boolean[] summed = new boolean[EXPONENTS];

    /**
     * The exponents of the grid {@link #summed} marks, the first {@link #summedCount}.
     */
    private final int[] summedExponents = new int[EXPONENTS];

    private int summedCount;

    /**
     * The bits the stream may spend on the measured frames.
     */
    private double target;

    /**
     * The duration of the measured frames not yet coded, in seconds.
     */
    private double remainingSeconds;

    /**
     * The bits spent on the measured frames coded so far.
     */
    private double spent;

    /**
     * The forgetting sums of the fit: weights, quantizer differences, size ratio logarithms, squared differences
     * and products.
     */
    private double sumWeight;

    private double sumX;

    private double sumY;

    private double sumXX;

    private double sumXY;

    /**
     * The fitted intercept and slope.
     */
    private double intercept;

    private double slope = PRIOR_SLOPE;

    /**
     * The forgetting sums of the residuals of the fit and their weights, by picture type.
     */
    private final double[] residualSum = new double[PassRecords.TYPES];

    private final double[] residualWeight = new double[PassRecords.TYPES];

    /**
     * Plans an encode.
     *
     * @param records the first pass records
     * @param bitrate the target bitrate in bits per second
     * @param idrOffset the quantizer offset of IDR anchors relative to P anchors
     */
    TwoPassPlan(PassRecords records, double bitrate, int idrOffset) {
        this.records = records;
        this.bitrate = bitrate;
        this.idrOffset = idrOffset;
        var frames = records.frames();
        this.anchor = new int[frames];
        this.stepFactor = new double[frames];
        this.logStep = new double[frames];
        this.coded = new boolean[frames];
        this.invariant = new double[frames];
        var smallest = Long.MAX_VALUE;
        for (var i = 0; i < frames; i++) {
            if (records.type(i) != PassRecords.INTRA) {
                smallest = Math.min(smallest, records.bits(i));
            }
        }
        for (var i = 0; i < frames; i++) {
            invariant[i] = smallest == Long.MAX_VALUE ? 0 : Math.min(records.bits(i), smallest);
            remainingInvariant += invariant[i];
        }
        // complexities are kept as logarithms: a group of a huge size or a tiny duration overflows a double
        var groupAnchor = new int[frames];
        var groupComplexity = new double[frames];
        var groupIntra = new boolean[frames];
        var groups = 0;
        var start = 0;
        for (var i = 0; i < frames; i++) {
            if (records.type(i) == PassRecords.BIDIRECTIONAL && i + 1 < frames) {
                continue;
            }
            var bits = 0.0;
            var seconds = 0.0;
            for (var j = start; j <= i; j++) {
                bits += records.bits(j);
                seconds += records.seconds(j);
                anchor[j] = i;
            }
            groupAnchor[groups] = i;
            groupIntra[groups] = records.type(i) == PassRecords.INTRA;
            groupComplexity[groups] = StrictMath.log(bits)
                    + GAMMA * StrictMath.log(RateController.qscale(records.qp(i))) - StrictMath.log(seconds);
            groups++;
            start = i + 1;
        }
        var groupLogStep = new double[groups];
        var allIntra = true;
        for (var g = 0; g < groups; g++) {
            allIntra &= groupIntra[g];
        }
        var idrStep = -idrOffset / 6.0 * StrictMath.log(2);
        for (var g = 0; g < groups; g++) {
            var largest = Double.NEGATIVE_INFINITY;
            for (var d = -BLUR_RADIUS; d <= BLUR_RADIUS; d++) {
                var other = g + d;
                if (other >= 0 && other < groups && (!groupIntra[other] || allIntra)) {
                    largest = Math.max(largest, groupComplexity[other]);
                }
            }
            var complexity = groupComplexity[g];
            if (largest > Double.NEGATIVE_INFINITY) {
                var sum = 0.0;
                var weights = 0.0;
                for (var d = -BLUR_RADIUS; d <= BLUR_RADIUS; d++) {
                    var other = g + d;
                    if (other < 0 || other >= groups || (groupIntra[other] && !allIntra)) {
                        continue;
                    }
                    var w = StrictMath.pow(0.5, Math.abs(d));
                    sum += w * StrictMath.exp(groupComplexity[other] - largest);
                    weights += w;
                }
                complexity = largest + StrictMath.log(sum / weights);
            }
            groupLogStep[g] = COMPLEXITY_EXPONENT * complexity + (groupIntra[g] ? idrStep : 0);
        }
        var g = 0;
        var seconds = 0.0;
        for (var i = 0; i < frames; i++) {
            while (groupAnchor[g] < anchor[i]) {
                g++;
            }
            stepFactor[i] = StrictMath.exp(groupLogStep[g]);
            logStep[i] = Math.clamp(StrictMath.log(RateController.qscale(records.qp(anchor[i]))) - groupLogStep[g],
                    -MAX_LOG_STEP, MAX_LOG_STEP);
            seconds += records.seconds(i);
        }
        this.remainingSeconds = seconds;
        this.target = bitrate * seconds;
    }

    /**
     * Returns whether the plan holds a frame.
     *
     * @param frame the display index
     * @return {@code true} when the first pass measured the frame and the second pass has not coded it yet
     */
    boolean covers(long frame) {
        return frame >= 0 && frame < coded.length && !coded[(int) frame];
    }

    /**
     * Retargets the frames not yet coded at a new bitrate: from now on the stream may spend the bits spent so
     * far plus the new bitrate times the duration of the measured frames not yet coded.
     *
     * @param newBitrate the bitrate in bits per second, positive
     */
    void retarget(double newBitrate) {
        bitrate = newBitrate;
        target = spent + newBitrate * remainingSeconds;
    }

    /**
     * {@return the size exponent of the fitted slope: how many times the size grows per doubling of the
     * quantizer step}
     */
    private double exponent() {
        return -slope * 6 / StrictMath.log(2);
    }

    /**
     * Returns the quantizer of a frame the encoder is about to code.
     *
     * @param frame the display index, {@linkplain #covers(long) covered}
     * @param intra whether the frame is coded as an IDR picture
     * @return the quantizer, unrounded and unclamped
     */
    double quantizer(long frame, boolean intra) {
        var index = (int) frame;
        var grid = (int) Math.clamp(Math.round((exponent() - MIN_EXPONENT) / EXPONENT_STEP), 0, EXPONENTS - 1);
        var exponent = exponentOf(grid);
        var base = grid * PassRecords.TYPES;
        if (!summed[grid]) {
            for (var i = 0; i < coded.length; i++) {
                if (!coded[i]) {
                    remainingWeight[base + records.type(i)] += weight(i, exponent);
                }
            }
            summed[grid] = true;
            summedExponents[summedCount++] = grid;
        }
        var nominal = bitrate * remainingSeconds;
        var left = Math.clamp(target - spent, MIN_REMAINING_SHARE * nominal, MAX_REMAINING_SHARE * nominal);
        var variable = Math.max(left - Math.max(0, remainingInvariant), MIN_REMAINING_SHARE * left);
        var predicted = 0.0;
        for (var type = 0; type < PassRecords.TYPES; type++) {
            predicted += StrictMath.exp(intercept + offset(type)) * Math.max(0, remainingWeight[base + type]);
        }
        var scale = StrictMath.pow(Math.max(predicted, 1) / Math.max(variable, 1), 1 / exponent);
        var qp = RateController.qp(scale * stepFactor[index]) + records.qp(index) - records.qp(anchor[index]);
        var wasIntra = records.type(index) == PassRecords.INTRA;
        if (intra != wasIntra) {
            qp += intra ? -idrOffset : idrOffset;
        }
        return Double.isNaN(qp) ? records.qp(index) : qp;
    }

    /**
     * Returns a size exponent of the grid {@link #remainingWeight} is summed at.
     *
     * @param grid the index in the grid
     * @return the exponent
     */
    private static double exponentOf(int grid) {
        return MIN_EXPONENT + grid * EXPONENT_STEP;
    }

    /**
     * Returns the variable part of a frame's size at a scale of one, before the intercept and its type offset.
     *
     * @param index the display index
     * @param exponent the size exponent
     * @return the size in bits
     */
    private double weight(int index, double exponent) {
        return (records.bits(index) - invariant[index]) * StrictMath.exp(exponent * logStep[index]);
    }

    /**
     * Returns the residual offset of a picture type, shrunk towards zero by its prior weight.
     *
     * @param type the picture type
     * @return the offset, natural logarithm
     */
    private double offset(int type) {
        return residualSum[type] / (residualWeight[type] + OFFSET_PRIOR_WEIGHT);
    }

    /**
     * Returns the predicted size of a frame at a quantizer.
     *
     * @param frame the display index, {@linkplain #covers(long) covered}
     * @param qp the quantizer
     * @return the size in bits
     */
    double predict(long frame, double qp) {
        var index = (int) frame;
        var difference = qp - records.qp(index);
        return invariant[index] + (records.bits(index) - invariant[index])
                * StrictMath.exp(intercept + offset(records.type(index)) + slope * difference);
    }

    /**
     * Records a frame of the second pass and refits the size model.
     *
     * @param frame the display index; a frame the plan does not {@linkplain #covers(long) cover} is ignored
     * @param qp the quantizer the frame was coded with, without the offset of its temporal layer
     * @param bits the coded size in bits
     */
    void coded(long frame, int qp, long bits) {
        if (!covers(frame)) {
            return;
        }
        var index = (int) frame;
        var type = records.type(index);
        coded[index] = true;
        for (var k = 0; k < summedCount; k++) {
            var grid = summedExponents[k];
            remainingWeight[grid * PassRecords.TYPES + type] -= weight(index, exponentOf(grid));
        }
        remainingSeconds = Math.max(0, remainingSeconds - records.seconds(index));
        remainingInvariant -= invariant[index];
        spent += bits;
        var first = records.bits(index) - invariant[index];
        if (first < 1) {
            return;
        }
        var w = first / records.bits(index);
        var x = (double) (qp - records.qp(index));
        var y = StrictMath.log(Math.max(1, bits - invariant[index]) / first);
        sumWeight = MEMORY * sumWeight + w;
        sumX = MEMORY * sumX + w * x;
        sumY = MEMORY * sumY + w * y;
        sumXX = MEMORY * sumXX + w * x * x;
        sumXY = MEMORY * sumXY + w * x * y;
        // minimizes the squared residuals plus the prior penalties on the intercept (towards 0) and the slope
        var a = sumWeight + INTERCEPT_PRIOR_WEIGHT;
        var b = sumX;
        var d = sumXX + SLOPE_PRIOR_WEIGHT;
        var determinant = a * d - b * b;
        var c = sumY;
        var e = sumXY + SLOPE_PRIOR_WEIGHT * PRIOR_SLOPE;
        intercept = (c * d - b * e) / determinant;
        slope = Math.clamp((a * e - b * c) / determinant, MIN_SLOPE, MAX_SLOPE);
        for (var t = 0; t < PassRecords.TYPES; t++) {
            residualSum[t] *= MEMORY;
            residualWeight[t] *= MEMORY;
        }
        residualSum[type] += w * (y - intercept - slope * x);
        residualWeight[type] += w;
    }
}
