/* Ports libjxl 0.11.2 (lib/jxl/modular/encoding/enc_ma.cc: TreeSamples, PreQuantizeProperties,
   CollectPixelSamples, FindBestSplit, SplitTreeSamples; enc_encoding.cc: GatherTreeData, LearnTree). Copyright the
   JPEG XL Project Authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.modular;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Learns a meta-adaptive tree from samples of modular channels by greedy splitting on the property that saves the
 * most estimated bits.
 *
 * @implNote This implementation caps the number of samples at about {@value #MAX_SAMPLES}, lowering the sampled
 * fraction for large images, so that learning memory stays bounded.
 */
final class TreeLearner {
    private static final int PROPERTY_RANGE = 511;

    private static final int MAX_SAMPLES = 1 << 21;

    private static final int[] WP_THRESHOLDS_16 = {-127, -63, -31, -15, -7, -3, -1, 0, 1, 3, 7, 15, 31, 63, 127};

    private static final int[] WP_THRESHOLDS_32 = {-255, -191, -127, -95, -63, -47, -31, -23, -15, -11, -7, -5, -3,
            -1, 0, 1, 3, 5, 7, 11, 15, 23, 31, 47, 63, 95, 127, 191, 255};

    private static final int[] WP_THRESHOLDS_64 = {-255, -223, -191, -159, -127, -111, -95, -79, -63, -55, -47, -39,
            -31, -27, -23, -19, -15, -13, -11, -9, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13, 15,
            19, 23, 27, 31, 39, 47, 55, 63, 79, 95, 111, 127, 159, 191, 223, 255};

    private final int[] predictors;

    private final int[] props;

    private final int[][] thresholds;

    private final int[][] mapping;

    private byte[][] token;

    private byte[][] nbits;

    private short[][] value;

    private int[] count;

    private int size;

    private long totalSamples;

    private int[] table;

    private TreeLearner(int[] predictors, int[] props, int[][] thresholds) {
        this.predictors = predictors;
        this.props = props;
        this.thresholds = thresholds;
        this.mapping = new int[props.length][];
        for (var i = 0; i < props.length; i++) {
            var m = new int[PROPERTY_RANGE * 2 + 1];
            var mapped = 0;
            var t = thresholds[i];
            for (var j = 0; j < m.length; j++) {
                while (mapped < t.length && j - PROPERTY_RANGE > t[mapped]) {
                    mapped++;
                }
                m[j] = mapped;
            }
            mapping[i] = m;
        }
        token = new byte[predictors.length][1024];
        nbits = new byte[predictors.length][1024];
        value = new short[props.length][1024];
        count = new int[1024];
        table = new int[2048];
        Arrays.fill(table, -1);
    }

