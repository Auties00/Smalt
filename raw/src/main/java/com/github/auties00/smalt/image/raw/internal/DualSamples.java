package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;

/**
 * Merges the two readings of each photosite of a color filter array that holds two samples per
 * photosite into one value: the two photodiodes of different sensitivity of a Fujifilm SuperCCD SR
 * sensor, or the two exposures or the two equal halves of a pair of a Fujifilm EXR sensor in its DR
 * or SN mode.
 *
 * <p>The merge works on linear reference values, in which each sample's black is 0 and its white 1.
 * It first measures how the samples relate: the sample of the larger sum over the photosites where
 * both are between {@value #FLOOR} and {@value #CEILING} is the primary one, the other the secondary
 * one, and for each color plane the ratio of the primary's sum to the secondary's over the
 * photosites whose primary lies between {@value #RATIO_LOW} and {@value #CEILING} is the factor that
 * brings the secondary to the primary's scale. A sample whose largest value is reached by at least
 * one photosite in 100000 (and 16) saturates there, below its white level. When every factor is
 * below {@value #SAME_EXPOSURE}, the two samples read the same light at the same sensitivity and
 * the merged value is their mean, or the one not clipped (within 2% of its saturation) when the
 * other is. Otherwise the primary sample holds the shadows and the secondary the highlights: the
 * merged value is the primary below {@value #BLEND_START} of the primary's saturation, the
 * secondary brought to scale above {@value #BLEND_END} of it, and a smooth blend between, a
 * clipped secondary counting as the top of the range.
 *
 * <p>The merged values are divided by the top of the range they reach, the secondary's saturation
 * brought to scale by the smallest factor (at least 1), so that every color clips at 1 at the same
 * light; {@link Merged#stops()} is the exposure, in stops, that restores the primary's scale.
 *
 * @implNote This implementation sums the selected values in fixed point, so the factors do not
 * depend on how the image is split between threads. The blend thresholds follow Adobe DNG
 * Converter's demosaiced files of SuperCCD SR cameras, which use the primary photodiodes up to
 * about two thirds of their white level and the secondary ones from nine tenths.
 */
final class DualSamples {
    /**
     * The lowest value of either sample that counts when the primary sample is chosen.
     */
    static final double FLOOR = 0.002;

    /**
     * The highest value of either sample that counts when the samples are related.
     */
    static final double CEILING = 0.85;

    /**
     * The lowest primary value that counts when the factors are measured.
     */
    static final double RATIO_LOW = 0.3;

    /**
     * The factor below which the two samples count as the same exposure.
     */
    static final double SAME_EXPOSURE = 1.5;

    /**
     * The share of the primary's saturation where the blend toward the secondary sample starts.
     */
    static final double BLEND_START = 0.70;

    /**
     * The share of the primary's saturation where only the secondary sample is used.
     */
    static final double BLEND_END = 0.90;

    /**
     * The share of a sample's saturation from which it counts as clipped.
     */
    private static final double CLIPPED = 0.98;

    /**
     * The fixed-point scale of the sums.
     */
    private static final double FIXED = 1 << 24;

    /**
     * The values a merge measures and writes.
     *
     * @param image the merged image, of one sample per photosite
     * @param stops the exposure, in stops, that brings the merged values back to the primary sample's scale
     * @param primary the sample, 0 or 1, read as the primary one
     * @param factors the factor of each color plane that brings the secondary sample to the primary's scale
     */
    record Merged(LinearImage image, double stops, int primary, double[] factors) {
    }

    /**
     * Prevents instantiation.
     */
    private DualSamples() {
        throw new AssertionError();
    }

