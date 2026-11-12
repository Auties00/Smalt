package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.image.Chromaticity;

import java.util.Objects;

/**
 * Holds the white point and primary chromaticities a PNG {@code cHRM} chunk records (PNG 3rd edition 11.3.2.1).
 *
 * <p>The chunk stores each coordinate times 100000, rounded to the nearest integer, so a coordinate must be at
 * most {@code 21474.83647}. Instances are immutable and thread-safe.
 *
 * @param white the white point
 * @param red the red primary
 * @param green the green primary
 * @param blue the blue primary
 */
public record PngChromaticities(Chromaticity white, Chromaticity red, Chromaticity green, Chromaticity blue) {
    /**
     * The largest coordinate the chunk can store.
     */
    private static final double MAX_COORDINATE = 0x7FFF_FFFF / 100000.0;

    /**
     * Validates the coordinates.
     *
     * @throws IllegalArgumentException if a coordinate exceeds {@code 21474.83647}
     */
    public PngChromaticities {
        Objects.requireNonNull(white, "white");
        Objects.requireNonNull(red, "red");
        Objects.requireNonNull(green, "green");
        Objects.requireNonNull(blue, "blue");
        for (var chromaticity : new Chromaticity[]{white, red, green, blue}) {
            if (Math.round(chromaticity.x() * 100000) > 0x7FFF_FFFFL || Math.round(chromaticity.y() * 100000) > 0x7FFF_FFFFL) {
                throw new IllegalArgumentException("chromaticity " + chromaticity + " exceeds " + MAX_COORDINATE);
            }
        }
    }

    /**
     * Returns the sRGB chromaticities (ITU-R BT.709 primaries, D65 white), the values PNG 3rd edition 11.3.2.5
     * prescribes for a {@code cHRM} chunk that accompanies {@code sRGB}.
     *
     * @return the chromaticities
     */
    public static PngChromaticities ofSrgb() {
        return new PngChromaticities(new Chromaticity(0.3127, 0.329), new Chromaticity(0.64, 0.33),
                new Chromaticity(0.3, 0.6), new Chromaticity(0.15, 0.06));
    }
}
