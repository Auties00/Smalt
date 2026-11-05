package com.github.auties00.smalt.audio.vorbis.internal;

import java.util.Arrays;

/**
 * Computes the masking curve of one block type: noise masking by a sliding median of the MDCT
 * spectrum, tone masking from the FFT spectrum with level-dependent masking curves and the
 * threshold in quiet, and their mix; and quantizes, normalizes and couples the residue.
 *
 * <p>An instance keeps scratch buffers and is not thread-safe.
 *
 * @implNote This implementation ports libvorbis 1.3.7's {@code psy.c} (BSD-3), including the
 * aoTuV M1 noise compensation, with the C expressions' float and double precision kept
 * operation by operation.
 */
public final class Psy {
    /**
     * The value marking an unset seed.
     */
    private static final float NEGINF = -9999.f;

    /**
     * The tone masking curve center offset.
     */
    private static final int EHMER_OFFSET = 16;

    /**
     * The tone masking curve length.
     */
    private static final int EHMER_MAX = VorbisSetupData.EHMER_MAX;

    /**
     * The number of tone masking levels.
     */
    private static final int LEVELS = 8;

    /**
     * The level of the first tone masking curve, in decibels.
     */
    private static final double LEVEL_0 = 30.;

    /**
     * The lossless coupling thresholds by amplitude index.
     */
    private static final double[] STEREO_THRESHOLDS = {0.0, .5, 1.0, 1.5, 2.5, 4.5, 8.5, 16.5, 9e10};

    /**
     * The lossless coupling thresholds of long blocks after the point.
     */
    private static final double[] STEREO_THRESHOLDS_LIMITED = {0.0, .5, 1.0, 1.5, 2.0, 2.5, 4.5, 8.5, 9e10};

    /**
     * The number of spectral lines.
     */
    final int n;

    /**
     * The parameters of the block type.
     */
    final PsyInfo vi;

    /**
     * The tone masking curves, indexed {@code [band][level][2 + point]} with the first two
     * entries holding the curve's first and last useful points.
     */
    private final float[][][] toneCurves;

    /**
     * The noise offsets of each noise curve and line.
     */
    private final float[][] noiseOffset;

    /**
     * The threshold in quiet of each line, in decibels.
     */
    private final float[] ath;

    /**
     * The eighth-octave position of each line.
     */
    private final long[] octave;

    /**
     * The noise window of each line: low bound in the high 16 bits, high bound in the low ones.
     */
    private final long[] bark;

    /**
     * The first eighth-octave position.
     */
    private final long firstOctave;

    /**
     * The shift from eighth-octave positions to half octaves.
     */
    private final long shiftOctave;

    /**
     * The eighth-octave resolution.
     */
    private final int eighthOctaveLines;

    /**
     * The number of eighth-octave positions.
     */
    private final int totalOctaveLines;

    /**
     * The aoTuV high-frequency weighting.
     */
    private final float mVal;

    /**
     * Scratch for the noise median.
     */
    private final float[] work;

    /**
     * Scratch for the tone masking seeds.
     */
    private final float[] seed;

    /**
     * Scratch for the noise fit sums.
     */
    private final float[] sumN;

    /**
     * Scratch for the noise fit sums.
     */
    private final float[] sumX;

    /**
     * Scratch for the noise fit sums.
     */
    private final float[] sumXX;

    /**
     * Scratch for the noise fit sums.
     */
    private final float[] sumY;

    /**
     * Scratch for the noise fit sums.
     */
    private final float[] sumXY;

    /**
     * Scratch positions of the seed chase.
     */
    private final long[] positionStack;

    /**
     * Scratch amplitudes of the seed chase.
     */
    private final float[] amplitudeStack;

