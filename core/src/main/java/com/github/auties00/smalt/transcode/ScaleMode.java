package com.github.auties00.smalt.transcode;

/**
 * Enumerates how a picture is fitted to a target box.
 *
 * <p>Sizes refer to the displayed picture, after any crop and orientation. A missing box
 * dimension leaves that dimension free: it follows the aspect ratio for {@link #FIT}, and keeps
 * the source size for {@link #FILL} and {@link #EXACT}.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum ScaleMode {
    /**
     * Scales the picture down, keeping its aspect ratio, until it fits inside the box; a picture
     * that already fits keeps its size unless enlargement is allowed.
     */
    FIT,

    /**
     * Scales the picture, keeping its aspect ratio, until it covers the box, then crops the
     * center to exactly the box.
     */
    FILL,

    /**
     * Stretches the picture to exactly the box, changing its aspect ratio if needed.
     */
    EXACT
}
