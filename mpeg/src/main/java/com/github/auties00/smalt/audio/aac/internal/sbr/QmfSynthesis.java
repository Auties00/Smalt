package com.github.auties00.smalt.audio.aac.internal.sbr;

/**
 * Merges complex QMF subbands back into time samples with the synthesis filterbank of ISO/IEC
 * 14496-3: 64 subbands into 64 samples per slot, or, in the downsampled mode, the lowest 32
 * subbands into 32 samples per slot.
 *
 * <p>With {@code N} subbands, each slot shifts {@code 2N} new values into the vector {@code V},
 * {@code V(n) = sum over k of Re(X(k) exp(i pi (k + 1/2)(2 n - d) / 2N)) / 64} with
 * {@code d = 255} for 64 subbands and {@code d = 127.5} for 32, and outputs {@code N} samples,
 * each the sum of ten products of {@code V} and the window ({@code c(n)}, or {@code c(2 n)} for
 * 32 subbands). The 32-band modulation is the time-reversed counterpart of the analysis bank's,
 * so the analysis and the downsampled synthesis reconstruct the core signal. Samples are scaled
 * back from the 16-bit range to the module's {@code [-1, 1]} range.
 *
 * @implNote This implementation transforms several slots at once, one per vector lane. It
 * computes the modulation with two {@code N/2}-point FFTs per slot, as the two {@code N}-point
 * type-IV transforms, the DCT-IV {@code P} of the real parts and the DST-IV {@code Q} of the
 * imaginary parts (a DCT-IV of the reversed input with alternating output signs):
 * {@code V(n) = Q(n) - P(n)} for {@code n} below {@code N} and
 * {@code V(n) = P(2N - 1 - n) + Q(2N - 1 - n)} above. The half-sample offset of the 32-band
 * modulation is a rotation of each subband by {@code exp(-i pi (k + 1/2) / 128)} ahead of the
 * transforms. The subbands are transposed so that one vector holds one subband of consecutive
 * slots, and {@code V} is transposed back to one {@code 2N}-value row per slot before the
 * windowing, so that the ten taps of a slot are contiguous aligned runs; the rows of the nine
 * slots before the frame stay in front of it.
 *
 * <p>The low delay bank of AAC-ELD uses the prototype of {@link LowDelayQmfWindow} ({@code c(n)}, or
 * {@code (c(2n) + c(2n + 1)) / 2} for 32 subbands) and the modulation offset {@code d = 3N - 1}, a
 * rotation of each subband by {@code exp(i pi (k + 1/2) / 2)} ahead of the same transforms; it
 * reconstructs the input of the low delay analysis bank, delayed by {@code N} samples.
 */
final class QmfSynthesis {
    /**
     * The number of slots of {@code V} kept from one frame for the next, nine.
     */
    private static final int HISTORY = 9;

    /**
     * The number of slots of a padded frame.
     */
    private static final int SLOTS = 32;

    /**
     * The window {@code c(m) / 32768} of the 64-band bank.
     */
    private static final float[] WINDOW_64 = new float[640];

    /**
     * The window {@code c(2 m) / 32768} of the 32-band bank.
     */
    private static final float[] WINDOW_32 = new float[320];

    /**
     * The window {@code c(m) / 32768} of the 64-band low delay bank.
     */
    private static final float[] LOW_DELAY_WINDOW_64 = new float[640];

    /**
     * The window {@code (c(2 m) + c(2 m + 1)) / 65536} of the 32-band low delay bank.
     */
    private static final float[] LOW_DELAY_WINDOW_32 = new float[320];

    /**
     * The real parts of the low delay rotation {@code exp(i 3 pi (k + 1/2) / 2)}.
     */
    private static final float[] LOW_DELAY_ROTATION_REAL = new float[64];

    /**
     * The imaginary parts of the low delay rotation.
     */
    private static final float[] LOW_DELAY_ROTATION_IMAGINARY = new float[64];

    /**
     * The real parts of the 32-band rotation {@code exp(-i pi (k + 1/2) / 128)}.
     */
    private static final float[] ROTATION_REAL = new float[32];

    /**
     * The imaginary parts of the 32-band rotation.
     */
    private static final float[] ROTATION_IMAGINARY = new float[32];

    /**
     * The twiddles of the radix-4 pass of the 32-point FFT: for {@code j} below 8, the cosines
     * of {@code w^j}, {@code w^(2j)} and {@code w^(3j)}, then their sines, with
     * {@code w = exp(-2 pi i / 32)}.
     */
    private static final float[] RADIX4 = new float[48];

    /**
     * The twiddles of the radix-2 pass of the 16-point FFT: eight cosines then eight sines of
     * {@code exp(-i pi j / 8)}.
     */
    private static final float[] RADIX2 = new float[16];

    /**
     * The row of each frequency of the 32-point FFT.
     */
    private static final int[] POSITIONS_32 = new int[32];

    /**
     * The row of each frequency of the 16-point FFT.
     */
    private static final int[] POSITIONS_16 = new int[16];

