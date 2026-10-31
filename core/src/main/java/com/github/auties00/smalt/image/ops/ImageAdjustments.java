/* Ports Pillow (src/PIL/ImageEnhance.py, src/PIL/ImageOps.py, src/libImaging/Blend.c). Copyright Secret Labs AB,
 * Fredrik Lundh and contributors, and Jeffrey A. Clark and contributors; MIT-CMU License. Modified for Smalt. */
package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Objects;

/**
 * Adjusts the tones and colors of pictures pixel by pixel: brightness, contrast, saturation,
 * gamma, levels, curves, automatic levels and contrast, histogram equalization, grayscale, sepia,
 * colorizing, tinting, inversion, thresholding, posterizing, solarizing, hue rotation, vignetting
 * and white balance.
 *
 * <p>Every method works on packed RGBA: a {@link YuvPicture} is converted with
 * {@link ImageOps#toRgba(Picture)} first and a {@link Yuv16Picture} to 16-bit RGBA with its matrix
 * and range, and the result is an {@link RgbaPicture} for 8-bit inputs or an {@link Rgba16Picture}
 * for 16-bit ones. Only the color channels change: alpha is kept, and a picture that reports no
 * alpha stays opaque. Samples are gamma-encoded, as stored, unless a method says it works in
 * linear light. Inputs are never modified and the results are new pictures, whatever the number of
 * threads that compute them; {@link com.github.auties00.smalt.MediaRuntime} describes the threading
 * model, and {@link ImageOps} the limits rule. This class is thread-safe.
 *
 * <p>Where a method is described by its 8-bit formula, a 16-bit picture uses the same formula with
 * {@code 65535} in place of {@code 255} (and 8-bit parameters scaled by 257).
 *
 * @implNote This implementation reproduces Pillow's {@code ImageEnhance} ({@code Brightness}, {@code Contrast},
 * {@code Color}) and {@code ImageOps} ({@code autocontrast}, {@code colorize}, {@code equalize}, {@code invert},
 * {@code posterize}, {@code solarize}) and its {@code L} conversion exactly on 8-bit pictures, including their
 * single-precision blending and truncation. Lookup tables are applied by scalar code, and channel mixing runs in a
 * kernel with identical scalar and Vector API twins.
 */
public final class ImageAdjustments {
    /**
     * The fixed-point channel matrix of the sepia tone.
     */
    private static final int[] SEPIA = {
            fixed(0.393), fixed(0.769), fixed(0.189), 0x8000,
            fixed(0.349), fixed(0.686), fixed(0.168), 0x8000,
            fixed(0.272), fixed(0.534), fixed(0.131), 0x8000
    };

    /**
     * Prevents instantiation.
     */
    private ImageAdjustments() {
        throw new AssertionError();
    }

