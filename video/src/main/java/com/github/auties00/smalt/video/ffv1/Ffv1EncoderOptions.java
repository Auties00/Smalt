package com.github.auties00.smalt.video.ffv1;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link Ffv1Encoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class Ffv1EncoderOptions {
    /**
     * The settings of {@link #defaults()}.
     */
    private static final Ffv1EncoderOptions DEFAULTS = new Builder().build();

    /**
     * The settings of {@link #forSpeed()}.
     */
    private static final Ffv1EncoderOptions SPEED = new Builder().coder(Ffv1Coder.GOLOMB_RICE).build();

    private final int version;

    private final Ffv1Coder coder;

    private final boolean largeContext;

    private final boolean rgb;

    private final ChromaFormat chromaFormat;

    private final int bitDepth;

    private final boolean alpha;

    private final OptionalInt slices;

    private final boolean sliceCrc;

    private final int keyframeInterval;

    private final Rational frameRate;

    private final Rational timeBase;

    private final ColorInfo color;

    private final FieldOrder fieldOrder;

    private final Rational sampleAspectRatio;

    private final int threads;

    private Ffv1EncoderOptions(Builder builder) {
        this.version = builder.version;
        this.coder = builder.coder;
        this.largeContext = builder.largeContext;
        this.rgb = builder.rgb;
        this.chromaFormat = builder.chromaFormat;
        this.bitDepth = builder.bitDepth;
        this.alpha = builder.alpha;
        this.slices = builder.slices;
        this.sliceCrc = builder.sliceCrc;
        this.keyframeInterval = builder.keyframeInterval;
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.color = builder.color;
        this.fieldOrder = builder.fieldOrder;
        this.sampleAspectRatio = builder.sampleAspectRatio;
        this.threads = builder.threads;
    }

    /**
     * Returns version 3 with the range coder and its custom state table, 8-bit 4:2:0 Y'CbCr, slices sized to the
     * frame with CRCs, every frame a key frame, 30 frames per second with timestamps in 1/90000 s.
     *
     * @return the options
     */
    public static Ffv1EncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the {@link #defaults()} with the Golomb Rice coder, the fastest and least compact.
     *
     * @return the options
     */
    public static Ffv1EncoderOptions forSpeed() {
        return SPEED;
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
     * {@return the FFV1 version: 0, 1 or 3; 3 by default}
     *
     * <p>Version 0 codes 8-bit samples only, and neither version 0 nor version 1 has slices, slice CRCs or a
     * configuration record: they repeat their parameters in every key frame.
     */
    public int version() {
        return version;
    }

    /**
     * {@return the entropy coder; {@link Ffv1Coder#RANGE_CUSTOM_STATES} by default}
     *
     * <p>{@link Ffv1Coder#GOLOMB_RICE} codes depths up to 8 bits; a deeper stream is coded with
     * {@link Ffv1Coder#RANGE_CUSTOM_STATES} instead.
     */
    public Ffv1Coder coder() {
        return coder;
    }

    /**
     * {@return whether the large context model is used; {@code false} by default}
     *
     * <p>The large model adds the differences two samples to the left and two rows above to the context of each
     * sample: 16638 contexts instead of 666, which compresses large frames better and small frames, whose
     * contexts adapt less, worse.
     */
    public boolean largeContext() {
        return largeContext;
    }

    /**
     * {@return whether the pictures are coded as RGB through the JPEG 2000 reversible color transform; {@code false}
     * by default, coding them as Y'CbCr}
     */
    public boolean rgb() {
        return rgb;
    }

    /**
     * {@return the chroma subsampling of Y'CbCr coding; {@link ChromaFormat#YUV420} by default}
     *
     * <p>{@link ChromaFormat#MONOCHROME} codes the luma plane only. RGB coding ignores it.
     */
    public ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /**
     * {@return the coded depth in bits, from 8 to 16 (8 in version 0); 8 by default}
     */
    public int bitDepth() {
        return bitDepth;
    }

    /**
     * {@return whether a transparency plane is coded; {@code false} by default}
     *
     * <p>A picture without alpha is then coded opaque.
     */
    public boolean alpha() {
        return alpha;
    }

    /**
     * {@return the number of slices of a version 3 frame, from 1 to 1024, or empty to size them to the frame;
     * empty by default}
     *
     * <p>The slices form a raster of {@code columns * rows} equal parts as near to square as the count allows with
     * every slice boundary on a chroma sample boundary, which a subsampled frame of an odd size may only allow in
     * one direction. The automatic count is 1 up to 352 by 288 pixels, 6 up to 1280 by 720, 12 up to 1920 by 1088
     * and 24 above (or the next count whose raster fits the chroma sample boundaries), so that frames larger than
     * 352 by 288 have at least 4 slices, as RFC 9043 requires. Slices are coded in parallel; the output does not
     * depend on the thread count. Versions 0 and 1 have one slice.
     */
    public OptionalInt slices() {
        return slices;
    }

    /**
     * {@return whether each slice of a version 3 frame carries a CRC; {@code true} by default}
     */
    public boolean sliceCrc() {
        return sliceCrc;
    }

    /**
     * {@return the number of frames from one key frame to the next, positive; 1 by default}
     *
     * <p>A frame that is not a key frame continues the adaptation of the context states of the frame before it,
     * which compresses a little better but makes it depend on that frame.
     */
    public int keyframeInterval() {
        return keyframeInterval;
    }

    /**
     * {@return the nominal frame rate, which gives the duration of frames that declare none; 30 by default}
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the time base of the timestamps of accepted frames and produced packets; 1/90000 by default}
     */
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * {@return the color description that converts RGB pictures for Y'CbCr coding and Y'CbCr pictures for RGB
     * coding; BT.601 limited range by default}
     *
     * <p>FFV1 signals no color description; pictures already in the coded layout are coded as they are.
     */
    public ColorInfo color() {
        return color;
    }

    /**
     * {@return the field order a version 3 frame declares, or empty to declare none; progressive by default}
     */
    public Optional<FieldOrder> fieldOrder() {
        return Optional.ofNullable(fieldOrder);
    }

    /**
     * {@return the sample aspect ratio a version 3 frame declares, or empty to declare it unknown; empty by
     * default}
     */
    public Optional<Rational> sampleAspectRatio() {
        return Optional.ofNullable(sampleAspectRatio);
    }

    /**
     * {@return the thread count, at least 0; 0 by default}
     *
     * <p>0 lets the encoder use the module's shared pool of helper threads, 1 keeps the work on the calling
     * thread, and a larger value allows up to that many threads. The output never depends on the thread count.
     */
    public int threads() {
        return threads;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Ffv1EncoderOptions that
                && version == that.version
                && coder == that.coder
                && largeContext == that.largeContext
                && rgb == that.rgb
                && chromaFormat == that.chromaFormat
                && bitDepth == that.bitDepth
                && alpha == that.alpha
                && slices.equals(that.slices)
                && sliceCrc == that.sliceCrc
                && keyframeInterval == that.keyframeInterval
                && frameRate.equals(that.frameRate)
                && timeBase.equals(that.timeBase)
                && color.equals(that.color)
                && fieldOrder == that.fieldOrder
                && Objects.equals(sampleAspectRatio, that.sampleAspectRatio)
                && threads == that.threads;
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, coder, largeContext, rgb, chromaFormat, bitDepth, alpha, slices, sliceCrc,
                keyframeInterval, frameRate, timeBase, color, fieldOrder, sampleAspectRatio, threads);
    }

    @Override
    public String toString() {
        return "Ffv1EncoderOptions[version=" + version + ", coder=" + coder + ", largeContext=" + largeContext
                + ", rgb=" + rgb + ", chromaFormat=" + chromaFormat + ", bitDepth=" + bitDepth + ", alpha=" + alpha
                + ", slices=" + slices + ", sliceCrc=" + sliceCrc + ", keyframeInterval=" + keyframeInterval
                + ", frameRate=" + frameRate + ", timeBase=" + timeBase + ", color=" + color + ", fieldOrder="
                + fieldOrder + ", sampleAspectRatio=" + sampleAspectRatio + ", threads=" + threads + "]";
    }

    /**
     * Builds {@link Ffv1EncoderOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private int version = 3;

        private Ffv1Coder coder = Ffv1Coder.RANGE_CUSTOM_STATES;

        private boolean largeContext;

        private boolean rgb;

        private ChromaFormat chromaFormat = ChromaFormat.YUV420;

        private int bitDepth = 8;

        private boolean alpha;

        private OptionalInt slices = OptionalInt.empty();

        private boolean sliceCrc = true;

        private int keyframeInterval = 1;

        private Rational frameRate = new Rational(30, 1);

        private Rational timeBase = new Rational(1, 90_000);

        private ColorInfo color = ColorInfo.BT601_LIMITED;

        private FieldOrder fieldOrder = FieldOrder.PROGRESSIVE;

        private Rational sampleAspectRatio;

        private int threads;

        private Builder() {
        }

        private Builder(Ffv1EncoderOptions options) {
            this.version = options.version;
            this.coder = options.coder;
            this.largeContext = options.largeContext;
            this.rgb = options.rgb;
            this.chromaFormat = options.chromaFormat;
            this.bitDepth = options.bitDepth;
            this.alpha = options.alpha;
            this.slices = options.slices;
            this.sliceCrc = options.sliceCrc;
            this.keyframeInterval = options.keyframeInterval;
            this.frameRate = options.frameRate;
            this.timeBase = options.timeBase;
            this.color = options.color;
            this.fieldOrder = options.fieldOrder;
            this.sampleAspectRatio = options.sampleAspectRatio;
            this.threads = options.threads;
        }

        /**
         * Sets the FFV1 version; see {@link Ffv1EncoderOptions#version()}.
         *
         * @param version the value
         * @return this builder
         */
        public Builder version(int version) {
            this.version = version;
            return this;
        }

        /**
         * Sets the entropy coder; see {@link Ffv1EncoderOptions#coder()}.
         *
         * @param coder the value
         * @return this builder
         */
        public Builder coder(Ffv1Coder coder) {
            this.coder = Objects.requireNonNull(coder, "coder");
            return this;
        }

        /**
         * Sets whether the large context model is used; see {@link Ffv1EncoderOptions#largeContext()}.
         *
         * @param largeContext the value
         * @return this builder
         */
        public Builder largeContext(boolean largeContext) {
            this.largeContext = largeContext;
            return this;
        }

        /**
         * Sets whether pictures are coded as RGB; see {@link Ffv1EncoderOptions#rgb()}.
         *
         * @param rgb the value
         * @return this builder
         */
        public Builder rgb(boolean rgb) {
            this.rgb = rgb;
            return this;
        }

        /**
         * Sets the chroma subsampling; see {@link Ffv1EncoderOptions#chromaFormat()}.
         *
         * @param chromaFormat the value
         * @return this builder
         */
        public Builder chromaFormat(ChromaFormat chromaFormat) {
            this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
            return this;
        }

        /**
         * Sets the coded depth; see {@link Ffv1EncoderOptions#bitDepth()}.
         *
         * @param bitDepth the value
         * @return this builder
         */
        public Builder bitDepth(int bitDepth) {
            this.bitDepth = bitDepth;
            return this;
        }

        /**
         * Sets whether a transparency plane is coded; see {@link Ffv1EncoderOptions#alpha()}.
         *
         * @param alpha the value
         * @return this builder
         */
        public Builder alpha(boolean alpha) {
            this.alpha = alpha;
            return this;
        }

        /**
         * Sets the number of slices; see {@link Ffv1EncoderOptions#slices()}.
         *
         * @param slices the value
         * @return this builder
         */
        public Builder slices(int slices) {
            this.slices = OptionalInt.of(slices);
            return this;
        }

        /**
         * Sizes the slices to the frame; see {@link Ffv1EncoderOptions#slices()}.
         *
         * @return this builder
         */
        public Builder automaticSlices() {
            this.slices = OptionalInt.empty();
            return this;
        }

        /**
         * Sets whether slices carry CRCs; see {@link Ffv1EncoderOptions#sliceCrc()}.
         *
         * @param sliceCrc the value
         * @return this builder
         */
        public Builder sliceCrc(boolean sliceCrc) {
            this.sliceCrc = sliceCrc;
            return this;
        }

        /**
         * Sets the key frame interval; see {@link Ffv1EncoderOptions#keyframeInterval()}.
         *
         * @param keyframeInterval the value
         * @return this builder
         */
        public Builder keyframeInterval(int keyframeInterval) {
            this.keyframeInterval = keyframeInterval;
            return this;
        }

        /**
         * Sets the nominal frame rate; see {@link Ffv1EncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base; see {@link Ffv1EncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the color description of conversions; see {@link Ffv1EncoderOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Sets the declared field order; see {@link Ffv1EncoderOptions#fieldOrder()}.
         *
         * @param fieldOrder the value
         * @return this builder
         */
        public Builder fieldOrder(FieldOrder fieldOrder) {
            this.fieldOrder = Objects.requireNonNull(fieldOrder, "fieldOrder");
            return this;
        }

        /**
         * Declares no field order; see {@link Ffv1EncoderOptions#fieldOrder()}.
         *
         * @return this builder
         */
        public Builder noFieldOrder() {
            this.fieldOrder = null;
            return this;
        }

        /**
         * Sets the declared sample aspect ratio; see {@link Ffv1EncoderOptions#sampleAspectRatio()}.
         *
         * @param sampleAspectRatio the value
         * @return this builder
         */
        public Builder sampleAspectRatio(Rational sampleAspectRatio) {
            this.sampleAspectRatio = Objects.requireNonNull(sampleAspectRatio, "sampleAspectRatio");
            return this;
        }

        /**
         * Declares the sample aspect ratio unknown; see {@link Ffv1EncoderOptions#sampleAspectRatio()}.
         *
         * @return this builder
         */
        public Builder noSampleAspectRatio() {
            this.sampleAspectRatio = null;
            return this;
        }

        /**
         * Sets the thread count; see {@link Ffv1EncoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the version is not 0, 1 or 3, the depth is not in [8, 16] (or not 8
         * in version 0), the slice count is not in [1, 1024], the key frame interval is not positive, a frame
         * rate or time base term or a sample aspect ratio term is not positive, or the thread count is negative
         */
        public Ffv1EncoderOptions build() {
            if (version != 0 && version != 1 && version != 3) {
                throw new IllegalArgumentException("FFV1 version " + version + " is not 0, 1 or 3");
            }
            Checks.inRange("bit depth", bitDepth, 8, 16);
            if (version == 0 && bitDepth != 8) {
                throw new IllegalArgumentException("FFV1 version 0 codes 8-bit samples only");
            }
            if (slices.isPresent()) {
                Checks.inRange("slice count", slices.getAsInt(), 1, 1024);
            }
            Checks.positive("key frame interval", keyframeInterval);
            Checks.positive("frame rate numerator", frameRate.numerator());
            Checks.positive("frame rate denominator", frameRate.denominator());
            Checks.positive("time base numerator", timeBase.numerator());
            Checks.positive("time base denominator", timeBase.denominator());
            if (sampleAspectRatio != null) {
                Checks.positive("sample aspect ratio numerator", sampleAspectRatio.numerator());
                Checks.positive("sample aspect ratio denominator", sampleAspectRatio.denominator());
            }
            Checks.nonNegative("thread count", threads);
            return new Ffv1EncoderOptions(this);
        }
    }
}
