/* Ports libwebp 1.6.0 (src/enc/near_lossless_enc.c, src/enc/predictor_enc.c). Copyright 2015 Google Inc.; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.webp.internal.lossless;

/**
 * Implements the near-lossless modes of the lossless encoder: the pixel preprocessing that
 * quantizes non-smooth pixels when no predictor runs ({@code VP8ApplyNearLossless}), and the
 * quantization of predictor residuals ({@code NearLossless} of {@code predictor_enc.c}).
 *
 * <p>Both bound the change of every channel by the quantization step of the near-lossless level:
 * 1 bit per 20 levels below 100, so level 100 is lossless and level 0 shaves 5 bits. Arguments
 * are not checked; values outside the documented ranges give unspecified results.
 */
final class NearLossless {
    /**
     * The side below which a picture, in both directions, is not preprocessed.
     */
    private static final int MIN_DIMENSION = 64;

    /**
     * Prevents instantiation.
     */
    private NearLossless() {
        throw new AssertionError();
    }

    /**
     * {@return the number of bits a near-lossless level shaves off, from 0 (level 100) to 5}
     *
     * @param quality the level, from 0 to 100
     */
    static int bits(int quality) {
        return 5 - quality / 20;
    }

    /**
     * Returns the pixels quantized by the preprocessing: every pixel off the border whose
     * 4-connected neighbors differ from it by at least the limit in some channel is rounded to a
     * multiple of the step, repeating with halved steps down to 2 ({@code VP8ApplyNearLossless}).
     *
     * @param argb the pixels; not modified
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param quality the level, from 0 to 99
     * @return the processed pixels, a new array
     */
    static int[] preprocess(int[] argb, int width, int height, int quality) {
        var out = argb.clone();
        var limitBits = bits(quality);
        if ((width < MIN_DIMENSION && height < MIN_DIMENSION) || height < 3) {
            return out;
        }
        var rows = new int[3 * width];
        apply(width, height, argb, limitBits, rows, out);
        for (var i = limitBits - 1; i != 0; i--) {
            apply(width, height, out, i, rows, out);
        }
        return out;
    }

    /**
     * Runs one preprocessing round ({@code NearLossless} of {@code near_lossless_enc.c}).
     *
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param src the source pixels, which may be {@code dst}
     * @param limitBits the bits of the round's step
     * @param rows scratch space for three rows
     * @param dst the destination pixels
     */
    private static void apply(int width, int height, int[] src, int limitBits, int[] rows, int[] dst) {
        var limit = 1 << limitBits;
        var prev = 0;
        var curr = width;
        var next = 2 * width;
        System.arraycopy(src, 0, rows, curr, width);
        System.arraycopy(src, width, rows, next, width);
        for (var y = 0; y < height; y++) {
            var row = y * width;
            if (y == 0 || y == height - 1) {
                System.arraycopy(src, row, dst, row, width);
            } else {
                System.arraycopy(src, row + width, rows, next, width);
                dst[row] = src[row];
                dst[row + width - 1] = src[row + width - 1];
                for (var x = 1; x < width - 1; x++) {
                    var pixel = rows[curr + x];
                    if (isNear(pixel, rows[curr + x - 1], limit) && isNear(pixel, rows[curr + x + 1], limit)
                            && isNear(pixel, rows[prev + x], limit) && isNear(pixel, rows[next + x], limit)) {
                        dst[row + x] = pixel;
                    } else {
                        dst[row + x] = closestDiscretized(pixel, limitBits);
                    }
                }
            }
            var temp = prev;
            prev = curr;
            curr = next;
            next = temp;
        }
    }

