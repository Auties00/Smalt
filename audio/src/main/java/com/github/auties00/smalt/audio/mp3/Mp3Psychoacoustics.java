package com.github.auties00.smalt.audio.mp3;

/**
 * Computes the masking threshold of one channel: for every coding band of a granule, the
 * largest quantization noise energy the band can hide.
 *
 * <p>The model works on the transform lines of the granule. For each coding band it measures the
 * energy and, for long blocks, the spectral flatness, which gives a tonality between 0 (noise)
 * and 1 (a pure tone). The band's own masking threshold lies a signal-to-mask ratio below its
 * energy, interpolated between {@link #TONAL_SMR} for tones and {@link #NOISE_SMR} for noise.
 * Masking then spreads to neighboring bands with slopes in decibels per Bark, the threshold in
 * quiet bounds it from below, and for long blocks the threshold may rise by at most a factor of
 * two over the previous granule's (pre-echo control).
 *
 * @implNote This implementation is a simplification of the ISO/IEC 11172-3 psychoacoustic model 2
 * in the structure of 3GPP TS 26.403 section 5.4 (thresholds from band energies and a
 * signal-to-mask ratio, spreading by maximum propagation, threshold in quiet, pre-echo control),
 * working on the MDCT lines rather than a separate FFT. Tonality comes from the spectral
 * flatness measure of Johnston (IEEE JSAC 6(2), 1988) over at least {@link #FLATNESS_LINES}
 * lines. The threshold in quiet is Terhardt's approximation (JASA 65(1), 1979) with 0 dB SPL at
 * the level of a 16-bit LSB.
 */
final class Mp3Psychoacoustics {
    /**
     * The signal-to-mask ratio of a tonal band, in decibels.
     */
    static final float TONAL_SMR = 24;

    /**
     * The signal-to-mask ratio of a noise-like band, in decibels.
     */
    static final float NOISE_SMR = 6;

    /**
     * The signal-to-mask ratio of the bands of short blocks, in decibels.
     */
    static final float SHORT_SMR = 12;

    /**
     * The spectral flatness, in decibels, at and above which a band counts as noise.
     */
    private static final float NOISE_FLATNESS = -6;

    /**
     * The spectral flatness, in decibels, at and below which a band counts as a tone.
     */
    private static final float TONAL_FLATNESS = -24;

    /**
     * The fewest lines over which a long band's flatness is measured; narrower bands borrow lines
     * from their neighbors.
     */
    private static final int FLATNESS_LINES = 16;

    /**
     * The slope of masking towards higher frequencies, in decibels per Bark.
     */
    private static final float UPWARD_SLOPE = 15;

    /**
     * The slope of masking towards lower frequencies, in decibels per Bark.
     */
    private static final float DOWNWARD_SLOPE = 27;

    /**
     * The factor by which a long block's threshold may exceed the previous granule's.
     */
    private static final float PRE_ECHO_INCREASE = 2;

    /**
     * The fraction of a threshold that pre-echo control always keeps.
     */
    private static final float PRE_ECHO_FLOOR = 0.01f;

    /**
     * The energy, in the long transform, assigned to a full-scale sine summed over the lines it
     * occupies when placing the threshold in quiet. A full-scale sine measures about 1.0 on
     * average over granules (0.56 to 1.37 in single granules, by frequency and phase); half of
     * it places the threshold in quiet 3 dB lower, as for playback 3 dB louder than the 96 dB
     * full-scale reference.
     */
    static final float FULL_SCALE_LONG = 0.5f;

    /**
     * The energy, in one window of the short transform, assigned to a full-scale sine summed over
     * its lines; like {@link #FULL_SCALE_LONG}, half the measured energy of about 1.0.
     */
    static final float FULL_SCALE_SHORT = 0.5f;

    /**
     * The sound pressure level of a full-scale sine, in decibels: 0 dB SPL at a 16-bit LSB.
     */
    private static final double FULL_SCALE_SPL = 96;

    /**
     * The long-block layout.
     */
    private final Mp3BandLayout longLayout;

    /**
     * The short-block layout.
     */
    private final Mp3BandLayout shortLayout;

    /**
     * The threshold in quiet of each long coding band, as an energy.
     */
    private final float[] quietLong;

    /**
     * The threshold in quiet of each short coding band, as an energy.
     */
    private final float[] quietShort;

    /**
     * The upward spreading factor into each long band from the band below.
     */
    private final float[] spreadUpLong;

    /**
     * The downward spreading factor into each long band from the band above.
     */
    private final float[] spreadDownLong;

    /**
     * The upward spreading factor into each short band from the band below, per band.
     */
    private final float[] spreadUpShort;

    /**
     * The downward spreading factor into each short band from the band above, per band.
     */
    private final float[] spreadDownShort;

