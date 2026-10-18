package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link Av1Encoder}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>The {@linkplain #usage() usage} decides which settings apply; {@link Builder#build()} rejects
 * the combinations the usage cannot code. The picture size is not an option: it is given to the
 * {@link Av1Encoder} constructor.
 */
public final class Av1EncoderOptions {
    /**
     * The largest base-2 logarithm of the tile columns.
     */
    private static final int MAX_TILE_COLUMNS_LOG2 = 6;

    /**
     * The largest number of threads; larger requests are lowered to it.
     */
    private static final int MAX_THREADS = 64;

    /**
     * The key frame target limit of {@link #forRealtime()}, in percent of the per-frame bandwidth:
     * six average frames.
     *
     * @implNote This implementation takes the limit from measurement rather than libaom's
     * real-time-only build default of 300 ({@code rc_max_intra_bitrate_pct}): 600 keeps the first
     * key frame near its limit at almost no cost in quality, where 300 costs noticeably more.
     */
    private static final int REALTIME_MAX_KEYFRAME_BITRATE_PERCENT = 600;

    /**
     * The largest lookahead, in frames.
     */
    private static final int MAX_LOOKAHEAD = 64;

    /**
     * The default lookahead of good-quality coding, libaom's {@code lag-in-frames}.
     */
    private static final int DEFAULT_LOOKAHEAD = 35;

    /**
     * The settings of {@link #defaults()}.
     */
    private static final Av1EncoderOptions DEFAULTS = new Builder().build();

    /**
     * The settings of {@link #forRealtime()}.
     */
    private static final Av1EncoderOptions REALTIME = new Builder()
            .speed(Av1EncoderSpeed.FASTER)
            .quantizers(2, 52)
            .initialBufferLevel(Duration.ofMillis(500))
            .dropFrames(true)
            .errorResilient(true)
            .maxKeyframeBitratePercent(REALTIME_MAX_KEYFRAME_BITRATE_PERCENT)
            .build();

    private final Av1EncoderUsage usage;

    private final int lookahead;

    private final long bitrate;

    private final Rational frameRate;

    private final Rational timeBase;

    private final Av1EncoderSpeed speed;

    private final OptionalInt keyframeInterval;

    private final int minQuantizer;

    private final int maxQuantizer;

    private final Duration bufferSize;

    private final Duration initialBufferLevel;

    private final Duration optimalBufferLevel;

    private final boolean dropFrames;

    private final boolean errorResilient;

    private final boolean adaptiveQuantization;

    private final int temporalLayers;

    private final int tileColumnsLog2;

    private final int threads;

    private final OptionalInt maxKeyframeBitratePercent;

    private final ColorInfo color;

    private final MediaLimits limits;

    private final OptionalInt quality;

    private final ChromaFormat chromaFormat;

    private final int bitDepth;

    private Av1EncoderOptions(Builder builder) {
        this.usage = builder.usage;
        this.lookahead = builder.lookahead;
        this.bitrate = builder.bitrate;
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.speed = builder.speed;
        this.keyframeInterval = builder.keyframeInterval;
        this.minQuantizer = builder.minQuantizer;
        this.maxQuantizer = builder.maxQuantizer;
        this.bufferSize = builder.bufferSize;
        this.initialBufferLevel = builder.initialBufferLevel;
        this.optimalBufferLevel = builder.optimalBufferLevel;
        this.dropFrames = builder.dropFrames;
        this.errorResilient = builder.errorResilient;
        this.adaptiveQuantization = builder.adaptiveQuantization;
        this.temporalLayers = builder.temporalLayers;
        this.tileColumnsLog2 = builder.tileColumnsLog2;
        this.threads = Math.min(builder.threads, MAX_THREADS);
        this.maxKeyframeBitratePercent = builder.maxKeyframeBitratePercent;
        this.color = builder.color;
        this.limits = builder.limits;
        this.quality = builder.quality;
        this.chromaFormat = builder.chromaFormat;
        this.bitDepth = builder.bitDepth;
    }

    /**
     * Returns the default settings: the {@link Av1EncoderUsage#REALTIME} usage at a constant 1
     * Mbit/s and 30 frames per second, the {@link Av1EncoderSpeed#BALANCED} speed, the full
     * quantizer window, no frame dropping, no error resilience and no periodic key frames.
     *
     * @apiNote For files and on-demand video, start from {@link #ofQuality(int)},
     * {@link #ofBitrate(long)} or {@link #forArchive()} instead; for calls, from
     * {@link #forRealtime()}.
     * @return the options
     */
    public static Av1EncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the settings for interactive real-time video such as calls.
     *
     * <p>The frames are error resilient and coded at the {@link Av1EncoderSpeed#FASTER} speed with
     * the quantizer window 2 to 52; frames are dropped when the buffer runs dry, the buffer starts
     * at 500 milliseconds, and key frames are limited to six average frames. Every other setting
     * is that of {@link #defaults()}.
     *
     * @return the options
     */
    public static Av1EncoderOptions forRealtime() {
        return REALTIME;
    }

    /**
     * Returns the settings of intra-only coding at a constant quality: every frame a key frame
     * searched as a still image at the {@link Av1EncoderSpeed#BALANCED} speed, 8-bit 4:2:0.
     *
     * @apiNote Intra-only streams suit editing, archiving frames individually or sequences of
     * unrelated pictures; they are several times larger than predicted video at the same quality,
     * which {@link #ofQuality(int)} codes. The quality scale is the one of the AVIF encoder.
     * @param quality the quality, from 0 (smallest) to 100 (lossless); see {@link #quality()}
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is not in [0, 100]
     */
    public static Av1EncoderOptions forIntraOnly(int quality) {
        return new Builder().usage(Av1EncoderUsage.ALL_INTRA).quality(quality).build();
    }

    /**
     * Returns the settings of good-quality coding at a constant quality, for files and on-demand
     * video: a 35-frame lookahead, pyramids of up to 16 frames with temporally filtered alternate
     * references, key frames on scene cuts and on request, at the {@link Av1EncoderSpeed#SLOW}
     * speed, 8-bit 4:2:0.
     *
     * @apiNote The encoder holds pictures back until its lookahead is full, so packets arrive in
     * bursts of a frame group and the last ones only at {@link Av1Encoder#flush}. 60 is a typical
     * web quality, 75 a high one.
     * @param quality the quality, from 0 (smallest) to 100 (finest); see {@link #quality()}
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is not in [0, 100]
     */
    public static Av1EncoderOptions ofQuality(int quality) {
        return new Builder().usage(Av1EncoderUsage.GOOD_QUALITY).speed(Av1EncoderSpeed.SLOW).quality(quality)
                .build();
    }

    /**
     * Returns the settings of good-quality coding at a variable bitrate: the frame structure of
     * {@link #ofQuality(int)}, with each frame group's quantizers chosen to spend the bitrate,
     * more in complex groups and less in simple ones, at the {@link Av1EncoderSpeed#SLOW} speed.
     *
     * @apiNote The bitrate is met over a few seconds, not per frame; for a bounded buffer, use
     * {@link #forRealtime()}.
     * @param bitrate the target bitrate in bits per second, positive
     * @return the options
     * @throws IllegalArgumentException if {@code bitrate} is not positive
     */
    public static Av1EncoderOptions ofBitrate(long bitrate) {
        return new Builder().usage(Av1EncoderUsage.GOOD_QUALITY).speed(Av1EncoderSpeed.SLOW).bitrate(bitrate)
                .build();
    }

    /**
     * Returns the settings of good-quality coding for archiving: quality 75 at the
     * {@link Av1EncoderSpeed#SLOWER} speed.
     *
     * @return the options
     */
    public static Av1EncoderOptions forArchive() {
        return new Builder().usage(Av1EncoderUsage.GOOD_QUALITY).speed(Av1EncoderSpeed.SLOWER).quality(75).build();
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
     * {@return the coding mode, which decides the settings that apply;
     * {@link Av1EncoderUsage#REALTIME} by default}
     *
     * <ul>
     *   <li>{@link Av1EncoderUsage#REALTIME}: a constant {@linkplain #bitrate() bitrate} with the
     *       buffer model, frame dropping, error resilience, adaptive quantization and temporal
     *       layers; no {@linkplain #quality() quality}; 8-bit 4:2:0.</li>
     *   <li>{@link Av1EncoderUsage#GOOD_QUALITY}: a constant quality when {@link #quality()} is set,
     *       otherwise a variable bitrate; the lookahead and the quantizer window apply, the buffer
     *       model, frame dropping, error resilience, adaptive quantization and temporal layers do
     *       not; 8-bit 4:2:0.</li>
     *   <li>{@link Av1EncoderUsage#ALL_INTRA}: a constant quality, which is required, in the picture
     *       format of {@link #chromaFormat()} and {@link #bitDepth()}; the bitrate, buffer,
     *       quantizer window, frame dropping, adaptive quantization, temporal layer and lookahead
     *       settings do not apply.</li>
     * </ul>
     */
    public Av1EncoderUsage usage() {
        return usage;
    }

    /**
     * {@return the lookahead of good-quality coding in frames, from 0 to 64; 35 by default}
     *
     * <p>The lookahead is the number of pictures the encoder buffers before coding a frame group.
     * Groups use alternate references from a lookahead of 4; the temporal filter and the dependency
     * model see the more frames the longer it is.
     */
    public int lookahead() {
        return lookahead;
    }

    /**
     * {@return the target bitrate in bits per second, positive; 1000000 by default}
     *
     * <p>The bitrate is the constant bitrate of real-time coding, or the variable bitrate of
     * good-quality coding without a {@linkplain #quality() quality}.
     */
    public long bitrate() {
        return bitrate;
    }

    /**
     * {@return the nominal frame rate, from 0.1 to 1000 frames per second, which sizes each frame's
     * share of the bitrate; 30 by default}
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the time base of the frame and packet timestamps; the 90 kHz time base
     * {@code 1/90000} by default}
     */
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * {@return the trade between compression and encoding time; {@link Av1EncoderSpeed#BALANCED} by
     * default}
     */
    public Av1EncoderSpeed speed() {
        return speed;
    }

    /**
     * {@return the distance between periodic key frames in frames, at least 1, or empty when key
     * frames are coded only first, on request and, in the good-quality usage, at scene cuts; empty
     * by default}
     */
    public OptionalInt keyframeInterval() {
        return keyframeInterval;
    }

    /**
     * {@return the smallest quantizer, from 0 to 63; 0 by default}
     *
     * <p>The quantizers use the scale of libaom's {@code min-q} and {@code max-q}; 63 maps to the
     * largest AV1 quantizer index, 255.
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
     * {@return the size of the rate control's buffer model, as playback time at the target
     * bitrate, positive and at most {@link Integer#MAX_VALUE} milliseconds; 1 second by default}
     */
    public Duration bufferSize() {
        return bufferSize;
    }

    /**
     * {@return the initial fullness of the buffer model, from zero to {@link #bufferSize()};
     * 600 milliseconds by default}
     */
    public Duration initialBufferLevel() {
        return initialBufferLevel;
    }

    /**
     * {@return the fullness the rate control steers the buffer model to, positive and at most
     * {@link #bufferSize()}; 600 milliseconds by default}
     */
    public Duration optimalBufferLevel() {
        return optimalBufferLevel;
    }

    /**
     * {@return whether frames are dropped when the buffer model runs low; {@code false} by
     * default}
     *
     * <p>With dropping, the rate control may drop a frame whose buffer is empty, or a coded frame
     * so large that it would empty the buffer.
     */
    public boolean dropFrames() {
        return dropFrames;
    }

    /**
     * {@return whether every frame decodes without the adaptive probabilities of earlier frames,
     * so a lost frame does not corrupt the entropy decoding of the following ones; {@code false} by
     * default}
     */
    public boolean errorResilient() {
        return errorResilient;
    }

    /**
     * {@return whether a cycling share of each inter frame is coded at a finer quantizer;
     * {@code true} by default}
     *
     * <p>The cyclic refresh raises static areas to a high quality over a few seconds and heals the
     * damage of lost frames without key frames.
     */
    public boolean adaptiveQuantization() {
        return adaptiveQuantization;
    }

    /**
     * {@return the number of temporal layers, from 1 to 3; 1 by default}
     *
     * <p>With 2 the frames alternate between the base layer and a top layer no frame predicts from
     * ({@code 0-1-0-1}), with 3 they follow the {@code 0-2-1-2} pattern, so a receiver can drop the
     * top layers to halve or quarter the frame rate. Each frame's layer is in its OBU extension
     * header and in {@link Av1EncoderStats#lastTemporalLayer()}.
     */
    public int temporalLayers() {
        return temporalLayers;
    }

    /**
     * {@return the base-2 logarithm of the number of tile columns, from 0 to 6; 0 by default}
     *
     * <p>The encoder lowers it to what the picture width allows.
     */
    public int tileColumnsLog2() {
        return tileColumnsLog2;
    }

    /**
     * {@return the most threads that code at once, the calling thread included, from 0 to 64; 1 by
     * default}
     *
     * <p>0 lets the encoder use up to {@link com.github.auties00.smalt.MediaRuntime#parallelism()
     * the library's parallelism}, at most 64. Any other value above 1 takes up to that many threads
     * from the module's shared pool. The stream is the same for every value.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return the color description signaled in the sequence header, which also converts RGB
     * pictures; {@link ColorInfo#BT601_LIMITED} by default}
     */
    public ColorInfo color() {
        return color;
    }

    /**
     * {@return the largest key frame target in percent of the per-frame bandwidth, at least 100, or
     * empty for no limit; empty by default}
     *
     * <p>With a limit, a key frame that overshoots its target by more than half is coded again at a
     * coarser quantizer, so key frames stay near the limit.
     */
    public OptionalInt maxKeyframeBitratePercent() {
        return maxKeyframeBitratePercent;
    }

    /**
     * {@return the allocation limits the picture size is checked against;
     * {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the constant quality of good-quality and intra-only coding, from 0 (smallest) to
     * 100 (finest, lossless when intra-only), or empty for a bitrate; empty by default}
     *
     * <p>The quality maps to the quantizer level {@code (100 - quality) * 63 / 100}, rounded to
     * nearest. The all-intra usage requires a quality; the real-time usage accepts none.
     */
    public OptionalInt quality() {
        return quality;
    }

    /**
     * {@return the chroma format of the coded pictures; {@link ChromaFormat#YUV420} by default}
     *
     * <p>Pictures in another format are converted. Only the all-intra usage codes a format other
     * than 4:2:0.
     */
    public ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /**
     * {@return the bit depth of the coded pictures, 8, 10 or 12; 8 by default}
     *
     * <p>Pictures of another depth are converted. Only the all-intra usage codes more than 8 bits.
     */
    public int bitDepth() {
        return bitDepth;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Av1EncoderOptions that && usage == that.usage && lookahead == that.lookahead
                && bitrate == that.bitrate && frameRate.equals(that.frameRate)
                && timeBase.equals(that.timeBase) && speed == that.speed
                && keyframeInterval.equals(that.keyframeInterval) && minQuantizer == that.minQuantizer
                && maxQuantizer == that.maxQuantizer && bufferSize.equals(that.bufferSize)
                && initialBufferLevel.equals(that.initialBufferLevel)
                && optimalBufferLevel.equals(that.optimalBufferLevel) && dropFrames == that.dropFrames
                && errorResilient == that.errorResilient && adaptiveQuantization == that.adaptiveQuantization
                && temporalLayers == that.temporalLayers
                && tileColumnsLog2 == that.tileColumnsLog2
                && threads == that.threads && maxKeyframeBitratePercent.equals(that.maxKeyframeBitratePercent)
                && color.equals(that.color) && limits.equals(that.limits) && quality.equals(that.quality)
                && chromaFormat == that.chromaFormat && bitDepth == that.bitDepth;
    }

    @Override
    public int hashCode() {
        return Objects.hash(usage, lookahead, bitrate, frameRate, timeBase, speed, keyframeInterval, minQuantizer,
                maxQuantizer,
                bufferSize, initialBufferLevel, optimalBufferLevel, dropFrames, errorResilient, adaptiveQuantization,
                temporalLayers, tileColumnsLog2, threads, maxKeyframeBitratePercent, color, limits, quality, chromaFormat,
                bitDepth);
    }

    @Override
    public String toString() {
        return "Av1EncoderOptions[usage=" + usage + ", lookahead=" + lookahead + ", bitrate=" + bitrate
                + ", frameRate=" + frameRate + ", timeBase=" + timeBase
                + ", speed=" + speed + ", keyframeInterval=" + keyframeInterval + ", quantizers=" + minQuantizer
                + ".." + maxQuantizer + ", buffer=" + initialBufferLevel.toMillis() + "/"
                + optimalBufferLevel.toMillis() + "/" + bufferSize.toMillis() + "ms, dropFrames=" + dropFrames
                + ", errorResilient=" + errorResilient + ", adaptiveQuantization=" + adaptiveQuantization
                + ", temporalLayers=" + temporalLayers
                + ", tileColumnsLog2=" + tileColumnsLog2 + ", threads="
                + threads + ", maxKeyframeBitratePercent=" + maxKeyframeBitratePercent + ", color=" + color
                + ", limits=" + limits + ", quality=" + quality + ", chromaFormat=" + chromaFormat + ", bitDepth="
                + bitDepth + "]";
    }

    /**
     * A builder of {@link Av1EncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and the combinations
     * of settings. Instances are not thread-safe.
     */
    public static final class Builder {
        private Av1EncoderUsage usage = Av1EncoderUsage.REALTIME;

        private int lookahead = DEFAULT_LOOKAHEAD;

        private long bitrate = 1_000_000;

        private Rational frameRate = new Rational(30, 1);

        private Rational timeBase = new Rational(1, 90_000);

        private Av1EncoderSpeed speed = Av1EncoderSpeed.BALANCED;

        private OptionalInt keyframeInterval = OptionalInt.empty();

        private int minQuantizer;

        private int maxQuantizer = 63;

        private Duration bufferSize = Duration.ofMillis(1000);

        private Duration initialBufferLevel = Duration.ofMillis(600);

        private Duration optimalBufferLevel = Duration.ofMillis(600);

        private boolean dropFrames;

        private boolean errorResilient;

        private boolean adaptiveQuantization = true;

        private int temporalLayers = 1;

        private int tileColumnsLog2;

        private int threads = 1;

        private OptionalInt maxKeyframeBitratePercent = OptionalInt.empty();

        private ColorInfo color = ColorInfo.BT601_LIMITED;

        private MediaLimits limits = MediaLimits.DEFAULT;

        private OptionalInt quality = OptionalInt.empty();

        private ChromaFormat chromaFormat = ChromaFormat.YUV420;

        private int bitDepth = 8;

        private Builder() {
        }

        private Builder(Av1EncoderOptions from) {
            usage = from.usage;
            lookahead = from.lookahead;
            bitrate = from.bitrate;
            frameRate = from.frameRate;
            timeBase = from.timeBase;
            speed = from.speed;
            keyframeInterval = from.keyframeInterval;
            minQuantizer = from.minQuantizer;
            maxQuantizer = from.maxQuantizer;
            bufferSize = from.bufferSize;
            initialBufferLevel = from.initialBufferLevel;
            optimalBufferLevel = from.optimalBufferLevel;
            dropFrames = from.dropFrames;
            errorResilient = from.errorResilient;
            adaptiveQuantization = from.adaptiveQuantization;
            temporalLayers = from.temporalLayers;
            tileColumnsLog2 = from.tileColumnsLog2;
            threads = from.threads;
            maxKeyframeBitratePercent = from.maxKeyframeBitratePercent;
            color = from.color;
            limits = from.limits;
            quality = from.quality;
            chromaFormat = from.chromaFormat;
            bitDepth = from.bitDepth;
        }

        /**
         * Sets the coding mode; see {@link Av1EncoderOptions#usage()}.
         *
         * @param usage the value
         * @return this builder
         */
        public Builder usage(Av1EncoderUsage usage) {
            this.usage = Objects.requireNonNull(usage, "usage");
            return this;
        }

        /**
         * Sets the good-quality lookahead; see {@link Av1EncoderOptions#lookahead()}.
         *
         * @param lookahead the value
         * @return this builder
         */
        public Builder lookahead(int lookahead) {
            this.lookahead = lookahead;
            return this;
        }

        /**
         * Sets the target bitrate; see {@link Av1EncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(long bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        /**
         * Sets the nominal frame rate; see {@link Av1EncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base of the timestamps; see {@link Av1EncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the trade between compression and encoding time; see
         * {@link Av1EncoderOptions#speed()}.
         *
         * @param speed the value
         * @return this builder
         */
        public Builder speed(Av1EncoderSpeed speed) {
            this.speed = Objects.requireNonNull(speed, "speed");
            return this;
        }

        /**
         * Sets the distance between periodic key frames; see
         * {@link Av1EncoderOptions#keyframeInterval()}.
         *
         * @param frames the value
         * @return this builder
         */
        public Builder keyframeInterval(int frames) {
            this.keyframeInterval = OptionalInt.of(frames);
            return this;
        }

        /**
         * Codes key frames only first, on request and, in the good-quality usage, at scene cuts;
         * see {@link Av1EncoderOptions#keyframeInterval()}.
         *
         * @return this builder
         */
        public Builder noPeriodicKeyframes() {
            this.keyframeInterval = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the quantizer window; see {@link Av1EncoderOptions#minQuantizer()} and
         * {@link Av1EncoderOptions#maxQuantizer()}.
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
         * Sets the size of the buffer model; see {@link Av1EncoderOptions#bufferSize()}.
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
         * {@link Av1EncoderOptions#initialBufferLevel()}.
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
         * {@link Av1EncoderOptions#optimalBufferLevel()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder optimalBufferLevel(Duration level) {
            this.optimalBufferLevel = Objects.requireNonNull(level, "level");
            return this;
        }

        /**
         * Sets whether frames may be dropped; see {@link Av1EncoderOptions#dropFrames()}.
         *
         * @param dropFrames the value
         * @return this builder
         */
        public Builder dropFrames(boolean dropFrames) {
            this.dropFrames = dropFrames;
            return this;
        }

        /**
         * Sets whether frames are error resilient; see {@link Av1EncoderOptions#errorResilient()}.
         *
         * @param errorResilient the value
         * @return this builder
         */
        public Builder errorResilient(boolean errorResilient) {
            this.errorResilient = errorResilient;
            return this;
        }

        /**
         * Sets whether adaptive quantization is on; see
         * {@link Av1EncoderOptions#adaptiveQuantization()}.
         *
         * @param adaptiveQuantization the value
         * @return this builder
         */
        public Builder adaptiveQuantization(boolean adaptiveQuantization) {
            this.adaptiveQuantization = adaptiveQuantization;
            return this;
        }

        /**
         * Sets the number of temporal layers; see {@link Av1EncoderOptions#temporalLayers()}.
         *
         * @param temporalLayers the value
         * @return this builder
         */
        public Builder temporalLayers(int temporalLayers) {
            this.temporalLayers = temporalLayers;
            return this;
        }

        /**
         * Sets the base-2 logarithm of the tile columns; see
         * {@link Av1EncoderOptions#tileColumnsLog2()}.
         *
         * @param tileColumnsLog2 the value
         * @return this builder
         */
        public Builder tileColumnsLog2(int tileColumnsLog2) {
            this.tileColumnsLog2 = tileColumnsLog2;
            return this;
        }

        /**
         * Sets the thread count; see {@link Av1EncoderOptions#threads()}. A count above 64 is
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
         * Sets the largest key frame target; see
         * {@link Av1EncoderOptions#maxKeyframeBitratePercent()}.
         *
         * @param percent the value
         * @return this builder
         */
        public Builder maxKeyframeBitratePercent(int percent) {
            this.maxKeyframeBitratePercent = OptionalInt.of(percent);
            return this;
        }

        /**
         * Leaves key frame targets without a limit; see
         * {@link Av1EncoderOptions#maxKeyframeBitratePercent()}.
         *
         * @return this builder
         */
        public Builder noMaxKeyframeBitrate() {
            this.maxKeyframeBitratePercent = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the signaled color description; see {@link Av1EncoderOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Sets the allocation limits; see {@link Av1EncoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the constant quality; see {@link Av1EncoderOptions#quality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder quality(int quality) {
            this.quality = OptionalInt.of(quality);
            return this;
        }

        /**
         * Clears the constant quality, so the rate control chooses the quantizers that spend
         * {@link Av1EncoderOptions#bitrate()}; see {@link Av1EncoderOptions#quality()}.
         *
         * @return this builder
         */
        public Builder automaticQuality() {
            this.quality = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the coded picture format; see {@link Av1EncoderOptions#chromaFormat()} and
         * {@link Av1EncoderOptions#bitDepth()}.
         *
         * @param chromaFormat the chroma format
         * @param bitDepth the bit depth
         * @return this builder
         */
        public Builder pictureFormat(ChromaFormat chromaFormat, int bitDepth) {
            this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
            this.bitDepth = bitDepth;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the quality is not in [0, 100], the bit depth is not 8,
         * 10 or 12, the lookahead is not in [0, 64], the bitrate is not positive, the frame rate is
         * not in [0.1, 1000], a time base term is not positive, the key frame interval is not
         * positive, the smallest quantizer is not in [0, 63], the largest is not in [smallest, 63],
         * the buffer size is not positive or exceeds {@link Integer#MAX_VALUE} milliseconds, the
         * initial level is not in [0, size], the optimal level is not positive or exceeds the size,
         * the temporal layers are not in [1, 3], the tile column logarithm is not in [0, 6], the
         * thread count is negative, the key frame target limit is below 100, the real-time usage has
         * a quality, the all-intra usage has none, or a usage other than all-intra has a picture
         * format other than 8-bit 4:2:0
         */
        public Av1EncoderOptions build() {
            if (quality.isPresent()) {
                Checks.inRange("quality", quality.getAsInt(), 0, 100);
            }
            if (bitDepth != 8 && bitDepth != 10 && bitDepth != 12) {
                throw new IllegalArgumentException("bit depth " + bitDepth + " is not 8, 10 or 12");
            }
            Checks.inRange("lookahead", lookahead, 0, MAX_LOOKAHEAD);
            Checks.positive("bitrate", bitrate);
            Checks.inRange("frame rate", frameRate.toDouble(), 0.1, 1000);
            Checks.positive("time base numerator", timeBase.numerator());
            Checks.positive("time base denominator", timeBase.denominator());
            if (keyframeInterval.isPresent()) {
                Checks.positive("key frame interval", keyframeInterval.getAsInt());
            }
            Checks.inRange("smallest quantizer", minQuantizer, 0, 63);
            Checks.inRange("largest quantizer", maxQuantizer, minQuantizer, 63);
            Checks.positive("buffer size", bufferSize);
            Checks.inRange("buffer size in milliseconds", bufferSize.toMillis(), 0, Integer.MAX_VALUE);
            Checks.inRange("initial buffer level", initialBufferLevel, Duration.ZERO, bufferSize);
            Checks.positive("optimal buffer level", optimalBufferLevel);
            Checks.inRange("optimal buffer level", optimalBufferLevel, Duration.ZERO, bufferSize);
            Checks.inRange("temporal layers", temporalLayers, 1, 3);
            Checks.inRange("tile column logarithm", tileColumnsLog2, 0, MAX_TILE_COLUMNS_LOG2);
            Checks.nonNegative("thread count", threads);
            if (maxKeyframeBitratePercent.isPresent()) {
                Checks.inRange("key frame bitrate percent", maxKeyframeBitratePercent.getAsInt(), 100,
                        Integer.MAX_VALUE);
            }
            if (usage == Av1EncoderUsage.REALTIME && quality.isPresent()) {
                throw new IllegalArgumentException("real-time coding has no constant quality; use the all-intra or "
                        + "good-quality usage");
            }
            if (usage == Av1EncoderUsage.ALL_INTRA && quality.isEmpty()) {
                throw new IllegalArgumentException("all-intra coding needs a quality");
            }
            if (usage != Av1EncoderUsage.ALL_INTRA && (chromaFormat != ChromaFormat.YUV420 || bitDepth != 8)) {
                throw new IllegalArgumentException(usage + " coding supports 8-bit 4:2:0 only, got " + bitDepth
                        + "-bit " + chromaFormat);
            }
            return new Av1EncoderOptions(this);
        }
    }
}
