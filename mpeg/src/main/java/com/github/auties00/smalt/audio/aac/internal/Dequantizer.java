package com.github.auties00.smalt.audio.aac.internal;

/**
 * Holds the inverse quantization tables of AAC: the {@code x^(4/3)} power law and the
 * scalefactor gains.
 */
public final class Dequantizer {
    /**
     * The number of entries of {@link #POW43}: every magnitude an escape sequence (at most 8191)
     * can reach after the largest pulse amplitude (15) is added.
     */
    public static final int POW43_SIZE = 8192 + 16;

    /**
     * The value {@code i^(4/3)} of every quantized magnitude {@code i}.
     */
    public static final float[] POW43 = buildPow43();

    /**
     * The offset of scalefactor gains: a scalefactor of 100 scales by one.
     */
    public static final int SCALEFACTOR_OFFSET = 100;

    /**
     * The lowest exponent {@link #gain(int)} accepts.
     */
    public static final int MIN_EXPONENT = -512;

    /**
     * The highest exponent {@link #gain(int)} accepts.
     */
    public static final int MAX_EXPONENT = 511;

    /**
     * The value {@code 2^(e / 4)} of every exponent {@code e} from {@link #MIN_EXPONENT} to
     * {@link #MAX_EXPONENT}, indexed by {@code e - MIN_EXPONENT}.
     */
    private static final float[] GAINS = buildGains();

    /**
     * Prevents instantiation.
     */
    private Dequantizer() {
        throw new AssertionError();
    }

    /**
     * Returns {@code 2^(exponent / 4)}.
     *
     * @param exponent the exponent, from {@link #MIN_EXPONENT} to {@link #MAX_EXPONENT}
     * @return the gain
     */
    public static float gain(int exponent) {
        return GAINS[exponent - MIN_EXPONENT];
    }

    /**
     * Returns the inverse quantization gain of a scalefactor, {@code 2^((sf - 100) / 4)}.
     *
     * @param scalefactor the scalefactor, from 0 to 255
     * @return the gain
     */
    public static float scalefactorGain(int scalefactor) {
        return GAINS[scalefactor - SCALEFACTOR_OFFSET - MIN_EXPONENT];
    }

    /**
     * Computes the power-law table.
     *
     * @return the table
     */
    private static float[] buildPow43() {
        var table = new float[POW43_SIZE];
        for (var i = 0; i < table.length; i++) {
            table[i] = (float) Math.pow(i, 4.0 / 3.0);
        }
        return table;
    }

    /**
     * Computes the gain table.
     *
     * @return the table
     */
    private static float[] buildGains() {
        var table = new float[MAX_EXPONENT - MIN_EXPONENT + 1];
        for (var i = 0; i < table.length; i++) {
            table[i] = (float) Math.pow(2.0, (i + MIN_EXPONENT) / 4.0);
        }
        return table;
    }
}
