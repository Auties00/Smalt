package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaLimits;

/**
 * Re-encodes a baseline JPEG as a progressive one with libjpeg's default script, for benchmarks.
 */
public final class ProgressiveInputs {
    private ProgressiveInputs() {
        throw new AssertionError();
    }

    public static byte[] progressive(byte[] baseline) {
        var coefficients = new JpegReader(baseline, 0, baseline.length, MediaLimits.DEFAULT).decodeCoefficients();
        var components = coefficients.frame().components.length;
        return TestJpegWriter.of(coefficients).progressive(TestJpegWriter.simpleProgression(components, components == 3));
    }
}
