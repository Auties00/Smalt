package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.jp2.internal.Jp2Pictures;
import com.github.auties00.smalt.image.jp2.internal.Jp2Reader;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;
import java.util.Optional;

/**
 * Decodes JPEG 2000 images (ISO/IEC 15444-1): JP2 and JPX files and raw codestreams.
 *
 * <p>Supported:
 * <ul>
 *   <li>the JP2 file format and the parts of JPX a JP2 reader understands: the image header,
 *       color specifications by enumeration or ICC profile (the first one with a known method),
 *       palettes with component mappings, channel definitions with opacity and premultiplied
 *       opacity, and EXIF and XMP metadata in {@code uuid} and {@code xml } boxes; and raw
 *       codestreams;</li>
 *   <li>the whole of Part 1 coding: any number of tiles and tile-parts, 1 to 16384 components with
 *       any subsampling, precisions of 1 to 31 bits, signed or unsigned, all five progression
 *       orders and progression order changes, user precinct sizes, packed packet headers
 *       ({@code PPM} and {@code PPT}), {@code SOP} and {@code EPH} markers, every code-block size
 *       and style (arithmetic coding bypass, context reset, termination on each pass, vertically
 *       causal contexts, predictable termination and segmentation symbols), no quantization and
 *       scalar derived or expounded quantization, the reversible 5-3 and irreversible 9-7
 *       wavelets with up to 32 decomposition levels, the reversible and irreversible component
 *       transforms, and regions of interest coded with the maxshift method;</li>
 *   <li>decoding at a reduced resolution, which discards whole resolution levels and so decodes
 *       large images several times faster, and decoding the first quality layers only.</li>
 * </ul>
 *
 * <p>{@link #decodeChannels(MediaSource, Jp2DecoderOptions)} returns the samples of each channel
 * at their own precision and subsampling, for applications that need the stored values (medical,
 * geospatial or scientific images). {@link #decode(MediaSource, ImageDecoderOptions)} returns a
 * picture: gray, RGB or CMYK channels and an opacity channel converted to RGBA, scaled to 8 bits,
 * or to 16 bits when {@link ImageDecoderOptions#highBitDepth()} is set and a channel is wider than
 * 8 bits. An enumerated sRGB, sYCC or ROMM-RGB color space, or an ICC profile, is reported through
 * {@link DecodedImage#colorProfile()} and applied when {@link ImageDecoderOptions#convertToSrgb()}
 * is set.
 *
 * <p>Unsupported, and rejected with {@link MediaException.Unsupported} naming the feature: the
 * high-throughput block coder of Part 15 (HTJ2K), Part 2 coding extensions (arbitrary wavelets,
 * multiple component transforms, trellis quantization, region-of-interest styles other than
 * maxshift), codestreams stored in JPX fragment tables, precisions above 31 bits, reference grids
 * larger than 2^31, and the conversion of CIE L*a*b*, CIECAM02 J*a*b*, YCCK and PhotoYCC samples
 * to a picture (their channels still decode).
 *
 * <p>Damaged codestreams decode as far as their data goes: a truncated tile leaves its missing
 * code-block data at zero, and a missing tile is uniformly mid-gray. An invalid main header is
 * {@link MediaException.Malformed}. The decoded samples are bit-identical to OpenJPEG's
 * {@code opj_decompress} for both wavelets.
 *
 * <p>This class is thread-safe.
 *
 * @implNote This implementation ports OpenJPEG 2.5 and computes the irreversible transforms with
 * the same single-precision operations in the same order. Tiles, code-blocks and wavelet rows and
 * columns are decoded in parallel on the shared pool that
 * {@link com.github.auties00.smalt.MediaRuntime} describes.
 */
public final class Jp2Decoder {
    /**
     * The input, named in failure messages.
     */
    private static final String WHAT = "JPEG 2000 file";

