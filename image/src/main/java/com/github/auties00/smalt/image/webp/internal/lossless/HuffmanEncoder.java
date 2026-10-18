package com.github.auties00.smalt.image.webp.internal.lossless;

import java.util.Arrays;

/**
 * Builds length-limited canonical prefix codes from symbol counts and serializes their code
 * lengths with the run-length code-length alphabet (codes 16, 17 and 18).
 *
 * @implNote This implementation follows libwebp's {@code src/utils/huffman_encode_utils.c}
 *: counts are first smoothed to favor run-length coding of the code lengths
 * ({@code OptimizeHuffmanForRle}), the tree is built by repeatedly merging the two least frequent
 * nodes, and the length limit is met by raising the smallest counts to a doubling floor until the
 * tree is shallow enough.
 */
final class HuffmanEncoder {
    /**
     * The longest code length.
     */
    private static final int MAX_ALLOWED_CODE_LENGTH = 15;

    /**
     * Prevents instantiation.
     */
    private HuffmanEncoder() {
        throw new AssertionError();
    }

    /**
     * Holds a prefix code: the length and bit-reversed code of every symbol.
     */
    static final class Code {
        /**
         * The number of symbols.
         */
        final int numSymbols;

        /**
         * The code length of every symbol, {@code 0} when unused.
         */
        final int[] codeLengths;

        /**
         * The code of every symbol, bit-reversed for least-significant-bit-first writing.
         */
        final int[] codes;

        /**
         * Allocates an empty code.
         *
         * @param numSymbols the number of symbols
         */
        Code(int numSymbols) {
            this.numSymbols = numSymbols;
            this.codeLengths = new int[numSymbols];
            this.codes = new int[numSymbols];
        }
    }

    /**
     * Builds the length-limited prefix code of a histogram.
     *
     * @param histogram the counts, modified in place by the run-length smoothing
     * @param depthLimit the longest code length allowed
     * @param code the destination
     */
    static void createTree(int[] histogram, int depthLimit, Code code) {
        var numSymbols = code.numSymbols;
        var goodForRle = new boolean[numSymbols];
        optimizeForRle(numSymbols, goodForRle, histogram);
        generateOptimalTree(histogram, numSymbols, depthLimit, code.codeLengths);
        convertBitDepthsToSymbols(code);
    }

    /**
     * Smooths counts so that the resulting code lengths form longer runs.
     *
     * @param length the number of counts
     * @param goodForRle scratch flags, all {@code false} on entry
     * @param counts the counts, modified in place
     */
    private static void optimizeForRle(int length, boolean[] goodForRle, int[] counts) {
        for (; length >= 0; --length) {
            if (length == 0) {
                return;
            }
            if (counts[length - 1] != 0) {
                break;
            }
        }
        var symbol = counts[0];
        var stride = 0;
        for (var i = 0; i < length + 1; i++) {
            if (i == length || counts[i] != symbol) {
                if ((symbol == 0 && stride >= 5) || (symbol != 0 && stride >= 7)) {
                    for (var k = 0; k < stride; k++) {
                        goodForRle[i - k - 1] = true;
                    }
                }
                stride = 1;
                if (i != length) {
                    symbol = counts[i];
                }
            } else {
                ++stride;
            }
        }
        long strideL = 0;
        long limit = counts[0] & 0xFFFFFFFFL;
        long sum = 0;
        for (var i = 0; i < length + 1; i++) {
            if (i == length || goodForRle[i] || (i != 0 && goodForRle[i - 1]) || !collapsibleLong(counts[i], limit)) {
                if (strideL >= 4 || (strideL >= 3 && sum == 0)) {
                    var count = (sum + strideL / 2) / strideL;
                    if (count < 1) {
                        count = 1;
                    }
                    if (sum == 0) {
                        count = 0;
                    }
                    for (var k = 0; k < strideL; k++) {
                        counts[(int) (i - k - 1)] = (int) count;
                    }
                }
                strideL = 0;
                sum = 0;
                if (i < length - 3) {
                    limit = ((counts[i] & 0xFFFFFFFFL) + (counts[i + 1] & 0xFFFFFFFFL) + (counts[i + 2] & 0xFFFFFFFFL)
                            + (counts[i + 3] & 0xFFFFFFFFL) + 2) / 4;
                } else if (i < length) {
                    limit = counts[i] & 0xFFFFFFFFL;
                } else {
                    limit = 0;
                }
            }
            ++strideL;
            if (i != length) {
                sum += counts[i] & 0xFFFFFFFFL;
                if (strideL >= 4) {
                    limit = (sum + strideL / 2) / strideL;
                }
            }
        }
    }

