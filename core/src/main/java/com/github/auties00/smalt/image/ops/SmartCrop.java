/* Ports smartcrop.js 2.0.5 (smartcrop.js). Copyright Jonas Wagner; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;

/**
 * Chooses the region of a picture to keep when cropping it to an aspect ratio.
 *
 * <p>The region always has the largest size of the aspect ratio that fits the picture; the
 * strategies differ only in where they place it. Content-aware strategies analyze a copy reduced
 * so that its short side is at most {@value #ANALYSIS_SIDE} pixels, and map the position they
 * choose back to the picture.
 *
 * @implNote This implementation ports the {@link SmartCropStrategy#ATTENTION} analysis of smartcrop.js 2.0.5 with its
 * default options: the edge, skin and saturation detectors, the 8x8 score down-sampling, the candidates on an 8-pixel
 * grid and the importance function, rounding every stored value as a {@code Uint8ClampedArray} does, so the same
 * reduced pixels give the same position. The reduction is this module's bilinear resize rather than a browser's canvas
 * scaling, and {@link SmartCropStrategy#ENTROPY} follows the idea of libvips' entropy crop without its code.
 */
final class SmartCrop {
    /**
     * The short side of the analysis copy, as smartcrop.js prescales.
     */
    static final int ANALYSIS_SIDE = 256;

    /**
     * The weight of the detail (edge) score.
     */
    private static final double DETAIL_WEIGHT = 0.2;

    /**
     * The normalized skin color.
     */
    private static final double SKIN_RED = 0.78, SKIN_GREEN = 0.57, SKIN_BLUE = 0.44;

    /**
     * The bias added to the detail under a skin pixel.
     */
    private static final double SKIN_BIAS = 0.01;

    /**
     * The lightness range of skin.
     */
    private static final double SKIN_BRIGHTNESS_MIN = 0.2, SKIN_BRIGHTNESS_MAX = 1.0;

    /**
     * The similarity to the skin color above which a pixel counts as skin.
     */
    private static final double SKIN_THRESHOLD = 0.8;

    /**
     * The weight of the skin score.
     */
    private static final double SKIN_WEIGHT = 1.8;

    /**
     * The lightness range of saturated pixels.
     */
    private static final double SATURATION_BRIGHTNESS_MIN = 0.05, SATURATION_BRIGHTNESS_MAX = 0.9;

    /**
     * The saturation above which a pixel counts as saturated.
     */
    private static final double SATURATION_THRESHOLD = 0.4;

    /**
     * The bias added to the detail under a saturated pixel.
     */
    private static final double SATURATION_BIAS = 0.2;

    /**
     * The weight of the saturation score.
     */
    private static final double SATURATION_WEIGHT = 0.1;

    /**
     * The down-sampling factor of the score map, and the step between candidate positions.
     */
    private static final int STEP = 8;

    /**
     * The fraction of the crop, from each edge, in which importance falls off.
     */
    private static final double EDGE_RADIUS = 0.4;

    /**
     * The weight of the edge fall-off.
     */
    private static final double EDGE_WEIGHT = -20.0;

    /**
     * The importance of pixels outside the crop.
     */
    private static final double OUTSIDE_IMPORTANCE = -0.5;

    /**
     * The number of luma bins of the entropy strategy.
     */
    private static final int ENTROPY_BINS = 64;

    /**
     * Prevents instantiation.
     */
    private SmartCrop() {
        throw new AssertionError();
    }

    /**
     * Chooses the region of a picture with the given aspect ratio.
     *
     * @param picture the picture to analyze
     * @param aspectWidth the width part of the aspect ratio
     * @param aspectHeight the height part of the aspect ratio
     * @param strategy how to choose the region
     * @return the region
     */
    static Region find(Picture picture, int aspectWidth, int aspectHeight, SmartCropStrategy strategy) {
        var width = picture.width();
        var height = picture.height();
        var scale = Math.min((double) width / aspectWidth, (double) height / aspectHeight);
        var cropWidth = (int) Math.clamp(Math.floor(aspectWidth * scale + 1e-9), 1, width);
        var cropHeight = (int) Math.clamp(Math.floor(aspectHeight * scale + 1e-9), 1, height);
        var centerX = (width - cropWidth) / 2;
        var centerY = (height - cropHeight) / 2;
        if (strategy == SmartCropStrategy.CENTER || (cropWidth == width && cropHeight == height)) {
            return new Region(centerX, centerY, cropWidth, cropHeight);
        }
        var prescale = Math.min((double) ANALYSIS_SIDE / Math.min(width, height), 1);
        var analysisWidth = Math.max(1, (int) (width * prescale));
        var analysisHeight = Math.max(1, (int) (height * prescale));
        var reduced = ImageOps.resize(ImageOps.toEightBit(picture), analysisWidth, analysisHeight, ResizeFilter.BILINEAR);
        var rgba = ImageOps.toRgba(reduced);
        var pixels = tight(rgba);
        var analysisCropWidth = Math.max(1, (int) (cropWidth * prescale));
        var analysisCropHeight = Math.max(1, (int) (cropHeight * prescale));
        var position = strategy == SmartCropStrategy.ATTENTION
                ? attention(pixels, analysisWidth, analysisHeight, analysisCropWidth, analysisCropHeight)
                : entropy(pixels, analysisWidth, analysisHeight, analysisCropWidth, analysisCropHeight);
        var x = position == null ? centerX : (int) Math.clamp((long) (position[0] / prescale), 0, width - cropWidth);
        var y = position == null ? centerY : (int) Math.clamp((long) (position[1] / prescale), 0, height - cropHeight);
        return new Region(x, y, cropWidth, cropHeight);
    }

