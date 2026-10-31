package com.github.auties00.smalt.audio.aac.internal.ep;

import java.util.Arrays;

/**
 * Encodes and decodes the systematic rate-compatible punctured convolutional (SRCPC) code of the MPEG-4 audio error
 * protection tool (ISO/IEC 14496-3 subclause 1.8.4.6).
 *
 * <p>The mother code is the rate 1/4 systematic recursive convolutional code of Figure 1.10 with four memory
 * cells. Each step {@code t} takes one input bit and yields the input itself followed by three parity bits, of which
 * the puncturing table of the step's rate keeps those whose bit {@code 7 - t mod 8} is set (Table 1.61). A sequence
 * that is terminated is followed by four tail steps whose inputs return the register to zero. Bits are held one per
 * byte; a received step stores its four outputs, -1 for a punctured one.
 *
 * <p>Decoding runs the Viterbi algorithm with hard decisions over a whole sequence, forcing the zero state after
 * every terminated run. Instances keep their trellis buffers between calls and are not thread-safe.
 */
final class Srcpc {
    /**
     * The highest code rate index: {@code 8/(8 + rate)} for rates 0 to 24.
     */
    static final int MAX_RATE = 24;

    /**
     * The puncturing patterns of the three parity outputs at each rate index, {@code Pr(1)} to {@code Pr(3)}.
     */
    private static final int[][] PATTERNS = {
            {0x00, 0x00, 0x00}, {0x80, 0x00, 0x00}, {0x88, 0x00, 0x00}, {0xA8, 0x00, 0x00}, {0xAA, 0x00, 0x00},
            {0xEA, 0x00, 0x00}, {0xEE, 0x00, 0x00}, {0xFE, 0x00, 0x00}, {0xFF, 0x00, 0x00}, {0xFF, 0x80, 0x00},
            {0xFF, 0x88, 0x00}, {0xFF, 0xA8, 0x00}, {0xFF, 0xAA, 0x00}, {0xFF, 0xEA, 0x00}, {0xFF, 0xEE, 0x00},
            {0xFF, 0xFE, 0x00}, {0xFF, 0xFF, 0x00}, {0xFF, 0xFF, 0x80}, {0xFF, 0xFF, 0x88}, {0xFF, 0xFF, 0xA8},
            {0xFF, 0xFF, 0xAA}, {0xFF, 0xFF, 0xEA}, {0xFF, 0xFF, 0xEE}, {0xFF, 0xFF, 0xFE}, {0xFF, 0xFF, 0xFF}
    };

    /**
     * The four outputs of the branch from each state on each register input, packed as bit {@code 3 - i} for output
     * {@code i}, indexed by {@code state * 2 + input}.
     */
    private static final int[] OUTPUTS = new int[32];

    static {
        for (var state = 0; state < 16; state++) {
            for (var input = 0; input < 2; input++) {
                OUTPUTS[state * 2 + input] = branch(state, input);
            }
        }
    }

    /**
     * The path metric of each state.
     */
    private final int[] metrics = new int[16];

    /**
     * The path metric of each state after the step being processed.
     */
    private final int[] next = new int[16];

    /**
     * The survivor decision of each state at each step: bit {@code s} set when state {@code s} was reached from the
     * predecessor with the high register cell set.
     */
    private short[] decisions = new short[0];

    /**
     * Computes the outputs of one branch of the trellis.
     *
     * @param state the register contents, cell {@code m1} in bit 0 to cell {@code m4} in bit 3
     * @param input the register input {@code u xor d}
     * @return the four outputs packed as bit {@code 3 - i} for output {@code i}
     */
    private static int branch(int state, int input) {
        var feedback = ((state >>> 3) ^ (state >>> 1) ^ state) & 1;
        var u = input ^ feedback;
        var common = ((state >>> 3) ^ (state >>> 2) ^ input) & 1;
        var v3 = common ^ ((state >>> 1) & 1);
        var v4 = common ^ (state & 1);
        return (u << 3) | (common << 2) | (v3 << 1) | v4;
    }

