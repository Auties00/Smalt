package com.github.auties00.smalt.internal.quantize;

import com.github.auties00.smalt.image.ops.Dithering;

import java.util.Arrays;

/**
 * Maps the pixels of an image rectangle to palette indices, with or without dithering.
 *
 * <p>The pixels are packed little-endian RGBA {@code int}s: a pixel whose alpha byte is 255 is
 * mapped to its nearest palette entry, any other pixel to the transparent index. Every
 * {@link Dithering} is available:
 * <ul>
 *   <li>{@link Dithering#NONE}: each pixel takes its nearest entry;</li>
 *   <li>{@link Dithering#ORDERED}: an 8x8 Bayer threshold is added to each channel before the search,
 *       scaled to the per-channel step of the palette: the mean distance from each entry to its
 *       nearest other entry, divided by the square root of 3;</li>
 *   <li>{@link Dithering#FLOYD_STEINBERG}: the quantization error of each pixel is diffused to its
 *       unvisited neighbors with the weights 7, 3, 5 and 1 sixteenths;</li>
 *   <li>{@link Dithering#SIERRA}: the error is diffused over two rows with Frankie Sierra's three-row
 *       weights (5 and 3 to the right, 2 4 5 4 2 below, 2 3 2 two rows below, in thirty-seconds).</li>
 * </ul>
 * Error diffusion scans the rows in serpentine order (alternating direction), and neither takes
 * error from nor gives error to a transparent pixel, so the colors of unchanged areas never leak
 * into changed ones. The output is deterministic.
 *
 * <p>An instance reuses its buffers across calls and is not thread-safe.
 */
public final class IndexMapper {
    /**
     * The 8x8 Bayer threshold matrix, values 0 to 63 in row-major order.
     */
    private static final int[] BAYER = {
            0, 32, 8, 40, 2, 34, 10, 42,
            48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38,
            60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41,
            51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37,
            63, 31, 55, 23, 61, 29, 53, 21
    };

    /**
     * The columns of padding on each side of an error row, the widest reach of a kernel.
     */
    private static final int PAD = 2;

    /**
     * The diffused error of the current row and the next two, three channels interleaved, in
     * thirty-seconds of a channel unit; reused across calls.
     */
    private int[] errors = new int[0];

    /**
     * Constructs a mapper.
     */
    public IndexMapper() {
    }

    /**
     * Maps a rectangle of pixels to palette indices with a dithering method.
     *
     * @param pixels the pixels, row-major, {@code width * height} of them
     * @param width the rectangle width
     * @param height the rectangle height
     * @param palette the palette the indices refer to
     * @param transparent the index of the transparent entry, used for pixels whose alpha byte is
     * not 255
     * @param dithering how the quantization error is spread
     * @param out the indices, row-major, {@code width * height} of them
     */
    public void map(int[] pixels, int width, int height, Palette palette, int transparent, Dithering dithering,
                    byte[] out) {
        switch (dithering) {
            case NONE -> nearest(pixels, width * height, palette, transparent, out);
            case ORDERED -> ordered(pixels, width, height, palette, transparent, out);
            case FLOYD_STEINBERG -> diffuse(pixels, width, height, palette, transparent, false, out);
            case SIERRA -> diffuse(pixels, width, height, palette, transparent, true, out);
        }
    }

    /**
     * Maps each pixel to its nearest entry.
     *
     * @param pixels      the packed RGBA pixels
     * @param count       the number of pixels
     * @param palette     the palette the indices refer to
     * @param transparent the transparent index
     * @param out         the indices
     */
    private static void nearest(int[] pixels, int count, Palette palette, int transparent, byte[] out) {
        var last = 0;
        var lastIndex = transparent;
        for (var i = 0; i < count; i++) {
            var pixel = pixels[i];
            if (pixel != last) {
                last = pixel;
                lastIndex = pixel >>> 24 == 0xFF ? palette.indexOf(pixel) : transparent;
            }
            out[i] = (byte) lastIndex;
        }
    }

