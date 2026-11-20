package com.github.auties00.smalt.video.h264.encoder;

/**
 * Enumerates the rate control modes of an {@link H264Encoder}.
 */
public enum H264RateControl {
    /**
     * Codes every frame around a fixed quantizer, {@link H264EncoderOptions#quality()}; the
     * bitrate settings are ignored.
     */
    CONSTANT_QUALITY,

    /**
     * Approaches the target bitrate on average while a video buffering verifier never
     * underflows; with {@link H264EncoderOptions#firstPassStats()} the second pass of a two-pass encode
     * spends the bits over the whole video.
     */
    AVERAGE_BITRATE,

    /**
     * Holds every frame within a tight buffer at the target bitrate, for real-time transmission; with
     * {@link H264EncoderOptions#hrd()} filler data also keeps the stream's rate exactly constant, as
     * broadcast transport streams need.
     */
    CONSTANT_BITRATE
}
