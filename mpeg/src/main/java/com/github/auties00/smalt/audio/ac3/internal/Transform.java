package com.github.auties00.smalt.audio.ac3.internal;

/**
 * Computes the transforms of the AC-3 filter bank (ATSC A/52 section 7.9): the inverse transform
 * of one audio block, as one 512-sample transform or two interleaved 256-sample transforms,
 * followed by the window, and the forward transform the encoder needs, which is its adjoint.
 *
 * <p>The inverse follows the fast algorithm of A/52 section 7.9.4 step by step: a complex
 * pre-twiddle, an unnormalized inverse FFT of 128 (or two of 64) points, a post-twiddle, and the
 * windowing and de-interleaving step. Instances are not thread-safe.
 */
public final class Transform {
    /**
     * The real parts of the pre- and post-twiddle factors of the long transform,
     * {@code xcos1[k] = -cos(2 pi (8k + 1) / 4096)}.
     */
    private static final float[] COS_LONG = new float[128];

    /**
     * The imaginary parts of the twiddle factors of the long transform,
     * {@code xsin1[k] = -sin(2 pi (8k + 1) / 4096)}.
     */
    private static final float[] SIN_LONG = new float[128];

    /**
     * The real parts of the twiddle factors of the short transforms,
     * {@code xcos2[k] = -cos(2 pi (8k + 1) / 2048)}.
     */
    private static final float[] COS_SHORT = new float[64];

    /**
     * The imaginary parts of the twiddle factors of the short transforms,
     * {@code xsin2[k] = -sin(2 pi (8k + 1) / 2048)}.
     */
    private static final float[] SIN_SHORT = new float[64];

    /**
     * The negated imaginary parts of {@link #SIN_LONG}, the conjugate factors of the forward
     * transform.
     */
    private static final float[] CONJ_SIN_LONG = new float[128];

    /**
     * The negated imaginary parts of {@link #SIN_SHORT}.
     */
    private static final float[] CONJ_SIN_SHORT = new float[64];

    static {
        for (var k = 0; k < 128; k++) {
            var angle = 2 * Math.PI * (8 * k + 1) / 4096;
            COS_LONG[k] = (float) -StrictMath.cos(angle);
            SIN_LONG[k] = (float) -StrictMath.sin(angle);
            CONJ_SIN_LONG[k] = -SIN_LONG[k];
        }
        for (var k = 0; k < 64; k++) {
            var angle = 2 * Math.PI * (8 * k + 1) / 2048;
            COS_SHORT[k] = (float) -StrictMath.cos(angle);
            SIN_SHORT[k] = (float) -StrictMath.sin(angle);
            CONJ_SIN_SHORT[k] = -SIN_SHORT[k];
        }
    }

    /**
     * The scale of the forward long transform that makes the inverse transform, with the
     * overlap-add of A/52 section 7.9.4 step 6, reconstruct the encoder input.
     */
    private static final float FORWARD_SCALE_LONG = 1.0f / 256;

    /**
     * The scale of the forward short transforms, as {@link #FORWARD_SCALE_LONG}.
     */
    private static final float FORWARD_SCALE_SHORT = 1.0f / 128;

    /**
     * The window of the inverse transform's output in output order, with the signs of the
     * de-interleaving step folded in: the output {@code k} is one twiddled FFT value times
     * {@code WINDOW_OUT[k]}.
     */
    private static final float[] WINDOW_OUT = windowOut();

    /**
     * The bit-reversed position of each index of the 128-point transform.
     */
    private static final int[] REVERSED_128 = reversal(7);

    /**
     * The bit-reversed position of each index of the 64-point transforms.
     */
    private static final int[] REVERSED_64 = reversal(6);

    /**
     * {@link #COS_LONG} in bit-reversed order, the pre-twiddle of a permuted input.
     */
    private static final float[] COS_LONG_REVERSED = permute(COS_LONG, REVERSED_128);

    /**
     * {@link #SIN_LONG} in bit-reversed order.
     */
    private static final float[] SIN_LONG_REVERSED = permute(SIN_LONG, REVERSED_128);

    /**
     * {@link #COS_SHORT} in bit-reversed order.
     */
    private static final float[] COS_SHORT_REVERSED = permute(COS_SHORT, REVERSED_64);

