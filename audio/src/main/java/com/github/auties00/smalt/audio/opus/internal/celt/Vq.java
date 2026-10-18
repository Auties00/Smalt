/* Ports libopus 1.5.2 (celt/vq.c). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Quantizes and reconstructs unit-norm band shapes with pyramid vector quantization (RFC 6716
 * section 4.3.4), including the spreading rotation and the stereo angle estimate.
 *
 * @implNote This implementation takes the scratch arrays the reference allocates on the stack from the caller.
 */
public final class Vq {
    /**
     * The spreading strength of each spreading decision ({@code SPREAD_FACTOR}).
     */
    private static final int[] SPREAD_FACTOR = {15, 10, 5};

    private Vq() {
        throw new AssertionError();
    }

    /**
     * Spreads (or, with {@code dir < 0}, un-spreads) the energy of a band shape to avoid tonal
     * artifacts from sparse pulses ({@code exp_rotation}).
     *
     * @param X      the vector
     * @param xOff   the index of the first sample
     * @param len    the vector length
     * @param dir    1 to spread, -1 to undo
     * @param stride the number of interleaved short blocks
     * @param K      the number of pulses
     * @param spread the spreading decision
     */
    public static void expRotation(float[] X, int xOff, int len, int dir, int stride, int K, int spread) {
        if (2 * K >= len || spread == Celt.SPREAD_NONE) {
            return;
        }
        var factor = SPREAD_FACTOR[spread - 1];
        var gain = (1.0f * len) / (float) (len + factor * K);
        var theta = .5f * (gain * gain);
        var c = MathOps.celtCosNorm(theta);
        var s = MathOps.celtCosNorm(1.0f - theta);
        var stride2 = 0;
        if (len >= 8 * stride) {
            stride2 = 1;
            while ((stride2 * stride2 + stride2) * stride + (stride >> 2) < len) {
                stride2++;
            }
        }
        len = Integer.divideUnsigned(len, stride);
        for (var i = 0; i < stride; i++) {
            if (dir < 0) {
                if (stride2 != 0) {
                    CeltKernels.INSTANCE.expRotation1(X, xOff + i * len, len, stride2, s, c);
                }
                CeltKernels.INSTANCE.expRotation1(X, xOff + i * len, len, 1, c, s);
            } else {
                CeltKernels.INSTANCE.expRotation1(X, xOff + i * len, len, 1, c, -s);
                if (stride2 != 0) {
                    CeltKernels.INSTANCE.expRotation1(X, xOff + i * len, len, stride2, s, -c);
                }
            }
        }
    }

    /**
     * Scales the decoded pulses to the unit-norm shape times {@code gain}
     * ({@code normalise_residual}).
     *
     * @param iy   the pulse vector
     * @param X    receives the shape
     * @param xOff the index of the first sample
     * @param N    the vector length
     * @param Ryy  the squared norm of the pulses
     * @param gain the target norm
     */
    private static void normalizeResidual(int[] iy, float[] X, int xOff, int N, float Ryy, float gain) {
        var g = MathOps.celtRsqrt(Ryy) * gain;
        var i = 0;
        do {
            X[xOff + i] = g * iy[i];
        } while (++i < N);
    }

    /**
     * Returns a bit mask of the short blocks that received at least one pulse
     * ({@code extract_collapse_mask}).
     *
     * @param iy the pulse vector
     * @param N  the vector length
     * @param B  the number of interleaved short blocks
     * @return the collapse mask
     */
    private static int extractCollapseMask(int[] iy, int N, int B) {
        if (B <= 1) {
            return 1;
        }
        var N0 = Integer.divideUnsigned(N, B);
        var collapseMask = 0;
        var i = 0;
        do {
            var tmp = 0;
            var j = 0;
            do {
                tmp |= iy[i * N0 + j];
            } while (++j < N0);
            collapseMask |= (tmp != 0 ? 1 : 0) << i;
        } while (++i < B);
        return collapseMask;
    }

    /**
     * Finds the pulse vector with {@code K} pulses closest in angle to {@code X}
     * ({@code op_pvq_search_c}).
     *
     * <p>{@code X} is replaced by its absolute value.
     *
     * @param X     the target shape; overwritten with its absolute values
     * @param xOff  the index of the first sample
     * @param iy    receives the signed pulse vector
     * @param K     the number of pulses
     * @param N     the vector length
     * @param y     scratch of at least {@code N} floats
     * @param signx scratch of at least {@code N} ints
     * @return the squared norm of the pulse vector
     */
    public static float opPvqSearch(float[] X, int xOff, int[] iy, int K, int N, float[] y, int[] signx) {
        int i, j;
        int pulsesLeft;
        float sum;
        float xy;
        float yy;
        sum = 0;
        j = 0;
        do {
            signx[j] = X[xOff + j] < 0 ? 1 : 0;
            X[xOff + j] = Math.abs(X[xOff + j]);
            iy[j] = 0;
            y[j] = 0;
        } while (++j < N);
        xy = yy = 0;
        pulsesLeft = K;
        if (K > (N >> 1)) {
            float rcp;
            j = 0;
            do {
                sum += X[xOff + j];
            } while (++j < N);
            if (!(sum > MathOps.EPSILON && sum < 64)) {
                X[xOff] = 1.f;
                j = 1;
                do {
                    X[xOff + j] = 0;
                } while (++j < N);
                sum = 1.f;
            }
            rcp = (K + 0.8f) * MathOps.celtRcp(sum);
            j = 0;
            do {
                iy[j] = (int) Math.floor(rcp * X[xOff + j]);
                y[j] = (float) iy[j];
                yy = yy + y[j] * y[j];
                xy = xy + X[xOff + j] * y[j];
                y[j] *= 2;
                pulsesLeft -= iy[j];
            } while (++j < N);
        }
        if (pulsesLeft > N + 3) {
            var tmp = (float) pulsesLeft;
            yy = yy + tmp * tmp;
            yy = yy + tmp * y[0];
            iy[0] += pulsesLeft;
            pulsesLeft = 0;
        }
        for (i = 0; i < pulsesLeft; i++) {
            int bestId;
            yy = yy + 1;
            bestId = CeltKernels.INSTANCE.pvqSearchBest(X, xOff, y, N, xy, yy);
            xy = xy + X[xOff + bestId];
            yy = yy + y[bestId];
            y[bestId] += 2;
            iy[bestId]++;
        }
        j = 0;
        do {
            iy[j] = (iy[j] ^ -signx[j]) + signx[j];
        } while (++j < N);
        return yy;
    }

