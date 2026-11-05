package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an audio decoder created by {@link AudioDecoders}. Instances are immutable
 * and thread-safe; create them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AudioDecoderOptions {
    private static final AudioDecoderOptions DEFAULTS = new AudioDecoderOptions(MediaLimits.DEFAULT, 0, false, false);

    private final MediaLimits limits;
    private final int preferredSampleRate;
    private final boolean verifyIntegrity;
    private final boolean dsdOverPcm;

    private AudioDecoderOptions(MediaLimits limits, int preferredSampleRate, boolean verifyIntegrity,
                                boolean dsdOverPcm) {
        this.limits = limits;
        this.preferredSampleRate = preferredSampleRate;
        this.verifyIntegrity = verifyIntegrity;
        this.dsdOverPcm = dsdOverPcm;
    }

    /**
     * Returns decoding at the codec's native rate under {@link MediaLimits#DEFAULT}.
     *
     * @return the default options
     */
    public static AudioDecoderOptions defaults() {
        return DEFAULTS;
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
     * {@return the resource bounds the decoder enforces; {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the rate in hertz the caller will resample the output to, positive, or empty to decode
     * at the codec's native rate; empty by default}
     *
     * <p>A codec that decodes natively at several rates (Opus, at 8, 12, 16, 24 or 48 kHz) decodes at the preferred
     * rate when it supports that rate. DSD, DST and DSD over PCM ({@link #dsdOverPcm()}) are converted to PCM at the
     * DSD rate divided by a power of two from 8 to 512, by default 16, and with a preferred rate by the ratio that
     * gives the lowest rate at or above it. Every other codec ignores the preference and outputs its native rate, so
     * callers check {@link AudioDecoder#sampleRate()}.
     */
    public OptionalInt preferredSampleRate() {
        return preferredSampleRate == 0 ? OptionalInt.empty() : OptionalInt.of(preferredSampleRate);
    }

    /**
     * {@return whether the decoder checks the whole-stream checksum the stream carries; {@code false} by default}
     *
     * <p>FLAC carries one, the MD5 signature of {@code STREAMINFO}, and WavPack its MD5 signature metadata. When it is
     * set (not all zeros) and the first frame decoded starts the stream, the flush compares the signature with the
     * decoded samples and raises
     * {@link com.github.auties00.smalt.MediaException.Malformed} on a mismatch, as it does when frames were lost or
     * repeated or the decode ended before the declared length. A decode that starts after the first sample is not
     * checked. The check hashes every decoded sample, a cost linear in the length of the stream.
     */
    public boolean verifyIntegrity() {
        return verifyIntegrity;
    }

    /**
     * {@return whether integer PCM that carries DSD over PCM (DoP 1.1) is decoded as DSD; {@code false} by default}
     *
     * <p>DSD over PCM carries 16 DSD samples of a channel in each 24-bit PCM sample: the marker {@code 0x05} or
     * {@code 0xFA} in the top byte, alternating from frame to frame, then two bytes of DSD, the oldest sample in the
     * most significant bit. When this is set, {@link AudioDecoders} decodes a track of a codec with integer output of
     * 24 or 32 bits (integer PCM, FLAC, ALAC, WavPack) through a decoder that inspects the first block: when every
     * frame of it carries the alternating markers in every channel, the stream is DSD at 16 times the PCM rate, which
     * is converted to PCM as {@link #preferredSampleRate()} describes (at the PCM rate by default); otherwise the PCM
     * is output unchanged. Such a decoder has no integer output. A stream whose DSD starts after its first block is
     * decoded as PCM.
     */
    public boolean dsdOverPcm() {
        return dsdOverPcm;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AudioDecoderOptions that
                && limits.equals(that.limits)
                && preferredSampleRate == that.preferredSampleRate
                && verifyIntegrity == that.verifyIntegrity
                && dsdOverPcm == that.dsdOverPcm;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, preferredSampleRate, verifyIntegrity, dsdOverPcm);
    }

    @Override
    public String toString() {
        return "AudioDecoderOptions[limits=" + limits + ", preferredSampleRate=" + preferredSampleRate
                + ", verifyIntegrity=" + verifyIntegrity + ", dsdOverPcm=" + dsdOverPcm + "]";
    }

    /**
     * Builds {@link AudioDecoderOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;
        private OptionalInt preferredSampleRate;
        private boolean verifyIntegrity;
        private boolean dsdOverPcm;

        private Builder(AudioDecoderOptions options) {
            this.limits = options.limits;
            this.preferredSampleRate = options.preferredSampleRate();
            this.verifyIntegrity = options.verifyIntegrity;
            this.dsdOverPcm = options.dsdOverPcm;
        }

        /**
         * Sets the resource bounds; see {@link AudioDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the preferred output rate; see {@link AudioDecoderOptions#preferredSampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder preferredSampleRate(int sampleRate) {
            this.preferredSampleRate = OptionalInt.of(sampleRate);
            return this;
        }

        /**
         * Clears the preferred output rate, so the codec decodes at its native rate; see
         * {@link AudioDecoderOptions#preferredSampleRate()}.
         *
         * @return this builder
         */
        public Builder nativeSampleRate() {
            this.preferredSampleRate = OptionalInt.empty();
            return this;
        }

        /**
         * Sets whether the whole-stream checksum is checked; see {@link AudioDecoderOptions#verifyIntegrity()}.
         *
         * @param verify the value
         * @return this builder
         */
        public Builder verifyIntegrity(boolean verify) {
            this.verifyIntegrity = verify;
            return this;
        }

        /**
         * Sets whether DSD over PCM is decoded as DSD; see {@link AudioDecoderOptions#dsdOverPcm()}.
         *
         * @param decode the value
         * @return this builder
         */
        public Builder dsdOverPcm(boolean decode) {
            this.dsdOverPcm = decode;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the preferred sample rate is not positive
         */
        public AudioDecoderOptions build() {
            var rate = preferredSampleRate.isPresent()
                    ? Checks.positive("preferred sample rate", preferredSampleRate.getAsInt()) : 0;
            return new AudioDecoderOptions(limits, rate, verifyIntegrity, dsdOverPcm);
        }
    }
}
