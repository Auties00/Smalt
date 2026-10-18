package com.github.auties00.smalt.video.h264.encoder;

/**
 * Enumerates the ways an {@link H264Encoder} delimits the NAL units of a packet.
 */
public enum H264PacketFormat {
    /**
     * Every NAL unit is preceded by its four-byte big-endian length, as in an MP4 sample; the
     * parameter sets travel out of band in the {@code AVCDecoderConfigurationRecord} of
     * {@link H264Encoder#codecConfig()}.
     */
    LENGTH_PREFIXED,

    /**
     * Every NAL unit is preceded by the four-byte start code {@code 00 00 00 01}, as in an ITU-T
     * H.264 Annex B byte stream, and every IDR access unit begins with the sequence and picture
     * parameter sets, so a receiver can start or resume decoding at any IDR picture without
     * out-of-band configuration; {@link H264Encoder#codecConfig()} is empty.
     */
    ANNEX_B
}
