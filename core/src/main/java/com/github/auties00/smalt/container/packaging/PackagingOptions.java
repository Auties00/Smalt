package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of a {@link Packager}: the manifests written, the segment format, the segment
 * duration and the file layout. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #hls()}.
 */
public final class PackagingOptions {
    /**
     * Enumerates the segment formats.
     */
    public enum SegmentFormat {
        /**
         * MPEG-2 transport stream segments ({@code .ts}), audio and video of a rendition muxed
         * together. HLS only.
         */
        MPEG_TS,

        /**
         * CMAF segments: one fragmented MP4 track per stream, an initialization segment
         * ({@code _init.mp4}) and media segments ({@code .m4s}).
         */
        CMAF
    }

    /**
     * The default shortest segment, in microseconds: six seconds, the target duration the HLS
     * authoring guidelines recommend.
     */
    public static final long DEFAULT_SEGMENT_MICROS = 6_000_000;

    /**
     * The options of {@link #hls()}.
     */
    private static final PackagingOptions HLS = new PackagingOptions(true, false, SegmentFormat.MPEG_TS,
            DEFAULT_SEGMENT_MICROS, false, false, "master.m3u8", "manifest.mpd");

    /**
     * The options of {@link #hlsCmaf()}.
     */
    private static final PackagingOptions HLS_CMAF = HLS.toBuilder().segmentFormat(SegmentFormat.CMAF).build();

    /**
     * The options of {@link #dash()}.
     */
    private static final PackagingOptions DASH = HLS.toBuilder().hls(false).dash(true)
            .segmentFormat(SegmentFormat.CMAF).build();

    /**
     * The options of {@link #cmaf()}.
     */
    private static final PackagingOptions CMAF = HLS.toBuilder().dash(true).segmentFormat(SegmentFormat.CMAF).build();

    /**
     * The options of {@link #dashOnDemand()}.
     */
    private static final PackagingOptions DASH_ON_DEMAND = DASH.toBuilder().byteRanges(true).segmentIndex(true).build();

    private final boolean hls;
    private final boolean dash;
    private final SegmentFormat segmentFormat;
    private final long segmentMicros;
    private final boolean byteRanges;
    private final boolean segmentIndex;
    private final String masterPlaylistName;
    private final String manifestName;

    private PackagingOptions(boolean hls, boolean dash, SegmentFormat segmentFormat, long segmentMicros,
                             boolean byteRanges, boolean segmentIndex, String masterPlaylistName, String manifestName) {
        this.hls = hls;
        this.dash = dash;
        this.segmentFormat = segmentFormat;
        this.segmentMicros = segmentMicros;
        this.byteRanges = byteRanges;
        this.segmentIndex = segmentIndex;
        this.masterPlaylistName = masterPlaylistName;
        this.manifestName = manifestName;
    }

    /**
     * Returns HLS with MPEG transport stream segments, the most compatible layout.
     *
     * @return the options
     */
    public static PackagingOptions hls() {
        return HLS;
    }

    /**
     * Returns HLS with CMAF (fragmented MP4) segments.
     *
     * @return the options
     */
    public static PackagingOptions hlsCmaf() {
        return HLS_CMAF;
    }

    /**
     * Returns DASH with CMAF segments.
     *
     * @return the options
     */
    public static PackagingOptions dash() {
        return DASH;
    }

    /**
     * Returns HLS and DASH over one set of CMAF segments.
     *
     * @return the options
     */
    public static PackagingOptions cmaf() {
        return CMAF;
    }

    /**
     * Returns a DASH manifest of the on-demand profile (ISO/IEC 23009-1 clause 8.3): CMAF streams,
     * each one file opening with a single {@code sidx}, addressed by a {@code SegmentBase}.
     *
     * @return the options
     */
    public static PackagingOptions dashOnDemand() {
        return DASH_ON_DEMAND;
    }

    /**
     * Returns a builder initialized with {@link #hls()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(HLS);
    }

    /**
     * {@return a builder initialized with these options}
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return whether an HLS master playlist and media playlists are written; {@code true} in
     * {@link #hls()}}
     */
    public boolean hlsEnabled() {
        return hls;
    }

    /**
     * {@return whether a DASH manifest is written, which needs {@link SegmentFormat#CMAF} segments;
     * {@code false} in {@link #hls()}}
     */
    public boolean dashEnabled() {
        return dash;
    }

    /**
     * {@return the segment format; {@link SegmentFormat#MPEG_TS} in {@link #hls()}}
     */
    public SegmentFormat segmentFormat() {
        return segmentFormat;
    }

    /**
     * {@return the shortest segment, in microseconds; positive; {@link #DEFAULT_SEGMENT_MICROS} in every
     * preset}
     *
     * <p>A segment ends at the first key frame after it lasts this long, so segments may run longer
     * when key frames are sparse.
     */
    public long segmentMicros() {
        return segmentMicros;
    }

    /**
     * {@return whether each stream is written as one file whose segments the manifests address by byte
     * range; {@code false} in {@link #hls()}}
     */
    public boolean byteRanges() {
        return byteRanges;
    }

    /**
     * {@return whether each stream's file opens with a single segment index ({@code sidx}) indexing its
     * fragments, which needs {@link #byteRanges()} and CMAF segments; {@code false} in {@link #hls()}}
     *
     * <p>The DASH manifest addresses an indexed file with a {@code SegmentBase} (the ISO/IEC 23009-1
     * on-demand profile), while HLS playlists keep their byte ranges.
     */
    public boolean segmentIndex() {
        return segmentIndex;
    }

    /**
     * {@return the file name of the HLS master playlist, with no path separator; {@code master.m3u8} by
     * default}
     */
    public String masterPlaylistName() {
        return masterPlaylistName;
    }