    /**
     * {@return whether every channel of two pixels differs by less than a limit}
     *
     * @param a the first pixel
     * @param b the second pixel
     * @param limit the exclusive bound of the channel differences
     */
    private static boolean isNear(int a, int b, int limit) {
        for (var k = 0; k < 32; k += 8) {
            var delta = ((a >>> k) & 0xFF) - ((b >>> k) & 0xFF);
            if (delta >= limit || delta <= -limit) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rounds a channel value to the nearer multiple of {@code 1 << bits}, ties to the even
     * multiple, or to 255 ({@code FindClosestDiscretized}).
     *
     * @param a the value
     * @param bits the step bits, at least 1
     * @return the rounded value
     */
    private static int closestChannel(int a, int bits) {
        var mask = (1 << bits) - 1;
        var biased = a + (mask >> 1) + ((a >> bits) & 1);
        return biased > 0xFF ? 0xFF : biased & ~mask;
    }

    /**
     * Rounds every channel of a pixel ({@code ClosestDiscretizedArgb}).
     *
     * @param a the pixel
     * @param bits the step bits, at least 1
     * @return the rounded pixel
     */
    private static int closestDiscretized(int a, int bits) {
        return closestChannel(a >>> 24, bits) << 24
                | closestChannel((a >>> 16) & 0xFF, bits) << 16
                | closestChannel((a >>> 8) & 0xFF, bits) << 8
                | closestChannel(a & 0xFF, bits);
    }

    /**
     * Returns the largest channel difference between two pixels.
     *
     * @param p1 the first pixel
     * @param p2 the second pixel
     * @return the difference, from 0 to 255
     */
    private static int maxDiffBetweenPixels(int p1, int p2) {
        var a = Math.abs((p1 >>> 24) - (p2 >>> 24));
        var r = Math.abs(((p1 >>> 16) & 0xFF) - ((p2 >>> 16) & 0xFF));
        var g = Math.abs(((p1 >>> 8) & 0xFF) - ((p2 >>> 8) & 0xFF));
        var b = Math.abs((p1 & 0xFF) - (p2 & 0xFF));
        return Math.max(Math.max(a, r), Math.max(g, b));
    }

    /**
     * Returns a pixel with green added back to red and blue, undoing subtract-green.
     *
     * @param argb the pixel
     * @return the pixel
     */
    private static int addGreenToBlueAndRed(int argb) {
        var green = (argb >>> 8) & 0xFF;
        var redBlue = argb & 0x00FF00FF;
        redBlue += (green << 16) | green;
        redBlue &= 0x00FF00FF;
        return (argb & 0xFF00FF00) | redBlue;
    }

    /**
     * Computes, for every pixel of a row but the first and the last, the largest channel
     * difference to its four neighbors ({@code MaxDiffsForRow}).
     *
     * @param width the number of pixels
     * @param stride the distance between rows in {@code argb}
     * @param argb the pixels, with the rows above and below the row present
     * @param pos the index of the row's first pixel
     * @param maxDiffs the destination
     * @param diffPos the index of the row's first difference
     * @param usedSubtractGreen whether the pixels went through subtract-green
     */
    static void maxDiffsForRow(int width, int stride, int[] argb, int pos, byte[] maxDiffs, int diffPos,
                               boolean usedSubtractGreen) {
        if (width <= 2) {
            return;
        }
        var current = argb[pos];
        var right = argb[pos + 1];
        if (usedSubtractGreen) {
            current = addGreenToBlueAndRed(current);
            right = addGreenToBlueAndRed(right);
        }
        for (var x = 1; x < width - 1; x++) {
            var up = argb[pos - stride + x];
            var down = argb[pos + stride + x];
            var left = current;
            current = right;
            right = argb[pos + x + 1];
            if (usedSubtractGreen) {
                up = addGreenToBlueAndRed(up);
                down = addGreenToBlueAndRed(down);
                right = addGreenToBlueAndRed(right);
            }
            var diff = Math.max(Math.max(maxDiffBetweenPixels(current, up), maxDiffBetweenPixels(current, down)),
                    Math.max(maxDiffBetweenPixels(current, left), maxDiffBetweenPixels(current, right)));
            maxDiffs[diffPos + x] = (byte) diff;
        }
    }

    /**
     * Quantizes the difference between a channel value and its prediction to a multiple of the
     * step, modulo 256, without crossing an inclusive upper boundary
     * ({@code NearLosslessComponent}).
     *
     * @param value the channel value
     * @param predict the prediction
     * @param boundary the largest value the reconstruction may take
     * @param quantization the step, a power of 2
     * @return the quantized residual
     */
    private static int component(int value, int predict, int boundary, int quantization) {
        var residual = (value - predict) & 0xFF;
        var boundaryResidual = (boundary - predict) & 0xFF;
        var lower = residual & ~(quantization - 1);
        var upper = lower + quantization;
        var bias = ((boundary - value) & 0xFF) < boundaryResidual ? 1 : 0;
        if (residual - lower < upper - residual + bias) {
            if (residual > boundaryResidual && lower <= boundaryResidual) {
                return lower + (quantization >> 1);
            }
            return lower;
        }
        if (residual <= boundaryResidual && upper > boundaryResidual) {
            return lower + (quantization >> 1);
        }
        return upper & 0xFF;
    }

    /**
     * Returns the quantized residual of a pixel: each channel's difference to the prediction
     * rounded to a multiple of the largest power of 2 below both the maximum step and the
     * pixel's largest neighbor difference, keeping fully transparent and fully opaque alpha
     * exact ({@code NearLossless} of {@code predictor_enc.c}).
     *
     * @param value the pixel
     * @param predict the prediction
     * @param maxQuantization the largest step, a power of 2
     * @param maxDiff the pixel's largest channel difference to its neighbors
     * @param usedSubtractGreen whether red and blue are stored as offsets from green
     * @return the residual
     */
    static int residual(int value, int predict, int maxQuantization, int maxDiff, boolean usedSubtractGreen) {
        if (maxDiff <= 2) {
            return ScalarLosslessDsp.subPixels(value, predict);
        }
        var quantization = maxQuantization;
        while (quantization >= maxDiff) {
            quantization >>= 1;
        }
        int a;
        var alpha = value >>> 24;
        if (alpha == 0 || alpha == 0xFF) {
            a = (alpha - (predict >>> 24)) & 0xFF;
        } else {
            a = component(alpha, predict >>> 24, 0xFF, quantization);
        }
        var g = component((value >>> 8) & 0xFF, (predict >>> 8) & 0xFF, 0xFF, quantization);
        var newGreen = 0;
        var greenDiff = 0;
        if (usedSubtractGreen) {
            newGreen = ((predict >>> 8) + g) & 0xFF;
            greenDiff = (newGreen - ((value >>> 8) & 0xFF)) & 0xFF;
        }
        var boundary = (0xFF - newGreen) & 0xFF;
        var r = component((((value >>> 16) & 0xFF) - greenDiff) & 0xFF, (predict >>> 16) & 0xFF, boundary,
                quantization);
        var b = component(((value & 0xFF) - greenDiff) & 0xFF, predict & 0xFF, boundary, quantization);
        return a << 24 | r << 16 | g << 8 | b;
    }
}
