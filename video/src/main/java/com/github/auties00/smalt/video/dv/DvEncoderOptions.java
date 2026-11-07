package com.github.auties00.smalt.video.dv;

import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a {@link DvEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>DV is a constant-size format: every frame of a {@link DvProfile} takes {@link DvProfile#frameSize()} bytes,
 * so the bitrate and the frame rate follow from the profile.
 */
public final class DvEncoderOptions {
    /**
     * The settings of {@link #defaults()}.
     */
    private static final DvEncoderOptions DEFAULTS = new Builder().build();

    private final DvProfile profile;

    private final boolean wide;

    private final boolean adaptiveDct;

    private final Optional<Rational> timeBase;

    private final int threads;

    private DvEncoderOptions(Builder builder) {
        this.profile = builder.profile;
        this.wide = builder.wide;
        this.adaptiveDct = builder.adaptiveDct;
        this.timeBase = Optional.ofNullable(builder.timeBase);
        this.threads = builder.threads;
    }

    /**
     * Returns {@link DvProfile#DV_525} at 4:3 with adaptive DCT modes, timestamps in frames, on the shared pool.
     *
     * @return the options
     */
    public static DvEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the {@link #defaults()} with another profile.
     *
     * @param profile the DV format
     * @return the options
     */
    public static DvEncoderOptions of(DvProfile profile) {
        return new Builder().profile(profile).build();
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
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
     * {@return the format of the frames, which fixes the picture size and the frame rate; {@link DvProfile#DV_525}
     * by default}
     */
    public DvProfile profile() {
        return profile;
    }

    /**
     * {@return whether the frames signal a 16:9 display in their VAUX source control pack rather than 4:3;
     * {@code false} by default}
     *
     * <p>The DVCPRO HD profiles always display at 16:9 and ignore this setting.
     */
    public boolean wide() {
        return wide;
    }

    /**
     * {@return whether a block whose two fields differ much may be coded in the 2-4-8 DCT mode (SD) or its
     * macroblock in the field DCT mode (DVCPRO HD 1080-line), which suit interlaced motion; {@code true} by
     * default}
     *
     * <p>With this setting off every block uses the 8-8 (frame) mode, which suits progressive content carried in
     * an interlaced format. The 720-line profiles are progressive and always use the frame mode.
     */
    public boolean adaptiveDct() {
        return adaptiveDct;
    }

    /**
     * {@return the time base of the packet timestamps, or empty for one unit per frame of the profile; empty by
     * default}
     */
    public Optional<Rational> timeBase() {
        return timeBase;
    }

    /**
     * {@return the number of threads that encode a frame: {@code 1} for the calling thread only, more for at most
     * that many threads of the shared pool, {@code 0} for the shared pool without a cap; 0 by default}
     *
     * <p>The output does not depend on the thread count.
     */
    public int threads() {
        return threads;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DvEncoderOptions that && profile == that.profile && wide == that.wide
                && adaptiveDct == that.adaptiveDct && timeBase.equals(that.timeBase) && threads == that.threads;
    }

    @Override
    public int hashCode() {
        return Objects.hash(profile, wide, adaptiveDct, timeBase, threads);
    }

    @Override
    public String toString() {
        return "DvEncoderOptions[profile=" + profile + ", wide=" + wide + ", adaptiveDct=" + adaptiveDct
                + ", timeBase=" + timeBase + ", threads=" + threads + "]";
    }

    /**
     * Builds {@link DvEncoderOptions}. Values are validated by {@link #build()}.
     *
     * <p>Instances are not thread-safe.
     */
    public static final class Builder {
        private DvProfile profile = DvProfile.DV_525;

        private boolean wide;

        private boolean adaptiveDct = true;

        private Rational timeBase;

        private int threads;

        private Builder() {
        }

        private Builder(DvEncoderOptions options) {
            this.profile = options.profile;
            this.wide = options.wide;
            this.adaptiveDct = options.adaptiveDct;
            this.timeBase = options.timeBase.orElse(null);
            this.threads = options.threads;
        }

        /**
         * Sets the format of the frames; see {@link DvEncoderOptions#profile()}.
         *
         * @param profile the value
         * @return this builder
         */
        public Builder profile(DvProfile profile) {
            this.profile = Objects.requireNonNull(profile, "profile");
            return this;
        }

        /**
         * Sets whether the frames signal a 16:9 display; see {@link DvEncoderOptions#wide()}.
         *
         * @param wide the value
         * @return this builder
         */
        public Builder wide(boolean wide) {
            this.wide = wide;
            return this;
        }

        /**
         * Sets whether blocks may use the field DCT modes; see {@link DvEncoderOptions#adaptiveDct()}.
         *
         * @param adaptiveDct the value
         * @return this builder
         */
        public Builder adaptiveDct(boolean adaptiveDct) {
            this.adaptiveDct = adaptiveDct;
            return this;
        }

        /**
         * Sets the time base of the packet timestamps; see {@link DvEncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Counts the timestamps in frames of the profile; see {@link DvEncoderOptions#timeBase()}.
         *
         * @return this builder
         */
        public Builder automaticTimeBase() {
            this.timeBase = null;
            return this;
        }

        /**
         * Sets the number of threads that encode a frame; see {@link DvEncoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the thread count is negative or the time base is not positive
         */
        public DvEncoderOptions build() {
            Checks.nonNegative("thread count", threads);
            if (timeBase != null) {
                Checks.positive("time base numerator", timeBase.numerator());
                Checks.positive("time base denominator", timeBase.denominator());
            }
            return new DvEncoderOptions(this);
        }
    }
}
