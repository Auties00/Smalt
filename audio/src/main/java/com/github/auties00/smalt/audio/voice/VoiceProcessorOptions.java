package com.github.auties00.smalt.audio.voice;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a {@link VoiceProcessor}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class VoiceProcessorOptions {
    /**
     * The default sampling rate, in hertz.
     */
    private static final int DEFAULT_SAMPLE_RATE = 16_000;

    /**
     * The highest number of capture or render channels.
     */
    private static final int MAX_CHANNELS = 8;

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

    private final int sampleRate;

    private final int captureChannels;

    private final int renderChannels;

    private final boolean multichannelCapture;

    private final boolean multichannelRender;

    private final EchoCancellation echoCancellation;

    private final MobileEchoRoute mobileEchoRoute;

    private final boolean comfortNoise;

    private final NoiseSuppression noiseSuppression;

    private final GainControl gainControl;

    private final int targetLevelDbfs;

    private final int compressionGainDb;

    private final boolean limiter;

    private final AnalogGainControllerOptions analogGainController;

    private final LevelControlOptions levelControl;

    private VoiceProcessorOptions(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.captureChannels = builder.captureChannels;
        this.renderChannels = builder.renderChannels;
        this.multichannelCapture = builder.multichannelCapture;
        this.multichannelRender = builder.multichannelRender;
        this.echoCancellation = builder.echoCancellation;
        this.mobileEchoRoute = builder.mobileEchoRoute;
        this.comfortNoise = builder.comfortNoise;
        this.noiseSuppression = builder.noiseSuppression;
        this.gainControl = builder.gainControl;
        this.targetLevelDbfs = builder.targetLevelDbfs;
        this.compressionGainDb = builder.compressionGainDb;
        this.limiter = builder.limiter;
        this.analogGainController = builder.analogGainController;
        this.levelControl = builder.levelControl;
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
     * Returns the options of a two-way voice call: 16 kHz mono, the {@link EchoCancellation#STANDARD}
     * echo canceller, noise suppression at {@link NoiseSuppression#HIGH} and no gain control.
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
     * {@return the sampling rate of both the render and the capture signal, in hertz: 8000, 16000,
     * 32000 or 48000; 16000 by default}
     *
     * <p>At 32 and 48 kHz the signal is split into bands of 8 kHz: the echo cancellers, the noise
     * suppressor and the gain controller of {@link #gainControl()} analyze the lowest 16 kHz band
     * and apply their gains to every band, while the high-pass filter and the level controller of
     * {@link #levelControl()} work on the full band. At 8 kHz both signals are resampled to 16 kHz
     * for processing and the capture signal back to 8 kHz, which adds about 1 ms of delay each
     * way.
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the number of channels of the capture signal, from 1 to 8; 1 by default}
     *
     * <p>With the {@link EchoCancellation#STANDARD} echo canceller and without
     * {@link #multichannelCapture()}, only the first capture channel is conditioned and the output
     * repeats it on every channel; otherwise every channel is conditioned on its own.
     */
    public int captureChannels() {
        return captureChannels;
    }

    /**
     * {@return the number of channels of the render signal, from 1 to 8; 1 by default}
     *
     * <p>Without {@link #multichannelRender()} the render channels are averaged into one before the
     * echo cancellers see them.
     */
    public int renderChannels() {
        return renderChannels;
    }

    /**
     * {@return whether the {@link EchoCancellation#STANDARD} echo canceller conditions every capture
     * channel; {@code false} by default}
     *
     * <p>The echo canceller then cancels the echo in each channel separately, which costs about one
     * mono canceller per channel. The setting applies only with {@link EchoCancellation#STANDARD};
     * the other configurations always condition every channel.
     */
    public boolean multichannelCapture() {
        return multichannelCapture;
    }

    /**
     * {@return whether the echo cancellers see every render channel instead of their average;
     * {@code false} by default}
     *
     * <p>With {@link EchoCancellation#STANDARD}, a render signal whose channels differ (true stereo
     * rather than a mono signal on several channels) is then modeled with one adaptive filter per
     * render channel, which cancels echo better when the loudspeakers play different signals. With
     * {@link EchoCancellation#MOBILE}, one canceller runs for every pair of capture and render
     * channel.
     */
    public boolean multichannelRender() {
        return multichannelRender;
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
     * {@return the gain controller of the lowest 16 kHz band; {@link GainControl#OFF} by default}
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

    /**
     * {@return the settings of the managed analog gain controller;
     * {@link AnalogGainControllerOptions#defaults()} by default}
     *
     * <p>The settings apply only when {@link #gainControl()} is {@link GainControl#MANAGED_ANALOG}.
     */
    public AnalogGainControllerOptions analogGainController() {
        return analogGainController;
    }

    /**
     * {@return the level controller that runs after every other stage, or an empty optional for
     * none, the default}
     */
    public Optional<LevelControlOptions> levelControl() {
        return Optional.ofNullable(levelControl);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof VoiceProcessorOptions that
                && sampleRate == that.sampleRate
                && captureChannels == that.captureChannels
                && renderChannels == that.renderChannels
                && multichannelCapture == that.multichannelCapture
                && multichannelRender == that.multichannelRender
                && echoCancellation == that.echoCancellation
                && mobileEchoRoute == that.mobileEchoRoute
                && comfortNoise == that.comfortNoise
                && noiseSuppression == that.noiseSuppression
                && gainControl == that.gainControl
                && targetLevelDbfs == that.targetLevelDbfs
                && compressionGainDb == that.compressionGainDb
                && limiter == that.limiter
                && analogGainController.equals(that.analogGainController)
                && Objects.equals(levelControl, that.levelControl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, captureChannels, renderChannels, multichannelCapture, multichannelRender,
                echoCancellation, mobileEchoRoute, comfortNoise, noiseSuppression, gainControl, targetLevelDbfs,
                compressionGainDb, limiter, analogGainController, levelControl);
    }

    @Override
    public String toString() {
        return "VoiceProcessorOptions[sampleRate=" + sampleRate
                + ", captureChannels=" + captureChannels
                + ", renderChannels=" + renderChannels
                + ", multichannelCapture=" + multichannelCapture
                + ", multichannelRender=" + multichannelRender
                + ", echoCancellation=" + echoCancellation
                + ", mobileEchoRoute=" + mobileEchoRoute
                + ", comfortNoise=" + comfortNoise
                + ", noiseSuppression=" + noiseSuppression
                + ", gainControl=" + gainControl
                + ", targetLevelDbfs=" + targetLevelDbfs
                + ", compressionGainDb=" + compressionGainDb
                + ", limiter=" + limiter
                + ", analogGainController=" + analogGainController
                + ", levelControl=" + levelControl + "]";
    }

    /**
     * A builder of {@link VoiceProcessorOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private int sampleRate;

        private int captureChannels;

        private int renderChannels;

        private boolean multichannelCapture;

        private boolean multichannelRender;

        private EchoCancellation echoCancellation;

        private MobileEchoRoute mobileEchoRoute;

        private boolean comfortNoise;

        private NoiseSuppression noiseSuppression;

        private GainControl gainControl;

        private int targetLevelDbfs;

        private int compressionGainDb;

        private boolean limiter;

        private AnalogGainControllerOptions analogGainController;

        private LevelControlOptions levelControl;

        private Builder() {
            this.sampleRate = DEFAULT_SAMPLE_RATE;
            this.captureChannels = 1;
            this.renderChannels = 1;
            this.multichannelCapture = false;
            this.multichannelRender = false;
            this.echoCancellation = EchoCancellation.STANDARD;
            this.mobileEchoRoute = MobileEchoRoute.SPEAKERPHONE;
            this.comfortNoise = false;
            this.noiseSuppression = NoiseSuppression.HIGH;
            this.gainControl = GainControl.OFF;
            this.targetLevelDbfs = DEFAULT_TARGET_LEVEL_DBFS;
            this.compressionGainDb = DEFAULT_COMPRESSION_GAIN_DB;
            this.limiter = true;
            this.analogGainController = AnalogGainControllerOptions.defaults();
            this.levelControl = null;
        }

        private Builder(VoiceProcessorOptions options) {
            this.sampleRate = options.sampleRate;
            this.captureChannels = options.captureChannels;
            this.renderChannels = options.renderChannels;
            this.multichannelCapture = options.multichannelCapture;
            this.multichannelRender = options.multichannelRender;
            this.echoCancellation = options.echoCancellation;
            this.mobileEchoRoute = options.mobileEchoRoute;
            this.comfortNoise = options.comfortNoise;
            this.noiseSuppression = options.noiseSuppression;
            this.gainControl = options.gainControl;
            this.targetLevelDbfs = options.targetLevelDbfs;
            this.compressionGainDb = options.compressionGainDb;
            this.limiter = options.limiter;
            this.analogGainController = options.analogGainController;
            this.levelControl = options.levelControl;
        }

        /**
         * Sets the sampling rate; see {@link VoiceProcessorOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the number of capture channels; see {@link VoiceProcessorOptions#captureChannels()}.
         *
         * @param captureChannels the value
         * @return this builder
         */
        public Builder captureChannels(int captureChannels) {
            this.captureChannels = captureChannels;
            return this;
        }

        /**
         * Sets the number of render channels; see {@link VoiceProcessorOptions#renderChannels()}.
         *
         * @param renderChannels the value
         * @return this builder
         */
        public Builder renderChannels(int renderChannels) {
            this.renderChannels = renderChannels;
            return this;
        }

        /**
         * Sets whether the standard echo canceller conditions every capture channel; see
         * {@link VoiceProcessorOptions#multichannelCapture()}.
         *
         * @param multichannelCapture the value
         * @return this builder
         */
        public Builder multichannelCapture(boolean multichannelCapture) {
            this.multichannelCapture = multichannelCapture;
            return this;
        }

        /**
         * Sets whether the echo cancellers see every render channel; see
         * {@link VoiceProcessorOptions#multichannelRender()}.
         *
         * @param multichannelRender the value
         * @return this builder
         */
        public Builder multichannelRender(boolean multichannelRender) {
            this.multichannelRender = multichannelRender;
            return this;
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
         * Sets the settings of the managed analog gain controller; see
         * {@link VoiceProcessorOptions#analogGainController()}.
         *
         * @param analogGainController the value
         * @return this builder
         */
        public Builder analogGainController(AnalogGainControllerOptions analogGainController) {
            this.analogGainController = Objects.requireNonNull(analogGainController, "analogGainController");
            return this;
        }

        /**
         * Sets the level controller; see {@link VoiceProcessorOptions#levelControl()}.
         *
         * @param levelControl the value
         * @return this builder
         */
        public Builder levelControl(LevelControlOptions levelControl) {
            this.levelControl = Objects.requireNonNull(levelControl, "levelControl");
            return this;
        }

        /**
         * Removes the level controller; see {@link VoiceProcessorOptions#levelControl()}.
         *
         * @return this builder
         */
        public Builder noLevelControl() {
            this.levelControl = null;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sampling rate is not 8000, 16000, 32000 or 48000,
         * either channel count is outside 1 to 8, the target level is outside 0 to 31, the
         * compression gain is outside 0 to 90, or the level controller recommends a microphone
         * volume while {@link VoiceProcessorOptions#gainControl()} is not {@link GainControl#OFF}
         */
        public VoiceProcessorOptions build() {
            if (sampleRate != 8_000 && sampleRate != 16_000 && sampleRate != 32_000 && sampleRate != 48_000) {
                throw new IllegalArgumentException("sample rate must be 8000, 16000, 32000 or 48000 Hz: "
                        + sampleRate);
            }
            Checks.inRange("capture channels", captureChannels, 1, MAX_CHANNELS);
            Checks.inRange("render channels", renderChannels, 1, MAX_CHANNELS);
            Checks.inRange("target level", targetLevelDbfs, 0, MAX_TARGET_LEVEL_DBFS);
            Checks.inRange("compression gain", compressionGainDb, 0, MAX_COMPRESSION_GAIN_DB);
            if (levelControl != null && levelControl.inputVolumeController() && gainControl != GainControl.OFF) {
                throw new IllegalArgumentException("the input volume controller of the level controller needs "
                        + "the gain control off: " + gainControl);
            }
            return new VoiceProcessorOptions(this);
        }
    }
}