    /**
     * {@return the file name of the DASH manifest, with no path separator; {@code manifest.mpd} by
     * default}
     */
    public String manifestName() {
        return manifestName;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PackagingOptions that && hls == that.hls && dash == that.dash
                && segmentFormat == that.segmentFormat && segmentMicros == that.segmentMicros
                && byteRanges == that.byteRanges && segmentIndex == that.segmentIndex
                && masterPlaylistName.equals(that.masterPlaylistName)
                && manifestName.equals(that.manifestName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hls, dash, segmentFormat, segmentMicros, byteRanges, segmentIndex, masterPlaylistName,
                manifestName);
    }

    @Override
    public String toString() {
        return "PackagingOptions[hls=" + hls + ", dash=" + dash + ", segmentFormat=" + segmentFormat
                + ", segmentMicros=" + segmentMicros + ", byteRanges=" + byteRanges + ", segmentIndex=" + segmentIndex
                + ", masterPlaylistName="
                + masterPlaylistName + ", manifestName=" + manifestName + "]";
    }

    /**
     * Assembles {@link PackagingOptions} setting by setting.
     *
     * <p>Setters reject {@code null} and store their value unchecked; {@link #build()} checks the
     * ranges, the file names and the combination of settings. Instances are not thread-safe.
     */
    public static final class Builder {
        private boolean hls;
        private boolean dash;
        private SegmentFormat segmentFormat;
        private long segmentMicros;
        private boolean byteRanges;
        private boolean segmentIndex;
        private String masterPlaylistName;
        private String manifestName;

        private Builder(PackagingOptions options) {
            this.hls = options.hls;
            this.dash = options.dash;
            this.segmentFormat = options.segmentFormat;
            this.segmentMicros = options.segmentMicros;
            this.byteRanges = options.byteRanges;
            this.segmentIndex = options.segmentIndex;
            this.masterPlaylistName = options.masterPlaylistName;
            this.manifestName = options.manifestName;
        }

        /**
         * Sets whether HLS playlists are written; see {@link PackagingOptions#hlsEnabled()}.
         *
         * @param enabled the value
         * @return this builder
         */
        public Builder hls(boolean enabled) {
            this.hls = enabled;
            return this;
        }

        /**
         * Sets whether a DASH manifest is written; see {@link PackagingOptions#dashEnabled()}.
         *
         * @param enabled the value
         * @return this builder
         */
        public Builder dash(boolean enabled) {
            this.dash = enabled;
            return this;
        }

        /**
         * Sets the segment format; see {@link PackagingOptions#segmentFormat()}.
         *
         * @param format the value
         * @return this builder
         */
        public Builder segmentFormat(SegmentFormat format) {
            this.segmentFormat = Objects.requireNonNull(format, "format");
            return this;
        }

        /**
         * Sets the shortest segment; see {@link PackagingOptions#segmentMicros()}.
         *
         * @param micros the value
         * @return this builder
         */
        public Builder segmentMicros(long micros) {
            this.segmentMicros = micros;
            return this;
        }

        /**
         * Sets whether streams are single files addressed by byte ranges; see
         * {@link PackagingOptions#byteRanges()}.
         *
         * @param enabled the value
         * @return this builder
         */
        public Builder byteRanges(boolean enabled) {
            this.byteRanges = enabled;
            return this;
        }

        /**
         * Sets whether each stream's file opens with a single segment index; see
         * {@link PackagingOptions#segmentIndex()}.
         *
         * @param enabled the value
         * @return this builder
         */
        public Builder segmentIndex(boolean enabled) {
            this.segmentIndex = enabled;
            return this;
        }

        /**
         * Sets the name of the HLS master playlist; see {@link PackagingOptions#masterPlaylistName()}.
         *
         * @param name the value
         * @return this builder
         */
        public Builder masterPlaylistName(String name) {
            this.masterPlaylistName = Objects.requireNonNull(name, "name");
            return this;
        }

        /**
         * Sets the name of the DASH manifest; see {@link PackagingOptions#manifestName()}.
         *
         * @param name the value
         * @return this builder
         */
        public Builder manifestName(String name) {
            this.manifestName = Objects.requireNonNull(name, "name");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if no manifest is requested, DASH is requested with
         * transport stream segments, a segment index is requested without byte ranges or CMAF
         * segments, the segment duration is not positive, or a name is empty or holds a path
         * separator
         */
        public PackagingOptions build() {
            if (!hls && !dash) {
                throw new IllegalArgumentException("neither HLS nor DASH is requested");
            }
            if (dash && segmentFormat != SegmentFormat.CMAF) {
                throw new IllegalArgumentException("DASH is requested with " + segmentFormat + " segments; it needs CMAF");
            }
            if (segmentIndex && (!byteRanges || segmentFormat != SegmentFormat.CMAF)) {
                throw new IllegalArgumentException("a segment index is requested without byte ranges and CMAF segments");
            }
            return new PackagingOptions(hls, dash, segmentFormat, Checks.positive("segment duration", segmentMicros),
                    byteRanges, segmentIndex, checkName("master playlist name", masterPlaylistName),
                    checkName("manifest name", manifestName));
        }

        /**
         * Validates a file name.
         *
         * @param setting the setting, for the message
         * @param name the file name to check
         * @return the name
         * @throws IllegalArgumentException if {@code name} is empty or holds a path separator
         */
        private static String checkName(String setting, String name) {
            Checks.notEmpty(setting, name);
            if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
                throw new IllegalArgumentException(setting + " \"" + name + "\" holds a path separator");
            }
            return name;
        }
    }
}
