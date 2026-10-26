/* Ports the ISO/IEC 23003-3:2019 reference software (ct_hfgen.c, HFgen_preFlat.c). Copyright Coding
   Technologies, Dolby Laboratories and ISO/IEC 2002-2008; ISO/IEC reference software licence (use in products
   conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Generates the high band of one SBR channel from its core band ({@code generateHF}): the copy-up patches with
 * inverse filtering and optional pre-flattening of ISO/IEC 23003-3 subclause 7.5.3, or the inverse filtering of
 * the harmonic transposer output.
 *
 * <p>Instances keep the bandwidth factors of the previous frame; they are mutable and not thread-safe.
 */
final class EsbrHfGenerator {
    static final int MAX_PATCHES = 6;

    private static final float[] INVERSE_FILTERING = {0, 0.6f, 0.75f, 0.9f, 0.98f};
    private static final float RELAXATION = 1 / (1 + 1e-6f);

    /**
     * The number of patches of the last frame.
     */
    int patches;

    /**
     * The first target subband of each patch of the last frame.
     */
    final int[] patchStart = new int[MAX_PATCHES + 1];

    private final float[] bandwidth = new float[MAX_PATCHES];
    private final float[] previousBandwidth = new float[MAX_PATCHES];
    private boolean initialized;

    private final float[] alphaReal0 = new float[64];
    private final float[] alphaImag0 = new float[64];
    private final float[] alphaReal1 = new float[64];
    private final float[] alphaImag1 = new float[64];
    private final float[] gains = new float[64];
    private final float[] correlation = new float[9];
    private final float[] lowEnvelope = new float[64];
    private final float[] slope = new float[64];
    private final float[] positions = new float[64];
    private final float[] polynomial = new float[4];
    private final float[][] matrix = new float[4][4];
    private final float[] vector = new float[4];
    private final float[] powers = new float[7];

    /**
     * Constructs a generator.
     */
    EsbrHfGenerator() {
        for (var i = 0; i < 64; i++) {
            positions[i] = i;
        }
    }

