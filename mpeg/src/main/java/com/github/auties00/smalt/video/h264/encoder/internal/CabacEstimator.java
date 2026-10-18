package com.github.auties00.smalt.video.h264.encoder.internal;

/**
 * Estimates the number of bits CABAC would spend on a sequence of bins without producing them.
 *
 * <p>Each context-coded bin costs {@code -log2} of its probability under the current context
 * state, in 1/256 bit, and updates the state exactly like {@link CabacEncoder}; bypass bins cost
 * one bit. Starting from a copy of the encoder's states, the estimate of a macroblock matches its
 * actual size to within a fraction of a bit per macroblock.
 */
final class CabacEstimator extends CabacCoder {
    /**
     * The accumulated cost in 1/256 bit.
     */
    int cost;

    /**
     * Constructs an estimator.
     */
    CabacEstimator() {
    }

    @Override
    void decision(int ctx, int bin) {
        var s = states[ctx] & 0xFF;
        cost += CabacTables.COST[s ^ bin];
        states[ctx] = CabacTables.NEXT_STATE[(s << 1) | bin];
    }

    @Override
    void bypass(int bin) {
        cost += 256;
    }

    @Override
    void bypassBits(int value, int count) {
        cost += 256 * count;
    }

    @Override
    void terminate(int bin) {
        cost += bin != 0 ? 7 * 256 : 2;
    }
}
