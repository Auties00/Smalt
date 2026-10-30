/* Ports Pillow (src/libImaging/UnsharpMask.c, src/PIL/ImageFilter.py). Copyright Secret Labs AB, Fredrik Lundh and
 * contributors, and Jeffrey A. Clark and contributors; MIT-CMU License. Modified for Smalt. */
package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Objects;

/**
 * Filters pictures by their neighborhoods: Gaussian and box blurs, unsharp masking, sharpening,
 * arbitrary convolution, and rank (minimum, median, maximum) and mode filtering.
 *
 * <p>Every method works on packed RGBA: a {@link YuvPicture} is converted with
 * {@link ImageOps#toRgba(Picture)} first and a {@link Yuv16Picture} to 16-bit RGBA with its matrix
 * and range, and the result is an {@link RgbaPicture} for 8-bit inputs or an {@link Rgba16Picture}
 * for 16-bit ones, with the input's alpha flag. Pixels beyond the edges of the picture repeat the
 * nearest edge pixel, except in {@link #mode}. A picture that reports no alpha is treated as opaque
 * and its result is opaque. Inputs are never modified; the results are new pictures with tightly
 * packed rows, whatever the number of threads that compute them, and follow the limits rule of
 * {@link ImageOps}. This class is thread-safe.
 *
 * @implNote This implementation runs its multiply-accumulate, box-filter and unsharp loops in a kernel with identical
 * scalar and Vector API twins. The box blur and the unsharp mask reproduce Pillow's {@code libImaging/BoxBlur.c} and
 * {@code UnsharpMask.c} exactly on 8-bit pictures, and the rank and mode filters match Pillow's {@code RankFilter} and
 * {@code ModeFilter}.
 */
public final class ImageFilters {
    /**
     * The standard deviation from which {@link BlurMethod#AUTO} switches to the box
     * approximation.
     */
    private static final double AUTO_BOX_SIGMA = 4;

    /**
     * The largest radius {@link #median}, {@link #rank} and the other window filters accept.
     */
    public static final int MAX_MEDIAN_RADIUS = 32;

    /**
     * The largest standard deviation or radius the blurs accept.
     */
    public static final double MAX_RADIUS = 1000;

    /**
     * Prevents instantiation.
     */
    private ImageFilters() {
        throw new AssertionError();
    }

