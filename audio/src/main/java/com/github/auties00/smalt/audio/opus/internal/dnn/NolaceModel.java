/* Generated from tools/codegen/opus-dnn; edit the generator.
   Ports libopus 1.5.2 (dnn/nolace_data.c, dnn/nolace_data.h).
   Copyright Amazon, Mozilla and Xiph.Org Foundation; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

/**
 * The layers of the NoLACE speech enhancer ({@code NOLACELayers}), read from {@code nolace.bin.gz}.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class NolaceModel {
    static final float NOLACE_PREEMPH = 0.85f;

    static final int NOLACE_FRAME_SIZE = 80;

    static final int NOLACE_OVERLAP_SIZE = 40;

    static final int NOLACE_NUM_FEATURES = 93;

    static final int NOLACE_PITCH_MAX = 300;

    static final int NOLACE_PITCH_EMBEDDING_DIM = 64;

    static final int NOLACE_NUMBITS_RANGE_LOW = 50;

    static final int NOLACE_NUMBITS_RANGE_HIGH = 650;

    static final int NOLACE_NUMBITS_EMBEDDING_DIM = 8;

    static final int NOLACE_COND_DIM = 160;

    static final int NOLACE_HIDDEN_FEATURE_DIM = 96;

    static final float NOLACE_NUMBITS_SCALE_0 = 1.0357311964035034f;

    static final float NOLACE_NUMBITS_SCALE_1 = 1.735559105873108f;

    static final float NOLACE_NUMBITS_SCALE_2 = 3.6004557609558105f;

    static final float NOLACE_NUMBITS_SCALE_3 = 4.552478313446045f;

    static final float NOLACE_NUMBITS_SCALE_4 = 5.932559490203857f;

    static final float NOLACE_NUMBITS_SCALE_5 = 7.176970481872559f;

    static final float NOLACE_NUMBITS_SCALE_6 = 8.114998817443848f;

    static final float NOLACE_NUMBITS_SCALE_7 = 8.77063274383545f;

    static final int NOLACE_PITCH_EMBEDDING_OUT_SIZE = 64;

    static final int NOLACE_FNET_CONV1_OUT_SIZE = 96;

    static final int NOLACE_FNET_CONV1_IN_SIZE = 173;

    static final int NOLACE_FNET_CONV1_STATE_SIZE = 0;

    static final int NOLACE_FNET_CONV1_DELAY = 0;

    static final int NOLACE_FNET_CONV2_OUT_SIZE = 160;

    static final int NOLACE_FNET_CONV2_IN_SIZE = 384;

    static final int NOLACE_FNET_CONV2_STATE_SIZE = 384;

    static final int NOLACE_FNET_CONV2_DELAY = 0;

    static final int NOLACE_FNET_TCONV_KERNEL_SIZE = 4;

    static final int NOLACE_FNET_TCONV_STRIDE = 4;

    static final int NOLACE_FNET_TCONV_IN_CHANNELS = 160;

    static final int NOLACE_FNET_TCONV_OUT_CHANNELS = 160;

    static final int NOLACE_FNET_GRU_OUT_SIZE = 160;

    static final int NOLACE_FNET_GRU_STATE_SIZE = 160;

    static final float NOLACE_CF1_FILTER_GAIN_A = 0.690776f;

    static final float NOLACE_CF1_FILTER_GAIN_B = 0.000000f;

    static final float NOLACE_CF1_LOG_GAIN_LIMIT = 1.151293f;

    static final int NOLACE_CF1_KERNEL_SIZE = 16;

    static final int NOLACE_CF1_LEFT_PADDING = 8;

    static final int NOLACE_CF1_FRAME_SIZE = 80;

    static final int NOLACE_CF1_OVERLAP_SIZE = 40;

    static final int NOLACE_CF1_IN_CHANNELS = 1;

    static final int NOLACE_CF1_OUT_CHANNELS = 1;

    static final int NOLACE_CF1_NORM_P = 2;

    static final int NOLACE_CF1_FEATURE_DIM = 160;

    static final int NOLACE_CF1_MAX_LAG = 301;

    static final int NOLACE_CF1_KERNEL_OUT_SIZE = 16;

    static final int NOLACE_CF1_GAIN_OUT_SIZE = 1;

    static final int NOLACE_CF1_GLOBAL_GAIN_OUT_SIZE = 1;

    static final float NOLACE_CF2_FILTER_GAIN_A = 0.690776f;

    static final float NOLACE_CF2_FILTER_GAIN_B = 0.000000f;

    static final float NOLACE_CF2_LOG_GAIN_LIMIT = 1.151293f;

    static final int NOLACE_CF2_KERNEL_SIZE = 16;

    static final int NOLACE_CF2_LEFT_PADDING = 8;

    static final int NOLACE_CF2_FRAME_SIZE = 80;

    static final int NOLACE_CF2_OVERLAP_SIZE = 40;

    static final int NOLACE_CF2_IN_CHANNELS = 1;

    static final int NOLACE_CF2_OUT_CHANNELS = 1;

    static final int NOLACE_CF2_NORM_P = 2;

    static final int NOLACE_CF2_FEATURE_DIM = 160;

    static final int NOLACE_CF2_MAX_LAG = 301;

    static final int NOLACE_CF2_KERNEL_OUT_SIZE = 16;

    static final int NOLACE_CF2_GAIN_OUT_SIZE = 1;

    static final int NOLACE_CF2_GLOBAL_GAIN_OUT_SIZE = 1;

    static final float NOLACE_AF1_FILTER_GAIN_A = 1.381551f;

    static final float NOLACE_AF1_FILTER_GAIN_B = 0.000000f;

    static final float NOLACE_AF1_SHAPE_GAIN = 1.000000f;

    static final int NOLACE_AF1_KERNEL_SIZE = 16;

    static final int NOLACE_AF1_FRAME_SIZE = 80;

    static final int NOLACE_AF1_LEFT_PADDING = 15;

    static final int NOLACE_AF1_OVERLAP_SIZE = 40;

    static final int NOLACE_AF1_IN_CHANNELS = 1;

    static final int NOLACE_AF1_OUT_CHANNELS = 2;

    static final int NOLACE_AF1_NORM_P = 2;

    static final int NOLACE_AF1_FEATURE_DIM = 160;

    static final int NOLACE_AF1_KERNEL_OUT_SIZE = 32;

    static final int NOLACE_AF1_GAIN_OUT_SIZE = 2;

    static final int NOLACE_TDSHAPE1_FEATURE_DIM = 160;

    static final int NOLACE_TDSHAPE1_FRAME_SIZE = 80;

    static final int NOLACE_TDSHAPE1_AVG_POOL_K = 4;

    static final int NOLACE_TDSHAPE1_INNOVATE = 0;

    static final int NOLACE_TDSHAPE1_POOL_AFTER = 0;

    static final int NOLACE_TDSHAPE1_ALPHA1_F_OUT_SIZE = 80;

    static final int NOLACE_TDSHAPE1_ALPHA1_F_IN_SIZE = 160;

    static final int NOLACE_TDSHAPE1_ALPHA1_F_STATE_SIZE = 160;

    static final int NOLACE_TDSHAPE1_ALPHA1_F_DELAY = 0;

    static final int NOLACE_TDSHAPE1_ALPHA1_T_OUT_SIZE = 80;

    static final int NOLACE_TDSHAPE1_ALPHA1_T_IN_SIZE = 21;

    static final int NOLACE_TDSHAPE1_ALPHA1_T_STATE_SIZE = 21;

    static final int NOLACE_TDSHAPE1_ALPHA1_T_DELAY = 0;

    static final int NOLACE_TDSHAPE1_ALPHA2_OUT_SIZE = 80;

    static final int NOLACE_TDSHAPE1_ALPHA2_IN_SIZE = 80;

    static final int NOLACE_TDSHAPE1_ALPHA2_STATE_SIZE = 80;

    static final int NOLACE_TDSHAPE1_ALPHA2_DELAY = 0;

    static final int NOLACE_TDSHAPE2_FEATURE_DIM = 160;

    static final int NOLACE_TDSHAPE2_FRAME_SIZE = 80;

    static final int NOLACE_TDSHAPE2_AVG_POOL_K = 4;

    static final int NOLACE_TDSHAPE2_INNOVATE = 0;

    static final int NOLACE_TDSHAPE2_POOL_AFTER = 0;

    static final int NOLACE_TDSHAPE2_ALPHA1_F_OUT_SIZE = 80;

    static final int NOLACE_TDSHAPE2_ALPHA1_F_IN_SIZE = 160;

    static final int NOLACE_TDSHAPE2_ALPHA1_F_STATE_SIZE = 160;

    static final int NOLACE_TDSHAPE2_ALPHA1_F_DELAY = 0;

    static final int NOLACE_TDSHAPE2_ALPHA1_T_OUT_SIZE = 80;

    static final int NOLACE_TDSHAPE2_ALPHA1_T_IN_SIZE = 21;

    static final int NOLACE_TDSHAPE2_ALPHA1_T_STATE_SIZE = 21;

    static final int NOLACE_TDSHAPE2_ALPHA1_T_DELAY = 0;

    static final int NOLACE_TDSHAPE2_ALPHA2_OUT_SIZE = 80;

    static final int NOLACE_TDSHAPE2_ALPHA2_IN_SIZE = 80;

    static final int NOLACE_TDSHAPE2_ALPHA2_STATE_SIZE = 80;

    static final int NOLACE_TDSHAPE2_ALPHA2_DELAY = 0;

    static final int NOLACE_TDSHAPE3_FEATURE_DIM = 160;

    static final int NOLACE_TDSHAPE3_FRAME_SIZE = 80;

    static final int NOLACE_TDSHAPE3_AVG_POOL_K = 4;

    static final int NOLACE_TDSHAPE3_INNOVATE = 0;

    static final int NOLACE_TDSHAPE3_POOL_AFTER = 0;

    static final int NOLACE_TDSHAPE3_ALPHA1_F_OUT_SIZE = 80;

    static final int NOLACE_TDSHAPE3_ALPHA1_F_IN_SIZE = 160;

    static final int NOLACE_TDSHAPE3_ALPHA1_F_STATE_SIZE = 160;

    static final int NOLACE_TDSHAPE3_ALPHA1_F_DELAY = 0;

    static final int NOLACE_TDSHAPE3_ALPHA1_T_OUT_SIZE = 80;

    static final int NOLACE_TDSHAPE3_ALPHA1_T_IN_SIZE = 21;

    static final int NOLACE_TDSHAPE3_ALPHA1_T_STATE_SIZE = 21;

    static final int NOLACE_TDSHAPE3_ALPHA1_T_DELAY = 0;

    static final int NOLACE_TDSHAPE3_ALPHA2_OUT_SIZE = 80;

    static final int NOLACE_TDSHAPE3_ALPHA2_IN_SIZE = 80;

    static final int NOLACE_TDSHAPE3_ALPHA2_STATE_SIZE = 80;

    static final int NOLACE_TDSHAPE3_ALPHA2_DELAY = 0;

    static final float NOLACE_AF2_FILTER_GAIN_A = 1.381551f;

    static final float NOLACE_AF2_FILTER_GAIN_B = 0.000000f;

    static final float NOLACE_AF2_SHAPE_GAIN = 1.000000f;

    static final int NOLACE_AF2_KERNEL_SIZE = 16;

    static final int NOLACE_AF2_FRAME_SIZE = 80;

    static final int NOLACE_AF2_LEFT_PADDING = 15;

    static final int NOLACE_AF2_OVERLAP_SIZE = 40;

    static final int NOLACE_AF2_IN_CHANNELS = 2;

    static final int NOLACE_AF2_OUT_CHANNELS = 2;

    static final int NOLACE_AF2_NORM_P = 2;

    static final int NOLACE_AF2_FEATURE_DIM = 160;

    static final int NOLACE_AF2_KERNEL_OUT_SIZE = 64;

    static final int NOLACE_AF2_GAIN_OUT_SIZE = 2;

    static final float NOLACE_AF3_FILTER_GAIN_A = 1.381551f;

    static final float NOLACE_AF3_FILTER_GAIN_B = 0.000000f;

    static final float NOLACE_AF3_SHAPE_GAIN = 1.000000f;

    static final int NOLACE_AF3_KERNEL_SIZE = 16;

    static final int NOLACE_AF3_FRAME_SIZE = 80;

    static final int NOLACE_AF3_LEFT_PADDING = 15;

    static final int NOLACE_AF3_OVERLAP_SIZE = 40;

    static final int NOLACE_AF3_IN_CHANNELS = 2;

    static final int NOLACE_AF3_OUT_CHANNELS = 2;

    static final int NOLACE_AF3_NORM_P = 2;

    static final int NOLACE_AF3_FEATURE_DIM = 160;

    static final int NOLACE_AF3_KERNEL_OUT_SIZE = 64;

    static final int NOLACE_AF3_GAIN_OUT_SIZE = 2;

    static final float NOLACE_AF4_FILTER_GAIN_A = 1.381551f;

    static final float NOLACE_AF4_FILTER_GAIN_B = 0.000000f;

    static final float NOLACE_AF4_SHAPE_GAIN = 1.000000f;

    static final int NOLACE_AF4_KERNEL_SIZE = 16;

    static final int NOLACE_AF4_FRAME_SIZE = 80;

    static final int NOLACE_AF4_LEFT_PADDING = 15;

    static final int NOLACE_AF4_OVERLAP_SIZE = 40;

    static final int NOLACE_AF4_IN_CHANNELS = 2;

    static final int NOLACE_AF4_OUT_CHANNELS = 1;

    static final int NOLACE_AF4_NORM_P = 2;

    static final int NOLACE_AF4_FEATURE_DIM = 160;

    static final int NOLACE_AF4_KERNEL_OUT_SIZE = 32;

    static final int NOLACE_AF4_GAIN_OUT_SIZE = 1;

    static final int NOLACE_POST_CF1_OUT_SIZE = 160;

    static final int NOLACE_POST_CF1_IN_SIZE = 160;

    static final int NOLACE_POST_CF1_STATE_SIZE = 160;

    static final int NOLACE_POST_CF1_DELAY = 0;

    static final int NOLACE_POST_CF2_OUT_SIZE = 160;

    static final int NOLACE_POST_CF2_IN_SIZE = 160;

    static final int NOLACE_POST_CF2_STATE_SIZE = 160;

    static final int NOLACE_POST_CF2_DELAY = 0;

    static final int NOLACE_POST_AF1_OUT_SIZE = 160;

    static final int NOLACE_POST_AF1_IN_SIZE = 160;

    static final int NOLACE_POST_AF1_STATE_SIZE = 160;

    static final int NOLACE_POST_AF1_DELAY = 0;

    static final int NOLACE_POST_AF2_OUT_SIZE = 160;

    static final int NOLACE_POST_AF2_IN_SIZE = 160;

    static final int NOLACE_POST_AF2_STATE_SIZE = 160;

    static final int NOLACE_POST_AF2_DELAY = 0;

    static final int NOLACE_POST_AF3_OUT_SIZE = 160;

    static final int NOLACE_POST_AF3_IN_SIZE = 160;

    static final int NOLACE_POST_AF3_STATE_SIZE = 160;

    static final int NOLACE_POST_AF3_DELAY = 0;

    final LinearLayer nolace_pitch_embedding;

    final LinearLayer nolace_fnet_conv1;

    final LinearLayer nolace_fnet_conv2;

    final LinearLayer nolace_fnet_tconv;

    final LinearLayer nolace_fnet_gru_input;

    final LinearLayer nolace_fnet_gru_recurrent;

    final LinearLayer nolace_cf1_kernel;

    final LinearLayer nolace_cf1_gain;

    final LinearLayer nolace_cf1_global_gain;

    final LinearLayer nolace_cf2_kernel;

    final LinearLayer nolace_cf2_gain;

    final LinearLayer nolace_cf2_global_gain;

    final LinearLayer nolace_af1_kernel;

    final LinearLayer nolace_af1_gain;

    final LinearLayer nolace_tdshape1_alpha1_f;

    final LinearLayer nolace_tdshape1_alpha1_t;

    final LinearLayer nolace_tdshape1_alpha2;

    final LinearLayer nolace_tdshape2_alpha1_f;

    final LinearLayer nolace_tdshape2_alpha1_t;

    final LinearLayer nolace_tdshape2_alpha2;

    final LinearLayer nolace_tdshape3_alpha1_f;

    final LinearLayer nolace_tdshape3_alpha1_t;

    final LinearLayer nolace_tdshape3_alpha2;

    final LinearLayer nolace_af2_kernel;

    final LinearLayer nolace_af2_gain;

    final LinearLayer nolace_af3_kernel;

    final LinearLayer nolace_af3_gain;

    final LinearLayer nolace_af4_kernel;

    final LinearLayer nolace_af4_gain;

    final LinearLayer nolace_post_cf1;

    final LinearLayer nolace_post_cf2;

    final LinearLayer nolace_post_af1;

    final LinearLayer nolace_post_af2;

    final LinearLayer nolace_post_af3;

    /**
     * Initializes the layers from a weight blob ({@code init_nolacelayers}).
     *
     * @param blob the parsed weight blob
     * @throws IllegalStateException if an array is missing or has the wrong size
     */
    NolaceModel(WeightBlob blob) {
        nolace_pitch_embedding = LinearLayer.of(blob, "nolace_pitch_embedding_bias", null, null,
                "nolace_pitch_embedding_weights_float", null, null, null, 301, 64);
        nolace_fnet_conv1 = LinearLayer.of(blob, "nolace_fnet_conv1_bias", null, null,
                "nolace_fnet_conv1_weights_float", null, null, null, 173, 96);
        nolace_fnet_conv2 = LinearLayer.of(blob, "nolace_fnet_conv2_bias", "nolace_fnet_conv2_subias",
                "nolace_fnet_conv2_weights_int8", "nolace_fnet_conv2_weights_float", null, null,
                "nolace_fnet_conv2_scale", 768, 160);
        nolace_fnet_tconv = LinearLayer.of(blob, "nolace_fnet_tconv_bias", "nolace_fnet_tconv_subias",
                "nolace_fnet_tconv_weights_int8", "nolace_fnet_tconv_weights_float", null, null,
                "nolace_fnet_tconv_scale", 160, 640);
        nolace_fnet_gru_input = LinearLayer.of(blob, "nolace_fnet_gru_input_bias", "nolace_fnet_gru_input_subias",
                "nolace_fnet_gru_input_weights_int8", "nolace_fnet_gru_input_weights_float", null, null,
                "nolace_fnet_gru_input_scale", 160, 480);
        nolace_fnet_gru_recurrent = LinearLayer.of(blob, "nolace_fnet_gru_recurrent_bias",
                "nolace_fnet_gru_recurrent_subias", "nolace_fnet_gru_recurrent_weights_int8",
                "nolace_fnet_gru_recurrent_weights_float", null, null, "nolace_fnet_gru_recurrent_scale", 160,
                480);
        nolace_cf1_kernel = LinearLayer.of(blob, "nolace_cf1_kernel_bias", "nolace_cf1_kernel_subias",
                "nolace_cf1_kernel_weights_int8", "nolace_cf1_kernel_weights_float", null, null,
                "nolace_cf1_kernel_scale", 160, 16);
        nolace_cf1_gain = LinearLayer.of(blob, "nolace_cf1_gain_bias", null, null,
                "nolace_cf1_gain_weights_float", null, null, null, 160, 1);
        nolace_cf1_global_gain = LinearLayer.of(blob, "nolace_cf1_global_gain_bias", null, null,
                "nolace_cf1_global_gain_weights_float", null, null, null, 160, 1);
        nolace_cf2_kernel = LinearLayer.of(blob, "nolace_cf2_kernel_bias", "nolace_cf2_kernel_subias",
                "nolace_cf2_kernel_weights_int8", "nolace_cf2_kernel_weights_float", null, null,
                "nolace_cf2_kernel_scale", 160, 16);
        nolace_cf2_gain = LinearLayer.of(blob, "nolace_cf2_gain_bias", null, null,
                "nolace_cf2_gain_weights_float", null, null, null, 160, 1);
        nolace_cf2_global_gain = LinearLayer.of(blob, "nolace_cf2_global_gain_bias", null, null,
                "nolace_cf2_global_gain_weights_float", null, null, null, 160, 1);
        nolace_af1_kernel = LinearLayer.of(blob, "nolace_af1_kernel_bias", "nolace_af1_kernel_subias",
                "nolace_af1_kernel_weights_int8", "nolace_af1_kernel_weights_float", null, null,
                "nolace_af1_kernel_scale", 160, 32);
        nolace_af1_gain = LinearLayer.of(blob, "nolace_af1_gain_bias", null, null,
                "nolace_af1_gain_weights_float", null, null, null, 160, 2);
        nolace_tdshape1_alpha1_f = LinearLayer.of(blob, "nolace_tdshape1_alpha1_f_bias",
                "nolace_tdshape1_alpha1_f_subias", "nolace_tdshape1_alpha1_f_weights_int8",
                "nolace_tdshape1_alpha1_f_weights_float", null, null, "nolace_tdshape1_alpha1_f_scale", 320, 80);
        nolace_tdshape1_alpha1_t = LinearLayer.of(blob, "nolace_tdshape1_alpha1_t_bias", null, null,
                "nolace_tdshape1_alpha1_t_weights_float", null, null, null, 42, 80);
        nolace_tdshape1_alpha2 = LinearLayer.of(blob, "nolace_tdshape1_alpha2_bias", null, null,
                "nolace_tdshape1_alpha2_weights_float", null, null, null, 160, 80);
        nolace_tdshape2_alpha1_f = LinearLayer.of(blob, "nolace_tdshape2_alpha1_f_bias",
                "nolace_tdshape2_alpha1_f_subias", "nolace_tdshape2_alpha1_f_weights_int8",
                "nolace_tdshape2_alpha1_f_weights_float", null, null, "nolace_tdshape2_alpha1_f_scale", 320, 80);
        nolace_tdshape2_alpha1_t = LinearLayer.of(blob, "nolace_tdshape2_alpha1_t_bias", null, null,
                "nolace_tdshape2_alpha1_t_weights_float", null, null, null, 42, 80);
        nolace_tdshape2_alpha2 = LinearLayer.of(blob, "nolace_tdshape2_alpha2_bias", null, null,
                "nolace_tdshape2_alpha2_weights_float", null, null, null, 160, 80);
        nolace_tdshape3_alpha1_f = LinearLayer.of(blob, "nolace_tdshape3_alpha1_f_bias",
                "nolace_tdshape3_alpha1_f_subias", "nolace_tdshape3_alpha1_f_weights_int8",
                "nolace_tdshape3_alpha1_f_weights_float", null, null, "nolace_tdshape3_alpha1_f_scale", 320, 80);
        nolace_tdshape3_alpha1_t = LinearLayer.of(blob, "nolace_tdshape3_alpha1_t_bias", null, null,
                "nolace_tdshape3_alpha1_t_weights_float", null, null, null, 42, 80);
        nolace_tdshape3_alpha2 = LinearLayer.of(blob, "nolace_tdshape3_alpha2_bias", null, null,
                "nolace_tdshape3_alpha2_weights_float", null, null, null, 160, 80);
        nolace_af2_kernel = LinearLayer.of(blob, "nolace_af2_kernel_bias", "nolace_af2_kernel_subias",
                "nolace_af2_kernel_weights_int8", "nolace_af2_kernel_weights_float", null, null,
                "nolace_af2_kernel_scale", 160, 64);
        nolace_af2_gain = LinearLayer.of(blob, "nolace_af2_gain_bias", null, null,
                "nolace_af2_gain_weights_float", null, null, null, 160, 2);
        nolace_af3_kernel = LinearLayer.of(blob, "nolace_af3_kernel_bias", "nolace_af3_kernel_subias",
                "nolace_af3_kernel_weights_int8", "nolace_af3_kernel_weights_float", null, null,
                "nolace_af3_kernel_scale", 160, 64);
        nolace_af3_gain = LinearLayer.of(blob, "nolace_af3_gain_bias", null, null,
                "nolace_af3_gain_weights_float", null, null, null, 160, 2);
        nolace_af4_kernel = LinearLayer.of(blob, "nolace_af4_kernel_bias", "nolace_af4_kernel_subias",
                "nolace_af4_kernel_weights_int8", "nolace_af4_kernel_weights_float", null, null,
                "nolace_af4_kernel_scale", 160, 32);
        nolace_af4_gain = LinearLayer.of(blob, "nolace_af4_gain_bias", null, null,
                "nolace_af4_gain_weights_float", null, null, null, 160, 1);
        nolace_post_cf1 = LinearLayer.of(blob, "nolace_post_cf1_bias", "nolace_post_cf1_subias",
                "nolace_post_cf1_weights_int8", "nolace_post_cf1_weights_float", null, null,
                "nolace_post_cf1_scale", 320, 160);
        nolace_post_cf2 = LinearLayer.of(blob, "nolace_post_cf2_bias", "nolace_post_cf2_subias",
                "nolace_post_cf2_weights_int8", "nolace_post_cf2_weights_float", null, null,
                "nolace_post_cf2_scale", 320, 160);
        nolace_post_af1 = LinearLayer.of(blob, "nolace_post_af1_bias", "nolace_post_af1_subias",
                "nolace_post_af1_weights_int8", "nolace_post_af1_weights_float", null, null,
                "nolace_post_af1_scale", 320, 160);
        nolace_post_af2 = LinearLayer.of(blob, "nolace_post_af2_bias", "nolace_post_af2_subias",
                "nolace_post_af2_weights_int8", "nolace_post_af2_weights_float", null, null,
                "nolace_post_af2_scale", 320, 160);
        nolace_post_af3 = LinearLayer.of(blob, "nolace_post_af3_bias", "nolace_post_af3_subias",
                "nolace_post_af3_weights_int8", "nolace_post_af3_weights_float", null, null,
                "nolace_post_af3_scale", 320, 160);
    }

    /**
     * {@return the shared instance, loaded on first use}
     */
    static NolaceModel get() {
        return Holder.INSTANCE;
    }

    /**
     * Holds the shared instance.
     */
    private static final class Holder {
        private static final NolaceModel INSTANCE = new NolaceModel(WeightBlob.load("nolace.bin.gz"));
    }
}
