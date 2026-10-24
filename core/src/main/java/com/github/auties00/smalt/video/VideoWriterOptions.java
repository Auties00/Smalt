package com.github.auties00.smalt.video;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.codec.WriterSupport;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.TrackKind;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Holds the settings of a {@link VideoWriter}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class VideoWriterOptions {
    /**
     * The default frame rate, 30 frames per second.
     */
    public static final Rational DEFAULT_FRAME_RATE = new Rational(30, 1);

    private static final VideoWriterOptions DEFAULTS = new VideoWriterOptions(null, 0, 0, DEFAULT_FRAME_RATE, null, 0,
            false, 0, OptionalInt.empty(), true, Orientation.NORMAL, null, null, MediaTags.empty());

    private final Codec codec;
    private final int width;
    private final int height;
    private final Rational frameRate;
    private final Rational timeBase;
    private final long bitrate;
    private final boolean realtime;
    private final int threads;
    private final OptionalInt keyframeInterval;
    private final boolean periodicKeyframes;
    private final Orientation orientation;
    private final ColorInfo color;
    private final String language;
    private final MediaTags tags;

    private VideoWriterOptions(Codec codec, int width, int height, Rational frameRate, Rational timeBase, long bitrate,
                               boolean realtime,
                               int threads, OptionalInt keyframeInterval, boolean periodicKeyframes,
                               Orientation orientation, ColorInfo color, String language, MediaTags tags) {
        this.codec = codec;
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        this.timeBase = timeBase;
        this.bitrate = bitrate;
        this.realtime = realtime;
        this.threads = threads;
        this.keyframeInterval = keyframeInterval;
        this.periodicKeyframes = periodicKeyframes;
        this.orientation = orientation;
        this.color = color;
        this.language = language;
        this.tags = tags;
    }

    /**
     * Returns options that code the container's default codec at the size of the first picture,
     * 30 frames per second and a bitrate chosen from the size and rate.
     *
     * @return the default options
     */
    public static VideoWriterOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options for pictures of a size at a frame rate.
     *
     * @param width the picture width in pixels, positive
     * @param height the picture height in pixels, positive
     * @param frameRate the nominal frame rate in frames per second, positive
     * @return the options
     * @throws IllegalArgumentException if a value is not positive
     */
    public static VideoWriterOptions of(int width, int height, Rational frameRate) {
        return builder().size(width, height).frameRate(frameRate).build();
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
     * {@link com.github.auties00.smalt.transcode.OutputContainer#videoCodecs()} that an encoder is
     * available for; empty by default}
     *
     * <p>A writer over a caller's muxer needs a codec.
     */
    public Optional<Codec> codec() {
        return Optional.ofNullable(codec);
    }

    /**
     * {@return the coded picture width in pixels, positive, or empty for the width of the first
     * picture written; empty by default}
     *
     * <p>Every picture written must have the coded size. The size is set or unset together with
     * {@link #height()}.
     */
    public OptionalInt width() {
        return width == 0 ? OptionalInt.empty() : OptionalInt.of(width);
    }

    /**
     * {@return the coded picture height in pixels, positive, or empty for the height of the first
     * picture written; empty by default}
     */
    public OptionalInt height() {
        return height == 0 ? OptionalInt.empty() : OptionalInt.of(height);
    }

    /**
     * {@return the nominal frame rate in frames per second, positive; 30 by default}
     *
     * <p>It is the rate of the frames {@link VideoWriter#write(com.github.auties00.smalt.image.Picture)}
     * places back to back, the rate the track declares and the rate the encoder's rate control
     * assumes.
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the unit of the track's timestamps, positive and no longer than one frame interval
     * of the {@link #frameRate()}, or empty for the encoder's own time base; empty by default}
     *
     * <p>Presentation times passed to {@link VideoWriter#write(com.github.auties00.smalt.image.Picture, long)}
     * are rounded to it, and two frames must be at least one unit apart. The encoder's own time base
     * is one frame interval of the nominal rate for some codecs, too coarse for a variable-rate
     * stream; a finer unit, such as {@link Rational#MICROSECONDS} or {@code 1/90000}, keeps the
     * times of such a stream.
     */
    public Optional<Rational> timeBase() {
        return Optional.ofNullable(timeBase);
    }

    /**
     * {@return the target bitrate in bits per second, positive, or empty for one of 0.1 bit per pixel
     * and frame, between 100 kbit/s and 50 Mbit/s; empty by default}
     */
    public OptionalLong bitrate() {
        return bitrate == 0 ? OptionalLong.empty() : OptionalLong.of(bitrate);
    }

    /**
     * {@return whether the encoder is tuned for interactive transmission; {@code false} by default}
     *
     * @see VideoEncoderOptions#realtime()
     */
    public boolean realtime() {
        return realtime;
    }

    /**
     * {@return the encoder's thread count, at least 0; 0 by default}
     *
     * @see VideoEncoderOptions#threads()
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return the most frames from one key frame to the next, positive, or empty when the codec
     * chooses the distance or {@link #periodicKeyframes()} is {@code false}; empty by default}
     *
     * @see VideoEncoderOptions#keyframeInterval()
     */
    public OptionalInt keyframeInterval() {
        return keyframeInterval;
    }

    /**
     * {@return whether the stream may have periodic key frames; {@code true} by default}
     *
     * @see VideoEncoderOptions#periodicKeyframes()
     */
    public boolean periodicKeyframes() {
        return periodicKeyframes;
    }

    /**
     * {@return the display orientation the track declares, applied by players and not to the coded
     * pixels; {@link Orientation#NORMAL} by default}
     *
     * <p>Any other orientation needs a file that stores one
     * ({@link com.github.auties00.smalt.transcode.OutputContainer#storesOrientation()}).
     */
    public Orientation orientation() {
        return orientation;
    }

    /**
     * {@return the color description the track declares, or empty to declare none; empty by
     * default}
     *
     * <p>The description is metadata for the container; it does not convert the pictures.
     */
    public Optional<ColorInfo> color() {
        return Optional.ofNullable(color);
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
        return other instanceof VideoWriterOptions that
                && codec == that.codec
                && width == that.width
                && height == that.height
                && frameRate.equals(that.frameRate)
                && Objects.equals(timeBase, that.timeBase)
                && bitrate == that.bitrate
                && realtime == that.realtime
                && threads == that.threads
                && keyframeInterval.equals(that.keyframeInterval)
                && periodicKeyframes == that.periodicKeyframes
                && orientation == that.orientation
                && Objects.equals(color, that.color)
                && Objects.equals(language, that.language)
                && tags.equals(that.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(codec, width, height, frameRate, timeBase, bitrate, realtime, threads, keyframeInterval,
                periodicKeyframes, orientation, color, language, tags);
    }

    @Override
    public String toString() {
        return "VideoWriterOptions[codec=" + codec + ", width=" + width + ", height=" + height + ", frameRate="
                + frameRate + ", timeBase=" + timeBase + ", bitrate=" + bitrate + ", realtime=" + realtime + ", threads=" + threads
                + ", keyframeInterval=" + keyframeInterval + ", periodicKeyframes=" + periodicKeyframes
                + ", orientation=" + orientation + ", color=" + color + ", language=" + language + ", tags=" + tags
                + "]";
    }

    /**
     * Builds {@link VideoWriterOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private Codec codec;
        private int width;
        private int height;
        private Rational frameRate;
        private Rational timeBase;
        private long bitrate;
        private boolean bitrateSet;
        private boolean realtime;
        private int threads;
        private OptionalInt keyframeInterval;
        private boolean periodicKeyframes;
        private Orientation orientation;
        private ColorInfo color;
        private String language;
        private MediaTags tags;

        private Builder(VideoWriterOptions options) {
            this.codec = options.codec;
            this.width = options.width;
            this.height = options.height;
            this.frameRate = options.frameRate;
            this.timeBase = options.timeBase;
            this.bitrate = options.bitrate;
            this.bitrateSet = options.bitrate != 0;
            this.realtime = options.realtime;
            this.threads = options.threads;
            this.keyframeInterval = options.keyframeInterval;
            this.periodicKeyframes = options.periodicKeyframes;
            this.orientation = options.orientation;
            this.color = options.color;
            this.language = options.language;
            this.tags = options.tags;
        }

        /**
         * Sets the codec of the written track; see {@link VideoWriterOptions#codec()}.
         *
         * @param codec the value, a video codec
         * @return this builder
         * @throws IllegalArgumentException if {@code codec} is not a video codec
         */
        public Builder codec(Codec codec) {
            Objects.requireNonNull(codec, "codec");
            if (codec.kind() != TrackKind.VIDEO) {
                throw new IllegalArgumentException(codec.displayName() + " is not a video codec");
            }
            this.codec = codec;
            return this;
        }

        /**
         * Selects the container's default codec; see {@link VideoWriterOptions#codec()}.
         *
         * @return this builder
         */
        public Builder automaticCodec() {
            this.codec = null;
            return this;
        }

        /**
         * Sets the coded picture size; see {@link VideoWriterOptions#width()}.
         *
         * @param width the width in pixels
         * @param height the height in pixels
         * @return this builder
         * @throws IllegalArgumentException if a dimension is not positive
         */
        public Builder size(int width, int height) {
            this.width = Checks.positive("picture width", width);
            this.height = Checks.positive("picture height", height);
            return this;
        }

        /**
         * Takes the coded size from the first picture; see {@link VideoWriterOptions#width()}.
         *
         * @return this builder
         */
        public Builder automaticSize() {
            this.width = 0;
            this.height = 0;
            return this;
        }

        /**
         * Sets the nominal frame rate; see {@link VideoWriterOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the unit of the track's timestamps; see {@link VideoWriterOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Stamps the track in the encoder's own time base; see {@link VideoWriterOptions#timeBase()}.
         *
         * @return this builder
         */
        public Builder automaticTimeBase() {
            this.timeBase = null;
            return this;
        }

        /**
         * Sets the target bitrate; see {@link VideoWriterOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(long bitrate) {
            this.bitrate = bitrate;
            this.bitrateSet = true;
            return this;
        }

        /**
         * Selects a bitrate from the picture size and frame rate; see
         * {@link VideoWriterOptions#bitrate()}.
         *
         * @return this builder
         */
        public Builder automaticBitrate() {
            this.bitrate = 0;
            this.bitrateSet = false;
            return this;
        }

        /**
         * Sets whether the encoder is tuned for interactive transmission; see
         * {@link VideoWriterOptions#realtime()}.
         *
         * @param realtime the value
         * @return this builder
         */
        public Builder realtime(boolean realtime) {
            this.realtime = realtime;
            return this;
        }

        /**
         * Sets the encoder's thread count; see {@link VideoWriterOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets the most frames from one key frame to the next, turning periodic key frames on; see
         * {@link VideoWriterOptions#keyframeInterval()}.
         *
         * @param frames the distance in frames
         * @return this builder
         */
        public Builder keyframeInterval(int frames) {
            this.keyframeInterval = OptionalInt.of(frames);
            this.periodicKeyframes = true;
            return this;
        }

        /**
         * Codes key frames only at the first frame, at scene cuts and on request; see
         * {@link VideoWriterOptions#periodicKeyframes()}.
         *
         * @return this builder
         */
        public Builder noPeriodicKeyframes() {
            this.keyframeInterval = OptionalInt.empty();
            this.periodicKeyframes = false;
            return this;
        }

        /**
         * Lets the codec choose the key frame distance for its tuning; see
         * {@link VideoWriterOptions#keyframeInterval()}.
         *
         * @return this builder
         */
        public Builder automaticKeyframeInterval() {
            this.keyframeInterval = OptionalInt.empty();
            this.periodicKeyframes = true;
            return this;
        }

        /**
         * Sets the declared display orientation; see {@link VideoWriterOptions#orientation()}.
         *
         * @param orientation the value
         * @return this builder
         */
        public Builder orientation(Orientation orientation) {
            this.orientation = Objects.requireNonNull(orientation, "orientation");
            return this;
        }

        /**
         * Sets the declared color description; see {@link VideoWriterOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Declares no color description; see {@link VideoWriterOptions#color()}.
         *
         * @return this builder
         */
        public Builder noColor() {
            this.color = null;
            return this;
        }

        /**
         * Sets the declared language; see {@link VideoWriterOptions#language()}.
         *
         * @param language the value
         * @return this builder
         */
        public Builder language(String language) {
            this.language = Objects.requireNonNull(language, "language");
            return this;
        }

        /**
         * Declares no language; see {@link VideoWriterOptions#language()}.
         *
         * @return this builder
         */
        public Builder noLanguage() {
            this.language = null;
            return this;
        }

        /**
         * Sets the tags of the file; see {@link VideoWriterOptions#tags()}.
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
         * @throws IllegalArgumentException if the frame rate, the time base, the bitrate or the key
         * frame interval is not positive, the time base is longer than one frame interval, the thread
         * count is negative, or the language is not three lowercase ASCII letters
         */
        public VideoWriterOptions build() {
            Checks.positive("frame rate", frameRate.toDouble());
            if (timeBase != null) {
                Checks.positive("time base", timeBase.toDouble());
                var interval = BigInteger.valueOf(frameRate.denominator()).multiply(BigInteger.valueOf(timeBase.denominator()));
                var unit = BigInteger.valueOf(frameRate.numerator()).multiply(BigInteger.valueOf(timeBase.numerator()));
                if (interval.abs().compareTo(unit.abs()) < 0) {
                    throw new IllegalArgumentException("time base " + timeBase + " is longer than one frame interval at "
                            + frameRate + " frames per second");
                }
            }
            if (bitrateSet) {
                Checks.positive("bitrate", bitrate);
            }
            Checks.nonNegative("thread count", threads);
            if (keyframeInterval.isPresent()) {
                Checks.positive("key frame interval", keyframeInterval.getAsInt());
            }
            return new VideoWriterOptions(codec, width, height, frameRate, timeBase, bitrate, realtime, threads,
                    keyframeInterval, periodicKeyframes, orientation, color, WriterSupport.language(language), tags);
        }
    }
}
