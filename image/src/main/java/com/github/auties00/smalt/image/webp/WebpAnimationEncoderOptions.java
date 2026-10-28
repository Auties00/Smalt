package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.tiff.TiffExif;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a {@link WebpAnimationEncoder}. Instances are immutable and thread-safe;
 * create them with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>Each frame is encoded as a sub-rectangle of the canvas holding only what changed since the
 * previous frame, blended over it or not, and the previous frame's disposal is chosen to make the
 * rectangle as small as possible.
 */
public final class WebpAnimationEncoderOptions {
    /**
     * The smallest key frame distance that follows lossless coding.
     */
    private static final int LOSSLESS_MIN_KEYFRAME_DISTANCE = 9;

    /**
     * The largest key frame distance that follows lossless coding.
     */
    private static final int LOSSLESS_MAX_KEYFRAME_DISTANCE = 17;

    /**
     * The smallest key frame distance that follows lossy coding.
     */
    private static final int LOSSY_MIN_KEYFRAME_DISTANCE = 3;

    /**
     * The largest key frame distance that follows lossy coding.
     */
    private static final int LOSSY_MAX_KEYFRAME_DISTANCE = 5;

    /**
     * The options of {@link #defaults()} and {@link #lossless()}.
     */
    private static final WebpAnimationEncoderOptions LOSSLESS = new Builder().build();

    private final int loopCount;

    private final boolean lossless;

    private final int quality;

    private final int method;

    /**
     * Whether the key frame distances were set rather than following the coding mode.
     */
    private final boolean keyframesSet;

    /**
     * The smallest key frame distance when {@link #keyframesSet}, otherwise 0.
     */
    private final int minKeyframeDistance;

    /**
     * The largest key frame distance when {@link #keyframesSet}, otherwise 0.
     */
    private final int maxKeyframeDistance;

    private final boolean allowMixed;

    private final boolean minimize;

    private final ColorProfile colorProfile;

    private final byte[] exif;

    private final byte[] xmp;

    private WebpAnimationEncoderOptions(Builder builder) {
        this.loopCount = builder.loopCount;
        this.lossless = builder.lossless;
        this.quality = builder.quality;
        this.method = builder.method;
        this.keyframesSet = builder.keyframesSet;
        this.minKeyframeDistance = builder.keyframesSet ? builder.minKeyframeDistance : 0;
        this.maxKeyframeDistance = builder.keyframesSet ? builder.maxKeyframeDistance : 0;
        this.allowMixed = builder.allowMixed;
        this.minimize = builder.minimize;
        this.colorProfile = builder.colorProfile;
        this.exif = builder.exif;
        this.xmp = builder.xmp;
    }

    /**
     * Returns lossless coding at effort 75 and method 4, with key frames every 9 to 17 frames,
     * looping forever; the same as {@link #lossless()}.
     *
     * @return the default options
     */
    public static WebpAnimationEncoderOptions defaults() {
        return LOSSLESS;
    }

    /**
     * Returns lossless coding at effort 75 and method 4, with key frames every 9 to 17 frames,
     * looping forever.
     *
     * @return the options
     */
    public static WebpAnimationEncoderOptions lossless() {
        return LOSSLESS;
    }

