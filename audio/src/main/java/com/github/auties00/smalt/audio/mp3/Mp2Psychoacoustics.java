/* Ports the psychoacoustic model 1 of the ISO/IEC 13818-5 MPEG-2 audio reference software (dist10,
 * lsf/encoder/tonal.c, with its LONDON, MAKE_SENSE and MI_OPTION modifications). Copyright ISO MPEG Audio Subgroup
 * Software Simulation Group; ISO/IEC 13818-5 reference software licence. Modified for Smalt. */
package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.audio.voice.internal.OouraRealFft;

import java.util.Arrays;

/**
 * Computes the signal-to-mask ratio of every subband of a Layer I or Layer II frame with the
 * psychoacoustic model 1 of ISO/IEC 11172-3 Annex D.
 *
 * <p>Each channel's frame is windowed and transformed (a 1024-point FFT for Layer II, 512-point for
 * Layer I), local maxima at least 7 dB above their neighborhood become tonal components, the rest of
 * every critical band becomes one non-tonal component, components below the threshold in quiet or
 * within half a bark of a stronger tone are dropped, and the individual masking thresholds of the
 * remaining components are summed with the threshold in quiet at the threshold points of the tables.
 * The ratio of a subband is the larger of its spectral peak and its scalefactor level, less the
 * smallest threshold among its points. Instances are not thread-safe.
 *
 * @implNote This implementation sums the masking contributions as powers instead of adding decibel
 * values pairwise, which gives the same threshold with one exponential per contribution.
 */
final class Mp2Psychoacoustics {
    /**
     * The level of a full-scale sine of the FFT normalization, {@code 20 log10(32768)}, in decibels.
     */
    private static final double POWER_NORMALIZATION = 90.3090;

    /**
     * The level standing for minus infinity, in decibels.
     */
    private static final double DB_MIN = -200.0;

    /**
     * The end of a component list.
     */
    private static final int LAST = -1;

    /**
     * The link of a line that is in no list.
     */
    private static final int STOP = -100;

    /**
     * The type of a line that is not a component.
     */
    private static final byte NONE = 0;

    /**
     * The type of a non-tonal component.
     */
    private static final byte NOISE = 1;

    /**
     * The type of a tonal component.
     */
    private static final byte TONE = 2;

    /**
     * Whether this model analyzes Layer I frames (512-point FFT, 384 samples per frame).
     */
    private final boolean layer1;

    /**
     * The FFT length.
     */
    private final int fftSize;

    /**
     * The number of spectral lines analyzed, half the FFT length.
     */
    private final int lines;

    /**
     * The number of samples per channel of a frame.
     */
    private final int frameSamples;

    /**
     * The length of the input history of each channel.
     */
    private final int historySize;

    /**
     * The offset, from the newest frame's write position, of the first sample the FFT reads.
     */
    private final int readOffset;

    /**
     * The critical band boundaries, as spectral lines.
     */
    private final int[] bounds;

    /**
     * The spectral line of each threshold point; entry 0 is unused.
     */
    private final int[] pointLine;

    /**
     * The critical band rate of each threshold point, in bark.
     */
    private final double[] pointBark;

    /**
     * The threshold in quiet of each threshold point, in decibels.
     */
    private final double[] pointQuiet;

    /**
     * The threshold point of every spectral line.
     */
    private final int[] map;

    /**
     * The input history of each channel, written circularly.
     */
    private final double[][] history;

    /**
     * The write position of each channel's history.
     */
    private final int[] historyOffset;

    /**
     * The FFT.
     */
    private final OouraRealFft fft;

    /**
     * The analysis window, Hann scaled by {@code sqrt(8/3) / fftSize}.
     */
    private final float[] window;

    /**
     * The FFT input and output.
     */
    private final float[] spectrum;

    /**
     * The level of every spectral line, in decibels, as the labeling stages rewrite it.
     */
    private final double[] level;

    /**
     * The next component of every line's list, or {@link #LAST} or {@link #STOP}.
     */
    private final int[] next;

    /**
     * The component type of every line.
     */
    private final byte[] type;

    /**
     * The global masking threshold at every threshold point, in decibels.
     */
    private final double[] global;

    /**
     * The first tonal component after {@link #subsample}, or {@link #LAST}.
     */
    private int firstTone;

    /**
     * The first non-tonal component after {@link #subsample}, or {@link #LAST}.
     */
    private int firstNoise;

    /**
     * The spectral peak of every subband, in decibels.
     */
    private final double[] spike = new double[32];

