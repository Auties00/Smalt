/* Ports SpatialDecCalculateM1andM2_5121, _5122, _5221 and _5227 of sac_calcM1andM2.c of the mp4spatialdec module of the
   ISO/IEC 23003-3 reference software.
   Copyright Agere Systems, Coding Technologies, Fraunhofer IIS, Philips and ISO/IEC 2008; ISO/IEC reference software
   licence (use in products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.mps;

import com.github.auties00.smalt.audio.aac.AacBinauralQuality;

import java.util.Arrays;

/**
 * Computes the matrices of the binaural rendering of an MPEG Surround tree, in 28 parameter bands, into the matrices of
 * an {@link MpsMatrices}: {@code M1} feeds the downmix on, and the complex {@code M2} mixes it to the two ears through
 * the band parameters of a data set of head related transfer functions, or through an {@link MpsHrtfFilterbank}, which
 * leaves {@code M2} out. Instances are not thread-safe.
 */
final class MpsBinauralMatrices {
    private static final int BANDS = MpsHrtf.BANDS;
    private static final float ABS_THR = 1.0e-9f;
    /**
     * The parameter bands that keep the phase differences of the data set; the bands above are real.
     */
    private static final int PHASE_BANDS = 12;
    private static final int COLUMNS = 5;

    private final MpsMatrices matrices;
    private final MpsParameters parameters;
    private final MpsHrtf hrtf;
    private final MpsHrtfFilterbank filterbank;
    private final float surroundGain;
    private final int arbitraryDownmix;
    private final int[] tttStart = new int[2];
    private final int[] tttStop = new int[2];
    private final float[] lf = new float[BANDS];
    private final float[] rf = new float[BANDS];
    private final float[] c = new float[BANDS];
    private final float[] ls = new float[BANDS];
    private final float[] rs = new float[BANDS];
    private final float[] c1 = new float[BANDS];
    private final float[] c2 = new float[BANDS];
    private final float[] iccCenter = new float[BANDS];
    private final boolean[] prediction = new boolean[BANDS];
    private final float[][] mReal = new float[BANDS][3 * COLUMNS];
    private final float[][] mImag = new float[BANDS][3 * COLUMNS];
    private final float[][] cosIpd = new float[MpsHrtf.DIRECTIONS][BANDS];
    private final float[][] sinIpd = new float[MpsHrtf.DIRECTIONS][BANDS];

    /**
     * Constructs the matrices of a stream.
     *
     * @param matrices the matrices to fill, whose parameters are mapped to 28 bands
     * @param binaural the rendering
     * @param qmfBands the number of QMF subbands
     * @param kernelBand the parameter band of each hybrid subband
     */
    MpsBinauralMatrices(MpsMatrices matrices, MpsBinaural binaural, int qmfBands, int[] kernelBand) {
        this.matrices = matrices;
        this.parameters = matrices.parameters;
        this.hrtf = MpsHrtf.of(binaural.hrtf());
        this.surroundGain = matrices.surroundGain;
        this.arbitraryDownmix = parameters.config.arbitraryDownmix;
        var stereo = parameters.inputs == 2;
        this.filterbank = stereo && binaural.quality() == AacBinauralQuality.FILTERED
                ? new MpsHrtfFilterbank(hrtf, surroundGain, qmfBands, kernelBand) : null;
        for (var part = 0; part < 2; part++) {
            tttStart[part] = parameters.mappedBand(parameters.tttStart[part]);
            tttStop[part] = parameters.mappedBand(parameters.tttStop[part]);
        }
        for (var direction = 0; direction < MpsHrtf.DIRECTIONS; direction++) {
            for (var pb = 0; pb < BANDS; pb++) {
                cosIpd[direction][pb] = (float) StrictMath.cos(hrtf.ipd[direction][pb]);
                sinIpd[direction][pb] = (float) StrictMath.sin(hrtf.ipd[direction][pb]);
            }
        }
    }

    /**
     * {@return the filterbank of the rendering, or {@code null} when it renders with the band parameters alone}
     */
    MpsHrtfFilterbank filterbank() {
        return filterbank;
    }

