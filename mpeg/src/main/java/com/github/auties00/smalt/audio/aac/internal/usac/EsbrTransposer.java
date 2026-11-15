/* Ports the ISO/IEC 23003-3:2019 reference software (harmsbrQmftrans.c, and CQMFanalysis and RQMFsynthesis of
   ct_polyphase.c). Copyright Dolby Laboratories, Fraunhofer IIS, Panasonic, Coding Technologies and ISO/IEC
   2002-2008; ISO/IEC reference software licence (use in products conforming to ISO/IEC 23003).
   Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import java.util.Arrays;

/**
 * Transposes the core band of one SBR channel into the high band with the QMF based harmonic transposer of
 * ISO/IEC 23003-3 subclause 7.5.4.
 *
 * <p>The transposer resynthesizes the upper core subbands at a reduced rate, analyzes them again with a bank of
 * twice the resolution and stretches the phases of each subband by the transposition order. Instances are
 * mutable and not thread-safe.
 */
final class EsbrTransposer {
    private static final int WINDOW = 12 + 6 - 4 - 1;
    private static final int SYNTH_CHANNELS = 64;
    private static final int SLOT_OFFSET = 6;
    private static final float THRESHOLD = 1.0f;
    private static final float PITCH_MINIMUM = 1.0f;
    private static final int MAX_STRETCH = 4;
    private static final double PI = 3.14159265358979323846264338327950288;
    private static final int[] START_SUBBAND = {
            0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 4, 4, 4, 4, 4, 6,
            6, 6, 8, 8, 8, 8, 8, 10, 10, 10, 12, 12, 12, 12, 12, 12, 12
    };

    /**
     * The crossover subbands of the transposition orders.
     */
    final int[] crossover = new int[EsbrHfGenerator.MAX_PATCHES];

    /**
     * The highest transposition order.
     */
    int maxStretch;

    private final int frameSize;
    private final int columns;
    private final boolean sbr41;
    private final int inputSize;
    private final int outputSize;
    private final float[] input;
    private final float[][] inReal;
    private final float[][] inImag;
    private final float[][] outReal;
    private final float[][] outImag;
    private final double[] synthesisBuffer = new double[1280];
    private final double[] analysisBuffer = new double[640];
    private final double[] synthesisMatrix = new double[40 * 20];
    private final double[] analysisReal = new double[40 * 80];
    private final double[] analysisImag = new double[40 * 80];
    private double[] synthesisPrototype;
    private double[] analysisPrototype;
    private final double[] rotation = new double[2 * 32];
    private final float[] temporary = new float[32];
    private final float[] zeros = new float[64];
    private final double[] scratchU = new double[640];
    private final double[] scratchY = new double[128];
    private final float[] gamma0Real = new float[32];
    private final float[] gamma0Imag = new float[32];
    private final float[] gamma1Real = new float[32];
    private final float[] gamma1Imag = new float[32];
    private final float[] grainReal = new float[WINDOW];
    private final float[] grainImag = new float[WINDOW];
    private final float[] window = new float[WINDOW];
    private final float[][] hintReal = new float[3][2];
    private final float[][] hintImag = new float[3][2];
    private final float[] vectorReal = new float[2];
    private final float[] vectorImag = new float[2];
    private final float[] outputReal = new float[2];
    private final float[] outputImag = new float[2];
    private final float[] hReal = new float[2];
    private final float[] hImag = new float[2];
    private int kstart;
    private int synthSize;
    private int startBand;
    private int stopBand;

    /**
     * Constructs a transposer.
     *
     * @param frameSize the core frame length, 768 or 1024
     * @param sbr41 whether the element uses the 4:1 ratio
     */
    EsbrTransposer(int frameSize, boolean sbr41) {
        this.frameSize = frameSize;
        this.sbr41 = sbr41;
        this.columns = frameSize == 768 ? (8 * frameSize / 3) / SYNTH_CHANNELS
                : ((sbr41 ? 1 : 0) + 1) * 2 * frameSize / SYNTH_CHANNELS;
        this.inputSize = columns / 2 + WINDOW - 1;
        this.outputSize = 2 * inputSize;
        this.input = new float[frameSize + SYNTH_CHANNELS];
        this.inReal = new float[inputSize][SYNTH_CHANNELS];
        this.inImag = new float[inputSize][SYNTH_CHANNELS];
        this.outReal = new float[outputSize][SYNTH_CHANNELS];
        this.outImag = new float[outputSize][SYNTH_CHANNELS];
    }

