package com.github.auties00.smalt.audio.aac.internal;

/**
 * Runs the AAC-ELD low delay synthesis filterbank (ISO/IEC 14496-3 subclause 4.6.20).
 *
 * <p>Each frame of {@code N} spectral lines is transformed by an {@code N}-point DCT-IV, extended to a
 * sequence {@code z} of {@code 4N} samples by the symmetries of the DCT-IV ({@code z[t] = y[t + 3N/2]}
 * with {@code y[-1-m] = y[m]} and {@code y[m + 2N] = -y[m]}), multiplied by the {@code 4N}-sample window
 * of {@link LowDelayWindows}, and overlapped with the three previous frames:
 * {@snippet :
 * out[n] = sum(k = 0..3) w[n + N/4 + k*N] * z[i - k][n + N/4 + k*N]    // 0 <= n < N
 * }
 * The window's first {@code N/4} coefficients are zero, so the filterbank keeps {@code 11N/4} samples of
 * memory per channel. Output samples are scaled so that a full-scale 16-bit decoder output maps to
 * {@code [-1, 1]}.
 *
 * <p>An instance holds scratch memory and is not thread-safe; the memory of each channel is owned by the
 * caller.
 */
public final class LowDelayFilterbank {
    /**
     * The inverse of the 16-bit full scale.
     */
    private static final double OUTPUT_SCALE = 1.0 / 32768.0;

    /**
     * The frame length {@code N}.
     */
    private final int length;

    /**
     * The DCT-IV, scaled to the output.
     */
    private final Dct4 dct;

    /**
     * The window times the sign of the extension, for {@code t} from {@code N/4} to {@code 4N}, indexed
     * {@code t - N/4}.
     */
    private final float[] window;

    /**
     * The DCT-IV output index each windowed sample takes, indexed like {@link #window}.
     */
    private final int[] source;

    /**
     * The DCT-IV output.
     */
    private final float[] transformed;

    /**
     * Prepares the filterbank of a frame length.
     *
     * @param length the frame length, 512 or 480
     * @param kernel the DSP kernel
     */
    public LowDelayFilterbank(int length, AacKernel kernel) {
        this.length = length;
        this.dct = new Dct4(length, OUTPUT_SCALE / length, kernel);
        var table = length == 480 ? LowDelayWindows.WINDOW_480 : LowDelayWindows.WINDOW_512;
        var delay = length / 4;
        this.window = new float[4 * length - delay];
        this.source = new int[4 * length - delay];
        for (var t = delay; t < 4 * length; t++) {
            var m = (t + 3 * length / 2) % (4 * length);
            int index;
            float sign;
            if (m < length) {
                index = m;
                sign = 1;
            } else if (m < 2 * length) {
                index = 2 * length - 1 - m;
                sign = -1;
            } else if (m < 3 * length) {
                index = m - 2 * length;
                sign = -1;
            } else {
                index = 4 * length - 1 - m;
                sign = 1;
            }
            window[t - delay] = sign * table[t];
            source[t - delay] = index;
        }
        this.transformed = new float[length];
    }

    /**
     * Returns the number of memory samples a channel needs.
     *
     * @param length the frame length
     * @return {@code 11N/4}
     */
    public static int stateLength(int length) {
        return 3 * length - length / 4;
    }

    /**
     * Synthesizes one frame.
     *
     * @param spectrum the {@code N} spectral lines
     * @param state the channel's memory, {@link #stateLength(int)} samples, updated in place
     * @param out the output array
     * @param outOffset the index of the first output sample
     */
    public void synthesize(float[] spectrum, float[] state, float[] out, int outOffset) {
        var n = length;
        dct.transform(spectrum, 0, transformed, 0);
        var y = transformed;
        var w = window;
        var s = source;
        for (var j = 0; j < n; j++) {
            out[outOffset + j] = w[j] * y[s[j]] + state[j];
        }
        var carried = state.length - n;
        for (var j = 0; j < carried; j++) {
            state[j] = w[n + j] * y[s[n + j]] + state[n + j];
        }
        for (var j = carried; j < state.length; j++) {
            state[j] = w[n + j] * y[s[n + j]];
        }
    }
}
