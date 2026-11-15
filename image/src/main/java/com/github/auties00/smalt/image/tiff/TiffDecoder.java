/* Ports LibTIFF 4.7.1 (tif_dirread.c, tif_getimage.c). Copyright Sam Leffler, Silicon Graphics, Inc.;
   LibTIFF license. Modified for Smalt. */
package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.tiff.internal.TiffFile;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;

/**
 * Decodes TIFF and BigTIFF images (TIFF 6.0, with the TIFF Technical Note 2 JPEG, Adobe Deflate
 * and floating-point predictor extensions) to packed RGBA8, or to packed 16-bit RGBA on request.
 *
 * <p>Supported:
 * <ul>
 *   <li>classic TIFF and BigTIFF, either byte order, multi-page files ({@link #openPages(MediaSource,
 *       ImageDecoderOptions)} reads every page), strips and tiles, and both planar
 *       configurations (interleaved and separate planes);</li>
 *   <li>compression: none, PackBits, LZW (with the pre-6.0 bit order), Deflate (codes 8 and
 *       32946), JPEG (compression 7, with {@code JPEGTables}; 8-bit and 12-bit samples), old-style
 *       JPEG (compression 6, 8-bit samples) when it points to a complete JPEG stream or stores its
 *       tables in the table tags, CCITT
 *       modified Huffman, T.4 (Group 3, one- and two-dimensional, with or without byte-aligned
 *       end-of-line codes) and T.6 (Group 4), ThunderScan, SGILog (LogL, and LogLuv in its
 *       32-bit and 24-bit forms), Zstandard (code 50000, and 34926, which decodes the same), LZMA
 *       (code 34925: XZ streams of LZMA2 data with the delta and branch-call-jump filters, or legacy
 *       .lzma data), PixarLog (code 32909; 8-bit and 16-bit unsigned and 32-bit floating-point
 *       samples), NeXT (code 32766; 2-bit gray), JBIG (code 34661: ITU-T T.82 bi-level image
 *       entities of any parameterization that does not continue another entity), LERC (code 34887:
 *       Lerc2 blobs of versions 1 to 6, bare or wrapped in Deflate or Zstandard as the
 *       {@code LercParameters} tag says, of 8-, 16- and 32-bit integer and 32- and 64-bit
 *       floating-point samples, and legacy Lerc1 blobs of 32-bit floating-point samples), and the
 *       compressions that store a complete image per strip or
 *       tile: WebP (code 50001; 8-bit RGB or RGBA), JPEG XL (codes 50002 and DNG's 52546;
 *       unsigned samples of up to 16 bits and 16- or 32-bit floating-point samples, as codestreams
 *       or containers) and JPEG 2000 (code 34712 and the Aperio codes 33003 and 33005; integer
 *       samples, as codestreams or JP2 files); {@code FillOrder} 2 (bit-reversed data);</li>
 *   <li>the horizontal predictor on 8-, 16-, 32- and 64-bit samples (also of different sizes in
 *       one pixel, and on subsampled YCbCr data units) and the floating-point predictor on 16-, 24-,
 *       32- and 64-bit samples (JPEG, old-style JPEG, SGILog, LERC, NeXT and JBIG pages ignore the
 *       {@code Predictor} tag);</li>
 *   <li>bilevel and gray (white-is-zero or black-is-zero), palette, RGB, CMYK (separated with
 *       the CMYK ink set), separations of other ink sets through their embedded ICC profile, YCbCr
 *       of 1 to 16 bits per sample (any of the 1, 2 and 4 subsampling factors, interleaved or in
 *       separate planes) and CIE, ICC and ITU L*a*b* images of any integer depth or floating point,
 *       with an associated or unassociated alpha sample, LogL and LogLuv images, and transparency
 *       masks;</li>
 *   <li>unsigned and signed integer samples of 1 to 32 bits, 16-, 24-, 32- and 64-bit
 *       floating-point samples, complex integer and complex floating-point samples, and
 *       {@code BitsPerSample} values that differ between the samples of a pixel.</li>
 * </ul>
 *
 * <p>Samples are converted as follows. Integer samples of {@code n} bits scale to 8 bits as
 * {@code round(v * 255 / (2^n - 1))}, exact for 1, 2, 4 and 8 bits; wider samples reduce to 8
 * bits with exact rounding, {@code (v * 255 + 32767) / 65535} after scaling to 16 bits. Signed
 * samples are offset by {@code 2^(n-1)}. Floating-point samples map the range from
 * {@code SMinSampleValue} to {@code SMaxSampleValue} (0.0 to 1.0 when the tags are absent) linearly
 * onto the 16-bit range, clamping outside it (NaN becomes 0); 24-bit floating-point samples have 1
 * sign bit, 7 exponent bits biased by 63 and 16 fraction bits, with the IEEE 754 rules for zero,
 * subnormal, infinite and NaN values. Samples whose sizes differ are each scaled from their own range,
 * to 8 bits when no sample is wider than 8 bits and through 16 bits otherwise; a sample that is not a
 * whole number of bytes starting on a byte boundary is packed high-order bit first whatever the byte
 * order. Complex samples, a real part then an imaginary part of half the {@code BitsPerSample} each,
 * decode to their magnitude: for complex integers with parts of {@code h} bits, rounded, clamped to
 * {@code 2^(h-1) - 1} and scaled from {@code 0..2^(h-1) - 1} to 16 bits; for complex floating point,
 * as a floating-point sample. Palette entries that all fit 8 bits
 * are taken as an old-style 8-bit palette. CMYK is converted naively as
 * {@link ImageOps#toRgba(com.github.auties00.smalt.image.Picture)} converts a CMYK picture,
 * {@code R = round((M - C) * (M - K) / M)} at the samples' depth (likewise G and B, after an associated
 * alpha is divided out of the inks); an embedded ICC profile is returned in
 * {@link DecodedImage#iccProfile()} for color management, not applied. YCbCr is
 * converted with the {@code YCbCrCoefficients} and {@code ReferenceBlackWhite} tags, each chroma
 * sample shared by its whole subsampling block. Samples of depths other than 8 bits are converted
 * with the formulas of TIFF 6.0 sections 20 and 21 in double precision to 16-bit values, the coding
 * range of Cb and Cr being {@code 2^(n-1) - 1} at {@code n} bits and {@code ReferenceBlackWhite}
 * defaulting to {@code [0, 2^n - 1, 2^(n-1), 2^n - 1, 2^(n-1), 2^n - 1]}. In separate planes, the Cb
 * and Cr planes are the subsampled images TIFF 6.0 section 21 describes, each strip or tile covering
 * the area of the Y plane's, and extra samples are planes at full resolution. The horizontal
 * predictor of interleaved subsampled data predicts each sample from the one
 * {@code SamplesPerPixel} positions before it, restarting every row of data units divided by the
 * vertical subsampling, as libtiff codes it. JPEG-coded YCbCr is converted by the JPEG
 * decoder, at 12 bits for 12-bit JPEG data. L*a*b* is rendered for display: to XYZ relative to the
 * {@code WhitePoint} (CIE D50 when absent), then through the sRGB primaries with a pure 2.4 power
 * law, always to 8 bits (ICC L*a*b* takes the ICC version 2 encoding, ITU L*a*b* its
 * {@code Decode} ranges, by default those of ITU-T T.42). L*a*b* samples of other integer depths keep
 * the meaning of their encoding and are rendered as their nearest 16-bit codes ({@code L*} scaled
 * from the full code range, or for ICC L*a*b* to the range ending at 0xFF00, and {@code a*} and
 * {@code b*} scaled by {@code 2^(16-n)}, except for ITU L*a*b*, whose codes all scale from the full
 * range); floating-point L*a*b* samples hold {@code L*}, {@code a*} and {@code b*} themselves and are
 * rounded to the nearest 16-bit code. A separated page of another ink set ({@code InkSet} 2, named
 * by {@code InkNames} and counted by {@code NumberOfInks}) is converted to sRGB through its embedded
 * ICC profile, which must have one channel per ink (an {@code nCLR} profile), at the depth of its
 * samples; the result reports the sRGB profile. A transparency mask applies to the page it masks as
 * {@link TiffPages#read(int)} describes. The images of WebP, JPEG XL and JPEG 2000
 * strips and tiles supply the stored samples: sample {@code i} of each pixel takes channel
 * {@code i} of the image (for JPEG XL the color channels, then the extra channels in order), JPEG XL
 * integer samples scale its nominal range from 0 to 1 onto the sample range, JPEG 2000 samples are
 * taken as stored, and a subsampled JPEG 2000 channel gives each pixel its nearest sample to the
 * upper left. A YCbCr page of these compressions decodes to RGB: WebP and JPEG XL images are RGB,
 * and JPEG 2000 Y, Cb and Cr channels (also those of Aperio code 33003, whose pages are tagged RGB)
 * are converted with the {@code YCbCrCoefficients} and {@code ReferenceBlackWhite} tags as above,
 * which default to full-range BT.601; the channels of Aperio code 33005 are RGB whatever the page's
 * photometric interpretation. A coded image wider or taller than its strip or tile is
 * cropped, one that is smaller leaves the samples it does not cover zero, and one with more pixels
 * than a full strip or tile exceeds the limits. A LERC strip or tile is one blob of its width and
 * height and of the samples' data type, holding one value per sample, or, for interleaved 8-bit
 * samples whose last extra sample is unassociated alpha, one value fewer with the alpha 255 inside the
 * blob's mask and 0 outside; interleaved floating-point samples may also be one band per sample.
 * Floating-point samples outside the mask are NaN, integer ones 0, and the values are the samples as
 * the file's little-endian byte order would store them, whatever its actual byte order, as libtiff
 * writes them. PixarLog codes become 8-bit, 16-bit or floating-point samples through PixarLog's linear
 * and logarithmic code tables (1.0, the code 1250, is 255, 65535 or 1.0). A JBIG strip or tile is
 * least significant bit first unless {@code FillOrder} is 2, as libtiff stores it, and its lowest
 * bit-plane at full resolution gives the samples. LogL and LogLuv are rendered for
 * display, to 8 bits with a square-root transfer (luminance 1 is white) through the ITU-R BT.709
 * primaries. The
 * color profile of CMYK, L*a*b*, LogL and LogLuv pages is reported empty, since an embedded
 * profile does not describe the converted pixels; with
 * {@link ImageDecoderOptions#convertToSrgb()} set, a CMYK page with an embedded CMYK profile is
 * converted through it instead, and other pages with a profile are converted as
 * {@link ImageOps#toSrgb(com.github.auties00.smalt.image.DecodedImage)} converts them. Premultiplied
 * (associated) alpha is divided out, so pictures carry straight alpha. With
 * {@link ImageDecoderOptions#highBitDepth()} set, a page with samples wider than 8 bits (or
 * floating point) decodes to an {@link Rgba16Picture} holding the 16-bit values instead; reducing
 * it with {@link ImageOps#toEightBit(Rgba16Picture)} gives exactly the default 8-bit output.
 *
 * <p>With {@link ImageDecoderOptions#keepChannels()} set (and {@link ImageDecoderOptions#convertToSrgb()}
 * not set), a separated page of any ink set, and a gray or RGB page with floating-point samples or with
 * extra samples other than one alpha sample, decodes to a
 * {@link com.github.auties00.smalt.image.ChannelPicture ChannelPicture} holding the samples as stored: CMYK
 * ({@code InkSet} 1) or named inks ({@code InkSet} 2, named by {@code InkNames}, counted by
 * {@code NumberOfInks} or else by the samples that are not extra samples), gray or RGB, followed by one
 * channel per extra sample as {@code ExtraSamples} codes it: the first one coded as associated or unassociated
 * alpha is the alpha channel, and every other one is unspecified. Integer samples are scaled
 * as above to 8 bits, or to 16 bits for samples wider than 8 bits when
 * {@link ImageDecoderOptions#highBitDepth()} is also set; floating-point samples are kept unscaled and
 * unclamped in a {@link com.github.auties00.smalt.image.ChannelFloatPicture ChannelFloatPicture}, 64-bit ones
 * narrowed to the nearest float, and white-is-zero gray is inverted ({@code SMinSampleValue + SMaxSampleValue
 * - v} for floating point). Converting the picture with
 * {@link ImageOps#toRgba(com.github.auties00.smalt.image.Picture)} or
 * {@link ImageOps#toRgba16(com.github.auties00.smalt.image.Picture)} gives exactly the default 8-bit or 16-bit
 * output when the page is gray, RGB or CMYK, its samples are integers or 16 or 32-bit floating point without
 * {@code SMinSampleValue} and {@code SMaxSampleValue} tags, samples wider than 8 bits were decoded with
 * {@code highBitDepth}, and the page is not an RGB page whose first extra sample is coded as unspecified
 * (which the default output takes as associated alpha). The color profile of the result is the embedded
 * profile when it has as many channels as the color channels. A strip or tile that is missing leaves zero
 * samples. Other pages decode as without the option.
 *
 * <p>The {@code Orientation} tag supplies {@link DecodedImage#orientation()} (not applied), the
 * ICC profile tag {@link DecodedImage#iccProfile()}, and a page with an EXIF or GPS directory
 * supplies {@link DecodedImage#exif()}: a stand-alone EXIF block with the page's descriptive tags
 * and copies of those directories.
 *
 * <p>Unsupported, and rejected with {@link MediaException.Unsupported} naming the feature:
 * CFA and linear raw photometric interpretations, ink sets other than CMYK without an embedded ICC
 * profile of one channel per ink (except to a channel picture), interleaved subsampled YCbCr with extra
 * samples (TIFF 6.0 defines data units of Y, Cb and Cr only), YCbCr samples that are wider than 16
 * bits, floating point or of different sizes, samples of different sizes, complex samples and
 * L*a*b* samples other than 8- and 16-bit integers in strips or tiles compressed other than with
 * PackBits, LZW, Deflate, Zstandard or LZMA, the floating-point predictor on samples of different sizes, JPEG-coded
 * samples other than 8 and 12 bits (old-style JPEG: other than 8 bits), complex L*a*b* samples,
 * LogL and LogLuv images with extra samples or separate planes or
 * without SGILog compression, WebP pages other than interleaved 8-bit RGB or RGBA, JPEG XL pages of
 * signed or wider samples, floating-point JPEG 2000 pages, JPEG 2000 YCbCr pages other than
 * interleaved 8-bit, PixarLog samples other than 8-bit and 16-bit unsigned and 32-bit floating point,
 * LERC samples of other types, and the LERC codes 34888 and 34889, which Esri reserves without a
 * definition.
 *
 * <p>Damaged files decode as far as they can: a strip or tile that is missing leaves transparent
 * pixels (and the picture then reports alpha), data that ends early leaves zero samples where it
 * stops (a Zstandard compressed block or a JBIG stripe that is cut short decodes to nothing), damaged
 * fax data ends its strip, and a directory chain that loops or points past the end of the file
 * ends at the last readable page. Invalid LZW, Deflate, Zstandard, LZMA, PixarLog, LERC, JBIG, WebP,
 * JPEG XL or JPEG 2000 data, a LERC blob or JBIG image that does not fit its strip or tile, a coded
 * image with fewer channels than the page has samples, and invalid structures are
 * {@link MediaException.Malformed}. Decoded pixels, premultiplied, match those of libtiff 4.7's
 * {@code tiff2rgba} tool, up to one step of rounding for 16-bit samples, palettes and CMYK (which
 * {@code tiff2rgba} truncates); WebP pages with {@code FillOrder} 2 are bit-reversed before decoding,
 * as for every other compression, which {@code tiff2rgba} does not do; the rows of PixarLog pages of
 * one sample per pixel or in separate planes accumulate every difference, as libtiff codes them,
 * where {@code tiff2rgba} leaves the first sample of each row out; and PixarLog tiles hold rows as
 * wide as the tile, where libtiff reads rows as wide as the image.
 *
 * <p>The size hint of {@link ImageDecoderOptions} is honored with a page's reduced-resolution
 * levels when it has any, as {@link TiffPages#read(int)} describes. Strips and tiles decode in
 * parallel on the shared threads {@link com.github.auties00.smalt.MediaRuntime MediaRuntime}
 * describes; the pixels do not depend on the thread count. This class is thread-safe.
 */