    /**
     * Prepares the lookups of a block type ({@code _vp_psy_init}).
     *
     * @param vi   the block type's parameters
     * @param gi   the global parameters
     * @param n    the number of spectral lines (half the block size)
     * @param rate the sampling rate
     */
    public Psy(PsyInfo vi, PsyGlobal gi, int n, long rate) {
        this.vi = vi;
        this.n = n;
        var data = VorbisSetupData.get();
        var athTable = data.ath;
        eighthOctaveLines = gi.eighthOctaveLines;
        shiftOctave = (long) (Math.rint(StrictMath.log(gi.eighthOctaveLines * 8.f) / StrictMath.log(2.f)) - 1);
        firstOctave = (long) (Scales.toOctave(.25f * rate * .5 / n) * (1 << (shiftOctave + 1)) - gi.eighthOctaveLines);
        var maxOctave = (long) (Scales.toOctave((n + .25f) * rate * .5 / n) * (1 << (shiftOctave + 1)) + .5f);
        totalOctaveLines = (int) (maxOctave - firstOctave + 1);
        ath = new float[n];
        octave = new long[n];
        bark = new long[n];
        if (rate < 26000) {
            mVal = 0;
        } else if (rate < 38000) {
            mVal = .94f;
        } else if (rate > 46000) {
            mVal = 1.275f;
        } else {
            mVal = 1.f;
        }
        var j = 0;
        for (var i = 0; i < athTable.length - 1; i++) {
            var endPosition = (int) Math.rint(Scales.fromOctave((i + 1) * .125 - 2.) * 2 * n / rate);
            var base = athTable[i];
            if (j < endPosition) {
                var delta = (athTable[i + 1] - base) / (endPosition - j);
                for (; j < endPosition && j < n; j++) {
                    ath[j] = (float) (base + 100.);
                    base += delta;
                }
            }
        }
        for (; j < n; j++) {
            ath[j] = ath[j - 1];
        }
        long lo = -99;
        long hi = 1;
        var binRate = rate / (2L * n);
        for (var i = 0; i < n; i++) {
            var barkValue = (float) Scales.toBark(binRate * i);
            for (; lo + vi.noiseWindowLowMin < i && Scales.toBark(binRate * lo) < (barkValue - vi.noiseWindowLow); lo++) {
            }
            for (; hi <= n && (hi < i + vi.noiseWindowHighMin || Scales.toBark(binRate * hi) < (barkValue + vi.noiseWindowHigh)); hi++) {
            }
            bark[i] = ((lo - 1) << 16) + (hi - 1);
        }
        for (var i = 0; i < n; i++) {
            octave[i] = (long) (Scales.toOctave((i + .25f) * .5 * rate / n) * (1 << (shiftOctave + 1)) + .5f);
        }
        toneCurves = setupToneCurves(vi.toneAttenuation, (float) (rate * .5 / n), n, vi.toneCenterBoost, vi.toneDecay, data);
        noiseOffset = new float[PsyInfo.NOISE_CURVES][n];
        for (var i = 0; i < n; i++) {
            var halfOctave = (float) (Scales.toOctave((i + .5) * rate / (2. * n)) * 2.);
            if (halfOctave < 0) {
                halfOctave = 0;
            }
            if (halfOctave >= PsyInfo.BANDS - 1) {
                halfOctave = PsyInfo.BANDS - 1;
            }
            var index = (int) halfOctave;
            var del = halfOctave - index;
            var next = Math.min(index + 1, PsyInfo.BANDS - 1);
            for (var k = 0; k < PsyInfo.NOISE_CURVES; k++) {
                noiseOffset[k][i] = (float) (vi.noiseOffset[k][index] * (1. - del) + vi.noiseOffset[k][next] * del);
            }
        }
        work = new float[n];
        seed = new float[totalOctaveLines];
        sumN = new float[n];
        sumX = new float[n];
        sumXX = new float[n];
        sumY = new float[n];
        sumXY = new float[n];
        positionStack = new long[totalOctaveLines];
        amplitudeStack = new float[totalOctaveLines];
    }

