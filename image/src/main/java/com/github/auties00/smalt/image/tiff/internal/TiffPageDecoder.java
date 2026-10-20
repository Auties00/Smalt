package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.internal.zlib.ZlibInflater;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.zip.DataFormatException;

/**
 * Decodes the pixels of one TIFF image file directory.
 *
 * <p>The directory is validated when the decoder is created: the image size, the sample layout
 * (bits per sample, sample format, samples per pixel and extra samples), the photometric
 * interpretation, the compression and predictor, and the strip or tile geometry. Decoding then
 * reads each strip or tile, reverses its bit order when {@code FillOrder} is 2, decompresses it,
 * reverses the predictor, and converts its samples to RGBA with a {@link SampleConverter}. Strips
 * and tiles are independent, so they are decoded in parallel bands; separated planes
 * ({@code PlanarConfiguration} 2) are gathered into one interleaved buffer first.
 *
 * <p>Damaged data is tolerated as libtiff tolerates it: a strip or tile whose offset is missing
 * or zero, or whose data ends early, leaves its pixels zero (transparent when missing); invalid
 * LZW, Deflate, WebP, JPEG XL or JPEG 2000 data is malformed; damaged fax data ends the strip.
 *
 * <p>WebP, JPEG XL and JPEG 2000 strips and tiles hold complete images, which
 * {@link TiffImageCodecs} decodes into the stored sample layout before the predictor and the
 * sample conversion run.
 *
 * <p>SGILog data (LogL and LogLuv) is decoded row by row to 8-bit gray or RGB, as libtiff renders
 * it for display, and then takes the gray or RGB path; a row whose run-length data ends early
 * ends its strip or tile, leaving the rest zero.
 *
 * <p>A decoder created with {@link #ofChannels(TiffDirectory)} decodes the pages that need a
 * {@link ChannelPicture} (see {@link #channels()}) to one instead: the samples are unpacked into
 * one interleaved buffer, without photometric conversion except that white-is-zero gray is
 * inverted, and wrapped with the page's {@link PixelLayout}.
 *
 * @implNote This implementation follows libtiff's {@code TIFFReadRGBAImage} (tif_getimage.c) for
 * the meaning of the tags it combines: the sample layout as {@link TiffLayout} reads it, and a
 * palette whose entries all fit 8 bits is an old-style 8-bit palette. Subsampled YCbCr data units
 * hold the luma samples of a block row by row followed by one Cb and one Cr sample, which every
 * pixel of the block shares (no chroma interpolation).
 */
public final class TiffPageDecoder {
    /** Compression: none. */
    static final int NONE = 1;
    /** Compression: CCITT modified Huffman. */
    static final int CCITT_RLE = 2;
    /** Compression: CCITT T.4. */
    static final int CCITT_T4 = 3;
    /** Compression: CCITT T.6. */
    static final int CCITT_T6 = 4;
    /** Compression: LZW. */
    static final int LZW = 5;
    /** Compression: old-style JPEG. */
    static final int OLD_JPEG = 6;
    /** Compression: JPEG (Technical Note 2). */
    static final int JPEG = 7;
    /** Compression: Deflate (Adobe code). */
    static final int ADOBE_DEFLATE = 8;
    /** Compression: CCITT modified Huffman, word-aligned. */
    static final int CCITT_RLEW = 32771;
    /** Compression: PackBits. */
    static final int PACKBITS = 32773;
    /** Compression: ThunderScan 4-bit. */
    static final int THUNDERSCAN = 32809;
    /** Compression: Deflate (old code). */
    static final int DEFLATE = 32946;
    /** Compression: SGILog (run-length LogL or 32-bit LogLuv). */
    static final int SGILOG = 34676;
    /** Compression: SGILog24 (24-bit LogLuv). */
    static final int SGILOG24 = 34677;
    /** Compression: JPEG 2000 codestreams or JP2 files. */
    static final int JPEG_2000 = 34712;
    /** Compression: Aperio JPEG 2000 codestreams of Y, Cb and Cr components. */
    static final int APERIO_YCBCR = 33003;
    /** Compression: Aperio JPEG 2000 codestreams of RGB components. */
    static final int APERIO_RGB = 33005;
    /** Compression: WebP files. */
    static final int WEBP = 50001;
    /** Compression: JPEG XL codestreams or containers. */
    static final int JPEG_XL = 50002;
    /** Compression: JPEG XL, DNG 1.7 code. */
    static final int JPEG_XL_DNG = 52546;

    /** Photometric: white is zero. */
    static final int WHITE_IS_ZERO = 0;
    /** Photometric: black is zero. */
    static final int BLACK_IS_ZERO = 1;
    /** Photometric: RGB. */
    static final int RGB = 2;
    /** Photometric: palette. */
    static final int PALETTE = 3;
    /** Photometric: transparency mask. */
    static final int MASK = 4;
    /** Photometric: separated (CMYK). */
    static final int SEPARATED = 5;
    /** Photometric: YCbCr. */
    static final int YCBCR = 6;
    /** Photometric: CIE L*a*b*. */
    static final int CIELAB = 8;
    /** Photometric: ICC L*a*b*. */
    static final int ICCLAB = 9;
    /** Photometric: ITU L*a*b*. */
    static final int ITULAB = 10;
    /** Photometric: LogL. */
    static final int LOG_L = 32844;
    /** Photometric: LogLuv. */
    static final int LOG_LUV = 32845;

    /** The mode of {@link #of(TiffDirectory)}: RGBA pixels. */
    private static final int RGBA_MODE = 0;

    /** The mode of {@link #ofRawCmyk(TiffDirectory)}: CMYK inks copied into the RGBA slots. */
    private static final int RAW_CMYK_MODE = 1;

    /** The mode of {@link #ofChannels(TiffDirectory)}: channel pictures for the pages that need them. */
    private static final int CHANNELS_MODE = 2;

    /**
     * The directory.
     */
    private final TiffDirectory directory;

    /**
     * The file.
     */
    private final TiffFile file;

    /**
     * The limits.
     */
    private final MediaLimits limits;

    /**
     * The image width.
     */
    private final int width;

    /**
     * The image height.
     */
    private final int height;

    /**
     * The samples per pixel.
     */
    private final int samples;

    /**
     * The bits per sample.
     */
    private final int bits;

    /**
     * The sample format: 1 unsigned, 2 signed, 3 floating point.
     */
    private final int format;

    /**
     * The compression code.
     */
    private final int compression;

    /**
     * The photometric interpretation code as stored, or as defaulted.
     */
    private final int photometric;

    /**
     * Whether samples are in separate planes.
     */
    private final boolean planar;

    /**
     * The predictor code.
     */
    private final int predictor;

    /**
     * Whether the coded bytes are least significant bit first.
     */
    private final boolean reverseBits;

    /**
     * Whether the image is tiled.
     */
    private final boolean tiled;

    /**
     * The width of a strip or tile.
     */
    private final int chunkWidth;

    /**
     * The height of a strip or tile.
     */
    private final int chunkHeight;

    /**
     * The number of strips or tiles across.
     */
    private final int across;

    /**
     * The number of strips or tiles down.
     */
    private final int down;

    /**
     * The number of planes holding strips or tiles.
     */
    private final int planes;

    /**
     * The strip or tile offsets.
     */
    private final long[] offsets;

    /**
     * The strip or tile byte counts, or {@code null} when absent.
     */
    private final long[] byteCounts;

    /**
     * The horizontal YCbCr subsampling, 1 when not subsampled.
     */
    private final int subsampleX;

    /**
     * The vertical YCbCr subsampling, 1 when not subsampled.
     */
    private final int subsampleY;

    /**
     * Whether the samples are YCbCr that the compression decodes to RGB: JPEG, WebP, JPEG XL and
     * JPEG 2000 data of a YCbCr page.
     */
    private final boolean jpegYcbcr;

