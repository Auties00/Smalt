package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.track.Rational;

import java.nio.charset.Charset;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a {@link Demuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class DemuxerOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final DemuxerOptions DEFAULTS = new DemuxerOptions(MediaLimits.DEFAULT, DataReferenceResolver.none(),
            null, null, DecryptionKeys.none());

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

    private DemuxerOptions(MediaLimits limits, DataReferenceResolver dataReferences, Charset charset,
                           Rational subtitleFrameRate, DecryptionKeys decryptionKeys) {
        this.limits = limits;
        this.dataReferences = dataReferences;
        this.charset = charset;
        this.subtitleFrameRate = subtitleFrameRate;
        this.decryptionKeys = decryptionKeys;
    }

    /**
     * Returns demuxing under {@link MediaLimits#defaults()}, without resolving data references and with
     * detected text encodings.
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

    @Override
    public boolean equals(Object other) {
        return other instanceof DemuxerOptions that && limits.equals(that.limits)
                && dataReferences.equals(that.dataReferences)
                && Objects.equals(charset, that.charset) && Objects.equals(subtitleFrameRate, that.subtitleFrameRate)
                && decryptionKeys.equals(that.decryptionKeys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, dataReferences, charset, subtitleFrameRate, decryptionKeys);
    }

    @Override
    public String toString() {
        return "DemuxerOptions[limits=" + limits + ", dataReferences=" + dataReferences + ", charset=" + charset()
                + ", subtitleFrameRate=" + subtitleFrameRate() + ", decryptionKeys=" + decryptionKeys + "]";
    }

    /**
     * Assembles {@link DemuxerOptions} setting by setting.
     *
     * <p>Setters reject {@code null}; {@link #build()} checks the frame rate. Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;
        private DataReferenceResolver dataReferences;
        private Charset charset;
        private Rational subtitleFrameRate;
        private DecryptionKeys decryptionKeys;

        private Builder(DemuxerOptions options) {
            this.limits = options.limits;
            this.dataReferences = options.dataReferences;
            this.charset = options.charset;
            this.subtitleFrameRate = options.subtitleFrameRate;
            this.decryptionKeys = options.decryptionKeys;
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
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the subtitle frame rate is not positive
         */
        public DemuxerOptions build() {
            if (subtitleFrameRate != null && (subtitleFrameRate.numerator() <= 0 || subtitleFrameRate.denominator() <= 0)) {
                throw new IllegalArgumentException("the subtitle frame rate must be positive, not " + subtitleFrameRate);
            }
            return new DemuxerOptions(limits, dataReferences, charset, subtitleFrameRate, decryptionKeys);
        }
    }
}
