package com.github.auties00.smalt.audio.voice.internal.aecm;

import java.util.Arrays;

/**
 * Estimates the delay between the far-end and the near-end signal of the mobile echo canceller
 * by matching binary spectra.
 *
 * <p>Each block, the far-end and near-end magnitude spectra are reduced to 32-bit binary
 * spectra: bit {@code k} is set when bin {@code 12 + k} exceeds its own slowly tracked mean.
 * The far-end binary spectra of the last {@code historySize} blocks are kept; the near-end
 * binary spectrum is compared with each of them, the number of differing bits is smoothed per
 * candidate delay, and the delay with the smallest smoothed count becomes the estimate once its
 * minimum is distinct and deep enough.
 *
 * @implNote This implementation ports the fixed-point paths of
 * {@code modules/audio_processing/utility/delay_estimator.cc} and
 * {@code delay_estimator_wrapper.cc} of webrtc-audio-processing 2.1 as the mobile echo canceller
 * configures them: no lookahead and robust validation disabled. The reference keeps updating a
 * delay histogram for the robust validation even while it is disabled; that histogram feeds
 * nothing else, so this implementation omits it, which leaves every returned delay unchanged.
 */
final class DelayEstimator {
    /**
     * The first spectrum bin that enters the binary spectrum.
     */
    private static final int BAND_FIRST = 12;

    /**
     * The last spectrum bin that enters the binary spectrum.
     */
    private static final int BAND_LAST = 43;

    /**
     * The largest smoothed bit count, 32 in Q9.
     */
    private static final int MAX_BIT_COUNTS_Q9 = 32 << 9;

    /**
     * The smoothing shift of the mean bit counts when the far-end binary spectrum is empty.
     */
    private static final int SHIFTS_AT_ZERO = 13;

    /**
     * The decrease of the smoothing shift per 16 set far-end bits.
     */
    private static final int SHIFTS_LINEAR_SLOPE = 3;

    /**
     * The margin, 2 in Q9, added to the best candidate to form the adaptive threshold, and the
     * least valley depth that makes a candidate valid.
     */
    private static final int PROBABILITY_OFFSET = 1024;

    /**
     * The lowest adaptive threshold, 17 in Q9.
     */
    private static final int PROBABILITY_LOWER_LIMIT = 8704;

    /**
     * The least valley depth, 5.5 in Q9, that lowers the adaptive threshold.
     */
    private static final int PROBABILITY_MIN_SPREAD = 2816;

    /**
     * The number of far-end binary spectra kept, which is the number of candidate delays.
     */
    private final int historySize;

    /**
     * The far-end binary spectra, newest first.
     */
    private final int[] binaryFarHistory;

    /**
     * The number of set bits of each far-end binary spectrum, newest first.
     */
    private final int[] farBitCounts;

    /**
     * The tracked mean of each far-end spectrum bin in Q15.
     */
    private final int[] meanFarSpectrum;

    /**
     * Whether {@link #meanFarSpectrum} has been initialized from a non-zero spectrum.
     */
    private final boolean[] farSpectrumInitialized = new boolean[1];

    /**
     * The tracked mean of each near-end spectrum bin in Q15.
     */
    private final int[] meanNearSpectrum;

    /**
     * Whether {@link #meanNearSpectrum} has been initialized from a non-zero spectrum.
     */
    private final boolean[] nearSpectrumInitialized = new boolean[1];

    /**
     * The smoothed number of differing bits per candidate delay in Q9.
     */
    private final int[] meanBitCounts;

    /**
     * The number of differing bits per candidate delay of the current block.
     */
    private final int[] bitCounts;

    /**
     * The adaptive threshold below which a best candidate is reliable, in Q9.
     */
    private int minimumProbability;

    /**
     * The smoothed bit count of the last accepted delay, slowly rising over time, in Q9.
     */
    private int lastDelayProbability;

    /**
     * The last accepted delay, or {@code -2} while none has been accepted.
     */
    private int lastDelay;

    /**
     * Creates an estimator ({@code WebRtc_CreateDelayEstimatorFarend} and
     * {@code WebRtc_CreateDelayEstimator} with no lookahead).
     *
     * @param spectrumSize the number of spectrum bins, at least {@value #BAND_LAST}
     * @param historySize  the number of candidate delays, in blocks
     */
    DelayEstimator(int spectrumSize, int historySize) {
        this.historySize = historySize;
        this.binaryFarHistory = new int[historySize];
        this.farBitCounts = new int[historySize];
        this.meanFarSpectrum = new int[spectrumSize];
        this.meanNearSpectrum = new int[spectrumSize];
        this.meanBitCounts = new int[historySize + 1];
        this.bitCounts = new int[historySize];
    }

    /**
     * Resets the far-end and near-end state ({@code WebRtc_InitDelayEstimatorFarend} and
     * {@code WebRtc_InitDelayEstimator}).
     */
    void init() {
        Arrays.fill(binaryFarHistory, 0);
        Arrays.fill(farBitCounts, 0);
        Arrays.fill(meanFarSpectrum, 0);
        farSpectrumInitialized[0] = false;
        Arrays.fill(bitCounts, 0);
        Arrays.fill(meanBitCounts, 20 << 9);
        minimumProbability = MAX_BIT_COUNTS_Q9;
        lastDelayProbability = MAX_BIT_COUNTS_Q9;
        lastDelay = -2;
        Arrays.fill(meanNearSpectrum, 0);
        nearSpectrumInitialized[0] = false;
    }

