package com.github.auties00.smalt.image.webp.internal.lossless;

import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Clusters the symbol histograms of image tiles into a small set of prefix code groups, the
 * entropy image of the lossless format.
 *
 * <p>Every tile of {@code 2^histogramBits} pixels gets its own histogram; histograms with similar
 * entropy are first merged within entropy bins, then pairs are merged by a stochastic search and
 * finally greedily while merging lowers the estimated total cost. Each tile is finally mapped to
 * the cluster that codes it cheapest.
 *
 * @implNote This implementation follows libwebp's {@code VP8LGetHistoImageSymbols} of
 * {@code src/enc/histogram_enc.c} (BSD-3), including its pseudo-random generator (the
 * Park-Miller minimal standard with multiplier 48271), so the clustering is deterministic.
 */
final class HistogramImage {
    /**
     * The number of partitions per dominant cost in the entropy binning.
     */
    private static final int NUM_PARTITIONS = 4;

    /**
     * The number of entropy bins.
     */
    private static final int BIN_SIZE = NUM_PARTITIONS * NUM_PARTITIONS * NUM_PARTITIONS;

    /**
     * The cluster count below which the greedy merge takes over.
     */
    private static final int MAX_HISTO_GREEDY = 100;

    /**
     * Prevents instantiation.
     */
    private HistogramImage() {
        throw new AssertionError();
    }

    /**
     * Holds a set of histograms with a fixed backing store, like libwebp's
     * {@code VP8LHistogramSet}.
     */
    static final class HistogramSet {
        /**
         * The histograms owned by the set, in allocation order.
         */
        final Histogram[] slots;

        /**
         * The current view of the set; entries may be removed or swapped.
         */
        final Histogram[] histograms;

        /**
         * The number of histograms in the view.
         */
        int size;

        /**
         * Allocates a set.
         *
         * @param size      the number of histograms
         * @param cacheBits the color cache width
         */
        HistogramSet(int size, int cacheBits) {
            this.slots = new Histogram[size];
            this.histograms = new Histogram[size];
            for (var i = 0; i < size; i++) {
                slots[i] = new Histogram(cacheBits);
                histograms[i] = slots[i];
            }
            this.size = size;
        }

        /**
         * Restores the view to every owned histogram and zeroes them.
         */
        void clear() {
            for (var i = 0; i < slots.length; i++) {
                slots[i].clear();
                histograms[i] = slots[i];
            }
            size = slots.length;
        }

        /**
         * Removes a histogram from the view, moving the last one into its place.
         *
         * @param i the index to remove
         */
        void remove(int i) {
            histograms[i] = histograms[size - 1];
            --size;
        }
    }

    /**
     * Computes the entropy image of a parse.
     *
     * @param xsize         the image width
     * @param ysize         the image height
     * @param refs          the parse
     * @param quality       the encoder quality, from 0 to 100
     * @param lowEffort     whether to use the fastest clustering
     * @param histogramBits the tile width in bits, at least 2
     * @param cacheBits     the color cache width
     * @param imageHisto    the destination set, sized for one histogram per tile; on return its
     *                      view holds the clusters
     * @param tmpHisto      a scratch histogram
     * @param symbols       the destination of the cluster index of every tile
     * @param stats         scratch statistics
     */
    static void getSymbols(int xsize, int ysize, BackwardRefs refs, int quality, boolean lowEffort,
                           int histogramBits, int cacheBits, HistogramSet imageHisto, Histogram tmpHisto,
                           int[] symbols, EntropyMath.Stats stats) {
        var histoXSize = Vp8lDecoder.subSampleSize(xsize, histogramBits);
        var histoYSize = Vp8lDecoder.subSampleSize(ysize, histogramBits);
        var rawSize = histoXSize * histoYSize;
        var orig = new HistogramSet(rawSize, cacheBits);
        var entropyCombineNumBins = lowEffort ? NUM_PARTITIONS : BIN_SIZE;
        build(xsize, histogramBits, refs, orig);
        copyAndAnalyze(orig, imageHisto, stats);
        var entropyCombine = imageHisto.size > entropyCombineNumBins * 2 && quality < 100;
        if (entropyCombine) {
            var combineCostFactor = combineCostFactor(rawSize, quality);
            analyzeEntropyBin(imageHisto, lowEffort);
            combineEntropyBin(imageHisto, tmpHisto, entropyCombineNumBins, combineCostFactor, lowEffort, stats);
        }
        if (!lowEffort || !entropyCombine) {
            var thresholdSize = (int) (1 + EntropyMath.divRound((long) quality * quality * quality * (MAX_HISTO_GREEDY - 1),
                    100L * 100 * 100));
            if (combineStochastic(imageHisto, thresholdSize, stats)) {
                combineGreedy(imageHisto, stats);
            }
        }
        remap(orig, imageHisto, symbols, stats);
    }