    /**
     * Computes the matrices of every parameter set of the frame.
     */
    void calculate() {
        for (var ps = 0; ps < parameters.sets; ps++) {
            switch (parameters.tree) {
                case MpsConfig.TREE_5151 -> {
                    powers5151(ps);
                    mono(ps, parameters.icc[3][ps], parameters.icc[2][ps]);
                }
                case MpsConfig.TREE_5152 -> {
                    powers5152(ps);
                    mono(ps, parameters.icc[1][ps], parameters.icc[1][ps]);
                }
                default -> {
                    if (filterbank != null) {
                        filtered(ps);
                    } else {
                        stereo(ps);
                    }
                }
            }
        }
    }

    private static float power(float cld) {
        return (float) StrictMath.pow(10.0f, cld / 10.0f);
    }

    private void powers5151(int ps) {
        for (var pb = 0; pb < BANDS; pb++) {
            var iidFs = power(parameters.cld[0][ps][pb]);
            var iidC = power(parameters.cld[1][ps][pb]);
            var iidS = power(parameters.cld[2][ps][pb]);
            var iidF = power(parameters.cld[3][ps][pb]);
            var fsFront = iidFs / (1 + iidFs);
            var fsSurround = 1 / (1 + iidFs);
            var cFront = iidC / (1 + iidC);
            var cCenter = 1 / (1 + iidC);
            var sLeft = iidS / (1 + iidS);
            var sRight = 1 / (1 + iidS);
            var fLeft = iidF / (1 + iidF);
            var fRight = 1 / (1 + iidF);
            lf[pb] = fsFront * cFront * fLeft;
            rf[pb] = fsFront * cFront * fRight;
            c[pb] = fsFront * cCenter;
            ls[pb] = fsSurround * sLeft * surroundGain * surroundGain;
            rs[pb] = fsSurround * sRight * surroundGain * surroundGain;
        }
    }

    private void powers5152(int ps) {
        for (var pb = 0; pb < BANDS; pb++) {
            var iidC = power(parameters.cld[0][ps][pb]);
            var iidLr = power(parameters.cld[1][ps][pb]);
            var iidL = power(parameters.cld[3][ps][pb]);
            var iidR = power(parameters.cld[4][ps][pb]);
            var cSides = iidC / (1 + iidC);
            var cCenter = 1 / (1 + iidC);
            var lrLeft = iidLr / (1 + iidLr);
            var lrRight = 1 / (1 + iidLr);
            var lFront = iidL / (1 + iidL);
            var lSurround = 1 / (1 + iidL);
            var rFront = iidR / (1 + iidR);
            var rSurround = 1 / (1 + iidR);
            lf[pb] = cSides * lrLeft * lFront;
            rf[pb] = cSides * lrRight * rFront;
            c[pb] = cCenter;
            ls[pb] = cSides * lrLeft * lSurround * surroundGain * surroundGain;
            rs[pb] = cSides * lrRight * rSurround * surroundGain * surroundGain;
        }
    }

