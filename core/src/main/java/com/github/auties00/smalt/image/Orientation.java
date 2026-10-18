package com.github.auties00.smalt.image;

/**
 * Enumerates the eight display orientations of a stored picture, with the code points of the
 * EXIF {@code Orientation} tag (0x0112).
 *
 * <p>Each constant names the transform a viewer applies to the stored pixels to display them
 * upright. The same eight transforms describe HEIF {@code irot}/{@code imir} properties and the
 * rotation-and-mirror display matrices of MP4 tracks. Rotations are clockwise.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum Orientation {
    /**
     * The stored pixels are upright (EXIF 1).
     */
    NORMAL(1, false),

    /**
     * Mirror left to right (EXIF 2).
     */
    FLIP_HORIZONTAL(2, false),

    /**
     * Rotate by 180 degrees (EXIF 3).
     */
    ROTATE_180(3, false),

    /**
     * Mirror top to bottom (EXIF 4).
     */
    FLIP_VERTICAL(4, false),

    /**
     * Mirror across the top-left to bottom-right diagonal (EXIF 5).
     */
    TRANSPOSE(5, true),

    /**
     * Rotate by 90 degrees clockwise (EXIF 6).
     */
    ROTATE_90(6, true),

    /**
     * Mirror across the top-right to bottom-left diagonal (EXIF 7).
     */
    TRANSVERSE(7, true),

    /**
     * Rotate by 270 degrees clockwise (EXIF 8).
     */
    ROTATE_270(8, true);

    /**
     * The EXIF code point.
     */
    private final int exifCode;

    /**
     * Whether applying the transform swaps width and height.
     */
    private final boolean swapsDimensions;

    /**
     * Constructs a constant.
     *
     * @param exifCode        the EXIF code point
     * @param swapsDimensions whether the transform swaps width and height
     */
    Orientation(int exifCode, boolean swapsDimensions) {
        this.exifCode = exifCode;
        this.swapsDimensions = swapsDimensions;
    }

    /**
     * Returns the EXIF code point.
     *
     * @return a value from 1 to 8
     */
    public int exifCode() {
        return exifCode;
    }

    /**
     * Returns whether applying this transform swaps width and height.
     *
     * @return {@code true} for the four transforms that rotate by a quarter turn
     */
    public boolean swapsDimensions() {
        return swapsDimensions;
    }

    /**
     * Returns the constant for an EXIF code point.
     *
     * @param code the EXIF {@code Orientation} value
     * @return the matching constant, or {@link #NORMAL} for values outside 1..8
     */
    public static Orientation ofExif(int code) {
        return code >= 1 && code <= 8 ? values()[code - 1] : NORMAL;
    }

    /**
     * Returns the constant for a clockwise rotation, optionally preceded by a left-to-right mirror.
     *
     * @param degrees the clockwise rotation, normalized modulo 360; only multiples of 90 are exact
     * @param mirror  whether the stored pixels are mirrored left to right before rotating
     * @return the matching constant; rotations that are not multiples of 90 round to the nearest
     *         quarter turn
     */
    public static Orientation ofRotation(int degrees, boolean mirror) {
        var quarter = Math.floorMod(Math.round(degrees / 90.0f), 4);
        if (!mirror) {
            return switch (quarter) {
                case 1 -> ROTATE_90;
                case 2 -> ROTATE_180;
                case 3 -> ROTATE_270;
                default -> NORMAL;
            };
        }
        return switch (quarter) {
            case 1 -> TRANSVERSE;
            case 2 -> FLIP_VERTICAL;
            case 3 -> TRANSPOSE;
            default -> FLIP_HORIZONTAL;
        };
    }
}