    /**
     * Adapts the transposer to new band tables ({@code QmfTransposerReInit}).
     *
     * @param bands the band tables
     */
    void reset(EsbrFrequencies bands) {
        var low = bands.table[EsbrFrequencies.LOW];
        var high = bands.table[EsbrFrequencies.HIGH];
        var lowCount = bands.bands[EsbrFrequencies.LOW];
        var highCount = bands.bands[EsbrFrequencies.HIGH];
        startBand = low[0];
        stopBand = low[lowCount];
        synthSize = 4 * ((startBand + 4) / 8 + 1);
        kstart = START_SUBBAND[Math.min(startBand, 32)];
        if (sbr41) {
            if (kstart + synthSize > 16) {
                kstart = 16 - synthSize;
            }
        } else if (frameSize == 768) {
            if (kstart + synthSize > 24) {
                kstart = 24 - synthSize;
            }
        }
        Arrays.fill(synthesisBuffer, 0);
        var l = synthSize;
        for (var row = 0; row < 2 * l; row++) {
            for (var k = 0; k < l; k++) {
                synthesisMatrix[row * l + k] = 1.0 / l * StrictMath.cos(PI / (2 * l) * (k + 0.5) * (2 * row - l));
            }
        }
        synthesisPrototype = EsbrQmf.interpolate(EsbrTables.QMF_WINDOW, 640, 64, l, 0);
        Arrays.fill(analysisBuffer, 0);
        l = 2 * synthSize;
        for (var k = 0; k < l; k++) {
            for (var col = 0; col < 2 * l; col++) {
                analysisReal[k * 2 * l + col] = StrictMath.cos(PI / (2 * l) * (k + 0.5) * (2 * col - 2 * l));
                analysisImag[k * 2 * l + col] = StrictMath.sin(PI / (2 * l) * (k + 0.5) * (2 * col - 2 * l));
            }
        }
        analysisPrototype = EsbrQmf.interpolate(EsbrTables.QMF_WINDOW, 640, 64, l, 0);
        for (var k = 0; k < synthSize; k++) {
            var ki = kstart + k;
            var angle = PI / 2 * ((ki + 0.5) * 191 / 64.0 - kstart);
            rotation[2 * k] = StrictMath.cos(angle);
            rotation[2 * k + 1] = StrictMath.sin(angle);
        }
        Arrays.fill(crossover, 0);
        var sfb = 0;
        int stopPatch;
        if (sbr41) {
            stopPatch = EsbrHfGenerator.MAX_PATCHES;
            maxStretch = MAX_STRETCH;
        } else {
            stopPatch = MAX_STRETCH;
        }
        for (var patch = 1; patch <= stopPatch; patch++) {
            while (sfb <= lowCount && low[sfb] <= patch * startBand) {
                sfb++;
            }
            if (sfb <= lowCount) {
                if (patch * startBand - low[sfb - 1] <= 3) {
                    crossover[patch - 1] = low[sfb - 1];
                } else {
                    var hsfb = 0;
                    while (hsfb <= highCount && high[hsfb] <= patch * startBand) {
                        hsfb++;
                    }
                    crossover[patch - 1] = high[hsfb - 1];
                }
            } else {
                crossover[patch - 1] = stopBand;
                maxStretch = Math.min(patch, MAX_STRETCH);
                break;
            }
        }
    }