    /**
     * Scales the brightness of a picture.
     *
     * <p>Each color sample {@code v} becomes {@code clamp(trunc(factor * v))}, computed in single
     * precision as Pillow's {@code ImageEnhance.Brightness} blends with black.
     *
     * @param picture the picture to brighten or darken
     * @param factor the factor: {@code 0} gives black, {@code 1} the original; from {@code 0} to {@code 100}
     * @return the adjusted picture
     * @throws IllegalArgumentException if {@code factor} is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture brightness(Picture picture, double factor) {
        try (var _ = ParallelBands.operation()) {
            requireFactor(factor);
            return run(picture, packed -> {
                var table = blendTable(Tone.max(packed), 0, (float) factor);
                return Tone.lookup(packed, table, table, table);
            });
        }
    }

    /**
     * Scales the contrast of a picture about its mean gray.
     *
     * <p>With {@code m} the mean BT.601 luma of the picture rounded to an integer, each color
     * sample {@code v} becomes {@code clamp(trunc(m + factor * (v - m)))}, computed in single
     * precision as Pillow's {@code ImageEnhance.Contrast} does.
     *
     * @param picture the picture to adjust
     * @param factor the factor: {@code 0} gives a uniform gray, {@code 1} the original; from {@code 0} to {@code 100}
     * @return the adjusted picture
     * @throws IllegalArgumentException if {@code factor} is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture contrast(Picture picture, double factor) {
        try (var _ = ParallelBands.operation()) {
            requireFactor(factor);
            return run(picture, packed -> {
                var histogram = Tone.lumaHistogram(packed);
                double sum = 0;
                long count = 0;
                for (var i = 0; i < histogram.length; i++) {
                    sum += (double) i * histogram[i];
                    count += histogram[i];
                }
                var mean = (int) (sum / count + 0.5);
                var table = blendTable(Tone.max(packed), mean, (float) factor);
                return Tone.lookup(packed, table, table, table);
            });
        }
    }

    /**
     * Scales the color saturation of a picture.
     *
     * <p>With {@code l} the BT.601 luma of a pixel (see {@link GrayscaleMode#BT601}), each color
     * sample {@code v} becomes {@code clamp(trunc(l + factor * (v - l)))}, computed in single
     * precision as Pillow's {@code ImageEnhance.Color} does.
     *
     * @param picture the picture to saturate or desaturate
     * @param factor the factor: {@code 0} gives grayscale, {@code 1} the original; from {@code 0} to {@code 100}
     * @return the adjusted picture
     * @throws IllegalArgumentException if {@code factor} is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture saturation(Picture picture, double factor) {
        try (var _ = ParallelBands.operation()) {
            requireFactor(factor);
            var f = (float) factor;
            return run(picture, packed -> {
                var max = Tone.max(packed);
                return Tone.map(packed, rgba -> {
                    var luma = Tone.luma601(rgba[0], rgba[1], rgba[2]);
                    for (var c = 0; c < 3; c++) {
                        rgba[c] = blend(luma, rgba[c], f, max);
                    }
                });
            });
        }
    }

    /**
     * Applies a gamma correction: each color sample {@code v} becomes
     * {@code round(255 * (v / 255)^(1 / gamma))}.
     *
     * @param picture the picture to correct
     * @param gamma the gamma: above 1 brightens the mid-tones, below 1 darkens them; positive and finite
     * @return the adjusted picture
     * @throws IllegalArgumentException if {@code gamma} is not positive and finite
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture gamma(Picture picture, double gamma) {
        return curves(picture, ToneCurve.gamma(gamma));
    }

    /**
     * Remaps the tonal range of a picture, as the levels tool of image editors does.
     *
     * <p>Each color sample {@code v} is clamped to {@code [inBlack, inWhite]}, normalized to
     * {@code t = (v - inBlack) / (inWhite - inBlack)}, raised to {@code 1 / gamma}, and mapped to
     * {@code round(outBlack + (outWhite - outBlack) * t^(1 / gamma))}. All levels are in 8-bit
     * units.
     *
     * @param picture the picture to remap
     * @param inBlack the input level that becomes {@code outBlack}, {@code 0..254}
     * @param inWhite the input level that becomes {@code outWhite}, above {@code inBlack} and at most {@code 255}
     * @param gamma the mid-tone gamma: above 1 brightens; positive and finite
     * @param outBlack the output level of black, {@code 0..255}
     * @param outWhite the output level of white, {@code 0..255}; may be below {@code outBlack}, which inverts
     * @return the adjusted picture
     * @throws IllegalArgumentException if a level is out of range or the gamma is invalid
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture levels(Picture picture, int inBlack, int inWhite, double gamma, int outBlack, int outWhite) {
        if (inBlack < 0 || inWhite > 255 || inBlack >= inWhite || outBlack < 0 || outBlack > 255 || outWhite < 0
                || outWhite > 255) {
            throw new IllegalArgumentException("invalid levels " + inBlack + ".." + inWhite + " -> " + outBlack + ".." + outWhite);
        }
        if (!(gamma > 0) || !Double.isFinite(gamma)) {
            throw new IllegalArgumentException("gamma " + gamma + " is not positive and finite");
        }
        var low = inBlack / 255.0;
        var high = inWhite / 255.0;
        var exponent = 1 / gamma;
        var black = outBlack / 255.0;
        var white = outWhite / 255.0;
        return curves(picture, ToneCurve.of(x -> {
            var t = (Math.clamp(x, low, high) - low) / (high - low);
            return black + (white - black) * StrictMath.pow(t, exponent);
        }));
    }

    /**
     * Applies one tone curve to the red, green and blue channels.
     *
     * @param picture the picture to adjust
     * @param curve the curve applied to red, green and blue
     * @return the adjusted picture
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture curves(Picture picture, ToneCurve curve) {
        return curves(picture, curve, curve, curve);
    }

    /**
     * Applies a tone curve to each color channel.
     *
     * <p>Each sample {@code v} of a channel becomes {@code round(255 * curve(v / 255))}.
     *
     * @param picture the picture to adjust
     * @param red the curve of the red channel
     * @param green the curve of the green channel
     * @param blue the curve of the blue channel
     * @return the adjusted picture
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture curves(Picture picture, ToneCurve red, ToneCurve green, ToneCurve blue) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(red, "red");
            Objects.requireNonNull(green, "green");
            Objects.requireNonNull(blue, "blue");
            return run(picture, packed -> {
                var max = Tone.max(packed);
                var r = red.table(max);
                var g = green == red ? r : green.table(max);
                var b = blue == red ? r : blue == green ? g : blue.table(max);
                return Tone.lookup(packed, r, g, b);
            });
        }
    }

    /**
     * Stretches each color channel independently to the full range, which also corrects a color
     * cast.
     *
     * <p>For each channel, the darkest and lightest {@code cutoff} percent of the samples are
     * ignored; the darkest remaining value {@code lo} becomes 0, the lightest {@code hi} becomes
     * 255, and every sample {@code v} becomes {@code clamp(trunc((v - lo) * 255 / (hi - lo)))};
     * a channel with a single remaining value is left unchanged. This is Pillow's
     * {@code ImageOps.autocontrast(image, cutoff)}.
     *
     * @param picture the picture to stretch
     * @param cutoff the percentage of samples to ignore at each end, {@code 0} to {@code 50}
     * @return the adjusted picture
     * @throws IllegalArgumentException if {@code cutoff} is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture autoLevels(Picture picture, double cutoff) {
        try (var _ = ParallelBands.operation()) {
            requireCutoff(cutoff);
            return run(picture, packed -> {
                var histograms = Tone.histograms(packed);
                var max = Tone.max(packed);
                return Tone.lookup(packed, stretch(histograms[0], cutoff, max), stretch(histograms[1], cutoff, max),
                        stretch(histograms[2], cutoff, max));
            });
        }
    }

    /**
     * Stretches the contrast of a picture to the full range without changing its hues.
     *
     * <p>Like {@link #autoLevels}, but the range is measured on the BT.601 luma of the pixels and
     * the same mapping is applied to the three color channels. This is Pillow's
     * {@code ImageOps.autocontrast(image, cutoff, preserve_tone=True)}.
     *
     * @param picture the picture to stretch
     * @param cutoff the percentage of pixels to ignore at each end, {@code 0} to {@code 50}
     * @return the adjusted picture
     * @throws IllegalArgumentException if {@code cutoff} is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture autoContrast(Picture picture, double cutoff) {
        try (var _ = ParallelBands.operation()) {
            requireCutoff(cutoff);
            return run(picture, packed -> {
                var table = stretch(Tone.lumaHistogram(packed), cutoff, Tone.max(packed));
                return Tone.lookup(packed, table, table, table);
            });
        }
    }

    /**
     * Converts a picture to gray, keeping it in RGBA with three equal color channels.
     *
     * @param picture the picture to convert
     * @param mode the weighting of the color channels
     * @return the gray picture
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture grayscale(Picture picture, GrayscaleMode mode) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(mode, "mode");
            return run(picture, packed -> switch (mode) {
                case BT601 -> Tone.mix(packed, uniform(19595, 38470, 7471));
                case BT709 -> Tone.mix(packed, uniform(13933, 46871, 4732));
                case LUMINANCE -> {
                    if (packed instanceof RgbaPicture) {
                        yield Tone.map(packed, rgba -> {
                            var y = 0.2126f * Srgb.decode8(rgba[0]) + 0.7152f * Srgb.decode8(rgba[1])
                                    + 0.0722f * Srgb.decode8(rgba[2]);
                            var gray = Srgb.encode8(y);
                            rgba[0] = gray;
                            rgba[1] = gray;
                            rgba[2] = gray;
                        });
                    }
                    yield Tone.map(packed, rgba -> {
                        var y = 0.2126 * Srgb.decode(rgba[0] / 65535.0) + 0.7152 * Srgb.decode(rgba[1] / 65535.0)
                                + 0.0722 * Srgb.decode(rgba[2] / 65535.0);
                        var gray = Srgb.encode16(y);
                        rgba[0] = gray;
                        rgba[1] = gray;
                        rgba[2] = gray;
                    });
                }
            });
        }
    }

    /**
     * Gives a picture a sepia tone with the classic matrix: red becomes
     * {@code 0.393 R + 0.769 G + 0.189 B}, green {@code 0.349 R + 0.686 G + 0.168 B} and blue
     * {@code 0.272 R + 0.534 G + 0.131 B}, each rounded and clamped.
     *
     * @param picture the picture to tone
     * @return the toned picture
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture sepia(Picture picture) {
        try (var _ = ParallelBands.operation()) {
            return run(picture, packed -> Tone.mix(packed, SEPIA));
        }
    }

    /**
     * Maps the luma of a picture onto a gradient between two colors (a duotone).
     *
     * <p>With {@code l} the BT.601 luma of a pixel, each channel becomes
     * {@code black + floor(l * (white - black) / 255)} for {@code l < 255} and {@code white} for
     * {@code l = 255}, as Pillow's {@code ImageOps.colorize(image, black, white)} computes it.
     *
     * @param picture the picture to colorize
     * @param rgbBlack the color of black as {@code 0xRRGGBB}; the top byte is ignored
     * @param rgbWhite the color of white as {@code 0xRRGGBB}; the top byte is ignored
     * @return the colorized picture
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture colorize(Picture picture, int rgbBlack, int rgbWhite) {
        try (var _ = ParallelBands.operation()) {
            return run(picture, packed -> {
                var max = Tone.max(packed);
                var scale = max / 255;
                var tables = new int[3][];
                for (var c = 0; c < 3; c++) {
                    var shift = 16 - 8 * c;
                    var black = ((rgbBlack >>> shift) & 0xFF) * scale;
                    var white = ((rgbWhite >>> shift) & 0xFF) * scale;
                    var table = new int[max + 1];
                    for (var i = 0; i < max; i++) {
                        table[i] = black + (int) Math.floorDiv((long) i * (white - black), max);
                    }
                    table[max] = white;
                    tables[c] = table;
                }
                return Tone.map(packed, rgba -> {
                    var luma = Tone.luma601(rgba[0], rgba[1], rgba[2]);
                    rgba[0] = tables[0][luma];
                    rgba[1] = tables[1][luma];
                    rgba[2] = tables[2][luma];
                });
            });
        }
    }

    /**
     * Tints a picture by multiplying it with a color.
     *
     * <p>Each color sample {@code v} becomes {@code round(v * (1 - amount + amount * t / 255))},
     * with {@code t} the matching channel of the tint: {@code amount = 1} multiplies by the tint
     * (white leaves the picture unchanged), {@code 0} leaves it unchanged.
     *
     * @param picture the picture to tint
     * @param rgbTint the tint as {@code 0xRRGGBB}; the top byte is ignored
     * @param amount the strength, from {@code 0} to {@code 1}
     * @return the tinted picture
     * @throws IllegalArgumentException if {@code amount} is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture tint(Picture picture, int rgbTint, double amount) {
        if (!(amount >= 0 && amount <= 1)) {
            throw new IllegalArgumentException("tint amount " + amount + " is outside 0..1");
        }
        return curves(picture, multiply((rgbTint >>> 16) & 0xFF, amount), multiply((rgbTint >>> 8) & 0xFF, amount),
                multiply(rgbTint & 0xFF, amount));
    }

    /**
     * Inverts the colors of a picture: each color sample {@code v} becomes {@code 255 - v}.
     *
     * @param picture the picture to invert
     * @return the inverted picture
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture invert(Picture picture) {
        try (var _ = ParallelBands.operation()) {
            return run(picture, packed -> {
                var max = Tone.max(packed);
                var table = new int[max + 1];
                for (var i = 0; i <= max; i++) {
                    table[i] = max - i;
                }
                return Tone.lookup(packed, table, table, table);
            });
        }
    }

    /**
     * Reduces a picture to black and white by its BT.601 luma: pixels whose luma is at least
     * {@code level} become white, the others black.
     *
     * @param picture the picture to reduce
     * @param level the threshold in 8-bit units, {@code 0..256} ({@code 0} makes every pixel white, {@code 256} every
     * pixel black); scaled by 257 for 16-bit pictures
     * @return the black and white picture
     * @throws IllegalArgumentException if {@code level} is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture threshold(Picture picture, int level) {
        if (level < 0 || level > 256) {
            throw new IllegalArgumentException("threshold " + level + " is outside 0..256");
        }
        try (var _ = ParallelBands.operation()) {
            return run(picture, packed -> {
                var max = Tone.max(packed);
                var limit = level * (max / 255);
                return Tone.map(packed, rgba -> {
                    var value = Tone.luma601(rgba[0], rgba[1], rgba[2]) >= limit ? max : 0;
                    rgba[0] = value;
                    rgba[1] = value;
                    rgba[2] = value;
                });
            });
        }
    }

    /**
     * Equalizes the histogram of each color channel, spreading the values a picture uses over the
     * full range.
     *
     * <p>Equivalent to {@link #equalize(Picture, Picture) equalize(picture, null)}: every pixel is
     * counted.
     *
     * @param picture the picture to equalize
     * @return the equalized picture
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture equalize(Picture picture) {
        return equalize(picture, null);
    }

    /**
     * Equalizes the histogram of each color channel, counting only the pixels a mask selects, and
     * maps every pixel through the result.
     *
     * <p>For each channel, with {@code h} the histogram of the selected pixels, {@code c(v)} the
     * number of selected samples below {@code v} and {@code n} the number of selected samples less
     * the count of the largest value present, each sample {@code v} of an 8-bit picture becomes
     * {@code min(255, (c(v) + s / 2) / s)} in integer arithmetic, with {@code s = n / 255}; this is
     * Pillow's {@code ImageOps.equalize(image, mask)}. A 16-bit picture uses the exact step
     * {@code n / 65535}: {@code v} becomes {@code min(65535, floor(c(v) * 65535 / n + 1 / 2))}. A
     * channel with fewer than two values present, or whose step {@code s} is zero, is left unchanged.
     *
     * @param picture the picture to equalize
     * @param mask the pixels whose histogram is equalized, the picture's size, a pixel counting where any
     * color channel of the mask is not zero (alpha is ignored); or {@code null} to count every pixel
     * @return the equalized picture
     * @throws IllegalArgumentException if the mask's size differs from the picture's
     * @throws MediaException.Unsupported if the mask is a channel picture of ink separations
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture equalize(Picture picture, Picture mask) {
        try (var _ = ParallelBands.operation()) {
            return run(picture, packed -> {
                var selected = mask == null ? null : Tone.selection(mask, packed.width(), packed.height());
                var histograms = Tone.histograms(packed, selected);
                var max = Tone.max(packed);
                return Tone.lookup(packed, equalized(histograms[0], max), equalized(histograms[1], max),
                        equalized(histograms[2], max));
            });
        }
    }

    /**
     * Reduces the number of levels of each color channel by keeping only the most significant bits
     * of its samples, as Pillow's {@code ImageOps.posterize(image, bits)} does.
     *
     * <p>Each color sample {@code v} becomes {@code v & ~(2^(8 - bits) - 1)} on an 8-bit picture and
     * {@code v & ~(2^(16 - bits) - 1)} on a 16-bit one, so a 16-bit picture keeps {@code bits} of
     * its 16 bits.
     *
     * @param picture the picture to posterize
     * @param bits the number of bits to keep, from {@code 1} to {@code 8} for an 8-bit picture and to
     * {@code 16} for a 16-bit one
     * @return the posterized picture
     * @throws IllegalArgumentException if {@code bits} is out of range for the picture's depth
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture posterize(Picture picture, int bits) {
        if (bits < 1 || bits > 16) {
            throw new IllegalArgumentException("posterize bits " + bits + " is outside 1..16");
        }
        try (var _ = ParallelBands.operation()) {
            return run(picture, packed -> {
                var max = Tone.max(packed);
                var depth = max == 255 ? 8 : 16;
                if (bits > depth) {
                    throw new IllegalArgumentException("posterize bits " + bits + " is outside 1.." + depth
                            + " for a " + depth + "-bit picture");
                }
                var keep = ~((1 << (depth - bits)) - 1);
                var table = new int[max + 1];
                for (var v = 0; v <= max; v++) {
                    table[v] = v & keep;
                }
                return Tone.lookup(packed, table, table, table);
            });
        }
    }

    /**
     * Inverts the color samples at or above a threshold, as Pillow's
     * {@code ImageOps.solarize(image, threshold)} does: each color sample {@code v} at least
     * {@code threshold} becomes {@code 255 - v}.
     *
     * @param picture the picture to solarize
     * @param threshold the level from which samples are inverted, in 8-bit units, {@code 0..256}
     * ({@code 0} inverts every sample, {@code 256} none); scaled by 257 for 16-bit pictures
     * @return the solarized picture
     * @throws IllegalArgumentException if {@code threshold} is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture solarize(Picture picture, int threshold) {
        if (threshold < 0 || threshold > 256) {
            throw new IllegalArgumentException("solarize threshold " + threshold + " is outside 0..256");
        }
        try (var _ = ParallelBands.operation()) {
            return run(picture, packed -> {
                var max = Tone.max(packed);
                var limit = threshold * (max / 255);
                var table = new int[max + 1];
                for (var v = 0; v <= max; v++) {
                    table[v] = v < limit ? v : max - v;
                }
                return Tone.lookup(packed, table, table, table);
            });
        }
    }

    /**
     * Rotates the hue of every pixel around the HSV color wheel, keeping its HSV saturation and
     * value.
     *
     * <p>With {@code M} and {@code m} the largest and smallest color samples of a pixel and
     * {@code d = M - m}, a gray pixel ({@code d = 0}) is unchanged. Otherwise the hue
     * {@code h} in {@code [0, 6)} is {@code (g - b) / d} (plus 6 when negative) when red is the
     * largest sample, {@code 2 + (b - r) / d} when green is and {@code 4 + (r - g) / d} otherwise,
     * in double precision. The new hue {@code h' = (h + degrees / 60) mod 6} is split into its
     * sector {@code k = floor(h')} and fraction {@code f = h' - k}; with {@code u = m + d * f} and
     * {@code w = m + d * (1 - f)} each rounded half up, sector 0 gives {@code (M, u, m)}, 1
     * {@code (w, M, m)}, 2 {@code (m, M, u)}, 3 {@code (m, w, M)}, 4 {@code (u, m, M)} and 5
     * {@code (M, m, w)}. The largest and smallest samples of every pixel therefore stay exact.
     *
     * @param picture the picture to adjust
     * @param degrees the rotation in degrees, any finite value: 120 turns red into green, green into blue and blue
     * into red
     * @return the adjusted picture
     * @throws IllegalArgumentException if {@code degrees} is not finite
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture rotateHue(Picture picture, double degrees) {
        if (!Double.isFinite(degrees)) {
            throw new IllegalArgumentException("hue rotation " + degrees + " is not finite");
        }
        var turn = degrees % 360;
        var shift = (turn < 0 ? turn + 360 : turn) / 60;
        try (var _ = ParallelBands.operation()) {
            return run(picture, packed -> Tone.map(packed, rgba -> rotateHue(rgba, shift)));
        }
    }

    /**
     * Darkens or lightens a picture toward its corners, in linear light, as a lens vignette does.
     *
     * <p>With {@code (x + 0.5, y + 0.5)} the center of a pixel of a {@code W} by {@code H} picture,
     * its normalized distance from the center of the picture is
     * {@snippet :
     * dx = (x + 0.5 - W / 2) / (W / 2)
     * dy = (y + 0.5 - H / 2) / (H / 2)
     * d = sqrt((dx * dx + dy * dy) / 2)          // 0 at the center, 1 at the corners
     * t = clamp((d - radius) / (1 - radius), 0, 1)
     * factor = 1 - amount * t * t * (3 - 2 * t)  // smoothstep falloff
     * }
     * and each color sample is decoded with the sRGB transfer function, multiplied by
     * {@code factor}, encoded back and rounded to the nearest sample, clamped. Pixels within
     * {@code radius} of the center are unchanged.
     *
     * @param picture the picture to adjust
     * @param amount the strength at the corners, from {@code -1} to {@code 1}: {@code 1} makes the corners black,
     * a negative amount lightens them
     * @param radius the normalized distance at which the falloff starts, from {@code 0} (inclusive) to {@code 1}
     * (exclusive)
     * @return the adjusted picture
     * @throws IllegalArgumentException if {@code amount} or {@code radius} is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture vignette(Picture picture, double amount, double radius) {
        if (!(amount >= -1 && amount <= 1)) {
            throw new IllegalArgumentException("vignette amount " + amount + " is outside -1..1");
        }
        if (!(radius >= 0 && radius < 1)) {
            throw new IllegalArgumentException("vignette radius " + radius + " is outside [0, 1)");
        }
        try (var _ = ParallelBands.operation()) {
            return run(picture, packed -> Lighting.vignette(packed, amount, radius));
        }
    }

    /**
     * Corrects the white balance of a picture taken under a light of a given color temperature and
     * tint, adapting its colors in linear light to a neutral reference light.
     *
     * <p>The source white is the point of the Planckian locus at {@code kelvin} in the CIE 1960
     * {@code (u, v)} diagram (Krystek's 1985 rational approximation), moved by {@code tint} along
     * the locus normal, positive toward green (higher {@code v}); the reference white is the same
     * construction at 6504 K with no tint, so {@code whiteBalance(picture, 6504, 0)} returns the
     * picture unchanged. Colors are decoded with the sRGB transfer function, converted to CIE XYZ
     * with the sRGB (D65) matrix, adapted from the source white to the reference white with the
     * Bradford transform, converted back, encoded and rounded to the nearest sample, clamped. A
     * temperature below 6504 K (warm light) therefore makes the picture bluer, one above it
     * yellower, and a positive tint makes it more magenta.
     *
     * @param picture the picture to correct
     * @param kelvin the correlated color temperature of the light, from {@code 1000} to {@code 15000}
     * @param tint the distance of the light from the Planckian locus in the {@code (u, v)} diagram (its
     * {@code Duv}), from {@code -0.05} to {@code 0.05}
     * @return the corrected picture
     * @throws IllegalArgumentException if {@code kelvin} or {@code tint} is out of range
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture whiteBalance(Picture picture, double kelvin, double tint) {
        if (!(kelvin >= 1000 && kelvin <= 15000)) {
            throw new IllegalArgumentException("color temperature " + kelvin + " K is outside 1000..15000");
        }
        if (!(tint >= -0.05 && tint <= 0.05)) {
            throw new IllegalArgumentException("tint " + tint + " is outside -0.05..0.05");
        }
        try (var _ = ParallelBands.operation()) {
            return run(picture, packed -> Lighting.whiteBalance(packed, Lighting.adaptation(kelvin, tint)));
        }
    }

    /**
     * Rotates the hue of one pixel in place.
     *
     * @param rgba the samples of the pixel; the first three are replaced
     * @param shift the rotation in sixths of a turn, {@code [0, 6)}
     */
    private static void rotateHue(int[] rgba, double shift) {
        var r = rgba[0];
        var g = rgba[1];
        var b = rgba[2];
        var high = Math.max(r, Math.max(g, b));
        var low = Math.min(r, Math.min(g, b));
        var d = high - low;
        if (d == 0) {
            return;
        }
        double h;
        if (high == r) {
            h = (double) (g - b) / d;
            if (h < 0) {
                h += 6;
            }
        } else if (high == g) {
            h = 2 + (double) (b - r) / d;
        } else {
            h = 4 + (double) (r - g) / d;
        }
        h += shift;
        if (h >= 6) {
            h -= 6;
        }
        var sector = (int) h;
        var f = h - sector;
        var rising = (int) Math.floor(low + d * f + 0.5);
        var falling = (int) Math.floor(low + d * (1 - f) + 0.5);
        switch (sector) {
            case 0 -> set(rgba, high, rising, low);
            case 1 -> set(rgba, falling, high, low);
            case 2 -> set(rgba, low, high, rising);
            case 3 -> set(rgba, low, falling, high);
            case 4 -> set(rgba, rising, low, high);
            default -> set(rgba, high, low, falling);
        }
    }

