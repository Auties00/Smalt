package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link AudioReader}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AudioReaderOptions {
    /**
     * The default number of samples per returned block.
     */
    public static final int DEFAULT_BLOCK_FRAMES = 4096;

    private static final AudioReaderOptions DEFAULTS =
            new AudioReaderOptions(0, null, MediaLimits.DEFAULT, DEFAULT_BLOCK_FRAMES);

    private final int sampleRate;
    private final ChannelLayout layout;
    private final MediaLimits limits;
    private final int blockFrames;

    private AudioReaderOptions(int sampleRate, ChannelLayout layout, MediaLimits limits, int blockFrames) {
        this.sampleRate = sampleRate;
        this.layout = layout;
        this.limits = limits;
        this.blockFrames = blockFrames;
    }

    /**
     * Returns options that keep the codec's rate and layout, with {@link MediaLimits#DEFAULT}
     * and blocks of {@value #DEFAULT_BLOCK_FRAMES} samples.
     *
     * @return the default options
     */
    public static AudioReaderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options converting to a rate and layout.
     *
     * @param sampleRate the output sampling rate in hertz, positive
     * @param layout the output channel layout
     * @return the options
     * @throws IllegalArgumentException if {@code sampleRate} is not positive
     */
    public static AudioReaderOptions of(int sampleRate, ChannelLayout layout) {
        return builder().sampleRate(sampleRate).layout(layout).build();
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
     * {@return the output sampling rate in hertz, positive, or empty to keep the codec's; empty
     * by default}
     */
    public OptionalInt sampleRate() {
        return sampleRate == 0 ? OptionalInt.empty() : OptionalInt.of(sampleRate);
    }

    /**
     * {@return the output channel layout, or empty to keep the codec's; empty by default}
     */
    public Optional<ChannelLayout> layout() {
        return Optional.ofNullable(layout);
    }

    /**
     * {@return the resource bounds enforced while demuxing and decoding; {@link MediaLimits#DEFAULT}
     * by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the largest number of samples per block returned by {@link AudioReader#read()},
     * positive; {@value #DEFAULT_BLOCK_FRAMES} by default}
     */
    public int blockFrames() {
        return blockFrames;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AudioReaderOptions that
                && sampleRate == that.sampleRate
                && Objects.equals(layout, that.layout)
                && limits.equals(that.limits)
                && blockFrames == that.blockFrames;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, limits, blockFrames);
    }

    @Override
    public String toString() {
        return "AudioReaderOptions[sampleRate=" + sampleRate + ", layout=" + layout + ", limits=" + limits
                + ", blockFrames=" + blockFrames + "]";
    }

    /**
     * Builds {@link AudioReaderOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private OptionalInt sampleRate;
        private ChannelLayout layout;
        private MediaLimits limits;
        private int blockFrames;

        private Builder(AudioReaderOptions options) {
            this.sampleRate = options.sampleRate();
            this.layout = options.layout;
            this.limits = options.limits;
            this.blockFrames = options.blockFrames;
        }

        /**
         * Sets the output sampling rate; see {@link AudioReaderOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = OptionalInt.of(sampleRate);
            return this;
        }

        /**
         * Keeps the codec's sampling rate; see {@link AudioReaderOptions#sampleRate()}.
         *
         * @return this builder
         */
        public Builder sourceSampleRate() {
            this.sampleRate = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the output channel layout; see {@link AudioReaderOptions#layout()}.
         *
         * @param layout the value
         * @return this builder
         */
        public Builder layout(ChannelLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            return this;
        }

        /**
         * Keeps the codec's channel layout; see {@link AudioReaderOptions#layout()}.
         *
         * @return this builder
         */
        public Builder sourceLayout() {
            this.layout = null;
            return this;
        }

        /**
         * Sets the resource bounds; see {@link AudioReaderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the largest number of samples per block; see {@link AudioReaderOptions#blockFrames()}.
         *
         * @param blockFrames the value
         * @return this builder
         */
        public Builder blockFrames(int blockFrames) {
            this.blockFrames = blockFrames;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sample rate or the block size is not positive
         */
        public AudioReaderOptions build() {
            var rate = sampleRate.isPresent() ? Checks.positive("sample rate", sampleRate.getAsInt()) : 0;
            Checks.positive("block size", blockFrames);
            return new AudioReaderOptions(rate, layout, limits, blockFrames);
        }
    }
}
