package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.image.Orientation;

import java.util.List;

/**
 * Reduces the transformative properties of an image item ({@code clap}, {@code irot},
 * {@code imir}) to one crop of the stored image followed by one {@link Orientation}.
 *
 * <p>ISO/IEC 23008-12 clause 6.5.1 applies the transformative properties in the order of their
 * association, each to the output of the previous one; MIAF clause 7.3.6.7 restricts that order to
 * clean aperture first, then rotation, then mirroring, but any order is reduced exactly here:
 * rotations and mirrors compose into one of the eight orientations, and a crop that follows some
 * of them is mapped back onto the stored image, where a rectangle stays a rectangle. Showing
 * {@code crop(stored)} through {@link #orientation()} therefore gives the image the properties
 * describe.
 *
 * <p>The clean aperture rectangle is computed as ISO/IEC 14496-12 clause 12.1.4.1 defines it and
 * MIAF clause 7.3.6.7 constrains it: its size and left and top edges must be whole samples, and
 * it must lie inside the image. A clean aperture that breaks these rules is ignored, so the image
 * is shown uncropped.
 *
 * @param x the left edge of the crop in the stored image
 * @param y the top edge of the crop in the stored image
 * @param width the width of the crop, positive
 * @param height the height of the crop, positive
 * @param orientation the transform that displays the cropped image upright
 * @implNote This implementation computes the clean aperture with the exact fraction arithmetic of
 * libavif's {@code avifCropRectFromCleanApertureBox} ({@code src/avif.c}, BSD 2-clause), in 64-bit
 * integers.
 */
public record HeifGeometry(int x, int y, int width, int height, Orientation orientation) {
    /**
     * The matrix of each {@link Orientation}, in ordinal order: the images of the unit vectors
     * {@code (1, 0)} and {@code (0, 1)} in a frame with y pointing down, as
     * {@code {xx, xy, yx, yy}} so that {@code (x, y)} maps to {@code (xx*x + xy*y, yx*x + yy*y)}.
     */
    private static final int[][] MATRICES = {
            {1, 0, 0, 1},
            {-1, 0, 0, 1},
            {-1, 0, 0, -1},
            {1, 0, 0, -1},
            {0, 1, 1, 0},
            {0, -1, 1, 0},
            {0, -1, -1, 0},
            {0, 1, -1, 0}
    };

    /**
     * Resolves the transformative properties of an item.
     *
     * @param properties the item's properties in association order
     * @param width the width of the reconstructed image, positive
     * @param height the height of the reconstructed image, positive
     * @return the crop and orientation; the whole image and {@link Orientation#NORMAL} when the
     * item has no transformative property
     */
    public static HeifGeometry resolve(List<HeifProperty> properties, int width, int height) {
        var orientation = Orientation.NORMAL;
        int cropX = 0;
        int cropY = 0;
        int cropWidth = width;
        int cropHeight = height;
        for (var property : properties) {
            switch (property) {
                case HeifProperty.Rotation rotation -> orientation = compose(switch (rotation.quarterTurns()) {
                    case 1 -> Orientation.ROTATE_270;
                    case 2 -> Orientation.ROTATE_180;
                    case 3 -> Orientation.ROTATE_90;
                    default -> Orientation.NORMAL;
                }, orientation);
                case HeifProperty.Mirror mirror -> orientation = compose(mirror.axis() == 0
                        ? Orientation.FLIP_VERTICAL : Orientation.FLIP_HORIZONTAL, orientation);
                case HeifProperty.CleanAperture clap -> {
                    var shownWidth = orientation.swapsDimensions() ? cropHeight : cropWidth;
                    var shownHeight = orientation.swapsDimensions() ? cropWidth : cropHeight;
                    var rect = cleanAperture(clap, shownWidth, shownHeight);
                    if (rect != null) {
                        var inverse = inverse(orientation);
                        var x0 = map(inverse, rect[0], rect[1], shownWidth, shownHeight);
                        var x1 = map(inverse, rect[0] + rect[2] - 1, rect[1] + rect[3] - 1, shownWidth, shownHeight);
                        var left = Math.min(x0[0], x1[0]);
                        var top = Math.min(x0[1], x1[1]);
                        cropX += left;
                        cropY += top;
                        cropWidth = Math.abs(x1[0] - x0[0]) + 1;
                        cropHeight = Math.abs(x1[1] - x0[1]) + 1;
                    }
                }
                default -> {
                }
            }
        }
        return new HeifGeometry(cropX, cropY, cropWidth, cropHeight, orientation);
    }