    /**
     * Returns the cost factor of the entropy-bin merge.
     *
     * @param histoSize the number of tiles
     * @param quality   the encoder quality
     * @return the factor, in percent
     */
    private static int combineCostFactor(int histoSize, int quality) {
        var factor = 16;
        if (quality < 90) {
            if (histoSize > 256) {
                factor /= 2;
            }
            if (histoSize > 512) {
                factor /= 2;
            }
            if (histoSize > 1024) {
                factor /= 2;
            }
            if (quality <= 50) {
                factor /= 2;
            }
        }
        return factor;
    }

    /**
     * Counts the symbols of every tile.
     *
     * @param xsize         the image width
     * @param histogramBits the tile width in bits
     * @param refs          the parse
     * @param set           the per-tile histograms
     */
    private static void build(int xsize, int histogramBits, BackwardRefs refs, HistogramSet set) {
        var histoXSize = Vp8lDecoder.subSampleSize(xsize, histogramBits);
        set.clear();
        var histograms = set.histograms;
        var x = 0;
        var y = 0;
        var data = refs.data;
        for (var i = 0; i < refs.size; i++) {
            var v = data[i];
            histograms[(y >> histogramBits) * histoXSize + (x >> histogramBits)].add(v, 0, false);
            x += BackwardRefs.length(v);
            while (x >= xsize) {
                x -= xsize;
                ++y;
            }
        }
    }

    /**
     * Computes the cost of every tile histogram and copies the non-empty ones to the working set.
     *
     * @param orig  the per-tile histograms; empty ones are replaced by {@code null}
     * @param image the working set
     * @param stats scratch statistics
     */
    private static void copyAndAnalyze(HistogramSet orig, HistogramSet image, EntropyMath.Stats stats) {
        image.size = 0;
        for (var i = 0; i < orig.slots.length; i++) {
            var histo = orig.histograms[i];
            histo.computeCost(stats);
            if (!histo.isUsed[0] && !histo.isUsed[1] && !histo.isUsed[2] && !histo.isUsed[3] && !histo.isUsed[4]) {
                orig.histograms[i] = null;
            } else {
                image.histograms[image.size].copyFrom(histo);
                ++image.size;
            }
        }
    }

    /**
     * Returns the bin of a cost within a range.
     *
     * @param min the smallest cost
     * @param max the largest cost
     * @param val the cost
     * @return the bin, from 0 to 3
     */
    private static int binForEntropy(long min, long max, long val) {
        var range = max - min;
        if (range > 0) {
            var delta = val - min;
            return (int) ((NUM_PARTITIONS - 1e-6) * delta / range);
        }
        return 0;
    }

