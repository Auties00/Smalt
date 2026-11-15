/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/ns/noise_suppressor.cc). Copyright (c) 2011, The WebRTC
   project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.ns;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;
import com.github.auties00.smalt.audio.voice.internal.OouraRealFft;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.BINS;
import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.FFT_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.FRAME_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.ns.NsConstants.OVERLAP_SIZE;

/**
 * Suppresses stationary noise in one or more channels of 10 ms frames, split into 16 kHz bands.
 *
 * <p>Each frame is first {@linkplain #analyze(float[][][]) analyzed}, before any echo
 * cancellation, to update the noise and speech models of every channel, then
 * {@linkplain #process(float[][][]) processed}: a Wiener filter derived from the models, the
 * lowest gain of every channel's filter in each bin, is applied to the lowest band in a 256-point
 * windowed overlap-add filter bank, and a single gain derived from the high-frequency bins to the
 * upper bands. The output of every band is delayed by 96 samples relative to its input. Samples
 * are in the 16-bit range ({@code [-32768, 32767]}) and the output is clamped to it.
 *
 * @implNote This implementation ports WebRTC's
 * {@code modules/audio_processing/ns/noise_suppressor.cc} (webrtc-audio-processing 2.1).
 */
public final class NoiseSuppressor {
    /**
     * The first 96 coefficients of the hybrid Hanning and flat analysis and synthesis window,
     * which is symmetric and flat between 96 and 160.
     *
     * @implNote This implementation copies {@code kBlocks160w256FirstHalf} of
     * {@code noise_suppressor.cc}.
     */
    private static final float[] WINDOW_FIRST_HALF = {
            0.00000000f, 0.01636173f, 0.03271908f, 0.04906767f, 0.06540313f,
            0.08172107f, 0.09801714f, 0.11428696f, 0.13052619f, 0.14673047f,
            0.16289547f, 0.17901686f, 0.19509032f, 0.21111155f, 0.22707626f,
            0.24298018f, 0.25881905f, 0.27458862f, 0.29028468f, 0.30590302f,
            0.32143947f, 0.33688985f, 0.35225005f, 0.36751594f, 0.38268343f,
            0.39774847f, 0.41270703f, 0.42755509f, 0.44228869f, 0.45690388f,
            0.47139674f, 0.48576339f, 0.50000000f, 0.51410274f, 0.52806785f,
            0.54189158f, 0.55557023f, 0.56910015f, 0.58247770f, 0.59569930f,
            0.60876143f, 0.62166057f, 0.63439328f, 0.64695615f, 0.65934582f,
            0.67155895f, 0.68359230f, 0.69544264f, 0.70710678f, 0.71858162f,
            0.72986407f, 0.74095113f, 0.75183981f, 0.76252720f, 0.77301045f,
            0.78328675f, 0.79335334f, 0.80320753f, 0.81284668f, 0.82226822f,
            0.83146961f, 0.84044840f, 0.84920218f, 0.85772861f, 0.86602540f,
            0.87409034f, 0.88192126f, 0.88951608f, 0.89687274f, 0.90398929f,
            0.91086382f, 0.91749450f, 0.92387953f, 0.93001722f, 0.93590593f,
            0.94154407f, 0.94693013f, 0.95206268f, 0.95694034f, 0.96156180f,
            0.96592583f, 0.97003125f, 0.97387698f, 0.97746197f, 0.98078528f,
            0.98384601f, 0.98664333f, 0.98917651f, 0.99144486f, 0.99344778f,
            0.99518473f, 0.99665524f, 0.99785892f, 0.99879546f, 0.99946459f,
            0.99986614f
    };

    /**
     * The number of high-frequency bins the upper-band gain averages.
     */
    private static final int NUM_AVG_BINS = 32;

    /**
     * The suppression level tuning.
     */
    private final SuppressionParams params;

    /**
     * The number of bands.
     */
    private final int numBands;

    /**
     * The state of every channel.
     */
    private final ChannelState[] channels;

    /**
     * The number of analyzed non-silent frames minus one, saturating at
     * {@link Integer#MAX_VALUE}.
     */
    private int numAnalyzedFrames = -1;

    /**
     * The 256-point transform of the filter bank.
     */
    private final OouraRealFft fft = new OouraRealFft(FFT_SIZE);

    /**
     * The scratch buffer holding the windowed frame of the analysis.
     */
    private final float[] analysisFrame = new float[FFT_SIZE];

    /**
     * The scratch buffer holding the real parts of the analysis spectrum.
     */
    private final float[] analysisReal = new float[FFT_SIZE];

