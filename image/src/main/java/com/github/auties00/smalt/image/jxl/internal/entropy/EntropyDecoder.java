/* Ports jxl-oxide 0.12.6 (crates/jxl-coding). Copyright Wonwoo Choi and the jxl-oxide contributors; MIT License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.entropy;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;

import java.util.Arrays;

/**
 * Decodes the entropy-coded integer streams of a JPEG XL codestream (ISO/IEC 18181-1 annex C):
 * the clustered distributions, coded either with asymmetric numeral systems (ANS) or with Brotli
 * prefix codes, the hybrid integer configurations, and the optional LZ77 back-references.
 *
 * <p>A decoder is read once from the bitstream with {@link #read(JxlBitReader, int)}; its tables
 * are immutable and shared by the copies {@link #copy()} makes, each of which carries its own
 * ANS state and LZ77 window, so the groups of a frame decode their streams independently.
 * Instances are not thread-safe.
 *
 * @implNote This implementation builds the ANS decoder's alias table with a branch-free bucket
 * lookup and decodes prefix codes with two-level tables.
 */
public final class EntropyDecoder {
    /**
     * The LZ77 distances relative to a row of {@code distMultiplier} samples, as {@code (dx, dy)}
     * pairs (ISO/IEC 18181-1 table C.1).
     */
    private static final byte[] SPECIAL_DISTANCES = {
            0, 1, 1, 0, 1, 1, -1, 1, 0, 2, 2, 0, 1, 2, -1, 2, 2, 1, -2, 1,
            2, 2, -2, 2, 0, 3, 3, 0, 1, 3, -1, 3, 3, 1, -3, 1, 2, 3, -2, 3,
            3, 2, -3, 2, 0, 4, 4, 0, 1, 4, -1, 4, 4, 1, -4, 1, 3, 3, -3, 3,
            2, 4, -2, 4, 4, 2, -4, 2, 0, 5, 3, 4, -3, 4, 4, 3, -4, 3, 5, 0,
            1, 5, -1, 5, 5, 1, -5, 1, 2, 5, -2, 5, 5, 2, -5, 2, 4, 4, -4, 4,
            3, 5, -3, 5, 5, 3, -5, 3, 0, 6, 6, 0, 1, 6, -1, 6, 6, 1, -6, 1,
            2, 6, -2, 6, 6, 2, -6, 2, 4, 5, -4, 5, 5, 4, -5, 4, 3, 6, -3, 6,
            6, 3, -6, 3, 0, 7, 7, 0, 1, 7, -1, 7, 5, 5, -5, 5, 7, 1, -7, 1,
            4, 6, -4, 6, 6, 4, -6, 4, 2, 7, -2, 7, 7, 2, -7, 2, 3, 7, -3, 7,
            7, 3, -7, 3, 5, 6, -5, 6, 6, 5, -6, 5, 8, 0, 4, 7, -4, 7, 7, 4,
            -7, 4, 8, 1, 8, 2, 6, 6, -6, 6, 8, 3, 5, 7, -5, 7, 7, 5, -7, 5,
            8, 4, 6, 7, -6, 7, 7, 6, -7, 6, 8, 5, 7, 7, -7, 7, 8, 6, 8, 7,
    };

