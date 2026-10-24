package com.github.auties00.smalt.audio.voice;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of the managed analog gain controller of a {@link VoiceProcessor}
 * ({@link GainControl#MANAGED_ANALOG}). Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AnalogGainControllerOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final AnalogGainControllerOptions DEFAULTS = new Builder().build();

    /**
     * The highest microphone volume.
     */
    private static final int MAX_LEVEL = 255;

    private final int clippedLevelMin;

    private final int clippedLevelStep;

    private final float clippedRatioThreshold;

    private final int clippedWaitFrames;

    private final boolean digitalAdaptive;

    private final ClippingPrediction clippingPrediction;

    private final int predictionWindowFrames;

    private final int referenceWindowFrames;

    private final int referenceWindowDelayFrames;

    private final float clippingThresholdDbfs;

    private final float crestFactorMarginDb;

    private final boolean predictedStep;

    private AnalogGainControllerOptions(Builder builder) {
        this.clippedLevelMin = builder.clippedLevelMin;
        this.clippedLevelStep = builder.clippedLevelStep;
        this.clippedRatioThreshold = builder.clippedRatioThreshold;
        this.clippedWaitFrames = builder.clippedWaitFrames;
        this.digitalAdaptive = builder.digitalAdaptive;
        this.clippingPrediction = builder.clippingPrediction;
        this.predictionWindowFrames = builder.predictionWindowFrames;
        this.referenceWindowFrames = builder.referenceWindowFrames;
        this.referenceWindowDelayFrames = builder.referenceWindowDelayFrames;
        this.clippingThresholdDbfs = builder.clippingThresholdDbfs;
        this.crestFactorMarginDb = builder.crestFactorMarginDb;
        this.predictedStep = builder.predictedStep;
    }

    /**
     * Returns the default options: a 15-step volume reduction whenever more than a tenth of a frame
     * clips, at most every 3 seconds and never below volume 70, an adaptive digital compressor and no
     * clipping prediction.
     *
     * @return the default options
     */
    public static AnalogGainControllerOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the lowest microphone volume a clipping reduction lowers the volume to, from 0 to 254;
     * 70 by default}
     */
    public int clippedLevelMin() {
        return clippedLevelMin;
    }

    /**
     * {@return the volume reduction applied when the input clips, from 1 to 255; 15 by default}
     */
    public int clippedLevelStep() {
        return clippedLevelStep;
    }

    /**
     * {@return the fraction of the samples of a frame at full scale above which the frame counts as
     * clipped, above 0 and below 1; 0.1 by default}
     */
    public float clippedRatioThreshold() {
        return clippedRatioThreshold;
    }

    /**
     * {@return the number of 10 ms frames after a clipping reduction during which clipping is
     * ignored, at least 1; 300 by default}
     */
    public int clippedWaitFrames() {
        return clippedWaitFrames;
    }

    /**
     * {@return whether the digital compressor after the volume control adapts its gain to the
     * remaining level error; {@code true} by default}
     *
     * <p>Without it the compressor applies no gain and no limiter.
     */
    public boolean digitalAdaptive() {
        return digitalAdaptive;
    }

    /**
     * {@return how clipping is anticipated; {@link ClippingPrediction#OFF} by default}
     */
    public ClippingPrediction clippingPrediction() {
        return clippingPrediction;
    }

    /**
     * {@return the number of latest 10 ms frames whose level the clipping prediction examines, at
     * least 1; 5 by default}
     *
     * <p>The setting applies only when {@link #clippingPrediction()} is not
     * {@link ClippingPrediction#OFF}.
     */
    public int predictionWindowFrames() {
        return predictionWindowFrames;
    }

    /**
     * {@return the number of 10 ms frames of the reference window of the clipping prediction, at
     * least 1; 5 by default}
     *
     * <p>The setting applies only when {@link #clippingPrediction()} is not
     * {@link ClippingPrediction#OFF}.
     */
    public int referenceWindowFrames() {
        return referenceWindowFrames;
    }

    /**
     * {@return how many 10 ms frames the reference window of the clipping prediction ends before the
     * latest frame, at least 0; 5 by default}
     *
     * <p>The reference window must reach further back than the prediction window:
     * {@code referenceWindowFrames() + referenceWindowDelayFrames() > predictionWindowFrames()}. The
     * setting applies only when {@link #clippingPrediction()} is not {@link ClippingPrediction#OFF}.
     */
    public int referenceWindowDelayFrames() {
        return referenceWindowDelayFrames;
    }

    /**
     * {@return the peak level above which the clipping prediction may predict clipping, in dB
     * relative to full scale; -1 by default}
     *
     * <p>The peak predictors ({@link ClippingPrediction#ADAPTIVE_STEP_PEAK},
     * {@link ClippingPrediction#FIXED_STEP_PEAK}) truncate the threshold to whole decibels toward
     * zero. The setting applies only when {@link #clippingPrediction()} is not
     * {@link ClippingPrediction#OFF}.
     */
    public float clippingThresholdDbfs() {
        return clippingThresholdDbfs;
    }

    /**
     * {@return the drop of the crest factor, in dB, that {@link ClippingPrediction#CLIPPING_EVENT}
     * reads as a coming clipping event, at least 0; 3 by default}
     */
    public float crestFactorMarginDb() {
        return crestFactorMarginDb;
    }

    /**
     * {@return whether a predicted clipping lowers the volume by the predicted step; {@code true} by
     * default}
     *
     * <p>Without it a prediction changes nothing and only an actual clipping lowers the volume. The
     * setting applies only when {@link #clippingPrediction()} is not {@link ClippingPrediction#OFF}.
     */
    public boolean predictedStep() {
        return predictedStep;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof AnalogGainControllerOptions that
                && clippedLevelMin == that.clippedLevelMin
                && clippedLevelStep == that.clippedLevelStep
                && Float.compare(clippedRatioThreshold, that.clippedRatioThreshold) == 0
                && clippedWaitFrames == that.clippedWaitFrames
                && digitalAdaptive == that.digitalAdaptive
                && clippingPrediction == that.clippingPrediction
                && predictionWindowFrames == that.predictionWindowFrames
                && referenceWindowFrames == that.referenceWindowFrames
                && referenceWindowDelayFrames == that.referenceWindowDelayFrames
                && Float.compare(clippingThresholdDbfs, that.clippingThresholdDbfs) == 0
                && Float.compare(crestFactorMarginDb, that.crestFactorMarginDb) == 0
                && predictedStep == that.predictedStep;
    }

    @Override
    public int hashCode() {
        return Objects.hash(clippedLevelMin, clippedLevelStep, clippedRatioThreshold, clippedWaitFrames,
                digitalAdaptive, clippingPrediction, predictionWindowFrames, referenceWindowFrames,
                referenceWindowDelayFrames, clippingThresholdDbfs, crestFactorMarginDb, predictedStep);
    }

    @Override
    public String toString() {
        return "AnalogGainControllerOptions[clippedLevelMin=" + clippedLevelMin
                + ", clippedLevelStep=" + clippedLevelStep
                + ", clippedRatioThreshold=" + clippedRatioThreshold
                + ", clippedWaitFrames=" + clippedWaitFrames
                + ", digitalAdaptive=" + digitalAdaptive
                + ", clippingPrediction=" + clippingPrediction
                + ", predictionWindowFrames=" + predictionWindowFrames
                + ", referenceWindowFrames=" + referenceWindowFrames
                + ", referenceWindowDelayFrames=" + referenceWindowDelayFrames
                + ", clippingThresholdDbfs=" + clippingThresholdDbfs
                + ", crestFactorMarginDb=" + crestFactorMarginDb
                + ", predictedStep=" + predictedStep + "]";
    }

    /**
     * A builder of {@link AnalogGainControllerOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private int clippedLevelMin;

        private int clippedLevelStep;

        private float clippedRatioThreshold;

        private int clippedWaitFrames;

        private boolean digitalAdaptive;

        private ClippingPrediction clippingPrediction;

        private int predictionWindowFrames;

        private int referenceWindowFrames;

        private int referenceWindowDelayFrames;

        private float clippingThresholdDbfs;

        private float crestFactorMarginDb;

        private boolean predictedStep;

        private Builder() {
            this.clippedLevelMin = 70;
            this.clippedLevelStep = 15;
            this.clippedRatioThreshold = 0.1f;
            this.clippedWaitFrames = 300;
            this.digitalAdaptive = true;
            this.clippingPrediction = ClippingPrediction.OFF;
            this.predictionWindowFrames = 5;
            this.referenceWindowFrames = 5;
            this.referenceWindowDelayFrames = 5;
            this.clippingThresholdDbfs = -1.0f;
            this.crestFactorMarginDb = 3.0f;
            this.predictedStep = true;
        }

        private Builder(AnalogGainControllerOptions options) {
            this.clippedLevelMin = options.clippedLevelMin;
            this.clippedLevelStep = options.clippedLevelStep;
            this.clippedRatioThreshold = options.clippedRatioThreshold;
            this.clippedWaitFrames = options.clippedWaitFrames;
            this.digitalAdaptive = options.digitalAdaptive;
            this.clippingPrediction = options.clippingPrediction;
            this.predictionWindowFrames = options.predictionWindowFrames;
            this.referenceWindowFrames = options.referenceWindowFrames;
            this.referenceWindowDelayFrames = options.referenceWindowDelayFrames;
            this.clippingThresholdDbfs = options.clippingThresholdDbfs;
            this.crestFactorMarginDb = options.crestFactorMarginDb;
            this.predictedStep = options.predictedStep;
        }

        /**
         * Sets the lowest volume of a clipping reduction; see
         * {@link AnalogGainControllerOptions#clippedLevelMin()}.
         *
         * @param clippedLevelMin the value
         * @return this builder
         */
        public Builder clippedLevelMin(int clippedLevelMin) {
            this.clippedLevelMin = clippedLevelMin;
            return this;
        }

        /**
         * Sets the volume reduction of a clipping; see
         * {@link AnalogGainControllerOptions#clippedLevelStep()}.
         *
         * @param clippedLevelStep the value
         * @return this builder
         */
        public Builder clippedLevelStep(int clippedLevelStep) {
            this.clippedLevelStep = clippedLevelStep;
            return this;
        }

        /**
         * Sets the fraction of full-scale samples of a clipped frame; see
         * {@link AnalogGainControllerOptions#clippedRatioThreshold()}.
         *
         * @param clippedRatioThreshold the value
         * @return this builder
         */
        public Builder clippedRatioThreshold(float clippedRatioThreshold) {
            this.clippedRatioThreshold = clippedRatioThreshold;
            return this;
        }

        /**
         * Sets the frames during which clipping is ignored after a reduction; see
         * {@link AnalogGainControllerOptions#clippedWaitFrames()}.
         *
         * @param clippedWaitFrames the value
         * @return this builder
         */
        public Builder clippedWaitFrames(int clippedWaitFrames) {
            this.clippedWaitFrames = clippedWaitFrames;
            return this;
        }

        /**
         * Sets whether the digital compressor adapts its gain; see
         * {@link AnalogGainControllerOptions#digitalAdaptive()}.
         *
         * @param digitalAdaptive the value
         * @return this builder
         */
        public Builder digitalAdaptive(boolean digitalAdaptive) {
            this.digitalAdaptive = digitalAdaptive;
            return this;
        }

        /**
         * Sets how clipping is anticipated; see
         * {@link AnalogGainControllerOptions#clippingPrediction()}.
         *
         * @param clippingPrediction the value
         * @return this builder
         */
        public Builder clippingPrediction(ClippingPrediction clippingPrediction) {
            this.clippingPrediction = Objects.requireNonNull(clippingPrediction, "clippingPrediction");
            return this;
        }

        /**
         * Sets the length of the prediction window; see
         * {@link AnalogGainControllerOptions#predictionWindowFrames()}.
         *
         * @param predictionWindowFrames the value
         * @return this builder
         */
        public Builder predictionWindowFrames(int predictionWindowFrames) {
            this.predictionWindowFrames = predictionWindowFrames;
            return this;
        }

        /**
         * Sets the length of the reference window; see
         * {@link AnalogGainControllerOptions#referenceWindowFrames()}.
         *
         * @param referenceWindowFrames the value
         * @return this builder
         */
        public Builder referenceWindowFrames(int referenceWindowFrames) {
            this.referenceWindowFrames = referenceWindowFrames;
            return this;
        }

        /**
         * Sets the delay of the reference window; see
         * {@link AnalogGainControllerOptions#referenceWindowDelayFrames()}.
         *
         * @param referenceWindowDelayFrames the value
         * @return this builder
         */
        public Builder referenceWindowDelayFrames(int referenceWindowDelayFrames) {
            this.referenceWindowDelayFrames = referenceWindowDelayFrames;
            return this;
        }

        /**
         * Sets the peak threshold of the clipping prediction; see
         * {@link AnalogGainControllerOptions#clippingThresholdDbfs()}.
         *
         * @param clippingThresholdDbfs the value
         * @return this builder
         */
        public Builder clippingThresholdDbfs(float clippingThresholdDbfs) {
            this.clippingThresholdDbfs = clippingThresholdDbfs;
            return this;
        }

        /**
         * Sets the crest factor margin of the clipping event prediction; see
         * {@link AnalogGainControllerOptions#crestFactorMarginDb()}.
         *
         * @param crestFactorMarginDb the value
         * @return this builder
         */
        public Builder crestFactorMarginDb(float crestFactorMarginDb) {
            this.crestFactorMarginDb = crestFactorMarginDb;
            return this;
        }

        /**
         * Sets whether a prediction lowers the volume; see
         * {@link AnalogGainControllerOptions#predictedStep()}.
         *
         * @param predictedStep the value
         * @return this builder
         */
        public Builder predictedStep(boolean predictedStep) {
            this.predictedStep = predictedStep;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the lowest clipped volume is outside 0 to 254, the step
         * outside 1 to 255, the clipped ratio not strictly between 0 and 1, the wait below 1 frame,
         * the prediction window or the reference window below 1 frame, the reference delay negative,
         * the reference window and its delay not longer together than the prediction window, the
         * threshold not finite, or the crest factor margin negative or not finite
         */
        public AnalogGainControllerOptions build() {
            Checks.inRange("lowest clipped level", clippedLevelMin, 0, MAX_LEVEL - 1);
            Checks.inRange("clipped level step", clippedLevelStep, 1, MAX_LEVEL);
            if (!(clippedRatioThreshold > 0.0f && clippedRatioThreshold < 1.0f)) {
                throw new IllegalArgumentException("clipped ratio threshold must be in (0, 1): "
                        + clippedRatioThreshold);
            }
            Checks.inRange("clipped wait frames", clippedWaitFrames, 1, Integer.MAX_VALUE);
            Checks.inRange("prediction window", predictionWindowFrames, 1, Integer.MAX_VALUE);
            Checks.inRange("reference window", referenceWindowFrames, 1, Integer.MAX_VALUE);
            Checks.inRange("reference window delay", referenceWindowDelayFrames, 0, Integer.MAX_VALUE);
            if ((long) referenceWindowFrames + referenceWindowDelayFrames <= predictionWindowFrames) {
                throw new IllegalArgumentException("the reference window and its delay must be longer than the "
                        + "prediction window: " + referenceWindowFrames + " + " + referenceWindowDelayFrames
                        + " <= " + predictionWindowFrames);
            }
            if (!Float.isFinite(clippingThresholdDbfs)) {
                throw new IllegalArgumentException("clipping threshold must be finite: " + clippingThresholdDbfs);
            }
            if (!(crestFactorMarginDb >= 0.0f && Float.isFinite(crestFactorMarginDb))) {
                throw new IllegalArgumentException("crest factor margin must be finite and at least 0 dB: "
                        + crestFactorMarginDb);
            }
            return new AnalogGainControllerOptions(this);
        }
    }
}