    /**
     * The scratch buffer holding the imaginary parts of the analysis spectrum.
     */
    private final float[] analysisImag = new float[FFT_SIZE];

    /**
     * The scratch buffer holding a magnitude spectrum.
     */
    private final float[] signalSpectrum = new float[BINS];

    /**
     * The scratch buffer holding the posterior SNR.
     */
    private final float[] postSnr = new float[BINS];

    /**
     * The scratch buffer holding the prior SNR.
     */
    private final float[] priorSnr = new float[BINS];

    /**
     * The filter aggregated over the channels.
     */
    private final float[] aggregatedFilter = new float[BINS];

    /**
     * The scratch buffer of a delayed upper band.
     */
    private final float[] delayedFrame = new float[FRAME_SIZE];

    /**
     * Creates a noise suppressor for a target attenuation level.
     *
     * @param level the level: {@code 0} for 6 dB, {@code 1} for 12 dB, {@code 2} for 18 dB and
     * {@code 3} for 21 dB
     * @param numBands the number of bands, 1 at 16 kHz, 2 at 32 kHz and 3 at 48 kHz
     * @param numChannels the number of channels
     * @throws IllegalArgumentException if {@code level} is outside {@code [0, 3]}
     */
    public NoiseSuppressor(int level, int numBands, int numChannels) {
        this.params = SuppressionParams.forLevel(level);
        this.numBands = numBands;
        this.channels = new ChannelState[numChannels];
        for (var ch = 0; ch < numChannels; ch++) {
            channels[ch] = new ChannelState(params, numBands);
        }
    }

    /**
     * Updates the noise and speech models of every channel with a frame, unless the lowest band
     * of every channel and its analysis window history are all zero.
     *
     * @param bands the bands of every channel, {@code [channel][band]}, 160 samples each; only the
     * lowest band is read
     */
    public void analyze(float[][][] bands) {
        for (var channel : channels) {
            channel.noiseEstimator.prepareAnalysis();
        }
        var zeroFrame = true;
        for (var ch = 0; ch < channels.length; ch++) {
            var memory = channels[ch].analyzeAnalysisMemory;
            var frame = bands[ch][0];
            var energy = 0.f;
            for (var i = 0; i < OVERLAP_SIZE; i++) {
                energy += memory[i] * memory[i];
            }
            for (var i = 0; i < FRAME_SIZE; i++) {
                energy += frame[i] * frame[i];
            }
            if (energy > 0.f) {
                zeroFrame = false;
                break;
            }
        }
        if (zeroFrame) {
            return;
        }
        if (++numAnalyzedFrames < 0) {
            numAnalyzedFrames = 0;
        }
        var real = analysisReal;
        var imag = analysisImag;
        for (var ch = 0; ch < channels.length; ch++) {
            var state = channels[ch];
            var extended = analysisFrame;
            formExtendedFrame(bands[ch][0], state.analyzeAnalysisMemory, extended);
            applyFilterBankWindow(extended);
            forwardFft(extended, real, imag);
            computeMagnitudeSpectrum(real, imag, signalSpectrum);
            var signalEnergy = 0.f;
            for (var i = 0; i < BINS; ++i) {
                signalEnergy += real[i] * real[i] + imag[i] * imag[i];
            }
            signalEnergy /= BINS;
            var signalSpectralSum = 0.f;
            for (var i = 0; i < BINS; ++i) {
                signalSpectralSum += signalSpectrum[i];
            }
            state.noiseEstimator.preUpdate(numAnalyzedFrames, signalSpectrum, signalSpectralSum);
            computeSnr(state.wienerFilter.filter(), state.prevAnalysisSignalSpectrum, signalSpectrum,
                    state.noiseEstimator.prevNoiseSpectrum(), state.noiseEstimator.noiseSpectrum(), priorSnr, postSnr);
            state.speechProbabilityEstimator.update(numAnalyzedFrames, priorSnr, postSnr,
                    state.noiseEstimator.conservativeNoiseSpectrum(), signalSpectrum, signalSpectralSum, signalEnergy);
            state.noiseEstimator.postUpdate(state.speechProbabilityEstimator.probability(), signalSpectrum);
            System.arraycopy(signalSpectrum, 0, state.prevAnalysisSignalSpectrum, 0, BINS);
        }
    }

