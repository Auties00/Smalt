package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.MediaLimits;

import java.util.Objects;

/**
 * Holds the settings of {@link TagEditor#read(com.github.auties00.smalt.io.MediaSource, TagReadOptions)}.
 * Instances are immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
 */
public final class TagReadOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final TagReadOptions DEFAULTS = new TagReadOptions(MediaLimits.DEFAULT);

    private final MediaLimits limits;

    private TagReadOptions(MediaLimits limits) {
        this.limits = limits;
    }

    /**
     * Returns reading under {@link MediaLimits#defaults()}.
     *
     * @return the default options
     */
    public static TagReadOptions defaults() {
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
     * {@return the resource bounds enforced while reading the metadata; {@link MediaLimits#defaults()}
     * by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TagReadOptions that && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return limits.hashCode();
    }

    @Override
    public String toString() {
        return "TagReadOptions[limits=" + limits + "]";
    }

    /**
     * Assembles {@link TagReadOptions} setting by setting.
     *
     * <p>Setters reject {@code null}; no setting has a range to validate. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;

        private Builder(TagReadOptions options) {
            this.limits = options.limits;
        }

        /**
         * Sets the resource bounds; see {@link TagReadOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public TagReadOptions build() {
            return new TagReadOptions(limits);
        }
    }
}