    /**
     * Builds the tone masking curves ({@code setup_tone_curves}).
     *
     * @param curveAttenuation the attenuation of each band
     * @param binHz            the width of a line in hertz
     * @param n                the number of lines
     * @param centerBoost      the center boost
     * @param centerDecayRate  the decay away from the center
     * @param data             the setup data
     * @return the curves
     */
    private static float[][][] setupToneCurves(float[] curveAttenuation, float binHz, int n, float centerBoost,
                                               float centerDecayRate, VorbisSetupData data) {
        var athTable = data.ath;
        var maxAth = athTable.length;
        var bands = PsyInfo.BANDS;
        var curveAth = new float[EHMER_MAX];
        var workc = new float[bands][LEVELS][EHMER_MAX];
        var athc = new float[LEVELS][EHMER_MAX];
        var brute = new float[n];
        var ret = new float[bands][LEVELS][];
        for (var i = 0; i < bands; i++) {
            var athOffset = i * 4;
            for (var j = 0; j < EHMER_MAX; j++) {
                var min = 999.f;
                for (var k = 0; k < 4; k++) {
                    if (j + k + athOffset < maxAth) {
                        if (min > athTable[j + k + athOffset]) {
                            min = athTable[j + k + athOffset];
                        }
                    } else if (min > athTable[maxAth - 1]) {
                        min = athTable[maxAth - 1];
                    }
                }
                curveAth[j] = min;
            }
            for (var j = 0; j < 6; j++) {
                System.arraycopy(data.toneMasks[i][j], 0, workc[i][j + 2], 0, EHMER_MAX);
            }
            System.arraycopy(data.toneMasks[i][0], 0, workc[i][0], 0, EHMER_MAX);
            System.arraycopy(data.toneMasks[i][0], 0, workc[i][1], 0, EHMER_MAX);
            for (var j = 0; j < LEVELS; j++) {
                for (var k = 0; k < EHMER_MAX; k++) {
                    var adj = centerBoost + Math.abs(EHMER_OFFSET - k) * centerDecayRate;
                    if (adj < 0. && centerBoost > 0) {
                        adj = 0.f;
                    }
                    if (adj > 0. && centerBoost < 0) {
                        adj = 0.f;
                    }
                    workc[i][j][k] += adj;
                }
            }
            for (var j = 0; j < LEVELS; j++) {
                attenuate(workc[i][j], (float) (curveAttenuation[i] + 100. - (j < 2 ? 2 : j) * 10. - LEVEL_0));
                System.arraycopy(curveAth, 0, athc[j], 0, EHMER_MAX);
                attenuate(athc[j], (float) (+100. - j * 10.f - LEVEL_0));
                maxCurve(athc[j], workc[i][j]);
            }
            for (var j = 1; j < LEVELS; j++) {
                minCurve(athc[j], athc[j - 1]);
                minCurve(workc[i][j], athc[j]);
            }
        }
        for (var i = 0; i < bands; i++) {
            var bin = (int) Math.floor(Scales.fromOctave(i * .5) / binHz);
            var loCurve = (int) Math.ceil(Scales.toOctave(bin * binHz + 1) * 2);
            var hiCurve = (int) Math.floor(Scales.toOctave((bin + 1) * binHz) * 2);
            if (loCurve > i) {
                loCurve = i;
            }
            if (loCurve < 0) {
                loCurve = 0;
            }
            if (hiCurve >= bands) {
                hiCurve = bands - 1;
            }
            for (var m = 0; m < LEVELS; m++) {
                var curve = ret[i][m] = new float[EHMER_MAX + 2];
                Arrays.fill(brute, 999.f);
                for (var k = loCurve; k <= hiCurve; k++) {
                    render(brute, workc[k][m], k, binHz, n);
                }
                if (i + 1 < bands) {
                    renderShifted(brute, workc[i + 1][m], i, binHz, n);
                }
                for (var j = 0; j < EHMER_MAX; j++) {
                    var b = (int) (Scales.fromOctave(j * .125 + i * .5 - 2.) / binHz);
                    if (b < 0 || b >= n) {
                        curve[j + 2] = -999.f;
                    } else {
                        curve[j + 2] = brute[b];
                    }
                }
                var j = 0;
                for (; j < EHMER_OFFSET; j++) {
                    if (curve[j + 2] > -200.f) {
                        break;
                    }
                }
                curve[0] = j;
                for (j = EHMER_MAX - 1; j > EHMER_OFFSET + 1; j--) {
                    if (curve[j + 2] > -200.f) {
                        break;
                    }
                }
                curve[1] = j;
            }
        }
        return ret;
    }

    /**
     * Renders one octave curve into the line buffer, keeping the minimum.
     *
     * @param brute the line buffer
     * @param curve the curve
     * @param k     the half-octave of the curve
     * @param binHz the line width
     * @param n     the number of lines
     */
    private static void render(float[] brute, float[] curve, int k, float binHz, int n) {
        renderAt(brute, curve, k, binHz, n);
    }

    /**
     * Renders the next band's curve at this band's position, keeping the minimum.
     *
     * @param brute the line buffer
     * @param curve the next band's curve
     * @param i     this band's half-octave
     * @param binHz the line width
     * @param n     the number of lines
     */
    private static void renderShifted(float[] brute, float[] curve, int i, float binHz, int n) {
        renderAt(brute, curve, i, binHz, n);
    }

    /**
     * Renders a curve placed at a half-octave into the line buffer, keeping the minimum.
     *
     * @param brute    the line buffer
     * @param curve    the curve
     * @param position the half-octave the curve's points are placed at
     * @param binHz    the line width
     * @param n        the number of lines
     */
    private static void renderAt(float[] brute, float[] curve, int position, float binHz, int n) {
        var l = 0;
        for (var j = 0; j < EHMER_MAX; j++) {
            var loBin = (int) (Scales.fromOctave(j * .125 + position * .5 - 2.0625) / binHz);
            var hiBin = (int) (Scales.fromOctave(j * .125 + position * .5 - 1.9375) / binHz + 1);
            if (loBin < 0) {
                loBin = 0;
            }
            if (loBin > n) {
                loBin = n;
            }
            if (loBin < l) {
                l = loBin;
            }
            if (hiBin < 0) {
                hiBin = 0;
            }
            if (hiBin > n) {
                hiBin = n;
            }
            for (; l < hiBin && l < n; l++) {
                if (brute[l] > curve[j]) {
                    brute[l] = curve[j];
                }
            }
        }
        for (; l < n; l++) {
            if (brute[l] > curve[EHMER_MAX - 1]) {
                brute[l] = curve[EHMER_MAX - 1];
            }
        }
    }

    /**
     * Adds an attenuation to a curve.
     *
     * @param c   the curve
     * @param att the attenuation
     */
    private static void attenuate(float[] c, float att) {
        for (var i = 0; i < EHMER_MAX; i++) {
            c[i] += att;
        }
    }