    /**
     * The conversion of the Y, Cb and Cr channels of JPEG 2000 data to RGB, or {@code null}.
     */
    private final YCbCrTables jpeg2000Ycbcr;

    /**
     * The decoders of WebP, JPEG XL and JPEG 2000 data, or {@code null} for other compressions.
     */
    private final TiffImageCodecs codecs;

    /**
     * The {@code JPEGTables} stream, or {@code null}.
     */
    private final byte[] jpegTables;

    /**
     * The T4Options or T6Options value.
     */
    private final long faxOptions;

    /**
     * The YCbCr tables, or {@code null}.
     */
    private final YCbCrTables ycbcr;

    /**
     * The sample converter.
     */
    private final SampleConverter converter;

    /**
     * The display orientation.
     */
    private final Orientation orientation;

    /**
     * The layout of the channel picture the page decodes to, or {@code null} when it decodes to RGBA.
     */
    private final PixelLayout channelLayout;

    /**
     * The sum of {@code SMinSampleValue} and {@code SMaxSampleValue}, which inverts white-is-zero
     * floating-point gray.
     */
    private final double floatRange;

    /**
     * Validates a directory.
     *
     * @param directory the directory to decode
     * @param mode {@link #RGBA_MODE}, {@link #RAW_CMYK_MODE} or {@link #CHANNELS_MODE}
     * @throws MediaException.Malformed if a required tag is missing or invalid
     * @throws MediaException.Unsupported if the directory uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    private TiffPageDecoder(TiffDirectory directory, int mode) {
        this.directory = directory;
        this.file = directory.file();
        this.limits = file.limits();
        width = positive(directory.longValue(TiffTags.IMAGE_WIDTH, 0), "ImageWidth");
        height = positive(directory.longValue(TiffTags.IMAGE_LENGTH, 0), "ImageLength");
        var spp = directory.longValue(TiffTags.SAMPLES_PER_PIXEL, 1);
        if (spp < 1 || spp > 64) {
            throw new MediaException.Unsupported("tiff", "TIFF images with " + spp + " samples per pixel are not supported");
        }
        var layout = TiffLayout.of(directory);
        samples = (int) spp;
        photometric = layout.photometric();
        var logLuv = photometric == LOG_L || photometric == LOG_LUV;
        var lab = photometric == CIELAB || photometric == ICCLAB || photometric == ITULAB;
        var storedBits = uniform(directory.longs(TiffTags.BITS_PER_SAMPLE), 1, "BitsPerSample");
        var sampleFormat = uniform(directory.longs(TiffTags.SAMPLE_FORMAT), 1, "SampleFormat");
        var storedFormat = sampleFormat == 4 ? 1 : sampleFormat;
        if (!logLuv && (storedFormat < 1 || storedFormat > 3)) {
            throw new MediaException.Unsupported("tiff sample format " + storedFormat, "TIFF sample format " + storedFormat + " is not supported");
        }
        if (lab && (storedFormat == 3 || (storedBits != 8 && storedBits != 16))) {
            throw new MediaException.Unsupported("tiff cielab " + storedBits + "-bit",
                    "only 8- and 16-bit integer L*a*b* TIFF images are supported");
        }
        bits = logLuv ? 8 : storedBits;
        format = logLuv || lab ? 1 : storedFormat;
        checkBits();
        compression = (int) directory.longValue(TiffTags.COMPRESSION, NONE);
        planar = layout.planar();
        predictor = compression == SGILOG || compression == SGILOG24 ? 1 : (int) directory.longValue(TiffTags.PREDICTOR, 1);
        reverseBits = directory.longValue(TiffTags.FILL_ORDER, 1) == 2;
        orientation = layout.orientation();
        faxOptions = directory.longValue(compression == CCITT_T6 ? TiffTags.T6_OPTIONS : TiffTags.T4_OPTIONS, 0);
        checkCompression();
        switch (photometric) {
            case WHITE_IS_ZERO, BLACK_IS_ZERO, PALETTE, LOG_L, RGB, YCBCR, CIELAB, ICCLAB, ITULAB, LOG_LUV, SEPARATED -> {
            }
            case MASK -> throw new MediaException.Unsupported("tiff transparency mask", "TIFF transparency masks are not supported");
            case 32803, 34892 -> throw new MediaException.Unsupported("tiff raw", "CFA and linear raw TIFF images are not supported");
            default -> throw new MediaException.Unsupported("tiff photometric " + photometric,
                    "TIFF photometric interpretation " + photometric + " is not supported");
        }
        var colorChannels = layout.colorChannels();
        var inkSet = photometric == SEPARATED ? directory.longValue(TiffTags.INK_SET, 1) : 1;
        var inks = inkSet != 1 && mode == CHANNELS_MODE ? inkCount(directory, samples) : 0;
        if (inks > 0) {
            colorChannels = inks;
        } else if (inkSet != 1) {
            throw new MediaException.Unsupported("tiff ink set", "TIFF separated images with an ink set other than CMYK are not supported");
        }
        if (samples < colorChannels) {
            throw new MediaException.Malformed("TIFF photometric interpretation " + photometric + " needs " + colorChannels
                    + " samples per pixel, the image has " + samples);
        }
        if (logLuv) {
            if (compression != SGILOG && (photometric == LOG_L || compression != SGILOG24)) {
                throw new MediaException.Unsupported("tiff logluv", "LogL and LogLuv TIFF images need SGILog compression");
            }
            if (samples != colorChannels || planar) {
                throw new MediaException.Unsupported("tiff logluv",
                        "LogL and LogLuv TIFF images with extra samples or separate planes are not supported");
            }
        }
        var alpha = layout.alpha();
        var associated = layout.associated();
        var channelEligible = photometric == SEPARATED || photometric == WHITE_IS_ZERO || photometric == BLACK_IS_ZERO
                || photometric == RGB;
        if (mode == CHANNELS_MODE && channelEligible && (inks > 0 || directory.has(TiffTags.EXTRA_SAMPLES))) {
            // the stored meaning of each extra sample, not the unspecified-as-alpha reading of RGBA mode
            alpha = -1;
            associated = false;
            var kinds = directory.longs(TiffTags.EXTRA_SAMPLES);
            for (var i = 0; kinds != null && i < kinds.length && colorChannels + i < samples; i++) {
                if (kinds[i] == 1 || kinds[i] == 2) {
                    alpha = colorChannels + i;
                    associated = kinds[i] == 1;
                    break;
                }
            }
        }
        var codec = codec(compression);
        jpegYcbcr = (compression == JPEG || compression == OLD_JPEG || codec) && photometric == YCBCR;
        if (photometric == YCBCR && !jpegYcbcr) {
            if (bits != 8 || format != 1) {
                throw new MediaException.Unsupported("tiff ycbcr " + bits + "-bit", "only 8-bit YCbCr TIFF images are supported");
            }
            var factors = directory.longs(TiffTags.Y_CB_CR_SUBSAMPLING);
            var sx = factors != null && factors.length > 0 ? factors[0] : 2;
            var sy = factors != null && factors.length > 1 ? factors[1] : 2;
            if ((sx != 1 && sx != 2 && sx != 4) || (sy != 1 && sy != 2 && sy != 4)) {
                throw new MediaException.Malformed("invalid TIFF YCbCr subsampling " + sx + "x" + sy);
            }
            // TODO: subsampled YCbCr in separate planes, with extra samples or with 16-bit samples
            if ((sx != 1 || sy != 1) && (planar || samples != 3 || predictor != 1)) {
                throw new MediaException.Unsupported("tiff ycbcr subsampling",
                        "subsampled YCbCr TIFF images with separate planes, extra samples or a predictor are not supported");
            }
            subsampleX = (int) sx;
            subsampleY = (int) sy;
            ycbcr = new YCbCrTables(floats(directory.doubles(TiffTags.Y_CB_CR_COEFFICIENTS), new float[]{0.299F, 0.587F, 0.114F}),
                    floats(directory.doubles(TiffTags.REFERENCE_BLACK_WHITE), new float[]{0, 255, 128, 255, 128, 255}));
        } else {
            subsampleX = 1;
            subsampleY = 1;
            ycbcr = compression == OLD_JPEG && photometric == YCBCR
                    ? new YCbCrTables(floats(directory.doubles(TiffTags.Y_CB_CR_COEFFICIENTS), new float[]{0.299F, 0.587F, 0.114F}),
                    floats(directory.doubles(TiffTags.REFERENCE_BLACK_WHITE), new float[]{0, 255, 128, 255, 128, 255}))
                    : null;
        }
        jpeg2000Ycbcr = compression == APERIO_YCBCR || (compression == JPEG_2000 && photometric == YCBCR)
                ? new YCbCrTables(
                        floats(directory.doubles(TiffTags.Y_CB_CR_COEFFICIENTS), new float[]{0.299F, 0.587F, 0.114F}),
                        floats(directory.doubles(TiffTags.REFERENCE_BLACK_WHITE), new float[]{0, 255, 128, 255, 128, 255}))
                : null;
        if (compression == JPEG && ((bits != 8 && bits != 12) || format != 1)) {
            throw new MediaException.Unsupported("tiff jpeg " + bits + "-bit",
                    "only 8-bit and 12-bit JPEG-compressed TIFF images are supported");
        }
        if (compression == OLD_JPEG && (bits != 8 || format != 1)) {
            throw new MediaException.Unsupported("tiff old-style jpeg " + bits + "-bit",
                    "only 8-bit old-style JPEG TIFF images are supported");
        }
        long[] colorMap = null;
        if (photometric == PALETTE) {
            if (bits > 16 || format == 3) {
                throw new MediaException.Unsupported("tiff palette " + bits + "-bit", "TIFF palettes of " + bits + "-bit samples are not supported");
            }
            colorMap = directory.longs(TiffTags.COLOR_MAP);
            var entries = 3L << bits;
            if (colorMap == null || colorMap.length < entries) {
                throw new MediaException.Malformed("the TIFF palette image has no complete ColorMap");
            }
            if (colorMap.length > entries) {
                colorMap = Arrays.copyOf(colorMap, (int) entries);
            }
        }
        if (predictor == 2 && bits != 8 && bits != 16 && bits != 32 && bits != 64) {
            throw new MediaException.Unsupported("tiff predictor", "the horizontal predictor is not supported with " + bits + "-bit samples");
        }
        if (predictor == 3 && (format != 3 || (bits != 16 && bits != 32 && bits != 64))) {
            throw new MediaException.Unsupported("tiff predictor", "the floating-point predictor needs 16-, 32- or 64-bit floating-point samples");
        }
        if (predictor != 1 && predictor != 2 && predictor != 3) {
            throw new MediaException.Unsupported("tiff predictor " + predictor, "TIFF predictor " + predictor + " is not supported");
        }
        var minimum = directory.doubleValue(TiffTags.S_MIN_SAMPLE_VALUE, 0);
        var maximum = directory.doubleValue(TiffTags.S_MAX_SAMPLE_VALUE, 1);
        var kind = switch (photometric) {
            case WHITE_IS_ZERO, BLACK_IS_ZERO, LOG_L -> SampleConverter.GRAY;
            case PALETTE -> SampleConverter.PALETTE;
            case SEPARATED -> mode == RAW_CMYK_MODE && alpha < 0 ? SampleConverter.RAW_CMYK : SampleConverter.CMYK;
            case YCBCR -> jpegYcbcr ? SampleConverter.RGB : SampleConverter.YCBCR;
            case CIELAB, ICCLAB, ITULAB -> SampleConverter.LAB;
            default -> SampleConverter.RGB;
        };
        var labTables = lab ? new LabTables(photometric, directory.doubles(TiffTags.WHITE_POINT), directory.doubles(TiffTags.DECODE)) : null;
        converter = new SampleConverter(kind, photometric == WHITE_IS_ZERO, samples, alpha, associated, bits, format,
                file.little(), format == 3 ? minimum : 0, format == 3 ? maximum : 1, colorMap, ycbcr, labTables);
        LimitChecks.checkPicture(limits, width, height);
        tiled = directory.has(TiffTags.TILE_WIDTH) || directory.has(TiffTags.TILE_OFFSETS);
        if (tiled) {
            chunkWidth = positive(directory.longValue(TiffTags.TILE_WIDTH, 0), "TileWidth");
            chunkHeight = positive(directory.longValue(TiffTags.TILE_LENGTH, 0), "TileLength");
            if ((long) chunkWidth * chunkHeight > limits.maxPixels() * 4) {
                throw new MediaException.LimitExceeded("TIFF tile of " + chunkWidth + "x" + chunkHeight + " pixels");
            }
            offsets = directory.has(TiffTags.TILE_OFFSETS) ? directory.longs(TiffTags.TILE_OFFSETS)
                    : directory.longs(TiffTags.STRIP_OFFSETS);
            byteCounts = directory.has(TiffTags.TILE_BYTE_COUNTS) ? directory.longs(TiffTags.TILE_BYTE_COUNTS)
                    : directory.longs(TiffTags.STRIP_BYTE_COUNTS);
        } else {
            chunkWidth = width;
            var rows = directory.longValue(TiffTags.ROWS_PER_STRIP, height);
            chunkHeight = (int) (rows <= 0 || rows > height ? height : rows);
            offsets = directory.longs(TiffTags.STRIP_OFFSETS);
            byteCounts = directory.longs(TiffTags.STRIP_BYTE_COUNTS);
        }
        codecs = codec ? new TiffImageCodecs(limits, chunkWidth, chunkHeight) : null;
        across = (int) ((width + (long) chunkWidth - 1) / chunkWidth);
        down = (int) ((height + (long) chunkHeight - 1) / chunkHeight);
        planes = planar ? samples : 1;
        if (offsets == null && !(compression == OLD_JPEG && directory.has(TiffTags.JPEG_INTERCHANGE_FORMAT))) {
            throw new MediaException.Malformed("the TIFF image has no " + (tiled ? "TileOffsets" : "StripOffsets"));
        }
        if (compression == JPEG) {
            var tables = directory.bytes(TiffTags.JPEG_TABLES);
            jpegTables = tables != null && tables.length > 4 ? tables : null;
        } else {
            jpegTables = null;
        }
        LimitChecks.checkAllocation(limits, chunkBytes());
        floatRange = minimum + maximum;
        var needsChannels = photometric == SEPARATED
                || ((photometric == WHITE_IS_ZERO || photometric == BLACK_IS_ZERO || photometric == RGB)
                && !jpegYcbcr && (format == 3 || samples > colorChannels + (alpha >= 0 ? 1 : 0)));
        channelLayout = mode == CHANNELS_MODE && needsChannels
                ? channelLayout(directory, colorChannels, inks, alpha, associated) : null;
    }

    /**
     * Returns the number of inks of a separated image with an ink set other than CMYK.
     *
     * @param directory the image file directory to decode
     * @param samples the samples per pixel
     * @return {@code NumberOfInks}, or the samples that are not extra samples when the tag is absent
     * @throws MediaException.Malformed if the count is not positive or exceeds the samples per pixel
     */
    private static int inkCount(TiffDirectory directory, int samples) {
        var extrasIndex = directory.find(TiffTags.EXTRA_SAMPLES);
        var extras = extrasIndex < 0 ? 0 : directory.valueCount(extrasIndex);
        var inks = directory.longValue(TiffTags.NUMBER_OF_INKS, samples - extras);
        if (inks <= 0 || inks > samples) {
            throw new MediaException.Malformed("the TIFF separated image has " + inks + " inks and " + samples
                    + " samples per pixel");
        }
        return (int) inks;
    }

