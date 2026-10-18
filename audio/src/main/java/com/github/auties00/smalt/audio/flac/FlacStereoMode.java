package com.github.auties00.smalt.audio.flac;

/**
 * Selects how a {@link FlacEncoder} decorrelates the two channels of a stereo stream.
 *
 * <p>FLAC codes a stereo frame as two independent channels, or as left and side, side and
 * right, or mid and side signals (RFC 9639 section 4.2); the encoder picks one per frame. Streams
 * with other channel counts always code independent channels.
 */
public enum FlacStereoMode {
    /**
     * Codes the two channels independently in every frame.
     */
    INDEPENDENT,

    /**
     * Evaluates mid and side only periodically (about every 0.4 seconds) and otherwise reuses the
     * previous frame's choice between independent and mid/side coding: libFLAC's loose mid/side
     * stereo, faster and slightly larger.
     */
    LOOSE_MID_SIDE,

    /**
     * Evaluates every frame as independent, left/side, side/right and mid/side channels and keeps
     * the smallest: libFLAC's mid/side stereo.
     */
    MID_SIDE
}
