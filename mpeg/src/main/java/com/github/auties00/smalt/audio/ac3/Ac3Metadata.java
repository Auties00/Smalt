package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the metadata an {@link Ac3Encoder} or an {@link Eac3Encoder} writes into its frames besides
 * the dialogue level: the type of service, the downmix levels and preferences, the matrix encodings,
 * the production information, the program scale factors and the dynamic range control profiles.
 * Instances are immutable and thread-safe; create them with {@link #builder()} or start from
 * {@link #defaults()}.
 *
 * <p>AC-3 frames carry the type of service, the center and surround mix levels, the Dolby Surround
 * mode, the copyright and original flags and the production information in every frame. When
 * {@link #preferredDownmix()}, an Lt/Rt or Lo/Ro level, {@link #surroundExMode()},
 * {@link #headphoneMode()} or {@link #converterType()} is set to anything but its default, the
 * frames use the alternate bit stream syntax of ATSC A/52 Annex D ({@code bsid} 6), whose extra bit
 * stream information carries them. E-AC-3 frames of the independent substream carry the mixing
 * metadata ({@code mixmdate}) when a downmix preference, a mix level, the LFE mix level or a program
 * scale factor differs from its default, and the informational metadata ({@code infomdate}) when any
 * informational field does.
 *
 * <p>Fields a frame's channel configuration has no place for are not written: the center levels
 * without three front channels, the surround levels without surrounds, the Dolby Surround and
 * Dolby Headphone modes outside 2/0, the surround EX mode without two surround channels, the
 * preferred downmix below three channels and the LFE mix level without an LFE channel.
 */
public final class Ac3Metadata {
    /**
     * The metadata of {@link #defaults()}.
     */
    private static final Ac3Metadata DEFAULTS = new Builder().build();

    private final Ac3ServiceType serviceType;

    private final Ac3MixLevel centerMixLevel;

    private final Ac3MixLevel surroundMixLevel;

    private final Ac3Indication surroundMode;

    private final Ac3PreferredDownmix preferredDownmix;

    private final Ac3MixLevel ltrtCenterMixLevel;

    private final Ac3MixLevel ltrtSurroundMixLevel;

    private final Ac3MixLevel loroCenterMixLevel;

    private final Ac3MixLevel loroSurroundMixLevel;

    private final Ac3SurroundExMode surroundExMode;

    private final Ac3Indication headphoneMode;

    private final Ac3ConverterType converterType;

    /**
     * The mixing level in dB SPL, or {@link Integer#MIN_VALUE} when not declared.
     */
    private final int mixingLevel;

    private final Ac3RoomType roomType;

    private final boolean copyright;

    private final boolean original;

    /**
     * The LFE mix level in dB, or {@link Integer#MIN_VALUE} when not declared.
     */
    private final int lfeMixLevel;

    /**
     * The program scale factor in dB, or {@link Integer#MIN_VALUE} when not declared.
     */
    private final int programScale;

    /**
     * The external program scale factor in dB, or {@link Integer#MIN_VALUE} when not declared.
     */
    private final int externalProgramScale;

    private final boolean doubleRateSource;

    private final Ac3DynamicRangeProfile lineModeProfile;

    private final Ac3DynamicRangeProfile rfModeProfile;

    private Ac3Metadata(Builder builder) {
        this.serviceType = builder.serviceType;
        this.centerMixLevel = builder.centerMixLevel;
        this.surroundMixLevel = builder.surroundMixLevel;
        this.surroundMode = builder.surroundMode;
        this.preferredDownmix = builder.preferredDownmix;
        this.ltrtCenterMixLevel = builder.ltrtCenterMixLevel;
        this.ltrtSurroundMixLevel = builder.ltrtSurroundMixLevel;
        this.loroCenterMixLevel = builder.loroCenterMixLevel;
        this.loroSurroundMixLevel = builder.loroSurroundMixLevel;
        this.surroundExMode = builder.surroundExMode;
        this.headphoneMode = builder.headphoneMode;
        this.converterType = builder.converterType;
        this.mixingLevel = builder.mixingLevel;
        this.roomType = builder.roomType;
        this.copyright = builder.copyright;
        this.original = builder.original;
        this.lfeMixLevel = builder.lfeMixLevel;
        this.programScale = builder.programScale;
        this.externalProgramScale = builder.externalProgramScale;
        this.doubleRateSource = builder.doubleRateSource;
        this.lineModeProfile = builder.lineModeProfile;
        this.rfModeProfile = builder.rfModeProfile;
    }

    /**
     * Returns the default metadata: a complete main service, a center mix level of -4.5 dB and a
     * surround mix level of -6 dB, every mode and preference not indicated, a standard converter,
     * no production information, an original stream without copyright, no LFE mix level or program
     * scale factors, and no dynamic range control.
     *
     * @return the default metadata
     */
    public static Ac3Metadata defaults() {
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
     * Returns a builder initialized with this metadata.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the type of service, {@code bsmod}; {@link Ac3ServiceType#COMPLETE_MAIN} by default}
     */
    public Ac3ServiceType serviceType() {
        return serviceType;
    }

    /**
     * {@return the center mix level of AC-3, {@code cmixlev}: -3, -4.5 or -6 dB;
     * {@link Ac3MixLevel#MINUS_4_5_DB} by default}
     *
     * <p>E-AC-3 has no such field; frames that carry mixing metadata (see the class description)
     * carry this level as their Lt/Rt and Lo/Ro center levels where those are not set. Frames whose
     * mixing fields all keep their defaults carry none, and E-AC-3 decoders then mix the center at
     * -3 dB; set {@link Builder#loroCenterMixLevel(Ac3MixLevel)} to send -4.5 dB.
     */
    public Ac3MixLevel centerMixLevel() {
        return centerMixLevel;
    }

    /**
     * {@return the surround mix level of AC-3, {@code surmixlev}: -3 dB, -6 dB or silence;
     * {@link Ac3MixLevel#MINUS_6_DB} by default}
     *
     * <p>E-AC-3 has no such field; frames that carry mixing metadata (see the class description)
     * carry this level as their Lt/Rt and Lo/Ro surround levels where those are not set. Frames whose
     * mixing fields all keep their defaults carry none, and E-AC-3 decoders then mix the surrounds at
     * -3 dB; set {@link Builder#loroSurroundMixLevel(Ac3MixLevel)} to send -6 dB.
     */
    public Ac3MixLevel surroundMixLevel() {
        return surroundMixLevel;
    }

    /**
     * {@return whether a 2/0 program is Dolby Surround encoded, {@code dsurmod};
     * {@link Ac3Indication#NOT_INDICATED} by default}
     */
    public Ac3Indication surroundMode() {
        return surroundMode;
    }

    /**
     * {@return the stereo downmix the stream declares preferred, {@code dmixmod};
     * {@link Ac3PreferredDownmix#NOT_INDICATED} by default}
     */
    public Ac3PreferredDownmix preferredDownmix() {
        return preferredDownmix;
    }

    /**
     * {@return the center mix level of the Lt/Rt downmix, {@code ltrtcmixlev}, or empty to take
     * {@link #centerMixLevel()}; empty by default}
     */
    public Optional<Ac3MixLevel> ltrtCenterMixLevel() {
        return Optional.ofNullable(ltrtCenterMixLevel);
    }

    /**
     * {@return the surround mix level of the Lt/Rt downmix, {@code ltrtsurmixlev}, from -1.5 dB to
     * silence, or empty to take {@link #surroundMixLevel()}; empty by default}
     */
    public Optional<Ac3MixLevel> ltrtSurroundMixLevel() {
        return Optional.ofNullable(ltrtSurroundMixLevel);
    }

    /**
     * {@return the center mix level of the Lo/Ro downmix, {@code lorocmixlev}, or empty to take
     * {@link #centerMixLevel()}; empty by default}
     */
    public Optional<Ac3MixLevel> loroCenterMixLevel() {
        return Optional.ofNullable(loroCenterMixLevel);
    }

    /**
     * {@return the surround mix level of the Lo/Ro downmix, {@code lorosurmixlev}, from -1.5 dB to
     * silence, or empty to take {@link #surroundMixLevel()}; empty by default}
     */
    public Optional<Ac3MixLevel> loroSurroundMixLevel() {
        return Optional.ofNullable(loroSurroundMixLevel);
    }

    /**
     * {@return the matrix encoding of the surround channels of a 2/2 or 3/2 program,
     * {@code dsurexmod}; {@link Ac3SurroundExMode#NOT_INDICATED} by default}
     */
    public Ac3SurroundExMode surroundExMode() {
        return surroundExMode;
    }

    /**
     * {@return whether a 2/0 program is Dolby Headphone encoded, {@code dheadphonmod};
     * {@link Ac3Indication#NOT_INDICATED} by default}
     */
    public Ac3Indication headphoneMode() {
        return headphoneMode;
    }

    /**
     * {@return the type of converter that captured the program, {@code adconvtyp};
     * {@link Ac3ConverterType#STANDARD} by default}
     *
     * <p>E-AC-3 carries the converter type with the production information, so its frames write
     * it only when {@link #mixingLevel()} is set.
     */
    public Ac3ConverterType converterType() {
        return converterType;
    }

    /**
     * {@return the peak sound pressure level of the mixing session in dB SPL, {@code mixlevel},
     * from 80 to 111, or empty when not declared; empty by default}
     *
     * <p>With a mixing level, the frames carry the production information: the mixing level and
     * the {@link #roomType()}.
     */
    public OptionalInt mixingLevel() {
        return mixingLevel == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(mixingLevel);
    }

    /**
     * {@return the type of the mixing room, {@code roomtyp}, written with {@link #mixingLevel()};
     * {@link Ac3RoomType#NOT_INDICATED} by default}
     */
    public Ac3RoomType roomType() {
        return roomType;
    }

    /**
     * {@return whether the program is protected by copyright, {@code copyrightb}; {@code false} by
     * default}
     */
    public boolean copyright() {
        return copyright;
    }

    /**
     * {@return whether the stream is an original rather than a copy, {@code origbs}; {@code true}
     * by default}
     */
    public boolean original() {
        return original;
    }

    /**
     * {@return the level at which a downmix mixes the LFE channel into the front pair, in dB from
     * -21 to +10 ({@code lfemixlevcod} of E-AC-3), or empty when not declared; empty by default}
     *
     * <p>AC-3 has no such field.
     */
    public OptionalInt lfeMixLevel() {
        return lfeMixLevel == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(lfeMixLevel);
    }

    /**
     * {@return the scale factor decoders apply to the program, in dB from -50 to +12 ({@code pgmscl}
     * of E-AC-3), or empty when not declared; empty by default}
     *
     * <p>AC-3 has no such field.
     */
    public OptionalInt programScale() {
        return programScale == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(programScale);
    }

    /**
     * {@return the scale factor decoders apply to an external program mixed with this one, in dB
     * from -50 to +12 ({@code extpgmscl} of E-AC-3), or empty when not declared; empty by default}
     *
     * <p>AC-3 has no such field.
     */
    public OptionalInt externalProgramScale() {
        return externalProgramScale == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(externalProgramScale);
    }

    /**
     * {@return whether the source material was sampled at twice the coded rate ({@code sourcefscod}
     * of E-AC-3); {@code false} by default}
     *
     * <p>AC-3 has no such field, and E-AC-3 has none at the reduced rates of 16 to 24 kHz.
     */
    public boolean doubleRateSource() {
        return doubleRateSource;
    }

    /**
     * {@return the profile of the dynamic range words ({@code dynrng}), which decoders apply in
     * line mode; {@link Ac3DynamicRangeProfile#NONE} by default}
     *
     * <p>With a profile, every block carries a word computed from the level of the program over the
     * block relative to the declared dialogue level.
     */
    public Ac3DynamicRangeProfile lineModeProfile() {
        return lineModeProfile;
    }

    /**
     * {@return the profile of the heavy compression words ({@code compr}), which decoders apply in
     * RF mode; {@link Ac3DynamicRangeProfile#NONE} by default}
     *
     * <p>With a profile, every frame carries a word computed from the level of the program over the
     * frame, limited so that a decoder in RF mode, which raises the output by 11 dB and normalizes
     * the dialogue level to -20 dBFS, does not clip the frame's peak.
     */
    public Ac3DynamicRangeProfile rfModeProfile() {
        return rfModeProfile;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof Ac3Metadata that
                && serviceType == that.serviceType
                && centerMixLevel == that.centerMixLevel
                && surroundMixLevel == that.surroundMixLevel
                && surroundMode == that.surroundMode
                && preferredDownmix == that.preferredDownmix
                && ltrtCenterMixLevel == that.ltrtCenterMixLevel
                && ltrtSurroundMixLevel == that.ltrtSurroundMixLevel
                && loroCenterMixLevel == that.loroCenterMixLevel
                && loroSurroundMixLevel == that.loroSurroundMixLevel
                && surroundExMode == that.surroundExMode
                && headphoneMode == that.headphoneMode
                && converterType == that.converterType
                && mixingLevel == that.mixingLevel
                && roomType == that.roomType
                && copyright == that.copyright
                && original == that.original
                && lfeMixLevel == that.lfeMixLevel
                && programScale == that.programScale
                && externalProgramScale == that.externalProgramScale
                && doubleRateSource == that.doubleRateSource
                && lineModeProfile == that.lineModeProfile
                && rfModeProfile == that.rfModeProfile;
    }

    @Override
    public int hashCode() {
        return Objects.hash(serviceType, centerMixLevel, surroundMixLevel, surroundMode, preferredDownmix,
                ltrtCenterMixLevel, ltrtSurroundMixLevel, loroCenterMixLevel, loroSurroundMixLevel, surroundExMode,
                headphoneMode, converterType, mixingLevel, roomType, copyright, original, lfeMixLevel, programScale,
                externalProgramScale, doubleRateSource, lineModeProfile, rfModeProfile);
    }

    @Override
    public String toString() {
        return "Ac3Metadata[serviceType=" + serviceType + ", centerMixLevel=" + centerMixLevel + ", surroundMixLevel="
                + surroundMixLevel + ", surroundMode=" + surroundMode + ", preferredDownmix=" + preferredDownmix
                + ", ltrtCenterMixLevel=" + ltrtCenterMixLevel() + ", ltrtSurroundMixLevel=" + ltrtSurroundMixLevel()
                + ", loroCenterMixLevel=" + loroCenterMixLevel() + ", loroSurroundMixLevel=" + loroSurroundMixLevel()
                + ", surroundExMode=" + surroundExMode + ", headphoneMode=" + headphoneMode + ", converterType="
                + converterType + ", mixingLevel=" + mixingLevel() + ", roomType=" + roomType + ", copyright="
                + copyright + ", original=" + original + ", lfeMixLevel=" + lfeMixLevel() + ", programScale="
                + programScale() + ", externalProgramScale=" + externalProgramScale() + ", doubleRateSource="
                + doubleRateSource + ", lineModeProfile=" + lineModeProfile + ", rfModeProfile=" + rfModeProfile + "]";
    }

    /**
     * A builder of {@link Ac3Metadata}, starting from {@link Ac3Metadata#defaults()}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the values. A builder may build
     * repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private Ac3ServiceType serviceType;

        private Ac3MixLevel centerMixLevel;

        private Ac3MixLevel surroundMixLevel;

        private Ac3Indication surroundMode;

        private Ac3PreferredDownmix preferredDownmix;

        private Ac3MixLevel ltrtCenterMixLevel;

        private Ac3MixLevel ltrtSurroundMixLevel;

        private Ac3MixLevel loroCenterMixLevel;

        private Ac3MixLevel loroSurroundMixLevel;

        private Ac3SurroundExMode surroundExMode;

        private Ac3Indication headphoneMode;

        private Ac3ConverterType converterType;

        private int mixingLevel;

        private Ac3RoomType roomType;

        private boolean copyright;

        private boolean original;

        private int lfeMixLevel;

        private int programScale;

        private int externalProgramScale;

        private boolean doubleRateSource;

        private Ac3DynamicRangeProfile lineModeProfile;

        private Ac3DynamicRangeProfile rfModeProfile;

        private Builder() {
            this.serviceType = Ac3ServiceType.COMPLETE_MAIN;
            this.centerMixLevel = Ac3MixLevel.MINUS_4_5_DB;
            this.surroundMixLevel = Ac3MixLevel.MINUS_6_DB;
            this.surroundMode = Ac3Indication.NOT_INDICATED;
            this.preferredDownmix = Ac3PreferredDownmix.NOT_INDICATED;
            this.surroundExMode = Ac3SurroundExMode.NOT_INDICATED;
            this.headphoneMode = Ac3Indication.NOT_INDICATED;
            this.converterType = Ac3ConverterType.STANDARD;
            this.roomType = Ac3RoomType.NOT_INDICATED;
            this.original = true;
            this.mixingLevel = Integer.MIN_VALUE;
            this.lfeMixLevel = Integer.MIN_VALUE;
            this.programScale = Integer.MIN_VALUE;
            this.externalProgramScale = Integer.MIN_VALUE;
            this.lineModeProfile = Ac3DynamicRangeProfile.NONE;
            this.rfModeProfile = Ac3DynamicRangeProfile.NONE;
        }

        private Builder(Ac3Metadata metadata) {
            this.serviceType = metadata.serviceType;
            this.centerMixLevel = metadata.centerMixLevel;
            this.surroundMixLevel = metadata.surroundMixLevel;
            this.surroundMode = metadata.surroundMode;
            this.preferredDownmix = metadata.preferredDownmix;
            this.ltrtCenterMixLevel = metadata.ltrtCenterMixLevel;
            this.ltrtSurroundMixLevel = metadata.ltrtSurroundMixLevel;
            this.loroCenterMixLevel = metadata.loroCenterMixLevel;
            this.loroSurroundMixLevel = metadata.loroSurroundMixLevel;
            this.surroundExMode = metadata.surroundExMode;
            this.headphoneMode = metadata.headphoneMode;
            this.converterType = metadata.converterType;
            this.mixingLevel = metadata.mixingLevel;
            this.roomType = metadata.roomType;
            this.copyright = metadata.copyright;
            this.original = metadata.original;
            this.lfeMixLevel = metadata.lfeMixLevel;
            this.programScale = metadata.programScale;
            this.externalProgramScale = metadata.externalProgramScale;
            this.doubleRateSource = metadata.doubleRateSource;
            this.lineModeProfile = metadata.lineModeProfile;
            this.rfModeProfile = metadata.rfModeProfile;
        }

        /**
         * Sets the type of service; see {@link Ac3Metadata#serviceType()}.
         *
         * @param serviceType the value
         * @return this builder
         */
        public Builder serviceType(Ac3ServiceType serviceType) {
            this.serviceType = Objects.requireNonNull(serviceType, "serviceType");
            return this;
        }

        /**
         * Sets the AC-3 center mix level; see {@link Ac3Metadata#centerMixLevel()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder centerMixLevel(Ac3MixLevel level) {
            this.centerMixLevel = Objects.requireNonNull(level, "level");
            return this;
        }

        /**
         * Sets the AC-3 surround mix level; see {@link Ac3Metadata#surroundMixLevel()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder surroundMixLevel(Ac3MixLevel level) {
            this.surroundMixLevel = Objects.requireNonNull(level, "level");
            return this;
        }

        /**
         * Sets the Dolby Surround mode; see {@link Ac3Metadata#surroundMode()}.
         *
         * @param mode the value
         * @return this builder
         */
        public Builder surroundMode(Ac3Indication mode) {
            this.surroundMode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Sets the preferred stereo downmix; see {@link Ac3Metadata#preferredDownmix()}.
         *
         * @param downmix the value
         * @return this builder
         */
        public Builder preferredDownmix(Ac3PreferredDownmix downmix) {
            this.preferredDownmix = Objects.requireNonNull(downmix, "downmix");
            return this;
        }

        /**
         * Sets the Lt/Rt center mix level; see {@link Ac3Metadata#ltrtCenterMixLevel()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder ltrtCenterMixLevel(Ac3MixLevel level) {
            this.ltrtCenterMixLevel = Objects.requireNonNull(level, "level");
            return this;
        }

        /**
         * Lets the Lt/Rt center mix level follow {@link Ac3Metadata#centerMixLevel()}.
         *
         * @return this builder
         */
        public Builder noLtrtCenterMixLevel() {
            this.ltrtCenterMixLevel = null;
            return this;
        }

        /**
         * Sets the Lt/Rt surround mix level; see {@link Ac3Metadata#ltrtSurroundMixLevel()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder ltrtSurroundMixLevel(Ac3MixLevel level) {
            this.ltrtSurroundMixLevel = Objects.requireNonNull(level, "level");
            return this;
        }

        /**
         * Lets the Lt/Rt surround mix level follow {@link Ac3Metadata#surroundMixLevel()}.
         *
         * @return this builder
         */
        public Builder noLtrtSurroundMixLevel() {
            this.ltrtSurroundMixLevel = null;
            return this;
        }

        /**
         * Sets the Lo/Ro center mix level; see {@link Ac3Metadata#loroCenterMixLevel()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder loroCenterMixLevel(Ac3MixLevel level) {
            this.loroCenterMixLevel = Objects.requireNonNull(level, "level");
            return this;
        }

        /**
         * Lets the Lo/Ro center mix level follow {@link Ac3Metadata#centerMixLevel()}.
         *
         * @return this builder
         */
        public Builder noLoroCenterMixLevel() {
            this.loroCenterMixLevel = null;
            return this;
        }

        /**
         * Sets the Lo/Ro surround mix level; see {@link Ac3Metadata#loroSurroundMixLevel()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder loroSurroundMixLevel(Ac3MixLevel level) {
            this.loroSurroundMixLevel = Objects.requireNonNull(level, "level");
            return this;
        }

        /**
         * Lets the Lo/Ro surround mix level follow {@link Ac3Metadata#surroundMixLevel()}.
         *
         * @return this builder
         */
        public Builder noLoroSurroundMixLevel() {
            this.loroSurroundMixLevel = null;
            return this;
        }

        /**
         * Sets the surround EX mode; see {@link Ac3Metadata#surroundExMode()}.
         *
         * @param mode the value
         * @return this builder
         */
        public Builder surroundExMode(Ac3SurroundExMode mode) {
            this.surroundExMode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Sets the Dolby Headphone mode; see {@link Ac3Metadata#headphoneMode()}.
         *
         * @param mode the value
         * @return this builder
         */
        public Builder headphoneMode(Ac3Indication mode) {
            this.headphoneMode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Sets the converter type; see {@link Ac3Metadata#converterType()}.
         *
         * @param converterType the value
         * @return this builder
         */
        public Builder converterType(Ac3ConverterType converterType) {
            this.converterType = Objects.requireNonNull(converterType, "converterType");
            return this;
        }

        /**
         * Sets the mixing level; see {@link Ac3Metadata#mixingLevel()}.
         *
         * @param level the value in dB SPL
         * @return this builder
         */
        public Builder mixingLevel(int level) {
            this.mixingLevel = level;
            return this;
        }

        /**
         * Leaves the mixing level, and with it the production information, undeclared.
         *
         * @return this builder
         */
        public Builder noMixingLevel() {
            this.mixingLevel = Integer.MIN_VALUE;
            return this;
        }

        /**
         * Sets the room type; see {@link Ac3Metadata#roomType()}.
         *
         * @param roomType the value
         * @return this builder
         */
        public Builder roomType(Ac3RoomType roomType) {
            this.roomType = Objects.requireNonNull(roomType, "roomType");
            return this;
        }

        /**
         * Sets the copyright flag; see {@link Ac3Metadata#copyright()}.
         *
         * @param copyright the value
         * @return this builder
         */
        public Builder copyright(boolean copyright) {
            this.copyright = copyright;
            return this;
        }

        /**
         * Sets the original flag; see {@link Ac3Metadata#original()}.
         *
         * @param original the value
         * @return this builder
         */
        public Builder original(boolean original) {
            this.original = original;
            return this;
        }

        /**
         * Sets the LFE mix level; see {@link Ac3Metadata#lfeMixLevel()}.
         *
         * @param level the value in dB
         * @return this builder
         */
        public Builder lfeMixLevel(int level) {
            this.lfeMixLevel = level;
            return this;
        }

        /**
         * Leaves the LFE mix level undeclared.
         *
         * @return this builder
         */
        public Builder noLfeMixLevel() {
            this.lfeMixLevel = Integer.MIN_VALUE;
            return this;
        }

        /**
         * Sets the program scale factor; see {@link Ac3Metadata#programScale()}.
         *
         * @param scale the value in dB
         * @return this builder
         */
        public Builder programScale(int scale) {
            this.programScale = scale;
            return this;
        }

        /**
         * Leaves the program scale factor undeclared.
         *
         * @return this builder
         */
        public Builder noProgramScale() {
            this.programScale = Integer.MIN_VALUE;
            return this;
        }

        /**
         * Sets the external program scale factor; see {@link Ac3Metadata#externalProgramScale()}.
         *
         * @param scale the value in dB
         * @return this builder
         */
        public Builder externalProgramScale(int scale) {
            this.externalProgramScale = scale;
            return this;
        }

        /**
         * Leaves the external program scale factor undeclared.
         *
         * @return this builder
         */
        public Builder noExternalProgramScale() {
            this.externalProgramScale = Integer.MIN_VALUE;
            return this;
        }

        /**
         * Sets whether the source was sampled at twice the coded rate; see
         * {@link Ac3Metadata#doubleRateSource()}.
         *
         * @param doubleRateSource the value
         * @return this builder
         */
        public Builder doubleRateSource(boolean doubleRateSource) {
            this.doubleRateSource = doubleRateSource;
            return this;
        }

        /**
         * Sets the profile of the dynamic range words; see {@link Ac3Metadata#lineModeProfile()}.
         *
         * @param profile the value
         * @return this builder
         */
        public Builder lineModeProfile(Ac3DynamicRangeProfile profile) {
            this.lineModeProfile = Objects.requireNonNull(profile, "profile");
            return this;
        }

        /**
         * Sets the profile of the heavy compression words; see {@link Ac3Metadata#rfModeProfile()}.
         *
         * @param profile the value
         * @return this builder
         */
        public Builder rfModeProfile(Ac3DynamicRangeProfile profile) {
            this.rfModeProfile = Objects.requireNonNull(profile, "profile");
            return this;
        }

        /**
         * Returns the metadata holding the settings of this builder.
         *
         * @return the metadata
         * @throws IllegalArgumentException if the center mix level is not -3, -4.5 or -6 dB, the
         * surround mix level not -3 dB, -6 dB or silence, an Lt/Rt or Lo/Ro surround level above
         * -1.5 dB, the mixing level outside [80, 111], a room type set without a mixing level, the
         * LFE mix level outside [-21, 10], or a program scale factor outside [-50, 12]
         */
        public Ac3Metadata build() {
            if (centerMixLevel != Ac3MixLevel.MINUS_3_DB && centerMixLevel != Ac3MixLevel.MINUS_4_5_DB
                    && centerMixLevel != Ac3MixLevel.MINUS_6_DB) {
                throw new IllegalArgumentException("the AC-3 center mix level is -3, -4.5 or -6 dB, not "
                        + centerMixLevel);
            }
            if (surroundMixLevel != Ac3MixLevel.MINUS_3_DB && surroundMixLevel != Ac3MixLevel.MINUS_6_DB
                    && surroundMixLevel != Ac3MixLevel.SILENT) {
                throw new IllegalArgumentException("the AC-3 surround mix level is -3 dB, -6 dB or silence, not "
                        + surroundMixLevel);
            }
            for (var level : new Ac3MixLevel[]{ltrtSurroundMixLevel, loroSurroundMixLevel}) {
                if (level != null && level.decibels() > -1.5) {
                    throw new IllegalArgumentException("a surround mix level is -1.5 dB or lower, not " + level);
                }
            }
            if (mixingLevel != Integer.MIN_VALUE) {
                Checks.inRange("mixing level", mixingLevel, 80, 111);
            } else if (roomType != Ac3RoomType.NOT_INDICATED) {
                throw new IllegalArgumentException("a room type needs a mixing level");
            }
            if (lfeMixLevel != Integer.MIN_VALUE) {
                Checks.inRange("LFE mix level", lfeMixLevel, -21, 10);
            }
            if (programScale != Integer.MIN_VALUE) {
                Checks.inRange("program scale", programScale, -50, 12);
            }
            if (externalProgramScale != Integer.MIN_VALUE) {
                Checks.inRange("external program scale", externalProgramScale, -50, 12);
            }
            return new Ac3Metadata(this);
        }
    }
}