    /**
     * Builds the channel layout of a page.
     *
     * @param directory the image file directory to decode
     * @param colors the number of color samples
     * @param inks the number of inks of an ink set other than CMYK, or 0
     * @param alpha the index of the alpha sample, or -1
     * @param associated whether the alpha is associated
     * @return the layout
     * @throws MediaException.Malformed if a tag it reads is invalid
     */
    private PixelLayout channelLayout(TiffDirectory directory, int colors, int inks, int alpha,
                                        boolean associated) {
        var channels = new ArrayList<Channel>(samples);
        if (inks > 0) {
            var names = inkNames(directory, inks);
            for (var i = 0; i < inks; i++) {
                channels.add(Channel.ofInk(names[i]));
            }
        } else {
            channels.addAll((switch (photometric) {
                case SEPARATED -> PixelLayout.CMYK;
                case RGB -> PixelLayout.RGB;
                default -> PixelLayout.GRAY;
            }).channels());
        }
        for (var i = colors; i < samples; i++) {
            channels.add(Channel.of(i == alpha ? (associated ? ChannelType.PREMULTIPLIED_ALPHA : ChannelType.ALPHA)
                    : ChannelType.UNSPECIFIED));
        }
        return new PixelLayout(channels);
    }

    /**
     * Reads the names of the inks.
     *
     * @param directory the image file directory to decode
     * @param inks the number of inks
     * @return one name per ink, empty for an ink {@code InkNames} does not name
     * @throws MediaException.Malformed if the tag's values are truncated
     */
    private static String[] inkNames(TiffDirectory directory, int inks) {
        var names = new String[inks];
        Arrays.fill(names, "");
        var data = directory.bytes(TiffTags.INK_NAMES);
        if (data == null) {
            return names;
        }
        var start = 0;
        var index = 0;
        for (var i = 0; i <= data.length && index < inks; i++) {
            if (i == data.length || data[i] == 0) {
                if (i > start || i < data.length) {
                    names[index++] = new String(data, start, i - start, StandardCharsets.UTF_8);
                }
                start = i + 1;
            }
        }
        return names;
    }