    /**
     * Blurs a picture with a Gaussian of the given standard deviation, choosing the method with
     * {@link BlurMethod#AUTO}.
     *
     * @param picture the picture to blur
     * @param sigma the standard deviation in pixels, from {@code 0} to {@link #MAX_RADIUS}
     * @return the blurred picture, as {@link #gaussianBlur(Picture, double, BlurMethod)} describes
     * @throws IllegalArgumentException if {@code sigma} is out of range or not finite
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture gaussianBlur(Picture picture, double sigma) {
        return gaussianBlur(picture, sigma, BlurMethod.AUTO);
    }

    /**
     * Blurs a picture with a Gaussian of the given standard deviation.
     *
     * <p>A picture with transparency is blurred in premultiplied alpha, so the color of
     * transparent pixels does not bleed into visible ones, and its alpha is blurred too. The mean
     * of every channel of an opaque picture is preserved up to rounding. A deviation of zero
     * returns a packed copy of the picture unchanged.
     *
     * @param picture the picture to blur
     * @param sigma the standard deviation in pixels, from {@code 0} to {@link #MAX_RADIUS}
     * @param method how to compute the blur
     * @return the blurred picture
     * @throws IllegalArgumentException if {@code sigma} is out of range or not finite
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture gaussianBlur(Picture picture, double sigma, BlurMethod method) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(method, "method");
            requireRadius(sigma, "standard deviation");
            try {
                return blur(Canvas.toPacked(picture), sigma, method);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Blurs a picture with one pass of an extended box filter.
     *
     * <p>Every sample becomes the mean of the {@code 2 * floor(radius) + 1} samples centered on it
     * in each direction, plus the samples just beyond them weighted by the fractional part of the
     * radius. On 8-bit pictures this reproduces Pillow's {@code BoxBlur} exactly (8-bit
     * pictures with transparency are blurred in premultiplied alpha as Pillow's {@code RGBa}
     * mode is); 16-bit pictures are filtered the same way in wider arithmetic.
     *
     * @param picture the picture to blur
     * @param radius the radius in pixels, from {@code 0} to {@link #MAX_RADIUS}
     * @return the blurred picture
     * @throws IllegalArgumentException if {@code radius} is out of range or not finite
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture boxBlur(Picture picture, double radius) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            requireRadius(radius, "radius");
            try {
                var packed = Canvas.toPacked(picture);
                return switch (packed) {
                    case RgbaPicture rgba -> BoxBlur.blur(rgba, (float) radius, (float) radius, 1);
                    case Rgba16Picture rgba -> BoxBlur.blur16(rgba, (float) radius, (float) radius, 1);
                    default -> throw new IllegalArgumentException("not a packed picture: " + packed);
                };
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Sharpens a picture with an unsharp mask, choosing the blur with {@link BlurMethod#AUTO}.
     *
     * @param picture the picture to sharpen
     * @param sigma the standard deviation of the blur, from {@code 0} to {@link #MAX_RADIUS}
     * @param amount the strength: {@code 1.0} adds the full difference from the blur once
     * @param threshold the difference, in 8-bit units, at or below which a sample is left alone
     * @return the sharpened picture, as {@link #unsharpMask(Picture, double, double, int, BlurMethod)} describes
     * @throws IllegalArgumentException if an argument is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture unsharpMask(Picture picture, double sigma, double amount, int threshold) {
        return unsharpMask(picture, sigma, amount, threshold, BlurMethod.AUTO);
    }

    /**
     * Sharpens a picture with an unsharp mask.
     *
     * <p>The picture is blurred as {@link #gaussianBlur(Picture, double, BlurMethod)} does; then
     * every color sample {@code v} whose difference {@code d = v - blurred} exceeds
     * {@code threshold} in absolute value becomes {@code clamp(v + trunc(d * p / 100))}, where
     * {@code p} is {@code amount} in whole percent, and the others keep their value. Alpha is
     * kept. With {@link BlurMethod#BOX} on an opaque 8-bit picture this reproduces Pillow's
     * {@code UnsharpMask(sigma, p, threshold)} exactly.
     *
     * @param picture the picture to sharpen
     * @param sigma the standard deviation of the blur, from {@code 0} to {@link #MAX_RADIUS}
     * @param amount the strength, from {@code 0} to {@code 100}: {@code 1.0} adds the full difference from the blur
     * once; rounded to whole percent
     * @param threshold the difference, from {@code 0} to {@code 255} in 8-bit units (scaled by 257 for 16-bit
     * pictures), at or below which a sample is left alone
     * @param method how to compute the blur
     * @return the sharpened picture
     * @throws IllegalArgumentException if an argument is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture unsharpMask(Picture picture, double sigma, double amount, int threshold, BlurMethod method) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(method, "method");
            requireRadius(sigma, "standard deviation");
            if (!(amount >= 0 && amount <= 100)) {
                throw new IllegalArgumentException("amount " + amount + " is outside 0..100");
            }
            if (threshold < 0 || threshold > 255) {
                throw new IllegalArgumentException("threshold " + threshold + " is outside 0..255");
            }
            try {
                var packed = Canvas.toPacked(picture);
                var blurred = blur(packed, sigma, method);
                return sharpen(packed, blurred, (int) Math.round(amount * 100), threshold);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Sharpens a picture with Pillow's {@code SHARPEN} kernel.
     *
     * @param picture the picture to sharpen
     * @return the sharpened picture, as {@link #convolve} describes
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     * @see ConvolutionKernel#sharpen()
     */
    public static Picture sharpen(Picture picture) {
        return convolve(picture, ConvolutionKernel.sharpen());
    }

