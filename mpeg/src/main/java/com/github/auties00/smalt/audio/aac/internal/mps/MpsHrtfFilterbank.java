/* Ports sac_hrtffilter.c of the mp4spatialdec module of the ISO/IEC 23003-3 reference software.
   Copyright Agere Systems, Coding Technologies, Fraunhofer IIS, Philips and ISO/IEC 2008; ISO/IEC reference software
   licence (use in products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.mps;

import java.util.Arrays;

/**
 * Filters the two channels of a stereo downmix, and the two of its arbitrary downmix residual, with the impulse
 * responses of a data set in the hybrid QMF domain, weighted per hybrid subband by the spatial parameters.
 *
 * <p>Each parameter set yields one filter per hybrid subband and pair of input and output channels, combining the front
 * and surround responses of each side by their powers and adding the center response; the filters are interpolated over
 * the slots between parameter sets. Instances keep the state of one stream and are not thread-safe.
 */
final class MpsHrtfFilterbank {
    private static final int PROTOTYPE_SLOTS = 3;
    private static final int KMAX = PROTOTYPE_SLOTS / 4 + 10 / 2 - 1;
    private static final int V_SLOTS = KMAX * 2 + 1;
    private static final float PI = 3.14159265358979f;
    private static final float ABS_THR = 1e-9f;
    private static final float SQRT2 = (float) Math.sqrt(2);
    private static final int OUTPUTS = 2;
    private static final int INPUTS = 4;

    /**
     * The impulse responses of a data set, converted to the QMF domain.
     *
     * @param slots the length of the responses, in QMF slots
     * @param leftReal the real parts of the left ear response, by direction, QMF subband and slot
     * @param leftImag the imaginary parts of the left ear response
     * @param rightReal the real parts of the right ear response
     * @param rightImag the imaginary parts of the right ear response
     */
    record Masks(int slots, float[][][] leftReal, float[][][] leftImag, float[][][] rightReal, float[][][] rightImag) {
    }

    private final int hybridBands;
    private final int slots;
    private final float surroundGain;
    private final int[] kernelBand;
    private final Masks masks;
    private final int[] filterSlot = new int[MpsMatrices.MAX_SETS + 1];
    private int filterSets;
    /**
     * The real parts of the filters by parameter set, index 0 holding the last set of the previous frame; a filter
     * starts at {@code ((hybrid band * 2 + output) * 4 + input) * slots}.
     */
    private final float[][] filterReal;
    private final float[][] filterImag;
    private final float[] stateReal;
    private final float[] stateImag;
    /**
     * The combined side responses being computed: left side to the left ear, left side to the right ear, right side to
     * the left ear and right side to the right ear.
     */
    private final float[][] sideReal;
    private final float[][] sideImag;
    private final float[] phase = new float[4];

    /**
     * Constructs a filterbank.
     *
     * @param hrtf the data set
     * @param surroundGain the gain of the surround channels
     * @param qmfBands the number of QMF subbands
     * @param kernelBand the parameter band of each hybrid subband, of 28 parameter bands
     */
    MpsHrtfFilterbank(MpsHrtf hrtf, float surroundGain, int qmfBands, int[] kernelBand) {
        this.hybridBands = MpsHybrid.bands(qmfBands);
        this.surroundGain = surroundGain;
        this.kernelBand = kernelBand;
        this.masks = hrtf.masks.computeIfAbsent(qmfBands, bands -> masks(hrtf, bands));
        this.slots = masks.slots();
        var size = hybridBands * OUTPUTS * INPUTS * slots;
        this.filterReal = new float[MpsMatrices.MAX_SETS + 1][size];
        this.filterImag = new float[MpsMatrices.MAX_SETS + 1][size];
        this.stateReal = new float[size];
        this.stateImag = new float[size];
        this.sideReal = new float[4][slots];
        this.sideImag = new float[4][slots];
        this.filterSlot[0] = -1;
    }

