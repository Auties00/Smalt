package com.github.auties00.smalt.image.avif.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.color.LumaWeights;
import com.github.auties00.smalt.internal.color.YcbcrModel;
import com.github.auties00.smalt.internal.color.YcgcoR;

import java.util.Optional;

/**
 * Holds a picture prepared for AV1 coding: planar Y'CbCr samples at the coded bit depth and
 * chroma format, the color description the file signals, and the alpha plane, if any.
 *
 * <p>RGB pictures are converted with the matrix and range of the requested color description
 * at full precision ({@code ITU-T H.273} equations, chroma averaged over the pixels a subsampled
 * sample covers, a single rounding); the YCgCo-Re and YCgCo-Ro matrices quantize R'G'B' to two or one
 * bits fewer than the coded depth and apply their integer transform. Premultiplied storage
 * multiplies the color by alpha first.
 * Planar pictures keep their samples: their chroma is resampled when another format is requested
 * and their depth rescaled when another depth is.
 *
 * @param width the picture width, in pixels
 * @param height the picture height, in pixels
 * @param format the chroma format
 * @param bitDepth the bit depth: 8, 10 or 12
 * @param color the color description signaled for the samples
 * @param planes the Y, U and V planes, with a stride of their width; U and V are {@code null}
 * for monochrome
 * @param alpha the alpha plane at the same depth with a stride of the width, or {@code null}
 * when the picture is opaque
 */