    /**
     * Convolves a picture with a kernel.
     *
     * <p>Each color sample becomes the weighted sum of the samples under the kernel, divided by
     * the kernel's scale, plus its offset, rounded to nearest and clamped (see
     * {@link ConvolutionKernel}); alpha is copied unchanged. The computation runs in
     * single-precision floating point. Inside the picture the result equals Pillow's
     * {@code ImageFilter.Kernel} up to its rounding; Pillow copies the border pixels unfiltered,
     * where this method reads the edge pixels repeated.
     *
     * @param picture the picture to filter
     * @param kernel the convolution weights
     * @return the filtered picture
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture convolve(Picture picture, ConvolutionKernel kernel) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(kernel, "kernel");
            try {
                return FloatFilter.convolve(Canvas.toPacked(picture), kernel);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Replaces every sample with the median of the square window around it, each channel
     * independently, which removes salt-and-pepper noise while keeping edges.
     *
     * <p>The window has {@code 2 * radius + 1} samples on each side. Alpha is filtered like the
     * colors when the picture reports alpha. On 8-bit pictures this equals Pillow's
     * {@code MedianFilter(2 * radius + 1)}.
     *
     * @param picture the picture to filter
     * @param radius the window radius, from {@code 1} to {@link #MAX_MEDIAN_RADIUS}
     * @return the filtered picture
     * @throws IllegalArgumentException if {@code radius} is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture median(Picture picture, int radius) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            if (radius < 1 || radius > MAX_MEDIAN_RADIUS) {
                throw new IllegalArgumentException("median radius " + radius + " is outside 1.." + MAX_MEDIAN_RADIUS);
            }
            try {
                return RankFilter.median(Canvas.toPacked(picture), radius);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Replaces every sample with the smallest sample of the square window around it, each channel
     * independently, which grows dark areas and removes bright specks (an erosion).
     *
     * <p>The window has {@code 2 * radius + 1} samples on each side. Alpha is filtered like the
     * colors when the picture reports alpha. On 8-bit pictures this equals Pillow's
     * {@code MinFilter(2 * radius + 1)}.
     *
     * @param picture the picture to filter
     * @param radius the window radius, from {@code 1} to {@link #MAX_MEDIAN_RADIUS}
     * @return the filtered picture
     * @throws IllegalArgumentException if {@code radius} is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture minimum(Picture picture, int radius) {
        return rank(picture, radius, 0);
    }

    /**
     * Replaces every sample with the largest sample of the square window around it, each channel
     * independently, which grows bright areas and removes dark specks (a dilation).
     *
     * <p>The window has {@code 2 * radius + 1} samples on each side. Alpha is filtered like the
     * colors when the picture reports alpha. On 8-bit pictures this equals Pillow's
     * {@code MaxFilter(2 * radius + 1)}.
     *
     * @param picture the picture to filter
     * @param radius the window radius, from {@code 1} to {@link #MAX_MEDIAN_RADIUS}
     * @return the filtered picture
     * @throws IllegalArgumentException if {@code radius} is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture maximum(Picture picture, int radius) {
        // an out-of-range radius is rejected by rank before the rank is checked
        var side = 2 * radius + 1;
        return rank(picture, radius, side * side - 1);
    }

    /**
     * Replaces every sample with the sample of a given rank in the square window around it, each
     * channel independently.
     *
     * <p>The {@code n = (2 * radius + 1)^2} samples of a window are sorted in ascending order and
     * the one at the zero-based {@code rank} is kept: {@code 0} is {@link #minimum}, {@code n / 2}
     * {@link #median} and {@code n - 1} {@link #maximum}. Alpha is filtered like the colors when the
     * picture reports alpha. On 8-bit pictures this equals Pillow's
     * {@code RankFilter(2 * radius + 1, rank)}.
     *
     * @param picture the picture to filter
     * @param radius the window radius, from {@code 1} to {@link #MAX_MEDIAN_RADIUS}
     * @param rank the zero-based rank, from {@code 0} to {@code (2 * radius + 1)^2 - 1}
     * @return the filtered picture
     * @throws IllegalArgumentException if {@code radius} or {@code rank} is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture rank(Picture picture, int radius, int rank) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            requireWindow(radius);
            var side = 2 * radius + 1;
            if (rank < 0 || rank >= side * side) {
                throw new IllegalArgumentException("rank " + rank + " is outside 0.." + (side * side - 1));
            }
            try {
                return RankFilter.filter(Canvas.toPacked(picture), radius, rank);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Replaces every sample with the most frequent value of the square window around it, each
     * channel independently, which flattens noisy areas into their dominant values.
     *
     * <p>The window has {@code 2 * radius + 1} samples on each side and, unlike the other filters of
     * this class, is cut at the edges of the picture rather than extended. The most frequent value
     * of the window wins, the smallest one among equally frequent values; a sample whose window has
     * no value occurring more than twice is kept. Alpha is filtered like the colors when the picture
     * reports alpha. On 8-bit pictures this equals Pillow's {@code ModeFilter(2 * radius + 1)}.
     *
     * @param picture the picture to filter
     * @param radius the window radius, from {@code 1} to {@link #MAX_MEDIAN_RADIUS}
     * @return the filtered picture
     * @throws IllegalArgumentException if {@code radius} is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture mode(Picture picture, int radius) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            requireWindow(radius);
            try {
                return ModeFilter.filter(Canvas.toPacked(picture), radius);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Checks the radius of a rank or mode filter window.
     *
     * @param radius the radius to check
     * @throws IllegalArgumentException if it is outside {@code 1..MAX_MEDIAN_RADIUS}
     */
    private static void requireWindow(int radius) {
        if (radius < 1 || radius > MAX_MEDIAN_RADIUS) {
            throw new IllegalArgumentException("window radius " + radius + " is outside 1.." + MAX_MEDIAN_RADIUS);
        }
    }