    /**
     * Learns the tree of a range of streams.
     *
     * @param streams the non-empty streams of the range
     * @param settings the learning settings
     * @return the tree
     */
    static ModularEncoder.Tree learn(List<ModularEncoder.Stream> streams, ModularEncoder.TreeSettings settings) {
        var pixelSamples = new ArrayList<Integer>();
        var diffSamples = new ArrayList<Integer>();
        var maxStream = 0;
        var maxChannel = 0;
        for (var s : streams) {
            maxStream = Math.max(maxStream, s.id);
            maxChannel = Math.max(maxChannel, s.codedCount());
        }
        var groupCount = new long[maxStream + 1];
        var channelCount = new long[maxChannel + 1];
        long totalPixels = 0;
        for (var s : streams) {
            totalPixels += collect(s, settings.sampleFraction(), groupCount, channelCount, pixelSamples, diffSamples);
        }
        var fraction = Math.min(settings.sampleFraction(), (double) MAX_SAMPLES / Math.max(1, totalPixels));
        var props = settings.properties();
        var thresholds = new int[props.length][];
        var maxValues = settings.maxPropertyValues();
        int[] pixelThresholds = null;
        int[] absPixelThresholds = null;
        int[] diffThresholds = null;
        int[] absDiffThresholds = null;
        for (var i = 0; i < props.length; i++) {
            var p = props[i];
            if (p == 0) {
                thresholds[i] = quantizeHistogram(channelCount, maxValues);
            } else if (p == 1) {
                thresholds[i] = quantizeHistogram(groupCount, maxValues);
            } else if (p == 2 || p == 3) {
                var q = new int[maxValues - 1];
                for (var k = 0; k + 1 < maxValues; k++) {
                    q[k] = (k + 1) * 256 / maxValues - 1;
                }
                thresholds[i] = q;
            } else if (p == 6 || p == 7 || p == 8 || (p >= 16 && (p - 16) % 4 == 1)) {
                if (pixelThresholds == null) {
                    pixelThresholds = quantizeSamples(pixelSamples, false, maxValues);
                }
                thresholds[i] = pixelThresholds;
            } else if (p == 4 || p == 5 || (p >= 16 && (p - 16) % 4 == 0)) {
                if (absPixelThresholds == null) {
                    absPixelThresholds = quantizeSamples(pixelSamples, true, maxValues);
                }
                thresholds[i] = absPixelThresholds;
            } else if (p >= 16 && (p - 16) % 4 == 2) {
                if (absDiffThresholds == null) {
                    absDiffThresholds = quantizeSamples(diffSamples, true, maxValues);
                }
                thresholds[i] = absDiffThresholds;
            } else if (p == 15) {
                thresholds[i] = maxValues < 32 ? WP_THRESHOLDS_16 : maxValues < 64 ? WP_THRESHOLDS_32
                        : WP_THRESHOLDS_64;
            } else {
                if (diffThresholds == null) {
                    diffThresholds = quantizeSamples(diffSamples, false, maxValues);
                }
                thresholds[i] = diffThresholds;
            }
        }
        var learner = new TreeLearner(settings.predictors(), props, thresholds);
        long total = 0;
        for (var s : streams) {
            total += learner.gather(s, fraction);
        }
        var tree = ModularEncoder.Tree.leaf(settings.predictors()[0]);
        if (learner.size == 0) {
            return tree;
        }
        var pixelFraction = (double) learner.totalSamples / Math.max(1, total);
        var required = pixelFraction * 0.9 + 0.1;
        learner.table = null;
        learner.findBestSplit((float) (settings.nodeThreshold() * required), settings.fastDecodeMultiplier(), tree);
        return tree;
    }

    private static long collect(ModularEncoder.Stream stream, float repeats, long[] groupCount, long[] channelCount,
                                List<Integer> pixelSamples, List<Integer> diffSamples) {
        var fraction = Math.min(repeats * 0.1, 0.99);
        var rng = new java.util.SplittableRandom(stream.id);
        long total = 0;
        for (var i = 0; i < stream.codedCount(); i++) {
            var c = stream.channels.get(i);
            if (c.width <= 1 || c.height == 0) {
                continue;
            }
            long px = (long) c.width * c.height;
            groupCount[stream.id] += px;
            channelCount[i] += px;
            total += px;
            if (fraction <= 0) {
                continue;
            }
            var step = Math.max(1, (int) Math.round(1 / fraction));
            for (var idx = rng.nextInt(step); idx < px && pixelSamples.size() < MAX_SAMPLES / 4; idx += 1 + rng.nextInt(2 * step - 1)) {
                var x = (int) (idx % c.width);
                var v = c.data[(int) idx];
                var xp = x == 0 ? 1 : x - 1;
                pixelSamples.add(v);
                diffSamples.add(v - c.data[(int) (idx - x + xp)]);
            }
        }
        return total;
    }

