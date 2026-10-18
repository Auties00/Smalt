package com.github.auties00.smalt.image.raw;

/**
 * A white balance a raw image is rendered with: the one recorded with the shot, a correlated color
 * temperature, or explicit camera channel multipliers.
 *
 * <p>Instances are immutable and thread-safe.
 */
public sealed interface RawWhiteBalance permits RawWhiteBalance.AsShot, RawWhiteBalance.Temperature,
        RawWhiteBalance.Multipliers {
    /**
     * Returns the white balance recorded with the shot.
     *
     * @return the as-shot white balance
     */
    static AsShot asShot() {
        return new AsShot();
    }

    /**
     * Returns the white balance of daylight at 5500 K.
     *
     * @return the daylight white balance
     */
    static Temperature daylight() {
        return new Temperature(5500);
    }

    /**
     * Returns the white balance of a light of a correlated color temperature.
     *
     * @param kelvin the temperature, from 2000 to 25000 kelvin
     * @return the white balance
     * @throws IllegalArgumentException if the temperature is out of range
     * @see Temperature
     */
    static Temperature temperature(double kelvin) {
        return new Temperature(kelvin);
    }

    /**
     * Returns the white balance that multiplies the red, green and blue camera channels by given factors.
     *
     * @param red the red multiplier, positive
     * @param green the green multiplier, positive
     * @param blue the blue multiplier, positive
     * @return the white balance
     * @throws IllegalArgumentException if a multiplier is not positive and finite
     * @see Multipliers
     */
    static Multipliers multipliers(double red, double green, double blue) {
        return new Multipliers(red, green, blue);
    }

    /**
     * The white balance recorded with the shot: the camera's as-shot neutral, or, for a file that records
     * none, a D65 white.
     */
    record AsShot() implements RawWhiteBalance {
    }

    /**
     * The white balance of a light of a correlated color temperature: on the CIE daylight locus from
     * 4000 K, on the Planckian locus below.
     *
     * <p>Rendering it needs the file's color matrices (or the generic fallback); a file that only embeds a
     * camera to sRGB matrix renders it as shot.
     *
     * @param kelvin the temperature, from 2000 to 25000 kelvin
     */
    record Temperature(double kelvin) implements RawWhiteBalance {
        /**
         * Validates the temperature.
         *
         * @throws IllegalArgumentException if the temperature is out of range
         */
        public Temperature {
            if (!(kelvin >= 2000 && kelvin <= 25000)) {
                throw new IllegalArgumentException("temperature " + kelvin + " K is outside 2000..25000");
            }
        }
    }

    /**
     * The white balance that multiplies the red, green and blue camera channels by given factors; only
     * their ratios matter.
     *
     * <p>A file of a four-color sensor cannot be balanced this way: decoding it with multipliers throws
     * {@link com.github.auties00.smalt.MediaException.Unsupported}.
     *
     * @param red the red multiplier, positive
     * @param green the green multiplier, positive
     * @param blue the blue multiplier, positive
     */
    record Multipliers(double red, double green, double blue) implements RawWhiteBalance {
        /**
         * Validates the multipliers.
         *
         * @throws IllegalArgumentException if a multiplier is not positive and finite
         */
        public Multipliers {
            for (var value : new double[]{red, green, blue}) {
                if (!(value > 0) || !Double.isFinite(value)) {
                    throw new IllegalArgumentException("invalid white balance multiplier " + value);
                }
            }
        }
    }
}