    /**
     * Returns lossy coding at a quality and method 4, with key frames every 3 to 5 frames, looping
     * forever.
     *
     * @param quality the quality, from 0 (smallest) to 100 (best)
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is not in [0, 100]
     */
    public static WebpAnimationEncoderOptions lossy(int quality) {
        return new Builder().lossless(false).quality(quality).build();
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
     * {@return the total number of plays written to the {@code ANIM} chunk, from 1 to 65535, or 0
     * for forever; 0 by default}
     */
    public int loopCount() {
        return loopCount;
    }

    /**
     * {@return whether frames are coded losslessly (VP8L) rather than lossily (VP8); {@code true}
     * by default}
     *
     * <p>With {@link #allowMixed()}, each frame may still be coded lossily when that is smaller.
     */
    public boolean isLossless() {
        return lossless;
    }

    /**
     * {@return the lossy quality, or the compression effort when frames are coded losslessly, from
     * 0 to 100; 75 by default}
     *
     * <p>The quality is on the scale every Smalt image encoder shares, the scale of
     * {@code gif2webp -q}.
     */
    public int quality() {
        return quality;
    }

    /**
     * {@return the speed and size trade-off, from 0 (fastest) to 6 (smallest); 4 by default}
     */
    public int method() {
        return method;
    }

    /**
     * {@return the smallest number of frames between two key frames, at least 0; unless set, 9
     * for lossless and 3 for lossy coding}
     *
     * <p>Key frames are full frames independent of the previous one: they make seeking cheaper at
     * a small size cost. A key frame is inserted somewhere between the smallest and the
     * {@linkplain #maxKeyframeDistance() largest} distance after the previous one, where it costs
     * least. Unless the distances were set, they follow {@link #isLossless()}, also when a
     * {@linkplain #toBuilder() builder} made from these options changes the coding mode.
     */
    public int minKeyframeDistance() {
        return keyframesSet ? minKeyframeDistance
                : lossless ? LOSSLESS_MIN_KEYFRAME_DISTANCE : LOSSY_MIN_KEYFRAME_DISTANCE;
    }

    /**
     * {@return the largest number of frames between two key frames, at least 0, where 0 means no
     * key frame after the first frame and 1 makes every frame a key frame; unless set, 17 for
     * lossless and 5 for lossy coding}
     *
     * <p>Unless the distances were set, they follow {@link #isLossless()}; see
     * {@link #minKeyframeDistance()}.
     */
    public int maxKeyframeDistance() {
        return keyframesSet ? maxKeyframeDistance
                : lossless ? LOSSLESS_MAX_KEYFRAME_DISTANCE : LOSSY_MAX_KEYFRAME_DISTANCE;
    }

    /**
     * {@return whether each frame may choose lossy or lossless coding, whichever is smaller;
     * {@code false} by default}
     */
    public boolean allowMixed() {
        return allowMixed;
    }

    /**
     * {@return whether every disposal and coding combination is tried for the smallest file, which
     * also disables key frames; {@code false} by default}
     */
    public boolean minimize() {
        return minimize;
    }

    /**
     * {@return the ICC profile embedded in the output, or empty when none is embedded; empty by
     * default}
     *
     * <p>The profile tells readers which color space the frames are in; it does not convert them.
     * Without one, readers take the pixels as sRGB. It is written as an {@code ICCP} chunk right
     * after {@code VP8X}, whose ICC flag is set, also when the animation collapses into a still
     * image. A high-dynamic-range profile is written in its ICC form, whose {@code cicp} tag
     * carries its PQ or HLG code points.
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    /**
     * {@return a copy of the EXIF block written as an {@code EXIF} chunk after the frames, a TIFF structure
     * starting with its byte-order mark, or empty for none; empty by default}
     */
    public Optional<byte[]> exif() {
        return Optional.ofNullable(exif).map(byte[]::clone);
    }

    /**
     * {@return a copy of the XMP packet written as an {@code XMP } chunk, the last of the file, in UTF-8, or empty
     * for none; empty by default}
     */
    public Optional<byte[]> xmp() {
        return Optional.ofNullable(xmp).map(byte[]::clone);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Options whose key frame distances follow the coding mode differ from options with the same
     * distances set explicitly, since a builder made from them treats the coding mode differently.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof WebpAnimationEncoderOptions that
                && loopCount == that.loopCount
                && lossless == that.lossless
                && quality == that.quality
                && method == that.method
                && keyframesSet == that.keyframesSet
                && minKeyframeDistance == that.minKeyframeDistance
                && maxKeyframeDistance == that.maxKeyframeDistance
                && allowMixed == that.allowMixed
                && minimize == that.minimize
                && Objects.equals(colorProfile, that.colorProfile)
                && Arrays.equals(exif, that.exif)
                && Arrays.equals(xmp, that.xmp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(loopCount, lossless, quality, method, keyframesSet, minKeyframeDistance,
                maxKeyframeDistance, allowMixed, minimize, colorProfile, Arrays.hashCode(exif), Arrays.hashCode(xmp));
    }

    @Override
    public String toString() {
        return "WebpAnimationEncoderOptions[loopCount=" + loopCount + ", lossless=" + lossless + ", quality=" + quality
                + ", method=" + method + ", keyframes=" + (keyframesSet ? "" : "automatic ") + minKeyframeDistance()
                + ".." + maxKeyframeDistance() + ", allowMixed=" + allowMixed + ", minimize=" + minimize
                + (colorProfile == null ? "" : ", colorProfile=" + colorProfile.description().orElse("unnamed"))
                + (exif == null ? "" : ", exif=" + exif.length + " bytes")
                + (xmp == null ? "" : ", xmp=" + xmp.length + " bytes") + "]";
    }

    /**
     * A builder of {@link WebpAnimationEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and the color profile.
     * Instances are not thread-safe.
     */
    public static final class Builder {
        private int loopCount;

        private boolean lossless = true;

        private int quality = 75;

        private int method = 4;

        private boolean keyframesSet;

        private int minKeyframeDistance;

        private int maxKeyframeDistance;

        private boolean allowMixed;

        private boolean minimize;

        private ColorProfile colorProfile;

        private byte[] exif;

        private byte[] xmp;

        private Builder() {
        }

        private Builder(WebpAnimationEncoderOptions options) {
            this.loopCount = options.loopCount;
            this.lossless = options.lossless;
            this.quality = options.quality;
            this.method = options.method;
            this.keyframesSet = options.keyframesSet;
            this.minKeyframeDistance = options.minKeyframeDistance;
            this.maxKeyframeDistance = options.maxKeyframeDistance;
            this.allowMixed = options.allowMixed;
            this.minimize = options.minimize;
            this.colorProfile = options.colorProfile;
            this.exif = options.exif;
            this.xmp = options.xmp;
        }

        /**
         * Sets the total number of plays; see {@link WebpAnimationEncoderOptions#loopCount()}.
         *
         * @param loopCount the value
         * @return this builder
         */
        public Builder loopCount(int loopCount) {
            this.loopCount = loopCount;
            return this;
        }

        /**
         * Sets whether frames are coded losslessly; see
         * {@link WebpAnimationEncoderOptions#isLossless()}. The quality is kept, and becomes the
         * effort when coding losslessly; key frame distances that were not set follow the mode.
         *
         * @param lossless the value
         * @return this builder
         */
        public Builder lossless(boolean lossless) {
            this.lossless = lossless;
            return this;
        }

        /**
         * Sets the lossy quality, or the effort when coding losslessly; see
         * {@link WebpAnimationEncoderOptions#quality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder quality(int quality) {
            this.quality = quality;
            return this;
        }

        /**
         * Sets the speed and size trade-off; see {@link WebpAnimationEncoderOptions#method()}.
         *
         * @param method the value
         * @return this builder
         */
        public Builder method(int method) {
            this.method = method;
            return this;
        }

        /**
         * Sets the key frame distances, which then no longer follow the coding mode; see
         * {@link WebpAnimationEncoderOptions#minKeyframeDistance()} and
         * {@link WebpAnimationEncoderOptions#maxKeyframeDistance()}.
         *
         * @param min the smallest number of frames between two key frames
         * @param max the largest number of frames between two key frames
         * @return this builder
         */
        public Builder keyframes(int min, int max) {
            this.keyframesSet = true;
            this.minKeyframeDistance = min;
            this.maxKeyframeDistance = max;
            return this;
        }

        /**
         * Inserts no key frame after the first frame, setting both distances to 0; see
         * {@link WebpAnimationEncoderOptions#maxKeyframeDistance()}.
         *
         * @return this builder
         */
        public Builder noKeyframes() {
            return keyframes(0, 0);
        }

        /**
         * Lets the key frame distances follow the coding mode; see
         * {@link WebpAnimationEncoderOptions#minKeyframeDistance()}.
         *
         * @return this builder
         */
        public Builder automaticKeyframes() {
            this.keyframesSet = false;
            this.minKeyframeDistance = 0;
            this.maxKeyframeDistance = 0;
            return this;
        }

        /**
         * Sets whether each frame may choose lossy or lossless coding; see
         * {@link WebpAnimationEncoderOptions#allowMixed()}.
         *
         * @param allowMixed the value
         * @return this builder
         */
        public Builder allowMixed(boolean allowMixed) {
            this.allowMixed = allowMixed;
            return this;
        }

        /**
         * Sets whether every disposal and coding combination is tried for the smallest file; see
         * {@link WebpAnimationEncoderOptions#minimize()}.
         *
         * @param minimize the value
         * @return this builder
         */
        public Builder minimize(boolean minimize) {
            this.minimize = minimize;
            return this;
        }

        /**
         * Sets the ICC profile embedded in the output; see
         * {@link WebpAnimationEncoderOptions#colorProfile()}.
         *
         * @param colorProfile the value
         * @return this builder
         */
        public Builder colorProfile(ColorProfile colorProfile) {
            this.colorProfile = Objects.requireNonNull(colorProfile, "colorProfile");
            return this;
        }

        /**
         * Removes the ICC profile; see {@link WebpAnimationEncoderOptions#colorProfile()}.
         *
         * @return this builder
         */
        public Builder noColorProfile() {
            this.colorProfile = null;
            return this;
        }

        /**
         * Sets the EXIF block; see {@link WebpAnimationEncoderOptions#exif()}.
         *
         * @param exif the value, with or without the {@code "Exif\0\0"} identifier JPEG puts in front of it,
         * which is removed; copied
         * @return this builder
         */
        public Builder exif(byte[] exif) {
            this.exif = TiffExif.stripExifPrefix(Objects.requireNonNull(exif, "exif")).clone();
            return this;
        }

        /**
         * Writes no EXIF block; see {@link WebpAnimationEncoderOptions#exif()}.
         *
         * @return this builder
         */
        public Builder noExif() {
            this.exif = null;
            return this;
        }

        /**
         * Sets the XMP packet; see {@link WebpAnimationEncoderOptions#xmp()}.
         *
         * @param xmp the value; copied
         * @return this builder
         */
        public Builder xmp(byte[] xmp) {
            this.xmp = Objects.requireNonNull(xmp, "xmp").clone();
            return this;
        }

        /**
         * Writes no XMP packet; see {@link WebpAnimationEncoderOptions#xmp()}.
         *
         * @return this builder
         */
        public Builder noXmp() {
            this.xmp = null;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the loop count is not in [0, 65535], the quality is not
         * in [0, 100], the method is not in [0, 6] or a key frame distance is negative
         */
        public WebpAnimationEncoderOptions build() {
            Checks.inRange("loop count", loopCount, 0, 0xFFFF);
            Checks.inRange("quality", quality, 0, 100);
            Checks.inRange("method", method, 0, 6);
            Checks.nonNegative("smallest key frame distance", minKeyframeDistance);
            Checks.nonNegative("largest key frame distance", maxKeyframeDistance);
            return new WebpAnimationEncoderOptions(this);
        }
    }
}