    static {
        for (var m = 0; m < 640; m++) {
            WINDOW_64[m] = (float) (SbrTables.QMF_WINDOW[m] / 32768);
        }
        for (var m = 0; m < 320; m++) {
            WINDOW_32[m] = (float) (SbrTables.QMF_WINDOW[2 * m] / 32768);
        }
        for (var m = 0; m < 640; m++) {
            LOW_DELAY_WINDOW_64[m] = (float) (LowDelayQmfWindow.COEFFICIENTS[m] / 32768);
        }
        for (var m = 0; m < 320; m++) {
            LOW_DELAY_WINDOW_32[m] = (float) ((LowDelayQmfWindow.COEFFICIENTS[2 * m]
                    + LowDelayQmfWindow.COEFFICIENTS[2 * m + 1]) / 65536);
        }
        for (var k = 0; k < 64; k++) {
            var angle = 3 * Math.PI * (k + 0.5) / 2;
            LOW_DELAY_ROTATION_REAL[k] = (float) StrictMath.cos(angle);
            LOW_DELAY_ROTATION_IMAGINARY[k] = (float) StrictMath.sin(angle);
        }
        for (var k = 0; k < 32; k++) {
            var angle = -Math.PI * (k + 0.5) / 128;
            ROTATION_REAL[k] = (float) StrictMath.cos(angle);
            ROTATION_IMAGINARY[k] = (float) StrictMath.sin(angle);
            POSITIONS_32[k] = (k & 3) * 8 + (Integer.reverse(k >> 2) >>> 29);
        }
        for (var k = 0; k < 16; k++) {
            POSITIONS_16[k] = (k & 1) * 8 + (Integer.reverse(k >> 1) >>> 29);
        }
        for (var j = 0; j < 8; j++) {
            for (var m = 1; m <= 3; m++) {
                var angle = -2 * Math.PI * m * j / 32;
                RADIX4[(m - 1) * 8 + j] = (float) StrictMath.cos(angle);
                RADIX4[(m + 2) * 8 + j] = (float) StrictMath.sin(angle);
            }
            RADIX2[j] = (float) StrictMath.cos(-Math.PI * j / 8);
            RADIX2[8 + j] = (float) StrictMath.sin(-Math.PI * j / 8);
        }
    }

    /**
     * The number of subbands, 64 or 32.
     */
    private final int bands;

    /**
     * The window.
     */
    private final float[] window;

    /**
     * The real parts of the rotation applied to each subband ahead of the transforms, or {@code null}
     * for none.
     */
    private final float[] rotationRe;

    /**
     * The imaginary parts of the rotation.
     */
    private final float[] rotationIm;

    /**
     * The transform kernel.
     */
    private final SbrKernel kernel;

    /**
     * The pre-twiddle cosines, {@code cos(pi (k + 1/8) / N) / 64}.
     */
    private final float[] preCos;

    /**
     * The pre-twiddle sines, {@code sin(pi (k + 1/8) / N) / 64}.
     */
    private final float[] preSin;

    /**
     * The post-twiddle cosines, {@code cos(pi (k + 1/8) / N)}.
     */
    private final float[] postCos;

    /**
     * The post-twiddle sines, {@code sin(pi (k + 1/8) / N)}.
     */
    private final float[] postSin;

    /**
     * The row of each frequency of the FFT.
     */
    private final int[] positions;

    /**
     * The vectors {@code V}, {@code 2 bands} values per slot: the {@link #HISTORY} slots before
     * the frame, then the frame.
     */
    private final float[] vector;

    /**
     * The subbands of a chunk, one subband per row: real parts.
     */
    private final float[] bandsRe;

    /**
     * The subbands of a chunk, imaginary parts.
     */
    private final float[] bandsIm;

    /**
     * The 32-band subbands of the frame after the half-sample rotation, 32 per slot: real parts.
     */
    private final float[] rotatedRe;

    /**
     * The 32-band subbands after the rotation: imaginary parts.
     */
    private final float[] rotatedIm;

    /**
     * The real parts of the FFT rows of a chunk.
     */
    private final float[] fftRe;

    /**
     * The imaginary parts of the FFT rows of a chunk.
     */
    private final float[] fftIm;

    /**
     * The rows of {@code V} of a chunk, one value per row.
     */
    private final float[] rows;

    /**
     * Creates a synthesis bank with a silent history.
     *
     * @param bands  the number of subbands, 64 or 32 (downsampled)
     * @param kernel the transform kernel
     */
    QmfSynthesis(int bands, SbrKernel kernel) {
        this(bands, kernel, false);
    }