    /**
     * Constructs the model of one layer and sampling rate.
     *
     * @param layer the layer, 1 or 2
     * @param rateIndex the table rate: 0 for 44.1 kHz, 1 for 48 kHz, 2 for 32 kHz, 3 for 22.05 kHz,
     * 4 for 24 kHz and 5 for 16 kHz
     * @param channels the number of channels, 1 or 2
     */
    Mp2Psychoacoustics(int layer, int rateIndex, int channels) {
        this.layer1 = layer == 1;
        this.fftSize = layer1 ? 512 : 1024;
        this.lines = fftSize / 2;
        this.frameSamples = layer1 ? 384 : 1152;
        this.historySize = layer1 ? 640 : 1408;
        this.readOffset = layer1 ? 448 : 1216;
        var table = 6 * (layer - 1) + rateIndex;
        this.bounds = Mp2PsychoTables.BOUNDARIES[table];
        this.pointLine = Mp2PsychoTables.LINES[table];
        this.pointBark = Mp2PsychoTables.BARK[table];
        this.pointQuiet = Mp2PsychoTables.QUIET[table];
        this.map = new int[lines];
        for (var i = 1; i < pointLine.length; i++) {
            for (var j = pointLine[i - 1]; j <= pointLine[i] && j < lines; j++) {
                map[j] = i;
            }
        }
        this.history = new double[channels][historySize];
        this.historyOffset = new int[channels];
        Arrays.fill(historyOffset, 256);
        this.fft = new OouraRealFft(fftSize);
        this.window = new float[fftSize];
        var scale = StrictMath.sqrt(8.0 / 3.0) * 0.5 / fftSize;
        for (var i = 0; i < fftSize; i++) {
            window[i] = (float) (scale * (1 - StrictMath.cos(2.0 * StrictMath.PI * i / fftSize)));
        }
        this.spectrum = new float[fftSize];
        this.level = new double[lines];
        this.next = new int[lines];
        this.type = new byte[lines];
        this.global = new double[pointLine.length];
    }

    /**
     * Returns {@code 10 log10(10^(a/10) + 10^(b/10))}.
     *
     * @param a a level in decibels
     * @param b a level in decibels
     * @return the level of the sum of the powers
     */
    private static double addDb(double a, double b) {
        return 10 * StrictMath.log10(StrictMath.pow(10.0, a / 10.0) + StrictMath.pow(10.0, b / 10.0));
    }

    /**
     * Returns {@code 10^(db/10)}.
     *
     * @param db a level in decibels
     * @return the power
     */
    private static double power(double db) {
        return StrictMath.pow(10.0, db / 10.0);
    }

    /**
     * Analyzes one channel of a frame.
     *
     * @param channel the channel whose history the frame extends, from 0
     * @param pcm the frame's samples of the channel, {@code [-1, 1]}
     * @param scale the scale of every subband: the largest scalefactor multiplier of the frame
     * @param bands the number of coded subbands
     * @param kbpsPerChannel the bitrate per channel in kilobits per second; below 96 the threshold
     * in quiet counts in full, otherwise 12 dB lower
     * @param smr receives the signal-to-mask ratio of each coded subband, in decibels
     */
    void analyze(int channel, float[] pcm, double[] scale, int bands, int kbpsPerChannel, double[] smr) {
        var ring = history[channel];
        var offset = historyOffset[channel];
        for (var i = 0; i < frameSamples; i++) {
            ring[(i + offset) % historySize] = pcm[i];
        }
        for (var i = 0; i < fftSize; i++) {
            spectrum[i] = (float) (ring[(i + readOffset + offset) % historySize] * window[i]);
        }
        historyOffset[channel] = (offset + frameSamples) % historySize;
        transform();
        pickMax();
        var tone = labelTones();
        var noise = labelNoise();
        subsample(tone, noise);
        threshold(firstTone, firstNoise, kbpsPerChannel);
        minimumMask(bands, smr);
        for (var i = 0; i < bands; i++) {
            var peak = 20 * StrictMath.log10(scale[i] * 32768) - 10;
            if (spike[i] > peak) {
                peak = spike[i];
            }
            smr[i] = peak - smr[i];
        }
    }

    /**
     * Computes the level of every spectral line from the windowed input ({@code II_f_f_t}).
     */
    private void transform() {
        fft.forward(spectrum);
        for (var i = 0; i < lines; i++) {
            double energy;
            if (i == 0) {
                energy = (double) spectrum[0] * spectrum[0];
            } else {
                double re = spectrum[2 * i];
                double im = spectrum[2 * i + 1];
                energy = re * re + im * im;
            }
            if (energy < 1e-20) {
                energy = 1e-20;
            }
            level[i] = 10 * StrictMath.log10(energy) + POWER_NORMALIZATION;
            next[i] = STOP;
            type[i] = NONE;
        }
    }