    /**
     * Lowers a curve to another wherever the other is lower.
     *
     * @param c  the curve
     * @param c2 the other curve
     */
    private static void minCurve(float[] c, float[] c2) {
        for (var i = 0; i < EHMER_MAX; i++) {
            if (c2[i] < c[i]) {
                c[i] = c2[i];
            }
        }
    }

    /**
     * Raises a curve to another wherever the other is higher.
     *
     * @param c  the curve
     * @param c2 the other curve
     */
    private static void maxCurve(float[] c, float[] c2) {
        for (var i = 0; i < EHMER_MAX; i++) {
            if (c2[i] > c[i]) {
                c[i] = c2[i];
            }
        }
    }

    /**
     * Seeds the masking curve of one peak ({@code seed_curve}).
     *
     * @param curves    the curves of the peak's band
     * @param amp       the peak amplitude in decibels
     * @param oc        the peak's eighth-octave position relative to the first
     * @param dbOffset  the offset selecting the curve level
     */
    private void seedCurve(float[][] curves, float amp, long oc, float dbOffset) {
        var linesPer = eighthOctaveLines;
        var choice = (int) ((amp + dbOffset - LEVEL_0) * .1f);
        choice = Math.max(choice, 0);
        choice = Math.min(choice, LEVELS - 1);
        var posts = curves[choice];
        var post1 = (int) posts[1];
        var seedPointer = (int) (oc + (posts[0] - EHMER_OFFSET) * linesPer - (linesPer >> 1));
        for (var i = (int) posts[0]; i < post1; i++) {
            if (seedPointer > 0) {
                var lin = amp + posts[i + 2];
                if (seed[seedPointer] < lin) {
                    seed[seedPointer] = lin;
                }
            }
            seedPointer += linesPer;
            if (seedPointer >= totalOctaveLines) {
                break;
            }
        }
    }

    /**
     * Seeds the masking curves of every spectral peak above the floor ({@code seed_loop}).
     *
     * @param f       the FFT spectrum in decibels
     * @param flr     the current mask
     * @param specMax the global spectral maximum
     */
    private void seedLoop(float[] f, float[] flr, float specMax) {
        var dbOffset = vi.maxCurveDb - specMax;
        for (var i = 0; i < n; i++) {
            var max = f[i];
            var oc = octave[i];
            while (i + 1 < n && octave[i + 1] == oc) {
                i++;
                if (f[i] > max) {
                    max = f[i];
                }
            }
            if (max + 6.f > flr[i]) {
                oc = oc >> shiftOctave;
                if (oc >= PsyInfo.BANDS) {
                    oc = PsyInfo.BANDS - 1;
                }
                if (oc < 0) {
                    oc = 0;
                }
                seedCurve(toneCurves[(int) oc], max, octave[i] - firstOctave, dbOffset);
            }
        }
    }

    /**
     * Turns the seeds into a staircase of maxima ({@code seed_chase}).
     */
    private void seedChase() {
        var linesPer = eighthOctaveLines;
        var count = totalOctaveLines;
        var positions = positionStack;
        var amplitudes = amplitudeStack;
        var stack = 0;
        long position = 0;
        for (var i = 0; i < count; i++) {
            if (stack < 2) {
                positions[stack] = i;
                amplitudes[stack++] = seed[i];
            } else {
                while (true) {
                    if (seed[i] < amplitudes[stack - 1]) {
                        positions[stack] = i;
                        amplitudes[stack++] = seed[i];
                        break;
                    }
                    if (i < positions[stack - 1] + linesPer) {
                        if (stack > 1 && amplitudes[stack - 1] <= amplitudes[stack - 2] && i < positions[stack - 2] + linesPer) {
                            stack--;
                            continue;
                        }
                    }
                    positions[stack] = i;
                    amplitudes[stack++] = seed[i];
                    break;
                }
            }
        }
        for (var i = 0; i < stack; i++) {
            long end;
            if (i < stack - 1 && amplitudes[i + 1] > amplitudes[i]) {
                end = positions[i + 1];
            } else {
                end = positions[i] + linesPer + 1;
            }
            if (end > count) {
                end = count;
            }
            for (; position < end; position++) {
                seed[(int) position] = amplitudes[i];
            }
        }
    }

    /**
     * Raises the mask to the tone masking seeds ({@code max_seeds}).
     *
     * @param flr the mask
     */
    private void maxSeeds(float[] flr) {
        var linesPer = eighthOctaveLines;
        var linePosition = 0;
        seedChase();
        long position = octave[0] - firstOctave - (linesPer >> 1);
        while (linePosition + 1 < n) {
            var minV = seed[(int) position];
            var end = ((octave[linePosition] + octave[linePosition + 1]) >> 1) - firstOctave;
            if (minV > vi.toneAbsoluteLimit) {
                minV = vi.toneAbsoluteLimit;
            }
            while (position + 1 <= end) {
                position++;
                if ((seed[(int) position] > NEGINF && seed[(int) position] < minV) || minV == NEGINF) {
                    minV = seed[(int) position];
                }
            }
            end = position + firstOctave;
            for (; linePosition < n && octave[linePosition] <= end; linePosition++) {
                if (flr[linePosition] < minV) {
                    flr[linePosition] = minV;
                }
            }
        }
        var minV = seed[totalOctaveLines - 1];
        for (; linePosition < n; linePosition++) {
            if (flr[linePosition] < minV) {
                flr[linePosition] = minV;
            }
        }
    }

