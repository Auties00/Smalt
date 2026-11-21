/* Ports libopus 1.5.2 (dnn/fargan.c, dnn/fargan.h). Copyright Amazon; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

import java.util.Arrays;

/**
 * Synthesizes 16 kHz speech from LPCNet features with the FARGAN vocoder ({@code FARGANState}), in 10 ms frames
 * of four 2.5 ms subframes.
 *
 * <p>Instances are not thread-safe.
 */
final class Fargan {
    /**
     * The number of samples of history a continuation needs ({@code FARGAN_CONT_SAMPLES}).
     */
    static final int CONT_SAMPLES = 320;

    private static final int NB_SUBFRAMES = 4;
    private static final int SUBFRAME_SIZE = 40;
    private static final int FRAME_SIZE = NB_SUBFRAMES * SUBFRAME_SIZE;
    private static final int COND_SIZE = FarganModel.COND_NET_FDENSE2_OUT_SIZE / NB_SUBFRAMES;
    private static final float DEEMPHASIS = 0.85f;
    private static final int SIG_NET_INPUT_SIZE = COND_SIZE + 2 * SUBFRAME_SIZE + 4;
    private static final int SIG_NET_FWC0_STATE_SIZE = 2 * SIG_NET_INPUT_SIZE;
    private static final int NB_FEATURES = LpcnetEncoder.NB_FEATURES;
    private static final int PITCH_MAX_PERIOD = PitchDnn.PITCH_MAX_PERIOD;
    private static final int GRU1_OUT = FarganModel.SIG_NET_GRU1_OUT_SIZE;
    private static final int GRU2_OUT = FarganModel.SIG_NET_GRU2_OUT_SIZE;
    private static final int GRU3_OUT = FarganModel.SIG_NET_GRU3_OUT_SIZE;
    private static final int FWC0_OUT = FarganModel.SIG_NET_FWC0_CONV_OUT_SIZE;
    private static final int GATE_OUT = FarganModel.SIG_NET_FWC0_GLU_GATE_OUT_SIZE;

    private final FarganModel model;
    private final Nnet nnet;
    private boolean contInitialized;
    private float deemphMem;
    private final float[] pitchBuf = new float[PITCH_MAX_PERIOD];
    private final float[] condConv1State = new float[FarganModel.COND_NET_FCONV1_STATE_SIZE];
    private final float[] fwc0Mem = new float[SIG_NET_FWC0_STATE_SIZE];
    private final float[] gru1State = new float[FarganModel.SIG_NET_GRU1_STATE_SIZE];
    private final float[] gru2State = new float[FarganModel.SIG_NET_GRU2_STATE_SIZE];
    private final float[] gru3State = new float[FarganModel.SIG_NET_GRU3_STATE_SIZE];
    private int lastPeriod;

    private final float[] denseIn = new float[NB_FEATURES + FarganModel.COND_NET_PEMBED_OUT_SIZE];
    private final float[] conv1In = new float[FarganModel.COND_NET_FCONV1_IN_SIZE];
    private final float[] fdense2In = new float[FarganModel.COND_NET_FCONV1_OUT_SIZE];
    private final float[] cond = new float[FarganModel.COND_NET_FDENSE2_OUT_SIZE];
    private final float[] fwc0In = new float[SIG_NET_INPUT_SIZE];
    private final float[] gru1In = new float[FWC0_OUT + 2 * SUBFRAME_SIZE];
    private final float[] gru2In = new float[GRU1_OUT + 2 * SUBFRAME_SIZE];
    private final float[] gru3In = new float[GRU2_OUT + 2 * SUBFRAME_SIZE];
    private final float[] pred = new float[SUBFRAME_SIZE + 4];
    private final float[] prev = new float[SUBFRAME_SIZE];
    private final float[] pitchGate = new float[4];
    private final float[] gain = new float[1];
    private final float[] skipCat = new float[GRU1_OUT + GRU2_OUT + GRU3_OUT + FWC0_OUT + 2 * SUBFRAME_SIZE];
    private final float[] skipOut = new float[FarganModel.SIG_NET_SKIP_DENSE_OUT_SIZE];
    private final float[] x0 = new float[CONT_SAMPLES];
    private final float[] dummy = new float[SUBFRAME_SIZE];
    private final float[] fpcm = new float[FRAME_SIZE];

    /**
     * Constructs a vocoder with a cleared state ({@code fargan_init}).
     *
     * @param model the network
     * @param nnet the scratch space of the network evaluation
     */
    Fargan(FarganModel model, Nnet nnet) {
        this.model = model;
        this.nnet = nnet;
    }

