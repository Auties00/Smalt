package com.github.auties00.smalt.audio.hvxc;

import com.github.auties00.smalt.MediaLimits;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link HvxcDecoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class HvxcDecoderOptions {
    /**
     * The smallest speed change factor.
     */
    public static final float MIN_SPEED = 0.2f;

    /**
     * The largest speed change factor.
     */
    public static final float MAX_SPEED = 5.0f;

    /**
     * The smallest pitch change factor.
     */
    public static final float MIN_PITCH = 0.25f;

    /**
     * The largest pitch change factor.
     */
    public static final float MAX_PITCH = 4.0f;

    /**
     * The options of {@link #defaults()}.
     */
    private static final HvxcDecoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final boolean postFilter;

    private final boolean lowDelay;

    private final float speed;

    private final float pitch;

    private final int maxLayer;

    private HvxcDecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.postFilter = builder.postFilter;
        this.lowDelay = builder.lowDelay;
        this.speed = builder.speed;
        this.pitch = builder.pitch;
        this.maxLayer = builder.maxLayer;
    }

    /**
     * Returns the default options: decoding every layer at the original speed and pitch, in the normal delay mode,
     * with the postfilter, under {@link MediaLimits#defaults()}.
     *
     * @return the default options
     */
    public static HvxcDecoderOptions defaults() {
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
     * {@return the resource bounds the decoder enforces; {@link MediaLimits#defaults()} by default}
     *
     * <p>The decoder checks the access units the error protection tool reassembles against them; every other
     * allocation is fixed by the configuration and the speed change factor.
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return whether the decoder runs the postfilter and the post-processing over the synthesized speech;
     * {@code true} by default}
     *
     * <p>The postfilter (ISO/IEC 14496-3 Annex 2.B, informative) sharpens the formants of the speech and keeps its
     * level; the post-processing band-limits it to about 70 Hz to 3.4 kHz. The standard defines the decoded signal
     * without them, which is what conformance compares.
     */
    public boolean postFilter() {
        return postFilter;
    }

    /**
     * {@return whether the decoder runs in the low delay mode; {@code false} by default}
     *
     * <p>The low delay mode synthesizes each frame with 7.5 milliseconds of delay instead of the 10 of the normal
     * mode, at a small cost in quality. In either mode the output starts with the delay, which
     * {@link HvxcDecoder#delay()} gives; dropping that many samples lines the output up with the coded speech.
     */
    public boolean lowDelay() {
        return lowDelay;
    }

    /**
     * {@return the speed change factor, from {@value #MIN_SPEED} to {@value #MAX_SPEED}; 1 by default}
     *
     * <p>The decoder plays the speech this many times faster without changing its pitch: each 20 millisecond frame
     * decodes to 160 samples divided by the factor on average, by interpolating the parameters of neighboring
     * frames.
     */
    public float speedFactor() {
        return speed;
    }

    /**
     * {@return the pitch change factor, from {@value #MIN_PITCH} to {@value #MAX_PITCH}; 1 by default}
     *
     * <p>The decoder multiplies the fundamental frequency of voiced speech by this factor without changing its speed,
     * within the pitch range the coder represents.
     */
    public float pitchFactor() {
        return pitch;
    }

    /**
     * {@return the highest layer decoded, or empty to decode every layer the tracks carry; empty by default}
     *
     * <p>Layer 0 is the base layer; layer 1 is the enhancement layer that raises a 2 kbit/s object to 4 kbit/s.
     */
    public OptionalInt maxLayer() {
        return maxLayer < 0 ? OptionalInt.empty() : OptionalInt.of(maxLayer);
    }

    /**
     * Builds {@link HvxcDecoderOptions}. Every value is validated when its setter is called.
     */
    public static final class Builder {
        private MediaLimits limits = MediaLimits.defaults();

        private boolean postFilter = true;

        private boolean lowDelay;

        private float speed = 1.0f;

        private float pitch = 1.0f;

        private int maxLayer = -1;

        private Builder() {
        }

        private Builder(HvxcDecoderOptions options) {
            this.limits = options.limits;
            this.postFilter = options.postFilter;
            this.lowDelay = options.lowDelay;
            this.speed = options.speed;
            this.pitch = options.pitch;
            this.maxLayer = options.maxLayer;
        }

        /**
         * Sets the resource bounds; see {@link HvxcDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets whether the postfilter runs; see {@link HvxcDecoderOptions#postFilter()}.
         *
         * @param postFilter the value
         * @return this builder
         */
        public Builder postFilter(boolean postFilter) {
            this.postFilter = postFilter;
            return this;
        }

        /**
         * Sets whether the decoder runs in the low delay mode; see {@link HvxcDecoderOptions#lowDelay()}.
         *
         * @param lowDelay the value
         * @return this builder
         */
        public Builder lowDelay(boolean lowDelay) {
            this.lowDelay = lowDelay;
            return this;
        }

        /**
         * Sets the speed change factor; see {@link HvxcDecoderOptions#speedFactor()}.
         *
         * @param speed the value, from {@value HvxcDecoderOptions#MIN_SPEED} to {@value HvxcDecoderOptions#MAX_SPEED}
         * @return this builder
         * @throws IllegalArgumentException if {@code speed} is outside that range or not a number
         */
        public Builder speedFactor(float speed) {
            if (!(speed >= MIN_SPEED && speed <= MAX_SPEED)) {
                throw new IllegalArgumentException("speed factor " + speed + " is outside [" + MIN_SPEED + ", "
                        + MAX_SPEED + "]");
            }
            this.speed = speed;
            return this;
        }

        /**
         * Sets the pitch change factor; see {@link HvxcDecoderOptions#pitchFactor()}.
         *
         * @param pitch the value, from {@value HvxcDecoderOptions#MIN_PITCH} to {@value HvxcDecoderOptions#MAX_PITCH}
         * @return this builder
         * @throws IllegalArgumentException if {@code pitch} is outside that range or not a number
         */
        public Builder pitchFactor(float pitch) {
            if (!(pitch >= MIN_PITCH && pitch <= MAX_PITCH)) {
                throw new IllegalArgumentException("pitch factor " + pitch + " is outside [" + MIN_PITCH + ", "
                        + MAX_PITCH + "]");
            }
            this.pitch = pitch;
            return this;
        }

        /**
         * Sets the highest layer decoded; see {@link HvxcDecoderOptions#maxLayer()}.
         *
         * @param maxLayer the value, from 0
         * @return this builder
         * @throws IllegalArgumentException if {@code maxLayer} is negative
         */
        public Builder maxLayer(int maxLayer) {
            if (maxLayer < 0) {
                throw new IllegalArgumentException("maxLayer " + maxLayer + " is negative");
            }
            this.maxLayer = maxLayer;
            return this;
        }

        /**
         * Decodes every layer the tracks carry, which is the default; see {@link HvxcDecoderOptions#maxLayer()}.
         *
         * @return this builder
         */
        public Builder allLayers() {
            this.maxLayer = -1;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public HvxcDecoderOptions build() {
            return new HvxcDecoderOptions(this);
        }
    }
}
