package com.github.auties00.smalt.track;

/**
 * Enumerates how a video track declares the scan of its frames: progressive, or interlaced with
 * the order in which the two fields are displayed and stored.
 *
 * <p>The top field holds the frame's rows 0, 2, 4 and so on; the bottom field rows 1, 3, 5 and so
 * on. The constants mirror the field orders that the QuickTime {@code fiel} box and the Matroska
 * {@code FieldOrder} element share (RFC 9559 section 5.1.4.1.28.2); {@link #code()} gives that
 * shared value. In the interleaved orders each frame holds both fields woven row by row, top row
 * first; in the separated orders a frame stores one field after the other.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
 * {@code default} branch.
 */
public enum Interlacing {
    /**
     * Progressive frames: every row belongs to the same instant ({@code fiel} field count 1,
     * Matroska {@code FlagInterlaced} 2).
     */
    PROGRESSIVE(0),

    /**
     * Interlaced frames whose field order is not declared (Matroska {@code FieldOrder} 2).
     */
    INTERLACED(2),

    /**
     * Interleaved fields, the top field displayed first (field order 9, ffprobe {@code tb}).
     */
    TOP_FIELD_FIRST(9),

    /**
     * Interleaved fields, the bottom field displayed first (field order 14, ffprobe {@code bt}).
     */
    BOTTOM_FIELD_FIRST(14),

    /**
     * Separated fields, the top field stored and displayed first (field order 1, ffprobe
     * {@code tt}).
     */
    TOP_FIELD_FIRST_SEPARATED(1),

    /**
     * Separated fields, the bottom field stored and displayed first (field order 6, ffprobe
     * {@code bb}).
     */
    BOTTOM_FIELD_FIRST_SEPARATED(6);

    /**
     * The shared field order value.
     */
    private final int code;

    Interlacing(int code) {
        this.code = code;
    }

    /**
     * Returns the field order value the QuickTime {@code fiel} box and the Matroska
     * {@code FieldOrder} element use for this scan.
     *
     * @return 0 for {@link #PROGRESSIVE}, 2 for {@link #INTERLACED}, otherwise 1, 6, 9 or 14
     */
    public int code() {
        return code;
    }

    /**
     * Returns the scan of a field order value.
     *
     * @param code a QuickTime {@code fiel} or Matroska {@code FieldOrder} value
     * @return the scan; {@link #INTERLACED} for a value this enum does not name, since only an
     * interlaced track declares a field order
     */
    public static Interlacing ofCode(long code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return INTERLACED;
    }

    /**
     * Returns whether the frames are interlaced.
     *
     * @return {@code true} for every constant except {@link #PROGRESSIVE}
     */
    public boolean isInterlaced() {
        return this != PROGRESSIVE;
    }

    /**
     * Returns whether the top field is displayed first.
     *
     * @return {@code true} for {@link #TOP_FIELD_FIRST} and {@link #TOP_FIELD_FIRST_SEPARATED}
     */
    public boolean isTopFieldFirst() {
        return this == TOP_FIELD_FIRST || this == TOP_FIELD_FIRST_SEPARATED;
    }
}
