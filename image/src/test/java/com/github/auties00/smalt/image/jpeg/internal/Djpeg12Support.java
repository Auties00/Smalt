package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Yuv16Picture;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reproduces djpeg's 12-bit output (a PNM of maxval 4095 with two big-endian bytes per sample) for the
 * tests of other packages: from a JPEG file through {@link DjpegEmulator}, or from a decoded
 * {@link Yuv16Picture} through libjpeg's 12-bit upsampling and color conversion.
 */
public final class Djpeg12Support {
    private Djpeg12Support() {
        throw new AssertionError();
    }

    public static byte[] emulate(byte[] jpeg, int scaleShift) {
        return DjpegEmulator.decode(jpeg, scaleShift);
    }

    public static byte[] pnm(int width, int height, int channels, int[] samples) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(((channels == 1 ? "P5" : "P6") + "\n" + width + " " + height + "\n4095\n")
                .getBytes(StandardCharsets.US_ASCII));
        for (var sample : samples) {
            out.write(sample >>> 8);
            out.write(sample & 0xFF);
        }
        return out.toByteArray();
    }

    public static byte[] djpegOutput(Yuv16Picture picture, boolean fancy) {
        var width = picture.width();
        var height = picture.height();
        if (picture.chromaFormat() == ChromaFormat.MONOCHROME) {
            var samples = new int[width * height];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    samples[y * width + x] = picture.y()[y * picture.yStride() + x];
                }
            }
            return pnm(width, height, 1, samples);
        }
        var format = picture.chromaFormat();
        var hMax = 1 << format.shiftX();
        var vMax = 1 << format.shiftY();
        var u = Upsampler.upsample(picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(),
                1, 1, hMax, vMax, width, height, fancy);
        var v = Upsampler.upsample(picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(),
                1, 1, hMax, vMax, width, height, fancy);
        var samples = new int[width * height * 3];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var rgb = JpegOutput.yccToRgb12(picture.y()[y * picture.yStride() + x], u[y * width + x], v[y * width + x]);
                var i = 3 * (y * width + x);
                samples[i] = (int) (rgb >>> 24);
                samples[i + 1] = (int) ((rgb >>> 12) & 0xFFF);
                samples[i + 2] = (int) (rgb & 0xFFF);
            }
        }
        return pnm(width, height, 3, samples);
    }
}
