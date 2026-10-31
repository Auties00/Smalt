package com.github.auties00.smalt.image.raw;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A white balance a raw image is rendered with: the one recorded with the shot, a correlated color
 * temperature and tint, explicit camera channel multipliers, or one estimated from the image.
 *
 * <p>Instances are immutable and thread-safe.
 */
public sealed interface RawWhiteBalance permits RawWhiteBalance.AsShot, RawWhiteBalance.Temperature,
        RawWhiteBalance.Multipliers, RawWhiteBalance.Automatic {
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
     * Returns the white balance of a light of a correlated color temperature, offset from the locus by a tint.
     *
     * @param kelvin the temperature, from 2000 to 25000 kelvin
     * @param tint the tint, from -150 to 150; see {@link Temperature}
     * @return the white balance
     * @throws IllegalArgumentException if the temperature or the tint is out of range
     * @see Temperature
     */
    static Temperature temperature(double kelvin, double tint) {
        return new Temperature(kelvin, tint);
    }

    /**
     * Returns the white balance estimated from the image by the gray world method.
     *
     * @return the automatic white balance
     * @see Automatic
     */
    static Automatic automatic() {
        return new Automatic(Automatic.Method.GRAY_WORLD);
    }

    /**
     * Returns the white balance estimated from the image by a method.
     *
     * @param method the estimation method
     * @return the automatic white balance
     * @see Automatic
     */
    static Automatic automatic(Automatic.Method method) {
        return new Automatic(method);
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
     * The white balance of a light of a correlated color temperature and tint.
     *
     * <p>A tint of 0 is the light on the CIE daylight locus from 4000 K, on the Planckian locus below. Another
     * tint moves the light's chromaticity, in the CIE 1960 UCS {@code (u, v)} diagram, from that point along
     * the normal to the Planckian locus at the temperature, by {@code tint / 3000}: positive values describe a
     * greener light (toward larger {@code v}), which renders the image more magenta, as the tint controls of raw
     * converters do, negative values a more magenta light.
     * {@snippet :
     * (u, v) = (u0, v0) + tint / 3000 * (nu, nv)   // (nu, nv) unit normal to the Planckian locus at T, nv > 0
     * }
     *
     * <p>Rendering it needs the file's color matrices (or the generic fallback); a file that only embeds a
     * camera to sRGB matrix renders it as shot.
     *
     * @param kelvin the temperature, from 2000 to 25000 kelvin
     * @param tint the tint, from -150 to 150
     */
    record Temperature(double kelvin, double tint) implements RawWhiteBalance {
        /**
         * Validates the temperature and the tint.
         *
         * @throws IllegalArgumentException if the temperature or the tint is out of range
         */
        public Temperature {
            if (!(kelvin >= 2000 && kelvin <= 25000)) {
                throw new IllegalArgumentException("temperature " + kelvin + " K is outside 2000..25000");
            }
            if (!(tint >= -150 && tint <= 150)) {
                throw new IllegalArgumentException("tint " + tint + " is outside -150..150");
            }
        }

        /**
         * Constructs the white balance of a light on the locus, of tint 0.
         *
         * @param kelvin the temperature, from 2000 to 25000 kelvin
         * @throws IllegalArgumentException if the temperature is out of range
         */
        public Temperature(double kelvin) {
            this(kelvin, 0);
        }
    }

    /**
     * The white balance estimated from the image: camera channel multipliers that make a reference of the
     * image neutral.
     *
     * <p>The estimate is computed on the camera values of the default crop (the whole image area of a file
     * without one), before demosaicing, from the blocks of one repeat of the color filter array (2x2 photosites
     * for a Bayer array, 6x6 for an X-Trans array; the pixels of a linear image) none of whose values is at or
     * above 98% of the white level or below 0.1% of it; an image without such blocks renders as shot. The
     * estimate depends only on the image and the method: rendering, {@link RawDecoder#decodeRaw} and DNG
     * conversion find the same one.
     *
     * @param method the estimation method
     */
    record Automatic(Method method) implements RawWhiteBalance {
        /**
         * Validates the method.
         *
         * @throws NullPointerException if the method is {@code null}
         */
        public Automatic {
            Objects.requireNonNull(method, "method");
        }

        /**
         * A method of estimating the white balance from the image.
         */
        public enum Method {
            /**
             * Gray world: the mean of each color over the blocks is taken as the neutral, which assumes that the
             * scene averages to gray.
             */
            GRAY_WORLD,

            /**
             * White patch: the mean of each color over the brightest 1% of the blocks (by the sum of their
             * colors) is taken as the neutral, which assumes that the brightest unclipped areas are white.
             */
            WHITE_PATCH
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