    /**
     * Writes the color samples of a pixel.
     *
     * @param rgba the samples of the pixel
     * @param r the red sample
     * @param g the green sample
     * @param b the blue sample
     */
    private static void set(int[] rgba, int r, int g, int b) {
        rgba[0] = r;
        rgba[1] = g;
        rgba[2] = b;
    }

    /**
     * Computes the lookup table of a histogram equalization.
     *
     * @param histogram the histogram of the selected samples, {@code max + 1} bins
     * @param max the largest sample value, 255 or 65535
     * @return the table
     */
    private static int[] equalized(long[] histogram, int max) {
        var table = new int[max + 1];
        long total = 0;
        long lastCount = 0;
        var present = 0;
        for (var count : histogram) {
            if (count != 0) {
                total += count;
                lastCount = count;
                present++;
            }
        }
        var n = total - lastCount;
        var identity = present <= 1 || (max == 255 ? n / 255 == 0 : n == 0);
        if (identity) {
            for (var v = 0; v <= max; v++) {
                table[v] = v;
            }
            return table;
        }
        long below = 0;
        if (max == 255) {
            var step = n / 255;
            var offset = step / 2;
            for (var v = 0; v <= max; v++) {
                table[v] = (int) Math.min(max, (offset + below) / step);
                below += histogram[v];
            }
        } else {
            for (var v = 0; v <= max; v++) {
                table[v] = (int) Math.min(max, (2 * below * max + n) / (2 * n));
                below += histogram[v];
            }
        }
        return table;
    }