public final class TiffDecoder {
    /**
     * Prevents instantiation.
     */
    private TiffDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes the first page of a TIFF file from its directory, without decoding pixels.
     *
     * @param source the input, read from offset zero
     * @return the summary; {@link ImageInfo#frameCount()} is the number of pages and
     * {@link ImageInfo#animated()} is {@code false}
     * @throws MediaException.Malformed if the header or the first directory is invalid
     * @throws MediaException.Unsupported if a BigTIFF declares offsets other than 8 bytes
     * @throws MediaException.LimitExceeded if the page count exceeds the frame limit, or probing
     * runs out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var pages = openPages(source)) {
            return pages.probe(0);
        }
    }

    /**
     * Decodes the first page of a TIFF file under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input
     * @return the decoded page
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the page uses a feature listed on the class as
     * unsupported
     * @throws MediaException.LimitExceeded if the page exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the first page of a TIFF file.
     *
     * <p>The picture is an {@link RgbaPicture}, or an {@link Rgba16Picture} for samples wider than
     * 8 bits when {@link ImageDecoderOptions#highBitDepth()} is set; the reduced-size hint selects
     * a reduced-resolution level when the page has one, as {@link TiffPages#read(int)} describes.
     * Use {@link #openPages(MediaSource, ImageDecoderOptions)} to read the other pages.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the size hint, the region, the bit depth choice and the sRGB conversion
     * @return the decoded page
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the page uses a feature listed on the class as
     * unsupported
     * @throws MediaException.LimitExceeded if the page exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        try (var pages = openPages(source, options)) {
            return pages.read(0);
        }
    }

    /**
     * Opens the pages of a TIFF file under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the page reader
     * @throws MediaException.Malformed if the header or the first directory is invalid
     * @throws MediaException.Unsupported if a BigTIFF declares offsets other than 8 bytes
     * @throws MediaException.LimitExceeded if the page count exceeds the frame limit
     * @throws MediaException.Io if reading the source fails
     */
    public static TiffPages openPages(MediaSource source) {
        return openPages(source, ImageDecoderOptions.defaults());
    }

    /**
     * Opens the pages of a TIFF file: reads its header and its chain of image file directories.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the size hint, the region and the bit depth choice applied to every page; a
     * page that the region does not lie inside raises {@link IllegalArgumentException} when it is read
     * @return the page reader
     * @throws MediaException.Malformed if the header or the first directory is invalid
     * @throws MediaException.Unsupported if a BigTIFF declares offsets other than 8 bytes
     * @throws MediaException.LimitExceeded if the page count exceeds the frame limit
     * @throws MediaException.Io if reading the source fails
     */
    public static TiffPages openPages(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return Guards.malformed("TIFF file", () -> new TiffPages(TiffFile.open(source, options.limits()), options));
    }
}
