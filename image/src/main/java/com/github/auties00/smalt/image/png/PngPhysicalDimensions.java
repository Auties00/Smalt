package com.github.auties00.smalt.image.png;

import java.util.Objects;

/**
 * Holds the intended pixel size or aspect ratio a PNG {@code pHYs} chunk records (PNG 3rd edition 11.3.4.3).
 *
 * <p>With {@link Unit#METER} the two values are pixels per meter; with {@link Unit#UNKNOWN} only their ratio
 * is meaningful, as the pixel aspect ratio. Instances are immutable and thread-safe.
 *
 * @param pixelsPerUnitX the pixels per unit along the x axis, from 1 to {@code 2^31 - 1}
 * @param pixelsPerUnitY the pixels per unit along the y axis, from 1 to {@code 2^31 - 1}
 * @param unit the unit of the two values
 */
public record PngPhysicalDimensions(int pixelsPerUnitX, int pixelsPerUnitY, Unit unit) {
    /**
     * The meters in an inch.
     */
    private static final double METERS_PER_INCH = 0.0254;

    /**
     * Validates the values.
     *
     * @throws IllegalArgumentException if a value is not positive
     */
    public PngPhysicalDimensions {
        Objects.requireNonNull(unit, "unit");
        if (pixelsPerUnitX <= 0 || pixelsPerUnitY <= 0) {
            throw new IllegalArgumentException("invalid pixel dimensions " + pixelsPerUnitX + "x" + pixelsPerUnitY);
        }
    }

    /**
     * Returns square pixels at a resolution in dots per inch, rounded to whole pixels per meter.
     *
     * <p>For example 72 dpi gives 2835 pixels per meter and 300 dpi gives 11811.
     *
     * @param dotsPerInch the resolution, positive and finite
     * @return the dimensions, in {@link Unit#METER}
     * @throws IllegalArgumentException if the resolution is not positive and finite, or rounds to no pixel or to
     * more than {@code 2^31 - 1} pixels per meter
     */
    public static PngPhysicalDimensions ofDotsPerInch(double dotsPerInch) {
        var perMeter = Math.round(dotsPerInch / METERS_PER_INCH);
        if (!(dotsPerInch > 0 && Double.isFinite(dotsPerInch)) || perMeter < 1 || perMeter > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid resolution " + dotsPerInch + " dpi");
        }
        return new PngPhysicalDimensions((int) perMeter, (int) perMeter, Unit.METER);
    }

    /**
     * Returns a pixel aspect ratio without a physical size, for pixels {@code width / height} times as wide as
     * they are tall.
     *
     * <p>Since the chunk counts pixels per unit, the result holds {@code height} pixels per unit along x and
     * {@code width} along y.
     *
     * @param width the relative width of a pixel, positive
     * @param height the relative height of a pixel, positive
     * @return the dimensions, in {@link Unit#UNKNOWN}
     * @throws IllegalArgumentException if a value is not positive
     */
    public static PngPhysicalDimensions ofAspectRatio(int width, int height) {
        return new PngPhysicalDimensions(height, width, Unit.UNKNOWN);
    }

    /**
     * Enumerates the units of a {@code pHYs} chunk.
     */
    public enum Unit {
        /**
         * The unit is unknown and the values define only the pixel aspect ratio, unit specifier 0.
         */
        UNKNOWN,

        /**
         * The values are pixels per meter, unit specifier 1.
         */
        METER
    }
}
