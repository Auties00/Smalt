package com.github.auties00.smalt.video.vp8;

/**
 * Enumerates the kinds of stream a {@link Vp8Encoder} produces, which decide its frame structure,
 * its rate control and how long it may hold pictures back.
 */
public enum Vp8EncoderUsage {
    /**
     * Low-delay coding for calls and live streaming: every picture yields its packet immediately,
     * at a constant bitrate with a buffer model, predicted from past frames only.
     */
    REALTIME,

    /**
     * Coding for files and on-demand streaming: pictures wait in a lookahead, each golden frame
     * group is predicted also from a hidden, temporally filtered alternate reference, and the rate
     * control spends a variable bitrate, a constrained quality or a constant quality, in one pass
     * or in the second pass of a two-pass encode.
     */
    GOOD_QUALITY,

    /**
     * The coding of {@link #GOOD_QUALITY} with the fullest search whatever the
     * {@linkplain Vp8EncoderOptions#speed() speed}: the mode thresholds are the lowest, every
     * split of a macroblock is searched and a split's poorly predicted partitions fall back to an
     * exhaustive motion search, so it compresses best and encodes slowest.
     */
    BEST_QUALITY
}
