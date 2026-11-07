package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.track.Rational;

/**
 * The stream-level values that {@link ParameterSets} writes into the sequence and picture parameter
 * sets, supplied by the settings of either encoding engine.
 */
interface StreamParameters {
    /**
     * {@return the resolved settings of the encoder}
     */
    EncoderConfig config();

    /**
     * {@return the {@code profile_idc}}
     */
    int profileIdc();

    /**
     * {@return the signaled level, {@code 9} for level 1b}
     */
    int levelIdc();

    /**
     * {@return the {@code chroma_format_idc}, from 0 to 3}
     */
    int chromaFormatIdc();

    /**
     * {@return the luma and chroma bit depth, from 8 to 14}
     */
    int bitDepth();

    /**
     * {@return whether {@code qpprime_y_zero_transform_bypass_flag} is set}
     */
    boolean transformBypass();

    /**
     * {@return the binary logarithm of {@code MaxFrameNum}}
     */
    int log2MaxFrameNum();

    /**
     * {@return whether the stream uses {@code pic_order_cnt_type} 0 rather than 2}
     */
    boolean pictureOrderType0();

    /**
     * {@return the number of {@code pic_order_cnt_lsb} bits}
     */
    int log2MaxPocLsb();

    /**
     * {@return the {@code max_num_ref_frames}}
     */
    int numRefFrames();

    /**
     * {@return whether {@code gaps_in_frame_num_value_allowed_flag} is set}
     */
    boolean frameNumGapsAllowed();

    /**
     * {@return the picture width in macroblocks}
     */
    int mbWidth();

    /**
     * {@return the frame height in macroblocks, a multiple of 2 for interlaced streams}
     */
    int mbHeight();

    /**
     * {@return whether every picture is a progressive frame ({@code frame_mbs_only_flag})}
     */
    boolean frameMbsOnly();

    /**
     * {@return whether frames are coded as macroblock pairs ({@code mb_adaptive_frame_field_flag})}
     */
    boolean mbaff();

    /**
     * {@return the number of luma samples cropped on the right}
     */
    int cropRight();

    /**
     * {@return the number of luma samples cropped at the bottom}
     */
    int cropBottom();

    /**
     * {@return the color description of the video usability information}
     */
    ColorInfo color();

    /**
     * {@return the sample aspect ratio}
     */
    Rational sampleAspectRatio();

    /**
     * {@return the {@code num_units_in_tick}}
     */
    long numUnitsInTick();

    /**
     * {@return the {@code time_scale}}
     */
    long timeScale();

    /**
     * {@return whether {@code fixed_frame_rate_flag} is set}
     */
    boolean fixedFrameRate();

    /**
     * {@return the {@code max_num_reorder_frames}}
     */
    int reorderFrames();

    /**
     * {@return the {@code max_dec_frame_buffering}}
     */
    int maxDecFrameBuffering();

    /**
     * {@return whether the entropy coder is CABAC}
     */
    boolean cabac();

    /**
     * {@return the {@code num_ref_idx_l0_default_active_minus1} plus one}
     */
    int activeRefs();

    /**
     * {@return the {@code chroma_qp_index_offset}}
     */
    int chromaQpOffset();

    /**
     * {@return whether {@code transform_8x8_mode_flag} is set}
     */
    boolean transform8x8();

    /**
     * {@return whether the slice headers of frames carry {@code delta_pic_order_cnt_bottom}}
     */
    boolean bottomFieldPicOrder();
}
