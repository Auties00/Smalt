package com.github.auties00.smalt.video.hevc.encoder;

/**
 * Enumerates the ways an {@link HevcEncoder} delimits the NAL units of a packet.
 */
public enum HevcPacketFormat {
    /**
     * Every NAL unit is preceded by its four-byte big-endian length, as in an MP4 sample; the parameter sets
     * travel out of band in the {@code HEVCDecoderConfigurationRecord} of {@link HevcEncoder#codecConfig()}.
     */
    LENGTH_PREFIXED,

    /**
     * Every NAL unit is preceded by the four-byte start code {@code 00 00 00 01}, as in an ITU-T H.265
     * Annex B byte stream, and every IDR access unit begins with the video, sequence and picture parameter
     * sets, so a receiver can start decoding at any IDR picture without out-of-band configuration;
     * {@link HevcEncoder#codecConfig()} is empty.
     */
    ANNEX_B
}
