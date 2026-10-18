package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of a {@link TsMuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class TsMuxerOptions {
    /**
     * Enumerates the packet layouts.
     */
    public enum PacketFormat {
        /**
         * Plain 188-byte transport packets ({@code .ts}).
         */
        TS,

        /**
         * 192-byte packets: a four-byte {@code TP_extra_header} holding a 30-bit arrival time
         * stamp on the 27 MHz clock, then the transport packet ({@code .m2ts}, {@code .mts}).
         */
        M2TS
    }

    /**
     * The options of {@link #defaults()}.
     */
    private static final TsMuxerOptions DEFAULTS = new TsMuxerOptions(PacketFormat.TS, 0, 100_000, 40_000, 700_000,
            1, 0x1000, 0x100, 6_000_000);

    /**
     * The options of {@link #m2ts()}.
     */
    private static final TsMuxerOptions M2TS = DEFAULTS.toBuilder().packetFormat(PacketFormat.M2TS).build();

    private final PacketFormat packetFormat;

    /**
     * The constant bit rate in bits per second, or {@code 0} for a variable rate.
     */
    private final long muxRate;

    private final long psiIntervalMicros;
    private final long pcrIntervalMicros;
    private final long delayMicros;
    private final int programNumber;
    private final int pmtPid;
    private final int firstPid;
    private final long segmentMicros;

    private TsMuxerOptions(PacketFormat packetFormat, long muxRate, long psiIntervalMicros, long pcrIntervalMicros,
                           long delayMicros, int programNumber, int pmtPid, int firstPid, long segmentMicros) {
        this.packetFormat = packetFormat;
        this.muxRate = muxRate;
        this.psiIntervalMicros = psiIntervalMicros;
        this.pcrIntervalMicros = pcrIntervalMicros;
        this.delayMicros = delayMicros;
        this.programNumber = programNumber;
        this.pmtPid = pmtPid;
        this.firstPid = firstPid;
        this.segmentMicros = segmentMicros;
    }

    /**
     * Returns the default options: 188-byte packets at a variable rate, with the program
     * association and map tables every 100 ms, a program clock reference at least every 40 ms, a
     * 700 ms multiplexing delay, program number 1 with its map table on PID {@code 0x1000} and
     * elementary streams from PID {@code 0x100}, as most transport stream writers do.
     *
     * @return the default options
     */
    public static TsMuxerOptions defaults() {
        return DEFAULTS;
    }

    /**
     * {@return the default options with the 192-byte packets of Blu-ray and AVCHD files}
     */
    public static TsMuxerOptions m2ts() {
        return M2TS;
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
     * {@return a builder initialized with these options}
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the packet layout; {@link PacketFormat#TS} by default}
     */
    public PacketFormat packetFormat() {
        return packetFormat;
    }

    /**
     * {@return the constant bit rate the output is padded to with null packets, as broadcast
     * multiplexers need, in bits per second; at least 64000, or 0 for a variable rate; 0 by default}
     */
    public long muxRate() {
        return muxRate;
    }

    /**
     * {@return the interval between repetitions of the program association and map tables, in
     * microseconds; from 1 to 10000000; 100 ms by default}
     */
    public long psiIntervalMicros() {
        return psiIntervalMicros;
    }

    /**
     * {@return the longest interval between program clock references, in microseconds; from 1 to
     * 100000 (100 ms, ISO/IEC 13818-1 clause 2.7.2); 40 ms by default}
     */
    public long pcrIntervalMicros() {
        return pcrIntervalMicros;
    }

    /**
     * {@return the multiplexing delay, how far the program clock runs behind the decoding times, in
     * microseconds; from 0 to 10000000; 700 ms by default}
     *
     * <p>The timestamps of the first packet are shifted to this delay.
     */
    public long delayMicros() {
        return delayMicros;
    }

    /**
     * {@return the {@code program_number} of the single program; from 1 to 65535; 1 by default}
     */
    public int programNumber() {
        return programNumber;
    }

    /**
     * {@return the PID of the program map table; from {@code 0x10} to {@code 0x1FFE}; {@code 0x1000}
     * by default}
     */
    public int pmtPid() {
        return pmtPid;
    }

    /**
     * {@return the PID of the first elementary stream, later streams taking the next PIDs; from
     * {@code 0x10} to {@code 0x1F00}; {@code 0x100} by default}
     */
    public int firstPid() {
        return firstPid;
    }

    /**
     * {@return the shortest segment a segmented output cuts, in microseconds; positive; six seconds by
     * default}
     *
     * <p>A segment ends at the first key frame of the reference track once it lasts this long.
     */
    public long segmentMicros() {
        return segmentMicros;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TsMuxerOptions that && packetFormat == that.packetFormat && muxRate == that.muxRate
                && psiIntervalMicros == that.psiIntervalMicros && pcrIntervalMicros == that.pcrIntervalMicros
                && delayMicros == that.delayMicros && programNumber == that.programNumber && pmtPid == that.pmtPid
                && firstPid == that.firstPid && segmentMicros == that.segmentMicros;
    }

    @Override
    public int hashCode() {
        return Objects.hash(packetFormat, muxRate, psiIntervalMicros, pcrIntervalMicros, delayMicros, programNumber, pmtPid,
                firstPid, segmentMicros);
    }

    @Override
    public String toString() {
        return "TsMuxerOptions[packetFormat=" + packetFormat + ", muxRate=" + muxRate + ", psiIntervalMicros="
                + psiIntervalMicros + ", pcrIntervalMicros=" + pcrIntervalMicros + ", delayMicros=" + delayMicros
                + ", programNumber=" + programNumber + ", pmtPid=" + pmtPid + ", firstPid=" + firstPid
                + ", segmentMicros=" + segmentMicros + "]";
    }

    /**
     * Assembles {@link TsMuxerOptions} setting by setting.
     *
     * <p>Setters reject {@code null} and store numbers unchecked; {@link #build()} checks the ranges.
     * Instances are not thread-safe.
     */
    public static final class Builder {
        /**
         * The builder value of a variable rate; never a rate a caller can set.
         */
        private static final long VARIABLE_RATE = Long.MIN_VALUE;

        private PacketFormat packetFormat;
        private long muxRate;
        private long psiIntervalMicros;
        private long pcrIntervalMicros;
        private long delayMicros;
        private int programNumber;
        private int pmtPid;
        private int firstPid;
        private long segmentMicros;

        private Builder(TsMuxerOptions options) {
            this.packetFormat = options.packetFormat;
            this.muxRate = options.muxRate == 0 ? VARIABLE_RATE : options.muxRate;
            this.psiIntervalMicros = options.psiIntervalMicros;
            this.pcrIntervalMicros = options.pcrIntervalMicros;
            this.delayMicros = options.delayMicros;
            this.programNumber = options.programNumber;
            this.pmtPid = options.pmtPid;
            this.firstPid = options.firstPid;
            this.segmentMicros = options.segmentMicros;
        }

        /**
         * Sets the packet layout; see {@link TsMuxerOptions#packetFormat()}.
         *
         * @param format the value
         * @return this builder
         */
        public Builder packetFormat(PacketFormat format) {
            this.packetFormat = Objects.requireNonNull(format, "format");
            return this;
        }

        /**
         * Sets the constant bit rate; see {@link TsMuxerOptions#muxRate()}.
         *
         * @param bitsPerSecond the value, at least 64000
         * @return this builder
         */
        public Builder muxRate(long bitsPerSecond) {
            this.muxRate = bitsPerSecond;
            return this;
        }

        /**
         * Writes the stream at a variable rate; see {@link TsMuxerOptions#muxRate()}.
         *
         * @return this builder
         */
        public Builder noMuxRate() {
            this.muxRate = VARIABLE_RATE;
            return this;
        }

        /**
         * Sets the program table interval; see {@link TsMuxerOptions#psiIntervalMicros()}.
         *
         * @param micros the value
         * @return this builder
         */
        public Builder psiIntervalMicros(long micros) {
            this.psiIntervalMicros = micros;
            return this;
        }

        /**
         * Sets the longest clock reference interval; see {@link TsMuxerOptions#pcrIntervalMicros()}.
         *
         * @param micros the value
         * @return this builder
         */
        public Builder pcrIntervalMicros(long micros) {
            this.pcrIntervalMicros = micros;
            return this;
        }

        /**
         * Sets the multiplexing delay; see {@link TsMuxerOptions#delayMicros()}.
         *
         * @param micros the value
         * @return this builder
         */
        public Builder delayMicros(long micros) {
            this.delayMicros = micros;
            return this;
        }

        /**
         * Sets the program number; see {@link TsMuxerOptions#programNumber()}.
         *
         * @param number the value
         * @return this builder
         */
        public Builder programNumber(int number) {
            this.programNumber = number;
            return this;
        }

        /**
         * Sets the PID of the program map table; see {@link TsMuxerOptions#pmtPid()}.
         *
         * @param pid the value
         * @return this builder
         */
        public Builder pmtPid(int pid) {
            this.pmtPid = pid;
            return this;
        }

        /**
         * Sets the PID of the first elementary stream; see {@link TsMuxerOptions#firstPid()}.
         *
         * @param pid the value
         * @return this builder
         */
        public Builder firstPid(int pid) {
            this.firstPid = pid;
            return this;
        }

        /**
         * Sets the shortest segment; see {@link TsMuxerOptions#segmentMicros()}.
         *
         * @param micros the value
         * @return this builder
         */
        public Builder segmentMicros(long micros) {
            this.segmentMicros = micros;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if a value is outside the range its accessor documents
         */
        public TsMuxerOptions build() {
            var rate = muxRate == VARIABLE_RATE ? 0 : Checks.inRange("mux rate", muxRate, 64_000, Long.MAX_VALUE);
            return new TsMuxerOptions(packetFormat, rate,
                    Checks.inRange("program table interval", psiIntervalMicros, 1, 10_000_000L),
                    Checks.inRange("clock reference interval", pcrIntervalMicros, 1, 100_000L),
                    Checks.inRange("multiplexing delay", delayMicros, 0, 10_000_000L),
                    Checks.inRange("program number", programNumber, 1, 0xFFFF),
                    Checks.inRange("program map PID", pmtPid, 0x10, 0x1FFE),
                    Checks.inRange("first elementary PID", firstPid, 0x10, 0x1F00),
                    Checks.positive("segment duration", segmentMicros));
        }
    }
}