    /**
     * Adds the far-end spectrum of a block to the history ({@code WebRtc_AddFarSpectrumFix}).
     *
     * @param farSpectrum the magnitude spectrum, {@code spectrumSize} unsigned 16-bit bins
     * @param farQ        the Q-domain of the spectrum, at most 15
     * @return {@code 0}, or {@code -1} when {@code farQ} exceeds 15
     */
    int addFarSpectrum(char[] farSpectrum, int farQ) {
        if (farQ > 15) {
            return -1;
        }
        var binary = binarySpectrum(farSpectrum, meanFarSpectrum, farQ, farSpectrumInitialized);
        System.arraycopy(binaryFarHistory, 0, binaryFarHistory, 1, historySize - 1);
        binaryFarHistory[0] = binary;
        System.arraycopy(farBitCounts, 0, farBitCounts, 1, historySize - 1);
        farBitCounts[0] = Integer.bitCount(binary);
        return 0;
    }

    /**
     * Matches the near-end spectrum of a block against the far-end history and returns the delay
     * estimate ({@code WebRtc_DelayEstimatorProcessFix} and {@code WebRtc_ProcessBinarySpectrum}).
     *
     * @param nearSpectrum the magnitude spectrum, {@code spectrumSize} unsigned 16-bit bins
     * @param nearQ        the Q-domain of the spectrum, at most 15
     * @return the delay in blocks, {@code -2} while no delay has been found, or {@code -1} when
     *         {@code nearQ} exceeds 15
     */
    int process(char[] nearSpectrum, int nearQ) {
        if (nearQ > 15) {
            return -1;
        }
        var binaryNear = binarySpectrum(nearSpectrum, meanNearSpectrum, nearQ, nearSpectrumInitialized);
        for (var n = 0; n < historySize; n++) {
            bitCounts[n] = Integer.bitCount(binaryNear ^ binaryFarHistory[n]);
        }
        for (var i = 0; i < historySize; i++) {
            var bitCount = bitCounts[i] << 9;
            if (farBitCounts[i] > 0) {
                var shifts = SHIFTS_AT_ZERO;
                shifts -= (SHIFTS_LINEAR_SLOPE * farBitCounts[i]) >> 4;
                meanBitCounts[i] = meanEstimator(bitCount, shifts, meanBitCounts[i]);
            }
        }
        var candidateDelay = -1;
        var valueBestCandidate = MAX_BIT_COUNTS_Q9;
        var valueWorstCandidate = 0;
        for (var i = 0; i < historySize; i++) {
            if (meanBitCounts[i] < valueBestCandidate) {
                valueBestCandidate = meanBitCounts[i];
                candidateDelay = i;
            }
            if (meanBitCounts[i] > valueWorstCandidate) {
                valueWorstCandidate = meanBitCounts[i];
            }
        }
        var valleyDepth = valueWorstCandidate - valueBestCandidate;
        if (minimumProbability > PROBABILITY_LOWER_LIMIT && valleyDepth > PROBABILITY_MIN_SPREAD) {
            var threshold = valueBestCandidate + PROBABILITY_OFFSET;
            if (threshold < PROBABILITY_LOWER_LIMIT) {
                threshold = PROBABILITY_LOWER_LIMIT;
            }
            if (minimumProbability > threshold) {
                minimumProbability = threshold;
            }
        }
        lastDelayProbability++;
        var validCandidate = valleyDepth > PROBABILITY_OFFSET
                && (valueBestCandidate < minimumProbability || valueBestCandidate < lastDelayProbability);
        var nonStationaryFarend = false;
        for (var i = 0; i < historySize; i++) {
            if (farBitCounts[i] > 0) {
                nonStationaryFarend = true;
                break;
            }
        }
        if (nonStationaryFarend && validCandidate) {
            lastDelay = candidateDelay;
            if (valueBestCandidate < lastDelayProbability) {
                lastDelayProbability = valueBestCandidate;
            }
        }
        return lastDelay;
    }

    /**
     * Reduces a magnitude spectrum to its binary spectrum, updating the tracked bin means
     * ({@code BinarySpectrumFix}).
     *
     * @param spectrum    the magnitude spectrum
     * @param mean        the tracked mean of each bin in Q15, updated in place
     * @param qDomain     the Q-domain of the spectrum
     * @param initialized whether {@code mean} has been initialized, updated in place
     * @return the binary spectrum, bit {@code k} for bin {@code BAND_FIRST + k}
     */
    private static int binarySpectrum(char[] spectrum, int[] mean, int qDomain, boolean[] initialized) {
        var out = 0;
        if (!initialized[0]) {
            for (var i = BAND_FIRST; i <= BAND_LAST; i++) {
                if (spectrum[i] > 0) {
                    var spectrumQ15 = spectrum[i] << (15 - qDomain);
                    mean[i] = spectrumQ15 >> 1;
                    initialized[0] = true;
                }
            }
        }
        for (var i = BAND_FIRST; i <= BAND_LAST; i++) {
            var spectrumQ15 = spectrum[i] << (15 - qDomain);
            mean[i] = meanEstimator(spectrumQ15, 6, mean[i]);
            if (spectrumQ15 > mean[i]) {
                out |= 1 << (i - BAND_FIRST);
            }
        }
        return out;
    }

    /**
     * Moves a mean a power-of-two fraction towards a new value, truncating towards the old mean
     * ({@code WebRtc_MeanEstimatorFix}).
     *
     * @param newValue the new value
     * @param factor   the right shift applied to the difference
     * @param mean     the current mean
     * @return the updated mean
     */
    private static int meanEstimator(int newValue, int factor, int mean) {
        var diff = newValue - mean;
        if (diff < 0) {
            diff = -((-diff) >> factor);
        } else {
            diff = diff >> factor;
        }
        return mean + diff;
    }
}
