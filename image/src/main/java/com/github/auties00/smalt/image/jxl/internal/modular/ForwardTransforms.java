/* Ports libjxl 0.11.2 (lib/jxl/modular/transform/enc_rct.cc: FwdRCT; enc_palette.cc: FwdPalette for lossless
   palettes; lib/jxl/enc_modular.cc: EstimateCost, try_palettes, maybe_do_transform and the RCT search of
   PrepareStreamParams). Copyright the JPEG XL Project Authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.modular;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Applies the forward modular transforms of a lossless encoder to a stream and records them in its header.
 */
public final class ForwardTransforms {
    private static final int[] COST_CUTOFFS = {0, 1, 3, 5, 7, 11, 15, 23, 31, 47, 63, 95, 127, 191, 255, 392, 500};

    /**
     * The RCT types the search tries, in order: no transform, YCoCg, then 17 others that are not equivalent to
     * them up to channel order and sign.
     */
    private static final int[] RCT_ORDER = {0, 6, 5, 10, 26, 40, 12, 19, 8, 4, 9, 15, 16, 17, 32, 33, 2, 1, 3};

    private ForwardTransforms() {
        throw new AssertionError();
    }

    /**
     * Applies a reversible color transform to three channels of equal size.
     *
     * @param stream the stream holding the channels
     * @param beginC the first of the three channels
     * @param type the RCT type, from 0 to 41; 0 records nothing
     */
    public static void rct(ModularEncoder.Stream stream, int beginC, int type) {
        if (type == 0) {
            return;
        }
        var channels = stream.channels;
        var permutation = type / 7;
        var custom = type % 7;
        var c0 = channels.get(beginC);
        var c1 = channels.get(beginC + 1);
        var c2 = channels.get(beginC + 2);
        var src = new int[][]{c0.data, c1.data, c2.data};
        var in0 = src[permutation % 3];
        var in1 = src[(permutation + 1 + permutation / 3) % 3];
        var in2 = src[(permutation + 2 - permutation / 3) % 3];
        var n = c0.width * c0.height;
        var out0 = new int[n];
        var out1 = new int[n];
        var out2 = new int[n];
        var second = custom >> 1;
        var third = custom & 1;
        for (var i = 0; i < n; i++) {
            var r = in0[i];
            var g = in1[i];
            var b = in2[i];
            if (custom == 6) {
                var co = r - b;
                var tmp = b + (co >> 1);
                var cg = g - tmp;
                out0[i] = tmp + (cg >> 1);
                out1[i] = co;
                out2[i] = cg;
            } else {
                if (second == 1) {
                    g -= r;
                } else if (second == 2) {
                    g -= (r + b) >> 1;
                }
                if (third != 0) {
                    b -= r;
                }
                out0[i] = r;
                out1[i] = g;
                out2[i] = b;
            }
        }
        channels.set(beginC, withData(c0, out0));
        channels.set(beginC + 1, withData(c1, out1));
        channels.set(beginC + 2, withData(c2, out2));
        stream.addRct(beginC, type);
    }

    private static ModularChannel withData(ModularChannel c, int[] data) {
        var copy = c.copyInfo();
        copy.data = data;
        return copy;
    }

    /**
     * Tries the first RCT types of the search order and keeps the one with the lowest estimated cost.
     *
     * @param stream the stream holding the channels
     * @param beginC the first of the three color channels
     * @param tries how many types to try, at most 19
     */
    public static void searchRct(ModularEncoder.Stream stream, int beginC, int tries) {
        if (tries <= 0) {
            return;
        }
        var saved = new ArrayList<>(stream.channels);
        var best = 0;
        var bestCost = Double.MAX_VALUE;
        for (var k = 0; k < Math.min(tries, RCT_ORDER.length); k++) {
            var type = RCT_ORDER[k];
            rct(stream, beginC, type);
            var cost = estimateCost(stream);
            if (cost < bestCost) {
                bestCost = cost;
                best = type;
            }
            if (type != 0) {
                stream.transforms.removeLast();
                restore(stream, saved);
            }
        }
        rct(stream, beginC, best);
    }

