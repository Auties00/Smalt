package com.github.auties00.smalt.image.bmp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.bmp.internal.BmpHeader;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.png.PngDecoder;
import com.github.auties00.smalt.image.tiff.internal.CcittFaxDecoder;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SlicedSource;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decodes Windows and OS/2 bitmaps (BMP) to packed RGBA.
 *
 * <p>Supported: the OS/2 1.x core header, OS/2 2.x headers, and the {@code BITMAPINFOHEADER} family
 * up to V5; bottom-up and top-down (negative height) row order; 1-, 2-, 4- and 8-bit palettes;
 * 16-bit pixels (5-5-5 by default, any contiguous masks with {@code BI_BITFIELDS}); 24-bit pixels;
 * 32-bit pixels ({@code BI_RGB} or masks, including {@code BI_ALPHABITFIELDS}); 64-bit pixels of
 * linear-light s2.13 fixed-point blue, green, red and straight alpha; {@code BI_RLE8} and
 * {@code BI_RLE4} with end-of-line, end-of-bitmap, delta and absolute escapes; the OS/2 Huffman 1D
 * (ITU-T T.4 modified Huffman codes, white runs being index 0) and RLE24 compressions; embedded JPEG
 * ({@code BI_JPEG}) and PNG ({@code BI_PNG}) files; CMYK pixels and color tables ({@code BI_CMYK},
 * {@code BI_CMYKRLE8}, {@code BI_CMYKRLE4}); an ICC profile embedded by a V5 header and the calibrated
 * color space of a V4 or V5 header; and OS/2 bitmap arrays ({@code BA} files). An unknown
 * compression raises {@link MediaException.Unsupported} naming it.
 *
 * <p>Channel values are scaled to 8 bits: a mask of {@code n} bits maps value {@code v} to
 * {@code round(v * 255 / (2^n - 1))}. With {@link ImageDecoderOptions#highBitDepth()}, a file whose
 * masks are wider than 8 bits, and a 64-bit file, decode to an {@link Rgba16Picture} instead, a mask
 * of {@code n} bits mapping {@code v} to {@code round(v * 65535 / (2^n - 1))}. 64-bit color values are
 * clamped to 0..1 and encoded with the sRGB transfer function, alpha is clamped and scaled. The alpha
 * channel of 32-bit {@code BI_RGB} pixels, any alpha mask and 64-bit alpha are used only when some
 * pixel has a non-zero alpha; when all are zero the image is opaque (many writers leave the byte
 * zero). A palette's fourth byte is ignored. An index past the palette paints opaque black.
 *
 * <p>CMYK values are stored black, yellow, magenta, cyan (the byte order of the Windows
 * {@code CMYK} color macro), 0 being no ink. They are converted to RGBA as
 * {@link ImageOps#toRgba(Picture)} converts a CMYK picture, without
 * color management. With {@link ImageDecoderOptions#keepChannels()} set (and
 * {@link ImageDecoderOptions#convertToSrgb()} not set) they decode to a {@link Channel8Picture} of
 * the {@link PixelLayout#CMYK} layout, carrying an embedded CMYK profile as its
 * {@link DecodedImage#colorProfile()}; with {@code convertToSrgb} set, an image with an embedded CMYK
 * profile is converted through it.
 *
 * <p>The color space of the pixels is reported in {@link DecodedImage#colorProfile()}: an embedded
 * RGB ICC profile (every embedded profile is returned by {@link DecodedImage#iccProfile()}), or the profile a
 * calibrated color space ({@code LCS_CALIBRATED_RGB}) describes, built with
 * {@link ColorProfile#rgb(Chromaticity, Chromaticity, Chromaticity, Chromaticity, double, double, double)}
 * from its endpoints and gammas. Endpoints whose X, Y and Z each sum to 1 are taken as chromaticity
 * coordinates with a D65 white; others as tristimulus values, the white being their sum. A calibrated
 * space whose endpoints or gammas are zero, negative or degenerate is ignored, and so are linked
 * profiles, whose file names are never opened. The sRGB and system color spaces leave the color
 * profile empty. The embedded JPEG or PNG file's own color data takes precedence over the header's.
 * An embedded file decodes as {@link JpegDecoder} and {@link PngDecoder} decode it, except that a
 * Y'CbCr picture is converted to RGBA ({@link ImageOps#toRgba(Picture)}, or
 * {@link ImageOps#toRgba16(Picture)} for one deeper than 8 bits).
 *
 * <p>{@link DecodedImage#density()} holds the header's horizontal and vertical pixels per meter, in
 * {@link PixelDensity.Unit#DOTS_PER_METER}, when both are positive; otherwise
 * it is empty, or for an embedded JPEG or PNG file the density that file declares.
 *
 * <p>An OS/2 bitmap array decodes to its bitmap of the largest area (the first among equal
 * areas), or, with a size hint, the smallest bitmap at least {@link ImageDecoderOptions#minWidth()} by
 * {@link ImageDecoderOptions#minHeight()}; {@link #openPages(MediaSource, ImageDecoderOptions)} opens
 * every bitmap. Its icon and pointer entries are skipped; an array without any bitmap raises
 * {@link MediaException.Unsupported}.
 *
 * <p>Damaged files decode as far as they can: the file size and image size fields are ignored
 * (except that the image size bounds embedded JPEG and PNG data); a zero pixel data offset means the
 * data follows the color table; a color table overlapping the pixel data is shortened; the last row
 * may lack its padding; pixel data that ends early leaves the missing pixels transparent; run-length
 * pixels that are skipped (by a delta, an early end of line or an early end of bitmap) are
 * transparent (no ink for a CMYK color table), and runs past the right edge are clipped; Huffman data
 * ending early leaves the remaining rows transparent. A file whose pixel data is entirely missing
 * raises {@link MediaException.Malformed}.
 *
 * <p>This class is thread-safe.
 */
public final class BmpDecoder {
    /**
     * The largest embedded ICC profile read, in bytes.
     */
    private static final int MAX_ICC_PROFILE = 32 << 20;

    /**
     * The description used by {@link Guards}.
     */
    private static final String WHAT = "BMP file";

    /**
     * The D65 white point, assumed by a calibrated color space given in chromaticity coordinates.
     */
    private static final Chromaticity D65 = new Chromaticity(0.3127, 0.3290);

    /**
     * Prevents instantiation.
     */
    private BmpDecoder() {
        throw new AssertionError();
    }

    /**
     * Reads the headers of a BMP file without decoding pixels.
     *
     * <p>{@link ImageInfo#hasAlpha()} is {@code true} for 32-bit {@code BI_RGB} pixels, an active
     * alpha mask, 64-bit pixels and run-length encoding: the pixels may carry transparency, which only
     * decoding confirms. Embedded JPEG and PNG files are described by their own headers. The image
     * described is the one {@link #decode(MediaSource)} decodes, and {@link ImageInfo#frameCount()} is
     * the number of bitmaps of an OS/2 bitmap array (one otherwise). The size is reported without
     * checking it against any limit.
     *
     * @param source the input, read from offset zero
     * @return the image summary
     * @throws MediaException.Malformed if the headers are truncated or invalid
     * @throws MediaException.Unsupported if the file uses an unknown compression, or is a bitmap array
     * without bitmaps
     * @throws MediaException.LimitExceeded if a bitmap array holds more bitmaps than the default frame
     * limit
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        return Guards.malformed(WHAT, () -> {
            var entries = entries(source, MediaLimits.defaults());
            var entry = entries.get(choose(entries, 0, 0));
            return info(source, BmpHeader.read(source, entry.start()), entries.size());
        });
    }

    /**
     * Decodes a BMP image at full size under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the decoded image
     * @throws MediaException.Malformed if the headers are invalid or the pixel data is missing
     * @throws MediaException.Unsupported if the file uses an unknown compression, or is a bitmap array
     * without bitmaps
     * @throws MediaException.LimitExceeded if the image exceeds the default limits or decoding runs
     * out of memory
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes a BMP file.
     *
     * <p>The picture's {@code hasAlpha()} is {@code true} when an alpha channel is in use (some alpha
     * non-zero), when run-length or Huffman decoding left pixels unset, or when the pixel data ended
     * early. The size hint of {@code options} chooses the bitmap of an OS/2 bitmap array and reaches
     * the decoder of an embedded JPEG file, which may then decode at a reduced size; it is otherwise
     * ignored.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the bitmap choice, the bit depth, the channel and the sRGB conversion
     * choices
     * @return the decoded image
     * @throws MediaException.Malformed if the headers are invalid or the pixel data is missing
     * @throws MediaException.Unsupported if the file uses an unknown compression, or is a bitmap array
     * without bitmaps
     * @throws MediaException.LimitExceeded if the image exceeds the limits of {@code options} or
     * decoding runs out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return Guards.malformed(WHAT, () -> {
            var entries = entries(source, options.limits());
            var entry = entries.get(choose(entries, options.minWidth(), options.minHeight()));
            return decodeBitmap(source, BmpHeader.read(source, entry.start()), options);
        });
    }

    /**
     * Opens the bitmaps of a BMP file as pages: one page for a plain file, one per bitmap, in array
     * order, for an OS/2 bitmap array.
     *
     * <p>Each page decodes as {@link #decode(MediaSource, ImageDecoderOptions)} decodes a plain file;
     * the size hint of {@code options} is ignored. Page summaries are those of
     * {@link #probe(MediaSource)}.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the bit depth, the channel and the sRGB conversion choices applied to
     * every page
     * @return the page reader
     * @throws MediaException.Malformed if the file or array headers are invalid
     * @throws MediaException.Unsupported if the file is a bitmap array without bitmaps
     * @throws MediaException.LimitExceeded if the bitmap count exceeds the frame limit
     * @throws MediaException.Io if reading the source fails
     */
    public static PageReader openPages(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var entries = Guards.malformed(WHAT, () -> entries(source, options.limits()));
        var full = options.toBuilder().fullSize().build();
        return new PageReader() {
            @Override
            public int pageCount() {
                return entries.size();
            }

            @Override
            public ImageInfo probe(int index) {
                var entry = entries.get(Objects.checkIndex(index, entries.size()));
                return Guards.malformed(WHAT, () -> info(source, BmpHeader.read(source, entry.start()), entries.size()));
            }

            @Override
            public DecodedImage read(int index) {
                var entry = entries.get(Objects.checkIndex(index, entries.size()));
                return Guards.malformed(WHAT, () -> decodeBitmap(source, BmpHeader.read(source, entry.start()), full));
            }
        };
    }

    /**
     * Opens the bitmaps of a BMP file as pages under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the page reader
     * @throws MediaException.Malformed if the file or array headers are invalid
     * @throws MediaException.Unsupported if the file is a bitmap array without bitmaps
     * @throws MediaException.LimitExceeded if the bitmap count exceeds the default frame limit
     * @throws MediaException.Io if reading the source fails
     * @see #openPages(MediaSource, ImageDecoderOptions)
     */
    public static PageReader openPages(MediaSource source) {
        return openPages(source, ImageDecoderOptions.defaults());
    }

    /**
     * Locates one bitmap of a file.
     *
     * @param start the file offset of its {@code BM} file header
     * @param width its width in pixels, at least zero
     * @param height its height in pixels, at least zero
     */
    private record Entry(long start, long width, long height) {
    }

    /**
     * Lists the bitmaps of a file: the file itself, or the bitmap entries of an OS/2 bitmap array.
     *
     * @param source the input
     * @param limits the limits bounding the entry count
     * @return the bitmaps, at least one
     * @throws MediaException.Malformed if the file is too short or the array chain is invalid
     * @throws MediaException.Unsupported if the array holds no bitmap
     * @throws MediaException.LimitExceeded if the array holds more bitmaps than the frame limit
     */
    private static List<Entry> entries(MediaSource source, MediaLimits limits) {
        var head = new byte[2];
        if (SourceBytes.readAvailable(source, 0, head, 0, 2) < 2) {
            throw new MediaException.Malformed("input is too short to be a BMP file");
        }
        if (head[0] != 'B' || head[1] != 'A') {
            return List.of(entry(source, 0));
        }
        var entries = new ArrayList<Entry>();
        var skipped = 0;
        var position = 0L;
        var header = new byte[BmpHeader.ARRAY_HEADER + 2];
        while (true) {
            if (SourceBytes.readAvailable(source, position, header, 0, header.length) < header.length
                    || header[0] != 'B' || header[1] != 'A') {
                throw new MediaException.Malformed("invalid OS/2 bitmap array header at offset " + position);
            }
            if (header[BmpHeader.ARRAY_HEADER] == 'B' && header[BmpHeader.ARRAY_HEADER + 1] == 'M') {
                if (entries.size() >= limits.maxFrames()) {
                    throw new MediaException.LimitExceeded("the OS/2 bitmap array holds more than " + limits.maxFrames()
                            + " bitmaps");
                }
                entries.add(entry(source, position + BmpHeader.ARRAY_HEADER));
            } else {
                skipped++;
            }
            var next = ByteArrays.i32le(header, 6) & 0xFFFF_FFFFL;
            if (next == 0) {
                break;
            }
            if (next <= position) {
                throw new MediaException.Malformed("the OS/2 bitmap array chain goes back to offset " + next);
            }
            position = next;
        }
        if (entries.isEmpty()) {
            throw new MediaException.Unsupported("bmp os/2 icon", "the OS/2 bitmap array holds " + skipped
                    + " icon or pointer entries and no bitmap");
        }
        return List.copyOf(entries);
    }

    /**
     * Reads the size of one bitmap.
     *
     * @param source the input
     * @param start the file offset of its {@code BM} file header
     * @return the entry
     * @throws MediaException.Malformed if the headers are truncated
     */
    private static Entry entry(MediaSource source, long start) {
        var fixed = new byte[BmpHeader.FILE_HEADER + 12];
        if (SourceBytes.readAvailable(source, start, fixed, 0, fixed.length) < fixed.length) {
            throw new MediaException.Malformed("input is too short to be a BMP file");
        }
        var core = ByteArrays.i32le(fixed, BmpHeader.FILE_HEADER) == 12;
        var width = core ? ByteArrays.u16le(fixed, 18) : Math.max(0, ByteArrays.i32le(fixed, 18));
        var height = core ? ByteArrays.u16le(fixed, 20) : Math.abs((long) ByteArrays.i32le(fixed, 22));
        return new Entry(start, width, height);
    }

    /**
     * Picks the bitmap to decode.
     *
     * @param entries the bitmaps
     * @param minWidth the hinted width, 0 for none
     * @param minHeight the hinted height, 0 for none
     * @return the index of the smallest bitmap covering the hint, or of the largest when none covers
     * it or there is no hint; the first among equal areas
     */
    private static int choose(List<Entry> entries, int minWidth, int minHeight) {
        var best = -1;
        if (minWidth > 0 || minHeight > 0) {
            for (var i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                if (entry.width() >= minWidth && entry.height() >= minHeight
                        && (best < 0 || area(entry) < area(entries.get(best)))) {
                    best = i;
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        for (var i = 0; i < entries.size(); i++) {
            if (best < 0 || area(entries.get(i)) > area(entries.get(best))) {
                best = i;
            }
        }
        return best;
    }

    /**
     * {@return the number of pixels of a bitmap}
     *
     * @param entry the bitmap
     */
    private static long area(Entry entry) {
        return entry.width() * entry.height();
    }

    /**
     * Summarizes one bitmap.
     *
     * @param source the input
     * @param header the bitmap's headers
     * @param count the number of bitmaps of the file
     * @return the summary
     */
    private static ImageInfo info(MediaSource source, BmpHeader header, int count) {
        if (header.compression() == BmpHeader.JPEG || header.compression() == BmpHeader.PNG) {
            var payload = payload(source, header);
            var embedded = header.compression() == BmpHeader.JPEG ? JpegDecoder.probe(payload) : PngDecoder.probe(payload);
            return new ImageInfo(ImageFormat.BMP, embedded.width(), embedded.height(), embedded.hasAlpha(), false,
                    count, Orientation.NORMAL);
        }
        return new ImageInfo(ImageFormat.BMP, header.width(), header.height(), header.declaresAlpha(), false, count,
                Orientation.NORMAL);
    }

    /**
     * Decodes one bitmap with the pixel density of its headers.
     *
     * @param source the input
     * @param header the bitmap's headers
     * @param options the decoding options
     * @return the decoded image
     */
    private static DecodedImage decodeBitmap(MediaSource source, BmpHeader header, ImageDecoderOptions options) {
        var image = decodePixels(source, header, options);
        var x = header.horizontalPixelsPerMeter();
        var y = header.verticalPixelsPerMeter();
        return x > 0 && y > 0 ? image.withDensity(Optional.of(PixelDensity.ofDotsPerMeter(x, y))) : image;
    }

    /**
     * Decodes the pixels of one bitmap.
     *
     * @param source the input
     * @param header the bitmap's headers
     * @param options the decoding options
     * @return the decoded image, without the density of the headers
     */
    private static DecodedImage decodePixels(MediaSource source, BmpHeader header, ImageDecoderOptions options) {
        var limits = options.limits();
        if (header.compression() == BmpHeader.JPEG || header.compression() == BmpHeader.PNG) {
            return decodeEmbedded(source, header, options);
        }
        var width = header.width();
        var height = header.height();
        LimitChecks.checkPicture(limits, width, height);
        var icc = readProfile(source, header, limits);
        var wide = options.highBitDepth() && header.widestChannel() > 8 && !header.isCmyk();
        if (wide) {
            LimitChecks.checkAllocation(limits, (long) width * height * 8);
            var pixels = new short[width * height * 4];
            var alpha = decodeWideRows(source, header, pixels, limits);
            var picture = Rgba16Picture.of(width, height, width * 4, pixels, alpha);
            return finish(picture, header, icc, options);
        }
        LimitChecks.checkAllocation(limits, (long) width * height * 4);
        var pixels = new byte[width * height * 4];
        var alpha = switch (header.compression()) {
            case BmpHeader.RLE8, BmpHeader.RLE4, BmpHeader.RLE24, BmpHeader.CMYK_RLE8, BmpHeader.CMYK_RLE4 ->
                    decodeRunLength(source, header, pixels, limits);
            case BmpHeader.HUFFMAN -> decodeHuffman(source, header, pixels, limits);
            default -> decodeRows(source, header, pixels, limits);
        };
        if (header.isCmyk()) {
            return finishCmyk(Channel8Picture.of(width, height, width * 4, pixels, PixelLayout.CMYK), header, icc,
                    options);
        }
        return finish(RgbaPicture.of(width, height, width * 4, pixels, alpha), header, icc, options);
    }

    /**
     * Wraps an RGBA picture with the color space of its headers and applies the sRGB conversion; an embedded
     * profile that is not an RGB profile, or that does not parse, is returned as bytes but declares no color space.
     *
     * @param picture the decoded pixels
     * @param header the headers
     * @param icc the embedded ICC profile, or {@code null}
     * @param options the decoding options
     * @return the decoded image
     */
    private static DecodedImage finish(Picture picture, BmpHeader header, byte[] icc,
                                       ImageDecoderOptions options) {
        var width = header.width();
        var height = header.height();
        var profile = icc != null ? parse(icc) : calibrated(header);
        if (profile != null && profile.colorSpace() != ProfileColorSpace.RGB) {
            profile = null;
        }
        var image = new DecodedImage(picture, ImageFormat.BMP, Orientation.NORMAL, width, height,
                Optional.ofNullable(icc), Optional.empty(), Optional.ofNullable(profile));
        return options.convertToSrgb() ? ImageOps.toSrgb(image) : image;
    }

    /**
     * Wraps a CMYK picture, keeping it, converting it through its CMYK profile, or converting it
     * naively, as the options ask.
     *
     * @param picture the decoded CMYK pixels
     * @param header the headers
     * @param icc the embedded ICC profile, or {@code null}
     * @param options the decoding options
     * @return the decoded image
     */
    private static DecodedImage finishCmyk(Channel8Picture picture, BmpHeader header, byte[] icc,
                                           ImageDecoderOptions options) {
        var width = header.width();
        var height = header.height();
        var parsed = icc == null ? null : parse(icc);
        var cmykProfile = parsed != null && parsed.colorSpace() == ProfileColorSpace.CMYK ? parsed : null;
        var iccBytes = Optional.ofNullable(icc);
        if (cmykProfile != null && (options.convertToSrgb() || options.keepChannels())) {
            var image = new DecodedImage(picture, ImageFormat.BMP, Orientation.NORMAL, width, height, iccBytes,
                    Optional.empty(), Optional.of(cmykProfile));
            return options.convertToSrgb() ? ImageOps.toSrgb(image) : image;
        }
        if (options.keepChannels() && !options.convertToSrgb()) {
            return new DecodedImage(picture, ImageFormat.BMP, Orientation.NORMAL, width, height, iccBytes,
                    Optional.empty(), Optional.empty());
        }
        return new DecodedImage(ImageOps.toRgba(picture), ImageFormat.BMP, Orientation.NORMAL, width, height, iccBytes,
                Optional.empty(), Optional.empty());
    }

    /**
     * Parses an ICC profile.
     *
     * @param icc the profile bytes
     * @return the profile, or {@code null} when it does not parse
     */
    private static ColorProfile parse(byte[] icc) {
        try {
            return ColorProfile.parse(icc);
        } catch (MediaException exception) {
            return null;
        }
    }

    /**
     * Decodes the JPEG or PNG file a bitmap embeds.
     *
     * @param source the input
     * @param header the headers
     * @param options the decoding options
     * @return the decoded image, reported as BMP
     */
    private static DecodedImage decodeEmbedded(MediaSource source, BmpHeader header, ImageDecoderOptions options) {
        var payload = payload(source, header);
        var limits = options.limits();
        var icc = readProfile(source, header, limits);
        var declared = icc != null ? parse(icc) : calibrated(header);
        if (declared != null && declared.colorSpace() != ProfileColorSpace.RGB) {
            declared = null;
        }
        var inner = declared == null ? options : options.toBuilder().convertToSrgb(false).build();
        var decoded = header.compression() == BmpHeader.JPEG ? JpegDecoder.decode(payload, inner)
                : PngDecoder.decode(payload, inner);
        var own = decoded.iccProfile().isPresent() || decoded.colorProfile().isPresent();
        var picture = switch (decoded.picture()) {
            case YuvPicture yuv -> ImageOps.toRgba(yuv);
            case Yuv16Picture yuv -> yuv.bitDepth() > 8 ? ImageOps.toRgba16(yuv) : ImageOps.toRgba(yuv);
            case Picture other -> other;
        };
        var image = new DecodedImage(picture, ImageFormat.BMP, decoded.orientation(), decoded.sourceWidth(),
                decoded.sourceHeight(), own ? decoded.iccProfile() : Optional.ofNullable(icc), decoded.exif(),
                decoded.xmp(), own ? decoded.colorProfile() : Optional.ofNullable(declared), decoded.density());
        return declared != null && options.convertToSrgb() ? ImageOps.toSrgb(image) : image;
    }

    /**
     * Exposes the JPEG or PNG file a bitmap embeds.
     *
     * @param source the input
     * @param header the headers
     * @return the embedded file, bounded by the stated image size when there is one
     * @throws MediaException.Malformed if the pixel data is missing
     */
    private static MediaSource payload(MediaSource source, BmpHeader header) {
        var size = source.size();
        if (size >= 0 && header.dataOffset() >= size) {
            throw new MediaException.Malformed("BMP pixel data is missing");
        }
        var available = size < 0 ? -1 : size - header.dataOffset();
        var length = header.dataSize() > 0 && (available < 0 || header.dataSize() < available) ? header.dataSize()
                : available;
        return new SlicedSource(source, header.dataOffset(), length);
    }

    /**
     * Decodes uncompressed or bit-field pixels, row by row, to 8 bits per sample.
     *
     * @param source the input
     * @param header the headers
     * @param pixels the destination canvas, zero-filled
     * @param limits the limits checked before allocating the row buffer
     * @return whether the decoded picture carries transparency
     * @throws MediaException.Malformed if no pixel data is present
     */
    private static boolean decodeRows(MediaSource source, BmpHeader header, byte[] pixels, MediaLimits limits) {
        var width = header.width();
        var height = header.height();
        var stride = header.rowStride();
        var rowBytes = ((long) width * header.bitCount() + 7) / 8;
        LimitChecks.checkAllocation(limits, rowBytes);
        var row = new byte[(int) rowBytes];
        var channels = BmpChannels.of(header, false);
        var decodedPixels = 0L;
        var nonZeroAlpha = 0;
        for (var stored = 0; stored < height; stored++) {
            var read = SourceBytes.readAvailable(source, header.dataOffset() + stored * stride, row, 0, row.length);
            if (read <= 0) {
                if (stored == 0) {
                    throw new MediaException.Malformed("BMP pixel data is missing");
                }
                break;
            }
            var count = (int) Math.min(width, (long) read * 8 / header.bitCount());
            var y = header.topDown() ? stored : height - 1 - stored;
            nonZeroAlpha |= channels.convert(row, count, pixels, y * width * 4);
            decodedPixels += count;
            if (count < width) {
                break;
            }
        }
        if (header.isCmyk()) {
            return false;
        }
        var complete = decodedPixels == (long) width * height;
        if (header.alphaMaskActive() && nonZeroAlpha == 0) {
            makeOpaque(pixels, null, header, decodedPixels);
            return !complete;
        }
        return header.alphaMaskActive() || !complete;
    }

    /**
     * Decodes bit-field or 64-bit pixels, row by row, to 16 bits per sample.
     *
     * @param source the input
     * @param header the headers
     * @param pixels the destination canvas, zero-filled
     * @param limits the limits checked before allocating the row buffer
     * @return whether the decoded picture carries transparency
     * @throws MediaException.Malformed if no pixel data is present
     */
    private static boolean decodeWideRows(MediaSource source, BmpHeader header, short[] pixels, MediaLimits limits) {
        var width = header.width();
        var height = header.height();
        var stride = header.rowStride();
        var rowBytes = ((long) width * header.bitCount() + 7) / 8;
        LimitChecks.checkAllocation(limits, rowBytes);
        var row = new byte[(int) rowBytes];
        var channels = BmpChannels.of(header, true);
        var decodedPixels = 0L;
        var nonZeroAlpha = 0;
        for (var stored = 0; stored < height; stored++) {
            var read = SourceBytes.readAvailable(source, header.dataOffset() + stored * stride, row, 0, row.length);
            if (read <= 0) {
                if (stored == 0) {
                    throw new MediaException.Malformed("BMP pixel data is missing");
                }
                break;
            }
            var count = (int) Math.min(width, (long) read * 8 / header.bitCount());
            var y = header.topDown() ? stored : height - 1 - stored;
            nonZeroAlpha |= channels.convertWide(row, count, pixels, y * width * 4);
            decodedPixels += count;
            if (count < width) {
                break;
            }
        }
        var complete = decodedPixels == (long) width * height;
        if (header.alphaMaskActive() && nonZeroAlpha == 0) {
            makeOpaque(null, pixels, header, decodedPixels);
            return !complete;
        }
        return header.alphaMaskActive() || !complete;
    }

    /**
     * Sets alpha to its maximum on every decoded pixel, used when the alpha channel is zero everywhere.
     *
     * @param pixels the 8-bit canvas, or {@code null}
     * @param wide the 16-bit canvas, or {@code null}
     * @param header the headers
     * @param decoded the number of pixels decoded, in stored order
     */
    private static void makeOpaque(byte[] pixels, short[] wide, BmpHeader header, long decoded) {
        var width = header.width();
        var height = header.height();
        for (var index = 0L; index < decoded; index++) {
            var stored = (int) (index / width);
            var y = header.topDown() ? stored : height - 1 - stored;
            var at = (int) (((long) y * width + index % width) * 4 + 3);
            if (pixels != null) {
                pixels[at] = (byte) 0xFF;
            } else {
                wide[at] = (short) 0xFFFF;
            }
        }
    }

    /**
     * Reads the compressed pixel data of a run-length or Huffman coded bitmap.
     *
     * @param source the input
     * @param header the headers
     * @param limits the limits checked before buffering the data
     * @param useful the longest data that can still set pixels
     * @return the data, of which only the bytes read are valid; its length is at least 1
     * @throws MediaException.Malformed if no pixel data is present
     */
    private static byte[] compressed(MediaSource source, BmpHeader header, MediaLimits limits, long useful) {
        var size = source.size();
        var available = size < 0 ? limits.maxBytes() : size - header.dataOffset();
        if (available <= 0) {
            throw new MediaException.Malformed("BMP pixel data is missing");
        }
        var bound = Math.min(Math.min(available, useful), Integer.MAX_VALUE - 16);
        LimitChecks.checkAllocation(limits, bound);
        return new byte[(int) bound];
    }

    /**
     * Decodes run-length encoded pixels.
     *
     * @param source the input
     * @param header the headers
     * @param pixels the destination canvas, zero-filled
     * @param limits the limits checked before buffering the compressed data
     * @return whether the decoded picture carries transparency (some pixel was not set)
     * @throws MediaException.Malformed if no pixel data is present
     */
    private static boolean decodeRunLength(MediaSource source, BmpHeader header, byte[] pixels, MediaLimits limits) {
        // one code per pixel (four bytes for RLE24) plus an end of line per row is the longest useful encoding
        var perPixel = header.compression() == BmpHeader.RLE24 ? 4L : 2L;
        var useful = (long) header.width() * header.height() * perPixel + (long) header.height() * 2 + 4096;
        var data = compressed(source, header, limits, useful);
        var length = SourceBytes.readAvailable(source, header.dataOffset(), data, 0, data.length);
        if (length <= 0) {
            throw new MediaException.Malformed("BMP pixel data is missing");
        }
        var written = BmpRunLength.decode(data, length, header, pixels);
        return !header.isCmyk() && written < (long) header.width() * header.height();
    }

    /**
     * Decodes OS/2 Huffman 1D pixels.
     *
     * @param source the input
     * @param header the headers
     * @param pixels the destination canvas, zero-filled
     * @param limits the limits checked before buffering the compressed data
     * @return whether the decoded picture carries transparency (some row was not decoded)
     * @throws MediaException.Malformed if no pixel data is present
     */
    private static boolean decodeHuffman(MediaSource source, BmpHeader header, byte[] pixels, MediaLimits limits) {
        var width = header.width();
        var height = header.height();
        var rowBytes = (width + 7) / 8;
        // a row of alternating pixels takes at most 13 bits per pixel plus an end-of-line code
        var useful = ((long) width * 13 + 12) * height / 8 + 4096;
        var data = compressed(source, header, limits, useful);
        var length = SourceBytes.readAvailable(source, header.dataOffset(), data, 0, data.length);
        if (length <= 0) {
            throw new MediaException.Malformed("BMP pixel data is missing");
        }
        LimitChecks.checkAllocation(limits, (long) rowBytes * height);
        var bits = new byte[rowBytes * height];
        var rows = new CcittFaxDecoder().decode(data, 0, length, bits, 0, rowBytes, width, height, 3, 0);
        var channels = BmpChannels.of(header, false);
        var row = new byte[rowBytes];
        for (var stored = 0; stored < rows; stored++) {
            System.arraycopy(bits, stored * rowBytes, row, 0, rowBytes);
            var y = height - 1 - stored;
            channels.convert(row, width, pixels, y * width * 4);
        }
        return rows < height;
    }

    /**
     * Reads the ICC profile embedded by a V5 header.
     *
     * @param source the input
     * @param header the headers
     * @param limits the limits bounding the profile size
     * @return the profile, or {@code null} when absent, linked, out of bounds or too large
     */
    private static byte[] readProfile(MediaSource source, BmpHeader header, MediaLimits limits) {
        if (header.colorSpace() != BmpHeader.EMBEDDED || header.iccOffset() < 0 || header.iccSize() <= 0
                || header.iccSize() > Math.min(MAX_ICC_PROFILE, limits.maxBytes())) {
            return null;
        }
        var profile = new byte[(int) header.iccSize()];
        return SourceBytes.readAvailable(source, header.iccOffset(), profile, 0, profile.length) == profile.length ? profile
                : null;
    }

    /**
     * Builds the profile of a calibrated color space.
     *
     * @param header the headers
     * @return the profile, or {@code null} when the color space is not calibrated or its values are
     * zero, negative or degenerate
     */
    private static ColorProfile calibrated(BmpHeader header) {
        if (header.headerSize() < 108 || header.colorSpace() != BmpHeader.CALIBRATED) {
            return null;
        }
        var endpoints = header.endpoints();
        var xyz = new double[9];
        for (var i = 0; i < 9; i++) {
            xyz[i] = endpoints[i] / (double) (1 << 30);
            if (xyz[i] < 0) {
                return null;
            }
        }
        var gammas = new double[3];
        for (var i = 0; i < 3; i++) {
            gammas[i] = (header.gammas()[i] & 0xFFFF_FFFFL) / 65536.0;
            if (!(gammas[i] > 0 && gammas[i] <= 64)) {
                return null;
            }
        }
        var normalized = true;
        var primaries = new Chromaticity[3];
        for (var channel = 0; channel < 3; channel++) {
            var sum = xyz[channel * 3] + xyz[channel * 3 + 1] + xyz[channel * 3 + 2];
            if (!(sum > 0)) {
                return null;
            }
            normalized &= Math.abs(sum - 1) <= 0.02;
            primaries[channel] = new Chromaticity(xyz[channel * 3] / sum, xyz[channel * 3 + 1] / sum);
        }
        var white = D65;
        if (!normalized) {
            var x = xyz[0] + xyz[3] + xyz[6];
            var y = xyz[1] + xyz[4] + xyz[7];
            var z = xyz[2] + xyz[5] + xyz[8];
            white = new Chromaticity(x / (x + y + z), y / (x + y + z));
        }
        try {
            return ColorProfile.rgb(primaries[0], primaries[1], primaries[2], white, gammas[0], gammas[1], gammas[2]);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
