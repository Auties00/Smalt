package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.codec.WriterSupport;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.TrackKind;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link AudioWriter}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AudioWriterOptions {
    /**
     * The default input sampling rate, in hertz.
     */
    public static final int DEFAULT_SAMPLE_RATE = 48_000;

    private static final AudioWriterOptions DEFAULTS = new AudioWriterOptions(null, DEFAULT_SAMPLE_RATE,
            ChannelLayout.STEREO, 0, null, 0, 0, false, null, MediaTags.empty());

    private final Codec codec;
    private final int sampleRate;
    private final ChannelLayout layout;
    private final int outputSampleRate;
    private final ChannelLayout outputLayout;
    private final int bitrate;
    private final int bitsPerSample;
    private final boolean realtime;
    private final String language;
    private final MediaTags tags;

    private AudioWriterOptions(Codec codec, int sampleRate, ChannelLayout layout, int outputSampleRate,
                               ChannelLayout outputLayout, int bitrate, int bitsPerSample, boolean realtime,
                               String language, MediaTags tags) {
        this.codec = codec;
        this.sampleRate = sampleRate;
        this.layout = layout;
        this.outputSampleRate = outputSampleRate;
        this.outputLayout = outputLayout;
        this.bitrate = bitrate;
        this.bitsPerSample = bitsPerSample;
        this.realtime = realtime;
        this.language = language;
        this.tags = tags;
    }

    /**
     * Returns options for 48 kHz stereo input, coded with the container's default codec at the
     * codec's default bitrate.
     *
     * @return the default options
     */
    public static AudioWriterOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options for input of a rate and layout.
     *
     * @param sampleRate the input sampling rate in hertz, positive
     * @param layout the input channel layout
     * @return the options
     * @throws IllegalArgumentException if {@code sampleRate} is not positive
     */
    public static AudioWriterOptions of(int sampleRate, ChannelLayout layout) {
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
     * {@return the codec of the written track, or empty for the first codec of
     * {@link com.github.auties00.smalt.transcode.OutputContainer#audioCodecs()} that an encoder is
     * available for; empty by default}
     *
     * <p>A writer over a caller's muxer needs a codec.
     */
    public Optional<Codec> codec() {
        return Optional.ofNullable(codec);
    }

    /**
     * {@return the sampling rate of the samples passed to the writer in hertz, positive;
     * {@value #DEFAULT_SAMPLE_RATE} by default}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the channel layout of the samples passed to the writer; {@link ChannelLayout#STEREO}
     * by default}
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the sampling rate the track is coded at in hertz, positive, or empty for the input
     * rate, or 48 kHz when the codec cannot code the input rate; empty by default}
     *
     * <p>A coded rate other than the input rate resamples the input.
     */
    public OptionalInt outputSampleRate() {
        return outputSampleRate == 0 ? OptionalInt.empty() : OptionalInt.of(outputSampleRate);
    }

    /**
     * {@return the channel layout the track is coded in, or empty for the input layout, or, when the
     * codec cannot code the input layout, the same sound field in ACN order with SN3D normalization
     * for an {@linkplain ChannelLayout#ambisonics() ambisonic} input and then stereo for an input of
     * more than two channels; empty by default}
     *
     * <p>A coded layout other than the input layout mixes the input as {@link
     * com.github.auties00.smalt.audio.process.ChannelMixer} describes.
     */
    public Optional<ChannelLayout> outputLayout() {
        return Optional.ofNullable(outputLayout);
    }

    /**
     * {@return the target bitrate in bits per second, positive, or empty for the codec's default;
     * empty by default}
     */
    public OptionalInt bitrate() {
        return bitrate == 0 ? OptionalInt.empty() : OptionalInt.of(bitrate);
    }

    /**
     * {@return the sample size a lossless or PCM codec codes in bits, from 1 to 32, or empty for
     * the codec's default; empty by default}
     *
     * @see AudioEncoderOptions#bitsPerSample()
     */
    public OptionalInt bitsPerSample() {
        return bitsPerSample == 0 ? OptionalInt.empty() : OptionalInt.of(bitsPerSample);
    }

    /**
     * {@return whether the encoder is tuned for interactive transmission; {@code false} by default}
     *
     * @see AudioEncoderOptions#realtime()
     */
    public boolean realtime() {
        return realtime;
    }

    /**
     * {@return the ISO 639-2 language code the track declares, three lowercase letters, or empty for
     * none; empty by default}
     */
    public Optional<String> language() {
        return Optional.ofNullable(language);
    }

    /**
     * {@return the tags, attached pictures and chapters the file carries, as
     * {@link com.github.auties00.smalt.container.Muxer#setTags(MediaTags)} writes them; empty by
     * default}
     *
     * <p>Tags that are not empty need a container that stores tags.
     */
    public MediaTags tags() {
        return tags;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AudioWriterOptions that
                && codec == that.codec
                && sampleRate == that.sampleRate
                && layout.equals(that.layout)
                && outputSampleRate == that.outputSampleRate
                && Objects.equals(outputLayout, that.outputLayout)
                && bitrate == that.bitrate
                && bitsPerSample == that.bitsPerSample
                && realtime == that.realtime
                && Objects.equals(language, that.language)
                && tags.equals(that.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(codec, sampleRate, layout, outputSampleRate, outputLayout, bitrate, bitsPerSample, realtime,
                language, tags);
    }

    @Override
    public String toString() {
        return "AudioWriterOptions[codec=" + codec + ", sampleRate=" + sampleRate + ", layout=" + layout
                + ", outputSampleRate=" + outputSampleRate + ", outputLayout=" + outputLayout + ", bitrate=" + bitrate
                + ", bitsPerSample=" + bitsPerSample + ", realtime=" + realtime + ", language=" + language
                + ", tags=" + tags + "]";
    }

    /**
     * Builds {@link AudioWriterOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private Codec codec;
        private int sampleRate;
        private ChannelLayout layout;
        private OptionalInt outputSampleRate;
        private ChannelLayout outputLayout;
        private OptionalInt bitrate;
        private OptionalInt bitsPerSample;
        private boolean realtime;
        private String language;
        private MediaTags tags;

        private Builder(AudioWriterOptions options) {
            this.codec = options.codec;
            this.sampleRate = options.sampleRate;
            this.layout = options.layout;
            this.outputSampleRate = options.outputSampleRate();
            this.outputLayout = options.outputLayout;
            this.bitrate = options.bitrate();
            this.bitsPerSample = options.bitsPerSample();
            this.realtime = options.realtime;
            this.language = options.language;
            this.tags = options.tags;
        }

        /**
         * Sets the codec of the written track; see {@link AudioWriterOptions#codec()}.
         *
         * @param codec the value, an audio codec
         * @return this builder
         * @throws IllegalArgumentException if {@code codec} is not an audio codec
         */
        public Builder codec(Codec codec) {
            Objects.requireNonNull(codec, "codec");
            if (codec.kind() != TrackKind.AUDIO) {
                throw new IllegalArgumentException(codec.displayName() + " is not an audio codec");
            }
            this.codec = codec;
            return this;
        }

        /**
         * Selects the container's default codec; see {@link AudioWriterOptions#codec()}.
         *
         * @return this builder
         */
        public Builder automaticCodec() {
            this.codec = null;
            return this;
        }

        /**
         * Sets the sampling rate of the input; see {@link AudioWriterOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the channel layout of the input; see {@link AudioWriterOptions#layout()}.
         *
         * @param layout the value
         * @return this builder
         */
        public Builder layout(ChannelLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            return this;
        }

        /**
         * Sets the coded sampling rate; see {@link AudioWriterOptions#outputSampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder outputSampleRate(int sampleRate) {
            this.outputSampleRate = OptionalInt.of(sampleRate);
            return this;
        }

        /**
         * Codes at the input rate when the codec can; see
         * {@link AudioWriterOptions#outputSampleRate()}.
         *
         * @return this builder
         */
        public Builder automaticOutputSampleRate() {
            this.outputSampleRate = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the coded channel layout; see {@link AudioWriterOptions#outputLayout()}.
         *
         * @param layout the value
         * @return this builder
         */
        public Builder outputLayout(ChannelLayout layout) {
            this.outputLayout = Objects.requireNonNull(layout, "layout");
            return this;
        }

        /**
         * Codes in the input layout when the codec can; see
         * {@link AudioWriterOptions#outputLayout()}.
         *
         * @return this builder
         */
        public Builder automaticOutputLayout() {
            this.outputLayout = null;
            return this;
        }

        /**
         * Sets the target bitrate; see {@link AudioWriterOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(int bitrate) {
            this.bitrate = OptionalInt.of(bitrate);
            return this;
        }

        /**
         * Selects the codec's default bitrate; see {@link AudioWriterOptions#bitrate()}.
         *
         * @return this builder
         */
        public Builder automaticBitrate() {
            this.bitrate = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the coded sample size; see {@link AudioWriterOptions#bitsPerSample()}.
         *
         * @param bitsPerSample the value
         * @return this builder
         */
        public Builder bitsPerSample(int bitsPerSample) {
            this.bitsPerSample = OptionalInt.of(bitsPerSample);
            return this;
        }

        /**
         * Selects the codec's default sample size; see {@link AudioWriterOptions#bitsPerSample()}.
         *
         * @return this builder
         */
        public Builder automaticBitsPerSample() {
            this.bitsPerSample = OptionalInt.empty();
            return this;
        }

        /**
         * Sets whether the encoder is tuned for interactive transmission; see
         * {@link AudioWriterOptions#realtime()}.
         *
         * @param realtime the value
         * @return this builder
         */
        public Builder realtime(boolean realtime) {
            this.realtime = realtime;
            return this;
        }

        /**
         * Sets the declared language; see {@link AudioWriterOptions#language()}.
         *
         * @param language the value
         * @return this builder
         */
        public Builder language(String language) {
            this.language = Objects.requireNonNull(language, "language");
            return this;
        }

        /**
         * Declares no language; see {@link AudioWriterOptions#language()}.
         *
         * @return this builder
         */
        public Builder noLanguage() {
            this.language = null;
            return this;
        }

        /**
         * Sets the tags of the file; see {@link AudioWriterOptions#tags()}.
         *
         * @param tags the value
         * @return this builder
         */
        public Builder tags(MediaTags tags) {
            this.tags = Objects.requireNonNull(tags, "tags");
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if a sampling rate or the bitrate is not positive, the
         * sample size is outside 1..32, or the language is not three lowercase ASCII letters
         */
        public AudioWriterOptions build() {
            Checks.positive("sample rate", sampleRate);
            var checkedOutputRate = outputSampleRate.isPresent()
                    ? Checks.positive("output sample rate", outputSampleRate.getAsInt()) : 0;
            var checkedBitrate = bitrate.isPresent() ? Checks.positive("bitrate", bitrate.getAsInt()) : 0;
            var checkedSize = bitsPerSample.isPresent()
                    ? Checks.inRange("sample size", bitsPerSample.getAsInt(), 1, 32) : 0;
            return new AudioWriterOptions(codec, sampleRate, layout, checkedOutputRate, outputLayout, checkedBitrate,
                    checkedSize, realtime, WriterSupport.language(language), tags);
        }
    }
}
