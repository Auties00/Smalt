package com.github.auties00.smalt.audio.process;

import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.Speaker;

/**
 * Evaluates the real spherical harmonics of ambisonics and converts between their normalizations.
 */
final class SphericalHarmonics {
    /**
     * The factor that turns each Furse-Malham component into its SN3D value, indexed by ACN; every factor is the
     * largest value of the SN3D harmonic over the sphere, except {@code W}, whose factor is {@code sqrt(2)}.
     */
    private static final double[] FUMA_TO_SN3D = {
            Math.sqrt(2),
            1, 1, 1,
            Math.sqrt(3) / 2, Math.sqrt(3) / 2, 1, Math.sqrt(3) / 2, Math.sqrt(3) / 2,
            Math.sqrt(5.0 / 8), Math.sqrt(5) / 3, Math.sqrt(32.0 / 45), 1, Math.sqrt(32.0 / 45), Math.sqrt(5) / 3,
            Math.sqrt(5.0 / 8)
    };

    private SphericalHarmonics() {
    }

    /**
     * Returns the factor that turns a component in a normalization into its SN3D value.
     *
     * @param normalization the normalization of the component
     * @param component the ACN, at most 15 for {@link Ambisonics.Normalization#FUMA}
     * @return the factor, positive
     */
    static double toSn3d(Ambisonics.Normalization normalization, int component) {
        return switch (normalization) {
            case SN3D -> 1;
            case N3D -> 1 / Math.sqrt(2 * Ambisonics.degree(component) + 1);
            case FUMA -> FUMA_TO_SN3D[component];
        };
    }

    /**
     * Evaluates every SN3D spherical harmonic up to an order in one direction.
     *
     * @param order the highest degree, from 0 to {@link Ambisonics#MAX_ORDER}
     * @param azimuth the azimuth in radians, counterclockwise from the front
     * @param elevation the elevation in radians, positive above the horizontal plane
     * @return the {@code (order + 1)^2} values, indexed by ACN
     */
    static double[] sn3d(int order, double azimuth, double elevation) {
        var x = StrictMath.sin(elevation);
        var c = StrictMath.cos(elevation);
        var values = new double[(order + 1) * (order + 1)];
        for (var m = 0; m <= order; m++) {
            // the associated Legendre functions P(l, m, x) without the Condon-Shortley phase, by the recurrence
            // in l from P(m, m, x) = (2m - 1)!! * cos(E)^m
            var pmm = 1.0;
            for (var k = 1; k <= m; k++) {
                pmm *= (2 * k - 1) * c;
            }
            var previous = 0.0;
            var current = pmm;
            for (var l = m; l <= order; l++) {
                if (l == m + 1) {
                    previous = current;
                    current = x * (2 * m + 1) * pmm;
                } else if (l > m + 1) {
                    var next = ((2 * l - 1) * x * current - (l + m - 1) * previous) / (l - m);
                    previous = current;
                    current = next;
                }
                var ratio = 1.0;
                for (var k = l - m + 1; k <= l + m; k++) {
                    ratio /= k;
                }
                var norm = Math.sqrt((m == 0 ? 1 : 2) * ratio) * current;
                var center = l * (l + 1);
                if (m == 0) {
                    values[center] = norm;
                } else {
                    values[center + m] = norm * StrictMath.cos(m * azimuth);
                    values[center - m] = norm * StrictMath.sin(m * azimuth);
                }
            }
        }
        return values;
    }

    /**
     * Returns the nominal direction of a speaker.
     *
     * @param speaker the speaker to place
     * @return the azimuth and elevation in degrees, or {@code null} for the low-frequency channels and
     * {@link Speaker#UNKNOWN}
     */
    static double[] direction(Speaker speaker) {
        return switch (speaker) {
            case FRONT_LEFT -> new double[]{30, 0};
            case FRONT_RIGHT -> new double[]{-30, 0};
            case FRONT_CENTER -> new double[]{0, 0};
            case BACK_LEFT -> new double[]{110, 0};
            case BACK_RIGHT -> new double[]{-110, 0};
            case FRONT_LEFT_OF_CENTER -> new double[]{15, 0};
            case FRONT_RIGHT_OF_CENTER -> new double[]{-15, 0};
            case BACK_CENTER -> new double[]{180, 0};
            case SIDE_LEFT, SURROUND_DIRECT_LEFT -> new double[]{90, 0};
            case SIDE_RIGHT, SURROUND_DIRECT_RIGHT -> new double[]{-90, 0};
            case TOP_CENTER -> new double[]{0, 90};
            case TOP_FRONT_LEFT -> new double[]{30, 45};
            case TOP_FRONT_CENTER -> new double[]{0, 45};
            case TOP_FRONT_RIGHT -> new double[]{-30, 45};
            case TOP_BACK_LEFT -> new double[]{110, 45};
            case TOP_BACK_CENTER -> new double[]{180, 45};
            case TOP_BACK_RIGHT -> new double[]{-110, 45};
            case WIDE_LEFT -> new double[]{60, 0};
            case WIDE_RIGHT -> new double[]{-60, 0};
            case TOP_SIDE_LEFT -> new double[]{90, 45};
            case TOP_SIDE_RIGHT -> new double[]{-90, 45};
            case BOTTOM_FRONT_CENTER -> new double[]{0, -30};
            case BOTTOM_FRONT_LEFT -> new double[]{45, -30};
            case BOTTOM_FRONT_RIGHT -> new double[]{-45, -30};
            case LOW_FREQUENCY, LOW_FREQUENCY_2, UNKNOWN -> null;
        };
    }
}