    /**
     * Computes the conditioning of one frame ({@code compute_fargan_cond}).
     */
    private void computeCond(float[] features, int featuresOffset, int period) {
        var embedding = Math.max(0, Math.min(period - 32, 223)) * FarganModel.COND_NET_PEMBED_OUT_SIZE;
        System.arraycopy(model.cond_net_pembed.float_weights, embedding, denseIn, NB_FEATURES,
                FarganModel.COND_NET_PEMBED_OUT_SIZE);
        System.arraycopy(features, featuresOffset, denseIn, 0, NB_FEATURES);
        nnet.dense(model.cond_net_fdense1, conv1In, 0, denseIn, 0, Nnet.ACTIVATION_TANH);
        nnet.conv1d(model.cond_net_fconv1, fdense2In, 0, condConv1State, 0, conv1In, 0,
                FarganModel.COND_NET_FCONV1_IN_SIZE, Nnet.ACTIVATION_TANH);
        nnet.dense(model.cond_net_fdense2, cond, 0, fdense2In, 0, Nnet.ACTIVATION_TANH);
    }

    /**
     * Synthesizes one subframe ({@code run_fargan_subframe}).
     */
    private void runSubframe(float[] pcm, int pcmOffset, int condOffset, int period) {
        nnet.dense(model.sig_net_cond_gain_dense, gain, 0, cond, condOffset, Nnet.ACTIVATION_LINEAR);
        var g = (float) StrictMath.exp(gain[0]);
        var g1 = 1.f / (1e-5f + g);
        var pos = PITCH_MAX_PERIOD - period - 2;
        for (var i = 0; i < SUBFRAME_SIZE + 4; i++) {
            var v = g1 * pitchBuf[Math.max(0, pos)];
            v = -1.f > v ? -1.f : v;
            pred[i] = 1.f < v ? 1.f : v;
            pos++;
            if (pos == PITCH_MAX_PERIOD) {
                pos -= period;
            }
        }
        for (var i = 0; i < SUBFRAME_SIZE; i++) {
            var v = g1 * pitchBuf[PITCH_MAX_PERIOD - SUBFRAME_SIZE + i];
            v = 1.f < v ? 1.f : v;
            prev[i] = -1.f > v ? -1.f : v;
        }
        System.arraycopy(cond, condOffset, fwc0In, 0, COND_SIZE);
        System.arraycopy(pred, 0, fwc0In, COND_SIZE, SUBFRAME_SIZE + 4);
        System.arraycopy(prev, 0, fwc0In, COND_SIZE + SUBFRAME_SIZE + 4, SUBFRAME_SIZE);
        nnet.conv1d(model.sig_net_fwc0_conv, gru1In, 0, fwc0Mem, 0, fwc0In, 0, SIG_NET_INPUT_SIZE,
                Nnet.ACTIVATION_TANH);
        nnet.glu(model.sig_net_fwc0_glu_gate, gru1In, 0, gru1In, 0);
        nnet.dense(model.sig_net_gain_dense_out, pitchGate, 0, gru1In, 0, Nnet.ACTIVATION_SIGMOID);
        for (var i = 0; i < SUBFRAME_SIZE; i++) {
            gru1In[GATE_OUT + i] = pitchGate[0] * pred[i + 2];
        }
        System.arraycopy(prev, 0, gru1In, GATE_OUT + SUBFRAME_SIZE, SUBFRAME_SIZE);
        nnet.gru(model.sig_net_gru1_input, model.sig_net_gru1_recurrent, gru1State, 0, gru1In, 0);
        nnet.glu(model.sig_net_gru1_glu_gate, gru2In, 0, gru1State, 0);
        for (var i = 0; i < SUBFRAME_SIZE; i++) {
            gru2In[GRU1_OUT + i] = pitchGate[1] * pred[i + 2];
        }
        System.arraycopy(prev, 0, gru2In, GRU1_OUT + SUBFRAME_SIZE, SUBFRAME_SIZE);
        nnet.gru(model.sig_net_gru2_input, model.sig_net_gru2_recurrent, gru2State, 0, gru2In, 0);
        nnet.glu(model.sig_net_gru2_glu_gate, gru3In, 0, gru2State, 0);
        for (var i = 0; i < SUBFRAME_SIZE; i++) {
            gru3In[GRU2_OUT + i] = pitchGate[2] * pred[i + 2];
        }
        System.arraycopy(prev, 0, gru3In, GRU2_OUT + SUBFRAME_SIZE, SUBFRAME_SIZE);
        nnet.gru(model.sig_net_gru3_input, model.sig_net_gru3_recurrent, gru3State, 0, gru3In, 0);
        nnet.glu(model.sig_net_gru3_glu_gate, skipCat, GRU1_OUT + GRU2_OUT, gru3State, 0);
        System.arraycopy(gru2In, 0, skipCat, 0, GRU1_OUT);
        System.arraycopy(gru3In, 0, skipCat, GRU1_OUT, GRU2_OUT);
        var base = GRU1_OUT + GRU2_OUT + GRU3_OUT;
        System.arraycopy(gru1In, 0, skipCat, base, FWC0_OUT);
        for (var i = 0; i < SUBFRAME_SIZE; i++) {
            skipCat[base + FWC0_OUT + i] = pitchGate[3] * pred[i + 2];
        }
        System.arraycopy(prev, 0, skipCat, base + FWC0_OUT + SUBFRAME_SIZE, SUBFRAME_SIZE);
        nnet.dense(model.sig_net_skip_dense, skipOut, 0, skipCat, 0, Nnet.ACTIVATION_TANH);
        nnet.glu(model.sig_net_skip_glu_gate, skipOut, 0, skipOut, 0);
        nnet.dense(model.sig_net_sig_dense_out, pcm, pcmOffset, skipOut, 0, Nnet.ACTIVATION_TANH);
        for (var i = 0; i < SUBFRAME_SIZE; i++) {
            pcm[pcmOffset + i] *= g;
        }
        System.arraycopy(pitchBuf, SUBFRAME_SIZE, pitchBuf, 0, PITCH_MAX_PERIOD - SUBFRAME_SIZE);
        System.arraycopy(pcm, pcmOffset, pitchBuf, PITCH_MAX_PERIOD - SUBFRAME_SIZE, SUBFRAME_SIZE);
        for (var i = 0; i < SUBFRAME_SIZE; i++) {
            pcm[pcmOffset + i] += DEEMPHASIS * deemphMem;
            deemphMem = pcm[pcmOffset + i];
        }
    }