    /**
     * Validates a directory for decoding.
     *
     * @param directory the directory to decode
     * @return the decoder
     * @throws MediaException.Malformed if a required tag is missing or invalid
     * @throws MediaException.Unsupported if the directory uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public static TiffPageDecoder of(TiffDirectory directory) {
        return new TiffPageDecoder(directory, RGBA_MODE);
    }

    /**
     * Validates a directory for decoding, to a channel picture when the page needs one.
     *
     * <p>A separated page of any ink set, and a gray or RGB page with floating-point samples or with
     * extra samples other than one alpha sample, then decodes to a {@link ChannelPicture}; other
     * pages decode as {@link #of(TiffDirectory)} decodes them.
     *
     * @param directory the directory to decode
     * @return the decoder
     * @throws MediaException.Malformed if a required tag is missing or invalid
     * @throws MediaException.Unsupported if the directory uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public static TiffPageDecoder ofChannels(TiffDirectory directory) {
        return new TiffPageDecoder(directory, CHANNELS_MODE);
    }

    /**
     * Returns whether the page decodes to a channel picture.
     *
     * @return {@code true} for a decoder from {@link #ofChannels(TiffDirectory)} of a page that needs one
     */
    public boolean channels() {
        return channelLayout != null;
    }

    /**
     * Validates a directory for decoding, keeping the ink samples of a CMYK image.
     *
     * <p>A CMYK image without alpha then decodes to pixels whose red, green, blue and alpha bytes
     * hold its cyan, magenta, yellow and black samples, ready for a CMYK color profile; other
     * images decode as {@link #of(TiffDirectory)} decodes them.
     *
     * @param directory the directory to decode
     * @return the decoder
     * @throws MediaException.Malformed if a required tag is missing or invalid
     * @throws MediaException.Unsupported if the directory uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public static TiffPageDecoder ofRawCmyk(TiffDirectory directory) {
        return new TiffPageDecoder(directory, RAW_CMYK_MODE);
    }

    /**
     * Returns the photometric interpretation code, as stored or as defaulted.
     *
     * @return the code
     */
    public int photometric() {
        return photometric;
    }

    /**
     * Returns the image width.
     *
     * @return the width in pixels
     */
    public int width() {
        return width;
    }

    /**
     * Returns the image height.
     *
     * @return the height in pixels
     */
    public int height() {
        return height;
    }

    /**
     * Returns whether the pixels carry alpha.
     *
     * @return {@code true} when an extra sample is alpha
     */
    public boolean hasAlpha() {
        return converter.hasAlpha();
    }

    /**
     * Returns whether a 16-bit picture is available.
     *
     * @return {@code true} when the samples are wider than 8 bits or floating point, L*a*b*
     * excepted
     */
    public boolean wide() {
        return converter.wideOutput();
    }

    /**
     * Returns the display orientation.
     *
     * @return the orientation, {@link Orientation#NORMAL} when absent
     */
    public Orientation orientation() {
        return orientation;
    }