    /**
     * Returns the table of Pillow's single-precision blend of a constant with each value.
     *
     * @param max the largest sample value
     * @param base the constant, the result for a factor of 0
     * @param factor the blend factor, 1 for the identity
     * @return the table, {@code max + 1} entries
     */
    private static int[] blendTable(int max, int base, float factor) {
        var table = new int[max + 1];
        for (var v = 0; v <= max; v++) {
            table[v] = blend(base, v, factor, max);
        }
        return table;
    }

    /**
     * Blends a value away from a base as Pillow's {@code Image.blend} does:
     * {@code base + factor * (value - base)} in single precision, clamped and truncated.
     *
     * @param base the base value, the result for a factor of 0
     * @param value the value blended away from {@code base}, the result for a factor of 1
     * @param factor the blend factor
     * @param max the largest sample value
     * @return the blended value
     */
    private static int blend(int base, int value, float factor, int max) {
        var t = base + factor * (value - base);
        if (t <= 0) {
            return 0;
        }
        if (t >= max) {
            return max;
        }
        return (int) t;
    }

    /**
     * Computes Pillow's autocontrast table of one histogram.
     *
     * @param histogram the histogram, {@code max + 1} bins
     * @param cutoff the percentage to ignore at each end
     * @param max the largest sample value
     * @return the table
     */
    private static int[] stretch(long[] histogram, double cutoff, int max) {
        var h = histogram.clone();
        long n = 0;
        for (var count : h) {
            n += count;
        }
        if (cutoff > 0) {
            var cut = (long) Math.floor(n * cutoff / 100);
            for (var lo = 0; lo <= max && cut > 0; lo++) {
                if (cut > h[lo]) {
                    cut -= h[lo];
                    h[lo] = 0;
                } else {
                    h[lo] -= cut;
                    cut = 0;
                }
            }
            cut = (long) Math.floor(n * cutoff / 100);
            for (var hi = max; hi >= 0 && cut > 0; hi--) {
                if (cut > h[hi]) {
                    cut -= h[hi];
                    h[hi] = 0;
                } else {
                    h[hi] -= cut;
                    cut = 0;
                }
            }
        }
        var lo = 0;
        while (lo < max && h[lo] == 0) {
            lo++;
        }
        var hi = max;
        while (hi > 0 && h[hi] == 0) {
            hi--;
        }
        var table = new int[max + 1];
        if (hi <= lo) {
            for (var i = 0; i <= max; i++) {
                table[i] = i;
            }
            return table;
        }
        var scale = (double) max / (hi - lo);
        var offset = -lo * scale;
        for (var i = 0; i <= max; i++) {
            table[i] = (int) Math.clamp((long) (i * scale + offset), 0, max);
        }
        return table;
    }

