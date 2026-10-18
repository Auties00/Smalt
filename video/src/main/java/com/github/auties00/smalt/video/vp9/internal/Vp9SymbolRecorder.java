package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

/**
 * Records the booleans written to it, with their probabilities, instead of coding them, so that a
 * {@link Vp9BoolEncoder} can code them later with {@link Vp9BoolEncoder#replay}, possibly on
 * another thread while more booleans are recorded.
 *
 * <p>Each symbol is one {@code int}: the probability of a zero shifted left by one, and the
 * boolean in the low bit. A probability of 0 never occurs, so the symbols 0 and 1 serve as marks
 * ({@link #TOKENS}, {@link #ROW}) that tell the replaying thread where to insert work of its own.
 * The recorder writes the symbols of a partition in order; a thread that
 * replays them must read {@link #symbols()} again after every synchronization with the recording
 * thread, since the array is replaced when it grows (the symbols already recorded keep their
 * values in the old array).
 */
final class Vp9SymbolRecorder extends Vp9BoolEncoder {
    /**
     * The mark of the place of a block's tokens.
     */
    static final int TOKENS = 0;

    /**
     * The mark of the start of a superblock row.
     */
    static final int ROW = 1;

    /**
     * The recorded symbols.
     */
    private int[] symbols;

    /**
     * The number of recorded symbols.
     */
    private int size;

    /**
     * Creates a recorder with an initial capacity.
     *
     * @param capacity the initial number of symbols
     */
    Vp9SymbolRecorder(int capacity) {
        super(0);
        symbols = new int[Math.max(256, capacity)];
    }

    /**
     * Discards every recorded symbol.
     */
    void clear() {
        size = 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The boolean is recorded, not coded.
     */
    @Override
    void write(int bit, int probability) {
        if (size == symbols.length) {
            symbols = Arrays.copyOf(symbols, size * 2);
        }
        symbols[size++] = (probability << 1) | (bit != 0 ? 1 : 0);
    }

    /**
     * Records a mark.
     *
     * @param mark {@link #TOKENS} or {@link #ROW}
     */
    void mark(int mark) {
        if (size == symbols.length) {
            symbols = Arrays.copyOf(symbols, size * 2);
        }
        symbols[size++] = mark;
    }

    /**
     * Returns the array holding the recorded symbols, valid until the next {@link #write} grows
     * it.
     *
     * @return the symbols; the first {@link #recorded()} are the recorded ones
     */
    int[] symbols() {
        return symbols;
    }

    /**
     * Returns the number of recorded symbols.
     *
     * @return the count
     */
    int recorded() {
        return size;
    }
}
