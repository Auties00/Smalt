/* Ports libopus 1.5.2 (dnn/nndsp.c, dnn/nndsp.h). Copyright Amazon; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

import com.github.auties00.smalt.audio.opus.internal.celt.Pitch;

import java.util.Arrays;

/**
 * Runs the adaptive filters of the OSCE enhancers on 5 ms frames: the adaptive convolution, the adaptive comb
 * filter and the adaptive temporal shaper, each with kernels predicted per frame from conditioning features and
 * cross-faded with the previous frame's.
 *
 * <p>Instances own their scratch space and are not thread-safe. Arguments are not checked; values outside the
 * documented ranges give unspecified results.
 */
final class NnDsp {
    /**
     * The longest kernel ({@code ADACONV_MAX_KERNEL_SIZE}).
     */
    static final int MAX_KERNEL_SIZE = 16;

    /**
     * The largest channel count of an adaptive convolution ({@code ADACONV_MAX_INPUT_CHANNELS}).
     */
    static final int MAX_CHANNELS = 2;

    /**
     * The longest frame ({@code ADACONV_MAX_FRAME_SIZE}).
     */
    static final int MAX_FRAME_SIZE = 80;

    /**
     * The longest comb filter lag ({@code ADACOMB_MAX_LAG}).
     */
    static final int MAX_LAG = 300;

    /**
     * The largest input of an adaptive shaper ({@code ADASHAPE_MAX_INPUT_DIM}).
     */
    static final int ADASHAPE_MAX_INPUT_DIM = 512;

    /**
     * The longest frame of an adaptive shaper ({@code ADASHAPE_MAX_FRAME_SIZE}).
     */
    static final int ADASHAPE_MAX_FRAME_SIZE = 160;

    /**
     * The state of an adaptive convolution ({@code AdaConvState}).
     */
    static final class AdaConvState {
        final float[] history = new float[MAX_KERNEL_SIZE * MAX_CHANNELS];
        final float[] lastKernel = new float[MAX_KERNEL_SIZE * MAX_CHANNELS * MAX_CHANNELS];

        /**
         * Clears the state ({@code init_adaconv_state}).
         */
        void reset() {
            Arrays.fill(history, 0);
            Arrays.fill(lastKernel, 0);
        }
    }

    /**
     * The state of an adaptive comb filter ({@code AdaCombState}).
     */
    static final class AdaCombState {
        final float[] history = new float[MAX_KERNEL_SIZE + MAX_LAG];
        final float[] lastKernel = new float[MAX_KERNEL_SIZE];
        float lastGlobalGain;
        int lastPitchLag;

        /**
         * Clears the state ({@code init_adacomb_state}).
         */
        void reset() {
            Arrays.fill(history, 0);
            Arrays.fill(lastKernel, 0);
            lastGlobalGain = 0;
            lastPitchLag = 0;
        }
    }

    /**
     * The state of an adaptive shaper ({@code AdaShapeState}).
     */
    static final class AdaShapeState {
        final float[] convAlpha1fState = new float[ADASHAPE_MAX_INPUT_DIM];
        final float[] convAlpha1tState = new float[ADASHAPE_MAX_INPUT_DIM];
        final float[] convAlpha2State = new float[ADASHAPE_MAX_FRAME_SIZE];

        /**
         * Clears the state ({@code init_adashape_state}).
         */
        void reset() {
            Arrays.fill(convAlpha1fState, 0);
            Arrays.fill(convAlpha1tState, 0);
            Arrays.fill(convAlpha2State, 0);
        }
    }

    private final Nnet nnet;
    private final float[] outputBuffer = new float[MAX_FRAME_SIZE * MAX_CHANNELS];
    private final float[] outputBufferLast = new float[MAX_FRAME_SIZE];
    private final float[] kernelBuffer = new float[MAX_KERNEL_SIZE * MAX_CHANNELS * MAX_CHANNELS];
    private final float[] inputBuffer = new float[MAX_CHANNELS * (MAX_FRAME_SIZE + MAX_KERNEL_SIZE)
            + MAX_FRAME_SIZE + MAX_LAG + MAX_KERNEL_SIZE];
    private final float[] kernel0 = new float[MAX_KERNEL_SIZE];
    private final float[] kernel1 = new float[MAX_KERNEL_SIZE];
    private final float[] channelBuffer0 = new float[MAX_FRAME_SIZE];
    private final float[] channelBuffer1 = new float[MAX_FRAME_SIZE];
    private final float[] gainBuffer = new float[MAX_CHANNELS];
    private final float[] scalar = new float[2];
    private final float[] shapeIn = new float[ADASHAPE_MAX_INPUT_DIM + ADASHAPE_MAX_FRAME_SIZE];
    private final float[] shapeOut = new float[ADASHAPE_MAX_FRAME_SIZE];
    private final float[] shapeTmp = new float[ADASHAPE_MAX_FRAME_SIZE];

