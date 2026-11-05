package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;

import java.util.Arrays;

/**
 * Collects the first-pass records of a clip, one {@link #FIELDS}-value record per picture, for
 * the public first pass of a two-pass VP9 encode. Instances are used by one thread at a time.
 */
public final class Vp9FirstPassRunner {
    /**
     * The number of values of a record.
     */
    public static final int FIELDS = Vp9FirstPassAnalyzer.FIELDS;

    /**
     * The analyzer.
     */
    private final Vp9FirstPassAnalyzer analyzer;

    /**
     * The copy of the picture being measured.
     */
    private final Vp9EncPicture picture;

    /**
     * The number of threads.
     */
    private final int threads;

    /**
     * The records measured so far.
     */
    private double[] records = new double[64 * FIELDS];

    /**
     * The number of records.
     */
    private int count;

    /**
     * Creates a first pass of a picture format.
     *
     * @param width the picture width
     * @param height the picture height
     * @param ssX the horizontal chroma subsampling shift
     * @param ssY the vertical chroma subsampling shift
     * @param bitDepth the bit depth
     * @param threads the number of threads
     */
    public Vp9FirstPassRunner(int width, int height, int ssX, int ssY, int bitDepth, int threads) {
        this.analyzer = new Vp9FirstPassAnalyzer(width, height);
        this.picture = new Vp9EncPicture(width, height, ssX, ssY, bitDepth);
        this.threads = Math.max(1, threads);
    }

    /**
     * Measures an 8-bit picture.
     *
     * @param source the picture, of the pass's size and chroma format, or 4:4:4 for 4:4:0
     * @param durationTicks the picture's duration in {@link Vp9RtEncoder#TICKS_PER_SECOND} ticks
     */
    public void add(YuvPicture source, long durationTicks) {
        picture.copyFrom(source, threads);
        measure(durationTicks);
    }

    /**
     * Measures a high bit depth picture.
     *
     * @param source the picture, of the pass's size, bit depth and chroma format, or 4:4:4 for
     * 4:4:0
     * @param durationTicks the picture's duration in ticks
     */
    public void add(Yuv16Picture source, long durationTicks) {
        picture.copyFrom(source, threads);
        measure(durationTicks);
    }

    /**
     * Measures the copied picture and keeps its record.
     *
     * @param durationTicks the picture's duration in ticks
     */
    private void measure(long durationTicks) {
        if ((count + 1) * FIELDS > records.length) {
            records = Arrays.copyOf(records, records.length * 2);
        }
        analyzer.analyze(picture, durationTicks, false, records, count * FIELDS, threads);
        count++;
    }

    /**
     * Returns the records measured so far.
     *
     * @return a new array of {@link #count()} records
     */
    public double[] records() {
        return Arrays.copyOf(records, count * FIELDS);
    }

    /**
     * Returns the number of pictures measured.
     *
     * @return the count
     */
    public int count() {
        return count;
    }
}
