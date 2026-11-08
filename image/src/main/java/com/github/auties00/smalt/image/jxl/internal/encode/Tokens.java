package com.github.auties00.smalt.image.jxl.internal.encode;

import java.util.Arrays;

/**
 * A growable list of entropy-coding tokens, each a context and an unsigned value.
 *
 * <p>Instances are not thread-safe.
 */
public final class Tokens {
    private int[] contexts;

    private int[] values;

    /**
     * Whether each token is an LZ77 copy length, or {@code null} while no token is.
     */
    private boolean[] lengths;

    private int size;

    /**
     * Constructs an empty list.
     */
    public Tokens() {
        this(64);
    }

    /**
     * Constructs an empty list with room for the given number of tokens.
     *
     * @param capacity the initial capacity
     */
    public Tokens(int capacity) {
        contexts = new int[Math.max(8, capacity)];
        values = new int[Math.max(8, capacity)];
    }

    /**
     * Appends a token.
     *
     * @param context the context, non-negative
     * @param value the value, as an unsigned 32-bit pattern
     */
    public void add(int context, int value) {
        append(context, value, false);
    }

    /**
     * Appends the length of an LZ77 copy, coded in the context of the first value it copies.
     *
     * @param context the context of the first copied value
     * @param length the number of copied values minus the minimum copy length
     */
    void addLength(int context, int length) {
        if (lengths == null) {
            lengths = new boolean[contexts.length];
        }
        append(context, length, true);
    }

    private void append(int context, int value, boolean length) {
        if (size == contexts.length) {
            var grown = Math.max(16, size * 2);
            contexts = Arrays.copyOf(contexts, grown);
            values = Arrays.copyOf(values, grown);
            if (lengths != null) {
                lengths = Arrays.copyOf(lengths, grown);
            }
        }
        contexts[size] = context;
        values[size] = value;
        if (lengths != null) {
            lengths[size] = length;
        }
        size++;
    }

    /**
     * Returns whether a token is the length of an LZ77 copy.
     *
     * @param index the token index
     * @return whether the token is a copy length
     */
    boolean isLength(int index) {
        return lengths != null && lengths[index];
    }

    /**
     * Appends a signed value packed as an unsigned one.
     *
     * @param context the context of the token
     * @param value the signed value
     */
    public void addSigned(int context, int value) {
        add(context, packSigned(value));
    }

    /**
     * Packs a signed value as an unsigned one: 0, -1, 1, -2, 2 and so on map to 0, 1, 2, 3, 4.
     *
     * @param value the signed value
     * @return the packed value
     */
    public static int packSigned(int value) {
        return (value << 1) ^ (value >> 31);
    }

    /**
     * {@return the number of tokens}
     */
    public int size() {
        return size;
    }

    /**
     * Returns the context of a token.
     *
     * @param index the token index
     * @return the context
     */
    public int context(int index) {
        return contexts[index];
    }

    /**
     * Returns the value of a token.
     *
     * @param index the token index
     * @return the value
     */
    public int value(int index) {
        return values[index];
    }

    /**
     * Removes every token.
     */
    public void clear() {
        size = 0;
    }
}
