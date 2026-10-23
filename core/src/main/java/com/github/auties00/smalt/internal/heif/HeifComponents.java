package com.github.auties00.smalt.internal.heif;

import java.util.List;

/**
 * Holds an image item decoded into separate planes of unsigned samples, one per component, before
 * they are assembled into a picture by {@link HeifComponentPictures}.
 *
 * <p>Component types use the codes of the ISO/IEC 23001-17 component definition box
 * ({@code cmpd}), with {@link #PREMULTIPLIED_ALPHA} added for alpha that the color is
 * premultiplied by. The record holds its arrays itself, not copies.
 *
 * @param width the image width in pixels
 * @param height the image height in pixels
 * @param planes the planes, at most one per type
 */
public record HeifComponents(int width, int height, List<Plane> planes) {
    /**
     * A monochrome component.
     */
    public static final int MONOCHROME = 0;

    /**
     * The luma component of Y'CbCr.
     */
    public static final int Y = 1;

    /**
     * The blue-difference chroma component.
     */
    public static final int CB = 2;

    /**
     * The red-difference chroma component.
     */
    public static final int CR = 3;

    /**
     * The red component.
     */
    public static final int RED = 4;

    /**
     * The green component.
     */
    public static final int GREEN = 5;

    /**
     * The blue component.
     */
    public static final int BLUE = 6;

    /**
     * Straight alpha: zero is transparent, the largest value opaque.
     */
    public static final int ALPHA = 7;

    /**
     * Alpha the color components are premultiplied by.
     */
    public static final int PREMULTIPLIED_ALPHA = 0x10007;

    /**
     * Holds the samples of one component.
     *
     * @param type the component type, one of the constants of {@link HeifComponents}
     * @param width the plane width in samples
     * @param height the plane height in samples
     * @param subsamplingX the horizontal subsampling factor, 1 for a full-resolution plane
     * @param subsamplingY the vertical subsampling factor, 1 for a full-resolution plane
     * @param bitDepth the bits per sample, from 1 to 16
     * @param samples the unsigned samples, row-major without padding
     */
    public record Plane(int type, int width, int height, int subsamplingX, int subsamplingY, int bitDepth,
                        short[] samples) {
    }

    /**
     * Constructs the components, copying the plane list.
     *
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param planes the planes, at most one per type
     */
    public HeifComponents {
        planes = List.copyOf(planes);
    }

    /**
     * {@return the plane of a type, or {@code null} when there is none}
     *
     * @param type the component type
     */
    public Plane plane(int type) {
        for (var plane : planes) {
            if (plane.type() == type) {
                return plane;
            }
        }
        return null;
    }
}
