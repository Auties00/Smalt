package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.check.Checks;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Holds the settings of a {@link FlacEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class FlacEncoderOptions {
    /**
     * The compression level of {@link #defaults()}, libFLAC's default.
     */
    public static final int DEFAULT_LEVEL = 5;

    /**
     * The largest sampling rate FLAC codes, {@code 2^20 - 1} hertz.
     */
    private static final int MAX_SAMPLE_RATE = (1 << 20) - 1;

    /**
     * The smallest block size of a FLAC stream.
     */
    private static final int MIN_BLOCK_SIZE = 16;

    /**
     * The largest block size of a FLAC stream.
     */
    private static final int MAX_BLOCK_SIZE = 65535;

    /**
     * The largest number of analysis windows libFLAC accepts.
     */
    private static final int MAX_WINDOWS = 32;

    /**
     * The largest padding a metadata block holds.
     */
    private static final int MAX_PADDING = (1 << 24) - 1;

    /**
     * The padding of the presets, as the {@code flac} command line tool writes it.
     */
    private static final int DEFAULT_PADDING = 8192;

    /**
     * The seek point interval of the presets, as the {@code flac} command line tool writes it.
     */
    private static final Duration DEFAULT_SEEK_INTERVAL = Duration.ofSeconds(10);

    /**
     * The options of {@link #defaults()}.
     */
    private static final FlacEncoderOptions DEFAULTS = new Builder().build();

    private final int sampleRate;

    private final ChannelLayout layout;

    private final int bitsPerSample;

    /**
     * The block size, zero to follow the maximum predictor order.
     */
    private final int blockSize;

    private final FlacStereoMode stereoMode;

    private final int maxLpcOrder;

    /**
     * The quantized coefficient precision, zero for libFLAC's choice.
     */
    private final int qlpPrecision;

    private final boolean precisionSearch;

    private final boolean exhaustiveModelSearch;

    private final boolean escapeCoding;

    private final int minPartitionOrder;

    private final int maxPartitionOrder;

    private final List<FlacApodization> apodizations;

    /**
     * The interval between seek points, or {@code null} for no seek table.
     */
    private final Duration seekPointInterval;

    private final int padding;

    /**
     * The expected number of samples per channel, or {@code -1} when unknown.
     */
    private final long expectedTotalSamples;

    private FlacEncoderOptions(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.layout = builder.layout;
        this.bitsPerSample = builder.bitsPerSample;
        this.blockSize = builder.blockSize == null ? 0 : builder.blockSize;
        this.stereoMode = builder.stereoMode;
        this.maxLpcOrder = builder.maxLpcOrder;
        this.qlpPrecision = builder.qlpPrecision == null ? 0 : builder.qlpPrecision;
        this.precisionSearch = builder.precisionSearch;
        this.exhaustiveModelSearch = builder.exhaustiveModelSearch;
        this.escapeCoding = builder.escapeCoding;
        this.minPartitionOrder = builder.minPartitionOrder;
        this.maxPartitionOrder = builder.maxPartitionOrder;
        this.apodizations = builder.apodizations;
        this.seekPointInterval = builder.seekPointInterval;
        this.padding = builder.padding;
        this.expectedTotalSamples = builder.expectedTotalSamples == null ? -1 : builder.expectedTotalSamples;
    }

    /**
     * Returns compression level {@value #DEFAULT_LEVEL} for 44.1 kHz 16-bit stereo input, with a
     * seek point every ten seconds and 8192 bytes of padding, as {@code flac} writes by default.
     *
     * @return the default options
     */
    public static FlacEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the defaults with one of libFLAC's compression levels, which trade encoding speed for
     * output size; {@link Builder#compressionLevel(int)} lists the settings of each level.
     *
     * @param level the level, from 0 (fastest) to 8 (smallest)
     * @return the options
     * @throws IllegalArgumentException if {@code level} is out of range
     */
    public static FlacEncoderOptions ofCompressionLevel(int level) {
        return new Builder().compressionLevel(level).build();
    }

    /**
     * Returns the preset for encoding speed: compression level 0, the fastest to encode.
     *
     * @return the options
     */
    public static FlacEncoderOptions forSpeed() {
        return ofCompressionLevel(0);
    }

    /**
     * Returns the preset for output size: compression level 8, the smallest output of the presets.
     *
     * @return the options
     */
    public static FlacEncoderOptions forSize() {
        return ofCompressionLevel(8);
    }

    /**
     * Returns the defaults for an input format.
     *
     * @param sampleRate the sampling rate in hertz
     * @param layout the channel layout, one to eight channels
     * @param bitsPerSample the sample size, from 4 to 32
     * @return the options
     * @throws IllegalArgumentException if a value is out of range
     */
    public static FlacEncoderOptions of(int sampleRate, ChannelLayout layout, int bitsPerSample) {
        return new Builder().format(sampleRate, layout).bitsPerSample(bitsPerSample).build();
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
     * {@return the input sampling rate in hertz, from 1 to {@code 2^20 - 1}; 44100 by default}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the input channel layout, one to eight channels; {@link ChannelLayout#STEREO} by
     * default}
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the sample size of the stream in bits, from 4 to 32; 16 by default}
     */
    public int bitsPerSample() {
        return bitsPerSample;
    }

    /**
     * {@return the number of samples per channel in every frame but the last, from 16 to 65535, at
     * least {@link #maxLpcOrder()}; by default libFLAC's choice of 1152 when linear prediction is off
     * and 4096 otherwise}
     */
    public int blockSize() {
        return blockSize != 0 ? blockSize : maxLpcOrder == 0 ? 1152 : 4096;
    }

    /**
     * {@return how stereo frames are decorrelated; {@link FlacStereoMode#MID_SIDE} by default}
     */
    public FlacStereoMode stereoMode() {
        return stereoMode;
    }

    /**
     * {@return the largest linear predictor order tried, from 0 (fixed predictors only) to 32; 8 by
     * default}
     */
    public int maxLpcOrder() {
        return maxLpcOrder;
    }

    /**
     * {@return the precision of the quantized predictor coefficients in bits, from 5 to 15, or empty
     * (the default) for libFLAC's choice from the sample and block sizes}
     */
    public OptionalInt qlpPrecision() {
        return qlpPrecision == 0 ? OptionalInt.empty() : OptionalInt.of(qlpPrecision);
    }

    /**
     * Returns the coefficient precision the encoder uses: {@link #qlpPrecision()} when set,
     * otherwise libFLAC's choice.
     *
     * @return the precision in bits
     */
    int resolvedQlpPrecision() {
        if (qlpPrecision != 0) {
            return qlpPrecision;
        }
        var size = blockSize();
        if (bitsPerSample < 16) {
            return Math.max(FlacFrameEncoder.MIN_QLP_PRECISION, 2 + bitsPerSample / 2);
        }
        if (bitsPerSample == 16) {
            return size <= 192 ? 7 : size <= 384 ? 8 : size <= 576 ? 9 : size <= 1152 ? 10
                    : size <= 2304 ? 11 : size <= 4608 ? 12 : 13;
        }
        return size <= 384 ? FlacFrameEncoder.MAX_QLP_PRECISION - 2
                : size <= 1152 ? FlacFrameEncoder.MAX_QLP_PRECISION - 1 : FlacFrameEncoder.MAX_QLP_PRECISION;
    }

    /**
     * {@return whether every coefficient precision is tried for each predictor; {@code false} by
     * default}
     */
    public boolean precisionSearch() {
        return precisionSearch;
    }

    /**
     * {@return whether every fixed and linear predictor order is tried rather than the order the
     * prediction error suggests; {@code false} by default}
     */
    public boolean exhaustiveModelSearch() {
        return exhaustiveModelSearch;
    }

    /**
     * {@return whether residual partitions may be stored verbatim behind an escape code when that is
     * smaller than Rice coding; {@code false} by default}
     */
    public boolean escapeCoding() {
        return escapeCoding;
    }

    /**
     * {@return the smallest Rice partition order tried, from 0 to {@link #maxPartitionOrder()}; 0 by
     * default}
     */
    public int minPartitionOrder() {
        return minPartitionOrder;
    }

    /**
     * {@return the largest Rice partition order tried, from {@link #minPartitionOrder()} to 15; 5 by
     * default}
     */
    public int maxPartitionOrder() {
        return maxPartitionOrder;
    }

    /**
     * {@return the window functions of the linear-prediction analysis, in order, one or more
     * expanding to at most 32 analysis windows; {@code tukey(0.5)} by default}
     */
    public List<FlacApodization> apodizations() {
        return apodizations;
    }

    /**
     * {@return the interval between the points of the {@code SEEKTABLE}, positive, or empty when no
     * seek table is written; ten seconds by default}
     *
     * <p>{@link FlacEncoder#codecConfig()} describes how the seek table is completed when the stream
     * ends.
     */
    public Optional<Duration> seekPointInterval() {
        return Optional.ofNullable(seekPointInterval);
    }

    /**
     * {@return the size in bytes of the {@code PADDING} block, from 0 (no padding block) to
     * {@code 2^24 - 1}; 8192 by default}
     *
     * <p>Other metadata (tags, pictures) can later fill the padding without rewriting the file.
     */
    public int padding() {
        return padding;
    }

    /**
     * {@return the number of samples per channel the caller expects to encode, at least 0, or empty
     * (the default) when unknown}
     *
     * <p>The count is written to {@code STREAMINFO} before the stream ends and lays out the seek
     * table.
     */
    public OptionalLong expectedTotalSamples() {
        return expectedTotalSamples < 0 ? OptionalLong.empty() : OptionalLong.of(expectedTotalSamples);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof FlacEncoderOptions that && sampleRate == that.sampleRate && layout.equals(that.layout)
                && bitsPerSample == that.bitsPerSample && blockSize == that.blockSize && stereoMode == that.stereoMode
                && maxLpcOrder == that.maxLpcOrder && qlpPrecision == that.qlpPrecision
                && precisionSearch == that.precisionSearch && exhaustiveModelSearch == that.exhaustiveModelSearch
                && escapeCoding == that.escapeCoding && minPartitionOrder == that.minPartitionOrder
                && maxPartitionOrder == that.maxPartitionOrder && apodizations.equals(that.apodizations)
                && Objects.equals(seekPointInterval, that.seekPointInterval) && padding == that.padding
                && expectedTotalSamples == that.expectedTotalSamples;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, bitsPerSample, blockSize, stereoMode, maxLpcOrder, qlpPrecision,
                precisionSearch, exhaustiveModelSearch, escapeCoding, minPartitionOrder, maxPartitionOrder, apodizations,
                seekPointInterval, padding, expectedTotalSamples);
    }

    @Override
    public String toString() {
        return "FlacEncoderOptions[sampleRate=" + sampleRate + ", layout=" + layout + ", bitsPerSample=" + bitsPerSample
                + ", blockSize=" + blockSize() + ", stereoMode=" + stereoMode + ", maxLpcOrder=" + maxLpcOrder
                + ", qlpPrecision=" + (qlpPrecision == 0 ? "auto" : qlpPrecision) + ", precisionSearch=" + precisionSearch
                + ", exhaustiveModelSearch=" + exhaustiveModelSearch + ", escapeCoding=" + escapeCoding
                + ", partitionOrders=" + minPartitionOrder + ".." + maxPartitionOrder + ", apodizations=" + apodizations
                + ", seekPointInterval=" + seekPointInterval + ", padding=" + padding
                + ", expectedTotalSamples=" + (expectedTotalSamples < 0 ? "unknown" : expectedTotalSamples) + "]";
    }

    /**
     * A builder of {@link FlacEncoderOptions}.
     *
     * <p>Setters reject only {@code null}, except {@link #compressionLevel(int)}, which applies its
     * preset at once; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private int sampleRate;

        private ChannelLayout layout;

        private int bitsPerSample;

        /**
         * The block size, or {@code null} for libFLAC's default.
         */
        private Integer blockSize;

        private FlacStereoMode stereoMode;

        private int maxLpcOrder;

        /**
         * The coefficient precision, or {@code null} for libFLAC's choice.
         */
        private Integer qlpPrecision;

        private boolean precisionSearch;

        private boolean exhaustiveModelSearch;

        private boolean escapeCoding;

        private int minPartitionOrder;

        private int maxPartitionOrder;

        private List<FlacApodization> apodizations;

        /**
         * The seek point interval, or {@code null} for no seek table.
         */
        private Duration seekPointInterval;

        private int padding;

        /**
         * The expected sample count, or {@code null} when unknown.
         */
        private Long expectedTotalSamples;

        private Builder() {
            this.sampleRate = 44_100;
            this.layout = ChannelLayout.STEREO;
            this.bitsPerSample = 16;
            this.seekPointInterval = DEFAULT_SEEK_INTERVAL;
            this.padding = DEFAULT_PADDING;
            compressionLevel(DEFAULT_LEVEL);
        }

        private Builder(FlacEncoderOptions options) {
            this.sampleRate = options.sampleRate;
            this.layout = options.layout;
            this.bitsPerSample = options.bitsPerSample;
            this.blockSize = options.blockSize == 0 ? null : options.blockSize;
            this.stereoMode = options.stereoMode;
            this.maxLpcOrder = options.maxLpcOrder;
            this.qlpPrecision = options.qlpPrecision == 0 ? null : options.qlpPrecision;
            this.precisionSearch = options.precisionSearch;
            this.exhaustiveModelSearch = options.exhaustiveModelSearch;
            this.escapeCoding = options.escapeCoding;
            this.minPartitionOrder = options.minPartitionOrder;
            this.maxPartitionOrder = options.maxPartitionOrder;
            this.apodizations = options.apodizations;
            this.seekPointInterval = options.seekPointInterval;
            this.padding = options.padding;
            this.expectedTotalSamples = options.expectedTotalSamples < 0 ? null : options.expectedTotalSamples;
        }

        /**
         * Sets the compression settings of one of libFLAC's levels, which trade encoding speed for
         * output size.
         *
         * <p>The level sets the stereo mode, the largest predictor order, the partition orders and
         * the windows as the table shows, resets the block size and the coefficient precision to
         * libFLAC's choice, and turns off the precision search, the exhaustive model search and
         * escape coding; the format and metadata settings are kept. Call it before changing any of
         * the settings it overwrites.
         * <table class="striped">
         * <caption>Compression levels</caption>
         * <thead><tr><th scope="col">Level</th><th scope="col">Stereo</th><th scope="col">Max LPC
         * order</th><th scope="col">Partition orders</th><th scope="col">Apodization</th>
         * <th scope="col">Block size</th></tr></thead>
         * <tbody>
         * <tr><th scope="row">0</th><td>independent</td><td>0 (fixed only)</td><td>0 to 3</td>
         * <td>tukey(0.5)</td><td>1152</td></tr>
         * <tr><th scope="row">1</th><td>loose mid/side</td><td>0</td><td>0 to 3</td><td>tukey(0.5)</td>
         * <td>1152</td></tr>
         * <tr><th scope="row">2</th><td>mid/side</td><td>0</td><td>0 to 3</td><td>tukey(0.5)</td>
         * <td>1152</td></tr>
         * <tr><th scope="row">3</th><td>independent</td><td>6</td><td>0 to 4</td><td>tukey(0.5)</td>
         * <td>4096</td></tr>
         * <tr><th scope="row">4</th><td>loose mid/side</td><td>8</td><td>0 to 4</td><td>tukey(0.5)</td>
         * <td>4096</td></tr>
         * <tr><th scope="row">5</th><td>mid/side</td><td>8</td><td>0 to 5</td><td>tukey(0.5)</td>
         * <td>4096</td></tr>
         * <tr><th scope="row">6</th><td>mid/side</td><td>8</td><td>0 to 6</td>
         * <td>subdivide_tukey(2)</td><td>4096</td></tr>
         * <tr><th scope="row">7</th><td>mid/side</td><td>12</td><td>0 to 6</td>
         * <td>subdivide_tukey(2)</td><td>4096</td></tr>
         * <tr><th scope="row">8</th><td>mid/side</td><td>12</td><td>0 to 6</td>
         * <td>subdivide_tukey(3)</td><td>4096</td></tr>
         * </tbody>
         * </table>
         * No level searches every order or precision or uses escape partitions. Every level produces
         * a subset ("streamable") stream for sample rates up to 48 kHz and sample sizes of 8, 12, 16,
         * 20, 24 or 32 bits.
         *
         * @implNote This implementation copies libFLAC 1.4's {@code compression_levels_} table.
         *
         * @param level the level, from 0 (fastest) to 8 (smallest)
         * @return this builder
         * @throws IllegalArgumentException if {@code level} is out of range
         */
        public Builder compressionLevel(int level) {
            Checks.inRange("compression level", level, 0, 8);
            stereoMode = switch (level) {
                case 0, 3 -> FlacStereoMode.INDEPENDENT;
                case 1, 4 -> FlacStereoMode.LOOSE_MID_SIDE;
                default -> FlacStereoMode.MID_SIDE;
            };
            maxLpcOrder = switch (level) {
                case 0, 1, 2 -> 0;
                case 3 -> 6;
                case 4, 5, 6 -> 8;
                default -> 12;
            };
            minPartitionOrder = 0;
            maxPartitionOrder = switch (level) {
                case 0, 1, 2 -> 3;
                case 3, 4 -> 4;
                case 5 -> 5;
                default -> 6;
            };
            apodizations = switch (level) {
                case 6, 7 -> List.of(FlacApodization.subdivideTukey(2, 0.5));
                case 8 -> List.of(FlacApodization.subdivideTukey(3, 0.5));
                default -> List.of(FlacApodization.tukey(0.5));
            };
            blockSize = null;
            qlpPrecision = null;
            precisionSearch = false;
            exhaustiveModelSearch = false;
            escapeCoding = false;
            return this;
        }

        /**
         * Sets the input format; see {@link FlacEncoderOptions#sampleRate()} and
         * {@link FlacEncoderOptions#layout()}.
         *
         * @param sampleRate the sampling rate
         * @param layout the channel layout
         * @return this builder
         */
        public Builder format(int sampleRate, ChannelLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the sample size; see {@link FlacEncoderOptions#bitsPerSample()}.
         *
         * @param bitsPerSample the value
         * @return this builder
         */
        public Builder bitsPerSample(int bitsPerSample) {
            this.bitsPerSample = bitsPerSample;
            return this;
        }

        /**
         * Sets the block size; see {@link FlacEncoderOptions#blockSize()}.
         *
         * @param blockSize the value
         * @return this builder
         */
        public Builder blockSize(int blockSize) {
            this.blockSize = blockSize;
            return this;
        }

        /**
         * Selects libFLAC's default block size; see {@link FlacEncoderOptions#blockSize()}.
         *
         * @return this builder
         */
        public Builder automaticBlockSize() {
            this.blockSize = null;
            return this;
        }

        /**
         * Sets the stereo decorrelation; see {@link FlacEncoderOptions#stereoMode()}.
         *
         * @param stereoMode the value
         * @return this builder
         */
        public Builder stereoMode(FlacStereoMode stereoMode) {
            this.stereoMode = Objects.requireNonNull(stereoMode, "stereoMode");
            return this;
        }

        /**
         * Sets the largest linear predictor order; see {@link FlacEncoderOptions#maxLpcOrder()}.
         *
         * @param maxLpcOrder the value
         * @return this builder
         */
        public Builder maxLpcOrder(int maxLpcOrder) {
            this.maxLpcOrder = maxLpcOrder;
            return this;
        }

        /**
         * Sets the coefficient precision; see {@link FlacEncoderOptions#qlpPrecision()}.
         *
         * @param precision the value
         * @return this builder
         */
        public Builder qlpPrecision(int precision) {
            this.qlpPrecision = precision;
            return this;
        }

        /**
         * Selects libFLAC's automatic coefficient precision; see
         * {@link FlacEncoderOptions#qlpPrecision()}.
         *
         * @return this builder
         */
        public Builder automaticQlpPrecision() {
            this.qlpPrecision = null;
            return this;
        }

        /**
         * Sets whether every coefficient precision is tried; see
         * {@link FlacEncoderOptions#precisionSearch()}.
         *
         * @param precisionSearch the value
         * @return this builder
         */
        public Builder precisionSearch(boolean precisionSearch) {
            this.precisionSearch = precisionSearch;
            return this;
        }

        /**
         * Sets whether every predictor order is tried; see
         * {@link FlacEncoderOptions#exhaustiveModelSearch()}.
         *
         * @param exhaustiveModelSearch the value
         * @return this builder
         */
        public Builder exhaustiveModelSearch(boolean exhaustiveModelSearch) {
            this.exhaustiveModelSearch = exhaustiveModelSearch;
            return this;
        }

        /**
         * Sets whether escape-coded partitions are allowed; see
         * {@link FlacEncoderOptions#escapeCoding()}.
         *
         * @param escapeCoding the value
         * @return this builder
         */
        public Builder escapeCoding(boolean escapeCoding) {
            this.escapeCoding = escapeCoding;
            return this;
        }

        /**
         * Sets the Rice partition orders tried; see {@link FlacEncoderOptions#minPartitionOrder()}
         * and {@link FlacEncoderOptions#maxPartitionOrder()}.
         *
         * @param min the smallest order
         * @param max the largest order
         * @return this builder
         */
        public Builder partitionOrders(int min, int max) {
            this.minPartitionOrder = min;
            this.maxPartitionOrder = max;
            return this;
        }

        /**
         * Sets the analysis windows; see {@link FlacEncoderOptions#apodizations()}.
         *
         * @param apodizations the value
         * @return this builder
         * @throws NullPointerException if an element of {@code apodizations} is {@code null}
         */
        public Builder apodizations(List<FlacApodization> apodizations) {
            this.apodizations = List.copyOf(apodizations);
            return this;
        }

        /**
         * Sets the interval between seek points; see {@link FlacEncoderOptions#seekPointInterval()}.
         *
         * @param interval the value
         * @return this builder
         */
        public Builder seekPointInterval(Duration interval) {
            this.seekPointInterval = Objects.requireNonNull(interval, "interval");
            return this;
        }

        /**
         * Removes the seek table; see {@link FlacEncoderOptions#seekPointInterval()}.
         *
         * @return this builder
         */
        public Builder noSeekTable() {
            this.seekPointInterval = null;
            return this;
        }

        /**
         * Sets the size of the padding block; see {@link FlacEncoderOptions#padding()}.
         *
         * @param padding the value
         * @return this builder
         */
        public Builder padding(int padding) {
            this.padding = padding;
            return this;
        }

        /**
         * Sets the expected stream length; see {@link FlacEncoderOptions#expectedTotalSamples()}.
         *
         * @param samples the value
         * @return this builder
         */
        public Builder expectedTotalSamples(long samples) {
            this.expectedTotalSamples = samples;
            return this;
        }

        /**
         * Clears the expected stream length, so that {@code STREAMINFO} records the length only when
         * the stream ends; see {@link FlacEncoderOptions#expectedTotalSamples()}.
         *
         * @return this builder
         */
        public Builder noExpectedTotalSamples() {
            this.expectedTotalSamples = null;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if a setting is outside the range its accessor states, the
         * apodizations are empty or expand to more than 32 windows, or the block size is smaller than
         * the largest predictor order
         */
        public FlacEncoderOptions build() {
            Checks.inRange("sample rate", sampleRate, 1, MAX_SAMPLE_RATE);
            Checks.inRange("channel count", layout.channels(), 1, 8);
            Checks.inRange("sample size", bitsPerSample, 4, 32);
            if (blockSize != null) {
                Checks.inRange("block size", blockSize, MIN_BLOCK_SIZE, MAX_BLOCK_SIZE);
            }
            Checks.inRange("LPC order", maxLpcOrder, 0, FlacFrameEncoder.MAX_LPC_ORDER);
            if (qlpPrecision != null) {
                Checks.inRange("coefficient precision", qlpPrecision, FlacFrameEncoder.MIN_QLP_PRECISION,
                        FlacFrameEncoder.MAX_QLP_PRECISION);
            }
            Checks.inRange("min partition order", minPartitionOrder, 0, FlacFrameEncoder.MAX_PARTITION_ORDER);
            Checks.inRange("max partition order", maxPartitionOrder, minPartitionOrder,
                    FlacFrameEncoder.MAX_PARTITION_ORDER);
            Checks.notEmpty("apodizations", apodizations);
            var windows = FlacWindow.expand(apodizations).size();
            if (windows > MAX_WINDOWS) {
                throw new IllegalArgumentException("apodizations " + apodizations + " expand to " + windows
                        + " windows, more than " + MAX_WINDOWS);
            }
            if (seekPointInterval != null) {
                Checks.positive("seek point interval", seekPointInterval);
            }
            Checks.inRange("padding", padding, 0, MAX_PADDING);
            if (expectedTotalSamples != null) {
                Checks.nonNegative("expected sample count", expectedTotalSamples.longValue());
            }
            var size = blockSize != null ? blockSize : maxLpcOrder == 0 ? 1152 : 4096;
            if (size < maxLpcOrder) {
                throw new IllegalArgumentException("block size " + size + " is smaller than the LPC order " + maxLpcOrder);
            }
            return new FlacEncoderOptions(this);
        }
    }
}
