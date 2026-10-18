package com.github.auties00.smalt.capture.audio;

import com.github.auties00.smalt.internal.check.Checks;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link AudioCaptureSession} or an {@link AudioPlaybackSession}.
 * Instances are immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
 */
public final class AudioStreamOptions {
    /**
     * The default {@linkplain #period() period}, 10 ms.
     */
    public static final Duration DEFAULT_PERIOD = Duration.ofMillis(10);

    /**
     * The default {@linkplain #latency() device latency}, 40 ms.
     */
    public static final Duration DEFAULT_LATENCY = Duration.ofMillis(40);

    /**
     * The default {@linkplain #bufferDuration() queue capacity}, 200 ms.
     */
    public static final Duration DEFAULT_BUFFER_DURATION = Duration.ofMillis(200);

    /**
     * The lowest accepted sampling rate, in hertz.
     */
    private static final int MIN_RATE = 1_000;

    /**
     * The highest accepted sampling rate, in hertz.
     */
    private static final int MAX_RATE = 768_000;

    /**
     * The highest accepted channel count.
     */
    private static final int MAX_CHANNELS = 32;

    /**
     * The shortest accepted period, latency or buffer duration.
     */
    private static final Duration MIN_DURATION = Duration.ofMillis(1);

    /**
     * The longest accepted period, latency or buffer duration.
     */
    private static final Duration MAX_DURATION = Duration.ofSeconds(10);

    /**
     * The options of {@link #defaults()}.
     */
    private static final AudioStreamOptions DEFAULTS = new Builder().build();

    /**
     * The options of {@link #forRealtime()}.
     */
    private static final AudioStreamOptions REALTIME = new Builder()
            .latency(Duration.ofMillis(20))
            .bufferDuration(Duration.ofMillis(60))
            .build();

    /**
     * The options of {@link #forVoice()}.
     */
    private static final AudioStreamOptions VOICE = REALTIME.toBuilder()
            .sampleRate(16_000)
            .channels(1)
            .build();

    /**
     * The sampling rate, or {@code 0} for the device's.
     */
    private final int sampleRate;

    /**
     * The channel count, or {@code 0} for the device's.
     */
    private final int channels;

    private final Duration period;

    private final Duration latency;

    private final Duration bufferDuration;

    private final AudioShareMode shareMode;

    private AudioStreamOptions(Builder builder) {
        this.sampleRate = builder.sampleRate == null ? 0 : builder.sampleRate;
        this.channels = builder.channels == null ? 0 : builder.channels;
        this.period = builder.period;
        this.latency = builder.latency;
        this.bufferDuration = builder.bufferDuration;
        this.shareMode = builder.shareMode;
    }

    /**
     * Returns the default options: the device's rate and channel count, a 10 ms period, a 40 ms
     * device latency, a 200 ms queue and the shared mode.
     *
     * @return the default options
     */
    public static AudioStreamOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the options of interactive audio: the device's rate and channel count, a 10 ms
     * period, a 20 ms device latency, a 60 ms queue and the shared mode.
     *
     * @apiNote Keep a playback queue this short only when the writer is paced by a clock of its
     * own, such as a jitter buffer; a writer that bursts needs a longer queue.
     *
     * @return the real-time options
     */
    public static AudioStreamOptions forRealtime() {
        return REALTIME;
    }