    /**
     * Decodes the pixels.
     *
     * @param highBitDepth whether a 16-bit picture is wanted when the samples are wider than
     * 8 bits
     * @return an {@link RgbaPicture}, or an {@link Rgba16Picture} when {@code highBitDepth} is set
     * and {@link #wide()} holds; for a page for which {@link #channels()} holds, a
     * {@link ChannelFloatPicture} for floating-point samples, a {@link Channel16Picture} for wider
     * integer samples when {@code highBitDepth} is set, and a {@link Channel8Picture} otherwise
     * @throws MediaException.Malformed if the compressed data is invalid
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public Picture decode(boolean highBitDepth) {
        if (channelLayout != null) {
            return decodeChannels(highBitDepth);
        }
        var sixteen = highBitDepth && converter.wideOutput();
        LimitChecks.checkAllocation(limits, (long) width * height * (sixteen ? 8 : 4));
        byte[] out8 = null;
        short[] out16 = null;
        Picture picture;
        if (sixteen) {
            var wide = Rgba16Picture.allocate(width, height, converter.hasAlpha());
            out16 = wide.pixels();
            picture = wide;
        } else {
            var narrow = RgbaPicture.allocate(width, height, converter.hasAlpha());
            out8 = narrow.pixels();
            picture = narrow;
        }
        var target = new Target(out8, out16);
        if (compression == OLD_JPEG && directory.has(TiffTags.JPEG_INTERCHANGE_FORMAT) && decodeOldJpegStream(target)) {
            return finish(picture, target);
        }
        if (compression == OLD_JPEG && (offsets == null || !directory.has(TiffTags.JPEG_Q_TABLES))) {
            throw new MediaException.Unsupported("tiff old-style jpeg", "the old-style JPEG TIFF image has neither a decodable JPEG stream nor JPEG tables");
        }
        if (planar) {
            LimitChecks.checkAllocation(limits, (long) width * height * samples * (converter.wide() ? 2 : 1));
            if (converter.wide()) {
                target.work16 = new short[width * height * samples];
            } else {
                target.work8 = new byte[width * height * samples];
            }
        }
        var count = planes * across * down;
        var bands = ParallelBands.bands(count, (long) width * height * samples);
        ParallelBands.run(bands, band -> {
            var context = new Context();
            try {
                var first = ParallelBands.start(band, bands, count);
                var last = ParallelBands.start(band + 1, bands, count);
                for (var chunk = first; chunk < last; chunk++) {
                    decodeChunk(chunk, context, target);
                }
            } finally {
                context.close();
            }
        });
        if (planar) {
            convertPlanar(target);
        }
        return finish(picture, target);
    }

    /**
     * Decodes a page to a channel picture.
     *
     * @param highBitDepth whether integer samples wider than 8 bits keep 16 bits
     * @return the picture
     * @throws MediaException.Malformed if the compressed data is invalid
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    private ChannelPicture decodeChannels(boolean highBitDepth) {
        var floating = converter.floating();
        var wide = !floating && bits > 8;
        var count = (long) width * height * samples;
        LimitChecks.checkAllocation(limits, count * (floating ? 4 : wide ? 2 : 1));
        var target = new Target(null, null);
        target.channels = true;
        if (floating) {
            target.workF = new float[(int) count];
        } else if (wide) {
            target.work16 = new short[(int) count];
        } else {
            target.work8 = new byte[(int) count];
        }
        if (!(compression == OLD_JPEG && directory.has(TiffTags.JPEG_INTERCHANGE_FORMAT)
                && decodeOldJpegStream(target))) {
            if (compression == OLD_JPEG && (offsets == null || !directory.has(TiffTags.JPEG_Q_TABLES))) {
                throw new MediaException.Unsupported("tiff old-style jpeg", "the old-style JPEG TIFF image has neither a decodable JPEG stream nor JPEG tables");
            }
            var chunks = planes * across * down;
            var bands = ParallelBands.bands(chunks, count);
            ParallelBands.run(bands, band -> {
                var context = new Context();
                try {
                    var first = ParallelBands.start(band, bands, chunks);
                    var last = ParallelBands.start(band + 1, bands, chunks);
                    for (var chunk = first; chunk < last; chunk++) {
                        decodeChunk(chunk, context, target);
                    }
                } finally {
                    context.close();
                }
            });
        }
        var invert = photometric == WHITE_IS_ZERO;
        var stride = width * samples;
        if (floating) {
            var work = target.workF;
            if (invert) {
                var range = (float) floatRange;
                for (var i = 0; i < work.length; i += samples) {
                    work[i] = range - work[i];
                }
            }
            return ChannelFloatPicture.of(width, height, stride, work, channelLayout);
        }
        if (wide) {
            var work = target.work16;
            if (invert) {
                for (var i = 0; i < work.length; i += samples) {
                    work[i] = (short) ~work[i];
                }
            }
            var picture = Channel16Picture.of(width, height, stride, work, channelLayout);
            return highBitDepth ? picture : ImageOps.toChannel8(picture);
        }
        var work = target.work8;
        if (invert) {
            for (var i = 0; i < work.length; i += samples) {
                work[i] = (byte) ~work[i];
            }
        }
        return Channel8Picture.of(width, height, stride, work, channelLayout);
    }

    /**
     * Marks a picture as carrying transparency when strips or tiles were missing.
     *
     * @param picture the decoded picture
     * @param target the destination the picture was decoded into
     * @return the picture, relabeled when needed
     */
    private Picture finish(Picture picture, Target target) {
        if (target.incomplete && picture instanceof RgbaPicture narrow && !narrow.hasAlpha()) {
            return RgbaPicture.of(width, height, width * 4, narrow.pixels(), true);
        }
        if (target.incomplete && picture instanceof Rgba16Picture wide && !wide.hasAlpha()) {
            return Rgba16Picture.of(width, height, width * 4, wide.pixels(), true);
        }
        return picture;
    }

    /**
     * Holds the destination of a decode.
     */
    private static final class Target {
        /**
         * The 8-bit RGBA pixels, or {@code null}.
         */
        private final byte[] out8;

        /**
         * The 16-bit RGBA pixels, or {@code null}.
         */
        private final short[] out16;

        /**
         * The interleaved 8-bit work samples of a planar image, or {@code null}.
         */
        private byte[] work8;

        /**
         * The interleaved 16-bit work samples of a planar image, or {@code null}.
         */
        private short[] work16;

        /**
         * The interleaved floating-point samples of a channel picture, or {@code null}.
         */
        private float[] workF;

        /**
         * Whether the samples are unpacked into the work samples of a channel picture rather than
         * converted to RGBA.
         */
        private boolean channels;

        /**
         * Whether a strip or tile was missing.
         */
        private volatile boolean incomplete;

        /**
         * Constructs a target.
         *
         * @param out8 the 8-bit pixels, or {@code null}
         * @param out16 the 16-bit pixels, or {@code null}
         */
        private Target(byte[] out8, short[] out16) {
            this.out8 = out8;
            this.out16 = out16;
        }
    }

    /**
     * Holds the per-band buffers and decompressors.
     */
    private static final class Context implements AutoCloseable {
        /**
         * The compressed bytes of the current strip or tile.
         */
        private byte[] raw = new byte[0];

        /**
         * The decompressed bytes of the current strip or tile.
         */
        private byte[] chunk = new byte[0];

        /**
         * A scratch row for the floating-point predictor.
         */
        private byte[] scratch = new byte[0];

        /**
         * The 8-bit work samples of one row.
         */
        private byte[] row8 = new byte[0];

        /**
         * The 16-bit work samples of one row.
         */
        private short[] row16 = new short[0];

        /**
         * The Deflate decompressor, created on first use.
         */
        private ZlibInflater inflater;

        /**
         * The LZW decoder, created on first use.
         */
        private LzwDecoder lzw;

        /**
         * The fax decoder, created on first use.
         */
        private CcittFaxDecoder fax;

        /**
         * The samples of one row decoded by a WebP, JPEG XL or JPEG 2000 decoder.
         */
        private int[] values = new int[0];

        /**
         * Releases the Deflate decompressor.
         */
        @Override
        public void close() {
        }
    }

    /**
     * Returns the size of the decompressed data of a full strip or tile.
     *
     * @return the size in bytes
     */
    private long chunkBytes() {
        return chunkRowBytes() * ((chunkHeight + (long) subsampleY - 1) / subsampleY);
    }

    /**
     * Returns the size of one decompressed row of a strip or tile; for subsampled YCbCr, one row
     * of data units.
     *
     * @return the size in bytes
     */
    private long chunkRowBytes() {
        if (subsampleX != 1 || subsampleY != 1) {
            var units = (chunkWidth + (long) subsampleX - 1) / subsampleX;
            return units * (subsampleX * subsampleY + 2);
        }
        var perRow = (long) chunkWidth * (planar ? 1 : samples);
        return (perRow * bits + 7) / 8;
    }