    /**
     * Assigns every histogram an entropy bin from its literal, red and blue costs.
     *
     * @param set       the working set
     * @param lowEffort whether only the literal cost is used
     */
    private static void analyzeEntropyBin(HistogramSet set, boolean lowEffort) {
        long literalMax = 0;
        var literalMin = Long.MAX_VALUE;
        long redMax = 0;
        var redMin = Long.MAX_VALUE;
        long blueMax = 0;
        var blueMin = Long.MAX_VALUE;
        for (var i = 0; i < set.size; i++) {
            var h = set.histograms[i];
            literalMax = Math.max(literalMax, h.costs[Histogram.LITERAL]);
            literalMin = Math.min(literalMin, h.costs[Histogram.LITERAL]);
            redMax = Math.max(redMax, h.costs[Histogram.RED]);
            redMin = Math.min(redMin, h.costs[Histogram.RED]);
            blueMax = Math.max(blueMax, h.costs[Histogram.BLUE]);
            blueMin = Math.min(blueMin, h.costs[Histogram.BLUE]);
        }
        for (var i = 0; i < set.size; i++) {
            var h = set.histograms[i];
            var binId = binForEntropy(literalMin, literalMax, h.costs[Histogram.LITERAL]);
            if (!lowEffort) {
                binId = binId * NUM_PARTITIONS + binForEntropy(redMin, redMax, h.costs[Histogram.RED]);
                binId = binId * NUM_PARTITIONS + binForEntropy(blueMin, blueMax, h.costs[Histogram.BLUE]);
            }
            h.binId = binId;
        }
    }

    /**
     * Merges histograms that share an entropy bin when that lowers their total cost.
     *
     * @param set               the working set
     * @param curCombo          a scratch histogram, swapped into the set when a merge succeeds
     * @param numBins           the number of bins
     * @param combineCostFactor the tolerated cost increase, in percent of the merged histogram
     * @param lowEffort         whether to merge unconditionally
     * @param stats             scratch statistics
     */
    private static void combineEntropyBin(HistogramSet set, Histogram curCombo, int numBins, int combineCostFactor,
                                          boolean lowEffort, EntropyMath.Stats stats) {
        var histograms = set.histograms;
        var first = new int[numBins];
        var failures = new int[numBins];
        java.util.Arrays.fill(first, -1);
        var costs = new long[5];
        for (var idx = 0; idx < set.size; ) {
            var binId = histograms[idx].binId;
            var firstIdx = first[binId];
            if (firstIdx == -1) {
                first[binId] = idx;
                ++idx;
            } else if (lowEffort) {
                Histogram.add(histograms[idx], histograms[firstIdx], histograms[firstIdx]);
                set.remove(idx);
            } else {
                var bitCost = histograms[idx].bitCost;
                var threshold = -EntropyMath.divRound(bitCost * combineCostFactor, 100);
                var a = histograms[firstIdx];
                var b = histograms[idx];
                var combined = Histogram.combinedHistogramEntropy(a, b,
                        Histogram.saturateAdd(a.bitCost + b.bitCost, threshold), costs, stats);
                if (combined >= 0) {
                    Histogram.add(a, b, curCombo);
                    curCombo.bitCost = combined;
                    System.arraycopy(costs, 0, curCombo.costs, 0, 5);
                    var tryCombine = curCombo.trivialSymbol[Histogram.RED] != EntropyMath.NON_TRIVIAL_SYM
                            && curCombo.trivialSymbol[Histogram.BLUE] != EntropyMath.NON_TRIVIAL_SYM
                            && curCombo.trivialSymbol[Histogram.ALPHA] != EntropyMath.NON_TRIVIAL_SYM;
                    if (!tryCombine) {
                        tryCombine = (b.trivialSymbol[Histogram.RED] == EntropyMath.NON_TRIVIAL_SYM
                                || b.trivialSymbol[Histogram.BLUE] == EntropyMath.NON_TRIVIAL_SYM
                                || b.trivialSymbol[Histogram.ALPHA] == EntropyMath.NON_TRIVIAL_SYM)
                                && (a.trivialSymbol[Histogram.RED] == EntropyMath.NON_TRIVIAL_SYM
                                || a.trivialSymbol[Histogram.BLUE] == EntropyMath.NON_TRIVIAL_SYM
                                || a.trivialSymbol[Histogram.ALPHA] == EntropyMath.NON_TRIVIAL_SYM);
                    }
                    if (tryCombine || failures[binId] >= 32) {
                        var swap = histograms[firstIdx];
                        histograms[firstIdx] = curCombo;
                        curCombo = swap;
                        set.remove(idx);
                    } else {
                        ++failures[binId];
                        ++idx;
                    }
                } else {
                    ++idx;
                }
            }
        }
        if (lowEffort) {
            for (var idx = 0; idx < set.size; idx++) {
                histograms[idx].computeCost(stats);
            }
        }
    }