    private static void restore(ModularEncoder.Stream stream, List<ModularChannel> saved) {
        stream.channels.clear();
        stream.channels.addAll(saved);
    }

    /**
     * Estimates the cost in bits of coding the stream's channels with the clamped gradient predictor and a
     * context per local gradient range ({@code EstimateCost}).
     *
     * @param stream the stream to estimate
     * @return the estimate
     */
    public static double estimateCost(ModularEncoder.Stream stream) {
        long extra = 0;
        var cost = 0.0;
        var nc = COST_CUTOFFS.length + 1;
        var histograms = new int[nc][128];
        var totals = new long[nc];
        for (var ch : stream.channels) {
            var width = ch.width;
            var data = ch.data;
            if (data == null) {
                continue;
            }
            for (var y = 0; y < ch.height; y++) {
                var row = y * width;
                for (var x = 0; x < width; x++) {
                    var idx = row + x;
                    long left = x > 0 ? data[idx - 1] : y > 0 ? data[idx - width] : 0;
                    long top = y > 0 ? data[idx - width] : left;
                    long topLeft = x > 0 && y > 0 ? data[idx - width - 1] : left;
                    var maxDiff = Math.max(Math.max(left, top), topLeft) - Math.min(Math.min(left, top), topLeft);
                    var ctx = 0;
                    for (var c : COST_CUTOFFS) {
                        if (c > maxDiff) {
                            ctx++;
                        }
                    }
                    var res = data[idx] - Predictors.gradient((int) top, (int) left, (int) topLeft);
                    var v = (res << 1) ^ (res >> 31);
                    int token;
                    if (Integer.compareUnsigned(v, 16) < 0) {
                        token = v;
                    } else {
                        var n = 31 - Integer.numberOfLeadingZeros(v);
                        var m = v - (1 << n);
                        token = 16 + ((n - 4) << 2) + (m >>> (n - 2));
                        extra += n - 2;
                    }
                    histograms[ctx][token]++;
                    totals[ctx]++;
                }
            }
            for (var c = 0; c < nc; c++) {
                cost += entropy(histograms[c], totals[c]);
                Arrays.fill(histograms[c], 0);
                totals[c] = 0;
            }
        }
        return cost + extra;
    }

    private static double entropy(int[] counts, long total) {
        if (total == 0) {
            return 0;
        }
        var inv = 1.0 / total;
        var sum = 0.0;
        for (var c : counts) {
            if (c != 0 && c != total) {
                sum -= c * StrictMath.log(c * inv) * 1.4426950408889634;
            }
        }
        return sum;
    }