    /**
     * Computes the level of the power summed over the lines of each subband ({@code II_pick_max}
     * with the {@code LONDON} modification).
     */
    private void pickMax() {
        var perBand = lines / 32;
        for (var band = 0; band < 32; band++) {
            var sum = power(DB_MIN);
            for (var j = 0; j < perBand; j++) {
                sum += power(level[band * perBand + j]);
            }
            spike[band] = 10.0 * StrictMath.log10(sum);
        }
    }

    /**
     * Finds the tonal components ({@code II_tonal_label}): every local maximum at least 7 dB
     * above the lines a frequency-dependent distance away absorbs its two neighbors.
     *
     * @return the first tonal component, or {@link #LAST}
     */
    private int labelTones() {
        var last = LAST;
        var first = LAST;
        var tone = LAST;
        var upper = layer1 ? lines - 6 : lines - 12;
        for (var i = 2; i < upper; i++) {
            if (level[i] > level[i - 1] && level[i] >= level[i + 1]) {
                type[i] = TONE;
                next[i] = LAST;
                if (last != LAST) {
                    next[last] = i;
                } else {
                    first = i;
                    tone = i;
                }
                last = i;
            }
        }
        last = LAST;
        first = tone;
        tone = LAST;
        var lastButOne = LAST;
        var top = layer1 ? 250 : 500;
        while (first != LAST) {
            int run;
            if (first < 3 || first > top) {
                run = 0;
            } else if (first < 63) {
                run = 2;
            } else if (first < 127) {
                run = 3;
            } else if (layer1 || first < 255) {
                run = 6;
            } else {
                run = 12;
            }
            var max = level[first] - 7;
            for (var j = 2; j <= run; j++) {
                if (max < level[first - j] || max < level[first + j]) {
                    type[first] = NONE;
                    break;
                }
            }
            if (type[first] == TONE) {
                var help = first;
                if (tone == LAST) {
                    tone = first;
                }
                while (next[help] != LAST && next[help] - first <= run) {
                    help = next[help];
                }
                help = next[help];
                next[first] = help;
                if (first - last <= run && lastButOne != LAST) {
                    next[lastButOne] = first;
                }
                if (first > 1 && first < (layer1 ? 255 : 500)) {
                    var neighbors = addDb(level[first - 1], level[first + 1]);
                    level[first] = addDb(level[first], neighbors);
                }
                for (var j = 1; j <= run; j++) {
                    level[first - j] = DB_MIN;
                    level[first + j] = DB_MIN;
                    next[first - j] = STOP;
                    next[first + j] = STOP;
                    type[first - j] = NONE;
                    type[first + j] = NONE;
                }
                lastButOne = last;
                last = first;
                first = next[first];
            } else {
                if (last != LAST) {
                    next[last] = next[first];
                }
                var removed = first;
                first = next[first];
                next[removed] = STOP;
            }
        }
        return tone;
    }

    /**
     * Replaces the lines of every critical band that are not tonal by one non-tonal component at
     * their power-weighted center ({@code noise_label} with the {@code MAKE_SENSE} and
     * {@code MI_OPTION} modifications).
     *
     * @return the first non-tonal component, or {@link #LAST}
     */
    private int labelNoise() {
        var last = LAST;
        var noise = LAST;
        for (var i = 0; i < bounds.length - 1; i++) {
            var sum = power(DB_MIN);
            var weight = 0.0;
            var empty = true;
            for (var j = bounds[i]; j < bounds[i + 1]; j++) {
                if (type[j] != TONE && level[j] != DB_MIN) {
                    var p = power(level[j]);
                    sum += p;
                    weight += p * (j - bounds[i]) / (double) (bounds[i + 1] - bounds[i]);
                    level[j] = DB_MIN;
                    empty = false;
                }
            }
            var sumDb = empty ? DB_MIN : 10 * StrictMath.log10(sum);
            int centre;
            if (empty) {
                centre = (bounds[i + 1] + bounds[i]) / 2;
            } else {
                var index = weight / sum;
                centre = bounds[i] + (int) (index * (bounds[i + 1] - bounds[i]));
            }
            if (type[centre] == TONE) {
                if (type[centre + 1] == TONE) {
                    centre++;
                } else {
                    centre--;
                }
            }
            if (last == LAST) {
                noise = centre;
            } else {
                next[centre] = LAST;
                next[last] = centre;
            }
            level[centre] = sumDb;
            type[centre] = NOISE;
            last = centre;
        }
        return noise;
    }

