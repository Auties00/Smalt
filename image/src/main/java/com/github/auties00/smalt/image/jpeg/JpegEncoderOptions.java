package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

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

    private final int chromaQuality;

    private final ChromaFormat chromaFormat;

    private final boolean optimizeHuffman;

    private final int restartInterval;

    private final ColorProfile colorProfile;

    private final ColorSpace colorSpace;

    private final boolean progressive;

    private final boolean arithmetic;

    private final boolean lossless;

    private final int predictor;

    private final int pointTransform;

    private final int precision;

    private final List<Sampling> samplingFactors;

    private final List<QuantizationTable> quantizationTables;

    private final List<Integer> quantizationSlots;

    private final boolean baselineQuantization;

    private final List<Scan> scanScript;

    private final Density density;

    private JpegEncoderOptions(Builder builder) {
        this.quality = builder.quality;
        this.chromaQuality = builder.chromaQuality.orElse(-1);
        this.chromaFormat = builder.chromaFormat;
        this.optimizeHuffman = builder.optimizeHuffman;
        this.restartInterval = builder.restartInterval;
        this.colorProfile = builder.colorProfile;
        this.colorSpace = builder.colorSpace;
        this.progressive = builder.progressive;
        this.arithmetic = builder.arithmetic;
        this.lossless = builder.lossless;
        this.predictor = builder.predictor;
        this.pointTransform = builder.pointTransform;
        this.precision = builder.precision.orElse(0);
        this.samplingFactors = List.copyOf(builder.samplingFactors);
        this.quantizationTables = List.copyOf(builder.quantizationTables);
        this.quantizationSlots = List.copyOf(builder.quantizationSlots);
        this.baselineQuantization = builder.baselineQuantization;
        this.scanScript = List.copyOf(builder.scanScript);
        this.density = builder.density;
    }

    /**
     * Returns quality 75 with 4:2:0 subsampling, optimized Huffman tables and no restart markers, as a
     * baseline JFIF file.
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
     * Returns the {@link #defaults()} as a progressive file (T.81 Annex G) with the default scan script.
     *
     * @param quality the quality, from 0 (smallest) to 100 (best)
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is not in [0, 100]
     */
    public static JpegEncoderOptions ofProgressive(int quality) {
        return new Builder().quality(quality).progressive(true).build();
    }

    /**
     * Returns lossless coding (T.81 Annex H) in {@link ColorSpace#RGB} with predictor 1, no point transform and
     * the picture's own precision, which keeps every sample of an RGB or gray picture.
     *
     * @apiNote A planar Y'CbCr picture keeps its samples in {@link ColorSpace#YCBCR} with its own
     * {@link #chromaFormat()}; {@link com.github.auties00.smalt.image.Images#encode Images.encode} with
     * {@link com.github.auties00.smalt.image.ImageEncoderOptions#lossless()} chooses the color space for the
     * picture.
     * @return the options
     */
    public static JpegEncoderOptions ofLossless() {
        return new Builder().lossless(true).colorSpace(ColorSpace.RGB).build();
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
     * <p>The quality scales the quantization table of slot 0 (luminance) and, unless
     * {@link #chromaQuality()} is set, those of the other slots: each table, the example tables of T.81
     * Annex K or {@link #quantizationTables()}, is scaled by {@code 5000 / quality} percent below 50
     * and by {@code 200 - 2 * quality} percent from 50 up, rounded, and limited as
     * {@link #baselineQuantization()} says. Quality 50 uses the tables as they are, quality 100 uses
     * all ones, and quality 0 is coded as quality 1. A lossless coding has no quantization tables and
     * ignores it.
     */
    public int quality() {
        return quality;
    }

    /**
     * {@return the quality of the quantization tables of slots 1 to 3, the slots the chrominance
     * components use, from 0 to 100, or empty when they use {@link #quality()}; empty by default}
     */
    public OptionalInt chromaQuality() {
        return chromaQuality < 0 ? OptionalInt.empty() : OptionalInt.of(chromaQuality);
    }

    /**
     * {@return the chroma subsampling of a {@link ColorSpace#YCBCR} or {@link ColorSpace#YCCK} output:
     * {@link ChromaFormat#MONOCHROME} for a luma-only (grayscale) file, {@link ChromaFormat#YUV420},
     * {@link ChromaFormat#YUV422} or {@link ChromaFormat#YUV444}; {@code YUV420} by default}
     *
     * <p>A YCCK output samples its black component like luma. {@link #samplingFactors()}, when set,
     * replaces the factors this implies; an {@link ColorSpace#RGB} or {@link ColorSpace#CMYK} output
     * ignores it and takes {@code MONOCHROME} as an error.
     */
    public ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /**
     * {@return whether the Huffman tables are optimized for the picture; {@code true} by default}
     *
     * <p>Optimized tables take a second pass and give a smaller file at the same quality; otherwise
     * the tables of T.81 Annex K are used in one pass. Progressive, lossless and 12-bit Huffman files
     * are always optimized, since the Annex K tables cannot code them, and arithmetic coding has no
     * tables.
     */
    public boolean optimizeHuffman() {
        return optimizeHuffman;
    }

    /**
     * {@return the number of MCUs between restart markers, from 1 to 65535, or 0 when no restart
     * marker is written; 0 by default}
     *
     * <p>The interval counts the MCUs of each scan: in a scan of one component an MCU is one block (one
     * sample in a lossless file). A lossless file needs an interval of whole MCU rows.
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
     * segments after the JFIF or Adobe header. A high-dynamic-range profile is written in its ICC
     * form, whose {@code cicp} tag carries its PQ or HLG code points.
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    /**
     * {@return the color space of the coded components; {@link ColorSpace#YCBCR} by default}
     */
    public ColorSpace colorSpace() {
        return colorSpace;
    }

    /**
     * {@return whether the file is progressive (T.81 Annex G, {@code SOF2} or {@code SOF10}); {@code false}
     * by default}
     *
     * <p>Without a {@link #scanScript()} a progressive file uses a script of ten scans for YCbCr and of
     * {@code 2 + 4 * n} scans for {@code n} other components: a first DC scan at one bit less, the low and
     * then the high AC frequencies at two bits less, one AC refinement scan, the last DC bit and the last
     * AC bits. It cannot be combined with {@link #lossless()}.
     */
    public boolean progressive() {
        return progressive;
    }

    /**
     * {@return whether the entropy coding is arithmetic (T.81 Annex D: {@code SOF9}, {@code SOF10} or
     * {@code SOF11}) rather than Huffman; {@code false} by default}
     *
     * <p>Arithmetic coding gives a smaller file than optimized Huffman tables, in one pass, with the
     * default conditioning of T.81; fewer readers decode it.
     */
    public boolean arithmetic() {
        return arithmetic;
    }

    /**
     * {@return whether the file is lossless (T.81 Annex H, {@code SOF3} or {@code SOF11}); {@code false}
     * by default}
     *
     * <p>A lossless file codes the samples of each component exactly, at {@link #precision()} bits, after
     * the {@link #pointTransform()}; the color conversion and subsampling of {@link #colorSpace()} and
     * {@link #samplingFactors()} still apply, so a picture keeps all its samples only when it needs
     * neither: an RGB picture coded as {@link ColorSpace#RGB} with all factors 1, or a full-range BT.601
     * planar picture coded in its own subsampling. Quality and quantization settings are ignored.
     */
    public boolean lossless() {
        return lossless;
    }

    /**
     * {@return the predictor of a lossless file, from 1 to 7 (T.81 Table H.1): 1 left, 2 above, 3 above
     * left, 4 {@code left + above - above left}, 5 {@code left + (above - above left) / 2}, 6
     * {@code above + (left - above left) / 2} and 7 {@code (left + above) / 2}; 1 by default}
     */
    public int predictor() {
        return predictor;
    }

    /**
     * {@return the point transform of a lossless file: the number of low bits dropped from every sample,
     * from 0 to 15 and below the precision; 0 by default}
     */
    public int pointTransform() {
        return pointTransform;
    }

    /**
     * {@return the sample precision in bits, or empty for the automatic choice; empty by default}
     *
     * <p>A DCT file has 8 or 12 bits, a lossless one 2 to 16. The automatic choice is 8 bits for a DCT
     * file and, for a lossless one, the picture's bit depth: 8 for an 8-bit picture, the
     * {@link com.github.auties00.smalt.image.Yuv16Picture#bitDepth() bit depth} of a 16-bit planar
     * picture and 16 for the other 16-bit and floating-point pictures. Samples of another depth are
     * rescaled as {@code round(v * (2^precision - 1) / (2^depth - 1))}, except that a 16-bit RGBA picture, or a
     * 16-bit planar one with JPEG's full-range BT.601 samples, coded at 8 bits is first reduced with
     * {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit(com.github.auties00.smalt.image.Picture)
     * ImageOps.toEightBit}.
     */
    public OptionalInt precision() {
        return precision == 0 ? OptionalInt.empty() : OptionalInt.of(precision);
    }

    /**
     * {@return the sampling factors of the components in frame order, or empty for those of
     * {@link #chromaFormat()} and {@link #colorSpace()}; empty by default}
     *
     * <p>Components past the end of the list take factors 1 by 1, so {@code [2x2]} codes 4:2:0 and
     * {@code [1x2]} 4:4:0. Every factor of a component must divide the largest factor in its
     * direction, and the MCU of a scan of several components, the sum of {@code horizontal * vertical} over
     * them, holds at most 10 data units (blocks, or samples in a lossless file).
     */
    public List<Sampling> samplingFactors() {
        return samplingFactors;
    }

    /**
     * {@return the base quantization tables of slots 0 to 3, slot {@code i} at index {@code i}, scaled by
     * the slot's quality like the tables of T.81 Annex K, or empty for the Annex K tables; empty by
     * default}
     *
     * <p>A slot past the end of the list keeps its Annex K table, luminance for slot 0 and chrominance
     * for slot 1; slots 2 and 3 exist only when the list gives them. At quality 50 a table is used as
     * given.
     */
    public List<QuantizationTable> quantizationTables() {
        return quantizationTables;
    }

    /**
     * {@return the quantization table slot of each component in frame order, or empty for the slots of
     * {@link #colorSpace()}; empty by default}
     *
     * <p>Components past the end of the list take its last slot. Without a list, luma and black use slot
     * 0, chroma slot 1, and the components of {@link ColorSpace#RGB} and {@link ColorSpace#CMYK} slot 0.
     */
    public List<Integer> quantizationSlots() {
        return quantizationSlots;
    }

    /**
     * {@return whether scaled quantizers are limited to 255, so that an 8-bit sequential Huffman file stays
     * baseline ({@code SOF0}); {@code true} by default}
     *
     * <p>Otherwise quantizers up to 32767 are kept, written as 16-bit tables, and such a file becomes
     * extended sequential ({@code SOF1}).
     */
    public boolean baselineQuantization() {
        return baselineQuantization;
    }

    /**
     * {@return the scans of the file, in order, or empty for the default script of the coding process;
     * empty by default}
     *
     * <p>The script must suit the process. In a sequential file every scan codes the whole spectrum
     * ({@code 0..63}, no successive approximation) and each component appears in exactly one scan. In a
     * progressive file a scan codes either DC coefficients of up to four components or one AC band of one
     * component, a component's AC bands follow its first DC scan, the first scan of a coefficient has
     * {@code successiveHigh} 0 and each later one {@code successiveHigh} equal to the previous
     * {@code successiveLow} and {@code successiveLow} one less, with bit positions up to 10 at 8 bits and
     * 13 at 12 bits, and every component has DC data. In a lossless file every scan has a predictor (1 to
     * 7) as {@code spectralStart}, {@code spectralEnd} 0 and the point transform as {@code successiveLow},
     * and each component appears in exactly one scan; {@link #predictor()} and {@link #pointTransform()}
     * then apply only to the default script. Within a scan the components are in frame order, at most
     * four.
     */
    public List<Scan> scanScript() {
        return scanScript;
    }

    /**
     * {@return the pixel density of the JFIF header; {@link Density#ofAspectRatio(int, int) an aspect
     * ratio of 1:1} by default}
     *
     * <p>Only {@link ColorSpace#YCBCR} files (grayscale included) carry a JFIF header; the other color
     * spaces ignore it, and so does a lossless 9-bit YCbCr file whose three components are all sampled 1x1,
     * which carries an Adobe {@code APP14} header instead.
     */
    public Density density() {
        return density;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JpegEncoderOptions that
                && quality == that.quality
                && chromaQuality == that.chromaQuality
                && chromaFormat == that.chromaFormat
                && optimizeHuffman == that.optimizeHuffman
                && restartInterval == that.restartInterval
                && Objects.equals(colorProfile, that.colorProfile)
                && colorSpace == that.colorSpace
                && progressive == that.progressive
                && arithmetic == that.arithmetic
                && lossless == that.lossless
                && predictor == that.predictor
                && pointTransform == that.pointTransform
                && precision == that.precision
                && samplingFactors.equals(that.samplingFactors)
                && quantizationTables.equals(that.quantizationTables)
                && quantizationSlots.equals(that.quantizationSlots)
                && baselineQuantization == that.baselineQuantization
                && scanScript.equals(that.scanScript)
                && density.equals(that.density);
    }

    @Override
    public int hashCode() {
        return Objects.hash(quality, chromaQuality, chromaFormat, optimizeHuffman, restartInterval, colorProfile,
                colorSpace, progressive, arithmetic, lossless, predictor, pointTransform, precision, samplingFactors,
                quantizationTables, quantizationSlots, baselineQuantization, scanScript, density);
    }

    @Override
    public String toString() {
        var text = new StringBuilder("JpegEncoderOptions[quality=").append(quality);
        if (chromaQuality >= 0) {
            text.append(", chromaQuality=").append(chromaQuality);
        }
        text.append(", chromaFormat=").append(chromaFormat)
                .append(", optimizeHuffman=").append(optimizeHuffman)
                .append(", restartInterval=").append(restartInterval);
        if (colorProfile != null) {
            text.append(", colorProfile=").append(colorProfile.description().orElse("unnamed"));
        }
        text.append(", colorSpace=").append(colorSpace);
        if (progressive) {
            text.append(", progressive");
        }
        if (arithmetic) {
            text.append(", arithmetic");
        }
        if (lossless) {
            text.append(", lossless, predictor=").append(predictor).append(", pointTransform=").append(pointTransform);
        }
        if (precision != 0) {
            text.append(", precision=").append(precision);
        }
        if (!samplingFactors.isEmpty()) {
            text.append(", samplingFactors=").append(samplingFactors);
        }
        if (!quantizationTables.isEmpty()) {
            text.append(", quantizationTables=").append(quantizationTables.size());
        }
        if (!quantizationSlots.isEmpty()) {
            text.append(", quantizationSlots=").append(quantizationSlots);
        }
        if (!baselineQuantization) {
            text.append(", baselineQuantization=false");
        }
        if (!scanScript.isEmpty()) {
            text.append(", scanScript=").append(scanScript.size()).append(" scans");
        }
        if (!density.equals(Density.DEFAULT)) {
            text.append(", density=").append(density);
        }
        return text.append(']').toString();
    }

    /**
     * Returns the number of components a frame of these options has.
     *
     * @return 1 for grayscale, 3 for YCbCr and RGB, 4 for CMYK and YCCK
     */
    int componentCount() {
        return switch (colorSpace) {
            case YCBCR -> chromaFormat.hasChroma() ? 3 : 1;
            case RGB -> 3;
            case CMYK, YCCK -> 4;
        };
    }

    /**
     * The color space of the coded components, and the marker that identifies it.
     */
    public enum ColorSpace {
        /**
         * Luma and two color differences (full-range BT.601), or luma alone with
         * {@link ChromaFormat#MONOCHROME}: the JFIF color space, with a JFIF {@code APP0} header.
         */
        YCBCR,

        /**
         * Red, green and blue without a transform, with component identifiers {@code 'R'}, {@code 'G'} and
         * {@code 'B'} and an Adobe {@code APP14} header (transform 0).
         */
        RGB,

        /**
         * Cyan, magenta, yellow and black, stored inverted (255 for no ink at 8 bits, as Adobe stores it),
         * with component identifiers {@code 'C'}, {@code 'M'}, {@code 'Y'} and {@code 'K'} and an Adobe
         * {@code APP14} header (transform 0).
         */
        CMYK,

        /**
         * The inverted cyan, magenta and yellow of {@link #CMYK} transformed to YCbCr, and black, with an Adobe
         * {@code APP14} header (transform 2).
         */
        YCCK
    }

    /**
     * The horizontal and vertical sampling factors of one component.
     *
     * @param horizontal the horizontal factor, from 1 to 4
     * @param vertical the vertical factor, from 1 to 4
     */
    public record Sampling(int horizontal, int vertical) {
        /**
         * Constructs the factors.
         *
         * @throws IllegalArgumentException if a factor is not in [1, 4]
         */
        public Sampling {
            Checks.inRange("horizontal sampling factor", horizontal, 1, 4);
            Checks.inRange("vertical sampling factor", vertical, 1, 4);
        }

        /**
         * Returns the factors of one component.
         *
         * @param horizontal the horizontal factor, from 1 to 4
         * @param vertical the vertical factor, from 1 to 4
         * @return the factors
         * @throws IllegalArgumentException if a factor is not in [1, 4]
         */
        public static Sampling of(int horizontal, int vertical) {
            return new Sampling(horizontal, vertical);
        }

        @Override
        public String toString() {
            return horizontal + "x" + vertical;
        }
    }

    /**
     * A base quantization table: 64 quantizers in natural (row-major) order, from 1 to 32767.
     *
     * @param values the quantizers, natural order
     */
    public record QuantizationTable(List<Integer> values) {
        /**
         * The example luminance table of T.81 Annex K (Table K.1).
         */
        public static final QuantizationTable ANNEX_K_LUMINANCE = of(
                16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
                14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
                18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
                49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99);

        /**
         * The example chrominance table of T.81 Annex K (Table K.2).
         */
        public static final QuantizationTable ANNEX_K_CHROMINANCE = of(
                17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
                24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
                99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
                99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99);

        /**
         * Constructs a table.
         *
         * @throws NullPointerException if a value is {@code null}
         * @throws IllegalArgumentException if there are not 64 values or one is not in [1, 32767]
         */
        public QuantizationTable {
            values = List.copyOf(values);
            if (values.size() != 64) {
                throw new IllegalArgumentException("a quantization table has 64 values, not " + values.size());
            }
            for (var value : values) {
                Checks.inRange("quantizer", value, 1, 32767);
            }
        }

        /**
         * Returns a table of 64 quantizers.
         *
         * @param values the quantizers in natural (row-major) order
         * @return the table
         * @throws IllegalArgumentException if there are not 64 values or one is not in [1, 32767]
         */
        public static QuantizationTable of(int... values) {
            return new QuantizationTable(Arrays.stream(values).boxed().toList());
        }

        /**
         * Returns the quantizers as an array.
         *
         * @return a new array of the 64 quantizers, natural order
         */
        public int[] toArray() {
            var result = new int[64];
            for (var i = 0; i < 64; i++) {
                result[i] = values.get(i);
            }
            return result;
        }
    }

    /**
     * One scan of a {@link #scanScript() scan script} (T.81 B.2.3).
     *
     * @param components the frame indexes of the components the scan codes, from 0, increasing, one to four
     * @param spectralStart the first coefficient of the band ({@code Ss}), or the predictor of a lossless scan
     * @param spectralEnd the last coefficient of the band ({@code Se}), 0 in a lossless scan
     * @param successiveHigh the bit position of the previous scan of the band ({@code Ah}), 0 for its first
     * scan
     * @param successiveLow the bit position of this scan ({@code Al}), or the point transform of a lossless
     * scan
     */
    public record Scan(List<Integer> components, int spectralStart, int spectralEnd, int successiveHigh,
                       int successiveLow) {
        /**
         * Constructs a scan.
         *
         * @throws NullPointerException if a component index is {@code null}
         * @throws IllegalArgumentException if there are not one to four components in increasing order from
         * 0, {@code spectralStart} or {@code spectralEnd} is not in [0, 63], or {@code successiveHigh} or
         * {@code successiveLow} is not in [0, 15]
         */
        public Scan {
            components = List.copyOf(components);
            if (components.isEmpty() || components.size() > 4) {
                throw new IllegalArgumentException("a scan has one to four components, not " + components.size());
            }
            var previous = -1;
            for (var component : components) {
                if (component <= previous) {
                    throw new IllegalArgumentException("scan components must increase from 0: " + components);
                }
                previous = component;
            }
            Checks.inRange("spectral start", spectralStart, 0, 63);
            Checks.inRange("spectral end", spectralEnd, 0, 63);
            Checks.inRange("successive approximation high bit", successiveHigh, 0, 15);
            Checks.inRange("successive approximation low bit", successiveLow, 0, 15);
        }

        /**
         * Returns a scan.
         *
         * @param spectralStart the first coefficient of the band, or the predictor of a lossless scan
         * @param spectralEnd the last coefficient of the band, 0 in a lossless scan
         * @param successiveHigh the bit position of the previous scan of the band, 0 for its first scan
         * @param successiveLow the bit position of this scan, or the point transform of a lossless scan
         * @param components the frame indexes of the components, increasing from 0, one to four
         * @return the scan
         * @throws IllegalArgumentException if a value is out of range, as for the canonical constructor
         */
        public static Scan of(int spectralStart, int spectralEnd, int successiveHigh, int successiveLow,
                              int... components) {
            return new Scan(Arrays.stream(components).boxed().toList(), spectralStart, spectralEnd, successiveHigh,
                    successiveLow);
        }
    }

    /**
     * The unit of a JFIF {@link Density}.
     */
    public enum DensityUnit {
        /**
         * No unit: the values give only the pixel aspect ratio (JFIF units 0).
         */
        ASPECT_RATIO,

        /**
         * Dots per inch (JFIF units 1).
         */
        DOTS_PER_INCH,

        /**
         * Dots per centimeter (JFIF units 2).
         */
        DOTS_PER_CENTIMETER
    }

    /**
     * The pixel density of a JFIF header.
     *
     * @param unit the unit of the values
     * @param horizontal the horizontal density, from 1 to 65535
     * @param vertical the vertical density, from 1 to 65535
     */
    public record Density(DensityUnit unit, int horizontal, int vertical) {
        /**
         * The square-pixel aspect ratio, the default of {@link #density()}.
         */
        private static final Density DEFAULT = new Density(DensityUnit.ASPECT_RATIO, 1, 1);

        /**
         * Constructs a density.
         *
         * @throws IllegalArgumentException if a value is not in [1, 65535]
         */
        public Density {
            Objects.requireNonNull(unit, "unit");
            Checks.inRange("horizontal density", horizontal, 1, 0xFFFF);
            Checks.inRange("vertical density", vertical, 1, 0xFFFF);
        }

        /**
         * Returns a pixel aspect ratio without a physical density.
         *
         * @param horizontal the horizontal value, from 1 to 65535
         * @param vertical the vertical value, from 1 to 65535
         * @return the density
         * @throws IllegalArgumentException if a value is not in [1, 65535]
         */
        public static Density ofAspectRatio(int horizontal, int vertical) {
            return new Density(DensityUnit.ASPECT_RATIO, horizontal, vertical);
        }

        /**
         * Returns a density in dots per inch.
         *
         * @param horizontal the horizontal density, from 1 to 65535
         * @param vertical the vertical density, from 1 to 65535
         * @return the density
         * @throws IllegalArgumentException if a value is not in [1, 65535]
         */
        public static Density ofDotsPerInch(int horizontal, int vertical) {
            return new Density(DensityUnit.DOTS_PER_INCH, horizontal, vertical);
        }

        /**
         * Returns a density in dots per centimeter.
         *
         * @param horizontal the horizontal density, from 1 to 65535
         * @param vertical the vertical density, from 1 to 65535
         * @return the density
         * @throws IllegalArgumentException if a value is not in [1, 65535]
         */
        public static Density ofDotsPerCentimeter(int horizontal, int vertical) {
            return new Density(DensityUnit.DOTS_PER_CENTIMETER, horizontal, vertical);
        }
    }

    /**
     * A builder of {@link JpegEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and how the settings
     * combine. Instances are not thread-safe.
     */
    public static final class Builder {
        private int quality = 75;

        private OptionalInt chromaQuality = OptionalInt.empty();

        private ChromaFormat chromaFormat = ChromaFormat.YUV420;

        private boolean optimizeHuffman = true;

        private int restartInterval;

        private ColorProfile colorProfile;

        private ColorSpace colorSpace = ColorSpace.YCBCR;

        private boolean progressive;

        private boolean arithmetic;

        private boolean lossless;

        private int predictor = 1;

        private int pointTransform;

        private OptionalInt precision = OptionalInt.empty();

        private List<Sampling> samplingFactors = List.of();

        private List<QuantizationTable> quantizationTables = List.of();

        private List<Integer> quantizationSlots = List.of();

        private boolean baselineQuantization = true;

        private List<Scan> scanScript = List.of();

        private Density density = Density.DEFAULT;

        private Builder() {
        }

        private Builder(JpegEncoderOptions options) {
            this.quality = options.quality;
            this.chromaQuality = options.chromaQuality();
            this.chromaFormat = options.chromaFormat;
            this.optimizeHuffman = options.optimizeHuffman;
            this.restartInterval = options.restartInterval;
            this.colorProfile = options.colorProfile;
            this.colorSpace = options.colorSpace;
            this.progressive = options.progressive;
            this.arithmetic = options.arithmetic;
            this.lossless = options.lossless;
            this.predictor = options.predictor;
            this.pointTransform = options.pointTransform;
            this.precision = options.precision();
            this.samplingFactors = options.samplingFactors;
            this.quantizationTables = options.quantizationTables;
            this.quantizationSlots = options.quantizationSlots;
            this.baselineQuantization = options.baselineQuantization;
            this.scanScript = options.scanScript;
            this.density = options.density;
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
         * Sets the quality of the chrominance quantization tables; see
         * {@link JpegEncoderOptions#chromaQuality()}.
         *
         * @param chromaQuality the value
         * @return this builder
         */
        public Builder chromaQuality(int chromaQuality) {
            this.chromaQuality = OptionalInt.of(chromaQuality);
            return this;
        }

        /**
         * Makes the chrominance quantization tables use {@link JpegEncoderOptions#quality()}; see
         * {@link JpegEncoderOptions#chromaQuality()}.
         *
         * @return this builder
         */
        public Builder automaticChromaQuality() {
            this.chromaQuality = OptionalInt.empty();
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
         * Sets the color space of the coded components; see {@link JpegEncoderOptions#colorSpace()}.
         *
         * @param colorSpace the value
         * @return this builder
         */
        public Builder colorSpace(ColorSpace colorSpace) {
            this.colorSpace = Objects.requireNonNull(colorSpace, "colorSpace");
            return this;
        }

        /**
         * Sets whether the file is progressive; see {@link JpegEncoderOptions#progressive()}.
         *
         * @param progressive the value
         * @return this builder
         */
        public Builder progressive(boolean progressive) {
            this.progressive = progressive;
            return this;
        }

        /**
         * Sets whether the entropy coding is arithmetic; see {@link JpegEncoderOptions#arithmetic()}.
         *
         * @param arithmetic the value
         * @return this builder
         */
        public Builder arithmetic(boolean arithmetic) {
            this.arithmetic = arithmetic;
            return this;
        }

        /**
         * Sets whether the file is lossless; see {@link JpegEncoderOptions#lossless()}.
         *
         * @param lossless the value
         * @return this builder
         */
        public Builder lossless(boolean lossless) {
            this.lossless = lossless;
            return this;
        }

        /**
         * Sets the predictor of a lossless file; see {@link JpegEncoderOptions#predictor()}.
         *
         * @param predictor the value
         * @return this builder
         */
        public Builder predictor(int predictor) {
            this.predictor = predictor;
            return this;
        }

        /**
         * Sets the point transform of a lossless file; see {@link JpegEncoderOptions#pointTransform()}.
         *
         * @param pointTransform the value
         * @return this builder
         */
        public Builder pointTransform(int pointTransform) {
            this.pointTransform = pointTransform;
            return this;
        }

        /**
         * Sets the sample precision; see {@link JpegEncoderOptions#precision()}.
         *
         * @param precision the value
         * @return this builder
         */
        public Builder precision(int precision) {
            this.precision = OptionalInt.of(precision);
            return this;
        }

        /**
         * Selects the automatic precision; see {@link JpegEncoderOptions#precision()}.
         *
         * @return this builder
         */
        public Builder automaticPrecision() {
            this.precision = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the sampling factors of the components; see {@link JpegEncoderOptions#samplingFactors()}.
         *
         * @param samplingFactors the value, at most four factors
         * @return this builder
         * @throws NullPointerException if an element is {@code null}
         */
        public Builder samplingFactors(List<Sampling> samplingFactors) {
            this.samplingFactors = List.copyOf(samplingFactors);
            return this;
        }

        /**
         * Sets the sampling factors of the components; see {@link JpegEncoderOptions#samplingFactors()}.
         *
         * @param samplingFactors the value, at most four factors
         * @return this builder
         * @throws NullPointerException if an element is {@code null}
         */
        public Builder samplingFactors(Sampling... samplingFactors) {
            return samplingFactors(List.of(samplingFactors));
        }

        /**
         * Selects the sampling factors of the chroma format and color space; see
         * {@link JpegEncoderOptions#samplingFactors()}.
         *
         * @return this builder
         */
        public Builder automaticSamplingFactors() {
            this.samplingFactors = List.of();
            return this;
        }

        /**
         * Sets the base quantization tables; see {@link JpegEncoderOptions#quantizationTables()}.
         *
         * @param quantizationTables the value, at most four tables
         * @return this builder
         * @throws NullPointerException if an element is {@code null}
         */
        public Builder quantizationTables(List<QuantizationTable> quantizationTables) {
            this.quantizationTables = List.copyOf(quantizationTables);
            return this;
        }

        /**
         * Sets the base quantization tables; see {@link JpegEncoderOptions#quantizationTables()}.
         *
         * @param quantizationTables the value, at most four tables
         * @return this builder
         * @throws NullPointerException if an element is {@code null}
         */
        public Builder quantizationTables(QuantizationTable... quantizationTables) {
            return quantizationTables(List.of(quantizationTables));
        }

        /**
         * Selects the tables of T.81 Annex K; see {@link JpegEncoderOptions#quantizationTables()}.
         *
         * @return this builder
         */
        public Builder automaticQuantizationTables() {
            this.quantizationTables = List.of();
            return this;
        }

        /**
         * Sets the quantization table slot of each component; see
         * {@link JpegEncoderOptions#quantizationSlots()}.
         *
         * @param quantizationSlots the value
         * @return this builder
         * @throws NullPointerException if an element is {@code null}
         */
        public Builder quantizationSlots(List<Integer> quantizationSlots) {
            this.quantizationSlots = List.copyOf(quantizationSlots);
            return this;
        }

        /**
         * Sets the quantization table slot of each component; see
         * {@link JpegEncoderOptions#quantizationSlots()}.
         *
         * @param quantizationSlots the value
         * @return this builder
         */
        public Builder quantizationSlots(int... quantizationSlots) {
            return quantizationSlots(Arrays.stream(quantizationSlots).boxed().toList());
        }

        /**
         * Selects the slots of the color space; see {@link JpegEncoderOptions#quantizationSlots()}.
         *
         * @return this builder
         */
        public Builder automaticQuantizationSlots() {
            this.quantizationSlots = List.of();
            return this;
        }

        /**
         * Sets whether scaled quantizers are limited to 255; see
         * {@link JpegEncoderOptions#baselineQuantization()}.
         *
         * @param baselineQuantization the value
         * @return this builder
         */
        public Builder baselineQuantization(boolean baselineQuantization) {
            this.baselineQuantization = baselineQuantization;
            return this;
        }

        /**
         * Sets the scans of the file; see {@link JpegEncoderOptions#scanScript()}.
         *
         * @param scanScript the value
         * @return this builder
         * @throws NullPointerException if an element is {@code null}
         */
        public Builder scanScript(List<Scan> scanScript) {
            this.scanScript = List.copyOf(scanScript);
            return this;
        }

        /**
         * Sets the scans of the file; see {@link JpegEncoderOptions#scanScript()}.
         *
         * @param scanScript the value
         * @return this builder
         * @throws NullPointerException if an element is {@code null}
         */
        public Builder scanScript(Scan... scanScript) {
            return scanScript(List.of(scanScript));
        }

        /**
         * Selects the default script of the coding process; see {@link JpegEncoderOptions#scanScript()}.
         *
         * @return this builder
         */
        public Builder automaticScanScript() {
            this.scanScript = List.of();
            return this;
        }

        /**
         * Sets the pixel density of the JFIF header; see {@link JpegEncoderOptions#density()}.
         *
         * @param density the value
         * @return this builder
         */
        public Builder density(Density density) {
            this.density = Objects.requireNonNull(density, "density");
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the quality or chroma quality is not in [0, 100], the restart
         * interval is not in [0, 65535], the predictor is not in [1, 7], the point transform is not in [0, 15]
         * or not below a set precision, a set precision is not 8 or 12 for a DCT file or not in [2, 16] for a
         * lossless one, the file is both progressive and lossless, {@link ChromaFormat#MONOCHROME} is asked of
         * a color space other than {@link ColorSpace#YCBCR}, there are more sampling factors than components,
         * more than four quantization tables, a slot outside [0, 3] or one with no table, or a scan script
         * that does not suit the process as {@link JpegEncoderOptions#scanScript()} describes
         */
        public JpegEncoderOptions build() {
            Checks.inRange("quality", quality, 0, 100);
            if (chromaQuality.isPresent()) {
                Checks.inRange("chroma quality", chromaQuality.getAsInt(), 0, 100);
            }
            Checks.inRange("restart interval", restartInterval, 0, 0xFFFF);
            Checks.inRange("predictor", predictor, 1, 7);
            Checks.inRange("point transform", pointTransform, 0, 15);
            if (lossless && progressive) {
                throw new IllegalArgumentException("a JPEG file cannot be both lossless and progressive");
            }
            var bits = precision.orElse(0);
            if (precision.isPresent()) {
                if (lossless) {
                    Checks.inRange("lossless precision", bits, 2, 16);
                    if (pointTransform >= bits) {
                        throw new IllegalArgumentException("point transform " + pointTransform
                                + " must be below the precision " + bits);
                    }
                } else if (bits != 8 && bits != 12) {
                    throw new IllegalArgumentException("a DCT JPEG file has 8 or 12 bits per sample, not " + bits);
                }
            }
            if (colorSpace != ColorSpace.YCBCR && chromaFormat == ChromaFormat.MONOCHROME) {
                throw new IllegalArgumentException("a " + colorSpace + " JPEG file cannot be monochrome");
            }
            var options = new JpegEncoderOptions(this);
            var components = options.componentCount();
            if (samplingFactors.size() > components) {
                throw new IllegalArgumentException(samplingFactors.size() + " sampling factors for " + components
                        + " components");
            }
            if (quantizationTables.size() > 4) {
                throw new IllegalArgumentException("at most 4 quantization tables, not " + quantizationTables.size());
            }
            var tableSlots = Math.max(2, quantizationTables.size());
            for (var slot : quantizationSlots) {
                if (slot < 0 || slot >= tableSlots) {
                    throw new IllegalArgumentException("quantization slot " + slot + " has no table");
                }
            }
            if (!scanScript.isEmpty()) {
                validateScript(components, bits);
            }
            return options;
        }

        /**
         * Checks the scan script against the process and the component count, at the widest bit positions the
         * precision allows when the precision is automatic.
         *
         * @param components the number of components
         * @param precision the precision, or 0 when automatic
         * @throws IllegalArgumentException if the script does not suit the process
         */
        private void validateScript(int components, int precision) {
            var sent = new boolean[components];
            var lastBits = new int[components][64];
            for (var bits : lastBits) {
                Arrays.fill(bits, -1);
            }
            var maxBit = precision == 0 ? 13 : precision == 12 ? 13 : 10;
            for (var index = 0; index < scanScript.size(); index++) {
                var scan = scanScript.get(index);
                var name = "scan " + (index + 1) + " ";
                for (var component : scan.components()) {
                    if (component >= components) {
                        throw new IllegalArgumentException(name + "names component " + component + " of "
                                + components);
                    }
                }
                var ss = scan.spectralStart();
                var se = scan.spectralEnd();
                var ah = scan.successiveHigh();
                var al = scan.successiveLow();
                if (lossless) {
                    if (ss < 1 || ss > 7 || se != 0 || ah != 0) {
                        throw new IllegalArgumentException(name + "is not a lossless scan");
                    }
                    if (precision != 0 && al >= precision) {
                        throw new IllegalArgumentException(name + "has a point transform not below the precision");
                    }
                } else if (progressive) {
                    if (se < ss || ah > maxBit || al > maxBit) {
                        throw new IllegalArgumentException(name + "has an invalid band or bit position");
                    }
                    if (ss == 0 && se != 0) {
                        throw new IllegalArgumentException(name + "mixes DC and AC coefficients");
                    }
                    if (ss != 0 && scan.components().size() != 1) {
                        throw new IllegalArgumentException(name + "codes AC coefficients of several components");
                    }
                    for (var component : scan.components()) {
                        var bits = lastBits[component];
                        if (ss != 0 && bits[0] < 0) {
                            throw new IllegalArgumentException(name + "codes AC coefficients before the first DC scan");
                        }
                        for (var k = ss; k <= se; k++) {
                            if (bits[k] < 0 ? ah != 0 : ah != bits[k] || al != ah - 1) {
                                throw new IllegalArgumentException(name + "breaks the successive approximation of "
                                        + "coefficient " + k);
                            }
                            bits[k] = al;
                        }
                    }
                    continue;
                } else if (ss != 0 || se != 63 || ah != 0 || al != 0) {
                    throw new IllegalArgumentException(name + "is not a sequential scan");
                }
                for (var component : scan.components()) {
                    if (sent[component]) {
                        throw new IllegalArgumentException(name + "codes component " + component + " again");
                    }
                    sent[component] = true;
                }
            }
            for (var component = 0; component < components; component++) {
                if (progressive ? lastBits[component][0] < 0 : !sent[component]) {
                    throw new IllegalArgumentException("the scan script codes no " + (progressive ? "DC data of " : "")
                            + "component " + component);
                }
            }
        }
    }
}
