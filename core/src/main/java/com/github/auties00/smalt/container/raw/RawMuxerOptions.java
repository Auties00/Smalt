package com.github.auties00.smalt.container.raw;

import java.util.Objects;

/**
 * Holds the settings of a {@link RawMuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class RawMuxerOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final RawMuxerOptions DEFAULTS = new RawMuxerOptions(true);

    private final boolean xingTag;

    private RawMuxerOptions(boolean xingTag) {
        this.xingTag = xingTag;
    }

    /**
     * Returns options that write a Xing tag frame before MP3 streams on seekable sinks.
     *
     * @return the default options
     */
    public static RawMuxerOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder(DEFAULTS);
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
     * {@return whether MP3 streams on a seekable sink get a Xing tag frame; {@code true} by default}
     *
     * <p>The muxer reserves the tag frame before the first audio frame and fills it in when the
     * stream ends. The tag (a silent Layer III frame whose main data starts with {@code Xing}, or
     * {@code Info} when every frame has the same bitrate) holds the number of audio frames, the number
     * of bytes and a 100-entry seek table, so readers know the exact duration of a variable bitrate
     * stream and can seek in it. A streaming sink gets the frames alone.
     */
    public boolean xingTag() {
        return xingTag;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof RawMuxerOptions that && xingTag == that.xingTag;
    }

    @Override
    public int hashCode() {
        return Objects.hash(xingTag);
    }

    @Override
    public String toString() {
        return "RawMuxerOptions[xingTag=" + xingTag + "]";
    }

    /**
     * Assembles {@link RawMuxerOptions} setting by setting.
     *
     * <p>No setting has a range to validate. Instances are not thread-safe.
     */
    public static final class Builder {
        private boolean xingTag;

        private Builder(RawMuxerOptions options) {
            this.xingTag = options.xingTag;
        }

        /**
         * Sets whether MP3 streams get a Xing tag frame; see {@link RawMuxerOptions#xingTag()}.
         *
         * @param xingTag the value
         * @return this builder
         */
        public Builder xingTag(boolean xingTag) {
            this.xingTag = xingTag;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public RawMuxerOptions build() {
            return new RawMuxerOptions(xingTag);
        }
    }
}
