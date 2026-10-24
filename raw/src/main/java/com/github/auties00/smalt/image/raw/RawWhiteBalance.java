package com.github.auties00.smalt.image.raw;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        return new Multipliers(List.of(red, green, blue));
    }

    /**
     * Returns the white balance that multiplies the camera channels by given factors, one per color
     * plane: red, green and blue, or the four colors of a four-color sensor in the order of the file's
     * color planes (as {@link RawImage#cameraNeutral()} orders them).
     *
     * @param factors the three or four multipliers, positive
     * @return the white balance
     * @throws IllegalArgumentException if there are not three or four factors, or one is not positive
     * and finite
     * @see Multipliers
     */
    static Multipliers multipliers(double... factors) {
        Objects.requireNonNull(factors, "factors");
        var list = new ArrayList<Double>(factors.length);
        for (var factor : factors) {
            list.add(factor);
        }
        return new Multipliers(list);
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
     * The white balance that multiplies the camera channels by given factors, one per color plane;
     * only their ratios matter.
     *
     * <p>Three factors balance a sensor of red, green and blue, four a four-color sensor (in the order
     * of the file's color planes). Decoding a file with a number of factors other than its sensor's
     * colors throws {@link com.github.auties00.smalt.MediaException.Unsupported}.
     *
     * @param factors the three or four multipliers, each positive; copied, unmodifiable
     */
    record Multipliers(List<Double> factors) implements RawWhiteBalance {
        /**
         * Validates and copies the multipliers.
         *
         * @throws NullPointerException if a factor is {@code null}
         * @throws IllegalArgumentException if there are not three or four factors, or one is not
         * positive and finite
         */
        public Multipliers {
            factors = List.copyOf(factors);
            if (factors.size() != 3 && factors.size() != 4) {
                throw new IllegalArgumentException("white balance multipliers are three or four, not " + factors.size());
            }
            for (var value : factors) {
                if (!(value > 0) || !Double.isFinite(value)) {
                    throw new IllegalArgumentException("invalid white balance multiplier " + value);
                }
            }
        }

        /**
         * {@return the first multiplier: red, or the first color plane of a four-color sensor}
         */
        public double red() {
            return factors.get(0);
        }

        /**
         * {@return the second multiplier: green, or the second color plane of a four-color sensor}
         */
        public double green() {
            return factors.get(1);
        }

        /**
         * {@return the third multiplier: blue, or the third color plane of a four-color sensor}
         */
        public double blue() {
            return factors.get(2);
        }
    }
}
