package com.github.auties00.smalt.audio.opus;

/**
 * The bitrate control of an {@link OpusEncoder}.
 */
public enum OpusBitrateMode {
    /**
     * Unconstrained variable bitrate: the size of each packet follows the content.
     */
    VBR,

    /**
     * Variable bitrate constrained to the bit reservoir of a constant-bitrate stream.
     */
    CONSTRAINED_VBR,

    /**
     * Constant bitrate: every packet of one duration has the same size.
     */
    CBR
}
