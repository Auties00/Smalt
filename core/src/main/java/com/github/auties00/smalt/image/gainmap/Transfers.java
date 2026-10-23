package com.github.auties00.smalt.image.gainmap;

import com.github.auties00.smalt.image.TransferCharacteristics;

/**
 * Holds the transfer functions of gain map rendering as tables: decoding of signal code values to linear light,
 * and PQ and HLG encoding of linear light.
 *
 * <p>Linear light is relative: 1 is the luminance of the reference white of the options, so that a
 * standard-dynamic-range white decodes to 1 and the PQ and HLG signals decode to their luminance divided by it.
 * HLG decodes to normalized scene light, from 0 to 1, which {@link GainMapRenderer} turns into display light
 * with the luminance of each pixel.
 */
final class Transfers {
    /**
     * The PQ constant {@code m1}.
     */
    private static final double M1 = 2610.0 / 16384.0;

    /**
     * The PQ constant {@code m2}.
     */
    private static final double M2 = 2523.0 / 4096.0 * 128.0;

    /**
     * The PQ constant {@code c1}.
     */
    private static final double C1 = 3424.0 / 4096.0;

    /**
     * The PQ constant {@code c2}.
     */
    private static final double C2 = 2413.0 / 4096.0 * 32.0;

    /**
     * The PQ constant {@code c3}.
     */
    private static final double C3 = 2392.0 / 4096.0 * 32.0;

    /**
     * The HLG constant {@code a}.
     */
    private static final double HLG_A = 0.17883277;

    /**
     * The HLG constant {@code b}.
     */
    private static final double HLG_B = 1 - 4 * HLG_A;

    /**
     * The HLG constant {@code c}.
     */
    private static final double HLG_C = 0.5 - HLG_A * StrictMath.log(4 * HLG_A);

    /**
     * The peak luminance of the PQ signal, in candelas per square meter.
     */
    static final double PQ_PEAK = 10000;

    /**
     * The peak luminance of the HLG reference display, in candelas per square meter.
     */
    static final double HLG_PEAK = 1000;

    /**
     * The HLG system gamma of the 1000 candelas per square meter reference display.
     */
    static final double HLG_GAMMA = 1.2;

    /**
     * The intervals of the encoding tables.
     */
    private static final int STEPS = 16384;

    /**
     * The PQ encoding of normalized display light, sampled at {@code x = t^4} for {@code t} in {@code 0..1}.
     */
    private static final float[] PQ_ENCODE = sample(t -> {
        var y = t * t * t * t;
        var p = StrictMath.pow(y, M1);
        return StrictMath.pow((C1 + C2 * p) / (1 + C3 * p), M2);
    });

    /**
     * The HLG OETF of normalized scene light, sampled at {@code e = t^2} for {@code t} in {@code 0..1}.
     */
    private static final float[] HLG_ENCODE = sample(t -> hlgOetf(t * t));

    /**
     * The HLG inverse system gamma {@code y^(1/1.2)}, sampled at {@code y = t^2}.
     */
    private static final float[] HLG_INVERSE_OOTF = sample(t -> StrictMath.pow(t * t, 1 / HLG_GAMMA));

    /**
     * The HLG system gamma {@code y^1.2}, sampled at {@code y = t^2}.
     */
    private static final float[] HLG_OOTF = sample(t -> StrictMath.pow(t * t, HLG_GAMMA));

    private Transfers() {
        throw new AssertionError();
    }

    /**
     * Returns whether a transfer function decodes analytically.
     *
     * @param transfer the transfer characteristics
     * @return {@code true} for sRGB, the BT.709 family, gamma 2.2 and 2.8, linear, PQ, HLG and unspecified
     */
    static boolean isAnalytic(TransferCharacteristics transfer) {
        return switch (transfer) {
            case SRGB, UNSPECIFIED, BT709, SMPTE170M, BT2020_10, BT2020_12, GAMMA22, GAMMA28, LINEAR, PQ, HLG -> true;
            default -> false;
        };
    }

    /**
     * Builds the table that decodes code values of a depth to relative linear light.
     *
     * @param transfer the transfer characteristics, one {@link #isAnalytic(TransferCharacteristics)} accepts;
     * unspecified is sRGB
     * @param bits the depth of the code values, 8 or 16
     * @param referenceWhite the luminance of linear 1, in candelas per square meter, used by PQ only
     * @return the table, one entry per code value
     */
    static float[] decode(TransferCharacteristics transfer, int bits, double referenceWhite) {
        var size = 1 << bits;
        var table = new float[size];
        var max = size - 1.0;
        for (var code = 0; code < size; code++) {
            table[code] = (float) decode(transfer, code / max, referenceWhite);
        }
        return table;
    }

