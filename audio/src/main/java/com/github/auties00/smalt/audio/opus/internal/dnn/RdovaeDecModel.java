/* Generated from tools/codegen/opus-dnn; edit the generator.
   Ports libopus 1.5.2 (dnn/dred_rdovae_dec_data.c, dnn/dred_rdovae_dec_data.h).
   Copyright Amazon, Mozilla and Xiph.Org Foundation; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

/**
 * The layers of the DRED decoder ({@code RDOVAEDec}), read from {@code rdovae-dec.bin.gz}.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class RdovaeDecModel {
    static final int DEC_DENSE1_OUT_SIZE = 96;

    static final int DEC_GLU1_OUT_SIZE = 96;

    static final int DEC_GLU2_OUT_SIZE = 96;

    static final int DEC_GLU3_OUT_SIZE = 96;

    static final int DEC_GLU4_OUT_SIZE = 96;

    static final int DEC_GLU5_OUT_SIZE = 96;

    static final int DEC_OUTPUT_OUT_SIZE = 80;

    static final int DEC_HIDDEN_INIT_OUT_SIZE = 128;

    static final int DEC_GRU_INIT_OUT_SIZE = 480;

    static final int DEC_GRU1_OUT_SIZE = 96;

    static final int DEC_GRU1_STATE_SIZE = 96;

    static final int DEC_GRU2_OUT_SIZE = 96;

    static final int DEC_GRU2_STATE_SIZE = 96;

    static final int DEC_GRU3_OUT_SIZE = 96;

    static final int DEC_GRU3_STATE_SIZE = 96;

    static final int DEC_GRU4_OUT_SIZE = 96;

    static final int DEC_GRU4_STATE_SIZE = 96;

    static final int DEC_GRU5_OUT_SIZE = 96;

    static final int DEC_GRU5_STATE_SIZE = 96;

    static final int DEC_CONV1_OUT_SIZE = 32;

    static final int DEC_CONV1_IN_SIZE = 192;

    static final int DEC_CONV1_STATE_SIZE = 192;

    static final int DEC_CONV1_DELAY = 0;

    static final int DEC_CONV2_OUT_SIZE = 32;

    static final int DEC_CONV2_IN_SIZE = 320;

    static final int DEC_CONV2_STATE_SIZE = 320;

    static final int DEC_CONV2_DELAY = 0;

    static final int DEC_CONV3_OUT_SIZE = 32;

    static final int DEC_CONV3_IN_SIZE = 448;

    static final int DEC_CONV3_STATE_SIZE = 448;

    static final int DEC_CONV3_DELAY = 0;

    static final int DEC_CONV4_OUT_SIZE = 32;

    static final int DEC_CONV4_IN_SIZE = 576;

    static final int DEC_CONV4_STATE_SIZE = 576;

    static final int DEC_CONV4_DELAY = 0;

    static final int DEC_CONV5_OUT_SIZE = 32;

    static final int DEC_CONV5_IN_SIZE = 704;

    static final int DEC_CONV5_STATE_SIZE = 704;

    static final int DEC_CONV5_DELAY = 0;

    final LinearLayer dec_dense1;

    final LinearLayer dec_glu1;

    final LinearLayer dec_glu2;

    final LinearLayer dec_glu3;

    final LinearLayer dec_glu4;

    final LinearLayer dec_glu5;

    final LinearLayer dec_output;

    final LinearLayer dec_hidden_init;

    final LinearLayer dec_gru_init;

    final LinearLayer dec_gru1_input;

    final LinearLayer dec_gru1_recurrent;

    final LinearLayer dec_gru2_input;

    final LinearLayer dec_gru2_recurrent;

    final LinearLayer dec_gru3_input;

    final LinearLayer dec_gru3_recurrent;

    final LinearLayer dec_gru4_input;

    final LinearLayer dec_gru4_recurrent;

    final LinearLayer dec_gru5_input;

    final LinearLayer dec_gru5_recurrent;

    final LinearLayer dec_conv1;

    final LinearLayer dec_conv2;

    final LinearLayer dec_conv3;

    final LinearLayer dec_conv4;

    final LinearLayer dec_conv5;

    /**
     * Initializes the layers from a weight blob ({@code init_rdovaedec}).
     *
     * @param blob the parsed weight blob
     * @throws IllegalStateException if an array is missing or has the wrong size
     */
    RdovaeDecModel(WeightBlob blob) {
        dec_dense1 = LinearLayer.of(blob, "dec_dense1_bias", null, null, "dec_dense1_weights_float", null, null,
                null, 21, 96);
        dec_glu1 = LinearLayer.of(blob, "dec_glu1_bias", "dec_glu1_subias", "dec_glu1_weights_int8",
                "dec_glu1_weights_float", null, null, "dec_glu1_scale", 96, 96);
        dec_glu2 = LinearLayer.of(blob, "dec_glu2_bias", "dec_glu2_subias", "dec_glu2_weights_int8",
                "dec_glu2_weights_float", null, null, "dec_glu2_scale", 96, 96);
        dec_glu3 = LinearLayer.of(blob, "dec_glu3_bias", "dec_glu3_subias", "dec_glu3_weights_int8",
                "dec_glu3_weights_float", null, null, "dec_glu3_scale", 96, 96);
        dec_glu4 = LinearLayer.of(blob, "dec_glu4_bias", "dec_glu4_subias", "dec_glu4_weights_int8",
                "dec_glu4_weights_float", null, null, "dec_glu4_scale", 96, 96);
        dec_glu5 = LinearLayer.of(blob, "dec_glu5_bias", "dec_glu5_subias", "dec_glu5_weights_int8",
                "dec_glu5_weights_float", null, null, "dec_glu5_scale", 96, 96);
        dec_output = LinearLayer.of(blob, "dec_output_bias", "dec_output_subias", "dec_output_weights_int8",
                "dec_output_weights_float", null, null, "dec_output_scale", 736, 80);
        dec_hidden_init = LinearLayer.of(blob, "dec_hidden_init_bias", null, null,
                "dec_hidden_init_weights_float", null, null, null, 19, 128);
        dec_gru_init = LinearLayer.of(blob, "dec_gru_init_bias", "dec_gru_init_subias",
                "dec_gru_init_weights_int8", "dec_gru_init_weights_float", null, null, "dec_gru_init_scale", 128,
                480);
        dec_gru1_input = LinearLayer.of(blob, "dec_gru1_input_bias", "dec_gru1_input_subias",
                "dec_gru1_input_weights_int8", "dec_gru1_input_weights_float", "dec_gru1_input_weights_idx", null,
                "dec_gru1_input_scale", 96, 288);
        dec_gru1_recurrent = LinearLayer.of(blob, "dec_gru1_recurrent_bias", "dec_gru1_recurrent_subias",
                "dec_gru1_recurrent_weights_int8", "dec_gru1_recurrent_weights_float", null, null,
                "dec_gru1_recurrent_scale", 96, 288);
        dec_gru2_input = LinearLayer.of(blob, "dec_gru2_input_bias", "dec_gru2_input_subias",
                "dec_gru2_input_weights_int8", "dec_gru2_input_weights_float", "dec_gru2_input_weights_idx", null,
                "dec_gru2_input_scale", 224, 288);
        dec_gru2_recurrent = LinearLayer.of(blob, "dec_gru2_recurrent_bias", "dec_gru2_recurrent_subias",
                "dec_gru2_recurrent_weights_int8", "dec_gru2_recurrent_weights_float", null, null,
                "dec_gru2_recurrent_scale", 96, 288);
        dec_gru3_input = LinearLayer.of(blob, "dec_gru3_input_bias", "dec_gru3_input_subias",
                "dec_gru3_input_weights_int8", "dec_gru3_input_weights_float", "dec_gru3_input_weights_idx", null,
                "dec_gru3_input_scale", 352, 288);
        dec_gru3_recurrent = LinearLayer.of(blob, "dec_gru3_recurrent_bias", "dec_gru3_recurrent_subias",
                "dec_gru3_recurrent_weights_int8", "dec_gru3_recurrent_weights_float", null, null,
                "dec_gru3_recurrent_scale", 96, 288);
        dec_gru4_input = LinearLayer.of(blob, "dec_gru4_input_bias", "dec_gru4_input_subias",
                "dec_gru4_input_weights_int8", "dec_gru4_input_weights_float", "dec_gru4_input_weights_idx", null,
                "dec_gru4_input_scale", 480, 288);
        dec_gru4_recurrent = LinearLayer.of(blob, "dec_gru4_recurrent_bias", "dec_gru4_recurrent_subias",
                "dec_gru4_recurrent_weights_int8", "dec_gru4_recurrent_weights_float", null, null,
                "dec_gru4_recurrent_scale", 96, 288);
        dec_gru5_input = LinearLayer.of(blob, "dec_gru5_input_bias", "dec_gru5_input_subias",
                "dec_gru5_input_weights_int8", "dec_gru5_input_weights_float", "dec_gru5_input_weights_idx", null,
                "dec_gru5_input_scale", 608, 288);
        dec_gru5_recurrent = LinearLayer.of(blob, "dec_gru5_recurrent_bias", "dec_gru5_recurrent_subias",
                "dec_gru5_recurrent_weights_int8", "dec_gru5_recurrent_weights_float", null, null,
                "dec_gru5_recurrent_scale", 96, 288);
        dec_conv1 = LinearLayer.of(blob, "dec_conv1_bias", "dec_conv1_subias", "dec_conv1_weights_int8",
                "dec_conv1_weights_float", null, null, "dec_conv1_scale", 384, 32);
        dec_conv2 = LinearLayer.of(blob, "dec_conv2_bias", "dec_conv2_subias", "dec_conv2_weights_int8",
                "dec_conv2_weights_float", null, null, "dec_conv2_scale", 640, 32);
        dec_conv3 = LinearLayer.of(blob, "dec_conv3_bias", "dec_conv3_subias", "dec_conv3_weights_int8",
                "dec_conv3_weights_float", null, null, "dec_conv3_scale", 896, 32);
        dec_conv4 = LinearLayer.of(blob, "dec_conv4_bias", "dec_conv4_subias", "dec_conv4_weights_int8",
                "dec_conv4_weights_float", null, null, "dec_conv4_scale", 1152, 32);
        dec_conv5 = LinearLayer.of(blob, "dec_conv5_bias", "dec_conv5_subias", "dec_conv5_weights_int8",
                "dec_conv5_weights_float", null, null, "dec_conv5_scale", 1408, 32);
    }

    /**
     * {@return the shared instance, loaded on first use}
     */
    static RdovaeDecModel get() {
        return Holder.INSTANCE;
    }

    /**
     * Holds the shared instance.
     */
    private static final class Holder {
        private static final RdovaeDecModel INSTANCE = new RdovaeDecModel(WeightBlob.load("rdovae-dec.bin.gz"));
    }
}
