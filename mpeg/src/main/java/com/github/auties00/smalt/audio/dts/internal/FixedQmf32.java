package com.github.auties00.smalt.audio.dts.internal;

import java.util.Arrays;

/**
 * The fixed-point 32-band synthesis filter bank of the DTS core for one channel, with its history: the integer
 * reconstruction that the residuals of the lossless extension are coded against.
 *
 * <p>Each block of 32 subband samples goes through a quarter-scale 32-point DCT-IV in Q23 integer arithmetic, every
 * product rounded half up, then through the 512-tap window, whose even and odd halves are each rounded at 2^-21
 * before they are added. A block whose subband samples sum in magnitude to more than 2^22 is transformed at a quarter
 * of its value and scaled back by four. The output is clipped to 24 bits.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results. Instances are not
 * thread-safe.
 *
 * @implNote This implementation matches ffmpeg's lossless DTS output, the reference this reconstruction was measured
 * against (see {@link DtsFixedTables}).
 */
final class FixedQmf32 {
    /**
     * The transformed blocks of the history, by block age (modulo 16) and coefficient.
     */
    private final int[][] blocks = new int[16][32];

    /**
     * The rounded odd half of the window output of the previous block.
     */
    private final int[] overlap = new int[32];

    /**
     * The subband samples of the current block.
     */
    private final int[] in = new int[32];

    /**
     * The pair sums of the current block.
     */
    private final int[] sums = new int[16];

    /**
     * The shifted pair sums of the current block.
     */
    private final int[] shifted = new int[16];

    /**
     * The output of the 16-point DCT-IV.
     */
    private final int[] even = new int[16];

    /**
     * The output of the 16-point DCT-III.
     */
    private final int[] odd = new int[16];

    /**
     * The 16-point transforms.
     */
    private final FixedDct dct = new FixedDct();

    /**
     * The index of the newest block in {@link #blocks}.
     */
    private int newest;

    /**
     * Synthesizes 32 PCM samples per subband sample.
     *
     * @param bands the 32 subband planes, each with {@link SubbandBuffer#HISTORY} history samples first, holding
     *              24-bit integers
     * @param from the index of the first subband sample, history excluded
     * @param count the number of subband samples
     * @param perfect whether to use the perfect reconstruction window ({@code FILTS} = 1)
     * @param out receives {@code 32 * count} 24-bit samples
     * @param outOffset the index of the first output sample
     */
    void synthesize(float[][] bands, int from, int count, boolean perfect, int[] out, int outOffset) {
        var window = perfect ? DtsFixedTables.WINDOW_PERFECT : DtsFixedTables.WINDOW_LOSSY;
        for (var s = 0; s < count; s++) {
            var index = SubbandBuffer.HISTORY + from + s;
            for (var i = 0; i < 32; i++) {
                in[i] = (int) bands[i][index];
            }
            newest = (newest + 15) & 15;
            transform(in, blocks[newest]);
            var position = outOffset + 32 * s;
            for (var i = 0; i < 32; i++) {
                var k = 31 - i;
                long low = 0;
                long high = 0;
                for (var t = 0; t < 8; t++) {
                    var block = blocks[(newest + 2 * t) & 15];
                    long a = block[i];
                    long b = block[k];
                    low += window[i + 64 * t] * (a - b);
                    high -= window[32 + i + 64 * t] * (a + b);
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
     * Computes the quarter-scale 32-point DCT-IV of one block of subband samples.
     *
     * @param x the 32 subband samples, overwritten when the block is scaled down
     * @param u receives the 32 coefficients
     */
    private void transform(int[] x, int[] u) {
        long magnitude = 0;
        for (var i = 0; i < 32; i++) {
            magnitude += Math.abs(x[i]);
        }
        var scaled = magnitude > 1 << 22;
        if (scaled) {
            for (var i = 0; i < 32; i++) {
                x[i] = (x[i] + 2) >> 2;
            }
        }
        sums[0] = x[0] + x[1];
        shifted[0] = x[0];
        for (var i = 1; i < 16; i++) {
            sums[i] = x[2 * i] + x[2 * i + 1];
            shifted[i] = x[2 * i] + x[2 * i - 1];
        }
        dct.dct4of16(sums, even);
        dct.dct3of16(shifted, odd);
        var shift = scaled ? 2 : 0;
        for (var k = 0; k < 16; k++) {
            u[k] = FixedDct.round23((long) (even[k] + odd[k]) * DtsFixedTables.TWIDDLE_32_SUM[k]) << shift;
            u[31 - k] = FixedDct.round23((long) (even[k] - odd[k]) * DtsFixedTables.TWIDDLE_32_DIFFERENCE[k]) << shift;
        }
    }
}
