package com.github.auties00.smalt.audio.aac.internal;

/**
 * Runs the AAC-ELD low delay analysis filterbank, the counterpart of {@link LowDelayFilterbank}.
 *
 * <p>Each frame takes the latest {@code 4N} samples {@code x(n)}, {@code n} from {@code -3N} to {@code N - 1} with
 * {@code x(N - 1)} the newest, windows them with the time-reversed synthesis window and modulates them into
 * {@code N} spectral lines in the decoder's domain:
 * {@snippet :
 * X[k] = scale * sum(n = -3N .. N-1) w[N - 1 - n] * x(n) * cos(pi / N * (n + n0) * (k + 1/2))   // n0 = 1/2 - N/2
 * }
 * The sum is folded to {@code N} values, using {@code cos(a + 2 pi (k + 1/2)) = -cos(a)} and the symmetries of the
 * type-IV DCT, and transformed by an {@code N}-point DCT-IV. The synthesis filterbank reconstructs the input
 * {@link #delay(int)} samples later.
 *
 * <p>An instance holds the channel's last four frames and is not thread-safe.
 */
public final class LowDelayAnalysis {
    /**
     * The frame length {@code N}.
     */
    private final int length;

    /**
     * The folded position of each of the {@code 4N} windowed samples, oldest first.
     */
    private final int[] position;

    /**
     * The coefficient of each of the {@code 4N} samples: the window times the signs of the folding.
     */
    private final float[] coefficient;

    /**
     * The last four frames, oldest first.
     */
    private final float[] frames;

    /**
     * The folded frame.
     */
    private final float[] folded;

    /**
     * The DCT-IV, scaled to the decoder's domain.
     */
    private final Dct4 dct;

    /**
     * Prepares the filterbank of a frame length with a silent history.
     *
     * @param length the frame length, 512 or 480
     * @param kernel the DSP kernel
     * @param scale the factor of the transform
     */
    LowDelayAnalysis(int length, AacKernel kernel, double scale) {
        this.length = length;
        var window = length == 480 ? LowDelayWindows.WINDOW_480 : LowDelayWindows.WINDOW_512;
        var n = length;
        this.position = new int[4 * n];
        this.coefficient = new float[4 * n];
        for (var p = 0; p < 4 * n; p++) {
            // p indexes x(p - 3N); the modulation index m = n + n0 - 1/2 + N/2 = p - 3N has period 4N
            // and changes sign every 2N
            var m = p - 3 * n;
            var sign = 1;
            while (m < 0) {
                m += 2 * n;
                sign = -sign;
            }
            int j;
            if (m < n / 2) {
                j = m + n / 2;
            } else if (m < n) {
                j = 3 * n / 2 - 1 - m;
                sign = -sign;
            } else if (m < 3 * n / 2) {
                j = 3 * n / 2 - 1 - m;
                sign = -sign;
            } else {
                j = m - 3 * n / 2;
                sign = -sign;
            }
            position[p] = j;
            coefficient[p] = sign * window[4 * n - 1 - p];
        }
        this.frames = new float[4 * n];
        this.folded = new float[n];
        this.dct = new Dct4(n, scale, kernel);
    }

    /**
     * Prepares the filterbank of a frame length with a silent history, scaled so that {@link LowDelayFilterbank}
     * reconstructs the input.
     *
     * @param length the frame length, 512 or 480
     * @param kernel the DSP kernel
     */
    public LowDelayAnalysis(int length, AacKernel kernel) {
        this(length, kernel, -2 * 32768.0);
    }

    /**
     * Returns the number of samples between an input sample and its reconstruction by {@link LowDelayFilterbank}.
     *
     * @param length the frame length, 512 or 480
     * @return {@code 3N/4}
     */
    public static int delay(int length) {
        return 3 * length / 4;
    }

    /**
     * Analyzes one frame of new samples.
     *
     * @param input the input samples, in {@code [-1, 1]}
     * @param offset the index of the frame's first sample
     * @param spectrum receives the {@code N} spectral lines
     */
    public void analyze(float[] input, int offset, float[] spectrum) {
        var n = length;
        System.arraycopy(frames, n, frames, 0, 3 * n);
        System.arraycopy(input, offset, frames, 3 * n, n);
        java.util.Arrays.fill(folded, 0);
        for (var p = 0; p < 4 * n; p++) {
            folded[position[p]] += coefficient[p] * frames[p];
        }
        dct.transform(folded, 0, spectrum, 0);
    }

    /**
     * Clears the history.
     */
    public void reset() {
        java.util.Arrays.fill(frames, 0);
    }
}