    /**
     * Returns the options of a voice call: {@link #forRealtime()} at 16 kHz mono, so each period
     * is one 160-sample frame of a 10 ms voice processor or speech codec.
     *
     * @return the voice options
     */
    public static AudioStreamOptions forVoice() {
        return VOICE;
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
     * Returns a builder initialized with these options.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the sampling rate the caller reads or writes, in hertz from 1000 to 768000, or empty
     * for the device's own rate; empty by default}
     *
     * <p>The device's own rate needs no conversion. Another rate is converted in
     * {@link AudioShareMode#SHARED} mode, by the operating system (Windows, Linux) or by the
     * session (macOS input).
     */
    public OptionalInt sampleRate() {
        return sampleRate == 0 ? OptionalInt.empty() : OptionalInt.of(sampleRate);
    }

    /**
     * {@return the channel count the caller reads or writes, from 1 to 32, or empty for the
     * device's own count; empty by default}
     *
     * <p>The device's own count needs no mixing. Another count is converted as for
     * {@link #sampleRate()}.
     */
    public OptionalInt channels() {
        return channels == 0 ? OptionalInt.empty() : OptionalInt.of(channels);
    }

    /**
     * {@return the interval at which the device thread exchanges audio with the device, from 1 ms
     * to 10 s; {@link #DEFAULT_PERIOD} by default}
     *
     * <p>It is also the size of the chunk {@link AudioCaptureSession#read()} returns.
     */
    public Duration period() {
        return period;
    }

    /**
     * {@return the device buffer asked of the operating system, from 1 ms to 10 s;
     * {@link #DEFAULT_LATENCY} by default}
     *
     * <p>It is how much audio the operating system holds between the device thread and the
     * hardware. A session raises it to at least one {@linkplain #period() period}, and the
     * operating system may grant more, which {@link AudioCaptureSession#latencyMicros()} reports.
     */
    public Duration latency() {
        return latency;
    }

    /**
     * {@return the capacity of the session's queue between the device thread and the caller, from
     * 1 ms to 10 s; {@link #DEFAULT_BUFFER_DURATION} by default}
     *
     * <p>A session raises it to at least two {@linkplain #period() periods}. A capture session
     * whose reader falls behind by more drops the oldest audio; a playback session's
     * {@code write} blocks while the queue is full.
     */
    public Duration bufferDuration() {
        return bufferDuration;
    }

    /**
     * {@return whether the stream shares its device with other applications;
     * {@link AudioShareMode#SHARED} by default}
     */
    public AudioShareMode shareMode() {
        return shareMode;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof AudioStreamOptions that
               && sampleRate == that.sampleRate
               && channels == that.channels
               && period.equals(that.period)
               && latency.equals(that.latency)
               && bufferDuration.equals(that.bufferDuration)
               && shareMode == that.shareMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, channels, period, latency, bufferDuration, shareMode);
    }

    @Override
    public String toString() {
        return "AudioStreamOptions[sampleRate=" + (sampleRate == 0 ? "device" : sampleRate)
               + ", channels=" + (channels == 0 ? "device" : channels)
               + ", period=" + period
               + ", latency=" + latency
               + ", bufferDuration=" + bufferDuration
               + ", shareMode=" + shareMode + "]";
    }

    /**
     * A builder of {@link AudioStreamOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private Integer sampleRate;

        private Integer channels;

        private Duration period;

        private Duration latency;

        private Duration bufferDuration;

        private AudioShareMode shareMode;

        private Builder() {
            this.period = DEFAULT_PERIOD;
            this.latency = DEFAULT_LATENCY;
            this.bufferDuration = DEFAULT_BUFFER_DURATION;
            this.shareMode = AudioShareMode.SHARED;
        }

        private Builder(AudioStreamOptions options) {
            this.sampleRate = options.sampleRate == 0 ? null : options.sampleRate;
            this.channels = options.channels == 0 ? null : options.channels;
            this.period = options.period;
            this.latency = options.latency;
            this.bufferDuration = options.bufferDuration;
            this.shareMode = options.shareMode;
        }

        /**
         * Sets the sampling rate the caller reads or writes; see
         * {@link AudioStreamOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Selects the device's own sampling rate, which needs no rate conversion; see
         * {@link AudioStreamOptions#sampleRate()}.
         *
         * @return this builder
         */
        public Builder deviceSampleRate() {
            this.sampleRate = null;
            return this;
        }

        /**
         * Sets the channel count the caller reads or writes; see
         * {@link AudioStreamOptions#channels()}.
         *
         * @param channels the value
         * @return this builder
         */
        public Builder channels(int channels) {
            this.channels = channels;
            return this;
        }

        /**
         * Selects the device's own channel count, which needs no channel mixing; see
         * {@link AudioStreamOptions#channels()}.
         *
         * @return this builder
         */
        public Builder deviceChannels() {
            this.channels = null;
            return this;
        }

        /**
         * Sets the interval of the device thread's exchanges with the device; see
         * {@link AudioStreamOptions#period()}.
         *
         * @param period the value
         * @return this builder
         */
        public Builder period(Duration period) {
            this.period = Objects.requireNonNull(period, "period");
            return this;
        }

        /**
         * Sets the device buffer asked of the operating system; see
         * {@link AudioStreamOptions#latency()}.
         *
         * @param latency the value
         * @return this builder
         */
        public Builder latency(Duration latency) {
            this.latency = Objects.requireNonNull(latency, "latency");
            return this;
        }

        /**
         * Sets the capacity of the session's queue; see
         * {@link AudioStreamOptions#bufferDuration()}.
         *
         * @param bufferDuration the value
         * @return this builder
         */
        public Builder bufferDuration(Duration bufferDuration) {
            this.bufferDuration = Objects.requireNonNull(bufferDuration, "bufferDuration");
            return this;
        }

        /**
         * Sets whether the stream shares its device; see {@link AudioStreamOptions#shareMode()}.
         *
         * @param shareMode the value
         * @return this builder
         */
        public Builder shareMode(AudioShareMode shareMode) {
            this.shareMode = Objects.requireNonNull(shareMode, "shareMode");
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sampling rate is set outside 1000 to 768000, the
         * channel count is set outside 1 to 32, or the period, latency or buffer duration is outside
         * 1 ms to 10 s
         */
        public AudioStreamOptions build() {
            if (sampleRate != null) {
                Checks.inRange("sample rate", sampleRate.intValue(), MIN_RATE, MAX_RATE);
            }
            if (channels != null) {
                Checks.inRange("channel count", channels.intValue(), 1, MAX_CHANNELS);
            }
            Checks.inRange("period", period, MIN_DURATION, MAX_DURATION);
            Checks.inRange("latency", latency, MIN_DURATION, MAX_DURATION);
            Checks.inRange("buffer duration", bufferDuration, MIN_DURATION, MAX_DURATION);
            return new AudioStreamOptions(this);
        }
    }
}