    /**
     * Blurs a packed picture with a Gaussian.
     *
     * @param packed the packed picture
     * @param sigma the standard deviation
     * @param method the blur algorithm
     * @return the blurred picture
     */
    private static Picture blur(Picture packed, double sigma, BlurMethod method) {
        if (sigma == 0) {
            return PictureCopy.copy(packed, null);
        }
        var box = method == BlurMethod.BOX || (method == BlurMethod.AUTO && sigma >= AUTO_BOX_SIGMA);
        if (box) {
            var radius = BoxBlur.gaussianRadius((float) sigma, 3);
            return switch (packed) {
                case RgbaPicture rgba -> BoxBlur.blur(rgba, radius, radius, 3);
                case Rgba16Picture rgba -> BoxBlur.blur16(rgba, radius, radius, 3);
                default -> throw new IllegalArgumentException("not a packed picture: " + packed);
            };
        }
        var taps = FloatFilter.gaussian(sigma);
        return FloatFilter.separable(packed, taps, taps, premultiplied(packed));
    }

    /**
     * Returns whether a packed picture must be filtered in premultiplied alpha.
     *
     * @param packed the packed picture
     * @return whether it reports alpha and is not fully opaque
     */
    private static boolean premultiplied(Picture packed) {
        return packed.hasAlpha() && !ImageOps.isOpaque(packed);
    }

    /**
     * Applies the unsharp mask correction to a picture and its blurred copy.
     *
     * @param packed the packed picture
     * @param blurred its blurred copy, tightly packed
     * @param percent the strength in percent
     * @param threshold the threshold in 8-bit units
     * @return the sharpened picture
     */
    private static Picture sharpen(Picture packed, Picture blurred, int percent, int threshold) {
        var width = packed.width();
        var height = packed.height();
        var bands = ParallelBands.bands(height, 4L * width * height);
        return switch (packed) {
            case RgbaPicture rgba -> {
                var soft = (RgbaPicture) blurred;
                var out = RgbaPicture.allocate(width, height, rgba.hasAlpha());
                ParallelBands.run(bands, band -> {
                    var kernel = EffectKernel.INSTANCE;
                    for (var y = ParallelBands.start(band, bands, height); y < ParallelBands.start(band + 1, bands, height); y++) {
                        var row = y * out.stride();
                        kernel.unsharpRow(rgba.pixels(), y * rgba.stride(), soft.pixels(), y * soft.stride(), 4 * width,
                                percent, threshold, out.pixels(), row);
                        for (var x = 0; x < width; x++) {
                            out.pixels()[row + 4 * x + 3] = rgba.hasAlpha() ? rgba.pixels()[y * rgba.stride() + 4 * x + 3] : (byte) 255;
                        }
                    }
                });
                yield out;
            }
            case Rgba16Picture rgba -> {
                var soft = (Rgba16Picture) blurred;
                var out = Rgba16Picture.allocate(width, height, rgba.hasAlpha());
                var limit = threshold * 257;
                ParallelBands.run(bands, band -> {
                    for (var y = ParallelBands.start(band, bands, height); y < ParallelBands.start(band + 1, bands, height); y++) {
                        for (var x = 0; x < width; x++) {
                            var source = y * rgba.stride() + 4 * x;
                            var target = y * out.stride() + 4 * x;
                            for (var c = 0; c < 3; c++) {
                                var value = rgba.pixels()[source + c] & 0xFFFF;
                                var diff = value - (soft.pixels()[y * soft.stride() + 4 * x + c] & 0xFFFF);
                                out.pixels()[target + c] = (short) (Math.abs(diff) > limit
                                        ? Math.clamp(value + (long) diff * percent / 100, 0, 65535) : value);
                            }
                            out.pixels()[target + 3] = rgba.hasAlpha() ? rgba.pixels()[source + 3] : (short) 0xFFFF;
                        }
                    }
                });
                yield out;
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + packed);
        };
    }

    /**
     * Checks a standard deviation or radius.
     *
     * @param value the standard deviation or radius to check
     * @param name the parameter name used in the message
     * @throws IllegalArgumentException if the value is outside {@code 0..MAX_RADIUS} or not finite
     */
    private static void requireRadius(double value, String name) {
        if (!(value >= 0 && value <= MAX_RADIUS)) {
            throw new IllegalArgumentException(name + " " + value + " is outside 0.." + MAX_RADIUS);
        }
    }

    /**
     * Translates an unexpected runtime failure into {@link MediaException.Malformed}, passing
     * through the exceptions this class documents.
     *
     * @param exception the failure
     * @return the exception to throw
     */
    static RuntimeException translate(RuntimeException exception) {
        if (exception instanceof MediaException
                || exception instanceof IllegalArgumentException
                || exception instanceof NullPointerException) {
            return exception;
        }
        return new MediaException.Malformed("picture processing failed: " + exception, exception);
    }
}