    private static int[] quantizeHistogram(long[] histogram, int chunks) {
        long sum = 0;
        for (var h : histogram) {
            sum += h;
        }
        var result = new ArrayList<Integer>();
        long cumsum = 0;
        long threshold = 1;
        for (var i = 0; i + 1 < histogram.length; i++) {
            cumsum += histogram[i];
            if (cumsum >= threshold * sum / chunks) {
                result.add(i);
                while (cumsum > threshold * sum / chunks) {
                    threshold++;
                }
            }
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] quantizeSamples(List<Integer> samples, boolean abs, int chunks) {
        if (samples.isEmpty()) {
            return new int[0];
        }
        var range = 512;
        var min = Integer.MAX_VALUE;
        for (int s : samples) {
            min = Math.min(min, abs ? Math.abs(s) : s);
        }
        min = Math.clamp(min, -range, range);
        var counts = new long[2 * range + 1];
        for (int s : samples) {
            var v = abs ? Math.abs(s) : s;
            counts[Math.clamp(v, -range, range) - min]++;
        }
        var t = quantizeHistogram(counts, chunks);
        for (var i = 0; i < t.length; i++) {
            t[i] += min;
        }
        return t;
    }

    private long gather(ModularEncoder.Stream stream, double repeats) {
        long total = 0;
        var needWp = false;
        for (var p : predictors) {
            needWp |= p == Predictors.WEIGHTED;
        }
        for (var p : props) {
            needWp |= p == 15;
        }
        var pred = new int[predictors.length];
        var all = new int[16];
        long s0 = 0x94D049BB133111EBL;
        long s1 = 0xBF58476D1CE4E5B9L;
        for (var i = 0; i < stream.codedCount(); i++) {
            var c = stream.channels.get(i);
            if (c.width == 0 || c.height == 0) {
                continue;
            }
            var width = c.width;
            var height = c.height;
            var data = c.data;
            var fraction = repeats;
            if (fraction > 0) {
                fraction = Math.max(fraction, Math.min(1.0, 1024.0 / ((double) width * height)));
            }
            var threshold = (long) ((0xFFFF_FFFFL) * fraction);
            var weighted = needWp ? new WeightedPredictor(WpHeader.DEFAULT, width) : null;
            all[0] = i;
            all[1] = stream.id;
            for (var y = 0; y < height; y++) {
                var row = y * width;
                var prevGrad = 0;
                all[2] = y;
                for (var x = 0; x < width; x++) {
                    var idx = row + x;
                    var w = x > 0 ? data[idx - 1] : (y > 0 ? data[idx - width] : 0);
                    var n = y > 0 ? data[idx - width] : w;
                    var nw = x > 0 && y > 0 ? data[idx - width - 1] : w;
                    var ne = y > 0 && x + 1 < width ? data[idx - width + 1] : n;
                    var nn = y > 1 ? data[idx - 2 * width] : n;
                    var ww = x > 1 ? data[idx - 2] : w;
                    var nee = y > 0 && x + 2 < width ? data[idx - width + 2] : ne;
                    if (weighted != null) {
                        weighted.predict(n, nw, ne, w, nn);
                        all[15] = weighted.maxError;
                    }
                    var grad = w - nw + n;
                    total++;
                    var a = s0;
                    var b = s1;
                    var bits = a + b;
                    s0 = b;
                    a ^= a << 23;
                    a ^= b ^ (a >>> 18) ^ (b >>> 5);
                    s1 = a;
                    if ((bits >>> 32) <= threshold) {
                        all[3] = x;
                        all[4] = Math.abs(n);
                        all[5] = Math.abs(w);
                        all[6] = n;
                        all[7] = w;
                        all[8] = w - prevGrad;
                        all[9] = grad;
                        all[10] = w - nw;
                        all[11] = nw - n;
                        all[12] = n - ne;
                        all[13] = n - nn;
                        all[14] = w - ww;
                        for (var k = 0; k < predictors.length; k++) {
                            pred[k] = predictors[k] == Predictors.WEIGHTED ? (int) ((weighted.prediction + 3) >> 3)
                                    : Predictors.predict(predictors[k], n, w, nw, ne, nn, ww, nee);
                        }
                        addSample(data[idx], all, pred);
                    }
                    if (weighted != null) {
                        weighted.record(data[idx]);
                    }
                    prevGrad = grad;
                }
            }
        }
        return total;
    }

    private void addSample(int pixel, int[] properties, int[] pred) {
        if (size == count.length) {
            var grown = size * 2;
            for (var k = 0; k < predictors.length; k++) {
                token[k] = Arrays.copyOf(token[k], grown);
                nbits[k] = Arrays.copyOf(nbits[k], grown);
            }
            for (var k = 0; k < props.length; k++) {
                value[k] = Arrays.copyOf(value[k], grown);
            }
            count = Arrays.copyOf(count, grown);
        }
        for (var k = 0; k < predictors.length; k++) {
            var v = packSigned(pixel - pred[k]);
            token[k][size] = (byte) tokenOf(v);
            nbits[k][size] = (byte) nbitsOf(v);
        }
        for (var k = 0; k < props.length; k++) {
            var v = properties[props[k]];
            v = Math.clamp(v, -PROPERTY_RANGE, PROPERTY_RANGE) + PROPERTY_RANGE;
            value[k][size] = (short) mapping[k][v];
        }
        totalSamples++;
        if (size * 2 >= table.length) {
            rehash(table.length * 2);
        }
        var h = hash(size) & (table.length - 1);
        while (table[h] >= 0) {
            var other = table[h];
            if (same(other, size)) {
                count[other]++;
                return;
            }
            h = (h + 1) & (table.length - 1);
        }
        table[h] = size;
        count[size] = 1;
        size++;
    }

    private void rehash(int capacity) {
        table = new int[capacity];
        Arrays.fill(table, -1);
        for (var i = 0; i < size; i++) {
            var h = hash(i) & (capacity - 1);
            while (table[h] >= 0) {
                h = (h + 1) & (capacity - 1);
            }
            table[h] = i;
        }
    }

    private int hash(int a) {
        long h = 0x1e35a7bdL;
        for (var k = 0; k < predictors.length; k++) {
            h = h * 0x1e35a7bdL + token[k][a];
            h = h * 0x1e35a7bdL + nbits[k][a];
        }
        for (var k = 0; k < props.length; k++) {
            h = h * 0x1e35a7bdL + value[k][a];
        }
        return (int) (h ^ (h >>> 29));
    }

    private boolean same(int a, int b) {
        for (var k = 0; k < predictors.length; k++) {
            if (token[k][a] != token[k][b] || nbits[k][a] != nbits[k][b]) {
                return false;
            }
        }
        for (var k = 0; k < props.length; k++) {
            if (value[k][a] != value[k][b]) {
                return false;
            }
        }
        return true;
    }

    private static int packSigned(int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static int tokenOf(int value) {
        if (Integer.compareUnsigned(value, 16) < 0) {
            return value;
        }
        var n = 31 - Integer.numberOfLeadingZeros(value);
        var m = value - (1 << n);
        return 16 + ((n - 4) << 3) + ((m >>> (n - 1)) << 2) + (m & 3);
    }

    private static int nbitsOf(int value) {
        if (Integer.compareUnsigned(value, 16) < 0) {
            return 0;
        }
        return 31 - Integer.numberOfLeadingZeros(value) - 3;
    }

    private static double estimateBits(int[] counts, int offset, int n) {
        long total = 0;
        for (var i = 0; i < n; i++) {
            total += counts[offset + i];
        }
        if (total == 0) {
            return 0;
        }
        var inv = 1.0 / total;
        var bits = 0.0;
        for (var i = 0; i < n; i++) {
            var c = counts[offset + i];
            if (c == 0 || c == total) {
                continue;
            }
            var p = Math.max(c * inv, 1.0 / 4096);
            bits -= c * StrictMath.log(p) * 1.4426950408889634;
        }
        return bits;
    }

    private static final class Split {
        int prop;

        int val;

        int pos;

        double lcost = Double.MAX_VALUE;

        double rcost = Double.MAX_VALUE;

        int lpred;

        int rpred;

        double cost() {
            return lcost + rcost;
        }
    }

    private void findBestSplit(float threshold, float fastDecodeMultiplier, ModularEncoder.Tree tree) {
        record Node(int pos, int begin, int end, long used) {
        }
        var stack = new ArrayList<Node>();
        stack.add(new Node(0, 0, size, 0));
        var numPred = predictors.length;
        var numProps = props.length;
        while (!stack.isEmpty()) {
            var node = stack.removeLast();
            var pos = node.pos();
            var begin = node.begin();
            var end = node.end();
            if (begin == end) {
                continue;
            }
            var maxSymbols = 0;
            for (var p = 0; p < numPred; p++) {
                var t = token[p];
                for (var i = begin; i < end; i++) {
                    maxSymbols = Math.max(maxSymbols, (t[i] & 0xFF) + 1);
                }
            }
            var counts = new int[maxSymbols * numPred];
            var totExtra = new long[numPred];
            for (var p = 0; p < numPred; p++) {
                var t = token[p];
                var nb = nbits[p];
                for (var i = begin; i < end; i++) {
                    counts[p * maxSymbols + (t[i] & 0xFF)] += count[i];
                    totExtra[p] += (long) nb[i] * count[i];
                }
            }
            var currentPred = tree.predictor.get(pos);
            var currentIndex = 0;
            for (var p = 0; p < numPred; p++) {
                if (predictors[p] == currentPred) {
                    currentIndex = p;
                }
            }
            var baseBits = estimateBits(counts, currentIndex * maxSymbols, maxSymbols) + totExtra[currentIndex];
            var staticConstant = new Split();
            var staticSplit = new Split();
            var nonstatic = new Split();
            var nowp = new Split();
            var changePenalty = 800.0 / (100.0 + threshold);
            var countsAbove = new int[maxSymbols];
            var countsBelow = new int[maxSymbols];
            for (var prop = 0; prop < numProps && baseBits > threshold; prop++) {
                var propSize = thresholds[prop].length + 1;
                var used = new int[propSize];
                var first = propSize;
                var last = 0;
                var v = value[prop];
                for (var i = begin; i < end; i++) {
                    var q = v[i];
                    used[q]++;
                    last = Math.max(last, q);
                    first = Math.min(first, q);
                }
                if (last <= first) {
                    continue;
                }
                var span = last - first;
                var costL = new double[span];
                var costR = new double[span];
                var extraL = new double[span];
                var extraR = new double[span];
                var predL = new int[span];
                var predR = new int[span];
                Arrays.fill(costL, Double.MAX_VALUE);
                Arrays.fill(costR, Double.MAX_VALUE);
                var increase = new int[propSize * maxSymbols];
                var extraIncrease = new long[propSize];
                for (var p = 0; p < numPred; p++) {
                    var t = token[p];
                    var nb = nbits[p];
                    for (var i = begin; i < end; i++) {
                        var q = v[i];
                        increase[q * maxSymbols + (t[i] & 0xFF)] += count[i];
                        extraIncrease[q] += (long) nb[i] * count[i];
                    }
                    System.arraycopy(counts, p * maxSymbols, countsAbove, 0, maxSymbols);
                    Arrays.fill(countsBelow, 0);
                    long extraBelow = 0;
                    for (var q = first; q < last; q++) {
                        if (used[q] == 0) {
                            continue;
                        }
                        extraBelow += extraIncrease[q];
                        extraIncrease[q] = 0;
                        for (var sym = 0; sym < maxSymbols; sym++) {
                            var inc = increase[q * maxSymbols + sym];
                            countsAbove[sym] -= inc;
                            countsBelow[sym] += inc;
                            increase[q * maxSymbols + sym] = 0;
                        }
                        var rcost = estimateBits(countsAbove, 0, maxSymbols) + totExtra[p] - extraBelow;
                        var lcost = estimateBits(countsBelow, 0, maxSymbols) + extraBelow;
                        var penalty = 0.0;
                        if (predictors[p] != currentPred && currentPred != Predictors.WEIGHTED) {
                            penalty = changePenalty;
                        }
                        if (predictors[p] == Predictors.WEIGHTED) {
                            penalty += 1e-8;
                        }
                        if (predictors[p] == Predictors.ZERO) {
                            penalty -= 1e-8;
                        }
                        var k = q - first;
                        if (rcost + penalty < costR[k] + extraR[k]) {
                            costR[k] = rcost;
                            extraR[k] = penalty;
                            predR[k] = predictors[p];
                        }
                        if (lcost + penalty < costL[k] + extraL[k]) {
                            costL[k] = lcost;
                            extraL[k] = penalty;
                            predL[k] = predictors[p];
                        }
                    }
                    extraIncrease[last] = 0;
                    for (var sym = 0; sym < maxSymbols; sym++) {
                        increase[last * maxSymbols + sym] = 0;
                    }
                }
                var split = begin;
                for (var q = first; q < last; q++) {
                    if (used[q] == 0) {
                        continue;
                    }
                    split += used[q];
                    var k = q - first;
                    var rcost = costR[k];
                    var lcost = costL[k];
                    var addsWp = (props[prop] == 15 && (node.used() & (1L << prop)) == 0)
                            || ((predL[k] == Predictors.WEIGHTED || predR[k] == Predictors.WEIGHTED)
                            && currentPred != Predictors.WEIGHTED);
                    var zeroEntropySide = rcost == 0 || lcost == 0;
                    var best = props[prop] < 2 ? (zeroEntropySide ? staticConstant : staticSplit)
                            : (addsWp ? nonstatic : nowp);
                    if (lcost + rcost < best.cost()) {
                        best.prop = prop;
                        best.val = q;
                        best.pos = split;
                        best.lcost = lcost;
                        best.lpred = predL[k];
                        best.rcost = rcost;
                        best.rpred = predR[k];
                    }
                }
            }
            var best = nonstatic;
            if (nowp.cost() + threshold < baseBits && nowp.cost() <= fastDecodeMultiplier * best.cost()) {
                best = nowp;
            }
            if (staticSplit.cost() + threshold < baseBits
                    && staticSplit.cost() <= fastDecodeMultiplier * best.cost()) {
                best = staticSplit;
            }
            if (staticConstant.cost() + threshold < baseBits) {
                best = staticConstant;
            }
            if (best.cost() + threshold < baseBits) {
                var p = props[best.prop];
                var dequant = thresholds[best.prop][best.val];
                var lchild = tree.size();
                tree.setSplit(pos, p, dequant, lchild, lchild + 1);
                tree.add(-1, 0, 0, 0, best.rpred, 0, 1);
                tree.add(-1, 0, 0, 0, best.lpred, 0, 1);
                partition(begin, end, best.prop);
                var used = node.used();
                if (p >= 2) {
                    used |= 1L << best.prop;
                }
                stack.add(new Node(lchild + 1, begin, best.pos, used));
                stack.add(new Node(lchild, best.pos, end, used));
            }
        }
    }

    private void partition(int begin, int end, int prop) {
        var v = value[prop];
        var buckets = new int[thresholds[prop].length + 2];
        for (var i = begin; i < end; i++) {
            buckets[v[i] + 1]++;
        }
        for (var k = 1; k < buckets.length; k++) {
            buckets[k] += buckets[k - 1];
        }
        var order = new int[end - begin];
        for (var i = begin; i < end; i++) {
            order[buckets[v[i]]++] = i;
        }
        permute(begin, order);
    }

    private void permute(int begin, int[] order) {
        var n = order.length;
        for (var p = 0; p < predictors.length; p++) {
            var t = new byte[n];
            var nb = new byte[n];
            for (var k = 0; k < n; k++) {
                t[k] = token[p][order[k]];
                nb[k] = nbits[p][order[k]];
            }
            System.arraycopy(t, 0, token[p], begin, n);
            System.arraycopy(nb, 0, nbits[p], begin, n);
        }
        for (var q = 0; q < props.length; q++) {
            var vv = new short[n];
            for (var k = 0; k < n; k++) {
                vv[k] = value[q][order[k]];
            }
            System.arraycopy(vv, 0, value[q], begin, n);
        }
        var c = new int[n];
        for (var k = 0; k < n; k++) {
            c[k] = count[order[k]];
        }
        System.arraycopy(c, 0, count, begin, n);
    }
}
