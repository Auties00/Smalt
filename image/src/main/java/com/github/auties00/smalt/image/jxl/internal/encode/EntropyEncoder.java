/* Ports libjxl 0.11.2 (lib/jxl/enc_ans.cc, enc_cluster.cc, enc_context_map.cc and enc_huffman.cc: histogram
   normalization and storage, ANS coding, hybrid integer configuration search, histogram clustering, context map
   coding, prefix code storage, ApplyLZ77_RLE). Copyright the JPEG XL Project Authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.encode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Codes token streams with clustered ANS or prefix-code distributions (ISO/IEC 18181-1 clause C).
 *
 * <p>An encoder is built from every token stream that shares its distributions, writes the distributions once
 * with {@link #writeHeader(JxlBitWriter)} and then each stream with {@link #writeTokens(Tokens, JxlBitWriter)}.
 * Instances are not thread-safe.
 */
public final class EntropyEncoder {
    private static final int LOG_TABLE = 12;

    private static final int TABLE_SIZE = 1 << LOG_TABLE;

    private static final int CLUSTERS_LIMIT = 128;

    private static final int ANS_MAX_ALPHABET = 256;

    private static final int PREFIX_MAX_ALPHABET = 4096;

    private static final int PREFIX_MAX_BITS = 15;

    private static final int ANS_SIGNATURE = 0x13;

    /**
     * The first token of an LZ77 copy length.
     */
    private static final int LZ77_MIN_SYMBOL = 224;

    /**
     * The shortest LZ77 copy.
     */
    private static final int LZ77_MIN_LENGTH = 3;

    /**
     * The hybrid integer configuration of LZ77 copy lengths.
     */
    private static final int[] LZ77_LENGTH_CONFIG = {0, 0, 0};

    /**
     * The longest run coded as one copy, which keeps every length token in an ANS alphabet.
     */
    private static final int LZ77_MAX_RUN = 1 << 20;

    /**
     * The special distance (1, 0) of the LZ77 distance table: the value just before the copy.
     */
    private static final int LZ77_PREVIOUS = 1;

    private static final int[] LOG_COUNT_BIT_LENGTHS = {5, 4, 4, 4, 4, 4, 3, 3, 3, 3, 3, 6, 7, 7};

    private static final int[] LOG_COUNT_SYMBOLS = {17, 11, 15, 3, 9, 7, 4, 2, 5, 6, 0, 33, 1, 65};

