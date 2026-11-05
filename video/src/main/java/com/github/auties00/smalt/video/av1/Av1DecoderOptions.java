package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.video.av1.internal.Av1Context;

import java.util.Objects;

/**
 * Holds the settings of an {@link Av1Decoder}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class Av1DecoderOptions {
    /**
     * The settings of {@link #defaults()}.
     */
    private static final Av1DecoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final int threads;

    private final int maxFrameDelay;

    private final boolean highBitDepth;

    private final int operatingPoint;

    private final boolean allLayers;

    private final boolean annexB;

    private Av1DecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.threads = builder.threads;
        this.maxFrameDelay = builder.maxFrameDelay;
        this.highBitDepth = builder.highBitDepth;
        this.operatingPoint = builder.operatingPoint;
        this.allLayers = builder.allLayers;
        this.annexB = builder.annexB;
    }

    /**
     * Returns the default options: 8-bit pictures of the highest layer of operating point 0, from
     * temporal units of low-overhead OBUs, decoded with the shared helper threads under
     * {@link MediaLimits#DEFAULT}, one frame at a time.
     *
     * @return the options
     */
    public static Av1DecoderOptions defaults() {
        return DEFAULTS;
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
     * {@return the resource bounds the decoder checks every frame size against before allocating;
     * {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the thread count, at least 0; 0 by default}
     *
     * <p>1 decodes on the calling thread only; 0 or a larger value lets the decoder spread the
     * entropy decoding, reconstruction and in-loop filtering of large frames over the module's
     * shared helper threads. The pictures never depend on the thread count.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return the most frames the decoder works on at once, from 1 to 64; 1 by default}
     *
     * <p>1 returns every frame from the call that decodes it, the lowest latency, for real-time
     * streams such as the video of a call. A larger value lets the decoder overlap the decoding of
     * up to that many consecutive frames, which multiplies the throughput on many threads,
     * especially for streams coded as a single tile, at the price of returning each frame up to that
     * many packets minus one later (the rest come from {@link Av1Decoder#flush}). It has no effect
     * with {@link #threads()} 1.
     */
    public int maxFrameDelay() {
        return maxFrameDelay;
    }

    /**
     * {@return whether the frames of 10 and 12-bit streams keep their bit depth; {@code false} by
     * default}
     *
     * <p>When set, such frames are {@link com.github.auties00.smalt.image.Yuv16Picture}s of the
     * stream's bit depth; otherwise they are reduced to 8-bit
     * {@link com.github.auties00.smalt.image.YuvPicture}s.
     */
    public boolean highBitDepth() {
        return highBitDepth;
    }

    /**
     * {@return the operating point decoded, from 0 to 31; 0 by default}
     *
     * <p>A scalable stream declares operating points in its sequence header, each a subset of its
     * temporal and spatial layers; the OBUs of the layers outside the selected one are dropped. An
     * index the sequence header does not declare selects operating point 0.
     */
    public int operatingPoint() {
        return operatingPoint;
    }

    /**
     * {@return whether every spatial layer of a temporal unit is output; {@code false} by default}
     *
     * <p>When set, every frame a temporal unit shows is output, in decoding order, each at its own
     * size; otherwise only the last frame a temporal unit shows, the highest spatial layer of the
     * {@linkplain #operatingPoint() operating point}, is output. Streams without spatial layers show
     * at most one frame per temporal unit either way.
     */
    public boolean allLayers() {
        return allLayers;
    }

    /**
     * {@return whether packets hold temporal units in the length-delimited format of the AV1
     * specification's Annex B; {@code false} by default}
     *
     * <p>When set, a packet holds one {@code temporal_unit_size} field followed by exactly that many
     * bytes of frame units, each preceded by its {@code frame_unit_size} and holding OBUs preceded by
     * their {@code obu_length}, the way an Annex B stream stores a temporal unit and IVF files in
     * that format frame it; an empty packet holds no unit, and a packet with bytes past its unit is
     * malformed. Otherwise a packet holds the low-overhead OBUs of one temporal unit, as MP4,
     * Matroska, WebM and IVF store them. The {@code av1C} record of the track is low-overhead
     * either way.
     */
    public boolean annexB() {
        return annexB;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Av1DecoderOptions that && limits.equals(that.limits) && threads == that.threads
                && maxFrameDelay == that.maxFrameDelay && highBitDepth == that.highBitDepth
                && operatingPoint == that.operatingPoint && allLayers == that.allLayers && annexB == that.annexB;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, threads, maxFrameDelay, highBitDepth, operatingPoint, allLayers, annexB);
    }

    @Override
    public String toString() {
        return "Av1DecoderOptions[limits=" + limits + ", threads=" + threads + ", maxFrameDelay=" + maxFrameDelay
                + ", highBitDepth=" + highBitDepth + ", operatingPoint=" + operatingPoint + ", allLayers="
                + allLayers + ", annexB=" + annexB + "]";
    }

    /**
     * A builder of {@link Av1DecoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits = MediaLimits.DEFAULT;

        private int threads;

        private int maxFrameDelay = 1;

        private boolean highBitDepth;

        private int operatingPoint;

        private boolean allLayers;

        private boolean annexB;

        private Builder() {
        }

        private Builder(Av1DecoderOptions options) {
            this.limits = options.limits;
            this.threads = options.threads;
            this.maxFrameDelay = options.maxFrameDelay;
            this.highBitDepth = options.highBitDepth;
            this.operatingPoint = options.operatingPoint;
            this.allLayers = options.allLayers;
            this.annexB = options.annexB;
        }

        /**
         * Sets the resource bounds; see {@link Av1DecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the thread count; see {@link Av1DecoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets the frame delay; see {@link Av1DecoderOptions#maxFrameDelay()}.
         *
         * @param maxFrameDelay the value
         * @return this builder
         */
        public Builder maxFrameDelay(int maxFrameDelay) {
            this.maxFrameDelay = maxFrameDelay;
            return this;
        }

        /**
         * Sets whether frames deeper than 8 bits keep their depth; see
         * {@link Av1DecoderOptions#highBitDepth()}.
         *
         * @param highBitDepth the value
         * @return this builder
         */
        public Builder highBitDepth(boolean highBitDepth) {
            this.highBitDepth = highBitDepth;
            return this;
        }

        /**
         * Sets the operating point; see {@link Av1DecoderOptions#operatingPoint()}.
         *
         * @param operatingPoint the value
         * @return this builder
         */
        public Builder operatingPoint(int operatingPoint) {
            this.operatingPoint = operatingPoint;
            return this;
        }

        /**
         * Sets whether every spatial layer is output; see {@link Av1DecoderOptions#allLayers()}.
         *
         * @param allLayers the value
         * @return this builder
         */
        public Builder allLayers(boolean allLayers) {
            this.allLayers = allLayers;
            return this;
        }

        /**
         * Sets whether packets are in the length-delimited format; see
         * {@link Av1DecoderOptions#annexB()}.
         *
         * @param annexB the value
         * @return this builder
         */
        public Builder annexB(boolean annexB) {
            this.annexB = annexB;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the thread count is negative, the frame delay is
         * outside 1 to 64 or the operating point is outside 0 to 31
         */
        public Av1DecoderOptions build() {
            Checks.nonNegative("thread count", threads);
            Checks.inRange("frame delay", maxFrameDelay, 1, Av1Context.MAX_FRAME_DELAY);
            Checks.inRange("operating point", operatingPoint, 0, 31);
            return new Av1DecoderOptions(this);
        }
    }
}