    /**
     * Decodes one signal value to relative linear light.
     *
     * @param transfer the transfer characteristics
     * @param signal the signal, from 0 to 1
     * @param referenceWhite the luminance of linear 1, in candelas per square meter
     * @return the linear value; for HLG, the normalized scene light
     */
    static double decode(TransferCharacteristics transfer, double signal, double referenceWhite) {
        return switch (transfer) {
            case SRGB, UNSPECIFIED -> signal <= 0.04045 ? signal / 12.92 : StrictMath.pow((signal + 0.055) / 1.055, 2.4);
            case BT709, SMPTE170M, BT2020_10, BT2020_12 -> signal < 4.5 * 0.018053968510807
                    ? signal / 4.5 : StrictMath.pow((signal + 0.09929682680944) / 1.09929682680944, 1 / 0.45);
            case GAMMA22 -> StrictMath.pow(signal, 2.2);
            case GAMMA28 -> StrictMath.pow(signal, 2.8);
            case LINEAR -> signal;
            case PQ -> {
                var p = StrictMath.pow(signal, 1 / M2);
                yield StrictMath.pow(Math.max(p - C1, 0) / (C2 - C3 * p), 1 / M1) * PQ_PEAK / referenceWhite;
            }
            case HLG -> signal <= 0.5 ? signal * signal / 3 : (StrictMath.exp((signal - HLG_C) / HLG_A) + HLG_B) / 12;
            default -> throw new IllegalArgumentException("no analytic decoding of " + transfer);
        };
    }

    /**
     * Encodes normalized display light with the PQ inverse EOTF.
     *
     * @param light the display light divided by 10000 candelas per square meter, from 0 to 1
     * @return the signal, from 0 to 1
     */
    static float pq(float light) {
        return lookup(PQ_ENCODE, (float) Math.sqrt(Math.sqrt(light)));
    }

    /**
     * Encodes normalized scene light with the HLG OETF.
     *
     * @param scene the scene light, from 0 to 1
     * @return the signal, from 0 to 1
     */
    static float hlg(float scene) {
        return lookup(HLG_ENCODE, (float) Math.sqrt(scene));
    }

    /**
     * Raises a luminance to {@code 1/1.2}, the exponent of the HLG inverse system gamma.
     *
     * @param luminance the luminance, from 0 to 1
     * @return the result
     */
    static float hlgInverseGamma(float luminance) {
        return lookup(HLG_INVERSE_OOTF, (float) Math.sqrt(luminance));
    }

    /**
     * Raises a luminance to {@code 1.2}, the exponent of the HLG system gamma.
     *
     * @param luminance the luminance, from 0 to 1
     * @return the result
     */
    static float hlgGamma(float luminance) {
        return lookup(HLG_OOTF, (float) Math.sqrt(luminance));
    }

    /**
     * Applies the HLG OETF exactly.
     *
     * @param scene the normalized scene light
     * @return the signal
     */
    static double hlgOetf(double scene) {
        return scene <= 1.0 / 12 ? Math.sqrt(3 * scene) : HLG_A * StrictMath.log(12 * scene - HLG_B) + HLG_C;
    }

    /**
     * Applies the PQ inverse EOTF exactly.
     *
     * @param light the display light divided by 10000 candelas per square meter
     * @return the signal
     */
    static double pqExact(double light) {
        var p = StrictMath.pow(light, M1);
        return StrictMath.pow((C1 + C2 * p) / (1 + C3 * p), M2);
    }

    /**
     * Interpolates a table sampled over {@code 0..1}.
     *
     * @param table the {@link #STEPS} plus one samples
     * @param t the position, clamped to {@code 0..1}
     * @return the interpolated value
     */
    private static float lookup(float[] table, float t) {
        if (!(t > 0)) {
            return table[0];
        }
        if (t >= 1) {
            return table[STEPS];
        }
        var scaled = t * STEPS;
        var index = (int) scaled;
        var fraction = scaled - index;
        return table[index] + (table[index + 1] - table[index]) * fraction;
    }

    /**
     * Samples a function over {@code 0..1}.
     *
     * @param function the function to sample
     * @return the {@link #STEPS} plus one samples
     */
    private static float[] sample(java.util.function.DoubleUnaryOperator function) {
        var table = new float[STEPS + 1];
        for (var i = 0; i <= STEPS; i++) {
            table[i] = (float) function.applyAsDouble((double) i / STEPS);
        }
        return table;
    }
}
