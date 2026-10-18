package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Runs the per-pixel loops of the tone adjustments on packed RGBA pictures, 8 or 16-bit: lookup
 * tables, fixed-point channel mixing, histograms and arbitrary per-pixel functions.
 *
 * <p>Every method keeps alpha (opaque when the picture reports none) and returns a new tightly
 * packed picture at the input's depth; rows are split into bands computed by
 * {@link ParallelBands}.
 */
final class Tone {
    /**
     * The approximate work per pixel of a lookup or mix, weighed against
     * {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long PIXEL_WORK = 8;

    /**
     * Prevents instantiation.
     */
    private Tone() {
        throw new AssertionError();
    }

    /**
     * Computes a new color for one pixel.
     */
    @FunctionalInterface
    interface PixelFunction {
        /**
         * Replaces the color channels of a pixel.
         *
         * @param rgba the red, green, blue and alpha samples, at the picture's depth; the first three are replaced
         */
        void apply(int[] rgba);
    }

    /**
     * Returns the largest sample value of a packed picture.
     *
     * @param packed the packed picture
     * @return {@code 255} or {@code 65535}
     */
    static int max(Picture packed) {
        return packed instanceof Rgba16Picture ? 65535 : 255;
    }

    /**
     * Applies a lookup table to each color channel.
     *
     * @param packed the packed picture
     * @param red the table of red, {@code max + 1} entries within {@code 0..max}
     * @param green the table of green
     * @param blue the table of blue
     * @return the adjusted picture
     */
    static Picture lookup(Picture packed, int[] red, int[] green, int[] blue) {
        var out = allocate(packed);
        var height = packed.height();
        var bands = ParallelBands.bands(height, PIXEL_WORK * packed.width() * height);
        switch (packed) {
            case RgbaPicture rgba -> {
                var r = narrow(red);
                var g = narrow(green);
                var b = narrow(blue);
                var dst = (RgbaPicture) out;
                ParallelBands.run(bands, band -> {
                    var src = rgba.pixels();
                    var pixels = dst.pixels();
                    for (var y = ParallelBands.start(band, bands, height); y < ParallelBands.start(band + 1, bands, height); y++) {
                        var s = y * rgba.stride();
                        var d = y * dst.stride();
                        for (var x = 0; x < rgba.width(); x++, s += 4, d += 4) {
                            pixels[d] = r[src[s] & 0xFF];
                            pixels[d + 1] = g[src[s + 1] & 0xFF];
                            pixels[d + 2] = b[src[s + 2] & 0xFF];
                            pixels[d + 3] = rgba.hasAlpha() ? src[s + 3] : (byte) 255;
                        }
                    }
                });
            }
            case Rgba16Picture rgba -> {
                var dst = (Rgba16Picture) out;
                ParallelBands.run(bands, band -> {
                    var src = rgba.pixels();
                    var pixels = dst.pixels();
                    for (var y = ParallelBands.start(band, bands, height); y < ParallelBands.start(band + 1, bands, height); y++) {
                        var s = y * rgba.stride();
                        var d = y * dst.stride();
                        for (var x = 0; x < rgba.width(); x++, s += 4, d += 4) {
                            pixels[d] = (short) red[src[s] & 0xFFFF];
                            pixels[d + 1] = (short) green[src[s + 1] & 0xFFFF];
                            pixels[d + 2] = (short) blue[src[s + 2] & 0xFFFF];
                            pixels[d + 3] = rgba.hasAlpha() ? src[s + 3] : (short) 0xFFFF;
                        }
                    }
                });
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + packed);
        }
        return out;
    }