    private static void invert(int length, float[][] x) {
        var t = new float[length][2 * length];
        for (var j = 0; j < length; j++) {
            System.arraycopy(x[j], 0, t[j], 0, length);
            t[j][length + j] = 1.0f;
        }
        for (var k = 0; k < length; k++) {
            var row = t[k];
            var pivot = row[k];
            for (var i = k; i < 2 * length; i++) {
                row[i] /= pivot;
            }
            for (var j = 0; j < length; j++) {
                var other = t[j];
                if (j != k && other[k] != 0.0f) {
                    var factor = other[k];
                    for (var i = k; i < 2 * length; i++) {
                        other[i] -= factor * row[i];
                    }
                }
            }
        }
        for (var j = 0; j < length; j++) {
            System.arraycopy(t[j], length, x[j], 0, length);
        }
    }

    /**
     * Solves for the window that turns an impulse response into its QMF domain filter, by least squares against the
     * response of the decoder's QMF analysis and synthesis ({@code GetPrototype}, {@code SolveSystem}).
     */
    private static float[] prototype(int qmfBands) {
        var length = qmfBands * PROTOTYPE_SLOTS;
        var columns = qmfBands * (V_SLOTS + PROTOTYPE_SLOTS) - 1;
        var qmf = MpsPolyphase.signedPrototype(qmfBands);
        var extended = new float[2 * length + 20 * qmfBands - 1];
        var offset1 = qmfBands * PROTOTYPE_SLOTS;
        var offset2 = qmfBands * 20 - 2 + offset1;
        var last = qmfBands * 10 - 1;
        for (var i = 0; i <= last; i++) {
            for (int j = 0, k = last - i; j <= i; j++, k++) {
                extended[offset1 + i] += qmf[j] * qmf[k];
            }
            extended[offset2 - i] = extended[offset1 + i];
        }
        var v = new float[length][columns];
        offset1 = qmfBands * PROTOTYPE_SLOTS / 2 - qmfBands * (PROTOTYPE_SLOTS / 2) - qmfBands / 2;
        offset2 = qmfBands * (PROTOTYPE_SLOTS + 10 + 2 * KMAX) - qmfBands * (PROTOTYPE_SLOTS / 2) - qmfBands / 2;
        for (var i = 0; i < V_SLOTS; i++) {
            for (var j = 0; j < qmfBands; j++) {
                for (var k = 0; k < PROTOTYPE_SLOTS; k++) {
                    v[k * qmfBands + offset1 + j][i * qmfBands + j] = extended[(k - 2 * i) * qmfBands + offset2 + j];
                }
            }
        }
        var r = new float[columns];
        for (var i = 0; i < qmfBands; i++) {
            r[qmfBands * KMAX + i] = qmfBands;
        }
        var vtv = new float[length][length];
        for (var k = 0; k < length; k++) {
            var rowK = v[k];
            var out = vtv[k];
            for (var j = 0; j < length; j++) {
                var rowJ = v[j];
                var sum = 0.0f;
                for (var i = 0; i < columns; i++) {
                    sum += rowK[i] * rowJ[i];
                }
                out[j] = sum;
            }
        }
        invert(length, vtv);
        var prototype = new float[length];
        var product = new float[columns];
        for (var k = 0; k < length; k++) {
            var inverse = vtv[k];
            for (var j = 0; j < columns; j++) {
                var sum = 0.0f;
                for (var i = 0; i < length; i++) {
                    sum += inverse[i] * v[i][j];
                }
                product[j] = sum;
            }
            var q = 0.0f;
            for (var i = 0; i < columns; i++) {
                q += product[i] * r[i];
            }
            prototype[k] = q;
        }
        return prototype;
    }

