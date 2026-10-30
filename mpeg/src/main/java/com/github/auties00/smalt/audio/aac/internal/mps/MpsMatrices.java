/* Ports the ISO/IEC 23003-3:2019 reference software MPEG Surround decoder (sac_calcM1andM2.c
   SpatialDecCalculateM1andM2, bufferM1andM2, updateAlpha, CalculateArbDmxMtx, SpatialDecCalculateM1andM2_5151,
   _5152, _5251, _7271, _7272, _7571 and _7572, CalculateTTT, CalculateMTXinv, getMatrixInversionWeights,
   invertMatrix, param2UMX_PS and param2UMX_PS_Core; sac_smoothing.c SpatialDecSmoothM1andM2 and calcFilterCoeff).
   Copyright Agere Systems, Coding Technologies, Fraunhofer IIS, Philips and ISO/IEC 2008; ISO/IEC reference software
   licence (use in products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.mps;

/**
 * Computes the pre-matrix {@code M1} and the mix matrix {@code M2} of every parameter set and band of an MPEG
 * Surround tree (ISO/IEC 23003-1 subclause 6.5.3), and smooths them in time.
 *
 * <p>Every matrix is stored as 8 by 8 entries, {@code row * 8 + column}; entries a tree does not set keep their
 * previous values, as in the reference decoder. Instances are not thread-safe.
 */
final class MpsMatrices {
    static final int STRIDE = 8;
    private static final int SIZE = STRIDE * STRIDE;
    private static final int MAX_SETS = MpsParameters.MAX_SETS + 1;
    private static final int MAX_BANDS = MpsParameters.MAX_BANDS;
    private static final int H11 = 0;
    private static final int H12 = 1;
    private static final int H21 = 2;
    private static final int H22 = 3;
    private static final int H12_RES = 4;
    private static final int H22_RES = 5;
    private static final int C_L = 6;
    private static final int C_R = 7;

    private final MpsParameters parameters;
    private final int bands;
    private final int inputs;
    final int xChannels;
    private final boolean matrixMode;
    private final int arbitraryDownmix;

    final float[][][] m1Real = new float[MAX_SETS][MAX_BANDS][SIZE];
    final float[][][] m1Imag = new float[MAX_SETS][MAX_BANDS][SIZE];
    final float[][][] m2Decor = new float[MAX_SETS][MAX_BANDS][SIZE];
    final float[][][] m2Resid = new float[MAX_SETS][MAX_BANDS][SIZE];
    final float[][] m1RealPrevious = new float[MAX_BANDS][SIZE];
    final float[][] m1ImagPrevious = new float[MAX_BANDS][SIZE];
    final float[][] m2DecorPrevious = new float[MAX_BANDS][SIZE];
    final float[][] m2ResidPrevious = new float[MAX_BANDS][SIZE];

    private final float[][][] boxes = new float[5][8][MAX_BANDS];
    private final float[] cLfeLeft = new float[MAX_BANDS];
    private final float[] cLfeRight = new float[MAX_BANDS];
    private final float[] iidLinear = new float[MAX_BANDS];
    private final float[] kappa = new float[MAX_BANDS];
    private final float[] decorrelatedGain = new float[MAX_BANDS];
    private final float[] alpha = new float[6];
    private final float[] alphaPrevious = new float[6];
    private final float[] gains = new float[6];
    private final float[][] pre = new float[3][5];
    private final float[][] ttt = new float[3][3];
    private final float[][] inverseReal = new float[2][2];
    private final float[][] inverseImag = new float[2][2];
    private final float[] weights = new float[2];

    /**
     * Constructs the matrices of a stream.
     *
     * @param parameters the stream's mapped parameters
     * @param xChannels the number of columns of {@code M1}
     */
    MpsMatrices(MpsParameters parameters, int xChannels) {
        this.parameters = parameters;
        this.bands = parameters.bands;
        this.inputs = parameters.inputs;
        this.xChannels = xChannels;
        this.matrixMode = parameters.config.matrixMode;
        this.arbitraryDownmix = parameters.config.arbitraryDownmix;
    }

    /**
     * Keeps the last matrices of the previous frame and computes the matrices of the current one
     * ({@code SpatialDecCalculateM1andM2}, {@code SpatialDecSmoothM1andM2}).
     */
    void calculate() {
        var previous = parameters.setsPrevious - 1;
        for (var pb = 0; pb < bands; pb++) {
            System.arraycopy(m1Real[previous][pb], 0, m1RealPrevious[pb], 0, SIZE);
            System.arraycopy(m1Imag[previous][pb], 0, m1ImagPrevious[pb], 0, SIZE);
            System.arraycopy(m2Decor[previous][pb], 0, m2DecorPrevious[pb], 0, SIZE);
            System.arraycopy(m2Resid[previous][pb], 0, m2ResidPrevious[pb], 0, SIZE);
        }
        if (arbitraryDownmix != 0) {
            updateAlpha();
        }
        switch (parameters.tree) {
            case MpsConfig.TREE_5151 -> calculate5151();
            case MpsConfig.TREE_5152 -> calculate5152();
            case MpsConfig.TREE_525 -> calculate525();
            case MpsConfig.TREE_7271 -> calculate7271();
            case MpsConfig.TREE_7272 -> calculate7272();
            case MpsConfig.TREE_7571 -> calculate7571();
            default -> calculate7572();
        }
        smooth();
    }

    private void updateAlpha() {
        for (var ch = 0; ch < inputs; ch++) {
            alphaPrevious[ch] = alpha[ch];
            float value;
            if (arbitraryDownmix == 2) {
                value = alpha[ch];
                if (parameters.downmixResidualAbs[ch]) {
                    value -= 0.33f;
                    if (value < 0.0f) {
                        value = 0.0f;
                    }
                } else {
                    value += 0.33f;
                    if (value > 1.0f) {
                        value = 1.0f;
                    }
                }
            } else {
                value = 1.0f;
            }
            alpha[ch] = value;
        }
    }

    private void downmixGains(int ps, int pb) {
        for (var ch = 0; ch < inputs; ch++) {
            var gain = (float) StrictMath.pow(10.0f, parameters.downmixGain[ch][ps][pb] / 20.0f);
            if (pb < parameters.downmixResidualBands) {
                if (ps == 0 && parameters.downmixAlphaUpdate[ch]) {
                    gains[ch] = alphaPrevious[ch] * gain;
                } else {
                    gains[ch] = alpha[ch] * gain;
                }
            } else {
                gains[ch] = gain;
            }
        }
    }

