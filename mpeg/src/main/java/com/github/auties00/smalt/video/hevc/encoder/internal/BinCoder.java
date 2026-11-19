package com.github.auties00.smalt.video.hevc.encoder.internal;

import com.github.auties00.smalt.video.hevc.internal.CabacTables;

/**
 * Codes the bins of the HEVC syntax with the context variables of ITU-T H.265 9.3: into a bit stream
 * ({@link CabacWriter}) or into an estimate of the bits that would be spent ({@link CabacCounter}).
 *
 * <p>Both kinds update the context variables in the same way, so a counter that starts from the
 * states of a writer predicts the states the writer reaches. Instances are not thread-safe.
 */
abstract sealed class BinCoder permits CabacWriter, CabacCounter {
    /**
     * The state after coding a bin, indexed {@code (state << 1) | bin}, where a state is
     * {@code (pStateIdx << 1) | valMps}.
     */
    static final byte[] NEXT_STATE = new byte[256];

    static {
        for (var state = 0; state < 64; state++) {
            for (var mps = 0; mps < 2; mps++) {
                var combined = (state << 1) | mps;
                var nextMps = Math.min(state + 1, 62);
                if (state == 63) {
                    nextMps = 63;
                }
                var lpsMps = state == 0 ? 1 - mps : mps;
                NEXT_STATE[(combined << 1) | mps] = (byte) ((nextMps << 1) | mps);
                NEXT_STATE[(combined << 1) | (1 - mps)] = (byte) ((CabacTables.TRANS_LPS[state] << 1) | lpsMps);
            }
        }
    }

    /**
     * The context variables as {@code (pStateIdx << 1) | valMps}, {@link CabacTables#COUNT} entries.
     */
    final byte[] states = new byte[CabacTables.COUNT];

    /**
     * Codes one context-coded bin and updates its context variable (9.3.4.3.2).
     *
     * @param ctx the context index, an offset of {@link CabacTables} plus the increment
     * @param bin the bin, 0 or 1
     */
    abstract void bin(int ctx, int bin);

    /**
     * Codes one bypass bin (9.3.4.3.4).
     *
     * @param bin the bin, 0 or 1
     */
    abstract void bypass(int bin);

    /**
     * Codes the low bits of a value as bypass bins, most significant first.
     *
     * @param value the bins
     * @param count the number of bins, from 0 to 32
     */
    abstract void bypassBins(int value, int count);

    /**
     * Codes a bin with the terminating decoding process (9.3.4.3.5).
     *
     * @param bin the bin, 0 or 1
     */
    abstract void terminate(int bin);

    /**
     * Initializes every context variable for a slice (9.3.2.2).
     *
     * @param initType the initialization type, 0 to 2
     * @param sliceQp {@code SliceQpY}
     */
    final void initContexts(int initType, int sliceQp) {
        CabacTables.init(initType, sliceQp, states);
    }

    /**
     * Copies the context variables of another coder.
     *
     * @param other the coder whose states to copy
     */
    final void copyStates(BinCoder other) {
        System.arraycopy(other.states, 0, states, 0, CabacTables.COUNT);
    }

    /**
     * Copies context variables saved in an array.
     *
     * @param saved the states, {@link CabacTables#COUNT} entries from {@code offset}
     * @param offset the index of the first state
     */
    final void loadStates(byte[] saved, int offset) {
        System.arraycopy(saved, offset, states, 0, CabacTables.COUNT);
    }

    /**
     * Saves the context variables into an array.
     *
     * @param saved receives the states, {@link CabacTables#COUNT} entries from {@code offset}
     * @param offset the index of the first state
     */
    final void storeStates(byte[] saved, int offset) {
        System.arraycopy(states, 0, saved, offset, CabacTables.COUNT);
    }
}