    /**
     * Merges the two samples of every photosite of an image.
     *
     * @param image the linear image, of two samples per photosite and a color filter array
     * @param limits the resource bounds
     * @return the merged image and its exposure
     * @throws IllegalArgumentException if the image does not hold two samples per photosite of a color filter array
     */
    static Merged merge(LinearImage image, MediaLimits limits) {
        if (image.samplesPerPixel != 2 || image.cfa == null || image.planes == null) {
            throw new IllegalArgumentException("not an image of two samples per photosite");
        }
        var width = image.width;
        var height = image.height;
        var data = image.data;
        var colors = image.colors;
        var count = (long) width * height;
        var bands = ParallelBands.bands(height, count * 2);
        // pass 1: the primary sample and the largest value of each sample
        var totals = new long[bands][2];
        var maxima = new float[bands][2];
        ParallelBands.run(bands, band -> {
            var last = ParallelBands.start(band + 1, bands, height);
            var sums = totals[band];
            var top = maxima[band];
            top[0] = Float.NEGATIVE_INFINITY;
            top[1] = Float.NEGATIVE_INFINITY;
            for (var y = ParallelBands.start(band, bands, height); y < last; y++) {
                for (var index = y * width * 2; index < (y + 1) * width * 2; index += 2) {
                    var a = data[index];
                    var b = data[index + 1];
                    top[0] = Math.max(top[0], a);
                    top[1] = Math.max(top[1], b);
                    if (a >= FLOOR && a <= CEILING && b >= FLOOR && b <= CEILING) {
                        sums[0] += Math.round(a * FIXED);
                        sums[1] += Math.round(b * FIXED);
                    }
                }
            }
        });
        var sumA = 0L;
        var sumB = 0L;
        var maxA = Float.NEGATIVE_INFINITY;
        var maxB = Float.NEGATIVE_INFINITY;
        for (var band = 0; band < bands; band++) {
            sumA += totals[band][0];
            sumB += totals[band][1];
            maxA = Math.max(maxA, maxima[band][0]);
            maxB = Math.max(maxB, maxima[band][1]);
        }
        var primary = sumA >= sumB ? 0 : 1;
        var secondary = 1 - primary;
        var overall = sumA > 0 && sumB > 0 ? primary == 0 ? (double) sumA / sumB : (double) sumB / sumA : 1;
        var primaryMax = primary == 0 ? maxA : maxB;
        var secondaryMax = primary == 0 ? maxB : maxA;
        // pass 2: the factor of each color plane and the photosites at each sample's largest value
        var planeSums = new long[bands][colors * 2];
        var peaks = new long[bands][2];
        var cfaWidth = image.cfa.width();
        var cfaHeight = image.cfa.height();
        var planes = image.planes;
        ParallelBands.run(bands, band -> {
            var last = ParallelBands.start(band + 1, bands, height);
            var sums = planeSums[band];
            var at = peaks[band];
            for (var y = ParallelBands.start(band, bands, height); y < last; y++) {
                var phase = (y % cfaHeight) * cfaWidth;
                for (var x = 0; x < width; x++) {
                    var index = (y * width + x) * 2;
                    var p = data[index + primary];
                    var s = data[index + secondary];
                    if (p == primaryMax) {
                        at[0]++;
                    }
                    if (s == secondaryMax) {
                        at[1]++;
                    }
                    if (p >= RATIO_LOW && p <= CEILING && s >= FLOOR) {
                        var plane = planes[phase + x % cfaWidth];
                        sums[plane * 2] += Math.round(p * FIXED);
                        sums[plane * 2 + 1] += Math.round(s * FIXED);
                    }
                }
            }
        });
        var factors = new double[colors];
        var primaryPeak = 0L;
        var secondaryPeak = 0L;
        for (var band = 0; band < bands; band++) {
            primaryPeak += peaks[band][0];
            secondaryPeak += peaks[band][1];
        }
        for (var plane = 0; plane < colors; plane++) {
            var p = 0L;
            var s = 0L;
            for (var band = 0; band < bands; band++) {
                p += planeSums[band][plane * 2];
                s += planeSums[band][plane * 2 + 1];
            }
            factors[plane] = p > 0 && s > 0 ? (double) p / s : overall;
        }
        var plateau = Math.max(16, count / 100000);
        var primaryWhite = saturation(primaryMax, primaryPeak, plateau);
        var secondaryWhite = saturation(secondaryMax, secondaryPeak, plateau);
        var same = true;
        var smallest = Double.MAX_VALUE;
        for (var factor : factors) {
            same &= factor < SAME_EXPOSURE;
            smallest = Math.min(smallest, factor);
        }
        var top = Math.max(1, smallest * secondaryWhite);
        LimitChecks.checkAllocation(limits, count * 4);
        var merged = new float[(int) count];
        var start = BLEND_START * primaryWhite;
        var span = (BLEND_END - BLEND_START) * primaryWhite;
        var primaryClip = CLIPPED * primaryWhite;
        var secondaryClip = CLIPPED * secondaryWhite;
        var scale = 1 / top;
        var mean = same;
        // pass 3: the merged values
        ParallelBands.run(bands, band -> {
            var last = ParallelBands.start(band + 1, bands, height);
            for (var y = ParallelBands.start(band, bands, height); y < last; y++) {
                var phase = (y % cfaHeight) * cfaWidth;
                for (var x = 0; x < width; x++) {
                    var index = y * width + x;
                    var p = (double) data[index * 2 + primary];
                    var s = (double) data[index * 2 + secondary];
                    var factor = factors[planes[phase + x % cfaWidth]];
                    var clipped = s >= secondaryClip;
                    var scaled = clipped ? top : Math.min(s, secondaryWhite) * factor;
                    double value;
                    if (mean) {
                        if (p >= primaryClip) {
                            value = clipped ? top : scaled;
                        } else {
                            value = clipped ? p : (p + scaled) / 2;
                        }
                    } else {
                        var t = Math.clamp((p - start) / span, 0, 1);
                        var weight = t * t * (3 - 2 * t);
                        value = (1 - weight) * p + weight * scaled;
                    }
                    merged[index] = (float) (Math.min(value, top) * scale);
                }
            }
        });
        var result = new LinearImage(width, height, 1, merged, image.cfa, planes);
        return new Merged(result, StrictMath.log(top) / StrictMath.log(2), primary, factors);
    }

    /**
     * {@return the filter array of a staggered grid that interleaves the columns of two images of the same filter
     * array, column by column: each column of the array becomes two columns of the same colors}
     *
     * @param pattern the array of both images
     */
    static CfaPattern interleaved(CfaPattern pattern) {
        var colors = new ArrayList<CfaColor>();
        for (var row = 0; row < pattern.height(); row++) {
            for (var column = 0; column < 2 * pattern.width(); column++) {
                colors.add(pattern.color(row, column / 2));
            }
        }
        return CfaPattern.of(2 * pattern.width(), pattern.height(), colors);
    }

    /**
     * Returns the level at which a sample saturates: its largest value when enough photosites reach it, below the
     * white level, otherwise the white level.
     *
     * @param maximum the sample's largest value
     * @param reached the photosites at that value
     * @param plateau the photosites needed
     * @return the saturation level, in linear reference values
     */
    private static double saturation(float maximum, long reached, long plateau) {
        return maximum > 0 && maximum < 1 && reached >= plateau ? maximum : 1;
    }
}