    private static Masks masks(MpsHrtf hrtf, int qmfBands) {
        var impulseSlots = (int) Math.ceil(hrtf.impulseLength / (float) qmfBands);
        var slots = impulseSlots + PROTOTYPE_SLOTS - 1;
        var prototype = prototype(qmfBands);
        var length = PROTOTYPE_SLOTS * qmfBands;
        var modulationReal = new float[qmfBands][length];
        var modulationImag = new float[qmfBands][length];
        var offset = qmfBands * PROTOTYPE_SLOTS / 2 - 1;
        for (var j = 0; j < qmfBands; j++) {
            for (var i = 0; i < length; i++) {
                var temp = -PI / qmfBands * (j + 0.5f) * (i - offset);
                modulationReal[j][i] = (float) StrictMath.cos(temp);
                modulationImag[j][i] = (float) StrictMath.sin(temp);
            }
        }
        var masks = new Masks(slots, new float[MpsHrtf.DIRECTIONS][qmfBands][slots],
                new float[MpsHrtf.DIRECTIONS][qmfBands][slots], new float[MpsHrtf.DIRECTIONS][qmfBands][slots],
                new float[MpsHrtf.DIRECTIONS][qmfBands][slots]);
        var extended = new float[(impulseSlots + PROTOTYPE_SLOTS + 1) * qmfBands];
        var windowed = new float[length];
        for (var direction = 0; direction < MpsHrtf.DIRECTIONS; direction++) {
            convert(hrtf.impulseLeft[direction], qmfBands, slots, prototype, modulationReal, modulationImag, extended,
                    windowed, masks.leftReal()[direction], masks.leftImag()[direction]);
            convert(hrtf.impulseRight[direction], qmfBands, slots, prototype, modulationReal, modulationImag, extended,
                    windowed, masks.rightReal()[direction], masks.rightImag()[direction]);
        }
        return masks;
    }

    private static void convert(float[] impulse, int qmfBands, int slots, float[] prototype, float[][] modulationReal,
                                float[][] modulationImag, float[] extended, float[] windowed, float[][] maskReal,
                                float[][] maskImag) {
        Arrays.fill(extended, 0.0f);
        System.arraycopy(impulse, 0, extended, qmfBands * (PROTOTYPE_SLOTS - 1), impulse.length);
        var length = PROTOTYPE_SLOTS * qmfBands;
        for (var k = 0; k < slots; k++) {
            var offset = qmfBands * k;
            for (var i = 0; i < length; i++) {
                windowed[i] = extended[offset + i] * prototype[i];
            }
            for (var j = 0; j < qmfBands; j++) {
                var real = 0.0f;
                var imag = 0.0f;
                var cos = modulationReal[j];
                var sin = modulationImag[j];
                for (var i = 0; i < length; i++) {
                    real += cos[i] * windowed[i];
                    imag += sin[i] * windowed[i];
                }
                maskReal[j][k] = real;
                maskImag[j][k] = imag;
            }
        }
    }

