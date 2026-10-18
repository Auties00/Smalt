package com.github.auties00.smalt.container.wav;

import java.util.Objects;

/**
 * Holds the settings of a {@link WavMuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class WavMuxerOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final WavMuxerOptions DEFAULTS = new WavMuxerOptions(Rf64.AUTO);

    /**
     * The options of {@link #rf64()}.
     */
    private static final WavMuxerOptions RF64 = new WavMuxerOptions(Rf64.ALWAYS);

    /**
     * Enumerates the policies for the 64-bit RF64 header.
     *
     * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Rf64 {
        /**
         * Writes RIFF with a {@code JUNK} chunk reserving the room of a {@code ds64} chunk, and
         * turns the header into RF64 when the file reaches 4 GiB.
         */
        AUTO,

        /**
         * Writes an RF64 header with a {@code ds64} chunk whatever the size; needs a seekable sink.
         */
        ALWAYS,

        /**
         * Writes plain RIFF without reserved room; data that would make the file reach 4 GiB is
         * rejected.
         */
        NEVER
    }

    private final Rf64 rf64;

    private WavMuxerOptions(Rf64 rf64) {
        this.rf64 = rf64;
    }

    /**
     * Returns the options that switch to RF64 only when needed.
     *
     * @return the default options
     */
    public static WavMuxerOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the options that always write RF64.
     *
     * @return the options
     */
    public static WavMuxerOptions rf64() {
        return RF64;
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
     * {@return how the muxer handles files of 4 GiB and more, which a RIFF header cannot describe;
     * {@link Rf64#AUTO} by default}
     *
     * <p>{@link Rf64#AUTO} reserves room for the 64-bit sizes of an RF64 header (EBU Tech 3306) and
     * switches to RF64 when the file needs it, {@link Rf64#ALWAYS} writes RF64 whatever the size, and
     * {@link Rf64#NEVER} writes plain RIFF and rejects data that would not fit. A streaming sink
     * always gets a plain RIFF header with unknown sizes.
     */
    public Rf64 rf64Policy() {
        return rf64;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WavMuxerOptions that && rf64 == that.rf64;
    }

    @Override
    public int hashCode() {
        return rf64.hashCode();
    }

    @Override
    public String toString() {
        return "WavMuxerOptions[rf64=" + rf64 + "]";
    }

    /**
     * Assembles {@link WavMuxerOptions} setting by setting.
     *
     * <p>Setters reject {@code null}; no setting has a range to validate. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private Rf64 rf64;

        private Builder(WavMuxerOptions options) {
            this.rf64 = options.rf64;
        }

        /**
         * Sets the RF64 policy; see {@link WavMuxerOptions#rf64Policy()}.
         *
         * @param rf64 the value
         * @return this builder
         */
        public Builder rf64Policy(Rf64 rf64) {
            this.rf64 = Objects.requireNonNull(rf64, "rf64");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public WavMuxerOptions build() {
            return new WavMuxerOptions(rf64);
        }
    }
}
