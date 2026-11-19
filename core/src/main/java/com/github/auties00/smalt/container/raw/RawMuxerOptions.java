package com.github.auties00.smalt.container.raw;

import java.util.Objects;

/**
 * Holds the settings of a {@link RawMuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class RawMuxerOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final RawMuxerOptions DEFAULTS = new RawMuxerOptions(true, false);

    private final boolean xingTag;

    private final boolean av1AnnexB;

    private RawMuxerOptions(boolean xingTag, boolean av1AnnexB) {
        this.xingTag = xingTag;
        this.av1AnnexB = av1AnnexB;
    }

    /**
     * Returns options that write a Xing tag frame before MP3 streams on seekable sinks and AV1 in the
     * low-overhead format.
     *
     * @return the default options
     */
    public static RawMuxerOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder(DEFAULTS);
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return whether MP3 streams on a seekable sink get a Xing tag frame; {@code true} by default}
     *
     * <p>The muxer reserves the tag frame before the first audio frame and fills it in when the
     * stream ends. The tag (a silent Layer III frame whose main data starts with {@code Xing}, or
     * {@code Info} when every frame has the same bitrate) holds the number of audio frames, the number
     * of bytes and a 100-entry seek table, so readers know the exact duration of a variable bitrate
     * stream and can seek in it. A streaming sink gets the frames alone.
     *
     * <p>When the track carries gapless information, the tag also holds a LAME tag with the encoder
     * delay and padding: the delay is the track's {@link com.github.auties00.smalt.track.AudioTrack#skipSamples()
     * skipSamples} less the 529 samples of decoder delay of the synthesis filterbank, and the padding makes the
     * kept length equal the track's duration or, for a track declared without one (an encoder's output), the
     * sum of the packet durations less {@code skipSamples}. A reader that drops {@code delay + 529} decoded
     * samples and keeps {@code frames * samplesPerFrame - delay - padding} then reproduces the encoder's
     * input exactly. The LAME tag is left out when {@code skipSamples} is below 529, when the length is
     * unknown, or when the delay or the padding exceeds 4095 samples. Both values are exact when the track's
     * time base counts single samples.
     */
    public boolean xingTag() {
        return xingTag;
    }

    /**
     * Returns whether AV1 is written in the length-delimited format of the AV1 specification's Annex B.
     *
     * <p>Each temporal unit then becomes a {@code temporal_unit_size} followed by frame units, a frame unit ending
     * after each frame header or frame OBU, and every OBU an {@code obu_length} followed by the OBU without its
     * {@code obu_size} field; temporal delimiters are kept at the start of each temporal unit. Otherwise AV1 is
     * written in the low-overhead format of section 5.
     *
     * @return {@code true} for Annex B; {@code false} by default
     */
    public boolean av1AnnexB() {
        return av1AnnexB;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof RawMuxerOptions that && xingTag == that.xingTag && av1AnnexB == that.av1AnnexB;
    }

    @Override
    public int hashCode() {
        return Objects.hash(xingTag, av1AnnexB);
    }

    @Override
    public String toString() {
        return "RawMuxerOptions[xingTag=" + xingTag + ", av1AnnexB=" + av1AnnexB + "]";
    }

    /**
     * Assembles {@link RawMuxerOptions} setting by setting.
     *
     * <p>No setting has a range to validate. Instances are not thread-safe.
     */
    public static final class Builder {
        private boolean xingTag;

        private boolean av1AnnexB;

        private Builder(RawMuxerOptions options) {
            this.xingTag = options.xingTag;
            this.av1AnnexB = options.av1AnnexB;
        }

        /**
         * Sets whether MP3 streams get a Xing tag frame; see {@link RawMuxerOptions#xingTag()}.
         *
         * @param xingTag the value
         * @return this builder
         */
        public Builder xingTag(boolean xingTag) {
            this.xingTag = xingTag;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public RawMuxerOptions build() {
            return new RawMuxerOptions(xingTag, av1AnnexB);
        }

        /**
         * Sets whether AV1 is written in Annex B; see {@link RawMuxerOptions#av1AnnexB()}.
         *
         * @param av1AnnexB the value
         * @return this builder
         */
        public Builder av1AnnexB(boolean av1AnnexB) {
            this.av1AnnexB = av1AnnexB;
            return this;
        }
    }
}
