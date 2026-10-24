package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link Vp8Encoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>The {@linkplain #usage() usage} decides which settings apply; {@link Builder#build()} rejects
 * the combinations the usage cannot code. The picture size is not an option: it is given to the
 * {@link Vp8Encoder} constructor.
 *
 * @apiNote The settings follow libvpx's VP8 configuration: {@link #usage()} is {@code --rt},
 * {@code --good} or {@code --best}, {@link #rateControl()} is {@code rc_end_usage}, {@link #bitrate()} is
 * {@code rc_target_bitrate} in bits rather than kilobits per second, the quantizer window is
 * {@code rc_min_quantizer} and {@code rc_max_quantizer} on the same scale, {@link #cqLevel()} is
 * {@code cq-level}, {@link #lookahead()} is {@code lag-in-frames}, {@link #alternateReferences()}
 * is {@code auto-alt-ref}, the temporal filter is {@code arnr-strength} and {@code arnr-maxframes},
 * {@link #firstPassStats()} is the {@code fpf} of the second pass, {@link #keyframeInterval()}
 * is {@code kf_max_dist}, {@link #speed()} is the magnitude of {@code cpu-used},
 * {@link #errorResilient()} is {@code g_error_resilient}, the buffer model is {@code rc_buf_sz},
 * {@code rc_buf_initial_sz} and {@code rc_buf_optimal_sz}, {@link #tokenPartitions()} is
 * {@code 2^token_partitions}, {@link #encodeBreakout()} is the static threshold, the rate
 * tolerances are {@code rc_undershoot_pct} and {@code rc_overshoot_pct}, and
 * {@link #maxKeyframeBitratePercent()} is {@code rc_max_intra_bitrate_pct}.
 */
public final class Vp8EncoderOptions {
    /**
     * The largest number of threads; larger requests are lowered to it.
     */
    private static final int MAX_THREADS = 64;

    /**
     * The largest lookahead, in frames ({@code MAX_LAG_BUFFERS}).
     */
    private static final int MAX_LOOKAHEAD = 25;

    /**
     * The settings of {@link #defaults()}.
     */
    private static final Vp8EncoderOptions DEFAULTS = new Builder().build();

    /**
     * The settings of {@link #forRealtime()}.
     */
    private static final Vp8EncoderOptions REALTIME = new Builder()
            .noPeriodicKeyframes()
            .errorResilient(true)
            .dropFrames(true)
            .bufferSize(Duration.ofMillis(1000))
            .initialBufferLevel(Duration.ofMillis(500))
            .optimalBufferLevel(Duration.ofMillis(600))
            .rateTolerance(100, 15)
            .maxKeyframeBitratePercent(900)
            .build();

    private final Vp8EncoderUsage usage;

    private final Vp8RateControl rateControl;

    private final Rational frameRate;

    private final Rational timeBase;

    private final long bitrate;

    private final int minQuantizer;

    private final int maxQuantizer;

    private final int cqLevel;

    private final int lookahead;

    private final boolean alternateReferences;

    private final int temporalFilterStrength;

    private final int temporalFilterFrames;

    private final Optional<Vp8FirstPassStats> firstPassStats;

    private final OptionalInt keyframeInterval;

    private final Vp8EncoderSpeed speed;

    private final boolean errorResilient;

    private final int temporalLayers;

    private final boolean dropFrames;

    private final Duration bufferSize;

    private final Duration initialBufferLevel;

    private final Duration optimalBufferLevel;

    private final int tokenPartitions;

    private final int threads;

    private final int encodeBreakout;

    private final int undershootPercent;

    private final int overshootPercent;

    private final OptionalInt maxKeyframeBitratePercent;

    private final MediaLimits limits;

    private Vp8EncoderOptions(Builder builder) {
        this.usage = builder.usage;
        this.rateControl = builder.rateControl;
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.bitrate = builder.bitrate;
        this.minQuantizer = builder.minQuantizer;
        this.maxQuantizer = builder.maxQuantizer;
        this.cqLevel = builder.cqLevel;
        this.lookahead = builder.lookahead;
        this.alternateReferences = builder.alternateReferences;
        this.temporalFilterStrength = builder.temporalFilterStrength;
        this.temporalFilterFrames = builder.temporalFilterFrames;
        this.firstPassStats = builder.firstPassStats;
        this.keyframeInterval = builder.keyframeInterval;
        this.speed = builder.speed;
        this.errorResilient = builder.errorResilient;
        this.temporalLayers = builder.temporalLayers;
        this.dropFrames = builder.dropFrames;
        this.bufferSize = builder.bufferSize;
        this.initialBufferLevel = builder.initialBufferLevel;
        this.optimalBufferLevel = builder.optimalBufferLevel;
        this.tokenPartitions = builder.tokenPartitions;
        this.threads = Math.min(builder.threads, MAX_THREADS);
        this.encodeBreakout = builder.encodeBreakout;
        this.undershootPercent = builder.undershootPercent;
        this.overshootPercent = builder.overshootPercent;
        this.maxKeyframeBitratePercent = builder.maxKeyframeBitratePercent;
        this.limits = builder.limits;
    }

    /**
     * Returns the general-purpose real-time settings: the {@link Vp8EncoderUsage#REALTIME} usage at
     * a constant 1 Mbit/s, a key frame every 128 frames, one temporal layer, no error resilience
     * and no frame dropping.
     *
     * @apiNote For files and on-demand video, start from {@link #ofBitrate(long)},
     * {@link #ofQuality(int)} or {@link #forArchive()} instead; for calls, from
     * {@link #forRealtime()}.
     * @return the options
     */
    public static Vp8EncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the settings of a real-time stream at a nominal 30 frames per second, such as a
     * video call.
     *
     * <p>The stream is error resilient and drops frames when the buffer is empty, has no periodic
     * key frames, and uses a one second buffer starting half full and aiming for 600 milliseconds,
     * an overshoot limit of 15 percent and a key frame target limit of 900 percent. Every other
     * setting is that of {@link #defaults()}.
     *
     * @apiNote The key frame target limit suits 30 frames per second; {@link #forRealtime(Rational)}
     * scales it to another frame rate.
     * @return the options
     */
    public static Vp8EncoderOptions forRealtime() {
        return REALTIME;
    }

    /**
     * Returns the settings of {@link #forRealtime()} at another nominal frame rate, with the key
     * frame target limit scaled to it.
     *
     * <p>The limit is {@code 600 * frameRate / 20} percent of the per-frame bandwidth, rounded
     * down and at least 300 (three frames' worth). Every other setting is that of
     * {@link #forRealtime()}, so {@code forRealtime(new Rational(30, 1))} equals
     * {@code forRealtime()}.
     *
     * @implNote This implementation takes the limit from WebRTC, which derives it from the 600
     * millisecond optimal buffer level of {@link #forRealtime()}.
     * @param frameRate the nominal frame rate in frames per second, from 0.1 to 1000
     * @return the options
     * @throws IllegalArgumentException if {@code frameRate} is not in [0.1, 1000]
     */
    public static Vp8EncoderOptions forRealtime(Rational frameRate) {
        var percent = Math.max(300, (int) Math.min(Integer.MAX_VALUE,
                (long) (REALTIME.optimalBufferLevel.toMillis() * frameRate.toDouble() / 20)));
        return REALTIME.toBuilder().frameRate(frameRate).maxKeyframeBitratePercent(percent).build();
    }

    /**
     * Returns the settings of good-quality coding at a variable bitrate, for files and on-demand
     * video: a 25-frame lookahead, golden frame groups predicted from temporally filtered hidden
     * alternate references, key frames at scene cuts, every 128 frames and on request, at the
     * {@link Vp8EncoderSpeed#SPEED_2} speed.
     *
     * @apiNote The encoder holds pictures back until its lookahead is full, so the last packets
     * arrive only at {@link Vp8Encoder#flush}. The bitrate is met over the stream, not per frame;
     * for a bounded buffer, use {@link #forRealtime()}. A first pass ({@link Vp8FirstPass}) lets
     * the bitrate follow the content of the whole clip.
     * @param bitrate the target bitrate in bits per second, from 1 to {@link Integer#MAX_VALUE}
     * @return the options
     * @throws IllegalArgumentException if {@code bitrate} is not in [1, {@link Integer#MAX_VALUE}]
     */
    public static Vp8EncoderOptions ofBitrate(long bitrate) {
        return goodQuality().rateControl(Vp8RateControl.VARIABLE_BITRATE).bitrate(bitrate).build();
    }

    /**
     * Returns the settings of good-quality coding at a constant quality: the frame structure of
     * {@link #ofBitrate(long)} with the quantizers following a quality level instead of a bitrate.
     *
     * @apiNote Lower levels are finer; 10 to 20 suit high quality, 25 to 35 web video.
     * @param cqLevel the quality level, from 4 (the finest the default quantizer window allows) to
     * 63; see {@link #cqLevel()}
     * @return the options
     * @throws IllegalArgumentException if {@code cqLevel} is not in [4, 63]
     */
    public static Vp8EncoderOptions ofQuality(int cqLevel) {
        return goodQuality().rateControl(Vp8RateControl.CONSTANT_QUALITY).cqLevel(cqLevel).build();
    }

    /**
     * Returns the settings of good-quality coding for archiving: constant quality level 10 at the
     * {@link Vp8EncoderSpeed#SPEED_1} speed, with the other settings of {@link #ofQuality(int)}.
     *
     * @return the options
     */
    public static Vp8EncoderOptions forArchive() {
        return goodQuality().rateControl(Vp8RateControl.CONSTANT_QUALITY).cqLevel(10)
                .speed(Vp8EncoderSpeed.SPEED_1).build();
    }

    /**
     * Returns a builder of the good-quality usage at the {@link Vp8EncoderSpeed#SPEED_2} speed.
     *
     * @return the builder
     */
    private static Builder goodQuality() {
        return new Builder().usage(Vp8EncoderUsage.GOOD_QUALITY).speed(Vp8EncoderSpeed.SPEED_2);
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
     * {@link Vp8EncoderUsage#REALTIME} by default}
     *
     * <ul>
     *   <li>{@link Vp8EncoderUsage#REALTIME}: the {@link Vp8RateControl#CONSTANT_BITRATE} rate
     *       control with the buffer model, frame dropping, error resilience, temporal layers, the
     *       rate tolerances and the key frame target limit, at the speeds from
     *       {@link Vp8EncoderSpeed#SPEED_4}; the
     *       lookahead, the alternate references and the first-pass statistics do not apply.</li>
     *   <li>{@link Vp8EncoderUsage#GOOD_QUALITY}: the variable bitrate, constrained quality and
     *       constant quality rate controls, the lookahead, the alternate references with their
     *       temporal filter and the first-pass statistics, at the speeds up to
     *       {@link Vp8EncoderSpeed#SPEED_4}; the buffer model, frame dropping, error resilience,
     *       temporal layers, rate tolerances and key frame target limit do not apply. A hidden
     *       alternate reference frame is a packet of its own, flagged
     *       {@link com.github.auties00.smalt.track.PacketFlag#DISCARD}.</li>
     *   <li>{@link Vp8EncoderUsage#BEST_QUALITY}: the settings of the good-quality usage, with the
     *       fullest search whatever the speed; the speed, from {@link Vp8EncoderSpeed#SPEED_0} to
     *       {@link Vp8EncoderSpeed#SPEED_4}, changes nothing in the stream, as libvpx ignores
     *       {@code cpu-used} at its best quality.</li>
     * </ul>
     */
    public Vp8EncoderUsage usage() {
        return usage;
    }

    /**
     * {@return the rate control; {@link Vp8RateControl#CONSTANT_BITRATE} by default}
     *
     * <p>Real-time coding requires {@link Vp8RateControl#CONSTANT_BITRATE}; good-quality and
     * best-quality coding require one of the others.
     */
    public Vp8RateControl rateControl() {
        return rateControl;
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
     * change of target within about a second. A target below what the content needs at the
     * {@linkplain #maxQuantizer() largest quantizer} cannot be met by coding alone: the buffer then
     * empties, and after such a period a raised target is undershot for a few seconds while the
     * buffer refills.
     */
    public long bitrate() {
        return bitrate;
    }

    /**
     * {@return the smallest quantizer index, from 0 to 63; 4 by default}
     */
    public int minQuantizer() {
        return minQuantizer;
    }

    /**
     * {@return the largest quantizer index, from {@link #minQuantizer()} to 63; 63 by default}
     */
    public int maxQuantizer() {
        return maxQuantizer;
    }

    /**
     * {@return the quality level of the {@link Vp8RateControl#CONSTANT_QUALITY} and
     * {@link Vp8RateControl#CONSTRAINED_QUALITY} rate controls, from 0 to 63 on the scale of
     * {@link #minQuantizer()} and within the quantizer window when they use it; 32 by default}
     *
     * <p>Lower levels are finer. Under constant quality, the frames of a group use the level and,
     * for the frames other frames predict from, finer quantizers; under constrained quality, no
     * frame uses a quantizer finer than the level allows.
     */
    public int cqLevel() {
        return cqLevel;
    }

    /**
     * {@return the number of pictures good-quality coding buffers before it codes a frame, from 0
     * to 25; 25 by default}
     *
     * <p>The lookahead bounds the golden frame groups and the alternate references: a group with
     * an alternate reference is shorter than the lookahead, so a lookahead below 5 codes none.
     */
    public int lookahead() {
        return lookahead;
    }

    /**
     * {@return whether good-quality coding codes hidden alternate references; {@code true} by
     * default}
     *
     * <p>An alternate reference is a temporally filtered copy of the picture that ends a golden
     * frame group, coded before the group in a packet of its own, flagged
     * {@link com.github.auties00.smalt.track.PacketFlag#DISCARD} because it is decoded but never
     * shown; the frames of the group predict from it, and the picture itself is coded again when
     * its turn comes.
     */
    public boolean alternateReferences() {
        return alternateReferences;
    }

    /**
     * {@return the strength of the temporal filter of the alternate references, from 0 (no
     * filtering) to 6; 5 by default}
     */
    public int temporalFilterStrength() {
        return temporalFilterStrength;
    }

    /**
     * {@return the most pictures the temporal filter of an alternate reference averages, the
     * filtered picture included, from 0 to 15; 7 by default}
     *
     * <p>0 and 1 code the alternate reference from its picture alone.
     */
    public int temporalFilterFrames() {
        return temporalFilterFrames;
    }

    /**
     * {@return the first-pass statistics of the clip, which make this encode the second pass of a
     * two-pass encode, or empty for one pass; empty by default}
     *
     * <p>With statistics, the key frames and the bitrate of each golden frame group follow the
     * whole clip rather than the lookahead; the encoder then accepts at most as many pictures as
     * the statistics hold.
     */
    public Optional<Vp8FirstPassStats> firstPassStats() {
        return firstPassStats;
    }

    /**
     * {@return the most frames between two key frames, at least 1, or empty for key frames only at
     * the start, on request and, in good-quality coding, at scene cuts; 128 by default}
     */
    public OptionalInt keyframeInterval() {
        return keyframeInterval;
    }

    /**
     * {@return the trade between compression and encoding time; {@link Vp8EncoderSpeed#SPEED_6} by
     * default}
     *
     * <p>Real-time coding requires a {@linkplain Vp8EncoderSpeed#realtime() real-time speed},
     * good-quality and best-quality coding a
     * {@linkplain Vp8EncoderSpeed#goodQuality() good-quality speed}.
     */
    public Vp8EncoderSpeed speed() {
        return speed;
    }

    /**
     * {@return whether the stream is coded for channels that lose frames; {@code false} by
     * default}
     *
     * <p>With error resilience, every frame codes its probability updates for itself only and
     * repeats the loop filter deltas, and the golden frame is refreshed only by key frames, so a
     * receiver that lost frames decodes the next frame correctly as long as it holds the frames it
     * references. Without it, the golden frame is refreshed periodically in static scenes and the
     * previous golden frame becomes the alternate reference. More than one
     * {@linkplain #temporalLayers() temporal layer} implies error resilience. Only real-time coding
     * is error resilient.
     */
    public boolean errorResilient() {
        return errorResilient;
    }

    /**
     * {@return the number of temporal layers, from 1 to 3; 1 by default}
     *
     * <p>With two layers the frames alternate between layer 0, which references and refreshes the
     * last frame, and layer 1, which references the last and golden frames and refreshes the golden
     * frame; with three layers the pattern of four frames is layer 0, 2, 1, 2, layer 2 refreshing no
     * reference. Layer 0 receives 60 percent of the bitrate with two layers, 40 and 60 percent with
     * three (cumulatively). More than one layer implies {@linkplain #errorResilient() error
     * resilience}.
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
     * {@return the number of token partitions, 1, 2, 4 or 8; 1 by default}
     */
    public int tokenPartitions() {
        return tokenPartitions;
    }

    /**
     * {@return the most threads that code macroblock rows at once, the calling thread included,
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
     * {@return the static threshold of the encode breakout, from 0 (off) to {@code 2^24}; 0 by
     * default}
     *
     * <p>When it is positive, an inter macroblock whose prediction error is below both this
     * threshold and a sixteenth of the squared luma quantizer step, and whose chroma prediction
     * error is below half of it, ends the mode search and is coded without residual. The stream
     * then differs from the stream coded without a threshold.
     */
    public int encodeBreakout() {
        return encodeBreakout;
    }

    /**
     * {@return the undershoot limit in percent, from 0 to 100; 100 by default}
     *
     * <p>While the buffer is below its optimal level, a frame's target shrinks below the per-frame
     * bandwidth by half of the shortfall in percent of the optimal level, at most by half of this
     * percentage.
     */
    public int undershootPercent() {
        return undershootPercent;
    }

    /**
     * {@return the overshoot limit in percent, from 0 to 1000; 100 by default}
     *
     * <p>While the buffer is above its optimal level, a frame's target grows above the per-frame
     * bandwidth by half of the excess in percent of the optimal level, at most by half of this
     * percentage. A small limit keeps frame sizes closer to the per-frame bandwidth after the buffer
     * filled up, at the cost of a slower refill.
     */
    public int overshootPercent() {
        return overshootPercent;
    }

    /**
     * {@return the largest key frame target in percent of the per-frame bandwidth, at least 100, or
     * empty for key frame targets without a limit; empty by default}
     */
    public OptionalInt maxKeyframeBitratePercent() {
        return maxKeyframeBitratePercent;
    }

    /**
     * {@return the allocation limits the picture size is checked against; {@link MediaLimits#DEFAULT}
     * by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Vp8EncoderOptions that && usage == that.usage && rateControl == that.rateControl
                && frameRate.equals(that.frameRate) && timeBase.equals(that.timeBase) && bitrate == that.bitrate
                && minQuantizer == that.minQuantizer && maxQuantizer == that.maxQuantizer && cqLevel == that.cqLevel
                && lookahead == that.lookahead && alternateReferences == that.alternateReferences
                && temporalFilterStrength == that.temporalFilterStrength
                && temporalFilterFrames == that.temporalFilterFrames && firstPassStats.equals(that.firstPassStats)
                && keyframeInterval.equals(that.keyframeInterval)
                && speed == that.speed && errorResilient == that.errorResilient
                && temporalLayers == that.temporalLayers && dropFrames == that.dropFrames
                && bufferSize.equals(that.bufferSize) && initialBufferLevel.equals(that.initialBufferLevel)
                && optimalBufferLevel.equals(that.optimalBufferLevel) && tokenPartitions == that.tokenPartitions
                && threads == that.threads && encodeBreakout == that.encodeBreakout
                && undershootPercent == that.undershootPercent && overshootPercent == that.overshootPercent
                && maxKeyframeBitratePercent.equals(that.maxKeyframeBitratePercent) && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(usage, rateControl, frameRate, timeBase, bitrate, minQuantizer, maxQuantizer, cqLevel,
                lookahead, alternateReferences, temporalFilterStrength, temporalFilterFrames, firstPassStats,
                keyframeInterval, speed,
                errorResilient, temporalLayers, dropFrames, bufferSize, initialBufferLevel, optimalBufferLevel,
                tokenPartitions, threads, encodeBreakout, undershootPercent, overshootPercent,
                maxKeyframeBitratePercent, limits);
    }

    @Override
    public String toString() {
        return "Vp8EncoderOptions[usage=" + usage + ", rateControl=" + rateControl + ", frameRate=" + frameRate
                + ", timeBase=" + timeBase + ", bitrate=" + bitrate + ", quantizers=" + minQuantizer + ".."
                + maxQuantizer + ", cqLevel=" + cqLevel + ", lookahead=" + lookahead + ", alternateReferences="
                + alternateReferences + ", temporalFilter=" + temporalFilterStrength + "/" + temporalFilterFrames
                + ", firstPassStats=" + firstPassStats + ", keyframeInterval=" + keyframeInterval
                + ", speed=" + speed + ", errorResilient=" + errorResilient + ", temporalLayers=" + temporalLayers
                + ", dropFrames=" + dropFrames + ", buffer=" + initialBufferLevel.toMillis() + "/"
                + optimalBufferLevel.toMillis() + "/" + bufferSize.toMillis() + "ms, tokenPartitions="
                + tokenPartitions + ", threads=" + threads + ", encodeBreakout=" + encodeBreakout
                + ", rateTolerance=" + undershootPercent + "/" + overshootPercent + ", maxKeyframeBitratePercent="
                + maxKeyframeBitratePercent + ", limits=" + limits + "]";
    }

    /**
     * A builder of {@link Vp8EncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and the combinations
     * of settings. Instances are not thread-safe.
     */
    public static final class Builder {
        private Vp8EncoderUsage usage = Vp8EncoderUsage.REALTIME;

        private Vp8RateControl rateControl = Vp8RateControl.CONSTANT_BITRATE;

        private Rational frameRate = new Rational(30, 1);

        private Rational timeBase = new Rational(1, 90_000);

        private long bitrate = 1_000_000;

        private int minQuantizer = 4;

        private int maxQuantizer = 63;

        private int cqLevel = 32;

        private int lookahead = MAX_LOOKAHEAD;

        private boolean alternateReferences = true;

        private int temporalFilterStrength = 5;

        private int temporalFilterFrames = 7;

        private Optional<Vp8FirstPassStats> firstPassStats = Optional.empty();

        private OptionalInt keyframeInterval = OptionalInt.of(128);

        private Vp8EncoderSpeed speed = Vp8EncoderSpeed.SPEED_6;

        private boolean errorResilient;

        private int temporalLayers = 1;

        private boolean dropFrames;

        private Duration bufferSize = Duration.ofMillis(6000);

        private Duration initialBufferLevel = Duration.ofMillis(4000);

        private Duration optimalBufferLevel = Duration.ofMillis(5000);

        private int tokenPartitions = 1;

        private int threads = 1;

        private int encodeBreakout;

        private int undershootPercent = 100;

        private int overshootPercent = 100;

        private OptionalInt maxKeyframeBitratePercent = OptionalInt.empty();

        private MediaLimits limits = MediaLimits.DEFAULT;

        private Builder() {
        }

        private Builder(Vp8EncoderOptions from) {
            usage = from.usage;
            rateControl = from.rateControl;
            frameRate = from.frameRate;
            timeBase = from.timeBase;
            bitrate = from.bitrate;
            minQuantizer = from.minQuantizer;
            maxQuantizer = from.maxQuantizer;
            cqLevel = from.cqLevel;
            lookahead = from.lookahead;
            alternateReferences = from.alternateReferences;
            temporalFilterStrength = from.temporalFilterStrength;
            temporalFilterFrames = from.temporalFilterFrames;
            firstPassStats = from.firstPassStats;
            keyframeInterval = from.keyframeInterval;
            speed = from.speed;
            errorResilient = from.errorResilient;
            temporalLayers = from.temporalLayers;
            dropFrames = from.dropFrames;
            bufferSize = from.bufferSize;
            initialBufferLevel = from.initialBufferLevel;
            optimalBufferLevel = from.optimalBufferLevel;
            tokenPartitions = from.tokenPartitions;
            threads = from.threads;
            encodeBreakout = from.encodeBreakout;
            undershootPercent = from.undershootPercent;
            overshootPercent = from.overshootPercent;
            maxKeyframeBitratePercent = from.maxKeyframeBitratePercent;
            limits = from.limits;
        }

        /**
         * Sets the coding mode; see {@link Vp8EncoderOptions#usage()}.
         *
         * @param usage the value
         * @return this builder
         */
        public Builder usage(Vp8EncoderUsage usage) {
            this.usage = Objects.requireNonNull(usage, "usage");
            return this;
        }

        /**
         * Sets the rate control; see {@link Vp8EncoderOptions#rateControl()}.
         *
         * @param rateControl the value
         * @return this builder
         */
        public Builder rateControl(Vp8RateControl rateControl) {
            this.rateControl = Objects.requireNonNull(rateControl, "rateControl");
            return this;
        }

        /**
         * Sets the nominal frame rate; see {@link Vp8EncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base of the timestamps; see {@link Vp8EncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the target bitrate; see {@link Vp8EncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(long bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        /**
         * Sets the quantizer window; see {@link Vp8EncoderOptions#minQuantizer()} and
         * {@link Vp8EncoderOptions#maxQuantizer()}.
         *
         * @param minQuantizer the smallest quantizer index
         * @param maxQuantizer the largest quantizer index
         * @return this builder
         */
        public Builder quantizers(int minQuantizer, int maxQuantizer) {
            this.minQuantizer = minQuantizer;
            this.maxQuantizer = maxQuantizer;
            return this;
        }

        /**
         * Sets the quality level; see {@link Vp8EncoderOptions#cqLevel()}.
         *
         * @param cqLevel the value
         * @return this builder
         */
        public Builder cqLevel(int cqLevel) {
            this.cqLevel = cqLevel;
            return this;
        }

        /**
         * Sets the lookahead; see {@link Vp8EncoderOptions#lookahead()}.
         *
         * @param frames the value
         * @return this builder
         */
        public Builder lookahead(int frames) {
            this.lookahead = frames;
            return this;
        }

        /**
         * Sets whether alternate references are coded; see
         * {@link Vp8EncoderOptions#alternateReferences()}.
         *
         * @param alternateReferences the value
         * @return this builder
         */
        public Builder alternateReferences(boolean alternateReferences) {
            this.alternateReferences = alternateReferences;
            return this;
        }

        /**
         * Sets the temporal filter of the alternate references; see
         * {@link Vp8EncoderOptions#temporalFilterStrength()} and
         * {@link Vp8EncoderOptions#temporalFilterFrames()}.
         *
         * @param strength the filter strength
         * @param frames the most pictures averaged
         * @return this builder
         */
        public Builder temporalFilter(int strength, int frames) {
            this.temporalFilterStrength = strength;
            this.temporalFilterFrames = frames;
            return this;
        }

        /**
         * Makes the encode the second pass of a two-pass encode; see
         * {@link Vp8EncoderOptions#firstPassStats()}.
         *
         * @param stats the first-pass statistics of the clip
         * @return this builder
         */
        public Builder firstPassStats(Vp8FirstPassStats stats) {
            this.firstPassStats = Optional.of(stats);
            return this;
        }

        /**
         * Makes the encode a one-pass encode; see {@link Vp8EncoderOptions#firstPassStats()}.
         *
         * @return this builder
         */
        public Builder noFirstPassStats() {
            this.firstPassStats = Optional.empty();
            return this;
        }

        /**
         * Sets the periodic key frame distance; see {@link Vp8EncoderOptions#keyframeInterval()}.
         *
         * @param frames the value
         * @return this builder
         */
        public Builder keyframeInterval(int frames) {
            this.keyframeInterval = OptionalInt.of(frames);
            return this;
        }

        /**
         * Codes key frames only at the start, on request and, in good-quality coding, at scene
         * cuts; see {@link Vp8EncoderOptions#keyframeInterval()}.
         *
         * @return this builder
         */
        public Builder noPeriodicKeyframes() {
            this.keyframeInterval = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the trade between compression and encoding time; see
         * {@link Vp8EncoderOptions#speed()}.
         *
         * @param speed the value
         * @return this builder
         */
        public Builder speed(Vp8EncoderSpeed speed) {
            this.speed = Objects.requireNonNull(speed, "speed");
            return this;
        }

        /**
         * Sets whether frames are error resilient; see {@link Vp8EncoderOptions#errorResilient()}.
         *
         * @param errorResilient the value
         * @return this builder
         */
        public Builder errorResilient(boolean errorResilient) {
            this.errorResilient = errorResilient;
            return this;
        }

        /**
         * Sets the number of temporal layers; see {@link Vp8EncoderOptions#temporalLayers()}.
         *
         * @param temporalLayers the value
         * @return this builder
         */
        public Builder temporalLayers(int temporalLayers) {
            this.temporalLayers = temporalLayers;
            return this;
        }

        /**
         * Sets whether frames may be dropped; see {@link Vp8EncoderOptions#dropFrames()}.
         *
         * @param dropFrames the value
         * @return this builder
         */
        public Builder dropFrames(boolean dropFrames) {
            this.dropFrames = dropFrames;
            return this;
        }

        /**
         * Sets the size of the buffer model; see {@link Vp8EncoderOptions#bufferSize()}.
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
         * {@link Vp8EncoderOptions#initialBufferLevel()}.
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
         * {@link Vp8EncoderOptions#optimalBufferLevel()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder optimalBufferLevel(Duration level) {
            this.optimalBufferLevel = Objects.requireNonNull(level, "level");
            return this;
        }

        /**
         * Sets the number of token partitions; see {@link Vp8EncoderOptions#tokenPartitions()}.
         *
         * @param tokenPartitions the value
         * @return this builder
         */
        public Builder tokenPartitions(int tokenPartitions) {
            this.tokenPartitions = tokenPartitions;
            return this;
        }

        /**
         * Sets the thread count; see {@link Vp8EncoderOptions#threads()}. A count above 64 is
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
         * Sets the encode breakout threshold; see {@link Vp8EncoderOptions#encodeBreakout()}.
         *
         * @param encodeBreakout the value
         * @return this builder
         */
        public Builder encodeBreakout(int encodeBreakout) {
            this.encodeBreakout = encodeBreakout;
            return this;
        }

        /**
         * Sets the rate tolerances; see {@link Vp8EncoderOptions#undershootPercent()} and
         * {@link Vp8EncoderOptions#overshootPercent()}.
         *
         * @param undershootPercent the undershoot limit
         * @param overshootPercent the overshoot limit
         * @return this builder
         */
        public Builder rateTolerance(int undershootPercent, int overshootPercent) {
            this.undershootPercent = undershootPercent;
            this.overshootPercent = overshootPercent;
            return this;
        }

        /**
         * Sets the key frame target limit; see {@link Vp8EncoderOptions#maxKeyframeBitratePercent()}.
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
         * {@link Vp8EncoderOptions#maxKeyframeBitratePercent()}.
         *
         * @return this builder
         */
        public Builder noMaxKeyframeBitrate() {
            this.maxKeyframeBitratePercent = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the allocation limits; see {@link Vp8EncoderOptions#limits()}.
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
         * quantizer is not in [0, 63], the largest is not in [smallest, 63], the quality level is
         * not in [0, 63] or, with a quality rate control, not in [smallest, largest], the lookahead
         * is not in [0, 25], the temporal filter strength is not in [0, 6] or its frames not in
         * [0, 15], the key frame interval
         * is not positive, the temporal layers are not in [1, 3], the buffer size is not in [1,
         * {@link Integer#MAX_VALUE}] milliseconds, the initial level is not in [0, size], the optimal
         * level is not in [1, size] milliseconds, the token partitions are not 1, 2, 4 or 8, the
         * thread count is negative, the encode breakout is not in [0, {@code 2^24}], the undershoot
         * is not in [0, 100], the overshoot is not in [0, 1000], the key frame target limit is
         * below 100, real-time coding has another rate control than
         * {@link Vp8RateControl#CONSTANT_BITRATE}, a speed below {@link Vp8EncoderSpeed#SPEED_4}
         * or first-pass statistics, or good-quality or best-quality coding has the constant
         * bitrate, a speed above
         * {@link Vp8EncoderSpeed#SPEED_4}, error resilience, frame dropping or more than one
         * temporal layer
         */
        public Vp8EncoderOptions build() {
            Checks.inRange("frame rate", frameRate.toDouble(), 0.1, 1000);
            Checks.positive("time base numerator", timeBase.numerator());
            Checks.positive("time base denominator", timeBase.denominator());
            Checks.inRange("bitrate", bitrate, 1, Integer.MAX_VALUE);
            Checks.inRange("smallest quantizer", minQuantizer, 0, 63);
            Checks.inRange("largest quantizer", maxQuantizer, minQuantizer, 63);
            Checks.inRange("quality level", cqLevel, 0, 63);
            if ((rateControl == Vp8RateControl.CONSTANT_QUALITY || rateControl == Vp8RateControl.CONSTRAINED_QUALITY)
                    && (cqLevel < minQuantizer || cqLevel > maxQuantizer)) {
                throw new IllegalArgumentException("quality level " + cqLevel + " is outside the quantizer window "
                        + minQuantizer + ".." + maxQuantizer);
            }
            Checks.inRange("lookahead", lookahead, 0, MAX_LOOKAHEAD);
            Checks.inRange("temporal filter strength", temporalFilterStrength, 0, 6);
            Checks.inRange("temporal filter frames", temporalFilterFrames, 0, 15);
            if (keyframeInterval.isPresent()) {
                Checks.positive("key frame interval", keyframeInterval.getAsInt());
            }
            Checks.inRange("temporal layers", temporalLayers, 1, 3);
            var sizeMillis = Checks.inRange("buffer size in milliseconds", bufferSize.toMillis(), 1,
                    Integer.MAX_VALUE);
            Checks.inRange("initial buffer level in milliseconds", initialBufferLevel.toMillis(), 0, sizeMillis);
            Checks.inRange("optimal buffer level in milliseconds", optimalBufferLevel.toMillis(), 1, sizeMillis);
            if (tokenPartitions != 1 && tokenPartitions != 2 && tokenPartitions != 4 && tokenPartitions != 8) {
                throw new IllegalArgumentException("token partitions " + tokenPartitions + " is not 1, 2, 4 or 8");
            }
            Checks.nonNegative("thread count", threads);
            Checks.inRange("encode breakout", encodeBreakout, 0, 1 << 24);
            Checks.inRange("undershoot percent", undershootPercent, 0, 100);
            Checks.inRange("overshoot percent", overshootPercent, 0, 1000);
            if (maxKeyframeBitratePercent.isPresent()) {
                Checks.inRange("key frame bitrate percent", maxKeyframeBitratePercent.getAsInt(), 100,
                        Integer.MAX_VALUE);
            }
            if (usage == Vp8EncoderUsage.REALTIME) {
                if (rateControl != Vp8RateControl.CONSTANT_BITRATE) {
                    throw new IllegalArgumentException("real-time coding codes at a constant bitrate, got "
                            + rateControl);
                }
                if (!speed.realtime()) {
                    throw new IllegalArgumentException("real-time coding needs a speed from 4 to 16, got " + speed);
                }
                if (firstPassStats.isPresent()) {
                    throw new IllegalArgumentException("real-time coding has no second pass");
                }
            } else {
                var name = usage == Vp8EncoderUsage.BEST_QUALITY ? "best-quality" : "good-quality";
                if (rateControl == Vp8RateControl.CONSTANT_BITRATE) {
                    throw new IllegalArgumentException(name + " coding needs a variable bitrate, a constrained "
                            + "quality or a constant quality");
                }
                if (!speed.goodQuality()) {
                    throw new IllegalArgumentException(name + " coding needs a speed from 0 to 4, got " + speed);
                }
                if (errorResilient || dropFrames || temporalLayers != 1) {
                    throw new IllegalArgumentException("error resilience, frame dropping and temporal layers apply to "
                            + "real-time coding only");
                }
            }
            return new Vp8EncoderOptions(this);
        }
    }
}
