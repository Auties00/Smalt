package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.jp2.internal.Jp2Pictures;
import com.github.auties00.smalt.image.jp2.internal.Jp2Reader;
import com.github.auties00.smalt.image.jp2.internal.JpxAnimation;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.anim.SeekableAnimationReader;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;
import java.util.Optional;

/**
 * Decodes JPEG 2000 images (ISO/IEC 15444-1, the extensions of ISO/IEC 15444-2 and the high-throughput block coder of
 * ISO/IEC 15444-15, HTJ2K): JP2, JPX and JPH files and raw codestreams.
 *
 * <p>Supported:
 * <ul>
 *   <li>the JP2 file format and the JPX file format of ISO/IEC 15444-2 annex M: the image header, color
 *       specifications by enumeration (with the enumerated parameters of CIE L*a*b* and CIECAM97s J*a*b*), ICC
 *       profile or H.273 parameters, chosen by precedence; palettes with component mappings, channel definitions with
 *       opacity and premultiplied opacity, opacity boxes with chroma keys; codestreams stored contiguously or in
 *       fragment tables within the file; several codestreams, their codestream header boxes and compositing layer
 *       header boxes, with codestream registration and cross-reference boxes; the composition box, rendered as an
 *       animation; EXIF and XMP metadata in {@code uuid} and {@code xml } boxes; and raw codestreams;</li>
 *   <li>the whole of Part 1 coding: any number of tiles and tile-parts, 1 to 16384 components with
 *       any subsampling, precisions of 1 to 38 bits, signed or unsigned, all five progression
 *       orders and progression order changes, user precinct sizes, packed packet headers
 *       ({@code PPM} and {@code PPT}), {@code SOP} and {@code EPH} markers, every code-block size
 *       and style (arithmetic coding bypass, context reset, termination on each pass, vertically
 *       causal contexts, predictable termination and segmentation symbols), no quantization and
 *       scalar derived or expounded quantization, the reversible 5-3 and irreversible 9-7
 *       wavelets with up to 32 decomposition levels, the reversible and irreversible component
 *       transforms, regions of interest coded with the maxshift method, and reference grids of up to 2^32
 *       points;</li>
 *   <li>the Part 2 coding extensions that change the decoded samples: variable DC offsets ({@code DCO}),
 *       arbitrary decompositions ({@code DFS} and {@code ADS}), arbitrary wavelet kernels ({@code ATK}),
 *       array-based and wavelet-based multiple component transformations with any number of stages
 *       ({@code MCT}, {@code MCC}, {@code MCO} and {@code CBD}), non-linear point transformations
 *       ({@code NLT}), and rectangular and elliptic regions of interest coded with the scaling method
 *       ({@code RGN});</li>
 *   <li>the whole of HTJ2K coding: HT code-blocks with their cleanup, SigProp and MagRef passes,
 *       placeholder passes and any number of HT sets (the last one is decoded), HT and ISO/IEC
 *       15444-1 code-blocks mixed in one tile-component, regions of interest, and JPH files,
 *       including their parameterized color specification for full-range R'G'B' samples;</li>
 *   <li>decoding at a reduced resolution, which discards whole resolution levels and so decodes
 *       large images several times faster, and decoding the first quality layers only.</li>
 * </ul>
 *
 * <p>{@link #decodeChannels(MediaSource, Jp2DecoderOptions)} returns the samples of each channel of one compositing
 * layer at their own precision and subsampling, for applications that need the stored values (medical, geospatial or
 * scientific images). {@link #decode(MediaSource, ImageDecoderOptions)} returns a picture: gray, RGB, CMY, CMYK,
 * YCbCr, YPbPr, PhotoYCC, YCCK, CIE L*a*b* or CIECAM97s J*a*b* channels and an opacity channel converted to RGBA,
 * scaled to 8 bits, or to 16 bits when {@link ImageDecoderOptions#highBitDepth()} is set and a channel is wider than 8
 * bits. A file whose composition box shows a compositing layer decodes to the first frame of its composition, and any
 * other file to its first compositing layer. An enumerated color space, or an ICC profile, is reported through
 * {@link DecodedImage#colorProfile()} and applied when {@link ImageDecoderOptions#convertToSrgb()} is set. With
 * {@link ImageDecoderOptions#keepChannels()} set (and {@code convertToSrgb} not set), a CMYK image decodes to a
 * {@link com.github.auties00.smalt.image.ChannelPicture ChannelPicture} of the
 * {@link com.github.auties00.smalt.image.PixelLayout#CMYK CMYK} layout instead, followed by an alpha channel (straight
 * or premultiplied as the channel definitions declare) when it has an opacity channel: the inks keep their stored
 * meaning, 0 being no ink, and are scaled to 8 bits, or to 16 bits as for the RGBA output; its color profile is the
 * embedded profile when that is a four-channel one. {@link #openPages(MediaSource, ImageDecoderOptions)} decodes each
 * compositing layer of a JPX file as a page, and {@link #openAnimation(MediaSource, ImageDecoderOptions)} renders its
 * composition.
 *
 * <p>Unsupported, and rejected with {@link MediaException.Unsupported} naming the feature: HTJ2K
 * code-blocks of sub-bands with more than 30 magnitude bit-planes; the Part 2 coding extensions of variable deadzone
 * and trellis coded quantization, visual masking, single sample overlap transformations, precinct-dependent
 * quantization, code-block anchor points and block coder extensions; derived quantization of sub-bands split in one
 * direction only; and codestream fragments stored in another file.
 *
 * <p>Damaged codestreams decode as far as their data goes: a truncated tile leaves its missing
 * code-block data at zero, and a missing tile is uniformly mid-gray. An invalid main header, or an
 * HTJ2K code-block whose segments are not a valid HT set, is {@link MediaException.Malformed}. The
 * decoded samples are bit-identical to OpenJPEG's {@code opj_decompress} for both wavelets and both
 * block coders, wherever OpenJPEG decodes the codestream (it decodes no HTJ2K placeholder passes, second HT sets, mixed
 * block coders, regions of interest or Part 2 extensions), as built without fast floating-point optimizations: the
 * irreversible wavelet is float arithmetic, which {@code -ffast-math} and fused multiply-adds reorder.
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
     * <p>A file whose composition box shows a compositing layer reports the size of its render area, whether the
     * composition is an animation (more than one frame) and its frame count; any other file reports the size of its
     * first compositing layer and the number of compositing layers as the frame count.
     *
     * @param source the input
     * @return the summary
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
                var exif = headers.boxes().exif();
                var orientation = exif == null ? Orientation.NORMAL : ExifReader.orientation(exif);
                var frames = frames(headers, MediaLimits.DEFAULT);
                if (frames > 0) {
                    var composition = headers.boxes().composition();
                    if (composition.width() > Integer.MAX_VALUE || composition.height() > Integer.MAX_VALUE) {
                        throw new MediaException.LimitExceeded("JPEG 2000 render area of " + composition.width()
                                + "x" + composition.height());
                    }
                    return new ImageInfo(ImageFormat.JP2, (int) composition.width(), (int) composition.height(),
                            true, frames > 1, frames, orientation);
                }
                var size = headers.size(0);
                return new ImageInfo(ImageFormat.JP2, size[0], size[1], headers.hasAlpha(), false,
                        headers.boxes().layerCount(), orientation);
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
     * Decodes a JPEG 2000 image to a picture: the first frame of the composition of a file whose composition box
     * shows a compositing layer, otherwise the first compositing layer.
     *
     * <p>When the options ask for a minimum size, a compositing layer is decoded at the smallest reduced
     * resolution, {@code 2^r} times smaller with each dimension rounded up, that still covers it,
     * discarding the resolution levels above it; the result reports the full size through
     * {@link DecodedImage#sourceWidth()} and {@link DecodedImage#sourceHeight()}. A composition is rendered at the
     * full size of its render area.
     *
     * @param source the input
     * @param options the limits, the size hint, the bit depth choice and the sRGB conversion
     * @return the decoded image: an {@link RgbaPicture}, or an {@link Rgba16Picture} when
     * {@link ImageDecoderOptions#highBitDepth()} is set and a channel is wider than 8 bits, or for a
     * CMYK image decoded with {@link ImageDecoderOptions#keepChannels()} a channel picture, as the
     * class description states
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
                if (frames(headers, options.limits()) > 0) {
                    var animation = animation(headers, options);
                    var frame = animation.read();
                    var exif = Optional.ofNullable(headers.boxes().exif());
                    var orientation = exif.isEmpty() ? Orientation.NORMAL : ExifReader.orientation(exif.get());
                    var profile = options.convertToSrgb() ? Optional.of(ColorProfile.srgb())
                            : layerProfile(headers, 0);
                    return new DecodedImage(frame.picture(), ImageFormat.JP2, orientation, animation.width(),
                            animation.height(), Optional.empty(), exif, Optional.ofNullable(headers.boxes().xmp()),
                            profile);
                }
                return layer(headers, 0, options, true);
            });
        }
    }

    /**
     * Decodes one compositing layer to a picture.
     *
     * @param headers the file's headers
     * @param index the layer index
     * @param options the decoding options
     * @param sized whether the options' size hint applies
     * @return the decoded layer
     */
    private static DecodedImage layer(Jp2Reader.Headers headers, int index, ImageDecoderOptions options,
                                      boolean sized) {
        var size = headers.size(index);
        var reduction = sized ? reduction(size[0], size[1], options.minWidth(), options.minHeight()) : 0;
        var image = Jp2Reader.decode(headers, index, reduction, 0, options.limits());
        var exif = image.exif();
        var orientation = exif.isEmpty() ? Orientation.NORMAL : ExifReader.orientation(exif.get());
        if (options.keepChannels() && !options.convertToSrgb()) {
            var channels = Jp2Pictures.toChannels(image, options.highBitDepth(), options.limits());
            if (channels != null) {
                return new DecodedImage(channels, ImageFormat.JP2, orientation, image.fullWidth(),
                        image.fullHeight(), image.iccProfile(), exif, image.xmp(),
                        profile(image).filter(profile -> profile.channels() == 4));
            }
        }
        var picture = Jp2Pictures.toPicture(image, options.highBitDepth(), options.limits());
        var decoded = new DecodedImage(picture, ImageFormat.JP2, orientation, image.fullWidth(),
                image.fullHeight(), image.iccProfile(), exif, image.xmp(), profile(image));
        return options.convertToSrgb() ? ImageOps.toSrgb(decoded) : decoded;
    }

    /**
     * Returns the color profile of a compositing layer's pixels without decoding it.
     *
     * @param headers the file's headers
     * @param index the layer index
     * @return the profile, as {@link #profile(Jp2Image)} chooses it
     */
    private static Optional<ColorProfile> layerProfile(Jp2Reader.Headers headers, int index) {
        var layer = headers.boxes().layer(index);
        if (layer.icc() != null) {
            try {
                return Optional.of(ColorProfile.parse(layer.icc()));
            } catch (MediaException exception) {
                return Optional.empty();
            }
        }
        return Jp2Pictures.profile(Jp2ColorSpace.of(layer.enumerated()));
    }

    /**
     * Counts the frames the composition of a file renders.
     *
     * @param headers the file's headers
     * @param limits the limits bounding the frame count and the instructions executed
     * @return the number of frames, 0 without a composition box or when no instruction shows a layer
     */
    private static int frames(Jp2Reader.Headers headers, MediaLimits limits) {
        var composition = headers.boxes().composition();
        return composition == null ? 0 : JpxAnimation.countFrames(composition, headers.boxes().layerCount(), limits);
    }

    /**
     * Opens the composition of a file as an animation, or the first compositing layer as a one-frame animation when
     * the file has no composition box or its composition shows no layer.
     *
     * @param headers the file's headers
     * @param options the decoding options
     * @return the animation
     */
    private static JpxAnimation animation(Jp2Reader.Headers headers, ImageDecoderOptions options) {
        var boxes = headers.boxes();
        var wide = false;
        if (options.highBitDepth()) {
            for (var i = 0; i < boxes.layerCount() && !wide; i++) {
                wide = headers.wide(i);
            }
        }
        var layerOptions = options.toBuilder().keepChannels(false).fullSize().build();
        var composition = frames(headers, options.limits()) > 0 ? boxes.composition() : null;
        return new JpxAnimation(composition, boxes.layerCount(), headers.size(0), wide,
                index -> JpxAnimation.Layer.of(layer(headers, index, layerOptions, false).picture()),
                options.limits());
    }

    /**
     * Decodes the channels of a JPEG 2000 image at full resolution under
     * {@link Jp2DecoderOptions#defaults()}.
     *
     * @param source the input
     * @return the channels of the first compositing layer
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
     * Decodes the channels of a compositing layer of a JPEG 2000 image, keeping each channel's precision, signedness
     * and subsampling.
     *
     * @param source the input
     * @param options the limits, the reduction, the number of quality layers and the compositing layer
     * @return the channels
     * @throws IndexOutOfBoundsException if the file has no compositing layer of the index the options ask for
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
            var headers = Guards.malformed(WHAT, () -> Jp2Reader.headers(
                    SourceBytes.readAll(source, options.limits(), true), options.limits()));
            Objects.checkIndex(options.compositingLayer(), headers.boxes().layerCount());
            return Guards.malformed(WHAT, () -> Jp2Reader.decode(headers, options.compositingLayer(),
                    options.reduction(), options.qualityLayers().orElse(0), options.limits()));
        }
    }

    /**
     * Opens the compositing layers of a JPEG 2000 file as pages under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read whole when the reader opens
     * @return the page reader
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file stores its codestreams in a way listed on the class as
     * unsupported
     * @throws MediaException.LimitExceeded if the layer count exceeds the default frame limit
     * @throws MediaException.Io if reading the source fails
     * @see #openPages(MediaSource, ImageDecoderOptions)
     */
    public static PageReader openPages(MediaSource source) {
        return openPages(source, ImageDecoderOptions.defaults());
    }

    /**
     * Opens the compositing layers of a JPEG 2000 file as pages, in layer order: one page for a JP2 file or a raw
     * codestream, one per compositing layer of a JPX file.
     *
     * <p>Each page decodes its layer at full size, as {@link #decode(MediaSource, ImageDecoderOptions)} decodes the
     * first layer of a file without a composition box: the size hint of {@code options} is ignored.
     *
     * @param source the input, read whole when the reader opens
     * @param options the limits, the bit depth choice, the channel choice and the sRGB conversion applied to every
     * page
     * @return the page reader
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file stores its codestreams in a way listed on the class as
     * unsupported
     * @throws MediaException.LimitExceeded if the layer count exceeds the frame limit
     * @throws MediaException.Io if reading the source fails
     */
    public static PageReader openPages(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        Jp2Reader.Headers headers;
        try (var _ = ParallelBands.operation()) {
            headers = Guards.malformed(WHAT, () -> Jp2Reader.headers(
                    SourceBytes.readAll(source, options.limits(), true), options.limits()));
        }
        var count = headers.boxes().layerCount();
        return new PageReader() {
            @Override
            public int pageCount() {
                return count;
            }

            @Override
            public ImageInfo probe(int index) {
                Objects.checkIndex(index, count);
                return Guards.malformed(WHAT, () -> {
                    var size = headers.size(index);
                    var exif = headers.boxes().exif();
                    var orientation = exif == null ? Orientation.NORMAL : ExifReader.orientation(exif);
                    return new ImageInfo(ImageFormat.JP2, size[0], size[1], headers.hasAlpha(index), false, count,
                            orientation);
                });
            }

            @Override
            public DecodedImage read(int index) {
                Objects.checkIndex(index, count);
                try (var _ = ParallelBands.operation()) {
                    return Guards.malformed(WHAT, () -> layer(headers, index, options, false));
                }
            }
        };
    }

    /**
     * Opens the composition of a JPEG 2000 file as an animation under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read whole when the reader opens
     * @return the animation reader
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file stores its codestreams in a way listed on the class as
     * unsupported
     * @throws MediaException.LimitExceeded if the render area or the frame count exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     * @see #openAnimation(MediaSource, ImageDecoderOptions)
     */
    public static AnimationReader openAnimation(MediaSource source) {
        return openAnimation(source, ImageDecoderOptions.defaults());
    }

    /**
     * Opens the composition of a JPX file (ISO/IEC 15444-2 M.5.3) as an animation; a file without a composition box,
     * or whose composition shows no compositing layer, is a one-frame animation of its first compositing layer.
     *
     * <p>The instructions run in order, each instruction set as many times as it repeats, until no instruction or no
     * compositing layer is left; a set that repeats indefinitely stops after the first of its executions that
     * assigns no compositing layer that no earlier instruction was assigned, so that a set that only reuses its layers
     * ends; each layer is cropped, rotated, scaled to the instruction's size by taking the sample
     * covering each rendered sample's center, and composited over the render area, which starts fully transparent.
     * A frame is returned for each instruction of non-zero life, after the instructions of zero life before it; its
     * duration is that life in timer ticks. {@link AnimationReader#loopCount()} is the global repetition count plus
     * one, or {@code 0} when the composition repeats indefinitely. Frames are 16-bit when
     * {@link ImageDecoderOptions#highBitDepth()} is set and a layer has a channel wider than 8 bits. The reader can
     * seek: a seek backwards renders again from the first instruction.
     *
     * @param source the input, read whole when the reader opens
     * @param options the limits, the bit depth choice and the sRGB conversion applied to every layer; the size hint
     * is ignored
     * @return the animation reader
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file stores its codestreams in a way listed on the class as
     * unsupported
     * @throws MediaException.LimitExceeded if the render area or the frame count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static AnimationReader openAnimation(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            var headers = Guards.malformed(WHAT, () -> Jp2Reader.headers(
                    SourceBytes.readAll(source, options.limits(), true), options.limits()));
            return SeekableAnimationReader.of(() -> {
                var animation = Guards.malformed(WHAT, () -> animation(headers, options));
                return new AnimationReader() {
                    @Override
                    public int width() {
                        return animation.width();
                    }

                    @Override
                    public int height() {
                        return animation.height();
                    }

                    @Override
                    public int loopCount() {
                        return animation.loopCount();
                    }

                    @Override
                    public int frameCount() {
                        return animation.frameCount();
                    }

                    @Override
                    public AnimationFrame read() {
                        try (var _ = ParallelBands.operation()) {
                            return Guards.malformed(WHAT, animation::read);
                        }
                    }
                };
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
     * @return the embedded profile, or the profile {@code Jp2Pictures} converts the enumerated color space to, or
     * empty
     */
    private static Optional<ColorProfile> profile(Jp2Image image) {
        if (image.iccProfile().isPresent()) {
            try {
                return Optional.of(ColorProfile.parse(image.iccProfile().get()));
            } catch (MediaException exception) {
                return Optional.empty();
            }
        }
        return Jp2Pictures.profile(image.colorSpace());
    }
}
