package com.github.auties00.smalt.audio.aac.internal;

/**
 * Holds the constants and gain tables of the AAC encoder's quantizer.
 *
 * <p>A spectral magnitude {@code x} is quantized at scalefactor {@code sf} to
 * {@code q = (int) ((x * 2^(-(sf - 100) / 4))^(3/4) + ROUNDING)} and reconstructed by the
 * decoder as {@code q^(4/3) * 2^((sf - 100) / 4)}.
 */
public final class Quantization {
    /**
     * The rounding offset of the power-law quantizer.
     *
     * @implNote This implementation uses 0.4054, the offset of the informative encoder of
     * ISO/IEC 14496-3, which centers the quantization intervals of the 3/4-power domain on the
     * reconstruction levels of the 4/3-power domain.
     */
    public static final float ROUNDING = 0.4054f;

    /**
     * The largest quantized magnitude the bitstream can carry.
     */
    public static final int MAX_QUANT = 8191;

    /**
     * The value {@code 2^(-3/16 (sf - 100))} of every scalefactor from 0 to 255.
     */
    private static final float[] INVERSE_STEPS = buildInverseSteps();

    /**
     * Prevents instantiation.
     */
    private Quantization() {
        throw new AssertionError();
    }

    /**
     * Returns the quantizer gain applied to 3/4-powered magnitudes.
     *
     * @param scalefactor the scalefactor, from 0 to 255
     * @return {@code 2^(-3/16 (sf - 100))}
     */
    public static float inverseStep(int scalefactor) {
        return INVERSE_STEPS[scalefactor];
    }

    /**
     * Returns the reconstruction gain of a scalefactor.
     *
     * @param scalefactor the scalefactor, from 0 to 255
     * @return {@code 2^((sf - 100) / 4)}
     */
    public static float step(int scalefactor) {
        return Dequantizer.scalefactorGain(scalefactor);
    }

    /**
     * Returns the smallest scalefactor that keeps every quantized magnitude of a band within
     * {@link #MAX_QUANT}.
     *
     * @param maxMagnitude the largest spectral magnitude of the band
     * @return the scalefactor bound, from 0 to 255
     */
    public static int minimumScalefactor(float maxMagnitude) {
        if (maxMagnitude <= 0) {
            return 0;
        }
        var estimate = (int) Math.floor(4 * log2(maxMagnitude) - 16.0 / 3.0 * log2(MAX_QUANT + 1 - ROUNDING)) + 100 - 2;
        var bound = Math.clamp(estimate, 0, 255);
        var power34 = (float) Math.sqrt(maxMagnitude * (float) Math.sqrt(maxMagnitude));
        while (bound < 255 && (int) (power34 * INVERSE_STEPS[bound] + ROUNDING) > MAX_QUANT) {
            bound++;
        }
        return bound;
    }

    /**
     * Returns {@code log2(x)}.
     *
     * @param x a positive value
     * @return the binary logarithm
     */
    public static double log2(double x) {
        return StrictMath.log(x) * (1 / StrictMath.log(2));
    }

    /**
     * Returns {@code 2^(-3/16 (sf - 100))} for any scalefactor.
     *
     * @param scalefactor the scalefactor {@code sf}, any integer
     * @return the quantizer gain
     */
    private static float inverseStepUnchecked(int scalefactor) {
        return (float) StrictMath.pow(2, -3.0 / 16.0 * (scalefactor - 100));
    }

    /**
     * Computes the quantizer gain table.
     *
     * @return the table
     */
    private static float[] buildInverseSteps() {
        var table = new float[256];
        for (var i = 0; i < table.length; i++) {
            table[i] = inverseStepUnchecked(i);
        }
        return table;
    }
}
