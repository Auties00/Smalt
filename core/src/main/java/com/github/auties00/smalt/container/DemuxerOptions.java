package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.track.Rational;

import java.nio.charset.Charset;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link Demuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class DemuxerOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final DemuxerOptions DEFAULTS = new DemuxerOptions(MediaLimits.DEFAULT, DataReferenceResolver.none(),
            null, null, DecryptionKeys.none(), -1, true);

    private final MediaLimits limits;
    private final DataReferenceResolver dataReferences;

    /**
     * The text encoding, or {@code null} to detect it.
     */
    private final Charset charset;

    /**
     * The MicroDVD frame rate, or {@code null} for the file's or the default.
     */
    private final Rational subtitleFrameRate;
    private final DecryptionKeys decryptionKeys;

    /**
     * The index of the edition to play, or {@code -1} for the default one.
     */
    private final int edition;
    private final boolean virtualTimeline;

    private DemuxerOptions(MediaLimits limits, DataReferenceResolver dataReferences, Charset charset,
                           Rational subtitleFrameRate, DecryptionKeys decryptionKeys, int edition,
                           boolean virtualTimeline) {
        this.limits = limits;
        this.dataReferences = dataReferences;
        this.charset = charset;
        this.subtitleFrameRate = subtitleFrameRate;
        this.decryptionKeys = decryptionKeys;
        this.edition = edition;
        this.virtualTimeline = virtualTimeline;
    }

    /**
     * Returns demuxing under {@link MediaLimits#defaults()}, without resolving data references, with detected text
     * encodings, and with the virtual timeline of the default edition.
     *
     * @return the default options
     */
    public static DemuxerOptions defaults() {
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
     * {@return the resource bounds the demuxer enforces; {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the resolver of the data references that name other files; {@link DataReferenceResolver#none()}
     * by default}
     *
     * <p>An ISO base media or QuickTime file may store the samples of a track in other files, which its data
     * reference box names (a reference movie, for example). The MP4 demuxer asks this resolver for those files;
     * the samples of a reference it does not resolve raise
     * {@link com.github.auties00.smalt.MediaException.Unsupported}. Other demuxers do not use it.
     */
    public DataReferenceResolver dataReferences() {
        return dataReferences;
    }

    /**
     * {@return the character encoding of text subtitle files that do not start with a byte order mark, or
     * empty to detect it; empty by default}
     *
     * <p>Detection recognizes UTF-8, UTF-16 without a byte order mark, Shift_JIS, EUC-JP, EUC-KR, GBK, Big5,
     * Windows-1251 and KOI8-R, and reads anything else as Windows-1252. A byte order mark always wins, and
     * so does the encoding a TTML document's XML declaration or a SAMI file's {@code charset} names.
     */
    public Optional<Charset> charset() {
        return Optional.ofNullable(charset);
    }

    /**
     * {@return the frame rate MicroDVD frame numbers count in, or empty for the rate the file declares or,
     * when it declares none, 24000/1001; empty by default}
     *
     * <p>A MicroDVD file declares its rate in a first cue at frame 1 whose text is the rate; this setting,
     * when present, takes precedence over it.
     */
    public Optional<Rational> subtitleFrameRate() {
        return Optional.ofNullable(subtitleFrameRate);
    }

    /**
     * {@return the keys that decrypt protected tracks; {@link DecryptionKeys#none()} by default}
     *
     * <p>The MP4 demuxer decrypts the samples of tracks protected with the Common Encryption schemes
     * {@code cenc}, {@code cbc1}, {@code cens} and {@code cbcs} (ISO/IEC 23001-7), and the Matroska demuxer the
     * frames of WebM encrypted tracks, with the key these keys give for the key identifier of each sample; their
     * tracks and packets are then those of the clear file. With {@link DecryptionKeys#none()} a protected track
     * raises {@link com.github.auties00.smalt.MediaException.Unsupported} when the demuxer is created, and with
     * other keys a sample whose key is not known raises it when it is read. Other demuxers do not use them.
     */
    public DecryptionKeys decryptionKeys() {
        return decryptionKeys;
    }

    /**
     * {@return the index of the chapter edition a demuxer plays, among the editions it reports with
     * {@link #virtualTimeline()} off, or empty for the default edition; empty by default}
     *
     * <p>The Matroska demuxer plays the edition at this index, which it then reports as the default edition, and
     * plays the default edition ({@link ChapterEdition#defaultIndex}) when the index is empty or past its last
     * edition. Other demuxers store a single edition and do not use it.
     */
    public OptionalInt edition() {
        return edition < 0 ? OptionalInt.empty() : OptionalInt.of(edition);
    }

    /**
     * {@return whether a demuxer presents the virtual timeline of the edition it plays rather than the
     * presentation as it is stored; {@code true} by default}
     *
     * <p>A Matroska edition of ordered chapters, and a chain of hard-linked segments, define a virtual timeline
     * (RFC 9559 sections 17 and 20.1.3): the parts of the segments the chapters name, in their order, or the linked
     * segments one after another. With this setting the Matroska demuxer returns the packets of that timeline,
     * reading the other segments it names from the {@link #dataReferences()} resolver; without it, the packets of
     * its own segment as they are stored. Other demuxers do not use it.
     */
    public boolean virtualTimeline() {
        return virtualTimeline;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DemuxerOptions that && limits.equals(that.limits)
                && dataReferences.equals(that.dataReferences)
                && Objects.equals(charset, that.charset) && Objects.equals(subtitleFrameRate, that.subtitleFrameRate)
                && decryptionKeys.equals(that.decryptionKeys) && edition == that.edition
                && virtualTimeline == that.virtualTimeline;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, dataReferences, charset, subtitleFrameRate, decryptionKeys, edition,
                virtualTimeline);
    }

    @Override
    public String toString() {
        return "DemuxerOptions[limits=" + limits + ", dataReferences=" + dataReferences + ", charset=" + charset()
                + ", subtitleFrameRate=" + subtitleFrameRate() + ", decryptionKeys=" + decryptionKeys
                + ", edition=" + edition() + ", virtualTimeline=" + virtualTimeline + "]";
    }

    /**
     * Assembles {@link DemuxerOptions} setting by setting.
     *
     * <p>Setters reject {@code null} and a negative edition index; {@link #build()} checks the frame rate.
     * Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;
        private DataReferenceResolver dataReferences;
        private Charset charset;
        private Rational subtitleFrameRate;
        private DecryptionKeys decryptionKeys;
        private int edition;
        private boolean virtualTimeline;

        private Builder(DemuxerOptions options) {
            this.limits = options.limits;
            this.dataReferences = options.dataReferences;
            this.charset = options.charset;
            this.subtitleFrameRate = options.subtitleFrameRate;
            this.decryptionKeys = options.decryptionKeys;
            this.edition = options.edition;
            this.virtualTimeline = options.virtualTimeline;
        }

        /**
         * Sets the resource bounds; see {@link DemuxerOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the resolver of the data references that name other files; see
         * {@link DemuxerOptions#dataReferences()}.
         *
         * @param dataReferences the resolver
         * @return this builder
         */
        public Builder dataReferences(DataReferenceResolver dataReferences) {
            this.dataReferences = Objects.requireNonNull(dataReferences, "dataReferences");
            return this;
        }

        /**
         * Resolves no data reference, the default; see {@link DemuxerOptions#dataReferences()}.
         *
         * @return this builder
         */
        public Builder noDataReferences() {
            this.dataReferences = DataReferenceResolver.none();
            return this;
        }

        /**
         * Sets the text encoding of subtitle files; see {@link DemuxerOptions#charset()}.
         *
         * @param charset the value
         * @return this builder
         */
        public Builder charset(Charset charset) {
            this.charset = Objects.requireNonNull(charset, "charset");
            return this;
        }

        /**
         * Detects the text encoding of subtitle files; see {@link DemuxerOptions#charset()}.
         *
         * @return this builder
         */
        public Builder automaticCharset() {
            this.charset = null;
            return this;
        }

        /**
         * Sets the MicroDVD frame rate; see {@link DemuxerOptions#subtitleFrameRate()}.
         *
         * @param frameRate the value, in frames per second
         * @return this builder
         */
        public Builder subtitleFrameRate(Rational frameRate) {
            this.subtitleFrameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Uses the frame rate a MicroDVD file declares, or 24000/1001; see
         * {@link DemuxerOptions#subtitleFrameRate()}.
         *
         * @return this builder
         */
        public Builder automaticSubtitleFrameRate() {
            this.subtitleFrameRate = null;
            return this;
        }

        /**
         * Sets the keys that decrypt protected tracks; see {@link DemuxerOptions#decryptionKeys()}.
         *
         * @param keys the value
         * @return this builder
         */
        public Builder decryptionKeys(DecryptionKeys keys) {
            this.decryptionKeys = Objects.requireNonNull(keys, "keys");
            return this;
        }

        /**
         * Decrypts nothing, the default; see {@link DemuxerOptions#decryptionKeys()}.
         *
         * @return this builder
         */
        public Builder noDecryptionKeys() {
            this.decryptionKeys = DecryptionKeys.none();
            return this;
        }

        /**
         * Sets the chapter edition to play; see {@link DemuxerOptions#edition()}.
         *
         * @param index the value, zero or more
         * @return this builder
         * @throws IllegalArgumentException if {@code index} is negative
         */
        public Builder edition(int index) {
            if (index < 0) {
                throw new IllegalArgumentException("negative edition index " + index);
            }
            this.edition = index;
            return this;
        }

        /**
         * Plays the default chapter edition, the default; see {@link DemuxerOptions#edition()}.
         *
         * @return this builder
         */
        public Builder defaultEdition() {
            this.edition = -1;
            return this;
        }

        /**
         * Sets whether the virtual timeline of the played edition is presented; see
         * {@link DemuxerOptions#virtualTimeline()}.
         *
         * @param virtualTimeline the value
         * @return this builder
         */
        public Builder virtualTimeline(boolean virtualTimeline) {
            this.virtualTimeline = virtualTimeline;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the subtitle frame rate is not positive
         */
        public DemuxerOptions build() {
            if (subtitleFrameRate != null && (subtitleFrameRate.numerator() <= 0 || subtitleFrameRate.denominator() <= 0)) {
                throw new IllegalArgumentException("the subtitle frame rate must be positive, not " + subtitleFrameRate);
            }
            return new DemuxerOptions(limits, dataReferences, charset, subtitleFrameRate, decryptionKeys, edition,
                    virtualTimeline);
        }
    }
}
