package com.github.auties00.smalt.video.av1.internal.encoder;

import java.util.Arrays;

/**
 * Records the symbols a {@link RangeEncoder} is asked to code, so a superblock row can be decided
 * and reconstructed on one thread and entropy coded later, in raster order, on another.
 *
 * <p>Every symbol is one {@code int}: its kind in the low three bits and its operands above them.
 * Adaptive symbols record the offset of their CDF, not its probabilities, so replaying them
 * through {@link #replay} codes them against the CDFs the replaying coder has adapted by then,
 * exactly as coding them in place would have. The binary partition symbols of the frame's right
 * and bottom edges, whose probability is gathered from the partition CDF, are recorded the same
 * way and gathered at replay. A deferred literal records only its position and a tag: its value and
 * width, decided after the symbol's position was reached, are supplied to
 * {@link #replay(RangeEncoder, char[], int, int)}, or looked up by tag in the table given to
 * {@link #replay(RangeEncoder, char[], int[], int)}.
 */
final class SymbolLog {
    /**
     * The kind of an adaptive multi-symbol: CDF offset, symbol count and value.
     */
    private static final int SYMBOL = 0;

    /**
     * The kind of an adaptive binary symbol: CDF offset and bit.
     */
    private static final int BOOL = 1;

    /**
     * The kind of a binary symbol with a fixed probability: probability and bit.
     */
    private static final int FIXED = 2;

    /**
     * The kind of a binary partition symbol at the frame's right or bottom edge: partition CDF
     * offset, block level, edge and bit.
     */
    private static final int EDGE = 3;

    /**
     * The kind of a literal of equiprobable bits whose value and width the replay supplies.
     */
    private static final int DEFERRED = 4;

    /**
     * The mask of the kind bits.
     */
    private static final int KIND = 7;

    /**
     * The recorded symbols.
     */
    private int[] data = new int[1 << 12];

    /**
     * The number of recorded symbols.
     */
    private int size;

    /**
     * Creates an empty log.
     */
    SymbolLog() {
    }

    /**
     * Forgets the recorded symbols, keeping the storage.
     */
    void clear() {
        size = 0;
    }

    /**
     * Appends one recorded symbol.
     *
     * @param value the packed symbol
     */
    private void add(int value) {
        if (size == data.length) {
            data = Arrays.copyOf(data, size << 1);
        }
        data[size++] = value;
    }

    /**
     * Records an adaptive multi-symbol ({@link RangeEncoder#encodeSymbol}).
     *
     * @param off the offset of the symbol's CDF
     * @param val the symbol, from 0 to {@code n}
     * @param n the number of symbols minus one, at most 15
     */
    void symbol(int off, int val, int n) {
        add(off << 11 | n << 7 | val << 3 | SYMBOL);
    }

    /**
     * Records an adaptive binary symbol ({@link RangeEncoder#encodeBool}).
     *
     * @param off the offset of the symbol's CDF
     * @param bit the bit, 0 or 1
     */
    void bool(int off, int bit) {
        add(off << 4 | bit << 3 | BOOL);
    }

    /**
     * Records a binary symbol with a fixed probability.
     *
     * @param bit the bit, 0 or 1
     * @param f the inverse probability of a zero, scaled by 32768
     */
    void fixed(int bit, int f) {
        add(f << 4 | bit << 3 | FIXED);
    }

    /**
     * Records a binary partition symbol at the frame's right or bottom edge, whose probability
     * is gathered from the partition CDF when the symbol is coded.
     *
     * @param pc the offset of the partition CDF
     * @param bl the block level
     * @param bottom whether the block crosses the bottom edge, else the right one
     * @param bit the bit, 1 for a split
     */
    void edge(int pc, int bl, boolean bottom, int bit) {
        add(pc << 9 | (bottom ? 1 : 0) << 8 | bl << 5 | bit << 3 | EDGE);
    }

    /**
     * Records a literal of equiprobable bits whose value and width are supplied at replay.
     *
     * @param tag the index of the literal's value in a replay's value table
     */
    void deferred(int tag) {
        add(tag << 3 | DEFERRED);
    }

    /**
     * Codes the recorded symbols in order.
     *
     * @param ec the coder, not recording
     * @param cdf the CDFs the coder adapts
     */
    void replay(RangeEncoder ec, char[] cdf) {
        replay(ec, cdf, 0, 0);
    }

    /**
     * Codes the recorded symbols in order, with one value and width for every deferred literal.
     *
     * @param ec the coder, not recording
     * @param cdf the CDFs the coder adapts
     * @param deferred the value of the deferred literals
     * @param bits the width of the deferred literals
     */
    void replay(RangeEncoder ec, char[] cdf, int deferred, int bits) {
        replay(ec, cdf, null, deferred, bits);
    }

    /**
     * Codes the recorded symbols in order, looking up the value of every deferred literal by its
     * tag.
     *
     * @param ec the coder, not recording
     * @param cdf the CDFs the coder adapts
     * @param values the value of each tag
     * @param bits the width of the deferred literals
     */
    void replay(RangeEncoder ec, char[] cdf, int[] values, int bits) {
        replay(ec, cdf, values, 0, bits);
    }

    /**
     * Codes the recorded symbols in order.
     *
     * @param ec the coder, not recording
     * @param cdf the CDFs the coder adapts
     * @param values the value of each tag, or {@code null} to code {@code deferred} for every
     * deferred literal
     * @param deferred the value of the deferred literals without a table
     * @param bits the width of the deferred literals
     */
    private void replay(RangeEncoder ec, char[] cdf, int[] values, int deferred, int bits) {
        var d = data;
        for (var i = 0; i < size; i++) {
            var v = d[i];
            switch (v & KIND) {
                case SYMBOL -> ec.encodeSymbol(cdf, v >>> 11, (v >>> 3) & 15, (v >>> 7) & 15);
                case BOOL -> ec.encodeBool(cdf, v >>> 4, (v >>> 3) & 1);
                case FIXED -> ec.encodeBoolFixed((v >>> 3) & 1, v >>> 4);
                case DEFERRED -> ec.encodeBits(values == null ? deferred : values[v >>> 3], bits);
                default -> {
                    var pc = v >>> 9;
                    var bl = (v >>> 5) & 7;
                    var prob = (v & 0x100) != 0 ? TileEncoder.topPartitionProb(cdf, pc, bl)
                            : TileEncoder.leftPartitionProb(cdf, pc, bl);
                    ec.encodeBoolFixed((v >>> 3) & 1, prob);
                }
            }
        }
    }
}
