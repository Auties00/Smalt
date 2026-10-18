package com.github.auties00.smalt.video;

/**
 * Enumerates how the rows of a coded frame are scanned in time: progressively, or as two
 * interlaced fields of alternate rows, one coded and displayed before the other.
 *
 * <p>The top field holds the frame's rows 0, 2, 4 and so on; the bottom field rows 1, 3, 5 and so
 * on. Decoders hand out an interlaced frame with both fields woven into one picture.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum FieldOrder {
    /**
     * A progressive frame: every row belongs to the same instant.
     */
    PROGRESSIVE,

    /**
     * An interlaced frame whose top field comes first.
     */
    TOP_FIRST,

    /**
     * An interlaced frame whose bottom field comes first.
     */
    BOTTOM_FIRST;

    /**
     * Returns whether the frame is interlaced.
     *
     * @return {@code true} for {@link #TOP_FIRST} and {@link #BOTTOM_FIRST}
     */
    public boolean isInterlaced() {
        return this != PROGRESSIVE;
    }
}
