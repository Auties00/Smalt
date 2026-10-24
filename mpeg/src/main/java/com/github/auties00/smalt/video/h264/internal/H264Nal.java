package com.github.auties00.smalt.video.h264.internal;

/**
 * The H.264 NAL unit types the codec handles ({@code nal_unit_type}, ITU-T H.264 Table 7-1).
 */
public final class H264Nal {
    /**
     * A coded slice of a non-IDR picture.
     */
    public static final int SLICE = 1;

    /**
     * Coded slice data partition A.
     */
    public static final int SLICE_PARTITION_A = 2;

    /**
     * Coded slice data partition B.
     */
    public static final int SLICE_PARTITION_B = 3;

    /**
     * Coded slice data partition C.
     */
    public static final int SLICE_PARTITION_C = 4;

    /**
     * A coded slice of an IDR picture.
     */
    public static final int IDR_SLICE = 5;

    /**
     * Supplemental enhancement information.
     */
    public static final int SEI = 6;

    /**
     * A sequence parameter set.
     */
    public static final int SPS = 7;

    /**
     * A picture parameter set.
     */
    public static final int PPS = 8;

    /**
     * An access unit delimiter.
     */
    public static final int AUD = 9;

    /**
     * The end of a coded video sequence.
     */
    public static final int END_OF_SEQUENCE = 10;

    /**
     * The end of the bitstream.
     */
    public static final int END_OF_STREAM = 11;

    /**
     * A sequence parameter set extension, which describes the auxiliary coded pictures.
     */
    public static final int SPS_EXTENSION = 13;

    /**
     * A prefix NAL unit, which carries the scalable or multiview header extension of the base
     * layer or base view slice that follows it.
     */
    public static final int PREFIX = 14;

    /**
     * A subset sequence parameter set, the sequence parameter set of the scalable and multiview
     * extensions.
     */
    public static final int SUBSET_SPS = 15;

    /**
     * A coded slice of an auxiliary coded picture without partitioning.
     */
    public static final int AUXILIARY_SLICE = 19;

    /**
     * A coded slice extension: a slice of a scalable enhancement layer or of a non-base view.
     */
    public static final int SLICE_EXTENSION = 20;

    /**
     * A coded slice extension of a depth view component (3D-AVC).
     */
    public static final int DEPTH_SLICE_EXTENSION = 21;

    private H264Nal() {
        throw new AssertionError();
    }
}
