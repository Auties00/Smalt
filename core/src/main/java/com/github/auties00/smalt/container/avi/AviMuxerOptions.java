package com.github.auties00.smalt.container.avi;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of an {@link AviMuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AviMuxerOptions {
    /**
     * The smallest RIFF form size accepted.
     */
    public static final long MIN_RIFF_SIZE = 64 * 1024;

    /**
     * The largest RIFF form size accepted: a form's size field is 32 bits wide.
     */
    public static final long MAX_RIFF_SIZE = 0xFFFF_FFFEL;

    /**
     * The options of {@link #defaults()}.
     */
    private static final AviMuxerOptions DEFAULTS = new AviMuxerOptions(1L << 30, true);

    private final long riffSize;
    private final boolean legacyIndex;

    private AviMuxerOptions(long riffSize, boolean legacyIndex) {
        this.riffSize = riffSize;
        this.legacyIndex = legacyIndex;
    }

    /**
     * Returns the default options: RIFF forms of 1 GiB, as OpenDML recommends for compatibility,
     * and an {@code idx1} index for the first form.
     *
     * @return the default options
     */
    public static AviMuxerOptions defaults() {
        return DEFAULTS;
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
     * {@return the largest size of one RIFF form, in bytes, from {@link #MIN_RIFF_SIZE} to
     * {@link #MAX_RIFF_SIZE}; 1 GiB by default}
     *
     * <p>The first form is an {@code AVI } form and the following ones are OpenDML {@code AVIX}
     * forms. Every chunk is indexed by the OpenDML indexes (a super index {@code indx} per stream
     * pointing to a standard index {@code ix##} per stream and form), which lift the 2 GiB limit of
     * the original format.
     */
    public long riffSize() {
        return riffSize;
    }

    /**
     * {@return whether the chunks of the first form are also indexed by an {@code idx1} chunk, which
     * players without OpenDML support need; {@code true} by default}
     */
    public boolean legacyIndex() {
        return legacyIndex;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AviMuxerOptions that && riffSize == that.riffSize && legacyIndex == that.legacyIndex;
    }

    @Override
    public int hashCode() {
        return Objects.hash(riffSize, legacyIndex);
    }

    @Override
    public String toString() {
        return "AviMuxerOptions[riffSize=" + riffSize + ", legacyIndex=" + legacyIndex + "]";
    }

    /**
     * Assembles {@link AviMuxerOptions} setting by setting.
     *
     * <p>Setters store their value unchecked; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private long riffSize;
        private boolean legacyIndex;

        private Builder(AviMuxerOptions options) {
            this.riffSize = options.riffSize;
            this.legacyIndex = options.legacyIndex;
        }

        /**
         * Sets the largest size of one RIFF form; see {@link AviMuxerOptions#riffSize()}.
         *
         * @param riffSize the value
         * @return this builder
         */
        public Builder riffSize(long riffSize) {
            this.riffSize = riffSize;
            return this;
        }

        /**
         * Sets whether the first form carries an {@code idx1} index; see {@link AviMuxerOptions#legacyIndex()}.
         *
         * @param legacyIndex the value
         * @return this builder
         */
        public Builder legacyIndex(boolean legacyIndex) {
            this.legacyIndex = legacyIndex;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the RIFF size is outside {@link #MIN_RIFF_SIZE} to
         * {@link #MAX_RIFF_SIZE}
         */
        public AviMuxerOptions build() {
            return new AviMuxerOptions(Checks.inRange("RIFF size", riffSize, MIN_RIFF_SIZE, MAX_RIFF_SIZE),
                    legacyIndex);
        }
    }
}
