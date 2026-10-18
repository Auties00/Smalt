package com.github.auties00.smalt.image.webp.internal.alpha;

import com.github.auties00.smalt.image.webp.internal.lossless.Vp8lEncoder;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.concurrent.Callable;

/**
 * Encodes an alpha plane as the payload of a WebP {@code ALPH} chunk.
 *
 * <p>Below quality 100 the plane is first quantized to fewer levels (from 2 at quality 0 to 16
 * at quality 70 and 256 at quality 100); the header then flags the pre-processing. The plane is
 * filtered with one or several of the four prediction filters and coded losslessly as the green
 * channel of a headerless VP8L stream; the smallest candidate wins, and the raw plane is stored
 * instead when compression does not help.
 *
 * @implNote This implementation follows libwebp's {@code src/enc/alpha_enc.c} and
 * {@code src/utils/quant_levels_utils.c} (BSD-3): the "fast" filter choice estimates the best
 * filter from a sparse sample (and also tries no filter when the plane has many levels or the
 * effort is above 3), the "best" choice tries all four, and the VP8L stream is coded with exact
 * pixels, the encoder method as effort and a quality of {@code 8 * effort} (100 at effort 6 when
 * the alpha quality is 100). The candidate filters are coded concurrently through
 * {@link ParallelBands}; the smallest wins, the first in filter order on a tie, as when they are
 * coded one after the other.
 */
public final class AlphaEncoder {
    /**
     * No filtering.
     */
    public static final int FILTER_NONE = 0;

    /**
     * A quick estimate of the best filter.
     */
    public static final int FILTER_FAST = 1;

    /**
     * Every filter, keeping the smallest result.
     */
    public static final int FILTER_BEST = 2;

