/* Generated from tools/codegen/opus-dnn; edit the generator.
   Ports libopus 1.5.2 (dnn/lace_data.c, dnn/lace_data.h).
   Copyright Amazon, Mozilla and Xiph.Org Foundation; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

/**
 * The layers of the LACE speech enhancer ({@code LACELayers}), read from {@code lace.bin.gz}.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class LaceModel {
    static final float LACE_PREEMPH = 0.85f;

    static final int LACE_FRAME_SIZE = 80;

    static final int LACE_OVERLAP_SIZE = 40;

    static final int LACE_NUM_FEATURES = 93;

    static final int LACE_PITCH_MAX = 300;

    static final int LACE_PITCH_EMBEDDING_DIM = 64;

    static final int LACE_NUMBITS_RANGE_LOW = 50;

    static final int LACE_NUMBITS_RANGE_HIGH = 650;

    static final int LACE_NUMBITS_EMBEDDING_DIM = 8;

    static final int LACE_COND_DIM = 128;

    static final int LACE_HIDDEN_FEATURE_DIM = 96;

    static final float LACE_NUMBITS_SCALE_0 = 1.0983514785766602f;

    static final float LACE_NUMBITS_SCALE_1 = 2.0509142875671387f;

    static final float LACE_NUMBITS_SCALE_2 = 3.5729939937591553f;

    static final float LACE_NUMBITS_SCALE_3 = 4.478035926818848f;

    static final float LACE_NUMBITS_SCALE_4 = 5.926519393920898f;

    static final float LACE_NUMBITS_SCALE_5 = 7.152282238006592f;

    static final float LACE_NUMBITS_SCALE_6 = 8.277412414550781f;

    static final float LACE_NUMBITS_SCALE_7 = 8.926830291748047f;

    static final int LACE_PITCH_EMBEDDING_OUT_SIZE = 64;

    static final int LACE_FNET_CONV1_OUT_SIZE = 96;

    static final int LACE_FNET_CONV1_IN_SIZE = 173;

    static final int LACE_FNET_CONV1_STATE_SIZE = 0;

    static final int LACE_FNET_CONV1_DELAY = 0;

    static final int LACE_FNET_CONV2_OUT_SIZE = 128;

    static final int LACE_FNET_CONV2_IN_SIZE = 384;

    static final int LACE_FNET_CONV2_STATE_SIZE = 384;

    static final int LACE_FNET_CONV2_DELAY = 0;

    static final int LACE_FNET_TCONV_KERNEL_SIZE = 4;

    static final int LACE_FNET_TCONV_STRIDE = 4;

    static final int LACE_FNET_TCONV_IN_CHANNELS = 128;

    static final int LACE_FNET_TCONV_OUT_CHANNELS = 128;

    static final int LACE_FNET_GRU_OUT_SIZE = 128;

    static final int LACE_FNET_GRU_STATE_SIZE = 128;

    static final float LACE_CF1_FILTER_GAIN_A = 0.690776f;

    static final float LACE_CF1_FILTER_GAIN_B = 0.000000f;

    static final float LACE_CF1_LOG_GAIN_LIMIT = 1.151293f;

    static final int LACE_CF1_KERNEL_SIZE = 16;

    static final int LACE_CF1_LEFT_PADDING = 8;

    static final int LACE_CF1_FRAME_SIZE = 80;

    static final int LACE_CF1_OVERLAP_SIZE = 40;

    static final int LACE_CF1_IN_CHANNELS = 1;

    static final int LACE_CF1_OUT_CHANNELS = 1;

    static final int LACE_CF1_NORM_P = 2;

    static final int LACE_CF1_FEATURE_DIM = 128;

    static final int LACE_CF1_MAX_LAG = 301;

    static final int LACE_CF1_KERNEL_OUT_SIZE = 16;

    static final int LACE_CF1_GAIN_OUT_SIZE = 1;

    static final int LACE_CF1_GLOBAL_GAIN_OUT_SIZE = 1;

    static final float LACE_CF2_FILTER_GAIN_A = 0.690776f;

    static final float LACE_CF2_FILTER_GAIN_B = 0.000000f;

    static final float LACE_CF2_LOG_GAIN_LIMIT = 1.151293f;

    static final int LACE_CF2_KERNEL_SIZE = 16;

    static final int LACE_CF2_LEFT_PADDING = 8;

    static final int LACE_CF2_FRAME_SIZE = 80;

    static final int LACE_CF2_OVERLAP_SIZE = 40;

    static final int LACE_CF2_IN_CHANNELS = 1;

    static final int LACE_CF2_OUT_CHANNELS = 1;

    static final int LACE_CF2_NORM_P = 2;

    static final int LACE_CF2_FEATURE_DIM = 128;

    static final int LACE_CF2_MAX_LAG = 301;

    static final int LACE_CF2_KERNEL_OUT_SIZE = 16;

    static final int LACE_CF2_GAIN_OUT_SIZE = 1;

    static final int LACE_CF2_GLOBAL_GAIN_OUT_SIZE = 1;

    static final float LACE_AF1_FILTER_GAIN_A = 1.381551f;

    static final float LACE_AF1_FILTER_GAIN_B = 0.000000f;

    static final float LACE_AF1_SHAPE_GAIN = 1.000000f;

    static final int LACE_AF1_KERNEL_SIZE = 16;

    static final int LACE_AF1_FRAME_SIZE = 80;

    static final int LACE_AF1_LEFT_PADDING = 15;

    static final int LACE_AF1_OVERLAP_SIZE = 40;

    static final int LACE_AF1_IN_CHANNELS = 1;

    static final int LACE_AF1_OUT_CHANNELS = 1;

    static final int LACE_AF1_NORM_P = 2;

    static final int LACE_AF1_FEATURE_DIM = 128;

    static final int LACE_AF1_KERNEL_OUT_SIZE = 16;

    static final int LACE_AF1_GAIN_OUT_SIZE = 1;

    final LinearLayer lace_pitch_embedding;

    final LinearLayer lace_fnet_conv1;

    final LinearLayer lace_fnet_conv2;

    final LinearLayer lace_fnet_tconv;

    final LinearLayer lace_fnet_gru_input;

    final LinearLayer lace_fnet_gru_recurrent;

    final LinearLayer lace_cf1_kernel;

    final LinearLayer lace_cf1_gain;

    final LinearLayer lace_cf1_global_gain;

    final LinearLayer lace_cf2_kernel;

    final LinearLayer lace_cf2_gain;

    final LinearLayer lace_cf2_global_gain;

    final LinearLayer lace_af1_kernel;

    final LinearLayer lace_af1_gain;

    /**
     * Initializes the layers from a weight blob ({@code init_lacelayers}).
     *
     * @param blob the parsed weight blob
     * @throws IllegalStateException if an array is missing or has the wrong size
     */
    LaceModel(WeightBlob blob) {
        lace_pitch_embedding = LinearLayer.of(blob, "lace_pitch_embedding_bias", null, null,
                "lace_pitch_embedding_weights_float", null, null, null, 301, 64);
        lace_fnet_conv1 = LinearLayer.of(blob, "lace_fnet_conv1_bias", null, null,
                "lace_fnet_conv1_weights_float", null, null, null, 173, 96);
        lace_fnet_conv2 = LinearLayer.of(blob, "lace_fnet_conv2_bias", "lace_fnet_conv2_subias",
                "lace_fnet_conv2_weights_int8", "lace_fnet_conv2_weights_float", null, null,
                "lace_fnet_conv2_scale", 768, 128);
        lace_fnet_tconv = LinearLayer.of(blob, "lace_fnet_tconv_bias", "lace_fnet_tconv_subias",
                "lace_fnet_tconv_weights_int8", "lace_fnet_tconv_weights_float", null, null,
                "lace_fnet_tconv_scale", 128, 512);
        lace_fnet_gru_input = LinearLayer.of(blob, "lace_fnet_gru_input_bias", "lace_fnet_gru_input_subias",
                "lace_fnet_gru_input_weights_int8", "lace_fnet_gru_input_weights_float", null, null,
                "lace_fnet_gru_input_scale", 128, 384);
        lace_fnet_gru_recurrent = LinearLayer.of(blob, "lace_fnet_gru_recurrent_bias",
                "lace_fnet_gru_recurrent_subias", "lace_fnet_gru_recurrent_weights_int8",
                "lace_fnet_gru_recurrent_weights_float", null, null, "lace_fnet_gru_recurrent_scale", 128, 384);
        lace_cf1_kernel = LinearLayer.of(blob, "lace_cf1_kernel_bias", "lace_cf1_kernel_subias",
                "lace_cf1_kernel_weights_int8", "lace_cf1_kernel_weights_float", null, null,
                "lace_cf1_kernel_scale", 128, 16);
        lace_cf1_gain = LinearLayer.of(blob, "lace_cf1_gain_bias", null, null, "lace_cf1_gain_weights_float",
                null, null, null, 128, 1);
        lace_cf1_global_gain = LinearLayer.of(blob, "lace_cf1_global_gain_bias", null, null,
                "lace_cf1_global_gain_weights_float", null, null, null, 128, 1);
        lace_cf2_kernel = LinearLayer.of(blob, "lace_cf2_kernel_bias", "lace_cf2_kernel_subias",
                "lace_cf2_kernel_weights_int8", "lace_cf2_kernel_weights_float", null, null,
                "lace_cf2_kernel_scale", 128, 16);
        lace_cf2_gain = LinearLayer.of(blob, "lace_cf2_gain_bias", null, null, "lace_cf2_gain_weights_float",
                null, null, null, 128, 1);
        lace_cf2_global_gain = LinearLayer.of(blob, "lace_cf2_global_gain_bias", null, null,
                "lace_cf2_global_gain_weights_float", null, null, null, 128, 1);
        lace_af1_kernel = LinearLayer.of(blob, "lace_af1_kernel_bias", "lace_af1_kernel_subias",
                "lace_af1_kernel_weights_int8", "lace_af1_kernel_weights_float", null, null,
                "lace_af1_kernel_scale", 128, 16);
        lace_af1_gain = LinearLayer.of(blob, "lace_af1_gain_bias", null, null, "lace_af1_gain_weights_float",
                null, null, null, 128, 1);
    }

    /**
     * {@return the shared instance, loaded on first use}
     */
    static LaceModel get() {
        return Holder.INSTANCE;
    }

    /**
     * Holds the shared instance.
     */
    private static final class Holder {
        private static final LaceModel INSTANCE = new LaceModel(WeightBlob.load("lace.bin.gz"));
    }
}