    /**
     * Computes the upmix of one OTT box ({@code param2UMX_PS}, {@code param2UMX_PS_Core}).
     */
    private void box(int box, int ps, int resBands, float[][] out) {
        var cld = parameters.cld[box][ps];
        var icc = parameters.icc[box][ps];
        var count = parameters.ottBands[box];
        var maxInverseDeterminant = 1.2f;
        for (var band = 0; band < count; band++) {
            iidLinear[band] = (float) StrictMath.pow(10, cld[band] / 20.0f);
            var iid = iidLinear[band];
            out[C_L][band] = (float) Math.sqrt((iid * iid) / (1 + (iid * iid)));
            out[C_R][band] = (float) Math.sqrt(1 / (1 + (iid * iid)));
        }
        for (var band = 0; band < count; band++) {
            var cl = out[C_L][band];
            var cr = out[C_R][band];
            if (band < resBands) {
                var limit = Math.max(icc[band], (1 / (maxInverseDeterminant * maxInverseDeterminant) - 1.0f)
                        * (iidLinear[band] + 1.0f / iidLinear[band]) / 2.0f);
                var a = (float) (0.5f * StrictMath.acos(limit));
                var b = (float) StrictMath.atan(StrictMath.tan(a) * (cr - cl) / (cr + cl));
                out[H11][band] = (float) (cl * StrictMath.cos(a + b));
                out[H21][band] = (float) (cr * StrictMath.cos(-a + b));
                out[H12][band] = 0;
                out[H22][band] = 0;
                out[H12_RES][band] = 1;
                out[H22_RES][band] = -1;
            } else {
                var a = (float) (0.5 * StrictMath.acos(icc[band]));
                var b = (float) StrictMath.atan(StrictMath.tan(a) * (cr - cl) / (cr + cl));
                out[H11][band] = (float) (cl * StrictMath.cos(a + b));
                out[H21][band] = (float) (cr * StrictMath.cos(-a + b));
                out[H12][band] = (float) (cl * StrictMath.sin(a + b));
                out[H22][band] = (float) (cr * StrictMath.sin(-a + b));
                out[H12_RES][band] = 0;
                out[H22_RES][band] = 0;
            }
        }
        for (var band = count; band < bands; band++) {
            out[H11][band] = 0;
            out[H21][band] = 0;
            out[H12][band] = 0;
            out[H22][band] = 0;
            out[H12_RES][band] = 0;
            out[H22_RES][band] = 0;
        }
    }

    private void lfe(int box, int ps) {
        var count = parameters.ottBands[box];
        var cld = parameters.cld[box][ps];
        for (var pb = 0; pb < count; pb++) {
            var iid = (float) StrictMath.pow(10, cld[pb] / 20.0f);
            cLfeLeft[pb] = (float) Math.sqrt(iid * iid / (1 + iid * iid));
            cLfeRight[pb] = (float) Math.sqrt(1 / (1 + iid * iid));
        }
        for (var pb = count; pb < bands; pb++) {
            cLfeLeft[pb] = 1;
            cLfeRight[pb] = 0;
        }
    }

    private static void set(float[] matrix, int row, int column, float value) {
        matrix[row * STRIDE + column] = value;
    }

    private static float get(float[] matrix, int row, int column) {
        return matrix[row * STRIDE + column];
    }

    private void calculate5151() {
        var res = parameters.residualBands;
        var fs = boxes[0];
        var c = boxes[1];
        var s = boxes[2];
        var f = boxes[3];
        for (var ps = 0; ps < parameters.sets; ps++) {
            box(0, ps, res[0], fs);
            box(1, ps, res[1], c);
            box(2, ps, res[2], s);
            box(3, ps, res[3], f);
            lfe(4, ps);
            for (var pb = 0; pb < bands; pb++) {
                var m1r = m1Real[ps][pb];
                var m1i = m1Imag[ps][pb];
                set(m1r, 0, 0, 1);
                set(m1r, 1, 0, 1);
                set(m1r, 2, 0, fs[C_L][pb]);
                set(m1r, 3, 0, fs[C_L][pb] * c[C_L][pb]);
                set(m1r, 4, 0, fs[C_R][pb]);
                for (var row = 0; row < 5; row++) {
                    set(m1i, row, 0, 0);
                }
            }
            for (var pb = 0; pb < bands; pb++) {
                var d = m2Decor[ps][pb];
                set(d, 0, 0, f[H11][pb] * c[H11][pb] * fs[H11][pb]);
                set(d, 0, 1, f[H11][pb] * c[H11][pb] * fs[H12][pb]);
                set(d, 0, 2, f[H11][pb] * c[H12][pb]);
                set(d, 0, 3, f[H12][pb]);
                set(d, 0, 4, 0);
                set(d, 1, 0, f[H21][pb] * c[H11][pb] * fs[H11][pb]);
                set(d, 1, 1, f[H21][pb] * c[H11][pb] * fs[H12][pb]);
                set(d, 1, 2, f[H21][pb] * c[H12][pb]);
                set(d, 1, 3, f[H22][pb]);
                set(d, 1, 4, 0);
                set(d, 2, 0, cLfeLeft[pb] * c[H21][pb] * fs[H11][pb]);
                set(d, 2, 1, cLfeLeft[pb] * c[H21][pb] * fs[H12][pb]);
                set(d, 2, 2, cLfeLeft[pb] * c[H22][pb]);
                set(d, 2, 3, 0);
                set(d, 2, 4, 0);
                set(d, 3, 0, fs[C_L][pb] * c[C_R][pb] * cLfeRight[pb]);
                set(d, 3, 1, 0);
                set(d, 3, 2, 0);
                set(d, 3, 3, 0);
                set(d, 3, 4, 0);
                set(d, 4, 0, s[H11][pb] * fs[H21][pb]);
                set(d, 4, 1, s[H11][pb] * fs[H22][pb]);
                set(d, 4, 2, 0);
                set(d, 4, 3, 0);
                set(d, 4, 4, s[H12][pb]);
                set(d, 5, 0, s[H21][pb] * fs[H21][pb]);
                set(d, 5, 1, s[H21][pb] * fs[H22][pb]);
                set(d, 5, 2, 0);
                set(d, 5, 3, 0);
                set(d, 5, 4, s[H22][pb]);
            }
            for (var pb = 0; pb < bands; pb++) {
                var r = m2Resid[ps][pb];
                set(r, 0, 0, f[H11][pb] * c[H11][pb] * fs[H11][pb]);
                set(r, 0, 1, f[H11][pb] * c[H11][pb] * fs[H12_RES][pb]);
                set(r, 0, 2, f[H11][pb] * c[H12_RES][pb]);
                set(r, 0, 3, f[H12_RES][pb]);
                set(r, 0, 4, 0);
                set(r, 1, 0, f[H21][pb] * c[H11][pb] * fs[H11][pb]);
                set(r, 1, 1, f[H21][pb] * c[H11][pb] * fs[H12_RES][pb]);
                set(r, 1, 2, f[H21][pb] * c[H12_RES][pb]);
                set(r, 1, 3, f[H22_RES][pb]);
                set(r, 1, 4, 0);
                set(r, 2, 0, cLfeLeft[pb] * c[H21][pb] * fs[H11][pb]);
                set(r, 2, 1, cLfeLeft[pb] * c[H21][pb] * fs[H12_RES][pb]);
                set(r, 2, 2, cLfeLeft[pb] * c[H22_RES][pb]);
                set(r, 2, 3, 0);
                set(r, 2, 4, 0);
                set(r, 3, 0, cLfeRight[pb] * c[C_R][pb] * fs[C_L][pb]);
                set(r, 3, 1, 0);
                set(r, 3, 2, 0);
                set(r, 3, 3, 0);
                set(r, 3, 4, 0);
                set(r, 4, 0, s[H11][pb] * fs[H21][pb]);
                set(r, 4, 1, s[H11][pb] * fs[H22_RES][pb]);
                set(r, 4, 2, 0);
                set(r, 4, 3, 0);
                set(r, 4, 4, s[H12_RES][pb]);
                set(r, 5, 0, s[H21][pb] * fs[H21][pb]);
                set(r, 5, 1, s[H21][pb] * fs[H22_RES][pb]);
                set(r, 5, 2, 0);
                set(r, 5, 3, 0);
                set(r, 5, 4, s[H22_RES][pb]);
            }
        }
        applyMonoDownmix();
    }

