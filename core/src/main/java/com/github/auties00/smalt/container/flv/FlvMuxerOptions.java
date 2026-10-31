package com.github.auties00.smalt.container.flv;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of an {@link FlvMuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or the preset {@link #defaults()}.
 */
public final class FlvMuxerOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final FlvMuxerOptions DEFAULTS = new FlvMuxerOptions(false, null);

    private final boolean keyframeIndex;
    private final Path temporaryDirectory;

    private FlvMuxerOptions(boolean keyframeIndex, Path temporaryDirectory) {
        this.keyframeIndex = keyframeIndex;
        this.temporaryDirectory = temporaryDirectory;
    }

    /**
     * Returns the default options: no key frame index, and the default temporary-file directory.
     *
     * @return the default options
     */
    public static FlvMuxerOptions defaults() {
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
     * {@return whether {@code onMetaData} carries a {@code keyframes} index of the times and tag positions of the
     * key frames of the first video track; {@code false} by default}
     *
     * <p>Players seek through the index without reading the file. Since the index precedes the media, the muxer then
     * holds the tags (in memory up to 16 MiB, then in a temporary file in {@link #temporaryDirectory()}) until
     * {@link FlvMuxer#finish()}, and writes its output strictly in order, so any
     * {@link com.github.auties00.smalt.io.MediaSink} gets the final duration and size too. Without the index the
     * tags are written as packets arrive and the duration and size of {@code onMetaData} are patched at the end,
     * which needs a seekable sink.
     */
    public boolean keyframeIndex() {
        return keyframeIndex;
    }

    /**
     * {@return the directory of the temporary file of {@link #keyframeIndex()}, or empty for the default
     * temporary-file directory; empty by default}
     */
    public Optional<Path> temporaryDirectory() {
        return Optional.ofNullable(temporaryDirectory);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FlvMuxerOptions that && keyframeIndex == that.keyframeIndex
                && Objects.equals(temporaryDirectory, that.temporaryDirectory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyframeIndex, temporaryDirectory);
    }

    @Override
    public String toString() {
        return "FlvMuxerOptions[keyframeIndex=" + keyframeIndex + ", temporaryDirectory=" + temporaryDirectory + "]";
    }

    /**
     * Assembles {@link FlvMuxerOptions} setting by setting. Instances are not thread-safe.
     */
    public static final class Builder {
        private boolean keyframeIndex;
        private Path temporaryDirectory;

        private Builder(FlvMuxerOptions options) {
            this.keyframeIndex = options.keyframeIndex;
            this.temporaryDirectory = options.temporaryDirectory;
        }

        /**
         * Sets whether {@code onMetaData} carries a key frame index; see {@link FlvMuxerOptions#keyframeIndex()}.
         *
         * @param keyframeIndex the value
         * @return this builder
         */
        public Builder keyframeIndex(boolean keyframeIndex) {
            this.keyframeIndex = keyframeIndex;
            return this;
        }

        /**
         * Sets the directory of the temporary file; see {@link FlvMuxerOptions#temporaryDirectory()}.
         *
         * @param directory the directory of the temporary file
         * @return this builder
         */
        public Builder temporaryDirectory(Path directory) {
            this.temporaryDirectory = Objects.requireNonNull(directory, "directory");
            return this;
        }

        /**
         * Restores the default temporary-file directory; see {@link FlvMuxerOptions#temporaryDirectory()}.
         *
         * @return this builder
         */
        public Builder defaultTemporaryDirectory() {
            this.temporaryDirectory = null;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public FlvMuxerOptions build() {
            return new FlvMuxerOptions(keyframeIndex, temporaryDirectory);
        }
    }
}
