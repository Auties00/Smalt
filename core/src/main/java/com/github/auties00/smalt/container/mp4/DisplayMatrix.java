package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.image.Orientation;

/**
 * Converts between the transformation matrix of an ISO base media track header and an
 * {@link Orientation}.
 *
 * <p>The track header stores a 3x3 matrix {@code {a, b, u, c, d, v, x, y, w}} where {@code a},
 * {@code b}, {@code c}, {@code d}, {@code x} and {@code y} are 16.16 fixed point and {@code u},
 * {@code v}, {@code w} are 2.30 fixed point. A stored pixel {@code (p, q)} is displayed at
 * {@code (a*p + c*q + x, b*p + d*q + y)}, with the vertical axis pointing down. The eight
 * orientations are the matrices whose upper-left 2x2 block has exactly one non-zero entry of equal
 * magnitude per row and column; a matrix with any other shape (a shear, a non-right-angle
 * rotation, a zero determinant) is reported as {@link Orientation#NORMAL}.
 */
final class DisplayMatrix {
    /**
     * The 16.16 fixed-point representation of one.
     */
    private static final int ONE = 0x10000;

    /**
     * The 2.30 fixed-point representation of one, used for the {@code w} element.
     */
    private static final int ONE_30 = 0x40000000;

    private DisplayMatrix() {
        throw new AssertionError();
    }

    /**
     * Returns the orientation described by a track header matrix.
     *
     * @param matrix the nine matrix elements in file order
     * @return the orientation, or {@link Orientation#NORMAL} when the matrix is not one of the
     * eight right-angle transforms
     */
    static Orientation toOrientation(int[] matrix) {
        var a = matrix[0];
        var b = matrix[1];
        var c = matrix[3];
        var d = matrix[4];
        int cos;
        int sin;
        boolean mirror;
        if (b == 0 && c == 0 && a != 0 && d != 0 && Math.abs((long) a) == Math.abs((long) d)) {
            mirror = Integer.signum(a) * Integer.signum(d) < 0;
            // Removing a left-to-right mirror applied first negates the first column.
            cos = mirror ? -Integer.signum(a) : Integer.signum(a);
            sin = 0;
        } else if (a == 0 && d == 0 && b != 0 && c != 0 && Math.abs((long) b) == Math.abs((long) c)) {
            mirror = Integer.signum(b) * Integer.signum(c) > 0;
            cos = 0;
            sin = mirror ? -Integer.signum(b) : Integer.signum(b);
        } else {
            return Orientation.NORMAL;
        }
        var degrees = cos == 1 ? 0 : cos == -1 ? 180 : sin == 1 ? 90 : 270;
        return Orientation.ofRotation(degrees, mirror);
    }

    /**
     * Returns the track header matrix of an orientation for a picture of the given coded size.
     *
     * <p>The translation moves the transformed picture back into the positive quadrant, as
     * cameras write it; players that ignore the translation still display the same picture.
     *
     * @param orientation the orientation to encode
     * @param width the coded width in pixels
     * @param height the coded height in pixels
     * @return the nine matrix elements in file order
     */
    static int[] of(Orientation orientation, int width, int height) {
        var w = width << 16;
        var h = height << 16;
        return switch (orientation) {
            case NORMAL -> new int[]{ONE, 0, 0, 0, ONE, 0, 0, 0, ONE_30};
            case FLIP_HORIZONTAL -> new int[]{-ONE, 0, 0, 0, ONE, 0, w, 0, ONE_30};
            case ROTATE_180 -> new int[]{-ONE, 0, 0, 0, -ONE, 0, w, h, ONE_30};
            case FLIP_VERTICAL -> new int[]{ONE, 0, 0, 0, -ONE, 0, 0, h, ONE_30};
            case TRANSPOSE -> new int[]{0, ONE, 0, ONE, 0, 0, 0, 0, ONE_30};
            case ROTATE_90 -> new int[]{0, ONE, 0, -ONE, 0, 0, h, 0, ONE_30};
            case TRANSVERSE -> new int[]{0, -ONE, 0, -ONE, 0, 0, h, w, ONE_30};
            case ROTATE_270 -> new int[]{0, -ONE, 0, ONE, 0, 0, 0, w, ONE_30};
        };
    }
}
