package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.vorbis.internal.CodecSetup;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link VorbisEncoder}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class VorbisEncoderOptions {
    /**
     * The quality of {@link #defaults()}.
     */
    public static final float DEFAULT_QUALITY = 3;

    /**
     * The default {@linkplain #reservoirBias() reservoir bias}.
     */
    public static final double DEFAULT_RESERVOIR_BIAS = 0.1;

    /**
     * The default {@linkplain #averageDamping() average damping}, in seconds.
     */
    public static final double DEFAULT_AVERAGE_DAMPING = 1.5;

    /**
     * The default options: 44.1 kHz stereo at quality 3. They are built without consulting the
     * encoder settings, which are known to cover them.
     */
    private static final VorbisEncoderOptions DEFAULTS = new VorbisEncoderOptions(new Builder());

    private final int sampleRate;

    private final ChannelLayout layout;

    private final float quality;

    /**
     * The nominal bitrate in bits per second, {@code 0} when unset.
     */
    private final int nominalBitrate;

    /**
     * The managed average bitrate in bits per second, {@code 0} when unset.
     */
    private final int averageBitrate;

    /**
     * The hard minimum bitrate in bits per second, {@code 0} when unset.
     */
    private final int minimumBitrate;

    /**
     * The hard maximum bitrate in bits per second, {@code 0} when unset.
     */
    private final int maximumBitrate;

    /**
     * The reservoir size in bits, {@code 0} when automatic.
     */
    private final int reservoirBits;

    /**
     * The preferred fill of the bit reservoir, from 0 to 1.
     */
    private final double reservoirBias;

    /**
     * The average damping in seconds.
     */
    private final double averageDamping;

    private VorbisEncoderOptions(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.layout = builder.layout;
        this.quality = builder.quality;
        this.nominalBitrate = orZero(builder.nominalBitrate);
        this.averageBitrate = orZero(builder.averageBitrate);
        this.minimumBitrate = orZero(builder.minimumBitrate);
        this.maximumBitrate = orZero(builder.maximumBitrate);
        this.reservoirBits = orZero(builder.reservoirBits);
        this.reservoirBias = builder.reservoirBias;
        this.averageDamping = builder.averageDamping;
    }

    /**
     * Returns an optional builder setting as a field value.
     *
     * @param value the value, or {@code null} when unset
     * @return the value, or {@code 0} when unset
     */
    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * Returns quality 3 for 44.1 kHz stereo input.
     *
     * @return the default options
     */
    public static VorbisEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default quality for an input format.
     *
     * @param sampleRate the sampling rate in hertz
     * @param layout the channel layout, 1 to 255 channels
     * @return the options
     * @throws IllegalArgumentException if the format is not codable
     */
    public static VorbisEncoderOptions of(int sampleRate, ChannelLayout layout) {
        return new Builder().format(sampleRate, layout).build();
    }

    /**
     * Returns options at a quality for 44.1 kHz stereo input.
     *
     * @param quality the quality, from -1 (smallest) to 10 (best)
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is out of range
     */
    public static VorbisEncoderOptions ofQuality(float quality) {
        return new Builder().quality(quality).build();
    }

    /**
     * Returns options approximating a nominal bitrate for 44.1 kHz stereo input, without bitrate
     * management.
     *
     * @param bitrate the nominal bitrate in bits per second, from 45 to 500 kbit/s for 44.1 kHz
     * stereo
     * @return the options
     * @throws IllegalArgumentException if no setting reaches the bitrate
     */
    public static VorbisEncoderOptions ofNominalBitrate(int bitrate) {
        return new Builder().nominalBitrate(bitrate).build();
    }

    /**
     * Returns bitrate managed options that hold an average bitrate for 44.1 kHz stereo input.
     *
     * @param bitrate the average bitrate in bits per second, from 45 to 500 kbit/s for 44.1 kHz
     * stereo
     * @return the options
     * @throws IllegalArgumentException if no setting reaches the bitrate
     * @see #averageBitrate()
     */
    public static VorbisEncoderOptions ofAverageBitrate(int bitrate) {
        return new Builder().averageBitrate(bitrate).build();
    }

    /**
     * Returns bitrate managed options that keep the bitrate between two limits for 44.1 kHz stereo
     * input, with the settings chosen for the bitrate halfway between them and no average.
     *
     * @param minimum the hard minimum bitrate in bits per second, positive
     * @param maximum the hard maximum bitrate in bits per second, at least {@code minimum}
     * @return the options
     * @throws IllegalArgumentException if {@code minimum} is above {@code maximum} or no setting
     * reaches the halfway bitrate
     * @see #minimumBitrate()
     * @see #maximumBitrate()
     */
    public static VorbisEncoderOptions ofBitrateLimits(int minimum, int maximum) {
        var halfway = (int) (((long) minimum + maximum) / 2);
        return new Builder().nominalBitrate(halfway).minimumBitrate(minimum).maximumBitrate(maximum).build();
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
     * {@return the input sampling rate in hertz, from 1 to 200000; 44100 by default}
     *
     * <p>Every rate codes at every quality. Settings tuned for the rate exist from 8 to 50 kHz
     * (around 8, 11.025, 16, 22.05, 32, 44.1 and 48 kHz); rates outside that range use generic
     * settings, and a {@linkplain #nominalBitrate() nominal} or {@linkplain #averageBitrate()
     * average} bitrate can be requested only within it.
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the input channel layout, 1 to 255 channels; {@link ChannelLayout#STEREO} by
     * default}
     *
     * <p>Stereo is coupled (lossless and lossy square-polar stereo); other channel counts are coded
     * independently.
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the quality on the {@code oggenc} scale from -1 (smallest) to 10 (best), fractional
     * values included, used when neither a {@link #nominalBitrate()} nor an {@link #averageBitrate()}
     * is set; {@link #DEFAULT_QUALITY}, 3, by default}
     *
     * <table class="striped">
     * <caption>Typical bitrates for 44.1 kHz stereo music</caption>
     * <thead><tr><th scope="col">Quality</th><th scope="col">Nominal bitrate</th></tr></thead>
     * <tbody>
     * <tr><th scope="row">-1</th><td>45 kbit/s</td></tr>
     * <tr><th scope="row">0</th><td>64 kbit/s</td></tr>
     * <tr><th scope="row">3</th><td>112 kbit/s</td></tr>
     * <tr><th scope="row">5</th><td>160 kbit/s</td></tr>
     * <tr><th scope="row">8</th><td>256 kbit/s</td></tr>
     * <tr><th scope="row">10</th><td>500 kbit/s</td></tr>
     * </tbody>
     * </table>
     */
    public float quality() {
        return quality;
    }

    /**
     * {@return the nominal bitrate the quality is chosen from, in bits per second, or empty (the
     * default) when {@link #quality()} or an {@link #averageBitrate()} applies}
     *
     * <p>The encoder picks the quality whose typical bitrate matches the nominal bitrate and codes
     * at that quality, so the real bitrate still follows the signal; the stream is not constrained
     * to the nominal bitrate, only to the {@linkplain #minimumBitrate() minimum} and
     * {@linkplain #maximumBitrate() maximum} when they are set.
     */
    public OptionalInt nominalBitrate() {
        return nominalBitrate == 0 ? OptionalInt.empty() : OptionalInt.of(nominalBitrate);
    }

    /**
     * {@return the average bitrate the stream is held to, in bits per second, or empty (the
     * default) for none}
     *
     * <p>The settings are chosen for this bitrate, and the bitrate management then steers each
     * packet's size so that the bit reservoir drifts back toward its preferred fill: over the long
     * run the stream averages this bitrate, while a stretch of the signal that needs more bits
     * borrows them from the reservoir. How fast it steers is the {@link #averageDamping()}. The
     * average combines with a {@link #minimumBitrate()} and a {@link #maximumBitrate()}, which must
     * bracket it.
     */
    public OptionalInt averageBitrate() {
        return averageBitrate == 0 ? OptionalInt.empty() : OptionalInt.of(averageBitrate);
    }

    /**
     * {@return the hard minimum bitrate in bits per second, or empty (the default) for none}
     *
     * <p>The packets of any stretch of the stream hold at least the minimum bitrate times the
     * stretch's duration, less the {@linkplain #reservoirBits() reservoir}; packets that would code
     * smaller are coded at a higher rate or padded with zero bytes. Each packet counts for half its
     * block, the minimum bitrate's bits of a short block rounded to a whole bit and a long block
     * counting as the short blocks it spans. The minimum applies with any {@link #quality()},
     * {@link #nominalBitrate()} or {@link #averageBitrate()} and is written into the identification
     * header.
     */
    public OptionalInt minimumBitrate() {
        return minimumBitrate == 0 ? OptionalInt.empty() : OptionalInt.of(minimumBitrate);
    }

    /**
     * {@return the hard maximum bitrate in bits per second, or empty (the default) for none}
     *
     * <p>The packets of any stretch of the stream hold at most the maximum bitrate times the
     * stretch's duration, plus the {@linkplain #reservoirBits() reservoir}; packets that would code
     * larger are coded at a lower rate or, when even the lowest rate is too large, truncated, which
     * a decoder reads as a packet whose last coefficients are zero; a packet keeps at least one
     * byte, so a maximum of a few kilobits per second or less can be exceeded. Each packet counts
     * for half its block, as for the {@link #minimumBitrate()}. The maximum applies with any
     * {@link #quality()}, {@link #nominalBitrate()} or {@link #averageBitrate()} and is written
     * into the identification header.
     */
    public OptionalInt maximumBitrate() {
        return maximumBitrate == 0 ? OptionalInt.empty() : OptionalInt.of(maximumBitrate);
    }

    /**
     * {@return the size in bits of the bit reservoir of the bitrate management, or empty (the
     * default) for automatic}
     *
     * <p>The reservoir is the slack the limits allow: a larger one lets short stretches go further
     * above the maximum or below the minimum, and lets the average borrow more. Automatic is two
     * seconds at the nominal bitrate: the {@link #averageBitrate()}, the {@link #nominalBitrate()},
     * or the typical bitrate of the {@link #quality()} (the maximum, or the minimum, when the
     * setting has no typical bitrate). It applies only when {@link #isBitrateManaged()}.
     */
    public OptionalInt reservoirBits() {
        return reservoirBits == 0 ? OptionalInt.empty() : OptionalInt.of(reservoirBits);
    }

    /**
     * {@return the preferred fill of the bit reservoir, from 0 to 1; {@link #DEFAULT_RESERVOIR_BIAS},
     * 0.1, by default}
     *
     * <p>Near 0 the management banks bits against later peaks above the maximum; near 1 it keeps the
     * reservoir full to absorb later drops below the minimum. It applies only when
     * {@link #isBitrateManaged()}.
     */
    public double reservoirBias() {
        return reservoirBias;
    }

    /**
     * {@return the time in seconds the average bitrate tracking takes, at the fastest, to move
     * from the lowest coding rate to the highest; positive, {@link #DEFAULT_AVERAGE_DAMPING}, 1.5, by
     * default}
     *
     * <p>Shorter times follow the {@link #averageBitrate()} more tightly at the cost of quality
     * swings. It applies only with an average bitrate.
     */
    public double averageDamping() {
        return averageDamping;
    }

    /**
     * {@return whether the stream is bitrate managed: whether an {@link #averageBitrate()}, a
     * {@link #minimumBitrate()} or a {@link #maximumBitrate()} is set}
     *
     * <p>A managed stream codes every block at several rates and picks one, so encoding costs
     * several times more than an unmanaged one.
     */
    public boolean isBitrateManaged() {
        return averageBitrate != 0 || minimumBitrate != 0 || maximumBitrate != 0;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof VorbisEncoderOptions that && sampleRate == that.sampleRate
                && layout.equals(that.layout) && Float.compare(quality, that.quality) == 0
                && nominalBitrate == that.nominalBitrate && averageBitrate == that.averageBitrate
                && minimumBitrate == that.minimumBitrate && maximumBitrate == that.maximumBitrate
                && reservoirBits == that.reservoirBits && Double.compare(reservoirBias, that.reservoirBias) == 0
                && Double.compare(averageDamping, that.averageDamping) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, quality, nominalBitrate, averageBitrate, minimumBitrate,
                maximumBitrate, reservoirBits, reservoirBias, averageDamping);
    }

    @Override
    public String toString() {
        var text = new StringBuilder("VorbisEncoderOptions[sampleRate=").append(sampleRate).append(", layout=")
                .append(layout).append(", ");
        if (averageBitrate != 0) {
            text.append("averageBitrate=").append(averageBitrate);
        } else if (nominalBitrate != 0) {
            text.append("nominalBitrate=").append(nominalBitrate);
        } else {
            text.append("quality=").append(quality);
        }
        if (minimumBitrate != 0) {
            text.append(", minimumBitrate=").append(minimumBitrate);
        }
        if (maximumBitrate != 0) {
            text.append(", maximumBitrate=").append(maximumBitrate);
        }
        if (isBitrateManaged()) {
            text.append(", reservoirBits=").append(reservoirBits == 0 ? "automatic" : reservoirBits)
                    .append(", reservoirBias=").append(reservoirBias);
            if (averageBitrate != 0) {
                text.append(", averageDamping=").append(averageDamping);
            }
        }
        return text.append(']').toString();
    }

    /**
     * Returns the encoder setup of these options.
     *
     * @return the setup
     * @throws IllegalArgumentException if no encoder setting covers the options
     */
    CodecSetup codecSetup() {
        var channels = layout.channels();
        if (!isBitrateManaged()) {
            return nominalBitrate != 0
                    ? CodecSetup.nominalBitrate(channels, sampleRate, nominalBitrate)
                    : CodecSetup.variableBitrate(channels, sampleRate, quality / 10);
        }
        if (averageBitrate != 0) {
            return CodecSetup.managedBitrate(channels, sampleRate, averageBitrate, averageBitrate, minimumBitrate,
                    maximumBitrate, reservoirBits, reservoirBias, averageDamping);
        }
        if (nominalBitrate != 0) {
            return CodecSetup.managedBitrate(channels, sampleRate, nominalBitrate, 0, minimumBitrate,
                    maximumBitrate, reservoirBits, reservoirBias, averageDamping);
        }
        return CodecSetup.managedQuality(channels, sampleRate, quality / 10, minimumBitrate, maximumBitrate,
                reservoirBits, reservoirBias, averageDamping);
    }

    /**
     * A builder of {@link VorbisEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and that an encoder
     * setting covers the result. Instances are not thread-safe.
     */
    public static final class Builder {
        private int sampleRate = 44_100;

        private ChannelLayout layout = ChannelLayout.STEREO;

        private float quality = DEFAULT_QUALITY;

        /**
         * The nominal bitrate, or {@code null} when unset.
         */
        private Integer nominalBitrate;

        /**
         * The average bitrate, or {@code null} when unset.
         */
        private Integer averageBitrate;

        /**
         * The minimum bitrate, or {@code null} when unset.
         */
        private Integer minimumBitrate;

        /**
         * The maximum bitrate, or {@code null} when unset.
         */
        private Integer maximumBitrate;

        /**
         * The reservoir size, or {@code null} when automatic.
         */
        private Integer reservoirBits;

        /**
         * The preferred fill of the bit reservoir.
         */
        private double reservoirBias = DEFAULT_RESERVOIR_BIAS;

        /**
         * The average damping in seconds.
         */
        private double averageDamping = DEFAULT_AVERAGE_DAMPING;

        private Builder() {
        }

        private Builder(VorbisEncoderOptions options) {
            this.sampleRate = options.sampleRate;
            this.layout = options.layout;
            this.quality = options.quality;
            this.nominalBitrate = orNull(options.nominalBitrate);
            this.averageBitrate = orNull(options.averageBitrate);
            this.minimumBitrate = orNull(options.minimumBitrate);
            this.maximumBitrate = orNull(options.maximumBitrate);
            this.reservoirBits = orNull(options.reservoirBits);
            this.reservoirBias = options.reservoirBias;
            this.averageDamping = options.averageDamping;
        }

        /**
         * Sets the input format; see {@link VorbisEncoderOptions#sampleRate()} and
         * {@link VorbisEncoderOptions#layout()}.
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
         * Sets the quality and clears any nominal or average bitrate; see
         * {@link VorbisEncoderOptions#quality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder quality(float quality) {
            this.quality = quality;
            this.nominalBitrate = null;
            this.averageBitrate = null;
            return this;
        }

        /**
         * Sets the nominal bitrate the quality is chosen from and clears any average bitrate; see
         * {@link VorbisEncoderOptions#nominalBitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder nominalBitrate(int bitrate) {
            this.nominalBitrate = bitrate;
            this.averageBitrate = null;
            return this;
        }

        /**
         * Sets the average bitrate and clears any nominal bitrate; see
         * {@link VorbisEncoderOptions#averageBitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder averageBitrate(int bitrate) {
            this.averageBitrate = bitrate;
            this.nominalBitrate = null;
            return this;
        }

        /**
         * Sets the hard minimum bitrate; see {@link VorbisEncoderOptions#minimumBitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder minimumBitrate(int bitrate) {
            this.minimumBitrate = bitrate;
            return this;
        }

        /**
         * Removes the hard minimum bitrate; see {@link VorbisEncoderOptions#minimumBitrate()}.
         *
         * @return this builder
         */
        public Builder noMinimumBitrate() {
            this.minimumBitrate = null;
            return this;
        }

        /**
         * Sets the hard maximum bitrate; see {@link VorbisEncoderOptions#maximumBitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder maximumBitrate(int bitrate) {
            this.maximumBitrate = bitrate;
            return this;
        }

        /**
         * Removes the hard maximum bitrate; see {@link VorbisEncoderOptions#maximumBitrate()}.
         *
         * @return this builder
         */
        public Builder noMaximumBitrate() {
            this.maximumBitrate = null;
            return this;
        }

        /**
         * Sets the size of the bit reservoir; see {@link VorbisEncoderOptions#reservoirBits()}.
         *
         * @param bits the value
         * @return this builder
         */
        public Builder reservoirBits(int bits) {
            this.reservoirBits = bits;
            return this;
        }

        /**
         * Sizes the bit reservoir automatically; see {@link VorbisEncoderOptions#reservoirBits()}.
         *
         * @return this builder
         */
        public Builder automaticReservoirBits() {
            this.reservoirBits = null;
            return this;
        }

        /**
         * Sets the preferred fill of the bit reservoir; see
         * {@link VorbisEncoderOptions#reservoirBias()}.
         *
         * @param bias the value
         * @return this builder
         */
        public Builder reservoirBias(double bias) {
            this.reservoirBias = bias;
            return this;
        }

        /**
         * Sets the average damping; see {@link VorbisEncoderOptions#averageDamping()}.
         *
         * @param seconds the value
         * @return this builder
         */
        public Builder averageDamping(double seconds) {
            this.averageDamping = seconds;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sampling rate is outside 1 to 200000 Hz, the layout
         * has more than 255 channels, the quality is outside -1 to 10 or NaN, a bitrate or the reservoir
         * size is not positive, the minimum bitrate is above the maximum or the average outside them,
         * the reservoir bias is outside 0 to 1, the average damping is not a positive finite number, or
         * no encoder setting covers the format with the quality, nominal or average bitrate
         */
        public VorbisEncoderOptions build() {
            Checks.inRange("sample rate", sampleRate, 1, 200_000);
            var channels = Checks.inRange("channel count", layout.channels(), 1, 255);
            Checks.inRange("quality", quality, -1.0, 10.0);
            var nominalBitrate = positive("nominal bitrate", this.nominalBitrate);
            var averageBitrate = positive("average bitrate", this.averageBitrate);
            var minimumBitrate = positive("minimum bitrate", this.minimumBitrate);
            var maximumBitrate = positive("maximum bitrate", this.maximumBitrate);
            positive("reservoir size", reservoirBits);
            if (minimumBitrate != 0 && maximumBitrate != 0 && minimumBitrate > maximumBitrate) {
                throw new IllegalArgumentException("minimum bitrate " + minimumBitrate + " is above the maximum "
                        + maximumBitrate);
            }
            if (averageBitrate != 0 && minimumBitrate > averageBitrate) {
                throw new IllegalArgumentException("minimum bitrate " + minimumBitrate + " is above the average "
                        + averageBitrate);
            }
            if (averageBitrate != 0 && maximumBitrate != 0 && maximumBitrate < averageBitrate) {
                throw new IllegalArgumentException("maximum bitrate " + maximumBitrate + " is below the average "
                        + averageBitrate);
            }
            Checks.inRange("reservoir bias", reservoirBias, 0.0, 1.0);
            if (!(averageDamping > 0) || averageDamping == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("average damping " + averageDamping
                        + " is not a positive finite number of seconds");
            }
            var bitrate = averageBitrate != 0 ? averageBitrate : nominalBitrate;
            if (bitrate != 0) {
                if (!CodecSetup.supportsBitrate(channels, sampleRate, bitrate)) {
                    throw new IllegalArgumentException("no Vorbis setting reaches " + bitrate + " bit/s for "
                            + channels + " channels at " + sampleRate + " Hz");
                }
            } else if (!CodecSetup.supportsQuality(channels, sampleRate, quality / 10)) {
                throw new IllegalArgumentException("no Vorbis setting covers quality " + quality + " for " + channels
                        + " channels at " + sampleRate + " Hz");
            }
            return new VorbisEncoderOptions(this);
        }

        /**
         * Checks that an optional setting is positive.
         *
         * @param name the setting's name
         * @param value the value, or {@code null} when unset
         * @return the value, or {@code 0} when unset
         * @throws IllegalArgumentException if the value is not positive
         */
        private static int positive(String name, Integer value) {
            return value == null ? 0 : Checks.positive(name, value);
        }

        /**
         * Returns an optional setting of the options as a builder field.
         *
         * @param value the value, {@code 0} when unset
         * @return the value, or {@code null} when unset
         */
        private static Integer orNull(int value) {
            return value == 0 ? null : value;
        }
    }
}