    /**
     * Returns whether a count and a limit differ by less than 4, reading the count at an index
     * that may be the end of the array.
     *
     * @param count the count, or any value at the end of the array
     * @param limit the stride limit
     * @return whether they are close
     */
    private static boolean collapsibleLong(int count, long limit) {
        return Math.abs((int) (count - limit)) < 4;
    }

    /**
     * Builds the code lengths of the optimal prefix code with lengths limited to
     * {@code depthLimit}.
     *
     * @param histogram the counts
     * @param histogramSize the number of symbols
     * @param depthLimit the longest code length
     * @param bitDepths the destination lengths; entries of unused symbols are left unchanged
     */
    private static void generateOptimalTree(int[] histogram, int histogramSize, int depthLimit, int[] bitDepths) {
        var treeSizeOrig = 0;
        for (var i = 0; i < histogramSize; i++) {
            if (histogram[i] != 0) {
                ++treeSizeOrig;
            }
        }
        if (treeSizeOrig == 0) {
            return;
        }
        var capacity = 2 * treeSizeOrig + 1;
        var treeCount = new long[capacity];
        var treeValue = new int[capacity];
        var treeLeft = new int[capacity];
        var treeRight = new int[capacity];
        var poolCount = new long[capacity];
        var poolValue = new int[capacity];
        var poolLeft = new int[capacity];
        var poolRight = new int[capacity];
        var keys = new long[treeSizeOrig];
        var stack = new int[2 * capacity];
        var stackDepth = new int[2 * capacity];
        for (long countMin = 1; ; countMin *= 2) {
            var treeSize = treeSizeOrig;
            var idx = 0;
            for (var j = 0; j < histogramSize; j++) {
                if (histogram[j] != 0) {
                    var count = Math.max(histogram[j] & 0xFFFFFFFFL, countMin);
                    keys[idx++] = ((Integer.MAX_VALUE - Math.min(count, Integer.MAX_VALUE)) << 32) | j;
                }
            }
            Arrays.sort(keys, 0, treeSize);
            for (var k = 0; k < treeSize; k++) {
                var j = (int) keys[k];
                treeCount[k] = Math.max(histogram[j] & 0xFFFFFFFFL, countMin);
                treeValue[k] = j;
                treeLeft[k] = -1;
                treeRight[k] = -1;
            }
            if (treeSize > 1) {
                var poolSize = 0;
                while (treeSize > 1) {
                    poolCount[poolSize] = treeCount[treeSize - 1];
                    poolValue[poolSize] = treeValue[treeSize - 1];
                    poolLeft[poolSize] = treeLeft[treeSize - 1];
                    poolRight[poolSize] = treeRight[treeSize - 1];
                    ++poolSize;
                    poolCount[poolSize] = treeCount[treeSize - 2];
                    poolValue[poolSize] = treeValue[treeSize - 2];
                    poolLeft[poolSize] = treeLeft[treeSize - 2];
                    poolRight[poolSize] = treeRight[treeSize - 2];
                    ++poolSize;
                    var count = poolCount[poolSize - 1] + poolCount[poolSize - 2];
                    treeSize -= 2;
                    var k = 0;
                    while (k < treeSize && treeCount[k] > count) {
                        ++k;
                    }
                    System.arraycopy(treeCount, k, treeCount, k + 1, treeSize - k);
                    System.arraycopy(treeValue, k, treeValue, k + 1, treeSize - k);
                    System.arraycopy(treeLeft, k, treeLeft, k + 1, treeSize - k);
                    System.arraycopy(treeRight, k, treeRight, k + 1, treeSize - k);
                    treeCount[k] = count;
                    treeValue[k] = -1;
                    treeLeft[k] = poolSize - 1;
                    treeRight[k] = poolSize - 2;
                    ++treeSize;
                }
                var top = 0;
                if (treeLeft[0] >= 0) {
                    stack[top] = treeLeft[0];
                    stackDepth[top++] = 1;
                    stack[top] = treeRight[0];
                    stackDepth[top++] = 1;
                } else {
                    bitDepths[treeValue[0]] = 0;
                }
                while (top > 0) {
                    var node = stack[--top];
                    var depth = stackDepth[top];
                    if (poolLeft[node] >= 0) {
                        stack[top] = poolLeft[node];
                        stackDepth[top++] = depth + 1;
                        stack[top] = poolRight[node];
                        stackDepth[top++] = depth + 1;
                    } else {
                        bitDepths[poolValue[node]] = depth;
                    }
                }
            } else {
                bitDepths[treeValue[0]] = 1;
            }
            var maxDepth = bitDepths[0];
            for (var j = 1; j < histogramSize; j++) {
                maxDepth = Math.max(maxDepth, bitDepths[j]);
            }
            if (maxDepth <= depthLimit) {
                break;
            }
        }
    }

