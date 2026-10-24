package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link Vp9Encoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>The {@linkplain #usage() usage} decides which settings apply; {@link Builder#build()} rejects
 * the combinations the usage cannot code. The picture size is not an option: it is given to the
 * {@link Vp9Encoder} constructor.
 *
 * @apiNote The settings follow libvpx's VP9 configuration: {@link #usage()} is {@code --rt},
 * {@code --good} or {@code --best}, {@link #rateControl()} is {@code rc_end_usage}, {@link #bitrate()} is
 * {@code rc_target_bitrate} in bits rather than kilobits per second, the quantizer window is
 * {@code rc_min_quantizer} and {@code rc_max_quantizer} on the same scale, {@link #cqLevel()} is
 * {@code cq-level}, {@link #lookahead()} is {@code lag-in-frames}, {@link #alternateReferences()}
 * is {@code auto-alt-ref}, the temporal filter is {@code arnr-strength} and {@code arnr-maxframes},
 * {@link #firstPassStats()} is the {@code fpf} of the second pass, {@link #keyframeInterval()} is
 * {@code kf_max_dist}, {@link #speed()} is the magnitude of the {@code cpu-used} control,
 * {@link #errorResilient()} is {@code g_error_resilient}, {@link #adaptiveQuantization()} is
 * {@code aq-mode 3}, {@link #tileColumnsLog2()} is the {@code tile-columns} control, the buffer
 * model is {@code rc_buf_sz}, {@code rc_buf_initial_sz} and {@code rc_buf_optimal_sz}, and the
 * picture format is {@code g_profile}, {@code g_bit_depth} and the {@code color-space} and
 * {@code color-range} controls.
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
     * The largest lookahead, in frames ({@code MAX_LAG_BUFFERS}).
     */
    private static final int MAX_LOOKAHEAD = 25;

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

    private final Vp9EncoderUsage usage;

    private final Vp9RateControl rateControl;

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

    private final Optional<Vp9FirstPassStats> firstPassStats;

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

    private final Vp9Subsampling subsampling;

    private final int bitDepth;

    private final ColorInfo color;

    private final MediaLimits limits;

    private Vp9EncoderOptions(Builder builder) {
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
        this.adaptiveQuantization = builder.adaptiveQuantization;
        this.temporalLayers = builder.temporalLayers;
        this.dropFrames = builder.dropFrames;
        this.bufferSize = builder.bufferSize;
        this.initialBufferLevel = builder.initialBufferLevel;
        this.optimalBufferLevel = builder.optimalBufferLevel;
        this.tileColumnsLog2 = builder.tileColumnsLog2;
        this.threads = Math.min(builder.threads, MAX_THREADS);
        this.subsampling = builder.subsampling;
        this.bitDepth = builder.bitDepth;
        this.color = builder.color;
        this.limits = builder.limits;
    }

    /**
     * Returns the general-purpose real-time settings: the {@link Vp9EncoderUsage#REALTIME} usage at
     * a constant 1 Mbit/s, the full quantizer window, a key frame every 128 frames, one temporal
     * layer, no error resilience, no adaptive quantization and no frame dropping, 8-bit 4:2:0 in
     * limited-range BT.601.
     *
     * @apiNote For files and on-demand video, start from {@link #ofBitrate(long)},
     * {@link #ofQuality(int)} or {@link #forArchive()} instead; for calls, from
     * {@link #forRealtime()}.
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
     * Returns the settings of good-quality coding at a variable bitrate, for files and on-demand
     * video: a 25-frame lookahead, golden frame groups predicted from temporally filtered hidden
     * alternate references, key frames at scene cuts, every 128 frames and on request, at the
     * {@link Vp9EncoderSpeed#SPEED_2} speed, 8-bit 4:2:0.
     *
     * @apiNote The encoder holds pictures back until its lookahead is full, so the last packets
     * arrive only at {@link Vp9Encoder#flush}. The bitrate is met over the stream, not per frame;
     * for a bounded buffer, use {@link #forRealtime()}. A first pass ({@link Vp9FirstPass}) lets
     * the bitrate follow the content of the whole clip.
     * @param bitrate the target bitrate in bits per second, from 1 to {@link Integer#MAX_VALUE}
     * @return the options
     * @throws IllegalArgumentException if {@code bitrate} is not in [1, {@link Integer#MAX_VALUE}]
     */
    public static Vp9EncoderOptions ofBitrate(long bitrate) {
        return goodQuality().rateControl(Vp9RateControl.VARIABLE_BITRATE).bitrate(bitrate).build();
    }

    /**
     * Returns the settings of good-quality coding at a constant quality: the frame structure of
     * {@link #ofBitrate(long)} with the quantizers following a quality level instead of a bitrate.
     *
     * @apiNote Lower levels are finer; 31 to 36 suit web video, 15 to 25 high quality.
     * @param cqLevel the quality level, from 0 (finest) to 63; see {@link #cqLevel()}
     * @return the options
     * @throws IllegalArgumentException if {@code cqLevel} is not in [0, 63]
     */
    public static Vp9EncoderOptions ofQuality(int cqLevel) {
        return goodQuality().rateControl(Vp9RateControl.CONSTANT_QUALITY).cqLevel(cqLevel).build();
    }

    /**
     * Returns the settings of good-quality coding for archiving: constant quality level 20 at the
     * {@link Vp9EncoderSpeed#SPEED_1} speed, with the other settings of {@link #ofQuality(int)}.
     *
     * @return the options
     */
    public static Vp9EncoderOptions forArchive() {
        return goodQuality().rateControl(Vp9RateControl.CONSTANT_QUALITY).cqLevel(20)
                .speed(Vp9EncoderSpeed.SPEED_1).build();
    }

    /**
     * Returns a builder of the good-quality usage at the {@link Vp9EncoderSpeed#SPEED_2} speed.
     *
     * @return the builder
     */
    private static Builder goodQuality() {
        return new Builder().usage(Vp9EncoderUsage.GOOD_QUALITY).speed(Vp9EncoderSpeed.SPEED_2);
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
     * {@link Vp9EncoderUsage#REALTIME} by default}
     *
     * <ul>
     *   <li>{@link Vp9EncoderUsage#REALTIME}: the {@link Vp9RateControl#CONSTANT_BITRATE} rate
     *       control with the buffer model, frame dropping, error resilience, adaptive quantization
     *       and temporal layers, at the speeds from {@link Vp9EncoderSpeed#SPEED_5}; the lookahead,
     *       the alternate references and the first-pass statistics do not apply.</li>
     *   <li>{@link Vp9EncoderUsage#GOOD_QUALITY}: the variable bitrate, constrained quality and
     *       constant quality rate controls, the lookahead, the alternate references with their
     *       temporal filter and the first-pass statistics, at the speeds up to
     *       {@link Vp9EncoderSpeed#SPEED_4}; the buffer model, frame dropping, error resilience,
     *       adaptive quantization and temporal layers do not apply.</li>
     *   <li>{@link Vp9EncoderUsage#BEST_QUALITY}: the settings of the good-quality usage, with the
     *       fullest search whatever the speed; the speed, from {@link Vp9EncoderSpeed#SPEED_0} to
     *       {@link Vp9EncoderSpeed#SPEED_4}, only scales the rate control's first estimate of the
     *       quantizers, as libvpx's {@code cpu-used} does.</li>
     * </ul>
     */
    public Vp9EncoderUsage usage() {
        return usage;
    }

    /**
     * {@return the rate control; {@link Vp9RateControl#CONSTANT_BITRATE} by default}
     *
     * <p>Real-time coding requires {@link Vp9RateControl#CONSTANT_BITRATE}; good-quality and
     * best-quality coding require one of the others.
     */
    public Vp9RateControl rateControl() {
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
     * <p>With {@link Vp9RateControl#CONSTANT_BITRATE}, the rate control models a buffer that the
     * channel fills at the bitrate and every coded frame drains: frame sizes follow the buffer
     * towards its {@linkplain #optimalBufferLevel() optimal level}, so the bitrate measured over a
     * second stays close to the target and follows a change of target within about a second.
     * Frames under- and overshoot their target by at most 50 percent. With
     * {@link Vp9RateControl#VARIABLE_BITRATE} and {@link Vp9RateControl#CONSTRAINED_QUALITY}, the
     * bitrate is the average the whole stream aims for, and the largest one under constrained
     * quality. {@link Vp9RateControl#CONSTANT_QUALITY} ignores it.
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
     * {@return the quality level of the {@link Vp9RateControl#CONSTANT_QUALITY} and
     * {@link Vp9RateControl#CONSTRAINED_QUALITY} rate controls, from 0 to 63 on the scale of
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
     * {@return the number of pictures good-quality and best-quality coding buffer before they
     * code a frame, from 0
     * to 25; 25 by default}
     *
     * <p>The lookahead bounds the golden frame groups and the alternate references: a group with
     * an alternate reference is shorter than the lookahead, so a lookahead below 5 codes none.
     */
    public int lookahead() {
        return lookahead;
    }

    /**
     * {@return whether good-quality and best-quality coding code hidden alternate references;
     * {@code true} by default}
     *
     * <p>An alternate reference is a temporally filtered copy of the last picture of a golden
     * frame group, coded before the group and never shown; the frames of the group predict from it,
     * and the picture itself is coded again as a cheap overlay when its turn comes.
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
    public Optional<Vp9FirstPassStats> firstPassStats() {
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
     * {@return the trade between compression and encoding time; {@link Vp9EncoderSpeed#SPEED_6} by
     * default}
     *
     * <p>Real-time coding requires a {@linkplain Vp9EncoderSpeed#realtime() real-time speed},
     * good-quality and best-quality coding one of {@link Vp9EncoderSpeed#SPEED_0} to
     * {@link Vp9EncoderSpeed#SPEED_4}.
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
     * frame is refreshed periodically in either mode. Only real-time coding is error resilient.
     */
    public boolean errorResilient() {
        return errorResilient;
    }

    /**
     * {@return whether static areas are refreshed at a lower quantizer (cyclic refresh), in
     * real-time coding; {@code false} by default}
     */
    public boolean adaptiveQuantization() {
        return adaptiveQuantization;
    }

    /**
     * {@return the number of temporal layers of real-time coding, from 1 to 3; 1 by default}
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
     * {@return whether a real-time frame may be dropped when the rate control's buffer is empty,
     * that is when the stream has spent more bits than the channel delivered; {@code false} by
     * default}
     */
    public boolean dropFrames() {
        return dropFrames;
    }

    /**
     * {@return the size of the real-time rate control's buffer model, as playback time at the
     * target bitrate, from 1 millisecond to {@link Integer#MAX_VALUE} milliseconds; 6 seconds by
     * default}
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
     * {@return the chroma subsampling of the coded pictures; {@link Vp9Subsampling#YUV420} by
     * default}
     *
     * <p>Pictures in another subsampling are converted. Together with {@link #bitDepth()} it
     * decides the {@linkplain #profile() profile}.
     */
    public Vp9Subsampling subsampling() {
        return subsampling;
    }

    /**
     * {@return the bit depth of the coded pictures, 8, 10 or 12; 8 by default}
     *
     * <p>Pictures of another depth are converted; an 8-bit picture coded at 10 or 12 bits has its
     * samples scaled up, so it decodes back to itself after rounding to 8 bits.
     */
    public int bitDepth() {
        return bitDepth;
    }

    /**
     * {@return the VP9 profile the picture format codes: 0 for 8-bit 4:2:0, 1 for 8-bit 4:2:2,
     * 4:4:0 and 4:4:4, 2 for 10 and 12-bit 4:2:0, 3 for 10 and 12-bit 4:2:2, 4:4:0 and 4:4:4}
     */
    public int profile() {
        return (bitDepth > 8 ? 2 : 0) + (subsampling == Vp9Subsampling.YUV420 ? 0 : 1);
    }

    /**
     * {@return the color description the key frames signal and pictures are converted to;
     * {@link ColorInfo#BT601_LIMITED} by default}
     *
     * <p>The stream signals the matrix and the range: the frame header's {@code color_space} is
     * {@code CS_SMPTE_170} for {@link MatrixCoefficients#BT601}, {@code CS_BT_601} for
     * {@link MatrixCoefficients#BT470BG}, {@code CS_BT_709}, {@code CS_SMPTE_240} and
     * {@code CS_BT_2020} for {@link MatrixCoefficients#BT709}, {@link MatrixCoefficients#SMPTE240M}
     * and {@link MatrixCoefficients#BT2020_NCL}, {@code CS_UNKNOWN} for
     * {@link MatrixCoefficients#UNSPECIFIED} and {@code CS_SRGB} for
     * {@link MatrixCoefficients#IDENTITY}, whose planes hold G, B and R and which requires 4:4:4
     * and the full range. The primaries and the transfer are not in the stream; a container
     * carries them.
     */
    public ColorInfo color() {
        return color;
    }

    /**
     * {@return the allocation limits the picture size is checked against;
     * {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * Returns the {@code color_space} code of a matrix.
     *
     * @param matrix the matrix coefficients
     * @return the code, from 0 to 7, or -1 when VP9 cannot signal the matrix
     */
    static int colorSpaceOf(MatrixCoefficients matrix) {
        return switch (matrix) {
            case UNSPECIFIED -> 0;
            case BT470BG -> 1;
            case BT709 -> 2;
            case BT601 -> 3;
            case SMPTE240M -> 4;
            case BT2020_NCL -> 5;
            case IDENTITY -> 7;
            default -> -1;
        };
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Vp9EncoderOptions that && usage == that.usage && rateControl == that.rateControl
                && frameRate.equals(that.frameRate) && timeBase.equals(that.timeBase) && bitrate == that.bitrate
                && minQuantizer == that.minQuantizer && maxQuantizer == that.maxQuantizer && cqLevel == that.cqLevel
                && lookahead == that.lookahead && alternateReferences == that.alternateReferences
                && temporalFilterStrength == that.temporalFilterStrength
                && temporalFilterFrames == that.temporalFilterFrames && firstPassStats.equals(that.firstPassStats)
                && keyframeInterval.equals(that.keyframeInterval) && speed == that.speed
                && errorResilient == that.errorResilient && adaptiveQuantization == that.adaptiveQuantization
                && temporalLayers == that.temporalLayers && dropFrames == that.dropFrames
                && bufferSize.equals(that.bufferSize) && initialBufferLevel.equals(that.initialBufferLevel)
                && optimalBufferLevel.equals(that.optimalBufferLevel) && tileColumnsLog2 == that.tileColumnsLog2
                && threads == that.threads && subsampling == that.subsampling && bitDepth == that.bitDepth
                && color.equals(that.color) && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(usage, rateControl, frameRate, timeBase, bitrate, minQuantizer, maxQuantizer, cqLevel,
                lookahead, alternateReferences, temporalFilterStrength, temporalFilterFrames, firstPassStats,
                keyframeInterval, speed, errorResilient, adaptiveQuantization, temporalLayers, dropFrames, bufferSize,
                initialBufferLevel, optimalBufferLevel, tileColumnsLog2, threads, subsampling, bitDepth, color,
                limits);
    }

    @Override
    public String toString() {
        return "Vp9EncoderOptions[usage=" + usage + ", rateControl=" + rateControl + ", frameRate=" + frameRate
                + ", timeBase=" + timeBase + ", bitrate=" + bitrate + ", quantizers=" + minQuantizer + ".."
                + maxQuantizer + ", cqLevel=" + cqLevel + ", lookahead=" + lookahead + ", alternateReferences="
                + alternateReferences + ", temporalFilter=" + temporalFilterStrength + "/" + temporalFilterFrames
                + ", firstPassStats=" + firstPassStats + ", keyframeInterval=" + keyframeInterval + ", speed="
                + speed + ", errorResilient=" + errorResilient + ", adaptiveQuantization=" + adaptiveQuantization
                + ", temporalLayers=" + temporalLayers + ", dropFrames=" + dropFrames + ", buffer="
                + initialBufferLevel.toMillis() + "/" + optimalBufferLevel.toMillis() + "/" + bufferSize.toMillis()
                + "ms, tileColumnsLog2=" + tileColumnsLog2 + ", threads=" + threads + ", subsampling=" + subsampling
                + ", bitDepth=" + bitDepth + ", color=" + color + ", limits=" + limits + "]";
    }

    /**
     * A builder of {@link Vp9EncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and the combinations
     * of settings. Instances are not thread-safe.
     */
    public static final class Builder {
        private Vp9EncoderUsage usage = Vp9EncoderUsage.REALTIME;

        private Vp9RateControl rateControl = Vp9RateControl.CONSTANT_BITRATE;

        private Rational frameRate = new Rational(30, 1);

        private Rational timeBase = new Rational(1, 90_000);

        private long bitrate = 1_000_000;

        private int minQuantizer;

        private int maxQuantizer = 63;

        private int cqLevel = 32;

        private int lookahead = MAX_LOOKAHEAD;

        private boolean alternateReferences = true;

        private int temporalFilterStrength = 5;

        private int temporalFilterFrames = 7;

        private Optional<Vp9FirstPassStats> firstPassStats = Optional.empty();

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

        private Vp9Subsampling subsampling = Vp9Subsampling.YUV420;

        private int bitDepth = 8;

        private ColorInfo color = ColorInfo.BT601_LIMITED;

        private MediaLimits limits = MediaLimits.DEFAULT;

        private Builder() {
        }

        private Builder(Vp9EncoderOptions from) {
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
            adaptiveQuantization = from.adaptiveQuantization;
            temporalLayers = from.temporalLayers;
            dropFrames = from.dropFrames;
            bufferSize = from.bufferSize;
            initialBufferLevel = from.initialBufferLevel;
            optimalBufferLevel = from.optimalBufferLevel;
            tileColumnsLog2 = from.tileColumnsLog2;
            threads = from.threads;
            subsampling = from.subsampling;
            bitDepth = from.bitDepth;
            color = from.color;
            limits = from.limits;
        }

        /**
         * Sets the coding mode; see {@link Vp9EncoderOptions#usage()}.
         *
         * @param usage the value
         * @return this builder
         */
        public Builder usage(Vp9EncoderUsage usage) {
            this.usage = Objects.requireNonNull(usage, "usage");
            return this;
        }

        /**
         * Sets the rate control; see {@link Vp9EncoderOptions#rateControl()}.
         *
         * @param rateControl the value
         * @return this builder
         */
        public Builder rateControl(Vp9RateControl rateControl) {
            this.rateControl = Objects.requireNonNull(rateControl, "rateControl");
            return this;
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
         * Sets the quality level; see {@link Vp9EncoderOptions#cqLevel()}.
         *
         * @param cqLevel the value
         * @return this builder
         */
        public Builder cqLevel(int cqLevel) {
            this.cqLevel = cqLevel;
            return this;
        }

        /**
         * Sets the lookahead; see {@link Vp9EncoderOptions#lookahead()}.
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
         * {@link Vp9EncoderOptions#alternateReferences()}.
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
         * {@link Vp9EncoderOptions#temporalFilterStrength()} and
         * {@link Vp9EncoderOptions#temporalFilterFrames()}.
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
         * {@link Vp9EncoderOptions#firstPassStats()}.
         *
         * @param stats the first-pass statistics of the clip
         * @return this builder
         */
        public Builder firstPassStats(Vp9FirstPassStats stats) {
            this.firstPassStats = Optional.of(stats);
            return this;
        }

        /**
         * Makes the encode a one-pass encode; see {@link Vp9EncoderOptions#firstPassStats()}.
         *
         * @return this builder
         */
        public Builder noFirstPassStats() {
            this.firstPassStats = Optional.empty();
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
         * Codes key frames only at the start, on request and at scene cuts; see
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
         * Sets the picture format; see {@link Vp9EncoderOptions#subsampling()} and
         * {@link Vp9EncoderOptions#bitDepth()}.
         *
         * @param subsampling the chroma subsampling
         * @param bitDepth the bit depth
         * @return this builder
         */
        public Builder pictureFormat(Vp9Subsampling subsampling, int bitDepth) {
            this.subsampling = Objects.requireNonNull(subsampling, "subsampling");
            this.bitDepth = bitDepth;
            return this;
        }

        /**
         * Sets the color description; see {@link Vp9EncoderOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
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
         * quantizer is not in [0, 63], the largest is not in [smallest, 63], the quality level is
         * not in [0, 63] or, with a quality rate control, not in [smallest, largest], the
         * lookahead is not in [0, 25], the temporal filter strength is not in [0, 6] or its frames
         * not in [0, 15], the key frame interval is not positive, the
         * temporal layers are not in [1, 3], the buffer size is not in [1,
         * {@link Integer#MAX_VALUE}] milliseconds, the initial level is not in [0, size], the
         * optimal level is not in [1, size] milliseconds, the tile column logarithm is not in [0,
         * 6], the thread count is negative, the bit depth is not 8, 10 or 12, VP9 cannot signal the
         * color's matrix, the identity matrix comes without 4:4:4 or the full range, real-time
         * coding has another rate control than {@link Vp9RateControl#CONSTANT_BITRATE}, a speed
         * below {@link Vp9EncoderSpeed#SPEED_5} or first-pass statistics, or good-quality or
         * best-quality coding has the constant bitrate, a real-time speed, error resilience, adaptive
         * quantization, frame dropping or more than one temporal layer
         */
        public Vp9EncoderOptions build() {
            Checks.inRange("frame rate", frameRate.toDouble(), 0.1, 1000);
            Checks.positive("time base numerator", timeBase.numerator());
            Checks.positive("time base denominator", timeBase.denominator());
            Checks.inRange("bitrate", bitrate, 1, Integer.MAX_VALUE);
            Checks.inRange("smallest quantizer", minQuantizer, 0, 63);
            Checks.inRange("largest quantizer", maxQuantizer, minQuantizer, 63);
            Checks.inRange("quality level", cqLevel, 0, 63);
            if ((rateControl == Vp9RateControl.CONSTANT_QUALITY || rateControl == Vp9RateControl.CONSTRAINED_QUALITY)
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
            Checks.inRange("tile column logarithm", tileColumnsLog2, 0, MAX_TILE_COLUMNS_LOG2);
            Checks.nonNegative("thread count", threads);
            if (bitDepth != 8 && bitDepth != 10 && bitDepth != 12) {
                throw new IllegalArgumentException("bit depth " + bitDepth + " is not 8, 10 or 12");
            }
            if (colorSpaceOf(color.matrix()) < 0) {
                throw new IllegalArgumentException("vp9 cannot signal the " + color.matrix() + " matrix");
            }
            if (color.matrix() == MatrixCoefficients.IDENTITY
                    && (subsampling != Vp9Subsampling.YUV444 || color.range() != ColorRange.FULL)) {
                throw new IllegalArgumentException("the identity matrix (sRGB) needs full-range 4:4:4 pictures");
            }
            if (usage == Vp9EncoderUsage.REALTIME) {
                if (rateControl != Vp9RateControl.CONSTANT_BITRATE) {
                    throw new IllegalArgumentException("real-time coding codes at a constant bitrate, got "
                            + rateControl);
                }
                if (!speed.realtime()) {
                    throw new IllegalArgumentException("real-time coding needs a speed from 5 to 9, got " + speed);
                }
                if (firstPassStats.isPresent()) {
                    throw new IllegalArgumentException("real-time coding has no second pass");
                }
            } else {
                var name = usage == Vp9EncoderUsage.BEST_QUALITY ? "best-quality" : "good-quality";
                if (rateControl == Vp9RateControl.CONSTANT_BITRATE) {
                    throw new IllegalArgumentException(name + " coding needs a variable bitrate, a constrained "
                            + "quality or a constant quality");
                }
                if (speed.realtime()) {
                    throw new IllegalArgumentException(name + " coding needs a speed from 0 to 4, got " + speed);
                }
                if (errorResilient || adaptiveQuantization || dropFrames || temporalLayers != 1) {
                    throw new IllegalArgumentException("error resilience, adaptive quantization, frame dropping and "
                            + "temporal layers apply to real-time coding only");
                }
            }
            return new Vp9EncoderOptions(this);
        }
    }
}