    /**
     * Creates a synthesis bank with a silent history, optionally the low delay bank of AAC-ELD.
     *
     * @param bands the number of subbands, 64 or 32 (downsampled)
     * @param kernel the transform kernel
     * @param lowDelay whether the bank is the low delay bank
     */
    QmfSynthesis(int bands, SbrKernel kernel, boolean lowDelay) {
        this.bands = bands;
        this.kernel = kernel;
        if (lowDelay) {
            this.window = bands == 64 ? LOW_DELAY_WINDOW_64 : LOW_DELAY_WINDOW_32;
            this.rotationRe = LOW_DELAY_ROTATION_REAL;
            this.rotationIm = LOW_DELAY_ROTATION_IMAGINARY;
        } else {
            this.window = bands == 64 ? WINDOW_64 : WINDOW_32;
            this.rotationRe = bands == 32 ? ROTATION_REAL : null;
            this.rotationIm = bands == 32 ? ROTATION_IMAGINARY : null;
        }
        this.positions = bands == 64 ? POSITIONS_32 : POSITIONS_16;
        var half = bands / 2;
        this.preCos = new float[half];
        this.preSin = new float[half];
        this.postCos = new float[half];
        this.postSin = new float[half];
        for (var k = 0; k < half; k++) {
            var angle = Math.PI * (k + 0.125) / bands;
            preCos[k] = (float) (StrictMath.cos(angle) / 64);
            preSin[k] = (float) (StrictMath.sin(angle) / 64);
            postCos[k] = (float) StrictMath.cos(angle);
            postSin[k] = (float) StrictMath.sin(angle);
        }
        var width = kernel.width();
        this.vector = new float[(HISTORY + SLOTS) * 2 * bands];
        this.bandsRe = new float[bands * width];
        this.bandsIm = new float[bands * width];
        this.rotatedRe = rotationRe != null ? new float[SLOTS * bands] : null;
        this.rotatedIm = rotationRe != null ? new float[SLOTS * bands] : null;
        this.fftRe = new float[bands * width];
        this.fftIm = new float[bands * width];
        this.rows = new float[2 * bands * width];
    }

    /**
     * Returns the number of subbands.
     *
     * @return 64, or 32 in the downsampled mode
     */
    int bands() {
        return bands;
    }

    /**
     * Synthesizes the slots of one frame.
     *
     * @param re        the real parts, 64 per slot
     * @param im        the imaginary parts, 64 per slot
     * @param slots     the number of slots
     * @param out       the destination samples, {@code bands} per slot
     * @param outOffset the index of the first sample
     */
    void synthesize(float[] re, float[] im, int slots, float[] out, int outOffset) {
        var span = 2 * bands;
        System.arraycopy(vector, slots * span, vector, 0, HISTORY * span);
        var inRe = re;
        var inIm = im;
        var stride = 64;
        if (rotationRe != null) {
            rotate(re, im, slots);
            inRe = rotatedRe;
            inIm = rotatedIm;
            stride = bands;
        }
        var width = kernel.width();
        for (var column = 0; column < slots; column += width) {
            var lanes = Math.min(width, slots - column);
            if (lanes == width) {
                kernel.transpose(inRe, stride * column, stride, bandsRe, 0, width, width, bands);
                kernel.transpose(inIm, stride * column, stride, bandsIm, 0, width, width, bands);
            } else {
                for (var l = 0; l < width; l++) {
                    for (var k = 0; k < bands; k++) {
                        var valid = l < lanes;
                        bandsRe[k * width + l] = valid ? inRe[stride * (column + l) + k] : 0;
                        bandsIm[k * width + l] = valid ? inIm[stride * (column + l) + k] : 0;
                    }
                }
            }
            kernel.synthesisInput(bandsRe, bandsIm, bands, preCos, preSin, fftRe, fftIm);
            if (bands == 64) {
                kernel.radix4(fftRe, fftIm, 0, RADIX4);
                kernel.radix4(fftRe, fftIm, 32, RADIX4);
                kernel.tail8(fftRe, fftIm, 0, 8);
            } else {
                kernel.radix2(fftRe, fftIm, 0, RADIX2);
                kernel.radix2(fftRe, fftIm, 16, RADIX2);
                kernel.tail8(fftRe, fftIm, 0, 4);
            }
            kernel.synthesisOutput(fftRe, fftIm, positions, postCos, postSin, bands, rows);
            kernel.transpose(rows, 0, width, vector, (HISTORY + column) * span, span, span, width);
            kernel.synthesisWindow(vector, (HISTORY + column) * span, window, bands, out, outOffset + bands * column,
                    lanes);
        }
    }

    /**
     * Rotates the subbands of a frame by the offset of the modulation: the half-sample offset of the
     * 32-band modulation, or the offset of the low delay modulation.
     *
     * @param re    the real parts, 64 per slot
     * @param im    the imaginary parts, 64 per slot
     * @param slots the number of slots
     */
    private void rotate(float[] re, float[] im, int slots) {
        for (var slot = 0; slot < slots; slot++) {
            for (var k = 0; k < bands; k++) {
                var xr = re[64 * slot + k];
                var xi = im[64 * slot + k];
                rotatedRe[bands * slot + k] = xr * rotationRe[k] - xi * rotationIm[k];
                rotatedIm[bands * slot + k] = xi * rotationRe[k] + xr * rotationIm[k];
            }
        }
    }
}