    /**
     * Fits a local regression of a curve over each line's noise window
     * ({@code bark_noise_hybridmp}).
     *
     * @param f      the curve
     * @param noise  receives the fitted curve
     * @param offset the offset added before fitting
     * @param fixed  the width of an additional fixed window, or non-positive for none
     */
    private void barkNoiseHybrid(float[] f, float[] noise, float offset, int fixed) {
        var tableN = sumN;
        var tableX = sumX;
        var tableXX = sumXX;
        var tableY = sumY;
        var tableXY = sumXY;
        float tN = 0;
        float tX = 0;
        float tXX = 0;
        float tY = 0;
        float tXY = 0;
        var a = 0.f;
        var b = 0.f;
        var d = 1.f;
        var y = f[0] + offset;
        if (y < 1.f) {
            y = 1.f;
        }
        var w = (float) (y * y * .5);
        tN += w;
        tX += w;
        tY += w * y;
        tableN[0] = tN;
        tableX[0] = tX;
        tableXX[0] = tXX;
        tableY[0] = tY;
        tableXY[0] = tXY;
        var x = 1.f;
        for (var i = 1; i < n; i++, x += 1.f) {
            y = f[i] + offset;
            if (y < 1.f) {
                y = 1.f;
            }
            w = y * y;
            tN += w;
            tX += w * x;
            tXX += w * x * x;
            tY += w * y;
            tXY += w * x * y;
            tableN[i] = tN;
            tableX[i] = tX;
            tableXX[i] = tXX;
            tableY[i] = tY;
            tableXY[i] = tXY;
        }
        var i = 0;
        x = 0.f;
        for (; i < n; i++, x += 1.f) {
            var lo = (int) (bark[i] >> 16);
            var hi = (int) (bark[i] & 0xFFFF);
            if (lo >= 0 || -lo >= n) {
                break;
            }
            if (hi >= n) {
                break;
            }
            tN = tableN[hi] + tableN[-lo];
            tX = tableX[hi] - tableX[-lo];
            tXX = tableXX[hi] + tableXX[-lo];
            tY = tableY[hi] + tableY[-lo];
            tXY = tableXY[hi] - tableXY[-lo];
            a = tY * tXX - tX * tXY;
            b = tN * tXY - tX * tY;
            d = tN * tXX - tX * tX;
            var r = (a + x * b) / d;
            if (r < 0.f) {
                r = 0.f;
            }
            noise[i] = r - offset;
        }
        for (; i < n; i++, x += 1.f) {
            var lo = (int) (bark[i] >> 16);
            var hi = (int) (bark[i] & 0xFFFF);
            if (lo < 0 || lo >= n) {
                break;
            }
            if (hi >= n) {
                break;
            }
            tN = tableN[hi] - tableN[lo];
            tX = tableX[hi] - tableX[lo];
            tXX = tableXX[hi] - tableXX[lo];
            tY = tableY[hi] - tableY[lo];
            tXY = tableXY[hi] - tableXY[lo];
            a = tY * tXX - tX * tXY;
            b = tN * tXY - tX * tY;
            d = tN * tXX - tX * tX;
            var r = (a + x * b) / d;
            if (r < 0.f) {
                r = 0.f;
            }
            noise[i] = r - offset;
        }
        for (; i < n; i++, x += 1.f) {
            var r = (a + x * b) / d;
            if (r < 0.f) {
                r = 0.f;
            }
            noise[i] = r - offset;
        }
        if (fixed <= 0) {
            return;
        }
        i = 0;
        x = 0.f;
        for (; i < n; i++, x += 1.f) {
            var hi = i + fixed / 2;
            var lo = hi - fixed;
            if (hi >= n) {
                break;
            }
            if (lo >= 0) {
                break;
            }
            tN = tableN[hi] + tableN[-lo];
            tX = tableX[hi] - tableX[-lo];
            tXX = tableXX[hi] + tableXX[-lo];
            tY = tableY[hi] + tableY[-lo];
            tXY = tableXY[hi] - tableXY[-lo];
            a = tY * tXX - tX * tXY;
            b = tN * tXY - tX * tY;
            d = tN * tXX - tX * tX;
            var r = (a + x * b) / d;
            if (r - offset < noise[i]) {
                noise[i] = r - offset;
            }
        }
        for (; i < n; i++, x += 1.f) {
            var hi = i + fixed / 2;
            var lo = hi - fixed;
            if (hi >= n) {
                break;
            }
            if (lo < 0) {
                break;
            }
            tN = tableN[hi] - tableN[lo];
            tX = tableX[hi] - tableX[lo];
            tXX = tableXX[hi] - tableXX[lo];
            tY = tableY[hi] - tableY[lo];
            tXY = tableXY[hi] - tableXY[lo];
            a = tY * tXX - tX * tXY;
            b = tN * tXY - tX * tY;
            d = tN * tXX - tX * tX;
            var r = (a + x * b) / d;
            if (r - offset < noise[i]) {
                noise[i] = r - offset;
            }
        }
        for (; i < n; i++, x += 1.f) {
            var r = (a + x * b) / d;
            if (r - offset < noise[i]) {
                noise[i] = r - offset;
            }
        }
    }

