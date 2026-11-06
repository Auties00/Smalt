package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Arrays;
import java.util.Objects;

/**
 * Holds the settings of a proofing {@link ColorTransform}, one created by
 * {@link ColorTransform#createProofing(ColorProfile, ColorProfile, ColorProfile, ColorTransformOptions,
 * ProofingOptions)}. Instances are immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
 */
public final class ProofingOptions {
    /**
     * The largest number of alarm values, one per target channel.
     */
    public static final int MAX_ALARM_CHANNELS = 16;

    private static final int[] DEFAULT_ALARM = {0x7F00, 0x7F00, 0x7F00};

    private static final ProofingOptions DEFAULTS = new ProofingOptions(RenderingIntent.PERCEPTUAL, false,
            DEFAULT_ALARM);

    private final RenderingIntent intent;
    private final boolean gamutCheck;
    private final int[] alarm;

    private ProofingOptions(RenderingIntent intent, boolean gamutCheck, int[] alarm) {
        this.intent = intent;
        this.gamutCheck = gamutCheck;
        this.alarm = alarm;
    }

    /**
     * Returns the perceptual proofing intent without a gamut check, with a mid-gray alarm.
     *
     * @return the default options
     */
    public static ProofingOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options with the gamut check enabled.
     *
     * @return the options
     */
    public static ProofingOptions forGamutCheck() {
        return new ProofingOptions(RenderingIntent.PERCEPTUAL, true, DEFAULT_ALARM);
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(DEFAULTS);
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the rendering intent from the proofing device to the target; {@link RenderingIntent#PERCEPTUAL} by
     * default}
     *
     * <p>The source is converted to the proofing device with the intent of the transform's
     * {@link ColorTransformOptions}, and the proofing device's colors are shown on the target with this one, without
     * black point compensation: {@link RenderingIntent#ABSOLUTE_COLORIMETRIC} also simulates the proofing medium's
     * white, {@link RenderingIntent#RELATIVE_COLORIMETRIC} maps it to the target white.
     */
    public RenderingIntent intent() {
        return intent;
    }

    /**
     * {@return whether colors the proofing device cannot reproduce are marked with the {@linkplain #alarm() alarm};
     * {@code false} by default}
     *
     * <p>A source color is out of gamut when converting it to the proofing device colorimetrically and back moves it
     * by more than 1 delta E (CIE 1976) for a matrix-shaper proofing profile, 5 for others. The check is computed
     * once per transform into a lookup table over the source values, which makes the transform more expensive to
     * create.
     */
    public boolean gamutCheck() {
        return gamutCheck;
    }

    /**
     * {@return the values written to the target channels of a color out of gamut, one per channel, the channels past
     * the end of the list receiving zero; mid-gray, {@code 0x7F00} on the first three channels, by default}
     *
     * <p>Each value is a 16-bit sample, {@code 0} to {@code 65535}: 16-bit transforms write it as it is, 8-bit ones
     * reduce it to 8 bits with rounding, and floating-point ones write the target value whose 16-bit encoding it is
     * (a device value divided by 65535; L*a*b* and XYZ in the version 4 encoding of 16-bit transforms). The returned
     * array is a copy.
     */
    public int[] alarm() {
        return alarm.clone();
    }

    /**
     * Returns the alarm values of a target, the channels past the end of the list zero.
     *
     * @param channels the number of target channels
     * @return the 16-bit values
     */
    int[] alarm(int channels) {
        return Arrays.copyOf(alarm, Math.max(channels, alarm.length));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProofingOptions that && intent == that.intent && gamutCheck == that.gamutCheck
                && Arrays.equals(alarm, that.alarm);
    }

    @Override
    public int hashCode() {
        return Objects.hash(intent, gamutCheck, Arrays.hashCode(alarm));
    }

    @Override
    public String toString() {
        return "ProofingOptions[intent=" + intent + ", gamutCheck=" + gamutCheck + ", alarm="
                + Arrays.toString(alarm) + "]";
    }

    /**
     * Builds {@link ProofingOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private RenderingIntent intent;
        private boolean gamutCheck;
        private int[] alarm;

        private Builder(ProofingOptions options) {
            this.intent = options.intent;
            this.gamutCheck = options.gamutCheck;
            this.alarm = options.alarm;
        }

        /**
         * Sets the rendering intent from the proofing device to the target; see {@link ProofingOptions#intent()}.
         *
         * @param intent the value
         * @return this builder
         */
        public Builder intent(RenderingIntent intent) {
            this.intent = Objects.requireNonNull(intent, "intent");
            return this;
        }

        /**
         * Sets whether colors out of the proofing gamut are marked; see {@link ProofingOptions#gamutCheck()}.
         *
         * @param gamutCheck the value
         * @return this builder
         */
        public Builder gamutCheck(boolean gamutCheck) {
            this.gamutCheck = gamutCheck;
            return this;
        }

        /**
         * Sets the alarm values; see {@link ProofingOptions#alarm()}.
         *
         * @param alarm the value, copied
         * @return this builder
         */
        public Builder alarm(int... alarm) {
            this.alarm = alarm.clone();
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the alarm holds no value, more than
         * {@value ProofingOptions#MAX_ALARM_CHANNELS}, or a value outside {@code 0..65535}
         */
        public ProofingOptions build() {
            Checks.inRange("alarm channels", alarm.length, 1, MAX_ALARM_CHANNELS);
            for (var value : alarm) {
                Checks.inRange("alarm value", value, 0, 0xFFFF);
            }
            return new ProofingOptions(intent, gamutCheck, alarm.clone());
        }
    }
}