    /**
     * Returns the tone curve of a multiplicative tint of one channel.
     *
     * @param tint the channel of the tint, {@code 0..255}
     * @param amount the strength
     * @return the curve
     */
    private static ToneCurve multiply(int tint, double amount) {
        var factor = 1 - amount + amount * tint / 255.0;
        return ToneCurve.of(x -> x * factor);
    }

    /**
     * Returns a mixing matrix that writes one weighted sum of red, green and blue to all three.
     *
     * @param r the red weight in units of {@code 2^-16}
     * @param g the green weight
     * @param b the blue weight
     * @return the matrix, with the rounding term
     */
    private static int[] uniform(int r, int g, int b) {
        return new int[]{r, g, b, 0x8000, r, g, b, 0x8000, r, g, b, 0x8000};
    }

    /**
     * Converts a weight to 16-bit fixed point.
     *
     * @param weight the real weight, typically from 0 to 1
     * @return the weight in units of {@code 2^-16}, rounded
     */
    private static int fixed(double weight) {
        return (int) Math.round(weight * 65536);
    }

    /**
     * Checks an enhancement factor.
     *
     * @param factor the enhancement factor to check
     * @throws IllegalArgumentException if it is outside {@code 0..100} or not finite
     */
    private static void requireFactor(double factor) {
        if (!(factor >= 0 && factor <= 100)) {
            throw new IllegalArgumentException("factor " + factor + " is outside 0..100");
        }
    }

    /**
     * Checks a cutoff percentage.
     *
     * @param cutoff the percentage
     * @throws IllegalArgumentException if it is outside {@code 0..50} or not finite
     */
    private static void requireCutoff(double cutoff) {
        if (!(cutoff >= 0 && cutoff <= 50)) {
            throw new IllegalArgumentException("cutoff " + cutoff + " is outside 0..50");
        }
    }

    /**
     * Converts a picture to packed RGBA, runs an adjustment and translates failures.
     *
     * @param picture the picture to convert and adjust
     * @param adjustment the adjustment of the packed picture
     * @return the adjusted picture
     */
    private static Picture run(Picture picture, java.util.function.UnaryOperator<Picture> adjustment) {
        Objects.requireNonNull(picture, "picture");
        try {
            return adjustment.apply(Canvas.toPacked(picture));
        } catch (RuntimeException exception) {
            throw OpChecks.translate(exception);
        }
    }
}