    /**
     * Transposes one frame ({@code QmfTransposerApply}).
     *
     * @param codecReal the real parts of the core band QMF slots
     * @param codecImag the imaginary parts of the core band QMF slots
     * @param codecBase the index of the first slot to transpose
     * @param columnsIn the number of slots to transpose
     * @param transposedReal receives the real parts of the transposed slots
     * @param transposedImag receives the imaginary parts of the transposed slots
     * @param transposedBase the index of the first transposed slot
     * @param pitchInBins {@code sbrPitchInBins}
     */
    void apply(float[][] codecReal, float[][] codecImag, int codecBase, int columnsIn, float[][] transposedReal,
               float[][] transposedImag, int transposedBase, int pitchInBins) {
        var vocoderColumns = columns / 2;
        System.arraycopy(input, columns * synthSize, input, 0, synthSize);
        var i = 0;
        for (; i < columnsIn; i++) {
            var re = codecReal[codecBase + i];
            var im = codecImag[codecBase + i];
            for (var k = 0; k < synthSize; k++) {
                var ki = kstart + k;
                temporary[k] = (float) (rotation[2 * k] * re[ki] + rotation[2 * k + 1] * im[ki]);
            }
            synthesize(temporary, input, (i + 1) * synthSize);
        }
        for (; i < columns; i++) {
            synthesize(zeros, input, (i + 1) * synthSize);
        }
        for (i = 0; i < WINDOW - 1; i++) {
            System.arraycopy(inReal[i + vocoderColumns], 0, inReal[i], 0, SYNTH_CHANNELS);
            System.arraycopy(inImag[i + vocoderColumns], 0, inImag[i], 0, SYNTH_CHANNELS);
        }
        for (i = 0; i < vocoderColumns; i++) {
            Arrays.fill(inReal[i + WINDOW - 1], 0.0f);
            Arrays.fill(inImag[i + WINDOW - 1], 0.0f);
            analyze(input, i * 2 * synthSize + 1, inReal[i + WINDOW - 1], inImag[i + WINDOW - 1], 2 * kstart);
        }
        for (i = 0; i < outputSize - columns; i++) {
            System.arraycopy(outReal[i + columns], 0, outReal[i], 0, SYNTH_CHANNELS);
            System.arraycopy(outImag[i + columns], 0, outImag[i], 0, SYNTH_CHANNELS);
        }
        for (; i < outputSize; i++) {
            Arrays.fill(outReal[i], 0.0f);
            Arrays.fill(outImag[i], 0.0f);
        }
        for (var stretch = 2; stretch <= maxStretch; stretch++) {
            for (var band = crossover[stretch - 2]; band < crossover[stretch - 1]; band++) {
                stretchBand(stretch, band, vocoderColumns, pitchInBins);
            }
        }
        for (i = 0; i < columns; i++) {
            for (var band = startBand; band < stopBand; band++) {
                var tout = (float) (-StrictMath.acos(-1.0) * (385.0 * ((float) band + 0.5)) / 128.0);
                var cos = StrictMath.cos(tout);
                var sin = StrictMath.sin(tout);
                transposedReal[transposedBase + i][band] = (float) (outReal[i][band] * cos - outImag[i][band] * sin);
                transposedImag[transposedBase + i][band] = (float) (outReal[i][band] * sin + outImag[i][band] * cos);
            }
        }
    }

