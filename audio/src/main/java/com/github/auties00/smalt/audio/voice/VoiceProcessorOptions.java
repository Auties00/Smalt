package com.github.auties00.smalt.audio.voice;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of a {@link VoiceProcessor}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class VoiceProcessorOptions {
    /**
     * The sampling rate of every processor, in hertz.
     */
    private static final int SAMPLE_RATE = 16_000;

    /**
     * The default target level, in dB below full scale.
     */
    private static final int DEFAULT_TARGET_LEVEL_DBFS = 3;

    /**
     * The default compression gain, in dB.
     */
    private static final int DEFAULT_COMPRESSION_GAIN_DB = 9;

    /**
     * The highest target level, in dB below full scale.
     */
    private static final int MAX_TARGET_LEVEL_DBFS = 31;

    /**
     * The highest compression gain, in dB.
     */
    private static final int MAX_COMPRESSION_GAIN_DB = 90;

    /**
     * The options of {@link #defaults()} and {@link #forVoip()}.
     */
    private static final VoiceProcessorOptions VOIP = new Builder().build();

    private final EchoCancellation echoCancellation;

    private final MobileEchoRoute mobileEchoRoute;

    private final boolean comfortNoise;

    private final NoiseSuppression noiseSuppression;

    private final GainControl gainControl;

    private final int targetLevelDbfs;

    private final int compressionGainDb;

    private final boolean limiter;

    private VoiceProcessorOptions(Builder builder) {
        this.echoCancellation = builder.echoCancellation;
        this.mobileEchoRoute = builder.mobileEchoRoute;
        this.comfortNoise = builder.comfortNoise;
        this.noiseSuppression = builder.noiseSuppression;
        this.gainControl = builder.gainControl;
        this.targetLevelDbfs = builder.targetLevelDbfs;
        this.compressionGainDb = builder.compressionGainDb;
        this.limiter = builder.limiter;
    }

    /**
     * Returns the default options, the same as {@link #forVoip()}.
     *
     * @return the default options
     */
    public static VoiceProcessorOptions defaults() {
        return VOIP;
    }

    /**
     * Returns the options of a two-way voice call: the {@link EchoCancellation#STANDARD} echo
     * canceller, noise suppression at {@link NoiseSuppression#HIGH} and no gain control.
     *
     * @apiNote Gain control is off because it changes the speech level, which a call application
     * may already manage; enable it with {@code toBuilder().gainControl(GainControl.ADAPTIVE_DIGITAL)}
     * to bring quiet and loud talkers to the same level.
     *
     * @return the voice call options
     */
    public static VoiceProcessorOptions forVoip() {
        return VOIP;
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
     * {@return the sampling rate of both the render and the capture signal, always 16000 Hz}
     */
    public int sampleRate() {
        return SAMPLE_RATE;
    }

    /**
     * {@return the echo canceller; {@link EchoCancellation#STANDARD} by default}
     */
    public EchoCancellation echoCancellation() {
        return echoCancellation;
    }

    /**
     * {@return the loudspeaker placement the mobile echo canceller is tuned for;
     * {@link MobileEchoRoute#SPEAKERPHONE} by default}
     *
     * <p>The setting applies only when {@link #echoCancellation()} is
     * {@link EchoCancellation#MOBILE}.
     */
    public MobileEchoRoute mobileEchoRoute() {
        return mobileEchoRoute;
    }

    /**
     * {@return whether the mobile echo canceller fills the bins it suppresses with comfort noise;
     * {@code false} by default}
     *
     * <p>The setting applies only when {@link #echoCancellation()} is
     * {@link EchoCancellation#MOBILE}.
     */
    public boolean comfortNoise() {
        return comfortNoise;
    }

    /**
     * {@return the noise suppression level; {@link NoiseSuppression#HIGH} by default}
     */
    public NoiseSuppression noiseSuppression() {
        return noiseSuppression;
    }

    /**
     * {@return the gain controller; {@link GainControl#OFF} by default}
     */
    public GainControl gainControl() {
        return gainControl;
    }

    /**
     * {@return the peak level the gain controller targets, in dB below full scale from 0 to 31; 3
     * by default}
     *
     * <p>The setting applies only when {@link #gainControl()} is not {@link GainControl#OFF}.
     */
    public int targetLevelDbfs() {
        return targetLevelDbfs;
    }

    /**
     * {@return the largest gain the gain controller's compressor applies to low-level speech, in
     * dB from 0 to 90; 9 by default}
     *
     * <p>The setting applies only when {@link #gainControl()} is not {@link GainControl#OFF}.
     */
    public int compressionGainDb() {
        return compressionGainDb;
    }

    /**
     * {@return whether the gain controller limits its output so that the target level is never
     * exceeded; {@code true} by default}
     *
     * <p>The setting applies only when {@link #gainControl()} is not {@link GainControl#OFF}.
     */
    public boolean limiter() {
        return limiter;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof VoiceProcessorOptions that
                && echoCancellation == that.echoCancellation
                && mobileEchoRoute == that.mobileEchoRoute
                && comfortNoise == that.comfortNoise
                && noiseSuppression == that.noiseSuppression
                && gainControl == that.gainControl
                && targetLevelDbfs == that.targetLevelDbfs
                && compressionGainDb == that.compressionGainDb
                && limiter == that.limiter;
    }

    @Override
    public int hashCode() {
        return Objects.hash(echoCancellation, mobileEchoRoute, comfortNoise, noiseSuppression, gainControl,
                targetLevelDbfs, compressionGainDb, limiter);
    }

    @Override
    public String toString() {
        return "VoiceProcessorOptions[sampleRate=" + SAMPLE_RATE
                + ", echoCancellation=" + echoCancellation
                + ", mobileEchoRoute=" + mobileEchoRoute
                + ", comfortNoise=" + comfortNoise
                + ", noiseSuppression=" + noiseSuppression
                + ", gainControl=" + gainControl
                + ", targetLevelDbfs=" + targetLevelDbfs
                + ", compressionGainDb=" + compressionGainDb
                + ", limiter=" + limiter + "]";
    }

    /**
     * A builder of {@link VoiceProcessorOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private EchoCancellation echoCancellation;

        private MobileEchoRoute mobileEchoRoute;

        private boolean comfortNoise;

        private NoiseSuppression noiseSuppression;

        private GainControl gainControl;

        private int targetLevelDbfs;

        private int compressionGainDb;

        private boolean limiter;

        private Builder() {
            this.echoCancellation = EchoCancellation.STANDARD;
            this.mobileEchoRoute = MobileEchoRoute.SPEAKERPHONE;
            this.comfortNoise = false;
            this.noiseSuppression = NoiseSuppression.HIGH;
            this.gainControl = GainControl.OFF;
            this.targetLevelDbfs = DEFAULT_TARGET_LEVEL_DBFS;
            this.compressionGainDb = DEFAULT_COMPRESSION_GAIN_DB;
            this.limiter = true;
        }

        private Builder(VoiceProcessorOptions options) {
            this.echoCancellation = options.echoCancellation;
            this.mobileEchoRoute = options.mobileEchoRoute;
            this.comfortNoise = options.comfortNoise;
            this.noiseSuppression = options.noiseSuppression;
            this.gainControl = options.gainControl;
            this.targetLevelDbfs = options.targetLevelDbfs;
            this.compressionGainDb = options.compressionGainDb;
            this.limiter = options.limiter;
        }

        /**
         * Sets the echo canceller; see {@link VoiceProcessorOptions#echoCancellation()}.
         *
         * @param echoCancellation the value
         * @return this builder
         */
        public Builder echoCancellation(EchoCancellation echoCancellation) {
            this.echoCancellation = Objects.requireNonNull(echoCancellation, "echoCancellation");
            return this;
        }

        /**
         * Sets the loudspeaker placement the mobile echo canceller is tuned for; see
         * {@link VoiceProcessorOptions#mobileEchoRoute()}.
         *
         * @param mobileEchoRoute the value
         * @return this builder
         */
        public Builder mobileEchoRoute(MobileEchoRoute mobileEchoRoute) {
            this.mobileEchoRoute = Objects.requireNonNull(mobileEchoRoute, "mobileEchoRoute");
            return this;
        }

        /**
         * Sets whether the mobile echo canceller generates comfort noise; see
         * {@link VoiceProcessorOptions#comfortNoise()}.
         *
         * @param comfortNoise the value
         * @return this builder
         */
        public Builder comfortNoise(boolean comfortNoise) {
            this.comfortNoise = comfortNoise;
            return this;
        }

        /**
         * Sets the noise suppression level; see {@link VoiceProcessorOptions#noiseSuppression()}.
         *
         * @param noiseSuppression the value
         * @return this builder
         */
        public Builder noiseSuppression(NoiseSuppression noiseSuppression) {
            this.noiseSuppression = Objects.requireNonNull(noiseSuppression, "noiseSuppression");
            return this;
        }

        /**
         * Sets the gain controller; see {@link VoiceProcessorOptions#gainControl()}.
         *
         * @param gainControl the value
         * @return this builder
         */
        public Builder gainControl(GainControl gainControl) {
            this.gainControl = Objects.requireNonNull(gainControl, "gainControl");
            return this;
        }

        /**
         * Sets the peak level the gain controller targets; see
         * {@link VoiceProcessorOptions#targetLevelDbfs()}.
         *
         * @param targetLevelDbfs the value
         * @return this builder
         */
        public Builder targetLevelDbfs(int targetLevelDbfs) {
            this.targetLevelDbfs = targetLevelDbfs;
            return this;
        }

        /**
         * Sets the largest gain of the gain controller's compressor; see
         * {@link VoiceProcessorOptions#compressionGainDb()}.
         *
         * @param compressionGainDb the value
         * @return this builder
         */
        public Builder compressionGainDb(int compressionGainDb) {
            this.compressionGainDb = compressionGainDb;
            return this;
        }

        /**
         * Sets whether the gain controller limits its output; see
         * {@link VoiceProcessorOptions#limiter()}.
         *
         * @param limiter the value
         * @return this builder
         */
        public Builder limiter(boolean limiter) {
            this.limiter = limiter;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the target level is outside 0 to 31 or the compression
         * gain is outside 0 to 90
         */
        public VoiceProcessorOptions build() {
            Checks.inRange("target level", targetLevelDbfs, 0, MAX_TARGET_LEVEL_DBFS);
            Checks.inRange("compression gain", compressionGainDb, 0, MAX_COMPRESSION_GAIN_DB);
            return new VoiceProcessorOptions(this);
        }
    }
}
