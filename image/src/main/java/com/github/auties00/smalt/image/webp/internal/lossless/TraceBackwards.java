package com.github.auties00.smalt.image.webp.internal.lossless;

/**
 * Refines an LZ77 parse with a shortest-path search: the cost of reaching every pixel is
 * minimized over literals, cache hits and the copies of the hash chain, with symbol costs taken
 * from the entropy of an initial parse, and the cheapest path is traced back from the last pixel.
 *
 * @implNote This implementation follows libwebp's {@code src/enc/backward_references_cost_enc.c}
 * (BSD-3): the literal and cache costs are weighted by 82% and 68%, copy costs are stored as
 * intervals over the positions they reach (at most 500 intervals are kept, longer runs are
 * applied immediately), and consecutive pixels sharing a copy offset are processed in one step.
 */
final class TraceBackwards {
    /**
     * The largest number of cost intervals kept.
     */
    private static final int COST_CACHE_INTERVAL_SIZE_MAX = 500;

    /**
     * The copy length below which costs are applied directly instead of as intervals.
     */
    private static final int SKIP_DISTANCE = 10;

    /**
     * The per-symbol bit costs, derived from a parse.
     */
    private final int[] alpha = new int[256];

    /**
     * The red literal costs.
     */
    private final int[] red = new int[256];

    /**
     * The blue literal costs.
     */
    private final int[] blue = new int[256];

    /**
     * The distance prefix costs.
     */
    private final int[] distance = new int[Histogram.NUM_DISTANCE_CODES];

    /**
     * The green literal, length prefix and cache index costs.
     */
    private final int[] literal;

    /**
     * The cost of each copy length below {@link HashChain#MAX_LENGTH}.
     */
    private final long[] costCache;

    /**
     * The start of each run of equal length costs.
     */
    private int[] cacheIntervalStart;

    /**
     * The exclusive end of each run of equal length costs.
     */
    private int[] cacheIntervalEnd;

    /**
     * The cost of each run of equal length costs.
     */
    private long[] cacheIntervalCost;

    /**
     * The number of runs of equal length costs.
     */
    private int cacheIntervalsSize;

    /**
     * The best cost of reaching each pixel.
     */
    private final long[] costs;

    /**
     * The length of the last step of the best path to each pixel.
     */
    private final int[] distArray;

    /**
     * The head of the interval list, sorted by start.
     */
    private Interval head;

    /**
     * The number of intervals in the list.
     */
    private int count;

    /**
     * Recycled intervals.
     */
    private Interval free;

    /**
     * Constructs the search state.
     *
     * @param pixCount  the number of pixels
     * @param cacheBits the color cache width
     */
    private TraceBackwards(int pixCount, int cacheBits) {
        this.literal = new int[Histogram.numCodes(cacheBits)];
        this.costCache = new long[HashChain.MAX_LENGTH];
        this.costs = new long[pixCount];
        this.distArray = new int[pixCount];
    }

    /**
     * Computes a refined parse.
     *
     * @param xsize     the image width
     * @param ysize     the image height
     * @param argb      the pixels
     * @param cacheBits the color cache width, or {@code 0}
     * @param chain     the hash chain whose matches are considered
     * @param refsSrc   the initial parse, whose statistics define the costs
     * @param refsDst   the destination
     */
    static void run(int xsize, int ysize, int[] argb, int cacheBits, HashChain chain, BackwardRefs refsSrc, BackwardRefs refsDst) {
        var pixCount = xsize * ysize;
        var search = new TraceBackwards(pixCount, cacheBits);
        search.distanceOnly(xsize, ysize, argb, cacheBits, chain, refsSrc);
        var distArray = search.distArray;
        var pathSize = 0;
        for (var cur = pixCount - 1; cur >= 0; cur -= distArray[cur]) {
            ++pathSize;
        }
        var path = new int[pathSize];
        var p = pathSize;
        for (var cur = pixCount - 1; cur >= 0; cur -= distArray[cur]) {
            path[--p] = distArray[cur];
        }
        followChosenPath(argb, cacheBits, path, chain, refsDst);
    }