    /**
     * The threshold of each long band in the previous granule, for pre-echo control.
     */
    private final float[] previous = new float[22];

    /**
     * Whether {@link #previous} holds a granule.
     */
    private boolean primed;

    /**
     * Scratch: the line powers of a granule.
     */
    private final float[] powers = new float[576];

    /**
     * The thresholds of the last short window, for pre-echo control across short windows.
     */
    private final float[] previousShort = new float[13];

    /**
     * Whether {@link #previousShort} holds the window just before the next short window.
     */
    private boolean shortPrimed;

    /**
     * The width of one long-block line in hertz.
     */
    private final float longLineWidth;

    /**
     * Scratch: the thresholds of the 13 bands of one short window.
     */
    private final float[] shortScratch = new float[13];

    /**
     * Creates the model of one channel.
     *
     * @param rateIndex the index in {@link Mp3EncoderTables#SAMPLE_RATES}
     * @param longLayout the long-block layout
     * @param shortLayout the short-block layout
     * @param athOffset the offset added to the threshold in quiet, in decibels
     */
    Mp3Psychoacoustics(int rateIndex, Mp3BandLayout longLayout, Mp3BandLayout shortLayout, float athOffset) {
        this.longLayout = longLayout;
        this.shortLayout = shortLayout;
        var rate = Mp3EncoderTables.SAMPLE_RATES[rateIndex];
        this.longLineWidth = rate / 1152f;
        this.quietLong = quiet(longLayout, rate / 1152.0, FULL_SCALE_LONG, athOffset);
        this.quietShort = quiet(shortLayout, rate / 384.0, FULL_SCALE_SHORT, athOffset);
        this.spreadUpLong = new float[22];
        this.spreadDownLong = new float[22];
        for (var b = 1; b < 22; b++) {
            var distance = bark(longLayout.center[b]) - bark(longLayout.center[b - 1]);
            spreadUpLong[b] = (float) Math.pow(10, -UPWARD_SLOPE * distance / 10);
            spreadDownLong[b - 1] = (float) Math.pow(10, -DOWNWARD_SLOPE * distance / 10);
        }
        this.spreadUpShort = new float[13];
        this.spreadDownShort = new float[13];
        for (var b = 1; b < 13; b++) {
            var distance = bark(shortLayout.center[3 * b]) - bark(shortLayout.center[3 * b - 3]);
            spreadUpShort[b] = (float) Math.pow(10, -UPWARD_SLOPE * distance / 10);
            spreadDownShort[b - 1] = (float) Math.pow(10, -DOWNWARD_SLOPE * distance / 10);
        }
    }

    /**
     * Returns the threshold in quiet of every coding band of a layout.
     *
     * @param layout the coding band layout
     * @param lineWidth the width of one transform line in hertz
     * @param fullScale the band energy of a full-scale sine
     * @param offset the offset in decibels
     * @return the energies
     */
    private static float[] quiet(Mp3BandLayout layout, double lineWidth, float fullScale, float offset) {
        var result = new float[layout.count];
        for (var b = 0; b < layout.count; b++) {
            var first = layout.start[b] % 192;
            var last = (layout.end[b] - 1) % 192 + 1;
            if (!layout.shortBlocks) {
                first = layout.start[b];
                last = layout.end[b];
            }
            var minimum = Double.MAX_VALUE;
            for (var line = first; line < last; line++) {
                minimum = Math.min(minimum, thresholdInQuiet((line + 0.5) * lineWidth));
            }
            result[b] = (float) (fullScale * Math.pow(10, (minimum - FULL_SCALE_SPL + offset) / 10));
        }
        return result;
    }

    /**
     * Returns the threshold in quiet at a frequency (Terhardt).
     *
     * @param frequency the frequency in hertz
     * @return the level in dB SPL
     */
    static double thresholdInQuiet(double frequency) {
        var f = Math.max(frequency, 20) / 1000;
        var level = 3.64 * Math.pow(f, -0.8) - 6.5 * Math.exp(-0.6 * (f - 3.3) * (f - 3.3)) + 1e-3 * Math.pow(f, 4);
        return Math.min(level, 120);
    }

    /**
     * Returns the critical-band rate of a frequency (Zwicker and Terhardt).
     *
     * @param frequency the frequency in hertz
     * @return the rate in Bark
     */
    static double bark(double frequency) {
        return 13 * Math.atan(0.00076 * frequency) + 3.5 * Math.atan((frequency / 7500) * (frequency / 7500));
    }

