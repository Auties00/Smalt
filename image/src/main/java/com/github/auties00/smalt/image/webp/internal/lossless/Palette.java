package com.github.auties00.smalt.image.webp.internal.lossless;

import java.util.Arrays;

/**
 * Extracts, orders and applies the color palette of the lossless color indexing transform.
 *
 * @implNote This implementation follows libwebp's {@code src/utils/palette.c} and
 * {@code ApplyPalette} of {@code src/enc/vp8l_enc.c} (BSD-3): colors are collected with an
 * open-addressing hash, sorted as unsigned values, and optionally reordered to minimize the
 * deltas of consecutive entries or with the modified Zeng co-occurrence ordering.
 */
final class Palette {
    /**
     * The largest palette.
     */
    static final int MAX_PALETTE_SIZE = 256;

    /**
     * Sorting by unsigned ARGB value, with a zero entry moved last for large palettes.
     */
    static final int SORTED_DEFAULT = 0;

    /**
     * Greedy ordering minimizing consecutive deltas.
     */
    static final int MINIMIZE_DELTA = 1;

    /**
     * The modified Zeng ordering by co-occurrence of neighboring indices.
     */
    static final int MODIFIED_ZENG = 2;

    /**
     * Prevents instantiation.
     */
    private Palette() {
        throw new AssertionError();
    }

    /**
     * Collects the distinct colors of an image.
     *
     * @param argb    the pixels
     * @param width   the image width
     * @param height  the image height
     * @param palette the destination of the sorted colors, at least 256 entries
     * @return the number of colors, or 257 when there are more than 256
     */
    static int colorPalette(int[] argb, int width, int height, int[] palette) {
        var hashSize = MAX_PALETTE_SIZE * 4;
        var inUse = new boolean[hashSize];
        var colors = new int[hashSize];
        var numColors = 0;
        var lastPix = ~argb[0];
        for (var i = 0; i < width * height; i++) {
            var pix = argb[i];
            if (pix == lastPix) {
                continue;
            }
            lastPix = pix;
            var key = ColorCache.hash(pix, 22);
            while (true) {
                if (!inUse[key]) {
                    colors[key] = pix;
                    inUse[key] = true;
                    ++numColors;
                    if (numColors > MAX_PALETTE_SIZE) {
                        return MAX_PALETTE_SIZE + 1;
                    }
                    break;
                } else if (colors[key] == pix) {
                    break;
                } else {
                    key = (key + 1) & (hashSize - 1);
                }
            }
        }
        numColors = 0;
        for (var i = 0; i < hashSize; i++) {
            if (inUse[i]) {
                palette[numColors++] = colors[i];
            }
        }
        sortUnsigned(palette, numColors);
        return numColors;
    }

    /**
     * Sorts the first entries of an array as unsigned integers.
     *
     * @param values the array
     * @param count  the number of entries
     */
    private static void sortUnsigned(int[] values, int count) {
        for (var i = 0; i < count; i++) {
            values[i] ^= Integer.MIN_VALUE;
        }
        Arrays.sort(values, 0, count);
        for (var i = 0; i < count; i++) {
            values[i] ^= Integer.MIN_VALUE;
        }
    }

    /**
     * Finds a color in a sorted palette.
     *
     * @param sorted    the palette sorted as unsigned values
     * @param color     the color, which must be present
     * @param numColors the palette size
     * @return the index of the color
     */
    static int searchColor(int[] sorted, int color, int numColors) {
        var low = 0;
        var hi = numColors;
        if (sorted[low] == color) {
            return low;
        }
        while (true) {
            var mid = (low + hi) >>> 1;
            if (sorted[mid] == color) {
                return mid;
            } else if (Integer.compareUnsigned(sorted[mid], color) < 0) {
                low = mid;
            } else {
                hi = mid;
            }
            if (hi - low <= 1 && sorted[low] != color && sorted[hi < numColors ? hi : low] != color) {
                return 0;
            }
        }
    }

    /**
     * Builds the map from sorted positions to palette indices.
     *
     * @param palette   the palette
     * @param numColors the palette size
     * @param sorted    the destination of the palette sorted as unsigned values
     * @param idxMap    the destination map from sorted position to palette index
     */
    static void prepareMapToPalette(int[] palette, int numColors, int[] sorted, int[] idxMap) {
        System.arraycopy(palette, 0, sorted, 0, numColors);
        sortUnsigned(sorted, numColors);
        for (var i = 0; i < numColors; i++) {
            idxMap[searchColor(sorted, palette[i], numColors)] = i;
        }
    }

    /**
     * Orders a palette.
     *
     * @param method        {@link #SORTED_DEFAULT}, {@link #MINIMIZE_DELTA} or
     *                      {@link #MODIFIED_ZENG}
     * @param argb          the pixels, used by the co-occurrence ordering
     * @param width         the image width
     * @param height        the image height
     * @param paletteSorted the palette sorted as unsigned values
     * @param numColors     the palette size
     * @param palette       the destination
     */
    static void sort(int method, int[] argb, int width, int height, int[] paletteSorted, int numColors, int[] palette) {
        switch (method) {
            case SORTED_DEFAULT -> {
                if (paletteSorted[0] == 0 && numColors > 17) {
                    System.arraycopy(paletteSorted, 1, palette, 0, numColors - 1);
                    palette[numColors - 1] = 0;
                } else {
                    System.arraycopy(paletteSorted, 0, palette, 0, numColors);
                }
            }
            case MINIMIZE_DELTA -> sortMinimizeDeltas(paletteSorted, numColors, palette);
            default -> sortModifiedZeng(argb, width, height, paletteSorted, numColors, palette);
        }
    }

