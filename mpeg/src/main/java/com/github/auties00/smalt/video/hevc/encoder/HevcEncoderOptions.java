package com.github.auties00.smalt.video.hevc.encoder;

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
 * Holds the settings of an {@link HevcEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>The picture size is not an option: it is given to the {@link HevcEncoder} constructor.
 */
public final class HevcEncoderOptions {
    /**
     * The smallest HEVC quantizer, the finest quantization, which {@link #quality()} and
     * {@link #minQuantizer()} accept.
     */
    public static final int MIN_QUANTIZER = 0;

    /**
     * The largest HEVC quantizer, the coarsest quantization, which {@link #quality()} and
     * {@link #maxQuantizer()} accept.
     */
    public static final int MAX_QUANTIZER = 51;

    /**
     * The largest number of worker threads.
     */
    private static final int MAX_THREADS = 64;

    /**
     * The largest number of consecutive B pictures.
     */
    private static final int MAX_B_FRAMES = 7;

    /**
     * The settings of {@link #forFile()}, which {@link #defaults()} also returns.
     */
    private static final HevcEncoderOptions FILE = new Builder().build();

    /**
     * The settings of {@link #forRealtime()}.
     */
    private static final HevcEncoderOptions REALTIME = new Builder()
            .rateControl(HevcRateControl.CONSTANT_BITRATE)
            .bufferSize(Duration.ofMillis(500))
            .quality(32)
            .noPeriodicKeyframes()
            .speed(HevcEncoderSpeed.FASTEST)
            .color(ColorInfo.BT601_LIMITED)
            .packetFormat(HevcPacketFormat.ANNEX_B)
            .quantizers(12, MAX_QUANTIZER)
            .realtime(true)
            .bFrames(0)
            .build();

    /**
     * The settings of {@link #forStillPicture()}.
     */
    private static final HevcEncoderOptions STILL = new Builder()
            .profile(HevcProfile.MAIN_STILL_PICTURE)
            .rateControl(HevcRateControl.CONSTANT_QUALITY)
            .quality(26)
            .color(ColorInfo.JPEG)
            .bFrames(0)
            .build();

    private final Rational frameRate;

    private final Rational timeBase;

    private final HevcProfile profile;

    /**
     * The signaled level, or {@code null} for the lowest level that fits.
     */
    private final HevcLevel level;

    private final int bitDepth;

    private final HevcRateControl rateControl;

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

    private final OptionalInt keyframeInterval;

    private final HevcEncoderSpeed speed;

    private final ColorInfo color;

    private final int threads;

    private final HevcPacketFormat packetFormat;

    private final int minQuantizer;

    private final int maxQuantizer;

    private final boolean realtime;

    private final int bFrames;

    private final MediaLimits limits;

    private final HevcPictureHash pictureHash;

    private final Rational sampleAspectRatio;

    private HevcEncoderOptions(Builder builder) {
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.profile = builder.profile;
        this.level = builder.level;
        this.bitDepth = builder.bitDepth;
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
        this.bFrames = builder.bFrames;
        this.limits = builder.limits;
        this.pictureHash = builder.pictureHash;
        this.sampleAspectRatio = builder.sampleAspectRatio;
    }

    /**
     * Returns the general-purpose settings, which are those of {@link #forFile()}.
     *
     * @return the options
     */
    public static HevcEncoderOptions defaults() {
        return FILE;
    }

    /**
     * Returns the settings for a file or an on-demand stream that a player downloads progressively.
     *
     * <p>The stream is {@link HevcProfile#MAIN} at an {@link HevcRateControl#AVERAGE_BITRATE} of 1 Mbit/s
     * whose buffering verifier fills at twice the bitrate and holds one second at that rate, with an IDR
     * picture every 60 frames (two seconds at the nominal 30 frames per second), three B pictures between
     * anchor pictures ({@link #bFrames()}), {@link HevcEncoderSpeed#BALANCED}, limited-range BT.709 color, one
     * thread, the lowest fitting level, {@link HevcPacketFormat#LENGTH_PREFIXED} packets, no decoded picture
     * hash and the 90 kHz time base {@code 1/90000}. The encoder holds the frames of each group of B pictures
     * until the anchor picture that follows them arrives, and its packets leave in decoding order with
     * decoding timestamps before their presentation timestamps.
     *
     * @apiNote Set the frame rate and bitrate of the stream with {@link Builder#frameRate(Rational)} and
     * {@link Builder#bitrate(long)} on {@link #toBuilder()}, and scale the key frame interval with the frame
     * rate through {@link Builder#keyframeInterval(int)} when the frame rate is not 30.
     *
     * @return the options
     */
    public static HevcEncoderOptions forFile() {
        return FILE;
    }

    /**
     * Returns the settings for real-time transmission, such as a video call.
     *
     * <p>The stream is {@link HevcProfile#MAIN} with I and P pictures only, so decoders output every picture
     * as soon as it is decoded, at a {@link HevcRateControl#CONSTANT_BITRATE} of 1 Mbit/s with a half-second
     * buffer and {@link #realtime()} tuning, with no periodic IDR picture (callers use
     * {@link HevcEncoder#requestKeyframe()} when a receiver loses sync), {@link HevcEncoderSpeed#FASTEST},
     * the quantizer window from 12 to 51, {@link HevcPacketFormat#ANNEX_B} packets, limited-range BT.601
     * color, one thread, the lowest fitting level, the nominal 30 frames per second and the 90 kHz RTP time
     * base {@code 1/90000}.
     *
     * @return the options
     */
    public static HevcEncoderOptions forRealtime() {
        return REALTIME;
    }

    /**
     * Returns the settings for a still image, such as the primary image of a HEIF file.
     *
     * <p>The stream is {@link HevcProfile#MAIN_STILL_PICTURE}, one intra picture at the
     * {@link HevcRateControl#CONSTANT_QUALITY} quantizer 26, with full-range BT.601 color of BT.709 primaries
     * and the sRGB transfer ({@link ColorInfo#JPEG}), {@link HevcEncoderSpeed#BALANCED}, one thread and
     * {@link HevcPacketFormat#LENGTH_PREFIXED} packets.
     *
     * @return the options
     */
    public static HevcEncoderOptions forStillPicture() {
        return STILL;
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
     * {@return the nominal frame rate in frames per second, from 0.001 to 1000, written to the stream's
     * timing information and used by rate control when timestamps are missing; 30 by default}
     *
     * <p>The numerator is at most {@code Integer.MAX_VALUE / 2}, so that it fits the stream timing fields.
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the unit of the timestamps of accepted frames and produced packets, positive; {@code 1/90000}
     * by default}
     */
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * {@return the profile, which bounds the bit depth and the number of pictures; {@link HevcProfile#MAIN} by
     * default}
     *
     * <p>A still picture profile codes the first frame given to the encoder as the only picture of the
     * stream; {@link #bFrames()}, {@link #keyframeInterval()} and the bitrate settings do not apply.
     */
    public HevcProfile profile() {
        return profile;
    }

    /**
     * {@return the level signaled in the stream, or empty when the encoder chooses the lowest level that fits
     * the picture size, sample rate, bitrate and buffer; empty by default}
     */
    public Optional<HevcLevel> level() {
        return Optional.ofNullable(level);
    }

    /**
     * {@return the bit depth of the luma and chroma samples, from 8 to {@link HevcProfile#maxBitDepth()} of
     * the profile; 8 by default}
     */
    public int bitDepth() {
        return bitDepth;
    }

    /**
     * {@return the rate control mode; {@link HevcRateControl#AVERAGE_BITRATE} by default}
     *
     * <ul>
     *   <li>{@link HevcRateControl#CONSTANT_QUALITY}: every frame is coded around the quantizer
     *       {@link #quality()}, IDR pictures slightly finer and B pictures slightly coarser; the bitrate,
     *       maximum bitrate and buffer are ignored.</li>
     *   <li>{@link HevcRateControl#AVERAGE_BITRATE}: the long-term average approaches {@link #bitrate()}
     *       while a video buffering verifier of {@link #bufferSize()} filled at {@link #maxBitrate()} bounds
     *       every picture.</li>
     *   <li>{@link HevcRateControl#CONSTANT_BITRATE}: as above with the maximum bitrate equal to the bitrate
     *       by default and a tighter per-frame budget, for real-time transmission.</li>
     * </ul>
     * Every frame quantizer stays inside the window from {@link #minQuantizer()} to {@link #maxQuantizer()};
     * a window narrower than {@value #MIN_QUANTIZER} to {@value #MAX_QUANTIZER} can make a frame miss its
     * rate target. A picture that would underflow the buffering verifier is coded again at coarser quantizers,
     * up to four times; one that still does not fit, because the buffer is smaller than the picture at
     * {@link #maxQuantizer()}, is kept and underflows it.
     */
    public HevcRateControl rateControl() {
        return rateControl;
    }

    /**
     * {@return the target bitrate in bits per second, from 1 to {@code Integer.MAX_VALUE}; 1000000 by
     * default}
     *
     * <p>{@link HevcRateControl#CONSTANT_QUALITY} ignores it.
     */
    public long bitrate() {
        return bitrate;
    }

    /**
     * {@return the rate in bits per second at which the buffering verifier fills, from the bitrate to
     * {@code Integer.MAX_VALUE}, or empty for the default of the mode: the bitrate for
     * {@link HevcRateControl#CONSTANT_BITRATE} and twice the bitrate for
     * {@link HevcRateControl#AVERAGE_BITRATE}; empty by default}
     *
     * <p>{@link HevcRateControl#CONSTANT_QUALITY} ignores it, and does not require it to reach the bitrate.
     */
    public OptionalLong maxBitrate() {
        return maxBitrate == 0 ? OptionalLong.empty() : OptionalLong.of(maxBitrate);
    }

    /**
     * {@return the size of the buffering verifier, as positive playback time at the maximum bitrate of at
     * most {@code Integer.MAX_VALUE} milliseconds, or empty for the default of the mode: half a second for
     * {@link HevcRateControl#CONSTANT_BITRATE} and one second for {@link HevcRateControl#AVERAGE_BITRATE};
     * empty by default}
     */
    public Optional<Duration> bufferSize() {
        return Optional.ofNullable(bufferSize);
    }

    /**
     * {@return the quantizer of {@link HevcRateControl#CONSTANT_QUALITY}, from {@value #MIN_QUANTIZER} (best)
     * to {@value #MAX_QUANTIZER} (worst); 28 by default}
     *
     * <p>Other modes ignore it.
     */
    public int quality() {
        return quality;
    }

    /**
     * {@return the most frames between two IDR pictures, positive, or empty for no periodic IDR picture; 60
     * by default}
     *
     * <p>Without periodic IDR pictures the first frame and {@link HevcEncoder#requestKeyframe()} still
     * produce one.
     */
    public OptionalInt keyframeInterval() {
        return keyframeInterval;
    }

    /**
     * {@return the speed and compression trade-off; {@link HevcEncoderSpeed#BALANCED} by default}
     */
    public HevcEncoderSpeed speed() {
        return speed;
    }

    /**
     * {@return the color description written to the stream and used to convert RGB input;
     * {@link ColorInfo#BT709_LIMITED} by default}
     *
     * <p>RGB input converts as {@link com.github.auties00.smalt.image.ops.ImageOps#toYuv16
     * ImageOps.toYuv16} converts it with this description. The range, primaries, transfer and matrix are
     * written to the video usability information, the chroma siting, when specified, as its
     * {@code chroma_sample_loc_type}, and the mastering display and content light level, when present, as
     * SEI messages of every IDR access unit. The identity matrix, which codes G, B and R as Y, Cb and Cr, is
     * not available for 4:2:0 pictures.
     */
    public ColorInfo color() {
        return color;
    }

    /**
     * {@return the most threads of the module's shared worker pool that analyze and code the rows of coding
     * tree blocks of a picture at once, from 1 to 64; 1 by default}
     *
     * <p>The output is the same for every thread count and scheduling.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return how the NAL units of a packet are delimited and where the parameter sets travel;
     * {@link HevcPacketFormat#LENGTH_PREFIXED} by default}
     */
    public HevcPacketFormat packetFormat() {
        return packetFormat;
    }

    /**
     * {@return the smallest quantizer any frame is coded with, from {@value #MIN_QUANTIZER} to
     * {@link #maxQuantizer()}; {@value #MIN_QUANTIZER} by default}
     */
    public int minQuantizer() {
        return minQuantizer;
    }

    /**
     * {@return the largest quantizer any frame is coded with, from {@link #minQuantizer()} to
     * {@value #MAX_QUANTIZER}; {@value #MAX_QUANTIZER} by default}
     */
    public int maxQuantizer() {
        return maxQuantizer;
    }

    /**
     * {@return whether the encoder is tuned for interactive transmission, where every frame must leave the
     * encoder promptly and at a bounded size; {@code false} by default}
     *
     * <p>Real-time tuning means:
     * <ul>
     *   <li>{@link #bFrames()} is ignored and treated as 0, so every frame produces its packet before
     *       {@link HevcEncoder#encode} returns;</li>
     *   <li>with a bitrate target, IDR pictures after the first are aimed at no more than seven frame
     *       budgets (the bits one frame of the nominal duration may use at the target bitrate), so an IDR
     *       picture does not drain the buffer the following pictures need;</li>
     *   <li>a frame whose timestamp does not increase is accepted rather than rejected (see
     *       {@link HevcEncoder#encode}).</li>
     * </ul>
     */
    public boolean realtime() {
        return realtime;
    }

    /**
     * {@return the number of consecutive B pictures the encoder codes between two anchor pictures, from 0 to
     * 7; 3 by default}
     *
     * <p>B pictures are predicted from the pictures on both sides in display order and cost fewer bits. With
     * two or more, the middle picture of each interval is coded first and referenced by the others (a B
     * pyramid), recursively. B pictures reorder the packets, so packets carry a decoding timestamp earlier
     * than the presentation timestamp, and the encoder holds that many frames before it codes the anchor
     * picture after them. {@link #realtime()} mode and the still picture profiles code none.
     */
    public int bFrames() {
        return bFrames;
    }

    /**
     * {@return the allocation limits the picture size is checked against; {@link MediaLimits#defaults()} by
     * default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the decoded picture hash SEI message attached to every picture; {@link HevcPictureHash#NONE} by
     * default}
     */
    public HevcPictureHash pictureHash() {
        return pictureHash;
    }

    /**
     * {@return the sample aspect ratio, the width of a sample over its height, written to the video
     * usability information; 1:1 by default}
     */
    public Rational sampleAspectRatio() {
        return sampleAspectRatio;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HevcEncoderOptions that
                && frameRate.equals(that.frameRate)
                && timeBase.equals(that.timeBase)
                && profile == that.profile
                && level == that.level
                && bitDepth == that.bitDepth
                && rateControl == that.rateControl
                && bitrate == that.bitrate
                && maxBitrate == that.maxBitrate
                && Objects.equals(bufferSize, that.bufferSize)
                && quality == that.quality
                && keyframeInterval.equals(that.keyframeInterval)
                && speed == that.speed
                && color.equals(that.color)
                && threads == that.threads
                && packetFormat == that.packetFormat
                && minQuantizer == that.minQuantizer
                && maxQuantizer == that.maxQuantizer
                && realtime == that.realtime
                && bFrames == that.bFrames
                && limits.equals(that.limits)
                && pictureHash == that.pictureHash
                && sampleAspectRatio.equals(that.sampleAspectRatio);
    }

    @Override
    public int hashCode() {
        return Objects.hash(frameRate, timeBase, profile, level, bitDepth, rateControl, bitrate, maxBitrate, bufferSize,
                quality, keyframeInterval, speed, color, threads, packetFormat, minQuantizer, maxQuantizer, realtime,
                bFrames, limits, pictureHash, sampleAspectRatio);
    }

    @Override
    public String toString() {
        return "HevcEncoderOptions[frameRate=" + frameRate + ", timeBase=" + timeBase + ", profile=" + profile
                + ", level=" + (level == null ? "automatic" : level) + ", bitDepth=" + bitDepth + ", rateControl="
                + rateControl + ", bitrate=" + bitrate + ", maxBitrate=" + (maxBitrate == 0 ? "default" : maxBitrate)
                + ", bufferSize=" + (bufferSize == null ? "default" : bufferSize) + ", quality=" + quality
                + ", keyframeInterval=" + keyframeInterval + ", speed=" + speed + ", color=" + color + ", threads="
                + threads + ", packetFormat=" + packetFormat + ", quantizers=" + minQuantizer + ".." + maxQuantizer
                + ", realtime=" + realtime + ", bFrames=" + bFrames + ", limits=" + limits + ", pictureHash="
                + pictureHash + ", sampleAspectRatio=" + sampleAspectRatio + "]";
    }

    /**
     * A builder of {@link HevcEncoderOptions}, starting from {@link HevcEncoderOptions#defaults()} or from
     * the options it was created from.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the values and how they combine. A builder
     * may build repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private Rational frameRate;

        private Rational timeBase;

        private HevcProfile profile;

        private HevcLevel level;

        private int bitDepth;

        private HevcRateControl rateControl;

        private long bitrate;

        private long maxBitrate;

        private boolean explicitMaxBitrate;

        private Duration bufferSize;

        private int quality;

        private OptionalInt keyframeInterval;

        private HevcEncoderSpeed speed;

        private ColorInfo color;

        private int threads;

        private HevcPacketFormat packetFormat;

        private int minQuantizer;

        private int maxQuantizer;

        private boolean realtime;

        private int bFrames;

        private MediaLimits limits;

        private HevcPictureHash pictureHash;

        private Rational sampleAspectRatio;

        private Builder() {
            frameRate = new Rational(30, 1);
            timeBase = new Rational(1, 90_000);
            profile = HevcProfile.MAIN;
            bitDepth = 8;
            rateControl = HevcRateControl.AVERAGE_BITRATE;
            bitrate = 1_000_000;
            quality = 28;
            keyframeInterval = OptionalInt.of(60);
            speed = HevcEncoderSpeed.BALANCED;
            color = ColorInfo.BT709_LIMITED;
            threads = 1;
            packetFormat = HevcPacketFormat.LENGTH_PREFIXED;
            minQuantizer = MIN_QUANTIZER;
            maxQuantizer = MAX_QUANTIZER;
            bFrames = 3;
            limits = MediaLimits.DEFAULT;
            pictureHash = HevcPictureHash.NONE;
            sampleAspectRatio = new Rational(1, 1);
        }

        private Builder(HevcEncoderOptions from) {
            frameRate = from.frameRate;
            timeBase = from.timeBase;
            profile = from.profile;
            level = from.level;
            bitDepth = from.bitDepth;
            rateControl = from.rateControl;
            bitrate = from.bitrate;
            maxBitrate = from.maxBitrate;
            explicitMaxBitrate = from.maxBitrate != 0;
            bufferSize = from.bufferSize;
            quality = from.quality;
            keyframeInterval = from.keyframeInterval;
            speed = from.speed;
            color = from.color;
            threads = from.threads;
            packetFormat = from.packetFormat;
            minQuantizer = from.minQuantizer;
            maxQuantizer = from.maxQuantizer;
            realtime = from.realtime;
            bFrames = from.bFrames;
            limits = from.limits;
            pictureHash = from.pictureHash;
            sampleAspectRatio = from.sampleAspectRatio;
        }

        /**
         * Sets the nominal frame rate; see {@link HevcEncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base of the timestamps; see {@link HevcEncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the profile; see {@link HevcEncoderOptions#profile()}.
         *
         * @param profile the value
         * @return this builder
         */
        public Builder profile(HevcProfile profile) {
            this.profile = Objects.requireNonNull(profile, "profile");
            return this;
        }

        /**
         * Sets the signaled level; see {@link HevcEncoderOptions#level()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder level(HevcLevel level) {
            this.level = Objects.requireNonNull(level, "level");
            return this;
        }

        /**
         * Lets the encoder choose the lowest level that fits the stream; see
         * {@link HevcEncoderOptions#level()}.
         *
         * @return this builder
         */
        public Builder automaticLevel() {
            this.level = null;
            return this;
        }

        /**
         * Sets the bit depth of the samples; see {@link HevcEncoderOptions#bitDepth()}.
         *
         * @param bitDepth the value
         * @return this builder
         */
        public Builder bitDepth(int bitDepth) {
            this.bitDepth = bitDepth;
            return this;
        }

        /**
         * Sets the rate control mode; see {@link HevcEncoderOptions#rateControl()}.
         *
         * @param rateControl the value
         * @return this builder
         */
        public Builder rateControl(HevcRateControl rateControl) {
            this.rateControl = Objects.requireNonNull(rateControl, "rateControl");
            return this;
        }

        /**
         * Sets the target bitrate; see {@link HevcEncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(long bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        /**
         * Sets the rate at which the buffering verifier fills; see {@link HevcEncoderOptions#maxBitrate()}.
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
         * Restores the default maximum bitrate of the rate control mode; see
         * {@link HevcEncoderOptions#maxBitrate()}.
         *
         * @return this builder
         */
        public Builder automaticMaxBitrate() {
            this.maxBitrate = 0;
            this.explicitMaxBitrate = false;
            return this;
        }

        /**
         * Sets the size of the buffering verifier; see {@link HevcEncoderOptions#bufferSize()}.
         *
         * @param bufferSize the value
         * @return this builder
         */
        public Builder bufferSize(Duration bufferSize) {
            this.bufferSize = Objects.requireNonNull(bufferSize, "bufferSize");
            return this;
        }

        /**
         * Restores the default buffer size of the rate control mode; see
         * {@link HevcEncoderOptions#bufferSize()}.
         *
         * @return this builder
         */
        public Builder automaticBufferSize() {
            this.bufferSize = null;
            return this;
        }

        /**
         * Sets the quantizer of constant quality coding; see {@link HevcEncoderOptions#quality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder quality(int quality) {
            this.quality = quality;
            return this;
        }

        /**
         * Sets the most frames between two IDR pictures; see {@link HevcEncoderOptions#keyframeInterval()}.
         *
         * @param frames the value
         * @return this builder
         */
        public Builder keyframeInterval(int frames) {
            this.keyframeInterval = OptionalInt.of(frames);
            return this;
        }

        /**
         * Removes the periodic IDR pictures; see {@link HevcEncoderOptions#keyframeInterval()}.
         *
         * @return this builder
         */
        public Builder noPeriodicKeyframes() {
            this.keyframeInterval = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the speed and compression trade-off; see {@link HevcEncoderOptions#speed()}.
         *
         * @param speed the value
         * @return this builder
         */
        public Builder speed(HevcEncoderSpeed speed) {
            this.speed = Objects.requireNonNull(speed, "speed");
            return this;
        }

        /**
         * Sets the color description; see {@link HevcEncoderOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Sets the most worker threads; see {@link HevcEncoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets how NAL units are delimited; see {@link HevcEncoderOptions#packetFormat()}.
         *
         * @param packetFormat the value
         * @return this builder
         */
        public Builder packetFormat(HevcPacketFormat packetFormat) {
            this.packetFormat = Objects.requireNonNull(packetFormat, "packetFormat");
            return this;
        }

        /**
         * Sets the window of frame quantizers; see {@link HevcEncoderOptions#minQuantizer()} and
         * {@link HevcEncoderOptions#maxQuantizer()}.
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
         * Sets the real-time tuning; see {@link HevcEncoderOptions#realtime()}.
         *
         * @param realtime the value
         * @return this builder
         */
        public Builder realtime(boolean realtime) {
            this.realtime = realtime;
            return this;
        }

        /**
         * Sets the number of consecutive B pictures; see {@link HevcEncoderOptions#bFrames()}.
         *
         * @param bFrames the value
         * @return this builder
         */
        public Builder bFrames(int bFrames) {
            this.bFrames = bFrames;
            return this;
        }

        /**
         * Sets the allocation limits; see {@link HevcEncoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the decoded picture hash; see {@link HevcEncoderOptions#pictureHash()}.
         *
         * @param pictureHash the value
         * @return this builder
         */
        public Builder pictureHash(HevcPictureHash pictureHash) {
            this.pictureHash = Objects.requireNonNull(pictureHash, "pictureHash");
            return this;
        }

        /**
         * Sets the sample aspect ratio; see {@link HevcEncoderOptions#sampleAspectRatio()}.
         *
         * @param sampleAspectRatio the value
         * @return this builder
         */
        public Builder sampleAspectRatio(Rational sampleAspectRatio) {
            this.sampleAspectRatio = Objects.requireNonNull(sampleAspectRatio, "sampleAspectRatio");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the frame rate is not in [0.001, 1000] or does not fit the stream
         * timing fields, the time base is not positive, the bitrate is not in [1, {@code Integer.MAX_VALUE}],
         * the maximum bitrate is not in [1, {@code Integer.MAX_VALUE}] or is below the bitrate in a bitrate
         * mode, the buffer size is not positive or exceeds {@code Integer.MAX_VALUE} milliseconds, the quality
         * is not in [0, 51], the key frame interval is not positive, the thread count is not in [1, 64], the B
         * picture count is not in [0, 7], the quantizer window is not inside [0, 51] or is empty, the bit depth
         * is not in [8, {@link HevcProfile#maxBitDepth()}] of the profile, the sample aspect ratio is not
         * positive or a term exceeds 65535, or the color matrix is {@link MatrixCoefficients#IDENTITY}
         */
        public HevcEncoderOptions build() {
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
                if (rateControl != HevcRateControl.CONSTANT_QUALITY && maxBitrate < bitrate) {
                    throw new IllegalArgumentException("maximum bitrate " + maxBitrate + " is below the bitrate "
                            + bitrate);
                }
            }
            if (bufferSize != null) {
                Checks.positive("buffer size", bufferSize);
                Checks.inRange("buffer size", bufferSize, Duration.ZERO, Duration.ofMillis(Integer.MAX_VALUE));
            }
            Checks.inRange("quality", quality, MIN_QUANTIZER, MAX_QUANTIZER);
            if (keyframeInterval.isPresent()) {
                Checks.positive("keyframe interval", keyframeInterval.getAsInt());
            }
            Checks.inRange("thread count", threads, 1, MAX_THREADS);
            Checks.inRange("B picture count", bFrames, 0, MAX_B_FRAMES);
            Checks.inRange("minimum quantizer", minQuantizer, MIN_QUANTIZER, MAX_QUANTIZER);
            Checks.inRange("maximum quantizer", maxQuantizer, minQuantizer, MAX_QUANTIZER);
            if (bitDepth < 8 || bitDepth > profile.maxBitDepth()) {
                throw new IllegalArgumentException("the " + profile + " profile does not code " + bitDepth
                        + "-bit samples");
            }
            if (sampleAspectRatio.numerator() <= 0 || sampleAspectRatio.denominator() <= 0
                    || sampleAspectRatio.numerator() > 65535 || sampleAspectRatio.denominator() > 65535) {
                throw new IllegalArgumentException("sample aspect ratio " + sampleAspectRatio
                        + " is not positive with terms of at most 65535");
            }
            if (color.matrix() == MatrixCoefficients.IDENTITY) {
                throw new IllegalArgumentException("the identity color matrix needs 4:4:4 pictures");
            }
            return new HevcEncoderOptions(this);
        }
    }
}