    /**
     * Copies the samples of an RGBA picture into a tightly packed array.
     *
     * @param rgba the picture
     * @return the samples, {@code 4 * width} per row
     */
    private static byte[] tight(RgbaPicture rgba) {
        var rowLength = 4 * rgba.width();
        if (rgba.stride() == rowLength) {
            return rgba.pixels();
        }
        var out = new byte[rowLength * rgba.height()];
        for (var y = 0; y < rgba.height(); y++) {
            System.arraycopy(rgba.pixels(), y * rgba.stride(), out, y * rowLength, rowLength);
        }
        return out;
    }

    /**
     * Finds the crop position with the best smartcrop.js score.
     *
     * @param rgba the tightly packed RGBA samples of the analysis copy
     * @param width the width of the analysis copy
     * @param height the height of the analysis copy
     * @param cropWidth the crop width in analysis pixels
     * @param cropHeight the crop height in analysis pixels
     * @return the column and row of the best crop, or {@code null} when no candidate fits
     */
    static int[] attention(byte[] rgba, int width, int height, int cropWidth, int cropHeight) {
        var features = new int[4 * width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var p = 4 * (y * width + x);
                var r = rgba[p] & 0xFF;
                var g = rgba[p + 1] & 0xFF;
                var b = rgba[p + 2] & 0xFF;
                double lightness;
                if (x == 0 || x >= width - 1 || y == 0 || y >= height - 1) {
                    lightness = cie(rgba, p);
                } else {
                    lightness = cie(rgba, p) * 4 - cie(rgba, p - width * 4) - cie(rgba, p - 4) - cie(rgba, p + 4)
                            - cie(rgba, p + width * 4);
                }
                features[p + 1] = clamped(lightness);
                var light = cie(r, g, b) / 255;
                var skin = skinColor(r, g, b);
                if (skin > SKIN_THRESHOLD && light >= SKIN_BRIGHTNESS_MIN && light <= SKIN_BRIGHTNESS_MAX) {
                    features[p] = clamped((skin - SKIN_THRESHOLD) * (255 / (1 - SKIN_THRESHOLD)));
                }
                var saturation = saturation(r, g, b);
                if (light >= SATURATION_BRIGHTNESS_MIN && light <= SATURATION_BRIGHTNESS_MAX
                        && saturation > SATURATION_THRESHOLD) {
                    features[p + 2] = clamped((saturation - SATURATION_THRESHOLD) * (255 / (1 - SATURATION_THRESHOLD)));
                }
            }
        }
        var scoreWidth = width / STEP;
        var scoreHeight = height / STEP;
        var scores = downSample(features, width, scoreWidth, scoreHeight);
        int[] best = null;
        var bestScore = Double.NEGATIVE_INFINITY;
        for (var y = 0; y + cropHeight <= height; y += STEP) {
            for (var x = 0; x + cropWidth <= width; x += STEP) {
                var total = score(scores, scoreWidth, scoreHeight, x, y, cropWidth, cropHeight);
                if (total > bestScore) {
                    bestScore = total;
                    best = new int[]{x, y};
                }
            }
        }
        return best;
    }

    /**
     * Down-samples the feature map by {@link #STEP} in each direction, as smartcrop.js does.
     *
     * @param features the feature map: skin, detail, saturation and boost per pixel
     * @param width the width of the feature map
     * @param scoreWidth the width of the result
     * @param scoreHeight the height of the result
     * @return the down-sampled map, four values per cell
     */
    private static int[] downSample(int[] features, int width, int scoreWidth, int scoreHeight) {
        var out = new int[4 * scoreWidth * scoreHeight];
        var inverseArea = 1.0 / (STEP * STEP);
        for (var y = 0; y < scoreHeight; y++) {
            for (var x = 0; x < scoreWidth; x++) {
                double r = 0;
                double g = 0;
                double b = 0;
                double a = 0;
                double maxR = 0;
                double maxG = 0;
                for (var v = 0; v < STEP; v++) {
                    for (var u = 0; u < STEP; u++) {
                        var j = ((y * STEP + v) * width + (x * STEP + u)) * 4;
                        r += features[j];
                        g += features[j + 1];
                        b += features[j + 2];
                        a += features[j + 3];
                        maxR = Math.max(maxR, features[j]);
                        maxG = Math.max(maxG, features[j + 1]);
                    }
                }
                var i = (y * scoreWidth + x) * 4;
                out[i] = clamped(r * inverseArea * 0.5 + maxR * 0.5);
                out[i + 1] = clamped(g * inverseArea * 0.7 + maxG * 0.3);
                out[i + 2] = clamped(b * inverseArea);
                out[i + 3] = clamped(a * inverseArea);
            }
        }
        return out;
    }

    /**
     * Scores one crop candidate.
     *
     * @param scores the down-sampled feature map
     * @param scoreWidth the width of the map
     * @param scoreHeight the height of the map
     * @param cropX the crop's column in analysis pixels
     * @param cropY the crop's row in analysis pixels
     * @param cropWidth the crop's width in analysis pixels
     * @param cropHeight the crop's height in analysis pixels
     * @return the total score
     */
    private static double score(int[] scores, int scoreWidth, int scoreHeight, int cropX, int cropY, int cropWidth,
                                int cropHeight) {
        double detailSum = 0;
        double saturationSum = 0;
        double skinSum = 0;
        double boostSum = 0;
        for (var y = 0; y < scoreHeight * STEP; y += STEP) {
            for (var x = 0; x < scoreWidth * STEP; x += STEP) {
                var p = ((y / STEP) * scoreWidth + (x / STEP)) * 4;
                var importance = importance(cropX, cropY, cropWidth, cropHeight, x, y);
                var detail = scores[p + 1] / 255.0;
                skinSum += (scores[p] / 255.0) * (detail + SKIN_BIAS) * importance;
                detailSum += detail * importance;
                saturationSum += (scores[p + 2] / 255.0) * (detail + SATURATION_BIAS) * importance;
                boostSum += (scores[p + 3] / 255.0) * importance;
            }
        }
        return (detailSum * DETAIL_WEIGHT + skinSum * SKIN_WEIGHT + saturationSum * SATURATION_WEIGHT
                + boostSum * 100.0) / ((double) cropWidth * cropHeight);
    }

    /**
     * Returns the importance of a point for a crop.
     *
     * @param cropX the crop's column
     * @param cropY the crop's row
     * @param cropWidth the crop's width
     * @param cropHeight the crop's height
     * @param px the point's column
     * @param py the point's row
     * @return the importance; negative outside the crop and near its edges
     */
    private static double importance(int cropX, int cropY, int cropWidth, int cropHeight, int px, int py) {
        if (cropX > px || px >= cropX + cropWidth || cropY > py || py >= cropY + cropHeight) {
            return OUTSIDE_IMPORTANCE;
        }
        var x = (double) (px - cropX) / cropWidth;
        var y = (double) (py - cropY) / cropHeight;
        var dxCenter = Math.abs(0.5 - x) * 2;
        var dyCenter = Math.abs(0.5 - y) * 2;
        var dx = Math.max(dxCenter - 1.0 + EDGE_RADIUS, 0);
        var dy = Math.max(dyCenter - 1.0 + EDGE_RADIUS, 0);
        var d = (dx * dx + dy * dy) * EDGE_WEIGHT;
        var s = 1.41 - Math.sqrt(dxCenter * dxCenter + dyCenter * dyCenter);
        s += Math.max(0, s + d + 0.5) * 1.2 * (thirds(dxCenter) + thirds(dyCenter));
        return s + d;
    }

    /**
     * Returns the rule-of-thirds weight of a normalized distance from the center.
     *
     * @param x the distance, {@code 0} at the center and {@code 1} at the edge
     * @return the weight, highest on the thirds
     */
    private static double thirds(double x) {
        x = (((x - 1.0 / 3 + 1.0) % 2.0) * 0.5 - 0.5) * 16;
        return Math.max(1.0 - x * x, 0.0);
    }

    /**
     * Returns the smartcrop.js lightness of the pixel at an index.
     *
     * @param rgba the samples
     * @param index the index of the pixel's red sample
     * @return the lightness
     */
    private static double cie(byte[] rgba, int index) {
        return cie(rgba[index] & 0xFF, rgba[index + 1] & 0xFF, rgba[index + 2] & 0xFF);
    }

    /**
     * Returns the smartcrop.js lightness of a color.
     *
     * @param r the red sample
     * @param g the green sample
     * @param b the blue sample
     * @return {@code 0.5126 * b + 0.7152 * g + 0.0722 * r}
     */
    private static double cie(int r, int g, int b) {
        return 0.5126 * b + 0.7152 * g + 0.0722 * r;
    }

    /**
     * Returns the similarity of a color to the skin color.
     *
     * @param r the red sample
     * @param g the green sample
     * @param b the blue sample
     * @return one minus the distance of the normalized color from the skin color; NaN for black
     */
    private static double skinColor(int r, int g, int b) {
        var magnitude = Math.sqrt((double) r * r + (double) g * g + (double) b * b);
        var rd = r / magnitude - SKIN_RED;
        var gd = g / magnitude - SKIN_GREEN;
        var bd = b / magnitude - SKIN_BLUE;
        return 1 - Math.sqrt(rd * rd + gd * gd + bd * bd);
    }

    /**
     * Returns the HSL saturation of a color.
     *
     * @param r the red sample
     * @param g the green sample
     * @param b the blue sample
     * @return the saturation, {@code 0} for gray
     */
    private static double saturation(int r, int g, int b) {
        var maximum = Math.max(r / 255.0, Math.max(g / 255.0, b / 255.0));
        var minimum = Math.min(r / 255.0, Math.min(g / 255.0, b / 255.0));
        if (maximum == minimum) {
            return 0;
        }
        var l = (maximum + minimum) / 2;
        var d = maximum - minimum;
        return l > 0.5 ? d / (2 - maximum - minimum) : d / (maximum + minimum);
    }

    /**
     * Converts a value as a store into a {@code Uint8ClampedArray} does: clamped to
     * {@code [0, 255]}, rounded to nearest with ties to even, NaN to zero.
     *
     * @param value the value to store
     * @return the stored byte value
     */
    static int clamped(double value) {
        if (!(value > 0)) {
            return 0;
        }
        if (value >= 255) {
            return 255;
        }
        return (int) Math.rint(value);
    }

    /**
     * Shrinks a window to the crop size by removing, strip by strip, the side with the lower luma
     * entropy.
     *
     * @param rgba the tightly packed RGBA samples of the analysis copy
     * @param width the width of the analysis copy
     * @param height the height of the analysis copy
     * @param cropWidth the crop width in analysis pixels
     * @param cropHeight the crop height in analysis pixels
     * @return the column and row of the window
     */
    static int[] entropy(byte[] rgba, int width, int height, int cropWidth, int cropHeight) {
        var luma = new int[width * height];
        for (var i = 0; i < luma.length; i++) {
            var p = 4 * i;
            var y = ((rgba[p] & 0xFF) * 19595 + (rgba[p + 1] & 0xFF) * 38470 + (rgba[p + 2] & 0xFF) * 7471 + 0x8000) >> 16;
            luma[i] = y * ENTROPY_BINS / 256;
        }
        var left = 0;
        var top = 0;
        var right = width;
        var bottom = height;
        var histogram = new int[ENTROPY_BINS];
        while (right - left > cropWidth) {
            var strip = Math.max(1, Math.min(STEP, right - left - cropWidth));
            var leftEntropy = entropy(luma, width, left, top, left + strip, bottom, histogram);
            var rightEntropy = entropy(luma, width, right - strip, top, right, bottom, histogram);
            if (leftEntropy < rightEntropy) {
                left += strip;
            } else {
                right -= strip;
            }
        }
        while (bottom - top > cropHeight) {
            var strip = Math.max(1, Math.min(STEP, bottom - top - cropHeight));
            var topEntropy = entropy(luma, width, left, top, right, top + strip, histogram);
            var bottomEntropy = entropy(luma, width, left, bottom - strip, right, bottom, histogram);
            if (topEntropy < bottomEntropy) {
                top += strip;
            } else {
                bottom -= strip;
            }
        }
        return new int[]{left, top};
    }

    /**
     * Returns the Shannon entropy, in bits, of the luma bins of a rectangle.
     *
     * @param luma the luma bin of every pixel
     * @param width the row length of {@code luma}
     * @param left the first column
     * @param top the first row
     * @param right the column after the last one
     * @param bottom the row after the last one
     * @param histogram a scratch histogram of {@link #ENTROPY_BINS} bins
     * @return the entropy
     */
    private static double entropy(int[] luma, int width, int left, int top, int right, int bottom, int[] histogram) {
        java.util.Arrays.fill(histogram, 0);
        for (var y = top; y < bottom; y++) {
            for (var x = left; x < right; x++) {
                histogram[luma[y * width + x]]++;
            }
        }
        var total = (double) (right - left) * (bottom - top);
        double entropy = 0;
        for (var count : histogram) {
            if (count != 0) {
                var p = count / total;
                entropy -= p * StrictMath.log(p);
            }
        }
        return entropy / StrictMath.log(2);
    }
}
