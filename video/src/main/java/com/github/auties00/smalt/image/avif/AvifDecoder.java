package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.AuxiliaryImage;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.internal.heif.HeifPages;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.image.avif.internal.AvifFiles;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Decodes AVIF images (AV1 Image File Format): still images, grids, derived images, alpha,
 * layered images, thumbnails and image sequences.
 *
 * <p>The primary image decodes to its native planar representation: a {@link YuvPicture} in the
 * chroma layout of its AV1 payload (4:2:0, 4:2:2, 4:4:4 or monochrome), with an alpha plane when
 * the file has an alpha auxiliary image. Sources of 10 and 12 bits are reduced to 8 bits by
 * rounding to nearest, exactly as the AV1 decoder of this module reduces them; with
 * {@link ImageDecoderOptions#highBitDepth()} set they decode to a {@link Yuv16Picture} of their
 * own depth instead, which
 * {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit(Yuv16Picture)} reduces to
 * exactly the default 8-bit picture. A source of more than 8 bits in the YCgCo-Re or YCgCo-Ro matrix, whose
 * 8-bit samples would keep only 6 or 7 bits of R'G'B', decodes to an {@link RgbaPicture} by default instead,
 * converted from its own depth. An image whose color is premultiplied by its alpha
 * ({@code prem} reference) decodes to a straight-alpha {@link RgbaPicture} instead, or, with
 * {@link ImageDecoderOptions#highBitDepth()} and a source of more than 8 bits, to a straight-alpha
 * {@link com.github.auties00.smalt.image.Rgba16Picture}.
 *
 * <p>The file structure follows ISO/IEC 23008-12 (HEIF), MIAF and the AVIF specification:
 * <ul>
 *   <li>a primary item coded as AV1 ({@code av01}), or an image grid ({@code grid}) of AV1
 *       tiles, which decode in parallel on the module's shared helper threads (a single item
 *       uses the AV1 decoder's frame-level threads);</li>
 *   <li>derived images (ISO/IEC 23008-12 clause 6.6.2), nested to any depth up to 8: an identity
 *       derivation ({@code iden}) shows its input; an image overlay ({@code iovl}) places its
 *       inputs over a canvas of its fill color, each over the ones before it, and decodes to a
 *       straight-alpha {@link RgbaPicture} (a {@link com.github.auties00.smalt.image.Rgba16Picture}
 *       with {@link ImageDecoderOptions#highBitDepth()} and an input of more than 8 bits); a grid
 *       may take derived inputs, and decodes to RGBA when an input is RGBA or has alpha. The
 *       inputs of a derived image have their own transformative properties applied;</li>
 *   <li>the sample transform of AVIF 1.2 ({@code sato}), which reconstructs images of up to 16
 *       bits from coded images of fewer, for example the 8 most and 8 least significant bits of
 *       each sample: its expression is evaluated exactly, on the integers of its intermediate
 *       width, over the samples of its inputs at their own depth, and gives a picture of the depth
 *       of its {@code pixi} property. A sample transform item is shown when it is the primary item,
 *       or when the alternatives group ({@code altr}) of the primary item lists it before the
 *       primary item and it is not a thumbnail; one that uses a version, an operator or inputs
 *       this decoder does not support leaves the primary item shown, as the group intends;</li>
 *   <li>the alpha plane of an auxiliary item ({@code auxl} reference, alpha {@code auxC}
 *       URN), as one item, a grid, or one item per color tile; limited-range alpha is expanded
 *       to full range, alpha of another bit depth is rescaled to the color depth, and an alpha
 *       image of another size than the color image (or color tile) it covers is resampled to it
 *       with the triangle filter ({@link com.github.auties00.smalt.image.ops.ResizeFilter#BILINEAR});</li>
 *   <li>layered images: the operating point of {@code a1op} and the layer of {@code lsel};
 *       without {@code lsel} the highest layer of the operating point is shown;</li>
 *   <li>the clean aperture ({@code clap}) is applied to the pixels; the rotation ({@code irot})
 *       and mirroring ({@code imir}) are reported as {@link DecodedImage#orientation()}, which
 *       the caller applies (for example with
 *       {@link com.github.auties00.smalt.image.ops.ImageOps#orient}), as for the EXIF
 *       orientation of other formats. The transformative properties are composed in their
 *       association order. A crop that starts inside a subsampled chroma block yields a 4:4:4
 *       picture, so that no pixel changes color;</li>
 *   <li>the color description comes from the {@code nclx} color box, or from the AV1 sequence
 *       header without one, together with the {@code clli} and {@code mdcv} high-dynamic-range
 *       properties (or the AV1 metadata OBUs); an ICC profile ({@code colr} of type {@code rICC}
 *       or {@code prof}), the EXIF item and the XMP item ({@code mime} item of type
 *       {@code application/rdf+xml}) of the primary image are returned with the picture. The ICC
 *       profile is the {@link DecodedImage#colorProfile()}, which otherwise describes the color of
 *       the {@code nclx} box or the sequence header when it is not sRGB.</li>
 * </ul>
 * The EXIF orientation is not used: AVIF orients images with {@code irot} and {@code imir}.
 *
 * <p>The gain map of a tone map derived image item ({@code tmap}, ISO 21496-1) decodes with
 * {@link #decodeGainMap(MediaSource, ImageDecoderOptions)}, for
 * {@link com.github.auties00.smalt.image.gainmap.GainMaps#apply}; the primary image stays the one shown. The
 * auxiliary images of the primary image (alpha, depth maps, thumbnails, the gain map) are listed by
 * {@link #auxiliaryImages(MediaSource, MediaLimits)} and decode one by one.
 *
 * <p>An image sequence ({@code avis}) plays through {@link #openAnimation}: its color track and
 * its alpha track, every frame converted to RGBA (16-bit RGBA when
 * {@link ImageDecoderOptions#highBitDepth()} is set and the sequence has more than 8 bits), with
 * the loop count of the edit list (a track without an edit list loops forever). The reader seeks:
 * it restarts the decoding at the last sync sample at or before the requested time and decodes up
 * to the frame shown then, which is the frame a decode from the start shows. {@link #decode}
 * returns the first frame of a sequence when the file's major brand is {@code avis}, and the
 * primary image otherwise. {@link #openPages} reads every master image of a collection.
 *
 * <p>Every image decodes with the artifact that implements its coding, whatever the brands of its
 * file: a file with only the generic HEIF brands ({@code mif1}, {@code miaf}, {@code msf1}) decodes here
 * when its image is AV1-coded, and a file whose primary image (or whose sequence, without a primary
 * image) is coded with HEVC, AVC, JPEG, JPEG 2000 or as uncompressed samples decodes exactly as
 * {@code com.github.auties00.smalt.image.heic.HeicDecoder} of the {@code smalt-mpeg} artifact decodes
 * it, and reports {@link ImageFormat#HEIC}. Without that artifact such a file raises
 * {@link MediaException.Unsupported} naming it.
 *
 * <p>Every size the file declares is checked against the {@link MediaLimits} of the options
 * before the corresponding allocation: the {@code meta} and {@code moov} boxes and every item
 * against {@link MediaLimits#maxBytes()}, the picture, grid and tiles against the size limits,
 * the sample counts against {@link MediaLimits#maxFrames()}. Malformed input raises
 * {@link MediaException.Malformed}, never an unchecked runtime exception; a valid file using a
 * feature this decoder lacks (an essential property it does not know, a primary item coded with
 * a codec no Smalt artifact decodes) raises {@link MediaException.Unsupported}.
 *
 * <p>Under the reduced-size hint of {@link ImageDecoderOptions}, a still image decodes to the
 * smallest of its thumbnail items ({@code thmb} reference) that is at least that large and shows
 * the image in the same orientation and aspect ratio (within two pixels), and at full size when it
 * has none; {@link DecodedImage#sourceWidth()} and {@link DecodedImage#sourceHeight()} keep the
 * full size. Sequences ignore the hint. With {@link ImageDecoderOptions#convertToSrgb()} the
 * pixels are converted to sRGB.
 *
 * @implNote This implementation follows libavif (BSD 2-clause) for the container semantics: the
 *           item and track selection, the property rules, the grid and clean aperture validation
 *           and the alpha handling. The AV1 payloads decode with the dav1d port of
 *           {@link com.github.auties00.smalt.video.av1.Av1Decoder}, so the samples are those of
 *           dav1d and libavif.
 */
public final class AvifDecoder {
    /**
     * Prevents instantiation.
     */
    private AvifDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes an AVIF file from its headers, without decoding pixels.
     *
     * <p>The size is that of the shown image after the clean aperture and before the
     * orientation; for a sequence it is the track size, and the frame count is the sample count.
     *
     * @param source the file
     * @return the summary
     * @throws NullPointerException       if {@code source} is {@code null}
     * @throws MediaException.Malformed   if the file structure is invalid or truncated
     * @throws MediaException.Unsupported if the file uses a feature this decoder lacks
     * @throws MediaException.Io          if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var _ = ParallelBands.operation()) {
            return AvifFiles.probe(source, MediaLimits.DEFAULT);
        } catch (MediaException exception) {
            throw exception;
        } catch (OutOfMemoryError error) {
            throw new MediaException.LimitExceeded("out of memory while probing an AVIF file");
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("malformed AVIF file", exception);
        }
    }

    /**
     * Decodes an AVIF image under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the file
     * @return the decoded image
     * @throws NullPointerException         if {@code source} is {@code null}
     * @throws MediaException.Malformed     if the file is invalid, corrupt or truncated
     * @throws MediaException.Unsupported   if the file uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io            if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the image an AVIF file shows: its primary image (or the sample transform preferred
     * to it), a thumbnail of it under the reduced-size hint, or the first frame of its sequence when
     * its major brand is {@code avis}.
     *
     * @param source  the file
     * @param options the limits, the bit depth choice, the reduced-size hint and the color
     *                conversion
     * @return the decoded image with its orientation, ICC profile, EXIF block and XMP packet
     * @throws NullPointerException         if an argument is {@code null}
     * @throws MediaException.Malformed     if the file is invalid, corrupt or truncated
     * @throws MediaException.Unsupported   if the file uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io            if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return AvifFiles.decode(source, options);
        } catch (MediaException exception) {
            throw exception;
        } catch (OutOfMemoryError error) {
            throw new MediaException.LimitExceeded("out of memory while decoding an AVIF file");
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("malformed AVIF file", exception);
        }
    }

    /**
     * Decodes the gain map of the primary image of an AVIF file under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the file
     * @return the gain map, or empty when the file has none
     * @throws MediaException.Malformed if the file or the gain map is invalid
     * @throws MediaException.Unsupported if the gain map image uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #decodeGainMap(MediaSource, ImageDecoderOptions)
     */
    public static Optional<GainMap> decodeGainMap(MediaSource source) {
        return decodeGainMap(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the gain map of the primary image of an AVIF file.
     *
     * <p>The gain map is the second input of the tone map derived image item ({@code tmap}) whose first input is
     * the primary item: an item with data that references exactly two distinct items with {@code dimg} and that an
     * alternatives group ({@code altr}) prefers to the primary item, in a file with the {@code tmap} brand. Its
     * payload is a version byte, 0, followed by the ISO 21496-1 metadata
     * ({@link com.github.auties00.smalt.image.gainmap.GainMapMetadata#parse(byte[])}); another version, or metadata
     * of an unsupported minimum version, is ignored as libavif ignores it. The gain map item decodes as an image
     * item does, without its orientation; the {@code colr} and {@code clli} properties of the {@code tmap} item
     * describe the alternate rendition.
     *
     * @param source the file
     * @param options the limits and the bit depth choice of the gain map image; the size hint and the color
     * conversion are ignored
     * @return the gain map, or empty when the file has none or shows an image sequence
     * @throws MediaException.Malformed if the file or the gain map is invalid
     * @throws MediaException.Unsupported if the gain map image uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static Optional<GainMap> decodeGainMap(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return auxiliaryGuarded(() -> AvifFiles.decodeGainMap(source, options));
    }

    /**
     * Lists the auxiliary images of the primary image of an AVIF file within the
     * {@linkplain MediaLimits#DEFAULT default limits}.
     *
     * @param source the file
     * @return the images, as {@link #auxiliaryImages(MediaSource, MediaLimits)} describes
     * @throws MediaException.Malformed if the file structure is invalid or truncated
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static List<AuxiliaryImage> auxiliaryImages(MediaSource source) {
        return auxiliaryImages(source, MediaLimits.DEFAULT);
    }

    /**
     * Lists the auxiliary images of the primary image of an AVIF file: its auxiliary items ({@code auxl}
     * references: alpha planes, depth maps, mattes, gain maps and others, by the URN of their {@code auxC}
     * property), then its thumbnails ({@code thmb} references), then the gain map of its tone map item.
     *
     * <p>Only the images this decoder reconstructs are listed, with the size of their {@code ispe} property, or
     * of their grid; a file that shows an image sequence lists none.
     *
     * @param source the file
     * @param limits the bounds of the structure and the items
     * @return the images, indexed from 0
     * @throws MediaException.Malformed if the file structure is invalid or truncated
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static List<AuxiliaryImage> auxiliaryImages(MediaSource source, MediaLimits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        return auxiliaryGuarded(() -> List.copyOf(AvifFiles.auxiliaryImages(source, limits)));
    }

    /**
     * Decodes an auxiliary image of the primary image of an AVIF file under the
     * {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the file
     * @param index the index of the image in the list of {@link #auxiliaryImages(MediaSource)}
     * @return the decoded image
     * @throws IndexOutOfBoundsException if the file lists no image of that index
     * @throws MediaException.Malformed if the file or the image is invalid
     * @throws MediaException.Unsupported if the image uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #decodeAuxiliaryImage(MediaSource, int, ImageDecoderOptions)
     */
    public static DecodedImage decodeAuxiliaryImage(MediaSource source, int index) {
        return decodeAuxiliaryImage(source, index, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes an auxiliary image of the primary image of an AVIF file, as an image item decodes: a monochrome
     * depth map or matte to a monochrome Y'CbCr picture, its clean aperture applied and its rotation and mirroring
     * reported as the orientation, with the color of its own {@code colr} property and no EXIF or XMP. The pixels
     * keep their values; the color conversion of the options is ignored.
     *
     * @param source the file
     * @param index the index of the image in the list of {@link #auxiliaryImages(MediaSource, MediaLimits)}
     * @param options the limits and the bit depth choice
     * @return the decoded image
     * @throws IndexOutOfBoundsException if the file lists no image of that index
     * @throws MediaException.Malformed if the file or the image is invalid
     * @throws MediaException.Unsupported if the image uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decodeAuxiliaryImage(MediaSource source, int index, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var image = auxiliaryGuarded(() -> AvifFiles.decodeAuxiliaryImage(source, index, options));
        if (image == null) {
            throw new IndexOutOfBoundsException("the file lists no auxiliary image " + index);
        }
        return image;
    }

    /**
     * Runs an operation, turning unchecked failures into media exceptions.
     *
     * @param <T> the result type
     * @param operation the decoding to run
     * @return its result
     */
    private static <T> T auxiliaryGuarded(Supplier<T> operation) {
        try (var _ = ParallelBands.operation()) {
            return operation.get();
        } catch (MediaException exception) {
            throw exception;
        } catch (OutOfMemoryError error) {
            throw new MediaException.LimitExceeded("out of memory while decoding an AVIF file");
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("malformed AVIF file", exception);
        }
    }

    /**
     * Opens an AVIF file as an animation under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the file
     * @return the animation reader
     * @throws NullPointerException         if {@code source} is {@code null}
     * @throws MediaException.Malformed     if the file structure is invalid or truncated
     * @throws MediaException.Unsupported   if the file uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if the canvas or the frame count exceeds the limits
     * @throws MediaException.Io            if reading the source fails
     * @see #openAnimation(MediaSource, ImageDecoderOptions)
     */
    public static AnimationReader openAnimation(MediaSource source) {
        return openAnimation(source, ImageDecoderOptions.defaults());
    }

    /**
     * Opens a reader over the frames of an AVIF image sequence.
     *
     * <p>A file without a sequence is presented as an animation of its shown still image: one frame
     * shown for zero microseconds that plays once. Either reader can seek. Frames are RGBA pictures in the stored
     * orientation: {@link com.github.auties00.smalt.image.Rgba16Picture} pictures when
     * {@link ImageDecoderOptions#highBitDepth()} is set and the source has more than 8 bits,
     * {@link RgbaPicture} pictures otherwise.
     *
     * @param source  the file
     * @param options the limits and the bit depth choice; the reduced-size hint is ignored
     * @return the reader; closing it does not close {@code source}
     * @throws NullPointerException         if an argument is {@code null}
     * @throws MediaException.Malformed     if the file structure is invalid or truncated
     * @throws MediaException.Unsupported   if the file uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if the canvas or the frame count exceeds the limits
     * @throws MediaException.Io            if reading the source fails
     */
    public static AnimationReader openAnimation(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return AvifFiles.openAnimation(source, options);
        } catch (MediaException exception) {
            throw exception;
        } catch (OutOfMemoryError error) {
            throw new MediaException.LimitExceeded("out of memory while opening an AVIF animation");
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("malformed AVIF file", exception);
        }
    }

    /**
     * Opens the pages of a HEIF file under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the file
     * @return the reader, which the caller closes; closing it does not close {@code source}
     * @throws NullPointerException if {@code source} is {@code null}
     * @throws MediaException.Malformed if the file structure is invalid or truncated
     * @throws MediaException.Unsupported if the image the file shows has a coding no artifact at hand decodes
     * @throws MediaException.LimitExceeded if a declared size or the page count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #openPages(MediaSource, ImageDecoderOptions)
     */
    public static PageReader openPages(MediaSource source) {
        return openPages(source, ImageDecoderOptions.defaults());
    }

    /**
     * Opens the pages of a HEIF file: its master images, such as the images of a collection, of a burst or of a
     * stereo pair.
     *
     * <p>Page 0 is the image {@link #decode(MediaSource, ImageDecoderOptions)} returns. The other pages are the
     * master images of the file (ISO/IEC 23008-12) in item order: every image item that is not hidden, not a
     * thumbnail, not an auxiliary image (alpha planes, depth maps, gain maps), not an input of a grid, an identity
     * derivation, an overlay or a sample transform, not a tone map, and not the image page 0 shows. The items of an
     * alternatives group ({@code altr}) are versions of one image and make a single page: the first of them that
     * can be reconstructed, the group of the primary item being page 0. Each page decodes as {@link #decode}
     * decodes the primary image, with its own orientation, thumbnails and metadata; a page coded with HEVC, AVC,
     * JPEG, JPEG 2000 or as uncompressed samples decodes with the HEIC decoder of the {@code smalt-mpeg}
     * artifact, and raises {@link MediaException.Unsupported} naming that artifact when it is not present. The
     * image sequence of a file is not a page, unless page 0 is its first frame.
     *
     * <p>{@link PageReader#groups()} reports the other entity groups of the file with the pages of their entities
     * in group order: a stereo pair ({@code ster}) as the left view then the right view, a burst ({@code brst}),
     * time-synchronized captures ({@code tsyn}), albums ({@code albc}) and the other groups by their type.
     *
     * @param source the file
     * @param options the limits, the bit depth choice, the reduced-size hint and the color conversion, applied
     * to every page
     * @return the reader, which the caller closes; closing it does not close {@code source}
     * @throws NullPointerException if an argument is {@code null}
     * @throws MediaException.Malformed if the file structure is invalid or truncated
     * @throws MediaException.Unsupported if the image the file shows has a coding no artifact at hand decodes
     * @throws MediaException.LimitExceeded if a declared size or the page count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static PageReader openPages(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return HeifPages.open(source, options, ImageFormat.AVIF);
        } catch (MediaException exception) {
            throw exception;
        } catch (OutOfMemoryError error) {
            throw new MediaException.LimitExceeded("out of memory while opening the pages of an AVIF file");
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("malformed AVIF file", exception);
        }
    }
}
