package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of a {@link PsMuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class PsMuxerOptions {
    /**
     * Enumerates the stream syntaxes.
     */
    public enum Syntax {
        /**
         * The program stream of ISO/IEC 13818-1 clause 2.5, with MPEG-2 pack and PES headers.
         */
        MPEG2,

        /**
         * The system stream of ISO/IEC 11172-1, with MPEG-1 pack and packet headers, as VCD uses it.
         */
        MPEG1
    }

    /**
     * The smallest pack size.
     */
    static final int MIN_PACK_BYTES = 256;

    /**
     * The largest pack size: a pack header and a PES packet of the largest length.
     */
    static final int MAX_PACK_BYTES = 65_536;

    /**
     * The mux rate of DVD-Video, in bits per second.
     */
    static final long DVD_MUX_RATE = 10_080_000;

    /**
     * The options of {@link #defaults()}.
     */
    private static final PsMuxerOptions DEFAULTS = new PsMuxerOptions(Syntax.MPEG2, 2048, false, DVD_MUX_RATE,
            500_000, false, false);

    /**
     * The options of {@link #forDvd()}.
     */
    private static final PsMuxerOptions DVD = new PsMuxerOptions(Syntax.MPEG2, 2048, true, DVD_MUX_RATE, 500_000,
            true, false);

    private final Syntax syntax;
    private final int packBytes;
    private final boolean fixedPackSize;
    private final long muxRate;
    private final long delayMicros;
    private final boolean navigationPacks;
    private final boolean programStreamMap;

    private PsMuxerOptions(Syntax syntax, int packBytes, boolean fixedPackSize, long muxRate, long delayMicros,
                           boolean navigationPacks, boolean programStreamMap) {
        this.syntax = syntax;
        this.packBytes = packBytes;
        this.fixedPackSize = fixedPackSize;
        this.muxRate = muxRate;
        this.delayMicros = delayMicros;
        this.navigationPacks = navigationPacks;
        this.programStreamMap = programStreamMap;
    }

    /**
     * Returns the default options: an MPEG-2 program stream of packs of at most 2048 bytes, shorter where a stream's
     * data ends, a mux rate of 10.08 Mbit/s and a 500 ms multiplexing delay, without navigation packs.
     *
     * @return the default options
     */
    public static PsMuxerOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the options of a DVD-Video video object ({@code .vob}): 2048-byte packs, each a logical block of the
     * disc, a navigation pack before every video key frame, a mux rate of 10.08 Mbit/s and a 500 ms multiplexing
     * delay. The muxer then accepts only the codecs of DVD-Video.
     *
     * @return the DVD-Video options
     */
    public static PsMuxerOptions forDvd() {
        return DVD;
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
     * {@return the stream syntax; {@link Syntax#MPEG2} by default}
     */
    public Syntax syntax() {
        return syntax;
    }

    /**
     * {@return the largest pack, in bytes, from 256 to 65536; 2048 by default}
     */
    public int packBytes() {
        return packBytes;
    }

    /**
     * {@return whether every pack is padded to {@link #packBytes()}, as the sectors of an optical disc need;
     * {@code false} by default, {@code true} in {@link #forDvd()}}
     */
    public boolean fixedPackSize() {
        return fixedPackSize;
    }

    /**
     * {@return the rate at which the pack headers declare the stream to arrive ({@code program_mux_rate}), which
     * paces the system clock references, in bits per second; a multiple of 400 from 400 to 1677721200; 10080000 by
     * default}
     */
    public long muxRate() {
        return muxRate;
    }

    /**
     * {@return the delay between the system clock reference of a pack and the decoding time of the access unit it
     * starts, in microseconds, from 0 to 10 s; the first decoding time is this delay; 500000 by default}
     */
    public long delayMicros() {
        return delayMicros;
    }

    /**
     * {@return whether a DVD-Video navigation pack (the presentation control and data search information of private
     * stream 2) opens every video object unit, before each video key frame; {@code false} by default, {@code true} in
     * {@link #forDvd()}}
     */
    public boolean navigationPacks() {
        return navigationPacks;
    }

    /**
     * {@return whether a program stream map is written even when every stream is identified by its stream id, so that
     * the languages of the tracks are stored; {@code false} by default, when a map is written only for the streams
     * that need it; only for the MPEG-2 syntax without navigation packs}
     */
    public boolean programStreamMap() {
        return programStreamMap;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PsMuxerOptions that && syntax == that.syntax && packBytes == that.packBytes
                && fixedPackSize == that.fixedPackSize && muxRate == that.muxRate && delayMicros == that.delayMicros
                && navigationPacks == that.navigationPacks && programStreamMap == that.programStreamMap;
    }

    @Override
    public int hashCode() {
        return Objects.hash(syntax, packBytes, fixedPackSize, muxRate, delayMicros, navigationPacks, programStreamMap);
    }

    @Override
    public String toString() {
        return "PsMuxerOptions[syntax=" + syntax + ", packBytes=" + packBytes + ", fixedPackSize=" + fixedPackSize
                + ", muxRate=" + muxRate + ", delayMicros=" + delayMicros + ", navigationPacks=" + navigationPacks
                + ", programStreamMap=" + programStreamMap + "]";
    }

    /**
     * Builds {@link PsMuxerOptions}. The setters store their value; {@link #build()} checks the ranges and the
     * combination. Instances are not thread-safe.
     */
    public static final class Builder {
        private Syntax syntax;
        private int packBytes;
        private boolean fixedPackSize;
        private long muxRate;
        private long delayMicros;
        private boolean navigationPacks;
        private boolean programStreamMap;

        private Builder(PsMuxerOptions options) {
            this.syntax = options.syntax;
            this.packBytes = options.packBytes;
            this.fixedPackSize = options.fixedPackSize;
            this.muxRate = options.muxRate;
            this.delayMicros = options.delayMicros;
            this.navigationPacks = options.navigationPacks;
            this.programStreamMap = options.programStreamMap;
        }

        /**
         * Sets the stream syntax; see {@link PsMuxerOptions#syntax()}.
         *
         * @param syntax the value
         * @return this builder
         */
        public Builder syntax(Syntax syntax) {
            this.syntax = Objects.requireNonNull(syntax, "syntax");
            return this;
        }

        /**
         * Sets the largest pack; see {@link PsMuxerOptions#packBytes()}.
         *
         * @param bytes the value
         * @return this builder
         */
        public Builder packBytes(int bytes) {
            this.packBytes = bytes;
            return this;
        }

        /**
         * Sets whether every pack is padded to the pack size; see {@link PsMuxerOptions#fixedPackSize()}.
         *
         * @param fixed the value
         * @return this builder
         */
        public Builder fixedPackSize(boolean fixed) {
            this.fixedPackSize = fixed;
            return this;
        }

        /**
         * Sets the mux rate; see {@link PsMuxerOptions#muxRate()}.
         *
         * @param bitsPerSecond the value
         * @return this builder
         */
        public Builder muxRate(long bitsPerSecond) {
            this.muxRate = bitsPerSecond;
            return this;
        }

        /**
         * Sets the multiplexing delay; see {@link PsMuxerOptions#delayMicros()}.
         *
         * @param micros the value
         * @return this builder
         */
        public Builder delayMicros(long micros) {
            this.delayMicros = micros;
            return this;
        }

        /**
         * Sets whether navigation packs are written; see {@link PsMuxerOptions#navigationPacks()}.
         *
         * @param navigation the value
         * @return this builder
         */
        public Builder navigationPacks(boolean navigation) {
            this.navigationPacks = navigation;
            return this;
        }

        /**
         * Sets whether a program stream map is always written; see {@link PsMuxerOptions#programStreamMap()}.
         *
         * @param always the value
         * @return this builder
         */
        public Builder programStreamMap(boolean always) {
            this.programStreamMap = always;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if a value is out of its range, the mux rate is not a multiple of 400,
         * navigation packs are asked for without the MPEG-2 syntax and fixed 2048-byte packs or with a program stream
         * map, which DVD-Video objects do not carry, or a program stream map is asked for with the MPEG-1 syntax
         */
        public PsMuxerOptions build() {
            Checks.inRange("pack size", packBytes, MIN_PACK_BYTES, MAX_PACK_BYTES);
            Checks.inRange("mux rate", muxRate, 400, 400L * ((1 << 22) - 1));
            if (muxRate % 400 != 0) {
                throw new IllegalArgumentException("the mux rate must be a multiple of 400 bits per second");
            }
            Checks.inRange("multiplexing delay", delayMicros, 0, 10_000_000L);
            if (navigationPacks && (syntax != Syntax.MPEG2 || !fixedPackSize || packBytes != 2048)) {
                throw new IllegalArgumentException("navigation packs need the MPEG-2 syntax and fixed 2048-byte packs");
            }
            if (programStreamMap && (navigationPacks || syntax != Syntax.MPEG2)) {
                throw new IllegalArgumentException("a program stream map is written in MPEG-2 program streams without"
                        + " navigation packs only");
            }
            return new PsMuxerOptions(syntax, packBytes, fixedPackSize, muxRate, delayMicros, navigationPacks,
                    programStreamMap);
        }
    }
}
