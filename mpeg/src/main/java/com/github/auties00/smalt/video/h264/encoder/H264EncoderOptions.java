package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Holds the settings of an {@link H264Encoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>The picture size is not an option: it is given to the {@link H264Encoder} constructor.
 *
 * @implNote This implementation chose the look-ahead depth, the B picture pyramid, the macroblock
 * tree strength, the B picture quantizer offset, the IDR size limits of real-time mode and the
 * rounding and decimation thresholds of the quantizer by Bjontegaard rate on PSNR-Y and SSIM-Y over
 * standard test sequences; {@code tools/docs/h264-encoder-tuning.md} records the measurements.
 */
public final class H264EncoderOptions {
    /**
     * The smallest H.264 quantizer, the finest quantization, which {@link #quality()} and
     * {@link #minQuantizer()} accept.
     */
    public static final int MIN_QUANTIZER = 0;

    /**
     * The largest H.264 quantizer, the coarsest quantization, which {@link #quality()} and
     * {@link #maxQuantizer()} accept.
     */
    public static final int MAX_QUANTIZER = 51;

    /**
     * The largest number of worker threads.
     */
    private static final int MAX_THREADS = 64;

    /**
     * The largest look-ahead depth in pictures.
     */
    private static final int MAX_LOOKAHEAD = 120;

    /**
     * The largest number of consecutive B pictures.
     */
    private static final int MAX_B_FRAMES = 8;

    /**
     * The settings of {@link #forFile()}, which {@link #defaults()} also returns.
     */
    private static final H264EncoderOptions FILE = new Builder().build();

    /**
     * The settings of {@link #forRealtime()}.
     */
    private static final H264EncoderOptions REALTIME = new Builder()
            .profile(H264Profile.CONSTRAINED_BASELINE)
            .rateControl(H264RateControl.CONSTANT_BITRATE)
            .bufferSize(Duration.ofMillis(500))
            .quality(26)
            .noPeriodicKeyframes()
            .speed(H264EncoderSpeed.FASTEST)
            .color(ColorInfo.BT601_LIMITED)
            .packetFormat(H264PacketFormat.ANNEX_B)
            .quantizers(12, MAX_QUANTIZER)
            .realtime(true)
            .lookahead(0)
            .bFrames(0)
            .build();

    private final Rational frameRate;

    private final Rational timeBase;

    private final H264Profile profile;

    /**
     * The signaled level, or {@code null} for the lowest level that fits.
     */
    private final H264Level level;

    private final H264RateControl rateControl;

    private final long bitrate;

    /**
     * The rate at which the buffering verifier fills, or 0 for the default of the mode.
     */
    private final long maxBitrate;

    /**
     * The buffering verifier size, or {@code null} for the default of the mode.
     */
    private final Duration bufferSize;

    private final int quality;

    /**
     * The most frames between two IDR pictures, or 0 for no periodic IDR.
     */
    private final int keyframeInterval;

    private final H264EncoderSpeed speed;

    private final ColorInfo color;

    private final int threads;

    private final H264PacketFormat packetFormat;

    private final int minQuantizer;

    private final int maxQuantizer;

    private final boolean realtime;

    private final int lookahead;

    private final int bFrames;

    private final MediaLimits limits;

    private H264EncoderOptions(Builder builder) {
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.profile = builder.profile;
        this.level = builder.level;
        this.rateControl = builder.rateControl;
        this.bitrate = builder.bitrate;
        this.maxBitrate = builder.maxBitrate;
        this.bufferSize = builder.bufferSize;
        this.quality = builder.quality;
        this.keyframeInterval = builder.keyframeInterval;
        this.speed = builder.speed;
        this.color = builder.color;
        this.threads = builder.threads;
        this.packetFormat = builder.packetFormat;
        this.minQuantizer = builder.minQuantizer;
        this.maxQuantizer = builder.maxQuantizer;
        this.realtime = builder.realtime;
        this.lookahead = builder.lookahead;
        this.bFrames = builder.bFrames;
        this.limits = builder.limits;
    }

    /**
     * Returns the general-purpose settings, which are those of {@link #forFile()}.
     *
     * @return the options
     */
    public static H264EncoderOptions defaults() {
        return FILE;
    }

    /**
     * Returns the settings for a file or an on-demand stream that a player downloads progressively.
     *
     * <p>The stream is {@link H264Profile#MAIN} at an {@link H264RateControl#AVERAGE_BITRATE} of
     * 1 Mbit/s whose buffering verifier fills at twice the bitrate and holds one second at that
     * rate, with an IDR picture every 60 frames (two seconds at the nominal 30 frames per second),
     * {@link H264EncoderSpeed#BALANCED}, limited-range BT.709 color, one thread, the lowest
     * fitting level, {@link H264PacketFormat#LENGTH_PREFIXED} packets and the 90 kHz time base
     * {@code 1/90000}. The encoder looks 20 pictures ahead ({@link #lookahead()}) and codes seven
     * B pictures between anchors ({@link #bFrames()}), so it holds the first 20 frames until
     * {@link H264Encoder#flush} or enough later frames arrive, and its packets leave in decoding
     * order with decoding timestamps before their presentation timestamps.
     *
     * @apiNote Set the frame rate and bitrate of the stream with {@link Builder#frameRate(Rational)}
     * and {@link Builder#bitrate(long)} on {@link #toBuilder()}, and scale the key frame interval
     * with the frame rate through {@link Builder#keyframeInterval(int)} when the frame rate is not 30.
     *
     * @return the options
     */
    public static H264EncoderOptions forFile() {
        return FILE;
    }

    /**
     * Returns the settings for real-time transmission, such as a video call.
     *
     * <p>The stream is {@link H264Profile#CONSTRAINED_BASELINE} (one slice per picture, CAVLC, I
     * and P pictures only, and a {@code bitstream_restriction} announcing zero reordered frames,
     * so decoders output every picture as soon as it is decoded) at a
     * {@link H264RateControl#CONSTANT_BITRATE} of 1 Mbit/s with a half-second buffer and
     * {@link #realtime()} tuning, with no periodic IDR (callers use
     * {@link H264Encoder#requestKeyframe()} when a receiver loses sync),
     * {@link H264EncoderSpeed#FASTEST}, the quantizer window from 12 to 51,
     * {@link H264PacketFormat#ANNEX_B} packets, limited-range BT.601 color (the matrix camera and
     * RTC pipelines convert with), one thread, the lowest fitting level, the nominal 30 frames per
     * second and the 90 kHz RTP time base {@code 1/90000}.
     *
     * @return the options
     */
    public static H264EncoderOptions forRealtime() {
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
     * {@return the nominal frame rate in frames per second, from 0.001 to 1000, written to the
     * stream's timing information and used by rate control when timestamps are missing; 30 by
     * default}
     *
     * <p>The numerator is at most {@code Integer.MAX_VALUE / 2}, so that it fits the stream timing
     * fields.
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the unit of the timestamps of accepted frames and produced packets, positive;
     * {@code 1/90000} by default}
     */
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * {@return the profile, which selects the entropy coder and the coding tools;
     * {@link H264Profile#MAIN} by default}
     */
    public H264Profile profile() {
        return profile;
    }

    /**
     * {@return the level signaled in the stream, or empty when the encoder chooses the lowest level
     * that fits the picture size, frame rate, bitrate and buffer; empty by default}
     */
    public Optional<H264Level> level() {
        return Optional.ofNullable(level);
    }

    /**
     * {@return the rate control mode; {@link H264RateControl#AVERAGE_BITRATE} by default}
     *
     * <ul>
     *   <li>{@link H264RateControl#CONSTANT_QUALITY}: every frame is coded around the quantizer
     *       {@link #quality()}, key frames slightly finer; the bitrate, maximum bitrate and buffer
     *       are ignored.</li>
     *   <li>{@link H264RateControl#AVERAGE_BITRATE}: the long-term average approaches
     *       {@link #bitrate()} while a video buffering verifier of {@link #bufferSize()} filled at
     *       {@link #maxBitrate()} never underflows.</li>
     *   <li>{@link H264RateControl#CONSTANT_BITRATE}: as above with the maximum bitrate equal to the
     *       bitrate by default and a tighter per-frame budget, for real-time transmission.</li>
     * </ul>
     * Every frame quantizer, and every macroblock quantizer derived from it by adaptive
     * quantization, stays inside the window from {@link #minQuantizer()} to {@link #maxQuantizer()};
     * a window narrower than {@value #MIN_QUANTIZER} to {@value #MAX_QUANTIZER} can make a frame
     * miss its rate target.
     */
    public H264RateControl rateControl() {
        return rateControl;
    }

    /**
     * {@return the target bitrate in bits per second, from 1 to {@code Integer.MAX_VALUE}; 1000000 by
     * default}
     *
     * <p>{@link H264RateControl#CONSTANT_QUALITY} ignores it.
     */
    public long bitrate() {
        return bitrate;
    }

    /**
     * {@return the rate in bits per second at which the buffering verifier fills, from the bitrate to
     * {@code Integer.MAX_VALUE}, or empty for the default of the mode: the bitrate for
     * {@link H264RateControl#CONSTANT_BITRATE} and twice the bitrate for
     * {@link H264RateControl#AVERAGE_BITRATE}; empty by default}
     *
     * <p>{@link H264RateControl#CONSTANT_QUALITY} ignores it, and does not require it to reach the
     * bitrate.
     */
    public OptionalLong maxBitrate() {
        return maxBitrate == 0 ? OptionalLong.empty() : OptionalLong.of(maxBitrate);
    }

    /**
     * {@return the size of the buffering verifier, as positive playback time at the maximum bitrate
     * of at most {@code Integer.MAX_VALUE} milliseconds, or empty for the default of the mode: half a
     * second for {@link H264RateControl#CONSTANT_BITRATE} and two seconds for
     * {@link H264RateControl#AVERAGE_BITRATE}; one second by default}
     */
    public Optional<Duration> bufferSize() {
        return Optional.ofNullable(bufferSize);
    }

    /**
     * {@return the quantizer of {@link H264RateControl#CONSTANT_QUALITY}, from {@value #MIN_QUANTIZER}
     * (best) to {@value #MAX_QUANTIZER} (worst); 23 by default}
     *
     * <p>Other modes ignore it.
     */
    public int quality() {
        return quality;
    }

    /**
     * {@return the most frames between two IDR pictures, positive, or empty for no periodic IDR; 60
     * by default}
     *
     * <p>Without periodic IDR pictures the first frame, scene cuts and
     * {@link H264Encoder#requestKeyframe()} still produce one.
     */
    public OptionalInt keyframeInterval() {
        return keyframeInterval == 0 ? OptionalInt.empty() : OptionalInt.of(keyframeInterval);
    }

    /**
     * {@return the speed and compression trade-off; {@link H264EncoderSpeed#BALANCED} by default}
     */
    public H264EncoderSpeed speed() {
        return speed;
    }

    /**
     * {@return the color description written to the stream and used to convert RGB input;
     * {@link ColorInfo#BT709_LIMITED} by default}
     *
     * <p>Its matrix is one of {@link MatrixCoefficients#BT709}, {@link MatrixCoefficients#BT601},
     * {@link MatrixCoefficients#BT470BG}, {@link MatrixCoefficients#SMPTE240M},
     * {@link MatrixCoefficients#FCC}, {@link MatrixCoefficients#BT2020_NCL} or
     * {@link MatrixCoefficients#UNSPECIFIED} (converted as BT.709).
     */
    public ColorInfo color() {
        return color;
    }

    /**
     * {@return the most threads of the module's shared worker pool that analyze the macroblock rows
     * of a picture at once, from 1 to 64; 1 by default}
     *
     * <p>Above 1 the rows are analyzed in parallel while the calling thread entropy codes them. The
     * output is the same for every thread count and scheduling.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return how the NAL units of a packet are delimited and where the parameter sets travel;
     * {@link H264PacketFormat#LENGTH_PREFIXED} by default}
     */
    public H264PacketFormat packetFormat() {
        return packetFormat;
    }

    /**
     * {@return the smallest quantizer any frame or macroblock is coded with, from
     * {@value #MIN_QUANTIZER} to {@link #maxQuantizer()}; {@value #MIN_QUANTIZER} by default}
     */
    public int minQuantizer() {
        return minQuantizer;
    }

    /**
     * {@return the largest quantizer any frame or macroblock is coded with, from
     * {@link #minQuantizer()} to {@value #MAX_QUANTIZER}; {@value #MAX_QUANTIZER} by default}
     */
    public int maxQuantizer() {
        return maxQuantizer;
    }

    /**
     * {@return whether the encoder is tuned for interactive transmission, where every frame must
     * leave the encoder promptly and at a bounded size; {@code false} by default}
     *
     * <p>Real-time tuning means:
     * <ul>
     *   <li>with a bitrate target, the first IDR picture is aimed at no more than five frame budgets
     *       (the bits one frame of the nominal duration may use at the target bitrate, so about
     *       167 ms of transmission at 30 frames per second) and the later ones at no more than seven,
     *       instead of filling most of the buffering verifier, so an IDR picture neither stalls the
     *       transmission nor drains the buffer the following pictures need;</li>
     *   <li>every macroblock of a picture is coded with the picture's quantizer: there is no
     *       adaptive quantization;</li>
     *   <li>no frame is coded a second time to correct the size the rate control model predicted for
     *       it; a frame is coded again only when it would overflow the buffering verifier;</li>
     *   <li>a frame whose timestamp does not increase is accepted rather than rejected (see
     *       {@link H264Encoder#encode(com.github.auties00.smalt.video.VideoFrame,
     *       java.util.function.Consumer)});</li>
     *   <li>{@link #lookahead()} and {@link #bFrames()} are ignored and treated as 0.</li>
     * </ul>
     */
    public boolean realtime() {
        return realtime;
    }

    /**
     * {@return the number of pictures the encoder analyzes ahead of the one it codes, from 0 to 120;
     * 20 by default}
     *
     * <p>With a look-ahead, a half-resolution analysis of the coming pictures estimates how much of
     * each macroblock later pictures reuse, and macroblocks that are reused longer get a finer
     * quantizer. This costs as many pictures of delay: the encoder emits nothing for the first
     * {@code lookahead} frames, and {@link H264Encoder#flush} emits the rest. It is ignored, and
     * treated as 0, in {@link #realtime()} mode.
     */
    public int lookahead() {
        return lookahead;
    }

    /**
     * {@return the number of consecutive B pictures the encoder codes between two anchor pictures,
     * from 0 to 8; 7 by default}
     *
     * <p>B pictures are predicted from the anchor pictures on both sides in display order, with
     * spatial direct, skip and 16x16 list 0, list 1 and bi-predicted macroblocks, and cost fewer
     * bits. With two or more, the middle picture of each interval is coded first and referenced by
     * the others (a B pyramid), recursively, so the decoder keeps up to three more reference frames
     * and the encoder uses fewer B pictures when the level's picture buffer is too small; P pictures
     * start their motion search from the motion the look-ahead tracked over the pictures in between.
     * B pictures reorder the packets, so packets carry a decoding timestamp earlier than the
     * presentation timestamp. They need a {@link #lookahead()} above their number, so the encoder
     * uses one fewer than the look-ahead when it is smaller, and a profile with B pictures: the
     * {@link H264Profile#CONSTRAINED_BASELINE} profile and {@link #realtime()} mode code none.
     */
    public int bFrames() {
        return bFrames;
    }

    /**
     * {@return the allocation limits the picture size is checked against;
     * {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof H264EncoderOptions that && frameRate.equals(that.frameRate)
                && timeBase.equals(that.timeBase) && profile == that.profile && level == that.level
                && rateControl == that.rateControl && bitrate == that.bitrate && maxBitrate == that.maxBitrate
                && Objects.equals(bufferSize, that.bufferSize) && quality == that.quality
                && keyframeInterval == that.keyframeInterval && speed == that.speed && color.equals(that.color)
                && threads == that.threads && packetFormat == that.packetFormat && minQuantizer == that.minQuantizer
                && maxQuantizer == that.maxQuantizer && realtime == that.realtime && lookahead == that.lookahead
                && bFrames == that.bFrames && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(frameRate, timeBase, profile, level, rateControl, bitrate, maxBitrate, bufferSize, quality,
                keyframeInterval, speed, color, threads, packetFormat, minQuantizer, maxQuantizer, realtime, lookahead,
                bFrames, limits);
    }

    @Override
    public String toString() {
        return "H264EncoderOptions[frameRate=" + frameRate + ", timeBase=" + timeBase + ", profile=" + profile
                + ", level=" + (level == null ? "auto" : level) + ", rateControl=" + rateControl + ", bitrate="
                + bitrate + ", maxBitrate=" + (maxBitrate == 0 ? "default" : maxBitrate) + ", bufferSize="
                + (bufferSize == null ? "default" : bufferSize) + ", quality=" + quality + ", keyframeInterval="
                + keyframeInterval() + ", speed=" + speed + ", color=" + color + ", threads=" + threads
                + ", packetFormat=" + packetFormat + ", quantizers=" + minQuantizer + ".." + maxQuantizer
                + ", realtime=" + realtime + ", lookahead=" + lookahead + ", bFrames=" + bFrames + ", limits=" + limits
                + "]";
    }

    /**
     * A builder of {@link H264EncoderOptions}, starting from {@link H264EncoderOptions#defaults()}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the values and how they combine.
     * A builder may build repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private Rational frameRate;

        private Rational timeBase;

        private H264Profile profile;

        private H264Level level;

        private H264RateControl rateControl;

        private long bitrate;

        private long maxBitrate;

        private boolean explicitMaxBitrate;

        private Duration bufferSize;

        private int quality;

        private int keyframeInterval;

        private boolean periodicKeyframes;

        private H264EncoderSpeed speed;

        private ColorInfo color;

        private int threads;

        private H264PacketFormat packetFormat;

        private int minQuantizer;

        private int maxQuantizer;

        private boolean realtime;

        private int lookahead;

        private int bFrames;

        private MediaLimits limits;

        private Builder() {
            frameRate = new Rational(30, 1);
            timeBase = new Rational(1, 90_000);
            profile = H264Profile.MAIN;
            rateControl = H264RateControl.AVERAGE_BITRATE;
            bitrate = 1_000_000;
            bufferSize = Duration.ofSeconds(1);
            quality = 23;
            keyframeInterval = 60;
            periodicKeyframes = true;
            speed = H264EncoderSpeed.BALANCED;
            color = ColorInfo.BT709_LIMITED;
            threads = 1;
            packetFormat = H264PacketFormat.LENGTH_PREFIXED;
            minQuantizer = MIN_QUANTIZER;
            maxQuantizer = MAX_QUANTIZER;
            lookahead = 20;
            bFrames = 7;
            limits = MediaLimits.DEFAULT;
        }

        private Builder(H264EncoderOptions from) {
            frameRate = from.frameRate;
            timeBase = from.timeBase;
            profile = from.profile;
            level = from.level;
            rateControl = from.rateControl;
            bitrate = from.bitrate;
            maxBitrate = from.maxBitrate;
            explicitMaxBitrate = from.maxBitrate != 0;
            bufferSize = from.bufferSize;
            quality = from.quality;
            keyframeInterval = from.keyframeInterval;
            periodicKeyframes = from.keyframeInterval != 0;
            speed = from.speed;
            color = from.color;
            threads = from.threads;
            packetFormat = from.packetFormat;
            minQuantizer = from.minQuantizer;
            maxQuantizer = from.maxQuantizer;
            realtime = from.realtime;
            lookahead = from.lookahead;
            bFrames = from.bFrames;
            limits = from.limits;
        }

        /**
         * Sets the nominal frame rate; see {@link H264EncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base of the timestamps; see {@link H264EncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the profile; see {@link H264EncoderOptions#profile()}.
         *
         * @param profile the value
         * @return this builder
         */
        public Builder profile(H264Profile profile) {
            this.profile = Objects.requireNonNull(profile, "profile");
            return this;
        }

        /**
         * Sets the signaled level; see {@link H264EncoderOptions#level()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder level(H264Level level) {
            this.level = Objects.requireNonNull(level, "level");
            return this;
        }

        /**
         * Lets the encoder signal the lowest level that fits the stream; see
         * {@link H264EncoderOptions#level()}.
         *
         * @return this builder
         */
        public Builder automaticLevel() {
            this.level = null;
            return this;
        }

        /**
         * Sets the rate control mode; see {@link H264EncoderOptions#rateControl()}.
         *
         * @param rateControl the value
         * @return this builder
         */
        public Builder rateControl(H264RateControl rateControl) {
            this.rateControl = Objects.requireNonNull(rateControl, "rateControl");
            return this;
        }

        /**
         * Sets the target bitrate; see {@link H264EncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(long bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        /**
         * Sets the rate at which the buffering verifier fills; see
         * {@link H264EncoderOptions#maxBitrate()}.
         *
         * @param maxBitrate the value
         * @return this builder
         */
        public Builder maxBitrate(long maxBitrate) {
            this.maxBitrate = maxBitrate;
            this.explicitMaxBitrate = true;
            return this;
        }

        /**
         * Lets the buffering verifier fill at the rate the rate control mode chooses; see
         * {@link H264EncoderOptions#maxBitrate()}.
         *
         * @return this builder
         */
        public Builder automaticMaxBitrate() {
            this.maxBitrate = 0;
            this.explicitMaxBitrate = false;
            return this;
        }

        /**
         * Sets the buffering verifier size; see {@link H264EncoderOptions#bufferSize()}.
         *
         * @param bufferSize the value
         * @return this builder
         */
        public Builder bufferSize(Duration bufferSize) {
            this.bufferSize = Objects.requireNonNull(bufferSize, "bufferSize");
            return this;
        }

        /**
         * Lets the buffering verifier take the size the rate control mode chooses; see
         * {@link H264EncoderOptions#bufferSize()}.
         *
         * @return this builder
         */
        public Builder automaticBufferSize() {
            this.bufferSize = null;
            return this;
        }

        /**
         * Sets the constant quality quantizer; see {@link H264EncoderOptions#quality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder quality(int quality) {
            this.quality = quality;
            return this;
        }

        /**
         * Sets the most frames between two IDR pictures; see
         * {@link H264EncoderOptions#keyframeInterval()}.
         *
         * @param frames the value
         * @return this builder
         */
        public Builder keyframeInterval(int frames) {
            this.keyframeInterval = frames;
            this.periodicKeyframes = true;
            return this;
        }

        /**
         * Codes IDR pictures only for the first frame, scene cuts and requests; see
         * {@link H264EncoderOptions#keyframeInterval()}.
         *
         * @return this builder
         */
        public Builder noPeriodicKeyframes() {
            this.keyframeInterval = 0;
            this.periodicKeyframes = false;
            return this;
        }

        /**
         * Sets the speed and compression trade-off; see {@link H264EncoderOptions#speed()}.
         *
         * @param speed the value
         * @return this builder
         */
        public Builder speed(H264EncoderSpeed speed) {
            this.speed = Objects.requireNonNull(speed, "speed");
            return this;
        }

        /**
         * Sets the color description; see {@link H264EncoderOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Sets the thread count; see {@link H264EncoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets the packet format; see {@link H264EncoderOptions#packetFormat()}.
         *
         * @param packetFormat the value
         * @return this builder
         */
        public Builder packetFormat(H264PacketFormat packetFormat) {
            this.packetFormat = Objects.requireNonNull(packetFormat, "packetFormat");
            return this;
        }

        /**
         * Sets the quantizer window; see {@link H264EncoderOptions#minQuantizer()} and
         * {@link H264EncoderOptions#maxQuantizer()}.
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
         * Sets whether the encoder is tuned for interactive transmission; see
         * {@link H264EncoderOptions#realtime()}.
         *
         * @param realtime the value
         * @return this builder
         */
        public Builder realtime(boolean realtime) {
            this.realtime = realtime;
            return this;
        }

        /**
         * Sets the look-ahead depth; see {@link H264EncoderOptions#lookahead()}.
         *
         * @param lookahead the value
         * @return this builder
         */
        public Builder lookahead(int lookahead) {
            this.lookahead = lookahead;
            return this;
        }

        /**
         * Sets the number of consecutive B pictures; see {@link H264EncoderOptions#bFrames()}.
         *
         * @param bFrames the value
         * @return this builder
         */
        public Builder bFrames(int bFrames) {
            this.bFrames = bFrames;
            return this;
        }

        /**
         * Sets the allocation limits; see {@link H264EncoderOptions#limits()}.
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
         * @throws IllegalArgumentException if the frame rate is not in [0.001, 1000] or does not fit the
         * stream timing fields, the time base is not positive, the bitrate is not in
         * [1, {@code Integer.MAX_VALUE}], the maximum bitrate is not in [1, {@code Integer.MAX_VALUE}]
         * or is below the bitrate in a bitrate mode, the buffer size is not positive or exceeds
         * {@code Integer.MAX_VALUE} milliseconds, the quality is not in [0, 51], the key frame
         * interval is not positive, the thread count is not in [1, 64], the look-ahead is not in
         * [0, 120], the B picture count is not in [0, 8], the quantizer window is not inside [0, 51]
         * or is empty, or the color matrix is not one {@link H264EncoderOptions#color()} accepts
         */
        public H264EncoderOptions build() {
            var rate = frameRate.toDouble();
            if (!(rate >= 0.001 && rate <= 1000)) {
                throw new IllegalArgumentException("frame rate " + frameRate + " is not in [0.001, 1000]");
            }
            if (frameRate.numerator() > Integer.MAX_VALUE / 2 || frameRate.denominator() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("frame rate " + frameRate
                        + " does not fit the stream timing fields");
            }
            if (timeBase.numerator() <= 0 || timeBase.denominator() <= 0) {
                throw new IllegalArgumentException("time base " + timeBase + " is not positive");
            }
            Checks.inRange("bitrate", bitrate, 1, Integer.MAX_VALUE);
            if (explicitMaxBitrate) {
                Checks.inRange("maximum bitrate", maxBitrate, 1, Integer.MAX_VALUE);
                if (rateControl != H264RateControl.CONSTANT_QUALITY && maxBitrate < bitrate) {
                    throw new IllegalArgumentException("maximum bitrate " + maxBitrate + " is below the bitrate "
                            + bitrate);
                }
            }
            if (bufferSize != null) {
                Checks.positive("buffer size", bufferSize);
                Checks.inRange("buffer size", bufferSize, Duration.ZERO, Duration.ofMillis(Integer.MAX_VALUE));
            }
            Checks.inRange("quality", quality, MIN_QUANTIZER, MAX_QUANTIZER);
            if (periodicKeyframes) {
                Checks.positive("keyframe interval", keyframeInterval);
            }
            Checks.inRange("thread count", threads, 1, MAX_THREADS);
            Checks.inRange("look-ahead", lookahead, 0, MAX_LOOKAHEAD);
            Checks.inRange("B picture count", bFrames, 0, MAX_B_FRAMES);
            Checks.inRange("minimum quantizer", minQuantizer, MIN_QUANTIZER, MAX_QUANTIZER);
            Checks.inRange("maximum quantizer", maxQuantizer, minQuantizer, MAX_QUANTIZER);
            switch (color.matrix()) {
                case BT709, BT601, BT470BG, SMPTE240M, FCC, BT2020_NCL, UNSPECIFIED -> {
                }
                default -> throw new IllegalArgumentException("color matrix " + color.matrix() + " is not supported");
            }
            return new H264EncoderOptions(this);
        }
    }
}