    /**
     * Mixes the color channels through a fixed-point matrix, as {@link EffectKernel#mixRow}
     * describes; 16-bit samples use the same coefficients with 64-bit sums.
     *
     * @param packed the packed picture
     * @param matrix the twelve coefficients in units of {@code 2^-16}, without offsets other than the rounding term
     * {@code 32768}
     * @return the mixed picture
     */
    static Picture mix(Picture packed, int[] matrix) {
        var out = allocate(packed);
        var height = packed.height();
        var width = packed.width();
        var bands = ParallelBands.bands(height, PIXEL_WORK * width * height);
        switch (packed) {
            case RgbaPicture rgba -> {
                var dst = (RgbaPicture) out;
                ParallelBands.run(bands, band -> {
                    var kernel = EffectKernel.INSTANCE;
                    for (var y = ParallelBands.start(band, bands, height); y < ParallelBands.start(band + 1, bands, height); y++) {
                        kernel.mixRow(rgba.pixels(), y * rgba.stride(), width, matrix, dst.pixels(), y * dst.stride());
                        if (!rgba.hasAlpha()) {
                            for (var x = 0; x < width; x++) {
                                dst.pixels()[y * dst.stride() + 4 * x + 3] = (byte) 255;
                            }
                        }
                    }
                });
            }
            case Rgba16Picture rgba -> {
                var dst = (Rgba16Picture) out;
                ParallelBands.run(bands, band -> {
                    var src = rgba.pixels();
                    var pixels = dst.pixels();
                    for (var y = ParallelBands.start(band, bands, height); y < ParallelBands.start(band + 1, bands, height); y++) {
                        var s = y * rgba.stride();
                        var d = y * dst.stride();
                        for (var x = 0; x < width; x++, s += 4, d += 4) {
                            long r = src[s] & 0xFFFF;
                            long g = src[s + 1] & 0xFFFF;
                            long b = src[s + 2] & 0xFFFF;
                            for (var c = 0; c < 3; c++) {
                                var sum = matrix[4 * c] * r + matrix[4 * c + 1] * g + matrix[4 * c + 2] * b + matrix[4 * c + 3];
                                pixels[d + c] = (short) Math.clamp(sum >> 16, 0, 65535);
                            }
                            pixels[d + 3] = rgba.hasAlpha() ? src[s + 3] : (short) 0xFFFF;
                        }
                    }
                });
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + packed);
        }
        return out;
    }

    /**
     * Applies a function to every pixel.
     *
     * @param packed the packed picture
     * @param function the function, called with the samples at the picture's depth
     * @return the adjusted picture
     */
    static Picture map(Picture packed, PixelFunction function) {
        var out = allocate(packed);
        var height = packed.height();
        var width = packed.width();
        var bands = ParallelBands.bands(height, 4 * PIXEL_WORK * width * height);
        ParallelBands.run(bands, band -> {
            var rgba = new int[4];
            for (var y = ParallelBands.start(band, bands, height); y < ParallelBands.start(band + 1, bands, height); y++) {
                for (var x = 0; x < width; x++) {
                    read(packed, x, y, rgba);
                    function.apply(rgba);
                    write(out, x, y, rgba);
                }
            }
        });
        return out;
    }

    /**
     * Counts the values of each channel.
     *
     * @param packed the packed picture
     * @return four histograms (red, green, blue, alpha) of {@code max + 1} bins
     */
    static long[][] histograms(Picture packed) {
        var max = max(packed);
        var histograms = new long[4][max + 1];
        var rgba = new int[4];
        for (var y = 0; y < packed.height(); y++) {
            for (var x = 0; x < packed.width(); x++) {
                read(packed, x, y, rgba);
                histograms[0][rgba[0]]++;
                histograms[1][rgba[1]]++;
                histograms[2][rgba[2]]++;
                histograms[3][rgba[3]]++;
            }
        }
        return histograms;
    }