    /**
     * Combines the front and surround responses of one side by their weights, aligning their phases
     * ({@code CombineFilters}).
     */
    private void combine(float[] mask1Real, float[] mask1Imag, float[] mask2Real, float[] mask2Imag, int qmfBand,
                         float weight1, float weight2, int which) {
        var crossReal = 0.0f;
        var crossImag = 0.0f;
        var norm1 = 0.0f;
        var norm2 = 0.0f;
        for (var i = 0; i < slots; i++) {
            norm1 += mask1Real[i] * mask1Real[i] + mask1Imag[i] * mask1Imag[i];
            norm2 += mask2Real[i] * mask2Real[i] + mask2Imag[i] * mask2Imag[i];
            crossReal += mask1Real[i] * mask2Real[i] + mask1Imag[i] * mask2Imag[i];
            crossImag += -mask1Real[i] * mask2Imag[i] + mask2Real[i] * mask1Imag[i];
        }
        var rReal = (float) (crossReal / (Math.sqrt(norm1 * norm2) + ABS_THR));
        var rImag = (float) (crossImag / (Math.sqrt(norm1 * norm2) + ABS_THR));
        var cfb = (float) (Math.sqrt(norm1) / (Math.sqrt(norm2) + ABS_THR));
        var phi = (float) StrictMath.atan2(rImag, rReal);
        if (qmfBand == 0) {
            phase[which] = phi;
        }
        var phiInt = (float) (int) (phase[which] / (2 * PI));
        var phiFract = phase[which] - 2 * PI * phiInt;
        if (phiFract > PI) {
            phiFract -= 2 * PI;
            phiInt += 1;
        }
        if (Math.abs(phi - phiFract) > Math.abs(2 * PI + phi - phiFract)) {
            phi += 2 * PI;
        }
        if (Math.abs(phi - phiFract) > Math.abs(-2 * PI + phi - phiFract)) {
            phi -= 2 * PI;
        }
        phi += phiInt * 2 * PI;
        phase[which] = phi;
        var icc = (float) Math.sqrt(rReal * rReal + rImag * rImag);
        var gain2 = surroundGain * surroundGain;
        var w1tau = weight1 / (weight1 + weight2 * gain2 + ABS_THR);
        var w2tau = 1 - w1tau;
        var a1 = (float) Math.sqrt(weight1);
        var a2 = (float) Math.sqrt(weight2);
        var t1Real = (float) (a1 * StrictMath.cos(phi * w2tau));
        var t1Imag = (float) (-a1 * StrictMath.sin(phi * w2tau));
        var t2Real = (float) (a2 * StrictMath.cos(phi * w1tau) * surroundGain);
        var t2Imag = (float) (a2 * StrictMath.sin(phi * w1tau) * surroundGain);
        var target = weight1 * cfb * cfb + weight2 * gain2;
        var intermediate = weight1 * cfb * cfb + weight2 * gain2 + 2 * a1 * a2 * cfb * icc * surroundGain;
        var compensation = (float) Math.sqrt(target / (intermediate + ABS_THR));
        t1Real *= compensation;
        t1Imag *= compensation;
        t2Real *= compensation;
        t2Imag *= compensation;
        var outReal = sideReal[which];
        var outImag = sideImag[which];
        for (var i = 0; i < slots; i++) {
            var real = mask1Real[i] * t1Real - mask1Imag[i] * t1Imag;
            var imag = mask1Real[i] * t1Imag + mask1Imag[i] * t1Real;
            real += mask2Real[i] * t2Real - mask2Imag[i] * t2Imag;
            imag += mask2Real[i] * t2Imag + mask2Imag[i] * t2Real;
            outReal[i] = real;
            outImag[i] = imag;
        }
    }

    /**
     * {@return the gain that makes up for the energy a prediction band loses in the mix of the side and center
     * responses, at most twice the center ICC}
     */
    private float predictionGain(float[] leftReal, float[] leftImag, float[] rightReal, float[] rightImag,
                                 float[] centerReal, float[] centerImag, float l, float r, float c, float dE,
                                 float icc) {
        var powerLeft = 0.0f;
        var powerRight = 0.0f;
        var powerCenter = 0.0f;
        var powerDifference = 0.0f;
        for (var i = 0; i < slots; i++) {
            powerLeft += leftReal[i] * leftReal[i] + leftImag[i] * leftImag[i];
            powerRight += rightReal[i] * rightReal[i] + rightImag[i] * rightImag[i];
            powerCenter += 2 * (centerReal[i] * centerReal[i] + centerImag[i] * centerImag[i]);
            var differenceReal = leftReal[i] + rightReal[i] - SQRT2 * centerReal[i];
            var differenceImag = leftImag[i] + rightImag[i] - SQRT2 * centerImag[i];
            powerDifference += differenceReal * differenceReal + differenceImag * differenceImag;
        }
        var energy = l * powerLeft + r * powerRight + c * powerCenter;
        var difference = dE * powerDifference;
        return (float) (icc * Math.min(2, Math.sqrt((energy + ABS_THR)
                / (Math.abs((double) (energy - difference)) + ABS_THR))));
    }

