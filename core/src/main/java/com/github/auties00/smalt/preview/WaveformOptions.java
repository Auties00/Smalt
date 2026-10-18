package com.github.auties00.smalt.preview;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Holds the settings of a {@link WaveformMeter} and of {@link Waveform#measure}. Instances are
 * immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
 *
 * <p>A waveform has either a fixed number of buckets, each an equal slice of the whole audio (the
 * shape a static waveform under a play button needs), or a fixed number of sample frames per
 * bucket (the shape a scrolling display needs, as long as the audio).
 */
public final class WaveformOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final WaveformOptions DEFAULTS = new WaveformOptions(100, 0, Channels.MIX, 0, MediaLimits.DEFAULT);

    /**
     * The number of buckets, or {@code 0} in frames-per-bucket mode.
     */
    private final int buckets;

    /**
     * The number of sample frames per bucket, or {@code 0} in bucket-count mode.
     */
    private final long framesPerBucket;

    private final Channels channels;

    /**
     * The sampling rate audio is decoded at by {@link Waveform#measure}, or {@code 0} for the source's.
     */
    private final int sampleRate;

    private final MediaLimits limits;

    private WaveformOptions(int buckets, long framesPerBucket, Channels channels, int sampleRate, MediaLimits limits) {
        this.buckets = buckets;
        this.framesPerBucket = framesPerBucket;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.limits = limits;
    }

    /**
     * Returns 100 buckets over the whole audio, channels mixed, at the source's sampling rate,
     * under {@link MediaLimits#defaults()}.
     *
     * @return the default options
     */
    public static WaveformOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a fixed number of buckets over the whole audio, otherwise as {@link #defaults()}.
     *
     * @param buckets the number of buckets, positive
     * @return the options
     * @throws IllegalArgumentException if {@code buckets} is not positive
     */
    public static WaveformOptions ofBuckets(int buckets) {
        return DEFAULTS.toBuilder().buckets(buckets).build();
    }

    /**
     * Returns buckets of a fixed number of sample frames, otherwise as {@link #defaults()}.
     *
     * @param framesPerBucket the sample frames per bucket, positive
     * @return the options
     * @throws IllegalArgumentException if {@code framesPerBucket} is not positive
     */
    public static WaveformOptions ofFramesPerBucket(long framesPerBucket) {
        return DEFAULTS.toBuilder().framesPerBucket(framesPerBucket).build();
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
     * {@return the number of buckets the whole audio is split into, positive, or empty in
     * frames-per-bucket mode; 100 by default}
     */
    public OptionalInt buckets() {
        return buckets == 0 ? OptionalInt.empty() : OptionalInt.of(buckets);
    }

    /**
     * {@return the number of sample frames of every bucket but the last, positive, or empty in
     * bucket-count mode; empty by default}
     */
    public OptionalLong framesPerBucket() {
        return framesPerBucket == 0 ? OptionalLong.empty() : OptionalLong.of(framesPerBucket);
    }

    /**
     * {@return how the channels of multichannel audio combine into the measured signal;
     * {@link Channels#MIX} by default}
     */
    public Channels channels() {
        return channels;
    }

    /**
     * {@return the sampling rate {@link Waveform#measure} decodes at, in hertz, positive, or empty for
     * the source's own rate; empty by default}
     */
    public OptionalInt sampleRate() {
        return sampleRate == 0 ? OptionalInt.empty() : OptionalInt.of(sampleRate);
    }

    /**
     * {@return the resource bounds of the demuxer and decoder {@link Waveform#measure} uses;
     * {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WaveformOptions that && buckets == that.buckets && framesPerBucket == that.framesPerBucket
                && channels == that.channels && sampleRate == that.sampleRate && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(buckets, framesPerBucket, channels, sampleRate, limits);
    }

    @Override
    public String toString() {
        return "WaveformOptions[" + (buckets > 0 ? "buckets=" + buckets : "framesPerBucket=" + framesPerBucket)
                + ", channels=" + channels + ", sampleRate=" + sampleRate + ", limits=" + limits + "]";
    }

    /**
     * Enumerates how the channels of multichannel audio combine into the measured signal.
     *
     * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Channels {
        /**
         * The mean of the channels, sample frame by sample frame: the signal a mono downmix
         * carries.
         */
        MIX,

        /**
         * The first channel only.
         */
        FIRST,

        /**
         * Every channel at once: each sample of each channel counts as a sample of the bucket, so
         * the extremes are those of the loudest channel.
         */
        ALL
    }

    /**
     * Assembles {@link WaveformOptions} setting by setting.
     *
     * <p>The bucket count and the frames per bucket exclude each other: the last one set wins. Setters
     * reject {@code null} and store numbers unchecked; {@link #build()} checks the ranges. Instances are
     * not thread-safe.
     */
    public static final class Builder {
        private int buckets;
        private long framesPerBucket;

        /**
         * Whether the builder is in bucket-count mode rather than frames-per-bucket mode.
         */
        private boolean bucketMode;

        private Channels channels;
        private int sampleRate;

        /**
         * Whether audio is decoded at the source's own rate, ignoring {@link #sampleRate}.
         */
        private boolean sourceRate;

        private MediaLimits limits;

        private Builder(WaveformOptions options) {
            this.buckets = options.buckets;
            this.framesPerBucket = options.framesPerBucket;
            this.bucketMode = options.buckets > 0;
            this.channels = options.channels;
            this.sampleRate = options.sampleRate;
            this.sourceRate = options.sampleRate == 0;
            this.limits = options.limits;
        }

        /**
         * Splits the whole audio into a number of buckets, replacing the frames per bucket; see
         * {@link WaveformOptions#buckets()}.
         *
         * @param buckets the value
         * @return this builder
         */
        public Builder buckets(int buckets) {
            this.buckets = buckets;
            this.bucketMode = true;
            return this;
        }

        /**
         * Uses buckets of a fixed number of sample frames, replacing the bucket count; see
         * {@link WaveformOptions#framesPerBucket()}.
         *
         * @param framesPerBucket the value
         * @return this builder
         */
        public Builder framesPerBucket(long framesPerBucket) {
            this.framesPerBucket = framesPerBucket;
            this.bucketMode = false;
            return this;
        }

        /**
         * Sets how the channels combine; see {@link WaveformOptions#channels()}.
         *
         * @param channels the value
         * @return this builder
         */
        public Builder channels(Channels channels) {
            this.channels = Objects.requireNonNull(channels, "channels");
            return this;
        }

        /**
         * Sets the decoding rate; see {@link WaveformOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            this.sourceRate = false;
            return this;
        }

        /**
         * Decodes the audio at the sampling rate of the source; see {@link WaveformOptions#sampleRate()}.
         *
         * @return this builder
         */
        public Builder sourceSampleRate() {
            this.sourceRate = true;
            return this;
        }

        /**
         * Sets the resource bounds; see {@link WaveformOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the bucket count or the frames per bucket, whichever was
         * set last, is not positive, or the decoding rate is not positive
         */
        public WaveformOptions build() {
            var count = bucketMode ? Checks.positive("bucket count", buckets) : 0;
            var frames = bucketMode ? 0 : Checks.positive("frames per bucket", framesPerBucket);
            var rate = sourceRate ? 0 : Checks.positive("sample rate", sampleRate);
            return new WaveformOptions(count, frames, channels, rate, limits);
        }
    }
}