    private void stretchBand(int stretch, int band, int vocoderColumns, int pitchInBins) {
        var sourceband = 2 * band / stretch;
        var pi = StrictMath.acos(-1.0);
        for (var k = 0; k < 3; k++) {
            for (var i = 0; i < 2; i++) {
                var angle = (pi / 2.0) * (2.0 * (float) i - 1.0) * ((float) sourceband + 0.5 + (float) k - 1.0);
                hintReal[k][i] = (float) (0.56342741195967 * StrictMath.cos(angle));
                hintImag[k][i] = (float) (0.56342741195967 * StrictMath.sin(angle));
            }
        }
        Arrays.fill(window, 0.0f);
        float wingain;
        if (stretch == 2) {
            for (var i = 0; i < 5; i++) {
                window[SLOT_OFFSET + i] = 1.0f;
                window[SLOT_OFFSET - 1 - i] = 1.0f;
            }
            wingain = 5.0f;
        } else if (stretch == 3) {
            for (var i = 0; i < 4; i++) {
                window[SLOT_OFFSET + i] = 1.4142f;
                window[SLOT_OFFSET - 1 - i] = 1.4142f;
            }
            wingain = 5.6568f;
        } else {
            for (var i = 0; i < 3; i++) {
                window[SLOT_OFFSET + i] = 2.0f;
                window[SLOT_OFFSET - 1 - i] = 2.0f;
            }
            wingain = 6.0f;
        }
        var exponent = 0.5 * ((1.0 / (float) stretch) - 1.0);
        var floor = StrictMath.pow(2.0, -52);
        var r = 0;
        for (var i = 0; i < vocoderColumns; i++) {
            Arrays.fill(gamma0Real, 0.0f);
            Arrays.fill(gamma0Imag, 0.0f);
            Arrays.fill(gamma1Real, 0.0f);
            Arrays.fill(gamma1Imag, 0.0f);
            for (var k = 0; k < WINDOW; k++) {
                if (stretch == 4) {
                    sourceband = band / 2;
                    r = band - 2 * sourceband;
                    var slot = 2 * (k - SLOT_OFFSET) + SLOT_OFFSET;
                    if (slot < WINDOW && slot + 1 > 0) {
                        var other = r == 0 ? sourceband - 1 : sourceband + 1;
                        gamma0Real[k] = inReal[i + slot][other];
                        gamma0Imag[k] = inImag[i + slot][other];
                        gamma1Real[k] = inReal[i + slot][sourceband];
                        gamma1Imag[k] = inImag[i + slot][sourceband];
                    }
                } else if (stretch == 3) {
                    sourceband = 2 * band / 3;
                    r = 2 * band - 3 * sourceband;
                    var shift = (3 * (k - SLOT_OFFSET) + 1000) / 2 - 500;
                    var filter = 3 * (k - SLOT_OFFSET) - 2 * shift;
                    if (r == 0 || r == 1) {
                        if (shift + SLOT_OFFSET < WINDOW && shift + SLOT_OFFSET + 1 > 0 && filter == 0) {
                            gamma0Real[k] = inReal[i + shift + SLOT_OFFSET][sourceband];
                            gamma0Imag[k] = inImag[i + shift + SLOT_OFFSET][sourceband];
                        } else if (shift + 1 + SLOT_OFFSET < WINDOW && shift + SLOT_OFFSET + 1 > 0 && filter == 1) {
                            var tr = inReal[i + shift + SLOT_OFFSET][sourceband];
                            var ti = inImag[i + shift + SLOT_OFFSET][sourceband];
                            gamma0Real[k] = hintReal[1][1] * tr - hintImag[1][1] * ti;
                            gamma0Imag[k] = hintImag[1][1] * tr + hintReal[1][1] * ti;
                            tr = inReal[i + shift + 1 + SLOT_OFFSET][sourceband];
                            ti = inImag[i + shift + 1 + SLOT_OFFSET][sourceband];
                            gamma0Real[k] += hintReal[1][0] * tr - hintImag[1][0] * ti;
                            gamma0Imag[k] += hintImag[1][0] * tr + hintReal[1][0] * ti;
                        }
                        gamma1Real[k] = gamma0Real[k];
                        gamma1Imag[k] = gamma0Imag[k];
                    } else {
                        if (shift + SLOT_OFFSET < WINDOW && shift + SLOT_OFFSET + 1 > 0 && filter == 0) {
                            gamma0Real[k] = inReal[i + shift + SLOT_OFFSET][sourceband + 1];
                            gamma0Imag[k] = inImag[i + shift + SLOT_OFFSET][sourceband + 1];
                            gamma1Real[k] = inReal[i + shift + SLOT_OFFSET][sourceband];
                            gamma1Imag[k] = inImag[i + shift + SLOT_OFFSET][sourceband];
                        } else if (shift + 1 + SLOT_OFFSET < WINDOW && shift + SLOT_OFFSET + 1 > 0 && filter == 1) {
                            var tr = inReal[i + shift + SLOT_OFFSET][sourceband];
                            var ti = inImag[i + shift + SLOT_OFFSET][sourceband];
                            gamma1Real[k] = hintReal[1][1] * tr - hintImag[1][1] * ti;
                            gamma1Imag[k] = hintImag[1][1] * tr + hintReal[1][1] * ti;
                            tr = inReal[i + shift + 1 + SLOT_OFFSET][sourceband];
                            ti = inImag[i + shift + 1 + SLOT_OFFSET][sourceband];
                            gamma1Real[k] += hintReal[1][0] * tr - hintImag[1][0] * ti;
                            gamma1Imag[k] += hintImag[1][0] * tr + hintReal[1][0] * ti;
                            tr = inReal[i + shift + SLOT_OFFSET][sourceband + 1];
                            ti = inImag[i + shift + SLOT_OFFSET][sourceband + 1];
                            gamma0Real[k] = hintReal[2][1] * tr - hintImag[2][1] * ti;
                            gamma0Imag[k] = hintImag[2][1] * tr + hintReal[2][1] * ti;
                            tr = inReal[i + shift + 1 + SLOT_OFFSET][sourceband + 1];
                            ti = inImag[i + shift + 1 + SLOT_OFFSET][sourceband + 1];
                            gamma0Real[k] += hintReal[2][0] * tr - hintImag[2][0] * ti;
                            gamma0Imag[k] += hintImag[2][0] * tr + hintReal[2][0] * ti;
                        }
                    }
                } else {
                    gamma0Real[k] = inReal[i + k][band];
                    gamma0Imag[k] = inImag[i + k][band];
                    gamma1Real[k] = inReal[i + k][band];
                    gamma1Imag[k] = inImag[i + k][band];
                }
                var scale = (float) StrictMath.pow(gamma0Real[k] * gamma0Real[k] + gamma0Imag[k] * gamma0Imag[k] + floor,
                        exponent);
                gamma0Real[k] *= scale;
                gamma0Imag[k] *= scale;
                scale = (float) StrictMath.pow(gamma1Real[k] * gamma1Real[k] + gamma1Imag[k] * gamma1Imag[k] + floor,
                        exponent);
                gamma1Real[k] *= scale;
                gamma1Imag[k] *= scale;
            }
            var centerReal = gamma1Real[SLOT_OFFSET];
            var centerImag = gamma1Imag[SLOT_OFFSET];
            var tmpReal = centerReal;
            var tmpImag = centerImag;
            for (var k = 0; k < stretch - 2; k++) {
                var tmp = centerReal;
                centerReal = centerReal * tmpReal - centerImag * tmpImag;
                centerImag = tmp * tmpImag + centerImag * tmpReal;
            }
            for (var k = 0; k < WINDOW; k++) {
                grainReal[k] = gamma0Real[k] * centerReal - gamma0Imag[k] * centerImag;
                grainImag[k] = gamma0Real[k] * centerImag + gamma0Imag[k] * centerReal;
            }
            if (stretch == 3 && r == 2) {
                centerReal = gamma0Real[SLOT_OFFSET];
                centerImag = gamma0Imag[SLOT_OFFSET];
                tmpReal = centerReal;
                tmpImag = centerImag;
                for (var k = 0; k < stretch - 2; k++) {
                    var tmp = centerReal;
                    centerReal = centerReal * tmpReal - centerImag * tmpImag;
                    centerImag = tmp * tmpImag + centerImag * tmpReal;
                }
                for (var k = 0; k < WINDOW; k++) {
                    grainReal[k] += gamma1Real[k] * centerReal - gamma1Imag[k] * centerImag;
                    grainImag[k] += gamma1Real[k] * centerImag + gamma1Imag[k] * centerReal;
                    grainReal[k] *= 0.5f;
                    grainImag[k] *= 0.5f;
                }
            }
            for (var k = 0; k < WINDOW; k++) {
                grainReal[k] *= window[k];
                grainImag[k] *= window[k];
                outReal[i * 2 + k][band] += grainReal[k] / 3;
                outImag[i * 2 + k][band] += grainImag[k] / 3;
            }
            var p = pitchInBins / (12.0f * (1 + (sbr41 ? 1 : 0)));
            if (p >= PITCH_MINIMUM) {
                crossProducts(stretch, band, i, p, wingain, exponent, floor);
            }
        }
    }

