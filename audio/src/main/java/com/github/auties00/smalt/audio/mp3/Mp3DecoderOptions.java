package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.MediaLimits;

import java.util.Objects;
import java.util.OptionalInt;

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

    private final Mp2Program program;

    private final int multilingualChannel;

    private Mp3DecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.crcCheck = builder.crcCheck;
        this.multichannel = builder.multichannel;
        this.program = builder.program;
        this.multilingualChannel = builder.multilingualChannel;
    }

    /**
     * Returns the default options: {@link MediaLimits#DEFAULT}, frames failing their CRC concealed and the
     * multichannel extension decoded to its main program.
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
     * the extension of such a frame is decoded as it stands instead. Which program of the extension is output
     * is {@link #program()}.
     *
     * <p>When the setting is off, every frame decodes to its MPEG-1 compatible two channels, the
     * {@code Lo/Ro} downmix of a multichannel stream.
     */
    public boolean multichannel() {
        return multichannel;
    }

    /**
     * {@return the program of a multichannel stream that is output; {@link Mp2Program#MAIN} by default}
     *
     * <p>The main program is the full channel configuration that {@link #multichannel()} describes. With
     * {@link Mp2Program#SECOND_STEREO}, a frame whose extension carries a second stereo program ({@code 1/0 + 2/0},
     * {@code 2/0 + 2/0} or {@code 3/0 + 2/0}) decodes to a {@link com.github.auties00.smalt.audio.ChannelLayout#STEREO}
     * buffer of that program, 1152 samples at the sampling rate of the frame. With {@link Mp2Program#MULTILINGUAL},
     * a frame whose extension carries multilingual channels decodes to a buffer of those channels, in the order of
     * the stream and each of {@link com.github.auties00.smalt.audio.Speaker#UNKNOWN} position, or to a
     * {@link com.github.auties00.smalt.audio.ChannelLayout#MONO} buffer of the one {@link #multilingualChannel()}
     * names: 1152 samples at the sampling rate of the frame or, when the extension codes them at half of it
     * ({@code multilingual_fs}), 576 samples at half the rate.
     *
     * <p>A frame that does not carry the selected program, because its extension does not, because it has fewer
     * multilingual channels than {@link #multilingualChannel()} names, or because it is not decoded as
     * multichannel (before the first such frame of the stream), decodes as the main program would. The exception
     * is a frame after one that decoded to the selected program whose extension fails its CRC, is cut short or
     * misses its extension frame, or that is concealed: it keeps the format of the selected program, with the
     * output of the frames before it decaying to silence.
     */
    public Mp2Program program() {
        return program;
    }

    /**
     * {@return the multilingual channel, from 1 to 7, that {@link Mp2Program#MULTILINGUAL} outputs alone, or empty
     * to output all of them; empty by default}
     */
    public OptionalInt multilingualChannel() {
        return multilingualChannel == 0 ? OptionalInt.empty() : OptionalInt.of(multilingualChannel);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof Mp3DecoderOptions that && limits.equals(that.limits) && crcCheck == that.crcCheck
                && multichannel == that.multichannel && program == that.program
                && multilingualChannel == that.multilingualChannel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, crcCheck, multichannel, program, multilingualChannel);
    }

    @Override
    public String toString() {
        return "Mp3DecoderOptions[limits=" + limits + ", crcCheck=" + crcCheck + ", multichannel=" + multichannel
                + ", program=" + program + ", multilingualChannel="
                + (multilingualChannel == 0 ? "all" : Integer.toString(multilingualChannel)) + "]";
    }

    /**
     * A builder of {@link Mp3DecoderOptions}, starting from {@link Mp3DecoderOptions#defaults()}.
     *
     * <p>Setters reject {@code null} and values out of range; {@link #build()} checks that the settings agree. A
     * builder may build repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;

        private boolean crcCheck;

        private boolean multichannel;

        private Mp2Program program;

        private int multilingualChannel;

        private Builder() {
            this.limits = MediaLimits.DEFAULT;
            this.crcCheck = true;
            this.multichannel = true;
            this.program = Mp2Program.MAIN;
        }

        private Builder(Mp3DecoderOptions options) {
            this.limits = options.limits;
            this.crcCheck = options.crcCheck;
            this.multichannel = options.multichannel;
            this.program = options.program;
            this.multilingualChannel = options.multilingualChannel;
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
         * Sets the program of a multichannel stream that is output; see {@link Mp3DecoderOptions#program()}.
         *
         * @param program the value
         * @return this builder
         */
        public Builder program(Mp2Program program) {
            this.program = Objects.requireNonNull(program, "program");
            return this;
        }

        /**
         * Sets the one multilingual channel that {@link Mp2Program#MULTILINGUAL} outputs; see
         * {@link Mp3DecoderOptions#multilingualChannel()}.
         *
         * @param channel the channel, from 1 to 7 in the order of the stream
         * @return this builder
         * @throws IllegalArgumentException if {@code channel} is out of range
         */
        public Builder multilingualChannel(int channel) {
            if (channel < 1 || channel > 7) {
                throw new IllegalArgumentException("multilingual channel " + channel + " is not between 1 and 7");
            }
            this.multilingualChannel = channel;
            return this;
        }

        /**
         * Makes {@link Mp2Program#MULTILINGUAL} output every multilingual channel; see
         * {@link Mp3DecoderOptions#multilingualChannel()}.
         *
         * @return this builder
         */
        public Builder allMultilingualChannels() {
            this.multilingualChannel = 0;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if a program other than {@link Mp2Program#MAIN} is selected with the
         * multichannel extension off, or a multilingual channel is named with a program other than
         * {@link Mp2Program#MULTILINGUAL}
         */
        public Mp3DecoderOptions build() {
            if (program != Mp2Program.MAIN && !multichannel) {
                throw new IllegalArgumentException("the " + program + " program needs the multichannel extension");
            }
            if (multilingualChannel != 0 && program != Mp2Program.MULTILINGUAL) {
                throw new IllegalArgumentException("a multilingual channel is named for the " + program + " program");
            }
            return new Mp3DecoderOptions(this);
        }
    }
}
