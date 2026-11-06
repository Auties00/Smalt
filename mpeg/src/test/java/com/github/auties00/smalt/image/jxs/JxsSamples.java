package com.github.auties00.smalt.image.jxs;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * Deterministic synthetic content for the JPEG XS tests: smooth gradients, a sharp-edged disc, a texture and noise,
 * different in every component, so that every band of the wavelet carries energy.
 */
final class JxsSamples {
    private JxsSamples() {
    }

    /**
     * Returns a plane of {@code width x height} samples of {@code precision} bits.
     */
    static short[] plane(int width, int height, int precision, long seed) {
        var random = new SplittableRandom(seed);
        var max = (1 << precision) - 1;
        var out = new short[width * height];
        var cx = width * (0.3 + 0.4 * random.nextDouble());
        var cy = height * (0.3 + 0.4 * random.nextDouble());
        var radius = Math.max(2, Math.min(width, height) / 4.0);
        var fx = 0.05 + random.nextDouble() * 0.3;
        var fy = 0.05 + random.nextDouble() * 0.3;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = 0.15 + 0.5 * x / Math.max(1, width - 1) + 0.2 * y / Math.max(1, height - 1);
                var dx = x - cx;
                var dy = y - cy;
                if (dx * dx + dy * dy < radius * radius) {
                    value = 1.0 - value * 0.7;
                }
                value += 0.08 * StrictMath.sin(x * fx) * StrictMath.cos(y * fy);
                value += (random.nextDouble() - 0.5) * 0.04;
                var sample = (int) Math.round(value * max);
                out[y * width + x] = (short) Math.max(0, Math.min(max, sample));
            }
        }
        return out;
    }

    /**
     * Returns an image of components with the given sampling factors.
     */
    static JpegXsImage image(int width, int height, int precision, int[] sx, int[] sy, long seed) {
        var components = new ArrayList<JpegXsImage.Component>();
        for (var c = 0; c < sx.length; c++) {
            var w = width / sx[c];
            var h = height / sy[c];
            components.add(new JpegXsImage.Component(w, h, precision, sx[c], sy[c], 0, 0,
                    plane(w, h, precision, seed + c)));
        }
        return new JpegXsImage(width, height, components, JpegXsImage.ColorTransform.NONE, Optional.empty(), 0, 0,
                Optional.empty(), Optional.empty());
    }

    /**
     * Returns a Bayer image of a mosaic of the given size.
     */
    static JpegXsImage bayer(int width, int height, int precision, JpegXsImage.CfaPattern pattern, long seed) {
        return JpegXsEncoder.bayer(plane(width, height, precision, seed), width, height, precision, pattern);
    }

    static RgbaPicture rgba(int width, int height, boolean alpha, long seed) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        for (var c = 0; c < 4; c++) {
            var plane = c < 3 || alpha ? plane(width, height, 8, seed + c) : null;
            for (var i = 0; i < width * height; i++) {
                pixels[i * 4 + c] = plane == null ? (byte) 255 : (byte) plane[i];
            }
        }
        return picture;
    }

    static Rgba16Picture rgba16(int width, int height, long seed) {
        var picture = Rgba16Picture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var c = 0; c < 3; c++) {
            var plane = plane(width, height, 16, seed + c);
            for (var i = 0; i < width * height; i++) {
                pixels[i * 4 + c] = plane[i];
            }
        }
        for (var i = 0; i < width * height; i++) {
            pixels[i * 4 + 3] = (short) 0xFFFF;
        }
        return picture;
    }

    static YuvPicture yuv(int width, int height, ChromaFormat format, boolean alpha, long seed) {
        var picture = YuvPicture.allocate(width, height, format, alpha, ColorInfo.BT709_LIMITED);
        fill8(picture.y(), plane(width, height, 8, seed));
        if (format != ChromaFormat.MONOCHROME) {
            var cw = picture.chromaWidth();
            var ch = picture.chromaHeight();
            fill8(picture.u(), plane(cw, ch, 8, seed + 1));
            fill8(picture.v(), plane(cw, ch, 8, seed + 2));
        }
        if (alpha) {
            fill8(picture.alpha(), plane(width, height, 8, seed + 3));
        }
        return picture;
    }

    static Yuv16Picture yuv16(int width, int height, ChromaFormat format, int depth, long seed) {
        var picture = Yuv16Picture.allocate(width, height, format, depth, false, ColorInfo.BT709_LIMITED);
        System.arraycopy(plane(width, height, depth, seed), 0, picture.y(), 0, width * height);
        if (format != ChromaFormat.MONOCHROME) {
            var count = picture.chromaWidth() * picture.chromaHeight();
            System.arraycopy(plane(picture.chromaWidth(), picture.chromaHeight(), depth, seed + 1), 0, picture.u(), 0,
                    count);
            System.arraycopy(plane(picture.chromaWidth(), picture.chromaHeight(), depth, seed + 2), 0, picture.v(), 0,
                    count);
        }
        return picture;
    }

    private static void fill8(byte[] target, short[] source) {
        for (var i = 0; i < source.length; i++) {
            target[i] = (byte) source[i];
        }
    }

    /**
     * Returns the PSNR in dB of decoded components against the originals.
     */
    static double psnr(List<JpegXsImage.Component> expected, List<JpegXsImage.Component> actual) {
        var error = 0.0;
        var count = 0L;
        var peak = 0;
        for (var c = 0; c < expected.size(); c++) {
            var a = expected.get(c).samples();
            var b = actual.get(c).samples();
            peak = Math.max(peak, (1 << expected.get(c).precision()) - 1);
            for (var i = 0; i < a.length; i++) {
                double d = (a[i] & 0xFFFF) - (b[i] & 0xFFFF);
                error += d * d;
                count++;
            }
        }
        return error == 0 ? Double.POSITIVE_INFINITY : 10 * StrictMath.log10((double) peak * peak / (error / count));
    }
}
