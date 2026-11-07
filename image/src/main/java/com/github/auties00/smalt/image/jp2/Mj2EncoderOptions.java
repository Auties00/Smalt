package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Holds the settings of a {@link Mj2Encoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class Mj2EncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final Mj2EncoderOptions DEFAULTS = new Builder().build();

    private final Jp2EncoderOptions coding;

    private final Mj2PixelFormat pixelFormat;

    private final int bitDepth;

    private final FieldOrder fieldOrder;

    private final Rational frameRate;

    private final Rational timeBase;

    private final OptionalLong bitrate;

    private Mj2EncoderOptions(Builder builder) {
        this.coding = builder.coding;
        this.pixelFormat = builder.pixelFormat;
        this.bitDepth = builder.bitDepth;
        this.fieldOrder = builder.fieldOrder;
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.bitrate = builder.bitrate;
    }

    /**
     * Returns lossless coding ({@link Jp2EncoderOptions#defaults()}, every frame a JP2 file) of 8-bit 4:2:0
     * Y'CbCr, progressive frames at 30 frames per second, timestamps in 1/90000 s, without a target bitrate.
     *
     * @return the default options
     */
    public static Mj2EncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the {@link #defaults()} with lossy coding at a compression ratio
     * ({@link Jp2EncoderOptions#lossy(double)}).
     *
     * @param compressionRatio how many times smaller than the raw samples each frame is, above 1
     * @return the options
     * @throws IllegalArgumentException if {@code compressionRatio} is not above 1 and finite
     */
    public static Mj2EncoderOptions lossy(double compressionRatio) {
        return new Builder().coding(Jp2EncoderOptions.lossy(compressionRatio)).build();
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
     * {@return the coding of each image: the wavelet, quality layers, tiles, code-blocks, progression, block coder,
     * profile, metadata and limits; {@link Jp2EncoderOptions#defaults()} by default}
     *
     * <p>Its {@linkplain Jp2EncoderOptions#format() format} decides the form of a progressive frame: a JP2 file, or
     * a raw codestream. The fields of an interlaced frame are raw codestreams, each in a Contiguous Codestream box.
     * Its {@linkplain Jp2EncoderOptions#limits() limits} bound the picture size.
     */
    public Jp2EncoderOptions coding() {
        return coding;
    }

    /**
     * {@return the component layout frames are coded in; {@link Mj2PixelFormat#YUV420} by default}
     *
     * <p>A picture in another layout is converted first: a Y'CbCr picture keeps its matrix and range and has its
     * chroma resampled, an RGB picture is converted to Y'CbCr with the full-range BT.601 matrix of sYCC, and any
     * picture is converted to RGB for the RGB layouts.
     */
    public Mj2PixelFormat pixelFormat() {
        return pixelFormat;
    }

    /**
     * {@return the precision of every component, from 8 to 16 bits; 8 by default}
     *
     * <p>Y'CbCr samples of another depth are shifted to it, and RGB samples rescaled as
     * {@code round(v * (2^bitDepth - 1) / (2^d - 1))} from their depth {@code d}.
     */
    public int bitDepth() {
        return bitDepth;
    }

    /**
     * {@return how frames are split into fields; {@link FieldOrder#PROGRESSIVE} by default}
     *
     * <p>An interlaced frame is coded as two images, one per field, the earlier field first: the top field (rows 0,
     * 2, 4 and so on) for {@link FieldOrder#TOP_FIRST} and the bottom field for {@link FieldOrder#BOTTOM_FIRST}.
     *
     * @apiNote A container describes these frames as
     * {@link com.github.auties00.smalt.track.Interlacing#TOP_FIELD_FIRST_SEPARATED} or
     * {@link com.github.auties00.smalt.track.Interlacing#BOTTOM_FIELD_FIRST_SEPARATED}; set that scan on the track
     * so that a Motion JPEG 2000 file records it in its field coding box.
     */
    public FieldOrder fieldOrder() {
        return fieldOrder;
    }

    /**
     * {@return the nominal frame rate, from 0.1 to 1000 frames per second, which gives the timing of frames without
     * one and the frame duration of the rate control; 30 by default}
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the time base of the timestamps of accepted frames and produced packets; the 90 kHz time base
     * {@code 1/90000} by default}
     */
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * {@return the target bitrate in bits per second, positive, or empty for none; empty by default}
     *
     * <p>With a bitrate, every frame is coded with the rate allocation of
     * {@link Jp2EncoderOptions#maxCodestreamBytes()} capped at its share of the target, the bitrate times the
     * frame's duration, split evenly between the fields of an interlaced frame; the coding's own cap applies when
     * it is lower. A frame can exceed its share only by its headers, and a simple frame may stay below it.
     */
    public OptionalLong bitrate() {
        return bitrate;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Mj2EncoderOptions that
                && coding.equals(that.coding)
                && pixelFormat == that.pixelFormat
                && bitDepth == that.bitDepth
                && fieldOrder == that.fieldOrder
                && frameRate.equals(that.frameRate)
                && timeBase.equals(that.timeBase)
                && bitrate.equals(that.bitrate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(coding, pixelFormat, bitDepth, fieldOrder, frameRate, timeBase, bitrate);
    }

    @Override
    public String toString() {
        return "Mj2EncoderOptions[coding=" + coding + ", pixelFormat=" + pixelFormat + ", bitDepth=" + bitDepth
                + ", fieldOrder=" + fieldOrder + ", frameRate=" + frameRate + ", timeBase=" + timeBase
                + ", bitrate=" + bitrate + "]";
    }

    /**
     * A builder of {@link Mj2EncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private Jp2EncoderOptions coding = Jp2EncoderOptions.defaults();

        private Mj2PixelFormat pixelFormat = Mj2PixelFormat.YUV420;

        private int bitDepth = 8;

        private FieldOrder fieldOrder = FieldOrder.PROGRESSIVE;

        private Rational frameRate = new Rational(30, 1);

        private Rational timeBase = new Rational(1, 90_000);

        private OptionalLong bitrate = OptionalLong.empty();

        private Builder() {
        }

        private Builder(Mj2EncoderOptions options) {
            this.coding = options.coding;
            this.pixelFormat = options.pixelFormat;
            this.bitDepth = options.bitDepth;
            this.fieldOrder = options.fieldOrder;
            this.frameRate = options.frameRate;
            this.timeBase = options.timeBase;
            this.bitrate = options.bitrate;
        }

        /**
         * Sets the coding of each image; see {@link Mj2EncoderOptions#coding()}.
         *
         * @param coding the value
         * @return this builder
         */
        public Builder coding(Jp2EncoderOptions coding) {
            this.coding = Objects.requireNonNull(coding, "coding");
            return this;
        }

        /**
         * Sets the component layout; see {@link Mj2EncoderOptions#pixelFormat()}.
         *
         * @param pixelFormat the value
         * @return this builder
         */
        public Builder pixelFormat(Mj2PixelFormat pixelFormat) {
            this.pixelFormat = Objects.requireNonNull(pixelFormat, "pixelFormat");
            return this;
        }

        /**
         * Sets the component precision; see {@link Mj2EncoderOptions#bitDepth()}.
         *
         * @param bitDepth the value
         * @return this builder
         */
        public Builder bitDepth(int bitDepth) {
            this.bitDepth = bitDepth;
            return this;
        }

        /**
         * Sets how frames are split into fields; see {@link Mj2EncoderOptions#fieldOrder()}.
         *
         * @param fieldOrder the value
         * @return this builder
         */
        public Builder fieldOrder(FieldOrder fieldOrder) {
            this.fieldOrder = Objects.requireNonNull(fieldOrder, "fieldOrder");
            return this;
        }

        /**
         * Sets the nominal frame rate; see {@link Mj2EncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base; see {@link Mj2EncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the target bitrate; see {@link Mj2EncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(long bitrate) {
            this.bitrate = OptionalLong.of(bitrate);
            return this;
        }

        /**
         * Codes every frame without a target bitrate; see {@link Mj2EncoderOptions#bitrate()}.
         *
         * @return this builder
         */
        public Builder noBitrate() {
            this.bitrate = OptionalLong.empty();
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the bit depth is not in [8, 16], the frame rate is not in [0.1, 1000],
         * a time base term or the bitrate is not positive, or the coding uses a codestream profile together with
         * {@link Mj2PixelFormat#RGBA}
         */
        public Mj2EncoderOptions build() {
            Checks.inRange("bit depth", bitDepth, 8, 16);
            Checks.inRange("frame rate", frameRate.toDouble(), 0.1, 1000);
            Checks.positive("time base numerator", timeBase.numerator());
            Checks.positive("time base denominator", timeBase.denominator());
            if (bitrate.isPresent()) {
                Checks.positive("bitrate", bitrate.getAsLong());
            }
            if (pixelFormat == Mj2PixelFormat.RGBA && coding.profile().isPresent()) {
                throw new IllegalArgumentException("the " + coding.profile().get() + " profile codes no opacity");
            }
            return new Mj2EncoderOptions(this);
        }
    }
}
