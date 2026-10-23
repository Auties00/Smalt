package com.github.auties00.smalt.audio.alac;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of an {@link AlacEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AlacEncoderOptions {
    /**
     * The frame length of {@link #defaults()}, the length every Apple Lossless decoder expects.
     */
    public static final int DEFAULT_FRAME_LENGTH = 4096;

    /**
     * The smallest frame length.
     */
    private static final int MIN_FRAME_LENGTH = 16;

    /**
     * The largest frame length.
     */
    private static final int MAX_FRAME_LENGTH = 65536;

    /**
     * The options of {@link #defaults()}.
     */
    private static final AlacEncoderOptions DEFAULTS = new Builder().build();

    private final int sampleRate;

    private final ChannelLayout layout;

    private final int bitsPerSample;

    private final int frameLength;

    private final boolean fast;

    private AlacEncoderOptions(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.layout = builder.layout;
        this.bitsPerSample = builder.bitsPerSample;
        this.frameLength = builder.frameLength;
        this.fast = builder.fast;
    }

    /**
     * Returns the options for 44.1 kHz 16-bit stereo input in frames of {@value #DEFAULT_FRAME_LENGTH}
     * samples, with the full parameter search.
     *
     * @return the default options
     */
    public static AlacEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the preset for encoding speed: the defaults with {@link #fast()} set.
     *
     * @return the options
     */
    public static AlacEncoderOptions forSpeed() {
        return new Builder().fast(true).build();
    }

    /**
     * Returns the defaults for an input format.
     *
     * @param sampleRate the sampling rate in hertz, positive
     * @param layout the channel layout, one to eight channels
     * @param bitsPerSample the sample size: 16, 20, 24 or 32
     * @return the options
     * @throws IllegalArgumentException if a value is out of range
     */
    public static AlacEncoderOptions of(int sampleRate, ChannelLayout layout, int bitsPerSample) {
        return new Builder().format(sampleRate, layout).bitsPerSample(bitsPerSample).build();
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
     * {@return the input sampling rate in hertz, positive; 44100 by default}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the input channel layout, one to eight channels; {@link ChannelLayout#STEREO} by default}
     *
     * <p>ALAC codes each channel count in one fixed speaker arrangement, the one {@link AlacDecoder}
     * reports. An input channel whose speaker belongs to that arrangement is coded as that speaker;
     * the other input channels take the remaining speakers of the arrangement in input order.
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the sample size of the stream in bits, 16, 20, 24 or 32; 16 by default}
     */
    public int bitsPerSample() {
        return bitsPerSample;
    }

    /**
     * {@return the number of samples per channel in every frame but the last, from 16 to 65536;
     * {@value #DEFAULT_FRAME_LENGTH} by default}
     *
     * @apiNote Every length in the range is valid ALAC, but Apple's format description recommends the
     * default for maximum compatibility.
     */
    public int frameLength() {
        return frameLength;
    }

    /**
     * {@return whether channel pairs are coded with fixed parameters instead of searching for the
     * best stereo matrixing and predictor order; {@code false} by default}
     *
     * <p>Fast coding applies to streams of two channels: it is the cheapest mode and gives larger
     * output. Streams of other channel counts always search.
     */
    public boolean fast() {
        return fast;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof AlacEncoderOptions that && sampleRate == that.sampleRate
                && layout.equals(that.layout) && bitsPerSample == that.bitsPerSample
                && frameLength == that.frameLength && fast == that.fast;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, bitsPerSample, frameLength, fast);
    }

    @Override
    public String toString() {
        return "AlacEncoderOptions[sampleRate=" + sampleRate + ", layout=" + layout + ", bitsPerSample="
                + bitsPerSample + ", frameLength=" + frameLength + ", fast=" + fast + "]";
    }

    /**
     * A builder of {@link AlacEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private int sampleRate;

        private ChannelLayout layout;

        private int bitsPerSample;

        private int frameLength;

        private boolean fast;

        private Builder() {
            this.sampleRate = 44_100;
            this.layout = ChannelLayout.STEREO;
            this.bitsPerSample = 16;
            this.frameLength = DEFAULT_FRAME_LENGTH;
        }

        private Builder(AlacEncoderOptions options) {
            this.sampleRate = options.sampleRate;
            this.layout = options.layout;
            this.bitsPerSample = options.bitsPerSample;
            this.frameLength = options.frameLength;
            this.fast = options.fast;
        }

        /**
         * Sets the input sampling rate; see {@link AlacEncoderOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the input channel layout; see {@link AlacEncoderOptions#layout()}.
         *
         * @param layout the value
         * @return this builder
         */
        public Builder layout(ChannelLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            return this;
        }

        /**
         * Sets the input sampling rate and channel layout; see {@link AlacEncoderOptions#sampleRate()}
         * and {@link AlacEncoderOptions#layout()}.
         *
         * @param sampleRate the sampling rate
         * @param layout the channel layout
         * @return this builder
         */
        public Builder format(int sampleRate, ChannelLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the sample size; see {@link AlacEncoderOptions#bitsPerSample()}.
         *
         * @param bitsPerSample the value
         * @return this builder
         */
        public Builder bitsPerSample(int bitsPerSample) {
            this.bitsPerSample = bitsPerSample;
            return this;
        }

        /**
         * Sets the frame length; see {@link AlacEncoderOptions#frameLength()}.
         *
         * @param frameLength the value
         * @return this builder
         */
        public Builder frameLength(int frameLength) {
            this.frameLength = frameLength;
            return this;
        }

        /**
         * Sets whether channel pairs are coded with fixed parameters; see {@link AlacEncoderOptions#fast()}.
         *
         * @param fast the value
         * @return this builder
         */
        public Builder fast(boolean fast) {
            this.fast = fast;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sampling rate is not positive, the layout has more
         * than eight channels, the sample size is not 16, 20, 24 or 32, or the frame length is outside
         * 16 to 65536
         */
        public AlacEncoderOptions build() {
            Checks.positive("sample rate", sampleRate);
            Checks.inRange("channel count", layout.channels(), 1, 8);
            if (bitsPerSample != 16 && bitsPerSample != 20 && bitsPerSample != 24 && bitsPerSample != 32) {
                throw new IllegalArgumentException("ALAC sample size " + bitsPerSample + " is not 16, 20, 24 or 32");
            }
            Checks.inRange("frame length", frameLength, MIN_FRAME_LENGTH, MAX_FRAME_LENGTH);
            return new AlacEncoderOptions(this);
        }
    }
}
