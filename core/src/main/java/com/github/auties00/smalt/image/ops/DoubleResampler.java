package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Resamples pictures with a separable filter in double-precision floating point: planes of 16-bit
 * samples, packed 16-bit RGBA pixels and, in linear light, packed 8-bit RGBA pixels.
 *
 * <p>The weights are the taps of {@link ResampleWeights} with {@value #WEIGHT_BITS} fractional
 * bits, converted exactly to doubles, so the weights of every output sample sum exactly to one.
 * Source rows are converted once into a ring of rows of doubles, one plane per channel: the sample
 * values themselves, decoded to linear light with the sRGB transfer function when asked, and
 * multiplied by their alpha for premultiplied filtering. The vertical pass filters the rows each
 * output row's taps cover into one row; blocks of {@link DoubleResampleKernel#lanes()}
 * output rows are interleaved lane by lane for the horizontal pass, as {@link Resampler} does.
 * Only the final value is rounded: to nearest and clamped to the sample range, or, in linear
 * light, encoded to the nearest sample. Premultiplied colors are divided by the filtered alpha
 * first, and a pixel whose filtered alpha is not positive becomes transparent black.
 *
 * <p>Blocks of output rows are independent, so large operations split them into bands computed
 * on several threads by {@link ParallelBands}; every sum is formed in the same order whatever the
 * split and whatever the kernel implementation, so the output depends on neither.
 */
final class DoubleResampler {
    /**
     * The fractional bits of the fixed-point taps the weights are converted from.
     */
    static final int WEIGHT_BITS = 24;

    /**
     * The largest ring of converted rows, in bytes, before the vertical pass switches to
     * accumulating over chunks of rows.
     */
    private static final long RING_BYTES = 32L << 20;

    /**
     * The source kind of a plane of 16-bit samples.
     */
    private static final int PLANE16 = 0;

    /**
     * The source kind of packed 16-bit RGBA pixels.
     */
    private static final int RGBA16 = 1;

    /**
     * The source kind of packed 8-bit RGBA pixels.
     */
    private static final int RGBA8 = 2;

    /**
     * Prevents instantiation.
     */
    private DoubleResampler() {
        throw new AssertionError();
    }

    /**
     * Describes one resampling: the source and destination arrays and how samples are converted.
     *
     * @param kind {@link #PLANE16}, {@link #RGBA16} or {@link #RGBA8}
     * @param srcBytes the 8-bit source, or {@code null}
     * @param srcShorts the 16-bit source, or {@code null}
     * @param srcStride the source row stride in samples
     * @param inWidth the source width in pixels
     * @param channels the number of filtered channels: 1 for a plane, 3 for RGBA written opaque, else 4
     * @param premultiplied whether colors are filtered premultiplied by alpha
     * @param linear whether colors are filtered in linear light
     * @param max the largest sample value
     * @param dstBytes the 8-bit destination, or {@code null}
     * @param dstShorts the 16-bit destination, or {@code null}
     * @param dstStride the destination row stride in samples
     * @param outWidth the output width in pixels
     */
    private record Job(int kind, byte[] srcBytes, short[] srcShorts, int srcStride, int inWidth, int channels,
                       boolean premultiplied, boolean linear, int max, byte[] dstBytes, short[] dstShorts,
                       int dstStride, int outWidth) {
    }

    /**
     * Resamples a plane of samples of up to 16 bits.
     *
     * @param src the source samples, read unsigned
     * @param srcStride the source row stride
     * @param inWidth the source width
     * @param inHeight the source height
     * @param windowX the horizontal source window
     * @param windowY the vertical source window
     * @param dst the destination samples
     * @param dstStride the destination row stride
     * @param outWidth the output width
     * @param outHeight the output height
     * @param filter the reconstruction filter
     * @param max the largest sample value, {@code 2^depth - 1}
     */
    static void plane(short[] src, int srcStride, int inWidth, int inHeight, Resampler.Window windowX,
                      Resampler.Window windowY, short[] dst, int dstStride, int outWidth, int outHeight,
                      ResizeFilter filter, int max) {
        if (filter == ResizeFilter.NEAREST) {
            nearest(src, srcStride, inWidth, inHeight, windowX, windowY, 1, dst, dstStride, outWidth, outHeight);
            return;
        }
        var job = new Job(PLANE16, null, src, srcStride, inWidth, 1, false, false, max, null, dst, dstStride, outWidth);
        run(job, windowX, windowY, inHeight, outHeight, filter);
    }

    /**
     * Resamples packed 16-bit RGBA pixels.
     *
     * @param src the source samples
     * @param srcStride the source row stride in samples
     * @param inWidth the source width
     * @param inHeight the source height
     * @param windowX the horizontal source window
     * @param windowY the vertical source window
     * @param dst the destination samples
     * @param dstStride the destination row stride in samples
     * @param outWidth the output width
     * @param outHeight the output height
     * @param filter the reconstruction filter
     * @param premultiplied whether to filter in premultiplied alpha, for pictures with transparency
     * @param forceOpaque whether to write opaque alpha instead of the filtered alpha
     * @param linear whether to filter the colors in linear light
     */
    static void rgba16(short[] src, int srcStride, int inWidth, int inHeight, Resampler.Window windowX,
                       Resampler.Window windowY, short[] dst, int dstStride, int outWidth, int outHeight,
                       ResizeFilter filter, boolean premultiplied, boolean forceOpaque, boolean linear) {
        if (filter == ResizeFilter.NEAREST) {
            nearest(src, srcStride, inWidth, inHeight, windowX, windowY, 4, dst, dstStride, outWidth, outHeight);
            if (forceOpaque) {
                for (var y = 0; y < outHeight; y++) {
                    for (var x = 0; x < outWidth; x++) {
                        dst[y * dstStride + 4 * x + 3] = (short) 0xFFFF;
                    }
                }
            }
            return;
        }
        var job = new Job(RGBA16, null, src, srcStride, inWidth, forceOpaque ? 3 : 4, premultiplied && !forceOpaque,
                linear, 65535, null, dst, dstStride, outWidth);
        run(job, windowX, windowY, inHeight, outHeight, filter);
    }

    /**
     * Resamples packed 8-bit RGBA pixels in linear light.
     *
     * @param src the source pixels
     * @param srcStride the source row stride in bytes
     * @param inWidth the source width
     * @param inHeight the source height
     * @param windowX the horizontal source window
     * @param windowY the vertical source window
     * @param dst the destination pixels
     * @param dstStride the destination row stride in bytes
     * @param outWidth the output width
     * @param outHeight the output height
     * @param filter the reconstruction filter; not {@link ResizeFilter#NEAREST}
     * @param premultiplied whether to filter in premultiplied alpha, for pictures with transparency
     * @param forceOpaque whether to write opaque alpha instead of the filtered alpha
     */
    static void linearRgba8(byte[] src, int srcStride, int inWidth, int inHeight, Resampler.Window windowX,
                            Resampler.Window windowY, byte[] dst, int dstStride, int outWidth, int outHeight,
                            ResizeFilter filter, boolean premultiplied, boolean forceOpaque) {
        var job = new Job(RGBA8, src, null, srcStride, inWidth, forceOpaque ? 3 : 4, premultiplied && !forceOpaque,
                true, 255, dst, null, dstStride, outWidth);
        run(job, windowX, windowY, inHeight, outHeight, filter);
    }

    /**
     * Computes the taps and runs a resampling in bands of output rows.
     *
     * @param job the resampling
     * @param windowX the horizontal source window
     * @param windowY the vertical source window
     * @param inHeight the source height
     * @param outHeight the output height
     * @param filter the reconstruction filter
     */
    private static void run(Job job, Resampler.Window windowX, Resampler.Window windowY, int inHeight, int outHeight,
                            ResizeFilter filter) {
        var vertical = windowY.weights(filter, inHeight, outHeight, WEIGHT_BITS);
        var horizontal = windowX.weights(filter, job.inWidth(), job.outWidth(), WEIGHT_BITS);
        var verticalWeights = toDouble(vertical.weights());
        var horizontalWeights = toDouble(horizontal.weights());
        var kernel = DoubleResampleKernel.INSTANCE;
        var lanes = kernel.lanes();
        var blocks = (outHeight + lanes - 1) / lanes;
        var work = (long) job.channels() * outHeight * ((long) job.inWidth() * vertical.maxTaps()
                + (long) job.outWidth() * horizontal.maxTaps());
        var bands = ParallelBands.bands(blocks, work);
        var ringBytes = Math.max(1, RING_BYTES / bands);
        ParallelBands.run(bands, band -> rows(job, kernel, vertical, verticalWeights, horizontal, horizontalWeights,
                ringBytes, ParallelBands.start(band, bands, blocks) * lanes,
                Math.min(outHeight, ParallelBands.start(band + 1, bands, blocks) * lanes)));
    }

    /**
     * Converts fixed-point weights with {@value #WEIGHT_BITS} fractional bits to doubles, exactly.
     *
     * @param weights the fixed-point weights
     * @return the weights as doubles
     */
    static double[] toDouble(int[] weights) {
        var result = new double[weights.length];
        for (var i = 0; i < weights.length; i++) {
            result[i] = weights[i] * 0x1p-24;
        }
        return result;
    }

    /**
     * Resamples the output rows {@code [top, bottom)}.
     *
     * @param job the resampling
     * @param kernel the kernel the whole operation computes with
     * @param vertical the vertical taps
     * @param verticalWeights the vertical weights as doubles
     * @param horizontal the horizontal taps
     * @param horizontalWeights the horizontal weights as doubles
     * @param ringBytes the largest ring of converted rows, in bytes
     * @param top the first output row, a multiple of the kernel's lane count
     * @param bottom the output row after the last one
     */
    private static void rows(Job job, DoubleResampleKernel kernel, ResampleWeights vertical, double[] verticalWeights,
                             ResampleWeights horizontal, double[] horizontalWeights, long ringBytes, int top, int bottom) {
        var lanes = kernel.lanes();
        var inWidth = job.inWidth();
        var outWidth = job.outWidth();
        var channels = job.channels();
        var rowLength = channels * inWidth;
        var capacity = (int) Math.max(1, Math.min(vertical.maxTaps(), ringBytes / (8L * rowLength)));
        var ring = new double[capacity * rowLength];
        var rows = new double[lanes][rowLength];
        var block = new double[inWidth * lanes];
        var sums = new double[channels][outWidth * lanes];
        var offsets = new int[capacity];
        var ringLow = 0;
        var ringHigh = 0;
        for (; top < bottom; top += lanes) {
            var count = Math.min(lanes, bottom - top);
            for (var r = 0; r < count; r++) {
                var y = top + r;
                var start = vertical.starts()[y];
                var taps = vertical.counts()[y];
                var weightOffset = y * vertical.stride();
                if (taps <= capacity) {
                    if (start < ringLow || start > ringHigh) {
                        ringLow = start;
                        ringHigh = start;
                    }
                    while (ringHigh < start + taps) {
                        load(job, ringHigh, ring, (ringHigh % capacity) * rowLength);
                        ringHigh++;
                    }
                    ringLow = Math.max(ringLow, ringHigh - capacity);
                    for (var k = 0; k < taps; k++) {
                        offsets[k] = ((start + k) % capacity) * rowLength;
                    }
                    kernel.vertical(ring, offsets, verticalWeights, weightOffset, taps, rowLength, rows[r], false);
                } else {
                    for (var done = 0; done < taps; done += capacity) {
                        var chunk = Math.min(capacity, taps - done);
                        for (var k = 0; k < chunk; k++) {
                            load(job, start + done + k, ring, k * rowLength);
                            offsets[k] = k * rowLength;
                        }
                        kernel.vertical(ring, offsets, verticalWeights, weightOffset + done, chunk, rowLength, rows[r],
                                done > 0);
                    }
                    ringLow = 0;
                    ringHigh = 0;
                }
            }
            for (var channel = 0; channel < channels; channel++) {
                gather(rows, count, channel * inWidth, inWidth, lanes, block);
                kernel.horizontal(block, lanes, horizontal.starts(), horizontal.counts(), horizontalWeights,
                        horizontal.stride(), outWidth, sums[channel]);
            }
            for (var r = 0; r < count; r++) {
                store(job, sums, lanes, r, top + r);
            }
        }
    }

    /**
     * Interleaves one channel of a block of rows lane by lane: {@code rows[r][offset + x]} goes to
     * {@code dst[x * lanes + r]}.
     *
     * @param rows the rows of the block
     * @param rowCount the number of rows to read; at most {@code lanes}
     * @param offset the index of the channel's first value in every row
     * @param length the number of values per row
     * @param lanes the number of lanes of the block
     * @param dst the interleaved destination
     */
    private static void gather(double[][] rows, int rowCount, int offset, int length, int lanes, double[] dst) {
        if (lanes == 1) {
            System.arraycopy(rows[0], offset, dst, 0, length);
            return;
        }
        for (var r = 0; r < rowCount; r++) {
            var row = rows[r];
            for (var x = 0; x < length; x++) {
                dst[x * lanes + r] = row[offset + x];
            }
        }
    }

    /**
     * Converts one source row into planar values.
     *
     * @param job the resampling
     * @param y the source row
     * @param ring the destination
     * @param offset the index of the first value of the first channel
     */
    private static void load(Job job, int y, double[] ring, int offset) {
        var width = job.inWidth();
        var in = y * job.srcStride();
        switch (job.kind()) {
            case PLANE16 -> {
                var src = job.srcShorts();
                for (var x = 0; x < width; x++) {
                    ring[offset + x] = src[in + x] & 0xFFFF;
                }
            }
            case RGBA16 -> {
                var src = job.srcShorts();
                var linear = job.linear();
                var premultiplied = job.premultiplied();
                var alphaPlane = job.channels() == 4;
                for (var x = 0; x < width; x++) {
                    var p = in + 4 * x;
                    var r = src[p] & 0xFFFF;
                    var g = src[p + 1] & 0xFFFF;
                    var b = src[p + 2] & 0xFFFF;
                    double alpha = src[p + 3] & 0xFFFF;
                    double red = linear ? Srgb.decode16(r) : r;
                    double green = linear ? Srgb.decode16(g) : g;
                    double blue = linear ? Srgb.decode16(b) : b;
                    if (premultiplied) {
                        red *= alpha;
                        green *= alpha;
                        blue *= alpha;
                    }
                    ring[offset + x] = red;
                    ring[offset + width + x] = green;
                    ring[offset + 2 * width + x] = blue;
                    if (alphaPlane) {
                        ring[offset + 3 * width + x] = alpha;
                    }
                }
            }
            default -> {
                var src = job.srcBytes();
                var premultiplied = job.premultiplied();
                var alphaPlane = job.channels() == 4;
                for (var x = 0; x < width; x++) {
                    var p = in + 4 * x;
                    double alpha = src[p + 3] & 0xFF;
                    double red = Srgb.decode8(src[p] & 0xFF);
                    double green = Srgb.decode8(src[p + 1] & 0xFF);
                    double blue = Srgb.decode8(src[p + 2] & 0xFF);
                    if (premultiplied) {
                        red *= alpha;
                        green *= alpha;
                        blue *= alpha;
                    }
                    ring[offset + x] = red;
                    ring[offset + width + x] = green;
                    ring[offset + 2 * width + x] = blue;
                    if (alphaPlane) {
                        ring[offset + 3 * width + x] = alpha;
                    }
                }
            }
        }
    }

    /**
     * Rounds the filtered values of one row of a block and writes them to the destination.
     *
     * @param job the resampling
     * @param sums the lane-interleaved filtered values, one array per channel
     * @param lanes the number of lanes of the block
     * @param lane the lane of the row
     * @param y the output row
     */
    private static void store(Job job, double[][] sums, int lanes, int lane, int y) {
        var width = job.outWidth();
        var out = y * job.dstStride();
        var max = job.max();
        if (job.kind() == PLANE16) {
            var dst = job.dstShorts();
            var plane = sums[0];
            for (var x = 0; x < width; x++) {
                dst[out + x] = (short) round(plane[x * lanes + lane], max);
            }
            return;
        }
        var linear = job.linear();
        var premultiplied = job.premultiplied();
        var opaque = job.channels() == 3;
        var shorts = job.dstShorts();
        var bytes = job.dstBytes();
        for (var x = 0; x < width; x++) {
            var i = x * lanes + lane;
            var red = sums[0][i];
            var green = sums[1][i];
            var blue = sums[2][i];
            int alpha;
            if (opaque) {
                alpha = max;
            } else {
                var a = sums[3][i];
                if (premultiplied) {
                    if (!(a > 0)) {
                        red = green = blue = a = 0;
                    } else {
                        red /= a;
                        green /= a;
                        blue /= a;
                    }
                }
                alpha = round(a, max);
            }
            var p = out + 4 * x;
            if (shorts != null) {
                shorts[p] = (short) (linear ? Srgb.encode16((float) red) : round(red, max));
                shorts[p + 1] = (short) (linear ? Srgb.encode16((float) green) : round(green, max));
                shorts[p + 2] = (short) (linear ? Srgb.encode16((float) blue) : round(blue, max));
                shorts[p + 3] = (short) alpha;
            } else {
                bytes[p] = (byte) Srgb.encode8((float) red);
                bytes[p + 1] = (byte) Srgb.encode8((float) green);
                bytes[p + 2] = (byte) Srgb.encode8((float) blue);
                bytes[p + 3] = (byte) alpha;
            }
        }
    }

    /**
     * Rounds a value to the nearest integer, halves up, and clamps it to {@code 0..max}.
     *
     * @param value the value; NaN gives {@code 0}
     * @param max the largest result
     * @return the rounded value
     */
    private static int round(double value, int max) {
        return (int) Math.clamp(Math.round(value), 0, max);
    }

    /**
     * Copies the source pixel selected for every output pixel.
     *
     * @param src the source samples
     * @param srcStride the source row stride in samples
     * @param inWidth the source width
     * @param inHeight the source height
     * @param windowX the horizontal source window
     * @param windowY the vertical source window
     * @param samples the number of samples per pixel
     * @param dst the destination samples
     * @param dstStride the destination row stride in samples
     * @param outWidth the output width
     * @param outHeight the output height
     */
    private static void nearest(short[] src, int srcStride, int inWidth, int inHeight, Resampler.Window windowX,
                                Resampler.Window windowY, int samples, short[] dst, int dstStride, int outWidth,
                                int outHeight) {
        var rows = windowY.weights(ResizeFilter.NEAREST, inHeight, outHeight, 1).starts();
        var columns = windowX.weights(ResizeFilter.NEAREST, inWidth, outWidth, 1).starts();
        for (var y = 0; y < outHeight; y++) {
            var srcRow = rows[y] * srcStride;
            var dstRow = y * dstStride;
            for (var x = 0; x < outWidth; x++) {
                System.arraycopy(src, srcRow + samples * columns[x], dst, dstRow + samples * x, samples);
            }
        }
    }
}