    /**
     * Computes the noise masking curve of an MDCT spectrum ({@code _vp_noisemask}).
     *
     * @param logMdct the MDCT spectrum in decibels
     * @param logMask receives the noise mask
     */
    public void noiseMask(float[] logMdct, float[] logMask) {
        barkNoiseHybrid(logMdct, logMask, 140.f, -1);
        for (var i = 0; i < n; i++) {
            work[i] = logMdct[i] - logMask[i];
        }
        barkNoiseHybrid(work, logMask, 0.f, vi.noiseWindowFixed);
        for (var i = 0; i < n; i++) {
            work[i] = logMdct[i] - work[i];
        }
        for (var i = 0; i < n; i++) {
            var db = (int) (logMask[i] + .5);
            if (db >= PsyInfo.COMPAND_LEVELS) {
                db = PsyInfo.COMPAND_LEVELS - 1;
            }
            if (db < 0) {
                db = 0;
            }
            logMask[i] = work[i] + vi.noiseCompand[db];
        }
    }

    /**
     * Computes the tone masking curve with the threshold in quiet ({@code _vp_tonemask}).
     *
     * @param logFft        the FFT spectrum in decibels
     * @param logMask       receives the tone mask
     * @param globalSpecMax the global spectral maximum
     * @param localSpecMax  the block's spectral maximum
     */
    public void toneMask(float[] logFft, float[] logMask, float globalSpecMax, float localSpecMax) {
        var att = localSpecMax + vi.athAdjustAttenuation;
        Arrays.fill(seed, NEGINF);
        if (att < vi.athMaxAttenuation) {
            att = vi.athMaxAttenuation;
        }
        for (var i = 0; i < n; i++) {
            logMask[i] = ath[i] + att;
        }
        seedLoop(logFft, logMask, globalSpecMax);
        maxSeeds(logMask);
    }

    /**
     * Mixes the noise and tone masks into the final mask and applies the aoTuV M1 compensation
     * to the MDCT ({@code _vp_offset_and_mix}).
     *
     * @param noise        the noise mask
     * @param tone         the tone mask
     * @param offsetSelect the noise curve, 0 to 2
     * @param logMask      receives the mask
     * @param mdct         the MDCT spectrum, compensated in place for curve 1
     * @param logMdct      the MDCT spectrum in decibels
     */
    public void offsetAndMix(float[] noise, float[] tone, int offsetSelect, float[] logMask, float[] mdct, float[] logMdct) {
        var toneAtt = vi.toneMasterAttenuation[offsetSelect];
        var cx = mVal;
        for (var i = 0; i < n; i++) {
            var val = noise[i] + noiseOffset[offsetSelect][i];
            if (val > vi.noiseMaxSuppression) {
                val = vi.noiseMaxSuppression;
            }
            logMask[i] = Math.max(val, tone[i] + toneAtt);
            if (offsetSelect == 1) {
                var coefficient = -17.2f;
                float de;
                val = val - logMdct[i];
                if (val > coefficient) {
                    de = (float) (1.0 - ((val - coefficient) * 0.005 * cx));
                    if (de < 0) {
                        de = 0.0001f;
                    }
                } else {
                    de = (float) (1.0 - ((val - coefficient) * 0.0003 * cx));
                }
                mdct[i] *= de;
            }
        }
    }

    /**
     * Returns the decayed global amplitude maximum ({@code _vp_ampmax_decay}).
     *
     * @param amp       the maximum
     * @param global    the global parameters
     * @param blockSize the current block size
     * @param rate      the sampling rate
     * @return the decayed maximum
     */
    public static float ampmaxDecay(float amp, PsyGlobal global, int blockSize, int rate) {
        var n = blockSize / 2;
        var secs = (float) n / rate;
        amp += secs * global.ampmaxAttenuationPerSecond;
        if (amp < -9999) {
            amp = -9999;
        }
        return amp;
    }

    /**
     * Holds the scratch state of {@link #coupleQuantizeNormalize}.
     */
    public static final class CouplingScratch {
        /**
         * The unquantized energies, negative for negative amplitudes.
         */
        final float[][] raw;

        /**
         * The quantized energies or the magnitudes.
         */
        final float[][] quant;

        /**
         * The floor energies.
         */
        final float[][] floor;

        /**
         * The lossless coupling flags.
         */
        final int[][] flag;