    /**
     * Returns whether the geometry crops the image.
     *
     * @param imageWidth the width of the reconstructed image
     * @param imageHeight the height of the reconstructed image
     * @return {@code true} unless the crop is the whole image
     */
    public boolean crops(int imageWidth, int imageHeight) {
        return x != 0 || y != 0 || width != imageWidth || height != imageHeight;
    }

    /**
     * Returns the transform that applies one orientation after another.
     *
     * @param after the transform applied second
     * @param before the transform applied first
     * @return the composed transform
     */
    static Orientation compose(Orientation after, Orientation before) {
        var a = MATRICES[after.ordinal()];
        var b = MATRICES[before.ordinal()];
        var product = new int[]{
                a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
                a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3]
        };
        for (var orientation : Orientation.values()) {
            if (java.util.Arrays.equals(MATRICES[orientation.ordinal()], product)) {
                return orientation;
            }
        }
        throw new AssertionError("orientations are closed under composition");
    }

    /**
     * Returns the transform that undoes an orientation.
     *
     * @param orientation the orientation to undo
     * @return its inverse: the other quarter turn for the two quarter turns, the orientation itself
     * otherwise
     */
    static Orientation inverse(Orientation orientation) {
        return switch (orientation) {
            case ROTATE_90 -> Orientation.ROTATE_270;
            case ROTATE_270 -> Orientation.ROTATE_90;
            default -> orientation;
        };
    }

    /**
     * Maps a sample position through an orientation.
     *
     * @param orientation the transform
     * @param x the column in the source image
     * @param y the row in the source image
     * @param width the source image width
     * @param height the source image height
     * @return the column and row in the transformed image
     */
    static int[] map(Orientation orientation, int x, int y, int width, int height) {
        return switch (orientation) {
            case NORMAL -> new int[]{x, y};
            case FLIP_HORIZONTAL -> new int[]{width - 1 - x, y};
            case ROTATE_180 -> new int[]{width - 1 - x, height - 1 - y};
            case FLIP_VERTICAL -> new int[]{x, height - 1 - y};
            case TRANSPOSE -> new int[]{y, x};
            case ROTATE_90 -> new int[]{height - 1 - y, x};
            case TRANSVERSE -> new int[]{height - 1 - y, width - 1 - x};
            case ROTATE_270 -> new int[]{y, width - 1 - x};
        };
    }

    /**
     * Computes the rectangle a clean aperture keeps.
     *
     * @param clap the clean aperture
     * @param width the width of the image it applies to
     * @param height the height of the image it applies to
     * @return {@code {x, y, width, height}}, or {@code null} when the clean aperture is invalid
     */
    static int[] cleanAperture(HeifProperty.CleanAperture clap, int width, int height) {
        long widthN = clap.widthN();
        long widthD = clap.widthD();
        long heightN = clap.heightN();
        long heightD = clap.heightD();
        long offsetXN = clap.horizontalOffsetN();
        long offsetXD = clap.horizontalOffsetD();
        long offsetYN = clap.verticalOffsetN();
        long offsetYD = clap.verticalOffsetD();
        if (widthD <= 0 || heightD <= 0 || offsetXD <= 0 || offsetYD <= 0 || widthN < 0 || heightN < 0
                || widthN % widthD != 0 || heightN % heightD != 0) {
            return null;
        }
        var clapWidth = widthN / widthD;
        var clapHeight = heightN / heightD;
        // ISO/IEC 14496-12 12.1.4.1: the aperture is centered on horizOff + (width - 1) / 2, so its
        // left edge is horizOff + (width - clapWidth) / 2, computed over a common denominator.
        var left = leftEdge(width, offsetXN, offsetXD, clapWidth);
        var top = leftEdge(height, offsetYN, offsetYD, clapHeight);
        if (left == Long.MIN_VALUE || top == Long.MIN_VALUE || clapWidth == 0 || clapHeight == 0
                || left < 0 || top < 0 || left + clapWidth > width || top + clapHeight > height) {
            return null;
        }
        return new int[]{(int) left, (int) top, (int) clapWidth, (int) clapHeight};
    }

    /**
     * Computes the first sample of an aperture of a given size centered at an offset from the
     * center of an image dimension.
     *
     * @param size the image dimension
     * @param offsetN the numerator of the center offset
     * @param offsetD the denominator of the center offset, positive
     * @param aperture the aperture size
     * @return the first sample, or {@link Long#MIN_VALUE} when it is not a whole sample
     */
    private static long leftEdge(long size, long offsetN, long offsetD, long aperture) {
        var numerator = (size - aperture) * offsetD + 2 * offsetN;
        var denominator = 2 * offsetD;
        if (numerator % denominator != 0) {
            return Long.MIN_VALUE;
        }
        return numerator / denominator;
    }
}
