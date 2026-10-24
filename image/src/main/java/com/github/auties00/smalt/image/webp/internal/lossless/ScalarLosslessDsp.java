package com.github.auties00.smalt.image.webp.internal.lossless;

/**
 * Implements the lossless pixel kernels with plain Java arithmetic; the reference for every
 * other implementation.
 *
 * @implNote This implementation follows libwebp's {@code src/dsp/lossless.c} and
 * {@code src/dsp/lossless_enc.c} (BSD-3): the predictors, the packed-channel arithmetic of
 * {@code VP8LAddPixels}/{@code VP8LSubPixels} and the 3.5 fixed-point cross-color deltas.
 */
public final class ScalarLosslessDsp implements LosslessDsp {
    /**
     * Opaque black, the prediction of mode 0.
     */
    static final int ARGB_BLACK = 0xFF000000;

    /**
     * Constructs the kernel.
     */
    public ScalarLosslessDsp() {
    }

    @Override
    public void addGreenToBlueAndRed(int[] src, int srcPos, int[] dst, int dstPos, int count) {
        for (var i = 0; i < count; i++) {
            var argb = src[srcPos + i];
            var green = (argb >>> 8) & 0xFF;
            var redBlue = (argb & 0x00FF00FF) + ((green << 16) | green);
            dst[dstPos + i] = (argb & 0xFF00FF00) | (redBlue & 0x00FF00FF);
        }
    }

    @Override
    public void subtractGreenFromBlueAndRed(int[] data, int pos, int count) {
        for (var i = pos; i < pos + count; i++) {
            var argb = data[i];
            var green = (argb >>> 8) & 0xFF;
            var newR = (((argb >>> 16) & 0xFF) - green) & 0xFF;
            var newB = ((argb & 0xFF) - green) & 0xFF;
            data[i] = (argb & 0xFF00FF00) | (newR << 16) | newB;
        }
    }

    @Override
    public void transformColorInverse(int code, int[] src, int srcPos, int[] dst, int dstPos, int count) {
        var greenToRed = (byte) code;
        var greenToBlue = (byte) (code >>> 8);
        var redToBlue = (byte) (code >>> 16);
        for (var i = 0; i < count; i++) {
            var argb = src[srcPos + i];
            var green = (byte) (argb >>> 8);
            var newRed = ((argb >>> 16) + ((greenToRed * green) >> 5)) & 0xFF;
            var newBlue = argb + ((greenToBlue * green) >> 5);
            newBlue = (newBlue + ((redToBlue * (byte) newRed) >> 5)) & 0xFF;
            dst[dstPos + i] = (argb & 0xFF00FF00) | (newRed << 16) | newBlue;
        }
    }

    @Override
    public void transformColor(int code, int[] data, int pos, int count) {
        var greenToRed = (byte) code;
        var greenToBlue = (byte) (code >>> 8);
        var redToBlue = (byte) (code >>> 16);
        for (var i = pos; i < pos + count; i++) {
            var argb = data[i];
            var green = (byte) (argb >>> 8);
            var red = (byte) (argb >>> 16);
            var newRed = ((argb >>> 16) - ((greenToRed * green) >> 5)) & 0xFF;
            var newBlue = (argb - ((greenToBlue * green) >> 5) - ((redToBlue * red) >> 5)) & 0xFF;
            data[i] = (argb & 0xFF00FF00) | (newRed << 16) | newBlue;
        }
    }