    /**
     * Prevents instantiation.
     */
    private AlphaEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes an alpha plane.
     *
     * @param alpha       the plane, {@code width * height} samples; not modified
     * @param width       the plane width
     * @param height      the plane height
     * @param quality     the alpha quality, from 0 to 100; 100 is lossless
     * @param filterMode  {@link #FILTER_NONE}, {@link #FILTER_FAST} or {@link #FILTER_BEST}
     * @param effortLevel the encoder method, from 0 to 6
     * @return the {@code ALPH} payload
     */
    public static byte[] encode(byte[] alpha, int width, int height, int quality, int filterMode, int effortLevel) {
        var data = alpha.clone();
        var reduceLevels = quality < 100;
        if (reduceLevels) {
            var levels = quality <= 70 ? 2 + quality / 5 : 16 + (quality - 70) * 8;
            quantizeLevels(data, width, height, levels);
        }
        var tryMap = filterMap(data, width, height, filterMode, effortLevel);
        var trials = new ArrayList<Callable<byte[]>>(4);
        for (var filter = AlphaFilters.NONE; tryMap != 0; filter++, tryMap >>= 1) {
            if ((tryMap & 1) != 0) {
                var trialFilter = filter;
                trials.add(() -> encodeWithFilter(data, width, height, trialFilter, reduceLevels, effortLevel));
            }
        }
        byte[] best = null;
        for (var trial : ParallelBands.invokeAll(trials)) {
            if (best == null || trial.length < best.length) {
                best = trial;
            }
        }
        return best;
    }

    /**
     * Returns the set of filters to try, as a bit mask indexed by filter method.
     *
     * @param alpha       the plane
     * @param width       the plane width
     * @param height      the plane height
     * @param filterMode  the filter selection mode
     * @param effortLevel the encoder method
     * @return the mask
     */
    private static int filterMap(byte[] alpha, int width, int height, int filterMode, int effortLevel) {
        if (filterMode == FILTER_FAST) {
            var tryNone = effortLevel > 3;
            var numColors = numColors(alpha, width, height);
            var filter = numColors <= 16 ? AlphaFilters.NONE : AlphaFilters.estimateBestFilter(alpha, width, height, width);
            var map = 1 << filter;
            if (tryNone || numColors > 192) {
                map |= 1;
            }
            return map;
        }
        if (filterMode == FILTER_NONE) {
            return 1;
        }
        return 0xF;
    }

    /**
     * Counts the distinct values of a plane.
     *
     * @param data   the plane
     * @param width  the plane width
     * @param height the plane height
     * @return the number of distinct values
     */
    private static int numColors(byte[] data, int width, int height) {
        var seen = new boolean[256];
        for (var i = 0; i < width * height; i++) {
            seen[data[i] & 0xFF] = true;
        }
        var colors = 0;
        for (var s : seen) {
            if (s) {
                ++colors;
            }
        }
        return colors;
    }

    /**
     * Encodes a plane with one filter.
     *
     * @param data         the (quantized) plane
     * @param width        the plane width
     * @param height       the plane height
     * @param filter       the filter method
     * @param reduceLevels whether the plane was quantized
     * @param effortLevel  the encoder method
     * @return the {@code ALPH} payload
     */
    private static byte[] encodeWithFilter(byte[] data, int width, int height, int filter, boolean reduceLevels, int effortLevel) {
        var dataSize = width * height;
        var source = data;
        if (filter != AlphaFilters.NONE) {
            source = new byte[dataSize];
            AlphaFilters.filter(filter, data, width, height, width, source);
        }
        var argb = new int[dataSize];
        for (var i = 0; i < dataSize; i++) {
            argb[i] = (source[i] & 0xFF) << 8;
        }
        var quality = (!reduceLevels && effortLevel == 6) ? 100 : 8 * effortLevel;
        var stream = Vp8lEncoder.encodeStream(argb, width, height, quality, effortLevel, true);
        var method = AlphaDecoder.METHOD_LOSSLESS;
        byte[] payload;
        if (stream.length > dataSize) {
            method = AlphaDecoder.METHOD_RAW;
            payload = source;
        } else {
            payload = stream;
        }
        var header = method | (filter << 2) | (reduceLevels ? 1 << 4 : 0);
        var out = new byte[1 + payload.length];
        out[0] = (byte) header;
        System.arraycopy(payload, 0, out, 1, payload.length);
        return out;
    }

    /**
     * Quantizes a plane to at most {@code numLevels} values with a few iterations of k-means,
     * keeping the smallest and largest values exact.
     *
     * @param data      the plane, modified in place
     * @param width     the plane width
     * @param height    the plane height
     * @param numLevels the number of levels, from 2 to 256
     */
    static void quantizeLevels(byte[] data, int width, int height, int numLevels) {
        var freq = new int[256];
        var qLevel = new int[256];
        var invQLevel = new double[256];
        var minS = 255;
        var maxS = 0;
        var dataSize = width * height;
        var numLevelsIn = 0;
        for (var n = 0; n < dataSize; n++) {
            var v = data[n] & 0xFF;
            if (freq[v] == 0) {
                ++numLevelsIn;
            }
            minS = Math.min(minS, v);
            maxS = Math.max(maxS, v);
            ++freq[v];
        }
        if (numLevelsIn <= numLevels) {
            return;
        }
        for (var i = 0; i < numLevels; i++) {
            invQLevel[i] = minS + (double) (maxS - minS) * i / (numLevels - 1);
        }
        qLevel[minS] = 0;
        qLevel[maxS] = numLevels - 1;
        var lastErr = 1.e38;
        var errThreshold = 1e-4 * dataSize;
        for (var iter = 0; iter < 6; iter++) {
            var qSum = new double[256];
            var qCount = new double[256];
            var slot = 0;
            for (var s = minS; s <= maxS; s++) {
                while (slot < numLevels - 1 && 2 * s > invQLevel[slot] + invQLevel[slot + 1]) {
                    ++slot;
                }
                if (freq[s] > 0) {
                    qSum[slot] += (double) s * freq[s];
                    qCount[slot] += freq[s];
                }
                qLevel[s] = slot;
            }
            if (numLevels > 2) {
                for (slot = 1; slot < numLevels - 1; slot++) {
                    var count = qCount[slot];
                    if (count > 0.) {
                        invQLevel[slot] = qSum[slot] / count;
                    }
                }
            }
            var err = 0.;
            for (var s = minS; s <= maxS; s++) {
                var error = s - invQLevel[qLevel[s]];
                err += freq[s] * error * error;
            }
            if (lastErr - err < errThreshold) {
                break;
            }
            lastErr = err;
        }
        var map = new byte[256];
        for (var s = minS; s <= maxS; s++) {
            map[s] = (byte) (int) (invQLevel[qLevel[s]] + .5);
        }
        for (var n = 0; n < dataSize; n++) {
            data[n] = map[data[n] & 0xFF];
        }
    }
}