    /**
     * Converts counts to bit costs: {@code log2(sum) - log2(count)} in fixed point.
     *
     * @param numSymbols the number of symbols
     * @param counts     the counts
     * @param output     the destination costs
     */
    private static void toBitEstimates(int numSymbols, int[] counts, int[] output) {
        var sum = 0;
        var nonzeros = 0;
        for (var i = 0; i < numSymbols; i++) {
            sum += counts[i];
            if (counts[i] > 0) {
                ++nonzeros;
            }
        }
        if (nonzeros <= 1) {
            java.util.Arrays.fill(output, 0, numSymbols, 0);
            return;
        }
        var logSum = EntropyMath.fastLog2(sum);
        for (var i = 0; i < numSymbols; i++) {
            output[i] = logSum - EntropyMath.fastLog2(counts[i]);
        }
    }

    /**
     * Builds the cost model from the statistics of a parse.
     *
     * @param xsize     the image width
     * @param cacheBits the color cache width
     * @param refs      the parse
     */
    private void buildCostModel(int xsize, int cacheBits, BackwardRefs refs) {
        var histo = new Histogram(cacheBits);
        histo.storeRefs(refs, xsize, true);
        toBitEstimates(literal.length, histo.literal, literal);
        toBitEstimates(256, histo.red, red);
        toBitEstimates(256, histo.blue, blue);
        toBitEstimates(256, histo.alpha, alpha);
        toBitEstimates(Histogram.NUM_DISTANCE_CODES, histo.distance, distance);
    }

    /**
     * Returns the cost of a literal pixel.
     *
     * @param v the pixel
     * @return the fixed-point cost
     */
    private long literalCost(int v) {
        return (long) alpha[v >>> 24] + red[(v >>> 16) & 0xFF] + literal[(v >>> 8) & 0xFF] + blue[v & 0xFF];
    }

    /**
     * Returns the cost of a copy length.
     *
     * @param length the length
     * @return the fixed-point cost
     */
    private long lengthCost(int length) {
        return (long) literal[256 + Prefix.code(length)] + ((long) Prefix.extraBits(length) << EntropyMath.LOG_2_PRECISION_BITS);
    }

    /**
     * Returns the cost of a distance code.
     *
     * @param code the distance code
     * @return the fixed-point cost
     */
    private long distanceCost(int code) {
        return (long) distance[Prefix.code(code)] + ((long) Prefix.extraBits(code) << EntropyMath.LOG_2_PRECISION_BITS);
    }

    /**
     * Offers a literal (or cache hit) as the way to reach a pixel.
     *
     * @param argb     the pixels
     * @param cache    the color cache, or {@code null}
     * @param idx      the pixel position
     * @param prevCost the cost of reaching the previous pixel
     */
    private void addSingleLiteral(int[] argb, ColorCache cache, int idx, long prevCost) {
        var costVal = prevCost;
        var color = argb[idx];
        var ix = cache != null ? cache.contains(color) : -1;
        if (ix >= 0) {
            costVal += EntropyMath.divRound(literal[256 + 24 + ix] * 68L, 100);
        } else {
            if (cache != null) {
                cache.insert(color);
            }
            costVal += EntropyMath.divRound(literalCost(color) * 82, 100);
        }
        if (costs[idx] > costVal) {
            costs[idx] = costVal;
            distArray[idx] = 1;
        }
    }

    /**
     * Prepares the length cost cache and its runs of equal cost.
     *
     * @param pixCount the number of pixels
     */
    private void initCostManager(int pixCount) {
        var cacheSize = Math.min(pixCount, HashChain.MAX_LENGTH);
        for (var i = 0; i < cacheSize; i++) {
            costCache[i] = lengthCost(i);
        }
        var runs = 1;
        for (var i = 1; i < cacheSize; i++) {
            if (costCache[i] != costCache[i - 1]) {
                ++runs;
            }
        }
        cacheIntervalStart = new int[runs];
        cacheIntervalEnd = new int[runs];
        cacheIntervalCost = new long[runs];
        cacheIntervalsSize = runs;
        var cur = 0;
        cacheIntervalStart[0] = 0;
        cacheIntervalEnd[0] = 1;
        cacheIntervalCost[0] = costCache[0];
        for (var i = 1; i < cacheSize; i++) {
            var costVal = costCache[i];
            if (costVal != cacheIntervalCost[cur]) {
                ++cur;
                cacheIntervalStart[cur] = i;
                cacheIntervalCost[cur] = costVal;
            }
            cacheIntervalEnd[cur] = i + 1;
        }
        java.util.Arrays.fill(costs, Long.MAX_VALUE);
    }

    /**
     * Records a cheaper way to reach pixel {@code i} with a copy starting at {@code position}.
     *
     * @param i        the pixel reached
     * @param position the first pixel of the copy
     * @param cost     the cost of the path through the copy
     */
    private void updateCost(int i, int position, long cost) {
        var k = i - position;
        if (costs[i] > cost) {
            costs[i] = cost;
            distArray[i] = k + 1;
        }
    }

