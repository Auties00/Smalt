package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link Vp9Encoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>The picture size is not an option: it is given to the {@link Vp9Encoder} constructor.
 *
 * @apiNote The settings follow libvpx's real-time VP9 configuration: {@link #bitrate()} is
 * {@code rc_target_bitrate} in bits rather than kilobits per second, the quantizer window is
 * {@code rc_min_quantizer} and {@code rc_max_quantizer} on the same scale, {@link #keyframeInterval()}
 * is {@code kf_max_dist}, {@link #speed()} is the magnitude of the real-time {@code cpu-used}
 * control, {@link #errorResilient()} is {@code g_error_resilient}, {@link #adaptiveQuantization()} is
 * {@code aq-mode 3}, {@link #tileColumnsLog2()} is the {@code tile-columns} control, and the buffer
 * model is {@code rc_buf_sz}, {@code rc_buf_initial_sz} and {@code rc_buf_optimal_sz}.
 */
public final class Vp9EncoderOptions {
    /**
     * The largest base-2 logarithm of the tile columns.
     */
    private static final int MAX_TILE_COLUMNS_LOG2 = 6;

    /**
     * The largest number of threads; larger requests are lowered to it.
     */
    private static final int MAX_THREADS = 64;

    /**
     * The settings of {@link #defaults()}.
     */
    private static final Vp9EncoderOptions DEFAULTS = new Builder().build();

    /**
     * The settings of {@link #forRealtime()}.
     */
    private static final Vp9EncoderOptions REALTIME = new Builder()
            .quantizers(2, 52)
            .noPeriodicKeyframes()
            .errorResilient(true)
            .adaptiveQuantization(true)
            .dropFrames(true)
            .bufferSize(Duration.ofMillis(1000))
            .initialBufferLevel(Duration.ofMillis(500))
            .optimalBufferLevel(Duration.ofMillis(600))
            .build();

    private final Rational frameRate;

    private final Rational timeBase;

    private final long bitrate;

    private final int minQuantizer;

    private final int maxQuantizer;

    private final OptionalInt keyframeInterval;

    private final Vp9EncoderSpeed speed;

    private final boolean errorResilient;

    private final boolean adaptiveQuantization;

    private final int temporalLayers;

    private final boolean dropFrames;

    private final Duration bufferSize;

    private final Duration initialBufferLevel;

    private final Duration optimalBufferLevel;

    private final int tileColumnsLog2;

    private final int threads;

    private final MediaLimits limits;

    private Vp9EncoderOptions(Builder builder) {
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.bitrate = builder.bitrate;
        this.minQuantizer = builder.minQuantizer;
        this.maxQuantizer = builder.maxQuantizer;
        this.keyframeInterval = builder.keyframeInterval;
        this.speed = builder.speed;
        this.errorResilient = builder.errorResilient;
        this.adaptiveQuantization = builder.adaptiveQuantization;
        this.temporalLayers = builder.temporalLayers;
        this.dropFrames = builder.dropFrames;
        this.bufferSize = builder.bufferSize;
        this.initialBufferLevel = builder.initialBufferLevel;
        this.optimalBufferLevel = builder.optimalBufferLevel;
        this.tileColumnsLog2 = builder.tileColumnsLog2;
        this.threads = Math.min(builder.threads, MAX_THREADS);
        this.limits = builder.limits;
    }

    /**
     * Returns the general-purpose settings, for streams that are stored or sent without a
     * real-time deadline: the full quantizer window, a key frame every 128 frames, one temporal
     * layer, no error resilience, no adaptive quantization and no frame dropping.
     *
     * @return the options
     */
    public static Vp9EncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the settings of a real-time stream, such as a video call.
     *
     * <p>The stream is error resilient, uses cyclic refresh, drops frames when the buffer is empty
     * and has no periodic key frames; it uses the quantizer window 2 to 52 and a one second buffer
     * starting half full and aiming for 600 milliseconds. Every other setting is that of
     * {@link #defaults()}.
     *
     * @return the options
     */
    public static Vp9EncoderOptions forRealtime() {
        return REALTIME;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
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
        return new Builder(this);
    }

    /**
     * {@return the nominal frame rate in frames per second, from 0.1 to 1000; 30 by default}
     *
     * <p>The rate control measures the actual rate from the frames' timestamps.
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the unit of the timestamps of accepted frames and produced packets; the 90 kHz time
     * base {@code 1/90000} by default}
     */
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * {@return the target bitrate in bits per second, from 1 to {@link Integer#MAX_VALUE}; 1000000
     * by default}
     *
     * <p>The rate control models a buffer that the channel fills at the bitrate and every coded
     * frame drains: frame sizes follow the buffer towards its {@linkplain #optimalBufferLevel()
     * optimal level}, so the bitrate measured over a second stays close to the target and follows a
     * change of target within about a second. Frames under- and overshoot their target by at most
     * 50 percent.
     */
    public long bitrate() {
        return bitrate;
    }

    /**
     * {@return the smallest quantizer, from 0 to 63; 0 by default}
     *
     * <p>The scale is that of libvpx's {@code min-q} and {@code max-q}, not the 0 to 255 index of
     * {@link Vp9EncoderStats#lastQuantizerIndex()}. A frame coded at quantizer 0 is lossless: it uses
     * the 4x4 Walsh-Hadamard transform and no loop filter.
     */
    public int minQuantizer() {
        return minQuantizer;
    }

    /**
     * {@return the largest quantizer, from {@link #minQuantizer()} to 63; 63 by default}
     */
    public int maxQuantizer() {
        return maxQuantizer;
    }

    /**
     * {@return the most frames between two key frames, at least 1, or empty for key frames only at
     * the start and on request; 128 by default}
     */
    public OptionalInt keyframeInterval() {
        return keyframeInterval;
    }

    /**
     * {@return the trade between compression and encoding time; {@link Vp9EncoderSpeed#SPEED_6} by
     * default}
     */
    public Vp9EncoderSpeed speed() {
        return speed;
    }

    /**
     * {@return whether the stream is coded for channels that lose frames; {@code false} by
     * default}
     *
     * <p>With error resilience, every frame codes its probability updates for itself only and the
     * decoder never uses the motion vectors of a previous frame, so a receiver that lost frames
     * decodes the next frame correctly as long as it holds the frames it references. The golden
     * frame is refreshed periodically in either mode.
     */
    public boolean errorResilient() {
        return errorResilient;
    }

    /**
     * {@return whether static areas are refreshed at a lower quantizer (cyclic refresh);
     * {@code false} by default}
     */
    public boolean adaptiveQuantization() {
        return adaptiveQuantization;
    }

    /**
     * {@return the number of temporal layers, from 1 to 3; 1 by default}
     *
     * <p>With two layers the frames alternate between layer 0, which references and refreshes the
     * last frame, and layer 1, which references layer 0 and refreshes no reference used by layer 0;
     * with three layers the pattern of four frames is layer 0, 2, 1, 2. Layer 0 receives 60 percent
     * of the bitrate with two layers, 40 and 60 percent with three (cumulatively).
     */
    public int temporalLayers() {
        return temporalLayers;
    }

    /**
     * {@return whether a frame may be dropped when the rate control's buffer is empty, that is when
     * the stream has spent more bits than the channel delivered; {@code false} by default}
     */
    public boolean dropFrames() {
        return dropFrames;
    }

    /**
     * {@return the size of the rate control's buffer model, as playback time at the target
     * bitrate, from 1 millisecond to {@link Integer#MAX_VALUE} milliseconds; 6 seconds by default}
     *
     * <p>The rate control works in whole milliseconds.
     */
    public Duration bufferSize() {
        return bufferSize;
    }

    /**
     * {@return the initial fullness of the buffer model, from zero to {@link #bufferSize()};
     * 4 seconds by default}
     */
    public Duration initialBufferLevel() {
        return initialBufferLevel;
    }

    /**
     * {@return the fullness the rate control steers the buffer model to, from 1 millisecond to
     * {@link #bufferSize()}; 5 seconds by default}
     */
    public Duration optimalBufferLevel() {
        return optimalBufferLevel;
    }

    /**
     * {@return the base-2 logarithm of the number of tile columns, from 0 to 6; 6 by default}
     *
     * <p>The encoder lowers it to what the picture width allows, a tile being at least 256 pixels
     * wide.
     */
    public int tileColumnsLog2() {
        return tileColumnsLog2;
    }

    /**
     * {@return the most threads that code superblock rows at once, the calling thread included,
     * from 0 to 64; 1 by default}
     *
     * <p>0 lets the encoder use up to {@link com.github.auties00.smalt.MediaRuntime#parallelism()
     * the library's parallelism}, at most 64. Any other value above 1 takes up to that many threads
     * from the module's shared pool. The stream is the same for every value.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return the allocation limits the picture size is checked against;
     * {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Vp9EncoderOptions that && frameRate.equals(that.frameRate)
                && timeBase.equals(that.timeBase) && bitrate == that.bitrate && minQuantizer == that.minQuantizer
                && maxQuantizer == that.maxQuantizer && keyframeInterval.equals(that.keyframeInterval)
                && speed == that.speed && errorResilient == that.errorResilient
                && adaptiveQuantization == that.adaptiveQuantization && temporalLayers == that.temporalLayers
                && dropFrames == that.dropFrames && bufferSize.equals(that.bufferSize)
                && initialBufferLevel.equals(that.initialBufferLevel)
                && optimalBufferLevel.equals(that.optimalBufferLevel) && tileColumnsLog2 == that.tileColumnsLog2
                && threads == that.threads && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(frameRate, timeBase, bitrate, minQuantizer, maxQuantizer, keyframeInterval, speed,
                errorResilient, adaptiveQuantization, temporalLayers, dropFrames, bufferSize, initialBufferLevel,
                optimalBufferLevel, tileColumnsLog2, threads, limits);
    }

    @Override
    public String toString() {
        return "Vp9EncoderOptions[frameRate=" + frameRate + ", timeBase=" + timeBase + ", bitrate=" + bitrate
                + ", quantizers=" + minQuantizer + ".." + maxQuantizer + ", keyframeInterval=" + keyframeInterval
                + ", speed=" + speed + ", errorResilient=" + errorResilient + ", adaptiveQuantization="
                + adaptiveQuantization + ", temporalLayers=" + temporalLayers + ", dropFrames=" + dropFrames
                + ", buffer=" + initialBufferLevel.toMillis() + "/" + optimalBufferLevel.toMillis() + "/"
                + bufferSize.toMillis() + "ms, tileColumnsLog2=" + tileColumnsLog2 + ", threads=" + threads
                + ", limits=" + limits + "]";
    }

    /**
     * A builder of {@link Vp9EncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and the combinations
     * of settings. Instances are not thread-safe.
     */
    public static final class Builder {
        private Rational frameRate = new Rational(30, 1);

        private Rational timeBase = new Rational(1, 90_000);

        private long bitrate = 1_000_000;

        private int minQuantizer;

        private int maxQuantizer = 63;

        private OptionalInt keyframeInterval = OptionalInt.of(128);

        private Vp9EncoderSpeed speed = Vp9EncoderSpeed.SPEED_6;

        private boolean errorResilient;

        private boolean adaptiveQuantization;

        private int temporalLayers = 1;

        private boolean dropFrames;

        private Duration bufferSize = Duration.ofMillis(6000);

        private Duration initialBufferLevel = Duration.ofMillis(4000);

        private Duration optimalBufferLevel = Duration.ofMillis(5000);

        private int tileColumnsLog2 = MAX_TILE_COLUMNS_LOG2;

        private int threads = 1;

        private MediaLimits limits = MediaLimits.DEFAULT;

        private Builder() {
        }

        private Builder(Vp9EncoderOptions from) {
            frameRate = from.frameRate;
            timeBase = from.timeBase;
            bitrate = from.bitrate;
            minQuantizer = from.minQuantizer;
            maxQuantizer = from.maxQuantizer;
            keyframeInterval = from.keyframeInterval;
            speed = from.speed;
            errorResilient = from.errorResilient;
            adaptiveQuantization = from.adaptiveQuantization;
            temporalLayers = from.temporalLayers;
            dropFrames = from.dropFrames;
            bufferSize = from.bufferSize;
            initialBufferLevel = from.initialBufferLevel;
            optimalBufferLevel = from.optimalBufferLevel;
            tileColumnsLog2 = from.tileColumnsLog2;
            threads = from.threads;
            limits = from.limits;
        }

        /**
         * Sets the nominal frame rate; see {@link Vp9EncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base of the timestamps; see {@link Vp9EncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the target bitrate; see {@link Vp9EncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(long bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        /**
         * Sets the quantizer window; see {@link Vp9EncoderOptions#minQuantizer()} and
         * {@link Vp9EncoderOptions#maxQuantizer()}.
         *
         * @param minQuantizer the smallest quantizer
         * @param maxQuantizer the largest quantizer
         * @return this builder
         */
        public Builder quantizers(int minQuantizer, int maxQuantizer) {
            this.minQuantizer = minQuantizer;
            this.maxQuantizer = maxQuantizer;
            return this;
        }

        /**
         * Sets the most frames between two key frames; see
         * {@link Vp9EncoderOptions#keyframeInterval()}.
         *
         * @param frames the value
         * @return this builder
         */
        public Builder keyframeInterval(int frames) {
            this.keyframeInterval = OptionalInt.of(frames);
            return this;
        }

        /**
         * Codes key frames only at the start and on request; see
         * {@link Vp9EncoderOptions#keyframeInterval()}.
         *
         * @return this builder
         */
        public Builder noPeriodicKeyframes() {
            this.keyframeInterval = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the trade between compression and encoding time; see
         * {@link Vp9EncoderOptions#speed()}.
         *
         * @param speed the value
         * @return this builder
         */
        public Builder speed(Vp9EncoderSpeed speed) {
            this.speed = Objects.requireNonNull(speed, "speed");
            return this;
        }

        /**
         * Sets whether frames are error resilient; see {@link Vp9EncoderOptions#errorResilient()}.
         *
         * @param errorResilient the value
         * @return this builder
         */
        public Builder errorResilient(boolean errorResilient) {
            this.errorResilient = errorResilient;
            return this;
        }

        /**
         * Sets whether cyclic refresh is on; see {@link Vp9EncoderOptions#adaptiveQuantization()}.
         *
         * @param adaptiveQuantization the value
         * @return this builder
         */
        public Builder adaptiveQuantization(boolean adaptiveQuantization) {
            this.adaptiveQuantization = adaptiveQuantization;
            return this;
        }

        /**
         * Sets the number of temporal layers; see {@link Vp9EncoderOptions#temporalLayers()}.
         *
         * @param temporalLayers the value
         * @return this builder
         */
        public Builder temporalLayers(int temporalLayers) {
            this.temporalLayers = temporalLayers;
            return this;
        }

        /**
         * Sets whether frames may be dropped; see {@link Vp9EncoderOptions#dropFrames()}.
         *
         * @param dropFrames the value
         * @return this builder
         */
        public Builder dropFrames(boolean dropFrames) {
            this.dropFrames = dropFrames;
            return this;
        }

        /**
         * Sets the size of the buffer model; see {@link Vp9EncoderOptions#bufferSize()}.
         *
         * @param size the value
         * @return this builder
         */
        public Builder bufferSize(Duration size) {
            this.bufferSize = Objects.requireNonNull(size, "size");
            return this;
        }

        /**
         * Sets the initial fullness of the buffer model; see
         * {@link Vp9EncoderOptions#initialBufferLevel()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder initialBufferLevel(Duration level) {
            this.initialBufferLevel = Objects.requireNonNull(level, "level");
            return this;
        }

        /**
         * Sets the fullness the rate control steers the buffer model to; see
         * {@link Vp9EncoderOptions#optimalBufferLevel()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder optimalBufferLevel(Duration level) {
            this.optimalBufferLevel = Objects.requireNonNull(level, "level");
            return this;
        }

        /**
         * Sets the base-2 logarithm of the tile columns; see
         * {@link Vp9EncoderOptions#tileColumnsLog2()}.
         *
         * @param tileColumnsLog2 the value
         * @return this builder
         */
        public Builder tileColumnsLog2(int tileColumnsLog2) {
            this.tileColumnsLog2 = tileColumnsLog2;
            return this;
        }

        /**
         * Sets the thread count; see {@link Vp9EncoderOptions#threads()}. A count above 64 is
         * lowered to 64.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets the allocation limits; see {@link Vp9EncoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the frame rate is not in [0.1, 1000], a time base term
         * is not positive, the bitrate is not in [1, {@link Integer#MAX_VALUE}], the smallest
         * quantizer is not in [0, 63], the largest is not in [smallest, 63], the key frame interval
         * is not positive, the temporal layers are not in [1, 3], the buffer size is not in [1,
         * {@link Integer#MAX_VALUE}] milliseconds, the initial level is not in [0, size], the optimal
         * level is not in [1, size] milliseconds, the tile column logarithm is not in [0, 6], or the
         * thread count is negative
         */
        public Vp9EncoderOptions build() {
            Checks.inRange("frame rate", frameRate.toDouble(), 0.1, 1000);
            Checks.positive("time base numerator", timeBase.numerator());
            Checks.positive("time base denominator", timeBase.denominator());
            Checks.inRange("bitrate", bitrate, 1, Integer.MAX_VALUE);
            Checks.inRange("smallest quantizer", minQuantizer, 0, 63);
            Checks.inRange("largest quantizer", maxQuantizer, minQuantizer, 63);
            if (keyframeInterval.isPresent()) {
                Checks.positive("key frame interval", keyframeInterval.getAsInt());
            }
            Checks.inRange("temporal layers", temporalLayers, 1, 3);
            var sizeMillis = Checks.inRange("buffer size in milliseconds", bufferSize.toMillis(), 1,
                    Integer.MAX_VALUE);
            Checks.inRange("initial buffer level in milliseconds", initialBufferLevel.toMillis(), 0, sizeMillis);
            Checks.inRange("optimal buffer level in milliseconds", optimalBufferLevel.toMillis(), 1, sizeMillis);
            Checks.inRange("tile column logarithm", tileColumnsLog2, 0, MAX_TILE_COLUMNS_LOG2);
            Checks.nonNegative("thread count", threads);
            return new Vp9EncoderOptions(this);
        }
    }
}
