package com.github.auties00.smalt.video.dnxhd;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;

/**
 * Holds the Y'CbCr and alpha samples of a picture as integers, with synthetic content and a
 * floating-point reference of the alpha premultiplication of ST 2019-1 Amendment 1:2023 (video
 * scaled by alpha about black for luma and about the middle code for chroma, a subsampled chroma
 * sample by the mean alpha it covers).
 *
 * @param width the picture width
 * @param height the picture height
 * @param format the chroma layout
 * @param depth the bit depth of every plane
 * @param y the luma samples, {@code width} per row
 * @param u the Cb samples, {@link #chromaWidth()} per row
 * @param v the Cr samples
 * @param a the alpha samples, {@code width} per row
 */
record AlphaPlanes(int width, int height, ChromaFormat format, int depth, int[] y, int[] u, int[] v, int[] a) {
    int chromaWidth() {
        return format == ChromaFormat.YUV444 ? width : (width + 1) / 2;
    }

    int chromaHeight() {
        return format == ChromaFormat.YUV420 ? (height + 1) / 2 : height;
    }

    int max() {
        return (1 << depth) - 1;
    }

    int black() {
        return 16 << (depth - 8);
    }

    int middle() {
        return 1 << (depth - 1);
    }

    /** Returns smooth video over an alpha of an opaque disc, a soft edge, a 25% band and a transparent rest. */
    static AlphaPlanes synthetic(int width, int height, ChromaFormat format, int depth) {
        var max = (1 << depth) - 1;
        var scale = 1 << (depth - 8);
        var chromaWidth = format == ChromaFormat.YUV444 ? width : (width + 1) / 2;
        var chromaHeight = format == ChromaFormat.YUV420 ? (height + 1) / 2 : height;
        var y = new int[width * height];
        var a = new int[width * height];
        var radius = Math.min(width, height) * 0.35;
        for (var row = 0; row < height; row++) {
            for (var x = 0; x < width; x++) {
                y[row * width + x] = (int) Math.round(scale * (126 + 90 * Math.sin(x / 11.0) * Math.cos(row / 7.0)));
                var edge = Math.hypot(x - width / 2.0, row - height / 2.0) - radius;
                double alpha;
                if (edge < -6) {
                    alpha = 1;
                } else if (edge < 6) {
                    alpha = (6 - edge) / 12;
                } else {
                    alpha = row < height / 6 ? 0.25 : 0;
                }
                a[row * width + x] = (int) Math.round(alpha * max);
            }
        }
        var u = new int[chromaWidth * chromaHeight];
        var v = new int[chromaWidth * chromaHeight];
        for (var row = 0; row < chromaHeight; row++) {
            for (var x = 0; x < chromaWidth; x++) {
                u[row * chromaWidth + x] = (int) Math.round(scale * (128 + 60 * Math.cos(x / 9.0 + row / 13.0)));
                v[row * chromaWidth + x] = (int) Math.round(scale * (128 - 50 * Math.sin(row / 10.0)));
            }
        }
        return new AlphaPlanes(width, height, format, depth, y, u, v, a);
    }

    /** Returns the samples of a decoded Y'CbCr picture with an alpha plane. */
    static AlphaPlanes of(Picture picture) {
        var width = picture.width();
        var height = picture.height();
        return switch (picture) {
            case Yuv16Picture p -> {
                var planes = blank(width, height, p.chromaFormat(), p.bitDepth());
                copy(p.y(), p.yStride(), planes.y, width, height);
                copy(p.u(), p.uvStride(), planes.u, planes.chromaWidth(), planes.chromaHeight());
                copy(p.v(), p.uvStride(), planes.v, planes.chromaWidth(), planes.chromaHeight());
                copy(p.alpha(), p.alphaStride(), planes.a, width, height);
                yield planes;
            }
            case YuvPicture p -> {
                var planes = blank(width, height, p.chromaFormat(), 8);
                copy(p.y(), p.yStride(), planes.y, width, height);
                copy(p.u(), p.uvStride(), planes.u, planes.chromaWidth(), planes.chromaHeight());
                copy(p.v(), p.uvStride(), planes.v, planes.chromaWidth(), planes.chromaHeight());
                copy(p.alpha(), p.alphaStride(), planes.a, width, height);
                yield planes;
            }
            default -> throw new IllegalArgumentException("not Y'CbCr: " + picture);
        };
    }

    private static AlphaPlanes blank(int width, int height, ChromaFormat format, int depth) {
        var chromaWidth = format == ChromaFormat.YUV444 ? width : (width + 1) / 2;
        var chromaHeight = format == ChromaFormat.YUV420 ? (height + 1) / 2 : height;
        return new AlphaPlanes(width, height, format, depth, new int[width * height],
                new int[chromaWidth * chromaHeight], new int[chromaWidth * chromaHeight], new int[width * height]);
    }

    private static void copy(short[] from, int stride, int[] to, int width, int height) {
        for (var row = 0; row < height; row++) {
            for (var x = 0; x < width; x++) {
                to[row * width + x] = from[row * stride + x] & 0xFFFF;
            }
        }
    }

    private static void copy(byte[] from, int stride, int[] to, int width, int height) {
        for (var row = 0; row < height; row++) {
            for (var x = 0; x < width; x++) {
                to[row * width + x] = from[row * stride + x] & 0xFF;
            }
        }
    }

