package com.github.auties00.smalt.transcode;

/**
 * Enumerates how a transcode handles the display orientation of a video stream (the rotation an
 * MP4 display matrix records, as phones write it).
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum OrientationMode {
    /**
     * Keeps the orientation as metadata when the output container stores one
     * ({@link OutputContainer#storesOrientation()}), and turns the pixels upright otherwise.
     */
    AUTO,

    /**
     * Keeps the coded pixels and records the orientation in the output; for a container that
     * cannot store one, the orientation is lost and the video plays as coded.
     */
    PRESERVE,

    /**
     * Turns the pixels upright and writes no orientation.
     */
    APPLY
}
