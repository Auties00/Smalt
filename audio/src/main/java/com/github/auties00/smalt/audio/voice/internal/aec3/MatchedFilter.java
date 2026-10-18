package com.github.auties00.smalt.audio.voice.internal.aec3;

import java.util.Arrays;

/**
 * Estimates the render-to-capture delay with a bank of NLMS matched filters over the decimated
 * signals.
 *
 * <p>Each of the {@value Aec3#NUM_FILTERS} filters has 512 taps at 4 kHz and covers a lag range
 * offset by 384 samples from its neighbor. Every update adapts each filter on the 16 decimated
 * capture samples of a block; the filter with the lowest prediction error that has a reliable
 * peak gives the lag estimate. The filter that won the previous update also accumulates the
 * prediction error along its taps to detect pre-echo, so that the reported lag can move to the
 * earliest tap that already explains the echo.
 *
 * @implNote This implementation ports {@code MatchedFilter} of WebRTC's
 * {@code modules/audio_processing/aec3/matched_filter.cc} with pre-echo detection enabled. The
 * filter cores run through {@link MatchedFilterKernel}.
 */
final class MatchedFilter {
    /**
     * The number of decimated samples per block.
     */
    private static final int SUB_BLOCK_SIZE = Aec3.BLOCK_SIZE / Aec3.DOWN_SAMPLING_FACTOR;

    /**
     * The number of taps of each filter.
     */
    static final int FILTER_LENGTH = Aec3.MATCHED_FILTER_WINDOW_SIZE_SUB_BLOCKS * SUB_BLOCK_SIZE;

    /**
     * The lag offset between consecutive filters, in decimated samples.
     */
    static final int FILTER_INTRA_LAG_SHIFT = Aec3.MATCHED_FILTER_ALIGNMENT_SHIFT_SIZE_SUB_BLOCKS * SUB_BLOCK_SIZE;

    /**
     * The largest lag the bank covers, {@code GetMaxFilterLag()}.
     */
    static final int MAX_FILTER_LAG = Aec3.NUM_FILTERS * FILTER_INTRA_LAG_SHIFT + FILTER_LENGTH;

    /**
     * The number of taps per accumulated-error entry.
     */
    private static final int ACCUMULATED_ERROR_SUB_SAMPLE_RATE = 4;

    /**
     * The number of accumulated-error entries per filter.
     */
    private static final int ACCUMULATED_ERROR_SIZE = FILTER_LENGTH / ACCUMULATED_ERROR_SUB_SAMPLE_RATE;

    /**
     * The filter taps, one filter after another.
     */
    private final float[] filters = new float[Aec3.NUM_FILTERS * FILTER_LENGTH];

    /**
     * The smoothed normalized accumulated errors of every filter.
     */
    private final float[] accumulatedError = new float[Aec3.NUM_FILTERS * ACCUMULATED_ERROR_SIZE];

    /**
     * The accumulated error of the last update of the pre-echo filter.
     */
    private final float[] instantaneousAccumulatedError = new float[ACCUMULATED_ERROR_SIZE];

    /**
     * The kernel running the filter cores.
     */
    private final MatchedFilterKernel kernel;

    /**
     * The per-update outputs of the kernel: the error sum and whether the filter was updated.
     */
    private final float[] coreResult = new float[2];

    /**
     * Whether the last update produced a lag estimate.
     */
    private boolean hasLagEstimate;

    /**
     * The lag of the last estimate.
     */
    private int reportedLag;

    /**
     * The pre-echo lag of the last estimate.
     */
    private int reportedPreEchoLag;

    /**
     * The index of the filter that won the last update with an estimate, or {@code -1}.
     */
    private int lastDetectedBestLagFilter = -1;

    /**
     * The number of updates of the accumulated error.
     */
    private int numberPreEchoUpdates;

    /**
     * Creates a filter bank of zero filters.
     *
     * @param kernel the kernel running the filter cores
     */
    MatchedFilter(MatchedFilterKernel kernel) {
        this.kernel = kernel;
        Arrays.fill(accumulatedError, 1.0f);
    }

