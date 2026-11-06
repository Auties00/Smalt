package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.MediaLimits;

import java.util.Objects;

/**
 * Holds the settings of an {@link Mp3Decoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class Mp3DecoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final Mp3DecoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final boolean crcCheck;

    private final boolean multichannel;

    private Mp3DecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.crcCheck = builder.crcCheck;
        this.multichannel = builder.multichannel;
    }

    /**
     * Returns the default options: {@link MediaLimits#DEFAULT}, frames failing their CRC concealed and the
     * multichannel extension decoded.
     *
     * @return the default options
     */
    public static Mp3DecoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
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
     * {@return the resource bounds the decoder enforces; {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return whether frames that fail their CRC-16 are concealed instead of decoded; {@code true} by
     * default}
     *
     * <p>A frame whose header sets the protection bit carries a CRC-16 word (ISO/IEC 11172-3 clause
     * 2.4.3.1) over the last 16 bits of its header and its side information (Layer III), bit allocation
     * (Layer I) or bit allocation and scalefactor selection (Layer II). When the check is on, a frame
     * whose word does not match is concealed as {@link Mp3Decoder} describes; when it is off, the word
     * is skipped and every frame is decoded as it stands. Frames without a CRC word are always decoded.
     */
    public boolean crcCheck() {
        return crcCheck;
    }

    /**
     * {@return whether MPEG-1 Layer II frames carrying the ISO/IEC 13818-3 multichannel extension decode to
     * their full channel configuration; {@code true} by default}
     *
     * <p>The extension follows the audio data of the frame, in its ancillary data, and continues, when it is
     * larger than the frame holds, in an extension frame (sync word {@code 0x7FF}) that must follow its
     * frame in the same packet. A frame is taken as multichannel when the CRC-16 of its extension matches,
     * which the extension always carries; once a frame of the stream has decoded as multichannel, a later
     * frame whose extension fails its CRC, whose extension frame is missing or whose extension is cut short
     * is concealed in the multichannel layout: its base channels {@code Lo/Ro} play in the left and right
     * channels, without dematrixing, and the other channels decay to silence. With {@link #crcCheck()} off,
     * the extension of such a frame is decoded as it stands instead. The output is the main programme:
     * the second stereo programme of a {@code 3/0 + 2/0} or {@code 2/0 + 2/0} stream and the multilingual
     * channels are not output.
     *
     * <p>When the setting is off, every frame decodes to its MPEG-1 compatible two channels, the
     * {@code Lo/Ro} downmix of a multichannel stream.
     */
    public boolean multichannel() {
        return multichannel;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof Mp3DecoderOptions that && limits.equals(that.limits) && crcCheck == that.crcCheck
                && multichannel == that.multichannel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, crcCheck, multichannel);
    }

    @Override
    public String toString() {
        return "Mp3DecoderOptions[limits=" + limits + ", crcCheck=" + crcCheck + ", multichannel=" + multichannel + "]";
    }

    /**
     * A builder of {@link Mp3DecoderOptions}, starting from {@link Mp3DecoderOptions#defaults()}.
     *
     * <p>No setting has a range to validate; setters reject only {@code null}. A builder may build
     * repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;

        private boolean crcCheck;

        private boolean multichannel;

        private Builder() {
            this.limits = MediaLimits.DEFAULT;
            this.crcCheck = true;
            this.multichannel = true;
        }

        private Builder(Mp3DecoderOptions options) {
            this.limits = options.limits;
            this.crcCheck = options.crcCheck;
            this.multichannel = options.multichannel;
        }

        /**
         * Sets the resource bounds; see {@link Mp3DecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets whether frames failing their CRC-16 are concealed; see {@link Mp3DecoderOptions#crcCheck()}.
         *
         * @param crcCheck the value
         * @return this builder
         */
        public Builder crcCheck(boolean crcCheck) {
            this.crcCheck = crcCheck;
            return this;
        }

        /**
         * Sets whether the multichannel extension is decoded; see {@link Mp3DecoderOptions#multichannel()}.
         *
         * @param multichannel the value
         * @return this builder
         */
        public Builder multichannel(boolean multichannel) {
            this.multichannel = multichannel;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         */
        public Mp3DecoderOptions build() {
            return new Mp3DecoderOptions(this);
        }
    }
}
