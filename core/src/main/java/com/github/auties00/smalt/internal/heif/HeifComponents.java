package com.github.auties00.smalt.internal.heif;

import java.util.List;

/**
 * Holds an image item decoded into separate planes of unsigned samples, one per component, before
 * they are assembled into a picture by {@link HeifComponentPictures}.
 *
 * <p>Component types use the codes of the ISO/IEC 23001-17 component definition box
 * ({@code cmpd}), with {@link #PREMULTIPLIED_ALPHA} added for alpha that the color is
 * premultiplied by. Planes of up to 16 bits hold their samples in {@link Plane#samples()}, deeper
 * planes (up to 31 bits) in {@link Plane#values()}. The record holds its arrays itself, not copies.
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
     * The cyan ink component: zero is no ink.
     */
    public static final int CYAN = 13;

    /**
     * The magenta ink component: zero is no ink.
     */
    public static final int MAGENTA = 14;

    /**
     * The yellow ink component: zero is no ink.
     */
    public static final int YELLOW = 15;

    /**
     * The black ink component (key): zero is no ink.
     */
    public static final int BLACK = 16;

    /**
     * The deepest plane, in bits.
     */
    public static final int MAX_BIT_DEPTH = 31;

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
     * @param bitDepth the bits per sample, from 1 to {@link #MAX_BIT_DEPTH}
     * @param samples the unsigned samples of a plane of up to 16 bits, row-major without padding, or
     * {@code null} for a deeper plane
     * @param values the unsigned samples of a plane of more than 16 bits, row-major without padding,
     * or {@code null} for a plane of up to 16 bits
     */
    public record Plane(int type, int width, int height, int subsamplingX, int subsamplingY, int bitDepth,
                        short[] samples, int[] values) {
        /**
         * Constructs a plane of up to 16 bits.
         *
         * @param type the component type, one of the constants of {@link HeifComponents}
         * @param width the plane width in samples
         * @param height the plane height in samples
         * @param subsamplingX the horizontal subsampling factor, 1 for a full-resolution plane
         * @param subsamplingY the vertical subsampling factor, 1 for a full-resolution plane
         * @param bitDepth the bits per sample, from 1 to 16
         * @param samples the unsigned samples, row-major without padding
         */
        public Plane(int type, int width, int height, int subsamplingX, int subsamplingY, int bitDepth,
                     short[] samples) {
            this(type, width, height, subsamplingX, subsamplingY, bitDepth, samples, null);
        }

        /**
         * {@return whether the plane is deeper than 16 bits and holds its samples in {@link #values()}}
         */
        public boolean deep() {
            return values != null;
        }

        /**
         * {@return the number of samples the plane holds}
         */
        public int length() {
            return values != null ? values.length : samples.length;
        }

        /**
         * {@return one unsigned sample}
         *
         * @param index the row-major index
         */
        public int sample(int index) {
            return values != null ? values[index] : samples[index] & 0xFFFF;
        }
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
     * {@return whether the components hold ink components or a plane deeper than 16 bits, which only a
     * {@link com.github.auties00.smalt.image.ChannelPicture} represents}
     */
    public boolean needsChannels() {
        for (var plane : planes) {
            if (plane.deep() || plane.type() >= CYAN && plane.type() <= BLACK) {
                return true;
            }
        }
        return false;
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