    /**
     * Applies an interval of equal copy cost to every pixel it covers.
     *
     * @param start    the first pixel
     * @param end      the pixel after the last
     * @param position the first pixel of the copy
     * @param cost     the cost
     */
    private void updateCostPerInterval(int start, int end, int position, long cost) {
        for (var i = start; i < end; i++) {
            updateCost(i, position, cost);
        }
    }

    /**
     * Links two intervals.
     *
     * @param prev the first interval, or {@code null} to make {@code next} the head
     * @param next the second interval, or {@code null}
     */
    private void connect(Interval prev, Interval next) {
        if (prev != null) {
            prev.next = next;
        } else {
            head = next;
        }
        if (next != null) {
            next.previous = prev;
        }
    }

    /**
     * Removes an interval from the list and recycles it.
     *
     * @param interval the interval, or {@code null}
     */
    private void pop(Interval interval) {
        if (interval == null) {
            return;
        }
        connect(interval.previous, interval.next);
        interval.next = free;
        free = interval;
        --count;
    }

    /**
     * Applies the intervals covering pixel {@code i}, optionally dropping the ones that ended.
     *
     * @param i                the pixel
     * @param doCleanIntervals whether intervals ending at or before {@code i} are removed
     */
    private void updateCostAtIndex(int i, boolean doCleanIntervals) {
        var current = head;
        while (current != null && current.start <= i) {
            var next = current.next;
            if (current.end <= i) {
                if (doCleanIntervals) {
                    pop(current);
                }
            } else {
                updateCost(i, current.index, current.cost);
            }
            current = next;
        }
    }

    /**
     * Inserts an interval into the sorted list, searching from a nearby interval.
     *
     * @param current  the interval to insert
     * @param previous an interval near the insertion point, or {@code null}
     */
    private void positionOrphan(Interval current, Interval previous) {
        if (previous == null) {
            previous = head;
        }
        while (previous != null && current.start < previous.start) {
            previous = previous.previous;
        }
        while (previous != null && previous.next != null && previous.next.start < current.start) {
            previous = previous.next;
        }
        if (previous != null) {
            connect(current, previous.next);
        } else {
            connect(current, head);
        }
        connect(previous, current);
    }

    /**
     * Adds an interval of equal copy cost, or applies it directly when the list is full.
     *
     * @param near     an interval near the insertion point, or {@code null}
     * @param cost     the cost
     * @param position the first pixel of the copy
     * @param start    the first pixel covered
     * @param end      the pixel after the last covered
     */
    private void insertInterval(Interval near, long cost, int position, int start, int end) {
        if (start >= end) {
            return;
        }
        if (count >= COST_CACHE_INTERVAL_SIZE_MAX) {
            updateCostPerInterval(start, end, position, cost);
            return;
        }
        Interval interval;
        if (free != null) {
            interval = free;
            free = interval.next;
        } else {
            interval = new Interval();
        }
        interval.cost = cost;
        interval.index = position;
        interval.start = start;
        interval.end = end;
        interval.previous = null;
        interval.next = null;
        positionOrphan(interval, near);
        ++count;
    }

    /**
     * Offers every copy of a match as a way to reach the pixels it covers.
     *
     * @param distanceCost the cost of the path up to the copy plus its distance
     * @param position     the first pixel of the copy
     * @param len          the match length
     */
    private void pushInterval(long distanceCost, int position, int len) {
        if (len < SKIP_DISTANCE) {
            for (var j = position; j < position + len; j++) {
                var k = j - position;
                var costTmp = distanceCost + costCache[k];
                if (costs[j] > costTmp) {
                    costs[j] = costTmp;
                    distArray[j] = k + 1;
                }
            }
            return;
        }
        var interval = head;
        for (var i = 0; i < cacheIntervalsSize && cacheIntervalStart[i] < len; i++) {
            var start = position + cacheIntervalStart[i];
            var end = position + Math.min(cacheIntervalEnd[i], len);
            var cost = distanceCost + cacheIntervalCost[i];
            Interval next;
            for (; interval != null && interval.start < end; interval = next) {
                next = interval.next;
                if (start >= interval.end) {
                    continue;
                }
                if (cost >= interval.cost) {
                    var startNew = interval.end;
                    insertInterval(interval, cost, position, start, interval.start);
                    start = startNew;
                    if (start >= end) {
                        break;
                    }
                    continue;
                }
                if (start <= interval.start) {
                    if (interval.end <= end) {
                        pop(interval);
                    } else {
                        interval.start = end;
                        break;
                    }
                } else {
                    if (end < interval.end) {
                        var endOriginal = interval.end;
                        interval.end = start;
                        insertInterval(interval, interval.cost, interval.index, end, endOriginal);
                        interval = interval.next;
                        break;
                    } else {
                        interval.end = start;
                    }
                }
            }
            insertInterval(interval, cost, position, start, end);
        }
    }

