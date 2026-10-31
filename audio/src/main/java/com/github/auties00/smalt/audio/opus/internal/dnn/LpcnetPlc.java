/* Ports libopus 1.5.2 (dnn/lpcnet_plc.c, dnn/lpcnet_private.h). Copyright Amazon and Mozilla;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

import java.util.Arrays;

/**
 * Conceals lost 10 ms frames of 16 kHz speech with the deep PLC of libopus 1.5.2 ({@code LPCNetPLCState}):
 * a recurrent network predicts the features of the missing frames, or takes them from the DRED features queued
 * with {@link #fecAdd}, and the FARGAN vocoder synthesizes them, continuing the audio that preceded the loss.
 *
 * <p>The decoder feeds every received 10 ms frame to {@link #update} and asks {@link #conceal} for every lost
 * one. The output is bit-identical to the generic C build of libopus 1.5.2. The networks load on the first
 * construction. Instances are not thread-safe.
 */
public final class LpcnetPlc {
    /**
     * The samples of one frame, 10 ms at 16 kHz ({@code FRAME_SIZE}).
     */
    public static final int FRAME_SIZE = Freq.FRAME_SIZE;

    /**
     * The number of features of one DRED frame ({@code NB_FEATURES}).
     */
    public static final int NB_FEATURES = LpcnetEncoder.NB_FEATURES;

    private static final int NB_BANDS = Freq.NB_BANDS;
    private static final int NB_TOTAL_FEATURES = LpcnetEncoder.NB_TOTAL_FEATURES;
    private static final int CONT_VECTORS = 5;
    private static final int PLC_BUF_SIZE = (CONT_VECTORS + 10) * FRAME_SIZE;
    private static final int PLC_MAX_FEC = 100;
    private static final int PLC_INPUT_SIZE = 2 * NB_BANDS + NB_FEATURES + 1;
    private static final int GRU1_SIZE = PlcModel.PLC_GRU1_STATE_SIZE;
    private static final int GRU2_SIZE = PlcModel.PLC_GRU2_STATE_SIZE;

    /**
     * The attenuation of the first concealed frames, in log-energy units.
     */
    private static final float[] ATT_TABLE = {0, 0, -.2f, -.2f, -.4f, -.4f, -.8f, -.8f, -1.6f, -1.6f};

    private final PlcModel model;
    private final Nnet nnet = new Nnet();
    private final Freq freq = new Freq();
    private final Fargan fargan;
    private final LpcnetEncoder enc;

    private final float[][] fec = new float[PLC_MAX_FEC][NB_FEATURES];
    private boolean analysisGap;

    /**
     * The index of the next queued DRED feature frame.
     */
    public int fec_read_pos;

    /**
     * The number of queued DRED feature frames, including those already read.
     */
    public int fec_fill_pos;

    /**
     * The number of lost frames to predict before the next queued DRED feature frame applies.
     */
    public int fec_skip;

    private int analysisPos;
    private int predictPos;
    private final float[] pcm = new float[PLC_BUF_SIZE];

    /**
     * Whether the last call concealed a frame rather than received one.
     */
    public boolean blend;

    private final float[] features = new float[NB_TOTAL_FEATURES];
    private final float[] contFeatures = new float[CONT_VECTORS * NB_FEATURES];
    private int lossCount;
    private final float[] gru1 = new float[GRU1_SIZE];
    private final float[] gru2 = new float[GRU2_SIZE];
    private final float[] bak0Gru1 = new float[GRU1_SIZE];
    private final float[] bak0Gru2 = new float[GRU2_SIZE];
    private final float[] bak1Gru1 = new float[GRU1_SIZE];
    private final float[] bak1Gru2 = new float[GRU2_SIZE];

    private final float[] predIn = new float[PlcModel.PLC_DENSE_IN_OUT_SIZE];
    private final float[] plcFeatures = new float[PLC_INPUT_SIZE];
    private final float[] discard = new float[NB_FEATURES];
    private final float[] frame = new float[FRAME_SIZE];

    /**
     * Constructs a concealer with a cleared state ({@code lpcnet_plc_init}).
     *
     * @throws IllegalStateException if a model cannot be loaded
     */
    public LpcnetPlc() {
        this.model = PlcModel.get();
        this.fargan = new Fargan(FarganModel.get(), nnet);
        this.enc = new LpcnetEncoder(PitchDnnModel.get(), nnet, freq);
        reset();
    }

