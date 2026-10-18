package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.internal.CabacTables;

/**
 * Codes the bins of CABAC syntax elements against a set of adaptive contexts.
 *
 * <p>Two coders exist: {@link CabacEncoder} runs the arithmetic encoding engine of clause 9.3.4
 * and produces the slice data, and {@link CabacEstimator} only accumulates the entropy of each
 * bin under the current context state, for rate-distortion decisions. Both update the context
 * states identically, so an estimate can run ahead of the encoder on a copy of its states.
 */
abstract class CabacCoder {
    /**
     * The packed context states, {@code (pStateIdx << 1) | valMPS} by {@code ctxIdx}.
     */
    final byte[] states = new byte[CabacTables.CONTEXT_COUNT];

    /**
     * Constructs a coder.
     */
    CabacCoder() {
    }

    /**
     * Initializes every context for a slice (clause 9.3.1.1).
     *
     * @param table the initialization table: 0 for I slices, 1 to 3 for P slices with
     * {@code cabac_init_idc} 0 to 2
     * @param qp the slice quantization parameter {@code SliceQPY}
     */
    final void initContexts(int table, int qp) {
        CabacTables.initContexts(states, table, qp);
    }

    /**
     * Copies the context states of another coder.
     *
     * @param other the source
     */
    final void copyStates(CabacCoder other) {
        System.arraycopy(other.states, 0, states, 0, CabacTables.CONTEXT_COUNT);
    }

    /**
     * Codes one bin with an adaptive context.
     *
     * @param ctx the context index
     * @param bin the bin value, 0 or 1
     */
    abstract void decision(int ctx, int bin);

    /**
     * Codes one bin with the bypass (equiprobable) engine.
     *
     * @param bin the bin value, 0 or 1
     */
    abstract void bypass(int bin);

    /**
     * Codes the low bits of a value with the bypass engine, most significant first.
     *
     * @param value the bits to code, most significant first
     * @param count the number of bits
     */
    abstract void bypassBits(int value, int count);

    /**
     * Codes a bin with the terminating context 276 ({@code end_of_slice_flag} and the
     * {@code I_PCM} bin of {@code mb_type}).
     *
     * @param bin the bin value, 0 or 1
     */
    abstract void terminate(int bin);
}