    /**
     * Computes the best cost of reaching every pixel and the last step of each best path.
     *
     * @param xsize     the image width
     * @param ysize     the image height
     * @param argb      the pixels
     * @param cacheBits the color cache width, or {@code 0}
     * @param chain     the matches considered
     * @param refs      the initial parse defining the cost model
     */
    private void distanceOnly(int xsize, int ysize, int[] argb, int cacheBits, HashChain chain, BackwardRefs refs) {
        var pixCount = xsize * ysize;
        var cache = cacheBits > 0 ? new ColorCache(cacheBits) : null;
        buildCostModel(xsize, cacheBits, refs);
        initCostManager(pixCount);
        var offsetPrev = -1;
        var lenPrev = -1;
        long offsetCost = -1;
        var firstOffsetIsConstant = -1;
        var reach = 0;
        distArray[0] = 0;
        addSingleLiteral(argb, cache, 0, 0);
        for (var i = 1; i < pixCount; i++) {
            var prevCost = costs[i - 1];
            var offset = chain.offset(i);
            var len = chain.length(i);
            addSingleLiteral(argb, cache, i, prevCost);
            if (len >= 2) {
                if (offset != offsetPrev) {
                    var code = Prefix.distanceToPlaneCode(xsize, offset);
                    offsetCost = distanceCost(code);
                    firstOffsetIsConstant = 1;
                    pushInterval(prevCost + offsetCost, i, len);
                } else {
                    if (firstOffsetIsConstant != 0) {
                        reach = i - 1 + lenPrev - 1;
                        firstOffsetIsConstant = 0;
                    }
                    if (i + len - 1 > reach) {
                        var lenJ = 0;
                        int j;
                        for (j = i; j <= reach; j++) {
                            var offsetJ = chain.offset(j + 1);
                            lenJ = chain.length(j + 1);
                            if (offsetJ != offset) {
                                lenJ = chain.length(j);
                                break;
                            }
                        }
                        updateCostAtIndex(j - 1, false);
                        updateCostAtIndex(j, false);
                        pushInterval(costs[j - 1] + offsetCost, j, lenJ);
                        reach = j + lenJ - 1;
                    }
                }
            }
            updateCostAtIndex(i, true);
            offsetPrev = offset;
            lenPrev = len;
        }
    }

    /**
     * Rebuilds a parse from the chosen path.
     *
     * @param argb      the pixels
     * @param cacheBits the color cache width, or {@code 0}
     * @param path      the length of every step of the path
     * @param chain     the matches, giving each copy's distance
     * @param refs      the destination
     */
    private static void followChosenPath(int[] argb, int cacheBits, int[] path, HashChain chain, BackwardRefs refs) {
        var cache = cacheBits > 0 ? new ColorCache(cacheBits) : null;
        refs.clear();
        var i = 0;
        for (var len : path) {
            if (len != 1) {
                var offset = chain.offset(i);
                refs.add(BackwardRefs.copy(offset, len));
                if (cache != null) {
                    for (var k = 0; k < len; k++) {
                        cache.insert(argb[i + k]);
                    }
                }
                i += len;
            } else {
                var idx = cache != null ? cache.contains(argb[i]) : -1;
                if (idx >= 0) {
                    refs.add(BackwardRefs.cacheIdx(idx));
                } else {
                    if (cache != null) {
                        cache.insert(argb[i]);
                    }
                    refs.add(BackwardRefs.literal(argb[i]));
                }
                ++i;
            }
        }
    }

    /**
     * Holds one interval of pixels reachable by the same copy at the same cost.
     */
    private static final class Interval {
        /**
         * The cost.
         */
        long cost;

        /**
         * The first pixel covered.
         */
        int start;

        /**
         * The pixel after the last covered.
         */
        int end;

        /**
         * The first pixel of the copy.
         */
        int index;

        /**
         * The previous interval in start order.
         */
        Interval previous;

        /**
         * The next interval in start order.
         */
        Interval next;
    }
}