    /**
     * Generates the high band of one frame.
     *
     * @param sourceReal the real parts of the core band QMF slots
     * @param sourceImag the imaginary parts of the core band QMF slots
     * @param sourceBase the index of the frame's first source slot; the two slots before it must exist
     * @param transposedReal the real parts of the transposed QMF slots, or {@code null} without the transposer
     * @param transposedImag the imaginary parts of the transposed QMF slots
     * @param transposedBase the index of the frame's first transposed slot
     * @param harmonic whether the element uses the harmonic transposer
     * @param frame the channel's frame data
     * @param bands the channel's band tables
     * @param sampleRate the output sampling rate
     * @param targetReal receives the real parts of the high band
     * @param targetImag receives the imaginary parts of the high band
     * @param targetBase the index of the frame's first target slot
     * @param sbr41 whether the element uses the 4:1 ratio
     */
    void generate(float[][] sourceReal, float[][] sourceImag, int sourceBase, float[][] transposedReal,
                  float[][] transposedImag, int transposedBase, boolean harmonic, EsbrFrame frame,
                  EsbrFrequencies bands, int sampleRate, float[][] targetReal, float[][] targetImag, int targetBase,
                  boolean sbr41) {
        var frameInfo = frame.frameInfo;
        var master = bands.master;
        var numMaster = bands.masterBands;
        var lsb = master[0];
        var usb = master[numMaster];
        var highBandStart = bands.lowSubband;
        var xoverOffset = highBandStart - master[0];
        var firstSlot = frameInfo[1];
        var lastSlot = frameInfo[frameInfo[0] + 1] - 16;
        if (!initialized) {
            initialized = true;
            Arrays.fill(previousBandwidth, 0);
            Arrays.fill(frame.previousInverseFiltering, 0);
        }
        int startSample;
        int stopSample;
        if (sbr41) {
            startSample = firstSlot * 4;
            stopSample = 64 + lastSlot * 4;
        } else {
            startSample = firstSlot * 2;
            stopSample = 32 + lastSlot * 2;
        }
        if (frame.header.preFlattening) {
            gainVector(sourceReal, sourceImag, sourceBase, master[0], startSample, stopSample);
        }
        var noiseTable = bands.noise;
        var noiseBands = bands.noiseBands;
        for (var i = 0; i < noiseBands; i++) {
            var mode = frame.inverseFiltering[i];
            var previous = frame.previousInverseFiltering[i];
            float value;
            switch (mode) {
                case 1 -> value = previous == 0 ? INVERSE_FILTERING[1] : INVERSE_FILTERING[2];
                case 2 -> value = INVERSE_FILTERING[3];
                case 3 -> value = INVERSE_FILTERING[4];
                default -> value = previous == 1 ? INVERSE_FILTERING[1] : INVERSE_FILTERING[0];
            }
            if (value < previousBandwidth[i]) {
                value = 0.75000f * value + 0.25000f * previousBandwidth[i];
            } else {
                value = 0.90625f * value + 0.09375f * previousBandwidth[i];
            }
            if (value < 0.015625) {
                value = 0;
            }
            if (value >= 0.99609375f) {
                value = 0.99609375f;
            }
            bandwidth[i] = value;
        }
        for (var i = startSample; i < stopSample; i++) {
            Arrays.fill(targetReal[targetBase + i], usb, 64, 0.0f);
            Arrays.fill(targetImag[targetBase + i], usb, 64, 0.0f);
        }
        var correlationLength = sbr41 ? 76 : 38;
        var patch = 0;
        var patchingMode = frame.patchingMode;
        if (patchingMode != 0 || !harmonic) {
            for (var loBand = 1; loBand < master[0]; loBand++) {
                autoCorrelate(sourceReal, sourceImag, sourceBase, loBand, correlationLength);
                predict(loBand);
            }
            var goal = (int) (2.048e6f / sampleRate + 0.5f);
            goal = closest(goal, master, numMaster, true);
            var sourceStart = xoverOffset + 1;
            var targetStop = lsb + xoverOffset;
            var guard = 0;
            while (targetStop < usb) {
                if (patch > MAX_PATCHES || ++guard > 64) {
                    throw new MediaException.Malformed("SBR band layout needs too many patches");
                }
                patchStart[patch] = targetStop;
                var count = goal - targetStop;
                int distance;
                if (count >= lsb - sourceStart) {
                    distance = targetStop - sourceStart;
                    distance = distance & ~1;
                    count = lsb - (targetStop - distance);
                    count = closest(targetStop + count, master, numMaster, false) - targetStop;
                }
                distance = count + targetStop - lsb;
                distance = (distance + 1) & ~1;
                sourceStart = 1;
                if (goal - (targetStop + count) < 3) {
                    goal = usb;
                }
                if (count < 3 && patch > 0 && targetStop + count == usb) {
                    for (var i = startSample; i < stopSample; i++) {
                        for (var hiBand = targetStop; hiBand < targetStop + count; hiBand++) {
                            targetReal[targetBase + i][hiBand] = 0.0f;
                            targetImag[targetBase + i][hiBand] = 0.0f;
                        }
                    }
                    break;
                }
                if (count <= 0) {
                    continue;
                }
                for (var hiBand = targetStop; hiBand < targetStop + count; hiBand++) {
                    var loBand = hiBand - distance;
                    if (loBand < 0 || loBand >= 64) {
                        throw new MediaException.Malformed("SBR patch reads outside the core band");
                    }
                    var bwIndex = 0;
                    while (hiBand >= noiseTable[1 + bwIndex]) {
                        bwIndex++;
                    }
                    var bw = bandwidth[bwIndex];
                    var a0r = bw * alphaReal0[loBand];
                    var a0i = bw * alphaImag0[loBand];
                    bw *= bw;
                    var a1r = bw * alphaReal1[loBand];
                    var a1i = bw * alphaImag1[loBand];
                    var gain = frame.header.preFlattening ? gains[loBand] : 1.0f;
                    for (var i = startSample; i < stopSample; i++) {
                        var s = sourceBase + i;
                        var re = sourceReal[s][loBand] * gain;
                        var im = sourceImag[s][loBand] * gain;
                        if (bw > 0.0f) {
                            re += (a0r * sourceReal[s - 1][loBand] - a0i * sourceImag[s - 1][loBand]
                                    + a1r * sourceReal[s - 2][loBand] - a1i * sourceImag[s - 2][loBand]) * gain;
                            im += (a0i * sourceReal[s - 1][loBand] + a0r * sourceImag[s - 1][loBand]
                                    + a1i * sourceReal[s - 2][loBand] + a1r * sourceImag[s - 2][loBand]) * gain;
                        }
                        targetReal[targetBase + i][hiBand] = re;
                        targetImag[targetBase + i][hiBand] = im;
                    }
                }
                targetStop += count;
                patch++;
            }
        }
        if (harmonic && patchingMode == 0) {
            var bwIndex = 0;
            patch = 1;
            for (var hiBand = highBandStart; hiBand < usb; hiBand++) {
                autoCorrelate(transposedReal, transposedImag, transposedBase, hiBand, correlationLength);
                predict(0);
                while (hiBand >= noiseTable[1 + bwIndex]) {
                    bwIndex++;
                }
                var bw = bandwidth[bwIndex];
                var a0r = bw * alphaReal0[0];
                var a0i = bw * alphaImag0[0];
                bw *= bw;
                var a1r = bw * alphaReal1[0];
                var a1i = bw * alphaImag1[0];
                for (var i = startSample; i < stopSample; i++) {
                    var s = transposedBase + i;
                    var realTarget = transposedReal[s][hiBand];
                    var imagTarget = transposedImag[s][hiBand];
                    if (bw > 0.0f) {
                        var real1 = transposedReal[s - 1][hiBand];
                        var imag1 = transposedImag[s - 1][hiBand];
                        var real2 = transposedReal[s - 2][hiBand];
                        var imag2 = transposedImag[s - 2][hiBand];
                        realTarget += (a0r * real1 - a0i * imag1) + (a1r * real2 - a1i * imag2);
                        imagTarget += (a0i * real1 + a0r * imag1) + (a1i * real2 + a1r * imag2);
                    }
                    targetReal[targetBase + i][hiBand] = realTarget;
                    targetImag[targetBase + i][hiBand] = imagTarget;
                }
            }
        }
        patches = patch;
        System.arraycopy(bandwidth, 0, previousBandwidth, 0, noiseBands);
    }

