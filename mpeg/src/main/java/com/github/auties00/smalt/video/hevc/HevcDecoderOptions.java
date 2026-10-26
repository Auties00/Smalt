package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link HevcDecoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class HevcDecoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final HevcDecoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final int threads;

    private final boolean highBitDepth;

    private final boolean hashVerification;

    private final boolean defaultDisplayWindow;

    private final boolean filmGrain;

    private final int outputLayerSet;

    private final boolean alpha;

    private final boolean dolbyVisionBaseLayer;

    private HevcDecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.threads = builder.threads;
        this.highBitDepth = builder.highBitDepth;
        this.hashVerification = builder.hashVerification;
        this.defaultDisplayWindow = builder.defaultDisplayWindow;
        this.filmGrain = builder.filmGrain;
        this.outputLayerSet = builder.lastOutputLayerSet ? -1 : builder.outputLayerSet;
        this.alpha = builder.alpha;
        this.dolbyVisionBaseLayer = builder.dolbyVisionBaseLayer;
    }

    /**
     * Returns the default options: {@link MediaLimits#defaults()}, the shared pool, 8-bit output, no
     * hash verification, output cropped by the conformance window only, film grain added, the base
     * layer of a multi-layer stream, its alpha attached, and Dolby Vision streams without a backward compatible
     * base layer rejected.
     *
     * @return the default options
     */
    public static HevcDecoderOptions defaults() {
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
     * {@return the resource bounds the decoder enforces; {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the thread count, zero or more: 0 lets the decoder use the module's shared pool of
     * helper threads (sized by the {@code smalt.threads} system property), 1 keeps the work on the
     * calling thread, and a larger value allows up to that many threads; 0 by default}
     *
     * <p>The output never depends on the thread count.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return whether the pictures of streams deeper than 8 bits are handed out at their own depth
     * as {@link Yuv16Picture}s rather than rounded to 8-bit {@link YuvPicture}s; {@code false} by
     * default}
     */
    public boolean highBitDepth() {
        return highBitDepth;
    }

    /**
     * {@return whether each picture that a decoded picture hash SEI message describes is checked
     * against it, a picture that does not match raising {@link MediaException.Malformed};
     * {@code false} by default}
     */
    public boolean hashVerification() {
        return hashVerification;
    }

    /**
     * {@return whether the pictures are also cropped by the default display window the VUI
     * signals, inside the conformance window; {@code false} by default}
     *
     * <p>The default display window is a display hint (ITU-T H.265 E.3.1): the conformance window
     * alone gives the decoded pictures. A default display window that would leave no picture is
     * ignored.
     */
    public boolean defaultDisplayWindow() {
        return defaultDisplayWindow;
    }

    /**
     * {@return whether the decoder adds the film grain that film grain characteristics SEI
     * messages describe to the pictures; {@code true} by default}
     *
     * <p>When set, the grain of the frequency filtering model with additive blending is rendered
     * as {@link HevcDecoder} describes; when clear, frames hold the decoded samples as they are.
     * Decoded picture hashes are always checked on the samples without grain.
     */
    public boolean filmGrain() {
        return filmGrain;
    }

    /**
     * {@return the index of the output layer set of a multi-layer stream to decode, or empty for
     * the last one the stream defines; 0 by default}
     *
     * <p>The output layer sets of a multi-layer stream (multiview, scalable or with auxiliary
     * layers, ITU-T H.265 Annex F) are listed by its video parameter set; this index is the
     * specification's {@code TargetOlsIdx}. Output layer set 0 is the base layer alone, which is
     * all a stream with one layer has; the last one commonly holds every layer, all views of a
     * multiview stream or the highest quality layer of a scalable one. The decoder decodes the
     * layers the set needs and outputs those it names as output layers, each frame naming its
     * layer in {@link com.github.auties00.smalt.video.FrameSideData#layer()} when more than the
     * base layer is output. An index past the last output layer set selects the last one, as does
     * index 0 for a stream whose base layer is coded outside it.
     */
    public OptionalInt outputLayerSet() {
        return outputLayerSet < 0 ? OptionalInt.empty() : OptionalInt.of(outputLayerSet);
    }

    /**
     * {@return whether the alpha auxiliary layer of an output layer is decoded and attached to the
     * layer's frames as their alpha plane; {@code true} by default}
     *
     * <p>An alpha layer ({@code AuxId} 1, the HEVC-with-alpha streams of QuickTime and MP4 files)
     * is associated with the primary layer whose scalability identifiers it shares. When set, its
     * pictures are not output as frames of their own: each becomes the straight alpha plane of the
     * frame of its access unit, as the alpha channel information SEI message interprets its
     * samples (or full scale, as the alpha layer's range says, without one), resized to the frame
     * when its size differs. Color premultiplied by the alpha is divided back into straight-alpha
     * RGBA frames. When clear, alpha layers are decoded only when the output layer set names them,
     * and then output as monochrome frames of their own.
     */
    public boolean alpha() {
        return alpha;
    }

    /**
     * {@return whether a Dolby Vision stream whose base layer is not backward compatible decodes to its base layer
     * pictures; {@code false} by default}
     *
     * <p>When clear, {@link HevcDecoder} rejects a track whose
     * {@linkplain com.github.auties00.smalt.track.VideoTrack#dolbyVision() Dolby Vision configuration} is not
     * {@linkplain com.github.auties00.smalt.track.DolbyVision#isBackwardCompatible() backward compatible} and a
     * single-layer stream whose first valid reference processing unit (NAL unit type 62) is of profile 5, with
     * {@link MediaException.Unsupported}: the base layer of such a stream is coded in a Dolby Vision specific color
     * space and shows wrong colors as plain video. When set, the base layer is decoded as plain HEVC and the
     * reference processing units are ignored, as they are for every backward compatible stream.
     */
    public boolean dolbyVisionBaseLayer() {
        return dolbyVisionBaseLayer;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HevcDecoderOptions that && limits.equals(that.limits) && threads == that.threads
                && highBitDepth == that.highBitDepth && hashVerification == that.hashVerification
                && defaultDisplayWindow == that.defaultDisplayWindow && filmGrain == that.filmGrain
                && outputLayerSet == that.outputLayerSet && alpha == that.alpha
                && dolbyVisionBaseLayer == that.dolbyVisionBaseLayer;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, threads, highBitDepth, hashVerification, defaultDisplayWindow, filmGrain,
                outputLayerSet, alpha, dolbyVisionBaseLayer);
    }

    @Override
    public String toString() {
        return "HevcDecoderOptions[limits=" + limits + ", threads=" + threads + ", highBitDepth=" + highBitDepth
                + ", hashVerification=" + hashVerification + ", defaultDisplayWindow=" + defaultDisplayWindow
                + ", filmGrain=" + filmGrain + ", outputLayerSet=" + outputLayerSet() + ", alpha=" + alpha
                + ", dolbyVisionBaseLayer=" + dolbyVisionBaseLayer + "]";
    }

    /**
     * A builder of {@link HevcDecoderOptions}, starting from {@link HevcDecoderOptions#defaults()}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. A builder may build
     * repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;

        private int threads;

        private boolean highBitDepth;

        private boolean hashVerification;

        private boolean defaultDisplayWindow;

        private boolean filmGrain;

        private int outputLayerSet;

        private boolean lastOutputLayerSet;

        private boolean alpha;

        private boolean dolbyVisionBaseLayer;

        private Builder() {
            this.limits = MediaLimits.DEFAULT;
            this.filmGrain = true;
            this.alpha = true;
        }

        private Builder(HevcDecoderOptions options) {
            this.limits = options.limits;
            this.threads = options.threads;
            this.highBitDepth = options.highBitDepth;
            this.hashVerification = options.hashVerification;
            this.defaultDisplayWindow = options.defaultDisplayWindow;
            this.filmGrain = options.filmGrain;
            this.outputLayerSet = Math.max(0, options.outputLayerSet);
            this.lastOutputLayerSet = options.outputLayerSet < 0;
            this.alpha = options.alpha;
            this.dolbyVisionBaseLayer = options.dolbyVisionBaseLayer;
        }

        /**
         * Sets the resource bounds; see {@link HevcDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the thread count; see {@link HevcDecoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets whether pictures deeper than 8 bits keep their depth; see
         * {@link HevcDecoderOptions#highBitDepth()}.
         *
         * @param highBitDepth the value
         * @return this builder
         */
        public Builder highBitDepth(boolean highBitDepth) {
            this.highBitDepth = highBitDepth;
            return this;
        }

        /**
         * Sets whether decoded picture hashes are verified; see
         * {@link HevcDecoderOptions#hashVerification()}.
         *
         * @param hashVerification the value
         * @return this builder
         */
        public Builder hashVerification(boolean hashVerification) {
            this.hashVerification = hashVerification;
            return this;
        }

        /**
         * Sets whether the pictures are also cropped by the default display window; see
         * {@link HevcDecoderOptions#defaultDisplayWindow()}.
         *
         * @param defaultDisplayWindow the value
         * @return this builder
         */
        public Builder defaultDisplayWindow(boolean defaultDisplayWindow) {
            this.defaultDisplayWindow = defaultDisplayWindow;
            return this;
        }

        /**
         * Sets whether the decoder adds film grain to the pictures; see
         * {@link HevcDecoderOptions#filmGrain()}.
         *
         * @param filmGrain the value
         * @return this builder
         */
        public Builder filmGrain(boolean filmGrain) {
            this.filmGrain = filmGrain;
            return this;
        }

        /**
         * Sets the output layer set to decode, replacing a {@link #lastOutputLayerSet()}
         * selection; see {@link HevcDecoderOptions#outputLayerSet()}.
         *
         * @param index the output layer set index, 0 for the base layer's; {@link #build()} rejects
         * a negative one
         * @return this builder
         */
        public Builder outputLayerSet(int index) {
            this.outputLayerSet = index;
            this.lastOutputLayerSet = false;
            return this;
        }

        /**
         * Selects the last output layer set the stream defines; see
         * {@link HevcDecoderOptions#outputLayerSet()}.
         *
         * @return this builder
         */
        public Builder lastOutputLayerSet() {
            this.lastOutputLayerSet = true;
            return this;
        }

        /**
         * Sets whether alpha layers are attached to their frames; see
         * {@link HevcDecoderOptions#alpha()}.
         *
         * @param alpha whether the alpha auxiliary layers are decoded and attached as alpha planes
         * @return this builder
         */
        public Builder alpha(boolean alpha) {
            this.alpha = alpha;
            return this;
        }

        /**
         * Sets whether a Dolby Vision stream without a backward compatible base layer decodes to its base layer;
         * see {@link HevcDecoderOptions#dolbyVisionBaseLayer()}.
         *
         * @param dolbyVisionBaseLayer the value
         * @return this builder
         */
        public Builder dolbyVisionBaseLayer(boolean dolbyVisionBaseLayer) {
            this.dolbyVisionBaseLayer = dolbyVisionBaseLayer;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the thread count or the output layer set index is
         * negative
         */
        public HevcDecoderOptions build() {
            Checks.nonNegative("thread count", threads);
            if (!lastOutputLayerSet) {
                Checks.nonNegative("output layer set index", outputLayerSet);
            }
            return new HevcDecoderOptions(this);
        }
    }
}