    /**
     * Counts the BT.601 lumas of the pixels, as {@link #luma601(int, int, int)} computes them.
     *
     * @param packed the packed picture
     * @return the histogram of {@code max + 1} bins
     */
    static long[] lumaHistogram(Picture packed) {
        var histogram = new long[max(packed) + 1];
        var rgba = new int[4];
        for (var y = 0; y < packed.height(); y++) {
            for (var x = 0; x < packed.width(); x++) {
                read(packed, x, y, rgba);
                histogram[luma601(rgba[0], rgba[1], rgba[2])]++;
            }
        }
        return histogram;
    }

    /**
     * Returns the BT.601 luma of a color as Pillow's {@code L} conversion computes it.
     *
     * @param r the red sample
     * @param g the green sample
     * @param b the blue sample
     * @return {@code (19595 r + 38470 g + 7471 b + 32768) >> 16}
     */
    static int luma601(int r, int g, int b) {
        return (int) ((19595L * r + 38470L * g + 7471L * b + 0x8000) >> 16);
    }

    /**
     * Reads one pixel, with opaque alpha when the picture reports none.
     *
     * @param packed the packed picture
     * @param x the column
     * @param y the row
     * @param rgba the destination of the four samples
     */
    static void read(Picture packed, int x, int y, int[] rgba) {
        switch (packed) {
            case RgbaPicture picture -> {
                var p = y * picture.stride() + 4 * x;
                var pixels = picture.pixels();
                rgba[0] = pixels[p] & 0xFF;
                rgba[1] = pixels[p + 1] & 0xFF;
                rgba[2] = pixels[p + 2] & 0xFF;
                rgba[3] = picture.hasAlpha() ? pixels[p + 3] & 0xFF : 255;
            }
            case Rgba16Picture picture -> {
                var p = y * picture.stride() + 4 * x;
                var pixels = picture.pixels();
                rgba[0] = pixels[p] & 0xFFFF;
                rgba[1] = pixels[p + 1] & 0xFFFF;
                rgba[2] = pixels[p + 2] & 0xFFFF;
                rgba[3] = picture.hasAlpha() ? pixels[p + 3] & 0xFFFF : 0xFFFF;
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + packed);
        }
    }

    /**
     * Writes one pixel.
     *
     * @param packed the packed picture
     * @param x the column
     * @param y the row
     * @param rgba the four samples, within the picture's depth
     */
    static void write(Picture packed, int x, int y, int[] rgba) {
        switch (packed) {
            case RgbaPicture picture -> {
                var p = y * picture.stride() + 4 * x;
                var pixels = picture.pixels();
                pixels[p] = (byte) rgba[0];
                pixels[p + 1] = (byte) rgba[1];
                pixels[p + 2] = (byte) rgba[2];
                pixels[p + 3] = (byte) rgba[3];
            }
            case Rgba16Picture picture -> {
                var p = y * picture.stride() + 4 * x;
                var pixels = picture.pixels();
                pixels[p] = (short) rgba[0];
                pixels[p + 1] = (short) rgba[1];
                pixels[p + 2] = (short) rgba[2];
                pixels[p + 3] = (short) rgba[3];
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + packed);
        }
    }

    /**
     * Allocates a picture of the size, depth and alpha flag of another.
     *
     * @param packed the packed picture
     * @return the new picture
     */
    static Picture allocate(Picture packed) {
        var width = packed.width();
        var height = packed.height();
        return switch (packed) {
            case RgbaPicture rgba -> {
                OpChecks.checkArray(4L * width * height);
                yield RgbaPicture.allocate(width, height, rgba.hasAlpha());
            }
            case Rgba16Picture rgba -> {
                OpChecks.checkArray(4L * width * height);
                yield Rgba16Picture.allocate(width, height, rgba.hasAlpha());
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + packed);
        };
    }

    /**
     * Converts an 8-bit table to bytes.
     *
     * @param table the 256 entries
     * @return the entries as bytes
     */
    private static byte[] narrow(int[] table) {
        var bytes = new byte[table.length];
        for (var i = 0; i < table.length; i++) {
            bytes[i] = (byte) table[i];
        }
        return bytes;
    }
}
