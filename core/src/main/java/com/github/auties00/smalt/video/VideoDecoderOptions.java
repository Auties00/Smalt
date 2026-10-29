package com.github.auties00.smalt.video;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of a video decoder created by {@link VideoDecoders}. Instances are immutable
 * and thread-safe; create them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class VideoDecoderOptions {
    private static final VideoDecoderOptions DEFAULTS = new VideoDecoderOptions(MediaLimits.DEFAULT, 0, false, false);

    private final MediaLimits limits;
    private final int threads;
    private final boolean highBitDepth;
    private final boolean dolbyVisionBaseLayer;

    private VideoDecoderOptions(MediaLimits limits, int threads, boolean highBitDepth, boolean dolbyVisionBaseLayer) {
        this.limits = limits;
        this.threads = threads;
        this.highBitDepth = highBitDepth;
        this.dolbyVisionBaseLayer = dolbyVisionBaseLayer;
    }

    /**
     * Returns decoding to 8-bit pictures on the shared pool, under {@link MediaLimits#DEFAULT}.
     *
     * @return the default options
     */
    public static VideoDecoderOptions defaults() {
        return DEFAULTS;
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
     * {@return the resource bounds the decoder enforces; {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the thread count, at least 0; 0 by default}
     *
     * <p>The count follows the module's per-operation convention: 0 lets the decoder use the
     * module's shared pool of helper threads (sized by the {@code smalt.threads} system property),
     * 1 keeps the work on the calling thread, and a larger value allows up to that many threads.
     * The output never depends on the thread count.
     *
     * @implNote This implementation honors the thread count in the H.264 decoder, which decodes up
     * to that many pictures at once when it is above 1 and one picture at a time otherwise. The VP8,
     * VP9 and AV1 decoders decode on the calling thread when it is 1 and spread the work of each
     * frame over the shared pool otherwise.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return whether the pictures of streams deeper than 8 bits keep their depth; {@code false} by
     * default}
     *
     * <p>When {@code true}, such pictures are {@link com.github.auties00.smalt.image.Yuv16Picture}s
     * of the stream's depth; when {@code false}, every picture is reduced to an 8-bit
     * {@link com.github.auties00.smalt.image.YuvPicture}. Codecs without a high bit depth output
     * ignore it.
     *
     * @implNote This implementation honors the setting in the VP9, AV1, HEVC, H.264 and Motion-JPEG
     * decoders.
     */
    public boolean highBitDepth() {
        return highBitDepth;
    }

    /**
     * {@return whether a Dolby Vision stream whose base layer is not backward compatible decodes to its base layer
     * pictures; {@code false} by default}
     *
     * <p>The base layer of such a stream (profile 5, or any
     * {@linkplain com.github.auties00.smalt.track.DolbyVision#isBackwardCompatible() configuration} with
     * compatibility identifier 0) is coded in a Dolby Vision specific color space, so its pictures show wrong
     * colors without the reconstruction its reference processing units describe. When {@code false},
     * {@link VideoDecoders#create(com.github.auties00.smalt.track.VideoTrack, VideoDecoderOptions)} rejects a track
     * that declares such a configuration and the HEVC decoder rejects a single-layer stream whose first reference
     * processing unit is of profile 5, both with {@link com.github.auties00.smalt.MediaException.Unsupported}; when
     * {@code true}, they decode the base layer as plain video, its pictures tagged with the color description of
     * the bitstream.
     */
    public boolean dolbyVisionBaseLayer() {
        return dolbyVisionBaseLayer;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VideoDecoderOptions that && limits.equals(that.limits) && threads == that.threads
                && highBitDepth == that.highBitDepth && dolbyVisionBaseLayer == that.dolbyVisionBaseLayer;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, threads, highBitDepth, dolbyVisionBaseLayer);
    }

    @Override
    public String toString() {
        return "VideoDecoderOptions[limits=" + limits + ", threads=" + threads + ", highBitDepth=" + highBitDepth
                + ", dolbyVisionBaseLayer=" + dolbyVisionBaseLayer + "]";
    }

    /**
     * Builds {@link VideoDecoderOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;
        private int threads;
        private boolean highBitDepth;
        private boolean dolbyVisionBaseLayer;

        private Builder(VideoDecoderOptions options) {
            this.limits = options.limits;
            this.threads = options.threads;
            this.highBitDepth = options.highBitDepth;
            this.dolbyVisionBaseLayer = options.dolbyVisionBaseLayer;
        }

        /**
         * Sets the resource bounds; see {@link VideoDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the thread count; see {@link VideoDecoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets whether high-bit-depth pictures keep their depth; see
         * {@link VideoDecoderOptions#highBitDepth()}.
         *
         * @param highBitDepth the value
         * @return this builder
         */
        public Builder highBitDepth(boolean highBitDepth) {
            this.highBitDepth = highBitDepth;
            return this;
        }

        /**
         * Sets whether a Dolby Vision stream without a backward compatible base layer decodes to its base layer;
         * see {@link VideoDecoderOptions#dolbyVisionBaseLayer()}.
         *
         * @param dolbyVisionBaseLayer the value
         * @return this builder
         */
        public Builder dolbyVisionBaseLayer(boolean dolbyVisionBaseLayer) {
            this.dolbyVisionBaseLayer = dolbyVisionBaseLayer;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the thread count is negative
         */
        public VideoDecoderOptions build() {
            Checks.nonNegative("thread count", threads);
            return new VideoDecoderOptions(limits, threads, highBitDepth, dolbyVisionBaseLayer);
        }
    }
}
