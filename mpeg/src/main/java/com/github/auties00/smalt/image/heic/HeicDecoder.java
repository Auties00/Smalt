package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.AuxiliaryImage;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.heic.internal.HeicFiles;
import com.github.auties00.smalt.internal.region.RegionOutsideImageException;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A decoder of HEIC images (HEIF with HEVC-coded images, the photo format of many phones and
 * cameras) and of the other HEIF images: still images, grids, derived images, alpha and image
 * sequences.
 *
 * <p>The primary image decodes to its native planar representation: a {@link YuvPicture} in the
 * chroma layout of its payload (4:2:0, 4:2:2, 4:4:4, 4:4:0 for a JPEG item, or monochrome), with an alpha plane when
 * the file has an alpha auxiliary image or the payload carries alpha itself. RGB payloads (JPEG
 * 2000 and uncompressed items with red, green and blue components, and RGB-coded JPEG) decode to
 * 4:4:4 planes with the identity matrix, green in the luma plane and blue and red in the chroma
 * planes. Sources of 10 to 16 bits are reduced to 8 bits by rounding
 * to nearest, exactly as {@link com.github.auties00.smalt.video.hevc.HevcDecoder} reduces them; with
 * {@link ImageDecoderOptions#highBitDepth()} set they decode to a {@link Yuv16Picture} of their
 * own depth instead, which
 * {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit(Yuv16Picture)} reduces to
 * exactly the default 8-bit picture. An image overlay, and an image whose color is
 * premultiplied by its alpha ({@code prem} reference), decode to a straight-alpha
 * {@link RgbaPicture} instead, or, with {@link ImageDecoderOptions#highBitDepth()} and a source
 * of more than 8 bits, to a straight-alpha {@link com.github.auties00.smalt.image.Rgba16Picture}
 * (an overlay composed at 16 bits).
 *
 * <p>The file structure follows ISO/IEC 23008-12 (HEIF) and MIAF (ISO/IEC 23000-22):
 * <ul>
 *   <li>the primary item is a coded item, or a derived image. A coded item is HEVC-coded
 *       ({@code hvc1}) or AVC-coded ({@code avc1}, ISO/IEC 23008-12 Annex E), possibly predicted
 *       from other coded items ({@code pred} references); layered HEVC-coded ({@code lhv1}: the
 *       scalable or multiview layers of its target output layer set {@code tols}, showing the
 *       layer its {@code lsel} property selects or else the highest output layer, over the
 *       picture of the AVC-coded item it references with {@code exbl} when its base layer is coded
 *       outside it), which the file shows in place of the primary item when the alternatives
 *       group ({@code altr}) of the primary item lists it first and it can be reconstructed;
 *       JPEG-coded ({@code jpeg}, Annex H, its
 *       {@code jpgC} property preceding its data); JPEG 2000-coded ({@code j2k1}, ISO/IEC
 *       15444-16, with the channel definition, component mapping and palette of its {@code j2kH}
 *       property); or uncompressed ({@code unci}, ISO/IEC 23001-17, with its {@code uncC},
 *       {@code cmpd} and {@code cmpC} properties). JPEG and JPEG 2000 items decode with the codecs
 *       of the {@code smalt-image} artifact and raise {@link MediaException.Unsupported} when it is
 *       not present. A derived image is an image grid ({@code grid}),
 *       whose coded tiles decode in parallel on the module's shared helper threads, an identity
 *       derivation ({@code iden}) or an image overlay ({@code iovl}), nested to any depth up to
 *       eight;</li>
 *   <li>the alpha plane comes from an auxiliary item ({@code auxl} reference) whose {@code auxC}
 *       property carries {@code urn:mpeg:hevc:2015:auxid:1} or
 *       {@code urn:mpeg:mpegB:cicp:systems:auxiliary:alpha}: one item, a grid, or one item per
 *       grid tile; limited-range alpha is expanded to full range, alpha of another bit depth is
 *       rescaled to the color depth, and an alpha image of another size than its color image is
 *       resampled to it with the triangle filter
 *       ({@link com.github.auties00.smalt.image.ops.ResizeFilter#BILINEAR}). The other auxiliary
 *       images (depth maps, mattes, gain maps) are left out of the decoded image and listed by
 *       {@link #auxiliaryImages(MediaSource, MediaLimits)}; the gain map, of a {@code tmap} item or
 *       Apple's auxiliary image, decodes with {@link #decodeGainMap(MediaSource, ImageDecoderOptions)};</li>
 *   <li>the clean aperture ({@code clap}) is applied to the pixels; the rotation ({@code irot})
 *       and mirroring ({@code imir}) are reported as {@link DecodedImage#orientation()}, which
 *       the caller applies (for example with
 *       {@link com.github.auties00.smalt.image.ops.ImageOps#orient}), as for the EXIF
 *       orientation of other formats. The transformative properties are composed in their
 *       association order, and those of the inputs of derived images are applied to the
 *       inputs. A crop that starts inside a subsampled chroma block yields a 4:4:4 picture, so
 *       that no pixel changes color;</li>
 *   <li>the color description comes from the {@code nclx} color box, or without one from the
 *       video usability information of the HEVC or AVC stream or from the JPEG stream, with the
 *       MIAF defaults (sRGB primaries and transfer, BT.601 matrix, full range) for what neither
 *       specifies, together with the {@code clli} and {@code mdcv} properties (or the SEI messages
 *       of the stream); RGB payloads keep the identity matrix and full range whatever the
 *       {@code nclx} box says, and uncompressed components of fewer than 8 bits are rescaled to 8
 *       bits; an ICC
 *       profile ({@code colr} of type {@code prof} or {@code rICC}) is returned with the picture
 *       and as its {@link DecodedImage#colorProfile()}, which otherwise describes the
 *       {@code nclx} color when it is not sRGB; the EXIF item and the XMP item ({@code mime} item
 *       of type {@code application/rdf+xml}) of the primary image are returned too.</li>
 * </ul>
 * The EXIF orientation is not used: HEIF orients images with {@code irot} and {@code imir}.
 *
 * <p>With a reduced-size hint ({@link ImageDecoderOptions#ofMinimumSize(int, int)}), the smallest
 * thumbnail item ({@code thmb} reference) that is at least that large and shows the primary image
 * in the same orientation and aspect ratio is decoded instead of the primary image; the
 * {@link DecodedImage#sourceWidth()} and {@link DecodedImage#sourceHeight()} still give the size
 * of the primary image. Without a suitable thumbnail the hint is ignored. With
 * {@link ImageDecoderOptions#convertToSrgb()} the pixels are converted to sRGB.
 *
 * <p>An image sequence ({@code msf1}) of HEVC or AVC tracks plays through {@link #openAnimation}:
 * its color track and its alpha track, every frame converted to RGBA (16-bit RGBA when
 * {@link ImageDecoderOptions#highBitDepth()} is set and the sequence has more than 8 bits), with
 * the loop count of the edit list (a track
 * without an edit list loops forever); a picture the edit list does not show is skipped. The
 * reader seeks: it restarts the decoding at the last sync sample at or before the requested time
 * and decodes up to the picture shown then, which is the picture a decode from the start shows.
 * {@link #decode} returns the primary image, or the first frame of the sequence of a file without
 * a primary item; {@link #decodeStereoView} returns either view of the stereo pair the primary
 * image belongs to.
 *
 * <p>Every size the file declares is checked against the {@link MediaLimits} of the options
 * before the corresponding allocation, and the pictures decoded for one image total at most four
 * times the pixel limit. Malformed input raises {@link MediaException.Malformed}, never an
 * unchecked runtime exception; a valid file using a feature this decoder lacks (an essential
 * property it does not know, an image coded with another codec) raises
 * {@link MediaException.Unsupported}; the README lists the unsupported features of JPEG 2000 and
 * uncompressed items. HEVC items of every range extension
 * format decode, such as the 10-bit 4:2:2 grids of camera {@code .hif} files and 4:4:4 images,
 * those coded as separate color planes and with the screen content coding tools included; a
 * picture whose luma and chroma depths differ keeps the larger depth under
 * {@link ImageDecoderOptions#highBitDepth()}, the shallower planes shifted left to it, as
 * {@link com.github.auties00.smalt.video.hevc.HevcDecoder} outputs it.
 *
 * <p>This class is thread-safe.
 *
 * @implNote This implementation reads the container leniently, accepting transformative
 * properties not marked essential and an overlay that names one input twice, as HEIF writers
 * produce them. Each grid tile decodes on its own decoding state, so tiles never depend on one
 * another.
 */
public final class HeicDecoder {
    /**
     * Prevents instantiation.
     */
    private HeicDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes a HEIC file from its headers, without decoding pixels, within the
     * {@linkplain MediaLimits#DEFAULT default limits}.
     *
     * <p>The size is that of the shown image after the clean aperture and before the
     * orientation; for a sequence it is the track size, and the frame count is the sample count.
     *
     * @param source the file
     * @return the summary
     * @throws MediaException.Malformed if the file structure is invalid or truncated
     * @throws MediaException.Unsupported if the file uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the
     * {@linkplain MediaLimits#DEFAULT default limits}
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        return guarded("probing a HEIC file", () -> HeicFiles.probe(source, MediaLimits.DEFAULT));
    }

    /**
     * Decodes the image a HEIC file shows with the {@linkplain ImageDecoderOptions#defaults() default}
     * options.
     *
     * @param source the file
     * @return the decoded image
     * @throws MediaException.Malformed if the file is invalid, corrupt or truncated
     * @throws MediaException.Unsupported if the file uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the image a HEIC file shows: its primary image, one of its thumbnails under a
     * reduced-size hint, or the first frame of its sequence.
     *
     * <p>With a {@linkplain ImageDecoderOptions#region() region}, a primary image that is a grid of coded images
     * decodes only the grid images the region intersects, reading only their data, and the limits bound those
     * rather than the whole grid; a thumbnail, a sequence frame and any other primary image decode whole and are
     * cropped.
     *
     * @param source the file
     * @param options the limits, the bit depth choice, the reduced-size hint, the region and the color
     * conversion
     * @return the decoded image with its orientation, ICC profile, color profile, EXIF block and XMP
     * packet
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the file is invalid, corrupt or truncated
     * @throws MediaException.Unsupported if the file uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return guarded("decoding a HEIC file", () -> HeicFiles.decode(source, options));
    }

    /**
     * Decodes the gain map of the primary image of a HEIC file under the {@link ImageDecoderOptions#defaults()}.
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
     * Decodes the gain map of the primary image of a HEIC file.
     *
     * <p>The gain map is the second input of the tone map derived image item ({@code tmap}, ISO 21496-1 metadata)
     * whose first input is the primary item and that an alternatives group ({@code altr}) prefers to it, as for
     * AVIF files but without requiring the {@code tmap} brand; its {@code colr} and {@code clli} properties describe
     * the alternate rendition. Without one, Apple's gain map auxiliary image
     * ({@code urn:com:apple:photo:2020:aux:hdrgainmap}) of an image whose XMP declares
     * {@code HDRGainMap:HDRGainMapVersion} is converted to the ISO 21496-1 model: its BT.709-encoded samples
     * {@code g} scale the linear base as {@code 1 + (headroom - 1) * g}, the headroom coming from the tags 33 and 48
     * of the Apple maker note of the EXIF item (or the {@code HDRGainMap:HDRGainMapHeadroom} XMP property), as
     * Apple's "Applying Apple HDR effect to your photos" describes; the converted gain map is a 16-bit gray picture
     * of {@code log2(1 + (headroom - 1) * g) / log2(headroom)}, whose log2 gains range from 0 to
     * {@code log2(headroom)}, without offsets.
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
        return auxiliaryGuarded(() -> HeicFiles.decodeGainMap(source, options));
    }

    /**
     * Lists the auxiliary images of the primary image of a HEIC file within the
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
     * Lists the auxiliary images of the primary image of a HEIC file: its auxiliary items ({@code auxl}
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
        return auxiliaryGuarded(() -> List.copyOf(HeicFiles.auxiliaryImages(source, limits)));
    }

    /**
     * Decodes an auxiliary image of the primary image of a HEIC file under the
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
     * Decodes an auxiliary image of the primary image of a HEIC file, as an image item decodes: a monochrome
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
        var image = auxiliaryGuarded(() -> HeicFiles.decodeAuxiliaryImage(source, index, options));
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
            throw new MediaException.LimitExceeded("out of memory while decoding a HEIC file");
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("malformed HEIC file", exception);
        }
    }

    /**
     * Decodes one view of the stereo pair of a HEIC file with the
     * {@linkplain ImageDecoderOptions#defaults() default} options.
     *
     * @param source the file
     * @param view 0 for the left view, 1 for the right view
     * @return the decoded view, or empty when the image the file shows belongs to no stereo pair
     * @throws IllegalArgumentException if {@code view} is neither 0 nor 1
     * @throws MediaException.Malformed if the file is invalid, corrupt or truncated
     * @throws MediaException.Unsupported if the view uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #decodeStereoView(MediaSource, int, ImageDecoderOptions)
     */
    public static Optional<DecodedImage> decodeStereoView(MediaSource source, int view) {
        return decodeStereoView(source, view, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes one view of the stereo pair of a HEIC file, such as the two eyes of a spatial photo
     * or of a multiview (MV-HEVC) image.
     *
     * <p>The pair is the stereo pair entity group ({@code ster}, ISO/IEC 23008-12) that holds the
     * primary image, or the image the file shows in its place; it lists the left view, then the
     * right view, each an image item of any kind {@link #decode} reconstructs, such as a grid or a
     * layered HEVC item ({@code lhv1}) whose layer selector names one view of a multiview stream.
     * The view decodes as {@link #decode} decodes the primary image, with the view's own
     * orientation, thumbnails and metadata, and the region of the options applies to it.
     *
     * @param source the file
     * @param view 0 for the left view, 1 for the right view
     * @param options the limits, the bit depth choice, the reduced-size hint, the region and the color
     * conversion
     * @return the decoded view with its orientation, ICC profile, color profile, EXIF block and XMP
     * packet, or empty when the image the file shows belongs to no stereo pair
     * @throws IllegalArgumentException if {@code view} is neither 0 nor 1, or the region does not lie inside the
     * view
     * @throws MediaException.Malformed if the file is invalid, corrupt or truncated
     * @throws MediaException.Unsupported if the view uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static Optional<DecodedImage> decodeStereoView(MediaSource source, int view, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        if (view != 0 && view != 1) {
            throw new IllegalArgumentException("stereo view must be 0 (left) or 1 (right): " + view);
        }
        return guarded("decoding a HEIC stereo view", () -> HeicFiles.decodeStereoView(source, view, options));
    }

    /**
     * Opens a reader over the frames of a HEIC image sequence with the
     * {@linkplain ImageDecoderOptions#defaults() default} options.
     *
     * @param source the file
     * @return the reader; closing it does not close {@code source}
     * @throws MediaException.Malformed if the file structure is invalid or truncated
     * @throws MediaException.Unsupported if the file uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if the canvas or the frame count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #openAnimation(MediaSource, ImageDecoderOptions)
     */
    public static AnimationReader openAnimation(MediaSource source) {
        return openAnimation(source, ImageDecoderOptions.defaults());
    }

    /**
     * Opens a reader over the frames of a HEIC image sequence.
     *
     * <p>A file without a sequence is presented as an animation of its primary image: one frame
     * shown for zero microseconds that plays once. Either reader can seek. Frames are RGBA pictures
     * in the stored orientation: {@link com.github.auties00.smalt.image.Rgba16Picture} pictures when
     * {@link ImageDecoderOptions#highBitDepth()} is set and the source has more than 8 bits,
     * {@link RgbaPicture} pictures otherwise.
     *
     * @param source the file
     * @param options the limits and the bit depth choice; the reduced-size hint and the color
     * conversion are ignored
     * @return the reader; closing it does not close {@code source}
     * @throws MediaException.Malformed if the file structure is invalid or truncated
     * @throws MediaException.Unsupported if the file uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if the canvas or the frame count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static AnimationReader openAnimation(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return guarded("opening a HEIC animation", () -> HeicFiles.openAnimation(source, options.limits(),
                options.highBitDepth()));
    }

    /**
     * Runs one operation on the shared pool and translates its failures: a {@link MediaException}
     * passes through, running out of memory becomes {@link MediaException.LimitExceeded}, and any
     * other runtime exception becomes {@link MediaException.Malformed}.
     *
     * @param action the operation, for the out-of-memory message, such as {@code "decoding a HEIC file"}
     * @param operation computes the result
     * @param <T> the type of the result
     * @return the result of the operation
     */
    private static <T> T guarded(String action, Supplier<T> operation) {
        try (var _ = ParallelBands.operation()) {
            return operation.get();
        } catch (MediaException exception) {
            throw exception;
        } catch (OutOfMemoryError error) {
            throw new MediaException.LimitExceeded("out of memory while " + action);
        } catch (RegionOutsideImageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("malformed HEIC file", exception);
        }
    }
}
