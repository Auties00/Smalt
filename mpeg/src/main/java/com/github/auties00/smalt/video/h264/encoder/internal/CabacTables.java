package com.github.auties00.smalt.video.h264.encoder.internal;

import static com.github.auties00.smalt.video.h264.internal.CabacTables.CONTEXT_COUNT;
import static com.github.auties00.smalt.video.h264.internal.CabacTables.NEXT_STATE_LPS;
import static com.github.auties00.smalt.video.h264.internal.CabacTables.NEXT_STATE_MPS;

/**
 * The encoder-side constant tables of CABAC (ITU-T H.264 9.3): the combined state transition and
 * the bin cost model.
 *
 * <p>Context states are packed as {@code (pStateIdx << 1) | valMPS} in one byte, the layout of
 * the decoder's {@link com.github.auties00.smalt.video.h264.internal.CabacTables}, whose context
 * initialization, context offsets, {@code rangeTabLPS} and transition tables the encoder shares.
 */
final class CabacTables {
    /**
     * The number of contexts used by 4:2:0 frame coding, {@code ctxIdx} 0 to 459.
     */
    static final int CONTEXTS = CONTEXT_COUNT;

    /**
     * The packed state after coding bin {@code b} in packed state {@code s}, at index
     * {@code (s << 1) | b}.
     */
    static final byte[] NEXT_STATE = new byte[256];

    /**
     * The cost in 1/256 bit of coding a bin, at index {@code s ^ b} for packed state {@code s}
     * and bin {@code b}: even indices are MPS costs, odd indices LPS costs.
     */
    static final int[] COST = new int[128];

    static {
        for (var packed = 0; packed < 128; packed++) {
            NEXT_STATE[packed << 1] = (packed & 1) == 0 ? NEXT_STATE_MPS[packed] : NEXT_STATE_LPS[packed];
            NEXT_STATE[(packed << 1) | 1] = (packed & 1) == 1 ? NEXT_STATE_MPS[packed] : NEXT_STATE_LPS[packed];
        }
        for (var state = 0; state < 64; state++) {
            var lps = 0.5 * Math.pow(0.01875 / 0.5, state / 63.0);
            COST[state << 1] = (int) Math.round(-Math.log(1 - lps) / Math.log(2) * 256);
            COST[(state << 1) | 1] = (int) Math.round(-Math.log(lps) / Math.log(2) * 256);
        }
    }

    private CabacTables() {
        throw new AssertionError();
    }
}