    /**
     * Replaces channels by a palette of their colors and an index channel, if they have few enough colors.
     *
     * <p>Colors are ordered by luma (times alpha when there is a fourth channel), frequent colors first.
     *
     * @param stream the stream holding the channels
     * @param beginC the first channel
     * @param numC the number of channels, all the same size
     * @param maxColors the largest palette worth making
     * @return whether the transform was applied
     */
    public static boolean palette(ModularEncoder.Stream stream, int beginC, int numC, int maxColors) {
        if (maxColors < 2 || numC < 1) {
            return false;
        }
        var channels = stream.channels;
        var first = channels.get(beginC);
        var n = first.width * first.height;
        if (numC == 1) {
            return channelPalette(stream, beginC, maxColors);
        }
        var data = new int[numC][];
        for (var c = 0; c < numC; c++) {
            data[c] = channels.get(beginC + c).data;
        }
        var index = new HashMap<ColorKey, int[]>();
        var order = new ArrayList<ColorKey>();
        var probe = new ColorKey(new int[numC]);
        for (var i = 0; i < n; i++) {
            for (var c = 0; c < numC; c++) {
                probe.values[c] = data[c][i];
            }
            probe.rehash();
            var entry = index.get(probe);
            if (entry == null) {
                if (order.size() >= maxColors) {
                    return false;
                }
                var key = new ColorKey(probe.values.clone());
                key.rehash();
                entry = new int[]{0, 0};
                index.put(key, entry);
                order.add(key);
            }
            entry[1]++;
        }
        if (order.size() <= 1) {
            return false;
        }
        if (numC >= 3) {
            order.sort((a, b) -> {
                var ay = luma(a.values);
                var by = luma(b.values);
                ay = index.get(a)[1] > 4 ? -ay : ay;
                by = index.get(b)[1] > 4 ? -by : by;
                return Float.compare(ay, by);
            });
        }
        var nbColors = order.size();
        var pal = new ModularChannel(nbColors, numC, -1, -1);
        pal.data = new int[nbColors * numC];
        for (var k = 0; k < nbColors; k++) {
            var key = order.get(k);
            index.get(key)[0] = k;
            for (var c = 0; c < numC; c++) {
                pal.data[c * nbColors + k] = key.values[c];
            }
        }
        var indices = new int[n];
        for (var i = 0; i < n; i++) {
            for (var c = 0; c < numC; c++) {
                probe.values[c] = data[c][i];
            }
            probe.rehash();
            indices[i] = index.get(probe)[0];
        }
        var replaced = withData(first, indices);
        channels.subList(beginC, beginC + numC).clear();
        channels.add(beginC, replaced);
        channels.addFirst(pal);
        stream.addPalette(beginC, numC, nbColors, 0, 0);
        stream.nbMeta++;
        return true;
    }

    private static float luma(int[] v) {
        var y = 0.299f * v[0] + 0.587f * v[1] + 0.114f * v[2] + 0.1f;
        if (v.length > 3) {
            y *= 1.0f + v[3];
        }
        return y;
    }

    /**
     * Compacts a channel to the indices of the values it uses, if they are few enough.
     *
     * @param stream the stream holding the channel
     * @param c the channel
     * @param maxColors the largest number of distinct values worth compacting
     * @return whether the transform was applied
     */
    public static boolean channelPalette(ModularEncoder.Stream stream, int c, int maxColors) {
        if (maxColors <= 0) {
            return false;
        }
        var channel = stream.channels.get(c);
        var data = channel.data;
        var n = channel.width * channel.height;
        if (n == 0) {
            return false;
        }
        var min = Integer.MAX_VALUE;
        var max = Integer.MIN_VALUE;
        for (var i = 0; i < n; i++) {
            min = Math.min(min, data[i]);
            max = Math.max(max, data[i]);
        }
        var range = (long) max - min + 1;
        int[] palette;
        var lookup = new HashMap<Integer, Integer>();
        if (range <= 1 << 16) {
            var used = new boolean[(int) range];
            var count = 0;
            for (var i = 0; i < n; i++) {
                if (!used[data[i] - min]) {
                    used[data[i] - min] = true;
                    if (++count > maxColors) {
                        return false;
                    }
                }
            }
            palette = new int[count];
            var k = 0;
            for (var i = 0; i < range; i++) {
                if (used[i]) {
                    palette[k] = (int) (i + min);
                    lookup.put(palette[k], k);
                    k++;
                }
            }
        } else {
            var set = new java.util.TreeSet<Integer>();
            for (var i = 0; i < n; i++) {
                if (set.add(data[i]) && set.size() > maxColors) {
                    return false;
                }
            }
            palette = new int[set.size()];
            var k = 0;
            for (var v : set) {
                palette[k] = v;
                lookup.put(v, k++);
            }
        }
        var indices = new int[n];
        if (range <= 1 << 16) {
            var table = new int[(int) range];
            for (var k = 0; k < palette.length; k++) {
                table[palette[k] - min] = k;
            }
            for (var i = 0; i < n; i++) {
                indices[i] = table[data[i] - min];
            }
        } else {
            for (var i = 0; i < n; i++) {
                indices[i] = lookup.get(data[i]);
            }
        }
        var pal = new ModularChannel(palette.length, 1, -1, -1);
        pal.data = palette;
        stream.channels.set(c, withData(channel, indices));
        stream.channels.addFirst(pal);
        stream.addPalette(c, 1, palette.length, 0, 0);
        stream.nbMeta++;
        return true;
    }

