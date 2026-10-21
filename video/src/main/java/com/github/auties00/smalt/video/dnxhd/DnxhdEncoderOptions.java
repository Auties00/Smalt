package com.github.auties00.smalt.video.dnxhd;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Holds the settings of a {@link DnxhdEncoder}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>VC-3 is a constant-size format: every frame of a compression ID takes the size SMPTE
 * ST 2019-1 assigns it (scaled by the macroblock count for DNxHR), so the bitrate follows from the
 * ID, the picture size and the frame rate.
 */
public final class DnxhdEncoderOptions {
    /**
     * The settings of {@link #defaults()}.
     */
    private static final DnxhdEncoderOptions DEFAULTS = new Builder().build();

    private final DnxhdProfile profile;

    private final OptionalInt compressionId;

    private final OptionalInt bitDepth;

    private final boolean rgb;

    private final boolean alternateColorSpace;

    private final Optional<ChromaFormat> chromaFormat;

    private final boolean alpha;

    private final boolean losslessAlpha;

    private final OptionalLong bitrate;

    private final Rational frameRate;

    private final Rational timeBase;

    private final FieldOrder fieldOrder;

    private final ColorInfo color;

    private final int threads;

    private final MediaLimits limits;

    private DnxhdEncoderOptions(Builder builder) {
        this.profile = builder.profile;
        this.compressionId = builder.compressionId;
        this.bitDepth = builder.bitDepth;
        this.rgb = builder.rgb;
        this.alternateColorSpace = builder.alternateColorSpace;
        this.chromaFormat = Optional.ofNullable(builder.chromaFormat);
        this.alpha = builder.alpha;
        this.losslessAlpha = builder.losslessAlpha;
        this.bitrate = builder.bitrate;
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.fieldOrder = builder.fieldOrder;
        this.color = builder.color;
        this.threads = builder.threads;
        this.limits = builder.limits;
    }

