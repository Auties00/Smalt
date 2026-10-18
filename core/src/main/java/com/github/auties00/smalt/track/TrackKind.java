package com.github.auties00.smalt.track;

/**
 * Enumerates the kinds of elementary stream a container track can carry.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum TrackKind {
    /**
     * A sequence of pictures.
     */
    VIDEO,

    /**
     * A sequence of audio samples.
     */
    AUDIO,

    /**
     * Timed text, metadata, or any other stream this module does not decode.
     */
    DATA
}