    /**
     * Clears the state that a decoder reset clears ({@code lpcnet_plc_reset}); the vocoder state is kept.
     */
    public void reset() {
        for (var row : fec) {
            Arrays.fill(row, 0);
        }
        fec_read_pos = fec_fill_pos = fec_skip = 0;
        Arrays.fill(pcm, 0);
        Arrays.fill(features, 0);
        Arrays.fill(contFeatures, 0);
        Arrays.fill(gru1, 0);
        Arrays.fill(gru2, 0);
        Arrays.fill(bak0Gru1, 0);
        Arrays.fill(bak0Gru2, 0);
        Arrays.fill(bak1Gru1, 0);
        Arrays.fill(bak1Gru2, 0);
        enc.reset();
        blend = false;
        lossCount = 0;
        analysisGap = true;
        analysisPos = PLC_BUF_SIZE;
        predictPos = PLC_BUF_SIZE;
    }

    /**
     * Clears the state, the vocoder state included, as a new concealer has it.
     */
    public void resetAll() {
        fargan.reset();
        reset();
    }

    /**
     * Queues the DRED features of the next lost frame, or marks a frame without features ({@code
     * lpcnet_plc_fec_add}).
     *
     * @param features the features, or {@code null} when the next lost frame has none
     * @param offset the index of the first of {@link #NB_FEATURES} features
     */
    public void fecAdd(float[] features, int offset) {
        if (features == null) {
            fec_skip++;
            return;
        }
        if (fec_fill_pos == PLC_MAX_FEC) {
            for (var i = 0; i < fec_fill_pos - fec_read_pos; i++) {
                System.arraycopy(fec[fec_read_pos + i], 0, fec[i], 0, NB_FEATURES);
            }
            fec_fill_pos = fec_fill_pos - fec_read_pos;
            fec_read_pos = 0;
        }
        System.arraycopy(features, offset, fec[fec_fill_pos], 0, NB_FEATURES);
        fec_fill_pos++;
    }

    /**
     * Drops every queued DRED feature frame ({@code lpcnet_plc_fec_clear}).
     */
    public void fecClear() {
        fec_read_pos = fec_fill_pos = fec_skip = 0;
    }

    /**
     * Runs the feature predictor on one input ({@code compute_plc_pred}).
     */
    private void computePred(float[] out, float[] in) {
        nnet.dense(model.plc_dense_in, predIn, 0, in, 0, Nnet.ACTIVATION_TANH);
        nnet.gru(model.plc_gru1_input, model.plc_gru1_recurrent, gru1, 0, predIn, 0);
        nnet.gru(model.plc_gru2_input, model.plc_gru2_recurrent, gru2, 0, gru1, 0);
        nnet.dense(model.plc_dense_out, out, 0, gru2, 0, Nnet.ACTIVATION_LINEAR);
    }

    /**
     * Takes the next queued DRED features, advancing the predictor on them, or predicts the features
     * ({@code get_fec_or_pred}).
     *
     * @return whether the features came from the queue
     */
    private boolean getFecOrPred(float[] out) {
        if (fec_read_pos != fec_fill_pos && fec_skip == 0) {
            Arrays.fill(plcFeatures, 0);
            System.arraycopy(fec[fec_read_pos], 0, out, 0, NB_FEATURES);
            fec_read_pos++;
            System.arraycopy(out, 0, plcFeatures, 2 * NB_BANDS, NB_FEATURES);
            plcFeatures[2 * NB_BANDS + NB_FEATURES] = -1;
            computePred(discard, plcFeatures);
            return true;
        }
        Arrays.fill(plcFeatures, 0);
        computePred(out, plcFeatures);
        if (fec_skip > 0) {
            fec_skip--;
        }
        return false;
    }

    /**
     * Shifts the last five frames of features and appends one ({@code queue_features}).
     */
    private void queueFeatures(float[] features) {
        System.arraycopy(contFeatures, NB_FEATURES, contFeatures, 0, (CONT_VECTORS - 1) * NB_FEATURES);
        System.arraycopy(features, 0, contFeatures, (CONT_VECTORS - 1) * NB_FEATURES, NB_FEATURES);
    }

