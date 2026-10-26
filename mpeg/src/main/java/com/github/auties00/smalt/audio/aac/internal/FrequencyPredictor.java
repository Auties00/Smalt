package com.github.auties00.smalt.audio.aac.internal;

import java.util.Arrays;

/**
 * Runs the backward-adaptive frequency-domain predictor bank of one AAC Main channel (ISO/IEC
 * 13818-7 clause 13, referenced by ISO/IEC 14496-3 subclause 4.6.6).
 *
 * <p>Every spectral line of the long-window bands below {@code PRED_SFB_MAX} has a second-order
 * lattice predictor fed with the reconstructed values of the two preceding frames. In a long-window
 * frame every predictor runs; where {@code prediction_used} is set its estimate is added to the
 * decoded value. An eight-short-window frame resets every predictor, and the frame's predictor
 * reset group, if any, is reset after the update. Noise-substituted and intensity-coded bands never
 * use their estimate; their predictors are fed zero, and those of noise-substituted bands are
 * reset (ISO/IEC 14496-3 subclause 4.6.13.4). The bank runs on the spectrum after M/S decoding and
 * before intensity stereo decoding, the order of the decoder tools in ISO/IEC 13818-7 clause 4.
 *
 * <p>The state variables are stored truncated to their 16 most significant bits, the estimate is
 * rounded to 16 bits half away from zero ({@code flt_round_inf}), and {@code b / VAR} is taken from
 * the standard's mantissa and exponent tables ({@code make_inv_tables}), which make the coefficient
 * zero while the variance is below 2.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation evaluates the intermediate results in double precision, where ISO/IEC
 * 13818-7 subclause 13.3.2.2 specifies single precision, and feeds the predictors of intensity-coded
 * bands the value before intensity decoding, where ISO/IEC 14496-3 subclause 4.6.8.2.4 words the
 * decoded value: only this reading reproduces the ISO/IEC 14496-26 reference waveforms of the AAC
 * Main streams with intensity stereo (am02) within the conformance tolerance.
 */
public final class FrequencyPredictor {
    /**
     * The attenuation factors {@code a} and {@code b}.
     */
    private static final float ATTENUATION = 0.953125f;

    /**
     * The adaptation time constant {@code alpha}.
     */
    private static final float ALPHA = 0.90625f;

    /**
     * The number of predictor reset groups.
     */
    private static final int RESET_GROUPS = 30;

    /**
     * {@code b / m} for every 16-bit mantissa {@code m = 1 + i / 128}, rounded to 16 bits
     * ({@code mnt_table}).
     */
    private static final float[] MANTISSA_INVERSES = mantissaInverses();

    /**
     * {@code 1 / 2^(e - 127)} for every biased exponent {@code e} whose power exceeds 1, else 0
     * ({@code exp_table}).
     */
    private static final float[] EXPONENT_INVERSES = exponentInverses();

    /**
     * The long-window band offsets.
     */
    private final int[] offsets;

    /**
     * The number of predicted bands.
     */
    private final int bands;

    /**
     * The number of predicted lines.
     */
    private final int lines;

    /**
     * The delayed reconstructed values {@code r0} of every predictor.
     */
    private final float[] r0;

    /**
     * The delayed lattice residuals {@code r1} of every predictor.
     */
    private final float[] r1;

    /**
     * The correlation estimates of the first lattice element.
     */
    private final float[] cor0;

    /**
     * The correlation estimates of the second lattice element.
     */
    private final float[] cor1;

    /**
     * The variance estimates of the first lattice element.
     */
    private final float[] var0;

    /**
     * The variance estimates of the second lattice element.
     */
    private final float[] var1;

    /**
     * Creates a predictor bank with every predictor initialized.
     *
     * @param frameLength the number of spectral lines per frame, 1024 or 960
     * @param tableIndex the sampling table index
     */
    public FrequencyPredictor(int frameLength, int tableIndex) {
        this.offsets = SamplingTables.longOffsets(tableIndex, frameLength);
        this.bands = Math.min(SamplingTables.predictionMaxBands(tableIndex), offsets.length - 1);
        this.lines = offsets[bands];
        this.r0 = new float[lines];
        this.r1 = new float[lines];
        this.cor0 = new float[lines];
        this.cor1 = new float[lines];
        this.var0 = new float[lines];
        this.var1 = new float[lines];
        reset();
    }

    /**
     * Initializes every predictor.
     */
    public void reset() {
        Arrays.fill(r0, 0);
        Arrays.fill(r1, 0);
        Arrays.fill(cor0, 0);
        Arrays.fill(cor1, 0);
        Arrays.fill(var0, 1);
        Arrays.fill(var1, 1);
    }

