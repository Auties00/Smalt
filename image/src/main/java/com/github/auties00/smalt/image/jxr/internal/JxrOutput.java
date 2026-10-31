/* Ports jxrlib 1.1 (image/decode/strdec.c: outputMBRow, outputMBRowAlpha, outputNChannel, interpolateUV,
   inverseConvertRGBE, pixel2float, backwardHalf). Copyright (c) Microsoft Corporation; BSD 2-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxr.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Converts the reconstructed samples of the planes of a JPEG XR codestream into the samples of its output format.
 */
final class JxrOutput {
    /**
     * The extra precision bits of the scaled arithmetic.
     */
    private static final int SCALED_SHIFT = 3;

    private JxrOutput() {
        throw new AssertionError();
    }

    /**
     * Converts the decoded planes.
     *
     * @param header the image header
     * @param primary the decoded primary plane, with its alpha plane
     * @return the samples
     * @throws MediaException.Unsupported if the output format does not fit the internal one
     */
    static JxrCodestream.Samples convert(JxrHeader header, JxrPlaneDecoder primary) {
        var internal = primary.colorFormat;
        var external = internal == JxrHeader.Y_ONLY ? JxrHeader.Y_ONLY : header.outputColorFormat;
        var bitDepth = header.outputBitDepth;
        var width = header.width;
        var height = header.height;
        var left = header.extraLeft;
        var top = header.extraTop;
        var scaled = primary.plane.scaled;
        var shift = scaled ? SCALED_SHIFT : 0;
        var rounding = scaled ? (1 << (SCALED_SHIFT - 1)) - 1 : 0;
        var mantissa = primary.plane.mantissa;
        var exponentBias = primary.plane.exponentBias;
        var planes = primary.planes;
        var stride = primary.planeWidth[0];
        int[] u = null;
        int[] v = null;
        var chromaStride = stride;
        var resolutionChange = external != JxrHeader.Y_ONLY && (internal == JxrHeader.YUV_420
                && external != JxrHeader.YUV_420 || internal == JxrHeader.YUV_422 && external != JxrHeader.YUV_422);
        if (primary.channels >= 3) {
            u = planes[1];
            v = planes[2];
            chromaStride = primary.planeWidth[1];
        }
        if (resolutionChange) {
            var upsampled = upsample(primary, external);
            u = upsampled[0];
            v = upsampled[1];
            chromaStride = external == JxrHeader.YUV_422 ? primary.mbWidth * 8 : stride;
        }
        var y = planes[0];
        var alphaPlane = primary.next;
        var pixels = width * height;
        int format;
        int[][] out;
        switch (external) {
            case JxrHeader.RGB -> {
                if (primary.channels < 3) {
                    throw new MediaException.Unsupported("jxr", "RGB output of " + primary.channels + " channels");
                }
                format = JxrCodestream.RGB;
                out = new int[3][pixels];
                rgb(bitDepth, header, y, u, v, stride, chromaStride, left, top, width, height, shift, rounding,
                        mantissa, exponentBias, out);
            }
            case JxrHeader.RGBE -> {
                if (primary.channels < 3 || bitDepth != JxrHeader.BD_8) {
                    throw new MediaException.Unsupported("jxr", "RGBE output of this codestream");
                }
                format = JxrCodestream.RGBE;
                out = new int[4][pixels];
                for (var row = 0; row < height; row++) {
                    for (var col = 0; col < width; col++) {
                        var at = (row + top) * stride + col + left;
                        var cat = (row + top) * chromaStride + col + left;
                        var g = y[at] + rounding;
                        var r = -u[cat];
                        var b = v[cat];
                        g -= r >> 1;
                        r -= ((b + 1) >> 1) - g;
                        b += r;
                        rgbe(r >> shift, g >> shift, b >> shift, out, row * width + col);
                    }
                }
            }
            case JxrHeader.CMYK -> {
                if (primary.channels < 4) {
                    throw new MediaException.Unsupported("jxr", "CMYK output of " + primary.channels + " channels");
                }
                format = JxrCodestream.CMYK;
                out = new int[4][pixels];
                cmyk(bitDepth, y, u, v, planes[3], stride, left, top, width, height, shift, rounding, mantissa, out);
            }
            case JxrHeader.YUV_422, JxrHeader.YUV_420 -> {
                var halfHeight = external == JxrHeader.YUV_420;
                if (bitDepth != JxrHeader.BD_8 && bitDepth != JxrHeader.BD_16
                        || internal != JxrHeader.YUV_420 && (halfHeight || internal != JxrHeader.YUV_422)) {
                    throw new MediaException.Unsupported("jxr", "YUV output of this codestream");
                }
                format = halfHeight ? JxrCodestream.YUV420 : JxrCodestream.YUV422;
                var chromaWidth = (width + 1) / 2;
                var chromaHeight = halfHeight ? (height + 1) / 2 : height;
                out = new int[][]{new int[pixels], new int[chromaWidth * chromaHeight],
                        new int[chromaWidth * chromaHeight]};
                var bias = nChannelBias(bitDepth, shift, rounding, mantissa);
                for (var row = 0; row < height; row++) {
                    for (var col = 0; col < width; col++) {
                        out[0][row * width + col] = nChannel(bitDepth, y[(row + top) * stride + col + left], bias,
                                shift, mantissa, exponentBias);
                    }
                }
                var chromaTop = halfHeight ? top / 2 : top;
                for (var row = 0; row < chromaHeight; row++) {
                    for (var col = 0; col < chromaWidth; col++) {
                        var at = (row + chromaTop) * chromaStride + col + left / 2;
                        out[1][row * chromaWidth + col] = nChannel(bitDepth, u[at], bias, shift, mantissa,
                                exponentBias);
                        out[2][row * chromaWidth + col] = nChannel(bitDepth, v[at], bias, shift, mantissa,
                                exponentBias);
                    }
                }
            }
            default -> {
                var count = external == JxrHeader.Y_ONLY ? 1 : primary.channels;
                format = external == JxrHeader.Y_ONLY ? JxrCodestream.GRAY
                        : external == JxrHeader.YUV_444 ? JxrCodestream.YUV444 : JxrCodestream.CHANNELS;
                if (bitDepth == JxrHeader.BD_5 || bitDepth == JxrHeader.BD_10 || bitDepth == JxrHeader.BD_565
                        || bitDepth == JxrHeader.BD_1 && external != JxrHeader.Y_ONLY) {
                    throw new MediaException.Unsupported("jxr", "packed output of independent channels");
                }
                out = new int[count][pixels];
                if (bitDepth == JxrHeader.BD_1) {
                    var threshold = shift > 0 ? 1 << (shift - 1) : 1;
                    var flip = header.blackWhite ? 1 : 0;
                    for (var row = 0; row < height; row++) {
                        for (var col = 0; col < width; col++) {
                            var value = y[(row + top) * stride + col + left];
                            out[0][row * width + col] = (flip + (value >= threshold ? 1 : 0)) & 1;
                        }
                    }
                } else {
                    var bias = nChannelBias(bitDepth, shift, rounding, mantissa);
                    for (var c = 0; c < count; c++) {
                        var source = c == 0 ? y : c == 1 && resolutionChange ? u : c == 2 && resolutionChange ? v
                                : planes[c];
                        var sourceStride = c > 0 && resolutionChange ? chromaStride : primary.planeWidth[c];
                        for (var row = 0; row < height; row++) {
                            for (var col = 0; col < width; col++) {
                                out[c][row * width + col] = nChannel(bitDepth,
                                        source[(row + top) * sourceStride + col + left], bias, shift, mantissa,
                                        exponentBias);
                            }
                        }
                    }
                }
            }
        }
        // jxrlib writes interleaved alpha into RGB and CMYK pixel formats, a gray image's included
        var hasAlpha = alphaPlane != null && (header.outputColorFormat == JxrHeader.RGB
                || header.outputColorFormat == JxrHeader.CMYK);
        if (alphaPlane != null && !hasAlpha) {
            throw new MediaException.Unsupported("jxr", "an interleaved alpha plane of this color format");
        }
        if (hasAlpha) {
            var alpha = new int[pixels];
            var a = alphaPlane.planes[0];
            var alphaStride = alphaPlane.planeWidth[0];
            var alphaBias = switch (bitDepth) {
                case JxrHeader.BD_8 -> (1 << (shift + 7)) + (shift == 0 ? 0 : 1 << (shift - 1));
                case JxrHeader.BD_16 -> (1 << (shift + 15)) + (shift == 0 ? 0 : 1 << (shift - 1));
                case JxrHeader.BD_16S, JxrHeader.BD_16F, JxrHeader.BD_32S, JxrHeader.BD_32F ->
                        shift == 0 ? 0 : 1 << (shift - 1);
                default -> throw new MediaException.Unsupported("jxr", "alpha of this bit depth");
            };
            for (var row = 0; row < height; row++) {
                for (var col = 0; col < width; col++) {
                    var value = (a[(row + top) * alphaStride + col + left] + alphaBias) >> shift;
                    alpha[row * width + col] = switch (bitDepth) {
                        case JxrHeader.BD_8 -> clip(value, 0, 255);
                        case JxrHeader.BD_16 -> clip(value << mantissa, 0, 65535);
                        case JxrHeader.BD_16S -> clip(value << mantissa, -32768, 32767);
                        case JxrHeader.BD_16F -> backwardHalf(value);
                        case JxrHeader.BD_32S -> value << mantissa;
                        default -> Float.floatToRawIntBits(pixelToFloat(value, exponentBias, mantissa));
                    };
                }
            }
            var withAlpha = java.util.Arrays.copyOf(out, out.length + 1);
            withAlpha[out.length] = alpha;
            out = withAlpha;
        }
        return new JxrCodestream.Samples(header, format, width, height, out, hasAlpha);
    }

