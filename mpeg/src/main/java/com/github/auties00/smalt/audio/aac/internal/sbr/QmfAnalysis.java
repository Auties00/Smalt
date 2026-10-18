package com.github.auties00.smalt.audio.aac.internal.sbr;

/**
 * Splits a channel of time samples into 32 complex QMF subbands, one time slot per 32 samples,
 * with the analysis filterbank of ISO/IEC 14496-3.
 *
 * <p>For each slot the 320 latest samples {@code x(n)}, newest first, are windowed into
 * {@code u(n) = sum over j of x(n + 64 j) c(2 (n + 64 j))} for {@code n} below 64, and the
 * subbands are {@code W(k) = 2 sum u(n) exp(i pi (k + 1/2)(2 n - 1/2) / 64)}. Samples are
 * scaled from the module's {@code [-1, 1]} range to the 16-bit range the standard's energies
 * refer to. The bank keeps the last 288 samples of each frame for the next one.
 *
 * @implNote This implementation transforms several slots at once, one per vector lane. The
 * window sums of one slot run along the 64 sums (contiguous samples), then are transposed so
 * that the same sum of consecutive slots sits in one vector, and the 64 x 32 complex modulation
 * is computed with two 16-point FFTs per slot, as two 32-point type-IV DCTs: writing {@code a}
 * and {@code b} for the halves of {@code u}, the real part is the DCT-IV of
 * {@code a(n) - b(31 - n)} and the imaginary part the DCT-IV of {@code a(31 - n) + b(n)} with
 * alternating signs, both rotated by {@code exp(-i 3 pi (k + 1/2) / 128)}. The FFT is a
 * radix-2 pass followed by two eight-point tails, with the same output permutation for every
 * slot, so no lane ever needs a shuffle; the subbands are transposed back to one slot per row
 * of 32 on the way out.
 */
final class QmfAnalysis {
    /**
     * The number of samples kept from one frame for the next.
     */
    private static final int HISTORY = 288;

    /**
     * The number of samples past the newest frame that a padded last chunk reads.
     */
    private static final int SLACK = 64;

    /**
     * The window {@code 2 * 32768 * c(2 (63 - m + 64 j))} at {@code 64 j + m}, for {@code j} below
     * 5 and {@code m} below 64.
     */
    private static final float[] WINDOW = new float[320];

    /**
     * The real parts of the output rotation {@code exp(-i 3 pi (k + 1/2) / 128)}.
     */
    private static final float[] ROTATION_REAL = new float[32];

    /**
     * The imaginary parts of the output rotation.
     */
    private static final float[] ROTATION_IMAGINARY = new float[32];

    /**
     * The pre-twiddle cosines {@code cos(pi (k + 1/8) / 32)}.
     */
    private static final float[] TWIDDLE_COS = new float[16];

    /**
     * The pre-twiddle sines {@code sin(pi (k + 1/8) / 32)}.
     */
    private static final float[] TWIDDLE_SIN = new float[16];

    /**
     * The twiddles of the radix-2 pass of the 16-point FFT: eight cosines then eight sines of
     * {@code exp(-i pi j / 8)}.
     */
    private static final float[] RADIX2 = new float[16];

    /**
     * The row of each frequency of the 16-point FFT.
     */
    private static final int[] POSITIONS = new int[16];

    static {
        for (var j = 0; j < 5; j++) {
            for (var m = 0; m < 64; m++) {
                WINDOW[64 * j + m] = (float) (2 * 32768 * SbrTables.QMF_WINDOW[2 * (63 - m + 64 * j)]);
            }
        }
        for (var k = 0; k < 32; k++) {
            var angle = -3 * Math.PI * (k + 0.5) / 128;
            ROTATION_REAL[k] = (float) Math.cos(angle);
            ROTATION_IMAGINARY[k] = (float) Math.sin(angle);
        }
        for (var k = 0; k < 16; k++) {
            var angle = Math.PI * (k + 0.125) / 32;
            TWIDDLE_COS[k] = (float) Math.cos(angle);
            TWIDDLE_SIN[k] = (float) Math.sin(angle);
            POSITIONS[k] = (k & 1) * 8 + (Integer.reverse(k >> 1) >>> 29);
        }
        for (var j = 0; j < 8; j++) {
            RADIX2[j] = (float) Math.cos(-Math.PI * j / 8);
            RADIX2[8 + j] = (float) Math.sin(-Math.PI * j / 8);
        }
    }