    /**
     * Constructs the filters.
     *
     * @param nnet the scratch space of the network evaluation
     */
    NnDsp(Nnet nnet) {
        this.nnet = nnet;
    }

    /**
     * Computes the raised-cosine cross-fade window, falling from 1 to 0 ({@code compute_overlap_window}).
     *
     * @param window receives {@code overlapSize} values
     * @param overlapSize the window length
     */
    static void computeOverlapWindow(float[] window, int overlapSize) {
        for (var i = 0; i < overlapSize; i++) {
            window[i] = (float) (0.5f + 0.5f * StrictMath.cos(Math.PI * (i + 0.5f) / overlapSize));
        }
    }

    /**
     * Normalizes each output channel of a kernel to unit Euclidean norm, then applies its gain
     * ({@code scale_kernel}).
     */
    private static void scaleKernel(float[] kernel, int inChannels, int outChannels, int kernelSize, float[] gain) {
        for (var o = 0; o < outChannels; o++) {
            var norm = 0f;
            for (var i = 0; i < inChannels; i++) {
                for (var k = 0; k < kernelSize; k++) {
                    var v = kernel[(o * inChannels + i) * kernelSize + k];
                    norm += v * v;
                }
            }
            norm = (float) (1.f / (1e-6f + StrictMath.sqrt(norm)));
            for (var i = 0; i < inChannels; i++) {
                for (var k = 0; k < kernelSize; k++) {
                    kernel[(o * inChannels + i) * kernelSize + k] *= norm * gain[o];
                }
            }
        }
    }

    /**
     * Filters one frame with an adaptive convolution ({@code adaconv_process_frame}).
     *
     * @param state the filter state
     * @param xOut receives {@code outChannels} frames, one after the other; may be the input
     * @param xOutOffset the index of the first output sample
     * @param xIn the {@code inChannels} input frames, one after the other
     * @param xInOffset the index of the first input sample
     * @param features the conditioning features
     * @param featuresOffset the index of the first feature
     * @param kernelLayer the kernel predictor
     * @param gainLayer the gain predictor
     * @param frameSize the frame size
     * @param overlapSize the cross-fade length
     * @param inChannels the number of input channels
     * @param outChannels the number of output channels
     * @param kernelSize the kernel length
     * @param leftPadding the kernel's look-back, {@code kernelSize - 1}
     * @param filterGainA the slope of the gain transform
     * @param filterGainB the offset of the gain transform
     * @param window the cross-fade window
     */
    void adaconv(AdaConvState state, float[] xOut, int xOutOffset, float[] xIn, int xInOffset, float[] features,
                 int featuresOffset, LinearLayer kernelLayer, LinearLayer gainLayer, int frameSize, int overlapSize,
                 int inChannels, int outChannels, int kernelSize, int leftPadding, float filterGainA,
                 float filterGainB, float[] window) {
        Arrays.fill(outputBuffer, 0);
        Arrays.fill(kernelBuffer, 0);
        Arrays.fill(inputBuffer, 0);
        for (var c = 0; c < inChannels; c++) {
            System.arraycopy(state.history, c * kernelSize, inputBuffer, c * (kernelSize + frameSize), kernelSize);
            System.arraycopy(xIn, xInOffset + frameSize * c, inputBuffer, kernelSize + c * (kernelSize + frameSize),
                    frameSize);
        }
        var pInput = kernelSize;
        nnet.dense(kernelLayer, kernelBuffer, 0, features, featuresOffset, Nnet.ACTIVATION_LINEAR);
        nnet.dense(gainLayer, gainBuffer, 0, features, featuresOffset, Nnet.ACTIVATION_TANH);
        for (var i = 0; i < outChannels; i++) {
            gainBuffer[i] = (float) StrictMath.exp(filterGainA * gainBuffer[i] + filterGainB);
        }
        scaleKernel(kernelBuffer, inChannels, outChannels, kernelSize, gainBuffer);
        for (var o = 0; o < outChannels; o++) {
            for (var c = 0; c < inChannels; c++) {
                Arrays.fill(kernel0, 0);
                Arrays.fill(kernel1, 0);
                System.arraycopy(state.lastKernel, (o * inChannels + c) * kernelSize, kernel0, 0, kernelSize);
                System.arraycopy(kernelBuffer, (o * inChannels + c) * kernelSize, kernel1, 0, kernelSize);
                var start = pInput + c * (frameSize + kernelSize) - leftPadding;
                Pitch.celtPitchXcorr(kernel0, 0, inputBuffer, start, channelBuffer0, 0, MAX_KERNEL_SIZE, overlapSize);
                Pitch.celtPitchXcorr(kernel1, 0, inputBuffer, start, channelBuffer1, 0, MAX_KERNEL_SIZE, frameSize);
                var out = o * frameSize;
                for (var s = 0; s < overlapSize; s++) {
                    outputBuffer[s + out] += window[s] * channelBuffer0[s];
                    outputBuffer[s + out] += (1.f - window[s]) * channelBuffer1[s];
                }
                for (var s = overlapSize; s < frameSize; s++) {
                    outputBuffer[s + out] += channelBuffer1[s];
                }
            }
        }
        System.arraycopy(outputBuffer, 0, xOut, xOutOffset, outChannels * frameSize);
        for (var c = 0; c < inChannels; c++) {
            System.arraycopy(inputBuffer, pInput + c * (frameSize + kernelSize) + frameSize - kernelSize,
                    state.history, c * kernelSize, kernelSize);
        }
        System.arraycopy(kernelBuffer, 0, state.lastKernel, 0, kernelSize * inChannels * outChannels);
    }

