/* Generated from tools/codegen/opus-dnn; edit the generator.
   Ports libopus 1.5.2 (dnn/plc_data.c, dnn/plc_data.h).
   Copyright Amazon, Mozilla and Xiph.Org Foundation; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

/**
 * The layers of the deep PLC feature predictor ({@code PLCModel}), read from {@code plc.bin.gz}.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class PlcModel {
    static final int PLC_DENSE_IN_OUT_SIZE = 128;

    static final int PLC_DENSE_OUT_OUT_SIZE = 20;

    static final int PLC_GRU1_OUT_SIZE = 192;

    static final int PLC_GRU1_STATE_SIZE = 192;

    static final int PLC_GRU2_OUT_SIZE = 192;

    static final int PLC_GRU2_STATE_SIZE = 192;

    static final int PLC_MAX_RNN_UNITS = 192;

    final LinearLayer plc_dense_in;

    final LinearLayer plc_dense_out;

    final LinearLayer plc_gru1_input;

    final LinearLayer plc_gru1_recurrent;

    final LinearLayer plc_gru2_input;

    final LinearLayer plc_gru2_recurrent;

    /**
     * Initializes the layers from a weight blob ({@code init_plcmodel}).
     *
     * @param blob the parsed weight blob
     * @throws IllegalStateException if an array is missing or has the wrong size
     */
    PlcModel(WeightBlob blob) {
        plc_dense_in = LinearLayer.of(blob, "plc_dense_in_bias", null, null, "plc_dense_in_weights_float", null,
                null, null, 57, 128);
        plc_dense_out = LinearLayer.of(blob, "plc_dense_out_bias", null, null, "plc_dense_out_weights_float",
                null, null, null, 192, 20);
        plc_gru1_input = LinearLayer.of(blob, "plc_gru1_input_bias", "plc_gru1_input_subias",
                "plc_gru1_input_weights_int8", "plc_gru1_input_weights_float", null, null, "plc_gru1_input_scale",
                128, 576);
        plc_gru1_recurrent = LinearLayer.of(blob, "plc_gru1_recurrent_bias", "plc_gru1_recurrent_subias",
                "plc_gru1_recurrent_weights_int8", "plc_gru1_recurrent_weights_float", null, null,
                "plc_gru1_recurrent_scale", 192, 576);
        plc_gru2_input = LinearLayer.of(blob, "plc_gru2_input_bias", "plc_gru2_input_subias",
                "plc_gru2_input_weights_int8", "plc_gru2_input_weights_float", null, null, "plc_gru2_input_scale",
                192, 576);
        plc_gru2_recurrent = LinearLayer.of(blob, "plc_gru2_recurrent_bias", "plc_gru2_recurrent_subias",
                "plc_gru2_recurrent_weights_int8", "plc_gru2_recurrent_weights_float", null, null,
                "plc_gru2_recurrent_scale", 192, 576);
    }

    /**
     * {@return the shared instance, loaded on first use}
     */
    static PlcModel get() {
        return Holder.INSTANCE;
    }

    /**
     * Holds the shared instance.
     */
    private static final class Holder {
        private static final PlcModel INSTANCE = new PlcModel(WeightBlob.load("plc.bin.gz"));
    }
}
