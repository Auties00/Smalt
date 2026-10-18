package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.internal.check.Checks;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of an {@link Mp4Muxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class Mp4MuxerOptions {
    /**
     * The default chunk interleaving: half a second.
     */
    public static final long DEFAULT_INTERLEAVE_MICROS = 500_000;

    /**
     * The options of {@link #mp4()}.
     */
    private static final Mp4MuxerOptions MP4 = new Mp4MuxerOptions(Brand.MP4, true, null, DEFAULT_INTERLEAVE_MICROS);

    /**
     * The options of {@link #m4a()}.
     */
    private static final Mp4MuxerOptions M4A = new Mp4MuxerOptions(Brand.M4A, true, null, DEFAULT_INTERLEAVE_MICROS);

    /**
     * The options of {@link #quickTime()}.
     */
    private static final Mp4MuxerOptions QUICKTIME = new Mp4MuxerOptions(Brand.QUICKTIME, true, null,
            DEFAULT_INTERLEAVE_MICROS);

    /**
     * Enumerates the file types the muxer can declare in its {@code ftyp} box.
     *
     * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Brand {
        /**
         * A generic MP4 file: major brand {@code isom}, compatible with {@code isom}, {@code iso2},
         * {@code avc1} and {@code mp41}; carries audio and video.
         */
        MP4,

        /**
         * An iTunes-style audio file: major brand {@code M4A }, compatible with {@code M4A },
         * {@code isom}, {@code iso2} and {@code mp42}; carries audio only.
         */
        M4A,

        /**
         * A QuickTime movie: major brand {@code qt  }, compatible with {@code qt  }; carries audio,
         * video and the professional codecs of QuickTime, ProRes and DNxHD, whose sample entries
         * get the QuickTime {@code colr} ({@code nclc}), {@code fiel} and {@code pasp} boxes.
         */
        QUICKTIME
    }

    private final Brand brand;
    private final boolean fastStart;
    private final Path temporaryDirectory;
    private final long interleaveMicros;

    private Mp4MuxerOptions(Brand brand, boolean fastStart, Path temporaryDirectory, long interleaveMicros) {
        this.brand = brand;
        this.fastStart = fastStart;
        this.temporaryDirectory = temporaryDirectory;
        this.interleaveMicros = interleaveMicros;
    }

    /**
     * Returns the options for a fast-start MP4 with the default interleaving, the same as
     * {@link #mp4()}.
     *
     * @return the default options
     */
    public static Mp4MuxerOptions defaults() {
        return MP4;
    }

    /**
     * {@return the options for a fast-start MP4 with the default interleaving}
     */
    public static Mp4MuxerOptions mp4() {
        return MP4;
    }

    /**
     * {@return the options for a fast-start M4A with the default interleaving}
     */
    public static Mp4MuxerOptions m4a() {
        return M4A;
    }

    /**
     * Returns the options for a fast-start QuickTime movie ({@code .mov}) with the default
     * interleaving.
     *
     * @return the options
     */
    public static Mp4MuxerOptions quickTime() {
        return QUICKTIME;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(MP4);
    }

    /**
     * {@return a builder initialized with these options}
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the file type the output declares; {@link Brand#MP4} by default}
     */
    public Brand brand() {
        return brand;
    }

    /**
     * {@return whether the {@code moov} box precedes the media data; {@code true} by default}
     *
     * <p>A fast-start file can start playing before a progressive download completes. The muxer then
     * spools the media data (in memory up to a threshold, then in a temporary file in
     * {@link #temporaryDirectory()}) until {@link Mp4Muxer#finish()}, and it writes its output
     * strictly in order, so any {@link com.github.auties00.smalt.io.MediaSink} works. Without fast
     * start the media data is written as packets arrive and the header is patched at the end, which
     * needs a seekable sink.
     */
    public boolean fastStart() {
        return fastStart;
    }

    /**
     * {@return the directory of the fast-start spool file, or empty for the default temporary-file
     * directory; empty by default}
     */
    public Optional<Path> temporaryDirectory() {
        return Optional.ofNullable(temporaryDirectory);
    }

    /**
     * {@return the longest stretch of one track's samples stored contiguously (one chunk), in
     * microseconds; positive; {@link #DEFAULT_INTERLEAVE_MICROS} by default}
     */
    public long interleaveMicros() {
        return interleaveMicros;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Mp4MuxerOptions that
                && brand == that.brand
                && fastStart == that.fastStart
                && Objects.equals(temporaryDirectory, that.temporaryDirectory)
                && interleaveMicros == that.interleaveMicros;
    }

    @Override
    public int hashCode() {
        return Objects.hash(brand, fastStart, temporaryDirectory, interleaveMicros);
    }

    @Override
    public String toString() {
        return "Mp4MuxerOptions[brand=" + brand + ", fastStart=" + fastStart + ", temporaryDirectory="
                + temporaryDirectory + ", interleaveMicros=" + interleaveMicros + "]";
    }

    /**
     * Assembles {@link Mp4MuxerOptions} setting by setting.
     *
     * <p>Setters reject {@code null} unless documented otherwise and store numbers unchecked;
     * {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private Brand brand;
        private boolean fastStart;
        private Path temporaryDirectory;
        private long interleaveMicros;

        private Builder(Mp4MuxerOptions options) {
            this.brand = options.brand;
            this.fastStart = options.fastStart;
            this.temporaryDirectory = options.temporaryDirectory;
            this.interleaveMicros = options.interleaveMicros;
        }

        /**
         * Sets the file type; see {@link Mp4MuxerOptions#brand()}.
         *
         * @param brand the value
         * @return this builder
         */
        public Builder brand(Brand brand) {
            this.brand = Objects.requireNonNull(brand, "brand");
            return this;
        }

        /**
         * Sets whether the {@code moov} precedes the media data; see {@link Mp4MuxerOptions#fastStart()}.
         *
         * @param fastStart the value
         * @return this builder
         */
        public Builder fastStart(boolean fastStart) {
            this.fastStart = fastStart;
            return this;
        }

        /**
         * Sets the spool directory; see {@link Mp4MuxerOptions#temporaryDirectory()}.
         *
         * @param directory the value, or {@code null} for the default temporary-file directory
         * @return this builder
         */
        public Builder temporaryDirectory(Path directory) {
            this.temporaryDirectory = directory;
            return this;
        }

        /**
         * Sets the chunk duration; see {@link Mp4MuxerOptions#interleaveMicros()}.
         *
         * @param micros the value
         * @return this builder
         */
        public Builder interleaveMicros(long micros) {
            this.interleaveMicros = micros;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the chunk duration is not positive
         */
        public Mp4MuxerOptions build() {
            return new Mp4MuxerOptions(brand, fastStart, temporaryDirectory,
                    Checks.positive("interleave duration", interleaveMicros));
        }
    }
}
