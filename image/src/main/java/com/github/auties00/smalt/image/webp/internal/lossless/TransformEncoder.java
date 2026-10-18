package com.github.auties00.smalt.image.webp.internal.lossless;

import java.util.Arrays;

/**
 * Chooses and applies the spatial predictor and cross-color transforms of the lossless encoder.
 *
 * <p>The predictor search evaluates the 14 modes on every tile at the finest tile size and, at
 * the same time, on every coarser tile size up to a maximum, scoring each mode by the entropy of
 * its residuals combined with the residuals already chosen; the tile size whose total entropy is
 * lowest wins. The cross-color search picks, per tile, the multipliers that minimize the entropy
 * of the transformed red and blue channels.
 *
 * @implNote This implementation follows libwebp's {@code src/enc/predictor_enc.c} (BSD-3)
 * without its near-lossless path: tiles are visited in Z order within the largest tiles so that
 * every coarser tile is scored as soon as it is complete, and when pixels are not required to be
 * exact, the color of fully transparent pixels is replaced by the prediction so that their
 * residual color is zero.
 */
final class TransformEncoder {
    /**
     * The pixel and entropy kernels.
     */
    private static final LosslessDsp DSP = LosslessDsp.INSTANCE;

    /**
     * The number of values of the four channel histograms of a tile.
     */
    private static final int HISTO_SIZE = 4 * 256;

    /**
     * The bonus given to a mode equal to a neighboring tile's mode.
     */
    private static final long SPATIAL_PREDICTOR_BIAS = 15L << EntropyMath.LOG_2_PRECISION_BITS;

    /**
     * The mode used by the fastest method.
     */
    private static final int PRED_LOW_EFFORT = 11;

    /**
     * The number of predictor modes searched.
     */
    private static final int NUM_PRED_MODES = 14;

    /**
     * The smallest transform tile width in bits.
     */
    static final int MIN_TRANSFORM_BITS = 2;

    /**
     * The largest transform tile width in bits.
     */
    static final int MAX_TRANSFORM_BITS = 9;

    /**
     * Opaque black.
     */
    private static final int ARGB_BLACK = 0xFF000000;

    /**
     * Prevents instantiation.
     */
    private TransformEncoder() {
        throw new AssertionError();
    }

    /**
     * Returns the cost bonus for residual histograms concentrated near zero.
     *
     * @param counts   the histogram
     * @param offset   the index of its first count
     * @param weight0  the weight of the zero residual
     * @param expVal   the initial weight of residuals around zero
     * @return the negative bonus
     */
    private static long predictionCostBias(int[] counts, int offset, long weight0, long expVal) {
        var significantSymbols = 256 >> 4;
        var bits = (weight0 * counts[offset]) << EntropyMath.LOG_2_PRECISION_BITS;
        expVal <<= EntropyMath.LOG_2_PRECISION_BITS;
        for (var i = 1; i < significantSymbols; i++) {
            bits += EntropyMath.divRound(expVal * (counts[offset + i] + counts[offset + 256 - i]), 100);
            expVal = EntropyMath.divRound(6 * expVal, 10);
        }
        return -EntropyMath.divRound(bits, 10);
    }

    /**
     * The weights of the residuals {@code +-1} to {@code +-15} (at indices 1 to 15) in the
     * spatial search's {@link #predictionCostBias}: an initial weight of 94 at the log
     * precision, multiplied by 0.6 per step with rounding, exactly as the bias loop computes
     * them.
     */
    private static final long[] SPATIAL_BIAS_WEIGHTS = biasWeights(94);

    /**
     * Computes the residual weights of {@link #predictionCostBias} for an initial weight.
     *
     * @param expVal the initial weight of residuals around zero
     * @return the weights of the residuals {@code +-1} to {@code +-15}, at indices 1 to 15
     */
    private static long[] biasWeights(long expVal) {
        var weights = new long[256 >> 4];
        expVal <<= EntropyMath.LOG_2_PRECISION_BITS;
        for (var i = 1; i < weights.length; i++) {
            weights[i] = expVal;
            expVal = EntropyMath.divRound(6 * expVal, 10);
        }
        return weights;
    }

    /**
     * Returns {@link #predictionCostBias} of the spatial search (zero weight 1, initial weight
     * 94) from the precomputed weights.
     *
     * @implNote This implementation skips the residuals that never occur: their term,
     * {@code divRound(0, 100)}, is zero, so the sum is unchanged.
     *
     * @param counts the histogram
     * @param offset the index of its first count
     * @return the negative bonus
     */
    private static long spatialPredictionCostBias(int[] counts, int offset) {
        var bits = (long) counts[offset] << EntropyMath.LOG_2_PRECISION_BITS;
        var weights = SPATIAL_BIAS_WEIGHTS;
        for (var i = 1; i < weights.length; i++) {
            var n = counts[offset + i] + counts[offset + 256 - i];
            if (n != 0) {
                bits += EntropyMath.divRound(weights[i] * n, 100);
            }
        }
        return -EntropyMath.divRound(bits, 10);
    }

