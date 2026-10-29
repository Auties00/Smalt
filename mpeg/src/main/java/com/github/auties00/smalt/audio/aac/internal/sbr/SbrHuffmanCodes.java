package com.github.auties00.smalt.audio.aac.internal.sbr;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;

/**
 * Holds the codewords of one spectral band replication or parametric stereo codebook, derived from the decoding
 * tree of {@link SbrHuffman}, so that the encoder and the decoder share one definition of every codebook.
 *
 * <p>Values outside {@link #min()} to {@link #max()} have no codeword. Instances are immutable and thread-safe.
 */
final class SbrHuffmanCodes {
    static final SbrHuffmanCodes ENVELOPE_15_TIME = new SbrHuffmanCodes(SbrHuffman.ENVELOPE_15_TIME);
    static final SbrHuffmanCodes ENVELOPE_15_FREQUENCY = new SbrHuffmanCodes(SbrHuffman.ENVELOPE_15_FREQUENCY);
    static final SbrHuffmanCodes BALANCE_15_TIME = new SbrHuffmanCodes(SbrHuffman.BALANCE_15_TIME);
    static final SbrHuffmanCodes BALANCE_15_FREQUENCY = new SbrHuffmanCodes(SbrHuffman.BALANCE_15_FREQUENCY);
    static final SbrHuffmanCodes ENVELOPE_30_TIME = new SbrHuffmanCodes(SbrHuffman.ENVELOPE_30_TIME);
    static final SbrHuffmanCodes ENVELOPE_30_FREQUENCY = new SbrHuffmanCodes(SbrHuffman.ENVELOPE_30_FREQUENCY);
    static final SbrHuffmanCodes BALANCE_30_TIME = new SbrHuffmanCodes(SbrHuffman.BALANCE_30_TIME);
    static final SbrHuffmanCodes BALANCE_30_FREQUENCY = new SbrHuffmanCodes(SbrHuffman.BALANCE_30_FREQUENCY);
    static final SbrHuffmanCodes NOISE_TIME = new SbrHuffmanCodes(SbrHuffman.NOISE_TIME);
    static final SbrHuffmanCodes NOISE_BALANCE_TIME = new SbrHuffmanCodes(SbrHuffman.NOISE_BALANCE_TIME);
    static final SbrHuffmanCodes IID_TIME = new SbrHuffmanCodes(SbrHuffman.IID_TIME);
    static final SbrHuffmanCodes IID_FREQUENCY = new SbrHuffmanCodes(SbrHuffman.IID_FREQUENCY);
    static final SbrHuffmanCodes ICC_TIME = new SbrHuffmanCodes(SbrHuffman.ICC_TIME);
    static final SbrHuffmanCodes ICC_FREQUENCY = new SbrHuffmanCodes(SbrHuffman.ICC_FREQUENCY);

    /**
     * The codeword of each value, at index {@code value + 64}, right-aligned.
     */
    private final int[] codes = new int[128];

    /**
     * The length in bits of each codeword, 0 for a value without one.
     */
    private final int[] lengths = new int[128];

    /**
     * The smallest value with a codeword.
     */
    private final int min;

    /**
     * The largest value with a codeword.
     */
    private final int max;

    /**
     * Derives the codewords of a decoding tree.
     *
     * @param tree the tree, in the form {@link SbrHuffman} decodes
     */
    private SbrHuffmanCodes(byte[] tree) {
        walk(tree, 0, 0, 0);
        var low = Integer.MAX_VALUE;
        var high = Integer.MIN_VALUE;
        for (var v = 0; v < 128; v++) {
            if (lengths[v] != 0) {
                low = Math.min(low, v - 64);
                high = Math.max(high, v - 64);
            }
        }
        for (var v = low; v <= high; v++) {
            if (lengths[v + 64] == 0) {
                throw new AssertionError("codebook with a gap at " + v);
            }
        }
        this.min = low;
        this.max = high;
    }

    /**
     * Visits a node of the tree, recording the codeword of every leaf below it.
     *
     * @param tree the decoding tree
     * @param node the index of the inner node
     * @param code the bits of the path to the node
     * @param length the length of the path
     */
    private void walk(byte[] tree, int node, int code, int length) {
        for (var bit = 0; bit < 2; bit++) {
            var next = tree[2 * node + bit];
            var path = (code << 1) | bit;
            if (next >= 0) {
                walk(tree, next, path, length + 1);
            } else {
                codes[next + 128] = path;
                lengths[next + 128] = length + 1;
            }
        }
    }

    /**
     * {@return the smallest value with a codeword}
     */
    int min() {
        return min;
    }

    /**
     * {@return the largest value with a codeword}
     */
    int max() {
        return max;
    }

    /**
     * Returns the length of a value's codeword.
     *
     * @param value a value from {@link #min()} to {@link #max()}
     * @return the length in bits
     */
    int length(int value) {
        return lengths[value + 64];
    }

    /**
     * Writes a value's codeword.
     *
     * @param writer the destination
     * @param value a value from {@link #min()} to {@link #max()}
     */
    void write(MsbBitWriter writer, int value) {
        writer.write(codes[value + 64], lengths[value + 64]);
    }
}
