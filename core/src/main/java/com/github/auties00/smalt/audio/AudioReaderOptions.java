package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.loudness.ReplayGain;
import com.github.auties00.smalt.audio.process.ChannelMixerOptions;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link AudioReader}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AudioReaderOptions {
    /**
     * The default number of samples per returned block.
     */
    public static final int DEFAULT_BLOCK_FRAMES = 4096;

    private static final AudioReaderOptions DEFAULTS =
            new AudioReaderOptions(0, null, MediaLimits.DEFAULT, DEFAULT_BLOCK_FRAMES, SampleFormat.FLOAT, false,
                    ChannelMixerOptions.defaults(), null, 0, true);

    private final int sampleRate;
    private final ChannelLayout layout;
    private final MediaLimits limits;
    private final int blockFrames;
    private final SampleFormat sampleFormat;
    private final boolean verifyIntegrity;
    private final ChannelMixerOptions mixing;

    /**
     * The ReplayGain mode, or {@code null} when no ReplayGain is applied.
     */
    private final ReplayGain.Mode replayGain;

    private final double replayGainPreampDb;
    private final boolean replayGainClipPrevention;

    private AudioReaderOptions(int sampleRate, ChannelLayout layout, MediaLimits limits, int blockFrames,
                               SampleFormat sampleFormat, boolean verifyIntegrity, ChannelMixerOptions mixing,
                               ReplayGain.Mode replayGain, double replayGainPreampDb, boolean replayGainClipPrevention) {
        this.sampleRate = sampleRate;
        this.layout = layout;
        this.limits = limits;
        this.blockFrames = blockFrames;
        this.sampleFormat = sampleFormat;
        this.verifyIntegrity = verifyIntegrity;
        this.mixing = mixing;
        this.replayGain = replayGain;
        this.replayGainPreampDb = replayGainPreampDb;
        this.replayGainClipPrevention = replayGainClipPrevention;
    }

    /**
     * Returns options that keep the codec's rate and layout, with {@link MediaLimits#DEFAULT}
     * and blocks of {@value #DEFAULT_BLOCK_FRAMES} samples.
     *
     * @return the default options
     */
    public static AudioReaderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options converting to a rate and layout.
     *
     * @param sampleRate the output sampling rate in hertz, positive
     * @param layout the output channel layout
     * @return the options
     * @throws IllegalArgumentException if {@code sampleRate} is not positive
     */
    public static AudioReaderOptions of(int sampleRate, ChannelLayout layout) {
        return builder().sampleRate(sampleRate).layout(layout).build();
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
     * {@return the output sampling rate in hertz, positive, or empty to keep the codec's; empty
     * by default}
     */
    public OptionalInt sampleRate() {
        return sampleRate == 0 ? OptionalInt.empty() : OptionalInt.of(sampleRate);
    }

    /**
     * {@return the output channel layout, or empty to keep the codec's; empty by default}
     */
    public Optional<ChannelLayout> layout() {
        return Optional.ofNullable(layout);
    }

    /**
     * {@return the resource bounds enforced while demuxing and decoding; {@link MediaLimits#DEFAULT}
     * by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the largest number of samples per block returned by {@link AudioReader#read()},
     * positive; {@value #DEFAULT_BLOCK_FRAMES} by default}
     */
    public int blockFrames() {
        return blockFrames;
    }

    /**
     * {@return the representation of the samples the reader returns; {@link SampleFormat#FLOAT} by
     * default}
     *
     * <p>{@link SampleFormat#FLOAT} blocks come from {@link AudioReader#read()},
     * {@link SampleFormat#INTEGER} blocks from {@link AudioReader#readIntegers()} and
     * {@link SampleFormat#DOUBLE} blocks from {@link AudioReader#readDoubles()}. The exact formats
     * keep the codec's rate and layout, as {@link AudioStreamDecoderOptions#sampleFormat()}
     * describes.
     */
    public SampleFormat sampleFormat() {
        return sampleFormat;
    }

    /**
     * {@return whether the codec checks the whole-stream checksum the stream carries, at the end of
     * a read from the start; {@code false} by default}
     *
     * @see AudioDecoderOptions#verifyIntegrity()
     */
    public boolean verifyIntegrity() {
        return verifyIntegrity;
    }

    /**
     * {@return how decoded channels are mixed to {@link #layout()}; {@link ChannelMixerOptions#defaults()}
     * by default}
     *
     * @see AudioStreamDecoderOptions#mixing()
     */
    public ChannelMixerOptions mixing() {
        return mixing;
    }

    /**
     * {@return the ReplayGain the reader applies to the samples it returns, or empty for none; empty by
     * default}
     *
     * <p>When present, the reader reads the input's tags once when it opens ({@link ReplayGain#fromTags})
     * and scales every sample by the gain {@link ReplayGain#playbackGainDb} returns for this mode,
     * {@link #replayGainPreampDb()} and {@link #replayGainClipPrevention()}
     * ({@link AudioReader#replayGainDb()}). An input whose container has no readable tags, whose tags
     * are corrupt, or that carries no gain for the mode is returned unchanged. Samples the gain pushes
     * past full scale are not clipped. ReplayGain needs {@link SampleFormat#FLOAT} or
     * {@link SampleFormat#DOUBLE} output.
     */
    public Optional<ReplayGain.Mode> replayGain() {
        return Optional.ofNullable(replayGain);
    }

    /**
     * {@return the gain added to the tagged ReplayGain, in dB, finite; 0 by default}
     */
    public double replayGainPreampDb() {
        return replayGainPreampDb;
    }

    /**
     * {@return whether the ReplayGain is lowered so that the tagged peak stays at or below full scale;
     * {@code true} by default}
     */
    public boolean replayGainClipPrevention() {
        return replayGainClipPrevention;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AudioReaderOptions that
                && sampleRate == that.sampleRate
                && Objects.equals(layout, that.layout)
                && limits.equals(that.limits)
                && blockFrames == that.blockFrames
                && sampleFormat == that.sampleFormat
                && verifyIntegrity == that.verifyIntegrity
                && mixing.equals(that.mixing)
                && replayGain == that.replayGain
                && Double.compare(replayGainPreampDb, that.replayGainPreampDb) == 0
                && replayGainClipPrevention == that.replayGainClipPrevention;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, limits, blockFrames, sampleFormat, verifyIntegrity, mixing, replayGain,
                replayGainPreampDb, replayGainClipPrevention);
    }

    @Override
    public String toString() {
        return "AudioReaderOptions[sampleRate=" + sampleRate + ", layout=" + layout + ", limits=" + limits
                + ", blockFrames=" + blockFrames + ", sampleFormat=" + sampleFormat
                + ", verifyIntegrity=" + verifyIntegrity + ", mixing=" + mixing + ", replayGain=" + replayGain()
                + ", replayGainPreampDb=" + replayGainPreampDb + ", replayGainClipPrevention="
                + replayGainClipPrevention + "]";
    }

    /**
     * Builds {@link AudioReaderOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private OptionalInt sampleRate;
        private ChannelLayout layout;
        private MediaLimits limits;
        private int blockFrames;
        private SampleFormat sampleFormat;
        private boolean verifyIntegrity;
        private ChannelMixerOptions mixing;
        private ReplayGain.Mode replayGain;
        private double replayGainPreampDb;
        private boolean replayGainClipPrevention;

        private Builder(AudioReaderOptions options) {
            this.sampleRate = options.sampleRate();
            this.layout = options.layout;
            this.limits = options.limits;
            this.blockFrames = options.blockFrames;
            this.sampleFormat = options.sampleFormat;
            this.verifyIntegrity = options.verifyIntegrity;
            this.mixing = options.mixing;
            this.replayGain = options.replayGain;
            this.replayGainPreampDb = options.replayGainPreampDb;
            this.replayGainClipPrevention = options.replayGainClipPrevention;
        }

        /**
         * Sets the output sampling rate; see {@link AudioReaderOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = OptionalInt.of(sampleRate);
            return this;
        }

        /**
         * Keeps the codec's sampling rate; see {@link AudioReaderOptions#sampleRate()}.
         *
         * @return this builder
         */
        public Builder sourceSampleRate() {
            this.sampleRate = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the output channel layout; see {@link AudioReaderOptions#layout()}.
         *
         * @param layout the value
         * @return this builder
         */
        public Builder layout(ChannelLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            return this;
        }

        /**
         * Keeps the codec's channel layout; see {@link AudioReaderOptions#layout()}.
         *
         * @return this builder
         */
        public Builder sourceLayout() {
            this.layout = null;
            return this;
        }

        /**
         * Sets the resource bounds; see {@link AudioReaderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the largest number of samples per block; see {@link AudioReaderOptions#blockFrames()}.
         *
         * @param blockFrames the value
         * @return this builder
         */
        public Builder blockFrames(int blockFrames) {
            this.blockFrames = blockFrames;
            return this;
        }

        /**
         * Sets the representation of the returned samples; see
         * {@link AudioReaderOptions#sampleFormat()}.
         *
         * @param format the value
         * @return this builder
         */
        public Builder sampleFormat(SampleFormat format) {
            this.sampleFormat = Objects.requireNonNull(format, "format");
            return this;
        }

        /**
         * Sets whether the whole-stream checksum is checked; see
         * {@link AudioReaderOptions#verifyIntegrity()}.
         *
         * @param verify the value
         * @return this builder
         */
        public Builder verifyIntegrity(boolean verify) {
            this.verifyIntegrity = verify;
            return this;
        }

        /**
         * Sets how decoded channels are mixed; see {@link AudioReaderOptions#mixing()}.
         *
         * @param mixing the value
         * @return this builder
         */
        public Builder mixing(ChannelMixerOptions mixing) {
            this.mixing = Objects.requireNonNull(mixing, "mixing");
            return this;
        }

        /**
         * Sets the ReplayGain mode; see {@link AudioReaderOptions#replayGain()}.
         *
         * @param mode the value
         * @return this builder
         */
        public Builder replayGain(ReplayGain.Mode mode) {
            this.replayGain = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Applies no ReplayGain; see {@link AudioReaderOptions#replayGain()}.
         *
         * @return this builder
         */
        public Builder noReplayGain() {
            this.replayGain = null;
            return this;
        }

        /**
         * Sets the ReplayGain preamp; see {@link AudioReaderOptions#replayGainPreampDb()}.
         *
         * @param preampDb the value
         * @return this builder
         */
        public Builder replayGainPreampDb(double preampDb) {
            this.replayGainPreampDb = preampDb;
            return this;
        }

        /**
         * Sets whether the ReplayGain prevents clipping; see
         * {@link AudioReaderOptions#replayGainClipPrevention()}.
         *
         * @param prevent the value
         * @return this builder
         */
        public Builder replayGainClipPrevention(boolean prevent) {
            this.replayGainClipPrevention = prevent;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sample rate or the block size is not positive, the
         * preamp is not finite, ReplayGain is combined with {@link SampleFormat#INTEGER} output, or the
         * mixing holds a caller's matrix and the sample format is not {@link SampleFormat#FLOAT}
         */
        public AudioReaderOptions build() {
            var rate = sampleRate.isPresent() ? Checks.positive("sample rate", sampleRate.getAsInt()) : 0;
            Checks.positive("block size", blockFrames);
            if (!Double.isFinite(replayGainPreampDb)) {
                throw new IllegalArgumentException("ReplayGain preamp " + replayGainPreampDb + " is not finite");
            }
            if (replayGain != null && sampleFormat == SampleFormat.INTEGER) {
                throw new IllegalArgumentException("ReplayGain cannot scale INTEGER output");
            }
            if (mixing.matrix().isPresent() && sampleFormat != SampleFormat.FLOAT) {
                throw new IllegalArgumentException("a mix matrix needs FLOAT output, not " + sampleFormat);
            }
            return new AudioReaderOptions(rate, layout, limits, blockFrames, sampleFormat, verifyIntegrity, mixing,
                    replayGain, replayGainPreampDb, replayGainClipPrevention);
        }
    }
}