    /**
     * Adds the filters of the next parameter set ({@code SpatialDecUpdateHrtfFilterbank}).
     *
     * @param mReal the real parts of the pre-matrix by parameter band, {@code row * 5 + column}: the left, right and
     *              center rows of the downmix columns, then the residual columns
     * @param mImag the imaginary parts of the pre-matrix
     * @param lf the power of the left front channel in its side, by parameter band
     * @param ls the power of the left surround channel in its side
     * @param rf the power of the right front channel in its side
     * @param rs the power of the right surround channel in its side
     * @param c1 the first prediction coefficient or TTT CLD of each band
     * @param c2 the second prediction coefficient or TTT CLD of each band
     * @param iccCenter the TTT ICC of each band
     * @param prediction whether each band is in a prediction mode
     * @param parameterSlot the slot the set applies at
     */
    void update(float[][] mReal, float[][] mImag, float[] lf, float[] ls, float[] rf, float[] rs, float[] c1,
                float[] c2, float[] iccCenter, boolean[] prediction, int parameterSlot) {
        var ps = filterSets + 1;
        filterSlot[ps] = parameterSlot;
        var left = masks.leftReal();
        var leftImag = masks.leftImag();
        var right = masks.rightReal();
        var rightImag = masks.rightImag();
        for (var hs = 0; hs < hybridBands; hs++) {
            var sign = hs < 2 ? -1 : 1;
            var qs = MpsDecorrelator.qmfBand(hs);
            var pb = kernelBand[hs];
            combine(left[0][qs], leftImag[0][qs], left[3][qs], leftImag[3][qs], qs, lf[pb], ls[pb], 0);
            combine(right[0][qs], rightImag[0][qs], right[3][qs], rightImag[3][qs], qs, lf[pb], ls[pb], 1);
            combine(left[1][qs], leftImag[1][qs], left[4][qs], leftImag[4][qs], qs, rf[pb], rs[pb], 2);
            combine(right[1][qs], rightImag[1][qs], right[4][qs], rightImag[4][qs], qs, rf[pb], rs[pb], 3);
            var alpha = (1 - c1[pb]) / 3;
            var beta = (1 - c2[pb]) / 3;
            var sigma = alpha + beta;
            if (Math.abs(alpha) < ABS_THR) {
                alpha = 0;
            }
            if (Math.abs(beta) < ABS_THR) {
                beta = 0;
            }
            if (Math.abs(1 - sigma) < ABS_THR) {
                sigma = 1;
            }
            var p = alpha * beta;
            var gain1 = 1.0f;
            var gain2 = 1.0f;
            if (prediction[pb] && alpha > 0 && beta > 0 && sigma < 1) {
                var l = beta * (1 - sigma);
                var r = alpha * (1 - sigma);
                var dE = p * (1 - sigma);
                gain1 = predictionGain(sideReal[0], sideImag[0], sideReal[2], sideImag[2], left[2][qs],
                        leftImag[2][qs], l, r, p, dE, iccCenter[pb]);
                gain2 = predictionGain(sideReal[1], sideImag[1], sideReal[3], sideImag[3], right[2][qs],
                        rightImag[2][qs], l, r, p, dE, iccCenter[pb]);
            }
            var real = filterReal[ps];
            var imag = filterImag[ps];
            var mr = mReal[pb];
            var mi = mImag[pb];
            for (var col = 0; col < INPUTS; col++) {
                var at = (hs * OUTPUTS * INPUTS + col) * slots;
                filter(real, imag, at, mr, mi, col, sign, sideReal[0], sideImag[0], sideReal[2], sideImag[2],
                        left[2][qs], leftImag[2][qs], gain1);
                at = (hs * OUTPUTS * INPUTS + INPUTS + col) * slots;
                filter(real, imag, at, mr, mi, col, sign, sideReal[1], sideImag[1], sideReal[3], sideImag[3],
                        right[2][qs], rightImag[2][qs], gain2);
            }
        }
        filterSets++;
    }

