package com.github.auties00.smalt.image;

/**
 * Enumerates the quantization ranges of Y'CbCr samples.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum ColorRange {
    /**
     * Studio (limited, TV) range: for 8-bit samples luma spans 16..235 and chroma 16..240.
     */
    LIMITED,

    /**
     * Full (PC, JPEG) range: every 8-bit sample spans 0..255.
     */
    FULL
}
