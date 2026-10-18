package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaLimits;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reproduces {@code djpeg -dct int -scale 1/N} from this module's building blocks: coefficient
 * decoding, the islow and reduced-size IDCTs with libjpeg's per-component DCT scaling (chroma is
 * scaled up through the IDCT where the subsampling allows), libjpeg's upsampler selection, and the
 * jdcolor.c conversions. Matching djpeg's MD5 therefore proves each piece bit-exact.
 */
final class DjpegEmulator {
    private DjpegEmulator() {
        throw new AssertionError();
    }

    static byte[] decode(byte[] jpeg, int scaleShift) {
        var reader = new JpegReader(jpeg, 0, jpeg.length, MediaLimits.DEFAULT);
        var coefficients = reader.decodeCoefficients();
        var colorSpace = reader.colorSpace();
        var frame = coefficients.frame();
        var components = frame.components;
        var minSize = 8 >> scaleShift;
        var width = JpegFrame.ceilDiv(frame.width, 1 << scaleShift);
        var height = JpegFrame.ceilDiv(frame.height, 1 << scaleShift);
        var full = new byte[components.length][];
        for (var c = 0; c < components.length; c++) {
            var component = components[c];
            var size = minSize;
            while (size < 8 && (frame.hMax * minSize) % (component.h * size * 2) == 0
                    && (frame.vMax * minSize) % (component.v * size * 2) == 0) {
                size *= 2;
            }
            var shift = Integer.numberOfTrailingZeros(8 / size);
            var stride = component.paddedBlocksWide * size;
            var plane = new byte[stride * component.paddedBlocksHigh * size];
            var block = new int[64];
            var quant = coefficients.quantization(c);
            for (var by = 0; by < component.paddedBlocksHigh; by++) {
                for (var bx = 0; bx < component.paddedBlocksWide; bx++) {
                    JpegReader.transformBlock(coefficients.coefficients(c), (by * component.paddedBlocksWide + bx) * 64,
                            quant, block, plane, by * size * stride + bx * size, stride, shift);
                }
            }
            var planeWidth = JpegFrame.ceilDiv((long) frame.width * component.h * size, frame.hMax * 8);
            var planeHeight = JpegFrame.ceilDiv((long) frame.height * component.v * size, frame.vMax * 8);
            var hIn = component.h * size / minSize;
            var vIn = component.v * size / minSize;
            full[c] = Upsampler.upsample(plane, stride, planeWidth, planeHeight, hIn, vIn, frame.hMax, frame.vMax,
                    width, height, minSize > 1);
        }
        var out = new ByteArrayOutputStream();
        if (colorSpace == JpegColorSpace.GRAY) {
            out.writeBytes(("P5\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(full[0]);
            return out.toByteArray();
        }
        out.writeBytes(("P6\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII));
        var rgb = new byte[width * height * 3];
        for (var i = 0; i < width * height; i++) {
            int r;
            int g;
            int b;
            switch (colorSpace) {
                case YCBCR -> {
                    var packed = JpegOutput.yccToRgb(full[0][i] & 0xFF, full[1][i] & 0xFF, full[2][i] & 0xFF);
                    r = packed >>> 16;
                    g = (packed >>> 8) & 0xFF;
                    b = packed & 0xFF;
                }
                case RGB -> {
                    r = full[0][i] & 0xFF;
                    g = full[1][i] & 0xFF;
                    b = full[2][i] & 0xFF;
                }
                default -> throw new IllegalArgumentException(colorSpace.toString());
            }
            rgb[3 * i] = (byte) r;
            rgb[3 * i + 1] = (byte) g;
            rgb[3 * i + 2] = (byte) b;
        }
        out.writeBytes(rgb);
        return out.toByteArray();
    }
}
