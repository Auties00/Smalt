package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.quantize.ExactColors;
import com.github.auties00.smalt.internal.quantize.IndexMapper;
import com.github.auties00.smalt.internal.quantize.MedianCut;
import com.github.auties00.smalt.internal.quantize.NeuQuant;
import com.github.auties00.smalt.internal.quantize.Palette;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Objects;

/**
 * Reduces pictures to a palette of at most 256 colors, with optional dithering.
 *
 * <p>The picture is converted to 8-bit RGBA first (16-bit and Y'CbCr pictures included). Pixels
 * whose alpha is below the options' threshold become transparent and take a dedicated transparent
 * entry; the others are treated as opaque. When the opaque pixels have few enough distinct colors
 * to fit the palette, the palette is exactly those colors, in order of first appearance, and no
 * pixel is dithered, so the reduction is lossless. Otherwise a palette is built with the options'
 * {@link QuantizeMethod} and the pixels are mapped to it with the options' {@link Dithering}. The
 * result is deterministic.
 *
 * @implNote This implementation shares its palette builders, nearest-color search (a Vector API kernel with a scalar
 * twin) and dithering with the GIF encoder. The NeuQuant builder is a port of Anthony Dekker's {@code neuquant.c}; the
 * median cut and the dithering are Smalt's own code.
 */
public final class ImageQuantization {
    /**
     * Prevents instantiation.
     */
    private ImageQuantization() {
        throw new AssertionError();
    }

    /**
     * Reduces a picture to a palette with the default options.
     *
     * @param picture the picture to reduce
     * @return the quantized image
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the work arrays do not fit one Java array
     * @see QuantizeOptions#defaults()
     */
    public static QuantizedImage quantize(Picture picture) {
        return quantize(picture, QuantizeOptions.defaults());
    }

    /**
     * Reduces a picture to a palette.
     *
     * @param picture the picture to reduce
     * @param options the palette size, algorithm, dithering and alpha threshold
     * @return the quantized image
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the work arrays do not fit one Java array
     */
    public static QuantizedImage quantize(Picture picture, QuantizeOptions options) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(options, "options");
            try {
                var rgba = ImageOps.toRgba(ImageOps.toEightBit(picture));
                return quantize(rgba, options);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Quantizes an 8-bit RGBA picture.
     *
     * @param rgba the picture to quantize, 8-bit
     * @param options the palette size, algorithm and dithering
     * @return the quantized image
     */
    private static QuantizedImage quantize(RgbaPicture rgba, QuantizeOptions options) {
        var width = rgba.width();
        var height = rgba.height();
        var count = (long) width * height;
        OpChecks.checkArray(count);
        var pixels = new int[(int) count];
        var opaque = new int[(int) count];
        var opaqueCount = 0;
        var src = rgba.pixels();
        var threshold = options.alphaThreshold();
        for (var y = 0; y < height; y++) {
            var p = y * rgba.stride();
            for (var x = 0; x < width; x++, p += 4) {
                var color = (src[p] & 0xFF) | (src[p + 1] & 0xFF) << 8 | (src[p + 2] & 0xFF) << 16;
                if (!rgba.hasAlpha() || (src[p + 3] & 0xFF) >= threshold) {
                    color |= 0xFF00_0000;
                    opaque[opaqueCount++] = color;
                }
                pixels[y * width + x] = color;
            }
        }
        var transparent = opaqueCount < count;
        var limit = options.colors() - (transparent ? 1 : 0);
        var indices = new byte[(int) count];
        if (opaqueCount == 0) {
            return new QuantizedImage(width, height, new int[]{0}, indices, 0);
        }
        var exact = new ExactColors();
        Palette palette;
        var dithering = Dithering.NONE;
        if (exact.collect(opaque, opaqueCount, limit)) {
            palette = new Palette(exact.colors(), exact.size());
        } else {
            var entries = switch (options.method()) {
                case MEDIAN_CUT -> MedianCut.quantize(opaque, opaqueCount, limit);
                case NEUQUANT -> NeuQuant.quantize(opaque, opaqueCount, limit, options.sampleFactor());
            };
            palette = new Palette(entries);
            dithering = options.dithering();
        }
        var size = palette.size();
        var transparentIndex = transparent ? size : -1;
        new IndexMapper().map(pixels, width, height, palette, Math.max(transparentIndex, 0), dithering, indices);
        var colors = new int[size + (transparent ? 1 : 0)];
        for (var i = 0; i < size; i++) {
            var color = palette.color(i);
            colors[i] = 0xFF00_0000 | (color & 0xFF) << 16 | (color & 0xFF00) | (color >>> 16) & 0xFF;
        }
        return new QuantizedImage(width, height, colors, indices, transparentIndex);
    }
}
