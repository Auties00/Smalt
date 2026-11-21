package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Holds the settings of a {@link MjpegEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class MjpegEncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final MjpegEncoderOptions DEFAULTS = new Builder().build();

    private final int quality;

    private final ChromaFormat chromaFormat;

    private final boolean optimizeHuffman;

    private final boolean huffmanTables;

    private final int restartInterval;

    private final FieldOrder fieldOrder;

    private final Rational frameRate;

    private final Rational timeBase;

    private final OptionalLong bitrate;

    private final int threads;

    private final MediaLimits limits;

    private MjpegEncoderOptions(Builder builder) {
        this.quality = builder.quality;
        this.chromaFormat = builder.chromaFormat;
        this.optimizeHuffman = builder.optimizeHuffman;
        this.huffmanTables = builder.huffmanTables;
        this.restartInterval = builder.restartInterval;
        this.fieldOrder = builder.fieldOrder;
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.bitrate = builder.bitrate;
        this.threads = builder.threads;
        this.limits = builder.limits;
    }

    /**
     * Returns constant quality 75 with 4:2:0 subsampling, optimized Huffman tables written in every
     * frame, no restart markers, progressive frames at 30 frames per second, timestamps in 1/90000 s,
     * on the shared pool.
     *
     * @return the default options
     */
    public static MjpegEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the {@link #defaults()} at another constant quality.
     *
     * @param quality the quality, from 0 (smallest) to 100 (best)
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is not in [0, 100]
     */
    public static MjpegEncoderOptions ofQuality(int quality) {
        return new Builder().quality(quality).build();
    }

    /**
     * Returns the {@link #defaults()} in the convention of AVI Motion-JPEG streams: the tables of
     * T.81 Annex K, left out of every frame, as the OpenDML and Microsoft Motion-JPEG DIB
     * specifications define.
     *
     * @return the options
     */
    public static MjpegEncoderOptions forAvi() {
        return new Builder().optimizeHuffman(false).huffmanTables(false).build();
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
     * {@return the quality, from 0 (smallest) to 100 (best); 75 by default}
     *
     * <p>The quality selects the quantization tables as {@link JpegEncoderOptions#quality()}
     * describes. Without a {@linkplain #bitrate() bitrate} every frame is coded at this quality;
     * with one it is the quality of the first frame.
     */
    public int quality() {
        return quality;
    }

    /**
     * {@return the chroma subsampling of the frames: {@link ChromaFormat#MONOCHROME} for luma only,
     * {@link ChromaFormat#YUV420}, {@link ChromaFormat#YUV422}, {@link ChromaFormat#YUV440} or
     * {@link ChromaFormat#YUV444}; {@code YUV420} by default}
     */
    public ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /**
     * {@return whether the Huffman tables are optimized for every image; {@code true} by default}
     *
     * <p>Optimized tables take a second pass over each image and give frames a few percent smaller
     * at the same quality; otherwise the tables of T.81 Annex K are used in one pass. Optimized
     * tables must be written in the frames, so they exclude {@code huffmanTables(false)}.
     */
    public boolean optimizeHuffman() {
        return optimizeHuffman;
    }

    /**
     * {@return whether every image carries its DHT segments; {@code true} by default}
     *
     * <p>Without them, the frames are abbreviated streams that rely on the tables of T.81 Annex K,
     * which Motion-JPEG decoders assume when a frame has none; still-image decoders may reject
     * such frames. Leaving the tables out requires {@link #optimizeHuffman()} to be
     * {@code false}.
     */
    public boolean huffmanTables() {
        return huffmanTables;
    }

    /**
     * {@return the number of MCUs between restart markers, from 1 to 65535, or 0 when no restart
     * marker is written; 0 by default}
     */
    public int restartInterval() {
        return restartInterval;
    }

    /**
     * {@return the scan of the coded frames; {@link FieldOrder#PROGRESSIVE} by default}
     *
     * <p>An interlaced order codes every frame as two field images in one packet, the field the
     * order names first: the top field holds the frame's rows 0, 2, 4 and so on, and each field's
     * chroma takes the alternate chroma rows the same way. Each field image starts with an OpenDML
     * {@code APP0 AVI1} segment, polarity 1 for the first field and 2 for the second, in place of
     * the JFIF segment of a progressive frame. A container learns the scan from its track, whose
     * {@link com.github.auties00.smalt.track.VideoTrack#interlacing() interlacing} the caller sets.
     *
     * @apiNote A QuickTime or Matroska track describes these frames as
     * {@link com.github.auties00.smalt.track.Interlacing#TOP_FIELD_FIRST_SEPARATED} or
     * {@link com.github.auties00.smalt.track.Interlacing#BOTTOM_FIELD_FIRST_SEPARATED}, the scans of
     * fields stored one after the other. AVI records no field order, and players commonly take the
     * first field of an AVI frame as the bottom field, so {@link FieldOrder#BOTTOM_FIRST} suits
     * interlaced AVI files; {@link MjpegDecoder} weaves such a file in that order only when the
     * track it is given declares a bottom-first scan.
     */
    public FieldOrder fieldOrder() {
        return fieldOrder;
    }

    /**
     * {@return the nominal frame rate, from 0.1 to 1000 frames per second, which gives the timing of
     * frames without one and the frame duration of the rate control; 30 by default}
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the time base of the timestamps of accepted frames and produced packets; the 90 kHz
     * time base {@code 1/90000} by default}
     */
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * {@return the target bitrate in bits per second, positive, or empty for constant quality;
     * empty by default}
     *
     * <p>With a bitrate, the quality of every frame after the first is chosen from the sizes of the
     * frames before it so that the stream's average rate approaches the target; a frame's quality
     * never leaves [1, 100], so a target outside the range those qualities reach is not met.
     */
    public OptionalLong bitrate() {
        return bitrate;
    }

    /**
     * {@return the thread count, at least 0; 0 by default}
     *
     * <p>1 encodes on the calling thread only, 0 spreads the MCU rows of a frame over the module's
     * shared helper threads, and a larger value caps the threads one frame uses. The output never
     * depends on the thread count.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return the limits the picture size is checked against; {@link MediaLimits#DEFAULT} by
     * default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MjpegEncoderOptions that
                && quality == that.quality
                && chromaFormat == that.chromaFormat
                && optimizeHuffman == that.optimizeHuffman
                && huffmanTables == that.huffmanTables
                && restartInterval == that.restartInterval
                && fieldOrder == that.fieldOrder
                && frameRate.equals(that.frameRate)
                && timeBase.equals(that.timeBase)
                && bitrate.equals(that.bitrate)
                && threads == that.threads
                && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(quality, chromaFormat, optimizeHuffman, huffmanTables, restartInterval, fieldOrder,
                frameRate, timeBase, bitrate, threads, limits);
    }

    @Override
    public String toString() {
        return "MjpegEncoderOptions[quality=" + quality + ", chromaFormat=" + chromaFormat + ", optimizeHuffman="
                + optimizeHuffman + ", huffmanTables=" + huffmanTables + ", restartInterval=" + restartInterval
                + ", fieldOrder=" + fieldOrder + ", frameRate=" + frameRate + ", timeBase=" + timeBase
                + ", bitrate=" + bitrate + ", threads=" + threads + ", limits=" + limits + "]";
    }

    /**
     * A builder of {@link MjpegEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and the Huffman
     * settings. Instances are not thread-safe.
     */
    public static final class Builder {
        private int quality = 75;

        private ChromaFormat chromaFormat = ChromaFormat.YUV420;

        private boolean optimizeHuffman = true;

        private boolean huffmanTables = true;

        private int restartInterval;

        private FieldOrder fieldOrder = FieldOrder.PROGRESSIVE;

        private Rational frameRate = new Rational(30, 1);

        private Rational timeBase = new Rational(1, 90_000);

        private OptionalLong bitrate = OptionalLong.empty();

        private int threads;

        private MediaLimits limits = MediaLimits.DEFAULT;

        private Builder() {
        }

        private Builder(MjpegEncoderOptions options) {
            this.quality = options.quality;
            this.chromaFormat = options.chromaFormat;
            this.optimizeHuffman = options.optimizeHuffman;
            this.huffmanTables = options.huffmanTables;
            this.restartInterval = options.restartInterval;
            this.fieldOrder = options.fieldOrder;
            this.frameRate = options.frameRate;
            this.timeBase = options.timeBase;
            this.bitrate = options.bitrate;
            this.threads = options.threads;
            this.limits = options.limits;
        }

        /**
         * Sets the quality; see {@link MjpegEncoderOptions#quality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder quality(int quality) {
            this.quality = quality;
            return this;
        }

        /**
         * Sets the chroma subsampling of the frames; see {@link MjpegEncoderOptions#chromaFormat()}.
         *
         * @param chromaFormat the value
         * @return this builder
         */
        public Builder chromaFormat(ChromaFormat chromaFormat) {
            this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
            return this;
        }

        /**
         * Sets whether the Huffman tables are optimized for every image; see
         * {@link MjpegEncoderOptions#optimizeHuffman()}.
         *
         * @param optimizeHuffman the value
         * @return this builder
         */
        public Builder optimizeHuffman(boolean optimizeHuffman) {
            this.optimizeHuffman = optimizeHuffman;
            return this;
        }

        /**
         * Sets whether every image carries its DHT segments; see
         * {@link MjpegEncoderOptions#huffmanTables()}.
         *
         * @param huffmanTables the value
         * @return this builder
         */
        public Builder huffmanTables(boolean huffmanTables) {
            this.huffmanTables = huffmanTables;
            return this;
        }

        /**
         * Sets the number of MCUs between restart markers; see
         * {@link MjpegEncoderOptions#restartInterval()}.
         *
         * @param restartInterval the value
         * @return this builder
         */
        public Builder restartInterval(int restartInterval) {
            this.restartInterval = restartInterval;
            return this;
        }

        /**
         * Sets the scan of the coded frames; see {@link MjpegEncoderOptions#fieldOrder()}.
         *
         * @param fieldOrder the value
         * @return this builder
         */
        public Builder fieldOrder(FieldOrder fieldOrder) {
            this.fieldOrder = Objects.requireNonNull(fieldOrder, "fieldOrder");
            return this;
        }

        /**
         * Sets the nominal frame rate; see {@link MjpegEncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base of the timestamps; see {@link MjpegEncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the target bitrate; see {@link MjpegEncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(long bitrate) {
            this.bitrate = OptionalLong.of(bitrate);
            return this;
        }

        /**
         * Codes every frame at the constant {@linkplain MjpegEncoderOptions#quality() quality}; see
         * {@link MjpegEncoderOptions#bitrate()}.
         *
         * @return this builder
         */
        public Builder noBitrate() {
            this.bitrate = OptionalLong.empty();
            return this;
        }

        /**
         * Sets the thread count; see {@link MjpegEncoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets the limits; see {@link MjpegEncoderOptions#limits()}.
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
         * @throws IllegalArgumentException if the quality is not in [0, 100], the restart interval is
         * not in [0, 65535], the frame rate is not in [0.1, 1000], a time base term or the bitrate
         * is not positive, the thread count is negative, or the Huffman tables are both optimized
         * and left out
         */
        public MjpegEncoderOptions build() {
            Checks.inRange("quality", quality, 0, 100);
            Checks.inRange("restart interval", restartInterval, 0, 0xFFFF);
            Checks.inRange("frame rate", frameRate.toDouble(), 0.1, 1000);
            Checks.positive("time base numerator", timeBase.numerator());
            Checks.positive("time base denominator", timeBase.denominator());
            if (bitrate.isPresent()) {
                Checks.positive("bitrate", bitrate.getAsLong());
            }
            Checks.nonNegative("thread count", threads);
            if (optimizeHuffman && !huffmanTables) {
                throw new IllegalArgumentException("optimized Huffman tables must be written in the frames");
            }
            return new MjpegEncoderOptions(this);
        }
    }
}
