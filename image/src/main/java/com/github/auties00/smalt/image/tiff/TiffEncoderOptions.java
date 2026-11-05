package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.metadata.IptcData;
import com.github.auties00.smalt.image.ops.Dithering;
import com.github.auties00.smalt.image.ops.QuantizeOptions;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link TiffEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class TiffEncoderOptions {
    /**
     * The WebP settings of {@link #defaults()}: lossless, transparent colors kept.
     */
    private static final WebpEncoderOptions LOSSLESS_WEBP = WebpEncoderOptions.lossless().toBuilder().exact(true)
            .build();

    /**
     * The options of {@link #defaults()}.
     */
    private static final TiffEncoderOptions DEFAULTS = new Builder().build();

    /**
     * The options of {@link #uncompressed()}.
     */
    private static final TiffEncoderOptions UNCOMPRESSED = new Builder().compression(TiffCompression.NONE)
            .predictor(TiffPredictor.NONE).build();

    /**
     * The options of {@link #deflate()}.
     */
    private static final TiffEncoderOptions DEFLATE = new Builder().compression(TiffCompression.DEFLATE).build();

    /**
     * The options of {@link #zstd()}.
     */
    private static final TiffEncoderOptions ZSTD = new Builder().compression(TiffCompression.ZSTD).build();

    /**
     * The options of {@link #fax()}.
     */
    private static final TiffEncoderOptions FAX = new Builder().compression(TiffCompression.CCITT_T6)
            .layout(Layout.BILEVEL).build();

    /**
     * The options of {@link #jpeg()}.
     */
    private static final TiffEncoderOptions JPEG = new Builder().compression(TiffCompression.JPEG).build();

    private final TiffCompression compression;

    private final TiffPredictor predictor;

    private final int compressionLevel;

    private final Layout layout;

    private final Depth depth;

    private final boolean planar;

    private final ChromaFormat chromaFormat;

    private final QuantizeOptions quantizeOptions;

    private final Dithering bilevelDithering;

    /**
     * The rows per strip, or 0 to choose them (and for tiled output).
     */
    private final int rowsPerStrip;

    /**
     * The tile width, or 0 for strips.
     */
    private final int tileWidth;

    /**
     * The tile height, or 0 for strips.
     */
    private final int tileHeight;

    private final boolean bigEndian;

    private final boolean bigTiff;

    private final boolean faxTwoDimensional;

    private final boolean faxFillBits;

    private final PixelDensity density;

    private final Orientation orientation;

    private final ColorProfile colorProfile;

    private final ExifData exif;

    private final XmpPacket xmp;

    private final IptcData iptc;

    private final WebpEncoderOptions webpOptions;

    private final Jp2EncoderOptions jpeg2000Options;

    private final JpegEncoderOptions jpegOptions;

    /**
     * The ICC bytes of {@link #colorProfile}, or {@code null}.
     */
    private final byte[] iccBytes;

    /**
     * Selects the samples written for each pixel.
     */
    public enum Layout {
        /**
         * Gray when every pixel is gray and RGB otherwise, with an alpha sample when some pixel
         * is not opaque; one bit per pixel under the CCITT compressions, as {@link #BILEVEL}
         * describes.
         */
        AUTO,

        /**
         * One gray sample (black-is-zero), computed as the BT.601 luma of RGB pixels.
         */
        GRAY,

        /**
         * One gray sample and an unassociated alpha sample.
         */
        GRAY_ALPHA,

        /**
         * Red, green and blue samples.
         */
        RGB,

        /**
         * Red, green, blue and an unassociated alpha sample.
         */
        RGBA,

        /**
         * Cyan, magenta, yellow and black samples ({@code PhotometricInterpretation} 5, {@code InkSet} 1).
         *
         * <p>RGB pixels are separated without color management: black is
         * {@code max - max(R, G, B)}, and each other ink is the smallest value that
         * {@link com.github.auties00.smalt.image.ops.ImageOps#toRgba(com.github.auties00.smalt.image.Picture)}
         * turns back into exactly the RGB value, so decoding the page restores the pixels. A CMYK
         * channel picture is written as stored.
         */
        CMYK,

        /**
         * The {@link #CMYK} samples and an unassociated alpha sample.
         */
        CMYK_ALPHA,

        /**
         * One palette index per pixel ({@code PhotometricInterpretation} 3) with a {@code ColorMap},
         * of 1, 2, 4 or 8 bits, the fewest that index the palette.
         *
         * <p>The palette is built from the opaque colors by
         * {@link com.github.auties00.smalt.image.ops.ImageQuantization} with
         * {@link TiffEncoderOptions#quantizeOptions()}: exact when the picture has few enough colors,
         * quantized otherwise. Alpha is not kept: a translucent pixel takes the palette entry of its
         * color.
         */
        PALETTE,

        /**
         * One bit per pixel, white-is-zero ({@code PhotometricInterpretation} 0, so that a 0 bit is
         * white, as the CCITT compressions code it): a pixel is black when its BT.601 luma is below
         * 128, or as {@link TiffEncoderOptions#bilevelDithering()} decides. Alpha is not kept.
         */
        BILEVEL,

        /**
         * Y'CbCr samples ({@code PhotometricInterpretation} 6), with the chroma subsampling of
         * {@link TiffEncoderOptions#chromaFormat()}.
         *
         * <p>RGB pixels are converted to full-range BT.601 Y'CbCr, each subsampled chroma sample being
         * the average of the pixels it covers; an 8-bit Y'CbCr picture whose matrix has fixed
         * coefficients (BT.601, BT.709, BT.2020 and the like) keeps its matrix and range, recorded in
         * {@code YCbCrCoefficients} and {@code ReferenceBlackWhite}, so its samples are written as
         * stored when its chroma layout is the requested one. Interleaved subsampled samples are
         * written as TIFF data units. Alpha is not kept, and the samples have 8 bits.
         */
        YCBCR
    }

    /**
     * Selects the bits per sample.
     */
    public enum Depth {
        /**
         * The depth of the picture: 32-bit floating point for floating-point channel pictures, 16
         * bits for other pictures of more than 8 bits per sample (10-bit Y'CbCr, for example), 8 bits
         * otherwise.
         */
        AUTO,

        /**
         * 8 bits per sample; 16-bit pictures are reduced with exact rounding.
         */
        EIGHT,

        /**
         * 16 bits per sample; 8-bit pictures are widened exactly ({@code v * 257}).
         */
        SIXTEEN,

        /**
         * 32-bit IEEE floating-point samples ({@code SampleFormat} 3), integer samples becoming
         * {@code v / 255} or {@code v / 65535}, as
         * {@link com.github.auties00.smalt.image.ops.ImageOps#toChannelFloat(com.github.auties00.smalt.image.Picture)}
         * converts them.
         */
        FLOAT
    }

    private TiffEncoderOptions(Builder builder) {
        this.compression = builder.compression;
        this.predictor = builder.predictor;
        this.compressionLevel = builder.compressionLevel;
        this.layout = builder.layout;
        this.depth = builder.depth;
        this.planar = builder.planar;
        this.chromaFormat = builder.chromaFormat;
        this.quantizeOptions = builder.quantizeOptions;
        this.bilevelDithering = builder.bilevelDithering;
        this.rowsPerStrip = builder.rowsPerStrip;
        this.tileWidth = builder.tileWidth;
        this.tileHeight = builder.tileHeight;
        this.bigEndian = builder.bigEndian;
        this.bigTiff = builder.bigTiff;
        this.faxTwoDimensional = builder.faxTwoDimensional;
        this.faxFillBits = builder.faxFillBits;
        this.density = builder.density;
        this.orientation = builder.orientation;
        this.colorProfile = builder.colorProfile;
        this.exif = builder.exif;
        this.xmp = builder.xmp;
        this.iptc = builder.iptc;
        this.webpOptions = builder.webpOptions;
        this.jpeg2000Options = builder.jpeg2000Options;
        this.jpegOptions = builder.jpegOptions;
        this.iccBytes = builder.colorProfile == null ? null : builder.colorProfile.toByteArray();
    }

    /**
     * Returns LZW compression with the horizontal predictor, automatic layout and depth, strips
     * of about 64 KiB, little-endian classic TIFF: lossless, compact and readable by every TIFF
     * reader.
     *
     * @return the default options
     */
    public static TiffEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the {@link #defaults()} without compression, the fastest to write and read.
     *
     * @return uncompressed options
     */
    public static TiffEncoderOptions uncompressed() {
        return UNCOMPRESSED;
    }

    /**
     * Returns the {@link #defaults()} with Deflate at level 6 instead of LZW, usually smaller.
     *
     * @return Deflate options
     */
    public static TiffEncoderOptions deflate() {
        return DEFLATE;
    }

    /**
     * Returns the {@link #defaults()} with Zstandard at level 6 instead of LZW.
     *
     * @apiNote Fewer readers decode Zstandard (compression 50000) than LZW or Deflate; libtiff does from
     * version 4.0.10 when built with it.
     * @return Zstandard options
     */
    public static TiffEncoderOptions zstd() {
        return ZSTD;
    }

    /**
     * Returns the {@link #defaults()} with bilevel pages coded as CCITT T.6 (Group 4), the most
     * compact lossless coding of black-and-white documents.
     *
     * @return CCITT Group 4 options
     */
    public static TiffEncoderOptions fax() {
        return FAX;
    }

    /**
     * Returns the {@link #defaults()} with JPEG compression (TIFF Technical Note 2) at the
     * {@link JpegEncoderOptions#defaults()}: lossy, for photographs.
     *
     * @return JPEG options
     */
    public static TiffEncoderOptions jpeg() {
        return JPEG;
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
     * {@return the compression scheme: {@link TiffCompression#NONE}, {@link TiffCompression#PACKBITS},
     * {@link TiffCompression#LZW}, {@link TiffCompression#DEFLATE}, {@link TiffCompression#ZSTD},
     * {@link TiffCompression#CCITT_RLE}, {@link TiffCompression#CCITT_RLEW},
     * {@link TiffCompression#CCITT_T4}, {@link TiffCompression#CCITT_T6}, {@link TiffCompression#JPEG},
     * {@link TiffCompression#WEBP} or {@link TiffCompression#JPEG_2000}; {@code LZW} by default}
     *
     * <p>The first five are lossless, so they decide only the size and the compatibility of the
     * file; {@code ZSTD} writes each strip or tile as one Zstandard frame. The CCITT schemes code
     * {@link Layout#BILEVEL} pages only, losslessly, as {@link #isFaxTwoDimensional()} and
     * {@link #isFaxFillBits()} configure Group 3; under them the {@link Layout#AUTO} layout is
     * bilevel. {@code JPEG} codes each strip or tile as an abbreviated JPEG stream under
     * {@link #jpegOptions()}, the quantization tables (and the Huffman tables, unless they are
     * optimized per strip or tile) shared in the {@code JPEGTables} tag; it holds 8-bit gray or
     * Y'CbCr samples, a color page being written as Y'CbCr with the subsampling of the JPEG
     * options, so it takes the {@code AUTO}, {@code GRAY}, {@code RGB} and {@code YCBCR} layouts at
     * 8 bits, without alpha. {@code WEBP} codes each strip or tile as a WebP file under
     * {@link #webpOptions()}; it holds 8-bit RGB or RGBA samples only, so it takes the {@code AUTO},
     * {@code RGB} and {@code RGBA} layouts at 8 bits, and gray pictures are written as RGB, 16-bit
     * pictures are reduced to 8 bits and channel pictures are converted to RGBA first.
     * {@code JPEG_2000} codes each strip or tile as a JPEG 2000 codestream under
     * {@link #jpeg2000Options()}, with the samples of any layout but {@code PALETTE},
     * {@code BILEVEL} and {@code YCBCR} at any integer depth; it cannot code floating-point samples.
     * WebP and JPEG 2000 are lossless under their default settings. JPEG, WebP and JPEG 2000 write
     * interleaved samples only.
     */
    public TiffCompression compression() {
        return compression;
    }

    /**
     * {@return the predictor, applied only with LZW, Deflate and Zstandard; {@link TiffPredictor#HORIZONTAL}
     * by default}
     *
     * <p>{@code HORIZONTAL} differences the integer samples of 8 bits or more of every layout but
     * {@link Layout#PALETTE} and subsampled {@link Layout#YCBCR}; {@code FLOATING_POINT} differences
     * the byte planes of floating-point samples. A predictor that does not fit the samples of a page
     * is not applied to it.
     */
    public TiffPredictor predictor() {
        return predictor;
    }

    /**
     * {@return the Deflate or Zstandard compression level, from 1 (fastest) to 9 (smallest); 6 by
     * default}
     */
    public int compressionLevel() {
        return compressionLevel;
    }

    /**
     * {@return the sample layout; {@link Layout#AUTO} by default}
     *
     * <p>A channel picture keeps its own channels under {@code AUTO} and under the layout whose
     * channels are exactly its own ({@link Layout#CMYK} for a CMYK picture, {@link Layout#RGB} for an
     * RGB one, and so on); any other layout converts it first.
     */
    public Layout layout() {
        return layout;
    }

    /**
     * {@return the sample depth; {@link Depth#AUTO} by default}
     *
     * <p>The {@link Layout#PALETTE}, {@link Layout#BILEVEL} and {@link Layout#YCBCR} layouts fix their
     * own depth and take {@code AUTO} or, for {@code YCBCR}, {@code EIGHT}.
     */
    public Depth depth() {
        return depth;
    }

    /**
     * {@return whether the samples are written in separate planes ({@code PlanarConfiguration} 2),
     * one series of strips or tiles per sample, rather than interleaved; {@code false} by default}
     *
     * <p>Subsampled Cb and Cr planes are written at their reduced size, each strip or tile of them
     * covering the pixels of the matching luma strip or tile. A page of one sample is written
     * interleaved, which is the same data. Only the lossless byte-oriented compressions write
     * separate planes.
     */
    public boolean isPlanar() {
        return planar;
    }

    /**
     * {@return the chroma subsampling of {@link Layout#YCBCR} pages without JPEG compression:
     * {@link ChromaFormat#YUV420} (the TIFF default), {@link ChromaFormat#YUV422} or
     * {@link ChromaFormat#YUV444}; {@code YUV420} by default}
     *
     * <p>JPEG pages take the subsampling of {@link #jpegOptions()} instead.
     */
    public ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /**
     * {@return the settings that build the palette of {@link Layout#PALETTE} pages;
     * {@link QuantizeOptions#defaults()} by default}
     *
     * <p>The alpha threshold is not used, since palette pages keep no alpha.
     */
    public QuantizeOptions quantizeOptions() {
        return quantizeOptions;
    }

    /**
     * {@return how {@link Layout#BILEVEL} pages turn the luma of each pixel into black or white;
     * {@link Dithering#NONE} by default}
     *
     * <p>{@code NONE} thresholds the luma at 128; {@code ORDERED} adds an 8x8 Bayer threshold
     * matrix first; {@code FLOYD_STEINBERG} and {@code SIERRA} diffuse each pixel's error to its
     * unvisited neighbors in serpentine order, with the weights their constants describe. A picture
     * whose pixels are all black or white comes out unchanged whatever the dithering.
     */
    public Dithering bilevelDithering() {
        return bilevelDithering;
    }

    /**
     * {@return the rows per strip, at least 1, or empty to choose strips of about 64 KiB (and at
     * most 16383 rows under {@link TiffCompression#WEBP}) and for tiled output; empty by default}
     *
     * <p>Under {@link TiffCompression#JPEG} the rows are a multiple of 8 times the vertical chroma
     * subsampling of {@link #jpegOptions()} (8 for the {@link Layout#GRAY} layout), and for
     * subsampled {@link Layout#YCBCR} pages a multiple of the vertical subsampling; automatic strips
     * are rounded down to such a multiple.
     */
    public OptionalInt rowsPerStrip() {
        return rowsPerStrip == 0 ? OptionalInt.empty() : OptionalInt.of(rowsPerStrip);
    }

    /**
     * {@return the tile width, a positive multiple of 16, or empty for output in strips; empty by
     * default}
     */
    public OptionalInt tileWidth() {
        return tileWidth == 0 ? OptionalInt.empty() : OptionalInt.of(tileWidth);
    }

    /**
     * {@return the tile height, a positive multiple of 16, or empty for output in strips; empty by
     * default}
     */
    public OptionalInt tileHeight() {
        return tileHeight == 0 ? OptionalInt.empty() : OptionalInt.of(tileHeight);
    }

    /**
     * {@return whether the file is written big-endian ({@code MM}) rather than little-endian
     * ({@code II}); {@code false} by default}
     */
    public boolean isBigEndian() {
        return bigEndian;
    }

    /**
     * {@return whether the file is written as a BigTIFF, whose 64-bit offsets allow files over
     * 4 GiB; {@code false} by default}
     */
    public boolean isBigTiff() {
        return bigTiff;
    }

    /**
     * {@return whether {@link TiffCompression#CCITT_T4} pages code rows two-dimensionally
     * ({@code T4Options} bit 0), every {@code K}-th row from the first one-dimensionally; {@code false}
     * by default}
     *
     * <p>{@code K} is 4 when the vertical resolution exceeds 150 rows per inch and 2 otherwise, the
     * values ITU-T T.4 sets for its fine and standard resolutions.
     */
    public boolean isFaxTwoDimensional() {
        return faxTwoDimensional;
    }

    /**
     * {@return whether {@link TiffCompression#CCITT_T4} pages pad each end-of-line code with zero bits
     * so that it ends on a byte boundary ({@code T4Options} bit 2); {@code false} by default}
     */
    public boolean isFaxFillBits() {
        return faxFillBits;
    }

    /**
     * {@return the pixel density of every page, written in its {@code XResolution}, {@code YResolution} and
     * {@code ResolutionUnit} fields, or empty to take them from {@link #exif()} or, without them there, to
     * write 72 dots per inch; empty by default}
     *
     * <p>Dots per inch are written with {@code ResolutionUnit} 2, dots per centimeter with 3, dots per meter
     * converted to dots per centimeter, and an aspect ratio with 1 (no absolute unit). Each value is written as
     * a continued-fraction approximation of two 32-bit unsigned terms, exact for integers and for fractions
     * whose terms fit.
     */
    public Optional<PixelDensity> density() {
        return Optional.ofNullable(density);
    }

    /**
     * {@return the display orientation of every page, written in the {@code Orientation} tag, or
     * empty to take it from {@link #exif()} or, without one there, to write none (which reads as
     * {@link Orientation#NORMAL}); empty by default}
     *
     * <p>The pixels are written as given; the tag tells readers how to display them.
     */
    public Optional<Orientation> orientation() {
        return Optional.ofNullable(orientation);
    }

    /**
     * {@return the ICC profile embedded in every page, or empty when none is embedded; empty by
     * default}
     *
     * <p>The profile tells readers which color space the pixels are in; it does not convert them.
     * Its ICC bytes are written unchanged; a high-dynamic-range profile is written in its ICC form,
     * whose {@code cicp} tag carries its PQ or HLG code points.
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    /**
     * {@return the EXIF metadata of every page, or empty for none; empty by default}
     *
     * <p>Its Exif, GPS and interoperability directories are written as sub-directories of the
     * page, and the descriptive entries of its first directory ({@code Make}, {@code Model},
     * {@code DateTime}, {@code Artist}, {@code Copyright} and the like) in the page's directory.
     * Entries that describe the image structure, which the encoder writes itself, are left out,
     * except that the orientation and resolution entries apply when {@link #orientation()} and
     * {@link #density()} are empty. The thumbnail is not written, since a TIFF page cannot hold
     * one.
     */
    public Optional<ExifData> exif() {
        return Optional.ofNullable(exif);
    }

    /**
     * {@return the XMP packet of every page, written in its {@code XMLPacket} tag (700), or empty
     * for none; empty by default}
     */
    public Optional<XmpPacket> xmp() {
        return Optional.ofNullable(xmp);
    }

    /**
     * {@return the IPTC-IIM metadata of every page, written in its {@code IPTC-NAA} tag (33723), or
     * empty for none; empty by default}
     */
    public Optional<IptcData> iptc() {
        return Optional.ofNullable(iptc);
    }

    /**
     * {@return the WebP settings of each strip or tile when the compression is
     * {@link TiffCompression#WEBP}; lossless, with the colors of transparent pixels kept, by
     * default}
     *
     * <p>Their color profile is ignored: the page's profile is {@link #colorProfile()}.
     */
    public WebpEncoderOptions webpOptions() {
        return webpOptions;
    }

    /**
     * {@return the JPEG 2000 settings of each strip or tile when the compression is
     * {@link TiffCompression#JPEG_2000}; {@link Jp2EncoderOptions#lossless()} by default}
     *
     * <p>Each strip or tile is a raw codestream whatever {@link Jp2EncoderOptions#format()} says,
     * so their color profile is ignored: the page's profile is {@link #colorProfile()}.
     */
    public Jp2EncoderOptions jpeg2000Options() {
        return jpeg2000Options;
    }

    /**
     * {@return the JPEG settings of each strip or tile when the compression is
     * {@link TiffCompression#JPEG}; {@link JpegEncoderOptions#defaults()} by default}
     *
     * <p>The quality, the chroma subsampling ({@link ChromaFormat#MONOCHROME} writing gray pages),
     * the Huffman optimization and the restart interval apply; the color profile and the JFIF density are
     * ignored, since the page's profile is {@link #colorProfile()} and its density
     * {@link #density()}. The other settings must keep their defaults: the pages are 8-bit baseline
     * Y'CbCr or gray.
     */
    public JpegEncoderOptions jpegOptions() {
        return jpegOptions;
    }

    /**
     * Returns the ICC bytes of the color profile without copying them.
     *
     * @return the bytes, or {@code null}
     */
    byte[] iccProfileBytes() {
        return iccBytes;
    }

    /**
     * Returns the rows per strip.
     *
     * @return the configured rows, {@code 0} when automatic or tiled
     */
    int rawRowsPerStrip() {
        return rowsPerStrip;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TiffEncoderOptions that
                && compression == that.compression && predictor == that.predictor
                && compressionLevel == that.compressionLevel && layout == that.layout && depth == that.depth
                && planar == that.planar && chromaFormat == that.chromaFormat
                && quantizeOptions.equals(that.quantizeOptions) && bilevelDithering == that.bilevelDithering
                && rowsPerStrip == that.rowsPerStrip && tileWidth == that.tileWidth && tileHeight == that.tileHeight
                && bigEndian == that.bigEndian && bigTiff == that.bigTiff
                && faxTwoDimensional == that.faxTwoDimensional && faxFillBits == that.faxFillBits
                && Objects.equals(density, that.density) && orientation == that.orientation
                && Objects.equals(colorProfile, that.colorProfile) && Objects.equals(exif, that.exif)
                && Objects.equals(xmp, that.xmp) && Objects.equals(iptc, that.iptc)
                && webpOptions.equals(that.webpOptions) && jpeg2000Options.equals(that.jpeg2000Options)
                && jpegOptions.equals(that.jpegOptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(compression, predictor, compressionLevel, layout, depth, planar, chromaFormat,
                quantizeOptions, bilevelDithering, rowsPerStrip, tileWidth, tileHeight, bigEndian, bigTiff,
                faxTwoDimensional, faxFillBits, density, orientation, colorProfile, exif, xmp, iptc, webpOptions,
                jpeg2000Options, jpegOptions);
    }

    @Override
    public String toString() {
        return "TiffEncoderOptions[compression=" + compression + ", predictor=" + predictor
                + ", compressionLevel=" + compressionLevel + ", layout=" + layout + ", depth=" + depth
                + (planar ? ", planar" : "")
                + (layout == Layout.YCBCR ? ", chromaFormat=" + chromaFormat : "")
                + (layout == Layout.PALETTE ? ", quantizeOptions=" + quantizeOptions : "")
                + (layout == Layout.BILEVEL ? ", bilevelDithering=" + bilevelDithering : "")
                + (tileWidth > 0 ? ", tiles=" + tileWidth + "x" + tileHeight : ", rowsPerStrip=" + rowsPerStrip)
                + ", bigEndian=" + bigEndian + ", bigTiff=" + bigTiff
                + (compression == TiffCompression.CCITT_T4
                ? ", faxTwoDimensional=" + faxTwoDimensional + ", faxFillBits=" + faxFillBits : "")
                + (density == null ? "" : ", density=" + density)
                + (orientation == null ? "" : ", orientation=" + orientation)
                + (colorProfile == null ? "" : ", colorProfile=" + colorProfile.description().orElse("unnamed"))
                + (exif == null ? "" : ", exif")
                + (xmp == null ? "" : ", xmp")
                + (iptc == null ? "" : ", iptc")
                + (compression == TiffCompression.WEBP ? ", webpOptions=" + webpOptions : "")
                + (compression == TiffCompression.JPEG_2000 ? ", jpeg2000Options=" + jpeg2000Options : "")
                + (compression == TiffCompression.JPEG ? ", jpegOptions=" + jpegOptions : "")
                + "]";
    }

    /**
     * A builder of {@link TiffEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and the combinations
     * of settings. Strips and tiles exclude each other: setting one drops the other. Instances are
     * not thread-safe.
     */
    public static final class Builder {
        private TiffCompression compression = TiffCompression.LZW;

        private TiffPredictor predictor = TiffPredictor.HORIZONTAL;

        private int compressionLevel = 6;

        private Layout layout = Layout.AUTO;

        private Depth depth = Depth.AUTO;

        private boolean planar;

        private ChromaFormat chromaFormat = ChromaFormat.YUV420;

        private QuantizeOptions quantizeOptions = QuantizeOptions.defaults();

        private Dithering bilevelDithering = Dithering.NONE;

        private int rowsPerStrip;

        private int tileWidth;

        private int tileHeight;

        private boolean bigEndian;

        private boolean bigTiff;

        private boolean faxTwoDimensional;

        private boolean faxFillBits;

        private PixelDensity density;

        private Orientation orientation;

        private ColorProfile colorProfile;

        private ExifData exif;

        private XmpPacket xmp;

        private IptcData iptc;

        private WebpEncoderOptions webpOptions = LOSSLESS_WEBP;

        private Jp2EncoderOptions jpeg2000Options = Jp2EncoderOptions.lossless();

        private JpegEncoderOptions jpegOptions = JpegEncoderOptions.defaults();

        /**
         * Whether {@link #strips(int)} was called last of the strip and tile settings, so that
         * {@link #build()} checks the rows it set.
         */
        private boolean stripsSet;

        /**
         * Whether {@link #tiles(int, int)} was called last of the strip and tile settings, so that
         * {@link #build()} checks the size it set.
         */
        private boolean tilesSet;

        private Builder() {
        }

        private Builder(TiffEncoderOptions options) {
            this.compression = options.compression;
            this.predictor = options.predictor;
            this.compressionLevel = options.compressionLevel;
            this.layout = options.layout;
            this.depth = options.depth;
            this.planar = options.planar;
            this.chromaFormat = options.chromaFormat;
            this.quantizeOptions = options.quantizeOptions;
            this.bilevelDithering = options.bilevelDithering;
            this.rowsPerStrip = options.rowsPerStrip;
            this.tileWidth = options.tileWidth;
            this.tileHeight = options.tileHeight;
            this.bigEndian = options.bigEndian;
            this.bigTiff = options.bigTiff;
            this.faxTwoDimensional = options.faxTwoDimensional;
            this.faxFillBits = options.faxFillBits;
            this.density = options.density;
            this.orientation = options.orientation;
            this.colorProfile = options.colorProfile;
            this.exif = options.exif;
            this.xmp = options.xmp;
            this.iptc = options.iptc;
            this.webpOptions = options.webpOptions;
            this.jpeg2000Options = options.jpeg2000Options;
            this.jpegOptions = options.jpegOptions;
            this.stripsSet = options.rowsPerStrip > 0;
            this.tilesSet = options.tileWidth > 0;
        }

        /**
         * Sets the compression scheme; see {@link TiffEncoderOptions#compression()}.
         *
         * @param compression the value
         * @return this builder
         */
        public Builder compression(TiffCompression compression) {
            this.compression = Objects.requireNonNull(compression, "compression");
            return this;
        }

        /**
         * Sets the predictor; see {@link TiffEncoderOptions#predictor()}.
         *
         * @param predictor the value
         * @return this builder
         */
        public Builder predictor(TiffPredictor predictor) {
            this.predictor = Objects.requireNonNull(predictor, "predictor");
            return this;
        }

        /**
         * Sets the Deflate or Zstandard level; see {@link TiffEncoderOptions#compressionLevel()}.
         *
         * @param compressionLevel the value
         * @return this builder
         */
        public Builder compressionLevel(int compressionLevel) {
            this.compressionLevel = compressionLevel;
            return this;
        }

        /**
         * Sets the sample layout; see {@link TiffEncoderOptions#layout()}.
         *
         * @param layout the value
         * @return this builder
         */
        public Builder layout(Layout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            return this;
        }

        /**
         * Sets the sample depth; see {@link TiffEncoderOptions#depth()}.
         *
         * @param depth the value
         * @return this builder
         */
        public Builder depth(Depth depth) {
            this.depth = Objects.requireNonNull(depth, "depth");
            return this;
        }

        /**
         * Chooses separate planes or interleaved samples; see {@link TiffEncoderOptions#isPlanar()}.
         *
         * @param planar the value
         * @return this builder
         */
        public Builder planar(boolean planar) {
            this.planar = planar;
            return this;
        }

        /**
         * Sets the chroma subsampling of Y'CbCr pages; see {@link TiffEncoderOptions#chromaFormat()}.
         *
         * @param chromaFormat the value
         * @return this builder
         */
        public Builder chromaFormat(ChromaFormat chromaFormat) {
            this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
            return this;
        }

        /**
         * Sets the palette settings; see {@link TiffEncoderOptions#quantizeOptions()}.
         *
         * @param quantizeOptions the value
         * @return this builder
         */
        public Builder quantizeOptions(QuantizeOptions quantizeOptions) {
            this.quantizeOptions = Objects.requireNonNull(quantizeOptions, "quantizeOptions");
            return this;
        }

        /**
         * Sets the dithering of bilevel pages; see {@link TiffEncoderOptions#bilevelDithering()}.
         *
         * @param bilevelDithering the value
         * @return this builder
         */
        public Builder bilevelDithering(Dithering bilevelDithering) {
            this.bilevelDithering = Objects.requireNonNull(bilevelDithering, "bilevelDithering");
            return this;
        }

        /**
         * Writes strips of a fixed number of rows instead of tiles; see
         * {@link TiffEncoderOptions#rowsPerStrip()}.
         *
         * @param rowsPerStrip the value
         * @return this builder
         */
        public Builder strips(int rowsPerStrip) {
            this.rowsPerStrip = rowsPerStrip;
            this.stripsSet = true;
            this.tileWidth = 0;
            this.tileHeight = 0;
            this.tilesSet = false;
            return this;
        }

        /**
         * Writes strips whose row count the encoder chooses, about 64 KiB each, instead of tiles;
         * see {@link TiffEncoderOptions#rowsPerStrip()}.
         *
         * @return this builder
         */
        public Builder automaticStrips() {
            this.rowsPerStrip = 0;
            this.stripsSet = false;
            this.tileWidth = 0;
            this.tileHeight = 0;
            this.tilesSet = false;
            return this;
        }

        /**
         * Writes tiles instead of strips; see {@link TiffEncoderOptions#tileWidth()} and
         * {@link TiffEncoderOptions#tileHeight()}.
         *
         * @param tileWidth the tile width
         * @param tileHeight the tile height
         * @return this builder
         */
        public Builder tiles(int tileWidth, int tileHeight) {
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
            this.tilesSet = true;
            this.rowsPerStrip = 0;
            this.stripsSet = false;
            return this;
        }

        /**
         * Sets the byte order; see {@link TiffEncoderOptions#isBigEndian()}.
         *
         * @param bigEndian the value
         * @return this builder
         */
        public Builder bigEndian(boolean bigEndian) {
            this.bigEndian = bigEndian;
            return this;
        }

        /**
         * Chooses BigTIFF or classic TIFF; see {@link TiffEncoderOptions#isBigTiff()}.
         *
         * @param bigTiff the value
         * @return this builder
         */
        public Builder bigTiff(boolean bigTiff) {
            this.bigTiff = bigTiff;
            return this;
        }

        /**
         * Chooses two-dimensional Group 3 coding; see {@link TiffEncoderOptions#isFaxTwoDimensional()}.
         *
         * @param faxTwoDimensional the value
         * @return this builder
         */
        public Builder faxTwoDimensional(boolean faxTwoDimensional) {
            this.faxTwoDimensional = faxTwoDimensional;
            return this;
        }

        /**
         * Chooses byte-aligned Group 3 end-of-line codes; see {@link TiffEncoderOptions#isFaxFillBits()}.
         *
         * @param faxFillBits the value
         * @return this builder
         */
        public Builder faxFillBits(boolean faxFillBits) {
            this.faxFillBits = faxFillBits;
            return this;
        }

        /**
         * Sets the pixel density of every page; see {@link TiffEncoderOptions#density()}.
         *
         * @param density the value
         * @return this builder
         */
        public Builder density(PixelDensity density) {
            this.density = Objects.requireNonNull(density, "density");
            return this;
        }

        /**
         * Takes the pixel density from the EXIF metadata, or writes 72 dots per inch; see
         * {@link TiffEncoderOptions#density()}.
         *
         * @return this builder
         */
        public Builder automaticDensity() {
            this.density = null;
            return this;
        }

        /**
         * Sets the display orientation of every page; see {@link TiffEncoderOptions#orientation()}.
         *
         * @param orientation the value
         * @return this builder
         */
        public Builder orientation(Orientation orientation) {
            this.orientation = Objects.requireNonNull(orientation, "orientation");
            return this;
        }

        /**
         * Takes the orientation from the EXIF metadata, or writes none; see
         * {@link TiffEncoderOptions#orientation()}.
         *
         * @return this builder
         */
        public Builder automaticOrientation() {
            this.orientation = null;
            return this;
        }

        /**
         * Sets the ICC profile embedded in every page; see {@link TiffEncoderOptions#colorProfile()}.
         *
         * @param colorProfile the value, or {@code null} for none
         * @return this builder
         */
        public Builder colorProfile(ColorProfile colorProfile) {
            this.colorProfile = colorProfile;
            return this;
        }

        /**
         * Sets the EXIF metadata of every page; see {@link TiffEncoderOptions#exif()}.
         *
         * @param exif the value
         * @return this builder
         */
        public Builder exif(ExifData exif) {
            this.exif = Objects.requireNonNull(exif, "exif");
            return this;
        }

        /**
         * Writes no EXIF metadata; see {@link TiffEncoderOptions#exif()}.
         *
         * @return this builder
         */
        public Builder noExif() {
            this.exif = null;
            return this;
        }

        /**
         * Sets the XMP packet of every page; see {@link TiffEncoderOptions#xmp()}.
         *
         * @param xmp the value
         * @return this builder
         */
        public Builder xmp(XmpPacket xmp) {
            this.xmp = Objects.requireNonNull(xmp, "xmp");
            return this;
        }

        /**
         * Writes no XMP packet; see {@link TiffEncoderOptions#xmp()}.
         *
         * @return this builder
         */
        public Builder noXmp() {
            this.xmp = null;
            return this;
        }

        /**
         * Sets the IPTC-IIM metadata of every page; see {@link TiffEncoderOptions#iptc()}.
         *
         * @param iptc the value
         * @return this builder
         */
        public Builder iptc(IptcData iptc) {
            this.iptc = Objects.requireNonNull(iptc, "iptc");
            return this;
        }

        /**
         * Writes no IPTC-IIM metadata; see {@link TiffEncoderOptions#iptc()}.
         *
         * @return this builder
         */
        public Builder noIptc() {
            this.iptc = null;
            return this;
        }

        /**
         * Sets the WebP settings of each strip or tile; see {@link TiffEncoderOptions#webpOptions()}.
         *
         * @param webpOptions the value
         * @return this builder
         */
        public Builder webpOptions(WebpEncoderOptions webpOptions) {
            this.webpOptions = Objects.requireNonNull(webpOptions, "webpOptions");
            return this;
        }

        /**
         * Sets the JPEG 2000 settings of each strip or tile; see
         * {@link TiffEncoderOptions#jpeg2000Options()}.
         *
         * @param jpeg2000Options the value
         * @return this builder
         */
        public Builder jpeg2000Options(Jp2EncoderOptions jpeg2000Options) {
            this.jpeg2000Options = Objects.requireNonNull(jpeg2000Options, "jpeg2000Options");
            return this;
        }

        /**
         * Sets the JPEG settings of each strip or tile; see {@link TiffEncoderOptions#jpegOptions()}.
         *
         * @param jpegOptions the value
         * @return this builder
         */
        public Builder jpegOptions(JpegEncoderOptions jpegOptions) {
            this.jpegOptions = Objects.requireNonNull(jpegOptions, "jpegOptions");
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the compression is not one
         * {@link TiffEncoderOptions#compression()} lists, the compression level is not in [1, 9], the rows
         * per strip are not positive or, for JPEG or subsampled Y'CbCr pages, not a multiple of the row
         * group {@link TiffEncoderOptions#rowsPerStrip()} describes, a tile dimension is not a positive
         * multiple of 16, the chroma format is {@code MONOCHROME} or {@code YUV440} (TIFF never
         * subsamples chroma more vertically than horizontally), the JPEG settings ask for
         * {@code YUV440} with JPEG compression, the layout is {@code PALETTE},
         * {@code BILEVEL} or {@code YCBCR} with a depth they do not take, the compression does not take the
         * layout, the depth or separate planes, as {@link TiffEncoderOptions#compression()} describes, or the
         * JPEG settings ask for more than {@link TiffEncoderOptions#jpegOptions()} takes
         */
        public TiffEncoderOptions build() {
            // TODO: JPEG XL pages, once Smalt has a JPEG XL encoder
            switch (compression) {
                case NONE, PACKBITS, LZW, DEFLATE, ZSTD, CCITT_RLE, CCITT_RLEW, CCITT_T4, CCITT_T6, JPEG, WEBP,
                     JPEG_2000 -> {
                }
                default -> throw new IllegalArgumentException("TIFF compression " + compression + " cannot be written");
            }
            Checks.inRange("compression level", compressionLevel, 1, 9);
            if (chromaFormat == ChromaFormat.MONOCHROME) {
                throw new IllegalArgumentException("a Y'CbCr page needs chroma; use the GRAY layout instead");
            }
            if (chromaFormat == ChromaFormat.YUV440
                    || compression == TiffCompression.JPEG && jpegOptions.chromaFormat() == ChromaFormat.YUV440) {
                throw new IllegalArgumentException("TIFF cannot subsample chroma vertically only (4:4:0)");
            }
            var fixedDepth = layout == Layout.PALETTE || layout == Layout.BILEVEL || layout == Layout.YCBCR;
            if (fixedDepth && depth != Depth.AUTO && !(layout == Layout.YCBCR && depth == Depth.EIGHT)) {
                throw new IllegalArgumentException("the " + layout + " layout has its own depth, not " + depth);
            }
            var fax = compression == TiffCompression.CCITT_RLE || compression == TiffCompression.CCITT_RLEW
                    || compression == TiffCompression.CCITT_T4 || compression == TiffCompression.CCITT_T6;
            if (fax && ((layout != Layout.AUTO && layout != Layout.BILEVEL) || depth != Depth.AUTO)) {
                throw new IllegalArgumentException("CCITT compression codes bilevel pages only, not " + layout + " "
                        + depth);
            }
            var eightBit = depth == Depth.AUTO || depth == Depth.EIGHT;
            switch (compression) {
                case WEBP -> {
                    if ((layout != Layout.AUTO && layout != Layout.RGB && layout != Layout.RGBA) || !eightBit
                            || planar) {
                        throw new IllegalArgumentException("WebP-compressed TIFF pages hold interleaved 8-bit RGB or"
                                + " RGBA samples only");
                    }
                }
                case JPEG -> {
                    if ((layout != Layout.AUTO && layout != Layout.GRAY && layout != Layout.RGB
                            && layout != Layout.YCBCR) || !eightBit || planar) {
                        throw new IllegalArgumentException("JPEG-compressed TIFF pages hold interleaved 8-bit gray or"
                                + " Y'CbCr samples only");
                    }
                    var jpeg = jpegOptions;
                    if (jpeg.progressive() || jpeg.arithmetic() || jpeg.lossless() || jpeg.precision().orElse(8) != 8
                            || jpeg.colorSpace() != JpegEncoderOptions.ColorSpace.YCBCR || jpeg.chromaQuality().isPresent()
                            || !jpeg.samplingFactors().isEmpty() || !jpeg.quantizationTables().isEmpty()
                            || !jpeg.quantizationSlots().isEmpty() || !jpeg.baselineQuantization()
                            || !jpeg.scanScript().isEmpty()) {
                        throw new IllegalArgumentException("JPEG-compressed TIFF pages take only the quality, chroma"
                                + " subsampling, Huffman optimization and restart interval of the JPEG settings");
                    }
                }
                case JPEG_2000 -> {
                    if (layout == Layout.PALETTE || layout == Layout.BILEVEL || layout == Layout.YCBCR
                            || depth == Depth.FLOAT || planar) {
                        throw new IllegalArgumentException("JPEG 2000-compressed TIFF pages hold interleaved integer"
                                + " samples of the gray, RGB and CMYK layouts only");
                    }
                }
                default -> {
                }
            }
            if (stripsSet) {
                Checks.positive("rows per strip", rowsPerStrip);
                var group = compression == TiffCompression.JPEG ? 8 * verticalSubsampling()
                        : layout == Layout.YCBCR ? verticalSubsampling() : 1;
                if (rowsPerStrip % group != 0) {
                    throw new IllegalArgumentException("rows per strip " + rowsPerStrip + " are not a multiple of "
                            + group);
                }
            }
            if (tilesSet) {
                Checks.positive("tile width", tileWidth);
                Checks.positive("tile height", tileHeight);
                if (tileWidth % 16 != 0 || tileHeight % 16 != 0) {
                    throw new IllegalArgumentException("tile size " + tileWidth + "x" + tileHeight
                            + " is not a multiple of 16");
                }
            }
            return new TiffEncoderOptions(this);
        }

        /**
         * {@return the vertical chroma subsampling of the color pages of these settings}
         */
        private int verticalSubsampling() {
            if (compression == TiffCompression.JPEG && layout == Layout.GRAY) {
                return 1;
            }
            var format = compression == TiffCompression.JPEG ? jpegOptions.chromaFormat() : chromaFormat;
            return format == ChromaFormat.YUV420 ? 2 : 1;
        }
    }
}
