package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Resamples {@link ChannelPicture}s with a separable filter in double-precision floating point, every channel
 * independently.
 *
 * <p>The weights are the taps of {@link ResampleWeights} with {@value DoubleResampler#WEIGHT_BITS} fractional
 * bits converted exactly to doubles, as {@link DoubleResampler} uses them. Each source row is filtered
 * horizontally once per band into a ring of rows, and each output row sums the ring rows its vertical taps
 * cover. When the layout has a straight alpha channel that is not opaque everywhere, the color channels are
 * filtered premultiplied by alpha and divided by the filtered alpha, a pixel whose filtered alpha is not
 * positive getting zero color. Integer samples are rounded to nearest, halves up, and clamped to their range;
 * floating-point samples are neither rounded nor clamped. {@link ResizeFilter#NEAREST} copies the selected
 * samples exactly. The output does not depend on the band split.
 */
final class ChannelResampler {
    /**
     * Prevents instantiation.
     */
    private ChannelResampler() {
        throw new AssertionError();
    }

    /**
     * Resamples a window of a channel picture.
     *
     * @param picture the source
     * @param windowX the horizontal source window
     * @param windowY the vertical source window
     * @param outWidth the output width
     * @param outHeight the output height
     * @param filter the reconstruction filter
     * @return the resampled picture, of the source's type and layout
     */
    static ChannelPicture resample(ChannelPicture picture, Resampler.Window windowX, Resampler.Window windowY,
                                   int outWidth, int outHeight, ResizeFilter filter) {
        var channels = picture.channelCount();
        OpChecks.checkArray((long) outWidth * outHeight * channels);
        var out = ChannelOps.allocateLike(picture, outWidth, outHeight, picture.layout());
        var horizontal = windowX.weights(filter, picture.width(), outWidth, DoubleResampler.WEIGHT_BITS);
        var vertical = windowY.weights(filter, picture.height(), outHeight, DoubleResampler.WEIGHT_BITS);
        if (filter == ResizeFilter.NEAREST) {
            nearest(picture, horizontal.starts(), vertical.starts(), out);
            return out;
        }
        var layout = picture.layout();
        var alpha = layout.alphaIndex();
        var premultiplied = alpha >= 0 && layout.channel(alpha).type() == ChannelType.ALPHA
                && !ChannelOps.isOpaque(picture);
        var colors = premultiplied ? layout.colorChannels() : 0;
        var horizontalWeights = DoubleResampler.toDouble(horizontal.weights());
        var verticalWeights = DoubleResampler.toDouble(vertical.weights());
        var max = picture instanceof Channel8Picture ? 255.0 : picture instanceof Channel16Picture ? 65535.0 : 1.0;
        var job = new Job(picture, out, channels, alpha, colors, max, horizontal, horizontalWeights, vertical,
                verticalWeights);
        var bands = ParallelBands.bands(outHeight, (long) vertical.maxTaps() * outWidth * outHeight * channels
                + (long) horizontal.maxTaps() * outWidth * picture.height() * channels);
        ParallelBands.run(bands, band -> rows(job, ParallelBands.start(band, bands, outHeight),
                ParallelBands.start(band + 1, bands, outHeight)));
        return out;
    }

    /**
     * Describes one resampling.
     *
     * @param source the source picture
     * @param target the destination picture
     * @param channels the samples per pixel
     * @param alpha the index of the alpha channel, or {@code -1}
     * @param colors the number of leading channels filtered premultiplied by alpha, 0 for none
     * @param max the nominal maximum of a sample: 255, 65535 or 1
     * @param horizontal the horizontal taps
     * @param horizontalWeights the horizontal weights as doubles
     * @param vertical the vertical taps
     * @param verticalWeights the vertical weights as doubles
     */
    private record Job(ChannelPicture source, ChannelPicture target, int channels, int alpha, int colors, double max,
                       ResampleWeights horizontal, double[] horizontalWeights, ResampleWeights vertical,
                       double[] verticalWeights) {
    }

    /**
     * Computes the output rows {@code [top, bottom)}.
     *
     * @param job the resampling
     * @param top the first output row
     * @param bottom the output row after the last one
     */
    private static void rows(Job job, int top, int bottom) {
        var channels = job.channels();
        var vertical = job.vertical();
        var outWidth = job.target().width();
        var ringSize = vertical.maxTaps();
        var ring = new double[ringSize][outWidth * channels];
        var ringRows = new int[ringSize];
        Arrays.fill(ringRows, -1);
        var line = new double[job.source().width() * channels];
        var sums = new double[outWidth * channels];
        var starts = vertical.starts();
        var counts = vertical.counts();
        var weights = job.verticalWeights();
        var stride = vertical.stride();
        for (var y = top; y < bottom; y++) {
            Arrays.fill(sums, 0);
            for (var k = 0; k < counts[y]; k++) {
                var sourceRow = starts[y] + k;
                var slot = sourceRow % ringSize;
                if (ringRows[slot] != sourceRow) {
                    filterRow(job, sourceRow, line, ring[slot]);
                    ringRows[slot] = sourceRow;
                }
                var weight = weights[y * stride + k];
                var filtered = ring[slot];
                for (var i = 0; i < sums.length; i++) {
                    sums[i] += weight * filtered[i];
                }
            }
            store(job, sums, y);
        }
    }

    /**
     * Loads one source row, premultiplied when asked, and filters it horizontally.
     *
     * @param job the resampling
     * @param y the source row
     * @param line the scratch row of source samples
     * @param out the filtered row, {@code outWidth * channels} values
     */
    private static void filterRow(Job job, int y, double[] line, double[] out) {
        var source = job.source();
        var channels = job.channels();
        var width = source.width();
        var offset = y * source.stride();
        var count = width * channels;
        switch (source) {
            case Channel8Picture eight -> {
                var pixels = eight.pixels();
                for (var i = 0; i < count; i++) {
                    line[i] = pixels[offset + i] & 0xFF;
                }
            }
            case Channel16Picture sixteen -> {
                var pixels = sixteen.pixels();
                for (var i = 0; i < count; i++) {
                    line[i] = pixels[offset + i] & 0xFFFF;
                }
            }
            case ChannelFloatPicture floating -> {
                var pixels = floating.pixels();
                for (var i = 0; i < count; i++) {
                    line[i] = pixels[offset + i];
                }
            }
        }
        var colors = job.colors();
        if (colors > 0) {
            var alpha = job.alpha();
            var scale = 1.0 / job.max();
            for (var at = 0; at < count; at += channels) {
                var factor = line[at + alpha] * scale;
                for (var c = 0; c < colors; c++) {
                    line[at + c] *= factor;
                }
            }
        }
        var horizontal = job.horizontal();
        var starts = horizontal.starts();
        var counts = horizontal.counts();
        var weights = job.horizontalWeights();
        var stride = horizontal.stride();
        var outWidth = out.length / channels;
        for (var x = 0; x < outWidth; x++) {
            var base = starts[x] * channels;
            var taps = counts[x];
            var weightRow = x * stride;
            for (var c = 0; c < channels; c++) {
                var sum = 0.0;
                for (var k = 0; k < taps; k++) {
                    sum += weights[weightRow + k] * line[base + k * channels + c];
                }
                out[x * channels + c] = sum;
            }
        }
    }

    /**
     * Writes one output row.
     *
     * @param job the resampling
     * @param sums the filtered values, premultiplied when asked
     * @param y the output row
     */
    private static void store(Job job, double[] sums, int y) {
        var channels = job.channels();
        var colors = job.colors();
        if (colors > 0) {
            var alpha = job.alpha();
            var max = job.max();
            for (var at = 0; at < sums.length; at += channels) {
                var filteredAlpha = sums[at + alpha];
                var factor = filteredAlpha > 0 ? max / filteredAlpha : 0;
                for (var c = 0; c < colors; c++) {
                    sums[at + c] *= factor;
                }
            }
        }
        var target = job.target();
        var offset = y * target.stride();
        switch (target) {
            case Channel8Picture eight -> {
                var pixels = eight.pixels();
                for (var i = 0; i < sums.length; i++) {
                    pixels[offset + i] = (byte) Math.clamp(Math.round(sums[i]), 0, 255);
                }
            }
            case Channel16Picture sixteen -> {
                var pixels = sixteen.pixels();
                for (var i = 0; i < sums.length; i++) {
                    pixels[offset + i] = (short) Math.clamp(Math.round(sums[i]), 0, 65535);
                }
            }
            case ChannelFloatPicture floating -> {
                var pixels = floating.pixels();
                for (var i = 0; i < sums.length; i++) {
                    pixels[offset + i] = (float) sums[i];
                }
            }
        }
    }

    /**
     * Copies the source pixel selected for every output pixel.
     *
     * @param source the source picture
     * @param columns the source column of each output column
     * @param rows the source row of each output row
     * @param target the destination picture
     */
    private static void nearest(ChannelPicture source, int[] columns, int[] rows, ChannelPicture target) {
        var channels = source.channelCount();
        var src = ChannelOps.samples(source);
        var dst = ChannelOps.samples(target);
        var width = target.width();
        var height = target.height();
        var bands = ParallelBands.bands(height, (long) width * height * channels);
        ParallelBands.run(bands, band -> {
            var top = ParallelBands.start(band, bands, height);
            var bottom = ParallelBands.start(band + 1, bands, height);
            for (var y = top; y < bottom; y++) {
                var rowStart = rows[y] * source.stride();
                var d = y * target.stride();
                for (var x = 0; x < width; x++, d += channels) {
                    System.arraycopy(src, rowStart + columns[x] * channels, dst, d, channels);
                }
            }
        });
    }
}