    /**
     * Applies the noise suppression filter to a frame in place.
     *
     * @param bands the bands of every channel, {@code [channel][band]}, 160 samples each; receive
     * the suppressed samples, clamped to the 16-bit range
     */
    public void process(float[][][] bands) {
        for (var ch = 0; ch < channels.length; ch++) {
            var state = channels[ch];
            var extended = state.extendedFrame;
            formExtendedFrame(bands[ch][0], state.processAnalysisMemory, extended);
            applyFilterBankWindow(extended);
            state.energyBeforeFiltering = energy(extended);
            forwardFft(extended, state.real, state.imag);
            computeMagnitudeSpectrum(state.real, state.imag, signalSpectrum);
            state.wienerFilter.update(numAnalyzedFrames, state.noiseEstimator.noiseSpectrum(),
                    state.noiseEstimator.prevNoiseSpectrum(), state.noiseEstimator.parametricNoiseSpectrum(),
                    signalSpectrum);
            if (numBands > 1) {
                state.upperBandGain = computeUpperBandsGain(params.minimumAttenuatingGain(),
                        state.wienerFilter.filter(), state.speechProbabilityEstimator.probability(),
                        state.prevAnalysisSignalSpectrum, signalSpectrum);
            }
        }
        float[] filter;
        if (channels.length == 1) {
            filter = channels[0].wienerFilter.filter();
        } else {
            filter = aggregatedFilter;
            System.arraycopy(channels[0].wienerFilter.filter(), 0, filter, 0, BINS);
            for (var ch = 1; ch < channels.length; ch++) {
                var filterCh = channels[ch].wienerFilter.filter();
                for (var k = 0; k < BINS; k++) {
                    filter[k] = Math.min(filter[k], filterCh[k]);
                }
            }
        }
        for (var state : channels) {
            for (var i = 0; i < BINS; ++i) {
                state.real[i] *= filter[i];
                state.imag[i] *= filter[i];
            }
        }
        for (var state : channels) {
            inverseFft(state.real, state.imag, state.extendedFrame);
        }
        for (var state : channels) {
            var energyAfterFiltering = energy(state.extendedFrame);
            applyFilterBankWindow(state.extendedFrame);
            state.gainAdjustment = state.wienerFilter.computeOverallScalingFactor(numAnalyzedFrames,
                    state.speechProbabilityEstimator.priorProbability(), state.energyBeforeFiltering,
                    energyAfterFiltering);
        }
        var gainAdjustment = channels[0].gainAdjustment;
        for (var ch = 1; ch < channels.length; ch++) {
            gainAdjustment = Math.min(gainAdjustment, channels[ch].gainAdjustment);
        }
        for (var state : channels) {
            var extended = state.extendedFrame;
            for (var i = 0; i < FFT_SIZE; ++i) {
                extended[i] = gainAdjustment * extended[i];
            }
        }
        for (var ch = 0; ch < channels.length; ch++) {
            var state = channels[ch];
            var extended = state.extendedFrame;
            var frame = bands[ch][0];
            for (var i = 0; i < OVERLAP_SIZE; ++i) {
                frame[i] = state.processSynthesisMemory[i] + extended[i];
            }
            System.arraycopy(extended, OVERLAP_SIZE, frame, OVERLAP_SIZE, FRAME_SIZE - OVERLAP_SIZE);
            System.arraycopy(extended, FRAME_SIZE, state.processSynthesisMemory, 0, OVERLAP_SIZE);
        }
        if (numBands > 1) {
            var upperBandGain = channels[0].upperBandGain;
            for (var ch = 1; ch < channels.length; ch++) {
                upperBandGain = Math.min(upperBandGain, channels[ch].upperBandGain);
            }
            var delayed = delayedFrame;
            for (var ch = 0; ch < channels.length; ch++) {
                for (var b = 1; b < numBands; ++b) {
                    var band = bands[ch][b];
                    var delayMemory = channels[ch].processDelayMemory[b - 1];
                    var fromFrame = FRAME_SIZE - (FFT_SIZE - FRAME_SIZE);
                    System.arraycopy(delayMemory, 0, delayed, 0, OVERLAP_SIZE);
                    System.arraycopy(band, 0, delayed, OVERLAP_SIZE, fromFrame);
                    System.arraycopy(band, fromFrame, delayMemory, 0, OVERLAP_SIZE);
                    for (var j = 0; j < FRAME_SIZE; j++) {
                        band[j] = upperBandGain * delayed[j];
                    }
                }
            }
        }
        for (var ch = 0; ch < channels.length; ch++) {
            for (var b = 0; b < numBands; ++b) {
                var band = bands[ch][b];
                for (var j = 0; j < FRAME_SIZE; j++) {
                    band[j] = FloatMath.min(FloatMath.max(band[j], -32768.f), 32767.f);
                }
            }
        }
    }