    /**
     * Saves the predictor state: the second backup becomes the first and the current state the second.
     */
    private void backup() {
        System.arraycopy(bak1Gru1, 0, bak0Gru1, 0, GRU1_SIZE);
        System.arraycopy(bak1Gru2, 0, bak0Gru2, 0, GRU2_SIZE);
        System.arraycopy(gru1, 0, bak1Gru1, 0, GRU1_SIZE);
        System.arraycopy(gru2, 0, bak1Gru2, 0, GRU2_SIZE);
    }

    /**
     * Records one received frame ({@code lpcnet_plc_update}).
     *
     * @param pcm the samples
     * @param offset the index of the first of {@link #FRAME_SIZE} samples
     */
    public void update(short[] pcm, int offset) {
        if (analysisPos - FRAME_SIZE >= 0) {
            analysisPos -= FRAME_SIZE;
        } else {
            analysisGap = true;
        }
        if (predictPos - FRAME_SIZE >= 0) {
            predictPos -= FRAME_SIZE;
        }
        System.arraycopy(this.pcm, FRAME_SIZE, this.pcm, 0, PLC_BUF_SIZE - FRAME_SIZE);
        for (var i = 0; i < FRAME_SIZE; i++) {
            this.pcm[PLC_BUF_SIZE - FRAME_SIZE + i] = (1.f / 32768.f) * pcm[offset + i];
        }
        lossCount = 0;
        blend = false;
    }

    /**
     * Conceals one lost frame ({@code lpcnet_plc_conceal}).
     *
     * @param pcm receives the samples
     * @param offset the index of the first of {@link #FRAME_SIZE} samples
     */
    public void conceal(short[] pcm, int offset) {
        if (!blend) {
            var count = 0;
            System.arraycopy(bak0Gru1, 0, gru1, 0, GRU1_SIZE);
            System.arraycopy(bak0Gru2, 0, gru2, 0, GRU2_SIZE);
            while (analysisPos + FRAME_SIZE <= PLC_BUF_SIZE) {
                for (var i = 0; i < FRAME_SIZE; i++) {
                    frame[i] = 32768.f * this.pcm[analysisPos + i];
                }
                freq.burgCepstralAnalysis(plcFeatures, frame);
                enc.computeSingleFrameFeatures(frame, 0, features, 0);
                if ((!analysisGap || count > 0) && analysisPos >= predictPos) {
                    queueFeatures(features);
                    System.arraycopy(features, 0, plcFeatures, 2 * NB_BANDS, NB_FEATURES);
                    plcFeatures[2 * NB_BANDS + NB_FEATURES] = 1;
                    backup();
                    computePred(features, plcFeatures);
                }
                analysisPos += FRAME_SIZE;
                count++;
            }
            backup();
            getFecOrPred(features);
            queueFeatures(features);
            backup();
            getFecOrPred(features);
            queueFeatures(features);
            fargan.cont(this.pcm, PLC_BUF_SIZE - Fargan.CONT_SAMPLES, contFeatures);
            analysisGap = false;
        }
        backup();
        if (getFecOrPred(features)) {
            lossCount = 0;
        } else {
            lossCount++;
        }
        if (lossCount >= 10) {
            var v = features[0] + ATT_TABLE[9] - 2 * (lossCount - 9);
            features[0] = -10 > v ? -10 : v;
        } else {
            var v = features[0] + ATT_TABLE[lossCount];
            features[0] = -10 > v ? -10 : v;
        }
        fargan.synthesize(pcm, offset, features);
        queueFeatures(features);
        if (analysisPos - FRAME_SIZE >= 0) {
            analysisPos -= FRAME_SIZE;
        } else {
            analysisGap = true;
        }
        predictPos = PLC_BUF_SIZE;
        System.arraycopy(this.pcm, FRAME_SIZE, this.pcm, 0, PLC_BUF_SIZE - FRAME_SIZE);
        for (var i = 0; i < FRAME_SIZE; i++) {
            this.pcm[PLC_BUF_SIZE - FRAME_SIZE + i] = (1.f / 32768.f) * pcm[offset + i];
        }
        blend = true;
    }
}
