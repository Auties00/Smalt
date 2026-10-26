package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagMode;

import java.util.Objects;
import java.util.Optional;

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
            DEFAULT_SEGMENT_MICROS, false, false, "master.m3u8", "manifest.mpd", TagMode.COPY, null, null);

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
    private final TagMode tagMode;

    /**
     * The tags of {@link TagMode#REPLACE} and {@link TagMode#MERGE}, or {@code null}.
     */
    private final MediaTags tags;

    /**
     * The encryption, or {@code null} for clear segments.
     */
    private final PackagingEncryption encryption;

    private PackagingOptions(boolean hls, boolean dash, SegmentFormat segmentFormat, long segmentMicros,
                             boolean byteRanges, boolean segmentIndex, String masterPlaylistName, String manifestName,
                             TagMode tagMode, MediaTags tags, PackagingEncryption encryption) {
        this.hls = hls;
        this.dash = dash;
        this.segmentFormat = segmentFormat;
        this.segmentMicros = segmentMicros;
        this.byteRanges = byteRanges;
        this.segmentIndex = segmentIndex;
        this.masterPlaylistName = masterPlaylistName;
        this.manifestName = manifestName;
        this.tagMode = tagMode;
        this.tags = tags;
        this.encryption = encryption;
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

    /**
     * {@return which tags, attached pictures, chapters and track tags each rendition's segments carry;
     * {@link TagMode#COPY}, the rendition's, by default}
     *
     * <p>The tags are on the terms of each rendition's input and mapped as {@link TagMode} describes. A CMAF
     * initialization segment (or single file) stores the tags, pictures and chapters, and the track tags of its
     * track; transport stream segments store the track names and some audio dispositions only.
     */
    public TagMode tagMode() {
        return tagMode;
    }

    /**
     * {@return the tags {@link TagMode#REPLACE} writes or {@link TagMode#MERGE} merges into each rendition's, or
     * empty for the other modes; empty by default}
     */
    public Optional<MediaTags> tags() {
        return Optional.ofNullable(tags);
    }

    /**
     * {@return the encryption of the segments, or empty for clear segments; empty by default}
     *
     * <p>The HLS media playlists then carry an {@code EXT-X-KEY} tag after the initialization segment's
     * {@code EXT-X-MAP} (RFC 8216 clause 4.3.2.4) with the method, the key URI, the initialization vector the method
     * signals and the key format, and the DASH manifest of {@code cbcs} or {@code cenc} CMAF segments declares the
     * scheme and the default key identifier in a {@code ContentProtection} element of the
     * {@code urn:mpeg:dash:mp4protection:2011} scheme in each adaptation set, followed by the W3C Clear Key one
     * ({@code urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e}). {@link PackagingEncryption} describes each method.
     */
    public Optional<PackagingEncryption> encryption() {
        return Optional.ofNullable(encryption);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PackagingOptions that && hls == that.hls && dash == that.dash
                && segmentFormat == that.segmentFormat && segmentMicros == that.segmentMicros
                && byteRanges == that.byteRanges && segmentIndex == that.segmentIndex
                && masterPlaylistName.equals(that.masterPlaylistName)
                && manifestName.equals(that.manifestName)
                && tagMode == that.tagMode
                && Objects.equals(tags, that.tags)
                && Objects.equals(encryption, that.encryption);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hls, dash, segmentFormat, segmentMicros, byteRanges, segmentIndex, masterPlaylistName,
                manifestName, tagMode, tags, encryption);
    }

    @Override
    public String toString() {
        return "PackagingOptions[hls=" + hls + ", dash=" + dash + ", segmentFormat=" + segmentFormat
                + ", segmentMicros=" + segmentMicros + ", byteRanges=" + byteRanges + ", segmentIndex=" + segmentIndex
                + ", masterPlaylistName="
                + masterPlaylistName + ", manifestName=" + manifestName + ", tagMode=" + tagMode
                + (tags == null ? "" : ", tags=" + tags) + (encryption == null ? "" : ", encryption=" + encryption) + "]";
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
        private TagMode tagMode;
        private MediaTags tags;
        private PackagingEncryption encryption;

        private Builder(PackagingOptions options) {
            this.hls = options.hls;
            this.dash = options.dash;
            this.segmentFormat = options.segmentFormat;
            this.segmentMicros = options.segmentMicros;
            this.byteRanges = options.byteRanges;
            this.segmentIndex = options.segmentIndex;
            this.masterPlaylistName = options.masterPlaylistName;
            this.manifestName = options.manifestName;
            this.tagMode = options.tagMode;
            this.tags = options.tags;
            this.encryption = options.encryption;
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
         * Writes each rendition's tags, pictures, chapters and track tags, setting the tag mode to
         * {@link TagMode#COPY}; see {@link PackagingOptions#tagMode()}.
         *
         * @return this builder
         */
        public Builder copyTags() {
            this.tagMode = TagMode.COPY;
            this.tags = null;
            return this;
        }

        /**
         * Writes no tag, picture, chapter or track tag, setting the tag mode to {@link TagMode#DROP}; see
         * {@link PackagingOptions#tagMode()}.
         *
         * @return this builder
         */
        public Builder noTags() {
            this.tagMode = TagMode.DROP;
            this.tags = null;
            return this;
        }

        /**
         * Writes the given tags instead of each rendition's, setting the tag mode to {@link TagMode#REPLACE}; see
         * {@link PackagingOptions#tags()}.
         *
         * @param tags the value, on the renditions' terms
         * @return this builder
         */
        public Builder tags(MediaTags tags) {
            this.tags = Objects.requireNonNull(tags, "tags");
            this.tagMode = TagMode.REPLACE;
            return this;
        }

        /**
         * Writes each rendition's tags updated with the given ones, setting the tag mode to {@link TagMode#MERGE};
         * see {@link PackagingOptions#tags()}.
         *
         * @param tags the value, on the renditions' terms
         * @return this builder
         */
        public Builder mergeTags(MediaTags tags) {
            this.tags = Objects.requireNonNull(tags, "tags");
            this.tagMode = TagMode.MERGE;
            return this;
        }

        /**
         * Sets the encryption of the segments; see {@link PackagingOptions#encryption()}.
         *
         * @param encryption the value
         * @return this builder
         */
        public Builder encryption(PackagingEncryption encryption) {
            this.encryption = Objects.requireNonNull(encryption, "encryption");
            return this;
        }

        /**
         * Writes clear segments, the default; see {@link PackagingOptions#encryption()}.
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
         * @throws IllegalArgumentException if no manifest is requested, DASH is requested with
         * transport stream segments, a segment index is requested without byte ranges or CMAF
         * segments, the segment duration is not positive, a name is empty or holds a path
         * separator, or the encryption does not fit the other settings: HLS without a key URI,
         * {@link PackagingEncryption.Method#AES_128} with DASH or a segment index,
         * {@link PackagingEncryption.Method#SAMPLE_AES_CTR} with transport stream segments, a
         * method that encrypts CMAF samples without a key identifier, or a key file named as the
         * master playlist or the manifest
         */
        public PackagingOptions build() {
            if (encryption != null) {
                var method = encryption.method();
                if (hls && encryption.keyUri().isEmpty()) {
                    throw new IllegalArgumentException("encrypted HLS playlists need the key URI");
                }
                if (method == PackagingEncryption.Method.AES_128 && (dash || segmentIndex)) {
                    throw new IllegalArgumentException("AES-128 segment encryption is HLS only and cannot be indexed "
                            + "by a single segment index");
                }
                if (method == PackagingEncryption.Method.SAMPLE_AES_CTR && segmentFormat != SegmentFormat.CMAF) {
                    throw new IllegalArgumentException("SAMPLE-AES-CTR encrypts CMAF segments only");
                }
                if (method != PackagingEncryption.Method.AES_128 && segmentFormat == SegmentFormat.CMAF
                        && encryption.keyId().isEmpty()) {
                    throw new IllegalArgumentException("Common Encryption of CMAF segments needs the key identifier");
                }
                var keyFile = encryption.keyFileName().orElse(null);
                if (keyFile != null && (hls && keyFile.equals(masterPlaylistName)
                        || dash && keyFile.equals(manifestName))) {
                    throw new IllegalArgumentException("the key file \"" + keyFile + "\" would replace a manifest");
                }
            }
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
                    checkName("manifest name", manifestName), tagMode, tags, encryption);
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
