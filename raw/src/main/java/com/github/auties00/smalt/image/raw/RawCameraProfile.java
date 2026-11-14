package com.github.auties00.smalt.image.raw;

import java.util.Objects;

/**
 * Describes a camera profile of a DNG file: its name, the group that relates it to profiles of the same look made
 * for another output range, and the output range it is made for ({@code ProfileDynamicRange}, DNG 1.7).
 *
 * <p>A high-dynamic-range profile renders values above reference white, which a
 * {@linkplain RawDecoderOptions#floatingPoint() floating-point} output keeps. Instances are immutable and
 * thread-safe.
 *
 * @param name the profile's name ({@code ProfileName}), or an empty string for an unnamed profile; the name
 * {@link RawDecoderOptions.Builder#cameraProfile(String)} selects it by
 * @param group the profile's group name ({@code ProfileGroupName}), or an empty string for none
 * @param highDynamicRange whether the profile is made for high-dynamic-range output
 * @param maxOutputHint the largest linear output value the profile intends, relative to reference white
 * ({@code HintMaxOutputValue}), or 0 when the file gives no hint
 */
public record RawCameraProfile(String name, String group, boolean highDynamicRange, double maxOutputHint) {
    /**
     * Validates the description.
     *
     * @throws IllegalArgumentException if the hint is negative or not finite
     */
    public RawCameraProfile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(group, "group");
        if (!(maxOutputHint >= 0) || !Double.isFinite(maxOutputHint)) {
            throw new IllegalArgumentException("invalid output hint " + maxOutputHint);
        }
    }
}
