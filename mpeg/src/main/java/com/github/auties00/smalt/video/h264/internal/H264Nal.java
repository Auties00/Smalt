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

    private H264Nal() {
        throw new AssertionError();
    }
}