    private void crossProducts(int stretch, int band, int i, float p, float wingain, double exponent,
                               double floor) {
        var sourceband = 2 * band / stretch;
        var center = inReal[i + SLOT_OFFSET];
        var centerI = inImag[i + SLOT_OFFSET];
        var sqmag0 = center[sourceband] * center[sourceband] + centerI[sourceband] * centerI[sourceband];
        var mVal = 0.0f;
        var ts1 = 0;
        var ts2 = 0;
        var mTr = 0;
        for (var tr = 1; tr < stretch; tr++) {
            var f1 = (2.0 * band + 1 - tr * p) / stretch;
            var ti1 = (int) f1;
            var ti2 = (int) (f1 + p);
            if (ti1 < 0 || ti2 >= SYNTH_CHANNELS || ti1 >= SYNTH_CHANNELS || ti2 < 0) {
                continue;
            }
            var sqmag1 = center[ti1] * center[ti1] + centerI[ti1] * centerI[ti1];
            var sqmag2 = center[ti2] * center[ti2] + centerI[ti2] * centerI[ti2];
            var temp = Math.min(sqmag1, sqmag2);
            if (temp > mVal) {
                mVal = temp;
                mTr = tr;
                ts1 = ti1;
                ts2 = ti2;
            }
        }
        if (!(mVal > THRESHOLD * THRESHOLD * sqmag0 && ts1 >= 0 && ts2 < SYNTH_CHANNELS)) {
            return;
        }
        float d1;
        float d2;
        var centerReal = 0.0f;
        var centerImag = 0.0f;
        var tCenter = stretch - mTr;
        var tVec = mTr;
        if (stretch == 2) {
            d1 = 0;
            d2 = 1;
            centerReal = center[ts1];
            centerImag = centerI[ts1];
            for (var k = 0; k < 2; k++) {
                vectorReal[k] = inReal[i + SLOT_OFFSET - 1 + k][ts2];
                vectorImag[k] = inImag[i + SLOT_OFFSET - 1 + k][ts2];
            }
        } else if (stretch == 4) {
            if (mTr == 1) {
                d1 = 0;
                d2 = 2;
                centerReal = center[ts1];
                centerImag = centerI[ts1];
                for (var k = 0; k < 2; k++) {
                    vectorReal[k] = inReal[i + SLOT_OFFSET + 2 * (k - 1)][ts2];
                    vectorImag[k] = inImag[i + SLOT_OFFSET + 2 * (k - 1)][ts2];
                }
            } else if (mTr == 2) {
                d1 = 0;
                d2 = 1;
                centerReal = center[ts1];
                centerImag = centerI[ts1];
                for (var k = 0; k < 2; k++) {
                    vectorReal[k] = inReal[i + SLOT_OFFSET + (k - 1)][ts2];
                    vectorImag[k] = inImag[i + SLOT_OFFSET + (k - 1)][ts2];
                }
            } else {
                d1 = 2;
                d2 = 0;
                tCenter = mTr;
                tVec = stretch - mTr;
                centerReal = center[ts2];
                centerImag = centerI[ts2];
                for (var k = 0; k < 2; k++) {
                    vectorReal[k] = inReal[i + SLOT_OFFSET + 2 * (k - 1)][ts1];
                    vectorImag[k] = inImag[i + SLOT_OFFSET + 2 * (k - 1)][ts1];
                }
            }
        } else {
            int source;
            if (mTr == 1) {
                d1 = 0;
                d2 = 1.5f;
                centerReal = center[ts1];
                centerImag = centerI[ts1];
                source = ts2;
            } else {
                d1 = 1.5f;
                d2 = 0;
                tCenter = mTr;
                tVec = stretch - mTr;
                centerReal = center[ts2];
                centerImag = centerI[ts2];
                source = ts1;
            }
            var pi = StrictMath.acos(-1.0);
            for (var k = 0; k < 2; k++) {
                var angle = (pi / 2.0) * (2.0 * (float) k - 1.0) * ((float) source + 0.5);
                hReal[k] = (float) (0.56342741195967 * StrictMath.cos(angle));
                hImag[k] = (float) (0.56342741195967 * StrictMath.sin(angle));
            }
            vectorReal[1] = center[source];
            vectorImag[1] = centerI[source];
            var tr = inReal[i - 2 + SLOT_OFFSET][source];
            var ti = inImag[i - 2 + SLOT_OFFSET][source];
            vectorReal[0] = hReal[1] * tr - hImag[1] * ti;
            vectorImag[0] = hImag[1] * tr + hReal[1] * ti;
            tr = inReal[i - 1 + SLOT_OFFSET][source];
            ti = inImag[i - 1 + SLOT_OFFSET][source];
            vectorReal[0] += hReal[0] * tr - hImag[0] * ti;
            vectorImag[0] += hImag[0] * tr + hReal[0] * ti;
        }
        var scale = (float) StrictMath.pow(centerReal * centerReal + centerImag * centerImag + floor, exponent);
        centerReal *= scale;
        centerImag *= scale;
        for (var k = 0; k < 2; k++) {
            scale = (float) StrictMath.pow(vectorReal[k] * vectorReal[k] + vectorImag[k] * vectorImag[k] + floor, exponent);
            vectorReal[k] *= scale;
            vectorImag[k] *= scale;
        }
        var tmpReal = centerReal;
        var tmpImag = centerImag;
        for (var idx = 0; idx < tCenter - 1; idx++) {
            var tmp = centerReal;
            centerReal = centerReal * tmpReal - centerImag * tmpImag;
            centerImag = tmp * tmpImag + centerImag * tmpReal;
        }
        for (var k = 0; k < 2; k++) {
            tmpReal = vectorReal[k];
            tmpImag = vectorImag[k];
            for (var idx = 0; idx < tVec - 1; idx++) {
                var tmp = vectorReal[k];
                vectorReal[k] = vectorReal[k] * tmpReal - vectorImag[k] * tmpImag;
                vectorImag[k] = tmp * tmpImag + vectorImag[k] * tmpReal;
            }
        }
        for (var k = 0; k < 2; k++) {
            outputReal[k] = vectorReal[k] * centerReal - vectorImag[k] * centerImag;
            outputImag[k] = vectorReal[k] * centerImag + vectorImag[k] * centerReal;
        }
        var twid = (float) (StrictMath.acos(-1.0) * (d2 - d1) * p * (float) mTr * (stretch - mTr) / stretch);
        var tmpR = outputReal[0];
        var tmpI = outputImag[0];
        outputReal[0] = (float) (StrictMath.cos(twid) * tmpR - StrictMath.sin(twid) * tmpI);
        outputImag[0] = (float) (StrictMath.cos(twid) * tmpI + StrictMath.sin(twid) * tmpR);
        for (var k = 0; k < 2; k++) {
            outReal[i * 2 + (k + SLOT_OFFSET - 1)][band] += (wingain / 3.0) * outputReal[k];
            outImag[i * 2 + (k + SLOT_OFFSET - 1)][band] += (wingain / 3.0) * outputImag[k];
        }
    }

