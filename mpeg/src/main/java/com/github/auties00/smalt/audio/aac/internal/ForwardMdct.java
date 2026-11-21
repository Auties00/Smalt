package com.github.auties00.smalt.audio.aac.internal;

import java.util.Arrays;

/**
 * Computes the encoder's forward MDCT of one 1024- or 960-sample frame for any window sequence and
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
    private final int length;

    /**
     * The number of spectral lines of a short window.
     */
    private final int shortLength;

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
    private final float[] folded;

    /**
     * Prepares the transform of 1024-sample frames.
     *
     * @param kernel the DSP kernel
     */
    public ForwardMdct(AacKernel kernel) {
        this(1024, kernel);
    }

    /**
     * Prepares the transform of one frame length.
     *
     * @param length the frame length, 1024 or 960
     * @param kernel the DSP kernel
     */
    public ForwardMdct(int length, AacKernel kernel) {
        this.kernel = kernel;
        this.length = length;
        this.shortLength = length / 8;
        this.folded = new float[length];
        this.longDct = new Dct4(length, 2 * 32768.0, kernel);
        this.shortDct = new Dct4(shortLength, 2 * 32768.0, kernel);
        this.longWindows = new float[][]{Windows.of(Windows.SINE, 2 * length), Windows.of(Windows.KBD, 2 * length)};
        this.shortWindows = new float[][]{Windows.of(Windows.SINE, 2 * shortLength),
                Windows.of(Windows.KBD, 2 * shortLength)};
    }

    /**
     * Transforms one frame.
     *
     * @param input         the input samples
     * @param offset        the index of the first of the frame's {@code 2 * length} samples
     * @param sequence      the window sequence
     * @param previousShape the window shape of the previous frame
     * @param shape         the window shape of this frame
     * @param spectrum      receives {@code length} spectral values; for eight short windows, window
     *                      after window
     */
    public void transform(float[] input, int offset, int sequence, int previousShape, int shape, float[] spectrum) {
        if (sequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            var start = offset + (length - shortLength) / 2;
            for (var window = 0; window < 8; window++) {
                var shortWindow = window == 0 ? firstShort(previousShape, shape) : shortWindows[shape];
                kernel.fold(input, start + window * shortLength, shortWindow, folded, shortLength);
                shortDct.transform(folded, 0, spectrum, window * shortLength);
            }
            return;
        }
        kernel.fold(input, offset, composite(sequence, previousShape, shape), folded, length);
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
            window = new float[2 * shortLength];
            System.arraycopy(shortWindows[previousShape], 0, window, 0, shortLength);
            System.arraycopy(shortWindows[shape], shortLength, window, shortLength, shortLength);
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
     * @return the window, {@code 2 * length} values
     */
    private float[] composite(int sequence, int previousShape, int shape) {
        var index = sequence * 4 + previousShape * 2 + shape;
        var window = composites[index];
        if (window != null) {
            return window;
        }
        window = new float[2 * length];
        var flat = (length - shortLength) / 2;
        switch (sequence) {
            case ChannelStream.LONG_START_SEQUENCE -> {
                System.arraycopy(longWindows[previousShape], 0, window, 0, length);
                Arrays.fill(window, length, length + flat, 1);
                System.arraycopy(shortWindows[shape], shortLength, window, length + flat, shortLength);
            }
            case ChannelStream.LONG_STOP_SEQUENCE -> {
                System.arraycopy(shortWindows[previousShape], 0, window, flat, shortLength);
                Arrays.fill(window, flat + shortLength, length, 1);
                System.arraycopy(longWindows[shape], length, window, length, length);
            }
            default -> {
                System.arraycopy(longWindows[previousShape], 0, window, 0, length);
                System.arraycopy(longWindows[shape], length, window, length, length);
            }
        }
        composites[index] = window;
        return window;
    }
}
