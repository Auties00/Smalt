package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Implements the pixel-copying and compositing operations of {@link ImageOps}: crop, pad,
 * flatten and the opacity scan. Arguments are validated by the caller.
 */
final class Geometry {
    /**
     * Prevents instantiation.
     */
    private Geometry() {
        throw new AssertionError();
    }

    /**
     * Copies a rectangle of a picture.
     *
     * @param picture the picture to copy from
     * @param x the rectangle's left edge
     * @param y the rectangle's top edge
     * @param width the rectangle's width
     * @param height the rectangle's height
     * @return the cropped picture
     */
    static Picture crop(Picture picture, int x, int y, int width, int height) {
        return switch (picture) {
            case RgbaPicture rgba -> {
                var out = RgbaPicture.allocate(width, height, rgba.hasAlpha());
                Planes.copy(rgba.pixels(), y * rgba.stride() + 4 * x, rgba.stride(), out.pixels(), 0, out.stride(), 4 * width, height);
                yield out;
            }
            case YuvPicture yuv -> {
                var format = yuv.chromaFormat();
                var out = YuvPicture.allocate(width, height, format, yuv.hasAlpha(), yuv.color());
                Planes.copy(yuv.y(), y * yuv.yStride() + x, yuv.yStride(), out.y(), 0, out.yStride(), width, height);
                if (format.hasChroma()) {
                    var offset = (y >> format.shiftY()) * yuv.uvStride() + (x >> format.shiftX());
                    Planes.copy(yuv.u(), offset, yuv.uvStride(), out.u(), 0, out.uvStride(), out.chromaWidth(), out.chromaHeight());
                    Planes.copy(yuv.v(), offset, yuv.uvStride(), out.v(), 0, out.uvStride(), out.chromaWidth(), out.chromaHeight());
                }
                if (yuv.hasAlpha()) {
                    Planes.copy(yuv.alpha(), y * yuv.alphaStride() + x, yuv.alphaStride(), out.alpha(), 0, out.alphaStride(), width, height);
                }
                yield out;
            }
            case Rgba16Picture rgba -> HighDepth.crop(rgba, x, y, width, height);
            case Yuv16Picture yuv -> HighDepth.crop(yuv, x, y, width, height);
        };
    }

