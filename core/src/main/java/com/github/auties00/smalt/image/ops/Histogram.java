/* Ports Pillow (src/PIL/ImageStat.py, the statistics of a histogram). Copyright Secret Labs AB, Fredrik Lundh and
 * contributors, and Jeffrey A. Clark and contributors; MIT-CMU License. Modified for Smalt. */
package com.github.auties00.smalt.image.ops;

import java.util.Arrays;
import java.util.Objects;

/**
 * Holds the counts of every sample value of each channel of a picture, as {@link ImageOps#histogram} computes
 * them.
 *
 * <p>The channels are red, green and blue, then alpha when the picture reports alpha. A histogram of an 8-bit
 * picture has 256 bins per channel, and one of a 16-bit picture 65536.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class Histogram {
    /**
     * The counts, {@code bins} per channel, channel after channel.
     */
    private final long[] counts;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The number of bins per channel.
     */
    private final int bins;

    /**
     * The number of pixels counted.
     */
    private final long pixels;

    /**
     * Wraps counts owned by the new instance.
     *
     * @param counts the counts, {@code bins} per channel; not copied
     * @param channels the number of channels
     * @param bins the number of bins per channel
     * @param pixels the number of pixels counted
     */
    Histogram(long[] counts, int channels, int bins, long pixels) {
        this.counts = counts;
        this.channels = channels;
        this.bins = bins;
        this.pixels = pixels;
    }

    /**
     * {@return the number of channels: 3, or 4 when the picture reports alpha}
     */
    public int channels() {
        return channels;
    }

    /**
     * {@return the number of bins per channel, one per sample value: 256 or 65536}
     */
    public int bins() {
        return bins;
    }

    /**
     * {@return the number of pixels counted: every pixel, or those a mask selects}
     */
    public long pixels() {
        return pixels;
    }

    /**
     * Returns the number of samples of one channel that have a value.
     *
     * @param channel the channel, from 0 to {@link #channels()} minus 1
     * @param value the sample value, from 0 to {@link #bins()} minus 1
     * @return the count
     * @throws IndexOutOfBoundsException if {@code channel} or {@code value} is out of range
     */
    public long count(int channel, int value) {
        Objects.checkIndex(channel, channels);
        Objects.checkIndex(value, bins);
        return counts[channel * bins + value];
    }

    /**
     * Returns the counts of every value of one channel.
     *
     * @param channel the channel, from 0 to {@link #channels()} minus 1
     * @return a new array of {@link #bins()} counts, indexed by sample value
     * @throws IndexOutOfBoundsException if {@code channel} is out of range
     */
    public long[] counts(int channel) {
        Objects.checkIndex(channel, channels);
        return Arrays.copyOfRange(counts, channel * bins, (channel + 1) * bins);
    }

    /**
     * Computes the statistics of one channel.
     *
     * <p>The sums are accumulated in double precision from the smallest value to the largest, each bin adding
     * {@code v * count} to the sum and {@code v * v * count} to the sum of squares.
     *
     * @param channel the channel, from 0 to {@link #channels()} minus 1
     * @return the statistics
     * @throws IndexOutOfBoundsException if {@code channel} is out of range
     */
    public ChannelStatistics statistics(int channel) {
        Objects.checkIndex(channel, channels);
        var base = channel * bins;
        long count = 0;
        double sum = 0;
        double sumOfSquares = 0;
        var minimum = -1;
        var maximum = 0;
        for (var v = 0; v < bins; v++) {
            var n = counts[base + v];
            if (n == 0) {
                continue;
            }
            if (minimum < 0) {
                minimum = v;
            }
            maximum = v;
            count += n;
            sum += (double) (v * n);
            sumOfSquares += (double) ((long) v * v) * n;
        }
        if (count == 0) {
            return new ChannelStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        var half = count / 2;
        var median = 0;
        long seen = 0;
        for (var v = 0; v < bins; v++) {
            seen += counts[base + v];
            if (seen > half) {
                median = v;
                break;
            }
        }
        var mean = sum / count;
        // rounding can leave a constant channel a tiny negative variance
        var variance = Math.max(0, (sumOfSquares - sum * sum / count) / count);
        return new ChannelStatistics(count, minimum, maximum, median, sum, sumOfSquares, mean,
                Math.sqrt(sumOfSquares / count), variance, Math.sqrt(variance));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Histogram histogram && channels == histogram.channels && bins == histogram.bins
                && pixels == histogram.pixels && Arrays.equals(counts, histogram.counts);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(counts) * 31 + channels;
    }

    @Override
    public String toString() {
        return "Histogram[channels=" + channels + ", bins=" + bins + ", pixels=" + pixels + "]";
    }
}