    /**
     * Holds a candidate merge of two histograms.
     */
    private static final class Pair {
        /**
         * The smaller histogram index.
         */
        int idx1;

        /**
         * The larger histogram index.
         */
        int idx2;

        /**
         * The change of total cost the merge brings, negative when it helps.
         */
        long costDiff;

        /**
         * The cost of the merged histogram.
         */
        long costCombo;

        /**
         * The population costs of the merged histogram.
         */
        final long[] costs = new long[5];

        /**
         * Copies another pair.
         *
         * @param other the pair to copy
         */
        void copyFrom(Pair other) {
            idx1 = other.idx1;
            idx2 = other.idx2;
            costDiff = other.costDiff;
            costCombo = other.costCombo;
            System.arraycopy(other.costs, 0, costs, 0, 5);
        }
    }

    /**
     * Holds candidate merges with the best one at index 0.
     */
    private static final class Queue {
        /**
         * The pairs, with one spare entry.
         */
        final Pair[] queue;

        /**
         * The number of pairs.
         */
        int size;

        /**
         * The capacity.
         */
        final int maxSize;

        /**
         * A scratch pair used for swaps.
         */
        final Pair tmp = new Pair();

        /**
         * Allocates a queue.
         *
         * @param maxSize the capacity
         */
        Queue(int maxSize) {
            this.maxSize = maxSize;
            this.queue = new Pair[maxSize + 1];
            for (var i = 0; i <= maxSize; i++) {
                queue[i] = new Pair();
            }
        }

        /**
         * Removes a pair by moving the last pair into its place.
         *
         * @param index the index of the pair
         */
        void pop(int index) {
            queue[index].copyFrom(queue[size - 1]);
            --size;
        }

        /**
         * Moves a pair to the head when it is better than the head.
         *
         * @param index the index of the pair
         */
        void updateHead(int index) {
            if (queue[index].costDiff < queue[0].costDiff) {
                tmp.copyFrom(queue[0]);
                queue[0].copyFrom(queue[index]);
                queue[index].copyFrom(tmp);
            }
        }

        /**
         * Evaluates a merge and stores it when it lowers the cost below a threshold.
         *
         * @param histograms the histograms
         * @param idx1       the first index
         * @param idx2       the second index
         * @param threshold  the largest acceptable cost change, at most zero
         * @param stats      scratch statistics
         * @return the cost change, or {@code 0} when the pair was not queued
         */
        long push(Histogram[] histograms, int idx1, int idx2, long threshold, EntropyMath.Stats stats) {
            if (size == maxSize) {
                return 0;
            }
            if (idx1 > idx2) {
                var t = idx2;
                idx2 = idx1;
                idx1 = t;
            }
            var pair = queue[size];
            pair.idx1 = idx1;
            pair.idx2 = idx2;
            if (!updatePair(histograms[idx1], histograms[idx2], threshold, pair, stats)) {
                return 0;
            }
            ++size;
            var diff = pair.costDiff;
            updateHead(size - 1);
            return diff;
        }
    }

    /**
     * Evaluates the merge of two histograms.
     *
     * @param h1        the first histogram
     * @param h2        the second histogram
     * @param threshold the largest acceptable cost change
     * @param pair      the destination
     * @param stats     scratch statistics
     * @return whether the merged cost is below the threshold
     */
    private static boolean updatePair(Histogram h1, Histogram h2, long threshold, Pair pair, EntropyMath.Stats stats) {
        var sumCost = h1.bitCost + h2.bitCost;
        var combo = Histogram.combinedHistogramEntropy(h1, h2, Histogram.saturateAdd(sumCost, threshold), pair.costs, stats);
        if (combo < 0) {
            return false;
        }
        pair.costCombo = combo;
        pair.costDiff = combo - sumCost;
        return true;
    }

