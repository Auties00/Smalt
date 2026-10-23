package com.github.auties00.smalt.image.jpeg;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link JpegLsEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class JpegLsEncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final JpegLsEncoderOptions DEFAULTS = new Builder().build();

    private final int nearLossless;

    private final Interleave interleave;

    private final int precision;

    private final PresetParameters presetParameters;

    private final ColorTransform colorTransform;

    private final int restartInterval;

    private final boolean spiffHeader;

    private JpegLsEncoderOptions(Builder builder) {
        this.nearLossless = builder.nearLossless;
        this.interleave = builder.interleave;
        this.precision = builder.precision;
        this.presetParameters = builder.presetParameters;
        this.colorTransform = builder.colorTransform;
        this.restartInterval = builder.restartInterval;
        this.spiffHeader = builder.spiffHeader;
    }

    /**
     * Returns lossless coding with the automatic interleave mode and precision, the default coding parameters,
     * no color transform, no restart intervals and no SPIFF header unless the picture needs one.
     *
     * @return the default options
     */
    public static JpegLsEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the {@link #defaults()} with near-lossless coding.
     *
     * @param nearLossless the largest difference allowed between a sample and its decoded value, from 0 to 255
     * @return the options
     * @throws IllegalArgumentException if {@code nearLossless} is out of range
     */
    public static JpegLsEncoderOptions ofNearLossless(int nearLossless) {
        return new Builder().nearLossless(nearLossless).build();
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
     * {@return the near-lossless bound {@code NEAR}, from 0 to 255: the largest difference between a sample and
     * its decoded value; 0 (lossless) by default}
     *
     * <p>It must not exceed half the largest sample value of the precision, {@code (2^P - 1) / 2}.
     */
    public int nearLossless() {
        return nearLossless;
    }

    /**
     * {@return how the scans interleave the components, or empty for {@link Interleave#NONE} with one component
     * and {@link Interleave#LINE} with several; empty by default}
     *
     * <p>{@link Interleave#SAMPLE} needs components of one size.
     */
    public Optional<Interleave> interleave() {
        return Optional.ofNullable(interleave);
    }

    /**
     * {@return the sample precision {@code P} in bits, from 2 to 16, or empty for the bit depth of the picture;
     * empty by default}
     *
     * <p>Samples of another depth {@code D} are rescaled as {@code round(v * (2^P - 1) / (2^D - 1))}.
     */
    public OptionalInt precision() {
        return precision == 0 ? OptionalInt.empty() : OptionalInt.of(precision);
    }

    /**
     * {@return the gradient thresholds and the reset value coded instead of the defaults of ITU-T T.87
     * C.2.4.1.1, or empty for the defaults; empty by default}
     */
    public Optional<PresetParameters> presetParameters() {
        return Optional.ofNullable(presetParameters);
    }

    /**
     * {@return the reversible color transform applied to red, green and blue samples before coding;
     * {@link ColorTransform#NONE} by default}
     *
     * <p>A transform other than {@code NONE} needs an RGB picture, lossless coding, a precision of 8 or 16 bits
     * and interleaved scans.
     */
    public ColorTransform colorTransform() {
        return colorTransform;
    }

    /**
     * {@return the number of minimum coded units in a restart interval, or 0 for none; 0 by default}
     *
     * <p>A minimum coded unit is one line of a component scan, one line of every component of a
     * sample-interleaved scan, and {@code v} lines of each component, its vertical sampling factor, of a
     * line-interleaved scan.
     */
    public int restartInterval() {
        return restartInterval;
    }

    /**
     * {@return whether a SPIFF header (ITU-T T.84 annex F) naming the color space is written; {@code false} by
     * default}
     *
     * <p>A Y'CbCr picture always gets one, since decoders take three components without it for RGB.
     */
    public boolean spiffHeader() {
        return spiffHeader;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JpegLsEncoderOptions that
                && nearLossless == that.nearLossless
                && interleave == that.interleave
                && precision == that.precision
                && Objects.equals(presetParameters, that.presetParameters)
                && colorTransform == that.colorTransform
                && restartInterval == that.restartInterval
                && spiffHeader == that.spiffHeader;
    }

    @Override
    public int hashCode() {
        return Objects.hash(nearLossless, interleave, precision, presetParameters, colorTransform, restartInterval,
                spiffHeader);
    }

    @Override
    public String toString() {
        return "JpegLsEncoderOptions[nearLossless=" + nearLossless
                + ", interleave=" + (interleave == null ? "automatic" : interleave)
                + ", precision=" + (precision == 0 ? "automatic" : precision)
                + (presetParameters == null ? "" : ", presetParameters=" + presetParameters)
                + ", colorTransform=" + colorTransform
                + (restartInterval == 0 ? "" : ", restartInterval=" + restartInterval)
                + (spiffHeader ? ", spiffHeader" : "") + "]";
    }

    /**
     * A builder of {@link JpegLsEncoderOptions}.
     *
     * <p>Setters check their own range and reject {@code null}; the settings that depend on the picture are
     * checked when it is encoded. Instances are not thread-safe.
     */
    public static final class Builder {
        private int nearLossless;

        private Interleave interleave;

        private int precision;

        private PresetParameters presetParameters;

        private ColorTransform colorTransform = ColorTransform.NONE;

        private int restartInterval;

        private boolean spiffHeader;

        private Builder() {
        }

        private Builder(JpegLsEncoderOptions options) {
            this.nearLossless = options.nearLossless;
            this.interleave = options.interleave;
            this.precision = options.precision;
            this.presetParameters = options.presetParameters;
            this.colorTransform = options.colorTransform;
            this.restartInterval = options.restartInterval;
            this.spiffHeader = options.spiffHeader;
        }

        /**
         * Sets the near-lossless bound; see {@link JpegLsEncoderOptions#nearLossless()}.
         *
         * @param nearLossless the value
         * @return this builder
         * @throws IllegalArgumentException if {@code nearLossless} is not from 0 to 255
         */
        public Builder nearLossless(int nearLossless) {
            if (nearLossless < 0 || nearLossless > 255) {
                throw new IllegalArgumentException("near-lossless bound " + nearLossless + " is not from 0 to 255");
            }
            this.nearLossless = nearLossless;
            return this;
        }

        /**
         * Sets how the scans interleave the components; see {@link JpegLsEncoderOptions#interleave()}.
         *
         * @param interleave the value
         * @return this builder
         */
        public Builder interleave(Interleave interleave) {
            this.interleave = Objects.requireNonNull(interleave, "interleave");
            return this;
        }

        /**
         * Chooses the interleave mode from the component count; see {@link JpegLsEncoderOptions#interleave()}.
         *
         * @return this builder
         */
        public Builder automaticInterleave() {
            this.interleave = null;
            return this;
        }

        /**
         * Sets the sample precision; see {@link JpegLsEncoderOptions#precision()}.
         *
         * @param precision the value
         * @return this builder
         * @throws IllegalArgumentException if {@code precision} is not from 2 to 16
         */
        public Builder precision(int precision) {
            if (precision < 2 || precision > 16) {
                throw new IllegalArgumentException("precision " + precision + " is not from 2 to 16");
            }
            this.precision = precision;
            return this;
        }

        /**
         * Takes the sample precision from the picture; see {@link JpegLsEncoderOptions#precision()}.
         *
         * @return this builder
         */
        public Builder automaticPrecision() {
            this.precision = 0;
            return this;
        }

        /**
         * Sets the coded gradient thresholds and reset value; see
         * {@link JpegLsEncoderOptions#presetParameters()}.
         *
         * @param presetParameters the value
         * @return this builder
         */
        public Builder presetParameters(PresetParameters presetParameters) {
            this.presetParameters = Objects.requireNonNull(presetParameters, "presetParameters");
            return this;
        }

        /**
         * Codes with the default gradient thresholds and reset value; see
         * {@link JpegLsEncoderOptions#presetParameters()}.
         *
         * @return this builder
         */
        public Builder defaultPresetParameters() {
            this.presetParameters = null;
            return this;
        }

        /**
         * Sets the color transform; see {@link JpegLsEncoderOptions#colorTransform()}.
         *
         * @param colorTransform the value
         * @return this builder
         */
        public Builder colorTransform(ColorTransform colorTransform) {
            this.colorTransform = Objects.requireNonNull(colorTransform, "colorTransform");
            return this;
        }

        /**
         * Sets the restart interval; see {@link JpegLsEncoderOptions#restartInterval()}.
         *
         * @param restartInterval the value
         * @return this builder
         * @throws IllegalArgumentException if {@code restartInterval} is negative
         */
        public Builder restartInterval(int restartInterval) {
            if (restartInterval < 0) {
                throw new IllegalArgumentException("negative restart interval " + restartInterval);
            }
            this.restartInterval = restartInterval;
            return this;
        }

        /**
         * Sets whether a SPIFF header is written; see {@link JpegLsEncoderOptions#spiffHeader()}.
         *
         * @param spiffHeader the value
         * @return this builder
         */
        public Builder spiffHeader(boolean spiffHeader) {
            this.spiffHeader = spiffHeader;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         */
        public JpegLsEncoderOptions build() {
            return new JpegLsEncoderOptions(this);
        }
    }

    /**
     * Enumerates the interleave modes of ITU-T T.87 annex B.
     */
    public enum Interleave {
        /**
         * One scan per component ({@code ILV = 0}).
         */
        NONE,

        /**
         * One scan of every component, a line of each in turn ({@code ILV = 1}).
         */
        LINE,

        /**
         * One scan of every component, a sample of each in turn, with runs common to all components
         * ({@code ILV = 2}).
         */
        SAMPLE
    }

    /**
     * Enumerates the reversible color transforms of HP's JPEG-LS implementation, which an {@code APP8 "mrfx"}
     * segment signals; they are not part of ITU-T T.87, and decoders that do not know them decode the transformed
     * components.
     */
    public enum ColorTransform {
        /**
         * No transform.
         */
        NONE,

        /**
         * HP1: {@code (R - G, G, B - G)}, offset by half the range.
         */
        HP1,

        /**
         * HP2: {@code (R - G, G, B - (R + G) / 2)}, offset by half the range.
         */
        HP2,

        /**
         * HP3: {@code (G + (Cb + Cr) / 4, Cb, Cr)} with {@code Cb = B - G} and {@code Cr = R - G}, offset by half
         * the range.
         */
        HP3
    }

    /**
     * Holds the JPEG-LS preset coding parameters of ITU-T T.87 C.2.4.1.1: the gradient thresholds and the value
     * at which the context counters are halved, 0 standing for the default of each.
     *
     * <p>When coding, a threshold {@code T1} must be from {@code NEAR + 1} to the largest sample value,
     * {@code T2} from {@code T1}, {@code T3} from {@code T2}, and the reset value from 3 to
     * {@code max(255, 2^P - 1)}, each default taking the place of 0.
     *
     * @param threshold1 the first gradient threshold {@code T1}, or 0 for the default
     * @param threshold2 the second gradient threshold {@code T2}, or 0 for the default
     * @param threshold3 the third gradient threshold {@code T3}, or 0 for the default
     * @param reset the counter reset value {@code RESET}, or 0 for the default of 64
     */
    public record PresetParameters(int threshold1, int threshold2, int threshold3, int reset) {
        /**
         * @throws IllegalArgumentException if a value is negative or above 65535
         */
        public PresetParameters {
            for (var value : new int[]{threshold1, threshold2, threshold3, reset}) {
                if (value < 0 || value > 65535) {
                    throw new IllegalArgumentException("JPEG-LS preset parameter " + value + " is not from 0 to 65535");
                }
            }
        }
    }
}
