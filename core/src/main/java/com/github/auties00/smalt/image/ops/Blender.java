package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Composites one packed RGBA picture over another, row by row, through
 * {@link EffectKernel#blend}.
 *
 * <p>The rows of the backdrop that the overlay covers are converted to planar floats normalized to
 * {@code [0, 1]} (decoded to linear light when asked), blended, and converted back: alpha and
 * gamma-encoded colors rounded to nearest, linear colors encoded to the nearest sample. The
 * rest of the backdrop is copied. Rows split into bands computed by {@link ParallelBands}.
 */
final class Blender {
    /**
     * The normalized value of every 8-bit sample.
     */
    private static final float[] NORMALIZED8 = new float[256];

    static {
        for (var i = 0; i < 256; i++) {
            NORMALIZED8[i] = i / 255f;
        }
    }

    /**
     * Prevents instantiation.
     */
    private Blender() {
        throw new AssertionError();
    }

    /**
     * Composites an overlay over a backdrop.
     *
     * @param base the packed backdrop
     * @param overlay the packed overlay
     * @param x the backdrop column of the overlay's left edge
     * @param y the backdrop row of the overlay's top edge
     * @param options the blending
     * @return the composited picture: 16-bit when either input is, with the backdrop's alpha flag
     */
    static Picture composite(Picture base, Picture overlay, int x, int y, CompositeOptions options) {
        var deep = base instanceof Rgba16Picture || overlay instanceof Rgba16Picture;
        if (deep) {
            base = widen(base);
            overlay = widen(overlay);
        }
        var width = base.width();
        var height = base.height();
        var out = copy(base);
        var left = (int) Math.max(0, (long) x);
        var top = (int) Math.max(0, (long) y);
        var right = (int) Math.min(width, (long) x + overlay.width());
        var bottom = (int) Math.min(height, (long) y + overlay.height());
        if (left >= right || top >= bottom || options.opacity() == 0) {
            return out;
        }
        var span = right - left;
        var rows = bottom - top;
        var bands = ParallelBands.bands(rows, 32L * span * rows);
        var source = overlay;
        ParallelBands.run(bands, band -> {
            var planes = new float[8][span];
            var kernel = EffectKernel.INSTANCE;
            for (var row = top + ParallelBands.start(band, bands, rows); row < top + ParallelBands.start(band + 1, bands, rows); row++) {
                decode(out, left, row, span, options.linearLight(), planes, 0);
                decode(source, left - x, row - y, span, options.linearLight(), planes, 4);
                kernel.blend(options.mode(), planes[0], planes[1], planes[2], planes[3], planes[4], planes[5], planes[6],
                        planes[7], (float) options.opacity(), span);
                encode(planes, out, left, row, span, options.linearLight());
            }
        });
        return out;
    }

    /**
     * Widens a packed picture to 16 bits.
     *
     * @param packed the packed picture
     * @return the picture itself if it is 16-bit, otherwise its 16-bit copy
     */
    private static Picture widen(Picture packed) {
        return packed instanceof RgbaPicture rgba ? ImageOps.toSixteenBit(rgba) : packed;
    }

    /**
     * Returns a tightly packed copy of a packed picture, opaque where it reports no alpha.
     *
     * @param packed the packed picture
     * @return the copy
     */
    private static Picture copy(Picture packed) {
        var width = packed.width();
        var height = packed.height();
        return switch (packed) {
            case RgbaPicture rgba -> {
                OpChecks.checkArray(4L * width * height);
                var out = RgbaPicture.allocate(width, height, rgba.hasAlpha());
                for (var y = 0; y < height; y++) {
                    System.arraycopy(rgba.pixels(), y * rgba.stride(), out.pixels(), y * out.stride(), 4 * width);
                }
                if (!rgba.hasAlpha()) {
                    for (var i = 3; i < out.pixels().length; i += 4) {
                        out.pixels()[i] = (byte) 255;
                    }
                }
                yield out;
            }
            case Rgba16Picture rgba -> {
                OpChecks.checkArray(4L * width * height);
                var out = Rgba16Picture.allocate(width, height, rgba.hasAlpha());
                for (var y = 0; y < height; y++) {
                    System.arraycopy(rgba.pixels(), y * rgba.stride(), out.pixels(), y * out.stride(), 4 * width);
                }
                if (!rgba.hasAlpha()) {
                    for (var i = 3; i < out.pixels().length; i += 4) {
                        out.pixels()[i] = (short) 0xFFFF;
                    }
                }
                yield out;
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + packed);
        };
    }

    /**
     * Decodes a run of pixels into four planes of normalized floats.
     *
     * @param packed the packed picture
     * @param x the first column
     * @param y the row
     * @param count the number of pixels
     * @param linear whether to decode the colors to linear light
     * @param planes the destination planes; the four from {@code first} receive red, green, blue and alpha
     * @param first the index of the red plane; green, blue and alpha follow
     */
    private static void decode(Picture packed, int x, int y, int count, boolean linear, float[][] planes, int first) {
        var red = planes[first];
        var green = planes[first + 1];
        var blue = planes[first + 2];
        var alpha = planes[first + 3];
        switch (packed) {
            case RgbaPicture rgba -> {
                var pixels = rgba.pixels();
                var p = y * rgba.stride() + 4 * x;
                for (var i = 0; i < count; i++, p += 4) {
                    if (linear) {
                        red[i] = Srgb.decode8(pixels[p] & 0xFF);
                        green[i] = Srgb.decode8(pixels[p + 1] & 0xFF);
                        blue[i] = Srgb.decode8(pixels[p + 2] & 0xFF);
                    } else {
                        red[i] = NORMALIZED8[pixels[p] & 0xFF];
                        green[i] = NORMALIZED8[pixels[p + 1] & 0xFF];
                        blue[i] = NORMALIZED8[pixels[p + 2] & 0xFF];
                    }
                    alpha[i] = rgba.hasAlpha() ? NORMALIZED8[pixels[p + 3] & 0xFF] : 1;
                }
            }
            case Rgba16Picture rgba -> {
                var pixels = rgba.pixels();
                var p = y * rgba.stride() + 4 * x;
                for (var i = 0; i < count; i++, p += 4) {
                    for (var c = 0; c < 3; c++) {
                        var value = (pixels[p + c] & 0xFFFF) / 65535f;
                        planes[first + c][i] = linear ? (float) Srgb.decode(value) : value;
                    }
                    alpha[i] = rgba.hasAlpha() ? (pixels[p + 3] & 0xFFFF) / 65535f : 1;
                }
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + packed);
        }
    }

    /**
     * Encodes four planes of normalized floats into a run of pixels.
     *
     * @param planes the planes: red, green, blue and alpha first
     * @param packed the packed picture
     * @param x the first column
     * @param y the row
     * @param count the number of pixels
     * @param linear whether the colors are in linear light
     */
    private static void encode(float[][] planes, Picture packed, int x, int y, int count, boolean linear) {
        switch (packed) {
            case RgbaPicture rgba -> {
                var pixels = rgba.pixels();
                var p = y * rgba.stride() + 4 * x;
                for (var i = 0; i < count; i++, p += 4) {
                    var a = rgba.hasAlpha() ? Warp.clamp(planes[3][i] * 255, 255) : 255;
                    for (var c = 0; c < 3; c++) {
                        var value = planes[c][i];
                        pixels[p + c] = (byte) (a == 0 ? 0 : linear ? Srgb.encode8(value) : Warp.clamp(value * 255, 255));
                    }
                    pixels[p + 3] = (byte) a;
                }
            }
            case Rgba16Picture rgba -> {
                var pixels = rgba.pixels();
                var p = y * rgba.stride() + 4 * x;
                for (var i = 0; i < count; i++, p += 4) {
                    var a = rgba.hasAlpha() ? Warp.clamp(planes[3][i] * 65535, 65535) : 65535;
                    for (var c = 0; c < 3; c++) {
                        var value = planes[c][i];
                        pixels[p + c] = (short) (a == 0 ? 0 : linear ? Srgb.encode16(value) : Warp.clamp(value * 65535, 65535));
                    }
                    pixels[p + 3] = (short) a;
                }
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + packed);
        }
    }
}