    /**
     * Computes the gain of the upper bands from the speech probability and the Wiener filter of
     * the highest bins ({@code ComputeUpperBandsGain}).
     *
     * @param minimumAttenuatingGain the lowest gain
     * @param filter the Wiener filter
     * @param speechProbability the speech probability of every bin
     * @param prevAnalysisSignalSpectrum the magnitude spectrum of the last analyzed frame
     * @param signalSpectrum the magnitude spectrum of the processed frame
     * @return the gain, in {@code [minimumAttenuatingGain, 1]}
     */
    private static float computeUpperBandsGain(float minimumAttenuatingGain, float[] filter, float[] speechProbability,
                                               float[] prevAnalysisSignalSpectrum, float[] signalSpectrum) {
        var oneByNumAvgBins = 1.f / NUM_AVG_BINS;
        var avgProbSpeech = 0.f;
        var avgFilterGain = 0.f;
        for (var i = BINS - NUM_AVG_BINS - 1; i < BINS - 1; i++) {
            avgProbSpeech += speechProbability[i];
            avgFilterGain += filter[i];
        }
        avgProbSpeech = avgProbSpeech * oneByNumAvgBins;
        avgFilterGain = avgFilterGain * oneByNumAvgBins;
        var sumAnalysisSpectrum = 0.f;
        var sumProcessingSpectrum = 0.f;
        for (var i = 0; i < BINS; ++i) {
            sumAnalysisSpectrum += prevAnalysisSignalSpectrum[i];
            sumProcessingSpectrum += signalSpectrum[i];
        }
        avgProbSpeech *= sumProcessingSpectrum / sumAnalysisSpectrum;
        var gain = 0.5f * (1.f + FloatMath.tanhf(2.f * avgProbSpeech - 1.f));
        if (avgProbSpeech >= 0.5f) {
            gain = 0.25f * gain + 0.75f * avgFilterGain;
        } else {
            gain = 0.5f * gain + 0.5f * avgFilterGain;
        }
        return FloatMath.min(FloatMath.max(gain, minimumAttenuatingGain), 1.f);
    }

    /**
     * Prepends the remembered samples to a frame and remembers its last samples.
     *
     * @param frame the 160 samples of the frame
     * @param oldData the remembered samples, {@value NsConstants#OVERLAP_SIZE} entries; receives
     * the frame's last samples
     * @param extended receives the extended frame, {@value NsConstants#FFT_SIZE} entries
     */
    private static void formExtendedFrame(float[] frame, float[] oldData, float[] extended) {
        System.arraycopy(oldData, 0, extended, 0, OVERLAP_SIZE);
        System.arraycopy(frame, 0, extended, OVERLAP_SIZE, FRAME_SIZE);
        System.arraycopy(extended, FFT_SIZE - OVERLAP_SIZE, oldData, 0, OVERLAP_SIZE);
    }

    /**
     * Multiplies an extended frame by the filter bank window.
     *
     * @param x the extended frame, {@value NsConstants#FFT_SIZE} entries
     */
    private static void applyFilterBankWindow(float[] x) {
        for (var i = 0; i < 96; ++i) {
            x[i] = WINDOW_FIRST_HALF[i] * x[i];
        }
        for (int i = 161, k = 95; i < FFT_SIZE; ++i, --k) {
            x[i] = WINDOW_FIRST_HALF[k] * x[i];
        }
    }

    /**
     * Returns the energy of an extended frame.
     *
     * @param x the extended frame, {@value NsConstants#FFT_SIZE} entries
     * @return the sum of the squared samples
     */
    private static float energy(float[] x) {
        var energy = 0.f;
        for (var i = 0; i < FFT_SIZE; i++) {
            energy += x[i] * x[i];
        }
        return energy;
    }

    /**
     * Transforms an extended frame into its real and imaginary spectrum parts.
     *
     * @param timeData the extended frame; overwritten with the packed transform
     * @param real receives the real parts of bins 0 to 128
     * @param imag receives the imaginary parts of bins 0 to 128
     */
    private void forwardFft(float[] timeData, float[] real, float[] imag) {
        fft.forward(timeData);
        imag[0] = 0;
        real[0] = timeData[0];
        imag[BINS - 1] = 0;
        real[BINS - 1] = timeData[1];
        for (var i = 1; i < BINS - 1; ++i) {
            real[i] = timeData[2 * i];
            imag[i] = timeData[2 * i + 1];
        }
    }

