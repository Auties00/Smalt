package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.track.Rational;

import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of an {@link RtpDepacketizer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class RtpDepacketizerOptions {
    /**
     * The default number of packets the depacketizer waits for a missing packet.
     */
    public static final int DEFAULT_REORDER_WINDOW = 16;

    private static final RtpDepacketizerOptions DEFAULTS = new Builder().build();

    private final int reorderWindow;
    private final Rational timeBase;
    private final int trackId;
    private final MediaLimits limits;

    private RtpDepacketizerOptions(Builder builder) {
        this.reorderWindow = builder.reorderWindow;
        this.timeBase = builder.timeBase;
        this.trackId = builder.trackId;
        this.limits = builder.limits;
    }

    /**
     * Returns options that wait for {@value #DEFAULT_REORDER_WINDOW} packets, keep RTP clock units, use track 1 and
     * the default limits.
     *
     * @return the default options
     */
    public static RtpDepacketizerOptions defaults() {
        return DEFAULTS;
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
        var builder = new Builder();
        builder.reorderWindow = reorderWindow;
        builder.timeBase = timeBase;
        builder.trackId = trackId;
        builder.limits = limits;
        return builder;
    }

    /**
     * {@return the number of packets, from 0 to 1024, that may arrive after a missing one before it is declared
     * lost; {@value #DEFAULT_REORDER_WINDOW} by default}
     *
     * <p>Packets arriving out of order within the window are put back in sequence order. A larger window tolerates
     * more reordering at the cost of holding output back while a packet is missing; 0 declares a packet lost as
     * soon as a later one arrives.
     */
    public int reorderWindow() {
        return reorderWindow;
    }

    /**
     * {@return the time base of the output packets' timestamps, or empty for the payload format's clock (the
     * default)}
     *
     * <p>Output timestamps count from the first packet's RTP timestamp, which maps to zero.
     */
    public Optional<Rational> timeBase() {
        return Optional.ofNullable(timeBase);
    }

    /**
     * {@return the track identifier the output packets carry; 1 by default}
     */
    public int trackId() {
        return trackId;
    }

    /**
     * {@return the limits on the input; {@link MediaLimits#maxBytes()} bounds the size of one reassembled unit;
     * {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof RtpDepacketizerOptions that && reorderWindow == that.reorderWindow
                && Objects.equals(timeBase, that.timeBase) && trackId == that.trackId && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reorderWindow, timeBase, trackId, limits);
    }

    @Override
    public String toString() {
        return "RtpDepacketizerOptions[reorderWindow=" + reorderWindow + ", timeBase="
                + (timeBase == null ? "clock" : timeBase) + ", trackId=" + trackId + ", limits=" + limits + "]";
    }

    /**
     * Assembles {@link RtpDepacketizerOptions} setting by setting.
     *
     * <p>Each setter validates its value. Instances are not thread-safe.
     */
    public static final class Builder {
        private int reorderWindow = DEFAULT_REORDER_WINDOW;
        private Rational timeBase;
        private int trackId = 1;
        private MediaLimits limits = MediaLimits.DEFAULT;

        private Builder() {
        }

        /**
         * Sets the reordering window; see {@link RtpDepacketizerOptions#reorderWindow()}.
         *
         * @param packets the value
         * @return this builder
         * @throws IllegalArgumentException if {@code packets} is not from 0 to 1024
         */
        public Builder reorderWindow(int packets) {
            if (packets < 0 || packets > 1024) {
                throw new IllegalArgumentException("reorder window " + packets + " is not from 0 to 1024");
            }
            this.reorderWindow = packets;
            return this;
        }

        /**
         * Sets the time base of the output timestamps; see {@link RtpDepacketizerOptions#timeBase()}.
         *
         * @param timeBase the value, with a positive numerator and denominator
         * @return this builder
         * @throws IllegalArgumentException if a term of {@code timeBase} is not positive
         */
        public Builder timeBase(Rational timeBase) {
            if (timeBase.numerator() <= 0 || timeBase.denominator() <= 0) {
                throw new IllegalArgumentException("time base " + timeBase + " is not positive");
            }
            this.timeBase = timeBase;
            return this;
        }

        /**
         * Keeps the output timestamps in RTP clock units; see {@link RtpDepacketizerOptions#timeBase()}.
         *
         * @return this builder
         */
        public Builder clockTimeBase() {
            this.timeBase = null;
            return this;
        }

        /**
         * Sets the track identifier of the output packets; see {@link RtpDepacketizerOptions#trackId()}.
         *
         * @param trackId the value
         * @return this builder
         */
        public Builder trackId(int trackId) {
            this.trackId = trackId;
            return this;
        }

        /**
         * Sets the limits; see {@link RtpDepacketizerOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public RtpDepacketizerOptions build() {
            return new RtpDepacketizerOptions(this);
        }
    }
}