    /**
     * Returns DNxHR HQ at 30 frames per second, timestamps in 1/90000 s, progressive, BT.709, on the
     * shared pool.
     *
     * @return the options
     */
    public static DnxhdEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the {@link #defaults()} with another family.
     *
     * @param profile the family
     * @return the options
     */
    public static DnxhdEncoderOptions of(DnxhdProfile profile) {
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
     * {@return the family the encoder targets; {@link DnxhdProfile#DNXHR_HQ} by default}
     */
    public DnxhdProfile profile() {
        return profile;
    }

    /**
     * {@return the HD-profile compression ID a {@link DnxhdProfile#DNXHD} encoder must use, from
     * 1235 to 1260, or empty to pick it from the picture, scan, bit depth and bitrate; empty by
     * default}
     *
     * <p>Only the {@link DnxhdProfile#DNXHD} family takes an ID; a DNxHR family has its own.
     */
    public OptionalInt compressionId() {
        return compressionId;
    }

    /**
     * {@return the sample bit depth, 8, 10 or 12, or empty for the family's
     * {@link DnxhdProfile#defaultBitDepth()}; empty by default}
     *
     * <p>When empty, a {@link DnxhdProfile#DNXHD} encoder coding {@linkplain #rgb() RGB} uses 10
     * bits.
     */
    public OptionalInt bitDepth() {
        return bitDepth;
    }

    /**
     * {@return whether the 4:4:4 compression IDs (DNxHR 444 and ID 1256) code RGB rather than
     * Y'CbCr; {@code false} by default}
     *
     * <p>RGB streams set the color format flag of ST 2019-1 clause 7.2.5 and code their macroblocks
     * as {@link #alternateColorSpace()} selects, at the BT.709 levels (64 to 940 at 10 bits);
     * Y'CbCr 4:4:4 streams leave the flag clear.
     *
     * @apiNote Some decoders read the flag the other way round: they return 4:4:4 Y'CbCr streams as
     * RGB, with wrong colors, and RGB streams as 4:4:4 Y'CbCr, which holds the right colors only
     * with {@link #alternateColorSpace()}.
     */
    public boolean rgb() {
        return rgb;
    }

    /**
     * {@return whether RGB streams code every macroblock in the alternate color space, Y'CbCr;
     * {@code true} by default}
     *
     * <p>Each macroblock of an RGB stream ({@link #rgb()} set) is coded either as red, green and
     * blue or, with its alternate color space flag {@code ACF} set, as Y'CbCr that decoders convert
     * to RGB with the BT.709 matrix (ST 2019-1 clauses 7.3.1.1 and 8.1.1). When this option is set,
     * the encoder converts the RGB input of every macroblock to BT.709 Y'CbCr at the levels of the
     * stream (Y' from 64 to 940 and Cb and Cr centered on 512, at 10 bits) and sets {@code ACF} on
     * every macroblock; the stream still declares RGB ({@code CLF} set) and decoders return RGB.
     * When it is clear, the macroblocks code the red, green and blue samples themselves. Without
     * {@link #rgb()} the option has no effect.
     *
     * @apiNote Y'CbCr macroblocks are how the DNxHD 444 and DNxHR 444 files of other encoders are
     * coded, and decoders that read the color format flag the other way round show them with the
     * right colors. Clear this option for decoders that follow the standard when the color
     * conversion and its rounding are not wanted.
     */
    public boolean alternateColorSpace() {
        return alternateColorSpace;
    }

    /**
     * {@return the chroma layout of the coded Y'CbCr samples, or empty for the compression ID's
     * own; empty by default}
     *
     * <p>The compression ID's own layout is 4:4:4 for DNxHR 444 and ID 1256, 4:2:2 otherwise.
     * {@link ChromaFormat#YUV420} is valid for DNxHR LB, SQ, HQ and HQX only (ST 2019-1 Table C.2)
     * and needs an even width and height; pictures in another layout are resampled.
     *
     * @apiNote Not every decoder reads 4:2:0 DNxHR streams.
     */
    public Optional<ChromaFormat> chromaFormat() {
        return chromaFormat;
    }

    /**
     * {@return whether every frame carries an alpha channel; {@code false} by default}
     *
     * <p>Alpha is coded in band as ST 2019-1 with Amendment 1:2023 defines it: each frame grows by
     * half (the size of the compression ID with alpha), HD-profile frames take header version 4,
     * and every scan line codes its alpha after its video. The alpha comes from the picture's
     * alpha plane or channel, full range; a picture without alpha is coded opaque. Every
     * compression ID but 1253 and DNxHR LB permits alpha.
     *
     * @apiNote Not every decoder reads in-band alpha, and some encoders store alpha differently,
     * after the coding units of each frame; {@link DnxhdDecoder} reads both.
     */
    public boolean alpha() {
        return alpha;
    }

    /**
     * {@return whether the alpha channel is coded losslessly, with the differential run-length code
     * of ST 2019-1 clause 7.3.1.2, rather than with the DCT; {@code false} by default}
     *
     * <p>Only DNxHR 444 and compression ID 1256 permit it, and {@link #alpha()} must be set. A
     * frame whose lossless alpha would leave no room for the video falls back to DCT-coded alpha.
     */
    public boolean losslessAlpha() {
        return losslessAlpha;
    }

    /**
     * {@return the bitrate in bits per second a {@link DnxhdProfile#DNXHD} encoder picks its
     * compression ID by, positive, or empty for the largest frame size; empty by default}
     *
     * <p>The encoder picks the ID whose frame size at {@link #frameRate()} comes closest. Only the
     * {@link DnxhdProfile#DNXHD} family takes a bitrate; the size of a DNxHR frame is fixed.
     */
    public OptionalLong bitrate() {
        return bitrate;
    }

    /**
     * {@return the nominal frame rate, from 0.1 to 1000 frames per second; 30 by default}
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
     * {@return the scan of the coded frames; {@link FieldOrder#PROGRESSIVE} by default}
     *
     * <p>Only the interlaced DNxHD IDs code interlaced frames.
     */
    public FieldOrder fieldOrder() {
        return fieldOrder;
    }

    /**
     * {@return the color description RGB input is converted to Y'CbCr with;
     * {@link ColorInfo#BT709_LIMITED} by default}
     *
     * <p>An {@linkplain com.github.auties00.smalt.image.MatrixCoefficients#UNSPECIFIED unspecified} matrix
     * converts with BT.709; every other matrix converts as
     * {@link com.github.auties00.smalt.image.ops.ImageOps#toYuv16 ImageOps.toYuv16} does, except the
     * {@linkplain com.github.auties00.smalt.image.MatrixCoefficients#IDENTITY identity} matrix, which RGB input
     * cannot be encoded as Y'CbCr with (code it as {@linkplain #rgb() RGB} instead). The frame header
     * signals coded Y'CbCr samples in the BT.709 or an unspecified matrix as color volume 0 (BT.709), in the BT.2020
     * non-constant or constant luminance matrix as color volume 1 or 2, and in every other matrix as color volume 3,
     * which leaves the color description to the container.
     */
    public ColorInfo color() {
        return color;
    }

    /**
     * {@return the thread count, at least 0; 0 by default}
     *
     * <p>1 encodes on the calling thread only, 0 spreads the macroblock rows of a frame over the
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
        return other instanceof DnxhdEncoderOptions that && profile == that.profile
                && compressionId.equals(that.compressionId)
                && bitDepth.equals(that.bitDepth)
                && rgb == that.rgb
                && alternateColorSpace == that.alternateColorSpace
                && chromaFormat.equals(that.chromaFormat)
                && alpha == that.alpha
                && losslessAlpha == that.losslessAlpha
                && bitrate.equals(that.bitrate)
                && frameRate.equals(that.frameRate)
                && timeBase.equals(that.timeBase)
                && fieldOrder == that.fieldOrder
                && color.equals(that.color)
                && threads == that.threads
                && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profile, compressionId, bitDepth, rgb, alternateColorSpace, chromaFormat, alpha,
                losslessAlpha, bitrate, frameRate, timeBase, fieldOrder, color, threads, limits);
    }

    @Override
    public String toString() {
        return "DnxhdEncoderOptions[profile=" + profile + ", compressionId=" + compressionId + ", bitDepth="
                + bitDepth + ", rgb=" + rgb + ", alternateColorSpace=" + alternateColorSpace + ", chromaFormat="
                + chromaFormat + ", alpha=" + alpha + ", losslessAlpha=" + losslessAlpha + ", bitrate=" + bitrate
                + ", frameRate=" + frameRate + ", timeBase=" + timeBase + ", fieldOrder=" + fieldOrder + ", color="
                + color + ", threads=" + threads + ", limits=" + limits + "]";
    }

    /**
     * A builder of {@link DnxhdEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and the combinations of
     * settings. Instances are not thread-safe.
     */
    public static final class Builder {
        private DnxhdProfile profile = DnxhdProfile.DNXHR_HQ;

        private OptionalInt compressionId = OptionalInt.empty();

        private OptionalInt bitDepth = OptionalInt.empty();

        private boolean rgb;

        private boolean alternateColorSpace = true;

        private ChromaFormat chromaFormat;

        private boolean alpha;

        private boolean losslessAlpha;

        private OptionalLong bitrate = OptionalLong.empty();

        private Rational frameRate = new Rational(30, 1);

        private Rational timeBase = new Rational(1, 90_000);

        private FieldOrder fieldOrder = FieldOrder.PROGRESSIVE;

        private ColorInfo color = ColorInfo.BT709_LIMITED;

        private int threads;

        private MediaLimits limits = MediaLimits.DEFAULT;

        private Builder() {
        }

        private Builder(DnxhdEncoderOptions options) {
            this.profile = options.profile;
            this.compressionId = options.compressionId;
            this.bitDepth = options.bitDepth;
            this.rgb = options.rgb;
            this.alternateColorSpace = options.alternateColorSpace;
            this.chromaFormat = options.chromaFormat.orElse(null);
            this.alpha = options.alpha;
            this.losslessAlpha = options.losslessAlpha;
            this.bitrate = options.bitrate;
            this.frameRate = options.frameRate;
            this.timeBase = options.timeBase;
            this.fieldOrder = options.fieldOrder;
            this.color = options.color;
            this.threads = options.threads;
            this.limits = options.limits;
        }

        /**
         * Sets the family; see {@link DnxhdEncoderOptions#profile()}.
         *
         * @param profile the value
         * @return this builder
         */
        public Builder profile(DnxhdProfile profile) {
            this.profile = Objects.requireNonNull(profile, "profile");
            return this;
        }

        /**
         * Sets the HD-profile compression ID a {@link DnxhdProfile#DNXHD} encoder must use; see
         * {@link DnxhdEncoderOptions#compressionId()}.
         *
         * @param compressionId the value
         * @return this builder
         */
        public Builder compressionId(int compressionId) {
            this.compressionId = OptionalInt.of(compressionId);
            return this;
        }

        /**
         * Lets the encoder pick the compression ID from the picture, scan, bit depth and bitrate;
         * see {@link DnxhdEncoderOptions#compressionId()}.
         *
         * @return this builder
         */
        public Builder automaticCompressionId() {
            this.compressionId = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the sample bit depth; see {@link DnxhdEncoderOptions#bitDepth()}.
         *
         * @param bitDepth the value
         * @return this builder
         */
        public Builder bitDepth(int bitDepth) {
            this.bitDepth = OptionalInt.of(bitDepth);
            return this;
        }

        /**
         * Codes at the family's {@link DnxhdProfile#defaultBitDepth()}; see
         * {@link DnxhdEncoderOptions#bitDepth()}.
         *
         * @return this builder
         */
        public Builder automaticBitDepth() {
            this.bitDepth = OptionalInt.empty();
            return this;
        }

        /**
         * Sets whether 4:4:4 IDs code RGB; see {@link DnxhdEncoderOptions#rgb()}.
         *
         * @param rgb the value
         * @return this builder
         */
        public Builder rgb(boolean rgb) {
            this.rgb = rgb;
            return this;
        }

        /**
         * Sets whether RGB streams code their macroblocks as Y'CbCr; see
         * {@link DnxhdEncoderOptions#alternateColorSpace()}.
         *
         * @param alternateColorSpace the value
         * @return this builder
         */
        public Builder alternateColorSpace(boolean alternateColorSpace) {
            this.alternateColorSpace = alternateColorSpace;
            return this;
        }

        /**
         * Sets the chroma layout of the coded Y'CbCr samples; see
         * {@link DnxhdEncoderOptions#chromaFormat()}.
         *
         * @param chromaFormat the value
         * @return this builder
         */
        public Builder chromaFormat(ChromaFormat chromaFormat) {
            this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
            return this;
        }

        /**
         * Codes in the compression ID's own chroma layout; see
         * {@link DnxhdEncoderOptions#chromaFormat()}.
         *
         * @return this builder
         */
        public Builder automaticChromaFormat() {
            this.chromaFormat = null;
            return this;
        }

        /**
         * Sets whether frames carry an alpha channel; see {@link DnxhdEncoderOptions#alpha()}.
         *
         * @param alpha the value
         * @return this builder
         */
        public Builder alpha(boolean alpha) {
            this.alpha = alpha;
            return this;
        }

        /**
         * Sets whether the alpha channel is coded losslessly; see
         * {@link DnxhdEncoderOptions#losslessAlpha()}.
         *
         * @param losslessAlpha the value
         * @return this builder
         */
        public Builder losslessAlpha(boolean losslessAlpha) {
            this.losslessAlpha = losslessAlpha;
            return this;
        }

        /**
         * Sets the bitrate a {@link DnxhdProfile#DNXHD} encoder picks its compression ID by; see
         * {@link DnxhdEncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(long bitrate) {
            this.bitrate = OptionalLong.of(bitrate);
            return this;
        }

        /**
         * Picks the DNxHD compression ID with the largest frame size; see
         * {@link DnxhdEncoderOptions#bitrate()}.
         *
         * @return this builder
         */
        public Builder automaticBitrate() {
            this.bitrate = OptionalLong.empty();
            return this;
        }

        /**
         * Sets the nominal frame rate; see {@link DnxhdEncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base of the timestamps; see {@link DnxhdEncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the scan of the coded frames; see {@link DnxhdEncoderOptions#fieldOrder()}.
         *
         * @param fieldOrder the value
         * @return this builder
         */
        public Builder fieldOrder(FieldOrder fieldOrder) {
            this.fieldOrder = Objects.requireNonNull(fieldOrder, "fieldOrder");
            return this;
        }

        /**
         * Sets the color description of RGB input; see {@link DnxhdEncoderOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Sets the thread count; see {@link DnxhdEncoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets the allocation limits; see {@link DnxhdEncoderOptions#limits()}.
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
         * is not positive, the bit depth is not 8, 10 or 12, the compression ID is not in
         * [1235, 1260], the bitrate is not positive, a DNxHR family has a compression ID or a
         * bitrate, the thread count is negative, the chroma layout is not 4:2:0, 4:2:2 or 4:4:4, or
         * lossless alpha is set without alpha
         */
        public DnxhdEncoderOptions build() {
            Checks.inRange("frame rate", frameRate.toDouble(), 0.1, 1000);
            Checks.positive("time base numerator", timeBase.numerator());
            Checks.positive("time base denominator", timeBase.denominator());
            if (bitDepth.isPresent()) {
                var depth = bitDepth.getAsInt();
                if (depth != 8 && depth != 10 && depth != 12) {
                    throw new IllegalArgumentException("bit depth " + depth + " is not 8, 10 or 12");
                }
            }
            if (compressionId.isPresent()) {
                Checks.inRange("compression ID", compressionId.getAsInt(), 1235, 1260);
            }
            if (bitrate.isPresent()) {
                Checks.positive("bitrate", bitrate.getAsLong());
            }
            if (profile != DnxhdProfile.DNXHD && (compressionId.isPresent() || bitrate.isPresent())) {
                throw new IllegalArgumentException(profile + " has its own compression ID and frame size; a "
                        + "compression ID or bitrate needs the DNXHD profile");
            }
            Checks.nonNegative("thread count", threads);
            if (chromaFormat != null && chromaFormat != ChromaFormat.YUV420 && chromaFormat != ChromaFormat.YUV422
                    && chromaFormat != ChromaFormat.YUV444) {
                throw new IllegalArgumentException("chroma format " + chromaFormat + " is not 4:2:0, 4:2:2 or 4:4:4");
            }
            if (losslessAlpha && !alpha) {
                throw new IllegalArgumentException("lossless alpha needs alpha");
            }
            return new DnxhdEncoderOptions(this);
        }
    }
}