    /**
     * Clears the filters and the estimate, and on a full reset also the pre-echo state.
     *
     * @param fullReset whether the accumulated errors are reset too
     */
    void reset(boolean fullReset) {
        Arrays.fill(filters, 0.f);
        hasLagEstimate = false;
        if (fullReset) {
            Arrays.fill(accumulatedError, 1.0f);
            numberPreEchoUpdates = 0;
        }
    }

    /**
     * Adapts the filters on one decimated capture sub-block and updates the lag estimate.
     *
     * @param renderBuffer    the decimated render ring, with its mirrored tail
     * @param renderRead      the read index of the decimated render ring
     * @param capture         the 16 decimated capture samples
     * @param useSlowSmoothing whether the adaptation uses the slower step size
     */
    void update(float[] renderBuffer, int renderRead, float[] capture, boolean useSlowSmoothing) {
        var y = capture;
        var smoothing = useSlowSmoothing ? Aec3.DELAY_ESTIMATE_SMOOTHING_DELAY_FOUND : Aec3.DELAY_ESTIMATE_SMOOTHING;
        var x2SumThreshold = FILTER_LENGTH * Aec3.POOR_EXCITATION_RENDER_LIMIT * Aec3.POOR_EXCITATION_RENDER_LIMIT;
        var errorSumAnchor = 0.0f;
        for (var k = 0; k < SUB_BLOCK_SIZE; ++k) {
            errorSumAnchor += y[k] * y[k];
        }
        var winnerErrorSum = errorSumAnchor;
        var hasWinnerLag = false;
        var winnerLag = 0;
        hasLagEstimate = false;
        var alignmentShift = 0;
        var hasPreviousLag = false;
        var previousLagEstimate = 0;
        var winnerIndex = -1;
        for (var n = 0; n < Aec3.NUM_FILTERS; ++n) {
            var computePreEcho = n == lastDetectedBestLagFilter;
            var xStartIndex = (renderRead + alignmentShift + SUB_BLOCK_SIZE - 1) % RenderDelayBuffer.LOW_RATE_SIZE;
            kernel.core(xStartIndex, x2SumThreshold, smoothing, renderBuffer, y, filters, n * FILTER_LENGTH,
                    computePreEcho, instantaneousAccumulatedError, coreResult);
            var errorSum = coreResult[0];
            var filtersUpdated = coreResult[1] != 0;
            var lagEstimate = maxSquarePeakIndex(filters, n * FILTER_LENGTH);
            var reliable = lagEstimate > 2 && lagEstimate < (FILTER_LENGTH - 10)
                    && errorSum < Aec3.DELAY_CANDIDATE_DETECTION_THRESHOLD * errorSumAnchor;
            var lag = lagEstimate + alignmentShift;
            if (filtersUpdated && reliable && errorSum < winnerErrorSum) {
                winnerErrorSum = errorSum;
                winnerIndex = n;
                if (hasPreviousLag && previousLagEstimate == lag) {
                    winnerLag = previousLagEstimate;
                    winnerIndex = n - 1;
                } else {
                    winnerLag = lag;
                }
                hasWinnerLag = true;
            }
            hasPreviousLag = true;
            previousLagEstimate = lag;
            alignmentShift += FILTER_INTRA_LAG_SHIFT;
        }
        if (winnerIndex != -1 && hasWinnerLag) {
            hasLagEstimate = true;
            reportedLag = winnerLag;
            reportedPreEchoLag = winnerLag;
            if (lastDetectedBestLagFilter == winnerIndex) {
                var energyThreshold = 1.0f;
                if (errorSumAnchor > energyThreshold) {
                    updateAccumulatedError(winnerIndex * ACCUMULATED_ERROR_SIZE, 1.0f / errorSumAnchor);
                    numberPreEchoUpdates++;
                }
                if (numberPreEchoUpdates >= 50) {
                    reportedPreEchoLag = computePreEchoLag(winnerIndex * ACCUMULATED_ERROR_SIZE, winnerLag,
                            winnerIndex * FILTER_INTRA_LAG_SHIFT);
                } else {
                    reportedPreEchoLag = winnerLag;
                }
            }
            lastDetectedBestLagFilter = winnerIndex;
        }
    }

