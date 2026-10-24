package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.DataReferenceResolver;

import java.util.Objects;

/**
 * Holds the settings of a {@link Demuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class DemuxerOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final DemuxerOptions DEFAULTS = new DemuxerOptions(MediaLimits.DEFAULT, DataReferenceResolver.none());

    private final MediaLimits limits;
    private final DataReferenceResolver dataReferences;

    private DemuxerOptions(MediaLimits limits, DataReferenceResolver dataReferences) {
        this.limits = limits;
        this.dataReferences = dataReferences;
    }

    /**
     * Returns demuxing under {@link MediaLimits#defaults()}, without resolving data references.
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

    @Override
    public boolean equals(Object other) {
        return other instanceof DemuxerOptions that && limits.equals(that.limits)
                && dataReferences.equals(that.dataReferences);
    }

    @Override
    public int hashCode() {
        return limits.hashCode() * 31 + dataReferences.hashCode();
    }

    @Override
    public String toString() {
        return "DemuxerOptions[limits=" + limits + ", dataReferences=" + dataReferences + "]";
    }

    /**
     * Assembles {@link DemuxerOptions} setting by setting.
     *
     * <p>Setters reject {@code null}; no setting has a range to validate. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;
        private DataReferenceResolver dataReferences;

        private Builder(DemuxerOptions options) {
            this.limits = options.limits;
            this.dataReferences = options.dataReferences;
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
         * Builds the options.
         *
         * @return the options
         */
        public DemuxerOptions build() {
            return new DemuxerOptions(limits, dataReferences);
        }
    }
}