    private void calculate5152() {
        var res = parameters.residualBands;
        var c = boxes[0];
        var lr = boxes[1];
        var l = boxes[3];
        var r = boxes[4];
        for (var ps = 0; ps < parameters.sets; ps++) {
            box(0, ps, res[0], c);
            box(1, ps, res[1], lr);
            box(3, ps, res[3], l);
            box(4, ps, res[4], r);
            lfe(2, ps);
            for (var pb = 0; pb < bands; pb++) {
                var m1r = m1Real[ps][pb];
                var m1i = m1Imag[ps][pb];
                set(m1r, 0, 0, 1);
                set(m1r, 1, 0, 1);
                set(m1r, 2, 0, c[C_L][pb]);
                set(m1r, 3, 0, c[C_L][pb] * lr[C_L][pb]);
                set(m1r, 4, 0, c[C_L][pb] * lr[C_R][pb]);
                for (var row = 0; row < 5; row++) {
                    set(m1i, row, 0, 0);
                }
            }
            for (var pb = 0; pb < bands; pb++) {
                var d = m2Decor[ps][pb];
                set(d, 0, 0, l[H11][pb] * lr[H11][pb] * c[H11][pb]);
                set(d, 0, 1, l[H11][pb] * lr[H11][pb] * c[H12][pb]);
                set(d, 0, 2, l[H11][pb] * lr[H12][pb]);
                set(d, 0, 3, l[H12][pb]);
                set(d, 0, 4, 0);
                set(d, 1, 0, l[H21][pb] * lr[H11][pb] * c[H11][pb]);
                set(d, 1, 1, l[H21][pb] * lr[H11][pb] * c[H12][pb]);
                set(d, 1, 2, l[H21][pb] * lr[H12][pb]);
                set(d, 1, 3, l[H22][pb]);
                set(d, 1, 4, 0);
                set(d, 2, 0, r[H11][pb] * lr[H21][pb] * c[H11][pb]);
                set(d, 2, 1, r[H11][pb] * lr[H21][pb] * c[H12][pb]);
                set(d, 2, 2, r[H11][pb] * lr[H22][pb]);
                set(d, 2, 3, 0);
                set(d, 2, 4, r[H12][pb]);
                set(d, 3, 0, r[H21][pb] * lr[H21][pb] * c[H11][pb]);
                set(d, 3, 1, r[H21][pb] * lr[H21][pb] * c[H12][pb]);
                set(d, 3, 2, r[H21][pb] * lr[H22][pb]);
                set(d, 3, 3, 0);
                set(d, 3, 4, r[H22][pb]);
                set(d, 4, 0, cLfeLeft[pb] * c[H21][pb]);
                set(d, 4, 1, cLfeLeft[pb] * c[H22][pb]);
                set(d, 4, 2, 0);
                set(d, 4, 3, 0);
                set(d, 4, 4, 0);
                set(d, 5, 0, c[C_R][pb] * cLfeRight[pb]);
                set(d, 5, 1, 0);
                set(d, 5, 2, 0);
                set(d, 5, 3, 0);
                set(d, 5, 4, 0);
            }
            for (var pb = 0; pb < bands; pb++) {
                var m = m2Resid[ps][pb];
                set(m, 0, 0, l[H11][pb] * lr[H11][pb] * c[H11][pb]);
                set(m, 0, 1, l[H11][pb] * lr[H11][pb] * c[H12_RES][pb]);
                set(m, 0, 2, l[H11][pb] * lr[H12_RES][pb]);
                set(m, 0, 3, l[H12_RES][pb]);
                set(m, 0, 4, 0);
                set(m, 1, 0, l[H21][pb] * lr[H11][pb] * c[H11][pb]);
                set(m, 1, 1, l[H21][pb] * lr[H11][pb] * c[H12_RES][pb]);
                set(m, 1, 2, l[H21][pb] * lr[H12_RES][pb]);
                set(m, 1, 3, l[H22_RES][pb]);
                set(m, 1, 4, 0);
                set(m, 2, 0, r[H11][pb] * lr[H21][pb] * c[H11][pb]);
                set(m, 2, 1, r[H11][pb] * lr[H21][pb] * c[H12_RES][pb]);
                set(m, 2, 2, r[H11][pb] * lr[H22_RES][pb]);
                set(m, 2, 3, 0);
                set(m, 2, 4, r[H12_RES][pb]);
                set(m, 3, 0, r[H21][pb] * lr[H21][pb] * c[H11][pb]);
                set(m, 3, 1, r[H21][pb] * lr[H21][pb] * c[H12_RES][pb]);
                set(m, 3, 2, r[H21][pb] * lr[H22_RES][pb]);
                set(m, 3, 3, 0);
                set(m, 3, 4, r[H22_RES][pb]);
                set(m, 4, 0, cLfeLeft[pb] * c[H21][pb]);
                set(m, 4, 1, cLfeLeft[pb] * c[H22_RES][pb]);
                set(m, 4, 2, 0);
                set(m, 4, 3, 0);
                set(m, 4, 4, 0);
                set(m, 5, 0, c[C_R][pb] * cLfeRight[pb]);
                set(m, 5, 1, 0);
                set(m, 5, 2, 0);
                set(m, 5, 3, 0);
                set(m, 5, 4, 0);
            }
        }
        applyMonoDownmix();
    }

