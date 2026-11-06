package com.github.auties00.smalt.audio.g726;

import java.util.Objects;

/**
 * Holds the settings of a {@link G726Encoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class G726EncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final G726EncoderOptions DEFAULTS = new Builder().build();

    private final int sampleRate;
    private final int codeSize;
    private final int packetSamples;

    /**
     * Constructs options from the settings of a builder.
     *
     * @param builder the builder, already checked
     */
    private G726EncoderOptions(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.codeSize = builder.codeSize;
        this.packetSamples = builder.packetSamples;
    }

    /**
     * Returns the options for 32 kbit/s (4-bit codes) at 8 kHz in packets of 20 ms.
     *
     * @return the default options
     */
    public static G726EncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the options for a code size at 8 kHz in packets of 20 ms.
     *
     * @param codeSize the code size; see {@link #codeSize()}
     * @return the options
     * @throws IllegalArgumentException if the code size is outside 2 to 5
     */
    public static G726EncoderOptions ofCodeSize(int codeSize) {
        return new Builder().codeSize(codeSize).build();
    }

    /**
     * {@return a new builder with the default settings}
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
        var builder = new Builder();
        builder.sampleRate = sampleRate;
        builder.codeSize = codeSize;
        builder.packetSamples = packetSamples;
        return builder;
    }

    /**
     * {@return the input sampling rate, in hertz; 8000 by default}
     *
     * <p>G.726 is specified at 8 kHz, where the code sizes give 16, 24, 32 and 40 kbit/s; other rates code the same
     * way at a proportional bitrate.
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the code size, the bits per sample, from 2 to 5; 4 (32 kbit/s at 8 kHz) by default}
     */
    public int codeSize() {
        return codeSize;
    }

    /**
     * {@return the samples of every packet but the last, a positive multiple of 8; 160 (20 ms at 8 kHz) by default}
     */
    public int packetSamples() {
        return packetSamples;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof G726EncoderOptions other && sampleRate == other.sampleRate
                && codeSize == other.codeSize && packetSamples == other.packetSamples;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, codeSize, packetSamples);
    }

    @Override
    public String toString() {
        return "G726EncoderOptions[sampleRate=" + sampleRate + ", codeSize=" + codeSize + ", packetSamples="
                + packetSamples + "]";
    }

    /**
     * Builds {@link G726EncoderOptions}. Every setter checks its argument.
     */
    public static final class Builder {
        private int sampleRate;
        private int codeSize;
        private int packetSamples;

        /**
         * Constructs a builder with the default settings.
         */
        private Builder() {
            this.sampleRate = 8000;
            this.codeSize = 4;
            this.packetSamples = 160;
        }

        /**
         * Sets the input sampling rate; see {@link G726EncoderOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         * @throws IllegalArgumentException if the rate is not positive
         */
        public Builder sampleRate(int sampleRate) {
            if (sampleRate <= 0) {
                throw new IllegalArgumentException("invalid sample rate " + sampleRate);
            }
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the code size; see {@link G726EncoderOptions#codeSize()}.
         *
         * @param codeSize the value
         * @return this builder
         * @throws IllegalArgumentException if the size is outside 2 to 5
         */
        public Builder codeSize(int codeSize) {
            if (codeSize < 2 || codeSize > 5) {
                throw new IllegalArgumentException("G.726 code size " + codeSize + " is outside 2..5");
            }
            this.codeSize = codeSize;
            return this;
        }

        /**
         * Sets the samples per packet; see {@link G726EncoderOptions#packetSamples()}.
         *
         * @param packetSamples the value
         * @return this builder
         * @throws IllegalArgumentException if the value is not a positive multiple of 8
         */
        public Builder packetSamples(int packetSamples) {
            if (packetSamples <= 0 || packetSamples % 8 != 0) {
                throw new IllegalArgumentException("G.726 packets of " + packetSamples
                        + " samples; a positive multiple of 8");
            }
            this.packetSamples = packetSamples;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public G726EncoderOptions build() {
            return new G726EncoderOptions(this);
        }
    }
}