    /**
     * Primes the vocoder with past audio and the features of the five frames that end with it
     * ({@code fargan_cont}).
     *
     * @param pcm0 the samples, nominally in {@code [-1, 1]}
     * @param pcm0Offset the index of the first of {@link #CONT_SAMPLES} samples
     * @param features0 five frames of {@link LpcnetEncoder#NB_FEATURES} features
     */
    void cont(float[] pcm0, int pcm0Offset, float[] features0) {
        var period = 0;
        for (var i = 0; i < 5; i++) {
            var offset = i * NB_FEATURES;
            lastPeriod = period;
            period = LpcnetEncoder.period(features0[offset + Freq.NB_BANDS]);
            computeCond(features0, offset, period);
        }
        x0[0] = 0;
        for (var i = 1; i < CONT_SAMPLES; i++) {
            x0[i] = pcm0[pcm0Offset + i] - DEEMPHASIS * pcm0[pcm0Offset + i - 1];
        }
        System.arraycopy(x0, 0, pitchBuf, PITCH_MAX_PERIOD - FRAME_SIZE, FRAME_SIZE);
        contInitialized = true;
        for (var i = 0; i < NB_SUBFRAMES; i++) {
            runSubframe(dummy, 0, i * COND_SIZE, lastPeriod);
            System.arraycopy(x0, FRAME_SIZE + i * SUBFRAME_SIZE, pitchBuf, PITCH_MAX_PERIOD - SUBFRAME_SIZE,
                    SUBFRAME_SIZE);
        }
        deemphMem = pcm0[pcm0Offset + CONT_SAMPLES - 1];
    }

    /**
     * Synthesizes one 10 ms frame into 16-bit samples ({@code fargan_synthesize_int}).
     *
     * @param pcm receives 160 samples
     * @param pcmOffset the index of the first sample
     * @param features the {@link LpcnetEncoder#NB_FEATURES} features of the frame
     * @throws IllegalStateException if the vocoder was never primed with {@link #cont}
     */
    void synthesize(short[] pcm, int pcmOffset, float[] features) {
        if (!contInitialized) {
            throw new IllegalStateException("FARGAN synthesis before priming");
        }
        var period = LpcnetEncoder.period(features[Freq.NB_BANDS]);
        computeCond(features, 0, period);
        for (var subframe = 0; subframe < NB_SUBFRAMES; subframe++) {
            runSubframe(fpcm, subframe * SUBFRAME_SIZE, subframe * COND_SIZE, lastPeriod);
        }
        lastPeriod = period;
        for (var i = 0; i < FRAME_SIZE; i++) {
            var v = 32768.f * fpcm[i];
            v = -32767 > v ? -32767 : v;
            v = 32767 < v ? 32767 : v;
            pcm[pcmOffset + i] = (short) (int) StrictMath.floor(.5 + v);
        }
    }

    /**
     * Clears the whole state, as a new vocoder has it.
     */
    void reset() {
        contInitialized = false;
        deemphMem = 0;
        Arrays.fill(pitchBuf, 0);
        Arrays.fill(condConv1State, 0);
        Arrays.fill(fwc0Mem, 0);
        Arrays.fill(gru1State, 0);
        Arrays.fill(gru2State, 0);
        Arrays.fill(gru3State, 0);
        lastPeriod = 0;
    }
}