    /**
     * Decodes one strip or tile into the target.
     *
     * @param index the strip or tile index, plane-major
     * @param context the band's buffers
     * @param target the destination
     */
    private void decodeChunk(int index, Context context, Target target) {
        var perPlane = across * down;
        var plane = index / perPlane;
        var within = index % perPlane;
        var x0 = (within % across) * chunkWidth;
        var y0 = (within / across) * chunkHeight;
        var rows = tiled ? chunkHeight : Math.min(chunkHeight, height - y0);
        var rowBytes = (int) chunkRowBytes();
        var unitRows = (rows + subsampleY - 1) / subsampleY;
        var expected = rowBytes * unitRows;
        if (context.chunk.length < expected) {
            context.chunk = new byte[expected];
        }
        var chunk = context.chunk;
        if (index >= offsets.length || offsets[index] == 0) {
            target.incomplete = true;
            return;
        }
        var offset = offsets[index];
        var count = byteCount(index, expected);
        if (count <= 0) {
            target.incomplete = true;
            return;
        }
        var produced = 0;
        if (compression == NONE && !reverseBits) {
            produced = file.readAvailable(offset, chunk, 0, (int) Math.min(count, expected));
        } else {
            LimitChecks.checkAllocation(limits, count);
            if (context.raw.length < count) {
                context.raw = new byte[(int) count];
            }
            var raw = context.raw;
            var length = file.readAvailable(offset, raw, 0, (int) count);
            if (length == 0) {
                target.incomplete = true;
                return;
            }
            if (reverseBits) {
                for (var i = 0; i < length; i++) {
                    raw[i] = (byte) (Integer.reverse(raw[i]) >>> 24);
                }
            }
            if (compression == JPEG || compression == OLD_JPEG) {
                decodeJpegChunk(raw, length, chunk, rowBytes, rows, plane, context, target, x0, y0);
                return;
            }
            Arrays.fill(chunk, 0, expected, (byte) 0);
            produced = decompress(raw, length, chunk, expected, rowBytes, rows, context);
        }
        if (produced < expected) {
            Arrays.fill(chunk, Math.max(produced, 0), expected, (byte) 0);
        }
        if (predictor != 1) {
            var stride = planar ? 1 : samples;
            var bytesPerSample = bits / 8;
            if (predictor == 2) {
                Predictor.undoHorizontal(chunk, 0, rowBytes, rows, stride, chunkWidth, bytesPerSample, file.little());
            } else {
                var needed = chunkWidth * stride * bytesPerSample;
                if (context.scratch.length < needed) {
                    context.scratch = new byte[needed];
                }
                Predictor.undoFloatingPoint(chunk, 0, rowBytes, rows, stride, chunkWidth * stride, bytesPerSample,
                        context.scratch);
            }
        }
        convertChunk(chunk, rowBytes, rows, plane, x0, y0, context, target);
    }

    /**
     * Returns the number of compressed bytes of a strip or tile.
     *
     * @param index the strip or tile index
     * @param expected the decompressed size
     * @return the byte count, estimated when the counts are missing
     */
    private long byteCount(int index, long expected) {
        if (byteCounts != null && index < byteCounts.length) {
            return Math.min(byteCounts[index], file.size() < 0 ? Long.MAX_VALUE : file.size() - offsets[index]);
        }
        if (compression == NONE) {
            return expected;
        }
        var size = file.size();
        return size < 0 ? limits.maxBytes() : Math.max(0, size - offsets[index]);
    }

    /**
     * Decompresses one strip or tile.
     *
     * @param raw the compressed bytes
     * @param length the number of compressed bytes
     * @param chunk the destination, zero-filled
     * @param expected the decompressed size
     * @param rowBytes the size of one decompressed row
     * @param rows the number of rows
     * @param context the band's decompressors
     * @return the number of bytes produced
     * @throws MediaException.Malformed if the compressed data is invalid
     */
    private int decompress(byte[] raw, int length, byte[] chunk, int expected, int rowBytes, int rows, Context context) {
        return switch (compression) {
            case NONE -> {
                var copy = Math.min(length, expected);
                System.arraycopy(raw, 0, chunk, 0, copy);
                yield copy;
            }
            case PACKBITS -> PackBits.decode(raw, 0, length, chunk, 0, expected);
            case LZW -> {
                if (context.lzw == null) {
                    context.lzw = new LzwDecoder();
                }
                yield context.lzw.decode(raw, 0, length, chunk, 0, expected);
            }
            case ADOBE_DEFLATE, DEFLATE -> inflate(raw, length, chunk, expected, context);
            case CCITT_RLE, CCITT_T4, CCITT_T6, CCITT_RLEW -> {
                if (context.fax == null) {
                    context.fax = new CcittFaxDecoder();
                }
                context.fax.decode(raw, 0, length, chunk, 0, rowBytes, chunkWidth, rows, compression, faxOptions);
                yield expected;
            }
            case THUNDERSCAN -> ThunderScan.decode(raw, length, chunk, rowBytes, chunkWidth, rows);
            case SGILOG, SGILOG24 -> SgiLog.decode(raw, length, chunk, chunkWidth, rows,
                    photometric == LOG_L ? SgiLog.LOG_L : compression == SGILOG24 ? SgiLog.LOG_LUV_24 : SgiLog.LOG_LUV_32);
            case WEBP -> {
                codecs.webp(raw, length, chunk, rowBytes, chunkWidth, rows, samples);
                yield expected;
            }
            case JPEG_XL, JPEG_XL_DNG -> {
                context.values = codecs.jpegXl(raw, length, chunk, rowBytes, chunkWidth, rows, planar ? 1 : samples,
                        bits, format == 3, file.little(), context.values);
                yield expected;
            }
            case JPEG_2000, APERIO_YCBCR, APERIO_RGB -> {
                context.values = codecs.jpeg2000(raw, length, chunk, rowBytes, chunkWidth, rows, planar ? 1 : samples,
                        bits, file.little(), jpeg2000Ycbcr, context.values);
                yield expected;
            }
            default -> throw new IllegalStateException("unchecked compression " + compression);
        };
    }