    /**
     * Applies the arbitrary downmix gains to the pre-matrix of a mono downmix.
     */
    private void applyMonoDownmix() {
        if (arbitraryDownmix == 0) {
            return;
        }
        for (var ps = 0; ps < parameters.sets; ps++) {
            for (var pb = 0; pb < bands; pb++) {
                downmixGains(ps, pb);
                var m1r = m1Real[ps][pb];
                var m1i = m1Imag[ps][pb];
                if (arbitraryDownmix == 2) {
                    for (var row = 0; row < 5; row++) {
                        set(m1r, row, 1, get(m1r, row, 0));
                        set(m1i, row, 1, get(m1i, row, 0));
                    }
                }
                for (var row = 0; row < 5; row++) {
                    set(m1r, row, 0, get(m1r, row, 0) * gains[0]);
                }
            }
        }
    }

    /**
     * Computes the TTT box matrix ({@code CalculateTTT}).
     */
    private void tttMatrix(int ps, int pb, int mode) {
        if (mode < 2) {
            var c1 = parameters.cpc1[ps][pb];
            var c2 = parameters.cpc2[ps][pb];
            ttt[0][0] = c1 + 2;
            ttt[0][1] = c2 - 1;
            ttt[1][0] = c1 - 1;
            ttt[1][1] = c2 + 2;
            ttt[2][0] = 1 - c1;
            ttt[2][1] = 1 - c2;
            if (pb >= parameters.residualBands[3]) {
                var icc = parameters.tttIcc[ps][pb];
                ttt[0][0] /= icc;
                ttt[0][1] /= icc;
                ttt[1][0] /= icc;
                ttt[1][1] /= icc;
                ttt[2][0] /= icc;
                ttt[2][1] /= icc;
            }
        } else {
            var q = 1.0f;
            var c1d = (float) StrictMath.pow(10, parameters.tttCld1[ps][pb] / 10.0f);
            var c2d = (float) StrictMath.pow(10, parameters.tttCld2[ps][pb] / 10.0f);
            ttt[0][0] = (float) (3 * Math.sqrt(c1d * c2d / (c2d + 1 + c1d * c2d)));
            ttt[0][1] = 0;
            ttt[1][0] = 0;
            ttt[1][1] = (float) (3 * Math.sqrt(c1d / (c1d + c2d + 1)));
            ttt[2][0] = (float) (3 * 0.5 * Math.sqrt((c2d + 1) / (c2d + 1 + c1d * c2d)));
            ttt[2][1] = (float) (3 * 0.5 * Math.sqrt((c2d + 1) / (c1d + c2d + 1)));
            if (mode == 2 || mode == 3) {
                var center = 1 / (1 + c1d);
                var right = center * c1d / (1 + c2d);
                var left = c2d * right;
                var wl1 = (float) Math.sqrt((right + q * q * center) / (right + q * q * center * (1 + right / left)));
                var wl2 = -q * q * center * wl1 / (right + q * q * center);
                var wr1 = (float) Math.sqrt((left + q * q * center) / (left + q * q * center * (1 + left / right)));
                var wr2 = -q * q * center * wr1 / (left + q * q * center);
                ttt[0][0] = 3 * wl1;
                ttt[0][1] = 3 * wl2;
                ttt[1][0] = 3 * wr2;
                ttt[1][1] = 3 * wr1;
            }
        }
        ttt[0][2] = 1;
        ttt[1][2] = 1;
        ttt[2][2] = -1;
        for (var row = 0; row < 3; row++) {
            for (var col = 0; col < 3; col++) {
                ttt[row][col] *= (1.0f / 3.0f);
            }
        }
        for (var col = 0; col < 3; col++) {
            ttt[2][col] *= (float) Math.sqrt(2);
        }
    }

    /**
     * Computes the matrix inversion of a matrix compatible stereo downmix ({@code CalculateMTXinv},
     * {@code getMatrixInversionWeights}, {@code invertMatrix}).
     */
    private void inverse(int ps, int pb, int mode) {
        var iidLeft = parameters.cld[1][ps][pb];
        var iidRight = parameters.cld[2][ps][pb];
        float c1;
        float c2;
        if (mode < 2) {
            c1 = parameters.cpc1[ps][pb];
            c2 = parameters.cpc2[ps][pb];
        } else {
            c1 = parameters.tttCld1[ps][pb];
            c2 = parameters.tttCld2[ps][pb];
        }
        var w1 = (float) StrictMath.pow(10.0f, -iidLeft / 20.0f);
        var w2 = (float) StrictMath.pow(10.0f, -iidRight / 20.0f);
        if (mode < 2) {
            c1 = predictionWeight(c1);
            c2 = predictionWeight(c2);
        } else {
            var c1p = (float) StrictMath.pow(10.0f, c1 / 10.0f);
            var c2p = (float) StrictMath.pow(10.0f, c2 / 10.0f);
            c1 = (float) Math.sqrt((c1p * c2p) / (c1p * c2p + 2.0f * (c2p + 1)));
            c2 = (float) Math.sqrt(c1p / (c1p + 2.0f * (c2p + 1)));
        }
        var weight1 = c1 * w1 / (1.0f + w1);
        var weight2 = c2 * w2 / (1.0f + w2);
        var len1 = (float) Math.sqrt(1.0f - 2.0f * weight1 + 2.0f * weight1 * weight1);
        var len2 = (float) Math.sqrt(1.0f - 2.0f * weight2 + 2.0f * weight2 * weight2);
        var h11Real = (1.0f - weight1) / len1;
        var h11Imag = weight1 / len1;
        var h12Real = 0.0f;
        var h12Imag = (float) ((1.0f / Math.sqrt(3.0f)) * (weight2 / len2));
        var h21Real = 0.0f;
        var h21Imag = (float) ((-1.0f / Math.sqrt(3.0f)) * (weight1 / len1));
        var h22Real = (1.0f - weight2) / len2;
        var h22Imag = -weight2 / len2;
        var normReal = (h11Real * h22Real - h11Imag * h22Imag) - (h12Real * h21Real - h12Imag * h21Imag);
        var normImag = (h11Real * h22Imag + h11Imag * h22Real) - (h12Real * h21Imag + h12Imag * h21Real);
        var norm = normReal * normReal + normImag * normImag;
        normReal = normReal / norm;
        normImag = -normImag / norm;
        inverseReal[0][0] = (h22Real * normReal - h22Imag * normImag);
        inverseImag[0][0] = (h22Real * normImag + h22Imag * normReal);
        inverseReal[0][1] = -(h12Real * normReal - h12Imag * normImag);
        inverseImag[0][1] = -(h12Real * normImag + h12Imag * normReal);
        inverseReal[1][0] = -(h21Real * normReal - h21Imag * normImag);
        inverseImag[1][0] = -(h21Real * normImag + h21Imag * normReal);
        inverseReal[1][1] = (h11Real * normReal - h11Imag * normImag);
        inverseImag[1][1] = (h11Real * normImag + h11Imag * normReal);
    }