    /**
     * Computes the energy and masking threshold of every coding band of a long, start or stop
     * granule.
     *
     * @param lines the 576 transform lines
     * @param energy receives the band energies
     * @param threshold receives the band thresholds
     */
    void analyzeLong(float[] lines, float[] energy, float[] threshold) {
        var layout = longLayout;
        var p = powers;
        for (var i = 0; i < 576; i++) {
            p[i] = lines[i] * lines[i];
        }
        for (var b = 0; b < 22; b++) {
            var sum = 0.0f;
            for (var i = layout.start[b]; i < layout.end[b]; i++) {
                sum += p[i];
            }
            energy[b] = sum;
            var tonality = tonality(p, layout.start[b], layout.end[b]);
            var smr = tonality * TONAL_SMR + (1 - tonality) * NOISE_SMR;
            threshold[b] = sum * (float) Math.pow(10, -smr / 10);
        }
        spread(threshold, 22, spreadUpLong, spreadDownLong);
        for (var b = 0; b < 22; b++) {
            var t = Math.max(threshold[b], quietLong[b]);
            if (primed) {
                t = Math.max(Math.min(t, PRE_ECHO_INCREASE * previous[b]), PRE_ECHO_FLOOR * t);
            }
            threshold[b] = Math.max(t, quietLong[b]);
            previous[b] = threshold[b];
        }
        primed = true;
        shortPrimed = false;
    }

    /**
     * Computes the energy and masking threshold of every coding band of a short granule.
     *
     * @param lines the window-major short spectra
     * @param energy receives the band energies, indexed like the short layout
     * @param threshold receives the band thresholds
     */
    void analyzeShort(float[] lines, float[] energy, float[] threshold) {
        var layout = shortLayout;
        var ratio = (float) Math.pow(10, -SHORT_SMR / 10);
        var window = shortScratch;
        var before = previousShort;
        if (!shortPrimed) {
            for (var b = 0; b < 13; b++) {
                var center = layout.center[3 * b];
                var band = 21;
                for (var l = 0; l < 22; l++) {
                    if (center < longLayout.end[l] * longLineWidth) {
                        band = l;
                        break;
                    }
                }
                before[b] = primed ? previous[band] / 3 : Float.MAX_VALUE;
            }
        }
        for (var w = 0; w < 3; w++) {
            for (var b = 0; b < 13; b++) {
                var index = 3 * b + w;
                var sum = 0.0f;
                for (var i = layout.start[index]; i < layout.end[index]; i++) {
                    sum += lines[i] * lines[i];
                }
                energy[index] = sum;
                window[b] = sum * ratio;
            }
            spread(window, 13, spreadUpShort, spreadDownShort);
            for (var b = 0; b < 13; b++) {
                var index = 3 * b + w;
                var t = Math.max(window[b], quietShort[index]);
                if (before[b] < Float.MAX_VALUE) {
                    t = Math.max(Math.min(t, PRE_ECHO_INCREASE * before[b]), PRE_ECHO_FLOOR * t);
                }
                t = Math.max(t, quietShort[index]);
                threshold[index] = t;
                before[b] = t;
            }
        }
        shortPrimed = true;
        primed = false;
    }

    /**
     * Returns the tonality of a band from the spectral flatness of its line powers.
     *
     * @param powers the line powers
     * @param start the first line of the band
     * @param end the end line of the band
     * @return the tonality, from 0 (noise) to 1 (a tone)
     */
    private static float tonality(float[] powers, int start, int end) {
        var width = end - start;
        if (width < FLATNESS_LINES) {
            var extra = FLATNESS_LINES - width;
            start = Math.max(0, start - extra / 2);
            end = Math.min(576, start + FLATNESS_LINES);
            start = Math.max(0, end - FLATNESS_LINES);
        }
        var arithmetic = 0.0;
        var logarithmic = 0.0;
        for (var i = start; i < end; i += 2) {
            var pair = powers[i] + powers[i + 1] + 1e-20;
            arithmetic += pair;
            logarithmic += Math.log(pair);
        }
        var pairs = (end - start) / 2;
        var flatness = 10 / Math.log(10) * (logarithmic / pairs - Math.log(arithmetic / pairs));
        var tonality = (NOISE_FLATNESS - flatness) / (NOISE_FLATNESS - TONAL_FLATNESS);
        return (float) Math.clamp(tonality, 0.0, 1.0);
    }

    /**
     * Spreads masking across bands: each band's threshold becomes at least its neighbors'
     * attenuated by the spreading slopes, propagated upwards then downwards.
     *
     * @param threshold the thresholds, updated in place
     * @param count the number of bands
     * @param up the upward factor into each band from the one below
     * @param down the downward factor into each band from the one above
     */
    private static void spread(float[] threshold, int count, float[] up, float[] down) {
        for (var b = 1; b < count; b++) {
            threshold[b] = Math.max(threshold[b], threshold[b - 1] * up[b]);
        }
        for (var b = count - 2; b >= 0; b--) {
            threshold[b] = Math.max(threshold[b], threshold[b + 1] * down[b]);
        }
    }
}