    /**
     * The transform kernel.
     */
    private final SbrKernel kernel;

    /**
     * The samples of the history followed by the current frame, oldest first.
     */
    private final float[] samples = new float[HISTORY + 32 * 32 + SLACK];

    /**
     * The windowed sums of one chunk, one slot per row.
     */
    private final float[] windowed;

    /**
     * The windowed sums of a chunk, one sum per row.
     */
    private final float[] rows;

    /**
     * The real parts of the FFT rows of a chunk.
     */
    private final float[] fftRe;

    /**
     * The imaginary parts of the FFT rows of a chunk.
     */
    private final float[] fftIm;

    /**
     * The real parts of the subbands of a chunk, one subband per row.
     */
    private final float[] bandsRe;

    /**
     * The imaginary parts of the subbands of a chunk.
     */
    private final float[] bandsIm;

    /**
     * Creates an analysis bank with a silent history.
     *
     * @param kernel the transform kernel
     */
    QmfAnalysis(SbrKernel kernel) {
        this.kernel = kernel;
        var width = kernel.width();
        this.windowed = new float[width * 64];
        this.rows = new float[64 * width];
        this.fftRe = new float[32 * width];
        this.fftIm = new float[32 * width];
        this.bandsRe = new float[32 * width];
        this.bandsIm = new float[32 * width];
    }

    /**
     * Analyzes one frame.
     *
     * @param input the frame's samples
     * @param slots the number of slots, the frame length divided by 32
     * @param re the destination real parts, 32 per slot
     * @param im the destination imaginary parts, 32 per slot
     * @param offset the index of the first slot's first subband in {@code re} and {@code im}
     */
    void analyze(float[] input, int slots, float[] re, float[] im, int offset) {
        var length = 32 * slots;
        System.arraycopy(samples, length, samples, 0, HISTORY);
        System.arraycopy(input, 0, samples, HISTORY, length);
        var width = kernel.width();
        for (var column = 0; column < slots; column += width) {
            kernel.analysisWindow(samples, HISTORY + 32 * column - 32, WINDOW, windowed);
            kernel.transpose(windowed, 0, 64, rows, 0, width, width, 64);
            kernel.foldRows(rows, TWIDDLE_COS, TWIDDLE_SIN, fftRe, fftIm);
            kernel.radix2(fftRe, fftIm, 0, RADIX2);
            kernel.radix2(fftRe, fftIm, 16, RADIX2);
            kernel.tail8(fftRe, fftIm, 0, 4);
            kernel.analysisOutput(fftRe, fftIm, POSITIONS, TWIDDLE_COS, TWIDDLE_SIN, ROTATION_REAL, ROTATION_IMAGINARY,
                    bandsRe, bandsIm);
            var lanes = Math.min(width, slots - column);
            if (lanes == width) {
                kernel.transpose(bandsRe, 0, width, re, offset + 32 * column, 32, 32, width);
                kernel.transpose(bandsIm, 0, width, im, offset + 32 * column, 32, 32, width);
            } else {
                for (var l = 0; l < lanes; l++) {
                    for (var k = 0; k < 32; k++) {
                        re[offset + 32 * (column + l) + k] = bandsRe[k * width + l];
                        im[offset + 32 * (column + l) + k] = bandsIm[k * width + l];
                    }
                }
            }
        }
    }
}
