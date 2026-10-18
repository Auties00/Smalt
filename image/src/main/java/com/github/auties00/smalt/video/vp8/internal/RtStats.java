package com.github.auties00.smalt.video.vp8.internal;

import java.util.Arrays;

/**
 * Accumulates the statistics the real-time encoder gathers while coding the macroblocks of a
 * frame: token branch counts, mode and motion vector counts, reference usage and skips.
 *
 * <p>Each thread coding rows owns one instance; the encoder adds them together once the frame's
 * rows are coded. Every statistic is a plain count, so the sums do not depend on which thread
 * coded which row.
 */
final class RtStats {
    /**
     * The number of motion vector component values counted per component, libvpx's
     * {@code MVvals}.
     */
    static final int MV_VALS = 2047;

    /**
     * The index of the zero motion vector component, libvpx's {@code mv_max}.
     */
    static final int MV_MAX = 1023;

    /**
     * The number of distortion bins of the speed features above speed 6.
     */
    static final int ERROR_BINS = 1024;

    /**
     * The number of times each token probability coded a zero ({@code [2 * p]}) and a one
     * ({@code [2 * p + 1]}).
     */
    final int[] coefCounts = new int[2 * 1056];

    /**
     * The number of intra macroblocks coded with each luma mode.
     */
    final int[] ymodeCounts = new int[5];

    /**
     * The number of intra macroblocks coded with each chroma mode.
     */
    final int[] uvModeCounts = new int[4];

    /**
     * The number of new motion vectors with each row ({@code [0, MV_VALS)}) and column
     * ({@code [MV_VALS, 2 * MV_VALS)}) difference from their reference vector, in quarter pixels
     * offset by {@link #MV_MAX}.
     */
    final int[] mvCounts = new int[2 * MV_VALS];

    /**
     * The number of macroblocks coded from each reference.
     */
    final int[] refUsage = new int[4];

    /**
     * The number of macroblocks in each segment.
     */
    final int[] segmentCounts = new int[4];

    /**
     * The number of macroblocks without coefficients.
     */
    int skipTrueCount;

    /**
     * The number of macroblocks whose best inter distortion fell in each bin of 128.
     */
    final int[] errorBins = new int[ERROR_BINS];

    /**
     * The sum of the mode decision rates, in 1/256 bit.
     */
    long totalRate;

    /**
     * Creates empty statistics.
     */
    RtStats() {
    }

    /**
     * Clears every statistic.
     */
    void clear() {
        Arrays.fill(coefCounts, 0);
        Arrays.fill(ymodeCounts, 0);
        Arrays.fill(uvModeCounts, 0);
        Arrays.fill(mvCounts, 0);
        Arrays.fill(refUsage, 0);
        Arrays.fill(segmentCounts, 0);
        Arrays.fill(errorBins, 0);
        skipTrueCount = 0;
        totalRate = 0;
    }

    /**
     * Adds another thread's statistics to these.
     *
     * @param other the statistics to add
     */
    void add(RtStats other) {
        for (var i = 0; i < coefCounts.length; i++) {
            coefCounts[i] += other.coefCounts[i];
        }
        for (var i = 0; i < 5; i++) {
            ymodeCounts[i] += other.ymodeCounts[i];
        }
        for (var i = 0; i < 4; i++) {
            uvModeCounts[i] += other.uvModeCounts[i];
            refUsage[i] += other.refUsage[i];
            segmentCounts[i] += other.segmentCounts[i];
        }
        for (var i = 0; i < mvCounts.length; i++) {
            mvCounts[i] += other.mvCounts[i];
        }
        for (var i = 0; i < ERROR_BINS; i++) {
            errorBins[i] += other.errorBins[i];
        }
        skipTrueCount += other.skipTrueCount;
        totalRate += other.totalRate;
    }
}