    /**
     * Mixes a mono downmix and its decorrelated signal so that the two ears get the power, coherence and phase
     * difference the five channels would give through the data set.
     *
     * @param ps the parameter set
     * @param iccFront the ICC of the front channels by band
     * @param iccSurround the ICC of the surround channels by band
     */
    private void mono(int ps, float[] iccFront, float[] iccSurround) {
        var pl = hrtf.powerLeft;
        var pr = hrtf.powerRight;
        var hi = hrtf.icc;
        var ipd = hrtf.ipd;
        for (var pb = 0; pb < BANDS; pb++) {
            var front = (float) Math.sqrt(lf[pb] * rf[pb]) * iccFront[pb];
            var surround = (float) Math.sqrt(ls[pb] * rs[pb]) * iccSurround[pb];
            var iccRf = hi[1][pb] * cosIpd[1][pb];
            var iccRs = hi[4][pb] * cosIpd[4][pb];
            var iccLf = hi[0][pb] * cosIpd[0][pb];
            var iccLs = hi[3][pb] * cosIpd[3][pb];
            var l = pl[0][pb] * pl[0][pb] * lf[pb];
            l += pl[1][pb] * pl[1][pb] * rf[pb];
            l += pl[2][pb] * pl[2][pb] * c[pb];
            l += pl[3][pb] * pl[3][pb] * ls[pb];
            l += pl[4][pb] * pl[4][pb] * rs[pb];
            l += 2 * pl[0][pb] * pl[1][pb] * front * iccRf;
            l += 2 * pl[3][pb] * pl[4][pb] * surround * iccRs;
            var r = pr[0][pb] * pr[0][pb] * lf[pb];
            r += pr[1][pb] * pr[1][pb] * rf[pb];
            r += pr[2][pb] * pr[2][pb] * c[pb];
            r += pr[3][pb] * pr[3][pb] * ls[pb];
            r += pr[4][pb] * pr[4][pb] * rs[pb];
            r += 2 * pr[0][pb] * pr[1][pb] * front * iccLf;
            r += 2 * pr[3][pb] * pr[4][pb] * surround * iccLs;
            var temp = pl[0][pb] * pr[0][pb] * hi[0][pb] * lf[pb];
            var lrReal = cosIpd[0][pb] * temp;
            var lrImag = sinIpd[0][pb] * temp;
            temp = pl[1][pb] * pr[1][pb] * hi[1][pb] * rf[pb];
            lrReal += cosIpd[1][pb] * temp;
            lrImag += sinIpd[1][pb] * temp;
            temp = pl[2][pb] * pr[2][pb] * hi[2][pb] * c[pb];
            lrReal += cosIpd[2][pb] * temp;
            lrImag += sinIpd[2][pb] * temp;
            temp = pl[3][pb] * pr[3][pb] * hi[3][pb] * ls[pb];
            lrReal += cosIpd[3][pb] * temp;
            lrImag += sinIpd[3][pb] * temp;
            temp = pl[4][pb] * pr[4][pb] * hi[4][pb] * rs[pb];
            lrReal += cosIpd[4][pb] * temp;
            lrImag += sinIpd[4][pb] * temp;
            lrReal += pl[0][pb] * pr[1][pb] * front;
            lrReal += pl[3][pb] * pr[4][pb] * surround;
            temp = pl[1][pb] * pr[0][pb] * front * hi[0][pb] * hi[1][pb];
            lrReal += (float) StrictMath.cos(ipd[0][pb] + ipd[1][pb]) * temp;
            lrImag += (float) StrictMath.sin(ipd[0][pb] + ipd[1][pb]) * temp;
            temp = pl[4][pb] * pr[3][pb] * surround * hi[3][pb] * hi[4][pb];
            lrReal += (float) StrictMath.cos(ipd[3][pb] + ipd[4][pb]) * temp;
            lrImag += (float) StrictMath.sin(ipd[3][pb] + ipd[4][pb]) * temp;
            mixMono(ps, pb, l, r, lrReal, lrImag);
        }
    }

