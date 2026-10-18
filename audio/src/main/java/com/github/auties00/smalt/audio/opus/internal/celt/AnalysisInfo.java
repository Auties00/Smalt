/* Ports libopus 1.5.2 (celt/celt.h). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Holds the result of the Opus tonality and music/speech analysis that steers the CELT encoder
 * ({@code AnalysisInfo}).
 *
 * @implNote This implementation keeps the reference field names.
 */
public final class AnalysisInfo {
    /**
     * Nonzero when the analysis is valid.
     */
    public int valid;

    /**
     * The tonality estimate, 0 to 1.
     */
    public float tonality;

    /**
     * The tonality slope across bands.
     */
    public float tonality_slope;

    /**
     * The noisiness estimate.
     */
    public float noisiness;

    /**
     * The activity estimate.
     */
    public float activity;

    /**
     * The probability that the signal is music.
     */
    public float music_prob;

    /**
     * The smallest recent music probability.
     */
    public float music_prob_min;

    /**
     * The largest recent music probability.
     */
    public float music_prob_max;

    /**
     * The detected bandwidth, as a CELT band index.
     */
    public int bandwidth;

    /**
     * The probability that the frame is active (not silence or noise).
     */
    public float activity_probability;

    /**
     * The ratio limiting the pitch pre-filter gain.
     */
    public float max_pitch_ratio;

    /**
     * The per-band leakage boost in Q6, unsigned bytes stored in {@code int}s.
     */
    public final int[] leak_boost = new int[Celt.LEAK_BANDS];

    /**
     * Creates an invalid analysis.
     */
    public AnalysisInfo() {
    }

    /**
     * Copies another analysis into this one.
     *
     * @param o the analysis to copy
     */
    public void copyFrom(AnalysisInfo o) {
        valid = o.valid;
        tonality = o.tonality;
        tonality_slope = o.tonality_slope;
        noisiness = o.noisiness;
        activity = o.activity;
        music_prob = o.music_prob;
        music_prob_min = o.music_prob_min;
        music_prob_max = o.music_prob_max;
        bandwidth = o.bandwidth;
        activity_probability = o.activity_probability;
        max_pitch_ratio = o.max_pitch_ratio;
        System.arraycopy(o.leak_boost, 0, leak_boost, 0, Celt.LEAK_BANDS);
    }

    /**
     * Resets every field to zero, as clearing the reference structure does.
     */
    public void clear() {
        valid = 0;
        tonality = 0;
        tonality_slope = 0;
        noisiness = 0;
        activity = 0;
        music_prob = 0;
        music_prob_min = 0;
        music_prob_max = 0;
        bandwidth = 0;
        activity_probability = 0;
        max_pitch_ratio = 0;
        java.util.Arrays.fill(leak_boost, 0);
    }
}