    /**
     * Scores the residual histogram of one mode on one tile.
     *
     * @param accumulated the residual histograms already chosen
     * @param accOff      the index of the accumulated histograms
     * @param accSums     the sum of each accumulated channel histogram
     * @param accEntropy  the sum of {@link EntropyMath#fastSLog2} over each accumulated channel
     *                    histogram
     * @param tile        the residual histograms of the mode
     * @param tileOff     the index of the mode's histograms
     * @param mode        the mode
     * @param leftMode    the mode of the tile to the left, or 255
     * @param aboveMode   the mode of the tile above, or 255
     * @return the cost, lower is better
     */
    private static long predictionCostSpatialHistogram(int[] accumulated, int accOff, int[] accSums, long[] accEntropy,
                                                       int[] tile, int tileOff, int mode, int leftMode, int aboveMode) {
        long retval = 0;
        for (var i = 0; i < 4; i++) {
            retval += spatialPredictionCostBias(tile, tileOff + i * 256);
            retval += DSP.combinedShannonEntropy(tile, tileOff + i * 256, accumulated, accOff + i * 256, accSums[i],
                    accEntropy[i]);
        }
        if (mode == leftMode) {
            retval -= SPATIAL_PREDICTOR_BIAS;
        }
        if (mode == aboveMode) {
            retval -= SPATIAL_PREDICTOR_BIAS;
        }
        return retval;
    }

    /**
     * Counts the four channels of a pixel.
     *
     * @param histo  the histograms
     * @param offset the index of the histograms
     * @param argb   the pixel
     */
    private static void updateHisto(int[] histo, int offset, int argb) {
        ++histo[offset + (argb >>> 24)];
        ++histo[offset + 256 + ((argb >>> 16) & 0xFF)];
        ++histo[offset + 512 + ((argb >>> 8) & 0xFF)];
        ++histo[offset + 768 + (argb & 0xFF)];
    }

    /**
     * Computes the residuals of a run of pixels of one row with one mode.
     *
     * <p>Row 0 is predicted from the left (the first pixel from black); column 0 from the pixel
     * above. When not exact, a fully transparent pixel gets its color replaced by the
     * prediction's color so its residual color is zero; the change is written back to
     * {@code current} (and, for column 0, to the wrap-around entry of {@code upper}).
     *
     * @param width    the image width
     * @param upper    the row above, {@code width + 1} entries
     * @param current  the current row, {@code width + 1} entries
     * @param mode     the predictor mode
     * @param xStart   the first column
     * @param xEnd     the column after the last
     * @param y        the row
     * @param exact    whether transparent pixels keep their color
     * @param out      the destination residuals
     * @param outPos   the index of the first residual
     */
    private static void residuals(int width, int[] upper, int[] current, int mode, int xStart, int xEnd, int y,
                                  boolean exact, int[] out, int outPos) {
        for (var x = xStart; x < xEnd; x++) {
            int predict;
            if (y == 0) {
                predict = x == 0 ? ARGB_BLACK : current[x - 1];
            } else if (x == 0) {
                predict = upper[x];
            } else {
                predict = ScalarLosslessDsp.predict(mode, current[x - 1], upper, x);
            }
            var residual = ScalarLosslessDsp.subPixels(current[x], predict);
            if (!exact && (current[x] & 0xFF000000) == 0) {
                residual &= 0xFF000000;
                current[x] = predict & 0x00FFFFFF;
                if (x == 0 && y != 0) {
                    upper[width] = current[0];
                }
            }
            out[outPos + x - xStart] = residual;
        }
    }

    /**
     * Adds {@code count} occurrences of a residual to the four channel histograms.
     *
     * @param histo  the histograms
     * @param offset the index of the histograms
     * @param argb   the residual
     * @param count  the number of occurrences
     */
    private static void addHisto(int[] histo, int offset, int argb, int count) {
        histo[offset + (argb >>> 24)] += count;
        histo[offset + 256 + ((argb >>> 16) & 0xFF)] += count;
        histo[offset + 512 + ((argb >>> 8) & 0xFF)] += count;
        histo[offset + 768 + (argb & 0xFF)] += count;
    }

    /**
     * Counts a run of residuals in the four channel histograms.
     *
     * @implNote This implementation merges runs of equal residuals into one update per bin,
     * which breaks the store-to-load dependency chains that repeated increments of one bin form
     * in flat areas.
     *
     * @param histo     the histograms
     * @param offset    the index of the histograms
     * @param residuals the residuals
     * @param count     the number of residuals, at least 1
     */
    private static void accumulate(int[] histo, int offset, int[] residuals, int count) {
        var previous = residuals[0];
        var run = 1;
        for (var i = 1; i < count; i++) {
            var residual = residuals[i];
            if (residual == previous) {
                run++;
            } else {
                addHisto(histo, offset, previous, run);
                previous = residual;
                run = 1;
            }
        }
        addHisto(histo, offset, previous, run);
    }