    /**
     * {@link #SIN_SHORT} in bit-reversed order.
     */
    private static final float[] SIN_SHORT_REVERSED = permute(SIN_SHORT, REVERSED_64);

    /**
     * The 128-point transform of the long blocks.
     */
    private final Fft long128;

    /**
     * The 64-point transform of the short blocks.
     */
    private final Fft short64;

    /**
     * The kernels the twiddles run on.
     */
    private final Ac3Kernel kernel;

    /**
     * The real parts of the first (or only) transform.
     */
    private final float[] re1 = new float[128];

    /**
     * The imaginary parts of the first (or only) transform.
     */
    private final float[] im1 = new float[128];

    /**
     * The real parts of the second short transform.
     */
    private final float[] re2 = new float[64];

    /**
     * The imaginary parts of the second short transform.
     */
    private final float[] im2 = new float[64];

    /**
     * Creates a transform running on a kernel.
     *
     * @param kernel the kernels
     */
    public Transform(Ac3Kernel kernel) {
        this.kernel = kernel;
        this.long128 = new Fft(128, kernel);
        this.short64 = new Fft(64, kernel);
    }

    /**
     * Builds the bit reversal of a transform length.
     *
     * @param bits the number of index bits
     * @return the reversed position of each index
     */
    private static int[] reversal(int bits) {
        var positions = new int[1 << bits];
        for (var i = 0; i < positions.length; i++) {
            positions[i] = Integer.reverse(i) >>> (32 - bits);
        }
        return positions;
    }

    /**
     * Moves each value of a table to the bit-reversed position of its index.
     *
     * @param table the values to permute in place
     * @param positions the bit-reversed positions
     * @return the permuted table
     */
    private static float[] permute(float[] table, int[] positions) {
        var permuted = new float[table.length];
        for (var i = 0; i < table.length; i++) {
            permuted[positions[i]] = table[i];
        }
        return permuted;
    }

    /**
     * Builds {@link #WINDOW_OUT} from the window of A/52 table 7.33 and the de-interleaving of
     * A/52 section 7.9.4.1 step 5.
     *
     * @return the signed window in output order
     */
    private static float[] windowOut() {
        var w = Ac3Tables.WINDOW;
        var out = new float[512];
        for (var n = 0; n < 64; n++) {
            out[2 * n] = -w[2 * n];
            out[2 * n + 1] = w[2 * n + 1];
            out[128 + 2 * n] = -w[128 + 2 * n];
            out[129 + 2 * n] = w[129 + 2 * n];
            out[256 + 2 * n] = -w[255 - 2 * n];
            out[257 + 2 * n] = w[254 - 2 * n];
            out[384 + 2 * n] = w[127 - 2 * n];
            out[385 + 2 * n] = -w[126 - 2 * n];
        }
        return out;
    }

