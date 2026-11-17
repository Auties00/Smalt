package com.github.auties00.smalt.audio.dts.internal;

/**
 * The 16-point integer transforms the cosine modulation networks of {@link FixedQmf32} and {@link FixedQmf64} are built
 * from, every product in Q23 and rounded half up.
 *
 * <p>Instances are not thread-safe.
 */
final class FixedDct {
    /**
     * Scratch space of the 8-point transforms, three rows of eight.
     */
    private final int[] scratch = new int[48];

    /**
     * Computes the 16-point DCT-IV from a direct 8-point DCT-IV of the pair sums and a direct 8-point DCT-III of the
     * shifted pair sums.
     *
     * @param p the 16 inputs
     * @param y receives the 16 outputs
     */
    void dct4of16(int[] p, int[] y) {
        var a = scratch;
        for (var i = 0; i < 8; i++) {
            a[16 + i] = p[2 * i] + p[2 * i + 1];
            a[24 + i] = i == 0 ? p[0] : p[2 * i] + p[2 * i - 1];
        }
        direct(DtsFixedTables.DCT_IV_8, a, 16, 0);
        direct(DtsFixedTables.DCT_III_8, a, 24, 8);
        for (var k = 0; k < 8; k++) {
            y[k] = round23((long) (a[k] + a[8 + k]) * DtsFixedTables.TWIDDLE_16_SUM[k]);
            y[15 - k] = round23((long) (a[k] - a[8 + k]) * DtsFixedTables.TWIDDLE_16_DIFFERENCE[k]);
        }
    }

    /**
     * Computes the 16-point DCT-III from a direct 8-point DCT-III of the even inputs and one of the summed odd
     * inputs, the latter twiddled.
     *
     * @param q the 16 inputs
     * @param y receives the 16 outputs
     */
    void dct3of16(int[] q, int[] y) {
        var a = scratch;
        for (var i = 0; i < 8; i++) {
            a[16 + i] = q[2 * i];
            a[24 + i] = i == 0 ? q[1] : q[2 * i + 1] + q[2 * i - 1];
        }
        direct(DtsFixedTables.DCT_III_8, a, 16, 0);
        direct(DtsFixedTables.DCT_III_8, a, 24, 8);
        for (var k = 0; k < 8; k++) {
            var o = round23((long) a[8 + k] * DtsFixedTables.TWIDDLE_16_ODD[k]);
            y[k] = a[k] + o;
            y[15 - k] = a[k] - o;
        }
    }

    /**
     * Applies an 8-point transform matrix, each output rounded at 2^-23.
     *
     * @param matrix the Q23 matrix, row by output
     * @param a the scratch array
     * @param input the index of the eight inputs in {@code a}
     * @param output the index of the eight outputs in {@code a}
     */
    private static void direct(int[] matrix, int[] a, int input, int output) {
        for (var k = 0; k < 8; k++) {
            long sum = 0;
            for (var n = 0; n < 8; n++) {
                sum += (long) matrix[8 * k + n] * a[input + n];
            }
            a[output + k] = round23(sum);
        }
    }

    /**
     * {@return a Q23 product rounded half up to an integer}
     *
     * @param value the product
     */
    static int round23(long value) {
        return (int) ((value + (1 << 22)) >> 23);
    }
}