    /**
     * {@return the bias added to a sample before the shift of the scaled arithmetic, per output bit depth}
     */
    private static int nChannelBias(int bitDepth, int shift, int rounding, int mantissa) {
        return switch (bitDepth) {
            case JxrHeader.BD_8 -> (128 << shift) + rounding;
            case JxrHeader.BD_16 -> (((1 << 15) >> mantissa) << shift) + (shift == 0 ? 0 : 1 << (shift - 1));
            case JxrHeader.BD_32 -> ((Integer.MIN_VALUE >> mantissa) << shift) + (shift == 0 ? 0 : 1 << (shift - 1));
            default -> rounding;
        };
    }

    /**
     * Converts one sample of an independent channel (outputNChannel).
     */
    private static int nChannel(int bitDepth, int sample, int bias, int shift, int mantissa, int exponentBias) {
        var p = (sample + bias) >> shift;
        return switch (bitDepth) {
            case JxrHeader.BD_8 -> clip(p, 0, 255);
            case JxrHeader.BD_16 -> clip(p << mantissa, 0, 65535);
            case JxrHeader.BD_16S -> clip(p << mantissa, -32768, 32767);
            case JxrHeader.BD_16F -> backwardHalf(p);
            case JxrHeader.BD_32, JxrHeader.BD_32S -> p << mantissa;
            case JxrHeader.BD_32F -> Float.floatToRawIntBits(pixelToFloat(p, exponentBias, mantissa));
            default -> throw new MediaException.Unsupported("jxr", "bit depth " + bitDepth + " of independent"
                    + " channels");
        };
    }

