package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a {@link MatroskaMuxer}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class MatroskaMuxerOptions {
    /**
     * The default longest cluster: five seconds.
     */
    public static final long DEFAULT_MAX_CLUSTER_MICROS = 5_000_000;

    /**
     * The default largest cluster: 5 MiB.
     */
    public static final int DEFAULT_MAX_CLUSTER_BYTES = 5 << 20;

    /**
     * The default timestamp scale: one millisecond.
     */
    public static final long DEFAULT_TIMESTAMP_SCALE_NANOS = 1_000_000;

    /**
     * The default writing application.
     */
    private static final String DEFAULT_WRITING_APP = "Smalt";

    /**
     * The options of {@link #defaults()}.
     */
    private static final MatroskaMuxerOptions MATROSKA = new MatroskaMuxerOptions(MatroskaDocType.MATROSKA, false,
            DEFAULT_MAX_CLUSTER_MICROS, DEFAULT_MAX_CLUSTER_BYTES, DEFAULT_TIMESTAMP_SCALE_NANOS, true, null, DEFAULT_WRITING_APP);

    /**
     * The options of {@link #webm()}.
     */
    private static final MatroskaMuxerOptions WEBM = MATROSKA.toBuilder().docType(MatroskaDocType.WEBM).build();

    /**
     * The options of {@link #liveWebm()}.
     */
    private static final MatroskaMuxerOptions LIVE_WEBM = WEBM.toBuilder().live(true).build();

    private final MatroskaDocType docType;
    private final boolean live;
    private final long maxClusterMicros;
    private final int maxClusterBytes;
    private final long timestampScaleNanos;
    private final boolean cues;
    private final String title;
    private final String writingApp;

    private MatroskaMuxerOptions(MatroskaDocType docType, boolean live, long maxClusterMicros, int maxClusterBytes,
                                 long timestampScaleNanos, boolean cues, String title, String writingApp) {
        this.docType = docType;
        this.writingApp = writingApp;
        this.live = live;
        this.maxClusterMicros = maxClusterMicros;
        this.maxClusterBytes = maxClusterBytes;
        this.timestampScaleNanos = timestampScaleNanos;
        this.cues = cues;
        this.title = title;
    }

    /**
     * Returns the options for a Matroska file with five-second clusters, the same as
     * {@link #matroska()}.
     *
     * @return the default options
     */
    public static MatroskaMuxerOptions defaults() {
        return MATROSKA;
    }

    /**
     * Returns the options for a Matroska file with five-second clusters.
     *
     * @return the options
     */
    public static MatroskaMuxerOptions matroska() {
        return MATROSKA;
    }

    /**
     * Returns the options for a WebM file with five-second clusters.
     *
     * @return the options
     */
    public static MatroskaMuxerOptions webm() {
        return WEBM;
    }

    /**
     * Returns the options for a live WebM stream: unknown-size clusters written block by block.
     *
     * @return the options
     */
    public static MatroskaMuxerOptions liveWebm() {
        return LIVE_WEBM;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(MATROSKA);
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
     * {@return the EBML document type of the output, which decides the codecs it accepts;
     * {@link MatroskaDocType#MATROSKA} by default}
     *
     * <p>Matroska carries any codec the muxer supports; its WebM profile carries only VP8, VP9, AV1,
     * Opus and Vorbis.
     */
    public MatroskaDocType docType() {
        return docType;
    }

    /**
     * {@return whether the output is written for live streaming; {@code false} by default}
     *
     * <p>Live output writes every block as soon as the interleaving allows, inside clusters and a
     * segment of unknown size, and never seeks: it suits streaming to a player or a socket. Otherwise
     * each cluster is assembled in memory and written with its size, and on a seekable sink the muxer
     * also writes {@code Cues}, the segment {@code Duration} and a complete {@code SeekHead} when it
     * finishes.
     */
    public boolean live() {
        return live;
    }

    /**
     * {@return the longest cluster, in microseconds; positive; {@link #DEFAULT_MAX_CLUSTER_MICROS} by
     * default}
     *
     * <p>A cluster ends when it reaches this duration or {@link #maxClusterBytes()}, when a block
     * timestamp no longer fits its 16-bit offset from the cluster timestamp, and, in a file with
     * video, before a video key frame.
     */
    public long maxClusterMicros() {
        return maxClusterMicros;
    }

    /**
     * {@return the largest cluster, in bytes; at least 4096; {@link #DEFAULT_MAX_CLUSTER_BYTES} by
     * default}
     */
    public int maxClusterBytes() {
        return maxClusterBytes;
    }

    /**
     * {@return the duration of one segment timestamp tick, in nanoseconds; from 1 to 1000000000;
     * {@link #DEFAULT_TIMESTAMP_SCALE_NANOS} (one millisecond) by default}
     */
    public long timestampScaleNanos() {
        return timestampScaleNanos;
    }

    /**
     * {@return whether {@code Cues} are written when the sink is seekable and the output is not live;
     * {@code true} by default}
     */
    public boolean cues() {
        return cues;
    }

    /**
     * {@return the segment title, or empty when none is written; empty by default}
     */
    public Optional<String> title() {
        return Optional.ofNullable(title);
    }

    /**
     * {@return the writing application stored in the segment information; {@code "Smalt"} by default}
     */
    public String writingApp() {
        return writingApp;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MatroskaMuxerOptions that
                && docType == that.docType
                && live == that.live
                && maxClusterMicros == that.maxClusterMicros
                && maxClusterBytes == that.maxClusterBytes
                && timestampScaleNanos == that.timestampScaleNanos
                && cues == that.cues
                && Objects.equals(title, that.title)
                && writingApp.equals(that.writingApp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(docType, live, maxClusterMicros, maxClusterBytes, timestampScaleNanos, cues, title, writingApp);
    }

    @Override
    public String toString() {
        return "MatroskaMuxerOptions[docType=" + docType + ", live=" + live + ", maxClusterMicros=" + maxClusterMicros
                + ", maxClusterBytes=" + maxClusterBytes + ", timestampScaleNanos=" + timestampScaleNanos + ", cues=" + cues
                + ", title=" + title + ", writingApp=" + writingApp + "]";
    }

    /**
     * Assembles {@link MatroskaMuxerOptions} setting by setting.
     *
     * <p>Setters reject {@code null} unless documented otherwise and store numbers unchecked;
     * {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private MatroskaDocType docType;
        private boolean live;
        private long maxClusterMicros;
        private int maxClusterBytes;
        private long timestampScaleNanos;
        private boolean cues;
        private String title;
        private String writingApp;

        private Builder(MatroskaMuxerOptions options) {
            this.docType = options.docType;
            this.live = options.live;
            this.maxClusterMicros = options.maxClusterMicros;
            this.maxClusterBytes = options.maxClusterBytes;
            this.timestampScaleNanos = options.timestampScaleNanos;
            this.cues = options.cues;
            this.title = options.title;
            this.writingApp = options.writingApp;
        }

        /**
         * Sets the document type; see {@link MatroskaMuxerOptions#docType()}.
         *
         * @param docType the value
         * @return this builder
         */
        public Builder docType(MatroskaDocType docType) {
            this.docType = Objects.requireNonNull(docType, "docType");
            return this;
        }

        /**
         * Sets whether to write for live streaming; see {@link MatroskaMuxerOptions#live()}.
         *
         * @param live the value
         * @return this builder
         */
        public Builder live(boolean live) {
            this.live = live;
            return this;
        }

        /**
         * Sets the longest cluster; see {@link MatroskaMuxerOptions#maxClusterMicros()}.
         *
         * @param micros the value
         * @return this builder
         */
        public Builder maxClusterMicros(long micros) {
            this.maxClusterMicros = micros;
            return this;
        }

        /**
         * Sets the largest cluster; see {@link MatroskaMuxerOptions#maxClusterBytes()}.
         *
         * @param bytes the value
         * @return this builder
         */
        public Builder maxClusterBytes(int bytes) {
            this.maxClusterBytes = bytes;
            return this;
        }

        /**
         * Sets the timestamp scale; see {@link MatroskaMuxerOptions#timestampScaleNanos()}.
         *
         * @param nanos the value
         * @return this builder
         */
        public Builder timestampScaleNanos(long nanos) {
            this.timestampScaleNanos = nanos;
            return this;
        }

        /**
         * Sets whether to write {@code Cues}; see {@link MatroskaMuxerOptions#cues()}.
         *
         * @param cues the value
         * @return this builder
         */
        public Builder cues(boolean cues) {
            this.cues = cues;
            return this;
        }

        /**
         * Sets the segment title; see {@link MatroskaMuxerOptions#title()}.
         *
         * @param title the value, or {@code null} for none
         * @return this builder
         */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /**
         * Sets the writing application; see {@link MatroskaMuxerOptions#writingApp()}.
         *
         * @param writingApp the value
         * @return this builder
         */
        public Builder writingApp(String writingApp) {
            this.writingApp = Objects.requireNonNull(writingApp, "writingApp");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the longest cluster is not positive, the largest cluster
         * is under 4096 bytes, or the timestamp scale is outside 1 to 1000000000 nanoseconds
         */
        public MatroskaMuxerOptions build() {
            Checks.positive("cluster duration", maxClusterMicros);
            Checks.inRange("cluster size", maxClusterBytes, 4096, Integer.MAX_VALUE);
            Checks.inRange("timestamp scale", timestampScaleNanos, 1, 1_000_000_000L);
            return new MatroskaMuxerOptions(docType, live, maxClusterMicros, maxClusterBytes, timestampScaleNanos, cues, title,
                    writingApp);
        }
    }
}
