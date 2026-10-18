package com.github.auties00.smalt.audio.aac.internal;

import java.util.Arrays;

/**
 * Computes the encoder's forward MDCT of one 1024-sample frame for any window sequence and
 * shape, the exact inverse of {@link Synthesis}.
 *
 * <p>Input samples are in {@code [-1, 1]}; spectral values come out in the decoder's domain,
 * {@code X[k] = 2 * 32768 * sum w[n] x[n] cos(2 pi / N (n + n0)(k + 1/2))}, so that the
 * decoder's inverse transform and overlap-add reconstruct the input. An instance holds scratch
 * memory and is not thread-safe.
 */
public final class ForwardMdct {
    /**
     * The number of spectral lines of a long window.
     */
    private static final int LENGTH = 1024;

    /**
     * The number of spectral lines of a short window.
     */
    private static final int SHORT_LENGTH = 128;

    /**
     * The folding kernel.
     */
    private final AacKernel kernel;

    /**
     * The long-window DCT-IV.
     */
    private final Dct4 longDct;

    /**
     * The short-window DCT-IV.
     */
    private final Dct4 shortDct;

    /**
     * The long windows, indexed by shape.
     */
    private final float[][] longWindows;

    /**
     * The short windows, indexed by shape.
     */
    private final float[][] shortWindows;

    /**
     * The composite long-sequence windows, indexed like those of {@link Synthesis}.
     */
    private final float[][] composites = new float[16][];

    /**
     * The composite first short window of an eight-short sequence, indexed by previous shape
     * times two plus shape.
     */
    private final float[][] firstShortWindows = new float[4][];

    /**
     * The folded DCT-IV input.
     */
    private final float[] folded = new float[LENGTH];

    /**
     * Prepares the transform.
     *
     * @param kernel the DSP kernel
     */
    public ForwardMdct(AacKernel kernel) {
        this.kernel = kernel;
        this.longDct = new Dct4(LENGTH, 2 * 32768.0, kernel);
        this.shortDct = new Dct4(SHORT_LENGTH, 2 * 32768.0, kernel);
        this.longWindows = new float[][]{Windows.of(Windows.SINE, 2 * LENGTH), Windows.of(Windows.KBD, 2 * LENGTH)};
        this.shortWindows = new float[][]{Windows.of(Windows.SINE, 2 * SHORT_LENGTH), Windows.of(Windows.KBD, 2 * SHORT_LENGTH)};
    }

    /**
     * Transforms one frame.
     *
     * @param input         the input samples
     * @param offset        the index of the first of the frame's 2048 samples
     * @param sequence      the window sequence
     * @param previousShape the window shape of the previous frame
     * @param shape         the window shape of this frame
     * @param spectrum      receives 1024 spectral values; for eight short windows, window
     *                      after window
     */
    public void transform(float[] input, int offset, int sequence, int previousShape, int shape, float[] spectrum) {
        if (sequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            var start = offset + (LENGTH - SHORT_LENGTH) / 2;
            for (var window = 0; window < 8; window++) {
                var shortWindow = window == 0 ? firstShort(previousShape, shape) : shortWindows[shape];
                kernel.fold(input, start + window * SHORT_LENGTH, shortWindow, folded, SHORT_LENGTH);
                shortDct.transform(folded, 0, spectrum, window * SHORT_LENGTH);
            }
            return;
        }
        kernel.fold(input, offset, composite(sequence, previousShape, shape), folded, LENGTH);
        longDct.transform(folded, 0, spectrum, 0);
    }

    /**
     * Returns the first short window of an eight-short sequence, whose rising half follows the
     * previous frame's shape.
     *
     * @param previousShape the previous shape
     * @param shape         the current shape
     * @return the window
     */
    private float[] firstShort(int previousShape, int shape) {
        var index = previousShape * 2 + shape;
        var window = firstShortWindows[index];
        if (window == null) {
            window = new float[2 * SHORT_LENGTH];
            System.arraycopy(shortWindows[previousShape], 0, window, 0, SHORT_LENGTH);
            System.arraycopy(shortWindows[shape], SHORT_LENGTH, window, SHORT_LENGTH, SHORT_LENGTH);
            firstShortWindows[index] = window;
        }
        return window;
    }

    /**
     * Returns the composite window of a long sequence, building it on first use.
     *
     * @param sequence      the window sequence
     * @param previousShape the shape of the rising half
     * @param shape         the shape of the falling half
     * @return the window, 2048 values
     */
    private float[] composite(int sequence, int previousShape, int shape) {
        var index = sequence * 4 + previousShape * 2 + shape;
        var window = composites[index];
        if (window != null) {
            return window;
        }
        window = new float[2 * LENGTH];
        var flat = (LENGTH - SHORT_LENGTH) / 2;
        switch (sequence) {
            case ChannelStream.LONG_START_SEQUENCE -> {
                System.arraycopy(longWindows[previousShape], 0, window, 0, LENGTH);
                Arrays.fill(window, LENGTH, LENGTH + flat, 1);
                System.arraycopy(shortWindows[shape], SHORT_LENGTH, window, LENGTH + flat, SHORT_LENGTH);
            }
            case ChannelStream.LONG_STOP_SEQUENCE -> {
                System.arraycopy(shortWindows[previousShape], 0, window, flat, SHORT_LENGTH);
                Arrays.fill(window, flat + SHORT_LENGTH, LENGTH, 1);
                System.arraycopy(longWindows[shape], LENGTH, window, LENGTH, LENGTH);
            }
            default -> {
                System.arraycopy(longWindows[previousShape], 0, window, 0, LENGTH);
                System.arraycopy(longWindows[shape], LENGTH, window, LENGTH, LENGTH);
            }
        }
        composites[index] = window;
        return window;
    }
}
