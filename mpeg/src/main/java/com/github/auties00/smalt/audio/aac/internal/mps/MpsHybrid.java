/* Ports the ISO/IEC 23003-3:2019 reference software MPEG Surround decoder (sac_hybfilter.c). Copyright Agere
   Systems, Coding Technologies, Fraunhofer IIS, Philips and ISO/IEC 2008; ISO/IEC reference software licence (use
   in products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.mps;

/**
 * Splits the lowest three QMF subbands of one channel into ten hybrid subbands, for 71 hybrid subbands of 64 QMF
 * subbands, and merges them back (the hybrid filterbank of ISO/IEC 23003-1 subclause 6.5.4).
 *
 * <p>The upper subbands pass through undelayed and the hybrid subbands come out six slots late, as in the
 * reference decoder. Instances keep the filter history of one channel; they are not thread-safe.
 */
public final class MpsHybrid {
    /**
     * The number of hybrid subbands of 64 QMF subbands.
     */
    public static final int BANDS = 71;

    /**
     * {@return the number of hybrid subbands of a number of QMF subbands}
     *
     * @param qmfBands the number of QMF subbands
     */
    public static int bands(int qmfBands) {
        return qmfBands - 3 + 10;
    }

    private static final int LENGTH = 13;
    private static final float PI = 3.14159265358979f;
    private static final float[] COEFFICIENTS_8 = {
            0.00746082949812f, 0.02270420949825f, 0.04546865930473f,
            0.07266113929591f, 0.09885108575264f, 0.11793710567217f,
            0.12500000000000f, 0.11793710567217f, 0.09885108575264f,
            0.07266113929591f, 0.04546865930473f, 0.02270420949825f,
            0.00746082949812f
    };
    private static final float[] COEFFICIENTS_2 = {
            0.0f, 0.01899487526049f, 0.0f,
            -0.07293139167538f, 0.0f, 0.30596630545168f,
            0.5f, 0.30596630545168f, 0.0f,
            -0.07293139167538f, 0.0f, 0.01899487526049f,
            0.0f
    };
    private static final float[][] MODULATION_8_REAL = new float[8][LENGTH];
    private static final float[][] MODULATION_8_IMAG = new float[8][LENGTH];
    private static final float[][] MODULATION_2 = new float[2][LENGTH];

    static {
        var offset = (float) (LENGTH - 1) / 2;
        for (var q = 0; q < 8; q++) {
            for (var n = 0; n < LENGTH; n++) {
                MODULATION_8_REAL[q][n] = (float) StrictMath.cos(PI * (n - offset) / 8 * (1 + 2 * q));
                MODULATION_8_IMAG[q][n] = (float) -StrictMath.sin(PI * (n - offset) / 8 * (1 + 2 * q));
            }
        }
        for (var q = 0; q < 2; q++) {
            for (var n = 0; n < LENGTH; n++) {
                MODULATION_2[q][n] = (float) StrictMath.cos(2 * PI * q * (n - offset) / 2);
            }
        }
    }

    private final int slots;
    private final int qmfBands;
    private final float[][] historyReal;
    private final float[][] historyImag;
    private final float[][] tempReal = new float[8][];
    private final float[][] tempImag = new float[8][];

    /**
     * Constructs the filter state of one channel of 64 QMF subbands.
     *
     * @param slots the number of QMF slots per frame
     */
    public MpsHybrid(int slots) {
        this(slots, 64);
    }

    /**
     * Constructs the filter state of one channel.
     *
     * @param slots the number of QMF slots per frame
     * @param qmfBands the number of QMF subbands, 32, 64 or 128
     */
    public MpsHybrid(int slots, int qmfBands) {
        this.slots = slots;
        this.qmfBands = qmfBands;
        this.historyReal = new float[3][LENGTH - 1 + slots];
        this.historyImag = new float[3][LENGTH - 1 + slots];
        for (var q = 0; q < 8; q++) {
            tempReal[q] = new float[slots];
            tempImag[q] = new float[slots];
        }
    }