    /**
     * Computes the windowed time samples of one block (steps 1 to 5 of A/52 section 7.9.4).
     *
     * <p>The coefficients are multiplied by {@code gain} first; the factor 2 of the overlap-add
     * step is part of the gain the caller passes.
     *
     * @param coefficients the 256 transform coefficients, interleaved for short blocks
     * @param shortBlocks whether the block holds two 256-sample transforms ({@code blksw})
     * @param gain the gain applied to the coefficients
     * @param out receives the 512 windowed samples
     */
    public void inverse(float[] coefficients, boolean shortBlocks, float gain, float[] out) {
        var w = WINDOW_OUT;
        if (!shortBlocks) {
            var reversed = REVERSED_128;
            for (var k = 0; k < 128; k++) {
                var position = reversed[k];
                re1[position] = coefficients[255 - 2 * k] * gain;
                im1[position] = coefficients[2 * k] * gain;
            }
            kernel.twiddle(re1, im1, COS_LONG_REVERSED, SIN_LONG_REVERSED, 128);
            long128.inversePermuted(re1, im1);
            kernel.twiddle(re1, im1, COS_LONG, SIN_LONG, 128);
            kernel.interleave(im1, 64, re1, 63, w, 0, out, 0, 64);
            kernel.interleave(re1, 0, im1, 127, w, 128, out, 128, 64);
            kernel.interleave(re1, 64, im1, 63, w, 256, out, 256, 64);
            kernel.interleave(im1, 0, re1, 127, w, 384, out, 384, 64);
            return;
        }
        var reversed = REVERSED_64;
        for (var k = 0; k < 64; k++) {
            var position = reversed[k];
            re1[position] = coefficients[254 - 4 * k] * gain;
            im1[position] = coefficients[4 * k] * gain;
            re2[position] = coefficients[255 - 4 * k] * gain;
            im2[position] = coefficients[4 * k + 1] * gain;
        }
        kernel.twiddle(re1, im1, COS_SHORT_REVERSED, SIN_SHORT_REVERSED, 64);
        kernel.twiddle(re2, im2, COS_SHORT_REVERSED, SIN_SHORT_REVERSED, 64);
        short64.inversePermuted(re1, im1);
        short64.inversePermuted(re2, im2);
        kernel.twiddle(re1, im1, COS_SHORT, SIN_SHORT, 64);
        kernel.twiddle(re2, im2, COS_SHORT, SIN_SHORT, 64);
        kernel.interleave(im1, 0, re1, 63, w, 0, out, 0, 64);
        kernel.interleave(re1, 0, im1, 63, w, 128, out, 128, 64);
        kernel.interleave(re2, 0, im2, 63, w, 256, out, 256, 64);
        kernel.interleave(im2, 0, re2, 63, w, 384, out, 384, 64);
    }

    /**
     * Computes the transform coefficients of one windowed block of 512 samples, the inverse of
     * {@link #inverse(float[], boolean, float, float[])} with the overlap-add (A/52 section
     * 8.2.3).
     *
     * <p>A long block yields 256 coefficients; short blocks yield the coefficients of the two
     * 256-sample transforms interleaved.
     *
     * @param windowed the 512 windowed samples
     * @param shortBlocks whether to compute two 256-sample transforms
     * @param coefficients receives the 256 coefficients
     */
    public void forward(float[] windowed, boolean shortBlocks, float[] coefficients) {
        var v = windowed;
        if (!shortBlocks) {
            for (var m = 0; m < 64; m++) {
                re1[m] = v[127 - 2 * m] - v[128 + 2 * m];
                re1[64 + m] = -v[256 + 2 * m] - v[511 - 2 * m];
                im1[m] = v[383 - 2 * m] + v[384 + 2 * m];
                im1[64 + m] = v[255 - 2 * m] - v[2 * m];
            }
            kernel.twiddle(re1, im1, COS_LONG, CONJ_SIN_LONG, 128);
            long128.forward(re1, im1);
            kernel.twiddle(re1, im1, COS_LONG, CONJ_SIN_LONG, 128);
            for (var k = 0; k < 128; k++) {
                coefficients[255 - 2 * k] = re1[k] * FORWARD_SCALE_LONG;
                coefficients[2 * k] = im1[k] * FORWARD_SCALE_LONG;
            }
            return;
        }
        for (var m = 0; m < 64; m++) {
            re1[m] = v[127 - 2 * m] - v[128 + 2 * m];
            im1[m] = v[255 - 2 * m] - v[2 * m];
            re2[m] = -v[256 + 2 * m] - v[511 - 2 * m];
            im2[m] = v[383 - 2 * m] + v[384 + 2 * m];
        }
        kernel.twiddle(re1, im1, COS_SHORT, CONJ_SIN_SHORT, 64);
        kernel.twiddle(re2, im2, COS_SHORT, CONJ_SIN_SHORT, 64);
        short64.forward(re1, im1);
        short64.forward(re2, im2);
        kernel.twiddle(re1, im1, COS_SHORT, CONJ_SIN_SHORT, 64);
        kernel.twiddle(re2, im2, COS_SHORT, CONJ_SIN_SHORT, 64);
        for (var k = 0; k < 64; k++) {
            coefficients[254 - 4 * k] = re1[k] * FORWARD_SCALE_SHORT;
            coefficients[4 * k] = im1[k] * FORWARD_SCALE_SHORT;
            coefficients[255 - 4 * k] = re2[k] * FORWARD_SCALE_SHORT;
            coefficients[4 * k + 1] = im2[k] * FORWARD_SCALE_SHORT;
        }
    }
}