    /**
     * Returns the distance of one channel difference, treating it as signed.
     *
     * @param v the channel difference modulo 256
     * @return the absolute signed difference
     */
    private static int componentDistance(int v) {
        return v <= 128 ? v : 256 - v;
    }

    /**
     * Returns the weighted distance between two colors, favoring RGB over alpha.
     *
     * @param col1 the first color
     * @param col2 the second color
     * @return the distance
     */
    private static int colorDistance(int col1, int col2) {
        var diff = ScalarLosslessDsp.subPixels(col1, col2);
        var score = componentDistance(diff & 0xFF) + componentDistance((diff >>> 8) & 0xFF)
                + componentDistance((diff >>> 16) & 0xFF);
        score *= 9;
        score += componentDistance(diff >>> 24);
        return score;
    }

    /**
     * Returns whether the deltas of a palette change sign in some channel.
     *
     * @param palette   the palette
     * @param numColors the palette size
     * @return whether reordering may help
     */
    private static boolean hasNonMonotonousDeltas(int[] palette, int numColors) {
        var predict = 0;
        var signFound = 0;
        for (var i = 0; i < numColors; i++) {
            var diff = ScalarLosslessDsp.subPixels(palette[i], predict);
            var rd = (diff >>> 16) & 0xFF;
            var gd = (diff >>> 8) & 0xFF;
            var bd = diff & 0xFF;
            if (rd != 0) {
                signFound |= rd < 0x80 ? 1 : 2;
            }
            if (gd != 0) {
                signFound |= gd < 0x80 ? 8 : 16;
            }
            if (bd != 0) {
                signFound |= bd < 0x80 ? 64 : 128;
            }
            predict = palette[i];
        }
        return (signFound & (signFound << 1)) != 0;
    }

    /**
     * Orders a palette greedily so that each color is closest to the previous one.
     *
     * @param paletteSorted the sorted palette
     * @param numColors     the palette size
     * @param palette       the destination
     */
    private static void sortMinimizeDeltas(int[] paletteSorted, int numColors, int[] palette) {
        System.arraycopy(paletteSorted, 0, palette, 0, numColors);
        if (!hasNonMonotonousDeltas(paletteSorted, numColors)) {
            return;
        }
        if (numColors > 17 && palette[0] == 0) {
            --numColors;
            var t = palette[numColors];
            palette[numColors] = palette[0];
            palette[0] = t;
        }
        var predict = 0;
        for (var i = 0; i < numColors; i++) {
            var bestIx = i;
            var bestScore = -1L;
            for (var k = i; k < numColors; k++) {
                var curScore = colorDistance(palette[k], predict) & 0xFFFFFFFFL;
                if (bestScore < 0 || bestScore > curScore) {
                    bestScore = curScore;
                    bestIx = k;
                }
            }
            var t = palette[bestIx];
            palette[bestIx] = palette[i];
            palette[i] = t;
            predict = palette[i];
        }
    }

