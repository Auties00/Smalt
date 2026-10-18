package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a {@link LoudnessMeter}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class LoudnessMeterOptions {
    private static final LoudnessMeterOptions DEFAULTS = new LoudnessMeterOptions(true, false, null);

    private final boolean truePeak;
    private final boolean dualMono;
    private final double[] channelWeights;

    private LoudnessMeterOptions(boolean truePeak, boolean dualMono, double[] channelWeights) {
        this.truePeak = truePeak;
        this.dualMono = dualMono;
        this.channelWeights = channelWeights;
    }

    /**
     * Returns every measurement on, with the BS.1770-4 channel weights of the meter's layout and
     * mono measured as one channel.
     *
     * @return the default options
     */
    public static LoudnessMeterOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options without the true peak, the most expensive measurement, for
     * meters that only need loudness.
     *
     * @return the options
     */
    public static LoudnessMeterOptions loudnessOnly() {
        return builder().truePeak(false).build();
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
     * {@return whether the meter oversamples the signal to find inter-sample peaks; {@code true} by
     * default}
     */
    public boolean truePeak() {
        return truePeak;
    }

    /**
     * {@return whether a mono signal is measured as dual mono; {@code false} by default}
     *
     * <p>Dual mono is one channel played on two loudspeakers, weighted 2.0 instead of 1.0, so it
     * reads about 3 LU louder.
     */
    public boolean dualMono() {
        return dualMono;
    }

    /**
     * {@return a copy of the explicit channel weights, one non-negative finite weight per channel,
     * or empty to derive them from the meter's layout; empty by default}
     *
     * <p>The weights derived from the layout follow ITU-R BS.1770-4: 1.0 for the front channels,
     * 1.41 (+1.5 dB) for the surround channels and 0 for the low-frequency effects channel (see
     * {@link LoudnessMeter}). Explicit weights replace that mapping; a weight of 0 leaves a channel
     * out.
     */
    public Optional<double[]> channelWeights() {
        return Optional.ofNullable(channelWeights).map(double[]::clone);
    }

    /**
     * Returns the weights stored, not a copy.
     *
     * @return the weights, or {@code null}
     */
    double[] weights() {
        return channelWeights;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LoudnessMeterOptions that && truePeak == that.truePeak && dualMono == that.dualMono
                && Arrays.equals(channelWeights, that.channelWeights);
    }

    @Override
    public int hashCode() {
        return Objects.hash(truePeak, dualMono, Arrays.hashCode(channelWeights));
    }

    @Override
    public String toString() {
        return "LoudnessMeterOptions[truePeak=" + truePeak + ", dualMono=" + dualMono + ", channelWeights="
                + (channelWeights == null ? "layout" : Arrays.toString(channelWeights)) + "]";
    }

    /**
     * Builds {@link LoudnessMeterOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private boolean truePeak;
        private boolean dualMono;
        private double[] channelWeights;

        private Builder(LoudnessMeterOptions options) {
            this.truePeak = options.truePeak;
            this.dualMono = options.dualMono;
            this.channelWeights = options.channelWeights;
        }

        /**
         * Sets whether the true peak is measured; see {@link LoudnessMeterOptions#truePeak()}.
         *
         * @param truePeak the value
         * @return this builder
         */
        public Builder truePeak(boolean truePeak) {
            this.truePeak = truePeak;
            return this;
        }

        /**
         * Sets whether a mono signal is measured as dual mono; see
         * {@link LoudnessMeterOptions#dualMono()}.
         *
         * @param dualMono the value
         * @return this builder
         */
        public Builder dualMono(boolean dualMono) {
            this.dualMono = dualMono;
            return this;
        }

        /**
         * Sets explicit channel weights; see {@link LoudnessMeterOptions#channelWeights()}.
         *
         * @param weights the value, copied, or {@code null} to derive the weights from the layout
         * @return this builder
         */
        public Builder channelWeights(double... weights) {
            this.channelWeights = weights == null ? null : weights.clone();
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if a channel weight is negative or not finite
         */
        public LoudnessMeterOptions build() {
            if (channelWeights != null) {
                for (var weight : channelWeights) {
                    if (Checks.nonNegative("channel weight", weight) == Double.POSITIVE_INFINITY) {
                        throw new IllegalArgumentException("channel weight " + weight + " is not finite");
                    }
                }
            }
            return new LoudnessMeterOptions(truePeak, dualMono, channelWeights == null ? null : channelWeights.clone());
        }
    }
}