    @Override
    public void predictorAdd(int mode, int[] in, int inPos, int[] out, int outPos, int count, int width) {
        var top = outPos - width;
        switch (mode) {
            case 1 -> {
                var left = out[outPos - 1];
                for (var i = 0; i < count; i++) {
                    left = addPixels(in[inPos + i], left);
                    out[outPos + i] = left;
                }
            }
            case 2 -> {
                for (var i = 0; i < count; i++) {
                    out[outPos + i] = addPixels(in[inPos + i], out[top + i]);
                }
            }
            case 3 -> {
                for (var i = 0; i < count; i++) {
                    out[outPos + i] = addPixels(in[inPos + i], out[top + i + 1]);
                }
            }
            case 4 -> {
                for (var i = 0; i < count; i++) {
                    out[outPos + i] = addPixels(in[inPos + i], out[top + i - 1]);
                }
            }
            case 5 -> {
                var left = out[outPos - 1];
                for (var i = 0; i < count; i++) {
                    left = addPixels(in[inPos + i], average2(average2(left, out[top + i + 1]), out[top + i]));
                    out[outPos + i] = left;
                }
            }
            case 6 -> {
                var left = out[outPos - 1];
                for (var i = 0; i < count; i++) {
                    left = addPixels(in[inPos + i], average2(left, out[top + i - 1]));
                    out[outPos + i] = left;
                }
            }
            case 7 -> {
                var left = out[outPos - 1];
                for (var i = 0; i < count; i++) {
                    left = addPixels(in[inPos + i], average2(left, out[top + i]));
                    out[outPos + i] = left;
                }
            }
            case 8 -> {
                for (var i = 0; i < count; i++) {
                    out[outPos + i] = addPixels(in[inPos + i], average2(out[top + i - 1], out[top + i]));
                }
            }
            case 9 -> {
                for (var i = 0; i < count; i++) {
                    out[outPos + i] = addPixels(in[inPos + i], average2(out[top + i], out[top + i + 1]));
                }
            }
            case 10 -> {
                var left = out[outPos - 1];
                for (var i = 0; i < count; i++) {
                    var pred = average2(average2(left, out[top + i - 1]), average2(out[top + i], out[top + i + 1]));
                    left = addPixels(in[inPos + i], pred);
                    out[outPos + i] = left;
                }
            }
            case 11 -> {
                var left = out[outPos - 1];
                for (var i = 0; i < count; i++) {
                    left = addPixels(in[inPos + i], select(out[top + i], left, out[top + i - 1]));
                    out[outPos + i] = left;
                }
            }
            case 12 -> {
                var left = out[outPos - 1];
                for (var i = 0; i < count; i++) {
                    left = addPixels(in[inPos + i], clampedAddSubtractFull(left, out[top + i], out[top + i - 1]));
                    out[outPos + i] = left;
                }
            }
            case 13 -> {
                var left = out[outPos - 1];
                for (var i = 0; i < count; i++) {
                    left = addPixels(in[inPos + i], clampedAddSubtractHalf(left, out[top + i], out[top + i - 1]));
                    out[outPos + i] = left;
                }
            }
            default -> {
                for (var i = 0; i < count; i++) {
                    out[outPos + i] = addPixels(in[inPos + i], ARGB_BLACK);
                }
            }
        }
    }

    @Override
    public void predictorResiduals(int mode, int[] current, int curPos, int[] upper, int upperPos, int count, int[] out,
                                   int outPos) {
        switch (mode) {
            case 1 -> {
                for (var i = 0; i < count; i++) {
                    out[outPos + i] = subPixels(current[curPos + i], current[curPos + i - 1]);
                }
            }
            case 2, 3, 4 -> {
                var t = upperPos + (mode == 2 ? 0 : mode == 3 ? 1 : -1);
                for (var i = 0; i < count; i++) {
                    out[outPos + i] = subPixels(current[curPos + i], upper[t + i]);
                }
            }
            case 5 -> {
                for (var i = 0; i < count; i++) {
                    var t = upperPos + i;
                    out[outPos + i] = subPixels(current[curPos + i],
                            average2(average2(current[curPos + i - 1], upper[t + 1]), upper[t]));
                }
            }
            case 6, 7 -> {
                var t = upperPos + (mode == 6 ? -1 : 0);
                for (var i = 0; i < count; i++) {
                    out[outPos + i] = subPixels(current[curPos + i], average2(current[curPos + i - 1], upper[t + i]));
                }
            }
            case 8, 9 -> {
                var t = upperPos + (mode == 8 ? -1 : 0);
                for (var i = 0; i < count; i++) {
                    out[outPos + i] = subPixels(current[curPos + i], average2(upper[t + i], upper[t + i + 1]));
                }
            }
            case 10 -> {
                for (var i = 0; i < count; i++) {
                    var t = upperPos + i;
                    out[outPos + i] = subPixels(current[curPos + i],
                            average2(average2(current[curPos + i - 1], upper[t - 1]), average2(upper[t], upper[t + 1])));
                }
            }
            case 11 -> {
                for (var i = 0; i < count; i++) {
                    var t = upperPos + i;
                    out[outPos + i] = subPixels(current[curPos + i], select(upper[t], current[curPos + i - 1], upper[t - 1]));
                }
            }
            case 12 -> {
                for (var i = 0; i < count; i++) {
                    var t = upperPos + i;
                    out[outPos + i] = subPixels(current[curPos + i],
                            clampedAddSubtractFull(current[curPos + i - 1], upper[t], upper[t - 1]));
                }
            }
            case 13 -> {
                for (var i = 0; i < count; i++) {
                    var t = upperPos + i;
                    out[outPos + i] = subPixels(current[curPos + i],
                            clampedAddSubtractHalf(current[curPos + i - 1], upper[t], upper[t - 1]));
                }
            }
            default -> {
                for (var i = 0; i < count; i++) {
                    out[outPos + i] = subPixels(current[curPos + i], ARGB_BLACK);
                }
            }
        }
    }