    /**
     * Drops the components below the threshold in quiet, and of two tonal components closer than
     * half a bark the weaker ({@code subsampling}).
     *
     * <p>The new first components are left in {@link #firstTone} and {@link #firstNoise}.
     *
     * @param tone the first tonal component
     * @param noise the first non-tonal component
     */
    private void subsample(int tone, int noise) {
        tone = dropInaudible(tone);
        noise = dropInaudible(noise);
        var i = tone;
        var old = STOP;
        while (i != LAST) {
            if (next[i] == LAST) {
                break;
            }
            if (pointBark[map[next[i]]] - pointBark[map[i]] < 0.5) {
                if (level[next[i]] > level[i]) {
                    if (old == STOP) {
                        tone = next[i];
                    } else {
                        next[old] = next[i];
                    }
                    type[i] = NONE;
                    level[i] = DB_MIN;
                    i = next[i];
                } else {
                    var weaker = next[i];
                    type[weaker] = NONE;
                    level[weaker] = DB_MIN;
                    next[i] = next[weaker];
                    old = i;
                }
            } else {
                old = i;
                i = next[i];
            }
        }
        firstTone = tone;
        firstNoise = noise;
    }

    /**
     * Removes the components of a list whose level is below the threshold in quiet.
     *
     * @param head the first component
     * @return the new first component
     */
    private int dropInaudible(int head) {
        var i = head;
        var old = STOP;
        while (i != LAST) {
            if (level[i] < pointQuiet[map[i]]) {
                type[i] = NONE;
                level[i] = DB_MIN;
                if (old == STOP) {
                    head = next[i];
                } else {
                    next[old] = next[i];
                }
            } else {
                old = i;
            }
            i = next[i];
        }
        return head;
    }

    /**
     * Computes the global masking threshold at every threshold point: the individual thresholds of
     * the components within 3 bark below and 8 bark above, plus the threshold in quiet
     * ({@code threshold}).
     *
     * @param tone the first tonal component
     * @param noise the first non-tonal component
     * @param kbpsPerChannel the bitrate per channel in kilobits per second
     */
    private void threshold(int tone, int noise, int kbpsPerChannel) {
        for (var k = 1; k < pointLine.length; k++) {
            var sum = power(DB_MIN);
            sum += masking(k, tone, 0.275, 4.5);
            sum += masking(k, noise, 0.175, 0.5);
            var quiet = kbpsPerChannel < 96 ? pointQuiet[k] : pointQuiet[k] - 12.0;
            global[k] = 10 * StrictMath.log10(sum + power(quiet));
        }
    }

    /**
     * Returns the summed power of the individual masking thresholds of a component list at a
     * threshold point.
     *
     * @param k the threshold point
     * @param head the first component
     * @param barkSlope the decrease of the masking index per bark of the masker
     * @param offset the masking index offset, in decibels
     * @return the power
     */
    private double masking(int k, int head, double barkSlope, double offset) {
        var sum = 0.0;
        var bark = pointBark[k];
        for (var t = head; t != LAST; t = next[t]) {
            var maskerBark = pointBark[map[t]];
            var dz = bark - maskerBark;
            if (dz < -3.0 || dz >= 8.0) {
                continue;
            }
            var x = level[t];
            var value = -1.525 - barkSlope * maskerBark - offset + x;
            double vf;
            if (dz < -1) {
                vf = 17 * (dz + 1) - (0.4 * x + 6);
            } else if (dz < 0) {
                vf = (0.4 * x + 6) * dz;
            } else if (dz < 1) {
                vf = -17 * dz;
            } else {
                vf = -(dz - 1) * (17 - 0.15 * x) - 17;
            }
            sum += power(value + vf);
        }
        return sum;
    }

    /**
     * Stores the smallest global threshold among the points of each subband ({@code II_minimum_mask}).
     *
     * @param bands the number of coded subbands
     * @param out receives the threshold of each subband, in decibels
     */
    private void minimumMask(int bands, double[] out) {
        var size = pointLine.length;
        var shift = layer1 ? 3 : 4;
        var j = 1;
        for (var i = 0; i < bands; i++) {
            if (j >= size - 1) {
                out[i] = pointQuiet[size - 1];
            } else {
                var min = global[j];
                while (j < size && pointLine[j] >> shift == i) {
                    if (min > global[j]) {
                        min = global[j];
                    }
                    j++;
                }
                out[i] = min;
            }
        }
    }
}