    /**
     * Rewrites the indices of a pair after a histogram moved.
     *
     * @param badId  the old index
     * @param goodId the new index
     * @param pair   the pair
     */
    private static void fixPair(int badId, int goodId, Pair pair) {
        if (pair.idx1 == badId) {
            pair.idx1 = goodId;
        }
        if (pair.idx2 == badId) {
            pair.idx2 = goodId;
        }
        if (pair.idx1 > pair.idx2) {
            var t = pair.idx1;
            pair.idx1 = pair.idx2;
            pair.idx2 = t;
        }
    }

    /**
     * Merges histogram {@code idx2} into {@code idx1} with the costs of a pair.
     *
     * @param set  the working set
     * @param pair the evaluated merge
     */
    private static void merge(HistogramSet set, Pair pair) {
        var histograms = set.histograms;
        Histogram.add(histograms[pair.idx2], histograms[pair.idx1], histograms[pair.idx1]);
        histograms[pair.idx1].bitCost = pair.costCombo;
        System.arraycopy(pair.costs, 0, histograms[pair.idx1].costs, 0, 5);
    }

    /**
     * Merges pairs of randomly chosen histograms while it lowers the cost.
     *
     * @param set            the working set
     * @param minClusterSize the cluster count at which to stop
     * @param stats          scratch statistics
     * @return whether the greedy merge should follow
     */
    private static boolean combineStochastic(HistogramSet set, int minClusterSize, EntropyMath.Stats stats) {
        if (set.size < minClusterSize) {
            return true;
        }
        var seed = 1L;
        var triesWithNoSuccess = 0;
        var outerIters = set.size;
        var numTriesNoSuccess = outerIters / 2;
        var histograms = set.histograms;
        var queue = new Queue(9);
        for (var iter = 0; iter < outerIters && set.size >= minClusterSize && ++triesWithNoSuccess < numTriesNoSuccess; iter++) {
            var bestCost = queue.size == 0 ? 0 : queue.queue[0].costDiff;
            var randRange = (long) (set.size - 1) * set.size;
            var numTries = set.size / 2;
            for (var j = 0; set.size >= 2 && j < numTries; j++) {
                seed = (seed * 48271L) % 2147483647L;
                var tmp = seed % randRange;
                var idx1 = (int) (tmp / (set.size - 1));
                var idx2 = (int) (tmp % (set.size - 1));
                if (idx2 >= idx1) {
                    ++idx2;
                }
                var currCost = queue.push(histograms, idx1, idx2, bestCost, stats);
                if (currCost < 0) {
                    bestCost = currCost;
                    if (queue.size == queue.maxSize) {
                        break;
                    }
                }
            }
            if (queue.size == 0) {
                continue;
            }
            var bestIdx1 = queue.queue[0].idx1;
            var bestIdx2 = queue.queue[0].idx2;
            merge(set, queue.queue[0]);
            set.remove(bestIdx2);
            for (var j = 0; j < queue.size; ) {
                var p = queue.queue[j];
                var isIdx1Best = p.idx1 == bestIdx1 || p.idx1 == bestIdx2;
                var isIdx2Best = p.idx2 == bestIdx1 || p.idx2 == bestIdx2;
                if (isIdx1Best && isIdx2Best) {
                    queue.pop(j);
                    continue;
                }
                if (isIdx1Best || isIdx2Best) {
                    fixPair(bestIdx2, bestIdx1, p);
                    if (!updatePair(histograms[p.idx1], histograms[p.idx2], 0, p, stats)) {
                        queue.pop(j);
                        continue;
                    }
                }
                fixPair(set.size, bestIdx2, p);
                queue.updateHead(j);
                ++j;
            }
            triesWithNoSuccess = 0;
        }
        return set.size <= minClusterSize;
    }