    @Override
    public void argbToRgba(int[] src, int srcPos, byte[] dst, int dstPos, int count) {
        for (var i = 0; i < count; i++) {
            var argb = src[srcPos + i];
            var d = dstPos + (i << 2);
            dst[d] = (byte) (argb >>> 16);
            dst[d + 1] = (byte) (argb >>> 8);
            dst[d + 2] = (byte) argb;
            dst[d + 3] = (byte) (argb >>> 24);
        }
    }

    /**
     * Returns the prediction of a mode for one pixel.
     *
     * @param mode  the predictor mode, from 0 to 15
     * @param left  the left neighbor
     * @param top   the array holding the row above
     * @param index the index in {@code top} of the pixel directly above
     * @return the predicted ARGB value
     */
    static int predict(int mode, int left, int[] top, int index) {
        return switch (mode) {
            case 1 -> left;
            case 2 -> top[index];
            case 3 -> top[index + 1];
            case 4 -> top[index - 1];
            case 5 -> average2(average2(left, top[index + 1]), top[index]);
            case 6 -> average2(left, top[index - 1]);
            case 7 -> average2(left, top[index]);
            case 8 -> average2(top[index - 1], top[index]);
            case 9 -> average2(top[index], top[index + 1]);
            case 10 -> average2(average2(left, top[index - 1]), average2(top[index], top[index + 1]));
            case 11 -> select(top[index], left, top[index - 1]);
            case 12 -> clampedAddSubtractFull(left, top[index], top[index - 1]);
            case 13 -> clampedAddSubtractHalf(left, top[index], top[index - 1]);
            default -> ARGB_BLACK;
        };
    }

    /**
     * Adds two pixels channel by channel, modulo 256.
     *
     * @param a the first pixel
     * @param b the second pixel
     * @return the channel-wise sum
     */
    static int addPixels(int a, int b) {
        var alphaAndGreen = (a & 0xFF00FF00) + (b & 0xFF00FF00);
        var redAndBlue = (a & 0x00FF00FF) + (b & 0x00FF00FF);
        return (alphaAndGreen & 0xFF00FF00) | (redAndBlue & 0x00FF00FF);
    }

    /**
     * Subtracts two pixels channel by channel, modulo 256.
     *
     * @param a the minuend
     * @param b the subtrahend
     * @return the channel-wise difference
     */
    static int subPixels(int a, int b) {
        var alphaAndGreen = 0x00FF00FF + (a & 0xFF00FF00) - (b & 0xFF00FF00);
        var redAndBlue = 0xFF00FF00 + (a & 0x00FF00FF) - (b & 0x00FF00FF);
        return (alphaAndGreen & 0xFF00FF00) | (redAndBlue & 0x00FF00FF);
    }

    /**
     * Averages two pixels channel by channel, rounding down.
     *
     * @param a the first pixel
     * @param b the second pixel
     * @return the channel-wise average
     */
    static int average2(int a, int b) {
        return (((a ^ b) & 0xFEFEFEFE) >>> 1) + (a & b);
    }

    /**
     * Chooses between the top and left neighbors by the Paeth-like gradient rule of predictor
     * mode 11.
     *
     * @param a the top neighbor
     * @param b the left neighbor
     * @param c the top-left neighbor
     * @return {@code a} when its estimate is at least as close, {@code b} otherwise
     */
    static int select(int a, int b, int c) {
        var paMinusPb = sub3(a >>> 24, b >>> 24, c >>> 24)
                + sub3((a >>> 16) & 0xFF, (b >>> 16) & 0xFF, (c >>> 16) & 0xFF)
                + sub3((a >>> 8) & 0xFF, (b >>> 8) & 0xFF, (c >>> 8) & 0xFF)
                + sub3(a & 0xFF, b & 0xFF, c & 0xFF);
        var mask = (paMinusPb - 1) >> 31;
        return (a & mask) | (b & ~mask);
    }

    /**
     * Returns {@code |b - c| - |a - c|} for one channel.
     *
     * @param a the first channel value
     * @param b the second channel value
     * @param c the reference channel value
     * @return the difference of distances
     */
    private static int sub3(int a, int b, int c) {
        return Math.abs(b - c) - Math.abs(a - c);
    }

