/* Generated from tools/codegen/opus-dnn; edit the generator.
   Ports libopus 1.5.2 (dnn/dred_rdovae_enc_data.c, dnn/dred_rdovae_enc_data.h).
   Copyright Amazon, Mozilla and Xiph.Org Foundation; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

/**
 * The layers of the DRED encoder ({@code RDOVAEEnc}), read from {@code rdovae-enc.bin.gz}.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class RdovaeEncModel {
    static final int ENC_DENSE1_OUT_SIZE = 64;

    static final int ENC_ZDENSE_OUT_SIZE = 24;

    static final int GDENSE1_OUT_SIZE = 128;

    static final int GDENSE2_OUT_SIZE = 24;

    static final int ENC_GRU1_OUT_SIZE = 64;

    static final int ENC_GRU1_STATE_SIZE = 64;

    static final int ENC_GRU2_OUT_SIZE = 64;

    static final int ENC_GRU2_STATE_SIZE = 64;

    static final int ENC_GRU3_OUT_SIZE = 64;

    static final int ENC_GRU3_STATE_SIZE = 64;

    static final int ENC_GRU4_OUT_SIZE = 64;

    static final int ENC_GRU4_STATE_SIZE = 64;

    static final int ENC_GRU5_OUT_SIZE = 64;

    static final int ENC_GRU5_STATE_SIZE = 64;

    static final int ENC_CONV1_OUT_SIZE = 96;

    static final int ENC_CONV1_IN_SIZE = 128;

    static final int ENC_CONV1_STATE_SIZE = 128;

    static final int ENC_CONV1_DELAY = 0;

    static final int ENC_CONV2_OUT_SIZE = 96;

    static final int ENC_CONV2_IN_SIZE = 288;

    static final int ENC_CONV2_STATE_SIZE = 288;

    static final int ENC_CONV2_DELAY = 0;

    static final int ENC_CONV3_OUT_SIZE = 96;

    static final int ENC_CONV3_IN_SIZE = 448;

    static final int ENC_CONV3_STATE_SIZE = 448;

    static final int ENC_CONV3_DELAY = 0;

    static final int ENC_CONV4_OUT_SIZE = 96;

    static final int ENC_CONV4_IN_SIZE = 608;

    static final int ENC_CONV4_STATE_SIZE = 608;

    static final int ENC_CONV4_DELAY = 0;

    static final int ENC_CONV5_OUT_SIZE = 96;

    static final int ENC_CONV5_IN_SIZE = 768;

    static final int ENC_CONV5_STATE_SIZE = 768;

    static final int ENC_CONV5_DELAY = 0;

    final LinearLayer enc_dense1;

    final LinearLayer enc_zdense;

    final LinearLayer gdense1;

    final LinearLayer gdense2;

    final LinearLayer enc_gru1_input;

    final LinearLayer enc_gru1_recurrent;

    final LinearLayer enc_gru2_input;

    final LinearLayer enc_gru2_recurrent;

    final LinearLayer enc_gru3_input;

    final LinearLayer enc_gru3_recurrent;

    final LinearLayer enc_gru4_input;

    final LinearLayer enc_gru4_recurrent;

    final LinearLayer enc_gru5_input;

    final LinearLayer enc_gru5_recurrent;

    final LinearLayer enc_conv1;

    final LinearLayer enc_conv2;

    final LinearLayer enc_conv3;

    final LinearLayer enc_conv4;

    final LinearLayer enc_conv5;

    /**
     * Initializes the layers from a weight blob ({@code init_rdovaeenc}).
     *
     * @param blob the parsed weight blob
     * @throws IllegalStateException if an array is missing or has the wrong size
     */
    RdovaeEncModel(WeightBlob blob) {
        enc_dense1 = LinearLayer.of(blob, "enc_dense1_bias", null, null, "enc_dense1_weights_float", null, null,
                null, 40, 64);
        enc_zdense = LinearLayer.of(blob, "enc_zdense_bias", "enc_zdense_subias", "enc_zdense_weights_int8",
                "enc_zdense_weights_float", null, null, "enc_zdense_scale", 864, 24);
        gdense1 = LinearLayer.of(blob, "gdense1_bias", "gdense1_subias", "gdense1_weights_int8",
                "gdense1_weights_float", null, null, "gdense1_scale", 864, 128);
        gdense2 = LinearLayer.of(blob, "gdense2_bias", "gdense2_subias", "gdense2_weights_int8",
                "gdense2_weights_float", null, null, "gdense2_scale", 128, 24);
        enc_gru1_input = LinearLayer.of(blob, "enc_gru1_input_bias", "enc_gru1_input_subias",
                "enc_gru1_input_weights_int8", "enc_gru1_input_weights_float", "enc_gru1_input_weights_idx", null,
                "enc_gru1_input_scale", 64, 192);
        enc_gru1_recurrent = LinearLayer.of(blob, "enc_gru1_recurrent_bias", "enc_gru1_recurrent_subias",
                "enc_gru1_recurrent_weights_int8", "enc_gru1_recurrent_weights_float", null, null,
                "enc_gru1_recurrent_scale", 64, 192);
        enc_gru2_input = LinearLayer.of(blob, "enc_gru2_input_bias", "enc_gru2_input_subias",
                "enc_gru2_input_weights_int8", "enc_gru2_input_weights_float", "enc_gru2_input_weights_idx", null,
                "enc_gru2_input_scale", 224, 192);
        enc_gru2_recurrent = LinearLayer.of(blob, "enc_gru2_recurrent_bias", "enc_gru2_recurrent_subias",
                "enc_gru2_recurrent_weights_int8", "enc_gru2_recurrent_weights_float", null, null,
                "enc_gru2_recurrent_scale", 64, 192);
        enc_gru3_input = LinearLayer.of(blob, "enc_gru3_input_bias", "enc_gru3_input_subias",
                "enc_gru3_input_weights_int8", "enc_gru3_input_weights_float", "enc_gru3_input_weights_idx", null,
                "enc_gru3_input_scale", 384, 192);
        enc_gru3_recurrent = LinearLayer.of(blob, "enc_gru3_recurrent_bias", "enc_gru3_recurrent_subias",
                "enc_gru3_recurrent_weights_int8", "enc_gru3_recurrent_weights_float", null, null,
                "enc_gru3_recurrent_scale", 64, 192);
        enc_gru4_input = LinearLayer.of(blob, "enc_gru4_input_bias", "enc_gru4_input_subias",
                "enc_gru4_input_weights_int8", "enc_gru4_input_weights_float", "enc_gru4_input_weights_idx", null,
                "enc_gru4_input_scale", 544, 192);
        enc_gru4_recurrent = LinearLayer.of(blob, "enc_gru4_recurrent_bias", "enc_gru4_recurrent_subias",
                "enc_gru4_recurrent_weights_int8", "enc_gru4_recurrent_weights_float", null, null,
                "enc_gru4_recurrent_scale", 64, 192);
        enc_gru5_input = LinearLayer.of(blob, "enc_gru5_input_bias", "enc_gru5_input_subias",
                "enc_gru5_input_weights_int8", "enc_gru5_input_weights_float", "enc_gru5_input_weights_idx", null,
                "enc_gru5_input_scale", 704, 192);
        enc_gru5_recurrent = LinearLayer.of(blob, "enc_gru5_recurrent_bias", "enc_gru5_recurrent_subias",
                "enc_gru5_recurrent_weights_int8", "enc_gru5_recurrent_weights_float", null, null,
                "enc_gru5_recurrent_scale", 64, 192);
        enc_conv1 = LinearLayer.of(blob, "enc_conv1_bias", "enc_conv1_subias", "enc_conv1_weights_int8",
                "enc_conv1_weights_float", null, null, "enc_conv1_scale", 256, 96);
        enc_conv2 = LinearLayer.of(blob, "enc_conv2_bias", "enc_conv2_subias", "enc_conv2_weights_int8",
                "enc_conv2_weights_float", null, null, "enc_conv2_scale", 576, 96);
        enc_conv3 = LinearLayer.of(blob, "enc_conv3_bias", "enc_conv3_subias", "enc_conv3_weights_int8",
                "enc_conv3_weights_float", null, null, "enc_conv3_scale", 896, 96);
        enc_conv4 = LinearLayer.of(blob, "enc_conv4_bias", "enc_conv4_subias", "enc_conv4_weights_int8",
                "enc_conv4_weights_float", null, null, "enc_conv4_scale", 1216, 96);
        enc_conv5 = LinearLayer.of(blob, "enc_conv5_bias", "enc_conv5_subias", "enc_conv5_weights_int8",
                "enc_conv5_weights_float", null, null, "enc_conv5_scale", 1536, 96);
    }

    /**
     * {@return the shared instance, loaded on first use}
     */
    static RdovaeEncModel get() {
        return Holder.INSTANCE;
    }

    /**
     * Holds the shared instance.
     */
    private static final class Holder {
        private static final RdovaeEncModel INSTANCE = new RdovaeEncModel(WeightBlob.load("rdovae-enc.bin.gz"));
    }
}
