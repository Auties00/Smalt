/* Generated from tools/codegen/opus-dnn; edit the generator.
   Ports libopus 1.5.2 (dnn/fargan_data.c, dnn/fargan_data.h).
   Copyright Amazon, Mozilla and Xiph.Org Foundation; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

/**
 * The layers of the FARGAN vocoder ({@code FARGAN}), read from {@code fargan.bin.gz}.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class FarganModel {
    static final int COND_NET_PEMBED_OUT_SIZE = 12;

    static final int COND_NET_FDENSE1_OUT_SIZE = 64;

    static final int COND_NET_FCONV1_OUT_SIZE = 128;

    static final int COND_NET_FCONV1_IN_SIZE = 64;

    static final int COND_NET_FCONV1_STATE_SIZE = 128;

    static final int COND_NET_FCONV1_DELAY = 1;

    static final int COND_NET_FDENSE2_OUT_SIZE = 320;

    static final int SIG_NET_COND_GAIN_DENSE_OUT_SIZE = 1;

    static final int SIG_NET_FWC0_CONV_OUT_SIZE = 192;

    static final int SIG_NET_FWC0_GLU_GATE_OUT_SIZE = 192;

    static final int SIG_NET_GRU1_OUT_SIZE = 160;

    static final int SIG_NET_GRU1_STATE_SIZE = 160;

    static final int SIG_NET_GRU2_OUT_SIZE = 128;

    static final int SIG_NET_GRU2_STATE_SIZE = 128;

    static final int SIG_NET_GRU3_OUT_SIZE = 128;

    static final int SIG_NET_GRU3_STATE_SIZE = 128;

    static final int SIG_NET_GRU1_GLU_GATE_OUT_SIZE = 160;

    static final int SIG_NET_GRU2_GLU_GATE_OUT_SIZE = 128;

    static final int SIG_NET_GRU3_GLU_GATE_OUT_SIZE = 128;

    static final int SIG_NET_SKIP_GLU_GATE_OUT_SIZE = 128;

    static final int SIG_NET_SKIP_DENSE_OUT_SIZE = 128;

    static final int SIG_NET_SIG_DENSE_OUT_OUT_SIZE = 40;

    static final int SIG_NET_GAIN_DENSE_OUT_OUT_SIZE = 4;

    final LinearLayer cond_net_pembed;

    final LinearLayer cond_net_fdense1;

    final LinearLayer cond_net_fconv1;

    final LinearLayer cond_net_fdense2;

    final LinearLayer sig_net_cond_gain_dense;

    final LinearLayer sig_net_fwc0_conv;

    final LinearLayer sig_net_fwc0_glu_gate;

    final LinearLayer sig_net_gru1_input;

    final LinearLayer sig_net_gru1_recurrent;

    final LinearLayer sig_net_gru2_input;

    final LinearLayer sig_net_gru2_recurrent;

    final LinearLayer sig_net_gru3_input;

    final LinearLayer sig_net_gru3_recurrent;

    final LinearLayer sig_net_gru1_glu_gate;

    final LinearLayer sig_net_gru2_glu_gate;

    final LinearLayer sig_net_gru3_glu_gate;

    final LinearLayer sig_net_skip_glu_gate;

    final LinearLayer sig_net_skip_dense;

    final LinearLayer sig_net_sig_dense_out;

    final LinearLayer sig_net_gain_dense_out;

    /**
     * Initializes the layers from a weight blob ({@code init_fargan}).
     *
     * @param blob the parsed weight blob
     * @throws IllegalStateException if an array is missing or has the wrong size
     */
    FarganModel(WeightBlob blob) {
        cond_net_pembed = LinearLayer.of(blob, "cond_net_pembed_bias", null, null,
                "cond_net_pembed_weights_float", null, null, null, 224, 12);
        cond_net_fdense1 = LinearLayer.of(blob, "cond_net_fdense1_bias", null, null,
                "cond_net_fdense1_weights_float", null, null, null, 32, 64);
        cond_net_fconv1 = LinearLayer.of(blob, "cond_net_fconv1_bias", "cond_net_fconv1_subias",
                "cond_net_fconv1_weights_int8", "cond_net_fconv1_weights_float", null, null,
                "cond_net_fconv1_scale", 192, 128);
        cond_net_fdense2 = LinearLayer.of(blob, "cond_net_fdense2_bias", "cond_net_fdense2_subias",
                "cond_net_fdense2_weights_int8", "cond_net_fdense2_weights_float", null, null,
                "cond_net_fdense2_scale", 128, 320);
        sig_net_cond_gain_dense = LinearLayer.of(blob, "sig_net_cond_gain_dense_bias", null, null,
                "sig_net_cond_gain_dense_weights_float", null, null, null, 80, 1);
        sig_net_fwc0_conv = LinearLayer.of(blob, "sig_net_fwc0_conv_bias", "sig_net_fwc0_conv_subias",
                "sig_net_fwc0_conv_weights_int8", "sig_net_fwc0_conv_weights_float", null, null,
                "sig_net_fwc0_conv_scale", 328, 192);
        sig_net_fwc0_glu_gate = LinearLayer.of(blob, "sig_net_fwc0_glu_gate_bias", "sig_net_fwc0_glu_gate_subias",
                "sig_net_fwc0_glu_gate_weights_int8", "sig_net_fwc0_glu_gate_weights_float", null, null,
                "sig_net_fwc0_glu_gate_scale", 192, 192);
        sig_net_gru1_input = LinearLayer.of(blob, null, "sig_net_gru1_input_subias",
                "sig_net_gru1_input_weights_int8", "sig_net_gru1_input_weights_float", null, null,
                "sig_net_gru1_input_scale", 272, 480);
        sig_net_gru1_recurrent = LinearLayer.of(blob, null, "sig_net_gru1_recurrent_subias",
                "sig_net_gru1_recurrent_weights_int8", "sig_net_gru1_recurrent_weights_float", null, null,
                "sig_net_gru1_recurrent_scale", 160, 480);
        sig_net_gru2_input = LinearLayer.of(blob, null, "sig_net_gru2_input_subias",
                "sig_net_gru2_input_weights_int8", "sig_net_gru2_input_weights_float", null, null,
                "sig_net_gru2_input_scale", 240, 384);
        sig_net_gru2_recurrent = LinearLayer.of(blob, null, "sig_net_gru2_recurrent_subias",
                "sig_net_gru2_recurrent_weights_int8", "sig_net_gru2_recurrent_weights_float", null, null,
                "sig_net_gru2_recurrent_scale", 128, 384);
        sig_net_gru3_input = LinearLayer.of(blob, null, "sig_net_gru3_input_subias",
                "sig_net_gru3_input_weights_int8", "sig_net_gru3_input_weights_float", null, null,
                "sig_net_gru3_input_scale", 208, 384);
        sig_net_gru3_recurrent = LinearLayer.of(blob, null, "sig_net_gru3_recurrent_subias",
                "sig_net_gru3_recurrent_weights_int8", "sig_net_gru3_recurrent_weights_float", null, null,
                "sig_net_gru3_recurrent_scale", 128, 384);
        sig_net_gru1_glu_gate = LinearLayer.of(blob, "sig_net_gru1_glu_gate_bias", "sig_net_gru1_glu_gate_subias",
                "sig_net_gru1_glu_gate_weights_int8", "sig_net_gru1_glu_gate_weights_float", null, null,
                "sig_net_gru1_glu_gate_scale", 160, 160);
        sig_net_gru2_glu_gate = LinearLayer.of(blob, "sig_net_gru2_glu_gate_bias", "sig_net_gru2_glu_gate_subias",
                "sig_net_gru2_glu_gate_weights_int8", "sig_net_gru2_glu_gate_weights_float", null, null,
                "sig_net_gru2_glu_gate_scale", 128, 128);
        sig_net_gru3_glu_gate = LinearLayer.of(blob, "sig_net_gru3_glu_gate_bias", "sig_net_gru3_glu_gate_subias",
                "sig_net_gru3_glu_gate_weights_int8", "sig_net_gru3_glu_gate_weights_float", null, null,
                "sig_net_gru3_glu_gate_scale", 128, 128);
        sig_net_skip_glu_gate = LinearLayer.of(blob, "sig_net_skip_glu_gate_bias", "sig_net_skip_glu_gate_subias",
                "sig_net_skip_glu_gate_weights_int8", "sig_net_skip_glu_gate_weights_float", null, null,
                "sig_net_skip_glu_gate_scale", 128, 128);
        sig_net_skip_dense = LinearLayer.of(blob, "sig_net_skip_dense_bias", "sig_net_skip_dense_subias",
                "sig_net_skip_dense_weights_int8", "sig_net_skip_dense_weights_float", null, null,
                "sig_net_skip_dense_scale", 688, 128);
        sig_net_sig_dense_out = LinearLayer.of(blob, "sig_net_sig_dense_out_bias", "sig_net_sig_dense_out_subias",
                "sig_net_sig_dense_out_weights_int8", "sig_net_sig_dense_out_weights_float", null, null,
                "sig_net_sig_dense_out_scale", 128, 40);
        sig_net_gain_dense_out = LinearLayer.of(blob, "sig_net_gain_dense_out_bias", null, null,
                "sig_net_gain_dense_out_weights_float", null, null, null, 192, 4);
    }

    /**
     * {@return the shared instance, loaded on first use}
     */
    static FarganModel get() {
        return Holder.INSTANCE;
    }

    /**
     * Holds the shared instance.
     */
    private static final class Holder {
        private static final FarganModel INSTANCE = new FarganModel(WeightBlob.load("fargan.bin.gz"));
    }
}
