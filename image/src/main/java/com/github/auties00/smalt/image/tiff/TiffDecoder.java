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
 *       32946), JPEG (compression 7, with {@code JPEGTables}), old-style JPEG (compression 6) when
 *       it points to a complete JPEG stream or stores its tables in the table tags, CCITT
 *       modified Huffman, T.4 (Group 3, one- and two-dimensional, with or without byte-aligned
 *       end-of-line codes) and T.6 (Group 4), ThunderScan, and SGILog (LogL, and LogLuv in its
 *       32-bit and 24-bit forms); {@code FillOrder} 2 (bit-reversed data);</li>
 *   <li>the horizontal predictor on 8-, 16-, 32- and 64-bit samples and the floating-point
 *       predictor;</li>
 *   <li>bilevel and gray (white-is-zero or black-is-zero), palette, RGB, CMYK (separated with
 *       the CMYK ink set), YCbCr (any of the 1, 2 and 4 subsampling factors) and 8- or 16-bit
 *       CIE, ICC and ITU L*a*b* images, with an associated or unassociated alpha sample, and
 *       LogL and LogLuv images;</li>
 *   <li>unsigned and signed integer samples of 1 to 32 bits, and 16-, 32- and 64-bit
 *       floating-point samples.</li>
 * </ul>
 *
 * <p>Samples are converted as follows. Integer samples of {@code n} bits scale to 8 bits as
 * {@code round(v * 255 / (2^n - 1))}, exact for 1, 2, 4 and 8 bits; wider samples reduce to 8
 * bits with exact rounding, {@code (v * 255 + 32767) / 65535} after scaling to 16 bits. Signed
 * samples are offset by {@code 2^(n-1)}. Floating-point samples map the range from
 * {@code SMinSampleValue} to {@code SMaxSampleValue} (0.0 to 1.0 when the tags are absent) linearly
 * onto the 16-bit range, clamping outside it (NaN becomes 0). Palette entries that all fit 8 bits
 * are taken as an old-style 8-bit palette. CMYK is converted naively,
 * {@code R = (255 - K) * (255 - C) / 255} (truncating, likewise G and B); an embedded ICC profile
 * is returned in {@link DecodedImage#iccProfile()} for color management, not applied. YCbCr is
 * converted with the {@code YCbCrCoefficients} and {@code ReferenceBlackWhite} tags, each chroma
 * sample shared by its whole subsampling block; JPEG-coded YCbCr is converted by the JPEG
 * decoder. L*a*b* is rendered for display: to XYZ relative to the
 * {@code WhitePoint} (CIE D50 when absent), then through the sRGB primaries with a pure 2.4 power
 * law, always to 8 bits (ICC L*a*b* takes the ICC version 2 encoding, ITU L*a*b* its
 * {@code Decode} ranges, by default those of ITU-T T.42). LogL and LogLuv are rendered for
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
 * <p>The {@code Orientation} tag supplies {@link DecodedImage#orientation()} (not applied), the
 * ICC profile tag {@link DecodedImage#iccProfile()}, and a page with an EXIF or GPS directory
 * supplies {@link DecodedImage#exif()}: a stand-alone EXIF block with the page's descriptive tags
 * and copies of those directories.
 *
 * <p>Unsupported, and rejected with {@link MediaException.Unsupported} naming the feature:
 * CFA and linear raw photometric interpretations, transparency masks, ink sets other than CMYK,
 * sample sizes that differ between samples, complex samples, 24-bit floating point, JPEG-coded
 * samples other than 8 bits, L*a*b* samples other than 8- and 16-bit integers, LogL and LogLuv
 * images with extra samples or separate planes or without SGILog compression, and the JBIG,
 * JPEG 2000, LZMA, Zstandard, WebP and JPEG XL compression schemes.
 *
 * <p>Damaged files decode as far as they can: a strip or tile that is missing leaves transparent
 * pixels (and the picture then reports alpha), data that ends early leaves zero samples, damaged
 * fax data ends its strip, and a directory chain that loops or points past the end of the file
 * ends at the last readable page. Invalid LZW or Deflate data and invalid structures are
 * {@link MediaException.Malformed}. Decoded pixels, premultiplied, match those of libtiff 4.7's
 * {@code tiff2rgba} tool, up to one step of rounding for 16-bit samples and palettes.
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
     * @param options the limits, the size hint, the bit depth choice and the sRGB conversion
     * @return the decoded page
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
     * @param options the limits and the bit depth choice applied to every page
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