    private void mixMono(int ps, int pb, float l, float r, float lrReal, float lrImag) {
        var iid = (l + ABS_THR) / (r + ABS_THR);
        var temp = (float) Math.sqrt(l * r) + ABS_THR;
        var icc = ((float) Math.sqrt(lrReal * lrReal + lrImag * lrImag) + ABS_THR) / temp;
        float ipd;
        if (icc < 0.6f) {
            ipd = 0;
            icc = (lrReal + ABS_THR) / temp;
        } else {
            ipd = pb < PHASE_BANDS ? (float) StrictMath.atan2(lrImag, lrReal) : 0;
        }
        if (icc > 1.0f) {
            icc = 1.0f;
        }
        if (icc < -1.0f) {
            icc = -1.0f;
        }
        var gain = (float) Math.sqrt(l + r);
        var cL = (float) Math.sqrt(iid / (1 + iid));
        var cR = (float) Math.sqrt(1 / (1 + iid));
        var alpha = 0.5f * (float) StrictMath.acos(icc);
        var beta = (float) StrictMath.atan(StrictMath.tan(alpha) * (cR - cL) / (cR + cL));
        var cos = (float) StrictMath.cos(0.5f * ipd);
        var sin = (float) StrictMath.sin(0.5f * ipd);
        var h11 = gain * cL * (float) StrictMath.cos(alpha + beta);
        var h12 = gain * cL * (float) StrictMath.sin(alpha + beta);
        var h21 = gain * cR * (float) StrictMath.cos(-alpha + beta);
        var h22 = gain * cR * (float) StrictMath.sin(-alpha + beta);
        var h11Imag = h11 * sin;
        var h12Imag = h12 * sin;
        var h21Imag = h21 * -sin;
        var h22Imag = h22 * -sin;
        h11 *= cos;
        h12 *= cos;
        h21 *= cos;
        h22 *= cos;
        var m1 = matrices.m1Real[ps][pb];
        MpsMatrices.set(m1, 0, 0, 1);
        MpsMatrices.set(m1, 1, 0, 1);
        MpsMatrices.set(matrices.m1Imag[ps][pb], 0, 0, 0);
        MpsMatrices.set(matrices.m1Imag[ps][pb], 1, 0, 0);
        if (arbitraryDownmix > 0) {
            matrices.downmixGains(ps, pb);
            MpsMatrices.set(m1, 0, 0, MpsMatrices.get(m1, 0, 0) * matrices.gains[0]);
            MpsMatrices.set(m1, 1, 0, MpsMatrices.get(m1, 1, 0) * matrices.gains[0]);
            if (arbitraryDownmix == 2) {
                MpsMatrices.set(m1, 0, 1, 1);
                MpsMatrices.set(m1, 1, 1, 1);
            }
        }
        var decor = matrices.m2Decor[ps][pb];
        var decorImag = matrices.m2DecorImag[ps][pb];
        MpsMatrices.set(decor, 0, 0, 0);
        MpsMatrices.set(decorImag, 0, 0, 0);
        MpsMatrices.set(decor, 0, 1, finite(h12));
        MpsMatrices.set(decorImag, 0, 1, finite(h12Imag));
        MpsMatrices.set(decor, 1, 0, 0);
        MpsMatrices.set(decorImag, 1, 0, 0);
        MpsMatrices.set(decor, 1, 1, finite(h22));
        MpsMatrices.set(decorImag, 1, 1, finite(h22Imag));
        var resid = matrices.m2Resid[ps][pb];
        var residImag = matrices.m2ResidImag[ps][pb];
        MpsMatrices.set(resid, 0, 0, finite(h11));
        MpsMatrices.set(residImag, 0, 0, finite(h11Imag));
        MpsMatrices.set(resid, 0, 1, 0);
        MpsMatrices.set(residImag, 0, 1, 0);
        MpsMatrices.set(resid, 1, 0, finite(h21));
        MpsMatrices.set(residImag, 1, 0, finite(h21Imag));
        MpsMatrices.set(resid, 1, 1, 0);
        MpsMatrices.set(residImag, 1, 1, 0);
    }

