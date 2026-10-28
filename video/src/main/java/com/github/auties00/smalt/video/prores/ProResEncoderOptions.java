package com.github.auties00.smalt.video.prores;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Holds the settings of a {@link ProResEncoder}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class ProResEncoderOptions {
    /**
     * The settings of {@link #defaults()}.
     */
    private static final ProResEncoderOptions DEFAULTS = new Builder().build();

    private final ProResProfile profile;

    private final Rational frameRate;

    private final Rational timeBase;

    private final OptionalLong bitrate;

    private final OptionalInt quantizationIndex;

    private final FieldOrder fieldOrder;

    private final ColorInfo color;

    private final boolean alpha;

    private final int alphaBits;

    private final int sliceSize;

    private final int threads;

    private final MediaLimits limits;

    private ProResEncoderOptions(Builder builder) {
        this.profile = builder.profile;
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.bitrate = builder.bitrate;
        this.quantizationIndex = builder.quantizationIndex;
        this.fieldOrder = builder.fieldOrder;
        this.color = builder.color;
        this.alpha = builder.alpha;
        this.alphaBits = builder.alphaBits;
        this.sliceSize = builder.sliceSize;
        this.threads = builder.threads;
        this.limits = builder.limits;
    }

    /**
     * Returns ProRes 422 HQ at 30 frames per second, timestamps in 1/90000 s, progressive, BT.709,
     * eight-macroblock slices on the shared pool.
     *
     * @return the options
     */
    public static ProResEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the {@link #defaults()} with another flavor.
     *
     * @param profile the flavor
     * @return the options
     */
    public static ProResEncoderOptions of(ProResProfile profile) {
        return new Builder().profile(profile).build();
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
     * {@return the flavor; {@link ProResProfile#HQ} by default}
     */
    public ProResProfile profile() {
        return profile;
    }

    /**
     * {@return the nominal frame rate, from 0.1 to 1000 frames per second, which scales the
     * flavor's target rate and sets the frame rate code of the frame header; 30 by default}
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
     * {@return the target bitrate in bits per second, positive, or empty for the flavor's target
     * rate; empty by default}
     *
     * <p>The encoder spends about {@link ProResProfile#bitsPerMacroblock()} bits per macroblock,
     * which is Apple's target data rate for the flavor scaled to the frame size and
     * {@link #frameRate()}, unless a bitrate sets another rate. Like Apple's encoders it treats the
     * rate as a ceiling: slices that code with fewer bits at the finest quantizer keep the saving.
     * A bitrate and a {@linkplain #quantizationIndex() quantization index} exclude each other.
     */
    public OptionalLong bitrate() {
        return bitrate;
    }

    /**
     * {@return the quantization index every slice uses instead of rate control, from 1 (finest) to
     * 224, or empty for rate control; empty by default}
     *
     * <p>A quantization index and a {@linkplain #bitrate() bitrate} exclude each other.
     */
    public OptionalInt quantizationIndex() {
        return quantizationIndex;
    }

    /**
     * {@return the scan of the coded frames; {@link FieldOrder#PROGRESSIVE} by default}
     *
     * <p>An interlaced order codes every frame as two field pictures.
     */
    public FieldOrder fieldOrder() {
        return fieldOrder;
    }

    /**
     * {@return the color description RGB input is converted with and the frame header then
     * carries; {@link ColorInfo#BT709_LIMITED} by default}
     *
     * <p>Y'CbCr input keeps its own description, which the frame header carries instead. RGB input is
     * converted with the BT.709 matrix when this matrix is
     * {@linkplain com.github.auties00.smalt.image.MatrixCoefficients#UNSPECIFIED unspecified}, and with every
     * other matrix as {@link com.github.auties00.smalt.image.ops.ImageOps#toYuv16 ImageOps.toYuv16} converts it,
     * except the {@linkplain com.github.auties00.smalt.image.MatrixCoefficients#IDENTITY identity} matrix, which the
     * frame header cannot signal and which RGB input cannot be encoded with.
     */
    public ColorInfo color() {
        return color;
    }

    /**
     * {@return whether the 4444 flavors code the input's alpha channel when the input has one;
     * {@code true} by default}
     */
    public boolean alpha() {
        return alpha;
    }

    /**
     * {@return the depth of the coded alpha samples, 8 or 16; 16 by default}
     */
    public int alphaBits() {
        return alphaBits;
    }

    /**
     * {@return the number of macroblocks of a full slice, 1, 2, 4 or 8; 8 by default}
     */
    public int sliceSize() {
        return sliceSize;
    }

    /**
     * {@return the thread count, at least 0; 0 by default}
     *
     * <p>1 encodes on the calling thread only, 0 spreads the slice rows of a frame over the
     * module's shared helper threads, and a larger value caps the threads one frame uses. The
     * output never depends on the thread count.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return the allocation limits the picture size is checked against;
     * {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProResEncoderOptions that && profile == that.profile && frameRate.equals(that.frameRate)
                && timeBase.equals(that.timeBase) && bitrate.equals(that.bitrate)
                && quantizationIndex.equals(that.quantizationIndex) && fieldOrder == that.fieldOrder
                && color.equals(that.color) && alpha == that.alpha && alphaBits == that.alphaBits
                && sliceSize == that.sliceSize && threads == that.threads && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profile, frameRate, timeBase, bitrate, quantizationIndex, fieldOrder, color, alpha,
                alphaBits, sliceSize, threads, limits);
    }

    @Override
    public String toString() {
        return "ProResEncoderOptions[profile=" + profile + ", frameRate=" + frameRate + ", timeBase=" + timeBase
                + ", bitrate=" + bitrate + ", quantizationIndex=" + quantizationIndex + ", fieldOrder=" + fieldOrder
                + ", color=" + color + ", alpha=" + alpha + ", alphaBits=" + alphaBits + ", sliceSize=" + sliceSize
                + ", threads=" + threads + ", limits=" + limits + "]";
    }

    /**
     * A builder of {@link ProResEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and the combinations
     * of settings. Instances are not thread-safe.
     */
    public static final class Builder {
        private ProResProfile profile = ProResProfile.HQ;

        private Rational frameRate = new Rational(30, 1);

        private Rational timeBase = new Rational(1, 90_000);

        private OptionalLong bitrate = OptionalLong.empty();

        private OptionalInt quantizationIndex = OptionalInt.empty();

        private FieldOrder fieldOrder = FieldOrder.PROGRESSIVE;

        private ColorInfo color = ColorInfo.BT709_LIMITED;

        private boolean alpha = true;

        private int alphaBits = 16;

        private int sliceSize = 8;

        private int threads;

        private MediaLimits limits = MediaLimits.DEFAULT;

        private Builder() {
        }

        private Builder(ProResEncoderOptions options) {
            this.profile = options.profile;
            this.frameRate = options.frameRate;
            this.timeBase = options.timeBase;
            this.bitrate = options.bitrate;
            this.quantizationIndex = options.quantizationIndex;
            this.fieldOrder = options.fieldOrder;
            this.color = options.color;
            this.alpha = options.alpha;
            this.alphaBits = options.alphaBits;
            this.sliceSize = options.sliceSize;
            this.threads = options.threads;
            this.limits = options.limits;
        }

        /**
         * Sets the flavor; see {@link ProResEncoderOptions#profile()}.
         *
         * @param profile the value
         * @return this builder
         */
        public Builder profile(ProResProfile profile) {
            this.profile = Objects.requireNonNull(profile, "profile");
            return this;
        }

        /**
         * Sets the nominal frame rate; see {@link ProResEncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base of the timestamps; see {@link ProResEncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the target bitrate; see {@link ProResEncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(long bitrate) {
            this.bitrate = OptionalLong.of(bitrate);
            return this;
        }

        /**
         * Targets the flavor's own data rate, scaled to the frame size and frame rate; see
         * {@link ProResEncoderOptions#bitrate()}.
         *
         * @return this builder
         */
        public Builder automaticBitrate() {
            this.bitrate = OptionalLong.empty();
            return this;
        }

        /**
         * Sets the quantization index every slice uses instead of rate control; see
         * {@link ProResEncoderOptions#quantizationIndex()}.
         *
         * @param quantizationIndex the value
         * @return this builder
         */
        public Builder quantizationIndex(int quantizationIndex) {
            this.quantizationIndex = OptionalInt.of(quantizationIndex);
            return this;
        }

        /**
         * Lets the rate control choose the quantization index of every slice; see
         * {@link ProResEncoderOptions#quantizationIndex()}.
         *
         * @return this builder
         */
        public Builder automaticQuantizationIndex() {
            this.quantizationIndex = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the scan of the coded frames; see {@link ProResEncoderOptions#fieldOrder()}.
         *
         * @param fieldOrder the value
         * @return this builder
         */
        public Builder fieldOrder(FieldOrder fieldOrder) {
            this.fieldOrder = Objects.requireNonNull(fieldOrder, "fieldOrder");
            return this;
        }

        /**
         * Sets the color description of RGB input; see {@link ProResEncoderOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Sets whether the 4444 flavors code alpha; see {@link ProResEncoderOptions#alpha()}.
         *
         * @param alpha the value
         * @return this builder
         */
        public Builder alpha(boolean alpha) {
            this.alpha = alpha;
            return this;
        }

        /**
         * Sets the depth of the coded alpha samples; see {@link ProResEncoderOptions#alphaBits()}.
         *
         * @param alphaBits the value
         * @return this builder
         */
        public Builder alphaBits(int alphaBits) {
            this.alphaBits = alphaBits;
            return this;
        }

        /**
         * Sets the number of macroblocks of a full slice; see
         * {@link ProResEncoderOptions#sliceSize()}.
         *
         * @param sliceSize the value
         * @return this builder
         */
        public Builder sliceSize(int sliceSize) {
            this.sliceSize = sliceSize;
            return this;
        }

        /**
         * Sets the thread count; see {@link ProResEncoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets the allocation limits; see {@link ProResEncoderOptions#limits()}.
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
         * is not positive, the bitrate is not positive, the quantization index is not in [1, 224],
         * both a bitrate and a quantization index are set, the alpha depth is not 8 or 16, the slice
         * size is not 1, 2, 4 or 8, or the thread count is negative
         */
        public ProResEncoderOptions build() {
            Checks.inRange("frame rate", frameRate.toDouble(), 0.1, 1000);
            Checks.positive("time base numerator", timeBase.numerator());
            Checks.positive("time base denominator", timeBase.denominator());
            if (bitrate.isPresent()) {
                Checks.positive("bitrate", bitrate.getAsLong());
            }
            if (quantizationIndex.isPresent()) {
                Checks.inRange("quantization index", quantizationIndex.getAsInt(), 1, 224);
            }
            if (bitrate.isPresent() && quantizationIndex.isPresent()) {
                throw new IllegalArgumentException("a quantization index replaces the rate control; set a bitrate "
                        + "or a quantization index, not both");
            }
            if (alphaBits != 8 && alphaBits != 16) {
                throw new IllegalArgumentException("alpha depth " + alphaBits + " is not 8 or 16");
            }
            if (sliceSize != 1 && sliceSize != 2 && sliceSize != 4 && sliceSize != 8) {
                throw new IllegalArgumentException("slice size " + sliceSize + " is not 1, 2, 4 or 8");
            }
            Checks.nonNegative("thread count", threads);
            return new ProResEncoderOptions(this);
        }
    }
}
