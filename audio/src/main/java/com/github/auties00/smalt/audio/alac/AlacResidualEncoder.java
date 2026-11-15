/* Ports Apple Lossless Audio Codec (codec/ag_enc.c). Copyright (c) 2011 Apple Inc.; Apache License 2.0. Modified
   for Smalt. */
package com.github.auties00.smalt.audio.alac;

/**
 * Codes the prediction residual of one ALAC channel with ALAC's adaptive Golomb code, the inverse of
 * {@link AlacResidual}.
 *
 * <p>Each value is mapped to a non-negative integer (twice its magnitude, less one when negative)
 * and coded with a parameter that follows a running mean of the coded values; a quotient of nine or
 * more, or a code longer than 25 bits, escapes to nine ones and the value in raw bits. When the mean
 * falls low, the length of the run of zeros that follows is coded with its own parameter.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 *
 * @implNote This implementation follows the reference's {@code dyn_comp} with the parameters of
 * {@code set_standard_ag_params}, the only ones its encoder uses, including its unsigned 32-bit
 * arithmetic on the mean.
 */
final class AlacResidualEncoder {
    /**
     * The fixed-point shift of the mean.
     */
    private static final int QB_SHIFT = 9;

    /**
     * One in the mean's fixed point.
     */
    private static final int QB = 1 << QB_SHIFT;

    /**
     * The shift that tests for a low mean.
     */
    private static final int M_MUL_SHIFT = 2;

    /**
     * The shift of the zero-run parameter correction.
     */
    private static final int M_DEN_SHIFT = QB_SHIFT - M_MUL_SHIFT - 1;

    /**
     * The rounding offset of the zero-run parameter correction.
     */
    private static final int M_OFF = 1 << (M_DEN_SHIFT - 2);

    /**
     * The offset of the zero-run parameter.
     */
    private static final int BIT_OFF = 24;

    /**
     * The quotient, and the prefix of ones, that escapes to a raw value.
     */
    private static final int MAX_PREFIX = 9;

    /**
     * The raw bits of an escaped zero-run length.
     */
    private static final int RUN_ESCAPE_BITS = 16;

    /**
     * The longest value code before it escapes.
     */
    private static final int MAX_CODE_BITS = 25;

    /**
     * The largest value that updates the mean normally; a larger one resets it to this value.
     */
    private static final int MAX_MEAN_VALUE = 0xFFFF;

    /**
     * The longest run of zeros one code covers.
     */
    private static final int MAX_RUN = 65535;

    /**
     * The initial mean, {@code MB0}.
     */
    private static final int INITIAL_MEAN = 10;

    /**
     * The adaptation rate of the mean, {@code PB0}.
     */
    private static final int RATE = 40;

    /**
     * The largest Golomb parameter, {@code KB0}.
     */
    private static final int MAX_PARAMETER = 14;

    /**
     * The mask of the zero-run parameter, {@code 2^KB0 - 1}.
     */
    private static final int RUN_MASK = (1 << MAX_PARAMETER) - 1;

    /**
     * Prevents instantiation.
     */
    private AlacResidualEncoder() {
        throw new AssertionError();
    }

    /**
     * Codes a residual, or only counts its bits.
     *
     * @param residual the residual, each value sign-extended from {@code bitSize} bits
     * @param count the number of values, non-negative
     * @param bitSize the size of the channel's prediction in bits, from 1 to 32
     * @param out receives the code, or {@code null} to count only
     * @return the length of the code in bits
     */
    static long encode(int[] residual, int count, int bitSize, AlacBitWriter out) {
        var mean = INITIAL_MEAN;
        var zeroMode = 0;
        var bits = 0L;
        var c = 0;
        while (c < count) {
            var k = 31 - Integer.numberOfLeadingZeros((mean >>> QB_SHIFT) + 3);
            if (k > MAX_PARAMETER) {
                k = MAX_PARAMETER;
            }
            var m = (1 << k) - 1;
            var del = residual[c];
            var n = ((del ^ (del >> 31)) - (del >> 31) << 1) - ((del >> 31) & 1) - zeroMode;
            var div = Integer.divideUnsigned(n, m);
            var escaped = true;
            if (Integer.compareUnsigned(div, MAX_PREFIX) < 0) {
                var mod = n - m * div;
                var de = mod == 0 ? 1 : 0;
                var numBits = div + k + 1 - de;
                if (numBits <= MAX_CODE_BITS) {
                    escaped = false;
                    if (out != null) {
                        out.write((((1 << div) - 1) << (numBits - div)) + mod + 1 - de, numBits);
                    }
                    bits += numBits;
                }
            }
            if (escaped) {
                if (out != null) {
                    out.write((1 << MAX_PREFIX) - 1, MAX_PREFIX);
                    out.write(n, bitSize);
                }
                bits += MAX_PREFIX + bitSize;
            }
            c++;
            mean = RATE * (n + zeroMode) + mean - ((RATE * mean) >>> QB_SHIFT);
            if (Integer.compareUnsigned(n, MAX_MEAN_VALUE) > 0) {
                mean = MAX_MEAN_VALUE;
            }
            zeroMode = 0;
            if (Integer.compareUnsigned(mean << M_MUL_SHIFT, QB) < 0 && c < count) {
                zeroMode = 1;
                var zeros = 0;
                while (c < count && residual[c] == 0) {
                    c++;
                    zeros++;
                    if (zeros >= MAX_RUN) {
                        zeroMode = 0;
                        break;
                    }
                }
                var runK = Integer.numberOfLeadingZeros(mean) - BIT_OFF + ((mean + M_OFF) >>> M_DEN_SHIFT);
                var runM = ((1 << runK) - 1) & RUN_MASK;
                bits += runCode(runM, runK, zeros, out);
                mean = 0;
            }
        }
        return bits;
    }

    /**
     * Codes the length of a run of zeros ({@code dyn_code}).
     *
     * @param m the Golomb divisor, positive
     * @param k the Golomb parameter
     * @param n the run length, from 0 to 65535
     * @param out receives the code, or {@code null} to count only
     * @return the length of the code in bits
     */
    private static int runCode(int m, int k, int n, AlacBitWriter out) {
        var div = n / m;
        int numBits;
        int value;
        if (div >= MAX_PREFIX) {
            numBits = MAX_PREFIX + RUN_ESCAPE_BITS;
            value = (((1 << MAX_PREFIX) - 1) << RUN_ESCAPE_BITS) + n;
        } else {
            var mod = n % m;
            var de = mod == 0 ? 1 : 0;
            numBits = div + k + 1 - de;
            value = (((1 << div) - 1) << (numBits - div)) + mod + 1 - de;
            if (numBits > MAX_PREFIX + RUN_ESCAPE_BITS) {
                numBits = MAX_PREFIX + RUN_ESCAPE_BITS;
                value = (((1 << MAX_PREFIX) - 1) << RUN_ESCAPE_BITS) + n;
            }
        }
        if (out != null) {
            out.write(value, numBits);
        }
        return numBits;
    }
}
