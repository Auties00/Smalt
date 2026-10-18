package com.github.auties00.smalt.video.av1;

/**
 * The kind of stream an {@link Av1Encoder} produces, which decides its frame structure, its rate
 * control and how long it may hold pictures back.
 */
public enum Av1EncoderUsage {
    /**
     * Low-delay coding for calls and live streaming: every picture yields its packet immediately,
     * at a constant bitrate with a buffer model, from past frames only.
     */
    REALTIME,

    /**
     * Coding for files and on-demand streaming: pictures wait in a lookahead, golden frame groups
     * are coded as a pyramid of hidden, temporally filtered alternate references, and the rate
     * control keeps a constant quality or a variable bitrate.
     */
    GOOD_QUALITY,

    /**
     * Intra-only coding at a constant quality: every picture is a key frame searched as a still
     * image.
     */
    ALL_INTRA
}
