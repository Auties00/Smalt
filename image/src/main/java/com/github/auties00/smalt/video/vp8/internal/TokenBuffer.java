/* Ports libwebp 1.6.0 (src/enc/token_enc.c, VP8TBuffer). Copyright (c) 2010, Google Inc.; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import java.util.Arrays;

/**
 * Stores coded tokens until the final probabilities are known.
 *
 * <p>Each token is a 16-bit value: bit 15 is the coded bit; bit 14 set means bits 0 to 7 hold a
 * constant probability, otherwise bits 0 to 13 index the adaptive probability table.
 *
 * @implNote This implementation keeps the tokens in one growable array where libwebp pages them;
 * the recorded sequence and its emission order are identical.
 */
final class TokenBuffer {
    /**
     * The tokens.
     */
    private short[] tokens;

    /**
     * The number of tokens.
     */
    private int size;

    /**
     * Creates a buffer with an initial capacity.
     *
     * @param capacity the initial number of tokens
     */
    TokenBuffer(int capacity) {
        tokens = new short[Math.max(capacity, 1024)];
    }

    /**
     * Appends a token.
     *
     * @param token the 16-bit token, in the format of the class description
     */
    void add(int token) {
        if (size == tokens.length) {
            if (tokens.length >= Integer.MAX_VALUE / 2 - 16) {
                throw new IllegalStateException("vp8 token buffer overflow");
            }
            tokens = Arrays.copyOf(tokens, tokens.length * 2);
        }
        tokens[size++] = (short) token;
    }

    /**
     * Makes room for more tokens and returns the token array, whose entries from {@link #size()}
     * on the caller may fill before publishing them with {@link #size(int)}.
     *
     * @param extra the number of tokens about to be appended
     * @return the token array
     */
    short[] reserve(int extra) {
        if (size + extra > tokens.length) {
            if (tokens.length >= Integer.MAX_VALUE / 2 - extra - 16) {
                throw new IllegalStateException("vp8 token buffer overflow");
            }
            tokens = Arrays.copyOf(tokens, Math.max(tokens.length * 2, size + extra));
        }
        return tokens;
    }

    /**
     * Returns the backing array; the first {@link #size()} entries are valid.
     *
     * @return the array
     */
    short[] array() {
        return tokens;
    }

    /**
     * {@return the number of tokens appended since the last clear}
     */
    int size() {
        return size;
    }

    /**
     * Sets the number of tokens after the caller appended tokens to the array of
     * {@link #reserve}.
     *
     * @param size the new number of tokens, within the reserved room
     */
    void size(int size) {
        this.size = size;
    }

    /**
     * Removes every token.
     */
    void clear() {
        size = 0;
    }

    /**
     * Codes every token with the final probabilities.
     *
     * @param bw     the partition writer
     * @param probas the adaptive token probabilities
     */
    void emit(BoolEncoder bw, int[] probas) {
        bw.putTokens(tokens, size, probas);
    }
}