    /**
     * Prevents instantiation.
     */
    private Jp2Decoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes a JPEG 2000 image from its boxes and main header, without decoding packets.
     *
     * @param source the input
     * @return the summary; a JPEG 2000 image has one frame
     * @throws MediaException.Malformed if the file or codestream headers are invalid
     * @throws MediaException.Unsupported if the file stores its codestream in a way listed on the
     * class as unsupported
     * @throws MediaException.LimitExceeded if the input exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                // TODO: read only the boxes and the main header instead of the whole source
                var data = SourceBytes.readAll(source, MediaLimits.DEFAULT, false);
                var headers = Jp2Reader.headers(data, MediaLimits.DEFAULT);
                var codestream = headers.codestream();
                var exif = headers.boxes().exif();
                var orientation = exif == null ? Orientation.NORMAL : ExifReader.orientation(exif);
                return new ImageInfo(ImageFormat.JP2, codestream.width(), codestream.height(), headers.hasAlpha(),
                        false, 1, orientation);
            });
        }
    }

    /**
     * Decodes a JPEG 2000 image to a picture under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input
     * @return the decoded image
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file uses a feature listed on the class as
     * unsupported
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes a JPEG 2000 image to a picture.
     *
     * <p>When the options ask for a minimum size, the image is decoded at the smallest reduced
     * resolution, {@code 2^r} times smaller with each dimension rounded up, that still covers it,
     * discarding the resolution levels above it; the result reports the full size through
     * {@link DecodedImage#sourceWidth()} and {@link DecodedImage#sourceHeight()}.
     *
     * @param source the input
     * @param options the limits, the size hint, the bit depth choice and the sRGB conversion
     * @return the decoded image: an {@link RgbaPicture}, or an {@link Rgba16Picture} when
     * {@link ImageDecoderOptions#highBitDepth()} is set and a channel is wider than 8 bits
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file uses a feature listed on the class as
     * unsupported
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, options.limits(), true);
                var headers = Jp2Reader.headers(data, options.limits());
                var codestream = headers.codestream();
                var reduction = reduction(codestream.width(), codestream.height(), options.minWidth(),
                        options.minHeight());
                var image = Jp2Reader.decode(headers, reduction, 0, options.limits());
                var picture = Jp2Pictures.toPicture(image, options.highBitDepth(), options.limits());
                var exif = image.exif();
                var orientation = exif.isEmpty() ? Orientation.NORMAL : ExifReader.orientation(exif.get());
                var decoded = new DecodedImage(picture, ImageFormat.JP2, orientation, image.fullWidth(),
                        image.fullHeight(), image.iccProfile(), exif, profile(image));
                return options.convertToSrgb() ? ImageOps.toSrgb(decoded) : decoded;
            });
        }
    }

    /**
     * Decodes the channels of a JPEG 2000 image at full resolution under
     * {@link Jp2DecoderOptions#defaults()}.
     *
     * @param source the input
     * @return the channels
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file uses a coding feature listed on the class as
     * unsupported
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #decodeChannels(MediaSource, Jp2DecoderOptions)
     */
    public static Jp2Image decodeChannels(MediaSource source) {
        return decodeChannels(source, Jp2DecoderOptions.defaults());
    }

    /**
     * Decodes the channels of a JPEG 2000 image, keeping each channel's precision, signedness
     * and subsampling.
     *
     * @param source the input
     * @param options the limits, the reduction and the number of quality layers
     * @return the channels
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file uses a coding feature listed on the class as
     * unsupported
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static Jp2Image decodeChannels(MediaSource source, Jp2DecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, options.limits(), true);
                return Jp2Reader.decode(Jp2Reader.headers(data, options.limits()), options.reduction(),
                        options.qualityLayers().orElse(0), options.limits());
            });
        }
    }

    /**
     * Chooses the reduction that decodes the smallest image still covering a minimum size.
     *
     * @param width the full width
     * @param height the full height
     * @param minWidth the smallest acceptable width, or 0
     * @param minHeight the smallest acceptable height, or 0
     * @return the number of resolution levels to discard
     */
    private static int reduction(int width, int height, int minWidth, int minHeight) {
        if (minWidth <= 0 && minHeight <= 0) {
            return 0;
        }
        var reduction = 0;
        while (reduction < 32) {
            var next = reduction + 1;
            var reducedWidth = (width + (1L << next) - 1) >> next;
            var reducedHeight = (height + (1L << next) - 1) >> next;
            if (reducedWidth < minWidth || reducedHeight < minHeight || (reducedWidth == 1 && reducedHeight == 1)) {
                break;
            }
            reduction = next;
        }
        return reduction;
    }

    /**
     * Returns the color profile describing the pixels of a decoded image.
     *
     * @param image the decoded image
     * @return the embedded profile, sRGB for the sRGB, e-sRGB, sYCC and e-sYCC color spaces,
     * ProPhoto RGB for ROMM-RGB, or empty
     */
    private static Optional<ColorProfile> profile(Jp2Image image) {
        if (image.iccProfile().isPresent()) {
            try {
                return Optional.of(ColorProfile.parse(image.iccProfile().get()));
            } catch (MediaException exception) {
                return Optional.empty();
            }
        }
        return switch (image.colorSpace()) {
            case SRGB, E_SRGB, SYCC, E_SYCC -> Optional.of(ColorProfile.srgb());
            case ROMM_RGB -> Optional.of(ColorProfile.proPhotoRgb());
            default -> Optional.empty();
        };
    }
}
