package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Resamples planes of 8-bit samples and rows of packed RGBA pixels with a separable filter.
 *
 * <p>Output rows are produced in blocks of {@link ResampleKernel#lanes()} rows. The vertical pass
 * filters, for each output row of the block, the source rows its taps cover into one full-width
 * row of intermediate values; the rows of the block are then interleaved lane by lane, so the
 * horizontal pass computes the same output column of every row of the block with one vector
 * multiply-add per tap, and a transposition writes the results back to the output rows. RGBA
 * channels are filtered as separate planes. The source is read in place, so the working memory
 * is one block of intermediate rows plus, for premultiplied RGBA, a ring of premultiplied
 * source rows.
 *
 * <p>Fixed-point formats:
 * <ul>
 *   <li>8-bit samples: the vertical weights have {@value #FIRST_BITS} fractional bits (the most
 *       an 8-bit sample times a weight sum of up to 1.55, the largest of these filters, fits in a
 *       32-bit accumulator), the intermediate keeps {@value #INTERMEDIATE_BITS} fractional bits,
 *       and the horizontal weights have {@value #SECOND_BITS}, again the most that fits. Only
 *       the final value is rounded to 8 bits and clamped, so a reduction by two with
 *       {@link ResizeFilter#AREA} yields exactly the rounded average of each 2x2 block.</li>
 *   <li>premultiplied RGBA: samples are the 16-bit products {@code c * a} and {@code 255 * a};
 *       the vertical weights have {@value #WIDE_FIRST_BITS} fractional bits, the intermediate
 *       keeps the premultiplied unit, and the horizontal weights have {@value #WIDE_SECOND_BITS};
 *       colors are divided by the filtered alpha with exact rounding.</li>
 * </ul>
 *
 * <p>Blocks of output rows are independent, so large operations split the output rows into
 * bands of whole blocks computed on several threads by {@link ParallelBands}; every band uses
 * its own working memory and the same arithmetic, so the output does not depend on the split.
 *
 * @implNote This implementation filters vertically first because the vertical pass vectorizes across a whole row with a
 * broadcast weight, and in a reduction it is the pass that runs over the full-resolution source; the horizontal pass
 * then runs only on output rows, and interleaving a block of rows lets it vectorize the same way instead of reducing
 * the taps of each output sample across vector lanes. The precision split mirrors Pillow's {@code Resample.c} choice of
 * the largest precision that cannot overflow a 32-bit accumulator, but keeps fractional bits between the passes instead
 * of rounding to 8 bits.
 */
final class Resampler {
    /**
     * The fractional bits of the vertical weights applied to 8-bit samples.
     */
    static final int FIRST_BITS = 22;

    /**
     * The fractional bits of the intermediate values between the passes over 8-bit samples.
     */
    static final int INTERMEDIATE_BITS = 4;

    /**
     * The fractional bits of the horizontal weights applied to the intermediate values.
     */
    static final int SECOND_BITS = 17;

    /**
     * The fractional bits of the vertical weights applied to premultiplied 16-bit samples.
     */
    static final int WIDE_FIRST_BITS = 14;

    /**
     * The fractional bits of the horizontal weights applied to premultiplied intermediates.
     */
    static final int WIDE_SECOND_BITS = 13;

    /**
     * The largest ring of premultiplied rows, in bytes, before the vertical pass switches to
     * accumulating over chunks of rows.
     */
    private static final long RING_BYTES = 32L << 20;

    /**
     * Prevents instantiation.
     */
    private Resampler() {
        throw new AssertionError();
    }

    /**
     * Describes the part of a source axis that maps onto the output: {@code [offset, offset +
     * length)} source samples, as fractions over a common denominator.
     *
     * @param offsetNumerator the numerator of the window's start; non-negative
     * @param lengthNumerator the numerator of the window's length; positive
     * @param denominator the common denominator; positive
     */
    record Window(long offsetNumerator, long lengthNumerator, long denominator) {
        /**
         * Returns the window covering a whole axis.
         *
         * @param size the number of samples of the axis
         * @return the window {@code [0, size)}
         */
        static Window full(int size) {
            return new Window(0, size, 1);
        }

        /**
         * Returns this window measured on an axis subsampled by {@code 2^shift}.
         *
         * @param shift the binary logarithm of the subsampling factor
         * @return the window in subsampled units
         */
        Window subsampled(int shift) {
            return new Window(offsetNumerator, lengthNumerator, denominator << shift);
        }

        /**
         * Computes the taps that map this window of an axis onto an output size.
         *
         * @param filter the reconstruction filter
         * @param inputSize the number of samples of the source axis
         * @param outputSize the number of output samples
         * @param bits the fractional bits of the weights
         * @return the taps, with a stride equal to the largest tap count
         */
        ResampleWeights weights(ResizeFilter filter, int inputSize, int outputSize, int bits) {
            return ResampleWeights.compute(filter, inputSize, outputSize, offsetNumerator, lengthNumerator, denominator, bits);
        }
    }

    /**
     * Resamples a plane of 8-bit samples.
     *
     * @param src the source samples
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
     */
    static void plane(byte[] src, int srcStride, int inWidth, int inHeight, Window windowX, Window windowY,
                      byte[] dst, int dstStride, int outWidth, int outHeight, ResizeFilter filter) {
        var vertical = windowY.weights(filter, inHeight, outHeight, FIRST_BITS);
        var horizontal = windowX.weights(filter, inWidth, outWidth, SECOND_BITS);
        if (filter == ResizeFilter.NEAREST) {
            nearest(src, srcStride, vertical.starts(), horizontal.starts(), 1, dst, dstStride);
            return;
        }
        var kernel = ResampleKernel.select();
        var lanes = kernel.lanes();
        var blocks = (outHeight + lanes - 1) / lanes;
        var bands = ParallelBands.bands(blocks, work(inWidth, outWidth, outHeight, vertical, horizontal));
        ParallelBands.run(bands, band -> planeRows(kernel, src, srcStride, inWidth, vertical, horizontal, dst, dstStride, outWidth,
                ParallelBands.start(band, bands, blocks) * lanes,
                Math.min(outHeight, ParallelBands.start(band + 1, bands, blocks) * lanes)));
    }

    /**
     * Returns the amount of work of a separable resampling, in multiply-adds per channel.
     *
     * @param inWidth the source width
     * @param outWidth the output width
     * @param outHeight the output height
     * @param vertical the vertical taps
     * @param horizontal the horizontal taps
     * @return the number of multiply-adds of both passes
     */
    private static long work(int inWidth, int outWidth, int outHeight, ResampleWeights vertical, ResampleWeights horizontal) {
        return (long) outHeight * ((long) inWidth * vertical.maxTaps() + (long) outWidth * horizontal.maxTaps());
    }

    /**
     * Resamples the output rows {@code [top, bottom)} of a plane of 8-bit samples.
     *
     * @param kernel the kernel the whole operation computes with
     * @param src the source samples
     * @param srcStride the source row stride
     * @param inWidth the source width
     * @param vertical the vertical taps
     * @param horizontal the horizontal taps
     * @param dst the destination samples
     * @param dstStride the destination row stride
     * @param outWidth the output width
     * @param top the first output row, a multiple of the kernel's lane count
     * @param bottom the output row after the last one
     */
    static void planeRows(ResampleKernel kernel, byte[] src, int srcStride, int inWidth, ResampleWeights vertical,
                                  ResampleWeights horizontal, byte[] dst, int dstStride, int outWidth, int top, int bottom) {
        var lanes = kernel.lanes();
        var rows = new int[lanes][inWidth];
        var block = new int[inWidth * lanes];
        var out = new byte[outWidth * lanes + 8];
        var offsets = new int[vertical.maxTaps()];
        for (; top < bottom; top += lanes) {
            var count = Math.min(lanes, bottom - top);
            for (var r = 0; r < count; r++) {
                verticalRow(kernel, src, srcStride, vertical, top + r, inWidth, offsets, rows[r]);
            }
            kernel.gatherRows(rows, count, 0, 1, inWidth, lanes, block);
            kernel.horizontalRows(block, lanes, horizontal.starts(), horizontal.counts(), horizontal.weights(),
                    horizontal.stride(), outWidth, out, SECOND_BITS + INTERMEDIATE_BITS);
            ResampleKernel.scatterRows(out, lanes, outWidth, count, dst, top * dstStride, dstStride);
        }
    }

    /**
     * Filters the source rows of one output row vertically.
     *
     * @param kernel the resampling kernel implementation
     * @param src the source samples
     * @param srcStride the source row stride
     * @param vertical the vertical taps
     * @param y the output row
     * @param length the number of samples per row
     * @param offsets a scratch array for the tap row offsets
     * @param row the destination row of intermediate values
     */
    private static void verticalRow(ResampleKernel kernel, byte[] src, int srcStride, ResampleWeights vertical, int y,
                                    int length, int[] offsets, int[] row) {
        var start = vertical.starts()[y];
        var taps = vertical.counts()[y];
        for (var k = 0; k < taps; k++) {
            offsets[k] = (start + k) * srcStride;
        }
        kernel.vertical(src, offsets, vertical.weights(), y * vertical.stride(), taps, length, row, FIRST_BITS - INTERMEDIATE_BITS);
    }

    /**
     * Resamples packed RGBA pixels.
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
     * @param filter the reconstruction filter
     * @param premultiplied whether to filter in premultiplied alpha, for pictures with transparency
     * @param forceOpaque whether to write opaque alpha instead of the filtered alpha, for pictures without transparency
     */
    static void rgba(byte[] src, int srcStride, int inWidth, int inHeight, Window windowX, Window windowY,
                     byte[] dst, int dstStride, int outWidth, int outHeight, ResizeFilter filter,
                     boolean premultiplied, boolean forceOpaque) {
        if (filter == ResizeFilter.NEAREST) {
            var vertical = windowY.weights(filter, inHeight, outHeight, FIRST_BITS);
            var horizontal = windowX.weights(filter, inWidth, outWidth, FIRST_BITS);
            nearest(src, srcStride, vertical.starts(), horizontal.starts(), 4, dst, dstStride);
            if (forceOpaque) {
                for (var y = 0; y < outHeight; y++) {
                    for (var x = 0; x < outWidth; x++) {
                        dst[y * dstStride + 4 * x + 3] = (byte) 255;
                    }
                }
            }
            return;
        }
        if (premultiplied) {
            premultipliedRgba(src, srcStride, inWidth, inHeight, windowX, windowY, dst, dstStride, outWidth, outHeight, filter);
            return;
        }
        var vertical = windowY.weights(filter, inHeight, outHeight, FIRST_BITS);
        var horizontal = windowX.weights(filter, inWidth, outWidth, SECOND_BITS);
        var kernel = ResampleKernel.select();
        var lanes = kernel.lanes();
        var blocks = (outHeight + lanes - 1) / lanes;
        var bands = ParallelBands.bands(blocks, 4 * work(inWidth, outWidth, outHeight, vertical, horizontal));
        ParallelBands.run(bands, band -> rgbaRows(kernel, src, srcStride, inWidth, vertical, horizontal, dst, dstStride, outWidth,
                forceOpaque, ParallelBands.start(band, bands, blocks) * lanes,
                Math.min(outHeight, ParallelBands.start(band + 1, bands, blocks) * lanes)));
    }

    /**
     * Resamples the output rows {@code [top, bottom)} of packed RGBA pixels filtered directly.
     *
     * @param kernel the kernel the whole operation computes with
     * @param src the source pixels
     * @param srcStride the source row stride in bytes
     * @param inWidth the source width
     * @param vertical the vertical taps
     * @param horizontal the horizontal taps
     * @param dst the destination pixels
     * @param dstStride the destination row stride in bytes
     * @param outWidth the output width
     * @param forceOpaque whether to write opaque alpha instead of the filtered alpha
     * @param top the first output row, a multiple of the kernel's lane count
     * @param bottom the output row after the last one
     */
    static void rgbaRows(ResampleKernel kernel, byte[] src, int srcStride, int inWidth, ResampleWeights vertical,
                                 ResampleWeights horizontal, byte[] dst, int dstStride, int outWidth, boolean forceOpaque,
                                 int top, int bottom) {
        var lanes = kernel.lanes();
        var rows = new int[lanes][4 * inWidth];
        var block = new int[inWidth * lanes];
        var out = new byte[outWidth * lanes + 8];
        var planes = new byte[lanes * 4 * outWidth];
        var offsets = new int[vertical.maxTaps()];
        var channels = forceOpaque ? 3 : 4;
        for (; top < bottom; top += lanes) {
            var count = Math.min(lanes, bottom - top);
            for (var r = 0; r < count; r++) {
                verticalRow(kernel, src, srcStride, vertical, top + r, 4 * inWidth, offsets, rows[r]);
            }
            for (var channel = 0; channel < channels; channel++) {
                kernel.gatherRows(rows, count, channel, 4, inWidth, lanes, block);
                kernel.horizontalRows(block, lanes, horizontal.starts(), horizontal.counts(), horizontal.weights(),
                        horizontal.stride(), outWidth, out, SECOND_BITS + INTERMEDIATE_BITS);
                ResampleKernel.scatterRows(out, lanes, outWidth, count, planes, channel * outWidth, 4 * outWidth);
            }
            for (var r = 0; r < count; r++) {
                kernel.interleave(planes, r * 4 * outWidth, outWidth, forceOpaque, dst, (top + r) * dstStride);
            }
        }
    }

    /**
     * Resamples packed RGBA pixels with transparency in premultiplied alpha.
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
     * @param filter the filter; not {@link ResizeFilter#NEAREST}
     */
    private static void premultipliedRgba(byte[] src, int srcStride, int inWidth, int inHeight, Window windowX,
                                          Window windowY, byte[] dst, int dstStride, int outWidth, int outHeight,
                                          ResizeFilter filter) {
        var vertical = windowY.weights(filter, inHeight, outHeight, WIDE_FIRST_BITS);
        var horizontal = windowX.weights(filter, inWidth, outWidth, WIDE_SECOND_BITS);
        var kernel = ResampleKernel.select();
        var lanes = kernel.lanes();
        var blocks = (outHeight + lanes - 1) / lanes;
        var bands = ParallelBands.bands(blocks, 4 * work(inWidth, outWidth, outHeight, vertical, horizontal));
        var ringBytes = Math.max(1, RING_BYTES / bands);
        ParallelBands.run(bands, band -> premultipliedRows(kernel, src, srcStride, inWidth, vertical, horizontal, dst, dstStride,
                outWidth, ringBytes, ParallelBands.start(band, bands, blocks) * lanes,
                Math.min(outHeight, ParallelBands.start(band + 1, bands, blocks) * lanes)));
    }

    /**
     * Resamples the output rows {@code [top, bottom)} of packed RGBA pixels in premultiplied
     * alpha.
     *
     * @param kernel the kernel the whole operation computes with
     * @param src the source pixels
     * @param srcStride the source row stride in bytes
     * @param inWidth the source width
     * @param vertical the vertical taps
     * @param horizontal the horizontal taps
     * @param dst the destination pixels
     * @param dstStride the destination row stride in bytes
     * @param outWidth the output width
     * @param ringBytes the largest ring of premultiplied rows, in bytes, before the vertical pass switches to
     * accumulating over chunks of rows
     * @param top the first output row, a multiple of the kernel's lane count
     * @param bottom the output row after the last one
     */
    static void premultipliedRows(ResampleKernel kernel, byte[] src, int srcStride, int inWidth,
                                          ResampleWeights vertical, ResampleWeights horizontal, byte[] dst, int dstStride,
                                          int outWidth, long ringBytes, int top, int bottom) {
        var lanes = kernel.lanes();
        var rowLength = 4 * inWidth;
        var capacity = (int) Math.max(1, Math.min(vertical.maxTaps(), ringBytes / (2L * rowLength)));
        var ring = new char[capacity * rowLength];
        var rows = new int[lanes][rowLength];
        var block = new int[inWidth * lanes];
        var channelSums = new int[4][outWidth * lanes];
        var sums = new int[4 * outWidth];
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
                        kernel.premultiply(src, ringHigh * srcStride, inWidth, ring, (ringHigh % capacity) * rowLength);
                        ringHigh++;
                    }
                    ringLow = Math.max(ringLow, ringHigh - capacity);
                    for (var k = 0; k < taps; k++) {
                        offsets[k] = ((start + k) % capacity) * rowLength;
                    }
                    kernel.verticalWide(ring, offsets, vertical.weights(), weightOffset, taps, rowLength, rows[r], false, WIDE_FIRST_BITS);
                } else {
                    for (var done = 0; done < taps; done += capacity) {
                        var chunk = Math.min(capacity, taps - done);
                        for (var k = 0; k < chunk; k++) {
                            kernel.premultiply(src, (start + done + k) * srcStride, inWidth, ring, k * rowLength);
                            offsets[k] = k * rowLength;
                        }
                        var last = done + chunk == taps;
                        kernel.verticalWide(ring, offsets, vertical.weights(), weightOffset + done, chunk, rowLength, rows[r],
                                done > 0, last ? WIDE_FIRST_BITS : 0);
                    }
                    ringLow = 0;
                    ringHigh = 0;
                }
            }
            for (var channel = 0; channel < 4; channel++) {
                kernel.gatherRows(rows, count, channel * inWidth, 1, inWidth, lanes, block);
                kernel.horizontalRowsWide(block, lanes, horizontal.starts(), horizontal.counts(), horizontal.weights(),
                        horizontal.stride(), outWidth, channelSums[channel]);
            }
            for (var r = 0; r < count; r++) {
                for (var channel = 0; channel < 4; channel++) {
                    var lane = channelSums[channel];
                    var base = channel * outWidth;
                    for (var x = 0; x < outWidth; x++) {
                        sums[base + x] = lane[x * lanes + r];
                    }
                }
                kernel.unpremultiply(sums, 0, outWidth, 1 << WIDE_SECOND_BITS, dst, (top + r) * dstStride);
            }
        }
    }

    /**
     * Copies the source pixel selected for every output pixel.
     *
     * @param src the source samples
     * @param srcStride the source row stride in bytes
     * @param rows the source row of each output row
     * @param columns the source column of each output column
     * @param bytes the number of bytes per pixel
     * @param dst the destination samples
     * @param dstStride the destination row stride in bytes
     */
    private static void nearest(byte[] src, int srcStride, int[] rows, int[] columns, int bytes, byte[] dst, int dstStride) {
        for (var y = 0; y < rows.length; y++) {
            var srcRow = rows[y] * srcStride;
            var dstRow = y * dstStride;
            if (bytes == 1) {
                for (var x = 0; x < columns.length; x++) {
                    dst[dstRow + x] = src[srcRow + columns[x]];
                }
            } else {
                for (var x = 0; x < columns.length; x++) {
                    System.arraycopy(src, srcRow + bytes * columns[x], dst, dstRow + bytes * x, bytes);
                }
            }
        }
    }
}