    /**
     * {@return the value, or 0 for one a degenerate band made infinite or not a number, which the filters would keep
     * to the end of the stream}
     */
    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }

    /**
     * Computes the left, right and center rows of a stereo downmix for every band of a parameter set, with the matrix
     * inversion or the inversion of a 3D downmix.
     */
    private void preMatrix(int ps) {
        var pre = matrices.pre;
        var ttt = matrices.ttt;
        var inverseReal = matrices.inverseReal;
        var inverseImag = matrices.inverseImag;
        for (var part = 0; part < 2; part++) {
            var mode = parameters.tttMode[part];
            var inversion = matrices.matrixMode;
            if (mode >= 2) {
                inversion = inversion && (mode == 2 || mode == 4);
            }
            for (var pb = tttStart[part]; pb < tttStop[part]; pb++) {
                for (var row = 0; row < 3; row++) {
                    Arrays.fill(pre[row], 0.0f);
                }
                matrices.tttMatrix(ps, pb, mode);
                for (var row = 0; row < 3; row++) {
                    System.arraycopy(ttt[row], 0, pre[row], 0, 3);
                }
                if (arbitraryDownmix != 0) {
                    matrices.downmixGains(ps, pb);
                    for (var ch = 0; ch < 2; ch++) {
                        for (var row = 0; row < 3; row++) {
                            pre[row][ch] *= matrices.gains[ch];
                            if (arbitraryDownmix == 2 && pb < parameters.downmixResidualBands) {
                                pre[row][3 + ch] = ttt[row][ch];
                            }
                        }
                    }
                }
                var mr = mReal[pb];
                var mi = mImag[pb];
                if (inversion || matrices.threeDimensional) {
                    if (inversion) {
                        matrices.inverse(ps, pb, mode);
                    } else {
                        matrices.inverse3d(ps, pb);
                    }
                    for (var row = 0; row < 3; row++) {
                        var at = row * COLUMNS;
                        mr[at] = finite(pre[row][0] * inverseReal[0][0] + pre[row][1] * inverseReal[1][0]);
                        mr[at + 1] = finite(pre[row][0] * inverseReal[0][1] + pre[row][1] * inverseReal[1][1]);
                        mi[at] = finite(pre[row][0] * inverseImag[0][0] + pre[row][1] * inverseImag[1][0]);
                        mi[at + 1] = finite(pre[row][0] * inverseImag[0][1] + pre[row][1] * inverseImag[1][1]);
                    }
                } else {
                    for (var row = 0; row < 3; row++) {
                        var at = row * COLUMNS;
                        mr[at] = finite(pre[row][0]);
                        mr[at + 1] = finite(pre[row][1]);
                        mi[at] = 0;
                        mi[at + 1] = 0;
                    }
                }
                for (var row = 0; row < 3; row++) {
                    var at = row * COLUMNS;
                    mr[at + 2] = finite(pre[row][3]);
                    mr[at + 3] = finite(pre[row][4]);
                    mr[at + 4] = finite(pre[row][2]);
                }
            }
        }
    }

    /**
     * Sets {@code M1} of a stereo downmix: the downmix and, with the arbitrary downmix residual, its residual pass on.
     */
    private void passThrough(int ps) {
        for (var pb = 0; pb < BANDS; pb++) {
            var real = matrices.m1Real[ps][pb];
            var imag = matrices.m1Imag[ps][pb];
            MpsMatrices.set(real, 0, 0, 1);
            MpsMatrices.set(real, 0, 1, 0);
            MpsMatrices.set(real, 1, 0, 0);
            MpsMatrices.set(real, 1, 1, 1);
            MpsMatrices.set(imag, 0, 0, 0);
            MpsMatrices.set(imag, 0, 1, 0);
            MpsMatrices.set(imag, 1, 0, 0);
            MpsMatrices.set(imag, 1, 1, 0);
            MpsMatrices.set(real, 2, 3, 1);
            MpsMatrices.set(real, 2, 4, 0);
            MpsMatrices.set(real, 3, 3, 0);
            MpsMatrices.set(real, 3, 4, 1);
            MpsMatrices.set(imag, 2, 3, 0);
            MpsMatrices.set(imag, 2, 4, 0);
            MpsMatrices.set(imag, 3, 3, 0);
            MpsMatrices.set(imag, 3, 4, 0);
        }
    }

    /**
     * Mixes a stereo downmix to the ears with the band parameters: each side takes one response, of its front and
     * surround channels by their power, and the center its own.
     */
    private void stereo(int ps) {
        preMatrix(ps);
        var pl = hrtf.powerLeft;
        var pr = hrtf.powerRight;
        var ipd = hrtf.ipd;
        var gain = surroundGain;
        var columns = arbitraryDownmix == 2 ? 4 : 2;
        for (var pb = 0; pb < BANDS; pb++) {
            var phased = pb < PHASE_BANDS;
            var temp1 = pl[2][pb];
            var temp2 = pr[2][pb];
            var pc1Real = temp1;
            var pc1Imag = 0.0f;
            var pc2Real = temp2;
            var pc2Imag = 0.0f;
            if (phased) {
                pc1Real = (float) StrictMath.cos(ipd[2][pb] / 2) * temp1;
                pc1Imag = (float) StrictMath.sin(ipd[2][pb] / 2) * temp1;
                pc2Real = (float) StrictMath.cos(-ipd[2][pb] / 2) * temp2;
                pc2Imag = (float) StrictMath.sin(-ipd[2][pb] / 2) * temp2;
            }
            var iid = power(parameters.cld[1][ps][pb]);
            var front = iid / (1 + iid);
            var surround = 1 / (1 + iid);
            var frontLeft = front / (front + surround) * pl[0][pb] * pl[0][pb];
            var frontRight = front / (front + surround) * pr[0][pb] * pr[0][pb];
            var surroundLeft = surround / (front + surround) * pl[3][pb] * pl[3][pb] * gain * gain;
            var surroundRight = surround / (front + surround) * pr[3][pb] * pr[3][pb] * gain * gain;
            var pl1 = (float) Math.sqrt(frontLeft + surroundLeft);
            var temp = (float) Math.sqrt(frontRight + surroundRight);
            var pl2Real = temp;
            var pl2Imag = 0.0f;
            if (phased) {
                var angle = -(frontRight * ipd[0][pb] + surroundRight * ipd[3][pb]) / (frontRight + surroundRight);
                pl2Real = (float) StrictMath.cos(angle) * temp;
                pl2Imag = (float) StrictMath.sin(angle) * temp;
            }
            iid = power(parameters.cld[2][ps][pb]);
            front = iid / (1 + iid);
            surround = 1 / (1 + iid);
            frontLeft = front / (front + surround) * pl[1][pb] * pl[1][pb];
            frontRight = front / (front + surround) * pr[1][pb] * pr[1][pb];
            surroundLeft = surround / (front + surround) * pl[4][pb] * pl[4][pb] * gain * gain;
            surroundRight = surround / (front + surround) * pr[4][pb] * pr[4][pb] * gain * gain;
            temp = (float) Math.sqrt(frontLeft + surroundLeft);
            var pr2 = (float) Math.sqrt(frontRight + surroundRight);
            var pr1Real = temp;
            var pr1Imag = 0.0f;
            if (phased) {
                var angle = (frontLeft * ipd[1][pb] + surroundLeft * ipd[4][pb]) / (frontLeft + surroundLeft);
                pr1Real = (float) StrictMath.cos(angle) * temp;
                pr1Imag = (float) StrictMath.sin(angle) * temp;
            }
            var mr = mReal[pb];
            var mi = mImag[pb];
            var resid = matrices.m2Resid[ps][pb];
            var residImag = matrices.m2ResidImag[ps][pb];
            for (var col = 0; col < columns; col++) {
                var re = mr[col] * pl1;
                var im = mi[col] * pl1;
                re += mr[5 + col] * pr1Real - mi[5 + col] * pr1Imag;
                im += mi[5 + col] * pr1Real + mr[5 + col] * pr1Imag;
                re += mr[10 + col] * pc1Real - mi[10 + col] * pc1Imag;
                im += mi[10 + col] * pc1Real + mr[10 + col] * pc1Imag;
                MpsMatrices.set(resid, 0, col, re);
                MpsMatrices.set(residImag, 0, col, im);
                re = mr[col] * pl2Real - mi[col] * pl2Imag;
                im = mi[col] * pl2Real + mr[col] * pl2Imag;
                re += mr[5 + col] * pr2;
                im += mi[5 + col] * pr2;
                re += mr[10 + col] * pc2Real - mi[10 + col] * pc2Imag;
                im += mi[10 + col] * pc2Real + mr[10 + col] * pc2Imag;
                MpsMatrices.set(resid, 1, col, re);
                MpsMatrices.set(residImag, 1, col, im);
            }
            var decor = matrices.m2Decor[ps][pb];
            var decorImag = matrices.m2DecorImag[ps][pb];
            MpsMatrices.set(decor, 0, 0, 0);
            MpsMatrices.set(decor, 0, 1, 0);
            MpsMatrices.set(decor, 1, 0, 0);
            MpsMatrices.set(decor, 1, 1, 0);
            MpsMatrices.set(decorImag, 0, 0, 0);
            MpsMatrices.set(decorImag, 0, 1, 0);
            MpsMatrices.set(decorImag, 1, 0, 0);
            MpsMatrices.set(decorImag, 1, 1, 0);
        }
        passThrough(ps);
    }

    /**
     * Hands a parameter set of a stereo downmix to the filterbank, which replaces {@code M2}.
     */
    private void filtered(int ps) {
        preMatrix(ps);
        for (var part = 0; part < 2; part++) {
            for (var pb = tttStart[part]; pb < tttStop[part]; pb++) {
                if (parameters.tttMode[part] < 2) {
                    prediction[pb] = true;
                    c1[pb] = parameters.cpc1[ps][pb];
                    c2[pb] = parameters.cpc2[ps][pb];
                    iccCenter[pb] = parameters.tttIcc[ps][pb];
                } else {
                    prediction[pb] = false;
                    c1[pb] = parameters.tttCld1[ps][pb];
                    c2[pb] = parameters.tttCld2[ps][pb];
                    iccCenter[pb] = 0;
                }
                var iid = power(parameters.cld[1][ps][pb]);
                lf[pb] = iid / (1 + iid);
                ls[pb] = 1 / (1 + iid);
                iid = power(parameters.cld[2][ps][pb]);
                rf[pb] = iid / (1 + iid);
                rs[pb] = 1 / (1 + iid);
            }
        }
        filterbank.update(mReal, mImag, lf, ls, rf, rs, c1, c2, iccCenter, prediction, parameters.parameterSlot[ps]);
        passThrough(ps);
    }
}
