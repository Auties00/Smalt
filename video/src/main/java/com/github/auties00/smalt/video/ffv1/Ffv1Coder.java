package com.github.auties00.smalt.video.ffv1;

/**
 * Enumerates the entropy coders of FFV1 (RFC 9043, section 4.2.3).
 */
public enum Ffv1Coder {
    /**
     * Golomb Rice codes with a run mode (coder type 0): the fastest coder and the least compact. It codes depths up
     * to 8 bits; deeper streams use the range coder.
     */
    GOLOMB_RICE,

    /**
     * The range coder with the default state transition table (coder type 1).
     */
    RANGE,

    /**
     * The range coder with the alternative state transition table of RFC 9043, figure 25, stored in the stream
     * (coder type 2): usually the most compact.
     */
    RANGE_CUSTOM_STATES
}
