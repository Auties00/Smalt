package com.github.auties00.smalt.capture;

import com.github.auties00.smalt.track.Rational;

import java.util.Objects;

/**
 * A resolution and frame rate a camera can capture at.
 *
 * <p>The pixel format the driver delivers is chosen by the capture backend and hidden from the
 * caller: every session delivers planar 4:2:0 pictures. Instances are immutable and thread-safe.
 *
 * @param width the width in pixels, positive
 * @param height the height in pixels, positive
 * @param frameRate the frames per second, positive
 */
public record CaptureMode(int width, int height, Rational frameRate) {
    /**
     * Validates the mode.
     *
     * @throws IllegalArgumentException if a dimension or the frame rate is not positive
     */
    public CaptureMode {
        Objects.requireNonNull(frameRate, "frameRate");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid capture size " + width + "x" + height);
        }
        if (frameRate.numerator() <= 0 || frameRate.denominator() <= 0) {
            throw new IllegalArgumentException("invalid frame rate " + frameRate);
        }
    }

    /**
     * Returns a mode with an integer frame rate.
     *
     * @param width the width in pixels, positive
     * @param height the height in pixels, positive
     * @param fps the frames per second, positive
     * @return the mode
     * @throws IllegalArgumentException if a value is not positive
     */
    public static CaptureMode of(int width, int height, int fps) {
        return new CaptureMode(width, height, new Rational(fps, 1));
    }
}
