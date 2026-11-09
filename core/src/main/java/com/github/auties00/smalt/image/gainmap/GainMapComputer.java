/* Ports libavif 1.3.0 (src/gainmap.c: avifRGBImageComputeGainMap, avifFindMinMaxWithoutOutliers). Copyright 2023
 * Google LLC; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.gainmap;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;

/**
 * Computes the gain map that turns one rendition of an image into another.
 */
final class GainMapComputer {
    /**
     * The smallest ratio whose logarithm is taken.
     */
    private static final double EPSILON = 1e-10;

    /**
     * The width of a histogram bucket of log2 gains.
     */
    private static final double BUCKET = 0.01;

    /**
     * The share of the pixels on each side that may be left outside the gain range.
     */
    private static final double OUTLIERS = 0.001;

    /**
     * The most histogram buckets.
     */
    private static final int MAX_BUCKETS = 10000;

    private GainMapComputer() {
        throw new AssertionError();
    }

    /**
     * Computes a gain map.
     *
     * @param base the base rendition
     * @param alternate the alternate rendition, of the same size
     * @param options the layout and encoding of the gain map
     * @return the gain map
     */
    static GainMap compute(DecodedImage base, DecodedImage alternate, GainMapComputeOptions options) {
        var referenceWhite = options.referenceWhite();
        var baseSource = LinearSource.of(base, referenceWhite);
        var alternateSource = LinearSource.of(alternate, referenceWhite);
        var width = baseSource.width;
        var height = baseSource.height;
        if (alternateSource.width != width || alternateSource.height != height) {
            throw new IllegalArgumentException("the renditions are " + width + "x" + height + " and "
                    + alternateSource.width + "x" + alternateSource.height);
        }
        var math = options.useBaseColorSpace() ? baseSource.primaries : alternateSource.primaries;
        var baseToMath = baseSource.primaries.same(math) ? null : baseSource.primaries.conversion(math);
        var alternateToMath = alternateSource.primaries.same(math) ? null : alternateSource.primaries.conversion(math);
        var luminance = math.luminance();
        var planes = options.multichannel() ? 3 : 1;
        var gains = new float[planes][width * height];
        var baseOffset = options.baseOffset();
        var alternateOffset = options.alternateOffset();
        var bands = ParallelBands.bands(height, (long) width * height * 4);
        var baseMaxima = new double[bands];
        var alternateMaxima = new double[bands];
        ParallelBands.run(bands, band -> {
            var baseRow = new float[3][width];
            var alternateRow = new float[3][width];
            var baseLinear = new double[3];
            var alternateLinear = new double[3];
            var baseMax = 0.0;
            var alternateMax = 0.0;
            for (var y = ParallelBands.start(band, bands, height); y < ParallelBands.start(band + 1, bands, height);
                 y++) {
                baseSource.row(y, baseRow[0], baseRow[1], baseRow[2]);
                alternateSource.row(y, alternateRow[0], alternateRow[1], alternateRow[2]);
                for (var x = 0; x < width; x++) {
                    convert(baseRow, x, baseToMath, baseLinear);
                    convert(alternateRow, x, alternateToMath, alternateLinear);
                    for (var plane = 0; plane < planes; plane++) {
                        double b;
                        double a;
                        if (planes == 1) {
                            b = luminance[0] * baseLinear[0] + luminance[1] * baseLinear[1]
                                    + luminance[2] * baseLinear[2];
                            a = luminance[0] * alternateLinear[0] + luminance[1] * alternateLinear[1]
                                    + luminance[2] * alternateLinear[2];
                        } else {
                            b = baseLinear[plane];
                            a = alternateLinear[plane];
                        }
                        baseMax = Math.max(baseMax, b);
                        alternateMax = Math.max(alternateMax, a);
                        var ratio = Math.max(a + alternateOffset, EPSILON) / Math.max(b + baseOffset, EPSILON);
                        gains[plane][y * width + x] = (float) (StrictMath.log(Math.max(ratio, EPSILON))
                                / StrictMath.log(2));
                    }
                }
            }
            baseMaxima[band] = baseMax;
            alternateMaxima[band] = alternateMax;
        });
        var baseMax = 1.0;
        var alternateMax = 1.0;
        for (var band = 0; band < bands; band++) {
            baseMax = Math.max(baseMax, baseMaxima[band]);
            alternateMax = Math.max(alternateMax, alternateMaxima[band]);
        }
        var baseHeadroom = log2(baseMax);
        var alternateHeadroom = options.alternateHeadroom().orElse(log2(alternateMax));
        if (alternateHeadroom < baseHeadroom) {
            for (var plane : gains) {
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = -plane[i];
                }
            }
        }
        var scale = options.scale();
        var mapWidth = (width + scale - 1) / scale;
        var mapHeight = (height + scale - 1) / scale;
        if (scale > 1) {
            for (var plane = 0; plane < planes; plane++) {
                gains[plane] = downscale(gains[plane], width, height, scale, mapWidth, mapHeight);
            }
        }
        var channels = new ArrayList<GainMapChannel>(planes);
        var samples = new byte[planes][mapWidth * mapHeight];
        for (var plane = 0; plane < planes; plane++) {
            var range = range(gains[plane]);
            var minimum = range[0];
            var maximum = range[1];
            channels.add(new GainMapChannel(minimum, maximum, options.gamma(), baseOffset, alternateOffset));
            var span = maximum - minimum;
            var values = gains[plane];
            for (var i = 0; i < values.length; i++) {
                if (span <= 0) {
                    continue;
                }
                var normalized = Math.min(Math.max((values[i] - minimum) / span, 0), 1);
                if (options.gamma() != 1) {
                    normalized = StrictMath.pow(normalized, options.gamma());
                }
                samples[plane][i] = (byte) (int) (normalized * 255 + 0.5);
            }
        }
        var metadata = new GainMapMetadata(baseHeadroom, alternateHeadroom, channels, options.useBaseColorSpace());
        return new GainMap(metadata, picture(samples, mapWidth, mapHeight), alternate.colorProfile(),
                alternate.colorProfile().flatMap(profile -> profile.colorInfo())
                        .flatMap(ColorInfo::contentLightLevel));
    }

    /**
     * Reads one pixel of a row and converts it to the gain map color space.
     *
     * @param row the red, green and blue rows
     * @param x the column
     * @param matrix the conversion, or {@code null} for none
     * @param out the converted pixel
     */
    private static void convert(float[][] row, int x, double[] matrix, double[] out) {
        double r = row[0][x];
        double g = row[1][x];
        double b = row[2][x];
        if (matrix == null) {
            out[0] = r;
            out[1] = g;
            out[2] = b;
            return;
        }
        out[0] = matrix[0] * r + matrix[1] * g + matrix[2] * b;
        out[1] = matrix[3] * r + matrix[4] * g + matrix[5] * b;
        out[2] = matrix[6] * r + matrix[7] * g + matrix[8] * b;
    }

    /**
     * {@return the base-2 logarithm of a value of at least 1, 0 for values below}
     */
    private static double log2(double value) {
        return value > 1 ? StrictMath.log(value) / StrictMath.log(2) : 0;
    }

    /**
     * Averages blocks of log2 gains.
     *
     * @param gains the gains of the full-size image
     * @param width the image width
     * @param height the image height
     * @param scale the block size
     * @param mapWidth the gain map width
     * @param mapHeight the gain map height
     * @return the averaged gains
     */
    private static float[] downscale(float[] gains, int width, int height, int scale, int mapWidth, int mapHeight) {
        var result = new float[mapWidth * mapHeight];
        for (var my = 0; my < mapHeight; my++) {
            for (var mx = 0; mx < mapWidth; mx++) {
                var sum = 0.0;
                var count = 0;
                for (var y = my * scale; y < Math.min(height, my * scale + scale); y++) {
                    for (var x = mx * scale; x < Math.min(width, mx * scale + scale); x++) {
                        sum += gains[y * width + x];
                        count++;
                    }
                }
                result[my * mapWidth + mx] = (float) (sum / count);
            }
        }
        return result;
    }

    /**
     * Finds the range of log2 gains, leaving out at most 0.1% of the values on each side when they are isolated
     * in the histogram.
     *
     * @param gains the log2 gains
     * @return the minimum and the maximum
     */
    static double[] range(float[] gains) {
        double minimum = gains[0];
        double maximum = gains[0];
        for (var gain : gains) {
            minimum = Math.min(minimum, gain);
            maximum = Math.max(maximum, gain);
        }
        var outliers = (int) Math.round(gains.length * OUTLIERS / 2);
        if (maximum - minimum <= BUCKET * 2 || outliers == 0) {
            return new double[]{minimum, maximum};
        }
        var buckets = (int) Math.min(Math.ceil((maximum - minimum) / BUCKET), MAX_BUCKETS);
        var histogram = new int[buckets];
        for (var gain : gains) {
            var clamped = Math.min(Math.max(gain, minimum), maximum);
            histogram[Math.min((int) Math.round((clamped - minimum) / (maximum - minimum) * buckets), buckets - 1)]++;
        }
        var low = minimum;
        var left = 0;
        for (var i = 0; i < buckets; i++) {
            left += histogram[i];
            if (left > outliers) {
                break;
            }
            if (histogram[i] == 0) {
                low = (i + 1) * (maximum - minimum) / buckets + minimum;
            }
        }
        var high = maximum;
        var right = 0;
        for (var i = buckets - 1; i >= 0; i--) {
            right += histogram[i];
            if (right > outliers) {
                break;
            }
            if (histogram[i] == 0) {
                high = i * (maximum - minimum) / buckets + minimum;
            }
        }
        return new double[]{low, high};
    }

    /**
     * Builds the gain map picture.
     *
     * @param samples one or three planes of 8-bit samples
     * @param width the gain map width
     * @param height the gain map height
     * @return a monochrome Y'CbCr picture for one plane, an opaque RGB picture for three
     */
    private static Picture picture(byte[][] samples, int width, int height) {
        if (samples.length == 1) {
            return YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.MONOCHROME)
                    .color(ColorInfo.JPEG).luma(samples[0], width).build();
        }
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            var at = y * picture.stride();
            for (var x = 0; x < width; x++, at += 4) {
                var i = y * width + x;
                pixels[at] = samples[0][i];
                pixels[at + 1] = samples[1][i];
                pixels[at + 2] = samples[2][i];
                pixels[at + 3] = (byte) 0xFF;
            }
        }
        return picture;
    }
}
