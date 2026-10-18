package com.github.auties00.smalt.io;

import com.github.auties00.smalt.internal.check.Checks;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Holds the settings of a {@link MediaSource} over an {@link InputStream}: how it keeps the bytes it
 * has read. Instances are immutable and thread-safe; create them with {@link #builder()} or a preset
 * such as {@link #defaults()}.
 *
 * <p>A stream is read once, front to back, but parsers read their sources at any offset, so the
 * source keeps every byte it has taken from the stream until the parser declares it will never
 * read it again: in memory up to {@link #memoryBudget()} bytes, then in a spill file in
 * {@link #spillDirectory()}. Formats read front to back keep only a small window and never reach
 * the budget; a file whose index follows its media data, such as an MP4 file whose {@code moov}
 * box comes last, keeps the media data until the index is read. A spill file is created with
 * owner-only permissions, is encrypted with a key that exists only in memory, and is deleted by
 * the operating system even if the process dies.
 *
 * @see MediaSource#of(InputStream, long, StreamSourceOptions)
 * @see MediaSource#of(Supplier, long, StreamSourceOptions)
 */
public final class StreamSourceOptions {
    /**
     * The default memory budget: 16 MiB.
     *
     * <p>The budget holds a typical photo whole, the {@code moov} box of a long MP4 file (which
     * the MP4 demuxer also prefetches up to 16 MiB from a file), and many times the look-behind
     * window of every demuxer that reads front to back, while a server decoding many streams at
     * once keeps a bounded footprint per stream.
     */
    public static final long DEFAULT_MEMORY_BUDGET = 16L << 20;

    /**
     * The options of {@link #defaults()}.
     */
    private static final StreamSourceOptions DEFAULTS = new StreamSourceOptions(null, DEFAULT_MEMORY_BUDGET, false);

    /**
     * The options of {@link #memoryOnly()}.
     */
    private static final StreamSourceOptions MEMORY_ONLY = new StreamSourceOptions(null, DEFAULT_MEMORY_BUDGET, true);

    /**
     * The directory of spill files, or {@code null} for the {@code java.io.tmpdir} directory.
     */
    private final Path spillDirectory;

    private final long memoryBudget;
    private final boolean memoryOnly;

    private StreamSourceOptions(Path spillDirectory, long memoryBudget, boolean memoryOnly) {
        this.spillDirectory = spillDirectory;
        this.memoryBudget = memoryBudget;
        this.memoryOnly = memoryOnly;
    }

    /**
     * Returns the default options: a budget of {@link #DEFAULT_MEMORY_BUDGET} bytes, then spilling
     * to the {@code java.io.tmpdir} directory.
     *
     * @return the default options
     */
    public static StreamSourceOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options that never touch the disk: a source that would keep more than
     * {@link #DEFAULT_MEMORY_BUDGET} bytes fails with
     * {@link com.github.auties00.smalt.MediaException.LimitExceeded} instead of spilling.
     *
     * @return the memory-only options
     */
    public static StreamSourceOptions memoryOnly() {
        return MEMORY_ONLY;
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
     * {@return the directory spill files are created in; the {@code java.io.tmpdir} directory by
     * default}
     */
    public Path spillDirectory() {
        return spillDirectory != null ? spillDirectory : Path.of(System.getProperty("java.io.tmpdir"));
    }

    /**
     * {@return the number of bytes a source keeps in memory before it spills, or fails when it is
     * {@linkplain #isMemoryOnly() memory only}; positive; {@link #DEFAULT_MEMORY_BUDGET} by default}
     */
    public long memoryBudget() {
        return memoryBudget;
    }

    /**
     * {@return whether a source never touches the disk and fails with
     * {@link com.github.auties00.smalt.MediaException.LimitExceeded} when it would exceed its memory
     * budget; {@code false} by default}
     */
    public boolean isMemoryOnly() {
        return memoryOnly;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StreamSourceOptions that && Objects.equals(spillDirectory, that.spillDirectory)
                && memoryBudget == that.memoryBudget && memoryOnly == that.memoryOnly;
    }

    @Override
    public int hashCode() {
        return Objects.hash(spillDirectory, memoryBudget, memoryOnly);
    }

    @Override
    public String toString() {
        return "StreamSourceOptions[spillDirectory=" + spillDirectory() + ", memoryBudget=" + memoryBudget
                + ", memoryOnly=" + memoryOnly + "]";
    }

    /**
     * Assembles {@link StreamSourceOptions} setting by setting.
     *
     * <p>Setters reject {@code null} and store numbers unchecked; {@link #build()} checks the ranges.
     * Instances are not thread-safe.
     */
    public static final class Builder {
        private Path spillDirectory;
        private long memoryBudget;
        private boolean memoryOnly;

        private Builder(StreamSourceOptions options) {
            this.spillDirectory = options.spillDirectory;
            this.memoryBudget = options.memoryBudget;
            this.memoryOnly = options.memoryOnly;
        }

        /**
         * Sets the directory spill files are created in; see {@link StreamSourceOptions#spillDirectory()}.
         *
         * @param spillDirectory the value
         * @return this builder
         */
        public Builder spillDirectory(Path spillDirectory) {
            this.spillDirectory = Objects.requireNonNull(spillDirectory, "spillDirectory");
            return this;
        }

        /**
         * Sets the number of bytes kept in memory before spilling; see
         * {@link StreamSourceOptions#memoryBudget()}.
         *
         * @param memoryBudget the value
         * @return this builder
         */
        public Builder memoryBudget(long memoryBudget) {
            this.memoryBudget = memoryBudget;
            return this;
        }

        /**
         * Sets whether a source fails instead of spilling; see {@link StreamSourceOptions#isMemoryOnly()}.
         *
         * @param memoryOnly the value
         * @return this builder
         */
        public Builder memoryOnly(boolean memoryOnly) {
            this.memoryOnly = memoryOnly;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the memory budget is not positive
         */
        public StreamSourceOptions build() {
            return new StreamSourceOptions(spillDirectory, Checks.positive("memory budget", memoryBudget), memoryOnly);
        }
    }
}
