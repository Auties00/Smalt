package com.github.auties00.smalt.transcode;

/**
 * Enumerates what an image transcode does with an animated input.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum AnimationMode {
    /**
     * Writes an animation when the output format can hold one and an animation encoder for it is
     * available, and the first frame otherwise.
     */
    KEEP,

    /**
     * Writes the first frame as a still image.
     */
    FIRST_FRAME
}
