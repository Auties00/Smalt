package com.github.auties00.smalt.video.vp8;

/**
 * Enumerates the rate control modes of a {@link Vp8Encoder}.
 *
 * <p>Real-time coding uses {@link #CONSTANT_BITRATE} only; good-quality coding uses the other
 * three.
 */
public enum Vp8RateControl {
    /**
     * A constant bitrate: frame sizes follow a buffer model the channel fills at the target
     * bitrate, so the bitrate over about a second stays near the target.
     */
    CONSTANT_BITRATE,

    /**
     * A variable bitrate: the stream spends the target bitrate on average, more on complex frame
     * groups and less on simple ones.
     */
    VARIABLE_BITRATE,

    /**
     * A variable bitrate whose quantizers never go below the quality level, so simple content
     * spends less than the target bitrate.
     */
    CONSTRAINED_QUALITY,

    /**
     * A constant quality: the quantizers follow the quality level, finer for the frames others
     * predict from, whatever the bitrate.
     */
    CONSTANT_QUALITY
}