    private void synthesize(float[] subbands, float[] out, int offset) {
        var l = synthSize;
        var n = 10 * l;
        var buffer = synthesisBuffer;
        System.arraycopy(buffer, 0, buffer, 2 * l, 2 * n - 2 * l);
        for (var row = 0; row < 2 * l; row++) {
            var accu = 0.0;
            for (var k = 0; k < l; k++) {
                accu += subbands[k] * synthesisMatrix[row * l + k];
            }
            buffer[row] = accu;
        }
        var u = scratchU;
        for (var i = 0; i < 5; i++) {
            for (var j = 0; j < l; j++) {
                u[2 * l * i + j] = buffer[4 * l * i + j];
                u[2 * l * i + l + j] = buffer[4 * l * i + 3 * l + j];
            }
        }
        var c = synthesisPrototype;
        for (var i = 0; i < l; i++) {
            var accu = 0.0;
            for (var j = 0; j < 10; j++) {
                var index = l * j + i;
                accu = accu + u[index] * c[index];
            }
            out[offset + i] = (float) accu;
        }
    }

    private void analyze(float[] time, int offset, float[] real, float[] imag, int first) {
        var l = 2 * synthSize;
        var n = 10 * l;
        var buffer = analysisBuffer;
        System.arraycopy(buffer, 0, buffer, l, n - l);
        for (var i = l - 1; i >= 0; i--) {
            buffer[i] = time[offset + l - 1 - i];
        }
        var c = analysisPrototype;
        var y = scratchY;
        for (var i = 0; i < 2 * l; i++) {
            var accu = 0.0;
            for (var j = 0; j < 5; j++) {
                var index = i + j * 2 * l;
                accu = accu + buffer[index] * c[index];
            }
            y[i] = accu;
        }
        for (var k = 0; k < l; k++) {
            var re = 0.0;
            var im = 0.0;
            var row = k * 2 * l;
            for (var col = 0; col < 2 * l; col++) {
                re = re + y[col] * analysisReal[row + col];
                im = im + y[col] * analysisImag[row + col];
            }
            real[first + k] = (float) re;
            imag[first + k] = (float) im;
        }
    }
}
