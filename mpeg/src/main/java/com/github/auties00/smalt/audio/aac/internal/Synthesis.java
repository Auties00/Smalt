package com.github.auties00.smalt.audio.aac.internal;

import java.util.Arrays;

/**
 * Runs the AAC synthesis filterbank: the inverse MDCT of every window, windowing with the
 * current and previous window shapes, and overlap-add (ISO/IEC 14496-3 subclause 4.6.11).
 *
 * <p>Output samples are scaled so that a full-scale 16-bit decoder output maps to
 * {@code [-1, 1]}. An instance holds scratch memory and is not thread-safe; the overlap memory
 * of each channel is owned by the caller.
 */
public final class Synthesis {
    /**
     * The inverse of the 16-bit full scale.
     */
    private static final double OUTPUT_SCALE = 1.0 / 32768.0;

    /**
     * The number of spectral lines of a long window.
     */
    private final int length;

    /**
     * The number of spectral lines of a short window.
     */
    private final int shortLength;

    /**
     * The window kernel.
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
     * The composite long-sequence windows, indexed {@code sequence * 4 + previous * 2 + current}
     * and built on first use.
     */
    private final float[][] composites = new float[16][];

    /**
     * The DCT-IV output.
     */
    private final float[] dctOutput;

    /**
     * The windowed output of an eight-short-window frame.
     */
    private final float[] shortBuffer;

    /**
     * Prepares the filterbank of a frame length.
     *
     * @param length the frame length, 1024 or 960
     * @param kernel the window kernel
     */
    public Synthesis(int length, AacKernel kernel) {
        this.length = length;
        this.shortLength = length / 8;
        this.kernel = kernel;
        this.longDct = new Dct4(length, OUTPUT_SCALE / length, kernel);
        this.shortDct = new Dct4(shortLength, OUTPUT_SCALE / shortLength, kernel);
        this.longWindows = new float[][]{Windows.of(Windows.SINE, 2 * length), Windows.of(Windows.KBD, 2 * length)};
        this.shortWindows = new float[][]{Windows.of(Windows.SINE, 2 * shortLength), Windows.of(Windows.KBD, 2 * shortLength)};
        this.dctOutput = new float[length];
        this.shortBuffer = new float[2 * length];
    }

    /**
     * Synthesizes one frame of one channel.
     *
     * @param spectrum      the spectral values
     * @param sequence      the window sequence
     * @param previousShape the window shape of the previous frame
     * @param shape         the window shape of this frame
     * @param overlap       the overlap memory of the channel, {@code length} samples, in place
     * @param out           the output samples
     * @param outOffset     the index of the first output sample
     */
    public void synthesize(float[] spectrum, int sequence, int previousShape, int shape, float[] overlap,
                           float[] out, int outOffset) {
        if (sequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            synthesizeShort(spectrum, previousShape, shape, overlap, out, outOffset);
            return;
        }
        longDct.transform(spectrum, 0, dctOutput, 0);
        kernel.overlapLong(dctOutput, length, composite(sequence, previousShape, shape), overlap, out, outOffset);
    }

    /**
     * Synthesizes an eight-short-window frame.
     *
     * @param spectrum      the spectral values, window after window
     * @param previousShape the window shape of the previous frame
     * @param shape         the window shape of this frame
     * @param overlap       the overlap memory, in place
     * @param out           the output samples
     * @param outOffset     the index of the first output sample
     */
    private void synthesizeShort(float[] spectrum, int previousShape, int shape, float[] overlap,
                                 float[] out, int outOffset) {
        var buffer = shortBuffer;
        Arrays.fill(buffer, 0);
        var m = shortLength;
        var h = m >> 1;
        var start = (length - m) / 2;
        var u = dctOutput;
        var falling = shortWindows[shape];
        for (var window = 0; window < 8; window++) {
            shortDct.transform(spectrum, window * m, u, 0);
            var rising = shortWindows[window == 0 ? previousShape : shape];
            var base = start + window * m;
            for (var n = 0; n < h; n++) {
                buffer[base + n] += rising[n] * u[h + n];
            }
            for (var n = h; n < m; n++) {
                buffer[base + n] -= rising[n] * u[m + h - 1 - n];
            }
            for (var n = 0; n < h; n++) {
                buffer[base + m + n] -= falling[m + n] * u[h - 1 - n];
            }
            for (var n = h; n < m; n++) {
                buffer[base + m + n] -= falling[m + n] * u[n - h];
            }
        }
        for (var n = 0; n < length; n++) {
            out[outOffset + n] = overlap[n] + buffer[n];
        }
        System.arraycopy(buffer, length, overlap, 0, length);
    }

    /**
     * Returns the composite window of a long window sequence, building it on first use.
     *
     * @param sequence      the window sequence: only long, long start or long stop
     * @param previousShape the shape of the rising half
     * @param shape         the shape of the falling half
     * @return the window, {@code 2 * length} samples
     */
    private float[] composite(int sequence, int previousShape, int shape) {
        var index = sequence * 4 + previousShape * 2 + shape;
        var window = composites[index];
        if (window != null) {
            return window;
        }
        window = new float[2 * length];
        var rising = longWindows[previousShape];
        var falling = longWindows[shape];
        var m = shortLength;
        var flat = (length - m) / 2;
        switch (sequence) {
            case ChannelStream.LONG_START_SEQUENCE -> {
                System.arraycopy(rising, 0, window, 0, length);
                Arrays.fill(window, length, length + flat, 1);
                System.arraycopy(shortWindows[shape], m, window, length + flat, m);
            }
            case ChannelStream.LONG_STOP_SEQUENCE -> {
                System.arraycopy(shortWindows[previousShape], 0, window, flat, m);
                Arrays.fill(window, flat + m, length, 1);
                System.arraycopy(falling, length, window, length, length);
            }
            default -> {
                System.arraycopy(rising, 0, window, 0, length);
                System.arraycopy(falling, length, window, length, length);
            }
        }
        composites[index] = window;
        return window;
    }
}