    /**
     * Quantizes a band shape with {@code K} pulses and encodes it ({@code alg_quant}).
     *
     * @param X       the unit-norm shape; replaced by its reconstruction when {@code resynth}
     * @param xOff    the index of the first sample
     * @param N       the vector length, at least 2
     * @param K       the number of pulses, positive
     * @param spread  the spreading decision
     * @param B       the number of interleaved short blocks
     * @param enc     the range encoder
     * @param gain    the reconstruction norm
     * @param resynth nonzero to reconstruct the quantized shape in {@code X}
     * @param iy      scratch of at least {@code N + 3} ints
     * @param y       scratch of at least {@code N} floats
     * @param signx   scratch of at least {@code N} ints
     * @return the collapse mask of the short blocks
     */
    public static int algQuant(float[] X, int xOff, int N, int K, int spread, int B, RangeEncoder enc, float gain,
                               int resynth, int[] iy, float[] y, int[] signx) {
        expRotation(X, xOff, N, 1, B, K, spread);
        var yy = opPvqSearch(X, xOff, iy, K, N, y, signx);
        Cwrs.encodePulses(iy, 0, N, K, enc);
        if (resynth != 0) {
            normalizeResidual(iy, X, xOff, N, yy, gain);
            expRotation(X, xOff, N, -1, B, K, spread);
        }
        return extractCollapseMask(iy, N, B);
    }

    /**
     * Decodes a band shape with {@code K} pulses ({@code alg_unquant}).
     *
     * @param X      receives the shape
     * @param xOff   the index of the first sample
     * @param N      the vector length, at least 2
     * @param K      the number of pulses, positive
     * @param spread the spreading decision
     * @param B      the number of interleaved short blocks
     * @param dec    the range decoder
     * @param gain   the reconstruction norm
     * @param iy     scratch of at least {@code N} ints
     * @return the collapse mask of the short blocks
     */
    public static int algUnquant(float[] X, int xOff, int N, int K, int spread, int B, RangeDecoder dec, float gain,
                                 int[] iy) {
        var Ryy = Cwrs.decodePulses(iy, 0, N, K, dec);
        normalizeResidual(iy, X, xOff, N, Ryy, gain);
        expRotation(X, xOff, N, -1, B, K, spread);
        return extractCollapseMask(iy, N, B);
    }

    /**
     * Scales a vector to norm {@code gain} ({@code renormalise_vector}).
     *
     * @param X    the vector
     * @param xOff the index of the first sample
     * @param N    the vector length
     * @param gain the target norm
     */
    public static void renormalizeVector(float[] X, int xOff, int N, float gain) {
        var E = MathOps.EPSILON + Pitch.celtInnerProd(X, xOff, X, xOff, N);
        var g = MathOps.celtRsqrt(E) * gain;
        for (var i = 0; i < N; i++) {
            X[xOff + i] = g * X[xOff + i];
        }
    }

    /**
     * Returns the angle between the mid and side (or the two halves) of a band, in Q14 units of
     * a quarter turn ({@code stereo_itheta}).
     *
     * @param X      the first vector
     * @param xOff   the index of the first sample of {@code X}
     * @param Y      the second vector
     * @param yOff   the index of the first sample of {@code Y}
     * @param stereo nonzero when {@code X} and {@code Y} are left and right channels
     * @param N      the vector length
     * @return the angle, 0 to 16384
     */
    public static int stereoItheta(float[] X, int xOff, float[] Y, int yOff, int stereo, int N) {
        float mid, side;
        float Emid, Eside;
        Emid = Eside = MathOps.EPSILON;
        if (stereo != 0) {
            for (var i = 0; i < N; i++) {
                var m = X[xOff + i] + Y[yOff + i];
                var s = X[xOff + i] - Y[yOff + i];
                Emid = Emid + m * m;
                Eside = Eside + s * s;
            }
        } else {
            Emid += Pitch.celtInnerProd(X, xOff, X, xOff, N);
            Eside += Pitch.celtInnerProd(Y, yOff, Y, yOff, N);
        }
        mid = MathOps.celtSqrt(Emid);
        side = MathOps.celtSqrt(Eside);
        return (int) Math.floor(.5f + 16384 * 0.63662f * MathOps.fastAtan2f(side, mid));
    }
}
