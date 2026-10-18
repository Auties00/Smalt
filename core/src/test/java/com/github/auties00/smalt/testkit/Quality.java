package com.github.auties00.smalt.testkit;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;

/**
 * Computes the objective quality metrics used to compare decoded or re-encoded media against a
 * reference: PSNR and maximum absolute difference for 8-bit and deeper samples, of arrays, strided
 * planes and whole pictures, and SNR for float audio. The PSNR is infinite for identical input;
 * tests whose thresholds were set against a capped PSNR apply their cap themselves.
 */
public final class Quality {
    private Quality() {
        throw new AssertionError();
    }

    /**
     * Returns the peak signal-to-noise ratio between two equally sized 8-bit sample arrays.
     *
     * @return the PSNR in decibels, or {@link Double#POSITIVE_INFINITY} for identical input
     */
    public static double psnr(byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            throw new IllegalArgumentException("length mismatch: " + expected.length + " vs " + actual.length);
        }
        double squared = 0;
        for (var i = 0; i < expected.length; i++) {
            var difference = (expected[i] & 0xFF) - (actual[i] & 0xFF);
            squared += (double) difference * difference;
        }
        if (squared == 0) {
            return Double.POSITIVE_INFINITY;
        }
        var mse = squared / expected.length;
        return 10 * Math.log10(255.0 * 255.0 / mse);
    }

    /**
     * Returns the peak signal-to-noise ratio of a mean squared error of 8-bit samples.
     *
     * @return the PSNR in decibels, or {@link Double#POSITIVE_INFINITY} for a zero error
     */
    public static double psnr(double mse) {
        return psnr(mse, 255);
    }

    /**
     * Returns the peak signal-to-noise ratio of a mean squared error of samples whose largest value
     * is {@code peak}.
     *
     * @return the PSNR in decibels, or {@link Double#POSITIVE_INFINITY} for a zero error
     */
    public static double psnr(double mse, double peak) {
        return mse == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(peak * peak / mse);
    }

    /**
     * Returns the peak signal-to-noise ratio between two rectangles of 8-bit samples in strided
     * planes, both starting at index 0.
     *
     * @return the PSNR in decibels, or {@link Double#POSITIVE_INFINITY} for identical input
     */
    public static double psnr(byte[] expected, int expectedStride, byte[] actual, int actualStride, int width,
                              int height) {
        var squared = squaredError(expected, expectedStride, actual, actualStride, width, height);
        return psnr(squared / ((double) width * height));
    }

    /**
     * Returns the peak signal-to-noise ratio between two equally sized arrays of unsigned 16-bit
     * samples whose largest value is {@code peak}, such as {@code 1023} for 10-bit samples.
     *
     * @return the PSNR in decibels, or {@link Double#POSITIVE_INFINITY} for identical input
     */
    public static double psnr(short[] expected, short[] actual, int peak) {
        if (expected.length != actual.length) {
            throw new IllegalArgumentException("length mismatch: " + expected.length + " vs " + actual.length);
        }
        double squared = 0;
        for (var i = 0; i < expected.length; i++) {
            var difference = (expected[i] & 0xFFFF) - (actual[i] & 0xFFFF);
            squared += (double) difference * difference;
        }
        return psnr(squared / expected.length, peak);
    }

    /**
     * Returns the peak signal-to-noise ratio between two pictures of the same type, size and
     * layout, over every sample of every plane (luma, chroma and alpha, or the packed components);
     * the peak is 255 for 8-bit pictures, {@code 2^bitDepth - 1} for planar deep pictures and
     * 65535 for packed 16-bit ones.
     *
     * @return the PSNR in decibels, or {@link Double#POSITIVE_INFINITY} for identical pictures
     */
    public static double psnr(Picture expected, Picture actual) {
        if (expected.width() != actual.width() || expected.height() != actual.height()
                || expected.getClass() != actual.getClass()) {
            throw new IllegalArgumentException("pictures differ in type or size");
        }
        var width = expected.width();
        var height = expected.height();
        return switch (expected) {
            case YuvPicture e -> {
                var a = (YuvPicture) actual;
                var squared = squaredError(e.y(), e.yStride(), a.y(), a.yStride(), width, height);
                var samples = (double) width * height;
                if (e.chromaFormat().hasChroma()) {
                    var cw = e.chromaWidth();
                    var ch = e.chromaHeight();
                    squared += squaredError(e.u(), e.uvStride(), a.u(), a.uvStride(), cw, ch);
                    squared += squaredError(e.v(), e.uvStride(), a.v(), a.uvStride(), cw, ch);
                    samples += 2.0 * cw * ch;
                }
                if (e.hasAlpha()) {
                    squared += squaredError(e.alpha(), e.alphaStride(), a.alpha(), a.alphaStride(), width, height);
                    samples += (double) width * height;
                }
                yield psnr(squared / samples);
            }
            case RgbaPicture e -> {
                var a = (RgbaPicture) actual;
                yield psnr(squaredError(e.pixels(), e.stride(), a.pixels(), a.stride(), width * 4, height)
                        / (4.0 * width * height));
            }
            case Yuv16Picture e -> {
                var a = (Yuv16Picture) actual;
                var squared = squaredError(e.y(), e.yStride(), a.y(), a.yStride(), width, height);
                var samples = (double) width * height;
                if (e.chromaFormat().hasChroma()) {
                    var cw = e.chromaWidth();
                    var ch = e.chromaHeight();
                    squared += squaredError(e.u(), e.uvStride(), a.u(), a.uvStride(), cw, ch);
                    squared += squaredError(e.v(), e.uvStride(), a.v(), a.uvStride(), cw, ch);
                    samples += 2.0 * cw * ch;
                }
                if (e.hasAlpha()) {
                    squared += squaredError(e.alpha(), e.alphaStride(), a.alpha(), a.alphaStride(), width, height);
                    samples += (double) width * height;
                }
                yield psnr(squared / samples, (1 << e.bitDepth()) - 1);
            }
            case Rgba16Picture e -> {
                var a = (Rgba16Picture) actual;
                yield psnr(squaredError(e.pixels(), e.stride(), a.pixels(), a.stride(), width * 4, height)
                        / (4.0 * width * height), 65535);
            }
        };
    }

    private static double squaredError(byte[] expected, int expectedStride, byte[] actual, int actualStride,
                                       int width, int height) {
        double squared = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var difference = (expected[y * expectedStride + x] & 0xFF) - (actual[y * actualStride + x] & 0xFF);
                squared += (double) difference * difference;
            }
        }
        return squared;
    }

    private static double squaredError(short[] expected, int expectedStride, short[] actual, int actualStride,
                                       int width, int height) {
        double squared = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var difference = (expected[y * expectedStride + x] & 0xFFFF) - (actual[y * actualStride + x] & 0xFFFF);
                squared += (double) difference * difference;
            }
        }
        return squared;
    }

    /**
     * Returns the largest absolute difference between two equally sized 8-bit sample arrays.
     */
    public static int maxDifference(byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            throw new IllegalArgumentException("length mismatch: " + expected.length + " vs " + actual.length);
        }
        var max = 0;
        for (var i = 0; i < expected.length; i++) {
            max = Math.max(max, Math.abs((expected[i] & 0xFF) - (actual[i] & 0xFF)));
        }
        return max;
    }

    /**
     * Returns the signal-to-noise ratio of {@code actual} against the reference {@code expected},
     * over their common length.
     *
     * @return the SNR in decibels, or {@link Double#POSITIVE_INFINITY} for identical input
     */
    public static double snr(float[] expected, float[] actual) {
        var length = Math.min(expected.length, actual.length);
        double signal = 0;
        double noise = 0;
        for (var i = 0; i < length; i++) {
            signal += (double) expected[i] * expected[i];
            var difference = (double) expected[i] - actual[i];
            noise += difference * difference;
        }
        if (noise == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return 10 * Math.log10(signal / noise);
    }
}