    /** Returns the samples as a picture, 8-bit for 8-bit samples, limited-range BT.709. */
    Picture toPicture() {
        if (depth == 8) {
            var picture = YuvPicture.allocate(width, height, format, true, ColorInfo.BT709_LIMITED);
            fill(picture.y(), picture.yStride(), y, width, height);
            fill(picture.u(), picture.uvStride(), u, chromaWidth(), chromaHeight());
            fill(picture.v(), picture.uvStride(), v, chromaWidth(), chromaHeight());
            fill(picture.alpha(), picture.alphaStride(), a, width, height);
            return picture;
        }
        var picture = Yuv16Picture.allocate(width, height, format, depth, true, ColorInfo.BT709_LIMITED);
        fill(picture.y(), picture.yStride(), y, width, height);
        fill(picture.u(), picture.uvStride(), u, chromaWidth(), chromaHeight());
        fill(picture.v(), picture.uvStride(), v, chromaWidth(), chromaHeight());
        fill(picture.alpha(), picture.alphaStride(), a, width, height);
        return picture;
    }

    private static void fill(byte[] to, int stride, int[] from, int width, int height) {
        for (var row = 0; row < height; row++) {
            for (var x = 0; x < width; x++) {
                to[row * stride + x] = (byte) from[row * width + x];
            }
        }
    }

    private static void fill(short[] to, int stride, int[] from, int width, int height) {
        for (var row = 0; row < height; row++) {
            for (var x = 0; x < width; x++) {
                to[row * stride + x] = (short) from[row * width + x];
            }
        }
    }

    /** Returns the mean alpha a chroma sample covers. */
    double chromaAlpha(int x, int row) {
        var shiftX = format == ChromaFormat.YUV444 ? 0 : 1;
        var shiftY = format == ChromaFormat.YUV420 ? 1 : 0;
        var sum = 0.0;
        var count = 0;
        for (var dy = 0; dy < 1 << shiftY; dy++) {
            for (var dx = 0; dx < 1 << shiftX; dx++) {
                var ax = Math.min(width - 1, (x << shiftX) + dx);
                var ay = Math.min(height - 1, (row << shiftY) + dy);
                sum += a[ay * width + ax];
                count++;
            }
        }
        return sum / count;
    }

    /** Rounds half away from zero. */
    static long round(double value) {
        return (long) Math.signum(value) * (long) Math.floor(Math.abs(value) + 0.5);
    }

    /** Returns the reference premultiplication of these samples by their alpha. */
    AlphaPlanes premultiplied() {
        var result = blank(width, height, format, depth);
        System.arraycopy(a, 0, result.a, 0, a.length);
        for (var i = 0; i < y.length; i++) {
            result.y[i] = (int) (black() + round((y[i] - black()) * (double) a[i] / max()));
        }
        for (var row = 0; row < chromaHeight(); row++) {
            for (var x = 0; x < chromaWidth(); x++) {
                var i = row * chromaWidth() + x;
                var alpha = chromaAlpha(x, row);
                result.u[i] = (int) (middle() + round((u[i] - middle()) * alpha / max()));
                result.v[i] = (int) (middle() + round((v[i] - middle()) * alpha / max()));
            }
        }
        return result;
    }

    /** Returns the reference division of these samples by their alpha, clipped, the base where alpha is 0. */
    AlphaPlanes straightened() {
        var result = blank(width, height, format, depth);
        System.arraycopy(a, 0, result.a, 0, a.length);
        for (var i = 0; i < y.length; i++) {
            result.y[i] = straighten(y[i], a[i], black());
        }
        for (var row = 0; row < chromaHeight(); row++) {
            for (var x = 0; x < chromaWidth(); x++) {
                var i = row * chromaWidth() + x;
                var alpha = chromaAlpha(x, row);
                result.u[i] = straighten(u[i], alpha, middle());
                result.v[i] = straighten(v[i], alpha, middle());
            }
        }
        return result;
    }

    private int straighten(int value, double alpha, int base) {
        if (alpha == 0) {
            return base;
        }
        return (int) Math.clamp(base + round((value - base) * max() / alpha), 0, max());
    }

    /** Asserts that two sets of samples are equal plane by plane. */
    static void assertSamplesEqual(AlphaPlanes expected, AlphaPlanes actual, String label) {
        org.junit.jupiter.api.Assertions.assertEquals(expected.depth, actual.depth, label + ": depth");
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected.a, actual.a, label + ": alpha");
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected.y, actual.y, label + ": luma");
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected.u, actual.u, label + ": Cb");
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected.v, actual.v, label + ": Cr");
    }

    /** Returns the PSNR of luma, Cb and Cr together against other samples, over samples whose alpha is at least a fraction. */
    double psnr(AlphaPlanes other, double minimumAlpha) {
        var sum = 0.0;
        var count = 0L;
        for (var i = 0; i < y.length; i++) {
            if (a[i] >= minimumAlpha * max()) {
                var d = y[i] - other.y[i];
                sum += (double) d * d;
                count++;
            }
        }
        for (var row = 0; row < chromaHeight(); row++) {
            for (var x = 0; x < chromaWidth(); x++) {
                if (chromaAlpha(x, row) >= minimumAlpha * max()) {
                    var i = row * chromaWidth() + x;
                    var du = u[i] - other.u[i];
                    var dv = v[i] - other.v[i];
                    sum += (double) du * du + (double) dv * dv;
                    count += 2;
                }
            }
        }
        return sum == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10((double) max() * max() * count / sum);
    }
}
