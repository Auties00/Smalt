package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.internal.JpegOutput;
import com.github.auties00.smalt.image.jpeg.internal.Upsampler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared helpers of the JPEG tests: PPM/PGM parsing and writing, tight plane extraction, and the
 * libjpeg-equivalent conversion of a decoded {@link YuvPicture} to RGB (fancy upsampling plus the
 * jdcolor.c tables), which lets decoder output be compared with {@code djpeg} reference files.
 */
final class JpegTestSupport {
    private JpegTestSupport() {
        throw new AssertionError();
    }

    record Pnm(int width, int height, int channels, byte[] pixels) {
    }

    static Pnm parsePnm(byte[] data) {
        var tokens = new String[4];
        var index = 0;
        var position = 0;
        while (index < 4) {
            while (Character.isWhitespace(data[position])) {
                position++;
            }
            if (data[position] == '#') {
                while (data[position] != '\n') {
                    position++;
                }
                continue;
            }
            var start = position;
            while (!Character.isWhitespace(data[position])) {
                position++;
            }
            tokens[index++] = new String(data, start, position - start, StandardCharsets.US_ASCII);
        }
        position++;
        var channels = switch (tokens[0]) {
            case "P5" -> 1;
            case "P6" -> 3;
            default -> throw new IllegalArgumentException("unsupported PNM " + tokens[0]);
        };
        var width = Integer.parseInt(tokens[1]);
        var height = Integer.parseInt(tokens[2]);
        var pixels = new byte[width * height * channels];
        System.arraycopy(data, position, pixels, 0, pixels.length);
        return new Pnm(width, height, channels, pixels);
    }

    static byte[] writePnm(int width, int height, int channels, byte[] pixels) {
        var out = new ByteArrayOutputStream();
        var header = (channels == 1 ? "P5" : "P6") + "\n" + width + " " + height + "\n255\n";
        out.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(pixels);
        return out.toByteArray();
    }

    static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static byte[] plane(byte[] samples, int stride, int width, int height) {
        var result = new byte[width * height];
        for (var y = 0; y < height; y++) {
            System.arraycopy(samples, y * stride, result, y * width, width);
        }
        return result;
    }

    static byte[] luma(YuvPicture picture) {
        return plane(picture.y(), picture.yStride(), picture.width(), picture.height());
    }

    static byte[] cb(YuvPicture picture) {
        return plane(picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight());
    }

    static byte[] cr(YuvPicture picture) {
        return plane(picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight());
    }

    /**
     * Concatenates the tight planes of a picture in Y, Cb, Cr order, the layout of ffmpeg's planar
     * raw video.
     */
    static byte[] planar(YuvPicture picture) {
        var y = luma(picture);
        if (!picture.chromaFormat().hasChroma()) {
            return y;
        }
        var u = cb(picture);
        var v = cr(picture);
        var result = new byte[y.length + u.length + v.length];
        System.arraycopy(y, 0, result, 0, y.length);
        System.arraycopy(u, 0, result, y.length, u.length);
        System.arraycopy(v, 0, result, y.length + u.length, v.length);
        return result;
    }

    /**
     * Converts a decoded picture to packed RGB exactly as djpeg does by default: triangle-filter
     * chroma upsampling and libjpeg's fixed-point YCbCr to RGB tables.
     */
    static byte[] toRgb(Picture picture, boolean fancy) {
        if (picture instanceof RgbaPicture rgba) {
            var result = new byte[rgba.width() * rgba.height() * 3];
            for (var y = 0; y < rgba.height(); y++) {
                for (var x = 0; x < rgba.width(); x++) {
                    var in = y * rgba.stride() + x * 4;
                    var out = (y * rgba.width() + x) * 3;
                    result[out] = rgba.pixels()[in];
                    result[out + 1] = rgba.pixels()[in + 1];
                    result[out + 2] = rgba.pixels()[in + 2];
                }
            }
            return result;
        }
        var yuv = (YuvPicture) picture;
        var width = yuv.width();
        var height = yuv.height();
        var result = new byte[width * height * 3];
        if (!yuv.chromaFormat().hasChroma()) {
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var value = yuv.y()[y * yuv.yStride() + x];
                    var out = (y * width + x) * 3;
                    result[out] = value;
                    result[out + 1] = value;
                    result[out + 2] = value;
                }
            }
            return result;
        }
        var hMax = 1 << yuv.chromaFormat().shiftX();
        var vMax = 1 << yuv.chromaFormat().shiftY();
        var u = Upsampler.upsample(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), 1, 1, hMax, vMax,
                width, height, fancy);
        var v = Upsampler.upsample(yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), 1, 1, hMax, vMax,
                width, height, fancy);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var rgb = JpegOutput.yccToRgb(yuv.y()[y * yuv.yStride() + x] & 0xFF, u[y * width + x] & 0xFF,
                        v[y * width + x] & 0xFF);
                var out = (y * width + x) * 3;
                result[out] = (byte) (rgb >>> 16);
                result[out + 1] = (byte) (rgb >>> 8);
                result[out + 2] = (byte) rgb;
            }
        }
        return result;
    }

    /**
     * Builds an RGBA picture from packed RGB.
     */
    static RgbaPicture rgba(int width, int height, byte[] rgb) {
        var picture = RgbaPicture.allocate(width, height, false);
        for (var i = 0; i < width * height; i++) {
            picture.pixels()[i * 4] = rgb[i * 3];
            picture.pixels()[i * 4 + 1] = rgb[i * 3 + 1];
            picture.pixels()[i * 4 + 2] = rgb[i * 3 + 2];
            picture.pixels()[i * 4 + 3] = (byte) 255;
        }
        return picture;
    }

    /**
     * Wraps ffmpeg's planar raw output in a picture with tight strides.
     */
    static YuvPicture yuv(int width, int height, ChromaFormat format, byte[] planar) {
        var lumaSize = width * height;
        if (!format.hasChroma()) {
            return YuvPicture.builder().size(width, height).chromaFormat(format).color(ColorInfo.JPEG)
                    .luma(planar, width)
                    .build();
        }
        var chromaWidth = format.chromaWidth(width);
        var chromaSize = chromaWidth * format.chromaHeight(height);
        var y = java.util.Arrays.copyOfRange(planar, 0, lumaSize);
        var u = java.util.Arrays.copyOfRange(planar, lumaSize, lumaSize + chromaSize);
        var v = java.util.Arrays.copyOfRange(planar, lumaSize + chromaSize, lumaSize + 2 * chromaSize);
        return YuvPicture.builder().size(width, height).chromaFormat(format).color(ColorInfo.JPEG).luma(y, width)
                .chroma(u, v, chromaWidth)
                .build();
    }
}
