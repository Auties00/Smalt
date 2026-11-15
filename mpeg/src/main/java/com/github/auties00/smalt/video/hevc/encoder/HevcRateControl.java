package com.github.auties00.smalt.video.hevc.encoder;

/**
 * Enumerates the rate control modes of an {@link HevcEncoder}.
 */
public enum HevcRateControl {
    /**
     * Codes every frame around a fixed quantizer, {@link HevcEncoderOptions#quality()}; the bitrate settings
     * are ignored.
     */
    CONSTANT_QUALITY,

    /**
     * Approaches the target bitrate on average while a video buffering verifier bounds every picture, as
     * {@link HevcEncoderOptions#rateControl()} describes.
     */
    AVERAGE_BITRATE,

    /**
     * Holds every frame within a tight buffer at the target bitrate, for real-time transmission.
     */
    CONSTANT_BITRATE
}