    /**
     * Places a picture on a canvas filled with a background color.
     *
     * @param rgba the picture
     * @param canvasWidth the canvas width
     * @param canvasHeight the canvas height
     * @param x the canvas column of the picture's left edge
     * @param y the canvas row of the picture's top edge
     * @param argbBackground the background as {@code 0xAARRGGBB}
     * @return the canvas
     */
    static RgbaPicture pad(RgbaPicture rgba, int canvasWidth, int canvasHeight, int x, int y, int argbBackground) {
        OpChecks.checkArray(4L * canvasWidth * canvasHeight);
        var backgroundAlpha = argbBackground >>> 24;
        var out = RgbaPicture.allocate(canvasWidth, canvasHeight, backgroundAlpha != 255 || rgba.hasAlpha());
        var pixels = out.pixels();
        var left = (int) Math.max(0, (long) x);
        var top = (int) Math.max(0, (long) y);
        var right = (int) Math.min(canvasWidth, (long) x + rgba.width());
        var bottom = (int) Math.min(canvasHeight, (long) y + rgba.height());
        var covers = left == 0 && top == 0 && right == canvasWidth && bottom == canvasHeight;
        if (!covers && argbBackground != 0) {
            fill(pixels, canvasWidth, canvasHeight, argbBackground);
        }
        if (left < right && top < bottom) {
            var length = 4 * (right - left);
            for (var row = top; row < bottom; row++) {
                var source = (row - y) * rgba.stride() + 4 * (left - x);
                System.arraycopy(rgba.pixels(), source, pixels, row * out.stride() + 4 * left, length);
                if (!rgba.hasAlpha()) {
                    for (var i = 3; i < length; i += 4) {
                        pixels[row * out.stride() + 4 * left + i] = (byte) 255;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Fills a tightly packed RGBA picture with one color.
     *
     * @param pixels the samples
     * @param width the width in pixels
     * @param height the height in pixels
     * @param argb the color as {@code 0xAARRGGBB}
     */
    private static void fill(byte[] pixels, int width, int height, int argb) {
        pixels[0] = (byte) (argb >>> 16);
        pixels[1] = (byte) (argb >>> 8);
        pixels[2] = (byte) argb;
        pixels[3] = (byte) (argb >>> 24);
        var total = 4 * width * height;
        for (var filled = 4; filled < total; filled *= 2) {
            System.arraycopy(pixels, 0, pixels, filled, Math.min(filled, total - filled));
        }
    }

    /**
     * Composites a picture with alpha over an opaque color; a large RGBA picture is composited in
     * bands of rows on several threads.
     *
     * @param picture the picture, which reports alpha
     * @param rgb the background as {@code 0xRRGGBB}
     * @return the opaque picture
     */
    static Picture flatten(Picture picture, int rgb) {
        var kernel = PixelKernel.INSTANCE;
        return switch (picture) {
            case RgbaPicture rgba -> {
                OpChecks.checkArray(4L * rgba.width() * rgba.height());
                var out = RgbaPicture.allocate(rgba.width(), rgba.height(), false);
                var height = rgba.height();
                var bands = ParallelBands.bands(height, 4L * rgba.width() * height);
                ParallelBands.run(bands, band -> {
                    var last = ParallelBands.start(band + 1, bands, height);
                    for (var row = ParallelBands.start(band, bands, height); row < last; row++) {
                        kernel.flattenRgba(rgba.pixels(), row * rgba.stride(), rgba.width(), out.pixels(), row * out.stride(), rgb);
                    }
                });
                yield out;
            }
            case YuvPicture yuv -> flattenYuv(yuv, rgb);
            // TODO: composite 16-bit pictures at their own depth instead of reducing them first
            case Rgba16Picture rgba -> flatten(HighDepth.toEightBit(rgba), rgb);
            case Yuv16Picture yuv -> flatten(HighDepth.toEightBit(yuv), rgb);
        };
    }

    /**
     * Composites a planar picture with an alpha plane over an opaque color in Y'CbCr.
     *
     * @param yuv the picture, which has an alpha plane
     * @param rgb the background as {@code 0xRRGGBB}
     * @return the opaque picture
     */
    private static YuvPicture flattenYuv(YuvPicture yuv, int rgb) {
        var matrix = ColorMatrix.of(yuv.color());
        var red = (rgb >>> 16) & 0xFF;
        var green = (rgb >>> 8) & 0xFF;
        var blue = rgb & 0xFF;
        var width = yuv.width();
        var height = yuv.height();
        var format = yuv.chromaFormat();
        var out = YuvPicture.allocate(width, height, format, false, yuv.color());
        var background = backgroundSample(matrix.fy, matrix.fk[0], red, green, blue);
        var alpha = yuv.alpha();
        var kernel = PixelKernel.INSTANCE;
        for (var row = 0; row < height; row++) {
            kernel.flattenPlane(yuv.y(), row * yuv.yStride(), alpha, row * yuv.alphaStride(), width, background,
                    out.y(), row * out.yStride());
        }
        if (!format.hasChroma()) {
            return out;
        }
        var backgroundU = backgroundSample(matrix.fu, matrix.fk[1], red, green, blue);
        var backgroundV = backgroundSample(matrix.fv, matrix.fk[2], red, green, blue);
        var chromaWidth = yuv.chromaWidth();
        var chromaHeight = yuv.chromaHeight();
        var spanX = 1 << format.shiftX();
        var spanY = 1 << format.shiftY();
        for (var cy = 0; cy < chromaHeight; cy++) {
            var y0 = cy * spanY;
            var y1 = Math.min(y0 + spanY, height);
            for (var cx = 0; cx < chromaWidth; cx++) {
                var x0 = cx * spanX;
                var x1 = Math.min(x0 + spanX, width);
                var coverage = 0;
                for (var yy = y0; yy < y1; yy++) {
                    for (var xx = x0; xx < x1; xx++) {
                        coverage += alpha[yy * yuv.alphaStride() + xx] & 0xFF;
                    }
                }
                var full = 255 * (y1 - y0) * (x1 - x0);
                var inIndex = cy * yuv.uvStride() + cx;
                var outIndex = cy * out.uvStride() + cx;
                out.u()[outIndex] = (byte) blend(yuv.u()[inIndex] & 0xFF, backgroundU, coverage, full);
                out.v()[outIndex] = (byte) blend(yuv.v()[inIndex] & 0xFF, backgroundV, coverage, full);
            }
        }
        return out;
    }

    /**
     * Converts one background color component with a forward matrix row.
     *
     * @param row the forward coefficients multiplying R, G and B
     * @param constant the forward constant of the component
     * @param red the background red
     * @param green the background green
     * @param blue the background blue
     * @return the background sample
     */
    private static int backgroundSample(int[] row, int constant, int red, int green, int blue) {
        return ScalarColorKernel.clamp((row[0] * red + row[1] * green + row[2] * blue + constant) >> ColorMatrix.BITS);
    }

    /**
     * Blends a sample with a background by a coverage fraction, rounding half up.
     *
     * @param sample the foreground sample
     * @param background the background sample
     * @param coverage the summed alpha of the covered pixels
     * @param full the summed alpha of fully opaque covered pixels; positive
     * @return {@code round((sample * coverage + background * (full - coverage)) / full)}
     */
    private static int blend(int sample, int background, int coverage, int full) {
        return (2 * (sample * coverage + background * (full - coverage)) + full) / (2 * full);
    }

    /**
     * Returns whether every pixel of a picture is opaque.
     *
     * @param picture the picture to inspect
     * @return whether no pixel is transparent
     */
    static boolean isOpaque(Picture picture) {
        if (!picture.hasAlpha()) {
            return true;
        }
        var kernel = PixelKernel.INSTANCE;
        return switch (picture) {
            case RgbaPicture rgba -> {
                for (var row = 0; row < rgba.height(); row++) {
                    if (!kernel.isOpaqueRgba(rgba.pixels(), row * rgba.stride(), rgba.width())) {
                        yield false;
                    }
                }
                yield true;
            }
            case YuvPicture yuv -> {
                for (var row = 0; row < yuv.height(); row++) {
                    if (!kernel.isOpaquePlane(yuv.alpha(), row * yuv.alphaStride(), yuv.width())) {
                        yield false;
                    }
                }
                yield true;
            }
            case Rgba16Picture rgba -> HighDepth.isOpaque(rgba);
            case Yuv16Picture yuv -> HighDepth.isOpaque(yuv);
        };
    }
}
