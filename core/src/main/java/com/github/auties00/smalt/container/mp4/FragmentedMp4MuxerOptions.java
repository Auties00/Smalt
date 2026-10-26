package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.internal.check.Checks;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a {@link FragmentedMp4Muxer}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class FragmentedMp4MuxerOptions {
    /**
     * The default fragment duration: two seconds.
     */
    public static final long DEFAULT_FRAGMENT_MICROS = 2_000_000;

    /**
     * The options of {@link #defaults()}.
     */
    private static final FragmentedMp4MuxerOptions DEFAULTS = new FragmentedMp4MuxerOptions(Brand.MP4, DEFAULT_FRAGMENT_MICROS,
            true, SegmentIndex.NONE, false, null, null);

    /**
     * The options of {@link #cmaf()}.
     */
    private static final FragmentedMp4MuxerOptions CMAF = new FragmentedMp4MuxerOptions(Brand.CMAF, DEFAULT_FRAGMENT_MICROS,
            true, SegmentIndex.NONE, false, null, null);

    /**
     * The options of {@link #dashOnDemand()}.
     */
    private static final FragmentedMp4MuxerOptions DASH_ON_DEMAND = new FragmentedMp4MuxerOptions(Brand.CMAF,
            DEFAULT_FRAGMENT_MICROS, true, SegmentIndex.SINGLE, false, null, null);

    /**
     * Enumerates the file types the muxer can declare.
     *
     * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Brand {
        /**
         * A fragmented MP4 file: major brand {@code iso6}, compatible with {@code iso6},
         * {@code isom} and {@code mp41}; any number of tracks.
         */
        MP4,

        /**
         * A CMAF track file: major brand {@code iso6}, compatible with {@code iso6}, {@code cmfc},
         * {@code isom} and {@code mp41}, segments typed {@code cmfs}; one track.
         */
        CMAF
    }

    /**
     * Enumerates the segment index layouts (ISO/IEC 14496-12 clause 8.16.3, {@code sidx}).
     *
     * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum SegmentIndex {
        /**
         * No segment index.
         */
        NONE,

        /**
         * A {@code sidx} before every fragment, referencing that fragment.
         */
        PER_FRAGMENT,

        /**
         * One {@code sidx} between the movie header and the first fragment, referencing every
         * fragment as a subsegment: the indexed single-file layout of the DASH on-demand profile
         * (ISO/IEC 23009-1 clause 8.3), addressed by a {@code SegmentBase} with an
         * {@code indexRange}. It needs a {@link com.github.auties00.smalt.io.MediaSink} output.
         */
        SINGLE
    }

    private final Brand brand;
    private final long fragmentMicros;
    private final boolean keyframeAligned;
    private final SegmentIndex segmentIndex;
    private final boolean randomAccessIndex;
    private final Path temporaryDirectory;

    /**
     * The encryption of the samples, or {@code null} for clear samples.
     */
    private final CommonEncryption encryption;

    private FragmentedMp4MuxerOptions(Brand brand, long fragmentMicros, boolean keyframeAligned, SegmentIndex segmentIndex,
                                      boolean randomAccessIndex, Path temporaryDirectory, CommonEncryption encryption) {
        this.brand = brand;
        this.fragmentMicros = fragmentMicros;
        this.keyframeAligned = keyframeAligned;
        this.segmentIndex = segmentIndex;
        this.randomAccessIndex = randomAccessIndex;
        this.temporaryDirectory = temporaryDirectory;
        this.encryption = encryption;
    }

    /**
     * Returns the options for a fragmented MP4 with two-second fragments aligned to key frames,
     * without {@code sidx} or {@code mfra}.
     *
     * @return the default options
     */
    public static FragmentedMp4MuxerOptions defaults() {
        return DEFAULTS;
    }

    /**
     * {@return the options for a CMAF track file with two-second fragments aligned to key frames}
     */
    public static FragmentedMp4MuxerOptions cmaf() {
        return CMAF;
    }

    /**
     * Returns the options for a DASH on-demand track file: a CMAF track file with two-second
     * fragments aligned to key frames, indexed by a single {@code sidx} after the movie header
     * ({@link SegmentIndex#SINGLE}).
     *
     * @return the options
     */
    public static FragmentedMp4MuxerOptions dashOnDemand() {
        return DASH_ON_DEMAND;
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
     * {@return a builder initialized with these options}
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the file type; {@link Brand#MP4} by default}
     *
     * <p>{@link Brand#MP4} declares any fragmented MP4, {@link Brand#CMAF} a CMAF track file
     * (ISO/IEC 23000-19), which carries a single track.
     */
    public Brand brand() {
        return brand;
    }

    /**
     * {@return the shortest fragment, in microseconds; positive; {@link #DEFAULT_FRAGMENT_MICROS} by
     * default}
     *
     * <p>A fragment ({@code moof} plus {@code mdat}) is cut once it lasts at least this long, at the
     * next point {@link #keyframeAligned()} allows.
     */
    public long fragmentMicros() {
        return fragmentMicros;
    }

    /**
     * {@return whether fragments of a file with video start at key frames of its first video track;
     * {@code true} by default}
     *
     * <p>Aligned fragments can each be decoded on their own, as DASH and HLS segments must.
     */
    public boolean keyframeAligned() {
        return keyframeAligned;
    }

    /**
     * {@return the segment index layout; {@link SegmentIndex#NONE} by default}
     *
     * <p>The single segment index of {@link SegmentIndex#SINGLE} follows the movie header, so the
     * muxer holds the fragments until {@link FragmentedMp4Muxer#finish()}: in memory up to a
     * threshold, then in a temporary file in {@link #temporaryDirectory()}.
     */
    public SegmentIndex segmentIndex() {
        return segmentIndex;
    }

    /**
     * {@return whether a random access index ({@code mfra}) closes a single-file output; {@code false}
     * by default}
     */
    public boolean randomAccessIndex() {
        return randomAccessIndex;
    }

    /**
     * {@return the directory of the fragment spool of a {@linkplain SegmentIndex#SINGLE single
     * segment index}, or empty for the default temporary-file directory; empty by default}
     */
    public Optional<Path> temporaryDirectory() {
        return Optional.ofNullable(temporaryDirectory);
    }

    /**
     * {@return the Common Encryption of the samples of the audio and video tracks, or empty for clear samples; empty
     * by default}
     *
     * <p>{@link CommonEncryption} describes what is encrypted and how the file signals it. Encrypting a video track
     * of a codec other than H.264 and HEVC raises {@link com.github.auties00.smalt.MediaException.Unsupported} when
     * the track is added.
     */
    public Optional<CommonEncryption> encryption() {
        return Optional.ofNullable(encryption);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FragmentedMp4MuxerOptions that
                && brand == that.brand
                && fragmentMicros == that.fragmentMicros
                && keyframeAligned == that.keyframeAligned
                && segmentIndex == that.segmentIndex
                && randomAccessIndex == that.randomAccessIndex
                && Objects.equals(temporaryDirectory, that.temporaryDirectory)
                && Objects.equals(encryption, that.encryption);
    }

    @Override
    public int hashCode() {
        return Objects.hash(brand, fragmentMicros, keyframeAligned, segmentIndex, randomAccessIndex, temporaryDirectory,
                encryption);
    }

    @Override
    public String toString() {
        return "FragmentedMp4MuxerOptions[brand=" + brand + ", fragmentMicros=" + fragmentMicros + ", keyframeAligned="
                + keyframeAligned + ", segmentIndex=" + segmentIndex + ", randomAccessIndex=" + randomAccessIndex
                + ", temporaryDirectory=" + temporaryDirectory + ", encryption=" + encryption + "]";
    }

    /**
     * Assembles {@link FragmentedMp4MuxerOptions} setting by setting.
     *
     * <p>Setters reject {@code null} unless documented otherwise and store numbers unchecked;
     * {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private Brand brand;
        private long fragmentMicros;
        private boolean keyframeAligned;
        private SegmentIndex segmentIndex;
        private boolean randomAccessIndex;
        private Path temporaryDirectory;
        private CommonEncryption encryption;

        private Builder(FragmentedMp4MuxerOptions options) {
            this.brand = options.brand;
            this.fragmentMicros = options.fragmentMicros;
            this.keyframeAligned = options.keyframeAligned;
            this.segmentIndex = options.segmentIndex;
            this.randomAccessIndex = options.randomAccessIndex;
            this.temporaryDirectory = options.temporaryDirectory;
            this.encryption = options.encryption;
        }

        /**
         * Sets the file type; see {@link FragmentedMp4MuxerOptions#brand()}.
         *
         * @param brand the value
         * @return this builder
         */
        public Builder brand(Brand brand) {
            this.brand = Objects.requireNonNull(brand, "brand");
            return this;
        }

        /**
         * Sets the shortest fragment; see {@link FragmentedMp4MuxerOptions#fragmentMicros()}.
         *
         * @param micros the value
         * @return this builder
         */
        public Builder fragmentMicros(long micros) {
            this.fragmentMicros = micros;
            return this;
        }

        /**
         * Sets whether fragments start at key frames; see {@link FragmentedMp4MuxerOptions#keyframeAligned()}.
         *
         * @param aligned the value
         * @return this builder
         */
        public Builder keyframeAligned(boolean aligned) {
            this.keyframeAligned = aligned;
            return this;
        }

        /**
         * Sets the segment index layout; see {@link FragmentedMp4MuxerOptions#segmentIndex()}.
         *
         * @param layout the value
         * @return this builder
         */
        public Builder segmentIndex(SegmentIndex layout) {
            this.segmentIndex = Objects.requireNonNull(layout, "layout");
            return this;
        }

        /**
         * Sets whether a {@code mfra} index closes the file; see
         * {@link FragmentedMp4MuxerOptions#randomAccessIndex()}.
         *
         * @param enabled the value
         * @return this builder
         */
        public Builder randomAccessIndex(boolean enabled) {
            this.randomAccessIndex = enabled;
            return this;
        }

        /**
         * Sets the directory of the fragment spool; see {@link FragmentedMp4MuxerOptions#temporaryDirectory()}.
         *
         * @param directory the value, or {@code null} for the default temporary-file directory
         * @return this builder
         */
        public Builder temporaryDirectory(Path directory) {
            this.temporaryDirectory = directory;
            return this;
        }

        /**
         * Sets the encryption of the samples; see {@link FragmentedMp4MuxerOptions#encryption()}.
         *
         * @param encryption the value
         * @return this builder
         */
        public Builder encryption(CommonEncryption encryption) {
            this.encryption = Objects.requireNonNull(encryption, "encryption");
            return this;
        }

        /**
         * Writes clear samples, the default; see {@link FragmentedMp4MuxerOptions#encryption()}.
         *
         * @return this builder
         */
        public Builder noEncryption() {
            this.encryption = null;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the fragment duration is not positive
         */
        public FragmentedMp4MuxerOptions build() {
            return new FragmentedMp4MuxerOptions(brand, Checks.positive("fragment duration", fragmentMicros),
                    keyframeAligned, segmentIndex, randomAccessIndex, temporaryDirectory, encryption);
        }
    }
}
