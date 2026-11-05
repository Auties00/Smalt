package com.github.auties00.smalt.image.internal.density;

import com.github.auties00.smalt.image.PixelDensity;

/**
 * Converts a {@link PixelDensity} into the integer terms of the fields that store it.
 *
 * <p>This class is thread-safe.
 */
public final class DensityTerms {
    /**
     * Prevents instantiation.
     */
    private DensityTerms() {
        throw new AssertionError();
    }

    /**
     * Returns the two integer values of a density in a unit, each rounded to the nearest integer.
     *
     * @param density a physical density
     * @param unit the unit of the field, a physical unit
     * @param limit the largest value the field holds
     * @return the horizontal and vertical values, from 1 to {@code limit}
     * @throws IllegalArgumentException if a value rounds to 0 or to more than {@code limit}
     */
    public static long[] rounded(PixelDensity density, PixelDensity.Unit unit, long limit) {
        var converted = density.to(unit);
        var x = Math.round(converted.horizontal());
        var y = Math.round(converted.vertical());
        if (x < 1 || y < 1 || x > limit || y > limit) {
            throw new IllegalArgumentException("pixel density " + converted.horizontal() + "x"
                    + converted.vertical() + " " + unit + " does not round to whole values from 1 to " + limit);
        }
        return new long[]{x, y};
    }

    /**
     * Returns two integers in the ratio of an aspect ratio: its values when both are integers up to
     * {@code limit}, otherwise the continued-fraction approximation of {@code horizontal / vertical} with terms
     * up to {@code limit}.
     *
     * @param density the density, whose unit is ignored
     * @param limit the largest value the field holds
     * @return the horizontal and vertical values, from 1 to {@code limit}
     * @throws IllegalArgumentException if the ratio is above {@code limit} or below {@code 1 / limit}
     */
    public static long[] ratio(PixelDensity density, long limit) {
        var x = density.horizontal();
        var y = density.vertical();
        if (x == Math.rint(x) && y == Math.rint(y) && x <= limit && y <= limit) {
            return new long[]{(long) x, (long) y};
        }
        var terms = fraction(x / y, limit);
        if (terms == null) {
            throw new IllegalArgumentException("pixel aspect ratio " + x + ":" + y
                    + " is not representable with values from 1 to " + limit);
        }
        return terms;
    }

    /**
     * Returns the last continued-fraction convergent of a value whose terms fit a limit.
     *
     * @param value the value, positive and finite
     * @param limit the largest term, at least 1
     * @return the numerator and the denominator, from 1 to {@code limit}, or {@code null} when the value is
     * below {@code 1 / limit} or above {@code limit}
     */
    public static long[] fraction(double value, long limit) {
        if (!(value >= 1.0 / limit) || value > limit) {
            return null;
        }
        long p0 = 0;
        long q0 = 1;
        long p1 = 1;
        long q1 = 0;
        long bestP = 0;
        long bestQ = 0;
        var x = value;
        for (var i = 0; i < 64; i++) {
            var a = (long) Math.floor(x);
            // p1 is at least 1 from the second term on, so a term above the limit ends the expansion before
            // the products can overflow
            if (a > limit) {
                break;
            }
            var p2 = a * p1 + p0;
            var q2 = a * q1 + q0;
            if (p2 > limit || q2 > limit) {
                break;
            }
            bestP = p2;
            bestQ = q2;
            var remainder = x - a;
            if ((double) p2 / q2 == value || remainder == 0) {
                break;
            }
            x = 1 / remainder;
            p0 = p1;
            q0 = q1;
            p1 = p2;
            q1 = q2;
        }
        // a value below 1 whose next convergent overflows keeps the smallest fraction rather than 0/1
        return bestP == 0 ? new long[]{1, limit} : new long[]{bestP, bestQ};
    }
}
