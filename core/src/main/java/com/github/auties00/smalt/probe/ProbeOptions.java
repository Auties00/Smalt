package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.probe.ProbeAccess;

import java.util.Objects;

/**
 * Holds the settings of {@link com.github.auties00.smalt.Media#probe}. Instances are immutable and
 * thread-safe; create them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class ProbeOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final ProbeOptions DEFAULTS = new ProbeOptions(MediaLimits.DEFAULT, true, true);

    /**
     * The options of {@link #headersOnly()}.
     */
    private static final ProbeOptions HEADERS_ONLY = new ProbeOptions(MediaLimits.DEFAULT, false, false);

    static {
        ProbeAccess.register(Prober::probe);
    }

    private final MediaLimits limits;
    private final boolean attachedPictures;
    private final boolean readFirstFrames;

    private ProbeOptions(MediaLimits limits, boolean attachedPictures, boolean readFirstFrames) {
        this.limits = limits;
        this.attachedPictures = attachedPictures;
        this.readFirstFrames = readFirstFrames;
    }

    /**
     * Returns probing under {@link MediaLimits#defaults()}, keeping embedded pictures and reading the
     * first key frame of a video track that has no configuration record.
     *
     * @return the default options
     */
    public static ProbeOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns probing that reads headers only: no embedded picture is returned and no packet is
     * read, so a VP8, VP9 or AV1 track stored without a configuration record reports no profile
     * or bit depth.
     *
     * @return the options
     */
    public static ProbeOptions headersOnly() {
        return HEADERS_ONLY;
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
     * {@return the resource bounds the demuxer enforces while reading the headers;
     * {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return whether embedded pictures, such as cover art, are returned in
     * {@link MediaInfo#attachedPictures()}; {@code true} by default}
     */
    public boolean attachedPictures() {
        return attachedPictures;
    }

    /**
     * {@return whether the first packets of a video track stored without a configuration record (VP8
     * and VP9 in IVF or WebM, AV1 in IVF, MPEG-1 and MPEG-2 video in MP4, Matroska and AVI) are read to describe the
     * stream from its first key frame; {@code true} by default}
     */
    public boolean readFirstFrames() {
        return readFirstFrames;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProbeOptions that && limits.equals(that.limits)
                && attachedPictures == that.attachedPictures && readFirstFrames == that.readFirstFrames;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, attachedPictures, readFirstFrames);
    }

    @Override
    public String toString() {
        return "ProbeOptions[limits=" + limits + ", attachedPictures=" + attachedPictures + ", readFirstFrames="
                + readFirstFrames + "]";
    }

    /**
     * Assembles {@link ProbeOptions} setting by setting.
     *
     * <p>Setters reject {@code null}; no setting has a range to validate. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;
        private boolean attachedPictures;
        private boolean readFirstFrames;

        private Builder(ProbeOptions options) {
            this.limits = options.limits;
            this.attachedPictures = options.attachedPictures;
            this.readFirstFrames = options.readFirstFrames;
        }

        /**
         * Sets the resource bounds; see {@link ProbeOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets whether embedded pictures are returned; see {@link ProbeOptions#attachedPictures()}.
         *
         * @param attachedPictures the value
         * @return this builder
         */
        public Builder attachedPictures(boolean attachedPictures) {
            this.attachedPictures = attachedPictures;
            return this;
        }

        /**
         * Sets whether the first key frame of a video track without a configuration record may be
         * read; see {@link ProbeOptions#readFirstFrames()}.
         *
         * @param readFirstFrames the value
         * @return this builder
         */
        public Builder readFirstFrames(boolean readFirstFrames) {
            this.readFirstFrames = readFirstFrames;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public ProbeOptions build() {
            return new ProbeOptions(limits, attachedPictures, readFirstFrames);
        }
    }
}