    private static float predictionWeight(float c) {
        if (Math.abs(c) >= 1.0f) {
            return 1.0f;
        }
        if (c < -0.5f && c > -1.0f) {
            return -1.0f - 2.0f * c;
        }
        return 1.0f / 3.0f + 2.0f / 3.0f * c;
    }

    /**
     * Computes the pre-matrix rows of the TTT box of a stereo downmix for one band, with the arbitrary downmix gains
     * and the matrix inversion; the first three rows of {@code M1} get the box outputs and the fourth and fifth rows
     * copies of the first two.
     */
    private void stereoPreMatrix(int ps, int pb, int part) {
        var mode = parameters.tttMode[part];
        var inversion = matrixMode;
        if (mode >= 2) {
            inversion = inversion && (mode == 2 || mode == 4);
        }
        for (var row = 0; row < 3; row++) {
            for (var col = 0; col < 5; col++) {
                pre[row][col] = 0;
            }
        }
        tttMatrix(ps, pb, mode);
        for (var row = 0; row < 3; row++) {
            System.arraycopy(ttt[row], 0, pre[row], 0, 3);
        }
        if (arbitraryDownmix != 0) {
            downmixGains(ps, pb);
            for (var ch = 0; ch < inputs; ch++) {
                for (var row = 0; row < 3; row++) {
                    pre[row][ch] *= gains[ch];
                    if (arbitraryDownmix == 2 && pb < parameters.downmixResidualBands) {
                        pre[row][3 + ch] = ttt[row][ch];
                    }
                }
            }
        }
        var m1r = m1Real[ps][pb];
        var m1i = m1Imag[ps][pb];
        if (inversion) {
            inverse(ps, pb, mode);
            for (var row = 0; row < 3; row++) {
                set(m1r, row, 0, pre[row][0] * inverseReal[0][0] + pre[row][1] * inverseReal[1][0]);
                set(m1r, row, 1, pre[row][0] * inverseReal[0][1] + pre[row][1] * inverseReal[1][1]);
            }
            for (var row = 0; row < 3; row++) {
                set(m1i, row, 0, pre[row][0] * inverseImag[0][0] + pre[row][1] * inverseImag[1][0]);
                set(m1i, row, 1, pre[row][0] * inverseImag[0][1] + pre[row][1] * inverseImag[1][1]);
            }
        } else {
            for (var row = 0; row < 3; row++) {
                set(m1r, row, 0, pre[row][0]);
                set(m1r, row, 1, pre[row][1]);
            }
            for (var row = 0; row < 3; row++) {
                set(m1i, row, 0, 0);
                set(m1i, row, 1, 0);
            }
        }
        for (var row = 0; row < 3; row++) {
            set(m1r, row, 2, pre[row][2]);
            set(m1r, row, 3, pre[row][3]);
            set(m1r, row, 4, pre[row][4]);
        }
        for (var col = 0; col < xChannels; col++) {
            set(m1r, 3, col, get(m1r, 0, col));
            set(m1i, 3, col, get(m1i, 0, col));
            set(m1r, 4, col, get(m1r, 1, col));
            set(m1i, 4, col, get(m1i, 1, col));
        }
    }

    private void calculate525() {
        var res = parameters.residualBands;
        var l = boxes[1];
        var r = boxes[2];
        for (var ps = 0; ps < parameters.sets; ps++) {
            for (var part = 0; part < 2; part++) {
                for (var pb = parameters.tttStart[part]; pb < parameters.tttStop[part]; pb++) {
                    stereoPreMatrix(ps, pb, part);
                    if (parameters.tttDecorrelated[part]) {
                        var m1r = m1Real[ps][pb];
                        var m1i = m1Imag[ps][pb];
                        for (var col = 0; col < xChannels; col++) {
                            set(m1r, 5, col, get(m1r, 2, col));
                            set(m1i, 5, col, get(m1i, 2, col));
                        }
                    }
                }
            }
            box(1, ps, res[1], l);
            box(2, ps, res[2], r);
            lfe(0, ps);
            for (var pb = 0; pb < bands; pb++) {
                var d = m2Decor[ps][pb];
                set(d, 0, 0, l[H11][pb]);
                set(d, 0, 1, 0);
                set(d, 0, 2, 0);
                set(d, 0, 3, l[H12][pb]);
                set(d, 0, 4, 0);
                set(d, 1, 0, l[H21][pb]);
                set(d, 1, 1, 0);
                set(d, 1, 2, 0);
                set(d, 1, 3, l[H22][pb]);
                set(d, 1, 4, 0);
                set(d, 2, 0, 0);
                set(d, 2, 1, r[H11][pb]);
                set(d, 2, 2, 0);
                set(d, 2, 3, 0);
                set(d, 2, 4, r[H12][pb]);
                set(d, 3, 0, 0);
                set(d, 3, 1, r[H21][pb]);
                set(d, 3, 2, 0);
                set(d, 3, 3, 0);
                set(d, 3, 4, r[H22][pb]);
                set(d, 4, 0, 0);
                set(d, 4, 1, 0);
                set(d, 4, 2, cLfeLeft[pb]);
                set(d, 4, 3, 0);
                set(d, 4, 4, 0);
                set(d, 5, 0, 0);
                set(d, 5, 1, 0);
                set(d, 5, 2, cLfeRight[pb]);
                set(d, 5, 3, 0);
                set(d, 5, 4, 0);
            }
            for (var pb = 0; pb < bands; pb++) {
                var m = m2Resid[ps][pb];
                set(m, 0, 0, l[H11][pb]);
                set(m, 0, 1, 0);
                set(m, 0, 2, 0);
                set(m, 0, 3, l[H12_RES][pb]);
                set(m, 0, 4, 0);
                set(m, 1, 0, l[H21][pb]);
                set(m, 1, 1, 0);
                set(m, 1, 2, 0);
                set(m, 1, 3, l[H22_RES][pb]);
                set(m, 1, 4, 0);
                set(m, 2, 0, 0);
                set(m, 2, 1, r[H11][pb]);
                set(m, 2, 2, 0);
                set(m, 2, 3, 0);
                set(m, 2, 4, r[H12_RES][pb]);
                set(m, 3, 0, 0);
                set(m, 3, 1, r[H21][pb]);
                set(m, 3, 2, 0);
                set(m, 3, 3, 0);
                set(m, 3, 4, r[H22_RES][pb]);
                set(m, 4, 0, 0);
                set(m, 4, 1, 0);
                set(m, 4, 2, cLfeLeft[pb]);
                set(m, 4, 3, 0);
                set(m, 4, 4, 0);
                set(m, 5, 0, 0);
                set(m, 5, 1, 0);
                set(m, 5, 2, cLfeRight[pb]);
                set(m, 5, 3, 0);
                set(m, 5, 4, 0);
            }
            for (var part = 0; part < 2; part++) {
                if (!parameters.tttDecorrelated[part]) {
                    continue;
                }
                for (var pb = parameters.tttStart[part]; pb < parameters.tttStop[part]; pb++) {
                    for (var row = 0; row < 6; row++) {
                        set(m2Resid[ps][pb], row, 5, 0);
                        set(m2Decor[ps][pb], row, 5, 0);
                    }
                }
            }
            decorrelatedGains(ps);
            for (var part = 0; part < 2; part++) {
                for (var pb = parameters.tttStart[part]; pb < parameters.tttStop[part]; pb++) {
                    if (parameters.tttMode[part] < 2 && pb >= res[3] && parameters.tttDecorrelated[part]) {
                        var d = m2Decor[ps][pb];
                        var m = m2Resid[ps][pb];
                        set(d, 0, 0, get(d, 0, 0) * kappa[pb]);
                        set(d, 2, 1, get(d, 2, 1) * kappa[pb]);
                        set(d, 4, 2, get(d, 4, 2) * kappa[pb]);
                        set(d, 0, 5, decorrelatedGain[pb] * l[C_L][pb]);
                        set(d, 2, 5, decorrelatedGain[pb] * r[C_L][pb]);
                        set(d, 4, 5, (float) (-Math.sqrt(2) * decorrelatedGain[pb] * cLfeLeft[pb]));
                        set(m, 0, 0, get(m, 0, 0) * kappa[pb]);
                        set(m, 2, 1, get(m, 2, 1) * kappa[pb]);
                        set(m, 4, 2, get(m, 4, 2) * kappa[pb]);
                        set(m, 0, 5, decorrelatedGain[pb] * l[C_L][pb]);
                        set(m, 2, 5, decorrelatedGain[pb] * r[C_L][pb]);
                        set(m, 4, 5, (float) (-Math.sqrt(2) * decorrelatedGain[pb] * cLfeLeft[pb]));
                    }
                }
            }
        }
    }

