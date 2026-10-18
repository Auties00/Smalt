/* Ports Pillow (src/libImaging/BoxBlur.c). Copyright Secret Labs AB, Fredrik Lundh and contributors, and Jeffrey A.
 * Clark and contributors; MIT-CMU License. Modified for Smalt. */
package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Blurs packed 8-bit RGBA pictures with passes of an extended box filter, the fractional-radius
 * box whose two end samples are weighted by the fractional part of the radius.
 *
 * <p>Each pass replaces every sample with the weighted mean of the {@code 2r + 1} samples around
 * it plus the fractional weight of the next one on each side, in fixed point with 24 fractional
 * bits, rounded to 8 bits; samples beyond the edges repeat the edge sample. The horizontal passes
 * run first, then the vertical ones. A Gaussian of standard deviation {@code sigma} is
 * approximated by three passes of the radius {@link #gaussianRadius(float, int)} computes. A
 * picture with transparency is premultiplied first, with 8-bit rounding, and divided back at the
 * end.
 *
 * <p>Every pass runs down the columns, one running sum per column: the horizontal passes run on
 * the transposed picture, which is transposed back afterwards. Passes split the columns into
 * bands computed by {@link ParallelBands}; the result does not depend on the split.
 *
 * @implNote This implementation reproduces Pillow's {@code libImaging/BoxBlur.c} sample for sample: the same
 * weights, edge handling, rounding between passes and single-precision radius arithmetic, and the {@code RGBa}
 * premultiplication of {@code libImaging/Convert.c}. Where Pillow transposes the picture to run its vertical passes
 * as horizontal ones, this implementation slides the window down the rows with one running sum per column, which
 * vectorizes across the columns, and transposes for the horizontal passes; the arithmetic per sample is the same.
 */
final class BoxBlur {
    /**
     * The number of samples per column band unit of the vertical passes.
     */
    private static final int COLUMN_CHUNK = 64;

    /**
     * The side of the square tiles of {@link #transpose}, in pixels.
     */
    private static final int TILE = 32;

    /**
     * Reads and writes packed 4-byte pixels of a byte array as {@code int}s.
     */
    private static final VarHandle PIXELS = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Prevents instantiation.
     */
    private BoxBlur() {
        throw new AssertionError();
    }

    /**
     * Returns the radius of an extended box filter whose {@code passes} passes approximate a
     * Gaussian of standard deviation {@code sigma}, as Pillow computes it.
     *
     * @param sigma the standard deviation
     * @param passes the number of passes
     * @return the fractional box radius
     */
    static float gaussianRadius(float sigma, int passes) {
        var sigma2 = sigma * sigma / passes;
        var length = (float) Math.sqrt(12.0 * sigma2 + 1.0);
        var whole = (float) Math.floor((length - 1.0) / 2.0);
        var fraction = (2 * whole + 1) * (whole * (whole + 1) - 3 * sigma2);
        fraction /= 6 * (sigma2 - (whole + 1) * (whole + 1));
        return whole + fraction;
    }

    /**
     * Blurs an 8-bit RGBA picture.
     *
     * @param src the picture
     * @param xRadius the fractional radius of the horizontal passes; {@code 0} skips them
     * @param yRadius the fractional radius of the vertical passes; {@code 0} skips them
     * @param passes the number of passes in each direction
     * @return the blurred picture
     */
    static RgbaPicture blur(RgbaPicture src, float xRadius, float yRadius, int passes) {
        var width = src.width();
        var height = src.height();
        var rowLength = 4 * width;
        OpChecks.checkArray((long) rowLength * height);
        var premultiplied = src.hasAlpha() && !Geometry.isOpaque(src);
        var first = new byte[rowLength * height];
        var second = new byte[rowLength * height];
        var rowBands = ParallelBands.bands(height, 2L * rowLength * height);
        ParallelBands.run(rowBands, band -> copyRows(src, premultiplied, first,
                ParallelBands.start(band, rowBands, height), ParallelBands.start(band + 1, rowBands, height)));
        var current = first;
        var other = second;
        if (xRadius != 0) {
            transpose(current, width, height, other);
            var swap = current;
            current = other;
            other = swap;
            current = columnPasses(current, other, 4 * height, width, xRadius, passes);
            other = current == first ? second : first;
            transpose(current, height, width, other);
            swap = current;
            current = other;
            other = swap;
        }
        if (yRadius != 0) {
            current = columnPasses(current, other, rowLength, height, yRadius, passes);
        }
        if (premultiplied) {
            unpremultiply(current);
        } else if (!src.hasAlpha()) {
            for (var i = 3; i < current.length; i += 4) {
                current[i] = (byte) 255;
            }
        }
        return RgbaPicture.of(width, height, rowLength, current, src.hasAlpha());
    }

    /**
     * Runs passes of the extended box filter down the columns of a tight buffer, alternating
     * between it and a second buffer of the same size.
     *
     * @param current the buffer holding the input
     * @param other the other buffer
     * @param rowLength the samples per row
     * @param rows the number of rows
     * @param radius the fractional radius
     * @param passes the number of passes
     * @return the buffer holding the result
     */
    private static byte[] columnPasses(byte[] current, byte[] other, int rowLength, int rows, float radius, int passes) {
        var whole = (int) radius;
        var ww = (int) ((float) (1 << 24) / (radius * 2 + 1));
        var fw = ((1 << 24) - (whole * 2 + 1) * ww) / 2;
        var chunks = (rowLength + COLUMN_CHUNK - 1) / COLUMN_CHUNK;
        var columnBands = ParallelBands.bands(chunks, 8L * rowLength * rows);
        for (var pass = 0; pass < passes; pass++) {
            var in = current;
            var out = other;
            ParallelBands.run(columnBands, band -> vertical(in, out, rowLength, rows, whole, ww, fw,
                    Math.min(rowLength, ParallelBands.start(band, columnBands, chunks) * COLUMN_CHUNK),
                    Math.min(rowLength, ParallelBands.start(band + 1, columnBands, chunks) * COLUMN_CHUNK)));
            current = out;
            other = in;
        }
        return current;
    }

    /**
     * Copies the rows {@code [top, bottom)} of a picture into a tight buffer, premultiplying them
     * when asked.
     *
     * @param src the picture
     * @param premultiplied whether to premultiply the color channels
     * @param out the tight destination buffer
     * @param top the first row
     * @param bottom the row after the last one
     */
    private static void copyRows(RgbaPicture src, boolean premultiplied, byte[] out, int top, int bottom) {
        var rowLength = 4 * src.width();
        for (var y = top; y < bottom; y++) {
            System.arraycopy(src.pixels(), y * src.stride(), out, y * rowLength, rowLength);
        }
        if (premultiplied) {
            var row = new byte[rowLength];
            for (var y = top; y < bottom; y++) {
                System.arraycopy(out, y * rowLength, row, 0, rowLength);
                premultiply(row, rowLength);
                System.arraycopy(row, 0, out, y * rowLength, rowLength);
            }
        }
    }

    /**
     * Transposes a tight buffer of packed 4-byte pixels: pixel {@code (x, y)} of the source becomes
     * pixel {@code (y, x)} of the destination. Tiles of {@value #TILE} pixels square split into bands
     * of tile rows computed by {@link ParallelBands}.
     *
     * @param src the source pixels
     * @param width the source width
     * @param height the source height
     * @param dst the destination pixels, {@code height} per row
     */
    static void transpose(byte[] src, int width, int height, byte[] dst) {
        var tiles = (height + TILE - 1) / TILE;
        var bands = ParallelBands.bands(tiles, 2L * width * height);
        ParallelBands.run(bands, band -> {
            for (var tile = ParallelBands.start(band, bands, tiles); tile < ParallelBands.start(band + 1, bands, tiles); tile++) {
                var top = tile * TILE;
                var bottom = Math.min(height, top + TILE);
                for (var left = 0; left < width; left += TILE) {
                    var right = Math.min(width, left + TILE);
                    for (var y = top; y < bottom; y++) {
                        for (var x = left; x < right; x++) {
                            PIXELS.set(dst, 4 * (x * height + y), (int) PIXELS.get(src, 4 * (y * width + x)));
                        }
                    }
                }
            }
        });
    }

    /**
     * Runs one vertical pass over the columns {@code [from, to)} of a tight buffer.
     *
     * @param in the source buffer
     * @param out the destination buffer
     * @param rowLength the samples per row
     * @param height the number of rows
     * @param radius the whole part of the radius
     * @param ww the whole-sample weight
     * @param fw the fractional end weight
     * @param from the first sample column
     * @param to the sample column after the last one
     */
    private static void vertical(byte[] in, byte[] out, int rowLength, int height, int radius, int ww, int fw, int from,
                                 int to) {
        var length = to - from;
        if (length <= 0) {
            return;
        }
        var kernel = EffectKernel.INSTANCE;
        var acc = new int[length];
        var last = height - 1;
        var edgeA = Math.min(radius + 1, height);
        var edgeB = Math.max(height - radius - 1, 0);
        kernel.boxAccumulate(in, from, radius + 1, acc, length);
        for (var y = 0; y < edgeA - 1; y++) {
            kernel.boxAccumulate(in, y * rowLength + from, 1, acc, length);
        }
        kernel.boxAccumulate(in, last * rowLength + from, radius - edgeA + 1, acc, length);
        int firstEnd;
        int secondEnd;
        if (edgeA <= edgeB) {
            firstEnd = edgeA;
            secondEnd = edgeB;
        } else {
            firstEnd = edgeB;
            secondEnd = edgeA;
        }
        for (var y = 0; y < firstEnd; y++) {
            row(kernel, in, out, rowLength, from, length, acc, ww, fw, y, 0, y + radius, 0, y + radius + 1);
        }
        for (var y = firstEnd; y < secondEnd; y++) {
            if (edgeA <= edgeB) {
                row(kernel, in, out, rowLength, from, length, acc, ww, fw, y, y - radius - 1, y + radius, y - radius - 1,
                        y + radius + 1);
            } else {
                row(kernel, in, out, rowLength, from, length, acc, ww, fw, y, 0, last, 0, last);
            }
        }
        for (var y = secondEnd; y <= last; y++) {
            row(kernel, in, out, rowLength, from, length, acc, ww, fw, y, y - radius - 1, last, y - radius - 1, last);
        }
    }

    /**
     * Filters one row of a column band.
     *
     * @param kernel the scalar or vector implementation of the row filters
     * @param in the source buffer
     * @param out the destination buffer
     * @param rowLength the samples per row
     * @param from the first sample column of the band
     * @param length the number of columns of the band
     * @param acc the sliding sums of the band
     * @param ww the whole-sample weight
     * @param fw the fractional end weight
     * @param y the output row
     * @param subtract the row leaving the window
     * @param add the row entering the window
     * @param left the row at the window's far start
     * @param right the row at the window's far end
     */
    private static void row(EffectKernel kernel, byte[] in, byte[] out, int rowLength, int from, int length, int[] acc,
                            int ww, int fw, int y, int subtract, int add, int left, int right) {
        kernel.boxRow(in, add * rowLength + from, subtract * rowLength + from, left * rowLength + from,
                right * rowLength + from, acc, ww, fw, out, y * rowLength + from, length);
    }

    /**
     * Blurs a 16-bit RGBA picture with the same filter, in 64-bit integer arithmetic with 16-bit
     * rounding between passes.
     *
     * <p>A picture with transparency is premultiplied first (each color becomes
     * {@code round(c * a / 65535)}) and divided back at the end
     * ({@code min(65535, c * 65535 / a)}, truncated).
     *
     * @param src the picture
     * @param xRadius the fractional radius of the horizontal passes; {@code 0} skips them
     * @param yRadius the fractional radius of the vertical passes; {@code 0} skips them
     * @param passes the number of passes in each direction
     * @return the blurred picture
     */
    static Rgba16Picture blur16(Rgba16Picture src, float xRadius, float yRadius, int passes) {
        var width = src.width();
        var height = src.height();
        var rowLength = 4 * width;
        OpChecks.checkArray((long) rowLength * height);
        var premultiplied = src.hasAlpha() && !ImageOps.isOpaque(src);
        var first = new short[rowLength * height];
        var second = new short[rowLength * height];
        var rowBands = ParallelBands.bands(height, 8L * passes * rowLength * height);
        ParallelBands.run(rowBands, band -> {
            var a = new short[rowLength];
            var b = new short[rowLength];
            for (var y = ParallelBands.start(band, rowBands, height); y < ParallelBands.start(band + 1, rowBands, height); y++) {
                System.arraycopy(src.pixels(), y * src.stride(), a, 0, rowLength);
                if (premultiplied) {
                    for (var i = 0; i < rowLength; i += 4) {
                        var alpha = a[i + 3] & 0xFFFF;
                        for (var c = 0; c < 3; c++) {
                            a[i + c] = (short) (((long) (a[i + c] & 0xFFFF) * alpha + 32767) / 65535);
                        }
                    }
                }
                if (xRadius != 0) {
                    for (var pass = 0; pass < passes; pass++) {
                        for (var channel = 0; channel < 4; channel++) {
                            line16(a, channel, 4, b, channel, 4, width, xRadius);
                        }
                        var swap = a;
                        a = b;
                        b = swap;
                    }
                }
                System.arraycopy(a, 0, first, y * rowLength, rowLength);
            }
        });
        var current = first;
        var other = second;
        if (yRadius != 0) {
            var columnBands = ParallelBands.bands(rowLength, 8L * rowLength * height);
            for (var pass = 0; pass < passes; pass++) {
                var in = current;
                var out = other;
                ParallelBands.run(columnBands, band -> {
                    for (var column = ParallelBands.start(band, columnBands, rowLength);
                         column < ParallelBands.start(band + 1, columnBands, rowLength); column++) {
                        line16(in, column, rowLength, out, column, rowLength, height, yRadius);
                    }
                });
                current = out;
                other = in;
            }
        }
        for (var i = 3; i < current.length; i += 4) {
            var alpha = current[i] & 0xFFFF;
            if (!src.hasAlpha()) {
                current[i] = (short) 0xFFFF;
            } else if (premultiplied && alpha != 0 && alpha != 0xFFFF) {
                for (var c = 1; c <= 3; c++) {
                    current[i - c] = (short) Math.min(65535, (long) (current[i - c] & 0xFFFF) * 65535 / alpha);
                }
            }
        }
        return Rgba16Picture.of(width, height, rowLength, current, src.hasAlpha());
    }

    /**
     * Runs one pass of the extended box filter along a strided line of 16-bit samples.
     *
     * @param in the source samples
     * @param inOffset the index of the line's first source sample
     * @param inStep the distance between consecutive source samples of the line
     * @param out the destination samples
     * @param outOffset the index of the line's first destination sample
     * @param outStep the distance between consecutive destination samples of the line
     * @param count the number of samples of the line
     * @param floatRadius the fractional radius
     */
    private static void line16(short[] in, int inOffset, int inStep, short[] out, int outOffset, int outStep, int count,
                               float floatRadius) {
        var radius = (int) floatRadius;
        var ww = (long) ((float) (1 << 24) / (floatRadius * 2 + 1));
        var fw = ((1L << 24) - (radius * 2L + 1) * ww) / 2;
        var last = count - 1;
        long acc = 0;
        for (var k = -radius - 1; k < radius; k++) {
            acc += in[inOffset + Math.clamp(k, 0, last) * inStep] & 0xFFFF;
        }
        for (var x = 0; x < count; x++) {
            acc += (in[inOffset + Math.min(x + radius, last) * inStep] & 0xFFFF)
                    - (in[inOffset + Math.max(x - radius - 1, 0) * inStep] & 0xFFFF);
            var ends = (in[inOffset + Math.max(x - radius - 1, 0) * inStep] & 0xFFFF)
                    + (in[inOffset + Math.min(x + radius + 1, last) * inStep] & 0xFFFF);
            var bulk = acc * ww + ends * fw;
            out[outOffset + x * outStep] = (short) Math.min(65535, (bulk + (1L << 23)) >>> 24);
        }
    }

    /**
     * Premultiplies a row of packed pixels as Pillow's {@code RGBa} conversion does: each color
     * becomes {@code (t + (t >> 8)) >> 8} with {@code t = c * a + 128}.
     *
     * @param row the packed row
     * @param length the number of samples
     */
    static void premultiply(byte[] row, int length) {
        for (var i = 0; i < length; i += 4) {
            var alpha = row[i + 3] & 0xFF;
            for (var c = 0; c < 3; c++) {
                var t = (row[i + c] & 0xFF) * alpha + 128;
                row[i + c] = (byte) (((t >> 8) + t) >> 8);
            }
        }
    }

    /**
     * Divides premultiplied packed pixels back as Pillow does: each color of a pixel whose alpha
     * is neither 0 nor 255 becomes {@code min(255, 255 * c / a)}, truncated.
     *
     * @param pixels the packed pixels
     */
    static void unpremultiply(byte[] pixels) {
        for (var i = 0; i < pixels.length; i += 4) {
            var alpha = pixels[i + 3] & 0xFF;
            if (alpha == 0 || alpha == 255) {
                continue;
            }
            for (var c = 0; c < 3; c++) {
                pixels[i + c] = (byte) Math.min(255, 255 * (pixels[i + c] & 0xFF) / alpha);
            }
        }
    }
}
