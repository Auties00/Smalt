package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Builds test pictures, copies them into tight arrays, and runs ffmpeg on raw frames.
 */
final class Pictures {
    private Pictures() {
        throw new AssertionError();
    }

    /** Returns an RGBA picture of random samples with a padded stride. */
    static RgbaPicture randomRgba(SplittableRandom random, int width, int height, boolean alpha) {
        var stride = 4 * width + 4 * random.nextInt(3);
        var pixels = new byte[stride * height];
        random.nextBytes(pixels);
        if (!alpha) {
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    pixels[y * stride + 4 * x + 3] = (byte) 255;
                }
            }
        }
        return RgbaPicture.of(width, height, stride, pixels, alpha);
    }

    /** Returns a smooth RGBA picture: gradients plus a few hard edges, deterministic. */
    static RgbaPicture patternRgba(int width, int height, boolean alpha) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = 4 * (y * width + x);
                pixels[i] = (byte) (x * 255 / Math.max(1, width - 1));
                pixels[i + 1] = (byte) (y * 255 / Math.max(1, height - 1));
                pixels[i + 2] = (byte) (((x / 7 + y / 5) & 1) == 0 ? 40 : 220);
                pixels[i + 3] = alpha ? (byte) ((x + y) * 255 / Math.max(1, width + height - 2)) : (byte) 255;
            }
        }
        return picture;
    }

    /** Returns a smooth RGBA picture without hard edges: sinusoidal channels and an alpha ramp. */
    static RgbaPicture smoothRgba(int width, int height, boolean alpha) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = 4 * (y * width + x);
                pixels[i] = (byte) (128 + 100 * Math.sin(x / 13.0));
                pixels[i + 1] = (byte) (128 + 100 * Math.cos(y / 9.0));
                pixels[i + 2] = (byte) (128 + 80 * Math.sin((x + y) / 17.0));
                pixels[i + 3] = alpha ? (byte) ((x + y) * 255 / Math.max(1, width + height - 2)) : (byte) 255;
            }
        }
        return picture;
    }

    /** Returns a planar picture of random samples with padded strides. */
    static YuvPicture randomYuv(SplittableRandom random, int width, int height, ChromaFormat format, boolean alpha, ColorInfo color) {
        var yStride = width + random.nextInt(3);
        var y = new byte[yStride * height];
        random.nextBytes(y);
        byte[] u = null;
        byte[] v = null;
        var uvStride = 0;
        if (format.hasChroma()) {
            uvStride = format.chromaWidth(width) + random.nextInt(3);
            u = new byte[uvStride * format.chromaHeight(height)];
            v = new byte[uvStride * format.chromaHeight(height)];
            random.nextBytes(u);
            random.nextBytes(v);
        }
        byte[] a = null;
        var aStride = 0;
        if (alpha) {
            aStride = width + random.nextInt(3);
            a = new byte[aStride * height];
            random.nextBytes(a);
        }
        return YuvPicture.builder().size(width, height).chromaFormat(format).color(color).luma(y, yStride)
                .chroma(u, v, uvStride)
                .alpha(a, aStride)
                .build();
    }

    /** Copies the visible pixels of an RGBA picture into a tight array. */
    static byte[] tight(RgbaPicture picture) {
        var out = new byte[4 * picture.width() * picture.height()];
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.pixels(), y * picture.stride(), out, 4 * y * picture.width(), 4 * picture.width());
        }
        return out;
    }

    /** Copies one plane into a tight array. */
    static byte[] tight(byte[] plane, int stride, int width, int height) {
        var out = new byte[width * height];
        for (var y = 0; y < height; y++) {
            System.arraycopy(plane, y * stride, out, y * width, width);
        }
        return out;
    }

    /** Concatenates the planes of a planar picture as ffmpeg's planar raw formats lay them out. */
    static byte[] raw(YuvPicture picture) {
        var parts = new ArrayList<byte[]>();
        parts.add(tight(picture.y(), picture.yStride(), picture.width(), picture.height()));
        if (picture.chromaFormat().hasChroma()) {
            parts.add(tight(picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight()));
            parts.add(tight(picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight()));
        }
        if (picture.hasAlpha()) {
            parts.add(tight(picture.alpha(), picture.alphaStride(), picture.width(), picture.height()));
        }
        var size = parts.stream().mapToInt(part -> part.length).sum();
        var out = new byte[size];
        var offset = 0;
        for (var part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    /** Wraps ffmpeg planar raw output as a tight planar picture. */
    static YuvPicture fromRaw(byte[] raw, int width, int height, ChromaFormat format, boolean alpha, ColorInfo color) {
        var picture = YuvPicture.allocate(width, height, format, alpha, color);
        var offset = 0;
        System.arraycopy(raw, offset, picture.y(), 0, width * height);
        offset += width * height;
        if (format.hasChroma()) {
            var chroma = picture.chromaWidth() * picture.chromaHeight();
            System.arraycopy(raw, offset, picture.u(), 0, chroma);
            offset += chroma;
            System.arraycopy(raw, offset, picture.v(), 0, chroma);
            offset += chroma;
        }
        if (alpha) {
            System.arraycopy(raw, offset, picture.alpha(), 0, width * height);
        }
        return picture;
    }

    /** Returns ffmpeg's name of a planar layout. */
    static String pixelFormat(ChromaFormat format, boolean alpha) {
        return switch (format) {
            case MONOCHROME -> "gray";
            case YUV420 -> alpha ? "yuva420p" : "yuv420p";
            case YUV422 -> alpha ? "yuva422p" : "yuv422p";
            case YUV444 -> alpha ? "yuva444p" : "yuv444p";
        };
    }

    /** Runs one raw frame through an ffmpeg filter graph and returns the raw output frame. */
    static byte[] ffmpeg(byte[] input, String inputFormat, int width, int height, String filter, String outputFormat) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var arguments = new ArrayList<>(List.of("-v", "error", "-f", "rawvideo", "-pix_fmt", inputFormat,
                "-s", width + "x" + height, "-i", "-", "-vf", filter, "-frames:v", "1",
                "-f", "rawvideo", "-pix_fmt", outputFormat, "-"));
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), input, arguments);
    }

    /** Renders ffmpeg's testsrc2 pattern as one raw frame in the given pixel format. */
    static byte[] testsrc(int width, int height, String pixelFormat) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        // testsrc2 renders even sizes only, so odd sizes are cropped from the next even size
        var evenWidth = (width + 1) & ~1;
        var evenHeight = (height + 1) & ~1;
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-f", "lavfi", "-i",
                "testsrc2=size=" + evenWidth + "x" + evenHeight + ":rate=1", "-vf", "format=rgba,crop=" + width + ":" + height + ":0:0",
                "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", pixelFormat, "-"));
    }
}
