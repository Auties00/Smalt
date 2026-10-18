package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a {@link JpegEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class JpegEncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final JpegEncoderOptions DEFAULTS = new Builder().build();

    private final int quality;

    private final ChromaFormat chromaFormat;

    private final boolean optimizeHuffman;

    private final int restartInterval;

    private final ColorProfile colorProfile;

    private JpegEncoderOptions(Builder builder) {
        this.quality = builder.quality;
        this.chromaFormat = builder.chromaFormat;
        this.optimizeHuffman = builder.optimizeHuffman;
        this.restartInterval = builder.restartInterval;
        this.colorProfile = builder.colorProfile;
    }

    /**
     * Returns quality 75 with 4:2:0 subsampling, optimized Huffman tables and no restart markers.
     *
     * @return the default options
     */
    public static JpegEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the {@link #defaults()} at another quality.
     *
     * @param quality the quality, from 0 (smallest) to 100 (best)
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is not in [0, 100]
     */
    public static JpegEncoderOptions ofQuality(int quality) {
        return new Builder().quality(quality).build();
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
     * {@return the quality, on the scale every Smalt image encoder shares: from 0 (smallest) to 100
     * (best); 75 by default}
     *
     * <p>The quality selects the quantization tables: the example tables of T.81 Annex K are scaled
     * by {@code 5000 / quality} percent below 50 and by {@code 200 - 2 * quality} percent from 50
     * up, rounded, and limited to 1..255 so the output stays baseline. Quality 50 uses the example
     * tables as they are, quality 100 uses all ones, and quality 0 is coded as quality 1.
     */
    public int quality() {
        return quality;
    }

    /**
     * {@return the chroma subsampling of the output: {@link ChromaFormat#MONOCHROME} for luma only,
     * {@link ChromaFormat#YUV420}, {@link ChromaFormat#YUV422} or {@link ChromaFormat#YUV444};
     * {@code YUV420} by default}
     */
    public ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /**
     * {@return whether the Huffman tables are optimized for the picture; {@code true} by default}
     *
     * <p>Optimized tables take a second pass and give a file a few percent smaller at the same
     * quality; otherwise the tables of T.81 Annex K are used in one pass.
     */
    public boolean optimizeHuffman() {
        return optimizeHuffman;
    }

    /**
     * {@return the number of MCUs between restart markers, from 1 to 65535, or 0 when no restart
     * marker is written; 0 by default}
     */
    public int restartInterval() {
        return restartInterval;
    }

    /**
     * {@return the ICC profile embedded in the output, or empty when none is embedded; empty by
     * default}
     *
     * <p>The profile tells readers which color space the pixels are in; it does not convert them.
     * Without one, readers take the pixels as sRGB. It is written as {@code APP2 ICC_PROFILE}
     * segments after the JFIF header. A high-dynamic-range profile, which has no ICC form, is
     * rejected.
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JpegEncoderOptions that
                && quality == that.quality
                && chromaFormat == that.chromaFormat
                && optimizeHuffman == that.optimizeHuffman
                && restartInterval == that.restartInterval
                && Objects.equals(colorProfile, that.colorProfile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(quality, chromaFormat, optimizeHuffman, restartInterval, colorProfile);
    }

    @Override
    public String toString() {
        return "JpegEncoderOptions[quality=" + quality + ", chromaFormat=" + chromaFormat + ", optimizeHuffman="
                + optimizeHuffman + ", restartInterval=" + restartInterval
                + (colorProfile == null ? "" : ", colorProfile=" + colorProfile.description().orElse("unnamed"))
                + "]";
    }

    /**
     * A builder of {@link JpegEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and the color
     * profile. Instances are not thread-safe.
     */
    public static final class Builder {
        private int quality = 75;

        private ChromaFormat chromaFormat = ChromaFormat.YUV420;

        private boolean optimizeHuffman = true;

        private int restartInterval;

        private ColorProfile colorProfile;

        private Builder() {
        }

        private Builder(JpegEncoderOptions options) {
            this.quality = options.quality;
            this.chromaFormat = options.chromaFormat;
            this.optimizeHuffman = options.optimizeHuffman;
            this.restartInterval = options.restartInterval;
            this.colorProfile = options.colorProfile;
        }

        /**
         * Sets the quality; see {@link JpegEncoderOptions#quality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder quality(int quality) {
            this.quality = quality;
            return this;
        }

        /**
         * Sets the chroma subsampling of the output; see {@link JpegEncoderOptions#chromaFormat()}.
         *
         * @param chromaFormat the value
         * @return this builder
         */
        public Builder chromaFormat(ChromaFormat chromaFormat) {
            this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
            return this;
        }

        /**
         * Sets whether the Huffman tables are optimized for the picture; see
         * {@link JpegEncoderOptions#optimizeHuffman()}.
         *
         * @param optimizeHuffman the value
         * @return this builder
         */
        public Builder optimizeHuffman(boolean optimizeHuffman) {
            this.optimizeHuffman = optimizeHuffman;
            return this;
        }

        /**
         * Sets the number of MCUs between restart markers; see
         * {@link JpegEncoderOptions#restartInterval()}.
         *
         * @param restartInterval the value
         * @return this builder
         */
        public Builder restartInterval(int restartInterval) {
            this.restartInterval = restartInterval;
            return this;
        }

        /**
         * Sets the ICC profile embedded in the output; see {@link JpegEncoderOptions#colorProfile()}.
         *
         * @param colorProfile the value, or {@code null} for none
         * @return this builder
         */
        public Builder colorProfile(ColorProfile colorProfile) {
            this.colorProfile = colorProfile;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the quality is not in [0, 100] or the restart interval
         * is not in [0, 65535]
         * @throws MediaException.Unsupported if the color profile has no ICC form (a
         * high-dynamic-range profile)
         */
        public JpegEncoderOptions build() {
            Checks.inRange("quality", quality, 0, 100);
            Checks.inRange("restart interval", restartInterval, 0, 0xFFFF);
            if (colorProfile != null && colorProfile.isHighDynamicRange()) {
                throw new MediaException.Unsupported("icc", "a high-dynamic-range profile has no ICC form");
            }
            return new JpegEncoderOptions(this);
        }
    }
}