    /**
     * Orders a palette by the modified Zeng method: indices that often touch become neighbors.
     *
     * @param argb          the pixels
     * @param width         the image width
     * @param height        the image height
     * @param paletteIn     the sorted palette
     * @param numColors     the palette size
     * @param palette       the destination
     */
    private static void sortModifiedZeng(int[] argb, int width, int height, int[] paletteIn, int numColors, int[] palette) {
        if (numColors <= 1) {
            if (numColors == 1) {
                palette[0] = paletteIn[0];
            }
            return;
        }
        var cooccurrence = new int[numColors * numColors];
        var idxMap = new int[MAX_PALETTE_SIZE];
        var sorted = new int[MAX_PALETTE_SIZE];
        prepareMapToPalette(paletteIn, numColors, sorted, idxMap);
        var lineTop = new int[width];
        var lineCurrent = new int[width];
        var prevPix = ~argb[0];
        var prevIdx = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var pix = argb[y * width + x];
                if (pix != prevPix) {
                    prevIdx = idxMap[searchColor(sorted, pix, numColors)];
                    prevPix = pix;
                }
                lineCurrent[x] = prevIdx;
                if (x > 0 && prevIdx != lineCurrent[x - 1]) {
                    var leftIdx = lineCurrent[x - 1];
                    ++cooccurrence[prevIdx * numColors + leftIdx];
                    ++cooccurrence[leftIdx * numColors + prevIdx];
                }
                if (y > 0 && prevIdx != lineTop[x]) {
                    var topIdx = lineTop[x];
                    ++cooccurrence[prevIdx * numColors + topIdx];
                    ++cooccurrence[topIdx * numColors + prevIdx];
                }
            }
            var t = lineTop;
            lineTop = lineCurrent;
            lineCurrent = t;
        }
        var remapping = new int[MAX_PALETTE_SIZE];
        var bestSum = 0L;
        var c1 = 0;
        for (var i = 0; i < numColors; i++) {
            var sum = 0L;
            for (var j = 0; j < numColors; j++) {
                sum += cooccurrence[i * numColors + j];
            }
            if (sum > bestSum) {
                bestSum = sum;
                c1 = i;
            }
        }
        var c2 = 0;
        var bestCooccurrence = 0;
        for (var i = 0; i < numColors; i++) {
            if (cooccurrence[c1 * numColors + i] > bestCooccurrence) {
                bestCooccurrence = cooccurrence[c1 * numColors + i];
                c2 = i;
            }
        }
        remapping[0] = c1;
        remapping[1] = c2;
        var first = 0;
        var last = 1;
        var numSums = numColors - 2;
        if (numSums > 0) {
            var sumIndex = new int[MAX_PALETTE_SIZE];
            var sumValue = new long[MAX_PALETTE_SIZE];
            var best = 0;
            sumIndex[0] = 0;
            sumValue[0] = 0;
            for (int i = 0, j = 0; i < numColors; i++) {
                if (i == remapping[0] || i == remapping[1]) {
                    continue;
                }
                sumIndex[j] = i;
                sumValue[j] = (long) cooccurrence[i * numColors + remapping[0]] + cooccurrence[i * numColors + remapping[1]];
                if (sumValue[j] > sumValue[best]) {
                    best = j;
                }
                ++j;
            }
            while (numSums > 0) {
                var bestIndex = sumIndex[best];
                long delta = 0;
                var n = numColors - numSums;
                for (int ind = first, j = 0; (ind + j) % numColors != last + 1; j++) {
                    var lj = remapping[(ind + j) % numColors];
                    delta += (long) (n - 1 - 2 * j) * cooccurrence[bestIndex * numColors + lj];
                }
                if (delta > 0) {
                    first = first == 0 ? numColors - 1 : first - 1;
                    remapping[first] = bestIndex;
                } else {
                    ++last;
                    remapping[last] = bestIndex;
                }
                sumIndex[best] = sumIndex[numSums - 1];
                sumValue[best] = sumValue[numSums - 1];
                --numSums;
                best = 0;
                for (var i = 0; i < numSums; i++) {
                    sumValue[i] += cooccurrence[bestIndex * numColors + sumIndex[i]];
                    if (sumValue[i] > sumValue[best]) {
                        best = i;
                    }
                }
            }
        }
        for (var i = 0; i < numColors; i++) {
            palette[i] = paletteIn[remapping[(first + i) % numColors]];
        }
    }

    /**
     * Packs 1, 2, 4 or 8 palette indices of a row into the green channel of each pixel.
     *
     * @param row    the indices of one row
     * @param width  the row length
     * @param xbits  the bundling width in bits: 0 (no packing) to 3 (8 per pixel)
     * @param dst    the destination
     * @param dstPos the index of the first packed pixel
     */
    static void bundleColorMap(int[] row, int width, int xbits, int[] dst, int dstPos) {
        if (xbits > 0) {
            var bitDepth = 1 << (3 - xbits);
            var mask = (1 << xbits) - 1;
            var code = 0xFF000000;
            for (var x = 0; x < width; x++) {
                var xsub = x & mask;
                if (xsub == 0) {
                    code = 0xFF000000;
                }
                code |= row[x] << (8 + bitDepth * xsub);
                dst[dstPos + (x >> xbits)] = code;
            }
        } else {
            for (var x = 0; x < width; x++) {
                dst[dstPos + x] = 0xFF000000 | (row[x] << 8);
            }
        }
    }

    /**
     * Replaces every pixel by its palette index and bundles the indices.
     *
     * @param src         the pixels, all of which appear in the palette
     * @param width       the image width
     * @param height      the image height
     * @param dst         the destination, {@code ceil(width / 2^xbits)} pixels per row
     * @param palette     the palette
     * @param paletteSize the palette size
     * @param xbits       the bundling width in bits
     */
    static void apply(int[] src, int width, int height, int[] dst, int[] palette, int paletteSize, int xbits) {
        var dstStride = Vp8lDecoder.subSampleSize(width, xbits);
        var tmpRow = new int[width];
        var sorted = new int[MAX_PALETTE_SIZE];
        var idxMap = new int[MAX_PALETTE_SIZE];
        prepareMapToPalette(palette, paletteSize, sorted, idxMap);
        var prevPix = palette[0];
        var prevIdx = 0;
        for (var y = 0; y < height; y++) {
            var row = y * width;
            for (var x = 0; x < width; x++) {
                var pix = src[row + x];
                if (pix != prevPix) {
                    prevIdx = idxMap[searchColor(sorted, pix, paletteSize)];
                    prevPix = pix;
                }
                tmpRow[x] = prevIdx;
            }
            bundleColorMap(tmpRow, width, xbits, dst, y * dstStride);
        }
    }
}