    /**
     * Converts the YUV samples of RGB output into RGB.
     */
    private static void rgb(int bitDepth, JxrHeader header, int[] y, int[] u, int[] v, int stride, int chromaStride,
                            int left, int top, int width, int height, int shift, int rounding, int mantissa,
                            int exponentBias, int[][] out) {
        var bias = switch (bitDepth) {
            case JxrHeader.BD_8 -> (128 << shift) + rounding;
            case JxrHeader.BD_16 -> (((1 << 15) >> mantissa) << shift) + (shift == 0 ? 0 : 1 << (shift - 1));
            case JxrHeader.BD_32 -> ((Integer.MIN_VALUE >> mantissa) << shift) + (shift == 0 ? 0 : 1 << (shift - 1));
            case JxrHeader.BD_5 -> (16 << shift) + rounding;
            case JxrHeader.BD_565 -> (32 << shift) + rounding;
            case JxrHeader.BD_10 -> (512 << shift) + rounding;
            case JxrHeader.BD_1 -> throw new MediaException.Unsupported("jxr", "black and white RGB output");
            default -> rounding;
        };
        var swapped = header.redBlueSwapped;
        for (var row = 0; row < height; row++) {
            for (var col = 0; col < width; col++) {
                var at = (row + top) * stride + col + left;
                var cat = (row + top) * chromaStride + col + left;
                var g = y[at] + bias;
                var r = -u[cat];
                var b = v[cat];
                g -= r >> 1;
                r -= ((b + 1) >> 1) - g;
                b += r;
                var index = row * width + col;
                switch (bitDepth) {
                    case JxrHeader.BD_8 -> {
                        out[0][index] = clip(r >> shift, 0, 255);
                        out[1][index] = clip(g >> shift, 0, 255);
                        out[2][index] = clip(b >> shift, 0, 255);
                    }
                    case JxrHeader.BD_16 -> {
                        out[0][index] = clip((r >> shift) << mantissa, 0, 65535);
                        out[1][index] = clip((g >> shift) << mantissa, 0, 65535);
                        out[2][index] = clip((b >> shift) << mantissa, 0, 65535);
                    }
                    case JxrHeader.BD_16S -> {
                        out[0][index] = clip((r >> shift) << mantissa, -32768, 32767);
                        out[1][index] = clip((g >> shift) << mantissa, -32768, 32767);
                        out[2][index] = clip((b >> shift) << mantissa, -32768, 32767);
                    }
                    case JxrHeader.BD_16F -> {
                        out[0][index] = backwardHalf(r >> shift);
                        out[1][index] = backwardHalf(g >> shift);
                        out[2][index] = backwardHalf(b >> shift);
                    }
                    case JxrHeader.BD_32, JxrHeader.BD_32S -> {
                        out[0][index] = (r >> shift) << mantissa;
                        out[1][index] = (g >> shift) << mantissa;
                        out[2][index] = (b >> shift) << mantissa;
                    }
                    case JxrHeader.BD_32F -> {
                        out[0][index] = Float.floatToRawIntBits(pixelToFloat(r >> shift, exponentBias, mantissa));
                        out[1][index] = Float.floatToRawIntBits(pixelToFloat(g >> shift, exponentBias, mantissa));
                        out[2][index] = Float.floatToRawIntBits(pixelToFloat(b >> shift, exponentBias, mantissa));
                    }
                    case JxrHeader.BD_565 -> {
                        out[0][index] = clip(r >> (shift + 1), 0, 31);
                        out[1][index] = clip(g >> shift, 0, 63);
                        out[2][index] = clip(b >> (shift + 1), 0, 31);
                    }
                    case JxrHeader.BD_5 -> {
                        out[0][index] = clip(r >> shift, 0, 31);
                        out[1][index] = clip(g >> shift, 0, 31);
                        out[2][index] = clip(b >> shift, 0, 31);
                    }
                    default -> {
                        out[0][index] = clip(r >> shift, 0, 1023);
                        out[1][index] = clip(g >> shift, 0, 1023);
                        out[2][index] = clip(b >> shift, 0, 1023);
                    }
                }
            }
        }
        // jxrlib packs the first channel into the low bits unless red and blue are swapped, and the packed
        // formats hold blue in their low bits
        if (!swapped && (bitDepth == JxrHeader.BD_5 || bitDepth == JxrHeader.BD_565 || bitDepth == JxrHeader.BD_10)) {
            var red = out[0];
            out[0] = out[2];
            out[2] = red;
        }
    }