    /**
     * Assigns canonical codes to code lengths, bit-reversed.
     *
     * @param code the code whose lengths are set
     */
    private static void convertBitDepthsToSymbols(Code code) {
        var depthCount = new int[MAX_ALLOWED_CODE_LENGTH + 1];
        var nextCode = new int[MAX_ALLOWED_CODE_LENGTH + 1];
        for (var i = 0; i < code.numSymbols; i++) {
            ++depthCount[code.codeLengths[i]];
        }
        depthCount[0] = 0;
        var c = 0;
        for (var i = 1; i <= MAX_ALLOWED_CODE_LENGTH; i++) {
            c = (c + depthCount[i - 1]) << 1;
            nextCode[i] = c;
        }
        for (var i = 0; i < code.numSymbols; i++) {
            var length = code.codeLengths[i];
            code.codes[i] = reverseBits(length, nextCode[length]++);
        }
    }

    /**
     * Reverses the low bits of a value.
     *
     * @param numBits the number of bits
     * @param bits the value
     * @return the reversed value
     */
    private static int reverseBits(int numBits, int bits) {
        return numBits == 0 ? 0 : Integer.reverse(bits) >>> (32 - numBits);
    }

    /**
     * Serializes code lengths with the run-length alphabet.
     *
     * @param code the code whose lengths are serialized
     * @param tokenCode the destination token symbols
     * @param tokenExtra the destination token extra bits
     * @return the number of tokens
     */
    static int compressTree(Code code, int[] tokenCode, int[] tokenExtra) {
        var n = 0;
        var prevValue = 8;
        var i = 0;
        var depthSize = code.numSymbols;
        var lengths = code.codeLengths;
        while (i < depthSize) {
            var value = lengths[i];
            var k = i + 1;
            while (k < depthSize && lengths[k] == value) {
                ++k;
            }
            var runs = k - i;
            if (value == 0) {
                n = codeRepeatedZeros(runs, tokenCode, tokenExtra, n);
            } else {
                n = codeRepeatedValues(runs, tokenCode, tokenExtra, n, value, prevValue);
                prevValue = value;
            }
            i += runs;
        }
        return n;
    }

    /**
     * Emits tokens for a run of one non-zero length.
     *
     * @param repetitions the run length
     * @param tokenCode the token symbols
     * @param tokenExtra the token extra bits
     * @param n the index of the next token
     * @param value the repeated length
     * @param prevValue the previous non-zero length
     * @return the index after the emitted tokens
     */
    private static int codeRepeatedValues(int repetitions, int[] tokenCode, int[] tokenExtra, int n, int value, int prevValue) {
        if (value != prevValue) {
            tokenCode[n] = value;
            tokenExtra[n++] = 0;
            --repetitions;
        }
        while (repetitions >= 1) {
            if (repetitions < 3) {
                for (var i = 0; i < repetitions; i++) {
                    tokenCode[n] = value;
                    tokenExtra[n++] = 0;
                }
                break;
            } else if (repetitions < 7) {
                tokenCode[n] = 16;
                tokenExtra[n++] = repetitions - 3;
                break;
            } else {
                tokenCode[n] = 16;
                tokenExtra[n++] = 3;
                repetitions -= 6;
            }
        }
        return n;
    }

    /**
     * Emits tokens for a run of zero lengths.
     *
     * @param repetitions the run length
     * @param tokenCode the token symbols
     * @param tokenExtra the token extra bits
     * @param n the index of the next token
     * @return the index after the emitted tokens
     */
    private static int codeRepeatedZeros(int repetitions, int[] tokenCode, int[] tokenExtra, int n) {
        while (repetitions >= 1) {
            if (repetitions < 3) {
                for (var i = 0; i < repetitions; i++) {
                    tokenCode[n] = 0;
                    tokenExtra[n++] = 0;
                }
                break;
            } else if (repetitions < 11) {
                tokenCode[n] = 17;
                tokenExtra[n++] = repetitions - 3;
                break;
            } else if (repetitions < 139) {
                tokenCode[n] = 18;
                tokenExtra[n++] = repetitions - 11;
                break;
            } else {
                tokenCode[n] = 18;
                tokenExtra[n++] = 0x7F;
                repetitions -= 138;
            }
        }
        return n;
    }
}
