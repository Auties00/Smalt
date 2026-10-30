package com.github.auties00.smalt.track;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes a video elementary stream.
 *
 * <p>Instances are created with {@link #builder()} or derived from another track with
 * {@link #toBuilder()}. The codec configuration array is shared, not copied. Instances are
 * immutable and thread-safe, provided the configuration array is not modified.
 */
public final class VideoTrack implements Track {
    /**
     * The identifier carried by the track's packets.
     */
    private final int id;

    /**
     * The coding format.
     */
    private final Codec codec;

    /**
     * The unit of timestamps and durations.
     */
    private final Rational timeBase;

    /**
     * The duration in time-base units, or {@code -1}.
     */
    private final long duration;

    /**
     * The out-of-band decoder configuration, or {@code null}.
     */
    private final byte[] codecConfig;

    /**
     * The ISO 639-2 language code, or {@code null}.
     */
    private final String language;

    /**
     * The coded picture width, or {@code 0}.
     */
    private final int width;

    /**
     * The coded picture height, or {@code 0}.
     */
    private final int height;

    /**
     * The display transform.
     */
    private final Orientation orientation;

    /**
     * The nominal frame rate, or {@code null}.
     */
    private final Rational frameRate;

    /**
     * The declared color description, or {@code null}.
     */
    private final ColorInfo color;

    /**
     * The sample aspect ratio, or {@code null}.
     */
    private final Rational sampleAspectRatio;

    /**
     * The declared scan, or {@code null}.
     */
    private final Interlacing interlacing;

    /**
     * The spherical projection, or {@code null}.
     */
    private final SphericalVideo spherical;

    /**
     * The stereoscopic packing, or {@code null}.
     */
    private final StereoMode stereoMode;

    /**
     * Whether the packets carry the alpha plane as a second coded stream.
     */
    private final boolean alpha;

    /**
     * The Dolby Vision configuration, or {@code null}.
     */
    private final DolbyVision dolbyVision;

    private VideoTrack(Builder builder) {
        this.id = builder.id;
        this.codec = builder.codec;
        this.timeBase = builder.timeBase;
        this.duration = builder.duration;
        this.codecConfig = builder.codecConfig;
        this.language = builder.language;
        this.width = builder.width;
        this.height = builder.height;
        this.orientation = builder.orientation;
        this.frameRate = builder.frameRate;
        this.color = builder.color;
        this.sampleAspectRatio = builder.sampleAspectRatio;
        this.interlacing = builder.interlacing;
        this.spherical = builder.spherical;
        this.stereoMode = builder.stereoMode;
        this.alpha = builder.alpha;
        this.dolbyVision = builder.dolbyVision;
    }

    /**
     * Returns a builder of video tracks.
     *
     * <p>The codec and the time base must be set; every other property starts at its "unknown"
     * value: identifier {@code 0}, duration {@code -1}, no configuration, language, frame rate,
     * color, sample aspect ratio, scan, spherical projection, stereo mode or Dolby Vision configuration, size
     * {@code 0x0}, {@link Orientation#NORMAL} and no alpha.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with the properties of this track.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        var builder = new Builder();
        builder.id = id;
        builder.codec = codec;
        builder.timeBase = timeBase;
        builder.duration = duration;
        builder.codecConfig = codecConfig;
        builder.language = language;
        builder.width = width;
        builder.height = height;
        builder.orientation = orientation;
        builder.frameRate = frameRate;
        builder.color = color;
        builder.sampleAspectRatio = sampleAspectRatio;
        builder.interlacing = interlacing;
        builder.spherical = spherical;
        builder.stereoMode = stereoMode;
        builder.alpha = alpha;
        builder.dolbyVision = dolbyVision;
        return builder;
    }

    @Override
    public int id() {
        return id;
    }

    /**
     * {@inheritDoc}
     *
     * @return the codec; its kind is {@link TrackKind#VIDEO}
     */
    @Override
    public Codec codec() {
        return codec;
    }

    @Override
    public Rational timeBase() {
        return timeBase;
    }

    @Override
    public long duration() {
        return duration;
    }

    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.ofNullable(codecConfig);
    }

    @Override
    public Optional<String> language() {
        return Optional.ofNullable(language);
    }

    /**
     * Returns the coded picture width, before {@link #orientation()} is applied.
     *
     * @return the width in pixels, or {@code 0} when the container does not declare it
     */
    public int width() {
        return width;
    }

    /**
     * Returns the coded picture height, before {@link #orientation()} is applied.
     *
     * @return the height in pixels, or {@code 0} when the container does not declare it
     */
    public int height() {
        return height;
    }

    /**
     * Returns the transform a player applies to display the pictures upright.
     *
     * @return the orientation
     */
    public Orientation orientation() {
        return orientation;
    }

    /**
     * Returns the nominal frame rate.
     *
     * @return the frame rate, or empty when the container does not declare one
     */
    public Optional<Rational> frameRate() {
        return Optional.ofNullable(frameRate);
    }

    /**
     * Returns the color description declared by the container.
     *
     * @return the description, or empty when the decoder must take it from the bitstream
     */
    public Optional<ColorInfo> color() {
        return Optional.ofNullable(color);
    }

    /**
     * Returns the shape of a coded sample declared by the container: its width divided by its
     * height, so that a picture is displayed {@code width() * sampleAspectRatio()} pixels wide and
     * {@code height()} pixels high.
     *
     * <p>The ratio is in coded orientation, before {@link #orientation()} is applied, and in lowest
     * terms. A container that declares square samples gives {@code 1/1}; an empty result means the
     * container is silent, and the bitstream's own signaling (or square samples) applies. A
     * container without a field of its own (MPEG-TS) reports the ratio the sequence parameter set
     * signals.
     *
     * @return the sample aspect ratio, or empty when the container does not declare one
     */
    public Optional<Rational> sampleAspectRatio() {
        return Optional.ofNullable(sampleAspectRatio);
    }

    /**
     * Returns the scan of the frames declared by the container.
     *
     * @return the scan, or empty when the container does not declare whether the frames are
     * interlaced
     */
    public Optional<Interlacing> interlacing() {
        return Optional.ofNullable(interlacing);
    }

    /**
     * Returns how the pictures of a 360-degree video map onto a sphere, as the container declares it.
     *
     * @return the projection, or empty for flat video or when the container declares none
     */
    public Optional<SphericalVideo> spherical() {
        return Optional.ofNullable(spherical);
    }

    /**
     * Returns how the pictures pack the views of the two eyes, as the container declares it.
     *
     * @return the packing, or empty when the container does not declare one; {@link StereoMode#MONO} when it
     * declares a single view
     */
    public Optional<StereoMode> stereoMode() {
        return Optional.ofNullable(stereoMode);
    }

    /**
     * Returns whether the track carries transparency as a second coded stream, as the container
     * declares it (the Matroska and WebM {@code AlphaMode} 1).
     *
     * <p>Each packet of such a track may carry, in {@link PacketSideData#alphaFrame()}, a frame of
     * the track's codec whose luma plane is the alpha plane of the packet's frame, with straight
     * (non-premultiplied) semantics; {@link com.github.auties00.smalt.video.VideoDecoders} decodes
     * both streams into pictures with an alpha plane. A packet without an alpha frame shows an opaque
     * frame.
     *
     * @return {@code true} when the packets carry alpha frames
     */
    public boolean hasAlpha() {
        return alpha;
    }

    /**
     * Returns the Dolby Vision configuration the container declares: an MP4 {@code dvcC}, {@code dvvC} or
     * {@code dvwC} box, a Matroska {@code BlockAdditionMapping} of one of those types, or an MPEG-TS DOVI video
     * stream descriptor.
     *
     * <p>The track's {@link #codec()} is the codec of the base layer, and its packets carry the reference
     * processing units in band. A muxer that can store the configuration writes it again, so that a remux keeps
     * the track a Dolby Vision track. A configuration that is not
     * {@linkplain DolbyVision#isBackwardCompatible() backward compatible} makes
     * {@link com.github.auties00.smalt.video.VideoDecoders} refuse the track unless
     * {@link com.github.auties00.smalt.video.VideoDecoderOptions#dolbyVisionBaseLayer()} is set.
     *
     * @return the configuration, or empty when the track is not declared as Dolby Vision
     */
    public Optional<DolbyVision> dolbyVision() {
        return Optional.ofNullable(dolbyVision);
    }

    /**
     * Compares two tracks property by property, the configuration by content.
     *
     * @param other the object to compare with
     * @return whether {@code other} is a video track with equal properties
     */
    @Override
    public boolean equals(Object other) {
        return other == this || other instanceof VideoTrack that
                && id == that.id && codec == that.codec && timeBase.equals(that.timeBase) && duration == that.duration
                && Arrays.equals(codecConfig, that.codecConfig) && Objects.equals(language, that.language)
                && width == that.width && height == that.height && orientation == that.orientation
                && Objects.equals(frameRate, that.frameRate) && Objects.equals(color, that.color)
                && Objects.equals(sampleAspectRatio, that.sampleAspectRatio) && interlacing == that.interlacing
                && Objects.equals(spherical, that.spherical) && stereoMode == that.stereoMode && alpha == that.alpha
                && Objects.equals(dolbyVision, that.dolbyVision);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, codec, timeBase, duration, Arrays.hashCode(codecConfig), language, width, height,
                orientation, frameRate, color, sampleAspectRatio, interlacing, spherical, stereoMode, alpha,
                dolbyVision);
    }

    /**
     * Returns a description of the properties, with the configuration size instead of its
     * contents.
     *
     * @return the description
     */
    @Override
    public String toString() {
        return "VideoTrack[id=" + id + ", codec=" + codec + ", timeBase=" + timeBase + ", duration=" + duration
                + ", codecConfig=" + (codecConfig == null ? "none" : codecConfig.length + " bytes")
                + ", language=" + language + ", width=" + width + ", height=" + height
                + ", orientation=" + orientation + ", frameRate=" + frameRate + ", color=" + color
                + ", sampleAspectRatio=" + sampleAspectRatio + ", interlacing=" + interlacing
                + ", spherical=" + spherical + ", stereoMode=" + stereoMode + ", alpha=" + alpha
                + ", dolbyVision=" + dolbyVision + "]";
    }

    /**
     * Builds {@link VideoTrack} instances.
     *
     * <p>Instances are not thread-safe; confine a builder to one thread.
     */
    public static final class Builder {
        /**
         * The identifier.
         */
        private int id;

        /**
         * The coding format, or {@code null} until set.
         */
        private Codec codec;

        /**
         * The time base, or {@code null} until set.
         */
        private Rational timeBase;

        /**
         * The duration, or {@code -1}.
         */
        private long duration = -1;

        /**
         * The configuration, or {@code null}.
         */
        private byte[] codecConfig;

        /**
         * The language, or {@code null}.
         */
        private String language;

        /**
         * The coded width, or {@code 0}.
         */
        private int width;

        /**
         * The coded height, or {@code 0}.
         */
        private int height;

        /**
         * The display transform.
         */
        private Orientation orientation = Orientation.NORMAL;

        /**
         * The frame rate, or {@code null}.
         */
        private Rational frameRate;

        /**
         * The color description, or {@code null}.
         */
        private ColorInfo color;

        /**
         * The sample aspect ratio in lowest terms, or {@code null}.
         */
        private Rational sampleAspectRatio;

        /**
         * The scan, or {@code null}.
         */
        private Interlacing interlacing;

        /**
         * The spherical projection, or {@code null}.
         */
        private SphericalVideo spherical;

        /**
         * The stereoscopic packing, or {@code null}.
         */
        private StereoMode stereoMode;

        /**
         * Whether the packets carry alpha frames.
         */
        private boolean alpha;

        /**
         * The Dolby Vision configuration, or {@code null}.
         */
        private DolbyVision dolbyVision;

        /**
         * Constructs a builder with every property unknown.
         */
        private Builder() {
        }

        /**
         * Sets the identifier the track's packets carry.
         *
         * @param id the identifier, unique within the container
         * @return this builder
         */
        public Builder id(int id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the coding format.
         *
         * @param codec the codec; its kind must be {@link TrackKind#VIDEO}
         * @return this builder
         */
        public Builder codec(Codec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /**
         * Sets the unit of timestamps and durations.
         *
         * @param timeBase the time base
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the stream duration.
         *
         * @param duration the duration in time-base units, or {@code -1} when unknown
         * @return this builder
         */
        public Builder duration(long duration) {
            this.duration = duration;
            return this;
        }

        /**
         * Sets the out-of-band decoder configuration, in the form {@link Track} describes.
         *
         * @param codecConfig the configuration, shared and not copied, or {@code null} for none
         * @return this builder
         */
        public Builder codecConfig(byte[] codecConfig) {
            this.codecConfig = codecConfig;
            return this;
        }

        /**
         * Sets the declared language.
         *
         * @param language the ISO 639-2 language code, or {@code null} when undeclared
         * @return this builder
         */
        public Builder language(String language) {
            this.language = language;
            return this;
        }

        /**
         * Sets the coded picture width, before the orientation is applied.
         *
         * @param width the width in pixels, or {@code 0} when undeclared; non-negative
         * @return this builder
         */
        public Builder width(int width) {
            this.width = width;
            return this;
        }

        /**
         * Sets the coded picture height, before the orientation is applied.
         *
         * @param height the height in pixels, or {@code 0} when undeclared; non-negative
         * @return this builder
         */
        public Builder height(int height) {
            this.height = height;
            return this;
        }

        /**
         * Sets the transform a player applies to display the pictures upright;
         * {@link Orientation#NORMAL} by default.
         *
         * @param orientation the transform
         * @return this builder
         */
        public Builder orientation(Orientation orientation) {
            this.orientation = Objects.requireNonNull(orientation, "orientation");
            return this;
        }

        /**
         * Sets the nominal frame rate.
         *
         * @param frameRate the frame rate, or {@code null} when undeclared
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = frameRate;
            return this;
        }

        /**
         * Sets the color description declared by the container.
         *
         * @param color the description, or {@code null} when the decoder must take it from the
         * bitstream
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = color;
            return this;
        }

        /**
         * Sets the sample aspect ratio declared by the container; see
         * {@link VideoTrack#sampleAspectRatio()}.
         *
         * @param sampleAspectRatio the width of a coded sample divided by its height; stored in
         * lowest terms
         * @return this builder
         */
        public Builder sampleAspectRatio(Rational sampleAspectRatio) {
            this.sampleAspectRatio = Objects.requireNonNull(sampleAspectRatio, "sampleAspectRatio").reduce();
            return this;
        }

        /**
         * Removes the sample aspect ratio, so that the track declares none.
         *
         * @return this builder
         */
        public Builder noSampleAspectRatio() {
            this.sampleAspectRatio = null;
            return this;
        }

        /**
         * Sets the scan of the frames declared by the container; see
         * {@link VideoTrack#interlacing()}.
         *
         * @param interlacing the scan
         * @return this builder
         */
        public Builder interlacing(Interlacing interlacing) {
            this.interlacing = Objects.requireNonNull(interlacing, "interlacing");
            return this;
        }

        /**
         * Removes the scan, so that the track does not declare whether its frames are interlaced.
         *
         * @return this builder
         */
        public Builder noInterlacing() {
            this.interlacing = null;
            return this;
        }

        /**
         * Sets the spherical projection declared by the container; see {@link VideoTrack#spherical()}.
         *
         * @param spherical the projection
         * @return this builder
         */
        public Builder spherical(SphericalVideo spherical) {
            this.spherical = Objects.requireNonNull(spherical, "spherical");
            return this;
        }

        /**
         * Removes the spherical projection, so that the track is flat video.
         *
         * @return this builder
         */
        public Builder noSpherical() {
            this.spherical = null;
            return this;
        }

        /**
         * Sets the stereoscopic packing declared by the container; see {@link VideoTrack#stereoMode()}.
         *
         * @param stereoMode the packing
         * @return this builder
         */
        public Builder stereoMode(StereoMode stereoMode) {
            this.stereoMode = Objects.requireNonNull(stereoMode, "stereoMode");
            return this;
        }

        /**
         * Removes the stereoscopic packing, so that the track declares none.
         *
         * @return this builder
         */
        public Builder noStereoMode() {
            this.stereoMode = null;
            return this;
        }

        /**
         * Sets whether the packets carry the alpha plane as a second coded stream; see
         * {@link VideoTrack#hasAlpha()}.
         *
         * @param alpha whether the packets carry alpha frames
         * @return this builder
         */
        public Builder alpha(boolean alpha) {
            this.alpha = alpha;
            return this;
        }

        /**
         * Sets the Dolby Vision configuration declared by the container; see {@link VideoTrack#dolbyVision()}.
         *
         * @param dolbyVision the configuration
         * @return this builder
         */
        public Builder dolbyVision(DolbyVision dolbyVision) {
            this.dolbyVision = Objects.requireNonNull(dolbyVision, "dolbyVision");
            return this;
        }

        /**
         * Removes the Dolby Vision configuration, so that the track is plain video of its codec.
         *
         * @return this builder
         */
        public Builder noDolbyVision() {
            this.dolbyVision = null;
            return this;
        }

        /**
         * Builds the track.
         *
         * @return the track
         * @throws NullPointerException if the codec or the time base is not set
         * @throws IllegalArgumentException if the codec is not a video codec or a dimension is
         * negative
         */
        public VideoTrack build() {
            Objects.requireNonNull(codec, "codec");
            Objects.requireNonNull(timeBase, "timeBase");
            if (codec.kind() != TrackKind.VIDEO) {
                throw new IllegalArgumentException(codec + " is not a video codec");
            }
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("invalid video size " + width + "x" + height);
            }
            return new VideoTrack(this);
        }
    }
}