    /**
     * Clamps a channel value to 0..255.
     *
     * @param v the value
     * @return the clamped value
     */
    private static int clip255(int v) {
        return Math.min(255, Math.max(0, v));
    }

    /**
     * Returns {@code clamp(a + b - c)} channel by channel (predictor mode 12).
     *
     * @param c0 the left neighbor
     * @param c1 the top neighbor
     * @param c2 the top-left neighbor
     * @return the prediction
     */
    static int clampedAddSubtractFull(int c0, int c1, int c2) {
        var a = clip255((c0 >>> 24) + (c1 >>> 24) - (c2 >>> 24));
        var r = clip255(((c0 >>> 16) & 0xFF) + ((c1 >>> 16) & 0xFF) - ((c2 >>> 16) & 0xFF));
        var g = clip255(((c0 >>> 8) & 0xFF) + ((c1 >>> 8) & 0xFF) - ((c2 >>> 8) & 0xFF));
        var b = clip255((c0 & 0xFF) + (c1 & 0xFF) - (c2 & 0xFF));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Returns {@code clamp(avg + (avg - c) / 2)} channel by channel, where {@code avg} averages
     * the left and top neighbors (predictor mode 13).
     *
     * @param c0 the left neighbor
     * @param c1 the top neighbor
     * @param c2 the top-left neighbor
     * @return the prediction
     */
    static int clampedAddSubtractHalf(int c0, int c1, int c2) {
        var ave = average2(c0, c1);
        var a = addSubtractHalf(ave >>> 24, c2 >>> 24);
        var r = addSubtractHalf((ave >>> 16) & 0xFF, (c2 >>> 16) & 0xFF);
        var g = addSubtractHalf((ave >>> 8) & 0xFF, (c2 >>> 8) & 0xFF);
        var b = addSubtractHalf(ave & 0xFF, c2 & 0xFF);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Returns {@code clamp(a + (a - b) / 2)} with division truncating toward zero.
     *
     * @param a the average channel value
     * @param b the top-left channel value
     * @return the clamped value
     */
    private static int addSubtractHalf(int a, int b) {
        return clip255(a + (a - b) / 2);
    }

    @Override
    public long combinedShannonEntropy(int[] x, int xOff, int[] y, int yOff) {
        return EntropyMath.combinedShannonEntropy(x, xOff, y, yOff);
    }

    @Override
    public void entropyUnrefined(int[] x, int[] y, int length, EntropyMath.Stats stats) {
        EntropyMath.entropyUnrefined(x, y, length, stats);
    }

    @Override
    public long combinedShannonEntropy(int[] x, int xOff, int[] y, int yOff, int sumY, long entropyY) {
        var retval = entropyY;
        var sumX = 0;
        for (var i = 0; i < 256; i++) {
            var xv = x[xOff + i];
            if (xv != 0) {
                var yv = y[yOff + i];
                sumX += xv;
                retval += EntropyMath.fastSLog2(xv) + EntropyMath.fastSLog2(xv + yv) - EntropyMath.fastSLog2(yv);
            }
        }
        return EntropyMath.fastSLog2(sumX) + EntropyMath.fastSLog2(sumX + sumY) - retval;
    }

    @Override
    public void collectColorRedTransforms(int[] argb, int pos, int stride, int tileWidth, int tileHeight, int greenToRed,
                                          int[] histo, int[] scratch) {
        var g2r = (byte) greenToRed;
        for (var y = 0; y < tileHeight; y++) {
            var row = pos + y * stride;
            for (var x = 0; x < tileWidth; x++) {
                var pixel = argb[row + x];
                var green = (byte) (pixel >>> 8);
                ++histo[((pixel >>> 16) - ((g2r * green) >> 5)) & 0xFF];
            }
        }
    }

    @Override
    public void collectColorBlueTransforms(int[] argb, int pos, int stride, int tileWidth, int tileHeight, int greenToBlue,
                                           int redToBlue, int[] histo, int[] scratch) {
        var g2b = (byte) greenToBlue;
        var r2b = (byte) redToBlue;
        for (var y = 0; y < tileHeight; y++) {
            var row = pos + y * stride;
            for (var x = 0; x < tileWidth; x++) {
                var pixel = argb[row + x];
                var green = (byte) (pixel >>> 8);
                var red = (byte) (pixel >>> 16);
                ++histo[(pixel - ((g2b * green) >> 5) - ((r2b * red) >> 5)) & 0xFF];
            }
        }
    }
}