    private void predict(int index) {
        var r11r = correlation[0];
        var r01r = correlation[1];
        var r01i = correlation[2];
        var r02r = correlation[3];
        var r02i = correlation[4];
        var r12r = correlation[5];
        var r12i = correlation[6];
        var det = correlation[8];
        float a1r;
        float a1i;
        if (det == 0.0f) {
            a1r = 0;
            a1i = 0;
        } else {
            var fac = 1.0f / det;
            a1r = (r01r * r12r - r01i * r12i - r02r * r11r) * fac;
            a1i = (r01i * r12r + r01r * r12i - r02i * r11r) * fac;
        }
        float a0r;
        float a0i;
        if (r11r == 0) {
            a0r = 0;
            a0i = 0;
        } else {
            var fac = 1.0f / r11r;
            a0r = -(r01r + a1r * r12r + a1i * r12i) * fac;
            a0i = -(r01i + a1i * r12r - a1r * r12i) * fac;
        }
        if (a0r * a0r + a0i * a0i >= 16.0f || a1r * a1r + a1i * a1i >= 16.0f) {
            a0r = 0.0f;
            a0i = 0.0f;
            a1r = 0.0f;
            a1i = 0.0f;
        }
        alphaReal0[index] = a0r;
        alphaImag0[index] = a0i;
        alphaReal1[index] = a1r;
        alphaImag1[index] = a1i;
    }

    private void autoCorrelate(float[][] real, float[][] imag, int base, int band, int length) {
        float r01r = 0;
        float r01i = 0;
        float r02r = 0;
        float r02i = 0;
        float r11r = 0;
        float r12r = 0;
        float r12i = 0;
        float r22r = 0;
        for (var j = 0; j < length; j++) {
            var x0r = real[base + j][band];
            var x0i = imag[base + j][band];
            var x1r = real[base + j - 1][band];
            var x1i = imag[base + j - 1][band];
            var x2r = real[base + j - 2][band];
            var x2i = imag[base + j - 2][band];
            r01r += x0r * x1r + x0i * x1i;
            r01i += x0i * x1r - x0r * x1i;
            r02r += x0r * x2r + x0i * x2i;
            r02i += x0i * x2r - x0r * x2i;
            r11r += x1r * x1r + x1i * x1i;
            r12r += x1r * x2r + x1i * x2i;
            r12i += x1i * x2r - x1r * x2i;
            r22r += x2r * x2r + x2i * x2i;
        }
        correlation[0] = r11r;
        correlation[1] = r01r;
        correlation[2] = r01i;
        correlation[3] = r02r;
        correlation[4] = r02i;
        correlation[5] = r12r;
        correlation[6] = r12i;
        correlation[7] = r22r;
        correlation[8] = r11r * r22r - RELAXATION * (r12r * r12r + r12i * r12i);
    }