    /**
     * Filters one frame with an adaptive comb filter around the pitch lag ({@code adacomb_process_frame}).
     *
     * @param state the filter state
     * @param xOut receives the frame; may be the input
     * @param xOutOffset the index of the first output sample
     * @param xIn the input frame
     * @param xInOffset the index of the first input sample
     * @param features the conditioning features
     * @param featuresOffset the index of the first feature
     * @param kernelLayer the kernel predictor
     * @param gainLayer the kernel gain predictor
     * @param globalGainLayer the global gain predictor
     * @param pitchLag the pitch lag in samples, at most {@link #MAX_LAG}
     * @param frameSize the frame size
     * @param overlapSize the cross-fade length
     * @param kernelSize the kernel length
     * @param leftPadding the kernel's look-back
     * @param filterGainA the slope of the global gain transform
     * @param filterGainB the offset of the global gain transform
     * @param logGainLimit the logarithm of the largest kernel gain
     * @param window the cross-fade window
     */
    void adacomb(AdaCombState state, float[] xOut, int xOutOffset, float[] xIn, int xInOffset, float[] features,
                 int featuresOffset, LinearLayer kernelLayer, LinearLayer gainLayer, LinearLayer globalGainLayer,
                 int pitchLag, int frameSize, int overlapSize, int kernelSize, int leftPadding, float filterGainA,
                 float filterGainB, float logGainLimit, float[] window) {
        var out = outputBuffer;
        Arrays.fill(out, 0, MAX_FRAME_SIZE, 0);
        Arrays.fill(kernelBuffer, 0, MAX_KERNEL_SIZE, 0);
        Arrays.fill(inputBuffer, 0, MAX_FRAME_SIZE + MAX_LAG + MAX_KERNEL_SIZE, 0);
        System.arraycopy(state.history, 0, inputBuffer, 0, kernelSize + MAX_LAG);
        System.arraycopy(xIn, xInOffset, inputBuffer, kernelSize + MAX_LAG, frameSize);
        var pInput = kernelSize + MAX_LAG;
        nnet.dense(kernelLayer, kernelBuffer, 0, features, featuresOffset, Nnet.ACTIVATION_LINEAR);
        nnet.dense(gainLayer, scalar, 0, features, featuresOffset, Nnet.ACTIVATION_RELU);
        nnet.dense(globalGainLayer, scalar, 1, features, featuresOffset, Nnet.ACTIVATION_TANH);
        scalar[0] = (float) StrictMath.exp(logGainLimit - scalar[0]);
        var globalGain = (float) StrictMath.exp(filterGainA * scalar[1] + filterGainB);
        scaleKernel(kernelBuffer, 1, 1, kernelSize, scalar);
        Arrays.fill(kernel0, 0);
        Arrays.fill(kernel1, 0);
        System.arraycopy(kernelBuffer, 0, kernel1, 0, kernelSize);
        System.arraycopy(state.lastKernel, 0, kernel0, 0, kernelSize);
        Pitch.celtPitchXcorr(kernel0, 0, inputBuffer, pInput - leftPadding - state.lastPitchLag, outputBufferLast, 0,
                MAX_KERNEL_SIZE, overlapSize);
        Pitch.celtPitchXcorr(kernel1, 0, inputBuffer, pInput - leftPadding - pitchLag, out, 0, MAX_KERNEL_SIZE,
                frameSize);
        var lastGain = state.lastGlobalGain;
        for (var s = 0; s < overlapSize; s++) {
            out[s] = lastGain * window[s] * outputBufferLast[s] + globalGain * (1.f - window[s]) * out[s];
        }
        for (var s = 0; s < overlapSize; s++) {
            out[s] += (window[s] * lastGain + (1.f - window[s]) * globalGain) * inputBuffer[pInput + s];
        }
        for (var s = overlapSize; s < frameSize; s++) {
            out[s] = globalGain * (out[s] + inputBuffer[pInput + s]);
        }
        System.arraycopy(out, 0, xOut, xOutOffset, frameSize);
        System.arraycopy(kernelBuffer, 0, state.lastKernel, 0, kernelSize);
        System.arraycopy(inputBuffer, pInput + frameSize - kernelSize - MAX_LAG, state.history, 0,
                kernelSize + MAX_LAG);
        state.lastPitchLag = pitchLag;
        state.lastGlobalGain = globalGain;
    }

