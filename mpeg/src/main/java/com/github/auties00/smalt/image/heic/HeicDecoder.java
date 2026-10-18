package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.heic.internal.HeicFiles;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A decoder of HEIC images (HEIF with HEVC-coded images, the photo format of many phones and
 * cameras): still images, grids, derived images, alpha and image sequences.
 *
 * <p>The primary image decodes to its native planar representation: a {@link YuvPicture} in the
 * chroma layout of its HEVC payload (4:2:0, 4:2:2, 4:4:4 or monochrome), with an alpha plane when
 * the file has an alpha auxiliary image. Sources of 10 to 16 bits are reduced to 8 bits by rounding
 * to nearest, exactly as {@link com.github.auties00.smalt.video.hevc.HevcDecoder} reduces them; with
 * {@link ImageDecoderOptions#highBitDepth()} set they decode to a {@link Yuv16Picture} of their
 * own depth instead, which
 * {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit(Yuv16Picture)} reduces to
 * exactly the default 8-bit picture. An image overlay, and an image whose color is
 * premultiplied by its alpha ({@code prem} reference), decode to a straight-alpha
 * {@link RgbaPicture} instead.
 *
 * <p>The file structure follows ISO/IEC 23008-12 (HEIF) and MIAF (ISO/IEC 23000-22):
 * <ul>
 *   <li>the primary item is an HEVC-coded item ({@code hvc1}), possibly predicted from other
 *       coded items ({@code pred} references), or a derived image: an image grid ({@code grid}),
 *       whose coded tiles decode in parallel on the module's shared helper threads, an identity
 *       derivation ({@code iden}) or an image overlay ({@code iovl}), nested to any depth up to
 *       eight;</li>
 *   <li>the alpha plane comes from an auxiliary item ({@code auxl} reference) whose {@code auxC}
 *       property carries {@code urn:mpeg:hevc:2015:auxid:1} or
 *       {@code urn:mpeg:mpegB:cicp:systems:auxiliary:alpha}: one item, a grid, or one item per
 *       grid tile; limited-range alpha is expanded to full range. Depth maps, HDR gain maps and
 *       the other auxiliary images are ignored;</li>
 *   <li>the clean aperture ({@code clap}) is applied to the pixels; the rotation ({@code irot})
 *       and mirroring ({@code imir}) are reported as {@link DecodedImage#orientation()}, which
 *       the caller applies (for example with
 *       {@link com.github.auties00.smalt.image.ops.ImageOps#orient}), as for the EXIF
 *       orientation of other formats. The transformative properties are composed in their
 *       association order, and those of the inputs of derived images are applied to the
 *       inputs. A crop that starts inside a subsampled chroma block yields a 4:4:4 picture, so
 *       that no pixel changes color;</li>
 *   <li>the color description comes from the {@code nclx} color box, or from the video
 *       usability information of the HEVC stream without one, with the MIAF defaults (sRGB
 *       primaries and transfer, BT.601 matrix, full range) for what neither specifies, together
 *       with the {@code clli} and {@code mdcv} properties (or the HEVC SEI messages); an ICC
 *       profile ({@code colr} of type {@code prof} or {@code rICC}) is returned with the picture
 *       and as its {@link DecodedImage#colorProfile()}, which otherwise describes the
 *       {@code nclx} color when it is not sRGB; the EXIF item of the primary image is returned
 *       too.</li>
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
 * <p>An image sequence ({@code msf1}) plays through {@link #openAnimation}: its color track and
 * its alpha track, every frame converted to RGBA, with the loop count of the edit list (a track
 * without an edit list loops forever); a picture the edit list does not show is skipped.
 * {@link #decode} returns the primary image, or the first frame of the sequence of a file without
 * a primary item.
 *
 * <p>Every size the file declares is checked against the {@link MediaLimits} of the options
 * before the corresponding allocation, and the pictures decoded for one image total at most four
 * times the pixel limit. Malformed input raises {@link MediaException.Malformed}, never an
 * unchecked runtime exception; a valid file using a feature this decoder lacks (an essential
 * property it does not know, an image coded with another codec, an alpha plane of another size
 * than the color) raises {@link MediaException.Unsupported}. HEVC items of every range extension
 * format decode, such as the 10-bit 4:2:2 grids of camera {@code .hif} files and 4:4:4 images.
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
     * @param source the file
     * @param options the limits, the bit depth choice, the reduced-size hint and the color
     * conversion
     * @return the decoded image with its orientation, ICC profile, color profile and EXIF block
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
     * shown for zero microseconds that plays once. Frames are RGBA pictures in the stored
     * orientation.
     *
     * @param source the file
     * @param options the limits; the reduced-size hint, the bit depth choice and the color
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
        return guarded("opening a HEIC animation", () -> HeicFiles.openAnimation(source, options.limits()));
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
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("malformed HEIC file", exception);
        }
    }
}
