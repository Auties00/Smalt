package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of a {@link ColorTransform}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class ColorTransformOptions {
    /**
     * The reference white of ITU-R BT.2408, in candelas per square meter.
     */
    private static final double BT2408_WHITE = Hdr.REFERENCE_WHITE;

    private static final ColorTransformOptions DEFAULTS = new ColorTransformOptions(RenderingIntent.PERCEPTUAL, false,
            ToneMapping.BT2390, BT2408_WHITE);

    private final RenderingIntent intent;
    private final boolean blackPointCompensation;
    private final ToneMapping toneMapping;
    private final double referenceWhite;

    private ColorTransformOptions(RenderingIntent intent, boolean blackPointCompensation, ToneMapping toneMapping,
                                  double referenceWhite) {
        this.intent = intent;
        this.blackPointCompensation = blackPointCompensation;
        this.toneMapping = toneMapping;
        this.referenceWhite = referenceWhite;
    }

    /**
     * Returns the perceptual intent without black point compensation (the defaults of Little CMS's
     * {@code transicc} and {@code jpgicc}), with BT.2390 tone mapping and the BT.2408 reference
     * white.
     *
     * @return the default options
     */
    public static ColorTransformOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options with the relative colorimetric intent and black point
     * compensation, the usual choice for converting photographs between RGB spaces and for
     * printing.
     *
     * @return the options
     */
    public static ColorTransformOptions relativeColorimetric() {
        return new ColorTransformOptions(RenderingIntent.RELATIVE_COLORIMETRIC, true, ToneMapping.BT2390, BT2408_WHITE);
    }

    /**
     * Returns the default options with the absolute colorimetric intent, for proofing.
     *
     * @return the options
     */
    public static ColorTransformOptions absoluteColorimetric() {
        return new ColorTransformOptions(RenderingIntent.ABSOLUTE_COLORIMETRIC, false, ToneMapping.BT2390, BT2408_WHITE);
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
     * {@return the rendering intent; {@link RenderingIntent#PERCEPTUAL} by default}
     */
    public RenderingIntent intent() {
        return intent;
    }

    /**
     * {@return whether black point compensation is requested; {@code false} by default}
     *
     * <p>Black point compensation scales colors so that the darkest color of the source maps to
     * the darkest color of the target, keeping shadow detail when the two differ. It never
     * applies with {@link RenderingIntent#ABSOLUTE_COLORIMETRIC}, and always applies with the
     * perceptual and saturation intents when the target is a version 4 profile, as the ICC
     * specification requires.
     */
    public boolean blackPointCompensation() {
        return blackPointCompensation;
    }

    /**
     * {@return the tone mapping of high-dynamic-range sources, and of sources whose peak exceeds the
     * limit of a high-dynamic-range target; {@link ToneMapping#BT2390} by default}
     */
    public ToneMapping toneMapping() {
        return toneMapping;
    }

    /**
     * {@return the luminance that corresponds to a standard-dynamic-range white, in candelas per
     * square meter, from 1 to 10000; 203 (ITU-R BT.2408) by default}
     *
     * <p>A high-dynamic-range source luminance equal to it maps to the white of a
     * standard-dynamic-range target, and the white of a standard-dynamic-range source is placed at
     * it in a high-dynamic-range target. It does not apply between two high-dynamic-range profiles.
     */
    public double referenceWhite() {
        return referenceWhite;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ColorTransformOptions that
                && intent == that.intent
                && blackPointCompensation == that.blackPointCompensation
                && toneMapping == that.toneMapping
                && Double.compare(referenceWhite, that.referenceWhite) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(intent, blackPointCompensation, toneMapping, referenceWhite);
    }

    @Override
    public String toString() {
        return "ColorTransformOptions[intent=" + intent + ", blackPointCompensation=" + blackPointCompensation
                + ", toneMapping=" + toneMapping + ", referenceWhite=" + referenceWhite + "]";
    }

    /**
     * Builds {@link ColorTransformOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private RenderingIntent intent;
        private boolean blackPointCompensation;
        private ToneMapping toneMapping;
        private double referenceWhite;

        private Builder(ColorTransformOptions options) {
            this.intent = options.intent;
            this.blackPointCompensation = options.blackPointCompensation;
            this.toneMapping = options.toneMapping;
            this.referenceWhite = options.referenceWhite;
        }

        /**
         * Sets the rendering intent; see {@link ColorTransformOptions#intent()}.
         *
         * @param intent the value
         * @return this builder
         */
        public Builder intent(RenderingIntent intent) {
            this.intent = Objects.requireNonNull(intent, "intent");
            return this;
        }

        /**
         * Sets whether black point compensation is requested; see
         * {@link ColorTransformOptions#blackPointCompensation()}.
         *
         * @param blackPointCompensation the value
         * @return this builder
         */
        public Builder blackPointCompensation(boolean blackPointCompensation) {
            this.blackPointCompensation = blackPointCompensation;
            return this;
        }

        /**
         * Sets the tone mapping of high-dynamic-range sources; see
         * {@link ColorTransformOptions#toneMapping()}.
         *
         * @param toneMapping the value
         * @return this builder
         */
        public Builder toneMapping(ToneMapping toneMapping) {
            this.toneMapping = Objects.requireNonNull(toneMapping, "toneMapping");
            return this;
        }

        /**
         * Sets the reference white; see {@link ColorTransformOptions#referenceWhite()}.
         *
         * @param referenceWhite the value
         * @return this builder
         */
        public Builder referenceWhite(double referenceWhite) {
            this.referenceWhite = referenceWhite;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the reference white is NaN or outside 1..10000
         */
        public ColorTransformOptions build() {
            Checks.inRange("reference white", referenceWhite, 1.0, 10000.0);
            return new ColorTransformOptions(intent, blackPointCompensation, toneMapping, referenceWhite);
        }
    }
}