public record EncodeImage(int width, int height, ChromaFormat format, int bitDepth, ColorInfo color,
                          short[][] planes, short[] alpha) {
    /**
     * Prepares a picture.
     *
     * @param picture the picture to prepare, RGBA or planar Y'CbCr at any depth
     * @param format the requested chroma format, or {@code null} for automatic
     * @param bitDepth the requested bit depth, or 0 for automatic
     * @param rgbColor the color description RGB pictures are converted with
     * @param lossless whether the color is coded losslessly
     * @param premultiplied whether color is stored premultiplied by alpha
     * @return the prepared image
     * @throws MediaException.Unsupported if the color description cannot be applied
     */
    public static EncodeImage of(Picture picture, ChromaFormat format, int bitDepth, ColorInfo rgbColor,
                                 boolean lossless, boolean premultiplied) {
        var reversible = YcgcoR.applies(rgbColor.matrix());
        return switch (picture) {
            case RgbaPicture rgba -> fromRgba(rgba.width(), rgba.height(), rgba.pixels(), null, rgba.stride(),
                    rgba.hasAlpha(), format, bitDepth != 0 ? bitDepth : reversible ? 10 : 8, rgbColor, lossless,
                    premultiplied);
            case Rgba16Picture rgba -> fromRgba(rgba.width(), rgba.height(), null, rgba.pixels(), rgba.stride(),
                    rgba.hasAlpha(), format, bitDepth != 0 ? bitDepth : reversible ? 12 : 10, rgbColor, lossless,
                    premultiplied);
            case YuvPicture yuv -> fromYuv(yuv, format, bitDepth == 0 ? 8 : bitDepth);
            case Yuv16Picture yuv -> fromYuv16(yuv, format,
                    bitDepth != 0 ? bitDepth : yuv.bitDepth() <= 8 ? 8 : yuv.bitDepth() <= 10 ? 10 : 12);
            case ChannelPicture channels -> of(channels.bitDepth() > 8 ? ImageOps.toRgba16(channels)
                    : ImageOps.toRgba(channels), format, bitDepth, rgbColor, lossless, premultiplied);
        };
    }

    /**
     * Returns the chroma planes' width.
     *
     * @return the width, 0 for monochrome
     */
    public int chromaWidth() {
        return format.chromaWidth(width);
    }

    /**
     * Returns the chroma planes' height.
     *
     * @return the height, 0 for monochrome
     */
    public int chromaHeight() {
        return format.chromaHeight(height);
    }

    /**
     * Returns whether the alpha plane is opaque everywhere.
     *
     * @param alpha the alpha plane
     * @param max the largest sample value
     * @return {@code true} when every sample is the largest value
     */
    private static boolean opaque(short[] alpha, int max) {
        for (var v : alpha) {
            if (v != max) {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts an RGBA picture.
     *
     * @param width the picture width, in pixels
     * @param height the picture height, in pixels
     * @param rgba8 the 8-bit samples, or {@code null}
     * @param rgba16 the 16-bit samples, or {@code null}
     * @param stride the distance between rows, in samples
     * @param hasAlpha whether the picture carries alpha
     * @param format the requested chroma format, or {@code null}
     * @param depth the coded bit depth
     * @param color the color description to convert with
     * @param lossless whether the color is coded losslessly
     * @param premultiplied whether color is premultiplied
     * @return the image
     */
    private static EncodeImage fromRgba(int width, int height, byte[] rgba8, short[] rgba16, int stride,
                                        boolean hasAlpha, ChromaFormat format, int depth, ColorInfo color,
                                        boolean lossless, boolean premultiplied) {
        if (format == null) {
            format = lossless ? ChromaFormat.YUV444 : ChromaFormat.YUV420;
        }
        if (lossless && format == ChromaFormat.YUV444) {
            color = YcgcoR.applies(color.matrix()) ? color.withRange(ColorRange.FULL)
                    : new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, color.primaries(), color.transfer());
        }
        var matrix = color.matrix();
        if (matrix == MatrixCoefficients.IDENTITY && format != ChromaFormat.YUV444) {
            throw new MediaException.Unsupported("avif", "the identity matrix needs 4:4:4");
        }
        if (YcgcoR.applies(matrix)) {
            return reversible(width, height, rgba8, rgba16, stride, hasAlpha, format, depth, color, premultiplied);
        }
        var k = matrix == MatrixCoefficients.IDENTITY || matrix == MatrixCoefficients.YCGCO ? null
                : weights(color).orElse(null);
        var model = k == null && matrix != MatrixCoefficients.IDENTITY && matrix != MatrixCoefficients.YCGCO
                ? YcbcrModel.of(color) : null;
        var max = (1 << depth) - 1;
        var premultiply = hasAlpha && premultiplied;
        var e = new double[3];
        var y = new short[width * height];
        short[] alpha = hasAlpha ? new short[width * height] : null;
        for (var py = 0; py < height; py++) {
            for (var px = 0; px < width; px++) {
                var a = sample(rgba8, rgba16, stride, px, py, 3);
                if (alpha != null) {
                    alpha[py * width + px] = (short) Math.round(a * max);
                }
                components(rgba8, rgba16, stride, px, py, premultiply ? a : 1, matrix, k, model, e);
                y[py * width + px] = (short) quantize(e[0], 0, color.range(), matrix, model, depth);
            }
        }
        if (alpha != null && opaque(alpha, max)) {
            alpha = null;
        }
        if (format == ChromaFormat.MONOCHROME) {
            return new EncodeImage(width, height, format, depth, color, new short[][]{y, null, null}, alpha);
        }
        var sx = format.shiftX();
        var sy = format.shiftY();
        var cw = format.chromaWidth(width);
        var ch = format.chromaHeight(height);
        var u = new short[cw * ch];
        var v = new short[cw * ch];
        for (var j = 0; j < ch; j++) {
            for (var i = 0; i < cw; i++) {
                double su = 0;
                double sv = 0;
                var count = 0;
                for (var py = j << sy; py < Math.min(height, (j + 1) << sy); py++) {
                    for (var px = i << sx; px < Math.min(width, (i + 1) << sx); px++) {
                        var a = premultiply ? sample(rgba8, rgba16, stride, px, py, 3) : 1;
                        components(rgba8, rgba16, stride, px, py, a, matrix, k, model, e);
                        su += e[1];
                        sv += e[2];
                        count++;
                    }
                }
                u[j * cw + i] = (short) quantize(su / count, 1, color.range(), matrix, model, depth);
                v[j * cw + i] = (short) quantize(sv / count, 2, color.range(), matrix, model, depth);
            }
        }
        return new EncodeImage(width, height, format, depth, color, new short[][]{y, u, v}, alpha);
    }

    /**
     * Converts an RGBA picture with the integer YCgCo-Re or YCgCo-Ro transform.
     *
     * @param width the picture width, in pixels
     * @param height the picture height, in pixels
     * @param rgba8 the 8-bit samples, or {@code null}
     * @param rgba16 the 16-bit samples, or {@code null}
     * @param stride the distance between rows, in samples
     * @param hasAlpha whether the picture carries alpha
     * @param format the chroma format
     * @param depth the coded bit depth
     * @param color the color description, whose matrix is YCgCo-Re or YCgCo-Ro
     * @param premultiplied whether color is premultiplied
     * @return the image
     */
    private static EncodeImage reversible(int width, int height, byte[] rgba8, short[] rgba16, int stride,
                                          boolean hasAlpha, ChromaFormat format, int depth, ColorInfo color,
                                          boolean premultiplied) {
        var rgbDepth = YcgcoR.rgbDepth(color.matrix(), depth);
        var range = color.range();
        var max = (1 << depth) - 1;
        var premultiply = hasAlpha && premultiplied;
        var y = new short[width * height];
        var cg = new int[width * height];
        var co = new int[width * height];
        short[] alpha = hasAlpha ? new short[width * height] : null;
        var codes = new int[3];
        for (var py = 0; py < height; py++) {
            for (var px = 0; px < width; px++) {
                var a = sample(rgba8, rgba16, stride, px, py, 3);
                if (alpha != null) {
                    alpha[py * width + px] = (short) Math.round(a * max);
                }
                var scale = premultiply ? a : 1;
                YcgcoR.forward(YcgcoR.toSample(sample(rgba8, rgba16, stride, px, py, 0) * scale, range, rgbDepth),
                        YcgcoR.toSample(sample(rgba8, rgba16, stride, px, py, 1) * scale, range, rgbDepth),
                        YcgcoR.toSample(sample(rgba8, rgba16, stride, px, py, 2) * scale, range, rgbDepth), depth, codes);
                y[py * width + px] = (short) codes[0];
                cg[py * width + px] = codes[1];
                co[py * width + px] = codes[2];
            }
        }
        if (alpha != null && opaque(alpha, max)) {
            alpha = null;
        }
        if (format == ChromaFormat.MONOCHROME) {
            return new EncodeImage(width, height, format, depth, color, new short[][]{y, null, null}, alpha);
        }
        var sx = format.shiftX();
        var sy = format.shiftY();
        var cw = format.chromaWidth(width);
        var ch = format.chromaHeight(height);
        var u = new short[cw * ch];
        var v = new short[cw * ch];
        for (var j = 0; j < ch; j++) {
            for (var i = 0; i < cw; i++) {
                long su = 0;
                long sv = 0;
                var count = 0;
                for (var py = j << sy; py < Math.min(height, (j + 1) << sy); py++) {
                    for (var px = i << sx; px < Math.min(width, (i + 1) << sx); px++) {
                        su += cg[py * width + px];
                        sv += co[py * width + px];
                        count++;
                    }
                }
                u[j * cw + i] = (short) Math.round((double) su / count);
                v[j * cw + i] = (short) Math.round((double) sv / count);
            }
        }
        return new EncodeImage(width, height, format, depth, color, new short[][]{y, u, v}, alpha);
    }

    /**
     * Returns one normalized channel of an RGBA pixel.
     *
     * @param rgba8 the 8-bit samples, or {@code null}
     * @param rgba16 the 16-bit samples, or {@code null}
     * @param stride the distance between rows, in samples
     * @param x the column
     * @param y the row
     * @param channel 0 red, 1 green, 2 blue, 3 alpha
     * @return the value, 0 to 1
     */
    private static double sample(byte[] rgba8, short[] rgba16, int stride, int x, int y, int channel) {
        var i = y * stride + x * 4 + channel;
        return rgba8 != null ? (rgba8[i] & 0xFF) / 255.0 : (rgba16[i] & 0xFFFF) / 65535.0;
    }

    /**
     * Computes the normalized luma and color differences of an RGBA pixel (ITU-T H.273
     * equations 38 to 40, and the identity and YCgCo forms).
     *
     * @param rgba8 the 8-bit samples, or {@code null}
     * @param rgba16 the 16-bit samples, or {@code null}
     * @param stride the distance between rows, in samples
     * @param x the column
     * @param y the row
     * @param scale the factor applied to the color, the alpha for premultiplied storage
     * @param matrix the matrix coefficients of the target color description
     * @param k the luma weights, unused for the identity and YCgCo matrices and with a model
     * @param model the model of a matrix that luma weights do not define, or {@code null}
     * @param out receives luma, then the blue and red differences (or B and R for identity)
     */
    private static void components(byte[] rgba8, short[] rgba16, int stride, int x, int y, double scale,
                                   MatrixCoefficients matrix, LumaWeights k, YcbcrModel model, double[] out) {
        var r = sample(rgba8, rgba16, stride, x, y, 0) * scale;
        var g = sample(rgba8, rgba16, stride, x, y, 1) * scale;
        var b = sample(rgba8, rgba16, stride, x, y, 2) * scale;
        if (model != null) {
            model.toYcbcr(r, g, b, out);
            return;
        }
        switch (matrix) {
            case IDENTITY -> {
                out[0] = g;
                out[1] = b;
                out[2] = r;
            }
            case YCGCO -> {
                out[0] = 0.25 * r + 0.5 * g + 0.25 * b;
                out[1] = -0.25 * r + 0.5 * g - 0.25 * b;
                out[2] = 0.5 * r - 0.5 * b;
            }
            default -> {
                var kr = k.kr();
                var kb = k.kb();
                var ey = kr * r + (1 - kr - kb) * g + kb * b;
                out[0] = ey;
                out[1] = (b - ey) / (2 * (1 - kb));
                out[2] = (r - ey) / (2 * (1 - kr));
            }
        }
    }

    /**
     * Returns the luma weights RGB samples are converted with directly.
     *
     * @param color the target color description
     * @return the matrix's weights, those of BT.601 for an unspecified matrix and those of the primaries for the
     * chromaticity-derived non-constant-luminance matrix, or empty for a matrix weighted luma does not define
     */
    private static Optional<LumaWeights> weights(ColorInfo color) {
        var matrix = color.matrix().effective();
        return matrix == MatrixCoefficients.CHROMA_DERIVED_NCL ? LumaWeights.of(matrix, color.primaries())
                : LumaWeights.of(matrix);
    }

    /**
     * Quantizes a normalized component value to a sample (ITU-T H.273 equations 27 to 38).
     *
     * @param e the value: luma in 0 to 1, a color difference in -0.5 to 0.5
     * @param component 0 for luma, 1 or 2 for a color difference
     * @param range the quantization range
     * @param matrix the matrix coefficients; {@link MatrixCoefficients#IDENTITY} codes color differences
     * without the mid-range offset and YCgCo scales them like luma in limited range
     * @param model the model of a matrix that luma weights do not define, which quantizes the value, or {@code null}
     * @param depth the bit depth
     * @return the sample, clamped
     */
    private static int quantize(double e, int component, ColorRange range, MatrixCoefficients matrix,
                                YcbcrModel model, int depth) {
        var max = (1 << depth) - 1;
        if (model != null) {
            var code = e * model.scale(component, range, depth) + model.offset(component, range, depth);
            return (int) Math.clamp(Math.round(code), 0, max);
        }
        var chroma = component != 0;
        var offset = chroma && matrix != MatrixCoefficients.IDENTITY;
        double v;
        if (range == ColorRange.FULL) {
            v = max * e + (offset ? 1 << (depth - 1) : 0);
        } else {
            var scale = 1 << (depth - 8);
            if (offset && matrix == MatrixCoefficients.YCGCO) {
                v = 219 * e * scale + (1 << (depth - 1));
            } else if (offset) {
                v = (224 * e + 128) * scale;
            } else {
                v = (219 * e + 16) * scale;
            }
        }
        return (int) Math.clamp(Math.round(v), 0, max);
    }

    /**
     * Prepares an 8-bit planar picture.
     *
     * @param yuv the picture
     * @param format the requested chroma format, or {@code null}
     * @param depth the coded bit depth
     * @return the image
     */
    private static EncodeImage fromYuv(YuvPicture yuv, ChromaFormat format, int depth) {
        if (format != null && format != yuv.chromaFormat()) {
            yuv = ImageOps.toYuv(yuv, format, yuv.color());
        }
        var w = yuv.width();
        var h = yuv.height();
        var f = yuv.chromaFormat();
        var shift = depth - 8;
        var planes = new short[3][];
        planes[0] = widen(yuv.y(), yuv.yStride(), w, h, shift);
        if (f.hasChroma()) {
            planes[1] = widen(yuv.u(), yuv.uvStride(), f.chromaWidth(w), f.chromaHeight(h), shift);
            planes[2] = widen(yuv.v(), yuv.uvStride(), f.chromaWidth(w), f.chromaHeight(h), shift);
        }
        short[] alpha = null;
        if (yuv.hasAlpha() && yuv.alpha() != null) {
            var max = (1 << depth) - 1;
            alpha = new short[w * h];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    alpha[y * w + x] = (short) (((yuv.alpha()[y * yuv.alphaStride() + x] & 0xFF) * max + 127) / 255);
                }
            }
            if (opaque(alpha, max)) {
                alpha = null;
            }
        }
        return new EncodeImage(w, h, f, depth, yuv.color(), planes, alpha);
    }

    /**
     * Copies an 8-bit plane into samples shifted to a deeper depth.
     *
     * @param src the plane
     * @param stride the plane stride
     * @param w the width
     * @param h the height
     * @param shift the left shift
     * @return the samples, with a stride of the width
     */
    private static short[] widen(byte[] src, int stride, int w, int h, int shift) {
        var out = new short[w * h];
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                out[y * w + x] = (short) ((src[y * stride + x] & 0xFF) << shift);
            }
        }
        return out;
    }

    /**
     * Prepares a deep planar picture.
     *
     * @param yuv the picture
     * @param format the requested chroma format, or {@code null}
     * @param depth the coded bit depth
     * @return the image
     */
    private static EncodeImage fromYuv16(Yuv16Picture yuv, ChromaFormat format, int depth) {
        if (yuv.bitDepth() != depth) {
            yuv = ImageOps.toBitDepth(yuv, depth);
        }
        var w = yuv.width();
        var h = yuv.height();
        var source = yuv.chromaFormat();
        var target = format == null ? source : format;
        var planes = new short[3][];
        planes[0] = copy(yuv.y(), yuv.yStride(), w, h);
        if (target.hasChroma()) {
            if (!source.hasChroma()) {
                var mid = (short) (1 << (depth - 1));
                var cw = target.chromaWidth(w);
                var ch = target.chromaHeight(h);
                planes[1] = new short[cw * ch];
                planes[2] = new short[cw * ch];
                java.util.Arrays.fill(planes[1], mid);
                java.util.Arrays.fill(planes[2], mid);
            } else {
                planes[1] = resample(yuv.u(), yuv.uvStride(), w, h, source, target);
                planes[2] = resample(yuv.v(), yuv.uvStride(), w, h, source, target);
            }
        }
        short[] alpha = null;
        if (yuv.hasAlpha() && yuv.alpha() != null) {
            alpha = copy(yuv.alpha(), yuv.alphaStride(), w, h);
            if (opaque(alpha, (1 << depth) - 1)) {
                alpha = null;
            }
        }
        return new EncodeImage(w, h, target, depth, yuv.color(), planes, alpha);
    }

    /**
     * Copies a plane.
     *
     * @param src the plane
     * @param stride the plane stride
     * @param w the width
     * @param h the height
     * @return the samples, with a stride of the width
     */
    private static short[] copy(short[] src, int stride, int w, int h) {
        var out = new short[w * h];
        for (var y = 0; y < h; y++) {
            System.arraycopy(src, y * stride, out, y * w, w);
        }
        return out;
    }

    /**
     * Resamples a deep chroma plane to another chroma format: averages the samples a reduced
     * sample covers and repeats the samples an enlarged one comes from.
     *
     * @param src the plane
     * @param stride the plane stride
     * @param w the luma width
     * @param h the luma height
     * @param from the plane's format
     * @param to the target format
     * @return the samples, with a stride of the target chroma width
     */
    private static short[] resample(short[] src, int stride, int w, int h, ChromaFormat from, ChromaFormat to) {
        var sw = from.chromaWidth(w);
        var sh = from.chromaHeight(h);
        var tw = to.chromaWidth(w);
        var th = to.chromaHeight(h);
        var out = new short[tw * th];
        for (var y = 0; y < th; y++) {
            var ly0 = y << to.shiftY();
            var ly1 = Math.min(h, (y + 1) << to.shiftY());
            for (var x = 0; x < tw; x++) {
                var lx0 = x << to.shiftX();
                var lx1 = Math.min(w, (x + 1) << to.shiftX());
                long sum = 0;
                var count = 0;
                for (var ly = ly0; ly < ly1; ly++) {
                    var syy = Math.min(sh - 1, ly >> from.shiftY());
                    for (var lx = lx0; lx < lx1; lx++) {
                        var sxx = Math.min(sw - 1, lx >> from.shiftX());
                        sum += src[syy * stride + sxx];
                        count++;
                    }
                }
                out[y * tw + x] = (short) ((sum + count / 2) / count);
            }
        }
        return out;
    }

    /**
     * Returns the region of this image covering one tile of a grid, edges replicated where the
     * tile extends past the image.
     *
     * @param x the tile's left column
     * @param y the tile's top row
     * @param size the tile side
     * @return the tile image
     */
    public EncodeImage tile(int x, int y, int size) {
        var planes = new short[3][];
        planes[0] = crop(this.planes[0], width, height, x, y, size, size);
        if (format.hasChroma()) {
            var cx = x >> format.shiftX();
            var cy = y >> format.shiftY();
            var cs = format.chromaWidth(size);
            var csh = format.chromaHeight(size);
            planes[1] = crop(this.planes[1], chromaWidth(), chromaHeight(), cx, cy, cs, csh);
            planes[2] = crop(this.planes[2], chromaWidth(), chromaHeight(), cx, cy, cs, csh);
        }
        var a = alpha == null ? null : crop(alpha, width, height, x, y, size, size);
        return new EncodeImage(size, size, format, bitDepth, color, planes, a);
    }

    /**
     * Copies a region of a plane, replicating the last column and row beyond the plane.
     *
     * @param src the plane, with a stride of its width
     * @param pw the plane width
     * @param ph the plane height
     * @param x the region's left column
     * @param y the region's top row
     * @param w the region width
     * @param h the region height
     * @return the region, with a stride of its width
     */
    private static short[] crop(short[] src, int pw, int ph, int x, int y, int w, int h) {
        var out = new short[w * h];
        for (var r = 0; r < h; r++) {
            var sy = Math.min(ph - 1, y + r);
            for (var c = 0; c < w; c++) {
                out[r * w + c] = src[sy * pw + Math.min(pw - 1, x + c)];
            }
        }
        return out;
    }

    /**
     * Returns the alpha plane as a monochrome image.
     *
     * @return the alpha image, full range
     * @throws IllegalStateException if the image has no alpha
     */
    public EncodeImage alphaImage() {
        if (alpha == null) {
            throw new IllegalStateException("no alpha");
        }
        var alphaColor = new ColorInfo(MatrixCoefficients.UNSPECIFIED, ColorRange.FULL,
                com.github.auties00.smalt.image.ColorPrimaries.UNSPECIFIED,
                com.github.auties00.smalt.image.TransferCharacteristics.UNSPECIFIED);
        return new EncodeImage(width, height, ChromaFormat.MONOCHROME, bitDepth, alphaColor,
                new short[][]{alpha, null, null}, null);
    }
}
