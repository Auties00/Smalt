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
    private static final TsDemuxerOptions DEFAULTS = new TsDemuxerOptions(MediaLimits.DEFAULT, 0, DEFAULT_PROBE_BYTES,
            SampleAesKeys.none());

    private final MediaLimits limits;

    /**
     * The requested {@code program_number}, or {@code 0} for the first program.
     */
    private final int program;

    private final long probeBytes;
    private final SampleAesKeys sampleAesKeys;

    private TsDemuxerOptions(MediaLimits limits, int program, long probeBytes, SampleAesKeys sampleAesKeys) {
        this.limits = limits;
        this.program = program;
        this.probeBytes = probeBytes;
        this.sampleAesKeys = sampleAesKeys;
    }

    /**
     * Returns options that select the first program, probe the first
     * {@value #DEFAULT_PROBE_BYTES} bytes, enforce {@link MediaLimits#defaults()} and decrypt nothing.
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

    /**
     * {@return the keys that decrypt the elementary streams encrypted in the HLS {@code SAMPLE-AES} format;
     * {@link SampleAesKeys#none()} by default}
     *
     * <p>A {@code SAMPLE-AES} stream (Apple's MPEG-2 Stream Encryption Format for HTTP Live Streaming) is an H.264
     * ({@code stream_type 0xDB}), AAC in ADTS ({@code 0xCF}), AC-3 ({@code 0xC1}) or E-AC-3 ({@code 0xC2}) stream
     * whose units are partly encrypted with AES-128 in CBC mode, the chain restarting from the initialization
     * vector at each unit: the slices (NAL unit types 1 and 5) of more than 48 bytes, whose first 32 bytes stay
     * clear and then one 16-byte block in ten while more than 16 bytes are left, after the emulation prevention
     * bytes added over the encrypted unit are removed; the whole blocks after the header and a 16-byte leader of an
     * ADTS frame; and the whole blocks after the 16-byte leader of each AC-3 or E-AC-3 syncframe, the chain running
     * on through the syncframes of one E-AC-3 frame. The demuxer decrypts each unit with the key these keys give
     * for its PES packet, so the tracks and packets are those of the clear stream; the audio setup information of
     * the program map ({@code apad}) gives the priming of audio tracks as their
     * {@link com.github.auties00.smalt.track.AudioTrack#skipSamples() skipped samples}, and the
     * {@code AudioSpecificConfig} of HE-AAC streams, which ADTS headers cannot signal. With
     * {@link SampleAesKeys#none()}, or when no key is known for a unit, an encrypted stream raises
     * {@link com.github.auties00.smalt.MediaException.Unsupported} when its first unit is read, which for most
     * streams is when the demuxer is created; the first syncframe an AC-3 or E-AC-3 key decrypts is checked
     * against its CRC, so a wrong key raises {@link com.github.auties00.smalt.MediaException.Malformed}. A wrong
     * key for other streams yields packets that do not decode.
     */
    public SampleAesKeys sampleAesKeys() {
        return sampleAesKeys;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TsDemuxerOptions that && limits.equals(that.limits) && program == that.program
                && probeBytes == that.probeBytes && sampleAesKeys.equals(that.sampleAesKeys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, program, probeBytes, sampleAesKeys);
    }

    @Override
    public String toString() {
        return "TsDemuxerOptions[limits=" + limits + ", program=" + (program == 0 ? "first" : program)
                + ", probeBytes=" + probeBytes + (sampleAesKeys.equals(SampleAesKeys.none()) ? ""
                : ", sampleAes=true") + "]";
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
        private SampleAesKeys sampleAesKeys;

        private Builder(TsDemuxerOptions options) {
            this.limits = options.limits;
            this.program = options.program == 0 ? FIRST_PROGRAM : options.program;
            this.probeBytes = options.probeBytes;
            this.sampleAesKeys = options.sampleAesKeys;
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
         * Sets the keys of {@code SAMPLE-AES} streams; see {@link TsDemuxerOptions#sampleAesKeys()}.
         *
         * @param keys the keys, {@link SampleAesKeys#none()} to decrypt nothing
         * @return this builder
         */
        public Builder sampleAesKeys(SampleAesKeys keys) {
            this.sampleAesKeys = Objects.requireNonNull(keys, "keys");
            return this;
        }

        /**
         * Decrypts {@code SAMPLE-AES} streams with one key; see {@link TsDemuxerOptions#sampleAesKeys()}.
         *
         * @param key the 16-byte AES-128 key, copied
         * @param initializationVector the 16-byte initialization vector, copied
         * @return this builder
         * @throws IllegalArgumentException if the key or the initialization vector is not 16 bytes long
         */
        public Builder sampleAes(byte[] key, byte[] initializationVector) {
            this.sampleAesKeys = SampleAesKeys.of(SampleAesKey.of(key, initializationVector));
            return this;
        }

        /**
         * Decrypts nothing, the default; see {@link TsDemuxerOptions#sampleAesKeys()}.
         *
         * @return this builder
         */
        public Builder noSampleAes() {
            this.sampleAesKeys = SampleAesKeys.none();
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
                    Checks.inRange("probe window", probeBytes, MIN_PROBE_BYTES, Long.MAX_VALUE), sampleAesKeys);
        }
    }
}