    /**
     * Returns whether a parity output is sent at a step.
     *
     * @param rate the rate index in effect, from 0 to {@link #MAX_RATE}
     * @param output the parity output, 0 to 2 for {@code Pr(1)} to {@code Pr(3)}
     * @param step the step index
     * @return {@code true} when the output is not punctured
     */
    static boolean sent(int rate, int output, int step) {
        return (PATTERNS[rate][output] & (0x80 >>> (step & 7))) != 0;
    }

    /**
     * Returns the number of bits a step produces after puncturing.
     *
     * @param rate the rate index in effect
     * @param step the step index
     * @return 1 to 4
     */
    static int width(int rate, int step) {
        var mask = 0x80 >>> (step & 7);
        var pattern = PATTERNS[rate];
        return 1 + ((pattern[0] & mask) != 0 ? 1 : 0) + ((pattern[1] & mask) != 0 ? 1 : 0)
                + ((pattern[2] & mask) != 0 ? 1 : 0);
    }

    /**
     * Encodes one step.
     *
     * @param state the register contents
     * @param bit the information bit, or -1 for a tail step
     * @return the next state in bits 4 to 7 and the four outputs in bits 0 to 3, output 0 in bit 3
     */
    static int step(int state, int bit) {
        var feedback = ((state >>> 3) ^ (state >>> 1) ^ state) & 1;
        var input = bit < 0 ? 0 : bit ^ feedback;
        return (((state << 1) | input) & 0xF) << 4 | OUTPUTS[state * 2 + input];
    }

    /**
     * Decodes a received sequence.
     *
     * @param received the received outputs, four per step, -1 for a punctured output
     * @param steps the number of steps
     * @param tails the tail flag of each step: nonzero for a tail step, whose register input is zero
     * @param terminated whether the sequence ends in the zero state
     * @param decoded receives the decoded information bit of each step (tail steps included)
     */
    void decode(byte[] received, int steps, byte[] tails, boolean terminated, byte[] decoded) {
        if (decisions.length < steps) {
            decisions = new short[Math.max(steps, decisions.length * 2)];
        }
        Arrays.fill(metrics, Integer.MAX_VALUE / 2);
        metrics[0] = 0;
        for (var t = 0; t < steps; t++) {
            var base = t * 4;
            var tail = tails[t] != 0;
            var decision = 0;
            for (var s = 0; s < 16; s++) {
                var input = s & 1;
                if (tail && input != 0) {
                    next[s] = Integer.MAX_VALUE / 2;
                    continue;
                }
                var low = s >>> 1;
                var high = low | 8;
                var lowMetric = metrics[low] + distance(OUTPUTS[low * 2 + input], received, base);
                var highMetric = metrics[high] + distance(OUTPUTS[high * 2 + input], received, base);
                if (highMetric < lowMetric) {
                    next[s] = highMetric;
                    decision |= 1 << s;
                } else {
                    next[s] = lowMetric;
                }
            }
            decisions[t] = (short) decision;
            System.arraycopy(next, 0, metrics, 0, 16);
        }
        var state = 0;
        if (!terminated) {
            for (var s = 1; s < 16; s++) {
                if (metrics[s] < metrics[state]) {
                    state = s;
                }
            }
        }
        for (var t = steps - 1; t >= 0; t--) {
            var input = state & 1;
            var previous = (state >>> 1) | ((decisions[t] >>> state) & 1) << 3;
            decoded[t] = (byte) (OUTPUTS[previous * 2 + input] >>> 3);
            state = previous;
        }
    }

    /**
     * Returns the Hamming distance between a branch's outputs and the received outputs of a step.
     *
     * @param outputs the branch outputs, output 0 in bit 3
     * @param received the received outputs
     * @param base the index of the step's first output
     * @return the number of sent outputs that differ
     */
    private static int distance(int outputs, byte[] received, int base) {
        var distance = 0;
        for (var i = 0; i < 4; i++) {
            var bit = received[base + i];
            if (bit >= 0 && bit != ((outputs >>> (3 - i)) & 1)) {
                distance++;
            }
        }
        return distance;
    }
}