        /**
         * The working non-zero flags.
         */
        final int[] nz;

        /**
         * The energy accumulators.
         */
        final float[] acc;

        /**
         * The sort buffer of noise normalization.
         */
        final Integer[] sort;

        /**
         * Creates scratch for a channel count and partition size.
         *
         * @param channels  the number of channels
         * @param partition the partition size
         * @param steps     the number of coupling steps
         */
        public CouplingScratch(int channels, int partition, int steps) {
            raw = new float[channels][partition];
            quant = new float[channels][partition];
            floor = new float[channels][partition];
            flag = new int[channels][partition];
            nz = new int[channels];
            acc = new float[channels + steps];
            sort = new Integer[partition];
        }
    }

    /**
     * Sets the lossless coupling flags of a partition ({@code flag_lossless}).
     *
     * @param limit     the point stereo limit
     * @param prePoint  the threshold below the limit
     * @param postPoint the threshold above the limit
     * @param mdct      the MDCT spectrum
     * @param mdctOff   the index of the partition in {@code mdct}
     * @param floor     the floor amplitudes
     * @param flag      receives the flags
     * @param i         the partition start
     * @param jn        the partition length
     */
    private static void flagLossless(int limit, float prePoint, float postPoint, float[] mdct, int mdctOff, float[] floor,
                                     int[] flag, int i, int jn) {
        for (var j = 0; j < jn; j++) {
            var point = j >= limit - i ? postPoint : prePoint;
            var r = (float) (Math.abs((double) mdct[mdctOff + j]) / floor[j]);
            flag[j] = r < point ? 0 : 1;
        }
    }

    /**
     * Quantizes a partition and normalizes the energy lost to zeros ({@code noise_normalize}).
     *
     * @param limit   the point stereo limit
     * @param r       the unquantized energies
     * @param q       the quantized energies or magnitudes, updated
     * @param f       the floor energies
     * @param flags   the lossless flags, or {@code null}
     * @param i       the partition start
     * @param n       the partition length
     * @param out     receives the quantized values
     * @param outOff  the index of the partition in {@code out}
     * @param sort    scratch for sorting
     * @return the energy accumulator
     */
    private float noiseNormalize(int limit, float[] r, float[] q, float[] f, int[] flags, int i, int n, int[] out,
                                 int outOff, Integer[] sort) {
        var count = 0;
        var start = vi.normalP != 0 ? vi.normalStart - i : n;
        if (start > n) {
            start = n;
        }
        var acc = 0.f;
        var j = 0;
        for (; j < start; j++) {
            if (flags == null || flags[j] == 0) {
                var ve = q[j] / f[j];
                if (r[j] < 0) {
                    out[outOff + j] = (int) -Math.rint(Math.sqrt(ve));
                } else {
                    out[outOff + j] = (int) Math.rint(Math.sqrt(ve));
                }
            }
        }
        for (; j < n; j++) {
            if (flags == null || flags[j] == 0) {
                var ve = q[j] / f[j];
                if (ve < .25f && (flags == null || j >= limit - i)) {
                    acc += ve;
                    sort[count++] = j;
                } else {
                    if (r[j] < 0) {
                        out[outOff + j] = (int) -Math.rint(Math.sqrt(ve));
                    } else {
                        out[outOff + j] = (int) Math.rint(Math.sqrt(ve));
                    }
                    q[j] = out[outOff + j] * out[outOff + j] * f[j];
                }
            }
        }
        if (count > 0) {
            Arrays.sort(sort, 0, count, (a, b) -> {
                var fa = q[a];
                var fb = q[b];
                var order = (fa < fb ? 1 : 0) - (fa > fb ? 1 : 0);
                return order != 0 ? order : Integer.compare(a, b);
            });
            for (j = 0; j < count; j++) {
                int k = sort[j];
                if (acc >= vi.normalThreshold) {
                    out[outOff + k] = (int) Scales.unitNorm(r[k]);
                    acc -= 1.f;
                    q[k] = f[k];
                } else {
                    out[outOff + k] = 0;
                    q[k] = 0.f;
                }
            }
        }
        return acc;
    }