    /**
     * Maps pixels with an ordered Bayer threshold.
     *
     * @param pixels      the packed RGBA pixels, row-major
     * @param width       the rectangle width
     * @param height      the rectangle height
     * @param palette     the palette the indices refer to
     * @param transparent the transparent index
     * @param out         the indices
     */
    private static void ordered(int[] pixels, int width, int height, Palette palette, int transparent, byte[] out) {
        var spread = palette.spacing() / Math.sqrt(3);
        var offsets = new int[BAYER.length];
        for (var i = 0; i < offsets.length; i++) {
            offsets[i] = (int) Math.round(((BAYER[i] + 0.5) / 64 - 0.5) * spread);
        }
        for (var y = 0; y < height; y++) {
            var row = y * width;
            var matrixRow = (y & 7) << 3;
            for (var x = 0; x < width; x++) {
                var pixel = pixels[row + x];
                if (pixel >>> 24 != 0xFF) {
                    out[row + x] = (byte) transparent;
                    continue;
                }
                var offset = offsets[matrixRow | (x & 7)];
                var red = Math.clamp((pixel & 0xFF) + offset, 0, 255);
                var green = Math.clamp(((pixel >>> 8) & 0xFF) + offset, 0, 255);
                var blue = Math.clamp(((pixel >>> 16) & 0xFF) + offset, 0, 255);
                out[row + x] = (byte) palette.indexOf(red | green << 8 | blue << 16);
            }
        }
    }

    /**
     * Maps pixels with error diffusion in serpentine order.
     *
     * @param pixels      the packed RGBA pixels, row-major
     * @param width       the rectangle width
     * @param height      the rectangle height
     * @param palette     the palette the indices refer to
     * @param transparent the transparent index
     * @param sierra      {@code true} for the Sierra kernel, {@code false} for Floyd-Steinberg
     * @param out         the indices
     */
    private void diffuse(int[] pixels, int width, int height, Palette palette, int transparent, boolean sierra,
                         byte[] out) {
        var rowLength = (width + 2 * PAD) * 3;
        if (errors.length < rowLength * 3) {
            errors = new int[rowLength * 3];
        }
        var errors = this.errors;
        Arrays.fill(errors, 0, rowLength * 3, 0);
        var current = 0;
        var below = rowLength;
        var further = rowLength * 2;
        for (var y = 0; y < height; y++) {
            var forward = (y & 1) == 0;
            var step = forward ? 1 : -1;
            var row = y * width;
            for (var i = 0; i < width; i++) {
                var x = forward ? i : width - 1 - i;
                var pixel = pixels[row + x];
                var e = current + (x + PAD) * 3;
                if (pixel >>> 24 != 0xFF) {
                    out[row + x] = (byte) transparent;
                    continue;
                }
                var red = Math.clamp((pixel & 0xFF) + ((errors[e] + 16) >> 5), 0, 255);
                var green = Math.clamp(((pixel >>> 8) & 0xFF) + ((errors[e + 1] + 16) >> 5), 0, 255);
                var blue = Math.clamp(((pixel >>> 16) & 0xFF) + ((errors[e + 2] + 16) >> 5), 0, 255);
                var index = palette.indexOf(red | green << 8 | blue << 16);
                out[row + x] = (byte) index;
                var errorRed = red - palette.red(index);
                var errorGreen = green - palette.green(index);
                var errorBlue = blue - palette.blue(index);
                var s = step * 3;
                var b = below + (x + PAD) * 3;
                if (sierra) {
                    var f = further + (x + PAD) * 3;
                    spread(errors, e + s, errorRed, errorGreen, errorBlue, 5);
                    spread(errors, e + 2 * s, errorRed, errorGreen, errorBlue, 3);
                    spread(errors, b - 2 * s, errorRed, errorGreen, errorBlue, 2);
                    spread(errors, b - s, errorRed, errorGreen, errorBlue, 4);
                    spread(errors, b, errorRed, errorGreen, errorBlue, 5);
                    spread(errors, b + s, errorRed, errorGreen, errorBlue, 4);
                    spread(errors, b + 2 * s, errorRed, errorGreen, errorBlue, 2);
                    spread(errors, f - s, errorRed, errorGreen, errorBlue, 2);
                    spread(errors, f, errorRed, errorGreen, errorBlue, 3);
                    spread(errors, f + s, errorRed, errorGreen, errorBlue, 2);
                } else {
                    spread(errors, e + s, errorRed, errorGreen, errorBlue, 14);
                    spread(errors, b - s, errorRed, errorGreen, errorBlue, 6);
                    spread(errors, b, errorRed, errorGreen, errorBlue, 10);
                    spread(errors, b + s, errorRed, errorGreen, errorBlue, 2);
                }
            }
            Arrays.fill(errors, current, current + rowLength, 0);
            var recycled = current;
            current = below;
            below = further;
            further = recycled;
        }
    }

    /**
     * Adds a weighted share of an error to one position of an error row.
     *
     * @param errors the error rows
     * @param offset the index of the position's red error
     * @param red    the red error
     * @param green  the green error
     * @param blue   the blue error
     * @param weight the share, in thirty-seconds
     */
    private static void spread(int[] errors, int offset, int red, int green, int blue, int weight) {
        errors[offset] += red * weight;
        errors[offset + 1] += green * weight;
        errors[offset + 2] += blue * weight;
    }
}
