/* Ports libvpx 1.17.0 (vp9/encoder/vp9_tokenize.h). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

/**
 * Holds the coefficient tokens of one superblock row of a tile, in coding order, until the
 * bitstream writer codes them.
 *
 * <p>Each token records the index of its three node probabilities in the frame context, so the
 * writer codes it with the probabilities the compressed header settles on, and a packed word
 * holding the token in the low 4 bits and the extra value above them. Every block's tokens end
 * with {@link Vp9EncTables#EOSB_TOKEN}.
 *
 * @implNote This implementation replaces libvpx's {@code TOKENEXTRA} lists
 * ({@code vp9/encoder/vp9_tokenize.h}), whose context pointer into the frame
 * context becomes an index.
 */
final class Vp9RtTokens {
    /**
     * The probability index of each token.
     */
    int[] probs;

    /**
     * The packed token and extra value of each token.
     */
    int[] words;

    /**
     * The number of tokens.
     */
    int size;

    /**
     * Creates a buffer with an initial capacity.
     *
     * @param capacity the initial number of tokens
     */
    Vp9RtTokens(int capacity) {
        probs = new int[Math.max(64, capacity)];
        words = new int[probs.length];
    }

    /**
     * Discards every token.
     */
    void clear() {
        size = 0;
    }

    /**
     * Appends a token.
     *
     * @param prob the index of the token's probabilities
     * @param token the token, from {@code ZERO_TOKEN} to {@code EOSB_TOKEN}
     * @param extra the extra value
     */
    void add(int prob, int token, int extra) {
        if (size == probs.length) {
            probs = Arrays.copyOf(probs, size * 2);
            words = Arrays.copyOf(words, size * 2);
        }
        probs[size] = prob;
        words[size] = token | (extra << 4);
        size++;
    }

    /**
     * Appends the marker ending a block's tokens.
     */
    void endBlock() {
        add(0, Vp9EncTables.EOSB_TOKEN, 0);
    }
}