    private void decorrelatedGains(int ps) {
        for (var pb = 0; pb < bands; pb++) {
            kappa[pb] = parameters.tttIcc[ps][pb];
            decorrelatedGain[pb] = (float) (Math.sqrt(1 - kappa[pb] * kappa[pb]) / Math.sqrt(3));
        }
    }

    /**
     * Computes the pre-matrix of the 7-2-7 trees for one parameter set: the TTT rows, their copies and the two rows
     * scaled by the front gains of the left and right boxes.
     */
    private void stereoPreMatrix7(int ps, float[] leftGain, float[] rightGain) {
        for (var part = 0; part < 2; part++) {
            for (var pb = parameters.tttStart[part]; pb < parameters.tttStop[part]; pb++) {
                stereoPreMatrix(ps, pb, part);
                var m1r = m1Real[ps][pb];
                var m1i = m1Imag[ps][pb];
                for (var col = 0; col < xChannels; col++) {
                    if (parameters.tttDecorrelated[part]) {
                        set(m1r, 5, col, get(m1r, 2, col));
                        set(m1i, 5, col, get(m1i, 2, col));
                    } else {
                        set(m1r, 5, col, 0);
                        set(m1i, 5, col, 0);
                    }
                    set(m1r, 6, col, leftGain[pb] * get(m1r, 0, col));
                    set(m1i, 6, col, leftGain[pb] * get(m1i, 0, col));
                    set(m1r, 7, col, rightGain[pb] * get(m1r, 1, col));
                    set(m1i, 7, col, rightGain[pb] * get(m1i, 1, col));
                }
            }
        }
    }

    private void calculate7271() {
        var res = parameters.residualBands;
        var l = boxes[1];
        var r = boxes[2];
        var lc = boxes[3];
        var rc = boxes[4];
        for (var ps = 0; ps < parameters.sets; ps++) {
            box(1, ps, res[1], l);
            box(2, ps, res[2], r);
            box(3, ps, res[3], lc);
            box(4, ps, res[4], rc);
            stereoPreMatrix7(ps, l[C_L], r[C_L]);
            lfe(0, ps);
            for (var pb = 0; pb < bands; pb++) {
                var d = m2Decor[ps][pb];
                set(d, 0, 3, lc[H11][pb] * l[H12][pb]);
                set(d, 0, 4, 0);
                set(d, 0, 6, lc[H12][pb]);
                set(d, 0, 7, 0);
                set(d, 1, 3, lc[H21][pb] * l[H12][pb]);
                set(d, 1, 4, 0);
                set(d, 1, 6, lc[H22][pb]);
                set(d, 1, 7, 0);
                set(d, 2, 3, l[H22][pb]);
                set(d, 2, 4, 0);
                set(d, 2, 6, 0);
                set(d, 2, 7, 0);
                set(d, 3, 3, 0);
                set(d, 3, 4, rc[H11][pb] * r[H12][pb]);
                set(d, 3, 6, 0);
                set(d, 3, 7, rc[H12][pb]);
                set(d, 4, 3, 0);
                set(d, 4, 4, rc[H21][pb] * r[H12][pb]);
                set(d, 4, 6, 0);
                set(d, 4, 7, rc[H22][pb]);
                set(d, 5, 3, 0);
                set(d, 5, 4, r[H22][pb]);
                set(d, 5, 6, 0);
                set(d, 5, 7, 0);
                for (var row = 6; row < 8; row++) {
                    set(d, row, 3, 0);
                    set(d, row, 4, 0);
                    set(d, row, 6, 0);
                    set(d, row, 7, 0);
                }
                for (var row = 0; row < 8; row++) {
                    set(d, row, 0, 0);
                    set(d, row, 1, 0);
                    set(d, row, 2, 0);
                    set(d, row, 5, 0);
                }
            }
            for (var pb = 0; pb < bands; pb++) {
                var m = m2Resid[ps][pb];
                set(m, 0, 0, lc[H11][pb] * l[H11][pb]);
                set(m, 0, 1, 0);
                set(m, 0, 2, 0);
                set(m, 0, 3, lc[H11][pb] * l[H12_RES][pb]);
                set(m, 0, 4, 0);
                set(m, 0, 6, lc[H12_RES][pb]);
                set(m, 0, 7, 0);
                set(m, 1, 0, lc[H21][pb] * l[H11][pb]);
                set(m, 1, 1, 0);
                set(m, 1, 2, 0);
                set(m, 1, 3, lc[H21][pb] * l[H12_RES][pb]);
                set(m, 1, 4, 0);
                set(m, 1, 6, lc[H22_RES][pb]);
                set(m, 1, 7, 0);
                set(m, 2, 0, l[H21][pb]);
                set(m, 2, 1, 0);
                set(m, 2, 2, 0);
                set(m, 2, 3, l[H22_RES][pb]);
                set(m, 2, 4, 0);
                set(m, 2, 6, 0);
                set(m, 2, 7, 0);
                set(m, 3, 0, 0);
                set(m, 3, 1, rc[H11][pb] * r[H11][pb]);
                set(m, 3, 2, 0);
                set(m, 3, 3, 0);
                set(m, 3, 4, rc[H11][pb] * r[H12_RES][pb]);
                set(m, 3, 6, 0);
                set(m, 3, 7, rc[H12_RES][pb]);
                set(m, 4, 0, 0);
                set(m, 4, 1, rc[H21][pb] * r[H11][pb]);
                set(m, 4, 2, 0);
                set(m, 4, 3, 0);
                set(m, 4, 4, rc[H21][pb] * r[H12_RES][pb]);
                set(m, 4, 6, 0);
                set(m, 4, 7, rc[H22_RES][pb]);
                set(m, 5, 0, 0);
                set(m, 5, 1, r[H21][pb]);
                set(m, 5, 2, 0);
                set(m, 5, 3, 0);
                set(m, 5, 4, r[H22_RES][pb]);
                set(m, 5, 6, 0);
                set(m, 5, 7, 0);
                lfeRows(m, pb);
            }
            decorrelatedGains(ps);
            for (var part = 0; part < 2; part++) {
                for (var pb = parameters.tttStart[part]; pb < parameters.tttStop[part]; pb++) {
                    if (parameters.tttMode[part] < 2 && pb >= res[5] && parameters.tttDecorrelated[part]) {
                        var d = m2Decor[ps][pb];
                        var m = m2Resid[ps][pb];
                        var g = decorrelatedGain[pb];
                        set(d, 0, 5, g * l[C_L][pb] * lc[H11][pb]);
                        set(d, 1, 5, g * l[C_L][pb] * lc[H21][pb]);
                        set(d, 3, 5, g * r[C_L][pb] * rc[H11][pb]);
                        set(d, 4, 5, g * r[C_L][pb] * rc[H21][pb]);
                        set(d, 6, 5, (float) (-Math.sqrt(2) * g * cLfeLeft[pb]));
                        set(m, 0, 0, get(m, 0, 0) * kappa[pb]);
                        set(m, 1, 0, get(m, 1, 0) * kappa[pb]);
                        set(m, 3, 1, get(m, 3, 1) * kappa[pb]);
                        set(m, 4, 1, get(m, 4, 1) * kappa[pb]);
                        set(m, 6, 2, get(m, 6, 2) * kappa[pb]);
                    }
                }
            }
        }
    }

