package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;

import java.util.Optional;

/**
 * Places packed RGBA pictures on larger canvases and finds the uniform borders of pictures.
 *
 * <p>Every method works on 8-bit {@link RgbaPicture}s and 16-bit {@link Rgba16Picture}s alike;
 * {@link #toPacked(Picture)} brings any picture to one of the two at its own depth.
 */
final class Canvas {
    /**
     * Prevents instantiation.
     */
    private Canvas() {
        throw new AssertionError();
    }

    /**
     * Converts a picture to packed RGBA at its own depth.
     *
     * @param picture the picture to convert
     * @return the picture itself if it is packed, an {@link RgbaPicture} for an 8-bit planar picture, or an
     * {@link Rgba16Picture} for a {@link Yuv16Picture}
     */
    static Picture toPacked(Picture picture) {
        return switch (picture) {
            case RgbaPicture rgba -> rgba;
            case Rgba16Picture rgba -> rgba;
            case YuvPicture yuv -> ImageOps.toRgba(yuv);
            case Yuv16Picture yuv -> {
                OpChecks.checkArray(4L * yuv.width() * yuv.height());
                yield ManagedColor.toRgba16(yuv);
            }
        };
    }

    /**
     * Places a packed picture on a canvas, filling the rest as {@code mode} describes.
     *
     * @param picture the packed picture, 8 or 16-bit
     * @param canvasWidth the canvas width
     * @param canvasHeight the canvas height
     * @param x the canvas column of the picture's left edge
     * @param y the canvas row of the picture's top edge
     * @param mode how to fill the canvas outside the picture
     * @param argbBackground the background as {@code 0xAARRGGBB}, for {@link ExtendMode#BACKGROUND}
     * @return the canvas, at the picture's depth
     */
    static Picture place(Picture picture, int canvasWidth, int canvasHeight, int x, int y, ExtendMode mode,
                         int argbBackground) {
        var hasAlpha = picture.hasAlpha() || (mode == ExtendMode.BACKGROUND && argbBackground >>> 24 != 255);
        var width = picture.width();
        var height = picture.height();
        var columns = new int[canvasWidth];
        for (var i = 0; i < canvasWidth; i++) {
            columns[i] = source(i - (long) x, width, mode);
        }
        return switch (picture) {
            case RgbaPicture rgba -> {
                OpChecks.checkArray(4L * canvasWidth * canvasHeight);
                var out = RgbaPicture.allocate(canvasWidth, canvasHeight, hasAlpha);
                var dst = out.pixels();
                var src = rgba.pixels();
                for (var j = 0; j < canvasHeight; j++) {
                    var row = source(j - (long) y, height, mode);
                    var offset = j * out.stride();
                    for (var i = 0; i < canvasWidth; i++, offset += 4) {
                        var column = columns[i];
                        if (row < 0 || column < 0) {
                            dst[offset] = (byte) (argbBackground >>> 16);
                            dst[offset + 1] = (byte) (argbBackground >>> 8);
                            dst[offset + 2] = (byte) argbBackground;
                            dst[offset + 3] = (byte) (argbBackground >>> 24);
                        } else {
                            var p = row * rgba.stride() + 4 * column;
                            dst[offset] = src[p];
                            dst[offset + 1] = src[p + 1];
                            dst[offset + 2] = src[p + 2];
                            dst[offset + 3] = rgba.hasAlpha() ? src[p + 3] : (byte) 255;
                        }
                    }
                }
                yield out;
            }
            case Rgba16Picture rgba -> {
                OpChecks.checkArray(4L * canvasWidth * canvasHeight);
                var out = Rgba16Picture.allocate(canvasWidth, canvasHeight, hasAlpha);
                var dst = out.pixels();
                var src = rgba.pixels();
                for (var j = 0; j < canvasHeight; j++) {
                    var row = source(j - (long) y, height, mode);
                    var offset = j * out.stride();
                    for (var i = 0; i < canvasWidth; i++, offset += 4) {
                        var column = columns[i];
                        if (row < 0 || column < 0) {
                            dst[offset] = (short) (((argbBackground >>> 16) & 0xFF) * 257);
                            dst[offset + 1] = (short) (((argbBackground >>> 8) & 0xFF) * 257);
                            dst[offset + 2] = (short) ((argbBackground & 0xFF) * 257);
                            dst[offset + 3] = (short) ((argbBackground >>> 24) * 257);
                        } else {
                            var p = row * rgba.stride() + 4 * column;
                            dst[offset] = src[p];
                            dst[offset + 1] = src[p + 1];
                            dst[offset + 2] = src[p + 2];
                            dst[offset + 3] = rgba.hasAlpha() ? src[p + 3] : (short) 0xFFFF;
                        }
                    }
                }
                yield out;
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + picture);
        };
    }

    /**
     * Maps a canvas coordinate to the picture coordinate that fills it.
     *
     * @param position the coordinate relative to the picture's edge, which may lie outside it
     * @param size the picture's size along the axis
     * @param mode the fill mode
     * @return the picture coordinate, or {@code -1} for the background
     */
    static int source(long position, int size, ExtendMode mode) {
        if (position >= 0 && position < size) {
            return (int) position;
        }
        return switch (mode) {
            case BACKGROUND -> -1;
            case REPLICATE -> position < 0 ? 0 : size - 1;
            case REPEAT -> (int) Math.floorMod(position, (long) size);
            case MIRROR -> {
                var period = 2L * size;
                var folded = Math.floorMod(position, period);
                yield (int) (folded < size ? folded : period - 1 - folded);
            }
        };
    }

