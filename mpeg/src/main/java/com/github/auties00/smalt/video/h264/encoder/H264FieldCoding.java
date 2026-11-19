package com.github.auties00.smalt.video.h264.encoder;

/**
 * Enumerates how an {@link H264Encoder} codes interlaced pictures, whose two fields were sampled at
 * different times ({@link H264EncoderOptions#fieldOrder()}).
 */
public enum H264FieldCoding {
    /**
     * Each frame is coded as two field pictures ({@code field_pic_flag} 1), the first field in the
     * field order first, each predicted from the fields of earlier frames and the second also from the
     * first; both fields of a frame travel in one packet.
     */
    FIELD_PICTURES,

    /**
     * Each frame is coded as one picture of macroblock pairs, each pair coded as two frame macroblocks
     * or as the macroblocks of its two fields, whichever predicts it better
     * ({@code mb_adaptive_frame_field_flag} 1).
     */
    MBAFF
}
