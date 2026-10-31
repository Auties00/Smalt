/* Ports Monkey's Audio SDK 13.45 (Source/MACLib/NNFilter.cpp, NNFilterGeneric.cpp, NNFilterCommon.h).
   Copyright (c) 2000 - 2026 Matthew T. Ashland; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.ape;

/**
 * The adaptive neural-network filter of Monkey's Audio for 32-bit samples ({@code CNNFilter<int64, int>}): 32-bit
 * coefficients, products wrapped to 32 bits and summed in 64 bits, history saturated to 16 bits.
 *
 * <p>Instances are not thread-safe.
 */
final class ApeNnFilter64 {
    private static final int WINDOW = 512;

    private final int order;

    private final int shift;

    private final int round;

    private final boolean newDelta;

    private final int[] coefficients;

    private final int[] input;

    private final int[] delta;

    private int position;

    private long runningAverage;

    /**
     * Constructs a filter.
     *
     * @param order the number of taps, 16 or a multiple of 32
     * @param shift the output shift
     * @param version the file version times 1000, which selects the delta update rule
     */
    ApeNnFilter64(int order, int shift, int version) {
        this.order = order;
        this.shift = shift;
        this.round = 1 << (shift - 1);
        this.newDelta = version >= 3980;
        this.coefficients = new int[order];
        this.input = new int[WINDOW + order];
        this.delta = new int[WINDOW + order];
        flush();
    }

    void flush() {
        java.util.Arrays.fill(coefficients, 0);
        java.util.Arrays.fill(input, 0, order + 1, 0);
        java.util.Arrays.fill(delta, 0, order + 1, 0);
        position = order;
        runningAverage = 0;
    }

    long decompress(long value) {
        var base = position - order;
        var dot = 0L;
        for (var i = 0; i < order; i++) {
            dot += input[base + i] * coefficients[i];
        }
        var output = value + ((dot + round) >> shift);
        if (value < 0) {
            for (var i = 0; i < order; i++) {
                coefficients[i] += delta[base + i];
            }
        } else if (value > 0) {
            for (var i = 0; i < order; i++) {
                coefficients[i] -= delta[base + i];
            }
        }
        if (newDelta) {
            var magnitude = Math.abs(output);
            if (magnitude > runningAverage * 3) {
                delta[position] = (int) (((output >> 25) & 64) - 32);
            } else if (magnitude > (runningAverage * 4) / 3) {
                delta[position] = (int) (((output >> 26) & 32) - 16);
            } else if (magnitude > 0) {
                delta[position] = (int) (((output >> 27) & 16) - 8);
            } else {
                delta[position] = 0;
            }
            runningAverage += (magnitude - runningAverage) / 16;
            delta[position - 1] >>= 1;
            delta[position - 2] >>= 1;
            delta[position - 8] >>= 1;
        } else {
            delta[position] = (int) (output == 0 ? 0 : ((output >> 28) & 8) - 4);
            delta[position - 4] >>= 1;
            delta[position - 8] >>= 1;
        }
        input[position] = ApeNnFilter.saturate(output);
        position++;
        if (position == WINDOW + order) {
            System.arraycopy(input, position - order, input, 0, order);
            System.arraycopy(delta, position - order, delta, 0, order);
            position = order;
        }
        return output;
    }
}