    /**
     * Runs the palette heuristics of a lossless encoder on a stream: a palette of all channels, or of all but
     * the last, then channel compaction.
     *
     * @param stream the stream to transform
     * @param bitDepth the sample bit depth
     * @param paletteColors the largest palette, 0 to make none
     * @param channelPercent the percentage of a channel's value range that may be used for compaction to be
     * tried, 0 to compact none
     * @param costCheck whether to undo a transform that does not lower the estimated cost
     */
    public static void tryPalettes(ModularEncoder.Stream stream, int bitDepth, int paletteColors,
                                   double channelPercent, boolean costCheck) {
        var channels = stream.channels;
        var nbChans = channels.size() - stream.nbMeta;
        if (nbChans <= 0) {
            return;
        }
        var image = channels.get(stream.nbMeta);
        var pixels = (double) image.width * image.height;
        var costBefore = costCheck ? estimateCost(stream) : pixels * 0.2 * bitDepth * nbChans;
        var didPalette = 0;
        if (paletteColors != 0) {
            var limit = (int) Math.min(costBefore * 0.0005 + pixels / 128 + 128, paletteColors);
            if (nbChans > 1 && sameSize(stream, stream.nbMeta, nbChans)
                    && tryTransform(stream, costCheck, costBefore,
                    () -> palette(stream, stream.nbMeta, nbChans, limit))) {
                didPalette = 1;
            }
            if (didPalette == 0 && nbChans > 3 && sameSize(stream, stream.nbMeta, nbChans - 1)
                    && tryTransform(stream, costCheck, costBefore,
                    () -> palette(stream, stream.nbMeta, nbChans - 1, limit))) {
                didPalette = 1;
            }
        }
        if (channelPercent > 0) {
            var remaining = channels.size() - stream.nbMeta - didPalette;
            if (remaining > 0 && (didPalette != 0 || !costCheck)) {
                costBefore = costCheck ? estimateCost(stream) : 0;
            }
            for (var i = didPalette; i < remaining + didPalette; i++) {
                var index = stream.nbMeta + i;
                if (index >= channels.size()) {
                    break;
                }
                var ch = channels.get(index);
                var min = Integer.MAX_VALUE;
                var max = Integer.MIN_VALUE;
                for (var k = 0; k < ch.width * ch.height; k++) {
                    min = Math.min(min, ch.data[k]);
                    max = Math.max(max, ch.data[k]);
                }
                var colors = (long) max - min + 1;
                var limit = (int) Math.min(pixels / 16, channelPercent / 100.0 * colors);
                var cost = costBefore;
                tryTransform(stream, costCheck, cost, () -> channelPalette(stream, index, limit));
            }
        }
    }

    private static boolean sameSize(ModularEncoder.Stream stream, int begin, int count) {
        var first = stream.channels.get(begin);
        for (var i = begin + 1; i < begin + count; i++) {
            if (!stream.channels.get(i).sameGeometry(first)) {
                return false;
            }
        }
        return true;
    }

    private static boolean tryTransform(ModularEncoder.Stream stream, boolean costCheck, double costBefore,
                                        java.util.function.BooleanSupplier transform) {
        var saved = new ArrayList<>(stream.channels);
        var savedMeta = stream.nbMeta;
        var savedTransforms = stream.transforms.size();
        if (!transform.getAsBoolean()) {
            return false;
        }
        if (costCheck && estimateCost(stream) > costBefore) {
            restore(stream, saved);
            stream.nbMeta = savedMeta;
            while (stream.transforms.size() > savedTransforms) {
                stream.transforms.removeLast();
            }
            return false;
        }
        return true;
    }

    private static final class ColorKey {
        final int[] values;

        int hash;

        ColorKey(int[] values) {
            this.values = values;
        }

        void rehash() {
            hash = Arrays.hashCode(values);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ColorKey k && Arrays.equals(values, k.values);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