    /**
     * Transforms a spectrum back into a time-domain frame.
     *
     * @param real the real parts of bins 0 to 128
     * @param imag the imaginary parts of bins 0 to 128
     * @param timeData receives the frame, {@value NsConstants#FFT_SIZE} entries
     */
    private void inverseFft(float[] real, float[] imag, float[] timeData) {
        timeData[0] = real[0];
        timeData[1] = real[BINS - 1];
        for (var i = 1; i < BINS - 1; ++i) {
            timeData[2 * i] = real[i];
            timeData[2 * i + 1] = imag[i];
        }
        fft.inverse(timeData);
        var scaling = 2.f / FFT_SIZE;
        for (var i = 0; i < FFT_SIZE; i++) {
            timeData[i] *= scaling;
        }
    }

    /**
     * Computes the magnitude spectrum, offset by one to keep it strictly positive.
     *
     * @param real the real parts
     * @param imag the imaginary parts
     * @param signalSpectrum receives the magnitudes, {@value NsConstants#BINS} bins
     */
    private static void computeMagnitudeSpectrum(float[] real, float[] imag, float[] signalSpectrum) {
        signalSpectrum[0] = Math.abs(real[0]) + 1.f;
        signalSpectrum[BINS - 1] = Math.abs(real[BINS - 1]) + 1.f;
        for (var i = 1; i < BINS - 1; ++i) {
            signalSpectrum[i] = (float) Math.sqrt(real[i] * real[i] + imag[i] * imag[i]) + 1.f;
        }
    }

    /**
     * Computes the decision-directed prior SNR and the posterior SNR.
     *
     * @param filter the current Wiener filter
     * @param prevSignalSpectrum the magnitude spectrum of the previous frame
     * @param signalSpectrum the magnitude spectrum of the frame
     * @param prevNoiseSpectrum the noise spectrum of the previous frame
     * @param noiseSpectrum the noise spectrum
     * @param priorSnr receives the prior SNR
     * @param postSnr receives the posterior SNR
     */
    private static void computeSnr(float[] filter, float[] prevSignalSpectrum, float[] signalSpectrum,
                                   float[] prevNoiseSpectrum, float[] noiseSpectrum, float[] priorSnr, float[] postSnr) {
        for (var i = 0; i < BINS; ++i) {
            var prevEstimate = prevSignalSpectrum[i] / (prevNoiseSpectrum[i] + 0.0001f) * filter[i];
            if (signalSpectrum[i] > noiseSpectrum[i]) {
                postSnr[i] = signalSpectrum[i] / (noiseSpectrum[i] + 0.0001f) - 1.f;
            } else {
                postSnr[i] = 0.f;
            }
            priorSnr[i] = 0.98f * prevEstimate + (1.f - 0.98f) * postSnr[i];
        }
    }

    /**
     * The models and filter bank memories of one channel.
     */
    private static final class ChannelState {
        /**
         * The speech probability estimator.
         */
        final SpeechProbabilityEstimator speechProbabilityEstimator = new SpeechProbabilityEstimator();

        /**
         * The Wiener filter.
         */
        final WienerFilter wienerFilter;

        /**
         * The noise estimator.
         */
        final NoiseEstimator noiseEstimator;

        /**
         * The magnitude spectrum of the last analyzed frame.
         */
        final float[] prevAnalysisSignalSpectrum = new float[BINS];

        /**
         * The past samples the analysis window extends over.
         */
        final float[] analyzeAnalysisMemory = new float[OVERLAP_SIZE];

        /**
         * The past samples the processing window extends over.
         */
        final float[] processAnalysisMemory = new float[OVERLAP_SIZE];

        /**
         * The overlap-add tail of the previous synthesis.
         */
        final float[] processSynthesisMemory = new float[OVERLAP_SIZE];

        /**
         * The delay lines of the upper bands, one per upper band.
         */
        final float[][] processDelayMemory;

        /**
         * The windowed frame of the processing.
         */
        final float[] extendedFrame = new float[FFT_SIZE];

        /**
         * The real parts of the processing spectrum.
         */
        final float[] real = new float[FFT_SIZE];

        /**
         * The imaginary parts of the processing spectrum.
         */
        final float[] imag = new float[FFT_SIZE];

        /**
         * The energy of the windowed frame before filtering.
         */
        float energyBeforeFiltering;

        /**
         * The overall scaling factor of the frame.
         */
        float gainAdjustment;

        /**
         * The gain of the upper bands of the frame.
         */
        float upperBandGain;

        /**
         * Creates the state of a channel.
         *
         * @param params the suppression level tuning
         * @param numBands the number of bands
         */
        ChannelState(SuppressionParams params, int numBands) {
            this.wienerFilter = new WienerFilter(params);
            this.noiseEstimator = new NoiseEstimator(params);
            this.processDelayMemory = new float[Math.max(numBands - 1, 0)][OVERLAP_SIZE];
            Arrays.fill(prevAnalysisSignalSpectrum, 1.f);
        }
    }
}