    private static final int[] CODE_LENGTH_ORDER = {1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    private static final int[] CODE_LENGTH_LENGTH_SYMBOLS = {0, 7, 3, 2, 1, 15};

    private static final int[] CODE_LENGTH_LENGTH_BITS = {2, 4, 3, 2, 2, 4};

    private static final int[][] BEST_CONFIGS = {
            {4, 2, 0}, {4, 1, 0}, {4, 2, 1}, {4, 2, 2}, {4, 1, 2},
            {5, 2, 0}, {5, 1, 0}, {5, 2, 1}, {5, 2, 2}, {5, 1, 2},
            {3, 2, 0}, {3, 1, 0}, {3, 2, 1}, {3, 1, 2},
            {4, 1, 3}, {5, 1, 4}, {5, 2, 3}, {6, 1, 5}, {6, 2, 4}, {6, 0, 0},
            {0, 0, 0}, {2, 0, 1}, {7, 0, 0}, {8, 0, 0}, {9, 0, 0}, {10, 0, 0}, {11, 0, 0}, {12, 0, 0}};

    private static final int[][] FAST_CONFIGS = {{4, 2, 0}, {4, 1, 2}, {0, 0, 0}, {2, 0, 1}};

    /**
     * How hard the encoder searches for good distributions.
     *
     * @param clustering 0 for at most 4 clusters, 1 for greedy clustering, 2 to also merge clusters by cost
     * @param uintMethod 0 for the default hybrid integer configuration, 1 for (0, 0, 0), 2 to try a few, 3 for
     * the context map configuration, 4 to try all
     * @param histogramStrategy 0 to try 3 histogram precisions, 1 to try 7, 2 to try all 13
     * @param maxHistograms the largest number of clusters
     * @param forcePrefix whether to use prefix codes instead of ANS
     * @param runs whether runs of a repeated value may be coded as LZ77 copies of the previous value; only for
     * streams whose decoder has a nonzero distance multiplier, as modular image streams do
     */
    public record Params(int clustering, int uintMethod, int histogramStrategy, int maxHistograms,
                         boolean forcePrefix, boolean runs) {
        /**
         * Creates parameters that code no LZ77 runs.
         *
         * @param clustering 0 for at most 4 clusters, 1 for greedy clustering, 2 to also merge clusters by cost
         * @param uintMethod 0 for the default hybrid integer configuration, 1 for (0, 0, 0), 2 to try a few, 3
         * for the context map configuration, 4 to try all
         * @param histogramStrategy 0 to try 3 histogram precisions, 1 to try 7, 2 to try all 13
         * @param maxHistograms the largest number of clusters
         * @param forcePrefix whether to use prefix codes instead of ANS
         */
        public Params(int clustering, int uintMethod, int histogramStrategy, int maxHistograms,
                      boolean forcePrefix) {
            this(clustering, uintMethod, histogramStrategy, maxHistograms, forcePrefix, false);
        }

        /**
         * The most thorough parameters.
         */
        public static final Params BEST = new Params(2, 4, 2, CLUSTERS_LIMIT, false);

        /**
         * The parameters of context maps and other small streams.
         */
        static final Params CONTEXT_MAP = new Params(2, 3, 2, CLUSTERS_LIMIT, false);

        /**
         * Returns the parameters libjxl uses for an encoder effort from 1 to 10, with LZ77 runs allowed in modular
         * streams at every effort.
         *
         * @param effort the encoder effort, from 1 to 10
         * @param modular whether the streams are modular residuals
         * @return the parameters
         */
        public static Params forEffort(int effort, boolean modular) {
            if (effort <= 2) {
                return new Params(0, 0, 0, CLUSTERS_LIMIT, false, modular);
            }
            if (effort <= 7) {
                return new Params(1, modular && effort >= 5 ? 2 : 0, effort <= 3 ? 0 : 1, CLUSTERS_LIMIT, false,
                        modular);
            }
            return new Params(effort >= 9 ? 2 : 1, 4, effort >= 9 ? 2 : 1, CLUSTERS_LIMIT, false, modular);
        }
    }

    private final int numContexts;

    private final int[] contextMap;

    private final int numClusters;

    private final boolean prefix;

    private final int[][] configs;

    private final int[][] freq;

    private final int[][] start;

    private final int[][] reverse;

    private final int[][] depth;

    private final int[][] codes;

    private final JxlBitWriter header;

    private final long cost;

    /**
     * The streams as rewritten with LZ77 copies, by the stream they were built from, or {@code null} when the
     * distributions code no copies.
     */
    private final IdentityHashMap<Tokens, Tokens> rewritten;

    private EntropyEncoder(int numContexts, int[] contextMap, int numClusters, boolean prefix, int[][] configs,
                           int[][] freq, int[][] start, int[][] reverse, int[][] depth, int[][] codes,
                           JxlBitWriter header, long cost, IdentityHashMap<Tokens, Tokens> rewritten) {
        this.numContexts = numContexts;
        this.contextMap = contextMap;
        this.numClusters = numClusters;
        this.prefix = prefix;
        this.configs = configs;
        this.freq = freq;
        this.start = start;
        this.reverse = reverse;
        this.depth = depth;
        this.codes = codes;
        this.header = header;
        this.cost = cost;
        this.rewritten = rewritten;
    }

    /**
     * Builds the distributions of a single token stream.
     *
     * @param tokens the stream
     * @param numContexts the number of contexts, at least 1
     * @param params the search parameters
     * @return the encoder
     */
    public static EntropyEncoder build(Tokens tokens, int numContexts, Params params) {
        return build(List.of(tokens), numContexts, params);
    }

    /**
     * Builds the distributions shared by several token streams.
     *
     * @param streams the token streams to code
     * @param numContexts the number of contexts, at least 1
     * @param params the search parameters
     * @return the encoder
     */
    public static EntropyEncoder build(List<Tokens> streams, int numContexts, Params params) {
        if (numContexts < 1) {
            throw new IllegalArgumentException("no contexts");
        }
        var defaultConfig = switch (params.uintMethod()) {
            case 1 -> new int[]{0, 0, 0};
            case 3 -> new int[]{2, 0, 1};
            default -> new int[]{4, 2, 0};
        };
        var histograms = histograms(streams, numContexts, defaultConfig);
        IdentityHashMap<Tokens, Tokens> rewritten = null;
        if (params.runs()) {
            var runs = runs(streams, numContexts, histograms, defaultConfig,
                    params.forcePrefix() || params.clustering() == 0);
            if (runs != null) {
                rewritten = new IdentityHashMap<>();
                for (var i = 0; i < streams.size(); i++) {
                    rewritten.put(streams.get(i), runs.get(i));
                }
                streams = runs;
                numContexts++;
                histograms = histograms(streams, numContexts, defaultConfig);
            }
        }
        long totalTokens = 0;
        for (var stream : streams) {
            totalTokens += stream.size();
        }
        var usePrefix = params.forcePrefix() || totalTokens < 100 || params.clustering() == 0;
        if (!usePrefix) {
            var allSingleton = true;
            for (var h : histograms) {
                if (h.computeEntropy() >= 1e-5) {
                    allSingleton = false;
                    break;
                }
            }
            usePrefix = allSingleton;
        }
        var header = new JxlBitWriter(64);
        header.writeBool(rewritten != null);
        if (rewritten != null) {
            header.writeU32(LZ77_MIN_SYMBOL, 224, 0, 512, 0, 4096, 0, 8, 15);
            header.writeU32(LZ77_MIN_LENGTH, 3, 0, 4, 0, 5, 2, 9, 8);
            writeConfig(LZ77_LENGTH_CONFIG, header, 8);
        }
        int[] contextMap;
        List<Histogram> clustered;
        if (numContexts > 1) {
            var symbols = new int[numContexts];
            clustered = cluster(params, histograms, symbols);
            contextMap = symbols;
            writeContextMap(contextMap, clustered.size(), header);
        } else {
            contextMap = new int[1];
            clustered = new ArrayList<>(List.of(histograms[0]));
        }
        var numClusters = clustered.size();
        var configs = chooseConfigs(params, streams, contextMap, clustered, usePrefix);
        for (var h : clustered) {
            h.clear();
        }
        var logAlpha = 4;
        for (var stream : streams) {
            for (var i = 0; i < stream.size(); i++) {
                var cluster = contextMap[stream.context(i)];
                var t = symbol(stream, i, configs[cluster]);
                clustered.get(cluster).add(t);
                while (t >= 1 << logAlpha) {
                    logAlpha++;
                }
            }
        }
        if (!usePrefix && logAlpha > 8) {
            throw new IllegalStateException("token alphabet too large for ANS");
        }
        logAlpha = Math.max(logAlpha, 5);
        header.write(1, usePrefix ? 1 : 0);
        if (usePrefix) {
            logAlpha = PREFIX_MAX_BITS;
        } else {
            header.write(2, logAlpha - 5);
        }
        for (var config : configs) {
            writeConfig(config, header, logAlpha);
        }
        var freq = new int[numClusters][];
        var start = new int[numClusters][];
        var reverse = new int[numClusters][];
        var depth = new int[numClusters][];
        var codes = new int[numClusters][];
        long cost = 0;
        if (usePrefix) {
            for (var h : clustered) {
                storeVarLenUint16(h.alphabetSize() - 1, header);
            }
            for (var c = 0; c < numClusters; c++) {
                var h = clustered.get(c);
                var size = h.alphabetSize();
                depth[c] = new int[size];
                codes[c] = new int[size];
                if (size > 1) {
                    storeHuffmanCode(h.data, size, depth[c], codes[c], header);
                }
                for (var s = 0; s < size; s++) {
                    cost += (long) h.data[s] * depth[c][s];
                }
            }
        } else {
            for (var c = 0; c < numClusters; c++) {
                var h = clustered.get(c);
                var size = h.alphabetSize();
                var counts = Arrays.copyOf(h.data, size);
                var result = storeAnsHistogram(counts, size, logAlpha, params.histogramStrategy(), header);
                freq[c] = result.freq;
                start[c] = result.start;
                reverse[c] = result.reverse;
                cost += (long) result.cost;
            }
        }
        cost += header.bitsWritten();
        return new EntropyEncoder(rewritten == null ? numContexts : numContexts - 1, contextMap, numClusters,
                usePrefix, configs, freq, start, reverse, depth, codes, header, cost, rewritten);
    }

    private static Histogram[] histograms(List<Tokens> streams, int numContexts, int[] config) {
        var histograms = new Histogram[numContexts];
        for (var i = 0; i < numContexts; i++) {
            histograms[i] = new Histogram();
        }
        for (var stream : streams) {
            for (var i = 0; i < stream.size(); i++) {
                histograms[stream.context(i)].add(symbol(stream, i, config));
            }
        }
        return histograms;
    }

    /**
     * Rewrites runs of a value repeating the value before them as LZ77 copies of the previous value, where the
     * copy is estimated to cost fewer bits than the values it replaces.
     *
     * @param streams the token streams
     * @param numContexts the number of contexts, the LZ77 distance context excluded
     * @param histograms the distributions of the streams, by context
     * @param config the hybrid integer configuration of the distributions
     * @param prefix whether the distributions will be prefix codes, whose symbols take at least one bit unless
     * the distribution has a single symbol
     * @return the rewritten streams, the copy distances in context {@code numContexts}, or {@code null} when
     * copies save too few bits to be worth coding
     */
    private static List<Tokens> runs(List<Tokens> streams, int numContexts, Histogram[] histograms, int[] config,
                                     boolean prefix) {
        var bits = new float[numContexts][];
        for (var c = 0; c < numContexts; c++) {
            var h = histograms[c];
            bits[c] = new float[h.data.length];
            var single = h.alphabetSize() <= 1 || h.data[h.alphabetSize() - 1] == h.total;
            for (var s = 0; s < h.data.length; s++) {
                if (h.data[s] == 0) {
                    continue;
                }
                var entropy = (float) (StrictMath.log((double) h.total / h.data[s]) / StrictMath.log(2));
                bits[c][s] = prefix && !single ? Math.max(1.0f, entropy) : entropy;
            }
        }
        var decrease = 0.0;
        long total = 0;
        var result = new ArrayList<Tokens>(streams.size());
        for (var in : streams) {
            var n = in.size();
            total += n;
            var cumulative = new float[n + 1];
            for (var i = 0; i < n; i++) {
                var value = in.value(i);
                cumulative[i + 1] = cumulative[i] + bits[in.context(i)][token(value, config)]
                        + extraBits(value, config);
            }
            var out = new Tokens(n);
            for (var i = 0; i < n; i++) {
                var run = 0;
                if (i > 0) {
                    var previous = in.value(i - 1);
                    while (i + run < n && run < LZ77_MAX_RUN && in.value(i + run) == previous) {
                        run++;
                    }
                }
                if (run == 0) {
                    out.add(in.context(i), in.value(i));
                    continue;
                }
                var cost = cumulative[i + run] - cumulative[i];
                var copyCost = run >= LZ77_MIN_LENGTH ? ceilLog2(run - LZ77_MIN_LENGTH + 1) + 1 : 0;
                if (run < LZ77_MIN_LENGTH || cost <= copyCost) {
                    for (var j = 0; j < run; j++) {
                        out.add(in.context(i + j), in.value(i + j));
                    }
                } else {
                    out.addLength(in.context(i), run - LZ77_MIN_LENGTH);
                    out.add(numContexts, LZ77_PREVIOUS);
                    decrease += cost - copyCost;
                }
                i += run - 1;
            }
            result.add(out);
        }
        return decrease > total * 0.2 + 16 ? result : null;
    }

    /**
     * Returns the symbol a token is coded as: its hybrid integer token, or for an LZ77 copy length the token of
     * the length under the length configuration past {@link #LZ77_MIN_SYMBOL}.
     *
     * @param tokens the stream
     * @param index the token index
     * @param config the configuration of the token's cluster
     * @return the symbol
     */
    private static int symbol(Tokens tokens, int index, int[] config) {
        var value = tokens.value(index);
        return tokens.isLength(index) ? LZ77_MIN_SYMBOL + token(value, LZ77_LENGTH_CONFIG) : token(value, config);
    }

    /**
     * {@return an estimate of the bits the distributions and every stream they were built from take}
     */
    public long estimatedBits() {
        return cost;
    }

    /**
     * {@return the number of contexts}
     */
    public int contexts() {
        return numContexts;
    }

    /**
     * Writes the distributions.
     *
     * @param writer the output
     */
    public void writeHeader(JxlBitWriter writer) {
        writer.append(header);
    }

    /**
     * Writes one token stream, which must have been part of the build.
     *
     * @param tokens the stream
     * @param writer the output
     */
    public void writeTokens(Tokens tokens, JxlBitWriter writer) {
        if (rewritten != null) {
            tokens = rewritten.getOrDefault(tokens, tokens);
        }
        var n = tokens.size();
        if (prefix) {
            for (var i = 0; i < n; i++) {
                var cluster = contextMap[tokens.context(i)];
                var config = tokens.isLength(i) ? LZ77_LENGTH_CONFIG : configs[cluster];
                var value = tokens.value(i);
                var t = symbol(tokens, i, configs[cluster]);
                writer.write(depth[cluster][t], codes[cluster][t]);
                var nbits = extraBits(value, config);
                if (nbits > 0) {
                    writer.write(nbits, extraValue(value, config, nbits));
                }
            }
            return;
        }
        var chunks = new long[Math.max(4, n / 2 + 4)];
        var chunkBits = new byte[chunks.length];
        var chunkCount = 0;
        long all = 0;
        var allBits = 0;
        var state = ANS_SIGNATURE << 16;
        for (var i = n - 1; i >= 0; i--) {
            var cluster = contextMap[tokens.context(i)];
            var config = tokens.isLength(i) ? LZ77_LENGTH_CONFIG : configs[cluster];
            var value = tokens.value(i);
            var t = symbol(tokens, i, configs[cluster]);
            var nbits = extraBits(value, config);
            if (nbits > 0) {
                if (allBits + nbits > 56) {
                    if (chunkCount == chunks.length) {
                        chunks = Arrays.copyOf(chunks, chunkCount * 2);
                        chunkBits = Arrays.copyOf(chunkBits, chunkCount * 2);
                    }
                    chunks[chunkCount] = all;
                    chunkBits[chunkCount++] = (byte) allBits;
                    all = 0;
                    allBits = 0;
                }
                all = (all << nbits) | extraValue(value, config, nbits);
                allBits += nbits;
            }
            var f = freq[cluster][t];
            if ((state >>> (32 - LOG_TABLE)) >= f) {
                if (allBits + 16 > 56) {
                    if (chunkCount == chunks.length) {
                        chunks = Arrays.copyOf(chunks, chunkCount * 2);
                        chunkBits = Arrays.copyOf(chunkBits, chunkCount * 2);
                    }
                    chunks[chunkCount] = all;
                    chunkBits[chunkCount++] = (byte) allBits;
                    all = 0;
                    allBits = 0;
                }
                all = (all << 16) | (state & 0xFFFF);
                allBits += 16;
                state >>>= 16;
            }
            state = (Integer.divideUnsigned(state, f) << LOG_TABLE)
                    + reverse[cluster][start[cluster][t] + Integer.remainderUnsigned(state, f)];
        }
        writer.write(32, state & 0xFFFF_FFFFL);
        writer.write(allBits, all);
        for (var i = chunkCount - 1; i >= 0; i--) {
            writer.write(chunkBits[i], chunks[i]);
        }
    }

    /**
     * Returns the token of a value under a hybrid integer configuration.
     *
     * @param value the value, unsigned
     * @param config the split exponent, the most significant token bits and the least significant token bits
     * @return the token
     */
    static int token(int value, int[] config) {
        var split = 1 << config[0];
        if (Integer.compareUnsigned(value, split) < 0) {
            return value;
        }
        var n = 31 - Integer.numberOfLeadingZeros(value);
        var m = value - (1 << n);
        var msb = config[1];
        var lsb = config[2];
        return split + ((n - config[0]) << (msb + lsb)) + ((m >>> (n - msb)) << lsb) + (m & ((1 << lsb) - 1));
    }

    static int extraBits(int value, int[] config) {
        if (Integer.compareUnsigned(value, 1 << config[0]) < 0) {
            return 0;
        }
        return 31 - Integer.numberOfLeadingZeros(value) - config[1] - config[2];
    }

    private static long extraValue(int value, int[] config, int nbits) {
        return ((value & 0xFFFF_FFFFL) >>> config[2]) & ((1L << nbits) - 1);
    }

    private static void writeConfig(int[] config, JxlBitWriter writer, int logAlpha) {
        writer.write(ceilLog2(logAlpha + 1), config[0]);
        if (config[0] == logAlpha) {
            return;
        }
        writer.write(ceilLog2(config[0] + 1), config[1]);
        writer.write(ceilLog2(config[0] - config[1] + 1), config[2]);
    }

    private static int ceilLog2(int x) {
        return x <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(x - 1);
    }

    private static int floorLog2(int x) {
        return 31 - Integer.numberOfLeadingZeros(x);
    }

    private static int[][] chooseConfigs(Params params, List<Tokens> streams, int[] contextMap,
                                         List<Histogram> clustered, boolean usePrefix) {
        var n = clustered.size();
        var result = new int[n][];
        var fallback = switch (params.uintMethod()) {
            case 1 -> new int[]{0, 0, 0};
            case 3 -> new int[]{2, 0, 1};
            default -> new int[]{4, 2, 0};
        };
        Arrays.fill(result, fallback);
        int[][] candidates;
        if (params.uintMethod() == 4) {
            candidates = BEST_CONFIGS;
        } else if (params.uintMethod() == 2) {
            candidates = FAST_CONFIGS;
        } else {
            return result;
        }
        var costs = new double[n];
        Arrays.fill(costs, Double.MAX_VALUE);
        var extra = new long[n];
        var valid = new boolean[n];
        var maxAlpha = usePrefix ? PREFIX_MAX_ALPHABET : ANS_MAX_ALPHABET;
        for (var config : candidates) {
            Arrays.fill(valid, true);
            Arrays.fill(extra, 0);
            for (var h : clustered) {
                h.clear();
            }
            for (var stream : streams) {
                for (var i = 0; i < stream.size(); i++) {
                    var cluster = contextMap[stream.context(i)];
                    var value = stream.value(i);
                    if (stream.isLength(i)) {
                        clustered.get(cluster).add(symbol(stream, i, config));
                        extra[cluster] += extraBits(value, LZ77_LENGTH_CONFIG);
                        continue;
                    }
                    var t = token(value, config);
                    if (t >= maxAlpha) {
                        valid[cluster] = false;
                        continue;
                    }
                    extra[cluster] += extraBits(value, config);
                    clustered.get(cluster).add(t);
                }
            }
            for (var c = 0; c < n; c++) {
                if (!valid[c]) {
                    continue;
                }
                var h = clustered.get(c);
                var cost = populationCost(h.data, h.alphabetSize()) + extra[c];
                cost += ceilLog2(config[0] + 1) + ceilLog2(config[0] - config[1] + 1);
                if (cost < costs[c]) {
                    result[c] = config;
                    costs[c] = cost;
                }
            }
        }
        return result;
    }

    /**
     * Estimates the bits an ANS distribution and the symbols it codes take.
     *
     * @param counts the symbol counts
     * @param size the alphabet size
     * @return the estimate, or {@link Double#MAX_VALUE} if the alphabet is too large for ANS
     */
    static double populationCost(int[] counts, int size) {
        if (size > ANS_MAX_ALPHABET) {
            return Double.MAX_VALUE;
        }
        var cost = new double[1];
        bestMethod(counts, size, 0, cost);
        return cost[0];
    }

    private static List<Histogram> cluster(Params params, Histogram[] in, int[] symbols) {
        var max = Math.min(Math.min(CLUSTERS_LIMIT, params.maxHistograms()), in.length);
        if (params.clustering() == 0) {
            max = Math.min(max, 4);
        }
        var out = new ArrayList<Histogram>();
        fastCluster(in, max, out, symbols);
        if (params.clustering() == 2) {
            mergeByCost(out, symbols);
        }
        reindex(out, symbols);
        return out;
    }

    private static void fastCluster(Histogram[] in, int max, List<Histogram> out, int[] symbols) {
        Arrays.fill(symbols, max);
        var dists = new double[in.length];
        Arrays.fill(dists, Double.MAX_VALUE);
        var largest = 0;
        for (var i = 0; i < in.length; i++) {
            if (in[i].total == 0) {
                symbols[i] = 0;
                dists[i] = 0;
                continue;
            }
            in[i].computeEntropy();
            if (in[i].total > in[largest].total) {
                largest = i;
            }
        }
        while (out.size() < max) {
            symbols[largest] = out.size();
            out.add(new Histogram(in[largest]));
            dists[largest] = 0;
            largest = 0;
            for (var i = 0; i < in.length; i++) {
                if (dists[i] == 0) {
                    continue;
                }
                dists[i] = Math.min(Histogram.distance(in[i], out.getLast()), dists[i]);
                if (dists[i] > dists[largest]) {
                    largest = i;
                }
            }
            if (dists[largest] < 48.0) {
                break;
            }
        }
        for (var i = 0; i < in.length; i++) {
            if (symbols[i] != max) {
                continue;
            }
            var best = 0;
            var bestDist = Double.MAX_VALUE;
            for (var j = 0; j < out.size(); j++) {
                var dist = Histogram.distance(in[i], out.get(j));
                if (dist < bestDist) {
                    best = j;
                    bestDist = dist;
                }
            }
            out.get(best).add(in[i]);
            out.get(best).computeEntropy();
            symbols[i] = best;
        }
    }

    private record Pair(double cost, int first, int second, int version) implements Comparable<Pair> {
        @Override
        public int compareTo(Pair o) {
            var c = Double.compare(cost, o.cost);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(first, o.first);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(second, o.second);
            return c != 0 ? c : Integer.compare(version, o.version);
        }
    }

    private static void mergeByCost(List<Histogram> out, int[] symbols) {
        var n = out.size();
        var costOf = new double[n];
        for (var i = 0; i < n; i++) {
            var h = out.get(i);
            costOf[i] = populationCost(h.data, h.alphabetSize());
        }
        var nextVersion = 2;
        var version = new int[n];
        Arrays.fill(version, 1);
        var renumbering = new int[n];
        for (var i = 0; i < n; i++) {
            renumbering[i] = i;
        }
        var queue = new PriorityQueue<Pair>();
        for (var i = 0; i < n; i++) {
            for (var j = i + 1; j < n; j++) {
                var merged = new Histogram(out.get(i));
                merged.add(out.get(j));
                var cost = populationCost(merged.data, merged.alphabetSize()) - costOf[i] - costOf[j];
                if (cost < 0) {
                    queue.add(new Pair(cost, i, j, Math.max(version[i], version[j])));
                }
            }
        }
        while (!queue.isEmpty()) {
            var top = queue.poll();
            var first = top.first();
            var second = top.second();
            if (top.version() != Math.max(version[first], version[second]) || version[first] == 0
                    || version[second] == 0) {
                continue;
            }
            out.get(first).add(out.get(second));
            costOf[first] = populationCost(out.get(first).data, out.get(first).alphabetSize());
            for (var k = 0; k < n; k++) {
                if (renumbering[k] == second) {
                    renumbering[k] = first;
                }
            }
            version[second] = 0;
            version[first] = nextVersion++;
            for (var j = 0; j < n; j++) {
                if (j == first || version[j] == 0) {
                    continue;
                }
                var merged = new Histogram(out.get(first));
                merged.add(out.get(j));
                var cost = populationCost(merged.data, merged.alphabetSize()) - costOf[first] - costOf[j];
                if (cost < 0) {
                    queue.add(new Pair(cost, Math.min(first, j), Math.max(first, j),
                            Math.max(version[first], version[j])));
                }
            }
        }
        var reverseRenumbering = new int[n];
        Arrays.fill(reverseRenumbering, -1);
        var alive = new ArrayList<Histogram>();
        for (var i = 0; i < n; i++) {
            if (version[i] == 0) {
                continue;
            }
            reverseRenumbering[i] = alive.size();
            alive.add(out.get(i));
        }
        out.clear();
        out.addAll(alive);
        for (var i = 0; i < symbols.length; i++) {
            symbols[i] = reverseRenumbering[renumbering[symbols[i]]];
        }
    }

    private static void reindex(List<Histogram> out, int[] symbols) {
        var tmp = new ArrayList<>(out);
        var newIndex = new int[tmp.size()];
        Arrays.fill(newIndex, -1);
        var next = 0;
        out.clear();
        for (var symbol : symbols) {
            if (newIndex[symbol] < 0) {
                newIndex[symbol] = next++;
                out.add(tmp.get(symbol));
            }
        }
        for (var i = 0; i < symbols.length; i++) {
            symbols[i] = newIndex[symbols[i]];
        }
    }

    /**
     * Writes a context map (ISO/IEC 18181-1 C.2.2).
     *
     * @param contextMap the cluster of each context
     * @param numClusters the number of clusters
     * @param writer the output
     */
    public static void writeContextMap(int[] contextMap, int numClusters, JxlBitWriter writer) {
        if (numClusters == 1) {
            writer.write(1, 1);
            writer.write(2, 0);
            return;
        }
        var mtf = moveToFront(contextMap);
        var plain = new Tokens(contextMap.length);
        var moved = new Tokens(contextMap.length);
        for (var i = 0; i < contextMap.length; i++) {
            plain.add(0, contextMap[i]);
            moved.add(0, mtf[i]);
        }
        var plainCode = build(plain, 1, Params.CONTEXT_MAP);
        var movedCode = build(moved, 1, Params.CONTEXT_MAP);
        var plainCost = plainCode.header.bitsWritten() + tokenBits(plainCode, plain);
        var movedCost = movedCode.header.bitsWritten() + tokenBits(movedCode, moved);
        var useMtf = movedCost < plainCost;
        var entryBits = ceilLog2(numClusters);
        var simpleCost = (long) entryBits * contextMap.length;
        if (entryBits < 4 && simpleCost < plainCost && simpleCost < movedCost) {
            writer.write(1, 1);
            writer.write(2, entryBits);
            for (var entry : contextMap) {
                writer.write(entryBits, entry);
            }
            return;
        }
        writer.write(1, 0);
        writer.write(1, useMtf ? 1 : 0);
        var code = useMtf ? movedCode : plainCode;
        code.writeHeader(writer);
        code.writeTokens(useMtf ? moved : plain, writer);
    }

    private static long tokenBits(EntropyEncoder code, Tokens tokens) {
        var probe = new JxlBitWriter(64);
        code.writeTokens(tokens, probe);
        return probe.bitsWritten();
    }

    private static int[] moveToFront(int[] values) {
        var max = 0;
        for (var v : values) {
            max = Math.max(max, v);
        }
        var order = new int[max + 1];
        for (var i = 0; i <= max; i++) {
            order[i] = i;
        }
        var result = new int[values.length];
        for (var i = 0; i < values.length; i++) {
            var index = 0;
            while (order[index] != values[i]) {
                index++;
            }
            result[i] = index;
            var value = order[index];
            System.arraycopy(order, 0, order, 1, index);
            order[0] = value;
        }
        return result;
    }

    private static void storeVarLenUint8(int n, JxlBitWriter writer) {
        if (n == 0) {
            writer.write(1, 0);
        } else {
            writer.write(1, 1);
            var nbits = floorLog2(n);
            writer.write(3, nbits);
            writer.write(nbits, n - (1 << nbits));
        }
    }

    private static void storeVarLenUint16(int n, JxlBitWriter writer) {
        if (n == 0) {
            writer.write(1, 0);
        } else {
            writer.write(1, 1);
            var nbits = floorLog2(n);
            writer.write(4, nbits);
            writer.write(nbits, n - (1 << nbits));
        }
    }

    private record AnsResult(int[] freq, int[] start, int[] reverse, double cost) {
    }

    private static AnsResult storeAnsHistogram(int[] histogram, int size, int logAlpha, int strategy,
                                               JxlBitWriter writer) {
        var cost = new double[1];
        var method = bestMethod(histogram, size, strategy, cost);
        var counts = histogram.clone();
        var sum = 0L;
        for (var c : counts) {
            sum += c;
        }
        if (sum == 0) {
            counts[0] = TABLE_SIZE;
        }
        var omit = new int[1];
        var numSymbols = new int[1];
        var symbols = new int[4];
        if (method == 0) {
            counts = flat(size);
        } else {
            normalize(counts, omit, size, method - 1, numSymbols, symbols);
        }
        var tables = aliasReverse(counts, size, logAlpha);
        if (method == 0) {
            writer.write(1, 0);
            writer.write(1, 1);
            storeVarLenUint8(size - 1, writer);
        } else {
            encodeCounts(counts, size, omit[0], numSymbols[0], method - 1, symbols, writer, null);
        }
        var freq = Arrays.copyOf(counts, Math.max(1, size));
        if (size == 0) {
            freq[0] = TABLE_SIZE;
        }
        return new AnsResult(freq, tables[0], tables[1], cost[0]);
    }

    private static int[] flat(int size) {
        var result = new int[size];
        var each = TABLE_SIZE / size;
        var rest = TABLE_SIZE % size;
        for (var i = 0; i < size; i++) {
            result[i] = each + (i < rest ? 1 : 0);
        }
        return result;
    }

    /**
     * Builds the reverse map of the decoder's alias table: for each symbol, the table slots that decode to it,
     * in the order of their offsets.
     *
     * @param counts the normalized frequencies, summing to 4096
     * @param size the alphabet size
     * @param logAlpha the base-2 logarithm of the alias table size
     * @return the first slot index of each symbol and the slots
     */
    private static int[][] aliasReverse(int[] counts, int size, int logAlpha) {
        var tableSize = 1 << logAlpha;
        var logBucket = LOG_TABLE - logAlpha;
        var bucketSize = 1 << logBucket;
        var dist = new int[tableSize];
        System.arraycopy(counts, 0, dist, 0, Math.min(size, counts.length));
        var start = new int[tableSize + 1];
        for (var i = 0; i < tableSize; i++) {
            start[i + 1] = start[i] + dist[i];
        }
        var reverse = new int[TABLE_SIZE];
        for (var i = 0; i < tableSize; i++) {
            if (dist[i] == TABLE_SIZE) {
                for (var k = 0; k < TABLE_SIZE; k++) {
                    reverse[k] = k;
                }
                Arrays.fill(start, 0, i + 1, 0);
                return new int[][]{start, reverse};
            }
        }
        var aliasSymbol = new int[tableSize];
        var aliasOffset = new int[tableSize];
        var aliasCutoff = new int[tableSize];
        var underfull = new int[tableSize];
        var overfull = new int[tableSize];
        var under = 0;
        var over = 0;
        for (var i = 0; i < tableSize; i++) {
            aliasSymbol[i] = i < size ? i : 0;
            aliasCutoff[i] = dist[i];
            if (dist[i] < bucketSize) {
                underfull[under++] = i;
            } else if (dist[i] > bucketSize) {
                overfull[over++] = i;
            }
        }
        while (over > 0 && under > 0) {
            var o = overfull[--over];
            var u = underfull[--under];
            var by = bucketSize - aliasCutoff[u];
            aliasCutoff[o] -= by;
            aliasSymbol[u] = o;
            aliasOffset[u] = aliasCutoff[o];
            if (aliasCutoff[o] < bucketSize) {
                underfull[under++] = o;
            } else if (aliasCutoff[o] > bucketSize) {
                overfull[over++] = o;
            }
        }
        for (var idx = 0; idx < TABLE_SIZE; idx++) {
            var i = idx >>> logBucket;
            var pos = idx & (bucketSize - 1);
            int symbol;
            int offset;
            if (aliasCutoff[i] == bucketSize) {
                symbol = i;
                offset = pos;
            } else if (pos < aliasCutoff[i]) {
                symbol = i;
                offset = pos;
            } else {
                symbol = aliasSymbol[i];
                offset = aliasOffset[i] - aliasCutoff[i] + pos;
            }
            reverse[start[symbol] + offset] = idx;
        }
        return new int[][]{start, reverse};
    }

    private static int populationCountPrecision(int logCount, int shift) {
        var r = Math.min(logCount, shift - ((LOG_TABLE - logCount) >> 1));
        return Math.max(r, 0);
    }

    private static int smallestIncrement(int count, int shift) {
        var bits = count == 0 ? -1 : floorLog2(count);
        var drop = bits - populationCountPrecision(bits, shift);
        return drop < 0 ? 1 : 1 << drop;
    }

    private static boolean rebalance(double[] targets, int maxSymbol, int shift, int[] omit, int[] counts,
                                     boolean minimizeErrorOfSum) {
        var sum = 0;
        var sumNonrounded = 0.0;
        var remainderPos = 0;
        var remainderLog = -1;
        for (var n = 0; n < maxSymbol; n++) {
            if (targets[n] > 0 && targets[n] < 1.0) {
                counts[n] = 1;
                sumNonrounded += targets[n];
                sum += counts[n];
            }
        }
        var discount = (TABLE_SIZE - sum) / (TABLE_SIZE - sumNonrounded);
        if (!(discount > 0) || discount > 1.0) {
            return false;
        }
        for (var n = 0; n < maxSymbol; n++) {
            if (targets[n] >= 1.0) {
                sumNonrounded += targets[n];
                counts[n] = (int) (targets[n] * discount);
                if (counts[n] == 0) {
                    counts[n] = 1;
                }
                if (counts[n] == TABLE_SIZE) {
                    counts[n] = TABLE_SIZE - 1;
                }
                var inc = smallestIncrement(counts[n], shift);
                counts[n] -= counts[n] & (inc - 1);
                var target = minimizeErrorOfSum ? (int) sumNonrounded - sum : (int) targets[n];
                if (counts[n] == 0 || (target >= counts[n] + inc / 2 && counts[n] + inc < TABLE_SIZE)) {
                    counts[n] += inc;
                }
                sum += counts[n];
                var countLog = floorLog2(counts[n]);
                if (countLog > remainderLog) {
                    remainderPos = n;
                    remainderLog = countLog;
                }
            }
        }
        counts[remainderPos] -= sum - TABLE_SIZE;
        omit[0] = remainderPos;
        return counts[remainderPos] > 0;
    }

    private static void normalize(int[] counts, int[] omit, int length, int shift, int[] numSymbols,
                                  int[] symbols) {
        long total = 0;
        var maxSymbol = 0;
        var symbolCount = 0;
        for (var n = 0; n < length; n++) {
            total += counts[n];
            if (counts[n] > 0) {
                if (symbolCount < 4) {
                    symbols[symbolCount] = n;
                }
                symbolCount++;
                maxSymbol = n + 1;
            }
        }
        numSymbols[0] = symbolCount;
        if (symbolCount == 0) {
            return;
        }
        if (symbolCount == 1) {
            counts[symbols[0]] = TABLE_SIZE;
            return;
        }
        var norm = (double) TABLE_SIZE / total;
        var targets = new double[maxSymbol];
        for (var n = 0; n < maxSymbol; n++) {
            targets[n] = norm * counts[n];
        }
        var backup = Arrays.copyOf(counts, length);
        if (!rebalance(targets, maxSymbol, shift, omit, counts, false)) {
            System.arraycopy(backup, 0, counts, 0, length);
            if (!rebalance(targets, maxSymbol, shift, omit, counts, true)) {
                throw new IllegalStateException("cannot rebalance an ANS histogram");
            }
        }
    }

    private static long encodeCounts(int[] counts, int alphabetSize, int omitPos, int numSymbols, int shift,
                                     int[] symbols, JxlBitWriter writer, long[] sizeOnly) {
        var w = new BitSink(writer);
        if (numSymbols <= 2) {
            w.write(1, 1);
            if (numSymbols == 0) {
                w.write(1, 0);
                w.varLenUint8(0);
            } else {
                w.write(1, numSymbols - 1);
                for (var i = 0; i < numSymbols; i++) {
                    w.varLenUint8(symbols[i]);
                }
            }
            if (numSymbols == 2) {
                w.write(LOG_TABLE, counts[symbols[0]]);
            }
            return w.size;
        }
        w.write(1, 0);
        w.write(1, 0);
        var same = new int[alphabetSize];
        var last = 0;
        for (var i = 1; i < alphabetSize; i++) {
            if (counts[i] != counts[last] || i + 1 == alphabetSize || (i - last) >= 255 || i == omitPos
                    || i == omitPos + 1) {
                same[last] = i - last;
                last = i + 1;
            }
        }
        var length = 0;
        var logCounts = new int[alphabetSize];
        var omitLog = 0;
        for (var i = 0; i < alphabetSize; i++) {
            if (i == omitPos) {
                length = i + 1;
            } else if (counts[i] > 0) {
                logCounts[i] = floorLog2(counts[i]) + 1;
                length = i + 1;
                if (i < omitPos) {
                    omitLog = Math.max(omitLog, logCounts[i] + 1);
                } else {
                    omitLog = Math.max(omitLog, logCounts[i]);
                }
            }
        }
        logCounts[omitPos] = omitLog;
        var upperBoundLog = floorLog2(LOG_TABLE + 1);
        var log = floorLog2(shift + 1);
        w.write(log, (1 << log) - 1);
        if (log != upperBoundLog) {
            w.write(1, 0);
        }
        w.write(log, ((1 << log) - 1) & (shift + 1));
        w.varLenUint8(Math.min(length - 3, 255));
        var rep = LOG_TABLE + 1;
        for (var i = 0; i < length; i++) {
            if (i > 0 && same[i - 1] > 4) {
                w.write(LOG_COUNT_BIT_LENGTHS[rep], LOG_COUNT_SYMBOLS[rep]);
                w.varLenUint8(same[i - 1] - 4 - 1);
                i += same[i - 1] - 2;
                continue;
            }
            w.write(LOG_COUNT_BIT_LENGTHS[logCounts[i]], LOG_COUNT_SYMBOLS[logCounts[i]]);
        }
        for (var i = 0; i < length; i++) {
            if (i > 0 && same[i - 1] > 4) {
                i += same[i - 1] - 2;
                continue;
            }
            if (logCounts[i] > 1 && i != omitPos) {
                var bitCount = populationCountPrecision(logCounts[i] - 1, shift);
                var drop = logCounts[i] - 1 - bitCount;
                w.write(bitCount, (counts[i] >> drop) - (1L << bitCount));
            }
        }
        return w.size;
    }

    private static final class BitSink {
        private final JxlBitWriter writer;

        long size;

        BitSink(JxlBitWriter writer) {
            this.writer = writer;
        }

        void write(int count, long value) {
            size += count;
            if (writer != null) {
                writer.write(count, value);
            }
        }

        void varLenUint8(int n) {
            if (n == 0) {
                write(1, 0);
            } else {
                write(1, 1);
                var nbits = floorLog2(n);
                write(3, nbits);
                write(nbits, n - (1 << nbits));
            }
        }
    }

    private static double histoAndDataCost(int[] histogram, int size, int method) {
        if (method == 0) {
            var flatBits = Math.max(Histogram.log2(size), 0);
            long total = 0;
            for (var i = 0; i < size; i++) {
                total += histogram[i];
            }
            return LOG_TABLE + 2 + total * flatBits;
        }
        var shift = method - 1;
        var counts = Arrays.copyOf(histogram, size);
        var omit = new int[1];
        var numSymbols = new int[1];
        var symbols = new int[4];
        normalize(counts, omit, size, shift, numSymbols, symbols);
        var bits = encodeCounts(counts, size, omit[0], numSymbols[0], shift, symbols, null, null);
        var sum = 0.0;
        for (var i = 0; i < size; i++) {
            if (histogram[i] > 0) {
                sum += histogram[i] * Math.max(0.0, LOG_TABLE - Histogram.log2(counts[i]));
            }
        }
        return bits + sum;
    }

    private static int bestMethod(int[] histogram, int size, int strategy, double[] cost) {
        var method = 0;
        var best = histoAndDataCost(histogram, size, 0);
        int[] shifts = switch (strategy) {
            case 2 -> new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
            case 1 -> new int[]{0, 2, 4, 6, 8, 10, 12};
            default -> new int[]{0, 6, 12};
        };
        for (var shift : shifts) {
            var c = histoAndDataCost(histogram, size, shift + 1);
            if (c < best) {
                method = shift + 1;
                best = c;
            }
        }
        cost[0] = best;
        return method;
    }

    private static void storeHuffmanCode(int[] histogram, int length, int[] depth, int[] bits,
                                         JxlBitWriter writer) {
        var count = 0;
        var s4 = new int[4];
        for (var i = 0; i < length; i++) {
            if (histogram[i] != 0) {
                if (count < 4) {
                    s4[count] = i;
                } else if (count > 4) {
                    break;
                }
                count++;
            }
        }
        var maxBits = 0;
        for (var counter = length - 1; counter != 0; counter >>= 1) {
            maxBits++;
        }
        if (count <= 1) {
            writer.write(4, 1);
            writer.write(maxBits, s4[0]);
            return;
        }
        HuffmanTree.create(histogram, length, PREFIX_MAX_BITS, depth);
        HuffmanTree.toCodes(depth, length, bits);
        if (count <= 4) {
            writer.write(2, 1);
            writer.write(2, count - 1);
            for (var i = 0; i < count; i++) {
                for (var j = i + 1; j < count; j++) {
                    if (depth[s4[j]] < depth[s4[i]]) {
                        var t = s4[j];
                        s4[j] = s4[i];
                        s4[i] = t;
                    }
                }
            }
            for (var i = 0; i < count; i++) {
                writer.write(maxBits, s4[i]);
            }
            if (count == 4) {
                writer.write(1, depth[s4[0]] == 1 ? 1 : 0);
            }
            return;
        }
        storeHuffmanTree(depth, length, writer);
    }

    private static void storeHuffmanTree(int[] depths, int num, JxlBitWriter writer) {
        var tree = new int[2 * num + 16];
        var extra = new int[2 * num + 16];
        var size = HuffmanTree.writeTree(depths, num, tree, extra);
        var histogram = new int[18];
        for (var i = 0; i < size; i++) {
            histogram[tree[i]]++;
        }
        var numCodes = 0;
        var code = 0;
        for (var i = 0; i < 18; i++) {
            if (histogram[i] != 0) {
                if (numCodes == 0) {
                    code = i;
                    numCodes = 1;
                } else {
                    numCodes = 2;
                    break;
                }
            }
        }
        var lengthDepth = new int[18];
        var lengthBits = new int[18];
        HuffmanTree.create(histogram, 18, 5, lengthDepth);
        HuffmanTree.toCodes(lengthDepth, 18, lengthBits);
        var toStore = 18;
        if (numCodes > 1) {
            while (toStore > 0 && lengthDepth[CODE_LENGTH_ORDER[toStore - 1]] == 0) {
                toStore--;
            }
        }
        var skip = 0;
        if (lengthDepth[CODE_LENGTH_ORDER[0]] == 0 && lengthDepth[CODE_LENGTH_ORDER[1]] == 0) {
            skip = 2;
            if (lengthDepth[CODE_LENGTH_ORDER[2]] == 0) {
                skip = 3;
            }
        }
        writer.write(2, skip);
        for (var i = skip; i < toStore; i++) {
            var l = lengthDepth[CODE_LENGTH_ORDER[i]];
            writer.write(CODE_LENGTH_LENGTH_BITS[l], CODE_LENGTH_LENGTH_SYMBOLS[l]);
        }
        if (numCodes == 1) {
            lengthDepth[code] = 0;
        }
        for (var i = 0; i < size; i++) {
            var ix = tree[i];
            writer.write(lengthDepth[ix], lengthBits[ix]);
            if (ix == 16) {
                writer.write(2, extra[i]);
            } else if (ix == 17) {
                writer.write(3, extra[i]);
            }
        }
    }
}
