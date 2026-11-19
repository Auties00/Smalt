package com.github.auties00.smalt.audio.process;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a {@link ChannelMixer}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class ChannelMixerOptions {
    /**
     * The -3 dB gain, {@code sqrt(1/2)}, the default center and surround mix level.
     */
    public static final double MINUS_3DB = 0.7071067811865476;

    /**
     * The largest accepted mix level.
     */
    public static final double MAX_MIX_LEVEL = 4;

    /**
     * The center mix levels of the AC-3 {@code cmixlev} codes 0 to 2, ATSC A/52 Table 5.9.
     */
    private static final double[] AC3_CENTER_LEVELS = {MINUS_3DB, 0.5956621435290105, 0.5};

    /**
     * The surround mix levels of the AC-3 {@code surmixlev} codes 0 to 2, ATSC A/52 Table 5.10.
     */
    private static final double[] AC3_SURROUND_LEVELS = {MINUS_3DB, 0.5, 0};

    private static final ChannelMixerOptions DEFAULTS =
            new ChannelMixerOptions(false, MINUS_3DB, MINUS_3DB, 0, StereoDownmix.LO_RO, MINUS_3DB, MINUS_3DB, null);

    private final boolean normalize;
    private final double centerMixLevel;
    private final double surroundMixLevel;
    private final double lfeMixLevel;
    private final StereoDownmix stereoDownmix;
    private final double ltrtCenterMixLevel;
    private final double ltrtSurroundMixLevel;

    /**
     * The caller's coefficients, indexed {@code [output][input]}, or {@code null} for the rules.
     */
    private final double[][] matrix;

    private ChannelMixerOptions(boolean normalize, double centerMixLevel, double surroundMixLevel, double lfeMixLevel,
                                StereoDownmix stereoDownmix, double ltrtCenterMixLevel, double ltrtSurroundMixLevel,
                                double[][] matrix) {
        this.normalize = normalize;
        this.centerMixLevel = centerMixLevel;
        this.surroundMixLevel = surroundMixLevel;
        this.lfeMixLevel = lfeMixLevel;
        this.stereoDownmix = stereoDownmix;
        this.ltrtCenterMixLevel = ltrtCenterMixLevel;
        this.ltrtSurroundMixLevel = ltrtSurroundMixLevel;
        this.matrix = matrix;
    }

    /**
     * Selects how surround channels fold into a stereo output.
     */
    public enum StereoDownmix {
        /**
         * The conventional left-only/right-only downmix (ATSC A/52 section 7.8.2 Lo/Ro): the center
         * folds into both front speakers at {@link ChannelMixerOptions#centerMixLevel()} and each
         * surround into the front speaker of its side at {@link ChannelMixerOptions#surroundMixLevel()}.
         */
        LO_RO,

        /**
         * The matrix-encoded left-total/right-total downmix (ATSC A/52 section 7.8.2 Lt/Rt), which a
         * matrix surround decoder can steer back to the rear: the center enters both outputs at
         * {@link ChannelMixerOptions#ltrtCenterMixLevel()}, and the surrounds are summed into one
         * surround signal {@code S}, which enters the left output with a negative sign and the right
         * output with a positive sign, each at {@link ChannelMixerOptions#ltrtSurroundMixLevel()}.
         */
        LT_RT
    }

    /**
     * Returns the default options: the ITU-R BS.775 rules with center and surrounds at -3 dB, the
     * low-frequency channel left out, a left-only/right-only stereo downmix and no normalization.
     *
     * @return the default options
     */
    public static ChannelMixerOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options using the downmix levels an AC-3 or E-AC-3 bitstream codes, with the other
     * settings at their defaults.
     *
     * <p>The codes set the left-only/right-only levels {@link #centerMixLevel()} and
     * {@link #surroundMixLevel()}; the Lt/Rt levels stay at -3 dB, as ATSC A/52 section 7.8.2 fixes
     * them for AC-3.
     *
     * @param cmixlev the 2-bit {@code cmixlev} code, from 0 to 2: -3, -4.5 or -6 dB
     * @param surmixlev the 2-bit {@code surmixlev} code, from 0 to 2: -3 dB, -6 dB or off
     * @return the options
     * @throws IllegalArgumentException if a code is outside 0..2 (code 3 is reserved)
     */
    public static ChannelMixerOptions ofAc3MixLevels(int cmixlev, int surmixlev) {
        if (cmixlev < 0 || cmixlev > 2 || surmixlev < 0 || surmixlev > 2) {
            throw new IllegalArgumentException("AC-3 mix level codes " + cmixlev + " and " + surmixlev
                    + " are not both in 0..2");
        }
        return builder().centerMixLevel(AC3_CENTER_LEVELS[cmixlev])
                .surroundMixLevel(AC3_SURROUND_LEVELS[surmixlev]).build();
    }

    /**
     * Returns options that mix with a caller's coefficient matrix instead of the rules.
     *
     * @param matrix the coefficients, indexed {@code [output channel][input channel]}; copied
     * @return the options
     * @throws IllegalArgumentException if the matrix is empty, not rectangular, or holds a value
     * that is not finite
     */
    public static ChannelMixerOptions ofMatrix(double[][] matrix) {
        return builder().matrix(matrix).build();
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
     * {@return whether each output row whose absolute coefficients sum to more than one is divided by
     * that sum, which rules out clipping at the cost of a quieter mix; {@code false} by default}
     */
    public boolean normalize() {
        return normalize;
    }

    /**
     * {@return the gain of the front center folded into front left and right when the output has no
     * center, from 0 to {@value #MAX_MIX_LEVEL}; {@link #MINUS_3DB} by default}
     *
     * <p>A stereo output under {@link StereoDownmix#LT_RT} uses {@link #ltrtCenterMixLevel()} instead.
     */
    public double centerMixLevel() {
        return centerMixLevel;
    }

    /**
     * {@return the gain of a surround folded into the front speaker of its side when the output has
     * no surround on that side, from 0 to {@value #MAX_MIX_LEVEL}; {@link #MINUS_3DB} by default}
     *
     * <p>A stereo output under {@link StereoDownmix#LT_RT} uses {@link #ltrtSurroundMixLevel()} instead.
     */
    public double surroundMixLevel() {
        return surroundMixLevel;
    }

    /**
     * {@return the gain of the low-frequency channel when the output has none, from 0 to
     * {@value #MAX_MIX_LEVEL}; 0 (the channel is left out) by default}
     *
     * <p>A positive level sends it to the front center at that gain, or, without a center, to front
     * left and right at that gain times {@link #MINUS_3DB}.
     */
    public double lfeMixLevel() {
        return lfeMixLevel;
    }

    /**
     * {@return how surround channels fold into a {@link com.github.auties00.smalt.audio.ChannelLayout#STEREO}
     * output; {@link StereoDownmix#LO_RO} by default}
     *
     * <p>Other output layouts, mono included, always fold as {@link StereoDownmix#LO_RO} does.
     */
    public StereoDownmix stereoDownmix() {
        return stereoDownmix;
    }

    /**
     * {@return the gain of the front center in both outputs of a {@link StereoDownmix#LT_RT} downmix,
     * the {@code ltrtcmixlev} of E-AC-3, from 0 to {@value #MAX_MIX_LEVEL}; {@link #MINUS_3DB} by
     * default}
     */
    public double ltrtCenterMixLevel() {
        return ltrtCenterMixLevel;
    }

    /**
     * {@return the gain of the summed surround signal in both outputs of a {@link StereoDownmix#LT_RT}
     * downmix, the {@code ltrtsurmixlev} of E-AC-3, from 0 to {@value #MAX_MIX_LEVEL};
     * {@link #MINUS_3DB} by default}
     */
    public double ltrtSurroundMixLevel() {
        return ltrtSurroundMixLevel;
    }

    /**
     * {@return a copy of the caller's coefficient matrix, indexed {@code [output channel][input
     * channel]}, or empty to build the matrix from the two layouts by the rules of {@link ChannelMixer};
     * empty by default}
     *
     * <p>When present, the mix levels and {@link #stereoDownmix()} are ignored, {@link #normalize()}
     * still applies, and a mixer rejects layouts whose channel counts differ from the matrix
     * dimensions.
     */
    public Optional<double[][]> matrix() {
        return Optional.ofNullable(matrix).map(ChannelMixerOptions::copy);
    }

    /**
     * Returns the caller's matrix without copying it.
     *
     * @return the matrix, or {@code null} for the rules
     */
    double[][] matrixOrNull() {
        return matrix;
    }

    /**
     * Returns a deep copy of a matrix.
     *
     * @param matrix the rows
     * @return the copy
     */
    private static double[][] copy(double[][] matrix) {
        var result = new double[matrix.length][];
        for (var row = 0; row < matrix.length; row++) {
            result[row] = matrix[row].clone();
        }
        return result;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ChannelMixerOptions that && normalize == that.normalize
                && Double.compare(centerMixLevel, that.centerMixLevel) == 0
                && Double.compare(surroundMixLevel, that.surroundMixLevel) == 0
                && Double.compare(lfeMixLevel, that.lfeMixLevel) == 0
                && stereoDownmix == that.stereoDownmix
                && Double.compare(ltrtCenterMixLevel, that.ltrtCenterMixLevel) == 0
                && Double.compare(ltrtSurroundMixLevel, that.ltrtSurroundMixLevel) == 0
                && Arrays.deepEquals(matrix, that.matrix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(normalize, centerMixLevel, surroundMixLevel, lfeMixLevel, stereoDownmix,
                ltrtCenterMixLevel, ltrtSurroundMixLevel, Arrays.deepHashCode(matrix));
    }

    @Override
    public String toString() {
        return "ChannelMixerOptions[normalize=" + normalize + ", centerMixLevel=" + centerMixLevel
                + ", surroundMixLevel=" + surroundMixLevel + ", lfeMixLevel=" + lfeMixLevel + ", stereoDownmix="
                + stereoDownmix + ", ltrtCenterMixLevel=" + ltrtCenterMixLevel + ", ltrtSurroundMixLevel="
                + ltrtSurroundMixLevel + ", matrix=" + (matrix == null ? "rules" : Arrays.deepToString(matrix)) + "]";
    }

    /**
     * Builds {@link ChannelMixerOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private boolean normalize;
        private double centerMixLevel;
        private double surroundMixLevel;
        private double lfeMixLevel;
        private StereoDownmix stereoDownmix;
        private double ltrtCenterMixLevel;
        private double ltrtSurroundMixLevel;
        private double[][] matrix;

        private Builder(ChannelMixerOptions options) {
            this.normalize = options.normalize;
            this.centerMixLevel = options.centerMixLevel;
            this.surroundMixLevel = options.surroundMixLevel;
            this.lfeMixLevel = options.lfeMixLevel;
            this.stereoDownmix = options.stereoDownmix;
            this.ltrtCenterMixLevel = options.ltrtCenterMixLevel;
            this.ltrtSurroundMixLevel = options.ltrtSurroundMixLevel;
            this.matrix = options.matrix;
        }

        /**
         * Sets whether rows are normalized; see {@link ChannelMixerOptions#normalize()}.
         *
         * @param normalize the value
         * @return this builder
         */
        public Builder normalize(boolean normalize) {
            this.normalize = normalize;
            return this;
        }

        /**
         * Sets the center mix level; see {@link ChannelMixerOptions#centerMixLevel()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder centerMixLevel(double level) {
            this.centerMixLevel = level;
            return this;
        }

        /**
         * Sets the surround mix level; see {@link ChannelMixerOptions#surroundMixLevel()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder surroundMixLevel(double level) {
            this.surroundMixLevel = level;
            return this;
        }

        /**
         * Sets the low-frequency mix level; see {@link ChannelMixerOptions#lfeMixLevel()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder lfeMixLevel(double level) {
            this.lfeMixLevel = level;
            return this;
        }

        /**
         * Sets the stereo downmix; see {@link ChannelMixerOptions#stereoDownmix()}.
         *
         * @param downmix the value
         * @return this builder
         */
        public Builder stereoDownmix(StereoDownmix downmix) {
            this.stereoDownmix = Objects.requireNonNull(downmix, "downmix");
            return this;
        }

        /**
         * Sets the Lt/Rt center mix level; see {@link ChannelMixerOptions#ltrtCenterMixLevel()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder ltrtCenterMixLevel(double level) {
            this.ltrtCenterMixLevel = level;
            return this;
        }

        /**
         * Sets the Lt/Rt surround mix level; see {@link ChannelMixerOptions#ltrtSurroundMixLevel()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder ltrtSurroundMixLevel(double level) {
            this.ltrtSurroundMixLevel = level;
            return this;
        }

        /**
         * Sets the caller's coefficient matrix; see {@link ChannelMixerOptions#matrix()}.
         *
         * @param matrix the value, copied
         * @return this builder
         * @throws NullPointerException if a row is {@code null}
         */
        public Builder matrix(double[][] matrix) {
            Objects.requireNonNull(matrix, "matrix");
            for (var row : matrix) {
                Objects.requireNonNull(row, "matrix row");
            }
            this.matrix = copy(matrix);
            return this;
        }

        /**
         * Builds the matrix from the layouts by the rules; see {@link ChannelMixerOptions#matrix()}.
         *
         * @return this builder
         */
        public Builder ruleMatrix() {
            this.matrix = null;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if a mix level is not in 0..{@value ChannelMixerOptions#MAX_MIX_LEVEL},
         * or the matrix is empty, not rectangular, or holds a value that is not finite
         */
        public ChannelMixerOptions build() {
            level("center mix level", centerMixLevel);
            level("surround mix level", surroundMixLevel);
            level("low-frequency mix level", lfeMixLevel);
            level("Lt/Rt center mix level", ltrtCenterMixLevel);
            level("Lt/Rt surround mix level", ltrtSurroundMixLevel);
            if (matrix != null) {
                if (matrix.length == 0 || matrix[0].length == 0) {
                    throw new IllegalArgumentException("the mix matrix is empty");
                }
                for (var row : matrix) {
                    if (row.length != matrix[0].length) {
                        throw new IllegalArgumentException("the mix matrix is not rectangular");
                    }
                    for (var value : row) {
                        if (!Double.isFinite(value)) {
                            throw new IllegalArgumentException("mix coefficient " + value + " is not finite");
                        }
                    }
                }
            }
            return new ChannelMixerOptions(normalize, centerMixLevel, surroundMixLevel, lfeMixLevel, stereoDownmix,
                    ltrtCenterMixLevel, ltrtSurroundMixLevel, matrix);
        }

        /**
         * Checks a mix level.
         *
         * @param name the setting, for the message
         * @param value the level
         * @throws IllegalArgumentException if the level is not in range
         */
        private static void level(String name, double value) {
            if (!(value >= 0 && value <= MAX_MIX_LEVEL)) {
                throw new IllegalArgumentException(name + " " + value + " is not in 0.." + MAX_MIX_LEVEL);
            }
        }
    }
}