    /**
     * Merges the best pair of histograms while merging lowers the cost.
     *
     * @param set   the working set
     * @param stats scratch statistics
     */
    private static void combineGreedy(HistogramSet set, EntropyMath.Stats stats) {
        var imageHistoSize = set.size;
        var histograms = set.histograms;
        var queue = new Queue(imageHistoSize * imageHistoSize);
        for (var i = 0; i < imageHistoSize; i++) {
            for (var j = i + 1; j < imageHistoSize; j++) {
                queue.push(histograms, i, j, 0, stats);
            }
        }
        while (queue.size > 0) {
            var idx1 = queue.queue[0].idx1;
            var idx2 = queue.queue[0].idx2;
            merge(set, queue.queue[0]);
            set.remove(idx2);
            for (var i = 0; i < queue.size; ) {
                var p = queue.queue[i];
                if (p.idx1 == idx1 || p.idx2 == idx1 || p.idx1 == idx2 || p.idx2 == idx2) {
                    queue.pop(i);
                } else {
                    fixPair(set.size, idx2, p);
                    queue.updateHead(i);
                    ++i;
                }
            }
            for (var i = 0; i < set.size; i++) {
                if (i == idx1) {
                    continue;
                }
                queue.push(set.histograms, idx1, i, 0, stats);
            }
        }
    }

    /**
     * The work of one tile-cluster pair of {@link #remap}, in the scheduler's output samples.
     */
    private static final int REMAP_WORK_PER_PAIR = 256;

    /**
     * Maps a range of non-empty tiles to the cluster that codes each cheapest; the symbols of
     * empty tiles are left untouched.
     *
     * @param inHisto the per-tile histograms, {@code null} for empty tiles
     * @param out     the clusters
     * @param symbols the destination of the cluster index of every tile
     * @param from    the first tile
     * @param to      the end of the tiles
     * @param costs   scratch costs
     * @param stats   scratch statistics
     */
    private static void nearest(Histogram[] inHisto, HistogramSet out, int[] symbols, int from, int to, long[] costs,
                                EntropyMath.Stats stats) {
        var outSize = out.size;
        for (var i = from; i < to; i++) {
            if (inHisto[i] == null) {
                continue;
            }
            var bestOut = 0;
            var bestBits = Long.MAX_VALUE;
            for (var k = 0; k < outSize; k++) {
                var a = out.histograms[k];
                var cost = Histogram.combinedHistogramEntropy(a, inHisto[i], Histogram.saturateAdd(a.bitCost, bestBits), costs, stats);
                if (cost >= 0) {
                    bestBits = cost - a.bitCost;
                    bestOut = k;
                }
            }
            symbols[i] = bestOut;
        }
    }

    /**
     * Maps every tile to the cluster that codes it cheapest and recomputes the clusters from the
     * tiles mapped to them.
     *
     * @param in      the per-tile histograms, {@code null} for empty tiles
     * @param out     the clusters
     * @param symbols the destination of the cluster index of every tile
     * @param stats   scratch statistics
     */
    private static void remap(HistogramSet in, HistogramSet out, int[] symbols, EntropyMath.Stats stats) {
        var inHisto = in.histograms;
        var inSize = in.slots.length;
        var outSize = out.size;
        if (outSize > 1) {
            var bands = ParallelBands.bands(inSize, (long) inSize * outSize * REMAP_WORK_PER_PAIR);
            if (bands > 1) {
                ParallelBands.run(bands, band -> nearest(inHisto, out, symbols, ParallelBands.start(band, bands, inSize),
                        ParallelBands.start(band + 1, bands, inSize), new long[5], new EntropyMath.Stats()));
            } else {
                nearest(inHisto, out, symbols, 0, inSize, new long[5], stats);
            }
            for (var i = 0; i < inSize; i++) {
                if (inHisto[i] == null) {
                    symbols[i] = symbols[i - 1];
                }
            }
        } else {
            for (var i = 0; i < inSize; i++) {
                symbols[i] = 0;
            }
        }
        out.clear();
        out.size = outSize;
        for (var i = 0; i < inSize; i++) {
            if (inHisto[i] == null) {
                continue;
            }
            var idx = symbols[i];
            Histogram.add(inHisto[i], out.histograms[idx], out.histograms[idx]);
        }
    }
}