    /**
     * Inflates one strip or tile.
     *
     * @param raw the compressed bytes
     * @param length the number of compressed bytes
     * @param chunk the destination
     * @param expected the decompressed size
     * @param context the band's decompressors
     * @return the number of bytes produced
     * @throws MediaException.Malformed if the zlib stream is invalid
     */
    private static int inflate(byte[] raw, int length, byte[] chunk, int expected, Context context) {
        if (context.inflater == null) {
            context.inflater = new ZlibInflater();
        }
        var inflater = context.inflater;
        inflater.reset();
        inflater.setInput(raw, 0, length);
        var produced = 0;
        try {
            while (produced < expected) {
                var count = inflater.inflate(chunk, produced, expected - produced);
                if (count == 0) {
                    if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                produced += count;
            }
        } catch (DataFormatException exception) {
            if (produced == 0) {
                throw new MediaException.Malformed("invalid Deflate data in a TIFF strip or tile", exception);
            }
        }
        return produced;
    }

    /**
     * Decodes one JPEG strip or tile and converts its samples.
     *
     * @param raw the compressed bytes
     * @param length the number of compressed bytes
     * @param chunk the buffer receiving the interleaved samples
     * @param rowBytes the size of one row of samples
     * @param rows the number of rows
     * @param plane the plane of the strip or tile
     * @param context the band's buffers
     * @param target the destination
     * @param x0 the left edge of the strip or tile
     * @param y0 the top edge of the strip or tile
     */
    private void decodeJpegChunk(byte[] raw, int length, byte[] chunk, int rowBytes, int rows, int plane, Context context,
                                 Target target, int x0, int y0) {
        byte[] stream;
        if (compression == OLD_JPEG) {
            stream = rebuildOldJpeg(raw, length, rows);
        } else {
            stream = TiffJpeg.join(jpegTables, raw, length);
        }
        var decoded = TiffJpeg.decode(stream, 0, stream.length, limits, jpegYcbcr, compression == OLD_JPEG ? ycbcr : null);
        var components = planar ? 1 : samples;
        if (decoded.components() != components) {
            throw new MediaException.Malformed("a TIFF JPEG strip or tile has " + decoded.components()
                    + " components, the image " + components);
        }
        if (decoded.bits() != bits) {
            throw new MediaException.Malformed("a TIFF JPEG strip or tile has " + decoded.bits()
                    + "-bit samples, the image " + bits);
        }
        var expected = rowBytes * rows;
        Arrays.fill(chunk, 0, expected, (byte) 0);
        var copyRows = Math.min(rows, decoded.height());
        var decodedRowBytes = decoded.rowBytes();
        var copyBytes = Math.min(rowBytes, decodedRowBytes);
        for (var row = 0; row < copyRows; row++) {
            System.arraycopy(decoded.samples(), row * decodedRowBytes, chunk, row * rowBytes, copyBytes);
        }
        convertChunk(chunk, rowBytes, rows, plane, x0, y0, context, target);
    }

    /**
     * Rebuilds the interchange stream of an old-style JPEG strip or tile from the table tags.
     *
     * @param raw the entropy-coded data
     * @param length the number of bytes
     * @param rows the number of rows of the strip or tile
     * @return the interchange stream
     * @throws MediaException.Malformed if a table tag is missing or truncated
     */
    private byte[] rebuildOldJpeg(byte[] raw, int length, int rows) {
        var components = planar ? 1 : samples;
        var quantization = tables(TiffTags.JPEG_Q_TABLES, components, 64);
        var dc = tables(TiffTags.JPEG_DC_TABLES, components, -1);
        var ac = tables(TiffTags.JPEG_AC_TABLES, components, -1);
        var factors = directory.longs(TiffTags.Y_CB_CR_SUBSAMPLING);
        var hs = photometric == YCBCR && factors != null && factors.length > 0 ? (int) factors[0] : (photometric == YCBCR ? 2 : 1);
        var vs = photometric == YCBCR && factors != null && factors.length > 1 ? (int) factors[1] : (photometric == YCBCR ? 2 : 1);
        if (components == 1 || hs < 1 || hs > 4 || vs < 1 || vs > 4) {
            hs = 1;
            vs = 1;
        }
        var restart = (int) directory.longValue(TiffTags.JPEG_RESTART_INTERVAL, 0);
        return TiffJpeg.rebuild(raw, length, chunkWidth, rows, components, quantization, dc, ac, hs, vs, restart);
    }

    /**
     * Reads the tables an old-style JPEG table tag points to.
     *
     * @param tag the tag listing one offset per component
     * @param components the number of components
     * @param size the size of a table, or {@code -1} for a Huffman table (16 counts then
     * the values)
     * @return the tables
     * @throws MediaException.Malformed if the tag is missing or a table is truncated
     */
    private byte[][] tables(int tag, int components, int size) {
        var pointers = directory.longs(tag);
        if (pointers == null || pointers.length == 0) {
            throw new MediaException.Unsupported("tiff old-style jpeg", "the old-style JPEG TIFF image lacks its tables");
        }
        var result = new byte[components][];
        for (var c = 0; c < components; c++) {
            var pointer = pointers[Math.min(c, pointers.length - 1)];
            if (size > 0) {
                result[c] = file.readBlock(pointer, size);
            } else {
                var counts = file.readBlock(pointer, 16);
                var values = 0;
                for (var b : counts) {
                    values += b & 0xFF;
                }
                result[c] = file.readBlock(pointer, 16L + values);
            }
        }
        return result;
    }

    /**
     * Decodes an old-style JPEG image from the complete stream its
     * {@code JPEGInterchangeFormat} tag points to.
     *
     * @param target the destination
     * @return whether the stream decoded; {@code false} when it is absent, incomplete or does not
     * match the image, so that the strips or tiles are decoded instead
     */
    private boolean decodeOldJpegStream(Target target) {
        var start = directory.longValue(TiffTags.JPEG_INTERCHANGE_FORMAT, -1);
        if (start <= 0) {
            return false;
        }
        var size = file.size();
        var length = directory.longValue(TiffTags.JPEG_INTERCHANGE_FORMAT_LENGTH, size < 0 ? limits.maxBytes() : size - start);
        TiffJpeg.Samples decoded;
        try {
            var stream = file.readBlock(start, length);
            decoded = TiffJpeg.decode(stream, 0, stream.length, limits, jpegYcbcr, ycbcr);
        } catch (MediaException.Malformed | MediaException.Unsupported exception) {
            return false;
        }
        if (decoded.components() != samples || decoded.bits() != 8) {
            return false;
        }
        var rowBytes = decoded.width() * samples;
        var rows = Math.min(height, decoded.height());
        var columns = Math.min(width, decoded.width());
        var context = new Context();
        for (var y = 0; y < rows; y++) {
            convertRow(decoded.samples(), y * rowBytes, columns, 0, y, context, target);
        }
        if (rows < height || columns < width) {
            target.incomplete = true;
        }
        return true;
    }

    /**
     * Converts the rows of a decompressed strip or tile into the target.
     *
     * @param chunk the decompressed rows
     * @param rowBytes the size of one row
     * @param rows the number of rows in the strip or tile
     * @param plane the plane of the strip or tile
     * @param x0 the left edge
     * @param y0 the top edge
     * @param context the band's buffers
     * @param target the destination
     */
    private void convertChunk(byte[] chunk, int rowBytes, int rows, int plane, int x0, int y0, Context context,
                              Target target) {
        var visibleRows = Math.min(rows, height - y0);
        var columns = Math.min(chunkWidth, width - x0);
        if (visibleRows <= 0 || columns <= 0) {
            return;
        }
        if (subsampleX != 1 || subsampleY != 1) {
            convertSubsampled(chunk, rowBytes, visibleRows, x0, y0, target);
            return;
        }
        if (planar) {
            for (var row = 0; row < visibleRows; row++) {
                var workOffset = ((y0 + row) * width + x0) * samples + plane;
                if (target.workF != null) {
                    converter.unpackFloat(chunk, row * rowBytes, columns, target.workF, workOffset, samples,
                            predictor == 3);
                } else if (target.work16 != null) {
                    converter.unpack16(chunk, row * rowBytes, columns, target.work16, workOffset, samples, predictor == 3);
                } else {
                    converter.unpack8(chunk, row * rowBytes, columns, target.work8, workOffset, samples);
                }
            }
            return;
        }
        for (var row = 0; row < visibleRows; row++) {
            convertRow(chunk, row * rowBytes, columns, x0, y0 + row, context, target);
        }
    }

    /**
     * Converts one row of interleaved samples into the target.
     *
     * @param data the stored samples
     * @param offset the index of the row's first byte
     * @param columns the number of pixels
     * @param x the column of the first pixel
     * @param y the row
     * @param context the band's buffers
     * @param target the destination
     */
    private void convertRow(byte[] data, int offset, int columns, int x, int y, Context context, Target target) {
        var count = columns * samples;
        if (target.channels) {
            var at = (y * width + x) * samples;
            if (target.workF != null) {
                converter.unpackFloat(data, offset, count, target.workF, at, 1, predictor == 3);
            } else if (target.work16 != null) {
                converter.unpack16(data, offset, count, target.work16, at, 1, predictor == 3);
            } else {
                converter.unpack8(data, offset, count, target.work8, at, 1);
            }
            return;
        }
        var outOffset = (y * width + x) * 4;
        if (converter.wide()) {
            if (context.row16.length < count) {
                context.row16 = new short[count];
            }
            converter.unpack16(data, offset, count, context.row16, 0, 1, predictor == 3);
            converter.toRgba16(context.row16, 0, columns, target.out8, target.out16, outOffset);
        } else if (converter.direct()) {
            converter.toRgba8(data, offset, columns, target.out8, outOffset);
        } else {
            if (context.row8.length < count) {
                context.row8 = new byte[count];
            }
            converter.unpack8(data, offset, count, context.row8, 0, 1);
            converter.toRgba8(context.row8, 0, columns, target.out8, outOffset);
        }
    }

    /**
     * Converts the gathered planes of a planar image into the target.
     *
     * @param target the destination
     */
    private void convertPlanar(Target target) {
        var bands = ParallelBands.bands(height, (long) width * height * samples);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, height);
            var last = ParallelBands.start(band + 1, bands, height);
            for (var y = first; y < last; y++) {
                var workOffset = y * width * samples;
                if (target.work16 != null) {
                    converter.toRgba16(target.work16, workOffset, width, target.out8, target.out16, y * width * 4);
                } else {
                    converter.toRgba8(target.work8, workOffset, width, target.out8, y * width * 4);
                }
            }
        });
    }

    /**
     * Converts the data units of a subsampled YCbCr strip or tile into the target.
     *
     * @param chunk the decompressed data units
     * @param rowBytes the size of one row of data units
     * @param rows the number of visible pixel rows
     * @param x0 the left edge
     * @param y0 the top edge
     * @param target the destination
     */
    private void convertSubsampled(byte[] chunk, int rowBytes, int rows, int x0, int y0, Target target) {
        var unitSize = subsampleX * subsampleY + 2;
        var units = (chunkWidth + subsampleX - 1) / subsampleX;
        var out8 = target.out8;
        for (var unitRow = 0; unitRow * subsampleY < rows; unitRow++) {
            for (var unit = 0; unit < units; unit++) {
                var at = unitRow * rowBytes + unit * unitSize;
                var cb = chunk[at + subsampleX * subsampleY] & 0xFF;
                var cr = chunk[at + subsampleX * subsampleY + 1] & 0xFF;
                for (var dy = 0; dy < subsampleY; dy++) {
                    var y = unitRow * subsampleY + dy;
                    if (y >= rows) {
                        break;
                    }
                    for (var dx = 0; dx < subsampleX; dx++) {
                        var x = x0 + unit * subsampleX + dx;
                        if (x >= width || unit * subsampleX + dx >= chunkWidth) {
                            break;
                        }
                        var rgb = ycbcr.toRgb(chunk[at + dy * subsampleX + dx] & 0xFF, cb, cr);
                        var o = ((y0 + y) * width + x) * 4;
                        out8[o] = (byte) (rgb >>> 16);
                        out8[o + 1] = (byte) (rgb >>> 8);
                        out8[o + 2] = (byte) rgb;
                        out8[o + 3] = (byte) 0xFF;
                    }
                }
            }
        }
    }

    /**
     * Rejects compression schemes this decoder lacks.
     *
     * @throws MediaException.Unsupported if the compression is not supported
     */
    private void checkCompression() {
        switch (compression) {
            case NONE, LZW, PACKBITS, ADOBE_DEFLATE, DEFLATE, JPEG, OLD_JPEG -> {
            }
            case CCITT_RLE, CCITT_T4, CCITT_T6, CCITT_RLEW -> {
                if (bits != 1 || samples != 1) {
                    throw new MediaException.Malformed("CCITT-compressed TIFF images must be bilevel");
                }
            }
            case THUNDERSCAN -> {
                if (bits != 4 || samples != 1) {
                    throw new MediaException.Malformed("ThunderScan-compressed TIFF images must be 4-bit gray");
                }
            }
            case SGILOG, SGILOG24 -> {
                if (photometric != LOG_LUV && (photometric != LOG_L || compression == SGILOG24)) {
                    throw new MediaException.Malformed("SGILog-compressed TIFF images must be LogL or LogLuv");
                }
            }
            case WEBP -> {
                if (bits != 8 || format != 1 || planar || (samples != 3 && samples != 4)
                        || (photometric != RGB && photometric != YCBCR)) {
                    throw new MediaException.Unsupported("tiff webp layout",
                            "WebP-compressed TIFF images must be interleaved 8-bit RGB or RGBA");
                }
            }
            case JPEG_XL, JPEG_XL_DNG -> {
                if (format == 2 || (format == 3 ? bits != 16 && bits != 32 : bits > 16)) {
                    throw new MediaException.Unsupported("tiff jpeg xl " + bits + "-bit",
                            "JPEG XL-compressed TIFF images need unsigned samples of up to 16 bits or 16- or 32-bit"
                                    + " floating-point samples");
                }
            }
            case JPEG_2000, APERIO_YCBCR, APERIO_RGB -> {
                if (format == 3) {
                    throw new MediaException.Unsupported("tiff jpeg 2000 floating-point",
                            "JPEG 2000-compressed TIFF images with floating-point samples are not supported");
                }
                if ((compression == APERIO_YCBCR || (compression == JPEG_2000 && photometric == YCBCR))
                        && (bits != 8 || format != 1 || planar || samples < 3)) {
                    throw new MediaException.Unsupported("tiff jpeg 2000 ycbcr",
                            "JPEG 2000 YCbCr TIFF images must be interleaved with 8-bit unsigned samples");
                }
            }
            default -> throw new MediaException.Unsupported("tiff " + compressionName(compression) + " compression",
                    "TIFF compression " + compressionName(compression) + " (" + compression + ") is not supported");
        }
    }

    /**
     * Returns whether a compression stores complete images of another format, which a codec
     * decodes to samples.
     *
     * @param code the compression code
     * @return {@code true} for WebP, JPEG XL and JPEG 2000
     */
    private static boolean codec(int code) {
        return code == WEBP || code == JPEG_XL || code == JPEG_XL_DNG || jpeg2000(code);
    }

    /**
     * Returns whether a compression is JPEG 2000.
     *
     * @param code the compression code
     * @return {@code true} for the JPEG 2000 code and the two Aperio codes
     */
    private static boolean jpeg2000(int code) {
        return code == JPEG_2000 || code == APERIO_YCBCR || code == APERIO_RGB;
    }

    /**
     * Names a compression scheme.
     *
     * @param code the compression code
     * @return the lowercase name
     */
    static String compressionName(int code) {
        return switch (code) {
            case 32766 -> "next";
            case 32808 -> "thunderscan";
            case 32895, 32896, 32897, 32898 -> "it8";
            case 32908 -> "pixar";
            case 34661 -> "jbig";
            case 34676, 34677 -> "sgilog";
            case 33003, 33005, 34712 -> "jpeg 2000";
            case 34887, 34888, 34889 -> "lerc";
            case 34925 -> "lzma";
            case 50000 -> "zstd";
            case 50001 -> "webp";
            case 50002, 52546 -> "jpeg xl";
            default -> String.valueOf(code);
        };
    }

    /**
     * Rejects sample sizes this decoder lacks.
     *
     * @throws MediaException.Unsupported if the bits per sample do not suit the sample format
     */
    private void checkBits() {
        var supported = format == 3 ? bits == 16 || bits == 32 || bits == 64 : (bits >= 1 && bits <= 32);
        if (!supported) {
            throw new MediaException.Unsupported("tiff " + bits + "-bit " + (format == 3 ? "floating-point " : "") + "samples",
                    "TIFF images with " + bits + "-bit " + (format == 3 ? "floating-point " : "") + "samples are not supported");
        }
    }

    /**
     * Requires every value of a per-sample tag to be equal.
     *
     * @param values the values, or {@code null} when the tag is absent
     * @param fallback the value when the tag is absent
     * @param name the tag name
     * @return the common value
     * @throws MediaException.Unsupported if the values differ
     */
    private static int uniform(long[] values, int fallback, String name) {
        if (values == null || values.length == 0) {
            return fallback;
        }
        for (var value : values) {
            if (value != values[0]) {
                throw new MediaException.Unsupported("tiff mixed " + name, "TIFF images whose " + name + " differs between samples are not supported");
            }
        }
        if (values[0] < 0 || values[0] > 0xFFFF) {
            throw new MediaException.Malformed("invalid TIFF " + name + " " + values[0]);
        }
        return (int) values[0];
    }

    /**
     * Requires a dimension to be positive and representable.
     *
     * @param value the stored dimension
     * @param name the tag name
     * @return the value
     * @throws MediaException.Malformed if the value is not positive
     */
    private static int positive(long value, String name) {
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("invalid TIFF " + name + " " + value);
        }
        return (int) value;
    }

    /**
     * Converts reals to single precision, as libtiff stores them.
     *
     * @param values the values, or {@code null}
     * @param fallback the values used when {@code values} is absent or too short
     * @return the single-precision values
     */
    private static float[] floats(double[] values, float[] fallback) {
        if (values == null || values.length < fallback.length) {
            return fallback;
        }
        var result = new float[fallback.length];
        for (var i = 0; i < result.length; i++) {
            result[i] = (float) values[i];
        }
        return result;
    }
}