    /**
     * Finds the smallest rectangle outside which every pixel of a packed picture is within a
     * tolerance of a color.
     *
     * @param picture the packed picture, 8 or 16-bit
     * @param argb the border color as {@code 0xAARRGGBB}, or {@code -1L} to use the color of the top-left pixel
     * @param tolerance the largest difference of any channel, alpha included, from the border color that still counts
     * as border, in 8-bit units
     * @return the rectangle, empty when every pixel matches
     */
    static Optional<Region> contentBounds(Picture picture, long argb, int tolerance) {
        var width = picture.width();
        var height = picture.height();
        var reference = new int[4];
        int scale;
        switch (picture) {
            case RgbaPicture rgba -> {
                scale = 1;
                if (argb < 0) {
                    pixel(rgba, 0, 0, reference);
                } else {
                    unpack((int) argb, 1, reference);
                }
            }
            case Rgba16Picture rgba -> {
                scale = 257;
                if (argb < 0) {
                    pixel(rgba, 0, 0, reference);
                } else {
                    unpack((int) argb, 257, reference);
                }
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + picture);
        }
        var limit = (long) tolerance * scale;
        var sample = new int[4];
        var top = 0;
        while (top < height && rowMatches(picture, top, 0, width, reference, limit, sample)) {
            top++;
        }
        if (top == height) {
            return Optional.empty();
        }
        var bottom = height;
        while (bottom > top + 1 && rowMatches(picture, bottom - 1, 0, width, reference, limit, sample)) {
            bottom--;
        }
        var left = 0;
        while (left < width && columnMatches(picture, left, top, bottom, reference, limit, sample)) {
            left++;
        }
        var right = width;
        while (right > left + 1 && columnMatches(picture, right - 1, top, bottom, reference, limit, sample)) {
            right--;
        }
        return Optional.of(new Region(left, top, right - left, bottom - top));
    }

    /**
     * Returns whether every pixel of part of a row is within a tolerance of a color.
     *
     * @param picture the packed picture
     * @param y the row
     * @param from the first column
     * @param to the column after the last one
     * @param reference the color's channels
     * @param limit the largest difference of a channel
     * @param sample a scratch array of four channels
     * @return whether the row matches
     */
    private static boolean rowMatches(Picture picture, int y, int from, int to, int[] reference, long limit, int[] sample) {
        for (var x = from; x < to; x++) {
            if (!matches(picture, x, y, reference, limit, sample)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether every pixel of part of a column is within a tolerance of a color.
     *
     * @param picture the packed picture
     * @param x the column
     * @param from the first row
     * @param to the row after the last one
     * @param reference the color's channels
     * @param limit the largest difference of a channel
     * @param sample a scratch array of four channels
     * @return whether the column matches
     */
    private static boolean columnMatches(Picture picture, int x, int from, int to, int[] reference, long limit,
                                         int[] sample) {
        for (var y = from; y < to; y++) {
            if (!matches(picture, x, y, reference, limit, sample)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether one pixel is within a tolerance of a color.
     *
     * @param picture the packed picture
     * @param x the column
     * @param y the row
     * @param reference the color's channels
     * @param limit the largest difference of a channel
     * @param sample a scratch array of four channels
     * @return whether the pixel matches
     */
    private static boolean matches(Picture picture, int x, int y, int[] reference, long limit, int[] sample) {
        switch (picture) {
            case RgbaPicture rgba -> pixel(rgba, x, y, sample);
            case Rgba16Picture rgba -> pixel(rgba, x, y, sample);
            default -> throw new IllegalArgumentException("not a packed picture: " + picture);
        }
        for (var c = 0; c < 4; c++) {
            if (Math.abs(sample[c] - reference[c]) > limit) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads the channels of an 8-bit pixel, with opaque alpha when the picture reports none.
     *
     * @param picture the picture to read
     * @param x the column
     * @param y the row
     * @param out the destination of red, green, blue and alpha
     */
    private static void pixel(RgbaPicture picture, int x, int y, int[] out) {
        var p = y * picture.stride() + 4 * x;
        var pixels = picture.pixels();
        out[0] = pixels[p] & 0xFF;
        out[1] = pixels[p + 1] & 0xFF;
        out[2] = pixels[p + 2] & 0xFF;
        out[3] = picture.hasAlpha() ? pixels[p + 3] & 0xFF : 255;
    }

    /**
     * Reads the channels of a 16-bit pixel, with opaque alpha when the picture reports none.
     *
     * @param picture the picture to read
     * @param x the column
     * @param y the row
     * @param out the destination of red, green, blue and alpha
     */
    private static void pixel(Rgba16Picture picture, int x, int y, int[] out) {
        var p = y * picture.stride() + 4 * x;
        var pixels = picture.pixels();
        out[0] = pixels[p] & 0xFFFF;
        out[1] = pixels[p + 1] & 0xFFFF;
        out[2] = pixels[p + 2] & 0xFFFF;
        out[3] = picture.hasAlpha() ? pixels[p + 3] & 0xFFFF : 0xFFFF;
    }

    /**
     * Splits an {@code 0xAARRGGBB} color into channels.
     *
     * @param argb the color
     * @param scale the factor applied to each 8-bit channel
     * @param out the destination of red, green, blue and alpha
     */
    private static void unpack(int argb, int scale, int[] out) {
        out[0] = ((argb >>> 16) & 0xFF) * scale;
        out[1] = ((argb >>> 8) & 0xFF) * scale;
        out[2] = (argb & 0xFF) * scale;
        out[3] = (argb >>> 24) * scale;
    }
}
