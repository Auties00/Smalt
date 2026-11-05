package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.image.Picture;

/**
 * Takes the first-pass measurements of the frames of a video in display order, as the
 * good-quality encoder takes them on its lookahead, for a second pass over the whole video.
 *
 * <p>An instance is used by one thread at a time.
 */
public final class FirstPassAnalyzer {
    /**
     * The number of values of one frame's measurements.
     */
    public static final int VALUES = 12;

    /**
     * The picture width.
     */
    private final int width;

    /**
     * The picture height.
     */
    private final int height;

    /**
     * The luma of the previous frame, or {@code null} before the second frame.
     */
    private short[] last;

    /**
     * The luma of the frame before the previous one, or {@code null}.
     */
    private short[] second;

    /**
     * Creates an analyzer of pictures of a size.
     *
     * @param width the picture width
     * @param height the picture height
     */
    public FirstPassAnalyzer(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
     * Measures the next frame and writes its values.
     *
     * @param picture the picture, a {@code YuvPicture} or a {@code Yuv16Picture} of the size
     * @param threads the most threads to use
     * @param out receives {@link #VALUES} values from {@code offset}
     * @param offset the index of the first value
     */
    public void analyze(Picture picture, int threads, double[] out, int offset) {
        var luma = FirstPass.luma(picture);
        write(FirstPass.measure(luma, last, second, width, height, threads), out, offset);
        second = last;
        last = luma;
    }

    /**
     * Writes a frame's statistics as values.
     *
     * @param s the statistics
     * @param out the values
     * @param offset the index of the first value
     */
    static void write(FirstPass.Stats s, double[] out, int offset) {
        out[offset] = s.intraError;
        out[offset + 1] = s.codedError;
        out[offset + 2] = s.srCodedError;
        out[offset + 3] = s.pcntInter;
        out[offset + 4] = s.pcntMotion;
        out[offset + 5] = s.pcntSecondRef;
        out[offset + 6] = s.pcntNeutral;
        out[offset + 7] = s.mvInOutCount;
        out[offset + 8] = s.mvr;
        out[offset + 9] = s.mvrAbs;
        out[offset + 10] = s.mvc;
        out[offset + 11] = s.mvcAbs;
    }

    /**
     * Reads a frame's statistics from values.
     *
     * @param values the measurements of the frames, {@link #VALUES} per frame
     * @param offset the index of the first value
     * @return the statistics
     */
    static FirstPass.Stats read(double[] values, int offset) {
        var s = new FirstPass.Stats();
        s.intraError = values[offset];
        s.codedError = values[offset + 1];
        s.srCodedError = values[offset + 2];
        s.pcntInter = values[offset + 3];
        s.pcntMotion = values[offset + 4];
        s.pcntSecondRef = values[offset + 5];
        s.pcntNeutral = values[offset + 6];
        s.mvInOutCount = values[offset + 7];
        s.mvr = values[offset + 8];
        s.mvrAbs = values[offset + 9];
        s.mvc = values[offset + 10];
        s.mvcAbs = values[offset + 11];
        return s;
    }
}
