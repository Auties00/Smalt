package com.github.auties00.smalt.audio.adpcm;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.Codec;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link AdpcmEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AdpcmEncoderOptions {
    /**
     * The largest number of channels.
     */
    static final int MAX_CHANNELS = 8;

    /**
     * The largest WAVE block, the limit of the format's 16-bit block alignment.
     */
    static final int MAX_BLOCK_BYTES = 0xFFFF;

    /**
     * The most samples per channel of a WAVE block, the limit of the format's 16-bit {@code wSamplesPerBlock}.
     */
    static final int MAX_BLOCK_SAMPLES = 0xFFFF;

    /**
     * The options of {@link #defaults()}.
     */
    private static final AdpcmEncoderOptions DEFAULTS = new Builder().build();

    private final Codec codec;
    private final int sampleRate;
    private final ChannelLayout layout;
    private final int blockBytes;

    /**
     * Constructs options from the settings of a builder.
     *
     * @param builder the builder, already checked
     */
    private AdpcmEncoderOptions(Builder builder) {
        this.codec = builder.codec;
        this.sampleRate = builder.sampleRate;
        this.layout = builder.layout;
        this.blockBytes = builder.blockBytes;
    }

    /**
     * Returns the options for {@link Codec#ADPCM_IMA_WAV} at 44.1 kHz in stereo, in blocks of the automatic size.
     *
     * @return the default options
     */
    public static AdpcmEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the options for a codec and input format, in blocks of the automatic size.
     *
     * @param codec {@link Codec#ADPCM_IMA_WAV}, {@link Codec#ADPCM_IMA_QT} or {@link Codec#ADPCM_MS}
     * @param sampleRate the sampling rate, in hertz, positive
     * @param layout the channel layout, of 1 to 8 channels
     * @return the options
     * @throws IllegalArgumentException if a value is out of range
     */
    public static AdpcmEncoderOptions of(Codec codec, int sampleRate, ChannelLayout layout) {
        return new Builder().codec(codec).format(sampleRate, layout).build();
    }

    /**
     * {@return a new builder with the default settings}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        var builder = new Builder();
        builder.codec = codec;
        builder.sampleRate = sampleRate;
        builder.layout = layout;
        builder.blockBytes = blockBytes;
        return builder;
    }

    /**
     * {@return the codec, {@link Codec#ADPCM_IMA_WAV}, {@link Codec#ADPCM_IMA_QT} or {@link Codec#ADPCM_MS};
     * {@code ADPCM_IMA_WAV} by default}
     */
    public Codec codec() {
        return codec;
    }

    /**
     * {@return the input sampling rate, in hertz; 44100 by default}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the input channel layout, of 1 to 8 channels; {@link ChannelLayout#STEREO} by default}
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * Returns the size of every WAVE block but the last, or empty for the automatic size.
     *
     * <p>A block of {@code B} bytes and {@code C} channels codes {@code 1 + 8 * (B - 4C) / (4C)} samples per channel
     * in IMA ADPCM, which needs {@code B - 4C} to be a positive multiple of {@code 4C}, and
     * {@code 2 + 2 * (B - 7C) / C} in Microsoft ADPCM, which needs {@code B} above {@code 7C} and
     * {@code 2 * (B - 7C)} a multiple of {@code C}; no block exceeds 65535 bytes or 65535 samples per channel. The
     * automatic size is 256 bytes per channel up to 11025 Hz, and that times the sampling rate divided by 11025,
     * rounded down, above it, reduced by 256 bytes per channel while it exceeds either limit. The
     * {@code ima4} layout has fixed frames of 34 bytes per channel and ignores this setting.
     *
     * @return the block size in bytes, or empty for the automatic size
     */
    public OptionalInt blockBytes() {
        return blockBytes == 0 ? OptionalInt.empty() : OptionalInt.of(blockBytes);
    }

    /**
     * {@return the size of every WAVE block but the last, the automatic size resolved}
     */
    int resolvedBlockBytes() {
        if (blockBytes != 0) {
            return blockBytes;
        }
        var channels = layout.channels();
        var bytes = 256L * channels * Math.max(1, sampleRate / 11025);
        while (bytes > MAX_BLOCK_BYTES || blockSamples(codec, bytes, channels) > MAX_BLOCK_SAMPLES) {
            bytes -= 256L * channels;
        }
        return (int) bytes;
    }

    /**
     * Returns the samples per channel of a WAVE block.
     *
     * @param codec {@link Codec#ADPCM_IMA_WAV} or {@link Codec#ADPCM_MS}; any other codec counts as IMA ADPCM
     * @param bytes the size of the block, at least its headers
     * @param channels the number of channels
     * @return the samples per channel
     */
    private static long blockSamples(Codec codec, long bytes, int channels) {
        return codec == Codec.ADPCM_MS ? 2 + 2 * (bytes - 7L * channels) / channels
                : 1 + (bytes - 4L * channels) / (4L * channels) * 8;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof AdpcmEncoderOptions other && codec == other.codec && sampleRate == other.sampleRate
                && layout.equals(other.layout) && blockBytes == other.blockBytes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(codec, sampleRate, layout, blockBytes);
    }

    @Override
    public String toString() {
        return "AdpcmEncoderOptions[codec=" + codec + ", sampleRate=" + sampleRate + ", layout=" + layout
                + ", blockBytes=" + (blockBytes == 0 ? "automatic" : blockBytes) + "]";
    }

    /**
     * Builds {@link AdpcmEncoderOptions}. Setters check their own argument; {@link #build()} checks the block size
     * against the codec and channel count.
     */
    public static final class Builder {
        private Codec codec;
        private int sampleRate;
        private ChannelLayout layout;
        private int blockBytes;

        /**
         * Constructs a builder with the default settings.
         */
        private Builder() {
            this.codec = Codec.ADPCM_IMA_WAV;
            this.sampleRate = 44100;
            this.layout = ChannelLayout.STEREO;
        }

        /**
         * Sets the codec; see {@link AdpcmEncoderOptions#codec()}.
         *
         * @param codec the value
         * @return this builder
         * @throws IllegalArgumentException if the codec is not one of the three ADPCM layouts
         */
        public Builder codec(Codec codec) {
            Objects.requireNonNull(codec, "codec");
            if (codec != Codec.ADPCM_IMA_WAV && codec != Codec.ADPCM_IMA_QT && codec != Codec.ADPCM_MS) {
                throw new IllegalArgumentException("not an IMA or Microsoft ADPCM codec: " + codec);
            }
            this.codec = codec;
            return this;
        }

        /**
         * Sets the input sampling rate; see {@link AdpcmEncoderOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         * @throws IllegalArgumentException if the rate is not positive
         */
        public Builder sampleRate(int sampleRate) {
            if (sampleRate <= 0) {
                throw new IllegalArgumentException("invalid sample rate " + sampleRate);
            }
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the input channel layout; see {@link AdpcmEncoderOptions#layout()}.
         *
         * @param layout the value
         * @return this builder
         * @throws IllegalArgumentException if the layout has more than 8 channels
         */
        public Builder layout(ChannelLayout layout) {
            Objects.requireNonNull(layout, "layout");
            if (layout.channels() > MAX_CHANNELS) {
                throw new IllegalArgumentException("ADPCM encoding of " + layout.channels() + " channels; at most "
                        + MAX_CHANNELS);
            }
            this.layout = layout;
            return this;
        }

        /**
         * Sets the input sampling rate and channel layout.
         *
         * @param sampleRate the sampling rate; see {@link AdpcmEncoderOptions#sampleRate()}
         * @param layout the channel layout; see {@link AdpcmEncoderOptions#layout()}
         * @return this builder
         * @throws IllegalArgumentException if either is out of range
         */
        public Builder format(int sampleRate, ChannelLayout layout) {
            return sampleRate(sampleRate).layout(layout);
        }

        /**
         * Sets the block size; see {@link AdpcmEncoderOptions#blockBytes()}.
         *
         * @param blockBytes the value, from 1 to 65535
         * @return this builder
         * @throws IllegalArgumentException if the size is outside 1 to 65535
         */
        public Builder blockBytes(int blockBytes) {
            if (blockBytes < 1 || blockBytes > MAX_BLOCK_BYTES) {
                throw new IllegalArgumentException("block size " + blockBytes + " is outside 1.." + MAX_BLOCK_BYTES);
            }
            this.blockBytes = blockBytes;
            return this;
        }

        /**
         * Selects the automatic block size; see {@link AdpcmEncoderOptions#blockBytes()}.
         *
         * @return this builder
         */
        public Builder automaticBlockBytes() {
            this.blockBytes = 0;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the block size does not suit the codec and channel count, or codes more
         * than 65535 samples per channel
         */
        public AdpcmEncoderOptions build() {
            if (blockBytes != 0 && codec != Codec.ADPCM_IMA_QT) {
                var channels = layout.channels();
                var valid = codec == Codec.ADPCM_IMA_WAV
                        ? blockBytes > 4 * channels && (blockBytes - 4 * channels) % (4 * channels) == 0
                        : blockBytes > 7 * channels && 2 * (blockBytes - 7 * channels) % channels == 0;
                if (valid && blockSamples(codec, blockBytes, channels) > MAX_BLOCK_SAMPLES) {
                    throw new IllegalArgumentException("a " + codec.displayName() + " block of " + blockBytes
                            + " bytes codes " + blockSamples(codec, blockBytes, channels)
                            + " samples per channel; at most " + MAX_BLOCK_SAMPLES);
                }
                if (!valid) {
                    throw new IllegalArgumentException("a " + codec.displayName() + " block of " + blockBytes
                            + " bytes for " + channels + " channels");
                }
            }
            return new AdpcmEncoderOptions(this);
        }
    }
}
