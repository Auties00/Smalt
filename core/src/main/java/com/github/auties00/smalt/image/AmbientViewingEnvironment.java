package com.github.auties00.smalt.image;

import java.util.Objects;

/**
 * Describes the nominal ambient viewing environment content was mastered for: the illuminance around the display and
 * the chromaticity of the ambient light (Rec. ITU-T H.274 clause 8.13).
 *
 * <p>This is the metadata of the ambient viewing environment SEI message of H.265 and H.266 and of the MP4
 * {@code amve} box. A receiver can use it to adapt the content to a viewing environment that differs from the one
 * the content was made for. For example, the reference conditions of Rec. ITU-R BT.2035 are an illuminance of 10 lux
 * with a D65 chromaticity of (0.3127, 0.3290).
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param illuminance the illuminance of the environment, in lux; positive and finite (H.274 stores it in units of
 *                    0.0001 lux)
 * @param ambientLight the CIE 1931 chromaticity of the ambient light (H.274 stores each coordinate in units of
 *                     0.00002, from 0 to 1)
 */
public record AmbientViewingEnvironment(double illuminance, Chromaticity ambientLight) {
    /**
     * Validates the description.
     *
     * @throws NullPointerException     if {@code ambientLight} is {@code null}
     * @throws IllegalArgumentException if the illuminance is not positive or not finite
     */
    public AmbientViewingEnvironment {
        Objects.requireNonNull(ambientLight, "ambientLight");
        if (!(illuminance > 0 && Double.isFinite(illuminance))) {
            throw new IllegalArgumentException("invalid ambient illuminance " + illuminance);
        }
    }
}
