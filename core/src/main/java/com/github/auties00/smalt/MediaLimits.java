package com.github.auties00.smalt;

import com.github.auties00.smalt.internal.check.Checks;

/**
 * Holds the resource bounds a decoder or demuxer enforces on a single input.
 *
 * <p>Decoders compare every size a header declares against these bounds before allocating, and
 * raise {@link MediaException.LimitExceeded} when one is exceeded. This protects callers that
 * process untrusted input from decompression bombs: a few bytes of header claiming a
 * gigapixel image, millions of frames, or thousands of channels.
 *
 * <p>Instances are immutable and thread-safe; create them with {@link #builder()} or a preset such
 * as {@link #defaults()}.
 */
public final class MediaLimits {
    /**
     * The limits applied when a caller supplies none, the instance {@link #defaults()} returns.
     */
    public static final MediaLimits DEFAULT = new MediaLimits(16_384, 128L * 1024 * 1024, 1_000_000, 64, 1L << 30);

    private final int maxDimension;
    private final long maxPixels;
    private final int maxFrames;
    private final int maxChannels;
    private final long maxBytes;

    private MediaLimits(int maxDimension, long maxPixels, int maxFrames, int maxChannels, long maxBytes) {
        this.maxDimension = maxDimension;
        this.maxPixels = maxPixels;
        this.maxFrames = maxFrames;
        this.maxChannels = maxChannels;
        this.maxBytes = maxBytes;
    }

    /**
     * Returns the limits applied when a caller supplies none, the same instance as {@link #DEFAULT}.
     *
     * @return the default limits
     */
    public static MediaLimits defaults() {
        return DEFAULT;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder(DEFAULT);
    }

    /**
     * Returns a builder initialized with these limits.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the largest accepted width or height of a picture, in pixels; positive; 16384 by
     * default}
     */
    public int maxDimension() {
        return maxDimension;
    }

    /**
     * {@return the largest accepted {@code width * height} of a picture; positive; 128 Mi pixels
     * (134217728) by default}
     */
    public long maxPixels() {
        return maxPixels;
    }

    /**
     * {@return the largest accepted number of frames in an animation or video; positive; 1000000 by
     * default}
     */
    public int maxFrames() {
        return maxFrames;
    }

    /**
     * {@return the largest accepted number of audio channels; positive; 64 by default}
     */
    public int maxChannels() {
        return maxChannels;
    }

    /**
     * {@return the largest single buffer a decoder may allocate for one input, in bytes; positive;
     * 1 GiB by default}
     */
    public long maxBytes() {
        return maxBytes;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MediaLimits that
                && maxDimension == that.maxDimension
                && maxPixels == that.maxPixels
                && maxFrames == that.maxFrames
                && maxChannels == that.maxChannels
                && maxBytes == that.maxBytes;
    }

    @Override
    public int hashCode() {
        var result = Integer.hashCode(maxDimension);
        result = 31 * result + Long.hashCode(maxPixels);
        result = 31 * result + Integer.hashCode(maxFrames);
        result = 31 * result + Integer.hashCode(maxChannels);
        return 31 * result + Long.hashCode(maxBytes);
    }

    @Override
    public String toString() {
        return "MediaLimits[maxDimension=" + maxDimension + ", maxPixels=" + maxPixels + ", maxFrames=" + maxFrames
                + ", maxChannels=" + maxChannels + ", maxBytes=" + maxBytes + "]";
    }

    /**
     * Assembles {@link MediaLimits} bound by bound.
     *
     * <p>Setters store their value unchecked; {@link #build()} checks that every bound is positive.
     * Instances are not thread-safe.
     */
    public static final class Builder {
        private int maxDimension;
        private long maxPixels;
        private int maxFrames;
        private int maxChannels;
        private long maxBytes;

        private Builder(MediaLimits limits) {
            this.maxDimension = limits.maxDimension;
            this.maxPixels = limits.maxPixels;
            this.maxFrames = limits.maxFrames;
            this.maxChannels = limits.maxChannels;
            this.maxBytes = limits.maxBytes;
        }

        /**
         * Sets the largest accepted width or height of a picture; see {@link MediaLimits#maxDimension()}.
         *
         * @param maxDimension the value
         * @return this builder
         */
        public Builder maxDimension(int maxDimension) {
            this.maxDimension = maxDimension;
            return this;
        }

        /**
         * Sets the largest accepted area of a picture; see {@link MediaLimits#maxPixels()}.
         *
         * @param maxPixels the value
         * @return this builder
         */
        public Builder maxPixels(long maxPixels) {
            this.maxPixels = maxPixels;
            return this;
        }

        /**
         * Sets the largest accepted number of frames; see {@link MediaLimits#maxFrames()}.
         *
         * @param maxFrames the value
         * @return this builder
         */
        public Builder maxFrames(int maxFrames) {
            this.maxFrames = maxFrames;
            return this;
        }

        /**
         * Sets the largest accepted number of audio channels; see {@link MediaLimits#maxChannels()}.
         *
         * @param maxChannels the value
         * @return this builder
         */
        public Builder maxChannels(int maxChannels) {
            this.maxChannels = maxChannels;
            return this;
        }

        /**
         * Sets the largest single buffer a decoder may allocate; see {@link MediaLimits#maxBytes()}.
         *
         * @param maxBytes the value
         * @return this builder
         */
        public Builder maxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
            return this;
        }

        /**
         * Builds the limits.
         *
         * @return the limits
         * @throws IllegalArgumentException if a bound is not positive
         */
        public MediaLimits build() {
            return new MediaLimits(
                    Checks.positive("maximum dimension", maxDimension),
                    Checks.positive("maximum pixel count", maxPixels),
                    Checks.positive("maximum frame count", maxFrames),
                    Checks.positive("maximum channel count", maxChannels),
                    Checks.positive("maximum buffer size", maxBytes));
        }
    }
}
