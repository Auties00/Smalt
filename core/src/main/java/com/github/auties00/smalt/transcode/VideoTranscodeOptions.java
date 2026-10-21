package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.resize.SizePlan;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.TrackKind;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of what a transcode does with the video stream. Instances are immutable and
 * thread-safe; create them with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>An encoded stream goes through these operations, in this order:
 * <ol>
 *   <li>crop ({@link #crop()}), in displayed coordinates;</li>
 *   <li>orientation ({@link #orientation()}): the pixels are turned upright, or kept as coded with
 *       the rotation recorded in the output;</li>
 *   <li>sample aspect ratio ({@link #sampleAspect()}): non-square samples are resampled to square
 *       ones, or kept with the ratio recorded in the output;</li>
 *   <li>scaling to a box ({@link #width()}, {@link #height()}, {@link #scaleMode()},
 *       {@link #upscale()}), then to the edge limits ({@link #maxLongEdge()},
 *       {@link #maxShortEdge()}), rounded to even dimensions when {@link #evenDimensions()} is
 *       set, resampled with {@link #filter()};</li>
 *   <li>frame-rate conversion ({@link #frameRate()}, {@link #maxFrameRate()}) by dropping and
 *       duplicating frames;</li>
 *   <li>pixel-format conversion ({@link #chromaFormat()}, {@link #color()}) of the pictures the
 *       encoder receives; pictures with transparency (animated images) are first flattened on
 *       {@link #background()}.</li>
 * </ol>
 * The encoder is the one {@link com.github.auties00.smalt.video.VideoEncoders} finds for
 * {@link #codec()}, configured through {@link com.github.auties00.smalt.video.VideoEncoderOptions}
 * with {@link #bitrate()}, {@link #threads()}, {@link #keyframeInterval()} and
 * {@link #periodicKeyframes()}.
 */
public final class VideoTranscodeOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final VideoTranscodeOptions DEFAULTS = new Builder().build();

    private final StreamMode mode;

    /**
     * The identifier of the selected track, or {@code -1} for the first video track.
     */
    private final int trackId;

    /**
     * The codec to produce, or {@code null} to let the container choose.
     */
    private final Codec codec;

    /**
     * The target bitrate in bits per second, or {@code 0} for the default.
     */
    private final int bitrate;

    private final int threads;

    /**
     * The key frame distance in frames, or {@code 0} to let the codec choose or when periodic key
     * frames are off.
     */
    private final int keyframeInterval;

    private final boolean periodicKeyframes;

    private final ResizeSettings resize;
    private final boolean evenDimensions;

    private final OrientationMode orientation;

    /**
     * How non-square samples are handled.
     */
    private final SampleAspectMode sampleAspect;

    /**
     * The constant output frame rate, or {@code null} to keep the source timing.
     */
    private final Rational frameRate;

    /**
     * The highest output frame rate, or {@code null} for no limit.
     */
    private final Rational maxFrameRate;

    /**
     * The chroma format of the encoded pictures, or {@code null} to let the encoder choose.
     */
    private final ChromaFormat chromaFormat;

    /**
     * The color description of the encoded pictures, or {@code null} to keep the source's.
     */
    private final ColorInfo color;

    /**
     * The background transparent pixels are flattened on, as {@code 0xRRGGBB}.
     */
    private final int background;

    private VideoTranscodeOptions(Builder builder, int trackId, int bitrate, int keyframeInterval,
                                  ResizeSettings resize) {
        this.mode = builder.mode;
        this.trackId = trackId;
        this.codec = builder.codec;
        this.bitrate = bitrate;
        this.threads = builder.threads;
        this.keyframeInterval = keyframeInterval;
        this.periodicKeyframes = builder.periodicKeyframes;
        this.resize = resize;
        this.evenDimensions = builder.evenDimensions;
        this.orientation = builder.orientation;
        this.sampleAspect = builder.sampleAspect;
        this.frameRate = builder.frameRate;
        this.maxFrameRate = builder.maxFrameRate;
        this.chromaFormat = builder.chromaFormat;
        this.color = builder.color;
        this.background = builder.background;
    }

    /**
     * Returns the default options: the first video track, copied when possible and encoded
     * otherwise, at its size, rate and orientation.
     *
     * @return the options
     */
    public static VideoTranscodeOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options that copy the first video track unchanged.
     *
     * @return the options
     */
    public static VideoTranscodeOptions copy() {
        return DEFAULTS.toBuilder().mode(StreamMode.COPY).build();
    }

    /**
     * Returns options that encode the first video track with a codec.
     *
     * @param codec the codec to produce
     * @return the options
     * @throws IllegalArgumentException if {@code codec} is not a video codec
     */
    public static VideoTranscodeOptions encode(Codec codec) {
        return new Builder().mode(StreamMode.ENCODE).codec(codec).build();
    }

    /**
     * Returns options that leave video out of the output.
     *
     * @return the options
     */
    public static VideoTranscodeOptions drop() {
        return DEFAULTS.toBuilder().mode(StreamMode.DROP).build();
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
     * {@return whether the stream is copied, encoded or dropped; {@link StreamMode#AUTO} by default}
     */
    public StreamMode mode() {
        return mode;
    }

    /**
     * {@return the identifier of the selected track, zero or more, or empty to select the first video
     * track; empty by default}
     */
    public OptionalInt trackId() {
        return trackId < 0 ? OptionalInt.empty() : OptionalInt.of(trackId);
    }

    /**
     * {@return the video codec to produce, or empty to keep the source codec when it can be copied and
     * otherwise use the first encodable codec of {@link OutputContainer#videoCodecs()}; empty by
     * default}
     */
    public Optional<Codec> codec() {
        return Optional.ofNullable(codec);
    }

    /**
     * {@return the target bitrate of an encoded stream, in bits per second, positive, or empty for the
     * default of about 0.1 bit per pixel and frame; empty by default}
     */
    public OptionalInt bitrate() {
        return bitrate == 0 ? OptionalInt.empty() : OptionalInt.of(bitrate);
    }

    /**
     * {@return the encoder thread count, zero or more; 0 by default}
     *
     * <p>0 uses the encoder's default share of the module's helper threads, 1 keeps the work on the
     * calling thread, and a larger value allows up to that many threads.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return the most frames from one key frame to the next in an encoded stream, positive, or empty
     * when the encoder chooses the distance or {@link #periodicKeyframes()} is {@code false}; empty by
     * default}
     *
     * <p>The interval counts output frames, after any frame-rate conversion; it has the meaning of
     * {@link com.github.auties00.smalt.video.VideoEncoderOptions#keyframeInterval()}. A copied stream
     * keeps its own key frames.
     */
    public OptionalInt keyframeInterval() {
        return keyframeInterval == 0 ? OptionalInt.empty() : OptionalInt.of(keyframeInterval);
    }

    /**
     * {@return whether an encoded stream may have periodic key frames; {@code true} by default}
     *
     * <p>When {@code false}, the first frame and the scene cuts the encoder detects are the only key
     * frames; when {@code true}, their distance is {@link #keyframeInterval()}, or the encoder's
     * default when that is empty.
     */
    public boolean periodicKeyframes() {
        return periodicKeyframes;
    }

    /**
     * {@return the width of the target box, in pixels, positive, or empty when free; empty by default}
     */
    public OptionalInt width() {
        return resize.width() == 0 ? OptionalInt.empty() : OptionalInt.of(resize.width());
    }

    /**
     * {@return the height of the target box, in pixels, positive, or empty when free; empty by default}
     */
    public OptionalInt height() {
        return resize.height() == 0 ? OptionalInt.empty() : OptionalInt.of(resize.height());
    }

    /**
     * {@return how the picture is fitted to the target box; {@link ScaleMode#FIT} by default}
     */
    public ScaleMode scaleMode() {
        return resize.scaleMode();
    }

    /**
     * {@return whether {@link ScaleMode#FIT} may enlarge a picture smaller than the box; {@code false}
     * by default}
     */
    public boolean upscale() {
        return resize.upscale();
    }

    /**
     * {@return the largest long edge of the output, applied after the box, in pixels, positive, or
     * empty for no limit; empty by default}
     */
    public OptionalInt maxLongEdge() {
        return resize.maxLongEdge() == 0 ? OptionalInt.empty() : OptionalInt.of(resize.maxLongEdge());
    }

    /**
     * {@return the largest short edge of the output, applied after the box, in pixels, positive, or
     * empty for no limit; empty by default}
     */
    public OptionalInt maxShortEdge() {
        return resize.maxShortEdge() == 0 ? OptionalInt.empty() : OptionalInt.of(resize.maxShortEdge());
    }

    /**
     * {@return whether the dimensions of an encoded stream are rounded down to even numbers, as 4:2:0
     * encoders need; {@code true} by default}
     *
     * <p>An odd-sized stream that is otherwise unchanged is still copied as it is.
     */
    public boolean evenDimensions() {
        return evenDimensions;
    }

    /**
     * {@return the resampling filter used when the size changes; {@link ResizeFilter#BILINEAR} by
     * default}
     */
    public ResizeFilter filter() {
        return resize.filter();
    }

    /**
     * {@return the region of the displayed picture that is kept, or empty for the whole picture; empty
     * by default}
     *
     * <p>Coordinates refer to the picture as displayed, after its orientation is applied, with the
     * origin at the top-left corner. A region that is not inside the picture fails the transcode with
     * an {@link IllegalArgumentException} once the picture size is known.
     */
    public Optional<Region> crop() {
        return Optional.ofNullable(resize.crop());
    }

    /**
     * {@return how the display orientation is handled; {@link OrientationMode#AUTO} by default}
     */
    public OrientationMode orientation() {
        return orientation;
    }

    /**
     * {@return how a stream of non-square samples is handled; {@link SampleAspectMode#AUTO} by default}
     *
     * <p>The sample aspect ratio of a stream is the one its container declares, else the one its
     * codec configuration signals; a stream that declares neither has square samples.
     */
    public SampleAspectMode sampleAspect() {
        return sampleAspect;
    }

    /**
     * {@return the constant frame rate of the output, in frames per second, or empty to keep the source
     * timing; empty by default}
     */
    public Optional<Rational> frameRate() {
        return Optional.ofNullable(frameRate);
    }

    /**
     * {@return the highest frame rate of the output, in frames per second, or empty for no limit; empty
     * by default}
     *
     * <p>A source with a higher declared rate is converted to this rate, a source at or below it keeps
     * its timing.
     */
    public Optional<Rational> maxFrameRate() {
        return Optional.ofNullable(maxFrameRate);
    }

    /**
     * {@return the chroma format the pictures are converted to before encoding, or empty to let the
     * encoder convert as it needs; empty by default}
     */
    public Optional<ChromaFormat> chromaFormat() {
        return Optional.ofNullable(chromaFormat);
    }

    /**
     * {@return the color description (matrix and range) the pictures are converted to before encoding,
     * or empty to keep the source's; empty by default}
     */
    public Optional<ColorInfo> color() {
        return Optional.ofNullable(color);
    }

    /**
     * {@return the color transparent pixels are flattened on before encoding, as {@code 0xRRGGBB};
     * white ({@code 0xFFFFFF}) by default}
     *
     * <p>It matters for animated images with transparency.
     */
    public int background() {
        return background;
    }

    /**
     * Returns whether the options change the picture size, crop it, or resample its timing, which
     * a copy cannot do, for a source of a displayed size and frame rate. Rounding to even
     * dimensions, which only matters to an encoder, does not count.
     *
     * @param displayWidth the displayed source width
     * @param displayHeight the displayed source height
     * @param sourceRate the source frame rate, or {@code null} when unknown
     * @return {@code true} when the stream has to be decoded
     */
    boolean changesPicture(int displayWidth, int displayHeight, Rational sourceRate) {
        if (resize.crop() != null || chromaFormat != null || color != null || effectiveFrameRate(sourceRate) != null) {
            return true;
        }
        var plan = resize.sizePlan(displayWidth, displayHeight, false);
        return plan.outputWidth() != displayWidth || plan.outputHeight() != displayHeight;
    }

    /**
     * {@return whether any picture operation is requested, whatever the source: a box, an edge
     * limit, a crop, a frame rate or a pixel format}
     */
    boolean hasPictureOps() {
        return resize.resizes() || frameRate != null || maxFrameRate != null || chromaFormat != null || color != null;
    }

    /**
     * Returns the constant rate the output is converted to, for a source of a frame rate.
     *
     * @param sourceRate the source frame rate, or {@code null} when unknown
     * @return {@link #frameRate()} when set; otherwise {@link #maxFrameRate()} when the source is
     * faster or of unknown rate; otherwise {@code null}, to keep the source timing
     */
    Rational effectiveFrameRate(Rational sourceRate) {
        if (frameRate != null) {
            return frameRate;
        }
        if (maxFrameRate != null && (sourceRate == null || sourceRate.toDouble() > maxFrameRate.toDouble() + 1e-9)) {
            return maxFrameRate;
        }
        return null;
    }

    /**
     * Plans the scaling of a displayed picture under these options.
     *
     * @param displayWidth the displayed width, after any crop
     * @param displayHeight the displayed height, after any crop
     * @return the plan
     */
    SizePlan sizePlan(int displayWidth, int displayHeight) {
        return resize.sizePlan(displayWidth, displayHeight, evenDimensions);
    }

    /**
     * Returns the state compared by {@link #equals(Object)}.
     *
     * @return the field values
     */
    private Object[] state() {
        return new Object[]{mode, trackId, codec, bitrate, threads, keyframeInterval, periodicKeyframes, resize,
                evenDimensions, orientation, sampleAspect, frameRate, maxFrameRate, chromaFormat, color, background};
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VideoTranscodeOptions that && Arrays.equals(state(), that.state());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(state());
    }

    @Override
    public String toString() {
        return "VideoTranscodeOptions[mode=" + mode + ", trackId=" + trackId() + ", codec=" + codec() + ", bitrate="
                + bitrate() + ", threads=" + threads + ", keyframeInterval=" + keyframeInterval() + ", periodicKeyframes="
                + periodicKeyframes + ", " + resize.describe() + ", evenDimensions=" + evenDimensions
                + ", filter=" + resize.filter() + ", crop=" + crop() + ", orientation=" + orientation
                + ", sampleAspect=" + sampleAspect
                + ", frameRate=" + frameRate() + ", maxFrameRate=" + maxFrameRate() + ", chromaFormat=" + chromaFormat()
                + ", color=" + color() + ", background=0x" + Integer.toHexString(background) + "]";
    }

    /**
     * Assembles {@link VideoTranscodeOptions} setting by setting.
     *
     * <p>Setters reject {@code null} unless documented otherwise and store numbers unchecked;
     * {@link #build()} checks the ranges and the codec. Instances are not thread-safe.
     */
    public static final class Builder {
        private StreamMode mode = StreamMode.AUTO;
        private int trackId;

        /**
         * Whether {@link #trackId} selects the track.
         */
        private boolean byId;

        private Codec codec;
        private int bitrate;

        /**
         * Whether {@link #bitrate} is set; {@code false} for the default bitrate.
         */
        private boolean bitrateSet;

        private int threads;
        private int keyframeInterval;

        /**
         * Whether {@link #keyframeInterval} is set; {@code false} to let the encoder choose.
         */
        private boolean keyframeIntervalSet;

        private boolean periodicKeyframes = true;
        private int width;
        private int height;
        private ScaleMode scaleMode = ScaleMode.FIT;
        private boolean upscale;
        private int maxLongEdge;

        /**
         * Whether {@link #maxLongEdge} limits the output.
         */
        private boolean longEdgeLimited;

        private int maxShortEdge;

        /**
         * Whether {@link #maxShortEdge} limits the output.
         */
        private boolean shortEdgeLimited;

        private boolean evenDimensions = true;
        private ResizeFilter filter = ResizeFilter.BILINEAR;
        private Region crop;
        private OrientationMode orientation = OrientationMode.AUTO;
        private SampleAspectMode sampleAspect = SampleAspectMode.AUTO;
        private Rational frameRate;
        private Rational maxFrameRate;
        private ChromaFormat chromaFormat;
        private ColorInfo color;
        private int background = 0xFFFFFF;

        private Builder() {
        }

        private Builder(VideoTranscodeOptions options) {
            this.mode = options.mode;
            this.trackId = options.trackId;
            this.byId = options.trackId >= 0;
            this.codec = options.codec;
            this.bitrate = options.bitrate;
            this.bitrateSet = options.bitrate != 0;
            this.threads = options.threads;
            this.keyframeInterval = options.keyframeInterval;
            this.keyframeIntervalSet = options.keyframeInterval != 0;
            this.periodicKeyframes = options.periodicKeyframes;
            var resize = options.resize;
            this.width = resize.width();
            this.height = resize.height();
            this.scaleMode = resize.scaleMode();
            this.upscale = resize.upscale();
            this.maxLongEdge = resize.maxLongEdge();
            this.longEdgeLimited = resize.maxLongEdge() != 0;
            this.maxShortEdge = resize.maxShortEdge();
            this.shortEdgeLimited = resize.maxShortEdge() != 0;
            this.evenDimensions = options.evenDimensions;
            this.filter = resize.filter();
            this.crop = resize.crop();
            this.orientation = options.orientation;
            this.sampleAspect = options.sampleAspect;
            this.frameRate = options.frameRate;
            this.maxFrameRate = options.maxFrameRate;
            this.chromaFormat = options.chromaFormat;
            this.color = options.color;
            this.background = options.background;
        }

        /**
         * Sets the mode; see {@link VideoTranscodeOptions#mode()}.
         *
         * @param mode the value
         * @return this builder
         */
        public Builder mode(StreamMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Selects a track by identifier; see {@link VideoTranscodeOptions#trackId()}.
         *
         * @param trackId the value
         * @return this builder
         */
        public Builder trackId(int trackId) {
            this.trackId = trackId;
            this.byId = true;
            return this;
        }

        /**
         * Selects the first video track; see {@link VideoTranscodeOptions#trackId()}.
         *
         * @return this builder
         */
        public Builder firstTrack() {
            this.byId = false;
            return this;
        }

        /**
         * Sets the codec to produce; see {@link VideoTranscodeOptions#codec()}.
         *
         * @param codec the value
         * @return this builder
         */
        public Builder codec(Codec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /**
         * Lets the output container choose the codec; see {@link VideoTranscodeOptions#codec()}.
         *
         * @return this builder
         */
        public Builder automaticCodec() {
            this.codec = null;
            return this;
        }

        /**
         * Sets the target bitrate; see {@link VideoTranscodeOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(int bitrate) {
            this.bitrate = bitrate;
            this.bitrateSet = true;
            return this;
        }

        /**
         * Lets the encoder choose the bitrate; see {@link VideoTranscodeOptions#bitrate()}.
         *
         * @return this builder
         */
        public Builder automaticBitrate() {
            this.bitrateSet = false;
            return this;
        }

        /**
         * Sets the encoder thread count; see {@link VideoTranscodeOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets the most frames from one key frame to the next, turning periodic key frames on; see
         * {@link VideoTranscodeOptions#keyframeInterval()}.
         *
         * @param frames the distance in frames
         * @return this builder
         */
        public Builder keyframeInterval(int frames) {
            this.keyframeInterval = frames;
            this.keyframeIntervalSet = true;
            this.periodicKeyframes = true;
            return this;
        }

        /**
         * Codes key frames only at the first frame and at scene cuts; see
         * {@link VideoTranscodeOptions#periodicKeyframes()}.
         *
         * @return this builder
         */
        public Builder noPeriodicKeyframes() {
            this.keyframeIntervalSet = false;
            this.periodicKeyframes = false;
            return this;
        }

        /**
         * Lets the encoder choose the key frame distance; see
         * {@link VideoTranscodeOptions#keyframeInterval()}.
         *
         * @return this builder
         */
        public Builder automaticKeyframeInterval() {
            this.keyframeIntervalSet = false;
            this.periodicKeyframes = true;
            return this;
        }

        /**
         * Sets the target box and how the picture is fitted to it; see
         * {@link VideoTranscodeOptions#width()}, {@link VideoTranscodeOptions#height()} and
         * {@link VideoTranscodeOptions#scaleMode()}.
         *
         * @param width the box width in pixels, or 0 to leave it free
         * @param height the box height in pixels, or 0 to leave it free
         * @param mode the scale mode
         * @return this builder
         */
        public Builder size(int width, int height, ScaleMode mode) {
            this.scaleMode = Objects.requireNonNull(mode, "mode");
            this.width = width;
            this.height = height;
            return this;
        }

        /**
         * Keeps the picture within a box, scaling it down with its aspect ratio kept, as
         * {@link #size(int, int, ScaleMode) size(width, height, ScaleMode.FIT)} does; see
         * {@link VideoTranscodeOptions#width()} and {@link VideoTranscodeOptions#height()}.
         *
         * @param width the box width in pixels, or 0 to leave it free
         * @param height the box height in pixels, or 0 to leave it free
         * @return this builder
         */
        public Builder maxSize(int width, int height) {
            return size(width, height, ScaleMode.FIT);
        }

        /**
         * Sets whether {@link ScaleMode#FIT} may enlarge the picture; see
         * {@link VideoTranscodeOptions#upscale()}.
         *
         * @param upscale the value
         * @return this builder
         */
        public Builder upscale(boolean upscale) {
            this.upscale = upscale;
            return this;
        }

        /**
         * Limits the long edge of the output; see {@link VideoTranscodeOptions#maxLongEdge()}.
         *
         * @param edge the value
         * @return this builder
         */
        public Builder maxLongEdge(int edge) {
            this.maxLongEdge = edge;
            this.longEdgeLimited = true;
            return this;
        }

        /**
         * Removes the limit on the long edge of the picture; see {@link VideoTranscodeOptions#maxLongEdge()}.
         *
         * @return this builder
         */
        public Builder noMaxLongEdge() {
            this.longEdgeLimited = false;
            return this;
        }

        /**
         * Limits the short edge of the output; see {@link VideoTranscodeOptions#maxShortEdge()}.
         *
         * @param edge the value
         * @return this builder
         */
        public Builder maxShortEdge(int edge) {
            this.maxShortEdge = edge;
            this.shortEdgeLimited = true;
            return this;
        }

        /**
         * Removes the limit on the short edge of the picture; see {@link VideoTranscodeOptions#maxShortEdge()}.
         *
         * @return this builder
         */
        public Builder noMaxShortEdge() {
            this.shortEdgeLimited = false;
            return this;
        }

        /**
         * Sets whether output dimensions are rounded down to even numbers; see
         * {@link VideoTranscodeOptions#evenDimensions()}.
         *
         * @param evenDimensions the value
         * @return this builder
         */
        public Builder evenDimensions(boolean evenDimensions) {
            this.evenDimensions = evenDimensions;
            return this;
        }

        /**
         * Sets the resampling filter; see {@link VideoTranscodeOptions#filter()}.
         *
         * @param filter the value
         * @return this builder
         */
        public Builder filter(ResizeFilter filter) {
            this.filter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /**
         * Sets the region of the displayed picture that is kept; see {@link VideoTranscodeOptions#crop()}.
         *
         * @param crop the value
         * @return this builder
         */
        public Builder crop(Region crop) {
            this.crop = Objects.requireNonNull(crop, "crop");
            return this;
        }

        /**
         * Keeps the whole picture; see {@link VideoTranscodeOptions#crop()}.
         *
         * @return this builder
         */
        public Builder noCrop() {
            this.crop = null;
            return this;
        }

        /**
         * Sets the orientation handling; see {@link VideoTranscodeOptions#orientation()}.
         *
         * @param orientation the value
         * @return this builder
         */
        public Builder orientation(OrientationMode orientation) {
            this.orientation = Objects.requireNonNull(orientation, "orientation");
            return this;
        }

        /**
         * Sets the handling of non-square samples; see {@link VideoTranscodeOptions#sampleAspect()}.
         *
         * @param sampleAspect the value
         * @return this builder
         */
        public Builder sampleAspect(SampleAspectMode sampleAspect) {
            this.sampleAspect = Objects.requireNonNull(sampleAspect, "sampleAspect");
            return this;
        }

        /**
         * Sets the constant output frame rate; see {@link VideoTranscodeOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Keeps the timing of the source frames; see {@link VideoTranscodeOptions#frameRate()}.
         *
         * @return this builder
         */
        public Builder sourceFrameRate() {
            this.frameRate = null;
            return this;
        }

        /**
         * Sets the highest output frame rate; see {@link VideoTranscodeOptions#maxFrameRate()}.
         *
         * @param maxFrameRate the value
         * @return this builder
         */
        public Builder maxFrameRate(Rational maxFrameRate) {
            this.maxFrameRate = Objects.requireNonNull(maxFrameRate, "maxFrameRate");
            return this;
        }

        /**
         * Removes the limit on the output frame rate; see {@link VideoTranscodeOptions#maxFrameRate()}.
         *
         * @return this builder
         */
        public Builder noMaxFrameRate() {
            this.maxFrameRate = null;
            return this;
        }

        /**
         * Sets the chroma format pictures are converted to before encoding; see
         * {@link VideoTranscodeOptions#chromaFormat()}.
         *
         * @param chromaFormat the value
         * @return this builder
         */
        public Builder chromaFormat(ChromaFormat chromaFormat) {
            this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
            return this;
        }

        /**
         * Lets the encoder choose the chroma format; see {@link VideoTranscodeOptions#chromaFormat()}.
         *
         * @return this builder
         */
        public Builder automaticChromaFormat() {
            this.chromaFormat = null;
            return this;
        }

        /**
         * Sets the color description pictures are converted to before encoding; see
         * {@link VideoTranscodeOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Keeps the color description of the source; see {@link VideoTranscodeOptions#color()}.
         *
         * @return this builder
         */
        public Builder sourceColor() {
            this.color = null;
            return this;
        }

        /**
         * Sets the color transparent pixels are flattened on; see {@link VideoTranscodeOptions#background()}.
         *
         * @param rgb the value as {@code 0xRRGGBB}; the top byte is ignored
         * @return this builder
         */
        public Builder background(int rgb) {
            this.background = rgb & 0xFFFFFF;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the track identifier, the thread count or a box dimension
         * is negative, the codec is not a video codec, or the bitrate, the key frame interval or an edge
         * limit is not positive
         */
        public VideoTranscodeOptions build() {
            if (codec != null && codec.kind() != TrackKind.VIDEO) {
                throw new IllegalArgumentException("codec " + codec.displayName() + " is not a video codec");
            }
            Checks.nonNegative("thread count", threads);
            var resize = ResizeSettings.checked(width, height, scaleMode, upscale, longEdgeLimited, maxLongEdge,
                    shortEdgeLimited, maxShortEdge, filter, crop);
            return new VideoTranscodeOptions(this,
                    byId ? Checks.nonNegative("track identifier", trackId) : -1,
                    bitrateSet ? Checks.positive("bitrate", bitrate) : 0,
                    keyframeIntervalSet ? Checks.positive("key frame interval", keyframeInterval) : 0,
                    resize);
        }
    }
}