    /**
     * The order in which the code length code lengths of a complex prefix code are stored
     * (RFC 7932 section 3.5).
     */
    private static final int[] CODE_LENGTH_ORDER = {1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    /**
     * The longest prefix code, in bits.
     */
    private static final int MAX_PREFIX_BITS = 15;

    /**
     * The longest code resolved by the first-level prefix table, in bits.
     */
    private static final int MAX_TOPLEVEL_BITS = 10;

    /**
     * The LZ77 window size, in symbols.
     */
    private static final int WINDOW_SIZE = 1 << 20;

    /**
     * The ANS state every stream must end in.
     */
    private static final int ANS_FINAL_STATE = 0x130000;

    /**
     * The cluster of every distribution context.
     */
    private final int[] clusters;

    /**
     * The number of clusters.
     */
    private final int numClusters;

    /**
     * The split exponent of the hybrid integer configuration of each cluster.
     */
    private final int[] splitExponent;

    /**
     * The number of most significant bits kept in the token, per cluster.
     */
    private final int[] msbInToken;

    /**
     * The number of least significant bits kept in the token, per cluster.
     */
    private final int[] lsbInToken;

    /**
     * Whether the symbols are prefix-coded rather than ANS-coded.
     */
    private final boolean prefixCoded;

    /**
     * The base-2 logarithm of the bucket size of the ANS alias tables.
     */
    private final int logBucketSize;

    /**
     * The number of buckets of each ANS alias table.
     */
    private final int tableSize;

    /**
     * The ANS alias tables of every cluster, one packed bucket per entry: alias symbol in bits
     * 0-7, cutoff in bits 8-15, frequency in bits 16-31, alias offset in bits 32-47 and the XOR of
     * the frequency with the alias symbol's in bits 48-63.
     */
    private final long[] ansBuckets;

    /**
     * The single symbol of each cluster whose distribution has one, or -1.
     */
    private final int[] singleSymbol;

    /**
     * The first-level prefix tables of every cluster.
     */
    private final int[][] prefixTop;

    /**
     * The number of bits indexing each first-level prefix table.
     */
    private final int[] prefixTopBits;

    /**
     * The second-level prefix tables of every cluster.
     */
    private final int[][] prefixSecond;

    /**
     * Whether LZ77 back-references are enabled.
     */
    private final boolean lz77;

    /**
     * The first token that starts an LZ77 copy.
     */
    private final int lzMinSymbol;

    /**
     * The shortest LZ77 copy.
     */
    private final int lzMinLength;

    /**
     * The split exponent of the LZ77 length configuration.
     */
    private final int lzSplitExponent;

    /**
     * The most significant token bits of the LZ77 length configuration.
     */
    private final int lzMsbInToken;

    /**
     * The least significant token bits of the LZ77 length configuration.
     */
    private final int lzLsbInToken;

    /**
     * The current ANS state.
     */
    private int ansState;

    /**
     * Whether the ANS state has been read.
     */
    private boolean ansStarted;

    /**
     * The LZ77 window of decoded values, grown on demand up to {@link #WINDOW_SIZE}.
     */
    private int[] window;

    /**
     * The number of values the current LZ77 copy still produces.
     */
    private int numToCopy;

    /**
     * The window position of the next value the current LZ77 copy produces.
     */
    private int copyPos;

    /**
     * The number of values decoded so far.
     */
    private int numDecoded;

    /**
     * Creates a decoder from its tables.
     *
     * @param source the decoder whose tables are shared, or {@code null} to use the other
     * arguments
     * @param parts the tables when {@code source} is {@code null}
     */
    private EntropyDecoder(EntropyDecoder source, Parts parts) {
        if (source != null) {
            this.clusters = source.clusters;
            this.numClusters = source.numClusters;
            this.splitExponent = source.splitExponent;
            this.msbInToken = source.msbInToken;
            this.lsbInToken = source.lsbInToken;
            this.prefixCoded = source.prefixCoded;
            this.logBucketSize = source.logBucketSize;
            this.tableSize = source.tableSize;
            this.ansBuckets = source.ansBuckets;
            this.singleSymbol = source.singleSymbol;
            this.prefixTop = source.prefixTop;
            this.prefixTopBits = source.prefixTopBits;
            this.prefixSecond = source.prefixSecond;
            this.lz77 = source.lz77;
            this.lzMinSymbol = source.lzMinSymbol;
            this.lzMinLength = source.lzMinLength;
            this.lzSplitExponent = source.lzSplitExponent;
            this.lzMsbInToken = source.lzMsbInToken;
            this.lzLsbInToken = source.lzLsbInToken;
        } else {
            this.clusters = parts.clusters;
            this.numClusters = parts.numClusters;
            this.splitExponent = parts.splitExponent;
            this.msbInToken = parts.msbInToken;
            this.lsbInToken = parts.lsbInToken;
            this.prefixCoded = parts.prefixCoded;
            this.logBucketSize = parts.logBucketSize;
            this.tableSize = parts.tableSize;
            this.ansBuckets = parts.ansBuckets;
            this.singleSymbol = parts.singleSymbol;
            this.prefixTop = parts.prefixTop;
            this.prefixTopBits = parts.prefixTopBits;
            this.prefixSecond = parts.prefixSecond;
            this.lz77 = parts.lz77;
            this.lzMinSymbol = parts.lzMinSymbol;
            this.lzMinLength = parts.lzMinLength;
            this.lzSplitExponent = parts.lzSplitExponent;
            this.lzMsbInToken = parts.lzMsbInToken;
            this.lzLsbInToken = parts.lzLsbInToken;
        }
    }

    /**
     * Holds the tables of a decoder while it is being read.
     */
    private static final class Parts {
        /**
         * Creates an empty instance.
         */
        Parts() {
        }

        /**
         * The cluster of every context.
         */
        int[] clusters;

        /**
         * The number of clusters.
         */
        int numClusters;

        /**
         * The split exponents.
         */
        int[] splitExponent;

        /**
         * The most significant token bits.
         */
        int[] msbInToken;

        /**
         * The least significant token bits.
         */
        int[] lsbInToken;

        /**
         * Whether prefix codes are used.
         */
        boolean prefixCoded;

        /**
         * The ANS bucket size logarithm.
         */
        int logBucketSize;

        /**
         * The ANS table size.
         */
        int tableSize;

        /**
         * The ANS buckets.
         */
        long[] ansBuckets;

        /**
         * The single symbols.
         */
        int[] singleSymbol;

        /**
         * The first-level prefix tables.
         */
        int[][] prefixTop;

        /**
         * The first-level prefix table bits.
         */
        int[] prefixTopBits;

        /**
         * The second-level prefix tables.
         */
        int[][] prefixSecond;

        /**
         * Whether LZ77 is enabled.
         */
        boolean lz77;

        /**
         * The LZ77 minimum symbol.
         */
        int lzMinSymbol;

        /**
         * The LZ77 minimum length.
         */
        int lzMinLength;

        /**
         * The LZ77 length split exponent.
         */
        int lzSplitExponent;

        /**
         * The LZ77 length most significant token bits.
         */
        int lzMsbInToken;

        /**
         * The LZ77 length least significant token bits.
         */
        int lzLsbInToken;
    }

    /**
     * Reads a decoder for a number of distribution contexts.
     *
     * @param br the bitstream
     * @param numDist the number of contexts, positive
     * @return the decoder
     * @throws MediaException.Malformed if the distributions are invalid
     */
    public static EntropyDecoder read(JxlBitReader br, int numDist) {
        return read(br, numDist, true);
    }

    /**
     * Reads a decoder, optionally refusing LZ77.
     *
     * @param br the bitstream
     * @param numDist the number of contexts
     * @param allowLz77 whether the stream may enable LZ77
     * @return the decoder
     * @throws MediaException.Malformed if the distributions are invalid
     */
    private static EntropyDecoder read(JxlBitReader br, int numDist, boolean allowLz77) {
        if (numDist <= 0) {
            throw new MediaException.Malformed("entropy stream without contexts");
        }
        var parts = new Parts();
        if (br.readBool()) {
            if (!allowLz77) {
                throw new MediaException.Malformed("LZ77 is not allowed in this entropy stream");
            }
            parts.lz77 = true;
            parts.lzMinSymbol = br.readU32(224, 0, 512, 0, 4096, 0, 8, 15);
            parts.lzMinLength = br.readU32(3, 0, 4, 0, 5, 2, 9, 8);
            var config = readIntegerConfig(br, 8);
            parts.lzSplitExponent = config[0];
            parts.lzMsbInToken = config[1];
            parts.lzLsbInToken = config[2];
            numDist++;
        }
        var clusterInfo = readClusters(br, numDist);
        var numClusters = clusterInfo[0][0];
        parts.clusters = clusterInfo[1];
        parts.numClusters = numClusters;
        parts.prefixCoded = br.readBool();
        var logAlphabetSize = parts.prefixCoded ? 15 : br.readBits(2) + 5;
        parts.splitExponent = new int[numClusters];
        parts.msbInToken = new int[numClusters];
        parts.lsbInToken = new int[numClusters];
        for (var i = 0; i < numClusters; i++) {
            var config = readIntegerConfig(br, logAlphabetSize);
            parts.splitExponent[i] = config[0];
            parts.msbInToken[i] = config[1];
            parts.lsbInToken[i] = config[2];
        }
        parts.singleSymbol = new int[numClusters];
        if (parts.prefixCoded) {
            var counts = new int[numClusters];
            for (var i = 0; i < numClusters; i++) {
                if (br.readBool()) {
                    var n = br.readBits(4);
                    counts[i] = 1 + (1 << n) + br.readBits(n);
                } else {
                    counts[i] = 1;
                }
                if (counts[i] > 1 << 15) {
                    throw new MediaException.Malformed("prefix code alphabet too large");
                }
            }
            parts.prefixTop = new int[numClusters][];
            parts.prefixTopBits = new int[numClusters];
            parts.prefixSecond = new int[numClusters][];
            for (var i = 0; i < numClusters; i++) {
                readPrefixCode(br, counts[i], parts, i);
            }
        } else {
            parts.logBucketSize = 12 - logAlphabetSize;
            parts.tableSize = 1 << logAlphabetSize;
            parts.ansBuckets = new long[numClusters * parts.tableSize];
            for (var i = 0; i < numClusters; i++) {
                parts.singleSymbol[i] = readAnsHistogram(br, logAlphabetSize, parts.ansBuckets, i * parts.tableSize);
            }
        }
        return new EntropyDecoder(null, parts);
    }

    /**
     * Returns a decoder sharing this decoder's tables with a fresh stream state.
     *
     * @return the copy
     */
    public EntropyDecoder copy() {
        return new EntropyDecoder(this, null);
    }

    /**
     * Reads a hybrid integer configuration.
     *
     * @param br the bitstream
     * @param logAlphabetSize the base-2 logarithm of the alphabet size
     * @return the split exponent, the most significant token bits and the least significant
     * token bits
     * @throws MediaException.Malformed if the configuration is inconsistent
     */
    private static int[] readIntegerConfig(JxlBitReader br, int logAlphabetSize) {
        var splitExponent = br.readBits(addLog2Ceil(logAlphabetSize));
        var msb = 0;
        var lsb = 0;
        if (splitExponent != logAlphabetSize) {
            msb = br.readBits(addLog2Ceil(splitExponent));
            if (msb > splitExponent) {
                throw new MediaException.Malformed("invalid hybrid integer configuration");
            }
            lsb = br.readBits(addLog2Ceil(splitExponent - msb));
        }
        if (msb + lsb > splitExponent || splitExponent > 31) {
            throw new MediaException.Malformed("invalid hybrid integer configuration");
        }
        return new int[]{splitExponent, msb, lsb};
    }

    /**
     * Returns {@code ceil(log2(x + 1))}.
     *
     * @param x the value, non-negative
     * @return the number of bits needed to store values from 0 to {@code x}
     */
    static int addLog2Ceil(int x) {
        if (x < 0) {
            return 32;
        }
        return 32 - Integer.numberOfLeadingZeros(x);
    }

    /**
     * Reads the clustering of distribution contexts.
     *
     * @param br the bitstream
     * @param numDist the number of contexts
     * @return {@code {{numClusters}, clusterOfContext}}
     * @throws MediaException.Malformed if the clustering is invalid or leaves a cluster unused
     */
    public static int[][] readClusters(JxlBitReader br, int numDist) {
        var clusters = new int[numDist];
        if (numDist == 1) {
            return new int[][]{{1}, clusters};
        }
        if (br.readBool()) {
            var bits = br.readBits(2);
            for (var i = 0; i < numDist; i++) {
                clusters[i] = br.readBits(bits);
            }
        } else {
            var useMtf = br.readBool();
            var decoder = read(br, 1, numDist > 2);
            decoder.begin(br);
            for (var i = 0; i < numDist; i++) {
                var value = decoder.readUint(br, 0);
                if (value < 0 || value >= 256) {
                    throw new MediaException.Malformed("invalid cluster index " + value);
                }
                clusters[i] = value;
            }
            decoder.checkFinalState();
            if (useMtf) {
                var mtf = new int[256];
                for (var i = 0; i < 256; i++) {
                    mtf[i] = i;
                }
                for (var i = 0; i < numDist; i++) {
                    var index = clusters[i];
                    var value = mtf[index];
                    clusters[i] = value;
                    System.arraycopy(mtf, 0, mtf, 1, index);
                    mtf[0] = value;
                }
            }
        }
        var max = 0;
        for (var cluster : clusters) {
            max = Math.max(max, cluster);
        }
        var used = new boolean[max + 1];
        for (var cluster : clusters) {
            used[cluster] = true;
        }
        for (var u : used) {
            if (!u) {
                throw new MediaException.Malformed("entropy clustering leaves a cluster unused");
            }
        }
        return new int[][]{{max + 1}, clusters};
    }

    /**
     * Reads an ANS distribution and builds its alias table.
     *
     * @param br the bitstream
     * @param logAlphabetSize the base-2 logarithm of the alphabet size, from 5 to 8
     * @param buckets the alias tables of all clusters
     * @param base the index of this cluster's first bucket
     * @return the single symbol of the distribution, or -1 when it has several
     * @throws MediaException.Malformed if the distribution is invalid
     */
    private static int readAnsHistogram(JxlBitReader br, int logAlphabetSize, long[] buckets, int base) {
        var tableSize = 1 << logAlphabetSize;
        var logBucketSize = 12 - logAlphabetSize;
        var bucketSize = 1 << logBucketSize;
        var dist = new int[tableSize];
        int alphabetSize;
        if (br.readBool()) {
            if (br.readBool()) {
                var v0 = readU8(br);
                var v1 = readU8(br);
                if (v0 == v1) {
                    throw new MediaException.Malformed("invalid ANS histogram");
                }
                alphabetSize = Math.max(v0, v1) + 1;
                if (alphabetSize > tableSize) {
                    throw new MediaException.Malformed("invalid ANS histogram");
                }
                var prob = br.readBits(12);
                dist[v0] = prob;
                dist[v1] = (1 << 12) - prob;
            } else {
                var value = readU8(br);
                alphabetSize = value + 1;
                if (alphabetSize > tableSize) {
                    throw new MediaException.Malformed("invalid ANS histogram");
                }
                dist[value] = 1 << 12;
            }
        } else if (br.readBool()) {
            alphabetSize = readU8(br) + 1;
            if (alphabetSize > tableSize) {
                throw new MediaException.Malformed("invalid ANS histogram");
            }
            var each = (1 << 12) / alphabetSize;
            var leftover = (1 << 12) % alphabetSize;
            for (var i = 0; i < alphabetSize; i++) {
                dist[i] = i < leftover ? each + 1 : each;
            }
        } else {
            var len = 0;
            while (len < 3 && br.readBool()) {
                len++;
            }
            var shift = br.readBits(len) + (1 << len) - 1;
            if (shift > 13) {
                throw new MediaException.Malformed("invalid ANS histogram");
            }
            alphabetSize = readU8(br) + 3;
            if (alphabetSize > tableSize) {
                throw new MediaException.Malformed("invalid ANS histogram");
            }
            var repeatStart = new int[tableSize];
            var repeatEnd = new int[tableSize];
            var repeats = 0;
            var omitLog = -1;
            var omitPos = -1;
            var idx = 0;
            while (idx < alphabetSize) {
                dist[idx] = readLogCount(br);
                if (dist[idx] == 13) {
                    var repeatCount = readU8(br) + 4;
                    if (idx + repeatCount > alphabetSize) {
                        throw new MediaException.Malformed("invalid ANS histogram");
                    }
                    repeatStart[repeats] = idx;
                    repeatEnd[repeats] = idx + repeatCount;
                    repeats++;
                    idx += repeatCount;
                    continue;
                }
                if (dist[idx] > omitLog) {
                    omitLog = dist[idx];
                    omitPos = idx;
                }
                idx++;
            }
            if (omitPos < 0 || (omitPos + 1 < tableSize && dist[omitPos + 1] == 13)) {
                throw new MediaException.Malformed("invalid ANS histogram");
            }
            var repeatIndex = 0;
            var acc = 0;
            var prevDist = 0;
            for (var i = 0; i < tableSize; i++) {
                if (repeatIndex < repeats && repeatStart[repeatIndex] <= i) {
                    if (repeatEnd[repeatIndex] == i) {
                        repeatIndex++;
                    } else {
                        dist[i] = prevDist;
                        acc += dist[i];
                        if (acc > 1 << 12) {
                            throw new MediaException.Malformed("invalid ANS histogram");
                        }
                        continue;
                    }
                }
                var code = dist[i];
                if (code == 0) {
                    prevDist = 0;
                    continue;
                }
                if (i == omitPos) {
                    prevDist = 0;
                    continue;
                }
                if (code > 1) {
                    var zeros = code - 1;
                    var bitCount = Math.clamp(shift - ((12 - zeros) >> 1), 0, zeros);
                    code = (1 << zeros) + (br.readBits(bitCount) << (zeros - bitCount));
                    dist[i] = code;
                }
                prevDist = dist[i];
                acc += dist[i];
                if (acc > 1 << 12) {
                    throw new MediaException.Malformed("invalid ANS histogram");
                }
            }
            dist[omitPos] = (1 << 12) - acc;
        }
        for (var i = 0; i < tableSize; i++) {
            if (dist[i] == 1 << 12) {
                for (var j = 0; j < tableSize; j++) {
                    buckets[base + j] = packBucket(i, 0, dist[j], bucketSize * j, dist[j] ^ (1 << 12));
                }
                return i;
            }
        }
        var aliasSymbol = new int[tableSize];
        var aliasOffset = new int[tableSize];
        var aliasCutoff = new int[tableSize];
        var underfull = new int[tableSize];
        var overfull = new int[tableSize];
        var underCount = 0;
        var overCount = 0;
        for (var i = 0; i < tableSize; i++) {
            aliasSymbol[i] = i < alphabetSize ? i : 0;
            aliasCutoff[i] = dist[i];
            if (dist[i] < bucketSize) {
                underfull[underCount++] = i;
            } else if (dist[i] > bucketSize) {
                overfull[overCount++] = i;
            }
        }
        while (overCount > 0 && underCount > 0) {
            var o = overfull[--overCount];
            var u = underfull[--underCount];
            var by = bucketSize - aliasCutoff[u];
            aliasCutoff[o] -= by;
            aliasSymbol[u] = o;
            aliasOffset[u] = aliasCutoff[o];
            if (aliasCutoff[o] < bucketSize) {
                underfull[underCount++] = o;
            } else if (aliasCutoff[o] > bucketSize) {
                overfull[overCount++] = o;
            }
        }
        for (var i = 0; i < tableSize; i++) {
            if (aliasCutoff[i] == bucketSize) {
                buckets[base + i] = packBucket(i, 0, dist[i], 0, 0);
            } else {
                buckets[base + i] = packBucket(aliasSymbol[i], aliasCutoff[i], dist[i],
                        (aliasOffset[i] - aliasCutoff[i]) & 0xFFFF, dist[i] ^ dist[aliasSymbol[i]]);
            }
        }
        return -1;
    }

    /**
     * Packs one alias-table bucket.
     *
     * @param symbol the alias symbol
     * @param cutoff the cutoff below which the bucket's own symbol is decoded
     * @param dist the frequency of the bucket's own symbol
     * @param offset the offset added for the alias symbol
     * @param distXor the XOR of the two frequencies
     * @return the packed bucket
     */
    private static long packBucket(int symbol, int cutoff, int dist, int offset, int distXor) {
        return (symbol & 0xFFL) | ((cutoff & 0xFFL) << 8) | ((dist & 0xFFFFL) << 16) | ((offset & 0xFFFFL) << 32)
                | ((distXor & 0xFFFFL) << 48);
    }

    /**
     * Reads the small integer coding of ANS histograms.
     *
     * @param br the bitstream
     * @return the value, from 0 to 255
     */
    private static int readU8(JxlBitReader br) {
        if (br.readBool()) {
            var n = br.readBits(3);
            return (1 << n) + br.readBits(n);
        }
        return 0;
    }

    /**
     * Reads the prefix-coded logarithm of an ANS frequency (ISO/IEC 18181-1 table C.4).
     *
     * @param br the bitstream
     * @return the code, from 0 to 13
     */
    private static int readLogCount(JxlBitReader br) {
        return switch (br.readBits(3)) {
            case 0 -> 10;
            case 1 -> {
                if (br.readBool()) {
                    yield 4;
                }
                if (br.readBool()) {
                    yield 0;
                }
                if (br.readBool()) {
                    yield 11;
                }
                if (br.readBool()) {
                    yield 13;
                }
                yield 12;
            }
            case 2 -> 7;
            case 3 -> br.readBool() ? 1 : 3;
            case 4 -> 6;
            case 5 -> 8;
            case 6 -> 9;
            default -> br.readBool() ? 2 : 5;
        };
    }

    /**
     * Reads a Brotli prefix code and builds its two-level lookup tables.
     *
     * @param br the bitstream
     * @param alphabetSize the number of symbols
     * @param parts the tables being built
     * @param cluster the cluster the code belongs to
     * @throws MediaException.Malformed if the code is invalid
     */
    private static void readPrefixCode(JxlBitReader br, int alphabetSize, Parts parts, int cluster) {
        if (alphabetSize == 1) {
            setSingleSymbol(parts, cluster, 0);
            return;
        }
        var hskip = br.readBits(2);
        if (hskip == 1) {
            var alphabetBits = 32 - Integer.numberOfLeadingZeros(alphabetSize - 1);
            var nsym = br.readBits(2) + 1;
            var lengths = new int[alphabetSize];
            switch (nsym) {
                case 1 -> {
                    var sym = br.readBits(alphabetBits);
                    if (sym >= alphabetSize) {
                        throw new MediaException.Malformed("invalid prefix code");
                    }
                    setSingleSymbol(parts, cluster, sym);
                    return;
                }
                case 2 -> {
                    var a = br.readBits(alphabetBits);
                    var b = br.readBits(alphabetBits);
                    setLength(lengths, a, 1);
                    setLength(lengths, b, 1);
                }
                case 3 -> {
                    var a = br.readBits(alphabetBits);
                    var b = br.readBits(alphabetBits);
                    var c = br.readBits(alphabetBits);
                    setLength(lengths, a, 1);
                    setLength(lengths, b, 2);
                    setLength(lengths, c, 2);
                }
                default -> {
                    var a = br.readBits(alphabetBits);
                    var b = br.readBits(alphabetBits);
                    var c = br.readBits(alphabetBits);
                    var d = br.readBits(alphabetBits);
                    if (br.readBool()) {
                        setLength(lengths, a, 1);
                        setLength(lengths, b, 2);
                        setLength(lengths, c, 3);
                        setLength(lengths, d, 3);
                    } else {
                        setLength(lengths, a, 2);
                        setLength(lengths, b, 2);
                        setLength(lengths, c, 2);
                        setLength(lengths, d, 2);
                    }
                }
            }
            buildPrefixTables(lengths, parts, cluster);
            return;
        }
        var codeLengthLengths = new int[18];
        var bitAcc = 0;
        var nonzeroCount = 0;
        var nonzeroSymbol = 0;
        for (var i = hskip; i < CODE_LENGTH_ORDER.length; i++) {
            var idx = CODE_LENGTH_ORDER[i];
            var base = br.readU32(0, 0, 4, 0, 3, 0, 8, 0);
            int len;
            if (base == 8) {
                if (br.readBool()) {
                    len = br.readBool() ? 5 : 1;
                } else {
                    len = 2;
                }
            } else {
                len = base;
            }
            codeLengthLengths[idx] = len;
            if (len != 0) {
                nonzeroCount++;
                nonzeroSymbol = idx;
                bitAcc += 32 >> len;
                if (bitAcc == 32) {
                    break;
                }
                if (bitAcc > 32) {
                    throw new MediaException.Malformed("invalid prefix code");
                }
            }
        }
        var codeLengthCode = new Parts();
        codeLengthCode.prefixTop = new int[1][];
        codeLengthCode.prefixTopBits = new int[1];
        codeLengthCode.prefixSecond = new int[1][];
        codeLengthCode.singleSymbol = new int[1];
        if (nonzeroCount == 1) {
            setSingleSymbol(codeLengthCode, 0, nonzeroSymbol);
        } else if (bitAcc != 32) {
            throw new MediaException.Malformed("invalid prefix code");
        } else {
            buildPrefixTables(codeLengthLengths, codeLengthCode, 0);
        }
        var top = codeLengthCode.prefixTop[0];
        var topBits = codeLengthCode.prefixTopBits[0];
        var second = codeLengthCode.prefixSecond[0];

        var lengths = new int[alphabetSize];
        bitAcc = 0;
        var prevSym = 8;
        var lastNonzero = 8;
        var lastRepeatCount = 0;
        var repeatCount = 0;
        var repeatSym = 0;
        for (var i = 0; i < alphabetSize; i++) {
            if (repeatCount > 0) {
                lengths[i] = repeatSym;
                repeatCount--;
            } else {
                var sym = readPrefixSymbol(br, top, topBits, second);
                switch (sym) {
                    case 0 -> {
                    }
                    case 16 -> {
                        repeatCount = br.readBits(2) + 3;
                        if (prevSym == 16) {
                            repeatCount += lastRepeatCount * 3 - 8;
                            lastRepeatCount += repeatCount;
                        } else {
                            lastRepeatCount = repeatCount;
                        }
                        repeatSym = lastNonzero;
                        lengths[i] = repeatSym;
                        repeatCount--;
                    }
                    case 17 -> {
                        repeatCount = br.readBits(3) + 3;
                        if (prevSym == 17) {
                            repeatCount += lastRepeatCount * 7 - 16;
                            lastRepeatCount += repeatCount;
                        } else {
                            lastRepeatCount = repeatCount;
                        }
                        repeatSym = 0;
                        lengths[i] = 0;
                        repeatCount--;
                    }
                    default -> {
                        lengths[i] = sym;
                        lastNonzero = sym;
                    }
                }
                prevSym = sym;
                if (repeatCount < 0 || repeatCount > alphabetSize) {
                    throw new MediaException.Malformed("invalid prefix code");
                }
            }
            if (lengths[i] != 0) {
                bitAcc += 1 << (MAX_PREFIX_BITS - lengths[i]);
                if (bitAcc > 1 << MAX_PREFIX_BITS) {
                    throw new MediaException.Malformed("invalid prefix code");
                }
                if (bitAcc == 1 << MAX_PREFIX_BITS && repeatCount == 0) {
                    break;
                }
            }
        }
        if (bitAcc != 1 << MAX_PREFIX_BITS || repeatCount > 0) {
            throw new MediaException.Malformed("invalid prefix code");
        }
        buildPrefixTables(lengths, parts, cluster);
    }

    /**
     * Sets the code length of a symbol of a simple prefix code.
     *
     * @param lengths the code lengths
     * @param symbol the symbol index
     * @param length the code length in bits
     * @throws MediaException.Malformed if the symbol is outside the alphabet
     */
    private static void setLength(int[] lengths, int symbol, int length) {
        if (symbol >= lengths.length) {
            throw new MediaException.Malformed("invalid prefix code");
        }
        lengths[symbol] = length;
    }

    /**
     * Records a prefix code that always decodes one symbol without reading bits.
     *
     * @param parts the tables being built
     * @param cluster the cluster index
     * @param symbol the symbol the code always decodes
     */
    private static void setSingleSymbol(Parts parts, int cluster, int symbol) {
        parts.prefixTop[cluster] = new int[]{symbol};
        parts.prefixTopBits[cluster] = 0;
        parts.prefixSecond[cluster] = new int[0];
        parts.singleSymbol[cluster] = symbol;
    }

    /**
     * Builds the two-level lookup tables of a prefix code from its code lengths.
     *
     * <p>A first-level entry holds the symbol in bits 0-15 and the code length in bits 16-23, or,
     * when bit 24 is set, the offset of a second-level chunk in bits 0-15 and its index mask in
     * bits 16-23.
     *
     * @param lengths the code length of each symbol, zero for absent symbols
     * @param parts the tables being built
     * @param cluster the cluster index
     * @throws MediaException.Malformed if the lengths do not form a complete code
     */
    private static void buildPrefixTables(int[] lengths, Parts parts, int cluster) {
        var maxLength = 0;
        var counts = new int[MAX_PREFIX_BITS + 1];
        for (var length : lengths) {
            if (length > 0) {
                counts[length]++;
                maxLength = Math.max(maxLength, length);
            }
        }
        var symsForLength = new int[MAX_PREFIX_BITS + 1][];
        var fill = new int[MAX_PREFIX_BITS + 1];
        for (var len = 1; len <= MAX_PREFIX_BITS; len++) {
            symsForLength[len] = new int[counts[len]];
        }
        for (var sym = 0; sym < lengths.length; sym++) {
            var len = lengths[sym];
            if (len > 0) {
                symsForLength[len][fill[len]++] = sym;
            }
        }
        var topBits = Math.min(maxLength, MAX_TOPLEVEL_BITS);
        var entries = new int[1 << topBits];
        var currentBits = 0;
        for (var len = 1; len <= topBits; len++) {
            var shifts = topBits - len;
            for (var sym : symsForLength[len]) {
                var entry = sym | (len << 16);
                Arrays.fill(entries, currentBits, currentBits + (1 << shifts), entry);
                currentBits += 1 << shifts;
            }
        }
        var second = new int[0];
        var secondLength = 0;
        if (topBits < maxLength) {
            int[] remaining = new int[0];
            var remainingCount = 0;
            var remainingBits = 0;
            for (var len = topBits + 1; len <= maxLength; len++) {
                if (symsForLength[len].length == 0) {
                    continue;
                }
                var chunkBits = len - topBits;
                var chunkSize = 1 << chunkBits;
                var chunk = new int[chunkSize];
                var chunkCount = 0;
                if (remainingCount > 0) {
                    var mult = 1 << (chunkBits - remainingBits);
                    for (var r = 0; r < remainingCount; r++) {
                        for (var m = 0; m < mult; m++) {
                            chunk[chunkCount++] = remaining[r];
                        }
                    }
                }
                for (var sym : symsForLength[len]) {
                    chunk[chunkCount++] = sym | (len << 16);
                    if (chunkCount == chunkSize) {
                        if (currentBits >= entries.length) {
                            throw new MediaException.Malformed("invalid prefix code");
                        }
                        entries[currentBits] = secondLength | ((chunkSize - 1) << 16) | (1 << 24);
                        if (secondLength + chunkSize > second.length) {
                            second = Arrays.copyOf(second, Math.max(second.length * 2, secondLength + chunkSize));
                        }
                        reverseInto(chunk, chunkSize, second, secondLength);
                        secondLength += chunkSize;
                        currentBits++;
                        chunk = new int[chunkSize];
                        chunkCount = 0;
                    }
                }
                remaining = chunk;
                remainingCount = chunkCount;
                remainingBits = chunkBits;
            }
            if (remainingCount != 0) {
                throw new MediaException.Malformed("invalid prefix code");
            }
        }
        if (currentBits != 1 << topBits) {
            throw new MediaException.Malformed("invalid prefix code");
        }
        var top = new int[entries.length];
        reverseInto(entries, entries.length, top, 0);
        parts.prefixTop[cluster] = top;
        parts.prefixTopBits[cluster] = topBits;
        parts.prefixSecond[cluster] = Arrays.copyOf(second, secondLength);
        parts.singleSymbol[cluster] = -1;
    }

    /**
     * Copies a power-of-two table into another in bit-reversed index order.
     *
     * @param source the table
     * @param length the table length, a power of two
     * @param target the destination
     * @param offset the index of the first destination entry
     */
    private static void reverseInto(int[] source, int length, int[] target, int offset) {
        var bits = Integer.numberOfTrailingZeros(length);
        if (bits == 0) {
            target[offset] = source[0];
            return;
        }
        for (var i = 0; i < length; i++) {
            var reversed = Integer.reverse(i) >>> (32 - bits);
            target[offset + i] = source[reversed];
        }
    }

    /**
     * Decodes one symbol of a prefix code.
     *
     * @param br the bitstream
     * @param top the first-level table
     * @param topBits the first-level table bits
     * @param second the second-level table
     * @return the symbol
     */
    private static int readPrefixSymbol(JxlBitReader br, int[] top, int topBits, int[] second) {
        var peeked = br.peek(MAX_PREFIX_BITS);
        var entry = top[peeked & ((1 << topBits) - 1)];
        if ((entry & (1 << 24)) != 0) {
            var index = (entry & 0xFFFF) + ((peeked >>> topBits) & ((entry >>> 16) & 0xFF));
            entry = second[index];
        }
        br.consume((entry >>> 16) & 0xFF);
        return entry & 0xFFFF;
    }

    /**
     * Reads the initial ANS state; does nothing for prefix codes.
     *
     * @param br the bitstream
     * @throws MediaException.Malformed if the bitstream is exhausted
     */
    public void begin(JxlBitReader br) {
        if (!prefixCoded) {
            ansState = br.readBits(32);
            ansStarted = true;
        }
    }

    /**
     * Checks that an ANS stream ended in the state the specification requires; prefix-coded
     * streams have no such check.
     *
     * @throws MediaException.Malformed if the final ANS state is wrong
     */
    public void checkFinalState() {
        if (!prefixCoded && ansStarted && ansState != ANS_FINAL_STATE) {
            throw new MediaException.Malformed("invalid final ANS state");
        }
    }

    /**
     * Returns the cluster of a context.
     *
     * @param context the context index
     * @return the cluster
     * @throws MediaException.Malformed if the context is out of range
     */
    public int cluster(int context) {
        if (context < 0 || context >= clusters.length) {
            throw new MediaException.Malformed("entropy context " + context + " out of range");
        }
        return clusters[context];
    }

    /**
     * Returns the number of contexts the decoder was read for, the LZ77 distance context included.
     *
     * @return the context count
     */
    public int contexts() {
        return clusters.length;
    }

    /**
     * Returns the cluster map.
     *
     * @return the cluster of every context, shared
     */
    public int[] clusterMap() {
        return clusters;
    }

    /**
     * Returns the value a cluster always decodes to, when its distribution has a single symbol
     * that encodes the value without extra bits and LZ77 is off.
     *
     * @param cluster the cluster index
     * @return the value, or -1 when the cluster can decode several values
     */
    public int singleToken(int cluster) {
        if (lz77) {
            return -1;
        }
        var symbol = singleSymbol[cluster];
        if (symbol < 0 || symbol >= (1 << splitExponent[cluster])) {
            return -1;
        }
        return symbol;
    }

    /**
     * Reads an integer of a context.
     *
     * @param br the bitstream
     * @param context the context index
     * @return the value, an unsigned 32-bit pattern
     * @throws MediaException.Malformed if the stream is invalid or truncated
     */
    public int readUint(JxlBitReader br, int context) {
        return readUintClustered(br, cluster(context), 0);
    }

    /**
     * Reads an integer of a cluster, with an LZ77 distance multiplier.
     *
     * @param br the bitstream
     * @param cluster the cluster index
     * @param distMultiplier the row width the special LZ77 distances refer to, or 0
     * @return the value, an unsigned 32-bit pattern
     * @throws MediaException.Malformed if the stream is invalid or truncated
     */
    public int readUintClustered(JxlBitReader br, int cluster, int distMultiplier) {
        if (!lz77) {
            var token = readSymbol(br, cluster);
            return readHybrid(br, token, splitExponent[cluster], msbInToken[cluster], lsbInToken[cluster]);
        }
        return readLz77(br, cluster, distMultiplier);
    }

    /**
     * Reads an integer through the LZ77 layer.
     *
     * @param br the bitstream
     * @param cluster the cluster of the literal
     * @param distMultiplier the row width of the special distances, or 0
     * @return the value
     * @throws MediaException.Malformed if a copy starts before any value was decoded
     */
    private int readLz77(JxlBitReader br, int cluster, int distMultiplier) {
        int value;
        if (numToCopy > 0) {
            value = window[copyPos & (WINDOW_SIZE - 1)];
            copyPos++;
            numToCopy--;
        } else {
            var token = readSymbol(br, cluster);
            if (Integer.compareUnsigned(token, lzMinSymbol) >= 0) {
                if (numDecoded == 0) {
                    throw new MediaException.Malformed("LZ77 copy before any decoded value");
                }
                var distCluster = clusters[clusters.length - 1];
                var length = readHybrid(br, token - lzMinSymbol, lzSplitExponent, lzMsbInToken, lzLsbInToken);
                var total = (long) (length & 0xFFFF_FFFFL) + lzMinLength;
                if (total > 0xFFFF_FFFFL) {
                    throw new MediaException.Malformed("LZ77 length overflow");
                }
                var distToken = readSymbol(br, distCluster);
                var distance = readHybrid(br, distToken, splitExponent[distCluster], msbInToken[distCluster],
                        lsbInToken[distCluster]);
                long dist;
                if (distMultiplier == 0) {
                    dist = distance & 0xFFFF_FFFFL;
                } else if (Integer.compareUnsigned(distance, 120) < 0) {
                    var dx = SPECIAL_DISTANCES[distance * 2];
                    var dy = SPECIAL_DISTANCES[distance * 2 + 1];
                    dist = Math.max((long) dx + (long) distMultiplier * dy - 1, 0);
                } else {
                    dist = (distance & 0xFFFF_FFFFL) - 120;
                }
                dist = Math.min(Math.min(WINDOW_SIZE - 1L, dist) + 1, numDecoded & 0xFFFF_FFFFL);
                copyPos = (int) (numDecoded - dist);
                numToCopy = (int) Math.min(total, Integer.MAX_VALUE);
                value = window[copyPos & (WINDOW_SIZE - 1)];
                copyPos++;
                numToCopy--;
            } else {
                value = readHybrid(br, token, splitExponent[cluster], msbInToken[cluster], lsbInToken[cluster]);
            }
        }
        var slot = numDecoded & (WINDOW_SIZE - 1);
        if (window == null) {
            window = new int[1024];
        } else if (slot >= window.length) {
            window = Arrays.copyOf(window, Math.min(WINDOW_SIZE, window.length * 2));
        }
        window[slot] = value;
        numDecoded++;
        return value;
    }

    /**
     * Reads one symbol of a cluster.
     *
     * @param br the bitstream
     * @param cluster the cluster index
     * @return the symbol
     * @throws MediaException.Malformed if the bitstream is exhausted
     */
    private int readSymbol(JxlBitReader br, int cluster) {
        if (prefixCoded) {
            return readPrefixSymbol(br, prefixTop[cluster], prefixTopBits[cluster], prefixSecond[cluster]);
        }
        if (!ansStarted) {
            ansState = br.readBits(32);
            ansStarted = true;
        }
        var state = ansState;
        var idx = state & 0xFFF;
        var i = idx >>> logBucketSize;
        var pos = idx & ((1 << logBucketSize) - 1);
        var bucket = ansBuckets[cluster * tableSize + i];
        var aliasSymbol = (int) (bucket & 0xFF);
        var cutoff = (int) ((bucket >>> 8) & 0xFF);
        var dist = (int) ((bucket >>> 16) & 0xFFFF);
        var alias = pos >= cutoff;
        var conditional = alias ? bucket : 0;
        var offset = ((int) (conditional >>> 32) + pos) & 0xFFFF;
        dist ^= (int) (conditional >>> 48);
        var symbol = alias ? aliasSymbol : i;
        var next = (state >>> 12) * dist + offset;
        if ((next >>> 16) == 0) {
            next = (next << 16) | br.readBits(16);
        }
        ansState = next;
        return symbol;
    }

    /**
     * Reads the extra bits of a hybrid integer token.
     *
     * @param br the bitstream
     * @param token the hybrid integer token, at least the split
     * @param splitExponent the split exponent
     * @param msb the most significant token bits
     * @param lsb the least significant token bits
     * @return the value, an unsigned 32-bit pattern
     * @throws MediaException.Malformed if the bitstream is exhausted
     */
    private static int readHybrid(JxlBitReader br, int token, int splitExponent, int msb, int lsb) {
        var split = 1 << splitExponent;
        if (Integer.compareUnsigned(token, split) < 0) {
            return token;
        }
        var n = (splitExponent - (msb + lsb) + ((token - split) >>> (msb + lsb))) & 31;
        long rest = br.readBits(n) & 0xFFFF_FFFFL;
        var low = token & ((1 << lsb) - 1);
        long high = ((token >>> lsb) & ((1 << msb) - 1)) | (1L << msb);
        return (int) ((((high << n) | rest) << lsb) | low);
    }
}
