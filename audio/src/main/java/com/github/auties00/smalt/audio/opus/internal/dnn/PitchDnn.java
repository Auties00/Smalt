/* Ports libopus 1.5.2 (dnn/pitchdnn.c, dnn/pitchdnn.h). Copyright Xiph.Org Foundation and the libopus
   contributors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

/**
 * Estimates the pitch of a frame from its instantaneous-frequency and cross-correlation features with a small
 * convolutional and recurrent network ({@code PitchDNNState}).
 *
 * <p>Instances are not thread-safe.
 */
final class PitchDnn {
    /**
     * The shortest pitch period at 16 kHz ({@code PITCH_MIN_PERIOD}).
     */
    static final int PITCH_MIN_PERIOD = 32;

    /**
     * The longest pitch period at 16 kHz ({@code PITCH_MAX_PERIOD}).
     */
    static final int PITCH_MAX_PERIOD = 256;

    /**
     * The number of cross-correlation features ({@code NB_XCORR_FEATURES}).
     */
    static final int NB_XCORR_FEATURES = PITCH_MAX_PERIOD - PITCH_MIN_PERIOD;

    private final PitchDnnModel model;
    private final Nnet nnet;
    private final float[] gruState = new float[PitchDnnModel.GRU_1_STATE_SIZE];
    private final float[] xcorrMem1 = new float[(NB_XCORR_FEATURES + 2) * 2];
    private final float[] xcorrMem2 = new float[(NB_XCORR_FEATURES + 2) * 2 * 8];
    private final float[] if1Out = new float[PitchDnnModel.DENSE_IF_UPSAMPLER_1_OUT_SIZE];
    private final float[] downsamplerIn = new float[NB_XCORR_FEATURES + PitchDnnModel.DENSE_IF_UPSAMPLER_2_OUT_SIZE];
    private final float[] downsamplerOut = new float[PitchDnnModel.DENSE_DOWNSAMPLER_OUT_SIZE];

    /**
     * The padded input of the first convolution; its borders stay zero.
     */
    private final float[] conv1Tmp1 = new float[(NB_XCORR_FEATURES + 2) * 8];

    /**
     * The padded output of the first convolution; its borders stay zero.
     */
    private final float[] conv1Tmp2 = new float[(NB_XCORR_FEATURES + 2) * 8];

    private final float[] output = new float[PitchDnnModel.DENSE_FINAL_UPSAMPLER_OUT_SIZE];

    /**
     * Constructs an estimator with a cleared state ({@code pitchdnn_init}).
     *
     * @param model the network
     * @param nnet the scratch space of the network evaluation
     */
    PitchDnn(PitchDnnModel model, Nnet nnet) {
        this.model = model;
        this.nnet = nnet;
    }

    /**
     * Clears the recurrent and convolution state.
     */
    void reset() {
        java.util.Arrays.fill(gruState, 0);
        java.util.Arrays.fill(xcorrMem1, 0);
        java.util.Arrays.fill(xcorrMem2, 0);
    }

    /**
     * Estimates the pitch of one frame ({@code compute_pitchdnn}).
     *
     * @param ifFeatures the 88 instantaneous-frequency features
     * @param xcorrFeatures the {@link #NB_XCORR_FEATURES} normalized cross-correlations
     * @return the pitch as {@code log2(256 / period) - 1.5}, in units of 60 per octave
     */
    float compute(float[] ifFeatures, float[] xcorrFeatures) {
        nnet.dense(model.dense_if_upsampler_1, if1Out, 0, ifFeatures, 0, Nnet.ACTIVATION_TANH);
        nnet.dense(model.dense_if_upsampler_2, downsamplerIn, NB_XCORR_FEATURES, if1Out, 0, Nnet.ACTIVATION_TANH);
        System.arraycopy(xcorrFeatures, 0, conv1Tmp1, 1, NB_XCORR_FEATURES);
        nnet.conv2d(model.conv2d_1, conv1Tmp2, 1, xcorrMem1, conv1Tmp1, 0, NB_XCORR_FEATURES, NB_XCORR_FEATURES + 2,
                Nnet.ACTIVATION_TANH);
        nnet.conv2d(model.conv2d_2, downsamplerIn, 0, xcorrMem2, conv1Tmp2, 0, NB_XCORR_FEATURES, NB_XCORR_FEATURES,
                Nnet.ACTIVATION_TANH);
        nnet.dense(model.dense_downsampler, downsamplerOut, 0, downsamplerIn, 0, Nnet.ACTIVATION_TANH);
        nnet.gru(model.gru_1_input, model.gru_1_recurrent, gruState, 0, downsamplerOut, 0);
        nnet.dense(model.dense_final_upsampler, output, 0, gruState, 0, Nnet.ACTIVATION_LINEAR);
        var pos = 0;
        var maxval = -1f;
        for (var i = 0; i < 180; i++) {
            if (output[i] > maxval) {
                pos = i;
                maxval = output[i];
            }
        }
        var sum = 0f;
        var count = 0f;
        for (var i = Math.max(0, pos - 2); i <= Math.min(179, pos + 2); i++) {
            var p = (float) StrictMath.exp(output[i]);
            sum += p * i;
            count += p;
        }
        return (float) ((1.f / 60.f) * (sum / count) - 1.5);
    }
}