    /**
     * Returns whether the last update produced a lag estimate.
     *
     * @return whether {@link #lag()} and {@link #preEchoLag()} are valid
     */
    boolean hasLagEstimate() {
        return hasLagEstimate;
    }

    /**
     * Returns the lag of the last estimate.
     *
     * @return the lag in decimated samples
     */
    int lag() {
        return reportedLag;
    }

    /**
     * Returns the pre-echo lag of the last estimate.
     *
     * @return the lag in decimated samples
     */
    int preEchoLag() {
        return reportedPreEchoLag;
    }

    /**
     * Smooths the accumulated error of a filter towards the normalized instantaneous error,
     * following decreases at once and increases slowly.
     *
     * @param offset                 the index of the filter's first accumulated-error entry
     * @param oneOverErrorSumAnchor  the reciprocal of the capture energy
     */
    private void updateAccumulatedError(int offset, float oneOverErrorSumAnchor) {
        var smoothConstantIncreases = 0.015f;
        for (var k = 0; k < ACCUMULATED_ERROR_SIZE; ++k) {
            var errorNorm = instantaneousAccumulatedError[k] * oneOverErrorSumAnchor;
            if (errorNorm < accumulatedError[offset + k]) {
                accumulatedError[offset + k] = errorNorm;
            } else {
                accumulatedError[offset + k] += smoothConstantIncreases * (errorNorm - accumulatedError[offset + k]);
            }
        }
    }

    /**
     * Returns the earliest lag whose accumulated error already explains the echo.
     *
     * @param offset               the index of the filter's first accumulated-error entry
     * @param lag                  the estimated lag
     * @param alignmentShiftWinner the lag offset of the filter
     * @return the pre-echo lag
     */
    private int computePreEchoLag(int offset, int lag, int alignmentShiftWinner) {
        var preEchoThreshold = 0.5f;
        var preEchoLagEstimate = lag - alignmentShiftWinner;
        var maximumPreEchoLag = Math.min(preEchoLagEstimate / ACCUMULATED_ERROR_SUB_SAMPLE_RATE, ACCUMULATED_ERROR_SIZE);
        for (var k = maximumPreEchoLag - 1; k >= 0; --k) {
            if (accumulatedError[offset + k] > preEchoThreshold) {
                break;
            }
            preEchoLagEstimate = (k + 1) * ACCUMULATED_ERROR_SUB_SAMPLE_RATE - 1;
        }
        return preEchoLagEstimate + alignmentShiftWinner;
    }

    /**
     * Returns the index of the largest squared tap of a filter, scanning even and odd taps
     * separately as the reference does.
     *
     * @param h      the taps
     * @param offset the index of the filter's first tap
     * @return the index of the peak within the filter
     */
    static int maxSquarePeakIndex(float[] h, int offset) {
        var maxElement1 = h[offset] * h[offset];
        var maxElement2 = h[offset + 1] * h[offset + 1];
        var lagEstimate1 = 0;
        var lagEstimate2 = 1;
        var lastIndex = FILTER_LENGTH - 1;
        for (var k = 2; k < lastIndex; k += 2) {
            var element1 = h[offset + k] * h[offset + k];
            var element2 = h[offset + k + 1] * h[offset + k + 1];
            if (element1 > maxElement1) {
                maxElement1 = element1;
                lagEstimate1 = k;
            }
            if (element2 > maxElement2) {
                maxElement2 = element2;
                lagEstimate2 = k + 1;
            }
        }
        if (maxElement2 > maxElement1) {
            maxElement1 = maxElement2;
            lagEstimate1 = lagEstimate2;
        }
        var lastElement = h[offset + lastIndex] * h[offset + lastIndex];
        if (lastElement > maxElement1) {
            return lastIndex;
        }
        return lagEstimate1;
    }
}