    private void lfeRows(float[] m, int pb) {
        for (var row = 6; row < 8; row++) {
            set(m, row, 0, 0);
            set(m, row, 1, 0);
            set(m, row, 3, 0);
            set(m, row, 4, 0);
            set(m, row, 6, 0);
            set(m, row, 7, 0);
        }
        set(m, 6, 2, cLfeLeft[pb]);
        set(m, 7, 2, cLfeRight[pb]);
        for (var row = 0; row < 8; row++) {
            set(m, row, 5, 0);
        }
    }

    private void calculate7272() {
        var res = parameters.residualBands;
        var l = boxes[1];
        var r = boxes[2];
        var ls = boxes[3];
        var rs = boxes[4];
        for (var ps = 0; ps < parameters.sets; ps++) {
            box(1, ps, res[1], l);
            box(2, ps, res[2], r);
            box(3, ps, res[3], ls);
            box(4, ps, res[4], rs);
            stereoPreMatrix7(ps, l[C_R], r[C_R]);
            lfe(0, ps);
            for (var pb = 0; pb < bands; pb++) {
                var d = m2Decor[ps][pb];
                set(d, 0, 3, l[H12][pb]);
                set(d, 0, 4, 0);
                set(d, 0, 6, 0);
                set(d, 0, 7, 0);
                set(d, 1, 3, ls[H11][pb] * l[H22][pb]);
                set(d, 1, 4, 0);
                set(d, 1, 6, ls[H12][pb]);
                set(d, 1, 7, 0);
                set(d, 2, 3, ls[H21][pb] * l[H22][pb]);
                set(d, 2, 4, 0);
                set(d, 2, 6, ls[H22][pb]);
                set(d, 2, 7, 0);
                set(d, 3, 3, 0);
                set(d, 3, 4, r[H12][pb]);
                set(d, 3, 6, 0);
                set(d, 3, 7, 0);
                set(d, 4, 3, 0);
                set(d, 4, 4, rs[H11][pb] * r[H22][pb]);
                set(d, 4, 6, 0);
                set(d, 4, 7, rs[H12][pb]);
                set(d, 5, 3, 0);
                set(d, 5, 4, rs[H21][pb] * r[H22][pb]);
                set(d, 5, 6, 0);
                set(d, 5, 7, rs[H22][pb]);
                for (var row = 6; row < 8; row++) {
                    set(d, row, 3, 0);
                    set(d, row, 4, 0);
                    set(d, row, 6, 0);
                    set(d, row, 7, 0);
                }
                for (var row = 0; row < 8; row++) {
                    set(d, row, 0, 0);
                    set(d, row, 1, 0);
                    set(d, row, 2, 0);
                    set(d, row, 5, 0);
                }
            }
            for (var pb = 0; pb < bands; pb++) {
                var m = m2Resid[ps][pb];
                set(m, 0, 0, l[H11][pb]);
                set(m, 0, 1, 0);
                set(m, 0, 2, 0);
                set(m, 0, 3, l[H12_RES][pb]);
                set(m, 0, 4, 0);
                set(m, 0, 6, 0);
                set(m, 0, 7, 0);
                set(m, 1, 0, ls[H11][pb] * l[H21][pb]);
                set(m, 1, 1, 0);
                set(m, 1, 2, 0);
                set(m, 1, 3, ls[H11][pb] * l[H22_RES][pb]);
                set(m, 1, 4, 0);
                set(m, 1, 6, ls[H12_RES][pb]);
                set(m, 1, 7, 0);
                set(m, 2, 0, ls[H21][pb] * l[H21][pb]);
                set(m, 2, 1, 0);
                set(m, 2, 2, 0);
                set(m, 2, 3, ls[H21][pb] * l[H22_RES][pb]);
                set(m, 2, 4, 0);
                set(m, 2, 6, ls[H22_RES][pb]);
                set(m, 2, 7, 0);
                set(m, 3, 0, 0);
                set(m, 3, 1, r[H11][pb]);
                set(m, 3, 2, 0);
                set(m, 3, 3, 0);
                set(m, 3, 4, r[H12_RES][pb]);
                set(m, 3, 6, 0);
                set(m, 3, 7, 0);
                set(m, 4, 0, 0);
                set(m, 4, 1, rs[H11][pb] * r[H21][pb]);
                set(m, 4, 2, 0);
                set(m, 4, 3, 0);
                set(m, 4, 4, rs[H11][pb] * r[H22_RES][pb]);
                set(m, 4, 6, 0);
                set(m, 4, 7, rs[H12_RES][pb]);
                set(m, 5, 0, 0);
                set(m, 5, 1, rs[H21][pb] * r[H21][pb]);
                set(m, 5, 2, 0);
                set(m, 5, 3, 0);
                set(m, 5, 4, rs[H21][pb] * r[H22_RES][pb]);
                set(m, 5, 6, 0);
                set(m, 5, 7, rs[H22_RES][pb]);
                lfeRows(m, pb);
            }
            decorrelatedGains(ps);
            for (var part = 0; part < 2; part++) {
                for (var pb = parameters.tttStart[part]; pb < parameters.tttStop[part]; pb++) {
                    if (parameters.tttMode[part] < 2 && pb >= res[5] && parameters.tttDecorrelated[part]) {
                        var d = m2Decor[ps][pb];
                        var m = m2Resid[ps][pb];
                        var g = decorrelatedGain[pb];
                        set(d, 0, 5, g * l[C_L][pb]);
                        set(d, 3, 5, g * r[C_L][pb]);
                        set(d, 6, 5, (float) (-Math.sqrt(2) * g * cLfeLeft[pb]));
                        set(m, 0, 0, get(m, 0, 0) * kappa[pb]);
                        set(m, 3, 1, get(m, 3, 1) * kappa[pb]);
                        set(m, 6, 2, get(m, 6, 2) * kappa[pb]);
                    }
                }
            }
        }
    }

