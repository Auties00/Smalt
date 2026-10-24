package com.github.auties00.smalt.audio.aac.internal;

import java.util.Arrays;

/**
 * Runs the long term prediction of one AAC LTP, ER AAC LTP or AAC-LD channel (ISO/IEC 14496-3 subclause
 * 4.6.7).
 *
 * <p>The predictor keeps the channel's last 2048 output samples. For a long-window frame with
 * {@code ltp_data_present}, the time signal {@code x_est(i) = ltp_coef * x_rec(i - ltp_lag)}, for
 * {@code i} below twice the frame length, is built from a buffer {@code x_rec} whose first frame
 * length of samples is the windowed second half of the previous inverse transform (the overlap
 * memory), followed by zeros, and whose negative indices are the past output. The estimate is
 * windowed with the frame's window, transformed by the forward MDCT, run through the frame's TNS
 * analysis filters, and added to the bands below {@code min(max_sfb, 40)} whose
 * {@code ltp_long_used} flag is set, except noise-substituted bands, where noise substitution takes
 * precedence. Eight-short-window frames are not predicted. Every frame's output is appended to the
 * history by {@link #update}. AAC-LD counts its lag from the start of the last output frame rather than
 * from the overlap memory, so {@code x_est(i) = ltp_coef * x_rec(i - ltp_lag - N)} for frame length
 * {@code N}.
 *
 * <p>Instances are not thread-safe.
 */
public final class LongTermPredictor {
    /**
     * The number of past output samples kept: the largest lag plus one.
     */
    private static final int HISTORY = 2048;

    /**
     * The scale from output samples, where full scale is 1, back to spectral units, times the
     * factor 2 of the forward MDCT.
     */
    private static final double INPUT_SCALE = 2 * 32768.0;

    /**
     * The number of spectral lines per frame.
     */
    private final int frameLength;

    /**
     * The offset of the lag origin: the frame length for AAC-LD, 0 otherwise.
     */
    private final int lagOrigin;

    /**
     * The past output samples, the most recent last.
     */
    private final float[] history = new float[HISTORY];

    /**
     * The windowed time estimate, twice the frame length.
     */
    private final float[] estimate;

    /**
     * The folded estimate the DCT-IV transforms.
     */
    private final float[] folded;

    /**
     * The estimated spectrum.
     */
    private final float[] estimatedSpectrum;

    /**
     * The scaled DCT-IV of the forward MDCT.
     */
    private final Dct4 dct;

    /**
     * Creates a predictor with an all-zero history.
     *
     * @param frameLength the number of spectral lines per frame, 1024 or 960
     * @param kernel the DSP kernel
     */
    public LongTermPredictor(int frameLength, AacKernel kernel) {
        this(frameLength, kernel, false);
    }

    /**
     * Creates a predictor with an all-zero history, optionally with the lag origin of AAC-LD.
     *
     * @param frameLength the number of spectral lines per frame: 1024 or 960, or 512 or 480 for AAC-LD
     * @param kernel the DSP kernel
     * @param lowDelay whether the lag counts from the last output frame, as in AAC-LD
     */
    public LongTermPredictor(int frameLength, AacKernel kernel, boolean lowDelay) {
        this.frameLength = frameLength;
        this.lagOrigin = lowDelay ? frameLength : 0;
        this.estimate = new float[2 * frameLength];
        this.folded = new float[frameLength];
        this.estimatedSpectrum = new float[frameLength];
        this.dct = new Dct4(frameLength, INPUT_SCALE, kernel);
    }

    /**
     * Clears the history.
     */
    public void reset() {
        Arrays.fill(history, 0);
    }

    /**
     * Adds the long term prediction of a frame to its spectrum, before TNS.
     *
     * @param stream the channel stream of the frame, whose spectrum is updated in place
     * @param overlap the channel's overlap memory from the previous frame, {@code frameLength}
     * samples
     * @param previousShape the window shape of the previous frame
     * @param synthesis the filterbank whose windows the frame uses
     */
    public void predict(ChannelStream stream, float[] overlap, int previousShape, Synthesis synthesis) {
        var sequence = stream.windowSequence();
        if (!stream.ltpPresent() || sequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            return;
        }
        var n = frameLength;
        var lag = stream.ltpLag();
        var coefficient = stream.ltpCoefficient();
        var window = synthesis.composite(sequence, previousShape, stream.windowShape());
        for (var i = 0; i < 2 * n; i++) {
            var j = i - lag - lagOrigin;
            float value;
            if (j >= n) {
                value = 0;
            } else if (j >= 0) {
                value = overlap[j];
            } else {
                value = j >= -HISTORY ? history[HISTORY + j] : 0;
            }
            estimate[i] = coefficient * value * window[i];
        }
        forwardMdct(estimate, estimatedSpectrum);
        stream.applyTnsAnalysis(estimatedSpectrum);
        var spectrum = stream.spectrum();
        var offsets = stream.bandOffsets();
        var bands = Math.min(stream.maxBands(), ChannelStream.LTP_MAX_BANDS);
        for (var band = 0; band < bands; band++) {
            if (!stream.ltpUsed(band) || stream.bandType(band) == Codebooks.NOISE_HCB) {
                continue;
            }
            for (var k = offsets[band]; k < offsets[band + 1]; k++) {
                spectrum[k] += estimatedSpectrum[k];
            }
        }
    }

    /**
     * Appends a frame of output samples to the history.
     *
     * @param output the array holding the frame's output samples
     * @param offset the index of the first sample
     */
    public void update(float[] output, int offset) {
        var n = frameLength;
        System.arraycopy(history, n, history, 0, HISTORY - n);
        System.arraycopy(output, offset, history, HISTORY - n, n);
    }

    /**
     * Transforms windowed output samples into spectral values with the forward MDCT
     * {@code X[k] = 2 * 32768 * sum z[n] cos(pi / N (n + 1/2 + N/2)(k + 1/2))}, the inverse of the
     * scaled transform of {@link Synthesis}.
     *
     * @param z the windowed samples, twice the frame length
     * @param spectrum the spectral values, the frame length
     */
    void forwardMdct(float[] z, float[] spectrum) {
        var n = frameLength;
        var h = n / 2;
        for (var i = 0; i < h; i++) {
            folded[i] = -z[3 * h - 1 - i] - z[3 * h + i];
            folded[h + i] = z[i] - z[n - 1 - i];
        }
        dct.transform(folded, 0, spectrum, 0);
    }
}
