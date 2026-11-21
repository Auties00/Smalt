package com.github.auties00.smalt.audio.amr;

import com.github.auties00.smalt.track.Codec;

import java.util.Objects;

/**
 * Holds the settings of an {@link AmrEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AmrEncoderOptions {
    /**
     * The options of {@link #defaults()} and {@link #narrowband()}.
     */
    private static final AmrEncoderOptions DEFAULTS = new Builder().build();

    /**
     * The options of {@link #wideband()}.
     */
    private static final AmrEncoderOptions WIDEBAND = new Builder().mode(AmrMode.WB_23_85).build();

    private final AmrMode mode;

    private final boolean dtx;

    private final AmrVad vad;

    private AmrEncoderOptions(Builder builder) {
        this.mode = builder.mode;
        this.dtx = builder.dtx;
        this.vad = builder.vad;
    }

    /**
     * Returns the default options: AMR-NB at 12.2 kbit/s without discontinuous transmission.
     *
     * @return the default options
     */
    public static AmrEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns AMR-NB at its highest bitrate, 12.2 kbit/s, without discontinuous transmission:
     * the same as {@link #defaults()}.
     *
     * @return the options
     */
    public static AmrEncoderOptions narrowband() {
        return DEFAULTS;
    }

    /**
     * Returns AMR-WB at its highest bitrate, 23.85 kbit/s, without discontinuous transmission.
     *
     * @return the options
     */
    public static AmrEncoderOptions wideband() {
        return WIDEBAND;
    }

    /**
     * Returns options coding a mode without discontinuous transmission.
     *
     * @param mode the codec mode; see {@link #mode()}
     * @return the options
     */
    public static AmrEncoderOptions of(AmrMode mode) {
        return new Builder().mode(mode).build();
    }

    /**
     * Returns the mode of a codec closest to a bitrate, as {@link AmrMode#closest(Codec, int)}
     * chooses it, without discontinuous transmission.
     *
     * @param codec {@link Codec#AMR_NB} or {@link Codec#AMR_WB}
     * @param bitrate the target bitrate in bits per second
     * @return the options
     * @throws IllegalArgumentException if {@code codec} is not an AMR codec
     */
    public static AmrEncoderOptions ofBitrate(Codec codec, int bitrate) {
        return of(AmrMode.closest(codec, bitrate));
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
     * {@return the codec mode, which selects AMR-NB or AMR-WB and the bitrate; {@link AmrMode#NB_12_2} by default}
     *
     * <p>AMR-NB codes 8 kHz mono input, AMR-WB 16 kHz mono input.
     */
    public AmrMode mode() {
        return mode;
    }

    /**
     * {@return whether discontinuous transmission is on; {@code false} by default}
     *
     * <p>With discontinuous transmission (DTX) the voice activity detector (the {@link #vad()} option
     * for AMR-NB, the TS 26.194 detector for AMR-WB) replaces the frames of background noise by comfort noise
     * parameters (SID frames) every eighth frame and empty frames in between, which roughly halves
     * the bitrate of a conversation.
     */
    public boolean dtx() {
        return dtx;
    }

    /**
     * {@return the voice activity detector of AMR-NB; {@link AmrVad#OPTION_1} by default}
     *
     * <p>The setting has no effect without {@link #dtx()} or with an AMR-WB mode, since AMR-WB has a
     * single detector. With either option the output is bit-exact with the 3GPP reference encoder
     * built with that option.
     */
    public AmrVad vad() {
        return vad;
    }

    /**
     * {@return the codec the mode belongs to, {@link Codec#AMR_NB} or {@link Codec#AMR_WB}}
     */
    public Codec codec() {
        return mode.codec();
    }

    /**
     * {@return the sampling rate of the input in hertz, 8000 for AMR-NB and 16000 for AMR-WB}
     */
    public int sampleRate() {
        return mode.sampleRate();
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof AmrEncoderOptions that && mode == that.mode && dtx == that.dtx && vad == that.vad;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, dtx, vad);
    }

    @Override
    public String toString() {
        return "AmrEncoderOptions[mode=" + mode + ", dtx=" + dtx + ", vad=" + vad + "]";
    }

    /**
     * A builder of {@link AmrEncoderOptions}, starting from {@link AmrEncoderOptions#defaults()}.
     *
     * <p>Setters reject only {@code null}; every value is valid. A builder may build repeatedly.
     * Instances are not thread-safe.
     */
    public static final class Builder {
        private AmrMode mode;

        private boolean dtx;

        private AmrVad vad;

        private Builder() {
            this.mode = AmrMode.NB_12_2;
            this.vad = AmrVad.OPTION_1;
        }

        private Builder(AmrEncoderOptions options) {
            this.mode = options.mode;
            this.dtx = options.dtx;
            this.vad = options.vad;
        }

        /**
         * Sets the codec mode; see {@link AmrEncoderOptions#mode()}.
         *
         * @param mode the value
         * @return this builder
         */
        public Builder mode(AmrMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Sets whether discontinuous transmission is on; see {@link AmrEncoderOptions#dtx()}.
         *
         * @param dtx the value
         * @return this builder
         */
        public Builder dtx(boolean dtx) {
            this.dtx = dtx;
            return this;
        }

        /**
         * Sets the voice activity detector of AMR-NB discontinuous transmission; see
         * {@link AmrEncoderOptions#vad()}.
         *
         * @param vad the value
         * @return this builder
         */
        public Builder vad(AmrVad vad) {
            this.vad = Objects.requireNonNull(vad, "vad");
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         */
        public AmrEncoderOptions build() {
            return new AmrEncoderOptions(this);
        }
    }
}
