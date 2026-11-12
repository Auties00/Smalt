package com.github.auties00.smalt.audio.dts.internal;

import java.util.Arrays;

/**
 * The fixed-point 64-band synthesis filter bank for one channel, with its history: the integer reconstruction that
 * the residuals of lossless channels at twice the rate of their core are coded against.
 *
 * <p>Each block of 64 subband samples goes through a 64-point DCT-IV in Q23 integer arithmetic, every product rounded
 * half up and the outermost twiddles scaled by 2^-2.5, then through the 1024-tap window, whose even and odd halves are
 * each rounded at 2^-21 before they are added. A block whose subband samples sum in magnitude to more than 2^22 is
 * transformed at a quarter of its value and scaled back by four. The output is clipped to 24 bits. The same window
 * serves either value of the core's {@code FILTS} flag.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results. Instances are not
 * thread-safe.
 *
 * @implNote This implementation matches ffmpeg's lossless DTS output, the reference this reconstruction was measured
 * against (see {@link DtsFixedTables}).
 */
final class FixedQmf64 {
    /**
     * The transformed blocks of the history, by block age (modulo 16) and coefficient.
     */
    private final int[][] blocks = new int[16][64];

    /**
     * The rounded odd half of the window output of the previous block.
     */
    private final int[] overlap = new int[64];

    /**
     * The subband samples of the current block.
     */
    private final int[] in = new int[64];

    /**
     * The pair sums of the current block.
     */
    private final int[] sums = new int[32];

    /**
     * The shifted pair sums of the current block.
     */
    private final int[] shifted = new int[32];

    /**
     * The output of the 32-point DCT-IV.
     */
    private final int[] even = new int[32];

    /**
     * The output of the 32-point DCT-III.
     */
    private final int[] odd = new int[32];

    /**
     * The first input of a 16-point transform.
     */
    private final int[] first = new int[16];

    /**
     * The second input of a 16-point transform.
     */
    private final int[] second = new int[16];

    /**
     * The output of the first 16-point transform.
     */
    private final int[] firstOut = new int[16];

    /**
     * The output of the second 16-point transform.
     */
    private final int[] secondOut = new int[16];

    /**
     * The 16-point transforms.
     */
    private final FixedDct dct = new FixedDct();

    /**
     * The index of the newest block in {@link #blocks}.
     */
    private int newest;

    /**
     * Synthesizes 64 PCM samples per subband sample.
     *
     * @param bands the subband planes, each with {@link SubbandBuffer#HISTORY} history samples first, holding 24-bit
     *              integers; the subbands from {@code activeBands} on hold nothing
     * @param activeBands the number of subbands that hold samples, at most 64
     * @param from the index of the first subband sample, history excluded
     * @param count the number of subband samples
     * @param out receives {@code 64 * count} 24-bit samples
     * @param outOffset the index of the first output sample
     */
    void synthesize(float[][] bands, int activeBands, int from, int count, int[] out, int outOffset) {
        var window = DtsFixedTables.WINDOW_64;
        for (var s = 0; s < count; s++) {
            var index = SubbandBuffer.HISTORY + from + s;
            for (var i = 0; i < activeBands; i++) {
                in[i] = (int) bands[i][index];
            }
            Arrays.fill(in, activeBands, 64, 0);
            newest = (newest + 15) & 15;
            transform(in, blocks[newest]);
            var position = outOffset + 64 * s;
            for (var i = 0; i < 64; i++) {
                var k = 63 - i;
                long low = 0;
                long high = 0;
                for (var t = 0; t < 8; t++) {
                    var block = blocks[(newest + 2 * t) & 15];
                    long a = block[i];
                    long b = block[k];
                    low += window[i + 128 * t] * (a - b);
                    high -= window[64 + i + 128 * t] * (a + b);
                }
                var sample = overlap[i] + (int) ((low + (1 << 20)) >> 21);
                overlap[i] = (int) ((high + (1 << 20)) >> 21);
                out[position + i] = Math.clamp(sample, -(1 << 23), (1 << 23) - 1);
            }
        }
    }

    /**
     * Clears the history.
     */
    void reset() {
        for (var block : blocks) {
            Arrays.fill(block, 0);
        }
        Arrays.fill(overlap, 0);
        newest = 0;
    }

    /**
     * Computes the 64-point DCT-IV of one block of subband samples.
     *
     * @param x the 64 subband samples, overwritten when the block is scaled down
     * @param u receives the 64 coefficients
     */
    private void transform(int[] x, int[] u) {
        long magnitude = 0;
        for (var i = 0; i < 64; i++) {
            magnitude += Math.abs(x[i]);
        }
        var scaled = magnitude > 1 << 22;
        if (scaled) {
            for (var i = 0; i < 64; i++) {
                x[i] = (x[i] + 2) >> 2;
            }
        }
        sums[0] = x[0] + x[1];
        shifted[0] = x[0];
        for (var i = 1; i < 32; i++) {
            sums[i] = x[2 * i] + x[2 * i + 1];
            shifted[i] = x[2 * i] + x[2 * i - 1];
        }
        dct4of32(sums, even);
        dct3of32(shifted, odd);
        var shift = scaled ? 2 : 0;
        for (var k = 0; k < 32; k++) {
            u[k] = FixedDct.round23((long) (even[k] + odd[k]) * DtsFixedTables.TWIDDLE_64_SUM[k]) << shift;
            u[63 - k] = FixedDct.round23((long) (even[k] - odd[k]) * DtsFixedTables.TWIDDLE_64_DIFFERENCE[k]) << shift;
        }
    }

    /**
     * Computes the 32-point DCT-IV from a 16-point DCT-IV of the pair sums and a 16-point DCT-III of the shifted pair
     * sums.
     */
    private void dct4of32(int[] p, int[] y) {
        for (var i = 0; i < 16; i++) {
            first[i] = p[2 * i] + p[2 * i + 1];
            second[i] = i == 0 ? p[0] : p[2 * i] + p[2 * i - 1];
        }
        dct.dct4of16(first, firstOut);
        dct.dct3of16(second, secondOut);
        for (var k = 0; k < 16; k++) {
            y[k] = FixedDct.round23((long) (firstOut[k] + secondOut[k]) * DtsFixedTables.TWIDDLE_32_UNIT_SUM[k]);
            y[31 - k] = FixedDct.round23((long) (firstOut[k] - secondOut[k])
                    * DtsFixedTables.TWIDDLE_32_UNIT_DIFFERENCE[k]);
        }
    }

    /**
     * Computes the 32-point DCT-III from a 16-point DCT-III of the even inputs and one of the summed odd inputs, the
     * latter twiddled.
     */
    private void dct3of32(int[] q, int[] y) {
        for (var i = 0; i < 16; i++) {
            first[i] = q[2 * i];
            second[i] = i == 0 ? q[1] : q[2 * i + 1] + q[2 * i - 1];
        }
        dct.dct3of16(first, firstOut);
        dct.dct3of16(second, secondOut);
        for (var k = 0; k < 16; k++) {
            var o = FixedDct.round23((long) secondOut[k] * DtsFixedTables.TWIDDLE_32_ODD[k]);
            y[k] = firstOut[k] + o;
            y[31 - k] = firstOut[k] - o;
        }
    }
}
