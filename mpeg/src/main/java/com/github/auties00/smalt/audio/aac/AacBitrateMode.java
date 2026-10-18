package com.github.auties00.smalt.audio.aac;

/**
 * Selects the bitrate control of an {@link AacEncoder}.
 */
public enum AacBitrateMode {
    /**
     * Constant bitrate: the long-term average is exactly the configured bitrate, kept by a bit
     * reservoir and fill elements.
     */
    CBR,

    /**
     * Variable bitrate: frame sizes follow each frame's perceptual demand around the nominal
     * bitrate, without a bit reservoir ceiling or fill elements.
     */
    VBR
}
