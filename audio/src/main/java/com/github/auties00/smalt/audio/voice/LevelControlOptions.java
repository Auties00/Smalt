package com.github.auties00.smalt.audio.voice;

import java.util.Objects;

/**
 * Holds the settings of the level controller of a {@link VoiceProcessor}. Instances are immutable
 * and thread-safe; create them with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>The level controller is the last stage of the capture path and works on the full band of
 * every processed channel. It applies, in order, an adaptive digital gain (when
 * {@link #adaptiveDigital()} is set) that brings the speech level, estimated over the frames a
 * neural-network voice activity detector classifies as speech, to a level that leaves
 * {@link #headroomDb()} below full scale and that a saturation protector lowers before loud
 * peaks; the fixed gain {@link #fixedGainDb()}; and a limiter that keeps every sample within full
 * scale. With {@link #inputVolumeController()} it also recommends a microphone volume, read from
 * {@link VoiceProcessor#recommendedAnalogLevel()}.
 */
public final class LevelControlOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final LevelControlOptions DEFAULTS = new Builder().build();

    /**
     * The largest adaptive digital gain, in dB, beyond which the 16-bit range offers nothing to raise.
     */
    private static final float MAX_ADAPTIVE_GAIN_DB = 100.0f;

    private final float fixedGainDb;

    private final boolean adaptiveDigital;

    private final float headroomDb;

    private final float maxGainDb;

    private final float initialGainDb;

    private final float maxGainChangeDbPerSecond;

    private final float maxOutputNoiseLevelDbfs;

    private final boolean inputVolumeController;

    private LevelControlOptions(Builder builder) {
        this.fixedGainDb = builder.fixedGainDb;
        this.adaptiveDigital = builder.adaptiveDigital;
        this.headroomDb = builder.headroomDb;
        this.maxGainDb = builder.maxGainDb;
        this.initialGainDb = builder.initialGainDb;
        this.maxGainChangeDbPerSecond = builder.maxGainChangeDbPerSecond;
        this.maxOutputNoiseLevelDbfs = builder.maxOutputNoiseLevelDbfs;
        this.inputVolumeController = builder.inputVolumeController;
    }

    /**
     * Returns the default options: the adaptive digital gain with its default tuning, no fixed gain
     * and no input volume controller.
     *
     * @return the default options
     */
    public static LevelControlOptions defaults() {
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
     * {@return the gain applied after the adaptive digital gain, in dB, at least 0 and below 50; 0 by
     * default}
     */
    public float fixedGainDb() {
        return fixedGainDb;
    }

    /**
     * {@return whether the adaptive digital gain runs; {@code true} by default}
     */
    public boolean adaptiveDigital() {
        return adaptiveDigital;
    }

    /**
     * {@return the margin the adaptive digital gain keeps between the estimated speech level and
     * full scale, in dB, finite and at least 0; 5 by default}
     *
     * <p>The setting applies only when {@link #adaptiveDigital()} is set.
     */
    public float headroomDb() {
        return headroomDb;
    }

    /**
     * {@return the largest adaptive digital gain, in dB, above 0 and at most 100; 50 by default}
     *
     * <p>The setting applies only when {@link #adaptiveDigital()} is set.
     */
    public float maxGainDb() {
        return maxGainDb;
    }

    /**
     * {@return the adaptive digital gain applied before the speech level is known, in dB, from 0 to
     * 100; 15 by default}
     *
     * <p>The setting applies only when {@link #adaptiveDigital()} is set.
     */
    public float initialGainDb() {
        return initialGainDb;
    }

    /**
     * {@return the fastest change of the adaptive digital gain, in dB per second, finite and
     * above 0; 6 by default}
     *
     * <p>The setting applies only when {@link #adaptiveDigital()} is set.
     */
    public float maxGainChangeDbPerSecond() {
        return maxGainChangeDbPerSecond;
    }

    /**
     * {@return the highest noise level the adaptive digital gain may raise the background noise to,
     * in dB relative to full scale, finite and at most 0; -50 by default}
     *
     * <p>The setting applies only when {@link #adaptiveDigital()} is set.
     */
    public float maxOutputNoiseLevelDbfs() {
        return maxOutputNoiseLevelDbfs;
    }

    /**
     * {@return whether the level controller recommends a microphone volume; {@code false} by
     * default}
     *
     * <p>The controller lowers the volume when the input clips and raises or lowers it, in steps a
     * second or more apart, while the speech level stays outside -50 to -30 dB below full scale.
     * The application reports the volume in effect through
     * {@link VoiceProcessor#setAnalogLevel(int)}, at least once before the first capture frame.
     */
    public boolean inputVolumeController() {
        return inputVolumeController;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof LevelControlOptions that
                && Float.compare(fixedGainDb, that.fixedGainDb) == 0
                && adaptiveDigital == that.adaptiveDigital
                && Float.compare(headroomDb, that.headroomDb) == 0
                && Float.compare(maxGainDb, that.maxGainDb) == 0
                && Float.compare(initialGainDb, that.initialGainDb) == 0
                && Float.compare(maxGainChangeDbPerSecond, that.maxGainChangeDbPerSecond) == 0
                && Float.compare(maxOutputNoiseLevelDbfs, that.maxOutputNoiseLevelDbfs) == 0
                && inputVolumeController == that.inputVolumeController;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fixedGainDb, adaptiveDigital, headroomDb, maxGainDb, initialGainDb,
                maxGainChangeDbPerSecond, maxOutputNoiseLevelDbfs, inputVolumeController);
    }

    @Override
    public String toString() {
        return "LevelControlOptions[fixedGainDb=" + fixedGainDb
                + ", adaptiveDigital=" + adaptiveDigital
                + ", headroomDb=" + headroomDb
                + ", maxGainDb=" + maxGainDb
                + ", initialGainDb=" + initialGainDb
                + ", maxGainChangeDbPerSecond=" + maxGainChangeDbPerSecond
                + ", maxOutputNoiseLevelDbfs=" + maxOutputNoiseLevelDbfs
                + ", inputVolumeController=" + inputVolumeController + "]";
    }

    /**
     * A builder of {@link LevelControlOptions}.
     *
     * <p>Setters accept any value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private float fixedGainDb;

        private boolean adaptiveDigital;

        private float headroomDb;

        private float maxGainDb;

        private float initialGainDb;

        private float maxGainChangeDbPerSecond;

        private float maxOutputNoiseLevelDbfs;

        private boolean inputVolumeController;

        private Builder() {
            this.fixedGainDb = 0.0f;
            this.adaptiveDigital = true;
            this.headroomDb = 5.0f;
            this.maxGainDb = 50.0f;
            this.initialGainDb = 15.0f;
            this.maxGainChangeDbPerSecond = 6.0f;
            this.maxOutputNoiseLevelDbfs = -50.0f;
            this.inputVolumeController = false;
        }

        private Builder(LevelControlOptions options) {
            this.fixedGainDb = options.fixedGainDb;
            this.adaptiveDigital = options.adaptiveDigital;
            this.headroomDb = options.headroomDb;
            this.maxGainDb = options.maxGainDb;
            this.initialGainDb = options.initialGainDb;
            this.maxGainChangeDbPerSecond = options.maxGainChangeDbPerSecond;
            this.maxOutputNoiseLevelDbfs = options.maxOutputNoiseLevelDbfs;
            this.inputVolumeController = options.inputVolumeController;
        }

        /**
         * Sets the fixed gain; see {@link LevelControlOptions#fixedGainDb()}.
         *
         * @param fixedGainDb the value
         * @return this builder
         */
        public Builder fixedGainDb(float fixedGainDb) {
            this.fixedGainDb = fixedGainDb;
            return this;
        }

        /**
         * Sets whether the adaptive digital gain runs; see
         * {@link LevelControlOptions#adaptiveDigital()}.
         *
         * @param adaptiveDigital the value
         * @return this builder
         */
        public Builder adaptiveDigital(boolean adaptiveDigital) {
            this.adaptiveDigital = adaptiveDigital;
            return this;
        }

        /**
         * Sets the headroom of the adaptive digital gain; see
         * {@link LevelControlOptions#headroomDb()}.
         *
         * @param headroomDb the value
         * @return this builder
         */
        public Builder headroomDb(float headroomDb) {
            this.headroomDb = headroomDb;
            return this;
        }

        /**
         * Sets the largest adaptive digital gain; see {@link LevelControlOptions#maxGainDb()}.
         *
         * @param maxGainDb the value
         * @return this builder
         */
        public Builder maxGainDb(float maxGainDb) {
            this.maxGainDb = maxGainDb;
            return this;
        }

        /**
         * Sets the initial adaptive digital gain; see {@link LevelControlOptions#initialGainDb()}.
         *
         * @param initialGainDb the value
         * @return this builder
         */
        public Builder initialGainDb(float initialGainDb) {
            this.initialGainDb = initialGainDb;
            return this;
        }

        /**
         * Sets the fastest change of the adaptive digital gain; see
         * {@link LevelControlOptions#maxGainChangeDbPerSecond()}.
         *
         * @param maxGainChangeDbPerSecond the value
         * @return this builder
         */
        public Builder maxGainChangeDbPerSecond(float maxGainChangeDbPerSecond) {
            this.maxGainChangeDbPerSecond = maxGainChangeDbPerSecond;
            return this;
        }

        /**
         * Sets the highest output noise level of the adaptive digital gain; see
         * {@link LevelControlOptions#maxOutputNoiseLevelDbfs()}.
         *
         * @param maxOutputNoiseLevelDbfs the value
         * @return this builder
         */
        public Builder maxOutputNoiseLevelDbfs(float maxOutputNoiseLevelDbfs) {
            this.maxOutputNoiseLevelDbfs = maxOutputNoiseLevelDbfs;
            return this;
        }

        /**
         * Sets whether the level controller recommends a microphone volume; see
         * {@link LevelControlOptions#inputVolumeController()}.
         *
         * @param inputVolumeController the value
         * @return this builder
         */
        public Builder inputVolumeController(boolean inputVolumeController) {
            this.inputVolumeController = inputVolumeController;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the fixed gain is not at least 0 and below 50 dB, the
         * headroom is negative or not finite, the largest gain is not above 0 and at most 100 dB, the
         * initial gain is outside 0 to 100 dB, the fastest gain change is not finite and above 0, or the
         * highest output noise level is above 0 or not finite
         */
        public LevelControlOptions build() {
            if (!(fixedGainDb >= 0.0f && fixedGainDb < 50.0f)) {
                throw new IllegalArgumentException("fixed gain must be in [0, 50) dB: " + fixedGainDb);
            }
            if (!(headroomDb >= 0.0f && Float.isFinite(headroomDb))) {
                throw new IllegalArgumentException("headroom must be finite and at least 0 dB: " + headroomDb);
            }
            if (!(maxGainDb > 0.0f && maxGainDb <= MAX_ADAPTIVE_GAIN_DB)) {
                throw new IllegalArgumentException("largest gain must be in (0, 100] dB: " + maxGainDb);
            }
            if (!(initialGainDb >= 0.0f && initialGainDb <= MAX_ADAPTIVE_GAIN_DB)) {
                throw new IllegalArgumentException("initial gain must be in [0, 100] dB: " + initialGainDb);
            }
            if (!(maxGainChangeDbPerSecond > 0.0f && Float.isFinite(maxGainChangeDbPerSecond))) {
                throw new IllegalArgumentException("fastest gain change must be finite and above 0 dB/s: "
                        + maxGainChangeDbPerSecond);
            }
            if (!(maxOutputNoiseLevelDbfs <= 0.0f && Float.isFinite(maxOutputNoiseLevelDbfs))) {
                throw new IllegalArgumentException("highest output noise level must be finite and at most 0 dBFS: "
                        + maxOutputNoiseLevelDbfs);
            }
            return new LevelControlOptions(this);
        }
    }
}