    private void calculate7571() {
        var res = parameters.residualBands;
        var l = boxes[0];
        var r = boxes[1];
        for (var ps = 0; ps < parameters.sets; ps++) {
            box(0, ps, res[0], l);
            box(1, ps, res[1], r);
            for (var pb = 0; pb < bands; pb++) {
                var m1r = m1Real[ps][pb];
                var m1i = m1Imag[ps][pb];
                clear(m1r, 8, 6);
                clear(m1i, 8, 6);
                for (var i = 0; i < 6; i++) {
                    set(m1r, i, i, 1);
                }
                set(m1r, 6, 0, 1);
                set(m1r, 7, 1, 1);
                var d = m2Decor[ps][pb];
                clear(d, 8, 8);
                set(d, 0, 6, l[H12][pb]);
                set(d, 1, 6, l[H22][pb]);
                set(d, 3, 7, r[H12][pb]);
                set(d, 4, 7, r[H22][pb]);
                var m = m2Resid[ps][pb];
                clear(m, 8, 8);
                set(m, 0, 0, l[H11][pb]);
                set(m, 0, 6, l[H12_RES][pb]);
                set(m, 1, 0, l[H21][pb]);
                set(m, 1, 6, l[H22_RES][pb]);
                set(m, 2, 4, 1);
                set(m, 3, 1, r[H11][pb]);
                set(m, 3, 7, r[H12_RES][pb]);
                set(m, 4, 1, r[H21][pb]);
                set(m, 4, 7, r[H22_RES][pb]);
                set(m, 5, 5, 1);
                set(m, 6, 2, 1);
                set(m, 7, 3, 1);
            }
        }
    }

    private void calculate7572() {
        var res = parameters.residualBands;
        var l = boxes[0];
        var r = boxes[1];
        for (var ps = 0; ps < parameters.sets; ps++) {
            box(0, ps, res[0], l);
            box(1, ps, res[1], r);
            for (var pb = 0; pb < bands; pb++) {
                var m1r = m1Real[ps][pb];
                var m1i = m1Imag[ps][pb];
                clear(m1r, 8, 6);
                clear(m1i, 8, 6);
                for (var i = 0; i < 6; i++) {
                    set(m1r, i, i, 1);
                }
                set(m1r, 6, 4, 1);
                set(m1r, 7, 5, 1);
                var d = m2Decor[ps][pb];
                clear(d, 8, 8);
                set(d, 1, 6, l[H22][pb]);
                set(d, 2, 6, l[H12][pb]);
                set(d, 4, 7, r[H22][pb]);
                set(d, 5, 7, r[H12][pb]);
                var m = m2Resid[ps][pb];
                clear(m, 8, 8);
                set(m, 0, 0, 1);
                set(m, 1, 4, l[H21][pb]);
                set(m, 1, 6, l[H22_RES][pb]);
                set(m, 2, 4, l[H11][pb]);
                set(m, 2, 6, l[H12_RES][pb]);
                set(m, 3, 1, 1);
                set(m, 4, 5, r[H21][pb]);
                set(m, 4, 7, r[H22_RES][pb]);
                set(m, 5, 5, r[H11][pb]);
                set(m, 5, 7, r[H12_RES][pb]);
                set(m, 6, 2, 1);
                set(m, 7, 3, 1);
            }
        }
    }

    private static void clear(float[] matrix, int rows, int columns) {
        for (var row = 0; row < rows; row++) {
            for (var col = 0; col < columns; col++) {
                matrix[row * STRIDE + col] = 0;
            }
        }
    }

    /**
     * Smooths the matrices of the bands the frame selects ({@code SpatialDecSmoothM1andM2}).
     */
    private void smooth() {
        var residual = 0;
        if (parameters.config.residualCoding) {
            for (var value : parameters.residualBands) {
                residual = Math.max(residual, value);
            }
        }
        if (arbitraryDownmix == 2) {
            residual = Math.max(residual, parameters.downmixResidualBands);
        }
        for (var ps = 0; ps < parameters.sets; ps++) {
            var slots = ps == 0 ? parameters.parameterSlot[0] + 1
                    : parameters.parameterSlot[ps] - parameters.parameterSlot[ps - 1];
            var delta = (float) slots / parameters.smoothingTime[ps];
            for (var pb = 0; pb < bands; pb++) {
                if (parameters.smoothingData[ps][pb] == 0 || pb < residual) {
                    continue;
                }
                blend(m1Real[ps][pb], ps > 0 ? m1Real[ps - 1][pb] : m1RealPrevious[pb], delta);
                blend(m1Imag[ps][pb], ps > 0 ? m1Imag[ps - 1][pb] : m1ImagPrevious[pb], delta);
                blend(m2Decor[ps][pb], ps > 0 ? m2Decor[ps - 1][pb] : m2DecorPrevious[pb], delta);
                blend(m2Resid[ps][pb], ps > 0 ? m2Resid[ps - 1][pb] : m2ResidPrevious[pb], delta);
            }
        }
    }

    private static void blend(float[] matrix, float[] previous, float delta) {
        for (var i = 0; i < SIZE; i++) {
            matrix[i] = delta * matrix[i] + (1 - delta) * previous[i];
        }
    }
}
