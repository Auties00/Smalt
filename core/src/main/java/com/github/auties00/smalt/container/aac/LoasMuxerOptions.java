package com.github.auties00.smalt.container.aac;

/**
 * Holds the settings of a {@link LoasMuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class LoasMuxerOptions {
    /**
     * The default number of frames from one {@code StreamMuxConfig} to the next.
     */
    public static final int DEFAULT_CONFIG_INTERVAL = 20;

    /**
     * The options of {@link #defaults()}.
     */
    private static final LoasMuxerOptions DEFAULTS = new LoasMuxerOptions(DEFAULT_CONFIG_INTERVAL);

    private final int configInterval;

    private LoasMuxerOptions(int configInterval) {
        this.configInterval = configInterval;
    }

    /**
     * Returns options that repeat the configuration every {@value #DEFAULT_CONFIG_INTERVAL} frames.
     *
     * @return the default options
     */
    public static LoasMuxerOptions defaults() {
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
     * {@return the number of frames from one frame that carries the {@code StreamMuxConfig} to the next, from 1 to
     * 65535; {@value #DEFAULT_CONFIG_INTERVAL} by default}
     *
     * <p>The first frame and the frame of every configuration change carry the configuration whatever the interval,
     * and every other frame refers back to it ({@code useSameStreamMux}). A decoder that joins the stream in the
     * middle starts at the next frame that carries it, so a shorter interval lets it start sooner at the cost of a
     * few bytes per frame that carries it; 1 makes every frame decodable on its own.
     */
    public int configInterval() {
        return configInterval;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof LoasMuxerOptions that && configInterval == that.configInterval;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(configInterval);
    }

    @Override
    public String toString() {
        return "LoasMuxerOptions[configInterval=" + configInterval + "]";
    }

    /**
     * Assembles {@link LoasMuxerOptions} setting by setting.
     *
     * <p>Each setter validates its value. Instances are not thread-safe.
     */
    public static final class Builder {
        private int configInterval;

        private Builder(LoasMuxerOptions options) {
            this.configInterval = options.configInterval;
        }

        /**
         * Sets the number of frames from one frame that carries the configuration to the next; see
         * {@link LoasMuxerOptions#configInterval()}.
         *
         * @param frames the value
         * @return this builder
         * @throws IllegalArgumentException if {@code frames} is not between 1 and 65535
         */
        public Builder configInterval(int frames) {
            if (frames < 1 || frames > 65_535) {
                throw new IllegalArgumentException("configuration interval " + frames + " is not between 1 and 65535");
            }
            this.configInterval = frames;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public LoasMuxerOptions build() {
            return new LoasMuxerOptions(configInterval);
        }
    }
}