    /**
     * Returns whether every pixel of a range equals a value.
     *
     * @param argb  the image
     * @param from  the index of the first pixel
     * @param to    the index after the last pixel
     * @param value the value
     * @return whether the range is uniform
     */
    private static boolean allEqual(int[] argb, int from, int to, int value) {
        for (var i = from; i < to; i++) {
            if (argb[i] != value) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether a tile and every neighbor its predictions read hold one value: the tile,
     * the column to its left, the row above it from the top-left to the top-right corner, and
     * the top-right neighbor of each row's last pixel (the first pixel of the next row when the
     * tile ends the row).
     *
     * @param argb   the image
     * @param width  the image width
     * @param startX the first column of the tile
     * @param startY the first row of the tile
     * @param maxX   the tile width
     * @param maxY   the tile height
     * @param value  the value
     * @return whether the tile and its context are uniform
     */
    private static boolean isUniformTile(int[] argb, int width, int startX, int startY, int maxX, int maxY, int value) {
        var left = startX > 0 ? 1 : 0;
        var lastRow = startY + maxY - 1;
        for (var y = Math.max(startY - 1, 0); y <= lastRow; y++) {
            var row = y * width;
            if (!allEqual(argb, row + startX - left, row + startX + maxX + (y < lastRow ? 1 : 0), value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether a tile holds a fully transparent pixel.
     *
     * @param argb   the image
     * @param width  the image width
     * @param startX the first column of the tile
     * @param startY the first row of the tile
     * @param maxX   the tile width
     * @param maxY   the tile height
     * @return whether some pixel has alpha 0
     */
    private static boolean hasTransparentPixel(int[] argb, int width, int startX, int startY, int maxX, int maxY) {
        for (var y = startY; y < startY + maxY; y++) {
            var row = y * width + startX;
            for (var x = 0; x < maxX; x++) {
                if ((argb[row + x] & 0xFF000000) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Computes the residual histograms of all modes on one tile of the finest size, adding them
     * to the coarser tiles' histograms that are updated per residual.
     *
     * @implNote This implementation counts each residual in the finest level only and adds the
     * finest histograms to the coarser levels once per tile, which gives the same counts as
     * updating every level per residual. It takes one of three paths with identical results. When the
     * tile and its context hold one value, the residuals are known without computing them.
     * When no pixel is rewritten (exact coding, or no fully transparent pixel in the tile), every
     * residual depends only on the source image, so each row is computed by the batch kernel
     * {@link LosslessDsp#predictorResiduals}. Otherwise the rows are predicted serially from
     * working copies that the transparent pixel cleanup rewrites, as libwebp does.
     *
     * @param width         the image width
     * @param height        the image height
     * @param tileX         the tile column
     * @param tileY         the tile row
     * @param minBits       the finest tile size in bits
     * @param updateUpTo    the coarsest level updated per residual
     * @param allArgb       the histograms of every level and mode
     * @param rows          the two scratch rows, the row above first; swapped as rows advance
     * @param argb          the image
     * @param exact         whether transparent pixels keep their color
     * @param residuals     scratch space for one tile row of residuals
     */
    private static void computeResidualsForTile(int width, int height, int tileX, int tileY, int minBits, int updateUpTo,
                                                int[] allArgb, int[][] rows, int[] argb, boolean exact, int[] residuals) {
        var startX = tileX << minBits;
        var startY = tileY << minBits;
        var tileSize = 1 << minBits;
        var maxY = Math.min(tileSize, height - startY);
        var maxX = Math.min(tileSize, width - startX);
        var value = argb[startY * width + startX];
        if ((exact || (value & 0xFF000000) != 0 || value == 0)
                && isUniformTile(argb, width, startX, startY, maxX, maxY, value)) {
            uniformTileHistograms(startX, startY, maxX, maxY, 0, allArgb, value);
        } else if (exact || !hasTransparentPixel(argb, width, startX, startY, maxX, maxY)) {
            independentTileHistograms(width, startX, startY, maxX, maxY, 0, allArgb, argb, residuals);
        } else {
            serialTileHistograms(width, height, startX, startY, maxX, maxY, 0, allArgb, rows, argb, exact, residuals);
        }
        var size = NUM_PRED_MODES * HISTO_SIZE;
        for (var level = 1; level <= updateUpTo; level++) {
            var offset = level * size;
            for (var i = 0; i < size; i++) {
                allArgb[offset + i] += allArgb[i];
            }
        }
    }

    /**
     * Adds the residual histograms of a tile whose pixels and context all hold one value.
     *
     * <p>Every prediction from uniform neighbors reproduces the value, so every residual is
     * zero except where black is predicted: the image's first pixel under every mode, and every
     * pixel off row 0 and column 0 under mode 0. When not exact, the only transparent value this
     * path accepts is transparent black, whose residuals the cleanup rule leaves unchanged.
     *
     * @param startX     the first column of the tile
     * @param startY     the first row of the tile
     * @param maxX       the tile width
     * @param maxY       the tile height
     * @param updateUpTo the coarsest level updated per residual
     * @param allArgb    the histograms of every level and mode
     * @param value      the value
     */
    private static void uniformTileHistograms(int startX, int startY, int maxX, int maxY, int updateUpTo, int[] allArgb,
                                              int value) {
        var corner = startX == 0 && startY == 0 ? 1 : 0;
        var boundary = (startY == 0 ? maxX - corner : 0) + (startX == 0 ? maxY - corner : 0);
        var interior = maxX * maxY - corner - boundary;
        var black = ScalarLosslessDsp.subPixels(value, ARGB_BLACK);
        for (var mode = 0; mode < NUM_PRED_MODES; mode++) {
            var zeros = boundary + (mode == 0 ? 0 : interior);
            var blacks = corner + (mode == 0 ? interior : 0);
            for (var level = 0; level <= updateUpTo; level++) {
                var offset = (level * NUM_PRED_MODES + mode) * HISTO_SIZE;
                if (zeros != 0) {
                    addHisto(allArgb, offset, 0, zeros);
                }
                if (blacks != 0) {
                    addHisto(allArgb, offset, black, blacks);
                }
            }
        }
    }

    /**
     * Adds the residual histograms of a tile whose pixels are all predicted from the source
     * image.
     *
     * @param width      the image width
     * @param startX     the first column of the tile
     * @param startY     the first row of the tile
     * @param maxX       the tile width
     * @param maxY       the tile height
     * @param updateUpTo the coarsest level updated per residual
     * @param allArgb    the histograms of every level and mode
     * @param argb       the image
     * @param residuals  scratch space for one tile row of residuals
     */
    private static void independentTileHistograms(int width, int startX, int startY, int maxX, int maxY, int updateUpTo,
                                                  int[] allArgb, int[] argb, int[] residuals) {
        for (var mode = 0; mode < NUM_PRED_MODES; mode++) {
            for (var y = startY; y < startY + maxY; y++) {
                var pos = y * width + startX;
                if (y == 0) {
                    for (var x = 0; x < maxX; x++) {
                        var predict = startX + x == 0 ? ARGB_BLACK : argb[pos + x - 1];
                        residuals[x] = ScalarLosslessDsp.subPixels(argb[pos + x], predict);
                    }
                } else {
                    var first = 0;
                    if (startX == 0) {
                        residuals[0] = ScalarLosslessDsp.subPixels(argb[pos], argb[pos - width]);
                        first = 1;
                    }
                    DSP.predictorResiduals(mode, argb, pos + first, argb, pos + first - width, maxX - first, residuals,
                            first);
                }
                for (var level = 0; level <= updateUpTo; level++) {
                    accumulate(allArgb, (level * NUM_PRED_MODES + mode) * HISTO_SIZE, residuals, maxX);
                }
            }
        }
    }

    /**
     * Adds the residual histograms of a tile predicted serially from working copies of its rows,
     * which the transparent pixel cleanup rewrites.
     *
     * @param width      the image width
     * @param height     the image height
     * @param startX     the first column of the tile
     * @param startY     the first row of the tile
     * @param maxX       the tile width
     * @param maxY       the tile height
     * @param updateUpTo the coarsest level updated per residual
     * @param allArgb    the histograms of every level and mode
     * @param rows       the two scratch rows, the row above first; swapped as rows advance
     * @param argb       the image
     * @param exact      whether transparent pixels keep their color
     * @param residuals  scratch space for one tile row of residuals
     */
    private static void serialTileHistograms(int width, int height, int startX, int startY, int maxX, int maxY,
                                             int updateUpTo, int[] allArgb, int[][] rows, int[] argb, boolean exact,
                                             int[] residuals) {
        var haveLeft = startX > 0 ? 1 : 0;
        var contextStartX = startX - haveLeft;
        for (var mode = 0; mode < NUM_PRED_MODES; mode++) {
            var histoOff = mode * HISTO_SIZE;
            if (startY > 0) {
                System.arraycopy(argb, (startY - 1) * width + contextStartX, rows[1], contextStartX, maxX + haveLeft + 1);
            }
            for (var relativeY = 0; relativeY < maxY; relativeY++) {
                var y = startY + relativeY;
                var tmp = rows[0];
                rows[0] = rows[1];
                rows[1] = tmp;
                var upper = rows[0];
                var current = rows[1];
                System.arraycopy(argb, y * width + contextStartX, current, contextStartX,
                        maxX + haveLeft + (y + 1 < height ? 1 : 0));
                residuals(width, upper, current, mode, startX, startX + maxX, y, exact, residuals, 0);
                for (var relativeX = 0; relativeX < maxX; relativeX++) {
                    updateHisto(allArgb, histoOff, residuals[relativeX]);
                }
                for (var level = 1; level <= updateUpTo; level++) {
                    var superOff = (level * NUM_PRED_MODES + mode) * HISTO_SIZE;
                    for (var relativeX = 0; relativeX < maxX; relativeX++) {
                        updateHisto(allArgb, superOff, residuals[relativeX]);
                    }
                }
            }
        }
    }

    /**
     * Chooses the best mode of a tile at one level and accumulates its histogram.
     *
     * @param allArgb            the histograms of every level and mode
     * @param level              the level
     * @param tileX              the tile column at that level
     * @param tileY              the tile row at that level
     * @param tilesPerRow        the number of tiles per row at that level
     * @param allAccumulated     the accumulated histograms of every level
     * @param allModes           the chosen modes of every level
     * @param allPredHistos      the mode counts of every level
     */
    private static void bestPredictorForTile(int[] allArgb, int level, int tileX, int tileY, int tilesPerRow,
                                             int[] allAccumulated, int[][] allModes, int[] allPredHistos) {
        var accOff = level * HISTO_SIZE;
        var modes = allModes[level];
        var leftMode = tileX > 0 ? (modes[tileY * tilesPerRow + tileX - 1] >>> 8) & 0xFF : 0xFF;
        var aboveMode = tileY > 0 ? (modes[(tileY - 1) * tilesPerRow + tileX] >>> 8) & 0xFF : 0xFF;
        var accSums = new int[4];
        var accEntropy = new long[4];
        for (var channel = 0; channel < 4; channel++) {
            var sum = 0;
            long entropy = 0;
            for (int i = accOff + channel * 256, end = i + 256; i < end; i++) {
                var v = allAccumulated[i];
                if (v != 0) {
                    sum += v;
                    entropy += EntropyMath.fastSLog2(v);
                }
            }
            accSums[channel] = sum;
            accEntropy[channel] = entropy;
        }
        var bestDiff = Long.MAX_VALUE;
        var bestMode = 0;
        var bestHistoOff = 0;
        for (var mode = 0; mode < NUM_PRED_MODES; mode++) {
            var histoOff = (level * NUM_PRED_MODES + mode) * HISTO_SIZE;
            var curDiff = predictionCostSpatialHistogram(allAccumulated, accOff, accSums, accEntropy, allArgb, histoOff,
                    mode, leftMode, aboveMode);
            if (curDiff < bestDiff) {
                bestHistoOff = histoOff;
                bestDiff = curDiff;
                bestMode = mode;
            }
        }
        for (var i = 0; i < HISTO_SIZE; i++) {
            allAccumulated[accOff + i] += allArgb[bestHistoOff + i];
        }
        modes[tileY * tilesPerRow + tileX] = ARGB_BLACK | (bestMode << 8);
        ++allPredHistos[level * NUM_PRED_MODES + bestMode];
    }

    /**
     * Picks the predictor mode of every tile and the tile size.
     *
     * @param width    the image width
     * @param height   the image height
     * @param minBits  the finest tile size in bits
     * @param maxBits  the coarsest tile size in bits
     * @param argb     the image
     * @param exact    whether transparent pixels keep their color
     * @param bestBits the destination of the chosen tile size in bits
     * @return the modes of the chosen size, one pixel per tile
     */
    private static int[] bestPredictorsAndSubSampling(int width, int height, int minBits, int maxBits, int[] argb,
                                                       boolean exact, int[] bestBits) {
        var tilesPerRow = Vp8lDecoder.subSampleSize(width, minBits);
        var tilesPerCol = Vp8lDecoder.subSampleSize(height, minBits);
        var maxSubsamplingIndex = maxBits - minBits;
        var allArgb = new int[(maxSubsamplingIndex + 1) * NUM_PRED_MODES * HISTO_SIZE];
        var allAccumulated = new int[(maxSubsamplingIndex + 1) * HISTO_SIZE];
        var allPredHistos = new int[(maxSubsamplingIndex + 1) * NUM_PRED_MODES];
        var allModes = new int[maxSubsamplingIndex + 1][];
        for (var level = 0; level <= maxSubsamplingIndex; level++) {
            allModes[level] = new int[Vp8lDecoder.subSampleSize(width, minBits + level) * Vp8lDecoder.subSampleSize(height, minBits + level)];
        }
        var maxTileSize = 1 << maxSubsamplingIndex;
        var updateUpTo = Math.max(Math.min(4, maxBits), minBits) - minBits;
        var rows = new int[][]{new int[width + 1], new int[width + 1]};
        var residuals = new int[1 << MAX_TRANSFORM_BITS];
        var localTileX = 0;
        var localTileY = 0;
        var maxTileX = 0;
        var maxTileY = 0;
        var tileX = 0;
        var tileY = 0;
        while (tileY < tilesPerCol) {
            computeResidualsForTile(width, height, tileX, tileY, minBits, updateUpTo, allArgb, rows, argb, exact, residuals);
            var level = 0;
            while (true) {
                var superTileX = tileX >> level;
                var superTileY = tileY >> level;
                var superTilesPerRow = Vp8lDecoder.subSampleSize(width, minBits + level);
                bestPredictorForTile(allArgb, level, superTileX, superTileY, superTilesPerRow, allAccumulated, allModes, allPredHistos);
                if (level == maxSubsamplingIndex) {
                    break;
                }
                ++level;
                if (level > updateUpTo && level <= maxSubsamplingIndex) {
                    var from = (level - 1) * NUM_PRED_MODES * HISTO_SIZE;
                    var to = level * NUM_PRED_MODES * HISTO_SIZE;
                    for (var i = 0; i < HISTO_SIZE * NUM_PRED_MODES; i++) {
                        allArgb[to + i] += allArgb[from + i];
                    }
                }
                if (!((tileX == tilesPerRow - 1 || (localTileX + 1) % (1 << level) == 0)
                        && (tileY == tilesPerCol - 1 || (localTileY + 1) % (1 << level) == 0))) {
                    --level;
                    break;
                }
            }
            Arrays.fill(allArgb, 0, HISTO_SIZE * NUM_PRED_MODES * (level + 1), 0);
            if (level == maxSubsamplingIndex) {
                if (tileX == tilesPerRow - 1) {
                    maxTileX = 0;
                    ++maxTileY;
                } else {
                    ++maxTileX;
                }
                localTileX = 0;
                localTileY = 0;
            } else {
                var coordX = localTileX >> level;
                var coordY = localTileY >> level;
                if (tileX == tilesPerRow - 1 && coordX % 2 == 0) {
                    ++coordY;
                } else if (coordX % 2 == 0) {
                    ++coordX;
                } else {
                    ++coordY;
                    --coordX;
                }
                localTileX = coordX << level;
                localTileY = coordY << level;
            }
            tileX = maxTileX * maxTileSize + localTileX;
            tileY = maxTileY * maxTileSize + localTileY;
        }
        var bestCost = Long.MAX_VALUE;
        int[] bestModes = null;
        for (var level = 0; level <= maxSubsamplingIndex; level++) {
            var cost = EntropyMath.shannonEntropy(allPredHistos, level * NUM_PRED_MODES, NUM_PRED_MODES);
            for (var plane = 0; plane < 4; plane++) {
                cost += EntropyMath.shannonEntropy(allAccumulated, level * HISTO_SIZE + plane * 256, 256);
            }
            if (cost < bestCost) {
                bestCost = cost;
                bestBits[0] = minBits + level;
                bestModes = allModes[level];
            }
        }
        optimizeSampling(bestModes, width, height, bestBits[0], MAX_TRANSFORM_BITS, bestBits);
        return bestModes;
    }

    /**
     * Enlarges the tile size of a transform image when groups of tiles hold identical values,
     * subsampling the image in place.
     *
     * @param image      the transform image
     * @param fullWidth  the image width
     * @param fullHeight the image height
     * @param bits       the current tile size in bits
     * @param maxBits    the largest tile size in bits
     * @param bestBits   the destination of the resulting tile size in bits
     */
    static void optimizeSampling(int[] image, int fullWidth, int fullHeight, int bits, int maxBits, int[] bestBits) {
        var width = Vp8lDecoder.subSampleSize(fullWidth, bits);
        var height = Vp8lDecoder.subSampleSize(fullHeight, bits);
        var best = bits;
        bestBits[0] = bits;
        while (best < maxBits) {
            var newSquareSize = 1 << (best + 1 - bits);
            var squareSize = 1 << (best - bits);
            var good = true;
            for (var y = 0; y + squareSize < height; y += newSquareSize) {
                if (Arrays.mismatch(image, y * width, y * width + width, image, (y + squareSize) * width, (y + squareSize) * width + width) >= 0) {
                    good = false;
                    break;
                }
            }
            if (good) {
                ++best;
            } else {
                break;
            }
        }
        if (best == bits) {
            return;
        }
        while (best > bits) {
            var good = true;
            var squareSize = 1 << (best - bits);
            for (var y = 0; good && y < height; y++) {
                for (var x = 0; good && x < width; x += squareSize) {
                    for (var i = x + 1; i < Math.min(x + squareSize, width); i++) {
                        if (image[y * width + i] != image[y * width + x]) {
                            good = false;
                            break;
                        }
                    }
                }
            }
            if (good) {
                break;
            }
            --best;
        }
        if (best == bits) {
            return;
        }
        var oldWidth = width;
        var squareSize = 1 << (best - bits);
        width = Vp8lDecoder.subSampleSize(fullWidth, best);
        height = Vp8lDecoder.subSampleSize(fullHeight, best);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                image[y * width + x] = image[squareSize * (y * oldWidth + x)];
            }
        }
        bestBits[0] = best;
    }

    /**
     * Replaces an image by its predictor residuals, choosing the mode of every tile.
     *
     * @param width     the image width
     * @param height    the image height
     * @param minBits   the finest tile size tried
     * @param maxBits   the coarsest tile size tried
     * @param lowEffort whether to use mode 11 everywhere at the coarsest size
     * @param argb      the image, replaced by the residuals
     * @param image     the destination of the mode image, one pixel per tile
     * @param exact     whether transparent pixels keep their color
     * @return the chosen tile size in bits
     */
    static int residualImage(int width, int height, int minBits, int maxBits, boolean lowEffort, int[] argb,
                             int[] image, boolean exact) {
        int bits;
        if (lowEffort) {
            var count = Vp8lDecoder.subSampleSize(width, maxBits) * Vp8lDecoder.subSampleSize(height, maxBits);
            for (var i = 0; i < count; i++) {
                image[i] = ARGB_BLACK | (PRED_LOW_EFFORT << 8);
            }
            bits = maxBits;
        } else {
            var bestBits = new int[1];
            var modes = bestPredictorsAndSubSampling(width, height, minBits, maxBits, argb, exact, bestBits);
            bits = bestBits[0];
            var count = Vp8lDecoder.subSampleSize(width, bits) * Vp8lDecoder.subSampleSize(height, bits);
            System.arraycopy(modes, 0, image, 0, count);
        }
        copyImageWithPrediction(width, height, bits, image, argb, lowEffort, exact);
        return bits;
    }

    /**
     * Replaces an image by its residuals under a given mode image.
     *
     * @param width     the image width
     * @param height    the image height
     * @param bits      the tile size in bits
     * @param modes     the mode image
     * @param argb      the image, replaced by the residuals
     * @param lowEffort whether every tile uses mode 11 with exact residuals
     * @param exact     whether transparent pixels keep their color
     */
    private static void copyImageWithPrediction(int width, int height, int bits, int[] modes, int[] argb,
                                                boolean lowEffort, boolean exact) {
        var tilesPerRow = Vp8lDecoder.subSampleSize(width, bits);
        var upper = new int[width + 1];
        var current = new int[width + 1];
        for (var y = 0; y < height; y++) {
            var tmp = upper;
            upper = current;
            current = tmp;
            System.arraycopy(argb, y * width, current, 0, width + (y + 1 < height ? 1 : 0));
            if (lowEffort) {
                predictBatch(PRED_LOW_EFFORT, 0, y, width, current, upper, argb, y * width);
            } else {
                for (var x = 0; x < width; ) {
                    var mode = (modes[(y >> bits) * tilesPerRow + (x >> bits)] >>> 8) & 0xFF;
                    var xEnd = Math.min(x + (1 << bits), width);
                    residuals(width, upper, current, mode, x, xEnd, y, exact, argb, y * width + x);
                    x = xEnd;
                }
            }
        }
    }

    /**
     * Computes exact residuals of a run of pixels.
     *
     * @param mode      the predictor mode
     * @param xStart    the first column
     * @param y         the row
     * @param numPixels the number of pixels
     * @param current   the current row
     * @param upper     the row above
     * @param out       the destination
     * @param outPos    the index of the first residual
     */
    private static void predictBatch(int mode, int xStart, int y, int numPixels, int[] current, int[] upper, int[] out, int outPos) {
        if (xStart == 0) {
            var predict = y == 0 ? ARGB_BLACK : upper[0];
            out[outPos++] = ScalarLosslessDsp.subPixels(current[0], predict);
            ++xStart;
            --numPixels;
        }
        for (var i = 0; i < numPixels; i++) {
            var x = xStart + i;
            var predict = y == 0 ? current[x - 1] : ScalarLosslessDsp.predict(mode, current[x - 1], upper, x);
            out[outPos + i] = ScalarLosslessDsp.subPixels(current[x], predict);
        }
    }

    /**
     * Returns the histogram cost of a cross-color choice.
     *
     * @param accumulated the accumulated histogram of the channel
     * @param counts      the histogram of the tile under the choice
     * @return the cost, lower is better
     */
    private static long predictionCostCrossColor(int[] accumulated, int[] counts) {
        return DSP.combinedShannonEntropy(counts, 0, accumulated, 0) + predictionCostBias(counts, 0, 3, 240);
    }

    /**
     * Returns the cost of a green-to-red multiplier on a tile.
     *
     * @param argb        the image
     * @param pos         the index of the tile's first pixel
     * @param stride      the image width
     * @param tileWidth   the tile width
     * @param tileHeight  the tile height
     * @param prevX       the multipliers of the tile to the left
     * @param prevY       the multipliers of the tile above
     * @param greenToRed  the multiplier
     * @param accumulated the accumulated red histogram
     * @param histo       scratch histogram
     * @return the cost
     */
    private static long costCrossColorRed(int[] argb, int pos, int stride, int tileWidth, int tileHeight, int prevX, int prevY,
                                          int greenToRed, int[] accumulated, int[] histo) {
        Arrays.fill(histo, 0);
        DSP.collectColorRedTransforms(argb, pos, stride, tileWidth, tileHeight, greenToRed, histo);
        var curDiff = predictionCostCrossColor(accumulated, histo);
        if ((greenToRed & 0xFF) == (prevX & 0xFF)) {
            curDiff -= 3L << EntropyMath.LOG_2_PRECISION_BITS;
        }
        if ((greenToRed & 0xFF) == (prevY & 0xFF)) {
            curDiff -= 3L << EntropyMath.LOG_2_PRECISION_BITS;
        }
        if (greenToRed == 0) {
            curDiff -= 3L << EntropyMath.LOG_2_PRECISION_BITS;
        }
        return curDiff;
    }

    /**
     * Returns the cost of green-to-blue and red-to-blue multipliers on a tile.
     *
     * @param argb        the image
     * @param pos         the index of the tile's first pixel
     * @param stride      the image width
     * @param tileWidth   the tile width
     * @param tileHeight  the tile height
     * @param prevX       the multipliers of the tile to the left
     * @param prevY       the multipliers of the tile above
     * @param greenToBlue the green-to-blue multiplier
     * @param redToBlue   the red-to-blue multiplier
     * @param accumulated the accumulated blue histogram
     * @param histo       scratch histogram
     * @return the cost
     */
    private static long costCrossColorBlue(int[] argb, int pos, int stride, int tileWidth, int tileHeight, int prevX, int prevY,
                                           int greenToBlue, int redToBlue, int[] accumulated, int[] histo) {
        Arrays.fill(histo, 0);
        DSP.collectColorBlueTransforms(argb, pos, stride, tileWidth, tileHeight, greenToBlue, redToBlue, histo);
        var curDiff = predictionCostCrossColor(accumulated, histo);
        var bonus = 3L << EntropyMath.LOG_2_PRECISION_BITS;
        if ((greenToBlue & 0xFF) == ((prevX >>> 8) & 0xFF)) {
            curDiff -= bonus;
        }
        if ((greenToBlue & 0xFF) == ((prevY >>> 8) & 0xFF)) {
            curDiff -= bonus;
        }
        if ((redToBlue & 0xFF) == ((prevX >>> 16) & 0xFF)) {
            curDiff -= bonus;
        }
        if ((redToBlue & 0xFF) == ((prevY >>> 16) & 0xFF)) {
            curDiff -= bonus;
        }
        if (greenToBlue == 0) {
            curDiff -= bonus;
        }
        if (redToBlue == 0) {
            curDiff -= bonus;
        }
        return curDiff;
    }

    /**
     * Chooses the cross-color multipliers of every tile and applies the forward transform.
     *
     * @param width    the image width
     * @param height   the image height
     * @param bits     the tile size in bits
     * @param quality  the encoder quality
     * @param argb     the image, transformed in place
     * @param image    the destination of the multiplier image, one pixel per tile
     * @return the tile size in bits after {@link #optimizeSampling}
     */
    static int colorSpaceTransform(int width, int height, int bits, int quality, int[] argb, int[] image) {
        var maxTileSize = 1 << bits;
        var tileXSize = Vp8lDecoder.subSampleSize(width, bits);
        var tileYSize = Vp8lDecoder.subSampleSize(height, bits);
        var accumulatedRed = new int[256];
        var accumulatedBlue = new int[256];
        var histo = new int[256];
        var redKeys = new int[16];
        var redCosts = new long[16];
        var blueGreens = new int[64];
        var blueReds = new int[64];
        var blueCosts = new long[64];
        var prevX = 0;
        var prevY = 0;
        var dsp = LosslessDsp.INSTANCE;
        for (var tileY = 0; tileY < tileYSize; tileY++) {
            for (var tileX = 0; tileX < tileXSize; tileX++) {
                var tileXOffset = tileX * maxTileSize;
                var tileYOffset = tileY * maxTileSize;
                var allXMax = Math.min(tileXOffset + maxTileSize, width);
                var allYMax = Math.min(tileYOffset + maxTileSize, height);
                var offset = tileY * tileXSize + tileX;
                if (tileY != 0) {
                    prevY = image[offset - tileXSize];
                }
                var tileWidth = allXMax - tileXOffset;
                var tileHeight = allYMax - tileYOffset;
                var tilePos = tileYOffset * width + tileXOffset;
                var kMaxIters = 4 + ((7 * quality) >> 8);
                var greenToRedBest = 0;
                var redSeen = 1;
                redKeys[0] = 0;
                var bestDiff = costCrossColorRed(argb, tilePos, width, tileWidth, tileHeight, prevX, prevY, 0, accumulatedRed, histo);
                redCosts[0] = bestDiff;
                for (var iter = 0; iter < kMaxIters; iter++) {
                    var delta = 32 >> iter;
                    for (var off = -delta; off <= delta; off += 2 * delta) {
                        var cur = off + greenToRedBest;
                        var known = -1;
                        for (var j = 0; j < redSeen; j++) {
                            if (redKeys[j] == cur) {
                                known = j;
                                break;
                            }
                        }
                        long curDiff;
                        if (known >= 0) {
                            curDiff = redCosts[known];
                        } else {
                            curDiff = costCrossColorRed(argb, tilePos, width, tileWidth, tileHeight, prevX, prevY, cur, accumulatedRed, histo);
                            redKeys[redSeen] = cur;
                            redCosts[redSeen++] = curDiff;
                        }
                        if (curDiff < bestDiff) {
                            bestDiff = curDiff;
                            greenToRedBest = cur;
                        }
                    }
                }
                var greenToBlueBest = 0;
                var redToBlueBest = 0;
                var iters = quality < 25 ? 1 : quality > 50 ? 7 : 4;
                var blueSeen = 1;
                blueGreens[0] = 0;
                blueReds[0] = 0;
                bestDiff = costCrossColorBlue(argb, tilePos, width, tileWidth, tileHeight, prevX, prevY, 0, 0, accumulatedBlue, histo);
                blueCosts[0] = bestDiff;
                for (var iter = 0; iter < iters; iter++) {
                    var delta = BLUE_DELTAS[iter];
                    for (var axis = 0; axis < 8; axis++) {
                        var g2b = BLUE_OFFSETS[axis][0] * delta + greenToBlueBest;
                        var r2b = BLUE_OFFSETS[axis][1] * delta + redToBlueBest;
                        var known = -1;
                        for (var j = 0; j < blueSeen; j++) {
                            if (blueGreens[j] == g2b && blueReds[j] == r2b) {
                                known = j;
                                break;
                            }
                        }
                        long curDiff;
                        if (known >= 0) {
                            curDiff = blueCosts[known];
                        } else {
                            curDiff = costCrossColorBlue(argb, tilePos, width, tileWidth, tileHeight, prevX, prevY, g2b, r2b, accumulatedBlue, histo);
                            blueGreens[blueSeen] = g2b;
                            blueReds[blueSeen] = r2b;
                            blueCosts[blueSeen++] = curDiff;
                        }
                        if (curDiff < bestDiff) {
                            bestDiff = curDiff;
                            greenToBlueBest = g2b;
                            redToBlueBest = r2b;
                        }
                        if (quality < 25 && iter == 4) {
                            break;
                        }
                    }
                    if (delta == 2 && greenToBlueBest == 0 && redToBlueBest == 0) {
                        break;
                    }
                }
                prevX = ((redToBlueBest & 0xFF) << 16) | ((greenToBlueBest & 0xFF) << 8) | (greenToRedBest & 0xFF);
                image[offset] = 0xFF000000 | prevX;
                for (var y = 0; y < tileHeight; y++) {
                    dsp.transformColor(prevX, argb, tilePos + y * width, tileWidth);
                }
                for (var y = tileYOffset; y < allYMax; y++) {
                    var ix = y * width + tileXOffset;
                    var ixEnd = ix + allXMax - tileXOffset;
                    for (; ix < ixEnd; ix++) {
                        var pix = argb[ix];
                        if (ix >= 2 && pix == argb[ix - 2] && pix == argb[ix - 1]) {
                            continue;
                        }
                        if (ix >= width + 2 && argb[ix - 2] == argb[ix - width - 2] && argb[ix - 1] == argb[ix - width - 1]
                                && pix == argb[ix - width]) {
                            continue;
                        }
                        ++accumulatedRed[(pix >>> 16) & 0xFF];
                        ++accumulatedBlue[pix & 0xFF];
                    }
                }
            }
        }
        var bestBits = new int[1];
        optimizeSampling(image, width, height, bits, MAX_TRANSFORM_BITS, bestBits);
        return bestBits[0];
    }

    /**
     * The search directions of the blue multipliers: (green-to-blue, red-to-blue) steps.
     */
    private static final int[][] BLUE_OFFSETS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}, {-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

    /**
     * The step size of each iteration of the blue multiplier search.
     */
    private static final int[] BLUE_DELTAS = {16, 16, 8, 4, 2, 2, 2};
}
