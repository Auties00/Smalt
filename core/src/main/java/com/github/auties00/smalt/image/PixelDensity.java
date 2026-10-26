package com.github.auties00.smalt.image;

import java.util.Objects;

/**
 * The physical size of the pixels of an image, as pixels per unit of length along each axis, or only their
 * aspect ratio.
 *
 * <p>This is the density of a JFIF header, the PNG {@code pHYs} chunk, the TIFF {@code XResolution},
 * {@code YResolution} and {@code ResolutionUnit} fields, the BMP pixels per meter and the JPEG 2000 resolution
 * box. With {@link Unit#ASPECT_RATIO} the two values carry no unit and only their ratio is meaningful. A
 * decoder reports the unit the file declares, so the values round trip through a format that stores that unit;
 * {@link #to(Unit)} converts between the physical units.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param unit the unit of the two values
 * @param horizontal the pixels per unit along the x axis, positive and finite
 * @param vertical the pixels per unit along the y axis, positive and finite
 */
public record PixelDensity(Unit unit, double horizontal, double vertical) {
    /**
     * Validates the density.
     *
     * @throws IllegalArgumentException if a value is not positive and finite
     */
    public PixelDensity {
        Objects.requireNonNull(unit, "unit");
        if (!(horizontal > 0 && horizontal < Double.POSITIVE_INFINITY)
                || !(vertical > 0 && vertical < Double.POSITIVE_INFINITY)) {
            throw new IllegalArgumentException("invalid pixel density " + horizontal + "x" + vertical);
        }
    }

    /**
     * Returns a pixel aspect ratio without a physical size.
     *
     * <p>The values count pixels per unit, so pixels twice as wide as they are tall are {@code ofAspectRatio(1, 2)}.
     *
     * @param horizontal the relative pixels per unit along the x axis, positive and finite
     * @param vertical the relative pixels per unit along the y axis, positive and finite
     * @return the density, in {@link Unit#ASPECT_RATIO}
     * @throws IllegalArgumentException if a value is not positive and finite
     */
    public static PixelDensity ofAspectRatio(double horizontal, double vertical) {
        return new PixelDensity(Unit.ASPECT_RATIO, horizontal, vertical);
    }

    /**
     * Returns square pixels at a density in dots per inch.
     *
     * @param dotsPerInch the pixels per inch along both axes, positive and finite
     * @return the density, in {@link Unit#DOTS_PER_INCH}
     * @throws IllegalArgumentException if the value is not positive and finite
     */
    public static PixelDensity ofDotsPerInch(double dotsPerInch) {
        return new PixelDensity(Unit.DOTS_PER_INCH, dotsPerInch, dotsPerInch);
    }

    /**
     * Returns a density in dots per inch, which may differ between the axes.
     *
     * @param horizontal the pixels per inch along the x axis, positive and finite
     * @param vertical the pixels per inch along the y axis, positive and finite
     * @return the density, in {@link Unit#DOTS_PER_INCH}
     * @throws IllegalArgumentException if a value is not positive and finite
     */
    public static PixelDensity ofDotsPerInch(double horizontal, double vertical) {
        return new PixelDensity(Unit.DOTS_PER_INCH, horizontal, vertical);
    }

    /**
     * Returns a density in dots per centimeter.
     *
     * @param horizontal the pixels per centimeter along the x axis, positive and finite
     * @param vertical the pixels per centimeter along the y axis, positive and finite
     * @return the density, in {@link Unit#DOTS_PER_CENTIMETER}
     * @throws IllegalArgumentException if a value is not positive and finite
     */
    public static PixelDensity ofDotsPerCentimeter(double horizontal, double vertical) {
        return new PixelDensity(Unit.DOTS_PER_CENTIMETER, horizontal, vertical);
    }

    /**
     * Returns a density in dots per meter.
     *
     * @param horizontal the pixels per meter along the x axis, positive and finite
     * @param vertical the pixels per meter along the y axis, positive and finite
     * @return the density, in {@link Unit#DOTS_PER_METER}
     * @throws IllegalArgumentException if a value is not positive and finite
     */
    public static PixelDensity ofDotsPerMeter(double horizontal, double vertical) {
        return new PixelDensity(Unit.DOTS_PER_METER, horizontal, vertical);
    }

    /**
     * {@return whether the values have a physical unit, that is whether the unit is not
     * {@link Unit#ASPECT_RATIO}}
     */
    public boolean isPhysical() {
        return unit != Unit.ASPECT_RATIO;
    }

    /**
     * {@return the width of a pixel divided by its height, {@code vertical / horizontal}}
     */
    public double pixelAspectRatio() {
        return vertical / horizontal;
    }

    /**
     * Returns this density in another unit.
     *
     * <p>A physical density converts to every physical unit, an inch being exactly 2.54 centimeters, and to
     * {@link Unit#ASPECT_RATIO} by keeping its values and dropping the unit. An aspect ratio converts only to
     * itself. Converting to the current unit returns this instance.
     *
     * @param target the unit of the result
     * @return the density in {@code target}
     * @throws IllegalArgumentException if this density is an aspect ratio and {@code target} is a physical unit
     */
    public PixelDensity to(Unit target) {
        Objects.requireNonNull(target, "target");
        if (target == unit) {
            return this;
        }
        if (target == Unit.ASPECT_RATIO) {
            return new PixelDensity(Unit.ASPECT_RATIO, horizontal, vertical);
        }
        if (unit == Unit.ASPECT_RATIO) {
            throw new IllegalArgumentException("an aspect ratio has no physical size");
        }
        double from = unit.micrometers();
        double into = target.micrometers();
        return new PixelDensity(target, horizontal * into / from, vertical * into / from);
    }

    /**
     * The unit of the values of a {@link PixelDensity}.
     */
    public enum Unit {
        /**
         * No unit: the values give only the pixel aspect ratio.
         */
        ASPECT_RATIO,

        /**
         * Pixels per inch.
         */
        DOTS_PER_INCH,

        /**
         * Pixels per centimeter.
         */
        DOTS_PER_CENTIMETER,

        /**
         * Pixels per meter.
         */
        DOTS_PER_METER;

        /**
         * {@return the micrometers in one unit, for a physical unit}
         */
        private int micrometers() {
            return switch (this) {
                case DOTS_PER_INCH -> 25_400;
                case DOTS_PER_CENTIMETER -> 10_000;
                case DOTS_PER_METER -> 1_000_000;
                case ASPECT_RATIO -> throw new IllegalStateException("no physical unit");
            };
        }
    }
}