    private static int closest(int goal, int[] master, int numMaster, boolean up) {
        if (goal <= master[0]) {
            return master[0];
        }
        if (goal >= master[numMaster]) {
            return master[numMaster];
        }
        int index;
        if (up) {
            index = 0;
            while (master[index] < goal) {
                index++;
            }
        } else {
            index = numMaster;
            while (master[index] > goal) {
                index--;
            }
        }
        return master[index];
    }

    private void gainVector(float[][] real, float[][] imag, int base, int count, int start, int stop) {
        Arrays.fill(slope, 0);
        for (var band = 0; band < count; band++) {
            var energy = 0.0f;
            for (var i = start; i < stop; i++) {
                energy += real[base + i][band] * real[base + i][band] + imag[base + i][band] * imag[base + i][band];
            }
            energy /= (stop - start);
            lowEnvelope[band] = energy;
        }
        for (var band = 0; band < count; band++) {
            lowEnvelope[band] = (float) (10 * StrictMath.log10(lowEnvelope[band] + 1));
        }
        polyfit(3, count, positions, lowEnvelope, polynomial);
        for (var k = 3; k >= 0; k--) {
            for (var i = 0; i < count; i++) {
                slope[i] = slope[i] + (float) (StrictMath.pow(positions[i], k) * polynomial[3 - k]);
            }
        }
        var mean = 0.0f;
        for (var i = 0; i < count; i++) {
            mean += lowEnvelope[i];
        }
        mean /= count;
        for (var i = 0; i < count; i++) {
            gains[i] = (float) StrictMath.pow(10, (mean - slope[i]) / 20.0f);
        }
    }

    private void polyfit(int degree, int n, float[] x, float[] y, float[] p) {
        for (var i = 0; i <= degree; i++) {
            vector[i] = 0.0f;
            Arrays.fill(matrix[i], 0.0f);
        }
        for (var k = 0; k < n; k++) {
            powers[0] = 1.0f;
            for (var i = 1; i <= 2 * degree; i++) {
                powers[i] = x[k] * powers[i - 1];
            }
            for (var i = 0; i <= degree; i++) {
                vector[i] += powers[degree - i] * y[k];
                for (var j = 0; j <= degree; j++) {
                    matrix[i][j] += powers[2 * degree - i - j];
                }
            }
        }
        solve(degree + 1, matrix, vector, p);
    }

    private static void solve(int n, float[][] a, float[] b, float[] y) {
        var eps = 1.0e-9f;
        for (var i = 0; i < n; i++) {
            var max = i;
            for (var k = i + 1; k < n; k++) {
                if (Math.abs(a[k][i]) > Math.abs(a[max][i])) {
                    max = k;
                }
            }
            if (max != i) {
                var v = b[max];
                b[max] = b[i];
                b[i] = v;
                for (var j = i; j < n; j++) {
                    v = a[max][j];
                    a[max][j] = a[i][j];
                    a[i][j] = v;
                }
            }
            var v = a[i][i];
            if (Math.abs(v) > eps) {
                b[i] /= v;
                for (var j = i; j < n; j++) {
                    a[i][j] /= v;
                }
            }
            for (var k = i + 1; k < n; k++) {
                v = a[k][i];
                b[k] -= v * b[i];
                for (var j = i + 1; j < n; j++) {
                    a[k][j] -= v * a[i][j];
                }
            }
        }
        for (var i = n - 1; i >= 0; i--) {
            y[i] = b[i];
            for (var j = i + 1; j < n; j++) {
                y[i] -= a[i][j] * y[j];
            }
        }
    }
}