    /**
     * Shapes the temporal envelope of one frame with an adaptive gain per sample ({@code adashape_process_frame}).
     *
     * @param state the shaper state
     * @param xOut receives the frame; may be the input
     * @param xOutOffset the index of the first output sample
     * @param xIn the input frame
     * @param xInOffset the index of the first input sample
     * @param features the conditioning features
     * @param featuresOffset the index of the first feature
     * @param alpha1f the feature branch of the first layer
     * @param alpha1t the envelope branch of the first layer
     * @param alpha2 the second layer
     * @param featureDim the number of features
     * @param frameSize the frame size, a multiple of {@code avgPoolK}
     * @param avgPoolK the envelope pooling length
     */
    void adashape(AdaShapeState state, float[] xOut, int xOutOffset, float[] xIn, int xInOffset, float[] features,
                  int featuresOffset, LinearLayer alpha1f, LinearLayer alpha1t, LinearLayer alpha2, int featureDim,
                  int frameSize, int avgPoolK) {
        var in = shapeIn;
        var tenvSize = frameSize / avgPoolK;
        Arrays.fill(in, featureDim, featureDim + tenvSize + 1, 0);
        System.arraycopy(features, featuresOffset, in, 0, featureDim);
        var mean = 0f;
        for (var i = 0; i < tenvSize; i++) {
            var t = featureDim + i;
            for (var k = 0; k < avgPoolK; k++) {
                in[t] = (float) (in[t] + (double) Math.abs(xIn[xInOffset + i * avgPoolK + k]));
            }
            in[t] = (float) StrictMath.log(in[t] / avgPoolK + 1.52587890625e-05f);
            mean += in[t];
        }
        mean /= tenvSize;
        for (var i = 0; i < tenvSize; i++) {
            in[featureDim + i] -= mean;
        }
        in[featureDim + tenvSize] = mean;
        nnet.conv1d(alpha1f, shapeOut, 0, state.convAlpha1fState, 0, in, 0, featureDim, Nnet.ACTIVATION_LINEAR);
        nnet.conv1d(alpha1t, shapeTmp, 0, state.convAlpha1tState, 0, in, featureDim, tenvSize + 1,
                Nnet.ACTIVATION_LINEAR);
        for (var i = 0; i < frameSize; i++) {
            var t = shapeOut[i] + shapeTmp[i];
            in[i] = t >= 0 ? t : (float) (0.2 * t);
        }
        nnet.conv1d(alpha2, shapeOut, 0, state.convAlpha2State, 0, in, 0, frameSize, Nnet.ACTIVATION_LINEAR);
        for (var i = 0; i < frameSize; i++) {
            xOut[xOutOffset + i] = (float) (StrictMath.exp(shapeOut[i]) * xIn[xInOffset + i]);
        }
    }
}
