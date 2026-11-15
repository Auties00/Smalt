package com.github.auties00.smalt.audio.loudness;

/**
 * Holds the loudness measurements of a program, EBU R128 style.
 *
 * <p>Loudness values are in LUFS (loudness units relative to full scale, ITU-R BS.1770-4), loudness
 * differences in LU; a program with nothing above the absolute gate (-70 LUFS) has an
 * integrated loudness of negative infinity. Peaks are linear magnitudes, 1.0 being full scale;
 * {@link #samplePeakDb()} and {@link #truePeakDb()} give them in dBFS and dBTP.
 *
 * @param integrated        the integrated (program) loudness in LUFS, gated as BS.1770-4
 *                          specifies; negative infinity when no block passes the gates
 * @param loudnessRange     the loudness range (LRA) in LU, EBU Tech 3342; {@code 0} when fewer
 *                          than two short-term blocks pass its gates
 * @param loudnessRangeLow  the lower bound of the loudness range (the 10th percentile) in LUFS,
 *                          negative infinity when there is no range
 * @param loudnessRangeHigh the upper bound of the loudness range (the 95th percentile) in LUFS,
 *                          negative infinity when there is no range
 * @param relativeThreshold the relative gate of the integrated loudness in LUFS: 10 LU below the
 *                          loudness of the blocks above the absolute gate, {@code -70} when there
 *                          are none
 * @param maxMomentary      the largest momentary loudness (400 ms windows) in LUFS, negative
 *                          infinity before the first full window
 * @param maxShortTerm      the largest short-term loudness (3 s windows) in LUFS, negative
 *                          infinity before the first full window
 * @param samplePeak        the largest sample magnitude over every channel
 * @param truePeak          the largest true-peak magnitude over every channel (BS.1770-4 Annex
 *                          2, never below the sample peak), or {@code NaN} when it was not measured
 * @param durationMicros    the duration measured, in microseconds
 */
public record LoudnessStats(double integrated, double loudnessRange, double loudnessRangeLow, double loudnessRangeHigh,
                            double relativeThreshold, double maxMomentary, double maxShortTerm, double samplePeak,
                            double truePeak, long durationMicros) {
    /**
     * Returns the sample peak in decibels relative to full scale.
     *
     * @return the peak in dBFS, negative infinity for silence
     */
    public double samplePeakDb() {
        return 20 * StrictMath.log10(samplePeak);
    }

    /**
     * Returns the true peak in decibels relative to full scale.
     *
     * @return the peak in dBTP, negative infinity for silence, {@code NaN} when it was not measured
     */
    public double truePeakDb() {
        return 20 * StrictMath.log10(truePeak);
    }
}