    /**
     * Converts the samples of CMYK output.
     */
    private static void cmyk(int bitDepth, int[] y, int[] u, int[] v, int[] k, int stride, int left, int top,
                             int width, int height, int shift, int rounding, int mantissa, int[][] out) {
        int bias1;
        int bias2;
        switch (bitDepth) {
            case JxrHeader.BD_8 -> {
                bias1 = 128 << shift;
                bias2 = rounding;
            }
            case JxrHeader.BD_16 -> {
                bias1 = (32768 >> mantissa) << shift;
                bias2 = (shift == 0 ? 0 : 1 << (shift - 1));
            }
            case JxrHeader.BD_16S -> {
                bias1 = 0;
                bias2 = rounding;
            }
            default -> throw new MediaException.Unsupported("jxr", "CMYK output of bit depth " + bitDepth);
        }
        for (var row = 0; row < height; row++) {
            for (var col = 0; col < width; col++) {
                var at = (row + top) * stride + col + left;
                var m = -y[at] + bias1;
                var c = u[at];
                var ye = -v[at];
                var kk = k[at] + bias2;
                kk -= (m + 1) >> 1;
                m -= (c >> 1) - kk;
                c -= ((ye + 1) >> 1) - m;
                ye += c;
                var index = row * width + col;
                int[] values = {c >> shift, m >> shift, ye >> shift, kk >> shift};
                for (var channel = 0; channel < 4; channel++) {
                    out[channel][index] = switch (bitDepth) {
                        case JxrHeader.BD_8 -> clip(values[channel], 0, 255);
                        case JxrHeader.BD_16 -> clip(values[channel] << mantissa, 0, 65535);
                        default -> (short) (values[channel] << mantissa);
                    };
                }
            }
        }
    }

