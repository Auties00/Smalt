package com.github.auties00.smalt.image.ops;

/**
 * Holds the statistics of one channel of a picture, computed from its {@link Histogram}.
 *
 * <p>Sample values are at the depth of the histogram, {@code 0..255} or {@code 0..65535}. When no pixel was
 * counted, {@code count} is zero and every other component is zero too. The components equal those of Pillow's
 * {@code ImageStat.Stat} for an 8-bit channel.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param count the number of samples counted
 * @param minimum the smallest sample value
 * @param maximum the largest sample value
 * @param median the smallest value {@code v} such that more than {@code count / 2} (rounded down) samples are at
 * most {@code v}
 * @param sum the sum of the samples
 * @param sumOfSquares the sum of the squares of the samples
 * @param mean the mean, {@code sum / count}
 * @param rms the root mean square, {@code sqrt(sumOfSquares / count)}
 * @param variance the population variance, {@code (sumOfSquares - sum * sum / count) / count}
 * @param standardDeviation the square root of the variance
 */
public record ChannelStatistics(long count, int minimum, int maximum, int median, double sum, double sumOfSquares,
                                double mean, double rms, double variance, double standardDeviation) {
}