    private void filter(float[] real, float[] imag, int at, float[] mr, float[] mi, int col, int sign, float[] leftReal,
                        float[] leftImag, float[] rightReal, float[] rightImag, float[] centerReal, float[] centerImag,
                        float gain) {
        var m0r = mr[col];
        var m0i = sign * mi[col];
        var m1r = mr[5 + col];
        var m1i = sign * mi[5 + col];
        var m2r = mr[10 + col];
        var m2i = sign * mi[10 + col];
        for (var i = 0; i < slots; i++) {
            var re = m0r * leftReal[i] - m0i * leftImag[i];
            var im = m0r * leftImag[i] + m0i * leftReal[i];
            re += m1r * rightReal[i] - m1i * rightImag[i];
            im += m1r * rightImag[i] + m1i * rightReal[i];
            re += m2r * centerReal[i] - m2i * centerImag[i];
            im += m2r * centerImag[i] + m2i * centerReal[i];
            real[at + i] = re * gain;
            imag[at + i] = im * gain;
        }
    }

    /**
     * Filters one frame ({@code SpatialDecApplyHrtfFilterbank}) and consumes the filters added for it.
     *
     * @param inputReal the real parts of the input channels, {@code [channel][slot][hybrid band]}
     * @param inputImag the imaginary parts of the input channels
     * @param outputReal receives the real parts of the two output channels
     * @param outputImag receives the imaginary parts of the two output channels
     * @param inputs the number of input channels: 2, or 4 with the arbitrary downmix residual
     */
    void apply(float[][][] inputReal, float[][][] inputImag, float[][][] outputReal, float[][][] outputImag,
               int inputs) {
        for (var ps = 0; ps < filterSets; ps++) {
            var previous = filterSlot[ps];
            var current = filterSlot[ps + 1];
            var fromReal = filterReal[ps];
            var fromImag = filterImag[ps];
            var toReal = filterReal[ps + 1];
            var toImag = filterImag[ps + 1];
            for (var ts = previous + 1; ts <= current; ts++) {
                var alpha = (float) (ts - previous) / (current - previous);
                var beta = 1.0f - alpha;
                for (var hs = 0; hs < hybridBands; hs++) {
                    for (var row = 0; row < OUTPUTS; row++) {
                        var outReal = 0.0f;
                        var outImag = 0.0f;
                        for (var col = 0; col < inputs; col++) {
                            var at = ((hs * OUTPUTS + row) * INPUTS + col) * slots;
                            var xr = inputReal[col][ts][hs];
                            var xi = inputImag[col][ts][hs];
                            for (var i = 0; i < slots; i++) {
                                var fr = beta * fromReal[at + i] + alpha * toReal[at + i];
                                var fi = beta * fromImag[at + i] + alpha * toImag[at + i];
                                stateReal[at + i] += fr * xr - fi * xi;
                                stateImag[at + i] += fr * xi + fi * xr;
                            }
                            outReal += stateReal[at];
                            outImag += stateImag[at];
                            System.arraycopy(stateReal, at + 1, stateReal, at, slots - 1);
                            System.arraycopy(stateImag, at + 1, stateImag, at, slots - 1);
                            stateReal[at + slots - 1] = 0;
                            stateImag[at + slots - 1] = 0;
                        }
                        outputReal[row][ts][hs] = outReal;
                        outputImag[row][ts][hs] = outImag;
                    }
                }
            }
        }
        if (filterSets > 0) {
            System.arraycopy(filterReal[filterSets], 0, filterReal[0], 0, filterReal[0].length);
            System.arraycopy(filterImag[filterSets], 0, filterImag[0], 0, filterImag[0].length);
        }
        filterSets = 0;
    }
}