    /**
     * Predicts the spectrum of one frame in place and updates the predictors.
     *
     * <p>The spectrum must hold the decoded values after M/S decoding and noise substitution, before
     * intensity stereo decoding; it is left untouched in eight-short-window frames.
     *
     * @param stream the channel stream of the frame
     */
    public void apply(ChannelStream stream) {
        if (stream.windowSequence() == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            reset();
            return;
        }
        var spectrum = stream.spectrum();
        var enabled = stream.predictorPresent();
        var coded = stream.maxBands();
        for (var band = 0; band < bands; band++) {
            var type = band < coded ? stream.bandType(band) : Codebooks.ZERO_HCB;
            var noise = type == Codebooks.NOISE_HCB;
            var intensity = type == Codebooks.INTENSITY_HCB || type == Codebooks.INTENSITY_HCB2;
            var used = enabled && stream.predictionUsed(band) && !noise && !intensity;
            for (var k = offsets[band]; k < offsets[band + 1]; k++) {
                predict(spectrum, k, used, noise || intensity);
            }
        }
        var group = stream.predictorResetGroup();
        if (enabled && group != 0) {
            for (var k = group - 1; k < lines; k += RESET_GROUPS) {
                resetLine(k);
            }
        }
        for (var band = 0; band < Math.min(bands, coded); band++) {
            if (stream.bandType(band) == Codebooks.NOISE_HCB) {
                for (var k = offsets[band]; k < offsets[band + 1]; k++) {
                    resetLine(k);
                }
            }
        }
    }

    /**
     * Runs the predictor of one spectral line.
     *
     * @param spectrum the spectrum, in place
     * @param k the line
     * @param used whether the estimate is added to the decoded value
     * @param silent whether the predictor is fed zero instead of the line's value
     */
    private void predict(float[] spectrum, int k, boolean used, boolean silent) {
        double past0 = r0[k];
        double past1 = r1[k];
        double c0 = cor0[k];
        double c1 = cor1[k];
        double v0 = var0[k];
        double v1 = var1[k];
        double k1 = c0 * inverse(var0[k]);
        double k2 = c1 * inverse(var1[k]);
        double estimate0 = k1 * past0;
        double estimate = roundInfinity((float) (estimate0 + k2 * past1));
        double e0 = silent ? 0 : spectrum[k];
        if (used) {
            e0 = estimate + e0;
            spectrum[k] = (float) e0;
        }
        double e1 = e0 - estimate0;
        cor1[k] = truncate((float) (ALPHA * c1 + past1 * e1));
        var1[k] = truncate((float) (ALPHA * v1 + 0.5 * (past1 * past1 + e1 * e1)));
        cor0[k] = truncate((float) (ALPHA * c0 + past0 * e0));
        var0[k] = truncate((float) (ALPHA * v0 + 0.5 * (past0 * past0 + e0 * e0)));
        r1[k] = truncate((float) (ATTENUATION * (past0 - k1 * e0)));
        r0[k] = truncate((float) (ATTENUATION * e0));
    }

    /**
     * Initializes the predictor of one line.
     *
     * @param k the line
     */
    private void resetLine(int k) {
        r0[k] = 0;
        r1[k] = 0;
        cor0[k] = 0;
        cor1[k] = 0;
        var0[k] = 1;
        var1[k] = 1;
    }

    /**
     * Returns {@code b / variance} rounded to 16 bits through the mantissa and exponent tables.
     *
     * @param variance a stored (truncated) variance
     * @return the rounded ratio, 0 while the variance is below 2
     */
    static float inverse(float variance) {
        var bits = Float.floatToRawIntBits(variance);
        return MANTISSA_INVERSES[bits >>> 16 & 0x7F] * EXPONENT_INVERSES[bits >>> 23 & 0xFF];
    }

    /**
     * Keeps the 16 most significant bits of a float.
     *
     * @param value the number to quantize
     * @return the truncated value
     */
    static float truncate(float value) {
        return Float.intBitsToFloat(Float.floatToRawIntBits(value) & 0xFFFF0000);
    }

    /**
     * Rounds a float to its 16 most significant bits, half away from zero ({@code flt_round_inf}).
     *
     * @param value the number to quantize
     * @return the rounded value
     */
    static float roundInfinity(float value) {
        var bits = Float.floatToRawIntBits(value);
        var truncated = Float.intBitsToFloat(bits & 0xFFFF0000);
        if ((bits & 0x8000) == 0) {
            return truncated;
        }
        var signExponent = bits & 0xFF800000;
        return truncated + Float.intBitsToFloat(signExponent | 0x00010000) - Float.intBitsToFloat(signExponent);
    }

    /**
     * Rounds a value between 0.25 and 2 to eight significant bits, halves upward, as
     * {@code flt_round_even} does.
     *
     * @param value the quotient to round
     * @return the rounded value
     */
    private static float roundEven(float value) {
        var exponent = Math.getExponent(value) + 1;
        var scale = (float) Math.scalb(1.0, 8 - exponent);
        var scaled = value * scale;
        var integer = (int) scaled;
        if (scaled - integer >= 0.5f) {
            integer++;
        }
        if (scaled - integer == 0.5f) {
            integer &= -2;
        }
        return integer / scale;
    }

    /**
     * Builds {@code mnt_table}.
     *
     * @return the 128 rounded mantissa inverses
     */
    private static float[] mantissaInverses() {
        var table = new float[128];
        for (var i = 0; i < table.length; i++) {
            var mantissa = Float.intBitsToFloat(Float.floatToRawIntBits(1.0f) + (i << 16));
            table[i] = roundEven(ATTENUATION / mantissa);
        }
        return table;
    }

    /**
     * Builds {@code exp_table}.
     *
     * @return the 256 exponent inverses
     */
    private static float[] exponentInverses() {
        var table = new float[256];
        for (var i = 0; i < table.length; i++) {
            var power = Float.intBitsToFloat(i << 23);
            table[i] = power > 1.0f ? 1.0f / power : 0;
        }
        return table;
    }
}