    /**
     * Upsamples the chroma of a 4:2:0 or 4:2:2 image for an output of another subsampling (interpolateUV).
     *
     * @return the upsampled U and V planes, at full resolution, or at 4:2:2 resolution for 4:2:2 output
     */
    private static int[][] upsample(JxrPlaneDecoder primary, int external) {
        var width = primary.mbWidth * 16;
        var height = primary.mbHeight * 16;
        var result = new int[2][];
        for (var c = 0; c < 2; c++) {
            var source = primary.planes[1 + c];
            var sourceStride = primary.planeWidth[1 + c];
            if (primary.colorFormat == JxrHeader.YUV_422) {
                var target = new int[width * height];
                for (var row = 0; row < height; row++) {
                    var line = row * width;
                    var last = 0;
                    for (var col = 0; col < width; col += 2) {
                        var value = source[row * sourceStride + (col >> 1)];
                        target[line + col] = value;
                        if (col > 0) {
                            target[line + col - 1] = (target[line + col - 2] + value + 1) >> 1;
                        }
                        last = value;
                    }
                    target[line + width - 1] = last;
                }
                result[c] = target;
                continue;
            }
            var halfWidth = width / 2;
            var vertical = new int[halfWidth * height];
            var chromaHeight = height / 2;
            for (var col = 0; col < halfWidth; col++) {
                for (var row = 0; row < chromaHeight; row++) {
                    vertical[(2 * row) * halfWidth + col] = source[row * sourceStride + col];
                    if (row > 0) {
                        vertical[(2 * row - 1) * halfWidth + col] = (vertical[(2 * row - 2) * halfWidth + col]
                                + source[row * sourceStride + col] + 1) >> 1;
                    }
                }
                vertical[(height - 1) * halfWidth + col] = vertical[(height - 2) * halfWidth + col];
            }
            if (external == JxrHeader.YUV_422) {
                result[c] = vertical;
                continue;
            }
            var target = new int[width * height];
            for (var row = 0; row < height; row++) {
                var line = row * width;
                for (var col = 0; col < halfWidth; col++) {
                    target[line + 2 * col] = vertical[row * halfWidth + col];
                }
                for (var col = 1; col < width - 2; col += 2) {
                    target[line + col] = (target[line + col + 1] + target[line + col - 1] + 1) >> 1;
                }
                target[line + width - 1] = target[line + width - 2];
            }
            result[c] = target;
        }
        return result;
    }

    /**
     * Converts an RGB sample of 9 bits of mantissa into RGBE bytes (inverseConvertRGBE).
     */
    private static void rgbe(int red, int green, int blue, int[][] out, int index) {
        var r = convertComponent(red);
        var g = convertComponent(green);
        var b = convertComponent(blue);
        var e = Math.max(Math.max(r >> 8, g >> 8), b >> 8);
        out[0][index] = align(r, e);
        out[1][index] = align(g, e);
        out[2][index] = align(b, e);
        out[3][index] = e;
    }

    /**
     * {@return a component as its exponent in bits 8 and up and its mantissa byte (inverseConvert)}
     */
    private static int convertComponent(int value) {
        if (value <= 0) {
            return 0;
        }
        if ((value >> 7) > 1) {
            return ((value >> 7) & 0xff) << 8 | ((value & 0x7f) | 0x80);
        }
        return 1 << 8 | (value & 0xff);
    }

    /**
     * {@return the mantissa byte of a component shifted to the shared exponent}
     */
    private static int align(int component, int exponent) {
        var mantissa = component & 0xff;
        var own = component >> 8;
        if (exponent > own) {
            mantissa = ((mantissa * 2 + 1) >> (exponent - own + 1)) & 0xff;
        }
        return mantissa;
    }

    /**
     * Converts an integer sample into a float of the given mantissa length and exponent bias (pixel2float).
     */
    static float pixelToFloat(int value, int exponentBias, int mantissaLength) {
        var lmShift = 1 << mantissaLength;
        var sign = value >> 31;
        var magnitude = (value ^ sign) - sign;
        var e = magnitude >>> mantissaLength;
        var m = (magnitude & (lmShift - 1)) | lmShift;
        if (e == 0) {
            m ^= lmShift;
            e = 1;
        }
        e += 127 - exponentBias;
        while (m < lmShift && e > 1 && m > 0) {
            e--;
            m <<= 1;
        }
        if (m < lmShift) {
            e = 0;
        } else {
            m ^= lmShift;
        }
        m <<= 23 - mantissaLength;
        return Float.intBitsToFloat((sign & 0x80000000) | (e << 23) | m);
    }

    /**
     * {@return the half bits of a sign-magnitude integer sample (backwardHalf)}
     */
    private static int backwardHalf(int value) {
        var sign = value >> 31;
        return (((value & 0x7fff) ^ sign) - sign) & 0xffff;
    }

    /**
     * {@return a value clamped to a range}
     */
    private static int clip(int value, int low, int high) {
        return value < low ? low : Math.min(value, high);
    }
}
