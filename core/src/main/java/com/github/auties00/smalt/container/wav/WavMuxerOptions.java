package com.github.auties00.smalt.container.wav;

import java.util.Objects;

/**
 * Holds the settings of a {@link WavMuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class WavMuxerOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final WavMuxerOptions DEFAULTS = new WavMuxerOptions(Rf64.AUTO, Encapsulation.NATIVE);

    /**
     * The options of {@link #rf64()}.
     */
    private static final WavMuxerOptions RF64 = new WavMuxerOptions(Rf64.ALWAYS, Encapsulation.NATIVE);

    /**
     * The options of {@link #forIec61937()}.
     */
    private static final WavMuxerOptions IEC61937 = new WavMuxerOptions(Rf64.AUTO, Encapsulation.IEC61937_PCM);

    /**
     * Enumerates the policies for the 64-bit RF64 header.
     *
     * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Rf64 {
        /**
         * Writes RIFF with a {@code JUNK} chunk reserving the room of a {@code ds64} chunk, and
         * turns the header into RF64 when the file reaches 4 GiB.
         */
        AUTO,

        /**
         * Writes an RF64 header with a {@code ds64} chunk whatever the size; needs a seekable sink.
         */
        ALWAYS,

        /**
         * Writes plain RIFF without reserved room; data that would make the file reach 4 GiB is
         * rejected.
         */
        NEVER
    }

    /**
     * Enumerates how coded audio is stored in the {@code data} chunk.
     *
     * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Encapsulation {
        /**
         * Stores coded audio under the format tag of its codec, in the framing {@link WavDemuxer}
         * reads back packet for packet; PCM is stored as it is.
         */
        NATIVE,

        /**
         * Stores coded audio as IEC 61937 data bursts in the samples of 16-bit PCM declared as
         * {@code WAVE_FORMAT_PCM} (or, for the 8 channels of the high bit rate formats, as
         * {@code WAVE_FORMAT_EXTENSIBLE} PCM), the form S/PDIF and HDMI pass-through players and
         * DTS and AC-3 audio CDs carry; PCM is stored as it is.
         */
        IEC61937_PCM,

        /**
         * Stores coded audio as IEC 61937 data bursts declared by a {@code WAVEFORMATEXTENSIBLE_IEC61937}
         * structure with the {@code KSDATAFORMAT_SUBTYPE_IEC61937} sub-format of the codec, the form
         * Windows pass-through audio uses; PCM is stored as it is.
         */
        IEC61937_EXTENSIBLE
    }

    private final Rf64 rf64;
    private final Encapsulation encapsulation;

    private WavMuxerOptions(Rf64 rf64, Encapsulation encapsulation) {
        this.rf64 = rf64;
        this.encapsulation = encapsulation;
    }

    /**
     * Returns the options that switch to RF64 only when needed.
     *
     * @return the default options
     */
    public static WavMuxerOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the options that always write RF64.
     *
     * @return the options
     */
    public static WavMuxerOptions rf64() {
        return RF64;
    }

    /**
     * Returns the options that store coded audio as IEC 61937 data bursts in 16-bit PCM samples
     * ({@link Encapsulation#IEC61937_PCM}), switching to RF64 only when needed.
     *
     * @return the options
     */
    public static WavMuxerOptions forIec61937() {
        return IEC61937;
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
     * {@return how the muxer handles files of 4 GiB and more, which a RIFF header cannot describe;
     * {@link Rf64#AUTO} by default}
     *
     * <p>{@link Rf64#AUTO} reserves room for the 64-bit sizes of an RF64 header (EBU Tech 3306) and
     * switches to RF64 when the file needs it, {@link Rf64#ALWAYS} writes RF64 whatever the size, and
     * {@link Rf64#NEVER} writes plain RIFF and rejects data that would not fit. A streaming sink
     * always gets a plain RIFF header with unknown sizes.
     */
    public Rf64 rf64Policy() {
        return rf64;
    }

    /**
     * {@return how coded audio is stored; {@link Encapsulation#NATIVE} by default}
     *
     * <p>{@link Encapsulation#NATIVE} stores each codec under its own format tag. The two IEC 61937
     * forms wrap AC-3, E-AC-3, MPEG audio, AAC, DTS (with DTS-HD) and Dolby TrueHD in the data
     * bursts of IEC 61937-2 to -6 and -9, which a receiver decodes from what looks like 16-bit PCM;
     * see {@link WavMuxer} for the data types and rates. Other coded audio cannot be carried in
     * bursts and is rejected, and PCM is stored as it is in every form.
     */
    public Encapsulation encapsulation() {
        return encapsulation;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WavMuxerOptions that && rf64 == that.rf64 && encapsulation == that.encapsulation;
    }

    @Override
    public int hashCode() {
        return 31 * rf64.hashCode() + encapsulation.hashCode();
    }

    @Override
    public String toString() {
        return "WavMuxerOptions[rf64=" + rf64 + ", encapsulation=" + encapsulation + "]";
    }

    /**
     * Assembles {@link WavMuxerOptions} setting by setting.
     *
     * <p>Setters reject {@code null}; no setting has a range to validate. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private Rf64 rf64;
        private Encapsulation encapsulation;

        private Builder(WavMuxerOptions options) {
            this.rf64 = options.rf64;
            this.encapsulation = options.encapsulation;
        }

        /**
         * Sets the RF64 policy; see {@link WavMuxerOptions#rf64Policy()}.
         *
         * @param rf64 the value
         * @return this builder
         */
        public Builder rf64Policy(Rf64 rf64) {
            this.rf64 = Objects.requireNonNull(rf64, "rf64");
            return this;
        }

        /**
         * Sets how coded audio is stored; see {@link WavMuxerOptions#encapsulation()}.
         *
         * @param encapsulation the value
         * @return this builder
         */
        public Builder encapsulation(Encapsulation encapsulation) {
            this.encapsulation = Objects.requireNonNull(encapsulation, "encapsulation");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public WavMuxerOptions build() {
            return new WavMuxerOptions(rf64, encapsulation);
        }
    }
}
