package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link TsDemuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class TsDemuxerOptions {
    /**
     * The default probe window, in bytes.
     */
    public static final long DEFAULT_PROBE_BYTES = 8L << 20;

    /**
     * The smallest probe window, in bytes.
     */
    private static final long MIN_PROBE_BYTES = 64 * 1024;

    /**
     * The options of {@link #defaults()}.
     */
    private static final TsDemuxerOptions DEFAULTS = new TsDemuxerOptions(MediaLimits.DEFAULT, 0, DEFAULT_PROBE_BYTES);

    private final MediaLimits limits;

    /**
     * The requested {@code program_number}, or {@code 0} for the first program.
     */
    private final int program;

    private final long probeBytes;

    private TsDemuxerOptions(MediaLimits limits, int program, long probeBytes) {
        this.limits = limits;
        this.program = program;
        this.probeBytes = probeBytes;
    }

    /**
     * Returns options that select the first program, probe the first
     * {@value #DEFAULT_PROBE_BYTES} bytes and enforce {@link MediaLimits#defaults()}.
     *
     * @return the default options
     */
    public static TsDemuxerOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options carrying the limits of generic demuxer options and the defaults otherwise.
     *
     * @param options the generic options
     * @return the options
     */
    public static TsDemuxerOptions of(DemuxerOptions options) {
        return DEFAULTS.toBuilder().limits(options.limits()).build();
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
     * {@return the resource bounds the demuxer enforces; {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the {@code program_number} of the program the demuxer exposes, from 1 to 65535, or empty
     * for the first program of the program association table that has an elementary stream; empty by
     * default}
     *
     * <p>A transport stream may multiplex several programs (several channels of a broadcast); the
     * demuxer exposes the elementary streams of one of them.
     */
    public OptionalInt program() {
        return program == 0 ? OptionalInt.empty() : OptionalInt.of(program);
    }

    /**
     * {@return the number of leading bytes read to describe the tracks; at least 64 KiB;
     * {@value #DEFAULT_PROBE_BYTES} by default}
     *
     * <p>A stream whose format cannot be determined from them (an H.264 stream without a parameter
     * set, an audio stream without a frame) is not exposed.
     */
    public long probeBytes() {
        return probeBytes;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TsDemuxerOptions that && limits.equals(that.limits) && program == that.program
                && probeBytes == that.probeBytes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, program, probeBytes);
    }

    @Override
    public String toString() {
        return "TsDemuxerOptions[limits=" + limits + ", program=" + (program == 0 ? "first" : program)
                + ", probeBytes=" + probeBytes + "]";
    }

    /**
     * Assembles {@link TsDemuxerOptions} setting by setting.
     *
     * <p>Setters reject {@code null} and store numbers unchecked; {@link #build()} checks the ranges.
     * Instances are not thread-safe.
     */
    public static final class Builder {
        /**
         * The builder value of the first-program selection; outside the {@code int} range.
         */
        private static final long FIRST_PROGRAM = Long.MIN_VALUE;

        private MediaLimits limits;
        private long program;
        private long probeBytes;

        private Builder(TsDemuxerOptions options) {
            this.limits = options.limits;
            this.program = options.program == 0 ? FIRST_PROGRAM : options.program;
            this.probeBytes = options.probeBytes;
        }

        /**
         * Sets the resource bounds; see {@link TsDemuxerOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Selects a program by number; see {@link TsDemuxerOptions#program()}.
         *
         * @param number the value
         * @return this builder
         */
        public Builder program(int number) {
            this.program = number;
            return this;
        }

        /**
         * Selects the first program with an elementary stream; see {@link TsDemuxerOptions#program()}.
         *
         * @return this builder
         */
        public Builder firstProgram() {
            this.program = FIRST_PROGRAM;
            return this;
        }

        /**
         * Sets the probe window; see {@link TsDemuxerOptions#probeBytes()}.
         *
         * @param bytes the value
         * @return this builder
         */
        public Builder probeBytes(long bytes) {
            this.probeBytes = bytes;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the program number is outside 1 to 65535 or the probe
         * window is below 64 KiB
         */
        public TsDemuxerOptions build() {
            var number = program == FIRST_PROGRAM ? 0 : Checks.inRange("program number", (int) program, 1, 0xFFFF);
            return new TsDemuxerOptions(limits, number,
                    Checks.inRange("probe window", probeBytes, MIN_PROBE_BYTES, Long.MAX_VALUE));
        }
    }
}
