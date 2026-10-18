package com.github.auties00.smalt.audio.mp3;

/**
 * Selects the bitrate control of an {@link Mp3Encoder}.
 */
public enum Mp3BitrateMode {
    /**
     * Constant bitrate: every frame has the configured bitrate; a bit reservoir lends the unused
     * bytes of easy frames to demanding ones.
     */
    CBR,

    /**
     * Variable bitrate: every frame is coded at the noise level of the quality setting and takes
     * the lowest bitrate that holds it; a Xing tag with a seek table lets players seek.
     */
    VBR,

    /**
     * Average bitrate: variable bitrate whose noise level is steered so that the long-term
     * average approaches the configured bitrate.
     */
    ABR
}
