package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.tiff.TiffExif;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a {@link JxlEncoder} and a {@link JxlAnimationEncoder}. Instances are immutable and
 * thread-safe; create them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class JxlEncoderOptions {
    /**
     * The largest distance, the most lossy coding.
     */
    public static final float MAX_DISTANCE = 25.0f;

    /**
     * The default distance, visually lossless for most images.
     */
    public static final float DEFAULT_DISTANCE = 1.0f;

    /**
     * The default effort.
     */
    public static final int DEFAULT_EFFORT = 7;

    private static final JxlEncoderOptions DEFAULTS = new Builder().build();

    private static final JxlEncoderOptions LOSSLESS = new Builder().lossless(true).build();

    private final boolean lossless;

    private final float distance;

    private final int effort;

    private final ColorProfile colorProfile;

    private final byte[] exif;

    private final byte[] xmp;

    private final Orientation orientation;

    private final boolean container;

    private final int loopCount;

    private JxlEncoderOptions(Builder builder) {
        this.lossless = builder.lossless;
        this.distance = builder.distance;
        this.effort = builder.effort;
        this.colorProfile = builder.colorProfile;
        this.exif = builder.exif;
        this.xmp = builder.xmp;
        this.orientation = builder.orientation;
        this.container = builder.container;
        this.loopCount = builder.loopCount;
    }

    /**
     * Returns lossy coding at distance {@value #DEFAULT_DISTANCE} and effort {@value #DEFAULT_EFFORT}, the
     * defaults of libjxl's {@code cjxl}.
     *
     * @return the default options
     */
    public static JxlEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns lossless coding at effort {@value #DEFAULT_EFFORT}.
     *
     * @return the options
     */
    public static JxlEncoderOptions lossless() {
        return LOSSLESS;
    }

    /**
     * Returns the {@link #defaults()} at another distance.
     *
     * @param distance the distance, above 0 and at most {@value #MAX_DISTANCE}
     * @return the options
     * @throws IllegalArgumentException if {@code distance} is out of range
     */
    public static JxlEncoderOptions ofDistance(float distance) {
        return new Builder().distance(distance).build();
    }

    /**
     * Returns the {@link #defaults()} at the distance of a quality on the scale of {@code cjxl -q}: 100 is
     * lossless, 90 is distance 1, and lower qualities give larger distances.
     *
     * @param quality the quality, from 0 to 100
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is not in [0, 100]
     */
    public static JxlEncoderOptions ofQuality(int quality) {
        Checks.inRange("quality", quality, 0, 100);
        if (quality == 100) {
            return LOSSLESS;
        }
        return new Builder().distance(distanceOf(quality)).build();
    }

    /**
     * Returns the distance of a quality on the scale of {@code cjxl -q}.
     *
     * @param quality the quality, from 0 to 99
     * @return the distance
     */
    static float distanceOf(int quality) {
        if (quality >= 30) {
            return 0.1f + (100 - quality) * 0.09f;
        }
        return Math.min(MAX_DISTANCE, 53.0f / 3000.0f * quality * quality - 23.0f / 20.0f * quality + 25.0f);
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
     * {@return whether the color channels are coded losslessly, as a modular image, rather than lossily in XYB
     * with VarDCT; {@code false} by default}
     *
     * <p>Extra channels, alpha included, are always coded losslessly.
     */
    public boolean isLossless() {
        return lossless;
    }

    /**
     * {@return the lossy distance, the target Butteraugli distance between the image and its decode, above 0
     * and at most {@value #MAX_DISTANCE}; {@value #DEFAULT_DISTANCE} by default}
     *
     * <p>Larger distances give smaller files; 1 is about the threshold of a visible difference at a normal
     * viewing distance. Lossless coding ignores it.
     */
    public float distance() {
        return distance;
    }

    /**
     * {@return the effort, from 1 (fastest) to 10 (smallest files), with the meaning of {@code cjxl -e};
     * {@value #DEFAULT_EFFORT} by default}
     *
     * <p>Lossless coding at effort 1 and 2 uses a fixed context model and no tree learning, the fast lossless
     * path; higher efforts learn the context tree from more properties and search more transforms. Lossy
     * coding enables the Gaborish filter and finer quantization adaptation from effort 5.
     */
    public int effort() {
        return effort;
    }

    /**
     * {@return the color space of the pixels, or empty when they are sRGB; empty by default}
     *
     * <p>An RGB sRGB profile, and a high-dynamic-range PQ or HLG profile with BT.709, BT.2020 or Display P3
     * primaries, are written as enumerated JPEG XL color encodings; any other profile is embedded as an ICC
     * profile.
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    /**
     * {@return the EXIF block written in an {@code Exif} box, a TIFF header and its directories, or empty for
     * none; empty by default}
     */
    public Optional<byte[]> exif() {
        return Optional.ofNullable(exif).map(byte[]::clone);
    }

    /**
     * {@return the XMP packet written in an {@code xml } box, or empty for none; empty by default}
     */
    public Optional<byte[]> xmp() {
        return Optional.ofNullable(xmp).map(byte[]::clone);
    }

    /**
     * {@return the orientation recorded in the codestream header, which decoders apply when displaying the
     * pixels; {@link Orientation#NORMAL} by default}
     */
    public Orientation orientation() {
        return orientation;
    }

    /**
     * {@return whether the file is always an ISO base media file format container; {@code false} by default}
     *
     * <p>Otherwise a bare codestream is written unless EXIF or XMP metadata needs a container, or the image needs
     * conformance level 10 (lossless samples of more than 12 bits, floating-point samples, more than 4 extra
     * channels, CMYK, sides over 2^18 pixels), which a {@code jxll} box in a container declares.
     */
    public boolean container() {
        return container;
    }

    /**
     * {@return the number of times an animation plays, at least 0, where 0 means forever; 0 by default}
     *
     * <p>Still images ignore it.
     */
    public int loopCount() {
        return loopCount;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JxlEncoderOptions o && lossless == o.lossless
                && Float.compare(distance, o.distance) == 0 && effort == o.effort
                && Objects.equals(colorProfile, o.colorProfile) && Arrays.equals(exif, o.exif)
                && Arrays.equals(xmp, o.xmp) && orientation == o.orientation && container == o.container
                && loopCount == o.loopCount;
    }

    @Override
    public int hashCode() {
        var h = Objects.hash(lossless, distance, effort, colorProfile, orientation, container, loopCount);
        return 31 * (31 * h + Arrays.hashCode(exif)) + Arrays.hashCode(xmp);
    }

    @Override
    public String toString() {
        return "JxlEncoderOptions[lossless=" + lossless + ", distance=" + distance + ", effort=" + effort
                + ", colorProfile=" + (colorProfile != null) + ", exif=" + (exif != null ? exif.length : 0)
                + ", xmp=" + (xmp != null ? xmp.length : 0) + ", orientation=" + orientation + ", container="
                + container + ", loopCount=" + loopCount + "]";
    }

    /**
     * A builder of {@link JxlEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private boolean lossless;

        private float distance = DEFAULT_DISTANCE;

        private int effort = DEFAULT_EFFORT;

        private ColorProfile colorProfile;

        private byte[] exif;

        private byte[] xmp;

        private Orientation orientation = Orientation.NORMAL;

        private boolean container;

        private int loopCount;

        private Builder() {
        }

        private Builder(JxlEncoderOptions options) {
            this.lossless = options.lossless;
            this.distance = options.distance;
            this.effort = options.effort;
            this.colorProfile = options.colorProfile;
            this.exif = options.exif;
            this.xmp = options.xmp;
            this.orientation = options.orientation;
            this.container = options.container;
            this.loopCount = options.loopCount;
        }

        /**
         * Sets whether the color channels are coded losslessly; see {@link JxlEncoderOptions#isLossless()}.
         *
         * @param lossless the value
         * @return this builder
         */
        public Builder lossless(boolean lossless) {
            this.lossless = lossless;
            return this;
        }

        /**
         * Sets the lossy distance; see {@link JxlEncoderOptions#distance()}.
         *
         * @param distance the value
         * @return this builder
         */
        public Builder distance(float distance) {
            this.distance = distance;
            return this;
        }

        /**
         * Sets the effort; see {@link JxlEncoderOptions#effort()}.
         *
         * @param effort the value
         * @return this builder
         */
        public Builder effort(int effort) {
            this.effort = effort;
            return this;
        }

        /**
         * Sets the color space of the pixels; see {@link JxlEncoderOptions#colorProfile()}.
         *
         * @param colorProfile the value
         * @return this builder
         */
        public Builder colorProfile(ColorProfile colorProfile) {
            this.colorProfile = Objects.requireNonNull(colorProfile, "colorProfile");
            return this;
        }

        /**
         * Removes the color profile, so the pixels are sRGB; see {@link JxlEncoderOptions#colorProfile()}.
         *
         * @return this builder
         */
        public Builder noColorProfile() {
            this.colorProfile = null;
            return this;
        }

        /**
         * Sets the EXIF block; see {@link JxlEncoderOptions#exif()}.
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
         * Removes the EXIF block; see {@link JxlEncoderOptions#exif()}.
         *
         * @return this builder
         */
        public Builder noExif() {
            this.exif = null;
            return this;
        }

        /**
         * Sets the XMP packet; see {@link JxlEncoderOptions#xmp()}.
         *
         * @param xmp the value, copied
         * @return this builder
         */
        public Builder xmp(byte[] xmp) {
            this.xmp = Objects.requireNonNull(xmp, "xmp").clone();
            return this;
        }

        /**
         * Removes the XMP packet; see {@link JxlEncoderOptions#xmp()}.
         *
         * @return this builder
         */
        public Builder noXmp() {
            this.xmp = null;
            return this;
        }

        /**
         * Sets the orientation; see {@link JxlEncoderOptions#orientation()}.
         *
         * @param orientation the value
         * @return this builder
         */
        public Builder orientation(Orientation orientation) {
            this.orientation = Objects.requireNonNull(orientation, "orientation");
            return this;
        }

        /**
         * Sets whether the file is always a container; see {@link JxlEncoderOptions#container()}.
         *
         * @param container the value
         * @return this builder
         */
        public Builder container(boolean container) {
            this.container = container;
            return this;
        }

        /**
         * Sets the loop count of an animation; see {@link JxlEncoderOptions#loopCount()}.
         *
         * @param loopCount the value
         * @return this builder
         */
        public Builder loopCount(int loopCount) {
            this.loopCount = loopCount;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the distance is not in (0, {@value #MAX_DISTANCE}], the effort is
         * not in [1, 10] or the loop count is negative
         */
        public JxlEncoderOptions build() {
            if (!(distance > 0) || distance > MAX_DISTANCE) {
                throw new IllegalArgumentException("distance " + distance + " is not in (0, " + MAX_DISTANCE + "]");
            }
            Checks.inRange("effort", effort, 1, 10);
            if (loopCount < 0) {
                throw new IllegalArgumentException("negative loop count " + loopCount);
            }
            return new JxlEncoderOptions(this);
        }
    }
}