    /**
     * Quantizes the residue against the coded floor, normalizes noise and couples channels
     * ({@code _vp_couple_quantize_normalize}).
     *
     * @param blob           the packet blob, {@link PsyGlobal#PACKET_BLOBS}{@code /2} unmanaged
     * @param g              the global parameters
     * @param map            the mapping
     * @param mdct           the MDCT spectrum of each channel
     * @param iwork          the coded floor of each channel on input, the quantized residue on
     *                       output
     * @param nonzero        whether each channel has a non-zero floor, updated
     * @param slidingLowpass the lowpass in lines
     * @param channels       the number of channels
     * @param scratch        the scratch state
     */
    public void coupleQuantizeNormalize(int blob, PsyGlobal g, Mapping0Info map, float[][] mdct, int[][] iwork,
                                        int[] nonzero, int slidingLowpass, int channels, CouplingScratch scratch) {
        var partition = vi.normalP != 0 ? vi.normalPartition : 16;
        var limit = g.couplingPointLimit[vi.blockFlag][blob];
        var prePoint = (float) STEREO_THRESHOLDS[g.couplingPrePointAmp[blob]];
        var postPoint = (float) STEREO_THRESHOLDS[g.couplingPostPointAmp[blob]];
        if (n > 1000) {
            postPoint = (float) STEREO_THRESHOLDS_LIMITED[g.couplingPostPointAmp[blob]];
        }
        var raw = scratch.raw;
        var quant = scratch.quant;
        var floor = scratch.floor;
        var flag = scratch.flag;
        var nz = scratch.nz;
        var acc = scratch.acc;
        Arrays.fill(acc, 0.f);
        var table = FloorTables.INVERSE_DB;
        for (var i = 0; i < n; i += partition) {
            var jn = Math.min(partition, n - i);
            var track = 0;
            System.arraycopy(nonzero, 0, nz, 0, channels);
            for (var k = 0; k < channels; k++) {
                Arrays.fill(flag[k], 0);
            }
            for (var k = 0; k < channels; k++) {
                var iout = iwork[k];
                if (nz[k] != 0) {
                    for (var j = 0; j < jn; j++) {
                        floor[k][j] = table[iout[i + j]];
                    }
                    flagLossless(limit, prePoint, postPoint, mdct[k], i, floor[k], flag[k], i, jn);
                    for (var j = 0; j < jn; j++) {
                        var m = mdct[k][i + j];
                        quant[k][j] = raw[k][j] = m * m;
                        if (m < 0.f) {
                            raw[k][j] *= -1.f;
                        }
                        floor[k][j] *= floor[k][j];
                    }
                    acc[track] = noiseNormalize(limit, raw[k], quant[k], floor[k], null, i, jn, iout, i, scratch.sort);
                } else {
                    for (var j = 0; j < jn; j++) {
                        floor[k][j] = 1e-10f;
                        raw[k][j] = 0.f;
                        quant[k][j] = 0.f;
                        flag[k][j] = 0;
                        iout[i + j] = 0;
                    }
                    acc[track] = 0.f;
                }
                track++;
            }
            for (var step = 0; step < map.couplingSteps; step++) {
                var mi = map.couplingMagnitude[step];
                var ai = map.couplingAngle[step];
                var im = iwork[mi];
                var ia = iwork[ai];
                var reM = raw[mi];
                var reA = raw[ai];
                var qeM = quant[mi];
                var qeA = quant[ai];
                var floorM = floor[mi];
                var floorA = floor[ai];
                var fm = flag[mi];
                var fa = flag[ai];
                if (nz[mi] != 0 || nz[ai] != 0) {
                    nz[mi] = nz[ai] = 1;
                    for (var j = 0; j < jn; j++) {
                        if (j < slidingLowpass - i) {
                            if (fm[j] != 0 || fa[j] != 0) {
                                reM[j] = (float) (Math.abs((double) reM[j]) + Math.abs((double) reA[j]));
                                qeM[j] = qeM[j] + qeA[j];
                                fm[j] = fa[j] = 1;
                                var a = im[i + j];
                                var b = ia[i + j];
                                if (Math.abs(a) > Math.abs(b)) {
                                    ia[i + j] = a > 0 ? a - b : b - a;
                                } else {
                                    ia[i + j] = b > 0 ? a - b : b - a;
                                    im[i + j] = b;
                                }
                                if (ia[i + j] >= Math.abs(im[i + j]) * 2) {
                                    ia[i + j] = -ia[i + j];
                                    im[i + j] = -im[i + j];
                                }
                            } else {
                                if (j < limit - i) {
                                    reM[j] += reA[j];
                                    qeM[j] = Math.abs(reM[j]);
                                } else {
                                    if (reM[j] + reA[j] < 0) {
                                        reM[j] = -(qeM[j] = (float) (Math.abs((double) reM[j]) + Math.abs((double) reA[j])));
                                    } else {
                                        reM[j] = (qeM[j] = (float) (Math.abs((double) reM[j]) + Math.abs((double) reA[j])));
                                    }
                                }
                                reA[j] = qeA[j] = 0.f;
                                fa[j] = 1;
                                ia[i + j] = 0;
                            }
                        }
                        floorM[j] = floorA[j] = floorM[j] + floorA[j];
                    }
                    acc[track] = noiseNormalize(limit, raw[mi], quant[mi], floor[mi], flag[mi], i, jn, im, i, scratch.sort);
                    track++;
                }
            }
        }
        for (var i = 0; i < map.couplingSteps; i++) {
            if (nonzero[map.couplingMagnitude[i]] != 0 || nonzero[map.couplingAngle[i]] != 0) {
                nonzero[map.couplingMagnitude[i]] = 1;
                nonzero[map.couplingAngle[i]] = 1;
            }
        }
    }
}