    /**
     * Splits one frame ({@code SacApplyAnaHybFilterbank}).
     *
     * @param qmfReal the real parts of the QMF slots, {@code [slot][qmfBands]}
     * @param qmfImag the imaginary parts of the QMF slots
     * @param hybridReal receives the real parts of the hybrid slots, {@code [slot][qmfBands + 7]}
     * @param hybridImag receives the imaginary parts of the hybrid slots
     */
    public void analyze(float[][] qmfReal, float[][] qmfImag, float[][] hybridReal, float[][] hybridImag) {
        var keep = LENGTH - 1;
        for (var k = 0; k < 3; k++) {
            System.arraycopy(historyReal[k], slots, historyReal[k], 0, keep);
            System.arraycopy(historyImag[k], slots, historyImag[k], 0, keep);
            for (var n = 0; n < slots; n++) {
                historyReal[k][keep + n] = qmfReal[n][k];
                historyImag[k][keep + n] = qmfImag[n][k];
            }
        }
        filter(historyReal[0], historyImag[0], 8, true, COEFFICIENTS_8);
        for (var n = 0; n < slots; n++) {
            var re = hybridReal[n];
            var im = hybridImag[n];
            re[0] = tempReal[6][n];
            im[0] = tempImag[6][n];
            re[1] = tempReal[7][n];
            im[1] = tempImag[7][n];
            re[2] = tempReal[0][n];
            im[2] = tempImag[0][n];
            re[3] = tempReal[1][n];
            im[3] = tempImag[1][n];
            re[4] = tempReal[2][n];
            im[4] = tempImag[2][n];
            re[4] += tempReal[5][n];
            im[4] += tempImag[5][n];
            re[5] = tempReal[3][n];
            im[5] = tempImag[3][n];
            re[5] += tempReal[4][n];
            im[5] += tempImag[4][n];
        }
        filter(historyReal[1], historyImag[1], 2, false, COEFFICIENTS_2);
        for (var n = 0; n < slots; n++) {
            hybridReal[n][6] = tempReal[1][n];
            hybridImag[n][6] = tempImag[1][n];
            hybridReal[n][7] = tempReal[0][n];
            hybridImag[n][7] = tempImag[0][n];
        }
        filter(historyReal[2], historyImag[2], 2, false, COEFFICIENTS_2);
        for (var n = 0; n < slots; n++) {
            hybridReal[n][8] = tempReal[0][n];
            hybridImag[n][8] = tempImag[0][n];
            hybridReal[n][9] = tempReal[1][n];
            hybridImag[n][9] = tempImag[1][n];
            System.arraycopy(qmfReal[n], 3, hybridReal[n], 10, qmfBands - 3);
            System.arraycopy(qmfImag[n], 3, hybridImag[n], 10, qmfBands - 3);
        }
    }

    private void filter(float[] real, float[] imag, int bands, boolean complex, float[] prototype) {
        for (var i = 0; i < slots; i++) {
            for (var q = 0; q < bands; q++) {
                var re = 0.0f;
                var im = 0.0f;
                for (var n = 0; n < LENGTH; n++) {
                    float modr;
                    float modi;
                    if (complex) {
                        modr = MODULATION_8_REAL[q][n];
                        modi = MODULATION_8_IMAG[q][n];
                    } else {
                        modr = MODULATION_2[q][n];
                        modi = 0.0f;
                    }
                    var p = prototype[LENGTH - 1 - n];
                    re += p * (real[n + i] * modr - imag[n + i] * modi);
                    im += p * (imag[n + i] * modr + real[n + i] * modi);
                }
                tempReal[q][i] = re;
                tempImag[q][i] = im;
            }
        }
    }

    /**
     * Merges hybrid slots into QMF slots ({@code SacApplySynHybFilterbank}).
     *
     * @param hybridReal the real parts of the hybrid slots
     * @param hybridImag the imaginary parts of the hybrid slots
     * @param slots the number of slots
     * @param qmfReal receives the real parts of the QMF slots
     * @param qmfImag receives the imaginary parts of the QMF slots
     */
    public static void synthesize(float[][] hybridReal, float[][] hybridImag, int slots, float[][] qmfReal,
                                  float[][] qmfImag) {
        synthesize(hybridReal, hybridImag, slots, qmfReal, qmfImag, 64);
    }

    /**
     * Merges hybrid slots into QMF slots of any number of subbands ({@code SacApplySynHybFilterbank}).
     *
     * @param hybridReal the real parts of the hybrid slots
     * @param hybridImag the imaginary parts of the hybrid slots
     * @param slots the number of slots
     * @param qmfReal receives the real parts of the QMF slots
     * @param qmfImag receives the imaginary parts of the QMF slots
     * @param qmfBands the number of QMF subbands
     */
    public static void synthesize(float[][] hybridReal, float[][] hybridImag, int slots, float[][] qmfReal,
                                  float[][] qmfImag, int qmfBands) {
        for (var n = 0; n < slots; n++) {
            var hr = hybridReal[n];
            var hi = hybridImag[n];
            var qr = qmfReal[n];
            var qi = qmfImag[n];
            qr[0] = hr[0];
            qi[0] = hi[0];
            for (var k = 1; k < 6; k++) {
                qr[0] += hr[k];
                qi[0] += hi[k];
            }
            qr[1] = hr[6] + hr[7];
            qi[1] = hi[6] + hi[7];
            qr[2] = hr[8] + hr[9];
            qi[2] = hi[8] + hi[9];
            System.arraycopy(hr, 10, qr, 3, qmfBands - 3);
            System.arraycopy(hi, 10, qi, 3, qmfBands - 3);
        }
    }
}
