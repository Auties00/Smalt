/* Ports Monkey's Audio SDK 13.45 (Source/MACLib/NNFilter.cpp, NNFilterGeneric.cpp, NNFilterCommon.h, Interim.cpp).
   Copyright (c) 2000 - 2026 Matthew T. Ashland; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.ape;

/**
 * The adaptive neural-network filter of Monkey's Audio for samples of up to 24 bits ({@code CNNFilter<int, short>}):
 * 16-bit coefficients and history, 32-bit wrapping arithmetic.
 *
 * <p>Instances are not thread-safe.
 */
final class ApeNnFilter {
    private static final int WINDOW = 512;

    private final int order;

    private final int shift;

    private final int round;

    private final boolean newDelta;

    private final boolean interim;

    private final short[] coefficients;

    private final short[] input;

    private final short[] delta;

    private int position;

    private int runningAverage;

    /**
     * Constructs a filter.
     *
     * @param order the number of taps, 16 or a multiple of 32
     * @param shift the output shift
     * @param version the file version times 1000, which selects the delta update rule
     * @param interim whether the filter rounds as the encoders of versions 5.01 to 8.51 did for 24-bit files
     */
    ApeNnFilter(int order, int shift, int version, boolean interim) {
        this.order = order;
        this.shift = shift;
        this.round = 1 << (shift - 1);
        this.newDelta = version >= 3980;
        this.interim = interim;
        this.coefficients = new short[order];
        this.input = new short[WINDOW + order];
        this.delta = new short[WINDOW + order];
        flush();
    }

    void flush() {
        java.util.Arrays.fill(coefficients, (short) 0);
        java.util.Arrays.fill(input, 0, order + 1, (short) 0);
        java.util.Arrays.fill(delta, 0, order + 1, (short) 0);
        position = order;
        runningAverage = 0;
    }

    static short saturate(long value) {
        var narrow = (short) value;
        if (narrow != value) {
            narrow = (short) ((value >> 63) ^ 0x7FFF);
        }
        return narrow;
    }

    int decompress(int value) {
        var base = position - order;
        var dot = 0;
        for (var i = 0; i < order; i++) {
            dot += input[base + i] * coefficients[i];
        }
        int output;
        if (interim) {
            output = value + (int) (((long) dot + round) >> shift);
        } else {
            output = value + ((dot + round) >> shift);
        }
        if (value < 0) {
            for (var i = 0; i < order; i++) {
                coefficients[i] += delta[base + i];
            }
        } else if (value > 0) {
            for (var i = 0; i < order; i++) {
                coefficients[i] -= delta[base + i];
            }
        }
        if (newDelta || interim) {
            var magnitude = Math.abs(output);
            if (magnitude > runningAverage * 3) {
                delta[position] = (short) (((output >> 25) & 64) - 32);
            } else if (magnitude > (runningAverage * 4) / 3) {
                delta[position] = (short) (((output >> 26) & 32) - 16);
            } else if (magnitude > 0) {
                delta[position] = (short) (((output >> 27) & 16) - 8);
            } else {
                delta[position] = 0;
            }
            runningAverage += (magnitude - runningAverage) / 16;
            delta[position - 1] >>= 1;
            delta[position - 2] >>= 1;
            delta[position - 8] >>= 1;
        } else {
            delta[position] = (short) (output == 0 ? 0 : ((output >> 28) & 8) - 4);
            delta[position - 4] >>= 1;
            delta[position - 8] >>= 1;
        }
        input[position] = saturate(output);
        position++;
        if (position == WINDOW + order) {
            System.arraycopy(input, position - order, input, 0, order);
            System.arraycopy(delta, position - order, delta, 0, order);
            position = order;
        }
        return output;
    }
}
